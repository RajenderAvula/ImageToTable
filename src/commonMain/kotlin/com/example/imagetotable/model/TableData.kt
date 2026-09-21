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

    // --- CREATE NEW TABLE FROM ACTIVE FILTERS ---
    fun createSubTable(
        newTableName: String,
        selectedRowIndices: List<Int>,
        selectedColIndices: List<Int>
    ): TableData {
        val subHeaders = selectedColIndices.map { headers[it].copy() }
        val subRowNames = selectedRowIndices.map { rowNames.getOrElse(it) { "Row" } }
        val subRows = selectedRowIndices.map { rIdx ->
            selectedColIndices.map { cIdx -> rows[rIdx].getOrElse(cIdx) { "" } }
        }
        return TableData(
            initialName = newTableName,
            initialHeaders = if (subHeaders.isEmpty()) listOf(ColumnDef("Col 1")) else subHeaders,
            initialRows = if (subRows.isEmpty()) listOf(listOf("")) else subRows,
            initialRowNames = subRowNames
        )
    }

    // --- MULTI-CELL CLIPBOARD & MOVEMENT ---
    fun copyCells(coords: Collection<Pair<Int, Int>>, isCut: Boolean = false): CellClipboard? {
        if (coords.isEmpty()) return null
        val minR = coords.minOf { it.first }
        val minC = coords.minOf { it.second }

        val items = coords.mapNotNull { (r, c) ->
            if (r in rows.indices && c in headers.indices) {
                CellOffsetValue(r - minR, c - minC, rows[r][c])
            } else null
        }
        return CellClipboard(items = items, isCut = isCut, sourceCoords = coords.toList())
    }

    fun pasteCells(targetRow: Int, targetCol: Int, clipboard: CellClipboard): List<Pair<Int, Int>> {
        if (clipboard.items.isEmpty()) return emptyList()

        if (clipboard.isCut) {
            for ((sr, sc) in clipboard.sourceCoords) {
                if (sr in rows.indices && sc in headers.indices) {
                    rows[sr][sc] = ""
                }
            }
        }

        val maxReqRow = targetRow + (clipboard.items.maxOfOrNull { it.rowOffset } ?: 0)
        val maxReqCol = targetCol + (clipboard.items.maxOfOrNull { it.colOffset } ?: 0)

        while (headers.size <= maxReqCol) addColumn("Col ${headers.size + 1}")
        while (rows.size <= maxReqRow) addRow()

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

    fun shiftCellsBatch(coords: Collection<Pair<Int, Int>>, direction: ShiftDirection): List<Pair<Int, Int>> {
        if (coords.isEmpty()) return emptyList()
        val dr = when (direction) { ShiftDirection.UP -> -1; ShiftDirection.DOWN -> 1; else -> 0 }
        val dc = when (direction) { ShiftDirection.LEFT -> -1; ShiftDirection.RIGHT -> 1; else -> 0 }

        val canMove = coords.all { (r, c) ->
            (r + dr) in rows.indices && (c + dc) in headers.indices
        }
        if (!canMove) return coords.toList()

        val snapshot = coords.associateWith { (r, c) -> rows[r][c] }
        for ((r, c) in coords) rows[r][c] = ""

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
