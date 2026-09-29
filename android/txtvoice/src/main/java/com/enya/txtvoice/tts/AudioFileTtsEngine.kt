package com.enya.txtvoice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Base for engines that turn a segment into an audio file over the network. Handles the on-disk
 * cache, de-duplicated prefetching and MediaPlayer playback with live speed/pitch changes.
 */
abstract class AudioFileTtsEngine(protected val context: Context) : TtsEngine {

    /** Everything besides the text that changes the produced audio (server, voice, model, ...). */
    protected abstract val cacheSalt: String

    /** Writes the audio for [text] to [target]. Runs on an IO thread; may block. */
    protected abstract fun synthesize(text: String, locale: Locale?, target: File)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashMap<String, Deferred<File>>()
    private val lock = Mutex()

    @Volatile
    private var current: MediaPlayer? = null

    protected open fun cacheKey(text: String, locale: Locale?): String {
        val raw = listOf(cacheSalt, text).joinToString("\u0000")
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns the (possibly already running) download for this segment. Failed downloads, and finished
     * ones whose file has since been evicted from the cache, are started again.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fetch(segment: Segment, locale: Locale?): Deferred<File> = lock.withLock {
        val key = cacheKey(segment.text, locale)
        val existing = inFlight[key]
        if (existing != null && !existing.isCancelled) {
            if (!existing.isCompleted) return existing
            if (existing.getCompletionExceptionOrNull() == null && existing.getCompleted().exists()) return existing
        }
        val deferred = scope.async { download(key, segment.text, locale) }
        inFlight[key] = deferred
        deferred
    }

    private fun download(key: String, text: String, locale: Locale?): File {
        val dir = cacheDir(context)
        val file = File(dir, "$key.mp3")
        if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val tmp = File(dir, "$key.part")
        try {
            synthesize(text, locale, tmp)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        if (downloads.incrementAndGet() % TRIM_EVERY == 0) trimCache(context)
        return file
    }

    override suspend fun prepare(segment: Segment, locale: Locale?) {
        fetch(segment, locale)
    }

    override suspend fun speak(segment: Segment, rate: Float, pitch: Float, locale: Locale?, onStart: () -> Unit) {
        val file = fetch(segment, locale).await()
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                current = mp
                fun finish() {
                    if (current === mp) current = null
                    runCatching { mp.reset() }
                    runCatching { mp.release() }
                }
                cont.invokeOnCancellation {
                    runCatching { if (mp.isPlaying) mp.stop() }
                    finish()
                }
                try {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener {
                        runCatching { mp.playbackParams = PlaybackParams().setSpeed(rate).setPitch(pitch) }
                        mp.start()
                        onStart()
                    }
                    mp.setOnCompletionListener {
                        finish()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        finish()
                        // A corrupt cache entry would fail forever; drop it so the next attempt re-downloads.
                        file.delete()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("MediaPlayer error $what/$extra"))
                        true
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    finish()
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    }

    override fun applyLive(rate: Float, pitch: Float) {
        val mp = current ?: return
        runCatching {
            if (mp.isPlaying) mp.playbackParams = PlaybackParams().setSpeed(rate).setPitch(pitch)
        }
    }

    override fun release() {
        scope.cancel()
        current?.let { mp ->
            current = null
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
    }

    companion object {
        /** Neural voices at 48 kbps are ~21 MB per hour; keep roughly the last ten hours. */
        private const val MAX_CACHE_BYTES = 200L * 1024 * 1024
        private const val TRIM_EVERY = 20
        private val downloads = AtomicInteger(0)

        fun cacheDir(context: Context): File = File(context.cacheDir, "tts").apply { mkdirs() }

        fun cacheSizeBytes(context: Context): Long =
            cacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L

        fun clearCache(context: Context) {
            cacheDir(context).listFiles()?.forEach { it.delete() }
        }

        /** Deletes least recently used files until the cache fits in [MAX_CACHE_BYTES]. */
        fun trimCache(context: Context, maxBytes: Long = MAX_CACHE_BYTES) {
            val files = cacheDir(context).listFiles()?.filter { it.name.endsWith(".mp3") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                total -= f.length()
                f.delete()
            }
        }
    }
}
