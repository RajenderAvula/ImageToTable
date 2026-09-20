package com.example.imagetotable.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TableData(
    initialHeaders: List<String>,
    initialRows: List<List<String>>,
    initialRowNames: List<String>? = null,
    initialDateTime: String? = null
) {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // The entire table is bound to this date & time
    var tableDateTime by mutableStateOf(
        initialDateTime ?: LocalDateTime.now().format(formatter)
    )

    val headers: SnapshotStateList<String> = mutableStateListOf(*initialHeaders.toTypedArray())
    val rowNames: SnapshotStateList<String> = mutableStateListOf(
        *(initialRowNames ?: List(initialRows.size) { "Row ${it + 1}" }).toTypedArray()
    )
    val rows: SnapshotStateList<SnapshotStateList<String>> = mutableStateListOf(
        *initialRows.map { mutableStateListOf(*it.toTypedArray()) }.toTypedArray()
    )

    private fun touchDateTime() {
        tableDateTime = LocalDateTime.now().format(formatter)
    }

    fun setCustomDateTime(customDateTime: String) {
        tableDateTime = customDateTime
    }

    fun loadExtractedData(newHeaders: List<String>, newRows: List<List<String>>) {
        headers.clear()
        headers.addAll(newHeaders)
        rowNames.clear()
        rows.clear()
        for ((idx, r) in newRows.withIndex()) {
            rowNames.add("Row ${idx + 1}")
            rows.add(mutableStateListOf(*r.toTypedArray()))
        }
        touchDateTime()
    }

    fun updateHeader(colIndex: Int, newName: String) {
        if (colIndex in headers.indices) {
            headers[colIndex] = newName
            touchDateTime()
        }
    }

    fun updateRowName(rowIndex: Int, name: String) {
        if (rowIndex in rowNames.indices) {
            rowNames[rowIndex] = name
            touchDateTime()
        }
    }

    fun updateCell(rowIndex: Int, colIndex: Int, value: String) {
        if (rowIndex in rows.indices && colIndex in headers.indices) {
            rows[rowIndex][colIndex] = value
            touchDateTime()
        }
    }

    fun updateFullRow(rowIndex: Int, name: String, values: List<String>) {
        if (rowIndex in rows.indices) {
            rowNames[rowIndex] = name
            rows[rowIndex].clear()
            rows[rowIndex].addAll(values)
            touchDateTime()
        }
    }

    fun addManualRow(name: String, values: List<String>) {
        rowNames.add(name.ifBlank { "Row ${rows.size + 1}" })
        val paddedValues = values + List((headers.size - values.size).coerceAtLeast(0)) { "" }
        rows.add(mutableStateListOf(*paddedValues.take(headers.size).toTypedArray()))
        touchDateTime()
    }

    fun addRow(name: String = "Row ${rows.size + 1}") {
        rowNames.add(name)
        rows.add(mutableStateListOf(*Array(headers.size) { "" }))
        touchDateTime()
    }

    fun addColumn(name: String = "Col ${headers.size + 1}") {
        headers.add(name)
        for (row in rows) {
            row.add("")
        }
        touchDateTime()
    }

    fun deleteRow(index: Int) {
        if (index in rows.indices) {
            rowNames.removeAt(index)
            rows.removeAt(index)
            touchDateTime()
        }
    }

    fun deleteColumn(index: Int) {
        if (index in headers.indices && headers.size > 1) {
            headers.removeAt(index)
            for (row in rows) {
                row.removeAt(index)
            }
            touchDateTime()
        }
    }

    fun moveColumn(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in headers.indices || toIndex !in headers.indices) return
        val header = headers.removeAt(fromIndex)
        headers.add(toIndex, header)
        for (row in rows) {
            val cell = row.removeAt(fromIndex)
            row.add(toIndex, cell)
        }
        touchDateTime()
    }

    fun moveRow(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in rows.indices || toIndex !in rows.indices) return
        val name = rowNames.removeAt(fromIndex)
        rowNames.add(toIndex, name)
        val row = rows.removeAt(fromIndex)
        rows.add(toIndex, row)
        touchDateTime()
    }

    fun importCsv(csvContent: String) {
        val lines = csvContent.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return

        var dataStartIdx = 0
        // Extract table timestamp if stored as metadata on line 1 (# Table Timestamp: ...)
        if (lines.first().startsWith("# Table Timestamp:")) {
            tableDateTime = lines.first().removePrefix("# Table Timestamp:").trim()
            dataStartIdx = 1
        }

        val remainingLines = lines.drop(dataStartIdx)
        if (remainingLines.isEmpty()) return

        val parsed = remainingLines.map { parseCsvLine(it) }
        val rawHeaders = parsed.first()
        val dataLines = if (parsed.size > 1) parsed.drop(1) else emptyList()

        val hasRowTitle = rawHeaders.firstOrNull()?.equals("Row Title", ignoreCase = true) == true
        val actualHeaders = if (hasRowTitle) rawHeaders.drop(1) else rawHeaders

        headers.clear()
        headers.addAll(actualHeaders)
        rowNames.clear()
        rows.clear()

        for ((idx, line) in dataLines.withIndex()) {
            val rName = if (hasRowTitle) line.getOrElse(0) { "Row ${idx + 1}" } else "Row ${idx + 1}"
            val cellValues = if (hasRowTitle) line.drop(1) else line

            rowNames.add(rName)
            val padded = cellValues + List((actualHeaders.size - cellValues.size).coerceAtLeast(0)) { "" }
            rows.add(mutableStateListOf(*padded.take(actualHeaders.size).toTypedArray()))
        }
        touchDateTime()
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '\"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        result.add(sb.toString().trim())
        return result
    }

    fun toTsvString(): String {
        val sb = StringBuilder()
        sb.append("# Table Timestamp: ").append(tableDateTime).append("\n")
        sb.append("Row Title\t").append(headers.joinToString("\t")).append("\n")
        for ((idx, row) in rows.withIndex()) {
            sb.append(rowNames.getOrElse(idx) { "Row ${idx + 1}" })
                .append("\t")
                .append(row.joinToString("\t"))
                .append("\n")
        }
        return sb.toString()
    }
}
