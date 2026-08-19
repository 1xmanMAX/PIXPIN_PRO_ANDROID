package com.forge.pixpin.motor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
    modifier: Modifier = Modifier
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
        }
    }
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
