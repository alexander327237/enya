package com.enya.txtvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.enya.txtvoice.ui.AppNavGraph
import com.enya.txtvoice.ui.IncomingIntent
import com.enya.txtvoice.ui.ViewModelFactory
import com.enya.txtvoice.ui.theme.TxtVoiceTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Content that arrived via "Open with"/"Share" or the notification, consumed by the nav graph. */
    private val incoming = MutableStateFlow<IncomingIntent?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        val app = application as TxtVoiceApp
        val factory = ViewModelFactory(app.bookRepository, app.settingsRepository)
        handleIntent(intent)

        setContent {
            TxtVoiceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavGraph(factory = factory, incoming = incoming)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val parsed: IncomingIntent? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { IncomingIntent.OpenUri(it) }
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> IncomingIntent.OpenUri(stream)
                    !text.isNullOrBlank() -> IncomingIntent.SharedText(intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(), text)
                    else -> null
                }
            }
            else -> intent.getStringExtra(EXTRA_OPEN_BOOK)?.let { IncomingIntent.OpenBook(it) }
        }
        if (parsed != null) {
            intent.removeExtra(EXTRA_OPEN_BOOK)
            incoming.value = parsed
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_BOOK = "open_book"
    }
}
