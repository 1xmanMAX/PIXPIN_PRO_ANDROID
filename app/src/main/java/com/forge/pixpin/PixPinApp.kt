package com.forge.pixpin

import android.app.Application
import com.forge.pixpin.data.CrashLog
import com.forge.pixpin.data.SettingsRepository
import com.forge.pixpin.pin.OverlayManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.util.Log

class PixPinApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    /**
     * Scope de toda la app. El handler evita que un fallo en una corrutina
     * de fondo (importar un archivo, leer ajustes…) tumbe la app entera.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error -> Log.e("PixPin", "corrutina", error) }
    )

    val overlayManager: OverlayManager by lazy { OverlayManager(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        settings = SettingsRepository(this)
    }
}
