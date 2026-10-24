package com.forge.pixpin.planos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * **Los marcadores de un plano** (24-oct-2026, como `Marcador2d` de `pixpin-cad/src/marcadores.rs`
 * del PC, 19/20-oct): cada uno guarda **la vista entera** —el centro y lo que se ve de ancho y de
 * alto, en coordenadas absolutas del dibujo, y hacia arriba— para volver justo ahí.
 *
 * Viajan en `anot-<uid>.vistas.json`, el mismo nombre que las vistas de los documentos pero con
 * otro formato: `{"version":1,"plano":[…],"modelo":[…]}`. Las dos listas van siempre, aunque estén
 * vacías (`[]` es «sin marcadores»: un archivo borrado volvería del otro aparato), y lo que no se
 * conoce —los marcadores 3D del PC, un campo nuevo— se deja como estaba.
 *
 * El emoticono del riel va en su `nombre` (el PC lo enseña como nombre); uno sin emoticono, del
 * PC, sale como 📍.
 */
object MarcadoresDelPlano {
    data class Marcador(val id: String, val nombre: String, val x: Double, val y: Double, val ancho: Double, val alto: Double) {
        /** Unidades del dibujo por píxel para ver lo mismo en una pantalla de [w]×[h] (`px_en`). */
        fun pxEn(w: Int, h: Int): Double {
            val px = maxOf(ancho / w.coerceAtLeast(1), alto / h.coerceAtLeast(1))
            return if (px.isFinite() && px > 0) px else 1.0
        }

        /** Lo que enseña el riel: el nombre si es un emoticono corto; si no, 📍. */
        val emoji: String get() = nombre.trim().takeIf { it.isNotEmpty() && it.length <= 8 && it.none { c -> c.isLetterOrDigit() } } ?: "📍"
    }

    /** El archivo entero: los del plano y todo lo demás tal cual. */
    class Archivo(val plano: List<Marcador>, private val resto: Map<String, JsonElement>) {
        fun con(nueva: List<Marcador>) = Archivo(nueva, resto)

        fun aTexto(): String {
            val o = buildJsonObject {
                put("version", (resto["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1)
                put("plano", JsonArray(plano.map { m ->
                    buildJsonObject {
                        put("id", m.id); put("nombre", m.nombre)
                        put("x", m.x); put("y", m.y); put("ancho", m.ancho); put("alto", m.alto)
                    }
                }))
                put("modelo", resto["modelo"] ?: JsonArray(emptyList()))
                for ((k, v) in resto) if (k != "version" && k != "plano" && k != "modelo" && k != "marcos") put(k, v)
            }
            return JSON.encodeToString(JsonObject.serializer(), o)
        }
    }

    private val JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun deTexto(texto: String?): Archivo {
        val o = texto?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            ?: return Archivo(emptyList(), emptyMap())
        val plano = (o["plano"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            fun n(k: String) = (m[k] as? JsonPrimitive)?.doubleOrNull
            val id = (m["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Marcador(id, (m["nombre"] as? JsonPrimitive)?.contentOrNull.orEmpty(), n("x") ?: return@mapNotNull null, n("y") ?: return@mapNotNull null,
                n("ancho") ?: return@mapNotNull null, n("alto") ?: return@mapNotNull null)
        }
        return Archivo(plano, o.filterKeys { it != "plano" })
    }

    /** Un id nuevo, como los del PC (`m` y el reloj en base 36). */
    fun idNuevo(ahora: Long, usados: Collection<String>): String {
        var t = ahora
        while ("m" + t.toString(36) in usados) t++
        return "m" + t.toString(36)
    }

    /**
     * El marcador de lo que se ve: [camara] con el centro **respecto al origen** del modelo
     * ([origenX], [origenY]) y una pantalla de [w]×[h].
     */
    fun deLaVista(camara: CamaraPlano, origenX: Double, origenY: Double, w: Int, h: Int, nombre: String, id: String) =
        Marcador(id, nombre, camara.centroX + origenX, camara.centroY + origenY, camara.px * w, camara.px * h)

    /** Y de vuelta: la cámara que enseña lo mismo en una pantalla de [w]×[h]. */
    fun camaraDe(m: Marcador, origenX: Double, origenY: Double, w: Int, h: Int) =
        CamaraPlano(m.x - origenX, m.y - origenY, m.pxEn(w, h))
}
