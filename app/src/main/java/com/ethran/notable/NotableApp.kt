package com.ethran.notable

import android.app.Application
import android.os.Environment
import android.util.Log
import com.ethran.notable.io.AtomicFileStore
import com.onyx.android.sdk.rx.RxManager
import dagger.hilt.android.HiltAndroidApp
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

@HiltAndroidApp
class NotableApp : Application() {

    override fun onCreate() {
        Log.i("NotableApp", "onCreate START")
        super.onCreate()
        RxManager.Builder.initAppContext(this)
        checkHiddenApiBypass()
        recoverStaleFilesInBackground()
        Log.i("NotableApp", "onCreate FINISH")
    }

    private fun recoverStaleFilesInBackground() {
        Thread({
            val databaseRoot = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "notabledb",
            )
            listOf(filesDir, cacheDir, databaseRoot).forEach { root ->
                runCatching {
                    if (!root.isDirectory) return@runCatching
                    root.walkTopDown().filter(File::isDirectory).forEach { directory ->
                        runCatching { AtomicFileStore.recoverStaleFiles(directory) }
                            .onFailure {
                                Log.w(
                                    "NotableApp",
                                    "Could not recover stale files in ${directory.absolutePath}",
                                    it,
                                )
                            }
                    }
                }.onFailure {
                    Log.w("NotableApp", "Could not scan ${root.absolutePath}", it)
                }
            }
        }, "notable-stale-file-recovery").start()
    }

    private fun checkHiddenApiBypass() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

}
