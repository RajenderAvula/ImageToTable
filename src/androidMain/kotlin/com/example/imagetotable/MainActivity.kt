package com.example.imagetotable

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items          // <-- ADD THIS IMPORT
import androidx.compose.foundation.lazy.itemsIndexed
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.imagetotable.model.*
import com.example.imagetotable.ocr.AndroidOcrService
import com.example.imagetotable.ui.AdvancedRowEditorDialog
import com.example.imagetotable.ui.FullScreenCropperDialog
import com.example.imagetotable.util.CalendarPickerUtil
import com.example.imagetotable.util.TableExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // Default Seed Table
    val initialTable = remember {
        TableData(
            initialName = "Inventory Log",
            initialHeaders = listOf(
                ColumnDef("SKU / Code", ColumnType.TEXT),
                ColumnDef("Description", ColumnType.TEXT),
                ColumnDef("Quantity", ColumnType.NUMBER),
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
    var selectedCell by remember { mutableStateOf<Pair<Int, Int>?>(Pair(0, 0)) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready") }

    // Dialog & Screen Controls
    var isFullScreen by remember { mutableStateOf(false) }
    var showCropperDialog by remember { mutableStateOf(false) }
    var showColumnFilterDialog by remember { mutableStateOf(false) }
    var showTableHistoryDrawer by remember { mutableStateOf(false) }
    var showRowEditorDialog by remember { mutableStateOf(false) }
    var editingRowIndex by remember { mutableIntStateOf(0) }

    // Filtering State
    var rowSearchQuery by remember { mutableStateOf("") }
    val hiddenColumns = remember { mutableStateListOf<Int>() }

    // System Image Picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                selectedBitmap = BitmapFactory.decodeStream(stream)
                showCropperDialog = true // Open full screen cropper immediately
            }
        }
    }

    // Full-Screen Drag Cropper
    if (showCropperDialog && selectedBitmap != null) {
        FullScreenCropperDialog(
            sourceBitmap = selectedBitmap!!,
            onDismiss = { showCropperDialog = false },
            onCropConfirmed = { cropped ->
                selectedBitmap = cropped
                showCropperDialog = false
                // Trigger OCR on confirmed crop
                coroutineScope.launch {
                    isProcessing = true
                    statusMessage = "Extracting table..."
                    try {
                        val service = AndroidOcrService(context) { msg -> statusMessage = msg }
                        val (h, r) = service.extractTable(cropped)
                        withContext(Dispatchers.Main) {
                            currentTable.loadExtractedData(h, r)
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

    // Column Visibility Modal
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

    // Filter computation
    val visibleColIndices = currentTable.headers.indices.filter { !hiddenColumns.contains(it) }
    val filteredRowIndices = currentTable.rows.indices.filter { rIdx ->
        val nameMatch = currentTable.rowNames.getOrElse(rIdx) { "" }.contains(rowSearchQuery, ignoreCase = true)
        val cellMatch = currentTable.rows[rIdx].any { it.contains(rowSearchQuery, ignoreCase = true) }
        rowSearchQuery.isBlank() || nameMatch || cellMatch
    }

    // Dynamic Sizing (Enlarged touch targets & fonts)
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
                        IconButton(onClick = { showTableHistoryDrawer = true }) {
                            Text("📂 Tables", color = Color.White, fontSize = 12.sp)
                        }
                        IconButton(onClick = { isFullScreen = true }) {
                            Text("⛶ Full", color = Color.White, fontSize = 12.sp)
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullScreen) 4.dp else paddingValues.calculateBottomPadding() + 8.dp)
        ) {
            if (isFullScreen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${currentTable.tableName} • 📅 ${currentTable.tableDateTime}", fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5), fontSize = 14.sp)
                    Button(onClick = { isFullScreen = false }) { Text("Exit Full Screen ✕", fontSize = 12.sp) }
                }
            }

            if (!isFullScreen) {
                // Primary Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                    ) {
                        Text("📷 Pick & Crop", color = Color.White, fontSize = 13.sp)
                    }

                    // SWAP AXES / TRANSPOSE BUTTON (Fixes row-to-column inversion)
                    Button(
                        onClick = { currentTable.transposeTable() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100))
                    ) {
                        Text("⇄ Transpose", color = Color.White, fontSize = 13.sp)
                    }

                    // Native Calendar & Clock Picker
                    Button(
                        onClick = {
                            CalendarPickerUtil.pickDateTime(context) { newDateTime ->
                                currentTable.tableDateTime = newDateTime
                                TableRepository.saveOrUpdate(currentTable)
                                statusMessage = "Bound table to: $newDateTime"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                    ) {
                        Text("📅 Pick Date/Time", color = Color.White, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            val newTable = TableData(
                                initialName = "Table ${TableRepository.tables.size + 1}",
                                initialHeaders = listOf(ColumnDef("Col 1"), ColumnDef("Col 2")),
                                initialRows = listOf(listOf("", ""), listOf("", ""))
                            )
                            TableRepository.saveOrUpdate(newTable)
                            currentTable = newTable
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1))
                    ) {
                        Text("+ New Table", color = Color.White, fontSize = 13.sp)
                    }

                    // Create Sub-Table from Current Filters
                    Button(
                        onClick = {
                            val subTable = currentTable.createSubTable(
                                newTableName = "${currentTable.tableName} (Filtered)",
                                selectedRowIndices = filteredRowIndices,
                                selectedColIndices = visibleColIndices
                            )
                            TableRepository.saveOrUpdate(subTable)
                            currentTable = subTable
                            statusMessage = "Created new table from filtered view!"
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00ACC1))
                    ) {
                        Text("Save View as New", color = Color.White, fontSize = 13.sp)
                    }

                    Button(onClick = { currentTable.addRow() }) { Text("+ Row", fontSize = 13.sp) }
                    Button(onClick = { currentTable.addColumn("Col ${currentTable.headers.size + 1}") }) { Text("+ Col", fontSize = 13.sp) }
                    Button(onClick = { showColumnFilterDialog = true }) { Text("Filter Cols (${visibleColIndices.size})", fontSize = 13.sp) }
                }

                // Search & Filter Row
                OutlinedTextField(
                    value = rowSearchQuery,
                    onValueChange = { rowSearchQuery = it },
                    placeholder = { Text("Filter rows by contents...") },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    textStyle = TextStyle(fontSize = 14.sp),
                    singleLine = true
                )

                // INDEPENDENT CELL SHIFT TOOLBAR (Move selected cell anywhere)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (selR, selC) = selectedCell ?: Pair(-1, -1)
                    Text("Shift Active Cell:", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    Button(
                        onClick = { currentTable.shiftIndividualCell(selR, selC, ShiftDirection.LEFT) },
                        enabled = selR >= 0 && selC > 0,
                        modifier = Modifier.size(width = 44.dp, height = 36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("◀", fontSize = 14.sp) }

                    Button(
                        onClick = { currentTable.shiftIndividualCell(selR, selC, ShiftDirection.RIGHT) },
                        enabled = selR >= 0 && selC < currentTable.headers.size - 1,
                        modifier = Modifier.size(width = 44.dp, height = 36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("▶", fontSize = 14.sp) }

                    Button(
                        onClick = { currentTable.shiftIndividualCell(selR, selC, ShiftDirection.UP) },
                        enabled = selR > 0 && selC >= 0,
                        modifier = Modifier.size(width = 44.dp, height = 36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("▲", fontSize = 14.sp) }

                    Button(
                        onClick = { currentTable.shiftIndividualCell(selR, selC, ShiftDirection.DOWN) },
                        enabled = selR in 0 until currentTable.rows.size - 1 && selC >= 0,
                        modifier = Modifier.size(width = 44.dp, height = 36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("▼", fontSize = 14.sp) }
                }
            }

            // Status Bar
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = "${filteredRowIndices.size} of ${currentTable.rows.size} rows • $statusMessage",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )
            }

            Divider()

            // MAIN INTERACTIVE TABLE CANVAS
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Column(modifier = Modifier.width(totalTableWidth).fillMaxHeight()) {
                    // Header Row (Editable names + sideways shifts)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE8EEF5))
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(actionColWidth).padding(4.dp), contentAlignment = Alignment.Center) {
                            Text("Row Controls", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        visibleColIndices.forEach { colIdx ->
                            val colDef = currentTable.headers[colIdx]
                            Column(
                                modifier = Modifier
                                    .width(dataColWidth)
                                    .border(0.5.dp, Color.LightGray)
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicTextField(
                                    value = colDef.name,
                                    onValueChange = {
                                        colDef.name = it
                                        currentTable.markUpdated()
                                    },
                                    textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(colDef.type.label, fontSize = 10.sp, color = Color.Gray)

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    // Enlarged column movement touch targets
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clickable(enabled = colIdx > 0) { currentTable.moveColumn(colIdx, colIdx - 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("◀", fontSize = 16.sp, color = if (colIdx > 0) Color.Black else Color.LightGray)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clickable(enabled = colIdx < currentTable.headers.size - 1) { currentTable.moveColumn(colIdx, colIdx + 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("▶", fontSize = 16.sp, color = if (colIdx < currentTable.headers.size - 1) Color.Black else Color.LightGray)
                                    }
                                    Box(
                                        modifier = Modifier.size(36.dp).clickable { currentTable.deleteColumn(colIdx) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✕", fontSize = 16.sp, color = Color.Red)
                                    }
                                }
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
                                // Row Title & Quick Actions
                                Row(
                                    modifier = Modifier
                                        .width(actionColWidth)
                                        .background(Color(0xFFF9FAFB))
                                        .padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Enlarged row movement buttons
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable(enabled = origRIdx > 0) { currentTable.moveRow(origRIdx, origRIdx - 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("▲", fontSize = 16.sp, color = if (origRIdx > 0) Color.Black else Color.LightGray)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable(enabled = origRIdx < currentTable.rows.size - 1) { currentTable.moveRow(origRIdx, origRIdx + 1) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("▼", fontSize = 16.sp, color = if (origRIdx < currentTable.rows.size - 1) Color.Black else Color.LightGray)
                                    }

                                    // Open Advanced Multi-Column Row Editor Dialog
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clickable {
                                                editingRowIndex = origRIdx
                                                showRowEditorDialog = true
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✎", fontSize = 16.sp, color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
                                    }

                                    BasicTextField(
                                        value = currentTable.rowNames.getOrElse(origRIdx) { "Row ${origRIdx + 1}" },
                                        onValueChange = {
                                            currentTable.rowNames[origRIdx] = it
                                            currentTable.markUpdated()
                                        },
                                        textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                // Interactive Cells
                                visibleColIndices.forEach { colIdx ->
                                    val cellValue = rowData.getOrElse(colIdx) { "" }
                                    val isSelected = selectedCell == Pair(origRIdx, colIdx)
                                    Box(
                                        modifier = Modifier
                                            .width(dataColWidth)
                                            .border(
                                                width = if (isSelected) 2.dp else 0.5.dp,
                                                color = if (isSelected) Color(0xFF1E88E5) else Color.LightGray
                                            )
                                            .background(if (isSelected) Color(0xFFE3F2FD) else Color.White)
                                            .clickable { selectedCell = Pair(origRIdx, colIdx) }
                                            .padding(10.dp)
                                    ) {
                                        BasicTextField(
                                            value = cellValue,
                                            onValueChange = { currentTable.updateCell(origRIdx, colIdx, it) },
                                            textStyle = TextStyle(fontSize = 14.sp),
                                            modifier = Modifier.fillMaxWidth()
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

    // MULTI-TABLE RETRIEVAL DRAWER / MODAL
    if (showTableHistoryDrawer) {
        AlertDialog(
            onDismissRequest = { showTableHistoryDrawer = false },
            title = { Text("Saved Tables by Date", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                    items(TableRepository.tables) { t ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    currentTable = t
                                    showTableHistoryDrawer = false
                                    statusMessage = "Loaded: ${t.tableName}"
                                },
                            elevation = 2.dp
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(t.tableName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("📅 ${t.tableDateTime} • ${t.rows.size} rows", fontSize = 11.sp, color = Color.Gray)
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
