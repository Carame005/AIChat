package com.example.caramelai

import android.app.Application
import com.example.caramelai.data.settings.AppLanguage
import com.example.caramelai.di.AppContainer

class CaramelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // English until Settings/DataStore apply the saved preference.
        AppLanguage.apply(AppLanguage.English)
    }
}
