package com.example.imagetotable.ui

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.imagetotable.model.ColumnDef
import com.example.imagetotable.model.ColumnType
import com.example.imagetotable.model.TableData

@Composable
fun DedicatedTableEditorDialog(
    tableData: TableData,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    // Preserve local copy snapshot in case user cancels
    val originalSnapshot = remember { tableData.createSnapshot() }

    Dialog(
        onDismissRequest = {
            tableData.revertToSnapshot(originalSnapshot)
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .fillMaxHeight(0.96f),
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // TOP BAR with prominent Save & Cancel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Dedicated Table Grid Editor",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1565C0)
                        )
                        Text(
                            "${tableData.rows.size} Rows • ${tableData.headers.size} Columns",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { tableData.addRow() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("+ Row", color = Color.White, fontSize = 11.sp) }

                        Button(
                            onClick = { tableData.addColumn("Col ${tableData.headers.size + 1}") },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("+ Col", color = Color.White, fontSize = 11.sp) }

                        // Top Cancel Button
                        OutlinedButton(
                            onClick = {
                                tableData.revertToSnapshot(originalSnapshot)
                                onDismiss()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("Cancel", color = Color.Red, fontSize = 11.sp)
                        }

                        // Top Save Button
                        Button(
                            onClick = onSave,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text("Save", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val colWidth = 140.dp
                val rowActionWidth = 90.dp
                val totalWidth = rowActionWidth + (colWidth * tableData.headers.size)

                // Scrollable Table Canvas
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Column(modifier = Modifier.width(totalWidth)) {
                        // Headers Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFE3EDF7))
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(rowActionWidth)
                                    .border(0.5.dp, Color.LightGray)
                                    .padding(6.dp)
                            ) {
                                Text("#", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            tableData.headers.forEachIndexed { cIdx, h ->
                                Box(
                                    modifier = Modifier
                                        .width(colWidth)
                                        .border(0.5.dp, Color.LightGray)
                                        .padding(6.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            BasicTextField(
                                                value = h.name,
                                                onValueChange = { newName ->
                                                    tableData.headers[cIdx] = h.copy(name = newName)
                                                    tableData.markUpdated()
                                                },
                                                textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                "✕",
                                                color = Color.Red,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable(enabled = tableData.headers.size > 1) {
                                                    tableData.deleteColumn(cIdx)
                                                }
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "◀",
                                                modifier = Modifier.clickable(enabled = cIdx > 0) {
                                                    tableData.moveColumn(cIdx, cIdx - 1)
                                                },
                                                fontSize = 11.sp,
                                                color = if (cIdx > 0) Color.Black else Color.LightGray
                                            )
                                            Text(
                                                "▶",
                                                modifier = Modifier.clickable(enabled = cIdx < tableData.headers.size - 1) {
                                                    tableData.moveColumn(cIdx, cIdx + 1)
                                                },
                                                fontSize = 11.sp,
                                                color = if (cIdx < tableData.headers.size - 1) Color.Black else Color.LightGray
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Data Rows
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            itemsIndexed(tableData.rows) { rIdx, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(0.5.dp, Color(0xFFE0E0E0)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(rowActionWidth)
                                            .border(0.5.dp, Color(0xFFCFD8DC))
                                            .background(Color(0xFFF9FAFB))
                                            .padding(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("${rIdx + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                "▲",
                                                modifier = Modifier.clickable(enabled = rIdx > 0) {
                                                    tableData.moveRow(rIdx, rIdx - 1)
                                                },
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                "▼",
                                                modifier = Modifier.clickable(enabled = rIdx < tableData.rows.size - 1) {
                                                    tableData.moveRow(rIdx, rIdx + 1)
                                                },
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                "✕",
                                                color = Color.Red,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable { tableData.deleteRow(rIdx) }
                                            )
                                        }
                                    }

                                    row.forEachIndexed { cIdx, cellValue ->
                                        Box(
                                            modifier = Modifier
                                                .width(colWidth)
                                                .border(0.5.dp, Color.LightGray)
                                                .background(Color.White)
                                                .padding(6.dp)
                                        ) {
                                            BasicTextField(
                                                value = cellValue,
                                                onValueChange = { updatedValue ->
                                                    tableData.setCellValue(rIdx, cIdx, updatedValue)
                                                },
                                                textStyle = TextStyle(fontSize = 12.sp, color = Color.Black),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // BOTTOM ACTION BAR: Redundant Save & Cancel for easy mobile reach
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${tableData.rows.size} Rows • ${tableData.headers.size} Columns",
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                tableData.revertToSnapshot(originalSnapshot)
                                onDismiss()
                            }
                        ) {
                            Text("Discard & Cancel", color = Color.Red)
                        }

                        Button(
                            onClick = onSave,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) {
                            Text("Save Changes", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
