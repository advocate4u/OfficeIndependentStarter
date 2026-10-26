package com.independent.office

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RecentDocument(val uri: String, val name: String, val isPdf: Boolean, val favorite: Boolean = false)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { OfficeHome() } } }
    }
}

@Composable
private fun OfficeHome() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("No file selected") }
    var editorText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Open a text file or PDF to get started.") }
    var isPdf by remember { mutableStateOf(false) }
    var pdfPage by remember { mutableIntStateOf(0) }
    var pdfPageCount by remember { mutableIntStateOf(0) }
    var pdfBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var zoom by remember { mutableIntStateOf(100) }
    var showPageDialog by remember { mutableStateOf(false) }
    var pageInput by remember { mutableStateOf("1") }
    var showFindReplaceDialog by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replacementText by remember { mutableStateOf("") }
    var recentFiles by remember { mutableStateOf(loadRecentDocuments(context)) }
    var favoriteUris by remember { mutableStateOf(loadFavoriteUris(context)) }
    var homeSearch by remember { mutableStateOf("") }
    var recentFilter by remember { mutableStateOf("All") }
    var undoStack by remember { mutableStateOf(emptyList<String>()) }
    var redoStack by remember { mutableStateOf(emptyList<String>()) }

    fun rememberRecent(uri: Uri, name: String, pdf: Boolean) {
        val oldFavorite = recentFiles.firstOrNull { it.uri == uri.toString() }?.favorite ?: false
        val updated = (listOf(RecentDocument(uri.toString(), name, pdf, oldFavorite)) + recentFiles.filterNot { it.uri == uri.toString() }).take(20)
        recentFiles = updated
        saveRecentDocuments(context, updated)
    }

    fun updateEditorText(newText: String) {
        if (newText == editorText) return
        undoStack = (undoStack + editorText).takeLast(50)
        redoStack = emptyList()
        editorText = newText
        selectedUri?.let { uri -> if (!isPdf) saveDraft(context, uri, newText) }
    }

    fun undoEditor() {
        val previous = undoStack.lastOrNull() ?: return
        redoStack = (redoStack + editorText).takeLast(50)
        undoStack = undoStack.dropLast(1)
        editorText = previous
        selectedUri?.let { uri -> if (!isPdf) saveDraft(context, uri, previous) }
    }

    fun redoEditor() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = (undoStack + editorText).takeLast(50)
        redoStack = redoStack.dropLast(1)
        editorText = next
        selectedUri?.let { uri -> if (!isPdf) saveDraft(context, uri, next) }
    }

    
    fun toggleFavorite(uri: String) {
        val updated = if (uri in favoriteUris) favoriteUris - uri else favoriteUris + uri
        favoriteUris = updated
        saveFavoriteUris(context, updated)
        status = if (uri in updated) "Added to favorites." else "Removed from favorites."
    }


    fun renderPdf(uri: Uri, pageIndex: Int, scalePercent: Int) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("Unable to open PDF")
                    pfd.use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            if (renderer.pageCount == 0) error("PDF has no pages")
                            val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                            renderer.openPage(safeIndex).use { page ->
                                val scale = (scalePercent.coerceIn(50, 250) / 100f) *
                                    (1000f / page.width.coerceAtLeast(1))
                                val width = (page.width * scale).toInt().coerceIn(1, 2200)
                                val height = (page.height * (width.toFloat() / page.width)).toInt().coerceIn(1, 3000)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                Triple(bitmap, renderer.pageCount, safeIndex)
                            }
                        }
                    }
                } catch (e: Exception) {
                    throw e
                }
            }
            pdfBitmap = result.first
            pdfPageCount = result.second
            pdfPage = result.third
            status = "PDF page ${result.third + 1} of ${result.second}"
        }
    }

    fun openDocument(uri: Uri) {
        undoStack = emptyList()
        redoStack = emptyList()
        selectedUri = uri
        fileName = queryDisplayName(uri, context.contentResolver)
        isPdf = context.contentResolver.getType(uri) == "application/pdf" || fileName.endsWith(".pdf", true)
        rememberRecent(uri, fileName, isPdf)
        if (isPdf) {
            editorText = ""
            pdfPage = 0
            zoom = 100
            renderPdf(uri, 0, zoom)
        } else {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mime = context.contentResolver.getType(uri).orEmpty()
            val textLike = extension in setOf("txt", "md", "csv", "json", "xml", "html", "htm", "log", "yaml", "yml") || mime.startsWith("text/") || mime == "application/json" || mime == "application/xml"
            if (!textLike) {
                editorText = ""
                status = "This format needs a compatible office app. Use Open in compatible app below."
                return
            }
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("Could not read file")
                    }
                }
                if (loaded.isSuccess) {
                    undoStack = emptyList()
                    redoStack = emptyList()
                    val diskText = loaded.getOrDefault("")
                    val draft = loadDraft(context, uri)
                    if (draft != null && draft != diskText) {
                        editorText = draft
                        status = "Recovered unsaved local draft. Save to write changes to the file."
                    } else {
                        if (draft != null) clearDraft(context, uri)
                        editorText = diskText
                        status = "Text file opened. Changes are backed up locally; use Save to update the file."
                    }
                } else {
                    editorText = ""
                    status = "Could not read this text file."
                }
            }
        }
    }


    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            openDocument(uri)
        }
    }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(editorText)
                        } ?: error("Could not create file")
                    }
                }
                if (saved.isSuccess) {
                    selectedUri = uri
                    fileName = queryDisplayName(uri, context.contentResolver)
                    isPdf = false
                    rememberRecent(uri, fileName, false)
                    clearDraft(context, uri)
                    status = "Copy saved and opened for continued editing."
                } else {
                    status = "Save failed: ${saved.exceptionOrNull()?.message}"
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(38.dp))
            Column {
                Text("Office Independent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Free Android document utility")
            }
        }
        OutlinedTextField(
            value = homeSearch,
            onValueChange = { homeSearch = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search recent documents") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Search, contentDescription = null) }
        )
        Text("Quick access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text("Text", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { editorText = ""; undoStack = emptyList(); redoStack = emptyList(); fileName = "Untitled.txt"; selectedUri = null; isPdf = false; status = "New text document. Use Save copy to save it." }) { Text("New") }
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text("PDF", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { openLauncher.launch(arrayOf("application/pdf")) }) { Text("Open") }
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text("Files", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) { Text("Browse") }
                }
            }
        }
        OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Open any document")
        }
        Text("Document types", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { openLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword")) }, modifier = Modifier.weight(1f)) { Text("Word") }
            OutlinedButton(onClick = { openLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv")) }, modifier = Modifier.weight(1f)) { Text("Sheets") }
            OutlinedButton(onClick = { openLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint")) }, modifier = Modifier.weight(1f)) { Text("Slides") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(fileName, fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }

        val visibleRecent = recentFiles.filter { recent ->
            recent.name.contains(homeSearch, ignoreCase = true) &&
                when (recentFilter) {
                    "PDF" -> recent.isPdf
                    "Text" -> !recent.isPdf
                    "★ Saved" -> recent.uri in favoriteUris
                    else -> true
                }
        }
        val visibleFavorites = recentFiles.filter { it.uri in favoriteUris && it.name.contains(homeSearch, ignoreCase = true) }
        if (visibleFavorites.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Favorites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    visibleFavorites.forEach { recent ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { openDocument(Uri.parse(recent.uri)) }, modifier = Modifier.weight(1f)) {
                                Text((if (recent.isPdf) "PDF · " else "FILE · ") + recent.name, maxLines = 1)
                            }
                            IconButton(onClick = { toggleFavorite(recent.uri) }) {
                                androidx.compose.material3.Icon(Icons.Default.Star, contentDescription = "Remove favorite")
                            }
                        }
                    }
                }
            }
        }
        if (recentFiles.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("All", "PDF", "Text", "★ Saved").forEach { filter ->
                            TextButton(onClick = { recentFilter = filter }) {
                                Text(if (recentFilter == filter) "• $filter" else filter)
                            }
                        }
                    }
                    if (visibleRecent.isEmpty()) Text("No matching recent files.", style = MaterialTheme.typography.bodySmall)
                    visibleRecent.forEach { recent ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { openDocument(Uri.parse(recent.uri)) }, modifier = Modifier.weight(1f)) {
                                Text((if (recent.isPdf) "PDF · " else "FILE · ") + recent.name, maxLines = 1)
                            }
                            IconButton(onClick = { toggleFavorite(recent.uri) }) {
                                androidx.compose.material3.Icon(
                                    if (recent.uri in favoriteUris) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = if (recent.uri in favoriteUris) "Remove favorite" else "Add favorite"
                                )
                            }
                        }
                    }
                    TextButton(onClick = {
                        val kept = recentFiles.filter { it.uri in favoriteUris }
                        recentFiles = kept
                        saveRecentDocuments(context, kept)
                        status = "Recent history cleared; saved favorites were kept."
                    }) { Text("Clear history (keep favorites)") }
                }
            }
        }

        if (isPdf && selectedUri != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Text("PDF reader", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${zoom}%")
            }
            pdfBitmap?.let { bitmap ->
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "PDF page ${pdfPage + 1}",
                        modifier = Modifier.width(with(LocalDensity.current) { bitmap.width.toDp() }).padding(vertical = 4.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { selectedUri?.let { renderPdf(it, pdfPage - 1, zoom) } },
                    enabled = pdfPage > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Previous") }
                OutlinedButton(
                    onClick = { selectedUri?.let { renderPdf(it, pdfPage + 1, zoom) } },
                    enabled = pdfPageCount > 0 && pdfPage < pdfPageCount - 1,
                    modifier = Modifier.weight(1f)
                ) { Text("Next") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    zoom = (zoom - 25).coerceAtLeast(50)
                    selectedUri?.let { renderPdf(it, pdfPage, zoom) }
                }, modifier = Modifier.weight(1f)) { Text("− Zoom") }
                OutlinedButton(onClick = {
                    zoom = 100
                    selectedUri?.let { renderPdf(it, pdfPage, zoom) }
                }, modifier = Modifier.weight(1f)) { Text("100%") }
                OutlinedButton(onClick = {
                    zoom = (zoom + 25).coerceAtMost(250)
                    selectedUri?.let { renderPdf(it, pdfPage, zoom) }
                }, modifier = Modifier.weight(1f)) { Text("+ Zoom") }
            }
            OutlinedButton(
                onClick = { pageInput = (pdfPage + 1).toString(); showPageDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Go to page…") }
            selectedUri?.let { uri ->
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share PDF"))
                }, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Share PDF")
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Text editor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { showFindReplaceDialog = true }) { Text("Find / Replace") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { undoEditor() }, enabled = undoStack.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Undo") }
                OutlinedButton(onClick = { redoEditor() }, enabled = redoStack.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Redo") }
            }
            OutlinedTextField(
                value = editorText,
                onValueChange = { updateEditorText(it) },
                modifier = Modifier.fillMaxWidth().height(320.dp),
                label = { Text("Text / Markdown / CSV") },
                placeholder = { Text("Open a text file or start typing…") }
            )
            val wordCount = editorText.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            val lineCount = if (editorText.isEmpty()) 0 else editorText.count { it == '\n' } + 1
            Text("$wordCount words · ${editorText.length} characters · $lineCount lines", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val uri = selectedUri
                        if (uri == null) {
                            status = "Use Save copy to choose a destination."
                        } else {
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                                            it.write(editorText)
                                        } ?: error("Could not write file")
                                    }
                                }
                                if (saved.isSuccess) clearDraft(context, uri)
                                status = if (saved.isSuccess) "Saved successfully. Local draft cleared." else "Save failed: ${saved.exceptionOrNull()?.message}"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    androidx.compose.material3.Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Save")
                }
                OutlinedButton(onClick = { createLauncher.launch(suggestedTextCopyName(fileName)) }, modifier = Modifier.weight(1f)) {
                    Text("Save copy")
                }
            }
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, fileName)
                        putExtra(Intent.EXTRA_TEXT, editorText)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share text"))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = editorText.isNotEmpty()
            ) {
                androidx.compose.material3.Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Share text")
            }
            selectedUri?.let { uri ->
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
                        .onFailure { status = "No compatible app found." }
                }, modifier = Modifier.fillMaxWidth()) { Text("Open in compatible app") }
            }
        }
        if (showFindReplaceDialog) {
            val matchCount = if (findText.isEmpty()) 0 else Regex(Regex.escape(findText), RegexOption.IGNORE_CASE).findAll(editorText).count()
            AlertDialog(
                onDismissRequest = { showFindReplaceDialog = false },
                title = { Text("Find and replace") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = findText,
                            onValueChange = { findText = it },
                            label = { Text("Find text") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = replacementText,
                            onValueChange = { replacementText = it },
                            label = { Text("Replace with") },
                            singleLine = true
                        )
                        Text("$matchCount match(es), case-insensitive", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (findText.isNotEmpty()) {
                            val before = editorText
                            val replaced = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE).replace(editorText) { replacementText }
                            val changed = before != replaced
                            if (changed) updateEditorText(replaced)
                            status = if (changed) "Replaced matching text. Local draft updated; save to keep changes in the file." else "No matching text found."
                        }
                        showFindReplaceDialog = false
                    }) { Text("Replace all") }
                },
                dismissButton = {
                    TextButton(onClick = { showFindReplaceDialog = false }) { Text("Close") }
                }
            )
        }
        if (showPageDialog) {
            AlertDialog(
                onDismissRequest = { showPageDialog = false },
                title = { Text("Go to page") },
                text = {
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { pageInput = it.filter(Char::isDigit).take(7) },
                        label = { Text("Page number (1–$pdfPageCount)") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val target = pageInput.toIntOrNull()
                        if (target != null && target in 1..pdfPageCount) {
                            selectedUri?.let { renderPdf(it, target - 1, zoom) }
                            showPageDialog = false
                        } else {
                            status = "Enter a page between 1 and $pdfPageCount."
                        }
                    }) { Text("Go") }
                },
                dismissButton = {
                    TextButton(onClick = { showPageDialog = false }) { Text("Cancel") }
                }
            )
        }
        Divider()
        Text("DOCX, XLSX and PPTX editing require an office-engine integration and are not yet built in.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun queryDisplayName(uri: Uri, resolver: android.content.ContentResolver): String {
    return try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Selected document"
    } catch (_: Exception) {
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Selected document"
    }
}


private fun loadRecentDocuments(context: android.content.Context): List<RecentDocument> {
    return runCatching {
        val json = context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
            .getString("recent_documents", "[]") ?: "[]"
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RecentDocument(uri, item.optString("name", "Document"), item.optBoolean("isPdf"), item.optBoolean("favorite", false))
        }.take(20)
    }.getOrDefault(emptyList())
}

private fun saveRecentDocuments(context: android.content.Context, documents: List<RecentDocument>) {
    val array = JSONArray()
    documents.forEach { document ->
        array.put(JSONObject().put("uri", document.uri).put("name", document.name).put("isPdf", document.isPdf).put("favorite", document.favorite))
    }
    context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
        .edit().putString("recent_documents", array.toString()).apply()
}



private fun loadFavoriteUris(context: android.content.Context): Set<String> {
    return runCatching {
        val json = context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
            .getString("favorite_uris", "[]") ?: "[]"
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }.toSet()
    }.getOrDefault(emptySet())
}

private fun saveFavoriteUris(context: android.content.Context, uris: Set<String>) {
    val array = JSONArray()
    uris.sorted().forEach { array.put(it) }
    context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
        .edit().putString("favorite_uris", array.toString()).apply()
}

private fun draftKey(uri: Uri): String = "draft:" + uri.toString()

private fun loadDraft(context: android.content.Context, uri: Uri): String? =
    context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
        .getString(draftKey(uri), null)

private fun saveDraft(context: android.content.Context, uri: Uri, text: String) {
    context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
        .edit().putString(draftKey(uri), text).apply()
}

private fun clearDraft(context: android.content.Context, uri: Uri) {
    context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
        .edit().remove(draftKey(uri)).apply()
}


private fun suggestedTextCopyName(currentName: String): String {
    val supported = setOf("txt", "md", "csv", "json", "xml", "html", "htm", "log", "yaml", "yml")
    val extension = currentName.substringAfterLast('.', "").lowercase()
    return if (currentName != "No file selected" && extension in supported) currentName else "document.txt"
}
