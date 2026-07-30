package com.buco7854.opentv.server

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.repo.AccountInfoResult
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.data.createRoomStorage
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProviderConnectionLimitsTest {
    private class MutableClock(var value: Long = 0) : ServerClock {
        override fun nowMs(): Long = value
    }

    @Test
    fun `stale limit cannot raise fallback and is retried before a fresh limit`() = runTest {
        val root = Files.createTempDirectory("stale-provider-limit")
        val storage = createRoomStorage(root.resolve("catalog.db").toString())
        try {
            storage.playlists.insert(playlist())
            val stored = storage.playlists.getAll().single()
            val clock = MutableClock()
            var available = true
            var reportedLimit = 7
            var attempts = 0
            val account = AccountRepository(
                XtreamApi {
                    attempts += 1
                    if (!available) error("provider unavailable")
                    accountJson(reportedLimit)
                },
                CoreLog { _, _ -> },
                clock = clock::nowMs,
            )
            assertIs<AccountInfoResult.Fresh>(account.accountInfo(stored, force = true))
            val limits = ProviderConnectionLimits(storage, account, fallback = 2, clock = clock)

            available = false
            clock.value = AccountRepository.CACHE_MS + 1
            assertEquals(2, limits.forUrl(streamUrl))
            assertEquals(2, attempts)

            available = true
            reportedLimit = 5
            clock.value += 29_999
            assertEquals(2, limits.forUrl(streamUrl))
            assertEquals(2, attempts)

            clock.value += 1
            assertEquals(5, limits.forUrl(streamUrl))
            assertEquals(3, attempts)
        } finally {
            storage.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stale limit may lower fallback to refuse excess admission`() = runTest {
        val root = Files.createTempDirectory("conservative-provider-limit")
        val storage = createRoomStorage(root.resolve("catalog.db").toString())
        try {
            storage.playlists.insert(playlist())
            val stored = storage.playlists.getAll().single()
            val clock = MutableClock()
            var available = true
            val account = AccountRepository(
                XtreamApi {
                    if (!available) error("provider unavailable")
                    accountJson(limit = 1)
                },
                CoreLog { _, _ -> },
                clock = clock::nowMs,
            )
            assertIs<AccountInfoResult.Fresh>(account.accountInfo(stored, force = true))
            val limits = ProviderConnectionLimits(storage, account, fallback = 2, clock = clock)

            available = false
            clock.value = AccountRepository.CACHE_MS + 1

            assertEquals(1, limits.forUrl(streamUrl))
        } finally {
            storage.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun playlist() = Playlist(
        name = "Provider",
        url = null,
        xtreamBase = "https://provider.example",
        xtreamUser = "alice",
        xtreamPass = "secret",
    )

    private fun accountJson(limit: Int) =
        """{"user_info":{"status":"Active","max_connections":"$limit"}}"""

    private companion object {
        const val streamUrl = "https://provider.example/live/alice/secret/42.ts"
    }
}
