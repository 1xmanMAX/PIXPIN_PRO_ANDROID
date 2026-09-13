package com.forge.pixpin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **La hoja de compartir, la misma en toda la aplicación.**
 *
 * Lo pidió el usuario el 13-sep-2026: compartir desde el lienzo, desde proyectos, desde el chat o
 * desde cualquier otro sitio tiene que ser el mismo menú. Sube desde abajo, como la del clip del
 * chat, porque ahí está el dedo. Tiene tres partes:
 *
 * - **Los formatos**, en botones redondos que se reconocen sin leer.
 * - **Qué páginas**, cuando hay para elegir: «Lienzo completo», «Página 1», «Página 2»… En los
 *   formatos de una sola página (imagen, SVG) se elige una; en los demás, las que se quiera. Con
 *   más de una marcada, los de una sola página desaparecen.
 * - **El peso**: el archivo se prepara en segundo plano con lo elegido y se enseña cuánto ocupa de
 *   verdad. Al compartir se usa ese mismo archivo, así que no se hace dos veces.
 *
 * Qué formatos hay y cómo se genera cada uno lo decide quien la abre (ver [Compartible] y
 * [CompartirPaginas]); la hoja no sabe de lienzos ni de proyectos.
 */
class Compartible(
    val titulo: String,
    val paginas: List<Pagina> = emptyList(),
    val formatos: List<Formato>,
    /** Las marcadas al abrir; por omisión, todas. */
    val marcadas: Set<String>? = null
) {
    data class Pagina(
        val clave: String,
        val nombre: String,
        /** Debajo del nombre: de qué proyecto, qué tipo. */
        val detalle: String = "",
        /** Con cuántos niveles de sangría se pinta: un marco va debajo de su lienzo. */
        val nivel: Int = 0
    )

    class Formato(
        val id: String,
        val icono: ImageVector,
        val nombre: String,
        /** Cuántas páginas admite: [NINGUNA] (va entero), [UNA] o [VARIAS]. */
        val paginas: Int = VARIAS,
        /** Qué páginas admite, si no son todas (una nota no tiene imagen). */
        val admite: (String) -> Boolean = { true },
        /** Hace el archivo con las páginas elegidas, fuera del hilo de la pantalla. Null si no hay nada. */
        val generar: (suspend (List<String>) -> Salida?)? = null,
        /** Lo que no produce un archivo que compartir, como enviar por Wi-Fi: se hace y se cierra la hoja. */
        val accion: ((List<String>) -> Unit)? = null,
        /** Un panel de opciones (la página web), con su botón al lado del peso. */
        val ajustes: (@Composable (cerrar: () -> Unit) -> Unit)? = null,
        /** Cambia cuando cambian los ajustes, para volver a preparar el archivo. */
        val versionDeAjustes: @Composable () -> Any? = { null }
    )

    class Salida(val archivo: File, val mime: String, val resumen: String? = null)

    companion object {
        const val NINGUNA = 0
        const val UNA = 1
        const val VARIAS = 2
    }
}

/** El icono de compartir de toda la aplicación: el cuadro con la flecha hacia arriba. */
val IconoDeCompartir: ImageVector get() = Icons.Filled.IosShare

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaDeCompartir(c: Compartible, alCerrar: () -> Unit) {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    val estado = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var formatoId by remember(c) { mutableStateOf(c.formatos.firstOrNull { it.generar != null }?.id ?: c.formatos.firstOrNull()?.id) }
    var marcadas by remember(c) { mutableStateOf(c.marcadas ?: c.paginas.map { it.clave }.toSet()) }
    var viendoAjustes by remember { mutableStateOf(false) }

    val formato = c.formatos.firstOrNull { it.id == formatoId }
    val hayPaginas = c.paginas.size > 1
    // **Con más de una página marcada, los de una sola desaparecen.**
    val visibles = c.formatos.filter { f -> !(f.paginas == Compartible.UNA && marcadas.size > 1 && formatoId != f.id) }
    // Lo elegido, en el orden de la lista, y solo lo que el formato admite.
    val elegidas = c.paginas.map { it.clave }.filter { it in marcadas && (formato?.admite?.invoke(it) != false) }

    var salida by remember { mutableStateOf<Compartible.Salida?>(null) }
    var preparando by remember { mutableStateOf(false) }
    var fallo by remember { mutableStateOf<String?>(null) }
    var vuelta by remember { mutableIntStateOf(0) }
    val version = formato?.versionDeAjustes?.invoke()

    // **El archivo, preparado en cuanto se elige**: así se ve su peso y compartir es instantáneo.
    LaunchedEffect(formatoId, elegidas, version, vuelta) {
        salida = null
        fallo = null
        val f = formato ?: return@LaunchedEffect
        val generar = f.generar ?: return@LaunchedEffect
        if (c.paginas.isNotEmpty() && f.paginas != Compartible.NINGUNA && elegidas.isEmpty()) return@LaunchedEffect
        preparando = true
        delay(300)
        val hecho = withContext(Dispatchers.IO) { runCatching { generar(if (f.paginas == Compartible.NINGUNA) emptyList() else elegidas) } }
        preparando = false
        hecho.exceptionOrNull()?.let {
            android.util.Log.e("PixPinCompartir", "generar ${f.id}", it)
            fallo = "No se pudo preparar (${it.javaClass.simpleName})"
        }
        salida = hecho.getOrNull()
        if (hecho.isSuccess && salida == null) fallo = "No hay nada que compartir con esto"
    }

    ModalBottomSheet(onDismissRequest = alCerrar, sheetState = estado) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(IconoDeCompartir, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(c.titulo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))

            // Los formatos.
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visibles, key = { it.id }) { f ->
                    val elegido = f.id == formatoId
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(78.dp).clickable {
                            if (f.accion != null && f.generar == null) {
                                f.accion.invoke(elegidas)
                                alCerrar()
                                return@clickable
                            }
                            formatoId = f.id
                            if (f.paginas == Compartible.UNA && marcadas.size > 1) {
                                marcadas = setOfNotNull(c.paginas.firstOrNull { it.clave in marcadas && f.admite(it.clave) }?.clave)
                            }
                        }.padding(vertical = 4.dp)
                    ) {
                        Box(
                            Modifier.size(56.dp)
                                .background(if (elegido) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                .then(if (elegido) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(f.icono, contentDescription = null, tint = if (elegido) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(f.nombre, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp),
                            fontWeight = if (elegido) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }

            // Las páginas.
            if (hayPaginas && formato != null && formato.paginas != Compartible.NINGUNA) {
                HorizontalDivider(Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (formato.paginas == Compartible.UNA) "Elige una página" else "Qué páginas",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)
                    )
                    if (formato.paginas == Compartible.VARIAS) {
                        TextButton(onClick = { marcadas = c.paginas.map { it.clave }.toSet() }) { Text("Todas") }
                        TextButton(onClick = { marcadas = emptySet() }) { Text("Ninguna") }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(c.paginas, key = { it.clave }) { p ->
                        val vale = formato.admite(p.clave)
                        val puesta = p.clave in marcadas
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = vale) {
                                    marcadas = if (formato.paginas == Compartible.UNA) setOf(p.clave)
                                    else if (puesta) marcadas - p.clave else marcadas + p.clave
                                }
                                .padding(start = (12 + 20 * p.nivel).dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (formato.paginas == Compartible.UNA) {
                                RadioButton(selected = puesta && vale, enabled = vale, onClick = { marcadas = setOf(p.clave) })
                            } else {
                                Checkbox(checked = puesta && vale, enabled = vale, onCheckedChange = { marcadas = if (it) marcadas + p.clave else marcadas - p.clave })
                            }
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(p.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (vale) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                val detalle = if (vale) p.detalle else "No se puede como ${formato.nombre.lowercase()}"
                                if (detalle.isNotBlank()) Text(detalle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // El peso y el botón.
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    when {
                        preparando -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Calculando el peso…", style = MaterialTheme.typography.bodyMedium)
                        }
                        salida != null -> {
                            Text("Pesa ${com.forge.pixpin.motor.Detalle.legible(salida!!.archivo.length())}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            salida!!.resumen?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                        }
                        fallo != null -> Text(fallo!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        elegidas.isEmpty() && hayPaginas -> Text("Marca alguna página", style = MaterialTheme.typography.bodyMedium)
                        else -> {}
                    }
                }
                if (formato?.ajustes != null) {
                    TextButton(onClick = { viendoAjustes = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Opciones")
                    }
                }
                // **Como enlace**, aparte: sube el archivo y comparte la dirección. Ver [CompartirEnlaceActivity].
                TextButton(
                    enabled = salida != null,
                    onClick = {
                        val s = salida ?: return@TextButton
                        // Antes de cerrar: al cerrarse la hoja se cancela su alcance.
                        CompartirEnlaceActivity.abrir(contexto, s.archivo, s.archivo.name.substringBeforeLast('.'), s.mime)
                        alCerrar()
                    }
                ) { Text("Enlace") }
                // **Compartir abre el panel del propio teléfono**, el de Huawei o el de Samsung. Ver [CompartirNativo].
                Button(
                    enabled = salida != null,
                    onClick = {
                        val s = salida ?: return@Button
                        CompartirNativo.archivo(contexto, s.archivo, s.mime, s.archivo.name)
                        alCerrar()
                    }
                ) {
                    Icon(IconoDeCompartir, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Compartir")
                }
            }
        }
    }
    if (viendoAjustes) formato?.ajustes?.invoke { viendoAjustes = false; vuelta++ }
}
