package com.buco7854.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.R
import com.buco7854.opentv.contract.RoomMemberDto
import com.buco7854.opentv.ui.components.OtvButton
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.OtvTonalButton
import com.buco7854.opentv.ui.components.QualityBadge
import com.buco7854.opentv.ui.components.RequestInitialFocusOnTv

internal data class WatchTogetherActions(
    val onWatchAlone: () -> Unit,
    val onJoin: (String) -> Unit,
    val onAnswerJoin: (String, Boolean) -> Unit,
    val onRequestControl: () -> Unit,
    val onAnswerControl: (String, Boolean) -> Unit,
    val onSetControl: (String, Boolean) -> Unit,
    val onKick: (String) -> Unit,
    val onLeave: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchTogetherSheet(
    state: WatchTogetherState,
    actions: WatchTogetherActions,
    onDismiss: () -> Unit,
) {
    val initialFocusRequester = remember { FocusRequester() }
    val manageableMember = state.members.firstOrNull {
        state.isHost && it.id != state.selfId && !it.host
    }
    val firstTarget = when {
        !state.inRoom && state.peers.isNotEmpty() -> "peer:${state.peers.first().id}"
        manageableMember != null -> "member:${manageableMember.id}"
        state.joinRequests.isNotEmpty() -> "join:${state.joinRequests.first().requestId}"
        state.controlRequests.isNotEmpty() -> "control:${state.controlRequests.first().peerId}"
        state.inRoom && !state.canControl -> "request-control"
        state.inRoom -> "leave"
        state.choosing -> "watch-alone"
        else -> null
    }
    if (firstTarget != null) {
        RequestInitialFocusOnTv(initialFocusRequester, firstTarget)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.watch_together_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            if (!state.inRoom && state.peers.isNotEmpty()) {
                Text(
                    stringResource(R.string.watch_together_offer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.peers.forEach { peer ->
                    WatchPeerRow(
                        name = peer.name,
                        actionLabel = stringResource(R.string.watch_together_join),
                        onAction = { actions.onJoin(peer.id) },
                        modifier = if (firstTarget == "peer:${peer.id}") {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                }
            }
            if (state.choosing) {
                OtvTonalButton(
                    onClick = actions.onWatchAlone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (firstTarget == "watch-alone") {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(stringResource(R.string.watch_together_watch_alone))
                }
            }

            state.members.forEach { member ->
                RoomMemberRow(
                    member = member,
                    isSelf = member.id == state.selfId,
                    canManage = state.isHost && member.id != state.selfId && !member.host,
                    onSetControl = { grant -> actions.onSetControl(member.id, grant) },
                    onKick = { actions.onKick(member.id) },
                    requestInitialFocus = firstTarget == "member:${member.id}",
                    initialFocusRequester = initialFocusRequester,
                )
            }

            state.joinRequests.forEach { request ->
                HorizontalDivider()
                Text(
                    stringResource(
                        R.string.watch_together_wants_join,
                        request.peerName.ifBlank { stringResource(R.string.watch_together_someone) },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                RoomActionRow(
                    negativeLabel = stringResource(R.string.watch_together_decline),
                    positiveLabel = stringResource(R.string.watch_together_accept),
                    onNegative = { actions.onAnswerJoin(request.requestId, false) },
                    onPositive = { actions.onAnswerJoin(request.requestId, true) },
                    requestInitialFocus = firstTarget == "join:${request.requestId}",
                    initialFocusRequester = initialFocusRequester,
                )
            }

            state.controlRequests.forEach { request ->
                HorizontalDivider()
                Text(
                    stringResource(
                        R.string.watch_together_wants_control,
                        request.peerName.ifBlank { stringResource(R.string.watch_together_someone) },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                RoomActionRow(
                    negativeLabel = stringResource(R.string.watch_together_decline),
                    positiveLabel = stringResource(R.string.watch_together_allow),
                    onNegative = { actions.onAnswerControl(request.peerId, false) },
                    onPositive = { actions.onAnswerControl(request.peerId, true) },
                    requestInitialFocus = firstTarget == "control:${request.peerId}",
                    initialFocusRequester = initialFocusRequester,
                )
            }

            if (state.inRoom) {
                Spacer(Modifier.height(4.dp))
                if (!state.canControl) {
                    OtvTonalButton(
                        onClick = actions.onRequestControl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (firstTarget == "request-control") {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Text(stringResource(R.string.watch_together_request_control))
                    }
                }
                OtvTextButton(
                    onClick = actions.onLeave,
                    enabled = !state.transitioning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (firstTarget == "leave") {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(stringResource(R.string.watch_together_leave))
                }
            }
        }
    }
}

@Composable
private fun WatchPeerRow(
    name: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OtvButton(
            onClick = onAction,
            modifier = modifier,
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun RoomMemberRow(
    member: RoomMemberDto,
    isSelf: Boolean,
    canManage: Boolean,
    onSetControl: (Boolean) -> Unit,
    onKick: () -> Unit,
    requestInitialFocus: Boolean,
    initialFocusRequester: FocusRequester,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (isSelf) stringResource(R.string.watch_together_you) else member.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QualityBadge(
                stringResource(
                    when {
                        member.host -> R.string.watch_together_host
                        member.controller -> R.string.watch_together_controller
                        else -> R.string.watch_together_viewer
                    },
                ),
            )
        }
        if (canManage) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OtvTextButton(
                    onClick = { onSetControl(!member.controller) },
                    modifier = if (requestInitialFocus) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    },
                ) {
                    Text(
                        stringResource(
                            if (member.controller) {
                                R.string.watch_together_revoke_control
                            } else {
                                R.string.watch_together_grant_control
                            },
                        ),
                    )
                }
                OtvTextButton(
                    onClick = onKick,
                    danger = true,
                ) {
                    Text(stringResource(R.string.watch_together_kick))
                }
            }
        }
    }
}

@Composable
private fun RoomActionRow(
    negativeLabel: String,
    positiveLabel: String,
    onNegative: () -> Unit,
    onPositive: () -> Unit,
    requestInitialFocus: Boolean,
    initialFocusRequester: FocusRequester,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        OtvTextButton(
            onClick = onNegative,
            modifier = if (requestInitialFocus) {
                Modifier.focusRequester(initialFocusRequester)
            } else {
                Modifier
            },
        ) {
            Text(negativeLabel)
        }
        OtvButton(
            onClick = onPositive,
        ) {
            Text(positiveLabel)
        }
    }
}

@Composable
internal fun WatchTogetherNoticeOverlay(notice: WatchTogetherNotice) {
    val message = when (notice.kind) {
        WatchTogetherNoticeKind.ADMIN_MESSAGE -> notice.text.orEmpty()
        WatchTogetherNoticeKind.JOIN_REQUEST -> stringResource(
            R.string.watch_together_wants_join,
            notice.text?.ifBlank { stringResource(R.string.watch_together_someone) }
                ?: stringResource(R.string.watch_together_someone),
        )
        WatchTogetherNoticeKind.CONTROL_REQUEST -> stringResource(
            R.string.watch_together_wants_control,
            notice.text?.ifBlank { stringResource(R.string.watch_together_someone) }
                ?: stringResource(R.string.watch_together_someone),
        )
        WatchTogetherNoticeKind.JOINED -> stringResource(R.string.watch_together_joined)
        WatchTogetherNoticeKind.JOIN_DECLINED ->
            stringResource(R.string.watch_together_join_declined)
        WatchTogetherNoticeKind.CONTROL_GRANTED ->
            stringResource(R.string.watch_together_control_granted)
        WatchTogetherNoticeKind.CONTROL_DENIED ->
            stringResource(R.string.watch_together_control_denied)
        WatchTogetherNoticeKind.ROOM_ENDED ->
            stringResource(R.string.watch_together_room_ended)
        WatchTogetherNoticeKind.ACTION_FAILED ->
            stringResource(R.string.watch_together_action_failed)
    }
    Surface(
        color = Color.Black.copy(alpha = 0.82f),
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 8.dp,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            // Admin messages may be up to 1000 characters. Cap the overlay so a
            // long one cannot blanket the video — on a TV there is no scrolling.
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}

@Composable
internal fun WatchTogetherLoadingOverlay() {
    Box(Modifier.fillMaxWidth().padding(top = 72.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            color = Color.Black.copy(alpha = 0.75f),
            contentColor = Color.White,
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.height(24.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Text(stringResource(R.string.watch_together_syncing))
            }
        }
    }
}
