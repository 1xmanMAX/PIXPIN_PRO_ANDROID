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
 * **Se instalan en `figuras.json` como en el PC** (`biblioteca_figuras::instalar_de_serie`): una vez
 * por [VERSION] del catálogo, apuntada en una preferencia que no viaja, cada aparato quita las de
 * serie de antes y pone las de ahora al final, sin tocar las del usuario (ni su orden; si una suya
 * se llama igual, gana la suya). Como `figuras.json` viaja entero y gana el último que lo toca, si
 * el teléfono no las pusiera, al guardar una figura propia le quitaría al PC las suyas, y el PC no
 * las vuelve a poner (ya tiene su marca). Con ids fijos, dos aparatos que las ponen dejan lo mismo.
 * El panel las enseña del APK ([todas]) y las del fichero las quita [sinLasDeSerie].
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
    /** En el APK sale sin el `.gz` (ver [todas]); el otro, por si algún día se empaqueta tal cual. */
    private val ASSETS = listOf("figuras/serie.json", "figuras/serie.json.gz")

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

    /**
     * Todas, leídas del APK una vez. Si el asset falta o no se lee, ninguna.
     *
     * **Con `.gz` o sin él** (25-oct-2026, el usuario: «las figuras no fueron importadas»): al
     * empaquetar, AAPT descomprime los `.gz` de `assets/` y les quita la extensión, así que en el
     * APK está `figuras/serie.json` en claro y abrir `serie.json.gz` fallaba: la lista salía vacía.
     * Se prueban los dos nombres y [leer] mira la cabecera, no el nombre. Un fallo no se guarda:
     * la próxima vez se vuelve a intentar.
     */
    fun todas(context: Context): List<FiguraGuardada> =
        enMemoria ?: synchronized(this) {
            enMemoria ?: (ASSETS.firstNotNullOfOrNull { a ->
                runCatching { context.assets.open(a).use(::leer) }
                    .onFailure { android.util.Log.w("FigurasDeSerie", "No se leyó $a", it) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
            } ?: emptyList()).also { if (it.isNotEmpty()) enMemoria = it }
        }

    /**
     * El catálogo a figuras, venga comprimido (`.json.gz`, el del repositorio) o en claro (el del
     * APK). El PC escribe los puntos como en Excalidraw, `[x, y]`; aquí un [Pt] es `{"x", "y"}`: se
     * pasan al leer (los de `points` y los de cada hueco).
     */
    fun leer(entrada: InputStream): List<FiguraGuardada> {
        val b = java.io.BufferedInputStream(entrada)
        b.mark(2)
        val gz = b.read() == 0x1f && b.read() == 0x8b
        b.reset()
        return leerTexto((if (gz) GZIPInputStream(b) else b).bufferedReader().readText())
    }

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

    /**
     * **Pone el catálogo en una lista**, el `instalar_en` del PC: quita las de serie de antes y mete
     * las de ahora al final, sin tocar las del usuario ni su orden. Si el usuario tiene una figura
     * con el mismo nombre que una de serie, gana la suya y la de serie no entra.
     */
    fun instalarEn(lista: List<FiguraGuardada>, catalogo: List<FiguraGuardada>): List<FiguraGuardada> {
        val propias = lista.filterNot(::esDeSerie)
        val nombres = propias.map { it.nombre.lowercase() }.toSet()
        return propias + catalogo.filter { it.nombre.lowercase() !in nombres }
    }

    private const val PREFS = "figuras_de_serie"
    private const val CLAVE_VERSION = "version"

    /**
     * **Las pone en `figuras.json` una vez por [VERSION]** (fuera de la pantalla: son 10 MB). La marca
     * no viaja, como `figuras-de-serie.version` del PC. Si no se pudo leer el catálogo o escribir, no
     * se marca y se reintenta al arrancar otra vez.
     */
    fun instalarDeSerie(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(CLAVE_VERSION, 0) == VERSION) return
        val catalogo = todas(context)
        if (catalogo.isEmpty()) return
        val lista = instalarEn(BibliotecaStore.cargar(context), catalogo)
        if (BibliotecaStore.guardar(context, lista)) prefs.edit().putInt(CLAVE_VERSION, VERSION).apply()
    }

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
