package com.example.imagetotable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    onSaveRowAndTable: (newTableName: String, newTableDateTime: String, newRowName: String, newValues: List<String>) -> Unit,
    onNavigateRow: (Int) -> Unit,
    onAddNewColumn: (String, ColumnType) -> Unit,
    onDeleteRow: () -> Unit
) {
    val context = LocalContext.current

    // Table Metadata State
    var editableTableName by remember(initialTableName) { mutableStateOf(initialTableName) }
    var editableTableDateTime by remember(initialTableDateTime) { mutableStateOf(initialTableDateTime) }

    // Row State
    var editableRowName by remember(currentRowIndex, rowName) { mutableStateOf(rowName) }
    val editableCellValues = remember(currentRowIndex, rowValues) {
        mutableStateListOf(*Array(headers.size) { idx -> rowValues.getOrElse(idx) { "" } })
    }

    var showNewColSheet by remember { mutableStateOf(false) }
    var newColName by remember { mutableStateOf("") }
    var newColType by remember { mutableStateOf(ColumnType.TEXT) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // ==========================================
                // 1. TABLE METADATA: NAME & CALENDAR PICKER
                // ==========================================
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    backgroundColor = Color(0xFFF1F5F9),
                    shape = RoundedCornerShape(8.dp),
                    elevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Table Settings (Bound to Whole Table)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E88E5)
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Editable Table Name Field
                            OutlinedTextField(
                                value = editableTableName,
                                onValueChange = { editableTableName = it },
                                label = { Text("Table Name") },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f).height(54.dp)
                            )

                            // Calendar & Time Picker Button
                            Button(
                                onClick = {
                                    CalendarPickerUtil.pickDateTime(context) { selectedDateTime ->
                                        editableTableDateTime = selectedDateTime
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                modifier = Modifier.height(54.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("📅 Date & Time", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = editableTableDateTime.take(16),
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Divider(modifier = Modifier.padding(bottom = 8.dp))

                // ==========================================
                // 2. ROW NAVIGATION & TITLE
                // ==========================================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Editing Row ${currentRowIndex + 1} of $totalRows",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.DarkGray
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { onNavigateRow(currentRowIndex - 1) },
                            enabled = currentRowIndex > 0,
                            modifier = Modifier.size(width = 44.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("▲", fontSize = 12.sp) }

                        Button(
                            onClick = { onNavigateRow(currentRowIndex + 1) },
                            enabled = currentRowIndex < totalRows - 1,
                            modifier = Modifier.size(width = 44.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("▼", fontSize = 12.sp) }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ==========================================
                // 3. EDITABLE ROW NAME & COLUMN FIELDS
                // ==========================================
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = editableRowName,
                        onValueChange = { editableRowName = it },
                        label = { Text("Row Name / Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    headers.forEachIndexed { colIdx, colDef ->
                        OutlinedTextField(
                            value = editableCellValues.getOrElse(colIdx) { "" },
                            onValueChange = { editableCellValues[colIdx] = it },
                            label = { Text("${colDef.name} (${colDef.type.label})") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Add Column Button
                    OutlinedButton(
                        onClick = { showNewColSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ Add New Column to Table", color = Color(0xFF1E88E5))
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
                                    label = { Text("Column Header Name") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        if (newColName.isNotBlank()) {
                                            onAddNewColumn(newColName, newColType)
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

                Spacer(modifier = Modifier.height(10.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // ==========================================
                // 4. ACTION BUTTONS: SAVE & CANCEL
                // ==========================================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDeleteRow) {
                        Text("Delete Row", color = Color.Red, fontSize = 13.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", fontSize = 13.sp)
                        }

                        // Save Row + Save Table Name & DateTime in one click
                        Button(
                            onClick = {
                                onSaveRowAndTable(
                                    editableTableName,
                                    editableTableDateTime,
                                    editableRowName,
                                    editableCellValues.toList()
                                )
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) {
                            Text("💾 Save Row & Table", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
