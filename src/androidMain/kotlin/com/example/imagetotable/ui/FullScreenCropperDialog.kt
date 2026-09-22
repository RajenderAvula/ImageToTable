package com.example.imagetotable.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
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

    // Crop box coordinates stored directly in Bitmap Space
    val initialLeft = workingBitmap.width * 0.08f
    val initialTop = workingBitmap.height * 0.12f
    val initialRight = workingBitmap.width * 0.92f
    val initialBottom = workingBitmap.height * 0.88f

    var cropBmpRect by remember(workingBitmap) {
        mutableStateOf(RectF(initialLeft, initialTop, initialRight, initialBottom))
    }
    val minBmpGap = 15f

    fun rotateImage90() {
        val matrix = Matrix().apply { postRotate(90f) }
        workingBitmap = Bitmap.createBitmap(workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, matrix, true)
        cropBmpRect = RectF(
            workingBitmap.width * 0.08f,
            workingBitmap.height * 0.12f,
            workingBitmap.width * 0.92f,
            workingBitmap.height * 0.88f
        )
        zoomScale = 1f
        panOffset = Offset.Zero
    }

    // Convert Bitmap pixel coordinate to current Screen coordinate
    fun bmpToScreenCoord(bx: Float, by: Float): Offset? {
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

        val unscaledX = x0 + (bx * baseScale)
        val unscaledY = y0 + (by * baseScale)

        val sx = (unscaledX - cx) * zoomScale + cx + panOffset.x
        val sy = (unscaledY - cy) * zoomScale + cy + panOffset.y

        return Offset(sx, sy)
    }

    // Convert current Screen coordinate to Bitmap pixel coordinate
    fun screenToBmpCoord(sx: Float, sy: Float): Offset? {
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

        val unpannedX = sx - panOffset.x
        val unpannedY = sy - panOffset.y

        val unscaledX = (unpannedX - cx) / zoomScale + cx
        val unscaledY = (unpannedY - cy) / zoomScale + cy

        val bx = (unscaledX - x0) / baseScale
        val by = (unscaledY - y0) / baseScale

        return Offset(bx, by)
    }

    fun calculateCroppedBitmap(): Bitmap? {
        val l = min(cropBmpRect.left, cropBmpRect.right).toInt().coerceIn(0, workingBitmap.width - 1)
        val t = min(cropBmpRect.top, cropBmpRect.bottom).toInt().coerceIn(0, workingBitmap.height - 1)
        val r = max(cropBmpRect.left, cropBmpRect.right).toInt().coerceIn(0, workingBitmap.width)
        val b = max(cropBmpRect.top, cropBmpRect.bottom).toInt().coerceIn(0, workingBitmap.height)

        val w = (r - l).coerceAtLeast(5).coerceAtMost(workingBitmap.width - l)
        val h = (b - t).coerceAtLeast(5).coerceAtMost(workingBitmap.height - t)

        return Bitmap.createBitmap(workingBitmap, l, t, w, h)
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

                // Interactive Crop Overlay Rendered in Bitmap Space Projection
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(zoomScale, panOffset, containerSize) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val bmpStart = screenToBmpCoord(startOffset.x, startOffset.y)
                                    if (bmpStart != null) {
                                        cropBmpRect = RectF(
                                            bmpStart.x.coerceIn(0f, workingBitmap.width.toFloat()),
                                            bmpStart.y.coerceIn(0f, workingBitmap.height.toFloat()),
                                            (bmpStart.x + minBmpGap).coerceIn(0f, workingBitmap.width.toFloat()),
                                            (bmpStart.y + minBmpGap).coerceIn(0f, workingBitmap.height.toFloat())
                                        )
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val bmpCurrent = screenToBmpCoord(change.position.x, change.position.y)
                                    if (bmpCurrent != null) {
                                        cropBmpRect = RectF(
                                            cropBmpRect.left,
                                            cropBmpRect.top,
                                            bmpCurrent.x.coerceIn(0f, workingBitmap.width.toFloat()),
                                            bmpCurrent.y.coerceIn(0f, workingBitmap.height.toFloat())
                                        )
                                    }
                                }
                            )
                        }
                ) {
                    val p1 = bmpToScreenCoord(min(cropBmpRect.left, cropBmpRect.right), min(cropBmpRect.top, cropBmpRect.bottom))
                    val p2 = bmpToScreenCoord(max(cropBmpRect.left, cropBmpRect.right), max(cropBmpRect.top, cropBmpRect.bottom))

                    if (p1 != null && p2 != null) {
                        val sl = p1.x
                        val st = p1.y
                        val sr = p2.x
                        val sb = p2.y

                        drawRect(color = Color.Black.copy(alpha = 0.45f))
                        drawRect(color = Color.Transparent, topLeft = Offset(sl, st), size = Size(sr - sl, sb - st))
                        drawRect(
                            color = if (extractionMode == CropExtractionMode.FULL_TABLE) Color(0xFF00E676) else Color(0xFFFF9100),
                            topLeft = Offset(sl, st),
                            size = Size(sr - sl, sb - st),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }

                // Top Toolbar: Zoom & Rotate Controls, Mode Selector & Edge Nudges
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

                        // Edge Nudges (Adjusts in Bitmap Space directly)
                        val stepX = (workingBitmap.width * 0.02f).coerceAtLeast(4f)
                        val stepY = (workingBitmap.height * 0.02f).coerceAtLeast(4f)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Left Edge:", color = Color(0xFF81D4FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropBmpRect.left = (cropBmpRect.left - stepX).coerceAtLeast(0f) }, contentPadding = PaddingValues(2.dp)) { Text("◀") }
                            Button(onClick = { cropBmpRect.left = (cropBmpRect.left + stepX).coerceAtMost(cropBmpRect.right - minBmpGap) }, contentPadding = PaddingValues(2.dp)) { Text("▶") }

                            Text("Right Edge:", color = Color(0xFF81D4FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropBmpRect.right = (cropBmpRect.right - stepX).coerceAtLeast(cropBmpRect.left + minBmpGap) }, contentPadding = PaddingValues(2.dp)) { Text("◀") }
                            Button(onClick = { cropBmpRect.right = (cropBmpRect.right + stepX).coerceAtMost(workingBitmap.width.toFloat()) }, contentPadding = PaddingValues(2.dp)) { Text("▶") }

                            Text("Top Edge:", color = Color(0xFFA5D6A7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropBmpRect.top = (cropBmpRect.top - stepY).coerceAtLeast(0f) }, contentPadding = PaddingValues(2.dp)) { Text("▲") }
                            Button(onClick = { cropBmpRect.top = (cropBmpRect.top + stepY).coerceAtMost(cropBmpRect.bottom - minBmpGap) }, contentPadding = PaddingValues(2.dp)) { Text("▼") }

                            Text("Bottom Edge:", color = Color(0xFFA5D6A7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = { cropBmpRect.bottom = (cropBmpRect.bottom - stepY).coerceAtLeast(cropBmpRect.top + minBmpGap) }, contentPadding = PaddingValues(2.dp)) { Text("▲") }
                            Button(onClick = { cropBmpRect.bottom = (cropBmpRect.bottom + stepY).coerceAtMost(workingBitmap.height.toFloat()) }, contentPadding = PaddingValues(2.dp)) { Text("▼") }
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
