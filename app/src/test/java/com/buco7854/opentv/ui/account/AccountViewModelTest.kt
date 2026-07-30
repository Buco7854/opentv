package com.buco7854.opentv.ui.account

import com.buco7854.opentv.core.repo.AccountInfoResult
import com.buco7854.opentv.core.xtream.AccountInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AccountViewModelTest {
    private val info = AccountInfo(
        activeConnections = 1,
        maxConnections = 2,
        status = "Active",
        expiresAtMs = null,
    )

    @Test
    fun staleRefreshKeepsTheOriginalUpdateTimeAndExplainsTheFailure() {
        val fresh = AccountUiState(refreshing = true).withAccountInfo(
            AccountInfoResult.Fresh(info, fetchedAtMs = 100),
            unavailableError = "unavailable",
            staleError = "showing earlier data",
        )

        val stale = fresh.copy(refreshing = true).withAccountInfo(
            AccountInfoResult.Stale(info, fetchedAtMs = 100),
            unavailableError = "unavailable",
            staleError = "showing earlier data",
        )

        assertSame(info, stale.info)
        assertEquals(100L, stale.updatedAtMs)
        assertEquals("showing earlier data", stale.error)
        assertEquals(false, stale.refreshing)
    }

    @Test
    fun unavailableRefreshKeepsAlreadyDisplayedDataWithoutRestampingIt() {
        val state = AccountUiState(info = info, updatedAtMs = 100, refreshing = true)

        val unavailable = state.withAccountInfo(
            AccountInfoResult.Unavailable(),
            unavailableError = "unavailable",
            staleError = "showing earlier data",
        )

        assertSame(info, unavailable.info)
        assertEquals(100L, unavailable.updatedAtMs)
        assertEquals("unavailable", unavailable.error)
    }
}
