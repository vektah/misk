package misk.client

import com.google.common.base.Stopwatch
import com.google.common.base.Ticker
import com.squareup.wire.GrpcMethod
import io.prometheus.client.Histogram
import io.prometheus.client.Summary
import misk.metrics.v2.Metrics
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Invocation
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class ClientMetricsInterceptor private constructor(
  val clientName: String,
  private val requestDurationSummary: Summary,
  private val requestDurationHistogram: Histogram,
) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    val actionName = actionName(chain)
      ?: return chain.proceed(chain.request())

    val stopwatch = Stopwatch.createStarted(Ticker.systemTicker())
    try {
      val result = chain.proceed(chain.request())
      val elapsedMillis = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS).toDouble()
      requestDurationSummary.labels(actionName, "${result.code}").observe(elapsedMillis)
      requestDurationHistogram.labels(actionName, "${result.code}").observe(elapsedMillis)
      return result
    } catch (e: SocketTimeoutException) {
      val elapsedMillis = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS).toDouble()
      requestDurationSummary.labels(actionName, "timeout").observe(elapsedMillis)
      requestDurationHistogram.labels(actionName, "timeout").observe(elapsedMillis)
      throw e
    }
  }

  private fun actionName(chain: Interceptor.Chain): String? {
    val invocation = chain.request().tag(Invocation::class.java)
    if (invocation != null) return "$clientName.${invocation.method().name}"

    val grpcMethod = chain.request().tag(GrpcMethod::class.java)
    if (grpcMethod != null) return "$clientName.${grpcMethod.path.substringAfterLast("/")}"

    return null
  }

  @Singleton
  class Factory @Inject internal constructor(m: Metrics) {
    internal val requestDuration = m.summary(
      name = "client_http_request_latency_ms",
      help = "count and duration in ms of outgoing client requests",
      labelNames = listOf("action", "code")
    )
    internal val requestDurationHistogram = m.histogram(
      name = "histo_client_http_request_latency_ms",
      help = "histogram in ms of outgoing client requests",
      labelNames = listOf("action", "code")
    )

    fun create(clientName: String) = ClientMetricsInterceptor(clientName, requestDuration, requestDurationHistogram)
  }
}
