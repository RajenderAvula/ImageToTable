package com.example.imagetotable.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.imagetotable.model.ColumnDef
import com.example.imagetotable.model.ColumnType

@Composable
fun ExtractionPreviewDialog(
    croppedBitmap: Bitmap,
    initialHeaders: List<ColumnDef>,
    initialRows: List<List<String>>,
    onDismiss: () -> Unit,
    onConfirmAppend: (headers: List<ColumnDef>, rows: List<List<String>>, excludeHeaders: Boolean, excludeRowNames: Boolean) -> Unit,
    onConfirmReplace: (headers: List<ColumnDef>, rows: List<List<String>>, excludeHeaders: Boolean, excludeRowNames: Boolean) -> Unit
) {
    var previewZoom by remember { mutableFloatStateOf(1f) }
    var previewPan by remember { mutableStateOf(Offset.Zero) }

    // Toggles to exclude headers or row titles
    var excludeHeaderRow by remember { mutableStateOf(false) }
    var excludeRowNames by remember { mutableStateOf(true) }

    val previewHeaders = remember { mutableStateListOf(*initialHeaders.map { it.copy() }.toTypedArray()) }
    val previewRows = remember {
        mutableStateListOf(*initialRows.map { mutableStateListOf(*it.toTypedArray()) }.toTypedArray())
    }
    val previewRowNames = remember {
        mutableStateListOf(*(1..initialRows.size).map { "Row $it" }.toTypedArray())
    }

    val actionColWidth = 140.dp
    val dataColWidth = 140.dp
    val totalWidth = (if (!excludeRowNames) actionColWidth else 0.dp) + (dataColWidth * previewHeaders.size)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.96f),
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                // Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Extraction Verification & Preview", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                        Text("Inspect cropped snippet & adjust extracted grid before committing", fontSize = 11.sp, color = Color.Gray)
                    }
                    IconButton(onClick = onDismiss) { Text("✕", fontSize = 16.sp, color = Color.Gray) }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // TOP PANE: ZOOMABLE & PANNABLE PREVIEW OF CROPPED IMAGE
                Card(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    shape = RoundedCornerShape(8.dp),
                    backgroundColor = Color(0xFF263238),
                    elevation = 2.dp
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        previewZoom = (previewZoom * zoom).coerceIn(0.6f, 6.0f)
                                        previewPan += pan
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = croppedBitmap.asImageBitmap(),
                                contentDescription = "Cropped Snippet",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = previewZoom
                                        scaleY = previewZoom
                                        translationX = previewPan.x
                                        translationY = previewPan.y
                                    },
                                contentScale = ContentScale.Fit
                            )
                        }

                        // Floating Zoom Controls
                        Row(
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Button(
                                onClick = { previewZoom = (previewZoom * 1.25f).coerceAtMost(6f) },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("🔍+", color = Color.White, fontSize = 10.sp) }

                            Button(
                                onClick = { previewZoom = (previewZoom / 1.25f).coerceAtLeast(0.6f) },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("🔍-", color = Color.White, fontSize = 10.sp) }

                            Button(
                                onClick = {
                                    previewZoom = 1f
                                    previewPan = Offset.Zero
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("Fit", color = Color.White, fontSize = 10.sp) }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // EXCLUSION TOGGLES
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = Color(0xFFF1F5F9),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = excludeHeaderRow,
                                onCheckedChange = { excludeHeaderRow = it }
                            )
                            Text("Exclude Column Headers (Pure Data Rows)", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = excludeRowNames,
                                onCheckedChange = { excludeRowNames = it }
                            )
                            Text("Exclude Row Names", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // BOTTOM PANE: INTERACTIVE EDITABLE TABLE PREVIEW
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFCFD8DC), RoundedCornerShape(6.dp))
                        .horizontalScroll(rememberScrollState())
                ) {
                    Column(modifier = Modifier.width(totalWidth).fillMaxHeight()) {
                        // UNIFORM COLUMN HEADERS
                        Row(
                            modifier = Modifier.fillMaxWidth().background(Color(0xFFE8EEF5)).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!excludeRowNames) {
                                Box(modifier = Modifier.width(actionColWidth).padding(6.dp)) {
                                    Text("Row Titles", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0D47A1))
                                }
                            }

                            previewHeaders.forEachIndexed { colIdx, colDef ->
                                Box(
                                    modifier = Modifier
                                        .width(dataColWidth)
                                        .border(0.5.dp, Color.LightGray)
                                        .padding(4.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            BasicTextField(
                                                value = if (excludeHeaderRow) "Col ${colIdx + 1}" else colDef.name,
                                                onValueChange = { previewHeaders[colIdx] = colDef.copy(name = it) },
                                                textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                                                enabled = !excludeHeaderRow,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                "✕",
                                                color = Color.Red,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable(enabled = previewHeaders.size > 1) {
                                                    previewHeaders.removeAt(colIdx)
                                                    previewRows.forEach { it.removeAt(colIdx) }
                                                }
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            var typeExpanded by remember { mutableStateOf(false) }
                                            Box {
                                                Text(
                                                    "[${colDef.type.label.take(7)} ▼]",
                                                    fontSize = 10.sp,
                                                    color = Color(0xFF1976D2),
                                                    modifier = Modifier.clickable { typeExpanded = true }
                                                )
                                                DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                                                    ColumnType.values().forEach { ct ->
                                                        DropdownMenuItem(onClick = {
                                                            previewHeaders[colIdx] = colDef.copy(type = ct)
                                                            typeExpanded = false
                                                        }) { Text(ct.label) }
                                                    }
                                                }
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    "◀",
                                                    modifier = Modifier.clickable(enabled = colIdx > 0) {
                                                        val h = previewHeaders.removeAt(colIdx)
                                                        previewHeaders.add(colIdx - 1, h)
                                                        previewRows.forEach { r ->
                                                            val c = r.removeAt(colIdx)
                                                            r.add(colIdx - 1, c)
                                                        }
                                                    },
                                                    fontSize = 11.sp,
                                                    color = if (colIdx > 0) Color.Black else Color.LightGray
                                                )
                                                Text(
                                                    "▶",
                                                    modifier = Modifier.clickable(enabled = colIdx < previewHeaders.size - 1) {
                                                        val h = previewHeaders.removeAt(colIdx)
                                                        previewHeaders.add(colIdx + 1, h)
                                                        previewRows.forEach { r ->
                                                            val c = r.removeAt(colIdx)
                                                            r.add(colIdx + 1, c)
                                                        }
                                                    },
                                                    fontSize = 11.sp,
                                                    color = if (colIdx < previewHeaders.size - 1) Color.Black else Color.LightGray
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // UNIFORM BODY ROWS (Multi-line content supported)
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            itemsIndexed(previewRows) { rowIdx, rowCells ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().border(0.5.dp, Color(0xFFEEEEEE)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!excludeRowNames) {
                                        Box(
                                            modifier = Modifier.width(actionColWidth).background(Color(0xFFF9FAFB)).padding(4.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                BasicTextField(
                                                    value = previewRowNames.getOrElse(rowIdx) { "Row ${rowIdx + 1}" },
                                                    onValueChange = { previewRowNames[rowIdx] = it },
                                                    textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0)),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    "▲",
                                                    modifier = Modifier.clickable(enabled = rowIdx > 0) {
                                                        val n = previewRowNames.removeAt(rowIdx); previewRowNames.add(rowIdx - 1, n)
                                                        val r = previewRows.removeAt(rowIdx); previewRows.add(rowIdx - 1, r)
                                                    },
                                                    fontSize = 11.sp,
                                                    color = if (rowIdx > 0) Color.Black else Color.LightGray
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    "▼",
                                                    modifier = Modifier.clickable(enabled = rowIdx < previewRows.size - 1) {
                                                        val n = previewRowNames.removeAt(rowIdx); previewRowNames.add(rowIdx + 1, n)
                                                        val r = previewRows.removeAt(rowIdx); previewRows.add(rowIdx + 1, r)
                                                    },
                                                    fontSize = 11.sp,
                                                    color = if (rowIdx < previewRows.size - 1) Color.Black else Color.LightGray
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    "✕",
                                                    color = Color.Red,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.clickable {
                                                        previewRowNames.removeAt(rowIdx)
                                                        previewRows.removeAt(rowIdx)
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    previewHeaders.indices.forEach { colIdx ->
                                        Box(
                                            modifier = Modifier
                                                .width(dataColWidth)
                                                .border(0.5.dp, Color(0xFFE0E0E0))
                                                .padding(6.dp)
                                        ) {
                                            BasicTextField(
                                                value = rowCells.getOrElse(colIdx) { "" },
                                                onValueChange = { rowCells[colIdx] = it },
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

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Discard / Cancel") }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                onConfirmAppend(previewHeaders.toList(), previewRows.map { it.toList() }, excludeHeaderRow, excludeRowNames)
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B))
                        ) {
                            Text("Append to Current Table", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                onConfirmReplace(previewHeaders.toList(), previewRows.map { it.toList() }, excludeHeaderRow, excludeRowNames)
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315))
                        ) {
                            Text("Replace Current Table", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
