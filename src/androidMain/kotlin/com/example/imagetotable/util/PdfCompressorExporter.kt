package com.example.imagetotable.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

data class PdfPageItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    var bitmap: Bitmap,
    var rotationDegrees: Float = 0f
)

object PdfCompressorExporter {

    suspend fun exportPagesToPdf(
        pages: List<PdfPageItem>,
        targetSizeKb: Int?,
        outputStream: OutputStream,
        onProgress: (String) -> Unit = {}
    ): Long = withContext(Dispatchers.Default) {
        if (pages.isEmpty()) return@withContext 0L

        // Apply page-level rotations
        val orientedBitmaps = pages.map { page ->
            if (page.rotationDegrees % 360f != 0f) {
                val matrix = Matrix().apply { postRotate(page.rotationDegrees) }
                Bitmap.createBitmap(page.bitmap, 0, 0, page.bitmap.width, page.bitmap.height, matrix, true)
            } else {
                page.bitmap
            }
        }

        if (targetSizeKb == null || targetSizeKb <= 0) {
            withContext(Dispatchers.Main) { onProgress("Compiling high-resolution PDF...") }
            val rawPdfBytes = buildPdfByteArray(orientedBitmaps, scale = 1.0f, jpegQuality = 92)
            outputStream.write(rawPdfBytes)
            outputStream.flush()
            return@withContext rawPdfBytes.size.toLong()
        }

        val targetSizeBytes = targetSizeKb * 1024L
        withContext(Dispatchers.Main) { onProgress("Optimizing for target size: ${targetSizeKb} KB...") }

        var minQuality = 15
        var maxQuality = 90
        var bestPdfBytes: ByteArray? = null

        // Pass 1: Quality Binary Search
        for (step in 0..4) {
            val candidateQuality = (minQuality + maxQuality) / 2
            val candidatePdf = buildPdfByteArray(orientedBitmaps, scale = 1.0f, jpegQuality = candidateQuality)

            if (candidatePdf.size <= targetSizeBytes) {
                bestPdfBytes = candidatePdf
                minQuality = candidateQuality + 1
            } else {
                maxQuality = candidateQuality - 1
            }
        }

        // Pass 2: Dimension Scaling (if still exceeds target)
        if (bestPdfBytes == null || bestPdfBytes.size > targetSizeBytes) {
            var scale = 0.85f
            while (scale >= 0.25f && (bestPdfBytes == null || bestPdfBytes.size > targetSizeBytes)) {
                val candidatePdf = buildPdfByteArray(orientedBitmaps, scale = scale, jpegQuality = 55)
                bestPdfBytes = candidatePdf
                if (candidatePdf.size <= targetSizeBytes) break
                scale -= 0.15f
            }
        }

        val finalBytes = bestPdfBytes ?: buildPdfByteArray(orientedBitmaps, scale = 0.4f, jpegQuality = 35)
        outputStream.write(finalBytes)
        outputStream.flush()
        return@withContext finalBytes.size.toLong()
    }

    suspend fun generateTempPdfFile(
        cacheDir: File,
        pages: List<PdfPageItem>,
        targetSizeKb: Int?,
        fileName: String = "compiled_preview.pdf"
    ): Pair<File, Long> = withContext(Dispatchers.IO) {
        val file = File(cacheDir, fileName)
        var sizeBytes = 0L
        FileOutputStream(file).use { out ->
            sizeBytes = exportPagesToPdf(pages, targetSizeKb, out)
        }
        Pair(file, sizeBytes)
    }

    private fun buildPdfByteArray(
        bitmaps: List<Bitmap>,
        scale: Float,
        jpegQuality: Int
    ): ByteArray {
        val pdfDocument = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        try {
            bitmaps.forEachIndexed { index, original ->
                val scaledW = (original.width * scale).roundToInt().coerceAtLeast(100)
                val scaledH = (original.height * scale).roundToInt().coerceAtLeast(100)
                val scaled = Bitmap.createScaledBitmap(original, scaledW, scaledH, true)

                val compressedStream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, compressedStream)
                val compressedBytes = compressedStream.toByteArray()
                val decodedPageBmp = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)

                val pageInfo = PdfDocument.PageInfo.Builder(decodedPageBmp.width, decodedPageBmp.height, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(decodedPageBmp, 0f, 0f, paint)
                pdfDocument.finishPage(page)
            }

            val pdfByteStream = ByteArrayOutputStream()
            pdfDocument.writeTo(pdfByteStream)
            return pdfByteStream.toByteArray()
        } finally {
            pdfDocument.close()
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format("%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes Bytes"
        }
    }
}
