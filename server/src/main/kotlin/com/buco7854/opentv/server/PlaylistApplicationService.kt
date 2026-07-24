package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Favorite
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.repo.xtreamSeriesKey
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.util.nowMs
import kotlinx.coroutines.flow.first

/** Application use cases for playlist-scoped browsing and mutations. */
class PlaylistApplicationService(
    private val storage: Storage,
    private val playlists: PlaylistRepository,
    private val epg: EpgRepository,
    private val xtream: XtreamRepository,
    private val account: AccountRepository,
    private val cipher: StreamCipher,
) {
    suspend fun list(): List<PlaylistDto> = storage.playlists.getAll().map(Playlist::toApiDto)

    suspend fun create(request: PlaylistUpsertRequest): PlaylistDto {
        val id = when (request.mode) {
            "xtream" -> playlists.addFromXtream(
                request.name,
                request.server,
                request.username,
                request.password,
            )
            "url" -> playlists.addFromUrl(request.name, request.url, request.epgUrl)
            "file" -> playlists.importFromLines(request.name, request.content.lineSequence())
            else -> throw IllegalArgumentException("Unknown mode")
        }
        runCatching { epg.refresh(id) }
        return playlist(id).toApiDto()
    }

    suspend fun update(id: Long, request: PlaylistUpsertRequest): PlaylistDto {
        val existing = playlist(id)
        val resolved = request.preservingSecretsFrom(existing)
        when (resolved.mode) {
            "xtream" -> playlists.updateXtream(
                id,
                resolved.name,
                resolved.server,
                resolved.username,
                resolved.password,
            )
            "url" -> playlists.updateUrl(
                id,
                resolved.name,
                resolved.url,
                resolved.epgUrl,
            )
            "file" -> if (resolved.content.isNotBlank()) {
                playlists.replaceFromLines(id, resolved.name, resolved.content.lineSequence())
            } else {
                playlists.rename(id, resolved.name)
            }
            else -> throw IllegalArgumentException("Unknown mode")
        }
        return playlist(id).toApiDto()
    }

    suspend fun delete(id: Long) = playlists.delete(id)

    suspend fun refresh(id: Long, force: Boolean): PlaylistDto {
        playlists.refresh(id, force)
        runCatching { epg.refresh(id, force) }
        return playlist(id).toApiDto()
    }

    suspend fun clearProgress(id: Long) = storage.resume.deleteForPlaylist(id)

    suspend fun detail(id: Long): PlaylistDetailDto {
        val playlist = playlist(id)
        val nativeXtream = playlist.isXtreamNative
        val seriesCount = if (nativeXtream) {
            storage.xtreamSeries.observeCount(id).first()
        } else {
            storage.channels.observeCount(id, ChannelKind.SERIES).first()
        }
        return PlaylistDetailDto(
            playlist = playlist.toApiDto(),
            isXtreamNative = nativeXtream,
            liveCount = storage.channels.observeCount(id, ChannelKind.LIVE).first(),
            movieCount = storage.channels.observeCount(id, ChannelKind.MOVIE).first(),
            seriesCount = seriesCount,
        )
    }

    suspend fun groups(id: Long, kind: Int): List<GroupCountDto> {
        val playlist = playlist(id)
        val groups = if (kind == ChannelKind.SERIES && playlist.isXtreamNative) {
            storage.xtreamSeries.observeCategories(id).first()
        } else {
            storage.channels.observeGroups(id, kind).first()
        }
        return groups.map { it.toDto() }
    }

    suspend fun channels(id: Long, kind: Int, group: String): List<ChannelDto> =
        storage.channels.observeInGroup(id, kind, group).first().map { it.toDto(cipher) }

    suspend fun seriesGroups(id: Long, group: String?): List<SeriesGroupDto> {
        val groups = if (group != null) {
            storage.channels.observeSeriesInGroup(id, group).first()
        } else {
            storage.channels.observeAllSeries(id).first()
        }
        return groups.filterNot { it.seriesKey.startsWith("xs:") }.map { it.toDto(cipher) }
    }

    suspend fun xtreamSeries(id: Long, category: String?): List<XtreamSeriesDto> {
        val series = if (category != null) {
            storage.xtreamSeries.observeInCategory(id, category).first()
        } else {
            storage.xtreamSeries.observeAll(id).first()
        }
        return series.map { it.toDto(cipher) }
    }

    suspend fun nowAiring(id: Long): Map<String, ProgrammeDto> =
        epg.nowAiring(id).mapValues { it.value.toDto() }

    suspend fun guideIds(id: Long): Set<String> = epg.observeGuideIds(id).first()

    suspend fun search(id: Long, query: String): SearchResultsDto {
        if (query.trim().length < 2) return SearchResultsDto()
        val escaped = query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val rows = storage.channels.search(id, escaped)
        val m3uSeries = rows.filter { it.kind == ChannelKind.SERIES }
            .filterNot { it.seriesKey?.startsWith("xs:") == true }
            .groupBy { it.seriesKey ?: it.name }
            .map { (key, episodes) ->
                SeriesHitDto(
                    seriesKey = key,
                    count = episodes.size,
                    logo = cipher.encryptOrNull(episodes.firstOrNull { it.logo != null }?.logo),
                    groupTitle = episodes.first().groupTitle,
                )
            }
        val panelSeries = storage.xtreamSeries.search(id, escaped).map {
            SeriesHitDto(
                seriesKey = it.name,
                count = 0,
                logo = cipher.encryptOrNull(it.cover),
                groupTitle = it.categoryName,
                xtreamSeriesId = it.seriesId,
            )
        }
        return SearchResultsDto(
            live = rows.filter { it.kind == ChannelKind.LIVE }.map { it.toDto(cipher) },
            movies = rows.filter { it.kind == ChannelKind.MOVIE }.map { it.toDto(cipher) },
            series = panelSeries + m3uSeries,
        )
    }

    suspend fun account(id: Long, force: Boolean): AccountInfoDto =
        account.accountInfo(playlist(id), force)?.toDto()
            ?: throw ResourceNotFound("account", "No account API for this playlist")

    suspend fun setGroupKind(id: Long, request: GroupKindRequest) =
        playlists.setGroupOverride(id, request.groupTitle, request.kind)

    suspend fun favorites(id: Long): List<FavoriteDto> =
        storage.favorites.getAll(id).map { it.toDto(cipher) }

    suspend fun addFavorite(id: Long, request: FavoriteRequest) {
        storage.favorites.add(Favorite(id, cipher.resolve(request.key), request.kind, nowMs()))
    }

    suspend fun removeFavorite(id: Long, key: String) =
        storage.favorites.remove(id, cipher.resolve(key))

    suspend fun resolvedFavorites(id: Long): FavoritesResolvedDto {
        val favorites = storage.favorites.getAll(id)
        suspend fun channelsFor(kind: Int): List<ChannelDto> {
            val urls = favorites.filter { it.kind == kind }.map { it.key }
            if (urls.isEmpty()) return emptyList()
            return storage.channels.observeByUrls(id, kind, urls.take(900)).first()
                .map { it.toDto(cipher) }
        }

        val series = favorites.filter { it.kind == ChannelKind.SERIES }.mapNotNull { favorite ->
            if (favorite.key.startsWith("x:")) {
                val seriesId = favorite.key.removePrefix("x:").toLongOrNull() ?: return@mapNotNull null
                storage.xtreamSeries.get(id, seriesId)?.let {
                    SeriesHitDto(
                        it.name,
                        0,
                        cipher.encryptOrNull(it.cover),
                        it.categoryName,
                        it.seriesId,
                    )
                }
            } else {
                storage.channels.observeAllSeries(id).first()
                    .firstOrNull { it.seriesKey == favorite.key }
                    ?.let {
                        SeriesHitDto(
                            it.seriesKey,
                            it.count,
                            cipher.encryptOrNull(it.logo),
                            it.groupTitle,
                        )
                    }
                    ?: SeriesHitDto(favorite.key, 0, null, "Series")
            }
        }.sortedBy { it.seriesKey.lowercase() }
        return FavoritesResolvedDto(
            live = channelsFor(ChannelKind.LIVE),
            movies = channelsFor(ChannelKind.MOVIE),
            series = series,
        )
    }

    suspend fun episodes(id: Long, seriesKey: String): List<ChannelDto> =
        storage.channels.observeEpisodes(id, seriesKey).first().map { it.toDto(cipher) }

    suspend fun xtreamSeriesDetail(id: Long, seriesId: Long): XtreamSeriesDetailDto {
        val series = storage.xtreamSeries.get(id, seriesId) ?: throw ResourceNotFound("series")
        val failure = runCatching { xtream.ensureEpisodes(id, seriesId) }.exceptionOrNull()
        val episodes = storage.channels.observeEpisodes(id, xtreamSeriesKey(seriesId)).first()
        return XtreamSeriesDetailDto(
            series = series.toDto(cipher),
            episodes = episodes.map { it.toDto(cipher) },
            error = failure?.let { "Couldn't load episodes: ${it.message}" }
                ?.takeIf { episodes.isEmpty() },
        )
    }

    private suspend fun playlist(id: Long): Playlist =
        storage.playlists.get(id) ?: throw ResourceNotFound("playlist")
}

private val Playlist.isXtreamNative: Boolean get() = url == null && xtreamBase != null
private val Playlist.mode: String get() = when {
    url != null -> "url"
    xtreamBase != null -> "xtream"
    else -> "file"
}

private fun Playlist.toApiDto() = PlaylistDto(
    id, name, mode, xtreamBase != null, lastRefreshedMs, channelCount,
)

internal fun PlaylistUpsertRequest.preservingSecretsFrom(existing: Playlist): PlaylistUpsertRequest =
    when (mode) {
        "xtream" -> copy(
            server = server.ifBlank { existing.xtreamBase.orEmpty() },
            username = username.ifBlank { existing.xtreamUser.orEmpty() },
            password = password.ifBlank { existing.xtreamPass.orEmpty() },
        )
        "url" -> copy(
            url = url.ifBlank { existing.url.orEmpty() },
            epgUrl = epgUrl.ifBlank { existing.epgUrl.orEmpty() },
        )
        else -> this
    }
