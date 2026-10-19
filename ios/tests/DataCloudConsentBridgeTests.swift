import Foundation

private func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  guard condition() else {
    FileHandle.standardError.write(Data("FAIL: \(message)\n".utf8))
    exit(1)
  }
}

private final class Locked<Value> {
  private let lock = NSLock()
  private var value: Value
  init(_ value: Value) { self.value = value }
  func read() -> Value { lock.withLock { value } }
  func update(_ body: (inout Value) -> Void) { lock.withLock { body(&value) } }
}

private final class WeakReference<T: AnyObject> {
  weak var value: T?
  init(_ value: T?) { self.value = value }
}

private final class Probe {}
private final class TestInterface {
  let identity: String
  init(_ identity: String = "default") { self.identity = identity }
}

private func waitUntil(_ message: String, _ condition: () -> Bool) {
  let deadline = DispatchTime.now() + 5
  while !condition() && DispatchTime.now() < deadline {
    Thread.sleep(forTimeInterval: 0.001)
  }
  expect(condition(), message)
}

private final class Harness {
  let deliveryQueue = DispatchQueue(label: "consent.test.delivery")
  let registrations = Locked<[(TestInterface) -> Void]>([])
  let deadlines = Locked<[DispatchTime]>([])
  let timeouts = Locked<[() -> Void]>([])
  let nativeQueue: DispatchQueue
  let coordinator: ConsentCoordinator<TestInterface>

  init(
    register: ((@escaping (TestInterface) -> Void) -> Void)? = nil,
    readyInterface: @escaping () -> TestInterface? = { nil },
    nativeQueue: DispatchQueue = NativeConsentExecutor.queue
  ) {
    self.nativeQueue = nativeQueue
    let registrations = registrations
    let deadlines = deadlines
    let timeouts = timeouts
    coordinator = ConsentCoordinator<TestInterface>(
      deliveryQueue: deliveryQueue,
      register: register ?? { callback in registrations.update { $0.append(callback) } },
      readyInterface: readyInterface,
      scheduleTimeout: { deadline, callback in
        deadlines.update { $0.append(deadline) }
        timeouts.update { $0.append(callback) }
      },
      nativeQueue: nativeQueue
    )
  }

  func submit(_ value: Int, outcomes: Locked<[Int]>, action: (() -> Void)? = nil) {
    coordinator.submit(timeout: 10, action: { _ in
      action?()
      outcomes.update { $0.append(value) }
      return {}
    }, timedOut: { outcomes.update { $0.append(-value - 1) } }, unavailable: {
      outcomes.update { $0.append(-100 - value) }
    })
  }

  func drain() {
    // Successors are intentionally re-enqueued one at a time for process-wide fairness.
    for _ in 0..<16 {
      nativeQueue.sync {}
      deliveryQueue.sync {}
    }
  }
  func ready(_ interface: TestInterface = TestInterface()) { registrations.read()[0](interface) }
  func fireTimeout(_ index: Int) { timeouts.read()[index]() }
}

@main
struct DataCloudConsentBridgeTests {
  static func main() {
    mappingTests()
    claimedOperationLifetimeTests()
    sharedReadinessAndFIFOTests()
    synchronousRegistrationTests()
    readinessWinnerAndDeadlineTests()
    cachedReadinessAdmissionOrderTests()
    cachedReadinessReentryTests()
    blockedRegistrationTimeoutTests()
    timeoutAndReadinessClaimTests()
    queuedHeadInvalidationTests()
    lifecycleTests()
    processWideExecutorTests()
    captureReleaseAndRaceTests()
    print("PASS: DataCloudConsentBridgeTests")
  }

  private static func mappingTests() {
    expect(dataCloudConsentReadinessTimeout == 10, "production readiness timeout is 10 seconds")
    expect(consentString(for: .optIn) == "opt_in", "optIn maps directly")
    expect(consentString(for: .optOut) == "opt_out", "optOut maps directly")
    expect(consentString(for: .pending) == "pending", "pending maps directly")
    expect(consentString(for: .none) == "pending", "none normalizes to pending")
    expect(writableConsent(for: "opt_in") == .optIn, "opt_in parses")
    expect(writableConsent(for: "opt_out") == .optOut, "opt_out parses")
    expect(writableConsent(for: "pending") == nil, "pending is not writable")
    expect(writableConsent(for: "OPT_IN") == nil, "writable parse is exact")
  }

  private static func sharedReadinessAndFIFOTests() {
    let harness = Harness()
    let outcomes = Locked<[Int]>([])
    harness.submit(0, outcomes: outcomes)
    harness.submit(1, outcomes: outcomes)
    expect(harness.registrations.read().count == 1,
           "pending operations share one readiness registration")
    harness.ready()
    harness.submit(2, outcomes: outcomes)
    harness.drain()
    expect(harness.registrations.read().count == 1,
           "cached readiness avoids a later registration")
    expect(harness.timeouts.read().count == 2,
           "cached readiness does not schedule another readiness timer")
    expect(outcomes.read() == [0, 1, 2], "native actions execute in coordinator FIFO order")
  }

  private static func synchronousRegistrationTests() {
    let registrations = Locked(0)
    let identities = Locked<[String]>([])
    let probeCount = Locked(0)
    let harness = Harness(register: { callback in
      registrations.update { $0 += 1 }
      callback(TestInterface("callback"))
    }, readyInterface: {
      probeCount.update { $0 += 1 }
      return TestInterface("probe")
    })
    harness.coordinator.submit(timeout: 10, action: { interface in
      identities.update { $0.append(interface.identity) }
      return {}
    }, timedOut: {}, unavailable: {})
    waitUntil("callback-first readiness action completes") { identities.read().count == 1 }
    harness.drain()
    expect(registrations.read() == 1, "synchronous registration does not deadlock or repeat")
    expect(probeCount.read() == 1, "post-registration operational probe executes once")
    expect(identities.read() == ["callback"],
           "callback-first readiness uses only the first accepted interface")
  }

  private static func readinessWinnerAndDeadlineTests() {
    let probeFirstCallbacks = Locked<[(TestInterface) -> Void]>([])
    let probeFirstIdentities = Locked<[String]>([])
    let probeFirst = Harness(register: { callback in
      probeFirstCallbacks.update { $0.append(callback) }
    }, readyInterface: { TestInterface("probe") })
    probeFirst.coordinator.submit(timeout: 10, action: { interface in
      probeFirstIdentities.update { $0.append(interface.identity) }
      return {}
    }, timedOut: {}, unavailable: {})
    probeFirstCallbacks.read()[0](TestInterface("callback"))
    waitUntil("probe-first readiness action completes") { probeFirstIdentities.read().count == 1 }
    probeFirst.drain()
    expect(probeFirstIdentities.read() == ["probe"],
           "probe-first readiness uses only the first accepted interface")

    let registrationTime = Locked<DispatchTime?>(nil)
    let scheduledDeadline = Locked<DispatchTime?>(nil)
    let deadlineCoordinator = ConsentCoordinator<TestInterface>(
      register: { _ in registrationTime.update { $0 = .now() } },
      scheduleTimeout: { deadline, _ in scheduledDeadline.update { $0 = deadline } }
    )
    deadlineCoordinator.submit(timeout: 0, action: { _ in {} }, timedOut: {}, unavailable: {})
    expect(scheduledDeadline.read()!.uptimeNanoseconds <= registrationTime.read()!.uptimeNanoseconds,
           "monotonic deadline is captured before readiness registration")
  }

  private static func cachedReadinessAdmissionOrderTests() {
    let nativeQueue = DispatchQueue(label: "consent.test.cached-ready-native")
    let harness = Harness(nativeQueue: nativeQueue)
    let outcomes = Locked<[Int]>([])

    harness.submit(0, outcomes: outcomes)
    harness.ready()
    waitUntil("initial readiness action completes") { outcomes.read() == [0] }
    harness.drain()

    nativeQueue.suspend()
    harness.submit(1, outcomes: outcomes)
    harness.submit(2, outcomes: outcomes)
    expect(outcomes.read() == [0], "suspended native queue holds cached-ready actions")
    nativeQueue.resume()
    harness.drain()

    expect(outcomes.read() == [0, 1, 2],
           "cached-ready native enqueue order matches state admission order")
  }

  private static func cachedReadinessReentryTests() {
    let nativeQueue = DispatchQueue(label: "consent.test.cached-ready-reentry")
    let harness = Harness(nativeQueue: nativeQueue)
    let outcomes = Locked<[Int]>([])
    harness.submit(0, outcomes: outcomes)
    harness.ready()
    harness.drain()

    let reentryReturned = DispatchSemaphore(value: 0)
    DispatchQueue.global().async {
      harness.submit(1, outcomes: outcomes, action: {
        harness.coordinator.invalidate()
        reentryReturned.signal()
      })
    }
    expect(reentryReturned.wait(timeout: .now() + 1) == .success,
           "cached-ready native action can reenter coordinator without blocking")
  }

  private static func blockedRegistrationTimeoutTests() {
    let registrationStarted = DispatchSemaphore(value: 0)
    let releaseRegistration = DispatchSemaphore(value: 0)
    let outcomes = Locked<[String]>([])
    let callbacks = Locked<[(TestInterface) -> Void]>([])
    let harness = Harness(register: { callback in
      callbacks.update { $0.append(callback) }
      registrationStarted.signal()
      releaseRegistration.wait()
    })

    let submitReturned = DispatchSemaphore(value: 0)
    DispatchQueue.global().async {
      harness.coordinator.submit(timeout: 10, action: { _ in
        { outcomes.update { $0.append("success") } }
      }, timedOut: { outcomes.update { $0.append("timeout") } }, unavailable: {})
      submitReturned.signal()
    }
    expect(registrationStarted.wait(timeout: .now() + 5) == .success,
           "readiness registration becomes blocked")
    expect(harness.timeouts.read().count == 1,
           "readiness timeout is scheduled before registration returns")
    harness.fireTimeout(0)
    harness.deliveryQueue.sync {}
    expect(outcomes.read() == ["timeout"],
           "readiness timeout can settle while registration remains blocked")

    releaseRegistration.signal()
    expect(submitReturned.wait(timeout: .now() + 5) == .success,
           "submission returns after readiness registration is released")
    callbacks.read()[0](TestInterface("late"))
    harness.drain()
    expect(outcomes.read() == ["timeout"],
           "late readiness after blocked registration is harmless")
  }

  private static func timeoutAndReadinessClaimTests() {
    let timedOut = Harness()
    let timedOutcomes = Locked<[Int]>([])
    timedOut.submit(0, outcomes: timedOutcomes)
    timedOut.submit(1, outcomes: timedOutcomes)
    timedOut.fireTimeout(0)
    timedOut.fireTimeout(1)
    timedOut.drain()
    expect(timedOutcomes.read() == [-1, -2], "all readiness waiters may time out")
    expect(timedOut.registrations.read().count == 1, "registration remains after all waiters time out")
    timedOut.ready()
    timedOut.submit(2, outcomes: timedOutcomes)
    timedOut.drain()
    expect(timedOut.registrations.read().count == 1, "later operation reuses the original registration")
    expect(timedOutcomes.read() == [-1, -2, 2], "later operation uses cached readiness")

    let blocker = Harness()
    let blockerStarted = DispatchSemaphore(value: 0)
    let releaseBlocker = DispatchSemaphore(value: 0)
    let claimOutcomes = Locked<[Int]>([])
    blocker.submit(0, outcomes: claimOutcomes, action: {
      blockerStarted.signal()
      releaseBlocker.wait()
    })
    blocker.submit(1, outcomes: claimOutcomes)
    blocker.ready()
    expect(blockerStarted.wait(timeout: .now() + 5) == .success, "executor becomes blocked")
    blocker.fireTimeout(1)
    releaseBlocker.signal()
    blocker.drain()
    expect(claimOutcomes.read() == [0, 1], "readiness claim wins timeout while executor is blocked")
  }

  private static func lifecycleTests() {
    let harness = Harness()
    let started = DispatchSemaphore(value: 0)
    let release = DispatchSemaphore(value: 0)
    let outcomes = Locked<[Int]>([])
    harness.submit(0, outcomes: outcomes, action: { started.signal(); release.wait() })
    harness.submit(1, outcomes: outcomes)
    harness.ready()
    expect(started.wait(timeout: .now() + 5) == .success, "first claimed action starts")
    harness.coordinator.invalidate()
    release.signal()
    harness.drain()
    expect(outcomes.read().sorted() == [-101, 0],
           "invalidation rejects a ready tail that native execution has not claimed")

    let waiting = Harness()
    let waitingOutcomes = Locked<[Int]>([])
    waiting.submit(0, outcomes: waitingOutcomes)
    waiting.coordinator.invalidate()
    waiting.drain()
    expect(waitingOutcomes.read() == [-100], "invalidation rejects unclaimed work")

    let fresh = Harness()
    let freshOutcomes = Locked<[Int]>([])
    fresh.submit(0, outcomes: freshOutcomes)
    fresh.ready()
    waitUntil("new coordinator action completes") { freshOutcomes.read().count == 1 }
    fresh.drain()
    expect(freshOutcomes.read() == [0], "invalidated old coordinator does not poison a new coordinator")

    let delivery = Harness()
    let deliveryKey = DispatchSpecificKey<String>()
    delivery.deliveryQueue.setSpecific(key: deliveryKey, value: "delivery")
    let deliveredOn = Locked("")
    delivery.coordinator.submit(timeout: 10, action: { _ in
      { deliveredOn.update { $0 = DispatchQueue.getSpecific(key: deliveryKey) ?? "wrong" } }
    }, timedOut: {}, unavailable: {})
    delivery.ready()
    waitUntil("promise settlement is delivered") { !deliveredOn.read().isEmpty }
    delivery.drain()
    expect(deliveredOn.read() == "delivery", "promise settlement uses the delivery queue")
  }

  private static func queuedHeadInvalidationTests() {
    let nativeQueue = DispatchQueue(label: "consent.test.queued-head-invalidation")
    let executorStarted = DispatchSemaphore(value: 0)
    let releaseExecutor = DispatchSemaphore(value: 0)
    nativeQueue.async {
      executorStarted.signal()
      releaseExecutor.wait()
    }
    expect(executorStarted.wait(timeout: .now() + 5) == .success, "native executor is blocked")

    let harness = Harness(nativeQueue: nativeQueue)
    let actions = Locked(0)
    let outcomes = Locked<[Int]>([])
    harness.submit(0, outcomes: outcomes, action: { actions.update { $0 += 1 } })
    harness.ready()
    harness.fireTimeout(0)
    harness.deliveryQueue.sync {}
    expect(outcomes.read().isEmpty, "readiness claims the deadline before native execution")

    harness.coordinator.invalidate()
    harness.deliveryQueue.sync {}
    releaseExecutor.signal()
    harness.drain()
    expect(actions.read() == 0, "invalidation prevents a queued head from reaching native code")
    expect(outcomes.read() == [-100], "invalidation rejects a queued but unclaimed head")
  }

  private static func processWideExecutorTests() {
    let first = Harness()
    let second = Harness()
    let active = Locked(0)
    let overlap = Locked(false)
    let started = DispatchSemaphore(value: 0)
    let secondStarted = DispatchSemaphore(value: 0)
    let release = DispatchSemaphore(value: 0)
    let outcomes = Locked<[Int]>([])
    first.submit(0, outcomes: outcomes, action: {
      active.update { $0 += 1; if $0 > 1 { overlap.update { $0 = true } } }
      started.signal(); release.wait()
      active.update { $0 -= 1 }
    })
    second.submit(1, outcomes: outcomes, action: {
      active.update { $0 += 1; if $0 > 1 { overlap.update { $0 = true } } }
      secondStarted.signal()
      active.update { $0 -= 1 }
    })
    first.ready()
    expect(started.wait(timeout: .now() + 5) == .success, "first coordinator enters native executor")
    second.ready()
    expect(secondStarted.wait(timeout: .now() + 0.1) == .timedOut,
           "second coordinator cannot start while first action is blocked")
    expect(!overlap.read(), "two coordinators never overlap native execution")
    release.signal()
    expect(secondStarted.wait(timeout: .now() + 5) == .success,
           "second coordinator starts after first action is released")
    first.drain(); second.drain()
    expect(outcomes.read().count == 2, "both coordinators complete")

    let incremental = Harness()
    let firstSettled = DispatchSemaphore(value: 0)
    let incrementalSecondStarted = DispatchSemaphore(value: 0)
    let releaseSecond = DispatchSemaphore(value: 0)
    incremental.coordinator.submit(timeout: 10, action: { _ in
      { firstSettled.signal() }
    }, timedOut: {}, unavailable: {})
    incremental.coordinator.submit(timeout: 10, action: { _ in
      incrementalSecondStarted.signal()
      releaseSecond.wait()
      return {}
    }, timedOut: {}, unavailable: {})
    incremental.ready()
    expect(incrementalSecondStarted.wait(timeout: .now() + 5) == .success,
           "second incremental action starts")
    expect(firstSettled.wait(timeout: .now() + 5) == .success,
           "first settlement is delivered before the ready queue becomes empty")
    releaseSecond.signal()
    incremental.drain()
  }

  private static func claimedOperationLifetimeTests() {
    let actionStarted = DispatchSemaphore(value: 0)
    let releaseAction = DispatchSemaphore(value: 0)
    let actions = Locked<[Int]>([])
    let settlements = Locked<[Int]>([])
    var harness: Harness? = Harness()
    harness?.coordinator.submit(timeout: 10, action: { _ in
      actionStarted.signal()
      releaseAction.wait()
      actions.update { $0.append(0) }
      return { settlements.update { $0.append(0) } }
    }, timedOut: {}, unavailable: {})
    harness?.coordinator.submit(timeout: 10, action: { _ in
      actions.update { $0.append(1) }
      return { settlements.update { $0.append(1) } }
    }, timedOut: {}, unavailable: {})
    harness?.ready(TestInterface("claimed"))
    expect(actionStarted.wait(timeout: .now() + 5) == .success, "native action is claimed")
    let deliveryQueue = harness!.deliveryQueue
    let weakCoordinator = WeakReference(harness?.coordinator)
    harness = nil
    expect(weakCoordinator.value == nil, "claimed action data does not retain its coordinator")
    releaseAction.signal()
    NativeConsentExecutor.queue.sync {}
    deliveryQueue.sync {}
    expect(actions.read() == [0], "only the claimed action survives coordinator deallocation")
    expect(settlements.read() == [0], "only the claimed settlement survives exactly once")

    let nativeQueue = DispatchQueue(label: "consent.test.unclaimed-lifetime")
    let executorStarted = DispatchSemaphore(value: 0)
    let releaseExecutor = DispatchSemaphore(value: 0)
    nativeQueue.async {
      executorStarted.signal()
      releaseExecutor.wait()
    }
    expect(executorStarted.wait(timeout: .now() + 5) == .success, "native executor is blocked")
    let queuedActions = Locked(0)
    var queuedHarness: Harness? = Harness(nativeQueue: nativeQueue)
    let weakQueuedCoordinator = WeakReference(queuedHarness?.coordinator)
    let queuedOutcomes = Locked<[Int]>([])
    queuedHarness?.submit(0, outcomes: queuedOutcomes, action: {
      queuedActions.update { $0 += 1 }
    })
    queuedHarness?.ready()
    queuedHarness = nil
    expect(weakQueuedCoordinator.value == nil, "queued unclaimed work does not retain its coordinator")
    releaseExecutor.signal()
    nativeQueue.sync {}
    expect(queuedActions.read() == 0, "released unclaimed work never reaches native code")
  }

  private static func captureReleaseAndRaceTests() {
    for outcome in 0..<3 {
      let harness = Harness()
      var probe: Probe? = Probe()
      let weakProbe = WeakReference(probe)
      harness.coordinator.submit(timeout: 10, action: { [probe] _ in { _ = probe } },
                                 timedOut: { [probe] in _ = probe }, unavailable: { [probe] in _ = probe })
      probe = nil
      if outcome == 0 { harness.ready() }
      if outcome == 1 { harness.fireTimeout(0) }
      if outcome == 2 { harness.coordinator.invalidate() }
      harness.drain()
      waitUntil("terminal outcome releases captures") { weakProbe.value == nil }
      expect(weakProbe.value == nil, "captures release after terminal outcome \(outcome)")
    }

    let callbacks = Locked<[(TestInterface) -> Void]>([])
    var coordinator: ConsentCoordinator<TestInterface>? = ConsentCoordinator(
      register: { callback in callbacks.update { $0.append(callback) } },
      scheduleTimeout: { _, _ in }
    )
    let weakCoordinator = WeakReference(coordinator)
    coordinator?.submit(timeout: 10, action: { _ in {} }, timedOut: {}, unavailable: {})
    coordinator = nil
    expect(weakCoordinator.value == nil, "SDK-retained readiness callback weakly captures coordinator")

    let timerCallbacks = Locked<[() -> Void]>([])
    var timerCoordinator: ConsentCoordinator<TestInterface>? = ConsentCoordinator(
      register: { _ in },
      scheduleTimeout: { _, callback in timerCallbacks.update { $0.append(callback) } }
    )
    let weakTimerCoordinator = WeakReference(timerCoordinator)
    timerCoordinator?.submit(timeout: 10, action: { _ in {} }, timedOut: {}, unavailable: {})
    timerCoordinator = nil
    expect(weakTimerCoordinator.value == nil, "scheduled timer weakly captures coordinator")
    timerCallbacks.read()[0]()

    for _ in 0..<100 {
      let harness = Harness()
      let outcomes = Locked<[Int]>([])
      harness.submit(0, outcomes: outcomes)
      let group = DispatchGroup()
      group.enter(); DispatchQueue.global().async { harness.fireTimeout(0); group.leave() }
      group.enter(); DispatchQueue.global().async { harness.ready(); group.leave() }
      group.enter(); DispatchQueue.global().async { harness.coordinator.invalidate(); group.leave() }
      group.wait()
      waitUntil("race produces one outcome") { outcomes.read().count == 1 }
      harness.drain()
      expect(outcomes.read().count == 1, "readiness/timeout/invalidation race settles exactly once")
    }
  }
}
