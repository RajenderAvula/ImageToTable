// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/Main.kt
package com.example.imagetotable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.imagetotable.model.TableData
import com.example.imagetotable.ocr.CellDetector
import com.example.imagetotable.ocr.OcrTableExtractor
import com.example.imagetotable.ui.TableOverlayPreview
import com.example.imagetotable.util.ClipboardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ImageToTable - Click-to-Select Navigation"
    ) {
        val coroutineScope = rememberCoroutineScope()
        val lazyListState = rememberLazyListState()

        val tableData = remember {
            TableData(
                initialHeaders = listOf("SKU / Code", "Description", "Quantity", "Price ($)"),
                initialRows = listOf(
                    listOf("A-101", "Ballpoint Pens", "50", "1.25"),
                    listOf("B-204", "A4 Copy Paper", "10", "4.50")
                )
            )
        }

        var sourceImage by remember { mutableStateOf<BufferedImage?>(null) }
        var detectedCellMatrix by remember { mutableStateOf<List<List<CellDetector.CellBox>>>(emptyList()) }
        var selectedCell by remember { mutableStateOf<Pair<Int, Int>?>(Pair(0, 0)) }
        var statusMessage by remember { mutableStateOf("Ready") }
        var isProcessing by remember { mutableStateOf(false) }

        // Function to select a cell and automatically scroll to its table row
        fun selectAndScrollToCell(matrixRow: Int, colIdx: Int) {
            selectedCell = Pair(matrixRow, colIdx)
            // If row 0 in matrix corresponds to table header, row 1+ maps to data rows (index - 1)
            val tableRowIndex = (matrixRow - 1).coerceAtLeast(0)
            coroutineScope.launch {
                if (tableData.rows.isNotEmpty()) {
                    lazyListState.animateScrollToItem(tableRowIndex)
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // Top Toolbar
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val fileChooser = JFileChooser().apply {
                            dialogTitle = "Select Table Image"
                            fileFilter = FileNameExtensionFilter(
                                "Image Files (*.png, *.jpg, *.jpeg, *.bmp)",
                                "png", "jpg", "jpeg", "bmp"
                            )
                        }
                        if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val selectedFile = fileChooser.selectedFile
                            isProcessing = true
                            statusMessage = "Analyzing image and grid lines..."

                            coroutineScope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        OcrTableExtractor.extractTableFromImage(selectedFile)
                                    }
                                    sourceImage = result.originalImage
                                    detectedCellMatrix = result.cellMatrix
                                    tableData.loadExtractedData(result.headers, result.rows)
                                    statusMessage = "Loaded ${result.cellMatrix.flatten().size} cells. Click any image box to inspect."
                                } catch (e: Exception) {
                                    statusMessage = "Error: ${e.message}"
                                } finally {
                                    isProcessing = false
                                }
                            }
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Text(if (isProcessing) "Extracting..." else "Open Image & Extract")
                }

                Button(onClick = { tableData.addRow() }) { Text("+ Row") }
                Button(onClick = { tableData.addColumn() }) { Text("+ Column") }

                Button(onClick = {
                    ClipboardManager.copyText(tableData.toTsvString())
                    statusMessage = "Table copied as TSV!"
                }) {
                    Text("Copy TSV")
                }

                Spacer(modifier = Modifier.weight(1f))
                Text(text = statusMessage, style = TextStyle(fontSize = 12.sp, color = Color.DarkGray))
            }

            if (isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }

            // Split View: Image Overlay (Left) & Synchronized Table (Right)
            Row(modifier = Modifier.fillMaxSize().weight(1f)) {
                // Interactive Canvas Panel
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(1.dp, Color.LightGray)
                ) {
                   // In Main.kt inside TableOverlayPreview(...) call:
TableOverlayPreview(
    image = sourceImage,
    cellMatrix = detectedCellMatrix,
    selectedCell = selectedCell,
    onCellClick = { rowIdx, colIdx ->
        selectAndScrollToCell(rowIdx, colIdx)
        statusMessage = "Selected cell [Row $rowIdx, Col $colIdx]"
    },
    onBoxResized = { rowIdx, colIdx, updatedBox ->
        statusMessage = "Resized cell [$rowIdx, $colIdx]. Re-running OCR..."
        coroutineScope.launch {
            if (sourceImage != null) {
                val newText = withContext(Dispatchers.IO) {
                    OcrTableExtractor.extractTextForCell(sourceImage!!, updatedBox.awtRectangle)
                }
                
                // If row 0 represents headers:
                if (rowIdx == 0 && colIdx in tableData.headers.indices) {
                    tableData.headers[colIdx] = newText
                } else {
                    val dataRow = rowIdx - 1
                    if (dataRow in tableData.rows.indices && colIdx in tableData.headers.indices) {
                        tableData.updateCell(dataRow, colIdx, newText)
                    }
                }
                statusMessage = "Updated text: \"$newText\""
            }
        }
    },
    modifier = Modifier.fillMaxSize()
)
 
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Table Panel with Synchronized Scroll State
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .border(1.dp, Color.LightGray)
                ) {
                    // Headers
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE8EEF5))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(85.dp).padding(4.dp)) {
                            Text("Actions", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        tableData.headers.forEachIndexed { colIdx, headerText ->
                            val isHeaderSelected = selectedCell == Pair(0, colIdx)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .border(
                                        width = if (isHeaderSelected) 1.5.dp else 0.5.dp,
                                        color = if (isHeaderSelected) Color(0xFF1E88E5) else Color.LightGray
                                    )
                                    .background(if (isHeaderSelected) Color(0xFFE3F2FD) else Color.Transparent)
                                    .clickable { selectAndScrollToCell(0, colIdx) }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicTextField(
                                    value = headerText,
                                    onValueChange = { tableData.headers[colIdx] = it },
                                    textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "◀",
                                        modifier = Modifier.clickable(enabled = colIdx > 0) {
                                            tableData.moveColumn(colIdx, colIdx - 1)
                                        }.padding(2.dp),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "▶",
                                        modifier = Modifier.clickable(enabled = colIdx < tableData.headers.size - 1) {
                                            tableData.moveColumn(colIdx, colIdx + 1)
                                        }.padding(2.dp),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "✕",
                                        modifier = Modifier.clickable { tableData.deleteColumn(colIdx) }.padding(2.dp),
                                        color = Color.Red,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }

                    // Table Body with Scroll State
                    LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(tableData.rows) { rowIdx, rowData ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(0.5.dp, Color(0xFFEEEEEE)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.width(85.dp).padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "▲",
                                        modifier = Modifier.clickable(enabled = rowIdx > 0) {
                                            tableData.moveRow(rowIdx, rowIdx - 1)
                                        }.padding(2.dp),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "▼",
                                        modifier = Modifier.clickable(enabled = rowIdx < tableData.rows.size - 1) {
                                            tableData.moveRow(rowIdx, rowIdx + 1)
                                        }.padding(2.dp),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "✕",
                                        modifier = Modifier.clickable { tableData.deleteRow(rowIdx) }.padding(2.dp),
                                        color = Color.Red,
                                        fontSize = 10.sp
                                    )
                                    Text("#${rowIdx + 1}", fontSize = 10.sp, color = Color.Gray)
                                }

                                rowData.forEachIndexed { colIdx, cellValue ->
                                    val isSelected = selectedCell == Pair(rowIdx + 1, colIdx)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(
                                                width = if (isSelected) 1.5.dp else 0.5.dp,
                                                color = if (isSelected) Color(0xFF1E88E5) else Color.LightGray
                                            )
                                            .background(if (isSelected) Color(0xFFE3F2FD) else Color.White)
                                            .clickable { selectAndScrollToCell(rowIdx + 1, colIdx) }
                                            .padding(6.dp)
                                    ) {
                                        BasicTextField(
                                            value = cellValue,
                                            onValueChange = { tableData.updateCell(rowIdx, colIdx, it) },
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
