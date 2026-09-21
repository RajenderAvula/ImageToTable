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

// --- MULTI-CELL CLIPBOARD MODELS ---
data class CellOffsetValue(
    val rowOffset: Int,
    val colOffset: Int,
    val value: String
)

data class CellClipboard(
    val items: List<CellOffsetValue>,
    val isCut: Boolean,
    val sourceCoords: List<Pair<Int, Int>>
)

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

    // --- MULTI-CELL COPY & CUT ---
    fun copyCells(coords: Collection<Pair<Int, Int>>, isCut: Boolean = false): CellClipboard? {
        if (coords.isEmpty()) return null
        val minR = coords.minOf { it.first }
        val minC = coords.minOf { it.second }

        val items = coords.mapNotNull { (r, c) ->
            if (r in rows.indices && c in headers.indices) {
                CellOffsetValue(
                    rowOffset = r - minR,
                    colOffset = c - minC,
                    value = rows[r][c]
                )
            } else null
        }
        return CellClipboard(items = items, isCut = isCut, sourceCoords = coords.toList())
    }

    // --- MULTI-CELL PASTE / MOVE TO TARGET ---
    fun pasteCells(targetRow: Int, targetCol: Int, clipboard: CellClipboard): List<Pair<Int, Int>> {
        if (clipboard.items.isEmpty()) return emptyList()

        // 1. If this was a Cut / Move, clear the original cells first
        if (clipboard.isCut) {
            for ((sr, sc) in clipboard.sourceCoords) {
                if (sr in rows.indices && sc in headers.indices) {
                    rows[sr][sc] = ""
                }
            }
        }

        // 2. Ensure table dimensions expand to accommodate the pasted block
        val maxReqRow = targetRow + (clipboard.items.maxOfOrNull { it.rowOffset } ?: 0)
        val maxReqCol = targetCol + (clipboard.items.maxOfOrNull { it.colOffset } ?: 0)

        while (headers.size <= maxReqCol) {
            addColumn("Col ${headers.size + 1}")
        }
        while (rows.size <= maxReqRow) {
            addRow()
        }

        // 3. Write copied/moved values into the target region
        val newSelection = mutableListOf<Pair<Int, Int>>()
        for (item in clipboard.items) {
            val destR = targetRow + item.rowOffset
            val destC = targetCol + item.colOffset
            if (destR in rows.indices && destC in headers.indices) {
                rows[destR][destC] = item.value
                newSelection.add(Pair(destR, destC))
            }
        }
        markUpdated()
        return newSelection
    }

    // --- BATCH CELL SHIFTING (Up, Down, Left, Right) ---
    fun shiftCellsBatch(coords: Collection<Pair<Int, Int>>, direction: ShiftDirection): List<Pair<Int, Int>> {
        if (coords.isEmpty()) return emptyList()

        val dr = when (direction) {
            ShiftDirection.UP -> -1
            ShiftDirection.DOWN -> 1
            else -> 0
        }
        val dc = when (direction) {
            ShiftDirection.LEFT -> -1
            ShiftDirection.RIGHT -> 1
            else -> 0
        }

        // Validate boundary constraints for the entire block
        val canMove = coords.all { (r, c) ->
            val nr = r + dr
            val nc = c + dc
            nr in rows.indices && nc in headers.indices
        }
        if (!canMove) return coords.toList()

        // Extract snapshot of values
        val snapshot = coords.associateWith { (r, c) -> rows[r][c] }

        // Clear existing coordinates
        for ((r, c) in coords) {
            rows[r][c] = ""
        }

        // Reassign shifted values
        val newCoords = mutableListOf<Pair<Int, Int>>()
        for ((orig, value) in snapshot) {
            val nr = orig.first + dr
            val nc = orig.second + dc
            rows[nr][nc] = value
            newCoords.add(Pair(nr, nc))
        }

        markUpdated()
        return newCoords
    }

    // --- SNAPSHOT BACKUP & REVERT ---
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

    // --- AXIS TRANSPOSE ---
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
