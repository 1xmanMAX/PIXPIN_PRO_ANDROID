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
    private val apagar = Runnable { if (aLaVista == 0) { red.cerrar(); SincronizacionAutomatica.alaVista(false) } }

    fun identidadCambio() { version.value = version.value + 1 }

    fun instalar(aplicacion: Application) {
        app = aplicacion
        red = Red(aplicacion)
        SincronizacionAutomatica.instalar(aplicacion)
        aplicacion.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                aLaVista++
                if (aLaVista == 1) { encender(); SincronizacionAutomatica.alaVista(true) }
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
                            alSaludar = { otro, puerto -> disco.apuntarDireccion(otro.id, quien, puerto) },
                            alAceptarSuelto = { _, mime -> LoAbierto.aceptar(delante(), mime, id.yo.nombre) },
                            alRecibirSuelto = { f, nombre, mime, otro -> alLoAbierto(f, nombre, mime, otro, id.yo.nombre) },
                            cache = java.io.File(app.cacheDir, "suelto")
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

    /** Qué hay delante ahora mismo: un lienzo, un chat o nada. Ver [LoAbierto]. */
    private fun delante(): LoAbierto.Delante = LoAbierto.delante(
        com.forge.pixpin.motor.LienzoAlFrente.actual() != null, com.forge.pixpin.motor.LienzoAlFrente.orden,
        com.forge.pixpin.guardados.ChatAlFrente.actual() != null, com.forge.pixpin.guardados.ChatAlFrente.orden
    )

    /**
     * **Un archivo suelto del PC** («p s» en Flow Launcher), ya llegado entero: a lo que esté
     * delante, según [LoAbierto]. Se vuelve a mirar qué hay: entre el «vale» y el último trozo de
     * un vídeo el usuario puede haber cambiado de pantalla. Devuelve dónde quedó y, si fue un chat
     * abierto, su nombre.
     */
    private fun alLoAbierto(fichero: java.io.File, nombre: String, mime: String, otro: Aparato, miNombre: String): Pair<String, String?> {
        val ahora = delante()
        // Se cambió a un lienzo mientras llegaba algo que no es una foto: se niega igual que antes
        // del vale. `recibirSuelto` convierte el error en la respuesta.
        LoAbierto.aceptar(ahora, mime, miNombre)?.let { throw IllegalStateException(it) }
        var lienzoPensando = false
        if (ahora == LoAbierto.Delante.LIENZO) {
            val lienzo = com.forge.pixpin.motor.LienzoAlFrente.actual()
            if (lienzo != null) {
                val hecho = java.util.concurrent.CountDownLatch(1)
                val puesta = java.util.concurrent.atomic.AtomicBoolean(false)
                principal.post {
                    try { puesta.set(lienzo.recibirImagen(fichero, mime)) } finally { hecho.countDown() }
                }
                val acabo = hecho.await(10, java.util.concurrent.TimeUnit.SECONDS)
                if (acabo && puesta.get()) {
                    apuntar("Foto de ${otro.nombre} puesta en el lienzo")
                    return Protocolo.SUELTO_EN_EL_LIENZO to null
                }
                lienzoPensando = !acabo
            }
        }
        // Si el lienzo se quedó pensando más de 10 s aún puede estar leyendo el fichero, y
        // `Recepcion.guardar` borra el que se le da: entonces, una copia. Si no, el mismo (con un
        // vídeo de 2 GB, copiarlo sería otro tanto de disco).
        val copia = if (lienzoPensando) java.io.File(fichero.parentFile, "chat_" + fichero.name).also { fichero.copyTo(it, overwrite = true) } else fichero
        try {
            val chat = if (ahora == LoAbierto.Delante.CHAT) com.forge.pixpin.guardados.ChatAlFrente.actual() else null
            if (chat != null) {
                val entro = com.forge.pixpin.guardados.AlChat.meterArchivo(
                    app, copia, nombre, mime, chat.proyecto, aligerar = false, recibidoDe = otro.nombre
                )
                if (!entro) throw IllegalStateException("No se pudo guardar «$nombre» en el chat")
                apuntar("«$nombre» de ${otro.nombre} puesto en el chat «${chat.nombre}»")
                return Protocolo.SUELTO_EN_EL_CHAT_ABIERTO to chat.nombre
            }
            val e = Envio.Elemento(
                tipo = Envio.ARCHIVO, nombre = nombre, bytes = copia.length(), mime = mime,
                // Única por envío: con la misma identidad, `guardarArchivo` sustituiría la anterior.
                identidad = "suelto:${otro.id}:${System.currentTimeMillis()}:$nombre"
            )
            Recepcion.guardar(app, e, copia, Envio.Oferta(de = otro.nombre, deId = otro.id, elementos = emptyList()))
                ?: throw IllegalStateException("No se pudo guardar «$nombre»")
            apuntar("«$nombre» de ${otro.nombre} guardado en la conversación general")
            return Protocolo.SUELTO_EN_EL_CHAT to null
        } finally {
            if (copia !== fichero) copia.delete()
        }
    }

    fun apuntar(texto: String) {
        registro.value = (listOf(texto) + registro.value).take(20)
    }
}
