package com.independent.office

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
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
import androidx.compose.ui.unit.sp
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
    var lastSavedText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Open a text file or PDF to get started.") }
    var isPdf by remember { mutableStateOf(false) }
    var unsupportedOfficeFile by remember { mutableStateOf(false) }
    var officePreviewOnly by remember { mutableStateOf(false) }
    var pdfPage by remember { mutableIntStateOf(0) }
    var pdfRotation by remember { mutableIntStateOf(0) }
    var pdfBookmarks by remember { mutableStateOf(emptySet<Int>()) }
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
    var editorFontSize by remember { mutableIntStateOf(context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE).getInt("editor_font_size", 16).coerceIn(10, 32)) }
    var csvTableMode by remember { mutableStateOf(false) }
    var csvFilter by remember { mutableStateOf("") }
    var csvSortColumnText by remember { mutableStateOf("1") }
    var csvNumericSort by remember { mutableStateOf(false) }
    var csvDeleteRowIndex by remember { mutableIntStateOf(-1) }
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

    fun setEditorFontSize(size: Int) {
        val safeSize = size.coerceIn(10, 32)
        editorFontSize = safeSize
        context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
            .edit().putInt("editor_font_size", safeSize).apply()
    }

    fun togglePdfBookmark(page: Int) {
        val uri = selectedUri ?: return
        val updated = if (page in pdfBookmarks) pdfBookmarks - page else pdfBookmarks + page
        pdfBookmarks = updated
        savePdfBookmarks(context, uri.toString(), updated)
        status = if (page in updated) "Bookmarked PDF page ${page + 1}." else "Removed bookmark for page ${page + 1}."
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
                                val finalBitmap = if (pdfRotation % 360 == 0) {
                                    bitmap
                                } else {
                                    val matrix = Matrix().apply { postRotate(pdfRotation.toFloat()) }
                                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                                        if (it !== bitmap) bitmap.recycle()
                                    }
                                }
                                Triple(finalBitmap, renderer.pageCount, safeIndex)
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
            context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("last_pdf_page:" + uri.toString(), result.third)
                .putInt("pdf_zoom:" + uri.toString(), scalePercent)
                .putInt("pdf_rotation:" + uri.toString(), pdfRotation)
                .apply()
            status = "PDF page ${result.third + 1} of ${result.second}"
        }
    }

    fun openDocument(uri: Uri) {
        unsupportedOfficeFile = false
        officePreviewOnly = false
        undoStack = emptyList()
        redoStack = emptyList()
        selectedUri = uri
        fileName = queryDisplayName(uri, context.contentResolver)
        isPdf = context.contentResolver.getType(uri) == "application/pdf" || fileName.endsWith(".pdf", true)
        csvTableMode = !isPdf && fileName.endsWith(".csv", true)
        csvFilter = ""
        csvSortColumnText = "1"
        csvNumericSort = false
        rememberRecent(uri, fileName, isPdf)
        if (isPdf) {
            pdfBookmarks = loadPdfBookmarks(context, uri.toString())
            editorText = ""
            lastSavedText = ""
            val pdfPreferences = context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
            pdfRotation = pdfPreferences.getInt("pdf_rotation:" + uri.toString(), 0).coerceIn(0, 270) / 90 * 90
            zoom = pdfPreferences.getInt("pdf_zoom:" + uri.toString(), 100).coerceIn(50, 250)
            val lastPage = pdfPreferences.getInt("last_pdf_page:" + uri.toString(), 0)
            pdfPage = lastPage
            renderPdf(uri, lastPage, zoom)
        } else {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mime = context.contentResolver.getType(uri).orEmpty()
            val textLike = extension in setOf("txt", "md", "csv", "json", "xml", "html", "htm", "log", "yaml", "yml") || mime.startsWith("text/") || mime == "application/json" || mime == "application/xml"
            if (!textLike) {
                if (extension == "docx" || extension == "pptx") {
                    scope.launch {
                        val loaded = withContext(Dispatchers.IO) {
                            runCatching {
                                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: error("Could not read Office document")
                                extractOfficePreview(bytes, extension)
                            }
                        }
                        if (loaded.isSuccess) {
                            officePreviewOnly = true
                            editorText = loaded.getOrDefault("")
                            lastSavedText = editorText
                            status = "Read-only preview. Open in a compatible office app to edit and preserve formatting."
                        } else {
                            unsupportedOfficeFile = true
                            editorText = ""
                            lastSavedText = ""
                            status = "Could not preview this Office file. Use Open in compatible app below."
                        }
                    }
                    return
                }
                unsupportedOfficeFile = true
                editorText = ""
                lastSavedText = ""
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
                    lastSavedText = diskText
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

    val pdfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            if (fileName.endsWith(".csv", true) && csvTableMode) {
                                val rows = parseCsv(editorText)
                                val visibleRows = if (csvFilter.isBlank()) rows else {
                                    rows.take(1) + rows.drop(1).filter { row ->
                                        row.any { cell -> cell.contains(csvFilter, ignoreCase = true) }
                                    }
                                }
                                writeCsvPdf(visibleRows, fileName, output)
                            } else {
                                writeTextPdf(editorText, fileName, output)
                            }
                        } ?: error("Could not create PDF")
                    }
                }
                if (saved.isSuccess) {
                    rememberRecent(uri, queryDisplayName(uri, context.contentResolver), true)
                    status = if (fileName.endsWith(".csv", true) && csvTableMode && csvFilter.isNotBlank()) "Filtered CSV rows exported to PDF and added to Recent files." else "PDF export completed and added to Recent files."
                } else {
                    status = "PDF export failed: ${saved.exceptionOrNull()?.message}"
                }
            }
        }
    }

    val filteredCsvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val rows = parseCsv(editorText)
                        val visibleRows = if (csvFilter.isBlank()) rows else {
                            rows.take(1) + rows.drop(1).filter { row ->
                                row.any { cell -> cell.contains(csvFilter, ignoreCase = true) }
                            }
                        }
                        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(serializeCsv(visibleRows))
                        } ?: error("Could not create CSV file")
                    }
                }
                if (saved.isSuccess) {
                    rememberRecent(uri, queryDisplayName(uri, context.contentResolver), false)
                    status = if (csvFilter.isBlank()) "CSV exported and added to Recent files." else "Filtered CSV rows exported and added to Recent files."
                } else {
                    status = "CSV export failed: ${saved.exceptionOrNull()?.message}"
                }
            }
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
                    lastSavedText = editorText
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
                    TextButton(onClick = { editorText = ""; lastSavedText = ""; undoStack = emptyList(); redoStack = emptyList(); fileName = "Untitled.txt"; selectedUri = null; isPdf = false; status = "New text document. Use Save copy to save it." }) { Text("New") }
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
            OutlinedButton(
                onClick = {
                    pdfRotation = (pdfRotation + 90) % 360
                    selectedUri?.let { renderPdf(it, pdfPage, zoom) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Rotate page 90° · ${pdfRotation}°") }
            OutlinedButton(
                onClick = { togglePdfBookmark(pdfPage) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (pdfPage in pdfBookmarks) "★ Remove bookmark · Page ${pdfPage + 1}" else "☆ Bookmark page ${pdfPage + 1}")
            }
            if (pdfBookmarks.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pdfBookmarks.sorted().forEach { bookmarkedPage ->
                        OutlinedButton(onClick = { selectedUri?.let { renderPdf(it, bookmarkedPage, zoom) } }) {
                            Text("Page ${bookmarkedPage + 1}")
                        }
                    }
                }
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
        } else if (officePreviewOnly && selectedUri != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(fileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Read-only text preview", style = MaterialTheme.typography.labelLarge)
                    Text("Formatting, images, tables, and layout may not be represented here.", style = MaterialTheme.typography.bodySmall)
                    Text(editorText.ifBlank { "No readable text was found in this document." })
                    OutlinedButton(onClick = {
                        val uri = selectedUri
                        if (uri != null) {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, context.contentResolver.getType(uri) ?: if (fileName.endsWith(".docx", true)) "application/vnd.openxmlformats-officedocument.wordprocessingml.document" else "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(intent, "Open in compatible app")) }
                                .onFailure { status = "No compatible app found." }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open in compatible app to edit") }
                }
            }
        } else if (unsupportedOfficeFile && selectedUri != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(42.dp))
                    Text(fileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("This file is not plain text. Open it with a compatible office app to preserve its formatting and layout.")
                    Text("Built-in DOCX/XLSX/PPTX editing is not included yet.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        val uri = selectedUri
                        if (uri != null) {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(intent, "Open with compatible app")) }
                                .onFailure { status = "No compatible app found." }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open in compatible app") }
                    OutlinedButton(onClick = {
                        val uri = selectedUri
                        if (uri != null) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = context.contentResolver.getType(uri) ?: "*/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share document"))
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Share document") }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { setEditorFontSize(editorFontSize - 2) }, modifier = Modifier.weight(1f)) { Text("A−") }
                Text("Text size: ${editorFontSize}sp", modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { setEditorFontSize(editorFontSize + 2) }, modifier = Modifier.weight(1f)) { Text("A+") }
                TextButton(onClick = { setEditorFontSize(16) }) { Text("Reset") }
            }
            if (editorText != lastSavedText) {
                Text("Unsaved changes · local draft protected", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
            if (fileName.endsWith(".csv", true)) {
                OutlinedButton(onClick = { csvTableMode = !csvTableMode }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (csvTableMode) "Switch to raw CSV text" else "Switch to table view")
                }
            }
            if (csvTableMode && fileName.endsWith(".csv", true)) {
                Text("CSV table · edit cells directly (first 100 rows and 20 columns shown)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val rows = parseCsv(editorText).map { it.toMutableList() }.toMutableList()
                        val columnCount = rows.maxOfOrNull { it.size } ?: 1
                        rows.add(MutableList(columnCount) { "" })
                        updateEditorText(serializeCsv(rows))
                    }, modifier = Modifier.weight(1f)) { Text("＋ Add row") }
                    OutlinedButton(onClick = {
                        val rows = parseCsv(editorText).map { it.toMutableList() }.toMutableList()
                        rows.forEach { it.add("") }
                        updateEditorText(serializeCsv(rows))
                    }, modifier = Modifier.weight(1f)) { Text("＋ Add column") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val rows = parseCsv(editorText).map { it.toMutableList() }.toMutableList()
                        if (rows.size > 1) rows.removeAt(rows.lastIndex) else status = "A CSV needs at least one row."
                        updateEditorText(serializeCsv(rows))
                    }, modifier = Modifier.weight(1f)) { Text("− Remove last row") }
                    OutlinedButton(onClick = {
                        val rows = parseCsv(editorText).map { it.toMutableList() }.toMutableList()
                        rows.forEach { row ->
                            if (row.size > 1) row.removeAt(row.lastIndex) else row[0] = ""
                        }
                        updateEditorText(serializeCsv(rows))
                    }, modifier = Modifier.weight(1f)) { Text("− Remove last column") }
                }
                val allCsvRows = parseCsv(editorText)
                Text("${allCsvRows.size} rows · ${allCsvRows.maxOfOrNull { it.size } ?: 0} columns", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = csvSortColumnText,
                    onValueChange = { csvSortColumnText = it.filter(Char::isDigit).take(3) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Sort by column number (1 = first column)") }
                )
                OutlinedButton(
                    onClick = { csvNumericSort = !csvNumericSort },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (csvNumericSort) "Sort mode: Numeric (tap to switch to text)" else "Sort mode: Text (tap to switch to numeric)") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val rows = parseCsv(editorText)
                        val columnIndex = (csvSortColumnText.toIntOrNull() ?: 1) - 1
                        if (rows.size > 2 && columnIndex >= 0 && columnIndex < (rows.maxOfOrNull { it.size } ?: 0)) {
                            val sorted = listOf(rows.first()) + rows.drop(1).sortedWith { a, b -> compareCsvValues(a.getOrNull(columnIndex).orEmpty(), b.getOrNull(columnIndex).orEmpty(), csvNumericSort) }
                            updateEditorText(serializeCsv(sorted))
                            status = "Sorted data rows ascending by column ${columnIndex + 1}; first row kept as header."
                        } else status = "Enter a valid column number and ensure at least two data rows exist."
                    }, modifier = Modifier.weight(1f)) { Text("Sort A–Z") }
                    OutlinedButton(onClick = {
                        val rows = parseCsv(editorText)
                        val columnIndex = (csvSortColumnText.toIntOrNull() ?: 1) - 1
                        if (rows.size > 2 && columnIndex >= 0 && columnIndex < (rows.maxOfOrNull { it.size } ?: 0)) {
                            val sorted = listOf(rows.first()) + rows.drop(1).sortedWith { a, b -> compareCsvValues(b.getOrNull(columnIndex).orEmpty(), a.getOrNull(columnIndex).orEmpty(), csvNumericSort) }
                            updateEditorText(serializeCsv(sorted))
                            status = "Sorted data rows descending by column ${columnIndex + 1}; first row kept as header."
                        } else status = "Enter a valid column number and ensure at least two data rows exist."
                    }, modifier = Modifier.weight(1f)) { Text("Sort Z–A") }
                }
                OutlinedTextField(
                    value = csvFilter,
                    onValueChange = { csvFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Filter rows by any cell") }
                )
                val filteredCsvRows = parseCsv(editorText).mapIndexed { index, row -> index to row }
                    .filter { (index, row) -> csvFilter.isBlank() || index == 0 || row.any { it.contains(csvFilter, ignoreCase = true) } }
                Text("Showing ${filteredCsvRows.size} of ${allCsvRows.size} rows; up to 100 displayed", style = MaterialTheme.typography.bodySmall)
                if (csvFilter.isNotBlank()) {
                    OutlinedButton(onClick = { csvFilter = "" }, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear filter")
                    }
                }
                val csvRows = filteredCsvRows.take(100)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    csvRows.forEach { (rowIndex, row) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (rowIndex > 0) {
                                OutlinedButton(
                                    onClick = {
                                        val rows = parseCsv(editorText).map { it.toMutableList() }.toMutableList()
                                        if (rowIndex in rows.indices) {
                                            rows.add((rowIndex + 1).coerceAtMost(rows.size), rows[rowIndex].toMutableList())
                                            updateEditorText(serializeCsv(rows))
                                            status = "Duplicated row ${rowIndex + 1}."
                                        }
                                    }
                                ) { Text("Duplicate row") }
                                OutlinedButton(onClick = { csvDeleteRowIndex = rowIndex }) {
                                    Text("Delete row")
                                }
                                OutlinedButton(
                                    enabled = rowIndex > 1,
                                    onClick = {
                                        val rows = parseCsv(editorText).map { it.toMutableList() }.toMutableList()
                                        if (rowIndex > 1 && rowIndex in rows.indices) {
                                            val moving = rows.removeAt(rowIndex)
                                            rows.add(rowIndex - 1, moving)
                                            updateEditorText(serializeCsv(rows))
                                            status = "Moved row up."
                                        }
                                    }
                                ) { Text("↑") }
                                OutlinedButton(
                                    enabled = rowIndex < allCsvRows.lastIndex,
                                    onClick = {
                                        val rows = parseCsv(editorText).map { it.toMutableList() }.toMutableList()
                                        if (rowIndex > 0 && rowIndex < rows.lastIndex) {
                                            val moving = rows.removeAt(rowIndex)
                                            rows.add(rowIndex + 1, moving)
                                            updateEditorText(serializeCsv(rows))
                                            status = "Moved row down."
                                        }
                                    }
                                ) { Text("↓") }
                            }
                            row.take(20).forEachIndexed { columnIndex, cell ->
                                OutlinedTextField(
                                    value = cell,
                                    onValueChange = { newValue ->
                                        val mutableRows = parseCsv(editorText).map { it.toMutableList() }.toMutableList()
                                        while (mutableRows.size <= rowIndex) mutableRows.add(mutableListOf(""))
                                        while (mutableRows[rowIndex].size <= columnIndex) mutableRows[rowIndex].add("")
                                        mutableRows[rowIndex][columnIndex] = newValue
                                        updateEditorText(serializeCsv(mutableRows))
                                    },
                                    modifier = Modifier.width(150.dp),
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = editorFontSize.sp, fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal),
                                    placeholder = { Text("R${rowIndex + 1}C${columnIndex + 1}") }
                                )
                            }
                        }
                    }
                }
            } else {
                            OutlinedTextField(
                value = editorText,
                onValueChange = { updateEditorText(it) },
                modifier = Modifier.fillMaxWidth().height(320.dp),
                label = { Text("Text / Markdown / CSV") },
                placeholder = { Text("Open a text file or start typing…") },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = editorFontSize.sp)
            )
            }
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
                                if (saved.isSuccess) {
                                    lastSavedText = editorText
                                    clearDraft(context, uri)
                                }
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
                onClick = { pdfExportLauncher.launch(suggestedPdfName(fileName)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = editorText.isNotEmpty()
            ) {
                androidx.compose.material3.Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (fileName.endsWith(".csv", true) && csvTableMode) "Export visible CSV rows to PDF" else "Export text to PDF")
            }
            if (csvTableMode) {
                OutlinedButton(
                    onClick = { filteredCsvExportLauncher.launch(suggestedCsvName(fileName)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editorText.isNotEmpty()
                ) {
                    Text(if (csvFilter.isBlank()) "Export CSV copy" else "Export filtered rows to CSV")
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
        if (csvDeleteRowIndex > 0) {
            AlertDialog(
                onDismissRequest = { csvDeleteRowIndex = -1 },
                title = { Text("Delete CSV row?") },
                text = { Text("Delete row ${csvDeleteRowIndex + 1}? This cannot be undone after you save the file.") },
                confirmButton = {
                    TextButton(onClick = {
                        val rows = parseCsv(editorText).map { it.toMutableList() }.toMutableList()
                        if (csvDeleteRowIndex in 1 until rows.size) {
                            rows.removeAt(csvDeleteRowIndex)
                            updateEditorText(serializeCsv(rows))
                            status = "Deleted row ${csvDeleteRowIndex + 1}."
                        }
                        csvDeleteRowIndex = -1
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { csvDeleteRowIndex = -1 }) { Text("Cancel") }
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

private fun extractOfficePreview(bytes: ByteArray, extension: String): String {
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            val wanted = (extension == "docx" && name == "word/document.xml") ||
                (extension == "pptx" && Regex("ppt/slides/slide\\d+\\.xml").matches(name))
            if (!entry.isDirectory && wanted) entries[name] = zip.readBytes()
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    fun readText(xmlBytes: ByteArray): String {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(String(xmlBytes, Charsets.UTF_8)))
        val result = StringBuilder()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "t") {
                val value = parser.nextText()
                if (value.isNotBlank()) result.append(value).append(' ')
            } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG && (parser.name == "p" || parser.name == "sp")) {
                result.append('\n')
            }
            event = parser.next()
        }
        return result.toString().replace(Regex("[ \\t]+\\n"), "\n").trim()
    }
    return when (extension) {
        "docx" -> readText(entries["word/document.xml"] ?: error("Word document XML was not found"))
        "pptx" -> entries.entries.sortedBy { Regex("slide(\\d+)").find(it.key)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
            .mapIndexed { index, entry -> "Slide ${index + 1}\n${readText(entry.value)}" }
            .joinToString("\n\n").ifBlank { error("No readable slide text was found") }
        else -> error("Unsupported Office preview type")
    }
}

private fun suggestedCsvName(currentName: String): String {
    val base = currentName.substringBeforeLast('.', currentName).ifBlank { "data" }
    return "${base}_export.csv"
}

private fun suggestedPdfName(currentName: String): String {
    val base = currentName.substringBeforeLast('.', currentName).ifBlank { "document" }
    return "${base}.pdf"
}

private fun writeCsvPdf(rows: List<List<String>>, title: String, output: java.io.OutputStream) {
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val margin = 28f
    val cellHeight = 26f
    val columnCount = (rows.maxOfOrNull { it.size } ?: 1).coerceIn(1, 6)
    val columnWidth = (pageWidth - 2 * margin) / columnCount
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = 9f
    }
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = 9f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = 11f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.LTGRAY
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 0.6f
    }
    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.DKGRAY
        textSize = 9f
    }
    var pageNumber = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var canvas = page.canvas
    fun drawHeader() {
        canvas.drawText(title.take(70), margin, 22f, titlePaint)
    }
    fun drawFooter() {
        canvas.drawText("Page $pageNumber", pageWidth - 80f, pageHeight - 16f, footerPaint)
    }
    var y = 44f
    drawHeader()
    fun nextPage() {
        drawFooter()
        document.finishPage(page)
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page.canvas
        drawHeader()
        y = 44f
    }
    rows.take(500).forEachIndexed { rowIndex, row ->
        if (y + cellHeight > pageHeight - margin) nextPage()
        for (columnIndex in 0 until columnCount) {
            val left = margin + columnIndex * columnWidth
            val rect = android.graphics.RectF(left, y, left + columnWidth, y + cellHeight)
            if (rowIndex == 0) canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, borderPaint)
            val value = row.getOrNull(columnIndex).orEmpty().replace('\n', ' ').replace('\r', ' ')
            val paint = if (rowIndex == 0) headerPaint else textPaint
            val count = paint.breakText(value, true, columnWidth - 8f, null)
            canvas.drawText(value.take(count), left + 4f, y + 16f, paint)
        }
        y += cellHeight
    }
    drawFooter()
    document.finishPage(page)
    document.writeTo(output)
    document.close()
}

private fun writeTextPdf(text: String, title: String, output: java.io.OutputStream) {
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val margin = 40f
    val lineHeight = 18f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = 12f
    }
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.DKGRAY
        textSize = 10f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.DKGRAY
        textSize = 9f
    }
    var pageNumber = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var canvas = page.canvas
    canvas.drawText(title.take(70), margin, 25f, headerPaint)
    var y = margin + 12f
    fun drawFooter() {
        canvas.drawText("Page $pageNumber", pageWidth - 80f, pageHeight - 18f, footerPaint)
    }
    fun nextPage() {
        drawFooter()
        document.finishPage(page)
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page.canvas
        canvas.drawText(title.take(70), margin, 25f, headerPaint)
        y = margin + 12f
    }
    text.split('\n').forEach { paragraph ->
        if (paragraph.isEmpty()) {
            if (y + lineHeight > pageHeight - margin) nextPage()
            y += lineHeight
        } else {
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                if (y + lineHeight > pageHeight - margin) nextPage()
                val count = paint.breakText(remaining, true, pageWidth - 2 * margin, null).coerceAtLeast(1)
                canvas.drawText(remaining.substring(0, count), margin, y, paint)
                remaining = remaining.substring(count)
                y += lineHeight
            }
        }
    }
    drawFooter()
    document.finishPage(page)
    document.writeTo(output)
    document.close()
}

private fun compareCsvValues(first: String, second: String, numeric: Boolean): Int {
    if (numeric) {
        val a = first.trim().toDoubleOrNull()
        val b = second.trim().toDoubleOrNull()
        if (a != null && b != null) return a.compareTo(b)
    }
    return first.compareTo(second, ignoreCase = true)
}

private fun parseCsv(content: String): List<List<String>> {
    if (content.isEmpty()) return listOf(listOf(""))
    val rows = mutableListOf<MutableList<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var index = 0
    while (index < content.length) {
        val ch = content[index]
        when {
            ch == '"' && quoted && index + 1 < content.length && content[index + 1] == '"' -> {
                cell.append('"')
                index++
            }
            ch == '"' -> quoted = !quoted
            ch == ',' && !quoted -> {
                row.add(cell.toString())
                cell.setLength(0)
            }
            (ch == '\n' || ch == '\r') && !quoted -> {
                if (ch == '\r' && index + 1 < content.length && content[index + 1] == '\n') index++
                row.add(cell.toString())
                cell.setLength(0)
                rows.add(row)
                row = mutableListOf()
            }
            else -> cell.append(ch)
        }
        index++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row.add(cell.toString())
        rows.add(row)
    }
    return rows.ifEmpty { listOf(listOf("")) }
}

private fun serializeCsv(rows: List<List<String>>): String =
    rows.joinToString("\n") { row ->
        row.joinToString(",") { value ->
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else value
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



private fun loadPdfBookmarks(context: android.content.Context, uri: String): Set<Int> {
    return runCatching {
        val json = context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
            .getString("pdf_bookmarks:" + uri, "[]") ?: "[]"
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { array.optInt(it, -1).takeIf { page -> page >= 0 } }.toSet()
    }.getOrDefault(emptySet())
}

private fun savePdfBookmarks(context: android.content.Context, uri: String, pages: Set<Int>) {
    val array = JSONArray()
    pages.sorted().forEach { array.put(it) }
    context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
        .edit().putString("pdf_bookmarks:" + uri, array.toString()).apply()
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
