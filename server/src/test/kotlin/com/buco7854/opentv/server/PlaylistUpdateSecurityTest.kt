package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Playlist
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistUpdateSecurityTest {
    @Test
    fun `blank Xtream fields preserve write-only credentials`() {
        val existing = Playlist(
            id = 7,
            name = "Provider",
            url = null,
            xtreamBase = "https://provider.example",
            xtreamUser = "alice",
            xtreamPass = "secret",
        )

        val resolved = PlaylistUpsertRequest(mode = "xtream", name = "Renamed")
            .preservingSecretsFrom(existing)

        assertEquals("https://provider.example", resolved.server)
        assertEquals("alice", resolved.username)
        assertEquals("secret", resolved.password)
        assertEquals("Renamed", resolved.name)
    }

    @Test
    fun `provided credential fields replace only those values`() {
        val existing = Playlist(
            id = 7,
            name = "Provider",
            url = null,
            xtreamBase = "https://provider.example",
            xtreamUser = "alice",
            xtreamPass = "old-secret",
        )

        val resolved = PlaylistUpsertRequest(
            mode = "xtream",
            name = "Provider",
            password = "new-secret",
        ).preservingSecretsFrom(existing)

        assertEquals("https://provider.example", resolved.server)
        assertEquals("alice", resolved.username)
        assertEquals("new-secret", resolved.password)
    }
}
