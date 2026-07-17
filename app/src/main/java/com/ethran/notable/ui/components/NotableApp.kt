package com.ethran.notable.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ethran.notable.data.AppRepository
import com.ethran.notable.gestures.quickNavGesture
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.navigation.NotableNavHost
import com.ethran.notable.navigation.rememberNotableAppState
import com.ethran.notable.ui.SnackBar
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.SnackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@Composable
fun NotableApp(
    exportEngine: ExportEngine,
    snackState: SnackState,
    snackDispatcher: SnackDispatcher,
    appRepository: AppRepository,
    incomingLink: Uri? = null,
    onIncomingLinkHandled: () -> Unit = {},
) {
    val appNavState = rememberNotableAppState()
    LaunchedEffect(incomingLink) {
        val link = incomingLink ?: return@LaunchedEffect
        val target = link.schemeSpecificPart.removePrefix("//")
        when {
            target.startsWith("page-") -> {
                val pageId = target.removePrefix("page-")
                val page = withContext(Dispatchers.IO) { appRepository.pageRepository.getById(pageId) }
                if (page != null) appNavState.goToEditor(page.id, page.notebookId)
            }
            target.startsWith("book-") -> {
                val bookId = target.removePrefix("book-")
                val book = withContext(Dispatchers.IO) { appRepository.bookRepository.getById(bookId) }
                val pageId = book?.openPageId ?: book?.pageIds?.firstOrNull()
                if (pageId != null) appNavState.goToEditor(pageId, bookId)
            }
        }
        onIncomingLinkHandled()
    }
    Box(
        Modifier
            .background(Color.White)
            .fillMaxSize()
            .quickNavGesture { appNavState.openQuickNav() }
    ) {
        NotableNavHost(
            exportEngine = exportEngine,
            appRepository = appRepository,
            appNavigator = appNavState
        )


        // overlays
        if (appNavState.isQuickNavOpen) {
            QuickNav(
                appRepository = appRepository,
                currentPageId = appNavState.currentPageId,
                quickNavSourcePageId = appNavState.quickNavSourcePageId,
                onClose = { appNavState.closeQuickNav() },
                goToPage = { pageId -> appNavState.goToPage(appRepository, pageId) },
                goToFolder = { folderId -> appNavState.goToLibrary(folderId) }
            )
        }

        if (appNavState.shouldAnchorBeVisible()) {
            Anchor(
                onClose = {
                    appNavState.goToAnchor(appRepository)
                    appNavState.closeQuickNav()
                }
            )
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.Black)
    )
    SnackBar(state = snackState, dispatcher = snackDispatcher)
}
