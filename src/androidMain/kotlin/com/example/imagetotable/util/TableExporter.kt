package com.example.imagetotable.util

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.example.imagetotable.model.TableData
import java.io.OutputStream

object TableExporter {

    fun exportToPdf(tableData: TableData, outputStream: OutputStream) {
        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        val canvas = page.canvas

        val paint = Paint().apply { isAntiAlias = true }
        val textPaint = Paint().apply {
            isAntiAlias = true
            textSize = 10f
            color = Color.BLACK
        }

        // Table Metadata Header
        val titlePaint = Paint().apply {
            isAntiAlias = true
            textSize = 14f
            isFakeBoldText = true
            color = Color.rgb(30, 136, 229)
        }
        canvas.drawText("Extracted Table", 30f, 35f, titlePaint)

        val metaPaint = Paint().apply {
            isAntiAlias = true
            textSize = 10f
            color = Color.DKGRAY
        }
        canvas.drawText("Bound Date & Time: ${tableData.tableDateTime}", 30f, 50f, metaPaint)

        val startX = 30f
        var startY = 70f
        val colWidth = ((pageInfo.pageWidth - 60f) / (tableData.headers.size + 1)).coerceAtLeast(60f)
        val rowHeight = 24f

        // Header Background
        paint.color = Color.rgb(232, 238, 245)
        canvas.drawRect(startX, startY, startX + (colWidth * (tableData.headers.size + 1)), startY + rowHeight, paint)

        // Header Text
        textPaint.isFakeBoldText = true
        canvas.drawText("Row Name", startX + 5f, startY + 16f, textPaint)
        tableData.headers.forEachIndexed { idx, h ->
            canvas.drawText(h.take(12), startX + ((idx + 1) * colWidth) + 5f, startY + 16f, textPaint)
        }

        // Rows
        textPaint.isFakeBoldText = false
        paint.style = Paint.Style.STROKE
        paint.color = Color.LTGRAY

        startY += rowHeight
        for (rIdx in tableData.rows.indices) {
            val rName = tableData.rowNames.getOrElse(rIdx) { "#${rIdx + 1}" }
            canvas.drawText(rName.take(12), startX + 5f, startY + 16f, textPaint)

            val rowCells = tableData.rows[rIdx]
            for (cIdx in tableData.headers.indices) {
                val cellVal = rowCells.getOrElse(cIdx) { "" }
                canvas.drawText(cellVal.take(12), startX + ((cIdx + 1) * colWidth) + 5f, startY + 16f, textPaint)
            }
            canvas.drawLine(startX, startY + rowHeight, startX + (colWidth * (tableData.headers.size + 1)), startY + rowHeight, paint)
            startY += rowHeight
            if (startY > pageInfo.pageHeight - 50) break
        }

        pdfDoc.finishPage(page)
        pdfDoc.writeTo(outputStream)
        pdfDoc.close()
    }

    fun exportToCsv(tableData: TableData, outputStream: OutputStream) {
        val sb = StringBuilder()
        sb.append("# Table Timestamp: ${tableData.tableDateTime}\r\n")
        val allHeaders = listOf("Row Title") + tableData.headers
        sb.append(allHeaders.joinToString(",") { escapeCsv(it) }).append("\r\n")

        for (rIdx in tableData.rows.indices) {
            val rName = tableData.rowNames.getOrElse(rIdx) { "Row ${rIdx + 1}" }
            val cells = listOf(rName) + tableData.rows[rIdx]
            sb.append(cells.joinToString(",") { escapeCsv(it) }).append("\r\n")
        }
        outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    fun exportToWordHtmlDoc(tableData: TableData, outputStream: OutputStream) {
        val sb = StringBuilder()
        sb.append("<html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:w='urn:schemas-microsoft-com:office:word'>")
        sb.append("<head><meta charset='utf-8'><title>Table Export</title></head><body>")
        sb.append("<h2>Table Export</h2>")
        sb.append("<p><strong>Bound Date & Time:</strong> ").append(tableData.tableDateTime).append("</p>")
        sb.append("<table border='1' style='border-collapse:collapse; font-family:sans-serif; width:100%;'>")
        sb.append("<tr style='background-color:#E8EEF5;'><th>Row Title</th>")
        for (h in tableData.headers) {
            sb.append("<th>").append(escapeHtml(h)).append("</th>")
        }
        sb.append("</tr>")

        for (rIdx in tableData.rows.indices) {
            sb.append("<tr>")
            val rName = tableData.rowNames.getOrElse(rIdx) { "Row ${rIdx + 1}" }
            sb.append("<td style='font-weight:bold;'>").append(escapeHtml(rName)).append("</td>")
            for (cell in tableData.rows[rIdx]) {
                sb.append("<td>").append(escapeHtml(cell)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table></body></html>")
        outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun escapeCsv(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
