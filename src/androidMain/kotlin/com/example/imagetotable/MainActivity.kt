package com.example.imagetotable

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.imagetotable.model.*
import com.example.imagetotable.ocr.AndroidOcrService
import com.example.imagetotable.ui.*
import com.example.imagetotable.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class ExportFormat(val extension: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    EXCEL("csv", "text/csv"),
    WORD("doc", "application/msword")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MobileTableEditorScreen()
            }
        }
    }
}

@Composable
fun MobileTableEditorScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val initialTable = remember {
        TableData(
            initialName = "Invoice Extraction",
            initialHeaders = listOf(
                ColumnDef("SKU / Code", ColumnType.TEXT),
                ColumnDef("Description", ColumnType.TEXT),
                ColumnDef("Qty", ColumnType.NUMBER),
                ColumnDef("Price ($)", ColumnType.DECIMAL)
            ),
            initialRows = listOf(
                listOf("A-101", "Ballpoint Pens", "50", "1.25"),
                listOf("B-204", "A4 Paper Reams", "10", "4.50"),
                listOf("C-305", "Desk Organizer", "3", "12.00")
            ),
            initialCorner = "Item #"
        ).also { TableRepository.saveOrUpdate(it) }
    }

    var currentTable by remember { mutableStateOf(initialTable) }
    var tableSnapshot by remember { mutableStateOf(initialTable.createSnapshot()) }

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedCells = remember { mutableStateListOf<Pair<Int, Int>>(Pair(0, 0)) }
    var anchorCell by remember { mutableStateOf(Pair(0, 0)) }
    var cellClipboard by remember { mutableStateOf<CellClipboard?>(null) }

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val detectedWords = remember { mutableStateListOf<String>() }
    val selectedTokens = remember { mutableStateListOf<String>() }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready") }

    var isFullScreen by remember { mutableStateOf(false) }
    var showScanWorkspaceInTable by remember { mutableStateOf(false) }

    // Dialog Visibilities
    var showCropperDialog by remember { mutableStateOf(false) }
    var cropperTargetPageIndex by remember { mutableStateOf<Int?>(null) }
    var showDedicatedEditor by remember { mutableStateOf(false) }
    var showNewTableDialog by remember { mutableStateOf(false) }
    var showAllWordsDialog by remember { mutableStateOf(false) }
    var showTableHistoryDrawer by remember { mutableStateOf(false) }
    var showDeleteTableConfirm by remember { mutableStateOf(false) }
    var showClearTableConfirm by remember { mutableStateOf(false) }
    var showRowEditorDialog by remember { mutableStateOf(false) }
    var editingRowIndex by remember { mutableIntStateOf(0) }

    // Column Value Filter Dialog State
    var activeFilterColIdx by remember { mutableStateOf<Int?>(null) }

    // Post-Generation PDF Inspector Dialog
    var showGeneratedPdfInspector by remember { mutableStateOf(false) }
    var generatedPdfSizeBytes by remember { mutableLongStateOf(0L) }

    // Extraction Preview Verification State
    var showExtractionPreviewDialog by remember { mutableStateOf(false) }
    var previewCroppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingExtractedHeaders by remember { mutableStateOf<List<ColumnDef>>(emptyList()) }
    var pendingExtractedRows by remember { mutableStateOf<List<List<String>>>(emptyList()) }

    var showExportMenu by remember { mutableStateOf(false) }
    var activeExportFormat by remember { mutableStateOf(ExportFormat.PDF) }

    // Search & Filter States
    var globalSearchQuery by remember { mutableStateOf("") }
    val hiddenColumns = remember { mutableStateListOf<Int>() }
    var drawerSearchQuery by remember { mutableStateOf("") }
    val columnValueFilters = remember { mutableStateMapOf<Int, Set<String>>() }

    // PDF Studio State
    val pdfPages = remember { mutableStateListOf<PdfPageItem>() }
    var cacheSizeText by remember { mutableStateOf(CacheManager.getFormattedCacheSize(context)) }

    fun jumpToNextRow() {
        val (cr, cc) = anchorCell
        val targetCol = if (cc < 0) 0 else cc
        if (cr >= 0 && cr < currentTable.rows.size - 1) {
            anchorCell = Pair(cr + 1, targetCol)
            selectedCells.clear()
            selectedCells.add(Pair(cr + 1, targetCol))
        } else if (cr < 0 && currentTable.rows.isNotEmpty()) {
            anchorCell = Pair(0, targetCol)
            selectedCells.clear()
            selectedCells.add(Pair(0, targetCol))
        } else {
            currentTable.addRow("Row ${currentTable.rows.size + 1}")
            anchorCell = Pair(currentTable.rows.size - 1, targetCol)
            selectedCells.clear()
            selectedCells.add(anchorCell)
            statusMessage = "Added & jumped to new row!"
        }
    }

    fun printTablePdf() {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            val cacheFile = File(context.cacheDir, "print_table.pdf")
            FileOutputStream(cacheFile).use { out -> TableExporter.exportToPdf(currentTable, out) }
            if (printManager != null) {
                val printAdapter = object : android.print.PrintDocumentAdapter() {
                    override fun onLayout(
                        oldAttributes: PrintAttributes?,
                        newAttributes: PrintAttributes?,
                        cancellationSignal: android.os.CancellationSignal?,
                        callback: LayoutResultCallback?,
                        extras: Bundle?
                    ) {
                        callback?.onLayoutFinished(
                            android.print.PrintDocumentInfo.Builder("${currentTable.tableName}.pdf")
                                .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                .build(),
                            true
                        )
                    }
                    override fun onWrite(
                        pages: Array<out android.print.PageRange>?,
                        destination: android.os.ParcelFileDescriptor?,
                        cancellationSignal: android.os.CancellationSignal?,
                        callback: WriteResultCallback?
                    ) {
                        try {
                            destination?.let { pfd ->
                                FileOutputStream(pfd.fileDescriptor).use { output ->
                                    cacheFile.inputStream().use { input -> input.copyTo(output) }
                                }
                            }
                            callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                        } catch (e: Exception) {
                            callback?.onWriteFailed(e.message)
                        }
                    }
                }
                printManager.print(currentTable.tableName, printAdapter, PrintAttributes.Builder().build())
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Print failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun shareTablePdf() {
        try {
            val cacheFile = File(context.cacheDir, "${currentTable.tableName.replace(" ", "_")}.pdf")
            FileOutputStream(cacheFile).use { out -> TableExporter.exportToPdf(currentTable, out) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", cacheFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, currentTable.tableName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Table PDF"))
        } catch (e: Exception) {
            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun printPdfStudioDocument(targetKb: Int?) {
        if (pdfPages.isEmpty()) return
        coroutineScope.launch {
            try {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                val cacheFile = File(context.cacheDir, "studio_print.pdf")
                FileOutputStream(cacheFile).use { out ->
                    PdfCompressorExporter.exportPagesToPdf(pdfPages.toList(), targetKb, out) { statusMessage = it }
                }
                if (printManager != null) {
                    val printAdapter = object : android.print.PrintDocumentAdapter() {
                        override fun onLayout(
                            oldAttributes: PrintAttributes?,
                            newAttributes: PrintAttributes?,
                            cancellationSignal: android.os.CancellationSignal?,
                            callback: LayoutResultCallback?,
                            extras: Bundle?
                        ) {
                            callback?.onLayoutFinished(
                                android.print.PrintDocumentInfo.Builder("Studio_Document.pdf")
                                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                    .build(),
                                true
                            )
                        }
                        override fun onWrite(
                            pages: Array<out android.print.PageRange>?,
                            destination: android.os.ParcelFileDescriptor?,
                            cancellationSignal: android.os.CancellationSignal?,
                            callback: WriteResultCallback?
                        ) {
                            try {
                                destination?.let { pfd ->
                                    FileOutputStream(pfd.fileDescriptor).use { output ->
                                        cacheFile.inputStream().use { input -> input.copyTo(output) }
                                    }
                                }
                                callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                            } catch (e: Exception) {
                                callback?.onWriteFailed(e.message)
                            }
                        }
                    }
                    printManager.print("PDF_Studio_Document", printAdapter, PrintAttributes.Builder().build())
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Print error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun sharePdfStudioDocument(targetKb: Int?) {
        if (pdfPages.isEmpty()) return
        coroutineScope.launch {
            try {
                val cacheFile = File(context.cacheDir, "shared_document.pdf")
                FileOutputStream(cacheFile).use { out ->
                    PdfCompressorExporter.exportPagesToPdf(pdfPages.toList(), targetKb, out) { statusMessage = it }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", cacheFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "PDF Document")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share PDF"))
            } catch (e: Exception) {
                Toast.makeText(context, "Share error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Launchers
    val csvImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = stream.bufferedReader().use { it.readText() }
                    currentTable.importCsv(content)
                    tableSnapshot = currentTable.createSnapshot()
                    columnValueFilters.clear()
                    selectedCells.clear(); selectedCells.add(Pair(0, 0))
                    statusMessage = "Imported CSV successfully!"
                }
            } catch (e: Exception) {
                statusMessage = "Import error: ${e.message}"
            }
        }
    }

    val fileSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(activeExportFormat.mime)
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    when (activeExportFormat) {
                        ExportFormat.PDF -> TableExporter.exportToPdf(currentTable, stream)
                        ExportFormat.EXCEL -> TableExporter.exportToCsv(currentTable, stream)
                        ExportFormat.WORD -> TableExporter.exportToWordHtmlDoc(currentTable, stream)
                    }
                }
                cacheSizeText = CacheManager.getFormattedCacheSize(context)
                statusMessage = "Exported as ${activeExportFormat.extension.uppercase()}!"
            } catch (e: Exception) {
                statusMessage = "Export failed: ${e.message}"
            }
        }
    }

    var pendingSaveTargetKb by remember { mutableStateOf<Int?>(null) }
    val pdfStudioSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        PdfCompressorExporter.exportPagesToPdf(pdfPages.toList(), pendingSaveTargetKb, stream) {
                            statusMessage = it
                        }
                    }
                    cacheSizeText = CacheManager.getFormattedCacheSize(context)
                    statusMessage = "Saved PDF to storage!"
                } catch (e: Exception) {
                    statusMessage = "Export error: ${e.message}"
                }
            }
        }
    }

    val pdfStudioCameraScanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null) {
            selectedBitmap = bmp
            cropperTargetPageIndex = pdfPages.size
            pdfPages.add(PdfPageItem(bitmap = bmp))
            showCropperDialog = true
            statusMessage = "Scanned page! Adjust borders now."
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                selectedBitmap = BitmapFactory.decodeStream(stream)
                cropperTargetPageIndex = null
                showCropperDialog = true
            }
        }
    }

    val multiImagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { u ->
                context.contentResolver.openInputStream(u)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { bmp ->
                        pdfPages.add(PdfPageItem(bitmap = bmp))
                    }
                }
            }
            statusMessage = "Loaded ${uris.size} page(s) into PDF Studio!"
        }
    }

    // Cropper Dialog
    if (showCropperDialog && selectedBitmap != null) {
        FullScreenCropperDialog(
            sourceBitmap = selectedBitmap!!,
            onDismiss = {
                showCropperDialog = false
                cropperTargetPageIndex = null
            },
            onCropConfirmed = { cropped, mode ->
                showCropperDialog = false
                val pageTarget = cropperTargetPageIndex
                if (pageTarget != null && pageTarget in pdfPages.indices) {
                    pdfPages[pageTarget] = pdfPages[pageTarget].copy(bitmap = cropped)
                    cropperTargetPageIndex = null
                    statusMessage = "Updated borders for Page ${pageTarget + 1}!"
                } else {
                    coroutineScope.launch {
                        isProcessing = true
                        try {
                            val service = AndroidOcrService(context) { msg -> statusMessage = msg }
                            if (mode == CropExtractionMode.SINGLE_CELL_STEP) {
                                val (h, r) = service.extractTable(cropped)
                                val text = (h + r.flatten()).filter { it.isNotBlank() }.joinToString(" ")
                                val (tr, tc) = anchorCell
                                withContext(Dispatchers.Main) {
                                    currentTable.setCellValue(tr, tc, text)
                                    statusMessage = "Inserted '$text' into active cell ($tr, $tc)"
                                }
                            } else {
                                val (h, r) = service.extractTable(cropped)
                                withContext(Dispatchers.Main) {
                                    detectedWords.clear()
                                    h.forEach { detectedWords.add(it) }
                                    r.flatten().filter { it.isNotBlank() }.forEach { detectedWords.add(it) }

                                    previewCroppedBitmap = cropped
                                    pendingExtractedHeaders = h.map { ColumnDef(it, ColumnType.TEXT) }
                                    pendingExtractedRows = r
                                    showExtractionPreviewDialog = true
                                }
                            }
                        } catch (e: Exception) {
                            statusMessage = "OCR error: ${e.message}"
                        } finally {
                            isProcessing = false
                        }
                    }
                }
            }
        )
    }

    // Extraction Preview Verification Dialog
    if (showExtractionPreviewDialog && previewCroppedBitmap != null) {
        ExtractionPreviewDialog(
            croppedBitmap = previewCroppedBitmap!!,
            initialHeaders = pendingExtractedHeaders,
            initialRows = pendingExtractedRows,
            onDismiss = { showExtractionPreviewDialog = false },
            onConfirmAppend = { verifiedHeaders, verifiedRows, excludeHeaders, _ ->
                if (excludeHeaders) {
                    val paddedRows = verifiedRows.map { r ->
                        r + List((currentTable.headers.size - r.size).coerceAtLeast(0)) { "" }
                    }
                    val startR = currentTable.rows.size
                    for ((idx, rData) in paddedRows.withIndex()) {
                        currentTable.addRow("Row ${startR + idx + 1}")
                        val targetRowIdx = currentTable.rows.size - 1
                        rData.take(currentTable.headers.size).forEachIndexed { cIdx, v ->
                            currentTable.setCellValue(targetRowIdx, cIdx, v)
                        }
                    }
                } else {
                    currentTable.appendExtractedData(verifiedHeaders.map { it.name }, verifiedRows)
                }
                tableSnapshot = currentTable.createSnapshot()
                showExtractionPreviewDialog = false
                statusMessage = "Appended ${verifiedRows.size} verified rows to table!"
            },
            onConfirmReplace = { verifiedHeaders, verifiedRows, excludeHeaders, _ ->
                if (excludeHeaders) {
                    currentTable.rows.clear()
                    currentTable.rowNames.clear()
                    for ((idx, rData) in verifiedRows.withIndex()) {
                        currentTable.addRow("Row ${idx + 1}")
                        val targetRowIdx = currentTable.rows.size - 1
                        rData.take(currentTable.headers.size).forEachIndexed { cIdx, v ->
                            currentTable.setCellValue(targetRowIdx, cIdx, v)
                        }
                    }
                } else {
                    currentTable.loadExtractedData(verifiedHeaders.map { it.name }, verifiedRows)
                }
                tableSnapshot = currentTable.createSnapshot()
                columnValueFilters.clear()
                showExtractionPreviewDialog = false
                statusMessage = "Replaced table with ${verifiedRows.size} verified rows!"
            }
        )
    }

    // Dedicated Table Editor UI
    if (showDedicatedEditor) {
        DedicatedTableEditorDialog(
            tableData = currentTable,
            onDismiss = { showDedicatedEditor = false },
            onSave = {
                tableSnapshot = currentTable.createSnapshot()
                TableRepository.saveOrUpdate(currentTable)
                showDedicatedEditor = false
                statusMessage = "Dedicated table edits saved!"
            }
        )
    }

    // New Table Dialog Bound to Calendar Date & Time
    if (showNewTableDialog) {
        NewTableDialog(
            onDismiss = { showNewTableDialog = false },
            onTableCreated = { newTable ->
                TableRepository.saveOrUpdate(newTable)
                currentTable = newTable
                tableSnapshot = newTable.createSnapshot()
                columnValueFilters.clear()
                selectedCells.clear(); selectedCells.add(Pair(0, 0))
                showNewTableDialog = false
                statusMessage = "Created table '${newTable.tableName}'!"
            }
        )
    }

    // All Words Inspector Full Modal
    if (showAllWordsDialog) {
        AllWordsSelectorDialog(
            detectedWords = detectedWords,
            onDismiss = { showAllWordsDialog = false },
            onTransferSelected = { words, mode ->
                when (mode) {
                    TokenPlacementMode.SEQUENCE_FROM_ACTIVE -> {
                        var (tr, tc) = anchorCell
                        for (w in words) {
                            currentTable.setCellValue(tr, tc, w)
                            if (tc < currentTable.headers.size - 1) tc++ else {
                                if (tr < currentTable.rows.size - 1) { tr++; tc = 0 } else {
                                    currentTable.addRow(); tr++; tc = 0
                                }
                            }
                        }
                    }
                    TokenPlacementMode.FILL_MULTI_SELECTION -> {
                        selectedCells.forEachIndexed { idx, (r, c) ->
                            val text = words.getOrElse(idx % words.size) { "" }
                            currentTable.setCellValue(r, c, text)
                        }
                    }
                    TokenPlacementMode.APPEND_NEW_ROW -> {
                        val rName = "Row ${currentTable.rows.size + 1}"
                        currentTable.addRow(rName)
                        val lastR = currentTable.rows.size - 1
                        words.forEachIndexed { cIdx, w ->
                            while (cIdx >= currentTable.headers.size) currentTable.addColumn("Col ${currentTable.headers.size + 1}")
                            currentTable.setCellValue(lastR, cIdx, w)
                        }
                    }
                    TokenPlacementMode.APPEND_NEW_COL -> {
                        val colName = words.firstOrNull() ?: "New Col"
                        currentTable.addColumn(colName)
                        val lastC = currentTable.headers.size - 1
                        words.drop(1).forEachIndexed { rIdx, w ->
                            while (rIdx >= currentTable.rows.size) currentTable.addRow()
                            currentTable.setCellValue(rIdx, lastC, w)
                        }
                    }
                }
                showAllWordsDialog = false
                statusMessage = "Transferred ${words.size} word tokens!"
            }
        )
    }

    // Advanced Row Editor Dialog with Next / Prev Navigation
    if (showRowEditorDialog && currentTable.rows.isNotEmpty()) {
        val safeIndex = editingRowIndex.coerceIn(0, currentTable.rows.size - 1)
        AdvancedRowEditorDialog(
            initialTableName = currentTable.tableName,
            initialTableDateTime = currentTable.tableDateTime,
            currentRowIndex = safeIndex,
            totalRows = currentTable.rows.size,
            rowName = currentTable.rowNames.getOrElse(safeIndex) { "Row ${safeIndex + 1}" },
            headers = currentTable.headers,
            rowValues = currentTable.rows.getOrElse(safeIndex) { emptyList() },
            onDismiss = { showRowEditorDialog = false },
            onSaveRowAndTable = { newName, newDateTime, updatedRowName, updatedHeaders, updatedValues ->
                currentTable.tableName = newName
                currentTable.tableDateTime = newDateTime
                currentTable.rowNames[safeIndex] = updatedRowName
                updatedHeaders.forEachIndexed { idx, cDef ->
                    if (idx in currentTable.headers.indices) {
                        currentTable.headers[idx] = cDef
                    }
                }
                currentTable.rows[safeIndex].clear()
                currentTable.rows[safeIndex].addAll(updatedValues)
                currentTable.markUpdated()
                TableRepository.saveOrUpdate(currentTable)
                tableSnapshot = currentTable.createSnapshot()
                statusMessage = "Row ${safeIndex + 1} updated!"
            },
            onNavigateRow = { target -> editingRowIndex = target },
            onAddNewColumn = { name, type -> currentTable.addColumn(name, type) },
            onDeleteColumn = { colIdx -> currentTable.deleteColumn(colIdx); columnValueFilters.remove(colIdx) },
            onAddNewRowBelow = { currentTable.addRow("Row ${currentTable.rows.size + 1}", index = safeIndex + 1) },
            onAddNewRowAbove = { currentTable.addRow("Row ${currentTable.rows.size + 1}", index = safeIndex) },
            onMoveRowUp = { currentTable.moveRow(safeIndex, safeIndex - 1); editingRowIndex = safeIndex - 1 },
            onMoveRowDown = { currentTable.moveRow(safeIndex, safeIndex + 1); editingRowIndex = safeIndex + 1 },
            onDeleteRow = {
                currentTable.deleteRow(safeIndex)
                showRowEditorDialog = false
            }
        )
    }

    // Post-Generation PDF Inspector Dialog
    if (showGeneratedPdfInspector) {
        GeneratedPdfInspectorDialog(
            pages = pdfPages,
            initialSizeBytes = generatedPdfSizeBytes,
            onDismiss = { showGeneratedPdfInspector = false },
            onSavePdf = { targetKb ->
                pendingSaveTargetKb = targetKb
                showGeneratedPdfInspector = false
                pdfStudioSaveLauncher.launch("Compiled_Document.pdf")
            },
            onPrintPdf = { targetKb ->
                printPdfStudioDocument(targetKb)
            },
            onSharePdf = { targetKb ->
                sharePdfStudioDocument(targetKb)
            }
        )
    }

    // Clear & Delete Confirmations
    if (showClearTableConfirm) {
        AlertDialog(
            onDismissRequest = { showClearTableConfirm = false },
            title = { Text("Clear All Cell Values?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to empty every cell in this table? Headers and rows will remain intact.") },
            confirmButton = {
                Button(
                    onClick = {
                        currentTable.clearAllValues()
                        showClearTableConfirm = false
                        statusMessage = "Cleared cells."
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                ) { Text("Clear All", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { showClearTableConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteTableConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteTableConfirm = false },
            title = { Text("Delete Entire Table?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete '${currentTable.tableName}'? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        TableRepository.deleteTable(currentTable.id)
                        val next = TableRepository.tables.firstOrNull() ?: TableData(
                            initialName = "New Table",
                            initialHeaders = listOf(ColumnDef("Col 1"), ColumnDef("Col 2")),
                            initialRows = listOf(listOf("", ""), listOf("", ""))
                        ).also { TableRepository.saveOrUpdate(it) }
                        currentTable = next
                        tableSnapshot = next.createSnapshot()
                        columnValueFilters.clear()
                        selectedCells.clear(); selectedCells.add(Pair(0, 0))
                        showDeleteTableConfirm = false
                        statusMessage = "Table deleted."
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                ) { Text("Delete", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { showDeleteTableConfirm = false }) { Text("Cancel") } }
        )
    }

    val visibleColIndices = currentTable.headers.indices.filter { !hiddenColumns.contains(it) }

    // Evaluates both Global Search and Excel-style Column Value Filters
    val filteredRowIndices = currentTable.rows.indices.filter { rIdx ->
        val nameMatch = currentTable.rowNames.getOrElse(rIdx) { "" }.contains(globalSearchQuery, ignoreCase = true)
        val cellMatch = currentTable.rows[rIdx].any { it.contains(globalSearchQuery, ignoreCase = true) }
        val matchesGlobal = globalSearchQuery.isBlank() || nameMatch || cellMatch

        val matchesColFilters = columnValueFilters.all { (colIdx, selectedSet) ->
            val cellVal = currentTable.rows[rIdx].getOrElse(colIdx) { "" }
            selectedSet.contains(cellVal)
        }
        matchesGlobal && matchesColFilters
    }

    // =========================================================================
    // COLUMN VALUE FILTER DIALOG (Interactive list with Search & Counts)
    // =========================================================================
    if (activeFilterColIdx != null) {
        val targetCol = activeFilterColIdx!!
        val colName = currentTable.headers.getOrNull(targetCol)?.name ?: "Column ${targetCol + 1}"

        val distinctValuesWithCount = remember(currentTable.rows, targetCol) {
            currentTable.rows
                .map { it.getOrElse(targetCol) { "" } }
                .groupingBy { it }
                .eachCount()
                .toList()
                .sortedWith(compareBy({ it.first.isEmpty() }, { it.first.lowercase() }))
        }

        var filterSearchQuery by remember { mutableStateOf("") }
        val allDistinctValues = remember(distinctValuesWithCount) { distinctValuesWithCount.map { it.first } }

        val activeSelectedInDialog = remember {
            mutableStateListOf<String>().apply {
                val existing = columnValueFilters[targetCol]
                if (existing != null) {
                    addAll(existing)
                } else {
                    addAll(allDistinctValues)
                }
            }
        }

        val filteredItemsInDialog = remember(filterSearchQuery, distinctValuesWithCount) {
            if (filterSearchQuery.isBlank()) {
                distinctValuesWithCount
            } else {
                distinctValuesWithCount.filter { (v, _) ->
                    val display = if (v.isEmpty()) "(Blanks)" else v
                    display.contains(filterSearchQuery, ignoreCase = true)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { activeFilterColIdx = null },
            title = {
                Column {
                    Text("Filter Values: $colName", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF0D47A1))
                    Text("${activeSelectedInDialog.size} of ${allDistinctValues.size} values selected", fontSize = 11.sp, color = Color.Gray)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    OutlinedTextField(
                        value = filterSearchQuery,
                        onValueChange = { filterSearchQuery = it },
                        placeholder = { Text("Search values...", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                filteredItemsInDialog.forEach { (v, _) ->
                                    if (!activeSelectedInDialog.contains(v)) activeSelectedInDialog.add(v)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(2.dp)
                        ) {
                            Text("Select All", fontSize = 10.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                filteredItemsInDialog.forEach { (v, _) ->
                                    activeSelectedInDialog.remove(v)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(2.dp)
                        ) {
                            Text("Clear All", fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Divider()

                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(filteredItemsInDialog) { _, (valStr, count) ->
                            val isSelected = activeSelectedInDialog.contains(valStr)
                            val displayLabel = if (valStr.isBlank()) "(Blanks)" else valStr

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelected) activeSelectedInDialog.remove(valStr)
                                        else activeSelectedInDialog.add(valStr)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { chk ->
                                        if (chk) activeSelectedInDialog.add(valStr)
                                        else activeSelectedInDialog.remove(valStr)
                                    },
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = displayLabel,
                                    fontSize = 13.sp,
                                    color = if (valStr.isBlank()) Color.Gray else Color.Black,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "($count)",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        columnValueFilters.remove(targetCol)
                        statusMessage = "Cleared filter for '$colName'"
                        activeFilterColIdx = null
                    }) {
                        Text("Reset", color = Color.Red, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            if (activeSelectedInDialog.size == allDistinctValues.size) {
                                columnValueFilters.remove(targetCol)
                            } else {
                                columnValueFilters[targetCol] = activeSelectedInDialog.toSet()
                            }
                            statusMessage = "Applied filter on '$colName'"
                            activeFilterColIdx = null
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E88E5))
                    ) {
                        Text("Apply", color = Color.White, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { activeFilterColIdx = null }) {
                    Text("Cancel", fontSize = 12.sp)
                }
            }
        )
    }

    val actionColWidth = 190.dp
    val dataColWidth = 180.dp
    val totalTableWidth = actionColWidth + (dataColWidth * visibleColIndices.size) + 90.dp

    Scaffold(
        topBar = {
            if (!isFullScreen) {
                TopAppBar(
                    title = {
                        Column {
                            BasicTextField(
                                value = currentTable.tableName,
                                onValueChange = { currentTable.tableName = it },
                                textStyle = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            )
                            Text("📅 ${currentTable.tableDateTime}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
                        }
                    },
                    backgroundColor = Color(0xFF1E88E5),
                    actions = {
                        IconButton(onClick = { showDedicatedEditor = true }) {
                            Text("🛠 Edit", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { showTableHistoryDrawer = true }) {
                            Text("📂 Tables", color = Color.White, fontSize = 11.sp)
                        }
                        IconButton(onClick = { isFullScreen = true }) {
                            Text("⛶ Full", color = Color.White, fontSize = 11.sp)
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!isFullScreen) {
                BottomNavigation(backgroundColor = Color(0xFF1E88E5)) {
                    BottomNavigationItem(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        icon = { Text("🏠", fontSize = 18.sp) },
                        label = { Text("Table & Scan", fontSize = 10.sp) }
                    )
                    BottomNavigationItem(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        icon = { Text("📄", fontSize = 18.sp) },
                        label = { Text("PDF Studio", fontSize = 10.sp) }
                    )
                    BottomNavigationItem(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        icon = { Text("📂", fontSize = 18.sp) },
                        label = { Text("History", fontSize = 10.sp) }
                    )
                    BottomNavigationItem(
                        selected = selectedTabIndex == 3,
                        onClick = {
                            cacheSizeText = CacheManager.getFormattedCacheSize(context)
                            selectedTabIndex = 3
                        },
                        icon = { Text("⚙️", fontSize = 18.sp) },
                        label = { Text("Settings", fontSize = 10.sp) }
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
        ) {
            // TAB 0: MERGED TABLE AND SCAN & CONVERT WORKSPACE
            if (selectedTabIndex == 0 || isFullScreen) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .background(Color(0xFFF1F5F9))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { showNewTableDialog = true }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("+ New Table", color = Color.White, fontSize = 11.sp) }
                        Button(onClick = {
                            val subTable = currentTable.createSubTable("${currentTable.tableName} (Filtered)", filteredRowIndices, visibleColIndices)
                            TableRepository.saveOrUpdate(subTable)
                            currentTable = subTable
                            tableSnapshot = subTable.createSnapshot()
                            columnValueFilters.clear()
                            selectedCells.clear(); selectedCells.add(Pair(0, 0))
                            statusMessage = "Created sub-table!"
                        }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00ACC1)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("📋 New from Filters", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = { showScanWorkspaceInTable = !showScanWorkspaceInTable },
                            colors = ButtonDefaults.buttonColors(backgroundColor = if (showScanWorkspaceInTable) Color(0xFFE91E63) else Color(0xFF3949AB)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text(if (showScanWorkspaceInTable) "✕ Hide Scanner" else "📷 Show Scanner & Words", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                        Button(onClick = { jumpToNextRow() }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Next Row ➔", color = Color.White, fontSize = 11.sp) }
                        Button(onClick = { currentTable.transposeTable() }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("⇄ Transpose", color = Color.White, fontSize = 11.sp) }

                        Button(onClick = { activeExportFormat = ExportFormat.PDF; fileSaveLauncher.launch("${currentTable.tableName}.pdf") }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("PDF", color = Color.White, fontSize = 11.sp) }
                        Button(onClick = { activeExportFormat = ExportFormat.EXCEL; fileSaveLauncher.launch("${currentTable.tableName}.csv") }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("CSV", color = Color.White, fontSize = 11.sp) }
                        Button(onClick = { activeExportFormat = ExportFormat.WORD; fileSaveLauncher.launch("${currentTable.tableName}.doc") }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Word", color = Color.White, fontSize = 11.sp) }
                        Button(onClick = { csvImportLauncher.launch("text/*") }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Import CSV", color = Color.White, fontSize = 11.sp) }

                        Button(onClick = { printTablePdf() }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0277BD)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("🖨 Print", color = Color.White, fontSize = 11.sp) }
                        Button(onClick = { shareTablePdf() }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00838F)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("↗ Share", color = Color.White, fontSize = 11.sp) }

                        Button(onClick = { currentTable.addRow() }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("+ Row", color = Color.White, fontSize = 11.sp) }
                        Button(onClick = { currentTable.addColumn("Col ${currentTable.headers.size + 1}") }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("+ Col", color = Color.White, fontSize = 11.sp) }
                    }

                    if (showScanWorkspaceInTable) {
                        Card(
                            modifier = Modifier.fillMaxWidth().height(160.dp).padding(4.dp),
                            shape = RoundedCornerShape(8.dp),
                            elevation = 3.dp
                        ) {
                            Row(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF263238), RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selectedBitmap != null) {
                                        Image(bitmap = selectedBitmap!!.asImageBitmap(), contentDescription = "Snippet", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                    } else {
                                        Text("No image loaded", color = Color.White, fontSize = 11.sp)
                                    }

                                    Row(modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Button(onClick = { imagePickerLauncher.launch("image/*") }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp)) { Text("📷 Pick", fontSize = 10.sp) }
                                        Button(onClick = { showCropperDialog = true }, enabled = selectedBitmap != null, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp)) { Text("✂ Crop", fontSize = 10.sp) }
                                    }
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                Column(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("Tokens (${detectedWords.size})", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF1565C0))
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            TextButton(onClick = { selectedTokens.clear(); selectedTokens.addAll(detectedWords) }, contentPadding = PaddingValues(1.dp)) { Text("All", fontSize = 9.sp) }
                                            TextButton(onClick = { selectedTokens.clear() }, contentPadding = PaddingValues(1.dp)) { Text("Clear", fontSize = 9.sp) }
                                        }
                                    }

                                    val (tr, tc) = anchorCell
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Button(
                                            onClick = {
                                                if (selectedTokens.isNotEmpty()) {
                                                    currentTable.setCellValue(tr, tc, selectedTokens.joinToString(" "))
                                                    jumpToNextRow()
                                                }
                                            },
                                            enabled = selectedTokens.isNotEmpty(),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(1.dp),
                                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                                        ) { Text("➔ Cell ($tr,$tc)", fontSize = 9.sp, color = Color.White) }

                                        Button(
                                            onClick = {
                                                var (cr, cc) = anchorCell
                                                for (w in selectedTokens) {
                                                    currentTable.setCellValue(cr, cc, w)
                                                    if (cc < currentTable.headers.size - 1) cc++ else {
                                                        if (cr < currentTable.rows.size - 1) { cr++; cc = 0 } else { currentTable.addRow(); cr++; cc = 0 }
                                                    }
                                                }
                                            },
                                            enabled = selectedTokens.isNotEmpty(),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(1.dp),
                                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                                        ) { Text("➔ Seq", fontSize = 9.sp, color = Color.White) }
                                    }

                                    LazyRow(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        itemsIndexed(detectedWords) { _, w ->
                                            val isSel = selectedTokens.contains(w)
                                            Box(
                                                modifier = Modifier
                                                    .background(if (isSel) Color(0xFF1976D2) else Color(0xFFE8EEF5), RoundedCornerShape(4.dp))
                                                    .clickable { if (isSel) selectedTokens.remove(w) else selectedTokens.add(w) }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) { Text(w, fontSize = 10.sp, color = if (isSel) Color.White else Color.Black) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // MULTI-SELECT & CLIPBOARD CONTROL BAR
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        backgroundColor = if (isMultiSelectMode) Color(0xFFF3E5F5) else Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(6.dp),
                        elevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔍", fontSize = 14.sp)
                            BasicTextField(value = globalSearchQuery, onValueChange = { globalSearchQuery = it }, modifier = Modifier.width(130.dp).padding(vertical = 4.dp), textStyle = TextStyle(fontSize = 12.sp, color = Color.Black))

                            if (columnValueFilters.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        columnValueFilters.clear()
                                        statusMessage = "Cleared all column filters"
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFFF6F00)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("✕ Reset Filters (${columnValueFilters.size})", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Button(
                                onClick = { isMultiSelectMode = !isMultiSelectMode },
                                colors = ButtonDefaults.buttonColors(backgroundColor = if (isMultiSelectMode) Color(0xFF7B1FA2) else Color(0xFF546E7A)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (isMultiSelectMode) "✓ Multi (${selectedCells.size})" else "☐ Multi-Select", color = Color.White, fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    if (selectedCells.isNotEmpty()) {
                                        cellClipboard = currentTable.copyCells(selectedCells, isCut = false)
                                        val tsv = currentTable.selectionToTsv(selectedCells)
                                        clipboardManager.setText(AnnotatedString(tsv))
                                        statusMessage = "Copied ${selectedCells.size} cell(s)!"
                                    }
                                },
                                enabled = selectedCells.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E88E5)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text("📋 Copy", color = Color.White, fontSize = 11.sp) }

                            Button(
                                onClick = {
                                    if (selectedCells.isNotEmpty()) {
                                        cellClipboard = currentTable.copyCells(selectedCells, isCut = true)
                                        val tsv = currentTable.selectionToTsv(selectedCells)
                                        clipboardManager.setText(AnnotatedString(tsv))
                                        tableSnapshot = currentTable.createSnapshot()
                                        statusMessage = "Cut ${selectedCells.size} cell(s)!"
                                    }
                                },
                                enabled = selectedCells.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text("✂ Cut", color = Color.White, fontSize = 11.sp) }

                            Button(
                                onClick = {
                                    cellClipboard?.let { clip ->
                                        val pasted = currentTable.pasteCells(selectedCells, anchorCell, clip)
                                        if (pasted.isNotEmpty()) {
                                            selectedCells.clear()
                                            selectedCells.addAll(pasted)
                                        }
                                        tableSnapshot = currentTable.createSnapshot()
                                        statusMessage = if (clip.items.size == 1 && selectedCells.size > 1) {
                                            "Replicated '${clip.items.first().value}' into ${selectedCells.size} cells!"
                                        } else {
                                            "Pasted ${pasted.size} cell(s)!"
                                        }
                                        if (clip.isCut) cellClipboard = null
                                    }
                                },
                                enabled = cellClipboard != null,
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text("📌 Paste", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                            Button(
                                onClick = {
                                    currentTable.clearCells(selectedCells)
                                    statusMessage = "Cleared ${selectedCells.size} cell(s)!"
                                },
                                enabled = selectedCells.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text("🗑 Clear Selection", color = Color.White, fontSize = 11.sp) }

                            OutlinedButton(onClick = { showClearTableConfirm = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Clear All", color = Color.Red, fontSize = 11.sp) }

                            Text("Shift:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.LEFT); selectedCells.clear(); selectedCells.addAll(u) }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("◀") }
                            Button(onClick = { val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.RIGHT); selectedCells.clear(); selectedCells.addAll(u) }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("▶") }
                            Button(onClick = { val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.UP); selectedCells.clear(); selectedCells.addAll(u) }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("▲") }
                            Button(onClick = { val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.DOWN); selectedCells.clear(); selectedCells.addAll(u) }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("▼") }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Column(modifier = Modifier.width(totalTableWidth).fillMaxHeight()) {
                            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFE3EDF7)).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.width(actionColWidth).border(1.dp, Color.LightGray).background(Color(0xFFECEFF1)).padding(6.dp)) {
                                    BasicTextField(value = currentTable.cornerHeader, onValueChange = { currentTable.cornerHeader = it }, textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0D47A1)))
                                }
                                visibleColIndices.forEach { colIdx ->
                                    val colDef = currentTable.headers[colIdx]
                                    val isColFiltered = columnValueFilters.containsKey(colIdx)

                                    Box(modifier = Modifier.width(dataColWidth).border(1.dp, Color.LightGray).background(Color(0xFFF5F9FD)).padding(6.dp)) {
                                        Column {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                BasicTextField(value = colDef.name, onValueChange = { currentTable.headers[colIdx] = colDef.copy(name = it); currentTable.markUpdated() }, textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp), modifier = Modifier.weight(1f))
                                                Text("✕", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = currentTable.headers.size > 1) { currentTable.deleteColumn(colIdx); columnValueFilters.remove(colIdx) })
                                            }
                                            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                var typeExpanded by remember { mutableStateOf(false) }
                                                Box {
                                                    Text("[${colDef.type.label.take(7)} ▼]", fontSize = 10.sp, color = Color(0xFF0D47A1), modifier = Modifier.background(Color(0xFFE1F5FE), RoundedCornerShape(3.dp)).clickable { typeExpanded = true }.padding(horizontal = 4.dp, vertical = 1.dp))
                                                    DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                                                        ColumnType.values().forEach { ct -> DropdownMenuItem(onClick = { currentTable.headers[colIdx] = colDef.copy(type = ct); currentTable.markUpdated(); typeExpanded = false }) { Text(ct.label) } }
                                                    }
                                                }

                                                // COLUMN VALUE FILTER BUTTON
                                                Box(
                                                    modifier = Modifier
                                                        .background(if (isColFiltered) Color(0xFFFF6F00) else Color(0xFFECEFF1), RoundedCornerShape(3.dp))
                                                        .clickable { activeFilterColIdx = colIdx }
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        text = if (isColFiltered) "⚲ Filtered" else "⚲ Filter",
                                                        fontSize = 10.sp,
                                                        fontWeight = if (isColFiltered) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isColFiltered) Color.White else Color(0xFF37474F)
                                                    )
                                                }

                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("◀", modifier = Modifier.clickable(enabled = colIdx > 0) { currentTable.moveColumn(colIdx, colIdx - 1) }, fontSize = 12.sp)
                                                    Text("▶", modifier = Modifier.clickable(enabled = colIdx < currentTable.headers.size - 1) { currentTable.moveColumn(colIdx, colIdx + 1) }, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                                Box(modifier = Modifier.width(90.dp).padding(4.dp), contentAlignment = Alignment.Center) {
                                    Button(onClick = { currentTable.addColumn("Col ${currentTable.headers.size + 1}") }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("+ Col", color = Color.White, fontSize = 11.sp) }
                                }
                            }

                            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                itemsIndexed(filteredRowIndices) { _, origRIdx ->
                                    val rowData = currentTable.rows[origRIdx]
                                    Row(modifier = Modifier.fillMaxWidth().border(0.5.dp, Color(0xFFE0E0E0)), verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.width(actionColWidth).border(0.5.dp, Color(0xFFCFD8DC)).background(Color(0xFFF9FAFB)).padding(6.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                BasicTextField(value = currentTable.rowNames.getOrElse(origRIdx) { "Row ${origRIdx + 1}" }, onValueChange = { currentTable.rowNames[origRIdx] = it; currentTable.markUpdated() }, textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0)), modifier = Modifier.weight(1f))
                                                Text("✎", fontSize = 13.sp, color = Color(0xFF00897B), fontWeight = FontWeight.Bold, modifier = Modifier.clickable { editingRowIndex = origRIdx; showRowEditorDialog = true }.padding(horizontal = 2.dp))
                                                Text("▲", modifier = Modifier.clickable(enabled = origRIdx > 0) { currentTable.moveRow(origRIdx, origRIdx - 1) }, fontSize = 11.sp)
                                                Text("▼", modifier = Modifier.clickable(enabled = origRIdx < currentTable.rows.size - 1) { currentTable.moveRow(origRIdx, origRIdx + 1) }, fontSize = 11.sp)
                                                Text("✕", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { currentTable.deleteRow(origRIdx) })
                                            }
                                        }

                                        visibleColIndices.forEach { colIdx ->
                                            val cellCoord = Pair(origRIdx, colIdx)
                                            val cellValue = rowData.getOrElse(colIdx) { "" }
                                            val isSelected = selectedCells.contains(cellCoord)
                                            val isAnchor = anchorCell == cellCoord

                                            val cellBg = when {
                                                isSelected && isMultiSelectMode -> Color(0xFFE1BEE7)
                                                isSelected -> Color(0xFFBBDEFB)
                                                else -> Color.White
                                            }
                                            val cellBorder = when {
                                                isAnchor -> Color(0xFF00C853)
                                                isSelected && isMultiSelectMode -> Color(0xFF7B1FA2)
                                                isSelected -> Color(0xFF1976D2)
                                                else -> Color.LightGray
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .width(dataColWidth)
                                                    .border(width = if (isSelected || isAnchor) 2.dp else 0.5.dp, color = cellBorder)
                                                    .background(cellBg)
                                                    .padding(8.dp)
                                            ) {
                                                BasicTextField(
                                                    value = cellValue,
                                                    onValueChange = { currentTable.setCellValue(origRIdx, colIdx, it) },
                                                    enabled = !isMultiSelectMode,
                                                    textStyle = TextStyle(fontSize = 13.sp, color = Color.Black),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .onFocusChanged {
                                                            if (it.isFocused && !isMultiSelectMode) {
                                                                anchorCell = cellCoord
                                                                selectedCells.clear()
                                                                selectedCells.add(cellCoord)
                                                            }
                                                        }
                                                )

                                                if (isMultiSelectMode) {
                                                    Box(
                                                        modifier = Modifier
                                                            .matchParentSize()
                                                            .clickable {
                                                                anchorCell = cellCoord
                                                                if (selectedCells.contains(cellCoord)) {
                                                                    selectedCells.remove(cellCoord)
                                                                } else {
                                                                    selectedCells.add(cellCoord)
                                                                }
                                                            }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 1: PDF STUDIO (SCAN CAMERA, UPLOAD, BORDERS, POST-GEN INSPECTION)
            if (selectedTabIndex == 1 && !isFullScreen) {
                Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), shape = RoundedCornerShape(8.dp), backgroundColor = Color(0xFFF1F5F9)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { pdfStudioCameraScanLauncher.launch(null) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) { Text("📷 Scan Page", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = { multiImagePickerLauncher.launch("image/*") },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) { Text("+ Upload Pages", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = { pdfPages.clear() },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) { Text("Clear", color = Color.White, fontSize = 11.sp) }
                            }

                            Text("${pdfPages.size} Page(s)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0D47A1))
                        }
                    }

                    if (pdfPages.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No PDF pages loaded.", color = Color.Gray, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { pdfStudioCameraScanLauncher.launch(null) }) { Text("📷 Scan with Camera") }
                                    Button(onClick = { multiImagePickerLauncher.launch("image/*") }) { Text("🖼 Upload from Gallery") }
                                }
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            itemsIndexed(pdfPages) { pageIdx, pageItem ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    elevation = 2.dp,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Image(
                                            bitmap = pageItem.bitmap.asImageBitmap(),
                                            contentDescription = "Page ${pageIdx + 1}",
                                            modifier = Modifier.size(64.dp).border(0.5.dp, Color.Gray, RoundedCornerShape(4.dp)),
                                            contentScale = ContentScale.Crop
                                        )

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Page ${pageIdx + 1} of ${pdfPages.size}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("${pageItem.bitmap.width} x ${pageItem.bitmap.height} px", fontSize = 11.sp, color = Color.Gray)

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Button(
                                                    onClick = {
                                                        selectedBitmap = pageItem.bitmap
                                                        cropperTargetPageIndex = pageIdx
                                                        showCropperDialog = true
                                                    },
                                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                                ) { Text("✂ Borders", color = Color.White, fontSize = 10.sp) }

                                                Button(
                                                    onClick = {
                                                        val matrix = Matrix().apply { postRotate(90f) }
                                                        val rot = Bitmap.createBitmap(pageItem.bitmap, 0, 0, pageItem.bitmap.width, pageItem.bitmap.height, matrix, true)
                                                        pdfPages[pageIdx] = pageItem.copy(bitmap = rot)
                                                    },
                                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF546E7A)),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                                ) { Text("🔄 90°", color = Color.White, fontSize = 10.sp) }
                                            }
                                        }

                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Button(
                                                onClick = {
                                                    val p = pdfPages.removeAt(pageIdx)
                                                    pdfPages.add(pageIdx - 1, p)
                                                },
                                                enabled = pageIdx > 0,
                                                modifier = Modifier.size(32.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) { Text("▲", fontSize = 12.sp) }

                                            Button(
                                                onClick = {
                                                    val p = pdfPages.removeAt(pageIdx)
                                                    pdfPages.add(pageIdx + 1, p)
                                                },
                                                enabled = pageIdx < pdfPages.size - 1,
                                                modifier = Modifier.size(32.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) { Text("▼", fontSize = 12.sp) }

                                            Text(
                                                "✕",
                                                color = Color.Red,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable { pdfPages.removeAt(pageIdx) }.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isProcessing = true
                                statusMessage = "Compiling document..."
                                try {
                                    val (_, sizeBytes) = PdfCompressorExporter.generateTempPdfFile(
                                        context.cacheDir,
                                        pdfPages.toList(),
                                        null
                                    )
                                    generatedPdfSizeBytes = sizeBytes
                                    showGeneratedPdfInspector = true
                                } catch (e: Exception) {
                                    statusMessage = "Compilation error: ${e.message}"
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        enabled = pdfPages.isNotEmpty() && !isProcessing,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                    ) {
                        Text(
                            if (isProcessing) "Generating..." else "⚡ Generate PDF & Inspect Pages (Zoom & Reduce Size)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // TAB 2: SAVED TABLES LIST & HISTORY
            if (selectedTabIndex == 2 && !isFullScreen) {
                Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Saved Tables (${TableRepository.tables.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Button(onClick = { showNewTableDialog = true }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))) { Text("+ New Table", color = Color.White) }
                    }
                    OutlinedTextField(value = drawerSearchQuery, onValueChange = { drawerSearchQuery = it }, placeholder = { Text("🔍 Search tables...") }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), singleLine = true)
                    val filtered = TableRepository.tables.filter { drawerSearchQuery.isBlank() || it.tableName.contains(drawerSearchQuery, ignoreCase = true) }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(filtered) { _, t ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), elevation = 2.dp) {
                                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f).clickable { currentTable = t; tableSnapshot = t.createSnapshot(); columnValueFilters.clear(); selectedTabIndex = 0; statusMessage = "Loaded ${t.tableName}" }) {
                                        Text(t.tableName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("📅 ${t.tableDateTime} • ${t.rows.size} rows", fontSize = 11.sp, color = Color.Gray)
                                    }
                                    IconButton(onClick = { TableRepository.deleteTable(t.id) }) { Text("🗑", fontSize = 16.sp) }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 3: SETTINGS & CACHE MANAGER
            if (selectedTabIndex == 3 && !isFullScreen) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Settings & App Maintenance", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Cache & Storage", fontWeight = FontWeight.Bold)
                            Text("Current App Cache: $cacheSizeText", fontSize = 13.sp, color = Color.DarkGray)
                            Button(onClick = { CacheManager.clearCache(context); cacheSizeText = CacheManager.getFormattedCacheSize(context); statusMessage = "Cache cleared!" }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)), modifier = Modifier.fillMaxWidth()) {
                                Text("🗑 Clear App Cache", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Table Danger Zone", fontWeight = FontWeight.Bold, color = Color.Red)
                            Button(onClick = { showClearTableConfirm = true }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315)), modifier = Modifier.fillMaxWidth()) { Text("Empty All Cells in Active Table", color = Color.White) }
                            Button(onClick = { showDeleteTableConfirm = true }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB71C1C)), modifier = Modifier.fillMaxWidth()) { Text("Delete Active Table Entirely", color = Color.White) }
                        }
                    }
                }
            }
        }
    }

    // SAVED TABLES DRAWER
    if (showTableHistoryDrawer) {
        AlertDialog(
            onDismissRequest = { showTableHistoryDrawer = false },
            title = {
                Column {
                    Text("Saved Tables History", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = drawerSearchQuery,
                        onValueChange = { drawerSearchQuery = it },
                        placeholder = { Text("🔍 Search tables by name or date...") },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                }
            },
            text = {
                val filteredTables = TableRepository.tables.filter {
                    drawerSearchQuery.isBlank() || it.tableName.contains(drawerSearchQuery, ignoreCase = true) || it.tableDateTime.contains(drawerSearchQuery, ignoreCase = true)
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                    itemsIndexed(filteredTables) { _, t ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), elevation = 2.dp) {
                            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f).clickable {
                                    currentTable = t
                                    tableSnapshot = t.createSnapshot()
                                    selectedCells.clear(); selectedCells.add(Pair(0, 0))
                                    showTableHistoryDrawer = false
                                    statusMessage = "Loaded: ${t.tableName}"
                                }) {
                                    Text(t.tableName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("📅 ${t.tableDateTime} • ${t.rows.size} rows", fontSize = 11.sp, color = Color.Gray)
                                }
                                IconButton(onClick = { TableRepository.deleteTable(t.id) }) { Text("🗑", fontSize = 16.sp) }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showTableHistoryDrawer = false }) { Text("Close") } }
        )
    }
}
