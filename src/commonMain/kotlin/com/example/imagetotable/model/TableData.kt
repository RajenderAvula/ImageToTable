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
    initialCorner: String = "#",
    initialDateTime: String? = null
) {
    var tableName by mutableStateOf(initialName)
    var cornerHeader by mutableStateOf(initialCorner)
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

    // --- UNIFIED CELL ACCESS (Corner: -1,-1 | Header: -1,c | Row Name: r,-1 | Cell: r,c) ---
    fun getCellValue(r: Int, c: Int): String {
        return when {
            r == -1 && c == -1 -> cornerHeader
            r == -1 && c in headers.indices -> headers[c].name
            r in rows.indices && c == -1 -> rowNames.getOrElse(r) { "Row ${r + 1}" }
            r in rows.indices && c in headers.indices -> rows[r].getOrElse(c) { "" }
            else -> ""
        }
    }

    fun setCellValue(r: Int, c: Int, value: String) {
        when {
            r == -1 && c == -1 -> cornerHeader = value
            r == -1 && c in headers.indices -> headers[c].name = value
            r in rows.indices && c == -1 -> if (r in rowNames.indices) rowNames[r] = value
            r in rows.indices && c in headers.indices -> rows[r][c] = value
        }
        markUpdated()
    }

    // --- CLEAR / DELETE CELL VALUES ---
    fun clearCells(coords: Collection<Pair<Int, Int>>) {
        for ((r, c) in coords) {
            setCellValue(r, c, "")
        }
        markUpdated()
    }

    fun clearAllValues() {
        for (r in rows.indices) {
            for (c in headers.indices) {
                rows[r][c] = ""
            }
        }
        markUpdated()
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
            initialRowNames = subRowNames,
            initialCorner = cornerHeader
        )
    }

    // --- APPEND VS REPLACE EXTRACTIONS ---
    fun appendExtractedData(newHeaders: List<String>, newRows: List<List<String>>) {
        while (headers.size < newHeaders.size) {
            val newColIdx = headers.size
            headers.add(ColumnDef(newHeaders.getOrElse(newColIdx) { "Col ${newColIdx + 1}" }))
            rows.forEach { it.add("") }
        }
        val startRowIdx = rows.size
        for ((idx, rData) in newRows.withIndex()) {
            rowNames.add("Row ${startRowIdx + idx + 1}")
            val padded = rData + List((headers.size - rData.size).coerceAtLeast(0)) { "" }
            rows.add(mutableStateListOf(*padded.take(headers.size).toTypedArray()))
        }
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

    fun transposeTable() {
        if (rows.isEmpty() || headers.isEmpty()) return
        val oldCols = headers.size
        val oldRows = rows.size

        val newHeaders = (1..oldRows).map { ColumnDef(rowNames.getOrElse(it - 1) { "Col $it" }) }
        val newRowNames = headers.map { it.name }

        val newGrid = mutableListOf<MutableList<String>>()
        for (c in 0 until oldCols) {
            val newRow = mutableListOf<String>()
            for (r in 0 until oldRows) {
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

    fun addRow(name: String = "Row ${rows.size + 1}", index: Int? = null) {
        val targetIdx = index?.coerceIn(0, rows.size) ?: rows.size
        rowNames.add(targetIdx, name)
        rows.add(targetIdx, mutableStateListOf(*Array(headers.size) { "" }))
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

    fun sortRowsByColumn(colIndex: Int, ascending: Boolean) {
        if (colIndex !in headers.indices || rows.isEmpty()) return
        val paired = rows.indices.map { idx -> Triple(rowNames[idx], rows[idx], idx) }
        val sorted = if (ascending) {
            paired.sortedBy { it.second.getOrElse(colIndex) { "" } }
        } else {
            paired.sortedByDescending { it.second.getOrElse(colIndex) { "" } }
        }
        rowNames.clear()
        rows.clear()
        sorted.forEach {
            rowNames.add(it.first)
            rows.add(it.second)
        }
        markUpdated()
    }

    fun copyCells(coords: Collection<Pair<Int, Int>>, isCut: Boolean = false): CellClipboard? {
        if (coords.isEmpty()) return null
        val minR = coords.minOf { it.first }
        val minC = coords.minOf { it.second }
        val items = coords.map { (r, c) ->
            CellOffsetValue(r - minR, c - minC, getCellValue(r, c))
        }
        return CellClipboard(items = items, isCut = isCut, sourceCoords = coords.toList())
    }

    fun pasteCells(
        targetCoords: List<Pair<Int, Int>>,
        anchor: Pair<Int, Int>,
        clipboard: CellClipboard
    ): List<Pair<Int, Int>> {
        if (clipboard.items.isEmpty()) return emptyList()
        if (clipboard.isCut) {
            for ((sr, sc) in clipboard.sourceCoords) setCellValue(sr, sc, "")
        }

        if (clipboard.items.size == 1 && targetCoords.size > 1) {
            val single = clipboard.items.first().value
            for ((r, c) in targetCoords) setCellValue(r, c, single)
            markUpdated()
            return targetCoords
        }

        val (startR, startC) = anchor
        val maxR = startR + (clipboard.items.maxOfOrNull { it.rowOffset } ?: 0)
        val maxC = startC + (clipboard.items.maxOfOrNull { it.colOffset } ?: 0)

        while (headers.size <= maxC) addColumn("Col ${headers.size + 1}")
        while (rows.size <= maxR) addRow()

        val results = mutableListOf<Pair<Int, Int>>()
        for (item in clipboard.items) {
            val dr = startR + item.rowOffset
            val dc = startC + item.colOffset
            setCellValue(dr, dc, item.value)
            results.add(Pair(dr, dc))
        }
        markUpdated()
        return results
    }

    fun shiftCellsBatch(coords: Collection<Pair<Int, Int>>, direction: ShiftDirection): List<Pair<Int, Int>> {
        if (coords.isEmpty()) return emptyList()
        val dr = when (direction) { ShiftDirection.UP -> -1; ShiftDirection.DOWN -> 1; else -> 0 }
        val dc = when (direction) { ShiftDirection.LEFT -> -1; ShiftDirection.RIGHT -> 1; else -> 0 }
        val canMove = coords.all { (r, c) -> (r + dr) in rows.indices && (c + dc) in headers.indices }
        if (!canMove) return coords.toList()

        val snapshot = coords.associateWith { (r, c) -> getCellValue(r, c) }
        for ((r, c) in coords) setCellValue(r, c, "")

        val newCoords = mutableListOf<Pair<Int, Int>>()
        for ((orig, value) in snapshot) {
            val nr = orig.first + dr
            val nc = orig.second + dc
            setCellValue(nr, nc, value)
            newCoords.add(Pair(nr, nc))
        }
        markUpdated()
        return newCoords
    }

    fun importCsv(content: String) {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return

        var dataStart = 0
        if (lines.first().startsWith("# Title:")) {
            tableName = lines.first().substringAfter("# Title:").substringBefore("|").trim()
            dataStart = 1
        }
        val raw = lines.drop(dataStart).map { l ->
            val delim = if (l.contains("\t")) "\t" else ","
            l.split(delim).map { it.trim().removeSurrounding("\"") }
        }
        val rawHeaders = raw.first()
        val dataLines = if (raw.size > 1) raw.drop(1) else emptyList()
        val hasRowTitle = rawHeaders.firstOrNull()?.equals("Row Title", true) == true
        val actualHeaders = if (hasRowTitle) rawHeaders.drop(1) else rawHeaders

        headers.clear()
        headers.addAll(actualHeaders.map { ColumnDef(it) })
        rowNames.clear()
        rows.clear()
        for ((idx, line) in dataLines.withIndex()) {
            rowNames.add(if (hasRowTitle) line.getOrElse(0) { "Row ${idx + 1}" } else "Row ${idx + 1}")
            val cellVals = if (hasRowTitle) line.drop(1) else line
            val padded = cellVals + List((actualHeaders.size - cellVals.size).coerceAtLeast(0)) { "" }
            rows.add(mutableStateListOf(*padded.take(actualHeaders.size).toTypedArray()))
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
            initialCorner = this.cornerHeader,
            initialDateTime = this.tableDateTime
        )
    }

    fun revertToSnapshot(snapshot: TableData) {
        this.tableName = snapshot.tableName
        this.tableDateTime = snapshot.tableDateTime
        this.cornerHeader = snapshot.cornerHeader
        this.headers.clear()
        this.headers.addAll(snapshot.headers.map { it.copy() })
        this.rowNames.clear()
        this.rowNames.addAll(snapshot.rowNames)
        this.rows.clear()
        snapshot.rows.forEach { r -> this.rows.add(mutableStateListOf(*r.toTypedArray())) }
    }
}

object TableRepository {
    val tables = mutableStateListOf<TableData>()

    fun saveOrUpdate(table: TableData) {
        val idx = tables.indexOfFirst { it.id == table.id }
        if (idx >= 0) tables[idx] = table else tables.add(table)
    }

    fun deleteTable(id: String) {
        tables.removeAll { it.id == id }
    }
}
