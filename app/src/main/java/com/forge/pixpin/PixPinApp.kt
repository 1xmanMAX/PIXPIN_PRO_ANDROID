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
     * **Los lienzos que uno tiene abiertos**, la rueda de la multitarea de dentro de la
     * aplicación (17-sep-2026). Uno por proceso: lo escriben varios editores.
     * Ver [com.forge.pixpin.data.LienzosAbiertos].
     */
    val lienzosAbiertos: com.forge.pixpin.data.LienzosAbiertos by lazy {
        com.forge.pixpin.data.LienzosAbiertos(this)
    }

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

    private fun nombreDelProceso(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault("")

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // **El proceso del compresor no es la aplicación**: solo corre [com.forge.pixpin.pdf.PdfSqueezeService].
        // Nada de presencia en la red, ni reparar chats, ni reponer documentos desde dos procesos a la vez.
        if (nombreDelProceso().endsWith(com.forge.pixpin.pdf.PdfSqueezeService.PROCESO)) return
        com.forge.pixpin.pdf.ComprimirPdf.despues = { archivo, nivel -> com.forge.pixpin.pdf.PdfSqueezeService.encolar(this, archivo, nivel) }
        // Un PDF aligerado después de entrar: el chat tiene que decir lo que pesa ahora.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    scope.launch(Dispatchers.IO) { runCatching { com.forge.pixpin.guardados.MensajesStore(this@PixPinApp).ponerPesosAlDia() } }
                }
            },
            android.content.IntentFilter(com.forge.pixpin.pdf.PdfSqueezeService.ALIGERADO),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        com.forge.pixpin.pdf.ComprimirPdf.alMomento = { archivo, nivel -> com.forge.pixpin.pdf.PdfSqueezeService.ahora(this, archivo, nivel) }
        // Localizable para los otros aparatos del grupo mientras haya una pantalla de PixPin a la
        // vista. Ver [com.forge.pixpin.sincro.Presencia].
        com.forge.pixpin.sincro.Presencia.instalar(this)
        // **La tasa de refresco, en todas las ventanas y desde el primer momento.**
        //
        // Aquí y no en cada Activity porque son veinte y no tienen clase base común: de
        // una en una, la próxima que se escriba nacería a 60 Hz sin que nadie lo note.
        // Ver [com.forge.pixpin.motor.PantallaFluida].
        com.forge.pixpin.motor.PantallaFluida.vigilarLasVentanas(this)
        // Y lo que el capturador no ve —crashes nativos, ANR—, que Android sí
        // apunta: se recoge al arrancar y acaba en el mismo informe.
        scope.launch { CrashLog.recogerMuertesDelSistema(this@PixPinApp) }
        settings = SettingsRepository(this)
        scope.launch { settings.settings.collect { ajustes = it; com.forge.pixpin.pdf.ComprimirPdf.nivel = it.compresionPdf } }
        // **Reponer los PDF que se hayan quedado sin archivo.**
        //
        // Esto estaba escrito, probado y documentado —el propio comentario de
        // `reponerLosPdf` dice «lo llama PixPinApp al arrancar»— pero **no lo llamaba
        // nadie**: la única mención estaba dentro de ese comentario. El resultado era que
        // un proyecto cuyo PDF se hubiera borrado se quedaba sin documento para siempre,
        // aunque su copia limpia siguiera ahí al lado: sin portada, sin miniaturas de
        // página y sin poder volver a pinearlo. Se notaba como «solo me sale la miniatura
        // de uno», que es el que todavía conservaba su archivo.
        //
        // Va en el hilo de disco porque copia documentos enteros, y sin esperar a nadie:
        // si tarda, la lista aparece primero y las portadas después.
        scope.launch(Dispatchers.IO) {
            runCatching { proyectos.reponerLosPdf() }
            // **Los tres códigos a lo que no los tenga** (15-sep-2026): el único, el de chat y la
            // fecha, para que se vean en el chat y viajen al compartir. Ver [com.forge.pixpin.sincro.Codigos].
            runCatching { com.forge.pixpin.sincro.Red.disco(this@PixPinApp).sellar() }
            // **Todo lo de los proyectos, en el chat.** Ver [com.forge.pixpin.guardados.RegistroDelChat].
            com.forge.pixpin.guardados.ChatDeLosProyectos.reparar(this@PixPinApp)
        }
    }
}
