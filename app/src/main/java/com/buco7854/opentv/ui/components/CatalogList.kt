package com.buco7854.opentv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A stable scrolling host, including its header while requests replace the result rows. */
@Composable
internal fun CatalogList(
    itemCount: Int,
    header: (@Composable () -> Unit)?,
    status: (@Composable () -> Unit)?,
    empty: @Composable () -> Unit,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null) item(key = "catalog-header", contentType = "header") { header() }
        if (status != null || itemCount == 0 && !hasMore) {
            item(key = "catalog-status", contentType = "status") {
                CatalogStatus { if (status != null) status() else empty() }
            }
        } else {
            content()
            if (hasMore) item(key = "catalog-page-$itemCount", contentType = "page") {
                LaunchedEffect(itemCount, loadingMore) { if (!loadingMore) onLoadMore() }
                OtvProgressBar(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
        }
    }
}

/** Bounded minimum, intrinsic maximum: no unbounded fill or nested scrolling in status items. */
@Composable
internal fun CatalogStatus(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) { content() }
}
