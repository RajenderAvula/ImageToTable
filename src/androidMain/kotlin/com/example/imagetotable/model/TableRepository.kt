package com.example.imagetotable.model

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TableRepository {
    private const val FILE_NAME = "saved_tables.json"
    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val tables = mutableStateListOf<TableData>()

    /**
     * Initializes the repository, loading previously saved tables from disk.
     */
    fun init(context: Context) {
        if (appContext != null && tables.isNotEmpty()) return
        appContext = context.applicationContext

        val loaded = loadFromDisk()
        tables.clear()
        if (loaded.isNotEmpty()) {
            tables.addAll(loaded)
        } else {
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
        val idx = tables.indexOfFirst { it.id == table.id }
        if (idx >= 0) {
            tables[idx] = table
        } else {
            tables.add(table)
        }
        persistToDisk()
    }

    fun deleteTable(id: String) {
        tables.removeAll { it.id == id }
        persistToDisk()
    }

    fun getTable(id: String): TableData? {
        return tables.find { it.id == id }
    }

    private fun persistToDisk() {
        val context = appContext ?: return
        val tablesSnapshot = tables.toList()
        scope.launch {
            try {
                val jsonArray = JSONArray()
                for (t in tablesSnapshot) {
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

                val file = File(context.filesDir, FILE_NAME)
                file.writeText(jsonArray.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadFromDisk(): List<TableData> {
        val context = appContext ?: return emptyList()
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()

        return try {
            val content = file.readText()
            if (content.isBlank()) return emptyList()

            val jsonArray = JSONArray(content)
            val list = mutableListOf<TableData>()
            val totalTables: Int = jsonArray.length()

            for (i in 0 until totalTables) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "")
                val name = obj.optString("tableName", "Untitled Table")
                val dateTime = obj.optString("tableDateTime", "")
                val corner = obj.optString("cornerHeader", "ID / #")

                val hArr = obj.getJSONArray("headers")
                val headersList = mutableListOf<ColumnDef>()
                val totalHeaders: Int = hArr.length()
                for (hIdx in 0 until totalHeaders) {
                    val hObj = hArr.getJSONObject(hIdx)
                    val hName = hObj.getString("name")
                    val hTypeStr = hObj.optString("type", "TEXT")
                    val hType = try {
                        ColumnType.valueOf(hTypeStr)
                    } catch (_: Exception) {
                        ColumnType.TEXT
                    }
                    headersList.add(ColumnDef(hName, hType))
                }

                val rnArr = obj.optJSONArray("rowNames")
                val rowNamesList = mutableListOf<String>()
                if (rnArr != null) {
                    val totalRowNames: Int = rnArr.length()
                    for (rnIdx in 0 until totalRowNames) {
                        rowNamesList.add(rnArr.getString(rnIdx))
                    }
                }

                val rArr = obj.getJSONArray("rows")
                val rowsList = mutableListOf<List<String>>()
                val totalRows: Int = rArr.length()
                for (rIdx in 0 until totalRows) {
                    val rowDataArr = rArr.getJSONArray(rIdx)
                    val rowCells = mutableListOf<String>()
                    val totalCells: Int = rowDataArr.length()
                    for (cIdx in 0 until totalCells) {
                        rowCells.add(rowDataArr.getString(cIdx))
                    }
                    rowsList.add(rowCells)
                }

                val table = TableData(
                    initialName = name,
                    initialHeaders = headersList,
                    initialRows = rowsList,
                    initialRowNames = rowNamesList,
                    initialCorner = corner,
                    initialDateTime = dateTime
                )
                if (id.isNotBlank()) {
                    table.id = id
                }
                list.add(table)
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
