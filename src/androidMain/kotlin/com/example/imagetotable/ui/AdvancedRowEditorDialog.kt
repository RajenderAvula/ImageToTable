package com.example.imagetotable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
        updatedHeaders: List<String>,
        updatedValues: List<String>
    ) -> Unit,
    onNavigateRow: (targetRowIndex: Int) -> Unit,
    onAddNewColumn: (name: String, type: ColumnType) -> Unit,
    onDeleteColumn: (columnIndex: Int) -> Unit,
    onAddNewRowBelow: () -> Unit,
    onDeleteRow: () -> Unit
) {
    var tableName by remember { mutableStateOf(initialTableName) }
    var tableDateTime by remember { mutableStateOf(initialTableDateTime) }
    var currentRowTitle by remember(currentRowIndex, rowName) { mutableStateOf(rowName) }

    val editableHeaders = remember(headers) { mutableStateListOf(*headers.map { it.name }.toTypedArray()) }
    val editableValues = remember(currentRowIndex, rowValues) {
        mutableStateListOf(*headers.indices.map { idx -> rowValues.getOrElse(idx) { "" } }.toTypedArray())
    }

    var newColName by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Advanced Row Editor", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                        Text("Row ${currentRowIndex + 1} of $totalRows", fontSize = 11.sp, color = Color.Gray)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { onNavigateRow(currentRowIndex - 1) },
                            enabled = currentRowIndex > 0,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("◀ Prev") }

                        Button(
                            onClick = { onNavigateRow(currentRowIndex + 1) },
                            enabled = currentRowIndex < totalRows - 1,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("Next ▶") }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Table & Row Titles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = tableName,
                        onValueChange = { tableName = it },
                        label = { Text("Table Name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    )

                    OutlinedTextField(
                        value = currentRowTitle,
                        onValueChange = { currentRowTitle = it },
                        label = { Text("Row Title") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Add Column Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newColName,
                        onValueChange = { newColName = it },
                        placeholder = { Text("New Column Name...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp)
                    )
                    Button(
                        onClick = {
                            if (newColName.isNotBlank()) {
                                onAddNewColumn(newColName, ColumnType.TEXT)
                                editableHeaders.add(newColName)
                                editableValues.add("")
                                newColName = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("+ Col", color = Color.White) }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()

                // List of Cells for This Row
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(editableHeaders) { colIdx, headerName ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            backgroundColor = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${colIdx + 1}. $headerName",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color(0xFF0D47A1)
                                    )
                                    Text(
                                        "✕ Delete Col",
                                        color = Color.Red,
                                        fontSize = 11.sp,
                                        modifier = Modifier.border(0.5.dp, Color.Red, RoundedCornerShape(4.dp)).padding(4.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                OutlinedTextField(
                                    value = editableValues.getOrElse(colIdx) { "" },
                                    onValueChange = {
                                        while (editableValues.size <= colIdx) editableValues.add("")
                                        editableValues[colIdx] = it
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = TextStyle(fontSize = 13.sp)
                                )
                            }
                        }
                    }
                }

                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDeleteRow,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) { Text("Delete Row ✕", color = Color.White, fontSize = 11.sp) }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }

                        Button(
                            onClick = {
                                onSaveRowAndTable(
                                    tableName,
                                    tableDateTime,
                                    currentRowTitle,
                                    editableHeaders.toList(),
                                    editableValues.toList()
                                )
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text("Save Row", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
