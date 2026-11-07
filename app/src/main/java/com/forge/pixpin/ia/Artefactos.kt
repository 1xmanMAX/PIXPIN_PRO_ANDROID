package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * **El asistente que crea cosas** (7-nov-2026, el usuario: «que funcione para crear miniapps en HTML, planos, PDF y
 * más, como lo hace Claude o Claude Code, con un comportamiento agéntico y resultados que se integren con la app»).
 *
 * Son las herramientas de un agente de código, adaptadas al chat: **crear** un archivo entero, **editarlo** cambiando
 * un trozo exacto por otro (sin reescribirlo todo), **leerlo** con sus números de línea para revisarlo, pasar HTML a
 * **PDF** y dibujar un **plano** que sale como DXF. Lo creado entra al chat como un adjunto más («IA») y se abre con
 * los visores de siempre: la miniapp en el visor web, el PDF en el lector, el plano en el de planos.
 *
 * Cada vez que se crea o se cambia algo, la respuesta al modelo lleva una **revisión** ([revisar]): si el HTML salió
 * cortado, si le falta lo que hace falta en un teléfono… Así el modelo se corrige solo antes de acabar, que es lo que
 * hace bueno a un agente: planear, hacer, comprobar y arreglar.
 *
 * Esto es lógica pura (las pruebas la usan sin Android); lo que escribe en el chat es
 * [com.forge.pixpin.asistente.ArtefactosDelChat].
 */
object Artefactos {
    const val CREAR = "crear_archivo"
    const val EDITAR = "editar_archivo"
    const val LEER = "leer_archivo"
    const val PDF = "crear_pdf"
    const val PLANO = "crear_plano"
    val NOMBRES = setOf(CREAR, EDITAR, LEER, PDF, PLANO)

    /** Lo más largo que puede ser un archivo creado (letras). */
    const val MAX_CONTENIDO = 400_000
    /** Lo que se le devuelve al leer: hasta tantas letras de líneas numeradas. */
    const val LETRAS_AL_LEER = 12_000
    /** Los tokens de respuesta en un turno que crea: un HTML entero cabe. */
    const val TOKENS_AL_CREAR = 16_000
    const val MAX_ENTIDADES = 2_000

    /** Los archivos de texto que se pueden crear (por la extensión). */
    val EXTENSIONES = setOf("html", "htm", "svg", "md", "txt", "csv", "json", "js", "css", "xml", "ics", "py", "tsv", "yaml", "yml")

    // ------------------------------------------------------------------------------------ lo que pide el modelo

    data class Cambio(val buscar: String, val poner: String, val todas: Boolean)

    sealed class Pedido {
        data class Crear(val nombre: String, val contenido: String, val descripcion: String) : Pedido()
        data class Editar(val nombre: String, val cambios: List<Cambio>) : Pedido()
        /** [nombre] null: la lista de archivos del chat. */
        data class Leer(val nombre: String?, val desde: Int, val hasta: Int?) : Pedido()
        data class Pdf(val nombre: String, val html: String, val papel: String, val horizontal: Boolean) : Pedido()
        data class Plano(val nombre: String, val plano: Dxf.Plano) : Pedido()
    }

    // ------------------------------------------------------------------------------------ las herramientas

    private fun texto(d: String, max: Int? = null) = buildJsonObject { put("type", "string"); put("description", d); max?.let { put("maxLength", it) } }
    private fun obj(props: Map<String, JsonElement>, obligatorios: List<String>) = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(props),
        "required" to JsonArray(obligatorios.map { JsonPrimitive(it) }),
    ))
    private val punto = buildJsonObject {
        put("type", "array"); put("description", "[x, y]"); put("minItems", 2); put("maxItems", 2)
        put("items", buildJsonObject { put("type", "number") })
    }

    fun herramientas(): List<Herramienta> = listOf(
        Herramienta(
            CREAR,
            "Crea (o reemplaza entero) un archivo de texto y lo deja en el chat, donde se abre con su visor: una miniapp o " +
                "pagina .html (un solo archivo con su CSS y JS dentro: calculadoras, juegos, formularios, presentaciones, " +
                "simuladores), un .svg, un .md, un .csv, un .json, etc. El resultado trae una revision: si dice que algo " +
                "falta o salio cortado, arreglalo con editar_archivo. Para cambiar parte de uno que ya existe usa " +
                "editar_archivo, no lo vuelvas a crear.",
            obj(mapOf(
                "nombre" to texto("Nombre con extension, corto y claro: «calculadora-de-notas.html».", 80),
                "contenido" to texto("El archivo entero.", MAX_CONTENIDO),
                "descripcion" to texto("Una linea de lo que es (va en el chat).", 200),
            ), listOf("nombre", "contenido"))
        ),
        Herramienta(
            EDITAR,
            "Cambia un archivo del chat sustituyendo trozos EXACTOS por otros (como un editor de codigo). Cada «buscar» " +
                "tiene que aparecer tal cual y una sola vez (pon lineas de alrededor para que sea unico) salvo con " +
                "«todas». Los cambios van en orden; si uno falla, no se aplica ninguno. Sirve tambien para los .html " +
                "y .md que el usuario adjunto.",
            obj(mapOf(
                "nombre" to texto("El archivo, por su nombre en el chat.", 120),
                "cambios" to buildJsonObject {
                    put("type", "array"); put("minItems", 1); put("maxItems", 40)
                    put("items", obj(mapOf(
                        "buscar" to texto("El texto exacto que hay ahora.", 40_000),
                        "poner" to texto("Lo que va en su lugar (vacio para borrarlo).", 120_000),
                        "todas" to buildJsonObject { put("type", "boolean"); put("description", "Cambiar todas las veces que aparece") },
                    ), listOf("buscar", "poner")))
                },
            ), listOf("nombre", "cambios"))
        ),
        Herramienta(
            LEER,
            "Lee un archivo de texto del chat (.html, .md, .txt, .csv, .svg, .dxf…) con numeros de linea, para revisarlo " +
                "antes de editarlo. Sin «nombre», lista los archivos del chat. Para lo escaneado o un PDF usa ocr.",
            obj(mapOf(
                "nombre" to texto("El archivo, por su nombre en el chat.", 120),
                "desde" to buildJsonObject { put("type", "integer"); put("description", "Primera linea (desde 1)") },
                "hasta" to buildJsonObject { put("type", "integer"); put("description", "Ultima linea") },
            ), emptyList())
        ),
        Herramienta(
            PDF,
            "Crea un PDF a partir de HTML con su CSS (informes, apuntes, cartas, CV, fichas, examenes, facturas de " +
                "ejemplo) y lo deja en el chat. Usa CSS de impresion: @page, page-break-before para hojas nuevas, " +
                "tablas con bordes. Las imagenes, en data: o https.",
            obj(mapOf(
                "nombre" to texto("Nombre del PDF: «informe-de-laboratorio.pdf».", 80),
                "html" to texto("El documento HTML entero.", MAX_CONTENIDO),
                "papel" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("A4", "carta", "A3", "A5").map { JsonPrimitive(it) })) },
                "horizontal" to buildJsonObject { put("type", "boolean") },
            ), listOf("nombre", "html"))
        ),
        Herramienta(
            PLANO,
            "Dibuja un plano tecnico (plantas, cortes, detalles, terrenos) y lo deja en el chat como DXF, que se abre en " +
                "el visor de planos y en cualquier programa de CAD. Coordenadas en las unidades elegidas, Y hacia " +
                "arriba. Muros con polilineas o rectangulos, puertas con arcos, cotas con «cota» y textos con «texto». " +
                "Usa capas (MUROS, PUERTAS, COTAS, TEXTOS…).",
            obj(mapOf(
                "nombre" to texto("Nombre del plano: «planta-casa.dxf».", 80),
                "unidades" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("m", "cm", "mm").map { JsonPrimitive(it) })) },
                "capas" to buildJsonObject {
                    put("type", "array"); put("maxItems", 60)
                    put("items", obj(mapOf(
                        "nombre" to texto("Nombre de la capa", 40),
                        "color" to buildJsonObject { put("type", "integer"); put("description", "Color de AutoCAD 1-255 (1 rojo, 2 amarillo, 3 verde, 4 cian, 5 azul, 6 magenta, 7 blanco/negro, 8 gris)") },
                    ), listOf("nombre")))
                },
                "entidades" to buildJsonObject {
                    put("type", "array"); put("maxItems", MAX_ENTIDADES)
                    put("items", obj(mapOf(
                        "tipo" to buildJsonObject { put("type", "string"); put("enum", JsonArray(Dxf.TIPOS.map { JsonPrimitive(it) })) },
                        "capa" to texto("La capa", 40),
                        "puntos" to buildJsonObject { put("type", "array"); put("items", punto); put("description", "linea: 2 puntos; polilinea: 2 o mas; cota: los 2 extremos") },
                        "cerrada" to buildJsonObject { put("type", "boolean") },
                        "esquina" to punto, "tam" to punto,
                        "centro" to punto,
                        "radio" to buildJsonObject { put("type", "number") },
                        "desde" to buildJsonObject { put("type", "number"); put("description", "Angulo inicial del arco, en grados") },
                        "hasta" to buildJsonObject { put("type", "number"); put("description", "Angulo final del arco, en grados") },
                        "en" to punto,
                        "texto" to texto("El texto (o el de la cota; vacio: la medida)", 200),
                        "altura" to buildJsonObject { put("type", "number"); put("description", "Altura de la letra") },
                        "separacion" to buildJsonObject { put("type", "number"); put("description", "Cota: distancia de la linea de cota a lo medido") },
                    ), listOf("tipo")))
                },
            ), listOf("nombre", "entidades"))
        ),
    )

    // ------------------------------------------------------------------------------------ validar

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)
    private fun cadena(a: JsonObject, k: String): String? = (a[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun siNo(a: JsonObject, k: String): Boolean = (a[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.content.lowercase() in setOf("si", "sí", "true")) } ?: false
    private fun numero(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.replace(',', '.').toDoubleOrNull() }?.takeIf { it.isFinite() }

    /** Un nombre de archivo que vale: sin carpetas ni signos raros, con [extension] si se pide. */
    fun nombreLimpio(n: String, extension: String? = null): String {
        var s = n.trim().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[^\p{L}\p{N} ._()\-]"""), "_").trim().take(80)
        if (s.isBlank() || s.startsWith(".")) s = "archivo$s"
        if (extension != null && !s.lowercase().endsWith(".$extension")) s = s.substringBeforeLast('.').ifBlank { s } + ".$extension"
        return s
    }

    fun extension(n: String): String = n.substringAfterLast('.', "").lowercase()

    fun validar(nombre: String, a: JsonObject): Pedido = when (nombre) {
        CREAR -> {
            val n = cadena(a, "nombre")?.takeIf { it.isNotBlank() } ?: throw mal("falta «nombre» (con extension: «algo.html»)")
            val limpio = nombreLimpio(n)
            val ext = extension(limpio)
            if (ext == "pdf") throw mal("para un PDF usa crear_pdf con el HTML")
            if (ext == "dxf") throw mal("para un plano usa crear_plano")
            if (ext !in EXTENSIONES) throw mal("«$limpio» no tiene una extension de texto que se pueda crear; usa una de: ${EXTENSIONES.joinToString(", ")}")
            val c = cadena(a, "contenido") ?: throw mal("falta «contenido» (el archivo entero)")
            if (c.isBlank()) throw mal("«contenido» esta vacio")
            if (c.length > MAX_CONTENIDO) throw mal("el archivo pasa de $MAX_CONTENIDO letras: hazlo mas compacto")
            Pedido.Crear(limpio, c, cadena(a, "descripcion").orEmpty().take(200))
        }
        EDITAR -> {
            val n = cadena(a, "nombre")?.takeIf { it.isNotBlank() } ?: throw mal("falta «nombre»")
            val lista = a["cambios"] as? JsonArray ?: throw mal("falta «cambios»: una lista de {buscar, poner}")
            if (lista.isEmpty()) throw mal("«cambios» esta vacia")
            Pedido.Editar(n.trim(), lista.mapIndexed { i, e ->
                val o = e as? JsonObject ?: throw mal("cambios[$i] tiene que ser un objeto {buscar, poner}")
                val b = cadena(o, "buscar") ?: throw mal("falta cambios[$i].buscar")
                if (b.isEmpty()) throw mal("cambios[$i].buscar esta vacio: pon el texto exacto que se cambia")
                Cambio(b, cadena(o, "poner") ?: "", siNo(o, "todas"))
            })
        }
        LEER -> Pedido.Leer(
            cadena(a, "nombre")?.trim()?.takeIf { it.isNotBlank() },
            ((a["desde"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1),
            (a["hasta"] as? JsonPrimitive)?.intOrNull,
        )
        PDF -> {
            val n = nombreLimpio(cadena(a, "nombre")?.takeIf { it.isNotBlank() } ?: "documento", "pdf")
            val h = cadena(a, "html") ?: throw mal("falta «html» (el documento entero)")
            if (h.isBlank()) throw mal("«html» esta vacio")
            if (h.length > MAX_CONTENIDO) throw mal("el HTML pasa de $MAX_CONTENIDO letras")
            val papel = when (cadena(a, "papel")?.lowercase()) { null, "a4" -> "A4"; "carta", "letter" -> "carta"; "a3" -> "A3"; "a5" -> "A5"; else -> throw mal("«papel» tiene que ser A4, carta, A3 o A5") }
            Pedido.Pdf(n, h, papel, siNo(a, "horizontal"))
        }
        PLANO -> Pedido.Plano(nombreLimpio(cadena(a, "nombre")?.takeIf { it.isNotBlank() } ?: "plano", "dxf"), Dxf.validar(a))
        else -> throw ErrorHerramienta.Desconocida(nombre)
    }

    // ------------------------------------------------------------------------------------ editar y leer

    /** **Aplica los cambios en orden**; si uno no encaja, ninguno (el motivo, para el modelo). */
    fun editar(texto: String, cambios: List<Cambio>): Result<Pair<String, Int>> {
        var t = texto
        var hechos = 0
        cambios.forEachIndexed { i, c ->
            val veces = cuantas(t, c.buscar)
            when {
                veces == 0 -> {
                    val pista = parecida(t, c.buscar)?.let { " Lo mas parecido (linea ${it.first}): «${it.second.take(160)}»" }.orEmpty()
                    return Result.failure(IllegalArgumentException("el cambio ${i + 1}: «${c.buscar.take(120)}» no aparece en el archivo (tiene que ser exacto, con sus espacios).$pista Usa leer_archivo para verlo."))
                }
                veces > 1 && !c.todas -> return Result.failure(IllegalArgumentException("el cambio ${i + 1}: «${c.buscar.take(120)}» aparece $veces veces; pon lineas de alrededor para que sea unico, o «todas»: true"))
            }
            t = if (c.todas) t.replace(c.buscar, c.poner) else t.replaceFirst(c.buscar, c.poner)
            hechos += if (c.todas) veces else 1
        }
        return Result.success(t to hechos)
    }

    private fun cuantas(t: String, b: String): Int {
        var n = 0; var i = t.indexOf(b)
        while (i >= 0) { n++; i = t.indexOf(b, i + b.length) }
        return n
    }

    /** La línea del archivo más parecida a la primera línea de lo buscado (para que el modelo vea su error). */
    private fun parecida(t: String, buscado: String): Pair<Int, String>? {
        val primera = buscado.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val palabras = primera.split(Regex("""\W+""")).filter { it.length > 2 }.toSet()
        if (palabras.isEmpty()) return null
        return t.lines().withIndex().map { (i, l) -> Triple(i + 1, l, palabras.count { l.contains(it) }) }
            .filter { it.third > 0 }.maxByOrNull { it.third }?.let { it.first to it.second.trim() }
    }

    /** **Las líneas numeradas** [desde]..[hasta], hasta [LETRAS_AL_LEER] letras, y por dónde seguir. */
    fun numerar(texto: String, desde: Int = 1, hasta: Int? = null, letras: Int = LETRAS_AL_LEER): String {
        val lineas = texto.lines()
        val total = lineas.size
        val ini = desde.coerceIn(1, total.coerceAtLeast(1))
        val fin = (hasta ?: total).coerceIn(ini, total.coerceAtLeast(1))
        val ancho = fin.toString().length
        val s = StringBuilder()
        var ultima = ini - 1
        for (n in ini..fin) {
            val l = lineas.getOrElse(n - 1) { "" }.let { if (it.length > 400) it.take(400) + "…" else it }
            val fila = n.toString().padStart(ancho) + "│ " + l + "\n"
            if (s.length + fila.length > letras && n > ini) break
            s.append(fila); ultima = n
        }
        val cola = if (ultima < total) "(lineas $ini–$ultima de $total: sigue con desde=${ultima + 1})" else "(lineas $ini–$ultima de $total)"
        return s.toString() + cola
    }

    // ------------------------------------------------------------------------------------ revisar

    /**
     * **La revisión que va con cada archivo creado o cambiado**: lo que un buen revisor miraría primero. Vacía si está
     * bien. No ejecuta nada: mira la forma (cortado, sin cerrar, sin lo que necesita un teléfono).
     */
    fun revisar(nombre: String, t: String): List<String> {
        val ext = extension(nombre)
        val r = mutableListOf<String>()
        when (ext) {
            "html", "htm" -> {
                val bajo = t.lowercase()
                if (!bajo.contains("</html>") && bajo.contains("<html")) r += "no termina en </html>: parece cortado; completalo con editar_archivo (o crealo mas compacto)"
                val abren = Regex("<script\\b").findAll(bajo).count(); val cierran = Regex("</script>").findAll(bajo).count()
                if (abren != cierran) r += "hay $abren <script> y $cierran </script>"
                val sAbre = Regex("<style\\b").findAll(bajo).count(); val sCierra = Regex("</style>").findAll(bajo).count()
                if (sAbre != sCierra) r += "hay $sAbre <style> y $sCierra </style>"
                if (!bajo.contains("name=\"viewport\"") && !bajo.contains("name='viewport'") && !bajo.contains("name=viewport"))
                    r += "le falta <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">: en el telefono se vera diminuto"
                if (!bajo.contains("charset")) r += "le falta <meta charset=\"utf-8\"> (las tildes pueden salir mal)"
                if (Regex("""(src|href)\s*=\s*["']http://""").containsMatchIn(bajo)) r += "carga algo por http:// (sin s): no cargara; usa https:// o mételo dentro"
                if (bajo.contains("localstorage") && !bajo.contains("try")) r += "usa localStorage sin try/catch: si falla, la app se rompe"
                if (Regex("""\balert\s*\(""").containsMatchIn(bajo)) r += "usa alert(): en el visor queda feo; muestra el mensaje dentro de la pagina"
                for (b in llavesDesparejadas(guiones(t))) r += b
            }
            "svg" -> {
                if (!t.contains("<svg")) r += "no hay <svg>"
                if (!t.contains("</svg>")) r += "no termina en </svg>: parece cortado"
                if (!t.contains("xmlns")) r += "le falta xmlns=\"http://www.w3.org/2000/svg\""
            }
            "json" -> runCatching { Json.parseToJsonElement(t) }.onFailure { r += "no es JSON valido: ${it.message?.take(120)}" }
            "csv", "tsv" -> {
                val cabeza = t.lineSequence().firstOrNull().orEmpty()
                val sep = if (ext == "tsv") '\t' else if (cabeza.count { it == ';' } > cabeza.count { it == ',' }) ';' else ','
                val cols = t.lines().filter { it.isNotBlank() }.map { l -> columnas(l, sep) }
                val distintas = cols.withIndex().filter { it.value != cols.firstOrNull() }
                if (distintas.isNotEmpty()) r += "la fila ${distintas.first().index + 1} tiene ${distintas.first().value} columnas y la primera ${cols.first()}"
            }
            "js" -> for (b in llavesDesparejadas(t)) r += b
        }
        return r
    }

    private fun columnas(l: String, sep: Char): Int {
        var n = 1; var comillas = false
        for (c in l) { if (c == '"') comillas = !comillas else if (c == sep && !comillas) n++ }
        return n
    }

    /** El JS de dentro de los `<script>` (sin `src`). */
    private fun guiones(html: String): String =
        Regex("""<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE).findAll(html).joinToString("\n") { it.groupValues[1] }

    /**
     * **Llaves, corchetes y paréntesis sin pareja** en un JS, saltando textos, plantillas y comentarios (las expresiones
     * regulares a medias: si hay `/…/` raras puede fallar, por eso solo avisa). Es lo que deja un archivo cortado.
     */
    fun llavesDesparejadas(js: String): List<String> {
        if (js.isBlank()) return emptyList()
        val pila = ArrayDeque<Char>()
        var i = 0
        var dudoso = false
        fun cierre(c: Char) = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
        while (i < js.length) {
            val c = js[i]
            when {
                c == '/' && i + 1 < js.length && js[i + 1] == '/' -> { while (i < js.length && js[i] != '\n') i++ }
                c == '/' && i + 1 < js.length && js[i + 1] == '*' -> { val f = js.indexOf("*/", i + 2); i = if (f < 0) js.length else f + 1 }
                c == '"' || c == '\'' || c == '`' -> {
                    i++
                    while (i < js.length && js[i] != c) { if (js[i] == '\\') i++; i++ }
                }
                c == '/' -> {
                    // ¿Una expresión regular? Si lo anterior no es algo que dé un valor, sí.
                    val antes = js.substring(0, i).trimEnd().lastOrNull()
                    if (antes == null || antes in "(,=:[!&|?{};+-*%<>~^") {
                        i++
                        var enClase = false
                        while (i < js.length && js[i] != '\n') {
                            val d = js[i]
                            if (d == '\\') i++ else if (d == '[') enClase = true else if (d == ']') enClase = false else if (d == '/' && !enClase) break
                            i++
                        }
                        if (i >= js.length || js[i] == '\n') dudoso = true
                    }
                }
                c in "([{" -> pila.addLast(c)
                c in ")]}" -> {
                    if (pila.isEmpty() || pila.last() != cierre(c)) {
                        return if (dudoso) emptyList() else listOf("en el JavaScript sobra o no encaja un «$c» (cerca de: «${js.substring((i - 40).coerceAtLeast(0), i + 1).replace('\n', ' ').trim()}»)")
                    }
                    pila.removeLast()
                }
            }
            i++
        }
        if (pila.isNotEmpty() && !dudoso) return listOf("en el JavaScript quedan ${pila.size} «${pila.joinToString("")}» sin cerrar: parece cortado o le falta un cierre")
        return emptyList()
    }

    // ------------------------------------------------------------------------------------ cuándo crear

    private val PIDE_CREAR = Regex(
        """\b(crea|creame|créame|hazme|haz|genera|generame|genérame|programa|construye|diseña|disena|arma|desarrolla|escribe|redacta|prepara)\b[\s\S]{0,80}\b(app|aplicaci[oó]n|miniapp|mini app|web|p[aá]gina|html|juego|calculadora|simulador|formulario|presentaci[oó]n|landing|dashboard|tablero|pdf|informe|reporte|documento|cv|curr[ií]culum|carta|plano|planta|dxf|svg|ficha|examen|cuestionario|quiz|herramienta|programa|script|c[oó]digo)\b""",
        RegexOption.IGNORE_CASE
    )

    /** **Si el pedido es de crear algo** (una app, un PDF, un plano): entonces el turno usa el modelo fuerte. */
    fun pideCrear(pedido: String): Boolean = PIDE_CREAR.containsMatchIn(pedido) ||
        Regex("""\b(miniapp|crear_archivo|editar_archivo|\.html|\.dxf)\b""", RegexOption.IGNORE_CASE).containsMatchIn(pedido)

    /**
     * **Cómo trabaja un buen agente al crear** (lo que hacen Claude Code y los agentes de código abiertos: OpenHands,
     * Cline, Aider, opencode): plan corto, hacerlo entero, revisar y corregir, y contar lo hecho en dos líneas.
     */
    val INSTRUCCIONES: String = """
        |
        |## Crear cosas (miniapps, PDF, planos, archivos)
        |Puedes crear archivos de verdad, que quedan en el chat y se abren en los visores de PinDesk. Trabaja como un agente:
        |1. Si es algo grande, apunta los pasos con pasos_del_pedido (3-6 pasos). Si falta un dato clave pregunta UNA vez; si no, asume lo razonable y dilo.
        |2. Hazlo entero y que funcione: nada de «aqui iria…», ni datos de relleno tipo lorem ipsum cuando el usuario dio los suyos.
        |3. Revisa el resultado: cada crear/editar te devuelve una revision; si marca algo, arreglalo con editar_archivo (no recrees el archivo entero por un detalle). Si dudas de una parte, leer_archivo.
        |4. Al acabar, en 2-4 lineas: que hiciste, como se usa y una idea para mejorarlo. Sin pegar el codigo en el mensaje: ya esta en el archivo.
        |
        |Miniapps .html (con crear_archivo): un solo archivo con su <style> y <script>; <meta charset="utf-8"> y <meta name="viewport" content="width=device-width, initial-scale=1">.
        |Pensadas para el telefono: botones de 44px o mas, letra de 16px o mas, sin scroll de lado, modo oscuro con prefers-color-scheme, diseño limpio y moderno (variables CSS, buen espaciado, bordes redondeados).
        |Que guarden su estado con localStorage dentro de try/catch. Sin alert()/prompt(): todo dentro de la pagina. Sin librerias si no hacen falta; si una ayuda mucho (graficas, 3D), desde https://cdn.jsdelivr.net o https://cdnjs.cloudflare.com con version fija (necesita internet).
        |PDF (crear_pdf): HTML pensado para papel, con @page { margin: 18mm }, titulos claros, tablas con bordes finos y page-break-before para hojas nuevas.
        |Planos (crear_plano): medidas reales en las unidades elegidas, capas con nombre, muros con su grosor (dos lineas o rectangulos), puertas con su arco, cotas de lo importante y el nombre de cada ambiente con su area.
        |Para cambiar un archivo que ya existe (tuyo o adjunto por el usuario): editar_archivo con trozos exactos.
        """.trimMargin()
}

/**
 * **Un plano en DXF R12** (AC1009, ASCII): lo leen el visor de planos de la app y cualquier programa de CAD. Solo
 * entidades sencillas y universales: LINE, POLYLINE, CIRCLE, ARC y TEXT; las cotas se dibujan con líneas, marcas y su
 * texto (no la entidad DIMENSION, que cada programa entiende a su manera).
 */
object Dxf {
    val TIPOS = listOf("linea", "polilinea", "rectangulo", "circulo", "arco", "texto", "cota")

    data class Capa(val nombre: String, val color: Int)
    data class Entidad(
        val tipo: String, val capa: String,
        val puntos: List<Pair<Double, Double>> = emptyList(), val cerrada: Boolean = false,
        val centro: Pair<Double, Double>? = null, val radio: Double = 0.0, val desde: Double = 0.0, val hasta: Double = 360.0,
        val texto: String = "", val altura: Double = 0.0, val separacion: Double = 0.0,
    )
    data class Plano(val unidades: String, val capas: List<Capa>, val entidades: List<Entidad>)

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)
    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.replace(',', '.').toDoubleOrNull() }?.takeIf { it.isFinite() }
    private fun pt(e: JsonElement?): Pair<Double, Double>? {
        val a = e as? JsonArray
        if (a != null && a.size >= 2) { val x = num(a[0]); val y = num(a[1]); if (x != null && y != null) return x to y }
        val o = e as? JsonObject ?: return null
        val x = num(o["x"]); val y = num(o["y"])
        return if (x != null && y != null) x to y else null
    }
    private fun capaLimpia(s: String?) = (s ?: "0").uppercase().replace(Regex("""[^A-Z0-9_\-]"""), "_").take(31).ifBlank { "0" }

    fun validar(a: JsonObject): Plano {
        val unidades = when ((a["unidades"] as? JsonPrimitive)?.content?.lowercase()) { null, "m" -> "m"; "cm" -> "cm"; "mm" -> "mm"; else -> throw mal("«unidades» tiene que ser m, cm o mm") }
        val capas = (a["capas"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Capa(capaLimpia((o["nombre"] as? JsonPrimitive)?.content), ((o["color"] as? JsonPrimitive)?.intOrNull ?: 7).coerceIn(1, 255))
        }.distinctBy { it.nombre }
        val lista = a["entidades"] as? JsonArray ?: throw mal("falta «entidades»")
        if (lista.isEmpty()) throw mal("«entidades» esta vacia")
        if (lista.size > Artefactos.MAX_ENTIDADES) throw mal("mas de ${Artefactos.MAX_ENTIDADES} entidades: divide el plano")
        val ents = lista.mapIndexed { i, e ->
            val o = e as? JsonObject ?: throw mal("entidades[$i] tiene que ser un objeto")
            val tipo = (o["tipo"] as? JsonPrimitive)?.content?.lowercase()?.let { if (it == "línea") "linea" else if (it == "círculo") "circulo" else if (it == "rectángulo") "rectangulo" else it }
                ?: throw mal("falta entidades[$i].tipo")
            val capa = capaLimpia((o["capa"] as? JsonPrimitive)?.content)
            val puntos = (o["puntos"] as? JsonArray).orEmpty().map { pt(it) ?: throw mal("entidades[$i].puntos: cada punto es [x, y]") }
            when (tipo) {
                "linea" -> { if (puntos.size < 2) throw mal("entidades[$i]: una linea necesita 2 puntos"); Entidad("polilinea", capa, puntos.take(2)) }
                "polilinea" -> { if (puntos.size < 2) throw mal("entidades[$i]: una polilinea necesita 2 puntos o mas"); Entidad("polilinea", capa, puntos, (o["cerrada"] as? JsonPrimitive)?.booleanOrNull == true) }
                "rectangulo" -> {
                    val (x, y) = pt(o["esquina"]) ?: puntos.getOrNull(0) ?: throw mal("entidades[$i]: el rectangulo necesita «esquina» [x, y] y «tam» [ancho, alto]")
                    val (w, h) = pt(o["tam"]) ?: puntos.getOrNull(1)?.let { it.first - x to it.second - y } ?: throw mal("entidades[$i]: falta «tam» [ancho, alto]")
                    Entidad("polilinea", capa, listOf(x to y, x + w to y, x + w to y + h, x to y + h), cerrada = true)
                }
                "circulo" -> Entidad("circulo", capa, centro = pt(o["centro"]) ?: throw mal("entidades[$i]: falta «centro»"),
                    radio = num(o["radio"])?.takeIf { it > 0 } ?: throw mal("entidades[$i]: falta «radio» (mayor que 0)"))
                "arco" -> Entidad("arco", capa, centro = pt(o["centro"]) ?: throw mal("entidades[$i]: falta «centro»"),
                    radio = num(o["radio"])?.takeIf { it > 0 } ?: throw mal("entidades[$i]: falta «radio»"),
                    desde = num(o["desde"]) ?: 0.0, hasta = num(o["hasta"]) ?: 90.0)
                "texto" -> Entidad("texto", capa, listOf(pt(o["en"]) ?: puntos.firstOrNull() ?: throw mal("entidades[$i]: el texto necesita «en» [x, y]")),
                    texto = (o["texto"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: throw mal("entidades[$i]: falta «texto»"),
                    altura = num(o["altura"])?.takeIf { it > 0 } ?: 0.0)
                "cota" -> { if (puntos.size < 2) throw mal("entidades[$i]: la cota necesita sus 2 extremos en «puntos»")
                    Entidad("cota", capa, puntos.take(2), texto = (o["texto"] as? JsonPrimitive)?.content.orEmpty(), altura = num(o["altura"]) ?: 0.0, separacion = num(o["separacion"]) ?: 0.0) }
                else -> throw mal("entidades[$i].tipo «$tipo» no existe; usa ${TIPOS.joinToString(", ")}")
            }
        }
        return Plano(unidades, capas, ents)
    }

    /** El recuadro de todo el plano (para la altura de letra por defecto y el resumen). */
    fun limites(p: Plano): DoubleArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        fun ver(x: Double, y: Double) { x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) }
        for (e in p.entidades) {
            e.puntos.forEach { ver(it.first, it.second) }
            e.centro?.let { ver(it.first - e.radio, it.second - e.radio); ver(it.first + e.radio, it.second + e.radio) }
        }
        if (x0 > x1) return doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        return doubleArrayOf(x0, y0, x1, y1)
    }

    private fun f(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() + ".0" else "%.6f".format(java.util.Locale.ROOT, v).trimEnd('0')
    private fun ascii(s: String): String = buildString {
        for (c in s) if (c.code in 32..126) append(c) else append("\\U+%04X".format(c.code))
    }

    /** **El DXF entero**, en texto. */
    fun escribir(p: Plano): String {
        val lim = limites(p)
        val diag = kotlin.math.hypot(lim[2] - lim[0], lim[3] - lim[1]).coerceAtLeast(1e-6)
        val letra = diag / 80
        val s = StringBuilder()
        fun g(codigo: Int, valor: String) { s.append(codigo).append('\n').append(valor).append('\n') }
        val capas = (p.capas + p.entidades.map { it.capa }.distinct().filter { c -> p.capas.none { it.nombre == c } }.map { Capa(it, 7) })
            .let { l -> if (l.none { it.nombre == "0" }) listOf(Capa("0", 7)) + l else l }
        g(0, "SECTION"); g(2, "HEADER")
        g(9, "\$ACADVER"); g(1, "AC1009")
        g(9, "\$INSUNITS"); g(70, when (p.unidades) { "mm" -> "4"; "cm" -> "5"; else -> "6" })
        g(9, "\$EXTMIN"); g(10, f(lim[0])); g(20, f(lim[1])); g(30, "0.0")
        g(9, "\$EXTMAX"); g(10, f(lim[2])); g(20, f(lim[3])); g(30, "0.0")
        g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "TABLES")
        g(0, "TABLE"); g(2, "LTYPE"); g(70, "1")
        g(0, "LTYPE"); g(2, "CONTINUOUS"); g(70, "0"); g(3, "Solid line"); g(72, "65"); g(73, "0"); g(40, "0.0")
        g(0, "ENDTAB")
        g(0, "TABLE"); g(2, "LAYER"); g(70, capas.size.toString())
        for (c in capas) { g(0, "LAYER"); g(2, c.nombre); g(70, "0"); g(62, c.color.toString()); g(6, "CONTINUOUS") }
        g(0, "ENDTAB")
        g(0, "TABLE"); g(2, "STYLE"); g(70, "1")
        g(0, "STYLE"); g(2, "STANDARD"); g(70, "0"); g(40, "0.0"); g(41, "1.0"); g(50, "0.0"); g(71, "0"); g(42, f(letra)); g(3, "txt"); g(4, "")
        g(0, "ENDTAB")
        g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "ENTITIES")
        fun linea(capa: String, a: Pair<Double, Double>, b: Pair<Double, Double>) {
            g(0, "LINE"); g(8, capa); g(10, f(a.first)); g(20, f(a.second)); g(30, "0.0"); g(11, f(b.first)); g(21, f(b.second)); g(31, "0.0")
        }
        fun texto(capa: String, en: Pair<Double, Double>, t: String, alto: Double, angulo: Double = 0.0, centrado: Boolean = false) {
            g(0, "TEXT"); g(8, capa); g(10, f(en.first)); g(20, f(en.second)); g(30, "0.0"); g(40, f(alto)); g(1, ascii(t))
            if (angulo != 0.0) g(50, f(angulo))
            if (centrado) { g(72, "1"); g(11, f(en.first)); g(21, f(en.second)); g(31, "0.0") }
        }
        for (e in p.entidades) when (e.tipo) {
            "polilinea" -> if (e.puntos.size == 2 && !e.cerrada) linea(e.capa, e.puntos[0], e.puntos[1]) else {
                g(0, "POLYLINE"); g(8, e.capa); g(66, "1"); g(10, "0.0"); g(20, "0.0"); g(30, "0.0"); g(70, if (e.cerrada) "1" else "0")
                for ((x, y) in e.puntos) { g(0, "VERTEX"); g(8, e.capa); g(10, f(x)); g(20, f(y)); g(30, "0.0") }
                g(0, "SEQEND"); g(8, e.capa)
            }
            "circulo" -> { val c = e.centro!!; g(0, "CIRCLE"); g(8, e.capa); g(10, f(c.first)); g(20, f(c.second)); g(30, "0.0"); g(40, f(e.radio)) }
            "arco" -> { val c = e.centro!!; g(0, "ARC"); g(8, e.capa); g(10, f(c.first)); g(20, f(c.second)); g(30, "0.0"); g(40, f(e.radio)); g(50, f(e.desde)); g(51, f(e.hasta)) }
            "texto" -> texto(e.capa, e.puntos[0], e.texto, if (e.altura > 0) e.altura else letra)
            "cota" -> {
                val (a, b) = e.puntos[0] to e.puntos[1]
                val dx = b.first - a.first; val dy = b.second - a.second
                val largo = kotlin.math.hypot(dx, dy)
                if (largo > 0) {
                    val ux = dx / largo; val uy = dy / largo
                    val nx = -uy; val ny = ux
                    val alto = if (e.altura > 0) e.altura else letra
                    val sep = if (e.separacion != 0.0) e.separacion else alto * 2
                    val a2 = a.first + nx * sep to a.second + ny * sep
                    val b2 = b.first + nx * sep to b.second + ny * sep
                    // Las líneas de referencia, la de cota y sus marcas a 45°.
                    linea(e.capa, a, a.first + nx * (sep + alto / 2) to a.second + ny * (sep + alto / 2))
                    linea(e.capa, b, b.first + nx * (sep + alto / 2) to b.second + ny * (sep + alto / 2))
                    linea(e.capa, a2, b2)
                    val m = alto / 2
                    for (q in listOf(a2, b2)) linea(e.capa, q.first - (ux + nx) * m / 1.4 to q.second - (uy + ny) * m / 1.4, q.first + (ux + nx) * m / 1.4 to q.second + (uy + ny) * m / 1.4)
                    var ang = Math.toDegrees(kotlin.math.atan2(dy, dx))
                    if (ang > 90 || ang < -90) ang += 180.0
                    val medio = (a2.first + b2.first) / 2 + nx * alto * 0.7 to (a2.second + b2.second) / 2 + ny * alto * 0.7
                    val dicho = e.texto.ifBlank { medida(largo, p.unidades) }
                    texto(e.capa, medio, dicho, alto, ang, centrado = true)
                }
            }
        }
        g(0, "ENDSEC"); g(0, "EOF")
        return s.toString()
    }

    /** «3.45» (en metros, con dos decimales), «345» (cm), «3450» (mm). */
    fun medida(v: Double, unidades: String): String = when (unidades) {
        "m" -> "%.2f".format(java.util.Locale.ROOT, v)
        else -> "%.0f".format(java.util.Locale.ROOT, v)
    }

    /** Lo que se le cuenta al modelo del plano hecho: cuánto mide y qué lleva, para que lo compruebe. */
    fun resumen(p: Plano): String {
        val l = limites(p)
        val cuantas = p.entidades.groupingBy { it.tipo }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
        return "mide ${medida(l[2] - l[0], p.unidades)} × ${medida(l[3] - l[1], p.unidades)} ${p.unidades} (de ${medida(l[0], p.unidades)},${medida(l[1], p.unidades)} a ${medida(l[2], p.unidades)},${medida(l[3], p.unidades)}); " +
            "$cuantas; capas: ${(p.entidades.map { it.capa }.distinct()).joinToString(", ")}"
    }
}
