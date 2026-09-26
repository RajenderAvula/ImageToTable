package com.example.imagetotable.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ColumnType(val label: String) {
    TEXT("Text"),
    NUMBER("Number"),
    DECIMAL("Decimal"),
    DATE("Date")
}

data class ColumnDef(
    var name: String,
    var type: ColumnType = ColumnType.TEXT
)

enum class ShiftDirection {
    LEFT, RIGHT, UP, DOWN
}

enum class TokenPlacementMode {
    SEQUENCE_FROM_ACTIVE,
    FILL_MULTI_SELECTION,
    APPEND_NEW_ROW,
    APPEND_NEW_COL
}

data class ClipboardItem(
    val rowOffset: Int,
    val colOffset: Int,
    val value: String
)

data class CellClipboard(
    val items: List<ClipboardItem>,
    val isCut: Boolean
)

data class TableSnapshot(
    val tableName: String,
    val tableDateTime: String,
    val cornerHeader: String,
    val headers: List<ColumnDef>,
    val rowNames: List<String>,
    val rows: List<List<String>>
)

class TableData(
    val id: String = UUID.randomUUID().toString(),
    initialName: String = "New Table",
    initialDateTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
    initialCorner: String = "Item #",
    initialHeaders: List<ColumnDef> = listOf(ColumnDef("Col 1"), ColumnDef("Col 2")),
    initialRows: List<List<String>> = emptyList(),
    initialRowNames: List<String> = emptyList()
) {
    var tableName: String by mutableStateOf(initialName)
    var tableDateTime: String by mutableStateOf(initialDateTime)
    var cornerHeader: String by mutableStateOf(initialCorner)

    val headers: SnapshotStateList<ColumnDef> = mutableStateListOf(*initialHeaders.toTypedArray())
    val rowNames: SnapshotStateList<String> = mutableStateListOf(
        *if (initialRowNames.isNotEmpty()) {
            initialRowNames.toTypedArray()
        } else {
            Array(initialRows.size) { "Row ${it + 1}" }
        }
    )
    val rows: SnapshotStateList<SnapshotStateList<String>> = mutableStateListOf(
        *initialRows.map { row ->
            mutableStateListOf(*row.toTypedArray())
        }.toTypedArray()
    )

    fun markUpdated() {
        tableDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
    }

    fun getCellValue(rowIndex: Int, colIndex: Int): String {
        return if (rowIndex in rows.indices && colIndex in headers.indices) {
            rows[rowIndex].getOrElse(colIndex) { "" }
        } else {
            ""
        }
    }

    fun setCellValue(rowIndex: Int, colIndex: Int, value: String) {
        if (rowIndex in rows.indices && colIndex in headers.indices) {
            while (rows[rowIndex].size <= colIndex) {
                rows[rowIndex].add("")
            }
            rows[rowIndex][colIndex] = value
            markUpdated()
        }
    }

    fun addRow(name: String = "Row ${rows.size + 1}", index: Int = rows.size) {
        val safeIndex = index.coerceIn(0, rows.size)
        val newRow = mutableStateListOf(*Array(headers.size) { "" })
        rows.add(safeIndex, newRow)
        rowNames.add(safeIndex, name)
        markUpdated()
    }

    fun addColumn(name: String = "Col ${headers.size + 1}", type: ColumnType = ColumnType.TEXT) {
        headers.add(ColumnDef(name, type))
        for (row in rows) {
            row.add("")
        }
        markUpdated()
    }

    fun deleteRow(index: Int) {
        if (index in rows.indices) {
            rows.removeAt(index)
            if (index in rowNames.indices) rowNames.removeAt(index)
            markUpdated()
        }
    }

    fun deleteColumn(index: Int) {
        if (index in headers.indices && headers.size > 1) {
            headers.removeAt(index)
            for (row in rows) {
                if (index in row.indices) row.removeAt(index)
            }
            markUpdated()
        }
    }

    fun moveRow(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in rows.indices || toIndex !in rows.indices || fromIndex == toIndex) return
        val row = rows.removeAt(fromIndex)
        rows.add(toIndex, row)
        val name = rowNames.removeAt(fromIndex)
        rowNames.add(toIndex, name)
        markUpdated()
    }

    fun moveColumn(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in headers.indices || toIndex !in headers.indices || fromIndex == toIndex) return
        val h = headers.removeAt(fromIndex)
        headers.add(toIndex, h)
        for (row in rows) {
            val c = row.removeAt(fromIndex)
            row.add(toIndex, c)
        }
        markUpdated()
    }

    fun clearAllValues() {
        for (r in rows) {
            for (c in r.indices) {
                r[c] = ""
            }
        }
        markUpdated()
    }

    fun clearCells(cells: Collection<Pair<Int, Int>>) {
        for ((r, c) in cells) {
            if (r in rows.indices && c in headers.indices) {
                rows[r][c] = ""
            }
        }
        markUpdated()
    }

    fun copyCells(cells: Collection<Pair<Int, Int>>, isCut: Boolean = false): CellClipboard {
        if (cells.isEmpty()) return CellClipboard(emptyList(), isCut)
        val validCells = cells.filter { (r, c) -> r in rows.indices && c in headers.indices }
        if (validCells.isEmpty()) return CellClipboard(emptyList(), isCut)

        val minR = validCells.minOf { it.first }
        val minC = validCells.minOf { it.second }

        val items = validCells.map { (r, c) ->
            val v = rows[r].getOrElse(c) { "" }
            if (isCut) rows[r][c] = ""
            ClipboardItem(r - minR, c - minC, v)
        }
        if (isCut) markUpdated()
        return CellClipboard(items, isCut)
    }

    fun pasteCells(
        selectedCells: Collection<Pair<Int, Int>>,
        anchor: Pair<Int, Int>,
        clipboard: CellClipboard
    ): List<Pair<Int, Int>> {
        if (clipboard.items.isEmpty()) return emptyList()
        val pasted = mutableListOf<Pair<Int, Int>>()

        // CASE 1: 1 Copied Cell -> Replicate across all selected target cells
        if (clipboard.items.size == 1 && selectedCells.size > 1) {
            val singleValue = clipboard.items.first().value
            for ((r, c) in selectedCells) {
                if (r in rows.indices && c in headers.indices) {
                    while (rows[r].size <= c) rows[r].add("")
                    rows[r][c] = singleValue
                    pasted.add(Pair(r, c))
                }
            }
        } else {
            // CASE 2: Multi-cell block paste or single cell placement
            val (baseR, baseC) = if (selectedCells.isNotEmpty()) {
                val minR = selectedCells.minOf { it.first }.coerceAtLeast(0)
                val minC = selectedCells.minOf { it.second }.coerceAtLeast(0)
                Pair(minR, minC)
            } else if (anchor.first in rows.indices && anchor.second in headers.indices) {
                anchor
            } else {
                Pair(0, 0)
            }

            for (item in clipboard.items) {
                val targetR = baseR + item.rowOffset
                val targetC = baseC + item.colOffset

                while (targetC >= headers.size) addColumn()
                while (targetR >= rows.size) addRow()
                while (rows[targetR].size <= targetC) rows[targetR].add("")

                rows[targetR][targetC] = item.value
                pasted.add(Pair(targetR, targetC))
            }
        }
        markUpdated()
        return pasted
    }

    fun shiftCellsBatch(
        cells: Collection<Pair<Int, Int>>,
        direction: ShiftDirection
    ): List<Pair<Int, Int>> {
        val validCells = cells.filter { (r, c) -> r in rows.indices && c in headers.indices }
        if (validCells.isEmpty()) return cells.toList()

        // Guard against shifting out-of-bounds at the top/left boundary
        if (direction == ShiftDirection.LEFT && validCells.any { it.second == 0 }) return cells.toList()
        if (direction == ShiftDirection.UP && validCells.any { it.first == 0 }) return cells.toList()

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

        val cellMap = validCells.associateWith { (r, c) -> rows[r][c] }
        val updatedCoords = mutableListOf<Pair<Int, Int>>()

        for ((r, c) in validCells) {
            rows[r][c] = ""
        }

        for ((coord, value) in cellMap) {
            val (r, c) = coord
            val newR = r + dr
            val newC = c + dc

            while (newC >= headers.size) addColumn()
            while (newR >= rows.size) addRow()
            while (rows[newR].size <= newC) rows[newR].add("")

            rows[newR][newC] = value
            updatedCoords.add(Pair(newR, newC))
        }
        markUpdated()
        return updatedCoords
    }

    fun transposeTable() {
        if (headers.isEmpty() && rows.isEmpty()) return
        val oldHeaders = headers.map { it.name }
        val oldRows = rows.map { it.toList() }
        val oldRowNames = rowNames.toList()

        headers.clear()
        headers.add(ColumnDef(cornerHeader))
        oldRowNames.forEach { rName ->
            headers.add(ColumnDef(rName))
        }

        rows.clear()
        rowNames.clear()

        for (c in oldHeaders.indices) {
            rowNames.add(oldHeaders[c])
            val newRowData = mutableListOf<String>()
            newRowData.add(oldHeaders[c])
            for (r in oldRows.indices) {
                newRowData.add(oldRows[r].getOrElse(c) { "" })
            }
            rows.add(mutableStateListOf(*newRowData.toTypedArray()))
        }
        markUpdated()
    }

    fun createSubTable(
        newTableName: String,
        selectedRowIndices: List<Int>,
        selectedColIndices: List<Int>
    ): TableData {
        val subHeaders = selectedColIndices.mapNotNull { headers.getOrNull(it) }
        val subRows = mutableListOf<List<String>>()
        val subRowNames = mutableListOf<String>()

        for (r in selectedRowIndices) {
            if (r in rows.indices) {
                subRowNames.add(rowNames.getOrElse(r) { "Row ${r + 1}" })
                val rowCells = selectedColIndices.map { c ->
                    rows[r].getOrElse(c) { "" }
                }
                subRows.add(rowCells)
            }
        }

        return TableData(
            initialName = newTableName,
            initialCorner = cornerHeader,
            initialHeaders = if (subHeaders.isNotEmpty()) subHeaders else listOf(ColumnDef("Col 1")),
            initialRows = subRows,
            initialRowNames = subRowNames
        )
    }

    fun loadExtractedData(newHeaders: List<String>, newRows: List<List<String>>) {
        headers.clear()
        headers.addAll(newHeaders.map { ColumnDef(it) })
        rows.clear()
        rowNames.clear()

        for ((idx, rowData) in newRows.withIndex()) {
            rowNames.add("Row ${idx + 1}")
            val safeRow = rowData + List((newHeaders.size - rowData.size).coerceAtLeast(0)) { "" }
            rows.add(mutableStateListOf(*safeRow.toTypedArray()))
        }
        markUpdated()
    }

    fun appendExtractedData(newHeaders: List<String>, newRows: List<List<String>>) {
        while (headers.size < newHeaders.size) {
            val idx = headers.size
            headers.add(ColumnDef(newHeaders.getOrElse(idx) { "Col ${idx + 1}" }))
        }
        val startR = rows.size
        for ((idx, rowData) in newRows.withIndex()) {
            rowNames.add("Row ${startR + idx + 1}")
            val padded = rowData + List((headers.size - rowData.size).coerceAtLeast(0)) { "" }
            rows.add(mutableStateListOf(*padded.toTypedArray()))
        }
        markUpdated()
    }

    fun importCsv(csvText: String) {
        val lines = csvText.trim().split("\n").map { line ->
            line.split(",").map { cell ->
                cell.trim().trim('"', '\'')
            }
        }.filter { it.isNotEmpty() }

        if (lines.isNotEmpty()) {
            val h = lines.first()
            val r = if (lines.size > 1) lines.drop(1) else emptyList()
            loadExtractedData(h, r)
        }
    }

    fun selectionToTsv(cells: Collection<Pair<Int, Int>>): String {
        val valid = cells.filter { (r, c) -> r in rows.indices && c in headers.indices }
        if (valid.isEmpty()) return ""
        val minR = valid.minOf { it.first }
        val maxR = valid.maxOf { it.first }
        val minC = valid.minOf { it.second }
        val maxC = valid.maxOf { it.second }

        val cellSet = valid.toSet()
        val sb = StringBuilder()
        for (r in minR..maxR) {
            val rowVals = mutableListOf<String>()
            for (c in minC..maxC) {
                if (cellSet.contains(Pair(r, c))) {
                    rowVals.add(rows[r].getOrElse(c) { "" })
                } else {
                    rowVals.add("")
                }
            }
            sb.append(rowVals.joinToString("\t"))
            if (r < maxR) sb.append("\n")
        }
        return sb.toString()
    }

    fun toTsvString(): String {
        val sb = StringBuilder()
        sb.append(cornerHeader).append("\t").append(headers.joinToString("\t") { it.name }).append("\n")
        for (r in rows.indices) {
            sb.append(rowNames.getOrElse(r) { "Row ${r + 1}" }).append("\t")
            sb.append(rows[r].joinToString("\t")).append("\n")
        }
        return sb.toString().trimEnd()
    }

    fun createSnapshot(): TableSnapshot {
        return TableSnapshot(
            tableName = tableName,
            tableDateTime = tableDateTime,
            cornerHeader = cornerHeader,
            headers = headers.map { it.copy() },
            rowNames = rowNames.toList(),
            rows = rows.map { it.toList() }
        )
    }

    fun revertToSnapshot(snapshot: TableSnapshot) {
        tableName = snapshot.tableName
        tableDateTime = snapshot.tableDateTime
        cornerHeader = snapshot.cornerHeader
        headers.clear()
        headers.addAll(snapshot.headers.map { it.copy() })
        rowNames.clear()
        rowNames.addAll(snapshot.rowNames)
        rows.clear()
        for (r in snapshot.rows) {
            rows.add(mutableStateListOf(*r.toTypedArray()))
        }
        markUpdated()
    }
}

/*object TableRepository {
    val tables: SnapshotStateList<TableData> = mutableStateListOf()

    fun saveOrUpdate(table: TableData) {
        val existingIndex = tables.indexOfFirst { it.id == table.id }
        if (existingIndex >= 0) {
            tables[existingIndex] = table
        } else {
            tables.add(table)
        }
    }

    fun deleteTable(id: String) {
        tables.removeAll { it.id == id }
    }
}*/
