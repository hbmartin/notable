package com.ethran.notable.data

import android.os.FileObserver
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.io.IN_IGNORED
import com.ethran.notable.io.fileObserverEventNames
import com.ethran.notable.io.waitForFileAvailable
import com.ethran.notable.utils.chunked
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Watches page-background files (PDFs, images) for external modification and
 * reports which pages need their background invalidated.
 *
 * Extracted from PageDataManager so file observation has its own lock and
 * lifecycle, independent of the page-cache lock. All observer bookkeeping
 * ([fileObservers], [fileToPages]) is guarded by [lock]; what to do when a
 * page's background changes is the caller's business (see [startCollector]).
 */
class BackgroundFileWatcher(
    private val scope: CoroutineScope,
    private val appEventBus: AppEventBus
) {
    private val log = ShipBook.getLogger("BackgroundFileWatcher")

    private val lock = Any()

    // filename -> observer watching it
    private val fileObservers = mutableMapOf<String, FileObserver>()

    // filename -> pages whose background uses this file
    private val fileToPages = mutableMapOf<String, MutableSet<String>>()

    private val invalidateFileFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Start observing a background file for changes.
     * Registers the pageId to the file, and launches a FileObserver if not already present.
     */
    fun watch(pageId: String, filePath: String) {
        synchronized(lock) {
            fileToPages.getOrPut(filePath) { mutableSetOf() }.add(pageId)
            if (fileObservers.containsKey(filePath)) return // Already observing this file

            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                log.w("Cannot observe background file: $filePath does not exist or is not readable")
                return
            }
            val mask = (FileObserver.CREATE or
                    FileObserver.DELETE or
                    FileObserver.DELETE_SELF or
                    FileObserver.CLOSE_WRITE or
                    FileObserver.MOVED_TO or
                    FileObserver.MOVE_SELF)

            // Launch a FileObserver for this file
            val observer = object : FileObserver(file, mask) {
                override fun onEvent(event: Int, path: String?) {
                    scope.launch {
                        if (event == IN_IGNORED)
                            return@launch
                        val eventString = fileObserverEventNames(event)

                        log.d("Background file changed: $filePath [event=$eventString]")
                        if (event == DELETE || event == DELETE_SELF) {
                            log.d("Background file deleted.")
                            synchronized(lock) {
                                fileObservers.remove(filePath)?.stopWatching()
                            }
                            if (!waitForFileAvailable(filePath)) {
                                log.w("File changed, but does not exist: $filePath")
                                appEventBus.tryEmit(
                                    AppEvent.ActionHint(
                                        "Background does not exist",
                                        3000
                                    )
                                )
                                return@launch
                            } else
                                watch(pageId, filePath)
                        }

                        invalidateFileFlow.emit(filePath)
                    }
                }
            }
            observer.startWatching()
            fileObservers[filePath] = observer
        }
    }

    /**
     * Stop observing the background file for the given page.
     * Cleans up observers if no more pages are using the file.
     */
    fun unwatch(pageId: String) {
        synchronized(lock) {
            val iterator = fileToPages.entries.iterator()
            while (iterator.hasNext()) {
                val (filePath, pageIds) = iterator.next()
                if (pageIds.remove(pageId) && pageIds.isEmpty()) {
                    fileObservers.remove(filePath)?.stopWatching()
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Starts the collector that batches file-change events (all events received
     * in a 10ms window) and invokes [onPageInvalidated] for every page whose
     * background file changed.
     */
    fun startCollector(onPageInvalidated: suspend (pageId: String) -> Unit) {
        scope.launch {
            invalidateFileFlow.chunked(10)
                .collect { filePathBatch ->
                    val uniqueFilePaths = filePathBatch.distinct()
                    if (uniqueFilePaths.isEmpty()) return@collect
                    log.i("Persisting batch of fileChanges: $uniqueFilePaths")
                    for (filePath in uniqueFilePaths) {
                        // Snapshot under the lock; the callback may be slow.
                        val pages = synchronized(lock) {
                            fileToPages[filePath]?.toList().orEmpty()
                        }
                        pages.forEach { onPageInvalidated(it) }
                    }
                }
        }
    }
}
