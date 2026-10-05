package com.example.imagetotable.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path as AndroidPath
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
 * RichPdfTextElement encapsulates an interactive on-page element
 * (either a custom text box, a paper-matched whiteout patch, or a replaced word).
 *
 * All coordinates are normalized ratios (0.0f..1.0f) relative to page dimensions.
 * This guarantees WYSIWYG placement precision regardless of screen density,
 * orientation, canvas zooming, or PDF export resolution.
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
    initialIsWhiteout: Boolean = false,
    initialIsReplacedWord: Boolean = false,
    initialOriginalWord: String = "",
    initialAssociatedWordBoxId: String? = null
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
    var opacity by mutableFloatStateOf(initialOpacity) // Shading 0.0f to 1.0f (0% to 100%)
    var backgroundColor by mutableStateOf(initialBackgroundColor)
    var isWhiteout by mutableStateOf(initialIsWhiteout)
    var isReplacedWord by mutableStateOf(initialIsReplacedWord)
    var originalWord by mutableStateOf(initialOriginalWord)
    var associatedWordBoxId by mutableStateOf(initialAssociatedWordBoxId)

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
            initialIsWhiteout = isWhiteout,
            initialIsReplacedWord = isReplacedWord,
            initialOriginalWord = originalWord,
            initialAssociatedWordBoxId = associatedWordBoxId
        )
    }
}

/**
 * DetectedWordBox tracks words identified via ML Kit OCR or document inspection.
 * Coordinates are normalized to 0.0f..1.0f relative to the page image dimensions.
 */
class DetectedWordBox(
    val id: String = UUID.randomUUID().toString(),
    initialWord: String,
    initialRelX: Float,
    initialRelY: Float,
    initialRelWidth: Float,
    initialRelHeight: Float,
    initialSampledPaperColor: Color = Color.White,
    initialIsReplaced: Boolean = false,
    initialReplacedText: String = "",
    initialAssociatedElementId: String? = null
) {
    var word by mutableStateOf(initialWord)
    var relX by mutableFloatStateOf(initialRelX)
    var relY by mutableFloatStateOf(initialRelY)
    var relWidth by mutableFloatStateOf(initialRelWidth)
    var relHeight by mutableFloatStateOf(initialRelHeight)
    var sampledPaperColor by mutableStateOf(initialSampledPaperColor)
    var isReplaced by mutableStateOf(initialIsReplaced)
    var replacedText by mutableStateOf(initialReplacedText)
    var associatedElementId by mutableStateOf(initialAssociatedElementId)
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
    val bitmapCopy: Bitmap,
    val elements: List<RichPdfTextElement>
)

/**
 * State holder that persists across tab navigation and screen re-composition.
 */
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
    var pageRenderVersion by mutableIntStateOf(0)

    val canvasUndoStack = mutableStateListOf<CanvasSnapshot>()
    val canvasRedoStack = mutableStateListOf<CanvasSnapshot>()

    // Word Inspector & Note Box State
    var isInlineWordEditMode by mutableStateOf(false)
    var selectedWordsTab by mutableIntStateOf(0) // 0 = Detected Words, 1 = Replaced Words
    var wordSearchFilter by mutableStateOf("")
    var editingWordBox by mutableStateOf<DetectedWordBox?>(null)
    var isNoteBoxMinimized by mutableStateOf(false)
    var liveWordText by mutableStateOf("")
    var liveWordFontSizePt by mutableFloatStateOf(14f)
    var liveWordIsBold by mutableStateOf(false)
    var liveWordIsItalic by mutableStateOf(false)
    var liveWordColor by mutableStateOf(Color(0xFF292524))
    var liveWordPaperColor by mutableStateOf(Color.Transparent)
    var liveWordOpacity by mutableFloatStateOf(0.90f) // Shading 0.0f..1.0f

    // Dedicated Eraser Tool State
   // var isEraserToolActive by mutableStateOf(false)
   // var selectedEraserTexture by mutableStateOf(Color.Transparent)
   // var eraserPaddingPx by mutableFloatStateOf(4f)
        // Dedicated Eraser Tool State
    var isEraserToolActive by mutableStateOf(false)
    var selectedEraserTexture by mutableStateOf(Color.Transparent)
    var eraserPaddingPx by mutableFloatStateOf(4f)
    var eraserBrushSize by mutableFloatStateOf(20f) // <-- ADD THIS: Manual eraser width in points
    var isEraserPanMode by mutableStateOf(false) // <-- ADD THIS: false = Erase, true = Pan/Move page

var isPanModeActive by mutableStateOf(false)
    // Dedicated Pen / Highlighter Tool State
    var isPenModeActive by mutableStateOf(false)
    var isHighlighterMode by mutableStateOf(false)
    var penColor by mutableStateOf(Color(0xFF1565C0))
    var penStrokeWidth by mutableFloatStateOf(3f)

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
        pageRenderVersion = 0
        canvasUndoStack.clear()
        canvasRedoStack.clear()
        isInlineWordEditMode = false
        selectedWordsTab = 0
        wordSearchFilter = ""
        editingWordBox = null
        isNoteBoxMinimized = false
        liveWordText = ""
        liveWordOpacity = 0.90f
        isEraserToolActive = false
        selectedEraserTexture = Color.Transparent
        eraserPaddingPx = 4f
        isPenModeActive = false
        isHighlighterMode = false
        penColor = Color(0xFF1565C0)
        penStrokeWidth = 3f
            isPanModeActive = false

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
    Pair("Vintage Parchment", Color(0xFFF5EFEB))
)

val AnnotationPenColors = listOf(
    Pair("Blue", Color(0xFF1565C0)),
    Pair("Black", Color(0xFF000000)),
    Pair("Red", Color(0xFFD32F2F)),
    Pair("Green", Color(0xFF2E7D32)),
    Pair("Yellow", Color(0xFFFFEB3B)),
    Pair("Pink", Color(0xFFE91E63)),
    Pair("Orange", Color(0xFFFF9800)),
    Pair("Purple", Color(0xFF7B1FA2)),
    Pair("White", Color(0xFFFFFFFF))
)

val PenThicknesses = listOf(
    Pair("1pt", 1.5f),
    Pair("2pt", 3f),
    Pair("4pt", 6f),
    Pair("8pt", 12f),
    Pair("16pt", 22f),
    Pair("24pt", 34f)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfEditorScreen(
    state: PdfEditorState = PdfEditorStateManager.state
) {
    val context = LocalContext.current
    val density = LocalDensity.current
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

    // Live points in screen display pixels [0..containerW, 0..containerH]
    //val liveDrawingScreenStroke = remember { mutableStateListOf<Offset>() }
    val liveDrawingScreenStroke = remember { mutableStateListOf<Offset>() }
    val liveEraserScreenStroke = remember { mutableStateListOf<Offset>() } // <-- ADD THIS

    val activePage = state.pages.getOrNull(state.activePageIndex)
    val activeElement = activePage?.elements?.find { it.id == state.activeElementId }

    fun pushCanvasSnapshot() {
        activePage?.let { page ->
            val bmpCopy = page.baseBitmap.copy(page.baseBitmap.config ?: Bitmap.Config.ARGB_8888, true)
            state.canvasUndoStack.add(
                CanvasSnapshot(
                    pageIndex = state.activePageIndex,
                    bitmapCopy = bmpCopy,
                    elements = page.elements.map { it.copyElement() }
                )
            )
            if (state.canvasUndoStack.size > 25) {
                state.canvasUndoStack.removeAt(0).bitmapCopy.recycle()
            }
            state.canvasRedoStack.clear()
        }
    }

   /* fun refreshFromEngine(editor: PdfEditor) {
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
        state.pageRenderVersion++
    }*/

fun refreshFromEngine(editor: PdfEditor) {
        val existingPages = state.pages.toList()
        state.pages.clear()
        for (i in 1..editor.pageCount) {
            val info = editor.pageInfo(i)
            val bmp = editor.renderPage(i, 150f)
            val oldPage = existingPages.getOrNull(i - 1)
            val newPage = EditablePdfPage(
                pageIndex = i - 1,
                baseBitmap = bmp,
                widthPt = info.displayWidth,
                heightPt = info.displayHeight
            )
            // Preserve elements & detected words
            if (oldPage != null) {
                newPage.elements.addAll(oldPage.elements)
                newPage.detectedWords.addAll(oldPage.detectedWords)
            }
            state.pages.add(newPage)
        }
        if (state.activePageIndex >= state.pages.size) {
            state.activePageIndex = (state.pages.size - 1).coerceAtLeast(0)
        }
        state.pageRenderVersion++
}

    
    fun undoLastAction() {
        if (state.canvasUndoStack.isNotEmpty()) {
            val snapshot = state.canvasUndoStack.removeAt(state.canvasUndoStack.size - 1)
            activePage?.let { page ->
                val currentBmpCopy = page.baseBitmap.copy(page.baseBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                state.canvasRedoStack.add(
                    CanvasSnapshot(
                        pageIndex = state.activePageIndex,
                        bitmapCopy = currentBmpCopy,
                        elements = page.elements.map { it.copyElement() }
                    )
                )
                page.baseBitmap = snapshot.bitmapCopy
                page.elements.clear()
                page.elements.addAll(snapshot.elements.map { it.copyElement() })
                state.activeElementId = null
                state.pageRenderVersion++
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
                val currentBmpCopy = page.baseBitmap.copy(page.baseBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                state.canvasUndoStack.add(
                    CanvasSnapshot(
                        pageIndex = state.activePageIndex,
                        bitmapCopy = currentBmpCopy,
                        elements = page.elements.map { it.copyElement() }
                    )
                )
                page.baseBitmap = snapshot.bitmapCopy
                page.elements.clear()
                page.elements.addAll(snapshot.elements.map { it.copyElement() })
                state.activeElementId = null
                state.pageRenderVersion++
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

    fun samplePurePaperBackground(bitmap: Bitmap, relX: Float, relY: Float, relW: Float, relH: Float): Color {
        val bW = bitmap.width.toFloat()
        val bH = bitmap.height.toFloat()
        val pxLeft = (relX * bW).toInt().coerceIn(0, bitmap.width - 1)
        val pxTop = (relY * bH).toInt().coerceIn(0, bitmap.height - 1)
        val pxW = (relW * bW).toInt().coerceAtLeast(4)
        val pxH = (relH * bH).toInt().coerceAtLeast(4)

        val samplePoints = mutableListOf<Pair<Int, Int>>()
        for (step in 0..4) {
            val frac = step / 4f
            samplePoints.add(Pair(pxLeft + (pxW * frac).toInt(), (pxTop - 5).coerceAtLeast(0)))
            samplePoints.add(Pair(pxLeft + (pxW * frac).toInt(), (pxTop + pxH + 5).coerceAtMost(bitmap.height - 1)))
            samplePoints.add(Pair((pxLeft - 5).coerceAtLeast(0), pxTop + (pxH * frac).toInt()))
            samplePoints.add(Pair((pxLeft + pxW + 5).coerceAtMost(bitmap.width - 1), pxTop + (pxH * frac).toInt()))
        }

        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        for ((x, y) in samplePoints) {
            val pixel = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            if (luminance >= 140) {
                rSum += r; gSum += g; bSum += b
                count++
            }
        }

        return if (count > 0) {
            Color((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt(), 255)
        } else {
            Color(0xFFFCFBF9)
        }
    }

    fun eraseWordWithTexture(target: DetectedWordBox) {
        activePage?.let { page ->
            pushCanvasSnapshot()

            val targetPaperColor = if (state.selectedEraserTexture == Color.Transparent) {
                samplePurePaperBackground(page.baseBitmap, target.relX, target.relY, target.relWidth, target.relHeight)
            } else {
                state.selectedEraserTexture
            }

            val canvas = android.graphics.Canvas(page.baseBitmap)
            val paint = Paint().apply {
                color = targetPaperColor.toArgb()
                style = Paint.Style.FILL
            }
            val bW = page.baseBitmap.width.toFloat()
            val bH = page.baseBitmap.height.toFloat()
            val pad = state.eraserPaddingPx
            val eraseRect = RectF(
                (target.relX * bW - pad).coerceAtLeast(0f),
                (target.relY * bH - pad).coerceAtLeast(0f),
                ((target.relX + target.relWidth) * bW + pad).coerceAtMost(bW),
                ((target.relY + target.relHeight) * bH + pad).coerceAtMost(bH)
            )
            canvas.drawRect(eraseRect, paint)

            target.associatedElementId?.let { elId ->
                page.elements.removeAll { it.id == elId }
            }
            page.detectedWords.removeAll { it.id == target.id }
            state.pageRenderVersion++
            state.statusText = "Erased '${target.word}' completely."
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

   /* fun openWordInNoteBox(target: DetectedWordBox) {
        state.isInlineWordEditMode = true
        state.isEraserToolActive = false
        state.isPenModeActive = false
        state.editingWordBox = target
        state.liveWordText = if (target.isReplaced) target.replacedText else target.word
        state.liveWordFontSizePt = 14f
        state.liveWordIsBold = false
        state.liveWordIsItalic = false
        state.liveWordColor = Color(0xFF292524)
        state.liveWordPaperColor = Color.Transparent
        state.liveWordOpacity = 0.90f
        state.isNoteBoxMinimized = false

        state.zoomScale = 1.9f
        state.panOffsetX = -(target.relX * 200f)
        state.panOffsetY = -(target.relY * 200f)
    }*/

/*fun openWordInNoteBox(target: DetectedWordBox) {
        state.isInlineWordEditMode = true
        state.isEraserToolActive = false
        state.isPenModeActive = false
        state.editingWordBox = target

        // Load existing element properties if this word was previously replaced
        val existingElem = activePage?.elements?.find { it.id == target.associatedElementId }
        if (existingElem != null) {
            state.liveWordText = existingElem.text
            state.liveWordFontSizePt = existingElem.fontSizePt
            state.liveWordIsBold = existingElem.isBold
            state.liveWordIsItalic = existingElem.isItalic
            state.liveWordColor = existingElem.textColor
            state.liveWordOpacity = existingElem.opacity
            state.liveWordPaperColor = existingElem.backgroundColor
            state.activeElementId = existingElem.id
        } else {
            state.liveWordText = if (target.isReplaced) target.replacedText else target.word
            state.liveWordFontSizePt = 14f
            state.liveWordIsBold = false
            state.liveWordIsItalic = false
            state.liveWordColor = Color(0xFF292524)
            state.liveWordPaperColor = Color.Transparent
            state.liveWordOpacity = 0.90f
        }
        state.isNoteBoxMinimized = false

        state.zoomScale = 1.9f
        state.panOffsetX = -(target.relX * 200f)
        state.panOffsetY = -(target.relY * 200f)
}*/
        fun openWordInNoteBox(target: DetectedWordBox) {
        state.isInlineWordEditMode = true // Force edit mode on
        state.isEraserToolActive = false   // Disable raw eraser mode
        state.isPenModeActive = false
        state.editingWordBox = target

        // Load existing element values if already replaced
        val existingElem = activePage?.elements?.find { it.id == target.associatedElementId }
        if (existingElem != null) {
            state.liveWordText = existingElem.text
            state.liveWordFontSizePt = existingElem.fontSizePt
            state.liveWordIsBold = existingElem.isBold
            state.liveWordIsItalic = existingElem.isItalic
            state.liveWordColor = existingElem.textColor
            state.liveWordOpacity = existingElem.opacity
            state.liveWordPaperColor = existingElem.backgroundColor
            state.activeElementId = existingElem.id
        } else {
            state.liveWordText = if (target.isReplaced) target.replacedText else target.word
            state.liveWordFontSizePt = 14f
            state.liveWordIsBold = false
            state.liveWordIsItalic = false
            state.liveWordColor = Color(0xFF292524)
            state.liveWordPaperColor = Color.Transparent
            state.liveWordOpacity = 0.90f
        }
        state.isNoteBoxMinimized = false

        state.zoomScale = 1.9f
        state.panOffsetX = -(target.relX * 200f)
        state.panOffsetY = -(target.relY * 200f)
    }


    // Permanently wipes original word on baseBitmap and updates/creates the interactive overlay element
    fun applyWordEraseOrReplace(target: DetectedWordBox, replacementText: String?) {
        activePage?.let { page ->
            pushCanvasSnapshot()

            val solidPaperBg = if (state.liveWordPaperColor == Color.Transparent) {
                samplePurePaperBackground(page.baseBitmap, target.relX, target.relY, target.relWidth, target.relHeight)
            } else {
                state.liveWordPaperColor
            }

            val bW = page.baseBitmap.width.toFloat()
            val bH = page.baseBitmap.height.toFloat()
            val pad = state.eraserPaddingPx
            val canvas = android.graphics.Canvas(page.baseBitmap)

            // 1. Wipe original word on baseBitmap permanently with paper texture
            val erasePaint = Paint().apply {
                color = solidPaperBg.toArgb()
                style = Paint.Style.FILL
            }
            val eraseRect = RectF(
                (target.relX * bW - pad).coerceAtLeast(0f),
                (target.relY * bH - pad).coerceAtLeast(0f),
                ((target.relX + target.relWidth) * bW + pad).coerceAtMost(bW),
                ((target.relY + target.relHeight) * bH + pad).coerceAtMost(bH)
            )
            canvas.drawRect(eraseRect, erasePaint)

            // 2. Add or update the re-editable overlay element
            val cleanReplacement = replacementText?.trim().orEmpty()
           /* if (cleanReplacement.isNotEmpty()) {
                val existingElem = page.elements.find { it.id == target.associatedElementId }
                if (existingElem != null) {
                    existingElem.text = cleanReplacement
                    existingElem.fontSizePt = state.liveWordFontSizePt
                    existingElem.isBold = state.liveWordIsBold
                    existingElem.isItalic = state.liveWordIsItalic
                    existingElem.textColor = state.liveWordColor
                    existingElem.opacity = state.liveWordOpacity
                    existingElem.backgroundColor = solidPaperBg
                    state.activeElementId = existingElem.id
                } else {
                    val newOverlay = RichPdfTextElement(
                        initialText = cleanReplacement,
                        initialRelX = target.relX,
                        initialRelY = target.relY,
                        initialRelWidth = maxOf(target.relWidth, cleanReplacement.length * 0.019f),
                        initialRelHeight = maxOf(target.relHeight, 0.035f),
                        initialFontSizePt = state.liveWordFontSizePt,
                        initialIsBold = state.liveWordIsBold,
                        initialIsItalic = state.liveWordIsItalic,
                        initialTextColor = state.liveWordColor,
                        initialOpacity = state.liveWordOpacity,
                        initialBackgroundColor = solidPaperBg,
                        initialIsWhiteout = false,
                        initialIsReplacedWord = true,
                        initialOriginalWord = target.word,
                        initialAssociatedWordBoxId = target.id
                    )
                    page.elements.add(newOverlay)
                    target.associatedElementId = newOverlay.id
                    state.activeElementId = newOverlay.id
                }

                target.isReplaced = true
                target.replacedText = cleanReplacement
                state.statusText = "Replaced with '$cleanReplacement' (re-editable overlay created)."
            } else {
                target.associatedElementId?.let { elId ->
                    page.elements.removeAll { it.id == elId }
                }
                page.detectedWords.removeAll { it.id == target.id }
                state.statusText = "Erased '${target.word}' from document."
            }

            state.pageRenderVersion++
            state.editingWordBox = null*/

                        if (cleanReplacement.isNotEmpty()) {
                val existingElem = page.elements.find { it.id == target.associatedElementId }
                if (existingElem != null) {
                    existingElem.text = cleanReplacement
                    existingElem.fontSizePt = state.liveWordFontSizePt
                    existingElem.isBold = state.liveWordIsBold
                    existingElem.isItalic = state.liveWordIsItalic
                    existingElem.textColor = state.liveWordColor
                    existingElem.opacity = state.liveWordOpacity
                    existingElem.backgroundColor = solidPaperBg
                    state.activeElementId = existingElem.id
                } else {
                    val newOverlay = RichPdfTextElement(
                        initialText = cleanReplacement,
                        initialRelX = target.relX,
                        initialRelY = target.relY,
                        initialRelWidth = maxOf(target.relWidth, cleanReplacement.length * 0.019f),
                        //initialRelHeight = maxOf(target.relHeight, 0.035f),
                        initialRelHeight = target.relHeight,
                        initialFontSizePt = state.liveWordFontSizePt,
                        initialIsBold = state.liveWordIsBold,
                        initialIsItalic = state.liveWordIsItalic,
                        initialTextColor = state.liveWordColor,
                        initialOpacity = state.liveWordOpacity,
                        initialBackgroundColor = solidPaperBg,
                        initialIsWhiteout = false,
                        initialIsReplacedWord = true,
                        initialOriginalWord = target.word,
                        initialAssociatedWordBoxId = target.id
                    )
                    page.elements.add(newOverlay)
                    target.associatedElementId = newOverlay.id
                    state.activeElementId = newOverlay.id
                }

                // KEEP THE WORD TARGET ACTIVE AND LINKED FOR REPEATED REPLACEMENTS
                target.isReplaced = true
                target.replacedText = cleanReplacement
                state.statusText = "Replaced with '$cleanReplacement'. Tap again anytime to re-edit."
            } else {
                // Only if explicitly erased with empty text, clean up
                target.associatedElementId?.let { elId ->
                    page.elements.removeAll { it.id == elId }
                }
                page.detectedWords.removeAll { it.id == target.id }
                state.statusText = "Erased '${target.word}' from document."
            }

            state.pageRenderVersion++
            state.editingWordBox = null
            // DO NOT turn off isInlineWordEditMode so the shelf stays available

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
                                                initialWord = text.trim(),
                                                initialRelX = rX,
                                                initialRelY = rY,
                                                initialRelWidth = rW,
                                                initialRelHeight = rH
                                            )
                                            wBox.sampledPaperColor = samplePurePaperBackground(page.baseBitmap, rX, rY, rW, rH)
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
                                        initialWord = token,
                                        initialRelX = curX,
                                        initialRelY = curY,
                                        initialRelWidth = tw,
                                        initialRelHeight = th
                                    )
                                    wBox.sampledPaperColor = samplePurePaperBackground(page.baseBitmap, curX, curY, tw, th)
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
                    state.statusText = "Found ${words.size} word(s). Tap any word below to edit or erase."
                } catch (e: Exception) {
                    state.statusText = "Detection error: ${e.message}"
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
                state.statusText = "Compiling PDF with exact typography and annotations..."
                try {
                    withContext(Dispatchers.IO) {
                        val pdfDocument = PdfDocument()

                        state.pages.forEachIndexed { pIdx, page ->
                            val ptWidth = page.widthPt.roundToInt().coerceAtLeast(100)
                            val ptHeight = page.heightPt.roundToInt().coerceAtLeast(100)

                            val pageInfo = PdfDocument.PageInfo.Builder(ptWidth, ptHeight, pIdx + 1).create()
                            val pdfPage = pdfDocument.startPage(pageInfo)
                            val pdfCanvas = pdfPage.canvas

                            // 1. Draw base page bitmap directly to page points (contains baked erasures & annotations)
                            val dstRect = RectF(0f, 0f, ptWidth.toFloat(), ptHeight.toFloat())
                            pdfCanvas.drawBitmap(page.baseBitmap, null, dstRect, null)

                            // 2. Draw all overlays using exact page-relative ratios and centered line alignment
                            page.elements.forEach { elem ->
                                val scaledX = elem.relX * ptWidth.toFloat()
                                val scaledY = elem.relY * ptHeight.toFloat()
                                val scaledW = elem.relWidth * ptWidth.toFloat()
                                val scaledH = elem.relHeight * ptHeight.toFloat()

                                /*if (elem.isWhiteout || elem.backgroundColor != Color.Transparent) {
                                    val bgPaint = Paint().apply {
                                        //color = elem.backgroundColor.toArgb()
                                        color = elem.backgroundColor.toArgb()
                                    alpha = (elem.opacity.coerceIn(0f, 1f) * 255).toInt()
                                        style = Paint.Style.FILL
                                    }
                                    pdfCanvas.drawRect(scaledX, scaledY, scaledX + scaledW, scaledY + scaledH, bgPaint)
                                }*/
                                 if (elem.isWhiteout || elem.backgroundColor != Color.Transparent) {
                                    val targetColor = if (elem.backgroundColor == Color.Transparent && elem.isWhiteout) {
                                        samplePurePaperBackground(page.baseBitmap, elem.relX, elem.relY, elem.relWidth, elem.relHeight)
                                    } else {
                                        elem.backgroundColor
                                    }

                                    if (targetColor != Color.Transparent) {
                                        val bgPaint = Paint().apply {
                                            color = targetColor.toArgb()
                                            alpha = (elem.opacity.coerceIn(0f, 1f) * 255).toInt()
                                            style = Paint.Style.FILL
                                        }
                                        pdfCanvas.drawRect(scaledX, scaledY, scaledX + scaledW, scaledY + scaledH, bgPaint)
                                    }
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

                                    /*val lines = elem.text.split("\n")
                                    val lineHeight = textPaint.fontSpacing
                                    val fontMetrics = textPaint.fontMetrics
                                    // Vertically center text within scaledH to eliminate line-shift
                                    val firstLineBaseline = scaledY + (scaledH - (lines.size - 1) * lineHeight) / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f

                                    lines.forEachIndexed { lineIdx, line ->
                                        val lineY = firstLineBaseline + (lineIdx * lineHeight)
                                        pdfCanvas.drawText(line, scaledX + 2f, lineY, textPaint)
                                    }*/
                                                                    val lines = elem.text.split("\n")
                                val lineHeight = textPaint.fontSpacing
                                val fontMetrics = textPaint.fontMetrics

                                // Replaced words center on the original word line; custom text boxes remain top-aligned
                                val firstLineBaseline = if (elem.isReplacedWord) {
                                    val boxCenterY = scaledY + (scaledH / 2f)
                                    boxCenterY - ((fontMetrics.ascent + fontMetrics.descent) / 2f)
                                } else {
                                    scaledY - fontMetrics.ascent
                                }

                                lines.forEachIndexed { lineIdx, line ->
                                    val lineY = firstLineBaseline + (lineIdx * lineHeight)
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
                        text = when {
                            state.isPenModeActive -> if (state.isHighlighterMode) "🖍️ Highlighter Active" else "✒️ Freehand Pen Active"
                            state.isEraserToolActive -> "🧹 Document Eraser Active"
                            state.editingWordBox != null -> "🎯 Editing: '${state.editingWordBox?.word}'"
                            else -> "✍️ PDF Studio: Real-Time Editor"
                        },
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
                    
                    // ✋ DEDICATED MOVE / PAN PAGE TOGGLE
                    Button(
                        onClick = {
                            state.isPanModeActive = !state.isPanModeActive
                            if (state.isPanModeActive) {
                                state.isPenModeActive = false
                                state.isEraserToolActive = false
                                state.isInlineWordEditMode = false
                                state.activeElementId = null
                                state.editingWordBox = null
                                state.statusText = "Move mode active: Drag to pan, pinch to zoom."
                            } else {
                                state.statusText = "Exited Move mode."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (state.isPanModeActive) Color(0xFF0288D1) else Color(0xFFECEFF1)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = if (state.isPanModeActive) "✓ Move Mode" else "✋ Move Page",
                            color = if (state.isPanModeActive) Color.White else Color.Black,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // FREEHAND PEN / HIGHLIGHTER TOOL BUTTON
                    Button(
                        onClick = {
                            state.isPenModeActive = !state.isPenModeActive
                            if (state.isPenModeActive) {
                                state.isEraserToolActive = false
                                state.isInlineWordEditMode = false
                                state.editingWordBox = null
                                state.activeElementId = null
                                state.statusText = "Pen active: Draw directly on page."
                            } else {
                                state.statusText = "Exited Pen Mode."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (state.isPenModeActive) Color(0xFF2E7D32) else Color(0xFF43A047)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = if (state.isPenModeActive) "✓ Pen Mode" else "✏️ Pen / Draw",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // DEDICATED DOCUMENT TEXTURE ERASER TOGGLE
                    Button(
                        onClick = {
                            state.isEraserToolActive = !state.isEraserToolActive
                            if (state.isEraserToolActive) {
                                state.isPenModeActive = false
                                state.isInlineWordEditMode = false
                                state.editingWordBox = null
                                state.activeElementId = null
                                if (activePage?.detectedWords?.isEmpty() == true) {
                                    scanCurrentPageWords()
                                }
                                state.statusText = "Eraser active: Tap any word to erase with matched paper texture."
                            } else {
                                state.statusText = "Exited Eraser Tool."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (state.isEraserToolActive) Color(0xFFC62828) else Color(0xFFD32F2F)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = if (state.isEraserToolActive) "✓ Eraser Active" else "🧹 Document Eraser",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (activeElement != null) {
                        Button(
                            onClick = {
                                openEditDialogForElement(activeElement)
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = if (activeElement.isReplacedWord) "✎ Re-edit Word" else "✎ Re-edit Text",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
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
                            Text("🗑 Delete", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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

                   /* Button(
                        onClick = {
                            if (!state.isInlineWordEditMode) {
                                state.isPenModeActive = false
                                state.isEraserToolActive = false
                                scanCurrentPageWords()
                            } else {
                                state.isInlineWordEditMode = false
                                state.editingWordBox = null
                                state.statusText = "Exited word inspector mode."
                            }
                        },*/

                        Button(
                        onClick = {
                            state.isEraserToolActive = false
                            state.isPenModeActive = false
                            if (!state.isInlineWordEditMode) {
                                state.isInlineWordEditMode = true
                                if (activePage?.detectedWords.isNullOrEmpty()) {
                                    scanCurrentPageWords()
                                } else {
                                    state.statusText = "Word replace active: Select any word below or on page."
                                }
                            } else {
                                state.isInlineWordEditMode = false
                                state.editingWordBox = null
                                state.statusText = "Exited word inspector mode."
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

                   /* Button(
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
                        },*/
                                            Button(
                        onClick = {
                            val activeTexture = if (state.selectedEraserTexture != Color.Transparent) {
                                state.selectedEraserTexture
                            } else {
                                Color.White
                            }
                            val newElem = RichPdfTextElement(
                                initialText = "",
                                initialRelX = 0.35f,
                                initialRelY = 0.35f,
                                initialRelWidth = 0.25f,
                                initialRelHeight = 0.05f,
                                initialIsWhiteout = true,
                                initialOpacity = 1.0f,
                                initialBackgroundColor = activeTexture
                            )
                            activePage?.elements?.add(newElem)
                            state.activeElementId = newElem.id
                            state.statusText = "Added Whiteout patch. Adjust fading and texture in edit controls."
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

        // PEN / ANNOTATION DOCKED PALETTE
        if (state.isPenModeActive && activePage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFE8F5E9),
                elevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { state.isHighlighterMode = !state.isHighlighterMode },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (state.isHighlighterMode) Color(0xFFFFEB3B) else Color(0xFF2E7D32)
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = if (state.isHighlighterMode) "🖍️ Highlight" else "✒️ Solid Pen",
                            color = if (state.isHighlighterMode) Color.Black else Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text("Size:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                    PenThicknesses.forEach { (lbl, widthVal) ->
                        val isSel = state.penStrokeWidth == widthVal
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = if (isSel) Color(0xFF2E7D32) else Color.White,
                            border = BorderStroke(0.5.dp, Color.Gray),
                            modifier = Modifier.clickable { state.penStrokeWidth = widthVal }
                        ) {
                            Text(
                                text = lbl,
                                fontSize = 9.sp,
                                color = if (isSel) Color.White else Color.Black,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Text("Color:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                    AnnotationPenColors.forEach { (_, col) ->
                        val isSel = state.penColor == col
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(col, CircleShape)
                                .border(
                                    width = if (isSel) 2.dp else 0.5.dp,
                                    color = if (isSel) Color(0xFF00E676) else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { state.penColor = col }
                        )
                    }

                    Button(
                        onClick = { state.isPenModeActive = false },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("Done ✕", color = Color.White, fontSize = 9.sp)
                    }
                }
            }
        }

        // DOCUMENT ERASER TEXTURE PALETTE DOCK
        /*if (state.isEraserToolActive && activePage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFFF3E0),
                elevation = 3.dp
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
                        text = "🧹 Eraser Texture:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100)
                    )

                    PaperTexturePalette.forEach { (name, col) ->
                        val isSelected = state.selectedEraserTexture == col
                        val displayColor = if (col == Color.Transparent) Color(0xFFF1EAD8) else col
                        Row(
                            modifier = Modifier
                                .background(if (isSelected) Color(0xFFFFCC80) else Color.White, RoundedCornerShape(4.dp))
                                .border(if (isSelected) 1.5.dp else 0.5.dp, if (isSelected) Color(0xFFE65100) else Color.LightGray, RoundedCornerShape(4.dp))
                                .clickable { state.selectedEraserTexture = col }
                                .padding(horizontal = 5.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(displayColor, CircleShape)
                                    .border(0.5.dp, Color.Gray, CircleShape)
                            )
                            Text(name, fontSize = 9.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Bleed:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                    listOf(Pair("Tight", 2f), Pair("Norm", 4f), Pair("Wide", 7f)).forEach { (lbl, pad) ->
                        val isSel = state.eraserPaddingPx == pad
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = if (isSel) Color(0xFFE65100) else Color.White,
                            border = BorderStroke(0.5.dp, Color.Gray),
                            modifier = Modifier.clickable { state.eraserPaddingPx = pad }
                        ) {
                            Text(lbl, fontSize = 9.sp, color = if (isSel) Color.White else Color.Black, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }

                    Button(
                        onClick = { state.isEraserToolActive = false },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5D4037)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("Done ✕", color = Color.White, fontSize = 9.sp)
                    }
                }
            }
        }*/

                // DOCUMENT ERASER TEXTURE PALETTE DOCK
        if (state.isEraserToolActive && activePage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFFF3E0),
                elevation = 3.dp
            ) {
                /*Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🧹 Texture Eraser:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100)
                    )*/

                                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1-FINGER TOGGLE: ERASE vs MOVE PAGE
                    Button(
                        onClick = { state.isEraserPanMode = !state.isEraserPanMode },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (state.isEraserPanMode) Color(0xFF1565C0) else Color(0xFFE65100)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = if (state.isEraserPanMode) "✋ Move Mode" else "🧹 Erase Mode",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))


                    PaperTexturePalette.forEach { (name, col) ->
                        val isSelected = state.selectedEraserTexture == col
                        val displayColor = if (col == Color.Transparent) Color(0xFFF1EAD8) else col
                        Row(
                            modifier = Modifier
                                .background(if (isSelected) Color(0xFFFFCC80) else Color.White, RoundedCornerShape(4.dp))
                                .border(if (isSelected) 1.5.dp else 0.5.dp, if (isSelected) Color(0xFFE65100) else Color.LightGray, RoundedCornerShape(4.dp))
                            .clickable { state.selectedEraserTexture = col }
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(displayColor, CircleShape)
                                    .border(0.5.dp, Color.Gray, CircleShape)
                            )
                            Text(name, fontSize = 9.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Brush:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                    listOf(Pair("Fine", 8f), Pair("Med", 18f), Pair("Wide", 32f), Pair("Block", 52f)).forEach { (lbl, sz) ->
                        val isSel = state.eraserBrushSize == sz
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = if (isSel) Color(0xFFE65100) else Color.White,
                            border = BorderStroke(0.5.dp, Color.Gray),
                            modifier = Modifier.clickable { state.eraserBrushSize = sz }
                        ) {
                            Text(lbl, fontSize = 9.sp, color = if (isSel) Color.White else Color.Black, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }

                    Button(
                        onClick = { state.isEraserToolActive = false },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5D4037)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("Done ✕", color = Color.White, fontSize = 9.sp)
                    }
                }
            }
        }


        // ELEMENT GEOMETRY & RESIZE TOOLBAR
        if (activeElement != null && state.editingWordBox == null && !state.isEraserToolActive && !state.isPenModeActive) {
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
                        text = if (activeElement.isReplacedWord) "✏️ Replaced Word:" else if (activeElement.isWhiteout) "⬜ Whiteout:" else "✏️ Text Box:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF283593)
                    )

                    Button(
                        onClick = {
                            openEditDialogForElement(activeElement)
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3F51B5)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = if (activeElement.isReplacedWord) "✎ Re-edit Word" else "✎ Edit Text",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // REAL-TIME NUDGING
                    Text("Move:", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Button(onClick = { activeElement.relX = (activeElement.relX - 0.004f).coerceAtLeast(0f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("◀", fontSize = 10.sp) }
                    Button(onClick = { activeElement.relX = (activeElement.relX + 0.004f).coerceAtMost(0.98f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("▶", fontSize = 10.sp) }
                    Button(onClick = { activeElement.relY = (activeElement.relY - 0.004f).coerceAtLeast(0f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("▲", fontSize = 10.sp) }
                    Button(onClick = { activeElement.relY = (activeElement.relY + 0.004f).coerceAtMost(0.98f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(26.dp)) { Text("▼", fontSize = 10.sp) }

                    Spacer(modifier = Modifier.width(4.dp))

                    // REAL-TIME SIZING
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

        // REAL-TIME WYSIWYG CANVAS WITH DECOUPLED GESTURE LAYERS
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 220.dp)
                .fillMaxWidth()
                .padding(4.dp)
                .clipToBounds()
                .background(Color(0xFF1E293B), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val containerHeightPx = with(density) { maxHeight.toPx() }

            if (activePage != null) {
                val bW = activePage.baseBitmap.width.toFloat().coerceAtLeast(1f)
                val bH = activePage.baseBitmap.height.toFloat().coerceAtLeast(1f)
                val fitScale = minOf(containerWidthPx / bW, containerHeightPx / bH)
                val pagePixelW = bW * fitScale
                val pagePixelH = bH * fitScale
                val pageDpW = with(density) { pagePixelW.toDp() }
                val pageDpH = with(density) { pagePixelH.toDp() }

                // The zoomable & pannable page container
                Box(
                    modifier = Modifier
                        .size(pageDpW, pageDpH)
                        .graphicsLayer(
                            scaleX = state.zoomScale,
                            scaleY = state.zoomScale,
                            translationX = state.panOffsetX,
                            translationY = state.panOffsetY
                        )
                ) {
                    key(activePage.baseBitmap, state.pageRenderVersion) {
                        Image(
                            bitmap = activePage.baseBitmap.asImageBitmap(),
                            contentDescription = "Page ${state.activePageIndex + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }

                    // LIVE PREVIEW DIRECTLY ON TARGET WORD
                   /* if (state.editingWordBox != null) {
                        val wordBox = state.editingWordBox!!
                        val activePaperBg = if (state.liveWordPaperColor == Color.Transparent) {
                            wordBox.sampledPaperColor
                        } else {
                            state.liveWordPaperColor
                        }

                        val targetWordLeftDp = (wordBox.relX * pageDpW.value).dp
                        val targetWordTopDp = (wordBox.relY * pageDpH.value).dp
                        val patchWDp = maxOf(
                            (wordBox.relWidth * pageDpW.value + 8f).dp,
                            (state.liveWordText.length * (state.liveWordFontSizePt * 0.70f) + 8f).dp
                        )
                        val patchHDp = maxOf(
                            (wordBox.relHeight * pageDpH.value + 6f).dp,
                            (state.liveWordFontSizePt + 6f).dp
                        )

                        Box(
                            modifier = Modifier
                                .offset(x = targetWordLeftDp - 4.dp, y = targetWordTopDp - 3.dp)
                                .size(width = patchWDp, height = patchHDp)
                                .background(activePaperBg)
                                .border(1.dp, Color(0xFF00E676), RoundedCornerShape(2.dp))
                                .padding(horizontal = 2.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            val liveFontSizeSp = with(density) {
                                (state.liveWordFontSizePt * (pagePixelH / activePage.heightPt)).toSp()
                            }
                            Text(
                                text = state.liveWordText,
                                fontSize = liveFontSizeSp,
                                fontWeight = if (state.liveWordIsBold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (state.liveWordIsItalic) FontStyle.Italic else FontStyle.Normal,
                                color = state.liveWordColor.copy(alpha = state.liveWordOpacity)
                            )
                        }
                    }*/

                               // LIVE INTERACTIVE REPLACEMENT TEXT BOX DIRECTLY ON THE DOCUMENT
                    if (state.editingWordBox != null) {
                        val wordBox = state.editingWordBox!!
                        val activePaperBg = if (state.liveWordPaperColor == Color.Transparent) {
                            wordBox.sampledPaperColor
                        } else {
                            state.liveWordPaperColor
                        }

                        val targetWordLeftDp = (wordBox.relX * pageDpW.value).dp
                        val targetWordTopDp = (wordBox.relY * pageDpH.value).dp
                        //val patchWDp = (wordBox.relWidth * pageDpW.value).coerceAtLeast(24f).dp
                        //val patchHDp = (wordBox.relHeight * pageDpH.value).coerceAtLeast(16f).dp
                        
                        val patchWDp = maxOf(
                            (wordBox.relWidth * pageDpW.value + 4f).dp,
                            (state.liveWordText.length * (state.liveWordFontSizePt * 0.65f) + 6f).dp
                        )
                        val patchHDp = (wordBox.relHeight * pageDpH.value).coerceAtLeast(14f).dp

                        Box(
                            modifier = Modifier
                                .offset(x = targetWordLeftDp, y = targetWordTopDp)
                                .size(width = patchWDp, height = patchHDp)
                                // 1. Direct finger dragging across the document in real time
                                .pointerInput(wordBox.id, pagePixelW, pagePixelH) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        wordBox.relX = (wordBox.relX + dragAmount.x / pagePixelW).coerceIn(0f, 0.98f)
                                        wordBox.relY = (wordBox.relY + dragAmount.y / pagePixelH).coerceIn(0f, 0.98f)
                                    }
                                }
                                // Solid matched paper background to cleanly cover the original word
                                .background(activePaperBg, RoundedCornerShape(2.dp))
                                .border(1.5.dp, Color(0xFF00C853), RoundedCornerShape(2.dp))
                                .padding(horizontal = 2.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            val liveFontSizeSp = with(density) {
                                (state.liveWordFontSizePt * (pagePixelH / activePage.heightPt)).toSp()
                            }

                            // 2. Direct in-place live typing right on the document
                            BasicTextField(
                                value = state.liveWordText,
                                onValueChange = { state.liveWordText = it },
                                textStyle = TextStyle(
                                    fontSize = liveFontSizeSp,
                                    lineHeight = liveFontSizeSp * 1.2f,
                                    fontWeight = if (state.liveWordIsBold) FontWeight.Bold else FontWeight.Normal,
                                    fontStyle = if (state.liveWordIsItalic) FontStyle.Italic else FontStyle.Normal,
                                    color = state.liveWordColor.copy(alpha = state.liveWordOpacity)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // 3. Top action badges: Commit replacement (✓) or cancel (✕)
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-14).dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF00C853), RoundedCornerShape(3.dp))
                                        .clickable {
                                            applyWordEraseOrReplace(wordBox, state.liveWordText)
                                        }
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("✓ Replace", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }

                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(Color(0xFF455A64), CircleShape)
                                        .clickable { state.editingWordBox = null },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✕", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // 4. Bottom-Left drag pill (✥)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .offset(x = (-6).dp, y = 6.dp)
                                    .size(20.dp)
                                    .background(Color(0xFF00897B), CircleShape)
                                    .border(1.dp, Color.White, CircleShape)
                                    .pointerInput(wordBox.id, pagePixelW, pagePixelH) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            wordBox.relX = (wordBox.relX + dragAmount.x / pagePixelW).coerceIn(0f, 0.98f)
                                            wordBox.relY = (wordBox.relY + dragAmount.y / pagePixelH).coerceIn(0f, 0.98f)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✥", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            // 5. Bottom-Right corner resize handle (⤡) to freely expand width & height
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 6.dp, y = 6.dp)
                                    .size(20.dp)
                                    .background(Color(0xFF00C853), CircleShape)
                                    .border(1.dp, Color.White, CircleShape)
                                    .pointerInput(wordBox.id, pagePixelW, pagePixelH) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            wordBox.relWidth = (wordBox.relWidth + dragAmount.x / pagePixelW).coerceIn(0.02f, 1f)
                                            wordBox.relHeight = (wordBox.relHeight + dragAmount.y / pagePixelH).coerceIn(0.015f, 1f)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("⤡", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // ERASER MODE ON-CANVAS HITBOXES
                    if (state.isEraserToolActive) {
                        activePage.detectedWords.forEach { wordBox ->
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = (wordBox.relX * pageDpW.value - 2f).dp,
                                        y = (wordBox.relY * pageDpH.value - 2f).dp
                                    )
                                    .size(
                                        width = (wordBox.relWidth * pageDpW.value + 4f).dp,
                                        height = (wordBox.relHeight * pageDpH.value + 4f).dp
                                    )
                                    .background(Color(0x33FF5722), RoundedCornerShape(2.dp))
                                    .border(1.dp, Color(0xFFD32F2F), RoundedCornerShape(2.dp))
                                    .clickable {
                                        eraseWordWithTexture(wordBox)
                                    }
                            )
                        }
                    }

                    // REAL-TIME RE-EDITABLE OVERLAYS (TAP-FIRST ARCHITECTURE TO PREVENT DRAG DEADLOCKS)
                    if (!state.isPenModeActive) {
                        activePage.elements.forEach { element ->
                            val isSelected = element.id == state.activeElementId
                            val elemLeftDp = (element.relX * pageDpW.value).dp
                            val elemTopDp = (element.relY * pageDpH.value).dp
                            val elemWDp = (element.relWidth * pageDpW.value).coerceAtLeast(20f).dp
                            val elemHDp = (element.relHeight * pageDpH.value).coerceAtLeast(14f).dp

                            /*Box(
                                modifier = Modifier
                                    .offset(x = elemLeftDp, y = elemTopDp)
                                    .size(width = elemWDp, height = elemHDp)
                                    .clickable {
                                        state.activeElementId = element.id
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
                                    .padding(horizontal = 2.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {*/

                                                            Box(
                                modifier = Modifier
                                    .offset(x = elemLeftDp, y = elemTopDp)
                                    .size(width = elemWDp, height = elemHDp)
                                    // 1. Direct drag gesture for finger movement anywhere on the box
                                    .pointerInput(element.id, pagePixelW, pagePixelH, state.zoomScale) {
                                        detectDragGestures(
                                            onDragStart = {
                                                state.activeElementId = element.id
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                // Divide by zoomScale so dragging remains 1:1 with your finger when zoomed in
                                                val effectiveZoom = state.zoomScale.coerceAtLeast(0.1f)
                                                element.relX = (element.relX + (dragAmount.x / effectiveZoom) / pagePixelW).coerceIn(0f, 0.98f)
                                                element.relY = (element.relY + (dragAmount.y / effectiveZoom) / pagePixelH).coerceIn(0f, 0.98f)
                                            
                                                //element.relX = (element.relX + dragAmount.x / pagePixelW).coerceIn(0f, 0.98f)
                                                //element.relY = (element.relY + dragAmount.y / pagePixelH).coerceIn(0f, 0.98f)
                                            }
                                        )
                                    }
                                    // 2. Tap gestures for selection and opening the re-edit dialog
                                   /* .pointerInput(element.id) {
                                        detectTapGestures(
                                            onTap = {
                                                state.activeElementId = element.id
                                                if (element.isReplacedWord && element.associatedWordBoxId != null) {
                                                    val wBox = activePage.detectedWords.find { it.id == element.associatedWordBoxId }
                                                    if (wBox != null) openWordInNoteBox(wBox)
                                                }
                                            },
                                            onDoubleTap = {
                                                state.activeElementId = element.id
                                                if (element.isReplacedWord && element.associatedWordBoxId != null) {
                                                    val wBox = activePage.detectedWords.find { it.id == element.associatedWordBoxId }
                                                    if (wBox != null) openWordInNoteBox(wBox)
                                                    else openEditDialogForElement(element)
                                                } else {
                                                    openEditDialogForElement(element)
                                                }
                                            }
                                        )
                                    }*/

                                                                    .pointerInput(element.id) {
                                        detectTapGestures(
                                            onTap = {
                                                state.activeElementId = element.id
                                                if (element.isReplacedWord) {
                                                    // Find or restore word box link so it can be re-edited immediately
                                                    val wBox = activePage.detectedWords.find { it.id == element.associatedWordBoxId }
                                                        ?: DetectedWordBox(
                                                            id = element.associatedWordBoxId ?: UUID.randomUUID().toString(),
                                                            initialWord = element.originalWord.ifBlank { element.text },
                                                            initialRelX = element.relX,
                                                            initialRelY = element.relY,
                                                            initialRelWidth = element.relWidth,
                                                            initialRelHeight = element.relHeight,
                                                            initialIsReplaced = true,
                                                            initialReplacedText = element.text,
                                                            initialAssociatedElementId = element.id
                                                        ).also {
                                                            if (activePage.detectedWords.none { w -> w.id == it.id }) {
                                                                activePage.detectedWords.add(it)
                                                            }
                                                        }
                                                    openWordInNoteBox(wBox)
                                                } else {
                                                    state.statusText = "Selected '${element.text}'. Tap ✎ Re-edit to edit."
                                                }
                                            },
                                            onDoubleTap = {
                                                state.activeElementId = element.id
                                                if (element.isReplacedWord) {
                                                    val wBox = activePage.detectedWords.find { it.id == element.associatedWordBoxId }
                                                    if (wBox != null) openWordInNoteBox(wBox)
                                                    else openEditDialogForElement(element)
                                                } else {
                                                    openEditDialogForElement(element)
                                                }
                                            }
                                        )
                                                                    }
                                    /*.background(
                                        //if (element.isWhiteout) element.backgroundColor else element.backgroundColor,
                                       element.backgroundColor.copy(alpha = element.opacity),
                                        RoundedCornerShape(2.dp)
                                    )*/
                                    // 1. If Auto Match (Transparent) on Whiteout, sample the real paper texture underneath
                                    val resolvedBgColor = when {
                                        element.backgroundColor != Color.Transparent -> element.backgroundColor
                                        element.isWhiteout -> samplePurePaperBackground(
                                            activePage.baseBitmap,
                                            element.relX,
                                            element.relY,
                                            element.relWidth,
                                            element.relHeight
                                        )
                                        else -> Color.Transparent
                                    }

                                    // 2. Never apply alpha to Color.Transparent (avoids turning into solid black)
                                    val finalBoxBackground = if (resolvedBgColor == Color.Transparent) {
                                        Color.Transparent
                                    } else {
                                        resolvedBgColor.copy(alpha = element.opacity.coerceIn(0.05f, 1f))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .offset(x = elemLeftDp, y = elemTopDp)
                                            .size(width = elemWDp, height = elemHDp)
                                            ...
                                            .background(finalBoxBackground, RoundedCornerShape(2.dp))

                                    
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                        color = if (isSelected) Color(0xFF1976D2) else if (element.isWhiteout) Color.LightGray else Color.Transparent,
                                        shape = RoundedCornerShape(2.dp)
                                    )
                                    .padding(horizontal = 2.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {

                                if (!element.isWhiteout) {
                                    val fontSizeSp = with(density) {
                                        (element.fontSizePt * (pagePixelH / activePage.heightPt)).toSp()
                                    }
                                    Text(
                                        text = element.text.ifBlank { " " },
                                        fontSize = fontSizeSp,
                                        lineHeight = fontSizeSp * 1.2f,
                                        fontWeight = if (element.isBold) FontWeight.Bold else FontWeight.Normal,
                                        fontStyle = if (element.isItalic) FontStyle.Italic else FontStyle.Normal,
                                        color = element.textColor.copy(alpha = element.opacity),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (isSelected) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 12.dp, y = (-12).dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF6A1B9A), RoundedCornerShape(4.dp))
                                                .clickable {
                                                    state.activeElementId = element.id
                                                    openEditDialogForElement(element)
                                                }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (element.isReplacedWord) "✎ Re-edit Word" else "✎ Edit Text",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
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
                                    }

                                    // Dedicated corner drag pill & resize handle
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .offset(x = (-8).dp, y = 8.dp)
                                            .size(22.dp)
                                            .background(Color(0xFF3F51B5), CircleShape)
                                            .border(1.5.dp, Color.White, CircleShape)
                                            .pointerInput(element.id, pagePixelW, pagePixelH) {
                                                detectDragGestures { change, dragAmount ->
                                                    change.consume()
                                                    element.relX = (element.relX + dragAmount.x / pagePixelW).coerceIn(0f, 0.98f)
                                                    element.relY = (element.relY + dragAmount.y / pagePixelH).coerceIn(0f, 0.98f)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✥", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 8.dp, y = 8.dp)
                                            .size(22.dp)
                                            .background(Color(0xFF1976D2), CircleShape)
                                            .border(1.5.dp, Color.White, CircleShape)
                                            .pointerInput(element.id, pagePixelW, pagePixelH) {
                                                detectDragGestures { change, dragAmount ->
                                                    change.consume()
                                                    element.relWidth = (element.relWidth + dragAmount.x / pagePixelW).coerceIn(0.02f, 1f)
                                                    element.relHeight = (element.relHeight + dragAmount.y / pagePixelH).coerceIn(0.015f, 1f)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("⤡", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // CONTAINER-LEVEL FULL-VIEWPORT PEN DRAWING & PAN GESTURE LAYER
              /*  if (state.isPenModeActive) {
                    // 1. Live Stroke Screen Canvas (Never clipped by Box bounds)
                    if (liveDrawingScreenStroke.size > 1) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeColor = if (state.isHighlighterMode) {
                                state.penColor.copy(alpha = 0.40f)
                            } else {
                                state.penColor
                            }
                            val strokeWidthScreen = state.penStrokeWidth * (pagePixelH / activePage.heightPt) * state.zoomScale
                            val path = androidx.compose.ui.graphics.Path()
                            path.moveTo(liveDrawingScreenStroke[0].x, liveDrawingScreenStroke[0].y)
                            for (i in 1 until liveDrawingScreenStroke.size) {
                                path.lineTo(liveDrawingScreenStroke[i].x, liveDrawingScreenStroke[i].y)
                            }
                            drawPath(
                                path = path,
                                color = strokeColor,
                                style = Stroke(
                                    width = strokeWidthScreen,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    // 2. Full-Viewport Gesture Dispatcher (Zero hit-test deadzones)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(activePage.pageIndex, state.isHighlighterMode, state.penColor, state.penStrokeWidth, state.zoomScale, state.panOffsetX, state.panOffsetY) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        pushCanvasSnapshot()
                                        liveDrawingScreenStroke.clear()
                                        liveDrawingScreenStroke.add(offset)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        liveDrawingScreenStroke.add(change.position)
                                    },
                                    onDragEnd = {
                                        if (liveDrawingScreenStroke.size > 1) {
                                            val canvas = android.graphics.Canvas(activePage.baseBitmap)
                                            val strokeWidthBmp = state.penStrokeWidth * (bH / activePage.heightPt)
                                            val paint = Paint().apply {
                                                isAntiAlias = true
                                                isDither = true
                                                style = Paint.Style.STROKE
                                                strokeJoin = Paint.Join.ROUND
                                                strokeCap = Paint.Cap.ROUND
                                                strokeWidth = strokeWidthBmp.coerceAtLeast(1.5f)
                                                color = if (state.isHighlighterMode) {
                                                    state.penColor.copy(alpha = 0.40f).toArgb()
                                                } else {
                                                    state.penColor.toArgb()
                                                }
                                            }

                                            // Invert screen coordinate projection into bitmap coordinates
                                            val screenCenterX = containerWidthPx / 2f + state.panOffsetX
                                            val screenCenterY = containerHeightPx / 2f + state.panOffsetY

                                            fun screenToBmp(pt: Offset): Offset {
                                                val relX = (pt.x - screenCenterX) / state.zoomScale
                                                val relY = (pt.y - screenCenterY) / state.zoomScale
                                                val pageX = relX + (pagePixelW / 2f)
                                                val pageY = relY + (pagePixelH / 2f)
                                                return Offset(
                                                    (pageX / fitScale).coerceIn(0f, bW),
                                                    (pageY / fitScale).coerceIn(0f, bH)
                                                )
                                            }

                                            val path = AndroidPath()
                                            val start = screenToBmp(liveDrawingScreenStroke[0])
                                            path.moveTo(start.x, start.y)
                                            for (i in 1 until liveDrawingScreenStroke.size) {
                                                val next = screenToBmp(liveDrawingScreenStroke[i])
                                                path.lineTo(next.x, next.y)
                                            }
                                            canvas.drawPath(path, paint)
                                            state.pageRenderVersion++
                                            state.statusText = "Annotated on page ${state.activePageIndex + 1}."
                                        }
                                        liveDrawingScreenStroke.clear()
                                    },
                                    onDragCancel = {
                                        liveDrawingScreenStroke.clear()
                                    }
                                )
                            }
                    )
                } else {
                    // Standard Two-Finger Pan & Pinch Detector when not drawing
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan: Offset, zoom: Float, _ ->
                                    state.zoomScale = (state.zoomScale * zoom).coerceIn(0.5f, 6.0f)
                                    val maxPan = 1200f * (state.zoomScale - 1f).coerceAtLeast(0f)
                                    state.panOffsetX = (state.panOffsetX + pan.x).coerceIn(-maxPan, maxPan)
                                    state.panOffsetY = (state.panOffsetY + pan.y).coerceIn(-maxPan, maxPan)
                                }
                            }
                    )
                }*/

                                // CONTAINER-LEVEL FULL-VIEWPORT GESTURE LAYER (PEN & MANUAL ERASER)
                if (state.isPenModeActive) {
                    // Live Pen Stroke Screen Canvas
                    if (liveDrawingScreenStroke.size > 1) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeColor = if (state.isHighlighterMode) {
                                state.penColor.copy(alpha = 0.40f)
                            } else {
                                state.penColor
                            }
                            val strokeWidthScreen = state.penStrokeWidth * (pagePixelH / activePage.heightPt) * state.zoomScale
                            val path = androidx.compose.ui.graphics.Path()
                            path.moveTo(liveDrawingScreenStroke[0].x, liveDrawingScreenStroke[0].y)
                            for (i in 1 until liveDrawingScreenStroke.size) {
                                path.lineTo(liveDrawingScreenStroke[i].x, liveDrawingScreenStroke[i].y)
                            }
                            drawPath(
                                path = path,
                                color = strokeColor,
                                style = Stroke(
                                    width = strokeWidthScreen,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    // Pen Drag Dispatcher
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(activePage.pageIndex, state.isHighlighterMode, state.penColor, state.penStrokeWidth, state.zoomScale, state.panOffsetX, state.panOffsetY) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        pushCanvasSnapshot()
                                        liveDrawingScreenStroke.clear()
                                        liveDrawingScreenStroke.add(offset)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        liveDrawingScreenStroke.add(change.position)
                                    },
                                    onDragEnd = {
                                        if (liveDrawingScreenStroke.size > 1) {
                                            val canvas = android.graphics.Canvas(activePage.baseBitmap)
                                            val strokeWidthBmp = state.penStrokeWidth * (bH / activePage.heightPt)
                                            val paint = Paint().apply {
                                                isAntiAlias = true
                                                isDither = true
                                                style = Paint.Style.STROKE
                                                strokeJoin = Paint.Join.ROUND
                                                strokeCap = Paint.Cap.ROUND
                                                strokeWidth = strokeWidthBmp.coerceAtLeast(1.5f)
                                                color = if (state.isHighlighterMode) {
                                                    state.penColor.copy(alpha = 0.40f).toArgb()
                                                } else {
                                                    state.penColor.toArgb()
                                                }
                                            }

                                            val screenCenterX = containerWidthPx / 2f + state.panOffsetX
                                            val screenCenterY = containerHeightPx / 2f + state.panOffsetY

                                            fun screenToBmp(pt: Offset): Offset {
                                                val relX = (pt.x - screenCenterX) / state.zoomScale
                                                val relY = (pt.y - screenCenterY) / state.zoomScale
                                                val pageX = relX + (pagePixelW / 2f)
                                                val pageY = relY + (pagePixelH / 2f)
                                                return Offset(
                                                    (pageX / fitScale).coerceIn(0f, bW),
                                                    (pageY / fitScale).coerceIn(0f, bH)
                                                )
                                            }

                                            val path = AndroidPath()
                                            val start = screenToBmp(liveDrawingScreenStroke[0])
                                            path.moveTo(start.x, start.y)
                                            for (i in 1 until liveDrawingScreenStroke.size) {
                                                val next = screenToBmp(liveDrawingScreenStroke[i])
                                                path.lineTo(next.x, next.y)
                                            }
                                            canvas.drawPath(path, paint)
                                            state.pageRenderVersion++
                                            state.statusText = "Annotated on page ${state.activePageIndex + 1}."
                                        }
                                        liveDrawingScreenStroke.clear()
                                    },
                                    onDragCancel = {
                                        liveDrawingScreenStroke.clear()
                                    }
                                )
                            }
                    )
                } else if (state.isEraserToolActive) {
                    // MANUAL FREEHAND TEXTURE ERASER (WORKS ZOOMED IN ON ANY LANGUAGE / UNDETECTED TEXT)
                    val eraserColor = if (state.selectedEraserTexture == Color.Transparent) {
                        samplePurePaperBackground(activePage.baseBitmap, 0.5f, 0.5f, 0.1f, 0.1f)
                    } else {
                        state.selectedEraserTexture
                    }

                    // Live Eraser Screen Preview
                    if (liveEraserScreenStroke.size > 1) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidthScreen = state.eraserBrushSize * (pagePixelH / activePage.heightPt) * state.zoomScale
                            val path = androidx.compose.ui.graphics.Path()
                            path.moveTo(liveEraserScreenStroke[0].x, liveEraserScreenStroke[0].y)
                            for (i in 1 until liveEraserScreenStroke.size) {
                                path.lineTo(liveEraserScreenStroke[i].x, liveEraserScreenStroke[i].y)
                            }
                            drawPath(
                                path = path,
                                color = eraserColor,
                                style = Stroke(
                                    width = strokeWidthScreen,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    // Manual Eraser Drag Dispatcher
                   /* Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(activePage.pageIndex, state.selectedEraserTexture, state.eraserBrushSize, state.zoomScale, state.panOffsetX, state.panOffsetY) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        pushCanvasSnapshot()
                                        liveEraserScreenStroke.clear()
                                        liveEraserScreenStroke.add(offset)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        liveEraserScreenStroke.add(change.position)
                                    },
                                    onDragEnd = {
                                        if (liveEraserScreenStroke.size > 1) {
                                            val canvas = android.graphics.Canvas(activePage.baseBitmap)
                                            val strokeWidthBmp = state.eraserBrushSize * (bH / activePage.heightPt)
                                            val paint = Paint().apply {
                                                isAntiAlias = true
                                                isDither = true
                                                style = Paint.Style.STROKE
                                                strokeJoin = Paint.Join.ROUND
                                                strokeCap = Paint.Cap.ROUND
                                                strokeWidth = strokeWidthBmp.coerceAtLeast(2f)
                                                color = eraserColor.toArgb()
                                            }

                                            val screenCenterX = containerWidthPx / 2f + state.panOffsetX
                                            val screenCenterY = containerHeightPx / 2f + state.panOffsetY

                                            fun screenToBmp(pt: Offset): Offset {
                                                val relX = (pt.x - screenCenterX) / state.zoomScale
                                                val relY = (pt.y - screenCenterY) / state.zoomScale
                                                val pageX = relX + (pagePixelW / 2f)
                                                val pageY = relY + (pagePixelH / 2f)
                                                return Offset(
                                                    (pageX / fitScale).coerceIn(0f, bW),
                                                    (pageY / fitScale).coerceIn(0f, bH)
                                                )
                                            }

                                            val path = AndroidPath()
                                            val start = screenToBmp(liveEraserScreenStroke[0])
                                            path.moveTo(start.x, start.y)
                                            for (i in 1 until liveEraserScreenStroke.size) {
                                                val next = screenToBmp(liveEraserScreenStroke[i])
                                                path.lineTo(next.x, next.y)
                                            }
                                            canvas.drawPath(path, paint)
                                            state.pageRenderVersion++
                                            state.statusText = "Erased custom text on page ${state.activePageIndex + 1}."
                                        }
                                        liveEraserScreenStroke.clear()
                                    },
                                    onDragCancel = {
                                        liveEraserScreenStroke.clear()
                                    }
                                )
                            }*/

                                                // 2. Full-Viewport Manual Eraser Dispatcher (Allows 2-finger pinch/zoom)
                    /*Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(activePage.pageIndex, state.selectedEraserTexture, state.eraserBrushSize, state.zoomScale, state.panOffsetX, state.panOffsetY) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    
                                    // If two or more fingers touch down, yield to zooming/panning
                                    var isMultiTouch = false
                                    val strokePoints = mutableListOf<Offset>()
                                    strokePoints.add(down.position)

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.size > 1) {
                                            isMultiTouch = true
                                            liveEraserScreenStroke.clear()
                                            break
                                        }

                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) break

                                        change.consume()
                                        strokePoints.add(change.position)
                                        liveEraserScreenStroke.clear()
                                        liveEraserScreenStroke.addAll(strokePoints)
                                    }

                                    // Commit erasure only if single-finger stroke was drawn
                                    if (!isMultiTouch && strokePoints.size > 1) {
                                        pushCanvasSnapshot()
                                        val canvas = android.graphics.Canvas(activePage.baseBitmap)
                                        val strokeWidthBmp = state.eraserBrushSize * (bH / activePage.heightPt)
                                        val paint = Paint().apply {
                                            isAntiAlias = true
                                            isDither = true
                                            style = Paint.Style.STROKE
                                            strokeJoin = Paint.Join.ROUND
                                            strokeCap = Paint.Cap.ROUND
                                            strokeWidth = strokeWidthBmp.coerceAtLeast(2f)
                                            color = eraserColor.toArgb()
                                        }

                                        val screenCenterX = containerWidthPx / 2f + state.panOffsetX
                                        val screenCenterY = containerHeightPx / 2f + state.panOffsetY

                                        fun screenToBmp(pt: Offset): Offset {
                                            val relX = (pt.x - screenCenterX) / state.zoomScale
                                            val relY = (pt.y - screenCenterY) / state.zoomScale
                                            val pageX = relX + (pagePixelW / 2f)
                                            val pageY = relY + (pagePixelH / 2f)
                                            return Offset(
                                                (pageX / fitScale).coerceIn(0f, bW),
                                                (pageY / fitScale).coerceIn(0f, bH)
                                            )
                                        }

                                        val path = AndroidPath()
                                        val start = screenToBmp(strokePoints[0])
                                        path.moveTo(start.x, start.y)
                                        for (i in 1 until strokePoints.size) {
                                            val next = screenToBmp(strokePoints[i])
                                            path.lineTo(next.x, next.y)
                                        }
                                        canvas.drawPath(path, paint)
                                        state.pageRenderVersion++
                                        state.statusText = "Erased text on page ${state.activePageIndex + 1}."
                                    }
                                    liveEraserScreenStroke.clear()
                                }
                            }
                    )*/
                                  /* Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(activePage.pageIndex, state.selectedEraserTexture, state.eraserBrushSize, state.zoomScale, state.panOffsetX, state.panOffsetY) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        isMultiTouchGesture = false
                                        liveEraserScreenStroke.clear()
                                        liveEraserScreenStroke.add(offset)
                                    },
                                    onDrag = { change, _ ->
                                        // If user pinches or uses 2 fingers, cancel erasing so zoom takes over
                                        if (isMultiTouchGesture) {
                                            liveEraserScreenStroke.clear()
                                            return@detectDragGestures
                                        }
                                        change.consume()
                                        liveEraserScreenStroke.add(change.position)
                                    },
                                    onDragEnd = {
                                        if (!isMultiTouchGesture && liveEraserScreenStroke.size > 1) {
                                            pushCanvasSnapshot()
                                            val canvas = android.graphics.Canvas(activePage.baseBitmap)
                                            val strokeWidthBmp = state.eraserBrushSize * (bH / activePage.heightPt)
                                            val paint = Paint().apply {
                                                isAntiAlias = true
                                                isDither = true
                                                style = Paint.Style.STROKE
                                                strokeJoin = Paint.Join.ROUND
                                                strokeCap = Paint.Cap.ROUND
                                                strokeWidth = strokeWidthBmp.coerceAtLeast(2f)
                                                color = eraserColor.toArgb()
                                            }

                                            val screenCenterX = containerWidthPx / 2f + state.panOffsetX
                                            val screenCenterY = containerHeightPx / 2f + state.panOffsetY

                                            fun screenToBmp(pt: Offset): Offset {
                                                val relX = (pt.x - screenCenterX) / state.zoomScale
                                                val relY = (pt.y - screenCenterY) / state.zoomScale
                                                val pageX = relX + (pagePixelW / 2f)
                                                val pageY = relY + (pagePixelH / 2f)
                                                return Offset(
                                                    (pageX / fitScale).coerceIn(0f, bW),
                                                    (pageY / fitScale).coerceIn(0f, bH)
                                                )
                                            }

                                            val path = AndroidPath()
                                            val start = screenToBmp(liveEraserScreenStroke[0])
                                            path.moveTo(start.x, start.y)
                                            for (i in 1 until liveEraserScreenStroke.size) {
                                                val next = screenToBmp(liveEraserScreenStroke[i])
                                                path.lineTo(next.x, next.y)
                                            }
                                            canvas.drawPath(path, paint)
                                            state.pageRenderVersion++
                                            state.statusText = "Erased text on page ${state.activePageIndex + 1}."
                                        }
                                        liveEraserScreenStroke.clear()
                                    },
                                    onDragCancel = {
                                        liveEraserScreenStroke.clear()
                                    }
                                )
                            }
                    )*/
                                        // Manual Eraser / 1-Finger Pan Dispatcher
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(activePage.pageIndex, state.isEraserPanMode, state.selectedEraserTexture, state.eraserBrushSize, state.zoomScale, state.panOffsetX, state.panOffsetY) {
                                /*if (state.isEraserPanMode) {
                                    // 1-FINGER PANNING: Moves the zoomed document freely
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val maxPan = 1200f * (state.zoomScale - 1f).coerceAtLeast(0f)
                                        state.panOffsetX = (state.panOffsetX + dragAmount.x).coerceIn(-maxPan, maxPan)
                                        state.panOffsetY = (state.panOffsetY + dragAmount.y).coerceIn(-maxPan, maxPan)
                                    }
                                } else {*/

                                                                if (state.isEraserPanMode) {
                                    // MOVE MODE: Single finger pans; two fingers simultaneously pinch-to-zoom & pan
                                    detectTransformGestures(panZoomLock = false) { _, pan: Offset, zoom: Float, _ ->
                                        state.zoomScale = (state.zoomScale * zoom).coerceIn(0.5f, 6.0f)
                                        val maxPan = 1200f * (state.zoomScale - 1f).coerceAtLeast(0f)
                                        state.panOffsetX = (state.panOffsetX + pan.x).coerceIn(-maxPan, maxPan)
                                        state.panOffsetY = (state.panOffsetY + pan.y).coerceIn(-maxPan, maxPan)
                                    }
                                } else {

                                    // 1-FINGER ERASING: Erases text with document texture
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            pushCanvasSnapshot()
                                            liveEraserScreenStroke.clear()
                                            liveEraserScreenStroke.add(offset)
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            liveEraserScreenStroke.add(change.position)
                                        },
                                        onDragEnd = {
                                            if (liveEraserScreenStroke.size > 1) {
                                                val canvas = android.graphics.Canvas(activePage.baseBitmap)
                                                val strokeWidthBmp = state.eraserBrushSize * (bH / activePage.heightPt)
                                                val paint = Paint().apply {
                                                    isAntiAlias = true
                                                    isDither = true
                                                    style = Paint.Style.STROKE
                                                    strokeJoin = Paint.Join.ROUND
                                                    strokeCap = Paint.Cap.ROUND
                                                    strokeWidth = strokeWidthBmp.coerceAtLeast(2f)
                                                    color = eraserColor.toArgb()
                                                }

                                                val screenCenterX = containerWidthPx / 2f + state.panOffsetX
                                                val screenCenterY = containerHeightPx / 2f + state.panOffsetY

                                                fun screenToBmp(pt: Offset): Offset {
                                                    val relX = (pt.x - screenCenterX) / state.zoomScale
                                                    val relY = (pt.y - screenCenterY) / state.zoomScale
                                                    val pageX = relX + (pagePixelW / 2f)
                                                    val pageY = relY + (pagePixelH / 2f)
                                                    return Offset(
                                                        (pageX / fitScale).coerceIn(0f, bW),
                                                        (pageY / fitScale).coerceIn(0f, bH)
                                                    )
                                                }

                                                val path = AndroidPath()
                                                val start = screenToBmp(liveEraserScreenStroke[0])
                                                path.moveTo(start.x, start.y)
                                                for (i in 1 until liveEraserScreenStroke.size) {
                                                    val next = screenToBmp(liveEraserScreenStroke[i])
                                                    path.lineTo(next.x, next.y)
                                                }
                                                canvas.drawPath(path, paint)
                                                state.pageRenderVersion++
                                                state.statusText = "Erased text on page ${state.activePageIndex + 1}."
                                            }
                                            liveEraserScreenStroke.clear()
                                        },
                                        onDragCancel = {
                                            liveEraserScreenStroke.clear()
                                        }
                                    )
                                }
                            }
                    )





                            
                    
                } else  if (state.isPanModeActive) {
                    // Full-Viewport Pan & Zoom dispatcher (Active only in Move Mode)
                    // Standard Two-Finger Pan & Pinch Detector when not drawing or manually erasing
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan: Offset, zoom: Float, _ ->
                                    state.zoomScale = (state.zoomScale * zoom).coerceIn(0.5f, 6.0f)
                                    val maxPan = 1200f * (state.zoomScale - 1f).coerceAtLeast(0f)
                                    state.panOffsetX = (state.panOffsetX + pan.x).coerceIn(-maxPan, maxPan)
                                    state.panOffsetY = (state.panOffsetY + pan.y).coerceIn(-maxPan, maxPan)
                                }
                            }
                    )
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

        // SEPARATE DOCKED UI: DETECTED WORDS SHELF & RE-EDITABLE NOTE BOX
        if (state.isInlineWordEditMode && activePage != null) {
            val replacedWordsCount = remember(activePage.detectedWords.size, activePage.elements.size, state.pageRenderVersion) {
                activePage.detectedWords.count { it.isReplaced }
            }

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
                        // Word list view header with tabs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (state.selectedWordsTab == 0) Color(0xFF00796B) else Color(0xFFECEFF1),
                                    modifier = Modifier.clickable { state.selectedWordsTab = 0 }
                                ) {
                                    Text(
                                        text = "🔤 Detected (${activePage.detectedWords.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.selectedWordsTab == 0) Color.White else Color.Black,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (state.selectedWordsTab == 1) Color(0xFF6A1B9A) else Color(0xFFECEFF1),
                                    modifier = Modifier.clickable { state.selectedWordsTab = 1 }
                                ) {
                                    Text(
                                        text = "✏️ Replaced (${replacedWordsCount})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.selectedWordsTab == 1) Color.White else Color.Black,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                                                            IconButton(onClick = { state.editingWordBox = null }, modifier = Modifier.size(22.dp)) {
                                    Text("✕", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                }


                           /* IconButton(onClick = { state.isInlineWordEditMode = false }, modifier = Modifier.size(20.dp)) {
                                Text("✕", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            }*/
                        }

                        OutlinedTextField(
                            value = state.wordSearchFilter,
                            onValueChange = { state.wordSearchFilter = it },
                            placeholder = { Text("Search word on page...", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 11.sp)
                        )

                        val sourceList = if (state.selectedWordsTab == 0) {
                            activePage.detectedWords
                        } else {
                            activePage.detectedWords.filter { it.isReplaced }
                        }

                        val filteredWords = sourceList.filter {
                            state.wordSearchFilter.isBlank() ||
                            it.word.contains(state.wordSearchFilter, ignoreCase = true) ||
                            it.replacedText.contains(state.wordSearchFilter, ignoreCase = true)
                        }

                        if (filteredWords.isEmpty()) {
                            Text(
                                text = if (state.selectedWordsTab == 1) "No words have been replaced yet." else "No matching words found.",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                filteredWords.forEach { wordBox ->
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (wordBox.isReplaced) Color(0xFFF3E5F5) else Color(0xFFE0F2F1),
                                        border = BorderStroke(0.5.dp, if (wordBox.isReplaced) Color(0xFF6A1B9A) else Color(0xFF00897B))
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (wordBox.isReplaced) "${wordBox.word} ➔ ${wordBox.replacedText}" else wordBox.word,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (wordBox.isReplaced) Color(0xFF4A148C) else Color(0xFF004D40),
                                                modifier = Modifier.clickable {
                                                    openWordInNoteBox(wordBox)
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (wordBox.isReplaced) "✎" else "🧹",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (wordBox.isReplaced) Color(0xFF6A1B9A) else Color.Red,
                                                modifier = Modifier.clickable {
                                                    if (wordBox.isReplaced) {
                                                        openWordInNoteBox(wordBox)
                                                    } else {
                                                        eraseWordWithTexture(wordBox)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // NOTE BOX: REAL-TIME REPLACEMENT & RESIZING CONTROLS
                        val target = state.editingWordBox!!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (target.isReplaced) "✏️ Re-editing '${target.word}' (Was: '${target.replacedText}')" else "✏️ Live Word: '${target.word}'",
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

                            // CONTINUOUS 0% TO 100% SHADING SLIDER & PRESET CHIPS
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Shading / Ink Density: ${(state.liveWordOpacity * 100).roundToInt()}%",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00796B)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        listOf(
                                            Pair("0%", 0.0f),
                                            Pair("25%", 0.25f),
                                            Pair("50%", 0.50f),
                                            Pair("75%", 0.75f),
                                            Pair("85%", 0.85f),
                                            Pair("100%", 1.0f)
                                        ).forEach { (lbl, valOp) ->
                                            Surface(
                                                shape = RoundedCornerShape(3.dp),
                                                color = if ((state.liveWordOpacity * 100).roundToInt() == (valOp * 100).roundToInt()) Color(0xFF00796B) else Color(0xFFECEFF1),
                                                modifier = Modifier.clickable { state.liveWordOpacity = valOp }
                                            ) {
                                                Text(
                                                    text = lbl,
                                                    fontSize = 8.sp,
                                                    color = if ((state.liveWordOpacity * 100).roundToInt() == (valOp * 100).roundToInt()) Color.White else Color.Black,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Slider(
                                    value = state.liveWordOpacity,
                                    onValueChange = { state.liveWordOpacity = it },
                                    valueRange = 0.0f..1.0f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF00796B),
                                        activeTrackColor = Color(0xFF00796B)
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(26.dp)
                                )
                            }

                            // Ultra-Fine Word Font Sizing & Formatting
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

                            // Action buttons: Permanently Erase & Apply to PDF
                           /* Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        applyWordEraseOrReplace(target, state.liveWordText)
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00796B)),
                                    modifier = Modifier.weight(1.2f).height(32.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) { Text("✓ Confirm Edit", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        applyWordEraseOrReplace(target, null)
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
                            }*/

                             // Action buttons: Confirm, Erase, or Convert directly to a full text box
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Button(
                                    onClick = {
                                        applyWordEraseOrReplace(target, state.liveWordText)
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00796B)),
                                    modifier = Modifier.weight(1.2f).height(32.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) { Text("✓ Confirm Edit", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        // Immediately wipe word on bitmap and turn into an interactive text box on the document
                                        applyWordEraseOrReplace(target, state.liveWordText.ifBlank { target.word })
                                        state.editingWordBox = null
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3F51B5)),
                                    modifier = Modifier.weight(1.2f).height(32.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) { Text("➕ Text Box on Doc", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        applyWordEraseOrReplace(target, null)
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                                    modifier = Modifier.weight(0.9f).height(32.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) { Text("🗑 Erase Word", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }

                                OutlinedButton(
                                    onClick = { state.editingWordBox = null },
                                    modifier = Modifier.weight(0.6f).height(32.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) { Text("Back", fontSize = 9.sp) }
                            }
                        }
                    }
                }
            }
        }

        // BOTTOM PAGE SEQUENCE STRIP (NON-DESTRUCTIVE PAGE NAVIGATION)
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

                        // Non-destructive Previous Page Navigation
                        Button(
                            onClick = {
                                if (state.activePageIndex > 0) {
                                    state.activePageIndex--
                                    state.activeElementId = null
                                    state.editingWordBox = null
                                    state.zoomScale = 1f
                                    state.panOffsetX = 0f
                                    state.panOffsetY = 0f
                                }
                            },
                            enabled = state.activePageIndex > 0,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(24.dp)
                        ) { Text("◀", fontSize = 10.sp) }

                        // Non-destructive Next Page Navigation
                        Button(
                            onClick = {
                                if (state.activePageIndex < state.pages.size - 1) {
                                    state.activePageIndex++
                                    state.activeElementId = null
                                    state.editingWordBox = null
                                    state.zoomScale = 1f
                                    state.panOffsetX = 0f
                                    state.panOffsetY = 0f
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
            onCropConfirmed = { cL: Float, cT: Float, cR: Float, cB: Float ->
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

    // MODAL: RICH TEXT FORMATTING WITH CONTINUOUS 0% TO 100% SHADING
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

                           /* Button(
                                onClick = { editingFontSizePt = (editingFontSizePt + 1f).coerceAtMost(72f) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(26.dp)
                            ) { Text("+") }
                        }

                        // CONTINUOUS 0% TO 100% SHADING CONTROLS
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Shading / Ink Density: ${(editingOpacity * 100).roundToInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.DarkGray
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    listOf(
                                        Pair("0%", 0.0f),
                                        Pair("25%", 0.25f),
                                        Pair("50%", 0.50f),
                                        Pair("75%", 0.75f),
                                        Pair("85%", 0.85f),
                                        Pair("100%", 1.0f)
                                    ).forEach { (lbl, valOp) ->
                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = if ((editingOpacity * 100).roundToInt() == (valOp * 100).roundToInt()) Color(0xFF00796B) else Color(0xFFECEFF1),
                                            modifier = Modifier.clickable { editingOpacity = valOp }
                                        ) {
                                            Text(
                                                text = lbl,
                                                fontSize = 8.sp,
                                                color = if ((editingOpacity * 100).roundToInt() == (valOp * 100).roundToInt()) Color.White else Color.Black,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Slider(
                                value = editingOpacity,
                                onValueChange = { editingOpacity = it },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF00796B),
                                    activeTrackColor = Color(0xFF00796B)
                                ),
                                modifier = Modifier.fillMaxWidth().height(30.dp)
                            )
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

                            // Keep detected words synced if this is a replaced word
                            if (elem.isReplacedWord && elem.associatedWordBoxId != null) {
                                val wBox = activePage?.detectedWords?.find { it.id == elem.associatedWordBoxId }
                                wBox?.replacedText = editingTextValue
                            }
                        }
                        showTextEditDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF15803D))
                ) { Text("Apply Format", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showTextEditDialog = false }) { Text("Cancel") }
            }
        )*/


                                    Button(
                                onClick = { editingFontSizePt = (editingFontSizePt + 1f).coerceAtMost(72f) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(26.dp)
                            ) { Text("+") }
                        }

                        // Ink Shade Palette (Text Only)
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

                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                    } // <--- Closes if (!editingIsWhiteout) so the sliders below show for Whiteout!

                    // --- WHITEOUT & TEXT SHARED CONTROLS (ALWAYS VISIBLE) ---
                    // Density / Fading Slider
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (editingIsWhiteout) "Whiteout Density (Fade): ${(editingOpacity * 100).roundToInt()}%" else "Shading / Ink Density: ${(editingOpacity * 100).roundToInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.DarkGray
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                listOf(
                                    Pair("25%", 0.25f),
                                    Pair("50%", 0.50f),
                                    Pair("75%", 0.75f),
                                    Pair("85%", 0.85f),
                                    Pair("100%", 1.0f)
                                ).forEach { (lbl, valOp) ->
                                    Surface(
                                        shape = RoundedCornerShape(3.dp),
                                        color = if ((editingOpacity * 100).roundToInt() == (valOp * 100).roundToInt()) Color(0xFF00796B) else Color(0xFFECEFF1),
                                        modifier = Modifier.clickable { editingOpacity = valOp }
                                    ) {
                                        Text(
                                            text = lbl,
                                            fontSize = 8.sp,
                                            color = if ((editingOpacity * 100).roundToInt() == (valOp * 100).roundToInt()) Color.White else Color.Black,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Slider(
                            value = editingOpacity,
                            onValueChange = { editingOpacity = it },
                            valueRange = 0.05f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00796B),
                                activeTrackColor = Color(0xFF00796B)
                            ),
                            modifier = Modifier.fillMaxWidth().height(30.dp)
                        )
                    }

                    // Background / Paper Texture Palette
                    Text(
                        text = if (editingIsWhiteout) "Match Paper Texture:" else "Background / Highlight Texture:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
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
                                   /* .clickable { editingBgColor = bg }*/
                                   .clickable {
                                        if (bg == Color.Transparent && editingIsWhiteout) {
                                            // Sample the exact paper tone at this whiteout location
                                            val elem = activePage?.elements?.find { it.id == state.activeElementId }
                                            editingBgColor = if (elem != null && activePage != null) {
                                                samplePurePaperBackground(activePage.baseBitmap, elem.relX, elem.relY, elem.relWidth, elem.relHeight)
                                            } else {
                                                Color.White
                                            }
                                        } else {
                                            editingBgColor = bg
                                        }
                                   }
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
            },
            confirmButton = {
                Button(
                    onClick = {
                        pushCanvasSnapshot()
                        activeElement?.let { elem ->
                            if (!elem.isWhiteout) {
                                elem.text = editingTextValue
                                elem.fontSizePt = editingFontSizePt
                                elem.isBold = editingIsBold
                                elem.isItalic = editingIsItalic
                                elem.textColor = editingColor
                                elem.relWidth = (editingTextValue.length * 0.018f).coerceIn(0.04f, 0.98f)
                            }
                            //elem.opacity = editingOpacity
                           // elem.backgroundColor = editingBgColor

                             elem.opacity = editingOpacity
                            elem.backgroundColor = if (elem.isWhiteout && editingBgColor == Color.Transparent) {
                                activePage?.let { page ->
                                    samplePurePaperBackground(page.baseBitmap, elem.relX, elem.relY, elem.relWidth, elem.relHeight)
                                } ?: Color.White
                            } else {
                                editingBgColor
                            }


                            // Keep detected words synced if this is a replaced word
                            if (elem.isReplacedWord && elem.associatedWordBoxId != null) {
                                val wBox = activePage?.detectedWords?.find { it.id == elem.associatedWordBoxId }
                                wBox?.replacedText = editingTextValue
                            }
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
fun FourCornerCropDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onCropConfirmed: (cL: Float, cT: Float, cR: Float, cB: Float) -> Unit
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
                                style = Stroke(width = 3f)
                            )
                        }

                        // 4 Draggable Corner Handles
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
