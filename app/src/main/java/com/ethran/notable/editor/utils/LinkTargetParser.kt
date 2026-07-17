package com.ethran.notable.editor.utils

sealed interface NotableDeepLinkTarget {
    data class Page(val id: String) : NotableDeepLinkTarget
    data class Notebook(val id: String) : NotableDeepLinkTarget
    data class Attachment(val id: String) : NotableDeepLinkTarget
}

object LinkTargetParser {
    fun parseNotableUri(uri: String): NotableDeepLinkTarget? {
        val target = uri.removePrefix("notable://")
        if (target == uri || target.isBlank() || '/' in target) return null
        return when {
            target.startsWith("page-") -> target.removePrefix("page-").takeIf(String::isNotBlank)?.let(NotableDeepLinkTarget::Page)
            target.startsWith("book-") -> target.removePrefix("book-").takeIf(String::isNotBlank)?.let(NotableDeepLinkTarget::Notebook)
            target.startsWith("attachment-") -> target.removePrefix("attachment-").takeIf(String::isNotBlank)?.let(NotableDeepLinkTarget::Attachment)
            else -> null
        }
    }

    fun isSafeWebUrl(value: String): Boolean = runCatching {
        val scheme = java.net.URI(value).scheme?.lowercase()
        scheme == "http" || scheme == "https"
    }.getOrDefault(false)
}
