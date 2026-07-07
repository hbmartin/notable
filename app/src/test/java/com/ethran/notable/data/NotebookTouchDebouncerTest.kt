package com.ethran.notable.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NotebookTouchDebouncerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `rapid touches of one notebook flush once`() {
        val touched = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)
        val debouncer = NotebookTouchDebouncer(scope, debounceMs = 50) {
            touched.add(it)
            latch.countDown()
        }

        repeat(20) { debouncer.touch("book-1") }

        assertTrue("flush did not happen", latch.await(5, TimeUnit.SECONDS))
        // Give a moment for any (incorrect) extra flushes to land.
        Thread.sleep(200)
        assertEquals(listOf("book-1"), touched)
    }

    @Test
    fun `touches of different notebooks all flush`() {
        val touched = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(2)
        val debouncer = NotebookTouchDebouncer(scope, debounceMs = 50) {
            touched.add(it)
            latch.countDown()
        }

        debouncer.touch("book-1")
        debouncer.touch("book-2")
        debouncer.touch("book-1")

        assertTrue("flush did not happen", latch.await(5, TimeUnit.SECONDS))
        assertEquals(setOf("book-1", "book-2"), touched.toSet())
        assertEquals(2, touched.size)
    }

    @Test
    fun `touch after flush schedules another flush`() {
        val touched = Collections.synchronizedList(mutableListOf<String>())
        val firstFlush = CountDownLatch(1)
        val secondFlush = CountDownLatch(2)
        val debouncer = NotebookTouchDebouncer(scope, debounceMs = 50) {
            touched.add(it)
            firstFlush.countDown()
            secondFlush.countDown()
        }

        debouncer.touch("book-1")
        assertTrue(firstFlush.await(5, TimeUnit.SECONDS))

        debouncer.touch("book-1")
        assertTrue("second flush did not happen", secondFlush.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("book-1", "book-1"), touched)
    }
}
