package com.example.imagetotable.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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

/**
 * Reactive Compose-state backed model. Mutating any coordinate, dimension or text
 * immediately triggers an instantaneous real-time UI recomposition and redraw.
 */
class RichPdfTextElement(
    val id: String = UUID.randomUUID().toString(),
    initialText: String = "Tap to type",
    initialRelX: Float = 0.10f,
    initialRelY: Float = 0.15f,
    initialRelWidth: Float = 0.35f,
    initialRelHeight: Float = 0.05f,
    initialFontSizePt: Float = 14f,
    initialIsBold: Boolean = false,
    initialIsItalic: Boolean = false,
    initialTextColor: Color = Color(0xFF292524),
    initialOpacity: Float = 0.90f,
    initialBackgroundColor: Color = Color.Transparent,
    initialIsWhiteout: Boolean = false
) {
    var text by mutableStateOf(initialText)
    var relX by mutableFloatStateOf(initialRelX)
    var relY by mutableFloatStateOf(initialRelY)
    var relWidth by mutableFloatStateOf(initialRelWidth)
    var relHeight by mutableFloatStateOf(initialRelHeight)
    var fontSizePt by mutableFloatStateOf(initialFontSizePt)
    var isBold by mutableStateOf(initialIsBold)
    var isItalic by mutableStateOf(initialIsItalic)
    var textColor by mutableStateOf(initialTextColor)
    var opacity by mutableFloatStateOf(initialOpacity)
    var backgroundColor by mutableStateOf(initialBackgroundColor)
    var isWhiteout by mutableStateOf(initialIsWhiteout)

    fun copyElement(): RichPdfTextElement {
        return RichPdfTextElement(
            id = id,
            initialText = text,
            initialRelX = relX,
            initialRelY = relY,
            initialRelWidth = relWidth,
            initialRelHeight = relHeight,
            initialFontSizePt = fontSizePt,
            initialIsBold = isBold,
            initialIsItalic = isItalic,
            initialTextColor = textColor,
            initialOpacity = opacity,
            initialBackgroundColor = backgroundColor,
            initialIsWhiteout = isWhiteout
        )
    }
}

class DetectedWordBox(
    val id: String = UUID.randomUUID().toString(),
    val word: String,
    initialRelX: Float,
    initialRelY: Float,
    initialRelWidth: Float,
    initialRelHeight: Float,
    initialSampledPaperColor: Color = Color.White
) {
    var relX by mutableFloatStateOf(initialRelX)
    var relY by mutableFloatStateOf(initialRelY)
    var relWidth by mutableFloatStateOf(initialRelWidth)
    var relHeight by mutableFloatStateOf(initialRelHeight)
    var sampledPaperColor by mutableStateOf(initialSampledPaperColor)
}

data class EditablePdfPage(
    val pageIndex: Int,
    var baseBitmap: Bitmap,
    val widthPt: Float = 595f,
    val heightPt: Float = 842f,
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
    var statusText by mutableStateOf("Ready: Open any PDF to edit words & letters in real time")

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
    var liveWordFontSizePt by mutableFloatStateOf(14f)
    var liveWordIsBold by mutableStateOf(false)
    var liveWordIsItalic by mutableStateOf(false)
    var liveWordColor by mutableStateOf(Color(0xFF292524))
    var liveWordPaperColor by mutableStateOf(Color.Transparent)
    var liveWordOpacity by mutableFloatStateOf(0.90f)

    fun reset() {
        activeEditor?.close()
        activeEditor = null
        pages.clear()
        activePageIndex = 0
        activeElementId = null
        isProcessing = false
        statusText = "Ready: Open any PDF to edit words & letters in real time"
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
        liveWordOpacity = 0.90f
    }
}

object PdfEditorStateManager {
    val state = PdfEditorState()
}

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

val PaperTexturePalette = listOf(
    Pair("Auto Match", Color.Transparent),
    Pair("Pure White", Color(0xFFFFFFFF)),
    Pair("Warm Ivory", Color(0xFFFCFBF7)),
    Pair("Cream Paper", Color(0xFFF9F7F1)),
    Pair("Scan Grey", Color(0xFFF3F4F6)),
    Pair("Vintage", Color(0xFFF5EFEB))
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfEditorScreen(
    state: PdfEditorState = PdfEditorStateManager.state
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var showTextEditDialog by remember { mutableStateOf(false) }
    var editingTextValue by remember { mutableStateOf("") }
    var editingFontSizePt by remember { mutableFloatStateOf(14f) }
    var editingIsBold by remember { mutableStateOf(false) }
    var editingIsItalic by remember { mutableStateOf(false) }
    var editingColor by remember { mutableStateOf(Color(0xFF292524)) }
    var editingOpacity by remember { mutableFloatStateOf(0.90f) }
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
            if (state.canvasUndoStack.size > 30) state.canvasUndoStack.removeAt(0)
            state.canvasRedoStack.clear()
        }
    }

    fun refreshFromEngine(editor: PdfEditor) {
        state.pages.clear()
        for (i in 1..editor.pageCount) {
            val info = editor.pageInfo(i)
            val bmp = editor.renderPage(i, 150f)
            state.pages.add(
                EditablePdfPage(
                    pageIndex = i - 1,
                    baseBitmap = bmp,
                    widthPt = info.displayWidth,
                    heightPt = info.displayHeight
                )
            )
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
                state.statusText = "Undid last edit."
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
                state.statusText = "Redid edit."
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

    fun sampleSurroundingPaperColor(bitmap: Bitmap, relX: Float, relY: Float, relW: Float, relH: Float): Color {
        val bW = bitmap.width.toFloat()
        val bH = bitmap.height.toFloat()
        val pxLeft = (relX * bW).toInt()
        val pxTop = (relY * bH).toInt()
        val pxW = (relW * bW).toInt().coerceAtLeast(4)
        val pxH = (relH * bH).toInt().coerceAtLeast(4)

        val sampleOffsets = listOf(
            Pair(-4, pxH / 2),
            Pair(pxW + 4, pxH / 2),
            Pair(pxW / 2, -4),
            Pair(pxW / 2, pxH + 4)
        )
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        for ((dx, dy) in sampleOffsets) {
            val sx = (pxLeft + dx).coerceIn(0, bitmap.width - 1)
            val sy = (pxTop + dy).coerceIn(0, bitmap.height - 1)
            val pixel = bitmap.getPixel(sx, sy)
            rSum += android.graphics.Color.red(pixel)
            gSum += android.graphics.Color.green(pixel)
            bSum += android.graphics.Color.blue(pixel)
            count++
        }
        return if (count > 0) {
            Color((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
        } else {
            Color(0xFFFCFBF9)
        }
    }

    fun openEditDialogForElement(element: RichPdfTextElement) {
        state.activeElementId = element.id
        editingTextValue = element.text
        editingFontSizePt = element.fontSizePt
        editingIsBold = element.isBold
        editingIsItalic = element.isItalic
        editingColor = element.textColor
        editingOpacity = element.opacity
        editingBgColor = element.backgroundColor
        editingIsWhiteout = element.isWhiteout
        showTextEditDialog = true
    }

    fun applyWordEraseOrReplaceOverlay(target: DetectedWordBox, replacementText: String?) {
        activePage?.let { page ->
            pushCanvasSnapshot()

            val paperBg = if (state.liveWordPaperColor == Color.Transparent) {
                target.sampledPaperColor
            } else {
                state.liveWordPaperColor
            }

            if (!replacementText.isNullOrBlank()) {
                val newOverlay = RichPdfTextElement(
                    initialText = replacementText,
                    initialRelX = target.relX,
                    initialRelY = target.relY,
                    initialRelWidth = target.relWidth.coerceAtLeast(replacementText.length * 0.018f),
                    initialRelHeight = target.relHeight.coerceAtLeast(0.024f),
                    initialFontSizePt = state.liveWordFontSizePt,
                    initialIsBold = state.liveWordIsBold,
                    initialIsItalic = state.liveWordIsItalic,
                    initialTextColor = state.liveWordColor,
                    initialOpacity = state.liveWordOpacity,
                    initialBackgroundColor = paperBg,
                    initialIsWhiteout = false
                )
                page.elements.add(newOverlay)
                state.activeElementId = newOverlay.id
                state.statusText = "Replaced '$replacementText' on exact line."
            } else {
                val eraseWhiteout = RichPdfTextElement(
                    initialText = "",
                    initialRelX = target.relX - 0.002f,
                    initialRelY = target.relY - 0.002f,
                    initialRelWidth = target.relWidth + 0.004f,
                    initialRelHeight = target.relHeight + 0.004f,
                    initialBackgroundColor = paperBg,
                    initialIsWhiteout = true
                )
                page.elements.add(eraseWhiteout)
                state.activeElementId = eraseWhiteout.id
                state.statusText = "Erased '${target.word}' on page with matched paper patch."
            }

            state.editingWordBox = null
        }
    }

    fun scanCurrentPageWords() {
        activePage?.let { page ->
            coroutineScope.launch {
                state.isProcessing = true
                state.statusText = "Scanning page text & line geometry..."
                try {
                    val words = withContext(Dispatchers.IO) {
                        val result = mutableListOf<DetectedWordBox>()
                        val bW = page.baseBitmap.width.toFloat().coerceAtLeast(1f)
                        val bH = page.baseBitmap.height.toFloat().coerceAtLeast(1f)
                        try {
                            val inputImageClass = Class.forName("com.google.mlkit.vision.common.InputImage")
                            val fromBitmapMethod = inputImageClass.getMethod("fromBitmap", Bitmap::class.java, Int::class.javaPrimitiveType)
                            val inputImage = fromBitmapMethod.invoke(null, page.baseBitmap, 0)

                            val textRecognitionClass = Class.forName("com.google.mlkit.vision.text.TextRecognition")
                            val latinOptionsClass = Class.forName("com.google.mlkit.vision.text.latin.TextRecognizerOptions")
                            val defaultOptions = latinOptionsClass.getField("DEFAULT_OPTIONS").get(null)
                            val recognizer = textRecognitionClass.getMethod("getClient", Class.forName("com.google.mlkit.vision.text.TextRecognizerOptionsInterface"))
                                .invoke(null, defaultOptions)

                            val processMethod = recognizer.javaClass.getMethod("process", inputImageClass)
                            val task = processMethod.invoke(recognizer, inputImage)
                            val tasksClass = Class.forName("com.google.android.gms.tasks.Tasks")
                            val awaitMethod = tasksClass.getMethod("await", Class.forName("com.google.android.gms.tasks.Task"))
                            val visionText = awaitMethod.invoke(null, task)

                            val getBlocksMethod = visionText.javaClass.getMethod("getTextBlocks")
                            val blocks = getBlocksMethod.invoke(visionText) as? List<*> ?: emptyList<Any>()

                            for (block in blocks) {
                                if (block == null) continue
                                val lines = block.javaClass.getMethod("getLines").invoke(block) as? List<*> ?: emptyList<Any>()
                                for (line in lines) {
                                    if (line == null) continue
                                    val elements = line.javaClass.getMethod("getElements").invoke(line) as? List<*> ?: emptyList<Any>()
                                    for (element in elements) {
                                        if (element == null) continue
                                        val text = element.javaClass.getMethod("getText").invoke(element) as? String ?: ""
                                        val rect = element.javaClass.getMethod("getBoundingBox").invoke(element) as? Rect
                                        if (text.isNotBlank() && rect != null) {
                                            val rX = rect.left / bW
                                            val rY = rect.top / bH
                                            val rW = rect.width() / bW
                                            val rH = rect.height() / bH
                                            val wBox = DetectedWordBox(
                                                word = text.trim(),
                                                initialRelX = rX,
                                                initialRelY = rY,
                                                initialRelWidth = rW,
                                                initialRelHeight = rH
                                            )
                                            wBox.sampledPaperColor = sampleSurroundingPaperColor(page.baseBitmap, rX, rY, rW, rH)
                                            result.add(wBox)
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            try {
                                val ocr = AndroidOcrService(context) { /* status */ }
                                val tokens = ocr.extractTokens(page.baseBitmap)
                                var curX = 0.08f
                                var curY = 0.08f
                                tokens.forEach { token ->
                                    val tw = (token.length * 0.018f).coerceAtLeast(0.04f)
                                    val th = 0.024f
                                    if (curX + tw > 0.90f) {
                                        curX = 0.08f
                                        curY += 0.032f
                                    }
                                    val wBox = DetectedWordBox(
                                        word = token,
                                        initialRelX = curX,
                                        initialRelY = curY,
                                        initialRelWidth = tw,
                                        initialRelHeight = th
                                    )
                                    wBox.sampledPaperColor = sampleSurroundingPaperColor(page.baseBitmap, curX, curY, tw, th)
                                    result.add(wBox)
                                    curX += tw + 0.015f
                                }
                            } catch (_: Throwable) {}
                        }
                        result
                    }
                    page.detectedWords.clear()
                    page.detectedWords.addAll(words)
                    state.isInlineWordEditMode = true
                    state.statusText = "Found ${words.size} word(s). Tap any word to erase or replace in real time."
                } catch (e: Exception) {
                    state.statusText = "Detection error: ${e.message}"
                } finally {
                    state.isProcessing = false
                }
            }
        }
    }

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
                        tempMerged.delete()
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
        if (destUri != null && state.pages.isNotEmpty()) {
            coroutineScope.launch {
                state.isProcessing = true
                state.statusText = "Compiling PDF with exact baseline typography..."
                try {
                    withContext(Dispatchers.IO) {
                        val pdfDocument = PdfDocument()

                        state.pages.forEachIndexed { pIdx, page ->
                            val ptWidth = page.widthPt.roundToInt().coerceAtLeast(100)
                            val ptHeight = page.heightPt.roundToInt().coerceAtLeast(100)

                            val pageInfo = PdfDocument.PageInfo.Builder(ptWidth, ptHeight, pIdx + 1).create()
                            val pdfPage = pdfDocument.startPage(pageInfo)
                            val pdfCanvas = pdfPage.canvas

                            // 1. Draw base page bitmap directly to page points
                            val dstRect = RectF(0f, 0f, ptWidth.toFloat(), ptHeight.toFloat())
                            pdfCanvas.drawBitmap(page.baseBitmap, null, dstRect, null)

                            // 2. Draw all overlays using exact page-relative ratios
                            page.elements.forEach { elem ->
                                val scaledX = elem.relX * ptWidth.toFloat()
                                val scaledY = elem.relY * ptHeight.toFloat()
                                val scaledW = elem.relWidth * ptWidth.toFloat()
                                val scaledH = elem.relHeight * ptHeight.toFloat()

                                if (elem.isWhiteout || elem.backgroundColor != Color.Transparent) {
                                    val bgPaint = Paint().apply {
                                        color = elem.backgroundColor.toArgb()
                                        style = Paint.Style.FILL
                                    }
                                    pdfCanvas.drawRect(scaledX, scaledY, scaledX + scaledW, scaledY + scaledH, bgPaint)
                                }

                                if (!elem.isWhiteout && elem.text.isNotBlank()) {
                                    val textPaint = Paint().apply {
                                        color = elem.textColor.copy(alpha = elem.opacity).toArgb()
                                        textSize = elem.fontSizePt
                                        isAntiAlias = true
                                        val style = when {
                                            elem.isBold && elem.isItalic -> Typeface.BOLD_ITALIC
                                            elem.isBold -> Typeface.BOLD
                                            elem.isItalic -> Typeface.ITALIC
                                            else -> Typeface.NORMAL
                                        }
                                        typeface = Typeface.create(Typeface.DEFAULT, style)
                                    }

                                    // Multiline baseline typography rendering
                                    val lines = elem.text.split("\n")
                                    val lineHeight = textPaint.fontSpacing
                                    val baselineOffset = -textPaint.fontMetrics.ascent

                                    lines.forEachIndexed { lineIdx, line ->
                                        val lineY = scaledY + baselineOffset + (lineIdx * lineHeight)
                                        pdfCanvas.drawText(line, scaledX + 2f, lineY, textPaint)
                                    }
                                }
                            }

                            pdfDocument.finishPage(pdfPage)
                        }

                        context.contentResolver.openOutputStream(destUri)?.use { outStream ->
                            pdfDocument.writeTo(outStream)
                            outStream.flush()
                        }
                        pdfDocument.close()
                    }
                    state.statusText = "Saved edited PDF successfully!"
                    Toast.makeText(context, "Saved Edited PDF with changes intact!", Toast.LENGTH_SHORT).show()
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
                        text = if (state.editingWordBox != null) "🎯 Editing: '${state.editingWordBox?.word}'" else "✍️ PDF Studio: Real-Time Editor",
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
                            Text("✕ Cancel", color = Color.White, fontSize = 10.sp)
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
                            onClick = { openEditDialogForElement(activeElement) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("✎ Re-edit Text", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

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
                        Text("🗑 Delete Page", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                                        RichPdfTextElement(
                                            initialText = clip,
                                            initialRelX = 0.15f,
                                            initialRelY = 0.20f
                                        )
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
                                    initialText = "Type here",
                                    initialRelX = 0.12f,
                                    initialRelY = 0.18f,
                                    initialRelWidth = 0.40f,
                                    initialRelHeight = 0.05f
                                )
                                page.elements.add(newElem)
                                state.activeElementId = newElem.id
                                openEditDialogForElement(newElem)
                            }
                        },
                        enabled = activePage != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("➕ Text Box", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = {
                            activePage?.let { page ->
                                pushCanvasSnapshot()
                                val whiteout = RichPdfTextElement(
                                    initialText = "",
                                    initialRelX = 0.15f,
                                    initialRelY = 0.25f,
                                    initialRelWidth = 0.30f,
                                    initialRelHeight = 0.04f,
                                    initialBackgroundColor = Color.White,
                                    initialIsWhiteout = true
                                )
                                page.elements.add(whiteout)
                                state.activeElementId = whiteout.id
                                state.statusText = "Added Whiteout patch."
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

                    Button(
                        onClick = { openEditDialogForElement(activeElement) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3F51B5)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("✎ Edit Text", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    // REAL-TIME NUDGING (UPDATES IMMEDIATELY ON SCREEN)
                    Text("Move:", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Button(onClick = { activeElement.relX = (activeElement.relX - 0.004f).coerceAtLeast(0f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("◀", fontSize = 10.sp) }
                    Button(onClick = { activeElement.relX = (activeElement.relX + 0.004f).coerceAtMost(0.98f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("▶", fontSize = 10.sp) }
                    Button(onClick = { activeElement.relY = (activeElement.relY - 0.004f).coerceAtLeast(0f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("▲", fontSize = 10.sp) }
                    Button(onClick = { activeElement.relY = (activeElement.relY + 0.004f).coerceAtMost(0.98f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("▼", fontSize = 10.sp) }

                    Spacer(modifier = Modifier.width(4.dp))

                    // REAL-TIME SIZING (EXPANDS IMMEDIATELY ON SCREEN)
                    Text("Size:", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Button(onClick = { activeElement.relWidth = (activeElement.relWidth - 0.02f).coerceAtLeast(0.02f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("W-", fontSize = 9.sp) }
                    Button(onClick = { activeElement.relWidth = (activeElement.relWidth + 0.02f).coerceAtMost(1f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("W+", fontSize = 9.sp) }
                    Button(onClick = { activeElement.relHeight = (activeElement.relHeight - 0.01f).coerceAtLeast(0.01f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("H-", fontSize = 9.sp) }
                    Button(onClick = { activeElement.relHeight = (activeElement.relHeight + 0.01f).coerceAtMost(1f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("H+", fontSize = 9.sp) }

                    if (!activeElement.isWhiteout) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${activeElement.fontSizePt.toInt()}pt", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { activeElement.fontSizePt = (activeElement.fontSizePt - 1f).coerceAtLeast(1f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(24.dp)) { Text("-", fontSize = 10.sp) }
                        Button(onClick = { activeElement.fontSizePt = (activeElement.fontSizePt + 1f).coerceAtMost(72f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(24.dp)) { Text("+", fontSize = 10.sp) }
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

        // REAL-TIME WYSIWYG CANVAS
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
                        val maxPan = 1200f * (state.zoomScale - 1f).coerceAtLeast(0f)
                        state.panOffsetX = (state.panOffsetX + pan.x).coerceIn(-maxPan, maxPan)
                        state.panOffsetY = (state.panOffsetY + pan.y).coerceIn(-maxPan, maxPan)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val containerW = maxWidth.value
            val containerH = maxHeight.value

            if (activePage != null) {
                val bW = activePage.baseBitmap.width.toFloat().coerceAtLeast(1f)
                val bH = activePage.baseBitmap.height.toFloat().coerceAtLeast(1f)
                val fitScale = minOf(containerW / bW, containerH / bH)
                val pagePixelW = bW * fitScale
                val pagePixelH = bH * fitScale

                Box(
                    modifier = Modifier
                        .size(pagePixelW.dp, pagePixelH.dp)
                        .graphicsLayer(
                            scaleX = state.zoomScale,
                            scaleY = state.zoomScale,
                            translationX = state.panOffsetX,
                            translationY = state.panOffsetY
                        )
                ) {
                    Image(
                        bitmap = activePage.baseBitmap.asImageBitmap(),
                        contentDescription = "Page ${state.activePageIndex + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )

                    // LIVE PREVIEW DIRECTLY ON TARGET WORD AT EXACT LINE
                    if (state.editingWordBox != null) {
                        val wordBox = state.editingWordBox!!
                        val activePaperBg = if (state.liveWordPaperColor == Color.Transparent) {
                            wordBox.sampledPaperColor
                        } else {
                            state.liveWordPaperColor
                        }

                        // 1. Ambient Paper-Tone Whiteout
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = (wordBox.relX * pagePixelW).dp,
                                    y = (wordBox.relY * pagePixelH).dp
                                )
                                .size(
                                    width = (wordBox.relWidth * pagePixelW).dp,
                                    height = (wordBox.relHeight * pagePixelH).dp
                                )
                                .background(activePaperBg)
                                .border(1.dp, Color(0xFF00E676), RoundedCornerShape(2.dp))
                        )

                        // 2. Real-Time Replacement Text rendered on exact line
                        Text(
                            text = state.liveWordText,
                            fontSize = (state.liveWordFontSizePt * (pagePixelH / activePage.heightPt)).sp,
                            fontWeight = if (state.liveWordIsBold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (state.liveWordIsItalic) FontStyle.Italic else FontStyle.Normal,
                            color = state.liveWordColor.copy(alpha = state.liveWordOpacity),
                            modifier = Modifier
                                .offset(
                                    x = (wordBox.relX * pagePixelW + 1f).dp,
                                    y = (wordBox.relY * pagePixelH).dp
                                )
                        )
                    }

                    // REAL-TIME DRAGGABLE & RESIZABLE OVERLAYS
                    activePage.elements.forEach { element ->
                        val isSelected = element.id == state.activeElementId
                        val elemLeft = element.relX * pagePixelW
                        val elemTop = element.relY * pagePixelH
                        val elemW = (element.relWidth * pagePixelW).coerceAtLeast(20f)
                        val elemH = (element.relHeight * pagePixelH).coerceAtLeast(14f)

                        Box(
                            modifier = Modifier
                                .offset(x = elemLeft.dp, y = elemTop.dp)
                                .size(width = elemW.dp, height = elemH.dp)
                                .pointerInput(element.id) {
                                    detectDragGestures(
                                        onDragStart = { state.activeElementId = element.id },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            element.relX = (element.relX + dragAmount.x / pagePixelW).coerceIn(0f, 0.98f)
                                            element.relY = (element.relY + dragAmount.y / pagePixelH).coerceIn(0f, 0.98f)
                                        }
                                    )
                                }
                                .background(
                                    if (element.isWhiteout) element.backgroundColor else element.backgroundColor,
                                    RoundedCornerShape(2.dp)
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) Color(0xFF1976D2) else if (element.isWhiteout) Color.LightGray else Color.Transparent,
                                    shape = RoundedCornerShape(2.dp)
                                )
                                .padding(horizontal = 2.dp)
                        ) {
                            if (!element.isWhiteout) {
                                BasicTextField(
                                    value = element.text,
                                    onValueChange = { element.text = it },
                                    textStyle = TextStyle(
                                        fontSize = (element.fontSizePt * (pagePixelH / activePage.heightPt)).sp,
                                        fontWeight = if (element.isBold) FontWeight.Bold else FontWeight.Normal,
                                        fontStyle = if (element.isItalic) FontStyle.Italic else FontStyle.Normal,
                                        color = element.textColor.copy(alpha = element.opacity)
                                    ),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Selection badges: Quick Delete & Corner Resize
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 10.dp, y = (-10).dp)
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

                                // Interactive Corner Resize Handle (Expands Freely on Drag)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(x = 10.dp, y = 10.dp)
                                        .size(24.dp)
                                        .background(Color(0xFF1976D2), CircleShape)
                                        .border(2.dp, Color.White, CircleShape)
                                        .pointerInput(element.id) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                element.relWidth = (element.relWidth + dragAmount.x / pagePixelW).coerceIn(0.02f, 1f)
                                                element.relHeight = (element.relHeight + dragAmount.y / pagePixelH).coerceIn(0.015f, 1f)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("⤡", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
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

        // SEPARATE DOCKED UI: DETECTED WORDS SHELF & LIVE RESIZABLE NOTE BOX
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
                                            state.liveWordFontSizePt = 14f
                                            state.liveWordIsBold = false
                                            state.liveWordIsItalic = false
                                            state.liveWordColor = Color(0xFF292524)
                                            state.liveWordPaperColor = Color.Transparent
                                            state.liveWordOpacity = 0.90f
                                            state.isNoteBoxMinimized = false

                                            // Auto-pan directly to this word
                                            state.zoomScale = 1.9f
                                            state.panOffsetX = -(wordBox.relX * 200f)
                                            state.panOffsetY = -(wordBox.relY * 200f)
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
                                label = { Text("Replacement Text (Updates in real time on PDF)", fontSize = 10.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = state.liveWordFontSizePt.sp,
                                    fontWeight = if (state.liveWordIsBold) FontWeight.Bold else FontWeight.Normal,
                                    fontStyle = if (state.liveWordIsItalic) FontStyle.Italic else FontStyle.Normal,
                                    color = state.liveWordColor.copy(alpha = state.liveWordOpacity)
                                )
                            )

                            // Position & Box Size Adjustments (Nudging on the line)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Pos:", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Button(onClick = { target.relX -= 0.003f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("◀", fontSize = 8.sp) }
                                Button(onClick = { target.relX += 0.003f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("▶", fontSize = 8.sp) }
                                Button(onClick = { target.relY -= 0.003f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("▲", fontSize = 8.sp) }
                                Button(onClick = { target.relY += 0.003f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("▼", fontSize = 8.sp) }

                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Box:", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Button(onClick = { target.relWidth = (target.relWidth - 0.01f).coerceAtLeast(0.02f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("W-", fontSize = 8.sp) }
                                Button(onClick = { target.relWidth += 0.01f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("W+", fontSize = 8.sp) }
                                Button(onClick = { target.relHeight = (target.relHeight - 0.005f).coerceAtLeast(0.015f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("H-", fontSize = 8.sp) }
                                Button(onClick = { target.relHeight += 0.005f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(22.dp)) { Text("H+", fontSize = 8.sp) }
                            }

                            // PAPER TEXTURE SELECTION FOR ERASER / WHITEOUT
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Paper Match:", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                                PaperTexturePalette.forEach { (name, col) ->
                                    val isSelected = state.liveWordPaperColor == col
                                    val displayColor = if (col == Color.Transparent) target.sampledPaperColor else col
                                    Row(
                                        modifier = Modifier
                                            .background(if (isSelected) Color(0xFFE0F2F1) else Color.Transparent, RoundedCornerShape(4.dp))
                                            .border(if (isSelected) 1.dp else 0.dp, if (isSelected) Color(0xFF00796B) else Color.Transparent, RoundedCornerShape(4.dp))
                                            .clickable { state.liveWordPaperColor = col }
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(displayColor, CircleShape)
                                                .border(0.5.dp, Color.Gray, CircleShape)
                                        )
                                        Text(name, fontSize = 8.sp, color = Color.DarkGray)
                                    }
                                }
                            }

                            // INK TONE & DENSITY
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

                            // Ultra-Fine Word Font Sizing (Down to 1pt) & Formatting
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
                                    onClick = { state.liveWordFontSizePt = (state.liveWordFontSizePt - 1f).coerceAtLeast(1f) },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(24.dp)
                                ) { Text("-", fontSize = 10.sp) }

                                Text("${state.liveWordFontSizePt.toInt()}pt", fontSize = 10.sp, fontWeight = FontWeight.Bold)

                                Button(
                                    onClick = { state.liveWordFontSizePt = (state.liveWordFontSizePt + 1f).coerceAtMost(72f) },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(24.dp)
                                ) { Text("+", fontSize = 10.sp) }

                                Spacer(modifier = Modifier.width(4.dp))

                                listOf(Color(0xFF000000), Color(0xFF292524), Color(0xFF1565C0), Color(0xFFC62828)).forEach { col ->
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .background(col, CircleShape)
                                            .border(
                                                width = if (state.liveWordColor == col) 2.dp else 0.5.dp,
                                                color = if (state.liveWordColor == col) Color.Cyan else Color.Gray,
                                                shape = CircleShape
                                            )
                                            .clickable { state.liveWordColor = col }
                                    )
                                }
                            }

                            // Action buttons: Apply to Overlay (Re-editable at any time)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        applyWordEraseOrReplaceOverlay(target, state.liveWordText)
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00796B)),
                                    modifier = Modifier.weight(1.2f).height(32.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) { Text("✓ Confirm Edit", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        applyWordEraseOrReplaceOverlay(target, null)
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
                    text = if (editingIsWhiteout) "Format Whiteout Block" else "Edit Text & Typography",
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
                            label = { Text("Text Content (Supports multiline)") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = editingFontSizePt.sp,
                                fontWeight = if (editingIsBold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (editingIsItalic) FontStyle.Italic else FontStyle.Normal,
                                color = editingColor.copy(alpha = editingOpacity)
                            )
                        )

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
                                onClick = { editingFontSizePt = (editingFontSizePt - 1f).coerceAtLeast(1f) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(26.dp)
                            ) { Text("-") }

                            Text("${editingFontSizePt.toInt()}pt", fontSize = 11.sp, fontWeight = FontWeight.Bold)

                            Button(
                                onClick = { editingFontSizePt = (editingFontSizePt + 1f).coerceAtMost(72f) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(26.dp)
                            ) { Text("+") }
                        }

                        // INK DENSITY
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
                                    shape = RoundedCornerShape(3.dp),
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

                        // Ink Shade Palette
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
                        Text("Background / Highlight Texture:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PaperTexturePalette.forEach { (name, bg) ->
                                val isSelected = editingBgColor == bg
                                val displayColor = if (bg == Color.Transparent) Color.LightGray else bg
                                Row(
                                    modifier = Modifier
                                        .background(if (isSelected) Color(0xFFE0F2F1) else Color.Transparent, RoundedCornerShape(4.dp))
                                        .border(if (isSelected) 1.dp else 0.dp, if (isSelected) Color(0xFF00796B) else Color.Transparent, RoundedCornerShape(4.dp))
                                        .clickable { editingBgColor = bg }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .background(displayColor, CircleShape)
                                            .border(0.5.dp, Color.Gray, CircleShape)
                                    )
                                    Text(name, fontSize = 9.sp, color = Color.DarkGray)
                                }
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
                            elem.fontSizePt = editingFontSizePt
                            elem.isBold = editingIsBold
                            elem.isItalic = editingIsItalic
                            elem.textColor = editingColor
                            elem.opacity = editingOpacity
                            elem.backgroundColor = editingBgColor
                            elem.relWidth = (editingTextValue.length * 0.018f).coerceIn(0.04f, 0.98f)
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
