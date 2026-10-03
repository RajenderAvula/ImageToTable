package com.example.imagetotable.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PageInfo(
    val number: Int,
    val widthPt: Float,
    val heightPt: Float,
    val rotation: Int
) {
    /** Size as displayed (rotation applied). */
    val displayWidth: Float get() = if (rotation == 90 || rotation == 270) heightPt else widthPt
    val displayHeight: Float get() = if (rotation == 90 || rotation == 270) widthPt else heightPt
}

data class PdfMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String? = null,
    val producer: String? = null
)

enum class PageNumberPosition {
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
    TOP_LEFT, TOP_CENTER, TOP_RIGHT
}

object PageRanges {
    /**
     * Parses a page-range spec into zero-based indices, preserving the order given
     * and removing duplicates. A null or blank spec means all pages.
     */
    fun parse(spec: String?, pageCount: Int): List<Int> {
        require(pageCount > 0) { "Document has no pages" }
        if (spec.isNullOrBlank()) return (0 until pageCount).toList()
        val result = LinkedHashSet<Int>()

        fun number(raw: String): Int {
            val t = raw.trim().lowercase()
            val n = if (t == "last") pageCount else t.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid page number: '$raw'")
            require(n in 1..pageCount) { "Page $n is out of range (1..$pageCount)" }
            return n
        }

        for (token in spec.split(',')) {
            val t = token.trim().lowercase()
            if (t.isEmpty()) continue
            when {
                t == "all" -> (0 until pageCount).forEach { result += it }
                t == "odd" -> (0 until pageCount step 2).forEach { result += it }
                t == "even" -> (1 until pageCount step 2).forEach { result += it }
                '-' in t -> {
                    val parts = t.split('-', limit = 2)
                    val a = parts[0]
                    val b = parts[1]
                    val start = if (a.isBlank()) 1 else number(a)
                    val end = if (b.isBlank()) pageCount else number(b)
                    require(start <= end) { "Invalid range '$token' (start is after end)" }
                    for (p in start..end) result += p - 1
                }
                else -> result += number(t) - 1
            }
        }
        require(result.isNotEmpty()) { "Page range '$spec' selects no pages" }
        return result.toList()
    }
}

/**
 * Android-native implementation of PdfEditor providing page-level PDF operations,
 * rich overlays, page-range parsing, undo/redo snapshots, and atomic saves.
 */
class PdfEditor private constructor(
    private val context: Context,
    private val undoDepth: Int = 10
) : AutoCloseable {

    companion object {
        fun load(context: Context, file: File, undoDepth: Int = 10): PdfEditor {
            require(file.isFile) { "File not found: ${file.path}" }
            val editor = PdfEditor(context, undoDepth)
            editor.loadSourceFile(file)
            return editor
        }

        fun create(context: Context, undoDepth: Int = 10): PdfEditor {
            return PdfEditor(context, undoDepth)
        }

        fun merge(context: Context, inputs: List<File>, output: File) {
            require(inputs.size >= 2) { "Need at least two files to merge" }
            inputs.forEach { require(it.isFile) { "File not found: ${it.path}" } }

            val doc = PdfDocument()
            var globalPageIdx = 1

            inputs.forEach { file ->
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val w = page.width
                    val h = page.height
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    val pageInfo = PdfDocument.PageInfo.Builder(w, h, globalPageIdx++).create()
                    val pdfPage = doc.startPage(pageInfo)
                    pdfPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                    doc.finishPage(pdfPage)
                    bmp.recycle()
                }
                renderer.close()
                pfd.close()
            }

            saveAtomically(doc, output)
            doc.close()
        }

        internal fun saveAtomically(document: PdfDocument, output: File) {
            output.absoluteFile.parentFile?.mkdirs()
            val tmp = File.createTempFile(".pdfeditor-", ".tmp", output.parentFile)
            try {
                FileOutputStream(tmp).use { fos -> document.writeTo(fos) }
                if (output.exists()) output.delete()
                if (!tmp.renameTo(output)) {
                    tmp.inputStream().use { input ->
                        FileOutputStream(output).use { out ->
                            input.copyTo(out)
                        }
                    }
                    tmp.delete()
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    data class PageModel(
        val id: String = UUID.randomUUID().toString(),
        var baseBitmap: Bitmap,
        var widthPt: Float,
        var heightPt: Float,
        var rotation: Int = 0,
        var cropLeft: Float = 0f,
        var cropTop: Float = 0f,
        var cropRight: Float = 0f,
        var cropBottom: Float = 0f,
        val overlayDrawers: MutableList<(Canvas, Float, Float) -> Unit> = mutableListOf()
    ) {
        fun copySnapshot(): PageModel {
            val copyBmp = baseBitmap.copy(baseBitmap.config ?: Bitmap.Config.ARGB_8888, false)
            return PageModel(
                id = id,
                baseBitmap = copyBmp,
                widthPt = widthPt,
                heightPt = heightPt,
                rotation = rotation,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropRight = cropRight,
                cropBottom = cropBottom,
                overlayDrawers = overlayDrawers.toMutableList()
            )
        }
    }

    private val pageList = mutableListOf<PageModel>()
    private val undoStack = ArrayDeque<List<PageModel>>()
    private val redoStack = ArrayDeque<List<PageModel>>()
    private var docMetadata = PdfMetadata()

    val pageCount: Int get() = pageList.size
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun loadSourceFile(file: File) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        pageList.clear()

        for (i in 0 until renderer.pageCount) {
            val page = renderer.openPage(i)
            val w = page.width
            val h = page.height
            val scale = 2.0f
            val targetW = (w * scale).toInt().coerceAtMost(2048)
            val targetH = (h * scale).toInt().coerceAtMost(2048)
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            pageList.add(
                PageModel(
                    baseBitmap = bmp,
                    widthPt = w.toFloat(),
                    heightPt = h.toFloat(),
                    rotation = 0
                )
            )
        }
        renderer.close()
        pfd.close()
    }

    fun pageInfo(page: Int): PageInfo {
        checkPage(page)
        val p = pageList[page - 1]
        return PageInfo(page, p.widthPt, p.heightPt, normalizeRotation(p.rotation))
    }

    fun pages(): List<PageInfo> = (1..pageCount).map { pageInfo(it) }

    fun metadata(): PdfMetadata = docMetadata

    fun setMetadata(title: String? = null, author: String? = null, subject: String? = null, keywords: String? = null) {
        mutate {
            docMetadata = docMetadata.copy(
                title = title ?: docMetadata.title,
                author = author ?: docMetadata.author,
                subject = subject ?: docMetadata.subject,
                keywords = keywords ?: docMetadata.keywords
            )
        }
    }

    fun renderPage(page: Int, dpi: Float = 150f): Bitmap {
        checkPage(page)
        val p = pageList[page - 1]
        val dispW = (p.widthPt * (dpi / 72f)).toInt().coerceAtLeast(1)
        val dispH = (p.heightPt * (dpi / 72f)).toInt().coerceAtLeast(1)

        val outputBmp = Bitmap.createBitmap(dispW, dispH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBmp)
        canvas.drawColor(android.graphics.Color.WHITE)

        val matrix = Matrix()
        matrix.postRotate(p.rotation.toFloat(), dispW / 2f, dispH / 2f)
        matrix.postScale(dispW.toFloat() / p.baseBitmap.width, dispH.toFloat() / p.baseBitmap.height)
        canvas.drawBitmap(p.baseBitmap, matrix, null)

        p.overlayDrawers.forEach { drawer ->
            drawer(canvas, dispW.toFloat(), dispH.toFloat())
        }

        return outputBmp
    }

    fun renderPages(
        dir: File,
        pages: String? = null,
        dpi: Float = 150f,
        format: String = "png",
        prefix: String = "page"
    ): List<File> {
        dir.mkdirs()
        val width = pageCount.toString().length
        return PageRanges.parse(pages, pageCount).map { idx ->
            val file = File(dir, "${prefix}_${(idx + 1).toString().padStart(width, '0')}.$format")
            val bmp = renderPage(idx + 1, dpi)
            FileOutputStream(file).use { out ->
                val compFormat = if (format.equals("jpg", true) || format.equals("jpeg", true)) {
                    Bitmap.CompressFormat.JPEG
                } else {
                    Bitmap.CompressFormat.PNG
                }
                bmp.compress(compFormat, 100, out)
            }
            bmp.recycle()
            file
        }
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.addLast(snapshot())
        restore(undoStack.removeLast())
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.addLast(snapshot())
        restore(redoStack.removeLast())
        return true
    }

    fun rotate(pages: String?, degrees: Int) {
        require(degrees % 90 == 0) { "degrees must be a multiple of 90" }
        val idx = PageRanges.parse(pages, pageCount)
        mutate {
            idx.forEach { i ->
                pageList[i].rotation = normalizeRotation(pageList[i].rotation + degrees)
            }
        }
    }

    fun deletePages(pages: String) {
        val idx = PageRanges.parse(pages, pageCount)
        require(idx.size < pageCount) { "Cannot delete every page" }
        mutate {
            idx.sortedDescending().forEach { i ->
                pageList.removeAt(i)
            }
        }
    }

    fun keepPages(pages: String) {
        val keep = PageRanges.parse(pages, pageCount)
        mutate {
            val retained = keep.map { pageList[it] }
            pageList.clear()
            pageList.addAll(retained)
        }
    }

    fun extractPages(pages: String, output: File) {
        val idx = PageRanges.parse(pages, pageCount)
        val doc = PdfDocument()
        idx.forEachIndexed { outIdx, pageIdx ->
            val p = pageList[pageIdx]
            val pageInfo = PdfDocument.PageInfo.Builder(p.widthPt.toInt(), p.heightPt.toInt(), outIdx + 1).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawBitmap(p.baseBitmap, 0f, 0f, null)
            p.overlayDrawers.forEach { it(page.canvas, p.widthPt, p.heightPt) }
            doc.finishPage(page)
        }
        saveAtomically(doc, output)
        doc.close()
    }

    fun reorder(newOrder: List<Int>) {
        require(newOrder.size == pageCount && newOrder.toSet() == (1..pageCount).toSet()) {
            "newOrder must contain each page number 1..$pageCount exactly once"
        }
        mutate {
            val all = pageList.toList()
            pageList.clear()
            newOrder.forEach { pageList.add(all[it - 1]) }
        }
    }

    fun movePage(from: Int, to: Int) {
        checkPage(from); checkPage(to)
        if (from == to) return
        mutate {
            val item = pageList.removeAt(from - 1)
            pageList.add(to - 1, item)
        }
    }

    fun duplicatePage(page: Int, copies: Int = 1) {
        checkPage(page)
        require(copies in 1..1000) { "copies must be between 1 and 1000" }
        mutate {
            val target = pageList[page - 1]
            repeat(copies) { n ->
                pageList.add(page + n, target.copySnapshot())
            }
        }
    }

    fun insertBlankPage(
        at: Int = pageCount + 1,
        widthPt: Float = 595f,
        heightPt: Float = 842f,
        landscape: Boolean = false
    ) {
        require(at in 1..pageCount + 1) { "at must be between 1 and ${pageCount + 1}" }
        val finalW = if (landscape) heightPt else widthPt
        val finalH = if (landscape) widthPt else heightPt

        val blankBmp = Bitmap.createBitmap(finalW.toInt(), finalH.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(blankBmp)
        canvas.drawColor(android.graphics.Color.WHITE)

        val model = PageModel(baseBitmap = blankBmp, widthPt = finalW, heightPt = finalH)
        mutate {
            if (at == pageCount + 1) pageList.add(model) else pageList.add(at - 1, model)
        }
    }

    fun insertPdf(file: File, at: Int = pageCount + 1, pages: String? = null) {
        require(file.isFile) { "File not found: ${file.path}" }
        require(at in 1..pageCount + 1) { "at must be between 1 and ${pageCount + 1}" }

        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val selectedIdx = PageRanges.parse(pages, renderer.pageCount)

        val imported = mutableListOf<PageModel>()
        selectedIdx.forEach { i ->
            val page = renderer.openPage(i)
            val w = page.width
            val h = page.height
            val bmp = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            imported.add(PageModel(baseBitmap = bmp, widthPt = w.toFloat(), heightPt = h.toFloat()))
        }
        renderer.close()
        pfd.close()

        mutate {
            pageList.addAll((at - 1).coerceAtMost(pageList.size), imported)
        }
    }

    fun crop(pages: String?, left: Float = 0f, bottom: Float = 0f, right: Float = 0f, top: Float = 0f) {
        require(left >= 0 && bottom >= 0 && right >= 0 && top >= 0) { "Margins must be non-negative" }
        val idx = PageRanges.parse(pages, pageCount)
        mutate {
            idx.forEach { i ->
                val p = pageList[i]
                p.cropLeft += left
                p.cropTop += top
                p.cropRight += right
                p.cropBottom += bottom
                p.widthPt = (p.widthPt - left - right).coerceAtLeast(20f)
                p.heightPt = (p.heightPt - top - bottom).coerceAtLeast(20f)
            }
        }
    }

    fun split(dir: File, pagesPerFile: Int = 1, prefix: String = "part"): List<File> {
        require(pagesPerFile >= 1) { "pagesPerFile must be >= 1" }
        dir.mkdirs()
        val files = mutableListOf<File>()
        val total = pageCount

        var chunkIdx = 1
        var start = 0
        while (start < total) {
            val end = (start + pagesPerFile).coerceAtMost(total)
            val rangeSpec = "${start + 1}-$end"
            val targetFile = File(dir, "${prefix}_${chunkIdx.toString().padStart(3, '0')}.pdf")
            extractPages(rangeSpec, targetFile)
            files.add(targetFile)
            start = end
            chunkIdx++
        }
        return files
    }

    fun splitByRanges(dir: File, ranges: List<String>, prefix: String = "part"): List<File> {
        require(ranges.isNotEmpty()) { "No ranges given" }
        dir.mkdirs()
        return ranges.mapIndexed { i, spec ->
            File(dir, "${prefix}_${(i + 1).toString().padStart(3, '0')}.pdf").also {
                extractPages(spec, it)
            }
        }
    }

    fun addText(
        page: Int,
        text: String,
        x: Float,
        y: Float,
        fontSize: Float = 14f,
        color: Int = android.graphics.Color.BLACK,
        isBold: Boolean = false,
        isItalic: Boolean = false
    ) {
        checkPage(page)
        mutate {
            pageList[page - 1].overlayDrawers.add { canvas, _, _ ->
                val paint = Paint().apply {
                    this.color = color
                    this.textSize = fontSize
                    this.isAntiAlias = true
                    val style = when {
                        isBold && isItalic -> Typeface.BOLD_ITALIC
                        isBold -> Typeface.BOLD
                        isItalic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                    this.typeface = Typeface.create(Typeface.DEFAULT, style)
                }
                canvas.drawText(text, x, y, paint)
            }
        }
    }

    fun addImage(page: Int, image: File, x: Float, y: Float, width: Float, height: Float? = null) {
        checkPage(page)
        require(image.isFile) { "Image not found: ${image.path}" }
        val bmp = BitmapFactory.decodeFile(image.path) ?: return
        val finalH = height ?: (width * bmp.height.toFloat() / bmp.width.toFloat())

        mutate {
            pageList[page - 1].overlayDrawers.add { canvas, _, _ ->
                val rect = RectF(x, y, x + width, y + finalH)
                canvas.drawBitmap(bmp, null, rect, null)
            }
        }
    }

    fun addRectangle(
        page: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        fill: Int? = null,
        stroke: Int? = android.graphics.Color.BLACK,
        lineWidth: Float = 1f
    ) {
        checkPage(page)
        mutate {
            pageList[page - 1].overlayDrawers.add { canvas, _, _ ->
                val rect = RectF(x, y, x + width, y + height)
                fill?.let { fColor ->
                    val fPaint = Paint().apply {
                        color = fColor
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(rect, fPaint)
                }
                stroke?.let { sColor ->
                    val sPaint = Paint().apply {
                        color = sColor
                        style = Paint.Style.STROKE
                        strokeWidth = lineWidth
                    }
                    canvas.drawRect(rect, sPaint)
                }
            }
        }
    }

    fun highlight(
        page: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int = android.graphics.Color.YELLOW
    ) {
        checkPage(page)
        mutate {
            pageList[page - 1].overlayDrawers.add { canvas, _, _ ->
                val paint = Paint().apply {
                    this.color = color
                    this.alpha = 128
                    this.style = Paint.Style.FILL
                }
                canvas.drawRect(x, y, x + width, y + height, paint)
            }
        }
    }

    fun addTextWatermark(
        text: String,
        pages: String? = null,
        fontSize: Float = 60f,
        color: Int = android.graphics.Color.GRAY,
        angleDegrees: Double = 45.0
    ) {
        val idx = PageRanges.parse(pages, pageCount)
        mutate {
            idx.forEach { i ->
                pageList[i].overlayDrawers.add { canvas, w, h ->
                    val paint = Paint().apply {
                        this.color = color
                        this.alpha = 70
                        this.textSize = fontSize
                        this.isAntiAlias = true
                        this.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    val textW = paint.measureText(text)
                    canvas.save()
                    canvas.rotate(angleDegrees.toFloat(), w / 2f, h / 2f)
                    canvas.drawText(text, (w - textW) / 2f, h / 2f, paint)
                    canvas.restore()
                }
            }
        }
    }

    fun addPageNumbers(
        format: String = "Page {n} of {total}",
        pages: String? = null,
        position: PageNumberPosition = PageNumberPosition.BOTTOM_CENTER,
        startAt: Int = 1,
        fontSize: Float = 11f,
        margin: Float = 24f,
        color: Int = android.graphics.Color.DKGRAY
    ) {
        val idx = PageRanges.parse(pages, pageCount)
        val total = pageCount
        mutate {
            idx.forEach { pageIdx ->
                pageList[pageIdx].overlayDrawers.add { canvas, w, h ->
                    val label = format.replace("{n}", (startAt + pageIdx).toString()).replace("{total}", total.toString())
                    val paint = Paint().apply {
                        this.color = color
                        this.textSize = fontSize
                        this.isAntiAlias = true
                    }
                    val tw = paint.measureText(label)

                    val x = when (position) {
                        PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.TOP_LEFT -> margin
                        PageNumberPosition.BOTTOM_CENTER, PageNumberPosition.TOP_CENTER -> (w - tw) / 2f
                        PageNumberPosition.BOTTOM_RIGHT, PageNumberPosition.TOP_RIGHT -> w - margin - tw
                    }
                    val y = when (position) {
                        PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.BOTTOM_CENTER, PageNumberPosition.BOTTOM_RIGHT -> h - margin
                        else -> margin + fontSize
                    }
                    canvas.drawText(label, x, y, paint)
                }
            }
        }
    }

    fun save(output: File) {
        val doc = PdfDocument()
        pageList.forEachIndexed { i, p ->
            val pageInfo = PdfDocument.PageInfo.Builder(p.widthPt.toInt(), p.heightPt.toInt(), i + 1).create()
            val pdfPage = doc.startPage(pageInfo)
            val canvas = pdfPage.canvas

            val matrix = Matrix()
            matrix.postRotate(p.rotation.toFloat(), p.widthPt / 2f, p.heightPt / 2f)
            matrix.postScale(p.widthPt / p.baseBitmap.width, p.heightPt / p.baseBitmap.height)
            canvas.drawBitmap(p.baseBitmap, matrix, null)

            p.overlayDrawers.forEach { drawer ->
                drawer(canvas, p.widthPt, p.heightPt)
            }
            doc.finishPage(pdfPage)
        }
        saveAtomically(doc, output)
        doc.close()
    }

    override fun close() {
        pageList.forEach { it.baseBitmap.recycle() }
        pageList.clear()
        undoStack.clear()
        redoStack.clear()
    }

    private fun checkPage(page: Int) = require(page in 1..pageCount) { "Page $page is out of range (1..$pageCount)" }
    private fun normalizeRotation(r: Int) = ((r % 360) + 360) % 360

    private fun <T> mutate(block: () -> T): T {
        val snap = if (undoDepth > 0) snapshot() else null
        try {
            val result = block()
            if (snap != null) {
                undoStack.addLast(snap)
                while (undoStack.size > undoDepth) undoStack.removeFirst()
                redoStack.clear()
            }
            return result
        } catch (t: Throwable) {
            if (snap != null) restore(snap)
            throw t
        }
    }

    private fun snapshot(): List<PageModel> = pageList.map { it.copySnapshot() }

    private fun restore(snapshot: List<PageModel>) {
        pageList.forEach { it.baseBitmap.recycle() }
        pageList.clear()
        pageList.addAll(snapshot.map { it.copySnapshot() })
    }
}
