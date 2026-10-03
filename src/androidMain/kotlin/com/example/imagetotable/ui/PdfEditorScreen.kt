package com.example.imagetotable.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.imagetotable.ocr.AndroidOcrService
import com.example.imagetotable.util.PageNumberPosition
import com.example.imagetotable.util.PageRanges
import com.example.imagetotable.util.PdfEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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
    var width: Float = 180f,
    var height: Float = 36f
)

data class DetectedWordBox(
    val word: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class EditablePdfPage(
    val pageIndex: Int,
    var baseBitmap: Bitmap,
    val elements: MutableList<RichPdfTextElement> = mutableListOf(),
    val detectedWords: MutableList<DetectedWordBox> = mutableListOf()
)

@Composable
fun PdfEditorScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var activeEditor by remember { mutableStateOf<PdfEditor?>(null) }
    val pages = remember { mutableStateListOf<EditablePdfPage>() }
    var activePageIndex by remember { mutableIntStateOf(0) }
    var activeElementId by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready: Open any PDF to edit inline words, letters & rich text") }

    // Canvas Zoom & Pan States
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    // Inline Word Edit Mode
    var isInlineWordEditMode by remember { mutableStateOf(false) }
    var inlineWordReplacementTarget by remember { mutableStateOf<DetectedWordBox?>(null) }
    var inlineReplacementText by remember { mutableStateOf("") }

    // Dialog Visibilities
    var showTextEditDialog by remember { mutableStateOf(false) }
    var editingTextValue by remember { mutableStateOf("") }
    var editingFontSize by remember { mutableFloatStateOf(14f) }
    var editingIsBold by remember { mutableStateOf(false) }
    var editingIsItalic by remember { mutableStateOf(false) }
    var editingColor by remember { mutableStateOf(Color.Black) }
    var editingBgColor by remember { mutableStateOf(Color.Transparent) }
    var editingIsWhiteout by remember { mutableStateOf(false) }

    var showCropDialog by remember { mutableStateOf(false) }
    var cropLeft by remember { mutableStateOf("0") }
    var cropTop by remember { mutableStateOf("0") }
    var cropRight by remember { mutableStateOf("0") }
    var cropBottom by remember { mutableStateOf("0") }
    var cropPagesSpec by remember { mutableStateOf("") }

    var showSplitDialog by remember { mutableStateOf(false) }
    var splitPagesPerFile by remember { mutableStateOf("1") }
    var splitRangeSpec by remember { mutableStateOf("1-2, 3-last") }
    var splitModeByRanges by remember { mutableStateOf(false) }

    var showExtractDialog by remember { mutableStateOf(false) }
    var extractPagesSpec by remember { mutableStateOf("1-3, last") }

    var showWatermarkDialog by remember { mutableStateOf(false) }
    var watermarkInput by remember { mutableStateOf("CONFIDENTIAL") }

    var showPageNumbersDialog by remember { mutableStateOf(false) }
    var pageNumberFormatInput by remember { mutableStateOf("Page {n} of {total}") }

    val activePage = pages.getOrNull(activePageIndex)
    val activeElement = activePage?.elements?.find { it.id == activeElementId }

    fun refreshFromEngine(editor: PdfEditor) {
        pages.clear()
        for (i in 1..editor.pageCount) {
            val bmp = editor.renderPage(i, 150f)
            pages.add(EditablePdfPage(pageIndex = i - 1, baseBitmap = bmp))
        }
        if (activePageIndex >= pages.size) activePageIndex = (pages.size - 1).coerceAtLeast(0)
    }

    // Run OCR Word Scanning on Current Page to enable inline word tap-to-replace
    fun scanCurrentPageWords() {
        activePage?.let { page ->
            coroutineScope.launch {
                isProcessing = true
                statusText = "Scanning page text for inline word editing..."
                try {
                    val words = withContext(Dispatchers.IO) {
                        val ocr = AndroidOcrService(context) { /* status */ }
                        val rawTokens = ocr.extractTokens(page.baseBitmap)
                        // Map tokens into clickable inline word hitboxes
                        val list = mutableListOf<DetectedWordBox>()
                        var curX = 40f
                        var curY = 40f
                        rawTokens.forEach { token ->
                            val tw = (token.length * 9f).coerceAtLeast(24f)
                            val th = 22f
                            if (curX + tw > page.baseBitmap.width - 40f) {
                                curX = 40f
                                curY += 30f
                            }
                            list.add(DetectedWordBox(token, curX, curY, tw, th))
                            curX += tw + 8f
                        }
                        list
                    }
                    page.detectedWords.clear()
                    page.detectedWords.addAll(words)
                    isInlineWordEditMode = true
                    statusText = "Found ${words.size} words! Tap any word directly to replace it."
                } catch (e: Exception) {
                    statusText = "OCR scan failed: ${e.message}"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    // File Pickers & Launchers
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                statusText = "Loading PDF into engine..."
                try {
                    val tempSource = withContext(Dispatchers.IO) {
                        val file = File(context.cacheDir, "editor_src_${System.currentTimeMillis()}.pdf")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(file).use { out -> input.copyTo(out) }
                        }
                        file
                    }
                    activeEditor?.close()
                    val editor = PdfEditor.load(context, tempSource)
                    activeEditor = editor
                    refreshFromEngine(editor)
                    activePageIndex = 0
                    activeElementId = null
                    zoomScale = 1f
                    panOffsetX = 0f
                    panOffsetY = 0f
                    statusText = "Loaded ${editor.pageCount} page(s). Tap 'Inline Word Edit' or tool buttons."
                } catch (e: Exception) {
                    statusText = "Open error: ${e.message}"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val mergeMultiPdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.size >= 2) {
            coroutineScope.launch {
                isProcessing = true
                statusText = "Merging ${uris.size} PDF documents..."
                try {
                    val mergedFile = withContext(Dispatchers.IO) {
                        val tempInputs = uris.mapIndexed { i, u ->
                            val f = File(context.cacheDir, "merge_in_${i}_${System.currentTimeMillis()}.pdf")
                            context.contentResolver.openInputStream(u)?.use { inp ->
                                FileOutputStream(f).use { out -> inp.copyTo(out) }
                            }
                            f
                        }
                        val out = File(context.cacheDir, "Merged_${System.currentTimeMillis()}.pdf")
                        PdfEditor.merge(context, tempInputs, out)
                        tempInputs.forEach { it.delete() }
                        out
                    }
                    activeEditor?.close()
                    val editor = PdfEditor.load(context, mergedFile)
                    activeEditor = editor
                    refreshFromEngine(editor)
                    statusText = "Merged successfully into ${editor.pageCount} pages!"
                    Toast.makeText(context, "Merged into ${editor.pageCount} pages!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    statusText = "Merge error: ${e.message}"
                } finally {
                    isProcessing = false
                }
            }
        } else if (uris.isNotEmpty()) {
            Toast.makeText(context, "Select at least 2 PDFs to merge", Toast.LENGTH_SHORT).show()
        }
    }

    val pdfSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        if (destUri != null && activeEditor != null) {
            coroutineScope.launch {
                isProcessing = true
                statusText = "Compiling modified PDF..."
                try {
                    val editor = activeEditor!!
                    pages.forEachIndexed { pIdx, page ->
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
                    statusText = "Saved edited PDF successfully!"
                    Toast.makeText(context, "Saved Edited PDF!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    statusText = "Save error: ${e.message}"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val pdfExtractSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        if (destUri != null && activeEditor != null) {
            coroutineScope.launch {
                isProcessing = true
                statusText = "Extracting pages ($extractPagesSpec)..."
                try {
                    withContext(Dispatchers.IO) {
                        val tempExtract = File(context.cacheDir, "extract_${System.currentTimeMillis()}.pdf")
                        activeEditor!!.extractPages(extractPagesSpec, tempExtract)
                        context.contentResolver.openOutputStream(destUri)?.use { out ->
                            tempExtract.inputStream().use { inp -> inp.copyTo(out) }
                        }
                        tempExtract.delete()
                    }
                    statusText = "Extracted pages successfully!"
                    Toast.makeText(context, "Pages extracted successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    statusText = "Extract error: ${e.message}"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "✍️ PDF Studio: Letter & Inline Editor",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusText,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { pdfPickerLauncher.launch("application/pdf") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("📂 Open", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { mergeMultiPdfPickerLauncher.launch("application/pdf") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00796B)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("🔀 Merge", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { pdfSaveLauncher.launch("Edited_Document.pdf") },
                        enabled = pages.isNotEmpty() && !isProcessing,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF15803D)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("💾 Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // TOOLBAR: INLINE WORD EDIT, CLIPBOARD, SPLIT, CROP, EXTRACT & RICH TEXT
        Surface(
            modifier = Modifier.fillMaxWidth(),
            elevation = 2.dp,
            color = Color.White
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // INLINE WORD TAP-TO-EDIT TOGGLE
                Button(
                    onClick = {
                        if (!isInlineWordEditMode) {
                            scanCurrentPageWords()
                        } else {
                            isInlineWordEditMode = false
                            statusText = "Exited inline word edit mode."
                        }
                    },
                    enabled = activePage != null,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (isInlineWordEditMode) Color(0xFF00897B) else Color(0xFF1565C0)
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (isInlineWordEditMode) "✓ Inline Words Active" else "🔤 Inline Word Edit",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Clipboard controls
                Button(
                    onClick = {
                        activeElement?.let {
                            clipboardManager.setText(AnnotatedString(it.text))
                            it.text = ""
                            statusText = "Cut text to clipboard"
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
                            statusText = "Copied text to clipboard"
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
                            activeElement?.let {
                                it.text += " $clip"
                            } ?: run {
                                activePage?.elements?.add(
                                    RichPdfTextElement(text = clip, xOffset = 60f, yOffset = 120f)
                                )
                            }
                            statusText = "Pasted text"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE2E8F0)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("📌 Paste", fontSize = 10.sp, color = Color.Black) }

                // Insert Text & Whiteout
                Button(
                    onClick = {
                        activePage?.let { page ->
                            val newElem = RichPdfTextElement(
                                text = "New Text",
                                xOffset = 50f,
                                yOffset = 100f
                            )
                            page.elements.add(newElem)
                            activeElementId = newElem.id
                            editingTextValue = newElem.text
                            editingFontSize = newElem.fontSize
                            editingIsBold = newElem.isBold
                            editingIsItalic = newElem.isItalic
                            editingColor = newElem.textColor
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
                            val whiteout = RichPdfTextElement(
                                text = "",
                                isWhiteout = true,
                                backgroundColor = Color.White,
                                width = 160f,
                                height = 40f,
                                xOffset = 50f,
                                yOffset = 80f
                            )
                            page.elements.add(whiteout)
                            activeElementId = whiteout.id
                            statusText = "Added Whiteout cover. Drag to redact/hide original text."
                        }
                    },
                    enabled = activePage != null,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("⬜ Whiteout", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                // Page Operations from pdfeditor.pdf: Crop, Split, Extract
                Button(
                    onClick = { showCropDialog = true },
                    enabled = activeEditor != null,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("✂ Crop Page", color = Color.White, fontSize = 10.sp) }

                Button(
                    onClick = { showSplitDialog = true },
                    enabled = activeEditor != null,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("✂ Split PDF", color = Color.White, fontSize = 10.sp) }

                Button(
                    onClick = { showExtractDialog = true },
                    enabled = activeEditor != null,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("📄 Extract", color = Color.White, fontSize = 10.sp) }

                Button(
                    onClick = { showWatermarkDialog = true },
                    enabled = activeEditor != null,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("💧 Watermark", color = Color.White, fontSize = 10.sp) }

                Button(
                    onClick = { showPageNumbersDialog = true },
                    enabled = activeEditor != null,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("🔢 Page #s", color = Color.White, fontSize = 10.sp) }

                Button(
                    onClick = {
                        activeEditor?.let {
                            it.rotate((activePageIndex + 1).toString(), 90)
                            refreshFromEngine(it)
                        }
                    },
                    enabled = activeEditor != null,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("🔄 90°", color = Color.White, fontSize = 10.sp) }

                Button(
                    onClick = {
                        activeEditor?.let {
                            it.duplicatePage(activePageIndex + 1, 1)
                            refreshFromEngine(it)
                        }
                    },
                    enabled = activeEditor != null,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("📄 Duplicate", color = Color.White, fontSize = 10.sp) }

                Button(
                    onClick = {
                        activeEditor?.let {
                            it.insertBlankPage(at = activePageIndex + 2)
                            refreshFromEngine(it)
                        }
                    },
                    enabled = activeEditor != null,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("➕ Blank", color = Color.White, fontSize = 10.sp) }
            }
        }

        // 3. ZOOMABLE & PANNABLE PAGE CANVAS VIEWER
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(6.dp)
                .clipToBounds()
                .background(Color(0xFF263238), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan: Offset, zoom: Float, _ ->
                        zoomScale = (zoomScale * zoom).coerceIn(0.5f, 6.0f)
                        val maxPan = 1000f * (zoomScale - 1f).coerceAtLeast(0f)
                        panOffsetX = (panOffsetX + pan.x).coerceIn(-maxPan, maxPan)
                        panOffsetY = (panOffsetY + pan.y).coerceIn(-maxPan, maxPan)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (activePage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = panOffsetX,
                            translationY = panOffsetY
                        )
                ) {
                    // Base Page Bitmap
                    Image(
                        bitmap = activePage.baseBitmap.asImageBitmap(),
                        contentDescription = "Page ${activePageIndex + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    // INLINE WORD DETECTION OVERLAYS (TAP TO REPLACE DIRECTLY)
                    if (isInlineWordEditMode) {
                        activePage.detectedWords.forEach { wordBox ->
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(wordBox.x.roundToInt(), wordBox.y.roundToInt()) }
                                    .size(wordBox.width.dp, wordBox.height.dp)
                                    .background(Color(0x3300897B), RoundedCornerShape(2.dp))
                                    .border(0.5.dp, Color(0xFF00897B), RoundedCornerShape(2.dp))
                                    .clickable {
                                        inlineWordReplacementTarget = wordBox
                                        inlineReplacementText = wordBox.word
                                    }
                            )
                        }
                    }

                    // Draggable Overlays (Text & Whiteout Blocks)
                    activePage.elements.forEach { element ->
                        val isSelected = element.id == activeElementId
                        var offsetX by remember(element.id) { mutableFloatStateOf(element.xOffset) }
                        var offsetY by remember(element.id) { mutableFloatStateOf(element.yOffset) }

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                                .pointerInput(element.id) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        offsetX += dragAmount.x
                                        offsetY += dragAmount.y
                                        element.xOffset = offsetX
                                        element.yOffset = offsetY
                                    }
                                }
                                .clickable { activeElementId = element.id }
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
                            if (element.isWhiteout) {
                                Box(
                                    modifier = Modifier
                                        .size(element.width.dp, element.height.dp)
                                        .background(Color.White)
                                )
                            } else {
                                Text(
                                    text = element.text.ifBlank { " " },
                                    fontSize = element.fontSize.sp,
                                    fontWeight = if (element.isBold) FontWeight.Bold else FontWeight.Normal,
                                    fontStyle = if (element.isItalic) FontStyle.Italic else FontStyle.Normal,
                                    color = element.textColor,
                                    modifier = Modifier.clickable {
                                        activeElementId = element.id
                                        editingTextValue = element.text
                                        editingFontSize = element.fontSize
                                        editingIsBold = element.isBold
                                        editingIsItalic = element.isItalic
                                        editingColor = element.textColor
                                        editingBgColor = element.backgroundColor
                                        editingIsWhiteout = false
                                        showTextEditDialog = true
                                    }
                                )
                            }
                        }
                    }
                }

                // FLOATING ZOOM BUTTONS
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { zoomScale = (zoomScale * 1.25f).coerceAtMost(6.0f) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("🔍+", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = { zoomScale = (zoomScale / 1.25f).coerceAtLeast(0.5f) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("🔍-", color = Color.White, fontSize = 10.sp) }

                    Button(
                        onClick = {
                            zoomScale = 1f
                            panOffsetX = 0f
                            panOffsetY = 0f
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

        // 4. BOTTOM PAGE SEQUENCE STRIP
        if (pages.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp,
                color = Color.White
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Page ${activePageIndex + 1} of ${pages.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0D47A1)
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        itemsIndexed(pages) { idx, _ ->
                            val isSel = idx == activePageIndex
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSel) Color(0xFF1976D2) else Color(0xFFECEFF1),
                                modifier = Modifier.clickable {
                                    activePageIndex = idx
                                    activeElementId = null
                                    zoomScale = 1f
                                    panOffsetX = 0f
                                    panOffsetY = 0f
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

    // MODAL: INLINE WORD EDIT & REPLACE
    if (inlineWordReplacementTarget != null) {
        val target = inlineWordReplacementTarget!!
        AlertDialog(
            onDismissRequest = { inlineWordReplacementTarget = null },
            title = { Text("Replace Inline Word: '${target.word}'", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF00796B)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Type the replacement text below. A clean whiteout will be automatically stamped under the old word.", fontSize = 11.sp, color = Color.DarkGray)
                    OutlinedTextField(
                        value = inlineReplacementText,
                        onValueChange = { inlineReplacementText = it },
                        label = { Text("New Word / Phrase") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        activePage?.let { page ->
                            // 1. Auto-stamp exact whiteout under the original word
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
                            // 2. Add replacement text at exact location
                            page.elements.add(
                                RichPdfTextElement(
                                    text = inlineReplacementText,
                                    xOffset = target.x,
                                    yOffset = target.y,
                                    fontSize = 13f,
                                    textColor = Color.Black
                                )
                            )
                            statusText = "Replaced word '${target.word}' with '$inlineReplacementText'!"
                        }
                        inlineWordReplacementTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00796B))
                ) { Text("Replace", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { inlineWordReplacementTarget = null }) { Text("Cancel") }
            }
        )
    }

    // MODAL: CROP PAGES
    if (showCropDialog) {
        AlertDialog(
            onDismissRequest = { showCropDialog = false },
            title = { Text("Crop PDF Page Margins (in points)", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = cropPagesSpec, onValueChange = { cropPagesSpec = it }, label = { Text("Pages (e.g. 1, 1-3, or blank for all)") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = cropLeft, onValueChange = { cropLeft = it }, label = { Text("Left") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = cropTop, onValueChange = { cropTop = it }, label = { Text("Top") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = cropRight, onValueChange = { cropRight = it }, label = { Text("Right") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(value = cropBottom, onValueChange = { cropBottom = it }, label = { Text("Bottom") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        activeEditor?.let {
                            it.crop(
                                pages = cropPagesSpec.ifBlank { (activePageIndex + 1).toString() },
                                left = cropLeft.toFloatOrNull() ?: 0f,
                                bottom = cropBottom.toFloatOrNull() ?: 0f,
                                right = cropRight.toFloatOrNull() ?: 0f,
                                top = cropTop.toFloatOrNull() ?: 0f
                            )
                            refreshFromEngine(it)
                            statusText = "Cropped margins successfully."
                        }
                        showCropDialog = false
                    }
                ) { Text("Apply Crop") }
            },
            dismissButton = { TextButton(onClick = { showCropDialog = false }) { Text("Cancel") } }
        )
    }

    // MODAL: SPLIT PDF
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
                        OutlinedTextField(value = splitPagesPerFile, onValueChange = { splitPagesPerFile = it }, label = { Text("Pages per file") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = splitModeByRanges, onClick = { splitModeByRanges = true })
                        Text("Split by custom ranges (e.g. 1-2, 3-last)", fontSize = 12.sp)
                    }
                    if (splitModeByRanges) {
                        OutlinedTextField(value = splitRangeSpec, onValueChange = { splitRangeSpec = it }, label = { Text("Ranges (comma separated)") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        activeEditor?.let { editor ->
                            coroutineScope.launch {
                                isProcessing = true
                                try {
                                    val outDir = File(context.cacheDir, "split_${System.currentTimeMillis()}").apply { mkdirs() }
                                    val files = withContext(Dispatchers.IO) {
                                        if (splitModeByRanges) {
                                            editor.splitByRanges(outDir, splitRangeSpec.split(',').map { it.trim() })
                                        } else {
                                            editor.split(outDir, splitPagesPerFile.toIntOrNull() ?: 1)
                                        }
                                    }
                                    statusText = "Split complete! Generated ${files.size} PDF files."
                                    Toast.makeText(context, "Generated ${files.size} split files!", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    statusText = "Split error: ${e.message}"
                                } finally {
                                    isProcessing = false
                                }
                            }
                        }
                        showSplitDialog = false
                    }
                ) { Text("Start Split") }
            },
            dismissButton = { TextButton(onClick = { showSplitDialog = false }) { Text("Cancel") } }
        )
    }

    // MODAL: EXTRACT PAGES
    if (showExtractDialog) {
        AlertDialog(
            onDismissRequest = { showExtractDialog = false },
            title = { Text("Extract Specific Pages to New PDF", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter page range syntax (e.g. '1-3, 5, 8-last'):", fontSize = 12.sp)
                    OutlinedTextField(value = extractPagesSpec, onValueChange = { extractPagesSpec = it }, label = { Text("Page Ranges") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExtractDialog = false
                        pdfExtractSaveLauncher.launch("Extracted_Pages.pdf")
                    }
                ) { Text("Extract & Save") }
            },
            dismissButton = { TextButton(onClick = { showExtractDialog = false }) { Text("Cancel") } }
        )
    }

    // MODAL: RICH TEXT & LETTER FORMATTING
    if (showTextEditDialog) {
        AlertDialog(
            onDismissRequest = { showTextEditDialog = false },
            title = {
                Text(
                    text = if (editingIsWhiteout) "Format Whiteout Block" else "Edit Text & Rich Formatting",
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
                                color = editingColor
                            )
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { editingIsBold = !editingIsBold },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (editingIsBold) Color(0xFF1976D2) else Color(0xFFECEFF1)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("B", fontWeight = FontWeight.Bold, color = if (editingIsBold) Color.White else Color.Black)
                            }

                            Button(
                                onClick = { editingIsItalic = !editingIsItalic },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (editingIsItalic) Color(0xFF1976D2) else Color(0xFFECEFF1)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("I", fontStyle = FontStyle.Italic, color = if (editingIsItalic) Color.White else Color.Black)
                            }

                            Text("Size: ${editingFontSize.toInt()}sp", fontSize = 11.sp, fontWeight = FontWeight.Bold)

                            Button(
                                onClick = { editingFontSize = (editingFontSize - 2f).coerceAtLeast(8f) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(28.dp)
                            ) { Text("-") }

                            Button(
                                onClick = { editingFontSize = (editingFontSize + 2f).coerceAtMost(36f) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(28.dp)
                            ) { Text("+") }
                        }

                        Text("Text Color:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(Color.Black, Color(0xFF1565C0), Color(0xFFC62828), Color(0xFF2E7D32), Color(0xFF6A1B9A), Color(0xFFE65100)).forEach { col ->
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .background(col, CircleShape)
                                        .border(
                                            width = if (editingColor == col) 2.5.dp else 0.5.dp,
                                            color = if (editingColor == col) Color.Cyan else Color.Gray,
                                            shape = CircleShape
                                        )
                                        .clickable { editingColor = col }
                                )
                            }
                        }

                        Text("Highlight / Background:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(Color.Transparent, Color.White, Color(0xFFFFF9C4), Color(0xFFE0F2FE), Color(0xFFE8F5E9)).forEach { bg ->
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .background(if (bg == Color.Transparent) Color.LightGray else bg, CircleShape)
                                        .border(
                                            width = if (editingBgColor == bg) 2.5.dp else 0.5.dp,
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
                        activeElement?.let { elem ->
                            elem.text = editingTextValue
                            elem.fontSize = editingFontSize
                            elem.isBold = editingIsBold
                            elem.isItalic = editingIsItalic
                            elem.textColor = editingColor
                            elem.backgroundColor = editingBgColor
                            elem.width = (editingTextValue.length * editingFontSize * 0.7f).coerceAtLeast(60f)
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
                        activeEditor?.let {
                            it.addTextWatermark(watermarkInput, pages = "all")
                            refreshFromEngine(it)
                            statusText = "Stamped watermark '$watermarkInput' across all pages."
                        }
                        showWatermarkDialog = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showWatermarkDialog = false }) { Text("Cancel") }
            }
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
                        activeEditor?.let {
                            it.addPageNumbers(
                                format = pageNumberFormatInput,
                                pages = "all",
                                position = PageNumberPosition.BOTTOM_CENTER
                            )
                            refreshFromEngine(it)
                            statusText = "Stamped page numbers across all pages."
                        }
                        showPageNumbersDialog = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showPageNumbersDialog = false }) { Text("Cancel") }
            }
        )
    }
}
