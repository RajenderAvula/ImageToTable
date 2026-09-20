// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/Main.kt
package com.example.imagetotable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
        title = "ImageToTable - Visual Grid Verification"
    ) {
        val coroutineScope = rememberCoroutineScope()
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
                                    statusMessage = "Detected ${result.cellMatrix.flatten().size} cells in ${selectedFile.name}"
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

            // Split View: Image Overlay Preview (Left) and Editable Table Grid (Right)
            Row(modifier = Modifier.fillMaxSize().weight(1f)) {
                // Left Panel: Image + Bounding Box Canvas
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(1.dp, Color.LightGray)
                ) {
                    TableOverlayPreview(
                        image = sourceImage,
                        cellMatrix = detectedCellMatrix,
                        selectedCell = selectedCell,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Right Panel: Editable Table Grid
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
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .border(0.5.dp, Color.LightGray)
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

                    // Rows
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                                    // Row 0 in TableData maps to row index 1 in cellMatrix if row 0 was used as headers
                                    val isSelected = selectedCell == Pair(rowIdx + 1, colIdx)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(
                                                width = if (isSelected) 1.5.dp else 0.5.dp,
                                                color = if (isSelected) Color(0xFF1E88E5) else Color.LightGray
                                            )
                                            .background(if (isSelected) Color(0xFFE3F2FD) else Color.White)
                                            .clickable {
                                                // Link selection directly to cellMatrix index
                                                selectedCell = Pair(rowIdx + 1, colIdx)
                                            }
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
