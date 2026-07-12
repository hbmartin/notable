@file:Suppress("AvoidVarsExceptWithDelegate", "NoCallbacksInFunctions")

package com.ethran.notable.sync

import com.ethran.notable.io.AtomicFileStore
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** Drive REST implementation over a user-selected folder shared through the Google Picker. */
class GoogleDriveProvider(
    private val folderId: String,
    private val tokenProvider: () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
) : RemoteSyncProvider {
    override val providerType = SyncProviderType.GOOGLE_DRIVE
    private val ids = ConcurrentHashMap<String, String>().apply { put("/", folderId) }

    override fun testConnection(): AppResult<ConnectionTestResult, DomainError> =
        request(Request.Builder().url("$FILES_URL/$folderId?fields=id").get()) { AppResult.Success(ConnectionTestResult()) }

    override fun getServerTime(): Long? = null
    override fun exists(path: String): Boolean = resolve(path, createFolders = false) != null
    override fun existsResult(path: String): AppResult<Boolean, DomainError> = AppResult.Success(exists(path))

    override fun createCollection(path: String): AppResult<Unit, DomainError> =
        if (resolve(path, createFolders = true, leafIsFolder = true) != null) AppResult.Success(Unit)
        else AppResult.Error(DomainError.SyncError("Could not create Drive folder $path"))

    override fun putFile(path: String, content: ByteArray, contentType: String, ifMatch: String?): AppResult<Unit, DomainError> =
        put(path, content.toRequestBody(contentType.toMediaType()), ifMatch)

    override fun putFile(path: String, localFile: File, contentType: String, ifMatch: String?): AppResult<Unit, DomainError> {
        if (!localFile.exists()) return AppResult.Error(DomainError.SyncError("Local file missing"))
        return put(path, localFile.asRequestBody(contentType.toMediaType()), ifMatch)
    }

    private fun put(path: String, body: okhttp3.RequestBody, ifMatch: String?): AppResult<Unit, DomainError> {
        val normalized = normalize(path)
        val existing = resolve(normalized, createFolders = false)
        val request = if (existing != null) {
            Request.Builder().url("$UPLOAD_URL/$existing?uploadType=media")
                .patch(body).apply { ifMatch?.let { header("If-Match", it) } }
        } else {
            val parentPath = normalized.substringBeforeLast('/', "/").ifEmpty { "/" }
            val parentId = resolve(parentPath, createFolders = true, leafIsFolder = true)
                ?: return AppResult.Error(DomainError.SyncError("Drive parent folder is unavailable"))
            val metadata = JSONObject()
                .put("name", normalized.substringAfterLast('/'))
                .put("parents", org.json.JSONArray().put(parentId))
            val multipart = MultipartBody.Builder("notable-${System.nanoTime()}")
                .setType("multipart/related".toMediaType())
                .addPart(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addPart(body)
                .build()
            Request.Builder().url("$UPLOAD_URL?uploadType=multipart&fields=id").post(multipart)
        }
        return request(request) { response ->
            val id = if (existing != null) existing else runCatching {
                JSONObject(response.body.string()).getString("id")
            }.getOrNull()
            if (id != null) ids[normalized] = id
            AppResult.Success(Unit)
        }
    }

    override fun getFile(path: String): AppResult<ByteArray, DomainError> {
        val id = resolve(path, false) ?: return AppResult.Error(DomainError.NotFound(path))
        return request(Request.Builder().url("$FILES_URL/$id?alt=media").get()) { response ->
            AppResult.Success(response.body.bytes())
        }
    }

    override fun getFileWithMetadata(path: String): AppResult<DownloadedFile, DomainError> {
        val id = resolve(path, false) ?: return AppResult.Error(DomainError.NotFound(path))
        return request(Request.Builder().url("$FILES_URL/$id?alt=media").get()) { response ->
            AppResult.Success(DownloadedFile(response.body.bytes(), response.header("ETag")))
        }
    }

    override fun getFile(path: String, localFile: File): AppResult<Unit, DomainError> = when (val result = getFile(path)) {
        is AppResult.Success -> runCatching {
            AtomicFileStore.write(localFile) { it.write(result.data) }
            AppResult.Success(Unit)
        }.getOrElse { AppResult.Error(DomainError.SyncError(it.message ?: "Drive download failed")) }
        is AppResult.Error -> result
    }

    override fun delete(path: String): AppResult<Unit, DomainError> {
        val normalized = normalize(path)
        val id = resolve(normalized, false) ?: return AppResult.Success(Unit)
        return request(Request.Builder().url("$FILES_URL/$id").delete()) {
            ids.remove(normalized)
            AppResult.Success(Unit)
        }
    }

    override fun listCollection(path: String): AppResult<List<String>, DomainError> =
        listChildren(path).let { result -> when (result) {
            is AppResult.Success -> AppResult.Success(result.data.map { it.first })
            is AppResult.Error -> result
        } }

    override fun listCollectionWithMetadata(path: String): AppResult<List<RemoteEntry>, DomainError> =
        listChildren(path).let { result -> when (result) {
            is AppResult.Success -> AppResult.Success(result.data.map { (name, modified) -> RemoteEntry(name, modified) })
            is AppResult.Error -> result
        } }

    private fun listChildren(path: String): AppResult<List<Pair<String, Date?>>, DomainError> {
        val parentId = resolve(path, false) ?: return AppResult.Success(emptyList())
        val q = urlEncode("'$parentId' in parents and trashed = false")
        return request(Request.Builder().url("$FILES_URL?q=$q&fields=files(id,name,modifiedTime,mimeType)&spaces=drive").get()) { response ->
            val array = JSONObject(response.body.string()).getJSONArray("files")
            val entries = buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val modified = item.optString("modifiedTime").takeIf(String::isNotBlank)
                        ?.let { runCatching { Date.from(Instant.parse(it)) }.getOrNull() }
                    add(item.getString("name") to modified)
                }
            }
            AppResult.Success(entries)
        }
    }

    override fun ensureParentDirectories(path: String): AppResult<Unit, DomainError> {
        val parent = normalize(path).substringBeforeLast('/', "/").ifEmpty { "/" }
        return createCollection(parent)
    }

    private fun resolve(path: String, createFolders: Boolean, leafIsFolder: Boolean = false): String? {
        val normalized = normalize(path)
        ids[normalized]?.let { return it }
        var parentId = folderId
        var currentPath = ""
        val segments = normalized.trim('/').split('/').filter(String::isNotBlank)
        for ((index, segment) in segments.withIndex()) {
            currentPath += "/$segment"
            val cached = ids[currentPath]
            if (cached != null) {
                parentId = cached
                continue
            }
            val isFolder = index < segments.lastIndex || leafIsFolder
            val found: String = findChild(parentId, segment, if (isFolder) FOLDER_MIME else null)
                ?: (if (createFolders && isFolder) createFolder(parentId, segment) else null)
                ?: return null
            ids[currentPath] = found
            parentId = found
        }
        return parentId
    }

    private fun findChild(parentId: String, name: String, mimeType: String?): String? {
        val escapedName = name.replace("'", "\\'")
        val mimeClause = mimeType?.let { " and mimeType = '$it'" }.orEmpty()
        val q = urlEncode("'$parentId' in parents and name = '$escapedName' and trashed = false$mimeClause")
        return request(Request.Builder().url("$FILES_URL?q=$q&fields=files(id)&pageSize=1&spaces=drive").get()) { response ->
            val files = JSONObject(response.body.string()).getJSONArray("files")
            AppResult.Success(if (files.length() == 0) null else files.getJSONObject(0).getString("id"))
        }.let { (it as? AppResult.Success)?.data }
    }

    private fun createFolder(parentId: String, name: String): String? {
        val body = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
            .put("parents", org.json.JSONArray().put(parentId)).toString()
            .toRequestBody("application/json".toMediaType())
        return request(Request.Builder().url("$FILES_URL?fields=id").post(body)) { response ->
            AppResult.Success(JSONObject(response.body.string()).getString("id"))
        }.let { (it as? AppResult.Success)?.data }
    }

    private fun <T> request(builder: Request.Builder, success: (okhttp3.Response) -> AppResult<T, DomainError>): AppResult<T, DomainError> {
        val token = tokenProvider() ?: return AppResult.Error(DomainError.SyncAuthError)
        return try {
            client.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { response ->
                when {
                    response.code == 401 -> AppResult.Error(DomainError.SyncAuthError)
                    response.code == 404 -> AppResult.Error(DomainError.NotFound(response.request.url.toString()))
                    response.code == 412 -> AppResult.Error(DomainError.SyncConflict)
                    !response.isSuccessful -> AppResult.Error(DomainError.SyncError("Drive request failed: ${response.code}"))
                    else -> success(response)
                }
            }
        } catch (error: Exception) {
            AppResult.Error(DomainError.NetworkError(error.message ?: "Drive request failed"))
        }
    }

    private fun normalize(path: String) = "/" + path.trim('/').replace(Regex("/{2,}"), "/")
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
