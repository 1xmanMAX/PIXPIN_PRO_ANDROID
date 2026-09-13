package com.forge.pixpin.sincro

import android.content.Context
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.motor.PaquetePixpin
import com.forge.pixpin.motor.Proyecto
import java.io.File
import java.util.UUID

/**
 * **Dónde va lo que llega por un envío.**
 *
 * Los archivos, a la conversación general, como si se hubieran compartido desde otra aplicación.
 * Los proyectos, a la lista de proyectos. Y **quien envía manda**: si lo que llega ya estaba de un
 * envío anterior —misma [Envio.Elemento.identidad]—, se sustituye por lo nuevo en vez de duplicarse.
 */
object Recepcion {

    fun identidadDe(p: Proyecto): String = "proyecto:" + (p.origen ?: p.id)

    /** Lo que ya hay con esa identidad, por su nombre, para avisar antes de aceptar. */
    fun queSustituye(context: Context, e: Envio.Elemento): String? = when (e.tipo) {
        Envio.PROYECTO -> proyectoCon(context, e.identidad)?.nombre
        Envio.LIENZO -> e.proyecto?.let { proyectoCon(context, it) }?.let { p ->
            val suya = partesDelLienzo(e.identidad)?.second
            p.hojas.firstOrNull { (it.origen ?: it.id) == suya }?.let { h -> "${h.nombre.ifBlank { "lienzo" }} de «${p.nombre}»" }
        }
        else -> MensajesStore(context).leer().firstOrNull { it.origen == e.identidad && it.ruta != null && File(it.ruta).exists() }
            ?.let { it.nombre.ifBlank { File(it.ruta!!).name } }
    }

    /** Adónde va a caer un lienzo suelto: a qué proyecto que ya se tiene, o a uno nuevo con su nombre. */
    fun adonde(context: Context, e: Envio.Elemento): String? {
        if (e.tipo != Envio.LIENZO) return null
        val p = e.proyecto?.let { proyectoCon(context, it) }
        return if (p != null) "Se añade a tu proyecto «${p.nombre}»" else "Se crea el proyecto «${e.proyectoNombre ?: "Proyecto"}» con este lienzo"
    }

    /** `lienzo:<proyecto>:<hoja>` → (proyecto, hoja). */
    private fun partesDelLienzo(identidad: String): Pair<String, String>? {
        val p = identidad.split(':', limit = 3)
        return if (p.size == 3 && p[0] == "lienzo") p[1] to p[2] else null
    }

    private fun proyectoCon(context: Context, identidad: String): Proyecto? {
        val app = context.applicationContext as? PixPinApp ?: return null
        val suya = identidad.removePrefix("proyecto:")
        return app.proyectos.proyectos.value.firstOrNull { (it.origen ?: it.id) == suya || it.id == suya }
    }

    data class Guardado(val texto: String, val proyecto: String? = null, val sustituido: Boolean = false)

    fun guardar(context: Context, e: Envio.Elemento, archivo: File, oferta: Envio.Oferta? = null): Guardado? = runCatching {
        when (e.tipo) {
            Envio.PROYECTO -> guardarProyecto(context, e, archivo)
            Envio.LIENZO -> guardarLienzo(context, e, archivo, oferta)
            else -> guardarArchivo(context, e, archivo, oferta)
        }
    }.onFailure { android.util.Log.e("PixPinRecibir", "guardar ${e.tipo}", it) }.getOrNull()

    private fun deQuien(oferta: Envio.Oferta?): String? =
        oferta?.let { if (it.deCodigo.isBlank()) it.de else "${it.de} · ${it.deCodigo}" }

    /**
     * **Un lienzo solo, a su proyecto.** Si ya se tiene ese proyecto (misma identidad), el lienzo
     * se añade —o sustituye al mismo lienzo si ya llegó antes—; si no, se crea el proyecto con el
     * mismo nombre y solo ese lienzo, y el siguiente lienzo de ese proyecto caerá en él. En el chat
     * del proyecto aparece con la hora a la que se creó en el otro aparato y de quién vino.
     */
    private fun guardarLienzo(context: Context, e: Envio.Elemento, archivo: File, oferta: Envio.Oferta?): Guardado? {
        val app = context.applicationContext as? PixPinApp ?: return null
        val (suyaP, suyaH) = partesDelLienzo(e.identidad) ?: return null
        val contenido = PaquetePixpin.abrir(archivo) ?: return null
        val ahora = System.currentTimeMillis()
        val importado = PaquetePixpin.importar(
            context, contenido, ahora, File(context.filesDir, "proyectos"),
            guardarCroquis = { id, json -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.guardarJson(context, id, json) }
        ) ?: return null
        archivo.delete()
        val llegada = importado.hojas.firstOrNull() ?: return null
        val antes = proyectoCon(context, "proyecto:$suyaP")
        val base = antes ?: importado.copy(
            nombre = e.proyectoNombre ?: importado.nombre, origen = suyaP, hojas = emptyList(),
            pdfOrigen = null, pdfLimpio = null, croquis = emptyList()
        )
        val repetida = base.hojas.firstOrNull { (it.origen ?: it.id) == suyaH }
        // Si ya estaba, lo nuevo se escribe en su mismo dibujo: el chat lo ve sin tocarlo. Ver [enSuSitio].
        val hoja = (if (repetida != null) enSuSitio(context, llegada, repetida) else llegada)
            .copy(id = repetida?.id ?: llegada.id, origen = suyaH, deMensaje = repetida?.deMensaje)
        var nuevo = base.copy(
            hojas = if (repetida != null) base.hojas.map { if (it.id == repetida.id) hoja else it } else base.hojas + hoja,
            tocado = ahora
        )
        // Una página de un PDF necesita el PDF: si el proyecto aún no tiene documento, el que vino.
        if (hoja.pagina != null && nuevo.pdfOrigen == null) nuevo = nuevo.copy(pdfOrigen = importado.pdfOrigen, pdfLimpio = importado.pdfLimpio)

        // **El mensaje del chat, antes de guardar el proyecto**: así el aviso automático de «hoja
        // nueva» lo encuentra hecho y no lo repite, y lleva la hora de origen y de quién vino.
        val almacen = MensajesStore(context)
        val de = deQuien(oferta)
        val dibujoViejo = repetida?.dibujo
        val existente = dibujoViejo?.let { d -> almacen.leer().firstOrNull { it.proyecto == nuevo.id && it.referencia == d } }
        if (existente != null && hoja.dibujo != null) {
            almacen.actualizar(existente.id) { it.copy(referencia = hoja.dibujo, recibidoDe = de, origen = e.identidad) }
        } else if (hoja.dibujo != null) {
            almacen.anadir(
                Mensaje(
                    id = UUID.randomUUID().toString(),
                    cuando = e.creado.takeIf { it > 0 } ?: ahora,
                    clase = Clase.DIBUJO,
                    proyecto = nuevo.id,
                    nombre = hoja.nombre.ifBlank { e.nombre },
                    referencia = hoja.dibujo,
                    unido = true,
                    recibidoDe = de,
                    origen = e.identidad
                )
            )
        }
        app.proyectos.guardar(nuevo)
        val texto = when {
            repetida != null -> "«${e.nombre}» puesto al día en «${nuevo.nombre}»"
            antes != null -> "«${e.nombre}» añadido a «${nuevo.nombre}»"
            else -> "Proyecto «${nuevo.nombre}» creado con «${e.nombre}»"
        }
        return Guardado(texto, proyecto = nuevo.id, sustituido = repetida != null)
    }

    private fun guardarArchivo(context: Context, e: Envio.Elemento, archivo: File, oferta: Envio.Oferta?): Guardado? {
        val almacen = MensajesStore(context)
        val antes = almacen.leer().firstOrNull { it.origen == e.identidad && it.ruta != null && File(it.ruta).exists() }
        if (antes != null) {
            archivo.copyTo(File(antes.ruta!!), overwrite = true)
            almacen.actualizar(antes.id) { it.copy(bytes = archivo.length(), recibidoDe = deQuien(oferta)) }
            archivo.delete()
            return Guardado("«${e.nombre}» sustituido en la conversación general", sustituido = true)
        }
        val extension = e.mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val ruta = almacen.copiarAdjunto(archivo, e.nombre, extension) ?: return null
        val esImagen = e.mime?.startsWith("image/") == true
        val esAudio = e.mime?.startsWith("audio/") == true
        almacen.anadir(
            Mensaje(
                id = UUID.randomUUID().toString(),
                cuando = System.currentTimeMillis(),
                clase = if (esImagen) Clase.IMAGEN else if (esAudio) Clase.VOZ else Clase.ARCHIVO,
                ruta = ruta,
                nombre = e.nombre,
                bytes = archivo.length(),
                duracionMs = if (esAudio) com.forge.pixpin.pin.Voz.duracion(ruta) else 0,
                origen = e.identidad,
                recibidoDe = deQuien(oferta)
            )
        )
        archivo.delete()
        return Guardado("«${e.nombre}» en la conversación general")
    }

    /**
     * Un proyecto: se abre el `.pixpin` y, si ya había uno con esa identidad, **ocupa su sitio**
     * (mismo id, así el chat del proyecto y los accesos directos siguen valiendo). Del chat se
     * quitan los mensajes que señalaban lienzos, tablas o notas que ya no están; los nuevos los
     * cuenta el propio proyecto al guardarse.
     */
    private fun guardarProyecto(context: Context, e: Envio.Elemento, archivo: File): Guardado? {
        val app = context.applicationContext as? PixPinApp ?: return null
        val contenido = PaquetePixpin.abrir(archivo) ?: return null
        val ahora = System.currentTimeMillis()
        val importado = PaquetePixpin.importar(
            context, contenido, ahora, File(context.filesDir, "proyectos"),
            guardarCroquis = { id, json -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.guardarJson(context, id, json) }
        ) ?: return null
        archivo.delete()
        val suya = e.identidad.removePrefix("proyecto:")
        val antes = proyectoCon(context, e.identidad)
        if (antes == null) {
            val nuevo = importado.copy(origen = suya, tocado = ahora)
            app.proyectos.guardar(nuevo)
            return Guardado("Proyecto «${nuevo.nombre}» añadido", proyecto = nuevo.id)
        }
        // **El chat manda**: lo que ya estaba se pone al día **en su sitio** —el mismo dibujo, la
        // misma tabla— y así los mensajes del chat, que señalan a esos, enseñan lo nuevo. Con ids
        // nuevos, proyectos se veía al día y el chat seguía con lo viejo (usuario, 13-sep-2026).
        val porRaiz = antes.hojas.associateBy { raiz(it.id) }
        val hojas = importado.hojas.map { h -> enSuSitio(context, h, porRaiz[raiz(h.id)]) }
        val nuevo = importado.copy(
            id = antes.id,
            origen = if (antes.id == suya) antes.origen else suya,
            archivado = antes.archivado,
            tocado = ahora,
            hojas = hojas
        )
        quitarDelChatLoQueYaNoEsta(context, nuevo)
        app.proyectos.guardar(nuevo)
        return Guardado("Proyecto «${nuevo.nombre}» puesto al día con la versión recibida", proyecto = nuevo.id, sustituido = true)
    }

    /** Un id sin los sufijos de hora que les pone cada importación (`-1726243200000`). */
    private fun raiz(id: String): String = id.replace(SUFIJOS_DE_IMPORTAR, "")

    private val SUFIJOS_DE_IMPORTAR = Regex("(-\\d{13})+$")

    /**
     * La hoja que llega, **escrita encima de la que ya había**: su lienzo en el dibujo de la vieja y
     * su tabla en la tabla vieja, y la hoja con los ids de antes. Sin vieja, tal cual.
     */
    private fun enSuSitio(context: Context, llega: com.forge.pixpin.motor.Hoja, vieja: com.forge.pixpin.motor.Hoja?): com.forge.pixpin.motor.Hoja {
        if (vieja == null) return llega
        var h = llega.copy(id = vieja.id, deMensaje = vieja.deMensaje, padre = vieja.padre ?: llega.padre)
        val dibujoNuevo = llega.dibujo
        val dibujoViejo = vieja.dibujo
        if (dibujoNuevo != null && dibujoViejo != null && dibujoNuevo != dibujoViejo) {
            val ruta = com.forge.pixpin.motor.ExcalidrawStore.rutaDe(context, dibujoNuevo)
            val escena = com.forge.pixpin.motor.ExcalidrawStore.cargar(ruta)
            if (escena != null && com.forge.pixpin.motor.ExcalidrawStore.guardar(context, dibujoViejo, escena) != null) {
                File(ruta).delete()
                h = h.copy(dibujo = dibujoViejo)
            }
        }
        val tablaNueva = llega.tabla
        val tablaVieja = vieja.tabla
        if (tablaNueva != null && tablaVieja != null && tablaNueva != tablaVieja) {
            val almacen = com.forge.pixpin.motor.TablasEnDisco.de(context.filesDir)
            almacen.cargar(tablaNueva)?.let { t ->
                if (almacen.guardar(tablaVieja, t)) { almacen.borrar(tablaNueva); h = h.copy(tabla = tablaVieja) }
            }
        }
        return h
    }

    private fun quitarDelChatLoQueYaNoEsta(context: Context, p: Proyecto) {
        val almacen = MensajesStore(context)
        val lista = almacen.leer()
        val dibujos = p.hojas.mapNotNull { it.dibujo }.toSet()
        val tablas = p.hojas.mapNotNull { it.tabla }.toSet()
        val notas = p.hojas.filter { it.nota != null }.map { it.id }.toSet()
        val croquis = p.croquis.toSet()
        val quedan = lista.filterNot { m ->
            m.proyecto == p.id && when (m.clase) {
                Clase.DIBUJO -> m.referencia != null && m.referencia !in dibujos
                Clase.TABLA -> m.referencia != null && m.referencia !in tablas
                Clase.NOTA -> m.referencia != null && m.referencia !in notas
                Clase.CROQUIS -> m.referencia != null && m.referencia !in croquis
                else -> false
            }
        }
        if (quedan.size != lista.size) almacen.reescribir(quedan)
    }
}
