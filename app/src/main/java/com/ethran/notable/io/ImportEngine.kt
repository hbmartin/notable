package com.ethran.notable.io

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.plus
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import javax.inject.Inject


/**
 * Configuration options for an import operation.
 * @param saveToBookId If specified, the imported pages are appended to the existing book with this ID. If null, a new book is created.
 * @param folderId If specified, the new notebook will be created in this folder.
 * @param linkToExternalFile If true, the app will link to the original file URI instead of copying it. This is applicable for file types like PDF.
 * @param fileType If specified, the app will only import files of this type.
 * @param bookTitle If specified, the filename will be overwritten to this value.
 */
data class ImportOptions(
    val saveToBookId: String? = null,
    val folderId: String? = null,
    val linkToExternalFile: Boolean = false,
    val fileType: String? = null,
    val bookTitle: String? = null
)


/**
 * The engine responsible for handling the logic of importing files into the app.
 * It is agnostic of the UI and operates on URIs provided to it.
 */
class ImportEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageRepo: PageRepository,
    private val bookRepo: BookRepository,
    private val strokeRepo: StrokeRepository,
    private val imageRepo: ImageRepository,
    private val appEventBus: AppEventBus
) {
    private val log = ShipBook.getLogger("ImportEngine")

    @Inject
    lateinit var xoppFile: XoppFile

    /**
     * Imports a notebook from the given URI. It recognizes the file type and
     * executes the correct import function.
     *
     * @param uri The URI of the file to import.
     * @param options The options for the import process.
     * @return An [String] indicating success or failure.
     */
    @WorkerThread
    suspend fun import(
        uri: Uri,
        options: ImportOptions = ImportOptions()
    ): AppResult<List<String>, DomainError> {
        val mimeType = context.contentResolver.getType(uri)
        if (options.fileType != null && mimeType != options.fileType)
            return AppResult.Error(DomainError.UnexpectedState("File type mismatch. Expected: ${options.fileType}, Actual: $mimeType"))

        val bookTitle = sanitizeNotebookName(options.bookTitle ?: getFileName(uri))
        log.d("Starting import for uri: $uri, mimeType: $mimeType, fileName: $bookTitle")

        // strip extension if present in bookTitle (from options or getFileName)
        val finalTitle = if (bookTitle.endsWith(".xopp", ignoreCase = true)) {
            bookTitle.removeSuffix(".xopp")
        } else if (bookTitle.endsWith(".pdf", ignoreCase = true)) {
            bookTitle.removeSuffix(".pdf")
        } else {
            bookTitle
        }

        val optionsWithTitle = options.copy(
            bookTitle = finalTitle,
        )

        return when {
            XoppFile.isXoppFile(mimeType, bookTitle) -> handleImportXopp(uri, optionsWithTitle)
            isPdfFile(mimeType, bookTitle) -> handleImportPDF(uri, optionsWithTitle)
            else -> {
                val errorMessage = "Unsupported file type: $mimeType"
                log.w(errorMessage)
                AppResult.Error(DomainError.UnexpectedState(errorMessage))
            }
        }
    }

    /**
     * Returns the book the imported pages should land in: the existing book named by
     * [ImportOptions.saveToBookId], or a freshly created one built by [createBook].
     */
    private suspend fun resolveTargetBook(
        options: ImportOptions,
        createBook: () -> Notebook
    ): AppResult<Notebook, DomainError> {
        val existingId = options.saveToBookId
            ?: return AppResult.Success(createBook().also { bookRepo.createEmpty(it) })
        val existing = bookRepo.getById(existingId)
            ?: return AppResult.Error(
                DomainError.UnexpectedState("Cannot import into book $existingId: it does not exist.")
            )
        return AppResult.Success(existing)
    }

    private suspend fun handleImportXopp(
        uri: Uri,
        options: ImportOptions
    ): AppResult<List<String>, DomainError> {
        log.d("Importing Xopp file...")
        require(options.bookTitle != null) { "bookTitle cannot be null when importing Xopp file" }
        val book = when (val target = resolveTargetBook(options) {
            Notebook(
                title = options.bookTitle,
                parentFolderId = options.folderId,
                defaultBackground = "blank",
                defaultBackgroundType = BackgroundType.Native.key
            )
        }) {
            is AppResult.Success -> target.data
            is AppResult.Error -> return AppResult.Error(target.error)
        }

        val importedPageIds = mutableListOf<String>()
        var persistentError: DomainError? = null

        try {
            xoppFile.importBook(
                uri = uri,
                onPageCreated = { page ->
                    try {
                        // TODO: Handle conflicts with existing pages.
                        pageRepo.create(page.copy(notebookId = book.id))
                        bookRepo.addPage(book.id, page.id)
                        importedPageIds.add(page.id)
                    } catch (e: Exception) {
                        val errMessage = "Failed to import page ${page.id}: ${e.message}"
                        appEventBus.emit(AppEvent.LogMessage("importBook", errMessage))
                        val error = DomainError.DatabaseError(errMessage)
                        persistentError = persistentError?.let { it + error } ?: error
                    }
                },
                onStrokeBatch = { strokes ->
                    try {
                        strokeRepo.create(strokes)
                    } catch (e: Exception) {
                        val errMessage = "Failed to import stroke batch: ${e.message}"
                        appEventBus.emit(AppEvent.LogMessage("importBook", errMessage))
                        val error = DomainError.DatabaseError(errMessage)
                        persistentError = persistentError?.let { it + error } ?: error
                    }
                },
                onPageFinalized = { _, images ->
                    try {
                        imageRepo.create(images)
                    } catch (e: Exception) {
                        val errMessage = "Failed to import page images: ${e.message}"
                        appEventBus.emit(AppEvent.LogMessage("importBook", errMessage))
                        val error = DomainError.DatabaseError(errMessage)
                        persistentError = persistentError?.let { it + error } ?: error
                    }
                }
            )
        } catch (e: Exception) {
            val error = DomainError.UnexpectedState(
                "Could not read XOPP file: ${e.message ?: e.javaClass.simpleName}"
            )
            persistentError = persistentError?.let { it + error } ?: error
        }

        return persistentError?.let { AppResult.Error(it) } ?: AppResult.Success(importedPageIds)
    }

    private suspend fun handleImportPDF(
        uri: Uri,
        options: ImportOptions
    ): AppResult<List<String>, DomainError> {
        log.d("Importing Pdf file...")
        require(options.bookTitle != null) { "bookTitle cannot be null when importing Pdf file" }

        val fileToSave = handleFileSaving(context, uri, options)
            ?: return AppResult.Error(DomainError.UnexpectedState("Couldn't determine file path. Does the app have permission to read external storage?"))

        val filePath = fileToSave.toString()

        val book = when (val target = resolveTargetBook(options) {
            Notebook(
                title = options.bookTitle,
                parentFolderId = options.folderId,
                defaultBackground = filePath,
                defaultBackgroundType = BackgroundType.AutoPdf.key
            )
        }) {
            is AppResult.Success -> target.data
            is AppResult.Error -> return AppResult.Error(target.error)
        }

        val importedPageIds = mutableListOf<String>()
        var persistentError: DomainError? = null

        importPdf(fileToSave, options) { pageData ->
            try {
                pageRepo.create(pageData.page.copy(notebookId = book.id))
                if (pageData.strokes.isNotEmpty())
                    strokeRepo.create(pageData.strokes)
                if (pageData.images.isNotEmpty())
                    imageRepo.create(pageData.images)
                bookRepo.addPage(book.id, pageData.page.id)
                importedPageIds.add(pageData.page.id)
            } catch (e: Exception) {
                val errMessage = "failed import book  ${e.message}"
                appEventBus.emit(AppEvent.LogMessage("importBook", errMessage))
                val error = DomainError.DatabaseError(errMessage)
                persistentError = persistentError?.let { it + error } ?: error
            }
        }

        return persistentError?.let { AppResult.Error(it) } ?: AppResult.Success(importedPageIds)
    }


    /**
     * Extracts the book title from a file URI.
     */
    private fun getFileName(uri: Uri): String {
        val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "Imported Book"
        return if (fileName.endsWith(".xopp", ignoreCase = true)) {
            fileName.removeSuffix(".xopp")
        } else if (fileName.endsWith(".pdf", ignoreCase = true)) {
            fileName.removeSuffix(".pdf")
        } else {
            fileName
        }
    }


    private fun sanitizeNotebookName(raw: String, maxLen: Int = 100): String {
        var name = raw

        // Allow only letters, numbers, spaces, and dots
        name = name.replace(Regex("[^A-Za-z0-9. ]"), " ")

        // Collapse multiple spaces
        name = name.replace(Regex("\\s+"), " ")

        // Reduce multiple consecutive dots to a single dot
        name = name.replace(Regex("\\.+"), ".")

        // Trim whitespace from start and end
        name = name.trim()

        // Remove leading dot if present
        if (name.startsWith(".")) {
            name = name.removePrefix(".")
        }

        // Cut if too long
        if (name.length > maxLen) {
            name = name.take(maxLen).trimEnd()
        }

        return name
    }

}