package com.enya.ollama

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.enya.ollama.ui.ViewModelFactory
import com.enya.ollama.ui.nav.EnyaNavGraph
import com.enya.ollama.ui.theme.EnyaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as EnyaApplication
        val factory = ViewModelFactory(app.chatRepository, app.settingsRepository)

        setContent {
            EnyaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EnyaNavGraph(factory = factory)
                }
            }
        }
    }
}
