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
 * Los proyectos, a la lista de proyectos.
 *
 * **Solo se pone al día lo que coincide en los tres códigos** ([Codigos], 15-sep-2026) y **si quien
 * recibe lo elige**; si no, o si elige «crear como nuevo», entra aparte. Lo que manda una versión
 * anterior de PixPin, sin códigos, se reconoce como antes, por su [Envio.Elemento.identidad].
 */
object Recepcion {

    fun identidadDe(p: Proyecto): String = "proyecto:" + (p.origen ?: p.id)

    /**
     * **Lo que aquí es lo mismo que llega**, por su nombre, para ofrecer ponerlo al día. Con códigos,
     * solo si coinciden los tres; sin ellos (una versión anterior), por la identidad de antes.
     */
    fun queSustituye(context: Context, e: Envio.Elemento): String? = when (e.tipo) {
        Envio.PROYECTO -> (if (e.uid != null) proyectoConCodigos(context, e) else proyectoCon(context, e.identidad))?.nombre
        Envio.LIENZO -> if (e.uid != null) hojaConCodigos(context, e)?.let { (p, h) -> "${h.nombre.ifBlank { "lienzo" }} de «${p.nombre}»" }
        else e.proyecto?.let { proyectoCon(context, it) }?.let { p ->
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

    /** El mensaje que registra una hoja en el chat de su proyecto. */
    fun mensajeDeLaHoja(mensajes: List<Mensaje>, p: Proyecto, h: com.forge.pixpin.motor.Hoja): Mensaje? =
        mensajes.firstOrNull { it.proyecto == p.id && h.deMensaje != null && it.id == h.deMensaje }
            ?: mensajes.firstOrNull { m ->
                m.proyecto == p.id && m.referencia != null && (
                    m.referencia == h.dibujo || m.referencia == h.tabla || m.referencia == h.croquis ||
                        (h.nota != null && m.clase == Clase.NOTA && m.referencia == h.id)
                    )
            }

    /**
     * **La hoja de aquí con los tres códigos de la que llega**: el único de la hoja, y el de chat y la
     * fecha de su mensaje. Una página de un PDF no tiene mensaje propio: entonces vale el código único
     * si allí tampoco lo tenía.
     */
    internal fun hojaConCodigos(context: Context, e: Envio.Elemento): Pair<Proyecto, com.forge.pixpin.motor.Hoja>? {
        val app = context.applicationContext as? PixPinApp ?: return null
        val uid = e.uid ?: return null
        val mensajes by lazy { MensajesStore(context).leer() }
        for (p in app.proyectos.proyectos.value) for (h in p.hojas) {
            if (Codigos.unico(h) != uid) continue
            val m = mensajeDeLaHoja(mensajes, p, h)
            val coincide = if (m == null) e.codigoDeChat == null && e.creado == 0L
            else Codigos.deChat(m) == e.codigoDeChat && m.cuando == e.creado
            if (coincide) return p to h
        }
        return null
    }

    /** El proyecto de aquí con los tres códigos del que llega. */
    internal fun proyectoConCodigos(context: Context, e: Envio.Elemento): Proyecto? {
        val app = context.applicationContext as? PixPinApp ?: return null
        val uid = e.uid ?: return null
        return app.proyectos.proyectos.value.firstOrNull { Codigos.unico(it) == uid && it.creado == e.creado && it.aparato == e.aparato }
    }

    /** Qué ids ya se usan aquí: lo que no, se recibe con el mismo id que tenía allí. */
    private fun idEnUso(context: Context): (String) -> Boolean {
        val app = context.applicationContext as? PixPinApp
        val ids = HashSet<String>()
        app?.proyectos?.proyectos?.value?.forEach { p -> ids += p.id; p.hojas.forEach { ids += it.id } }
        MensajesStore(context).leer().forEach { m -> m.proyecto?.let { ids += it } }
        return { it in ids }
    }

    /**
     * **Crear como nuevo: tres códigos nuevos** para todo lo que llega. Los mensajes del paquete salen
     * con id nuevo, sin número ni aparato (los pone quien los guarda) y con la hora de ahora; las
     * hojas, con código único nuevo y señalando a sus mensajes nuevos.
     */
    private fun renovado(c: PaquetePixpin.Contenido, ahora: Long): PaquetePixpin.Contenido {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val ids = HashMap<String, String>()
        val lineas = c.chat?.lines()?.filter { it.isNotBlank() }?.mapIndexedNotNull { i, l ->
            val m = runCatching { json.decodeFromString(Mensaje.serializer(), l) }.getOrNull() ?: return@mapIndexedNotNull null
            val nuevo = UUID.randomUUID().toString()
            ids[m.id] = nuevo
            json.encodeToString(Mensaje.serializer(), Codigos.renovar(m).copy(id = nuevo, cuando = ahora + i, uid = null))
        }
        val p = c.proyecto.copy(
            uid = Codigos.nuevo(), creado = ahora, aparato = null, origen = null,
            hojas = c.proyecto.hojas.map { h -> h.copy(uid = Codigos.nuevo(), origen = null, deMensaje = h.deMensaje?.let { ids[it] }) }
        )
        // Los adjuntos van por el id viejo del mensaje: se les cambia la clave igual.
        val adjuntos = c.adjuntosDelChat.mapKeys { (clave, _) ->
            ids.entries.firstOrNull { clave.startsWith(it.key + "-") }?.let { it.value + clave.removePrefix(it.key) } ?: clave
        }
        val chat = lineas?.joinToString("\n")?.let { texto ->
            ids.entries.fold(texto) { t, (viejo, nuevo) -> t.replace("adjunto:$viejo-", "adjunto:$nuevo-") }
        }
        return PaquetePixpin.Contenido(p, c.lienzos, c.imagenes, c.croquis, c.notas, c.pdf, c.tablas, chat, adjuntos)
    }

    private fun proyectoCon(context: Context, identidad: String): Proyecto? {
        val app = context.applicationContext as? PixPinApp ?: return null
        val suya = identidad.removePrefix("proyecto:")
        return app.proyectos.proyectos.value.firstOrNull { (it.origen ?: it.id) == suya || it.id == suya }
    }

    data class Guardado(val texto: String, val proyecto: String? = null, val sustituido: Boolean = false)

    fun guardar(
        context: Context, e: Envio.Elemento, archivo: File, oferta: Envio.Oferta? = null,
        /** Quien recibe eligió **crear como nuevo** algo que ya tenía: entra con códigos nuevos. */
        comoNuevo: Boolean = false
    ): Guardado? = runCatching {
        when (e.tipo) {
            Envio.PROYECTO -> guardarProyecto(context, e, archivo, oferta, comoNuevo)
            Envio.LIENZO -> guardarLienzo(context, e, archivo, oferta, comoNuevo)
            else -> guardarArchivo(context, e, archivo, oferta)
        }
    }.onFailure { android.util.Log.e("PixPinRecibir", "guardar ${e.tipo}", it) }.getOrNull()
        .also { if (it?.proyecto != null) com.forge.pixpin.guardados.ChatDeLosProyectos.reparar(context) }

    private fun deQuien(oferta: Envio.Oferta?): String? =
        oferta?.let { if (it.deCodigo.isBlank()) it.de else "${it.de} · ${it.deCodigo}" }

    /**
     * **Un lienzo solo, a su proyecto.** Si ya se tiene ese proyecto (misma identidad), el lienzo
     * se añade —o sustituye al mismo lienzo si ya llegó antes—; si no, se crea el proyecto con el
     * mismo nombre y solo ese lienzo, y el siguiente lienzo de ese proyecto caerá en él. En el chat
     * del proyecto aparece con la hora a la que se creó en el otro aparato y de quién vino.
     */
    private fun guardarLienzo(context: Context, e: Envio.Elemento, archivo: File, oferta: Envio.Oferta?, comoNuevo: Boolean): Guardado? {
        val app = context.applicationContext as? PixPinApp ?: return null
        val (suyaP, suyaH) = partesDelLienzo(e.identidad) ?: return null
        val ahora = System.currentTimeMillis()
        val abierto = PaquetePixpin.abrir(archivo) ?: return null
        val contenido = if (comoNuevo) renovado(abierto, ahora) else abierto
        // **La misma hoja aquí, con los tres códigos**: solo esa se pone al día.
        val coincide = if (comoNuevo || e.uid == null) null else hojaConCodigos(context, e)
        val importado = PaquetePixpin.importar(
            context, contenido, ahora, File(context.filesDir, "proyectos"),
            guardarCroquis = { id, json -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.guardarJson(context, id, json) },
            idEnUso = idEnUso(context)
        ) ?: return null
        archivo.delete()
        val llegada = importado.hojas.firstOrNull() ?: return null
        val antes = coincide?.first ?: proyectoCon(context, "proyecto:$suyaP")
        // **Antes de tocar el proyecto que ya se tiene, cómo estaba.** Ver [Copias].
        if (antes != null) Copias(Red.disco(context)).hacer(antes.id, "Antes de recibir «${e.nombre}»${deQuien(oferta)?.let { " de $it" }.orEmpty()}")
        val base = antes ?: importado.copy(
            nombre = e.proyectoNombre ?: importado.nombre, origen = suyaP, hojas = emptyList(),
            pdfOrigen = null, pdfLimpio = null, croquis = emptyList()
        )
        val repetida = when {
            comoNuevo -> null
            coincide != null -> base.hojas.firstOrNull { it.id == coincide.second.id }
            // Sin códigos (lo manda una versión anterior): como antes.
            e.uid == null -> base.hojas.firstOrNull { (it.origen ?: it.id) == suyaH }
            else -> null
        }
        // Si ya estaba, lo nuevo se escribe en su mismo dibujo: el chat lo ve sin tocarlo. Ver [enSuSitio].
        val hoja = (if (repetida != null) enSuSitio(context, llegada, repetida) else llegada)
            .copy(id = repetida?.id ?: llegada.id, origen = if (comoNuevo) null else suyaH, deMensaje = if (contenido.chat != null) llegada.deMensaje ?: repetida?.deMensaje else repetida?.deMensaje)
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
        // **Si vino con su chat**, el mensaje es el de verdad —con su hora, su seña y su archivo— y
        // no uno inventado aquí. Ver [ChatQueViaja].
        if (contenido.chat != null) {
            val mapa = ChatQueViaja.mapaDeIds(contenido.proyecto.hojas, listOf(hoja), contenido.proyecto.id, nuevo.id)
            ChatQueViaja.recibir(context, contenido.chat, contenido.adjuntosDelChat, mapa, nuevo, de)
            app.proyectos.guardar(nuevo)
            val texto = when {
                repetida != null -> "«${e.nombre}» puesto al día en «${nuevo.nombre}»"
                antes != null -> "«${e.nombre}» añadido a «${nuevo.nombre}»"
                else -> "Proyecto «${nuevo.nombre}» creado con «${e.nombre}»"
            }
            return Guardado(texto, proyecto = nuevo.id, sustituido = repetida != null)
        }
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
        val ruta = almacen.copiarAdjunto(archivo, e.nombre, extension, aligerar = false) ?: return null
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
    private fun guardarProyecto(context: Context, e: Envio.Elemento, archivo: File, oferta: Envio.Oferta?, comoNuevo: Boolean): Guardado? {
        val app = context.applicationContext as? PixPinApp ?: return null
        val ahora = System.currentTimeMillis()
        val abierto = PaquetePixpin.abrir(archivo) ?: return null
        val contenido = if (comoNuevo) renovado(abierto, ahora) else abierto
        val importado = PaquetePixpin.importar(
            context, contenido, ahora, File(context.filesDir, "proyectos"),
            guardarCroquis = { id, json -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.guardarJson(context, id, json) },
            idEnUso = idEnUso(context)
        ) ?: return null
        archivo.delete()
        val suya = e.identidad.removePrefix("proyecto:")
        // **Solo se pone al día el proyecto con los tres códigos iguales**, y si no se eligió crear uno nuevo.
        val antes = when {
            comoNuevo -> null
            e.uid != null -> proyectoConCodigos(context, e)
            else -> proyectoCon(context, e.identidad)
        }
        if (antes == null) {
            val nuevo = importado.copy(origen = if (comoNuevo) null else suya, tocado = ahora)
            // **El chat primero**, y después el proyecto: así lo que el proyecto trae ya tiene su
            // mensaje y no se inventa otro. Ver [ChatQueViaja].
            chatQueLlega(context, contenido, nuevo, oferta)
            // Recibirlo a propósito es decir que se quiere: si estaba borrado aquí, deja de
            // estarlo, o la siguiente sincronización lo borraría otra vez. Ver [Disco.quitarLapida].
            runCatching { Red.disco(context).quitarLapida(nuevo.id) }
            app.proyectos.guardar(nuevo)
            return Guardado(if (comoNuevo) "Proyecto «${nuevo.nombre}» creado como nuevo" else "Proyecto «${nuevo.nombre}» añadido", proyecto = nuevo.id)
        }
        // **Antes de tocar el proyecto que ya se tiene, cómo estaba.** Ver [Copias].
        Copias(Red.disco(context)).hacer(antes.id, "Antes de recibir «${e.nombre}»${deQuien(oferta)?.let { " de $it" }.orEmpty()}")
        // **El chat manda**: lo que ya estaba se pone al día **en su sitio** —el mismo dibujo, la
        // misma tabla— y así los mensajes del chat, que señalan a esos, enseñan lo nuevo. Con ids
        // nuevos, proyectos se veía al día y el chat seguía con lo viejo (usuario, 13-sep-2026).
        val parejas = parejas(antes.hojas, importado.hojas, "-$ahora")
        val llegadas = importado.hojas.map { h -> enSuSitio(context, h, parejas[h.id]) }
        enlazarLasZonas(context, importado.hojas, llegadas)
        // **Recibir no quita lienzos** (15-sep-2026): lo que solo tiene quien recibe se queda,
        // detrás. Antes el proyecto recibido sustituía al de aquí entero y el usuario perdió los
        // lienzos que la tableta no tenía. Quitar un lienzo se hace a mano, no llega por un envío.
        val usadas = parejas.values.mapTo(HashSet()) { it.id }
        val hojas = llegadas + antes.hojas.filter { it.id !in usadas }
        val nuevo = importado.copy(
            id = antes.id,
            origen = if (antes.id == suya) antes.origen else suya,
            archivado = antes.archivado,
            tocado = ahora,
            hojas = hojas
        )
        quitarDelChatLoQueYaNoEsta(context, nuevo)
        chatQueLlega(context, contenido, nuevo, oferta)
        runCatching { Red.disco(context).quitarLapida(nuevo.id) }
        app.proyectos.guardar(nuevo)
        return Guardado("Proyecto «${nuevo.nombre}» puesto al día con la versión recibida", proyecto = nuevo.id, sustituido = true)
    }

    /**
     * Los mensajes que vinieron en el paquete, al chat de [p]. Si el paquete no traía chat (lo
     * mandó una versión anterior), cada hoja sin mensaje recibe uno, para que el chat no quede vacío.
     */
    private fun chatQueLlega(context: Context, contenido: PaquetePixpin.Contenido, p: Proyecto, oferta: Envio.Oferta?) {
        val mapa = ChatQueViaja.mapaDeIds(contenido.proyecto.hojas, p.hojas, contenido.proyecto.id, p.id)
        val puestos = ChatQueViaja.recibir(context, contenido.chat, contenido.adjuntosDelChat, mapa, p, deQuien(oferta))
        if (puestos > 0 || contenido.chat != null) return
        val almacen = MensajesStore(context)
        val suyos = almacen.leer().filter { it.proyecto == p.id }
        val ahora = System.currentTimeMillis()
        for ((i, h) in p.hojas.withIndex()) {
            val dibujo = h.dibujo ?: continue
            if (h.pagina != null || suyos.any { it.referencia == dibujo || it.id == h.deMensaje }) continue
            almacen.anadir(
                Mensaje(
                    id = UUID.randomUUID().toString(), cuando = ahora + i, clase = Clase.DIBUJO,
                    proyecto = p.id, nombre = h.nombre, referencia = dibujo, unido = true, recibidoDe = deQuien(oferta)
                )
            )
        }
    }

    /**
     * Las marcas de zona de las páginas señalan a su sublienzo por el id del dibujo. Si al ponerse
     * en su sitio un sublienzo recuperó su id de antes, las marcas que apuntaban al id recién
     * importado se cambian al de antes. Ver [enSuSitio].
     */
    private fun enlazarLasZonas(context: Context, vinieron: List<com.forge.pixpin.motor.Hoja>, quedaron: List<com.forge.pixpin.motor.Hoja>) {
        val cambio = vinieron.zip(quedaron).mapNotNull { (a, b) ->
            val x = a.dibujo; val y = b.dibujo
            if (x != null && y != null && x != y) x to y else null
        }.toMap()
        if (cambio.isEmpty()) return
        for (h in quedaron) {
            val dibujo = h.dibujo ?: continue
            val ruta = com.forge.pixpin.motor.ExcalidrawStore.rutaDe(context, dibujo)
            val escena = com.forge.pixpin.motor.ExcalidrawStore.cargar(ruta) ?: continue
            if (escena.elements.none { it.enlace in cambio }) continue
            val puesta = escena.copy(elements = escena.elements.map { el -> el.enlace?.let { cambio[it] }?.let { el.copy(enlace = it) } ?: el })
            com.forge.pixpin.motor.ExcalidrawStore.guardar(context, dibujo, puesta)
        }
    }

    /**
     * **Qué hoja de aquí es cada una de las que llegan.** Cada importación le pone a los ids la hora
     * detrás (`-1726243200000`), así que la misma hoja, ida y vuelta, lleva colas distintas en cada
     * aparato; se reconocen quitando colas.
     *
     * Pero **no todas**: un lienzo creado aquí se llama `hoja-<hora>`, y quitándole también esa
     * hora todos los lienzos se llamaban `hoja` y se tomaban por el mismo. Eso es lo que apiló tres
     * lienzos en uno el 15-sep-2026. Ahora una cola se quita solo si lo que queda sigue llevando una
     * hora, y cada hoja de aquí se empareja con una sola de las que llegan.
     *
     * [sufijo] es la cola de esta importación. Devuelve id de la que llega → hoja de aquí.
     */
    internal fun parejas(aqui: List<com.forge.pixpin.motor.Hoja>, llegan: List<com.forge.pixpin.motor.Hoja>, sufijo: String): Map<String, com.forge.pixpin.motor.Hoja> {
        val libres = aqui.toMutableList()
        val r = HashMap<String, com.forge.pixpin.motor.Hoja>()
        for (h in llegan) {
            // **Por su código único**, si lo trae: es lo que dice que es la misma hoja.
            if (h.uid != null) {
                libres.firstOrNull { Codigos.unico(it) == h.uid }?.let { libres.remove(it); r[h.id] = it }
                continue
            }
            val suyos = nombresDe(h.id.removeSuffix(sufijo)) + listOfNotNull(h.origen)
            val cual = libres.firstOrNull { v -> v.id == h.id.removeSuffix(sufijo) } ?: libres.firstOrNull { v ->
                val mios = nombresDe(v.id) + listOfNotNull(v.origen)
                mios.any { it in suyos }
            } ?: continue
            libres.remove(cual)
            r[h.id] = cual
        }
        return r
    }

    /** Un id y los que resultan de quitarle colas de hora mientras lo que queda lleve una hora. */
    internal fun nombresDe(id: String): Set<String> {
        val r = linkedSetOf(id)
        var x = id
        while (true) {
            val m = COLA_DE_HORA.find(x) ?: break
            x = x.substring(0, m.range.first)
            if (!HORA.containsMatchIn(x)) break
            r += x
        }
        return r
    }

    private val COLA_DE_HORA = Regex("-\\d{13}$")
    private val HORA = Regex("\\d{13}")

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
