// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/ui/TableOverlayPreview.kt
package com.example.imagetotable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.imagetotable.ocr.CellDetector
import java.awt.image.BufferedImage
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun TableOverlayPreview(
    image: BufferedImage?,
    cellMatrix: List<List<CellDetector.CellBox>>,
    selectedCell: Pair<Int, Int>?,
    onCellClick: (rowIndex: Int, colIndex: Int) -> Unit,
    onBoxResized: (rowIndex: Int, colIndex: Int, updatedBox: CellDetector.CellBox) -> Unit,
    modifier: Modifier = Modifier
) {
    if (image == null) {
        Box(
            modifier = modifier.background(Color(0xFF202020)).fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("No Image Loaded", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    val imageBitmap = remember(image) { image.toComposeImageBitmap() }

    var zoom by remember(image) { mutableStateOf(1.0f) }
    var pan by remember(image) { mutableStateOf(Offset.Zero) }

    // Active drag tracking
    var activeHandle by remember { mutableStateOf(ResizeHandle.NONE) }
    var dragCellIndices by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
                .pointerInput(image, cellMatrix, selectedCell, zoom, pan) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val canvasW = size.width.toFloat()
                            val canvasH = size.height.toFloat()
                            val imgW = image.width.toFloat()
                            val imgH = image.height.toFloat()

                            if (imgW <= 0f || imgH <= 0f) continue

                            val center = Offset(canvasW / 2f, canvasH / 2f)
                            val baseScale = min(canvasW / imgW, canvasH / imgH)
                            val baseOffsetX = (canvasW - imgW * baseScale) / 2f
                            val baseOffsetY = (canvasH - imgH * baseScale) / 2f

                            fun screenToImg(screenPos: Offset): Offset {
                                val baseX = center.x + (screenPos.x - center.x - pan.x) / zoom
                                val baseY = center.y + (screenPos.y - center.y - pan.y) / zoom
                                return Offset(
                                    (baseX - baseOffsetX) / baseScale,
                                    (baseY - baseOffsetY) / baseScale
                                )
                            }

                            fun imgToScreen(imgPos: Offset): Offset {
                                val baseX = baseOffsetX + imgPos.x * baseScale
                                val baseY = baseOffsetY + imgPos.y * baseScale
                                return Offset(
                                    center.x + pan.x + (baseX - center.x) * zoom,
                                    center.y + pan.y + (baseY - center.y) * zoom
                                )
                            }

                            when (event.type) {
                                // 1. Scroll-Wheel Zoom
                                PointerEventType.Scroll -> {
                                    val change = event.changes.firstOrNull() ?: continue
                                    val scrollDelta = change.scrollDelta.y
                                    val cursor = change.position
                                    val zoomFactor = if (scrollDelta < 0) 1.15f else (1f / 1.15f)
                                    val newZoom = (zoom * zoomFactor).coerceIn(0.2f, 25.0f)

                                    pan += (cursor - center - pan) * (1f - newZoom / zoom)
                                    zoom = newZoom
                                    change.consume()
                                }

                                // 2. Pointer Down -> Handle detection vs Pan vs Cell Select
                                PointerEventType.Press -> {
                                    val downPos = event.changes.first().position
                                    var lastPos = downPos
                                    var totalDrag = 0f

                                    // Check if pointer hit a resize handle on the selected cell
                                    var hitHandle = ResizeHandle.NONE
                                    val activeIndices = selectedCell

                                    if (activeIndices != null) {
                                        val (r, c) = activeIndices
                                        val box = cellMatrix.getOrNull(r)?.getOrNull(c)?.rect
                                        if (box != null) {
                                            val tl = imgToScreen(Offset(box.x.toFloat(), box.y.toFloat()))
                                            val br = imgToScreen(Offset((box.x + box.width).toFloat(), (box.y + box.height).toFloat()))
                                            val tr = Offset(br.x, tl.y)
                                            val bl = Offset(tl.x, br.y)
                                            val midT = Offset((tl.x + br.x) / 2f, tl.y)
                                            val midB = Offset((tl.x + br.x) / 2f, br.y)
                                            val midL = Offset(tl.x, (tl.y + br.y) / 2f)
                                            val midR = Offset(br.x, (tl.y + br.y) / 2f)

                                            val handleRadius = 12f // Hit threshold in screen pixels

                                            hitHandle = when {
                                                (downPos - tl).getDistance() <= handleRadius -> ResizeHandle.TOP_LEFT
                                                (downPos - tr).getDistance() <= handleRadius -> ResizeHandle.TOP_RIGHT
                                                (downPos - bl).getDistance() <= handleRadius -> ResizeHandle.BOTTOM_LEFT
                                                (downPos - br).getDistance() <= handleRadius -> ResizeHandle.BOTTOM_RIGHT
                                                (downPos - midT).getDistance() <= handleRadius -> ResizeHandle.TOP
                                                (downPos - midB).getDistance() <= handleRadius -> ResizeHandle.BOTTOM
                                                (downPos - midL).getDistance() <= handleRadius -> ResizeHandle.LEFT
                                                (downPos - midR).getDistance() <= handleRadius -> ResizeHandle.RIGHT
                                                else -> ResizeHandle.NONE
                                            }
                                        }
                                    }

                                    activeHandle = hitHandle
                                    dragCellIndices = activeIndices

                                    while (true) {
                                        val subEvent = awaitPointerEvent(PointerEventPass.Main)
                                        if (subEvent.type == PointerEventType.Move) {
                                            val currentPos = subEvent.changes.first().position
                                            val delta = currentPos - lastPos
                                            lastPos = currentPos
                                            totalDrag += delta.getDistance()

                                            if (activeHandle != ResizeHandle.NONE && dragCellIndices != null) {
                                                // Convert screen delta to image pixel delta
                                                val deltaImgX = (delta.x / (baseScale * zoom)).roundToInt()
                                                val deltaImgY = (delta.y / (baseScale * zoom)).roundToInt()

                                                val (r, c) = dragCellIndices!!
                                                val targetCell = cellMatrix[r][c]
                                                val b = targetCell.rect

                                                var newX = b.x
                                                var newY = b.y
                                                var newW = b.width
                                                var newH = b.height

                                                when (activeHandle) {
                                                    ResizeHandle.LEFT -> {
                                                        newX += deltaImgX
                                                        newW -= deltaImgX
                                                    }
                                                    ResizeHandle.RIGHT -> {
                                                        newW += deltaImgX
                                                    }
                                                    ResizeHandle.TOP -> {
                                                        newY += deltaImgY
                                                        newH -= deltaImgY
                                                    }
                                                    ResizeHandle.BOTTOM -> {
                                                        newH += deltaImgY
                                                    }
                                                    ResizeHandle.TOP_LEFT -> {
                                                        newX += deltaImgX
                                                        newW -= deltaImgX
                                                        newY += deltaImgY
                                                        newH -= deltaImgY
                                                    }
                                                    ResizeHandle.TOP_RIGHT -> {
                                                        newW += deltaImgX
                                                        newY += deltaImgY
                                                        newH -= deltaImgY
                                                    }
                                                    ResizeHandle.BOTTOM_LEFT -> {
                                                        newX += deltaImgX
                                                        newW -= deltaImgX
                                                        newH += deltaImgY
                                                    }
                                                    ResizeHandle.BOTTOM_RIGHT -> {
                                                        newW += deltaImgX
                                                        newH += deltaImgY
                                                    }
                                                    ResizeHandle.NONE -> {}
                                                }

                                                targetCell.updateBounds(newX, newY, newW, newH, image.width, image.height)
                                            } else {
                                                // Pan view if not dragging a handle
                                                pan += delta
                                            }
                                            subEvent.changes.first().consume()
                                        } else if (subEvent.type == PointerEventType.Release) {
                                            if (activeHandle != ResizeHandle.NONE && dragCellIndices != null) {
                                                // Notify parent that box dimensions finalized
                                                val (r, c) = dragCellIndices!!
                                                onBoxResized(r, c, cellMatrix[r][c])
                                            } else if (totalDrag < 5f) {
                                                // Tap selection
                                                val tapImg = screenToImg(downPos)
                                                for ((r, row) in cellMatrix.withIndex()) {
                                                    for ((c, cell) in row.withIndex()) {
                                                        val box = cell.rect
                                                        if (tapImg.x >= box.x && tapImg.x <= box.x + box.width &&
                                                            tapImg.y >= box.y && tapImg.y <= box.y + box.height
                                                        ) {
                                                            onCellClick(r, c)
                                                            break
                                                        }
                                                    }
                                                }
                                            }
                                            activeHandle = ResizeHandle.NONE
                                            dragCellIndices = null
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            val canvasW = size.width
            val canvasH = size.height
            val imgW = image.width.toFloat()
            val imgH = image.height.toFloat()

            if (imgW <= 0f || imgH <= 0f) return@Canvas

            val baseScale = min(canvasW / imgW, canvasH / imgH)
            val center = Offset(canvasW / 2f, canvasH / 2f)
            val baseOffsetX = (canvasW - imgW * baseScale) / 2f
            val baseOffsetY = (canvasH - imgH * baseScale) / 2f

            fun imgToScreen(x: Float, y: Float): Offset {
                val baseX = baseOffsetX + x * baseScale
                val baseY = baseOffsetY + y * baseScale
                return Offset(
                    center.x + pan.x + (baseX - center.x) * zoom,
                    center.y + pan.y + (baseY - center.y) * zoom
                )
            }

            // Draw image
            val imgTopLeft = imgToScreen(0f, 0f)
            val renderW = (imgW * baseScale * zoom).roundToInt().coerceAtLeast(1)
            val renderH = (imgH * baseScale * zoom).roundToInt().coerceAtLeast(1)

            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(imgTopLeft.x.roundToInt(), imgTopLeft.y.roundToInt()),
                dstSize = IntSize(renderW, renderH)
            )

            // Draw bounding boxes
            val strokeW = (1.5f * zoom).coerceIn(1f, 3.5f)
            val selectedStrokeW = (2.5f * zoom).coerceIn(2f, 5f)

            cellMatrix.forEachIndexed { r, rowCells ->
                rowCells.forEachIndexed { c, cell ->
                    val b = cell.rect
                    val tl = imgToScreen(b.x.toFloat(), b.y.toFloat())
                    val w = b.width * baseScale * zoom
                    val h = b.height * baseScale * zoom
                    val isSelected = selectedCell == Pair(r, c)

                    if (isSelected) {
                        // Cell interior fill & primary border
                        drawRect(
                            color = Color(0x442196F3),
                            topLeft = tl,
                            size = Size(w, h)
                        )
                        drawRect(
                            color = Color(0xFF1E88E5),
                            topLeft = tl,
                            size = Size(w, h),
                            style = Stroke(width = selectedStrokeW)
                        )

                        // Draw 8 Interactive Resize Handles on the selected box
                        val handleRadius = 5f * zoom.coerceIn(0.8f, 1.8f)
                        val points = listOf(
                            tl,                                    // Top-Left
                            Offset(tl.x + w / 2f, tl.y),          // Top-Mid
                            Offset(tl.x + w, tl.y),               // Top-Right
                            Offset(tl.x, tl.y + h / 2f),          // Left-Mid
                            Offset(tl.x + w, tl.y + h / 2f),      // Right-Mid
                            Offset(tl.x, tl.y + h),               // Bottom-Left
                            Offset(tl.x + w / 2f, tl.y + h),      // Bottom-Mid
                            Offset(tl.x + w, tl.y + h)            // Bottom-Right
                        )

                        for (pt in points) {
                            // White interior circle
                            drawCircle(
                                color = Color.White,
                                radius = handleRadius,
                                center = pt,
                                style = Fill
                            )
                            // Solid blue border around handle
                            drawCircle(
                                color = Color(0xFF0D47A1),
                                radius = handleRadius,
                                center = pt,
                                style = Stroke(width = 2f)
                            )
                        }
                    } else {
                        drawRect(
                            color = Color(0xFF00E676),
                            topLeft = tl,
                            size = Size(w, h),
                            style = Stroke(width = strokeW)
                        )
                    }
                }
            }
        }

        // Zoom Toolbar
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF444444), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.2f) },
                modifier = Modifier.size(28.dp)
            ) {
                Text("-", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "${(zoom * 100).roundToInt()}%",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            IconButton(
                onClick = { zoom = (zoom * 1.25f).coerceAtMost(25.0f) },
                modifier = Modifier.size(28.dp)
            ) {
                Text("+", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(
                onClick = {
                    zoom = 1.0f
                    pan = Offset.Zero
                },
                modifier = Modifier.size(28.dp)
            ) {
                Text("Fit", color = Color(0xFF64B5F6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
