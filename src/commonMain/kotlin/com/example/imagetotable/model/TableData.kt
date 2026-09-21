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

    // --- SNAPSHOT & REVERT (For Save & Cancel) ---
    fun createSnapshot(): TableData {
        return TableData(
            id = this.id,
            initialName = this.tableName,
            initialHeaders = this.headers.map { it.copy() },
            initialRows = this.rows.map { it.toList() },
            initialRowNames = this.rowNames.toList(),
            initialDateTime = this.tableDateTime
        )
    }

    fun revertToSnapshot(snapshot: TableData) {
        this.tableName = snapshot.tableName
        this.tableDateTime = snapshot.tableDateTime
        this.headers.clear()
        this.headers.addAll(snapshot.headers.map { it.copy() })
        this.rowNames.clear()
        this.rowNames.addAll(snapshot.rowNames)
        this.rows.clear()
        snapshot.rows.forEach { r ->
            this.rows.add(mutableStateListOf(*r.toTypedArray()))
        }
    }

    // --- TRANSPOSE / AXIS SWAP ---
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

    // --- INDIVIDUAL CELL SHIFTING ---
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

    fun importCsv(csvContent: String) {
        val lines = csvContent.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return

        var dataStartIdx = 0
        if (lines.first().startsWith("# Title:")) {
            tableName = lines.first().substringAfter("# Title:").substringBefore("|").trim()
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
        headers.addAll(actualHeaders.map { ColumnDef(it, ColumnType.TEXT) })
        rowNames.clear()
        rows.clear()

        for ((idx, line) in dataLines.withIndex()) {
            val rName = if (hasRowTitle) line.getOrElse(0) { "Row ${idx + 1}" } else "Row ${idx + 1}"
            val cellValues = if (hasRowTitle) line.drop(1) else line

            rowNames.add(rName)
            val padded = cellValues + List((actualHeaders.size - cellValues.size).coerceAtLeast(0)) { "" }
            rows.add(mutableStateListOf(*padded.take(actualHeaders.size).toTypedArray()))
        }
        markUpdated()
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

    fun deleteTable(tableId: String) {
        tables.removeAll { it.id == tableId }
    }
}
