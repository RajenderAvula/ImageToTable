package com.example.imagetotable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

enum class TokenPlacementMode {
    SEQUENCE_FROM_ACTIVE,
    FILL_MULTI_SELECTION,
    APPEND_NEW_ROW,
    APPEND_NEW_COL
}

@Composable
fun AllWordsSelectorDialog(
    detectedWords: List<String>,
    onDismiss: () -> Unit,
    onTransferSelected: (selectedWords: List<String>, mode: TokenPlacementMode) -> Unit
) {
    val selectedWords = remember { mutableStateListOf<String>() }
    var searchQuery by remember { mutableStateOf("") }

    val filteredWords = detectedWords.filter {
        searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "All Words Retrieved from Image (${detectedWords.size})",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1565C0)
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Filter words...") },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${selectedWords.size} words selected", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            selectedWords.clear()
                            selectedWords.addAll(filteredWords)
                        }) { Text("Select All") }
                        TextButton(onClick = { selectedWords.clear() }) { Text("Clear") }
                    }
                }

                // Grid of all words
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 90.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(filteredWords) { _, word ->
                        val isSelected = selectedWords.contains(word)
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isSelected) Color(0xFF1976D2) else Color(0xFFE8EEF5),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFF0D47A1) else Color.LightGray,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    if (isSelected) selectedWords.remove(word) else selectedWords.add(word)
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = word,
                                fontSize = 12.sp,
                                color = if (isSelected) Color.White else Color.Black,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // Placement options
                Text("Transfer Selected Words To:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { onTransferSelected(selectedWords.toList(), TokenPlacementMode.SEQUENCE_FROM_ACTIVE) },
                        enabled = selectedWords.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(2.dp)
                    ) { Text("➔ Sequence", fontSize = 10.sp) }

                    Button(
                        onClick = { onTransferSelected(selectedWords.toList(), TokenPlacementMode.FILL_MULTI_SELECTION) },
                        enabled = selectedWords.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(2.dp)
                    ) { Text("➔ Multi-Cells", fontSize = 10.sp) }

                    Button(
                        onClick = { onTransferSelected(selectedWords.toList(), TokenPlacementMode.APPEND_NEW_ROW) },
                        enabled = selectedWords.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(2.dp)
                    ) { Text("+ As Row", fontSize = 10.sp) }

                    Button(
                        onClick = { onTransferSelected(selectedWords.toList(), TokenPlacementMode.APPEND_NEW_COL) },
                        enabled = selectedWords.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(2.dp)
                    ) { Text("+ As Col", fontSize = 10.sp) }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
