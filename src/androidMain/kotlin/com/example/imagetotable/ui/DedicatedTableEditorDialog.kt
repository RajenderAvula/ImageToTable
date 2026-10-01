package com.example.imagetotable.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.imagetotable.model.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DedicatedTableEditorDialog(
    tableData: TableData,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current

    var selectedRowIndex by remember { mutableIntStateOf(0) }
    val totalRows = tableData.rows.size

    // Keep safe row index within bounds
    val safeRowIndex = if (totalRows > 0) selectedRowIndex.coerceIn(0, totalRows - 1) else 0

    // Local state for table-level properties
    var tableNameState by remember(tableData.id) { mutableStateOf(tableData.tableName) }
    var tableDateTimeState by remember(tableData.id) { mutableStateOf(tableData.tableDateTime) }

    // Column Management Modal States
    var showAddColumnDialog by remember { mutableStateOf(false) }
    var newColNameInput by remember { mutableStateOf("") }
    var newColTypeSelection by remember { mutableStateOf(ColumnType.TEXT) }
    var newColFormulaInput by remember { mutableStateOf("") }

    // Formula Editor Sub-dialog
    var showFormulaEditorDialog by remember { mutableStateOf(false) }
    var editingFormulaColIdx by remember { mutableIntStateOf(-1) }

    // Cell Attachment Dialog state within this editor
    var activeAttachmentColIdx by remember { mutableStateOf<Int?>(null) }

    // Confirmation dialog states
    var columnPendingDeleteIdx by remember { mutableStateOf<Int?>(null) }
    var showRowDeleteConfirm by remember { mutableStateOf(false) }

    fun openCalendarPickerForTable() {
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
                        tableData.tableDateTime = tableDateTimeState
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

    fun openCalendarPickerForCell(colIdx: Int) {
        val cal = Calendar.getInstance()
        val currentRaw = tableData.rows.getOrNull(safeRowIndex)?.getOrNull(colIdx) ?: ""
        val currentVal = CellAttachmentHelper.parseCellContent(currentRaw).displayText
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val parsed = sdf.parse(currentVal)
            if (parsed != null) cal.time = parsed
        } catch (_: Exception) {
            try {
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val parsed = sdfDate.parse(currentVal)
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
                        val formattedDate = outFormat.format(cal.time)
                        val existingPayload = CellAttachmentHelper.parseCellContent(currentRaw)
                        val updatedEncoded = CellAttachmentHelper.formatCellContent(
                            displayText = formattedDate,
                            attachments = existingPayload.attachments,
                            note = existingPayload.note,
                            additionalNote = existingPayload.additionalNote,
                            checklists = existingPayload.checklists
                        )
                        tableData.setCellValue(safeRowIndex, colIdx, updatedEncoded)
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF1F5F9)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. DIALOG HEADER BAR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D47A1))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Horizontal Row Editor",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF1976D2)
                            ) {
                                Text(
                                    text = if (totalRows > 0) "Row #${safeRowIndex + 1} of $totalRows" else "0 Rows",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Swipe horizontally to edit row columns and view attachments",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                tableData.tableName = tableNameState
                                tableData.tableDateTime = tableDateTimeState
                                onSave()
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF15803D)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("💾 Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                            Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 2. HORIZONTAL ROW SELECTOR TRACK
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    elevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Rows:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF455A64),
                            modifier = Modifier.padding(end = 6.dp)
                        )

                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            itemsIndexed(tableData.rows) { rIdx, _ ->
                                val isSelected = rIdx == safeRowIndex
                                val rName = tableData.rowNames.getOrElse(rIdx) { "Row ${rIdx + 1}" }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) Color(0xFF1976D2) else Color(0xFFF1F5F9),
                                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF0D47A1) else Color(0xFFCFD8DC)),
                                    modifier = Modifier.clickable { selectedRowIndex = rIdx }
                                ) {
                                    Text(
                                        text = "#${rIdx + 1} $rName",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else Color(0xFF263238),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }

                            item {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFE8F5E9),
                                    border = BorderStroke(1.dp, Color(0xFF81C784)),
                                    modifier = Modifier.clickable {
                                        tableData.addRow("Row ${tableData.rows.size + 1}")
                                        selectedRowIndex = tableData.rows.size - 1
                                    }
                                ) {
                                    Text(
                                        text = "+ New Row",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. TABLE METADATA & ROW IDENTIFIER SUB-BAR
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(0.5.dp, Color(0xFFE2E8F0))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Editable Table Name
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Table:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            BasicTextField(
                                value = tableNameState,
                                onValueChange = {
                                    tableNameState = it
                                    tableData.tableName = it
                                },
                                textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1)),
                                modifier = Modifier
                                    .width(130.dp)
                                    .background(Color.White, RoundedCornerShape(4.dp))
                                    .border(0.5.dp, Color(0xFFB0BEC5), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }

                        // Timestamp picker button
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.White,
                            border = BorderStroke(0.5.dp, Color(0xFF90CAF9)),
                            modifier = Modifier.clickable { openCalendarPickerForTable() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("📅", fontSize = 11.sp)
                                Text(
                                    text = tableDateTimeState.ifBlank { "Set Date/Time" },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1565C0)
                                )
                            }
                        }

                        // Active Row Label
                        if (totalRows > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Row Label:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                val currentRowName = tableData.rowNames.getOrElse(safeRowIndex) { "Row ${safeRowIndex + 1}" }
                                BasicTextField(
                                    value = currentRowName,
                                    onValueChange = { newRName ->
                                        if (safeRowIndex in tableData.rowNames.indices) {
                                            tableData.rowNames[safeRowIndex] = newRName
                                        }
                                    },
                                    textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00796B)),
                                    modifier = Modifier
                                        .width(130.dp)
                                        .background(Color.White, RoundedCornerShape(4.dp))
                                        .border(0.5.dp, Color(0xFFB0BEC5), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Button(
                            onClick = { showAddColumnDialog = true },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("+ Add Column", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 4. MAIN CONTENT: HORIZONTAL DECK OF COLUMN / CELL CARDS
                if (totalRows == 0) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No rows exist in this table yet.", fontSize = 14.sp, color = Color.Gray)
                            Button(
                                onClick = {
                                    tableData.addRow("Row 1")
                                    selectedRowIndex = 0
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                            ) {
                                Text("+ Add First Row", color = Color.White)
                            }
                        }
                    }
                } else {
                    val cleanValuesForFormula = remember(tableData.rows.getOrNull(safeRowIndex)?.toList()) {
                        tableData.rows.getOrNull(safeRowIndex)?.map {
                            CellAttachmentHelper.parseCellContent(it).displayText
                        } ?: emptyList()
                    }

                    LazyRow(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        itemsIndexed(tableData.headers) { cIdx, colDef ->
                            val rawCellValue = tableData.rows.getOrNull(safeRowIndex)?.getOrElse(cIdx) { "" } ?: ""
                            val payload = remember(rawCellValue) {
                                CellAttachmentHelper.parseCellContent(rawCellValue)
                            }
                            val displayVal = payload.displayText
                            val cellAttachments = payload.attachments
                            val cellChecklists = payload.checklists
                            val cellNote = payload.note
                            val cellExtraNote = payload.additionalNote

                            // Individual Column Card in Horizontal Deck
                            Card(
                                modifier = Modifier
                                    .width(280.dp)
                                    .fillMaxHeight(),
                                shape = RoundedCornerShape(12.dp),
                                elevation = 3.dp,
                                backgroundColor = Color.White,
                                border = BorderStroke(1.dp, Color(0xFFCFD8DC))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Card Header: Column index, name, type pill, move/delete icons
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (colDef.type == ColumnType.FORMULA) Color(0xFFE8F5E9) else Color(0xFFE3F2FD)
                                            ) {
                                                Text(
                                                    text = if (colDef.type == ColumnType.FORMULA) "fx" else "Col ${cIdx + 1}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (colDef.type == ColumnType.FORMULA) Color(0xFF2E7D32) else Color(0xFF1565C0),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            Text(
                                                text = colDef.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = Color(0xFF263238),
                                                maxLines = 1,
                                                modifier = Modifier.widthIn(max = 130.dp)
                                            )
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = "◀",
                                                fontSize = 13.sp,
                                                modifier = Modifier
                                                    .clickable(enabled = cIdx > 0) { tableData.moveColumn(cIdx, cIdx - 1) }
                                                    .padding(2.dp)
                                            )
                                            Text(
                                                text = "▶",
                                                fontSize = 13.sp,
                                                modifier = Modifier
                                                    .clickable(enabled = cIdx < tableData.headers.size - 1) { tableData.moveColumn(cIdx, cIdx + 1) }
                                                    .padding(2.dp)
                                            )
                                            Text(
                                                text = "✕",
                                                color = Color.Red,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .clickable(enabled = tableData.headers.size > 1) { columnPendingDeleteIdx = cIdx }
                                                    .padding(2.dp)
                                            )
                                        }
                                    }

                                    // Type Selector & Formula Button
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        var typeMenuExpanded by remember { mutableStateOf(false) }

                                        Box {
                                            Text(
                                                text = "[${colDef.type.label} ▼]",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF0D47A1),
                                                modifier = Modifier
                                                    .background(Color(0xFFE1F5FE), RoundedCornerShape(4.dp))
                                                    .clickable { typeMenuExpanded = true }
                                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                            )

                                            DropdownMenu(
                                                expanded = typeMenuExpanded,
                                                onDismissRequest = { typeMenuExpanded = false }
                                            ) {
                                                ColumnType.values().forEach { cType ->
                                                    DropdownMenuItem(onClick = {
                                                        tableData.headers[cIdx] = colDef.copy(type = cType)
                                                        typeMenuExpanded = false
                                                    }) {
                                                        Text(cType.label, fontSize = 12.sp)
                                                    }
                                                }
                                            }
                                        }

                                        if (colDef.type == ColumnType.FORMULA) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFFE8F5E9),
                                                border = BorderStroke(1.dp, Color(0xFF81C784)),
                                                modifier = Modifier.clickable {
                                                    editingFormulaColIdx = cIdx
                                                    showFormulaEditorDialog = true
                                                }
                                            ) {
                                                Text(
                                                    text = "✎ Formula",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF2E7D32),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    Divider(color = Color(0xFFECEFF1))

                                    // Primary Value Input (Type-Specific)
                                    when (colDef.type) {
                                        ColumnType.FORMULA -> {
                                            val computedVal = remember(colDef.formula, cleanValuesForFormula, tableData.headers.toList()) {
                                                FormulaEvaluator.evaluate(
                                                    formula = colDef.formula,
                                                    headers = tableData.headers,
                                                    rowValues = cleanValuesForFormula,
                                                    targetColIdx = cIdx
                                                )
                                            }

                                            Card(
                                                backgroundColor = Color(0xFFF1F8E9),
                                                border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("fx Computed Value:", fontSize = 10.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                                    Text(
                                                        text = computedVal.ifBlank { "0" },
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (computedVal.startsWith("#")) Color.Red else Color(0xFF1B5E20)
                                                    )
                                                    Text(
                                                        text = "Exp: ${colDef.formula.ifBlank { "(None)" }}",
                                                        fontSize = 10.sp,
                                                        color = Color.Gray
                                                    )
                                                }
                                            }
                                        }

                                        ColumnType.DATE -> {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = displayVal,
                                                    onValueChange = { newVal ->
                                                        val encoded = CellAttachmentHelper.formatCellContent(
                                                            displayText = newVal,
                                                            attachments = cellAttachments,
                                                            note = cellNote,
                                                            additionalNote = cellExtraNote,
                                                            checklists = cellChecklists
                                                        )
                                                        tableData.setCellValue(safeRowIndex, cIdx, encoded)
                                                    },
                                                    label = { Text("Date Value") },
                                                    modifier = Modifier.weight(1f),
                                                    singleLine = true,
                                                    textStyle = TextStyle(fontSize = 12.sp)
                                                )

                                                Button(
                                                    onClick = { openCalendarPickerForCell(cIdx) },
                                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                    modifier = Modifier.height(48.dp)
                                                ) {
                                                    Text("📅", fontSize = 13.sp)
                                                }
                                            }
                                        }

                                        ColumnType.NUMBER -> {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = displayVal,
                                                    onValueChange = { newVal ->
                                                        val filtered = newVal.filter { it.isDigit() || it == '-' }
                                                        val encoded = CellAttachmentHelper.formatCellContent(
                                                            displayText = filtered,
                                                            attachments = cellAttachments,
                                                            note = cellNote,
                                                            additionalNote = cellExtraNote,
                                                            checklists = cellChecklists
                                                        )
                                                        tableData.setCellValue(safeRowIndex, cIdx, encoded)
                                                    },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    label = { Text("Number") },
                                                    modifier = Modifier.weight(1f),
                                                    singleLine = true,
                                                    textStyle = TextStyle(fontSize = 12.sp)
                                                )

                                                Button(
                                                    onClick = {
                                                        val num = displayVal.toIntOrNull() ?: 0
                                                        val encoded = CellAttachmentHelper.formatCellContent(
                                                            displayText = (num - 1).toString(),
                                                            attachments = cellAttachments,
                                                            note = cellNote,
                                                            additionalNote = cellExtraNote,
                                                            checklists = cellChecklists
                                                        )
                                                        tableData.setCellValue(safeRowIndex, cIdx, encoded)
                                                    },
                                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFECEFF1)),
                                                    modifier = Modifier.size(34.dp, 44.dp),
                                                    contentPadding = PaddingValues(0.dp)
                                                ) { Text("-", fontWeight = FontWeight.Bold, fontSize = 13.sp) }

                                                Button(
                                                    onClick = {
                                                        val num = displayVal.toIntOrNull() ?: 0
                                                        val encoded = CellAttachmentHelper.formatCellContent(
                                                            displayText = (num + 1).toString(),
                                                            attachments = cellAttachments,
                                                            note = cellNote,
                                                            additionalNote = cellExtraNote,
                                                            checklists = cellChecklists
                                                        )
                                                        tableData.setCellValue(safeRowIndex, cIdx, encoded)
                                                    },
                                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFECEFF1)),
                                                    modifier = Modifier.size(34.dp, 44.dp),
                                                    contentPadding = PaddingValues(0.dp)
                                                ) { Text("+", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                                            }
                                        }

                                        ColumnType.DECIMAL -> {
                                            OutlinedTextField(
                                                value = displayVal,
                                                onValueChange = { newVal ->
                                                    val filtered = newVal.filter { it.isDigit() || it == '.' || it == '-' }
                                                    val encoded = CellAttachmentHelper.formatCellContent(
                                                        displayText = filtered,
                                                        attachments = cellAttachments,
                                                        note = cellNote,
                                                        additionalNote = cellExtraNote,
                                                        checklists = cellChecklists
                                                    )
                                                    tableData.setCellValue(safeRowIndex, cIdx, encoded)
                                                },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                label = { Text("Decimal Value") },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                textStyle = TextStyle(fontSize = 12.sp)
                                            )
                                        }

                                        ColumnType.TEXT -> {
                                            OutlinedTextField(
                                                value = displayVal,
                                                onValueChange = { newVal ->
                                                    val encoded = CellAttachmentHelper.formatCellContent(
                                                        displayText = newVal,
                                                        attachments = cellAttachments,
                                                        note = cellNote,
                                                        additionalNote = cellExtraNote,
                                                        checklists = cellChecklists
                                                    )
                                                    tableData.setCellValue(safeRowIndex, cIdx, encoded)
                                                },
                                                label = { Text("Value") },
                                                placeholder = { Text("Enter text...", fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = TextStyle(fontSize = 12.sp)
                                            )
                                        }
                                    }

                                    // Attachment & Voice Action Button
                                    Button(
                                        onClick = { activeAttachmentColIdx = cIdx },
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = if (cellAttachments.isNotEmpty() || cellNote.isNotBlank() || cellExtraNote.isNotBlank() || cellChecklists.isNotEmpty()) {
                                                Color(0xFF0288D1)
                                            } else {
                                                Color(0xFF546E7A)
                                            }
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = if (cellAttachments.isNotEmpty()) "📎 Attachments & Notes (${cellAttachments.size})" else "📎 + Attach Files, Voice & Notes",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    // Live Inline Preview Chips for this Column Card
                                    if (cellAttachments.isNotEmpty() || cellChecklists.isNotEmpty() || cellNote.isNotBlank() || cellExtraNote.isNotBlank()) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                                                .border(0.5.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                                                .padding(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("Active Previews:", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray)

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (cellNote.isNotBlank()) {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0xFFFFF9C4), RoundedCornerShape(3.dp))
                                                            .clickable { activeAttachmentColIdx = cIdx }
                                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                                    ) {
                                                        Text("📝 Note", fontSize = 10.sp, color = Color(0xFFF57F17), fontWeight = FontWeight.Bold)
                                                    }
                                                }

                                                if (cellExtraNote.isNotBlank()) {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0xFFF3E5F5), RoundedCornerShape(3.dp))
                                                            .clickable { activeAttachmentColIdx = cIdx }
                                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                                    ) {
                                                        Text("📋 +Note", fontSize = 10.sp, color = Color(0xFF7B1FA2), fontWeight = FontWeight.Bold)
                                                    }
                                                }

                                                if (cellChecklists.isNotEmpty()) {
                                                    val done = cellChecklists.count { it.isChecked }
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0xFFE8F5E9), RoundedCornerShape(3.dp))
                                                            .clickable { activeAttachmentColIdx = cIdx }
                                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                                    ) {
                                                        Text("☑ $done/${cellChecklists.size}", fontSize = 10.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                                    }
                                                }

                                                cellAttachments.forEach { att ->
                                                    Box(
                                                        modifier = Modifier
                                                            .background(
                                                                if (att.type == AttachmentType.CONTACT) Color(0xFFE0F2FE) else Color(0xFFECEFF1),
                                                                RoundedCornerShape(3.dp)
                                                            )
                                                            .clickable { activeAttachmentColIdx = cIdx }
                                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = when (att.type) {
                                                                AttachmentType.IMAGE -> "🖼 ${att.displayName.take(7)}"
                                                                AttachmentType.PDF -> "📄 PDF"
                                                                AttachmentType.CONTACT -> "👤 ${att.displayName.take(7)}"
                                                                AttachmentType.FILE -> "📁 ${att.displayName.take(7)}"
                                                            },
                                                            fontSize = 10.sp,
                                                            color = Color.DarkGray,
                                                            maxLines = 1
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
                }

                // 5. STICKY BOTTOM ROW CONTROLS & SAVE FOOTER
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    elevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Row Operations
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { if (safeRowIndex > 0) selectedRowIndex = safeRowIndex - 1 },
                                enabled = safeRowIndex > 0,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) { Text("◄ Prev Row", fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = { if (safeRowIndex < totalRows - 1) selectedRowIndex = safeRowIndex + 1 },
                                enabled = safeRowIndex < totalRows - 1,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) { Text("Next Row ►", fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = {
                                    tableData.addRow("Row ${tableData.rows.size + 1}", index = safeRowIndex + 1)
                                    selectedRowIndex = safeRowIndex + 1
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) { Text("+ Row Below", fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = {
                                    tableData.addRow("Row ${tableData.rows.size + 1}", index = safeRowIndex)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) { Text("+ Row Above", fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = {
                                    tableData.moveRow(safeRowIndex, safeRowIndex - 1)
                                    selectedRowIndex = safeRowIndex - 1
                                },
                                enabled = safeRowIndex > 0,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) { Text("▲ Move Up", fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = {
                                    tableData.moveRow(safeRowIndex, safeRowIndex + 1)
                                    selectedRowIndex = safeRowIndex + 1
                                },
                                enabled = safeRowIndex < totalRows - 1,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) { Text("▼ Move Down", fontSize = 11.sp) }

                            Button(
                                onClick = { showRowDeleteConfirm = true },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) { Text("🗑 Delete Row", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        }

                        // Close & Save Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) { Text("Close") }

                            Button(
                                onClick = {
                                    tableData.tableName = tableNameState
                                    tableData.tableDateTime = tableDateTimeState
                                    onSave()
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF15803D)),
                                modifier = Modifier.weight(1.3f).height(38.dp)
                            ) {
                                Text("💾 Save & Apply", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // CELL ATTACHMENT DIALOG TRIGGERED FROM HORIZONTAL COLUMN CARDS
    if (activeAttachmentColIdx != null && safeRowIndex < tableData.rows.size) {
        val cIdx = activeAttachmentColIdx!!
        val rawCell = tableData.rows[safeRowIndex].getOrElse(cIdx) { "" }
        val payload = CellAttachmentHelper.parseCellContent(rawCell)
        val colName = tableData.headers.getOrNull(cIdx)?.name ?: "Col ${cIdx + 1}"

        CellAttachmentDialog(
            rowIndex = safeRowIndex,
            columnIndex = cIdx,
            columnName = colName,
            initialText = payload.displayText,
            initialNote = payload.note,
            initialAdditionalNote = payload.additionalNote,
            initialAttachments = payload.attachments,
            initialChecklists = payload.checklists,
            onDismiss = { activeAttachmentColIdx = null },
            onSave = { updatedText, updatedNote, updatedAdditionalNote, updatedAttachments, updatedChecklists ->
                val encoded = CellAttachmentHelper.formatCellContent(
                    displayText = updatedText,
                    attachments = updatedAttachments,
                    note = updatedNote,
                    additionalNote = updatedAdditionalNote,
                    checklists = updatedChecklists
                )
                tableData.setCellValue(safeRowIndex, cIdx, encoded)
                activeAttachmentColIdx = null
            }
        )
    }

    // FORMULA BUILDER DIALOG
    if (showFormulaEditorDialog && editingFormulaColIdx in tableData.headers.indices) {
        val targetDef = tableData.headers[editingFormulaColIdx]
        val cleanValues = tableData.rows.getOrNull(safeRowIndex)?.map {
            CellAttachmentHelper.parseCellContent(it).displayText
        } ?: emptyList()

        FormulaBuilderDialog(
            initialColName = targetDef.name,
            initialFormula = targetDef.formula,
            headers = tableData.headers.toList(),
            sampleRowValues = cleanValues,
            targetColIndex = editingFormulaColIdx,
            onDismiss = { showFormulaEditorDialog = false },
            onConfirm = { updatedName, updatedFormula ->
                tableData.headers[editingFormulaColIdx] = targetDef.copy(
                    name = updatedName,
                    type = ColumnType.FORMULA,
                    formula = updatedFormula
                )
                tableData.recomputeFormulas()
                showFormulaEditorDialog = false
            }
        )
    }

    // CONFIRM DELETE COLUMN
    if (columnPendingDeleteIdx != null) {
        val colToDelete = columnPendingDeleteIdx!!
        val colName = tableData.headers.getOrNull(colToDelete)?.name ?: "Column"
        AlertDialog(
            onDismissRequest = { columnPendingDeleteIdx = null },
            title = { Text("Delete Column?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete column '$colName'? All cell data in this column across all rows will be removed.") },
            confirmButton = {
                Button(
                    onClick = {
                        tableData.deleteColumn(colToDelete)
                        columnPendingDeleteIdx = null
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                ) { Text("Delete", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { columnPendingDeleteIdx = null }) { Text("Cancel") }
            }
        )
    }

    // CONFIRM DELETE ROW
    if (showRowDeleteConfirm) {
        val rName = tableData.rowNames.getOrElse(safeRowIndex) { "Row ${safeRowIndex + 1}" }
        AlertDialog(
            onDismissRequest = { showRowDeleteConfirm = false },
            title = { Text("Delete Row?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete '$rName' (Row #${safeRowIndex + 1})?") },
            confirmButton = {
                Button(
                    onClick = {
                        showRowDeleteConfirm = false
                        tableData.deleteRow(safeRowIndex)
                        if (safeRowIndex >= tableData.rows.size && tableData.rows.isNotEmpty()) {
                            selectedRowIndex = tableData.rows.size - 1
                        }
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                ) { Text("Delete Row", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRowDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // ADD NEW COLUMN MODAL
    if (showAddColumnDialog) {
        AlertDialog(
            onDismissRequest = { showAddColumnDialog = false },
            title = { Text("Add New Column", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newColNameInput,
                        onValueChange = { newColNameInput = it },
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
                                text = "${newColTypeSelection.label} ▼",
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
                                        newColTypeSelection = ct
                                        typeDropdownExpanded = false
                                    }) { Text(ct.label, fontSize = 12.sp) }
                                }
                            }
                        }
                    }

                    if (newColTypeSelection == ColumnType.FORMULA) {
                        OutlinedTextField(
                            value = newColFormulaInput,
                            onValueChange = { newColFormulaInput = it },
                            label = { Text("Formula (e.g. [Qty] * [Price])") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 12.sp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newColNameInput.ifBlank { "Col ${tableData.headers.size + 1}" }
                        tableData.addColumn(name, newColTypeSelection, newColFormulaInput)
                        newColNameInput = ""
                        newColFormulaInput = ""
                        showAddColumnDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                ) { Text("Add", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showAddColumnDialog = false }) { Text("Cancel") }
            }
        )
    }
}
