package com.buco7854.opentv.source

const val DEFAULT_CATALOG_PAGE_SIZE = 50

interface CatalogGateway {
    val source: SourceId

    /**
     * Per-user playlist operations. Unlisted operations must not be rendered.
     *
     * Local playlist management still lives in the local application layer;
     * hub gateways override this boundary with server-authoritative capabilities.
     */
    suspend fun playlistCapabilities(): CatalogResult<PlaylistCapabilities> =
        CatalogResult.Success(PlaylistCapabilities(emptyMap()))

    suspend fun clearWatchProgress(): CatalogResult<Unit> =
        CatalogResult.Failed(UnsupportedOperationException("Clearing progress is not available"))

    suspend fun correctCategoryType(groupTitle: String, kind: Int?): CatalogResult<Unit> =
        CatalogResult.Failed(UnsupportedOperationException("Category correction is not available"))

    suspend fun playlistEditForm(): CatalogResult<PlaylistEditForm> =
        CatalogResult.Failed(UnsupportedOperationException("Playlist editing is not available"))

    suspend fun updatePlaylist(update: PlaylistEditUpdate): CatalogResult<Unit> =
        CatalogResult.Failed(UnsupportedOperationException("Playlist editing is not available"))

    suspend fun refreshPlaylist(
        force: Boolean = true,
        onProgress: (PlaylistRefreshProgress) -> Unit = {},
    ): CatalogResult<PlaylistRefreshResult> =
        CatalogResult.Failed(UnsupportedOperationException("Playlist refresh is not available"))

    suspend fun playlistDeleteInfo(): CatalogResult<PlaylistDeleteInfo> =
        CatalogResult.Failed(UnsupportedOperationException("Playlist deletion is not available"))

    suspend fun deletePlaylist(): CatalogResult<Unit> =
        CatalogResult.Failed(UnsupportedOperationException("Playlist deletion is not available"))

    suspend fun providerAccount(force: Boolean = true): CatalogResult<ProviderAccountInfo> =
        CatalogResult.Failed(UnsupportedOperationException("Provider account is not available"))

    /**
     * Source capabilities may depend on persisted source configuration.
     *
     * Keeping this boundary suspending lets platform stores resolve that
     * configuration without blocking UI construction.
     */
    suspend fun traits(): SourceTraits

    suspend fun groups(kind: Int): CatalogResult<List<CatalogGroup>>

    suspend fun channels(
        kind: Int,
        group: String,
        offset: Int = 0,
        limit: Int = DEFAULT_CATALOG_PAGE_SIZE,
        filter: String = "",
    ): CatalogResult<Page<CatalogItem>>

    suspend fun seriesGroups(
        group: String,
        offset: Int = 0,
        limit: Int = DEFAULT_CATALOG_PAGE_SIZE,
        filter: String = "",
    ): CatalogResult<Page<CatalogItem>>

    suspend fun xtreamSeries(
        category: String,
        offset: Int = 0,
        limit: Int = DEFAULT_CATALOG_PAGE_SIZE,
        filter: String = "",
    ): CatalogResult<Page<CatalogItem>>

    suspend fun episodes(
        seriesKey: String,
        season: Int? = null,
        offset: Int = 0,
        limit: Int = DEFAULT_CATALOG_PAGE_SIZE,
    ): CatalogResult<Page<CatalogItem>>

    suspend fun search(query: String): CatalogResult<CatalogSearchResult>
    suspend fun nowAiring(tvgIds: Set<String>): CatalogResult<Map<String, CatalogProgramme>>
    suspend fun guideIds(tvgIds: Set<String>): CatalogResult<Set<String>>

    suspend fun favorites(
        offset: Int = 0,
        limit: Int = DEFAULT_CATALOG_PAGE_SIZE,
    ): CatalogResult<Page<CatalogItem>>

    suspend fun resumePoints(): CatalogResult<List<CatalogResumePoint>>
    suspend fun guideFor(ref: ContentRef): CatalogResult<List<CatalogGuideEntry>>
    suspend fun detail(ref: ContentRef): CatalogResult<CatalogDetail?>

    suspend fun seriesDetail(
        ref: ContentRef,
        seriesKey: String,
        seriesId: String?,
    ): CatalogResult<CatalogDetail?> = detail(ref)

    suspend fun isFavorite(ref: ContentRef): CatalogResult<Boolean>

    /** Returns the favorite state after the toggle. */
    suspend fun toggleFavorite(ref: ContentRef): CatalogResult<Boolean>

    /** Idempotently sets and returns the favorite state. */
    suspend fun setFavorite(ref: ContentRef, favorite: Boolean): CatalogResult<Boolean> =
        when (val current = isFavorite(ref)) {
            is CatalogResult.Success ->
                if (current.value == favorite) current else toggleFavorite(ref)
            CatalogResult.SignedOut -> CatalogResult.SignedOut
            CatalogResult.Unreachable -> CatalogResult.Unreachable
            is CatalogResult.Failed -> current
        }
}
