package com.buco7854.opentv.hub

import com.buco7854.opentv.core.net.HttpResponseSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed failures for hub calls. Semantics callers must respect:
 * - 401 means the session is gone: surface "signed out", never retry-loop.
 * - 410 is the ONLY "stop playing" signal for a lease; 404 never is.
 * - 429 carries pacing; honor [HubCapacityException.retryAfterMs] strictly
 *   (the hub's auth rate limiter escalates on ignored backoff).
 */
sealed class HubException(message: String) : Exception(message) {
    /** The machine-readable `ApiErrorDto.code`, when the body carried one. */
    open val code: String? get() = null
}

class HubUnreachableException(message: String, cause: Throwable? = null) :
    HubException(message) {
    init {
        cause?.let(::initCause)
    }
}

class HubUnauthorizedException(override val code: String?, message: String) : HubException(message)
class HubForbiddenException(override val code: String?, message: String) : HubException(message)
class HubNotFoundException(override val code: String?, message: String) : HubException(message)
class HubGoneException(override val code: String?, message: String) : HubException(message)
class HubDuplicatePlaybackException(override val code: String?, message: String) : HubException(message)
class HubCapacityException(override val code: String?, message: String, val retryAfterMs: Long?) :
    HubException(message)

class HubServerException(val status: Int, override val code: String?, message: String) :
    HubException(message)

/** Any other non-2xx (400, 405, ...) — the request itself was wrong. */
class HubApiException(val status: Int, override val code: String?, message: String) :
    HubException(message)

/** A successful response did not satisfy the hub protocol's security contract. */
class HubProtocolException(message: String) : HubException(message)

/** Maps a non-2xx hub response to its typed exception. Auth-flow callers decode their expected
 *  conflict bodies before reaching this mapper; ordinary 409 responses remain typed failures. */
fun hubFailure(response: HttpResponseSpec): HubException {
    val (code, message) = parseApiError(response.bodyText)
    val text = message ?: code ?: "HTTP ${response.status}"
    return when (response.status) {
        401 -> HubUnauthorizedException(code, text)
        403 -> HubForbiddenException(code, text)
        404 -> HubNotFoundException(code, text)
        409 -> if (code == SAME_CONTENT_ALREADY_PLAYING) {
            HubDuplicatePlaybackException(code, text)
        } else {
            HubApiException(response.status, code, text)
        }
        410 -> HubGoneException(code, text)
        429 -> HubCapacityException(code, text, response.header("Retry-After")?.toLongOrNull()?.times(1000))
        in 500..599 -> HubServerException(response.status, code, text)
        else -> HubApiException(response.status, code, text)
    }
}

private const val SAME_CONTENT_ALREADY_PLAYING = "same_content_already_playing"

/** Lenient `ApiErrorDto` read: any non-JSON or unexpected body degrades to nulls. */
internal fun parseApiError(body: String): Pair<String?, String?> = runCatching {
    val obj = Json.parseToJsonElement(body).jsonObject
    val code = obj["code"]?.jsonPrimitive?.content
    val message = obj["message"]?.jsonPrimitive?.content
    code to message
}.getOrDefault(null to null)
