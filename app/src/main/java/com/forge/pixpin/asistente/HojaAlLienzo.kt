package com.forge.pixpin.asistente

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.HojaNativa
import com.forge.pixpin.ia.MensajeIa
import com.forge.pixpin.ia.Motor
import com.forge.pixpin.ia.Parte
import com.forge.pixpin.ia.Peticion
import com.forge.pixpin.ia.Rol
import com.forge.pixpin.ia.Seguir
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.SceneFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **`hoja_a_lienzo` desde el chat del teléfono** (PC `asistente/hoja_a_lienzo.rs`): el trabajo lo hace
 * la biblioteca del PC ([HojaNativa]) y esto es su [HojaNativa.Puente]: leer la foto o la página (con
 * la capa de texto del PDF), el OCR de ML Kit, la nube del asistente, **cada zona al lienzo en cuanto
 * está hecha** (rehacer una zona la sustituye) y pintar lo hecho para que el PC lo compare.
 *
 * El lienzo, como `al_lienzo` del PC: el que el usuario tiene abierto (lo ve llegar zona a zona) o, si
 * no, el del asistente en este chat (o uno nuevo que entra al chat); la hoja va a un sitio libre, a la
 * derecha de lo que hubiera, o junto a la imagen de la que sale.
 */
class HojaAlLienzo(
    private val contexto: Context,
    private val almacen: MensajesStore,
    private val chat: String?,
    private val motor: Motor,
    private val esNube: Boolean,
    private val parar: AtomicBoolean,
    /** El lienzo nombrado con `@lienzo:` o el que el turno usa (`usar_lienzo`), si lo hay. */
    private val destino: String?,
    private val alProgresar: (Int, Int) -> Unit = { _, _ -> }
) : HojaNativa.Puente {

    /** El lienzo donde quedó, para contarlo y para lo siguiente del turno. */
    @Volatile var lienzo: String? = null
        private set

    companion object {
        const val SOLO_NUBE = "Para pasar una hoja al lienzo necesitas Claude o DeepSeek: actívalo en Ajustes → Asistente."
        private val json = Json { ignoreUnknownKeys = true }

        private fun empaquetarImagen(cabeza: String, rgba: ByteArray): ByteArray {
            val c = cabeza.toByteArray()
            return ByteBuffer.allocate(4 + c.size + rgba.size).order(ByteOrder.LITTLE_ENDIAN).putInt(c.size).put(c).put(rgba).array()
        }

        fun rgbaDe(b: Bitmap): ByteArray {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val out = ByteArray(px.size * 4)
            for (i in px.indices) {
                val c = px[i]
                out[i * 4] = (c shr 16).toByte(); out[i * 4 + 1] = (c shr 8).toByte(); out[i * 4 + 2] = c.toByte(); out[i * 4 + 3] = (c ushr 24).toByte()
            }
            return out
        }

        fun bitmapDe(rgba: ByteArray, w: Int, h: Int): Bitmap {
            val px = IntArray(w * h)
            for (i in px.indices) {
                val r = rgba[i * 4].toInt() and 255; val g = rgba[i * 4 + 1].toInt() and 255
                val b = rgba[i * 4 + 2].toInt() and 255; val a = rgba[i * 4 + 3].toInt() and 255
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        /** Las imágenes que manda la biblioteca: `[u32 n]` y por imagen `[u32 largo][id][u32 ancho][u32 alto][RGBA]`. */
        fun desempaquetar(b: ByteArray): List<Triple<String, Int, Pair<Int, ByteArray>>> {
            if (b.size < 4) return emptyList()
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.int
            val v = ArrayList<Triple<String, Int, Pair<Int, ByteArray>>>()
            repeat(n) {
                val l = bb.int; val id = ByteArray(l).also { bb.get(it) }.toString(Charsets.UTF_8)
                val w = bb.int; val h = bb.int
                val px = ByteArray(w * h * 4).also { bb.get(it) }
                v += Triple(id, w, h to px)
            }
            return v
        }

        /** Los elementos de un documento de Excalidraw de la biblioteca. */
        fun elementosDe(documento: String): List<Element> {
            if (documento.isBlank()) return emptyList()
            val o = json.parseToJsonElement(documento).jsonObject
            val els = (o["elements"] as? JsonArray)?.map { it.jsonObject } ?: return emptyList()
            return com.forge.pixpin.ia.Lienzo.aElementos(els)
        }
    }

    // --------------------------------------------------------------------------- la herramienta

    /** **La herramienta**: elige la hoja de los adjuntos del chat (la nombrada o la última) y la pasa. */
    fun hacer(args: JsonObject, foto: File? = null): Result<String> = runCatching {
        if (!esNube) error(SOLO_NUBE)
        if (!HojaNativa.disponible) error("esta versión del teléfono no trae la hoja al lienzo (falta su biblioteca)")
        val adjuntos = buildJsonArray {
            almacen.leer().filter { m -> !m.enBuzon && m.proyecto == chat && m.ruta != null && File(m.ruta).isFile }
                .sortedBy { it.cuando }
                .forEach { m -> add(buildJsonObject { put("nombre", m.nombre.ifBlank { File(m.ruta!!).name }); put("ruta", m.ruta) }) }
            // La foto de la conversación, la última (la que ve el modelo).
            foto?.takeIf { it.isFile }?.let { f -> add(buildJsonObject { put("nombre", f.name); put("ruta", f.path) }) }
        }
        HojaNativa.resultado(HojaNativa.hacer(args.toString(), adjuntos.toString(), System.currentTimeMillis(), this)).getOrThrow()
    }

    // ------------------------------------------------------------------------------- leer la hoja

    override fun leer(ruta: String, pagina: Int, ancho: Int): ByteArray = try {
        val f = File(ruta)
        if (f.extension.equals("pdf", ignoreCase = true)) {
            val i = pagina.coerceAtLeast(0)
            val bm = com.forge.pixpin.motor.PdfDoc.render(ruta, i, ancho) ?: error("no se pudo pintar la página ${i + 1} del PDF")
            val medida = com.forge.pixpin.motor.PdfDoc.medidaEnPuntos(ruta, i)
            val trozos = runCatching {
                val archivo = com.forge.pixpin.motor.leerPdf(f.readBytes()) ?: return@runCatching null
                com.forge.pixpin.motor.PdfAHtml.trozosDe(com.forge.pixpin.motor.PlanoDePdf.de(archivo, i)?.textos.orEmpty())
            }.getOrNull()
            val cabeza = buildJsonObject {
                put("ancho", bm.width); put("alto", bm.height)
                if (trozos != null && medida != null) {
                    put("ancho_pt", medida.first)
                    put("trozos", buildJsonArray {
                        for (t in trozos) add(buildJsonArray { add(JsonPrimitive(t.texto)); add(JsonPrimitive(t.x)); add(JsonPrimitive(t.y)); add(JsonPrimitive(t.fin)); add(JsonPrimitive(t.alto)); add(JsonPrimitive(t.negrita)) })
                    })
                }
            }
            empaquetarImagen(cabeza.toString(), rgbaDe(bm)).also { bm.recycle() }
        } else {
            // La foto entera (el PC la lee a su tamaño y la reduce a 2600 si es más grande).
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(ruta, o)
            var muestra = 1
            while (maxOf(o.outWidth, o.outHeight) / (muestra * 2) >= 2600) muestra *= 2
            val bm = BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = muestra; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: error("no se pudo abrir la imagen")
            empaquetarImagen(buildJsonObject { put("ancho", bm.width); put("alto", bm.height) }.toString(), rgbaDe(bm)).also { bm.recycle() }
        }
    } catch (e: Exception) {
        empaquetarImagen(buildJsonObject { put("error", e.message ?: "no se pudo leer la hoja") }.toString(), ByteArray(0))
    }

    override fun ocr(rgba: ByteArray, ancho: Int, alto: Int): String {
        val bm = bitmapDe(rgba, ancho, alto)
        val r = LectorDeLetras.renglones(bm).also { bm.recycle() }
            ?: return buildJsonObject { put("error", "el OCR del teléfono no está disponible (hace falta Google Play)") }.toString()
        return buildJsonArray {
            for (l in r) add(buildJsonArray { for (p in l) add(buildJsonArray { add(JsonPrimitive(p.texto)); add(JsonPrimitive(p.x)); add(JsonPrimitive(p.y)); add(JsonPrimitive(p.w)); add(JsonPrimitive(p.h)) }) })
        }.toString()
    }

    // ------------------------------------------------------------------------------------ la nube

    override fun motor(): String = buildJsonObject {
        put("nube", esNube); put("ve", motor.veImagenes)
        // Cuántas a la vez (PC: Claude 8, DeepSeek 3).
        val proveedor = (contexto.applicationContext as? com.forge.pixpin.PixPinApp)?.let { app ->
            runCatching { runBlocking { app.settings.settings.first() }.asistenteMotor }.getOrNull()
        }.orEmpty()
        put("a_la_vez", if (proveedor.contains("deepseek", ignoreCase = true)) 3 else 8)
    }.toString()

    override fun completar(peticion: String): String = try {
        val o = json.parseToJsonElement(peticion).jsonObject
        val mensajes = o["mensajes"]!!.jsonArray.map { m ->
            val mo = m.jsonObject
            val rol = when ((mo["rol"] as? JsonPrimitive)?.content) { "sistema" -> Rol.SISTEMA; "asistente" -> Rol.ASISTENTE; "herramienta" -> Rol.HERRAMIENTA; else -> Rol.USUARIO }
            MensajeIa(rol, mo["partes"]!!.jsonArray.map { p ->
                val po = p.jsonObject
                val img = (po["imagen"] as? JsonPrimitive)?.content
                if (img != null) Parte.Imagen(java.util.Base64.getDecoder().decode(img), (po["mime"] as? JsonPrimitive)?.content ?: "image/png")
                else Parte.Texto((po["texto"] as? JsonPrimitive)?.content.orEmpty())
            })
        }
        val p = Peticion(mensajes, maxTokens = (o["max_tokens"] as? JsonPrimitive)?.intOrNull ?: 1024,
            temperatura = (o["temperatura"] as? JsonPrimitive)?.doubleOrNull ?: 0.2)
        val r = motor.completar(p) { if (parar.get()) Seguir.PARAR else Seguir.SI }
        buildJsonObject { put("texto", ChatDelAsistente.sinPensado(r.texto)) }.toString()
    } catch (e: Exception) {
        buildJsonObject { put("error", e.message ?: "la nube no contestó") }.toString()
    }

    override fun parado(): Boolean = parar.get()

    override fun progreso(hechas: Int, total: Int) = alProgresar(hechas, total)

    // ------------------------------------------------------------------------------- el lienzo

    /** Lo que se sabe de una hoja a medio meter (PC `al_lienzo::Sesion`). */
    private class Sesion(val dx: Double, val dy: Double, val enAbierto: Boolean, val id: String, val nuevo: Boolean) {
        val zonas = LinkedHashMap<Int, List<String>>()
        val archivos = HashMap<String, SceneFile>()
    }

    private val sesiones = HashMap<String, Sesion>()
    private val cerrojo = Any()

    /** Lo abierto, si deja editar: el usuario ve llegar la hoja. */
    private fun abierto(): com.forge.pixpin.motor.EditaElLienzo? = com.forge.pixpin.motor.LienzoAlFrente.actual() as? com.forge.pixpin.motor.EditaElLienzo

    /** En el hilo de la interfaz (ya en él, sin esperar a sí mismo). */
    private fun <T> enPantalla(f: () -> T): T =
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) f() else runBlocking { withContext(Dispatchers.Main) { f() } }

    private fun escenaDe(s: Sesion): Scene? =
        if (s.enAbierto) enPantalla { abierto()?.paraEditar()?.takeIf { it.id == s.id }?.escena }
        else ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, s.id)) ?: Scene()

    private fun empezar(clave: String, tam: Pair<Double, Double>, juntoA: DoubleArray?): Sesion {
        // 1. El lienzo que el usuario tiene delante: a la vista, sin tapar lo que hay.
        val a = enPantalla { abierto()?.paraEditar() }
        if (a != null) {
            val (dx, dy) = sitioLibre(a.escena.elements, tam, juntoA, a.esquina.x to a.esquina.y)
            return Sesion(dx, dy, true, a.id, false)
        }
        // 2. El nombrado o el del asistente en este chat; 3. uno nuevo.
        val propio = destino?.takeIf { File(ExcalidrawStore.rutaDe(contexto, it)).isFile }
            ?: ChatDelAsistente.lienzoPropio(almacen.leer(), chat)?.referencia
        if (propio != null) {
            val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, propio)) ?: Scene()
            val (dx, dy) = sitioLibre(escena.elements, tam, juntoA, null)
            return Sesion(dx, dy, false, propio, false)
        }
        return Sesion(0.0, 0.0, false, "ia-hoja-$clave", true)
    }

    /** **Un sitio libre** para la hoja (PC `sitio_libre`): junto a su imagen si sale de una, si no a la derecha de todo. */
    private fun sitioLibre(hay: List<Element>, tam: Pair<Double, Double>, juntoA: DoubleArray?, vista: Pair<Double, Double>?): Pair<Double, Double> {
        if (juntoA != null) return (juntoA[2] + 80.0) to juntoA[1]
        val vivos = hay.filter { !it.isDeleted }
        if (vivos.isEmpty()) return vista ?: (0.0 to 0.0)
        return com.forge.pixpin.ia.Lienzo.alLado(vivos)
    }

    /** Ids que no choquen con los que ya hay (rehacer una zona borra por id). */
    private fun sinChoques(els: List<Element>, usados: Set<String>): List<Element> {
        val cambio = HashMap<String, String>()
        for (e in els) if (e.id in usados) cambio[e.id] = "${e.id}-${UUID.randomUUID().toString().take(6)}"
        if (cambio.isEmpty()) return els
        return els.map { e ->
            e.copy(
                id = cambio[e.id] ?: e.id,
                containerId = e.containerId?.let { cambio[it] ?: it },
                boundElements = e.boundElements?.map { b -> b.copy(id = cambio[b.id] ?: b.id) }
            )
        }
    }

    override fun entregar(documento: String, imagenes: ByteArray, en: String): String = try {
        synchronized(cerrojo) { entregarYa(documento, imagenes, json.parseToJsonElement(en).jsonObject) }
        ""
    } catch (e: Exception) {
        e.message ?: "no se pudo meter la zona en el lienzo"
    }

    private fun entregarYa(documento: String, imagenes: ByteArray, en: JsonObject) {
        val clave = (en["sesion"] as JsonPrimitive).content
        val zona = (en["zona"] as JsonPrimitive).intOrNull ?: 0
        val rehacer = (en["rehacer"] as? JsonPrimitive)?.booleanOrNull == true
        val ultima = (en["ultima"] as? JsonPrimitive)?.booleanOrNull == true
        val tam = (en["tam"] as? JsonArray)?.let { (it[0] as JsonPrimitive).doubleOrNull!! to (it[1] as JsonPrimitive).doubleOrNull!! } ?: (1000.0 to 1414.0)
        val juntoA = (en["junto_a"] as? JsonArray)?.takeIf { it !is JsonNull }?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.toDoubleArray()?.takeIf { it.size == 4 }
        val s = sesiones.getOrPut(clave) { empezar(clave, tam, juntoA) }
        lienzo = s.id
        // Las imágenes de la zona, a ficheros del lienzo (con el id que nombran sus elementos).
        for ((id, w, hp) in desempaquetar(imagenes)) {
            val f = ExcalidrawStore.rutaDeImagen(contexto, id)
            bitmapDe(hp.second, w, hp.first).let { bm -> f.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }; bm.recycle() }
            s.archivos[id] = SceneFile(id, "image/png", f.absolutePath, System.currentTimeMillis())
        }
        if (documento.isNotEmpty() || rehacer) {
            val escena = escenaDe(s) ?: return
            val viejos = if (rehacer) s.zonas[zona].orEmpty().toSet() else emptySet()
            val quedan = escena.elements.filter { it.id !in viejos }
            val nuevos = sinChoques(elementosDe(documento), quedan.map { it.id }.toSet())
                .map { it.copy(x = it.x + s.dx, y = it.y + s.dy, updated = System.currentTimeMillis()) }
            s.zonas[zona] = nuevos.map { it.id }
            val archivos = escena.files + s.archivos
            if (s.enAbierto) {
                val bien = enPantalla { abierto()?.ponerEscena(quedan + nuevos, archivos) ?: false }
                if (!bien) error("el lienzo abierto ya no deja editar")
            } else {
                ExcalidrawStore.guardar(contexto, s.id, escena.copy(elements = quedan + nuevos, files = archivos)) ?: error("no se pudo guardar el lienzo")
            }
        }
        if (ultima) {
            sesiones.remove(clave)
            // Uno nuevo entra al chat como dibujo del asistente (como lo demás que dibuja).
            if (s.nuevo && File(ExcalidrawStore.rutaDe(contexto, s.id)).isFile) almacen.anadir(
                Mensaje(id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.DIBUJO,
                    nombre = ChatDelAsistente.NOMBRE_DEL_LIENZO, referencia = s.id, proyecto = chat, aparato = ChatDelAsistente.APARATO)
            )
        }
    }

    // ------------------------------------------------------------------------------ pintar

    override fun pintar(documento: String, imagenes: ByteArray, tamAncho: Float, tamAlto: Float, ancho: Int): ByteArray {
        val alto = maxOf(1, (ancho * tamAlto / maxOf(tamAncho, 1f)).toInt())
        val bm = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        bm.eraseColor(android.graphics.Color.WHITE)
        val fotos = desempaquetar(imagenes).associate { (id, w, hp) -> id to bitmapDe(hp.second, w, hp.first) }
        val els = runCatching { elementosDe(documento) }.getOrDefault(emptyList())
        if (els.isNotEmpty()) {
            com.forge.pixpin.motor.Renderer(
                imageProvider = { id -> fotos[id] },
                typefaces = com.forge.pixpin.motor.DrawFonts.provider(contexto.applicationContext)
            ).renderScene(
                android.graphics.Canvas(bm),
                Scene(elements = els, viewport = com.forge.pixpin.motor.Viewport(zoom = (ancho / maxOf(tamAncho, 1f)).toDouble())),
                ancho.toDouble(), alto.toDouble()
            )
        }
        fotos.values.forEach { it.recycle() }
        val rgba = rgbaDe(bm).also { bm.recycle() }
        return ByteBuffer.allocate(8 + rgba.size).order(ByteOrder.LITTLE_ENDIAN).putInt(ancho).putInt(alto).put(rgba).array()
    }
}
