package com.forge.pixpin.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
 * 2. Se pregunta a cada celda **cuánto alto necesitaría** con el ancho de las
 *    columnas que ocupa, y cada fila se queda con el de la celda más alta que
 *    empiece y acabe en ella.
 * 3. Las que ocupan varias filas van después: si no caben en la suma de las
 *    suyas, **el sobrante se reparte** entre ellas. Es su bucle del `per + rem`.
 * 4. Y al final se mide cada una **una sola vez**, ya con su tamaño exacto, y se
 *    coloca en la suma de las columnas y filas que tiene delante.
 *
 * El paso 2 pregunta en vez de medir a propósito. Su rejilla es una View de
 * Android y ahí `measure` se puede llamar las veces que haga falta —la suya lo
 * hace tres—; en Compose la segunda llamada lanza, y eso fue una caída de verdad
 * en un móvil. Los intrínsecos dan el mismo número sin gastar la única medida.
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

    // El ancho de la ventana se mide **fuera** del desplazamiento: dentro es
    // infinito, y con eso las columnas nunca podrían llenar la pantalla.
    BoxWithConstraints(modifier) {
        val ventana = constraints.maxWidth

        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Layout(
                content = {
                    anclas.forEach { ancla ->
                        Box(
                            Modifier
                                .border(1.dp, colorDelBorde)
                                .background(
                                    if (ancla.celda.cabecera) {
                                        fondoDeCabecera
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            contentAlignment = alineacionDeLaCelda(ancla.celda)
                        ) {
                            celda(ancla)
                        }
                    }
                }
            ) { medibles, _ ->
                if (medibles.isEmpty()) {
                    return@Layout layout(0, 0) {}
                }

                val minimo = MINIMO_DE_COLUMNA.roundToPx()
                val anchoDeColumna = maxOf(minimo, ventana / columnas)
                val anchos = IntArray(columnas) { anchoDeColumna }

                fun anchoDe(a: Ancla): Int =
                    (a.columna until minOf(a.columna + a.celda.anchoEnColumnas, columnas))
                        .sumOf { anchos.getOrElse(it) { 0 } }
                        .coerceAtLeast(minimo)

                // Las alturas se sacan de los **intrínsecos**, no midiendo.
                //
                // Su código es una View de Android y ahí se puede llamar a
                // `measure` las veces que haga falta; su rejilla lo hace tres.
                // En Compose medir dos veces el mismo hijo lanza, y esa fue la
                // caída que salió en el móvil. Preguntar por el alto que
                // necesitaría —`maxIntrinsicHeight`— da el mismo número sin
                // gastar la única medida que hay.
                val alturas = IntArray(filas)
                medibles.forEachIndexed { i, medible ->
                    val a = anclas[i]
                    if (a.celda.altoEnFilas > 1) return@forEachIndexed
                    val alto = medible.maxIntrinsicHeight(anchoDe(a))
                    if (a.fila in 0 until filas && alto > alturas[a.fila]) {
                        alturas[a.fila] = alto
                    }
                }

                // Las que ocupan varias filas: si no caben en la suma de las
                // suyas, el sobrante se reparte. Es su bucle del `per + rem`.
                medibles.forEachIndexed { i, medible ->
                    val a = anclas[i]
                    if (a.celda.altoEnFilas <= 1) return@forEachIndexed
                    val alto = medible.maxIntrinsicHeight(anchoDe(a))
                    val suyas = (a.fila until minOf(a.fila + a.celda.altoEnFilas, filas)).toList()
                    if (suyas.isEmpty()) return@forEachIndexed
                    val total = suyas.sumOf { alturas[it] }
                    if (alto > total) {
                        val falta = alto - total
                        val cada = falta / suyas.size
                        var resto = falta % suyas.size
                        suyas.forEach {
                            alturas[it] += cada + if (resto > 0) 1 else 0
                            if (resto > 0) resto--
                        }
                    }
                }

                val inicioDeColumna = IntArray(columnas + 1)
                for (c in 0 until columnas) {
                    inicioDeColumna[c + 1] = inicioDeColumna[c] + anchos[c]
                }
                val inicioDeFila = IntArray(filas + 1)
                for (f in 0 until filas) inicioDeFila[f + 1] = inicioDeFila[f] + alturas[f]

                // Y ahora sí: **una sola medida** por hijo, ya con su tamaño.
                val colocables = medibles.mapIndexed { i, medible ->
                    val a = anclas[i]
                    val ancho = anchoDe(a)
                    val alto = (a.fila until minOf(a.fila + a.celda.altoEnFilas, filas))
                        .sumOf { alturas.getOrElse(it) { 0 } }
                        .coerceAtLeast(1)
                    medible.measure(Constraints.fixed(ancho, alto))
                }

                layout(inicioDeColumna[columnas], inicioDeFila[filas]) {
                    colocables.forEachIndexed { i, placeable ->
                        val a = anclas[i]
                        placeable.place(
                            inicioDeColumna[a.columna.coerceIn(0, columnas)],
                            inicioDeFila[a.fila.coerceIn(0, filas)]
                        )
                    }
                }
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
