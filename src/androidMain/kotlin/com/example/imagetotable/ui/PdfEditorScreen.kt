package com.example.imagetotable.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.imagetotable.ocr.AndroidOcrService
import com.example.imagetotable.util.PageNumberPosition
import com.example.imagetotable.util.PageRanges
import com.example.imagetotable.util.PdfEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

data class RichPdfTextElement(
    val id: String = UUID.randomUUID().toString(),
    var text: String = "Double tap to edit",
    var xOffset: Float = 40f,
    var yOffset: Float = 40f,
    var fontSize: Float = 14f,
    var isBold: Boolean = false,
    var isItalic: Boolean = false,
    var textColor: Color = Color.Black,
    var backgroundColor: Color = Color.Transparent,
    var isWhiteout: Boolean = false,
    var width: Float = 140f,
    var height: Float = 36f
) {
    fun copyElement(): RichPdfTextElement {
        return RichPdfTextElement(
            id = id,
            text = text,
            xOffset = xOffset,
            yOffset = yOffset,
            fontSize = fontSize,
            isBold = isBold,
            isItalic = isItalic,
            textColor = textColor,
            backgroundColor = backgroundColor,
            isWhiteout = isWhiteout,
            width = width,
            height = height
        )
    }
}

data class DetectedWordBox(
    val id: String = UUID.randomUUID().toString(),
    val word: String,
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float
)

data class EditablePdfPage(
    val pageIndex: Int,
    var baseBitmap: Bitmap,
    val elements: MutableList<RichPdfTextElement> = mutableListOf(),
    val detectedWords: MutableList<DetectedWordBox> = mutableListOf()
)

data class CanvasSnapshot(
    val pageIndex: Int,
    val elements: List<RichPdfTextElement>
)

class PdfEditorState {
    var activeEditor by mutableStateOf<PdfEditor?>(null)
    val pages = mutableStateListOf<EditablePdfPage>()
    var activePageIndex by mutableIntStateOf(0)
    var activeElementId by mutableStateOf<String?>(null)
    var isProcessing by mutableStateOf(false)
    var statusText by mutableStateOf("Ready: Open any PDF to edit inline words, letters & rich text")

    var zoomScale by mutableFloatStateOf(1f)
    var panOffsetX by mutableFloatStateOf(0f)
    var panOffsetY by mutableFloatStateOf(0f)

    val canvasUndoStack = mutableStateListOf<CanvasSnapshot>()
    val canvasRedoStack = mutableStateListOf<CanvasSnapshot>()

    var isInlineWordEditMode by mutableStateOf(false)
    var wordSearchFilter by mutableStateOf("")
    var editingWordBox by mutableStateOf<DetectedWordBox?>(null)
    var isNoteBoxMinimized by mutableStateOf(false)
    var liveWordText by mutableStateOf("")
    var liveWordFontSize by mutableFloatStateOf(14f)
    var liveWordIsBold by mutableStateOf(false)
    var liveWordIsItalic by mutableStateOf(false)
    var liveWordColor by mutableStateOf(Color(0xFF292524)) // Default to natural carbon toner
    var liveWordOpacity by mutableFloatStateOf(0.85f)      // Default to 85% opacity to match PDF bleed

    fun reset() {
        activeEditor?.close()
        activeEditor = null
        pages.clear()
        activePageIndex = 0
        activeElementId = null
        isProcessing = false
        statusText = "Ready: Open any PDF to edit inline words, letters & rich text"
        zoomScale = 1f
        panOffsetX = 0f
        panOffsetY = 0f
        canvasUndoStack.clear()
        canvasRedoStack.clear()
        isInlineWordEditMode = false
        wordSearchFilter = ""
        editingWordBox = null
        isNoteBoxMinimized = false
        liveWordText = ""
        liveWordOpacity = 0.85f
    }
}

object PdfEditorStateManager {
    val state = PdfEditorState()
}

// Curated ink palettes designed to match real document printing
val InkShadePalette = listOf(
    Pair("Jet Black", Color(0xFF000000)),
    Pair("Charcoal", Color(0xFF1C1917)),
    Pair("Carbon Toner", Color(0xFF292524)),
    Pair("Faded Ink", Color(0xFF44403C)),
    Pair("Graphite", Color(0xFF57534E)),
    Pair("Blue-Black", Color(0xFF1E293B)),
    Pair("Royal Blue", Color(0xFF1565C0)),
    Pair("Red Ink", Color(0xFFC62828))
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfEditorScreen(
    state: PdfEditorState = PdfEditorStateManager.state
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // Dialog Visibilities
    var showTextEditDialog by remember { mutableStateOf(false) }
    var editingTextValue by remember { mutableStateOf("") }
    var editingFontSize by remember { mutableFloatStateOf(14f) }
    var editingIsBold by remember { mutableStateOf(false) }
    var editingIsItalic by remember { mutableStateOf(false) }
    var editingColor by remember { mutableStateOf(Color(0xFF292524)) }
    var editingOpacity by remember { mutableFloatStateOf(0.85f) }
    var editingBgColor by remember { mutableStateOf(Color.Transparent) }
    var editingIsWhiteout by remember { mutableStateOf(false) }

    var showFourCornerCropDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var splitPagesPerFile by remember { mutableStateOf("1") }
    var splitRangeSpec by remember { mutableStateOf("1-2, 3-last") }
    var splitModeByRanges by remember { mutableStateOf(false) }

    var showDeletePagesDialog by remember { mutableStateOf(false) }
    var deletePagesSpecInput by remember { mutableStateOf("") }

    var showReorderPagesDialog by remember { mutableStateOf(false) }
    var reorderPermutationInput by remember { mutableStateOf("") }

    var showExtractDialog by remember { mutableStateOf(false) }
    var extractPagesSpec by remember { mutableStateOf("1-3, last") }

    var showWatermarkDialog by remember { mutableStateOf(false) }
    var watermarkInput by remember { mutableStateOf("CONFIDENTIAL") }

    var showPageNumbersDialog by remember { mutableStateOf(false) }
    var pageNumberFormatInput by remember { mutableStateOf("Page {n} of {total}") }

    val activePage = state.pages.getOrNull(state.activePageIndex)
    val activeElement = activePage?.elements?.find { it.id == state.activeElementId }

    fun pushCanvasSnapshot() {
        activePage?.let { page ->
            state.canvasUndoStack.add(
                CanvasSnapshot(
                    pageIndex = state.activePageIndex,
                    elements = page.elements.map { it.copyElement() }
                )
            )
            if (state.canvasUndoStack.size > 25) state.canvasUndoStack.removeAt(0)
            state.canvasRedoStack.clear()
        }
    }

    fun refreshFromEngine(editor: PdfEditor) {
        state.pages.clear()
        for (i in 1..editor.pageCount) {
            val bmp = editor.renderPage(i, 150f)
            state.pages.add(EditablePdfPage(pageIndex = i - 1, baseBitmap = bmp))
        }
        if (state.activePageIndex >= state.pages.size) {
            state.activePageIndex = (state.pages.size - 1).coerceAtLeast(0)
        }
    }

    fun undoLastAction() {
        if (state.canvasUndoStack.isNotEmpty()) {
            val snapshot = state.canvasUndoStack.removeAt(state.canvasUndoStack.size - 1)
            activePage?.let { page ->
                state.canvasRedoStack.add(
                    CanvasSnapshot(
                        pageIndex = state.activePageIndex,
                        elements = page.elements.map { it.copyElement() }
                    )
                )
                page.elements.clear()
                page.elements.addAll(snapshot.elements.map { it.copyElement() })
                state.activeElementId = null
                state.statusText = "Undid last overlay action."
            }
        } else if (state.activeEditor?.canUndo == true) {
            state.activeEditor?.let { editor ->
                if (editor.undo()) {
                    refreshFromEngine(editor)
                    state.statusText = "Undid last page operation."
                }
            }
        }
    }

    fun redoLastAction() {
        if (state.canvasRedoStack.isNotEmpty()) {
            val snapshot = state.canvasRedoStack.removeAt(state.canvasRedoStack.size - 1)
            activePage?.let { page ->
                state.canvasUndoStack.add(
                    CanvasSnapshot(
                        pageIndex = state.activePageIndex,
                        elements = page.elements.map { it.copyElement() }
                    )
                )
                page.elements.clear()
                page.elements.addAll(snapshot.elements.map { it.copyElement() })
                state.activeElementId = null
                state.statusText = "Redid action."
            }
        } else if (state.activeEditor?.canRedo == true) {
            state.activeEditor?.let { editor ->
                if (editor.redo()) {
                    refreshFromEngine(editor)
                    state.statusText = "Redid page operation."
                }
            }
        }
    }

    fun scanCurrentPageWords() {
        activePage?.let { page ->
            coroutineScope.launch {
                state.isProcessing = true
                state.statusText = "Scanning page text for word editor..."
                try {
                    val words = withContext(Dispatchers.IO) {
                        val ocr = AndroidOcrService(context) { /* status */ }
                        val rawTokens = ocr.extractTokens(page.baseBitmap)
                        val list = mutableListOf<DetectedWordBox>()
                        var curX = 35f
                        var curY = 35f
                        rawTokens.forEach { token ->
                            val tw = (token.length * 8.5f).coerceAtLeast(22f)
                            val th = 20f
                            if (curX + tw > page.baseBitmap.width - 40f) {
                                curX = 35f
                                curY += 28f
                            }
                            list.add(DetectedWordBox(word = token, x = curX, y = curY, width = tw, height = th))
                            curX += tw + 6f
                        }
                        list
                    }
                    page.detectedWords.clear()
                    page.detectedWords.addAll(words)
                    state.isInlineWordEditMode = true
                    state.statusText = "Detected ${words.size} word(s). Tap any word below to edit or erase."
                } catch (e: Exception) {
                    state.statusText = "Word scan error: ${e.message}"
                } finally {
                    state.isProcessing = false
                }
            }
        }
    }

    // Launchers
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                state.isProcessing = true
                state.statusText = "Opening PDF..."
                try {
                    val tempSource = withContext(Dispatchers.IO) {
                        val file = File(context.cacheDir, "editor_src_${System.currentTimeMillis()}.pdf")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(file).use { out -> input.copyTo(out) }
                        }
                        file
                    }
                    state.activeEditor?.close()
                    val editor = PdfEditor.load(context, tempSource)
                    state.activeEditor = editor
                    refreshFromEngine(editor)
                    state.activePageIndex = 0
                    state.activeElementId = null
                    state.editingWordBox = null
                    state.canvasUndoStack.clear()
                    state.canvasRedoStack.clear()
                    state.zoomScale = 1f
                    state.panOffsetX = 0f
                    state.panOffsetY = 0f
                    state.statusText = "Loaded ${editor.pageCount} page(s). Ready to edit."
                } catch (e: Exception) {
                    state.statusText = "Open error: ${e.message}"
                } finally {
                    state.isProcessing = false
                }
            }
        }
    }

    var pendingMergeUris by remember { mutableStateOf<List<Uri>?>(null) }
    val mergePdfSaveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        if (destUri != null && pendingMergeUris != null) {
            coroutineScope.launch {
                state.isProcessing = true
                state.statusText = "Merging & saving PDFs..."
                try {
                    withContext(Dispatchers.IO) {
                        val tempInputs = pendingMergeUris!!.mapIndexed { i, u ->
                            val f = File(context.cacheDir, "merge_in_${i}_${System.currentTimeMillis()}.pdf")
                            context.contentResolver.openInputStream(u)?.use { inp ->
                                FileOutputStream(f).use { out -> inp.copyTo(out) }
                            }
                            f
                        }
                        val tempMerged = File(context.cacheDir, "Merged_${System.currentTimeMillis()}.pdf")
                        PdfEditor.merge(context, tempInputs, tempMerged)
                        tempInputs.forEach { it.delete() }

                        context.contentResolver.openOutputStream(destUri)?.use { out ->
                            tempMerged.inputStream().use { inp -> inp.copyTo(out) }
                        }
                        tempMerged
                    }
                    pendingMergeUris = null
                    state.statusText = "Merged PDF saved successfully!"
                    Toast.makeText(context, "Merged PDF saved successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    state.statusText = "Merge error: ${e.message}"
                } finally {
                    state.isProcessing = false
                }
            }
        }
    }

    val mergeMultiPdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.size >= 2) {
            pendingMergeUris = uris
            mergePdfSaveAsLauncher.launch("Merged_Document.pdf")
        } else if (uris.isNotEmpty()) {
            Toast.makeText(context, "Please select at least 2 PDFs to merge", Toast.LENGTH_SHORT).show()
        }
    }

    var pendingSplitZipFile by remember { mutableStateOf<File?>(null) }
    val splitZipSaveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { destUri: Uri? ->
        if (destUri != null && pendingSplitZipFile != null) {
            coroutineScope.launch {
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        pendingSplitZipFile!!.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    pendingSplitZipFile!!.delete()
                    pendingSplitZipFile = null
                    state.statusText = "Saved split PDFs archive!"
                    Toast.makeText(context, "Saved split PDFs archive (.zip)!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    state.statusText = "Save error: ${e.message}"
                }
            }
        }
    }

    val pdfSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        if (destUri != null && state.activeEditor != null) {
            coroutineScope.launch {
                state.isProcessing = true
                state.statusText = "Compiling modified PDF..."
                try {
                    val editor = state.activeEditor!!
                    state.pages.forEachIndexed { pIdx, page ->
                        page.elements.forEach { elem ->
                            if (elem.isWhiteout) {
                                editor.addRectangle(
                                    page = pIdx + 1,
                                    x = elem.xOffset,
                                    y = elem.yOffset,
                                    width = elem.width,
                                    height = elem.height,
                                    fill = android.graphics.Color.WHITE,
                                    stroke = null
                                )
                            } else {
                                editor.addText(
                                    page = pIdx + 1,
                                    text = elem.text,
                                    x = elem.xOffset,
                                    y = elem.yOffset + elem.fontSize,
                                    fontSize = elem.fontSize,
                                    color = elem.textColor.toArgb(),
                                    isBold = elem.isBold,
                                    isItalic = elem.isItalic
                                )
                            }
                        }
                    }

                    withContext(Dispatchers.IO) {
                        val tempOut = File(context.cacheDir, "compiled_${System.currentTimeMillis()}.pdf")
                        editor.save(tempOut)
                        context.contentResolver.openOutputStream(destUri)?.use { outStream ->
                            tempOut.inputStream().use { inStream -> inStream.copyTo(outStream) }
                        }
                        tempOut.delete()
                    }
                    state.statusText = "Saved edited PDF successfully!"
                    Toast.makeText(context, "Saved Edited PDF!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    state.statusText = "Save error: ${e.message}"
                } finally {
                    state.isProcessing = false
                }
            }
        }
    }

    val pdfExtractSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        if (destUri != null && state.activeEditor != null) {
            coroutineScope.launch {
                state.isProcessing = true
                state.statusText = "Extracting pages ($extractPagesSpec)..."
                try {
                    withContext(Dispatchers.IO) {
                        val tempExtract = File(context.cacheDir, "extract_${System.currentTimeMillis()}.pdf")
                        state.activeEditor!!.extractPages(extractPagesSpec, tempExtract)
                        context.contentResolver.openOutputStream(destUri)?.use { out ->
                            tempExtract.inputStream().use { inp -> inp.copyTo(out) }
                        }
                        tempExtract.delete()
                    }
                    state.statusText = "Extracted pages successfully!"
                    Toast.makeText(context, "Pages extracted successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    state.statusText = "Extract error: ${e.message}"
                } finally {
                    state.isProcessing = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(Color(0xFFF1F5F9))
    ) {
        // TOP CONTROL HEADER
        Surface(
            modifier = Modifier.fillMaxWidth(),
            elevation = 3.dp,
            color = Color(0xFF0D47A1)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.editingWordBox != null) "🎯 Editing Word: '${state.editingWordBox?.word}'" else "✍️ PDF Studio: Letter & Word Editor",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.statusText,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.editingWordBox == null) {
                        Button(
                            onClick = { pdfPickerLauncher.launch("application/pdf") },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("📂 Open", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { mergeMultiPdfPickerLauncher.launch("application/pdf") },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00796B)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("🔀 Merge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { pdfSaveLauncher.launch("Edited_Document.pdf") },
                            enabled = state.pages.isNotEmpty() && !state.isProcessing,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF15803D)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("💾 Save", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { state.editingWordBox = null },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("✕ Cancel Edit", color = Color.White, fontSize = 10.sp)
                        }
                    }

                    if (state.pages.isNotEmpty()) {
                        Button(
                            onClick = { state.reset() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("✕", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // TOOLBAR
        AnimatedVisibility(visible = state.editingWordBox == null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp,
                color = Color.White
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val canUndoAction = state.canvasUndoStack.isNotEmpty() || state.activeEditor?.canUndo == true
                    val canRedoAction = state.canvasRedoStack.isNotEmpty() || state.activeEditor?.canRedo == true

                    Button(
                        onClick = { undoLastAction() },
                        enabled = canUndoAction,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (canUndoAction) Color(0xFF1E88E5) else Color(0xFFECEFF1)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("↩ Undo", fontSize = 10.sp, color = if (canUndoAction) Color.White else Color.Gray, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { redoLastAction() },
                        enabled = canRedoAction,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (canRedoAction) Color(0xFF1E88E5) else Color(0xFFECEFF1)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("↪ Redo", fontSize = 10.sp, color = if (canRedoAction) Color.White else Color.Gray, fontWeight = FontWeight.Bold)
                    }

                    if (activeElement != null) {
                        Button(
                            onClick = {
                                pushCanvasSnapshot()
                                activePage?.elements?.removeAll { it.id == state.activeElementId }
                                state.activeElementId = null
                                state.statusText = "Deleted selected element."
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("🗑 Delete Box", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            deletePagesSpecInput = (state.activePageIndex + 1).toString()
                            showDeletePagesDialog = true
                        },
                        enabled = state.pages.size > 1,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB71C1C)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("🗑 Delete Page(s)", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            reorderPermutationInput = (1..state.pages.size).joinToString(", ")
                            showReorderPagesDialog = true
                        },
                        enabled = state.pages.size > 1,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4A148C)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("🔀 Reorder", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            if (!state.isInlineWordEditMode) {
                                scanCurrentPageWords()
                            } else {
                                state.isInlineWordEditMode = false
                                state.editingWordBox = null
                                state.statusText = "Exited inline word edit mode."
                            }
                        },
                        enabled = activePage != null,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (state.isInlineWordEditMode) Color(0xFF00897B) else Color(0xFF1565C0)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = if (state.isInlineWordEditMode) "✓ Words Active" else "🔤 Edit/Erase Words",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            activeElement?.let {
                                clipboardManager.setText(AnnotatedString(it.text))
                                pushCanvasSnapshot()
                                it.text = ""
                                state.statusText = "Cut text to clipboard."
                            }
                        },
                        enabled = activeElement != null && !activeElement.isWhiteout,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE2E8F0)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("✂ Cut", fontSize = 10.sp, color = Color.Black) }

                    Button(
                        onClick = {
                            activeElement?.let {
                                clipboardManager.setText(AnnotatedString(it.text))
                                state.statusText = "Copied text to clipboard."
                            }
                        },
                        enabled = activeElement != null && !activeElement.isWhiteout,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE2E8F0)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("📋 Copy", fontSize = 10.sp, color = Color.Black) }

                    Button(
                        onClick = {
                            val clip = clipboardManager.getText()?.text.orEmpty()
                            if (clip.isNotBlank()) {
                                pushCanvasSnapshot()
                                activeElement?.let {
                                    it.text += " $clip"
                                } ?: run {
                                    activePage?.elements?.add(
                                        RichPdfTextElement(text = clip, xOffset = 60f, yOffset = 120f)
                                    )
                                }
                                state.statusText = "Pasted text."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE2E8F0)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("📌 Paste", fontSize = 10.sp, color = Color.Black) }

                    Button(
                        onClick = {
                            activePage?.let { page ->
                                pushCanvasSnapshot()
                                val newElem = RichPdfTextElement(
                                    text = "New Text",
                                    xOffset = 50f,
                                    yOffset = 100f
                                )
                                page.elements.add(newElem)
                                state.activeElementId = newElem.id
                                editingTextValue = newElem.text
                                editingFontSize = newElem.fontSize
                                editingIsBold = newElem.isBold
                                editingIsItalic = newElem.isItalic
                                editingColor = Color(0xFF292524)
                                editingOpacity = 0.85f
                                editingBgColor = newElem.backgroundColor
                                editingIsWhiteout = false
                                showTextEditDialog = true
                            }
                        },
                        enabled = activePage != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("➕ Text", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = {
                            activePage?.let { page ->
                                pushCanvasSnapshot()
                                val whiteout = RichPdfTextElement(
                                    text = "",
                                    isWhiteout = true,
                                    backgroundColor = Color.White,
                                    width = 140f,
                                    height = 36f,
                                    xOffset = 50f,
                                    yOffset = 80f
                                )
                                page.elements.add(whiteout)
                                state.activeElementId = whiteout.id
                                state.statusText = "Added Whiteout cover."
                            }
                        },
                        enabled = activePage != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("⬜ Whiteout", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = { showFourCornerCropDialog = true },
                        enabled = activePage != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("✂ Crop", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = { showSplitDialog = true },
                        enabled = state.activeEditor != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("✂ Split", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = { showExtractDialog = true },
                        enabled = state.activeEditor != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("📄 Extract", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = { showWatermarkDialog = true },
                        enabled = state.activeEditor != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("💧 Watermark", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = { showPageNumbersDialog = true },
                        enabled = state.activeEditor != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("🔢 Page #s", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = {
                            state.activeEditor?.let {
                                it.rotate((state.activePageIndex + 1).toString(), 90)
                                refreshFromEngine(it)
                            }
                        },
                        enabled = state.activeEditor != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("🔄 90°", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = {
                            state.activeEditor?.let {
                                it.duplicatePage(state.activePageIndex + 1, 1)
                                refreshFromEngine(it)
                            }
                        },
                        enabled = state.activeEditor != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("📄 Duplicate", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = {
                            state.activeEditor?.let {
                                it.insertBlankPage(at = state.activePageIndex + 2)
                                refreshFromEngine(it)
                            }
                        },
                        enabled = state.activeEditor != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("➕ Blank", color = Color.White, fontSize = 10.sp) }
                }
            }
        }

        // ELEMENT GEOMETRY & RESIZE TOOLBAR
        if (activeElement != null && state.editingWordBox == null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFE8EAF6),
                elevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (activeElement.isWhiteout) "⬜ Whiteout:" else "✏️ Text Box:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF283593)
                    )

                    Text("Move:", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Button(onClick = { activeElement.xOffset -= 4f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("◀", fontSize = 10.sp) }
                    Button(onClick = { activeElement.xOffset += 4f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("▶", fontSize = 10.sp) }
                    Button(onClick = { activeElement.yOffset -= 4f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("▲", fontSize = 10.sp) }
                    Button(onClick = { activeElement.yOffset += 4f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("▼", fontSize = 10.sp) }

                    Spacer(modifier = Modifier.width(4.dp))

                    Text("Size:", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Button(onClick = { activeElement.width = (activeElement.width - 10f).coerceAtLeast(20f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("W-", fontSize = 9.sp) }
                    Button(onClick = { activeElement.width += 10f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("W+", fontSize = 9.sp) }
                    Button(onClick = { activeElement.height = (activeElement.height - 6f).coerceAtLeast(10f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("H-", fontSize = 9.sp) }
                    Button(onClick = { activeElement.height += 6f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("H+", fontSize = 9.sp) }

                    if (!activeElement.isWhiteout) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${activeElement.fontSize.toInt()}sp", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { activeElement.fontSize = (activeElement.fontSize - 1f).coerceAtLeast(1f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(24.dp)) { Text("-", fontSize = 10.sp) }
                        Button(onClick = { activeElement.fontSize = (activeElement.fontSize + 1f).coerceAtMost(72f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(24.dp)) { Text("+", fontSize = 10.sp) }
                    }

                    Button(
                        onClick = {
                            pushCanvasSnapshot()
                            activePage?.elements?.removeAll { it.id == activeElement.id }
                            state.activeElementId = null
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("🗑 Delete", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ZOOMABLE & PANNABLE PAGE CANVAS (GUARANTEED MINIMUM HEIGHT, NEVER INVISIBLE)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 220.dp)
                .fillMaxWidth()
                .padding(4.dp)
                .clipToBounds()
                .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan: Offset, zoom: Float, _ ->
                        state.zoomScale = (state.zoomScale * zoom).coerceIn(0.5f, 6.0f)
                        val maxPan = 1000f * (state.zoomScale - 1f).coerceAtLeast(0f)
                        state.panOffsetX = (state.panOffsetX + pan.x).coerceIn(-maxPan, maxPan)
                        state.panOffsetY = (state.panOffsetY + pan.y).coerceIn(-maxPan, maxPan)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val containerWidth = maxWidth.value
            val containerHeight = maxHeight.value

            if (activePage != null) {
                val bmpW = activePage.baseBitmap.width.toFloat().coerceAtLeast(1f)
                val bmpH = activePage.baseBitmap.height.toFloat().coerceAtLeast(1f)
                val renderScale = minOf(containerWidth / bmpW, containerHeight / bmpH)
                val renderedW = bmpW * renderScale
                val renderedH = bmpH * renderScale
                val originX = (containerWidth - renderedW) / 2f
                val originY = (containerHeight - renderedH) / 2f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = state.zoomScale,
                            scaleY = state.zoomScale,
                            translationX = state.panOffsetX,
                            translationY = state.panOffsetY
                        )
                ) {
                    // Base Page Image
                    Image(
                        bitmap = activePage.baseBitmap.asImageBitmap(),
                        contentDescription = "Page ${state.activePageIndex + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    // LIVE VISUAL LOCATOR & PREVIEW ON TARGET WORD
                    if (state.editingWordBox != null) {
                        val wordBox = state.editingWordBox!!
                        val targetScreenX = originX + wordBox.x * renderScale
                        val targetScreenY = originY + wordBox.y * renderScale
                        val targetScreenW = (wordBox.width * renderScale).coerceAtLeast(14f)
                        val targetScreenH = (wordBox.height * renderScale).coerceAtLeast(12f)

                        // 1. High-Visibility Pulsing Glowing Frame around the Word
                        Box(
                            modifier = Modifier
                                .offset { IntOffset((targetScreenX - 4f).roundToInt(), (targetScreenY - 4f).roundToInt()) }
                                .size((targetScreenW + 8f).dp, (targetScreenH + 8f).dp)
                                .border(2.dp, Color(0xFFFFD600), RoundedCornerShape(4.dp))
                                .background(Color(0x22FFD600), RoundedCornerShape(4.dp))
                        ) {
                            Text(
                                text = "🎯 EDITING HERE",
                                color = Color(0xFFFFD600),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = (-14).dp)
                                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                                    .padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }

                        // 2. Exact Whiteout Mask Covering the Original Word
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(targetScreenX.roundToInt(), targetScreenY.roundToInt()) }
                                .size(targetScreenW.dp, targetScreenH.dp)
                                .background(Color.White)
                        )

                        // 3. Live Replacement Text Rendered Directly on Top of Original Document with Opacity Matching
                        Text(
                            text = state.liveWordText,
                            fontSize = (state.liveWordFontSize * renderScale * 1.3f).coerceAtLeast(8f).sp,
                            fontWeight = if (state.liveWordIsBold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (state.liveWordIsItalic) FontStyle.Italic else FontStyle.Normal,
                            color = state.liveWordColor.copy(alpha = state.liveWordOpacity),
                            modifier = Modifier
                                .offset { IntOffset(targetScreenX.roundToInt(), targetScreenY.roundToInt()) }
                        )
                    }

                    // Regular Text & Whiteout Overlays
                    activePage.elements.forEach { element ->
                        val isSelected = element.id == state.activeElementId
                        var offsetX by remember(element.id) { mutableFloatStateOf(element.xOffset) }
                        var offsetY by remember(element.id) { mutableFloatStateOf(element.yOffset) }
                        var elWidth by remember(element.id) { mutableFloatStateOf(element.width) }
                        var elHeight by remember(element.id) { mutableFloatStateOf(element.height) }

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                                .size(elWidth.dp, elHeight.dp)
                                .pointerInput(element.id) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        offsetX += dragAmount.x
                                        offsetY += dragAmount.y
                                        element.xOffset = offsetX
                                        element.yOffset = offsetY
                                    }
                                }
                                .clickable { state.activeElementId = element.id }
                                .background(
                                    if (element.isWhiteout) Color.White else element.backgroundColor,
                                    RoundedCornerShape(3.dp)
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) Color(0xFF1976D2) else if (element.isWhiteout) Color.LightGray else Color.Transparent,
                                    shape = RoundedCornerShape(3.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            if (!element.isWhiteout) {
                                Text(
                                    text = element.text.ifBlank { " " },
                                    fontSize = element.fontSize.sp,
                                    fontWeight = if (element.isBold) FontWeight.Bold else FontWeight.Normal,
                                    fontStyle = if (element.isItalic) FontStyle.Italic else FontStyle.Normal,
                                    color = element.textColor,
                                    modifier = Modifier.clickable {
                                        state.activeElementId = element.id
                                        editingTextValue = element.text
                                        editingFontSize = element.fontSize
                                        editingIsBold = element.isBold
                                        editingIsItalic = element.isItalic
                                        editingColor = element.textColor
                                        editingOpacity = element.textColor.alpha
                                        editingBgColor = element.backgroundColor
                                        editingIsWhiteout = false
                                        showTextEditDialog = true
                                    }
                                )
                            }

                            // DELETE ✕ BADGE ON TOP-RIGHT CORNER
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 8.dp, y = (-8).dp)
                                        .size(20.dp)
                                        .background(Color.Red, CircleShape)
                                        .clickable {
                                            pushCanvasSnapshot()
                                            activePage.elements.removeAll { it.id == element.id }
                                            state.activeElementId = null
                                            state.statusText = "Deleted element."
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✕", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                // DRAGGABLE CORNER RESIZE HANDLE ON BOTTOM-RIGHT
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(x = 8.dp, y = 8.dp)
                                        .size(18.dp)
                                        .background(Color(0xFF1976D2), RoundedCornerShape(3.dp))
                                        .border(1.dp, Color.White, RoundedCornerShape(3.dp))
                                        .pointerInput(element.id) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                elWidth = (elWidth + dragAmount.x).coerceAtLeast(20f)
                                                elHeight = (elHeight + dragAmount.y).coerceAtLeast(12f)
                                                element.width = elWidth
                                                element.height = elHeight
                                            }
                                        }
                                )
                            }
                        }
                    }
                }

                // Floating Zoom & Reset Buttons
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { state.zoomScale = (state.zoomScale * 1.25f).coerceAtMost(6.0f) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("🔍+", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = { state.zoomScale = (state.zoomScale / 1.25f).coerceAtLeast(0.5f) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("🔍-", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = {
                            state.zoomScale = 1f
                            state.panOffsetX = 0f
                            state.panOffsetY = 0f
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("Fit", color = Color.White, fontSize = 10.sp) }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "No PDF loaded. Open a document to begin letter editing, zoom, crop & split.",
                        color = Color.White,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { pdfPickerLauncher.launch("application/pdf") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                    ) {
                        Text("📂 Select PDF Document", color = Color.White)
                    }
                }
            }
        }

        // SEPARATE DOCKED UI: DETECTED WORDS SHELF & LIVE RESIZABLE NOTE BOX WITH INK MATCHING
        if (state.isInlineWordEditMode && activePage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                shape = RoundedCornerShape(10.dp),
                elevation = 6.dp,
                backgroundColor = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .heightIn(max = if (state.isNoteBoxMinimized) 44.dp else 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (state.editingWordBox == null) {
                        // Word list view
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔤 Detected Words (${activePage.detectedWords.size}) - Tap Word to Edit/Erase",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color(0xFF00796B)
                            )
                            IconButton(onClick = { state.isInlineWordEditMode = false }, modifier = Modifier.size(20.dp)) {
                                Text("✕", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            }
                        }

                        OutlinedTextField(
                            value = state.wordSearchFilter,
                            onValueChange = { state.wordSearchFilter = it },
                            placeholder = { Text("Search word on page...", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 11.sp)
                        )

                        val filteredWords = activePage.detectedWords.filter {
                            state.wordSearchFilter.isBlank() || it.word.contains(state.wordSearchFilter, ignoreCase = true)
                        }

                        if (filteredWords.isEmpty()) {
                            Text("No matching words.", fontSize = 11.sp, color = Color.Gray)
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                filteredWords.forEach { wordBox ->
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFE0F2F1),
                                        border = BorderStroke(0.5.dp, Color(0xFF00897B)),
                                        modifier = Modifier.clickable {
                                            state.editingWordBox = wordBox
                                            state.liveWordText = wordBox.word
                                            state.liveWordFontSize = 14f
                                            state.liveWordIsBold = false
                                            state.liveWordIsItalic = false
                                            state.liveWordColor = Color(0xFF292524) // Natural carbon tone
                                            state.liveWordOpacity = 0.85f           // Matched print bleed
                                            state.isNoteBoxMinimized = false

                                            // Automatically pan & zoom directly to this word on the canvas
                                            state.zoomScale = 1.9f
                                            state.panOffsetX = -(wordBox.x * 0.4f)
                                            state.panOffsetY = -(wordBox.y * 0.35f)
                                        }
                                    ) {
                                        Text(
                                            text = wordBox.word,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF004D40),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // NOTE BOX: REAL-TIME REPLACEMENT & INK MATCHING CONTROLS
                        val target = state.editingWordBox!!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "✏️ Live Word: '${target.word}'",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color(0xFF00796B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { state.isNoteBoxMinimized = !state.isNoteBoxMinimized },
                                    contentPadding = PaddingValues(2.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text(if (state.isNoteBoxMinimized) "🔼 Expand" else "🔽 Minimize", fontSize = 10.sp)
                                }

                                IconButton(onClick = { state.editingWordBox = null }, modifier = Modifier.size(22.dp)) {
                                    Text("✕", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                }
                            }
                        }

                        if (!state.isNoteBoxMinimized) {
                            OutlinedTextField(
                                value = state.liveWordText,
                                onValueChange = { state.liveWordText = it },
                                label = { Text("Replacement Text (Type freely, live preview above)", fontSize = 10.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = state.liveWordFontSize.sp,
                                    fontWeight = if (state.liveWordIsBold) FontWeight.Bold else FontWeight.Normal,
                                    fontStyle = if (state.liveWordIsItalic) FontStyle.Italic else FontStyle.Normal,
                                    color = state.liveWordColor.copy(alpha = state.liveWordOpacity)
                                )
                            )

                            // Position & Box Size Adjustments
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Pos:", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Button(onClick = { target.x -= 2f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("◀", fontSize = 8.sp) }
                                Button(onClick = { target.x += 2f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("▶", fontSize = 8.sp) }
                                Button(onClick = { target.y -= 2f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("▲", fontSize = 8.sp) }
                                Button(onClick = { target.y += 2f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("▼", fontSize = 8.sp) }

                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Box:", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Button(onClick = { target.width = (target.width - 4f).coerceAtLeast(10f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("W-", fontSize = 8.sp) }
                                Button(onClick = { target.width += 4f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("W+", fontSize = 8.sp) }
                                Button(onClick = { target.height = (target.height - 2f).coerceAtLeast(8f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("H-", fontSize = 8.sp) }
                                Button(onClick = { target.height += 2f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("H+", fontSize = 8.sp) }
                            }

                            // INK TONE & OPACITY CONTROLS (REDUCES BLACKNESS TO MATCH SCANNED PDF)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Ink Density:", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                                listOf(
                                    Pair("100%", 1.0f),
                                    Pair("85% (Laser)", 0.85f),
                                    Pair("75% (Scan)", 0.75f),
                                    Pair("60% (Faded)", 0.60f)
                                ).forEach { (label, opacityVal) ->
                                    val isSel = (state.liveWordOpacity * 100).roundToInt() == (opacityVal * 100).roundToInt()
                                    Surface(
                                        shape = RoundedCornerShape(3.dp),
                                        color = if (isSel) Color(0xFF00796B) else Color(0xFFECEFF1),
                                        modifier = Modifier.clickable { state.liveWordOpacity = opacityVal }
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 8.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) Color.White else Color.Black,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            // Ink Shades Palette
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Ink Tone:", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                                InkShadePalette.forEach { (name, col) ->
                                    val isSelected = state.liveWordColor == col
                                    Row(
                                        modifier = Modifier
                                            .background(
                                                if (isSelected) Color(0xFFE0F2F1) else Color.Transparent,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                if (isSelected) 1.dp else 0.dp,
                                                if (isSelected) Color(0xFF00796B) else Color.Transparent,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable { state.liveWordColor = col }
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(col, CircleShape)
                                                .border(0.5.dp, Color.Gray, CircleShape)
                                        )
                                        Text(name, fontSize = 8.sp, color = Color.DarkGray)
                                    }
                                }
                            }

                            // Ultra-Fine Word Font Sizing (Down to 1sp) & Formatting
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { state.liveWordIsBold = !state.liveWordIsBold },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (state.liveWordIsBold) Color(0xFF1976D2) else Color(0xFFECEFF1)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) { Text("B", fontWeight = FontWeight.Bold, color = if (state.liveWordIsBold) Color.White else Color.Black, fontSize = 10.sp) }

                                Button(
                                    onClick = { state.liveWordIsItalic = !state.liveWordIsItalic },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (state.liveWordIsItalic) Color(0xFF1976D2) else Color(0xFFECEFF1)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) { Text("I", fontStyle = FontStyle.Italic, color = if (state.liveWordIsItalic) Color.White else Color.Black, fontSize = 10.sp) }

                                Button(
                                    onClick = { state.liveWordFontSize = (state.liveWordFontSize - 1f).coerceAtLeast(1f) },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(24.dp)
                                ) { Text("-", fontSize = 10.sp) }

                                Text("${state.liveWordFontSize.toInt()}sp", fontSize = 10.sp, fontWeight = FontWeight.Bold)

                                Button(
                                    onClick = { state.liveWordFontSize = (state.liveWordFontSize + 1f).coerceAtMost(72f) },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(24.dp)
                                ) { Text("+", fontSize = 10.sp) }
                            }

                            // Action buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        activePage.let { page ->
                                            pushCanvasSnapshot()
                                            page.elements.add(
                                                RichPdfTextElement(
                                                    text = "",
                                                    isWhiteout = true,
                                                    backgroundColor = Color.White,
                                                    xOffset = target.x,
                                                    yOffset = target.y,
                                                    width = target.width,
                                                    height = target.height
                                                )
                                            )
                                            page.elements.add(
                                                RichPdfTextElement(
                                                    text = state.liveWordText,
                                                    xOffset = target.x,
                                                    yOffset = target.y,
                                                    fontSize = state.liveWordFontSize,
                                                    isBold = state.liveWordIsBold,
                                                    isItalic = state.liveWordIsItalic,
                                                    textColor = state.liveWordColor.copy(alpha = state.liveWordOpacity)
                                                )
                                            )
                                            state.statusText = "Applied replacement '${state.liveWordText}' to page."
                                        }
                                        state.editingWordBox = null
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00796B)),
                                    modifier = Modifier.weight(1.2f).height(32.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) { Text("✓ Confirm", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        activePage.let { page ->
                                            pushCanvasSnapshot()
                                            page.elements.add(
                                                RichPdfTextElement(
                                                    text = "",
                                                    isWhiteout = true,
                                                    backgroundColor = Color.White,
                                                    xOffset = target.x,
                                                    yOffset = target.y,
                                                    width = target.width,
                                                    height = target.height
                                                )
                                            )
                                            state.statusText = "Erased word '${target.word}' from page."
                                        }
                                        state.editingWordBox = null
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                                    modifier = Modifier.weight(0.9f).height(32.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) { Text("🗑 Erase Word", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                                OutlinedButton(
                                    onClick = { state.editingWordBox = null },
                                    modifier = Modifier.weight(0.6f).height(32.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) { Text("Back", fontSize = 10.sp) }
                            }
                        }
                    }
                }
            }
        }

        // BOTTOM PAGE SEQUENCE STRIP
        if (state.pages.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp,
                color = Color.White
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "P. ${state.activePageIndex + 1}/${state.pages.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )

                        Button(
                            onClick = {
                                val cur = state.activePageIndex
                                if (cur > 0) {
                                    state.activeEditor?.let {
                                        it.movePage(cur + 1, cur)
                                        refreshFromEngine(it)
                                        state.activePageIndex = cur - 1
                                    }
                                }
                            },
                            enabled = state.activePageIndex > 0,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(24.dp)
                        ) { Text("◀", fontSize = 10.sp) }

                        Button(
                            onClick = {
                                val cur = state.activePageIndex
                                if (cur < state.pages.size - 1) {
                                    state.activeEditor?.let {
                                        it.movePage(cur + 1, cur + 2)
                                        refreshFromEngine(it)
                                        state.activePageIndex = cur + 1
                                    }
                                }
                            },
                            enabled = state.activePageIndex < state.pages.size - 1,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(24.dp)
                        ) { Text("▶", fontSize = 10.sp) }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        itemsIndexed(state.pages) { idx, _ ->
                            val isSel = idx == state.activePageIndex
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSel) Color(0xFF1976D2) else Color(0xFFECEFF1),
                                modifier = Modifier.clickable {
                                    state.activePageIndex = idx
                                    state.activeElementId = null
                                    state.editingWordBox = null
                                    state.zoomScale = 1f
                                    state.panOffsetX = 0f
                                    state.panOffsetY = 0f
                                }
                            ) {
                                Text(
                                    text = "P. ${idx + 1}",
                                    fontSize = 10.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) Color.White else Color.Black,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // MODAL: DELETE PAGE(S)
    if (showDeletePagesDialog) {
        AlertDialog(
            onDismissRequest = { showDeletePagesDialog = false },
            title = { Text("Delete Page(s)", fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter page number or range to permanently remove (e.g. '2' or '1-2, 4'):", fontSize = 12.sp)
                    OutlinedTextField(
                        value = deletePagesSpecInput,
                        onValueChange = { deletePagesSpecInput = it },
                        label = { Text("Pages to Delete") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.activeEditor?.let { editor ->
                            try {
                                editor.deletePages(deletePagesSpecInput.trim())
                                refreshFromEngine(editor)
                                state.statusText = "Deleted pages ($deletePagesSpecInput)."
                            } catch (e: Exception) {
                                Toast.makeText(context, "Delete error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showDeletePagesDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB71C1C))
                ) { Text("Delete", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { showDeletePagesDialog = false }) { Text("Cancel") } }
        )
    }

    // MODAL: REORDER ALL PAGES
    if (showReorderPagesDialog) {
        AlertDialog(
            onDismissRequest = { showReorderPagesDialog = false },
            title = { Text("Reorder All Pages", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter full page order permutation (e.g. '3, 1, 2' for 3 pages):", fontSize = 12.sp)
                    OutlinedTextField(
                        value = reorderPermutationInput,
                        onValueChange = { reorderPermutationInput = it },
                        label = { Text("Page Permutation") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.activeEditor?.let { editor ->
                            try {
                                val order = reorderPermutationInput.split(',').map { it.trim().toInt() }
                                editor.reorder(order)
                                refreshFromEngine(editor)
                                state.statusText = "Reordered pages successfully."
                            } catch (e: Exception) {
                                Toast.makeText(context, "Reorder error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showReorderPagesDialog = false
                    }
                ) { Text("Apply Order") }
            },
            dismissButton = { TextButton(onClick = { showReorderPagesDialog = false }) { Text("Cancel") } }
        )
    }

    // MODAL: 4-CORNER INTERACTIVE VISUAL CROPPING DIALOG
    if (showFourCornerCropDialog && activePage != null) {
        FourCornerCropDialog(
            sourceBitmap = activePage.baseBitmap,
            onDismiss = { showFourCornerCropDialog = false },
            onCropConfirmed = { cL, cT, cR, cB ->
                state.activeEditor?.let { editor ->
                    editor.crop(
                        pages = (state.activePageIndex + 1).toString(),
                        left = cL,
                        top = cT,
                        right = cR,
                        bottom = cB
                    )
                    refreshFromEngine(editor)
                    state.statusText = "Cropped page ${state.activePageIndex + 1} successfully."
                }
                showFourCornerCropDialog = false
            }
        )
    }

    // MODAL: SPLIT PDF (SAVES AS ZIP OF ALL PARTS)
    if (showSplitDialog) {
        AlertDialog(
            onDismissRequest = { showSplitDialog = false },
            title = { Text("Split PDF into Multiple Files", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !splitModeByRanges, onClick = { splitModeByRanges = false })
                        Text("Split by pages per file (e.g. 1)", fontSize = 12.sp)
                    }
                    if (!splitModeByRanges) {
                        OutlinedTextField(
                            value = splitPagesPerFile,
                            onValueChange = { splitPagesPerFile = it },
                            label = { Text("Pages per file") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = splitModeByRanges, onClick = { splitModeByRanges = true })
                        Text("Split by custom ranges (e.g. 1-2, 3-last)", fontSize = 12.sp)
                    }
                    if (splitModeByRanges) {
                        OutlinedTextField(
                            value = splitRangeSpec,
                            onValueChange = { splitRangeSpec = it },
                            label = { Text("Ranges (comma separated)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.activeEditor?.let { editor ->
                            coroutineScope.launch {
                                state.isProcessing = true
                                try {
                                    val outDir = File(context.cacheDir, "split_${System.currentTimeMillis()}").apply { mkdirs() }
                                    val files = withContext(Dispatchers.IO) {
                                        if (splitModeByRanges) {
                                            editor.splitByRanges(outDir, splitRangeSpec.split(',').map { it.trim() })
                                        } else {
                                            editor.split(outDir, splitPagesPerFile.toIntOrNull() ?: 1)
                                        }
                                    }
                                    val zipFile = File(context.cacheDir, "Split_PDFs_${System.currentTimeMillis()}.zip")
                                    withContext(Dispatchers.IO) {
                                        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                                            files.forEach { f ->
                                                zos.putNextEntry(ZipEntry(f.name))
                                                f.inputStream().use { it.copyTo(zos) }
                                                zos.closeEntry()
                                            }
                                        }
                                    }
                                    pendingSplitZipFile = zipFile
                                    splitZipSaveAsLauncher.launch("Split_Documents.zip")
                                } catch (e: Exception) {
                                    state.statusText = "Split error: ${e.message}"
                                } finally {
                                    state.isProcessing = false
                                }
                            }
                        }
                        showSplitDialog = false
                    }
                ) { Text("Split & Save As") }
            },
            dismissButton = { TextButton(onClick = { showSplitDialog = false }) { Text("Cancel") } }
        )
    }

    // MODAL: EXTRACT PAGES (SAVE AS)
    if (showExtractDialog) {
        AlertDialog(
            onDismissRequest = { showExtractDialog = false },
            title = { Text("Extract Specific Pages to New PDF", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter page range syntax (e.g. '1-3, 5, 8-last'):", fontSize = 12.sp)
                    OutlinedTextField(
                        value = extractPagesSpec,
                        onValueChange = { extractPagesSpec = it },
                        label = { Text("Page Ranges") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExtractDialog = false
                        pdfExtractSaveLauncher.launch("Extracted_Pages.pdf")
                    }
                ) { Text("Extract & Save As") }
            },
            dismissButton = { TextButton(onClick = { showExtractDialog = false }) { Text("Cancel") } }
        )
    }

    // MODAL: RICH TEXT FORMATTING WITH INK DENSITY AND SHADES OF BLACK
    if (showTextEditDialog) {
        AlertDialog(
            onDismissRequest = { showTextEditDialog = false },
            title = {
                Text(
                    text = if (editingIsWhiteout) "Format Whiteout Block" else "Edit Text & Ink Matching",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF0D47A1)
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!editingIsWhiteout) {
                        OutlinedTextField(
                            value = editingTextValue,
                            onValueChange = { editingTextValue = it },
                            label = { Text("Letter / Text Content") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = editingFontSize.sp,
                                fontWeight = if (editingIsBold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (editingIsItalic) FontStyle.Italic else FontStyle.Normal,
                                color = editingColor.copy(alpha = editingOpacity)
                            )
                        )

                        // Font Style & Size (Down to 1sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { editingIsBold = !editingIsBold },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (editingIsBold) Color(0xFF1976D2) else Color(0xFFECEFF1)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) { Text("B", fontWeight = FontWeight.Bold, color = if (editingIsBold) Color.White else Color.Black) }

                            Button(
                                onClick = { editingIsItalic = !editingIsItalic },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (editingIsItalic) Color(0xFF1976D2) else Color(0xFFECEFF1)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) { Text("I", fontStyle = FontStyle.Italic, color = if (editingIsItalic) Color.White else Color.Black) }

                            Button(
                                onClick = { editingFontSize = (editingFontSize - 1f).coerceAtLeast(1f) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(26.dp)
                            ) { Text("-") }

                            Text("${editingFontSize.toInt()}sp", fontSize = 11.sp, fontWeight = FontWeight.Bold)

                            Button(
                                onClick = { editingFontSize = (editingFontSize + 1f).coerceAtMost(72f) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(26.dp)
                            ) { Text("+") }
                        }

                        // INK DENSITY / OPACITY CONTROLS
                        Text("Ink Density (Reduce Blackness to Match PDF):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                Pair("100%", 1.0f),
                                Pair("85% (Laser)", 0.85f),
                                Pair("75% (Scan)", 0.75f),
                                Pair("60% (Faded)", 0.60f)
                            ).forEach { (label, opacityVal) ->
                                val isSel = (editingOpacity * 100).roundToInt() == (opacityVal * 100).roundToInt()
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSel) Color(0xFF00796B) else Color(0xFFECEFF1),
                                    modifier = Modifier.clickable { editingOpacity = opacityVal }
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 9.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) Color.White else Color.Black,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        // Natural Ink Tone Shades
                        Text("Ink Shade (Document Print Matching):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            InkShadePalette.forEach { (name, col) ->
                                val isSelected = editingColor == col
                                Row(
                                    modifier = Modifier
                                        .background(if (isSelected) Color(0xFFE0F2F1) else Color.Transparent, RoundedCornerShape(4.dp))
                                        .border(if (isSelected) 1.dp else 0.dp, if (isSelected) Color(0xFF00796B) else Color.Transparent, RoundedCornerShape(4.dp))
                                        .clickable { editingColor = col }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .background(col, CircleShape)
                                            .border(0.5.dp, Color.Gray, CircleShape)
                                    )
                                    Text(name, fontSize = 9.sp, color = Color.DarkGray)
                                }
                            }
                        }

                        // Background Highlight
                        Text("Highlight / Background:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(Color.Transparent, Color.White, Color(0xFFFFF9C4), Color(0xFFE0F2FE), Color(0xFFE8F5E9)).forEach { bg ->
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(if (bg == Color.Transparent) Color.LightGray else bg, CircleShape)
                                        .border(
                                            width = if (editingBgColor == bg) 2.dp else 0.5.dp,
                                            color = if (editingBgColor == bg) Color.Blue else Color.Gray,
                                            shape = CircleShape
                                        )
                                        .clickable { editingBgColor = bg }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pushCanvasSnapshot()
                        activeElement?.let { elem ->
                            elem.text = editingTextValue
                            elem.fontSize = editingFontSize
                            elem.isBold = editingIsBold
                            elem.isItalic = editingIsItalic
                            elem.textColor = editingColor.copy(alpha = editingOpacity)
                            elem.backgroundColor = editingBgColor
                            elem.width = (editingTextValue.length * editingFontSize * 0.75f).coerceAtLeast(40f)
                        }
                        showTextEditDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF15803D))
                ) { Text("Apply Format", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showTextEditDialog = false }) { Text("Cancel") }
            }
        )
    }

    // MODAL: ADD WATERMARK
    if (showWatermarkDialog) {
        AlertDialog(
            onDismissRequest = { showWatermarkDialog = false },
            title = { Text("Add Text Watermark", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter watermark text to stamp across all pages:", fontSize = 12.sp)
                    OutlinedTextField(
                        value = watermarkInput,
                        onValueChange = { watermarkInput = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.activeEditor?.let {
                            it.addTextWatermark(watermarkInput, pages = "all")
                            refreshFromEngine(it)
                            state.statusText = "Stamped watermark '$watermarkInput' across all pages."
                        }
                        showWatermarkDialog = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showWatermarkDialog = false }) { Text("Cancel") } }
        )
    }

    // MODAL: ADD PAGE NUMBERS
    if (showPageNumbersDialog) {
        AlertDialog(
            onDismissRequest = { showPageNumbersDialog = false },
            title = { Text("Stamp Page Numbers", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Format string ({n} = page, {total} = total pages):", fontSize = 12.sp)
                    OutlinedTextField(
                        value = pageNumberFormatInput,
                        onValueChange = { pageNumberFormatInput = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.activeEditor?.let {
                            it.addPageNumbers(
                                format = pageNumberFormatInput,
                                pages = "all",
                                position = PageNumberPosition.BOTTOM_CENTER
                            )
                            refreshFromEngine(it)
                            state.statusText = "Stamped page numbers across all pages."
                        }
                        showPageNumbersDialog = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showPageNumbersDialog = false }) { Text("Cancel") } }
        )
    }
}

// 4-CORNER INTERACTIVE VISUAL CROPPING DIALOG
@Composable
private fun FourCornerCropDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onCropConfirmed: (cropL: Float, cropT: Float, cropR: Float, cropB: Float) -> Unit
) {
    var leftFraction by remember { mutableFloatStateOf(0.08f) }
    var topFraction by remember { mutableFloatStateOf(0.08f) }
    var rightFraction by remember { mutableFloatStateOf(0.08f) }
    var bottomFraction by remember { mutableFloatStateOf(0.08f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E293B)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✂ 4-Corner Page Cropper", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("✕", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Text(
                    text = "Drag the 4 corner handles to adjust the crop bounds with live visual preview:",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .background(Color.Black, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = sourceBitmap.asImageBitmap(),
                            contentDescription = "Crop Page Base",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            val l = w * leftFraction
                            val t = h * topFraction
                            val r = w * (1f - rightFraction)
                            val b = h * (1f - bottomFraction)

                            drawRect(Color.Black.copy(alpha = 0.55f), size = Size(w, t))
                            drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, b), size = Size(w, h - b))
                            drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(0f, t), size = Size(l, b - t))
                            drawRect(Color.Black.copy(alpha = 0.55f), topLeft = Offset(r, t), size = Size(w - r, b - t))

                            drawRect(
                                color = Color(0xFF00E676),
                                topLeft = Offset(l, t),
                                size = Size(r - l, b - t),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                            )
                        }

                        // 4 Draggable Handles
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset { IntOffset((leftFraction * 320f).roundToInt(), (topFraction * 440f).roundToInt()) }
                                .size(32.dp)
                                .background(Color(0xFF00E676), CircleShape)
                                .border(2.dp, Color.White, CircleShape)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        leftFraction = (leftFraction + dragAmount.x / 400f).coerceIn(0f, 0.45f)
                                        topFraction = (topFraction + dragAmount.y / 500f).coerceIn(0f, 0.45f)
                                    }
                                }
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset { IntOffset((-rightFraction * 320f).roundToInt(), (topFraction * 440f).roundToInt()) }
                                .size(32.dp)
                                .background(Color(0xFF00E676), CircleShape)
                                .border(2.dp, Color.White, CircleShape)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        rightFraction = (rightFraction - dragAmount.x / 400f).coerceIn(0f, 0.45f)
                                        topFraction = (topFraction + dragAmount.y / 500f).coerceIn(0f, 0.45f)
                                    }
                                }
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .offset { IntOffset((leftFraction * 320f).roundToInt(), (-bottomFraction * 440f).roundToInt()) }
                                .size(32.dp)
                                .background(Color(0xFF00E676), CircleShape)
                                .border(2.dp, Color.White, CircleShape)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        leftFraction = (leftFraction + dragAmount.x / 400f).coerceIn(0f, 0.45f)
                                        bottomFraction = (bottomFraction - dragAmount.y / 500f).coerceIn(0f, 0.45f)
                                    }
                                }
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset { IntOffset((-rightFraction * 320f).roundToInt(), (-bottomFraction * 440f).roundToInt()) }
                                .size(32.dp)
                                .background(Color(0xFF00E676), CircleShape)
                                .border(2.dp, Color.White, CircleShape)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        rightFraction = (rightFraction - dragAmount.x / 400f).coerceIn(0f, 0.45f)
                                        bottomFraction = (bottomFraction - dragAmount.y / 500f).coerceIn(0f, 0.45f)
                                    }
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            leftFraction = 0.05f
                            topFraction = 0.05f
                            rightFraction = 0.05f
                            bottomFraction = 0.05f
                        },
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) { Text("Reset", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = {
                            val ptW = 595f
                            val ptH = 842f
                            onCropConfirmed(
                                leftFraction * ptW,
                                topFraction * ptH,
                                rightFraction * ptW,
                                bottomFraction * ptH
                            )
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00E676)),
                        modifier = Modifier.weight(1.5f).height(38.dp)
                    ) {
                        Text("✓ Apply 4-Corner Crop", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
