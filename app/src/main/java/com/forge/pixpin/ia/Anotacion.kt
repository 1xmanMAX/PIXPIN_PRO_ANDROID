package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.FillStyle
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Roundness
import com.forge.pixpin.motor.randomId
import com.forge.pixpin.motor.randomSeed
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **Anotar un documento con cabeza** (PC `pixpin_ia::anotacion`, 15-oct-2026: «el asistente debería
 * poder hacer anotaciones ahí de forma inteligente: señalarme puntos importantes con un cuadradito y
 * la explicación al costado»).
 *
 * El modelo **no ve píxeles ni inventa coordenadas**: recibe el texto de la página renglón a renglón y
 * devuelve, una por línea, anotaciones **ancladas a un trozo del texto** (`ancla`) o a una celda. Aquí
 * se encuentra ese trozo ([Ancla]), se busca sitio en el margen libre para la explicación sin pisar
 * otra ([Colocador]) y se hacen las figuras con el motor del lienzo ([figuras]): recuadro o resaltado
 * sobre lo señalado, la nota al costado y una línea guía que no tapa el texto.
 *
 * Un solo motor para todos los formatos, igual que el del PC (mismos números): lo que cambia es cómo
 * se leen las [Pagina]s y dónde se escriben las figuras, que lo hace el asistente
 * (`asistente/AnotarInteligente.kt`).
 */
object Anotacion {
    const val NOMBRE = "anotar_inteligente"
    /** Lo que llevan delante los grupos de lo que pone un turno (`asistente-anotado:<turno>`). */
    const val GRUPO_TURNO = "asistente-anotado:"
    const val MAX_POR_PAGINA = 7
    const val MAX_LETRAS = 14_000
    /** La letra de las notas: la «Normal» del catálogo (Nunito), la que viaja tal cual. */
    const val FAMILIA = com.forge.pixpin.motor.ItemStyle.FONT_NUNITO
    const val INTERLINEADO = 1.25

    enum class Clase(val nombre: String, val color: Int, val titulo: String) {
        IMPORTANTE("importante", 0xe8590c, "Importante"),
        PASO("paso", 0x1c7ed6, "Paso"),
        ERROR("error", 0xe03131, "Error"),
        RESUMEN("resumen", 0x5f3dc4, "Resumen"),
        DEFINICION("definicion", 0x2b8a3e, "Definición"),
        PREGUNTA("pregunta", 0x0c8599, "Pregunta"),
        EXPLICACION("explicacion", 0x865d0b, "Explicación");

        companion object {
            fun de(s: String): Clase? = when (Acciones.normalizar(s.trim())) {
                "importante", "clave", "destacado", "idea", "idea clave" -> IMPORTANTE
                "paso", "pasos", "numero", "numerar" -> PASO
                "error", "errores", "correccion", "falta" -> ERROR
                "resumen", "resumir", "sintesis" -> RESUMEN
                "definicion", "glosario", "termino", "concepto" -> DEFINICION
                "pregunta", "preguntas", "repaso" -> PREGUNTA
                "explicacion", "explicar", "explica", "nota", "formula" -> EXPLICACION
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------ el documento

    /** Una palabra con su caja `[x0, y0, x1, y1]` en las unidades de la capa (y hacia abajo). */
    data class Palabra(var texto: String, val caja: DoubleArray)

    /** Un renglón: sus palabras en orden de lectura; en una hoja de cálculo, una celda con su [etiqueta]. */
    class Renglon(val palabras: MutableList<Palabra> = ArrayList(), val etiqueta: String? = null) {
        fun texto() = palabras.joinToString(" ") { it.texto }
        fun caja(): DoubleArray? = union(palabras.map { it.caja })
    }

    fun union(cajas: List<DoubleArray>): DoubleArray? = cajas.fold(null as DoubleArray?) { u, c ->
        if (u == null) c.copyOf() else doubleArrayOf(min(u[0], c[0]), min(u[1], c[1]), max(u[2], c[2]), max(u[3], c[3]))
    }

    /** **Una página para anotar**, en las unidades de su capa. */
    class Pagina(
        val numero: Int,
        val caja: DoubleArray,
        val renglones: MutableList<Renglon>,
        val letra: Double,
        /** Cuánto se puede salir de la hoja para las notas, a cada lado; 0, nada. */
        var fuera: Double,
        /** Una hoja de cálculo: las notas solo a la derecha de lo usado. */
        val soloDerecha: Boolean = false
    ) {
        fun vacia() = renglones.all { it.palabras.isEmpty() }

        /** **La columna del texto**: donde empieza y acaba la mayoría de los renglones. */
        fun columna(): DoubleArray? {
            val cajas = renglones.mapNotNull { it.caja() }
            if (cajas.isEmpty()) return null
            val x0 = cajas.map { it[0] }.sorted(); val x1 = cajas.map { it[2] }.sorted()
            val k = (cajas.size * 0.1).toInt()
            val izq = x0[min(k, x0.size - 1)]
            val der = x1[x1.size - 1 - min(k, x1.size - 1)]
            return doubleArrayOf(izq, cajas.minOf { it[1] }, max(der, izq + 1.0), cajas.maxOf { it[3] })
        }

        /** **Dónde pueden ir las notas**: los márgenes con sitio y, si se puede salir, el espacio de fuera. */
        fun franjas(): List<Franja> {
            val l = max(letra, 1.0)
            val aire = l * 0.6
            val minimo = l * LETRAS_MINIMAS
            val col = columna() ?: doubleArrayOf(caja[0], caja[1], caja[0], caja[1])
            val y0 = caja[1] + aire; val y1 = caja[3] - aire
            val v = ArrayList<Franja>()
            val izq = doubleArrayOf(caja[0] + aire, col[0] - aire)
            val der = doubleArrayOf(col[2] + aire, caja[2] - aire)
            if (!soloDerecha && izq[1] - izq[0] >= minimo) v += Franja(izq[0], izq[1], y0, y1, Lado.IZQUIERDA, false)
            if (!soloDerecha && der[1] - der[0] >= minimo) v += Franja(der[0], der[1], y0, y1, Lado.DERECHA, false)
            if (fuera >= minimo) {
                val ancho = fuera - aire
                val y1f = if (soloDerecha) max(y1, y0 + l * 120.0) else y1
                v += Franja(caja[2] + aire, caja[2] + aire + ancho, y0, y1f, Lado.DERECHA, true)
                if (!soloDerecha) v += Franja(caja[0] - aire - ancho, caja[0] - aire, y0, y1f, Lado.IZQUIERDA, true)
            }
            return v
        }
    }

    // ------------------------------------------------------------- lo que se le pide

    fun instrucciones(hojaDeCalculo: Boolean): String {
        val ancla = if (hojaDeCalculo) "la celda o el rango al que se refiere, tal cual (\"B4\", \"B4:D9\")"
        else "el trozo EXACTO del documento al que se refiere, copiado letra por letra del renglon (de 2 a 12 palabras; una palabra sola si es un termino)"
        val celdas = if (hojaDeCalculo) " o su celda «B4»" else ""
        val lCeldas = if (hojaDeCalculo) " (en una hoja de calculo, omitelo)" else ""
        return "Eres el anotador de documentos de PixPin. Recibes el texto de una o varias paginas renglon a renglon " +
            "(cada renglon con su numero «L12»$celdas) y el pedido del usuario. Contestas SOLO con anotaciones, " +
            "una por linea, cada una un objeto JSON en UNA sola linea; nada de texto antes ni despues, ni ``` ni listas.\n" +
            "Campos:\n" +
            "- \"tipo\": importante | paso | error | resumen | definicion | pregunta | explicacion\n" +
            "- \"pag\": el numero de la pagina (el de «=== Pagina N ===»)\n" +
            "- \"ancla\": $ancla\n" +
            "- \"l\": el numero del renglon donde empieza el ancla$lCeldas\n" +
            "- \"nota\": lo que va al margen, en el idioma del pedido del usuario (aunque el documento este en otro), claro y breve (como mucho 25 palabras). " +
            "En «resumen», 1 a 3 frases; en «pregunta», la pregunta de repaso; en «definicion», «termino: definicion»; " +
            "en «explicacion», que significa o como se lee (una formula: que es cada simbolo).\n" +
            "- \"corr\": solo en «error»: como deberia decir\n" +
            "- \"n\": solo en «paso»: su numero (1, 2, 3...)\n" +
            "Reglas: el ancla SIEMPRE copiada del texto, nunca inventada ni resumida; como mucho $MAX_POR_PAGINA anotaciones " +
            "por pagina; la mas util primero; no anotes dos veces el mismo trozo; nada obvio ni de relleno. " +
            "Un «resumen» de la pagina entera lleva de ancla el primer renglon de la pagina (o de la seccion que resume). " +
            "Si no hay nada que anotar, una sola linea {\"tipo\":\"nada\",\"nota\":\"por que\"}."
    }

    /** **El texto que ve el modelo**: el pedido y las páginas, renglón a renglón, recortado. */
    fun mensaje(pedido: String, paginas: List<Pagina>, maxLetras: Int): String {
        val s = StringBuilder("Pedido del usuario: ${pedido.trim()}\n\n")
        var recortado = false
        fuera@ for (p in paginas) {
            s.append("=== Pagina ${p.numero + 1} ===\n")
            for ((i, r) in p.renglones.withIndex()) {
                if (r.palabras.isEmpty()) continue
                val linea = if (r.etiqueta != null) "${r.etiqueta}: ${r.texto()}\n" else "L${i + 1}: ${r.texto()}\n"
                if (s.codePointCount(0, s.length) + linea.codePointCount(0, linea.length) > maxLetras) { recortado = true; break@fuera }
                s.append(linea)
            }
            s.append('\n')
        }
        if (recortado) s.append("[...el resto no cabe: anota solo lo que ves aqui]\n")
        return s.toString()
    }

    // --------------------------------------------------------------- lo que contesta

    data class Pedida(
        val clase: Clase, val pagina: Int?, val ancla: String, val linea: Int?, val nota: String,
        val correccion: String? = null, val numero: Int? = null
    )

    sealed class Leida {
        data class Anotacion(val p: Pedida) : Leida()
        data class Nada(val motivo: String) : Leida()
        data class Mal(val motivo: String) : Leida()

        companion object {
            private fun texto(v: JsonObject, campos: List<String>): String? = campos.firstNotNullOfOrNull { c ->
                val x = v[c] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
                if (x.isString) x.content.trim().takeIf { it.isNotEmpty() } else x.content.takeIf { x.doubleOrNull != null }
            }

            private fun numero(v: JsonObject, campos: List<String>): Long? = campos.firstNotNullOfOrNull { c ->
                val x = v[c] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
                if (x.isString) x.content.trim().trimStart('L', 'l').toLongOrNull()
                else x.content.toLongOrNull() ?: x.doubleOrNull?.takeIf { it >= 0 }?.toLong()
            }

            fun de(e: JsonElement): Leida {
                val v = e as? JsonObject ?: return Mal("no es un objeto")
                val tipo = texto(v, listOf("tipo", "type")) ?: return Mal("sin «tipo»")
                if (Acciones.normalizar(tipo) == "nada") return Nada(texto(v, listOf("nota")).orEmpty())
                val clase = Clase.de(tipo) ?: return Mal("tipo «$tipo» desconocido")
                val ancla = texto(v, listOf("ancla", "texto", "anchor")).orEmpty()
                val nota = texto(v, listOf("nota", "explicacion", "note")).orEmpty()
                if (ancla.isEmpty() && clase != Clase.RESUMEN) return Mal("sin «ancla»")
                if (nota.isEmpty() && clase != Clase.PASO && clase != Clase.ERROR) return Mal("sin «nota»")
                return Anotacion(Pedida(
                    clase,
                    numero(v, listOf("pag", "pagina", "page"))?.takeIf { it >= 1 }?.let { (it - 1).toInt() },
                    ancla,
                    numero(v, listOf("l", "linea", "renglon"))?.takeIf { it >= 1 }?.toInt(),
                    nota,
                    texto(v, listOf("corr", "correccion", "correcto")),
                    numero(v, listOf("n", "numero", "paso"))?.toInt()
                ))
            }
        }
    }

    /**
     * **Las anotaciones según llegan**: suelta cada objeto JSON en cuanto se cierra su última llave
     * (cuenta llaves fuera de las comillas: no se fía de los saltos de línea ni de los ```).
     */
    class Flujo {
        private val pendiente = StringBuilder()
        private var inicio: Int? = null
        private var hondo = 0
        private var enComillas = false
        private var escapado = false
        private var mirado = 0
        var rotos = 0; private set

        /** El lector de kotlinx deja pasar palabras sin comillas (`{"tipo": roto}`); el JSON de verdad no. */
        private fun esJson(e: JsonElement): Boolean = when (e) {
            is JsonObject -> e.values.all(::esJson)
            is kotlinx.serialization.json.JsonArray -> e.all(::esJson)
            is JsonPrimitive -> e.isString || e is kotlinx.serialization.json.JsonNull || e.content == "true" || e.content == "false" || e.doubleOrNull != null
        }

        fun empujar(trozo: String): List<JsonObject> {
            pendiente.append(trozo)
            val salen = ArrayList<JsonObject>()
            var i = mirado
            while (i < pendiente.length) {
                val c = pendiente[i]
                if (enComillas) {
                    if (escapado) escapado = false else if (c == '\\') escapado = true else if (c == '"') enComillas = false
                } else if (c == '"') {
                    if (inicio != null) enComillas = true
                } else if (c == '{') {
                    if (inicio == null) { inicio = i; hondo = 0 }
                    hondo++
                } else if (c == '}' && inicio != null) {
                    hondo--
                    if (hondo == 0) {
                        val desde = inicio!!; inicio = null
                        val v = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(pendiente.substring(desde, i + 1)) }.getOrNull()
                        if (v is JsonObject && esJson(v)) salen += v else rotos++
                    }
                }
                i++
            }
            val d = inicio
            if (d != null) { pendiente.delete(0, d); inicio = 0; mirado = pendiente.length }
            else { pendiente.setLength(0); mirado = 0 }
            return salen
        }
    }

    // ---------------------------------------------------------------------- anclar

    class Anclaje(val cajas: List<DoubleArray>, val renglon: Int, val ultimo: Int, val exacto: Boolean) {
        fun union(): DoubleArray = Anotacion.union(cajas) ?: DoubleArray(4)
    }

    /** **Encontrar en la página lo que dijo el modelo** (PC `anotacion::ancla`). */
    object Ancla {
        private class Ficha(val renglon: Int, val palabra: Int, val norma: String)

        private fun norma(s: String) = Acciones.normalizar(s).filter { it.isLetterOrDigit() }

        private fun fichas(p: Pagina): List<Ficha> {
            val v = ArrayList<Ficha>()
            for ((r, ren) in p.renglones.withIndex()) for ((k, w) in ren.palabras.withIndex()) {
                val n = norma(w.texto); if (n.isNotEmpty()) v += Ficha(r, k, n)
            }
            return v
        }

        private fun cajasDe(p: Pagina, f: List<Ficha>, desde: Int, hasta: Int, exacto: Boolean): Anclaje? {
            if (desde < 0 || hasta > f.size || desde >= hasta) return null
            val tramo = f.subList(desde, hasta)
            val primero = tramo.first().renglon; val ultimo = tramo.last().renglon
            val cajas = (primero..ultimo).mapNotNull { r -> union(tramo.filter { it.renglon == r }.map { p.renglones[r].palabras[it.palabra].caja }) }
            return if (cajas.isEmpty()) null else Anclaje(cajas, primero, ultimo, exacto)
        }

        /** **Ancla [ancla] en la página.** [linea]: el renglón que dijo el modelo (desde 1). */
        fun anclar(p: Pagina, ancla: String, linea: Int?): Anclaje? {
            porCelda(p, ancla)?.let { return it }
            val f = fichas(p)
            if (f.isEmpty()) return null
            val pedido = norma(ancla)
            if (pedido.isEmpty()) return null
            val cerca = { r: Int -> if (linea == null) 0 else abs(r - (linea - 1)) }
            val exactos = talCual(f, pedido).sortedBy { cerca(f[it.first].renglon) }
            exactos.firstOrNull()?.let { return cajasDe(p, f, it.first, it.second, true) }
            val palabras = ancla.split(Regex("\\s+")).map(::norma).filter { it.isNotEmpty() }
            parecido(f, palabras, cerca)?.let { return cajasDe(p, f, it.first, it.second, false) }
            return null
        }

        private fun talCual(f: List<Ficha>, pedido: String): List<Pair<Int, Int>> {
            val largo = pedido.length
            val v = ArrayList<Pair<Int, Int>>()
            for (estricto in listOf(true, false)) {
                if (!estricto && largo < 8) break
                for (d in f.indices) {
                    val acum = StringBuilder()
                    for (h in d until f.size) {
                        acum.append(f[h].norma)
                        if (acum.length > pedido.length + f[h].norma.length + f[d].norma.length) break
                        val a = acum.toString()
                        val ok = if (estricto) a == pedido
                        else a.contains(pedido) && !a.substring(f[d].norma.length).contains(pedido) && pedido.length * 10 >= a.length * 7
                        if (ok) { v += d to h + 1; break }
                        if (estricto && !pedido.startsWith(a)) break
                    }
                }
                if (v.isNotEmpty()) break
            }
            return v
        }

        private fun distancia(a: String, b: String): Int {
            var antes = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val ahora = IntArray(b.length + 1) { i }
                for (j in 1..b.length) {
                    val c = if (a[i - 1] != b[j - 1]) 1 else 0
                    ahora[j] = min(min(antes[j] + 1, ahora[j - 1] + 1), antes[j - 1] + c)
                }
                antes = ahora
            }
            return antes[b.length]
        }

        private fun semejanza(a: String, b: String): Double {
            if (a == b) return 1.0
            val la = a.length; val lb = b.length
            if (min(la, lb) >= 4 && (abs(la - lb) <= 2 || a.startsWith(b) || b.startsWith(a))) {
                val d = distancia(a, b)
                if (d == 1) return 0.85
                if (d == 2 && min(la, lb) >= 7) return 0.7
                if (a.startsWith(b) || b.startsWith(a)) return 0.7
            }
            return 0.0
        }

        private fun parecido(f: List<Ficha>, palabras: List<String>, cerca: (Int) -> Int): Pair<Int, Int>? {
            val n = palabras.size
            if (n == 0) return null
            if (n == 1 && palabras[0].length < 5) return null
            val parecidas = palabras.map { q -> f.indices.map { it to semejanza(q, f[it].norma) }.filter { it.second > 0.0 } }
            var mejor: Triple<Double, Int, Int>? = null
            for (largo in listOf(n, n + 1, max(n - 1, 1))) {
                if (largo > f.size) continue
                for (d in 0..(f.size - largo)) {
                    val suma = parecidas.sumOf { l -> l.filter { it.first in d until d + largo }.maxOfOrNull { it.second } ?: 0.0 }
                    val nota = suma / max(n, largo)
                    val m = mejor
                    val mejorQue = m == null || nota > m.first + 1e-4 || (abs(nota - m.first) <= 1e-4 && cerca(f[d].renglon) < cerca(f[m.second].renglon))
                    if (mejorQue) mejor = Triple(nota, d, d + largo)
                }
            }
            val m = mejor ?: return null
            return if (m.first >= 0.7) m.second to m.third else null
        }

        fun primerRenglon(p: Pagina): Anclaje? {
            val r = p.renglones.indexOfFirst { it.palabras.isNotEmpty() }
            if (r < 0) return null
            return Anclaje(listOf(p.renglones[r].caja() ?: return null), r, r, true)
        }

        /** «B4» → (fila 3, columna 1), desde 0. */
        fun celda(s: String): Pair<Int, Int>? {
            val t = s.trim().trimStart('$').replace("$", "")
            val letras = t.takeWhile { it in 'a'..'z' || it in 'A'..'Z' }
            val numero = t.substring(letras.length)
            if (letras.isEmpty() || letras.length > 3 || numero.isEmpty() || !numero.all { it in '0'..'9' }) return null
            val col = letras.uppercase().fold(0) { a, b -> a * 26 + (b - 'A' + 1) } - 1
            val fila = numero.toIntOrNull() ?: return null
            return if (fila >= 1) (fila - 1) to col else null
        }

        fun rango(s: String): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
            val t = s.trim()
            val (a0, b0) = t.indexOf(':').let { if (it < 0) t to t else t.substring(0, it) to t.substring(it + 1) }
            val a = celda(a0) ?: return null; val b = celda(b0) ?: return null
            return (min(a.first, b.first) to min(a.second, b.second)) to (max(a.first, b.first) to max(a.second, b.second))
        }

        private fun porCelda(p: Pagina, ancla: String): Anclaje? {
            val (desde, hasta) = rango(ancla) ?: return null
            val cajas = ArrayList<DoubleArray>()
            var primero = Int.MAX_VALUE; var ultimo = 0
            for ((i, r) in p.renglones.withIndex()) {
                val (f, c) = r.etiqueta?.let(::celda) ?: continue
                if (f in desde.first..hasta.first && c in desde.second..hasta.second) {
                    val caja = r.caja() ?: continue
                    cajas += caja; primero = min(primero, i); ultimo = max(ultimo, i)
                }
            }
            val u = union(cajas) ?: return null
            return Anclaje(listOf(u), primero, ultimo, true)
        }
    }

    // --------------------------------------------------------------------- colocar

    const val LETRAS_MINIMAS = 9.0
    private const val COSTE_FUERA = 12.0
    private const val COSTE_OTRO_LADO = 1.5

    enum class Lado { IZQUIERDA, DERECHA }

    data class Franja(val x0: Double, val x1: Double, val y0: Double, val y1: Double, val lado: Lado, val fuera: Boolean)

    class Colocada(val franja: Franja, val caja: DoubleArray)

    /** **Dónde va cada nota del margen**, sin que dos se pisen (PC `anotacion::colocar`). */
    class Colocador(val franjas: List<Franja>, hueco: Double) {
        private val ocupado = List(franjas.size) { ArrayList<DoubleArray>() }
        private val hueco = max(hueco, 0.0)

        fun ocupar(caja: DoubleArray) {
            for ((i, f) in franjas.withIndex()) if (caja[2] > f.x0 && caja[0] < f.x1) ocupado[i] += doubleArrayOf(caja[1], caja[3])
        }

        private fun huecoEn(i: Int, deseado: Double, alto: Double): Double? {
            val f = franjas[i]
            val mn = f.y0; val mx = f.y1 - alto
            if (mx < mn) return null
            val g = hueco
            val libre = { y: Double -> y >= mn - 1e-3 && y <= mx + 1e-3 && ocupado[i].all { t -> y + alto + g <= t[0] || y >= t[1] + g } }
            val candidatos = ArrayList<Double>().apply { add(deseado.coerceIn(mn, mx)) }
            for (t in ocupado[i]) { candidatos += t[1] + g; candidatos += t[0] - g - alto }
            return candidatos.filter(libre).minByOrNull { abs(it - deseado) }
        }

        fun colocar(ancla: DoubleArray, altoCon: (Double) -> Double, anchoMaximo: Double): Colocada? {
            val letra = max(hueco * 2.0, 1.0)
            val centroX = (ancla[0] + ancla[2]) / 2.0
            var izq = Double.MAX_VALUE; var der = -Double.MAX_VALUE
            for (f in franjas) { izq = min(izq, f.x1); der = max(der, f.x0) }
            val ladoNatural = if (centroX < (izq + der) / 2.0) Lado.IZQUIERDA else Lado.DERECHA
            var mejor: DoubleArray? = null // coste, i, y, ancho, alto
            for ((i, f) in franjas.withIndex()) {
                val ancho = min(f.x1 - f.x0, anchoMaximo)
                if (ancho <= 0.0) continue
                val alto = altoCon(ancho)
                val deseado = ancla[1] - hueco * 0.5
                val y = huecoEn(i, deseado, alto) ?: continue
                var coste = abs(y - deseado)
                if (f.fuera) coste += COSTE_FUERA * letra
                if (f.lado != ladoNatural) coste += COSTE_OTRO_LADO * letra
                if (mejor == null || coste < mejor[0]) mejor = doubleArrayOf(coste, i.toDouble(), y, ancho, alto)
            }
            val m = mejor ?: return null
            val i = m[1].toInt(); val y = m[2]; val ancho = m[3]; val alto = m[4]
            val f = franjas[i]
            val x0 = if (f.lado == Lado.DERECHA) f.x0 else f.x1 - ancho
            ocupado[i] += doubleArrayOf(y, y + alto)
            return Colocada(f, doubleArrayOf(x0, y, x0 + ancho, y + alto))
        }
    }

    // --------------------------------------------------------------------- figuras

    /** Mide un texto de una línea (ancho) con la letra de las notas: [tam], negrita. */
    fun interface Medidor { fun ancho(texto: String, tam: Double, negrita: Boolean): Double }

    /** A ojo y algo de más (el PC sin DirectWrite): lo seguro. */
    val A_OJO = Medidor { t, tam, _ -> (t.codePointCount(0, t.length) + 1) * tam * 0.62 }

    /** El medidor del teléfono (Nunito de verdad); lo pone el asistente al arrancar. Sin él, [A_OJO]. */
    @Volatile var medidor: Medidor = A_OJO

    fun partir(texto: String, tam: Double, ancho: Double, negrita: Boolean): List<String> {
        val v = ArrayList<String>()
        for (parrafo in texto.split('\n')) {
            var l = ""
            for (w in parrafo.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val prueba = if (l.isEmpty()) w else "$l $w"
                if (l.isNotEmpty() && medidor.ancho(prueba, tam, negrita) > ancho) { v += l; l = w } else l = prueba
            }
            v += l
        }
        return v
    }

    class Nota(val titulo: String, val cuerpo: String, letra: Double) {
        val letra = max(letra, 1.0)
        val tamTitulo get() = letra * 0.82
        val relleno get() = letra * 0.5
        fun anchoMaximo() = letra * 18.0

        fun renglones(ancho: Double): Pair<List<String>, List<String>> {
            val util = max((ancho - 2.0 * relleno) * 0.96, letra * 3.0)
            val t = partir(titulo, tamTitulo, util, true)
            val c = if (cuerpo.isEmpty()) emptyList() else partir(cuerpo, letra, util, false)
            return t to c
        }

        fun altoCon(ancho: Double): Double {
            val (t, c) = renglones(ancho)
            var alto = 2.0 * relleno + t.size * tamTitulo * INTERLINEADO
            if (c.isNotEmpty()) alto += letra * 0.2 + c.size * letra * INTERLINEADO
            return alto
        }

        companion object {
            fun de(p: Pedida, letra: Double): Nota {
                val titulo = if (p.clase == Clase.PASO && p.numero != null) "Paso ${p.numero}" else p.clase.titulo
                var cuerpo = p.nota.trim()
                p.correccion?.trim()?.takeIf { it.isNotEmpty() }?.let { cuerpo = (if (cuerpo.isEmpty()) "" else "$cuerpo\n") + "→ $it" }
                return Nota(titulo, cuerpo, letra)
            }
        }
    }

    private fun hex(c: Int) = "#%06x".format(c and 0xffffff)
    private fun palido(c: Int, k: Double): String {
        fun m(x: Int) = (((x / 255.0) * (1 - k) + k) * 255).let { Math.round(it).toInt().coerceIn(0, 255) }
        return "#%02x%02x%02x".format(m(c shr 16 and 255), m(c shr 8 and 255), m(c and 255))
    }

    private const val TINTA = 0x212529

    private fun base(grupos: List<String>, trazo: String, grosor: Double) = Element(
        id = randomId(), type = ElementType.RECTANGLE, x = 0.0, y = 0.0, width = 0.0, height = 0.0, seed = randomSeed(),
        strokeColor = trazo, strokeWidth = max(grosor, 0.5), roughness = 0, groupIds = grupos, updated = System.currentTimeMillis()
    )

    private fun nuevo(b: Element) = b.copy(id = randomId(), seed = randomSeed())

    private fun rect(c: DoubleArray, b: Element) = nuevo(b).copy(type = ElementType.RECTANGLE, x = c[0], y = c[1], width = max(c[2] - c[0], 0.5), height = max(c[3] - c[1], 0.5))

    private fun linea(puntos: List<Pair<Double, Double>>, b: Element): Element {
        val x0 = puntos.minOf { it.first }; val y0 = puntos.minOf { it.second }
        val p0 = puntos.first()
        // Como el motor del lienzo: el elemento en su primer punto y los puntos relativos a él.
        return nuevo(b).copy(type = ElementType.LINE, x = p0.first, y = p0.second,
            width = puntos.maxOf { it.first } - x0, height = puntos.maxOf { it.second } - y0,
            points = puntos.map { Pt(it.first - p0.first, it.second - p0.second) })
    }

    private fun texto(t: String, x: Double, y: Double, tam: Double, ancho: Double, renglones: Int, b: Element, negrita: Boolean) =
        nuevo(b).copy(type = ElementType.TEXT, x = x, y = y, width = ancho, height = max(renglones, 1) * tam * INTERLINEADO,
            text = t, fontSize = tam, fontFamily = FAMILIA, strokeWidth = 1.0, negrita = negrita)

    /** **Las figuras de una anotación**: la marca sobre [a], la nota en [colocada] y la guía, en [grupos]. */
    fun figuras(clase: Clase, a: Anclaje, colocada: Colocada, nota: Nota, pag: Pagina, grupos: List<String>): List<Element> {
        val l = nota.letra
        val tinta = hex(clase.color)
        val b = base(grupos, tinta, l * 0.1)
        val v = ArrayList<Element>()
        val u = a.union()
        val aire = l * 0.2
        val marca = doubleArrayOf(u[0] - aire, u[1] - aire, u[2] + aire, u[3] + aire)
        when (clase) {
            Clase.DEFINICION, Clase.ERROR -> for (caja in a.cajas) {
                v += rect(doubleArrayOf(caja[0] - aire * 0.5, caja[1] - aire * 0.3, caja[2] + aire * 0.5, caja[3] + aire * 0.3), b)
                    .copy(strokeColor = tinta + "00", backgroundColor = tinta, fillStyle = FillStyle.SOLID, opacity = if (clase == Clase.DEFINICION) 22 else 14)
                if (clase == Clase.DEFINICION) v += linea(listOf(caja[0] to caja[3] + aire * 0.4, caja[2] to caja[3] + aire * 0.4), b)
                else {
                    val y = caja[3] + aire * 0.5; val paso = l * 0.3
                    val pts = ArrayList<Pair<Double, Double>>()
                    var x = caja[0]; var arriba = false
                    while (x < caja[2]) { pts += x to (if (arriba) y - paso * 0.5 else y); arriba = !arriba; x += paso }
                    pts += caja[2] to y
                    if (pts.size >= 2) v += linea(pts, b)
                }
            }
            Clase.RESUMEN -> {
                val col = pag.columna() ?: marca
                val x = if (colocada.franja.lado == Lado.DERECHA) max(col[2], marca[2]) + aire * 2.0 else min(col[0], marca[0]) - aire * 2.0
                v += linea(listOf(x to marca[1], x to marca[3]), b).copy(strokeWidth = l * 0.18)
            }
            else -> v += rect(marca, b).copy(roundness = Roundness(Roundness.ADAPTIVE_RADIUS), strokeWidth = l * 0.11)
        }
        if (clase == Clase.PASO) {
            val n = nota.titulo.removePrefix("Paso ")
            val d = l * 1.25
            val cx = marca[0] - d * 0.6; val cy = (a.cajas[0][1] + a.cajas[0][3]) / 2.0
            v += nuevo(b).copy(type = ElementType.ELLIPSE, x = cx - d / 2, y = cy - d / 2, width = d, height = d, backgroundColor = tinta, fillStyle = FillStyle.SOLID)
            val tam = l * 0.8
            val ancho = medidor.ancho(n, tam, true)
            v += texto(n, cx - ancho / 2, cy - tam * INTERLINEADO / 2, tam, ancho, 1, b, true).copy(strokeColor = "#ffffff")
        }
        val caja = colocada.caja
        v += guia(a, marca, caja, colocada.franja.lado, pag, b, l)
        v += rect(caja, b).copy(backgroundColor = palido(clase.color, 0.9), fillStyle = FillStyle.SOLID, strokeColor = tinta + "8c",
            strokeWidth = max(l * 0.06, 0.5), roundness = Roundness(Roundness.ADAPTIVE_RADIUS))
        val (t, cu) = nota.renglones(caja[2] - caja[0])
        val p = nota.relleno
        val ancho = caja[2] - caja[0] - 2.0 * p
        val tt = nota.tamTitulo
        v += texto(t.joinToString("\n"), caja[0] + p, caja[1] + p, tt, ancho, t.size, b, true)
        if (cu.isNotEmpty()) {
            val y = caja[1] + p + t.size * tt * INTERLINEADO + l * 0.2
            v += texto(cu.joinToString("\n"), caja[0] + p, y, l, ancho, cu.size, b, false).copy(strokeColor = hex(TINTA))
        }
        return v
    }

    private fun dentro(c: DoubleArray, x: Double, y: Double, m: Double) = x > c[0] + m && x < c[2] - m && y > c[1] + m && y < c[3] - m

    private fun tapa(camino: List<Pair<Double, Double>>, palabras: List<DoubleArray>, paso: Double): Boolean {
        for (k in 0 until camino.size - 1) {
            val a = camino[k]; val b = camino[k + 1]
            val largo = sqrt((b.first - a.first).let { it * it } + (b.second - a.second).let { it * it })
            val n = max(ceil(largo / max(paso, 0.1)), 1.0).toInt()
            for (j in 0..n) {
                val t = j.toDouble() / n
                val qx = a.first + (b.first - a.first) * t; val qy = a.second + (b.second - a.second) * t
                if (palabras.any { dentro(it, qx, qy, paso * 0.15) }) return true
            }
        }
        return false
    }

    /** **La línea guía** de la nota a lo señalado: el primer camino que no tapa texto, y un punto al llegar. */
    private fun guia(a: Anclaje, marca: DoubleArray, caja: DoubleArray, lado: Lado, pag: Pagina, b: Element, l: Double): List<Element> {
        val col = if (pag.soloDerecha) pag.caja else pag.columna() ?: marca
        val palabras = pag.renglones.flatMap { r -> r.palabras.map { it.caja } }
            .filter { c -> !(c[0] >= marca[0] - 0.5 && c[2] <= marca[2] + 0.5 && c[1] >= marca[1] - 0.5 && c[3] <= marca[3] + 0.5) }
        val primera = a.cajas[0]
        val ay = (primera[1] + primera[3]) / 2.0
        val g = l * 0.5
        val derecha = lado == Lado.DERECHA
        val sy = ay.coerceIn(caja[1] + l * 0.6, max(caja[3] - l * 0.6, caja[1] + l * 0.6))
        val s = (if (derecha) caja[0] else caja[2]) to sy
        val borde = if (derecha) marca[2] else marca[0]
        val bordeCol = if (derecha) max(col[2], marca[2]) + g else min(col[0], marca[0]) - g
        val xa = if (derecha) marca[0] else bordeCol; val xb = if (derecha) bordeCol else marca[2]
        val solapa = { c: DoubleArray -> c[2] > xa && c[0] < xb }
        val techo = palabras.filter { solapa(it) && it[3] <= marca[1] + 0.5 }.maxOfOrNull { it[3] }
        val suelo = palabras.filter { solapa(it) && it[1] >= marca[3] - 0.5 }.minOfOrNull { it[1] }
        val huecoArriba = if (techo == null) marca[1] - l * 0.4 else if (marca[1] - techo >= l * 0.12) (marca[1] + techo) / 2 else null
        val huecoAbajo = if (suelo == null) marca[3] + l * 0.4 else if (suelo - marca[3] >= l * 0.12) (marca[3] + suelo) / 2 else null
        val entradaX = if (derecha) borde - l * 0.6 else borde + l * 0.6
        val caminos = ArrayList<List<Pair<Double, Double>>>()
        if (!pag.soloDerecha) {
            caminos += listOf(s, borde to ay)
            caminos += listOf(s, bordeCol to sy, bordeCol to ay, borde to ay)
            huecoArriba?.let { y -> caminos += listOf(s, bordeCol to sy, bordeCol to y, entradaX to y, entradaX to marca[1]) }
            huecoAbajo?.let { y -> caminos += listOf(s, bordeCol to sy, bordeCol to y, entradaX to y, entradaX to marca[3]) }
        }
        val paso = l / 3.0
        val libre = caminos.firstOrNull { !tapa(it, palabras, paso) }
        val camino0 = libre ?: listOf(s, bordeCol to sy, bordeCol to ay)
        val llega = libre?.last() ?: (bordeCol to ay)
        val camino = camino0.fold(ArrayList<Pair<Double, Double>>()) { v, p ->
            val q = v.lastOrNull()
            if (q == null || abs(q.first - p.first) + abs(q.second - p.second) > 0.01) v += p
            v
        }
        val v = ArrayList<Element>()
        if (camino.size >= 2) v += linea(camino, b).copy(strokeWidth = max(l * 0.07, 0.6))
        val r = l * 0.18
        v += nuevo(b).copy(type = ElementType.ELLIPSE, x = llega.first - r, y = llega.second - r, width = 2 * r, height = 2 * r,
            backgroundColor = b.strokeColor, fillStyle = FillStyle.SOLID)
        return v
    }

    // ------------------------------------------------------------------- el anotador

    class Puesta(val en: Int, val clase: Clase, val ancla: String, val fuera: Boolean, val lado: Lado, val elementos: List<Element>)

    /** **El anotador de un turno**: cada [Pedida] que llega se ancla, se coloca y sale hecha figuras. */
    class Anotador(val paginas: List<Pagina>, private val turno: String) {
        private val colocadores = paginas.mapIndexed { i, p ->
            val c = Colocador(p.franjas(), p.letra * 0.5)
            p.columna()?.let { col -> for (r in p.renglones) for (w in r.palabras) if (w.caja[2] < col[0] || w.caja[0] > col[2]) c.ocupar(w.caja) }
            i to c
        }.toMap()
        private var puestas = 0
        private val vistas = HashSet<Pair<Int, String>>()
        val porClase = LinkedHashMap<Clase, Int>()
        val noEncontradas = ArrayList<String>()
        var sinSitio = 0; private set

        fun grupo() = "$GRUPO_TURNO$turno"
        fun cuantas() = puestas

        /** **Pone una anotación**; con el motivo si no se encontró el ancla o no queda sitio. */
        fun poner(p: Pedida): Result<Puesta> {
            if (paginas.isEmpty()) return Result.failure(IllegalStateException("no hay paginas"))
            val dicha = p.pagina?.let { n -> paginas.indexOfFirst { it.numero == n }.takeIf { it >= 0 } }
            val orden = listOfNotNull(dicha) + paginas.indices.filter { it != dicha }
            var hallado: Pair<Int, Anclaje>? = null
            for (i in orden) {
                val pag = paginas[i]
                val a = if (p.ancla.isBlank() && p.clase == Clase.RESUMEN) Ancla.primerRenglon(pag)
                else Ancla.anclar(pag, p.ancla, if (i == dicha || dicha == null) p.linea else null)
                if (a != null) { hallado = i to a; break }
            }
            val (i, a) = hallado ?: run { noEncontradas += p.ancla; return Result.failure(IllegalStateException("«${p.ancla}» no esta en el texto")) }
            if (!vistas.add(i to Acciones.normalizar(p.ancla))) return Result.failure(IllegalStateException("«${p.ancla}» ya estaba anotado"))
            val pag = paginas[i]
            val k = puestas + 1
            val nota = Nota.de(p, pag.letra)
            val colocada = colocadores.getValue(i).colocar(a.union(), nota::altoCon, nota.anchoMaximo())
                ?: run { sinSitio++; return Result.failure(IllegalStateException("no queda sitio en el margen de esa pagina")) }
            val elementos = figuras(p.clase, a, colocada, nota, pag, listOf("${grupo()}#$k", grupo()))
            puestas = k
            porClase[p.clase] = (porClase[p.clase] ?: 0) + 1
            return Result.success(Puesta(i, p.clase, p.ancla, colocada.franja.fuera, colocada.franja.lado, elementos))
        }

        /** Lo hecho, contado para el modelo: «5 anotaciones (3 importante, 2 pregunta)». */
        fun resumen(): String {
            val partes = porClase.entries.map { it.value to it.key.nombre }.sortedWith(compareByDescending<Pair<Int, String>> { it.first }.thenBy { it.second })
            val s = StringBuilder("$puestas anotaciones (${partes.joinToString(", ") { "${it.first} ${it.second}" }})")
            if (noEncontradas.isNotEmpty()) s.append("; no se encontraron en el texto: ${noEncontradas.take(4).joinToString(", ") { "«$it»" }}")
            if (sinSitio > 0) s.append("; $sinSitio no cupieron en el margen")
            return s.toString()
        }
    }
}
