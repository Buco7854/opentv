package com.buco7854.opentv.hub.playback

import com.buco7854.opentv.contract.HeartbeatResponseDto
import com.buco7854.opentv.contract.GrantControlBody
import com.buco7854.opentv.contract.JoinAnswerBody
import com.buco7854.opentv.contract.JoinRequestBody
import com.buco7854.opentv.contract.KickBody
import com.buco7854.opentv.contract.MediaGrantDto
import com.buco7854.opentv.contract.PlaybackCreateRequest
import com.buco7854.opentv.contract.PlaybackLeaseDto
import com.buco7854.opentv.contract.ReadyBody
import com.buco7854.opentv.contract.RemuxStartDto
import com.buco7854.opentv.contract.RequestControlBody
import com.buco7854.opentv.contract.RoomAudioBody
import com.buco7854.opentv.contract.SetControlBody
import com.buco7854.opentv.contract.SessionHeartbeatDto
import com.buco7854.opentv.contract.SyncStateDto
import com.buco7854.opentv.contract.WatchIntentResponse
import com.buco7854.opentv.contract.WebSocketAccessDto
import com.buco7854.opentv.core.net.HttpRequestSpec
import com.buco7854.opentv.core.net.HttpResponseSpec
import com.buco7854.opentv.core.net.HttpTransport
import com.buco7854.opentv.core.net.Urls
import com.buco7854.opentv.hub.HubClient
import com.buco7854.opentv.hub.HubEndpoints
import com.buco7854.opentv.hub.HubUnreachableException
import com.buco7854.opentv.hub.hubFailure
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

data class HubMediaGrant(
    val token: String,
    val expiresAtMs: Long,
)

/**
 * The playback control surface consumed by [HubPlaybackController].
 *
 * Keeping this interface free of Android and player types makes lease ownership
 * testable with virtual time.
 */
interface HubPlaybackApi {
    val baseUrl: String

    suspend fun createLease(request: PlaybackCreateRequest): PlaybackLeaseDto
    suspend fun heartbeat(leaseId: String, heartbeat: SessionHeartbeatDto): HeartbeatResponseDto
    suspend fun webSocketAccess(leaseId: String): WebSocketAccessDto
    suspend fun sync(leaseId: String, state: SyncStateDto)
    suspend fun watchIntent(leaseId: String): WatchIntentResponse =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun watchAlone(leaseId: String): Unit =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun requestJoin(leaseId: String, peerId: String): Unit =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun answerJoin(leaseId: String, requestId: String, accept: Boolean): Unit =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun requestControl(leaseId: String): Unit =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun grantControl(leaseId: String, peerId: String, grant: Boolean): Unit =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun setControl(leaseId: String, targetId: String, grant: Boolean): Unit =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun kick(leaseId: String, targetId: String): Unit =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun setRoomAudio(leaseId: String, audioTrackIndex: Int): Unit =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun ready(leaseId: String, generation: Long): Unit =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun leaveRoom(leaseId: String): Unit =
        throw UnsupportedOperationException("Watch together is not configured")
    suspend fun refreshMediaGrant(leaseId: String): HubMediaGrant
    suspend fun startRemux(
        startUrl: String,
        audioTrackIndex: Int,
        timeshift: Boolean,
        mediaGrant: String,
    ): RemuxStartDto

    suspend fun stopRemux(leaseId: String, remuxId: String, mediaGrant: String)
    suspend fun endLease(leaseId: String)
}

/**
 * Production adapter. Lease/control traffic uses [HubClient.call], while remux
 * traffic uses its own capability URL and deliberately carries no bearer.
 */
class HubClientPlaybackApi(
    private val client: HubClient,
    private val transport: HttpTransport,
    private val json: Json = DEFAULT_JSON,
) : HubPlaybackApi {
    override val baseUrl: String get() = client.baseUrl

    override suspend fun createLease(request: PlaybackCreateRequest): PlaybackLeaseDto =
        client.call { createLease(it, request) }

    override suspend fun heartbeat(
        leaseId: String,
        heartbeat: SessionHeartbeatDto,
    ): HeartbeatResponseDto = client.call { heartbeat(it, leaseId, heartbeat) }

    override suspend fun webSocketAccess(leaseId: String): WebSocketAccessDto =
        client.call { webSocketAccess(it, leaseId) }

    override suspend fun sync(leaseId: String, state: SyncStateDto) {
        client.call { sync(it, leaseId, state) }
    }

    override suspend fun watchIntent(leaseId: String): WatchIntentResponse =
        client.call { intent(it, leaseId) }

    override suspend fun watchAlone(leaseId: String) {
        client.call { watchAlone(it, leaseId) }
    }

    override suspend fun requestJoin(leaseId: String, peerId: String) {
        client.call {
            roomAction(it, leaseId, "join-request", JoinRequestBody.serializer(), JoinRequestBody(peerId))
        }
    }

    override suspend fun answerJoin(leaseId: String, requestId: String, accept: Boolean) {
        client.call {
            roomAction(
                it,
                leaseId,
                "join-answer",
                JoinAnswerBody.serializer(),
                JoinAnswerBody(requestId, accept),
            )
        }
    }

    override suspend fun requestControl(leaseId: String) {
        client.call {
            roomAction(
                it,
                leaseId,
                "request-control",
                RequestControlBody.serializer(),
                RequestControlBody(),
            )
        }
    }

    override suspend fun grantControl(leaseId: String, peerId: String, grant: Boolean) {
        client.call {
            roomAction(
                it,
                leaseId,
                "grant-control",
                GrantControlBody.serializer(),
                GrantControlBody(peerId, grant),
            )
        }
    }

    override suspend fun setControl(leaseId: String, targetId: String, grant: Boolean) {
        client.call {
            roomAction(
                it,
                leaseId,
                "set-control",
                SetControlBody.serializer(),
                SetControlBody(targetId, grant),
            )
        }
    }

    override suspend fun kick(leaseId: String, targetId: String) {
        client.call {
            roomAction(it, leaseId, "kick", KickBody.serializer(), KickBody(targetId))
        }
    }

    override suspend fun setRoomAudio(leaseId: String, audioTrackIndex: Int) {
        client.call {
            roomAction(
                it,
                leaseId,
                "room-audio",
                RoomAudioBody.serializer(),
                RoomAudioBody(audioTrackIndex),
            )
        }
    }

    override suspend fun ready(leaseId: String, generation: Long) {
        client.call {
            roomAction(
                it,
                leaseId,
                "ready",
                ReadyBody.serializer(),
                ReadyBody(generation),
            )
        }
    }

    override suspend fun leaveRoom(leaseId: String) {
        client.call { roomAction(it, leaseId, "leave") }
    }

    override suspend fun refreshMediaGrant(leaseId: String): HubMediaGrant {
        val issued: MediaGrantDto = client.call { mediaGrant(it, leaseId) }
        return HubMediaGrant(
            token = issued.token,
            expiresAtMs = issued.expiresAtMs,
        )
    }

    override suspend fun startRemux(
        startUrl: String,
        audioTrackIndex: Int,
        timeshift: Boolean,
        mediaGrant: String,
    ): RemuxStartDto {
        val url = resolve(startUrl).newBuilder()
            .setQueryParameter("audio", audioTrackIndex.coerceAtLeast(0).toString())
            .setQueryParameter("timeshift", if (timeshift) "1" else "0")
            .setQueryParameter("g", mediaGrant)
            .build()
            .toString()
        val response = execute(HttpRequestSpec("POST", url, ACCEPT_JSON, body = ""))
        return json.decodeFromString(RemuxStartDto.serializer(), response.bodyText)
    }

    override suspend fun stopRemux(leaseId: String, remuxId: String, mediaGrant: String) {
        val url = HubEndpoints.api(
            baseUrl,
            "/remux/${Urls.encodePathSegment(remuxId)}" +
                "?sid=${Urls.percentEncode(leaseId)}&g=${Urls.percentEncode(mediaGrant)}",
        )
        execute(HttpRequestSpec("DELETE", url, ACCEPT_JSON))
    }

    override suspend fun endLease(leaseId: String) {
        client.call { endLease(it, leaseId) }
    }

    private fun resolve(url: String) = if (url.startsWith("http://") || url.startsWith("https://")) {
        url.toHttpUrl()
    } else {
        requireNotNull(HubEndpoints.normalizeBaseUrl(baseUrl).toHttpUrl().resolve(url)) {
            "Invalid hub media URL"
        }
    }

    private suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        val response = try {
            transport.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw HubUnreachableException(error.message ?: "Hub unreachable", error)
        }
        if (!response.isSuccess) throw hubFailure(response)
        return response
    }

    private companion object {
        val ACCEPT_JSON = mapOf("Accept" to "application/json")
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
