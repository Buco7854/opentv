package com.buco7854.opentv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

class PosterItem(
    val id: String,
    val image: String?,
    val title: String,
    val subtitle: String?,
    /** Quality tags rendered as a badge over the poster (e.g. "4K"). */
    val tags: List<String> = emptyList(),
)

/** Portrait-card grid: the alternative to row lists for movies and series. */
@Composable
fun PosterGrid(
    items: List<PosterItem>,
    fallback: ImageVector,
    onClick: (id: String) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState("Empty category", "No items in this category.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            Card(
                onClick = { onClick(item.id) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
            ) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.image.isNullOrBlank()) {
                            Icon(
                                fallback,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp),
                            )
                        } else {
                            AsyncImage(
                                model = item.image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        item.tags.firstOrNull()?.let { tag ->
                            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                                QualityBadge(tag)
                            }
                        }
                    }
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            minLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
