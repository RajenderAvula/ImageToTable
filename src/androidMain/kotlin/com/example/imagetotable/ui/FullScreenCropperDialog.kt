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

    // Crop box limits in screen coordinates
    var cropLeft by remember { mutableFloatStateOf(60f) }
    var cropTop by remember { mutableFloatStateOf(120f) }
    var cropRight by remember { mutableFloatStateOf(340f) }
    var cropBottom by remember { mutableFloatStateOf(460f) }
    val minGap = 20f

    fun rotateImage90() {
        val matrix = Matrix().apply { postRotate(90f) }
        workingBitmap = Bitmap.createBitmap(workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, matrix, true)
        zoomScale = 1f
        panOffset = Offset.Zero
    }

    // Inverse viewport projection: Maps screen coordinate to actual Bitmap pixel
    fun screenToBitmapCoord(screenX: Float, screenY: Float): Offset? {
        if (containerSize.width == 0 || containerSize.height == 0) return null
        val cw = containerSize.width.toFloat()
        val ch = containerSize.height.toFloat()
        val bw = workingBitmap.width.toFloat()
        val bh = workingBitmap.height.toFloat()

        val baseScale = minOf(cw / bw, ch / bh)
        val w0 = bw * baseScale
        val h0 = bh * baseScale
        val x0 = (cw - w0) / 2f
        val y0 = (ch - h0) / 2f

        val cx = cw / 2f
        val cy = ch / 2f

        val unpannedX = screenX - panOffset.x
        val unpannedY = screenY - panOffset.y

        val unscaledX = (unpannedX - cx) / zoomScale + cx
        val unscaledY = (unpannedY - cy) / zoomScale + cy

        val bmpX = (unscaledX - x0) / baseScale
        val bmpY = (unscaledY - y0) / baseScale

        return Offset(bmpX, bmpY)
    }

    fun calculateCroppedBitmap(): Bitmap? {
        val p1 = screenToBitmapCoord(min(cropLeft, cropRight), min(cropTop, cropBottom)) ?: return null
        val p2 = screenToBitmapCoord(max(cropLeft, cropRight), max(cropTop, cropBottom)) ?: return null

        val left = min(p1.x, p2.x).toInt().coerceIn(0, workingBitmap.width - 1)
        val top = min(p1.y, p2.y).toInt().coerceIn(0, workingBitmap.height - 1)
        val right = max(p1.x, p2.x).toInt().coerceIn(0, workingBitmap.width)
        val bottom = max(p1.y, p2.y).toInt().coerceIn(0, workingBitmap.height)

        val width = (right - left).coerceAtLeast(5).coerceAtMost(workingBitmap.width - left)
        val height = (bottom - top).coerceAtLeast(5).coerceAtMost(workingBitmap.height - top)

        return Bitmap.createBitmap(workingBitmap, left, top, width, height)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Zoomable & Pannable Source Image Viewport
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(0.5f, 6.0f)
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

                // Interactive Crop Bounding Box Overlay
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

                    drawRect(color = Color.Black.copy(alpha = 0.45f))
                    drawRect(color = Color.Transparent, topLeft = Offset(l, t), size = Size(r - l, b - t))
                    drawRect(
                        color = if (extractionMode == CropExtractionMode.FULL_TABLE) Color(0xFF00E676) else Color(0xFFFF9100),
                        topLeft = Offset(l, t),
                        size = Size(r - l, b - t),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                // Top Toolbar: Zoom Controls, 90° Rotate, Mode Selector & Edge Nudges
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(6.dp),
                    backgroundColor = Color.Black.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Zoom & Rotate Controls
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { rotateImage90() },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0288D1)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) { Text("🔄 90°", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = { zoomScale = (zoomScale * 1.25f).coerceAtMost(6.0f) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) { Text("🔍+", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = { zoomScale = (zoomScale / 1.25f).coerceAtLeast(0.5f) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) { Text("🔍-", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        zoomScale = 1f
                                        panOffset = Offset.Zero
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) { Text("Fit", color = Color.White, fontSize = 11.sp) }
                            }

                            // Mode Selection
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

                        // Directional Edge Selection Arrow Controls
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Left Edge:", color = Color(0xFF81D4FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropLeft = (cropLeft - 12f).coerceAtLeast(0f) }, contentPadding = PaddingValues(2.dp)) { Text("◀") }
                            Button(onClick = { cropLeft = (cropLeft + 12f).coerceAtMost(cropRight - minGap) }, contentPadding = PaddingValues(2.dp)) { Text("▶") }

                            Text("Right Edge:", color = Color(0xFF81D4FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropRight = (cropRight - 12f).coerceAtLeast(cropLeft + minGap) }, contentPadding = PaddingValues(2.dp)) { Text("◀") }
                            Button(onClick = { cropRight = (cropRight + 12f).coerceAtMost(containerSize.width.toFloat()) }, contentPadding = PaddingValues(2.dp)) { Text("▶") }

                            Text("Top Edge:", color = Color(0xFFA5D6A7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropTop = (cropTop - 12f).coerceAtLeast(0f) }, contentPadding = PaddingValues(2.dp)) { Text("▲") }
                            Button(onClick = { cropTop = (cropTop + 12f).coerceAtMost(cropBottom - minGap) }, contentPadding = PaddingValues(2.dp)) { Text("▼") }

                            Text("Bottom Edge:", color = Color(0xFFA5D6A7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropBottom = (cropBottom - 12f).coerceAtLeast(cropTop + minGap) }, contentPadding = PaddingValues(2.dp)) { Text("▲") }
                            Button(onClick = { cropBottom = (cropBottom + 12f).coerceAtMost(containerSize.height.toFloat()) }, contentPadding = PaddingValues(2.dp)) { Text("▼") }
                        }
                    }
                }

                // Bottom Action Buttons
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.88f))
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
                            if (extractionMode == CropExtractionMode.FULL_TABLE) "Proceed to Preview & Extract" else "Extract to Active Cell",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
