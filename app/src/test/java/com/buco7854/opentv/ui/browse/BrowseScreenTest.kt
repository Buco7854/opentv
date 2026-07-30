package com.buco7854.opentv.ui.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseScreenTest {
    @Test
    fun `an empty category list still loading is a spinner not an empty state`() {
        assertTrue(
            browseListLoading(
                loading = true,
                groupSelected = true,
                groupCount = 12,
                itemCount = 0,
            ),
        )
    }

    @Test
    fun `paging more of a category keeps the list on screen`() {
        assertFalse(
            browseListLoading(
                loading = true,
                groupSelected = true,
                groupCount = 12,
                itemCount = 50,
            ),
        )
    }

    @Test
    fun `the category list has its own first load`() {
        assertTrue(
            browseListLoading(
                loading = true,
                groupSelected = false,
                groupCount = 0,
                itemCount = 0,
            ),
        )
        assertFalse(
            browseListLoading(
                loading = true,
                groupSelected = false,
                groupCount = 12,
                itemCount = 0,
            ),
        )
    }

    @Test
    fun `a settled category that really is empty says so`() {
        assertFalse(
            browseListLoading(
                loading = false,
                groupSelected = true,
                groupCount = 12,
                itemCount = 0,
            ),
        )
    }
}
