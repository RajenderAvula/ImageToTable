package com.example.imagetotable.model

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.time.LocalDate

class TableData(
    initialHeaders: List<String>,
    initialRows: List<List<String>>,
    initialRowNames: List<String>? = null,
    initialDates: List<String>? = null
) {
    val headers: SnapshotStateList<String> = mutableStateListOf(*initialHeaders.toTypedArray())
    val rowNames: SnapshotStateList<String> = mutableStateListOf(
        *(initialRowNames ?: List(initialRows.size) { "Row ${it + 1}" }).toTypedArray()
    )
    val rowDates: SnapshotStateList<String> = mutableStateListOf(
        *(initialDates ?: List(initialRows.size) { LocalDate.now().toString() }).toTypedArray()
    )
    val rows: SnapshotStateList<SnapshotStateList<String>> = mutableStateListOf(
        *initialRows.map { mutableStateListOf(*it.toTypedArray()) }.toTypedArray()
    )

    fun loadExtractedData(newHeaders: List<String>, newRows: List<List<String>>) {
        headers.clear()
        headers.addAll(newHeaders)
        rowNames.clear()
        rowDates.clear()
        rows.clear()
        val today = LocalDate.now().toString()
        for ((idx, r) in newRows.withIndex()) {
            rowNames.add("Row ${idx + 1}")
            rowDates.add(today)
            rows.add(mutableStateListOf(*r.toTypedArray()))
        }
    }

    fun updateRowName(rowIndex: Int, name: String) {
        if (rowIndex in rowNames.indices) rowNames[rowIndex] = name
    }

    fun updateRowDate(rowIndex: Int, date: String) {
        if (rowIndex in rowDates.indices) rowDates[rowIndex] = date
    }

    fun updateCell(rowIndex: Int, colIndex: Int, value: String) {
        if (rowIndex in rows.indices && colIndex in headers.indices) {
            rows[rowIndex][colIndex] = value
        }
    }

    fun updateFullRow(rowIndex: Int, name: String, date: String, values: List<String>) {
        if (rowIndex in rows.indices) {
            rowNames[rowIndex] = name
            rowDates[rowIndex] = date
            rows[rowIndex].clear()
            rows[rowIndex].addAll(values)
        }
    }

    fun addManualRow(name: String, date: String, values: List<String>) {
        rowNames.add(name.ifBlank { "Row ${rows.size + 1}" })
        rowDates.add(date.ifBlank { LocalDate.now().toString() })
        val paddedValues = values + List((headers.size - values.size).coerceAtLeast(0)) { "" }
        rows.add(mutableStateListOf(*paddedValues.take(headers.size).toTypedArray()))
    }

    fun addRow(name: String = "Row ${rows.size + 1}") {
        rowNames.add(name)
        rowDates.add(LocalDate.now().toString())
        rows.add(mutableStateListOf(*Array(headers.size) { "" }))
    }

    fun addColumn(name: String = "Col ${headers.size + 1}") {
        headers.add(name)
        for (row in rows) {
            row.add("")
        }
    }

    fun deleteRow(index: Int) {
        if (index in rows.indices) {
            rowNames.removeAt(index)
            rowDates.removeAt(index)
            rows.removeAt(index)
        }
    }

    fun deleteColumn(index: Int) {
        if (index in headers.indices && headers.size > 1) {
            headers.removeAt(index)
            for (row in rows) {
                row.removeAt(index)
            }
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
    }

    fun moveRow(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in rows.indices || toIndex !in rows.indices) return
        val name = rowNames.removeAt(fromIndex)
        rowNames.add(toIndex, name)
        val date = rowDates.removeAt(fromIndex)
        rowDates.add(toIndex, date)
        val row = rows.removeAt(fromIndex)
        rows.add(toIndex, row)
    }

    fun importCsv(csvContent: String) {
        val lines = csvContent.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return

        val parsed = lines.map { parseCsvLine(it) }
        val rawHeaders = parsed.first()
        val dataLines = if (parsed.size > 1) parsed.drop(1) else emptyList()

        // Check if first columns represent Row Title and Date
        val hasRowTitle = rawHeaders.firstOrNull()?.equals("Row Title", ignoreCase = true) == true
        val hasDate = rawHeaders.getOrNull(1)?.equals("Date", ignoreCase = true) == true

        val actualHeaders = when {
            hasRowTitle && hasDate -> rawHeaders.drop(2)
            hasRowTitle -> rawHeaders.drop(1)
            else -> rawHeaders
        }

        headers.clear()
        headers.addAll(actualHeaders)
        rowNames.clear()
        rowDates.clear()
        rows.clear()

        val today = LocalDate.now().toString()
        for ((idx, line) in dataLines.withIndex()) {
            var rName = "Row ${idx + 1}"
            var rDate = today
            var cellValues = line

            if (hasRowTitle && hasDate) {
                rName = line.getOrElse(0) { rName }
                rDate = line.getOrElse(1) { today }
                cellValues = line.drop(2)
            } else if (hasRowTitle) {
                rName = line.getOrElse(0) { rName }
                cellValues = line.drop(1)
            }

            rowNames.add(rName)
            rowDates.add(rDate)
            val padded = cellValues + List((actualHeaders.size - cellValues.size).coerceAtLeast(0)) { "" }
            rows.add(mutableStateListOf(*padded.take(actualHeaders.size).toTypedArray()))
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = java.lang.StringBuilder()
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
        val sb = java.lang.StringBuilder()
        sb.append("Row Title\tDate\t").append(headers.joinToString("\t")).append("\n")
        for ((idx, row) in rows.withIndex()) {
            sb.append(rowNames.getOrElse(idx) { "Row ${idx + 1}" })
                .append("\t")
                .append(rowDates.getOrElse(idx) { "" })
                .append("\t")
                .append(row.joinToString("\t"))
                .append("\n")
        }
        return sb.toString()
    }
}
