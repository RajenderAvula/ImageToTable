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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                listOf("C-305", "Desk Organizer", "3", "12.00"),
                listOf("D-402", "USB Cable", "8", "5.99")
            )
        ).also { TableRepository.saveOrUpdate(it) }
    }

    var currentTable by remember { mutableStateOf(initialTable) }
    var tableSnapshot by remember { mutableStateOf(initialTable.createSnapshot()) }

    // --- MULTI-CELL SELECTION & CLIPBOARD STATE ---
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedCells = remember { mutableStateListOf<Pair<Int, Int>>(Pair(0, 0)) }
    var anchorCell by remember { mutableStateOf(Pair(0, 0)) }
    var cellClipboard by remember { mutableStateOf<CellClipboard?>(null) }

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready") }

    // View Modes & Modals
    var isFullScreen by remember { mutableStateOf(false) }
    var showComparisonView by remember { mutableStateOf(false) }
    var showCropperDialog by remember { mutableStateOf(false) }
    var showColumnFilterDialog by remember { mutableStateOf(false) }
    var showTableHistoryDrawer by remember { mutableStateOf(false) }
    var showDeleteTableConfirm by remember { mutableStateOf(false) }
    var showRowEditorDialog by remember { mutableStateOf(false) }
    var editingRowIndex by remember { mutableIntStateOf(0) }

    var showExportMenu by remember { mutableStateOf(false) }
    var activeExportFormat by remember { mutableStateOf(ExportFormat.PDF) }

    var rowSearchQuery by remember { mutableStateOf("") }
    val hiddenColumns = remember { mutableStateListOf<Int>() }
    val columnSearchQueries = remember { mutableStateMapOf<Int, String>() }

    // Import Launcher
    val csvImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = stream.bufferedReader().use { it.readText() }
                    currentTable.importCsv(content)
                    tableSnapshot = currentTable.createSnapshot()
                    selectedCells.clear()
                    selectedCells.add(Pair(0, 0))
                    statusMessage = "Imported CSV successfully!"
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

    // Image Picker
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

    // Row Editor Dialog
    if (showRowEditorDialog && currentTable.rows.isNotEmpty()) {
        val safeIndex = editingRowIndex.coerceIn(0, currentTable.rows.size - 1)
        AdvancedRowEditorDialog(
            currentRowIndex = safeIndex,
            totalRows = currentTable.rows.size,
            rowName = currentTable.rowNames.getOrElse(safeIndex) { "" },
            headers = currentTable.headers,
            rowValues = currentTable.rows.getOrElse(safeIndex) { emptyList() },
            onDismiss = { showRowEditorDialog = false },
            onSaveRow = { name, values ->
                currentTable.rowNames[safeIndex] = name
                currentTable.rows[safeIndex].clear()
                currentTable.rows[safeIndex].addAll(values)
                currentTable.markUpdated()
            },
            onNavigateRow = { target -> editingRowIndex = target },
            onAddNewColumn = { name, type -> currentTable.addColumn(name, type) },
            onDeleteRow = {
                currentTable.deleteRow(safeIndex)
                showRowEditorDialog = false
            }
        )
    }

    // Column Filter Dialog
    if (showColumnFilterDialog) {
        AlertDialog(
            onDismissRequest = { showColumnFilterDialog = false },
            title = { Text("Filter Column Visibility", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    currentTable.headers.forEachIndexed { idx, h ->
                        val isHidden = hiddenColumns.contains(idx)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isHidden) hiddenColumns.remove(idx) else hiddenColumns.add(idx)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(checked = !isHidden, onCheckedChange = { chk ->
                                if (chk) hiddenColumns.remove(idx) else hiddenColumns.add(idx)
                            })
                            Text("${h.name} (${h.type.label})", fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showColumnFilterDialog = false }) { Text("Done") }
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
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteTableConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Filter computation
    val visibleColIndices = currentTable.headers.indices.filter { !hiddenColumns.contains(it) }
    val filteredRowIndices = currentTable.rows.indices.filter { rIdx ->
        val globalNameMatch = currentTable.rowNames.getOrElse(rIdx) { "" }.contains(rowSearchQuery, ignoreCase = true)
        val globalCellMatch = currentTable.rows[rIdx].any { it.contains(rowSearchQuery, ignoreCase = true) }
        val matchesGlobal = rowSearchQuery.isBlank() || globalNameMatch || globalCellMatch

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
                            Text(if (showComparisonView) "✕ Img" else "🔍 Split", color = Color.White, fontSize = 11.sp)
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
            // Full Screen Top Banner
            if (isFullScreen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${currentTable.tableName} (Full Screen Mode)",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E88E5),
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                TableRepository.saveOrUpdate(currentTable)
                                tableSnapshot = currentTable.createSnapshot()
                                statusMessage = "Saved!"
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) {
                            Text("Save", color = Color.White, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { isFullScreen = false },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                        ) {
                            Text("Exit ✕", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Normal Screen Menus & Table Actions
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
                            TableRepository.saveOrUpdate(currentTable)
                            tableSnapshot = currentTable.createSnapshot()
                            statusMessage = "Table '${currentTable.tableName}' saved!"
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                    ) {
                        Text("💾 Save", color = Color.White, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            currentTable.revertToSnapshot(tableSnapshot)
                            selectedCells.clear()
                            selectedCells.add(Pair(0, 0))
                            statusMessage = "Cancelled! Table reverted to saved state."
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828))
                    ) {
                        Text("↩ Cancel", color = Color.White, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { csvImportLauncher.launch("text/*") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                    ) {
                        Text("📥 Import CSV", color = Color.White, fontSize = 12.sp)
                    }

                    Box {
                        Button(
                            onClick = { showExportMenu = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A))
                        ) {
                            Text("📤 Export / Print ▼", color = Color.White, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.PDF
                                fileSaveLauncher.launch("${currentTable.tableName}.pdf")
                            }) { Text("Export as PDF (.pdf)") }
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.EXCEL
                                fileSaveLauncher.launch("${currentTable.tableName}.csv")
                            }) { Text("Export as Excel (.csv)") }
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.WORD
                                fileSaveLauncher.launch("${currentTable.tableName}.doc")
                            }) { Text("Export as Word (.doc)") }
                            Divider()
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                printOrShareTable()
                            }) { Text("🖨 Print / Share PDF") }
                        }
                    }

                    Button(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                    ) {
                        Text("📷 Pick & Crop", color = Color.White, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { currentTable.transposeTable() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100))
                    ) {
                        Text("⇄ Transpose", color = Color.White, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            CalendarPickerUtil.pickDateTime(context) { newDateTime ->
                                currentTable.tableDateTime = newDateTime
                                TableRepository.saveOrUpdate(currentTable)
                                statusMessage = "Bound to: $newDateTime"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                    ) {
                        Text("📅 Date/Time", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // ==========================================
            // MULTI-CELL SELECTION & MOVE / COPY CONTROL BAR
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                backgroundColor = if (isMultiSelectMode) Color(0xFFF3E5F5) else Color(0xFFF0F4F8),
                shape = RoundedCornerShape(6.dp),
                elevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Multi-Select Mode Toggle
                        Button(
                            onClick = { isMultiSelectMode = !isMultiSelectMode },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (isMultiSelectMode) Color(0xFF7B1FA2) else Color(0xFF546E7A)
                            )
                        ) {
                            Text(
                                text = if (isMultiSelectMode) "✓ Multi-Select ON (${selectedCells.size})" else "☐ Multi-Select OFF",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // COPY SELECTED CELLS
                        Button(
                            onClick = {
                                cellClipboard = currentTable.copyCells(selectedCells, isCut = false)
                                statusMessage = "Copied ${selectedCells.size} cell(s)!"
                            },
                            enabled = selectedCells.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E88E5))
                        ) {
                            Text("📋 Copy", color = Color.White, fontSize = 11.sp)
                        }

                        // CUT / MOVE SELECTED CELLS
                        Button(
                            onClick = {
                                cellClipboard = currentTable.copyCells(selectedCells, isCut = true)
                                statusMessage = "Cut ${selectedCells.size} cell(s). Select target & tap Paste!"
                            },
                            enabled = selectedCells.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315))
                        ) {
                            Text("✂ Cut / Move", color = Color.White, fontSize = 11.sp)
                        }

                        // PASTE / MOVE TO TARGET
                        Button(
                            onClick = {
                                cellClipboard?.let { clip ->
                                    val (targetR, targetC) = anchorCell
                                    val newSelected = currentTable.pasteCells(targetR, targetC, clip)
                                    selectedCells.clear()
                                    selectedCells.addAll(newSelected)
                                    statusMessage = if (clip.isCut) {
                                        "Moved ${newSelected.size} cell(s) to ($targetR, $targetC)!"
                                    } else {
                                        "Pasted ${newSelected.size} cell(s) to ($targetR, $targetC)!"
                                    }
                                    if (clip.isCut) cellClipboard = null // Consume cut clipboard
                                }
                            },
                            enabled = cellClipboard != null,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) {
                            Text(
                                text = if (cellClipboard?.isCut == true) "📌 Move Here" else "📌 Paste Here",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // SELECT ALL
                        OutlinedButton(
                            onClick = {
                                selectedCells.clear()
                                for (r in currentTable.rows.indices) {
                                    for (c in currentTable.headers.indices) {
                                        selectedCells.add(Pair(r, c))
                                    }
                                }
                                isMultiSelectMode = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("All", fontSize = 11.sp)
                        }

                        // DESELECT
                        OutlinedButton(
                            onClick = {
                                selectedCells.clear()
                                selectedCells.add(anchorCell)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Clear", fontSize = 11.sp)
                        }

                        // BATCH DIRECTIONAL SHIFT FOR ALL SELECTED CELLS
                        Text("Shift Selection:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = {
                                val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.LEFT)
                                selectedCells.clear()
                                selectedCells.addAll(updated)
                            },
                            enabled = selectedCells.isNotEmpty(),
                            modifier = Modifier.size(width = 36.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("◀", fontSize = 12.sp) }

                        Button(
                            onClick = {
                                val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.RIGHT)
                                selectedCells.clear()
                                selectedCells.addAll(updated)
                            },
                            enabled = selectedCells.isNotEmpty(),
                            modifier = Modifier.size(width = 36.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("▶", fontSize = 12.sp) }

                        Button(
                            onClick = {
                                val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.UP)
                                selectedCells.clear()
                                selectedCells.addAll(updated)
                            },
                            enabled = selectedCells.isNotEmpty(),
                            modifier = Modifier.size(width = 36.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("▲", fontSize = 12.sp) }

                        Button(
                            onClick = {
                                val updated = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.DOWN)
                                selectedCells.clear()
                                selectedCells.addAll(updated)
                            },
                            enabled = selectedCells.isNotEmpty(),
                            modifier = Modifier.size(width = 36.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("▼", fontSize = 12.sp) }
                    }
                }
            }

            // Global Filter Input
            if (!isFullScreen) {
                OutlinedTextField(
                    value = rowSearchQuery,
                    onValueChange = { rowSearchQuery = it },
                    placeholder = { Text("Filter rows by text...") },
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(bottom = 2.dp),
                    textStyle = TextStyle(fontSize = 13.sp),
                    singleLine = true
                )
            }

            // Side-by-Side Verification Panel
            if (showComparisonView && selectedBitmap != null && !isFullScreen) {
                Card(
                    modifier = Modifier.fillMaxWidth().height(140.dp).padding(vertical = 4.dp),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                        Column(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Source Image", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Image(
                                bitmap = selectedBitmap!!.asImageBitmap(),
                                contentDescription = "Original",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Divider(modifier = Modifier.fillMaxHeight().width(1.dp).padding(horizontal = 4.dp))
                        Column(modifier = Modifier.weight(1f).padding(4.dp)) {
                            Text("Verification Info", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Selected: ${selectedCells.size} cells", fontSize = 11.sp, color = Color(0xFF7B1FA2), fontWeight = FontWeight.Bold)
                            Text("Anchor Cell: (${anchorCell.first}, ${anchorCell.second})", fontSize = 11.sp)
                            cellClipboard?.let {
                                Text(
                                    if (it.isCut) "✂ ${it.items.size} cells staged for MOVE"
                                    else "📋 ${it.items.size} cells in COPY buffer",
                                    fontSize = 11.sp,
                                    color = if (it.isCut) Color(0xFFD84315) else Color(0xFF1E88E5),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Status Bar
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = "${filteredRowIndices.size}/${currentTable.rows.size} rows • Target: (${anchorCell.first}, ${anchorCell.second}) • $statusMessage",
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
            }

            Divider()

            // ==========================================
            // MAIN INTERACTIVE TABLE CANVAS
            // ==========================================
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Column(modifier = Modifier.width(totalTableWidth).fillMaxHeight()) {
                    // Header Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE8EEF5))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(actionColWidth).padding(4.dp), contentAlignment = Alignment.Center) {
                            Text("Row Controls", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                                BasicTextField(
                                    value = colDef.name,
                                    onValueChange = {
                                        colDef.name = it
                                        currentTable.markUpdated()
                                    },
                                    textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable(enabled = colIdx > 0) { currentTable.moveColumn(colIdx, colIdx - 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("◀", fontSize = 15.sp, color = if (colIdx > 0) Color.Black else Color.LightGray)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable(enabled = colIdx < currentTable.headers.size - 1) { currentTable.moveColumn(colIdx, colIdx + 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("▶", fontSize = 15.sp, color = if (colIdx < currentTable.headers.size - 1) Color.Black else Color.LightGray)
                                    }
                                    Box(
                                        modifier = Modifier.size(32.dp).clickable { currentTable.deleteColumn(colIdx) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✕", fontSize = 16.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Inline Column Filters
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

                    // Body Rows with Dynamic Multi-Cell Selection Highlighting
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(filteredRowIndices) { _, origRIdx ->
                            val rowData = currentTable.rows[origRIdx]
                            Row(
                                modifier = Modifier.fillMaxWidth().border(0.5.dp, Color(0xFFE0E0E0)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Row Actions
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
                                            .size(28.dp)
                                            .clickable(enabled = origRIdx > 0) { currentTable.moveRow(origRIdx, origRIdx - 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("▲", fontSize = 14.sp, color = if (origRIdx > 0) Color.Black else Color.LightGray)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clickable(enabled = origRIdx < currentTable.rows.size - 1) { currentTable.moveRow(origRIdx, origRIdx + 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("▼", fontSize = 14.sp, color = if (origRIdx < currentTable.rows.size - 1) Color.Black else Color.LightGray)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
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
                                            .size(28.dp)
                                            .clickable { currentTable.deleteRow(origRIdx) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✕", fontSize = 15.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                                    }

                                    BasicTextField(
                                        value = currentTable.rowNames.getOrElse(origRIdx) { "Row ${origRIdx + 1}" },
                                        onValueChange = {
                                            currentTable.rowNames[origRIdx] = it
                                            currentTable.markUpdated()
                                        },
                                        textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                // Cells
                                visibleColIndices.forEach { colIdx ->
                                    val cellCoord = Pair(origRIdx, colIdx)
                                    val cellValue = rowData.getOrElse(colIdx) { "" }
                                    val isSelected = selectedCells.contains(cellCoord)
                                    val isAnchor = anchorCell == cellCoord
                                    val isStagedForCut = cellClipboard?.isCut == true && cellClipboard?.sourceCoords?.contains(cellCoord) == true

                                    val cellBg = when {
                                        isStagedForCut -> Color(0xFFFFEBEE)
                                        isSelected && isMultiSelectMode -> Color(0xFFE1BEE7) // Light Purple
                                        isSelected -> Color(0xFFBBDEFB) // Light Blue
                                        else -> Color.White
                                    }

                                    val cellBorderColor = when {
                                        isAnchor -> Color(0xFF00E676) // Green Anchor Target
                                        isStagedForCut -> Color(0xFFD32F2F) // Red Cut Outline
                                        isSelected && isMultiSelectMode -> Color(0xFF7B1FA2) // Deep Purple
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
                                            .clickable {
                                                anchorCell = cellCoord
                                                if (isMultiSelectMode) {
                                                    if (selectedCells.contains(cellCoord)) {
                                                        selectedCells.remove(cellCoord)
                                                    } else {
                                                        selectedCells.add(cellCoord)
                                                    }
                                                } else {
                                                    selectedCells.clear()
                                                    selectedCells.add(cellCoord)
                                                }
                                            }
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
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isStagedForCut) {
                                                Text("✂", fontSize = 10.sp, color = Color.Red)
                                            } else if (isAnchor && isMultiSelectMode) {
                                                Text("⚓", fontSize = 10.sp, color = Color(0xFF2E7D32))
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
    }

    // Past Tables Drawer
    if (showTableHistoryDrawer) {
        AlertDialog(
            onDismissRequest = { showTableHistoryDrawer = false },
            title = { Text("Saved Tables by Date", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                    itemsIndexed(TableRepository.tables) { _, t ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            elevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
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
                                ) {
                                    Text("🗑", fontSize = 16.sp)
                                }
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
