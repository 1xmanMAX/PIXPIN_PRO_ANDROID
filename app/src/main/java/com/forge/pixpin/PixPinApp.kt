package com.forge.pixpin

import android.app.Application
import com.forge.pixpin.data.SettingsRepository

class PixPinApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
    }
}
