package com.enya.pdfreader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.roundToInt

class PdfOpenException(val kind: Kind, cause: Throwable?) : Exception(cause) {
    enum class Kind { NO_ACCESS, PASSWORD, CORRUPT }
}

/**
 * Thin, coroutine-friendly wrapper around the platform [PdfRenderer].
 *
 * PdfRenderer is not thread-safe and allows only one open page at a time, so all rendering is
 * serialised through a mutex. Rendered pages are kept in an LRU bitmap cache keyed by
 * page index and pixel width.
 */
class PdfDocument private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val name: String,
    val sizeBytes: Long,
) : Closeable {

    val pageCount: Int = renderer.pageCount

    private val mutex = Mutex()

    @Volatile
    private var closed = false

    private val cache = object : LruCache<String, Bitmap>(cacheBudgetBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun render(pageIndex: Int, widthPx: Int): Bitmap {
        require(pageIndex in 0 until pageCount) { "page $pageIndex out of range" }
        val width = widthPx.coerceIn(16, MAX_DIMENSION)
        val key = "$pageIndex@$width"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cache.get(key)?.let { return@withLock it }
                if (closed) throw IOException("document closed")
                renderer.openPage(pageIndex).use { page ->
                    val height = (width * page.height / page.width.toFloat())
                        .roundToInt()
                        .coerceIn(16, MAX_DIMENSION)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE) // PDF pages are transparent by default
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bitmap)
                    bitmap
                }
            }
        }
    }

    override fun close() {
        closed = true
        cache.evictAll()
        try {
            renderer.close()
        } catch (_: Exception) {
        }
        try {
            pfd.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MAX_DIMENSION = 4096

        private fun cacheBudgetBytes(): Int {
            val max = Runtime.getRuntime().maxMemory()
            return (max / 5).coerceIn(24L * 1024 * 1024, 160L * 1024 * 1024).toInt()
        }

        suspend fun open(context: Context, uri: Uri): PdfDocument = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = queryDisplayName(context, uri)

            val pfd = try {
                resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
            } catch (e: Exception) {
                throw PdfOpenException(PdfOpenException.Kind.NO_ACCESS, e)
            }

            try {
                val renderer = createRenderer(pfd)
                PdfDocument(pfd, renderer, name, pfd.statSize)
            } catch (e: IOException) {
                // Most likely a non-seekable stream (e.g. a pipe from a network provider):
                // copy it to the cache directory and try again from a real file.
                pfd.close()
                val copy = copyToCache(context, uri)
                val copyPfd = try {
                    ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (e2: Exception) {
                    throw PdfOpenException(PdfOpenException.Kind.NO_ACCESS, e2)
                }
                try {
                    val renderer = createRenderer(copyPfd)
                    PdfDocument(copyPfd, renderer, name, copy.length())
                } catch (e2: Exception) {
                    copyPfd.close()
                    if (e2 is PdfOpenException) throw e2
                    throw PdfOpenException(PdfOpenException.Kind.CORRUPT, e2)
                }
            } catch (e: PdfOpenException) {
                pfd.close()
                throw e
            } catch (e: Exception) {
                pfd.close()
                throw PdfOpenException(PdfOpenException.Kind.CORRUPT, e)
            }
        }

        private fun createRenderer(pfd: ParcelFileDescriptor): PdfRenderer = try {
            PdfRenderer(pfd)
        } catch (e: SecurityException) {
            throw PdfOpenException(PdfOpenException.Kind.PASSWORD, e)
        }

        private fun copyToCache(context: Context, uri: Uri): File {
            val dir = File(context.cacheDir, "opened").apply { mkdirs() }
            val target = File(dir, uri.toString().hashCode().toUInt().toString(16) + ".pdf")
            val input = try {
                context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
            } catch (e: Exception) {
                throw PdfOpenException(PdfOpenException.Kind.NO_ACCESS, e)
            }
            input.use { ins -> target.outputStream().use { outs -> ins.copyTo(outs) } }
            return target
        }

        private fun queryDisplayName(context: Context, uri: Uri): String {
            if (uri.scheme == "content") {
                try {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (col >= 0 && cursor.moveToFirst()) {
                                val n = cursor.getString(col)
                                if (!n.isNullOrBlank()) return n
                            }
                        }
                } catch (_: Exception) {
                }
            }
            val last = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            return if (last.isNullOrBlank()) "document.pdf" else last
        }
    }
}
