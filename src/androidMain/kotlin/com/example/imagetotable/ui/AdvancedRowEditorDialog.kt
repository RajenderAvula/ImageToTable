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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.imagetotable.model.ColumnDef
import com.example.imagetotable.model.ColumnType
import com.example.imagetotable.util.CalendarPickerUtil

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
    onNavigateRow: (targetRowIndex: Int) -> Unit,
    onAddNewColumn: (name: String, type: ColumnType) -> Unit,
    onDeleteColumn: (columnIndex: Int) -> Unit,
    onAddNewRowBelow: () -> Unit,
    onAddNewRowAbove: () -> Unit,
    onMoveRowUp: () -> Unit,
    onMoveRowDown: () -> Unit,
    onDeleteRow: () -> Unit
) {
    val context = LocalContext.current
    var tableName by remember { mutableStateOf(initialTableName) }
    var tableDateTime by remember { mutableStateOf(initialTableDateTime) }

    var currentRowTitle by remember { mutableStateOf(rowName) }
    val editableHeaders = remember { mutableStateListOf<ColumnDef>() }
    val editableValues = remember { mutableStateListOf<String>() }

    // Resynchronize when navigating to a new row
    LaunchedEffect(currentRowIndex, rowName, rowValues, headers) {
        currentRowTitle = rowName
        editableHeaders.clear()
        editableHeaders.addAll(headers.map { it.copy() })
        editableValues.clear()
        for (i in headers.indices) {
            editableValues.add(rowValues.getOrElse(i) { "" })
        }
    }

    var newColName by remember { mutableStateOf("") }
    var newColType by remember { mutableStateOf(ColumnType.TEXT) }

    fun autoSaveCurrent() {
        onSaveRowAndTable(
            tableName,
            tableDateTime,
            currentRowTitle,
            editableHeaders.toList(),
            editableValues.toList()
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f),
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Header with Row Navigation Arrows and Calendar Picker[cite: 1, 6]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Advanced Row Editor", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                        Text("Row ${currentRowIndex + 1} of $totalRows", fontSize = 11.sp, color = Color.Gray)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                CalendarPickerUtil.pickDateTime(context) { newDt -> tableDateTime = newDt }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text("📅 ${tableDateTime.take(16)}", color = Color.White, fontSize = 10.sp) }

                        Button(
                            onClick = {
                                autoSaveCurrent()
                                onNavigateRow(currentRowIndex - 1)
                            },
                            enabled = currentRowIndex > 0,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("◀ Prev") }

                        Button(
                            onClick = {
                                autoSaveCurrent()
                                onNavigateRow(currentRowIndex + 1)
                            },
                            enabled = currentRowIndex < totalRows - 1,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("Next ▶") }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Table Title & Row Name
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

                Spacer(modifier = Modifier.height(6.dp))

                // Action Bar: Add Row Above/Below & Move Row Vertically[cite: 1, 6]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onAddNewRowAbove,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3949AB)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("+ Row Above", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = onAddNewRowBelow,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3949AB)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("+ Row Below", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = onMoveRowUp,
                        enabled = currentRowIndex > 0,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF546E7A)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("▲ Move Up", color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = onMoveRowDown,
                        enabled = currentRowIndex < totalRows - 1,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF546E7A)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("▼ Move Down", color = Color.White, fontSize = 11.sp) }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Add Column Field[cite: 2, 7]
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

                    var typeDropdownOpen by remember { mutableStateOf(false) }
                    Box {
                        Button(
                            onClick = { typeDropdownOpen = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0288D1)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text(newColType.label.take(7), color = Color.White, fontSize = 11.sp) }

                        DropdownMenu(expanded = typeDropdownOpen, onDismissRequest = { typeDropdownOpen = false }) {
                            ColumnType.values().forEach { cType ->
                                DropdownMenuItem(onClick = {
                                    newColType = cType
                                    typeDropdownOpen = false
                                }) { Text(cType.label) }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (newColName.isNotBlank()) {
                                onAddNewColumn(newColName, newColType)
                                editableHeaders.add(ColumnDef(newColName, newColType))
                                editableValues.add("")
                                newColName = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("+ Col", color = Color.White, fontSize = 11.sp) }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Divider()

                // Column List with Renaming, Types, Lateral Move, and Cell Editing[cite: 1, 6]
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(editableHeaders) { colIdx, colDef ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            backgroundColor = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(6.dp),
                            elevation = 1.dp
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Column Rename Input[cite: 1, 6]
                                    BasicTextField(
                                        value = colDef.name,
                                        onValueChange = { newName ->
                                            editableHeaders[colIdx] = colDef.copy(name = newName)
                                        },
                                        textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0D47A1)),
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Data Type Selector Chip[cite: 1, 6]
                                    var colTypeMenuOpen by remember { mutableStateOf(false) }
                                    Box {
                                        Text(
                                            "[${colDef.type.label.take(7)} ▼]",
                                            fontSize = 11.sp,
                                            color = Color(0xFF1565C0),
                                            modifier = Modifier
                                                .background(Color(0xFFE3F2FD), RoundedCornerShape(3.dp))
                                                .clickable { colTypeMenuOpen = true }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                        DropdownMenu(expanded = colTypeMenuOpen, onDismissRequest = { colTypeMenuOpen = false }) {
                                            ColumnType.values().forEach { ct ->
                                                DropdownMenuItem(onClick = {
                                                    editableHeaders[colIdx] = colDef.copy(type = ct)
                                                    colTypeMenuOpen = false
                                                }) { Text(ct.label) }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Lateral Column Move Arrows[cite: 1, 6]
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            "◀",
                                            modifier = Modifier.clickable(enabled = colIdx > 0) {
                                                val h = editableHeaders.removeAt(colIdx)
                                                editableHeaders.add(colIdx - 1, h)
                                                val v = editableValues.removeAt(colIdx)
                                                editableValues.add(colIdx - 1, v)
                                            },
                                            fontSize = 12.sp,
                                            color = if (colIdx > 0) Color.Black else Color.LightGray
                                        )

                                        Text(
                                            "▶",
                                            modifier = Modifier.clickable(enabled = colIdx < editableHeaders.size - 1) {
                                                val h = editableHeaders.removeAt(colIdx)
                                                editableHeaders.add(colIdx + 1, h)
                                                val v = editableValues.removeAt(colIdx)
                                                editableValues.add(colIdx + 1, v)
                                            },
                                            fontSize = 12.sp,
                                            color = if (colIdx < editableHeaders.size - 1) Color.Black else Color.LightGray
                                        )

                                        Text(
                                            "✕",
                                            color = Color.Red,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable(enabled = editableHeaders.size > 1) {
                                                editableHeaders.removeAt(colIdx)
                                                editableValues.removeAt(colIdx)
                                                onDeleteColumn(colIdx)
                                            }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Cell Value Input[cite: 1, 6]
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
                Spacer(modifier = Modifier.height(6.dp))

                // Bottom Action Bar[cite: 1, 6]
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
                            onClick = { autoSaveCurrent(); onDismiss() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text("Save & Close", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
