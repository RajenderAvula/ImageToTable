package com.example.imagetotable.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

data class EditablePdfPage(
    val pageIndex: Int,
    val baseBitmap: Bitmap,
    val elements: MutableList<RichPdfTextElement> = mutableListOf()
)

@Composable
fun PdfEditorScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val pages = remember { mutableStateListOf<EditablePdfPage>() }
    var activePageIndex by remember { mutableIntStateOf(0) }
    var activeElementId by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready: Open any PDF to edit letters & rich text") }

    // Text Editor Modal State
    var showTextEditDialog by remember { mutableStateOf(false) }
    var editingTextValue by remember { mutableStateOf("") }
    var editingFontSize by remember { mutableFloatStateOf(14f) }
    var editingIsBold by remember { mutableStateOf(false) }
    var editingIsItalic by remember { mutableStateOf(false) }
    var editingColor by remember { mutableStateOf(Color.Black) }
    var editingBgColor by remember { mutableStateOf(Color.Transparent) }
    var editingIsWhiteout by remember { mutableStateOf(false) }

    val activePage = pages.getOrNull(activePageIndex)
    val activeElement = activePage?.elements?.find { it.id == activeElementId }

    // Launcher to Open External PDF
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                statusText = "Loading and rasterizing PDF pages..."
                try {
                    val loadedPages = withContext(Dispatchers.IO) {
                        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                            ?: throw IllegalArgumentException("Could not open PDF descriptor")
                        val renderer = PdfRenderer(pfd)
                        val result = mutableListOf<EditablePdfPage>()

                        for (i in 0 until renderer.pageCount) {
                            val page = renderer.openPage(i)
                            val scale = 2.0f
                            val w = (page.width * scale).toInt().coerceAtMost(2048)
                            val h = (page.height * scale).toInt().coerceAtMost(2048)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bmp)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            result.add(EditablePdfPage(pageIndex = i, baseBitmap = bmp))
                        }
                        renderer.close()
                        pfd.close()
                        result
                    }
                    pages.clear()
                    pages.addAll(loadedPages)
                    activePageIndex = 0
                    activeElementId = null
                    statusText = "Loaded ${loadedPages.size} page(s). Tap '+ Text' or '+ Whiteout' to edit."
                } catch (e: Exception) {
                    statusText = "Open PDF error: ${e.message}"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    // Launcher to Save Exported PDF
    val pdfSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        if (destUri != null) {
            coroutineScope.launch {
                isProcessing = true
                statusText = "Compiling modified text overlays into PDF..."
                try {
                    withContext(Dispatchers.IO) {
                        val pdfDoc = PdfDocument()

                        pages.forEachIndexed { idx, pageItem ->
                            val pageInfo = PdfDocument.PageInfo.Builder(
                                pageItem.baseBitmap.width,
                                pageItem.baseBitmap.height,
                                idx + 1
                            ).create()
                            val page = pdfDoc.startPage(pageInfo)
                            val canvas = page.canvas

                            // Draw original page background
                            canvas.drawBitmap(pageItem.baseBitmap, 0f, 0f, null)

                            // Render each edited text / whiteout element
                            pageItem.elements.forEach { elem ->
                                if (elem.isWhiteout) {
                                    val bgPaint = Paint().apply {
                                        color = elem.backgroundColor.toArgb()
                                        style = Paint.Style.FILL
                                    }
                                    canvas.drawRect(
                                        elem.xOffset,
                                        elem.yOffset,
                                        elem.xOffset + elem.width,
                                        elem.yOffset + elem.height,
                                        bgPaint
                                    )
                                } else {
                                    if (elem.backgroundColor != Color.Transparent) {
                                        val bgPaint = Paint().apply {
                                            color = elem.backgroundColor.toArgb()
                                            style = Paint.Style.FILL
                                        }
                                        canvas.drawRect(
                                            elem.xOffset - 4f,
                                            elem.yOffset - elem.fontSize * 1.5f,
                                            elem.xOffset + elem.width + 4f,
                                            elem.yOffset + 6f,
                                            bgPaint
                                        )
                                    }
                                    val textPaint = Paint().apply {
                                        color = elem.textColor.toArgb()
                                        textSize = elem.fontSize * 2f
                                        isAntiAlias = true
                                        val tfStyle = when {
                                            elem.isBold && elem.isItalic -> Typeface.BOLD_ITALIC
                                            elem.isBold -> Typeface.BOLD
                                            elem.isItalic -> Typeface.ITALIC
                                            else -> Typeface.NORMAL
                                        }
                                        typeface = Typeface.create(Typeface.DEFAULT, tfStyle)
                                    }
                                    canvas.drawText(elem.text, elem.xOffset, elem.yOffset, textPaint)
                                }
                            }
                            pdfDoc.finishPage(page)
                        }

                        context.contentResolver.openOutputStream(destUri)?.use { out ->
                            pdfDoc.writeTo(out)
                        }
                        pdfDoc.close()
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
                        text = "✍️ PDF Studio: Letter & Rich Text Editor",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusText,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { pdfPickerLauncher.launch("application/pdf") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("📂 Open PDF", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { pdfSaveLauncher.launch("Edited_Document.pdf") },
                        enabled = pages.isNotEmpty() && !isProcessing,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF15803D)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("💾 Save PDF", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // EDITING TOOLBAR: CUT, COPY, PASTE, RICH TEXT & WHITEOUT
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

                Spacer(modifier = Modifier.width(4.dp))

                // Insert elements
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
                ) { Text("➕ Text Box", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

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

                if (activeElement != null) {
                    Button(
                        onClick = {
                            activeElement?.let { elem ->
                                editingTextValue = elem.text
                                editingFontSize = elem.fontSize
                                editingIsBold = elem.isBold
                                editingIsItalic = elem.isItalic
                                editingColor = elem.textColor
                                editingBgColor = elem.backgroundColor
                                editingIsWhiteout = elem.isWhiteout
                                showTextEditDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("✎ Format", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = {
                            activePage?.elements?.removeAll { it.id == activeElementId }
                            activeElementId = null
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("✕ Delete", color = Color.White, fontSize = 10.sp) }
                }
            }
        }

        // PAGE CANVAS VIEWER
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (activePage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                    shape = RoundedCornerShape(8.dp),
                    elevation = 4.dp
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Base Rendered PDF Page
                        Image(
                            bitmap = activePage.baseBitmap.asImageBitmap(),
                            contentDescription = "Page ${activePageIndex + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // Editable Overlay Elements
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
                                    .clickable {
                                        activeElementId = element.id
                                    }
                                    .background(
                                        if (element.isWhiteout) element.backgroundColor
                                        else element.backgroundColor,
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
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "No PDF loaded. Open a document to begin letter & rich text editing.",
                        color = Color.Gray,
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

        // BOTTOM PAGE SEQUENCE STRIP
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
                        itemsIndexed(pages) { idx, pageItem ->
                            val isSel = idx == activePageIndex
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSel) Color(0xFF1976D2) else Color(0xFFECEFF1),
                                modifier = Modifier.clickable {
                                    activePageIndex = idx
                                    activeElementId = null
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

    // MODAL DIALOG: RICH TEXT, FONT, COLOR & SIZE FORMATTER
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

                        // Rich Style Toggles: Bold, Italic
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

                        // Text Color Palette
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

                        // Background Highlight Palette
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
}
