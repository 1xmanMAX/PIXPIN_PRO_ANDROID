package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.TextAlign
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
import kotlin.math.abs
import kotlin.math.max

/**
 * **`editar_texto`: cambiar lo que dicen los textos que ya hay** (`edicion/textos.rs` del PC,
 * 22-oct-2026; portado el 25-oct-2026). Pedido del usuario: «traduce esa figura a chino» y el
 * asistente contestaba que no tenía herramienta. Ahora es UNA llamada con la lista
 * `[{ref, texto}]`: cada texto (o la caja que lo lleva de rótulo) cambia lo que dice y nada más
 * —sitio, letra, color y alineación se quedan—, en un solo paso de deshacer.
 *
 * Lo que hace falta para que no quede peor que antes, como en el PC:
 * - **Se vuelve a medir** ([LienzoDestino.medir]): un chino es más corto que su español y un
 *   alemán más largo.
 * - **Lo centrado sigue centrado** y lo de la derecha sigue pegado a la derecha; un texto suelto
 *   centrado a ojo encima de una caja (como los dibuja `dibujar_en_lienzo`) también.
 * - **El rótulo de una caja** se parte a lo ancho de su hueco.
 * - **Si ya no cabe, se dice** (y con «ajustar» se encoge la letra o se parte en renglones).
 *
 * El nombre, el esquema y los textos de los resultados y errores son los del PC (`edicion.rs`,
 * fila `EDITAR_TEXTO`). Los `#id` son los del teléfono ([LienzoDestino]). Sin Android: trabaja
 * sobre la lista de [Element]; quien llama guarda el resultado en un solo paso.
 */
object EditarTexto {
    const val NOMBRE = "editar_texto"

    /** La letra más pequeña a la que se encoge un texto para que quepa: por debajo ya no se lee. */
    const val TAM_MINIMO = 8.0
    /** Cuántos textos como mucho devuelve «listar» de una vez. */
    private const val MAX_LISTA = 120
    /** Cuántos cambios como mucho en una llamada (una figura grande traducida entera cabe). */
    const val MAX_CAMBIOS = 300
    /** Cuántas letras como mucho en un texto nuevo. */
    const val MAX_LETRAS_TEXTO = 4000

    private const val DESCRIPCION = "Cambia lo que DICEN los textos que ya estan en el lienzo (tambien los rotulos dentro de cajas) sin moverlos ni cambiar su letra, color o alineacion: traducir, corregir, renombrar. Para traducir o corregir una figura entera manda TODOS sus textos en UNA llamada con «cambios»; cada caja se vuelve a medir y lo centrado sigue centrado, y el resultado dice que #id cambiaron y cuales ya no caben. Con «ajustar» encoge la letra o parte renglones para que quepa en su caja; con accion «listar» devuelve el texto completo y el #id de cada texto (usala si el resumen no los trae todos). No la uses para crear textos nuevos (dibujar_en_lienzo) ni para el tamano o el color (cambiar_estilo). Ejemplo: {\"cambios\": [{\"ref\": \"#3\", \"texto\": \"开始\"}, {\"ref\": \"#5\", \"texto\": \"结束\"}], \"ajustar\": \"auto\"}"

    private val ACCIONES = listOf("cambiar", "ajustar", "listar")
    private val AJUSTES = listOf("no", "encoger", "partir", "auto")

    /** **El esquema**, como lo arma `edicion::herramienta` del PC para su fila. */
    fun herramienta(): Herramienta {
        fun texto(d: String) = buildJsonObject { put("type", "string"); put("description", d) }
        fun opciones(o: List<String>, d: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(o.map { JsonPrimitive(it) })); put("description", d) }
        fun numero(d: String) = buildJsonObject { put("type", "number"); put("description", d) }
        val p = buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { })
            put("properties", buildJsonObject {
                put("cambios", buildJsonObject {
                    put("type", "array"); put("maxItems", MAX_CAMBIOS)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", buildJsonArray { add(JsonPrimitive("ref")); add(JsonPrimitive("texto")) })
                        put("properties", buildJsonObject {
                            put("ref", texto("#id del texto o de la caja que lo lleva"))
                            put("texto", texto("lo que pasa a decir"))
                        })
                    })
                    put("description", "[{\"ref\": \"#3\", \"texto\": \"nuevo\"}, ...]: cada texto (o caja con rotulo) por su #id y lo que pasa a decir, completo")
                })
                put("que", texto("un solo texto (con «texto»), o lo que se ajusta o se lista: «#3», «todo», «los textos»"))
                put("texto", texto("con «que»: lo que pasa a decir"))
                put("accion", opciones(ACCIONES, "omitir: cambiar si hay «cambios» o «texto», ajustar si solo hay «ajustar», listar si no"))
                put("ajustar", opciones(AJUSTES, "si no cabe en su caja (o en «ancho_max»): encoger la letra, partir en renglones, o auto (partir y, si aun no cabe, encoger). Omitir: no se toca y se avisa"))
                put("ancho_max", numero("con «ajustar»: el ancho maximo en px (omitir: el de la caja donde esta)"))
                put("desde", numero("listar: saltarse los primeros N (para listas largas)"))
            })
        }
        return Herramienta(NOMBRE, DESCRIPCION, ordenado(p) as JsonObject)
    }

    // ------------------------------------------------------------------------------- validar

    /** Una llamada ya validada: los campos limpios (`cambios` como `[(ref, texto)]`). */
    data class Edicion(
        val cambios: List<Pair<String, String>>?,
        val que: String?,
        val texto: String?,
        val accion: String?,
        val ajustar: String?,
        val anchoMax: Double?,
        val desde: Double?
    )

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)

    private fun textoLargo(s: String): String {
        val t = s.trim()
        return if (t.codePointCount(0, t.length) <= MAX_LETRAS_TEXTO) t else t.substring(0, t.offsetByCodePoints(0, MAX_LETRAS_TEXTO))
    }

    private const val FORMA_DE_CAMBIOS = "«cambios» tiene que ser una lista [{\"ref\": \"#3\", \"texto\": \"...\"}]"

    private fun numeroComoTexto(p: JsonPrimitive): String = p.doubleOrNull?.let { Herramientas.n(it) } ?: p.content

    /** **Los cambios como los mande el modelo**, a `[(ref, texto)]` (`cambios_de` del PC). */
    internal fun cambiosDe(v: JsonElement): List<Pair<String, String>> {
        fun comoRef(x: JsonElement?): String? {
            val p = x as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            if (!p.isString) return p.doubleOrNull?.let { "#" + numeroComoTexto(p) }
            val t = p.content.trim().takeIf { it.isNotEmpty() } ?: return null
            return if (t.all { it in '0'..'9' }) "#$t" else t
        }
        fun textoDe(x: JsonElement?): String? = (x as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::textoLargo)
        val lista: List<Pair<String?, String?>> = when (v) {
            is JsonPrimitive -> {
                if (!v.isString) throw mal(FORMA_DE_CAMBIOS)
                val dentro = runCatching { Json.parseToJsonElement(v.content.trim()) }.getOrNull() ?: throw mal(FORMA_DE_CAMBIOS)
                if (dentro is JsonPrimitive) throw mal(FORMA_DE_CAMBIOS)
                return cambiosDe(dentro)
            }
            is JsonArray -> v.map { x ->
                val o = x as? JsonObject
                val r = listOf("ref", "que", "id", "referencia").firstNotNullOfOrNull { o?.get(it) }?.let(::comoRef)
                val t = listOf("texto", "text", "nuevo").firstNotNullOfOrNull { o?.get(it) }?.let(::textoDe)
                r to t
            }
            is JsonObject -> v.entries.map { (k, x) -> comoRef(JsonPrimitive(k)) to textoDe(x) }
        }
        if (lista.size > MAX_CAMBIOS) throw mal("«cambios» trae ${lista.size} y caben $MAX_CAMBIOS: partelos en dos llamadas")
        val salida = ArrayList<Pair<String, String>>(lista.size)
        lista.forEachIndexed { i, (r, t) ->
            val ref = r ?: throw mal("al cambio ${i + 1} le falta «ref» (el #id del texto)")
            val tx = t?.takeIf { it.isNotEmpty() }
                ?: throw mal("al cambio ${i + 1} ($ref) le falta «texto» o esta vacio; para borrar un texto usa transformar_elementos con accion borrar")
            salida += ref to tx
        }
        if (salida.isEmpty()) throw mal("«cambios» esta vacio: pon al menos uno, o usa accion «listar» para ver los textos")
        return salida
    }

    /** **Valida la llamada** contra su fila: tipos y opciones; lo que no está en la fila se ignora. */
    fun validar(args: JsonObject): Edicion {
        fun presente(k: String) = args[k]?.takeIf { it !is JsonNull }
        fun referencia(k: String): String? = when (val v = presente(k)) {
            null -> null
            is JsonPrimitive -> if (v.isString) v.content.trim().takeIf { it.isNotEmpty() }?.let { s -> if (s.codePointCount(0, s.length) > 300) s.substring(0, s.offsetByCodePoints(0, 300)) else s }
            else if (v.booleanOrNull == null) "#" + numeroComoTexto(v) else null
            is JsonArray -> v.takeIf { it.isNotEmpty() }?.joinToString(" ") { x ->
                val p = x as? JsonPrimitive
                when {
                    p == null -> x.toString()
                    p.isString -> p.content
                    else -> "#" + numeroComoTexto(p)
                }
            }
            else -> null
        }
        fun largo(k: String): String? = when (val v = presente(k)) {
            is JsonPrimitive -> if (v.isString) v.content.trim().takeIf { it.isNotEmpty() }?.let(::textoLargo) else if (v.booleanOrNull == null) v.content else null
            else -> null
        }
        fun numero(k: String): Double? {
            val v = presente(k) ?: return null
            return Herramientas.numero(v)?.takeIf { it.isFinite() } ?: throw mal("«$k» tiene que ser un numero")
        }
        fun opcion(k: String, o: List<String>): String? {
            val v = (presente(k) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val t = Acciones.normalizar(v.trim()).replace(' ', '_')
            return o.firstOrNull { Acciones.normalizar(it) == t }
        }
        return Edicion(
            cambios = presente("cambios")?.let(::cambiosDe),
            que = referencia("que"),
            texto = largo("texto"),
            accion = opcion("accion", ACCIONES),
            ajustar = opcion("ajustar", AJUSTES),
            anchoMax = numero("ancho_max"),
            desde = numero("desde")
        )
    }

    // ------------------------------------------------------------------------------- referencias

    private val COLORES = mapOf(
        "rojo" to "rojo", "roja" to "rojo", "azul" to "azul", "verde" to "verde", "amarillo" to "amarillo", "amarilla" to "amarillo",
        "naranja" to "naranja", "morado" to "morado", "morada" to "morado", "rosa" to "rosa", "gris" to "gris", "marron" to "marron",
        "negro" to "negro", "negra" to "negro", "blanco" to "blanco", "blanca" to "blanco", "celeste" to "celeste"
    )

    private fun colorDePalabra(p: String): String? = COLORES[p] ?: COLORES[p.removeSuffix("s")] ?: COLORES[p.removeSuffix("es")]

    private fun tipoDePalabra(p0: String): ((Element) -> Boolean)? {
        var raiz = p0.trimEnd('s')
        if (raiz.endsWith("e") && (raiz.dropLast(1).endsWith("l") || raiz.dropLast(1).endsWith("n"))) raiz = raiz.dropLast(1)
        return when (raiz) {
            "recta", "linea", "raya", "segmento" -> { e -> e.type == ElementType.LINE || e.type == ElementType.MEASURE }
            "flecha" -> { e -> e.type == ElementType.ARROW }
            "circulo", "circunferencia", "elipse", "ovalo" -> { e -> e.type == ElementType.ELLIPSE }
            "rectangulo", "cuadrado", "caja", "recuadro" -> { e -> e.type == ElementType.RECTANGLE }
            "rombo" -> { e -> e.type == ElementType.DIAMOND }
            "texto", "titulo", "letrero", "palabra", "frase", "etiqueta", "rotulo", "resultado" -> { e -> e.type == ElementType.TEXT }
            "imagen", "image", "foto", "fotografia", "captura" -> { e -> e.type == ElementType.IMAGE }
            else -> null
        }
    }

    private fun citado(q: String): String? {
        for ((abre, cierra) in listOf('"' to '"', '«' to '»', '“' to '”', '\'' to '\'')) {
            val i = q.indexOf(abre).takeIf { it >= 0 } ?: continue
            val resto = q.substring(i + 1)
            val j = resto.indexOf(cierra).takeIf { it >= 0 } ?: continue
            resto.substring(0, j).trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (marca in listOf("que dice ", "que pone ", "texto:", "con el texto ")) {
            val i = q.indexOf(marca).takeIf { it >= 0 } ?: continue
            q.substring(i + marca.length).trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** Los `#n` (o números sueltos) de una referencia. */
    private fun idsEscritos(q: String): List<Int> = q.split(Regex("[^A-Za-z0-9#]+"))
        .map { it.trimStart('#') }
        .filter { it.isNotEmpty() && it.length < 9 && it.all { c -> c in '0'..'9' } }
        .map { it.toInt() }

    /** Lo que encajó con una referencia (en la escena) y si hablaba en plural. */
    data class Encontrado(val elementos: List<Element>, val plural: Boolean)

    /**
     * **Resuelve una referencia** (`referencia::resolver` del PC, lo que vale para textos): `#id`
     * (uno o varios), «lo último», «todo», o una descripción (tipo, color, texto citado, «el
     * título», un extremo: «de arriba», «el más grande»). Si encajan varios donde hace falta uno,
     * quien llama da la lista corta: nunca se elige al azar. El fallo lleva el motivo.
     */
    fun resolver(elementos: List<Element>, que: String, ultimos: List<String> = emptyList()): Result<Encontrado> = runCatching {
        val vivos = LienzoDestino.vivos(elementos).filter { !it.reference }
        if (vivos.isEmpty()) throw IllegalStateException("el lienzo esta vacio: no hay nada que elegir")
        val q = Acciones.normalizar(que.trim())
        fun loQueHay() = LienzoDestino.listar(elementos, vivos.takeLast(12).reversed())
        val escritos = idsEscritos(q)
        val soloNumeros = q.split(Regex("[\\s,y]+")).filter { it.isNotEmpty() }.all { t -> t.trimStart('#').all { it in '0'..'9' } }
        if (escritos.isNotEmpty() && (q.contains('#') || soloNumeros)) {
            val faltan = escritos.filter { LienzoDestino.porNumero(elementos, it) == null }
            if (faltan.isNotEmpty()) throw IllegalStateException("no existe ${faltan.joinToString(", ") { "#$it" }} en el lienzo. Lo que hay:\n${loQueHay()}")
            return@runCatching Encontrado(escritos.mapNotNull { LienzoDestino.porNumero(elementos, it) }, escritos.size > 1)
        }
        val palabras = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        fun dice(w: String) = w in palabras
        val pluralUltimo = dice("ultimos") || dice("ultimas")
        if ((dice("ultimo") || dice("ultima") || pluralUltimo) && palabras.none { tipoDePalabra(it) != null }) {
            val us = ultimos.mapNotNull { id -> vivos.firstOrNull { it.id == id } }
            if (us.isNotEmpty()) return@runCatching Encontrado(us, us.size > 1)
            return@runCatching Encontrado(listOf(vivos.last()), false)
        }
        val tipos = palabras.mapNotNull(::tipoDePalabra)
        val colores = palabras.mapNotNull(::colorDePalabra)
        val todos = dice("todo") || dice("todos") || dice("todas")
        val cita = citado(que)?.let(Acciones::normalizar)
        if (todos && tipos.isEmpty() && colores.isEmpty() && cita == null) return@runCatching Encontrado(vivos, true)
        val plural = todos || palabras.any { it.length > 3 && it.endsWith('s') && tipoDePalabra(it) != null }
        val extremo = palabras.asReversed().firstNotNullOfOrNull { p ->
            when (p) {
                "arriba", "superior", "alto", "alta" -> "arriba"; "abajo", "inferior", "bajo", "baja" -> "abajo"
                "izquierda", "izquierdo" -> "izquierda"; "derecha", "derecho" -> "derecha"
                "grande", "mayor", "larga", "largo" -> "grande"; "pequena", "pequeno", "menor", "corta", "corto" -> "pequeno"
                "primera", "primero", "primer" -> "primero"; "segunda", "segundo" -> "segundo"
                else -> null
            }
        }
        val titulo = dice("titulo") || dice("encabezado")
        if (tipos.isEmpty() && colores.isEmpty() && cita == null && extremo == null)
            throw IllegalStateException("no entiendo «$que» como algo del lienzo: nombralo por su #id, tipo o color. Lo que hay:\n${loQueHay()}")
        var candidatos = vivos.filter { e -> tipos.isEmpty() || tipos.any { it(e) } }
            .filter { e -> colores.isEmpty() || colores.any { c -> c == LienzoDestino.nombreDeColor(e.strokeColor) || (e.backgroundColor != Element.TRANSPARENT && c == LienzoDestino.nombreDeColor(e.backgroundColor)) } }
            .filter { e -> cita == null || (LienzoDestino.esTexto(e) && Acciones.normalizar(LienzoDestino.textoDe(e)).contains(cita)) }
        if (candidatos.isEmpty()) throw IllegalStateException("no hay nada que encaje con «$que» en el lienzo. Lo que hay:\n${loQueHay()}")
        if (titulo && !plural && candidatos.size > 1) {
            val mayor = candidatos.maxOf { it.fontSize ?: 0.0 }
            candidatos = candidatos.filter { (it.fontSize ?: 0.0) >= mayor - 0.5 }.sortedBy { LienzoDestino.caja(it).y0 }.take(1)
        }
        if (extremo != null && candidatos.size > 1 && !plural) {
            fun medida(e: Element): Double {
                val k = LienzoDestino.caja(e)
                return when (extremo) {
                    "arriba" -> (k.y0 + k.y1) / 2; "abajo" -> -(k.y0 + k.y1) / 2
                    "izquierda" -> (k.x0 + k.x1) / 2; "derecha" -> -(k.x0 + k.x1) / 2
                    "grande" -> -(k.ancho * k.ancho + k.alto * k.alto); "pequeno" -> k.ancho * k.ancho + k.alto * k.alto
                    else -> 0.0
                }
            }
            candidatos = when (extremo) {
                "primero" -> candidatos.take(1)
                "segundo" -> listOfNotNull(candidatos.getOrNull(1))
                else -> listOf(candidatos.minBy(::medida))
            }
        }
        Encontrado(candidatos, plural)
    }

    // ---------------------------------------------------------------------------------- hacer

    /** Lo que hizo una llamada: el texto para el modelo, la escena nueva y lo que cambió. */
    data class Hecho(val texto: String, val elementos: List<Element>, val cambiados: List<String>)

    private fun almohadillas(q: String): List<Int> = Regex("#(\\d+)").findAll(q).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()

    /** **Un error que dice cómo arreglarlo**: el `#id` que no existe con los textos de número parecido. */
    private fun errorDe(elementos: List<Element>, que: String, m: String): String {
        val n = LienzoDestino.numeros(elementos)
        val textos = LienzoDestino.vivos(elementos).filter(LienzoDestino::esTexto)
        val falta = almohadillas(que).firstOrNull { LienzoDestino.porNumero(elementos, it) == null }
        if (falta != null) {
            val cerca = textos.sortedBy { abs((n[it.id] ?: 0) - falta) }.take(3)
                .map { "#${n[it.id]} \"${LienzoDestino.textoDe(it).take(24).replace('\n', ' ')}\"" }
            return if (cerca.isEmpty()) "no existe #$falta y en el lienzo no hay textos"
            else "no existe #$falta; textos con id parecido: ${cerca.joinToString(" ")} (accion «listar» da todos)"
        }
        return "${m.lineSequence().first()} (accion «listar» da el #id de cada texto)"
    }

    /** **Un solo texto** por su referencia (un texto, o una caja con rótulo). */
    private fun unoDe(elementos: List<Element>, que: String, ultimos: List<String>): Result<Element> {
        val r = resolver(elementos, que, ultimos).getOrElse { return Result.failure(IllegalStateException(errorDe(elementos, que, it.message.orEmpty()))) }
        val ts = r.elementos.mapNotNull { LienzoDestino.textoPara(elementos, it) }.distinctBy { it.id }
        return when (ts.size) {
            1 -> Result.success(ts[0])
            0 -> Result.failure(IllegalStateException("$que es un ${r.elementos.firstOrNull()?.let(LienzoDestino::nombreDe) ?: "figura"} sin texto: para ponerle uno nuevo usa dibujar_en_lienzo"))
            else -> Result.failure(IllegalStateException("«$que» encaja con ${ts.size} textos; di cual por su #id:\n${LienzoDestino.listar(elementos, ts)}"))
        }
    }

    /** **Varios textos**: todos los que nombra la referencia y los rótulos de las cajas nombradas. */
    private fun variosDe(elementos: List<Element>, que: String, ultimos: List<String> = emptyList()): Result<List<Element>> {
        val r = resolver(elementos, que, ultimos).getOrElse { return Result.failure(IllegalStateException(errorDe(elementos, que, it.message.orEmpty()))) }
        val ts = r.elementos.mapNotNull { LienzoDestino.textoPara(elementos, it) }.distinctBy { it.id }
        return if (ts.isEmpty()) Result.failure(IllegalStateException("en «$que» no hay textos")) else Result.success(ts)
    }

    /** Lo que pasó con un texto. */
    private class Informe(val nuevo: Element?, val desborda: String?, val encogido: Double?)

    /**
     * **Pone [nuevo] en el texto [t]** (o lo deja como está si es null) y lo ajusta: medido otra
     * vez, anclado por su alineación, partido o encogido si se pide.
     */
    private fun poner(elementos: List<Element>, numeros: Map<String, Int>, t: Element, nuevo: String?, ajuste: String, anchoMax: Double?): Informe {
        val tam0 = t.fontSize ?: 20.0
        val base = nuevo ?: com.forge.pixpin.motor.TextoRico.paraEditar(t)
        val plano = com.forge.pixpin.motor.TextoRico.escrito(base).texto
        val rico = com.forge.pixpin.motor.TextoRico.tieneMarcas(base)
        val atada = LienzoDestino.cajaDelRotulo(elementos, t)
        val rodea = if (atada == null) LienzoDestino.cajaQueLoRodea(elementos, t) else null
        val encoge = ajuste == "encoger" || ajuste == "auto"
        val parte = ajuste == "partir" || ajuste == "auto"
        var tam = tam0
        var desborda: String? = null
        val id = numeros[t.id]
        var x: Element
        if (atada != null) {
            // **Rótulo de una caja**: se parte a lo ancho del hueco y se centra en ella (su alineación).
            val cw = LienzoDestino.anchoQueCabe(atada).coerceAtLeast(4.0)
            val ch = LienzoDestino.altoQueCabe(atada).coerceAtLeast(4.0)
            fun medir(tm: Double): Pair<Double, Double> = LienzoDestino.medir(LienzoDestino.repartir(plano, cw, tm).joinToString("\n"), tm)
            var (w, h) = medir(tam)
            if (encoge) while ((w > cw + 0.5 || h > ch + 0.5) && tam > TAM_MINIMO) { tam = max(tam * 0.92, TAM_MINIMO); val m = medir(tam); w = m.first; h = m.second }
            x = LienzoDestino.conTexto(t, base).copy(fontSize = tam, width = w + 1.0, height = h)
            val k = LienzoDestino.caja(atada)
            val nx = when (t.textAlign) {
                TextAlign.LEFT -> k.x0 + LienzoDestino.RELLENO_DEL_TEXTO
                TextAlign.RIGHT -> k.x1 - LienzoDestino.RELLENO_DEL_TEXTO - x.width
                else -> (k.x0 + k.x1) / 2 - x.width / 2
            }
            x = x.copy(x = nx, y = (k.y0 + k.y1) / 2 - x.height / 2)
            if (w > cw + 1.0 || h > ch + 1.0) desborda = "#$id no cabe en #${numeros[atada.id]} (mide ${Math.round(w)}x${Math.round(h)} y hay ${Math.round(cw)}x${Math.round(ch)})"
        } else {
            // **Suelto**: se mide entero y se ancla por donde dice su alineación (o por el centro, si
            // estaba centrado en su caja).
            val k0 = LienzoDestino.caja(t)
            val cx = (k0.x0 + k0.x1) / 2; val cy = (k0.y0 + k0.y1) / 2
            val limiteW = (anchoMax ?: rodea?.let(LienzoDestino::anchoQueCabe))?.coerceAtLeast(4.0)
            val limiteH = if (anchoMax != null) null else rodea?.let(LienzoDestino::altoQueCabe)
            val partir = parte && !rico && limiteW != null
            fun componer(tm: Double): Triple<String, Double, Double> {
                val f = if (partir) LienzoDestino.repartir(base, limiteW!!, tm).joinToString("\n") else base
                val (w, h) = LienzoDestino.medir(com.forge.pixpin.motor.TextoRico.escrito(f).texto, tm)
                return Triple(f, w, h)
            }
            fun sobra(w: Double, h: Double) = (limiteW != null && w > limiteW + 0.5) || (limiteH != null && h > limiteH + 0.5)
            var (f, w, h) = componer(tam)
            if (encoge) while (sobra(w, h) && tam > TAM_MINIMO) { tam = max(tam * 0.92, TAM_MINIMO); val c = componer(tam); f = c.first; w = c.second; h = c.third }
            x = LienzoDestino.conTexto(t, f).copy(fontSize = tam, width = w, height = h)
            val centradoEnSuCaja = rodea != null && abs(cx - (LienzoDestino.caja(rodea).x0 + LienzoDestino.caja(rodea).x1) / 2) < 3.0
            x = when {
                t.textAlign == TextAlign.CENTER -> x.copy(x = cx - w / 2)
                t.textAlign == TextAlign.RIGHT -> x.copy(x = k0.x1 - w)
                centradoEnSuCaja -> x.copy(x = cx - w / 2)
                else -> x
            }
            if (rodea != null) {
                val kr = LienzoDestino.caja(rodea)
                if (abs(cy - (kr.y0 + kr.y1) / 2) < 0.15 * kr.alto) x = x.copy(y = cy - h / 2)
            }
            if (sobra(w, h)) {
                val donde = if (rodea != null && anchoMax == null) "en #${numeros[rodea.id]}" else "en ${Math.round(limiteW ?: 0.0)} px"
                desborda = "#$id no cabe $donde (mide ${Math.round(w)}x${Math.round(h)} y hay ${Math.round(limiteW ?: w)}x${Math.round(limiteH ?: h)})"
            }
        }
        val encogido = if (abs(tam - tam0) > 0.01) tam else null
        val igual = x.copy(version = t.version, updated = t.updated) == t
        return Informe(if (igual) null else x.copy(version = t.version + 1, updated = System.currentTimeMillis()), desborda, encogido)
    }

    /**
     * **La herramienta**: listar, cambiar o ajustar. [Result.failure] con el motivo si no se pudo
     * nada; si no, el «hecho» y la escena nueva (con lo que no cambió, igual).
     */
    fun hacer(elementos: List<Element>, e: Edicion, ultimos: List<String> = emptyList()): Result<Hecho> = runCatching {
        val hayCambios = e.cambios != null || e.texto != null
        val ajuste = e.ajustar ?: "no"
        val accion = e.accion ?: when {
            hayCambios -> "cambiar"
            ajuste != "no" -> "ajustar"
            else -> "listar"
        }
        val anchoMax = e.anchoMax?.takeIf { it >= 4.0 }
        when (accion) {
            "listar" -> listarTextos(elementos, e)
            "ajustar" -> {
                val ts = variosDe(elementos, e.que ?: "todo", ultimos).getOrThrow()
                aplicarTodos(elementos, ts.map { it to null }, if (ajuste == "no") "auto" else ajuste, anchoMax, emptyList())
            }
            else -> {
                val lista = e.cambios.orEmpty().toMutableList()
                if (e.texto != null) {
                    val que = e.que ?: throw IllegalStateException("con «texto» hace falta «que»: el #id del texto que cambia (o usa «cambios»)")
                    lista += que to e.texto
                }
                if (lista.isEmpty()) throw IllegalStateException("para cambiar hace falta «cambios» [{\"ref\": \"#3\", \"texto\": \"...\"}] o «que» con «texto»")
                val fallos = ArrayList<String>()
                val pares = ArrayList<Pair<Element, String?>>()
                val n = LienzoDestino.numeros(elementos)
                for ((r, t) in lista) {
                    unoDe(elementos, r, ultimos).fold({ x ->
                        if (x.locked) fallos += "#${n[x.id]} esta bloqueado: el usuario lo dejo quieto a proposito"
                        else { pares.removeAll { it.first.id == x.id }; pares += x to t }
                    }, { fallos += "$r: ${it.message}" })
                }
                if (pares.isEmpty()) throw IllegalStateException("no se cambio nada:\n${fallos.joinToString("\n")}")
                aplicarTodos(elementos, pares, ajuste, anchoMax, fallos)
            }
        }
    }

    /** Cambia (o ajusta) todos en UN paso y escribe el «hecho» (los textos del PC). */
    private fun aplicarTodos(elementos: List<Element>, pares: List<Pair<Element, String?>>, ajuste: String, anchoMax: Double?, fallos: List<String>): Hecho {
        val n = LienzoDestino.numeros(elementos)
        val nuevos = HashMap<String, Element>()
        val cambiados = ArrayList<String>()
        val iguales = ArrayList<String>()
        val avisos = ArrayList<String>()
        val encogidos = ArrayList<String>()
        for ((t, nuevo) in pares) {
            val inf = poner(elementos, n, t, nuevo, ajuste, anchoMax)
            if (inf.nuevo != null) { nuevos[t.id] = inf.nuevo; cambiados += t.id } else iguales += "#${n[t.id]}"
            inf.encogido?.let { encogidos += "#${n[t.id]} a ${Math.round(it)}px" }
            inf.desborda?.let { avisos += it }
        }
        val escena = elementos.map { nuevos[it.id] ?: it }
        val c = cambiados.size
        val s = StringBuilder("hecho: $c texto${if (c == 1) "" else "s"} cambiado${if (c == 1) "" else "s"}: ${cambiados.joinToString(" ") { "#${n[it]}" }}")
        if (encogidos.isNotEmpty()) s.append("\nletra encogida: ${encogidos.joinToString(", ")}")
        if (iguales.isNotEmpty()) s.append("\nsin cambio (ya era asi): ${iguales.joinToString(" ")}")
        if (avisos.isNotEmpty()) s.append("\nno caben (repite con «ajustar»: «auto», o agranda la caja con transformar_elementos):\n${avisos.joinToString("\n")}")
        if (fallos.isNotEmpty()) s.append("\nno se pudo:\n${fallos.joinToString("\n")}")
        val lista = LienzoDestino.listar(escena, cambiados.take(12).mapNotNull { id -> escena.firstOrNull { it.id == id } })
        if (lista.isNotEmpty()) s.append('\n').append(lista)
        return Hecho(s.toString(), escena, cambiados)
    }

    /** **Todos los textos con su `#id` y su texto entero**, en orden de lectura. No toca nada. */
    private fun listarTextos(elementos: List<Element>, e: Edicion): Hecho {
        val elegidos = e.que?.let { variosDe(elementos, it).getOrNull()?.map { x -> x.id }?.toSet() }
        val textos = LienzoDestino.vivos(elementos).filter { LienzoDestino.esTexto(it) && !it.reference && (elegidos == null || it.id in elegidos) }
            .sortedWith(compareBy<Element>({ Math.round(it.y / 10.0) }, { it.x }))
        val total = textos.size
        if (total == 0) return Hecho("en el lienzo no hay textos", elementos, emptyList())
        val n = LienzoDestino.numeros(elementos)
        val (mx, my) = LienzoDestino.marco(elementos)
        val desde = (e.desde ?: 0.0).coerceAtLeast(0.0).toInt().coerceAtMost(total)
        val s = StringBuilder("$total textos (de arriba abajo):")
        var puestos = 0
        for (t in textos.drop(desde).take(MAX_LISTA)) {
            val k = LienzoDestino.caja(t)
            s.append("\n#${n[t.id]} \"${LienzoDestino.textoDe(t).replace("\n", "\\n")}\" [${Math.round(k.x0 - mx)},${Math.round(k.y0 - my)},${Math.round(k.ancho)},${Math.round(k.alto)}] letra ${Math.round(t.fontSize ?: 20.0)}")
            LienzoDestino.cajaDelRotulo(elementos, t)?.let { s.append(" (rotulo de #${n[it.id]})") }
            puestos++
        }
        if (desde + puestos < total) s.append("\n(+${total - desde - puestos} mas: repite con «desde»: ${desde + puestos})")
        return Hecho(s.toString(), elementos, emptyList())
    }
}
