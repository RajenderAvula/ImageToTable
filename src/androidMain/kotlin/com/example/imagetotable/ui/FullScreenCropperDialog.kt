package com.example.imagetotable.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.min

enum class CropExtractionMode {
    FULL_TABLE,
    SINGLE_CELL_STEP
}

@Composable
fun FullScreenCropperDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onCropConfirmed: (croppedBitmap: Bitmap, mode: CropExtractionMode) -> Unit
) {
    var workingBitmap by remember { mutableStateOf(sourceBitmap) }
    var extractionMode by remember { mutableStateOf(CropExtractionMode.FULL_TABLE) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Pinch-to-zoom & pan states
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Crop box limits
    var cropLeft by remember { mutableFloatStateOf(60f) }
    var cropTop by remember { mutableFloatStateOf(100f) }
    var cropRight by remember { mutableFloatStateOf(340f) }
    var cropBottom by remember { mutableFloatStateOf(440f) }
    val minGap = 20f

    fun rotateImage90() {
        val matrix = Matrix().apply { postRotate(90f) }
        workingBitmap = Bitmap.createBitmap(workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, matrix, true)
        zoomScale = 1f
        panOffset = Offset.Zero
    }

    fun calculateCroppedBitmap(): Bitmap? {
        if (containerSize.width == 0 || containerSize.height == 0) return null
        val left = min(cropLeft, cropRight).coerceIn(0f, containerSize.width.toFloat())
        val right = max(cropLeft, cropRight).coerceIn(0f, containerSize.width.toFloat())
        val top = min(cropTop, cropBottom).coerceIn(0f, containerSize.height.toFloat())
        val bottom = max(cropTop, cropBottom).coerceIn(0f, containerSize.height.toFloat())

        val scaleX = workingBitmap.width.toFloat() / containerSize.width
        val scaleY = workingBitmap.height.toFloat() / containerSize.height

        val cropX = (left * scaleX).toInt().coerceIn(0, workingBitmap.width - 1)
        val cropY = (top * scaleY).toInt().coerceIn(0, workingBitmap.height - 1)
        val cropW = ((right - left) * scaleX).toInt().coerceAtLeast(5).coerceAtMost(workingBitmap.width - cropX)
        val cropH = ((bottom - top) * scaleY).toInt().coerceAtLeast(5).coerceAtMost(workingBitmap.height - cropY)

        return Bitmap.createBitmap(workingBitmap, cropX, cropY, cropW, cropH)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Zoomable & Pannable Source Image
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(0.5f, 5.0f)
                                panOffset += pan
                            }
                        }
                ) {
                    Image(
                        bitmap = workingBitmap.asImageBitmap(),
                        contentDescription = "Cropping preview",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                                translationX = panOffset.x
                                translationY = panOffset.y
                            }
                            .onSizeChanged { containerSize = it },
                        contentScale = ContentScale.Fit
                    )
                }

                // Interactive Crop Overlay
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    cropLeft = offset.x
                                    cropTop = offset.y
                                    cropRight = offset.x + minGap
                                    cropBottom = offset.y + minGap
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    cropRight = change.position.x
                                    cropBottom = change.position.y
                                }
                            )
                        }
                ) {
                    val l = min(cropLeft, cropRight)
                    val r = max(cropLeft, cropRight)
                    val t = min(cropTop, cropBottom)
                    val b = max(cropTop, cropBottom)

                    drawRect(color = Color.Black.copy(alpha = 0.50f))
                    drawRect(color = Color.Transparent, topLeft = Offset(l, t), size = Size(r - l, b - t))
                    drawRect(
                        color = if (extractionMode == CropExtractionMode.FULL_TABLE) Color(0xFF00E676) else Color(0xFFFF9100),
                        topLeft = Offset(l, t),
                        size = Size(r - l, b - t),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                // Top Controls: Rotate 90°, Mode Selection & Edge Nudges
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(6.dp),
                    backgroundColor = Color.Black.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = { rotateImage90() },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0288D1)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("🔄 90°", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        zoomScale = 1f
                                        panOffset = Offset.Zero
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("Fit", color = Color.White, fontSize = 11.sp) }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = { extractionMode = CropExtractionMode.FULL_TABLE },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (extractionMode == CropExtractionMode.FULL_TABLE) Color(0xFF2E7D32) else Color.DarkGray
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) { Text("Full Table", color = Color.White, fontSize = 11.sp) }

                                Button(
                                    onClick = { extractionMode = CropExtractionMode.SINGLE_CELL_STEP },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (extractionMode == CropExtractionMode.SINGLE_CELL_STEP) Color(0xFFE65100) else Color.DarkGray
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) { Text("One Cell", color = Color.White, fontSize = 11.sp) }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Edge Nudges
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Left Edge:", color = Color(0xFF81D4FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropLeft = (cropLeft - 10f).coerceAtLeast(0f) }, contentPadding = PaddingValues(2.dp)) { Text("◀") }
                            Button(onClick = { cropLeft = (cropLeft + 10f).coerceAtMost(cropRight - minGap) }, contentPadding = PaddingValues(2.dp)) { Text("▶") }

                            Text("Right Edge:", color = Color(0xFF81D4FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropRight = (cropRight - 10f).coerceAtLeast(cropLeft + minGap) }, contentPadding = PaddingValues(2.dp)) { Text("◀") }
                            Button(onClick = { cropRight = (cropRight + 10f).coerceAtMost(containerSize.width.toFloat()) }, contentPadding = PaddingValues(2.dp)) { Text("▶") }

                            Text("Top Edge:", color = Color(0xFFA5D6A7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropTop = (cropTop - 10f).coerceAtLeast(0f) }, contentPadding = PaddingValues(2.dp)) { Text("▲") }
                            Button(onClick = { cropTop = (cropTop + 10f).coerceAtMost(cropBottom - minGap) }, contentPadding = PaddingValues(2.dp)) { Text("▼") }

                            Text("Bottom Edge:", color = Color(0xFFA5D6A7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropBottom = (cropBottom - 10f).coerceAtLeast(cropTop + minGap) }, contentPadding = PaddingValues(2.dp)) { Text("▲") }
                            Button(onClick = { cropBottom = (cropBottom + 10f).coerceAtMost(containerSize.height.toFloat()) }, contentPadding = PaddingValues(2.dp)) { Text("▼") }
                        }
                    }
                }

                // Bottom Bar
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF424242))) {
                        Text("Cancel", color = Color.White)
                    }

                    Button(
                        onClick = {
                            val cropped = calculateCroppedBitmap()
                            if (cropped != null) onCropConfirmed(cropped, extractionMode)
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                    ) {
                        Text(
                            if (extractionMode == CropExtractionMode.FULL_TABLE) "Extract Full Table" else "Extract to Active Cell",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
