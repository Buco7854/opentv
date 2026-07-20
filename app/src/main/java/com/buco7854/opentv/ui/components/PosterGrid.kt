package com.buco7854.opentv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
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
            PosterCard(item = item, fallback = fallback, onClick = { onClick(item.id) })
        }
    }
}

/** Single portrait card, reusable outside the grid container (e.g. favorites). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: PosterItem,
    fallback: ImageVector,
    onClick: () -> Unit,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = if (selected == true) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .focusHighlight(RoundedCornerShape(16.dp))
            // Clip before the clickable so the ripple stays inside the rounded corners.
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                        if (selected != null) {
                            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                                SelectCheck(selected)
                            }
                        }
                    }
                    // Fixed min height keeps cards uniform whether or not the title wraps.
                    Column(
                        Modifier
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .heightIn(min = if (item.subtitle != null) 56.dp else 40.dp),
                    ) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
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
