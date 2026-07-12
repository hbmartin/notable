package com.ethran.notable.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.WorkerThread
import com.ethran.notable.data.copyBackgroundToDatabase
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageWithData
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.utils.ensureNotMainThread
import io.shipbook.shipbooksdk.ShipBook
import java.io.File

private val log = ShipBook.getLogger("importPdf")

@WorkerThread
fun handleFileSaving(
    context: Context,
    uri: Uri,
    options: ImportOptions,
): File? {
    ensureNotMainThread("Importing")

    //copy file:
    val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val permissionPersisted = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, flag)
    }.isSuccess
    val subfolder = BackgroundType.Pdf(0).folderName
    return if (!options.linkToExternalFile) copyBackgroundToDatabase(context, uri, subfolder)
    else {
        if (!permissionPersisted) {
            log.w("Provider did not grant persistent access for linked PDF: $uri")
        }
        val fileName = getFilePathFromUri(context, uri)
        if (fileName == null) {
            log.e("Couldn't determine file path. Missing permission for external storage?")
            return null
        } else File(fileName)
    }
}

@WorkerThread
suspend fun importPdf(
    fileToSave: File,
    pageCount: Int,
    options: ImportOptions,
    savePageToDatabase: suspend (PageWithData) -> Unit
): String {
    log.v("Importing PDF from")

    require(pageCount > 0) { "PDF must contain at least one readable page" }
    for (i in 0 until pageCount) {
        val page = Page(
            notebookId = options.saveToBookId,
            background = fileToSave.toString(),
            backgroundType = if (options.linkToExternalFile) BackgroundType.AutoPdf.key
            else BackgroundType.Pdf(i).key
        )
        savePageToDatabase(PageWithData(page, emptyList(), emptyList()))
    }
    return "Imported ${fileToSave.name}"
}
