package com.ethran.notable.io

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.ui.geometry.Offset
import androidx.core.net.toUri
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.A4_HEIGHT
import com.ethran.notable.data.datastore.A4_WIDTH
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.ui.components.getFolderList
import com.ethran.notable.utils.ensureNotMainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect as FitzRect
import com.ethran.notable.data.db.LinkTargetType
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/* ---------------------------- Public API ---------------------------- */

private const val SAF_COPY_BACKUP_SUFFIX = ".notable-copy-backup"

enum class ExportFormat { PDF, PNG, JPEG, XOPP }

sealed class ExportTarget {
    data class Book(val bookId: String) : ExportTarget()
    data class Page(val pageId: String) : ExportTarget()
}

data class ExportOptions(
    val copyToClipboard: Boolean = true,
    val targetFolderUri: Uri? = null, // can be made to also get from it fileName.
    // true: replace an existing file of the same name; false: keep it and write
    // to a uniquely suffixed sibling ("name (1).ext").
    val overwrite: Boolean = false,
    val fileName: String? = null
)

/** Lets format writers finish their own wrappers without closing the file owned by the exporter. */
internal class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    override fun write(buffer: ByteArray) {
        out.write(buffer)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
    }

    override fun close() {
        flush()
    }
}

@Singleton
class ExportEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appRepository: AppRepository,
    private val pageRepo: PageRepository,
    private val bookRepo: BookRepository,
    private val pageContentRenderer: PageContentRenderer,
    private val appEventBus: AppEventBus,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope private val applicationScope: CoroutineScope
) {
    private val log = ShipBook.getLogger("ExportEngine")

    @Inject
    lateinit var xoppFile : XoppFile

    suspend fun export(
        target: ExportTarget, format: ExportFormat, options: ExportOptions = ExportOptions()
    ): String {
        // prepare file name and folder
        val (folderUri, baseFileName) = createFileNameAndFolder(target, format, options)
        // TODO: Retrieve all necessary data from the target, so that specific format exporter does not need to handle reading from db.
        //       For book it should be done page by page.
        return when (format) {
            ExportFormat.PDF -> exportAsPdf(target, folderUri, baseFileName, options)
            ExportFormat.PNG, ExportFormat.JPEG -> exportAsImages(
                target, folderUri, baseFileName, format, options
            )

            ExportFormat.XOPP -> exportAsXopp(target, folderUri, baseFileName, options)
        }
    }

    fun exportToLinkedFileAsync(bookId: String) {
        applicationScope.launch {
            val uriStr = try {
                bookRepo.getById(bookId)?.linkedExternalUri
            } catch (e: Exception) {
                appEventBus.emit(
                    AppEvent.LogMessage(
                        "exportToLinkedFileAsync",
                        "Error reading linked export path: ${e.message}"
                    )
                )
                return@launch
            }

            if (uriStr.isNullOrBlank()) return@launch

            try {
                log.i("Exporting book to linked file, uri: $uriStr")
                export(
                    target = ExportTarget.Book(bookId),
                    format = ExportFormat.XOPP,
                    options = ExportOptions(
                        copyToClipboard = false,
                        targetFolderUri = uriStr.toUri(),
                        overwrite = true
                    )
                )
                log.i("Linked export successful")
            } catch (e: Exception) {
                appEventBus.emit(
                    AppEvent.LogMessage(
                        "exportToLinkedFileAsync",
                        "Error exporting linked file: ${e.message}"
                    )
                )
            }
        }
    }


    /* -------------------- PDF EXPORT -------------------- */

    private suspend fun exportAsPdf(
        target: ExportTarget, folderUri: Uri, baseFileName: String, options: ExportOptions
    ): String {
        val writeAction: suspend (OutputStream) -> Unit
        when (target) {
            is ExportTarget.Book -> {
                val book = bookRepo.getById(target.bookId) ?: return "Book ID not found"
                writeAction = { out ->
                    val raw = ByteArrayOutputStream()
                    PdfDocument().use { doc ->
                        book.pageIds.forEachIndexed { index, pageId ->
                            writePageToPdfDocument(doc, pageId, pageNumber = index + 1)
                        }
                        doc.writeTo(raw)
                    }
                    out.write(addPdfLinkAnnotations(raw.toByteArray(), target))
                }
            }

            is ExportTarget.Page -> {
                writeAction = { out ->
                    val raw = ByteArrayOutputStream()
                    PdfDocument().use { doc ->
                        writePageToPdfDocument(doc, target.pageId, pageNumber = 1)
                        doc.writeTo(raw)
                    }
                    out.write(addPdfLinkAnnotations(raw.toByteArray(), target))
                }
                if (options.copyToClipboard) copyPagePngLink(
                    context, target.pageId
                ) // You may want a separate PDF variant
            }
        }

        return saveStream(
            folderUri = folderUri,
            fileName = baseFileName,
            extension = "pdf",
            mimeType = "application/pdf",
            writer = writeAction,
            overwrite = options.overwrite
        )
    }

    /* -------------------- IMAGE EXPORT (PNG / JPEG) -------------------- */

    private suspend fun exportAsImages(
        target: ExportTarget,
        folderUri: Uri,
        baseFileName: String,
        format: ExportFormat,
        options: ExportOptions
    ): String {
        val (ext, mime, compressFormat) = when (format) {
            ExportFormat.PNG -> Triple("png", "image/png", Bitmap.CompressFormat.PNG)
            ExportFormat.JPEG -> Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
            else -> error("Unsupported image format")
        }

        when (target) {
            is ExportTarget.Page -> {
                val pageId = target.pageId
                val bitmap = pageContentRenderer.renderPageBitmap(pageId, RenderTarget.Full)
                bitmap.useAndRecycle { bmp ->
                    val bytes = bmp.toBytes(compressFormat)
                    saveBytes(
                        folderUri, baseFileName,
                        ext, mime, options.overwrite, bytes
                    )
                }
                if (options.copyToClipboard && format == ExportFormat.PNG) {
                    copyPagePngLink(context, pageId)
                }
                return "Page exported: $baseFileName.$ext"
            }

            is ExportTarget.Book -> {
                val book = bookRepo.getById(target.bookId) ?: return "Book ID not found"
                // Export each page separately (same folder = book title)
                book.pageIds.forEachIndexed { index, pageId ->
                    val fileName = "$baseFileName-p${index + 1}"
                    val bitmap = pageContentRenderer.renderPageBitmap(pageId, RenderTarget.Full)
                    bitmap.useAndRecycle { bmp ->
                        val bytes = bmp.toBytes(compressFormat)
                        saveBytes(folderUri, fileName, ext, mime, options.overwrite, bytes)
                    }
                }
                if (options.copyToClipboard) {
                    log.w("Can't copy book links or images to clipboard -- batch export.")
                }
                return "Book exported: ${book.title} (${book.pageIds.size} pages)"
            }
        }
    }
    /* -------------------- XOPP export -------------------- */

    private suspend fun exportAsXopp(
        target: ExportTarget,
        folderUri: Uri,
        baseFileName: String,
        options: ExportOptions
    ): String {
        return saveStream(
            extension = "xopp",
            folderUri = folderUri,
            fileName = baseFileName,
            mimeType = "application/x-xopp",
            overwrite = options.overwrite
        ) { out ->
            xoppFile.writeToXoppStream(target, out)
        }
    }

    /* -------------------- File naming and folder path -------------------- */

    /**
     * Returns: Pair(folderUri, fileNameWithoutExtension)
     *
     * Rules:
     *  Book export:
     *      folder: Documents/notable/<folderHierarchy>/BookTitle
     *      file:   BookTitle
     *
     *  Page export (belongs to a book):
     *      folder: Documents/notable/<folderHierarchy>/BookTitle
     *      file:   BookTitle-p<PageNumber>   (falls back to BookTitle-p? if no number)
     *
     *  Page export (no book = quick page):
     *      folder: Documents/notable/<folderHierarchyFromPageParent?>
     *      file:   quickpage-<timestamp>
     *
     * - If options.saveToUri is provided, it must point to a directory (tree/document folder Uri or file:// directory).
     */
    suspend fun createFileNameAndFolder(
        target: ExportTarget, format: ExportFormat, options: ExportOptions
    ): Pair<Uri, String> {
        val fileName =
            sanitizeFileName(options.fileName?.trim()?.takeIf { it.isNotBlank() } ?: createFileName(
                target
            ))

        // If caller provided a directory Uri, accept both SAF directory and file:// directory.
        options.targetFolderUri?.let { provided ->
            if (!isDirectoryUri(provided) && !isFileDirectory(provided)) {
                throw IllegalArgumentException(
                    "ExportOptions.targetFolderUri must point to a directory (SAF tree/document folder or file:// directory). Maybe folder was deleted?"
                )
            }
            return provided to fileName
        }

        // Default export directory under Documents/notable/<subfolder>
        val subfolderPath = createSubfolderName(target, format)
        val folderUri = getDefaultExportDirectoryUri(subfolderPath)
        return folderUri to fileName
    }

    /**
     * Builds a subfolder path relative to the "notable" export root.
     *
     * Rules:
     * - Book (PDF/XOPP): folder hierarchy of the book.
     * - Book (PNG/JPEG): folder hierarchy + a folder for the book itself.
     * - Page (in a book): folder hierarchy + a folder for the book itself.
     * - Page (Quick Page): folder hierarchy of the page.
     *
     * @return A path without leading/trailing slashes, or an empty string.
     */
    suspend fun createSubfolderName(target: ExportTarget, format: ExportFormat): String {
        // Helper to build a full folder hierarchy path from a parent folder ID.
        suspend fun buildFolderPath(parentFolderId: String?): String {
            return parentFolderId?.let {
                // Fetches folder hierarchy and joins their sanitized titles with "/".
                getFolderList(appRepository, it)
                    .joinToString("/") { folder -> sanitizeFileName(folder.title) }
            }.orEmpty()
        }

        return when (target) {
            is ExportTarget.Book -> {
                val book = bookRepo.getById(target.bookId)
                    ?: run { log.e("Book ID not found"); return "" }

                val basePath = buildFolderPath(book.parentFolderId)
                val bookTitleFolder = sanitizeFileName(book.title)

                // For image formats, create an extra subfolder named after the book.
                if (format == ExportFormat.PNG || format == ExportFormat.JPEG) {
                    listOfNotNull(basePath.takeIf { it.isNotEmpty() }, bookTitleFolder)
                        .joinToString("/")
                } else {
                    basePath
                }
            }

            is ExportTarget.Page -> {
                val page = pageRepo.getById(target.pageId)
                    ?: run { log.e("Page ID not found"); return "" }

                // Check if the page belongs to a book.
                val book = page.notebookId?.let { bookRepo.getById(it) }

                if (book != null) {
                    // Page is inside a book: create path from the book's hierarchy + book title.
                    val basePath = buildFolderPath(book.parentFolderId)
                    val bookTitleFolder = sanitizeFileName(book.title)
                    listOfNotNull(basePath.takeIf { it.isNotEmpty() }, bookTitleFolder)
                        .joinToString("/")
                } else {
                    // This is a "Quick Page": use its own folder hierarchy.
                    buildFolderPath(page.parentFolderId)
                }
            }
        }
    }


    // Create a default directory Uri under Documents/notable/<subfolderPath> using file:// scheme.
    private fun getDefaultExportDirectoryUri(subfolderPath: String): Uri {
        val documentsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

        val targetPath = listOfNotBlank("notable", subfolderPath).joinToString(File.separator)
        val dir = File(documentsDir, targetPath)
        if (!dir.exists()) dir.mkdirs()
        return dir.toUri()
    }

    /**
     * Returns: fileNameWithoutExtension
     *
     * Book export: BookTitle
     * Page export in book: BookTitle-p<PageNumber> (or p?)
     * Quick page: quickpage-<timestamp>
     */
    suspend fun createFileName(target: ExportTarget): String {
        return when (target) {
            is ExportTarget.Book -> {
                val book =
                    bookRepo.getById(target.bookId) ?: run { log.e("Book ID not found"); return "" }
                sanitizeFileName(book.title)
            }

            is ExportTarget.Page -> {
                val page =
                    pageRepo.getById(target.pageId) ?: run { log.e("Page ID not found"); return "" }

                val book = page.notebookId?.let { bookRepo.getById(it) }

                if (book != null) {
                    // Page inside a book
                    val bookTitle = sanitizeFileName(book.title)
                    val pageNumber = getPageNumber(book.id, page.id).plus(1)
                    val pageToken = if (pageNumber >= 1) "p$pageNumber" else "p_"
                    "$bookTitle-$pageToken"
                } else {
                    val timeStamp = getReadableUtcTimestamp()
                    "quickpage-$timeStamp"
                }
            }
        }
    }

    /* -------------------- Shared Drawing & PDF Helpers -------------------- */

    private suspend fun writePageToPdfDocument(doc: PdfDocument, pageId: String, pageNumber: Int) {
        ensureNotMainThread("ExportPdf")
        val data = pageContentRenderer.loadPageContent(pageId) ?: return
        val (_, contentHeightPx) = pageContentRenderer.computeContentDimensions(data)

        val scaleFactor = A4_WIDTH.toFloat() / SCREEN_WIDTH.toFloat()
        val scaledHeight = (contentHeightPx * scaleFactor).toInt()

        if (GlobalAppSettings.current.paginatePdf) {
            var currentTop = 0
            var logicalPageNumber = pageNumber
            while (currentTop < scaledHeight) {
                val pageInfo =
                    PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, logicalPageNumber).create()
                val page = doc.startPage(pageInfo)
                pageContentRenderer.drawPage(
                    canvas = page.canvas,
                    data = data,
                    scroll = Offset(0f, currentTop.toFloat()),
                    scaleFactor = scaleFactor,
                )
                doc.finishPage(page)
                currentTop += A4_HEIGHT
                logicalPageNumber++
            }
        } else {
            val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, scaledHeight, pageNumber).create()
            val page = doc.startPage(pageInfo)
            pageContentRenderer.drawPage(
                canvas = page.canvas,
                data = data,
                scroll = Offset.Zero,
                scaleFactor = scaleFactor,
            )
            doc.finishPage(page)
        }
    }

    @Suppress("CyclomaticComplexMethod", "AvoidVarsExceptWithDelegate")
    private suspend fun addPdfLinkAnnotations(pdfBytes: ByteArray, target: ExportTarget): ByteArray =
        withContext(Dispatchers.IO) {
            val pageIds = when (target) {
                is ExportTarget.Page -> listOf(target.pageId)
                is ExportTarget.Book -> bookRepo.getById(target.bookId)?.pageIds.orEmpty()
            }
            if (pageIds.isEmpty()) return@withContext pdfBytes

            data class ExportedPage(val pageId: String, val pdfIndex: Int, val topPx: Float)
            val exportedPages = mutableListOf<ExportedPage>()
            val firstPdfIndex = mutableMapOf<String, Int>()
            var pdfIndex = 0
            for (pageId in pageIds) {
                val data = pageContentRenderer.loadPageContent(pageId) ?: continue
                firstPdfIndex[pageId] = pdfIndex
                val (_, height) = pageContentRenderer.computeContentDimensions(data)
                val scaledHeight = height * (A4_WIDTH.toFloat() / SCREEN_WIDTH.toFloat())
                val count = if (GlobalAppSettings.current.paginatePdf) {
                    kotlin.math.ceil(scaledHeight / A4_HEIGHT).toInt().coerceAtLeast(1)
                } else 1
                repeat(count) { segment ->
                    val topPx = if (GlobalAppSettings.current.paginatePdf) {
                        segment * A4_HEIGHT / (A4_WIDTH.toFloat() / SCREEN_WIDTH.toFloat())
                    } else 0f
                    exportedPages += ExportedPage(pageId, pdfIndex++, topPx)
                }
            }

            val document = Document.openDocument(pdfBytes, "application/pdf").asPDF()
            try {
                val scale = A4_WIDTH.toFloat() / SCREEN_WIDTH.toFloat()
                for (source in exportedPages) {
                    val data = pageContentRenderer.loadPageContent(source.pageId) ?: continue
                    val pdfPage = document.loadPage(source.pdfIndex) as? PDFPage ?: continue
                    val segmentBottom = source.topPx + if (GlobalAppSettings.current.paginatePdf) A4_HEIGHT / scale else Float.MAX_VALUE
                    data.links.filter { it.y + it.height >= source.topPx && it.y <= segmentBottom }.forEach { link ->
                        val bounds = FitzRect(
                            link.x * scale,
                            (link.y - source.topPx) * scale,
                            (link.x + link.width) * scale,
                            (link.y + link.height - source.topPx) * scale,
                        )
                        val internalDestination = when (link.targetType) {
                            LinkTargetType.PAGE -> firstPdfIndex[link.target]
                            LinkTargetType.NOTEBOOK -> null
                            else -> null
                        }
                        if (internalDestination != null) {
                            pdfPage.createLinkFit(bounds, internalDestination)
                        } else {
                            val uri = when (link.targetType) {
                                LinkTargetType.PAGE -> "notable://page-${link.target}"
                                LinkTargetType.NOTEBOOK -> "notable://book-${link.target}"
                                LinkTargetType.URL -> link.target
                                LinkTargetType.PDF_ATTACHMENT -> "notable://attachment-${link.target}"
                            }
                            pdfPage.createLink(bounds, uri)
                        }
                    }
                    pdfPage.update()
                    pdfPage.destroy()
                }
                val output = File(context.cacheDir, ".notable-annotated-${java.util.UUID.randomUUID()}.pdf")
                try {
                    document.save(output.absolutePath, "garbage=collect,compress")
                    output.readBytes()
                } finally {
                    output.delete()
                }
            } catch (error: Exception) {
                log.w("Could not add PDF link annotations: ${error.message}")
                pdfBytes
            } finally {
                document.destroy()
            }
        }


    /* -------------------- Saving Helpers -------------------- */

    /**
     * A convenience wrapper around [saveInternal] to save a raw [ByteArray] to a file.
     *
     * @param folderUri The URI of the directory where the file will be saved.
     *                  Can be a `file://` URI or a Storage Access Framework (SAF) tree/document URI.
     * @param fileName The name of the file, without the extension.
     * @param extension The file extension (e.g., "png", "jpg").
     * @param mimeType The MIME type of the file (e.g., "image/png").
     * @param overwrite If `true`, any existing file with the same name will be replaced.
     * @param bytes The raw byte data to write to the file.
     * @return A [String] indicating the result of the save operation, typically a success or error message.
     */
    private suspend fun saveBytes(
        folderUri: Uri,
        fileName: String,
        extension: String,
        mimeType: String,
        overwrite: Boolean,
        bytes: ByteArray
    ): String = saveInternal(
        folderUri = folderUri,
        fileName = fileName,
        extension = extension,
        mimeType = mimeType,
        overwrite = overwrite
    ) { out -> out.write(bytes) }

    /**
     * A convenience wrapper around [saveInternal] that accepts a suspendable [writer] lambda
     * to write content to an [OutputStream].
     *
     * @param folderUri The URI of the directory where the file will be saved.
     *                  Can be a `file://` URI or a Storage Access Framework (SAF) tree/document URI.
     * @param fileName The base name of the file, without the extension.
     * @param extension The file extension (e.g., "pdf", "png").
     * @param mimeType The MIME type of the file (e.g., "application/pdf").
     * @param overwrite If `true`, any existing file with the same name will be replaced.
     * @param writer A suspendable lambda that receives an [OutputStream] to write the file content into.
     * @return A user-facing message indicating the result of the save operation (e.g., "Saved file.pdf" or "Error saving...").
     */
    private suspend fun saveStream(
        folderUri: Uri,
        fileName: String,
        extension: String,
        mimeType: String,
        overwrite: Boolean,
        writer: suspend (OutputStream) -> Unit
    ): String = saveInternal(
        folderUri = folderUri,
        fileName = fileName,
        extension = extension,
        mimeType = mimeType,
        overwrite = overwrite,
        writer = writer
    )

    /**
     * Central writer that handles directory types:
     * - SAF directory Uris (tree/document) via DocumentsContract
     * - file:// directory Uris via java.io.File
     */
    private suspend fun saveInternal(
        folderUri: Uri,
        fileName: String,
        extension: String,
        mimeType: String,
        overwrite: Boolean,
        writer: suspend (OutputStream) -> Unit
    ): String = withContext(ioDispatcher) {
        val displayName = if (extension.isBlank()) fileName else "$fileName.$extension"
        var prepared: File? = null
        try {
            prepared = prepareExportFile(displayName, writer)
            when (folderUri.scheme) {
                ContentResolver.SCHEME_CONTENT -> {
                    writeSafExportAtomically(folderUri, displayName, mimeType, overwrite, prepared)
                }

                "file" -> {
                    val parent = File(requireNotNull(folderUri.path) { "Missing directory path" })
                    AtomicFileStore.ensureDirectory(parent)
                    AtomicFileStore.recoverStaleFiles(parent)
                    val requested = File(parent, displayName)
                    val target = if (!overwrite && requested.exists()) {
                        uniqueSibling(parent, displayName)
                    } else requested
                    AtomicFileStore.write(target) { output ->
                        prepared.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }

                else -> throw IOException("Unsupported Uri scheme: ${folderUri.scheme}")
            }

            "Saved $displayName"
        } catch (e: OutOfMemoryError) {
            log.e("Save error (OOM): ${e.message}")
            "Not enough memory to save $displayName"
        } catch (e: Exception) {
            log.e("Save error: ${e.message}")
            "Error saving $displayName"
        } finally {
            prepared?.let { file ->
                if (file.exists() && !file.delete()) {
                    log.w("Could not delete prepared export file: ${file.absolutePath}")
                }
            }
        }
    }

    private suspend fun prepareExportFile(
        displayName: String,
        writer: suspend (OutputStream) -> Unit,
    ): File {
        val directory = File(context.cacheDir, "prepared-exports")
        AtomicFileStore.ensureDirectory(directory)
        AtomicFileStore.recoverStaleFiles(directory)
        val prepared = File(
            directory,
            ".${sanitizeFileName(displayName)}.notable-tmp-${UUID.randomUUID()}",
        )
        try {
            FileOutputStream(prepared).use { output ->
                writer(NonClosingOutputStream(output))
                output.flush()
                output.fd.sync()
            }
            if (!prepared.isFile || prepared.length() == 0L) {
                throw IOException("Export produced an empty file")
            }
            return prepared
        } catch (e: Exception) {
            if (prepared.exists()) prepared.delete()
            throw e
        }
    }

    private data class SafDirectory(
        val parentUri: Uri,
        val childrenUri: Uri,
        val childUri: (String) -> Uri,
    )

    @Synchronized
    private fun writeSafExportAtomically(
        directoryUri: Uri,
        requestedName: String,
        mimeType: String,
        overwrite: Boolean,
        prepared: File,
    ) {
        val resolver = context.contentResolver
        val directory = resolveSafDirectory(directoryUri)
        recoverStaleSafDocuments(directory)
        var children = listSafChildren(directory)
        val finalName = if (!overwrite && requestedName in children) {
            uniqueSafName(requestedName, children.keys)
        } else requestedName
        val temporaryName = ".$finalName.notable-tmp-${UUID.randomUUID()}"
        val temporaryUri = DocumentsContract.createDocument(
            resolver, directory.parentUri, mimeType, temporaryName
        ) ?: throw IOException("The document provider could not create a temporary export")

        var backupUri: Uri? = null
        try {
            resolver.openFileDescriptor(temporaryUri, "w")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    prepared.inputStream().use { it.copyTo(output) }
                    output.flush()
                    output.fd.sync()
                }
            } ?: throw IOException("The document provider could not open the temporary export")

            children = listSafChildren(directory)
            val existing = children[finalName]?.takeIf { overwrite }
            val canRenameAtomically = supportsSafRename(temporaryUri) &&
                    (existing == null || supportsSafRename(existing))
            if (!canRenameAtomically) {
                writeSafWithoutRename(directory, finalName, mimeType, prepared, existing)
                deleteSafDocumentBestEffort(temporaryUri)
                return
            }

            existing?.let {
                val backupName = "$finalName.notable-backup"
                children[backupName]?.let { stale ->
                    if (!DocumentsContract.deleteDocument(resolver, stale)) {
                        throw IOException("Could not remove stale provider backup")
                    }
                }
                backupUri = tryRenameSafDocument(it, backupName)
                if (backupUri == null) {
                    writeSafWithoutRename(directory, finalName, mimeType, prepared, it)
                    deleteSafDocumentBestEffort(temporaryUri)
                    return
                }
            }

            if (tryRenameSafDocument(temporaryUri, finalName) == null) {
                // The provider advertised rename support but did not perform it. Preserve the
                // staged backup until a direct final-name write has completed successfully.
                writeSafWithoutRename(directory, finalName, mimeType, prepared, existing = null)
                deleteSafDocumentBestEffort(temporaryUri)
            }
            backupUri?.let {
                if (!DocumentsContract.deleteDocument(resolver, it)) {
                    throw IOException("Could not remove the completed export backup")
                }
                backupUri = null
            }
        } catch (e: Exception) {
            deleteSafDocumentBestEffort(temporaryUri)
            backupUri?.let { backup ->
                if (finalName !in listSafChildren(directory)) {
                    tryRenameSafDocument(backup, finalName)
                }
            }
            throw e
        }
    }

    private fun supportsSafRename(documentUri: Uri): Boolean = runCatching {
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val flags = cursor.getLong(0)
            flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() != 0L
        } ?: false
    }.getOrDefault(false)

    private fun tryRenameSafDocument(documentUri: Uri, displayName: String): Uri? =
        runCatching {
            DocumentsContract.renameDocument(context.contentResolver, documentUri, displayName)
        }.onFailure {
            log.w("The document provider could not rename $documentUri: ${it.message}")
        }.getOrNull()

    /** Compatibility path for providers that support create/write but not rename. */
    private fun writeSafWithoutRename(
        directory: SafDirectory,
        displayName: String,
        mimeType: String,
        prepared: File,
        existing: Uri?,
    ) {
        val resolver = context.contentResolver
        val created = existing == null
        val destination = existing ?: DocumentsContract.createDocument(
            resolver,
            directory.parentUri,
            mimeType,
            displayName,
        ) ?: throw IOException("The document provider could not create the final export")
        val rollbackDocument = existing?.let {
            createSafCopyBackup(directory, displayName, mimeType, it)
        }

        try {
            writeFileToSafDocument(prepared, destination)
            rollbackDocument?.let { backup ->
                if (!DocumentsContract.deleteDocument(resolver, backup)) {
                    throw IOException("Could not remove the completed export rollback copy")
                }
            }
        } catch (e: Exception) {
            if (rollbackDocument != null) {
                runCatching { copySafDocument(rollbackDocument, destination) }
                    .onSuccess { deleteSafDocumentBestEffort(rollbackDocument) }
                    .onFailure { restoreError -> e.addSuppressed(restoreError) }
            } else if (created) {
                deleteSafDocumentBestEffort(destination)
            }
            throw e
        }
    }

    /**
     * Providers without rename support require an in-place overwrite. Preserve the complete
     * previous document in the provider before opening it with "wt", which truncates immediately.
     * A surviving copy is treated as an interrupted transaction and restored on the next export.
     */
    private fun createSafCopyBackup(
        directory: SafDirectory,
        displayName: String,
        mimeType: String,
        documentUri: Uri,
    ): Uri {
        val resolver = context.contentResolver
        val backupName = "$displayName$SAF_COPY_BACKUP_SUFFIX"
        listSafChildren(directory)[backupName]?.let { stale ->
            if (!DocumentsContract.deleteDocument(resolver, stale)) {
                throw IOException("Could not remove stale provider rollback copy")
            }
        }
        val backup = DocumentsContract.createDocument(
            resolver,
            directory.parentUri,
            mimeType,
            backupName,
        ) ?: throw IOException("The document provider could not create an export rollback copy")

        try {
            copySafDocument(documentUri, backup)
            return backup
        } catch (e: Exception) {
            deleteSafDocumentBestEffort(backup)
            throw e
        }
    }

    private fun copySafDocument(source: Uri, destination: Uri) {
        context.contentResolver.openInputStream(source)?.use { input ->
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                input.copyTo(output)
                output.flush()
            } ?: throw IOException("The document provider could not write an export rollback copy")
        } ?: throw IOException("The document provider could not read an export rollback copy")
    }

    private fun writeFileToSafDocument(source: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
            source.inputStream().use { it.copyTo(output) }
            output.flush()
        } ?: throw IOException("The document provider could not write the final export")
    }

    private fun deleteSafDocumentBestEffort(documentUri: Uri) {
        runCatching {
            if (!DocumentsContract.deleteDocument(context.contentResolver, documentUri)) {
                log.w("The document provider did not delete temporary document $documentUri")
            }
        }.onFailure {
            log.w("The document provider could not delete $documentUri: ${it.message}")
        }
    }

    private fun resolveSafDirectory(uri: Uri): SafDirectory = when {
        DocumentsContract.isTreeUri(uri) -> {
            val id = DocumentsContract.getTreeDocumentId(uri)
            SafDirectory(
                DocumentsContract.buildDocumentUriUsingTree(uri, id),
                DocumentsContract.buildChildDocumentsUriUsingTree(uri, id),
                { DocumentsContract.buildDocumentUriUsingTree(uri, it) },
            )
        }
        DocumentsContract.isDocumentUri(context, uri) -> {
            val id = DocumentsContract.getDocumentId(uri)
            SafDirectory(
                uri,
                DocumentsContract.buildChildDocumentsUriUsingTree(uri, id),
                { DocumentsContract.buildDocumentUriUsingTree(uri, it) },
            )
        }
        else -> throw IOException("Not a Storage Access Framework directory: $uri")
    }

    private fun listSafChildren(directory: SafDirectory): Map<String, Uri> {
        val result = linkedMapOf<String, Uri>()
        context.contentResolver.query(
            directory.childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )
            val nameIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            while (cursor.moveToNext()) {
                result[cursor.getString(nameIndex)] = directory.childUri(cursor.getString(idIndex))
            }
        } ?: throw IOException("The document provider did not list the export directory")
        return result
    }

    private fun recoverStaleSafDocuments(directory: SafDirectory) {
        val resolver = context.contentResolver
        var children = listSafChildren(directory)
        children.filterKeys { it.contains(".notable-tmp-") }.values.forEach { uri ->
            if (!DocumentsContract.deleteDocument(resolver, uri)) {
                throw IOException("Could not remove a stale provider temporary document")
            }
        }
        children = listSafChildren(directory)
        // Some providers append an extension based on MIME type, so identify rollback copies by
        // marker rather than requiring it to be the final display-name suffix.
        children.filterKeys { it.contains(SAF_COPY_BACKUP_SUFFIX) }
            .forEach { (name, backup) ->
                val originalName = name.substringBefore(SAF_COPY_BACKUP_SUFFIX)
                val original = children[originalName] ?: DocumentsContract.createDocument(
                    resolver,
                    directory.parentUri,
                    resolver.getType(backup) ?: "application/octet-stream",
                    originalName,
                ) ?: throw IOException("Could not recreate an interrupted provider export")
                copySafDocument(backup, original)
                if (!DocumentsContract.deleteDocument(resolver, backup)) {
                    throw IOException("Could not remove a restored provider rollback copy")
                }
            }
        children = listSafChildren(directory)
        children.filterKeys { it.endsWith(".notable-backup") }.forEach { (name, backup) ->
            val originalName = name.removeSuffix(".notable-backup")
            if (originalName in children) {
                if (!DocumentsContract.deleteDocument(resolver, backup)) {
                    throw IOException("Could not remove a stale provider backup")
                }
            } else if (DocumentsContract.renameDocument(resolver, backup, originalName) == null) {
                throw IOException("Could not restore a stale provider backup")
            }
        }
    }

    private fun uniqueSafName(displayName: String, existingNames: Set<String>): String {
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val extension = if (dot > 0) displayName.substring(dot) else ""
        return generateSequence(1) { it + 1 }
            .map { counter -> "$base ($counter)$extension" }
            .first { it !in existingNames }
    }


    /* -------------------- Clipboard Helpers -------------------- */

    private fun copyPagePngLink(context: Context, pageId: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = """
            [[../attachments/Notable/Pages/notable-page-$pageId.png]]
            [[Notable Link][notable://page-$pageId]]
        """.trimIndent()
        clipboard.setPrimaryClip(ClipData.newPlainText("Notable Page Link", text))
    }

    private fun copyBookPdfLink(context: Context, bookId: String, bookName: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = """
            [[../attachments/Notable/Notebooks/$bookName.pdf]]
            [[Notable Book Link][notable://book-$bookId]]
        """.trimIndent()
        clipboard.setPrimaryClip(ClipData.newPlainText("Notable Book PDF Link", text))
    }

    /* -------------------- Utilities -------------------- */

    /**
     * Gets the current time in UTC and formats it into a human-readable, filename-safe string.
     * Example output: "2025-10-11_21-48"
     */
    fun getReadableUtcTimestamp(): String {
        val currentUtcTime = ZonedDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")
        return currentUtcTime.format(formatter)
    }

    // Accepts SAF tree/document directory Uris OR file:// directory Uris
    private fun isDirectoryUri(uri: Uri): Boolean {
        // SAF tree directory
        if (android.provider.DocumentsContract.isTreeUri(uri)) return true

        // SAF document directory
        if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
            val resolver = context.contentResolver
            resolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val mime = c.getString(0)
                    if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                        return true
                    }
                }
            }
        }
        // file:// directory
        return isFileDirectory(uri)
    }

    private fun isFileDirectory(uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return File(path).isDirectory
    }

    private fun Bitmap.toBytes(format: Bitmap.CompressFormat, quality: Int = 100): ByteArray {
        val bos = ByteArrayOutputStream()
        this.compress(format, quality, bos)
        return bos.toByteArray()
    }

    private inline fun Bitmap.useAndRecycle(block: (Bitmap) -> Unit) {
        try {
            block(this)
        } finally {
            try {
                recycle()
            } catch (_: Exception) {
            }
        }
    }

    // Simple PdfDocument.use extension
    private inline fun PdfDocument.use(block: (PdfDocument) -> Unit) {
        try {
            block(this)
        } finally {
            try {
                close()
            } catch (_: Exception) {
            }
        }
    }

    private fun listOfNotBlank(vararg parts: String): List<String> =
        parts.filter { it.isNotBlank() }

    // First non-existing "name (n).ext" next to the taken displayName.
    private fun uniqueSibling(parent: File, displayName: String): File {
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var counter = 1
        var candidate = File(parent, "$base ($counter)$ext")
        while (candidate.exists()) {
            counter++
            candidate = File(parent, "$base ($counter)$ext")
        }
        return candidate
    }

    // Retrieves the 0-based page number of a specific page within a book.
    suspend fun getPageNumber(bookId: String, id: String): Int {
        return appRepository.getPageNumber(bookId, id)

    }

}
