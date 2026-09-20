// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/ui/TableOverlayPreview.kt
package com.example.imagetotable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.imagetotable.ocr.CellDetector
import java.awt.image.BufferedImage
import kotlin.math.min

@Composable
fun TableOverlayPreview(
    image: BufferedImage?,
    cellMatrix: List<List<CellDetector.CellBox>>,
    selectedCell: Pair<Int, Int>?,
    onCellClick: (rowIndex: Int, colIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (image == null) {
        Box(modifier = modifier.background(Color(0xFFF0F2F5)))
        return
    }

    val imageBitmap = remember(image) { image.toComposeImageBitmap() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF2B2B2B))
            .pointerInput(image, cellMatrix) {
                detectTapGestures { tapOffset ->
                    val canvasW = size.width.toFloat()
                    val canvasH = size.height.toFloat()
                    val imgW = image.width.toFloat()
                    val imgH = image.height.toFloat()

                    if (imgW <= 0f || imgH <= 0f) return@detectTapGestures

                    // Calculate active scale and letterbox offsets
                    val scale = min(canvasW / imgW, canvasH / imgH)
                    val offsetX = (canvasW - (imgW * scale)) / 2f
                    val offsetY = (canvasH - (imgH * scale)) / 2f

                    // Map tap coordinates into image pixel space
                    val tapImgX = (tapOffset.x - offsetX) / scale
                    val tapImgY = (tapOffset.y - offsetY) / scale

                    // Find intersecting cell bounding box
                    for ((rowIdx, rowCells) in cellMatrix.withIndex()) {
                        for ((colIdx, cell) in rowCells.withIndex()) {
                            val box = cell.rect
                            val containsX = tapImgX >= box.x && tapImgX <= (box.x + box.width)
                            val containsY = tapImgY >= box.y && tapImgY <= (box.y + box.height)

                            if (containsX && containsY) {
                                onCellClick(rowIdx, colIdx)
                                return@detectTapGestures
                            }
                        }
                    }
                }
            }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val imgWidth = image.width.toFloat()
        val imgHeight = image.height.toFloat()

        if (imgWidth <= 0 || imgHeight <= 0) return@Canvas

        val scale = min(canvasWidth / imgWidth, canvasHeight / imgHeight)
        val renderWidth = imgWidth * scale
        val renderHeight = imgHeight * scale
        val offsetX = (canvasWidth - renderWidth) / 2f
        val offsetY = (canvasHeight - renderHeight) / 2f

        // Draw background image
        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
            dstSize = IntSize(renderWidth.toInt(), renderHeight.toInt())
        )

        // Draw bounding boxes
        cellMatrix.forEachIndexed { rowIdx, rowCells ->
            rowCells.forEachIndexed { colIdx, cell ->
                val box = cell.rect
                val scaledX = offsetX + (box.x * scale)
                val scaledY = offsetY + (box.y * scale)
                val scaledW = box.width * scale
                val scaledH = box.height * scale

                val isSelected = selectedCell == Pair(rowIdx, colIdx)

                if (isSelected) {
                    drawRect(
                        color = Color(0x552196F3),
                        topLeft = Offset(scaledX, scaledY),
                        size = Size(scaledW, scaledH)
                    )
                    drawRect(
                        color = Color(0xFF1976D2),
                        topLeft = Offset(scaledX, scaledY),
                        size = Size(scaledW, scaledH),
                        style = Stroke(width = 3f)
                    )
                } else {
                    drawRect(
                        color = Color(0xFF00E676),
                        topLeft = Offset(scaledX, scaledY),
                        size = Size(scaledW, scaledH),
                        style = Stroke(width = 1.5f)
                    )
                }
            }
        }
    }
}
