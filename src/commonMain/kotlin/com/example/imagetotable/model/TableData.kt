package com.example.imagetotable.model

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

class TableData(
    initialHeaders: List<String>,
    initialRows: List<List<String>>,
    initialRowNames: List<String>? = null
) {
    val headers: SnapshotStateList<String> = mutableStateListOf(*initialHeaders.toTypedArray())
    val rowNames: SnapshotStateList<String> = mutableStateListOf(
        *(initialRowNames ?: List(initialRows.size) { "Row ${it + 1}" }).toTypedArray()
    )
    val rows: SnapshotStateList<SnapshotStateList<String>> = mutableStateListOf(
        *initialRows.map { mutableStateListOf(*it.toTypedArray()) }.toTypedArray()
    )

    fun loadExtractedData(newHeaders: List<String>, newRows: List<List<String>>) {
        headers.clear()
        headers.addAll(newHeaders)
        rowNames.clear()
        rows.clear()
        for ((idx, r) in newRows.withIndex()) {
            rowNames.add("Row ${idx + 1}")
            rows.add(mutableStateListOf(*r.toTypedArray()))
        }
    }

    fun updateRowName(rowIndex: Int, name: String) {
        if (rowIndex in rowNames.indices) {
            rowNames[rowIndex] = name
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
        val row = rows.removeAt(fromIndex)
        rows.add(toIndex, row)
    }

    fun updateCell(rowIndex: Int, colIndex: Int, value: String) {
        if (rowIndex in rows.indices && colIndex in headers.indices) {
            rows[rowIndex][colIndex] = value
        }
    }

    fun addRow(name: String = "Row ${rows.size + 1}") {
        rowNames.add(name)
        val newRow = mutableStateListOf(*Array(headers.size) { "" })
        rows.add(newRow)
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

    fun pasteTsvData(tsvText: String, startRow: Int, startCol: Int) {
        val lines = tsvText.trimEnd().split("\n")
        if (lines.isEmpty()) return

        val parsed = lines.map { it.split("\t") }
        val requiredRows = startRow + parsed.size
        val maxNewCols = parsed.maxOfOrNull { it.size } ?: 0
        val requiredCols = startCol + maxNewCols

        while (headers.size < requiredCols) {
            addColumn()
        }
        while (rows.size < requiredRows) {
            addRow()
        }

        for ((rOffset, rowData) in parsed.withIndex()) {
            for ((cOffset, cellValue) in rowData.withIndex()) {
                val targetR = startRow + rOffset
                val targetC = startCol + cOffset
                rows[targetR][targetC] = cellValue.trim()
            }
        }
    }

    fun toTsvString(): String {
        val sb = StringBuilder()
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
