package com.example.imagetotable

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.example.imagetotable.ui.AdvancedRowEditorDialog
import com.example.imagetotable.ui.FullScreenCropperDialog
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

    // Default Starting Table
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

    // Multi-Cell Selection & Anchor
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedCells = remember { mutableStateListOf<Pair<Int, Int>>(Pair(0, 0)) }
    var anchorCell by remember { mutableStateOf(Pair(0, 0)) }
    var cellClipboard by remember { mutableStateOf<CellClipboard?>(null) }

    // OCR & Image Transfer State
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val detectedWords = remember { mutableStateListOf<String>() }
    var selectedWordText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready") }

    // Screen Modes & Dialogs
    var isFullScreen by remember { mutableStateOf(false) }
    var showComparisonView by remember { mutableStateOf(false) }
    var showCropperDialog by remember { mutableStateOf(false) }
    var showTableHistoryDrawer by remember { mutableStateOf(false) }
    var showDeleteTableConfirm by remember { mutableStateOf(false) }
    var showRowEditorDialog by remember { mutableStateOf(false) }
    var editingRowIndex by remember { mutableIntStateOf(0) }

    var showExportMenu by remember { mutableStateOf(false) }
    var activeExportFormat by remember { mutableStateOf(ExportFormat.PDF) }

    // Search & Filter
    var globalSearchQuery by remember { mutableStateOf("") }
    val hiddenColumns = remember { mutableStateListOf<Int>() }
    var showColumnFilterDropdown by remember { mutableStateOf(false) }
    var showRowFilterDropdown by remember { mutableStateOf(false) }
    var selectedFilterColIndex by remember { mutableIntStateOf(0) }
    var activeColDropdownIdx by remember { mutableStateOf<Int?>(null) }
    var activeRowDropdownIdx by remember { mutableStateOf<Int?>(null) }
    var drawerSearchQuery by remember { mutableStateOf("") }

    // Printing
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

    // Sharing
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

    // CSV Import Launcher
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

    // Export Document Launcher
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
                statusMessage = "Exported as ${activeExportFormat.extension.uppercase()}!"
            } catch (e: Exception) {
                statusMessage = "Export failed: ${e.message}"
            }
        }
    }

    // Image Picker Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                selectedBitmap = BitmapFactory.decodeStream(stream)
                showCropperDialog = true
            }
        }
    }

    // Full Screen Cropper Dialog
    if (showCropperDialog && selectedBitmap != null) {
        FullScreenCropperDialog(
            sourceBitmap = selectedBitmap!!,
            onDismiss = { showCropperDialog = false },
            onCropConfirmed = { cropped ->
                selectedBitmap = cropped
                showCropperDialog = false
                coroutineScope.launch {
                    isProcessing = true
                    statusMessage = "Extracting table via OCR..."
                    try {
                        val service = AndroidOcrService(context) { msg -> statusMessage = msg }
                        val (h, r) = service.extractTable(cropped)
                        withContext(Dispatchers.Main) {
                            currentTable.loadExtractedData(h, r)
                            tableSnapshot = currentTable.createSnapshot()
                            detectedWords.clear()
                            h.forEach { detectedWords.add(it) }
                            r.flatten().filter { it.isNotBlank() }.forEach { detectedWords.add(it) }
                            selectedCells.clear(); selectedCells.add(Pair(0, 0))
                            statusMessage = "Extracted ${r.size} rows & ${h.size} cols!"
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

    // Advanced Row Editor Dialog
    if (showRowEditorDialog && currentTable.rows.isNotEmpty()) {
        val safeIndex = editingRowIndex.coerceIn(0, currentTable.rows.size - 1)
        AdvancedRowEditorDialog(
            initialTableName = currentTable.tableName,
            initialTableDateTime = currentTable.tableDateTime,
            currentRowIndex = safeIndex,
            totalRows = currentTable.rows.size,
            rowName = currentTable.rowNames.getOrElse(safeIndex) { "" },
            headers = currentTable.headers,
            rowValues = currentTable.rows.getOrElse(safeIndex) { emptyList() },
            onDismiss = { showRowEditorDialog = false },
            onSaveRowAndTable = { newName, newDateTime, updatedRowName, updatedHeaders, updatedValues ->
                currentTable.tableName = newName
                currentTable.tableDateTime = newDateTime
                currentTable.rowNames[safeIndex] = updatedRowName
                updatedHeaders.forEachIndexed { idx, colName ->
                    if (idx in currentTable.headers.indices) currentTable.headers[idx].name = colName
                }
                currentTable.rows[safeIndex].clear()
                currentTable.rows[safeIndex].addAll(updatedValues)
                currentTable.markUpdated()
                TableRepository.saveOrUpdate(currentTable)
                tableSnapshot = currentTable.createSnapshot()
                showRowEditorDialog = false
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

    // Delete Table Dialog
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
            dismissButton = {
                TextButton(onClick = { showDeleteTableConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Filter computation
    val visibleColIndices = currentTable.headers.indices.filter { !hiddenColumns.contains(it) }
    val filteredRowIndices = currentTable.rows.indices.filter { rIdx ->
        val nameMatch = currentTable.rowNames.getOrElse(rIdx) { "" }.contains(globalSearchQuery, ignoreCase = true)
        val cellMatch = currentTable.rows[rIdx].any { it.contains(globalSearchQuery, ignoreCase = true) }
        globalSearchQuery.isBlank() || nameMatch || cellMatch
    }

    val actionColWidth = 190.dp
    val dataColWidth = 180.dp
    val totalTableWidth = actionColWidth + (dataColWidth * visibleColIndices.size)

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
                        IconButton(onClick = { showComparisonView = !showComparisonView }) {
                            Text(if (showComparisonView) "✕ Split" else "🔍 Split", color = Color.White, fontSize = 11.sp)
                        }
                        IconButton(onClick = { showTableHistoryDrawer = true }) {
                            Text("📂 Tables", color = Color.White, fontSize = 11.sp)
                        }
                        IconButton(onClick = { showDeleteTableConfirm = true }) {
                            Text("🗑 Del", color = Color(0xFFFFCDD2), fontSize = 11.sp)
                        }
                        IconButton(onClick = { isFullScreen = true }) {
                            Text("⛶ Full", color = Color.White, fontSize = 11.sp)
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        // DISMISS KEYBOARD ON OUTSIDE TAP
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullScreen) 4.dp else paddingValues.calculateBottomPadding() + 4.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
        ) {
            // =========================================================================
            // 1. FULL-SCREEN MODE TOP BAR WITH EXIT BUTTON & CONTROLS
            // =========================================================================
            if (isFullScreen) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    backgroundColor = Color(0xFFE8EEF5),
                    shape = RoundedCornerShape(6.dp),
                    elevation = 2.dp
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
                            modifier = Modifier.width(160.dp).height(48.dp)
                        )

                        Button(
                            onClick = {
                                CalendarPickerUtil.pickDateTime(context) { newDateTime ->
                                    currentTable.tableDateTime = newDateTime
                                    currentTable.markUpdated()
                                    TableRepository.saveOrUpdate(currentTable)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                            modifier = Modifier.height(42.dp)
                        ) { Text("📅 ${currentTable.tableDateTime.take(16)}", color = Color.White, fontSize = 11.sp) }

                        // Transpose in Full Screen
                        Button(
                            onClick = { currentTable.transposeTable() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100)),
                            modifier = Modifier.height(42.dp)
                        ) { Text("⇄ Transpose", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = { currentTable.addRow() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            modifier = Modifier.height(42.dp)
                        ) { Text("+ Row", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = { currentTable.addColumn("Col ${currentTable.headers.size + 1}") },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            modifier = Modifier.height(42.dp)
                        ) { Text("+ Col", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = { showComparisonView = !showComparisonView },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1)),
                            modifier = Modifier.height(42.dp)
                        ) { Text(if (showComparisonView) "✕ Split" else "🔍 Split", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = {
                                TableRepository.saveOrUpdate(currentTable)
                                tableSnapshot = currentTable.createSnapshot()
                                statusMessage = "Saved!"
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                            modifier = Modifier.height(42.dp)
                        ) { Text("💾 Save", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = {
                                currentTable.revertToSnapshot(tableSnapshot)
                                selectedCells.clear(); selectedCells.add(Pair(0, 0))
                                statusMessage = "Reverted!"
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                            modifier = Modifier.height(42.dp)
                        ) { Text("↩ Cancel", color = Color.White, fontSize = 11.sp) }

                        // PROMINENT EXIT FULL SCREEN BUTTON
                        Button(
                            onClick = { isFullScreen = false },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray),
                            modifier = Modifier.height(42.dp)
                        ) { Text("Exit Full Screen ✕", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            // =========================================================================
            // 2. MAIN TOOLBAR (Normal Screen Mode)
            // =========================================================================
            if (!isFullScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // NEW TABLE
                    Button(
                        onClick = {
                            val newTable = TableData(
                                initialName = "Table ${TableRepository.tables.size + 1}",
                                initialHeaders = listOf(ColumnDef("Col 1"), ColumnDef("Col 2")),
                                initialRows = listOf(listOf("", ""), listOf("", ""))
                            )
                            TableRepository.saveOrUpdate(newTable)
                            currentTable = newTable
                            tableSnapshot = newTable.createSnapshot()
                            selectedCells.clear(); selectedCells.add(Pair(0, 0))
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("+ New Table", color = Color.White, fontSize = 11.sp) }

                    // NEW TABLE FROM FILTERS
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

                    // TRANSPOSE BUTTON
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

                    // IMPORT CSV BUTTON
                    Button(
                        onClick = { csvImportLauncher.launch("text/*") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("📥 Import CSV", color = Color.White, fontSize = 11.sp) }

                    // PRINT & SHARE BUTTONS
                    Button(
                        onClick = { printTablePdf() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0277BD)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("🖨 Print", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = { shareTablePdf() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00838F)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("↗ Share", color = Color.White, fontSize = 11.sp) }

                    Box {
                        Button(
                            onClick = { showExportMenu = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("📤 Export ▼", color = Color.White, fontSize = 11.sp) }
                        DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.PDF
                                fileSaveLauncher.launch("${currentTable.tableName}.pdf")
                            }) { Text("PDF Document (.pdf)") }
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.EXCEL
                                fileSaveLauncher.launch("${currentTable.tableName}.csv")
                            }) { Text("Excel Spreadsheet (.csv)") }
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.WORD
                                fileSaveLauncher.launch("${currentTable.tableName}.doc")
                            }) { Text("Word Document (.doc)") }
                        }
                    }

                    Button(
                        onClick = { currentTable.addRow() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("+ Row", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = { currentTable.addColumn("Col ${currentTable.headers.size + 1}") },
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
            }

            // =========================================================================
            // 3. UNCONGESTED SEARCH BOX
            // =========================================================================
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                elevation = 1.dp,
                backgroundColor = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔍", fontSize = 15.sp, modifier = Modifier.padding(end = 6.dp))
                    BasicTextField(
                        value = globalSearchQuery,
                        onValueChange = { globalSearchQuery = it },
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        textStyle = TextStyle(fontSize = 13.sp, color = Color.Black),
                        decorationBox = { innerTextField ->
                            if (globalSearchQuery.isEmpty()) {
                                Text("Search rows, cells, and values across entire table...", color = Color.Gray, fontSize = 12.sp)
                            }
                            innerTextField()
                        }
                    )
                    if (globalSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { globalSearchQuery = "" }, modifier = Modifier.size(22.dp)) {
                            Text("✕", fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }
            }

            // =========================================================================
            // 4. SPLIT VIEW & ONE-BY-ONE TEXT TRANSFER (Works in Normal & Full-Screen)
            // =========================================================================
            if (showComparisonView) {
                Card(
                    modifier = Modifier.fillMaxWidth().height(260.dp).padding(4.dp),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                        // High-visibility Image Box
                        Box(
                            modifier = Modifier
                                .weight(1.1f)
                                .fillMaxHeight()
                                .background(Color(0xFF212121), RoundedCornerShape(6.dp))
                                .border(1.dp, Color.Gray, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedBitmap != null) {
                                Image(
                                    bitmap = selectedBitmap!!.asImageBitmap(),
                                    contentDescription = "Source Image",
                                    modifier = Modifier.fillMaxSize().padding(4.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                                    Text("📷 Load Image", fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Interactive Token Inspector
                        Column(
                            modifier = Modifier.weight(1.2f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Extracted Tokens (Tap to Transfer):", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF1565C0))

                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (detectedWords.isEmpty()) {
                                    item { Text("No tokens yet. Load & crop image.", fontSize = 11.sp, color = Color.Gray) }
                                }
                                items(detectedWords.size) { idx ->
                                    val word = detectedWords[idx]
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (selectedWordText == word) Color(0xFF1976D2) else Color(0xFFE3F2FD),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable { selectedWordText = word }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(word, fontSize = 11.sp, color = if (selectedWordText == word) Color.White else Color.Black)
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = selectedWordText,
                                onValueChange = { selectedWordText = it },
                                label = { Text("Selected Token") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                textStyle = TextStyle(fontSize = 12.sp)
                            )

                            val (tr, tc) = anchorCell
                            val targetLabel = when {
                                tr == -1 && tc == -1 -> "Corner"
                                tr == -1 -> "Col Header #$tc"
                                tc == -1 -> "Row Title #$tr"
                                else -> "Cell ($tr, $tc)"
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = {
                                        currentTable.setCellValue(tr, tc, selectedWordText)
                                        // Auto advance to next cell for fast data entry
                                        anchorCell = if (tc < currentTable.headers.size - 1) Pair(tr, tc + 1) else Pair((tr + 1).coerceAtMost(currentTable.rows.size - 1), 0)
                                        statusMessage = "Transferred to $targetLabel!"
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                    contentPadding = PaddingValues(2.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("➔ $targetLabel", fontSize = 10.sp, color = Color.White) }

                                Button(
                                    onClick = { currentTable.addRow(selectedWordText) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                    contentPadding = PaddingValues(2.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("+ Row", fontSize = 10.sp, color = Color.White) }

                                Button(
                                    onClick = { currentTable.addColumn(selectedWordText) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1)),
                                    contentPadding = PaddingValues(2.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("+ Col", fontSize = 10.sp, color = Color.White) }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 5. MULTI-CELL SELECTION & 1-TO-MANY PASTE TOOLBAR
            // =========================================================================
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
                    Button(
                        onClick = { isMultiSelectMode = !isMultiSelectMode },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isMultiSelectMode) Color(0xFF7B1FA2) else Color(0xFF546E7A)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (isMultiSelectMode) "✓ Multi-Select (${selectedCells.size})" else "☐ Multi-Select",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }

                    Button(
                        onClick = {
                            cellClipboard = currentTable.copyCells(selectedCells, isCut = false)
                            statusMessage = "Copied ${selectedCells.size} item(s)!"
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E88E5)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("📋 Copy", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = {
                            cellClipboard = currentTable.copyCells(selectedCells, isCut = true)
                            statusMessage = "Cut ${selectedCells.size} item(s)!"
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("✂ Cut", color = Color.White, fontSize = 11.sp) }

                    // PASTE (1-to-many replication support)
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
                        onClick = {
                            selectedCells.clear(); selectedCells.add(anchorCell)
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("Clear", fontSize = 11.sp) }

                    Text("Shift:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.LEFT)
                            selectedCells.clear(); selectedCells.addAll(updated)
                        },
                        modifier = Modifier.size(width = 32.dp, height = 28.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("◀") }
                    Button(
                        onClick = {
                            val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.RIGHT)
                            selectedCells.clear(); selectedCells.addAll(updated)
                        },
                        modifier = Modifier.size(width = 32.dp, height = 28.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("▶") }
                    Button(
                        onClick = {
                            val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.UP)
                            selectedCells.clear(); selectedCells.addAll(updated)
                        },
                        modifier = Modifier.size(width = 32.dp, height = 28.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("▲") }
                    Button(
                        onClick = {
                            val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.DOWN)
                            selectedCells.clear(); selectedCells.addAll(updated)
                        },
                        modifier = Modifier.size(width = 32.dp, height = 28.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("▼") }
                }
            }

            // =========================================================================
            // 6. MAIN TABLE CANVAS (Includes Corner (-1,-1), Headers & Row Names in Grid)
            // =========================================================================
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    }
            ) {
                Column(modifier = Modifier.width(totalTableWidth).fillMaxHeight()) {
                    // Header Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE3EDF7))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. CORNER CELL (-1, -1)
                        val isCornerSelected = selectedCells.contains(Pair(-1, -1))
                        Box(
                            modifier = Modifier
                                .width(actionColWidth)
                                .border(
                                    width = if (isCornerSelected) 2.dp else 1.dp,
                                    color = if (isCornerSelected) Color(0xFF1E88E5) else Color.LightGray
                                )
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
                                    textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0D47A1)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .onFocusChanged {
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

                        // 2. COLUMN HEADERS (-1, colIdx)
                        visibleColIndices.forEach { colIdx ->
                            val colDef = currentTable.headers[colIdx]
                            val isColSelected = selectedCells.contains(Pair(-1, colIdx))

                            Box(
                                modifier = Modifier
                                    .width(dataColWidth)
                                    .border(
                                        width = if (isColSelected) 2.dp else 1.dp,
                                        color = if (isColSelected) Color(0xFF1E88E5) else Color.LightGray
                                    )
                                    .background(if (isColSelected) Color(0xFFBBDEFB) else Color(0xFFF5F9FD))
                                    .padding(6.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        BasicTextField(
                                            value = colDef.name,
                                            onValueChange = { colDef.name = it; currentTable.markUpdated() },
                                            textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .onFocusChanged {
                                                    if (it.isFocused) {
                                                        anchorCell = Pair(-1, colIdx)
                                                        if (!isMultiSelectMode) {
                                                            selectedCells.clear(); selectedCells.add(Pair(-1, colIdx))
                                                        }
                                                    }
                                                }
                                        )

                                        // INLINE COLUMN DROPDOWN TRIGGER ▼
                                        Box {
                                            IconButton(
                                                onClick = { activeColDropdownIdx = colIdx },
                                                modifier = Modifier.size(24.dp)
                                            ) { Text("▼", fontSize = 11.sp, color = Color(0xFF1976D2)) }

                                            DropdownMenu(
                                                expanded = activeColDropdownIdx == colIdx,
                                                onDismissRequest = { activeColDropdownIdx = null }
                                            ) {
                                                Text("Column: ${colDef.name}", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                                                Divider()
                                                DropdownMenuItem(onClick = {
                                                    currentTable.sortRowsByColumn(colIdx, ascending = true)
                                                    activeColDropdownIdx = null
                                                }) { Text("Sort A ➔ Z") }
                                                DropdownMenuItem(onClick = {
                                                    currentTable.sortRowsByColumn(colIdx, ascending = false)
                                                    activeColDropdownIdx = null
                                                }) { Text("Sort Z ➔ A") }
                                                Divider()
                                                Text("Change Data Type:", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 8.dp))
                                                ColumnType.values().forEach { cType ->
                                                    DropdownMenuItem(onClick = {
                                                        colDef.type = cType
                                                        activeColDropdownIdx = null
                                                    }) { Text((if (colDef.type == cType) "● " else "○ ") + cType.label) }
                                                }
                                                Divider()
                                                DropdownMenuItem(onClick = {
                                                    currentTable.deleteColumn(colIdx)
                                                    activeColDropdownIdx = null
                                                }) { Text("✕ Delete Column", color = Color.Red) }
                                            }
                                        }
                                    }

                                    // Type Badge & Lateral Shift
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "[${colDef.type.label.take(8)}]",
                                            fontSize = 10.sp,
                                            color = Color(0xFF0D47A1),
                                            modifier = Modifier
                                                .background(Color(0xFFE1F5FE), RoundedCornerShape(3.dp))
                                                .clickable { activeColDropdownIdx = colIdx }
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                "◀",
                                                modifier = Modifier.clickable(enabled = colIdx > 0) {
                                                    currentTable.moveColumn(colIdx, colIdx - 1)
                                                },
                                                fontSize = 12.sp,
                                                color = if (colIdx > 0) Color.Black else Color.LightGray
                                            )
                                            Text(
                                                "▶",
                                                modifier = Modifier.clickable(enabled = colIdx < currentTable.headers.size - 1) {
                                                    currentTable.moveColumn(colIdx, colIdx + 1)
                                                },
                                                fontSize = 12.sp,
                                                color = if (colIdx < currentTable.headers.size - 1) Color.Black else Color.LightGray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Body Rows
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(filteredRowIndices) { _, origRIdx ->
                            val rowData = currentTable.rows[origRIdx]
                            val isRowNameSelected = selectedCells.contains(Pair(origRIdx, -1))

                            Row(
                                modifier = Modifier.fillMaxWidth().border(0.5.dp, Color(0xFFE0E0E0)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. ROW NAME (origRIdx, -1) WITH INLINE DROPDOWN & CONTROLS
                                Box(
                                    modifier = Modifier
                                        .width(actionColWidth)
                                        .border(
                                            width = if (isRowNameSelected) 2.dp else 0.5.dp,
                                            color = if (isRowNameSelected) Color(0xFF1E88E5) else Color(0xFFCFD8DC)
                                        )
                                        .background(if (isRowNameSelected) Color(0xFFBBDEFB) else Color(0xFFF9FAFB))
                                        .padding(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        BasicTextField(
                                            value = currentTable.rowNames.getOrElse(origRIdx) { "Row ${origRIdx + 1}" },
                                            onValueChange = { currentTable.rowNames[origRIdx] = it; currentTable.markUpdated() },
                                            textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .onFocusChanged {
                                                    if (it.isFocused) {
                                                        anchorCell = Pair(origRIdx, -1)
                                                        if (!isMultiSelectMode) {
                                                            selectedCells.clear(); selectedCells.add(Pair(origRIdx, -1))
                                                        }
                                                    }
                                                }
                                        )

                                        // INLINE ROW DROPDOWN TRIGGER ▼
                                        Box {
                                            IconButton(
                                                onClick = { activeRowDropdownIdx = origRIdx },
                                                modifier = Modifier.size(24.dp)
                                            ) { Text("▼", fontSize = 11.sp, color = Color.Gray) }

                                            DropdownMenu(
                                                expanded = activeRowDropdownIdx == origRIdx,
                                                onDismissRequest = { activeRowDropdownIdx = null }
                                            ) {
                                                DropdownMenuItem(onClick = {
                                                    currentTable.addRow("Row", index = origRIdx)
                                                    activeRowDropdownIdx = null
                                                }) { Text("+ Insert Row Above") }
                                                DropdownMenuItem(onClick = {
                                                    currentTable.addRow("Row", index = origRIdx + 1)
                                                    activeRowDropdownIdx = null
                                                }) { Text("+ Insert Row Below") }
                                                Divider()
                                                DropdownMenuItem(onClick = {
                                                    editingRowIndex = origRIdx
                                                    showRowEditorDialog = true
                                                    activeRowDropdownIdx = null
                                                }) { Text("✎ Open Row Editor") }
                                                Divider()
                                                DropdownMenuItem(onClick = {
                                                    currentTable.deleteRow(origRIdx)
                                                    activeRowDropdownIdx = null
                                                }) { Text("✕ Delete Row", color = Color.Red) }
                                            }
                                        }

                                        Text(
                                            "▲",
                                            modifier = Modifier.clickable(enabled = origRIdx > 0) { currentTable.moveRow(origRIdx, origRIdx - 1) },
                                            fontSize = 11.sp,
                                            color = if (origRIdx > 0) Color.Black else Color.LightGray
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "▼",
                                            modifier = Modifier.clickable(enabled = origRIdx < currentTable.rows.size - 1) { currentTable.moveRow(origRIdx, origRIdx + 1) },
                                            fontSize = 11.sp,
                                            color = if (origRIdx < currentTable.rows.size - 1) Color.Black else Color.LightGray
                                        )
                                    }
                                }

                                // 2. DATA CELLS (origRIdx, colIdx)
                                visibleColIndices.forEach { colIdx ->
                                    val cellCoord = Pair(origRIdx, colIdx)
                                    val cellValue = rowData.getOrElse(colIdx) { "" }
                                    val isSelected = selectedCells.contains(cellCoord)
                                    val isAnchor = anchorCell == cellCoord

                                    Box(
                                        modifier = Modifier
                                            .width(dataColWidth)
                                            .border(
                                                width = if (isSelected || isAnchor) 2.dp else 0.5.dp,
                                                color = if (isAnchor) Color(0xFF00E676) else if (isSelected) Color(0xFF1976D2) else Color.LightGray
                                            )
                                            .background(if (isSelected && isMultiSelectMode) Color(0xFFE1BEE7) else if (isSelected) Color(0xFFBBDEFB) else Color.White)
                                            .padding(8.dp)
                                    ) {
                                        BasicTextField(
                                            value = cellValue,
                                            onValueChange = { currentTable.setCellValue(origRIdx, colIdx, it) },
                                            textStyle = TextStyle(fontSize = 13.sp, color = Color.Black),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged { focusState ->
                                                    if (focusState.isFocused) {
                                                        anchorCell = cellCoord
                                                        if (!isMultiSelectMode) {
                                                            selectedCells.clear(); selectedCells.add(cellCoord)
                                                        }
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

                        // Bottom Append Button
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
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

    // =========================================================================
    // 7. SAVED TABLES DRAWER (WITH TIMESTAMP SEARCH & TABLE CREATION)
    // =========================================================================
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
