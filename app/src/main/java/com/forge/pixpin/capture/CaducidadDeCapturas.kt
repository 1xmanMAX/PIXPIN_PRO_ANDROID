package com.forge.pixpin.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * **Las capturas se van solas a la semana**, como en el PC (`apps/pixpin/src/caducidad_capturas.rs`,
 * 3-oct-2026). El usuario: «que todas tengan una fecha de autodestrucción, por ejemplo 1 semana;
 * cada captura se va borrando a la semana a menos que se le ponga que no se borre y se conserve
 * ahí en el chat».
 *
 * Las mismas tres decisiones que allí:
 *
 * - **Lo que ya había no se borra de golpe.** El registro apunta `desde` (la primera vez que se
 *   miró) y una captura caduca a los N días de lo MÁS TARDE entre su fecha y `desde`.
 * - **Irse es ir a la papelera** (la del sistema, `IS_TRASHED`, de donde se recupera desde la
 *   galería del teléfono), no borrar. Ver [BarrenderoDeCapturas].
 * - **Conservar es mandarla al chat** y apuntarla en [Registro.conservadas]: deja de caducar.
 *
 * Y la v2 de la galería: **«Dar 7 días más»** apunta en [Registro.prorrogadas] la nueva fecha.
 * Campo opcional: un registro sin él se lee igual y, vacío, no se escribe.
 *
 * El registro vive en `<filesDir>/capturas-caducidad.json`, con el mismo nombre y la misma forma
 * que el del PC (`desde`, `conservadas`, `prorrogadas`). No viaja: las capturas son de cada aparato.
 *
 * Sin Android: se prueba en JUnit (`CaducidadDeCapturasTest`).
 */
object CaducidadDeCapturas {

    /** Lo que aguanta una captura de fábrica, y lo que da «Dar 7 días más». */
    const val DIAS = 7
    const val DIA_MS = 86_400_000L
    const val FICHERO = "capturas-caducidad.json"

    /** Leer, cambiar y escribir el registro, de uno en uno: lo tocan la galería y el barrendero. */
    private val cerrojo = Any()

    // Dos espacios, como `serde_json::to_vec_pretty` del PC.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }

    data class Registro(
        /** La primera vez que se miró la caducidad, en ms UTC. Nada caduca antes de `desde + días`. */
        val desde: Long,
        /** Los nombres de fichero de las capturas que no se van. */
        val conservadas: Set<String> = emptySet(),
        /**
         * Las que se dejaron estar más: nombre y la fecha nueva en que se van, en ms UTC. Si es
         * anterior a la de la regla, gana la regla: prorrogar nunca acorta.
         */
        val prorrogadas: Map<String, Long> = emptyMap(),
        /**
         * **La fecha exacta en que se va, acordada con el grupo** (8-oct-2026, la galería que se
         * sincroniza: ver `sincro/GaleriaQueViaja`). Manda sobre la regla y las prórrogas: así una
         * captura se va a la vez en todos los aparatos aunque llegara a cada uno otro día. Solo de
         * Android: el PC no lo escribe ni lo lee (su registro no viaja).
         */
        val fijadas: Map<String, Long> = emptyMap()
    )

    // ------------------------------------------------------------------- reglas

    /** Cuándo se va la captura [nombre] hecha en [cuando] (ms UTC). null: no se va. Con [dias] ≤ 0 no se va ninguna. */
    fun seVaEl(r: Registro, nombre: String, cuando: Long, dias: Int): Long? {
        if (dias <= 0 || nombre in r.conservadas) return null
        r.fijadas[nombre]?.let { return it }
        val regla = maxOf(cuando, r.desde) + dias * DIA_MS
        return r.prorrogadas[nombre]?.let { maxOf(regla, it) } ?: regla
    }

    /** La fecha nueva de «Dar 7 días más»: siete días después de la que tenía, o de ahora si ya pasó. null si no se va. */
    fun fechaProrrogada(r: Registro, nombre: String, cuando: Long, ahora: Long, dias: Int): Long? =
        seVaEl(r, nombre, cuando, dias)?.let { maxOf(it, ahora) + DIAS * DIA_MS }

    /** El registro con la prórroga apuntada (igual si no se va). */
    fun prorrogada(r: Registro, nombre: String, cuando: Long, ahora: Long, dias: Int): Registro {
        val t = fechaProrrogada(r, nombre, cuando, ahora, dias) ?: return r
        // Con fecha acordada, se mueve esa: es la que viaja.
        if (nombre in r.fijadas) return r.copy(fijadas = r.fijadas + (nombre to t))
        return r.copy(prorrogadas = r.prorrogadas + (nombre to t))
    }

    /** Los índices de [lista] (nombre, fecha) que ya tocan. */
    fun caducadas(r: Registro, lista: List<Pair<String, Long>>, ahora: Long, dias: Int): List<Int> =
        lista.indices.filter { i -> seVaEl(r, lista[i].first, lista[i].second, dias)?.let { it <= ahora } == true }

    /**
     * Lo que se olvida al borrar [nombres] (su nombre queda libre). El barrido del PC también olvida
     * las que no aparecen en la carpeta; aquí no (ver [BarrenderoDeCapturas.barrer]).
     */
    fun sinEstas(r: Registro, nombres: Collection<String>): Registro =
        r.copy(conservadas = r.conservadas - nombres.toSet(), prorrogadas = r.prorrogadas - nombres.toSet(), fijadas = r.fijadas - nombres.toSet())

    // ------------------------------------------------------------------- fichero

    /** El JSON del registro, como lo escribe el PC: `prorrogadas` solo si hay alguna. */
    fun aTexto(r: Registro): String = JSON.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("desde", JsonPrimitive(r.desde))
        put("conservadas", JsonArray(r.conservadas.sorted().map { JsonPrimitive(it) }))
        if (r.prorrogadas.isNotEmpty()) {
            put("prorrogadas", JsonObject(r.prorrogadas.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        }
        if (r.fijadas.isNotEmpty()) {
            put("fijadas", JsonObject(r.fijadas.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        }
    })

    /** null si no se entiende (o le falta `desde`, que en el PC es obligatorio). */
    fun deTexto(texto: String): Registro? = runCatching {
        val o = Json.parseToJsonElement(texto).jsonObject
        val desde = o["desde"]?.jsonPrimitive?.longOrNull ?: return null
        Registro(
            desde = desde,
            conservadas = o["conservadas"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet(),
            prorrogadas = (o["prorrogadas"] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.longOrNull?.let { k to it }
            }?.toMap() ?: emptyMap(),
            fijadas = (o["fijadas"] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.longOrNull?.let { k to it }
            }?.toMap() ?: emptyMap()
        )
    }.getOrNull()

    /** Lee el registro de [raiz]; si no está (la primera vez) lo crea con `desde = ahora` y lo escribe en el acto. */
    fun leer(raiz: File, ahora: Long): Registro = synchronized(cerrojo) { leerSinCerrojo(raiz, ahora) }

    /** Cambia el registro con el cerrojo cogido y lo escribe. */
    fun cambiar(raiz: File, ahora: Long, f: (Registro) -> Registro): Registro = synchronized(cerrojo) {
        val r = f(leerSinCerrojo(raiz, ahora))
        escribir(raiz, r)
        r
    }

    private fun leerSinCerrojo(raiz: File, ahora: Long): Registro {
        val ruta = File(raiz, FICHERO)
        if (ruta.exists()) {
            runCatching { deTexto(ruta.readText()) }.getOrNull()?.let { return it }
            // Estaba pero no se entiende: se aparta, no se pisa a ciegas.
            runCatching { ruta.renameTo(File(raiz, "$FICHERO.roto-$ahora")) }
        }
        val nuevo = Registro(desde = ahora)
        runCatching { escribir(raiz, nuevo) }
        return nuevo
    }

    private fun escribir(raiz: File, r: Registro) {
        raiz.mkdirs()
        val ruta = File(raiz, FICHERO)
        val tmp = File(raiz, "$FICHERO.tmp")
        tmp.writeText(aTexto(r))
        if (!tmp.renameTo(ruta)) {
            ruta.delete()
            check(tmp.renameTo(ruta)) { "no se pudo escribir $FICHERO" }
        }
    }
}
