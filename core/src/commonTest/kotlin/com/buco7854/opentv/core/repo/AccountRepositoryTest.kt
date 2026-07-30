package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.xtream.XtreamApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AccountRepositoryTest {
    private val playlist = Playlist(
        id = 7,
        name = "Provider",
        url = null,
        xtreamBase = "https://provider.example",
        xtreamUser = "alice",
        xtreamPass = "secret",
    )

    @Test
    fun providerFailureReturnsStaleDataWithItsOriginalFetchTime() = runTest {
        var providerAvailable = true
        var nowMs = 100L
        val repository = AccountRepository(
            XtreamApi {
                if (!providerAvailable) error("provider unavailable")
                """{"user_info":{"status":"Active","active_cons":"1","max_connections":"2"}}"""
            },
            CoreLog { _, _ -> },
            clock = { nowMs },
        )
        val fresh = assertIs<AccountInfoResult.Fresh>(
            repository.accountInfo(playlist, force = true),
        )

        providerAvailable = false
        nowMs = 500L
        val stale = assertIs<AccountInfoResult.Stale>(
            repository.accountInfo(playlist, force = true),
        )

        assertSame(fresh.info, stale.info)
        assertEquals(100L, stale.fetchedAtMs)
    }

    @Test
    fun providerFailureWithoutCachedDataIsUnavailable() = runTest {
        val repository = AccountRepository(
            XtreamApi { error("provider unavailable") },
            CoreLog { _, _ -> },
        )

        val unavailable = assertIs<AccountInfoResult.Unavailable>(
            repository.accountInfo(playlist, force = true),
        )
        assertEquals("provider unavailable", unavailable.cause?.message)
    }
}
