// ImageToTable/src/jvmMain/kotlin/com/example/imagetotable/ui/TableOverlayPreview.kt
package com.example.imagetotable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
    onCellClick: ((rowIndex: Int, colIndex: Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (image == null) {
        Box(
            modifier = modifier.background(Color(0xFFF0F2F5))
        )
        return
    }

    val imageBitmap = remember(image) { image.toComposeImageBitmap() }

    Canvas(modifier = modifier.fillMaxSize().background(Color(0xFF2B2B2B))) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        val imgWidth = image.width.toFloat()
        val imgHeight = image.height.toFloat()

        if (imgWidth <= 0 || imgHeight <= 0) return@Canvas

        // 1. Calculate uniform scale factor to maintain image aspect ratio
        val scale = min(canvasWidth / imgWidth, canvasHeight / imgHeight)
        val renderWidth = imgWidth * scale
        val renderHeight = imgHeight * scale

        // 2. Calculate letterbox / pillarbox centering offsets
        val offsetX = (canvasWidth - renderWidth) / 2f
        val offsetY = (canvasHeight - renderHeight) / 2f

        // 3. Draw the scaled background image
        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
            dstSize = IntSize(renderWidth.toInt(), renderHeight.toInt())
        )

        // 4. Draw bounding boxes mapped from OpenCV pixel space to Compose canvas space
        cellMatrix.forEachIndexed { rowIdx, rowCells ->
            rowCells.forEachIndexed { colIdx, cell ->
                val box = cell.rect

                // Scale coordinates
                val scaledX = offsetX + (box.x * scale)
                val scaledY = offsetY + (box.y * scale)
                val scaledW = box.width * scale
                val scaledH = box.height * scale

                val isSelected = selectedCell == Pair(rowIdx, colIdx)

                if (isSelected) {
                    // Semi-transparent blue fill for the active cell
                    drawRect(
                        color = Color(0x552196F3),
                        topLeft = Offset(scaledX, scaledY),
                        size = Size(scaledW, scaledH)
                    )
                    // Solid blue outline
                    drawRect(
                        color = Color(0xFF1976D2),
                        topLeft = Offset(scaledX, scaledY),
                        size = Size(scaledW, scaledH),
                        style = Stroke(width = 3f)
                    )
                } else {
                    // Subtle emerald green border for all other detected cells
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
