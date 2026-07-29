package com.buco7854.opentv.hub.playback

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolves every media request against the latest lease grant. The wrapped
 * factory remains responsible for normal OkHttp configuration; no bearer is
 * added here because hub media URLs authorize themselves.
 */
@UnstableApi
class HubMediaDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val grantProvider: () -> String?,
) : DataSource.Factory {

    constructor(
        upstream: DataSource.Factory,
        controller: HubPlaybackController,
    ) : this(upstream, controller::currentGrant)

    override fun createDataSource(): DataSource = ResolvingDataSource(
        upstream.createDataSource(),
        ResolvingDataSource.Resolver { dataSpec ->
            val grant = grantProvider() ?: return@Resolver dataSpec
            val resolved = replaceMediaGrant(dataSpec.uri.toString(), grant) ?: return@Resolver dataSpec
            dataSpec.withUri(resolved.toUri())
        },
    )
}

internal fun replaceMediaGrant(url: String, grant: String): String? =
    url.toHttpUrlOrNull()
        ?.newBuilder()
        ?.setQueryParameter("g", grant)
        ?.build()
        ?.toString()
