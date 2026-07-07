package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Modal listing every notebook so the user can pick one, e.g. as the target
 * when moving a quick page into a notebook.
 */
@Composable
fun ShowNotebookSelectionDialog(
    appRepository: AppRepository,
    title: String,
    onCancel: () -> Unit,
    onConfirm: (notebookId: String) -> Unit
) {
    var books by remember { mutableStateOf<List<Notebook>?>(null) }
    var selectedBookId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // getAll() runs a blocking Room query — keep it off the main thread.
        books = withContext(Dispatchers.IO) { appRepository.bookRepository.getAll() }
    }

    Dialog(onDismissRequest = { onCancel() }) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val loadedBooks = books
            when {
                loadedBooks == null -> {
                    CircularProgressIndicator()
                }

                loadedBooks.isEmpty() -> {
                    Text(text = "No notebooks yet.", fontSize = 16.sp)
                }

                else -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        loadedBooks.forEach { book ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedBookId = book.id }
                                    .background(if (selectedBookId == book.id) Color.LightGray else Color.Transparent)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = book.title,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier.fillMaxWidth()
            ) {
                ActionButton("Cancel", onClick = onCancel)
                ActionButton("Confirm", onClick = {
                    selectedBookId?.let { onConfirm(it) }
                })
            }
        }
    }
}
