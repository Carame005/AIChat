package com.example.caramelai

import android.app.Application
import com.example.caramelai.di.AppContainer

class CaramelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
