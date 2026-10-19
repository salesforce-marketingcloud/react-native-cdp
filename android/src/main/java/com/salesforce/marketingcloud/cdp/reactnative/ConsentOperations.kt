package com.salesforce.marketingcloud.cdp.reactnative

import android.os.Handler
import android.os.Looper
import com.salesforce.marketingcloud.cdp.CdpReadyListener
import com.salesforce.marketingcloud.cdp.CdpSdk
import com.salesforce.marketingcloud.cdp.consent.Consent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Converts consent values between the native SDK and React Native string contract. */
internal object ConsentValue {
  /** Returns the public bridge value for a native consent state. */
  fun toBridgeValue(consent: Consent): String = when (consent) {
    Consent.OPT_IN -> "opt_in"
    Consent.OPT_OUT -> "opt_out"
    Consent.PENDING -> "pending"
    else -> "pending"
  }

  /** Returns a writable native consent state, or `null` when [value] is unsupported. */
  fun fromBridgeValue(value: String): Consent? = when (value) {
    "opt_in" -> Consent.OPT_IN
    "opt_out" -> Consent.OPT_OUT
    else -> null
  }
}

/** Minimal native consent surface used by the lifecycle coordinator and its tests. */
internal interface ConsentAccess {
  /** Current native consent state. */
  var consent: Consent
}

/** Registers readiness callbacks and exposes the SDK instance delivered to the active callback. */
internal interface ConsentGateway {
  /** Requests the native SDK and invokes [listener] once it becomes ready. */
  fun request(listener: () -> ConsentAccess)

  /** Removes the exact pending readiness [listener] when the native SDK supports cancellation. */
  fun unregister(listener: () -> ConsentAccess)

  /** Returns the consent access associated with the currently executing readiness callback. */
  fun currentAccess(): ConsentAccess
}

/** Adapter for the static Android CDP readiness API. */
internal interface CdpReadinessRegistration {
  /** Registers a one-shot callback for a ready CDP SDK instance. */
  fun request(listener: (ConsentAccess) -> Unit)

  /** Unregisters the exact callback if it is still pending in the CDP SDK. */
  fun unregister(listener: (ConsentAccess) -> Unit)
}

/** Production readiness adapter backed by [CdpSdk.requestSdk]. */
internal class NativeCdpReadinessRegistration : CdpReadinessRegistration {
  private val listeners = ConcurrentHashMap<(ConsentAccess) -> Unit, CdpReadyListener>()

  override fun request(listener: (ConsentAccess) -> Unit) {
    lateinit var nativeListener: CdpReadyListener
    nativeListener = CdpReadyListener { sdk ->
      if (!listeners.remove(listener, nativeListener)) return@CdpReadyListener
      listener(SdkConsentAccess(sdk))
    }
    listeners[listener] = nativeListener
    CdpSdk.requestSdk(nativeListener)
  }

  override fun unregister(listener: (ConsentAccess) -> Unit) {
    listeners.remove(listener)?.let(CdpSdk::unregisterWhenReadyListener)
  }

  private class SdkConsentAccess(private val sdk: CdpSdk) : ConsentAccess {
    override var consent: Consent
      get() = sdk.consent
      set(value) {
        sdk.consent = value
      }
  }
}

/**
 * Owns native listener identity and suppresses callbacks that arrive after bridge cancellation.
 *
 * The CDP SDK may already have posted a callback when unregister is requested. Removing the bridge
 * listener mapping before delivery ensures that posted callback becomes a no-op.
 */
internal class CdpConsentGateway(
  private val registration: CdpReadinessRegistration = NativeCdpReadinessRegistration()
) : ConsentGateway {
  private val listeners = ConcurrentHashMap<() -> ConsentAccess, RegistrationState>()
  private val delivered = ThreadLocal<ConsentAccess>()

  override fun request(listener: () -> ConsentAccess) {
    lateinit var readinessListener: (ConsentAccess) -> Unit
    lateinit var state: RegistrationState
    readinessListener = { access ->
      if (listeners.remove(listener, state)) {
        state.delivered.set(true)
        delivered.set(access)
        try {
          listener()
        } finally {
          delivered.remove()
        }
      }
    }
    state = RegistrationState(readinessListener)
    listeners[listener] = state
    try {
      registration.request(readinessListener)
    } finally {
      state.registering.set(false)
      if (!listeners.containsKey(listener) && !state.delivered.get()) state.unregister()
    }
  }

  override fun unregister(listener: () -> ConsentAccess) {
    listeners.remove(listener)?.let { state ->
      if (!state.registering.get()) state.unregister()
    }
  }

  override fun currentAccess(): ConsentAccess = checkNotNull(delivered.get())

  private inner class RegistrationState(val listener: (ConsentAccess) -> Unit) {
    val registering = AtomicBoolean(true)
    val delivered = AtomicBoolean(false)
    private val unregistered = AtomicBoolean(false)

    fun unregister() {
      if (unregistered.compareAndSet(false, true)) registration.unregister(listener)
    }
  }
}

/** Schedules and cancels per-call SDK readiness deadlines. */
internal interface ReadinessScheduler {
  /** Schedules [callback] after [delayMillis]. */
  fun schedule(delayMillis: Long, callback: () -> Unit)

  /** Cancels [callback] if its runnable has not started. */
  fun cancel(callback: () -> Unit)
}

/** Main-looper readiness scheduler that safely handles cancellation racing with Handler posting. */
internal class HandlerReadinessScheduler(
  private val postDelayed: (Runnable, Long) -> Boolean,
  private val removeCallbacks: (Runnable) -> Unit
) : ReadinessScheduler {
  constructor(handler: Handler = Handler(Looper.getMainLooper())) : this(
    { runnable, delayMillis -> handler.postDelayed(runnable, delayMillis) },
    handler::removeCallbacks
  )

  private val callbacks = ConcurrentHashMap<() -> Unit, Runnable>()

  override fun schedule(delayMillis: Long, callback: () -> Unit) {
    lateinit var runnable: Runnable
    runnable = Runnable {
      if (callbacks.remove(callback, runnable)) callback()
    }
    callbacks[callback] = runnable
    val accepted = try {
      postDelayed(runnable, delayMillis)
    } catch (exception: Exception) {
      callbacks.remove(callback, runnable)
      removeCallbacks(runnable)
      throw exception
    }
    if (!accepted) {
      callbacks.remove(callback, runnable)
      throw IllegalStateException("The readiness timeout could not be scheduled.")
    }
    if (callbacks[callback] !== runnable) removeCallbacks(runnable)
  }

  override fun cancel(callback: () -> Unit) {
    callbacks.remove(callback)?.let(removeCallbacks)
  }
}

/** Platform-neutral promise settlement used by consent lifecycle code. */
internal interface ConsentResult {
  /** Resolves the operation with [value]. */
  fun resolve(value: Any?)

  /** Rejects the operation with a stable bridge [code] and [message]. */
  fun reject(code: String, message: String, cause: Throwable? = null)
}

private typealias ConsentSettlement = ConsentResult.() -> Unit

/**
 * Executes one synchronous native consent action inside a process-wide serialization boundary.
 *
 * Implementations must return only after [action] completes. The coordinator performs state
 * advancement and promise delivery after this boundary is released.
 */
internal fun interface ConsentNativeExecutor {
  fun execute(action: () -> Unit)
}

/**
 * Process-wide native consent executor used by every React Native module instance.
 *
 * React Native can briefly overlap an invalidated module and its replacement. This monitor prevents
 * concurrent access to the process-wide CDP SDK in that window. It does not provide FIFO ordering
 * between separate module instances.
 */
internal object ProcessWideConsentNativeExecutor : ConsentNativeExecutor {
  private val lock = Any()

  override fun execute(action: () -> Unit) = synchronized(lock) { action() }
}

/**
 * Runs consent reads and writes after the Android CDP SDK becomes ready.
 *
 * Every bridge call owns an independent readiness listener and ten-second deadline. Ready native
 * operations execute serially in this coordinator's admission order, even when readiness callbacks
 * arrive out of order. A deadline ends when its operation becomes ready; time behind an earlier
 * operation or the process-wide native executor is not reported as SDK-not-ready.
 *
 * Invalidation unregisters and rejects operations that have not been claimed. A claimed operation
 * completes because its synchronous native action may already have started irreversible local side
 * effects. Promise settlement occurs after releasing the process-wide native boundary.
 */
internal class ConsentOperations(
  private val gateway: ConsentGateway,
  private val scheduler: ReadinessScheduler,
  private val isAvailable: () -> Boolean,
  private val dispatch: (() -> Unit) -> Unit,
  private val nativeExecutor: ConsentNativeExecutor = ProcessWideConsentNativeExecutor
) {
  private val lock = Any()
  private val operations = mutableListOf<Operation>()
  private var draining = false
  private var invalidated = false

  /**
   * Reads the current native consent state after readiness and all earlier admitted operations.
   */
  fun get(result: ConsentResult) {
    whenReady(result) { access -> ConsentValue.toBridgeValue(access.consent) }
  }

  /**
   * Writes [value] after validation, readiness, and all earlier admitted operations.
   *
   * The result resolves after the synchronous native assignment returns; it does not confirm
   * asynchronous persistence, event-queue processing, consent-event delivery, or server
   * propagation.
   */
  fun set(value: String, result: ConsentResult) {
    val consent = ConsentValue.fromBridgeValue(value)
    if (consent == null) {
      result.reject("E_INVALID_CONSENT", "Consent must be either opt_in or opt_out.")
      return
    }
    whenReady(result) { access ->
      access.consent = consent
      null
    }
  }

  /**
   * Rejects every unclaimed request, unregisters pending listeners, and prevents future admission.
   * Claimed native work is allowed to reach its selected terminal result.
   */
  fun invalidate() {
    val cancelled = synchronized(lock) {
      if (invalidated) return
      invalidated = true
      operations.filter { it.state != OperationState.CLAIMED }.also { operations.removeAll(it) }
    }
    cancelled.forEach {
      scheduler.cancel(it.timeout)
      unregister(it)
      deliverSafely(it.result, unavailable())
    }
  }

  /**
   * Admits an operation in call order and installs its independent readiness listener and deadline.
   * The native [action] remains deferred until this operation is ready and every earlier admitted
   * operation has reached a terminal state.
   */
  private fun whenReady(result: ConsentResult, action: (ConsentAccess) -> Any?) {
    if (!isAvailable()) {
      result.unavailable()
      return
    }

    lateinit var request: Operation
    val listener: () -> ConsentAccess = {
      val access = gateway.currentAccess()
      ready(request, access)
      access
    }
    request = Operation(result, action, listener)
    request.timeout = { timeout(request) }
    val admitted = synchronized(lock) {
      if (invalidated) false else operations.add(request)
    }
    if (!admitted) {
      result.unavailable()
      return
    }
    try {
      scheduler.schedule(READY_TIMEOUT_MILLIS, request.timeout)
    } catch (exception: Exception) {
      fail(request, nativeFailure(exception), unregister = false)
      return
    }

    if (!synchronized(lock) { operations.contains(request) }) {
      scheduler.cancel(request.timeout)
      return
    }
    try {
      request.registered = true
      gateway.request(request.listener)
      if (!synchronized(lock) { operations.contains(request) }) unregister(request)
    } catch (exception: Exception) {
      fail(request, nativeFailure(exception))
    }
  }

  /**
   * Records the native access delivered for [request], ends its readiness deadline, and attempts to
   * drain the queue. A ready operation cannot overtake an earlier operation that is still waiting.
   */
  private fun ready(request: Operation, access: ConsentAccess) {
    if (!isAvailable()) {
      fail(request, unavailable(), unregister = false)
      return
    }
    val accepted = synchronized(lock) {
      if (!operations.contains(request) || request.state != OperationState.WAITING) return
      request.state = OperationState.READY
      request.access = access
      true
    }
    if (accepted) scheduler.cancel(request.timeout)
    drain()
  }

  private fun timeout(request: Operation) {
    synchronized(lock) {
      if (request.state != OperationState.WAITING || !operations.remove(request)) return
    }
    scheduler.cancel(request.timeout)
    unregister(request)
    deliverSafely(request.result) {
      reject("E_SDK_NOT_READY", "The Data Cloud SDK was not ready within 10 seconds.")
    }
    drain()
  }

  private fun fail(request: Operation, settlement: ConsentSettlement, unregister: Boolean = true) {
    synchronized(lock) {
      if (request.state == OperationState.CLAIMED || !operations.remove(request)) return
    }
    scheduler.cancel(request.timeout)
    if (unregister) unregister(request)
    deliverSafely(request.result, settlement)
    drain()
  }

  private fun unregister(request: Operation) {
    if (!request.registered) return
    try {
      gateway.unregister(request.listener)
    } catch (_: Exception) {
      // Cleanup cannot replace an operation's already-selected terminal outcome.
    }
  }

  /** Claims the admission-order head. Must run inside both [nativeExecutor] and [lock]. */
  private fun claimHeadLocked(): Operation? {
    val head = operations.firstOrNull() ?: return null
    if (head.state != OperationState.READY) return null
    head.state = OperationState.CLAIMED
    return head
  }

  /**
   * Iteratively executes the contiguous ready prefix without recursive stack growth.
   *
   * Each action runs inside the process-wide native executor. Queue advancement occurs under
   * [lock], and promise delivery occurs after the native serialization boundary is released.
   */
  private fun drain() {
    synchronized(lock) {
      if (draining) return
      if (operations.firstOrNull()?.state != OperationState.READY) return
      draining = true
    }

    while (true) {
      var completed: Pair<Operation, ConsentSettlement>? = null
      try {
        nativeExecutor.execute {
          if (!isAvailable()) {
            val rejected = synchronized(lock) {
              operations.firstOrNull()?.takeIf { it.state == OperationState.READY }?.also {
                operations.removeAt(0)
              }
            }
            if (rejected != null) completed = rejected to unavailable()
            return@execute
          }
          val operation = synchronized(lock) { claimHeadLocked() } ?: return@execute
          val settlement = try {
            val value = operation.action(checkNotNull(operation.access))
            val success: ConsentSettlement = { resolve(value) }
            success
          } catch (exception: Exception) {
            nativeFailure(exception)
          }
          completed = operation to settlement
        }
      } catch (exception: Exception) {
        if (completed == null) {
          val rejected = synchronized(lock) {
            operations.firstOrNull()?.takeIf { it.state == OperationState.READY }?.also {
              operations.removeAt(0)
            }
          }
          if (rejected != null) completed = rejected to nativeFailure(exception)
        }
      }

      val finished = completed
      if (finished == null) {
        synchronized(lock) { draining = false }
        return
      }
      val (operation, settlement) = finished
      val continues = synchronized(lock) {
        operations.remove(operation)
        (operations.firstOrNull()?.state == OperationState.READY).also {
          if (!it) draining = false
        }
      }
      deliverSafely(operation.result, settlement)
      if (!continues) return
    }
  }

  private fun deliverSafely(result: ConsentResult, settlement: ConsentSettlement) {
    val delivered = AtomicBoolean(false)
    val deliver = { if (delivered.compareAndSet(false, true)) settleSafely(result, settlement) }
    try {
      dispatch(deliver)
    } catch (_: Exception) {
      deliver()
    }
  }

  private fun settleSafely(result: ConsentResult, settlement: ConsentSettlement) {
    try {
      result.settlement()
    } catch (_: Exception) {
      // Promise callbacks remain terminal when bridge delivery throws.
    }
  }

  private fun unavailable(): ConsentSettlement = {
    reject("E_SDK_UNAVAILABLE", "The Data Cloud SDK is unavailable.")
  }

  private fun ConsentResult.unavailable() {
    reject("E_SDK_UNAVAILABLE", "The Data Cloud SDK is unavailable.")
  }

  private fun nativeFailure(exception: Exception): ConsentSettlement = {
    reject("E_NATIVE_FAILURE", "Native consent operation failed.", exception)
  }

  /** Lifecycle of an admitted operation until native execution claims it. */
  private enum class OperationState { WAITING, READY, CLAIMED }

  private class Operation(
    val result: ConsentResult,
    val action: (ConsentAccess) -> Any?,
    val listener: () -> ConsentAccess
  ) {
    lateinit var timeout: () -> Unit
    @Volatile var registered = false
    var state = OperationState.WAITING
    var access: ConsentAccess? = null
  }

  private companion object {
    const val READY_TIMEOUT_MILLIS = 10_000L
  }
}
