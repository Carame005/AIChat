package com.example.caramelai

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.caramelai.data.settings.AppSettings
import com.example.caramelai.ui.navigation.CaramelNavHost
import com.example.caramelai.ui.theme.CaramelAITheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepository = (application as CaramelApp).container.settingsRepository
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            // Locale is applied in CaramelApp (startup) and SettingsViewModel.setLanguage.
            // Applying it here from collectAsState initialValue caused an en<->es recreate loop.
            CaramelAITheme(theme = settings.theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CaramelNavHost()
                }
            }
        }
    }
}
