package com.forge.pixpin

import android.app.Application
import com.forge.pixpin.data.CrashLog
import com.forge.pixpin.data.ProyectosRepository
import com.forge.pixpin.data.SettingsRepository
import com.forge.pixpin.pin.OverlayManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /**
     * Los proyectos: varias hojas que se entregan juntas.
     *
     * Aquí y no en cada pantalla porque **hay tres sitios mirándolos a la vez**:
     * la lista, el editor que está anotando una hoja y el pin del PDF del que
     * salió. Con una copia por sitio, anotar una página no aparecería en la
     * lista hasta cerrarla y volver a abrirla.
     */
    val proyectos: ProyectosRepository by lazy { ProyectosRepository(this) }

    /**
     * Los ajustes tal como estaban la última vez que cambiaron.
     *
     * Existe para quien **no puede esperar**: exportar una imagen decide el
     * formato en mitad de un `Bitmap.compress`, dentro de una función que no es
     * suspendida y a la que llegar con el `Flow` obligaría a bloquear un hilo.
     * Aquí hay un valor listo, y el colector de abajo lo mantiene al día.
     *
     * `@Volatile` porque lo escribe el hilo principal y lo leen las corrutinas
     * de E/S: sin eso, una podría seguir viendo el valor viejo indefinidamente.
     */
    @Volatile
    var ajustes: com.forge.pixpin.data.Settings = com.forge.pixpin.data.Settings()
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        settings = SettingsRepository(this)
        scope.launch { settings.settings.collect { ajustes = it } }
    }
}
