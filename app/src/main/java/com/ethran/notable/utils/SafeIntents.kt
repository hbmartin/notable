package com.ethran.notable.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Launch an external activity without allowing a missing or blocked handler to crash Notable. */
fun Context.launchIntentSafely(
    intent: Intent,
    chooserTitle: String? = null,
    onError: (String) -> Unit = {},
): Boolean {
    val launchIntent = if (chooserTitle != null) {
        Intent.createChooser(intent, chooserTitle)
    } else {
        intent
    }.apply {
        if (this@launchIntentSafely !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
        startActivity(launchIntent)
        true
    } catch (_: ActivityNotFoundException) {
        onError("No installed application can handle this request.")
        false
    } catch (_: SecurityException) {
        onError("Android blocked this request for security reasons.")
        false
    }
}
