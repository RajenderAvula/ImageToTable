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
    var workingBitmap by remember { mutableStateOf(sourceBitmap) }
    var startPoint by remember { mutableStateOf(Offset(80f, 80f)) }
    var endPoint by remember { mutableStateOf(Offset(420f, 420f)) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var previewCroppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showPreviewModal by remember { mutableStateOf(false) }

    fun calculateCroppedBitmap(): Bitmap? {
        if (containerSize.width == 0 || containerSize.height == 0) return null

        val left = min(startPoint.x, endPoint.x).coerceAtLeast(0f)
        val right = max(startPoint.x, endPoint.x).coerceAtMost(containerSize.width.toFloat())
        val top = min(startPoint.y, endPoint.y).coerceAtLeast(0f)
        val bottom = max(startPoint.y, endPoint.y).coerceAtMost(containerSize.height.toFloat())

        val scaleX = workingBitmap.width.toFloat() / containerSize.width
        val scaleY = workingBitmap.height.toFloat() / containerSize.height

        val cropX = (left * scaleX).toInt().coerceIn(0, workingBitmap.width - 1)
        val cropY = (top * scaleY).toInt().coerceIn(0, workingBitmap.height - 1)
        val cropW = ((right - left) * scaleX).toInt().coerceAtLeast(10)
        val cropH = ((bottom - top) * scaleY).toInt().coerceAtLeast(10)

        val safeW = cropW.coerceAtMost(workingBitmap.width - cropX)
        val safeH = cropH.coerceAtMost(workingBitmap.height - cropY)

        return Bitmap.createBitmap(workingBitmap, cropX, cropY, safeW, safeH)
    }

    fun nudge(dx: Float, dy: Float) {
        startPoint = Offset(startPoint.x + dx, startPoint.y + dy)
        endPoint = Offset(endPoint.x + dx, endPoint.y + dy)
    }

    fun resizeBox(delta: Float) {
        endPoint = Offset(endPoint.x + delta, endPoint.y + delta)
    }

    fun rotateImage() {
        val matrix = Matrix().apply { postRotate(90f) }
        workingBitmap = Bitmap.createBitmap(
            workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, matrix, true
        )
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

                    drawRect(color = Color.Black.copy(alpha = 0.45f))
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectWidth, rectHeight)
                    )
                    drawRect(
                        color = Color(0xFF00E676),
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectWidth, rectHeight),
                        style = Stroke(width = 4.dp.toPx())
                    )
                }

                // Top Precision Nudge & Sizing Toolbar
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.8f))
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Nudge:", color = Color.White, fontSize = 12.sp)
                    Button(onClick = { nudge(-15f, 0f) }, contentPadding = PaddingValues(4.dp)) { Text("◀") }
                    Button(onClick = { nudge(15f, 0f) }, contentPadding = PaddingValues(4.dp)) { Text("▶") }
                    Button(onClick = { nudge(0f, -15f) }, contentPadding = PaddingValues(4.dp)) { Text("▲") }
                    Button(onClick = { nudge(0f, 15f) }, contentPadding = PaddingValues(4.dp)) { Text("▼") }

                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Size:", color = Color.White, fontSize = 12.sp)
                    Button(onClick = { resizeBox(-20f) }, contentPadding = PaddingValues(4.dp)) { Text("−") }
                    Button(onClick = { resizeBox(20f) }, contentPadding = PaddingValues(4.dp)) { Text("+") }

                    Button(
                        onClick = { rotateImage() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE65100))
                    ) {
                        Text("⟳ Rotate 90°", color = Color.White, fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            startPoint = Offset(20f, 20f)
                            endPoint = Offset(containerSize.width.toFloat() - 20f, containerSize.height.toFloat() - 20f)
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF616161))
                    ) {
                        Text("Select All", color = Color.White, fontSize = 11.sp)
                    }
                }

                // Bottom Action Bar
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(14.dp),
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
                                    contentDescription = "Crop Preview",
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
                                Text("Back")
                            }
                        }
                    )
                }
            }
        }
    }
}
