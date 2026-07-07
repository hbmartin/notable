package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class PageMergeTest {

    private val toleranceMs = 1000L
    private val base = 1_700_000_000_000L

    @Test
    fun `both timestamps missing skips`() {
        assertEquals(PageSyncAction.SKIP, decidePageAction(null, null, toleranceMs))
    }

    @Test
    fun `missing local applies remote`() {
        assertEquals(
            PageSyncAction.APPLY_REMOTE,
            decidePageAction(null, Date(base), toleranceMs)
        )
    }

    @Test
    fun `missing remote uploads local`() {
        assertEquals(
            PageSyncAction.UPLOAD_LOCAL,
            decidePageAction(Date(base), null, toleranceMs)
        )
    }

    @Test
    fun `local newer beyond tolerance uploads`() {
        assertEquals(
            PageSyncAction.UPLOAD_LOCAL,
            decidePageAction(Date(base + toleranceMs + 1), Date(base), toleranceMs)
        )
    }

    @Test
    fun `remote newer beyond tolerance applies remote`() {
        assertEquals(
            PageSyncAction.APPLY_REMOTE,
            decidePageAction(Date(base), Date(base + toleranceMs + 1), toleranceMs)
        )
    }

    @Test
    fun `equal timestamps skip`() {
        assertEquals(
            PageSyncAction.SKIP,
            decidePageAction(Date(base), Date(base), toleranceMs)
        )
    }

    @Test
    fun `difference exactly at tolerance skips`() {
        assertEquals(
            PageSyncAction.SKIP,
            decidePageAction(Date(base + toleranceMs), Date(base), toleranceMs)
        )
        assertEquals(
            PageSyncAction.SKIP,
            decidePageAction(Date(base), Date(base + toleranceMs), toleranceMs)
        )
    }

    @Test
    fun `difference within tolerance skips both directions`() {
        assertEquals(
            PageSyncAction.SKIP,
            decidePageAction(Date(base + 500), Date(base), toleranceMs)
        )
        assertEquals(
            PageSyncAction.SKIP,
            decidePageAction(Date(base), Date(base + 500), toleranceMs)
        )
    }
}
