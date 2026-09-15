package com.forge.pixpin.sincro

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import kotlinx.serialization.json.Json
import java.io.File

/**
 * **El chat viaja con lo que se manda.**
 *
 * El chat manda y los proyectos son su galería, así que mandar un proyecto a otra persona sin su
 * chat era mandar la galería sin lo que la origina: al otro lado el proyecto llegaba y su chat
 * salía vacío (usuario, 14-sep-2026). Ahora cada envío lleva **los mensajes de lo que se manda**
 * —todo el chat del proyecto, o solo los del lienzo si se manda un lienzo— con su hora real de
 * creación, su número y su letra, y los archivos que tengan (fotos, audios, PDF).
 *
 * Va dentro del mismo `.pixpin`, en `chat/`: un JSON por línea y los adjuntos aparte. Los ids de
 * los mensajes **no cambian**, y por eso volver a mandar lo mismo pone al día esos mensajes en vez
 * de repetirlos, y las hojas siguen sabiendo de qué mensaje salieron ([Hoja.deMensaje]).
 */
object ChatQueViaja {

    /** Lo que va en el paquete: los mensajes en JSON por líneas y sus archivos por clave. */
    class Paquete(val texto: String, val adjuntos: Map<String, File>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** La ruta de un mensaje que es el PDF del propio proyecto: ya viaja como `documento.pdf`. */
    private const val EL_DOCUMENTO = "documento:"
    private const val ADJUNTO = "adjunto:"

    /** Los mensajes del chat de [p]. */
    fun delProyecto(context: Context, p: Proyecto): List<Mensaje> =
        MensajesStore(context).leer().filter { it.proyecto == p.id }

    /** Los mensajes de una sola hoja: el suyo, y el PDF si la hoja es una página. */
    fun deLaHoja(context: Context, p: Proyecto, h: Hoja): List<Mensaje> =
        MensajesStore(context).leer().filter { m ->
            m.proyecto == p.id && (
                m.id == h.deMensaje ||
                    (h.dibujo != null && m.referencia == h.dibujo) ||
                    (h.tabla != null && m.referencia == h.tabla) ||
                    (h.croquis != null && m.referencia == h.croquis) ||
                    (h.nota != null && m.clase == Clase.NOTA && m.referencia == h.id) ||
                    (h.pagina != null && m.clase == Clase.ARCHIVO && m.ruta != null && (m.ruta == p.pdfLimpio || m.ruta == p.pdfOrigen))
                )
        }

    /** Prepara [mensajes] de [p] para el paquete. Trabajo de disco: solo lee. */
    fun preparar(p: Proyecto, mensajes: List<Mensaje>): Paquete? {
        if (mensajes.isEmpty()) return null
        val adjuntos = LinkedHashMap<String, File>()
        val lineas = mensajes.sortedBy { it.cuando }.map { m ->
            val ruta = m.ruta
            val viaja = when {
                ruta == null -> m
                ruta == p.pdfLimpio || ruta == p.pdfOrigen -> m.copy(ruta = EL_DOCUMENTO)
                File(ruta).isFile -> {
                    val clave = "${m.id}-${Envio.nombreSano(File(ruta).name)}"
                    adjuntos[clave] = File(ruta)
                    m.copy(ruta = ADJUNTO + clave)
                }
                else -> m.copy(ruta = null)
            }
            json.encodeToString(Mensaje.serializer(), viaja)
        }
        return Paquete(lineas.joinToString("\n"), adjuntos)
    }

    /**
     * **Los ids de allí, en los de aquí**: lo que un mensaje señala (su dibujo, su tabla, su nota,
     * su croquis, su proyecto) cambió de nombre al importarse. Se sacan comparando las hojas que
     * vinieron con las que quedaron, que van en el mismo orden.
     */
    fun mapaDeIds(vinieron: List<Hoja>, quedaron: List<Hoja>, proyectoAlli: String, proyectoAqui: String): Map<String, String> {
        val mapa = HashMap<String, String>()
        mapa[proyectoAlli] = proyectoAqui
        for ((a, b) in vinieron.zip(quedaron)) {
            mapa[a.id] = b.id
            if (a.dibujo != null && b.dibujo != null) mapa[a.dibujo!!] = b.dibujo!!
            if (a.tabla != null && b.tabla != null) mapa[a.tabla!!] = b.tabla!!
            if (a.croquis != null && b.croquis != null) mapa[a.croquis!!] = b.croquis!!
        }
        return mapa
    }

    /**
     * Pone en el chat de aquí los mensajes que vinieron. Los que ya estaban (mismo id) se ponen al
     * día; los nuevos se añaden **con su hora, su número y su letra de origen**. Devuelve cuántos.
     */
    fun recibir(
        context: Context,
        texto: String?,
        adjuntos: Map<String, ByteArray>,
        mapa: Map<String, String>,
        proyecto: Proyecto,
        de: String?
    ): Int {
        if (texto.isNullOrBlank()) return 0
        val almacen = MensajesStore(context)
        var cuantos = 0
        // **Quien envía manda**: un mensaje de aquí que es lo mismo que uno que llega (el mismo
        // dibujo, la misma tabla, el mismo PDF del proyecto) pero con otro id —el que se inventó
        // al recibirlo con una versión anterior— se quita, para no tenerlo dos veces.
        val llegan = texto.lines().mapNotNull { l -> l.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(Mensaje.serializer(), it) }.getOrNull() } }
        val idsQueLlegan = llegan.map { it.id }.toSet()
        val referencias = llegan.mapNotNull { m -> m.referencia?.let { mapa[it] ?: it } }.toSet()
        val traeElDocumento = llegan.any { it.ruta == EL_DOCUMENTO }
        val antes = almacen.leer()
        val sobran = antes.filter { m ->
            m.proyecto == proyecto.id && m.id !in idsQueLlegan && (
                (m.referencia != null && m.referencia in referencias && m.clase != Clase.NOTA) ||
                    (traeElDocumento && m.clase == Clase.ARCHIVO && m.ruta != null && (m.ruta == proyecto.pdfLimpio || m.ruta == proyecto.pdfOrigen))
                )
        }.map { it.id }.toSet()
        if (sobran.isNotEmpty()) almacen.reescribir(antes.filterNot { it.id in sobran })
        val yaEstan = almacen.leer().associateBy { it.id }
        for (linea in texto.lines()) {
            if (linea.isBlank()) continue
            val llega = runCatching { json.decodeFromString(Mensaje.serializer(), linea) }.getOrNull() ?: continue
            val ruta = llega.ruta
            val rutaAqui = when {
                ruta == null -> null
                ruta == EL_DOCUMENTO -> proyecto.pdfLimpio ?: proyecto.pdfOrigen
                ruta.startsWith(ADJUNTO) -> {
                    val clave = ruta.removePrefix(ADJUNTO)
                    val bytes = adjuntos[clave]
                    if (bytes == null) null else {
                        // Si ya estaba y su archivo sigue, se sobrescribe en su sitio.
                        val vieja = yaEstan[llega.id]?.ruta?.let { File(it) }?.takeIf { it.isFile && it.path.contains("/guardados/") }
                        if (vieja != null) { vieja.writeBytes(bytes); vieja.absolutePath }
                        else {
                            val temporal = File(context.cacheDir, "chat-que-llega-$clave").apply { writeBytes(bytes) }
                            almacen.copiarAdjunto(
                                temporal, llega.nombre.ifBlank { clave.substringAfter('-') },
                                File(clave).extension.ifBlank { null }, aligerar = false
                            )
                                .also { temporal.delete() }
                        }
                    }
                }
                else -> null
            }
            fun aqui(id: String?) = id?.let { mapa[it] ?: it }
            val puesto = llega.copy(
                ruta = rutaAqui,
                proyecto = aqui(llega.proyecto) ?: proyecto.id,
                referencia = aqui(llega.referencia),
                hojaDelTexto = aqui(llega.hojaDelTexto),
                vieneDe = llega.vieneDe?.let { v ->
                    v.copy(dibujo = aqui(v.dibujo), proyecto = aqui(v.proyecto), pdf = if (v.pdf != null) proyecto.pdfLimpio ?: proyecto.pdfOrigen else null)
                },
                // Lo que ya forma parte del proyecto no se vuelve a meter como hoja.
                unido = true,
                recibidoDe = llega.recibidoDe ?: de,
                recuerdaEn = null
            )
            if (yaEstan.containsKey(puesto.id)) almacen.actualizar(puesto.id) { puesto.copy(fijado = it.fijado) }
            else almacen.anadir(puesto)
            cuantos++
        }
        return cuantos
    }
}
