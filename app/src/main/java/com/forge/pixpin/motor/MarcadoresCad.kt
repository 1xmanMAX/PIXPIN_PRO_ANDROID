package com.forge.pixpin.motor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * **Los marcadores de los planos y de los modelos** (20-oct-2026, puerto de `pixpin-cad/src/marcadores.rs`
 * del PC; manual `docs/sincronizar-marcos-vistas-y-tinta.md` §3.2-3.4). El usuario: «los marcadores
 * deben existir en CAD y en todos los formatos, guardando no solo la posición sino también el zoom».
 *
 * - **En el plano** ([Marcador2d]): el centro y lo que se ve de ancho y de alto, en coordenadas
 *   absolutas del dibujo (y hacia arriba).
 * - **En el modelo** ([Estado3d]): la cámara (objetivo absoluto, rumbo, altura, distancia), la caja de
 *   sección, lo aislado y lo oculto, el tema y las aristas.
 * - **Los marcos para imprimir del modelo** ([Marco3d]): un recuadro de la pantalla (desde el centro,
 *   en altos de ventana) con el estado en que se hizo.
 *
 * Van en `anot-<uid>.vistas.json` (`{"version":1,"plano":[…],"modelo":[…]}`) y
 * `anot-<uid>.marcos.json` (`{"version":1,"marcos":[…]}`). Las listas se escriben siempre, aunque
 * vacías (un borrado volvería del otro aparato), y **lo que no se conoce se deja como estaba**: los
 * campos de más de cada fichero y de cada estado (los índices de plantas del lector del PC, que aquí
 * no se numeran igual) vuelven a escribirse tal cual llegaron.
 *
 * Sin Android.
 */
data class Marcador2d(val id: String, val nombre: String = "", val x: Double, val y: Double, val ancho: Double, val alto: Double) {
    /** Unidades del dibujo por píxel para ver lo mismo en una ventana de [w] × [h]. */
    fun pxEn(w: Int, h: Int): Double {
        val px = maxOf(ancho / w.coerceAtLeast(1), alto / h.coerceAtLeast(1))
        return if (px.isFinite() && px > 0) px else 1.0
    }
}

data class Estado3d(
    val objetivo: DoubleArray,
    val rumbo: Double,
    val altura: Double,
    val dist: Double,
    val seccion: DoubleArray? = null,
    val aislado: Int? = null,
    /** **Lo aislado por su clave estable** (GlobalId, PC 20-oct): manda sobre [aislado], que es solo una pista. */
    val aisladoClave: String? = null,
    /** Lo oculto: `n:=<clave del nivel>` (o el `n:<i>` de antes), `n:-`, `t:<Tipo>`. */
    val ocultos: List<String> = emptyList(),
    val claro: Boolean = false,
    val aristas: Boolean = true,
    /** Lo de más que traía (otro aparato): vuelve a escribirse igual. */
    val resto: Map<String, JsonElement> = emptyMap()
) {
    override fun equals(other: Any?) = other is Estado3d && objetivo.contentEquals(other.objetivo) && rumbo == other.rumbo &&
        altura == other.altura && dist == other.dist && (seccion?.contentEquals(other.seccion ?: DoubleArray(0)) ?: (other.seccion == null)) &&
        aislado == other.aislado && aisladoClave == other.aisladoClave && ocultos == other.ocultos && claro == other.claro && aristas == other.aristas
    override fun hashCode() = objetivo.contentHashCode() * 31 + dist.hashCode()
}

data class Marcador3d(val id: String, val nombre: String = "", val estado: Estado3d)

data class Marco3d(val id: String, val caja: List<Double>, val estado: Estado3d)

/** Lo de un plano o un modelo: los dos ficheros, en memoria, con lo desconocido de cada uno. */
data class MarcadoresCad(
    val plano: List<Marcador2d> = emptyList(),
    val modelo: List<Marcador3d> = emptyList(),
    val marcos: List<Marco3d> = emptyList(),
    val restoDeVistas: Map<String, JsonElement> = emptyMap(),
    val restoDeMarcos: Map<String, JsonElement> = emptyMap()
) {
    companion object {
        const val VERSION = 1
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val BONITO = Json { prettyPrint = true }

        /** **Si dos estados son la misma vista** (`coincide` del PC): el tema y las aristas no cuentan. */
        fun coincide(a: Estado3d, b: Estado3d): Boolean {
            val d = kotlin.math.sqrt((0 until 3).sumOf { (a.objetivo[it] - b.objetivo[it]).let { v -> v * v } })
            val escala = kotlin.math.abs(b.dist).coerceAtLeast(1e-6)
            var dr = (a.rumbo - b.rumbo) % (2 * Math.PI)
            if (dr > Math.PI) dr -= 2 * Math.PI else if (dr < -Math.PI) dr += 2 * Math.PI
            val camara = d < escala * 2e-3 && kotlin.math.abs(dr) < 2e-3 && kotlin.math.abs(a.altura - b.altura) < 2e-3 && kotlin.math.abs(a.dist / escala - 1) < 2e-3
            val seccion = when {
                a.seccion == null && b.seccion == null -> true
                a.seccion != null && b.seccion != null -> a.seccion.indices.all { kotlin.math.abs(a.seccion[it] - b.seccion[it]) <= escala * 1e-3 + 1e-6 }
                else -> false
            }
            // Lo aislado por su clave si las dos la traen; si no, por el número.
            val mismoAislado = if (a.aisladoClave != null && b.aisladoClave != null) a.aisladoClave == b.aisladoClave else a.aislado == b.aislado
            return camara && seccion && mismoAislado && a.ocultos == b.ocultos
        }

        /** Del píxel de la ventana al punto normalizado: desde el centro y en altos de ventana. */
        fun aNormal(x: Double, y: Double, w: Int, h: Int): DoubleArray { val hh = h.coerceAtLeast(1).toDouble(); return doubleArrayOf((x - w / 2.0) / hh, (y - hh / 2) / hh) }
        fun deNormal(x: Double, y: Double, w: Int, h: Int): DoubleArray { val hh = h.coerceAtLeast(1).toDouble(); return doubleArrayOf(x * hh + w / 2.0, y * hh + hh / 2) }

        private var cuenta = 0L
        /** Un id nuevo, como los del PC: `m` + la hora en nanosegundos y un contador, en hexadecimal. */
        @Synchronized fun nuevoId(): String = "m" + java.lang.Long.toHexString(System.currentTimeMillis() * 1_000_000 + System.nanoTime() % 1_000_000) + java.lang.Long.toHexString(cuenta++)

        // ---- Leer ----

        private fun d(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull
        private fun texto(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        fun estadoDe(e: JsonElement?): Estado3d? {
            val o = e as? JsonObject ?: return null
            val obj = runCatching { o["objetivo"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull!! } }.getOrNull()?.takeIf { it.size == 3 } ?: return null
            val conocidos = setOf("objetivo", "rumbo", "altura", "dist", "seccion", "aislado", "aislado_clave", "ocultos", "claro", "aristas")
            return Estado3d(
                obj.toDoubleArray(), d(o["rumbo"]) ?: 0.0, d(o["altura"]) ?: 0.0, d(o["dist"]) ?: 1.0,
                (o["seccion"] as? JsonArray)?.mapNotNull { d(it) }?.takeIf { it.size == 6 }?.toDoubleArray(),
                (o["aislado"] as? JsonPrimitive)?.intOrNull,
                texto(o["aislado_clave"]),
                (o["ocultos"] as? JsonArray)?.mapNotNull { texto(it) }.orEmpty(),
                (o["claro"] as? JsonPrimitive)?.booleanOrNull ?: false,
                (o["aristas"] as? JsonPrimitive)?.booleanOrNull ?: true,
                o.filterKeys { it !in conocidos }
            )
        }

        fun deTextos(vistas: String?, marcos: String?): MarcadoresCad {
            val v = runCatching { vistas?.let { JSON.parseToJsonElement(it) as? JsonObject } }.getOrNull()
            val m = runCatching { marcos?.let { JSON.parseToJsonElement(it) as? JsonObject } }.getOrNull()
            val plano = (v?.get("plano") as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Marcador2d(texto(o["id"]) ?: return@mapNotNull null, texto(o["nombre"]).orEmpty(),
                    d(o["x"]) ?: return@mapNotNull null, d(o["y"]) ?: return@mapNotNull null,
                    d(o["ancho"]) ?: return@mapNotNull null, d(o["alto"]) ?: return@mapNotNull null)
            }
            val modelo = (v?.get("modelo") as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Marcador3d(texto(o["id"]) ?: return@mapNotNull null, texto(o["nombre"]).orEmpty(), estadoDe(o["estado"]) ?: return@mapNotNull null)
            }
            fun marcosDe(a: JsonElement?) = (a as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val caja = (o["caja"] as? JsonArray)?.mapNotNull { d(it) }?.takeIf { it.size == 4 } ?: return@mapNotNull null
                Marco3d(texto(o["id"]).orEmpty().ifEmpty { nuevoId() }, caja, estadoDe(o["estado"]) ?: return@mapNotNull null)
            }
            // El `vistas.json` del 19-oct llevaba los marcos dentro: se leen de ahí si el otro no los tiene.
            val marcosLeidos = marcosDe(m?.get("marcos")).ifEmpty { if (m == null) marcosDe(v?.get("marcos")) else emptyList() }
            return MarcadoresCad(
                plano, modelo, marcosLeidos,
                v?.filterKeys { it !in setOf("version", "plano", "modelo", "marcos") }.orEmpty(),
                m?.filterKeys { it !in setOf("version", "marcos") }.orEmpty()
            )
        }

        // ---- Escribir ----

        private fun n(v: Double) = JsonPrimitive(v)

        fun estadoAJson(e: Estado3d): JsonObject = JsonObject(buildMap {
            putAll(e.resto)
            put("objetivo", JsonArray(e.objetivo.map { n(it) }))
            put("rumbo", n(e.rumbo)); put("altura", n(e.altura)); put("dist", n(e.dist))
            put("seccion", e.seccion?.let { s -> JsonArray(s.map { n(it) }) } ?: JsonNull)
            put("aislado", e.aislado?.let { JsonPrimitive(it) } ?: JsonNull)
            e.aisladoClave?.let { put("aislado_clave", JsonPrimitive(it)) }
            put("ocultos", JsonArray(e.ocultos.map { JsonPrimitive(it) }))
            put("claro", JsonPrimitive(e.claro)); put("aristas", JsonPrimitive(e.aristas))
        })
    }

    fun textoDeVistas(): String = BONITO.encodeToString(JsonObject.serializer(), JsonObject(buildMap {
        putAll(restoDeVistas)
        put("version", JsonPrimitive(VERSION))
        put("plano", JsonArray(plano.map { m ->
            JsonObject(mapOf("id" to JsonPrimitive(m.id), "nombre" to JsonPrimitive(m.nombre), "x" to n(m.x), "y" to n(m.y), "ancho" to n(m.ancho), "alto" to n(m.alto)))
        }))
        put("modelo", JsonArray(modelo.map { m -> JsonObject(mapOf("id" to JsonPrimitive(m.id), "nombre" to JsonPrimitive(m.nombre), "estado" to estadoAJson(m.estado))) }))
    }))

    fun textoDeMarcos(): String = BONITO.encodeToString(JsonObject.serializer(), JsonObject(buildMap {
        putAll(restoDeMarcos)
        put("version", JsonPrimitive(VERSION))
        put("marcos", JsonArray(marcos.map { m -> JsonObject(mapOf("id" to JsonPrimitive(m.id), "caja" to JsonArray(m.caja.map { n(it) }), "estado" to estadoAJson(m.estado))) }))
    }))
}
