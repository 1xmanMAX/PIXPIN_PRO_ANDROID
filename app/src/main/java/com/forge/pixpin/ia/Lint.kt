package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **La revisión automática del lienzo** (`lint.rs` del PC, 22-oct-2026; portada el 25-oct-2026):
 * lo que se ve mal, dicho con su `#id`, sin mirar la imagen.
 *
 * Queja del usuario: el asistente «hace las cosas mal y no las revisa». Muchas faltas se ven en
 * la escena misma, en microsegundos y sin dudas: dos textos encimados, un rótulo que se sale de su
 * caja, una flecha que se queda a 20 px de la caja a la que iba, algo medio fuera de su marco, dos
 * cajas de una fila a 4 px de estar alineadas, un texto que quedó sin traducir, «numero» sin
 * tilde. Va **pegado al resultado de cada herramienta que dibuja o edita** (lo pega
 * `EjecutorDelChat`): una señal de fuera que el modelo recibe gratis.
 *
 * Reglas para que sirva y no estorbe (las del PC):
 * - **Solo lo de esta llamada**: con `tocados`, cada aviso nombra algo tocado (salvo el idioma:
 *   el texto que quedó sin traducir es justo el que NO se tocó).
 * - **Corto**: [paraElModelo] da como mucho [MAX_LINEAS] líneas.
 * - **Sin falsos avisos en un dibujo limpio**: cada regla tiene su margen.
 *
 * Mismos textos que el PC; los `#id` son los del teléfono ([LienzoDestino]). Las medidas de los
 * textos, las de [LienzoDestino.medir] (las mismas con las que dibuja el asistente).
 */
object Lint {
    /** Cuántas líneas de avisos como mucho van al modelo. */
    const val MAX_LINEAS = 8

    /** Con más piezas que estas, las reglas de dos en dos solo miran lo tocado contra todo. */
    private const val MAX_PARES = 400

    /** Un aviso: lo que pasa y a quién (ids de Excalidraw). */
    data class Hallazgo(val ids: List<String>, val texto: String, val deIdioma: Boolean = false)

    /** Una palabra mal escrita y su arreglo, si se sabe. */
    data class Falta(val palabra: String, val arreglo: String?)

    /** **Quién sabe de ortografía**; [idioma] es el código corto («es», «en»). */
    fun interface Ortografia {
        fun revisar(texto: String, idioma: String): List<Falta>
    }

    // ------------------------------------------------------------------------------- ortografía

    /** Las tildes que casi nunca sobran (la lista del PC, tal cual). */
    private val CON_TILDE = mapOf(
        "tambien" to "también", "ademas" to "además", "despues" to "después", "mas" to "más", "aqui" to "aquí", "ahi" to "ahí",
        "alli" to "allí", "asi" to "así", "segun" to "según", "algun" to "algún", "ningun" to "ningún", "jamas" to "jamás",
        "detras" to "detrás", "atras" to "atrás", "traves" to "través", "todavia" to "todavía", "quiza" to "quizá", "dia" to "día",
        "dias" to "días", "numero" to "número", "numeros" to "números", "pagina" to "página", "paginas" to "páginas",
        "facil" to "fácil", "dificil" to "difícil", "rapido" to "rápido", "rapida" to "rápida", "ultimo" to "último",
        "ultima" to "última", "ultimos" to "últimos", "ultimas" to "últimas", "codigo" to "código", "codigos" to "códigos",
        "metodo" to "método", "metodos" to "métodos", "analisis" to "análisis", "telefono" to "teléfono", "musica" to "música",
        "pais" to "país", "paises" to "países", "grafico" to "gráfico", "graficos" to "gráficos", "grafica" to "gráfica",
        "graficas" to "gráficas", "matematicas" to "matemáticas", "logica" to "lógica", "basico" to "básico", "basica" to "básica",
        "titulo" to "título", "titulos" to "títulos", "angulo" to "ángulo", "angulos" to "ángulos", "triangulo" to "triángulo",
        "rectangulo" to "rectángulo", "circulo" to "círculo", "linea" to "línea", "lineas" to "líneas", "menu" to "menú",
        "ingles" to "inglés", "frances" to "francés", "aleman" to "alemán", "espanol" to "español", "ano" to "año", "anos" to "años",
        "nino" to "niño", "ninos" to "niños", "manana" to "mañana", "pequeno" to "pequeño", "pequena" to "pequeña",
        "diseno" to "diseño", "tamano" to "tamaño", "compania" to "compañía", "energia" to "energía", "economia" to "economía",
        "tecnologia" to "tecnología", "biologia" to "biología", "geografia" to "geografía", "categoria" to "categoría",
        "categorias" to "categorías", "exito" to "éxito", "caracteristica" to "característica", "caracteristicas" to "características",
        "electrico" to "eléctrico", "automatico" to "automático", "tecnico" to "técnico", "unico" to "único", "unica" to "única",
        "util" to "útil", "utiles" to "útiles", "movil" to "móvil", "arbol" to "árbol", "lapiz" to "lápiz", "examenes" to "exámenes",
        "imagenes" to "imágenes", "parametro" to "parámetro", "parametros" to "parámetros", "perimetro" to "perímetro",
        "diametro" to "diámetro", "simbolo" to "símbolo", "simbolos" to "símbolos", "vehiculo" to "vehículo", "articulo" to "artículo",
        "capitulo" to "capítulo", "oceano" to "océano", "raiz" to "raíz", "tipico" to "típico", "economico" to "económico",
        "historico" to "histórico", "periodico" to "periódico", "academico" to "académico", "telefonos" to "teléfonos",
        "medico" to "médico", "maquina" to "máquina", "maquinas" to "máquinas", "busqueda" to "búsqueda", "decada" to "década",
        "epoca" to "época", "indice" to "índice", "sintesis" to "síntesis", "hipotesis" to "hipótesis", "fisica" to "física",
        "quimica" to "química", "organico" to "orgánico"
    )

    /** Las de pregunta: solo con tilde dentro de una pregunta, al empezarla. */
    private val DE_PREGUNTA = mapOf(
        "que" to "qué", "como" to "cómo", "cuando" to "cuándo", "donde" to "dónde", "quien" to "quién", "quienes" to "quiénes",
        "cual" to "cuál", "cuales" to "cuáles", "cuanto" to "cuánto", "cuanta" to "cuánta", "cuantos" to "cuántos", "cuantas" to "cuántas"
    )

    /** El arreglo con las mayúsculas del original («NUMERO» → «NÚMERO»). */
    private fun conMayusculas(original: String, arreglo: String): String {
        if (original.none { it.isLowerCase() } && original.length > 1) return arreglo.uppercase()
        if (original.firstOrNull()?.isUpperCase() == true) return arreglo.replaceFirstChar { it.uppercase() }
        return arreglo
    }

    private fun palabras(texto: String): List<String> = texto.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }

    private fun esAscii(s: String) = s.all { it.code < 128 }

    /** La tilde que le falta a una palabra suelta (sin mirar el contexto). */
    private fun tildeDe(min: String): String? {
        if (!esAscii(min)) return null
        CON_TILDE[min]?.let { return it }
        for ((fin, con) in listOf("cion" to "ción", "sion" to "sión"))
            if (min.length > fin.length + 1 && min.endsWith(fin)) return min.dropLast(fin.length) + con
        return null
    }

    /**
     * **Las tildes que casi nunca sobran** (`OrtografiaBasica` del PC): las de la lista, las
     * terminaciones -cion y -sion, «esta» delante de un participio o gerundio, y las de pregunta.
     */
    object OrtografiaBasica : Ortografia {
        override fun revisar(texto: String, idioma: String): List<Falta> {
            if (idioma != "es") return emptyList()
            val ps = palabras(texto)
            val min = ps.map { it.lowercase() }
            val faltas = ArrayList<Falta>()
            fun poner(p: String, arreglo: String) { if (faltas.none { it.palabra == p }) faltas += Falta(p, conMayusculas(p, arreglo)) }
            ps.forEachIndexed { i, p ->
                tildeDe(min[i])?.let { poner(p, it); return@forEachIndexed }
                if (min[i] == "esta" || min[i] == "estan") {
                    val sig = min.getOrNull(i + 1).orEmpty()
                    val verbal = listOf("ando", "iendo", "ado", "ada", "ados", "adas", "ido", "ida", "idos", "idas").any { sig.length > it.length + 1 && sig.endsWith(it) } ||
                        sig in setOf("bien", "mal", "en", "listo", "lista", "aqui", "alli", "claro")
                    val t = texto.trimEnd()
                    if (verbal || (sig.isEmpty() && (t.endsWith('.') || t.endsWith('?') || t.endsWith('!')))) poner(p, if (min[i] == "esta") "está" else "están")
                }
            }
            val hayAbre = texto.contains('¿')
            val trozos = texto.split('¿').drop(if (hayAbre) 1 else 0)
            for (trozo in trozos) {
                if (!hayAbre && !texto.trimEnd().endsWith('?')) break
                val pt = palabras(trozo)
                val primera = if (pt.firstOrNull()?.equals("por", ignoreCase = true) == true) pt.getOrNull(1) else pt.firstOrNull()
                if (primera != null) DE_PREGUNTA.entries.firstOrNull { it.key.equals(primera, ignoreCase = true) }?.let { poner(primera, it.value) }
            }
            return faltas
        }
    }

    // ---------------------------------------------------------------------------------- idioma

    private val ES = setOf(
        "el", "la", "los", "las", "de", "del", "que", "y", "en", "un", "una", "es", "por", "con", "para", "se", "no", "al", "lo", "su",
        "sus", "como", "pero", "este", "esta", "entre", "cuando", "muy", "sin", "sobre", "hay", "donde", "desde", "todo", "todos",
        "otro", "otra", "son", "fin", "inicio", "si", "datos", "resultado", "paso"
    )
    private val EN = setOf(
        "the", "of", "and", "to", "in", "is", "it", "that", "for", "on", "with", "as", "was", "are", "be", "this", "by", "at", "from",
        "or", "an", "not", "have", "has", "which", "you", "we", "they", "can", "will", "all", "end", "start", "step", "data", "result"
    )

    /**
     * **En qué idioma está un texto**, si se nota: «zh», «ja», «ko» por su escritura; «es» o «en»
     * por sus palabras de siempre. null si es corto o no se sabe («A», «x²», «42»).
     */
    fun idiomaDe(texto: String): String? {
        var han = 0; var kana = 0; var hangul = 0; var letras = 0
        var i = 0
        while (i < texto.length) {
            val c = texto.codePointAt(i)
            when (c) {
                in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> han++
                in 0x3040..0x30FF -> kana++
                in 0xAC00..0xD7AF -> hangul++
                else -> if (Character.isLetter(c)) letras++
            }
            i += Character.charCount(c)
        }
        val cjk = han + kana + hangul
        if (cjk > 0 && cjk * 3 >= letras + cjk) return if (kana > 0) "ja" else if (hangul > han) "ko" else "zh"
        var es = 0; var en = 0
        for (p in palabras(texto)) {
            val m = p.lowercase()
            if (m.any { it in "ñáéíóú¿¡" }) es += 2
            if (m in ES) es++
            if (m in EN) en++
            if (m.length > 5 && (m.endsWith("cion") || m.endsWith("ción") || m.endsWith("ciones"))) es++
            if (m.length > 5 && (m.endsWith("tion") || m.endsWith("tions") || m.endsWith("ing"))) en++
            if (tildeDe(m) != null && m !in setOf("menu", "area", "radio")) es++
        }
        if (texto.contains('¿') || texto.contains('¡')) es += 2
        return when {
            es > en && es >= 1 -> "es"
            en > es && en >= 1 -> "en"
            else -> null
        }
    }

    private fun nombreDeIdioma(i: String) = when (i) {
        "es" -> "espanol"; "en" -> "ingles"; "zh" -> "chino"; "ja" -> "japones"; "ko" -> "coreano"; else -> "otro idioma"
    }

    // -------------------------------------------------------------------------------- revisión

    private fun distanciaACaja(px: Double, py: Double, c: LienzoDestino.Caja): Double {
        val dx = max(max(c.x0 - px, 0.0), px - c.x1)
        val dy = max(max(c.y0 - py, 0.0), py - c.y1)
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * **Si un texto cabe donde está** (`textos::no_cabe` del PC): `(ancho, alto, cabeAncho,
     * cabeAlto, caja)` si NO cabe.
     */
    data class NoCabe(val w: Double, val h: Double, val cw: Double, val ch: Double, val caja: Element)

    fun noCabe(elementos: List<Element>, t: Element): NoCabe? {
        if (!LienzoDestino.esTexto(t)) return null
        val tam = t.fontSize ?: 20.0
        val plano = LienzoDestino.textoDe(t)
        LienzoDestino.cajaDelRotulo(elementos, t)?.let { c ->
            val cw = LienzoDestino.anchoQueCabe(c); val ch = LienzoDestino.altoQueCabe(c)
            val (w, h) = LienzoDestino.medir(LienzoDestino.repartir(plano, max(cw, 1.0), tam).joinToString("\n"), tam)
            return if (w > cw + 1.0 || h > ch + 1.0) NoCabe(w, h, cw, ch, c) else null
        }
        val c = LienzoDestino.cajaQueLoRodea(elementos, t) ?: return null
        val (w, h) = LienzoDestino.medir(plano, tam)
        // Suelto encima de una caja: lo que la caja tiene de ancho, o el hueco de dentro si es un óvalo o un rombo.
        val cw = if (c.type == ElementType.RECTANGLE) abs(c.width) - 2 else LienzoDestino.anchoQueCabe(c) + 2 * LienzoDestino.RELLENO_DEL_TEXTO
        val ch = if (c.type == ElementType.RECTANGLE) abs(c.height) - 2 else LienzoDestino.altoQueCabe(c) + 2 * LienzoDestino.RELLENO_DEL_TEXTO
        return if (w > cw + 1.0 || h > ch + 1.0) NoCabe(w, h, cw, ch, c) else null
    }

    /**
     * **Revisa la escena.** Con [tocados] (ids de Excalidraw), solo lo que nombra algo de ellos (y
     * el idioma, si se tocaron textos).
     */
    fun revisar(elementos: List<Element>, tocados: Set<String>? = null, orto: Ortografia = OrtografiaBasica): List<Hallazgo> {
        val n = LienzoDestino.numeros(elementos)
        fun h(e: Element) = "#${n[e.id]}"
        val vivos = LienzoDestino.vivos(elementos).filter { !it.reference }
        fun toca(id: String) = tocados == null || id in tocados
        val avisos = ArrayList<Hallazgo>()

        // Lo que se compara de dos en dos: textos y cajas (los trazos a mano y las fotos se pisan con todo).
        val piezas = vivos.filter { LienzoDestino.esTexto(it) || LienzoDestino.esCaja(it) }
        val todosContraTodos = piezas.size <= MAX_PARES
        fun parVale(a: Element, b: Element) = (toca(a.id) || toca(b.id)) && (todosContraTodos || toca(a.id))

        // 1. Encimados: dos textos que se pisan, o dos cajas que se pisan a medias.
        for (i in piezas.indices) for (j in i + 1 until piezas.size) {
            val a = piezas[i]; val b = piezas[j]
            if (!parVale(a, b)) continue
            val ca = LienzoDestino.caja(a); val cb = LienzoDestino.caja(b)
            val comun = ca.cruce(cb).area
            if (comun <= 0.0) continue
            val menor = max(min(ca.area, cb.area), 1.0)
            if (LienzoDestino.esTexto(a) && LienzoDestino.esTexto(b) && comun > 0.2 * menor) {
                avisos += Hallazgo(listOf(a.id, b.id), "${h(a)} y ${h(b)}: textos encimados")
            } else if (LienzoDestino.esCaja(a) && LienzoDestino.esCaja(b) && comun > 0.08 * menor && !ca.dentroDe(cb, 2.0) && !cb.dentroDe(ca, 2.0) &&
                // Dos óvalos que se cortan suelen ser un Venn.
                !(a.type == ElementType.ELLIPSE && b.type == ElementType.ELLIPSE)
            ) {
                avisos += Hallazgo(listOf(a.id, b.id), "${h(a)} y ${h(b)}: figuras que se pisan a medias")
            }
        }

        // 2. Textos que no caben en su caja (atados o encima de ella).
        for (t in vivos.filter(LienzoDestino::esTexto)) {
            val nc = noCabe(elementos, t) ?: continue
            if (!(toca(t.id) || toca(nc.caja.id))) continue
            val que = if (nc.w > nc.cw + 1.0) "sobra ${Math.round(nc.w - nc.cw)} px a lo ancho" else "sobra ${Math.round(nc.h - nc.ch)} px a lo alto"
            avisos += Hallazgo(listOf(t.id, nc.caja.id), "${h(t)} se sale de ${h(nc.caja)} ($que; editar_texto con «ajustar»: «auto», o agranda ${h(nc.caja)})")
        }

        // 3. Flechas con una punta suelta cerca de algo: iba a ello y no llegó.
        for (f in vivos.filter { it.type == ElementType.ARROW }) {
            val (ini, fin) = LienzoDestino.extremos(f) ?: continue
            for ((nombre, punta, enganche) in listOf(Triple("inicio", ini, f.startBinding), Triple("fin", fin, f.endBinding))) {
                if (enganche != null) {
                    if (vivos.none { it.id == enganche.elementId } && toca(f.id))
                        avisos += Hallazgo(listOf(f.id), "${h(f)}: el $nombre apunta a una figura que ya no esta")
                    continue
                }
                val cerca = vivos.asSequence()
                    .filter { it.id != f.id && (LienzoDestino.esCaja(it) || LienzoDestino.esTexto(it) || it.type == ElementType.IMAGE) }
                    .map { it to distanciaACaja(punta.first, punta.second, LienzoDestino.caja(it)) }
                    .minByOrNull { it.second } ?: continue
                val (e, d) = cerca
                if (d > 6.0 && d <= 60.0 && (toca(f.id) || toca(e.id)))
                    avisos += Hallazgo(listOf(f.id, e.id), "${h(f)}: el $nombre de la flecha se queda a ${Math.round(d)} px de ${h(e)} sin tocarlo")
            }
        }

        // 4. Medio fuera de un marco.
        for (m in vivos.filter(LienzoDestino::esMarco)) {
            val cm = LienzoDestino.caja(m)
            for (e in vivos.filter { it.id != m.id && !LienzoDestino.esMarco(it) }) {
                val ce = LienzoDestino.caja(e)
                if (ce.cruce(cm).area > 0.0 && !ce.dentroDe(cm, 2.0) && (toca(e.id) || toca(m.id)))
                    avisos += Hallazgo(listOf(e.id, m.id), "${h(e)} se sale a medias del marco ${h(m)}")
            }
        }

        // 5. Casi alineados: dos piezas de la misma talla, una al lado de la otra, a unos px de la misma fila (o columna).
        val sueltas = piezas.filter { LienzoDestino.esCaja(it) || (it.containerId == null && LienzoDestino.cajaQueLoRodea(elementos, it) == null) }
        val yaAlineado = HashSet<String>()
        for (i in sueltas.indices) for (j in i + 1 until sueltas.size) {
            val a = sueltas[i]; val b = sueltas[j]
            if (!parVale(a, b) || LienzoDestino.esCaja(a) != LienzoDestino.esCaja(b) || a.id in yaAlineado || b.id in yaAlineado) continue
            val ca = LienzoDestino.caja(a); val cb = LienzoDestino.caja(b)
            fun parecida(x: Double, y: Double) = min(x, y) >= 0.8 * max(x, y)
            val separadasX = ca.x1 <= cb.x0 || cb.x1 <= ca.x0
            val separadasY = ca.y1 <= cb.y0 || cb.y1 <= ca.y0
            fun menor(a0: Double, a1: Double, b0: Double, b1: Double) = minOf(abs(a0 - b0), abs((a0 + a1) - (b0 + b1)) / 2, abs(a1 - b1))
            val dy = menor(ca.y0, ca.y1, cb.y0, cb.y1)
            val dx = menor(ca.x0, ca.x1, cb.x0, cb.x1)
            fun casi(d: Double) = d > 1.5 && d <= 8.0
            if (parecida(ca.alto, cb.alto) && separadasX && casi(dy)) {
                avisos += Hallazgo(listOf(a.id, b.id), "${h(a)} y ${h(b)}: casi en la misma fila (${Math.round(dy)} px de diferencia en y)")
                yaAlineado += b.id
            } else if (parecida(ca.ancho, cb.ancho) && separadasY && casi(dx)) {
                avisos += Hallazgo(listOf(a.id, b.id), "${h(a)} y ${h(b)}: casi en la misma columna (${Math.round(dx)} px de diferencia en x)")
                yaAlineado += b.id
            }
        }

        // 6. Idioma: lo que quedó en otro idioma que casi todos (sin traducir), y la ortografía.
        val conIdioma = vivos.filter(LienzoDestino::esTexto).map { it to idiomaDe(LienzoDestino.textoDe(it)) }
        val cuenta = LinkedHashMap<String, Int>()
        for ((_, i) in conIdioma) if (i != null) cuenta[i] = (cuenta[i] ?: 0) + 1
        val sabidos = cuenta.values.sum()
        val dominante = cuenta.entries.maxByOrNull { it.value }?.takeIf { sabidos >= 2 && it.value * 10 >= sabidos * 6 && it.value < sabidos }?.key
        val seTocaronTextos = tocados == null || tocados.any { id -> vivos.any { it.id == id && LienzoDestino.esTexto(it) } }
        if (dominante != null && seTocaronTextos) {
            val otros = conIdioma.filter { (_, i) -> i != null && i != dominante }.map { it.first }
            if (otros.isNotEmpty()) {
                val primero = conIdioma.first { it.first.id == otros[0].id }.second ?: "?"
                avisos += Hallazgo(
                    otros.map { it.id },
                    "${otros.take(8).joinToString(" ") { h(it) }} sigue${if (otros.size == 1) "" else "n"} en ${nombreDeIdioma(primero)} y el resto esta en ${nombreDeIdioma(dominante)}",
                    deIdioma = true
                )
            }
        }
        val todoEs = cuenta.entries.maxByOrNull { it.value }?.key
        for ((e, i) in conIdioma) {
            if (!toca(e.id)) continue
            val idioma = i ?: todoEs ?: continue
            val faltas = orto.revisar(LienzoDestino.textoDe(e), idioma)
            if (faltas.isEmpty()) continue
            val lista = faltas.take(4).joinToString(", ") { f -> f.arreglo?.let { "«${f.palabra}»→«$it»" } ?: "«${f.palabra}»" }
            avisos += Hallazgo(listOf(e.id), "${h(e)} ortografia: $lista")
        }

        return if (tocados == null) avisos else avisos.filter { a -> a.deIdioma || a.ids.any { it in tocados } }
    }

    /** **Los avisos en pocas líneas**, para pegarlos al resultado de una herramienta. Vacío si no hay. */
    fun paraElModelo(avisos: List<Hallazgo>): String {
        if (avisos.isEmpty()) return ""
        val s = StringBuilder("revision automatica (arreglalo, o ignoralo si es a proposito):")
        for (a in avisos.take(MAX_LINEAS - 1)) s.append("\n- ").append(a.texto)
        if (avisos.size > MAX_LINEAS - 1) s.append("\n(+${avisos.size - (MAX_LINEAS - 1)} avisos mas)")
        return s.toString()
    }
}
