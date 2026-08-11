package com.forge.pixpin.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    marcado: Tablas.Marcado? = null,
    conAsas: Boolean = false,
    onColumna: (Int) -> Unit = {},
    onFila: (Int) -> Unit = {},
    onCelda: (Int, Int) -> Unit = { _, _ -> },
    onCeldaLarga: (Int, Int) -> Unit = { _, _ -> },
    celda: @Composable (Ancla) -> Unit
) {
    val anclas = anclasDe(tabla)
    val columnas = tabla.columnas.coerceAtLeast(1)
    val filas = tabla.filas.size.coerceAtLeast(1)
    val colorDelBorde = MaterialTheme.colorScheme.outlineVariant
    val fondoDeCabecera = MaterialTheme.colorScheme.surfaceVariant
    val marcadoColor = MaterialTheme.colorScheme.primary

    // El ancho de la ventana se mide **fuera** del desplazamiento: dentro es
    // infinito, y con eso las columnas nunca podrían llenar la pantalla.
    BoxWithConstraints(modifier) {
        val ventana = constraints.maxWidth

        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Layout(
                content = {
                    // Primero las celdas, luego las asas de columna y por último
                    // las de fila. El orden importa: la medida las reparte por
                    // posición, que es como habla un Layout.
                    anclas.forEach { ancla ->
                        val dentro = marcado != null &&
                            (ancla.fila to ancla.columna) in marcado
                        Box(
                            Modifier
                                .border(1.dp, colorDelBorde)
                                .background(
                                    when {
                                        dentro -> marcadoColor.copy(alpha = 0.18f)
                                        ancla.celda.cabecera -> fondoDeCabecera
                                        else -> Color.Transparent
                                    }
                                )
                                // **Solo la pulsación larga, y sin quedarse el
                                // toque.** Con `detectTapGestures` la celda se
                                // comía el toque antes de que llegara al campo
                                // de texto de dentro, así que la tabla se creaba
                                // y luego no se podía escribir en ella.
                                //
                                // Mirando en la pasada inicial y sin consumir
                                // nada, el dedo sigue su camino hasta el campo;
                                // aquí solo se cuenta cuánto tarda en levantarse
                                // para saber si fue larga.
                                .pointerInput(ancla.fila, ancla.columna) {
                                    awaitEachGesture {
                                        awaitFirstDown(
                                            requireUnconsumed = false,
                                            pass = PointerEventPass.Initial
                                        )
                                        val seLevanto = withTimeoutOrNull(
                                            viewConfiguration.longPressTimeoutMillis
                                        ) {
                                            waitForUpOrCancellation(PointerEventPass.Initial)
                                        }
                                        if (seLevanto == null) {
                                            onCeldaLarga(ancla.fila, ancla.columna)
                                        }
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            contentAlignment = alineacionDeLaCelda(ancla.celda)
                        ) {
                            celda(ancla)
                        }
                    }

                    if (conAsas) {
                        (0 until columnas).forEach { c ->
                            val suya = marcado != null &&
                                c in marcado.columnas && marcado.filas.count() >= filas
                            Asa(if (suya) marcadoColor else colorDelBorde) { onColumna(c) }
                        }
                        (0 until filas).forEach { f ->
                            val suya = marcado != null &&
                                f in marcado.filas && marcado.columnas.count() >= columnas
                            Asa(if (suya) marcadoColor else colorDelBorde) { onFila(f) }
                        }
                    }
                }
            ) { medibles, _ ->
                if (medibles.isEmpty()) {
                    return@Layout layout(0, 0) {}
                }

                val minimo = MINIMO_DE_COLUMNA.roundToPx()
                val hueco = if (conAsas) ASA_DP.roundToPx() else 0
                val libre = (ventana - hueco).coerceAtLeast(0)
                val anchoDeColumna = maxOf(minimo, libre / columnas)
                val anchos = IntArray(columnas) { anchoDeColumna }

                fun anchoDe(a: Ancla): Int =
                    (a.columna until minOf(a.columna + a.celda.anchoEnColumnas, columnas))
                        .sumOf { anchos.getOrElse(it) { 0 } }
                        .coerceAtLeast(minimo)

                val deCeldas = medibles.take(anclas.size)

                // Las alturas se sacan de los **intrínsecos**, no midiendo: en
                // Compose solo se puede medir una vez cada hijo.
                val alturas = IntArray(filas)
                deCeldas.forEachIndexed { i, medible ->
                    val a = anclas[i]
                    if (a.celda.altoEnFilas > 1) return@forEachIndexed
                    val alto = medible.maxIntrinsicHeight(anchoDe(a))
                    if (a.fila in 0 until filas && alto > alturas[a.fila]) {
                        alturas[a.fila] = alto
                    }
                }
                deCeldas.forEachIndexed { i, medible ->
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
                // Ninguna fila por debajo del alto de un dedo. Una celda de dos
                // puntos no se puede tocar, y una tabla recién hecha está vacía.
                val suelo = ALTO_MINIMO_DE_FILA.roundToPx()
                for (f in 0 until filas) {
                    if (alturas[f] < suelo) alturas[f] = suelo
                }

                val inicioDeColumna = IntArray(columnas + 1)
                for (c in 0 until columnas) {
                    inicioDeColumna[c + 1] = inicioDeColumna[c] + anchos[c]
                }
                val inicioDeFila = IntArray(filas + 1)
                for (f in 0 until filas) inicioDeFila[f + 1] = inicioDeFila[f] + alturas[f]

                val colocables = deCeldas.mapIndexed { i, medible ->
                    val a = anclas[i]
                    val alto = (a.fila until minOf(a.fila + a.celda.altoEnFilas, filas))
                        .sumOf { alturas.getOrElse(it) { 0 } }
                        .coerceAtLeast(1)
                    medible.measure(Constraints.fixed(anchoDe(a), alto))
                }

                // Las asas se miden con **el mismo ancho y alto** que la columna
                // o la fila a la que pertenecen. Ese es el arreglo: antes se
                // dibujaban aparte con un ancho fijo y no cuadraban con nada.
                val asasDeColumna = if (!conAsas) emptyList() else
                    medibles.subList(anclas.size, anclas.size + columnas)
                        .mapIndexed { c, m -> m.measure(Constraints.fixed(anchos[c], hueco)) }
                val asasDeFila = if (!conAsas) emptyList() else
                    medibles.subList(anclas.size + columnas, medibles.size)
                        .mapIndexed { f, m -> m.measure(Constraints.fixed(hueco, alturas[f])) }

                layout(hueco + inicioDeColumna[columnas], hueco + inicioDeFila[filas]) {
                    colocables.forEachIndexed { i, placeable ->
                        val a = anclas[i]
                        placeable.place(
                            hueco + inicioDeColumna[a.columna.coerceIn(0, columnas)],
                            hueco + inicioDeFila[a.fila.coerceIn(0, filas)]
                        )
                    }
                    asasDeColumna.forEachIndexed { c, p ->
                        p.place(hueco + inicioDeColumna[c], 0)
                    }
                    asasDeFila.forEachIndexed { f, p ->
                        p.place(0, hueco + inicioDeFila[f])
                    }
                }
            }
        }
    }
}

/** Una asa: la barrita que marca una fila o una columna entera al tocarla. */
@Composable
private fun Asa(color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(1.dp)
            .background(color, RoundedCornerShape(2.dp))
            .clickable { onClick() }
    )
}

/** Lo que ocupan las asas. Su `HANDLE_PAD_DP`. */
val ASA_DP = 14.dp

/** Lo menos que puede medir una fila para seguir siendo tocable. */
val ALTO_MINIMO_DE_FILA = 40.dp

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

/** Lo que se puede pedir desde el menú de la tabla. */
enum class AccionDeTabla {
    IZQUIERDA, CENTRO, DERECHA,
    ARRIBA, MEDIO, ABAJO,
    DESTACAR, COMBINAR, SEPARAR,
    FILA_ARRIBA, FILA_ABAJO, COLUMNA_IZQUIERDA, COLUMNA_DERECHA,
    QUITAR_FILA, QUITAR_COLUMNA
}

/**
 * El menú de la tabla: alineación, destacar, combinar, separar y añadir.
 *
 * Sale al mantener pulsada una celda y al tocar un asa. Es lo que tiene su
 * editor detrás de las asas y del menú de celda, con sus mismas acciones —
 * `setAlign` y `setVAlign` en las seis posiciones, `setHeader`, `mergeCells`,
 * `unmergeCell`, `insertRowAt` e `insertColumnAt`.
 *
 * Va **pegado a la tabla y no en una ventana flotante** a propósito: la nota
 * también se edita desde el pin, que es una ventana del servicio y donde un
 * menú emergente no tiene actividad que lo sostenga. Así el mismo menú vale en
 * los dos sitios.
 */
@Composable
fun MenuDeTabla(
    puedeCombinar: Boolean,
    puedeSeparar: Boolean,
    onAccion: (AccionDeTabla) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 8.dp,
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(4.dp)) {
            // Las seis posiciones: tres a lo ancho y tres a lo alto, como su
            // align y su valign. Juntas y en una fila porque son la misma
            // pregunta hecha en dos ejes.
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotonDelMenu(Icons.Filled.FormatAlignLeft, "Izquierda") {
                    onAccion(AccionDeTabla.IZQUIERDA)
                }
                BotonDelMenu(Icons.Filled.FormatAlignCenter, "Centrado") {
                    onAccion(AccionDeTabla.CENTRO)
                }
                BotonDelMenu(Icons.Filled.FormatAlignRight, "Derecha") {
                    onAccion(AccionDeTabla.DERECHA)
                }
                Separador()
                BotonDelMenu(Icons.Filled.VerticalAlignTop, "Arriba") {
                    onAccion(AccionDeTabla.ARRIBA)
                }
                BotonDelMenu(Icons.Filled.VerticalAlignCenter, "En medio") {
                    onAccion(AccionDeTabla.MEDIO)
                }
                BotonDelMenu(Icons.Filled.VerticalAlignBottom, "Abajo") {
                    onAccion(AccionDeTabla.ABAJO)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                BotonDelMenu(Icons.Filled.Highlight, "Destacar") {
                    onAccion(AccionDeTabla.DESTACAR)
                }
                if (puedeCombinar) {
                    BotonDelMenu(Icons.Filled.Merge, "Combinar") {
                        onAccion(AccionDeTabla.COMBINAR)
                    }
                }
                if (puedeSeparar) {
                    BotonDelMenu(Icons.Filled.CallSplit, "Separar") {
                        onAccion(AccionDeTabla.SEPARAR)
                    }
                }
                Separador()
                BotonDelMenu(Icons.Filled.ArrowUpward, "Fila arriba") {
                    onAccion(AccionDeTabla.FILA_ARRIBA)
                }
                BotonDelMenu(Icons.Filled.ArrowDownward, "Fila abajo") {
                    onAccion(AccionDeTabla.FILA_ABAJO)
                }
                BotonDelMenu(Icons.AutoMirrored.Filled.ArrowBack, "Columna a la izquierda") {
                    onAccion(AccionDeTabla.COLUMNA_IZQUIERDA)
                }
                BotonDelMenu(Icons.AutoMirrored.Filled.ArrowForward, "Columna a la derecha") {
                    onAccion(AccionDeTabla.COLUMNA_DERECHA)
                }
                Separador()
                BotonDelMenu(
                    Icons.Filled.DeleteOutline,
                    "Quitar la fila",
                    MaterialTheme.colorScheme.error
                ) { onAccion(AccionDeTabla.QUITAR_FILA) }
                BotonDelMenu(
                    Icons.Filled.DeleteSweep,
                    "Quitar la columna",
                    MaterialTheme.colorScheme.error
                ) { onAccion(AccionDeTabla.QUITAR_COLUMNA) }
            }
        }
    }
}

@Composable
private fun BotonDelMenu(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    nombre: String,
    tinte: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(icono, contentDescription = nombre, modifier = Modifier.size(19.dp), tint = tinte)
    }
}

@Composable
private fun Separador() {
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(22.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
