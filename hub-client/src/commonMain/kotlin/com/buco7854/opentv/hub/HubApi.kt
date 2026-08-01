package com.buco7854.opentv.hub

import com.buco7854.opentv.contract.AuthCapabilitiesDto
import com.buco7854.opentv.contract.AuthFlowDto
import com.buco7854.opentv.contract.AccountInfoDto
import com.buco7854.opentv.contract.ChannelDto
import com.buco7854.opentv.contract.ChannelPageDto
import com.buco7854.opentv.contract.CurrentUserDto
import com.buco7854.opentv.contract.DeviceLinkPollRequestDto
import com.buco7854.opentv.contract.DeviceLinkStartDto
import com.buco7854.opentv.contract.DeviceLinkStartRequestDto
import com.buco7854.opentv.contract.DeviceLinkStatusDto
import com.buco7854.opentv.contract.DownloadDto
import com.buco7854.opentv.contract.EnqueueDownloadRequest
import com.buco7854.opentv.contract.EpisodePageDto
import com.buco7854.opentv.contract.FavoriteDto
import com.buco7854.opentv.contract.FavoriteRequest
import com.buco7854.opentv.contract.FavoritesResolvedDto
import com.buco7854.opentv.contract.GroupCountDto
import com.buco7854.opentv.contract.GuideEntryDto
import com.buco7854.opentv.contract.LogoutRequestDto
import com.buco7854.opentv.contract.MediaGrantDto
import com.buco7854.opentv.contract.PasswordLoginRequestDto
import com.buco7854.opentv.contract.PlaybackCreateRequest
import com.buco7854.opentv.contract.PlaybackLeaseDto
import com.buco7854.opentv.contract.PlaylistCapabilitiesDto
import com.buco7854.opentv.contract.PlaylistDeleteInfoDto
import com.buco7854.opentv.contract.PlaylistDetailDto
import com.buco7854.opentv.contract.PlaylistDto
import com.buco7854.opentv.contract.PlaylistEditDto
import com.buco7854.opentv.contract.GroupKindRequest
import com.buco7854.opentv.contract.PlaylistRefreshJobDto
import com.buco7854.opentv.contract.PlaylistUpdateRequest
import com.buco7854.opentv.contract.ProgrammeDto
import com.buco7854.opentv.contract.RecoveryCompleteRequestDto
import com.buco7854.opentv.contract.ResumePointDto
import com.buco7854.opentv.contract.SearchResultsDto
import com.buco7854.opentv.contract.SeriesGroupPageDto
import com.buco7854.opentv.contract.ServerInfoDto
import com.buco7854.opentv.contract.SessionHeartbeatDto
import com.buco7854.opentv.contract.HeartbeatResponseDto
import com.buco7854.opentv.contract.SyncStateDto
import com.buco7854.opentv.contract.TotpCompleteRequestDto
import com.buco7854.opentv.contract.TotpEnrollmentDto
import com.buco7854.opentv.contract.TotpEnrollmentStartRequestDto
import com.buco7854.opentv.contract.WatchIntentResponse
import com.buco7854.opentv.contract.WebSocketAccessDto
import com.buco7854.opentv.contract.XtreamSeriesDetailDto
import com.buco7854.opentv.contract.XtreamSeriesPageDto
import com.buco7854.opentv.core.net.HttpRequestSpec
import com.buco7854.opentv.core.net.HttpResponseSpec
import com.buco7854.opentv.core.net.HttpTransport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** Where to reach a hub and, once signed in, how to prove it. */
data class HubCredentials(val baseUrl: String, val token: String? = null)

/**
 * The hub's `/api/v1` surface. Stateless: every call takes the [HubCredentials]
 * to use, so a caller may hold several hubs at once and swap a token after
 * re-authentication without rebuilding anything.
 *
 * Failures are typed by [hubFailure]; a 409 is not a failure here — auth flows
 * answer with it and an `AuthFlowDto` body, which [HubAuthFlowRequired] carries
 * back to the sign-in state machine.
 */
class HubApi(
    private val transport: HttpTransport,
    private val json: Json = DEFAULT_JSON,
) {

    // ---- Discovery & auth -------------------------------------------------

    suspend fun serverInfo(base: String): ServerInfoDto =
        get(HubCredentials(base), HubEndpoints.serverInfo(base), ServerInfoDto.serializer())

    suspend fun authCapabilities(base: String): AuthCapabilitiesDto =
        get(HubCredentials(base), HubEndpoints.authCapabilities(base), AuthCapabilitiesDto.serializer())

    suspend fun password(base: String, username: String, password: String): AuthFlowDto =
        authFlow(base, HubEndpoints.password(base), PasswordLoginRequestDto.serializer(), PasswordLoginRequestDto(username, password))

    suspend fun totp(base: String, challenge: String, code: String): AuthFlowDto =
        authFlow(base, HubEndpoints.totp(base), TotpCompleteRequestDto.serializer(), TotpCompleteRequestDto(challenge, code))

    suspend fun recovery(base: String, challenge: String, code: String): AuthFlowDto =
        authFlow(base, HubEndpoints.recovery(base), RecoveryCompleteRequestDto.serializer(), RecoveryCompleteRequestDto(challenge, code))

    suspend fun totpEnrollStart(base: String, challenge: String): TotpEnrollmentDto =
        post(HubCredentials(base), HubEndpoints.totpEnrollStart(base), TotpEnrollmentStartRequestDto.serializer(), TotpEnrollmentStartRequestDto(challenge), TotpEnrollmentDto.serializer())

    suspend fun totpEnrollComplete(base: String, challenge: String, code: String): AuthFlowDto =
        authFlow(base, HubEndpoints.totpEnrollComplete(base), TotpCompleteRequestDto.serializer(), TotpCompleteRequestDto(challenge, code))

    suspend fun linkStart(
        base: String,
        deviceName: String?,
        browserSignIn: Boolean = false,
    ): DeviceLinkStartDto =
        post(
            HubCredentials(base),
            HubEndpoints.linkStart(base),
            DeviceLinkStartRequestDto.serializer(),
            DeviceLinkStartRequestDto(deviceName, browserSignIn),
            DeviceLinkStartDto.serializer(),
        )

    suspend fun linkPoll(base: String, pollToken: String): DeviceLinkStatusDto =
        post(HubCredentials(base), HubEndpoints.linkPoll(base), DeviceLinkPollRequestDto.serializer(), DeviceLinkPollRequestDto(pollToken), DeviceLinkStatusDto.serializer())

    suspend fun linkCancel(base: String, pollToken: String) {
        postNoContent(
            HubCredentials(base),
            HubEndpoints.linkCancel(base),
            DeviceLinkPollRequestDto.serializer(),
            DeviceLinkPollRequestDto(pollToken),
        )
    }

    suspend fun me(credentials: HubCredentials): CurrentUserDto =
        get(credentials, HubEndpoints.me(credentials.baseUrl), CurrentUserDto.serializer())

    suspend fun logout(credentials: HubCredentials) {
        postNoContent(credentials, HubEndpoints.logout(credentials.baseUrl), LogoutRequestDto.serializer(), LogoutRequestDto())
    }

    // ---- Catalog ----------------------------------------------------------

    suspend fun playlists(c: HubCredentials): List<PlaylistDto> =
        getList(c, HubEndpoints.playlists(c.baseUrl), PlaylistDto.serializer())

    suspend fun playlist(c: HubCredentials, playlistId: Long): PlaylistDetailDto =
        get(
            c,
            HubEndpoints.playlist(c.baseUrl, playlistId),
            PlaylistDetailDto.serializer(),
        )

    suspend fun playlistCapabilities(
        c: HubCredentials,
        playlistId: Long,
    ): HubPlaylistCapabilities =
        get(
            c,
            HubEndpoints.playlistCapabilities(c.baseUrl, playlistId),
            PlaylistCapabilitiesDto.serializer(),
        ).forHub(c.baseUrl)

    suspend fun playlistEdit(c: HubCredentials, playlistId: Long): PlaylistEditDto =
        get(c, HubEndpoints.playlistEdit(c.baseUrl, playlistId), PlaylistEditDto.serializer())

    suspend fun updatePlaylist(
        c: HubCredentials,
        playlistId: Long,
        request: PlaylistUpdateRequest,
    ): PlaylistDto =
        put(
            c,
            HubEndpoints.playlist(c.baseUrl, playlistId),
            PlaylistUpdateRequest.serializer(),
            request,
            PlaylistDto.serializer(),
        )

    suspend fun startPlaylistRefresh(
        c: HubCredentials,
        playlistId: Long,
        force: Boolean,
    ): PlaylistRefreshJobDto =
        postEmpty(
            c,
            HubEndpoints.playlistRefreshJobs(c.baseUrl, playlistId, force),
            PlaylistRefreshJobDto.serializer(),
        )

    suspend fun playlistRefreshStatus(
        c: HubCredentials,
        playlistId: Long,
        refreshId: String,
    ): PlaylistRefreshJobDto =
        get(
            c,
            HubEndpoints.playlistRefreshJob(c.baseUrl, playlistId, refreshId),
            PlaylistRefreshJobDto.serializer(),
        )

    suspend fun playlistDeleteInfo(
        c: HubCredentials,
        playlistId: Long,
    ): PlaylistDeleteInfoDto =
        get(
            c,
            HubEndpoints.playlistDeleteInfo(c.baseUrl, playlistId),
            PlaylistDeleteInfoDto.serializer(),
        )

    suspend fun deletePlaylist(c: HubCredentials, playlistId: Long) {
        send(c, "DELETE", HubEndpoints.playlist(c.baseUrl, playlistId), null)
    }

    suspend fun playlistAccount(
        c: HubCredentials,
        playlistId: Long,
        force: Boolean,
    ): AccountInfoDto =
        get(
            c,
            HubEndpoints.playlistAccount(c.baseUrl, playlistId, force),
            AccountInfoDto.serializer(),
        )

    suspend fun clearPlaylistProgress(c: HubCredentials, playlistId: Long) {
        send(c, "POST", HubEndpoints.playlistClearProgress(c.baseUrl, playlistId), null)
    }

    suspend fun setPlaylistGroupKind(
        c: HubCredentials,
        playlistId: Long,
        groupTitle: String,
        kind: Int?,
    ) {
        send(
            c,
            "PUT",
            HubEndpoints.playlistGroupKind(c.baseUrl, playlistId),
            body(GroupKindRequest.serializer(), GroupKindRequest(groupTitle, kind)),
        )
    }

    suspend fun groups(c: HubCredentials, playlistId: Long, kind: Int): List<GroupCountDto> =
        getList(c, HubEndpoints.groups(c.baseUrl, playlistId, kind), GroupCountDto.serializer())

    suspend fun channels(
        c: HubCredentials,
        playlistId: Long,
        kind: Int,
        group: String,
        offset: Int,
        limit: Int,
        filter: String = "",
    ): ChannelPageDto =
        get(c, HubEndpoints.channels(c.baseUrl, playlistId, kind, group, offset, limit, filter), ChannelPageDto.serializer())

    suspend fun seriesGroups(
        c: HubCredentials,
        playlistId: Long,
        group: String,
        offset: Int,
        limit: Int,
        filter: String = "",
    ): SeriesGroupPageDto =
        get(c, HubEndpoints.seriesGroups(c.baseUrl, playlistId, group, offset, limit, filter), SeriesGroupPageDto.serializer())

    suspend fun xtreamSeries(
        c: HubCredentials,
        playlistId: Long,
        category: String,
        offset: Int,
        limit: Int,
        filter: String = "",
    ): XtreamSeriesPageDto =
        get(c, HubEndpoints.xtreamSeries(c.baseUrl, playlistId, category, offset, limit, filter), XtreamSeriesPageDto.serializer())

    suspend fun episodes(
        c: HubCredentials,
        playlistId: Long,
        seriesKey: String,
        season: Int?,
        offset: Int,
        limit: Int,
    ): EpisodePageDto =
        get(c, HubEndpoints.episodes(c.baseUrl, playlistId, seriesKey, season, offset, limit), EpisodePageDto.serializer())

    suspend fun xtreamSeriesDetail(c: HubCredentials, playlistId: Long, seriesId: String): XtreamSeriesDetailDto =
        get(c, HubEndpoints.xtreamSeriesDetail(c.baseUrl, playlistId, seriesId), XtreamSeriesDetailDto.serializer())

    suspend fun search(c: HubCredentials, playlistId: Long, query: String): SearchResultsDto =
        get(c, HubEndpoints.search(c.baseUrl, playlistId, query), SearchResultsDto.serializer())

    suspend fun nowAiring(c: HubCredentials, playlistId: Long): List<ProgrammeDto> =
        getMap(
            c,
            HubEndpoints.nowAiring(c.baseUrl, playlistId),
            String.serializer(),
            ProgrammeDto.serializer(),
        ).values.toList()

    suspend fun guideIds(c: HubCredentials, playlistId: Long): List<String> =
        getList(c, HubEndpoints.guideIds(c.baseUrl, playlistId), String.serializer())

    suspend fun content(c: HubCredentials, contentId: String): ChannelDto =
        get(c, HubEndpoints.content(c.baseUrl, contentId), ChannelDto.serializer())

    suspend fun contentGuide(c: HubCredentials, contentId: String): List<GuideEntryDto> =
        getList(c, HubEndpoints.contentGuide(c.baseUrl, contentId), GuideEntryDto.serializer())

    // ---- User-owned state (contentId-keyed, server-side) -------------------

    suspend fun favorites(c: HubCredentials, playlistId: Long): List<FavoriteDto> =
        getList(c, HubEndpoints.favorites(c.baseUrl, playlistId), FavoriteDto.serializer())

    suspend fun favoritesResolved(c: HubCredentials, playlistId: Long): FavoritesResolvedDto =
        get(c, HubEndpoints.favoritesResolved(c.baseUrl, playlistId), FavoritesResolvedDto.serializer())

    suspend fun addFavorite(c: HubCredentials, playlistId: Long, contentId: String) {
        send(c, "PUT", HubEndpoints.favorites(c.baseUrl, playlistId), body(FavoriteRequest.serializer(), FavoriteRequest(contentId)))
    }

    suspend fun removeFavorite(c: HubCredentials, playlistId: Long, contentId: String) {
        send(c, "DELETE", HubEndpoints.favoriteDelete(c.baseUrl, playlistId, contentId), null)
    }

    suspend fun resume(c: HubCredentials): List<ResumePointDto> =
        getList(c, HubEndpoints.resume(c.baseUrl), ResumePointDto.serializer())

    suspend fun saveResume(c: HubCredentials, point: ResumePointDto) {
        send(c, "PUT", HubEndpoints.resume(c.baseUrl), body(ResumePointDto.serializer(), point))
    }

    suspend fun deleteResume(c: HubCredentials, contentId: String) {
        send(c, "DELETE", HubEndpoints.resumeDelete(c.baseUrl, contentId), null)
    }

    // ---- Playback leases & watch together ---------------------------------

    suspend fun createLease(c: HubCredentials, request: PlaybackCreateRequest): PlaybackLeaseDto =
        post(c, HubEndpoints.playback(c.baseUrl), PlaybackCreateRequest.serializer(), request, PlaybackLeaseDto.serializer())

    suspend fun heartbeat(c: HubCredentials, leaseId: String, beat: SessionHeartbeatDto): HeartbeatResponseDto =
        post(c, HubEndpoints.playbackAction(c.baseUrl, leaseId, "heartbeat"), SessionHeartbeatDto.serializer(), beat, HeartbeatResponseDto.serializer())

    suspend fun webSocketAccess(c: HubCredentials, leaseId: String): WebSocketAccessDto =
        postEmpty(c, HubEndpoints.playbackAction(c.baseUrl, leaseId, "ws-token"), WebSocketAccessDto.serializer())

    suspend fun mediaGrant(c: HubCredentials, leaseId: String): MediaGrantDto =
        postEmpty(c, HubEndpoints.playbackAction(c.baseUrl, leaseId, "media-grant"), MediaGrantDto.serializer())

    suspend fun intent(c: HubCredentials, leaseId: String): WatchIntentResponse =
        postEmpty(c, HubEndpoints.playbackAction(c.baseUrl, leaseId, "intent"), WatchIntentResponse.serializer())

    suspend fun watchAlone(c: HubCredentials, leaseId: String) {
        send(c, "POST", HubEndpoints.playbackAction(c.baseUrl, leaseId, "watch-alone"), null)
    }

    suspend fun sync(c: HubCredentials, leaseId: String, state: SyncStateDto) {
        send(c, "POST", HubEndpoints.playbackAction(c.baseUrl, leaseId, "sync"), body(SyncStateDto.serializer(), state))
    }

    /** Room actions with a body ([JoinRequestBody], [KickBody], ...) and no response. */
    suspend fun <T> roomAction(c: HubCredentials, leaseId: String, action: String, serializer: KSerializer<T>, value: T) {
        send(c, "POST", HubEndpoints.playbackAction(c.baseUrl, leaseId, action), body(serializer, value))
    }

    /** Room actions with neither body nor response (`leave`). */
    suspend fun roomAction(c: HubCredentials, leaseId: String, action: String) {
        send(c, "POST", HubEndpoints.playbackAction(c.baseUrl, leaseId, action), null)
    }

    suspend fun endLease(c: HubCredentials, leaseId: String) {
        send(c, "DELETE", HubEndpoints.playbackDelete(c.baseUrl, leaseId), null)
    }

    // ---- Downloads (hub-side blob; the device pulls the finished file) -----

    suspend fun downloads(c: HubCredentials): List<DownloadDto> =
        getList(c, HubEndpoints.downloads(c.baseUrl), DownloadDto.serializer())

    suspend fun enqueueDownload(c: HubCredentials, contentId: String) {
        send(c, "POST", HubEndpoints.downloads(c.baseUrl), body(EnqueueDownloadRequest.serializer(), EnqueueDownloadRequest(contentId)))
    }

    suspend fun downloadAction(c: HubCredentials, downloadId: String, action: String) {
        send(c, "POST", HubEndpoints.downloadAction(c.baseUrl, downloadId, action), null)
    }

    suspend fun deleteDownload(c: HubCredentials, downloadId: String) {
        send(c, "DELETE", HubEndpoints.downloadDelete(c.baseUrl, downloadId), null)
    }

    // ---- Plumbing ---------------------------------------------------------

    private suspend fun <R> get(c: HubCredentials, url: String, serializer: KSerializer<R>): R =
        json.decodeFromString(serializer, send(c, "GET", url, null).bodyText)

    private suspend fun <R> getList(c: HubCredentials, url: String, serializer: KSerializer<R>): List<R> =
        json.decodeFromString(ListSerializer(serializer), send(c, "GET", url, null).bodyText)

    private suspend fun <K, V> getMap(
        c: HubCredentials,
        url: String,
        keySerializer: KSerializer<K>,
        valueSerializer: KSerializer<V>,
    ): Map<K, V> =
        json.decodeFromString(
            MapSerializer(keySerializer, valueSerializer),
            send(c, "GET", url, null).bodyText,
        )

    private suspend fun <T, R> post(
        c: HubCredentials,
        url: String,
        requestSerializer: KSerializer<T>,
        value: T,
        responseSerializer: KSerializer<R>,
    ): R = json.decodeFromString(responseSerializer, send(c, "POST", url, body(requestSerializer, value)).bodyText)

    private suspend fun <T, R> put(
        c: HubCredentials,
        url: String,
        requestSerializer: KSerializer<T>,
        value: T,
        responseSerializer: KSerializer<R>,
    ): R = json.decodeFromString(
        responseSerializer,
        send(c, "PUT", url, body(requestSerializer, value)).bodyText,
    )

    private suspend fun <R> postEmpty(c: HubCredentials, url: String, serializer: KSerializer<R>): R =
        json.decodeFromString(serializer, send(c, "POST", url, null).bodyText)

    private suspend fun <T> postNoContent(c: HubCredentials, url: String, serializer: KSerializer<T>, value: T) {
        send(c, "POST", url, body(serializer, value))
    }

    /**
     * Auth flows answer 200 with a token or 409 with the next challenge; both
     * carry an [AuthFlowDto], so 409 is decoded rather than thrown as a failure.
     */
    private suspend fun <T> authFlow(
        base: String,
        url: String,
        serializer: KSerializer<T>,
        value: T,
    ): AuthFlowDto {
        val response = send(HubCredentials(base), "POST", url, body(serializer, value), allowConflict = true)
        return json.decodeFromString(AuthFlowDto.serializer(), response.bodyText)
    }

    private fun <T> body(serializer: KSerializer<T>, value: T) = json.encodeToString(serializer, value)

    private suspend fun send(
        c: HubCredentials,
        method: String,
        url: String,
        body: String?,
        allowConflict: Boolean = false,
    ): HttpResponseSpec {
        val headers = buildMap {
            put("Accept", "application/json")
            put(CLIENT_HEADER, CLIENT_NATIVE)
            c.token?.let { put("Authorization", "Bearer $it") }
        }
        val response = try {
            transport.execute(HttpRequestSpec(method, url, headers, body, "application/json"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw HubUnreachableException(error.message ?: "Hub unreachable", error)
        }

        if (response.isSuccess) return response
        if (allowConflict && response.status == 409) return response
        throw hubFailure(response)
    }

    private companion object {
        const val CLIENT_HEADER = "X-OpenTV-Client"
        const val CLIENT_NATIVE = "native"
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
