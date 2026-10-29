package com.forge.pixpin.guardados

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.mini.TodasLasTareas
import java.io.File

/**
 * **«Temas de la reunión» en «Preparar»** (29-oct-2026; `grabar_clase/temas.rs` y `jev/temas.rs` del PC;
 * `docs/jev.md` §5 paso 1 y «Android», paso 1): se escribe un tema e Intro, se pegan varios («Pegar», uno
 * por línea, con las reglas de limpieza de [TemasDeLaReunion.deTexto]) o **«De una lista»** toma lo
 * pendiente de una lista de tareas (cada toque pasa a la siguiente; primero las del chat donde se
 * guarda) y deja la reunión enlazada a esa lista. Los temas que se pasaron «a la próxima reunión» salen
 * ya puestos. Al lado, **«Duración prevista»** (15, 30, 45, 60, 90, 120 o 180 min: el tema olvidado).
 *
 * Lo elegido se entrega a [ReunionConJev.preparada] al empezar ([entregar]).
 */
object PrepararTemas {

    /** Las duraciones previstas que se ofrecen, en minutos. */
    val DURACIONES = listOf(15, 30, 45, 60, 90, 120, 180)

    /** Lo elegido en esta pantalla (vive lo que vive «Preparar»). */
    val temas = mutableStateListOf<TemasDeLaReunion.Tema>()
    var lista by mutableStateOf<Pair<String?, String>?>(null)
    var duracion by mutableStateOf<Int?>(null)
    private var siguienteLista = 0

    /** Al abrir «Preparar»: vacío, salvo lo que esperaba a la próxima reunión. Devuelve cuántos de esos. */
    fun empezar(context: Context): Int {
        temas.clear()
        lista = null
        duracion = null
        siguienteLista = 0
        val v = mutableListOf<TemasDeLaReunion.Tema>()
        val n = TemasDeLaReunion.anadirVarios(v, runCatching { paraLaProxima(context) }.getOrDefault(emptyList()))
        temas.addAll(v)
        return n
    }

    /** Al empezar a grabar: lo elegido, a la grabación. */
    fun entregar() {
        ReunionConJev.preparada = ReunionConJev.Preparada(temas.toList(), lista, duracion)
    }

    private fun anadir(textos: List<String>): Int {
        val v = temas.toMutableList()
        val n = TemasDeLaReunion.anadirVarios(v, textos)
        temas.clear()
        temas.addAll(v)
        return n
    }

    // --- Las listas de tareas con algo pendiente ------------------------------------------------

    /** Una lista de tareas de la que sacar los temas: lo pendiente, ya limpio. */
    data class ListaDeTemas(val proyecto: String?, val codigo: String, val titulo: String, val temas: List<String>)

    /** **Las listas de tareas con algo pendiente**, primero las del chat [proyecto] y luego las demás. Trabajo de disco. */
    fun listas(context: Context, proyecto: String?): List<ListaDeTemas> {
        val app = context.applicationContext
        val nombres = (app as? PixPinApp)?.proyectos?.proyectos?.value?.associate { it.id to it.nombre }.orEmpty()
        val todas = TodasLasTareas.reunir(MensajesStore(app).leer().filter { !it.enBuzon }, nombres)
        return todas.map { l ->
            ListaDeTemas(l.proyecto, l.codigo, l.titulo, l.filas.filter { !it.hecha }.map { TemasDeLaReunion.limpio(it.texto) }.filter { it.isNotEmpty() })
        }.filter { it.temas.isNotEmpty() }.sortedBy { it.proyecto != proyecto }
    }

    // --- Lo que pasa a la próxima reunión -------------------------------------------------------

    /** Donde esperan, en este teléfono, uno por línea (`<datos>/voz/temas-para-la-proxima.txt` del PC). */
    private fun fichero(context: Context) = File(File(context.applicationContext.filesDir, "voz"), "temas-para-la-proxima.txt")

    fun paraLaProxima(context: Context): List<String> =
        runCatching { TemasDeLaReunion.deTexto(fichero(context).readText()) }.getOrDefault(emptyList())

    /** **Pasa estos temas a la próxima reunión**: se suman a los que ya esperaban, sin repetir. Devuelve cuántos esperan. */
    fun guardarParaLaProxima(context: Context, nuevos: List<String>): Int {
        val v = TemasDeLaReunion.deTexto((paraLaProxima(context) + nuevos).joinToString("\n"))
        val f = fichero(context)
        f.parentFile?.mkdirs()
        f.writeText(v.joinToString("\n"))
        return v.size
    }

    /** Ya se usaron (empezó la reunión con ellos). */
    fun olvidarLaProxima(context: Context) {
        fichero(context).delete()
    }

    // --- Lo que se pinta ------------------------------------------------------------------------

    /**
     * **La fila de «Preparar»**: «Temas de la reunión · N», que abre su pantalla, y la ficha de la duración
     * prevista (cada toque, la siguiente; tras la última, ninguna). Sin `return` antes de tiempo (R8).
     */
    @Composable
    fun Fila(proyecto: String?) {
        var abierta by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { abierta = true }, modifier = Modifier.weight(1f)) {
                Text(if (temas.isEmpty()) TextosDeLaReunion.NINGUNO else TextosDeLaReunion.conN(temas.size), maxLines = 1)
            }
            FilterChip(
                selected = duracion != null,
                onClick = { duracion = duracion.let { d -> if (d == null) DURACIONES.first() else DURACIONES.firstOrNull { it > d } } },
                label = { Text(duracion?.let { TextosDeLaReunion.duracionN(it) } ?: TextosDeLaReunion.DURACION, maxLines = 1) }
            )
        }
        if (abierta) Dialogo(proyecto) { abierta = false }
    }

    /** **La pantalla de los temas**: escribir, pegar, de una lista, quitar y vaciar. */
    @Composable
    private fun Dialogo(proyecto: String?, alCerrar: () -> Unit) {
        val contexto = LocalContext.current
        var escrito by remember { mutableStateOf("") }
        var avisoDeLista by remember { mutableStateOf<String?>(null) }
        var vuelta by remember { mutableIntStateOf(0) }
        fun toast(t: String) = Toast.makeText(contexto, t, Toast.LENGTH_SHORT).show()
        AlertDialog(
            onDismissRequest = alCerrar,
            title = { Text(TextosDeLaReunion.TITULO) },
            text = {
                Column {
                    Text(TextosDeLaReunion.SUB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = escrito, onValueChange = { escrito = it },
                        placeholder = { Text(TextosDeLaReunion.ESCRIBE) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            // Lo escrito con saltos (pegado en la caja) son varios temas.
                            anadir(TemasDeLaReunion.deTexto(escrito))
                            escrito = ""
                        }),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            val pegado = runCatching {
                                contexto.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(contexto)?.toString()
                            }.getOrNull().orEmpty()
                            if (pegado.isBlank()) toast(TextosDeLaReunion.NADA_QUE_PEGAR)
                            else toast(TextosDeLaReunion.pegados(anadir(TemasDeLaReunion.deTexto(pegado))))
                        }) { Text(TextosDeLaReunion.PEGAR) }
                        TextButton(onClick = {
                            Thread {
                                val ls = runCatching { listas(contexto, proyecto) }.getOrDefault(emptyList())
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    if (ls.isEmpty()) { toast(TextosDeLaReunion.SIN_LISTAS); return@post }
                                    val l = ls[siguienteLista % ls.size]
                                    siguienteLista++
                                    val v = mutableListOf<TemasDeLaReunion.Tema>()
                                    TemasDeLaReunion.anadirVarios(v, l.temas)
                                    temas.clear()
                                    temas.addAll(v)
                                    lista = l.proyecto to l.codigo
                                    avisoDeLista = TextosDeLaReunion.lista(l.titulo, v.size)
                                    vuelta++
                                }
                            }.start()
                        }) { Text(TextosDeLaReunion.DE_UNA_LISTA) }
                        TextButton(onClick = { temas.clear(); lista = null; avisoDeLista = null }) { Text(TextosDeLaReunion.VACIAR) }
                    }
                    avisoDeLista?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        itemsIndexed(temas.toList(), key = { _, t -> t.id }) { i, t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}. ${t.texto}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = {
                                    val v = temas.toMutableList()
                                    TemasDeLaReunion.quitar(v, t.id)
                                    temas.clear()
                                    temas.addAll(v)
                                }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Close, contentDescription = null) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (escrito.isNotBlank()) { anadir(TemasDeLaReunion.deTexto(escrito)); escrito = "" }
                    alCerrar()
                }) { Text(TextosDeLaReunion.LISTO) }
            }
        )
    }
}
