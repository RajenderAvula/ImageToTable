package com.example.imagetotable.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
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
    var startPoint by remember { mutableStateOf(Offset(80f, 80f)) }
    var endPoint by remember { mutableStateOf(Offset(400f, 400f)) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var previewCroppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showPreviewModal by remember { mutableStateOf(false) }

    fun calculateCroppedBitmap(): Bitmap? {
        if (containerSize.width == 0 || containerSize.height == 0) return null

        val left = min(startPoint.x, endPoint.x).coerceAtLeast(0f)
        val right = max(startPoint.x, endPoint.x).coerceAtMost(containerSize.width.toFloat())
        val top = min(startPoint.y, endPoint.y).coerceAtLeast(0f)
        val bottom = max(startPoint.y, endPoint.y).coerceAtMost(containerSize.height.toFloat())

        val scaleX = sourceBitmap.width.toFloat() / containerSize.width
        val scaleY = sourceBitmap.height.toFloat() / containerSize.height

        val cropX = (left * scaleX).toInt().coerceIn(0, sourceBitmap.width - 1)
        val cropY = (top * scaleY).toInt().coerceIn(0, sourceBitmap.height - 1)
        val cropW = ((right - left) * scaleX).toInt().coerceAtLeast(10)
        val cropH = ((bottom - top) * scaleY).toInt().coerceAtLeast(10)

        val safeW = cropW.coerceAtMost(sourceBitmap.width - cropX)
        val safeH = cropH.coerceAtMost(sourceBitmap.height - cropY)

        return Bitmap.createBitmap(sourceBitmap, cropX, cropY, safeW, safeH)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Background Source Image
                Image(
                    bitmap = sourceBitmap.asImageBitmap(),
                    contentDescription = "Full Screen Crop",
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { containerSize = it },
                    contentScale = ContentScale.FillBounds
                )

                // Drag Canvas Overlay
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    startPoint = offset
                                    endPoint = offset
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    endPoint = change.position
                                }
                            )
                        }
                ) {
                    val rectLeft = min(startPoint.x, endPoint.x)
                    val rectTop = min(startPoint.y, endPoint.y)
                    val rectWidth = kotlin.math.abs(endPoint.x - startPoint.x)
                    val rectHeight = kotlin.math.abs(endPoint.y - startPoint.y)

                    // Dimmed backdrop
                    drawRect(color = Color.Black.copy(alpha = 0.45f))
                    // Selected crop area cut out
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectWidth, rectHeight)
                    )
                    // High-contrast neon bounding border
                    drawRect(
                        color = Color(0xFF00E676),
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectWidth, rectHeight),
                        style = Stroke(width = 4.dp.toPx())
                    )
                }

                // Control Bar
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF424242))
                    ) {
                        Text("Cancel", color = Color.White, fontSize = 14.sp)
                    }

                    Button(
                        onClick = {
                            previewCroppedBitmap = calculateCroppedBitmap()
                            showPreviewModal = true
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                    ) {
                        Text("Preview", color = Color.White, fontSize = 14.sp)
                    }

                    Button(
                        onClick = {
                            val finalCrop = calculateCroppedBitmap()
                            if (finalCrop != null) onCropConfirmed(finalCrop)
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                    ) {
                        Text("Apply Crop", color = Color.White, fontSize = 14.sp)
                    }
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
                                    contentDescription = "Preview",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showPreviewModal = false
                                onCropConfirmed(previewCroppedBitmap!!)
                            }) {
                                Text("Confirm & Extract")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPreviewModal = false }) {
                                Text("Back to Crop")
                            }
                        }
                    )
                }
            }
        }
    }
}
