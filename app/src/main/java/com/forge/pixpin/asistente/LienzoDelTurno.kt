package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.ia.Edicion
import com.forge.pixpin.motor.EditaElLienzo
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.LienzoAlFrente
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.SceneFile
import com.forge.pixpin.motor.newImageElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **El lienzo destino de un turno del asistente** (PC `edicion::Contexto` + el resumen que se pega al
 * pedido): cuál es —el abierto delante, el de `@lienzo:` o el propio del asistente en el chat—, sus
 * `#n` ([Edicion.Numeracion]) y su marco, que no cambian en todo el turno; lo elegido por el usuario y
 * lo último que tocó el asistente («lo último»).
 *
 * Editar el abierto va por su [EditaElLienzo] (un paso de deshacer, y se guarda y sincroniza como lo
 * hecho a mano); uno cerrado, por [ExcalidrawStore], como lo dibujado.
 */
class LienzoDelTurno(
    private val contexto: Context,
    private val lienzos: () -> List<Mensaje>,
    private val chat: String?
) {
    private class Estado(val ref: String, val numeracion: Edicion.Numeracion, val marco: Pt)

    @Volatile private var estado: Estado? = null
    @Volatile var ultimos: List<String> = emptyList()
        private set
    /** Todo lo que tocó o creó el asistente en este turno (la revisión mira eso, con lo de alrededor). */
    private val delTurno = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private fun tocados(ids: List<String>) { ultimos = ids; delTurno += ids }

    private fun abierto(): EditaElLienzo? = LienzoAlFrente.actual() as? EditaElLienzo

    private class Cargado(val ref: String, val escena: Scene, val elegidos: Set<String>, val esquina: Pt?, val abierto: EditaElLienzo?)

    /** El lienzo de ahora: el abierto (si se puede editar), o el de [destino], o el propio del chat. */
    private fun cargar(destino: String?): Cargado? {
        abierto()?.let { a ->
            val l = runCatching { runBlocking { withContext(Dispatchers.Main) { a.paraEditar() } } }.getOrNull()
            if (l != null) return Cargado(l.id, l.escena, l.elegidos, l.esquina, a)
        }
        val ref = destino?.takeIf { File(ExcalidrawStore.rutaDe(contexto, it)).isFile }
            ?: ChatDelAsistente.lienzoPropio(lienzos(), chat)?.referencia
            ?: return null
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, ref)) ?: return null
        return Cargado(ref, escena, emptySet(), null, null)
    }

    private fun estadoDe(c: Cargado): Estado {
        estado?.takeIf { it.ref == c.ref }?.let { return it }
        // Con el lienzo abierto, la esquina de lo que se ve; si no, la de lo dibujado (PC `marco_de`).
        return Estado(c.ref, Edicion.Numeracion(c.escena.elements), c.esquina ?: Edicion.marcoDe(c.escena.elements)).also { estado = it }
    }

    private fun contextoDe(c: Cargado, e: Estado) = Edicion.Contexto(e.marco, c.elegidos.toList(), ultimos)

    /** **El resumen del lienzo para pegarlo al pedido** (PC `anadir_resumen`), o null si no hay lienzo. */
    fun resumen(destino: String?): String? {
        val c = cargar(destino) ?: return null
        val e = estadoDe(c)
        return Edicion.resumen(c.escena, e.numeracion, contextoDe(c, e))
    }

    /**
     * **La geometría exacta** en el lienzo de ahora, con sus `#n` y su marco: [hacer] recibe la escena,
     * la numeración y el marco, y da lo nuevo. Null si no hay lienzo (quien llama hace uno nuevo).
     */
    fun geometria(destino: String?, hacer: (Scene, Edicion.Numeracion, Pt) -> List<Element>): Pair<String, String>? {
        val c = cargar(destino) ?: return null
        val e = estadoDe(c)
        val nuevos = hacer(c.escena, e.numeracion, e.marco)
        guardar(c, c.escena.copy(elements = c.escena.elements + nuevos))
        val ids = nuevos.map { it.id }
        tocados(ids)
        e.numeracion.ponerAlDia(c.escena.elements + nuevos)
        return ids.joinToString(" ") { "#${e.numeracion.numero(it)}" } to c.ref
    }

    /**
     * **La vista del lienzo** para el modelo (`ver_lienzo` y la revisión): la caja de la mirada, pintada
     * con el mismo motor del lienzo, con la rejilla y los `#n` de este turno.
     */
    fun ver(destino: String?, m: com.forge.pixpin.ia.Ojos.Mirada): com.forge.pixpin.ia.Ojos.Vista {
        val c = cargar(destino) ?: throw com.forge.pixpin.ia.Ojos.Fallo("no hay ningun lienzo que mirar en este chat")
        val e = estadoDe(c)
        e.numeracion.ponerAlDia(c.escena.elements)
        val ctx = contextoDe(c, e).let { if (m.zona == com.forge.pixpin.ia.Ojos.Zona.Turno) it.copy(ultimos = delTurno.toList()) else it }
        val caja = com.forge.pixpin.ia.Ojos.cajaDe(c.escena, m, ctx, e.numeracion)
        return PintorDeOjos.pintar(c.escena, caja, e.marco, e.numeracion, m.marcas)
    }

    /** **Una edición** en el lienzo de ahora; devuelve el «hecho» y el lienzo tocado. */
    fun editar(destino: String?, pedido: Edicion.Pedido, anfitrion: (String) -> Edicion.Anfitrion): Pair<String, String> {
        val c = cargar(destino) ?: throw Edicion.Fallo("no hay ningun lienzo que editar: dibuja primero en este chat, nombralo con @lienzo: o abrelo")
        val e = estadoDe(c)
        val (nueva, hecho) = Edicion.aplicar(c.escena, pedido, contextoDe(c, e), e.numeracion, anfitrion(c.ref))
        if (nueva !== c.escena) guardar(c, nueva)
        if (pedido.herramienta == Edicion.SELECCIONAR) ultimos = hecho.ids else tocados(hecho.ids)
        return hecho.texto to c.ref
    }

    private fun guardar(c: Cargado, nueva: Scene) {
        if (c.abierto != null) {
            val ok = runCatching { runBlocking { withContext(Dispatchers.Main) { c.abierto.ponerEscena(nueva.elements, nueva.files) } } }.getOrDefault(false)
            if (!ok) throw Edicion.Fallo("el lienzo abierto no se dejo editar (¿solo se mira?)")
        } else {
            ExcalidrawStore.guardar(contexto, c.ref, nueva) ?: throw Edicion.Fallo("no se pudo guardar el lienzo")
        }
    }

    /** **El «hecho» de lo dibujado** con sus `#n` (PC `resumen_de_lo_anadido`), si fue al lienzo de este turno. */
    fun anadidos(ref: String, ids: List<String>): String? {
        val c = cargar(ref)?.takeIf { it.ref == ref } ?: return null
        val e = estadoDe(c)
        tocados(ids)
        return Edicion.resumenDeLoAnadido(c.escena, ids, e.numeracion, contextoDe(c, e))
    }

    /** Pegar fotos del chat: guarda el mapa en el almacén de imágenes y da el elemento en (0, 0). */
    class FotosDelChat(private val contexto: Context, private val fotos: () -> List<Pair<String, File>>) : Edicion.Anfitrion {
        override fun imagenNueva(escena: Scene, fuente: String): Pair<Scene, Element> {
            val todas = fotos()
            if (Acciones.normal(fuente) == "portapapeles") throw Edicion.Fallo("en el telefono el asistente no lee el portapapeles: adjunta la imagen al chat")
            val elegida = if (fuente.isBlank()) todas.lastOrNull()
            else todas.lastOrNull { (n, f) -> Acciones.normal(n).contains(Acciones.normal(fuente)) || Acciones.normal(f.name).contains(Acciones.normal(fuente)) } ?: todas.lastOrNull()
            val (_, archivo) = elegida ?: throw Edicion.Fallo("no hay ninguna imagen adjunta en este chat que pegar")
            val mime = if (archivo.name.lowercase().endsWith(".png")) "image/png" else "image/jpeg"
            val file: SceneFile = ExcalidrawStore.guardarImagen(contexto, archivo, mime) ?: throw Edicion.Fallo("no se pudo guardar la imagen")
            val (w, h) = tamano(archivo) ?: throw Edicion.Fallo("no se pudo leer la imagen")
            return escena.copy(files = escena.files + (file.id to file)) to newImageElement(file.id, 0.0, 0.0, w.toDouble(), h.toDouble(), escena.style)
        }

        override fun tamanoDe(fileId: String): Pair<Int, Int>? = null

        private fun tamano(f: File): Pair<Int, Int>? {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    }

    private object Acciones {
        fun normal(s: String) = com.forge.pixpin.ia.Acciones.normalizar(s.trim())
    }
}
