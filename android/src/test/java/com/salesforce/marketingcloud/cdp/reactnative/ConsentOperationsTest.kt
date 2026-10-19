package com.salesforce.marketingcloud.cdp.reactnative

import com.salesforce.marketingcloud.cdp.consent.Consent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ConsentOperationsTest {
  @Test
  fun `CDP gateway installs ownership before synchronous readiness and detaches before callback`() {
    val registration = FakeCdpReadinessRegistration(synchronous = true)
    val gateway = CdpConsentGateway(registration)
    var delivered: ConsentAccess? = null
    lateinit var listener: () -> ConsentAccess
    listener = {
      gateway.unregister(listener)
      delivered = gateway.currentAccess()
      delivered!!
    }

    gateway.request(listener)

    assertTrue(delivered === registration.access)
    assertEquals(0, registration.unregisterCount)
  }

  @Test
  fun `CDP gateway unregisters pending request failure exactly once`() {
    val registration = FakeCdpReadinessRegistration(requestFailure = IllegalStateException("failed"))
    val gateway = CdpConsentGateway(registration)
    val scheduler = FakeScheduler()
    val result = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() })

    operations.get(result)
    scheduler.runTimeout()

    assertEquals(1, registration.unregisterCount)
    assertEquals("E_NATIVE_FAILURE", result.errorCode)
    assertEquals(1, result.settlementCount)
  }

  @Test
  fun `CDP gateway ignores duplicate and late readiness callbacks`() {
    val registration = FakeCdpReadinessRegistration()
    val gateway = CdpConsentGateway(registration)
    var callbackCount = 0
    val listener = {
      callbackCount++
      gateway.currentAccess()
    }

    gateway.request(listener)
    registration.ready()
    registration.ready()
    gateway.unregister(listener)
    registration.ready()

    assertEquals(1, callbackCount)
    assertEquals(0, registration.unregisterCount)
  }

  @Test
  fun `CDP gateway ignores readiness callback after pending listener is unregistered`() {
    val registration = FakeCdpReadinessRegistration()
    val gateway = CdpConsentGateway(registration)
    var callbackCount = 0
    val listener = {
      callbackCount++
      gateway.currentAccess()
    }

    gateway.request(listener)
    gateway.unregister(listener)
    registration.ready()

    assertEquals(0, callbackCount)
    assertEquals(1, registration.unregisterCount)
  }

  @Test
  fun `timeout while readiness registration is blocked cleans up after request returns`() {
    val registration = BlockingCdpReadinessRegistration()
    val gateway = CdpConsentGateway(registration)
    val scheduler = FakeScheduler()
    val result = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() })
    val request = thread { operations.set("opt_in", result) }
    registration.awaitRequest()

    scheduler.runTimeout()
    registration.releaseRequest()
    request.join()
    registration.ready()

    assertEquals("E_SDK_NOT_READY", result.errorCode)
    assertEquals(1, result.settlementCount)
    assertEquals(1, registration.unregisterCount)
    assertFalse(registration.registered)
    assertEquals(Consent.PENDING, registration.access.consent)
  }

  @Test
  fun `invalidation while readiness registration is blocked cleans up after request returns`() {
    val registration = BlockingCdpReadinessRegistration()
    val gateway = CdpConsentGateway(registration)
    val scheduler = FakeScheduler()
    val result = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() })
    val request = thread { operations.set("opt_in", result) }
    registration.awaitRequest()

    operations.invalidate()
    registration.releaseRequest()
    request.join()
    registration.ready()

    assertEquals("E_SDK_UNAVAILABLE", result.errorCode)
    assertEquals(1, result.settlementCount)
    assertEquals(1, registration.unregisterCount)
    assertFalse(registration.registered)
    assertEquals(Consent.PENDING, registration.access.consent)
  }

  @Test
  fun `invalidation between availability check and pending admission rejects without registration`() {
    val availabilityChecked = CountDownLatch(1)
    val releaseAvailability = CountDownLatch(1)
    val registration = FakeCdpReadinessRegistration()
    val gateway = CdpConsentGateway(registration)
    val scheduler = FakeScheduler()
    val result = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, {
      availabilityChecked.countDown()
      releaseAvailability.await()
      true
    }, { it() })
    val request = thread { operations.get(result) }
    availabilityChecked.await()

    operations.invalidate()
    releaseAvailability.countDown()
    request.join()

    assertEquals("E_SDK_UNAVAILABLE", result.errorCode)
    assertEquals(1, result.settlementCount)
    assertEquals(0, registration.requestCount)
    assertEquals(0, registration.unregisterCount)
    assertFalse(scheduler.pending)
  }

  @Test
  fun `invalidation during timeout installation cancels eventual timeout`() {
    val gateway = FakeGateway(Consent.PENDING)
    val scheduler = BlockingScheduler()
    val result = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() })
    val request = thread { operations.get(result) }
    scheduler.awaitSchedule()

    operations.invalidate()
    scheduler.releaseSchedule()
    request.join()

    assertEquals("E_SDK_UNAVAILABLE", result.errorCode)
    assertEquals(1, result.settlementCount)
    assertEquals(0, gateway.requestCount)
    assertFalse(scheduler.pending)
  }

  @Test
  fun `handler scheduler removes callback cancelled before post completes`() {
    val postStarted = CountDownLatch(1)
    val releasePost = CountDownLatch(1)
    val posted = AtomicReference<Runnable?>()
    val removed = AtomicReference<Runnable?>()
    val callback = {}
    val scheduler = HandlerReadinessScheduler(
      postDelayed = { runnable, _ ->
        postStarted.countDown()
        releasePost.await()
        posted.set(runnable)
        true
      },
      removeCallbacks = { removed.set(it) }
    )

    val scheduling = thread { scheduler.schedule(10_000L, callback) }
    postStarted.await()
    scheduler.cancel(callback)
    releasePost.countDown()
    scheduling.join()

    assertTrue(removed.get() === posted.get())
  }

  @Test
  fun `handler scheduler rejects callback when handler refuses post`() {
    val callback = {}
    val scheduler = HandlerReadinessScheduler(
      postDelayed = { _, _ -> false },
      removeCallbacks = {}
    )

    val failure = runCatching { scheduler.schedule(10_000L, callback) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
  }

  @Test
  fun `handler scheduler removes callback accepted before post throws`() {
    val posted = AtomicReference<Runnable?>()
    val removed = AtomicReference<Runnable?>()
    val scheduler = HandlerReadinessScheduler(
      postDelayed = { runnable, _ ->
        posted.set(runnable)
        throw IllegalStateException("handler failed after accepting callback")
      },
      removeCallbacks = { removed.set(it) }
    )

    val failure = runCatching { scheduler.schedule(10_000L) {} }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(removed.get() === posted.get())
  }

  @Test
  fun `build dependencies are host owned and native SDK is pinned`() {
    val build = File("build.gradle").readText()

    assertTrue(build.contains("implementation \"com.facebook.react:react-android\""))
    assertFalse(build.contains("react-android:0.85.1"))
    assertTrue(build.contains("api \"com.salesforce.marketingcloud:cdp:3.0.1\""))
    assertFalse(build.contains("buildscript {"))
  }

  @Test
  fun `legacy bridge sources are removed`() {
    val legacySources = listOf("RNCdpModule.java", "RNCdpPackage.java", "EventUtility.java")

    legacySources.forEach { source ->
      assertFalse(File("src/main/java/com/salesforce/marketingcloud/cdp/reactnative/$source").exists())
    }
  }

  @Test
  fun `maps every native consent value explicitly`() {
    assertEquals("opt_in", ConsentValue.toBridgeValue(Consent.OPT_IN))
    assertEquals("opt_out", ConsentValue.toBridgeValue(Consent.OPT_OUT))
    assertEquals("pending", ConsentValue.toBridgeValue(Consent.PENDING))
  }

  @Test
  fun `accepts only writable consent values`() {
    assertEquals(Consent.OPT_IN, ConsentValue.fromBridgeValue("opt_in"))
    assertEquals(Consent.OPT_OUT, ConsentValue.fromBridgeValue("opt_out"))
    assertNull(ConsentValue.fromBridgeValue("pending"))
    assertNull(ConsentValue.fromBridgeValue("OPT_IN"))
    assertNull(ConsentValue.fromBridgeValue("invalid"))
  }

  @Test
  fun `reads current consent when SDK becomes ready`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.get(fixture.result)
    fixture.gateway.ready()

    assertEquals("opt_in", fixture.result.value)
    assertNull(fixture.result.errorCode)
    assertFalse(fixture.scheduler.pending)
    assertEquals(0, fixture.gateway.unregisterCount)
  }

  @Test
  fun `successful readiness delivery detaches before bridge callback without native unregister`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.get(fixture.result)
    fixture.gateway.ready()

    assertFalse(fixture.gateway.callbackObservedRegistered)
    assertEquals(0, fixture.gateway.unregisterCount)
  }

  @Test
  fun `duplicate readiness callback is ignored`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.get(fixture.result)
    fixture.gateway.ready()
    fixture.gateway.consent = Consent.OPT_OUT
    fixture.gateway.ready()

    assertEquals("opt_in", fixture.result.value)
    assertEquals(1, fixture.result.settlementCount)
  }

  @Test
  fun `late readiness callback after timeout does not write consent`() {
    val fixture = Fixture(Consent.PENDING)

    fixture.operations.set("opt_in", fixture.result)
    fixture.scheduler.runTimeout()
    fixture.gateway.ready()

    assertEquals(Consent.PENDING, fixture.gateway.consent)
    assertEquals("E_SDK_NOT_READY", fixture.result.errorCode)
    assertEquals(1, fixture.result.settlementCount)
  }

  @Test
  fun `synchronous readiness callback is not lost`() {
    val fixture = Fixture(Consent.OPT_IN)
    fixture.gateway.readySynchronously = true

    fixture.operations.get(fixture.result)

    assertEquals("opt_in", fixture.result.value)
    assertEquals(1, fixture.result.settlementCount)
    assertFalse(fixture.scheduler.pending)
  }

  @Test
  fun `writes consent and permits repeated updates`() {
    val fixture = Fixture(Consent.PENDING)

    fixture.operations.set("opt_in", fixture.result)
    fixture.gateway.ready()
    assertEquals(Consent.OPT_IN, fixture.gateway.consent)

    val second = RecordingResult()
    fixture.operations.set("opt_out", second)
    fixture.gateway.ready()

    assertEquals(Consent.OPT_OUT, fixture.gateway.consent)
    assertTrue(fixture.result.resolved)
    assertTrue(second.resolved)
  }

  @Test
  fun `rejects invalid and pending values`() {
    val fixture = Fixture(Consent.PENDING)

    fixture.operations.set("pending", fixture.result)

    assertEquals("E_INVALID_CONSENT", fixture.result.errorCode)
    assertEquals("Consent must be either opt_in or opt_out.", fixture.result.errorMessage)
    assertEquals(0, fixture.gateway.requestCount)
  }

  @Test
  fun `times out and unregisters exact readiness listener`() {
    val fixture = Fixture(Consent.PENDING)

    fixture.operations.get(fixture.result)
    val requestedListener = fixture.gateway.listener
    fixture.scheduler.runTimeout()

    assertEquals("E_SDK_NOT_READY", fixture.result.errorCode)
    assertEquals("The Data Cloud SDK was not ready within 10 seconds.", fixture.result.errorMessage)
    assertTrue(requestedListener === fixture.gateway.unregisteredListener)
  }

  @Test
  fun `rejects native failures and settles only once`() {
    val fixture = Fixture(Consent.PENDING)
    fixture.gateway.failure = IllegalStateException("broken")

    fixture.operations.get(fixture.result)
    fixture.gateway.ready()
    fixture.scheduler.runTimeout()

    assertEquals("E_NATIVE_FAILURE", fixture.result.errorCode)
    assertEquals(1, fixture.result.settlementCount)
  }

  @Test
  fun `rejects when React instance is unavailable`() {
    val fixture = Fixture(Consent.PENDING, active = false)

    fixture.operations.get(fixture.result)

    assertEquals("E_SDK_UNAVAILABLE", fixture.result.errorCode)
    assertEquals("The Data Cloud SDK is unavailable.", fixture.result.errorMessage)
    assertEquals(0, fixture.gateway.requestCount)
  }

  @Test
  fun `readiness rejects when React instance becomes unavailable while waiting`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.get(fixture.result)
    fixture.active = false
    fixture.gateway.ready()

    assertEquals("E_SDK_UNAVAILABLE", fixture.result.errorCode)
    assertEquals(1, fixture.result.settlementCount)
    assertEquals(0, fixture.gateway.getCount)
  }

  @Test
  fun `post-invalidation call rejects without readiness registration`() {
    val fixture = Fixture(Consent.PENDING)
    fixture.operations.invalidate()
    val result = RecordingResult()

    fixture.operations.get(result)

    assertEquals("E_SDK_UNAVAILABLE", result.errorCode)
    assertEquals(1, result.settlementCount)
    assertEquals(0, fixture.gateway.requestCount)
  }

  @Test
  fun `timeout scheduling failure rejects as native failure without readiness registration`() {
    val gateway = FakeGateway(Consent.PENDING)
    val scheduler = object : ReadinessScheduler {
      override fun schedule(delayMillis: Long, callback: () -> Unit) {
        throw IllegalStateException("scheduler unavailable")
      }

      override fun cancel(callback: () -> Unit) = Unit
    }
    val result = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() })

    operations.get(result)

    assertEquals("E_NATIVE_FAILURE", result.errorCode)
    assertEquals(1, result.settlementCount)
    assertEquals(0, gateway.requestCount)
  }

  @Test
  fun `each bridge call registers its own readiness listener`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.set("opt_out", fixture.result)
    val read = RecordingResult()
    fixture.operations.get(read)

    assertEquals(2, fixture.gateway.requestCount)
    fixture.gateway.ready(0)
    fixture.gateway.ready(1)

    assertTrue(fixture.result.resolved)
    assertEquals("opt_out", read.value)
  }

  @Test
  fun `set then get ready in reverse order execute in admission order`() {
    val fixture = Fixture(Consent.OPT_IN)
    val read = RecordingResult()

    fixture.operations.set("opt_out", fixture.result)
    fixture.operations.get(read)
    fixture.gateway.ready(1)
    fixture.gateway.ready(0)

    assertTrue(fixture.result.resolved)
    assertEquals("opt_out", read.value)
  }

  @Test
  fun `three operations ready in reverse order execute FIFO`() {
    val fixture = Fixture(Consent.PENDING)
    val firstRead = RecordingResult()
    val secondRead = RecordingResult()

    fixture.operations.set("opt_in", fixture.result)
    fixture.operations.get(firstRead)
    fixture.operations.get(secondRead)
    fixture.gateway.ready(2)
    fixture.gateway.ready(1)
    fixture.gateway.ready(0)

    assertTrue(fixture.result.resolved)
    assertEquals("opt_in", firstRead.value)
    assertEquals("opt_in", secondRead.value)
  }

  @Test
  fun `ready tail runs after unready head times out`() {
    val fixture = Fixture(Consent.OPT_IN)
    val tail = RecordingResult()

    fixture.operations.get(fixture.result)
    fixture.operations.set("opt_out", tail)
    fixture.gateway.ready(1)
    fixture.scheduler.runTimeout(0)

    assertEquals("E_SDK_NOT_READY", fixture.result.errorCode)
    assertTrue(tail.resolved)
    assertEquals(Consent.OPT_OUT, fixture.gateway.consent)
  }

  @Test
  fun `non-head timeout prevents late callback without disturbing head`() {
    val fixture = Fixture(Consent.PENDING)
    val tail = RecordingResult()
    val tailTimeout = AtomicReference<() -> Unit>()

    fixture.operations.set("opt_in", fixture.result)
    fixture.operations.set("opt_out", tail)
    tailTimeout.set(fixture.scheduler.captureTimeout(1))
    tailTimeout.get().invoke()
    fixture.gateway.ready(1)
    fixture.gateway.ready(0)

    assertTrue(fixture.result.resolved)
    assertEquals("E_SDK_NOT_READY", tail.errorCode)
    assertEquals(Consent.OPT_IN, fixture.gateway.consent)
    assertEquals(1, tail.settlementCount)
  }

  @Test
  fun `invalidation with unready head and ready tail rejects both and executes neither`() {
    val fixture = Fixture(Consent.PENDING)
    val tail = RecordingResult()

    fixture.operations.set("opt_in", fixture.result)
    fixture.operations.set("opt_out", tail)
    fixture.gateway.ready(1)
    fixture.operations.invalidate()
    fixture.gateway.ready(0)

    assertEquals("E_SDK_UNAVAILABLE", fixture.result.errorCode)
    assertEquals("E_SDK_UNAVAILABLE", tail.errorCode)
    assertEquals(Consent.PENDING, fixture.gateway.consent)
    assertEquals(1, fixture.gateway.unregisterCount)
  }

  @Test
  fun `invalidation preserves claimed action and rejects every unclaimed action`() {
    val actionStarted = CountDownLatch(1)
    val releaseAction = CountDownLatch(1)
    val gateway = FakeGateway(Consent.PENDING)
    gateway.beforeSet = { actionStarted.countDown(); releaseAction.await() }
    val scheduler = FakeScheduler()
    val claimed = RecordingResult()
    val waiting = RecordingResult()
    val readyTail = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() })
    val workerFailure = AtomicReference<Throwable?>()

    operations.set("opt_in", claimed)
    operations.get(waiting)
    operations.set("opt_out", readyTail)
    val ready = checkedThread(workerFailure) { gateway.ready(0) }
    actionStarted.await()
    gateway.ready(2)
    operations.invalidate()
    releaseAction.countDown()
    ready.join()
    workerFailure.get()?.let { throw AssertionError("readiness worker failed", it) }

    assertTrue(claimed.resolved)
    assertEquals("E_SDK_UNAVAILABLE", waiting.errorCode)
    assertEquals("E_SDK_UNAVAILABLE", readyTail.errorCode)
    assertEquals(Consent.OPT_IN, gateway.consent)
  }

  @Test
  fun `head native failure advances next ready action`() {
    val fixture = Fixture(Consent.OPT_IN)
    val tail = RecordingResult()
    var failFirst = true
    fixture.gateway.beforeGet = {
      if (failFirst) {
        failFirst = false
        throw IllegalStateException("first read failed")
      }
    }

    fixture.operations.get(fixture.result)
    fixture.operations.get(tail)
    fixture.gateway.ready(1)
    fixture.gateway.ready(0)

    assertEquals("E_NATIVE_FAILURE", fixture.result.errorCode)
    assertEquals("opt_in", tail.value)
  }

  @Test
  fun `blocked promise delivery queue does not block next native action`() {
    val fixture = Fixture(Consent.PENDING, queuedDispatch = true)
    val read = RecordingResult()

    fixture.operations.set("opt_in", fixture.result)
    fixture.operations.get(read)
    fixture.gateway.ready(0)
    fixture.gateway.ready(1)

    assertEquals(Consent.OPT_IN, fixture.gateway.consent)
    assertEquals(1, fixture.gateway.getCount)
    assertEquals(0, fixture.result.settlementCount)
    assertEquals(0, read.settlementCount)
    fixture.dispatcher.runNext()
    fixture.dispatcher.runNext()
    assertEquals("opt_in", read.value)
  }

  @Test
  fun `two coordinators share native executor and never overlap`() {
    val firstGateway = FakeGateway(Consent.PENDING)
    val secondGateway = FakeGateway(Consent.PENDING)
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val active = java.util.concurrent.atomic.AtomicInteger()
    val overlap = AtomicReference(false)
    firstGateway.beforeSet = {
      if (active.incrementAndGet() > 1) overlap.set(true)
      firstStarted.countDown()
      releaseFirst.await()
      active.decrementAndGet()
    }
    secondGateway.beforeSet = {
      if (active.incrementAndGet() > 1) overlap.set(true)
      secondStarted.countDown()
      active.decrementAndGet()
    }
    val first = ConsentOperations(firstGateway, FakeScheduler(), { true }, { it() })
    val second = ConsentOperations(secondGateway, FakeScheduler(), { true }, { it() })
    first.set("opt_in", RecordingResult())
    second.set("opt_out", RecordingResult())
    val workerFailure = AtomicReference<Throwable?>()
    val firstReady = checkedThread(workerFailure) { firstGateway.ready() }
    firstStarted.await()
    val secondReady = checkedThread(workerFailure) { secondGateway.ready() }

    assertFalse(secondStarted.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
    assertFalse(overlap.get())
    releaseFirst.countDown()
    firstReady.join()
    secondReady.join()
    workerFailure.get()?.let { throw AssertionError("native executor worker failed", it) }
    assertTrue(secondStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
    assertFalse(overlap.get())
  }

  @Test
  fun `invalidation rejects cross coordinator work waiting for native executor`() {
    val firstGateway = FakeGateway(Consent.PENDING)
    val secondGateway = FakeGateway(Consent.PENDING)
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondAttempted = CountDownLatch(1)
    val executorLock = Any()
    val submissions = java.util.concurrent.atomic.AtomicInteger()
    val executor = ConsentNativeExecutor { action ->
      if (submissions.incrementAndGet() == 2) secondAttempted.countDown()
      synchronized(executorLock) { action() }
    }
    firstGateway.beforeSet = { firstStarted.countDown(); releaseFirst.await() }
    val first = ConsentOperations(firstGateway, FakeScheduler(), { true }, { it() }, executor)
    val secondResult = RecordingResult()
    val second = ConsentOperations(secondGateway, FakeScheduler(), { true }, { it() }, executor)
    first.set("opt_in", RecordingResult())
    second.set("opt_out", secondResult)
    val workerFailure = AtomicReference<Throwable?>()
    val firstReady = checkedThread(workerFailure) { firstGateway.ready() }
    firstStarted.await()
    val secondReady = checkedThread(workerFailure) { secondGateway.ready() }
    assertTrue(secondAttempted.await(1, java.util.concurrent.TimeUnit.SECONDS))

    second.invalidate()
    releaseFirst.countDown()
    firstReady.join()
    secondReady.join()
    workerFailure.get()?.let { throw AssertionError("native executor worker failed", it) }

    assertEquals(Consent.PENDING, secondGateway.consent)
    assertEquals("E_SDK_UNAVAILABLE", secondResult.errorCode)
    assertEquals(1, secondResult.settlementCount)
  }

  @Test
  fun `ready tail rejects when React instance becomes unavailable before native claim`() {
    val actionStarted = CountDownLatch(1)
    val releaseAction = CountDownLatch(1)
    val gateway = FakeGateway(Consent.PENDING)
    var first = true
    gateway.beforeSet = {
      if (first) {
        first = false
        actionStarted.countDown()
        releaseAction.await()
      }
    }
    val active = AtomicBoolean(true)
    val scheduler = FakeScheduler()
    val head = RecordingResult()
    val tail = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, active::get, { it() })
    operations.set("opt_in", head)
    operations.set("opt_out", tail)
    val workerFailure = AtomicReference<Throwable?>()
    val headReady = checkedThread(workerFailure) { gateway.ready(0) }
    actionStarted.await()
    gateway.ready(1)

    active.set(false)
    releaseAction.countDown()
    headReady.join()
    workerFailure.get()?.let { throw AssertionError("readiness worker failed", it) }

    assertTrue(head.resolved)
    assertEquals("E_SDK_UNAVAILABLE", tail.errorCode)
    assertEquals(Consent.OPT_IN, gateway.consent)
  }

  @Test
  fun `ready tail timeout ends while head native action is blocked`() {
    val actionStarted = CountDownLatch(1)
    val releaseAction = CountDownLatch(1)
    val gateway = FakeGateway(Consent.PENDING)
    gateway.beforeSet = {
      actionStarted.countDown()
      assertTrue(releaseAction.await(1, java.util.concurrent.TimeUnit.SECONDS))
    }
    val scheduler = FakeScheduler()
    val head = RecordingResult()
    val tail = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() })

    operations.set("opt_in", head)
    operations.set("opt_out", tail)
    val tailTimeout = scheduler.captureTimeout(1)
    val workerFailure = AtomicReference<Throwable?>()
    val headReady = checkedThread(workerFailure) { gateway.ready(0) }
    assertTrue(actionStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
    gateway.ready(1)
    tailTimeout()
    assertEquals(0, tail.settlementCount)

    releaseAction.countDown()
    headReady.join(1_000)
    assertFalse("head readiness remained blocked", headReady.isAlive)
    workerFailure.get()?.let { throw AssertionError("readiness worker failed", it) }
    assertTrue(tail.resolved)
    assertEquals(Consent.OPT_OUT, gateway.consent)
  }

  @Test
  fun `executor rejection advances next ready operation exactly once`() {
    val gateway = FakeGateway(Consent.PENDING)
    val scheduler = FakeScheduler()
    val first = RecordingResult()
    val second = RecordingResult()
    var submissions = 0
    val executor = ConsentNativeExecutor { action ->
      submissions++
      if (submissions == 1) throw IllegalStateException("executor rejected action")
      action()
    }
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() }, executor)

    operations.set("opt_in", first)
    operations.set("opt_out", second)
    gateway.ready(1)
    gateway.ready(0)

    assertEquals("E_NATIVE_FAILURE", first.errorCode)
    assertEquals(1, first.settlementCount)
    assertTrue(second.resolved)
    assertEquals(Consent.OPT_OUT, gateway.consent)
  }

  @Test
  fun `large ready burst advances without recursive stack growth`() {
    val operationCount = 5_000
    val fixture = Fixture(Consent.OPT_IN)
    val results = List(operationCount) { RecordingResult() }

    results.forEach(fixture.operations::get)
    for (index in operationCount - 1 downTo 1) fixture.gateway.ready(index)
    fixture.gateway.ready(0)

    assertEquals(operationCount, results.sumOf { it.settlementCount })
    assertTrue(results.all { it.value == "opt_in" })
  }

  @Test
  fun `unready operation in one coordinator does not block ready operation in another`() {
    val firstGateway = FakeGateway(Consent.PENDING)
    val secondGateway = FakeGateway(Consent.PENDING)
    val first = ConsentOperations(firstGateway, FakeScheduler(), { true }, { it() })
    val second = ConsentOperations(secondGateway, FakeScheduler(), { true }, { it() })
    val waiting = RecordingResult()
    val ready = RecordingResult()

    first.set("opt_in", waiting)
    second.set("opt_out", ready)
    secondGateway.ready()

    assertEquals(0, waiting.settlementCount)
    assertTrue(ready.resolved)
    assertEquals(Consent.OPT_OUT, secondGateway.consent)
  }

  @Test
  fun `global native boundary is released before promise dispatch runs`() {
    val firstGateway = FakeGateway(Consent.PENDING)
    val secondGateway = FakeGateway(Consent.PENDING)
    val secondResult = RecordingResult()
    val workerFailure = AtomicReference<Throwable?>()
    val secondFinished = CountDownLatch(1)
    val second = ConsentOperations(secondGateway, FakeScheduler(), { true }, { it() })
    second.set("opt_out", secondResult)
    val first = ConsentOperations(firstGateway, FakeScheduler(), { true }, { callback ->
      val worker = checkedThread(workerFailure) {
        secondGateway.ready()
        secondFinished.countDown()
      }
      assertTrue(secondFinished.await(1, java.util.concurrent.TimeUnit.SECONDS))
      worker.join(1_000)
      callback()
    })

    first.set("opt_in", RecordingResult())
    firstGateway.ready()

    workerFailure.get()?.let { throw AssertionError("second coordinator failed", it) }
    assertTrue(secondResult.resolved)
  }

  @Test
  fun `get submitted after set resolves observes updated native consent`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.set("opt_out", fixture.result)
    fixture.gateway.ready()
    assertTrue(fixture.result.resolved)

    val read = RecordingResult()
    fixture.operations.get(read)
    fixture.gateway.ready()

    assertEquals("opt_out", read.value)
    assertEquals(2, fixture.gateway.requestCount)
  }

  @Test
  fun `readiness closes batch and later arrival registers afresh`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.get(fixture.result)
    fixture.gateway.ready()
    fixture.operations.get(RecordingResult())

    assertEquals(2, fixture.gateway.requestCount)
  }

  @Test
  fun `ready callback after timeout cannot settle twice`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.get(fixture.result)
    fixture.scheduler.runTimeout(0)
    fixture.gateway.ready(0)

    assertEquals("E_SDK_NOT_READY", fixture.result.errorCode)
    assertEquals(1, fixture.result.settlementCount)
  }

  @Test
  fun `cancelled timeout callback firing after ready cannot settle twice`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.get(fixture.result)
    val timeout = fixture.scheduler.captureTimeout(0)
    fixture.gateway.ready(0)
    timeout()

    assertEquals("opt_in", fixture.result.value)
    assertEquals(1, fixture.result.settlementCount)
  }

  @Test
  fun `ready callback after invalidate cannot settle twice`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.get(fixture.result)
    fixture.operations.invalidate()
    fixture.gateway.ready(0)

    assertEquals("E_SDK_UNAVAILABLE", fixture.result.errorCode)
    assertEquals(1, fixture.result.settlementCount)
  }

  @Test
  fun `cancelled timeout callback firing after invalidate cannot settle twice`() {
    val fixture = Fixture(Consent.OPT_IN)

    fixture.operations.get(fixture.result)
    val timeout = fixture.scheduler.captureTimeout(0)
    fixture.operations.invalidate()
    timeout()

    assertEquals("E_SDK_UNAVAILABLE", fixture.result.errorCode)
    assertEquals(1, fixture.result.settlementCount)
  }

  @Test
  fun `timeout and ready race settles exactly once`() {
    repeat(100) {
      val fixture = Fixture(Consent.OPT_IN)
      fixture.operations.get(fixture.result)
      val start = CountDownLatch(1)
      val workerFailure = AtomicReference<Throwable?>()
      val ready = checkedThread(workerFailure) { start.await(); fixture.gateway.ready(0) }
      val timeout = checkedThread(workerFailure) { start.await(); fixture.scheduler.runTimeout(0) }

      start.countDown()
      ready.join()
      timeout.join()
      workerFailure.get()?.let { throw AssertionError("race worker failed", it) }

      assertEquals(1, fixture.result.settlementCount)
    }
  }

  @Test
  fun `invalidate and ready race settles exactly once`() {
    repeat(100) {
      val fixture = Fixture(Consent.OPT_IN)
      fixture.operations.get(fixture.result)
      val start = CountDownLatch(1)
      val workerFailure = AtomicReference<Throwable?>()
      val ready = checkedThread(workerFailure) { start.await(); fixture.gateway.ready(0) }
      val invalidate = checkedThread(workerFailure) { start.await(); fixture.operations.invalidate() }

      start.countDown()
      ready.join()
      invalidate.join()
      workerFailure.get()?.let { throw AssertionError("race worker failed", it) }

      assertEquals(1, fixture.result.settlementCount)
    }
  }

  private fun checkedThread(failure: AtomicReference<Throwable?>, action: () -> Unit) = thread {
    try {
      action()
    } catch (throwable: Throwable) {
      failure.compareAndSet(null, throwable)
    }
  }

  @Test
  fun `request failure rejects once and cleans up`() {
    val fixture = Fixture(Consent.PENDING)
    fixture.gateway.requestFailure = IllegalStateException("request failed")

    fixture.operations.get(fixture.result)

    assertEquals("E_NATIVE_FAILURE", fixture.result.errorCode)
    assertEquals(1, fixture.result.settlementCount)
    assertFalse(fixture.scheduler.pending)
  }

  @Test
  fun `unregister failure cannot rewrite selected timeout`() {
    val fixture = Fixture(Consent.OPT_IN)
    fixture.gateway.unregisterFailure = IllegalStateException("unregister failed")

    fixture.operations.get(fixture.result)
    fixture.scheduler.runTimeout(0)

    assertEquals("E_SDK_NOT_READY", fixture.result.errorCode)
    assertEquals(1, fixture.result.settlementCount)
    assertFalse(fixture.scheduler.pending)
  }

  @Test
  fun `invalidation returns while authorized native action is blocked and native success wins`() {
    val actionStarted = CountDownLatch(1)
    val releaseAction = CountDownLatch(1)
    val gateway = FakeGateway(Consent.PENDING)
    gateway.beforeSet = {
      actionStarted.countDown()
      releaseAction.await()
    }
    val scheduler = FakeScheduler()
    val result = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() })
    val workerFailure = AtomicReference<Throwable?>()

    operations.set("opt_in", result)
    val ready = checkedThread(workerFailure) { gateway.ready() }
    actionStarted.await()

    val invalidate = checkedThread(workerFailure) { operations.invalidate() }
    invalidate.join(1_000)

    assertFalse("invalidation blocked on authorized native action", invalidate.isAlive)
    scheduler.runTimeout()
    releaseAction.countDown()
    ready.join()
    workerFailure.get()?.let { throw AssertionError("readiness worker failed", it) }

    assertEquals(Consent.OPT_IN, gateway.consent)
    assertTrue(result.resolved)
    assertEquals(1, result.settlementCount)
  }

  @Test
  fun `timeout during gateway request installation unregisters eventual registration exactly once`() {
    val gateway = BlockingConsentGateway()
    val scheduler = FakeScheduler()
    val result = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() })
    val request = thread { operations.get(result) }
    gateway.awaitRequest()

    scheduler.runTimeout()
    gateway.releaseRequest()
    request.join()

    assertEquals("E_SDK_NOT_READY", result.errorCode)
    assertEquals(1, result.settlementCount)
    assertEquals(1, gateway.unregisterCount)
    assertFalse(gateway.registered)
  }

  @Test
  fun `invalidation during gateway request installation unregisters eventual registration exactly once`() {
    val gateway = BlockingConsentGateway()
    val scheduler = FakeScheduler()
    val result = RecordingResult()
    val operations = ConsentOperations(gateway, scheduler, { true }, { it() })
    val request = thread { operations.get(result) }
    gateway.awaitRequest()

    operations.invalidate()
    gateway.releaseRequest()
    request.join()

    assertEquals("E_SDK_UNAVAILABLE", result.errorCode)
    assertEquals(1, result.settlementCount)
    assertEquals(1, gateway.unregisterCount)
    assertFalse(gateway.registered)
  }

  @Test
  fun `promise callback exceptions do not trigger second settlement`() {
    val fixture = Fixture(Consent.OPT_IN)
    val result = ThrowingResult()

    fixture.operations.get(result)
    fixture.gateway.ready()
    fixture.scheduler.runTimeout()
    fixture.operations.invalidate()

    assertEquals(1, result.settlementCount)
  }

  @Test
  fun `result callback can invalidate without deadlock and settles exactly once`() {
    val gateway = FakeGateway(Consent.OPT_IN)
    val scheduler = FakeScheduler()
    val workerFailure = AtomicReference<Throwable?>()
    lateinit var operations: ConsentOperations
    var settlementCount = 0
    val result = object : ConsentResult {
      override fun resolve(value: Any?) {
        settlementCount++
        val invalidationStarted = CountDownLatch(1)
        val invalidation = checkedThread(workerFailure) {
          invalidationStarted.countDown()
          operations.invalidate()
        }
        invalidationStarted.await()
        invalidation.join(1_000)
        assertFalse("invalidation blocked on result delivery", invalidation.isAlive)
      }

      override fun reject(code: String, message: String, cause: Throwable?) {
        settlementCount++
      }
    }
    operations = ConsentOperations(gateway, scheduler, { true }, { it() })

    operations.get(result)
    gateway.ready()
    workerFailure.get()?.let { throw AssertionError("invalidation worker failed", it) }

    assertEquals(1, settlementCount)
  }

  @Test
  fun `dispatch failure rejects safely without double settlement`() {
    val fixture = Fixture(Consent.OPT_IN)
    fixture.dispatcher.failure = IllegalStateException("queue unavailable")

    fixture.operations.get(fixture.result)
    fixture.scheduler.runTimeout(0)

    assertEquals("E_SDK_NOT_READY", fixture.result.errorCode)
    assertEquals(1, fixture.result.settlementCount)
  }

  private class Fixture(initialConsent: Consent, active: Boolean = true, queuedDispatch: Boolean = false) {
    val gateway = FakeGateway(initialConsent)
    val scheduler = FakeScheduler()
    val result = RecordingResult()
    var active = active
    val dispatcher = FakeDispatcher(queuedDispatch)
    val operations = ConsentOperations(gateway, scheduler, { this.active }, dispatcher::dispatch)
  }

  private class FakeCdpReadinessRegistration(
    private val synchronous: Boolean = false,
    private val requestFailure: RuntimeException? = null
  ) : CdpReadinessRegistration {
    val access = object : ConsentAccess {
      override var consent = Consent.PENDING
    }
    private var registeredListener: ((ConsentAccess) -> Unit)? = null
    private var callback: ((ConsentAccess) -> Unit)? = null
    var requestCount = 0
      private set
    var unregisterCount = 0

    override fun request(listener: (ConsentAccess) -> Unit) {
      requestCount++
      registeredListener = listener
      callback = listener
      if (synchronous) listener(access)
      requestFailure?.let { throw it }
    }

    override fun unregister(listener: (ConsentAccess) -> Unit) {
      if (registeredListener !== listener) return
      registeredListener = null
      unregisterCount++
    }

    fun ready() {
      callback?.invoke(access)
    }
  }

  private class BlockingCdpReadinessRegistration : CdpReadinessRegistration {
    val access = object : ConsentAccess {
      override var consent = Consent.PENDING
    }
    private val requestStarted = CountDownLatch(1)
    private val requestRelease = CountDownLatch(1)
    private var listener: ((ConsentAccess) -> Unit)? = null
    var unregisterCount = 0
      private set
    val registered get() = synchronized(this) { listener != null }

    override fun request(listener: (ConsentAccess) -> Unit) {
      requestStarted.countDown()
      requestRelease.await()
      synchronized(this) { this.listener = listener }
    }

    @Synchronized
    override fun unregister(listener: (ConsentAccess) -> Unit) {
      if (this.listener !== listener) return
      this.listener = null
      unregisterCount++
    }

    fun awaitRequest() {
      requestStarted.await()
    }

    fun releaseRequest() {
      requestRelease.countDown()
    }

    fun ready() {
      synchronized(this) { listener }?.invoke(access)
    }
  }

  private class FakeGateway(initialConsent: Consent) : ConsentGateway {
    @Volatile
    var consent = initialConsent
    private val listeners = mutableListOf<() -> ConsentAccess>()
    private val registeredListeners = mutableSetOf<() -> ConsentAccess>()
    val listener get() = synchronized(this) { listeners.lastOrNull() }
    var unregisteredListener: (() -> ConsentAccess)? = null
    var requestCount = 0
    var unregisterCount = 0
    var failure: RuntimeException? = null
    var requestFailure: RuntimeException? = null
    var unregisterFailure: RuntimeException? = null
    var readySynchronously = false
    var callbackObservedRegistered = false
    var beforeSet: (() -> Unit)? = null
    var beforeGet: (() -> Unit)? = null
    var getCount = 0

    @Synchronized
    override fun request(listener: () -> ConsentAccess) {
      requestCount++
      requestFailure?.let { throw it }
      listeners.add(listener)
      registeredListeners.add(listener)
      if (readySynchronously) ready(listeners.lastIndex)
    }

    @Synchronized
    override fun unregister(listener: () -> ConsentAccess) {
      if (!registeredListeners.remove(listener)) return
      unregisterCount++
      unregisteredListener = listener
      unregisterFailure?.let { throw it }
    }

    fun ready(index: Int = listeners.lastIndex) {
      val callback = synchronized(this) {
        val selected = listeners[index]
        if (!registeredListeners.remove(selected)) return
        callbackObservedRegistered = registeredListeners.any { it === selected }
        selected
      }
      callback.invoke()
    }

    override fun currentAccess(): ConsentAccess = object : ConsentAccess {
      override var consent: Consent
        get() {
          getCount++
          beforeGet?.invoke()
          return failure?.let { throw it } ?: this@FakeGateway.consent
        }
        set(value) {
          beforeSet?.invoke()
          failure?.let { throw it }
          this@FakeGateway.consent = value
        }
    }
  }

  private class BlockingConsentGateway : ConsentGateway {
    private val requestStarted = CountDownLatch(1)
    private val requestRelease = CountDownLatch(1)
    private var listener: (() -> ConsentAccess)? = null
    val registered get() = synchronized(this) { listener != null }
    var unregisterCount = 0
      private set

    override fun request(listener: () -> ConsentAccess) {
      requestStarted.countDown()
      requestRelease.await()
      synchronized(this) { this.listener = listener }
    }

    @Synchronized
    override fun unregister(listener: () -> ConsentAccess) {
      if (this.listener !== listener) return
      this.listener = null
      unregisterCount++
    }

    override fun currentAccess(): ConsentAccess = error("not ready")

    fun awaitRequest() {
      requestStarted.await()
    }

    fun releaseRequest() {
      requestRelease.countDown()
    }
  }

  private class FakeScheduler : ReadinessScheduler {
    private val callbacks = mutableListOf<() -> Unit>()
    val pending get() = synchronized(callbacks) { callbacks.isNotEmpty() }

    override fun schedule(delayMillis: Long, callback: () -> Unit) {
      assertEquals(10_000L, delayMillis)
      synchronized(callbacks) { callbacks.add(callback) }
    }

    override fun cancel(callback: () -> Unit) {
      synchronized(callbacks) { callbacks.removeAll { it === callback } }
    }

    fun runTimeout(index: Int = 0) {
      val scheduled = synchronized(callbacks) {
        callbacks.getOrNull(index)?.also { callbacks.removeAt(index) }
      }
      scheduled?.invoke()
    }

    fun captureTimeout(index: Int): () -> Unit = synchronized(callbacks) { callbacks[index] }
  }

  private class BlockingScheduler : ReadinessScheduler {
    private val scheduleStarted = CountDownLatch(1)
    private val scheduleRelease = CountDownLatch(1)
    private val callbacks = mutableListOf<() -> Unit>()
    val pending get() = synchronized(callbacks) { callbacks.isNotEmpty() }

    override fun schedule(delayMillis: Long, callback: () -> Unit) {
      assertEquals(10_000L, delayMillis)
      scheduleStarted.countDown()
      scheduleRelease.await()
      synchronized(callbacks) { callbacks.add(callback) }
    }

    override fun cancel(callback: () -> Unit) {
      synchronized(callbacks) { callbacks.removeAll { it === callback } }
    }

    fun awaitSchedule() {
      scheduleStarted.await()
    }

    fun releaseSchedule() {
      scheduleRelease.countDown()
    }
  }

  private class FakeDispatcher(private val queued: Boolean) {
    private val callbacks = mutableListOf<() -> Unit>()
    var failure: RuntimeException? = null
    var enqueueBeforeFailure = false

    fun dispatch(callback: () -> Unit) {
      failure?.let {
        if (enqueueBeforeFailure) callbacks.add(callback)
        throw it
      }
      if (queued) callbacks.add(callback) else callback()
    }

    fun runNext() {
      callbacks.removeAt(0).invoke()
    }
  }

  private class RecordingResult : ConsentResult {
    var value: String? = null
    var errorCode: String? = null
    var errorMessage: String? = null
    var resolved = false
    var settlementCount = 0

    @Synchronized
    override fun resolve(value: Any?) {
      settlementCount++
      resolved = true
      this.value = value as? String
    }

    @Synchronized
    override fun reject(code: String, message: String, cause: Throwable?) {
      settlementCount++
      errorCode = code
      errorMessage = message
    }
  }

  private class ThrowingResult : ConsentResult {
    var settlementCount = 0

    override fun resolve(value: Any?) {
      settlementCount++
      throw IllegalStateException("resolve failed")
    }

    override fun reject(code: String, message: String, cause: Throwable?) {
      settlementCount++
      throw IllegalStateException("reject failed")
    }
  }
}
