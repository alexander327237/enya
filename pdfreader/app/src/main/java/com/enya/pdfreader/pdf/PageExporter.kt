package com.enya.pdfreader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument as AndroidPdfDocument
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

enum class ExportFormat(val mimeType: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    PNG("image/png", "png"),
}

/**
 * Saves pages of an open document as standalone files.
 *
 * PDF export first tries a true page extraction with PdfBox (text stays selectable, vectors stay
 * sharp). If that fails for any reason (unusual file, memory pressure, encryption), it falls
 * back to rendering the pages to bitmaps and wrapping them in a new PDF.
 */
object PageExporter {

    private const val EXPORT_WIDTH_PX = 2000
    private const val PDFBOX_MEMORY_BYTES = 64L * 1024 * 1024

    /** Writes [pages] (zero-based, inclusive) of the document as one PDF to [out]. */
    suspend fun exportPdf(
        context: Context,
        sourceUri: Uri,
        document: PdfDocument,
        pages: IntRange,
        out: OutputStream,
    ) {
        require(!pages.isEmpty() && pages.first >= 0 && pages.last < document.pageCount) { "bad range $pages" }
        val temp = File(context.cacheDir, "export-${System.nanoTime()}.pdf")
        try {
            val extracted = withContext(Dispatchers.IO) {
                try {
                    extractPagesWithPdfBox(context, sourceUri, pages, temp)
                } catch (e: Throwable) {
                    // OutOfMemoryError included: fall back to the raster path below.
                    false
                }
            }
            if (extracted && temp.length() > 0) {
                withContext(Dispatchers.IO) {
                    temp.inputStream().use { it.copyTo(out) }
                    out.flush()
                }
            } else {
                rasterPdf(document, pages, out)
            }
        } finally {
            temp.delete()
        }
    }

    /** Writes a single page as a PNG image to [out]. */
    suspend fun exportPng(document: PdfDocument, pageIndex: Int, out: OutputStream) {
        val bitmap = document.render(pageIndex, EXPORT_WIDTH_PX)
        withContext(Dispatchers.IO) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
    }

    private fun extractPagesWithPdfBox(context: Context, sourceUri: Uri, pages: IntRange, target: File): Boolean {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
        val input = context.contentResolver.openInputStream(sourceUri) ?: return false
        val memory = MemoryUsageSetting.setupMixed(PDFBOX_MEMORY_BYTES).setTempDir(context.cacheDir)
        input.use { ins ->
            PDDocument.load(ins, memory).use { source ->
                if (source.isEncrypted || pages.last >= source.numberOfPages) return false
                val count = pages.last - pages.first + 1
                val splitter = Splitter().apply {
                    setStartPage(pages.first + 1)
                    setEndPage(pages.last + 1)
                    setSplitAtPage(count)
                }
                val parts = splitter.split(source)
                try {
                    val part = parts.firstOrNull() ?: return false
                    if (part.numberOfPages != count) return false
                    part.save(target)
                } finally {
                    parts.forEach { runCatching { it.close() } }
                }
            }
        }
        return true
    }

    private suspend fun rasterPdf(document: PdfDocument, pages: IntRange, out: OutputStream) {
        val pdf = AndroidPdfDocument()
        try {
            for ((number, pageIndex) in pages.withIndex()) {
                val (widthPt, heightPt) = document.pageSizePoints(pageIndex)
                val bitmap = document.render(pageIndex, EXPORT_WIDTH_PX)
                withContext(Dispatchers.IO) {
                    val info = AndroidPdfDocument.PageInfo.Builder(widthPt, heightPt, number + 1).create()
                    val page = pdf.startPage(info)
                    page.canvas.drawBitmap(
                        bitmap,
                        null,
                        Rect(0, 0, widthPt, heightPt),
                        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                    )
                    pdf.finishPage(page)
                }
            }
            withContext(Dispatchers.IO) {
                pdf.writeTo(out)
                out.flush()
            }
        } finally {
            pdf.close()
        }
    }
}
