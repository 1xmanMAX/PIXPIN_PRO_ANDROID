package com.forge.pixpin.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * La rejilla de la tabla, con el algoritmo de su `RichTableCellGrid`.
 *
 * ## Por qué hace falta una disposición propia
 *
 * Una tabla con celdas fusionadas **no es una lista de filas**. Una celda con
 * `rowspan` ocupa sitio en dos filas a la vez, y con una fila detrás de otra
 * —que es como estaba antes— eso no se puede dibujar: cada fila se mide sola y
 * no sabe nada de la de abajo. Por eso lo suyo es un `ViewGroup` que mide y
 * coloca a mano, y por eso aquí es un `Layout`.
 *
 * ## Su algoritmo, tal cual
 *
 * 1. **Todas las columnas miden igual**, y al menos [MINIMO_DE_COLUMNA]: su
 *    `equalWidth = max(dp(MIN_COL_DP), parentWidth / colCount)`. Nada de repartir
 *    según el contenido — una tabla que baila de ancho al escribir es peor que
 *    una con una columna algo corta.
 * 2. Se mide cada celda con **el ancho de todas las columnas que ocupa**, a lo
 *    alto libre, y cada fila se queda con la altura de la celda más alta que
 *    empiece y acabe en ella.
 * 3. Las que ocupan varias filas se miden después: si no caben en la suma de sus
 *    filas, **el sobrante se reparte** entre ellas. Es su bucle del `per + rem`.
 * 4. Se vuelve a medir cada una con su tamaño exacto y se coloca en la suma de
 *    las columnas y filas que tiene delante.
 *
 * La usan igual el editor y la vista, así que una tabla se ve **exactamente
 * igual** mientras se escribe y después.
 */
@Composable
fun RejillaDeTabla(
    tabla: MarkdownBlock.Tabla,
    modifier: Modifier = Modifier,
    colorDelBorde: Color = MaterialTheme.colorScheme.outlineVariant,
    fondoDeCabecera: Color = MaterialTheme.colorScheme.surfaceVariant,
    celda: @Composable (Ancla) -> Unit
) {
    val anclas = anclasDe(tabla)
    val columnas = tabla.columnas.coerceAtLeast(1)
    val filas = tabla.filas.size.coerceAtLeast(1)

    Layout(
        modifier = modifier,
        content = {
            anclas.forEach { ancla ->
                Box(
                    Modifier
                        .border(1.dp, colorDelBorde)
                        .background(
                            if (ancla.celda.cabecera) fondoDeCabecera else Color.Transparent
                        )
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    contentAlignment = alineacionDeLaCelda(ancla.celda)
                ) {
                    celda(ancla)
                }
            }
        }
    ) { medibles, restricciones ->
        if (medibles.isEmpty()) {
            return@Layout layout(0, 0) {}
        }

        val minimo = MINIMO_DE_COLUMNA.roundToPx()
        val disponible = if (restricciones.hasBoundedWidth) restricciones.maxWidth else 0
        val anchoDeColumna = maxOf(minimo, disponible / columnas)
        val anchos = IntArray(columnas) { anchoDeColumna }

        fun anchoDe(a: Ancla): Int =
            (a.columna until minOf(a.columna + a.celda.anchoEnColumnas, columnas))
                .sumOf { anchos[it] }

        // Primera pasada: la altura de cada fila la marca la celda más alta que
        // empieza y acaba en ella.
        val alturas = IntArray(filas)
        val medidos = arrayOfNulls<androidx.compose.ui.layout.Placeable>(medibles.size)
        medibles.forEachIndexed { i, medible ->
            val a = anclas[i]
            if (a.celda.altoEnFilas > 1) return@forEachIndexed
            val p = medible.measure(
                Constraints(minWidth = anchoDe(a), maxWidth = anchoDe(a))
            )
            medidos[i] = p
            if (a.fila in 0 until filas && p.height > alturas[a.fila]) {
                alturas[a.fila] = p.height
            }
        }

        // Segunda: las que ocupan varias filas. Si no caben, el sobrante se
        // reparte entre sus filas — su bucle del `per + rem`.
        medibles.forEachIndexed { i, medible ->
            val a = anclas[i]
            if (a.celda.altoEnFilas <= 1) return@forEachIndexed
            val p = medible.measure(
                Constraints(minWidth = anchoDe(a), maxWidth = anchoDe(a))
            )
            medidos[i] = p
            val suyas = (a.fila until minOf(a.fila + a.celda.altoEnFilas, filas)).toList()
            val total = suyas.sumOf { alturas[it] }
            if (p.height > total && suyas.isNotEmpty()) {
                val falta = p.height - total
                val cada = falta / suyas.size
                var resto = falta % suyas.size
                suyas.forEach {
                    alturas[it] += cada + if (resto > 0) 1 else 0
                    if (resto > 0) resto--
                }
            }
        }

        val inicioDeColumna = IntArray(columnas + 1)
        for (c in 0 until columnas) inicioDeColumna[c + 1] = inicioDeColumna[c] + anchos[c]
        val inicioDeFila = IntArray(filas + 1)
        for (f in 0 until filas) inicioDeFila[f + 1] = inicioDeFila[f] + alturas[f]

        // Tercera: cada una con su tamaño exacto, ya sabiendo cuánto miden sus
        // filas. Sin esto, una celda fusionada dejaría un hueco debajo.
        val finales = medibles.mapIndexed { i, medible ->
            val a = anclas[i]
            val ancho = anchoDe(a)
            val alto = (a.fila until minOf(a.fila + a.celda.altoEnFilas, filas))
                .sumOf { alturas[it] }
            medible.measure(
                Constraints(
                    minWidth = ancho, maxWidth = ancho,
                    minHeight = alto, maxHeight = alto
                )
            )
        }

        layout(inicioDeColumna[columnas], inicioDeFila[filas]) {
            finales.forEachIndexed { i, placeable ->
                val a = anclas[i]
                placeable.place(
                    inicioDeColumna[a.columna.coerceIn(0, columnas)],
                    inicioDeFila[a.fila.coerceIn(0, filas)]
                )
            }
        }
    }
}

/** Cuánto mide una columna como mínimo. Su `MIN_COL_DP`. */
val MINIMO_DE_COLUMNA = 80.dp

/**
 * Una celda ancla y dónde está.
 *
 * [indiceEnLaFila] es su sitio **en la lista de la fila**, que no es lo mismo
 * que [columna]: la fila guarda solo las anclas, así que con una fusión de dos
 * columnas la celda que se ve en la columna 2 puede ser la primera de la lista.
 * Quien quiera cambiarla tiene que usar este índice, no el de la rejilla.
 */
data class Ancla(
    val fila: Int,
    val columna: Int,
    val indiceEnLaFila: Int,
    val celda: Celda
)

/** Las celdas ancla de la tabla, con su sitio en la rejilla y en su fila. */
fun anclasDe(tabla: MarkdownBlock.Tabla): List<Ancla> {
    val rejilla = Tablas.rejilla(tabla)
    val salida = mutableListOf<Ancla>()
    rejilla.forEachIndexed { f, fila ->
        var enLaLista = 0
        fila.forEachIndexed { c, hueco ->
            if (hueco != null && hueco.esElAncla && hueco.filaDelAncla == f) {
                salida += Ancla(f, c, enLaLista, hueco.celda)
                enLaLista++
            }
        }
    }
    return salida
}

/** Las dos alineaciones juntas, como su `align` + `valign`. */
fun alineacionDeLaCelda(celda: Celda): Alignment = when (celda.altura) {
    AlturaEnCelda.ARRIBA -> when (celda.alineacion) {
        Alineacion.CENTRO -> Alignment.TopCenter
        Alineacion.DERECHA -> Alignment.TopEnd
        else -> Alignment.TopStart
    }
    AlturaEnCelda.MEDIO -> when (celda.alineacion) {
        Alineacion.CENTRO -> Alignment.Center
        Alineacion.DERECHA -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    AlturaEnCelda.ABAJO -> when (celda.alineacion) {
        Alineacion.CENTRO -> Alignment.BottomCenter
        Alineacion.DERECHA -> Alignment.BottomEnd
        else -> Alignment.BottomStart
    }
}
