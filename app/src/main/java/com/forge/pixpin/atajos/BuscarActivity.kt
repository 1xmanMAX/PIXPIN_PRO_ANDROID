package com.forge.pixpin.atajos

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.MensajesActivity
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * **La tarjeta de buscar en PixPin** (4-oct-2026): baja desde arriba encima de lo que haya —el
 * buscador del teléfono, otra app, el escritorio— y no abre la aplicación entera. Vacía enseña
 * las funciones (grabar, capturas, lecciones…) para que nada quede escondido; escribiendo,
 * todo lo que casa ([BuscarEnTodo]). Tocar un resultado lo abre y la tarjeta se va.
 *
 * Tiene su propia tarea (ver el manifiesto), como el micrófono: así no arrastra detrás la
 * pantalla que hubiera abierta en PixPin.
 */
class BuscarActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val inicial = intent.getStringExtra(EXTRA_TEXTO).orEmpty()
        setContent { PixPinTheme(cielo = false) { Tarjeta(inicial) } }
    }

    @Composable
    private fun Tarjeta(inicial: String) {
        var consulta by remember { mutableStateOf(inicial) }
        var todo by remember { mutableStateOf(BuscarEnTodo.FUNCIONES) }
        var resultados by remember { mutableStateOf<List<BuscarEnTodo.Elemento>>(emptyList()) }
        val foco = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            runCatching { foco.requestFocus() }
            todo = withContext(Dispatchers.IO) {
                val mensajes = runCatching { MensajesStore(this@BuscarActivity).leer() }.getOrDefault(emptyList())
                val proyectos = (application as PixPinApp).proyectos.proyectos.value
                    .filter { !it.archivado }.sortedByDescending { it.tocado }.map { it.id to it.nombre }
                BuscarEnTodo.elementos(mensajes, proyectos)
            }
        }
        LaunchedEffect(consulta, todo) {
            if (consulta.isNotBlank()) delay(80)
            resultados = withContext(Dispatchers.Default) { BuscarEnTodo.buscar(todo, consulta) }
        }

        BackHandler { finish() }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, null) { finish() },
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                Modifier.statusBarsPadding().imePadding().padding(12.dp).widthIn(max = 640.dp).fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, null) {},
                shape = RoundedCornerShape(26.dp), tonalElevation = 6.dp, shadowElevation = 16.dp
            ) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = consulta, onValueChange = { consulta = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(foco),
                        placeholder = { Text("Buscar en PixPin") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            IconButton(onClick = { if (consulta.isEmpty()) finish() else consulta = "" }) { Icon(Icons.Filled.Close, "Borrar") }
                        },
                        singleLine = true, shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { resultados.firstOrNull()?.let { abrir(it) } })
                    )
                    Spacer(Modifier.height(8.dp))
                    if (consulta.isBlank()) Funciones()
                    else if (resultados.isEmpty()) Text(
                        "Nada con «${BuscarEnTodo.sinLaP(consulta).trim()}»", Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else Resultados(resultados)
                }
            }
        }
    }

    /** Sin escribir nada: todo lo que hace PixPin, a un toque. Así la galería y compañía se encuentran. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Funciones() {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BuscarEnTodo.FUNCIONES.forEach { f ->
                AssistChip(onClick = { abrir(f) }, label = { Text("${f.emoji}  ${f.titulo}") })
            }
        }
    }

    @Composable
    private fun Resultados(lista: List<BuscarEnTodo.Elemento>) {
        val estado = rememberLazyListState()
        LazyColumn(Modifier.heightIn(max = 460.dp), state = estado) {
            lista.groupBy { it.tipo }.toSortedMap(compareBy { it.ordinal }).entries.forEachIndexed { i, (tipo, cosas) ->
                item(key = "t-" + tipo.name) {
                    Text(tipo.nombre, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = if (i == 0) 2.dp else 10.dp, bottom = 2.dp))
                }
                items(cosas, key = { tipo.name + (it.mensaje?.id ?: it.id ?: it.accion ?: it.titulo) }) { e -> Fila(e) }
            }
        }
    }

    @Composable
    private fun Fila(e: BuscarEnTodo.Elemento) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { abrir(e) }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(e.emoji, fontSize = 20.sp, modifier = Modifier.width(34.dp))
            Column(Modifier.weight(1f)) {
                Text(e.titulo, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(e.detalle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun abrir(e: BuscarEnTodo.Elemento) {
        runCatching {
            val m = e.mensaje
            when {
                e.accion != null -> startActivity(
                    Intent(Intent.ACTION_VIEW, Atajos.sena(e.accion, e.id)).setClass(this, AtajoActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                e.tipo == BuscarEnTodo.Tipo.LECCION && e.id != null -> com.forge.pixpin.lecciones.LeccionActivity.abrir(this, e.id)
                m != null && e.tipo == BuscarEnTodo.Tipo.ARCHIVO -> MensajesActivity.abrirYAbrir(this, m.id)
                m != null -> MensajesActivity.irAlMensaje(this, m.proyecto, e.detalle, m.id)
            }
        }
        finish()
    }

    companion object {
        private const val EXTRA_TEXTO = "buscar_texto"

        fun abrir(context: Context, texto: String? = null) = context.startActivity(
            Intent(context, BuscarActivity::class.java).putExtra(EXTRA_TEXTO, texto)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }
}
