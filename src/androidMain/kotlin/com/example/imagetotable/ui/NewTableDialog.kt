package com.example.imagetotable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.imagetotable.model.ColumnDef
import com.example.imagetotable.model.TableData
import com.example.imagetotable.util.CalendarPickerUtil
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun NewTableDialog(
    onDismiss: () -> Unit,
    onTableCreated: (TableData) -> Unit
) {
    val context = LocalContext.current
    var tableName by remember { mutableStateOf("New Table") }
    var boundDateTime by remember {
        mutableStateOf(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
    }
    var colCount by remember { mutableIntStateOf(3) }
    var rowCount by remember { mutableIntStateOf(3) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create New Table", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))

                OutlinedTextField(
                    value = tableName,
                    onValueChange = { tableName = it },
                    label = { Text("Table Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Bound Calendar Date/Time:", fontSize = 11.sp, color = Color.Gray)
                        Text(boundDateTime, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            CalendarPickerUtil.pickDateTime(context) { picked -> boundDateTime = picked }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                    ) { Text("📅 Pick Date", color = Color.White, fontSize = 11.sp) }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = colCount.toString(),
                        onValueChange = { colCount = it.toIntOrNull()?.coerceIn(1, 20) ?: 1 },
                        label = { Text("Initial Cols") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = rowCount.toString(),
                        onValueChange = { rowCount = it.toIntOrNull()?.coerceIn(1, 50) ?: 1 },
                        label = { Text("Initial Rows") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val headers = (1..colCount).map { ColumnDef("Col $it") }
                            val rows = (1..rowCount).map { List(colCount) { "" } }
                            val newTable = TableData(
                                initialName = tableName,
                                initialHeaders = headers,
                                initialRows = rows,
                                initialDateTime = boundDateTime
                            )
                            onTableCreated(newTable)
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                    ) { Text("Create", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
