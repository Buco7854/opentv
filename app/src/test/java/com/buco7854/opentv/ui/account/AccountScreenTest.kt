package com.buco7854.opentv.ui.account

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountScreenTest {
    @Test
    fun expiryEarlierTodayIsAlreadyExpired() {
        val now = 2 * 86_400_000L

        assertEquals(-1, accountDaysLeft(expiryMs = now - 1, nowMs = now))
    }

    @Test
    fun futureExpiryWithinOneDayStillShowsZeroDaysLeft() {
        val now = 2 * 86_400_000L

        assertEquals(0, accountDaysLeft(expiryMs = now + 1, nowMs = now))
    }
}
