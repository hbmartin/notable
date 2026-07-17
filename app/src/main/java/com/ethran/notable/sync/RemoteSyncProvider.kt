package com.ethran.notable.sync

import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import java.io.File

enum class SyncProviderType { WEBDAV, GOOGLE_DRIVE }

/** Storage contract used by reconciliation. Paths are provider-neutral virtual paths. */
interface RemoteSyncProvider {
    val providerType: SyncProviderType
    fun testConnection(): AppResult<ConnectionTestResult, DomainError>
    fun getServerTime(): Long?
    fun exists(path: String): Boolean
    fun existsResult(path: String): AppResult<Boolean, DomainError>
    fun createCollection(path: String): AppResult<Unit, DomainError>
    fun putFile(path: String, content: ByteArray, contentType: String = "application/octet-stream", ifMatch: String? = null): AppResult<Unit, DomainError>
    fun putFile(path: String, localFile: File, contentType: String = "application/octet-stream", ifMatch: String? = null): AppResult<Unit, DomainError>
    fun getFile(path: String): AppResult<ByteArray, DomainError>
    fun getFileWithMetadata(path: String): AppResult<DownloadedFile, DomainError>
    fun getFile(path: String, localFile: File): AppResult<Unit, DomainError>
    fun delete(path: String): AppResult<Unit, DomainError>
    fun listCollection(path: String): AppResult<List<String>, DomainError>
    fun listCollectionWithMetadata(path: String): AppResult<List<RemoteEntry>, DomainError>
    fun ensureParentDirectories(path: String): AppResult<Unit, DomainError>
}
