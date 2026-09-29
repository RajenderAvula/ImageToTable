package com.example.imagetotable.model

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.*

enum class ColumnType(val label: String) {
    TEXT("Text"),
    NUMBER("Number"),
    DECIMAL("Decimal"),
    DATE("Date"),
    FORMULA("Formula")
}

data class ColumnDef(
    var name: String,
    var type: ColumnType = ColumnType.TEXT,
    var formula: String = ""
)

enum class ShiftDirection {
    LEFT, RIGHT, UP, DOWN
}

enum class TokenPlacementMode {
    SEQUENCE_FROM_ACTIVE,
    FILL_MULTI_SELECTION,
    APPEND_NEW_ROW,
    APPEND_NEW_COL,
    REPLACE_CURRENT_TABLE
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

// Dedicated class-level recursive-descent parser resolving Kotlin local function mutual recursion
private class ExpressionParser(private val str: String) {
    private var pos = -1
    private var ch = 0

    private fun nextChar() {
        ch = if (++pos < str.length) str[pos].code else -1
    }

    private fun eat(charToEat: Int): Boolean {
        while (ch == ' '.code) nextChar()
        if (ch == charToEat) {
            nextChar()
            return true
        }
        return false
    }

    fun parse(): Double {
        nextChar()
        val x = parseExpression()
        return x
    }

    private fun parseExpression(): Double {
        var x = parseTerm()
        while (true) {
            when {
                eat('+'.code) -> x += parseTerm()
                eat('-'.code) -> x -= parseTerm()
                else -> return x
            }
        }
    }

    private fun parseTerm(): Double {
        var x = parseFactor()
        while (true) {
            when {
                eat('*'.code) || eat('×'.code) -> x *= parseFactor()
                eat('/'.code) || eat('÷'.code) -> {
                    val divisor = parseFactor()
                    if (divisor == 0.0) throw ArithmeticException("Division by zero")
                    x /= divisor
                }
                eat('%'.code) -> x %= parseFactor()
                else -> return x
            }
        }
    }

    private fun parseFactor(): Double {
        if (eat('+'.code)) return +parseFactor()
        if (eat('-'.code)) return -parseFactor()

        var x: Double
        val startPos = pos
        if (eat('('.code)) {
            x = parseExpression()
            eat(')'.code)
        } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
            while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
            x = str.substring(startPos, pos).toDoubleOrNull() ?: 0.0
        } else if (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code) {
            while (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code) nextChar()
            val func = str.substring(startPos, pos).uppercase(Locale.US)
            if (eat('('.code)) {
                val args = mutableListOf<Double>()
                if (!eat(')'.code)) {
                    do {
                        args.add(parseExpression())
                    } while (eat(','.code))
                    eat(')'.code)
                }
                x = when (func) {
                    "SUM" -> args.sum()
                    "AVG", "AVERAGE" -> if (args.isNotEmpty()) args.average() else 0.0
                    "MIN" -> args.minOrNull() ?: 0.0
                    "MAX" -> args.maxOrNull() ?: 0.0
                    "ROUND" -> if (args.isNotEmpty()) args[0].roundToLong().toDouble() else 0.0
                    "ABS" -> if (args.isNotEmpty()) abs(args[0]) else 0.0
                    "SQRT" -> if (args.isNotEmpty()) sqrt(args[0]) else 0.0
                    else -> 0.0
                }
            } else {
                x = 0.0
            }
        } else {
            x = 0.0
            if (ch != -1) nextChar()
        }

        if (eat('^'.code)) x = x.pow(parseFactor())
        return x
    }
}

// Zero-dependency Mathematical and Statistical Expression Parser
object FormulaEvaluator {
    fun evaluate(
        formula: String,
        headers: List<ColumnDef>,
        rowValues: List<String>,
        targetColIdx: Int = -1
    ): String {
        if (formula.isBlank()) return ""
        var expr = formula.trim()
        if (expr.startsWith("=")) expr = expr.substring(1).trim()
        if (expr.isBlank()) return ""

        // Replace bracketed column references [Column Name] or [Col 1] with current row numeric values
        headers.forEachIndexed { idx, col ->
            if (idx != targetColIdx) {
                val raw = rowValues.getOrElse(idx) { "" }.trim()
                val num = raw.replace(",", "").toDoubleOrNull() ?: 0.0
                val formattedNum = if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()

                expr = expr.replace("[${col.name}]", formattedNum, ignoreCase = true)
                expr = expr.replace("{${col.name}}", formattedNum, ignoreCase = true)
                expr = expr.replace("[Col ${idx + 1}]", formattedNum, ignoreCase = true)
                expr = expr.replace("[Col${idx + 1}]", formattedNum, ignoreCase = true)
            }
        }

        return try {
            val result = ExpressionParser(expr).parse()
            if (result.isNaN() || result.isInfinite()) {
                "#DIV/0!"
            } else if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                String.format(Locale.US, "%.2f", result).trimEnd('0').trimEnd('.')
            }
        } catch (_: Exception) {
            "#ERR"
        }
    }
}

class TableData(
    initialId: String = UUID.randomUUID().toString(),
    initialName: String = "New Table",
    initialDateTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
    initialCorner: String = "Item #",
    initialHeaders: List<ColumnDef> = listOf(ColumnDef("Col 1"), ColumnDef("Col 2")),
    initialRows: List<List<String>> = emptyList(),
    initialRowNames: List<String> = emptyList()
) {
    val id: String = initialId
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

    // Recomputes all formula columns across all rows in real-time
    fun recomputeFormulas() {
        headers.forEachIndexed { colIdx, colDef ->
            if (colDef.type == ColumnType.FORMULA && colDef.formula.isNotBlank()) {
                for (rIdx in rows.indices) {
                    while (rows[rIdx].size <= colIdx) {
                        rows[rIdx].add("")
                    }
                    val evaluated = FormulaEvaluator.evaluate(
                        formula = colDef.formula,
                        headers = headers,
                        rowValues = rows[rIdx],
                        targetColIdx = colIdx
                    )
                    rows[rIdx][colIdx] = evaluated
                }
            }
        }
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
            recomputeFormulas()
            markUpdated()
        }
    }

    fun addRow(name: String = "Row ${rows.size + 1}", index: Int = rows.size) {
        val safeIndex = index.coerceIn(0, rows.size)
        val newRow = mutableStateListOf(*Array(headers.size) { "" })
        rows.add(safeIndex, newRow)
        rowNames.add(safeIndex, name)
        recomputeFormulas()
        markUpdated()
    }

    fun addColumn(
        name: String = "Col ${headers.size + 1}",
        type: ColumnType = ColumnType.TEXT,
        formula: String = ""
    ) {
        headers.add(ColumnDef(name, type, formula))
        for (row in rows) {
            row.add("")
        }
        recomputeFormulas()
        markUpdated()
    }

    fun deleteRow(index: Int) {
        if (index in rows.indices) {
            rows.removeAt(index)
            if (index in rowNames.indices) rowNames.removeAt(index)
            recomputeFormulas()
            markUpdated()
        }
    }

    fun deleteColumn(index: Int) {
        if (index in headers.indices && headers.size > 1) {
            headers.removeAt(index)
            for (row in rows) {
                if (index in row.indices) row.removeAt(index)
            }
            recomputeFormulas()
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
        recomputeFormulas()
        markUpdated()
    }

    fun clearAllValues() {
        for (r in rows) {
            for (c in r.indices) {
                r[c] = ""
            }
        }
        recomputeFormulas()
        markUpdated()
    }

    fun clearCells(cells: Collection<Pair<Int, Int>>) {
        for ((r, c) in cells) {
            if (r in rows.indices && c in headers.indices) {
                rows[r][c] = ""
            }
        }
        recomputeFormulas()
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
        if (isCut) {
            recomputeFormulas()
            markUpdated()
        }
        return CellClipboard(items, isCut)
    }

    fun pasteCells(
        selectedCells: Collection<Pair<Int, Int>>,
        anchor: Pair<Int, Int>,
        clipboard: CellClipboard
    ): List<Pair<Int, Int>> {
        if (clipboard.items.isEmpty()) return emptyList()
        val pasted = mutableListOf<Pair<Int, Int>>()

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
        recomputeFormulas()
        markUpdated()
        return pasted
    }

    fun shiftCellsBatch(
        cells: Collection<Pair<Int, Int>>,
        direction: ShiftDirection
    ): List<Pair<Int, Int>> {
        val validCells = cells.filter { (r, c) -> r in rows.indices && c in headers.indices }
        if (validCells.isEmpty()) return cells.toList()

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
        recomputeFormulas()
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
        recomputeFormulas()
        markUpdated()
    }

    fun replaceTableWithStructuredTokens(
        tokens: List<String>,
        columnCount: Int,
        firstRowAsHeader: Boolean,
        excludedRowsCount: Int
    ) {
        val colCount = columnCount.coerceAtLeast(1)
        var chunked = tokens.chunked(colCount)
        if (excludedRowsCount > 0) {
            chunked = chunked.drop(excludedRowsCount.coerceAtMost(chunked.size))
        }

        headers.clear()
        rows.clear()
        rowNames.clear()

        if (firstRowAsHeader && chunked.isNotEmpty()) {
            val headerNames = chunked.first()
            for (c in 0 until colCount) {
                headers.add(ColumnDef(headerNames.getOrElse(c) { "Col ${c + 1}" }))
            }
            chunked = chunked.drop(1)
        } else {
            for (c in 1..colCount) {
                headers.add(ColumnDef("Col $c"))
            }
        }

        chunked.forEachIndexed { idx, rowVals ->
            rowNames.add("Row ${idx + 1}")
            val padded = rowVals + List((colCount - rowVals.size).coerceAtLeast(0)) { "" }
            rows.add(mutableStateListOf(*padded.toTypedArray()))
        }
        recomputeFormulas()
        markUpdated()
    }

    fun appendStructuredTokens(
        tokens: List<String>,
        columnCount: Int,
        excludedRowsCount: Int
    ) {
        val colCount = columnCount.coerceAtLeast(1)
        while (headers.size < colCount) {
            headers.add(ColumnDef("Col ${headers.size + 1}"))
        }

        var chunked = tokens.chunked(colCount)
        if (excludedRowsCount > 0) {
            chunked = chunked.drop(excludedRowsCount.coerceAtMost(chunked.size))
        }

        val startR = rows.size
        chunked.forEachIndexed { idx, rowVals ->
            rowNames.add("Row ${startR + idx + 1}")
            val padded = rowVals + List((headers.size - rowVals.size).coerceAtLeast(0)) { "" }
            rows.add(mutableStateListOf(*padded.toTypedArray()))
        }
        recomputeFormulas()
        markUpdated()
    }

    fun createSubTable(
        newTableName: String,
        selectedRowIndices: List<Int>,
        selectedColIndices: List<Int>
    ): TableData {
        val subHeaders = selectedColIndices.mapNotNull { headers.getOrNull(it)?.copy() }
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

        val subTable = TableData(
            initialName = newTableName,
            initialCorner = cornerHeader,
            initialHeaders = if (subHeaders.isNotEmpty()) subHeaders else listOf(ColumnDef("Col 1")),
            initialRows = subRows,
            initialRowNames = subRowNames
        )
        subTable.recomputeFormulas()
        return subTable
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
        recomputeFormulas()
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
        recomputeFormulas()
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
        recomputeFormulas()
        markUpdated()
    }
}

object TableRepository {
    val tables: SnapshotStateList<TableData> = mutableStateListOf()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadFromDisk()
        if (tables.isEmpty()) {
            val defaultTable = TableData(
                initialName = "Invoice & Attendance",
                initialHeaders = listOf(
                    ColumnDef("Employee / SKU", ColumnType.TEXT),
                    ColumnDef("Department", ColumnType.TEXT),
                    ColumnDef("2026-09-25", ColumnType.DATE),
                    ColumnDef("2026-09-26", ColumnType.DATE)
                ),
                initialRows = listOf(
                    listOf("John Doe", "Engineering", "Present", "Present"),
                    listOf("Jane Smith", "Design", "Present", "Absent"),
                    listOf("Robert Lee", "Marketing", "Absent", "Present"),
                    listOf("Alice Wong", "Engineering", "Present", "Present")
                ),
                initialCorner = "ID / #"
            )
            tables.add(defaultTable)
            persistToDisk()
        }
    }

    fun saveOrUpdate(table: TableData) {
        val existingIndex = tables.indexOfFirst { it.id == table.id }
        if (existingIndex >= 0) {
            tables[existingIndex] = table
        } else {
            tables.add(table)
        }
        persistToDisk()
    }

    fun deleteTable(id: String) {
        tables.removeAll { it.id == id }
        persistToDisk()
    }

    private fun persistToDisk() {
        val context = appContext ?: return
        try {
            val jsonArray = JSONArray()
            for (t in tables) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("tableName", t.tableName)
                    put("tableDateTime", t.tableDateTime)
                    put("cornerHeader", t.cornerHeader)

                    val hArr = JSONArray()
                    for (h in t.headers) {
                        val hObj = JSONObject().apply {
                            put("name", h.name)
                            put("type", h.type.name)
                            put("formula", h.formula)
                        }
                        hArr.put(hObj)
                    }
                    put("headers", hArr)

                    val rnArr = JSONArray()
                    for (rn in t.rowNames) {
                        rnArr.put(rn)
                    }
                    put("rowNames", rnArr)

                    val rArr = JSONArray()
                    for (row in t.rows) {
                        val rowDataArr = JSONArray()
                        for (cell in row) {
                            rowDataArr.put(cell)
                        }
                        rArr.put(rowDataArr)
                    }
                    put("rows", rArr)
                }
                jsonArray.put(obj)
            }
            val file = File(context.filesDir, "saved_tables.json")
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadFromDisk() {
        val context = appContext ?: return
        try {
            val file = File(context.filesDir, "saved_tables.json")
            if (!file.exists()) return
            val content = file.readText()
            if (content.isBlank()) return

            val jsonArray = JSONArray(content)
            tables.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val name = obj.optString("tableName", "New Table")
                val dateTime = obj.optString("tableDateTime", "")
                val corner = obj.optString("cornerHeader", "Item #")

                val hArr = obj.getJSONArray("headers")
                val headersList = mutableListOf<ColumnDef>()
                for (hIdx in 0 until hArr.length()) {
                    val hObj = hArr.getJSONObject(hIdx)
                    val hName = hObj.getString("name")
                    val hTypeStr = hObj.optString("type", "TEXT")
                    val hFormula = hObj.optString("formula", "")
                    val hType = try { ColumnType.valueOf(hTypeStr) } catch (_: Exception) { ColumnType.TEXT }
                    headersList.add(ColumnDef(hName, hType, hFormula))
                }

                val rnArr = obj.optJSONArray("rowNames")
                val rowNamesList = mutableListOf<String>()
                if (rnArr != null) {
                    for (rnIdx in 0 until rnArr.length()) {
                        rowNamesList.add(rnArr.getString(rnIdx))
                    }
                }

                val rArr = obj.getJSONArray("rows")
                val rowsList = mutableListOf<List<String>>()
                for (rIdx in 0 until rArr.length()) {
                    val rowDataArr = rArr.getJSONArray(rIdx)
                    val rowCells = mutableListOf<String>()
                    for (cIdx in 0 until rowDataArr.length()) {
                        rowCells.add(rowDataArr.getString(cIdx))
                    }
                    rowsList.add(rowCells)
                }

                val table = TableData(
                    initialId = id,
                    initialName = name,
                    initialDateTime = dateTime,
                    initialCorner = corner,
                    initialHeaders = headersList,
                    initialRows = rowsList,
                    initialRowNames = rowNamesList
                )
                table.recomputeFormulas()
                tables.add(table)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
