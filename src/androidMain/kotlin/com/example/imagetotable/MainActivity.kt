package com.example.imagetotable

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.imagetotable.model.TableData
import com.example.imagetotable.ocr.AndroidOcrService
import com.example.imagetotable.ui.ImageCropperDialog
import com.example.imagetotable.ui.RowEditorDialog
import com.example.imagetotable.ui.TableCalendarView
import com.example.imagetotable.util.TableExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

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
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val tableData = remember {
        TableData(
            initialHeaders = listOf("SKU / Code", "Description", "Qty", "Price ($)"),
            initialRows = listOf(
                listOf("A-101", "Ballpoint Pens", "50", "1.25"),
                listOf("B-204", "A4 Paper Reams", "10", "4.50"),
                listOf("C-305", "Desk Organizer", "3", "12.00"),
                listOf("D-402", "USB Drive 64GB", "8", "7.99")
            ),
            initialRowNames = listOf("Item 1", "Item 2", "Item 3", "Item 4"),
            initialDates = listOf(
                LocalDate.now().toString(),
                LocalDate.now().toString(),
                LocalDate.now().minusDays(1).toString(),
                LocalDate.now().plusDays(1).toString()
            )
        )
    }

    var selectedCell by remember { mutableStateOf<Pair<Int, Int>?>(Pair(0, 0)) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready") }

    // Display & Layout Modifiers
    var isFullScreen by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    var showImagePreview by remember { mutableStateOf(false) }
    var showCropperDialog by remember { mutableStateOf(false) }
    var showColumnVisibilityDialog by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var activeExportFormat by remember { mutableStateOf(ExportFormat.PDF) }

    // Filtering State
    var rowSearchQuery by remember { mutableStateOf("") }
    var selectedCalendarDate by remember { mutableStateOf<String?>(null) }
    val hiddenColumns = remember { mutableStateListOf<Int>() }

    // Manual CRUD Entry Dialog State
    var activeEditingRowIndex by remember { mutableStateOf<Int?>(null) }
    var showRowEditorDialog by remember { mutableStateOf(false) }

    // Import CSV Launcher
    val csvImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val content = stream.bufferedReader().use { it.readText() }
                    tableData.importCsv(content)
                    statusMessage = "Imported CSV successfully!"
                }
            } catch (e: Exception) {
                statusMessage = "CSV Import Failed: ${e.message}"
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
                        ExportFormat.PDF -> TableExporter.exportToPdf(tableData, stream)
                        ExportFormat.EXCEL -> TableExporter.exportToCsv(tableData, stream)
                        ExportFormat.WORD -> TableExporter.exportToWordHtmlDoc(tableData, stream)
                    }
                }
                statusMessage = "Saved as ${activeExportFormat.extension.uppercase()}!"
            } catch (e: Exception) {
                statusMessage = "Save failed: ${e.message}"
            }
        }
    }

    // Photo Picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    selectedBitmap = BitmapFactory.decodeStream(stream)
                    showImagePreview = true
                    statusMessage = "Image ready. Crop or Extract."
                }
            } catch (e: Exception) {
                statusMessage = "Error: ${e.message}"
            }
        }
    }

    // Modals
    if (showCropperDialog && selectedBitmap != null) {
        ImageCropperDialog(
            sourceBitmap = selectedBitmap!!,
            onDismiss = { showCropperDialog = false },
            onCropConfirmed = { cropped ->
                selectedBitmap = cropped
                showCropperDialog = false
                statusMessage = "Image cropped successfully."
            }
        )
    }

    if (showRowEditorDialog) {
        val rIdx = activeEditingRowIndex
        RowEditorDialog(
            rowIndex = rIdx,
            initialName = if (rIdx != null) tableData.rowNames.getOrElse(rIdx) { "" } else "",
            initialDate = if (rIdx != null) tableData.rowDates.getOrElse(rIdx) { "" } else "",
            headers = tableData.headers,
            initialValues = if (rIdx != null) tableData.rows.getOrElse(rIdx) { emptyList() } else emptyList(),
            onDismiss = { showRowEditorDialog = false },
            onSave = { name, date, values ->
                if (rIdx != null) {
                    tableData.updateFullRow(rIdx, name, date, values)
                    statusMessage = "Updated Row #${rIdx + 1}"
                } else {
                    tableData.addManualRow(name, date, values)
                    statusMessage = "Created new row: $name"
                }
                showRowEditorDialog = false
            },
            onDelete = if (rIdx != null) {
                {
                    tableData.deleteRow(rIdx)
                    statusMessage = "Deleted Row #${rIdx + 1}"
                    showRowEditorDialog = false
                }
            } else null
        )
    }

    if (showColumnVisibilityDialog) {
        AlertDialog(
            onDismissRequest = { showColumnVisibilityDialog = false },
            title = { Text("Show / Hide Columns") },
            text = {
                Column {
                    tableData.headers.forEachIndexed { idx, h ->
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
                            Checkbox(
                                checked = !isHidden,
                                onCheckedChange = { check ->
                                    if (check) hiddenColumns.remove(idx) else hiddenColumns.add(idx)
                                }
                            )
                            Text(h)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColumnVisibilityDialog = false }) { Text("Close") }
            }
        )
    }

    // Filter computation
    val visibleColIndices = tableData.headers.indices.filter { !hiddenColumns.contains(it) }
    val filteredRowIndices = tableData.rows.indices.filter { rIdx ->
        val dateMatch = selectedCalendarDate == null || tableData.rowDates.getOrNull(rIdx) == selectedCalendarDate
        val nameMatch = tableData.rowNames.getOrElse(rIdx) { "" }.contains(rowSearchQuery, ignoreCase = true)
        val cellMatch = tableData.rows[rIdx].any { it.contains(rowSearchQuery, ignoreCase = true) }
        dateMatch && (rowSearchQuery.isBlank() || nameMatch || cellMatch)
    }

    val totalTableWidth = 160.dp + (130.dp * visibleColIndices.size)

    Scaffold(
        topBar = {
            if (!isFullScreen) {
                TopAppBar(
                    title = { Text("ImageToTable", fontSize = 18.sp) },
                    backgroundColor = Color(0xFF1E88E5),
                    contentColor = Color.White,
                    actions = {
                        IconButton(onClick = { showCalendar = !showCalendar }) {
                            Text(if (showCalendar) "🗓 Hide" else "🗓 Cal", color = Color.White, fontSize = 12.sp)
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
            // Full Screen Exit Toolbar
            if (isFullScreen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Full Screen Mode", fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                    Button(
                        onClick = { isFullScreen = false },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                    ) {
                        Text("Exit Full Screen ✕", color = Color.White, fontSize = 11.sp)
                    }
                }
            }

            // Main Toolbar (Hidden in full-screen)
            if (!isFullScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Text("Pick Img", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { showCropperDialog = true },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFF57C00)),
                        enabled = selectedBitmap != null && !isProcessing
                    ) {
                        Text("Crop", color = Color.White, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            val bitmap = selectedBitmap
                            if (bitmap != null) {
                                coroutineScope.launch {
                                    isProcessing = true
                                    statusMessage = "Running OCR..."
                                    try {
                                        val service = AndroidOcrService(context) { msg -> statusMessage = msg }
                                        val (h, r) = service.extractTable(bitmap)
                                        withContext(Dispatchers.Main) {
                                            tableData.loadExtractedData(h, r)
                                            statusMessage = "Extracted ${r.size} rows & ${h.size} cols!"
                                        }
                                    } catch (e: Exception) {
                                        statusMessage = "OCR Failed: ${e.message}"
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                        enabled = selectedBitmap != null && !isProcessing
                    ) {
                        Text("Extract", color = Color.White, fontSize = 12.sp)
                    }

                    // Import CSV
                    Button(
                        onClick = { csvImportLauncher.launch("text/*") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                    ) {
                        Text("Import CSV", color = Color.White, fontSize = 12.sp)
                    }

                    // Manual Add Dialog Launcher
                    Button(
                        onClick = {
                            activeEditingRowIndex = null
                            showRowEditorDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3949AB))
                    ) {
                        Text("+ New Entry", color = Color.White, fontSize = 12.sp)
                    }

                    // Column Visibility Filter Button
                    Button(onClick = { showColumnVisibilityDialog = true }) {
                        Text("Cols Filter (${tableData.headers.size - hiddenColumns.size})", fontSize = 12.sp)
                    }

                    // Export Menu
                    Box {
                        Button(
                            onClick = { showExportMenu = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A))
                        ) {
                            Text("Export ▼", color = Color.White, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.PDF
                                fileSaveLauncher.launch("Export.pdf")
                            }) { Text("Export as PDF (.pdf)") }
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.EXCEL
                                fileSaveLauncher.launch("Export.csv")
                            }) { Text("Export as Excel (.csv)") }
                            DropdownMenuItem(onClick = {
                                showExportMenu = false
                                activeExportFormat = ExportFormat.WORD
                                fileSaveLauncher.launch("Export.doc")
                            }) { Text("Export as Word (.doc)") }
                        }
                    }
                }

                // Row Search Filter Bar
                OutlinedTextField(
                    value = rowSearchQuery,
                    onValueChange = { rowSearchQuery = it },
                    placeholder = { Text("Filter rows by text, title or values...") },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    textStyle = TextStyle(fontSize = 12.sp),
                    singleLine = true
                )
            }

            // Collapsible In-App Calendar
            if (showCalendar && !isFullScreen) {
                TableCalendarView(
                    activeDates = tableData.rowDates.toSet(),
                    selectedDate = selectedCalendarDate,
                    onDateSelected = { selectedCalendarDate = it }
                )
            }

            // Image Preview Collapsible
            if (showImagePreview && selectedBitmap != null && !isFullScreen) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    elevation = 3.dp,
                    modifier = Modifier.fillMaxWidth().height(140.dp).padding(vertical = 4.dp)
                ) {
                    Image(bitmap = selectedBitmap!!.asImageBitmap(), contentDescription = "Preview", modifier = Modifier.fillMaxSize())
                }
            }

            // Status Bar
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = "${filteredRowIndices.size} of ${tableData.rows.size} rows visible • $statusMessage",
                    style = TextStyle(fontSize = 11.sp, color = Color.DarkGray)
                )
            }

            Divider()

            // Main Interactive Table Canvas
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
                        Box(modifier = Modifier.width(160.dp).padding(4.dp), contentAlignment = Alignment.Center) {
                            Text("Title & Date", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        visibleColIndices.forEach { colIdx ->
                            val headerText = tableData.headers[colIdx]
                            Column(
                                modifier = Modifier
                                    .width(130.dp)
                                    .border(0.5.dp, Color.LightGray)
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicTextField(
                                    value = headerText,
                                    onValueChange = { tableData.headers[colIdx] = it },
                                    textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text("◀", modifier = Modifier.clickable(enabled = colIdx > 0) { tableData.moveColumn(colIdx, colIdx - 1) }, fontSize = 12.sp)
                                    Text("▶", modifier = Modifier.clickable(enabled = colIdx < tableData.headers.size - 1) { tableData.moveColumn(colIdx, colIdx + 1) }, fontSize = 12.sp)
                                    Text("✕", modifier = Modifier.clickable { tableData.deleteColumn(colIdx) }, color = Color.Red, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Body Rows
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(filteredRowIndices) { _, originalRowIdx ->
                            val rowData = tableData.rows[originalRowIdx]
                            Row(
                                modifier = Modifier.fillMaxWidth().border(0.5.dp, Color(0xFFE0E0E0)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Row Title, Date & Action Panel
                                Row(
                                    modifier = Modifier
                                        .width(160.dp)
                                        .background(Color(0xFFF9FAFB))
                                        .padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text("▲", modifier = Modifier.clickable(enabled = originalRowIdx > 0) { tableData.moveRow(originalRowIdx, originalRowIdx - 1) }, fontSize = 11.sp)
                                    Text("▼", modifier = Modifier.clickable(enabled = originalRowIdx < tableData.rows.size - 1) { tableData.moveRow(originalRowIdx, originalRowIdx + 1) }, fontSize = 11.sp)

                                    // Open Dedicated CRUD Editor Modal
                                    Text(
                                        text = "✎",
                                        modifier = Modifier
                                            .clickable {
                                                activeEditingRowIndex = originalRowIdx
                                                showRowEditorDialog = true
                                            }
                                            .padding(horizontal = 2.dp),
                                        color = Color(0xFF1E88E5),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        BasicTextField(
                                            value = tableData.rowNames.getOrElse(originalRowIdx) { "Row ${originalRowIdx + 1}" },
                                            onValueChange = { tableData.updateRowName(originalRowIdx, it) },
                                            textStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        )
                                        BasicTextField(
                                            value = tableData.rowDates.getOrElse(originalRowIdx) { "" },
                                            onValueChange = { tableData.updateRowDate(originalRowIdx, it) },
                                            textStyle = TextStyle(fontSize = 9.sp, color = Color.Gray)
                                        )
                                    }
                                }

                                // Cells for visible columns
                                visibleColIndices.forEach { colIdx ->
                                    val cellValue = rowData.getOrElse(colIdx) { "" }
                                    val isSelected = selectedCell == Pair(originalRowIdx, colIdx)
                                    Box(
                                        modifier = Modifier
                                            .width(130.dp)
                                            .border(
                                                width = if (isSelected) 1.5.dp else 0.5.dp,
                                                color = if (isSelected) Color(0xFF1E88E5) else Color.LightGray
                                            )
                                            .background(if (isSelected) Color(0xFFE3F2FD) else Color.White)
                                            .clickable { selectedCell = Pair(originalRowIdx, colIdx) }
                                            .padding(8.dp)
                                    ) {
                                        BasicTextField(
                                            value = cellValue,
                                            onValueChange = { tableData.updateCell(originalRowIdx, colIdx, it) },
                                            textStyle = TextStyle(fontSize = 12.sp),
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
}
