package com.enya.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * A thin wrapper over a single libtorrent session. The session lives for the whole process;
 * [TorrentService] keeps the process alive in the foreground while downloads run.
 */
object TorrentEngine {

    private const val TAG = "TorrentEngine"

    data class Item(
        val hash: String,
        val name: String,
        val progress: Float,
        val downloadRate: Int,
        val uploadRate: Int,
        val state: TorrentStatus.State,
        val peers: Int,
        val seeds: Int,
        val done: Long,
        val total: Long,
        val paused: Boolean,
        val finished: Boolean,
        val hasMetadata: Boolean,
        val error: String?,
    )

    private val session = SessionManager()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val errors = ConcurrentHashMap<String, String>()

    private val _torrents = MutableStateFlow<List<Item>>(emptyList())
    val torrents: StateFlow<List<Item>> = _torrents.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages = _messages.asSharedFlow()

    /** Where new downloads land. Re-evaluated via [updateSaveDir] when storage permissions change. */
    private val _saveDir = MutableStateFlow<File?>(null)
    val saveDir: StateFlow<File?> = _saveDir.asStateFlow()

    private val _publicAccess = MutableStateFlow(false)
    val publicAccess: StateFlow<Boolean> = _publicAccess.asStateFlow()

    /** Where we remember magnet links / .torrent files so downloads survive a restart. */
    private lateinit var storeDir: File

    fun init(context: Context) {
        val app = context.applicationContext
        storeDir = File(app.filesDir, "torrents")
        storeDir.mkdirs()
        updateSaveDir(app)

        session.addListener(object : AlertListener {
            override fun types(): IntArray? = null
            override fun alert(alert: Alert<*>) = onAlert(alert)
        })
    }

    /** Picks the public Downloads folder when the app may write there, else its private folder. */
    fun updateSaveDir(context: Context) {
        _publicAccess.value = Storage.hasPublicAccess(context)
        _saveDir.value = Storage.resolveSaveDir(context)
    }

    private fun currentSaveDir(): File = _saveDir.value ?: error("TorrentEngine.init() was not called")

    @Synchronized
    fun start() {
        if (session.isRunning) return
        val settings = SettingsPack()
            .activeDownloads(4)
            .activeSeeds(4)
            .listenInterfaces("0.0.0.0:6881,[::]:6881")
        session.start(SessionParams(settings))
        _running.value = true
        restoreSaved()
        refresh()
    }

    @Synchronized
    fun stop() {
        if (!session.isRunning) return
        session.stop()
        handles.clear()
        _running.value = false
        _torrents.value = emptyList()
    }

    val isRunning: Boolean get() = session.isRunning

    // ---- adding ----------------------------------------------------------------------------

    /** Accepts a magnet link, a bare 40-hex info hash, or an http(s) URL pointing to a .torrent. */
    fun add(input: String) {
        val text = input.trim()
        when {
            text.startsWith("magnet:", ignoreCase = true) -> addMagnet(text)
            text.matches(Regex("^[0-9a-fA-F]{40}$")) -> addMagnet("magnet:?xt=urn:btih:$text")
            text.startsWith("http://", true) || text.startsWith("https://", true) -> addFromUrl(text)
            else -> post("Не похоже на magnet-ссылку, хеш или URL .torrent")
        }
    }

    fun addMagnet(uri: String) {
        try {
            val params = AddTorrentParams.parseMagnetUri(uri)
            val hash = params.infoHashes.best.toHex()
            if (handles.containsKey(hash)) {
                post("Этот торрент уже добавлен")
                return
            }
            File(storeDir, "$hash.magnet").writeText(uri)
            enqueue(params)
        } catch (t: Throwable) {
            Log.w(TAG, "bad magnet", t)
            post("Не удалось разобрать magnet-ссылку: ${t.message}")
        }
    }

    fun addTorrentBytes(bytes: ByteArray) {
        try {
            val info = TorrentInfo.bdecode(bytes)
            val hash = info.infoHash().toHex()
            if (handles.containsKey(hash)) {
                post("Этот торрент уже добавлен")
                return
            }
            File(storeDir, "$hash.torrent").writeBytes(bytes)
            enqueue(AddTorrentParams().apply { torrentInfo = info })
        } catch (t: Throwable) {
            Log.w(TAG, "bad torrent file", t)
            post("Не удалось прочитать .torrent: ${t.message}")
        }
    }

    private fun addFromUrl(url: String) {
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                val bytes = conn.inputStream.use { it.readBytes() }
                addTorrentBytes(bytes)
            } catch (t: Throwable) {
                Log.w(TAG, "download torrent file failed", t)
                post("Не удалось скачать .torrent: ${t.message}")
            }
        }.start()
    }

    private fun enqueue(params: AddTorrentParams, savePath: String? = null) {
        start()
        val path = savePath ?: currentSaveDir().absolutePath
        params.savePath = path
        File(storeDir, "${params.infoHashes.best.toHex()}.path").writeText(path)
        SessionHandle(session.swig()).asyncAddTorrent(params)
    }

    private fun restoreSaved() {
        storeDir.listFiles()?.forEach { file ->
            try {
                // Keep downloading into the folder the torrent was originally added with.
                val savedPath = File(storeDir, "${file.nameWithoutExtension}.path")
                    .takeIf { it.isFile }?.readText()?.takeIf { File(it).isDirectory }
                when (file.extension) {
                    "magnet" -> enqueue(AddTorrentParams.parseMagnetUri(file.readText()), savedPath)
                    "torrent" -> enqueue(AddTorrentParams().apply { torrentInfo = TorrentInfo(file) }, savedPath)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "restore ${file.name} failed", t)
            }
        }
    }

    // ---- controlling -----------------------------------------------------------------------

    fun pause(hash: String) {
        handles[hash]?.takeIf { it.isValid }?.let {
            it.unsetFlags(TorrentFlags.AUTO_MANAGED)
            it.pause()
        }
        refresh()
    }

    fun resume(hash: String) {
        handles[hash]?.takeIf { it.isValid }?.let {
            it.setFlags(TorrentFlags.AUTO_MANAGED)
            it.resume()
        }
        refresh()
    }

    fun remove(hash: String, deleteFiles: Boolean) {
        val handle = handles.remove(hash)
        errors.remove(hash)
        File(storeDir, "$hash.magnet").delete()
        File(storeDir, "$hash.torrent").delete()
        File(storeDir, "$hash.path").delete()
        if (handle != null && handle.isValid) {
            if (deleteFiles) session.remove(handle, SessionHandle.DELETE_FILES) else session.remove(handle)
        }
        refresh()
    }

    // ---- status ----------------------------------------------------------------------------

    fun refresh() {
        if (!session.isRunning) return
        val list = handles.entries.mapNotNull { (hash, handle) ->
            if (!handle.isValid) return@mapNotNull null
            val st = handle.status()
            val paused = handle.flags.and_(TorrentFlags.PAUSED).non_zero()
            val name = st.name().ifBlank { hash.take(12) }
            Item(
                hash = hash,
                name = name,
                progress = st.progress(),
                downloadRate = st.downloadPayloadRate(),
                uploadRate = st.uploadPayloadRate(),
                state = st.state(),
                peers = st.numPeers(),
                seeds = st.numSeeds(),
                done = st.totalWantedDone(),
                total = st.totalWanted(),
                paused = paused,
                finished = st.isFinished,
                hasMetadata = st.hasMetadata(),
                error = errors[hash] ?: st.errorCode().takeIf { it.isError }?.message,
            )
        }.sortedBy { it.name.lowercase() }
        _torrents.value = list
    }

    val totalDownloadRate: Long get() = if (session.isRunning) session.downloadRate() else 0
    val totalUploadRate: Long get() = if (session.isRunning) session.uploadRate() else 0

    private fun onAlert(alert: Alert<*>) {
        when (alert.type()) {
            AlertType.ADD_TORRENT -> {
                val a = alert as AddTorrentAlert
                if (a.error().isError) {
                    post("Ошибка добавления: ${a.error().message}")
                } else {
                    val handle = a.handle()
                    handles[handle.infoHash().toHex()] = handle
                }
                refresh()
            }
            AlertType.METADATA_RECEIVED -> refresh()
            AlertType.TORRENT_FINISHED -> {
                val a = alert as TorrentFinishedAlert
                post("Загрузка завершена: ${a.handle().status().name()}")
                refresh()
            }
            AlertType.TORRENT_ERROR -> {
                val a = alert as TorrentErrorAlert
                errors[a.handle().infoHash().toHex()] = a.error().message
                refresh()
            }
            else -> Unit
        }
    }

    private fun post(message: String) {
        Log.i(TAG, message)
        _messages.tryEmit(message)
    }

}
