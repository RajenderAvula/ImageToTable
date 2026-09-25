package com.example.imagetotable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.imagetotable.model.ColumnDef
import com.example.imagetotable.model.ColumnType

@Composable
fun AdvancedRowEditorDialog(
    initialTableName: String,
    initialTableDateTime: String,
    currentRowIndex: Int,
    totalRows: Int,
    rowName: String,
    headers: List<ColumnDef>,
    rowValues: List<String>,
    onDismiss: () -> Unit,
    onSaveRowAndTable: (
        tableName: String,
        tableDateTime: String,
        updatedRowName: String,
        updatedHeaders: List<ColumnDef>,
        updatedValues: List<String>
    ) -> Unit,
    onNavigateRow: (targetIndex: Int) -> Unit,
    onAddNewColumn: (name: String, type: ColumnType) -> Unit,
    onDeleteColumn: (colIndex: Int) -> Unit,
    onAddNewRowBelow: () -> Unit,
    onAddNewRowAbove: () -> Unit,
    onMoveRowUp: () -> Unit,
    onMoveRowDown: () -> Unit,
    onDeleteRow: () -> Unit
) {
    var tableName by remember(initialTableName) { mutableStateOf(initialTableName) }
    var tableDateTime by remember(initialTableDateTime) { mutableStateOf(initialTableDateTime) }
    var currentRowTitle by remember(rowName, currentRowIndex) { mutableStateOf(rowName) }

    val editableHeaders = remember(headers) {
        mutableStateListOf(*headers.map { it.copy() }.toTypedArray())
    }

    val cellValues = remember(rowValues, currentRowIndex, headers.size) {
        mutableStateListOf(*Array(headers.size) { idx -> rowValues.getOrElse(idx) { "" } })
    }

    fun commitCurrentChanges() {
        onSaveRowAndTable(
            tableName,
            tableDateTime,
            currentRowTitle,
            editableHeaders.toList(),
            cellValues.toList()
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header with Row Counter and Prev / Next Navigation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Row Editor: #${currentRowIndex + 1} of $totalRows",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1565C0)
                        )
                        Text(
                            text = "Navigate across rows without closing",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }

                    // Navigation buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                commitCurrentChanges()
                                onNavigateRow(currentRowIndex - 1)
                            },
                            enabled = currentRowIndex > 0,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0288D1)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("◀ Prev", color = Color.White, fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                commitCurrentChanges()
                                onNavigateRow(currentRowIndex + 1)
                            },
                            enabled = currentRowIndex < totalRows - 1,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0288D1)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Next ▶", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable Form Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Row Label & Table Info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(8.dp),
                        elevation = 1.dp
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = currentRowTitle,
                                onValueChange = { currentRowTitle = it },
                                label = { Text("Row Name / Label") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = tableName,
                                    onValueChange = { tableName = it },
                                    label = { Text("Table Name") },
                                    modifier = Modifier.weight(1.2f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = tableDateTime,
                                    onValueChange = { tableDateTime = it },
                                    label = { Text("Date & Time") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }
                    }

                    // Structural Controls for this Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = onAddNewRowAbove,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) { Text("+ Row Above", fontSize = 10.sp, color = Color.White) }

                        Button(
                            onClick = onAddNewRowBelow,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) { Text("+ Row Below", fontSize = 10.sp, color = Color.White) }

                        Button(
                            onClick = onMoveRowUp,
                            enabled = currentRowIndex > 0,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF546E7A)),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("▲", color = Color.White) }

                        Button(
                            onClick = onMoveRowDown,
                            enabled = currentRowIndex < totalRows - 1,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF546E7A)),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("▼", color = Color.White) }
                    }

                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Column Cell Values", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    // Cell Inputs for Every Column
                    editableHeaders.forEachIndexed { colIdx, colDef ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 1.dp,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${colDef.name} [${colDef.type.label}]",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        color = Color(0xFF0D47A1)
                                    )
                                    if (editableHeaders.size > 1) {
                                        TextButton(
                                            onClick = { onDeleteColumn(colIdx) },
                                            contentPadding = PaddingValues(2.dp)
                                        ) {
                                            Text("Delete Col", color = Color.Red, fontSize = 10.sp)
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = cellValues.getOrElse(colIdx) { "" },
                                    onValueChange = { newVal ->
                                        while (cellValues.size <= colIdx) cellValues.add("")
                                        cellValues[colIdx] = newVal
                                    },
                                    label = { Text("Value for ${colDef.name}") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Divider()
                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Action Bar: Delete, Cancel, Save & Next, Save & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDeleteRow,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Delete Row", color = Color.White, fontSize = 11.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onDismiss) {
                            Text("Cancel")
                        }

                        if (currentRowIndex < totalRows - 1) {
                            Button(
                                onClick = {
                                    commitCurrentChanges()
                                    onNavigateRow(currentRowIndex + 1)
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                            ) {
                                Text("Save & Next ▶", color = Color.White, fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = {
                                commitCurrentChanges()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) {
                            Text("Save & Close", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
