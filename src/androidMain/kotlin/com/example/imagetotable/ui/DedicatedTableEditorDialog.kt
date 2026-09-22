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
import com.example.imagetotable.model.TableData
import com.example.imagetotable.util.CalendarPickerUtil

@Composable
fun DedicatedTableEditorDialog(
    tableData: TableData,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    var activeTab by remember { mutableIntStateOf(0) } // 0: Edit Cells Grid, 1: Columns, 2: Rows, 3: Actions & Transpose
    var newColName by remember { mutableStateOf("") }
    var newColType by remember { mutableStateOf(ColumnType.TEXT) }

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
                // Header: Table Title & Timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dedicated Table Editor", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1565C0))
                    Button(
                        onClick = {
                            CalendarPickerUtil.pickDateTime(context) { newDateTime ->
                                tableData.tableDateTime = newDateTime
                                tableData.markUpdated()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("📅 ${tableData.tableDateTime.take(16)}", color = Color.White, fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = tableData.tableName,
                    onValueChange = { tableData.tableName = it; tableData.markUpdated() },
                    label = { Text("Table Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(8.dp))

                TabRow(selectedTabIndex = activeTab, backgroundColor = Color(0xFFECEFF1)) {
                    Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("Edit Cells Grid") })
                    Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("Columns (${tableData.headers.size})") })
                    Tab(selected = activeTab == 2, onClick = { activeTab = 2 }, text = { Text("Rows (${tableData.rows.size})") })
                    Tab(selected = activeTab == 3, onClick = { activeTab = 3 }, text = { Text("Actions & Transpose") })
                }

                Spacer(modifier = Modifier.height(8.dp))

                // TAB 0: DIRECT CELL VALUES EDITING GRID (Holding & Updating state)
                if (activeTab == 0) {
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Directly edit any cell value below:", fontSize = 11.sp, color = Color.Gray)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { tableData.addRow() },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) { Text("+ Row", fontSize = 10.sp, color = Color.White) }
                                Button(
                                    onClick = { tableData.addColumn("Col ${tableData.headers.size + 1}") },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) { Text("+ Col", fontSize = 10.sp, color = Color.White) }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            val colW = 140.dp
                            val rowNameW = 120.dp
                            val fullW = rowNameW + (colW * tableData.headers.size)

                            Column(modifier = Modifier.width(fullW).fillMaxHeight()) {
                                // Header row
                                Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFCFD8DC)).padding(vertical = 4.dp)) {
                                    Box(modifier = Modifier.width(rowNameW).padding(4.dp)) {
                                        BasicTextField(
                                            value = tableData.cornerHeader,
                                            onValueChange = { tableData.cornerHeader = it; tableData.markUpdated() },
                                            textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0D47A1))
                                        )
                                    }
                                    tableData.headers.forEachIndexed { cIdx, colDef ->
                                        Box(modifier = Modifier.width(colW).padding(4.dp)) {
                                            BasicTextField(
                                                value = colDef.name,
                                                onValueChange = { colDef.name = it; tableData.markUpdated() },
                                                textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            )
                                        }
                                    }
                                }

                                // Body rows holding cell values
                                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    itemsIndexed(tableData.rows) { rIdx, rowList ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().border(0.5.dp, Color.LightGray),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(modifier = Modifier.width(rowNameW).background(Color(0xFFECEFF1)).padding(6.dp)) {
                                                BasicTextField(
                                                    value = tableData.rowNames.getOrElse(rIdx) { "Row ${rIdx + 1}" },
                                                    onValueChange = {
                                                        tableData.rowNames[rIdx] = it
                                                        tableData.markUpdated()
                                                    },
                                                    textStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF1565C0))
                                                )
                                            }

                                            tableData.headers.indices.forEach { cIdx ->
                                                val cellVal = rowList.getOrElse(cIdx) { "" }
                                                Box(
                                                    modifier = Modifier
                                                        .width(colW)
                                                        .border(0.5.dp, Color(0xFFEEEEEE))
                                                        .padding(6.dp)
                                                ) {
                                                    BasicTextField(
                                                        value = cellVal,
                                                        onValueChange = {
                                                            tableData.setCellValue(rIdx, cIdx, it)
                                                        },
                                                        textStyle = TextStyle(fontSize = 12.sp),
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
                }

                // TAB 1: MANAGE COLUMNS (Preserved from original)
                if (activeTab == 1) {
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newColName,
                                onValueChange = { newColName = it },
                                label = { Text("New Column Name") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    if (newColName.isNotBlank()) {
                                        tableData.addColumn(newColName, newColType)
                                        newColName = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                            ) { Text("+ Add Col") }
                        }

                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(tableData.headers) { idx, col ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    backgroundColor = Color(0xFFF5F9FD)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 12.sp)
                                        OutlinedTextField(
                                            value = col.name,
                                            onValueChange = { col.name = it; tableData.markUpdated() },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                        Box {
                                            var typeExpanded by remember { mutableStateOf(false) }
                                            Button(
                                                onClick = { typeExpanded = true },
                                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                                                contentPadding = PaddingValues(4.dp)
                                            ) { Text(col.type.label.take(7), color = Color.White, fontSize = 10.sp) }
                                            DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                                                ColumnType.values().forEach { ct ->
                                                    DropdownMenuItem(onClick = { col.type = ct; typeExpanded = false }) {
                                                        Text(ct.label)
                                                    }
                                                }
                                            }
                                        }
                                        IconButton(onClick = { tableData.moveColumn(idx, idx - 1) }, enabled = idx > 0) { Text("◀") }
                                        IconButton(onClick = { tableData.moveColumn(idx, idx + 1) }, enabled = idx < tableData.headers.size - 1) { Text("▶") }
                                        IconButton(onClick = { tableData.deleteColumn(idx) }, enabled = tableData.headers.size > 1) {
                                            Text("✕", color = Color.Red, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // TAB 2: MANAGE ROWS (Preserved from original)
                if (activeTab == 2) {
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Button(
                            onClick = { tableData.addRow("Row ${tableData.rows.size + 1}") },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) { Text("+ Append New Row", color = Color.White) }

                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(tableData.rowNames) { idx, rName ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    backgroundColor = Color(0xFFFAFAFA)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 12.sp)
                                        OutlinedTextField(
                                            value = rName,
                                            onValueChange = { tableData.rowNames[idx] = it; tableData.markUpdated() },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                        IconButton(onClick = { tableData.moveRow(idx, idx - 1) }, enabled = idx > 0) { Text("▲") }
                                        IconButton(onClick = { tableData.moveRow(idx, idx + 1) }, enabled = idx < tableData.rowNames.size - 1) { Text("▼") }
                                        IconButton(onClick = { tableData.deleteRow(idx) }) {
                                            Text("✕", color = Color.Red, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // TAB 3: ACTIONS & TRANSPOSE (Preserved from original)
                if (activeTab == 3) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { tableData.transposeTable() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("⇄ Transpose Table (Swap Rows & Cols)", color = Color.White, fontWeight = FontWeight.Bold) }

                        OutlinedTextField(
                            value = tableData.cornerHeader,
                            onValueChange = { tableData.cornerHeader = it },
                            label = { Text("Corner Cell (-1, -1) Title") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("Total Columns: ${tableData.headers.size}", fontWeight = FontWeight.SemiBold)
                        Text("Total Rows: ${tableData.rows.size}", fontWeight = FontWeight.SemiBold)
                    }
                }

                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onSave, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))) {
                        Text("Save Changes", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
