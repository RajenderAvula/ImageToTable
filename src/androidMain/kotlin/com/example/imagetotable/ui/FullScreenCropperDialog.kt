package com.example.imagetotable.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.*

enum class CropExtractionMode {
    FULL_TABLE,
    SINGLE_CELL_STEP
}

enum class CropperGestureMode {
    PAN_ZOOM,
    ADJUST_CROP
}

enum class CropCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    ENTIRE_QUAD,
    NONE
}

@Composable
fun FullScreenCropperDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onCropConfirmed: (croppedBitmap: Bitmap, mode: CropExtractionMode) -> Unit
) {
    var workingBitmap by remember { mutableStateOf(sourceBitmap) }
    var extractionMode by remember { mutableStateOf(CropExtractionMode.FULL_TABLE) }
    var gestureMode by remember { mutableStateOf(CropperGestureMode.ADJUST_CROP) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Pinch-to-zoom & pan states
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Rotation states: 90-degree steps + text box custom rotation angle (0.1° precision)
    var base90Rotation by remember { mutableFloatStateOf(0f) }
    var angleInputText by remember { mutableStateOf("0.0") }
    var customAngle by remember { mutableFloatStateOf(0f) }
    val totalRotation get() = (base90Rotation + customAngle)

    // 4 Independent Corner coordinates stored in Bitmap Coordinate Space (Pixel-perfect & invariant to zoom/pan)
    var bTopLeft by remember(workingBitmap) {
        mutableStateOf(Offset(workingBitmap.width * 0.08f, workingBitmap.height * 0.12f))
    }
    var bTopRight by remember(workingBitmap) {
        mutableStateOf(Offset(workingBitmap.width * 0.92f, workingBitmap.height * 0.12f))
    }
    var bBottomRight by remember(workingBitmap) {
        mutableStateOf(Offset(workingBitmap.width * 0.92f, workingBitmap.height * 0.88f))
    }
    var bBottomLeft by remember(workingBitmap) {
        mutableStateOf(Offset(workingBitmap.width * 0.08f, workingBitmap.height * 0.88f))
    }

    // Active corner selected for precision directional arrows
    var activeNudgeCorner by remember { mutableStateOf(CropCorner.TOP_LEFT) }
    var currentDraggingCorner by remember { mutableStateOf(CropCorner.NONE) }

    fun resetCornersToBox() {
        val bw = workingBitmap.width.toFloat()
        val bh = workingBitmap.height.toFloat()
        bTopLeft = Offset(bw * 0.08f, bh * 0.12f)
        bTopRight = Offset(bw * 0.92f, bh * 0.12f)
        bBottomRight = Offset(bw * 0.92f, bh * 0.88f)
        bBottomLeft = Offset(bw * 0.08f, bh * 0.88f)
    }

    fun rotate90Clockwise() {
        base90Rotation = (base90Rotation + 90f) % 360f
        zoomScale = 1f
        panOffset = Offset.Zero
    }

    // Maps Bitmap coordinates (pixel) -> Screen coordinates (display)
    fun bmpToScreenCoord(bmpPt: Offset): Offset? {
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

        val sx0 = x0 + bmpPt.x * baseScale
        val sy0 = y0 + bmpPt.y * baseScale

        val dx = sx0 - cx
        val dy = sy0 - cy

        val rad = totalRotation * (PI.toFloat() / 180f)
        val rotX = dx * cos(rad) - dy * sin(rad)
        val rotY = dx * sin(rad) + dy * cos(rad)

        val finalX = cx + rotX * zoomScale + panOffset.x
        val finalY = cy + rotY * zoomScale + panOffset.y

        return Offset(finalX, finalY)
    }

    // Maps Screen coordinates (touch) -> Bitmap coordinates (pixel)
    fun screenToBmpCoord(screenPt: Offset): Offset? {
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

        val pannedX = screenPt.x - panOffset.x
        val pannedY = screenPt.y - panOffset.y

        val dx = pannedX - cx
        val dy = pannedY - cy

        val unzoomX = dx / zoomScale
        val unzoomY = dy / zoomScale

        val invRad = -totalRotation * (PI.toFloat() / 180f)
        val unrotX = unzoomX * cos(invRad) - unzoomY * sin(invRad)
        val unrotY = unzoomX * sin(invRad) + unzoomY * cos(invRad)

        val origScreenX = cx + unrotX
        val origScreenY = cy + unrotY

        val bx = ((origScreenX - x0) / baseScale).coerceIn(0f, bw)
        val by = ((origScreenY - y0) / baseScale).coerceIn(0f, bh)

        return Offset(bx, by)
    }

    // Perspective unwarp & quadrilateral crop
    fun calculatePerspectiveCroppedBitmap(): Bitmap? {
        val widthTop = hypot(bTopRight.x - bTopLeft.x, bTopRight.y - bTopLeft.y)
        val widthBottom = hypot(bBottomRight.x - bBottomLeft.x, bBottomRight.y - bBottomLeft.y)
        val targetWidth = max(widthTop, widthBottom).roundToInt().coerceIn(10, 4096)

        val heightLeft = hypot(bBottomLeft.x - bTopLeft.x, bBottomLeft.y - bTopLeft.y)
        val heightRight = hypot(bBottomRight.x - bTopRight.x, bBottomRight.y - bTopRight.y)
        val targetHeight = max(heightLeft, heightRight).roundToInt().coerceIn(10, 4096)

        val srcPts = floatArrayOf(
            bTopLeft.x, bTopLeft.y,
            bTopRight.x, bTopRight.y,
            bBottomRight.x, bBottomRight.y,
            bBottomLeft.x, bBottomLeft.y
        )
        val dstPts = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val polyMatrix = Matrix()
        val matrixSet = polyMatrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

        return if (matrixSet) {
            val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(result)
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }
            canvas.drawBitmap(workingBitmap, polyMatrix, paint)
            result
        } else {
            val left = minOf(bTopLeft.x, bBottomLeft.x).toInt().coerceIn(0, workingBitmap.width - 1)
            val top = minOf(bTopLeft.y, bTopRight.y).toInt().coerceIn(0, workingBitmap.height - 1)
            val right = maxOf(bTopRight.x, bBottomRight.x).toInt().coerceIn(left + 1, workingBitmap.width)
            val bottom = maxOf(bBottomLeft.y, bBottomRight.y).toInt().coerceIn(top + 1, workingBitmap.height)
            Bitmap.createBitmap(workingBitmap, left, top, right - left, bottom - top)
        }
    }

    // Directional nudge arrow action for selected corner (in bitmap space)
    fun nudgeCorner(dx: Float, dy: Float) {
        val bw = workingBitmap.width.toFloat()
        val bh = workingBitmap.height.toFloat()
        val delta = Offset(dx, dy)

        when (activeNudgeCorner) {
            CropCorner.TOP_LEFT -> {
                bTopLeft = Offset((bTopLeft.x + delta.x).coerceIn(0f, bw), (bTopLeft.y + delta.y).coerceIn(0f, bh))
            }
            CropCorner.TOP_RIGHT -> {
                bTopRight = Offset((bTopRight.x + delta.x).coerceIn(0f, bw), (bTopRight.y + delta.y).coerceIn(0f, bh))
            }
            CropCorner.BOTTOM_RIGHT -> {
                bBottomRight = Offset((bBottomRight.x + delta.x).coerceIn(0f, bw), (bBottomRight.y + delta.y).coerceIn(0f, bh))
            }
            CropCorner.BOTTOM_LEFT -> {
                bBottomLeft = Offset((bBottomLeft.x + delta.x).coerceIn(0f, bw), (bBottomLeft.y + delta.y).coerceIn(0f, bh))
            }
            CropCorner.ENTIRE_QUAD -> {
                bTopLeft = Offset((bTopLeft.x + delta.x).coerceIn(0f, bw), (bTopLeft.y + delta.y).coerceIn(0f, bh))
                bTopRight = Offset((bTopRight.x + delta.x).coerceIn(0f, bw), (bTopRight.y + delta.y).coerceIn(0f, bh))
                bBottomRight = Offset((bBottomRight.x + delta.x).coerceIn(0f, bw), (bBottomRight.y + delta.y).coerceIn(0f, bh))
                bBottomLeft = Offset((bBottomLeft.x + delta.x).coerceIn(0f, bw), (bBottomLeft.y + delta.y).coerceIn(0f, bh))
            }
            CropCorner.NONE -> {}
        }
    }

    // Gesture Modifier: Switches between Zoom/Pan and Independent 4-Corner Dragging
    val activeGestureModifier = if (gestureMode == CropperGestureMode.PAN_ZOOM) {
        Modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                zoomScale = (zoomScale * zoom).coerceIn(0.5f, 6.0f)
                panOffset += pan
            }
        }
    } else {
        Modifier.pointerInput(containerSize, zoomScale, panOffset, totalRotation) {
            val handleRadiusPx = 48.dp.toPx()

            detectDragGestures(
                onDragStart = { startPt ->
                    val sTL = bmpToScreenCoord(bTopLeft) ?: Offset.Zero
                    val sTR = bmpToScreenCoord(bTopRight) ?: Offset.Zero
                    val sBR = bmpToScreenCoord(bBottomRight) ?: Offset.Zero
                    val sBL = bmpToScreenCoord(bBottomLeft) ?: Offset.Zero

                    val dTL = (startPt - sTL).getDistance()
                    val dTR = (startPt - sTR).getDistance()
                    val dBR = (startPt - sBR).getDistance()
                    val dBL = (startPt - sBL).getDistance()

                    val minD = minOf(dTL, dTR, dBR, dBL)

                    currentDraggingCorner = when {
                        minD <= handleRadiusPx && minD == dTL -> {
                            activeNudgeCorner = CropCorner.TOP_LEFT
                            CropCorner.TOP_LEFT
                        }
                        minD <= handleRadiusPx && minD == dTR -> {
                            activeNudgeCorner = CropCorner.TOP_RIGHT
                            CropCorner.TOP_RIGHT
                        }
                        minD <= handleRadiusPx && minD == dBR -> {
                            activeNudgeCorner = CropCorner.BOTTOM_RIGHT
                            CropCorner.BOTTOM_RIGHT
                        }
                        minD <= handleRadiusPx && minD == dBL -> {
                            activeNudgeCorner = CropCorner.BOTTOM_LEFT
                            CropCorner.BOTTOM_LEFT
                        }
                        else -> {
                            activeNudgeCorner = CropCorner.ENTIRE_QUAD
                            CropCorner.ENTIRE_QUAD
                        }
                    }
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val bw = workingBitmap.width.toFloat()
                    val bh = workingBitmap.height.toFloat()

                    when (currentDraggingCorner) {
                        CropCorner.TOP_LEFT -> {
                            val newBmpPt = screenToBmpCoord(change.position)
                            if (newBmpPt != null) bTopLeft = newBmpPt
                        }
                        CropCorner.TOP_RIGHT -> {
                            val newBmpPt = screenToBmpCoord(change.position)
                            if (newBmpPt != null) bTopRight = newBmpPt
                        }
                        CropCorner.BOTTOM_RIGHT -> {
                            val newBmpPt = screenToBmpCoord(change.position)
                            if (newBmpPt != null) bBottomRight = newBmpPt
                        }
                        CropCorner.BOTTOM_LEFT -> {
                            val newBmpPt = screenToBmpCoord(change.position)
                            if (newBmpPt != null) bBottomLeft = newBmpPt
                        }
                        CropCorner.ENTIRE_QUAD -> {
                            val currBmp = screenToBmpCoord(change.position)
                            val prevBmp = screenToBmpCoord(change.position - dragAmount)
                            if (currBmp != null && prevBmp != null) {
                                val dBmp = currBmp - prevBmp
                                bTopLeft = Offset((bTopLeft.x + dBmp.x).coerceIn(0f, bw), (bTopLeft.y + dBmp.y).coerceIn(0f, bh))
                                bTopRight = Offset((bTopRight.x + dBmp.x).coerceIn(0f, bw), (bTopRight.y + dBmp.y).coerceIn(0f, bh))
                                bBottomRight = Offset((bBottomRight.x + dBmp.x).coerceIn(0f, bw), (bBottomRight.y + dBmp.y).coerceIn(0f, bh))
                                bBottomLeft = Offset((bBottomLeft.x + dBmp.x).coerceIn(0f, bw), (bBottomLeft.y + dBmp.y).coerceIn(0f, bh))
                            }
                        }
                        CropCorner.NONE -> {}
                    }
                },
                onDragEnd = { currentDraggingCorner = CropCorner.NONE },
                onDragCancel = { currentDraggingCorner = CropCorner.NONE }
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(activeGestureModifier)
            ) {
                // 1. ZOOMABLE, PANNABLE, ROTATABLE SOURCE IMAGE
                Image(
                    bitmap = workingBitmap.asImageBitmap(),
                    contentDescription = "Cropping Viewport",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = totalRotation
                            scaleX = zoomScale
                            scaleY = zoomScale
                            translationX = panOffset.x
                            translationY = panOffset.y
                        }
                        .onSizeChanged { containerSize = it },
                    contentScale = ContentScale.Fit
                )

                // 2. INTERACTIVE INDEPENDENT 4-CORNER PERSPECTIVE DRAG OVERLAY
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val sTL = bmpToScreenCoord(bTopLeft)
                    val sTR = bmpToScreenCoord(bTopRight)
                    val sBR = bmpToScreenCoord(bBottomRight)
                    val sBL = bmpToScreenCoord(bBottomLeft)

                    if (sTL != null && sTR != null && sBR != null && sBL != null) {
                        val quadPath = Path().apply {
                            moveTo(sTL.x, sTL.y)
                            lineTo(sTR.x, sTR.y)
                            lineTo(sBR.x, sBR.y)
                            lineTo(sBL.x, sBL.y)
                            close()
                        }

                        // Darken region outside crop area
                        clipPath(quadPath, clipOp = ClipOp.Difference) {
                            drawRect(color = Color.Black.copy(alpha = 0.50f))
                        }

                        // Boundary outline
                        val strokeColor = if (extractionMode == CropExtractionMode.FULL_TABLE) {
                            Color(0xFF00E676)
                        } else {
                            Color(0xFFFF9100)
                        }
                        drawPath(
                            path = quadPath,
                            color = strokeColor,
                            style = Stroke(width = 3.dp.toPx())
                        )

                        // Rule-of-thirds perspective guide grid
                        for (i in 1..2) {
                            val fraction = i / 3f
                            val topInterp = Offset(
                                sTL.x + (sTR.x - sTL.x) * fraction,
                                sTL.y + (sTR.y - sTL.y) * fraction
                            )
                            val bottomInterp = Offset(
                                sBL.x + (sBR.x - sBL.x) * fraction,
                                sBL.y + (sBR.y - sBL.y) * fraction
                            )
                            drawLine(
                                color = strokeColor.copy(alpha = 0.35f),
                                start = topInterp,
                                end = bottomInterp,
                                strokeWidth = 1.dp.toPx()
                            )

                            val leftInterp = Offset(
                                sTL.x + (sBL.x - sTL.x) * fraction,
                                sTL.y + (sBL.y - sTL.y) * fraction
                            )
                            val rightInterp = Offset(
                                sTR.x + (sBR.x - sTR.x) * fraction,
                                sTR.y + (sBR.y - sTR.y) * fraction
                            )
                            drawLine(
                                color = strokeColor.copy(alpha = 0.35f),
                                start = leftInterp,
                                end = rightInterp,
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        // Draw Corner Handles with outer touch halos
                        val corners = listOf(
                            Triple(sTL, "TL", CropCorner.TOP_LEFT),
                            Triple(sTR, "TR", CropCorner.TOP_RIGHT),
                            Triple(sBR, "BR", CropCorner.BOTTOM_RIGHT),
                            Triple(sBL, "BL", CropCorner.BOTTOM_LEFT)
                        )

                        for ((pt, _, cornerType) in corners) {
                            val isActive = activeNudgeCorner == cornerType
                            drawCircle(
                                color = if (isActive) Color(0xFF00E5FF).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.25f),
                                radius = if (isActive) 22.dp.toPx() else 16.dp.toPx(),
                                center = pt
                            )
                            drawCircle(
                                color = if (isActive) Color(0xFF00E5FF) else strokeColor,
                                radius = 11.dp.toPx(),
                                center = pt,
                                style = Stroke(width = 3.dp.toPx())
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 6.dp.toPx(),
                                center = pt
                            )
                        }
                    }
                }

                // 3. TOP CONTROLS: TEXT BOX ROTATION (0.1° PRECISION), ZOOM, GESTURE MODES, CORNER CHIPS & NUDGES
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(6.dp),
                    backgroundColor = Color.Black.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // ROW 1: 90° ROTATION, TEXT BOX BASED CUSTOM ANGLE ROTATION (0.1° PRECISION)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { rotate90Clockwise() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0288D1)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("🔄 90°", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Text(
                                text = "Angle:",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )

                            // DIRECT NUMERIC INPUT FOR ROTATION WITH 0.1° PRECISION
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(Color(0xFF263238), RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                BasicTextField(
                                    value = angleInputText,
                                    onValueChange = { input ->
                                        if (input.isEmpty() || input == "-" || input == "+" || input == "." || input == "-." || input.matches(Regex("^[+-]?\\d*(\\.\\d*)?$"))) {
                                            angleInputText = input
                                            val parsed = input.toFloatOrNull()
                                            if (parsed != null) {
                                                customAngle = parsed
                                            } else if (input.isEmpty() || input == "-" || input == "+") {
                                                customAngle = 0f
                                            }
                                        }
                                    },
                                    textStyle = TextStyle(
                                        color = Color(0xFFFFD54F),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    ),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(68.dp)
                                )
                                Text("°", color = Color(0xFFFFD54F), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            Text(
                                text = "Total: ${"%.1f".format(totalRotation)}°",
                                color = Color(0xFFB0BEC5),
                                fontSize = 11.sp
                            )

                            if (customAngle != 0f || base90Rotation != 0f) {
                                Button(
                                    onClick = {
                                        customAngle = 0f
                                        angleInputText = "0.0"
                                        base90Rotation = 0f
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Reset 0°", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // ROW 2: ZOOM CONTROLS, GESTURE MODE TOGGLE, AND EXTRACTION MODE
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { zoomScale = (zoomScale * 1.25f).coerceAtMost(6.0f) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) { Text("🔍+", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = { zoomScale = (zoomScale / 1.25f).coerceAtLeast(0.5f) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) { Text("🔍-", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        zoomScale = 1f
                                        panOffset = Offset.Zero
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) { Text("Fit", color = Color.White, fontSize = 11.sp) }

                                // Gesture Mode Toggle
                                Button(
                                    onClick = {
                                        gestureMode = if (gestureMode == CropperGestureMode.PAN_ZOOM)
                                            CropperGestureMode.ADJUST_CROP else CropperGestureMode.PAN_ZOOM
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (gestureMode == CropperGestureMode.PAN_ZOOM) Color(0xFFE91E63) else Color(0xFF5E35B1)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        if (gestureMode == CropperGestureMode.PAN_ZOOM) "🖐 Pan/Zoom" else "✂ 4-Corners",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = { extractionMode = CropExtractionMode.FULL_TABLE },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (extractionMode == CropExtractionMode.FULL_TABLE) Color(0xFF2E7D32) else Color.DarkGray
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) { Text("Full Table", color = Color.White, fontSize = 11.sp) }

                                Button(
                                    onClick = { extractionMode = CropExtractionMode.SINGLE_CELL_STEP },
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (extractionMode == CropExtractionMode.SINGLE_CELL_STEP) Color(0xFFE65100) else Color.DarkGray
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) { Text("One Cell", color = Color.White, fontSize = 11.sp) }
                            }
                        }

                        // ROW 3: CORNER SELECTION CHIPS & DIRECTIONAL NUDGE ARROWS
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Nudge:",
                                color = Color(0xFF81D4FA),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            val cornerOptions = listOf(
                                "TL" to CropCorner.TOP_LEFT,
                                "TR" to CropCorner.TOP_RIGHT,
                                "BR" to CropCorner.BOTTOM_RIGHT,
                                "BL" to CropCorner.BOTTOM_LEFT,
                                "All" to CropCorner.ENTIRE_QUAD
                            )

                            cornerOptions.forEach { (label, corner) ->
                                val isSelected = activeNudgeCorner == corner
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isSelected) Color(0xFF00E5FF) else Color(0xFF37474F),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable { activeNudgeCorner = corner }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.Black else Color.White
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            val step = (workingBitmap.width * 0.015f).coerceAtLeast(4f)

                            Button(
                                onClick = { nudgeCorner(-step, 0f) },
                                contentPadding = PaddingValues(2.dp),
                                modifier = Modifier.size(width = 30.dp, height = 28.dp)
                            ) { Text("◀", fontSize = 11.sp) }

                            Button(
                                onClick = { nudgeCorner(step, 0f) },
                                contentPadding = PaddingValues(2.dp),
                                modifier = Modifier.size(width = 30.dp, height = 28.dp)
                            ) { Text("▶", fontSize = 11.sp) }

                            Button(
                                onClick = { nudgeCorner(0f, -step) },
                                contentPadding = PaddingValues(2.dp),
                                modifier = Modifier.size(width = 30.dp, height = 28.dp)
                            ) { Text("▲", fontSize = 11.sp) }

                            Button(
                                onClick = { nudgeCorner(0f, step) },
                                contentPadding = PaddingValues(2.dp),
                                modifier = Modifier.size(width = 30.dp, height = 28.dp)
                            ) { Text("▼", fontSize = 11.sp) }

                            Button(
                                onClick = { resetCornersToBox() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5E35B1)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) { Text("Reset Box", color = Color.White, fontSize = 10.sp) }
                        }
                    }
                }

                // 4. BOTTOM ACTION BAR: CANCEL & PROCEED TO EXTRACTION
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.88f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF424242))
                    ) {
                        Text("Cancel", color = Color.White)
                    }

                    Button(
                        onClick = {
                            val perspectiveCropped = calculatePerspectiveCroppedBitmap()
                            if (perspectiveCropped != null) {
                                onCropConfirmed(perspectiveCropped, extractionMode)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                    ) {
                        Text(
                            text = if (extractionMode == CropExtractionMode.FULL_TABLE) {
                                "Unwarp & Extract Full Table"
                            } else {
                                "Unwarp & Extract to Active Cell"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
