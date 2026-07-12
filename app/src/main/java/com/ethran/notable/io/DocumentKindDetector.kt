package com.ethran.notable.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

enum class DocumentKind {
    PDF,
    XOPP,
    UNKNOWN,
}

data class DocumentClassification(
    val kind: DocumentKind,
    val displayName: String,
    val mimeType: String?,
    val error: String? = null,
) {
    val isSupported: Boolean get() = kind != DocumentKind.UNKNOWN
}

/**
 * Central, signature-first classifier for every notebook import boundary.
 *
 * MIME type and display-name extension are retained for diagnostics, but they never override
 * readable bytes. This prevents a renamed image or a lying content provider from entering a PDF
 * or XOPP parser merely because its metadata claims a supported type.
 */
object DocumentKindDetector {
    private const val RAW_PREFIX_LIMIT = 8 * 1024
    private const val XML_PREFIX_LIMIT = 16 * 1024
    private val pdfMagic = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val xournalRoot = Regex("<xournal(?:\\s|>)", RegexOption.IGNORE_CASE)

    fun detect(context: Context, uri: Uri): DocumentClassification {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "Imported document"
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()

        val rawPrefix = try {
            resolver.openInputStream(uri)?.use { it.readPrefix(RAW_PREFIX_LIMIT) }
                ?: return unsupported(displayName, mimeType, "The selected file cannot be opened.")
        } catch (e: Exception) {
            return unsupported(
                displayName,
                mimeType,
                "The selected file cannot be read: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        if (rawPrefix.isEmpty()) {
            return unsupported(displayName, mimeType, "The selected file is empty.")
        }

        val expandedGzipPrefix = if (rawPrefix.hasGzipMagic()) {
            try {
                resolver.openInputStream(uri)?.use { input ->
                    GzipCompressorInputStream(BufferedInputStream(input)).use {
                        it.readPrefix(XML_PREFIX_LIMIT)
                    }
                }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        return classify(rawPrefix, expandedGzipPrefix, mimeType, displayName)
    }

    fun detectFile(file: File, mimeType: String? = null): DocumentClassification {
        val displayName = file.name.ifBlank { "Imported document" }
        if (!file.isFile) {
            return unsupported(displayName, mimeType, "The selected path is not a readable file.")
        }
        val rawPrefix = try {
            file.inputStream().use { it.readPrefix(RAW_PREFIX_LIMIT) }
        } catch (e: Exception) {
            return unsupported(displayName, mimeType, "The selected file cannot be read: ${e.message}")
        }
        val expandedGzipPrefix = if (rawPrefix.hasGzipMagic()) {
            try {
                GzipCompressorInputStream(BufferedInputStream(file.inputStream())).use {
                    it.readPrefix(XML_PREFIX_LIMIT)
                }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return classify(rawPrefix, expandedGzipPrefix, mimeType, displayName)
    }

    internal fun classify(
        rawPrefix: ByteArray,
        expandedGzipPrefix: ByteArray?,
        mimeType: String?,
        displayName: String,
    ): DocumentClassification {
        if (rawPrefix.indexOf(pdfMagic, searchLimit = 1024) >= 0) {
            return DocumentClassification(DocumentKind.PDF, displayName, mimeType)
        }

        if (rawPrefix.hasGzipMagic() && expandedGzipPrefix != null) {
            val xmlPrefix = expandedGzipPrefix.toString(Charsets.UTF_8)
            if (xournalRoot.containsMatchIn(xmlPrefix)) {
                return DocumentClassification(DocumentKind.XOPP, displayName, mimeType)
            }
        }

        val claimedKind = kindFromMetadata(mimeType, displayName)
        val claim = when (claimedKind) {
            DocumentKind.PDF -> "The file claims to be a PDF, but its PDF signature is missing."
            DocumentKind.XOPP -> "The file claims to be an XOPP notebook, but its XOPP signature is invalid."
            DocumentKind.UNKNOWN -> "The selected file is not a supported PDF or XOPP document."
        }
        return unsupported(displayName, mimeType, claim)
    }

    internal fun kindFromMetadata(mimeType: String?, displayName: String): DocumentKind {
        val normalizedMime = mimeType?.lowercase(Locale.US)
        val normalizedName = displayName.lowercase(Locale.US)
        return when {
            normalizedMime == "application/pdf" || normalizedName.endsWith(".pdf") -> DocumentKind.PDF
            normalizedMime in setOf(
                "application/x-xopp",
                "application/gzip",
                "application/x-gzip",
            ) || normalizedName.endsWith(".xopp") -> DocumentKind.XOPP
            else -> DocumentKind.UNKNOWN
        }
    }

    private fun unsupported(name: String, mime: String?, message: String) =
        DocumentClassification(DocumentKind.UNKNOWN, name, mime, message)

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun ByteArray.hasGzipMagic(): Boolean =
        size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()

    private fun ByteArray.indexOf(needle: ByteArray, searchLimit: Int): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        val lastStart = minOf(size - needle.size, searchLimit - needle.size)
        for (start in 0..lastStart) {
            if (needle.indices.all { offset -> this[start + offset] == needle[offset] }) {
                return start
            }
        }
        return -1
    }

    private fun InputStream.readPrefix(limit: Int): ByteArray {
        if (limit <= 0) throw IOException("Prefix limit must be positive")
        val result = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = read(result, count, limit - count)
            if (read < 0) break
            if (read == 0) continue
            count += read
        }
        return result.copyOf(count)
    }
}
