package com.forge.pixpin.croquis3d

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motor.Malla3D

/**
 * **Lo que se hace con un modelo traído de Revit**: cortarlo con una caja y mandar sobre sus
 * elementos. Los dos los pidió el usuario el 21-sep-2026 al ver su edificio en el croquis.
 *
 * - **La caja de sección**, la de Revit: el modelo va dentro de una caja invisible y cada lado
 *   se sube o se baja con su mando; lo que queda fuera deja de verse, así que bajar la tapa **es
 *   cortar el edificio por ahí** y mirar dentro.
 * - **La lista de elementos**, por tipo: muros, vigas, columnas, losas, cimientos… con cuántos
 *   hay de cada uno, para **pintarlos de un color** y para apagarlos.
 *
 * Los dos trabajan sobre el modelo elegido; sin ninguno elegido, sobre el único que haya.
 */
@Composable
fun PanelDelModelo(controlador: Croquis3DControlador, modifier: Modifier = Modifier) {
    val modelos = controlador.croquis.modelos
    if (modelos.isEmpty()) return
    val modelo = modelos.firstOrNull { it.id in controlador.seleccion } ?: modelos.first()
    val malla = remember(modelo.ruta, AlmacenDeMallas.version.intValue) { AlmacenDeMallas.cargadas[modelo.ruta]?.malla }

    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modelo.nombre, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "%,d ▲".format(modelo.triangulos),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            CajaDeSeccion(controlador, modelo)
            if (malla != null) ElementosPorTipo(controlador, modelo, malla)
        }
    }
}

/** Los seis lados de la caja, cada uno con su mando. Ver [Modelo3D.conLaCaja]. */
@Composable
private fun CajaDeSeccion(controlador: Croquis3DControlador, modelo: Modelo3D) {
    val s = modelo.seccion ?: Modelo3D.SECCION_ENTERA
    val puesta = modelo.seccion != null
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Caja de sección", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (puesta) TextButton(onClick = { controlador.quitarLaSeccion(modelo.id) }) { Text("Quitar") }
    }
    // **Cada lado, un mando, y el nombre de lo que corta.** Los mínimos suben desde abajo y los
    // máximos bajan desde arriba, que es como se entiende una caja: se aprietan los lados.
    val lados = listOf(
        Triple(2, "Suelo", false), Triple(5, "Techo", true),
        Triple(0, "Izquierda", false), Triple(3, "Derecha", true),
        Triple(1, "Frente", false), Triple(4, "Fondo", true)
    )
    for ((lado, nombre, esMaximo) in lados) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                nombre, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(66.dp)
            )
            // El mando va **de fuera hacia dentro** en los dos casos: a la derecha, más corte.
            val valor = if (esMaximo) (1.0 - s[lado]).toFloat() else s[lado].toFloat()
            Slider(
                value = valor.coerceIn(0f, 1f),
                onValueChange = { v ->
                    controlador.moverLaSeccion(modelo.id, lado, if (esMaximo) 1.0 - v.toDouble() else v.toDouble())
                },
                modifier = Modifier.weight(1f)
            )
            Text(
                "%d%%".format(Math.round(valor * 100)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(38.dp)
            )
        }
    }
}

/** Los elementos del modelo agrupados por su tipo IFC: cuántos hay, de qué color y si se ven. */
@Composable
private fun ElementosPorTipo(controlador: Croquis3DControlador, modelo: Modelo3D, malla: Malla3D) {
    val porTipo = remember(malla) {
        malla.piezas.groupBy { it.tipo }.map { (tipo, suyas) -> tipo to suyas.size }.sortedByDescending { it.second }
    }
    if (porTipo.isEmpty()) return
    var pintando by remember { mutableStateOf<String?>(null) }
    Text(
        "Elementos", style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
        for ((tipo, cuantos) in porTipo) {
            val apagado = tipo in modelo.tiposOcultos
            val suyo = modelo.coloresPorTipo[tipo]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = !apagado,
                    onCheckedChange = { controlador.ocultarElTipo(modelo.id, tipo, !it) }
                )
                Column(Modifier.weight(1f)) {
                    Text(nombreDelTipo(tipo), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        "$cuantos ${if (cuantos == 1) "elemento" else "elementos"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // La bolita del color: tocarla abre la paleta, y con un color puesto se ve cuál.
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(suyo?.let { Color(android.graphics.Color.parseColor(it)) } ?: Color(0xFF9E9E9E))
                        .border(
                            if (pintando == tipo) 2.dp else 1.dp,
                            if (pintando == tipo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                        .clickable { pintando = if (pintando == tipo) null else tipo }
                )
            }
            if (pintando == tipo) Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (c in COLORES_DE_ELEMENTO) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(c)))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { controlador.pintarElTipo(modelo.id, tipo, c); pintando = null }
                    )
                }
                TextButton(onClick = { controlador.pintarElTipo(modelo.id, tipo, null); pintando = null }) {
                    Text("El suyo", fontSize = 12.sp)
                }
            }
        }
    }
}

/** Cómo se llama en cristiano un tipo de elemento de Revit. Lo que no esté, por su nombre IFC. */
private fun nombreDelTipo(tipo: String): String = when (tipo.uppercase()) {
    "IFCWALL", "IFCWALLSTANDARDCASE" -> "Muros"
    "IFCBEAM" -> "Vigas"
    "IFCCOLUMN" -> "Columnas"
    "IFCSLAB" -> "Losas"
    "IFCFOOTING" -> "Cimientos"
    "IFCDOOR" -> "Puertas"
    "IFCWINDOW" -> "Ventanas"
    "IFCSTAIR", "IFCSTAIRFLIGHT" -> "Escaleras"
    "IFCRAILING" -> "Barandillas"
    "IFCROOF" -> "Cubiertas"
    "IFCCOVERING" -> "Acabados"
    "IFCPLATE" -> "Placas"
    "IFCMEMBER" -> "Barras"
    "IFCFURNISHINGELEMENT" -> "Mobiliario"
    "IFCBUILDINGELEMENTPROXY" -> "Otros elementos"
    else -> tipo.removePrefix("IFC").lowercase().replaceFirstChar { it.uppercase() }
}

/** Colores para separar familias de un vistazo: fuertes y distintos entre sí. */
private val COLORES_DE_ELEMENTO = listOf(
    "#E53935", "#FB8C00", "#FDD835", "#43A047", "#00ACC1", "#1E88E5", "#8E24AA", "#6D4C41", "#FFFFFF"
)
