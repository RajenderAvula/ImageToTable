package com.example.imagetotable.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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

@Composable
fun FullScreenCropperDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onCropConfirmed: (Bitmap) -> Unit
) {
    var workingBitmap by remember { mutableStateOf(sourceBitmap) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Independent Edge Coordinates
    var cropLeft by remember { mutableFloatStateOf(60f) }
    var cropTop by remember { mutableFloatStateOf(100f) }
    var cropRight by remember { mutableFloatStateOf(340f) }
    var cropBottom by remember { mutableFloatStateOf(440f) }

    var previewCroppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showPreviewModal by remember { mutableStateOf(false) }

    val minGap = 20f

    fun calculateCroppedBitmap(): Bitmap? {
        if (containerSize.width == 0 || containerSize.height == 0) return null

        val left = cropLeft.coerceIn(0f, containerSize.width.toFloat())
        val right = cropRight.coerceIn(0f, containerSize.width.toFloat())
        val top = cropTop.coerceIn(0f, containerSize.height.toFloat())
        val bottom = cropBottom.coerceIn(0f, containerSize.height.toFloat())

        val scaleX = workingBitmap.width.toFloat() / containerSize.width
        val scaleY = workingBitmap.height.toFloat() / containerSize.height

        val cropX = (min(left, right) * scaleX).toInt().coerceIn(0, workingBitmap.width - 1)
        val cropY = (min(top, bottom) * scaleY).toInt().coerceIn(0, workingBitmap.height - 1)
        val cropW = (kotlin.math.abs(right - left) * scaleX).toInt().coerceAtLeast(10)
        val cropH = (kotlin.math.abs(bottom - top) * scaleY).toInt().coerceAtLeast(10)

        val safeW = cropW.coerceAtMost(workingBitmap.width - cropX)
        val safeH = cropH.coerceAtMost(workingBitmap.height - cropY)

        return Bitmap.createBitmap(workingBitmap, cropX, cropY, safeW, safeH)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Background Base Image
                Image(
                    bitmap = workingBitmap.asImageBitmap(),
                    contentDescription = "Full Screen Crop",
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { containerSize = it },
                    contentScale = ContentScale.FillBounds
                )

                // Drag Canvas
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
                        color = Color(0xFF00E676),
                        topLeft = Offset(l, t),
                        size = Size(r - l, b - t),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                // Independent Edge Nudge Controls
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(4.dp),
                    backgroundColor = Color.Black.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Edge ONLY
                            Text("Left Edge:", color = Color(0xFF81D4FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropLeft = (cropLeft - 10f).coerceAtLeast(0f) }, contentPadding = PaddingValues(2.dp)) { Text("◀") }
                            Button(onClick = { cropLeft = (cropLeft + 10f).coerceAtMost(cropRight - minGap) }, contentPadding = PaddingValues(2.dp)) { Text("▶") }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Right Edge ONLY
                            Text("Right Edge:", color = Color(0xFF81D4FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropRight = (cropRight - 10f).coerceAtLeast(cropLeft + minGap) }, contentPadding = PaddingValues(2.dp)) { Text("◀") }
                            Button(onClick = { cropRight = (cropRight + 10f).coerceAtMost(containerSize.width.toFloat()) }, contentPadding = PaddingValues(2.dp)) { Text("▶") }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Top Edge ONLY
                            Text("Top Edge:", color = Color(0xFFA5D6A7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropTop = (cropTop - 10f).coerceAtLeast(0f) }, contentPadding = PaddingValues(2.dp)) { Text("▲") }
                            Button(onClick = { cropTop = (cropTop + 10f).coerceAtMost(cropBottom - minGap) }, contentPadding = PaddingValues(2.dp)) { Text("▼") }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Bottom Edge ONLY
                            Text("Bottom Edge:", color = Color(0xFFA5D6A7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropBottom = (cropBottom - 10f).coerceAtLeast(cropTop + minGap) }, contentPadding = PaddingValues(2.dp)) { Text("▲") }
                            Button(onClick = { cropBottom = (cropBottom + 10f).coerceAtMost(containerSize.height.toFloat()) }, contentPadding = PaddingValues(2.dp)) { Text("▼") }
                        }
                    }
                }

                // Bottom Action Bar
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF424242))
                    ) { Text("Cancel", color = Color.White) }

                    Button(
                        onClick = {
                            previewCroppedBitmap = calculateCroppedBitmap()
                            showPreviewModal = true
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                    ) { Text("Preview", color = Color.White) }

                    Button(
                        onClick = {
                            val finalCrop = calculateCroppedBitmap()
                            if (finalCrop != null) onCropConfirmed(finalCrop)
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                    ) { Text("Apply Crop", color = Color.White) }
                }

                // Crop Preview Modal
                if (showPreviewModal && previewCroppedBitmap != null) {
                    AlertDialog(
                        onDismissRequest = { showPreviewModal = false },
                        title = { Text("Cropped Area Preview") },
                        text = {
                            Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                                Image(
                                    bitmap = previewCroppedBitmap!!.asImageBitmap(),
                                    contentDescription = "Crop Preview",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showPreviewModal = false
                                onCropConfirmed(previewCroppedBitmap!!)
                            }) { Text("Confirm & Extract") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPreviewModal = false }) { Text("Back") }
                        }
                    )
                }
            }
        }
    }
}
