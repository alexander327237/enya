package com.enya.torrent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.enya.torrent.ui.TorrentScreen
import com.enya.torrent.ui.theme.EnyaTorrentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickTorrent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { addFromUri(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        TorrentService.start(this)
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
                        TorrentService.start(this)
                        TorrentEngine.add(text)
                    },
                    onPickFile = { pickTorrent.launch(arrayOf("application/x-bittorrent", "*/*")) },
                    onPause = TorrentEngine::pause,
                    onResume = TorrentEngine::resume,
                    onRemove = TorrentEngine::remove,
                    onStopService = { TorrentService.stop(this) },
                    onStartService = { TorrentService.start(this) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
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
