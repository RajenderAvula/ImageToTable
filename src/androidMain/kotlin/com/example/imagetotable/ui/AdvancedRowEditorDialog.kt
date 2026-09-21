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
        newTableName: String,
        newTableDateTime: String,
        newRowName: String,
        updatedHeaders: List<String>,
        newValues: List<String>
    ) -> Unit,
    onNavigateRow: (Int) -> Unit,
    onAddNewColumn: (String, ColumnType) -> Unit,
    onDeleteColumn: (Int) -> Unit,
    onAddNewRowBelow: () -> Unit,
    onDeleteRow: () -> Unit
) {
    val context = LocalContext.current

    // Table Metadata State
    var editableTableName by remember(initialTableName) { mutableStateOf(initialTableName) }
    var editableTableDateTime by remember(initialTableDateTime) { mutableStateOf(initialTableDateTime) }

    // Row Name State
    var editableRowName by remember(currentRowIndex, rowName) { mutableStateOf(rowName) }

    // Column Header Names State (Editable within this dialog)
    val editableHeaderNames = remember(headers) {
        mutableStateListOf(*headers.map { it.name }.toTypedArray())
    }

    // Cell Values State
    val editableCellValues = remember(currentRowIndex, rowValues) {
        mutableStateListOf(*Array(headers.size) { idx -> rowValues.getOrElse(idx) { "" } })
    }

    // In-dialog Search Box to filter displayed columns
    var columnSearchQuery by remember { mutableStateOf("") }

    var showNewColSheet by remember { mutableStateOf(false) }
    var newColName by remember { mutableStateOf("") }
    var newColType by remember { mutableStateOf(ColumnType.TEXT) }

    val filteredColIndices = headers.indices.filter { idx ->
        val colName = editableHeaderNames.getOrElse(idx) { "" }
        val cellVal = editableCellValues.getOrElse(idx) { "" }
        columnSearchQuery.isBlank() || colName.contains(columnSearchQuery, ignoreCase = true) || cellVal.contains(columnSearchQuery, ignoreCase = true)
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
            Column(modifier = Modifier.padding(14.dp)) {
                // 1. Table Metadata Bar
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    backgroundColor = Color(0xFFF1F5F9),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = editableTableName,
                                onValueChange = { editableTableName = it },
                                label = { Text("Table Name") },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f).height(52.dp)
                            )

                            Button(
                                onClick = {
                                    CalendarPickerUtil.pickDateTime(context) { selectedDateTime ->
                                        editableTableDateTime = selectedDateTime
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                modifier = Modifier.height(52.dp)
                            ) {
                                Text("📅 ${editableTableDateTime.take(16)}", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // 2. Row Navigation Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Editing Row ${currentRowIndex + 1} of $totalRows",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.DarkGray
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { onNavigateRow(currentRowIndex - 1) },
                            enabled = currentRowIndex > 0,
                            modifier = Modifier.size(width = 40.dp, height = 30.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("▲") }

                        Button(
                            onClick = { onNavigateRow(currentRowIndex + 1) },
                            enabled = currentRowIndex < totalRows - 1,
                            modifier = Modifier.size(width = 40.dp, height = 30.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("▼") }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 3. Row Name Editor Field
                OutlinedTextField(
                    value = editableRowName,
                    onValueChange = { editableRowName = it },
                    label = { Text("Row Name / Label (Editable)") },
                    singleLine = true,
                    textStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 4. In-Dialog Search Box across columns
                OutlinedTextField(
                    value = columnSearchQuery,
                    onValueChange = { columnSearchQuery = it },
                    placeholder = { Text("🔍 Search columns or values inside this row...") },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 5. Scrollable Column List (with Dual Column Name + Cell Value Editors)
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredColIndices.forEach { colIdx ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color(0xFFFAFAFA),
                            shape = RoundedCornerShape(6.dp),
                            elevation = 1.dp
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Column Name Editor
                                    OutlinedTextField(
                                        value = editableHeaderNames.getOrElse(colIdx) { "" },
                                        onValueChange = { editableHeaderNames[colIdx] = it },
                                        label = { Text("Col #${colIdx + 1} Name") },
                                        singleLine = true,
                                        textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                        modifier = Modifier.weight(0.45f).height(50.dp)
                                    )

                                    // Cell Value Editor
                                    OutlinedTextField(
                                        value = editableCellValues.getOrElse(colIdx) { "" },
                                        onValueChange = { editableCellValues[colIdx] = it },
                                        label = { Text("Value") },
                                        singleLine = true,
                                        textStyle = TextStyle(fontSize = 12.sp),
                                        modifier = Modifier.weight(0.55f).height(50.dp)
                                    )

                                    // Remove Column Button
                                    IconButton(
                                        onClick = { onDeleteColumn(colIdx) },
                                        enabled = headers.size > 1
                                    ) {
                                        Text(
                                            "✕",
                                            color = if (headers.size > 1) Color.Red else Color.LightGray,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Add Column / Add Row Below Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showNewColSheet = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+ Add Column", color = Color(0xFF1E88E5), fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { onAddNewRowBelow() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+ Insert Row Below", color = Color(0xFF2E7D32), fontSize = 12.sp)
                        }
                    }

                    if (showNewColSheet) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color(0xFFE8EEF5),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                OutlinedTextField(
                                    value = newColName,
                                    onValueChange = { newColName = it },
                                    label = { Text("New Column Name") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = {
                                        if (newColName.isNotBlank()) {
                                            onAddNewColumn(newColName, newColType)
                                            editableHeaderNames.add(newColName)
                                            editableCellValues.add("")
                                            newColName = ""
                                            showNewColSheet = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Confirm Add Column", color = Color.White)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // 6. Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDeleteRow) {
                        Text("Delete Row", color = Color.Red, fontSize = 13.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(
                            onClick = {
                                onSaveRowAndTable(
                                    editableTableName,
                                    editableTableDateTime,
                                    editableRowName,
                                    editableHeaderNames.toList(),
                                    editableCellValues.toList()
                                )
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) {
                            Text("💾 Save All", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
