package com.example.caramelai

import android.app.Application
import com.example.caramelai.data.settings.AppLanguage
import com.example.caramelai.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class CaramelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Apply saved language once. Do NOT also apply a placeholder in MainActivity —
        // that caused an en/es recreate loop (DataStore vs AppSettings defaults).
        val language = runBlocking {
            container.settingsRepository.settings.first().language
        }
        AppLanguage.apply(language)
    }
}
