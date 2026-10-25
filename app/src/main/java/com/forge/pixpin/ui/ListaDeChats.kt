package com.forge.pixpin.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Conversacion
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesActivity
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.conversaciones
import com.forge.pixpin.lecciones.LeccionesStore
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.ui.theme.BotonRedondo
import com.forge.pixpin.ui.theme.Cristal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * **Los chats, como una app de mensajería** (4-oct-2026, pedido del usuario): el botón de chat de
 * Proyectos ya no entra directo en el chat del proyecto, sino que **convierte la pantalla en la
 * lista de chats** —Mensajes guardados arriba y cada proyecto por su último mensaje, como
 * WhatsApp o Telegram—. El mismo sitio pasa a ser «Proyectos» y devuelve a las tarjetas. El
 * chat de un proyecto concreto sigue a un deslizamiento de su tarjeta.
 *
 * Las lecciones no cuentan como último mensaje: viven en su sección ([LeccionesStore.sinLecciones]).
 */
@Composable
internal fun ListaDeChats(proyectos: List<Proyecto>, onProyectos: () -> Unit) {
    val contexto = LocalContext.current
    BackHandler(onBack = onProyectos)
    val cambios by MensajesStore.cambios.collectAsState()
    var mensajes by remember { mutableStateOf<List<Mensaje>?>(null) }
    LaunchedEffect(cambios) {
        mensajes = withContext(Dispatchers.IO) {
            runCatching { LeccionesStore.sinLecciones(MensajesStore(contexto).leer()) }.getOrDefault(emptyList())
        }
    }
    val general = contexto.getString(R.string.guardados_titulo)
    // El chat «Asistente», fijo detrás de la general; si aún no existe, se crea al tocarlo.
    val delAsistente = remember(mensajes, proyectos) {
        mensajes?.let { com.forge.pixpin.asistente.ChatDelAsistente.buscar(proyectos, it)?.id }
            ?: com.forge.pixpin.asistente.ChatDelAsistente.SIN_CREAR
    }
    val charlas = remember(mensajes, proyectos) {
        mensajes?.let { conversaciones(it, proyectos.filter { p -> !p.archivado }.associate { p -> p.id to p.nombre }, general, delAsistente) }
    }
    val alcance = rememberCoroutineScope()
    // **Los chats compartidos en los que estoy**, para su chapa (25-oct-2026, del PC `e3f1d60`). Se
    // releen cada vez que cambia la lista de compartidos; sin ninguno, ni se mira la identidad.
    val versionDeCompartidos by com.forge.pixpin.sincro.CompartidosEnDisco.version.collectAsState()
    var compartidos by remember { mutableStateOf<Map<String, com.forge.pixpin.sincro.EnElChat>>(emptyMap()) }
    LaunchedEffect(versionDeCompartidos) {
        compartidos = withContext(Dispatchers.IO) {
            runCatching { com.forge.pixpin.guardados.chatsCompartidosDentro(contexto) }.getOrDefault(emptyMap())
        }
    }
    var eligiendoNuevo by remember { mutableStateOf(false) }
    var enFunciones by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // Atrás, con Funciones puesto, vuelve a Chat (el Escape del PC) antes de salir de la lista.
    BackHandler(enabled = enFunciones) { enFunciones = false }

    // **El buscador de la lista** (25-oct-2026, PC `d106969` y `02b3124`): filtra los chats por su
    // nombre y su última línea sin mirar tildes y, debajo, la sección «Mensajes · N» con los
    // mensajes de cualquier chat que llevan lo escrito. Ver [com.forge.pixpin.guardados.BuscarEnChats].
    var consulta by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = consulta != null && !enFunciones) { consulta = null }
    val aguja = consulta?.trim().orEmpty()
    val filtradas = remember(charlas, aguja) {
        if (aguja.isEmpty()) charlas.orEmpty()
        else charlas.orEmpty().filter { c ->
            com.forge.pixpin.guardados.BuscarEnChats.hayCoincidencia(c.nombre, aguja) ||
                c.ultimo?.let { com.forge.pixpin.guardados.BuscarEnChats.hayCoincidencia(vistazo(it), aguja) } == true
        }
    }
    val indice = remember { com.forge.pixpin.guardados.BuscarEnChats.Indice() }
    var tope by remember(aguja) { mutableIntStateOf(com.forge.pixpin.guardados.BuscarEnChats.DE_PRIMERAS) }
    var hallado by remember { mutableStateOf<Pair<String, com.forge.pixpin.guardados.BuscarEnChats.Resultado>?>(null) }
    // La espera de 180 ms desde la última tecla (el «debounce»: cada tecla cancela la anterior), el
    // índice fuera del hilo de la pantalla y, con algo nuevo en el chat (sincronizar, escribir), se
    // repite sola. Sin nada escrito, la sección se va al momento.
    LaunchedEffect(aguja, tope, mensajes) {
        if (aguja.isEmpty()) { hallado = null; return@LaunchedEffect }
        val lista = mensajes ?: return@LaunchedEffect
        if (hallado?.first != aguja) kotlinx.coroutines.delay(com.forge.pixpin.guardados.BuscarEnChats.ESPERA_MS)
        val r = withContext(Dispatchers.Default) { indice.ponerAlDia(lista); indice.buscar(aguja, tope) }
        hallado = aguja to r
    }
    val resultado = hallado?.takeIf { it.first == aguja }?.second
    // El nombre de cada chat, también de los archivados (que no salen en la lista pero sí se buscan).
    val nombres = remember(charlas, proyectos) {
        proyectos.associate<Proyecto, String?, String> { it.id to it.nombre } + charlas.orEmpty().associate { it.proyecto to it.nombre }
    }
    var cambiandoFoto by remember { mutableStateOf<Conversacion?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
                // **El interruptor Chat · Funciones** (25-oct-2026, del PC `768b73f8`): en el sitio del
                // título. «Funciones» cambia la lista por la rejilla de funciones y se queda puesta
                // como una pestaña; «Chat» devuelve la lista. Ver [PestanaDeFunciones].
                InterruptorChatFunciones(
                    enFunciones, { enFunciones = it },
                    Modifier.padding(start = 16.dp, top = 10.dp, bottom = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                if (!enFunciones) androidx.compose.material3.IconButton(onClick = { consulta = if (consulta == null) "" else null }) {
                    Icon(Icons.Filled.Search, stringResource(R.string.chat_buscar), tint = Cristal.tinta)
                }
                // **El botón de sincronizar gira mientras hay una vuelta en marcha** y lleva la marca
                // roja si la última falló (25-oct-2026, PC `0470294`). Pulsarlo es ver qué pasó.
                androidx.compose.material3.IconButton(onClick = {
                    com.forge.pixpin.sincro.VueltasEnMarcha.olvidarFallo()
                    contexto.startActivity(Intent(contexto, com.forge.pixpin.sincro.SincronizarActivity::class.java))
                }) {
                    IconoDeSincronizar(Cristal.tinta, MaterialTheme.colorScheme.background)
                }
            }
            if (consulta != null && !enFunciones) {
                val foco = remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.compose.material3.OutlinedTextField(
                    value = consulta.orEmpty(), onValueChange = { consulta = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_buscar)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { consulta = null }) { Icon(Icons.Filled.Close, null) }
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(foco)
                )
                LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }
            }
            if (enFunciones) PestanaDeFunciones(onChat = { enFunciones = false }, Modifier.weight(1f).padding(bottom = 80.dp))
            else LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
                items(filtradas, key = { it.proyecto ?: "general" }) { c ->
                    Fila(
                        c, esDelAsistente = c.proyecto == delAsistente, compartido = c.proyecto?.let { compartidos[it] },
                        consulta = aguja,
                        alMantener = if (c.proyecto != null && c.proyecto != com.forge.pixpin.asistente.ChatDelAsistente.SIN_CREAR) {
                            { cambiandoFoto = c }
                        } else null
                    ) {
                        if (c.proyecto == com.forge.pixpin.asistente.ChatDelAsistente.SIN_CREAR) alcance.launch {
                            val p = withContext(Dispatchers.IO) { com.forge.pixpin.asistente.Asistente.asegurarChat(contexto) }
                            MensajesActivity.abrirChatDe(contexto, p.id, p.nombre)
                        } else abrir(contexto, c)
                    }
                }
                // **La sección «Mensajes»**: debajo de los chats; sin chats que casen por nombre pero con
                // mensajes, solo ella (y no «Nada con eso»).
                val r = resultado
                if (r != null) {
                    val elementos = com.forge.pixpin.guardados.BuscarEnChats.elementos(r.encontrados.size, r.hayMas)
                    items(elementos.size, key = { "buscado-$it" }) { i ->
                        when (val el = elementos[i]) {
                            com.forge.pixpin.guardados.BuscarEnChats.Elemento.Cabecera -> CabeceraDeMensajes(r.total)
                            is com.forge.pixpin.guardados.BuscarEnChats.Elemento.DeMensaje -> {
                                val e = r.encontrados[el.n]
                                val proyecto = e.chat.takeIf { it != com.forge.pixpin.sincro.Disco.GENERAL }
                                val nombre = nombres[proyecto] ?: if (proyecto == null) general else e.chat
                                FilaDeMensaje(e, proyecto, nombre) { MensajesActivity.abrirChatEn(contexto, proyecto, nombre, e.id) }
                            }
                            com.forge.pixpin.guardados.BuscarEnChats.Elemento.VerMas -> VerMas(
                                minOf(com.forge.pixpin.guardados.BuscarEnChats.DE_PRIMERAS, r.total - r.encontrados.size)
                            ) { tope += com.forge.pixpin.guardados.BuscarEnChats.DE_PRIMERAS }
                        }
                    }
                }
                if (aguja.isNotEmpty() && filtradas.isEmpty() && (r == null || r.encontrados.isEmpty()) && hallado?.first == aguja) item(key = "nada") {
                    Text(
                        stringResource(R.string.chat_buscar_nada), color = Cristal.tinta.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        cambiandoFoto?.let { c ->
            com.forge.pixpin.guardados.HojaDeLaFotoDelChat(c.proyecto!!, c.nombre, onCerrar = { cambiandoFoto = null })
        }
        // El mismo botón, transformado: ahora lleva de vuelta a los proyectos.
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BotonRedondo(Icons.Filled.Folder, "Proyectos", onProyectos, tamano = 56.dp, puesto = true)
            // **El «+» de los chats** (25-oct-2026, del PC `e3f1d60`): primero, normal o compartido.
            // El compartido nace ya en `compartidos.json` conmigo dentro y se abren sus personas para
            // invitar (Añadir, el código y el QR), como «Compartir» de siempre.
            Spacer(Modifier.width(12.dp))
            Box {
                BotonRedondo(Icons.Filled.Add, "Chat nuevo", { eligiendoNuevo = true }, tamano = 48.dp)
                com.forge.pixpin.guardados.MenuDeChatNuevo(eligiendoNuevo, { eligiendoNuevo = false }) { conPersonas ->
                    alcance.launch { chatNuevo(contexto, conPersonas) }
                }
            }
        }
    }
}

/**
 * **El interruptor Chat · Funciones**: una sola pastilla con las dos partes y la puesta más clara,
 * como el conmutador de vistas de Tareas (dos botones sueltos parecían dos acciones distintas).
 */
@Composable
private fun InterruptorChatFunciones(enFunciones: Boolean, cambiar: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val forma = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    Row(modifier.clip(forma).background(Cristal.boton, forma).padding(3.dp)) {
        listOf(false to R.string.proyectos_interruptor_chat, true to R.string.proyectos_interruptor_funciones).forEach { (esta, texto) ->
            val puesta = enFunciones == esta
            Text(
                androidx.compose.ui.res.stringResource(texto),
                color = if (puesta) Cristal.tintaPuesta else Cristal.tinta.copy(alpha = 0.75f),
                fontSize = 15.sp, fontWeight = if (puesta) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(forma).background(if (puesta) Cristal.puesto else androidx.compose.ui.graphics.Color.Transparent, forma)
                    .clickable { cambiar(esta) }.padding(horizontal = 16.dp, vertical = 7.dp)
            )
        }
    }
}

/**
 * **Un chat nuevo desde la lista** (25-oct-2026): el proyecto nuevo, como el «+» de Proyectos, y su
 * chat abierto. [conPersonas]: además compartido, y encima sus personas para invitar. Si compartir
 * falla, queda como chat normal y se avisa (`cc-nuevo-error` del PC).
 */
private suspend fun chatNuevo(contexto: android.content.Context, conPersonas: Boolean) {
    val app = contexto.applicationContext as com.forge.pixpin.PixPinApp
    val p = app.proyectos.nuevo(contexto.getString(R.string.proyecto_nuevo_nombre), System.currentTimeMillis())
    val compartido = conPersonas && withContext(Dispatchers.IO) {
        runCatching { com.forge.pixpin.guardados.compartirChatNuevo(contexto, p.id, p.nombre) }.isSuccess
    }
    MensajesActivity.abrirChatDe(contexto, p.id, p.nombre)
    if (compartido) com.forge.pixpin.sincro.ChatCompartidoActivity.abrir(contexto, p.id, p.nombre)
    else if (conPersonas) android.widget.Toast.makeText(contexto, "No se pudo compartir el chat nuevo: queda como chat normal", android.widget.Toast.LENGTH_LONG).show()
}

private fun abrir(contexto: android.content.Context, c: Conversacion) {
    if (c.proyecto != null) MensajesActivity.abrirChatDe(contexto, c.proyecto, c.nombre)
    else contexto.startActivity(Intent(contexto, MensajesActivity::class.java))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Fila(
    c: Conversacion, esDelAsistente: Boolean = false, compartido: com.forge.pixpin.sincro.EnElChat? = null,
    consulta: String = "",
    /** Mantener pulsado: el menú con «Cambiar foto del chat…» (el clic derecho del PC). */
    alMantener: (() -> Unit)? = null,
    alTocar: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = alTocar, onLongClick = alMantener?.let { { menu = true } })
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(50.dp)) {
            // **La foto del chat** (25-oct-2026): su imagen, su emoji o sus iniciales en su color; sin
            // ella, lo de siempre. Ver [com.forge.pixpin.guardados.CirculoDelChat].
            if (c.proyecto == null) Box(Modifier.size(50.dp).clip(CircleShape).background(Cristal.boton, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.BookmarkBorder, null, tint = Cristal.tinta)
            }
            else com.forge.pixpin.guardados.CirculoDelChat(c.proyecto, c.nombre, 50.dp, Cristal.boton) {
                if (esDelAsistente) Text("🤖", fontSize = 22.sp)
                else Text(c.nombre.trim().take(1).uppercase().ifBlank { "·" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Cristal.tinta)
            }
            // **La chapa de compartido** (25-oct-2026): abajo a la derecha del avatar, con un aro del
            // color del fondo. Un chat normal no la lleva, ni uno del que salí. Va encima de la foto.
            if (compartido != null) com.forge.pixpin.guardados.ChapaDeCompartido(
                MaterialTheme.colorScheme.background, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp)
            )
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.foto_chat_menu)) },
                    leadingIcon = { Icon(Icons.Filled.AccountCircle, null) },
                    onClick = { menu = false; alMantener?.invoke() }
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Delante del nombre, las dos personas y cuántas son si son más de una, como el
                // icono de grupo de Telegram delante del nombre del grupo.
                if (compartido != null) {
                    val verde = androidx.compose.ui.graphics.Color(com.forge.pixpin.sincro.EntrePersonas.VERDE_COMPARTIDO)
                    Icon(Icons.Filled.Groups, contentDescription = null, tint = verde, modifier = Modifier.size(16.dp))
                    if (compartido.personas > 1) Text("${compartido.personas}", fontSize = 12.sp, color = verde, modifier = Modifier.padding(start = 2.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(conResaltado(c.nombre, consulta), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Cristal.tinta,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                c.ultimo?.let { Text(hora(it.cuando), fontSize = 12.sp, color = Cristal.tinta.copy(alpha = 0.6f)) }
            }
            Text(
                c.ultimo?.let { vistazo(it) } ?: if (esDelAsistente) "Pregunta, pide un dibujo o una gráfica" else "Sin mensajes",
                fontSize = 14.sp, color = Cristal.tinta.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** [texto] con lo buscado en negrita (sin mirar tildes, como el filtro). */
private fun conResaltado(texto: String, consulta: String): androidx.compose.ui.text.AnnotatedString {
    val tramos = com.forge.pixpin.guardados.BuscarEnChats.coincidencias(texto, consulta)
    return tramado(texto, tramos, null)
}

private fun tramado(
    texto: String, tramos: List<com.forge.pixpin.guardados.BuscarEnChats.Tramo>, fondo: androidx.compose.ui.graphics.Color?
) = androidx.compose.ui.text.buildAnnotatedString {
    append(texto)
    for (t in tramos) if (t.desde in 0..t.hasta && t.hasta <= texto.length) addStyle(
        androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.ExtraBold, background = fondo ?: androidx.compose.ui.graphics.Color.Unspecified),
        t.desde, t.hasta
    )
}

/** La franja «Mensajes · N» de la sección (30 de alto, texto de 12 apagado, del color de la cabecera). */
@Composable
private fun CabeceraDeMensajes(total: Int) {
    Box(
        Modifier.fillMaxWidth().height(30.dp).background(Cristal.boton).padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            androidx.compose.ui.res.pluralStringResource(R.plurals.chat_buscar_mensajes, total, total),
            fontSize = 12.sp, color = Cristal.tinta.copy(alpha = 0.65f)
        )
    }
}

/**
 * **Un mensaje encontrado**: lo que mide una fila de chat, con el avatar de su chat, el nombre del
 * chat, la fecha a la derecha y debajo su trozo en una línea, con lo encontrado en negrita y con un
 * fondo del color de enviar al 28 %.
 */
@Composable
private fun FilaDeMensaje(e: com.forge.pixpin.guardados.BuscarEnChats.Encontrado, proyecto: String?, nombre: String, alTocar: () -> Unit) {
    val resalte = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = alTocar).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (proyecto == null) Box(Modifier.size(50.dp).clip(CircleShape).background(Cristal.boton, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.BookmarkBorder, null, tint = Cristal.tinta)
        }
        else com.forge.pixpin.guardados.CirculoDelChat(proyecto, nombre, 50.dp, Cristal.boton) {
            Text(nombre.trim().take(1).uppercase().ifBlank { "·" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Cristal.tinta)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nombre, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Cristal.tinta,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(hora(e.cuando), fontSize = 12.sp, color = Cristal.tinta.copy(alpha = 0.6f))
            }
            Text(
                tramado(e.fragmento.texto, e.fragmento.tramos, resalte),
                fontSize = 14.sp, color = Cristal.tinta.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** «Ver N más», centrada y del color de enviar: añade otros veinte. */
@Composable
private fun VerMas(n: Int, alTocar: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(40.dp).clickable(onClick = alTocar), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.chat_buscar_ver_mas, n), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** La línea de debajo, como en cualquier app de mensajes: el texto, o qué es el archivo. */
internal fun vistazo(m: Mensaje): String = when (m.clase) {
    Clase.VOZ -> "🎙 " + m.nombre.removeSuffix(".m4a").ifBlank { "Nota de voz" }
    Clase.IMAGEN -> "🖼 " + m.texto.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "Foto" }
    Clase.DIBUJO, Clase.PAGINA -> "✏️ " + m.nombre.ifBlank { "Lienzo" }
    Clase.TABLA -> "📊 " + m.nombre.ifBlank { "Tabla" }
    Clase.CROQUIS -> "🧊 " + m.nombre.ifBlank { "Croquis 3D" }
    Clase.PROYECTO -> "📁 " + m.nombre.ifBlank { "Proyecto" }
    // Una carpeta de páginas: su nombre, no su JSON (25-oct-2026). Ver [com.forge.pixpin.guardados.CarpetaDePaginas].
    Clase.MINIAPP -> if (com.forge.pixpin.guardados.CarpetaDePaginas.esCarpeta(m)) "📒 " + m.nombre.ifBlank { "Carpeta de páginas" }
    else "☑️ " + m.texto.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim('#', ' ')
    Clase.ARCHIVO -> "📄 " + m.nombre.ifBlank { "Archivo" }
    // Las fotos de una nota rica, como «🖼 alt» y no su ruta (25-oct-2026). Ver [com.forge.pixpin.guardados.NotaRica.sinRutasDeFotos].
    Clase.NOTA -> com.forge.pixpin.guardados.NotaRica.sinRutasDeFotos(m.texto).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
}

/** La hora si es de hoy, «ayer», o el día. */
private fun hora(ms: Long): String {
    val hoy = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = ms }
    val mismoAno = hoy.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    val dias = hoy.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
    return when {
        mismoAno && dias == 0 -> String.format(java.util.Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        mismoAno && dias == 1 -> "ayer"
        else -> String.format(java.util.Locale.ROOT, "%d/%d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
    }
}
