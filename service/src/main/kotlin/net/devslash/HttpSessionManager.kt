package net.devslash

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

typealias Contents = Pair<HttpRequest, RequestData>

class HttpSessionManager(private val engine: Driver, private val session: Session) : SessionManager {

  private val semaphore = Semaphore(0)
  private var lastCall = 0L
  private val clock = Clock.systemUTC()
  private lateinit var sessionManager: SessionManager

  fun run() {
    val jar = CookieJar()
    engine.use {
      sessionManager = this
      for (call in session.calls) {
        call(call, jar)?.let {
          throw RuntimeException(it)
        }
      }
    }
  }

  override fun <T> call(call: Call<T>): Exception? = call(call, CookieJar())

  override fun <T> call(call: Call<T>, jar: CookieJar): Exception? = runBlocking(Dispatchers.Default) {
    // Okay, so in here we're going to do the one to many calls we have to to get this to run.
    val channel: Channel<Envelope<Pair<HttpRequest, RequestData>>> =
      Channel(session.concurrency * 2)
    val produceThreadPool = System.getProperty("PRODUCE_THREAD_POOL_SIZE")?.toInt()
      ?: Runtime.getRuntime().availableProcessors()
    val produceExecutor = Executors.newFixedThreadPool(produceThreadPool)
    val produceDispatcher = produceExecutor.asCoroutineDispatcher()
    launch(produceDispatcher) { RequestProducer().produceHttp(sessionManager, call, jar, channel) }

    val afterRequest: MutableList<AfterHook> = mutableListOf(jar)
    afterRequest.addAll(call.afterHooks)

    val delay = session.delay
    val hasDelay = delay != null && delay > 0

    if (hasDelay) {
      println(
        "Delay has been set to $delay ms. This means that after a call has been made, " + //
                "there will be a delay of at least $delay ms before the beginning of the next one.\n" + //
                "Due to a delay being set - the number of HTTP threads has been locked to 1. " + //
                "Effectively `session.concurrency = 1`"
      )
    }
    val limiter = AcquiringRateLimiter(session.rateOptions)

    val threadPool = System.getProperty("HTTP_THREAD_POOL_SIZE")?.toInt()
      ?: Runtime.getRuntime().availableProcessors() * 2
    val httpThreadPool = Executors.newFixedThreadPool(if (hasDelay) 1 else threadPool)
    val dispatcher = httpThreadPool.asCoroutineDispatcher()
    semaphore.release(session.concurrency)

    val jobs = mutableListOf<Job>()
    val concurrency = if (hasDelay) 1 else session.concurrency
    val storedException = AtomicReference<Exception>(null)
    repeat(concurrency) {
      jobs += launchHttpProcessor(
        call,
        limiter,
        afterRequest,
        channel,
        dispatcher,
        storedException
      )
    }
    jobs.joinAll()
    produceExecutor.shutdownNow()
    httpThreadPool.shutdownNow()
    produceExecutor.awaitTermination(100L, TimeUnit.MILLISECONDS)
    httpThreadPool.awaitTermination(100L, TimeUnit.MILLISECONDS)
    storedException.get()
  }

  private fun <T> CoroutineScope.launchHttpProcessor(
    call: Call<T>,
    rateLimiter: AcquiringRateLimiter,
    afterRequest: List<AfterHook>,
    channel: Channel<Envelope<Pair<HttpRequest, RequestData>>>,
    dispatcher: CoroutineContext,
    storedException: AtomicReference<Exception>
  ) = launch(dispatcher) {
    for (next in channel) {
      if (storedException.get() != null) {
        // Break out in the event we've detected an exception somewhere
        break
      }
      try {
        if (next.shouldProceed()) {
          val contents = next.get()
          rateLimiter.acquire()
          when (val resp = makeRequest(contents.first)) {
            is Failure -> handleFailure(call, channel, next, resp)
            is Success -> handleSuccess(resp, afterRequest, contents)
          }
        }
      } catch (e: Exception) {
        storedException.set(e)
        break
      }
    }
  }

  private fun handleSuccess(
    resp: Success<HttpResponse>,
    afterRequest: List<AfterHook>,
    contents: Pair<HttpRequest, RequestData>
  ) {
    val mappedResponse = resp.value
    afterRequest.forEach {
      when (it) {
        is SimpleAfterHook -> it.accept(mappedResponse.copy())
        is BodyMutatingAfterHook -> it.accept(mappedResponse)
        is FullDataAfterHook -> it.accept(contents.first, mappedResponse, contents.second)
      }
    }
  }

  private suspend fun <T> handleFailure(
    call: Call<T>,
    channel: Channel<Envelope<Pair<HttpRequest, RequestData>>>,
    next: Envelope<Pair<HttpRequest, RequestData>>,
    exception: Failure<java.lang.Exception>
  ) {
    when (val onError = call.onError) {
      is OnErrorWithState -> onError.accept(channel, next, exception.err)
      else -> {
        throw exception.err
      }
    }
  }


  private suspend fun makeRequest(modelRequest: HttpRequest): HttpResult<HttpResponse, java.lang.Exception> {
    maybeDelay()
    val result = engine.call(modelRequest)
    lastCall = clock.millis()
    return result
  }

  private suspend fun maybeDelay() {
    session.delay?.let {
      require(it > 0)
      // Then between every call, we have to have waited at least that many ms
      val diff = clock.millis() - lastCall
      // we have to wait for the diff. Due to the fact that delays institute a single threaded
      // system, this is safe. Negative delay returns instantly
      delay(it - diff)
    }
  }
}


