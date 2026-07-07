package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.fold
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import com.ethran.notable.utils.plus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotebookReconciliationService @Inject constructor(
    private val appRepository: AppRepository,
    private val syncPreflightService: SyncPreflightService,
    private val notebookSyncService: NotebookSyncService,
    private val reporter: SyncProgressReporter
) {
    private val logger = SyncLogger

    suspend fun syncExistingNotebooks(
        webdavClient: WebDAVClient,
        uploadOnly: Boolean
    ): AppResult<Set<String>, DomainError> {
        val localNotebooks = appRepository.bookRepository.getAll()
        val preDownloadNotebookIds = localNotebooks.map { it.id }.toSet()
        val total = localNotebooks.size
        var persistentError: DomainError? = null

        localNotebooks.forEachIndexed { i, notebook ->
            reporter.beginItem(index = i + 1, total = total, name = notebook.title)
            // Individual notebook sync failures are non-fatal for the whole process
            syncNotebook(notebook.id, webdavClient, uploadOnly).onError { error ->
                persistentError = persistentError?.let { it + error } ?: error
            }
        }
        reporter.endItem()

        return if (persistentError != null) AppResult.Error(persistentError)
        else AppResult.Success(preDownloadNotebookIds)
    }

    suspend fun syncNotebook(
        notebookId: String,
        webdavClient: WebDAVClient,
        uploadOnly: Boolean
    ): AppResult<Unit, DomainError> {
        logger.i(TAG, "Syncing notebook: $notebookId")

        syncPreflightService.checkWifiConstraint().onError { return AppResult.Error(it) }
        syncPreflightService.checkClockSkew(webdavClient).onError { return AppResult.Error(it) }

        val localNotebook = appRepository.bookRepository.getById(notebookId)
            ?: return AppResult.Error(DomainError.NotFound("Notebook $notebookId"))

        val remotePath = SyncPaths.manifestFile(notebookId)
        val remoteExists = webdavClient.exists(remotePath)

        return if (remoteExists) {
            webdavClient.getFileWithMetadata(remotePath).flatMap { remoteManifest ->
                val remoteEtag = remoteManifest.etag
                    ?: return@flatMap AppResult.Error(DomainError.SyncError("Missing ETag for $remotePath"))

                val remoteManifestJson = remoteManifest.content.decodeToString()
                val remoteUpdatedAt = NotebookSerializer.getManifestUpdatedAt(remoteManifestJson)
                val diffMs = remoteUpdatedAt?.let { localNotebook.updatedAt.time - it.time }
                    ?: Long.MAX_VALUE

                when {
                    remoteUpdatedAt == null -> notebookSyncService.uploadNotebook(
                        localNotebook,
                        webdavClient,
                        manifestIfMatch = remoteEtag
                    )

                    diffMs < -TIMESTAMP_TOLERANCE_MS -> {
                        if (uploadOnly) {
                            AppResult.Error(DomainError.SyncUploadOnlySkip(localNotebook.title))
                        } else {
                            mergeNotebook(
                                localNotebook,
                                remoteManifestJson,
                                remoteEtag,
                                webdavClient,
                                remoteIsNewer = true
                            )
                        }
                    }

                    diffMs > TIMESTAMP_TOLERANCE_MS -> {
                        if (uploadOnly) {
                            notebookSyncService.uploadNotebook(
                                localNotebook,
                                webdavClient,
                                manifestIfMatch = remoteEtag
                            )
                        } else {
                            mergeNotebook(
                                localNotebook,
                                remoteManifestJson,
                                remoteEtag,
                                webdavClient,
                                remoteIsNewer = false
                            )
                        }
                    }

                    else -> {
                        logger.i(
                            TAG,
                            "= No changes (within tolerance), skipping ${localNotebook.title}"
                        )
                        AppResult.Success(Unit)
                    }
                }
            }
        } else {
            notebookSyncService.uploadNotebook(localNotebook, webdavClient)
        }
    }

    /**
     * Reconciles a notebook that differs between this device and the server.
     *
     * Notebook metadata and page *membership* still follow the newer manifest
     * (last-writer-wins, unchanged from before), but each page's *content* is
     * decided by the page's own updatedAt via [decidePageAction]. Edits made to
     * different pages of the same notebook on different devices both survive;
     * previously the newer side's whole notebook silently overwrote the other.
     *
     * Pages whose timestamps agree within tolerance are not transferred at all.
     */
    private suspend fun mergeNotebook(
        localNotebook: Notebook,
        remoteManifestJson: String,
        remoteEtag: String,
        webdavClient: WebDAVClient,
        remoteIsNewer: Boolean
    ): AppResult<Unit, DomainError> {
        val notebookId = localNotebook.id
        val remoteNotebook = NotebookSerializer.deserializeManifest(remoteManifestJson)
            .onFailure { return AppResult.Error(it) }

        logger.i(
            TAG,
            "Merging '${localNotebook.title}' page-by-page " +
                    "(${if (remoteIsNewer) "remote" else "local"} manifest wins membership)"
        )

        var persistentError: DomainError? = null

        if (remoteIsNewer) {
            try {
                appRepository.bookRepository.updatePreservingTimestamp(remoteNotebook)
            } catch (e: Exception) {
                return AppResult.Error(
                    DomainError.DatabaseError("Failed to update notebook locally: ${e.message}")
                )
            }
        }

        val pageIds = if (remoteIsNewer) remoteNotebook.pageIds else localNotebook.pageIds
        for (pageId in pageIds) {
            val localPage = appRepository.pageRepository.getById(pageId)
            val pagePath = SyncPaths.pageFile(notebookId, pageId)
            val remoteBytes = if (webdavClient.exists(pagePath)) {
                webdavClient.getFile(pagePath).fold(
                    onSuccess = { it },
                    onError = { error ->
                        logger.w(TAG, "Failed to fetch remote page $pageId: ${error.userMessage}")
                        null
                    }
                )
            } else {
                null
            }
            val remoteUpdatedAt = remoteBytes?.let {
                NotebookSerializer.getPageUpdatedAt(it.decodeToString())
            }

            val action = decidePageAction(
                localPage?.updatedAt, remoteUpdatedAt, TIMESTAMP_TOLERANCE_MS
            )
            val result = when (action) {
                PageSyncAction.UPLOAD_LOCAL -> localPage?.let {
                    notebookSyncService.uploadPage(it, notebookId, webdavClient)
                }

                PageSyncAction.APPLY_REMOTE -> notebookSyncService.downloadPage(
                    pageId, notebookId, webdavClient, preloadedBytes = remoteBytes
                )

                PageSyncAction.SKIP -> null
            }
            result?.onError { error ->
                persistentError = persistentError?.let { it + error } ?: error
            }
        }

        if (!remoteIsNewer) {
            val manifestJson = NotebookSerializer.serializeManifest(localNotebook)
            webdavClient.putFile(
                SyncPaths.manifestFile(notebookId),
                manifestJson.toByteArray(),
                "application/json",
                ifMatch = remoteEtag
            ).onError { error ->
                persistentError = persistentError?.let { it + error } ?: error
            }
        }

        return persistentError?.let { AppResult.Error(it) } ?: AppResult.Success(Unit)
    }

    companion object {
        private const val TAG = "NotebookReconciliationService"
        private const val TIMESTAMP_TOLERANCE_MS = 1000L
    }
}
