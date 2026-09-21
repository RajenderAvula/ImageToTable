package com.example.imagetotable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.imagetotable.model.ColumnDef
import com.example.imagetotable.model.ColumnType

@Composable
fun AdvancedRowEditorDialog(
    currentRowIndex: Int,
    totalRows: Int,
    rowName: String,
    headers: List<ColumnDef>,
    rowValues: List<String>,
    onDismiss: () -> Unit,
    onSaveRow: (name: String, values: List<String>) -> Unit,
    onNavigateRow: (targetIndex: Int) -> Unit,
    onAddNewColumn: (name: String, type: ColumnType) -> Unit,
    onDeleteRow: () -> Unit
) {
    var name by remember(currentRowIndex) { mutableStateOf(rowName) }
    val cellValues = remember(currentRowIndex, headers.size) {
        mutableStateListOf(*Array(headers.size) { idx -> rowValues.getOrElse(idx) { "" } })
    }

    var showAddColDialog by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(6.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header & Row Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Row ${currentRowIndex + 1} of $totalRows",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E88E5)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = {
                                onSaveRow(name, cellValues.toList())
                                onNavigateRow(currentRowIndex - 1)
                            },
                            enabled = currentRowIndex > 0,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("◀ Prev", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                onSaveRow(name, cellValues.toList())
                                onNavigateRow(currentRowIndex + 1)
                            },
                            enabled = currentRowIndex < totalRows - 1,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Next ▶", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Row Name / Label") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Column Fields", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        OutlinedButton(
                            onClick = { showAddColDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("+ Add Col In Row", fontSize = 11.sp)
                        }
                    }

                    // Column Fields with Type-Specific Inputs
                    headers.forEachIndexed { idx, colDef ->
                        val kType = when (colDef.type) {
                            ColumnType.NUMBER -> KeyboardOptions(keyboardType = KeyboardType.Number)
                            ColumnType.DECIMAL -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            else -> KeyboardOptions(keyboardType = KeyboardType.Text)
                        }

                        OutlinedTextField(
                            value = cellValues.getOrElse(idx) { "" },
                            onValueChange = { cellValues[idx] = it },
                            label = { Text("${colDef.name} (${colDef.type.label})") },
                            keyboardOptions = kType,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDeleteRow) {
                        Text("Delete Row", color = Color.Red)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("Close") }
                        Button(
                            onClick = {
                                onSaveRow(name, cellValues.toList())
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) {
                            Text("Save", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Modal to add a new column directly from inside the row view
    if (showAddColDialog) {
        var newColName by remember { mutableStateOf("") }
        var selectedType by remember { mutableStateOf(ColumnType.TEXT) }

        AlertDialog(
            onDismissRequest = { showAddColDialog = false },
            title = { Text("Add New Column") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newColName,
                        onValueChange = { newColName = it },
                        label = { Text("Column Header Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Select Data Type:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    ColumnType.values().forEach { cType ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedType == cType,
                                onClick = { selectedType = cType }
                            )
                            Text(cType.label, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newColName.isNotBlank()) {
                        onAddNewColumn(newColName.trim(), selectedType)
                        showAddColDialog = false
                    }
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddColDialog = false }) { Text("Cancel") }
            }
        )
    }
}
