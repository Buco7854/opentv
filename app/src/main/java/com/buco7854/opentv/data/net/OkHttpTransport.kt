package com.buco7854.opentv.data.net

import com.buco7854.opentv.core.net.HttpRequestSpec
import com.buco7854.opentv.core.net.HttpResponseSpec
import com.buco7854.opentv.core.net.HttpTransport
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * [HttpTransport] over the shared [Http] client. Executes the spec verbatim;
 * only a User-Agent is added when the spec carries none, identifying the app
 * itself rather than [Http.userAgent]'s provider-facing spoof.
 */
class OkHttpTransport(
    private val userAgent: () -> String = { APP_USER_AGENT },
) : HttpTransport {

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        if (request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", userAgent())
        }
        val mediaType = (request.contentType ?: "application/json").toMediaType()
        // Use bytes rather than OkHttp's String overload: that overload rewrites
        // `application/json` to `application/json; charset=utf-8`, while this
        // transport promises to carry HttpRequestSpec's content type verbatim.
        val body = request.body?.toByteArray(Charsets.UTF_8)?.toRequestBody(mediaType)
            // OkHttp rejects body-less POST/PUT/PATCH; the server treats them as empty.
            ?: if (request.method in BODY_REQUIRED) ByteArray(0).toRequestBody(mediaType) else null
        builder.method(request.method, body)
        return Http.ok.newCall(builder.build()).executeCancellable { response ->
            HttpResponseSpec(
                status = response.code,
                headers = response.headers.toMultimap(),
                bodyText = response.body.string(),
            )
        }
    }

    companion object {
        const val APP_USER_AGENT = "OpenTV-Android"
        private val BODY_REQUIRED = setOf("POST", "PUT", "PATCH")
    }
}

/**
 * Runs a blocking OkHttp exchange on IO while keeping the [Call] tied to the
 * coroutine for the entire response-body read, not just until headers arrive.
 */
internal suspend fun <T> Call.executeCancellable(
    block: suspend (Response) -> T,
): T = coroutineScope {
    val call = this@executeCancellable
    val exchangeFinished = AtomicBoolean()
    val cancellation = launch(
        context = Dispatchers.Unconfined,
        start = CoroutineStart.UNDISPATCHED,
    ) {
        try {
            awaitCancellation()
        } finally {
            if (!exchangeFinished.get()) {
                call.cancel()
            }
        }
    }
    try {
        withContext(Dispatchers.IO) {
            try {
                call.execute().use { response -> block(response) }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                throw error
            }
        }
    } finally {
        if (currentCoroutineContext().isActive) {
            exchangeFinished.set(true)
        }
        cancellation.cancelAndJoin()
    }
}
