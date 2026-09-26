package com.example.imagetotable.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import java.text.SimpleDateFormat
import java.util.*

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
    onMoveColumn: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onAddNewRowBelow: () -> Unit,
    onAddNewRowAbove: () -> Unit,
    onMoveRowUp: () -> Unit,
    onMoveRowDown: () -> Unit,
    onDeleteRow: () -> Unit
) {
    val context = LocalContext.current

    var tableNameState by remember(initialTableName) { mutableStateOf(initialTableName) }
    var tableDateTimeState by remember(initialTableDateTime) { mutableStateOf(initialTableDateTime) }
    var rowNameState by remember(rowName, currentRowIndex) { mutableStateOf(rowName) }

    val headersState = remember(headers) {
        mutableStateListOf<ColumnDef>().apply {
            addAll(headers.map { it.copy() })
        }
    }

    val valuesState = remember(rowValues, currentRowIndex, headers.size) {
        mutableStateListOf<String>().apply {
            addAll(rowValues)
            while (size < headers.size) add("")
        }
    }

    var showAddColumnDialog by remember { mutableStateOf(false) }
    var newColName by remember { mutableStateOf("") }
    var newColType by remember { mutableStateOf(ColumnType.TEXT) }

    fun commitCurrentChanges() {
        onSaveRowAndTable(
            tableNameState,
            tableDateTimeState,
            rowNameState,
            headersState.toList(),
            valuesState.toList()
        )
    }

    fun openCalendarPicker() {
        val cal = Calendar.getInstance()
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val parsed = sdf.parse(tableDateTimeState)
            if (parsed != null) cal.time = parsed
        } catch (_: Exception) {
            try {
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val parsed = sdfDate.parse(tableDateTimeState)
                if (parsed != null) cal.time = parsed
            } catch (_: Exception) {}
        }

        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)

                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        cal.set(Calendar.MINUTE, minute)
                        val outFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        tableDateTimeState = outFormat.format(cal.time)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun moveColumnLocally(from: Int, to: Int) {
        if (from !in headersState.indices || to !in headersState.indices) return
        val col = headersState.removeAt(from)
        headersState.add(to, col)

        val cellVal = if (from in valuesState.indices) valuesState.removeAt(from) else ""
        if (to <= valuesState.size) {
            valuesState.add(to, cellVal)
        } else {
            while (valuesState.size < to) valuesState.add("")
            valuesState.add(cellVal)
        }
        onMoveColumn(from, to)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.93f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF8FAFC)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // DIALOG HEADER BAR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1976D2))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Row Editor",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Row #${currentRowIndex + 1} of $totalRows",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                commitCurrentChanges()
                                onNavigateRow(currentRowIndex - 1)
                            },
                            enabled = currentRowIndex > 0,
                            colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color(0x2EFFFFFF)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("◀ Prev", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = {
                                commitCurrentChanges()
                                onNavigateRow(currentRowIndex + 1)
                            },
                            enabled = currentRowIndex < totalRows - 1,
                            colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color(0x2EFFFFFF)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Next ▶", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // SCROLLABLE BODY
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // SECTION 1: TABLE METADATA & CALENDAR DATE/TIME PICKER
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        elevation = 2.dp,
                        backgroundColor = Color.White
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Table Metadata & Bound Timestamp",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF0D47A1)
                            )

                            OutlinedTextField(
                                value = tableNameState,
                                onValueChange = { tableNameState = it },
                                label = { Text("Table Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 13.sp)
                            )

                            // Interactive Calendar Date-Time Box
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, Color(0xFF90CAF9), RoundedCornerShape(6.dp))
                                        .background(Color(0xFFF1F8FE), RoundedCornerShape(6.dp))
                                        .clickable { openCalendarPicker() }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "Date & Time (Tap Calendar)",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("📅 ", fontSize = 14.sp)
                                            Text(
                                                text = tableDateTimeState.ifBlank { "Tap to set date & time" },
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF1565C0)
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                        tableDateTimeState = sdf.format(Date())
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0288D1)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    modifier = Modifier.height(52.dp)
                                ) {
                                    Text("🕒 Now", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // SECTION 2: ROW IDENTIFIER
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        elevation = 2.dp,
                        backgroundColor = Color.White
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Row Label / Identification",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF0D47A1)
                            )

                            OutlinedTextField(
                                value = rowNameState,
                                onValueChange = { rowNameState = it },
                                label = { Text("Row Title / Code") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            )
                        }
                    }

                    // SECTION 3: COLUMN LIST (NAMES, TYPES, MOVE UP/DOWN, VALUE INPUT)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Columns & Values (${headersState.size})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF263238)
                            )

                            Button(
                                onClick = { showAddColumnDialog = true },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("+ Add Column", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        headersState.forEachIndexed { cIdx, colDef ->
                            val cellValue = valuesState.getOrElse(cIdx) { "" }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                elevation = 1.dp,
                                backgroundColor = Color.White,
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Row A: Column Name, Move Arrows, and Delete
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFFE3F2FD), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "Col ${cIdx + 1}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF1565C0)
                                            )
                                        }

                                        OutlinedTextField(
                                            value = colDef.name,
                                            onValueChange = { newName ->
                                                headersState[cIdx] = colDef.copy(name = newName)
                                            },
                                            label = { Text("Column Name", fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        )

                                        // Move Column Up Button
                                        Button(
                                            onClick = { moveColumnLocally(cIdx, cIdx - 1) },
                                            enabled = cIdx > 0,
                                            modifier = Modifier.size(width = 34.dp, height = 36.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFECEFF1))
                                        ) {
                                            Text("▲", fontSize = 12.sp, color = if (cIdx > 0) Color.Black else Color.Gray)
                                        }

                                        // Move Column Down Button
                                        Button(
                                            onClick = { moveColumnLocally(cIdx, cIdx + 1) },
                                            enabled = cIdx < headersState.size - 1,
                                            modifier = Modifier.size(width = 34.dp, height = 36.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFECEFF1))
                                        ) {
                                            Text("▼", fontSize = 12.sp, color = if (cIdx < headersState.size - 1) Color.Black else Color.Gray)
                                        }

                                        // Delete Column Button
                                        IconButton(
                                            onClick = {
                                                if (headersState.size > 1) {
                                                    headersState.removeAt(cIdx)
                                                    if (cIdx in valuesState.indices) valuesState.removeAt(cIdx)
                                                    onDeleteColumn(cIdx)
                                                }
                                            },
                                            enabled = headersState.size > 1,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Text(
                                                text = "✕",
                                                color = if (headersState.size > 1) Color.Red else Color.LightGray,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    // Row B: Column Data Type Dropdown Selector
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        var typeMenuExpanded by remember { mutableStateOf(false) }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Data Type: ", fontSize = 11.sp, color = Color.Gray)
                                            Box {
                                                Text(
                                                    text = "${colDef.type.label} ▼",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF0D47A1),
                                                    modifier = Modifier
                                                        .background(Color(0xFFE1F5FE), RoundedCornerShape(4.dp))
                                                        .clickable { typeMenuExpanded = true }
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                )

                                                DropdownMenu(
                                                    expanded = typeMenuExpanded,
                                                    onDismissRequest = { typeMenuExpanded = false }
                                                ) {
                                                    ColumnType.values().forEach { cType ->
                                                        DropdownMenuItem(onClick = {
                                                            headersState[cIdx] = colDef.copy(type = cType)
                                                            typeMenuExpanded = false
                                                        }) {
                                                            Text(cType.label, fontSize = 12.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Text(
                                            text = "Position: Column ${cIdx + 1}",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }

                                    // Row C: Cell Value Input
                                    OutlinedTextField(
                                        value = cellValue,
                                        onValueChange = { newVal ->
                                            while (valuesState.size <= cIdx) valuesState.add("")
                                            valuesState[cIdx] = newVal
                                        },
                                        label = { Text("Value for ${colDef.name}") },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = TextStyle(fontSize = 13.sp)
                                    )
                                }
                            }
                        }
                    }

                    // SECTION 4: ROW ACTIONS & STRUCTURAL REORDERING
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        elevation = 1.dp,
                        backgroundColor = Color.White
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Row Positioning & Actions", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF455A64))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onAddNewRowAbove,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("+ Row Above", color = Color.White, fontSize = 11.sp)
                                }

                                Button(
                                    onClick = onAddNewRowBelow,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("+ Row Below", color = Color.White, fontSize = 11.sp)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onMoveRowUp,
                                    enabled = currentRowIndex > 0,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF546E7A)),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("▲ Shift Up", color = Color.White, fontSize = 11.sp)
                                }

                                Button(
                                    onClick = onMoveRowDown,
                                    enabled = currentRowIndex < totalRows - 1,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF546E7A)),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("▼ Shift Down", color = Color.White, fontSize = 11.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = onDeleteRow,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color(0xFFFFEBEE)),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("🗑 Delete This Row", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // STICKY BOTTOM ACTION FOOTER
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .border(0.5.dp, Color(0xFFE0E0E0))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Text("Cancel", fontSize = 13.sp)
                    }

                    if (currentRowIndex < totalRows - 1) {
                        Button(
                            onClick = {
                                commitCurrentChanges()
                                onNavigateRow(currentRowIndex + 1)
                            },
                            modifier = Modifier.weight(1.2f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                        ) {
                            Text("Save & Next ▶", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            commitCurrentChanges()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1.2f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                    ) {
                        Text("Save & Close", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ADD NEW COLUMN MODAL
    if (showAddColumnDialog) {
        AlertDialog(
            onDismissRequest = { showAddColumnDialog = false },
            title = { Text("Add New Column", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newColName,
                        onValueChange = { newColName = it },
                        label = { Text("Column Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    var typeDropdownExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Data Type:", fontSize = 12.sp)
                        Box {
                            Text(
                                text = "${newColType.label} ▼",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0D47A1),
                                modifier = Modifier
                                    .background(Color(0xFFE1F5FE), RoundedCornerShape(4.dp))
                                    .clickable { typeDropdownExpanded = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            DropdownMenu(
                                expanded = typeDropdownExpanded,
                                onDismissRequest = { typeDropdownExpanded = false }
                            ) {
                                ColumnType.values().forEach { ct ->
                                    DropdownMenuItem(onClick = {
                                        newColType = ct
                                        typeDropdownExpanded = false
                                    }) {
                                        Text(ct.label, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newColName.ifBlank { "Col ${headersState.size + 1}" }
                        headersState.add(ColumnDef(name, newColType))
                        valuesState.add("")
                        onAddNewColumn(name, newColType)
                        newColName = ""
                        showAddColumnDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                ) {
                    Text("Add", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddColumnDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
