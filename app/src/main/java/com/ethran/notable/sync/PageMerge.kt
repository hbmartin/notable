package com.ethran.notable.sync

import java.util.Date

/**
 * Per-page reconciliation decision used when a notebook has changed on both the
 * local device and the server. Notebook metadata and page membership still follow
 * the newer manifest (last-writer-wins, as before), but the *content* of each page
 * is decided by the page's own updatedAt so concurrent edits to different pages of
 * the same notebook no longer overwrite each other.
 */
enum class PageSyncAction {
    /** Local page is newer (or the remote copy is missing/corrupt): upload it. */
    UPLOAD_LOCAL,

    /** Remote page is newer (or there is no local copy): apply it locally. */
    APPLY_REMOTE,

    /** Timestamps agree within tolerance, or neither side has the page. */
    SKIP,
}

fun decidePageAction(
    localUpdatedAt: Date?,
    remoteUpdatedAt: Date?,
    toleranceMs: Long
): PageSyncAction {
    if (localUpdatedAt == null && remoteUpdatedAt == null) return PageSyncAction.SKIP
    if (localUpdatedAt == null) return PageSyncAction.APPLY_REMOTE
    if (remoteUpdatedAt == null) return PageSyncAction.UPLOAD_LOCAL

    val diffMs = localUpdatedAt.time - remoteUpdatedAt.time
    return when {
        diffMs > toleranceMs -> PageSyncAction.UPLOAD_LOCAL
        diffMs < -toleranceMs -> PageSyncAction.APPLY_REMOTE
        else -> PageSyncAction.SKIP
    }
}
