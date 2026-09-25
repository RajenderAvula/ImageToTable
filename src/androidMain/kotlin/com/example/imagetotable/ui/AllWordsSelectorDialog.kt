package com.example.imagetotable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
// Import single source of truth from model
import com.example.imagetotable.model.TokenPlacementMode

@Composable
fun AllWordsSelectorDialog(
    detectedWords: List<String>,
    onDismiss: () -> Unit,
    onTransferSelected: (List<String>, TokenPlacementMode) -> Unit
) {
    val selectedWords = remember { mutableStateListOf<String>() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "OCR Word Token Inspector",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1565C0)
                        )
                        Text(
                            "${selectedWords.size} of ${detectedWords.size} words selected",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { selectedWords.clear(); selectedWords.addAll(detectedWords) }) {
                            Text("Select All", fontSize = 11.sp)
                        }
                        TextButton(onClick = { selectedWords.clear() }) {
                            Text("Clear", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 85.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(detectedWords) { _, word ->
                        val isSelected = selectedWords.contains(word)
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isSelected) Color(0xFF1976D2) else Color(0xFFECEFF1),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    if (isSelected) selectedWords.remove(word) else selectedWords.add(word)
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = word,
                                fontSize = 11.sp,
                                color = if (isSelected) Color.White else Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                Text("Transfer Selected Words As:", fontWeight = FontWeight.Bold, fontSize = 12.sp)

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { onTransferSelected(selectedWords.toList(), TokenPlacementMode.SEQUENCE_FROM_ACTIVE) },
                        enabled = selectedWords.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                        contentPadding = PaddingValues(2.dp)
                    ) { Text("➔ Seq", fontSize = 10.sp, color = Color.White) }

                    Button(
                        onClick = { onTransferSelected(selectedWords.toList(), TokenPlacementMode.FILL_MULTI_SELECTION) },
                        enabled = selectedWords.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF7B1FA2)),
                        contentPadding = PaddingValues(2.dp)
                    ) { Text("➔ Multi", fontSize = 10.sp, color = Color.White) }

                    Button(
                        onClick = { onTransferSelected(selectedWords.toList(), TokenPlacementMode.APPEND_NEW_ROW) },
                        enabled = selectedWords.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3949AB)),
                        contentPadding = PaddingValues(2.dp)
                    ) { Text("+ Row", fontSize = 10.sp, color = Color.White) }

                    Button(
                        onClick = { onTransferSelected(selectedWords.toList(), TokenPlacementMode.APPEND_NEW_COL) },
                        enabled = selectedWords.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD84315)),
                        contentPadding = PaddingValues(2.dp)
                    ) { Text("+ Col", fontSize = 10.sp, color = Color.White) }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
