// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/ui/TableOverlayPreview.kt
package com.example.imagetotable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    modifier: Modifier = Modifier
) {
    if (image == null) {
        Box(
            modifier = modifier
                .background(Color(0xFF202020))
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("No Image Loaded", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    val imageBitmap = remember(image) { image.toComposeImageBitmap() }

    // Transformation States
    var zoom by remember(image) { mutableStateOf(1.0f) }
    var pan by remember(image) { mutableStateOf(Offset.Zero) }

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
                .pointerInput(image, cellMatrix, zoom, pan) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val canvasW = size.width.toFloat()
                            val canvasH = size.height.toFloat()
                            val imgW = image.width.toFloat()
                            val imgH = image.height.toFloat()

                            if (imgW <= 0f || imgH <= 0f) continue

                            val center = Offset(canvasW / 2f, canvasH / 2f)

                            when (event.type) {
                                // 1. Scroll Wheel -> Zoom at mouse cursor
                                PointerEventType.Scroll -> {
                                    val change = event.changes.firstOrNull() ?: continue
                                    val scrollDelta = change.scrollDelta.y
                                    val cursor = change.position

                                    val zoomFactor = if (scrollDelta < 0) 1.15f else (1f / 1.15f)
                                    val newZoom = (zoom * zoomFactor).coerceIn(0.2f, 25.0f)

                                    // Adjust pan so the point under the cursor stays fixed
                                    val newPan = pan + (cursor - center - pan) * (1f - newZoom / zoom)
                                    zoom = newZoom
                                    pan = newPan
                                    change.consume()
                                }

                                // 2. Mouse Press -> Distinguish between Pan Drag and Tap Selection
                                PointerEventType.Press -> {
                                    val downPos = event.changes.first().position
                                    var lastPos = downPos
                                    var totalDragDistance = 0f

                                    while (true) {
                                        val subEvent = awaitPointerEvent(PointerEventPass.Main)
                                        if (subEvent.type == PointerEventType.Move) {
                                            val currentPos = subEvent.changes.first().position
                                            val delta = currentPos - lastPos
                                            lastPos = currentPos
                                            totalDragDistance += delta.getDistance()

                                            // Pan the canvas
                                            pan += delta
                                            subEvent.changes.first().consume()
                                        } else if (subEvent.type == PointerEventType.Release) {
                                            // Tap detected if user moved less than 5 pixels
                                            if (totalDragDistance < 5f) {
                                                val baseScale = min(canvasW / imgW, canvasH / imgH)
                                                val baseOffsetX = (canvasW - imgW * baseScale) / 2f
                                                val baseOffsetY = (canvasH - imgH * baseScale) / 2f

                                                // Invert screen coords to image coords
                                                val baseX = center.x + (downPos.x - center.x - pan.x) / zoom
                                                val baseY = center.y + (downPos.y - center.y - pan.y) / zoom

                                                val imgX = (baseX - baseOffsetX) / baseScale
                                                val imgY = (baseY - baseOffsetY) / baseScale

                                                // Hit test against cell boxes
                                                for ((rowIdx, rowCells) in cellMatrix.withIndex()) {
                                                    for ((colIdx, cell) in rowCells.withIndex()) {
                                                        val box = cell.rect
                                                        if (imgX >= box.x && imgX <= (box.x + box.width) &&
                                                            imgY >= box.y && imgY <= (box.y + box.height)
                                                        ) {
                                                            onCellClick(rowIdx, colIdx)
                                                            break
                                                        }
                                                    }
                                                }
                                            }
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

            fun imageToScreen(imgX: Float, imgY: Float): Offset {
                val baseX = baseOffsetX + imgX * baseScale
                val baseY = baseOffsetY + imgY * baseScale
                val screenX = center.x + pan.x + (baseX - center.x) * zoom
                val screenY = center.y + pan.y + (baseY - center.y) * zoom
                return Offset(screenX, screenY)
            }

            // Draw image scaled and translated
            val imgTopLeft = imageToScreen(0f, 0f)
            val renderedW = (imgW * baseScale * zoom).roundToInt().coerceAtLeast(1)
            val renderedH = (imgH * baseScale * zoom).roundToInt().coerceAtLeast(1)

            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(imgTopLeft.x.roundToInt(), imgTopLeft.y.roundToInt()),
                dstSize = IntSize(renderedW, renderedH)
            )

            // Draw bounding boxes
            val effectiveStroke = (1.5f * zoom).coerceIn(1f, 4f)
            val selectedStroke = (3f * zoom).coerceIn(2f, 6f)

            cellMatrix.forEachIndexed { rowIdx, rowCells ->
                rowCells.forEachIndexed { colIdx, cell ->
                    val box = cell.rect
                    val cellTopLeft = imageToScreen(box.x.toFloat(), box.y.toFloat())
                    val cellW = box.width * baseScale * zoom
                    val cellH = box.height * baseScale * zoom

                    val isSelected = selectedCell == Pair(rowIdx, colIdx)

                    if (isSelected) {
                        drawRect(
                            color = Color(0x662196F3),
                            topLeft = cellTopLeft,
                            size = Size(cellW, cellH)
                        )
                        drawRect(
                            color = Color(0xFF1E88E5),
                            topLeft = cellTopLeft,
                            size = Size(cellW, cellH),
                            style = Stroke(width = selectedStroke)
                        )
                    } else {
                        drawRect(
                            color = Color(0xFF00E676),
                            topLeft = cellTopLeft,
                            size = Size(cellW, cellH),
                            style = Stroke(width = effectiveStroke)
                        )
                    }
                }
            }
        }

        // Floating Zoom / Pan Controls Overlay
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
