package com.example.imagetotable

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.hypot

enum class ExportFormat(val extension: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    EXCEL("csv", "text/csv"),
    WORD("doc", "application/msword")
}

fun parseDateFromHeader(header: String): Date? {
    val formats = listOf(
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()),
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
        SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()),
        SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()),
        SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    )
    for (sdf in formats) {
        try {
            return sdf.parse(header.trim())
        } catch (_: Exception) {}
    }
    return null
}

fun isAttendancePresent(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.equals("P", ignoreCase = true) ||
           trimmed.equals("Present", ignoreCase = true) ||
           trimmed.equals("1", ignoreCase = true) ||
           trimmed.equals("Yes", ignoreCase = true)
}

fun isAttendanceAbsent(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.equals("A", ignoreCase = true) ||
           trimmed.equals("Absent", ignoreCase = true) ||
           trimmed.equals("0", ignoreCase = true) ||
           trimmed.equals("No", ignoreCase = true)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TableRepository.init(applicationContext)
        setContent {
            MaterialTheme {
                MobileTableEditorScreen()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileTableEditorScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val initialTable = remember {
        TableRepository.tables.firstOrNull() ?: TableData(
            initialName = "Invoice & Attendance",
            initialHeaders = listOf(
                ColumnDef("Employee / SKU", ColumnType.TEXT),
                ColumnDef("Department", ColumnType.TEXT),
                ColumnDef("2026-09-25", ColumnType.DATE),
                ColumnDef("2026-09-26", ColumnType.DATE)
            ),
            initialRows = listOf(
                listOf("John Doe", "Engineering", "Present", "Present"),
                listOf("Jane Smith", "Design", "Present", "Absent"),
                listOf("Robert Lee", "Marketing", "Absent", "Present"),
                listOf("Alice Wong", "Engineering", "Present", "Present")
            ),
            initialCorner = "ID / #"
        ).also { TableRepository.saveOrUpdate(it) }
    }

    var currentTable by remember { mutableStateOf(initialTable) }
    var tableSnapshot by remember { mutableStateOf(initialTable.createSnapshot()) }

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // Mode Switcher: View Mode vs Edit Mode on Home UI
    var isViewMode by remember { mutableStateOf(false) }

    // Magnification Zoom Scale (controls font sizes and cell dimensions)
    var tableZoomScale by remember { mutableFloatStateOf(1.0f) }

    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedCells = remember { mutableStateListOf<Pair<Int, Int>>(Pair(0, 0)) }
    var anchorCell by remember { mutableStateOf(Pair(0, 0)) }
    var cellClipboard by remember { mutableStateOf<CellClipboard?>(null) }

    // Multi-edit batch value dialog state
    var showBatchEditDialog by remember { mutableStateOf(false) }
    var batchEditText by remember { mutableStateOf("") }

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val detectedWords = remember { mutableStateListOf<String>() }
    val selectedTokens = remember { mutableStateListOf<String>() }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready") }

    var isFullScreen by remember { mutableStateOf(false) }
    var showScanWorkspaceInTable by remember { mutableStateOf(false) }
    var showAttendanceChart by remember { mutableStateOf(true) }

    val hiddenChartDateIndices = remember { mutableStateListOf<Int>() }
    var showChartDateSelectorDialog by remember { mutableStateOf(false) }

    // Dialog Visibilities
    var showCropperDialog by remember { mutableStateOf(false) }
    var cropperTargetPageIndex by remember { mutableStateOf<Int?>(null) }
    var showDedicatedEditor by remember { mutableStateOf(false) }
    var activeAttachmentCellCoord by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showNewTableDialog by remember { mutableStateOf(false) }
    var showAllWordsDialog by remember { mutableStateOf(false) }
    var showTableHistoryDrawer by remember { mutableStateOf(false) }
    var tablePendingDelete by remember { mutableStateOf<TableData?>(null) }
    var showClearTableConfirm by remember { mutableStateOf(false) }
    var showRowEditorDialog by remember { mutableStateOf(false) }
    var editingRowIndex by remember { mutableIntStateOf(0) }
    var showAddDateColumnDialog by remember { mutableStateOf(false) }

    // Deletion confirmation states for table rows & columns
    var colPendingDeleteIdx by remember { mutableStateOf<Int?>(null) }
    var rowPendingDeleteIdx by remember { mutableStateOf<Int?>(null) }

    // Formula Builder Dialog State
    var showFormulaBuilderDialog by remember { mutableStateOf(false) }
    var formulaEditingColIndex by remember { mutableIntStateOf(-1) }
    var formulaInitialColName by remember { mutableStateOf("Total") }
    var formulaInitialExpression by remember { mutableStateOf("") }

    // Custom Row and Column Show/Hide Dialog States
    var showColumnVisibilityDialog by remember { mutableStateOf(false) }
    var showRowVisibilityDialog by remember { mutableStateOf(false) }
    val hiddenColumns = remember { mutableStateListOf<Int>() }
    val hiddenRows = remember { mutableStateListOf<Int>() }

    var activeFilterColIdx by remember { mutableStateOf<Int?>(null) }

    var showGeneratedPdfInspector by remember { mutableStateOf(false) }
    var generatedPdfSizeBytes by remember { mutableLongStateOf(0L) }

    var showExtractionPreviewDialog by remember { mutableStateOf(false) }
    var previewCroppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingExtractedHeaders by remember { mutableStateOf<List<ColumnDef>>(emptyList()) }
    var pendingExtractedRows by remember { mutableStateOf<List<List<String>>>(emptyList()) }

    var activeExportFormat by remember { mutableStateOf(ExportFormat.PDF) }

    var globalSearchQuery by remember { mutableStateOf("") }
    var drawerSearchQuery by remember { mutableStateOf("") }
    val columnValueFilters = remember { mutableStateMapOf<Int, Set<String>>() }

    val pdfPages = remember { mutableStateListOf<PdfPageItem>() }
    var cacheSizeText by remember { mutableStateOf(CacheManager.getFormattedCacheSize(context)) }

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    // Accurate Index Remapping Helpers (Protects hidden rows/columns from becoming unhidden on deletion)
    fun deleteColumnAndRemapIndices(cIdx: Int) {
        if (cIdx !in currentTable.headers.indices || currentTable.headers.size <= 1) return

        currentTable.deleteColumn(cIdx)

        val newHiddenCols = hiddenColumns
            .filter { it != cIdx }
            .map { if (it > cIdx) it - 1 else it }
            .distinct()
        hiddenColumns.clear()
        hiddenColumns.addAll(newHiddenCols)

        val newHiddenChartDates = hiddenChartDateIndices
            .filter { it != cIdx }
            .map { if (it > cIdx) it - 1 else it }
            .distinct()
        hiddenChartDateIndices.clear()
        hiddenChartDateIndices.addAll(newHiddenChartDates)

        val newColFilters = mutableMapOf<Int, Set<String>>()
        columnValueFilters.forEach { (key, set) ->
            if (key != cIdx) {
                val newKey = if (key > cIdx) key - 1 else key
                newColFilters[newKey] = set
            }
        }
        columnValueFilters.clear()
        columnValueFilters.putAll(newColFilters)

        val newSelected = selectedCells.mapNotNull { (r, c) ->
            when {
                c == cIdx -> null
                c > cIdx -> Pair(r, c - 1)
                else -> Pair(r, c)
            }
        }
        selectedCells.clear()
        selectedCells.addAll(newSelected)
        if (anchorCell.second == cIdx) {
            anchorCell = Pair(anchorCell.first, 0.coerceAtMost(currentTable.headers.size - 1))
        } else if (anchorCell.second > cIdx) {
            anchorCell = Pair(anchorCell.first, anchorCell.second - 1)
        }

        currentTable.markUpdated()
        TableRepository.saveOrUpdate(currentTable)
        tableSnapshot = currentTable.createSnapshot()
    }

    fun deleteRowAndRemapIndices(rIdx: Int) {
        if (rIdx !in currentTable.rows.indices) return

        currentTable.deleteRow(rIdx)

        val newHiddenRows = hiddenRows
            .filter { it != rIdx }
            .map { if (it > rIdx) it - 1 else it }
            .distinct()
        hiddenRows.clear()
        hiddenRows.addAll(newHiddenRows)

        val newSelected = selectedCells.mapNotNull { (r, c) ->
            when {
                r == rIdx -> null
                r > rIdx -> Pair(r - 1, c)
                else -> Pair(r, c)
            }
        }
        selectedCells.clear()
        selectedCells.addAll(newSelected)
        if (anchorCell.first == rIdx) {
            anchorCell = Pair(0.coerceAtMost(currentTable.rows.size - 1), anchorCell.second)
        } else if (anchorCell.first > rIdx) {
            anchorCell = Pair(anchorCell.first - 1, anchorCell.second)
        }

        currentTable.markUpdated()
        TableRepository.saveOrUpdate(currentTable)
        tableSnapshot = currentTable.createSnapshot()
    }

    // Synchronized Save and Cancel Across All UIs
    fun performSynchronizedSave() {
        currentTable.recomputeFormulas()
        currentTable.markUpdated()
        TableRepository.saveOrUpdate(currentTable)
        tableSnapshot = currentTable.createSnapshot()
        statusMessage = "All changes saved & synchronized across all UIs!"
        Toast.makeText(context, "Saved & Synchronized", Toast.LENGTH_SHORT).show()
    }

    fun performSynchronizedCancel() {
        currentTable.tableName = tableSnapshot.tableName
        currentTable.tableDateTime = tableSnapshot.tableDateTime
        currentTable.cornerHeader = tableSnapshot.cornerHeader
        currentTable.headers.clear()
        currentTable.headers.addAll(tableSnapshot.headers.map { it.copy() })
        currentTable.rowNames.clear()
        currentTable.rowNames.addAll(tableSnapshot.rowNames)
        currentTable.rows.clear()
        tableSnapshot.rows.forEach { r ->
            currentTable.rows.add(mutableStateListOf(*r.toTypedArray()))
        }
        currentTable.recomputeFormulas()
        currentTable.markUpdated()
        TableRepository.saveOrUpdate(currentTable)
        statusMessage = "Reverted changes to last saved state."
        Toast.makeText(context, "Changes reverted", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(previewCroppedBitmap) {
        zoomScale = 1f
        panOffsetX = 0f
        panOffsetY = 0f
    }

    fun openDatePickerForCell(rIdx: Int, cIdx: Int) {
        val cal = Calendar.getInstance()
        val currentRaw = currentTable.rows.getOrNull(rIdx)?.getOrNull(cIdx) ?: ""
        val currentVal = CellAttachmentHelper.parseCellContent(currentRaw).displayText
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val parsed = sdf.parse(currentVal)
            if (parsed != null) cal.time = parsed
        } catch (_: Exception) {
            try {
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val parsed = sdfDate.parse(currentVal)
                if (parsed != null) cal.time = parsed
            } catch (_: Exception) {}
        }

        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)

                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        cal.set(Calendar.MINUTE, minute)
                        val outFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        val formattedDate = outFormat.format(cal.time)
                        val existingPayload = CellAttachmentHelper.parseCellContent(currentRaw)
                        val updatedEncoded = CellAttachmentHelper.formatCellContent(
                            displayText = formattedDate,
                            attachments = existingPayload.attachments,
                            note = existingPayload.note,
                            additionalNote = existingPayload.additionalNote,
                            checklists = existingPayload.checklists
                        )
                        currentTable.setCellValue(rIdx, cIdx, updatedEncoded)
                        currentTable.markUpdated()
                        TableRepository.saveOrUpdate(currentTable)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun executeDeleteTable(targetTableId: String) {
        TableRepository.deleteTable(targetTableId)
        if (currentTable.id == targetTableId) {
            val next = TableRepository.tables.firstOrNull() ?: TableData(
                initialName = "New Table",
                initialHeaders = listOf(ColumnDef("Col 1", ColumnType.TEXT), ColumnDef("Col 2", ColumnType.TEXT)),
                initialRows = listOf(listOf("", ""), listOf("", "")),
                initialCorner = "ID / #"
            ).also { TableRepository.saveOrUpdate(it) }

            currentTable = next
            tableSnapshot = next.createSnapshot()
            columnValueFilters.clear()
            hiddenColumns.clear()
            hiddenRows.clear()
            hiddenChartDateIndices.clear()
            globalSearchQuery = ""
            selectedCells.clear()
            selectedCells.add(Pair(0, 0))
            anchorCell = Pair(0, 0)
            detectedWords.clear()
            selectedTokens.clear()
            selectedBitmap = null
            previewCroppedBitmap = null
            cellClipboard = null
            showDedicatedEditor = false
            showRowEditorDialog = false
            showExtractionPreviewDialog = false
            showCropperDialog = false
        } else {
            tableSnapshot = currentTable.createSnapshot()
        }
        statusMessage = "Table deleted successfully."
    }

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
            currentTable.markUpdated()
            TableRepository.saveOrUpdate(currentTable)
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
                    currentTable.recomputeFormulas()
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
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
                previewCroppedBitmap = null
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

    val pdfPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                statusMessage = "Importing pages from PDF..."
                try {
                    val count = withContext(Dispatchers.IO) {
                        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                            ?: throw IllegalArgumentException("Could not open file descriptor for PDF.")
                        val renderer = PdfRenderer(pfd)
                        val total = renderer.pageCount
                        val newPages = mutableListOf<PdfPageItem>()

                        for (i in 0 until total) {
                            val page = renderer.openPage(i)
                            val scale = 2f
                            val w = (page.width * scale).toInt().coerceAtMost(2480)
                            val h = (page.height * scale).toInt().coerceAtMost(3508)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bmp)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            newPages.add(PdfPageItem(bitmap = bmp))
                        }

                        renderer.close()
                        pfd.close()

                        withContext(Dispatchers.Main) {
                            pdfPages.addAll(newPages)
                        }
                        total
                    }
                    statusMessage = "Imported $count page(s) from PDF! Ready to compress/edit."
                } catch (e: Exception) {
                    statusMessage = "PDF Import error: ${e.message}"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

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
                    previewCroppedBitmap = cropped
                    coroutineScope.launch {
                        isProcessing = true
                        try {
                            val service = AndroidOcrService(context) { msg -> statusMessage = msg }
                            if (mode == CropExtractionMode.SINGLE_CELL_STEP) {
                                val (h, r) = service.extractTable(cropped)
                                val text = (h + r.flatten()).filter { it.isNotBlank() }.joinToString(" ")
                                val (tr, tc) = anchorCell
                                withContext(Dispatchers.Main) {
                                    val currentRaw = currentTable.rows.getOrNull(tr)?.getOrNull(tc) ?: ""
                                    val payload = CellAttachmentHelper.parseCellContent(currentRaw)
                                    val updatedEncoded = CellAttachmentHelper.formatCellContent(
                                        displayText = text,
                                        attachments = payload.attachments,
                                        note = payload.note,
                                        additionalNote = payload.additionalNote,
                                        checklists = payload.checklists
                                    )
                                    currentTable.setCellValue(tr, tc, updatedEncoded)
                                    currentTable.markUpdated()
                                    TableRepository.saveOrUpdate(currentTable)
                                    tableSnapshot = currentTable.createSnapshot()
                                    statusMessage = "Inserted '$text' into active cell ($tr, $tc)"
                                }
                            } else {
                                val (h, r) = service.extractTable(cropped)
                                withContext(Dispatchers.Main) {
                                    detectedWords.clear()
                                    h.filter { it.isNotBlank() }.forEach { detectedWords.add(it) }
                                    r.flatten().filter { it.isNotBlank() }.forEach { detectedWords.add(it) }

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
                currentTable.recomputeFormulas()
                currentTable.markUpdated()
                TableRepository.saveOrUpdate(currentTable)
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
                currentTable.recomputeFormulas()
                currentTable.markUpdated()
                TableRepository.saveOrUpdate(currentTable)
                tableSnapshot = currentTable.createSnapshot()
                columnValueFilters.clear()
                showExtractionPreviewDialog = false
                statusMessage = "Replaced table with ${verifiedRows.size} verified rows!"
            }
        )
    }

    if (showDedicatedEditor) {
        key(currentTable.id) {
            DedicatedTableEditorDialog(
                tableData = currentTable,
                onDismiss = { showDedicatedEditor = false },
                onSave = {
                    currentTable.recomputeFormulas()
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                    showDedicatedEditor = false
                    statusMessage = "Dedicated table edits saved & synchronized!"
                }
            )
        }
    }

    // CELL ATTACHMENT, NOTES & CHECKLISTS DIALOG
    if (activeAttachmentCellCoord != null) {
        val (r, c) = activeAttachmentCellCoord!!
        val rawCell = currentTable.rows.getOrNull(r)?.getOrNull(c) ?: ""
        val payload = CellAttachmentHelper.parseCellContent(rawCell)
        val colName = currentTable.headers.getOrNull(c)?.name ?: "Col ${c + 1}"

        CellAttachmentDialog(
            rowIndex = r,
            columnIndex = c,
            columnName = colName,
            initialText = payload.displayText,
            initialNote = payload.note,
            initialAdditionalNote = payload.additionalNote,
            initialAttachments = payload.attachments,
            initialChecklists = payload.checklists,
            onDismiss = { activeAttachmentCellCoord = null },
            onSave = { updatedText, updatedNote, updatedAdditionalNote, updatedAttachments, updatedChecklists ->
                val encoded = CellAttachmentHelper.formatCellContent(
                    displayText = updatedText,
                    attachments = updatedAttachments,
                    note = updatedNote,
                    additionalNote = updatedAdditionalNote,
                    checklists = updatedChecklists
                )
                currentTable.setCellValue(r, c, encoded)
                currentTable.markUpdated()
                TableRepository.saveOrUpdate(currentTable)
                tableSnapshot = currentTable.createSnapshot()
                statusMessage = "Updated cell ($r, $c)"
            }
        )
    }

    // BATCH MULTI-EDIT DIALOG
    if (showBatchEditDialog) {
        AlertDialog(
            onDismissRequest = { showBatchEditDialog = false },
            title = {
                Text(
                    text = "Multi-Edit (${selectedCells.size} Selected Cells)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF6A1B9A)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter a value to set across all ${selectedCells.size} selected cells. Attachments, notes, and checklists will remain intact.",
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )
                    OutlinedTextField(
                        value = batchEditText,
                        onValueChange = { batchEditText = it },
                        placeholder = { Text("Enter cell value...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedCells.forEach { (r, c) ->
                            val oldRaw = currentTable.rows.getOrNull(r)?.getOrNull(c) ?: ""
                            val payload = CellAttachmentHelper.parseCellContent(oldRaw)
                            val updatedEncoded = CellAttachmentHelper.formatCellContent(
                                displayText = batchEditText,
                                attachments = payload.attachments,
                                note = payload.note,
                                additionalNote = payload.additionalNote,
                                checklists = payload.checklists
                            )
                            currentTable.setCellValue(r, c, updatedEncoded)
                        }
                        currentTable.recomputeFormulas()
                        currentTable.markUpdated()
                        TableRepository.saveOrUpdate(currentTable)
                        tableSnapshot = currentTable.createSnapshot()
                        showBatchEditDialog = false
                        statusMessage = "Updated ${selectedCells.size} cells with '$batchEditText'"
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                ) {
                    Text("Apply to All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showNewTableDialog) {
        NewTableDialog(
            onDismiss = { showNewTableDialog = false },
            onTableCreated = { newTable ->
                TableRepository.saveOrUpdate(newTable)
                currentTable = newTable
                tableSnapshot = newTable.createSnapshot()
                columnValueFilters.clear()
                hiddenColumns.clear()
                hiddenRows.clear()
                hiddenChartDateIndices.clear()
                globalSearchQuery = ""
                selectedCells.clear(); selectedCells.add(Pair(0, 0))
                anchorCell = Pair(0, 0)
                detectedWords.clear()
                selectedTokens.clear()
                selectedBitmap = null
                previewCroppedBitmap = null
                showNewTableDialog = false
                statusMessage = "Created table '${newTable.tableName}' and saved to storage!"
            }
        )
    }

    // FORMULA BUILDER MODAL
    if (showFormulaBuilderDialog) {
        FormulaBuilderDialog(
            initialColName = formulaInitialColName,
            initialFormula = formulaInitialExpression,
            headers = currentTable.headers,
            sampleRowValues = currentTable.rows.firstOrNull() ?: emptyList(),
            targetColIndex = formulaEditingColIndex,
            onDismiss = { showFormulaBuilderDialog = false },
            onConfirm = { colName, formula ->
                if (formulaEditingColIndex >= 0 && formulaEditingColIndex < currentTable.headers.size) {
                    val existing = currentTable.headers[formulaEditingColIndex]
                    currentTable.headers[formulaEditingColIndex] = existing.copy(
                        name = colName,
                        type = ColumnType.FORMULA,
                        formula = formula
                    )
                } else {
                    currentTable.addColumn(
                        name = colName,
                        type = ColumnType.FORMULA,
                        formula = formula
                    )
                }
                currentTable.recomputeFormulas()
                currentTable.markUpdated()
                TableRepository.saveOrUpdate(currentTable)
                tableSnapshot = currentTable.createSnapshot()
                showFormulaBuilderDialog = false
                statusMessage = "Applied formula: $colName = $formula"
                Toast.makeText(context, "Formula applied!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showAllWordsDialog) {
        AllWordsSelectorDialog(
            detectedWords = detectedWords,
            onDismiss = { showAllWordsDialog = false },
            onTransferSelected = { words, mode ->
                when (mode) {
                    TokenPlacementMode.SEQUENCE_FROM_ACTIVE -> {
                        var (tr, tc) = anchorCell
                        for (w in words) {
                            val currentRaw = currentTable.rows.getOrNull(tr)?.getOrNull(tc) ?: ""
                            val payload = CellAttachmentHelper.parseCellContent(currentRaw)
                            val updatedEncoded = CellAttachmentHelper.formatCellContent(
                                displayText = w,
                                attachments = payload.attachments,
                                note = payload.note,
                                additionalNote = payload.additionalNote,
                                checklists = payload.checklists
                            )
                            currentTable.setCellValue(tr, tc, updatedEncoded)
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
                            val currentRaw = currentTable.rows.getOrNull(r)?.getOrNull(c) ?: ""
                            val payload = CellAttachmentHelper.parseCellContent(currentRaw)
                            val updatedEncoded = CellAttachmentHelper.formatCellContent(
                                displayText = text,
                                attachments = payload.attachments,
                                note = payload.note,
                                additionalNote = payload.additionalNote,
                                checklists = payload.checklists
                            )
                            currentTable.setCellValue(r, c, updatedEncoded)
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
                    TokenPlacementMode.REPLACE_CURRENT_TABLE -> {
                        currentTable.replaceTableWithStructuredTokens(words, 3, false, 0)
                    }
                }
                currentTable.recomputeFormulas()
                currentTable.markUpdated()
                TableRepository.saveOrUpdate(currentTable)
                tableSnapshot = currentTable.createSnapshot()
                showAllWordsDialog = false
                statusMessage = "Transferred ${words.size} word tokens!"
            }
        )
    }

    // ADVANCED ROW EDITOR DIALOG (Passes only clean displayText and preserves attachments upon saving)
   /* if (showRowEditorDialog && currentTable.rows.isNotEmpty()) {
        val safeIndex = editingRowIndex.coerceIn(0, currentTable.rows.size - 1)
        key(currentTable.id, safeIndex) {
            val rawRowValues = currentTable.rows.getOrElse(safeIndex) { emptyList() }
            val cleanRowValues = remember(rawRowValues) {
                rawRowValues.map { CellAttachmentHelper.parseCellContent(it).displayText }
            }

            AdvancedRowEditorDialog(
                initialTableName = currentTable.tableName,
                initialTableDateTime = currentTable.tableDateTime,
                currentRowIndex = safeIndex,
                totalRows = currentTable.rows.size,
                rowName = currentTable.rowNames.getOrElse(safeIndex) { "Row ${safeIndex + 1}" },
                headers = currentTable.headers,
                rowValues = cleanRowValues,
                onDismiss = { showRowEditorDialog = false },
                onSaveRowAndTable = { newName, newDateTime, updatedRowName, updatedHeaders, updatedValues ->
                    currentTable.tableName = newName
                    currentTable.tableDateTime = newDateTime
                    if (safeIndex in currentTable.rowNames.indices) {
                        currentTable.rowNames[safeIndex] = updatedRowName
                    }
                    for (i in updatedHeaders.indices) {
                        if (i in currentTable.headers.indices) {
                            currentTable.headers[i] = updatedHeaders[i]
                        }
                    }
                    if (safeIndex in currentTable.rows.indices) {
                        val mergedValues = updatedValues.mapIndexed { i, newText ->
                            val oldRaw = currentTable.rows[safeIndex].getOrElse(i) { "" }
                            val payload = CellAttachmentHelper.parseCellContent(oldRaw)
                            CellAttachmentHelper.formatCellContent(
                                displayText = newText,
                                attachments = payload.attachments,
                                note = payload.note,
                                additionalNote = payload.additionalNote,
                                checklists = payload.checklists
                            )
                        }.toMutableList()
                        while (mergedValues.size < currentTable.headers.size) {
                            mergedValues.add("")
                        }
                        currentTable.rows[safeIndex].clear()
                        currentTable.rows[safeIndex].addAll(mergedValues)
                    }
                    currentTable.recomputeFormulas()
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                    statusMessage = "Row ${safeIndex + 1} updated and saved to storage!"
                },
                onNavigateRow = { target -> editingRowIndex = target },
                onAddNewColumn = { name, type ->
                    currentTable.addColumn(name, type)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                },
                onDeleteColumn = { colIdx ->
                    deleteColumnAndRemapIndices(colIdx)
                },
                onMoveColumn = { from, to ->
                    currentTable.moveColumn(from, to)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                },
                onAddNewRowBelow = {
                    currentTable.addRow("Row ${currentTable.rows.size + 1}", index = safeIndex + 1)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                },
                onAddNewRowAbove = {
                    currentTable.addRow("Row ${currentTable.rows.size + 1}", index = safeIndex)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                },
                onMoveRowUp = {
                    currentTable.moveRow(safeIndex, safeIndex - 1)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                    editingRowIndex = safeIndex - 1
                },
                onMoveRowDown = {
                    currentTable.moveRow(safeIndex, safeIndex + 1)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                    editingRowIndex = safeIndex + 1
                },
                onDeleteRow = {
                    deleteRowAndRemapIndices(safeIndex)
                    showRowEditorDialog = false
                    statusMessage = "Row ${safeIndex + 1} deleted."
                }
            )
        }
    }*/
        // ADVANCED ROW EDITOR DIALOG
    if (showRowEditorDialog && currentTable.rows.isNotEmpty()) {
        val safeIndex = editingRowIndex.coerceIn(0, currentTable.rows.size - 1)
        key(currentTable.id, safeIndex) {
            val rawRowValues = currentTable.rows.getOrElse(safeIndex) { emptyList() }

            AdvancedRowEditorDialog(
                initialTableName = currentTable.tableName,
                initialTableDateTime = currentTable.tableDateTime,
                currentRowIndex = safeIndex,
                totalRows = currentTable.rows.size,
                rowName = currentTable.rowNames.getOrElse(safeIndex) { "Row ${safeIndex + 1}" },
                headers = currentTable.headers,
                rowValues = rawRowValues, // Pass raw values directly
                onDismiss = { showRowEditorDialog = false },
                onSaveRowAndTable = { newName, newDateTime, updatedRowName, updatedHeaders, updatedValues ->
                    currentTable.tableName = newName
                    currentTable.tableDateTime = newDateTime
                    if (safeIndex in currentTable.rowNames.indices) {
                        currentTable.rowNames[safeIndex] = updatedRowName
                    }
                    for (i in updatedHeaders.indices) {
                        if (i in currentTable.headers.indices) {
                            currentTable.headers[i] = updatedHeaders[i]
                        }
                    }
                    if (safeIndex in currentTable.rows.indices) {
                        currentTable.rows[safeIndex].clear()
                        currentTable.rows[safeIndex].addAll(updatedValues)
                        while (currentTable.rows[safeIndex].size < currentTable.headers.size) {
                            currentTable.rows[safeIndex].add("")
                        }
                    }
                    currentTable.recomputeFormulas()
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                    statusMessage = "Row ${safeIndex + 1} updated and saved to storage!"
                },
                onNavigateRow = { target -> editingRowIndex = target },
                onAddNewColumn = { name, type ->
                    currentTable.addColumn(name, type)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                },
                onDeleteColumn = { colIdx ->
                    deleteColumnAndRemapIndices(colIdx)
                },
                onMoveColumn = { from, to ->
                    currentTable.moveColumn(from, to)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                },
                onAddNewRowBelow = {
                    currentTable.addRow("Row ${currentTable.rows.size + 1}", index = safeIndex + 1)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                },
                onAddNewRowAbove = {
                    currentTable.addRow("Row ${currentTable.rows.size + 1}", index = safeIndex)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                },
                onMoveRowUp = {
                    currentTable.moveRow(safeIndex, safeIndex - 1)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                    editingRowIndex = safeIndex - 1
                },
                onMoveRowDown = {
                    currentTable.moveRow(safeIndex, safeIndex + 1)
                    currentTable.markUpdated()
                    TableRepository.saveOrUpdate(currentTable)
                    tableSnapshot = currentTable.createSnapshot()
                    editingRowIndex = safeIndex + 1
                },
                onDeleteRow = {
                    deleteRowAndRemapIndices(safeIndex)
                    showRowEditorDialog = false
                    statusMessage = "Row ${safeIndex + 1} deleted."
                }
            )
        }
    }


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

    if (showAddDateColumnDialog) {
        AlertDialog(
            onDismissRequest = { showAddDateColumnDialog = false },
            title = {
                Text("Add Date Attendance Column", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1565C0))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Select how you'd like to add date columns to track Present or Absent:", fontSize = 12.sp, color = Color.DarkGray)

                    Button(
                        onClick = {
                            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            val todayStr = sdf.format(Date())
                            currentTable.addColumn(todayStr, ColumnType.DATE)
                            currentTable.markUpdated()
                            TableRepository.saveOrUpdate(currentTable)
                            tableSnapshot = currentTable.createSnapshot()
                            showAddDateColumnDialog = false
                            statusMessage = "Added column '$todayStr' and saved to storage"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                    ) {
                        Text("📅 Add Today's Date", color = Color.White)
                    }

                    Button(
                        onClick = {
                            showAddDateColumnDialog = false
                            val cal = Calendar.getInstance()
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val selCal = Calendar.getInstance().apply { set(y, m, d) }
                                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                    val dateStr = sdf.format(selCal.time)
                                    currentTable.addColumn(dateStr, ColumnType.DATE)
                                    currentTable.markUpdated()
                                    TableRepository.saveOrUpdate(currentTable)
                                    tableSnapshot = currentTable.createSnapshot()
                                    statusMessage = "Added column '$dateStr' and saved to storage"
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                    ) {
                        Text("🗓 Pick Custom Date from Calendar", color = Color.White)
                    }

                    Button(
                        onClick = {
                            showAddDateColumnDialog = false
                            val cal = Calendar.getInstance()
                            DatePickerDialog(
                                context,
                                { _, y, m, _ ->
                                    val tempCal = Calendar.getInstance().apply { set(y, m, 1) }
                                    val daysInMonth = tempCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                    for (day in 1..daysInMonth) {
                                        tempCal.set(Calendar.DAY_OF_MONTH, day)
                                        val dayStr = sdf.format(tempCal.time)
                                        if (currentTable.headers.none { it.name.trim() == dayStr }) {
                                            currentTable.addColumn(dayStr, ColumnType.DATE)
                                        }
                                    }
                                    currentTable.markUpdated()
                                    TableRepository.saveOrUpdate(currentTable)
                                    tableSnapshot = currentTable.createSnapshot()
                                    statusMessage = "Generated $daysInMonth daily columns in storage!"
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                1
                            ).apply {
                                setTitle("Select Month to Generate Daily Columns")
                            }.show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A))
                    ) {
                        Text("📆 Generate Full Month (1 to 31)", color = Color.White)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddDateColumnDialog = false }) { Text("Cancel") }
            }
        )
    }

    // SHOW / HIDE ATTENDANCE DATES IN CHART
    val dateColIndices = remember(currentTable.headers) {
        currentTable.headers.indices.filter { idx ->
            val def = currentTable.headers[idx]
            def.type == ColumnType.DATE || parseDateFromHeader(def.name) != null
        }
    }

    if (showChartDateSelectorDialog) {
        AlertDialog(
            onDismissRequest = { showChartDateSelectorDialog = false },
            title = {
                Text("Show / Hide Dates in Attendance Chart", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF1565C0))
            },
            text = {
                if (dateColIndices.isEmpty()) {
                    Text("No date columns found in this table. Add date columns first.", fontSize = 12.sp, color = Color.Gray)
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Uncheck any date to hide it from the daily attendance chart:", fontSize = 11.sp, color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(4.dp))
                        for (colIdx in dateColIndices) {
                            val colName = currentTable.headers.getOrNull(colIdx)?.name ?: "Date $colIdx"
                            val isVisible = !hiddenChartDateIndices.contains(colIdx)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isVisible) hiddenChartDateIndices.add(colIdx)
                                        else hiddenChartDateIndices.remove(colIdx)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isVisible,
                                    onCheckedChange = { checked ->
                                        if (checked) hiddenChartDateIndices.remove(colIdx)
                                        else hiddenChartDateIndices.add(colIdx)
                                    },
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = colName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showChartDateSelectorDialog = false },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                ) {
                    Text("Done", color = Color.White)
                }
            }
        )
    }

    // SHOW / HIDE CUSTOM COLUMNS DIALOG
    if (showColumnVisibilityDialog) {
        var colSearchQuery by remember { mutableStateOf("") }
        val filteredColsForDialog = remember(currentTable.headers, colSearchQuery) {
            currentTable.headers.indices.filter { idx ->
                colSearchQuery.isBlank() || currentTable.headers[idx].name.contains(colSearchQuery, ignoreCase = true)
            }
        }

        AlertDialog(
            onDismissRequest = { showColumnVisibilityDialog = false },
            title = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Show / Hide Columns", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1565C0))
                    Text("${currentTable.headers.size - hiddenColumns.size}/${currentTable.headers.size} Visible", fontSize = 11.sp, color = Color.Gray)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    OutlinedTextField(
                        value = colSearchQuery,
                        onValueChange = { colSearchQuery = it },
                        placeholder = { Text("Search columns...", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { hiddenColumns.clear() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(2.dp)
                        ) { Text("Show All", fontSize = 11.sp) }

                        OutlinedButton(
                            onClick = {
                                hiddenColumns.clear()
                                hiddenColumns.addAll(currentTable.headers.indices.drop(1))
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(2.dp)
                        ) { Text("Hide Others", fontSize = 11.sp) }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Divider()

                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(filteredColsForDialog) { _, cIdx ->
                            val colDef = currentTable.headers[cIdx]
                            val isVisible = !hiddenColumns.contains(cIdx)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isVisible) {
                                            if (currentTable.headers.size - hiddenColumns.size > 1) {
                                                hiddenColumns.add(cIdx)
                                            } else {
                                                Toast.makeText(context, "At least 1 column must stay visible", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            hiddenColumns.remove(cIdx)
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isVisible,
                                    onCheckedChange = { checked ->
                                        if (checked) hiddenColumns.remove(cIdx)
                                        else if (currentTable.headers.size - hiddenColumns.size > 1) hiddenColumns.add(cIdx)
                                    },
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = colDef.name, fontSize = 13.sp, fontWeight = if (isVisible) FontWeight.SemiBold else FontWeight.Normal)
                                    Text(text = if (colDef.type == ColumnType.FORMULA) "[fx: ${colDef.formula}]" else "[${colDef.type.label}]", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showColumnVisibilityDialog = false }) { Text("Done") }
            }
        )
    }

    // SHOW / HIDE CUSTOM ROWS DIALOG
    if (showRowVisibilityDialog) {
        var rowSearchQueryInDialog by remember { mutableStateOf("") }
        val filteredRowsForDialog = remember(currentTable.rows, currentTable.rowNames, rowSearchQueryInDialog) {
            currentTable.rows.indices.filter { idx ->
                val rName = currentTable.rowNames.getOrElse(idx) { "Row ${idx + 1}" }
                rowSearchQueryInDialog.isBlank() ||
                rName.contains(rowSearchQueryInDialog, ignoreCase = true) ||
                currentTable.rows[idx].any {
                    CellAttachmentHelper.parseCellContent(it).displayText.contains(rowSearchQueryInDialog, ignoreCase = true)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showRowVisibilityDialog = false },
            title = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Show / Hide Rows", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1565C0))
                    Text("${currentTable.rows.size - hiddenRows.size}/${currentTable.rows.size} Visible", fontSize = 11.sp, color = Color.Gray)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    OutlinedTextField(
                        value = rowSearchQueryInDialog,
                        onValueChange = { rowSearchQueryInDialog = it },
                        placeholder = { Text("Search rows...", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { hiddenRows.clear() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(2.dp)
                        ) { Text("Show All", fontSize = 11.sp) }

                        OutlinedButton(
                            onClick = {
                                hiddenRows.clear()
                                hiddenRows.addAll(currentTable.rows.indices)
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(2.dp)
                        ) { Text("Hide All", fontSize = 11.sp) }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Divider()

                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(filteredRowsForDialog) { _, rIdx ->
                            val rName = currentTable.rowNames.getOrElse(rIdx) { "Row ${rIdx + 1}" }
                            val isVisible = !hiddenRows.contains(rIdx)
                            val firstCellVal = CellAttachmentHelper.parseCellContent(
                                currentTable.rows.getOrNull(rIdx)?.firstOrNull().orEmpty()
                            ).displayText

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isVisible) hiddenRows.add(rIdx)
                                        else hiddenRows.remove(rIdx)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isVisible,
                                    onCheckedChange = { checked ->
                                        if (checked) hiddenRows.remove(rIdx)
                                        else hiddenRows.add(rIdx)
                                    },
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = rName, fontSize = 13.sp, fontWeight = if (isVisible) FontWeight.SemiBold else FontWeight.Normal)
                                    if (firstCellVal.isNotBlank()) {
                                        Text(text = "Preview: $firstCellVal", fontSize = 10.sp, color = Color.Gray, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showRowVisibilityDialog = false }) { Text("Done") }
            }
        )
    }

    // CONFIRM DELETE COLUMN DIALOG
    if (colPendingDeleteIdx != null) {
        val cIdx = colPendingDeleteIdx!!
        val colName = currentTable.headers.getOrNull(cIdx)?.name ?: "Column ${cIdx + 1}"
        AlertDialog(
            onDismissRequest = { colPendingDeleteIdx = null },
            title = { Text("Delete Column?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete column '$colName'? All data within this column will be permanently removed.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteColumnAndRemapIndices(cIdx)
                        colPendingDeleteIdx = null
                        statusMessage = "Column '$colName' deleted."
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                ) { Text("Delete", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { colPendingDeleteIdx = null }) { Text("Cancel") }
            }
        )
    }

    // CONFIRM DELETE ROW DIALOG
    if (rowPendingDeleteIdx != null) {
        val rIdx = rowPendingDeleteIdx!!
        val rName = currentTable.rowNames.getOrElse(rIdx) { "Row ${rIdx + 1}" }
        AlertDialog(
            onDismissRequest = { rowPendingDeleteIdx = null },
            title = { Text("Delete Row?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete '$rName' (Row #${rIdx + 1})?") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteRowAndRemapIndices(rIdx)
                        rowPendingDeleteIdx = null
                        statusMessage = "Row '$rName' deleted."
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                ) { Text("Delete", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { rowPendingDeleteIdx = null }) { Text("Cancel") }
            }
        )
    }

    if (showClearTableConfirm) {
        AlertDialog(
            onDismissRequest = { showClearTableConfirm = false },
            title = { Text("Clear All Cell Values?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to empty every cell in '${currentTable.tableName}'? Headers and rows will remain intact.") },
            confirmButton = {
                Button(
                    onClick = {
                        currentTable.clearAllValues()
                        currentTable.markUpdated()
                        TableRepository.saveOrUpdate(currentTable)
                        tableSnapshot = currentTable.createSnapshot()
                        showClearTableConfirm = false
                        statusMessage = "Cleared all cells in table."
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                ) { Text("Clear All", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { showClearTableConfirm = false }) { Text("Cancel") } }
        )
    }

    if (tablePendingDelete != null) {
        val targetTable = tablePendingDelete!!
        val isCurrentActive = targetTable.id == currentTable.id
        AlertDialog(
            onDismissRequest = { tablePendingDelete = null },
            title = {
                Text(
                    text = if (isCurrentActive) "Delete Active Table?" else "Delete Table?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Are you sure you want to delete '${targetTable.tableName}'? All data, rows, and cells will be completely deleted across the entire application and storage.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        executeDeleteTable(targetTable.id)
                        tablePendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                ) { Text("Delete Table", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { tablePendingDelete = null }) { Text("Cancel") } }
        )
    }

    val visibleColIndices = currentTable.headers.indices.filter { !hiddenColumns.contains(it) }

    // Strip metadata when searching and filtering rows
    val filteredRowIndices = currentTable.rows.indices.filter { rIdx ->
        if (hiddenRows.contains(rIdx)) return@filter false

        val nameMatch = currentTable.rowNames.getOrElse(rIdx) { "" }.contains(globalSearchQuery, ignoreCase = true)
        val cellMatch = currentTable.rows[rIdx].any { raw ->
            CellAttachmentHelper.parseCellContent(raw).displayText.contains(globalSearchQuery, ignoreCase = true)
        }
        val matchesGlobal = globalSearchQuery.isBlank() || nameMatch || cellMatch

        val matchesColFilters = columnValueFilters.all { (colIdx, selectedSet) ->
            val cellVal = CellAttachmentHelper.parseCellContent(currentTable.rows[rIdx].getOrElse(colIdx) { "" }).displayText
            selectedSet.contains(cellVal)
        }
        matchesGlobal && matchesColFilters
    }

    // Filter values dialog: Groups purely by clean display text (e.g. "John Doe")
    if (activeFilterColIdx != null) {
        val targetCol = activeFilterColIdx!!
        val colName = currentTable.headers.getOrNull(targetCol)?.name ?: "Column ${targetCol + 1}"

        val distinctValuesWithCount = remember(currentTable.rows, targetCol) {
            currentTable.rows
                .map { raw -> CellAttachmentHelper.parseCellContent(raw.getOrElse(targetCol) { "" }).displayText }
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
                        ) { Text("Select All", fontSize = 10.sp) }

                        OutlinedButton(
                            onClick = {
                                filteredItemsInDialog.forEach { (v, _) ->
                                    activeSelectedInDialog.remove(v)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(2.dp)
                        ) { Text("Clear All", fontSize = 10.sp) }
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

    // Dynamic typography scaling
    val cellFontSize = (12 * tableZoomScale).sp
    val headerFontSize = (13 * tableZoomScale).sp
    val subTextFontSize = (10 * tableZoomScale).sp
    val badgeFontSize = (9 * tableZoomScale).sp
    val dateBtnFontSize = (11 * tableZoomScale).sp

    // Proportional column dimensions
    val actionColWidth = (190 * tableZoomScale).dp.coerceAtLeast(140.dp)
    val dataColWidth = (195 * tableZoomScale).dp.coerceAtLeast(140.dp)
    val totalTableWidth = actionColWidth + (dataColWidth * visibleColIndices.size) + (90 * tableZoomScale).dp
    val tableHorizontalScrollState = rememberScrollState()

    Scaffold(
        topBar = {
            if (!isFullScreen) {
                Column {
                    // Line 1: Primary Top App Bar
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

                    // Line 2: PERMANENT CANCEL & SAVE BAR (FIXED AT TOP, UNCONGESTED, NEVER DISAPPEARS)
                    Surface(
                        color = Color(0xFFFFFFFF),
                        elevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFFE0F2FE), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "${filteredRowIndices.size}/${currentTable.rows.size} Rows • ${visibleColIndices.size}/${currentTable.headers.size} Cols",
                                        color = Color(0xFF0369A1),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                                Text(
                                    text = statusMessage.take(20),
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    maxLines = 1
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { performSynchronizedCancel() },
                                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    border = BorderStroke(1.dp, Color(0xFFDC2626)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("↩ Cancel", color = Color(0xFFDC2626), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { performSynchronizedSave() },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF15803D)),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("💾 Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                Surface(
                    color = Color(0xFFFFFFFF),
                    elevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⛶ ${currentTable.tableName}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF0F172A),
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { performSynchronizedCancel() },
                                colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, Color(0xFFDC2626)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("↩ Cancel", color = Color(0xFFDC2626), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { performSynchronizedSave() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF15803D)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("💾 Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { isFullScreen = false },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF475569)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Exit ✕", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
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
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        if (!isMultiSelectMode) {
                            selectedCells.clear()
                            anchorCell = Pair(-1, -1)
                        }
                    })
                }
        ) {
            if (selectedTabIndex == 0 || isFullScreen) {
                key(currentTable.id) {
                    val tab0VerticalScrollState = rememberScrollState()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                            .verticalScroll(tab0VerticalScrollState)
                    ) {
                        // Action Toolbar Row
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
                                onClick = { isViewMode = !isViewMode },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (isViewMode) Color(0xFF00897B) else Color(0xFF1565C0)
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isViewMode) "👁 View Mode" else "✎ Edit Mode",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Quick Magnification Buttons
                            Button(
                                onClick = { tableZoomScale = (tableZoomScale * 1.15f).coerceAtMost(2.0f) },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) { Text("🔍+", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                            Button(
                                onClick = { tableZoomScale = (tableZoomScale / 1.15f).coerceAtLeast(0.75f) },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) { Text("🔍-", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                            if (tableZoomScale != 1.0f) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF263238), RoundedCornerShape(4.dp))
                                        .clickable { tableZoomScale = 1.0f }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "🔍 ${(tableZoomScale * 100).toInt()}% (Reset)",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Button(onClick = { showNewTableDialog = true }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("+ New Table", color = Color.White, fontSize = 11.sp) }

                            Button(
                                onClick = {
                                    formulaEditingColIndex = -1
                                    formulaInitialColName = "Total"
                                    formulaInitialExpression = ""
                                    showFormulaBuilderDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("fx + Formula Col", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { showColumnVisibilityDialog = true },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF334155)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("👁 Columns (${visibleColIndices.size}/${currentTable.headers.size})", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { showRowVisibilityDialog = true },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF475569)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("👁 Rows (${currentTable.rows.size - hiddenRows.size}/${currentTable.rows.size})", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            if (hiddenColumns.isNotEmpty() || hiddenRows.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        hiddenColumns.clear()
                                        hiddenRows.clear()
                                        statusMessage = "Restored all hidden rows and columns"
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD97706)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("✕ Reset Hidden (${hiddenColumns.size}c, ${hiddenRows.size}r)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Button(
                                onClick = { showAddDateColumnDialog = true },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("📅 + Date Col", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { showAttendanceChart = !showAttendanceChart },
                                colors = ButtonDefaults.buttonColors(backgroundColor = if (showAttendanceChart) Color(0xFF303F9F) else Color(0xFF5C6BC0)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (showAttendanceChart) "📊 Hide Chart" else "📊 Attendance Chart", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(onClick = {
                                val subTable = currentTable.createSubTable("${currentTable.tableName} (Filtered)", filteredRowIndices, visibleColIndices)
                                TableRepository.saveOrUpdate(subTable)
                                currentTable = subTable
                                tableSnapshot = subTable.createSnapshot()
                                columnValueFilters.clear()
                                hiddenColumns.clear()
                                hiddenRows.clear()
                                hiddenChartDateIndices.clear()
                                globalSearchQuery = ""
                                selectedCells.clear(); selectedCells.add(Pair(0, 0))
                                statusMessage = "Created sub-table!"
                            }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00ACC1)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("📋 New from Filters", color = Color.White, fontSize = 11.sp) }

                            Button(
                                onClick = { showScanWorkspaceInTable = !showScanWorkspaceInTable },
                                colors = ButtonDefaults.buttonColors(backgroundColor = if (showScanWorkspaceInTable) Color(0xFFE91E63) else Color(0xFF3949AB)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text(if (showScanWorkspaceInTable) "✕ Hide Scanner" else "📷 Show Scanner & Words", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                            Button(onClick = { jumpToNextRow() }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Next Row ➔", color = Color.White, fontSize = 11.sp) }
                            Button(onClick = {
                                currentTable.transposeTable()
                                currentTable.markUpdated()
                                TableRepository.saveOrUpdate(currentTable)
                                tableSnapshot = currentTable.createSnapshot()
                            }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("⇄ Transpose", color = Color.White, fontSize = 11.sp) }

                            Button(onClick = { activeExportFormat = ExportFormat.PDF; fileSaveLauncher.launch("${currentTable.tableName}.pdf") }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("PDF", color = Color.White, fontSize = 11.sp) }
                            Button(onClick = { activeExportFormat = ExportFormat.EXCEL; fileSaveLauncher.launch("${currentTable.tableName}.csv") }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("CSV", color = Color.White, fontSize = 11.sp) }
                            Button(onClick = { activeExportFormat = ExportFormat.WORD; fileSaveLauncher.launch("${currentTable.tableName}.doc") }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Word", color = Color.White, fontSize = 11.sp) }
                            Button(onClick = { csvImportLauncher.launch("text/*") }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Import CSV", color = Color.White, fontSize = 11.sp) }

                            Button(onClick = { printTablePdf() }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0277BD)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("🖨 Print", color = Color.White, fontSize = 11.sp) }
                            Button(onClick = { shareTablePdf() }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00838F)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("↗ Share", color = Color.White, fontSize = 11.sp) }

                            Button(onClick = {
                                currentTable.addRow()
                                currentTable.markUpdated()
                                TableRepository.saveOrUpdate(currentTable)
                            }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("+ Row", color = Color.White, fontSize = 11.sp) }
                            Button(onClick = {
                                currentTable.addColumn("Col ${currentTable.headers.size + 1}")
                                currentTable.markUpdated()
                                TableRepository.saveOrUpdate(currentTable)
                            }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("+ Col", color = Color.White, fontSize = 11.sp) }
                        }

                        // ATTENDANCE CHART
                        if (showAttendanceChart) {
                            val allDateColIndices = remember(currentTable.headers) {
                                currentTable.headers.indices.filter { idx ->
                                    val def = currentTable.headers[idx]
                                    def.type == ColumnType.DATE || parseDateFromHeader(def.name) != null
                                }
                            }
                            val dateColIndices = allDateColIndices.filter { !hiddenChartDateIndices.contains(it) }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                elevation = 2.dp,
                                backgroundColor = Color.White
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "📊 Daily Attendance Chart (${filteredRowIndices.size} filtered rows)",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF1565C0)
                                            )
                                            if (dateColIndices.isNotEmpty()) {
                                                Text(
                                                    text = "${dateColIndices.size} of ${allDateColIndices.size} Dates Visible",
                                                    fontSize = 10.sp,
                                                    color = Color.Gray,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }

                                        if (allDateColIndices.isNotEmpty()) {
                                            Button(
                                                onClick = { showChartDateSelectorDialog = true },
                                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF546E7A)),
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                modifier = Modifier.height(26.dp)
                                            ) {
                                                Text("⚙ Select Dates (${dateColIndices.size}/${allDateColIndices.size})", color = Color.White, fontSize = 10.sp)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    if (dateColIndices.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(60.dp)
                                                .background(Color(0xFFF1F5F9), RoundedCornerShape(6.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (allDateColIndices.isEmpty()) "No date columns yet. Tap '📅 + Date Col' to add attendance columns." else "All dates are hidden. Tap '⚙ Select Dates' to show them.",
                                                fontSize = 11.sp,
                                                color = Color.Gray,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    } else {
                                        val totalRowsFiltered = filteredRowIndices.size.coerceAtLeast(1)
                                        val maxChartHeight = 70.dp

                                        LazyRow(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.Bottom
                                        ) {
                                            itemsIndexed(dateColIndices) { _, colIdx ->
                                                val colDef = currentTable.headers[colIdx]
                                                val presentCount = filteredRowIndices.count { rIdx ->
                                                    val raw = currentTable.rows[rIdx].getOrElse(colIdx) { "" }
                                                    val cellVal = CellAttachmentHelper.parseCellContent(raw).displayText
                                                    isAttendancePresent(cellVal)
                                                }
                                                val absentCount = filteredRowIndices.count { rIdx ->
                                                    val raw = currentTable.rows[rIdx].getOrElse(colIdx) { "" }
                                                    val cellVal = CellAttachmentHelper.parseCellContent(raw).displayText
                                                    isAttendanceAbsent(cellVal)
                                                }
                                                val ratio = (presentCount.toFloat() / totalRowsFiltered).coerceIn(0.05f, 1f)

                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier
                                                        .width(54.dp)
                                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(6.dp))
                                                        .border(0.5.dp, Color(0xFFE2E8F0), RoundedCornerShape(6.dp))
                                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "$presentCount P",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF2E7D32)
                                                    )

                                                    Spacer(modifier = Modifier.height(2.dp))

                                                    Box(
                                                        modifier = Modifier
                                                            .width(20.dp)
                                                            .height(maxChartHeight),
                                                        contentAlignment = Alignment.BottomCenter
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .fillMaxHeight()
                                                                .background(Color(0xFFECEFF1), RoundedCornerShape(3.dp))
                                                        )
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .fillMaxHeight(fraction = ratio)
                                                                .background(Color(0xFF43A047), RoundedCornerShape(3.dp))
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.height(4.dp))

                                                    Text(
                                                        text = if (absentCount > 0) "$absentCount A" else "${(ratio * 100).toInt()}%",
                                                        fontSize = 9.sp,
                                                        color = if (absentCount > 0) Color(0xFFC62828) else Color.DarkGray
                                                    )

                                                    val displayLabel = colDef.name.replace("2026-", "").replace("2025-", "")
                                                    Text(
                                                        text = displayLabel,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        textAlign = TextAlign.Center,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // SCANNER WORKSPACE
                        if (showScanWorkspaceInTable) {
                            val activeDisplayBitmap = previewCroppedBitmap ?: selectedBitmap
                            var scannerViewMode by remember { mutableIntStateOf(0) }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(420.dp)
                                    .padding(4.dp),
                                shape = RoundedCornerShape(10.dp),
                                elevation = 3.dp
                            ) {
                                Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "📷 Scanner (${detectedWords.size} Tokens)",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color(0xFF1565C0)
                                        )

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (scannerViewMode == 0) Color(0xFF1976D2) else Color(0xFFECEFF1),
                                                        RoundedCornerShape(4.dp)
                                                    )
                                                    .clickable { scannerViewMode = 0 }
                                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                            ) {
                                                Text("⚏ Split", fontSize = 10.sp, color = if (scannerViewMode == 0) Color.White else Color.Black)
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (scannerViewMode == 1) Color(0xFF1976D2) else Color(0xFFECEFF1),
                                                        RoundedCornerShape(4.dp)
                                                    )
                                                    .clickable { scannerViewMode = 1 }
                                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                            ) {
                                                Text("🔤 Tokens", fontSize = 10.sp, color = if (scannerViewMode == 1) Color.White else Color.Black)
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (scannerViewMode == 2) Color(0xFF1976D2) else Color(0xFFECEFF1),
                                                        RoundedCornerShape(4.dp)
                                                    )
                                                    .clickable { scannerViewMode = 2 }
                                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                            ) {
                                                Text("🖼 Image", fontSize = 10.sp, color = if (scannerViewMode == 2) Color.White else Color.Black)
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (scannerViewMode == 0 || scannerViewMode == 2) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(if (scannerViewMode == 2) 1f else 0.95f)
                                                    .fillMaxHeight()
                                                    .clipToBounds()
                                                    .background(Color(0xFF263238), RoundedCornerShape(6.dp))
                                                    .pointerInput(activeDisplayBitmap) {
                                                        detectTransformGestures { _, pan: Offset, zoom: Float, _ ->
                                                            zoomScale = (zoomScale * zoom).coerceIn(1f, 5f)
                                                            val maxPan = 500f * (zoomScale - 1f)
                                                            panOffsetX = (panOffsetX + pan.x).coerceIn(-maxPan, maxPan)
                                                            panOffsetY = (panOffsetY + pan.y).coerceIn(-maxPan, maxPan)
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (activeDisplayBitmap != null) {
                                                    Image(
                                                        bitmap = activeDisplayBitmap.asImageBitmap(),
                                                        contentDescription = "Cropped Snippet",
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .graphicsLayer(
                                                                scaleX = zoomScale,
                                                                scaleY = zoomScale,
                                                                translationX = panOffsetX,
                                                                translationY = panOffsetY
                                                            ),
                                                        contentScale = ContentScale.Fit
                                                    )

                                                    if (zoomScale > 1.05f) {
                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.TopStart)
                                                                .padding(6.dp)
                                                                .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                                                                .clickable {
                                                                    zoomScale = 1f
                                                                    panOffsetX = 0f
                                                                    panOffsetY = 0f
                                                                }
                                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                                        ) {
                                                            Text(
                                                                text = "🔍 ${(zoomScale * 100).toInt()}% (Reset)",
                                                                color = Color.White,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    Text(
                                                        "No cropped snippet yet.\nPick or scan to crop.",
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }

                                                Row(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomCenter)
                                                        .padding(4.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Button(
                                                        onClick = { imagePickerLauncher.launch("image/*") },
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) { Text("📷 Pick", fontSize = 10.sp) }

                                                    Button(
                                                        onClick = { showCropperDialog = true },
                                                        enabled = selectedBitmap != null,
                                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) { Text("✂ Crop", fontSize = 10.sp) }
                                                }
                                            }
                                        }

                                        if (scannerViewMode == 0 || scannerViewMode == 1) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(if (scannerViewMode == 1) 1f else 1.35f)
                                                    .fillMaxHeight()
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "Tokens (${detectedWords.size})",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp,
                                                        color = Color(0xFF1565C0)
                                                    )
                                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        TextButton(
                                                            onClick = {
                                                                selectedTokens.clear()
                                                                selectedTokens.addAll(detectedWords)
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)
                                                        ) { Text("All", fontSize = 10.sp) }

                                                        TextButton(
                                                            onClick = { selectedTokens.clear() },
                                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)
                                                        ) { Text("Clear", fontSize = 10.sp) }

                                                        TextButton(
                                                            onClick = { showAllWordsDialog = true },
                                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)
                                                        ) { Text("⛶ Modal", fontSize = 10.sp, color = Color(0xFF0D47A1), fontWeight = FontWeight.Bold) }
                                                    }
                                                }

                                                val (tr, tc) = anchorCell
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(bottom = 4.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Button(
                                                        onClick = {
                                                            if (selectedTokens.isNotEmpty()) {
                                                                val currentRaw = currentTable.rows.getOrNull(tr)?.getOrNull(tc) ?: ""
                                                                val payload = CellAttachmentHelper.parseCellContent(currentRaw)
                                                                val updatedEncoded = CellAttachmentHelper.formatCellContent(
                                                                    displayText = selectedTokens.joinToString(" "),
                                                                    attachments = payload.attachments,
                                                                    note = payload.note,
                                                                    additionalNote = payload.additionalNote,
                                                                    checklists = payload.checklists
                                                                )
                                                                currentTable.setCellValue(tr, tc, updatedEncoded)
                                                                currentTable.markUpdated()
                                                                TableRepository.saveOrUpdate(currentTable)
                                                                tableSnapshot = currentTable.createSnapshot()
                                                                jumpToNextRow()
                                                            }
                                                        },
                                                        enabled = selectedTokens.isNotEmpty(),
                                                        modifier = Modifier.weight(1f).height(32.dp),
                                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                                                    ) {
                                                        Text(
                                                            text = "➔ Active ($tr,$tc)",
                                                            fontSize = 10.sp,
                                                            color = Color.White,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1
                                                        )
                                                    }

                                                    Button(
                                                        onClick = {
                                                            var (cr, cc) = anchorCell
                                                            for (w in selectedTokens) {
                                                                val currentRaw = currentTable.rows.getOrNull(cr)?.getOrNull(cc) ?: ""
                                                                val payload = CellAttachmentHelper.parseCellContent(currentRaw)
                                                                val updatedEncoded = CellAttachmentHelper.formatCellContent(
                                                                    displayText = w,
                                                                    attachments = payload.attachments,
                                                                    note = payload.note,
                                                                    additionalNote = payload.additionalNote,
                                                                    checklists = payload.checklists
                                                                )
                                                                currentTable.setCellValue(cr, cc, updatedEncoded)
                                                                if (cc < currentTable.headers.size - 1) cc++ else {
                                                                    if (cr < currentTable.rows.size - 1) { cr++; cc = 0 } else { currentTable.addRow(); cr++; cc = 0 }
                                                                }
                                                            }
                                                            currentTable.markUpdated()
                                                            TableRepository.saveOrUpdate(currentTable)
                                                            tableSnapshot = currentTable.createSnapshot()
                                                        },
                                                        enabled = selectedTokens.isNotEmpty(),
                                                        modifier = Modifier.weight(1f).height(32.dp),
                                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                                                    ) {
                                                        Text(
                                                            text = "➔ Sequence",
                                                            fontSize = 10.sp,
                                                            color = Color.White,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .weight(1f)
                                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(6.dp))
                                                        .border(1.dp, Color(0xFFCFD8DC), RoundedCornerShape(6.dp))
                                                        .verticalScroll(rememberScrollState())
                                                        .padding(6.dp)
                                                ) {
                                                    if (detectedWords.isEmpty()) {
                                                        Box(
                                                            modifier = Modifier.fillMaxSize().padding(top = 28.dp),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                "No tokens extracted yet.\nCrop a region above to generate words.",
                                                                fontSize = 11.sp,
                                                                color = Color.Gray,
                                                                textAlign = TextAlign.Center
                                                            )
                                                        }
                                                    } else {
                                                        FlowRow(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            detectedWords.forEach { w ->
                                                                val isSel = selectedTokens.contains(w)
                                                                Box(
                                                                    modifier = Modifier
                                                                        .background(
                                                                            if (isSel) Color(0xFF1976D2) else Color(0xFFFFFFFF),
                                                                            RoundedCornerShape(6.dp)
                                                                        )
                                                                        .border(
                                                                            1.dp,
                                                                            if (isSel) Color(0xFF0D47A1) else Color(0xFFB0BEC5),
                                                                            RoundedCornerShape(6.dp)
                                                                        )
                                                                        .clickable {
                                                                            if (isSel) selectedTokens.remove(w)
                                                                            else selectedTokens.add(w)
                                                                        }
                                                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                                                ) {
                                                                    Text(
                                                                        text = w,
                                                                        fontSize = 11.sp,
                                                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                                        color = if (isSel) Color.White else Color(0xFF263238)
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
                                    onClick = {
                                        isMultiSelectMode = !isMultiSelectMode
                                        if (isMultiSelectMode && selectedCells.isEmpty() && anchorCell.first >= 0 && anchorCell.second >= 0) {
                                            selectedCells.add(anchorCell)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = if (isMultiSelectMode) Color(0xFF7B1FA2) else Color(0xFF546E7A)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(if (isMultiSelectMode) "✓ Multi (${selectedCells.size})" else "☐ Multi-Select", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                // Direct Batch Multi-Edit button
                                if (isMultiSelectMode && selectedCells.isNotEmpty()) {
                                    Button(
                                        onClick = {
                                            batchEditText = ""
                                            showBatchEditDialog = true
                                        },
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("✏️ Set All (${selectedCells.size})", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
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
                                            currentTable.markUpdated()
                                            TableRepository.saveOrUpdate(currentTable)
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
                                            currentTable.markUpdated()
                                            TableRepository.saveOrUpdate(currentTable)
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
                                        currentTable.markUpdated()
                                        TableRepository.saveOrUpdate(currentTable)
                                        statusMessage = "Cleared ${selectedCells.size} cell(s)!"
                                    },
                                    enabled = selectedCells.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("🗑 Clear Selection", color = Color.White, fontSize = 11.sp) }

                                OutlinedButton(onClick = { showClearTableConfirm = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Clear All", color = Color.Red, fontSize = 11.sp) }

                                Text("Shift:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Button(onClick = {
                                    val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.LEFT)
                                    selectedCells.clear(); selectedCells.addAll(u)
                                    currentTable.markUpdated()
                                    TableRepository.saveOrUpdate(currentTable)
                                }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("◀") }
                                Button(onClick = {
                                    val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.RIGHT)
                                    selectedCells.clear(); selectedCells.addAll(u)
                                    currentTable.markUpdated()
                                    TableRepository.saveOrUpdate(currentTable)
                                }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("▶") }
                                Button(onClick = {
                                    val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.UP)
                                    selectedCells.clear(); selectedCells.addAll(u)
                                    currentTable.markUpdated()
                                    TableRepository.saveOrUpdate(currentTable)
                                }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("▲") }
                                Button(onClick = {
                                    val u = currentTable.shiftCellsBatch(selectedCells, ShiftDirection.DOWN)
                                    selectedCells.clear(); selectedCells.addAll(u)
                                    currentTable.markUpdated()
                                    TableRepository.saveOrUpdate(currentTable)
                                }, modifier = Modifier.size(width = 30.dp, height = 26.dp), contentPadding = PaddingValues(0.dp)) { Text("▼") }
                            }
                        }

                        // HORIZONTAL SCROLL CONTAINER FOR THE FULL TABLE (WITH RELIABLE FULL-HEIGHT INTRINSIC RENDERING)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                                .horizontalScroll(tableHorizontalScrollState)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.size >= 2) {
                                                val p1 = event.changes[0].position
                                                val p2 = event.changes[1].position
                                                val prevP1 = event.changes[0].previousPosition
                                                val prevP2 = event.changes[1].previousPosition
                                                val currentDist = hypot((p1.x - p2.x).toDouble(), (p1.y - p2.y).toDouble()).toFloat()
                                                val prevDist = hypot((prevP1.x - prevP2.x).toDouble(), (prevP1.y - prevP2.y).toDouble()).toFloat()
                                                if (prevDist > 0f) {
                                                    val factor = currentDist / prevDist
                                                    tableZoomScale = (tableZoomScale * factor).coerceIn(0.75f, 2.0f)
                                                    event.changes.forEach { it.consume() }
                                                }
                                            }
                                        }
                                    }
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(totalTableWidth)
                                    .wrapContentHeight()
                            ) {
                                // Table Header Row
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFE3EDF7))
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.width(actionColWidth).border(1.dp, Color.LightGray).background(Color(0xFFECEFF1)).padding(6.dp)) {
                                        BasicTextField(value = currentTable.cornerHeader, onValueChange = { currentTable.cornerHeader = it }, textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = cellFontSize, color = Color(0xFF0D47A1)))
                                    }
                                    visibleColIndices.forEach { colIdx ->
                                        val colDef = currentTable.headers[colIdx]
                                        val isColFiltered = columnValueFilters.containsKey(colIdx)
                                        val isFormulaCol = colDef.type == ColumnType.FORMULA

                                        Box(
                                            modifier = Modifier
                                                .width(dataColWidth)
                                                .border(1.dp, if (isFormulaCol) Color(0xFF81C784) else Color.LightGray)
                                                .background(if (isFormulaCol) Color(0xFFF1F8E9) else Color(0xFFF5F9FD))
                                                .padding(6.dp)
                                        ) {
                                            Column {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    BasicTextField(value = colDef.name, onValueChange = { currentTable.headers[colIdx] = colDef.copy(name = it); currentTable.markUpdated() }, textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = headerFontSize), modifier = Modifier.weight(1f))
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            "👁",
                                                            fontSize = cellFontSize,
                                                            modifier = Modifier.clickable {
                                                                if (currentTable.headers.size - hiddenColumns.size > 1) {
                                                                    hiddenColumns.add(colIdx)
                                                                    statusMessage = "Hidden column '${colDef.name}'"
                                                                } else {
                                                                    Toast.makeText(context, "At least 1 column must stay visible", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }.padding(horizontal = 2.dp)
                                                        )
                                                        Text("✕", color = Color.Red, fontSize = cellFontSize, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = currentTable.headers.size > 1) {
                                                            colPendingDeleteIdx = colIdx
                                                        })
                                                    }
                                                }
                                                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    var typeExpanded by remember { mutableStateOf(false) }
                                                    Box {
                                                        Text(
                                                            text = if (isFormulaCol) "[fx: ${colDef.formula.take(10)} ▼]" else "[${colDef.type.label.take(7)} ▼]",
                                                            fontSize = subTextFontSize,
                                                            color = if (isFormulaCol) Color(0xFF2E7D32) else Color(0xFF0D47A1),
                                                            fontWeight = if (isFormulaCol) FontWeight.Bold else FontWeight.Normal,
                                                            modifier = Modifier
                                                                .background(if (isFormulaCol) Color(0xFFDCEDC8) else Color(0xFFE1F5FE), RoundedCornerShape(3.dp))
                                                                .clickable { typeExpanded = true }
                                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                        DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                                                            if (isFormulaCol) {
                                                                DropdownMenuItem(onClick = {
                                                                    formulaEditingColIndex = colIdx
                                                                    formulaInitialColName = colDef.name
                                                                    formulaInitialExpression = colDef.formula
                                                                    showFormulaBuilderDialog = true
                                                                    typeExpanded = false
                                                                }) {
                                                                    Text("✎ Edit Formula Expression", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                                                }
                                                                Divider()
                                                            }
                                                            ColumnType.values().forEach { ct ->
                                                                DropdownMenuItem(onClick = {
                                                                    if (ct == ColumnType.FORMULA) {
                                                                        formulaEditingColIndex = colIdx
                                                                        formulaInitialColName = colDef.name
                                                                        formulaInitialExpression = colDef.formula
                                                                        showFormulaBuilderDialog = true
                                                                    } else {
                                                                        currentTable.headers[colIdx] = colDef.copy(type = ct, formula = "")
                                                                        currentTable.recomputeFormulas()
                                                                        currentTable.markUpdated()
                                                                    }
                                                                    typeExpanded = false
                                                                }) { Text(ct.label) }
                                                            }
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
                                                            fontSize = subTextFontSize,
                                                            fontWeight = if (isColFiltered) FontWeight.Bold else FontWeight.Normal,
                                                            color = if (isColFiltered) Color.White else Color(0xFF37474F)
                                                        )
                                                    }

                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text("◀", modifier = Modifier.clickable(enabled = colIdx > 0) {
                                                            currentTable.moveColumn(colIdx, colIdx - 1)
                                                            currentTable.markUpdated()
                                                            TableRepository.saveOrUpdate(currentTable)
                                                        }, fontSize = cellFontSize)
                                                        Text("▶", modifier = Modifier.clickable(enabled = colIdx < currentTable.headers.size - 1) {
                                                            currentTable.moveColumn(colIdx, colIdx + 1)
                                                            currentTable.markUpdated()
                                                            TableRepository.saveOrUpdate(currentTable)
                                                        }, fontSize = cellFontSize)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Box(modifier = Modifier.width((90 * tableZoomScale).dp).padding(4.dp), contentAlignment = Alignment.Center) {
                                        Button(onClick = {
                                            currentTable.addColumn("Col ${currentTable.headers.size + 1}")
                                            currentTable.markUpdated()
                                            TableRepository.saveOrUpdate(currentTable)
                                            tableSnapshot = currentTable.createSnapshot()
                                        }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("+ Col", color = Color.White, fontSize = cellFontSize) }
                                    }
                                }

                                if (filteredRowIndices.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (globalSearchQuery.isNotBlank() || columnValueFilters.isNotEmpty() || hiddenRows.isNotEmpty()) 
                                                "No rows match the search, filter, or visibility criteria." 
                                            else "Table is empty. Tap '+ Row' above to add rows.",
                                            color = Color.Gray,
                                            fontSize = cellFontSize
                                        )
                                    }
                                } else {
                                    filteredRowIndices.forEach { origRIdx ->
                                        key("${currentTable.id}_row_$origRIdx") {
                                            val rowData = currentTable.rows[origRIdx]
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .border(0.5.dp, Color(0xFFE0E0E0)),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(modifier = Modifier.width(actionColWidth).border(0.5.dp, Color(0xFFCFD8DC)).background(Color(0xFFF9FAFB)).padding(6.dp)) {
                                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                        if (isViewMode) {
                                                            Text(
                                                                text = currentTable.rowNames.getOrElse(origRIdx) { "Row ${origRIdx + 1}" },
                                                                fontSize = cellFontSize,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = Color(0xFF1565C0),
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                        } else {
                                                            BasicTextField(value = currentTable.rowNames.getOrElse(origRIdx) { "Row ${origRIdx + 1}" }, onValueChange = { currentTable.rowNames[origRIdx] = it; currentTable.markUpdated() }, textStyle = TextStyle(fontSize = cellFontSize, fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0)), modifier = Modifier.weight(1f))
                                                        }
                                                        Text("✎", fontSize = headerFontSize, color = Color(0xFF00897B), fontWeight = FontWeight.Bold, modifier = Modifier.clickable { editingRowIndex = origRIdx; showRowEditorDialog = true }.padding(horizontal = 2.dp))
                                                        Text("👁", fontSize = cellFontSize, modifier = Modifier.clickable {
                                                            hiddenRows.add(origRIdx)
                                                            statusMessage = "Hidden Row ${origRIdx + 1}"
                                                        }.padding(horizontal = 2.dp))
                                                        Text("▲", modifier = Modifier.clickable(enabled = origRIdx > 0) {
                                                            currentTable.moveRow(origRIdx, origRIdx - 1)
                                                            currentTable.markUpdated()
                                                            TableRepository.saveOrUpdate(currentTable)
                                                        }, fontSize = dateBtnFontSize)
                                                        Text("▼", modifier = Modifier.clickable(enabled = origRIdx < currentTable.rows.size - 1) {
                                                            currentTable.moveRow(origRIdx, origRIdx + 1)
                                                            currentTable.markUpdated()
                                                            TableRepository.saveOrUpdate(currentTable)
                                                        }, fontSize = dateBtnFontSize)
                                                        Text("✕", color = Color.Red, fontSize = dateBtnFontSize, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                                                            rowPendingDeleteIdx = origRIdx
                                                        })
                                                    }
                                                }

                                                visibleColIndices.forEach { colIdx ->
                                                    val colDef = currentTable.headers[colIdx]
                                                    val cellCoord = Pair(origRIdx, colIdx)
                                                    val cellValue = rowData.getOrElse(colIdx) { "" }
                                                    val isSelected = selectedCells.contains(cellCoord)
                                                    val isAnchor = anchorCell == cellCoord

                                                    // Parse visible text and embedded attachments
                                                    val payload = remember(cellValue) {
                                                        CellAttachmentHelper.parseCellContent(cellValue)
                                                    }
                                                    val displayVal = payload.displayText
                                                    val cellAttachments = payload.attachments
                                                    val cellChecklists = payload.checklists
                                                    val cellNote = payload.note
                                                    val cellExtraNote = payload.additionalNote

                                                    val isDateCol = colDef.type == ColumnType.DATE || parseDateFromHeader(colDef.name) != null
                                                    val isPresent = isAttendancePresent(displayVal)
                                                    val isAbsent = isAttendanceAbsent(displayVal)
                                                    val isFormulaCol = colDef.type == ColumnType.FORMULA

                                                    val cellBg = when {
                                                        isSelected && isMultiSelectMode -> Color(0xFFE1BEE7)
                                                        isSelected -> Color(0xFFBBDEFB)
                                                        isFormulaCol -> Color(0xFFF1F8E9)
                                                        isDateCol && isPresent -> Color(0xFFE8F5E9)
                                                        isDateCol && isAbsent -> Color(0xFFFFEBEE)
                                                        else -> Color.White
                                                    }
                                                    val cellBorder = when {
                                                        isAnchor -> Color(0xFF00C853)
                                                        isSelected && isMultiSelectMode -> Color(0xFF7B1FA2)
                                                        isSelected -> Color(0xFF1976D2)
                                                        isFormulaCol -> Color(0xFFA5D6A7)
                                                        else -> Color.LightGray
                                                    }

                                                    Box(
                                                        modifier = Modifier
                                                            .width(dataColWidth)
                                                            .border(width = if (isSelected || isAnchor) 2.dp else 0.5.dp, color = cellBorder)
                                                            .background(cellBg)
                                                    ) {
                                                        Column(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = (6 * tableZoomScale).dp, vertical = (4 * tableZoomScale).dp)
                                                        ) {
                                                            // Top Row: Value / Editor + Clip Icon Button
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Box(modifier = Modifier.weight(1f)) {
                                                                    if (isViewMode) {
                                                                        Text(
                                                                            text = displayVal.ifBlank { " " },
                                                                            fontSize = cellFontSize,
                                                                            color = when {
                                                                                isPresent -> Color(0xFF2E7D32)
                                                                                isAbsent -> Color(0xFFC62828)
                                                                                else -> Color.Black
                                                                            },
                                                                            fontWeight = if (isPresent || isAbsent || isFormulaCol) FontWeight.Bold else FontWeight.Normal,
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .pointerInput(cellCoord, isMultiSelectMode) {
                                                                                    detectTapGestures(
                                                                                        onTap = {
                                                                                            if (isMultiSelectMode) {
                                                                                                anchorCell = cellCoord
                                                                                                if (selectedCells.contains(cellCoord)) {
                                                                                                    selectedCells.remove(cellCoord)
                                                                                                } else {
                                                                                                    selectedCells.add(cellCoord)
                                                                                                }
                                                                                            } else {
                                                                                                anchorCell = cellCoord
                                                                                                selectedCells.clear()
                                                                                                selectedCells.add(cellCoord)
                                                                                            }
                                                                                        },
                                                                                        onLongPress = {
                                                                                            isMultiSelectMode = true
                                                                                            anchorCell = cellCoord
                                                                                            if (!selectedCells.contains(cellCoord)) {
                                                                                                selectedCells.add(cellCoord)
                                                                                            }
                                                                                            statusMessage = "Multi-select: ${selectedCells.size} selected"
                                                                                        }
                                                                                    )
                                                                                }
                                                                        )
                                                                    } else {
                                                                        // EDIT MODE: Direct in-cell editing
                                                                        when (colDef.type) {
                                                                            ColumnType.FORMULA -> {
                                                                                Row(
                                                                                    modifier = Modifier
                                                                                        .fillMaxWidth()
                                                                                        .clickable(enabled = !isMultiSelectMode) {
                                                                                            formulaEditingColIndex = colIdx
                                                                                            formulaInitialColName = colDef.name
                                                                                            formulaInitialExpression = colDef.formula
                                                                                            showFormulaBuilderDialog = true
                                                                                        },
                                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                                                ) {
                                                                                    Text(
                                                                                        text = displayVal.ifBlank { "0" },
                                                                                        fontSize = cellFontSize,
                                                                                        fontWeight = FontWeight.Bold,
                                                                                        color = if (displayVal.startsWith("#")) Color.Red else Color(0xFF1B5E20),
                                                                                        modifier = Modifier.weight(1f)
                                                                                    )
                                                                                    Box(
                                                                                        modifier = Modifier
                                                                                            .background(Color(0xFFDCEDC8), RoundedCornerShape(3.dp))
                                                                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                                                                    ) {
                                                                                        Text("fx", fontSize = badgeFontSize, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                                                                    }
                                                                                }
                                                                            }

                                                                            ColumnType.DATE -> {
                                                                                Row(
                                                                                    modifier = Modifier.fillMaxWidth(),
                                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                                                ) {
                                                                                    BasicTextField(
                                                                                        value = displayVal,
                                                                                        onValueChange = { newVal ->
                                                                                            val encoded = CellAttachmentHelper.formatCellContent(
                                                                                                displayText = newVal,
                                                                                                attachments = cellAttachments,
                                                                                                note = cellNote,
                                                                                                additionalNote = cellExtraNote,
                                                                                                checklists = cellChecklists
                                                                                            )
                                                                                            currentTable.setCellValue(origRIdx, colIdx, encoded)
                                                                                            currentTable.markUpdated()
                                                                                        },
                                                                                        enabled = !isMultiSelectMode,
                                                                                        textStyle = TextStyle(
                                                                                            fontSize = dateBtnFontSize,
                                                                                            fontWeight = if (isPresent || isAbsent) FontWeight.Bold else FontWeight.Normal,
                                                                                            color = when {
                                                                                                isPresent -> Color(0xFF2E7D32)
                                                                                                isAbsent -> Color(0xFFC62828)
                                                                                                else -> Color.Black
                                                                                            }
                                                                                        ),
                                                                                        modifier = Modifier
                                                                                            .weight(1f)
                                                                                            .onFocusChanged {
                                                                                                if (it.isFocused && !isMultiSelectMode) {
                                                                                                    anchorCell = cellCoord
                                                                                                    selectedCells.clear()
                                                                                                    selectedCells.add(cellCoord)
                                                                                                }
                                                                                            }
                                                                                    )

                                                                                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                                                                        Box(
                                                                                            modifier = Modifier
                                                                                                .background(Color(0xFFE3F2FD), RoundedCornerShape(3.dp))
                                                                                                .clickable(enabled = !isMultiSelectMode) { openDatePickerForCell(origRIdx, colIdx) }
                                                                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                                                        ) {
                                                                                            Text("📅", fontSize = dateBtnFontSize)
                                                                                        }

                                                                                        Box(
                                                                                            modifier = Modifier
                                                                                                .background(
                                                                                                    if (isPresent) Color(0xFF2E7D32) else Color(0xFFC8E6C9),
                                                                                                    RoundedCornerShape(3.dp)
                                                                                                )
                                                                                                .clickable(enabled = !isMultiSelectMode) {
                                                                                                    val newVal = if (isPresent) "" else "Present"
                                                                                                    val encoded = CellAttachmentHelper.formatCellContent(
                                                                                                        displayText = newVal,
                                                                                                        attachments = cellAttachments,
                                                                                                        note = cellNote,
                                                                                                        additionalNote = cellExtraNote,
                                                                                                        checklists = cellChecklists
                                                                                                    )
                                                                                                    currentTable.setCellValue(origRIdx, colIdx, encoded)
                                                                                                    currentTable.markUpdated()
                                                                                                }
                                                                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                                                        ) {
                                                                                            Text("P", fontSize = subTextFontSize, fontWeight = FontWeight.Bold, color = if (isPresent) Color.White else Color(0xFF1B5E20))
                                                                                        }

                                                                                        Box(
                                                                                            modifier = Modifier
                                                                                                .background(
                                                                                                    if (isAbsent) Color(0xFFC62828) else Color(0xFFFFCDD2),
                                                                                                    RoundedCornerShape(3.dp)
                                                                                                )
                                                                                                .clickable(enabled = !isMultiSelectMode) {
                                                                                                    val newVal = if (isAbsent) "" else "Absent"
                                                                                                    val encoded = CellAttachmentHelper.formatCellContent(
                                                                                                        displayText = newVal,
                                                                                                        attachments = cellAttachments,
                                                                                                        note = cellNote,
                                                                                                        additionalNote = cellExtraNote,
                                                                                                        checklists = cellChecklists
                                                                                                    )
                                                                                                    currentTable.setCellValue(origRIdx, colIdx, encoded)
                                                                                                    currentTable.markUpdated()
                                                                                                }
                                                                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                                                        ) {
                                                                                            Text("A", fontSize = subTextFontSize, fontWeight = FontWeight.Bold, color = if (isAbsent) Color.White else Color(0xFFB71C1C))
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }

                                                                            ColumnType.NUMBER -> {
                                                                                Row(
                                                                                    modifier = Modifier.fillMaxWidth(),
                                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                                                ) {
                                                                                    BasicTextField(
                                                                                        value = displayVal,
                                                                                        onValueChange = { newVal ->
                                                                                            val filtered = newVal.filter { it.isDigit() || it == '-' }
                                                                                            val encoded = CellAttachmentHelper.formatCellContent(
                                                                                                displayText = filtered,
                                                                                                attachments = cellAttachments,
                                                                                                note = cellNote,
                                                                                                additionalNote = cellExtraNote,
                                                                                                checklists = cellChecklists
                                                                                            )
                                                                                            currentTable.setCellValue(origRIdx, colIdx, encoded)
                                                                                            currentTable.markUpdated()
                                                                                        },
                                                                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                                                        enabled = !isMultiSelectMode,
                                                                                        textStyle = TextStyle(fontSize = cellFontSize, color = Color.Black),
                                                                                        modifier = Modifier
                                                                                            .weight(1f)
                                                                                            .onFocusChanged {
                                                                                                if (it.isFocused && !isMultiSelectMode) {
                                                                                                    anchorCell = cellCoord
                                                                                                    selectedCells.clear()
                                                                                                    selectedCells.add(cellCoord)
                                                                                                }
                                                                                            }
                                                                                    )

                                                                                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                                                        Box(
                                                                                            modifier = Modifier
                                                                                                .background(Color(0xFFECEFF1), RoundedCornerShape(3.dp))
                                                                                                .clickable(enabled = !isMultiSelectMode) {
                                                                                                    val num = displayVal.toIntOrNull() ?: 0
                                                                                                    val encoded = CellAttachmentHelper.formatCellContent(
                                                                                                        displayText = (num - 1).toString(),
                                                                                                        attachments = cellAttachments,
                                                                                                        note = cellNote,
                                                                                                        additionalNote = cellExtraNote,
                                                                                                        checklists = cellChecklists
                                                                                                    )
                                                                                                    currentTable.setCellValue(origRIdx, colIdx, encoded)
                                                                                                    currentTable.markUpdated()
                                                                                                }
                                                                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                                                        ) {
                                                                                            Text("-", fontSize = dateBtnFontSize, fontWeight = FontWeight.Bold)
                                                                                        }

                                                                                        Box(
                                                                                            modifier = Modifier
                                                                                                .background(Color(0xFFECEFF1), RoundedCornerShape(3.dp))
                                                                                                .clickable(enabled = !isMultiSelectMode) {
                                                                                                    val num = displayVal.toIntOrNull() ?: 0
                                                                                                    val encoded = CellAttachmentHelper.formatCellContent(
                                                                                                        displayText = (num + 1).toString(),
                                                                                                        attachments = cellAttachments,
                                                                                                        note = cellNote,
                                                                                                        additionalNote = cellExtraNote,
                                                                                                        checklists = cellChecklists
                                                                                                    )
                                                                                                    currentTable.setCellValue(origRIdx, colIdx, encoded)
                                                                                                    currentTable.markUpdated()
                                                                                                }
                                                                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                                                        ) {
                                                                                            Text("+", fontSize = dateBtnFontSize, fontWeight = FontWeight.Bold)
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }

                                                                            ColumnType.DECIMAL -> {
                                                                                BasicTextField(
                                                                                    value = displayVal,
                                                                                    onValueChange = { newVal ->
                                                                                        val filtered = newVal.filter { it.isDigit() || it == '.' || it == '-' }
                                                                                        val encoded = CellAttachmentHelper.formatCellContent(
                                                                                            displayText = filtered,
                                                                                            attachments = cellAttachments,
                                                                                            note = cellNote,
                                                                                            additionalNote = cellExtraNote,
                                                                                            checklists = cellChecklists
                                                                                        )
                                                                                        currentTable.setCellValue(origRIdx, colIdx, encoded)
                                                                                        currentTable.markUpdated()
                                                                                    },
                                                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                                                    enabled = !isMultiSelectMode,
                                                                                    textStyle = TextStyle(fontSize = cellFontSize, color = Color.Black),
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
                                                                            }

                                                                            ColumnType.TEXT -> {
                                                                                BasicTextField(
                                                                                    value = displayVal,
                                                                                    onValueChange = { newVal ->
                                                                                        val encoded = CellAttachmentHelper.formatCellContent(
                                                                                            displayText = newVal,
                                                                                            attachments = cellAttachments,
                                                                                            note = cellNote,
                                                                                            additionalNote = cellExtraNote,
                                                                                            checklists = cellChecklists
                                                                                        )
                                                                                        currentTable.setCellValue(origRIdx, colIdx, encoded)
                                                                                        currentTable.markUpdated()
                                                                                    },
                                                                                    enabled = !isMultiSelectMode,
                                                                                    textStyle = TextStyle(fontSize = cellFontSize, color = Color.Black),
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
                                                                            }
                                                                        }
                                                                    }
                                                                }

                                                                // Attachment Manager Button
                                                                Text(
                                                                    text = if (cellAttachments.isNotEmpty()) "📎 ${cellAttachments.size}" else "📎",
                                                                    fontSize = subTextFontSize,
                                                                    fontWeight = if (cellAttachments.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                                                    color = if (cellAttachments.isNotEmpty()) Color(0xFF0D47A1) else Color(0xFF90A4AE),
                                                                    modifier = Modifier
                                                                        .clickable(enabled = !isMultiSelectMode) { activeAttachmentCellCoord = cellCoord }
                                                                        .padding(start = 4.dp, end = 2.dp)
                                                                )
                                                            }

                                                            // Inline Badges: Notes, Extra Notes, Checklists & Attachments
                                                            if (cellAttachments.isNotEmpty() || cellChecklists.isNotEmpty() || cellNote.isNotBlank() || cellExtraNote.isNotBlank()) {
                                                                Row(
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .horizontalScroll(rememberScrollState())
                                                                        .padding(top = 2.dp),
                                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    if (cellNote.isNotBlank()) {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .background(Color(0xFFFFF9C4), RoundedCornerShape(3.dp))
                                                                                .clickable(enabled = !isMultiSelectMode) { activeAttachmentCellCoord = cellCoord }
                                                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                                                        ) { Text("📝 Note", fontSize = badgeFontSize, color = Color(0xFFF57F17)) }
                                                                    }

                                                                    if (cellExtraNote.isNotBlank()) {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .background(Color(0xFFF3E5F5), RoundedCornerShape(3.dp))
                                                                                .clickable(enabled = !isMultiSelectMode) { activeAttachmentCellCoord = cellCoord }
                                                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                                                        ) { Text("📋 +Note", fontSize = badgeFontSize, color = Color(0xFF7B1FA2)) }
                                                                    }

                                                                    if (cellChecklists.isNotEmpty()) {
                                                                        val done = cellChecklists.count { it.isChecked }
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .background(Color(0xFFE8F5E9), RoundedCornerShape(3.dp))
                                                                                .clickable(enabled = !isMultiSelectMode) { activeAttachmentCellCoord = cellCoord }
                                                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                                                        ) {
                                                                            Text("☑ $done/${cellChecklists.size}", fontSize = badgeFontSize, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                                                        }
                                                                    }

                                                                    cellAttachments.take(2).forEach { att ->
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .background(if (att.type == AttachmentType.CONTACT) Color(0xFFE0F2FE) else Color(0xFFECEFF1), RoundedCornerShape(3.dp))
                                                                                .clickable(enabled = !isMultiSelectMode) { activeAttachmentCellCoord = cellCoord }
                                                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                                                        ) {
                                                                            Text(
                                                                                text = when (att.type) {
                                                                                    AttachmentType.IMAGE -> "🖼 ${att.displayName.take(6)}"
                                                                                    AttachmentType.PDF -> "📄 PDF"
                                                                                    AttachmentType.CONTACT -> "👤 ${att.displayName.take(6)}"
                                                                                    AttachmentType.FILE -> "📁 ${att.displayName.take(6)}"
                                                                                },
                                                                                fontSize = badgeFontSize,
                                                                                color = Color.DarkGray,
                                                                                maxLines = 1
                                                                            )
                                                                        }
                                                                    }

                                                                    if (cellAttachments.size > 2) {
                                                                        Text(
                                                                            text = "+${cellAttachments.size - 2}",
                                                                            fontSize = badgeFontSize,
                                                                            color = Color.Gray,
                                                                            modifier = Modifier.clickable(enabled = !isMultiSelectMode) { activeAttachmentCellCoord = cellCoord }
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }

                                                        // Reliable multi-select overlay using matchParentSize()
                                                        if (isMultiSelectMode) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .matchParentSize()
                                                                    .background(
                                                                        if (isSelected) Color(0x337B1FA2) else Color.Transparent
                                                                    )
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

                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }

            if (selectedTabIndex == 1 && !isFullScreen) {
                Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), shape = RoundedCornerShape(8.dp), backgroundColor = Color(0xFFF1F5F9)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                    onClick = { pdfPickerLauncher.launch("application/pdf") },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) { Text("📄 + Import PDF", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

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
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("No PDF pages loaded.", color = Color.Gray, fontSize = 14.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { pdfStudioCameraScanLauncher.launch(null) }) { Text("📷 Scan with Camera") }
                                    Button(onClick = { multiImagePickerLauncher.launch("image/*") }) { Text("🖼 Upload Images") }
                                }
                                Button(
                                    onClick = { pdfPickerLauncher.launch("application/pdf") },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B9A))
                                ) {
                                    Text("📄 Import Existing PDF", color = Color.White, fontWeight = FontWeight.Bold)
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
                            if (isProcessing) "Generating..." else "⚡ Compress & Inspect PDF (Target Size KB / DPI)",
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
                                    Column(modifier = Modifier.weight(1f).clickable {
                                        currentTable = t
                                        tableSnapshot = t.createSnapshot()
                                        columnValueFilters.clear()
                                        hiddenColumns.clear()
                                        hiddenRows.clear()
                                        hiddenChartDateIndices.clear()
                                        globalSearchQuery = ""
                                        selectedCells.clear(); selectedCells.add(Pair(0, 0))
                                        anchorCell = Pair(0, 0)
                                        selectedTabIndex = 0
                                        statusMessage = "Loaded ${t.tableName}"
                                    }) {
                                        Text(t.tableName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("📅 ${t.tableDateTime} • ${t.rows.size} rows", fontSize = 11.sp, color = Color.Gray)
                                    }
                                    IconButton(onClick = { tablePendingDelete = t }) { Text("🗑", fontSize = 16.sp) }
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
                                Text("🗑 Clear App Cache", color = Color.White)
                            }
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Table Danger Zone", fontWeight = FontWeight.Bold, color = Color.Red)
                            Button(onClick = { showClearTableConfirm = true }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315)), modifier = Modifier.fillMaxWidth()) { Text("Empty All Cells in Active Table", color = Color.White) }
                            Button(onClick = { tablePendingDelete = currentTable }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB71C1C)), modifier = Modifier.fillMaxWidth()) { Text("Delete Active Table Entirely", color = Color.White) }
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
                Text("Saved Tables History", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = drawerSearchQuery,
                        onValueChange = { drawerSearchQuery = it },
                        placeholder = { Text("🔍 Search tables by name or date...") },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
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
                                        columnValueFilters.clear()
                                        hiddenColumns.clear()
                                        hiddenRows.clear()
                                        hiddenChartDateIndices.clear()
                                        globalSearchQuery = ""
                                        selectedCells.clear(); selectedCells.add(Pair(0, 0))
                                        anchorCell = Pair(0, 0)
                                        showTableHistoryDrawer = false
                                        statusMessage = "Loaded: ${t.tableName}"
                                    }) {
                                        Text(t.tableName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("📅 ${t.tableDateTime} • ${t.rows.size} rows", fontSize = 11.sp, color = Color.Gray)
                                    }
                                    IconButton(onClick = { tablePendingDelete = t }) { Text("🗑", fontSize = 16.sp) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showTableHistoryDrawer = false }) { Text("Close") } }
        )
    }
}
