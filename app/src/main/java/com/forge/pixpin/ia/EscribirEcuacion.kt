package com.forge.pixpin.ia

import com.forge.pixpin.motor.Ecuacion
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ItemStyle
import com.forge.pixpin.motor.MedidaDeTexto
import com.forge.pixpin.motormd.Formulas
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * **Ecuaciones compuestas en el lienzo** (`escribir_ecuacion`, PC `pixpin-ia/src/ecuacion.rs`): la
 * fórmula como se escribe en la pizarra —fracciones con su raya, raíces con su tejado, exponentes
 * subidos, griegas— y no `sqrt(b^2-4ac)` a máquina.
 *
 * No hay un compositor nuevo: se juntan los dos que ya hay, como en el PC. **Leer**: [Formulas.leer]
 * (el de las notas, el mismo que el PC copió de aquí). **Componer**: las cajas de [Ecuacion] (las del
 * texto rico del lienzo), que dan textos y rayas del lienzo.
 */
object EscribirEcuacion {
    const val NOMBRE = "escribir_ecuacion"
    const val MAX_LARGO = 600
    const val MAX_LINEAS = 12

    data class Pedido(val lineas: List<String>, val donde: Pair<Double, Double>?, val tam: Double, val color: String)

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)

    fun validar(args: JsonObject, avisos: MutableList<String>): Pedido {
        val texto = listOf("ecuacion", "formula", "latex", "texto").firstNotNullOfOrNull { k ->
            (args[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        } ?: throw mal("falta «ecuacion» (en LaTeX o escrita normal)")
        if (texto.codePointCount(0, texto.length) > MAX_LARGO) throw mal("la ecuacion pasa de $MAX_LARGO caracteres: partela en varias")
        // Los modelos escapan de más (`\\frac` en vez de `\frac`): una barra doble pegada a una letra es un mandato.
        val sinDobles = StringBuilder()
        var i = 0
        while (i < texto.length) {
            if (texto[i] == '\\' && texto.getOrNull(i + 1) == '\\' && texto.getOrNull(i + 2)?.let { it in 'a'..'z' || it in 'A'..'Z' } == true) { i++; continue }
            sinDobles.append(texto[i]); i++
        }
        // Sin los `$` de alrededor ni los entornos de alineado.
        var limpio = sinDobles.toString().trim('$')
        for (q in listOf("\\begin{aligned}", "\\end{aligned}", "\\begin{align*}", "\\end{align*}", "\\begin{align}", "\\end{align}", "\\[", "\\]")) limpio = limpio.replace(q, "")
        val lineas = partirLineas(limpio)
        if (lineas.isEmpty()) throw mal("la ecuacion esta vacia")
        if (lineas.size > MAX_LINEAS) throw mal("como mucho $MAX_LINEAS lineas por llamada")
        val abre = limpio.count { it == '{' }; val cierra = limpio.count { it == '}' }
        if (abre != cierra) throw mal("las llaves no cuadran ($abre «{» y $cierra «}»)")
        val raros = lineas.flatMap { Formulas.desconocidos(Formulas.leer(it)) }
        if (raros.isNotEmpty()) avisos += "no se entendio ${raros.joinToString(", ")} y sale tal cual"
        fun num(k: String, desde: Double, hasta: Double): Double? {
            val v = args[k] ?: return null
            if (v is JsonNull) return null
            return (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it in desde..hasta }
                ?: throw mal("«$k» tiene que ser un numero de ${bonito(desde)} a ${bonito(hasta)}")
        }
        val x = num("x", -200.0, 2000.0); val y = num("y", -200.0, 2000.0)
        val tam = num("tam_letra", 8.0, 160.0) ?: 32.0
        val color = (args["color"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(Herramientas::color) ?: "#1e1e1e"
        return Pedido(lineas, if (x != null && y != null) x to y else null, tam, color)
    }

    private fun bonito(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    /**
     * **Los renglones de una ecuación** (PC `partir_lineas`): se parte por `\\` y por saltos de línea y
     * se quitan los `&` de alineado, pero SOLO fuera de los entornos (`bmatrix`, `cases`): dentro son
     * las filas y celdas de la matriz.
     */
    fun partirLineas(texto: String): List<String> {
        val lineas = mutableListOf<String>()
        val actual = StringBuilder()
        var prof = 0
        var i = 0
        fun empieza(s: String) = texto.startsWith(s, i)
        while (i < texto.length) {
            if (empieza("\\begin{")) prof++ else if (empieza("\\end{")) prof = maxOf(prof - 1, 0)
            val c = texto[i]
            if (prof == 0) {
                if (c == '\\' && texto.getOrNull(i + 1) == '\\') { lineas += actual.toString(); actual.clear(); i += 2; continue }
                if (c == '\n') { lineas += actual.toString(); actual.clear(); i++; continue }
                if (c == '&' && (i == 0 || texto[i - 1] != '\\')) { i++; continue }
            } else if (c == '\n') { actual.append(' '); i++; continue }
            // Un mandato con su barra, de una vez (que `\\` dentro no se lea como dos barras sueltas).
            if (c == '\\' && i + 1 < texto.length) { actual.append('\\').append(texto[i + 1]); i += 2; continue }
            actual.append(c); i++
        }
        lineas += actual.toString()
        return lineas.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * **Las piezas de la ecuación ya colocadas** en el marco de 800×600 del modelo (o en su `x, y`), y lo
     * que ocupa. [medir] mide con la letra de verdad (Fraunces, la de serifa de los libros).
     */
    fun piezas(e: Pedido, medir: MedidaDeTexto): Pair<List<Element>, Pair<Double, Double>> {
        val estilo = ItemStyle(strokeColor = e.color, fontSize = e.tam, fontFamily = ItemStyle.FONT_FRAUNCES, roughness = 0)
        val cajas = e.lineas.map { Ecuacion.deFormula(Formulas.leer(it), e.tam, medir, false) }
        val col = Ecuacion.columna(cajas, e.tam * 0.6)
        val (x, y) = e.donde ?: (((Herramientas.ANCHO_LIENZO - col.ancho) / 2) to ((Herramientas.ALTO_LIENZO - col.alto) / 2))
        val grupo = com.forge.pixpin.motor.randomId()
        return col.elementos(x, y, estilo).map { it.copy(groupIds = listOf(grupo)) } to (col.ancho to col.alto)
    }

    /** La medida de a ojo (sin Android: pruebas), la del exportador del PC: 0,55 de la letra por carácter. */
    val MEDIDA_ESTIMADA: MedidaDeTexto = { t, tam -> t.codePointCount(0, t.length) * tam * 0.55 to tam * 1.25 }
}
