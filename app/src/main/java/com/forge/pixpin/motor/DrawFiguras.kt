package com.forge.pixpin.motor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.R

/**
 * La lista de figuras, tal como se toca.
 *
 * Es una rejilla de miniaturas y poco más, y esa es la idea: **una figura se
 * reconoce mirándola**, no leyendo su nombre. Por eso cada casilla pinta la
 * figura de verdad —con el mismo renderizador que el lienzo, ver [DrawPreview]—
 * en vez de un icono que habría que dibujar aparte y mantener al día.
 *
 * Debajo, las dos formas de que la lista crezca: guardar lo que hay marcado y
 * pegar una tabla de la hoja de cálculo. Van aquí y no en la barra de
 * herramientas porque las tres cosas son lo mismo —traer algo hecho al dibujo—
 * y separarlas obligaría a acordarse de en qué menú estaba cada una.
 */
@Composable
fun PanelDeFiguras(
    figuras: List<FiguraGuardada>,
    onInsertar: (FiguraGuardada) -> Unit,
    onQuitar: (FiguraGuardada) -> Unit,
    /** Guardar lo que hay marcado con este nombre. */
    onGuardarSeleccion: (String) -> Unit,
    /** Si hay algo marcado que guardar. Sin nada, el botón explica por qué no. */
    puedeGuardar: Boolean,
    onPegarTabla: () -> Unit,
    onCerrar: () -> Unit,
    modifier: Modifier = Modifier,
    /** Abrir el diálogo de la gráfica de una función. Ver [Graficas]. */
    onGrafica: () -> Unit = {},
    /** Pegar el texto del portapapeles como un texto del dibujo. Ver [DrawController.pegarTexto]. */
    onPegarTexto: () -> Unit = {},
    /** Las de serie del PC, ver [FigurasDeSerie]: van por categorías. */
    deSerie: List<FiguraGuardada> = emptyList(),
    /**
     * **Para adjuntar al chat** (25-oct-2026, como el PC: «adjuntar figura abre la ventana de
     * figuras, selecciono, aparece el botón de adjuntar»). Con él, lo que hace «Usar» es adjuntar;
     * guardar, pegar y la gráfica, que son del lienzo, no salen.
     */
    onAdjuntar: ((FiguraGuardada) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    /**
     * **Usarla**: lo que el panel se abrió para hacer.
     *
     * **Tocar abre la vista rápida y mantener pulsada la usa ya** (27-oct-2026, el usuario: «al
     * tocar una figura, verla en grande antes de ponerla»). La miniatura de una hoja no deja ver
     * sus casillas; la vista sí ([VistaPreviaDeFiguraUi]). Y quien ya sabe cuál quiere no paga
     * el paso de más: la pulsación larga es el camino corto de antes.
     */
    val usar: (FiguraGuardada) -> Unit = onAdjuntar ?: onInsertar
    var viendo by remember { mutableStateOf<FiguraGuardada?>(null) }
    /**
     * **Día o noche, recordado** (27-oct-2026). De día no sale ninguna gemela «(noche)»; de noche
     * cada plantilla que la tiene sale con ella, en su sitio. Ver [FigurasDeSerie.segunElModo]. Se
     * guarda en una preferencia de este aparato, que no viaja: es cómo se mira, no qué hay.
     */
    val prefs = remember { context.getSharedPreferences(PREFS_DEL_PANEL, android.content.Context.MODE_PRIVATE) }
    var noche by remember { mutableStateOf(prefs.getBoolean(CLAVE_NOCHE, false)) }
    val ponerNoche: (Boolean) -> Unit = { n ->
        noche = n
        prefs.edit().putBoolean(CLAVE_NOCHE, n).apply()
    }
    /**
     * El nombre a medio escribir, o null si no se está guardando nada.
     *
     * El campo aparece **al pedir guardar** y no siempre: ocupa una línea entera
     * y la mayoría de las veces uno viene a coger una figura, no a dejar otra.
     */
    var nombre: String? by remember { mutableStateOf(null) }

    val propias = remember(figuras, noche) { FigurasDeSerie.segunElModo(figuras.filter { it.propia }, noche) }
    val fabrica = remember(figuras, noche) { FigurasDeSerie.segunElModo(figuras.filter { !it.propia }, noche) }
    val deSerieDelModo = remember(deSerie, noche) { FigurasDeSerie.segunElModo(deSerie, noche) }
    // **Por categorías, como el menú de figuras del PC** (25-oct-2026): «Mías» y «Básicas» primero,
    // luego las 35 de serie. Con 627 figuras la lista de antes, todo seguido, no servía.
    val grupos = remember(deSerieDelModo) { FigurasDeSerie.porCategoria(deSerieDelModo) }
    val pestanas = remember(grupos, propias.isEmpty()) {
        listOfNotNull("Mías".takeIf { propias.isNotEmpty() }, "Básicas") + grupos.map { it.first }
    }
    var pestana by remember { mutableStateOf(if (propias.isNotEmpty()) "Mías" else "Básicas") }
    var buscando by remember { mutableStateOf("") }
    val visibles: List<FiguraGuardada> = remember(pestana, buscando, propias, fabrica, grupos) {
        // Buscando, entre las del modo puesto: de noche, «diario» da la de noche y no las dos.
        if (buscando.isNotBlank()) FigurasDeSerie.buscar(propias + fabrica + deSerieDelModo, buscando)
        else when (pestana) {
            "Mías" -> propias
            "Básicas" -> fabrica
            else -> grupos.firstOrNull { it.first == pestana }?.second.orEmpty()
        }
    }

    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, modifier = modifier) {
        Column(Modifier.widthIn(max = 560.dp).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.figuras_titulo),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                InterruptorDiaNoche(noche, ponerNoche)
                IconButton(onClick = onCerrar) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                }
            }
            Text(
                if (onAdjuntar != null) "Toca una para verla en grande; mantenla pulsada para adjuntarla ya."
                else stringResource(R.string.figuras_ayuda),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = buscando, onValueChange = { buscando = it }, singleLine = true,
                placeholder = { Text("Buscar figura (cama, resistencia, semanal…)") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            if (buscando.isBlank()) androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(pestanas.size) { i ->
                    val c = pestanas[i]
                    androidx.compose.material3.FilterChip(
                        selected = c == pestana, onClick = { pestana = c },
                        label = { Text(c, maxLines = 1) }
                    )
                }
            }
            // Perezosa: solo se pintan las miniaturas que se ven (una plantilla de página tiene
            // cientos de piezas).
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(LADO_DE_LA_CASILLA - 10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (visibles.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (buscando.isNotBlank()) "Ninguna se llama así" else stringResource(R.string.figuras_sin_mias),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                items(visibles.size, key = { visibles[it].id }) { i ->
                    val f = visibles[i]
                    Casilla(
                        f, onInsertar = { viendo = it }, onQuitar = if (f.propia) onQuitar else null,
                        onLargo = usar
                    )
                }
            }
            viendo?.let { f ->
                VistaPreviaDeFiguraUi(
                    figura = f,
                    rotuloDeUsar = if (onAdjuntar != null) "Adjuntar" else "Usar",
                    onUsar = {
                        viendo = null
                        usar(f)
                    },
                    onCerrar = { viendo = null }
                )
            }
            // Guardar, pegar y la gráfica son del lienzo: adjuntando no salen. Con un `if` y no
            // con un `return@Column`, que sale de una función en línea y a R8 no le gusta.
            if (onAdjuntar == null) {
                if (nombre != null) {
                    // Al aceptar se cierra el campo: guardar dos veces seguidas la
                    // misma selección deja dos figuras iguales y nadie lo quiere.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = nombre.orEmpty(),
                            onValueChange = { nombre = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.figuras_nombre)) },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            onGuardarSeleccion(nombre.orEmpty())
                            nombre = null
                        }) {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    TextButton(
                        onClick = { nombre = "" },
                        enabled = puedeGuardar && nombre == null
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.figuras_guardar),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                if (!puedeGuardar) {
                    Text(
                        stringResource(R.string.figuras_guardar_ayuda),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(onClick = onPegarTabla) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.tabla_pegada_abrir),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                // **El texto copiado en cualquier sitio**, como un texto del dibujo con la letra del
                // panel: el «Pegar» del menú del lienzo del PC.
                TextButton(onClick = onPegarTexto) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.texto_pegado_abrir),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                // **La gráfica de una función**, escrita con su fórmula y sus límites.
                TextButton(onClick = onGrafica) {
                    Text("ƒ(x)", style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.grafica_abrir),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * **El teclado de fórmulas**: lo que una calculadora científica tiene y el teclado del
 * teléfono no. Cada tecla mete su texto donde está el cursor —una función mete su
 * paréntesis abierto—, y las de borrar y mover el cursor hacen lo suyo. Convive con el
 * teclado del sistema: el campo sigue siendo un campo normal.
 *
 * [conY] y [conT] añaden las variables de las superficies y de las curvas en el espacio.
 */
@Composable
fun TecladoDeFormulas(
    valor: androidx.compose.ui.text.input.TextFieldValue,
    onCambio: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    conY: Boolean = false,
    conT: Boolean = false
) {
    fun meter(texto: String, atras: Int = 0) {
        val a = valor.selection.min.coerceIn(0, valor.text.length)
        val b = valor.selection.max.coerceIn(0, valor.text.length)
        val nuevo = valor.text.substring(0, a) + texto + valor.text.substring(b)
        val cursor = a + texto.length - atras
        onCambio(androidx.compose.ui.text.input.TextFieldValue(nuevo, androidx.compose.ui.text.TextRange(cursor)))
    }
    fun borrar() {
        val a = valor.selection.min.coerceIn(0, valor.text.length)
        val b = valor.selection.max.coerceIn(0, valor.text.length)
        if (a != b) {
            onCambio(androidx.compose.ui.text.input.TextFieldValue(valor.text.removeRange(a, b), androidx.compose.ui.text.TextRange(a)))
        } else if (a > 0) {
            onCambio(androidx.compose.ui.text.input.TextFieldValue(valor.text.removeRange(a - 1, a), androidx.compose.ui.text.TextRange(a - 1)))
        }
    }
    fun mover(cuanto: Int) {
        val c = (valor.selection.end + cuanto).coerceIn(0, valor.text.length)
        onCambio(valor.copy(selection = androidx.compose.ui.text.TextRange(c)))
    }

    /** Una tecla: lo que enseña y lo que hace. */
    class Tecla(val rotulo: String, val fuerte: Boolean = false, val hace: () -> Unit)
    fun t(rotulo: String, texto: String = rotulo, atras: Int = 0) = Tecla(rotulo) { meter(texto, atras) }
    fun fn(rotulo: String, nombre: String = rotulo) = Tecla(rotulo, fuerte = true) { meter("$nombre()", 1) }

    val filas = buildList {
        add(listOf(t("7"), t("8"), t("9"), t("÷", "/"), t("(", "()", 1), t(")")))
        add(listOf(t("4"), t("5"), t("6"), t("×", "*"), t("^"), t("x²", "^2")))
        add(listOf(t("1"), t("2"), t("3"), t("−", "-"), fn("√", "sqrt"), t("π", "pi")))
        add(
            buildList {
                add(t("0")); add(t(".")); add(t(","))
                add(t("+")); add(Tecla("x", fuerte = true) { meter("x") })
                if (conY) add(Tecla("y", fuerte = true) { meter("y") })
                if (conT) add(Tecla("t", fuerte = true) { meter("t") })
                if (!conY && !conT) add(t("e"))
            }
        )
        add(listOf(fn("sin"), fn("cos"), fn("tan"), fn("ln"), fn("log"), fn("exp")))
        add(listOf(fn("abs"), t("|x|", "||", 1), t("<"), t(">"), t("≤", "<="), t("≥", ">=")))
        add(
            listOf(
                t("si", " si "), t(";", "; "), t("e"),
                Tecla("←") { mover(-1) }, Tecla("→") { mover(1) },
                Tecla("⌫", fuerte = true) { borrar() }
            )
        )
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (fila in filas) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (tecla in fila) {
                    Surface(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clickable { tecla.hace() },
                        shape = RoundedCornerShape(8.dp),
                        color = if (tecla.fuerte) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 1.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                tecla.rotulo,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * **La ecuación como es**: cada fórmula compuesta de verdad —fracciones, raíces,
 * exponentes— con el mismo compositor que usan las notas, y con su condición al lado si
 * es por partes. Lo que no se entiende se dice en rojo, sin más.
 */
@Composable
fun VistaDeFormula(
    prefijo: String,
    texto: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val compilada = remember(texto) { if (texto.isBlank()) null else Formula.compilar(texto) }
    Column(modifier.fillMaxWidth()) {
        if (texto.isBlank()) return@Column
        if (compilada == null) {
            Text(
                stringResource(R.string.grafica_formula_mal) + ": " + texto,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }
        for ((i, parte) in compilada.latex.withIndex()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (i == 0) "$prefijo = " else "     ",
                    color = color,
                    style = MaterialTheme.typography.bodyLarge
                )
                com.forge.pixpin.motormd.FormulaUi(
                    parte.first, tamanoSp = 18f, color = color,
                    modifier = Modifier.weight(1f, fill = false)
                )
                parte.second?.let { cond ->
                    Text("  si  ", color = color, style = MaterialTheme.typography.bodySmall)
                    com.forge.pixpin.motormd.FormulaUi(
                        cond, tamanoSp = 15f, color = color,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
    }
}

/** Un campo de número con etiqueta corta, para los límites. */
@Composable
private fun CampoNumero(valor: String, etiqueta: String, modifier: Modifier, onCambio: (String) -> Unit) {
    OutlinedTextField(
        value = valor, onValueChange = onCambio,
        label = { Text(etiqueta) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text
        ),
        modifier = modifier
    )
}

/** El número de un campo de límites, con coma decimal admitida. */
fun numeroDelCampo(t: String): Double? = t.trim().replace(',', '.').toDoubleOrNull()

/**
 * **El diálogo de la gráfica**: las fórmulas —una por renglón, cada una una curva—, la
 * ecuación compuesta debajo mientras se teclea, el teclado de fórmulas y los límites. Todo
 * con lo que hay de fábrica ya puesto, para que baste con teclear y aceptar. El botón no se
 * enciende hasta que todo se entiende. Ver [Graficas] y [Formula].
 */
@Composable
fun DialogoDeGrafica(
    onCerrar: () -> Unit,
    onAceptar: (Graficas.Peticion) -> Unit
) {
    var formulas by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("sin(x)", androidx.compose.ui.text.TextRange(6)))
    }
    var xDesde by remember { mutableStateOf("-5") }
    var xHasta by remember { mutableStateOf("5") }
    var yDesde by remember { mutableStateOf("-3") }
    var yHasta by remember { mutableStateOf("3") }
    var escala by remember { mutableStateOf("40") }

    val lineas = remember(formulas.text) { formulas.text.lines().map { it.trim() }.filter { it.isNotEmpty() } }
    val compilan = remember(lineas) {
        lineas.isNotEmpty() && lineas.all { l ->
            Formula.compilar(l)?.let { c -> c.variables.all { it == "x" } } == true
        }
    }
    val peticion = remember(lineas, xDesde, xHasta, yDesde, yHasta, escala) {
        val a = numeroDelCampo(xDesde); val b = numeroDelCampo(xHasta)
        val c = numeroDelCampo(yDesde); val d = numeroDelCampo(yHasta)
        val k = numeroDelCampo(escala)
        if (a == null || b == null || c == null || d == null || k == null) null
        else if (b <= a || d <= c || k <= 0.0) null
        else Graficas.Peticion(lineas, a, b, c, d, k)
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(stringResource(R.string.grafica_titulo)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = formulas,
                    onValueChange = { formulas = it },
                    label = { Text(stringResource(R.string.grafica_formulas)) },
                    placeholder = { Text("x^2 - 2x\nsin(x)/x\nx^2 si x<0; 2x si x>=0") },
                    isError = !compilan,
                    supportingText = { Text(stringResource(R.string.grafica_formulas_ayuda)) },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                // La ecuación como es, una por curva y del color con el que va a salir.
                for ((i, l) in lineas.withIndex()) {
                    val color = if (i == 0) MaterialTheme.colorScheme.onSurface
                    else Color(parseColor(Graficas.COLORES_DE_CURVAS[(i - 1) % Graficas.COLORES_DE_CURVAS.size], 255))
                    VistaDeFormula("y", l, color)
                }
                TecladoDeFormulas(formulas, { formulas = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampoNumero(xDesde, stringResource(R.string.grafica_x_desde), Modifier.weight(1f)) { xDesde = it }
                    CampoNumero(xHasta, stringResource(R.string.grafica_x_hasta), Modifier.weight(1f)) { xHasta = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampoNumero(yDesde, stringResource(R.string.grafica_y_desde), Modifier.weight(1f)) { yDesde = it }
                    CampoNumero(yHasta, stringResource(R.string.grafica_y_hasta), Modifier.weight(1f)) { yHasta = it }
                }
                CampoNumero(escala, stringResource(R.string.grafica_escala), Modifier.fillMaxWidth()) { escala = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { peticion?.let(onAceptar) },
                enabled = compilan && peticion != null
            ) { Text(stringResource(R.string.grafica_insertar)) }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text(stringResource(R.string.grafica_cancelar)) }
        }
    )
}

/** Un grupo de la lista, con su título. Vacío, se dice en vez de no salir. */
@Composable
private fun Apartado(
    titulo: String,
    figuras: List<FiguraGuardada>,
    onInsertar: (FiguraGuardada) -> Unit,
    onQuitar: ((FiguraGuardada) -> Unit)?,
    vacio: String? = null
) {
    if (figuras.isEmpty() && vacio == null) return
    Text(
        titulo,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
    if (figuras.isEmpty()) {
        Text(
            vacio.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    // **De dos en dos, y grandes.** A tres por fila la miniatura se quedaba en
    // ochenta puntos, y a ese tamaño una cuadrícula y un plano de funciones son
    // el mismo cuadro gris: había que leer el nombre para saber cuál era, o sea
    // que la miniatura no servía para nada. Con la mitad de casillas se
    // reconocen de un vistazo, que es lo que se viene a hacer aquí.
    figuras.chunked(2).forEach { tanda ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tanda.forEach { figura ->
                Casilla(figura, onInsertar, onQuitar, Modifier.weight(1f))
            }
            // Los huecos de la última tanda, para que no se estiren las que hay.
            repeat(2 - tanda.size) { Box(Modifier.weight(1f)) }
        }
    }
}

/** Una figura de la lista: su dibujo, su nombre y —si es mía— cómo quitarla. */
@Composable
private fun Casilla(
    figura: FiguraGuardada,
    onInsertar: (FiguraGuardada) -> Unit,
    onQuitar: ((FiguraGuardada) -> Unit)?,
    modifier: Modifier = Modifier,
    marcada: Boolean = false,
    /** Mantener pulsada: el camino corto, usarla sin verla. */
    onLargo: ((FiguraGuardada) -> Unit)? = null
) {
    Column(
        modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                Modifier
                    .size(LADO_DE_LA_CASILLA)
                    // **Blanco fijo, y la de noche sobre su gris** (27-oct-2026): la miniatura no
                    // sigue el tema de la aplicación ni el papel del lienzo. La hoja de noche trae
                    // su propio papel; el gris de detrás es para que no quede un marco blanco.
                    .background(if (FigurasDeSerie.esDeNoche(figura)) FONDO_DE_LA_DE_NOCHE else Color.White, RoundedCornerShape(10.dp))
                    .border(
                        if (marcada) 3.dp else 1.dp,
                        if (marcada) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp)
                    )
                    .combinedClickable(
                        onClick = { onInsertar(figura) },
                        onLongClick = onLargo?.let { l -> { l(figura) } }
                    )
                    .padding(6.dp)
            ) {
                // La figura de verdad, encuadrada por el propio renderizador.
                DrawPreview(
                    scene = Scene(elements = figura.elementos),
                    modifier = Modifier.size(LADO_DE_LA_CASILLA - 12.dp)
                )
            }
            if (onQuitar != null) {
                IconButton(onClick = { onQuitar(figura) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.figuras_quitar),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Text(
            FigurasDeSerie.nombreCorto(figura.nombre),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = ComposeTextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Lo que mide una casilla de la lista.
 *
 * Grande a propósito: la miniatura **es** la forma de reconocer una figura, y por
 * debajo de esto un plano y una cuadrícula se ven igual. Lo que se paga es
 * desplazar más, que es barato — la lista es perezosa.
 */
private val LADO_DE_LA_CASILLA = 120.dp

/** Detrás de la miniatura de una de noche: el gris neutro de su zona de notas (`noche.rs` del PC). */
private val FONDO_DE_LA_DE_NOCHE = Color(0xFF2A2D35)

private const val PREFS_DEL_PANEL = "panel_de_figuras"
private const val CLAVE_NOCHE = "noche"

/**
 * **El interruptor de día y noche** del panel: un sol y una luna, el puesto marcado. Dos botones y
 * no un `Switch` porque se lee de un vistazo cuál de los dos está, sin saber hacia qué lado es
 * «encendido».
 */
@Composable
private fun InterruptorDiaNoche(noche: Boolean, onCambio: (Boolean) -> Unit) {
    Row(
        Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for ((esNoche, icono, dice) in listOf(Triple(false, "☀", "Figuras de día"), Triple(true, "☾", "Figuras de noche"))) {
            val puesto = esNoche == noche
            Box(
                Modifier
                    .size(32.dp)
                    .background(
                        if (puesto) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        RoundedCornerShape(50)
                    )
                    .clickable(onClickLabel = dice) { onCambio(esNoche) }
                    .semantics { contentDescription = dice; selected = puesto },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    icono,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (puesto) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
