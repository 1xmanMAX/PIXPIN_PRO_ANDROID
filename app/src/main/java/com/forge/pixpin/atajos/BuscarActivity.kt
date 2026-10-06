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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
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

    /** Lo reciente de este aparato (ver [RecientesDelBuscador]). Se lee y se escribe fuera del hilo principal. */
    private var recientes = RecientesDelBuscador()

    private val archivoDeRecientes get() = java.io.File(filesDir, RecientesDelBuscador.NOMBRE)

    private fun guardarRecientes(r: RecientesDelBuscador) {
        recientes = r
        val texto = r.aJson()
        // Pequeño, pero es disco: fuera del hilo principal. Se escribe al lado y se cambia de nombre
        // para que un corte a mitad no deje el archivo roto.
        Thread {
            runCatching {
                val tmp = java.io.File(filesDir, RecientesDelBuscador.NOMBRE + ".tmp")
                tmp.writeText(texto)
                if (!tmp.renameTo(archivoDeRecientes)) tmp.delete()
            }
        }.start()
    }

    /**
     * La tarjeta (v2, 5-oct-2026, como «Buscar en PixPin» del PC): la caja, las **pestañas** por
     * tipo con cuántos hay en cada una, y debajo:
     * - caja vacía en «Todo»: las **búsquedas recientes** (con su ✕ y «Borrar todas»), lo
     *   **abierto hace poco** y las funciones;
     * - caja vacía en otra pestaña: todo lo de esa pestaña, lo más reciente primero;
     * - escribiendo: en «Todo» el mejor resultado arriba y el resto por grupos; en otra pestaña,
     *   solo lo suyo.
     * Mantener pulsado un resultado enseña su **vista previa** con sus botones.
     */
    @Composable
    private fun Tarjeta(inicial: String) {
        var consulta by remember { mutableStateOf(inicial) }
        var pestana by remember { mutableStateOf(BuscarEnTodo.Pestana.TODO) }
        var todo by remember { mutableStateOf(BuscarEnTodo.FUNCIONES) }
        var resultados by remember { mutableStateOf<List<BuscarEnTodo.Elemento>>(emptyList()) }
        var rec by remember { mutableStateOf(recientes) }
        var vista by remember { mutableStateOf<BuscarEnTodo.Elemento?>(null) }
        val foco = remember { FocusRequester() }

        LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }
        LaunchedEffect(Unit) {
            rec = withContext(Dispatchers.IO) {
                RecientesDelBuscador.leer(runCatching { archivoDeRecientes.readText() }.getOrNull())
            }
            recientes = rec
        }
        // Con la app arrancada en frío desde el atajo, los proyectos llegan un poco después:
        // se vuelve a montar la lista cuando llegan.
        val listaDeProyectos by (application as PixPinApp).proyectos.proyectos.collectAsState()
        LaunchedEffect(listaDeProyectos) {
            todo = withContext(Dispatchers.IO) {
                val mensajes = runCatching { MensajesStore(this@BuscarActivity).leer() }.getOrDefault(emptyList())
                val proyectos = listaDeProyectos.filter { !it.archivado }.sortedByDescending { it.tocado }.map { it.id to it.nombre }
                BuscarEnTodo.elementos(mensajes, proyectos)
            }
        }
        LaunchedEffect(consulta, todo) {
            if (consulta.isNotBlank()) delay(80)
            resultados = withContext(Dispatchers.Default) { BuscarEnTodo.buscar(todo, consulta) }
        }
        val vacia = consulta.isBlank()
        // Con la caja vacía, cada pestaña enseña lo suyo; escribiendo, se filtra lo hallado.
        val cuentas = remember(resultados, todo, vacia) {
            if (vacia) BuscarEnTodo.Pestana.entries.associateWith { p ->
                if (p == BuscarEnTodo.Pestana.TODO) -1 else todo.count { BuscarEnTodo.pestanaDe(it.tipo) == p }
            } else BuscarEnTodo.cuentas(resultados)
        }
        val abiertos = remember(rec, todo) { rec.abiertosEn(todo) }

        BackHandler { if (vista != null) vista = null else finish() }
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
                        value = consulta, onValueChange = { consulta = it.take(300); vista = null },
                        modifier = Modifier.fillMaxWidth().focusRequester(foco),
                        placeholder = { Text("Buscar en PixPin") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            IconButton(onClick = { if (consulta.isEmpty()) finish() else consulta = "" }) { Icon(Icons.Filled.Close, "Borrar") }
                        },
                        singleLine = true, shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            BuscarEnTodo.lineas(resultados, pestana).firstOrNull { it is BuscarEnTodo.Elemento }
                                ?.let { abrir(it as BuscarEnTodo.Elemento, consulta) }
                        })
                    )
                    Pestanas(pestana, cuentas) { pestana = it; vista = null }
                    val v = vista
                    when {
                        v != null -> VistaPrevia(v, consulta) { vista = null }
                        vacia && pestana == BuscarEnTodo.Pestana.TODO -> Inicio(
                            rec, abiertos,
                            alBuscar = { consulta = it },
                            alQuitar = { i -> rec = rec.sinBusqueda(i); guardarRecientes(rec) },
                            alBorrarTodas = { rec = rec.sinBusquedas(); guardarRecientes(rec) },
                            alVer = { vista = it }
                        )
                        vacia -> {
                            val de = remember(todo, pestana) { BuscarEnTodo.deLaPestana(todo, pestana) }
                            if (de.isEmpty()) Text("Aquí no hay nada todavía", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Lista(de, consulta) { vista = it }
                        }
                        else -> {
                            val lineas = remember(resultados, pestana) { BuscarEnTodo.lineas(resultados, pestana) }
                            if (lineas.isEmpty()) Text(
                                if (resultados.isEmpty()) "Nada con «${BuscarEnTodo.sinLaP(consulta).trim()}»"
                                else "Nada aquí: mira en otra pestaña",
                                Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            else Lista(lineas, consulta) { vista = it }
                        }
                    }
                }
            }
        }
    }

    /** Las pestañas, con cuántos hay en cada una (sin número la de «Todo» con la caja vacía). */
    @Composable
    private fun Pestanas(elegida: BuscarEnTodo.Pestana, cuentas: Map<BuscarEnTodo.Pestana, Int>, alElegir: (BuscarEnTodo.Pestana) -> Unit) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (p in BuscarEnTodo.Pestana.entries) {
                val n = cuentas[p] ?: 0
                FilterChip(
                    selected = p == elegida,
                    onClick = { alElegir(p) },
                    label = { Text(if (n >= 0) "${p.nombre} $n" else p.nombre, maxLines = 1) }
                )
            }
        }
    }

    /** Sin escribir nada, en «Todo»: lo reciente y todo lo que hace PixPin, a un toque. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Inicio(
        rec: RecientesDelBuscador,
        abiertos: List<BuscarEnTodo.Elemento>,
        alBuscar: (String) -> Unit,
        alQuitar: (Int) -> Unit,
        alBorrarTodas: () -> Unit,
        alVer: (BuscarEnTodo.Elemento) -> Unit
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            if (rec.busquedas.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cabecera("Búsquedas recientes", Modifier.weight(1f))
                    TextButton(onClick = alBorrarTodas) { Text("Borrar todas", maxLines = 1) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rec.busquedas.forEachIndexed { i, b ->
                        InputChip(
                            selected = false, onClick = { alBuscar(b) }, label = { Text(b, maxLines = 1) },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, "Quitar", Modifier.size(16.dp).clickable { alQuitar(i) })
                            }
                        )
                    }
                }
            }
            if (abiertos.isNotEmpty()) {
                Cabecera("Abierto hace poco", Modifier.padding(top = 10.dp))
                abiertos.forEach { Fila(it, "", alVer) }
            }
            Cabecera("Funciones", Modifier.padding(top = 10.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BuscarEnTodo.FUNCIONES.forEach { f ->
                    AssistChip(onClick = { abrir(f, "") }, label = { Text("${f.emoji}  ${f.titulo}") })
                }
            }
        }
    }

    @Composable
    private fun Cabecera(texto: String, modifier: Modifier = Modifier) {
        Text(
            texto, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = modifier.padding(start = 8.dp)
        )
    }

    /** Una lista de líneas: cabeceras de grupo ([BuscarEnTodo.Grupo]) y resultados. */
    @Composable
    private fun Lista(lineas: List<Any>, consulta: String, alVer: (BuscarEnTodo.Elemento) -> Unit) {
        val estado = rememberLazyListState()
        LazyColumn(Modifier.heightIn(max = 460.dp), state = estado) {
            lineas.forEachIndexed { i, l ->
                when (l) {
                    is BuscarEnTodo.Grupo -> item(key = "g-" + l.name) {
                        Cabecera(l.nombre, Modifier.padding(top = if (i == 0) 2.dp else 10.dp, bottom = 2.dp))
                    }
                    is BuscarEnTodo.Elemento -> item(key = "e-" + i + "-" + RecientesDelBuscador.claveDe(l)) { Fila(l, consulta, alVer) }
                }
            }
        }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun Fila(e: BuscarEnTodo.Elemento, consulta: String, alVer: (BuscarEnTodo.Elemento) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .combinedClickable(onClick = { abrir(e, consulta) }, onLongClick = { alVer(e) })
                .padding(horizontal = 8.dp, vertical = 8.dp),
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

    /**
     * **La vista previa** (como la del PC): lo que es, de dónde, un trozo de su texto y, si es una
     * foto, la foto; con los botones de abrir, ver en el chat y copiar. La foto se lee pequeña y
     * fuera del hilo principal.
     */
    @Composable
    private fun VistaPrevia(e: BuscarEnTodo.Elemento, consulta: String, alCerrar: () -> Unit) {
        val m = e.mensaje
        val foto by produceState<android.graphics.Bitmap?>(null, m?.id) {
            val ruta = m?.ruta
            value = if (m?.clase == com.forge.pixpin.guardados.Clase.IMAGEN && ruta != null) withContext(Dispatchers.IO) {
                runCatching {
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(ruta, o)
                    var paso = 1
                    while (maxOf(o.outWidth, o.outHeight) / (paso * 2) >= 720) paso *= 2
                    android.graphics.BitmapFactory.decodeFile(ruta, android.graphics.BitmapFactory.Options().apply { inSampleSize = paso })
                }.getOrNull()
            } else null
        }
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.emoji, fontSize = 24.sp, modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.titulo, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        e.tipo.nombre + " · " + e.detalle,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = alCerrar) { Icon(Icons.Filled.Close, "Cerrar") }
            }
            foto?.let {
                androidx.compose.foundation.Image(
                    it.asImageBitmap(), null,
                    Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                )
            }
            val texto = (m?.texto ?: e.otras).trim()
            if (texto.isNotEmpty() && texto != e.titulo) Text(
                texto.take(600), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp), maxLines = 12, overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { abrir(e, consulta) }) { Text("Abrir", maxLines = 1) }
                if (m != null && e.tipo != BuscarEnTodo.Tipo.NOTA) OutlinedButton(onClick = {
                    apuntar(e, consulta)
                    runCatching { MensajesActivity.irAlMensaje(this@BuscarActivity, m.proyecto, e.detalle, m.id) }
                    finish()
                }) { Text("Ver en el chat", maxLines = 1) }
                OutlinedButton(onClick = {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText(e.titulo, m?.texto?.ifBlank { null } ?: e.titulo))
                    android.widget.Toast.makeText(this@BuscarActivity, "Copiado", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Copiar", maxLines = 1) }
            }
        }
    }

    /** Un doble toque no abre dos veces. */
    private var abierto = false

    /** Lo elegido se apunta en los recientes: la búsqueda que llevó a ello y la cosa misma. */
    private fun apuntar(e: BuscarEnTodo.Elemento, consulta: String) {
        guardarRecientes(recientes.conBusqueda(BuscarEnTodo.sinLaP(consulta)).conAbierto(e))
    }

    private fun abrir(e: BuscarEnTodo.Elemento, consulta: String) {
        if (abierto) return
        abierto = true
        apuntar(e, consulta)
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
