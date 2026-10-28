package com.forge.pixpin.guardados

import android.content.Context
import android.os.StatFs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * **El diario de la grabación que está en marcha** (28-oct-2026, «Grabar sin perder nada»; lo de
 * `grabar_clase/a_salvo.rs` del PC): lo usan [GrabarClaseService] y [ReunionPresencialService] para
 * apuntar en el disco **en el acto** todo lo que se va haciendo ([GrabacionAMedias.Diario]).
 *
 * - Se escribe en un hilo propio, en orden ([guardar]): el JSON se saca en el hilo de quien llama (es
 *   pequeño) y el disco no frena la barra ni el micrófono.
 * - **El latido** ([latir], cada [GrabacionAMedias.CADA_LATIDO_MS]): a qué hora iba la nota por qué
 *   minuto. Si la hora corrió mucho más que la nota sin estar en pausa (Android congeló la app, el
 *   teléfono se durmió), apunta **un hueco** en el reloj de pared y lo devuelve para avisar.
 * - **El reloj de pared** de la grabación, al día en [RelojDeLaGrabacion.enCurso] en cada latido.
 * - **La guarda del disco** ([espacio]): `StatFs(filesDir)` con los umbrales del PC (2 GB, 500 MB).
 *
 * Todo se llama desde el hilo principal.
 */
class DiarioEnMarcha(private val context: Context, val diario: GrabacionAMedias.Diario) {

    /** La carpeta del diario: ahí van también las capturas y las fotos. */
    val dir: File = diario.carpeta(context.filesDir)

    private val escritor = Executors.newSingleThreadExecutor { r -> Thread(r, "pixpin-diario").apply { isDaemon = true } }
    private val aparato: String by lazy { runCatching { com.forge.pixpin.sincro.Codigos.deEsteAparato(context.filesDir) }.getOrNull().orEmpty() }
    @Volatile private var cerrado = false

    init {
        enMarcha += diario.marca
        dir.mkdirs()
        guardar()
        RelojDeLaGrabacion.enCurso = reloj()
    }

    /** **Lo lleva al disco**, en orden, sin esperar. */
    fun guardar() {
        if (cerrado) return
        val bytes = synchronized(diario) { diario.aJson() }.toByteArray()
        runCatching {
            escritor.execute {
                runCatching { GrabacionAMedias.escribirEntero(File(dir, GrabacionAMedias.FICHERO), bytes) }
                    .onFailure { android.util.Log.w("PixPinDiario", "el diario no se pudo escribir", it) }
            }
        }
    }

    /** Cambia el diario y lo lleva al disco. */
    fun cambiar(que: GrabacionAMedias.Diario.() -> Unit) {
        synchronized(diario) { diario.que() }
        guardar()
    }

    /**
     * **Un latido**: la nota va por [ms] (lo grabado de verdad, si el grabador lo sabe) y está o no en
     * pausa. Devuelve los minutos del hueco si se acaba de apuntar uno (para avisar), o null.
     */
    fun latir(ms: Long, pausa: Boolean): Long? {
        val ahora = System.currentTimeMillis()
        val antes = synchronized(diario) { diario.reloj.huecos.size }
        val cambio = synchronized(diario) { diario.latir(ahora, ms, pausa) }
        RelojDeLaGrabacion.enCurso = reloj()
        guardar()
        if (!cambio) return null
        val hueco = synchronized(diario) { diario.reloj.huecos.drop(antes).lastOrNull() } ?: return null
        return (((hueco.hasta ?: ahora) - hueco.desde) / 60_000).coerceAtLeast(1)
    }

    /** **El reloj de pared** como va en la nota, visto ahora. */
    fun reloj(): RelojDePared = synchronized(diario) {
        RelojDeLaGrabacion.delDiario(diario.reloj, aparato, diario.latido.utc, diario.movil.orEmpty())
    }

    /** **Cuánto cabe** en el disco de la app ahora. */
    fun espacio(): GrabacionAMedias.Espacio = GrabacionAMedias.espacio(libres(context))

    /** Acaba el reloj (al pulsar «Terminar»). */
    fun acabar() {
        cambiar { acabar(System.currentTimeMillis()) }
        RelojDeLaGrabacion.enCurso = null
    }

    /**
     * **Termina con el diario**: espera a que se escriba lo pendiente y, si [borrar] (la grabación entró
     * entera en el chat, o se tiró), borra su carpeta. Sin borrar, se queda para recuperarla.
     */
    fun cerrar(borrar: Boolean) {
        if (cerrado) return
        cerrado = true
        escritor.shutdown()
        runCatching { escritor.awaitTermination(3, TimeUnit.SECONDS) }
        if (borrar) runCatching { dir.deleteRecursively() }
        enMarcha -= diario.marca
        if (RelojDeLaGrabacion.enCurso?.inicio == diario.reloj.inicio) RelojDeLaGrabacion.enCurso = null
    }

    companion object {
        /** Las marcas de los diarios que se están grabando ahora: no son de una grabación cortada. */
        val enMarcha: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        /** Los bytes libres en el disco de la app, o null si no se sabe. */
        fun libres(context: Context): Long? = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull()
    }
}
