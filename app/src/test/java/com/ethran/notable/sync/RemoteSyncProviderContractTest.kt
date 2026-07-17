package com.ethran.notable.sync

import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

class RemoteSyncProviderContractTest {
    @Test fun webDav_contract() = verifyContract(MemoryProvider(SyncProviderType.WEBDAV))
    @Test fun drive_contract() = verifyContract(MemoryProvider(SyncProviderType.GOOGLE_DRIVE))

    private fun verifyContract(provider: RemoteSyncProvider) {
        assertTrue(provider.createCollection("/notable-v2/notebooks") is AppResult.Success)
        assertTrue(provider.putFile("/notable-v2/notebooks/a.json", "one".toByteArray()) is AppResult.Success)
        assertTrue(provider.exists("/notable-v2/notebooks/a.json"))
        val downloaded = provider.getFile("/notable-v2/notebooks/a.json") as? AppResult.Success
        val listed = provider.listCollection("/notable-v2/notebooks") as? AppResult.Success
        assertEquals("one", downloaded?.data?.decodeToString())
        assertEquals(listOf("a.json"), listed?.data)
        assertTrue(provider.delete("/notable-v2/notebooks/a.json") is AppResult.Success)
        assertFalse(provider.exists("/notable-v2/notebooks/a.json"))
    }

    private class MemoryProvider(override val providerType: SyncProviderType) : RemoteSyncProvider {
        private val files = linkedMapOf<String, ByteArray>()
        private val directories = mutableSetOf<String>()
        override fun testConnection() = AppResult.Success(ConnectionTestResult())
        override fun getServerTime(): Long? = null
        override fun exists(path: String) = path in files || path in directories
        override fun existsResult(path: String) = AppResult.Success(exists(path))
        override fun createCollection(path: String): AppResult<Unit, DomainError> { directories += path; return AppResult.Success(Unit) }
        override fun putFile(path: String, content: ByteArray, contentType: String, ifMatch: String?): AppResult<Unit, DomainError> { files[path] = content; return AppResult.Success(Unit) }
        override fun putFile(path: String, localFile: File, contentType: String, ifMatch: String?) = putFile(path, localFile.readBytes(), contentType, ifMatch)
        override fun getFile(path: String): AppResult<ByteArray, DomainError> = files[path]?.let { AppResult.Success(it) } ?: AppResult.Error(DomainError.NotFound(path))
        override fun getFileWithMetadata(path: String): AppResult<DownloadedFile, DomainError> = when (val value = getFile(path)) { is AppResult.Success -> AppResult.Success(DownloadedFile(value.data, null)); is AppResult.Error -> value }
        override fun getFile(path: String, localFile: File): AppResult<Unit, DomainError> = when (val value = getFile(path)) { is AppResult.Success -> { localFile.writeBytes(value.data); AppResult.Success(Unit) }; is AppResult.Error -> value }
        override fun delete(path: String): AppResult<Unit, DomainError> { files.remove(path); directories.remove(path); return AppResult.Success(Unit) }
        override fun listCollection(path: String): AppResult<List<String>, DomainError> = AppResult.Success(files.keys.filter { it.substringBeforeLast('/') == path }.map { it.substringAfterLast('/') })
        override fun listCollectionWithMetadata(path: String): AppResult<List<RemoteEntry>, DomainError> = when (val value = listCollection(path)) { is AppResult.Success -> AppResult.Success(value.data.map { RemoteEntry(it, Date(0)) }); is AppResult.Error -> value }
        override fun ensureParentDirectories(path: String): AppResult<Unit, DomainError> = createCollection(path.substringBeforeLast('/'))
    }
}
