package com.example.imagetotable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.imagetotable.model.FormulaEvaluator

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormulaBuilderDialog(
    initialColName: String = "Total",
    initialFormula: String = "",
    headers: List<ColumnDef>,
    sampleRowValues: List<String> = emptyList(),
    targetColIndex: Int = -1,
    onDismiss: () -> Unit,
    onConfirm: (colName: String, formula: String) -> Unit
) {
    var colName by remember { mutableStateOf(initialColName) }
    var formulaText by remember { mutableStateOf(initialFormula) }

    // Real-time evaluation preview of Row 1
    val previewResult = remember(formulaText, headers, sampleRowValues) {
        if (formulaText.isBlank()) ""
        else FormulaEvaluator.evaluate(
            formula = formulaText,
            headers = headers,
            rowValues = sampleRowValues,
            targetColIdx = targetColIndex
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(14.dp),
            elevation = 8.dp,
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE8F5E9), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("fx", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32), fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (initialFormula.isNotBlank()) "Edit Formula Column" else "Add Formula Column",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color(0xFF1565C0)
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("✕", fontSize = 16.sp, color = Color.Gray)
                    }
                }

                // Column Name
                OutlinedTextField(
                    value = colName,
                    onValueChange = { colName = it },
                    label = { Text("Formula Column Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )

                // Formula Input Field
                OutlinedTextField(
                    value = formulaText,
                    onValueChange = { formulaText = it },
                    label = { Text("Formula Expression (e.g. [Qty] * [Price ($)])") },
                    placeholder = { Text("[Col 1] + [Col 2]") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0D47A1))
                )

                // Real-time Preview Banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = Color(0xFFF1F8E9),
                    border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Row 1 Preview Output:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                        Text(
                            text = if (previewResult.isBlank()) "(Enter formula)" else previewResult,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (previewResult.startsWith("#")) Color.Red else Color(0xFF1B5E20)
                        )
                    }
                }

                Divider()

                // Column Operands Chips
                Text("Insert Column as Operand:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    headers.forEachIndexed { idx, col ->
                        if (idx != targetColIndex) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFE0F2FE), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0xFF90CAF9), RoundedCornerShape(4.dp))
                                    .clickable { formulaText += "[${col.name}]" }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("[${col.name}]", fontSize = 11.sp, color = Color(0xFF0277BD), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Operator & Function Keys
                Text("Operators & Functions:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("+", "-", "*", "/", "%", "(", ")").forEach { op ->
                        Button(
                            onClick = { formulaText += " $op " },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFECEFF1)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(op, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }

                    listOf("SUM", "AVG", "MIN", "MAX", "ROUND").forEach { fn ->
                        Button(
                            onClick = { formulaText += "$fn(" },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFEDE7F6)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(fn, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E35B1))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Bottom Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (colName.isNotBlank() && formulaText.isNotBlank()) {
                                onConfirm(colName.trim(), formulaText.trim())
                            }
                        },
                        enabled = colName.isNotBlank() && formulaText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("Apply Formula Column", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
