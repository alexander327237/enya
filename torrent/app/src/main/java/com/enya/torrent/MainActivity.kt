package com.enya.torrent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.enya.torrent.ui.TorrentScreen
import com.enya.torrent.ui.theme.EnyaTorrentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            TorrentEngine.updateSaveDir(this)
        }

    private val pickTorrent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { addFromUri(it) }
        }

    private var crashLog by mutableStateOf<String?>(null)
    private var batteryOptimized by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        TorrentEngine.updateSaveDir(this)
        crashLog = CrashLog.read(this)

        // Start the download service only after the first frame, when the main thread is idle
        // again: the service must answer startForegroundService() within seconds, and a cold
        // Compose start can keep the main thread busy long enough to miss that window.
        // Adding a torrent starts the service on demand.
        window.decorView.post {
            if (TorrentEngine.isRunning || TorrentEngine.hasSavedTorrents()) ensureService()
        }
        handleIntent(intent)

        lifecycleScope.launch {
            TorrentEngine.messages.collect { msg ->
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        setContent {
            EnyaTorrentTheme {
                TorrentScreen(
                    onAdd = { text ->
                        ensureService()
                        TorrentEngine.add(text)
                    },
                    onPickFile = { pickTorrent.launch(arrayOf("application/x-bittorrent", "*/*")) },
                    onPause = TorrentEngine::pause,
                    onResume = TorrentEngine::resume,
                    onRemove = TorrentEngine::remove,
                    onStopService = { TorrentService.stop(this) },
                    onStartService = { TorrentService.start(this) },
                    onRequestStorage = ::requestStorageAccess,
                    batteryOptimized = batteryOptimized,
                    onRequestBattery = ::requestIgnoreBatteryOptimizations,
                    crashLog = crashLog,
                    onCopyCrashLog = ::copyCrashLog,
                    onShareCrashLog = ::shareCrashLog,
                    onClearCrashLog = {
                        CrashLog.clear(this)
                        crashLog = null
                    },
                )
            }
        }
    }

    private fun ensureService() {
        if (!TorrentEngine.isRunning) TorrentService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // The user may have just granted "All files access" in system settings.
        TorrentEngine.updateSaveDir(this)
        batteryOptimized = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    /** Asks Android not to kill the download service in Doze / battery saver. */
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось открыть настройки: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Only ever called from the button on screen. Android 11+: opens the "All files access"
     * settings page; older versions: a runtime permission dialog.
     */
    private fun requestStorageAccess() {
        val settings = Storage.allFilesAccessIntent(this)
        if (settings != null) {
            try {
                startActivity(settings)
            } catch (t: Throwable) {
                Toast.makeText(this, "Не удалось открыть настройки: ${t.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun shareCrashLog() {
        val text = crashLog ?: return
        try {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Enya Torrent crash log")
                .putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, "Отправить лог"))
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось поделиться: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyCrashLog() {
        val text = crashLog ?: return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Enya Torrent crash log", text))
        Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        ensureService()
        if (uri.scheme.equals("magnet", ignoreCase = true)) {
            TorrentEngine.addMagnet(uri.toString())
        } else {
            addFromUri(uri)
        }
        // Consume the intent so rotating the screen does not re-add the torrent.
        intent.action = Intent.ACTION_MAIN
        intent.data = null
    }

    private fun addFromUri(uri: Uri) {
        ensureService()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Не удалось открыть файл", Toast.LENGTH_LONG).show()
                    }
                } else {
                    TorrentEngine.addTorrentBytes(bytes)
                }
            } catch (t: Throwable) {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ошибка: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
