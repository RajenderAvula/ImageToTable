package com.example.imagetotable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.imagetotable.util.PdfCompressorExporter
import com.example.imagetotable.util.PdfPageItem
import kotlinx.coroutines.launch

@Composable
fun GeneratedPdfInspectorDialog(
    pages: MutableList<PdfPageItem>,
    initialSizeBytes: Long,
    onDismiss: () -> Unit,
    onSavePdf: (targetKb: Int?) -> Unit,
    onPrintPdf: (targetKb: Int?) -> Unit,
    onSharePdf: (targetKb: Int?) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activePageIndex by remember { mutableIntStateOf(0) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    var targetSizeInputKb by remember { mutableStateOf("500") }
    var currentFileSize by remember { mutableLongStateOf(initialSizeBytes) }
    var isCompressing by remember { mutableStateOf(false) }
    var compressionFeedback by remember { mutableStateOf("") }

    val safeIndex = activePageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.96f),
            shape = RoundedCornerShape(14.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                // Top Header: Title & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PDF Studio: Generated Document Inspector", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                        Text("Current Size: ${PdfCompressorExporter.formatBytes(currentFileSize)} • ${pages.size} Pages", fontSize = 11.sp, color = Color.DarkGray)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("✕", fontSize = 16.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Post-Generation File Size Reducer Strip (FIXED PRESET CHIPS & INPUT BOX)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = Color(0xFFF1F5F9),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, Color(0xFFCFD8DC))
                ) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Reduce File Size After Generation (Target KB):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF37474F))

                        // Presets Row: 100K, 250K, 500K, 1000K (Equal Weights, No Wrapping into 50 0K)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("100", "250", "500", "1000").forEach { preset ->
                                val isSelected = targetSizeInputKb == preset
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) Color(0xFF1976D2) else Color(0xFFECEFF1),
                                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF0D47A1) else Color(0xFFCFD8DC)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { targetSizeInputKb = preset }
                                ) {
                                    Text(
                                        text = "${preset}K",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else Color(0xFF263238),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        // Custom Target KB Input & Compress Button (Properly sized, never squished)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = targetSizeInputKb,
                                onValueChange = { targetSizeInputKb = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Target KB", fontSize = 10.sp) },
                                modifier = Modifier.width(105.dp).height(50.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            )

                            Button(
                                onClick = {
                                    val kb = targetSizeInputKb.toIntOrNull() ?: 500
                                    isCompressing = true
                                    coroutineScope.launch {
                                        try {
                                            val (_, newSize) = PdfCompressorExporter.generateTempPdfFile(
                                                context.cacheDir,
                                                pages,
                                                kb
                                            )
                                            val diff = currentFileSize - newSize
                                            currentFileSize = newSize
                                            compressionFeedback = "Compressed to ${PdfCompressorExporter.formatBytes(newSize)} (Saved ${PdfCompressorExporter.formatBytes(diff.coerceAtLeast(0))})!"
                                        } catch (e: Exception) {
                                            compressionFeedback = "Compression error: ${e.message}"
                                        } finally {
                                            isCompressing = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100)),
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(if (isCompressing) "Compressing..." else "⚡ Compress File Size", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (compressionFeedback.isNotBlank()) {
                            Text(compressionFeedback, fontSize = 10.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // High-Resolution Zoomable Page Viewer
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    backgroundColor = Color(0xFF212121)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (pages.isNotEmpty() && safeIndex in pages.indices) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            zoomScale = (zoomScale * zoom).coerceIn(0.5f, 6.0f)
                                            panOffset += pan
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = pages[safeIndex].bitmap.asImageBitmap(),
                                    contentDescription = "Zoom Page",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = zoomScale
                                            scaleY = zoomScale
                                            translationX = panOffset.x
                                            translationY = panOffset.y
                                        },
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }

                        // Floating Zoom Controls
                        Row(
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Button(
                                onClick = { zoomScale = (zoomScale * 1.25f).coerceAtMost(6.0f) },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("🔍+", color = Color.White, fontSize = 10.sp) }

                            Button(
                                onClick = { zoomScale = (zoomScale / 1.25f).coerceAtLeast(0.5f) },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("🔍-", color = Color.White, fontSize = 10.sp) }

                            Button(
                                onClick = {
                                    zoomScale = 1f
                                    panOffset = Offset.Zero
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("Fit", color = Color.White, fontSize = 10.sp) }
                        }

                        // Active Page Badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "Page ${safeIndex + 1} of ${pages.size}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Adjust Page Numbers / Page Sequence Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reorder & Adjust Page Sequence:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF1565C0))

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = {
                                if (safeIndex > 0) {
                                    val item = pages.removeAt(safeIndex)
                                    pages.add(safeIndex - 1, item)
                                    activePageIndex = safeIndex - 1
                                }
                            },
                            enabled = safeIndex > 0,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text("◀ Move Page", fontSize = 10.sp) }

                        Button(
                            onClick = {
                                if (safeIndex < pages.size - 1) {
                                    val item = pages.removeAt(safeIndex)
                                    pages.add(safeIndex + 1, item)
                                    activePageIndex = safeIndex + 1
                                }
                            },
                            enabled = safeIndex < pages.size - 1,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text("Move Page ▶", fontSize = 10.sp) }
                    }
                }

                // Page Number Thumbnails Selector
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(pages) { idx, pageItem ->
                        val isSelected = idx == safeIndex
                        Card(
                            modifier = Modifier
                                .size(width = 62.dp, height = 74.dp)
                                .border(
                                    width = if (isSelected) 2.dp else 0.5.dp,
                                    color = if (isSelected) Color(0xFF1976D2) else Color.LightGray,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    activePageIndex = idx
                                    zoomScale = 1f
                                    panOffset = Offset.Zero
                                },
                            elevation = 2.dp
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(
                                    bitmap = pageItem.bitmap.asImageBitmap(),
                                    contentDescription = "Page ${idx + 1}",
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentScale = ContentScale.Crop
                                )
                                Text("P. ${idx + 1}", fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Bottom Final Actions: Save, Print, Share
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val targetKb = targetSizeInputKb.toIntOrNull()

                    Button(
                        onClick = { onSavePdf(targetKb) },
                        modifier = Modifier.weight(1.2f).height(42.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(6.dp)
                    ) { Text("💾 Save PDF", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }

                    Button(
                        onClick = { onPrintPdf(targetKb) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0277BD)),
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("🖨 Print", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = { onSharePdf(targetKb) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00838F)),
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("↗ Share", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
