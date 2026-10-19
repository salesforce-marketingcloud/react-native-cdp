import Foundation

#if CONSENT_CORE_TESTS
enum Consent {
  case optIn
  case optOut
  case pending
  case none
}
#else
import React
import SFMCSDK
#endif

/// Maximum time a bridge operation waits for the native Data Cloud SDK to become ready.
let dataCloudConsentReadinessTimeout: TimeInterval = 10

/// Converts a native consent value to the string exposed by the React Native API.
///
/// Native pending or unset consent values are both represented as `"pending"`.
func consentString(for consent: Consent) -> String {
  switch consent {
  case .optIn: "opt_in"
  case .optOut: "opt_out"
  case .pending, .none: "pending"
  @unknown default: "pending"
  }
}

/// Converts a writable React Native consent value to its native representation.
///
/// - Parameter value: The bridge value. Only `"opt_in"` and `"opt_out"` are writable.
/// - Returns: The corresponding native consent, or `nil` when the value is invalid.
func writableConsent(for value: String) -> Consent? {
  switch value {
  case "opt_in": .optIn
  case "opt_out": .optOut
  default: nil
  }
}

/// Process-wide serialization boundary for synchronous native consent access.
///
/// React Native can briefly retain an old module while constructing its replacement. Sharing this
/// queue prevents those module instances from concurrently calling the process-wide CDP SDK. FIFO
/// is guaranteed only within one coordinator; separate coordinators are serialized but not ordered.
enum NativeConsentExecutor {
  static let queue = DispatchQueue(label: "com.salesforce.datacloud.consent.native")
}

/// Coordinates one React Native module instance's consent operations.
///
/// The coordinator registers for SDK readiness once, closes the native registration race with a
/// post-registration readiness probe, and caches the delivered interface for its lifetime. Calls
/// waiting for readiness have independent monotonic deadlines. Once readiness claims a call, its
/// deadline no longer applies and its native action executes in coordinator admission order on the
/// process-wide native queue. A ready call remains cancellable until the native queue claims it for
/// immediate execution. Invalidation rejects unclaimed calls; claimed calls retain the data needed
/// to finish and settle without retaining the coordinator.
///
/// Runtime SFMCSdk reinitialization is unsupported while this coordinator is active because cached
/// readiness belongs to the SDK initialization that produced the interface.
final class ConsentCoordinator<Interface>: @unchecked Sendable {
  typealias Token = UUID
  typealias NativeAction = (Interface) -> (() -> Void)
  typealias Registration = (@escaping (Interface) -> Void) -> Void
  typealias TimeoutScheduler = (DispatchTime, @escaping () -> Void) -> Void

  private enum Readiness {
    case idle
    case waiting
    case ready(Interface)
    case invalidated
  }

  private struct PendingRequest {
    let token: Token
    let action: NativeAction
    let timedOut: () -> Void
    let unavailable: () -> Void
  }

  private let stateQueue = DispatchQueue(label: "com.salesforce.datacloud.consent.state")
  private let deliveryQueue: DispatchQueue
  private let register: Registration
  private let readyInterface: () -> Interface?
  private let scheduleTimeout: TimeoutScheduler
  private let nativeQueue: DispatchQueue
  private var readiness = Readiness.idle
  private var pendingRequests: [PendingRequest] = []
  private var executing = false

  /// Creates a coordinator with injectable readiness, timeout, delivery, and native queues.
  ///
  /// - Parameters:
  ///   - deliveryQueue: Queue used to deliver the selected promise settlement.
  ///   - register: Installs the shared SDK-readiness callback.
  ///   - readyInterface: Probes for an already-operational interface after registration.
  ///   - scheduleTimeout: Schedules a callback at an absolute monotonic deadline.
  ///   - nativeQueue: Serial queue used for synchronous native consent access.
  init(
    deliveryQueue: DispatchQueue = .main,
    register: @escaping Registration,
    readyInterface: @escaping () -> Interface? = { nil },
    scheduleTimeout: @escaping TimeoutScheduler = { deadline, callback in
      DispatchQueue.global().asyncAfter(deadline: deadline, execute: callback)
    },
    nativeQueue: DispatchQueue = NativeConsentExecutor.queue
  ) {
    self.deliveryQueue = deliveryQueue
    self.register = register
    self.readyInterface = readyInterface
    self.scheduleTimeout = scheduleTimeout
    self.nativeQueue = nativeQueue
  }

  @discardableResult
  /// Admits a consent operation and returns its identity for readiness-timeout arbitration.
  ///
  /// - Important: `timeout` covers SDK readiness only. Native-executor queueing and promise
  ///   delivery are outside this deadline after readiness claims the request.
  func submit(
    timeout: TimeInterval,
    action: @escaping NativeAction,
    timedOut: @escaping () -> Void,
    unavailable: @escaping () -> Void
  ) -> Token {
    let deadline = DispatchTime.now() + timeout
    let token = Token()
    let request = PendingRequest(token: token, action: action, timedOut: timedOut,
                                 unavailable: unavailable)
    var shouldRegister = false
    var waitsForReadiness = false
    var rejected = false

    stateQueue.sync {
      switch readiness {
      case .idle:
        readiness = .waiting
        pendingRequests.append(request)
        shouldRegister = true
        waitsForReadiness = true
      case .waiting:
        pendingRequests.append(request)
        waitsForReadiness = true
      case .ready:
        pendingRequests.append(request)
        claimNext()
      case .invalidated:
        rejected = true
      }
    }

    if rejected {
      deliver(unavailable)
      return token
    }

    guard waitsForReadiness else { return token }

    scheduleTimeout(deadline) { [weak self] in self?.timeout(token) }
    if shouldRegister {
      register { [weak self] interface in self?.reportReady(interface) }
      if let interface = readyInterface() {
        reportReady(interface)
      }
    }
    return token
  }

  /// Rejects every operation still waiting for readiness and prevents future admission.
  ///
  /// Operations already claimed by `nativeQueue` continue because a synchronous native setter may
  /// have begun irreversible local side effects before invalidation can observe it.
  func invalidate() {
    var requests: [PendingRequest] = []
    stateQueue.sync {
      if case .invalidated = readiness {
        return
      }
      readiness = .invalidated
      requests = pendingRequests
      pendingRequests.removeAll()
    }
    requests.forEach { deliver($0.unavailable) }
  }

  private func timeout(_ token: Token) {
    var request: PendingRequest?
    stateQueue.sync {
      guard case .waiting = readiness,
            let index = pendingRequests.firstIndex(where: { $0.token == token }) else { return }
      request = pendingRequests.remove(at: index)
    }
    if let request { deliver(request.timedOut) }
  }

  private func reportReady(_ interface: Interface) {
    stateQueue.async { [weak self] in self?.acceptReadiness(interface) }
  }

  private func acceptReadiness(_ interface: Interface) {
    guard case .waiting = readiness else { return }
    readiness = .ready(interface)
    claimNext()
  }

  /// Claims only the admission-order head so invalidation can still reject a queued ready tail.
  private func claimNext() {
    guard !executing, !pendingRequests.isEmpty else { return }
    executing = true
    execute(pendingRequests[0].token)
  }

  /// Enqueues without executing inline so native SDK calls never run under `stateQueue`.
  private func execute(_ token: Token) {
    let deliveryQueue = deliveryQueue
    nativeQueue.async { [weak self] in
      guard let (current, interface) = self?.claim(token) else { return }
      let settlement = current.action(interface)
      deliveryQueue.async(execute: settlement)
      self?.scheduleFollowing()
    }
  }

  private func claim(_ token: Token) -> (PendingRequest, Interface)? {
    stateQueue.sync {
      guard executing, case .ready(let interface) = readiness,
            pendingRequests.first?.token == token else {
        executing = false
        return nil
      }
      return (pendingRequests.removeFirst(), interface)
    }
  }

  /// Re-enqueues the next ready action so other coordinators can use the process-wide queue.
  private func scheduleFollowing() {
    var token: Token?
    stateQueue.sync {
      executing = false
      guard case .ready = readiness, let next = pendingRequests.first else { return }
      executing = true
      token = next.token
    }
    if let token { execute(token) }
  }

  private func deliver(_ settlement: @escaping () -> Void) {
    deliveryQueue.async(execute: settlement)
  }
}

#if !CONSENT_CORE_TESTS
/// React Native bridge for reading and updating Salesforce Data Cloud consent.
///
/// The module waits up to 10 seconds for the native Data Cloud SDK to become ready.
/// Reads resolve to `"opt_in"`, `"opt_out"`, or `"pending"`; writes accept only `"opt_in"` and
/// `"opt_out"`. Valid native actions execute one at a time in this module's admission order.
/// Promise callbacks are delivered on the main queue.
@objc(DataCloudConsentModule)
public final class DataCloudConsentModule: NSObject, RCTBridgeModule, RCTInvalidating {
  private let coordinator: ConsentCoordinator<any CdpInterface>

  /// Creates a bridge backed by the process-wide Salesforce SDK instance.
  ///
  /// The bridge registers for CDP readiness once and immediately probes for an already-operational
  /// interface to avoid missing readiness during registration.
  public override init() {
    coordinator = ConsentCoordinator<any CdpInterface>(
      register: { callback in SFMCSdk.requestCdpSdk(callback) },
      readyInterface: {
        let cdp = SFMCSdk.cdp
        return cdp.getStatus() == .operational ? cdp : nil
      }
    )
    super.init()
  }

  /// Indicates that React Native may construct this module away from the main queue.
  @objc public static func requiresMainQueueSetup() -> Bool { false }

  /// Name used to register and resolve this native module from React Native.
  @objc public static func moduleName() -> String! { "DataCloudConsentModule" }

  /// Reads the current Data Cloud consent state.
  ///
  /// - Parameters:
  ///   - resolve: Resolves with `"opt_in"`, `"opt_out"`, or `"pending"`.
  ///   - reject: Rejects with `E_SDK_NOT_READY` after the readiness timeout or
  ///     `E_SDK_UNAVAILABLE` after bridge invalidation.
  @objc public func getConsent(
    _ resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    begin(reject) { cdp in
      let value = consentString(for: cdp.getConsent())
      return { resolve(value) }
    }
  }

  /// Updates the Data Cloud consent state.
  ///
  /// - Parameters:
  ///   - value: The new state. Supported values are `"opt_in"` and `"opt_out"`.
  ///   - resolve: Resolves with no value after synchronous local native assignment returns.
  ///     Resolution does not confirm completion of asynchronous persistence, event-queue processing,
  ///     consent-event delivery, or server propagation.
  ///   - reject: Rejects with `E_INVALID_CONSENT`, `E_SDK_NOT_READY`, or
  ///     `E_SDK_UNAVAILABLE` when the update cannot be performed.
  @objc public func setConsent(
    _ value: String,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    guard let consent = writableConsent(for: value) else {
      DispatchQueue.main.async {
        reject("E_INVALID_CONSENT", "Consent must be either opt_in or opt_out.", nil)
      }
      return
    }
    begin(reject) { cdp in
      cdp.setConsent(consent: consent)
      return { resolve(nil) }
    }
  }

  /// Invalidates the bridge and rejects consent operations not yet claimed for native execution.
  ///
  /// Claimed work continues to its selected terminal settlement.
  @objc public func invalidate() {
    coordinator.invalidate()
  }

  private func begin(
    _ reject: @escaping RCTPromiseRejectBlock,
    action: @escaping ConsentCoordinator<any CdpInterface>.NativeAction
  ) {
    let seconds = String(format: "%g", dataCloudConsentReadinessTimeout)
    coordinator.submit(timeout: dataCloudConsentReadinessTimeout, action: action, timedOut: {
      reject("E_SDK_NOT_READY", "The Data Cloud SDK was not ready within \(seconds) seconds.", nil)
    }, unavailable: {
      reject("E_SDK_UNAVAILABLE", "The Data Cloud SDK is unavailable.", nil)
    })
  }
}
#endif
