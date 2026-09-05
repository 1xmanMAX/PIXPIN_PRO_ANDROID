package com.forge.pixpin.motor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    onGrafica: () -> Unit = {}
) {
    /**
     * El nombre a medio escribir, o null si no se está guardando nada.
     *
     * El campo aparece **al pedir guardar** y no siempre: ocupa una línea entera
     * y la mayoría de las veces uno viene a coger una figura, no a dejar otra.
     */
    var nombre: String? by remember { mutableStateOf(null) }

    val propias = figuras.filter { it.propia }
    val fabrica = figuras.filter { !it.propia }

    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, modifier = modifier) {
        Column(Modifier.widthIn(max = 340.dp).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.figuras_titulo),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCerrar) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                }
            }
            Text(
                stringResource(R.string.figuras_ayuda),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp)
            ) {
                Apartado(stringResource(R.string.figuras_de_fabrica), fabrica, onInsertar, null)
                Apartado(
                    stringResource(R.string.figuras_mias), propias, onInsertar, onQuitar,
                    vacio = stringResource(R.string.figuras_sin_mias)
                )
            }

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
    modifier: Modifier = Modifier
) {
    Column(
        modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                Modifier
                    .size(LADO_DE_LA_CASILLA)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable { onInsertar(figura) }
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
            figura.nombre,
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
private val LADO_DE_LA_CASILLA = 130.dp
