package com.forge.pixpin.motormd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * **Las marcas de texto de una nota o de una página web** (2-nov-2026, puerto de
 * `pindesk-docs/src/ancla_texto.rs` y `apps/pindesk/src/marcas_web.rs` del PC): resaltar, raya
 * ondulada, subrayar, tachar y tapar lo elegido. Una página se recoloca con el ancho, así que cada
 * marca guarda **su cita** (W3C `TextQuoteSelector` + `TextPositionSelector`, posiciones UTF-16
 * sobre el texto de la página) y la página la vuelve a buscar al abrir (el guion del PC,
 * `assets/marcas-pagina.js`).
 *
 * Viven en `pins/draw/anot-<uid>.marcas-texto.json` del mensaje y viajan con él
 * ([com.forge.pixpin.sincro.AnotacionesDelAdjunto.marcasDeTexto]). Sin marcas no se borra el
 * archivo: se escribe la lista vacía. **Lo que no se entiende se conserva**: cada marca se guarda
 * tal cual llega (con sus campos desconocidos), y lo de arriba también ([Archivo.resto]).
 */
object MarcasDeTexto {
    const val VERSION = 1
    const val GRUPO = "marca-texto"
    val MARCAS = listOf("resaltar", "ondulada", "subrayar", "tachar", "tapar")
    const val SOBRE_MARKDOWN = "markdown"
    const val SOBRE_HTML = "html"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    data class Archivo(
        val version: Int = VERSION,
        val sobre: String = "",
        val marcas: List<JsonObject> = emptyList(),
        val resto: Map<String, JsonElement> = emptyMap()
    )

    fun id(m: JsonObject): String = (m["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** Con id y algo citado. */
    fun valida(m: JsonObject): Boolean {
        val cita = m["cita"] as? JsonObject ?: return false
        val exact = (cita["exact"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return id(m).isNotEmpty() && exact.isNotEmpty()
    }

    /** Vacío es «sin marcas»; lo roto es error (no se escribe encima). */
    fun leer(t: String): Result<Archivo> = runCatching {
        if (t.isBlank()) return@runCatching Archivo()
        val o = json.parseToJsonElement(t) as? JsonObject ?: error("no es un objeto")
        Archivo(
            version = (o["version"] as? JsonPrimitive)?.intOrNull ?: VERSION,
            sobre = (o["sobre"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            marcas = deLaPagina(o["marcas"]),
            resto = o.filterKeys { it != "version" && it != "sobre" && it != "marcas" }
        )
    }

    fun escribir(a: Archivo): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(linkedMapOf<String, JsonElement>("version" to JsonPrimitive(a.version), "sobre" to JsonPrimitive(a.sobre), "marcas" to JsonArray(a.marcas)) + a.resto)
    )

    /** La lista que manda la página: solo las marcas válidas. */
    fun deLaPagina(v: JsonElement?): List<JsonObject> =
        (v as? JsonArray)?.mapNotNull { it as? JsonObject }?.filter(::valida).orEmpty()

    fun deLaPagina(t: String): List<JsonObject> = runCatching { deLaPagina(json.parseToJsonElement(t)) }.getOrDefault(emptyList())

    fun lista(marcas: List<JsonObject>): String = json.encodeToString(JsonArray.serializer(), JsonArray(marcas)).replace("\n", "")

    /**
     * **Lo de aquí y lo que llegó mientras tanto** (`md_comentarios::juntar` del PC): nuevo de los
     * dos lados se queda; borrado allí se va si aquí no se tocó; cambiado en los dos, gana lo de aquí.
     */
    fun fusionar(base: Archivo, mio: Archivo, disco: Archivo): Archivo {
        fun busca(l: List<JsonObject>, k: String) = l.firstOrNull { id(it) == k }
        val salida = ArrayList<JsonObject>()
        for (m in mio.marcas) {
            val k = id(m)
            val b = busca(base.marcas, k)
            val d = busca(disco.marcas, k)
            when {
                b == null -> salida += m
                d == null -> if (b != m) salida += m
                else -> salida += if (m == b) d else m
            }
        }
        for (d in disco.marcas) {
            val k = id(d)
            if (mio.marcas.none { id(it) == k } && base.marcas.none { id(it) == k }) salida += d
        }
        return Archivo(
            version = maxOf(mio.version, disco.version),
            sobre = mio.sobre.ifEmpty { disco.sobre },
            marcas = salida,
            resto = disco.resto + mio.resto
        )
    }

    /**
     * **Las marcas de la página abierta** (`MarcasDeLaPagina` del PC): lee el archivo, guarda lo
     * que manda la página juntándolo con lo que haya llegado al disco y avisa si cambió por fuera.
     */
    class DeLaPagina(private val archivo: File?, sobre: String) {
        private var base = Archivo()
        private var mio = Archivo(sobre = sobre)
        private var roto = false
        private var sello: Pair<Long, Long>? = null

        private fun selloDe(f: File) = if (f.isFile) f.lastModified() to f.length() else null

        init {
            val f = archivo
            if (f != null) {
                sello = selloDe(f)
                val t = if (f.isFile) runCatching { f.readText() }.getOrNull() else ""
                val l = t?.let { leer(it) }
                if (l != null && l.isSuccess) {
                    base = l.getOrThrow()
                    mio = base.copy(sobre = base.sobre.ifEmpty { sobre })
                } else roto = true
            }
        }

        val marcas: List<JsonObject> get() = mio.marcas

        /** La lista para la página, en JSON. */
        fun lista(): String = MarcasDeTexto.lista(mio.marcas)

        /** La lista entera que mandó la página: se guarda. Si al juntar sale otra cosa, la nueva para ella. */
        @Synchronized
        fun deLaPagina(t: String): String? {
            val suyas = MarcasDeTexto.deLaPagina(t)
            mio = mio.copy(marcas = suyas)
            guardar()
            return if (mio.marcas != suyas) MarcasDeTexto.lista(mio.marcas) else null
        }

        private fun leerDisco(): Archivo? {
            val f = archivo ?: return null
            if (!f.isFile) return Archivo()
            return runCatching { f.readText() }.getOrNull()?.let { leer(it).getOrNull() }
        }

        private fun guardar() {
            val f = archivo ?: return
            if (roto) return
            val junto = fusionar(base, mio, leerDisco() ?: base)
            // Uno que no estaba no se crea vacío.
            if (junto.marcas.isNotEmpty() || f.isFile) {
                runCatching {
                    f.parentFile?.mkdirs()
                    val tmp = File(f.path + ".tmp")
                    tmp.writeText(escribir(junto))
                    if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
                }.onFailure { return }
            }
            base = junto
            mio = junto
            sello = selloDe(f)
        }

        /** Si el archivo cambió por fuera (la sincronización): la lista nueva para la página. */
        @Synchronized
        fun mirar(): String? {
            val f = archivo ?: return null
            val ahora = selloDe(f)
            if (ahora == sello) return null
            sello = ahora
            val disco = leerDisco() ?: return null
            val junto = fusionar(base, mio, disco)
            base = disco
            if (junto.marcas == mio.marcas) return null
            mio = junto
            return MarcasDeTexto.lista(mio.marcas)
        }
    }
}
