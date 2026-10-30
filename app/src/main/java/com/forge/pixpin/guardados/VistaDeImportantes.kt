package com.forge.pixpin.guardados

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **Lo importante de la clase a la vista** (28-oct-2026, Jev; la ventana del PC, `4c144080`): un punto de
 * color por tipo bajo el minuto de cada renglón (azul tarea, verde decisión, morado pregunta, rojo
 * examen) y, encima del texto, el filtro «Todo · Tarea 3 · Decisión 1 · …» y «+N tareas a Tareas». Lo
 * usa [EstudiarClaseActivity]; los datos y las cuentas, en [Importantes]. **No necesita clave**: lo
 * hallado puede venir del PC.
 */
@Composable
fun PuntosDeImportantes(tipos: List<Importantes.Tipo>, modifier: Modifier = Modifier) {
    if (tipos.isNotEmpty()) Row(modifier.padding(top = 2.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (t in tipos) Box(Modifier.size(7.dp).clip(CircleShape).background(Color(t.color)))
    }
}

/**
 * **El filtro y las tareas**: «Todo» y un botón por tipo hallado con cuántos renglones tiene; el elegido,
 * relleno. [sinApuntar]: las tareas aún no apuntadas («+N tareas a Tareas»). [buscando]: Jev está
 * mirando la clase ahora («Buscando lo importante…»).
 */
@Composable
fun FiltroDeImportantes(
    importantes: List<Importantes.Importante>,
    filtro: Importantes.Tipo?,
    buscando: Boolean,
    alElegir: (Importantes.Tipo?) -> Unit,
    alApuntar: () -> Unit,
    modifier: Modifier = Modifier,
    /** Lo hallado en texto, para «Reportar respuesta» (30-oct-2026, Google Play): la bandera al final. */
    alReportar: (() -> String)? = null
) {
    val cuentas = Importantes.cuentas(importantes)
    val sinApuntar = Importantes.tareasSinApuntar(importantes).size
    if (importantes.isEmpty() && !buscando) return
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (buscando) Text("Buscando lo importante…", fontSize = 12.sp, color = tenue)
        if (importantes.isNotEmpty()) {
            Pastilla("Todo", null, filtro == null) { alElegir(null) }
            for (t in Importantes.Tipo.entries) {
                val n = cuentas[t] ?: 0
                if (n > 0) Pastilla("${t.rotulo} $n", Color(t.color), filtro == t) { alElegir(if (filtro == t) null else t) }
            }
        }
        if (sinApuntar > 0) TextButton(onClick = alApuntar) { Text("+$sinApuntar tareas a Tareas", fontSize = 13.sp) }
        // Lo marcó una IA (Jev): se puede reportar sin salir de la clase.
        if (importantes.isNotEmpty() && alReportar != null) com.forge.pixpin.privacidad.BotonReportarIa(
            com.forge.pixpin.privacidad.ReportesDeIa.Funcion.LO_IMPORTANTE, alReportar, tinte = tenue
        )
    }
}

/** Un botón del filtro: con el punto de su color; el elegido, relleno del color de la app. */
@Composable
private fun Pastilla(texto: String, color: Color?, elegida: Boolean, al: () -> Unit) {
    val acento = MaterialTheme.colorScheme.primary
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .then(if (elegida) Modifier.background(acento) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)))
            .clickable(onClick = al)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (color != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (elegida) MaterialTheme.colorScheme.onPrimary else color))
            Spacer(Modifier.width(5.dp))
        }
        Text(texto, fontSize = 13.sp, color = if (elegida) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}
