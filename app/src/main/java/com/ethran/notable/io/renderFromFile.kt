package com.ethran.notable.io

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.utils.Timing
import com.ethran.notable.utils.ensureNotMainThread
import io.shipbook.shipbooksdk.ShipBook
import java.io.File


private val log = ShipBook.getLogger("renderFromFile")

/**
 * Converts a URI to a Bitmap using the provided [context] and [uri].
 *
 * @param context The context used to access the content resolver.
 * @param uri The URI of the image to be converted to a Bitmap.
 * @return The Bitmap representation of the image, or null if conversion fails.
 * https://medium.com/@munbonecci/how-to-display-an-image-loaded-from-the-gallery-using-pick-visual-media-in-jetpack-compose-df83c78a66bf
 */
fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        // Obtain the content resolver from the context
        val contentResolver: ContentResolver = context.contentResolver

        // Since the minimum SDK is 29, we can directly use ImageDecoder to decode the Bitmap
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source)
    } catch (e: SecurityException) {
        log.e("SecurityException: ${e.message}", e)
        null
    } catch (e: ImageDecoder.DecodeException) {
        log.e("DecodeException: ${e.message}", e)
        null
    } catch (e: Exception) {
        log.e("Unexpected error: ${e.message}", e)
        null
    }
}


fun loadBackgroundBitmap(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
    if (filePath.isEmpty()) return null
    ensureNotMainThread("loadBackgroundBitmap")
    log.v("Reloading background, path: $filePath, scale: $scale")
    val file = File(filePath)
    if (!file.exists()) {
        log.v("getOrLoadBackground: File does not exist at path: $filePath")
        return null
    }
    val timer = Timing("loadBackgroundBitmap")
    if (!filePath.endsWith(".pdf", ignoreCase = true)) {
        try {
            timer.step("decode bitmap image")
            val result = decodeImageDownsampled(
                file,
                targetWidth = (SCREEN_WIDTH * scale.coerceAtMost(2f)).toInt()
            )
            if (result == null)
                log.e(
                    "loadBackgroundBitmap: result is null, couldn't decode image, file name ends with ${
                        filePath.takeLast(
                            4
                        )
                    }"
                )
            timer.end("loaded background")
            return result
        } catch (e: Exception) {
            log.e("getOrLoadBackground: Error loading background - ${e.message}", e)
        }
        // Image decode failed — don't fall through and hand a non-PDF file
        // to the PDF renderers below.
        return null
    }
    val targetWidth = SCREEN_WIDTH * (scale.coerceAtMost(2f))
    timer.step("start android Pdf")
    log.d("Rendering using ${if (GlobalAppSettings.current.muPdfRendering) "MuPdf" else "Android Pdf"}")
    val newBitmap: Bitmap? = if (GlobalAppSettings.current.muPdfRendering)
        renderPdfPageMuPdf(filePath, pageNumber, targetWidth.toInt(), resolutionModifier = 1.5f)
    else
        renderPdfPageAndroid(file, pageNumber, targetWidth.toInt(), resolutionModifier = 1.2f)
    timer.end("loaded background")
    return newBitmap
}

/**
 * Decodes an image file at roughly [targetWidth], using [BitmapFactory.Options.inSampleSize]
 * so a camera-sized photo is not decoded at full resolution just to be drawn
 * as a page background.
 */
fun decodeImageDownsampled(file: File, targetWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    if (targetWidth > 0) {
        while (bounds.outWidth / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
