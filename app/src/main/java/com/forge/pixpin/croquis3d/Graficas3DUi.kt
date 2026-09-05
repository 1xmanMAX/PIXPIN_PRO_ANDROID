package com.forge.pixpin.croquis3d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.forge.pixpin.R
import com.forge.pixpin.motor.Formula
import com.forge.pixpin.motor.TecladoDeFormulas
import com.forge.pixpin.motor.VistaDeFormula
import com.forge.pixpin.motor.numeroDelCampo

/**
 * **El diálogo de la gráfica en el espacio**: superficie `z = f(x, y)` o curva
 * `(x(t), y(t), z(t))`, con el mismo teclado de fórmulas y la misma ecuación compuesta que
 * la gráfica plana. El teclado escribe en el campo que tenga el foco. Ver [Graficas3D].
 */
@Composable
fun DialogoDeGrafica3D(
    onCerrar: () -> Unit,
    onAceptar: (List<Trazo3D>) -> Unit,
    color: String,
    grosor: Double
) {
    var esSuperficie by remember { mutableStateOf(true) }
    val campos = remember {
        mutableListOf(
            mutableStateOf(TextFieldValue("sin(x) cos(y)", TextRange(13))),
            mutableStateOf(TextFieldValue("cos(t)", TextRange(6))),
            mutableStateOf(TextFieldValue("sin(t)", TextRange(6))),
            mutableStateOf(TextFieldValue("t/4", TextRange(3)))
        )
    }
    var activo by remember { mutableIntStateOf(0) }
    var xDesde by remember { mutableStateOf("-3") }
    var xHasta by remember { mutableStateOf("3") }
    var yDesde by remember { mutableStateOf("-3") }
    var yHasta by remember { mutableStateOf("3") }
    var zDesde by remember { mutableStateOf("-3") }
    var zHasta by remember { mutableStateOf("3") }
    var tDesde by remember { mutableStateOf("0") }
    var tHasta by remember { mutableStateOf("12,57") }
    var escala by remember { mutableStateOf("40") }
    var lineas by remember { mutableStateOf("16") }

    val textos = campos.map { it.value.text }
    val trazos = remember(esSuperficie, textos, xDesde, xHasta, yDesde, yHasta, zDesde, zHasta, tDesde, tHasta, escala, lineas) {
        val k = numeroDelCampo(escala) ?: return@remember null
        if (esSuperficie) {
            val a = numeroDelCampo(xDesde) ?: return@remember null
            val b = numeroDelCampo(xHasta) ?: return@remember null
            val c = numeroDelCampo(yDesde) ?: return@remember null
            val d = numeroDelCampo(yHasta) ?: return@remember null
            val e = numeroDelCampo(zDesde) ?: return@remember null
            val f = numeroDelCampo(zHasta) ?: return@remember null
            val n = lineas.trim().toIntOrNull() ?: return@remember null
            Graficas3D.superficie(
                Graficas3D.Superficie(textos[0], a, b, c, d, e, f, k, n.coerceIn(2, 60)),
                color, grosor
            )
        } else {
            val a = numeroDelCampo(tDesde) ?: return@remember null
            val b = numeroDelCampo(tHasta) ?: return@remember null
            Graficas3D.curva(Graficas3D.Curva(textos[1], textos[2], textos[3], a, b, k), color, grosor)
        }
    }

    @Composable
    fun campoDeFormula(indice: Int, etiqueta: String) {
        OutlinedTextField(
            value = campos[indice].value,
            onValueChange = { campos[indice].value = it },
            label = { Text(etiqueta) },
            singleLine = true,
            isError = Formula.compilar(campos[indice].value.text) == null,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) activo = indice }
        )
    }

    @Composable
    fun numero(valor: String, etiqueta: String, modifier: Modifier, onCambio: (String) -> Unit) {
        OutlinedTextField(
            value = valor, onValueChange = onCambio, label = { Text(etiqueta) },
            singleLine = true, modifier = modifier
        )
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(stringResource(R.string.grafica3d_titulo)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = esSuperficie,
                        onClick = { esSuperficie = true; activo = 0 },
                        label = { Text(stringResource(R.string.grafica3d_superficie)) }
                    )
                    FilterChip(
                        selected = !esSuperficie,
                        onClick = { esSuperficie = false; activo = 1 },
                        label = { Text(stringResource(R.string.grafica3d_curva)) }
                    )
                }
                if (esSuperficie) {
                    campoDeFormula(0, stringResource(R.string.grafica3d_formula_z))
                    VistaDeFormula("z", textos[0])
                } else {
                    campoDeFormula(1, stringResource(R.string.grafica3d_formula_x))
                    campoDeFormula(2, stringResource(R.string.grafica3d_formula_y))
                    campoDeFormula(3, stringResource(R.string.grafica3d_formula_zt))
                    VistaDeFormula("x", textos[1])
                    VistaDeFormula("y", textos[2])
                    VistaDeFormula("z", textos[3])
                }
                TecladoDeFormulas(
                    campos[activo].value,
                    { campos[activo].value = it },
                    conY = esSuperficie,
                    conT = !esSuperficie
                )
                if (esSuperficie) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        numero(xDesde, stringResource(R.string.grafica_x_desde), Modifier.weight(1f)) { xDesde = it }
                        numero(xHasta, stringResource(R.string.grafica_x_hasta), Modifier.weight(1f)) { xHasta = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        numero(yDesde, stringResource(R.string.grafica_y_desde), Modifier.weight(1f)) { yDesde = it }
                        numero(yHasta, stringResource(R.string.grafica_y_hasta), Modifier.weight(1f)) { yHasta = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        numero(zDesde, stringResource(R.string.grafica3d_z_desde), Modifier.weight(1f)) { zDesde = it }
                        numero(zHasta, stringResource(R.string.grafica3d_z_hasta), Modifier.weight(1f)) { zHasta = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        numero(escala, stringResource(R.string.grafica3d_escala), Modifier.weight(1f)) { escala = it }
                        numero(lineas, stringResource(R.string.grafica3d_lineas), Modifier.weight(1f)) { lineas = it }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        numero(tDesde, stringResource(R.string.grafica3d_t_desde), Modifier.weight(1f)) { tDesde = it }
                        numero(tHasta, stringResource(R.string.grafica3d_t_hasta), Modifier.weight(1f)) { tHasta = it }
                    }
                    numero(escala, stringResource(R.string.grafica3d_escala), Modifier.fillMaxWidth()) { escala = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { trazos?.let(onAceptar) }, enabled = trazos != null) {
                Text(stringResource(R.string.grafica_insertar))
            }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text(stringResource(R.string.grafica_cancelar)) }
        }
    )
}
