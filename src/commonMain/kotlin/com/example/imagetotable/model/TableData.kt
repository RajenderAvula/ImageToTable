package com.example.imagetotable.model

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class ColumnType(val label: String) {
    TEXT("Text"),
    NUMBER("Whole Number"),
    DECIMAL("Decimal"),
    DATE("Date (YYYY-MM-DD)"),
    TIME("Time (HH:MM)")
}

data class ColumnDef(
    var name: String,
    var type: ColumnType = ColumnType.TEXT
)

enum class ShiftDirection { UP, DOWN, LEFT, RIGHT }

class TableData(
    val id: String = UUID.randomUUID().toString(),
    initialName: String = "Untitled Table",
    initialHeaders: List<ColumnDef>,
    initialRows: List<List<String>>,
    initialRowNames: List<String>? = null,
    initialDateTime: String? = null
) {
    var tableName by mutableStateOf(initialName)
    var tableDateTime by mutableStateOf(
        initialDateTime ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    )

    val headers: SnapshotStateList<ColumnDef> = mutableStateListOf(*initialHeaders.toTypedArray())
    val rowNames: SnapshotStateList<String> = mutableStateListOf(
        *(initialRowNames ?: List(initialRows.size) { "Row ${it + 1}" }).toTypedArray()
    )
    val rows: SnapshotStateList<SnapshotStateList<String>> = mutableStateListOf(
        *initialRows.map { mutableStateListOf(*it.toTypedArray()) }.toTypedArray()
    )

    fun markUpdated() {
        tableDateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    // --- TRANSPOSE / AXIS FIX (When rows are extracted as columns) ---
    fun transposeTable() {
        if (rows.isEmpty() || headers.isEmpty()) return

        val oldColsCount = headers.size
        val oldRowsCount = rows.size

        val newHeaders = (1..oldRowsCount).map {
            ColumnDef(rowNames.getOrElse(it - 1) { "Col $it" }, ColumnType.TEXT)
        }
        val newRowNames = headers.map { it.name }

        val newGrid = mutableListOf<MutableList<String>>()
        for (c in 0 until oldColsCount) {
            val newRow = mutableListOf<String>()
            for (r in 0 until oldRowsCount) {
                newRow.add(rows[r].getOrElse(c) { "" })
            }
            newGrid.add(newRow)
        }

        headers.clear()
        headers.addAll(newHeaders)
        rowNames.clear()
        rowNames.addAll(newRowNames)
        rows.clear()
        newGrid.forEach { rows.add(mutableStateListOf(*it.toTypedArray())) }
        markUpdated()
    }

    // --- INDIVIDUAL CELL SHIFTING (Independent of rows & columns) ---
    fun shiftIndividualCell(r: Int, c: Int, direction: ShiftDirection) {
        if (r !in rows.indices || c !in headers.indices) return

        val targetR = when (direction) {
            ShiftDirection.UP -> r - 1
            ShiftDirection.DOWN -> r + 1
            else -> r
        }
        val targetC = when (direction) {
            ShiftDirection.LEFT -> c - 1
            ShiftDirection.RIGHT -> c + 1
            else -> c
        }

        if (targetR in rows.indices && targetC in headers.indices) {
            val temp = rows[r][c]
            rows[r][c] = rows[targetR][targetC]
            rows[targetR][targetC] = temp
            markUpdated()
        }
    }

    // --- ROW & COLUMN MUTATIONS ---
    fun addRow(name: String = "Row ${rows.size + 1}") {
        rowNames.add(name)
        rows.add(mutableStateListOf(*Array(headers.size) { "" }))
        markUpdated()
    }

    fun deleteRow(index: Int) {
        if (index in rows.indices) {
            rowNames.removeAt(index)
            rows.removeAt(index)
            markUpdated()
        }
    }

    fun moveRow(from: Int, to: Int) {
        if (from !in rows.indices || to !in rows.indices) return
        val rName = rowNames.removeAt(from)
        rowNames.add(to, rName)
        val rData = rows.removeAt(from)
        rows.add(to, rData)
        markUpdated()
    }

    fun addColumn(name: String, type: ColumnType = ColumnType.TEXT) {
        headers.add(ColumnDef(name, type))
        rows.forEach { it.add("") }
        markUpdated()
    }

    fun deleteColumn(index: Int) {
        if (index in headers.indices && headers.size > 1) {
            headers.removeAt(index)
            rows.forEach { it.removeAt(index) }
            markUpdated()
        }
    }

    fun moveColumn(from: Int, to: Int) {
        if (from !in headers.indices || to !in headers.indices) return
        val h = headers.removeAt(from)
        headers.add(to, h)
        rows.forEach { row ->
            val cell = row.removeAt(from)
            row.add(to, cell)
        }
        markUpdated()
    }

    fun updateCell(r: Int, c: Int, value: String) {
        if (r in rows.indices && c in headers.indices) {
            rows[r][c] = value
            markUpdated()
        }
    }

    // --- CREATE SUB-TABLE FROM FILTERED VIEW ---
    fun createSubTable(
        newTableName: String,
        selectedRowIndices: List<Int>,
        selectedColIndices: List<Int>
    ): TableData {
        val subHeaders = selectedColIndices.map { headers[it].copy() }
        val subRowNames = selectedRowIndices.map { rowNames[it] }
        val subRows = selectedRowIndices.map { rIdx ->
            selectedColIndices.map { cIdx -> rows[rIdx][cIdx] }
        }
        return TableData(
            initialName = newTableName,
            initialHeaders = subHeaders,
            initialRows = subRows,
            initialRowNames = subRowNames
        )
    }

    fun loadExtractedData(newHeaders: List<String>, newRows: List<List<String>>) {
        headers.clear()
        headers.addAll(newHeaders.map { ColumnDef(it, ColumnType.TEXT) })
        rowNames.clear()
        rows.clear()
        for ((idx, r) in newRows.withIndex()) {
            rowNames.add("Row ${idx + 1}")
            rows.add(mutableStateListOf(*r.toTypedArray()))
        }
        markUpdated()
    }

    fun toTsvString(): String {
        val sb = java.lang.StringBuilder()
        sb.append("# Title: ").append(tableName).append(" | Date: ").append(tableDateTime).append("\n")
        sb.append("Row Title\t").append(headers.joinToString("\t") { it.name }).append("\n")
        for ((idx, row) in rows.withIndex()) {
            sb.append(rowNames.getOrElse(idx) { "Row ${idx + 1}" })
                .append("\t")
                .append(row.joinToString("\t"))
                .append("\n")
        }
        return sb.toString()
    }
}

// Global Repository managing multi-table instances across dates
object TableRepository {
    val tables = mutableStateListOf<TableData>()

    fun saveOrUpdate(table: TableData) {
        val existingIndex = tables.indexOfFirst { it.id == table.id }
        if (existingIndex >= 0) {
            tables[existingIndex] = table
        } else {
            tables.add(table)
        }
    }

    fun getTablesForDate(datePrefix: String): List<TableData> {
        return tables.filter { it.tableDateTime.startsWith(datePrefix) }
    }
}
