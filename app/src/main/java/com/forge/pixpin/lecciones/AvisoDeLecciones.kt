package com.forge.pixpin.lecciones

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.ui.theme.Cristal
import com.forge.pixpin.ui.theme.cristal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **El aviso en el momento justo**: al entrar en el chat de un proyecto, una franja con las
 * lecciones que le tocan —las suyas y las que hablan de lo mismo que su nombre—, la más grave
 * primero. Es lo que el estudio señala como lo que más ayuda: que la lección salga sola cuando
 * se va a necesitar, no que haya que acordarse de buscarla. Tocarla abre las lecciones de ese
 * proyecto. Sin lecciones, no ocupa sitio.
 */
@Composable
fun AvisoDeLecciones(proyecto: String, nombre: String, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val todas by LeccionesStore.todas.collectAsState()
    LaunchedEffect(Unit) { if (todas.isEmpty()) withContext(Dispatchers.IO) { runCatching { LeccionesStore(contexto).recargar() } } }
    var tocan by remember { mutableStateOf<List<Leccion>>(emptyList()) }
    LaunchedEffect(todas, proyecto, nombre) {
        tocan = withContext(Dispatchers.Default) {
            val suyas = todas.filter { it.proyecto == proyecto }.map { it.leccion }
            val cerca = Buscador.paraElContexto(todas.filter { it.proyecto != proyecto }.map { it.indice }, nombre, 5)
            (suyas + cerca).distinctBy { it.id }
                .sortedWith(compareByDescending<Leccion> { it.gravedad }.thenByDescending { it.repeticiones.size })
        }
    }
    val primera = tocan.firstOrNull() ?: return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).cristal(RoundedCornerShape(16.dp))
            .clickable { LeccionesActivity.abrir(contexto, proyecto = proyecto) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "💡 ${tocan.size} " + (if (tocan.size == 1) "lección" else "lecciones") + ": ",
            color = Cristal.puesto, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge
        )
        Text(
            primera.proxima.ifBlank { primera.titulo }, color = Cristal.tinta, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium
        )
    }
}
