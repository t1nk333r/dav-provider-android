package xyz.satr.davprovider.net

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * An [Interceptor.Chain] that records the request it is handed and answers with a bare 200.
 *
 * OkHttp's Android artifact reaches for `android.util.Log` while a *client* is being built, so a test
 * that wanted a real client would need an Android runtime. An interceptor's own contract needs none:
 * what it does is decide what to put in the request it proceeds with, and that decision is what
 * these tests assert on.
 */
internal class FakeChain(
    private val request: Request,
    private val onProceed: (Request) -> Unit,
) : Interceptor.Chain {

    override fun request(): Request = request

    override fun proceed(request: Request): Response {
        onProceed(request)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody(null))
            .build()
    }

    override fun connection(): Connection? = null

    override fun call(): Call = throw UnsupportedOperationException("interceptors under test never use the call")

    override fun connectTimeoutMillis(): Int = 0

    override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    override fun readTimeoutMillis(): Int = 0

    override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    override fun writeTimeoutMillis(): Int = 0

    override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
}
