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

    /**
     * **Un marcador de un modelo 3D** (`Marcador3d` del PC): la cámara en órbita, con el objetivo en
     * coordenadas **absolutas** del mundo (Z arriba) y, si había, la caja del corte. Lo demás de su
     * `estado` (lo aislado y lo oculto por sus claves, `claro`, `aristas`) se guarda tal cual en
     * [estado] y se reescribe igual: este teléfono aún no lo aplica.
     */
    data class Marcador3d(
        val id: String, val nombre: String,
        val objetivo: DoubleArray, val rumbo: Double, val altura: Double, val dist: Double,
        val seccion: DoubleArray? = null,
        val estado: JsonObject? = null
    ) {
        val emoji: String get() = Marcador(id, nombre, 0.0, 0.0, 0.0, 0.0).emoji

        fun aJson(): JsonObject = buildJsonObject {
            put("id", id); put("nombre", nombre)
            put("estado", estadoJson())
        }

        /** El `Estado3d` del PC: la cámara, el corte y lo demás tal como vino. */
        fun estadoJson(): JsonObject = buildJsonObject {
                put("objetivo", JsonArray(objetivo.map { JsonPrimitive(it) }))
                put("rumbo", rumbo); put("altura", altura.coerceIn(-1.5697, 1.5697)); put("dist", dist)
                put("seccion", seccion?.let { c -> JsonArray(c.map { JsonPrimitive(it) }) } ?: kotlinx.serialization.json.JsonNull)
                val antes = estado
                if (antes == null) {
                    put("aislado", kotlinx.serialization.json.JsonNull)
                    put("ocultos", JsonArray(emptyList()))
                    put("claro", false); put("aristas", true)
                } else for ((k, v) in antes) if (k !in setOf("objetivo", "rumbo", "altura", "dist", "seccion")) put(k, v)
        }

        /** Lo aislado (por su clave si la hay, si no por su número) y lo oculto, para [coincide]. */
        internal val aisladoClave: String? get() = (estado?.get("aislado_clave") as? JsonPrimitive)?.contentOrNull
        internal val aisladoNumero: Double? get() = (estado?.get("aislado") as? JsonPrimitive)?.doubleOrNull
        internal val ocultos: List<String> get() = (estado?.get("ocultos") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        override fun equals(other: Any?) = other is Marcador3d && id == other.id && nombre == other.nombre &&
            objetivo.contentEquals(other.objetivo) && rumbo == other.rumbo && altura == other.altura && dist == other.dist &&
            (seccion?.contentEquals(other.seccion ?: DoubleArray(0)) ?: (other.seccion == null))
        override fun hashCode() = id.hashCode()
    }

    /** El archivo entero: los del plano, los del modelo y todo lo demás tal cual. */
    class Archivo(val plano: List<Marcador>, private val resto: Map<String, JsonElement>) {
        fun con(nueva: List<Marcador>) = Archivo(nueva, resto)

        /** Los del modelo 3D (los que se entienden; los rotos se quedan en el archivo tal cual). */
        val modelo: List<Marcador3d> get() = (resto["modelo"] as? JsonArray).orEmpty().mapNotNull { modelo3dDe(it) }

        fun conModelo(nueva: List<Marcador3d>) = Archivo(plano, resto + ("modelo" to JsonArray(nueva.map { it.aJson() })))

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

    private fun modelo3dDe(e: JsonElement): Marcador3d? {
        val m = e as? JsonObject ?: return null
        val id = (m["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val est = m["estado"] as? JsonObject ?: return null
        fun n(k: String) = (est[k] as? JsonPrimitive)?.doubleOrNull
        fun lista(k: String) = (est[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.toDoubleArray()
        val objetivo = lista("objetivo")?.takeIf { it.size == 3 } ?: return null
        return Marcador3d(
            id, (m["nombre"] as? JsonPrimitive)?.contentOrNull.orEmpty(), objetivo,
            n("rumbo") ?: 0.0, n("altura") ?: 0.0, n("dist") ?: return null,
            lista("seccion")?.takeIf { it.size == 6 }, est
        )
    }

    /**
     * **Si la vista [a] es la del marcador [b]** (`coincide` de `marcadores.rs`): la cámara a menos
     * de ~2e-3 (relativo a su distancia), el corte a menos de 1e-3·dist, y lo aislado y lo oculto
     * iguales. Lo anotado sobre un marcador solo se ve en su vista.
     */
    fun coincide(a: Marcador3d, b: Marcador3d): Boolean {
        val d = kotlin.math.sqrt((0 until 3).sumOf { k -> (a.objetivo[k] - b.objetivo[k]).let { it * it } })
        val escala = kotlin.math.abs(b.dist).coerceAtLeast(1e-6)
        val tau = 2 * Math.PI
        var dr = (a.rumbo - b.rumbo) % tau
        if (dr > Math.PI) dr -= tau else if (dr < -Math.PI) dr += tau
        val camara = d < escala * 2e-3 && kotlin.math.abs(dr) < 2e-3 &&
            kotlin.math.abs(a.altura - b.altura) < 2e-3 && kotlin.math.abs(a.dist / escala - 1) < 2e-3
        val sa = a.seccion; val sb = b.seccion
        val seccion = when {
            sa == null && sb == null -> true
            sa != null && sb != null -> sa.indices.all { kotlin.math.abs(sa[it] - sb[it]) <= escala * 1e-3 + 1e-6 }
            else -> false
        }
        val ka = a.aisladoClave; val kb = b.aisladoClave
        val aislado = if (ka != null && kb != null) ka == kb else a.aisladoNumero == b.aisladoNumero
        return camara && seccion && aislado && a.ocultos == b.ocultos
    }

    /** Un punto de la pantalla, desde su centro y en unidades de su alto (`a_normal`). */
    fun aNormal(x: Double, y: Double, w: Int, h: Int): DoubleArray {
        val hh = h.coerceAtLeast(1).toDouble()
        return doubleArrayOf((x - w / 2.0) / hh, (y - hh / 2) / hh)
    }

    /** Y de vuelta, al píxel (`de_normal`). */
    fun deNormal(x: Double, y: Double, w: Int, h: Int): DoubleArray {
        val hh = h.coerceAtLeast(1).toDouble()
        return doubleArrayOf(x * hh + w / 2.0, y * hh + hh / 2)
    }

    /**
     * **La vista de la tinta de un marcador 3D** (`vista_de_tinta` del PC): la pantalla contada desde
     * su centro y en milésimas de su alto, así lo anotado no se separa del modelo aunque la pantalla
     * cambie de tamaño. Con el `.hoja` `-2500,-2500,2500,2500`.
     */
    fun vistaDeTinta(w: Int, h: Int): com.forge.pixpin.motor.Viewport {
        val z = h.coerceAtLeast(1) / 1000.0
        return com.forge.pixpin.motor.Viewport(scrollX = w / 2.0 / z, scrollY = 500.0, zoom = z)
    }

    /** El marco de la tinta de un marcador 3D. */
    val HOJA_DE_LA_TINTA = com.forge.pixpin.sincro.AnotacionesDelAdjunto.Marco(-2500.0, -2500.0, 2500.0, 2500.0)

    /** La base de la tinta de un marcador: `anot-<uid>-m<id>`, el id saneado a `[A-Za-z0-9_-]`. */
    fun baseDeLaTinta(base: String, id: String) = "$base-m" + id.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }

    // ---- Los marcos 3D: `anot-<uid>.marcos.json` de un modelo ----

    /** Un marco del modelo: su recuadro normalizado ([aNormal]) y la vista de la que es. */
    data class Marco3d(val id: String, val caja: DoubleArray, val estado: Marcador3d) {
        override fun equals(other: Any?) = other is Marco3d && id == other.id && caja.contentEquals(other.caja) && estado == other.estado
        override fun hashCode() = id.hashCode()
    }

    /** `{"version":1,"marcos":[{id, caja, estado}]}`, con lo que no se conoce tal cual. */
    class ArchivoDeMarcos(val marcos: List<Marco3d>, private val resto: Map<String, JsonElement>) {
        fun con(nuevos: List<Marco3d>) = ArchivoDeMarcos(nuevos, resto)

        fun aTexto(): String {
            val o = buildJsonObject {
                put("version", (resto["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1)
                put("marcos", JsonArray(marcos.map { m ->
                    buildJsonObject {
                        put("id", m.id)
                        put("caja", JsonArray(m.caja.map { JsonPrimitive(it) }))
                        put("estado", m.estado.estadoJson())
                    }
                }))
                for ((k, v) in resto) if (k != "version" && k != "marcos") put(k, v)
            }
            return JSON.encodeToString(JsonObject.serializer(), o)
        }
    }

    fun marcosDeTexto(texto: String?): ArchivoDeMarcos {
        val o = texto?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            ?: return ArchivoDeMarcos(emptyList(), emptyMap())
        val marcos = (o["marcos"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val id = (m["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val caja = (m["caja"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 4 } ?: return@mapNotNull null
            val est = modelo3dDe(buildJsonObject { put("id", id); put("estado", m["estado"] ?: return@mapNotNull null) }) ?: return@mapNotNull null
            Marco3d(id, caja.toDoubleArray(), est)
        }
        return ArchivoDeMarcos(marcos, o.filterKeys { it != "marcos" })
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
