package com.forge.pixpin.sincro

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * **Estar localizable para el grupo mientras PixPin está abierto.**
 *
 * Antes solo se atendía con la pantalla de Sincronizar abierta: al salir a añadir una foto y
 * volver, el aparato dejaba de anunciarse y se anunciaba otra vez, y en muchas Wi-Fi ese anuncio
 * nuevo tarda uno o dos minutos en llegar al otro. Eso es lo que vio el usuario: «tengo que
 * esperar un minuto o dos y recién me aparece para sincronizar».
 *
 * Ahora se escucha y se anuncia **mientras haya alguna pantalla de PixPin a la vista**, y se deja
 * de hacer un minuto después de que no quede ninguna. Escuchar un puerto sin nadie hablando no
 * gasta nada; lo que sí gasta, buscar a los demás, solo se hace con Sincronizar abierta.
 */
object Presencia {

    /** Sube cuando cambia la identidad (alguien se unió, otro nombre). La pantalla lo mira. */
    val version = MutableStateFlow(0)

    /** Lo último que pasó como respondedor, para enseñarlo. */
    val registro = MutableStateFlow<List<String>>(emptyList())

    /** Lo que está pasando ahora mismo como respondedor, o nada. */
    val atendiendo = MutableStateFlow<String?>(null)

    private lateinit var app: Application
    lateinit var red: Red
        private set
    private val principal = Handler(Looper.getMainLooper())
    private var aLaVista = 0
    private val apagar = Runnable { if (aLaVista == 0) red.cerrar() }

    fun identidadCambio() { version.value = version.value + 1 }

    fun instalar(aplicacion: Application) {
        app = aplicacion
        red = Red(aplicacion)
        aplicacion.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                aLaVista++
                if (aLaVista == 1) encender()
            }
            override fun onActivityStopped(activity: Activity) {
                aLaVista = (aLaVista - 1).coerceAtLeast(0)
                if (aLaVista == 0) { principal.removeCallbacks(apagar); principal.postDelayed(apagar, 60_000) }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Escucha y se anuncia si este aparato está en un grupo; si no, lo apaga. Se llama al abrir
     * PixPin y cada vez que cambia el grupo o el nombre.
     */
    fun encender() {
        if (!::red.isInitialized) return
        principal.removeCallbacks(apagar)
        Thread {
            val disco = Red.disco(app)
            val id = disco.identidad.leer()
            if (!id.enGrupo) { principal.post { red.cerrar() }; return@Thread }
            val etiqueta = Grupo.etiqueta(Grupo.clave(id.codigo!!))
            principal.post {
                red.escuchar(Red.PUERTO) { socket, entrada ->
                    val quien = socket.inetAddress?.hostAddress.orEmpty()
                    runCatching {
                        Respondedor(
                            disco,
                            estado = { atendiendo.value = it },
                            miPuerto = red.puerto,
                            alSaludar = { otro, puerto -> disco.apuntarDireccion(otro.id, quien, puerto) }
                        ).atender(entrada, socket.getOutputStream())
                    }.onFailure { e ->
                        apuntar(when (e) {
                            is Canal.CodigoDistinto -> "Alguien intentó conectar con otro código de grupo"
                            else -> "Se cortó una sincronización: ${e.message ?: e.javaClass.simpleName}"
                        })
                    }
                    atendiendo.value?.let { apuntar(it) }
                    atendiendo.value = null
                }
                red.anunciar(Red.TIPO, "PixPin ${id.yo.nombre}", mapOf(
                    "g" to etiqueta, "id" to id.yo.id, "l" to (id.yo.letra ?: ""), "n" to id.yo.nombre.take(40)
                ))
            }
        }.start()
    }

    /** Vuelve a empezar: tras crear o dejar el grupo, o cambiar de nombre. */
    fun reiniciar() {
        if (!::red.isInitialized) return
        principal.post { red.cerrar(); encender() }
    }

    fun apuntar(texto: String) {
        registro.value = (listOf(texto) + registro.value).take(20)
    }
}
