package com.enya.pdfreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import com.enya.pdfreader.ui.App
import com.enya.pdfreader.ui.theme.PdfReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A document handed to us by another app (file manager, browser, "Share" sheet). */
    private val externalUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            externalUri.value = uriFromIntent(intent)
        }
        addOnNewIntentListener { newIntent ->
            uriFromIntent(newIntent)?.let { externalUri.value = it }
        }

        setContent {
            PdfReaderTheme {
                val pending by externalUri.collectAsState()
                App(
                    externalUri = pending,
                    onExternalConsumed = { externalUri.value = null },
                )
            }
        }
    }

    private fun uriFromIntent(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
    }
}
