package com.buco7854.opentv.serverdata.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

data class GrantReplacement(
    val removed: Set<Long>,
    val added: Set<Long>,
)

/** Atomically replaces grants and updates the visibility state of associated downloads. */
suspend fun ServerUserDatabase.replaceUserPlaylistGrants(
    userId: String,
    playlistIds: List<Long>,
    atMs: Long,
): GrantReplacement = useWriterConnection { connection ->
    connection.immediateTransaction {
        val before = grants().forUser(userId).toSet()
        val after = playlistIds.distinct().toSet()
        grants().replaceForUser(userId, after.toList(), atMs)
        (before - after).forEach {
            downloads().suspendForPlaylist(userId, it, true, atMs)
        }
        (after - before).forEach {
            downloads().suspendForPlaylist(userId, it, false, atMs)
        }
        GrantReplacement(before - after, after - before)
    }
}
