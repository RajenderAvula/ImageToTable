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
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.imagetotable.model.*
import com.example.imagetotable.ocr.AndroidOcrService
import com.example.imagetotable.ui.*
import com.example.imagetotable.util.CacheManager
import com.example.imagetotable.util.CalendarPickerUtil
import com.example.imagetotable.util.TableExporter
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

    // 5 Clean Tabs: 0: Home/Table, 1: Scanner, 2: Conversions, 3: Tables, 4: Settings
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedCells = remember { mutableStateListOf<Pair<Int, Int>>(Pair(0, 0)) }
    var anchorCell by remember { mutableStateOf(Pair(0, 0)) }
    var cellClipboard by remember { mutableStateOf<CellClipboard?>(null) }

    // Image, Pinch Zoom & Token States
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var splitZoomScale by remember { mutableFloatStateOf(1f) }
    var splitPanOffset by remember { mutableStateOf(Offset.Zero) }

    val detectedWords = remember { mutableStateListOf<String>() }
    val selectedTokens = remember { mutableStateListOf<String>() }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready") }

    // Section Visibility Toggles (Can be toggled inside Table or Scanner view)
    var showImageSection by remember { mutableStateOf(false) }
    var showTokensSection by remember { mutableStateOf(false) }
    var isFullScreen by remember { mutableStateOf(false) }

    // Dialog Visibilities
    var showCropperDialog by remember { mutableStateOf(false) }
    var showDedicatedEditor by remember { mutableStateOf(false) }
    var showNewTableDialog by remember { mutableStateOf(false) }
    var showAllWordsDialog by remember { mutableStateOf(false) }
    var showTableHistoryDrawer by remember { mutableStateOf(false) }
    var showDeleteTableConfirm by remember { mutableStateOf(false) }
    var showClearTableConfirm by remember { mutableStateOf(false) }
    var showRowEditorDialog by remember { mutableStateOf(false) }
    var editingRowIndex by remember { mutableIntStateOf(0) }

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
    var showColumnFilterDropdown by remember { mutableStateOf(false) }
    var showRowFilterDropdown by remember { mutableStateOf(false) }
    var selectedFilterColIndex by remember { mutableIntStateOf(0) }
    var activeColDropdownIdx by remember { mutableStateOf<Int?>(null) }
    var activeRowDropdownIdx by remember { mutableStateOf<Int?>(null) }
    var drawerSearchQuery by remember { mutableStateOf("") }

    val columnValueFilters = remember { mutableStateMapOf<Int, MutableSet<String>>() }

    // Cache Size State
    var cacheSizeText by remember { mutableStateOf(CacheManager.getFormattedCacheSize(context)) }

    fun rotatePreviewImage() {
        if (selectedBitmap != null) {
            val matrix = Matrix().apply { postRotate(90f) }
            selectedBitmap = Bitmap.createBitmap(selectedBitmap!!, 0, 0, selectedBitmap!!.width, selectedBitmap!!.height, matrix, true)
            splitZoomScale = 1f
            splitPanOffset = Offset.Zero
        }
    }

    // Fast Next Row Navigation
    fun jumpToNextRow() {
        val (cr, cc) = anchorCell
        if (cr >= 0 && cr < currentTable.rows.size - 1) {
            anchorCell = Pair(cr + 1, cc)
            selectedCells.clear()
            selectedCells.add(Pair(cr + 1, cc))
        } else {
            currentTable.addRow("Row ${currentTable.rows.size + 1}")
            anchorCell = Pair(currentTable.rows.size - 1, cc)
            selectedCells.clear()
            selectedCells.add(anchorCell)
            statusMessage = "Added & jumped to new row!"
        }
    }

    // Printing Handler
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

    // Sharing Handler
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

    // Launchers
    val csvImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = stream.bufferedReader().use { it.readText() }
                    currentTable.importCsv(content)
                    tableSnapshot = currentTable.createSnapshot()
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

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                selectedBitmap = BitmapFactory.decodeStream(stream)
                showCropperDialog = true
            }
        }
    }

    // 1. Cropper Dialog (Zoom & pan with arrow controls)
    if (showCropperDialog && selectedBitmap != null) {
        FullScreenCropperDialog(
            sourceBitmap = selectedBitmap!!,
            onDismiss = { showCropperDialog = false },
            onCropConfirmed = { cropped, mode ->
                showCropperDialog = false
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
        )
    }

    // 2. Extraction Preview Verification Dialog (Inspect zoomable crop & edit extracted data)
    if (showExtractionPreviewDialog && previewCroppedBitmap != null) {
        ExtractionPreviewDialog(
            croppedBitmap = previewCroppedBitmap!!,
            initialHeaders = pendingExtractedHeaders,
            initialRows = pendingExtractedRows,
            onDismiss = { showExtractionPreviewDialog = false },
            onConfirmAppend = { verifiedHeaders, verifiedRows, excludeHeaders, excludeRowNames ->
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
            onConfirmReplace = { verifiedHeaders, verifiedRows, excludeHeaders, excludeRowNames ->
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
                showExtractionPreviewDialog = false
                statusMessage = "Replaced table with ${verifiedRows.size} verified rows!"
            }
        )
    }

    // 3. Dedicated Table Editor UI
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

    // 4. New Table Dialog Bound to Calendar Date & Time
    if (showNewTableDialog) {
        NewTableDialog(
            onDismiss = { showNewTableDialog = false },
            onTableCreated = { newTable ->
                TableRepository.saveOrUpdate(newTable)
                currentTable = newTable
                tableSnapshot = newTable.createSnapshot()
                selectedCells.clear(); selectedCells.add(Pair(0, 0))
                showNewTableDialog = false
                statusMessage = "Created table '${newTable.tableName}'!"
            }
        )
    }

    // 5. All Words Inspector Full Modal
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

    // 6. Advanced Row Editor Dialog (Restored & Directly Accessible via ✎ button)
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
                updatedHeaders.forEachIndexed { idx, colName ->
                    if (idx in currentTable.headers.indices) {
                        currentTable.headers[idx] = currentTable.headers[idx].copy(name = colName)
                    }
                }
                currentTable.rows[safeIndex].clear()
                currentTable.rows[safeIndex].addAll(updatedValues)
                currentTable.markUpdated()
                TableRepository.saveOrUpdate(currentTable)
                tableSnapshot = currentTable.createSnapshot()
                showRowEditorDialog = false
                statusMessage = "Row ${safeIndex + 1} updated!"
            },
            onNavigateRow = { target -> editingRowIndex = target },
            onAddNewColumn = { name, type -> currentTable.addColumn(name, type) },
            onDeleteColumn = { colIdx -> currentTable.deleteColumn(colIdx) },
            onAddNewRowBelow = { currentTable.addRow("Row ${currentTable.rows.size + 1}") },
            onDeleteRow = {
                currentTable.deleteRow(safeIndex)
                showRowEditorDialog = false
            }
        )
    }

    // 7. Clear All Table Values Confirmation
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
                        statusMessage = "Cleared all table cells."
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                ) { Text("Clear All", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { showClearTableConfirm = false }) { Text("Cancel") } }
        )
    }

    // 8. Delete Entire Table Confirmation Dialog
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

    // Filter computation
    val visibleColIndices = currentTable.headers.indices.filter { !hiddenColumns.contains(it) }
    val filteredRowIndices = currentTable.rows.indices.filter { rIdx ->
        val nameMatch = currentTable.rowNames.getOrElse(rIdx) { "" }.contains(globalSearchQuery, ignoreCase = true)
        val cellMatch = currentTable.rows[rIdx].any { it.contains(globalSearchQuery, ignoreCase = true) }
        val matchesGlobal = globalSearchQuery.isBlank() || nameMatch || cellMatch

        val matchesColFilters = columnValueFilters.all { (colIdx, selectedValues) ->
            if (selectedValues.isEmpty()) true
            else {
                val rowVal = currentTable.rows[rIdx].getOrElse(colIdx) { "" }
                selectedValues.contains(rowVal)
            }
        }
        matchesGlobal && matchesColFilters
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
                        label = { Text("Table", fontSize = 10.sp) }
                    )
                    BottomNavigationItem(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        icon = { Text("📷", fontSize = 18.sp) },
                        label = { Text("Scanner", fontSize = 10.sp) }
                    )
                    BottomNavigationItem(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        icon = { Text("🔄", fontSize = 18.sp) },
                        label = { Text("Conversions", fontSize = 10.sp) }
                    )
                    BottomNavigationItem(
                        selected = selectedTabIndex == 3,
                        onClick = { selectedTabIndex = 3 },
                        icon = { Text("📂", fontSize = 18.sp) },
                        label = { Text("History", fontSize = 10.sp) }
                    )
                    BottomNavigationItem(
                        selected = selectedTabIndex == 4,
                        onClick = {
                            cacheSizeText = CacheManager.getFormattedCacheSize(context)
                            selectedTabIndex = 4
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
            // ==========================================
            // TAB 0: HOME / ACTIVE INTERACTIVE TABLE UI
            // ==========================================
            if (selectedTabIndex == 0 || isFullScreen) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Full Screen Exit Bar
                    if (isFullScreen) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                            backgroundColor = Color(0xFFE8EEF5),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = currentTable.tableName,
                                    onValueChange = { currentTable.tableName = it; currentTable.markUpdated() },
                                    label = { Text("Table Name") },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.width(150.dp).height(46.dp)
                                )
                                Button(onClick = { currentTable.addRow() }) { Text("+ Row") }
                                Button(onClick = { currentTable.addColumn("Col ${currentTable.headers.size + 1}") }) { Text("+ Col") }
                                Button(onClick = { jumpToNextRow() }) { Text("Next Row ➔") }
                                Button(onClick = { currentTable.transposeTable() }) { Text("⇄ Transpose") }
                                Button(onClick = { isFullScreen = false }, colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)) {
                                    Text("Exit Full Screen ✕", color = Color.White)
                                }
                            }
                        }
                    }

                    // Complete Feature Action Bar (All tools from previous files preserved)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .background(Color(0xFFF1F5F9))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { showNewTableDialog = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("+ New Table", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = {
                                val subTable = currentTable.createSubTable(
                                    newTableName = "${currentTable.tableName} (Filtered)",
                                    selectedRowIndices = filteredRowIndices,
                                    selectedColIndices = visibleColIndices
                                )
                                TableRepository.saveOrUpdate(subTable)
                                currentTable = subTable
                                tableSnapshot = subTable.createSnapshot()
                                selectedCells.clear(); selectedCells.add(Pair(0, 0))
                                statusMessage = "Created new table from filtered view!"
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00ACC1)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("📋 New from Filters", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = { showDedicatedEditor = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00838F)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("🛠 Edit Table UI", color = Color.White, fontSize = 11.sp) }

                        // COLUMNS FILTER DROPDOWN
                        Box {
                            Button(
                                onClick = { showColumnFilterDropdown = true },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Columns Filter (${visibleColIndices.size}) ▼", color = Color.White, fontSize = 11.sp)
                            }
                            DropdownMenu(
                                expanded = showColumnFilterDropdown,
                                onDismissRequest = { showColumnFilterDropdown = false }
                            ) {
                                DropdownMenuItem(onClick = { hiddenColumns.clear() }) {
                                    Text("✓ Select All Columns", fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                                }
                                DropdownMenuItem(onClick = {
                                    hiddenColumns.clear()
                                    hiddenColumns.addAll(currentTable.headers.indices.drop(1))
                                }) {
                                    Text("✕ Hide Others (Keep 1st)", color = Color.Gray)
                                }
                                Divider()
                                currentTable.headers.forEachIndexed { idx, h ->
                                    val isHidden = hiddenColumns.contains(idx)
                                    DropdownMenuItem(onClick = {
                                        if (isHidden) hiddenColumns.remove(idx) else hiddenColumns.add(idx)
                                    }) {
                                        Checkbox(checked = !isHidden, onCheckedChange = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(h.name, fontSize = 13.sp)
                                    }
                                }
                            }
                        }

                        // ROW VALUES FILTER DROPDOWN
                        Box {
                            Button(
                                onClick = { showRowFilterDropdown = true },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00695C)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Row Values Filter ▼", color = Color.White, fontSize = 11.sp)
                            }
                            DropdownMenu(
                                expanded = showRowFilterDropdown,
                                onDismissRequest = { showRowFilterDropdown = false }
                            ) {
                                Text(
                                    text = "Filter By Column Values:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                                currentTable.headers.forEachIndexed { cIdx, col ->
                                    DropdownMenuItem(onClick = { selectedFilterColIndex = cIdx }) {
                                        Text(
                                            text = (if (selectedFilterColIndex == cIdx) "● " else "○ ") + col.name,
                                            fontWeight = if (selectedFilterColIndex == cIdx) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Divider()
                                DropdownMenuItem(onClick = {
                                    globalSearchQuery = ""
                                    showRowFilterDropdown = false
                                }) { Text("✕ Clear Value Filter", color = Color.Red, fontSize = 12.sp) }

                                val distinctValues = currentTable.rows.mapNotNull {
                                    it.getOrNull(selectedFilterColIndex)?.takeIf { s -> s.isNotBlank() }
                                }.distinct()

                                distinctValues.take(15).forEach { valItem ->
                                    DropdownMenuItem(onClick = {
                                        globalSearchQuery = valItem
                                        showRowFilterDropdown = false
                                    }) { Text("Match: \"$valItem\"", fontSize = 12.sp) }
                                }
                            }
                        }

                        Button(
                            onClick = { jumpToNextRow() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("Next Row ➔", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = { showAllWordsDialog = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("Tokens (${detectedWords.size})", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = { currentTable.transposeTable() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("⇄ Transpose", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = {
                                TableRepository.saveOrUpdate(currentTable)
                                tableSnapshot = currentTable.createSnapshot()
                                statusMessage = "Saved!"
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("💾 Save", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = {
                                currentTable.revertToSnapshot(tableSnapshot)
                                selectedCells.clear(); selectedCells.add(Pair(0, 0))
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("↩ Cancel", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = { currentTable.addRow() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("+ Row", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = {
                                val nextColNum = currentTable.headers.size + 1
                                currentTable.addColumn("Col $nextColNum")
                                statusMessage = "Added Column $nextColNum"
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("+ Col", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = {
                                CalendarPickerUtil.pickDateTime(context) { newDateTime ->
                                    currentTable.tableDateTime = newDateTime
                                    currentTable.markUpdated()
                                    TableRepository.saveOrUpdate(currentTable)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("📅 Date/Time", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("📷 Crop OCR", color = Color.White, fontSize = 11.sp) }
                    }

                    // Search & Multi-Cell Toolbar
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        backgroundColor = if (isMultiSelectMode) Color(0xFFF3E5F5) else Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(6.dp),
                        elevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔍", fontSize = 14.sp)
                            BasicTextField(
                                value = globalSearchQuery,
                                onValueChange = { globalSearchQuery = it },
                                modifier = Modifier.width(140.dp).padding(vertical = 4.dp),
                                textStyle = TextStyle(fontSize = 12.sp, color = Color.Black),
                                decorationBox = { inner ->
                                    if (globalSearchQuery.isEmpty()) Text("Search table...", color = Color.Gray, fontSize = 11.sp)
                                    inner()
                                }
                            )
                            if (globalSearchQuery.isNotEmpty() || columnValueFilters.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        globalSearchQuery = ""
                                        columnValueFilters.clear()
                                    },
                                    modifier = Modifier.size(20.dp)
                                ) { Text("✕", fontSize = 12.sp, color = Color.Gray) }
                            }

                            Button(
                                onClick = { isMultiSelectMode = !isMultiSelectMode },
                                colors = ButtonDefaults.buttonColors(backgroundColor = if (isMultiSelectMode) Color(0xFF7B1FA2) else Color(0xFF546E7A)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text(if (isMultiSelectMode) "✓ Multi (${selectedCells.size})" else "☐ Multi-Select", color = Color.White, fontSize = 11.sp) }

                            Button(
                                onClick = {
                                    cellClipboard = currentTable.copyCells(selectedCells, isCut = false)
                                    statusMessage = "Copied ${selectedCells.size} cell(s)!"
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E88E5)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text("📋 Copy", color = Color.White, fontSize = 11.sp) }

                            Button(
                                onClick = {
                                    cellClipboard = currentTable.copyCells(selectedCells, isCut = true)
                                    statusMessage = "Cut ${selectedCells.size} cell(s)!"
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text("✂ Cut", color = Color.White, fontSize = 11.sp) }

                            Button(
                                onClick = {
                                    cellClipboard?.let { clip ->
                                        val pasted = currentTable.pasteCells(selectedCells, anchorCell, clip)
                                        statusMessage = if (clip.items.size == 1 && selectedCells.size > 1) {
                                            "Replicated '${clip.items.first().value}' into ${selectedCells.size} cells!"
                                        } else {
                                            "Pasted ${pasted.size} item(s)!"
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
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text("🗑 Clear Selection", color = Color.White, fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = { showClearTableConfirm = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text("Clear All", color = Color.Red, fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = {
                                    selectedCells.clear()
                                    for (r in currentTable.rows.indices) {
                                        for (c in currentTable.headers.indices) selectedCells.add(Pair(r, c))
                                    }
                                    isMultiSelectMode = true
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("Select All", fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = { selectedCells.clear(); selectedCells.add(anchorCell) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("Clear", fontSize = 11.sp) }

                            Text("Shift:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = {
                                val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.LEFT)
                                selectedCells.clear(); selectedCells.addAll(u)
                            }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("◀") }
                            Button(onClick = {
                                val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.RIGHT)
                                selectedCells.clear(); selectedCells.addAll(u)
                            }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("▶") }
                            Button(onClick = {
                                val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.UP)
                                selectedCells.clear(); selectedCells.addAll(u)
                            }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("▲") }
                            Button(onClick = {
                                val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.DOWN)
                                selectedCells.clear(); selectedCells.addAll(u)
                            }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("▼") }
                        }
                    }

                    // Main Table Canvas with Full Vertical & Horizontal Scrolling
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Column(modifier = Modifier.width(totalTableWidth).fillMaxHeight()) {
                            // Column Headers Row with Inline Re-Naming and + Col Button
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFE3EDF7))
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Corner Cell (-1, -1)
                                val isCornerSelected = selectedCells.contains(Pair(-1, -1))
                                Box(
                                    modifier = Modifier
                                        .width(actionColWidth)
                                        .border(if (isCornerSelected) 2.dp else 1.dp, if (isCornerSelected) Color(0xFF1E88E5) else Color.LightGray)
                                        .background(if (isCornerSelected) Color(0xFFBBDEFB) else Color(0xFFECEFF1))
                                        .padding(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        BasicTextField(
                                            value = currentTable.cornerHeader,
                                            onValueChange = { currentTable.cornerHeader = it },
                                            textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0D47A1)),
                                            modifier = Modifier.weight(1f).onFocusChanged {
                                                if (it.isFocused) {
                                                    anchorCell = Pair(-1, -1)
                                                    if (!isMultiSelectMode) {
                                                        selectedCells.clear(); selectedCells.add(Pair(-1, -1))
                                                    }
                                                }
                                            }
                                        )
                                        Text("✎", fontSize = 11.sp, color = Color.Gray)
                                    }
                                }

                                // 2. Interactive Column Headers (-1, colIdx) with Reactive Renaming
                                visibleColIndices.forEach { colIdx ->
                                    val colDef = currentTable.headers[colIdx]
                                    val isColSelected = selectedCells.contains(Pair(-1, colIdx))
                                    val hasActiveValueFilter = columnValueFilters[colIdx]?.isNotEmpty() == true

                                    Box(
                                        modifier = Modifier
                                            .width(dataColWidth)
                                            .border(if (isColSelected) 2.dp else 1.dp, if (isColSelected) Color(0xFF1E88E5) else Color.LightGray)
                                            .background(if (hasActiveValueFilter) Color(0xFFFFF9C4) else if (isColSelected) Color(0xFFBBDEFB) else Color(0xFFF5F9FD))
                                            .padding(6.dp)
                                    ) {
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                // FIXED: Immutable copy triggers immediate Compose recomposition!
                                                BasicTextField(
                                                    value = colDef.name,
                                                    onValueChange = { newName ->
                                                        currentTable.headers[colIdx] = colDef.copy(name = newName)
                                                        currentTable.markUpdated()
                                                    },
                                                    textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                                    modifier = Modifier.weight(1f).onFocusChanged {
                                                        if (it.isFocused) {
                                                            anchorCell = Pair(-1, colIdx)
                                                            if (!isMultiSelectMode) {
                                                                selectedCells.clear(); selectedCells.add(Pair(-1, colIdx))
                                                            }
                                                        }
                                                    }
                                                )

                                                Text(
                                                    "✕",
                                                    color = Color.Red,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.clickable(enabled = currentTable.headers.size > 1) {
                                                        currentTable.deleteColumn(colIdx)
                                                    }
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                var typeExpanded by remember { mutableStateOf(false) }
                                                Box {
                                                    Text(
                                                        text = "[${colDef.type.label.take(7)} ▼]",
                                                        fontSize = 10.sp,
                                                        color = Color(0xFF0D47A1),
                                                        modifier = Modifier
                                                            .background(Color(0xFFE1F5FE), RoundedCornerShape(3.dp))
                                                            .clickable { typeExpanded = true }
                                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                    DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                                                        ColumnType.values().forEach { ct ->
                                                            DropdownMenuItem(onClick = {
                                                                currentTable.headers[colIdx] = colDef.copy(type = ct)
                                                                currentTable.markUpdated()
                                                                typeExpanded = false
                                                            }) { Text(ct.label) }
                                                        }
                                                    }
                                                }

                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Text("◀", modifier = Modifier.clickable(enabled = colIdx > 0) { currentTable.moveColumn(colIdx, colIdx - 1) }, fontSize = 12.sp)
                                                    Text("▶", modifier = Modifier.clickable(enabled = colIdx < currentTable.headers.size - 1) { currentTable.moveColumn(colIdx, colIdx + 1) }, fontSize = 12.sp)

                                                    // Distinct Value Filter Trigger
                                                    Box {
                                                        Text(
                                                            if (hasActiveValueFilter) "●" else "▾",
                                                            fontSize = 11.sp,
                                                            color = if (hasActiveValueFilter) Color(0xFFE65100) else Color.Gray,
                                                            modifier = Modifier.clickable { activeColDropdownIdx = colIdx }.padding(start = 2.dp)
                                                        )
                                                        DropdownMenu(
                                                            expanded = activeColDropdownIdx == colIdx,
                                                            onDismissRequest = { activeColDropdownIdx = null }
                                                        ) {
                                                            Text("Filter '${colDef.name}' values:", fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                                                            val distinctVals = currentTable.rows.map { it.getOrElse(colIdx) { "" } }.distinct().filter { it.isNotBlank() }

                                                            DropdownMenuItem(onClick = {
                                                                columnValueFilters.remove(colIdx)
                                                                activeColDropdownIdx = null
                                                            }) { Text("Clear Filter (Show All)", color = Color.Red, fontSize = 11.sp) }

                                                            distinctVals.forEach { dVal ->
                                                                val isChecked = columnValueFilters[colIdx]?.contains(dVal) == true
                                                                DropdownMenuItem(onClick = {
                                                                    val currentSet = columnValueFilters[colIdx] ?: mutableSetOf()
                                                                    if (currentSet.contains(dVal)) {
                                                                        currentSet.remove(dVal)
                                                                        if (currentSet.isEmpty()) columnValueFilters.remove(colIdx)
                                                                    } else {
                                                                        currentSet.add(dVal)
                                                                        columnValueFilters[colIdx] = currentSet
                                                                    }
                                                                }) {
                                                                    Checkbox(checked = isChecked, onCheckedChange = null)
                                                                    Spacer(modifier = Modifier.width(4.dp))
                                                                    Text(dVal, fontSize = 12.sp)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Direct "+ Col" Header Button
                                Box(modifier = Modifier.width(90.dp).padding(4.dp), contentAlignment = Alignment.Center) {
                                    Button(
                                        onClick = {
                                            val nextColNum = currentTable.headers.size + 1
                                            currentTable.addColumn("Col $nextColNum")
                                            statusMessage = "Added Column $nextColNum"
                                        },
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text("+ Col", color = Color.White, fontSize = 11.sp) }
                                }
                            }

                            // Body Rows LazyColumn with restored '✎' Row Editor button
                            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                itemsIndexed(filteredRowIndices) { _, origRIdx ->
                                    val rowData = currentTable.rows[origRIdx]
                                    val isRowSelected = selectedCells.contains(Pair(origRIdx, -1))

                                    Row(
                                        modifier = Modifier.fillMaxWidth().border(0.5.dp, Color(0xFFE0E0E0)),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Row Title Box with Direct '✎' Advanced Editor Launch
                                        Box(
                                            modifier = Modifier
                                                .width(actionColWidth)
                                                .border(0.5.dp, Color(0xFFCFD8DC))
                                                .background(if (isRowSelected) Color(0xFFBBDEFB) else Color(0xFFF9FAFB))
                                                .padding(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                BasicTextField(
                                                    value = currentTable.rowNames.getOrElse(origRIdx) { "Row ${origRIdx + 1}" },
                                                    onValueChange = {
                                                        currentTable.rowNames[origRIdx] = it
                                                        currentTable.markUpdated()
                                                    },
                                                    textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0)),
                                                    modifier = Modifier.weight(1f).onFocusChanged {
                                                        if (it.isFocused) {
                                                            anchorCell = Pair(origRIdx, -1)
                                                            if (!isMultiSelectMode) {
                                                                selectedCells.clear(); selectedCells.add(Pair(origRIdx, -1))
                                                            }
                                                        }
                                                    }
                                                )

                                                // RESTORED: Direct Advanced Row Editor UI Trigger
                                                Text(
                                                    "✎",
                                                    fontSize = 13.sp,
                                                    color = Color(0xFF00897B),
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier
                                                        .clickable {
                                                            editingRowIndex = origRIdx
                                                            showRowEditorDialog = true
                                                        }
                                                        .padding(horizontal = 2.dp)
                                                )

                                                // Row Dropdown Menu (Insert Above, Below, Editor, Delete)
                                                Box {
                                                    IconButton(onClick = { activeRowDropdownIdx = origRIdx }, modifier = Modifier.size(20.dp)) {
                                                        Text("▼", fontSize = 10.sp, color = Color.Gray)
                                                    }
                                                    DropdownMenu(expanded = activeRowDropdownIdx == origRIdx, onDismissRequest = { activeRowDropdownIdx = null }) {
                                                        DropdownMenuItem(onClick = { currentTable.addRow("Row", index = origRIdx); activeRowDropdownIdx = null }) { Text("+ Insert Row Above") }
                                                        DropdownMenuItem(onClick = { currentTable.addRow("Row", index = origRIdx + 1); activeRowDropdownIdx = null }) { Text("+ Insert Row Below") }
                                                        Divider()
                                                        DropdownMenuItem(onClick = { editingRowIndex = origRIdx; showRowEditorDialog = true; activeRowDropdownIdx = null }) { Text("✎ Open Row Editor") }
                                                        Divider()
                                                        DropdownMenuItem(onClick = { currentTable.deleteRow(origRIdx); activeRowDropdownIdx = null }) { Text("✕ Delete Row", color = Color.Red) }
                                                    }
                                                }

                                                Text("▲", modifier = Modifier.clickable(enabled = origRIdx > 0) { currentTable.moveRow(origRIdx, origRIdx - 1) }, fontSize = 11.sp)
                                                Text("▼", modifier = Modifier.clickable(enabled = origRIdx < currentTable.rows.size - 1) { currentTable.moveRow(origRIdx, origRIdx + 1) }, fontSize = 11.sp)
                                                Text("✕", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { currentTable.deleteRow(origRIdx) })
                                            }
                                        }

                                        // Data Cells
                                        visibleColIndices.forEach { colIdx ->
                                            val cellCoord = Pair(origRIdx, colIdx)
                                            val cellValue = rowData.getOrElse(colIdx) { "" }
                                            val isSelected = selectedCells.contains(cellCoord)

                                            Box(
                                                modifier = Modifier
                                                    .width(dataColWidth)
                                                    .border(
                                                        width = if (isSelected) 2.dp else 0.5.dp,
                                                        color = if (isSelected) Color(0xFF1976D2) else Color.LightGray
                                                    )
                                                    .background(if (isSelected) Color(0xFFBBDEFB) else Color.White)
                                                    .padding(8.dp)
                                            ) {
                                                BasicTextField(
                                                    value = cellValue,
                                                    onValueChange = { currentTable.setCellValue(origRIdx, colIdx, it) },
                                                    textStyle = TextStyle(fontSize = 13.sp, color = Color.Black),
                                                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                                                        if (it.isFocused) {
                                                            anchorCell = cellCoord
                                                            if (!isMultiSelectMode) {
                                                                selectedCells.clear(); selectedCells.add(cellCoord)
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                item {
                                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                                        Button(
                                            onClick = { currentTable.addRow() },
                                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFECEFF1)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("+ Append New Row", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // TAB 1: SCANNER & OCR WORKSPACE
            // ==========================================
            if (selectedTabIndex == 1 && !isFullScreen) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        shape = RoundedCornerShape(8.dp),
                        backgroundColor = Color(0xFF263238)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (selectedBitmap != null) {
                                Image(
                                    bitmap = selectedBitmap!!.asImageBitmap(),
                                    contentDescription = "Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text("No image loaded", color = Color.White)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                                        Text("📷 Load Table Image")
                                    }
                                }
                            }

                            Row(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                                Button(
                                    onClick = { rotatePreviewImage() },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f))
                                ) { Text("🔄 90°", color = Color.White) }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                        ) { Text("📷 Pick Image", color = Color.White) }

                        Button(
                            onClick = { showCropperDialog = true },
                            enabled = selectedBitmap != null,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) { Text("✂ Crop & OCR", color = Color.White) }
                    }

                    // Word Bank Tokens
                    Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Extracted Word Bank (${detectedWords.size} words):", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(detectedWords) { _, w ->
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFFE8EEF5), RoundedCornerShape(4.dp))
                                            .clickable {
                                                val (r, c) = anchorCell
                                                currentTable.setCellValue(r, c, w)
                                                jumpToNextRow()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) { Text(w, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // TAB 2: CONVERSIONS & EXPORTS
            // ==========================================
            if (selectedTabIndex == 2 && !isFullScreen) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Conversions & Data Operations", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Export Active Table", fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = {
                                        activeExportFormat = ExportFormat.PDF
                                        fileSaveLauncher.launch("${currentTable.tableName}.pdf")
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828))
                                ) { Text("Export PDF", color = Color.White) }

                                Button(
                                    onClick = {
                                        activeExportFormat = ExportFormat.EXCEL
                                        fileSaveLauncher.launch("${currentTable.tableName}.csv")
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                                ) { Text("Export CSV", color = Color.White) }

                                Button(
                                    onClick = {
                                        activeExportFormat = ExportFormat.WORD
                                        fileSaveLauncher.launch("${currentTable.tableName}.doc")
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0))
                                ) { Text("Export Word", color = Color.White) }
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Import & Transformations", fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { csvImportLauncher.launch("text/*") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                            ) { Text("📥 Import CSV into Current Table", color = Color.White) }

                            Button(
                                onClick = { currentTable.transposeTable() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100))
                            ) { Text("⇄ Transpose Table (Swap Rows & Cols)", color = Color.White) }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Print & Share", fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { printTablePdf() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0277BD))
                                ) { Text("🖨 Print PDF", color = Color.White) }

                                Button(
                                    onClick = { shareTablePdf() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00838F))
                                ) { Text("↗ Share PDF", color = Color.White) }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // TAB 3: TABLES LIST & HISTORY
            // ==========================================
            if (selectedTabIndex == 3 && !isFullScreen) {
                Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Saved Tables (${TableRepository.tables.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Button(
                            onClick = { showNewTableDialog = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) { Text("+ New Table", color = Color.White) }
                    }

                    OutlinedTextField(
                        value = drawerSearchQuery,
                        onValueChange = { drawerSearchQuery = it },
                        placeholder = { Text("🔍 Search tables...") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        singleLine = true
                    )

                    val filtered = TableRepository.tables.filter {
                        drawerSearchQuery.isBlank() || it.tableName.contains(drawerSearchQuery, ignoreCase = true)
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(filtered) { _, t ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                elevation = 2.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).clickable {
                                        currentTable = t
                                        tableSnapshot = t.createSnapshot()
                                        selectedTabIndex = 0
                                        statusMessage = "Loaded ${t.tableName}"
                                    }) {
                                        Text(t.tableName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("📅 ${t.tableDateTime} • ${t.rows.size} rows • ${t.headers.size} cols", fontSize = 11.sp, color = Color.Gray)
                                    }

                                    IconButton(onClick = {
                                        TableRepository.deleteTable(t.id)
                                    }) { Text("🗑", fontSize = 16.sp) }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // TAB 4: SETTINGS & CACHE MANAGER
            // ==========================================
            if (selectedTabIndex == 4 && !isFullScreen) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Settings & App Maintenance", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Cache & Storage", fontWeight = FontWeight.Bold)
                            Text("Current App Cache: $cacheSizeText", fontSize = 13.sp, color = Color.DarkGray)
                            Text("Includes temporary export PDF/Word docs, print rendering cache, and cropped camera snippets.", fontSize = 11.sp, color = Color.Gray)

                            Button(
                                onClick = {
                                    CacheManager.clearCache(context)
                                    cacheSizeText = CacheManager.getFormattedCacheSize(context)
                                    statusMessage = "Cache cleared successfully!"
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("🗑 Clear App Cache", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Table Danger Zone", fontWeight = FontWeight.Bold, color = Color.Red)
                            Button(
                                onClick = { showClearTableConfirm = true },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315)),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Empty All Cells in Active Table", color = Color.White) }

                            Button(
                                onClick = { showDeleteTableConfirm = true },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB71C1C)),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Delete Active Table Entirely", color = Color.White) }
                        }
                    }
                }
            }
        }
    }

    // SAVED TABLES DRAWER (Accessible via top bar icon)
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
                    if (filteredTables.isEmpty()) {
                        item { Text("No matching tables found.", fontSize = 12.sp, color = Color.Gray) }
                    }
                    itemsIndexed(filteredTables) { _, t ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            elevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            currentTable = t
                                            tableSnapshot = t.createSnapshot()
                                            selectedCells.clear(); selectedCells.add(Pair(0, 0))
                                            showTableHistoryDrawer = false
                                            statusMessage = "Loaded: ${t.tableName}"
                                        }
                                ) {
                                    Text(t.tableName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("📅 ${t.tableDateTime} • ${t.rows.size} rows", fontSize = 11.sp, color = Color.Gray)
                                }

                                IconButton(
                                    onClick = {
                                        TableRepository.deleteTable(t.id)
                                        if (currentTable.id == t.id) {
                                            val fallback = TableRepository.tables.firstOrNull() ?: TableData(
                                                initialName = "New Table",
                                                initialHeaders = listOf(ColumnDef("Col 1"), ColumnDef("Col 2")),
                                                initialRows = listOf(listOf("", ""), listOf("", ""))
                                            ).also { TableRepository.saveOrUpdate(it) }
                                            currentTable = fallback
                                            tableSnapshot = fallback.createSnapshot()
                                        }
                                    }
                                ) { Text("🗑", fontSize = 16.sp) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showTableHistoryDrawer = false }) { Text("Close") }
            }
        )
    }
}
