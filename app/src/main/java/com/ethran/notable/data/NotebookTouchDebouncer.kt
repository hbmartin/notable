package com.ethran.notable.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces parent-notebook "touch" timestamp bumps.
 *
 * Every pen action wants to mark the notebook as modified; writing the notebook
 * row each time would rewrite it dozens of times per minute (and dirty sync
 * state just as often), so touches are collected and flushed at most once per
 * debounce window.
 *
 * Extracted from PageDataManager so the debounce state has its own lock and can
 * be tested in isolation. [touchAction] performs the actual persistence.
 */
class NotebookTouchDebouncer(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val touchAction: suspend (notebookId: String) -> Unit
) {
    private val pending = mutableSetOf<String>()
    private var flushJob: Job? = null

    fun touch(notebookId: String) {
        synchronized(pending) {
            pending.add(notebookId)
            if (flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(debounceMs)
                    flush()
                }
            }
        }
    }

    private suspend fun flush() {
        val toTouch = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        toTouch.forEach { touchAction(it) }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 5_000L
    }
}
