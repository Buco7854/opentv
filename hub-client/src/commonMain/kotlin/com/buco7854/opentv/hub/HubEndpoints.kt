package com.buco7854.opentv.hub

import com.buco7854.opentv.core.net.Urls

/**
 * Pure URL construction for a hub's `/api/v1` surface — no I/O, no state
 * (mirror of the `object Xtream` / `XtreamApi` split). Endpoints that take a
 * playback lease id or contentId encode them as path segments; opaque
 * capability URLs (stream/relay/remux) come from the server inside
 * `PlaybackLeaseDto` and are never built here.
 */
object HubEndpoints {

    /** Trims trailing slashes and a trailing `/api/v1` so users can paste either form. */
    fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.endsWith("/api/v1")) base = base.removeSuffix("/api/v1")
        return base
    }

    fun api(base: String, path: String): String = "${normalizeBaseUrl(base)}/api/v1$path"

    // Public
    fun serverInfo(base: String) = api(base, "/server-info")
    fun authCapabilities(base: String) = api(base, "/auth/capabilities")

    // Auth
    fun password(base: String) = api(base, "/auth/password")
    fun totp(base: String) = api(base, "/auth/totp")
    fun totpEnrollStart(base: String) = api(base, "/auth/totp/enroll/start")
    fun totpEnrollComplete(base: String) = api(base, "/auth/totp/enroll/complete")
    fun recovery(base: String) = api(base, "/auth/recovery")
    fun linkStart(base: String) = api(base, "/auth/link/start")
    fun linkPoll(base: String) = api(base, "/auth/link/poll")
    fun linkCancel(base: String) = api(base, "/auth/link/cancel")
    fun me(base: String) = api(base, "/auth/me")
    fun logout(base: String) = api(base, "/auth/logout")

    // Catalog (server-paged; query built via [listing])
    fun playlists(base: String) = api(base, "/playlists")
    fun playlistCapabilities(base: String, playlistId: Long) =
        api(base, "/playlists/$playlistId/capabilities")

    fun playlistEdit(base: String, playlistId: Long) =
        api(base, "/playlists/$playlistId/edit")

    fun playlist(base: String, playlistId: Long) =
        api(base, "/playlists/$playlistId")

    fun playlistRefreshJobs(base: String, playlistId: Long, force: Boolean) =
        api(base, "/playlists/$playlistId/refresh-jobs?force=$force")

    fun playlistRefreshJob(base: String, playlistId: Long, refreshId: String) =
        api(
            base,
            "/playlists/$playlistId/refresh-jobs/${Urls.encodePathSegment(refreshId)}",
        )

    fun playlistDeleteInfo(base: String, playlistId: Long) =
        api(base, "/playlists/$playlistId/delete-info")

    fun playlistAccount(base: String, playlistId: Long, force: Boolean) =
        api(base, "/playlists/$playlistId/account?force=$force")

    fun playlistClearProgress(base: String, playlistId: Long) =
        api(base, "/playlists/$playlistId/clear-progress")

    fun playlistGroupKind(base: String, playlistId: Long) =
        api(base, "/playlists/$playlistId/group-kind")

    fun groups(base: String, playlistId: Long, kind: Int) =
        api(base, "/playlists/$playlistId/groups?kind=$kind")

    fun channels(base: String, playlistId: Long, kind: Int, group: String, offset: Int, limit: Int, filter: String) =
        api(base, "/playlists/$playlistId/channels?kind=$kind&group=${Urls.percentEncode(group)}" + listing(offset, limit, filter))

    fun seriesGroups(base: String, playlistId: Long, group: String, offset: Int, limit: Int, filter: String) =
        api(base, "/playlists/$playlistId/series-groups?group=${Urls.percentEncode(group)}" + listing(offset, limit, filter))

    fun xtreamSeries(base: String, playlistId: Long, category: String, offset: Int, limit: Int, filter: String) =
        api(base, "/playlists/$playlistId/xtream-series?category=${Urls.percentEncode(category)}" + listing(offset, limit, filter))

    fun episodes(base: String, playlistId: Long, seriesKey: String, season: Int?, offset: Int, limit: Int) =
        api(
            base,
            "/playlists/$playlistId/series/${Urls.encodePathSegment(seriesKey)}/episodes?" +
                (season?.let { "season=$it&" }.orEmpty()) + "offset=$offset&limit=$limit",
        )

    fun xtreamSeriesDetail(base: String, playlistId: Long, seriesId: String) =
        api(base, "/playlists/$playlistId/xseries/${Urls.encodePathSegment(seriesId)}")

    fun search(base: String, playlistId: Long, query: String) =
        api(base, "/playlists/$playlistId/search?q=${Urls.percentEncode(query)}")

    fun nowAiring(base: String, playlistId: Long) = api(base, "/playlists/$playlistId/now-airing")
    fun guideIds(base: String, playlistId: Long) = api(base, "/playlists/$playlistId/guide-ids")

    /** Image capabilities authorize themselves; callers must not add the session bearer. */
    fun image(base: String, token: String) =
        api(base, "/img?u=${Urls.percentEncode(token)}")

    // Favorites / resume (contentId-keyed, server-owned)
    fun favorites(base: String, playlistId: Long) = api(base, "/playlists/$playlistId/favorites")
    fun favoritesResolved(base: String, playlistId: Long) = api(base, "/playlists/$playlistId/favorites/resolved")
    fun favoriteDelete(base: String, playlistId: Long, contentId: String) =
        api(base, "/playlists/$playlistId/favorites?contentId=${Urls.percentEncode(contentId)}")

    fun resume(base: String) = api(base, "/resume")
    fun resumeDelete(base: String, contentId: String) =
        api(base, "/resume?contentId=${Urls.percentEncode(contentId)}")

    // Content (stable identity; never the numeric channel id)
    fun content(base: String, contentId: String) = api(base, "/content/${Urls.encodePathSegment(contentId)}")
    fun contentGuide(base: String, contentId: String) = "${content(base, contentId)}/guide"
    fun contentVodInfo(base: String, contentId: String) = "${content(base, contentId)}/vod-info"

    fun meta(base: String, type: String, title: String) =
        api(base, "/meta?type=$type&title=${Urls.percentEncode(title)}")

    fun metaEpisode(base: String, series: String, season: Int, episode: Int) =
        api(base, "/meta/episode?series=${Urls.percentEncode(series)}&season=$season&episode=$episode")

    // Playback leases + watch together (control actions are HTTP POSTs; events ride the WS)
    fun playback(base: String) = api(base, "/playback")
    fun playbackAction(base: String, leaseId: String, action: String) =
        api(base, "/playback/${Urls.encodePathSegment(leaseId)}/$action")

    fun playbackDelete(base: String, leaseId: String) =
        api(base, "/playback/${Urls.encodePathSegment(leaseId)}")

    /** ws(s):// form of the per-lease socket; [wsToken] comes from the ws-token action. */
    fun playbackSocket(base: String, leaseId: String, wsToken: String): String {
        val http = api(base, "/playback/${Urls.encodePathSegment(leaseId)}/ws?ws_token=${Urls.percentEncode(wsToken)}")
        return when {
            http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
            http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
            else -> http
        }
    }

    // Downloads (hub-side blobs the device later pulls)
    fun downloads(base: String) = api(base, "/downloads")
    fun downloadAction(base: String, downloadId: String, action: String) =
        api(base, "/downloads/${Urls.encodePathSegment(downloadId)}/$action")
    fun downloadDelete(base: String, downloadId: String) =
        api(base, "/downloads/${Urls.encodePathSegment(downloadId)}")
    fun downloadFile(base: String, downloadId: String, fileToken: String) =
        api(
            base,
            "/downloads/${Urls.encodePathSegment(downloadId)}/file" +
                "?token=${Urls.percentEncode(fileToken)}",
        )

    // Browser handoff targets (opened in a Custom Tab / shown as QR, never fetched)
    fun webRoot(base: String) = normalizeBaseUrl(base) + "/"
    fun webSecurity(base: String) = normalizeBaseUrl(base) + "/security"
    fun webAdmin(base: String) = normalizeBaseUrl(base) + "/admin"
    fun webSessions(base: String) = normalizeBaseUrl(base) + "/sessions"

    /** Resolves a server-owned root-relative web path within this hub's deployment base. */
    fun webPath(base: String, path: String): String {
        require(path.startsWith("/") && !path.startsWith("//")) {
            "Hub browser path must be root-relative"
        }
        val target = normalizeBaseUrl(base) + path
        require(isSameOrigin(base, target)) {
            "Hub browser path must stay on the connected hub"
        }
        return target
    }

    /**
     * True when [target] belongs to the same origin as [base].
     *
     * The hub hands back URLs meant to be opened in the user's browser (the
     * device-link verification page). Those must be confined to the hub the
     * user actually added: a hub that answered with an off-origin link would
     * otherwise steer the browser somewhere the user never chose.
     */
    fun isSameOrigin(base: String, target: String): Boolean {
        val expected = Urls.parse(normalizeBaseUrl(base)) ?: return false
        val actual = Urls.parse(target) ?: return false
        return expected.scheme.equals(actual.scheme, ignoreCase = true) &&
            expected.host.equals(actual.host, ignoreCase = true) &&
            expected.port == actual.port
    }

    private fun listing(offset: Int, limit: Int, filter: String): String =
        "&offset=$offset&limit=$limit" +
            (filter.takeIf { it.isNotBlank() }?.let { "&filter=${Urls.percentEncode(it)}" }.orEmpty())
}
