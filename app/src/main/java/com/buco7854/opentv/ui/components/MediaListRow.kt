package com.buco7854.opentv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.ui.theme.Mint

/** Shared list-row for live/movie/series items; every affordance is optional. */
@OptIn(ExperimentalFoundationApi::class)
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
    downloadState: Download? = null,
    onDownload: (() -> Unit)? = null,
    onGuide: (() -> Unit)? = null,
    guideHighlight: Boolean = false,
    trailingChevron: Boolean = false,
    /** Non-null enters selection mode: leading checkbox replaces trailing actions. */
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected == true) MaterialTheme.colorScheme.surfaceContainerHighest
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = modifier
            .focusHighlight(RoundedCornerShape(16.dp))
            // Clip before the clickable so the ripple stays inside the rounded corners.
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                SelectCheck(selected)
                Spacer(Modifier.width(12.dp))
            }
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
                            OtvProgressBar(
                                progress = { nowAiringProgress },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = Mint,
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
            if (selected == null) {
                if (isFavorite != null && onToggleFavorite != null) {
                    FavoriteIcon(isFavorite = isFavorite, onToggle = onToggleFavorite)
                }
                if (onGuide != null) {
                    IconButton(onClick = onGuide) {
                        Icon(
                            Icons.Outlined.CalendarMonth,
                            contentDescription = if (guideHighlight) {
                                stringResource(R.string.guide_open_catchup)
                            } else {
                                stringResource(R.string.guide_open)
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onDownload != null) {
                    DownloadStateIcon(state = downloadState, onDownload = onDownload)
                }
                if (trailingChevron) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Circular selection checkbox used by list rows and poster cards. */
@Composable
fun SelectCheck(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .then(
                if (selected) Modifier
                else Modifier.border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
