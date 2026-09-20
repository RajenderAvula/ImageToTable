package com.example.imagetotable.model

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

class TableData(
    initialHeaders: List<String>,
    initialRows: List<List<String>>
) {
    val headers: SnapshotStateList<String> = mutableStateListOf(*initialHeaders.toTypedArray())
    val rows: SnapshotStateList<SnapshotStateList<String>> = mutableStateListOf(
        *initialRows.map { mutableStateListOf(*it.toTypedArray()) }.toTypedArray()
    )

    fun loadExtractedData(newHeaders: List<String>, newRows: List<List<String>>) {
        headers.clear()
        headers.addAll(newHeaders)
        rows.clear()
        for (r in newRows) {
            rows.add(mutableStateListOf(*r.toTypedArray()))
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
        val row = rows.removeAt(fromIndex)
        rows.add(toIndex, row)
    }

    fun updateCell(rowIndex: Int, colIndex: Int, value: String) {
        if (rowIndex in rows.indices && colIndex in headers.indices) {
            rows[rowIndex][colIndex] = value
        }
    }

    fun addRow() {
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
        if (index in rows.indices) rows.removeAt(index)
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
        sb.append(headers.joinToString("\t")).append("\n")
        for (row in rows) {
            sb.append(row.joinToString("\t")).append("\n")
        }
        return sb.toString()
    }
}
