package com.example.caramelai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.caramelai.data.settings.AppLanguage
import com.example.caramelai.ui.navigation.CaramelNavHost
import com.example.caramelai.ui.theme.CaramelAITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepository = (application as CaramelApp).container.settingsRepository
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = com.example.caramelai.data.settings.AppSettings(),
            )
            LaunchedEffect(settings.language) {
                AppLanguage.apply(settings.language)
            }
            CaramelAITheme(theme = settings.theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CaramelNavHost()
                }
            }
        }
    }
}
