package com.example.imagetotable.ui

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
import java.time.LocalDate

@Composable
fun RowEditorDialog(
    rowIndex: Int?, // null = Create New Row, number = Edit Existing Row
    initialName: String = "",
    initialDate: String = "",
    headers: List<String>,
    initialValues: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (name: String, date: String, values: List<String>) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialName.ifBlank { "Row New" }) }
    var date by remember { mutableStateOf(initialDate.ifBlank { LocalDate.now().toString() }) }
    val cellValues = remember {
        mutableStateListOf(*Array(headers.size) { idx -> initialValues.getOrElse(idx) { "" } })
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(8.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (rowIndex != null) "Edit Entry #${rowIndex + 1}" else "New Table Entry",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E88E5)
                )
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Row Name / Title") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Assigned Date (YYYY-MM-DD)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Column Values", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                    headers.forEachIndexed { colIdx, headerName ->
                        OutlinedTextField(
                            value = cellValues.getOrElse(colIdx) { "" },
                            onValueChange = { cellValues[colIdx] = it },
                            label = { Text(headerName) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text("Delete", color = Color.Red)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(
                            onClick = { onSave(name, date, cellValues.toList()) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) {
                            Text("Save", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
