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

    val tableData = remember {
        TableData(
            initialHeaders = listOf("SKU / Code", "Description", "Qty", "Price ($)"),
            initialRows = listOf(
                listOf("A-101", "Ballpoint Pens", "50", "1.25"),
                listOf("B-204", "A4 Paper Reams", "10", "4.50"),
                listOf("C-305", "Desk Organizer", "3", "12.00"),
                listOf("D-402", "USB Drive 64GB", "8", "7.99")
            )
        )
    }

    var selectedCell by remember { mutableStateOf<Pair<Int, Int>?>(Pair(0, 0)) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready. Pick an image to extract.") }
    var showImagePreview by remember { mutableStateOf(false) }

    // System Image Picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    selectedBitmap = BitmapFactory.decodeStream(stream)
                    showImagePreview = true
                    statusMessage = "Image loaded. Tap 'Extract Table' to run OCR."
                }
            } catch (e: Exception) {
                statusMessage = "Error loading image: ${e.message}"
            }
        }
    }

    val horizontalScrollState = rememberScrollState()

    // Fixed column width strategy prevents intrinsic measurement crashes
    val actionColWidth = 90.dp
    val dataColWidth = 130.dp
    val totalTableWidth = actionColWidth + (dataColWidth * tableData.headers.size)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ImageToTable", fontSize = 18.sp) },
                backgroundColor = Color(0xFF1E88E5),
                contentColor = Color.White,
                actions = {
                    if (selectedBitmap != null) {
                        TextButton(onClick = { showImagePreview = !showImagePreview }) {
                            Text(
                                if (showImagePreview) "Hide Image" else "View Image",
                                color = Color.White
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(8.dp)
        ) {
            // Scrollable Action Toolbar
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
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                    enabled = !isProcessing
                ) {
                    Text("Pick Image", color = Color.White, fontSize = 12.sp)
                }

                // SUBMIT / EXTRACT ACTION
                Button(
                    onClick = {
                        val bitmap = selectedBitmap
                        if (bitmap != null) {
                            coroutineScope.launch {
                                isProcessing = true
                                statusMessage = "Starting extraction..."
                                try {
                                    val service = AndroidOcrService(context) { msg ->
                                        statusMessage = msg
                                    }
                                    val (extractedHeaders, extractedRows) = service.extractTable(bitmap)

                                    withContext(Dispatchers.Main) {
                                        tableData.loadExtractedData(extractedHeaders, extractedRows)
                                        statusMessage = "Extracted ${extractedRows.size} rows & ${extractedHeaders.size} columns!"
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
                    Text("Extract Table", color = Color.White, fontSize = 12.sp)
                }

                Button(
                    onClick = { tableData.addRow() },
                    enabled = !isProcessing
                ) {
                    Text("+ Row", fontSize = 12.sp)
                }

                Button(
                    onClick = { tableData.addColumn() },
                    enabled = !isProcessing
                ) {
                    Text("+ Col", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(tableData.toTsvString()))
                        statusMessage = "Table copied to clipboard!"
                    },
                    enabled = !isProcessing
                ) {
                    Text("Copy TSV", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        val clipText = clipboardManager.getText()?.text
                        if (!clipText.isNullOrBlank()) {
                            val (r, c) = selectedCell ?: Pair(0, 0)
                            tableData.pasteTsvData(clipText, r, c)
                            statusMessage = "Pasted at ($r, $c)"
                        } else {
                            statusMessage = "Clipboard is empty"
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Text("Paste", fontSize = 12.sp)
                }
            }

            // Status Bar & Processing Spinner
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF1E88E5)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = statusMessage,
                    style = TextStyle(fontSize = 11.sp, color = Color.DarkGray)
                )
            }

            // Image Preview Container
            if (showImagePreview && selectedBitmap != null) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    elevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(bottom = 8.dp)
                ) {
                    Image(
                        bitmap = selectedBitmap!!.asImageBitmap(),
                        contentDescription = "Selected Table Image",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Divider()

            // Horizontal Scrollable Grid Shell
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState)
            ) {
                Column(
                    modifier = Modifier
                        .width(totalTableWidth)
                        .fillMaxHeight()
                ) {
                    // Header Row with Sideways (◀ / ▶) Shifts
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE8EEF5))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.width(actionColWidth).padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Actions", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        tableData.headers.forEachIndexed { colIdx, headerText ->
                            Column(
                                modifier = Modifier
                                    .width(dataColWidth)
                                    .border(0.5.dp, Color.LightGray)
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicTextField(
                                    value = headerText,
                                    onValueChange = { tableData.headers[colIdx] = it },
                                    textStyle = TextStyle(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text(
                                        text = "◀",
                                        modifier = Modifier
                                            .clickable(enabled = colIdx > 0) {
                                                tableData.moveColumn(colIdx, colIdx - 1)
                                            }
                                            .padding(2.dp),
                                        fontSize = 12.sp,
                                        color = if (colIdx > 0) Color.Black else Color.LightGray
                                    )
                                    Text(
                                        text = "▶",
                                        modifier = Modifier
                                            .clickable(enabled = colIdx < tableData.headers.size - 1) {
                                                tableData.moveColumn(colIdx, colIdx + 1)
                                            }
                                            .padding(2.dp),
                                        fontSize = 12.sp,
                                        color = if (colIdx < tableData.headers.size - 1) Color.Black else Color.LightGray
                                    )
                                    Text(
                                        text = "✕",
                                        modifier = Modifier
                                            .clickable { tableData.deleteColumn(colIdx) }
                                            .padding(2.dp),
                                        color = Color.Red,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    // Vertically Scrollable Body with Up/Down (▲ / ▼) Shifts
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(tableData.rows) { rowIdx, rowData ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(0.5.dp, Color(0xFFE0E0E0)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.width(actionColWidth).padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "▲",
                                        modifier = Modifier
                                            .clickable(enabled = rowIdx > 0) {
                                                tableData.moveRow(rowIdx, rowIdx - 1)
                                            }
                                            .padding(2.dp),
                                        fontSize = 12.sp,
                                        color = if (rowIdx > 0) Color.Black else Color.LightGray
                                    )
                                    Text(
                                        text = "▼",
                                        modifier = Modifier
                                            .clickable(enabled = rowIdx < tableData.rows.size - 1) {
                                                tableData.moveRow(rowIdx, rowIdx + 1)
                                            }
                                            .padding(2.dp),
                                        fontSize = 12.sp,
                                        color = if (rowIdx < tableData.rows.size - 1) Color.Black else Color.LightGray
                                    )
                                    Text(
                                        text = "✕",
                                        modifier = Modifier
                                            .clickable { tableData.deleteRow(rowIdx) }
                                            .padding(2.dp),
                                        color = Color.Red,
                                        fontSize = 12.sp
                                    )
                                    Text("#${rowIdx + 1}", fontSize = 10.sp, color = Color.Gray)
                                }

                                rowData.forEachIndexed { colIdx, cellValue ->
                                    val isSelected = selectedCell == Pair(rowIdx, colIdx)
                                    Box(
                                        modifier = Modifier
                                            .width(dataColWidth)
                                            .border(
                                                width = if (isSelected) 1.5.dp else 0.5.dp,
                                                color = if (isSelected) Color(0xFF1E88E5) else Color.LightGray
                                            )
                                            .background(if (isSelected) Color(0xFFE3F2FD) else Color.White)
                                            .clickable { selectedCell = Pair(rowIdx, colIdx) }
                                            .padding(8.dp)
                                    ) {
                                        BasicTextField(
                                            value = cellValue,
                                            onValueChange = {
                                                tableData.updateCell(rowIdx, colIdx, it)
                                            },
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
