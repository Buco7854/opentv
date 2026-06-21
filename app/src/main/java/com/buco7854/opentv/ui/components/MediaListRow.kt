package com.buco7854.opentv.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.data.db.DownloadEntity
import com.buco7854.opentv.ui.theme.Mint

/**
 * The single list-row used for live / movie / series items across browse,
 * search and favorites, so the same item always looks and behaves the same.
 * Every affordance is optional; pass only what applies.
 */
@Composable
fun MediaListRow(
    title: String,
    logo: String?,
    fallbackKind: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleTags: List<String> = emptyList(),
    nowAiringTitle: String? = null,
    nowAiringProgress: Float? = null,
    isFavorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
    downloadState: DownloadEntity? = null,
    onDownload: (() -> Unit)? = null,
    onGuide: (() -> Unit)? = null,
    guideHighlight: Boolean = false,
    trailingChevron: Boolean = false,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.focusHighlight(RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(logo, kindIcon(fallbackKind))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (titleTags.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        BadgeRow(titleTags)
                    }
                }
                when {
                    nowAiringTitle != null -> {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            nowAiringTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Mint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (nowAiringProgress != null) {
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { nowAiringProgress },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                        }
                    }
                    subtitle != null -> Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isFavorite != null && onToggleFavorite != null) {
                FavoriteIcon(isFavorite = isFavorite, onToggle = onToggleFavorite)
            }
            if (onGuide != null) {
                IconButton(onClick = onGuide) {
                    Icon(
                        Icons.Rounded.CalendarMonth,
                        contentDescription = if (guideHighlight) "Guide & catch-up" else "Guide",
                        tint = if (guideHighlight) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onDownload != null) {
                DownloadStateIcon(state = downloadState, onDownload = onDownload)
            }
            if (trailingChevron) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
