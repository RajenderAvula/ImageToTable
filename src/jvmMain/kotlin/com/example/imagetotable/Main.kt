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
import com.example.imagetotable.util.ClipboardManager

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ImageToTable - Interactive Editor"
    ) {
        val tableData = remember {
            TableData(
                initialHeaders = listOf("SKU / Code", "Description", "Quantity", "Price ($)"),
                initialRows = listOf(
                    listOf("A-101", "Ballpoint Pens", "50", "1.25"),
                    listOf("B-204", "A4 Copy Paper", "10", "4.50"),
                    listOf("C-305", "Desk Organizer", "3", "12.00"),
                    listOf("D-402", "USB Flash Drive", "8", "7.99")
                )
            )
        }

        var selectedCell by remember { mutableStateOf<Pair<Int, Int>?>(Pair(0, 0)) }
        var statusMessage by remember { mutableStateOf("Ready") }

        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // Action Toolbar
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { tableData.addRow() }) { Text("+ Row") }
                Button(onClick = { tableData.addColumn() }) { Text("+ Column") }

                Button(onClick = {
                    val tsv = tableData.toTsvString()
                    ClipboardManager.copyText(tsv)
                    statusMessage = "Table copied as TSV!"
                }) {
                    Text("Copy All")
                }

                Button(onClick = {
                    val clip = ClipboardManager.readClipboardText()
                    if (!clip.isNullOrBlank()) {
                        val (r, c) = selectedCell ?: Pair(0, 0)
                        tableData.pasteTsvData(clip, r, c)
                        statusMessage = "Pasted at cell ($r, $c)"
                    } else {
                        statusMessage = "Clipboard is empty"
                    }
                }) {
                    Text("Paste at Selection")
                }

                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = statusMessage,
                    style = TextStyle(fontSize = 12.sp, color = Color.DarkGray)
                )
            }

            Divider()

            // Header Row (Horizontal reordering)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE8EEF5))
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(90.dp).padding(4.dp)) {
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
                            textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = "◀",
                                modifier = Modifier
                                    .clickable(enabled = colIdx > 0) {
                                        tableData.moveColumn(colIdx, colIdx - 1)
                                    }
                                    .padding(2.dp),
                                color = if (colIdx > 0) Color.Black else Color.Gray,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "▶",
                                modifier = Modifier
                                    .clickable(enabled = colIdx < tableData.headers.size - 1) {
                                        tableData.moveColumn(colIdx, colIdx + 1)
                                    }
                                    .padding(2.dp),
                                color = if (colIdx < tableData.headers.size - 1) Color.Black else Color.Gray,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "✕",
                                modifier = Modifier
                                    .clickable { tableData.deleteColumn(colIdx) }
                                    .padding(2.dp),
                                color = Color.Red,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Data Rows (Vertical reordering & inline editing)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(tableData.rows) { rowIdx, rowData ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, Color(0xFFE0E0E0)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Directional controls for rows: Up / Down / Remove
                        Row(
                            modifier = Modifier.width(90.dp).padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "▲",
                                modifier = Modifier
                                    .clickable(enabled = rowIdx > 0) {
                                        tableData.moveRow(rowIdx, rowIdx - 1)
                                    }
                                    .padding(2.dp),
                                color = if (rowIdx > 0) Color.Black else Color.Gray,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "▼",
                                modifier = Modifier
                                    .clickable(enabled = rowIdx < tableData.rows.size - 1) {
                                        tableData.moveRow(rowIdx, rowIdx + 1)
                                    }
                                    .padding(2.dp),
                                color = if (rowIdx < tableData.rows.size - 1) Color.Black else Color.Gray,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "✕",
                                modifier = Modifier
                                    .clickable { tableData.deleteRow(rowIdx) }
                                    .padding(2.dp),
                                color = Color.Red,
                                fontSize = 11.sp
                            )
                            Text("#${rowIdx + 1}", fontSize = 11.sp, color = Color.Gray)
                        }

                        // Inline Editable Cells
                        rowData.forEachIndexed { colIdx, cellValue ->
                            val isSelected = selectedCell == Pair(rowIdx, colIdx)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
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
                                    onValueChange = { tableData.updateCell(rowIdx, colIdx, it) },
                                    textStyle = TextStyle(fontSize = 13.sp),
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
