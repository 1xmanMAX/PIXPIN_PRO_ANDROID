package com.forge.pixpin

import android.app.Application
import com.forge.pixpin.data.SettingsRepository
import com.forge.pixpin.pin.OverlayManager

class PixPinApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    val overlayManager: OverlayManager by lazy { OverlayManager(this) }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
    }
}
