package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * **Las herramientas del asistente: el protocolo común PC/Android y la validación de lo que el
 * modelo pide** (`herramientas.rs` del PC).
 *
 * Cada herramienta se describe con nombre, descripción y parámetros en JSON Schema —la forma de
 * `tools` de OpenAI—, **con el mismo texto que el PC** (`catalogo_json()`; lo comprueba
 * `HerramientasIaTest` contra `docs/manual-asistente-android/herramientas.json`): así un modelo
 * que llama `dibujar_en_lienzo` en el PC la llama igual en el teléfono.
 *
 * De las 45 del PC, el teléfono trae por ahora las tres del primer encargo: dibujar, gráfica y
 * leer (OCR). Las demás (calcar, SVG, acciones de la app…) no se ofrecen; si el modelo las nombra
 * igual, la validación le dice que no existen y cuáles hay.
 *
 * - **La estructura es estricta**: tipos, obligatorios, rangos y listas. Si falla, la llamada NO
 *   se ejecuta y el modelo recibe el motivo en español, **con los mismos textos del PC** (el
 *   modelo aprende de ellos).
 * - **Lo cosmético es tolerante**: un color que no se entiende pasa a negro con un aviso.
 * - **El JSON roto se intenta arreglar** una vez ([repararJson]).
 */
data class Herramienta(val nombre: String, val descripcion: String, val parametros: JsonObject) {
    fun aJson(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", nombre)
            put("description", descripcion)
            put("parameters", parametros)
        })
    }
}

/** Lo que pide el modelo, ya validado. */
sealed class Llamada {
    abstract val nombre: String

    data class Dibujar(val figuras: List<Figura>) : Llamada() { override val nombre get() = Herramientas.DIBUJAR }
    data class Grafica(val grafica: com.forge.pixpin.ia.Grafica) : Llamada() { override val nombre get() = Herramientas.GRAFICA }
    data class Ocr(val peticion: PeticionOcr) : Llamada() { override val nombre get() = Herramientas.OCR }

    /** Si lo que pide se dibuja tal cual en el lienzo. */
    val dibuja: Boolean get() = this is Dibujar || this is Grafica
}

enum class TipoFigura { RECTANGULO, ELIPSE, ROMBO, LINEA, FLECHA, TEXTO, POLIGONO, CURVA, LAPIZ, PUNTO, INSTRUMENTO }

/** Cómo se pinta una figura, más allá de sus colores; las palabras ya como las escribe el fichero. */
data class EstiloFigura(
    val grosor: Double? = null,
    /** `solid`, `dashed` o `dotted`. */
    val trazo: String? = null,
    /** De 0 a 100. */
    val opacidad: Double? = null,
    /** `solid`, `hachure` o `cross-hatch`. */
    val relleno: String? = null,
    val rugosidad: Double? = null,
    /** La palabra del `material` (`cuadritos` es el grafito). */
    val material: String? = null,
    /** `4H` … `8B`. */
    val dureza: String? = null
)

data class PedidoInstrumento(val ejes: Int, val unidad: Double, val tabla: List<Pair<Double, Double>>)

data class Figura(
    val tipo: TipoFigura,
    val x: Double, val y: Double, val ancho: Double, val alto: Double,
    val x2: Double, val y2: Double,
    /** `#rrggbb`. */
    val color: String,
    val relleno: String?,
    val texto: String,
    val tamLetra: Double,
    /** Si el modelo puso algún color; sin él se saca del pedido ([Herramientas.coloresDelPedido]). */
    val conColor: Boolean,
    /** Líneas, flechas, curvas, polígonos y lápiz: todos sus puntos. Vacío en las demás. */
    val puntos: List<Pair<Double, Double>>,
    val grupo: String?,
    val estilo: EstiloFigura,
    val capa: Int,
    val instrumento: PedidoInstrumento?
)

enum class TipoGrafica { BARRAS, LINEAS, PASTEL }

data class Grafica(val tipo: TipoGrafica, val titulo: String, val etiquetas: List<String>, val valores: List<Double>)

/** [archivo] null: el último adjunto. [paginas]: rangos desde 1, ambos incluidos; vacío = todas. */
data class PeticionOcr(val archivo: String?, val paginas: List<Pair<Int, Int>>, val idioma: String?)

/** Una llamada correcta, con los arreglos cosméticos que se le hicieron. */
data class Validada(val llamada: Llamada, val avisos: List<String>)

/** Por qué no se ejecuta una llamada. */
sealed class ErrorHerramienta(m: String) : Exception(m) {
    class Desconocida(val nombre: String) : ErrorHerramienta("la herramienta «$nombre» no existe")
    object JsonRoto : ErrorHerramienta("los argumentos no son JSON valido") {
        private fun readResolve(): Any = JsonRoto
    }
    class Parametro(val motivo: String) : ErrorHerramienta(motivo)

    /** El mensaje que vuelve al modelo para que se corrija (los textos del PC, tal cual). */
    fun paraElModelo(conocidas: List<Herramienta>): String = when (this) {
        is Desconocida -> "ERROR: la herramienta «$nombre» no existe. Las que hay son: ${conocidas.joinToString(", ") { it.nombre }}. Usa una de ellas o contesta con texto."
        JsonRoto -> "ERROR: los argumentos no son JSON valido. Vuelve a llamar con un objeto JSON correcto."
        is Parametro -> "ERROR: $motivo. Corrige los argumentos y vuelve a llamar."
    }
}

object Herramientas {
    /** El lienzo que se le describe al modelo; el real coloca lo dibujado donde mira el usuario. */
    const val ANCHO_LIENZO = 800.0
    const val ALTO_LIENZO = 600.0
    const val COORDENADA_MAXIMA = 2000.0
    const val COORDENADA_MINIMA = -200.0
    const val MAX_ELEMENTOS = 60
    const val MAX_DATOS_GRAFICA = 40
    const val MAX_TEXTO = 500
    const val MAX_NUMEROS_PUNTOS = 240
    const val MAX_TABLA = 60

    const val DIBUJAR = "dibujar_en_lienzo"
    const val GRAFICA = "grafica"
    const val OCR = "ocr"

    private val COORD = COORDENADA_MINIMA to COORDENADA_MAXIMA
    private val MEDIDA = 1.0 to COORDENADA_MAXIMA

    private fun campo(tipo: String, descripcion: String? = null) = buildJsonObject {
        put("type", tipo); descripcion?.let { put("description", it) }
    }

    private fun lista(items: JsonObject, descripcion: String? = null) = buildJsonObject {
        put("type", "array"); put("items", items); descripcion?.let { put("description", it) }
    }

    private fun enumerado(vararg valores: String) = buildJsonObject {
        put("type", "string"); put("enum", JsonArray(valores.map { JsonPrimitive(it) }))
    }

    /**
     * **El catálogo del teléfono**: las tres del primer encargo, con el JSON Schema y la
     * descripción exactos del PC (`catalogo_base()` de `herramientas.rs`).
     */
    fun catalogo(): List<Herramienta> = listOf(
        Herramienta(
            DIBUJAR,
            "Dibuja figuras en el lienzo de PixPin (800x600, origen arriba a la izquierda). " +
                "Cada elemento lleva su «color» (el que pidio el usuario) y, si va lleno, su «relleno». " +
                "Tipos: rectangulo, elipse, rombo, triangulo, estrella y onda (con «caja»:[x,y,ancho,alto]; un circulo tambien con «centro»:[x,y] y «radio»); " +
                "linea, flecha, curva (suave), poligono y lapiz (a mano alzada) (con «puntos»:[x1,y1,x2,y2,...]); texto (con «x», «y» y «texto»); " +
                "punto (con «x», «y» y su letra en «texto»); instrumento (plano cartesiano con «ejes»:2, recta con 1, espacio con 3; «caja», «unidad» en px y «tabla»:[[x,y],...] de coordenadas). " +
                "Opcionales: «grosor», «trazo» (solido, discontinuo, punteado), «opacidad» (0-100), «relleno_estilo» (solido, rayado, cruzado), " +
                "«material» del lapiz (lisa, tiza, grafito) con su «dureza» (4H, 2H, HB, 2B, 4B, 6B, 8B) y «capa» (las mayores van encima). " +
                "Una figura con «texto» lo lleva escrito dentro (o encima si es flecha). «grupo»: un nombre comun para agrupar varias. " +
                "Ejemplo, una casa: {\"elementos\":[{\"tipo\":\"rectangulo\",\"caja\":[300,280,200,160],\"color\":\"marron\"}," +
                "{\"tipo\":\"triangulo\",\"caja\":[280,170,240,110],\"color\":\"rojo\",\"relleno\":\"rojo\"}," +
                "{\"tipo\":\"rectangulo\",\"caja\":[380,360,40,80],\"color\":\"azul\",\"relleno\":\"azul\"}]}. " +
                "Un paso de un diagrama: {\"tipo\":\"rectangulo\",\"caja\":[60,260,160,70],\"texto\":\"Inicio\"}," +
                "{\"tipo\":\"flecha\",\"puntos\":[220,295,320,295]}. " +
                "Para copiar exacta una foto adjunta usa calcar_imagen; para dibujarla con figuras o a lapiz, interpretala con estas figuras. " +
                "Para ilustraciones con curvas o mucho detalle, mejor dibujar_svg.",
            buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray { add(JsonPrimitive("elementos")) })
                put("properties", buildJsonObject {
                    put("elementos", buildJsonObject {
                        put("type", "array"); put("minItems", 1); put("maxItems", MAX_ELEMENTOS)
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("required", buildJsonArray { add(JsonPrimitive("tipo")) })
                            put("properties", buildJsonObject {
                                put("tipo", enumerado("rectangulo", "elipse", "rombo", "triangulo", "estrella", "onda", "linea", "flecha", "curva", "poligono", "lapiz", "texto", "punto", "instrumento"))
                                put("caja", lista(campo("number"), "[x, y, ancho, alto]"))
                                put("puntos", lista(campo("number"), "[x1, y1, x2, y2, ...] de linea, flecha, curva o poligono"))
                                put("centro", lista(campo("number"), "[x, y] de un circulo"))
                                put("radio", campo("number"))
                                put("x", campo("number"))
                                put("y", campo("number"))
                                put("color", campo("string", "color del trazo: rojo, azul, verde, amarillo, naranja, morado, rosa, gris, marron, negro, blanco o #rrggbb"))
                                put("relleno", campo("string", "color de relleno (los mismos nombres); omitir si va vacia"))
                                put("texto", campo("string"))
                                put("tam_letra", campo("number"))
                                put("grupo", campo("string"))
                                put("grosor", campo("number", "grosor del trazo, 1 a 40"))
                                put("trazo", enumerado("solido", "discontinuo", "punteado"))
                                put("opacidad", campo("number", "0 a 100"))
                                put("relleno_estilo", enumerado("solido", "rayado", "cruzado"))
                                put("material", enumerado("lisa", "tiza", "grafito", "lapiz2b", "seco"))
                                put("dureza", enumerado("4H", "2H", "HB", "2B", "4B", "6B", "8B"))
                                put("capa", campo("number", "orden: las mayores encima"))
                                put("ejes", campo("number", "instrumento: 1, 2 o 3"))
                                put("unidad", campo("number", "instrumento: px por unidad"))
                                put("tabla", lista(lista(campo("number")), "instrumento: [[x, y], ...]"))
                            })
                        })
                    })
                    put("origen", lista(campo("number"), "[x, y] que se suma a todas las coordenadas"))
                })
            }
        ),
        Herramienta(
            GRAFICA,
            "Dibuja en el lienzo una grafica de barras, lineas o pastel a partir de datos.",
            buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray { listOf("tipo", "etiquetas", "valores").forEach { add(JsonPrimitive(it)) } })
                put("properties", buildJsonObject {
                    put("tipo", enumerado("barras", "lineas", "pastel"))
                    put("titulo", campo("string"))
                    put("etiquetas", buildJsonObject { put("type", "array"); put("items", campo("string")); put("maxItems", MAX_DATOS_GRAFICA) })
                    put("valores", buildJsonObject { put("type", "array"); put("items", campo("number")); put("maxItems", MAX_DATOS_GRAFICA) })
                })
            }
        ),
        Herramienta(
            OCR,
            // El mismo texto que el PC aunque aquí no haya OCR de Windows: el catálogo tiene que
            // salir idéntico en los dos aparatos. Lo que hace de verdad el teléfono lo dice
            // [com.forge.pixpin.ia.app.EjecutorDelChat].
            "Reconoce el texto de un PDF escaneado o de una imagen sin texto seleccionable, con el OCR de Windows. Usala cuando el usuario quiera el texto de un documento adjunto.",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("archivo", campo("string", "nombre del adjunto; omitir para el ultimo adjunto"))
                    put("paginas", campo("string", "ej. 1-3,5; omitir para todas"))
                    put("idioma", enumerado("es", "en", "auto"))
                })
            }
        )
    )

    /** El catálogo como texto JSON (la lista `tools`). */
    fun catalogoJson(): String = JsonArray(catalogo().map { it.aJson() }).toString()

    // -------------------------------------------------------------------------------------------
    // Arreglar y validar.

    /**
     * **Intenta arreglar un JSON roto** de los que escriben los modelos pequeños: entre vallas de
     * Markdown, con texto delante o detrás, o con una coma antes de `}`/`]`.
     */
    fun repararJson(texto: String): JsonElement? {
        val t = texto.trim()
        leer(t)?.let { return it }
        val desde = t.indexOf('{').takeIf { it >= 0 } ?: return null
        val hasta = t.lastIndexOf('}').takeIf { it >= 0 } ?: return null
        if (hasta <= desde) return null
        val trozo = t.substring(desde, hasta + 1)
        leer(trozo)?.let { return it }
        // Comas colgantes, fuera de cadenas.
        val limpio = StringBuilder(trozo.length)
        var enCadena = false
        var escapado = false
        for (i in trozo.indices) {
            val c = trozo[i]
            if (enCadena) {
                limpio.append(c)
                if (escapado) escapado = false else if (c == '\\') escapado = true else if (c == '"') enCadena = false
                continue
            }
            if (c == '"') enCadena = true
            if (c == ',') {
                val siguiente = (i + 1 until trozo.length).map { trozo[it] }.firstOrNull { !it.isWhitespace() }
                if (siguiente == '}' || siguiente == ']') continue
            }
            limpio.append(c)
        }
        return leer(limpio.toString())
    }

    private fun leer(t: String): JsonElement? = runCatching { Json.parseToJsonElement(t) }.getOrNull()

    /** **Valida una llamada del modelo** contra las herramientas conocidas. */
    fun validar(cruda: LlamadaCruda, conocidas: List<Herramienta>): Validada {
        val nombre = cruda.nombre.trim()
        if (conocidas.none { it.nombre == nombre }) throw ErrorHerramienta.Desconocida(nombre)
        val args = if (cruda.argumentos.isBlank()) JsonObject(emptyMap())
        else repararJson(cruda.argumentos) ?: throw ErrorHerramienta.JsonRoto
        if (args !is JsonObject) throw mal("los argumentos tienen que ser un objeto JSON")
        val avisos = mutableListOf<String>()
        val llamada = when (nombre) {
            DIBUJAR -> Llamada.Dibujar(validarDibujo(args, avisos))
            GRAFICA -> Llamada.Grafica(validarGrafica(args, avisos))
            OCR -> Llamada.Ocr(validarOcr(args))
            else -> throw ErrorHerramienta.Desconocida(nombre)
        }
        return Validada(llamada, avisos)
    }

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)

    /** Un número como lo escribe el PC en sus mensajes: `2010`, `0.5`, `-200`. */
    internal fun n(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    /** Un número: acepta también `"120"` y `"20px"` (los modelos pequeños lo hacen). */
    internal fun numero(v: JsonElement?): Double? {
        val p = v as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        val d = if (p.isString) p.content.trim().removeSuffix("px").trim().toDoubleOrNull()
        else if (p.booleanOrNull != null) null else p.doubleOrNull
        return d?.takeIf { it.isFinite() }
    }

    private fun numeroEn(o: JsonObject, campo: String, donde: String, rango: Pair<Double, Double>): Double? {
        val v = o[campo] ?: return null
        if (v is JsonNull) return null
        val x = numero(v) ?: throw mal("$donde: «$campo» tiene que ser un numero")
        if (x < rango.first || x > rango.second) throw mal("$donde: «$campo» = ${n(x)} esta fuera de rango (${n(rango.first)} a ${n(rango.second)})")
        return x
    }

    internal fun textoEn(o: JsonObject, campo: String): String? {
        val p = o[campo] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return when {
            p.isString -> p.content.trim().takeIf { it.isNotEmpty() }
            p.booleanOrNull != null -> null
            else -> p.content
        }
    }

    /** Una lista de números: plana (`[1,2,3,4]`) o de pares (`[[1,2],[3,4]]`). */
    private fun numeros(v: JsonElement): List<Double>? {
        val l = v as? JsonArray ?: return null
        val out = ArrayList<Double>(l.size * 2)
        for (e in l) {
            if (e is JsonArray) for (x in e) out += numero(x) ?: return null
            else out += numero(e) ?: return null
        }
        return out
    }

    private fun validarDibujo(args: JsonObject, avisos: MutableList<String>): List<Figura> {
        val lista: List<JsonElement> = when (val e = args["elementos"]) {
            is JsonArray -> e
            // Un solo elemento suelto, sin la lista: se acepta.
            is JsonObject -> listOf(e)
            else -> throw mal("falta «elementos» (una lista de figuras)")
        }
        if (lista.isEmpty()) throw mal("«elementos» esta vacia: pon al menos una figura")
        if (lista.size > MAX_ELEMENTOS) throw mal("demasiadas figuras (${lista.size}); el maximo es $MAX_ELEMENTOS")
        // `origen`: dibujar una cosa compuesta pensando desde (0, 0) y ponerla luego donde va.
        val origen = when (val o = args["origen"]) {
            null, JsonNull -> 0.0 to 0.0
            else -> numeros(o)?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: throw mal("«origen» tiene que ser [x, y]")
        }
        return lista.mapIndexed { i, v -> validarFigura(i, v, origen, avisos) }
    }

    private fun noNulo(o: JsonObject, campo: String): JsonElement? = o[campo]?.takeIf { it !is JsonNull }

    private fun validarFigura(i: Int, v: JsonElement, origen: Pair<Double, Double>, avisos: MutableList<String>): Figura {
        val (ox, oy) = origen
        val donde = "elemento ${i + 1}"
        val o = v as? JsonObject ?: throw mal("$donde: tiene que ser un objeto")
        val tipoTxt = textoEn(o, "tipo") ?: throw mal("$donde: falta «tipo»")
        // Formas que se generan de su caja (al modelo le cuesta poner sus puntos).
        var forma: String? = null
        val tipo = when (tipoTxt.lowercase()) {
            "rectangulo", "rectángulo", "rect", "rectangle", "cuadrado", "square" -> TipoFigura.RECTANGULO
            "elipse", "ellipse", "circulo", "círculo", "circle", "ovalo", "óvalo" -> TipoFigura.ELIPSE
            "rombo", "diamond" -> TipoFigura.ROMBO
            "linea", "línea", "line" -> TipoFigura.LINEA
            "flecha", "arrow" -> TipoFigura.FLECHA
            "texto", "text" -> TipoFigura.TEXTO
            "curva", "curve", "spline" -> TipoFigura.CURVA
            "onda", "wave", "ondas" -> { forma = "onda"; TipoFigura.CURVA }
            "poligono", "polígono", "polygon" -> TipoFigura.POLIGONO
            "lapiz", "lápiz", "pencil", "freedraw", "mano_alzada", "trazo", "grafito", "boceto" -> TipoFigura.LAPIZ
            "punto", "point" -> TipoFigura.PUNTO
            "instrumento", "plano", "plano_cartesiano", "ejes", "axes", "recta", "recta_numerica", "espacio" -> TipoFigura.INSTRUMENTO
            "triangulo", "triángulo", "triangle" -> { forma = "triangulo"; TipoFigura.POLIGONO }
            "estrella", "star" -> { forma = "estrella"; TipoFigura.POLIGONO }
            else -> throw mal("$donde: «tipo» = «${tipoTxt.lowercase()}» no existe; usa rectangulo, elipse, rombo, triangulo, estrella, linea, flecha, curva, poligono, lapiz, texto, punto o instrumento")
        }
        // La caja: «caja», o «centro» y «radio», o x, y, ancho, alto.
        val cajaDada: DoubleArray? = when (val c = noNulo(o, "caja")) {
            null -> when (val ce = noNulo(o, "centro")) {
                null -> null
                else -> {
                    val p = numeros(ce)?.takeIf { it.size == 2 } ?: throw mal("$donde: «centro» tiene que ser [x, y]")
                    val r = numeroEn(o, "radio", donde, MEDIDA) ?: 50.0
                    doubleArrayOf(p[0] - r, p[1] - r, 2 * r, 2 * r)
                }
            }
            else -> numeros(c)?.takeIf { it.size == 4 }?.toDoubleArray() ?: throw mal("$donde: «caja» tiene que ser [x, y, ancho, alto]")
        }
        cajaDada?.let { (x, y, w, h) ->
            for ((valor, campo, rango) in listOf(Triple(x, "x", COORD), Triple(y, "y", COORD), Triple(w, "ancho", MEDIDA), Triple(h, "alto", MEDIDA))) {
                if (valor < rango.first || valor > rango.second)
                    throw mal("$donde: «caja» con $campo = ${n(valor)} fuera de rango (${n(rango.first)} a ${n(rango.second)})")
            }
        }
        // Los puntos de líneas, curvas y polígonos.
        var puntos: MutableList<Pair<Double, Double>> = when (val p = noNulo(o, "puntos")) {
            null -> mutableListOf()
            else -> {
                val ns = numeros(p) ?: throw mal("$donde: «puntos» tiene que ser una lista de numeros")
                if (ns.size % 2 != 0) throw mal("$donde: «puntos» tiene un numero impar de valores (van de dos en dos: x, y)")
                if (ns.size > MAX_NUMEROS_PUNTOS) throw mal("$donde: demasiados puntos (maximo ${MAX_NUMEROS_PUNTOS / 2})")
                ns.firstOrNull { it < COORD.first || it > COORD.second }?.let { throw mal("$donde: un punto (${n(it)}) esta fuera de rango") }
                ns.chunked(2).map { it[0] to it[1] }.toMutableList()
            }
        }
        val esTrazo = tipo in setOf(TipoFigura.LINEA, TipoFigura.FLECHA, TipoFigura.CURVA, TipoFigura.POLIGONO, TipoFigura.LAPIZ)
        // En una línea el ancho/alto son el desplazamiento y pueden ser negativos.
        val rangoMedida = if (esTrazo) -COORDENADA_MAXIMA to COORDENADA_MAXIMA else MEDIDA
        val x_ = numeroEn(o, "x", donde, COORD)
        val y_ = numeroEn(o, "y", donde, COORD)
        val ancho_ = numeroEn(o, "ancho", donde, rangoMedida)
        val alto_ = numeroEn(o, "alto", donde, rangoMedida)
        val x2_ = numeroEn(o, "x2", donde, COORD)
        val y2_ = numeroEn(o, "y2", donde, COORD)
        val tamLetra = numeroEn(o, "tam_letra", donde, 6.0 to 200.0) ?: 24.0
        // El texto también con otro nombre («text», «etiqueta», «contenido»...).
        val texto = listOf("texto", "text", "etiqueta", "rotulo", "contenido", "label", "titulo", "valor")
            .firstNotNullOfOrNull { textoEn(o, it) }.orEmpty()
        if (texto.codePointCount(0, texto.length) > MAX_TEXTO) throw mal("$donde: el texto pasa de $MAX_TEXTO caracteres")
        // Una forma con «puntos» de menos (el 2B pone [x, y] sueltos): se ignoran y vale la caja.
        if (esTrazo && puntos.isNotEmpty()) {
            val minimo = if (tipo == TipoFigura.POLIGONO) 3 else 2
            if (puntos.size < minimo) {
                if (forma != null) puntos.clear()
                else if (x_ != null && y_ != null) puntos.add(0, x_ to y_)
            }
        }
        // Sin sitio ninguno: en el centro, a un tamaño normal (mejor dibujarlo que rendirse).
        val sinSitio = cajaDada == null && x_ == null && y_ == null && puntos.isEmpty()
        val caja: DoubleArray? = when {
            cajaDada == null && forma == "onda" && sinSitio -> doubleArrayOf(100.0, 220.0, 600.0, 160.0)
            cajaDada == null && sinSitio && tipo == TipoFigura.INSTRUMENTO -> doubleArrayOf(240.0, 140.0, 320.0, 320.0)
            cajaDada == null && sinSitio && tipo == TipoFigura.PUNTO -> doubleArrayOf(400.0, 300.0, 1.0, 1.0)
            cajaDada == null && sinSitio && tipo != TipoFigura.TEXTO -> doubleArrayOf(250.0, 150.0, 300.0, 300.0)
            else -> cajaDada
        }
        if (esTrazo && puntos.isEmpty()) {
            when {
                forma != null && caja != null -> puntos = formaDeCaja(forma, caja).toMutableList()
                forma != null && x_ != null && y_ != null -> {
                    val a = kotlin.math.abs(ancho_ ?: 100.0).coerceAtLeast(1.0)
                    puntos = formaDeCaja(forma, doubleArrayOf(x_, y_, a, kotlin.math.abs(alto_ ?: a).coerceAtLeast(1.0))).toMutableList()
                }
                tipo == TipoFigura.CURVA && caja != null -> puntos = formaDeCaja("onda", caja).toMutableList()
                tipo == TipoFigura.LINEA || tipo == TipoFigura.FLECHA -> {
                    if (caja != null && x_ == null) {
                        val (x, y, w, h) = caja
                        puntos = mutableListOf(x to y + h / 2, x + w to y + h / 2)
                    } else {
                        val x = x_ ?: throw mal("$donde: falta «puntos» (o «x», «y», «x2», «y2»)")
                        val y = y_ ?: throw mal("$donde: falta «y»")
                        val x2 = x2_ ?: ancho_?.let { x + it } ?: (x + 100.0)
                        val y2 = y2_ ?: alto_?.let { y + it } ?: y
                        puntos = mutableListOf(x to y, x2 to y2)
                    }
                }
                else -> throw mal("$donde: falta «puntos»: [x1, y1, x2, y2, ...]")
            }
        }
        val minimo = when (tipo) {
            TipoFigura.POLIGONO -> 3
            TipoFigura.LINEA, TipoFigura.FLECHA, TipoFigura.CURVA, TipoFigura.LAPIZ -> 2
            else -> 0
        }
        if (puntos.size < minimo) throw mal("$donde: hacen falta al menos $minimo puntos")
        if (esTrazo && puntos.zipWithNext().all { (a, b) -> a == b }) throw mal("$donde: la linea empieza y acaba en el mismo punto")
        puntos = puntos.map { (px, py) -> (px + ox) to (py + oy) }.toMutableList()
        val x: Double; val y: Double; val ancho: Double; val alto: Double; val x2: Double; val y2: Double
        if (esTrazo) {
            x = puntos.first().first; y = puntos.first().second
            x2 = puntos.last().first; y2 = puntos.last().second
            ancho = x2 - x; alto = y2 - y
        } else {
            val c = caja ?: run {
                val cx = x_ ?: if (tipo == TipoFigura.TEXTO) 60.0 else throw mal("$donde: falta «x» (o «caja»)")
                val cy = y_ ?: if (tipo == TipoFigura.TEXTO) 40.0 else throw mal("$donde: falta «y» (o «caja»)")
                if (tipo == TipoFigura.TEXTO) doubleArrayOf(
                    cx, cy, ancho_ ?: (tamLetra * 0.6 * texto.codePointCount(0, texto.length)), alto_ ?: (tamLetra * 1.25)
                ) else {
                    val a = ancho_ ?: 100.0
                    doubleArrayOf(cx, cy, a, alto_ ?: a)
                }
            }
            x = c[0] + ox; y = c[1] + oy; ancho = c[2]; alto = c[3]; x2 = x + ancho; y2 = y + alto
        }
        // Los modelos pequeños escriben el color con otro nombre («fill», «stroke», «fondo»).
        val colorTxt = listOf("color", "colour", "stroke", "trazo").firstNotNullOfOrNull { textoEn(o, it) }
        val rellenoTxt = listOf("relleno", "fill", "fondo", "background").firstNotNullOfOrNull { textoEn(o, it) }
        val conColor = colorTxt != null || rellenoTxt != null
        val trazo = if (colorTxt == null) {
            // Sin color pero con relleno: el borde del mismo color (un «círculo rojo» lleno no
            // debe salir con el borde negro).
            rellenoTxt?.let { color(it) }?.takeIf { it != "#ffffff" } ?: "#1e1e1e"
        } else color(colorTxt) ?: run { avisos += "$donde: color «$colorTxt» desconocido, se usa negro"; "#1e1e1e" }
        val relleno = when {
            rellenoTxt == null -> null
            rellenoTxt.lowercase() in setOf("ninguno", "none", "transparent", "transparente") -> null
            else -> color(rellenoTxt) ?: run { avisos += "$donde: relleno «$rellenoTxt» desconocido, se deja sin relleno"; null }
        }
        val grupo = textoEn(o, "grupo")?.let { g -> g.codePoints().limit(40).toArray().let { String(it, 0, it.size) } }
        val estilo = validarEstilo(o, donde, tipo, avisos)
        val capa = (numeroEn(o, "capa", donde, -1000.0 to 1000.0) ?: 0.0).toInt()
        val instrumento = if (tipo == TipoFigura.INSTRUMENTO) validarInstrumento(o, donde, tipoTxt) else null
        return Figura(
            tipo, x, y, ancho, alto, x2, y2, trazo, relleno, texto, tamLetra, conColor,
            if (esTrazo) puntos else emptyList(), grupo, estilo, capa, instrumento
        )
    }

    /** El estilo: lo que no se entiende se queda en lo de siempre con un aviso, salvo los números fuera de rango. */
    private fun validarEstilo(o: JsonObject, donde: String, tipo: TipoFigura, avisos: MutableList<String>): EstiloFigura {
        fun palabra(campo: String) = textoEn(o, campo)?.let { normalizar(it) }
        val trazo = palabra("trazo")?.let {
            when (it) {
                "solido", "solid", "continuo", "normal" -> "solid"
                "discontinuo", "dashed", "rayas", "guiones" -> "dashed"
                "punteado", "dotted", "puntos" -> "dotted"
                else -> { avisos += "$donde: trazo «$it» desconocido, se usa solido"; null }
            }
        }
        val relleno = palabra("relleno_estilo")?.let {
            when (it) {
                "solido", "solid", "liso", "lleno" -> "solid"
                "rayado", "hachure", "rayas", "achurado" -> "hachure"
                "cruzado", "cross-hatch", "cuadriculado" -> "cross-hatch"
                else -> { avisos += "$donde: relleno_estilo «$it» desconocido, se usa solido"; null }
            }
        }
        var material = palabra("material")?.let {
            when (it) {
                "lisa", "liso", "normal", "tinta" -> null
                "tiza", "chalk" -> "tiza"
                "grafito", "cuadritos", "lapiz", "graphite" -> "cuadritos"
                "lapiz2b", "2b", "carboncillo" -> "lapiz2b"
                "seco", "rotulador", "marcador" -> "seco"
                else -> { avisos += "$donde: material «$it» desconocido, se usa liso"; null }
            }
        }
        val dureza = textoEn(o, "dureza")?.let { d -> dureza(d) ?: run { avisos += "$donde: dureza «$d» desconocida, se usa HB"; null } }
        // «tipo: grafito» o una dureza sin material: es grafito.
        if (tipo == TipoFigura.LAPIZ && material == null && dureza != null) material = "cuadritos"
        // 0.5 es la mitad, no medio por ciento.
        val opacidad = numeroEn(o, "opacidad", donde, 0.0 to 100.0)?.let { if (it > 0.0 && it <= 1.0) it * 100 else it }
        return EstiloFigura(
            grosor = numeroEn(o, "grosor", donde, 0.5 to 40.0),
            trazo = trazo,
            opacidad = opacidad,
            relleno = relleno,
            rugosidad = numeroEn(o, "rugosidad", donde, 0.0 to 3.0),
            material = material,
            dureza = dureza
        )
    }

    /** La dureza de un grafito, a una de las siete del lienzo: «6B», «hb», «3H» (la más cercana). */
    fun dureza(texto: String): String? {
        val t = texto.trim().uppercase()
        when (t) { "HB", "F" -> return "HB"; "H" -> return "2H"; "B" -> return "2B" }
        if (t.isEmpty()) return null
        val letra = t.last()
        val num = t.dropLast(1).toIntOrNull() ?: return null
        return when {
            letra == 'H' && num in 1..2 -> "2H"
            letra == 'H' && num in 3..9 -> "4H"
            letra == 'B' && num in 1..2 -> "2B"
            letra == 'B' && num in 3..4 -> "4B"
            letra == 'B' && num in 5..6 -> "6B"
            letra == 'B' && num in 7..9 -> "8B"
            else -> null
        }
    }

    private fun validarInstrumento(o: JsonObject, donde: String, tipoTxt: String): PedidoInstrumento {
        val porNombre = when (normalizar(tipoTxt)) { "recta", "recta_numerica" -> 1.0; "espacio" -> 3.0; else -> null }
        val ejes = (numeroEn(o, "ejes", donde, 1.0 to 3.0) ?: porNombre)?.let { Math.round(it).toInt() } ?: 2
        val unidad = numeroEn(o, "unidad", donde, 5.0 to 400.0) ?: 40.0
        val tabla = when (val v = noNulo(o, "tabla")) {
            null -> emptyList()
            else -> {
                val ns = numeros(v) ?: throw mal("$donde: «tabla» tiene que ser [[x, y], ...]")
                if (ns.size % 2 != 0) throw mal("$donde: «tabla» tiene un numero impar de valores (van de dos en dos: x, y)")
                if (ns.size / 2 > MAX_TABLA) throw mal("$donde: la tabla pasa de $MAX_TABLA puntos")
                if (ns.any { kotlin.math.abs(it) > 1e6 }) throw mal("$donde: un valor de la tabla es enorme")
                ns.chunked(2).map { it[0] to it[1] }
            }
        }
        return PedidoInstrumento(ejes, unidad, tabla)
    }

    /** Los vértices de una forma dentro de su caja. */
    internal fun formaDeCaja(forma: String, c: DoubleArray): List<Pair<Double, Double>> {
        val (x, y, w, h) = c
        return when (forma) {
            "estrella" -> {
                val cx = x + w / 2; val cy = y + h / 2
                (0 until 10).map { k ->
                    val a = -Math.PI / 2 + k * Math.PI / 5
                    val r = if (k % 2 == 0) 1.0 else 0.4
                    (cx + r * w / 2 * kotlin.math.cos(a)) to (cy + r * h / 2 * kotlin.math.sin(a))
                }
            }
            // Dos ondas de seno a lo ancho de la caja.
            "onda" -> (0..16).map { k ->
                val t = k / 16.0
                (x + t * w) to (y + h / 2 - h / 2 * kotlin.math.sin(t * 4 * Math.PI))
            }
            // Triángulo con la punta arriba.
            else -> listOf(x to y + h, x + w / 2 to y, x + w to y + h)
        }
    }

    /**
     * **Los colores que el modelo olvidó, sacados del pedido.** Medido con el 2B: a «un círculo
     * rojo y un cuadrado azul» contesta con las figuras bien puestas pero sin `color`. Solo toca
     * figuras sin color: nunca corrige lo que el modelo sí puso. También rellena los textos que
     * dejó vacíos con lo que el usuario puso entre comillas.
     */
    fun coloresDelPedido(figuras: List<Figura>, pedido: String): List<Figura> {
        val palabras = pedido.lowercase().split(Regex("[^\\p{L}\\p{N}#]+")).filter { it.isNotEmpty() }
        fun tipoDe(p: String): TipoFigura? = when (p) {
            "circulo", "círculo", "circulos", "círculos", "elipse", "ovalo", "óvalo", "sol", "pelota", "bola", "circle", "ellipse" -> TipoFigura.ELIPSE
            "cuadrado", "cuadrados", "rectangulo", "rectángulo", "caja", "square", "rectangle" -> TipoFigura.RECTANGULO
            "rombo", "diamante", "diamond" -> TipoFigura.ROMBO
            "triangulo", "triángulo", "estrella", "poligono", "polígono", "techo", "tejado", "triangle", "star" -> TipoFigura.POLIGONO
            "curva", "onda", "curve", "wave" -> TipoFigura.CURVA
            "linea", "línea", "raya", "line" -> TipoFigura.LINEA
            "flecha", "arrow" -> TipoFigura.FLECHA
            "titulo", "título", "texto", "letrero", "title", "text" -> TipoFigura.TEXTO
            else -> null
        }
        // (tipo, color) en el orden del pedido: el color en las palabras siguientes.
        val pares = mutableListOf<Pair<TipoFigura, String>>()
        for ((i, p) in palabras.withIndex()) {
            val t = tipoDe(p) ?: continue
            palabras.drop(i + 1).take(4).takeWhile { tipoDe(it) == null }.firstNotNullOfOrNull { color(it) }?.let { pares += t to it }
        }
        val salida = figuras.map { f ->
            if (f.conColor) return@map f
            val i = pares.indexOfFirst { it.first == f.tipo }
            if (i < 0) return@map f
            val c = pares.removeAt(i).second
            val lleno = f.tipo in setOf(TipoFigura.RECTANGULO, TipoFigura.ELIPSE, TipoFigura.ROMBO, TipoFigura.POLIGONO)
            f.copy(color = c, relleno = if (lleno) c else f.relleno, conColor = true)
        }.toMutableList()
        val citas = citasDelPedido(pedido).iterator()
        for (i in salida.indices) {
            val f = salida[i]
            if (f.tipo == TipoFigura.TEXTO && f.texto.isEmpty()) {
                if (!citas.hasNext()) break
                salida[i] = f.copy(texto = citas.next())
            }
        }
        return salida
    }

    /** Lo que va entre comillas en el pedido: «…», "…", “…” o '…'. */
    fun citasDelPedido(pedido: String): List<String> {
        val salida = mutableListOf<String>()
        var i = 0
        while (i < pedido.length) {
            val cierre = when (pedido[i]) { '«' -> '»'; '"' -> '"'; '“' -> '”'; '\'' -> '\''; else -> null }
            if (cierre != null) {
                val fin = pedido.indexOf(cierre, i + 1)
                if (fin >= 0) {
                    val dentro = pedido.substring(i + 1, fin)
                    if (dentro.isNotBlank() && dentro.codePointCount(0, dentro.length) <= MAX_TEXTO) salida += dentro.trim()
                    i = fin + 1
                    continue
                }
            }
            i++
        }
        return salida
    }

    /** `#rgb`, `#rrggbb` o un nombre en español o inglés, a la paleta de Excalidraw (la del lienzo). */
    fun color(texto: String): String? {
        val t = texto.trim().lowercase()
        if (t.startsWith("#")) {
            val h = t.substring(1)
            if (h.isEmpty() || !h.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return when (h.length) {
                6 -> "#$h"
                8 -> "#" + h.substring(0, 6)
                3 -> "#" + h.map { "$it$it" }.joinToString("")
                else -> null
            }
        }
        // «roja», «amarillas», «azules»: el femenino y el plural del pedido.
        val candidatos = mutableListOf(t)
        for (s in listOf("es", "s")) if (t.endsWith(s)) candidatos += t.removeSuffix(s)
        for (c in candidatos.toList()) if (c.endsWith("a")) candidatos += c.removeSuffix("a") + "o"
        return candidatos.firstNotNullOfOrNull { colorPorNombre(it) }
    }

    private fun colorPorNombre(t: String): String? = when (t) {
        "negro", "black" -> "#1e1e1e"
        "blanco", "white" -> "#ffffff"
        "rojo", "red" -> "#e03131"
        "verde", "green" -> "#2f9e44"
        "azul", "blue" -> "#1971c2"
        "amarillo", "yellow" -> "#f08c00"
        "naranja", "orange" -> "#e8590c"
        "morado", "violeta", "purple", "violet" -> "#6741d9"
        "rosa", "pink" -> "#c2255c"
        "gris", "gray", "grey" -> "#868e96"
        "marron", "marrón", "cafe", "café", "brown" -> "#8b5a2b"
        "celeste", "cyan", "cian" -> "#0c8599"
        else -> null
    }

    private fun validarGrafica(args: JsonObject, avisos: MutableList<String>): Grafica {
        val tipo = when (val t = textoEn(args, "tipo")?.lowercase()) {
            "barras", "barra", "bar", "columnas" -> TipoGrafica.BARRAS
            "lineas", "líneas", "linea", "línea", "line" -> TipoGrafica.LINEAS
            "pastel", "torta", "pie", "circular" -> TipoGrafica.PASTEL
            null -> throw mal("falta «tipo» (barras, lineas o pastel)")
            else -> throw mal("«tipo» = «$t» no existe; usa barras, lineas o pastel")
        }
        val valores = (args["valores"] as? JsonArray ?: throw mal("falta «valores» (una lista de numeros)"))
            .mapIndexed { i, v -> numero(v)?.takeIf { kotlin.math.abs(it) < 1e12 } ?: throw mal("«valores»[${i + 1}] no es un numero valido") }
        if (valores.isEmpty() || valores.size > MAX_DATOS_GRAFICA)
            throw mal("«valores» tiene ${valores.size} datos; tiene que tener de 1 a $MAX_DATOS_GRAFICA")
        val etiquetas = when (val e = args["etiquetas"]) {
            is JsonArray -> e.map { v ->
                val p = v as? JsonPrimitive
                if (p != null && p.isString) p.content.trim().let { s -> s.codePoints().limit(60).toArray().let { String(it, 0, it.size) } }
                else v.toString()
            }
            null, JsonNull -> { avisos += "sin «etiquetas»: se numeran"; (1..valores.size).map { it.toString() } }
            else -> throw mal("«etiquetas» tiene que ser una lista de textos")
        }
        if (etiquetas.size != valores.size)
            throw mal("hay ${etiquetas.size} etiquetas y ${valores.size} valores; tiene que haber el mismo numero")
        if (tipo == TipoGrafica.PASTEL) {
            if (valores.any { it < 0 }) throw mal("un pastel no admite valores negativos")
            if (valores.sum() <= 0) throw mal("un pastel necesita algun valor mayor que cero")
        }
        val titulo = textoEn(args, "titulo").orEmpty().let { s -> s.codePoints().limit(120).toArray().let { String(it, 0, it.size) } }
        return Grafica(tipo, titulo, etiquetas, valores)
    }

    private fun validarOcr(args: JsonObject): PeticionOcr {
        val archivo = textoEn(args, "archivo")
        val paginas = textoEn(args, "paginas")?.let { leerPaginas(it) } ?: emptyList()
        val idioma = when (val i = textoEn(args, "idioma")?.lowercase()) {
            null, "auto" -> null
            "es", "en" -> i
            "espanol", "español", "spanish" -> "es"
            "ingles", "inglés", "english" -> "en"
            else -> throw mal("«idioma» = «$i» no existe; usa es, en o auto")
        }
        return PeticionOcr(archivo, paginas, idioma)
    }

    /** `"1-3,5"` → `[(1,3),(5,5)]`. Páginas de 1 a 10 000. */
    fun leerPaginas(texto: String): List<Pair<Int, Int>> {
        val t = texto.trim().lowercase()
        if (t.isEmpty() || t == "todas" || t == "all") return emptyList()
        return t.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { trozo ->
            val partes = trozo.split('-', limit = 2)
            val a = partes[0].trim().toIntOrNull()?.takeIf { it >= 0 } ?: throw mal("«paginas»: «$trozo» no se entiende")
            val b = (if (partes.size == 2) partes[1].trim() else partes[0].trim()).toIntOrNull()?.takeIf { it >= 0 }
                ?: throw mal("«paginas»: «$trozo» no se entiende")
            if (a == 0 || b < a || b > 10_000) throw mal("«paginas»: «$trozo» esta fuera de rango")
            a to b
        }
    }

    /** Minúsculas y sin tildes (`acciones::normalizar`). */
    fun normalizar(s: String): String = s.lowercase().map {
        when (it) { 'á', 'à' -> 'a'; 'é', 'è' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú', 'ü' -> 'u'; 'ñ' -> 'n'; else -> it }
    }.joinToString("")

    /**
     * **Las herramientas de este turno** (lo que el PC hace en `seleccion::para_el_pedido` con las
     * tres que hay aquí): una charla corta («hola», «gracias») no lleva ninguna, para que el
     * modelo conteste con texto en vez de dibujar un saludo.
     */
    fun paraElPedido(pedido: String): List<Herramienta> {
        val q = normalizar(pedido)
        val palabras = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val charla = palabras.size <= 4 &&
            palabras.any { it in setOf("hola", "gracias", "buenas", "adios", "chao", "ok", "vale", "genial", "perfecto") } ||
            "que tal" in q
        return if (charla) emptyList() else catalogo()
    }
}
