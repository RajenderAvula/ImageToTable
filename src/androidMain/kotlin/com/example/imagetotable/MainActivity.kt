package com.example.imagetotable

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
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
            )
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

    // Screen Modes
    var isFullScreen by remember { mutableStateOf(false) }
    var showComparisonView by remember { mutableStateOf(false) }
    var showCropperDialog by remember { mutableStateOf(false) }
    var showTableHistoryDrawer by remember { mutableStateOf(false) }
    var showDeleteTableConfirm by remember { mutableStateOf(false) }
    var showRowEditorDialog by remember { mutableStateOf(false) }
    var editingRowIndex by remember { mutableIntStateOf(0) }

    var showExportMenu by remember { mutableStateOf(false) }
    var activeExportFormat by remember { mutableStateOf(ExportFormat.PDF) }

    // =========================================================================
    // SEARCH BOX & DROPDOWN FILTER STATES ACROSS ALL UIS
    // =========================================================================
    var globalSearchQuery by remember { mutableStateOf("") }
    val hiddenColumns = remember { mutableStateListOf<Int>() }
    val columnSearchQueries = remember { mutableStateMapOf<Int, String>() }

    // Dropdown Filtering States
    var showColumnFilterDropdown by remember { mutableStateOf(false) }
    var showRowFilterDropdown by remember { mutableStateOf(false) }
    var selectedFilterColIndex by remember { mutableIntStateOf(0) }
    var drawerSearchQuery by remember { mutableStateOf("") }

    // CSV Import Launcher
    val csvImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = stream.bufferedReader().use { it.readText() }
                    val lines = content.lines().filter { it.isNotBlank() }
                    if (lines.isNotEmpty()) {
                        val headers = lines.first().split(",").map { it.trim().removeSurrounding("\"") }
                        val rows = lines.drop(1).map { line ->
                            line.split(",").map { it.trim().removeSurrounding("\"") }
                        }
                        currentTable.loadExtractedData(headers, rows)
                        tableSnapshot = currentTable.createSnapshot()
                        statusMessage = "Imported CSV successfully!"
                    }
                }
            } catch (e: Exception) {
                statusMessage = "Import Failed: ${e.message}"
            }
        }
    }

    // Export Launcher
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
                statusMessage = "Saved as ${activeExportFormat.extension.uppercase()}!"
            } catch (e: Exception) {
                statusMessage = "Save failed: ${e.message}"
            }
        }
    }

    // Gallery Picker Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                selectedBitmap = BitmapFactory.decodeStream(stream)
                showCropperDialog = true
            }
        }
    }

    fun printOrShareTable() {
        try {
            val file = File(context.cacheDir, "${currentTable.tableName}.pdf")
            FileOutputStream(file).use { out ->
                TableExporter.exportToPdf(currentTable, out)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Print / Share Table PDF"))
        } catch (e: Exception) {
            statusMessage = "Print error: ${e.message}"
        }
    }

    // Full-Screen Cropper
    if (showCropperDialog && selectedBitmap != null) {
        FullScreenCropperDialog(
            sourceBitmap = selectedBitmap!!,
            onDismiss = { showCropperDialog = false },
            onCropConfirmed = { cropped ->
                selectedBitmap = cropped
                showCropperDialog = false
                coroutineScope.launch {
                    isProcessing = true
                    statusMessage = "Extracting table..."
                    try {
                        val service = AndroidOcrService(context) { msg -> statusMessage = msg }
                        val (h, r) = service.extractTable(cropped)
                        withContext(Dispatchers.Main) {
                            currentTable.loadExtractedData(h, r)
                            tableSnapshot = currentTable.createSnapshot()

                            detectedWords.clear()
                            h.forEach { detectedWords.add(it) }
                            r.flatten().filter { it.isNotBlank() }.forEach { detectedWords.add(it) }

                            selectedCells.clear()
                            selectedCells.add(Pair(0, 0))
                            statusMessage = "Extracted ${r.size} rows & ${h.size} cols!"
                        }
                    } catch (e: Exception) {
                        statusMessage = "OCR Failed: ${e.message}"
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

                // Update Column Header Names
                updatedHeaders.forEachIndexed { idx, colName ->
                    if (idx in currentTable.headers.indices) {
                        currentTable.headers[idx].name = colName
                    }
                }

                currentTable.rows[safeIndex].clear()
                currentTable.rows[safeIndex].addAll(updatedValues)
                currentTable.markUpdated()

                TableRepository.saveOrUpdate(currentTable)
                tableSnapshot = currentTable.createSnapshot()

                statusMessage = "Saved Row #${safeIndex + 1} and updated header names!"
                showRowEditorDialog = false
            },
            onNavigateRow = { target -> editingRowIndex = target },
            onAddNewColumn = { name, type -> currentTable.addColumn(name, type) },
            onDeleteColumn = { colIdx -> currentTable.deleteColumn(colIdx) },
            onAddNewRowBelow = {
                currentTable.addRow("Row ${currentTable.rows.size + 1}")
                statusMessage = "Inserted row below!"
            },
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
            text = { Text("Are you sure you want to delete '${currentTable.tableName}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        TableRepository.deleteTable(currentTable.id)
                        val nextTable = TableRepository.tables.firstOrNull() ?: TableData(
                            initialName = "New Table",
                            initialHeaders = listOf(ColumnDef("Col 1"), ColumnDef("Col 2")),
                            initialRows = listOf(listOf("", ""), listOf("", ""))
                        ).also { TableRepository.saveOrUpdate(it) }
                        currentTable = nextTable
                        tableSnapshot = nextTable.createSnapshot()
                        selectedCells.clear()
                        selectedCells.add(Pair(0, 0))
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

    val visibleColIndices = currentTable.headers.indices.filter { !hiddenColumns.contains(it) }
    val filteredRowIndices = currentTable.rows.indices.filter { rIdx ->
        val globalNameMatch = currentTable.rowNames.getOrElse(rIdx) { "" }.contains(globalSearchQuery, ignoreCase = true)
        val globalCellMatch = currentTable.rows[rIdx].any { it.contains(globalSearchQuery, ignoreCase = true) }
        val matchesGlobal = globalSearchQuery.isBlank() || globalNameMatch || globalCellMatch

        val matchesColumnQueries = columnSearchQueries.all { (colIdx, q) ->
            if (q.isBlank()) true
            else currentTable.rows[rIdx].getOrElse(colIdx) { "" }.contains(q, ignoreCase = true)
        }
        matchesGlobal && matchesColumnQueries
    }

    val actionColWidth = 160.dp
    val dataColWidth = 150.dp
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
                                textStyle = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            )
                            Text("📅 ${currentTable.tableDateTime}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
                        }
                    },
                    backgroundColor = Color(0xFF1E88E5),
                    actions = {
                        IconButton(onClick = { showComparisonView = !showComparisonView }) {
                            Text(if (showComparisonView) "✕ Split" else "🔍 Split", color = Color.White, fontSize = 11.sp)
                        }
                        IconButton(onClick = { showDeleteTableConfirm = true }) {
                            Text("🗑 Del", color = Color(0xFFFFCDD2), fontSize = 11.sp)
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
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullScreen) 4.dp else paddingValues.calculateBottomPadding() + 4.dp)
        ) {
            // =========================================================================
            // FULL-SCREEN MODE TOP BAR WITH SEARCH BOX & TABLE NAME
            // =========================================================================
            if (isFullScreen) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    backgroundColor = Color(0xFFE8EEF5),
                    shape = RoundedCornerShape(6.dp),
                    elevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = currentTable.tableName,
                                onValueChange = {
                                    currentTable.tableName = it
                                    currentTable.markUpdated()
                                },
                                label = { Text("Table Name") },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.width(160.dp).height(48.dp)
                            )

                            // SEARCH BOX IN FULL SCREEN MODE
                            OutlinedTextField(
                                value = globalSearchQuery,
                                onValueChange = { globalSearchQuery = it },
                                placeholder = { Text("🔍 Search table...") },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 12.sp),
                                modifier = Modifier.width(170.dp).height(48.dp)
                            )

                            Button(
                                onClick = {
                                    CalendarPickerUtil.pickDateTime(context) { newDateTime ->
                                        currentTable.tableDateTime = newDateTime
                                        currentTable.markUpdated()
                                        TableRepository.saveOrUpdate(currentTable)
                                        statusMessage = "Bound table to: $newDateTime"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                modifier = Modifier.height(42.dp)
                            ) {
                                Text("📅 Date/Time", color = Color.White, fontSize = 11.sp)
                            }

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
                                    selectedCells.clear()
                                    selectedCells.add(Pair(0, 0))
                                    statusMessage = "Reverted!"
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                                modifier = Modifier.height(42.dp)
                            ) { Text("↩ Cancel", color = Color.White, fontSize = 11.sp) }

                            Button(
                                onClick = { isFullScreen = false },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray),
                                modifier = Modifier.height(42.dp)
                            ) { Text("Exit ✕", color = Color.White, fontSize = 11.sp) }
                        }
                    }
                }
            }

            // Normal Screen Menu Bar
            if (!isFullScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                            selectedCells.clear()
                            selectedCells.add(Pair(0, 0))
                            statusMessage = "Created new table!"
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1))
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
                            selectedCells.clear()
                            selectedCells.add(Pair(0, 0))
                            statusMessage = "Created new table from filtered view!"
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00ACC1))
                    ) { Text("📋 New from Filters", color = Color.White, fontSize = 11.sp) }

                    // ==========================================
                    // 1. DROPDOWN LIST FOR FILTERING COLUMNS
                    // ==========================================
                    Box {
                        Button(
                            onClick = { showColumnFilterDropdown = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F))
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

                    // ==========================================
                    // 2. DROPDOWN LIST FOR FILTERING ROWS BY VALUES
                    // ==========================================
                    Box {
                        Button(
                            onClick = { showRowFilterDropdown = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00695C))
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
                            // Pick Column Sub-selector
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
                            }) {
                                Text("✕ Clear Value Filter", color = Color.Red, fontSize = 12.sp)
                            }

                            // Extract Distinct Values from Selected Column
                            val distinctValues = currentTable.rows.mapNotNull {
                                it.getOrNull(selectedFilterColIndex)?.takeIf { s -> s.isNotBlank() }
                            }.distinct()

                            distinctValues.take(15).forEach { valItem ->
                                DropdownMenuItem(onClick = {
                                    globalSearchQuery = valItem
                                    showRowFilterDropdown = false
                                }) {
                                    Text("Match: \"$valItem\"", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { currentTable.addRow() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                    ) { Text("+ Add Row", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = { currentTable.addColumn("Col ${currentTable.headers.size + 1}") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                    ) { Text("+ Add Col", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = {
                            TableRepository.saveOrUpdate(currentTable)
                            tableSnapshot = currentTable.createSnapshot()
                            statusMessage = "Table '${currentTable.tableName}' saved!"
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                    ) { Text("💾 Save", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = {
                            currentTable.revertToSnapshot(tableSnapshot)
                            selectedCells.clear()
                            selectedCells.add(Pair(0, 0))
                            statusMessage = "Reverted to saved state!"
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828))
                    ) { Text("↩ Cancel", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = { csvImportLauncher.launch("text/*") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                    ) { Text("📥 Import", color = Color.White, fontSize = 11.sp) }

                    Box {
                        Button(
                            onClick = { showExportMenu = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A))
                        ) { Text("📤 Export ▼", color = Color.White, fontSize = 11.sp) }
                        DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.PDF
                                fileSaveLauncher.launch("${currentTable.tableName}.pdf")
                            }) { Text("Export PDF") }
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.EXCEL
                                fileSaveLauncher.launch("${currentTable.tableName}.csv")
                            }) { Text("Export CSV") }
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.WORD
                                fileSaveLauncher.launch("${currentTable.tableName}.doc")
                            }) { Text("Export Word") }
                            Divider()
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                printOrShareTable()
                            }) { Text("🖨 Print / Share") }
                        }
                    }

                    Button(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                    ) { Text("📷 Pick & Crop", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = { currentTable.transposeTable() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100))
                    ) { Text("⇄ Transpose", color = Color.White, fontSize = 11.sp) }
                }
            }

            // =========================================================================
            // SEARCH BOX IN MAIN UI
            // =========================================================================
            if (!isFullScreen) {
                OutlinedTextField(
                    value = globalSearchQuery,
                    onValueChange = { globalSearchQuery = it },
                    placeholder = { Text("🔍 Search table rows and column values across entire table...") },
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(bottom = 2.dp),
                    textStyle = TextStyle(fontSize = 12.sp),
                    singleLine = true
                )
            }

            // Split View Panel
            if (showComparisonView && !isFullScreen) {
                Card(
                    modifier = Modifier.fillMaxWidth().height(190.dp).padding(vertical = 4.dp),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (selectedBitmap != null) {
                                Image(
                                    bitmap = selectedBitmap!!.asImageBitmap(),
                                    contentDescription = "OCR Source",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color(0xFFEEEEEE), RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                                        Text("📷 Load Image", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        Divider(modifier = Modifier.fillMaxHeight().width(1.dp).padding(horizontal = 6.dp))

                        Column(
                            modifier = Modifier.weight(1.3f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Transfer Text to Table:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color(0xFF1976D2)
                            )

                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (detectedWords.isEmpty()) {
                                    item { Text("No words extracted yet. Pick & crop an image.", fontSize = 11.sp, color = Color.Gray) }
                                }
                                items(detectedWords.size) { idx ->
                                    val word = detectedWords[idx]
                                    val isPicked = selectedWordText == word
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (isPicked) Color(0xFF1976D2) else Color(0xFFE3F2FD),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable { selectedWordText = word }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(text = word, fontSize = 11.sp, color = if (isPicked) Color.White else Color.Black)
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = selectedWordText,
                                onValueChange = { selectedWordText = it },
                                label = { Text("Text to Transfer") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                textStyle = TextStyle(fontSize = 11.sp)
                            )

                            val (targetR, targetC) = anchorCell
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (selectedWordText.isNotBlank()) {
                                            currentTable.updateCell(targetR, targetC, selectedWordText)
                                            statusMessage = "Assigned '$selectedWordText' to ($targetR, $targetC)!"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("➔ Cell ($targetR,$targetC)", fontSize = 10.sp, color = Color.White) }

                                Button(
                                    onClick = {
                                        if (selectedWordText.isNotBlank()) {
                                            currentTable.addRow(selectedWordText)
                                            statusMessage = "Added new row '$selectedWordText'!"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("+ As Row", fontSize = 10.sp, color = Color.White) }

                                Button(
                                    onClick = {
                                        if (selectedWordText.isNotBlank()) {
                                            currentTable.addColumn(selectedWordText)
                                            statusMessage = "Added new col '$selectedWordText'!"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("+ As Col", fontSize = 10.sp, color = Color.White) }
                            }
                        }
                    }
                }
            }

            // Multi-Cell Selection Bar
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                backgroundColor = if (isMultiSelectMode) Color(0xFFF3E5F5) else Color(0xFFF0F4F8),
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
                        )
                    ) {
                        Text(
                            text = if (isMultiSelectMode) "✓ Multi-Select (${selectedCells.size})" else "☐ Multi-Select",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            cellClipboard = currentTable.copyCells(selectedCells, isCut = false)
                            statusMessage = "Copied ${selectedCells.size} cell(s)!"
                        },
                        enabled = selectedCells.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E88E5))
                    ) { Text("📋 Copy", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = {
                            cellClipboard = currentTable.copyCells(selectedCells, isCut = true)
                            statusMessage = "Cut ${selectedCells.size} cell(s). Tap Move Here!"
                        },
                        enabled = selectedCells.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315))
                    ) { Text("✂ Cut", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = {
                            cellClipboard?.let { clip ->
                                val (targetR, targetC) = anchorCell
                                val newSelected = currentTable.pasteCells(targetR, targetC, clip)
                                selectedCells.clear()
                                selectedCells.addAll(newSelected)
                                statusMessage = "Pasted/Moved to ($targetR, $targetC)!"
                                if (clip.isCut) cellClipboard = null
                            }
                        },
                        enabled = cellClipboard != null,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                    ) {
                        Text(if (cellClipboard?.isCut == true) "📌 Move Here" else "📌 Paste Here", color = Color.White, fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            selectedCells.clear()
                            for (r in currentTable.rows.indices) {
                                for (c in currentTable.headers.indices) selectedCells.add(Pair(r, c))
                            }
                            isMultiSelectMode = true
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("All", fontSize = 11.sp) }

                    OutlinedButton(
                        onClick = {
                            selectedCells.clear()
                            selectedCells.add(anchorCell)
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("Clear", fontSize = 11.sp) }

                    Text("Shift:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.LEFT)
                            selectedCells.clear(); selectedCells.addAll(updated)
                        },
                        enabled = selectedCells.isNotEmpty(),
                        modifier = Modifier.size(width = 34.dp, height = 30.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("◀") }

                    Button(
                        onClick = {
                            val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.RIGHT)
                            selectedCells.clear(); selectedCells.addAll(updated)
                        },
                        enabled = selectedCells.isNotEmpty(),
                        modifier = Modifier.size(width = 34.dp, height = 30.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("▶") }

                    Button(
                        onClick = {
                            val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.UP)
                            selectedCells.clear(); selectedCells.addAll(updated)
                        },
                        enabled = selectedCells.isNotEmpty(),
                        modifier = Modifier.size(width = 34.dp, height = 30.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("▲") }

                    Button(
                        onClick = {
                            val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.DOWN)
                            selectedCells.clear(); selectedCells.addAll(updated)
                        },
                        enabled = selectedCells.isNotEmpty(),
                        modifier = Modifier.size(width = 34.dp, height = 30.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("▼") }
                }
            }

            // Status Bar
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = "${filteredRowIndices.size}/${currentTable.rows.size} rows shown • Active Cell: (${anchorCell.first}, ${anchorCell.second}) • $statusMessage",
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
            }

            Divider()

            // =========================================================================
            // MAIN INTERACTIVE TABLE CANVAS (WITH ROW & COLUMN NAME EDITORS ACROSS ALL UIS)
            // =========================================================================
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Column(modifier = Modifier.width(totalTableWidth).fillMaxHeight()) {
                    // Header Row with DIRECT COLUMN NAME EDITOR
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE8EEF5))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(actionColWidth).padding(4.dp), contentAlignment = Alignment.Center) {
                            Text("Row Names & Controls", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        visibleColIndices.forEach { colIdx ->
                            val colDef = currentTable.headers[colIdx]
                            Column(
                                modifier = Modifier
                                    .width(dataColWidth)
                                    .border(0.5.dp, Color.LightGray)
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // INLINE COLUMN NAME EDITOR
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White, RoundedCornerShape(4.dp))
                                        .border(1.dp, Color(0xFF90CAF9), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("✎ ", fontSize = 11.sp, color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
                                    BasicTextField(
                                        value = colDef.name,
                                        onValueChange = {
                                            colDef.name = it
                                            currentTable.markUpdated()
                                        },
                                        textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clickable(enabled = colIdx > 0) { currentTable.moveColumn(colIdx, colIdx - 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("◀", fontSize = 14.sp, color = if (colIdx > 0) Color.Black else Color.LightGray)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clickable(enabled = colIdx < currentTable.headers.size - 1) { currentTable.moveColumn(colIdx, colIdx + 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("▶", fontSize = 14.sp, color = if (colIdx < currentTable.headers.size - 1) Color.Black else Color.LightGray)
                                    }
                                    Box(
                                        modifier = Modifier.size(28.dp).clickable { currentTable.deleteColumn(colIdx) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✕", fontSize = 15.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Inline Column Filter inputs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF1F5F9))
                            .border(0.5.dp, Color.LightGray)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(actionColWidth).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                            Text("Col Filters:", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                        }

                        visibleColIndices.forEach { colIdx ->
                            Box(modifier = Modifier.width(dataColWidth).padding(horizontal = 4.dp)) {
                                BasicTextField(
                                    value = columnSearchQueries[colIdx] ?: "",
                                    onValueChange = { columnSearchQueries[colIdx] = it },
                                    textStyle = TextStyle(fontSize = 11.sp, color = Color.DarkGray),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White, RoundedCornerShape(4.dp))
                                        .border(0.5.dp, Color.Gray, RoundedCornerShape(4.dp))
                                        .padding(4.dp),
                                    decorationBox = { innerTextField ->
                                        if ((columnSearchQueries[colIdx] ?: "").isEmpty()) {
                                            Text("Filter col...", fontSize = 11.sp, color = Color.LightGray)
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }
                    }

                    // Body Rows
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(filteredRowIndices) { _, origRIdx ->
                            val rowData = currentTable.rows[origRIdx]
                            Row(
                                modifier = Modifier.fillMaxWidth().border(0.5.dp, Color(0xFFE0E0E0)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Row Controls & DIRECT INLINE ROW NAME EDITOR
                                Row(
                                    modifier = Modifier
                                        .width(actionColWidth)
                                        .background(Color(0xFFF9FAFB))
                                        .padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clickable(enabled = origRIdx > 0) { currentTable.moveRow(origRIdx, origRIdx - 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("▲", fontSize = 13.sp, color = if (origRIdx > 0) Color.Black else Color.LightGray)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clickable(enabled = origRIdx < currentTable.rows.size - 1) { currentTable.moveRow(origRIdx, origRIdx + 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("▼", fontSize = 13.sp, color = if (origRIdx < currentTable.rows.size - 1) Color.Black else Color.LightGray)
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clickable {
                                                editingRowIndex = origRIdx
                                                showRowEditorDialog = true
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✎", fontSize = 15.sp, color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clickable { currentTable.deleteRow(origRIdx) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✕", fontSize = 14.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                                    }

                                    // INLINE ROW NAME EDITOR
                                    BasicTextField(
                                        value = currentTable.rowNames.getOrElse(origRIdx) { "Row ${origRIdx + 1}" },
                                        onValueChange = {
                                            currentTable.rowNames[origRIdx] = it
                                            currentTable.markUpdated()
                                        },
                                        textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(Color.White, RoundedCornerShape(3.dp))
                                            .border(0.5.dp, Color.LightGray, RoundedCornerShape(3.dp))
                                            .padding(2.dp)
                                    )
                                }

                                // Interactive Cells
                                visibleColIndices.forEach { colIdx ->
                                    val cellCoord = Pair(origRIdx, colIdx)
                                    val cellValue = rowData.getOrElse(colIdx) { "" }
                                    val isSelected = selectedCells.contains(cellCoord)
                                    val isAnchor = anchorCell == cellCoord
                                    val isStagedForCut = cellClipboard?.isCut == true && cellClipboard?.sourceCoords?.contains(cellCoord) == true

                                    val cellBg = when {
                                        isStagedForCut -> Color(0xFFFFEBEE)
                                        isSelected && isMultiSelectMode -> Color(0xFFE1BEE7)
                                        isSelected -> Color(0xFFBBDEFB)
                                        else -> Color.White
                                    }

                                    val cellBorderColor = when {
                                        isAnchor -> Color(0xFF00E676)
                                        isStagedForCut -> Color(0xFFD32F2F)
                                        isSelected && isMultiSelectMode -> Color(0xFF7B1FA2)
                                        isSelected -> Color(0xFF1976D2)
                                        else -> Color.LightGray
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(dataColWidth)
                                            .border(
                                                width = if (isSelected || isAnchor || isStagedForCut) 2.dp else 0.5.dp,
                                                color = cellBorderColor
                                            )
                                            .background(cellBg)
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            BasicTextField(
                                                value = cellValue,
                                                onValueChange = { currentTable.updateCell(origRIdx, colIdx, it) },
                                                textStyle = TextStyle(
                                                    fontSize = 13.sp,
                                                    color = if (isStagedForCut) Color.Red else Color.Black
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            anchorCell = cellCoord
                                                            if (!isMultiSelectMode) {
                                                                selectedCells.clear()
                                                                selectedCells.add(cellCoord)
                                                            }
                                                        }
                                                    }
                                            )
                                            if (isStagedForCut) {
                                                Text("✂", fontSize = 10.sp, color = Color.Red)
                                            } else if (isAnchor && isMultiSelectMode) {
                                                Text("⚓", fontSize = 10.sp, color = Color(0xFF2E7D32))
                                            }
                                        }

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

                        // Bottom Add Row Button
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Button(
                                    onClick = { currentTable.addRow() },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE0E0E0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("+ Add New Row at End", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // PAST TABLES DRAWER WITH SEARCH BOX ACROSS SAVED TABLES
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
                        placeholder = { Text("🔍 Search saved tables by name or date...") },
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
                                            selectedCells.clear()
                                            selectedCells.add(Pair(0, 0))
                                            showTableHistoryDrawer = false
                                            statusMessage = "Loaded table: ${t.tableName}"
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
