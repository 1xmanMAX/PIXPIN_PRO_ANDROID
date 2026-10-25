package com.forge.pixpin.motor

import android.content.Context
import java.io.InputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.zip.GZIPInputStream

/**
 * **Las figuras de serie del PC** (25-oct-2026; el usuario: «no veo las figuras que tiene Windows»):
 * las 627 plantillas y símbolos de `pixpin-motor2d/src/figuras_de_serie` —planificadores, blocs,
 * storyboard, arquitectura, planta, mobiliario, vegetación, personas, civil, derecho, ciencias,
 * electrónica, flujo, UML, BPMN, entidad-relación, redes, mapas, negocio, formas y llamadas…—,
 * en 35 categorías.
 *
 * **Son las mismas, no una copia hecha a mano**: el catálogo se genera con el código del PC
 * (`figuras_de_serie::catalogo()` pasado por `biblioteca::a_json`, el formato de `figuras.json`) y
 * va en `assets/figuras/serie.json.gz`. Ids `serie-<clave>` iguales que allí: una figura de serie
 * estampada aquí o allí es la misma pieza, y si el `figuras.json` que llega del PC trae las suyas
 * no salen dos veces ([sinLasDeSerie]).
 *
 * No se meten en `figuras.json`: son 10 MB de JSON que no hace falta mandar por el chat de
 * sincronizar; se leen del APK la primera vez que se abre la lista y se quedan en memoria.
 *
 * Para regenerarlas al cambiar el catálogo del PC (su `VERSION`): ver
 * `herramientas/figuras-de-serie/volcar.sh`.
 */
object FigurasDeSerie {
    /** La `figuras_de_serie::VERSION` del PC con la que se generó el asset. */
    const val VERSION = 7
    /** Entre la categoría y el nombre («Diario · Gratitud»), como en el PC. */
    const val SEPARADOR = " · "
    const val PREFIJO_ID = "serie-"
    private const val ASSET = "figuras/serie.json.gz"

    /** Las categorías, en el orden del menú del PC (`CATEGORIAS`). */
    val CATEGORIAS = listOf(
        "Planificación", "Académico", "Creativo", "Diario", "Blocs", "Storyboard",
        "Arquitectura", "Vegetación", "Mobiliario", "Escala humana",
        "Planta: muebles", "Planta: cocina y baño", "Planta: puertas y ventanas", "Planta: estructura",
        "Planta: exterior", "Personas", "Personas en planta", "Ingeniería civil", "Derecho",
        "Electrónica", "Diagrama de flujo", "Marcas y sellos", "UML", "BPMN", "Entidad-relación",
        "Redes y TI", "Mapas mentales y organigramas", "Estrategia y negocio", "Formas y llamadas",
        "Física", "Química", "Matemáticas", "Biología", "Medicina", "Mecánica"
    )

    @Volatile private var enMemoria: List<FiguraGuardada>? = null

    /** Todas, leídas del APK una vez. Si el asset falta o no se lee, ninguna. */
    fun todas(context: Context): List<FiguraGuardada> =
        enMemoria ?: synchronized(this) {
            enMemoria ?: runCatching { context.assets.open(ASSET).use(::leer) }.getOrDefault(emptyList()).also { enMemoria = it }
        }

    /**
     * El `.json.gz` del catálogo a figuras. El PC escribe los puntos como en Excalidraw, `[x, y]`;
     * aquí un [Pt] es `{"x", "y"}`: se pasan al leer (los de `points` y los de cada hueco).
     */
    fun leer(entrada: InputStream): List<FiguraGuardada> = leerTexto(GZIPInputStream(entrada).bufferedReader().readText())

    /**
     * Una lista de figuras en el formato de `figuras.json`, la escriba el PC (puntos `[x, y]`) o
     * este teléfono (`{"x", "y"}`). La usa también [BibliotecaStore.cargar]: el `figuras.json` que
     * llegaba del PC con una sola raya no se leía entero y no salía ninguna figura.
     */
    fun leerTexto(texto: String): List<FiguraGuardada> =
        ExcalidrawJson.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(FiguraGuardada.serializer()),
            puntosComoObjetos(ExcalidrawJson.parseToJsonElement(texto))
        )

    private fun par(p: JsonElement): JsonElement =
        if (p is JsonArray && p.size >= 2) JsonObject(mapOf("x" to p[0], "y" to p[1])) else p

    /** Recorre el árbol y cambia `points: [[x,y]…]` y `huecos: [[[x,y]…]…]` a objetos. */
    private fun puntosComoObjetos(e: JsonElement): JsonElement = when (e) {
        is JsonArray -> JsonArray(e.map(::puntosComoObjetos))
        is JsonObject -> JsonObject(e.mapValues { (k, v) ->
            when {
                k == "points" && v is JsonArray -> JsonArray(v.map(::par))
                k == "huecos" && v is JsonArray -> JsonArray(v.map { h -> if (h is JsonArray) JsonArray(h.map(::par)) else h })
                else -> puntosComoObjetos(v)
            }
        })
        else -> e
    }

    fun esDeSerie(f: FiguraGuardada) = f.id.startsWith(PREFIJO_ID)

    /** Las del usuario sin las de serie que haya traído el `figuras.json` del PC. */
    fun sinLasDeSerie(lista: List<FiguraGuardada>) = lista.filterNot(::esDeSerie)

    /** «Diario · Gratitud» → («Diario», «Gratitud»); sin separador, null. */
    fun categoriaDe(nombre: String): Pair<String, String>? {
        val i = nombre.indexOf(SEPARADOR)
        if (i <= 0) return null
        val c = nombre.substring(0, i).trim()
        val n = nombre.substring(i + SEPARADOR.length).trim()
        return if (c.isEmpty() || n.isEmpty()) null else c to n
    }

    /** El nombre sin su categoría, para la casilla. */
    fun nombreCorto(nombre: String) = categoriaDe(nombre)?.second ?: nombre

    /**
     * Las de serie agrupadas por categoría en el orden del PC; una categoría que no esté en la
     * lista (de un catálogo más nuevo) va al final, en el orden en que aparece.
     */
    fun porCategoria(lista: List<FiguraGuardada>): List<Pair<String, List<FiguraGuardada>>> {
        val grupos = LinkedHashMap<String, MutableList<FiguraGuardada>>()
        for (f in lista) grupos.getOrPut(categoriaDe(f.nombre)?.first ?: "Otras") { ArrayList() } += f
        val orden = CATEGORIAS + grupos.keys.filter { it !in CATEGORIAS }
        return orden.mapNotNull { c -> grupos[c]?.let { c to it } }
    }
}
