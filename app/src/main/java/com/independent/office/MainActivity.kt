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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RecentDocument(val uri: String, val name: String, val isPdf: Boolean)

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

    fun rememberRecent(uri: Uri, name: String, pdf: Boolean) {
        val updated = (listOf(RecentDocument(uri.toString(), name, pdf)) + recentFiles.filterNot { it.uri == uri.toString() }).take(8)
        recentFiles = updated
        saveRecentDocuments(context, updated)
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
                status = if (saved.isSuccess) "Copy saved successfully." else "Save failed: ${saved.exceptionOrNull()?.message}"
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
        Button(onClick = { openLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Open file")
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(fileName, fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (recentFiles.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    recentFiles.forEach { recent ->
                        OutlinedButton(
                            onClick = { openDocument(Uri.parse(recent.uri)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text((if (recent.isPdf) "PDF · " else "FILE · ") + recent.name, maxLines = 1)
                        }
                    }
                    TextButton(onClick = {
                        recentFiles = emptyList()
                        saveRecentDocuments(context, emptyList())
                        status = "Recent-file history cleared."
                    }) { Text("Clear history") }
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
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "PDF page ${pdfPage + 1}",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
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
            OutlinedTextField(
                value = editorText,
                onValueChange = {
                    editorText = it
                    selectedUri?.let { uri -> if (!isPdf) saveDraft(context, uri, it) }
                },
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
                OutlinedButton(onClick = { createLauncher.launch("document.txt") }, modifier = Modifier.weight(1f)) {
                    Text("Save copy")
                }
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
                            editorText = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE).replace(editorText) { replacementText }
                            val changed = before != editorText
                            if (changed) selectedUri?.let { uri -> if (!isPdf) saveDraft(context, uri, editorText) }
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
            RecentDocument(uri, item.optString("name", "Document"), item.optBoolean("isPdf"))
        }.take(8)
    }.getOrDefault(emptyList())
}

private fun saveRecentDocuments(context: android.content.Context, documents: List<RecentDocument>) {
    val array = JSONArray()
    documents.forEach { document ->
        array.put(JSONObject().put("uri", document.uri).put("name", document.name).put("isPdf", document.isPdf))
    }
    context.getSharedPreferences("office_independent", android.content.Context.MODE_PRIVATE)
        .edit().putString("recent_documents", array.toString()).apply()
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
