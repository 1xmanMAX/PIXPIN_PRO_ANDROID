package com.forge.pixpin.guardados

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import com.forge.pixpin.ui.pinzaParaAmpliar
import com.forge.pixpin.ui.CapaDeAmpliacion
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.pin.Voz
import com.forge.pixpin.pin.duracionLegible
import com.forge.pixpin.ui.theme.PixPinTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * Mensajes guardados: **el sitio donde va lo que uno quiere volver a encontrar**.
 *
 * Es una conversación con uno mismo, con la forma de una mensajería porque esa forma ya se
 * sabe usar: se escribe abajo, sale arriba, y lo de hoy está donde el dedo ya estaba. Lo
 * que se copia de ahí —y solo eso— son las **secciones por tipo** de la cabecera, los
 * separadores por día y que guardar no pregunte nada.
 *
 * ## Por qué una pantalla y no una ventana flotante
 *
 * Aquí se escribe, y las ventanas flotantes de PixPin **no cogen el foco** a propósito
 * —para no robarle el teclado a la aplicación de debajo—. Un cajón de documentos en el que
 * no se puede escribir no es un cajón de documentos. Y una pantalla de verdad trae gratis
 * el hueco del teclado, el botón de atrás y el poder salir sin dejar nada por encima.
 *
 * Toda la decisión de qué se ve está en [Mensajes]; aquí solo se pinta y se toca.
 */
class MensajesActivity : ComponentActivity() {

    private lateinit var almacen: MensajesStore

    /**
     * De qué conversación es esta pantalla: la de un proyecto, o la general.
     *
     * La misma pantalla sirve para las dos porque **son la misma cosa**: un cajón con
     * forma de conversación. Lo único que cambia es de quién es lo que se enseña y a
     * quién se le apunta lo que se escriba. Dos pantallas distintas habrían sido dos
     * sitios donde arreglar cada fallo.
     */
    // **Estado de Compose, y no dos campos a secas.**
    //
    // Esta pantalla es `singleTask`: abrirla otra vez **no la vuelve a crear**, el intento
    // nuevo llega por [onNewIntent] y el `onCreate` no se ejecuta. Con campos normales,
    // entrar al chat de un proyecto desde la lista de proyectos dejaba la pantalla como
    // estaba —en la conversación general— y parecía que el botón no llevaba a ninguna
    // parte. Guardado como estado, la pantalla se entera y cambia de conversación.
    private var chatDe by mutableStateOf<String?>(null)
    private var nombreDelChat by mutableStateOf("")

    /**
     * Si a esta conversación se llegó **desde la general**, dentro de esta misma pantalla.
     *
     * Es lo que decide qué hace el botón de atrás, y da dos respuestas distintas porque
     * son dos caminos distintos: viniendo de la lista de conversaciones, atrás devuelve a
     * esa lista; viniendo de la pantalla de proyectos, atrás tiene que devolver **a
     * proyectos**, que es de donde se venía. Sin esto, entrar al chat de un proyecto desde
     * su tarjeta y volver atrás te dejaba en Mensajes guardados, en un sitio por el que no
     * habías pasado.
     */
    private var vineDeLaGeneral by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        almacen = MensajesStore(this)
        aplicar(intent)
        // La portada solo se comprueba en la general: entrando al chat de un proyecto ya
        // se está dentro de la aplicación, y saltar a los permisos ahí sería secuestrar
        // un toque que pedía otra cosa.
        if (chatDe == null) comprobarLaPortada()
        setContent { PixPinTheme { Pantalla() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        aplicar(intent)
    }

    /** De qué conversación viene este intento. */
    private fun aplicar(intent: Intent?) {
        chatDe = intent?.getStringExtra(EXTRA_CHAT_DE)
        nombreDelChat = intent?.getStringExtra(EXTRA_NOMBRE_DEL_CHAT).orEmpty()
        // Llegando por un intento de fuera, atrás cierra esta pantalla y devuelve a
        // quien la abrió. Solo el paso por la lista de conversaciones marca lo contrario.
        vineDeLaGeneral = false
    }

    companion object {
        private const val EXTRA_CHAT_DE = "chat_de"
        private const val EXTRA_NOMBRE_DEL_CHAT = "chat_nombre"

        /** Abre la conversación de un proyecto. */
        fun abrirChatDe(context: android.content.Context, proyecto: String, nombre: String) {
            context.startActivity(
                // **Sin `NEW_TASK`**: abriéndola desde una pantalla, la conversación
                // tiene que quedarse en esa misma pila para que atrás devuelva a los
                // proyectos. Con tarea nueva, atrás se iba al escritorio.
                Intent(context, MensajesActivity::class.java)
                    .putExtra(EXTRA_CHAT_DE, proyecto)
                    .putExtra(EXTRA_NOMBRE_DEL_CHAT, nombre)
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Pantalla() {
        // **Leer el archivo no puede ir en la composición.** Estaba con `remember { cargar() }`,
        // que abre el archivo y lo analiza **en el hilo de la interfaz** justo al abrir la
        // pantalla: con doscientas cosas guardadas eso es la pantalla en blanco un rato,
        // que es la primera impresión que da la función.
        var mensajes by remember { mutableStateOf(emptyList<Mensaje>()) }
        var cargando by remember { mutableStateOf(true) }
        var recargar by remember { mutableIntStateOf(0) }
        androidx.compose.runtime.LaunchedEffect(recargar) {
            mensajes = withContext(Dispatchers.IO) { cargar() }
            cargando = false
        }

        // **Con `rememberSaveable` para que el giro no se lo lleve.** Girar el móvil
        // recreaba la pantalla entera: volvías a «Todo», sin búsqueda y con lo escrito a
        // medias perdido. Perder un texto a medio escribir por girar es imperdonable.
        var seccion by androidx.compose.runtime.saveable.rememberSaveable {
            mutableStateOf(Seccion.TODO)
        }
        var consulta by androidx.compose.runtime.saveable.rememberSaveable {
            mutableStateOf<String?>(null)
        }
        var escrito by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        var grabando by remember { mutableStateOf(false) }
        // **Modo selección.** Guarda identificadores y no mensajes: la lista se recarga
        // entera al escribir o borrar, y con copias guardadas se quedarían marcadas
        // versiones viejas de cosas que ya han cambiado.
        var marcados by remember { mutableStateOf(setOf<String>()) }
        val seleccionando = marcados.isNotEmpty()
        val loMarcado = remember(mensajes, marcados) { mensajes.filter { it.id in marcados } }
        // Salir de la selección es lo primero que hace el botón de atrás, antes que
        // cerrar la pantalla. Al revés se pierde el sitio por querer desmarcar.
        androidx.activity.compose.BackHandler(enabled = seleccionando) { marcados = emptySet() }
        // Y desde la conversación de un proyecto, atrás **vuelve a la general**, que es
        // de donde se viene: cerrar la aplicación entera desde ahí obligaría a abrirla de
        // nuevo para seguir en lo de siempre.
        androidx.activity.compose.BackHandler(
            enabled = !seleccionando && chatDe != null && vineDeLaGeneral
        ) {
            chatDe = null
            nombreDelChat = ""
            vineDeLaGeneral = false
        }
        // A quién se está contestando. Aparte de [marcados] porque son dos cosas
        // distintas: una elige sobre qué actuar ahora mismo, y esta deja un mensaje
        // colgado mientras se escribe la respuesta.
        var respondiendo by remember { mutableStateOf<Mensaje?>(null) }
        // A qué mensaje hay que saltar, cuando se toca una cita.
        var irA by remember { mutableStateOf<String?>(null) }
        // Y a qué fila hay que volver desde el salto. Telegram guarda esto en
        // `returnToMessageId` (`ChatActivity.java:10545-10553`): si llegaste a un sitio
        // saltando a una cita, el botón de abajo te devuelve al salto, no al final.
        // Sin ello, leer una cita de hace tres meses te deja tirado allí.
        var volverA by remember { mutableStateOf<Int?>(null) }
        // Lo último que se ha mandado desde aquí. Es lo que dispara bajar al final.
        var loMandado by remember { mutableStateOf<String?>(null) }
        // A cuál se acaba de saltar, para teñirlo un momento al llegar.
        var reciénLlegado by remember { mutableStateOf<String?>(null) }
        // Qué se está eligiendo con el clip: el menú, luego el proyecto, luego la página.
        var eligiendoQue by remember { mutableStateOf(false) }
        // **Las pestañas están escondidas hasta que se piden.**
        //
        // En Telegram, Fotos/Archivos/Voz no viven en el chat sino en el perfil, y lo
        // único que sale bajo la barra son unos chips **mientras se busca**
        // (`ChatActivity.java:8890`). Tenerlas siempre puestas costaba cuarenta puntos
        // de alto en la pantalla donde manda el contenido, todo el rato, para algo que
        // se usa de vez en cuando. Se piden tocando el título, que es el blanco más
        // grande que hay.
        var fichasALaVista by androidx.compose.runtime.saveable.rememberSaveable {
            mutableStateOf(false)
        }
        // Por cuál de los fijados va el paseo. Crece sin parar y se toma el resto: así
        // no hay que reajustarlo cuando se fija o se suelta algo mientras se mira.
        var porElFijado by remember { mutableIntStateOf(0) }
        var eligiendoProyecto by remember { mutableStateOf<Boolean?>(null) }
        var eligiendoMini by remember { mutableStateOf(false) }
        var eligiendoChat by remember { mutableStateOf(false) }
        // La etiqueta por la que se está filtrando, y qué se está reenviando.
        var porEtiqueta by androidx.compose.runtime.saveable.rememberSaveable {
            mutableStateOf<String?>(null)
        }
        var reenviando by remember { mutableStateOf<List<Mensaje>>(emptyList()) }
        var etiquetando by remember { mutableStateOf<Mensaje?>(null) }
        var compartiendo by remember { mutableStateOf<Mensaje?>(null) }
        var paginasDe by remember { mutableStateOf<com.forge.pixpin.motor.Proyecto?>(null) }

        fun refrescar() { recargar++ }
        androidx.compose.runtime.DisposableEffect(Unit) {
            recargarLaLista = { recargar++ }
            onDispose { recargarLaLista = null }
        }

        // **Al volver de fuera se relee.**
        //
        // De aquí se sale al editor, a los proyectos y a otras aplicaciones, y lo que
        // pasa allí cambia lo que hay que enseñar: una foto anotada, una página que ya
        // no está. Sin esto se vuelve a una lista que enseña cómo estaban las cosas
        // antes de salir, y la edición parece que no se guardó.
        val dueño = androidx.compose.ui.platform.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(dueño) {
            val mirón = androidx.lifecycle.LifecycleEventObserver { _, evento ->
                if (evento == androidx.lifecycle.Lifecycle.Event.ON_RESUME) recargar++
            }
            dueño.lifecycle.addObserver(mirón)
            onDispose { dueño.lifecycle.removeObserver(mirón) }
        }

        val elegirArchivo = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { guardarArchivo(it); refrescar() } }

        // **Filtrar y buscar, en dos pasos.** Con la consulta dentro de la misma clave,
        // cada tecla volvía a filtrar y a **ordenar** la lista entera, y ordenar no
        // depende de lo que se escriba. Separados, escribir solo paga la búsqueda.
        val deEsteChat = remember(mensajes, chatDe) { delChat(mensajes, chatDe) }
        val deLaSeccion = remember(deEsteChat, seccion) { deSeccion(deEsteChat, seccion) }
        val visibles = remember(deLaSeccion, consulta, porEtiqueta) {
            val buscados =
                if (consulta.isNullOrBlank()) deLaSeccion else buscar(deLaSeccion, consulta!!)
            porEmoji(buscados, porEtiqueta)
        }
        val tramos = remember(visibles) { porDias(visibles) { diaDe(it) } }
        // Dónde cae cada mensaje dentro de su racha: se calcula una vez para toda la
        // lista, no burbuja por burbuja. Mirar al vecino desde dentro de la burbuja
        // obligaría a pasarle la lista entera a cada una y a recorrerla en cada
        // recomposición, que es justo lo que hace que una lista larga se arrastre.
        val sitios = remember(tramos) { sitiosPorId(tramos) }
        // Las citas, por identificador. Buscar el original recorriendo la lista **por
        // cada burbuja** son varios miles de comparaciones en cada pasada; el mapa se
        // hace una vez, igual que [sitiosPorId] y por el mismo motivo.
        val porId = remember(mensajes) { mensajes.associateBy { it.id } }

        Scaffold(
            topBar = {
                Column {
                    if (seleccionando) {
                        // **La barra de arriba se sustituye, no se añade otra debajo.**
                        //
                        // Es lo que hace Telegram (`ActionBar` en modo selección) y el
                        // motivo es que mientras se eligen cosas el título y el buscador
                        // no sirven de nada; lo que hace falta es cuántas van y qué se
                        // puede hacer con ellas, en el sitio donde ya estaba mirando.
                        //
                        // Las acciones de uno solo —comentar, compartir, sacar a la
                        // pantalla— **desaparecen** con dos o más en vez de quedarse
                        // apagadas: un botón apagado se sigue pulsando y no explica nada.
                        TopAppBar(
                            navigationIcon = {
                                IconButton(onClick = { marcados = emptySet() }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = getString(
                                            com.forge.pixpin.R.string.cancel
                                        )
                                    )
                                }
                            },
                            title = {
                                // Los dígitos ruedan al cambiar, como el `AnimatedTextView`
                                // de Telegram: sin eso el número salta y no se percibe si
                                // subió o bajó, que es justo lo que se está mirando.
                                androidx.compose.animation.AnimatedContent(
                                    targetState = marcados.size,
                                    transitionSpec = {
                                        val sube = targetState > initialState
                                        val entra = androidx.compose.animation.slideInVertically {
                                            if (sube) it else -it
                                        } + androidx.compose.animation.fadeIn()
                                        val sale = androidx.compose.animation.slideOutVertically {
                                            if (sube) -it else it
                                        } + androidx.compose.animation.fadeOut()
                                        entra togetherWith sale
                                    },
                                    label = "cuantos"
                                ) { cuantos ->
                                    Text(
                                        resources.getQuantityString(
                                            com.forge.pixpin.R.plurals.guardados_elegidos,
                                            cuantos, cuantos
                                        ),
                                        fontSize = 18.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                }
                            },
                            actions = {
                                // **Solo lo que es de bloque.**
                                //
                                // Aquí había siete iconos, y tres de ellos —responder,
                                // compartir, sacar a la pantalla— solo valían con uno
                                // marcado: aparecían y desaparecían según cuántos
                                // llevaras, que es una barra que cambia debajo del dedo.
                                // Esas tres viven en el menú de la pulsación larga, que
                                // es donde se actúa sobre **un** mensaje. Aquí se queda
                                // lo que de verdad tiene sentido en bloque.
                                // Copiar sale siempre que haya texto que copiar, con uno
                                // o con veinte: es el botón más usado con notas sueltas.
                                // Con varios, Telegram los junta con una línea en blanco
                                // en medio (`ChatActivity.java:3699-3724`), que es lo que
                                // hace que lo pegado se lea como lo que era.
                                val copiables = loMarcado.filter { it.texto.isNotBlank() }
                                if (copiables.isNotEmpty()) {
                                    IconButton(onClick = {
                                        val junto = copiables.sortedBy { it.cuando }
                                            .joinToString("\n\n") { it.texto }
                                        val portapapeles = getSystemService(CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                        portapapeles.setPrimaryClip(
                                            android.content.ClipData.newPlainText(null, junto)
                                        )
                                        marcados = emptySet()
                                    }) {
                                        Icon(
                                            Icons.Filled.ContentCopy,
                                            contentDescription = getString(
                                                com.forge.pixpin.R.string.guardados_copiar
                                            )
                                        )
                                    }
                                }
                                // Fijar mira lo que hay marcado: si algo no está fijado,
                                // el botón fija todo; si ya lo estaban todos, los suelta.
                                // Con una selección mixta, «fijar» es lo que se espera.
                                val fijarTodos = loMarcado.any { !it.fijado }
                                IconButton(onClick = {
                                    guardarAparte(mensajes.map {
                                        if (it.id in marcados) it.copy(fijado = fijarTodos) else it
                                    }) { refrescar() }
                                    marcados = emptySet()
                                }) {
                                    Icon(
                                        Icons.Filled.PushPin,
                                        contentDescription = getString(
                                            if (fijarTodos) com.forge.pixpin.R.string.guardados_fijar
                                            else com.forge.pixpin.R.string.guardados_soltar
                                        )
                                    )
                                }
                                IconButton(onClick = {
                                    loMarcado.forEach { almacen.borrarAdjunto(it.ruta) }
                                    guardarAparte(
                                        mensajes.filterNot { it.id in marcados }
                                    ) { refrescar() }
                                    marcados = emptySet()
                                }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = getString(
                                            com.forge.pixpin.R.string.cd_delete
                                        )
                                    )
                                }
                            }
                        )
                        // Las pestañas siguen la misma regla que fuera de la selección:
                        // si no estaban abiertas, entrar a elegir no las abre. Aparecían
                        // de golpe cuarenta y seis puntos que nadie había pedido y toda
                        // la lista daba un salto hacia abajo.
                        if (fichasALaVista || consulta != null) {
                            Fichas(seccion, deEsteChat.any { it.enBuzon }) { seccion = it }
                        }
                        return@Column
                    }
                    TopAppBar(
                        navigationIcon = {
                            // La flecha solo dentro de un proyecto: en la general no hay
                            // adónde volver, y una flecha que no lleva a ningún sitio
                            // enseña a no fiarse de las flechas.
                            if (chatDe != null && vineDeLaGeneral) {
                                IconButton(onClick = {
                                    chatDe = null
                                    nombreDelChat = ""
                                    vineDeLaGeneral = false
                                }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = getString(
                                            com.forge.pixpin.R.string.guardados_titulo
                                        )
                                    )
                                }
                            }
                        },
                        title = {
                            if (consulta == null) {
                                // Telegram esconde el subtítulo en Mensajes guardados
                                // porque allí diría «en línea», que hablando solo no
                                // significa nada (`ChatAvatarContainer.java:1097`).
                                // Aquí sí hay algo que decir, y es justo lo que se
                                // pregunta de un cajón de documentos: cuántas cosas
                                // guarda y cuánto ocupan.
                                //
                                // Y mantener pulsado el título abre el buscador, como
                                // en Telegram (`ChatAvatarContainer.java:376-382`): es
                                // el blanco más grande de la pantalla y llegar a la
                                // lupa de la esquina obliga a cruzarla entera.
                                Column(
                                    Modifier.combinedClickable(
                                        onClick = { fichasALaVista = !fichasALaVista },
                                        onLongClick = { consulta = "" }
                                    )
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Donde Telegram pone el avatar del chat, aquí va
                                        // el marcador: es lo que dice de un vistazo en qué
                                        // pantalla estás, ahora que es la primera que se
                                        // abre y ya no se llega a ella desde ningún sitio.
                                        Box(
                                            Modifier
                                                .size(32.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    androidx.compose.foundation.shape.CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                if (chatDe == null) Icons.Filled.BookmarkBorder
                                                else Icons.Filled.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                        Spacer(Modifier.size(10.dp))
                                        Text(
                                            nombreDelChat.ifBlank {
                                                getString(com.forge.pixpin.R.string.guardados_titulo)
                                            }
                                        )
                                        // La flechita es lo único que dice que aquí hay
                                        // algo que abrir. Sin ella, unas pestañas
                                        // escondidas son unas pestañas que no existen.
                                        Icon(
                                            Icons.Filled.KeyboardArrowDown,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(start = 2.dp)
                                                .size(18.dp)
                                                .rotate(if (fichasALaVista) 180f else 0f)
                                        )
                                    }
                                    if (mensajes.isNotEmpty()) {
                                        Text(
                                            // El tamaño solo si se sabe: casi nada
                                            // guarda su peso, y `tamanoLegible(0)`
                                            // devuelve vacío, así que el subtítulo
                                            // decía «12 cosas · » con el separador
                                            // colgando de la nada.
                                            listOfNotNull(
                                                resources.getQuantityString(
                                                    com.forge.pixpin.R.plurals.guardados_cuantos,
                                                    deEsteChat.size, deEsteChat.size
                                                ),
                                                tamanoLegible(
                                                    deEsteChat.sumOf { it.bytes }
                                                ).ifBlank { null }
                                            ).joinToString(" · "),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                // El buscador **sustituye al título**, no se añade
                                // debajo: mientras se busca no hace falta saber en
                                // qué pantalla se está, se sabe de sobra.
                                // **En píldora, como todo lo que se escribe aquí.**
                                //
                                // El `TextField` de Material trae subrayado y relleno de
                                // formulario: en una barra de arriba se ve como un campo
                                // de registro. Telegram redondea el suyo y lo deja del
                                // color del fondo hundido, que es lo que lo hace parecer
                                // un hueco donde escribir y no un trámite.
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier
                                            .heightIn(min = ALTO_DE_LA_FICHA)
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // **El teclado sale solo al abrir la búsqueda.**
                                        //
                                        // Tocar la lupa y encontrarse un campo vacío que
                                        // hay que volver a tocar son dos toques para una
                                        // intención sola. En cualquier mensajería el
                                        // campo llega con el cursor puesto.
                                        val foco = remember { androidx.compose.ui.focus.FocusRequester() }
                                        LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = consulta.orEmpty(),
                                            onValueChange = { consulta = it },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontSize = 16.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            cursorBrush =
                                                androidx.compose.ui.graphics.SolidColor(
                                                    MaterialTheme.colorScheme.primary
                                                ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(foco),
                                            decorationBox = { campo ->
                                                if (consulta.isNullOrEmpty()) {
                                                    Text(
                                                        getString(
                                                            com.forge.pixpin.R.string
                                                                .guardados_buscar
                                                        ),
                                                        fontSize = 16.sp,
                                                        color = MaterialTheme.colorScheme
                                                            .onSurfaceVariant
                                                    )
                                                }
                                                campo()
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { consulta = if (consulta == null) "" else null }) {
                                Icon(
                                    if (consulta == null) Icons.Filled.Search else Icons.Filled.Close,
                                    // La misma etiqueta para buscar y para cerrar la
                                    // búsqueda dejaba a quien usa el lector de pantalla
                                    // sin saber cuál de las dos cosas iba a pasar.
                                    contentDescription = getString(
                                        if (consulta == null) com.forge.pixpin.R.string.guardados_buscar
                                        else com.forge.pixpin.R.string.cd_close
                                    )
                                )
                            }
                            // **Lo demás de la aplicación, detrás de los tres puntos.**
                            //
                            // Esta pantalla pasa a ser lo primero que se ve, y lo que se
                            // hace aquí a diario es dejar algo o volver a buscarlo. Los
                            // proyectos, los ajustes y arrancar la bola se tocan de
                            // uvas a peras, así que van donde Telegram pone lo suyo:
                            // al lado de la lupa, detrás de una puerta. Sacarlos a la
                            // barra les daría el mismo peso que a buscar, que es lo que
                            // de verdad se usa.
                            var masOpciones by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { masOpciones = true }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = getString(
                                            com.forge.pixpin.R.string.guardados_mas
                                        )
                                    )
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = masOpciones,
                                    onDismissRequest = { masOpciones = false }
                                ) {
                                    // **El nexo.** Desde la conversación general se
                                    // entra a la de cualquier proyecto: es lo que hace
                                    // que esto sea el sitio desde el que se llega a todo
                                    // y no una conversación más entre otras.
                                    if (chatDe == null) {
                                        DelMenu(com.forge.pixpin.R.string.guardados_chats) {
                                            masOpciones = false
                                            eligiendoChat = true
                                        }
                                    }
                                    DelMenu(com.forge.pixpin.R.string.proyectos_titulo) {
                                        masOpciones = false
                                        abrirLaPortada(enProyectos = true)
                                    }
                                    DelMenu(com.forge.pixpin.R.string.start_app) {
                                        masOpciones = false
                                        com.forge.pixpin.floating.PinHostService.start(this@MensajesActivity)
                                        Toast.makeText(
                                            this@MensajesActivity,
                                            com.forge.pixpin.R.string.app_started,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    DelMenu(com.forge.pixpin.R.string.ajustes_titulo) {
                                        masOpciones = false
                                        abrirLaPortada(enProyectos = false)
                                    }
                                }
                            }
                        }
                    )
                    // Al buscar salen solas: ahí es cuando de verdad hacen falta, que
                    // es exactamente cuando las saca Telegram.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = fichasALaVista || consulta != null
                    ) {
                        Fichas(seccion, deEsteChat.any { it.enBuzon }) { seccion = it }
                    }
                    // **La fila de etiquetas, como la de Telegram en Guardados.**
                    //
                    // Sale **solo mientras se busca** (`ChatActivity.java:8890`), y por
                    // el mismo motivo que allí: el resto del tiempo es una fila de
                    // colores que no se toca, y en la pantalla manda el contenido. Al
                    // buscar, en cambio, es media búsqueda hecha: la mayoría de las
                    // veces uno no recuerda la palabra, recuerda que lo marcó.
                    val etiquetas = remember(deEsteChat) { emojisUsados(deEsteChat) }
                    if (consulta != null && etiquetas.isNotEmpty()) {
                        androidx.compose.foundation.lazy.LazyRow(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(etiquetas) { emoji ->
                                FilterChip(
                                    selected = porEtiqueta == emoji,
                                    onClick = {
                                        porEtiqueta = if (porEtiqueta == emoji) null else emoji
                                    },
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    modifier = Modifier.height(ALTO_DE_LA_FICHA),
                                    label = { Text(emoji, fontSize = 16.sp) }
                                )
                            }
                        }
                    }
                    // **La barra de fijados.**
                    //
                    // Lo fijado es lo que quieres tener a mano, y tenerlo escondido en
                    // una pestaña es tenerlo lejos. Telegram lo pone bajo la barra de
                    // arriba, con unas rayitas al lado que dicen cuántos hay y por cuál
                    // vas (`ChatActivity.java:11326`, `PinnedLineView.java:74-77`), y
                    // tocarlo salta al siguiente, volviendo al primero al acabar
                    // (`:11336-11344`).
                    val fijados = remember(deEsteChat) {
                        deEsteChat.filter { it.fijado && !it.enBuzon }.sortedBy { it.cuando }
                    }
                    // **El buzón dice en voz alta que caduca.**
                    //
                    // Lo que entra ahí se borra solo si nadie lo toca, y eso es
                    // exactamente la clase de cosa que no se puede dejar implícita: quien
                    // no lo sepa dará por guardado algo que va a desaparecer. Es un
                    // renglón y solo sale dentro del buzón.
                    if (seccion == Seccion.BUZON) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer) {
                            Text(
                                getString(com.forge.pixpin.R.string.guardados_buzon_aviso),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                    if (fijados.isNotEmpty() && seccion != Seccion.FIJADOS &&
                        consulta == null
                    ) {
                        BarraDeFijados(
                            fijados = fijados,
                            cual = porElFijado % fijados.size,
                            onTocar = {
                                irA = fijados[porElFijado % fijados.size].id
                                porElFijado++
                            },
                            onVerTodos = if (fijados.size <= 1) null else {
                                { seccion = Seccion.FIJADOS }
                            }
                        )
                    }
                }
            },
            bottomBar = {
                Column {
                    // Mientras se eligen cosas no se escribe: el sitio del teclado lo
                    // ocupan las acciones de arriba, y dejar el campo ahí invita a
                    // escribir en una conversación que en ese momento no acepta texto.
                    if (seleccionando) return@Column
                    // **A quién se contesta, encima del campo y con su aspa.**
                    //
                    // Sin esta barra uno escribe una respuesta larga sin saber si el
                    // mensaje sigue enganchado —o peor, creyendo que sí— y la manda
                    // suelta. Va arriba del campo porque es lo que se mira al escribir.
                    respondiendo?.let { r ->
                        val acento = MaterialTheme.colorScheme.primary
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(
                                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // La misma barra de color que la cita dentro de la
                                // burbuja: son lo mismo visto en dos sitios, y con dos
                                // aspectos distintos habría que aprenderlo dos veces.
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = null,
                                    tint = acento,
                                    modifier = Modifier.padding(start = 12.dp).size(18.dp)
                                )
                                Box(
                                    Modifier
                                        .padding(start = 10.dp, top = 6.dp, bottom = 6.dp)
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .background(acento, RoundedCornerShape(2.dp))
                                )
                                Box(Modifier.padding(start = 8.dp)) { SelloDelMensaje(r, 30.dp) }
                                Text(
                                    resumen(r),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { respondiendo = null }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = getString(
                                            com.forge.pixpin.R.string.cancel
                                        )
                                    )
                                }
                            }
                        }
                    }
                    // **Cuántos hay y cómo salir de la búsqueda.**
                    //
                    // Filtrar la lista es lo correcto en un cajón de documentos —quien
                    // busca «factura enero» quiere las cinco juntas para compararlas, no
                    // ir saltando de una a otra— pero deja al que busca encerrado en su
                    // propio filtro. Telegram, cuando filtra por etiqueta, siempre ofrece
                    // volver a ver el resto (`ChatActivity.java:10473-10486`); esta barra
                    // es eso: el recuento, y un botón que quita el filtro y **deja el
                    // primer resultado a la vista**, para no perder dónde estabas.
                    if (!consulta.isNullOrBlank() && visibles.isNotEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    resources.getQuantityString(
                                        com.forge.pixpin.R.plurals.guardados_resultados,
                                        visibles.size, visibles.size
                                    ),
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                androidx.compose.material3.TextButton(onClick = {
                                    irA = visibles.first().id
                                    consulta = null
                                }) {
                                    Text(
                                        getString(com.forge.pixpin.R.string.guardados_en_contexto),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                    BarraDeEscribir(
                    escrito = escrito,
                    grabando = grabando,
                    onEscrito = { escrito = it },
                    onAdjuntar = { eligiendoQue = true },
                    onEmpezarVoz = {
                        grabando = empezarVoz()
                        grabando
                    },
                    onSoltarVoz = { guardar ->
                        if (grabando) {
                            grabando = false
                            if (guardar) {
                                pararVoz()
                                loMandado = System.currentTimeMillis().toString()
                                refrescar()
                            } else tirarVoz()
                        }
                    },
                    onMandar = {
                        if (escrito.isNotBlank()) {
                            val id = UUID.randomUUID().toString()
                            almacen.anadir(
                                Mensaje(
                                    id = id,
                                    cuando = System.currentTimeMillis(),
                                    clase = Clase.NOTA,
                                    texto = escrito.trim(),
                                    respondeA = respondiendo?.id,
                                    proyecto = chatDe
                                )
                            )
                            escrito = ""
                            respondiendo = null
                            loMandado = id
                            refrescar()
                        }
                    }
                    )
                }
            }
        ) { hueco ->
            // **El fondo no es el mismo papel que las burbujas.**
            //
            // En Telegram el chat tiene su propio fondo, y no es un capricho: si el
            // hueco y la burbuja son del mismo color, las burbujas dejan de leerse como
            // objetos sueltos y la lista se vuelve una masa. Aquí no se mete una foto de
            // fondo —pesaría y se pelearía con lo escrito— sino un degradado suave
            // sacado del propio tema, que funciona igual de día que de noche.
            val fondo = androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = ALTO_DEL_FONDO),
                    MaterialTheme.colorScheme.tertiary.copy(alpha = BAJO_DEL_FONDO)
                )
            )
            // La foto que se esté sujetando con los dedos, encima de todo. Es el mismo
            // aparato del carrusel de proyectos —dos dedos la despegan de la lista y la
            // amplían, y al soltar vuelve a su sitio—, así que aquí no se inventa un
            // segundo zoom: se reutiliza [ZoomDeHoja].
            val ampliada = com.forge.pixpin.ui.recordarZoomDeHoja()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .background(fondo)
                    .padding(hueco)
            ) {
            // **Se abre por el final.**
            //
            // La conversación va del más viejo al más nuevo, así que abrir por arriba
            // dejaba mirando lo de hace tres meses: había que desplazarse hasta abajo
            // cada vez para ver lo que uno acababa de mandar. Se arranca en la última
            // fila, que es donde está lo de hoy.
            val lista = rememberLazyListState(
                initialFirstVisibleItemIndex = (visibles.size + tramos.size - 1)
                    .coerceAtLeast(0)
            )
            // Y al mandar algo nuevo, baja hasta ello. Sin esto, lo que acabas de
            // escribir aparece fuera de la vista y parece que no se ha mandado.
            // Saltar al mensaje citado. Se busca **en lo que se está viendo**: si el
            // original está en otra sección o filtrado por la búsqueda, no hay a dónde
            // saltar, y moverse a ciegas sería peor que no hacer nada.
            androidx.compose.runtime.LaunchedEffect(irA, tramos) {
                val destino = irA ?: return@LaunchedEffect
                // **Si el destino no está a la vista, se va a por él.**
                //
                // Antes se buscaba solo en lo que se estaba viendo y, si el mensaje
                // estaba en otra sección o lo tapaba la búsqueda, no pasaba **nada**: ni
                // salto ni aviso. Tocar una cita o un fijado desde la sección de fotos
                // era tocar y que no ocurriera nada, que es de las peores cosas que
                // puede hacer un botón. Ahora se quita el filtro y se cambia de sección,
                // y el salto se hace en la pasada siguiente, cuando la lista ya lo tiene.
                val elCitado = mensajes.firstOrNull { it.id == destino }
                if (elCitado == null) {
                    irA = null
                    return@LaunchedEffect
                }
                if (visibles.none { it.id == destino }) {
                    // **A la sección donde de verdad vive, no siempre a «Todo».**
                    //
                    // Lo del buzón **no sale en «Todo»** —a propósito: es lo que está a
                    // punto de borrarse solo y no se mezcla con lo guardado—, así que
                    // mandar el salto ahí era mandarlo al único sitio donde nunca iba a
                    // aparecer. Y como todo lo que se pinea entra al buzón, eso era la
                    // mayoría de las citas: tocabas la referencia y no pasaba nada.
                    consulta = null
                    porEtiqueta = null
                    seccion = if (elCitado.enBuzon) Seccion.BUZON else Seccion.TODO
                    return@LaunchedEffect
                }
                // **`irA` se limpia al final, no antes.**
                //
                // Estaba puesto justo aquí, y `irA` es clave de este mismo efecto: al
                // ponerlo a null la corrutina se cancelaba **a sí misma** en la primera
                // suspensión, que es exactamente la línea siguiente. O sea que el salto
                // se pedía y se cancelaba antes de moverse: tocabas la cita y no pasaba
                // nada, sin que nada fallara. Limpiarlo al final deja que la animación y
                // el destello terminen; que el efecto se cancele entonces da igual,
                // porque ya no queda nada que hacer.
                val fila = filaDe(tramos, destino)
                if (fila >= 0) {
                    volverA = lista.firstVisibleItemIndex
                    runCatching { lista.animateScrollToItem(fila) }
                    // **El destello al llegar.**
                    //
                    // Saltar sin más deja mirando una pantalla de burbujas parecidas sin
                    // saber cuál era la buscada. Telegram tiñe la de destino un segundo
                    // (`ChatActivity.java:16634`), y ese segundo es toda la diferencia
                    // entre haber llegado y creer que no ha pasado nada.
                    reciénLlegado = destino
                    delay(LO_QUE_DURA_EL_DESTELLO)
                    if (reciénLlegado == destino) reciénLlegado = null
                }
                irA = null
            }

            // **Se baja al final al mandar algo, no cada vez que la lista cambia de
            // tamaño**, que era lo que hacía antes y estaba mal: borrar un mensaje,
            // filtrar, cambiar de sección o tocar «ver en contexto» también cambian el
            // tamaño. Lo de «ver en contexto» era lo más visible — se peleaba con el
            // salto y ganaba este, así que acababas al final del chat en vez de en el
            // resultado, y el destello se gastaba en una burbuja que no estabas viendo.
            androidx.compose.runtime.LaunchedEffect(loMandado) {
                if (loMandado == null || visibles.isEmpty()) return@LaunchedEffect
                runCatching {
                    lista.animateScrollToItem((visibles.size + tramos.size - 1).coerceAtLeast(0))
                }
            }
            LazyColumn(
                state = lista,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 10.dp, vertical = 8.dp
                ),
                // Sin espaciado fijo: lo pone cada burbuja según esté agrupada o
                // suelta, que es lo que separa una racha de mensajes del siguiente.
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Mientras se lee el archivo no se enseña nada: con `visibles` vacía a
                // secas, **la primera pantalla de la aplicación empezaba dando la
                // bienvenida** y saltaba a la lista un instante después.
                if (visibles.isEmpty() && !cargando) {
                    item { Vacio(seccion, consulta != null) }
                }
                tramos.forEach { tramo ->
                    item(key = "dia-${tramo.dia}") { SeparadorDeDia(tramo.mensajes.first().cuando) }
                    items(tramo.mensajes, key = { it.id }) { m ->
                        Burbuja(
                            m = m,
                            respondido = m.respondeA?.let { porId[it] },
                            sitio = sitios[m.id] ?: Sitio(false, false),
                            consulta = consulta,
                            recarga = recargar,
                            ampliada = ampliada,
                            acciones = remember(m, mensajes) { Acciones(
                                etiquetar = { etiquetando = m },
                                reenviar = { reenviando = listOf(m) },
                                compartirComo = { compartiendo = m },
                                cambiarTexto = { nuevo ->
                                    guardarAparte(mensajes.map {
                                        if (it.id == m.id) it.copy(texto = nuevo) else it
                                    }) { refrescar() }
                                },
                                responder = { respondiendo = m },
                                copiar = if (m.texto.isBlank()) null else {
                                    {
                                        val portapapeles =
                                            getSystemService(CLIPBOARD_SERVICE)
                                                as android.content.ClipboardManager
                                        portapapeles.setPrimaryClip(
                                            android.content.ClipData
                                                .newPlainText(null, m.texto)
                                        )
                                    }
                                },
                                fijar = {
                                    guardarAparte(mensajes.map {
                                        if (it.id == m.id) it.copy(fijado = !it.fijado)
                                        else it
                                    }) { refrescar() }
                                },
                                compartir = { compartir(m) },
                                pinear = { pinear(m) },
                                rescatar = if (!m.enBuzon) null else {
                                    {
                                        guardarAparte(mensajes.map {
                                            if (it.id == m.id) {
                                                rescatado(it, System.currentTimeMillis())
                                            } else it
                                        }) { refrescar() }
                                    }
                                },
                                seleccionar = { marcados = marcados + m.id },
                                borrar = {
                                    almacen.borrarAdjunto(m.ruta)
                                    guardarAparte(
                                        mensajes.filterNot { it.id == m.id }
                                    ) { refrescar() }
                                }
                            ) },
                            marcado = m.id in marcados,
                            reciénLlegado = m.id == reciénLlegado,
                            seleccionando = seleccionando,
                            // Con la selección abierta, **tocar marca**: es lo que
                            // espera cualquiera tras marcar el primero, y obligar a
                            // mantener pulsado cada uno haría inservible el modo.
                            onTocar = {
                                if (seleccionando) {
                                    marcados = if (m.id in marcados) marcados - m.id
                                               else marcados + m.id
                                } else abrir(m)
                            },
                            // Mantener pulsado alterna, no añade: en Telegram el toque
                            // y la pulsación larga entran por la misma puerta
                            // (`processRowSelect`), y con esto desmarcar el último cierra
                            // el modo solo, sin código aparte.
                            onMantener = {
                                marcados = if (m.id in marcados) marcados - m.id
                                           else marcados + m.id
                            },
                            onResponder = { respondiendo = m },
                            onIrAlCitado = { irA = m.respondeA }
                        )
                    }
                }
            }

            // **La fecha flotante mientras se desplaza.**
            //
            // Los separadores de día son una fila más de la lista, así que en cuanto uno
            // se desplaza se pierden de vista y deja de saberse en qué día está —justo
            // cuando más falta hace, que es buscando algo de hace semanas—.
            //
            // Copiado de Telegram con sus números: **150 ms de fundido** y **medio segundo
            // quieto antes de irse** (`ChatActivity.java:13505` y `:609`). Medio segundo
            // no es capricho: menos y parpadea entre dos empujones del dedo, más y se
            // queda estorbando encima de lo que ibas a leer.
            val densidadDePantalla = androidx.compose.ui.platform.LocalDensity.current
            val alcanceDeLaLista = androidx.compose.runtime.rememberCoroutineScope()
            // **Solo la enciende el dedo, y esto era otro fallo mío.**
            //
            // Telegram la activa únicamente mientras se arrastra —`SCROLL_STATE_DRAGGING`,
            // `ChatActivity.java:6752`— y nunca en un desplazamiento pedido por el
            // programa. Aquí no es un detalle: esta pantalla se coloca sola al final al
            // abrir y en cada envío, así que mirando solo «se está moviendo» la pastilla
            // daba un fogonazo cada vez que mandabas algo.
            //
            // Se queda encendida durante el impulso —Telegram tampoco la apaga hasta que
            // todo para (`:6723`)— y se va medio segundo después.
            val arrastrando by lista.interactionSource.collectIsDraggedAsState()
            val moviendose = lista.isScrollInProgress
            var fechaALaVista by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(arrastrando, moviendose) {
                when {
                    arrastrando -> fechaALaVista = true
                    !moviendose -> {
                        kotlinx.coroutines.delay(ESPERA_DE_LA_FECHA)
                        fechaALaVista = false
                    }
                }
            }
            val opacidad by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (fechaALaVista) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(FUNDIDO_DE_LA_FECHA),
                label = "fecha"
            )
            // **Con `tramos` de clave, y esto era un fallo mío.** Sin ella, el
            // `derivedStateOf` se queda con la lista de la primera composición: al mandar
            // algo, buscar o cambiar de ficha, la pastilla enseñaba días de una lista que
            // ya no existe.
            val cuandoArriba by androidx.compose.runtime.remember(tramos) {
                androidx.compose.runtime.derivedStateOf {
                    cuandoDeLaFila(tramos, lista.firstVisibleItemIndex)
                }
            }
            // **El botón de bajar al final.**
            //
            // Sale al **subir cien puntos** y se va al bajar otros cien, no según lo cerca
            // que se esté del final: es lo que hace Telegram (`ChatActivity.java:6714` y
            // `:6847-6866`) y la diferencia importa. Con «aparece si no estás abajo» el
            // botón parpadea con cada empujoncito del dedo; con la cuenta acumulada hay
            // que moverse de verdad para que aparezca, y de verdad para que se vaya.
            val alturaDeGatillo = with(densidadDePantalla) { SUBIDA_PARA_EL_BOTON.toPx() }
            var subido by remember { mutableFloatStateOf(0f) }
            var botonALaVista by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(lista) {
                var anterior = 0
                androidx.compose.runtime.snapshotFlow {
                    lista.firstVisibleItemIndex * 1000 - lista.firstVisibleItemScrollOffset
                }.collect { ahora ->
                    val delta = (ahora - anterior).toFloat()
                    anterior = ahora
                    if (delta < 0 && !botonALaVista) {
                        subido += -delta
                        if (subido > alturaDeGatillo) { botonALaVista = true; subido = 0f }
                    } else if (delta > 0 && botonALaVista) {
                        subido += delta
                        if (subido > alturaDeGatillo) { botonALaVista = false; subido = 0f }
                    }
                }
            }
            // Se esconde además mientras se elige o se graba, como Telegram
            // (`updatePagedownButtonVisibility`, `:16906-16933`): ahí estorba a lo que
            // se está haciendo y encima se solapa con lo que sale por debajo.
            androidx.compose.animation.AnimatedVisibility(
                visible = botonALaVista && !seleccionando && !grabando,
                enter = androidx.compose.animation.fadeIn() +
                    androidx.compose.animation.scaleIn(initialScale = 0.7f),
                exit = androidx.compose.animation.fadeOut() +
                    androidx.compose.animation.scaleOut(targetScale = 0.7f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            ) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = {
                        val vuelta = volverA
                        volverA = null
                        alcanceDeLaLista.launch {
                            runCatching {
                                lista.animateScrollToItem(
                                    vuelta ?: (visibles.size + tramos.size - 1)
                                        .coerceAtLeast(0)
                                )
                            }
                        }
                    }
                ) {
                    Icon(
                        if (volverA != null) Icons.AutoMirrored.Filled.Reply
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = getString(
                            if (volverA != null) com.forge.pixpin.R.string.guardados_volver_al_salto
                            else com.forge.pixpin.R.string.guardados_al_final
                        )
                    )
                }
            }

            cuandoArriba?.let { cuando ->
                // **La opacidad se lee dentro de la capa, no en la composición.**
                //
                // Leída fuera, los 150 ms del fundido recomponían este `Box` entero —con
                // la lista dentro— una vez por fotograma. Leída aquí, el fundido no
                // recompone nada: solo repinta.
                //
                // Y **sin `Surface`**: la de Material lleva dentro un capturador de toques
                // que se tragaba el arrastre justo donde aparece la pastilla, que es justo
                // donde está el dedo desplazándose.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .graphicsLayer { alpha = opacidad }
                        .background(
                            // Al 0,75 del fondo, como la suya (`ChatActionCell.java:3486`):
                            // así se distingue de los separadores de verdad.
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                            RoundedCornerShape(11.dp)
                        )
                ) {
                    // **El mismo rótulo que el separador del día, dicho igual.**
                    //
                    // Eran la misma frase con dos aspectos: el separador a 14 con peso y
                    // la pastilla flotante a 11 normal. Al desplazar, la pastilla pasa
                    // justo por encima de los separadores y el salto de tamaño se veía.
                    Text(
                        fechaDe(cuando),
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // La foto sujetada, por encima de todo lo demás. Se pone una vez y no hace
            // nada mientras no haya ninguna cogida.
            CapaDeAmpliacion(ampliada)
            }
        }

        // **El clip: archivo, una página o un proyecto entero.**
        //
        // Lo segundo y lo tercero **no copian nada**: son la puerta a lo que ya existe.
        // Copiar un proyecto daría dos que se editan por separado y divergen, que es la
        // peor forma de perder trabajo — la que no se nota hasta que has escrito en el
        // equivocado.
        if (eligiendoQue) {
            // **Una hoja que sube desde abajo, no un diálogo en medio.**
            //
            // Es lo que hace Telegram con su `ChatAttachAlert`, y el motivo es dónde está
            // el dedo: se acaba de tocar el clip, que está abajo. Un diálogo centrado
            // obliga a subir la mano para elegir y volver a bajarla para seguir
            // escribiendo. La hoja sale justo encima del clip.
            //
            // Los botones son redondos y con icono porque **se reconocen sin leer**: a la
            // tercera vez uno ya va al de la derecha sin mirar la etiqueta.
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { eligiendoQue = false }
            ) {
                // **Cada botón ocupa el ancho partido entre 4,5**, no un reparto a
                // partes iguales (`ChatAttachAlert.java:1505`). El medio botón que
                // sobra es lo que dice que la fila sigue: con el reparto equitativo,
                // cinco opciones parecen cinco y nadie desliza a buscar la sexta.
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.fillMaxWidth().padding(bottom = 28.dp)
                ) {
                val anchoDelBoton = maxWidth / minOf(BOTONES_A_LA_VISTA, 4f)
                androidx.compose.foundation.lazy.LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                ) {
                    item {
                        Row {
                    BotonDeAdjuntar(
                        Icons.Filled.Description,
                        com.forge.pixpin.R.string.guardados_adj_archivo,
                        ancho = anchoDelBoton
                    ) {
                        eligiendoQue = false
                        elegirArchivo.launch(arrayOf("*/*"))
                    }
                    BotonDeAdjuntar(
                        Icons.AutoMirrored.Filled.MenuBook,
                        com.forge.pixpin.R.string.guardados_adj_pagina,
                        ancho = anchoDelBoton
                    ) {
                        eligiendoQue = false
                        eligiendoProyecto = true
                    }
                    BotonDeAdjuntar(
                        Icons.Filled.Folder,
                        com.forge.pixpin.R.string.guardados_adj_proyecto,
                        ancho = anchoDelBoton
                    ) {
                        eligiendoQue = false
                        eligiendoProyecto = false
                    }
                    // Las mini-apps: cosas que se llevan la cuenta solas —lo que falta
                    // por hacer, lo que se lleva gastado— y que en una conversación con
                    // uno mismo son justo lo que uno se manda para no olvidarlo.
                    BotonDeAdjuntar(
                        Icons.Filled.Checklist,
                        com.forge.pixpin.R.string.guardados_adj_miniapp,
                        ancho = anchoDelBoton
                    ) {
                        eligiendoQue = false
                        eligiendoMini = true
                    }
                        }
                    }
                }
                }
            }
        }

        compartiendo?.let { cual ->
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { compartiendo = null }
            ) {
                Column(Modifier.padding(bottom = 28.dp)) {
                    listOf(
                        false to com.forge.pixpin.R.string.guardados_como_imagen,
                        true to com.forge.pixpin.R.string.guardados_como_pdf
                    ).forEach { (pdf, texto) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    compartiendo = null
                                    compartirCompuesto(cual, pdf)
                                }
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (pdf) Icons.Filled.PictureAsPdf else Icons.Filled.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                getString(texto),
                                fontSize = 16.sp,
                                modifier = Modifier.padding(start = 14.dp)
                            )
                        }
                    }
                }
            }
        }

        etiquetando?.let { cual ->
            // **Una fila de emojis y ya.** Un selector de emoji entero para elegir entre
            // seis es abrir un cajón para coger un bolígrafo que estaba encima de la
            // mesa. Estos seis cubren lo que se etiqueta de verdad: importante, hecho,
            // pendiente, idea, dinero, sitio.
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { etiquetando = null }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .padding(bottom = 28.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ETIQUETAS.forEach { emoji ->
                        val puesta = cual.emoji == emoji
                        Text(
                            emoji,
                            fontSize = 30.sp,
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    if (puesta) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    } else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable {
                                    // Tocar la que ya está puesta la quita: es el mismo
                                    // gesto para poner y para arrepentirse.
                                    guardarAparte(mensajes.map {
                                        if (it.id == cual.id) {
                                            it.copy(emoji = if (puesta) null else emoji)
                                        } else it
                                    }) { refrescar() }
                                    etiquetando = null
                                }
                                .padding(10.dp)
                        )
                    }
                }
            }
        }

        if (reenviando.isNotEmpty()) {
            val proyectos = (application as? PixPinApp)?.proyectos?.proyectos?.value.orEmpty()
            val charlas = remember(mensajes, proyectos) {
                conversaciones(
                    mensajes,
                    proyectos.associate { it.id to it.nombre },
                    getString(com.forge.pixpin.R.string.guardados_titulo)
                )
            }
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { reenviando = emptyList() }
            ) {
                Text(
                    getString(com.forge.pixpin.R.string.guardados_reenviar_a),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
                )
                LazyColumn(Modifier.padding(bottom = 28.dp)) {
                    items(charlas, key = { it.proyecto ?: "general" }) { charla ->
                        // La conversación en la que se está no se ofrece: reenviar algo
                        // al sitio donde ya está es hacer una copia al lado del original.
                        if (charla.proyecto == chatDe) return@items
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    reenviarA(reenviando, charla.proyecto)
                                    reenviando = emptyList()
                                    refrescar()
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (charla.proyecto == null) Icons.Filled.BookmarkBorder
                                else Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                charla.nombre,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }

        if (eligiendoChat) {
            // **La lista de conversaciones, como la de Telegram**: cada una con su último
            // mensaje y su hora, ordenadas por lo más reciente, y la general arriba por
            // ser el nexo. Se calcula en una sola pasada sobre todos los mensajes —ver
            // [conversaciones]— y no filtrando la lista una vez por proyecto.
            val proyectos = (application as? PixPinApp)?.proyectos?.proyectos?.value.orEmpty()
            val charlas = remember(mensajes, proyectos) {
                conversaciones(
                    mensajes,
                    proyectos.associate { it.id to it.nombre },
                    getString(com.forge.pixpin.R.string.guardados_titulo)
                )
            }
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { eligiendoChat = false }
            ) {
                LazyColumn(Modifier.padding(bottom = 28.dp)) {
                    items(charlas, key = { it.proyecto ?: "general" }) { charla ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    eligiendoChat = false
                                    // **Se cambia de conversación aquí mismo**, sin
                                    // lanzar otra pantalla: es la misma, y volver a
                                    // lanzarla sobre sí misma no deja nada en la pila a
                                    // donde volver. Así atrás devuelve a la general.
                                    if (charla.proyecto != null) {
                                        chatDe = charla.proyecto
                                        nombreDelChat = charla.nombre
                                        vineDeLaGeneral = true
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        androidx.compose.foundation.shape.CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (charla.proyecto == null) Icons.Filled.BookmarkBorder
                                    else Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    charla.nombre,
                                    fontSize = 16.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    charla.ultimo?.let { resumen(it) }
                                        ?: getString(com.forge.pixpin.R.string.guardados_vacio),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // La hora del último, como en cualquier lista de chats: dice
                            // de un vistazo cuál se movió hoy y cuál lleva un mes quieto.
                            charla.ultimo?.let {
                                Text(
                                    fechaDe(it.cuando),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (eligiendoMini) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { eligiendoMini = false }
            ) {
                Column(Modifier.padding(bottom = 28.dp)) {
                    com.forge.pixpin.mini.MiniApp.entries.forEach { cual ->
                        Text(
                            getString(cual.nombre),
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    eligiendoMini = false
                                    mandarMiniApp(cual)
                                    refrescar()
                                }
                                .padding(horizontal = 24.dp, vertical = 14.dp)
                        )
                    }
                }
            }
        }

        eligiendoProyecto?.let { paraPagina ->
            val todos = (application as? PixPinApp)?.proyectos?.proyectos?.value.orEmpty()
            // Para una página solo valen los que tienen PDF: en un proyecto de lienzos no
            // hay páginas que señalar.
            val validos = if (paraPagina) todos.filter { it.pdfOrigen != null } else todos
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { eligiendoProyecto = null },
                title = {
                    Text(
                        getString(
                            if (paraPagina) com.forge.pixpin.R.string.guardados_adj_pagina
                            else com.forge.pixpin.R.string.guardados_adj_proyecto
                        )
                    )
                },
                text = {
                    if (validos.isEmpty()) {
                        Text(getString(com.forge.pixpin.R.string.guardados_sin_proyectos))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(validos, key = { it.id }) { pr ->
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        eligiendoProyecto = null
                                        if (paraPagina) paginasDe = pr else guardarProyecto(pr)
                                        if (!paraPagina) refrescar()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(pr.nombre, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { eligiendoProyecto = null }) {
                        Text(getString(com.forge.pixpin.R.string.cancel))
                    }
                }
            )
        }

        paginasDe?.let { pr ->
            val hojas = remember(pr) { pr.hojas.filter { it.pagina != null } }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { paginasDe = null },
                title = { Text(pr.nombre) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(hojas, key = { it.id }) { hoja ->
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    paginasDe = null
                                    guardarPagina(pr, hoja)
                                    // **Se dice dónde ha caído.** Lo adjuntado va a la
                                    // conversación en la que estás, y desde el chat de
                                    // un proyecto eso no es la general — quien lo busque
                                    // allí no lo encuentra y parece que no se guardó.
                                    Toast.makeText(
                                        this@MensajesActivity,
                                        getString(
                                            com.forge.pixpin.R.string.guardados_anadido_a,
                                            nombreDelChat.ifBlank {
                                                getString(
                                                    com.forge.pixpin.R.string.guardados_titulo
                                                )
                                            }
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refrescar()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    getString(
                                        com.forge.pixpin.R.string.proyecto_pagina,
                                        (hoja.pagina ?: 0) + 1
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { paginasDe = null }) {
                        Text(getString(com.forge.pixpin.R.string.cancel))
                    }
                }
            )
        }

    }

    // ---- Piezas ----------------------------------------------------------

    /** Las secciones de la cabecera. Es lo que se copia de Telegram y funciona. */
    @OptIn(ExperimentalMaterial3Api::class)
    /** La barra de lo fijado, con sus rayitas y su salto al siguiente. */
    @Composable
    private fun BarraDeFijados(
        fijados: List<Mensaje>,
        cual: Int,
        onTocar: () -> Unit,
        onVerTodos: (() -> Unit)?
    ) {
        val acento = MaterialTheme.colorScheme.primary
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ALTO_DE_LOS_FIJADOS)
                    .clickable(onClick = onTocar)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Las rayitas: como mucho tres a la vez, la de ahora entera y las demás
                // apagadas (`PinnedLineView.java:154`). Con veinte fijados, veinte rayas
                // de un píxel no dirían nada; tres dicen «hay más arriba y más abajo».
                androidx.compose.foundation.Canvas(
                    Modifier.width(3.dp).height(ALTO_DE_LOS_FIJADOS - 16.dp)
                ) {
                    val cuantas = minOf(fijados.size, RAYITAS_A_LA_VEZ)
                    val alto = size.height / cuantas
                    repeat(cuantas) { i ->
                        drawRoundRect(
                            color = if (i == cual % cuantas) acento
                                    else acento.copy(alpha = ALFA_DE_LA_RAYITA),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, i * alto),
                            size = androidx.compose.ui.geometry.Size(
                                size.width, alto - 2f
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                size.width / 2f
                            )
                        )
                    }
                }
                Box(Modifier.padding(start = 10.dp)) {
                    SelloDelMensaje(fijados[cual], 30.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        getString(com.forge.pixpin.R.string.guardados_fijados),
                        fontSize = 12.sp,
                        color = acento,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        resumen(fijados[cual]),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                // El botón de la lista **solo con más de uno**: con uno, la lista es esa
                // misma línea y el botón sobra (`updatePinnedListButton`, `:11439`).
                onVerTodos?.let { verTodos ->
                    IconButton(onClick = verTodos) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = getString(
                                com.forge.pixpin.R.string.guardados_fijados
                            )
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Fichas(actual: Seccion, hayBuzon: Boolean, onElegir: (Seccion) -> Unit) {
        // **El buzón solo sale si hay algo dentro.** Una bandeja vacía siempre a la
        // vista enseña a no mirarla, y cuando por fin tenga algo ya nadie la mira.
        val secciones = remember(hayBuzon) {
            if (hayBuzon) Seccion.entries else Seccion.entries.filter { it != Seccion.BUZON }
        }
        androidx.compose.foundation.lazy.LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(secciones) { s ->
                // Píldoras enteras y de 38 de alto, como los chips de Telegram
                // (`ChatActivity.java:8865-8907`). El chip de Material va con esquinas
                // de ocho, que a este tamaño se lee como un botón cuadrado.
                FilterChip(
                    selected = s == actual,
                    onClick = { onElegir(s) },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.height(ALTO_DE_LA_FICHA),
                    label = { Text(nombreDe(s), fontSize = 13.sp) }
                )
            }
        }
    }

    @Composable
    private fun SeparadorDeDia(cuando: Long) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            // Números de la píldora de Telegram (`ChatActionCell.java:3328, :3339`):
            // radio 11, 8 de aire a los lados, 4 arriba y abajo. La letra es más grande
            // de lo que parece —14 y con peso—: el día es un rótulo que se busca de un
            // vistazo al desplazar, no una nota al pie.
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    fechaDe(cuando),
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }

    /**
     * Lo que se puede hacer con un mensaje suelto.
     *
     * Van juntas en vez de sueltas como siete parámetros porque siempre viajan juntas:
     * el menú las necesita todas y ninguna tiene sentido sin las demás.
     */
    private class Acciones(
        /** Cambia el documento de una mini-app sin salir de la conversación. */
        val cambiarTexto: (String) -> Unit,
        val compartirComo: () -> Unit,
        val etiquetar: () -> Unit,
        val reenviar: () -> Unit,
        val responder: () -> Unit,
        val copiar: (() -> Unit)?,
        val fijar: () -> Unit,
        val compartir: () -> Unit,
        val pinear: () -> Unit,
        val rescatar: (() -> Unit)?,
        val seleccionar: () -> Unit,
        val borrar: () -> Unit
    )

    /**
     * Una burbuja.
     *
     * Todas van al mismo lado. En una mensajería hay dos lados porque hay dos personas;
     * aquí solo hay uno, y partir la pantalla en dos columnas para nada dejaría la mitad
     * del ancho sin usar justo donde se leen documentos.
     */
    @OptIn(
        androidx.compose.foundation.ExperimentalFoundationApi::class,
        androidx.compose.foundation.layout.ExperimentalLayoutApi::class
    )
    @Composable
    private fun Burbuja(
        m: Mensaje,
        respondido: Mensaje?,
        sitio: Sitio,
        consulta: String?,
        recarga: Int,
        ampliada: com.forge.pixpin.ui.ZoomDeHoja,
        acciones: Acciones,
        marcado: Boolean,
        reciénLlegado: Boolean,
        seleccionando: Boolean,
        onTocar: () -> Unit,
        onMantener: () -> Unit,
        onResponder: () -> Unit,
        onIrAlCitado: () -> Unit
    ) {
        // **Deslizar la burbuja para comentarla**, copiado de Telegram y comprobado
        // contra su código (`ChatActivity.java:4963-5000` de DrKLO/Telegram).
        //
        // Es el gesto que hace que comentar salga gratis: el menú largo obliga a esperar,
        // mirar una lista y elegir, y por ese precio uno acaba no comentando nada.
        //
        // Tres detalles suyos que no se adivinan mirando la aplicación, y que son los que
        // hacen que el gesto se sienta bien:
        //
        // 1. **Se arrastra hacia la izquierda**, no hacia la derecha. La flecha asoma por
        //    el lado contrario, que es hacia donde uno está tirando.
        // 2. **El punto de no retorno está en 50 puntos** y el toquecito del móvil
        //    **vuelve a poder darse**: si uno retrocede por debajo y avanza otra vez, se
        //    siente de nuevo. Con un aviso único, dudar una vez te deja a ciegas.
        // 3. **No empieza si la lista se está moviendo.** Un desplazamiento rápido con el
        //    dedo un poco torcido no puede acabar en un comentario.
        val corrimiento = remember(m.id) { androidx.compose.animation.core.Animatable(0f) }
        val alcance = androidx.compose.runtime.rememberCoroutineScope()
        val vibrar = androidx.compose.ui.platform.LocalHapticFeedback.current
        val densidad = androidx.compose.ui.platform.LocalDensity.current
        val tope = with(densidad) { ARRASTRE_MAXIMO.toPx() }
        val gatillo = with(densidad) { ARRASTRE_PARA_RESPONDER.toPx() }
        var yaVibro by remember(m.id) { mutableStateOf(false) }
        val tinteDelDestello by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (reciénLlegado) 0.16f else 0f,
            animationSpec = androidx.compose.animation.core.tween(APAGADO_DEL_DESTELLO),
            label = "destello"
        )
        var menuAbierto by remember(m.id) { mutableStateOf(false) }
        var dondeElDedo by remember(m.id) {
            mutableStateOf(androidx.compose.ui.unit.DpOffset.Zero)
        }

        // Lo marcado se tiñe **de lado a lado**, no solo la burbuja: así se ve de un
        // vistazo cuántas van sin tener que mirar burbuja por burbuja.
        Spacer(Modifier.height(espacioAntes(sitio).dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        marcado -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        // El destello se apaga solo, con un fundido corto, para que no
                        // parezca que se ha quedado seleccionado.
                        reciénLlegado -> MaterialTheme.colorScheme.primary.copy(
                            alpha = tinteDelDestello
                        )
                        else -> androidx.compose.ui.graphics.Color.Transparent
                    }
                )
        ) {
            // **El menú del mensaje, con Borrar el último.**
            //
            // Es el orden de Telegram (`ChatActivity.java:45543-45836`): responder
            // primero, porque es lo que más se hace, y borrar al final, lejos del dedo
            // que viene bajando. Lleva nombres y no iconos mudos: aquí «fijar» y «sacar
            // a la pantalla» son dos cosas distintas y con dibujitos no se distinguen.
            //
            // La última es «Elegir», que es la puerta al modo de varios. En Telegram el
            // menú es la vía principal y la selección la excepción, no al revés.
            androidx.compose.material3.DropdownMenu(
                expanded = menuAbierto,
                onDismissRequest = { menuAbierto = false },
                offset = dondeElDedo
            ) {
                DelMenu(com.forge.pixpin.R.string.guardados_responder,
                        Icons.AutoMirrored.Filled.Reply) {
                    menuAbierto = false; acciones.responder()
                }
                acciones.copiar?.let { copiar ->
                    DelMenu(com.forge.pixpin.R.string.guardados_copiar,
                            Icons.Filled.ContentCopy) {
                        menuAbierto = false; copiar()
                    }
                }
                DelMenu(
                    if (m.fijado) com.forge.pixpin.R.string.guardados_soltar
                    else com.forge.pixpin.R.string.guardados_fijar
                ) { menuAbierto = false; acciones.fijar() }
                DelMenu(com.forge.pixpin.R.string.guardados_compartir, Icons.Filled.Share) {
                    menuAbierto = false
                    // Con algo que componer, primero se pregunta con qué cara sale.
                    if (sePuedeComponer(m)) acciones.compartirComo() else acciones.compartir()
                }
                DelMenu(com.forge.pixpin.R.string.guardados_pinear, Icons.Filled.OpenInNew) {
                    menuAbierto = false; acciones.pinear()
                }
                acciones.rescatar?.let { rescatar ->
                    DelMenu(com.forge.pixpin.R.string.guardados_rescatar,
                            Icons.Filled.BookmarkBorder) {
                        menuAbierto = false; rescatar()
                    }
                }
                DelMenu(com.forge.pixpin.R.string.guardados_etiquetar,
                        Icons.Filled.EmojiEmotions) {
                    menuAbierto = false; acciones.etiquetar()
                }
                DelMenu(com.forge.pixpin.R.string.guardados_reenviar,
                        Icons.AutoMirrored.Filled.Forward) {
                    menuAbierto = false; acciones.reenviar()
                }
                DelMenu(com.forge.pixpin.R.string.guardados_elegir, Icons.Filled.CheckBox) {
                    menuAbierto = false; acciones.seleccionar()
                }
                DelMenu(com.forge.pixpin.R.string.cd_delete, Icons.Filled.Delete,
                        peligro = true) {
                    menuAbierto = false; acciones.borrar()
                }
            }
            // **Lo del buzón lleva su franja.**
            //
            // Con el pie de fechas ya se sabe cuándo se va, pero hay que leerlo. Una
            // franja del color de aviso a la izquierda dice «esto es temporal» antes de
            // leer nada, que es lo que hace falta al bajar por una lista mezclada.
            if (m.enBuzon) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(vertical = 4.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
            // La flecha que asoma por detrás. Se marca según lo que se ha arrastrado, y
            // solo se ve del todo pasado el punto en que soltar ya responde.
            if (corrimiento.value < -1f) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(
                        alpha = (-corrimiento.value / gatillo).coerceIn(0f, 1f)
                    ),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                        .size(20.dp)
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .offset {
                        androidx.compose.ui.unit.IntOffset(corrimiento.value.toInt(), 0)
                    }
                    // Mientras se eligen mensajes no se desliza para comentar: la barra
                    // de responder está escondida, así que se quedaba un «respondiendo»
                    // invisible y al salir de la selección estabas contestando a algo que
                    // no habías elegido.
                    .pointerInput(m.id, seleccionando) {
                        if (seleccionando) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val respondio = -corrimiento.value >= gatillo
                                yaVibro = false
                                alcance.launch {
                                    corrimiento.animateTo(
                                        0f,
                                        androidx.compose.animation.core.spring(
                                            dampingRatio =
                                                androidx.compose.animation.core.Spring
                                                    .DampingRatioMediumBouncy
                                        )
                                    )
                                }
                                if (respondio) onResponder()
                            },
                            onDragCancel = {
                                yaVibro = false
                                alcance.launch { corrimiento.animateTo(0f) }
                            }
                        ) { cambio, delta ->
                            // Solo hacia la izquierda y con tope: pasado el tope la
                            // burbuja deja de seguir al dedo, que es lo que avisa de que
                            // ya está sin necesidad de mirar nada.
                            val nuevo = (corrimiento.value + delta).coerceIn(-tope, 0f)
                            if (nuevo < 0f) cambio.consume()
                            // El toquecito al cruzar el punto de no retorno, **y se puede
                            // volver a sentir**: si uno duda, retrocede y vuelve a
                            // avanzar, lo nota otra vez. Es lo que hace Telegram, y con un
                            // aviso único dudar una vez te deja adivinando.
                            if (-nuevo >= gatillo) {
                                if (!yaVibro) {
                                    yaVibro = true
                                    vibrar.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback
                                            .HapticFeedbackType.LongPress
                                    )
                                }
                            } else {
                                yaVibro = false
                            }
                            alcance.launch { corrimiento.snapTo(nuevo) }
                        }
                    },
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Bottom
            ) {
            // **El marcador para guardarlo, al lado y fuera de la burbuja.**
            //
            // Antes era un botón con la palabra «Guardar esto» metido dentro, y ahí
            // chocaba con la hora y con las fechas de caducidad — tres cosas peleándose
            // por la misma esquina. Fuera no se pelea con nada, y un marcador dice lo que
            // hace sin ocupar una línea: es el mismo icono con el que se guarda en
            // cualquier sitio.
            if (m.enBuzon) {
                IconButton(
                    onClick = { acciones.rescatar?.invoke() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.BookmarkBorder,
                        contentDescription = getString(
                            com.forge.pixpin.R.string.guardados_rescatar
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            // **La casilla, con la burbuja apartándose para hacerle sitio.**
            //
            // Telegram corre la burbuja 35 puntos y mete ahí una casilla de 21, en 200
            // milisegundos y saliendo suave (`ChatMessageCell.java:20645, :20659,
            // :22881`). El tinte de fondo dice «hay algo marcado»; la casilla dice
            // **cuál**, y sin ella una selección de tres en una lista larga obliga a
            // repasar tinte por tinte.
            val hueco by androidx.compose.animation.core.animateDpAsState(
                targetValue = if (seleccionando) HUECO_DE_LA_CASILLA else 0.dp,
                animationSpec = androidx.compose.animation.core.tween(
                    APARTARSE_DE_LA_BURBUJA,
                    easing = if (seleccionando)
                        androidx.compose.animation.core.EaseOut
                    else androidx.compose.animation.core.EaseIn
                ),
                label = "casilla"
            )
            if (hueco > 0.dp) {
                Box(
                    Modifier.width(hueco).padding(bottom = 6.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = marcado,
                        onCheckedChange = null,
                        modifier = Modifier.size(CASILLA_DE_MARCAR)
                    )
                }
            }
            // **El ancho máximo es una parte de la pantalla, no 300 puntos.**
            //
            // Con una medida fija, en una tableta la burbuja ocupa un tercio y la
            // conversación queda en una columna flaca contra el borde; en un móvil
            // estrecho, se come el ancho entero y no se distingue una burbuja de otra.
            // **Por el lado corto de la pantalla, no por el ancho.** En una tableta de
            // 1200 puntos, el 78 % del ancho son burbujas de 936 con renglones de ciento
            // cincuenta caracteres, que no se leen. Telegram acota igual, por el lado
            // corto (`ChatMessageCell.java:7370-7373`).
            val medidas = androidx.compose.ui.platform.LocalConfiguration.current
            val anchoMaximo = minOf(medidas.screenWidthDp, medidas.screenHeightDp)
                .times(PARTE_DE_LA_PANTALLA).dp
                .coerceAtMost(ANCHO_TOPE_DE_BURBUJA)
            // La esquina que toca al vecino se achata a 3: es lo que hace que cuatro
            // notas seguidas se lean como un bloque y no como cuatro cajas sueltas
            // (`ChatMessageCell.java:10978`). Las demás van a 17, el radio de Telegram.
            val r = radios(sitio)
            Surface(
                shape = RoundedCornerShape(
                    topStart = r.arribaIzq.dp, topEnd = r.arribaDer.dp,
                    bottomEnd = r.abajoDer.dp, bottomStart = r.abajoIzq.dp
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .widthIn(max = anchoMaximo)
                    // **Se mira dónde cae el dedo sin quedarse con el toque.**
                    //
                    // El menú tiene que salir donde tocaste —en Telegram el popup nace
                    // ahí (`ChatActivity.java:32161`), y eso es lo que pone la opción a
                    // un centímetro en vez de al otro lado de la pantalla— pero
                    // `combinedClickable` no dice el punto.
                    //
                    // Se resolvía con un `detectTapGestures` en la burbuja, y eso **se
                    // tragaba el toque de la cita**, que va dentro: tocar la referencia
                    // no llevaba al mensaje original porque el toque no llegaba nunca a
                    // la cita, se lo quedaba la burbuja entera. Ahora el punto se anota
                    // en la pasada inicial —mirar sin consumir— y el toque lo reparte
                    // `combinedClickable`, que sí cede a lo que tiene dentro.
                    .pointerInput(m.id) {
                        awaitEachGesture {
                            val abajo = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                            )
                            dondeElDedo = androidx.compose.ui.unit.DpOffset(
                                abajo.position.x.toDp(), 0.dp
                            )
                        }
                    }
                    .combinedClickable(
                        onClick = onTocar,
                        onLongClick = {
                            if (seleccionando) onMantener() else menuAbierto = true
                        }
                    )
            ) {
                // **Una foto sola se come la burbuja.**
                //
                // Es lo que hace Telegram (`shouldDrawTimeOnMedia`,
                // `ChatMessageCell.java:23625`): sin texto que acompañar, el marco de
                // color y los 10 puntos de aire alrededor no envuelven nada, solo
                // encogen la foto y meten un borde que no dice nada. La hora se va
                // entonces encima, sobre una píldora oscura.
                val soloFoto = m.clase == Clase.IMAGEN &&
                    m.texto.isBlank() && respondido == null
                // El relleno no es simétrico: al lado de la cola sobran 6 puntos
                // (`ChatMessageCell.java:3364`), y sin ellos la hora se pega al borde
                // redondeado justo donde la burbuja se estrecha.
                val pad = relleno(sitio)
                Column(
                    if (soloFoto) Modifier
                    else Modifier.padding(
                        start = pad.inicio.dp, top = pad.arriba.dp,
                        end = pad.fin.dp, bottom = pad.abajo.dp
                    )
                ) {
                    // A qué contesta, si contesta a algo: es lo que convierte una
                    // lista de cosas en una conversación donde uno se explica.
                    respondido?.let { citado ->
                        // **La cita, con la barra de color de Telegram.**
                        //
                        // Antes era una caja `surfaceVariant` dentro de una burbuja
                        // `surfaceVariant`: el mismo color sobre el mismo color, o sea,
                        // invisible. Telegram la resuelve con una barra de 3 puntos del
                        // color de acento y un fondo de ese mismo acento al 10 %
                        // (`ReplyMessageLine.java:238, :536`), y ocupando **el ancho
                        // entero** de la burbuja, no el del texto: así se lee como una
                        // banda y no como un recuadro suelto flotando dentro.
                        //
                        // Las esquinas no son iguales: pegadas a la barra van a 4 y la
                        // de fuera a 10 (`ChatMessageCell.java:22519, :22536`).
                        val acento = MaterialTheme.colorScheme.primary
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 4.dp, bottomStart = 4.dp,
                                        topEnd = 10.dp, bottomEnd = 4.dp
                                    )
                                )
                                .background(acento.copy(alpha = ALFA_DE_LA_CITA))
                                // Tocar la cita lleva al original, como en cualquier
                                // mensajería: sin eso, una conversación larga obliga a
                                // buscar a mano de qué se estaba hablando.
                                .clickable { onIrAlCitado() }
                                .height(IntrinsicSize.Min)
                        ) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(acento)
                            )
                            Row(
                                Modifier.padding(start = 8.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                            SelloDelMensaje(citado)
                            Column(Modifier.padding(end = 8.dp)) {
                                // Donde Telegram pone el autor, aquí va el nombre de la
                                // cosa: es lo que identifica a qué se contesta cuando
                                // todos los mensajes son de uno mismo.
                                if (citado.nombre.isNotBlank()) {
                                    Text(
                                        citado.nombre,
                                        fontSize = 13.sp,
                                        color = acento,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    resumen(citado),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            }
                        }
                    }
                    when (m.clase) {
                        Clase.NOTA -> {}
                        Clase.IMAGEN -> Miniatura(m, soloFoto, recarga, ampliada)
                        // **Una página adjunta se ve, no se lee.**
                        //
                        // Como fila de archivo decía «documento.pdf · pág. 7», que es
                        // justo lo que uno no recuerda: lo que se recuerda es lo que
                        // había señalado en ella. Se enseña la hoja con lo dibujado
                        // encima, y tocarla abre el editor donde se anotó.
                        Clase.PAGINA -> MiniaturaDePagina(m, recarga, ampliada)
                        Clase.VOZ -> FilaDeVoz(m)
                        Clase.MINIAPP -> FilaDeMiniApp(m) { nuevo ->
                            acciones.cambiarTexto(nuevo)
                        }
                        else -> FilaDeArchivo(m)
                    }
                    // **La hora va en el mismo renglón que el final del texto, y
                    // cuando no cabe baja pero se queda a la derecha.**
                    //
                    // El intento anterior era una fila que se dobla sola, y estaba mal:
                    // el texto entra como **un solo hijo** y ocupa el ancho entero en
                    // cuanto pasa de una línea, así que la hora saltaba a un renglón
                    // propio **pegada al borde izquierdo**. O sea, la hora aparecía a la
                    // derecha en las notas de una línea y a la izquierda en todo lo
                    // demás: el mismo dato en dos sitios distintos de la misma lista.
                    //
                    // Ahora se reserva el hueco **dentro del propio texto**, con un
                    // espacio en blanco del ancho de la hora al final. Ese hueco pasa por
                    // el mismo reparto en líneas que las palabras: si cabe tras el último
                    // renglón, la hora se queda ahí; si no cabe, arrastra un renglón más
                    // y la hora baja. Es lo que hace Telegram midiendo la última línea
                    // (`ChatMessageCell.java:12887-12924`), pero midiendo de verdad en
                    // vez de calcularlo aparte y esperar que coincida.
                    // La mini-app **no lleva pie**: su texto es el documento entero, y
                    // como pie salía el Markdown en crudo debajo de la propia mini-app
                    // —la tabla de los gastos con sus barras y sus guiones—, que es
                    // exactamente lo que la mini-app existe para no tener que leer.
                    val alPie = when (m.clase) {
                        Clase.NOTA -> m.texto
                        Clase.MINIAPP -> null
                        else -> m.texto.takeIf { it.isNotBlank() }
                    }
                    if (soloFoto) return@Column
                    // **La tarjeta del enlace.**
                    //
                    // Encima del texto y no debajo, como en cualquier mensajería: lo que
                    // se toca es la tarjeta, y ponerla al final obliga a leer la nota
                    // entera para llegar a ella.
                    if (m.clase == Clase.NOTA) {
                        val enlace = remember(m.texto) { primerEnlace(m.texto) }
                        enlace?.let { TarjetaDeEnlace(it) }
                    }
                    val medidor = androidx.compose.ui.text.rememberTextMeasurer()
                    val estiloDeLaHora = androidx.compose.ui.text.TextStyle(fontSize = TAMANO_DE_LA_HORA)
                    val hora = horaDe(m.cuando)
                    // El ancho del hueco es el de la hora más el aire, y la chincheta si
                    // la lleva. Se mide una vez por hora distinta, no por fotograma.
                    // El hueco cuenta con lo que va al lado de la hora: la chincheta y
                    // la etiqueta. Sin sumarlas, la hora sale pisando la última palabra.
                    val huecoDeLaHora = remember(hora, m.fijado, m.enBuzon, m.emoji) {
                        medidor.measure(hora, estiloDeLaHora).size.width +
                            with(densidad) {
                                var extra = 6.dp
                                if (m.fijado) extra += 14.dp
                                if (m.emoji != null) extra += 16.dp
                                extra.toPx()
                            }
                    }
                    // **En el buzón, el pie va en su renglón y la hora no se mete en el
                    // texto.**
                    //
                    // El truco de reservarle a la hora un hueco dentro del texto es lo
                    // que hace que un «sí» quepa en una pastilla; pero en el buzón hay
                    // además dos fechas, y ahí ese mismo truco acababa con las fechas, la
                    // hora y el botón de guardar pisándose en la esquina de abajo. Con
                    // fechas, la línea de abajo es una línea de verdad.
                    if (m.enBuzon) {
                        alPie?.let { texto ->
                            Text(
                                conLoQueCasa(texto, consulta),
                                fontSize = if (m.clase == Clase.NOTA) 15.sp else 13.sp,
                                modifier = Modifier.padding(
                                    top = if (m.clase == Clase.NOTA) 0.dp else 6.dp
                                )
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                cortaDe(m.cuando),
                                fontSize = TAMANO_DE_LA_HORA,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowRightAlt,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp).padding(horizontal = 1.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                // En rojo la de irse: es la única de las dos que obliga a
                                // hacer algo antes de que llegue.
                                cortaDe(m.cuando + DIAS_DEL_BUZON * UN_DIA),
                                fontSize = TAMANO_DE_LA_HORA,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            m.emoji?.let {
                                Text(it, fontSize = TAMANO_DE_LA_HORA)
                                Spacer(Modifier.size(3.dp))
                            }
                            Text(
                                horaDe(m.cuando),
                                fontSize = TAMANO_DE_LA_HORA,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        return@Column
                    }
                    Box {
                        alPie?.let { texto ->
                            val conHueco = androidx.compose.ui.text.buildAnnotatedString {
                                append(conLoQueCasa(texto, consulta))
                                appendInlineContent(HUECO_DE_LA_HORA, " ")
                            }
                            Text(
                                conHueco,
                                fontSize = if (m.clase == Clase.NOTA) 15.sp else 13.sp,
                                inlineContent = mapOf(
                                    HUECO_DE_LA_HORA to androidx.compose.foundation.text.InlineTextContent(
                                        androidx.compose.ui.text.Placeholder(
                                            width = with(densidad) { huecoDeLaHora.toSp() },
                                            height = 1.sp,
                                            placeholderVerticalAlign = androidx.compose.ui.text
                                                .PlaceholderVerticalAlign.Bottom
                                        )
                                    ) {}
                                ),
                                modifier = Modifier.padding(
                                    top = if (m.clase == Clase.NOTA) 0.dp else 6.dp
                                )
                            )
                        }
                        // **En el buzón, cuándo entró y cuándo se va.**
                        //
                        //
                        // Lo del buzón se borra solo, y eso no puede ser una sorpresa: si
                        // no se dice la fecha, uno da por guardado algo que va a
                        // desaparecer. Con «17 ago → 24 ago» delante se ve de un vistazo
                        // cuánto le queda, y cuál es el siguiente en irse.
                        Row(
                            Modifier.align(Alignment.BottomEnd),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            m.emoji?.let {
                                Text(it, fontSize = 11.sp)
                                Spacer(Modifier.size(3.dp))
                            }
                            if (m.fijado) {
                                Icon(
                                    Icons.Filled.PushPin,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.size(3.dp))
                            }
                            Text(
                                hora,
                                fontSize = TAMANO_DE_LA_HORA,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            }
        }
    }

    @Composable
    private fun Miniatura(
        m: Mensaje,
        conHoraEncima: Boolean = false,
        recarga: Int = 0,
        ampliada: com.forge.pixpin.ui.ZoomDeHoja? = null
    ) {
        // **La foto se descodifica fuera del hilo de la pantalla.**
        //
        // Estaba en un `remember`, que parece seguro pero corre en la composición de la
        // fila: descodificar una captura de móvil son decenas de milisegundos dentro de
        // un fotograma que dura dieciséis. Se notaba como un tropiezo por cada foto que
        // entraba a la vista, y otra vez al volver a subir.
        var original by remember(m.ruta) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(m.ruta) {
            original = m.ruta?.let {
                // Y se lee a más resolución: 600 puntos estirados al alto nuevo en una
                // pantalla de tres veces son menos píxeles de los que tiene el hueco, y
                // se veía blanda sin necesidad.
                withContext(Dispatchers.IO) {
                    com.forge.pixpin.pin.ImageStore.load(it, ANCHO_DE_LA_FOTO)
                }
            }
        }
        // **Si la foto se ha editado, en la lista se ve editada.**
        //
        // Una foto que enseña el original después de haberle señalado cosas encima es
        // una mentira pequeña pero cara: uno la abre otra vez creyendo que no se guardó.
        //
        // El dibujo se pinta **fuera del hilo de la pantalla** y solo cuando el fichero
        // ha cambiado —la fecha del fichero es la clave—, y a escala pequeña. Mientras
        // llega se ve la foto de siempre, así que la lista nunca se queda con un hueco.
        val dibujo = m.referencia
        var editada by remember(dibujo) { mutableStateOf<android.graphics.Bitmap?>(null) }
        if (dibujo != null && m.clase == Clase.IMAGEN) {
            val rutaDelDibujo = remember(dibujo) {
                com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, dibujo)
            }
            // La fecha del fichero se vuelve a mirar en cada vuelta a la pantalla: es
            // lo que hace que lo dibujado en el editor aparezca aquí al salir de él.
            val version = remember(rutaDelDibujo, recarga) {
                File(rutaDelDibujo).lastModified()
            }
            LaunchedEffect(rutaDelDibujo, version) {
                if (version == 0L) return@LaunchedEffect
                editada = withContext(Dispatchers.IO) {
                    runCatching {
                        val escena = com.forge.pixpin.motor.ExcalidrawStore
                            .cargar(rutaDelDibujo) ?: return@runCatching null
                        // **Con quien sepa dar las imágenes.** `aBitmap` no las
                        // carga por su cuenta: el dibujo guarda la foto por
                        // identificador y hay que decirle de dónde sacarla. Sin esta
                        // lambda se pintaba lo dibujado encima de nada, y la foto
                        // desaparecía justo al anotarla, que es cuando más importa.
                        com.forge.pixpin.motor.DrawExport.aBitmap(
                            escena, ESCALA_DE_LA_FOTO
                        ) { id ->
                            escena.files[id]?.path?.let {
                                com.forge.pixpin.pin.ImageStore.load(it)
                            }
                        }
                    }.getOrNull()
                }
            }
        }
        val mapa = editada ?: original
        if (mapa == null) {
            // Sin bitmap no hay foto que enseñar —el fichero se borró desde fuera—, y
            // una caja gris no explica nada: se enseña como archivo, con su nombre.
            FilaDeArchivo(m)
            return
        }
        var hueco by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        val ventana = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        val alcance = androidx.compose.runtime.rememberCoroutineScope()
        Box {
            androidx.compose.foundation.Image(
                bitmap = mapa.asImageBitmap(),
                // Con la descripción a nulo, un cajón lleno de capturas se lee como una
                // lista de silencios: no hay forma de saber cuál es cuál sin verlas.
                contentDescription = getString(
                    com.forge.pixpin.R.string.guardados_foto_de, horaDe(m.cuando)
                ),
                modifier = Modifier
                    // **Más alta de lo que estaba.** A 260 puntos una captura de móvil
                    // —alta y estrecha— salía como una tira en la que no se lee nada, y
                    // había que ampliarla para saber qué era. Telegram encaja la foto en
                    // una caja del 70 % del lado corto de la pantalla
                    // (`ChatMessageCell.java:9801-9808`); esto es lo mismo dicho en alto.
                    .heightIn(max = ALTO_DE_LA_FOTO)
                    .clip(RoundedCornerShape(if (conHoraEncima) RADIO_DE_LA_FOTO else 4.dp))
                    .onGloballyPositioned { hueco = it.boundsInWindow() }
                    // **Dos dedos amplían la foto sin salir de la conversación.**
                    //
                    // Con un dedo se sigue desplazando la lista, que es lo que se hace
                    // casi siempre; hasta que no baja el segundo no se toca nada. Y al
                    // soltar, la foto vuelve a su hueco, así que mirar una foto de cerca
                    // no cuesta abrir una pantalla y luego cerrarla.
                    .then(
                        if (ampliada == null) Modifier
                        else Modifier.pinzaParaAmpliar(
                            alCoger = {
                                val cogida = ampliada.coger(mapa.asImageBitmap(), hueco, ventana)
                                // **La nítida se pide solo de la que se sujeta.**
                                // La lista carga las fotos pequeñas —a seiscientos
                                // puntos— porque en una burbuja no se ve más; ampliando
                                // cuatro veces eso son píxeles estirados. Se vuelve a
                                // leer del archivo en grande mientras los dedos siguen
                                // encima, y entra en cuanto está.
                                if (cogida) {
                                    alcance.launch {
                                        val fina = withContext(Dispatchers.IO) {
                                            // **Si la foto está anotada, la nítida es el
                                            // dibujo, no la foto.**
                                            //
                                            // Pedir el archivo original al ampliar hacía
                                            // desaparecer lo dibujado justo al hacer
                                            // zoom: se veía la versión editada, y encima
                                            // entraba la original en grande y la tapaba.
                                            // Lo anotado vive en el dibujo, así que la
                                            // versión grande hay que componerla, no
                                            // leerla.
                                            val elDibujo = dibujo?.let { id ->
                                                com.forge.pixpin.motor.ExcalidrawStore
                                                    .rutaDe(this@MensajesActivity, id)
                                            }
                                            if (elDibujo != null && File(elDibujo).exists()) {
                                                runCatching {
                                                    val escena = com.forge.pixpin.motor
                                                        .ExcalidrawStore.cargar(elDibujo)
                                                        ?: return@runCatching null
                                                    com.forge.pixpin.motor.DrawExport.aBitmap(
                                                        escena, ESCALA_DE_LA_FOTO_AMPLIADA
                                                    ) { id ->
                                                        escena.files[id]?.path?.let {
                                                            com.forge.pixpin.pin.ImageStore
                                                                .load(it)
                                                        }
                                                    }
                                                }.getOrNull()
                                            } else {
                                                m.ruta?.let {
                                                    com.forge.pixpin.pin.ImageStore.load(
                                                        it, ANCHO_AMPLIADO
                                                    )
                                                }
                                            }
                                        }
                                        fina?.let { ampliada.afinar(it.asImageBitmap()) }
                                    }
                                }
                                cogida
                            },
                            alMover = { zoom, pan, giro, foco ->
                                ampliada.mover(zoom, pan, giro, foco)
                            },
                            alSoltar = { alcance.launch { ampliada.soltar() } }
                        )
                    )
            )
            if (conHoraEncima) {
                // Píldora al 60 % de negro, radio 8, a 6 puntos del borde
                // (`ChatMessageCell.java:23796-23819`). El negro translúcido es lo
                // único que se lee tanto sobre un cielo como sobre una sombra.
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(
                            androidx.compose.ui.graphics.Color.Black.copy(
                                alpha = ALFA_DE_LA_PILDORA
                            ),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // La etiqueta va también aquí: en una foto sola no hay renglón de
                    // hora dentro de la burbuja —la burbuja no existe— y el emoji se
                    // quedaba sin sitio donde salir, o sea, invisible justo en lo que más
                    // se etiqueta.
                    m.emoji?.let {
                        Text(it, fontSize = 11.sp)
                        Spacer(Modifier.size(3.dp))
                    }
                    if (m.fijado) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = androidx.compose.ui.graphics.Color.White
                        )
                        Spacer(Modifier.size(3.dp))
                    }
                    Text(
                        horaDe(m.cuando),
                        fontSize = TAMANO_DE_LA_HORA,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }
    }

    /**
     * Un enlace, con lo que se puede saber de él **sin salir a la red**.
     *
     * PixPin no pide permiso de internet, así que aquí no hay título de la página ni
     * imagen: hay lo que la propia dirección dice, que para reconocer un enlace suele
     * bastar. Ver [Enlace] para el razonamiento entero.
     */
    @Composable
    private fun TarjetaDeEnlace(enlace: Enlace) {
        val acento = MaterialTheme.colorScheme.primary
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 4.dp, bottomStart = 4.dp,
                        topEnd = 10.dp, bottomEnd = 4.dp
                    )
                )
                .background(acento.copy(alpha = ALFA_DE_LA_CITA))
                .clickable { abrirEnlace(enlace.url) }
                .height(IntrinsicSize.Min)
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(acento))
            Row(
                Modifier.padding(start = 10.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = null,
                    tint = acento,
                    modifier = Modifier.size(18.dp)
                )
                Column(Modifier.padding(start = 8.dp)) {
                    Text(
                        enlace.donde,
                        fontSize = 13.sp,
                        color = acento,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (enlace.deQue.isNotBlank()) {
                        Text(
                            enlace.deQue,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    /** Abre un enlace fuera. Si no hay con qué abrirlo, se dice en vez de no hacer nada. */
    private fun abrirEnlace(url: String) {
        val abierto = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.isSuccess
        if (!abierto) {
            Toast.makeText(
                this, com.forge.pixpin.R.string.guardados_ya_no_esta, Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * El sello de un mensaje: una miniatura cuadrada de lo que lleva dentro.
     *
     * Es lo que Telegram pone en la cita, en la barra de responder y en la de fijados
     * (`ChatMessageCell.java:22615`), y sirve para lo mismo en los tres sitios: **una
     * línea de texto no distingue dos fotos**. «IMG_20260817.jpg» e «IMG_20260818.jpg»
     * son la misma frase para el que mira; sus miniaturas, no.
     *
     * Se compone pequeña, fuera del hilo de la pantalla, y **solo para lo que tiene algo
     * que enseñar**: una nota no lleva sello, y ponerle un icono genérico llenaría la
     * cita de cuadrados grises que no dicen nada.
     */
    @Composable
    private fun SelloDelMensaje(m: Mensaje, lado: androidx.compose.ui.unit.Dp = 34.dp) {
        val ancho = with(androidx.compose.ui.platform.LocalDensity.current) {
            (lado.toPx() * 2).toInt()
        }
        var sello by remember(m.id, m.referencia) {
            mutableStateOf<android.graphics.Bitmap?>(null)
        }
        LaunchedEffect(m.id, m.referencia, ancho) {
            sello = withContext(Dispatchers.IO) {
                runCatching {
                    val dibujo = m.referencia?.let {
                        com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this@MensajesActivity, it)
                    }
                    when {
                        m.clase == Clase.PAGINA && m.ruta != null && m.pagina != null ->
                            paginaAnotada(
                                this@MensajesActivity, m.ruta!!, m.pagina!!,
                                m.referencia, dibujo, ancho
                            )
                        // Anotada, el sello enseña **lo anotado**: un sello de la foto
                        // limpia no sería el mensaje del que se está hablando.
                        dibujo != null && File(dibujo).exists() ->
                            com.forge.pixpin.motor.ExcalidrawStore.cargar(dibujo)?.let { e ->
                                com.forge.pixpin.motor.DrawExport.aBitmap(
                                    e, ESCALA_DEL_SELLO
                                ) { id ->
                                    e.files[id]?.path?.let {
                                        com.forge.pixpin.pin.ImageStore.load(it)
                                    }
                                }
                            }
                        m.clase == Clase.IMAGEN && m.ruta != null ->
                            com.forge.pixpin.pin.ImageStore.load(m.ruta!!, ancho)
                        else -> null
                    }
                }.getOrNull()
            }
        }
        sello?.let {
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(lado)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }

    /** La hoja de un PDF con lo anotado encima, tal y como quedó. */
    @Composable
    private fun MiniaturaDePagina(
        m: Mensaje,
        recarga: Int,
        ampliada: com.forge.pixpin.ui.ZoomDeHoja?
    ) {
        val pdf = m.ruta
        val pagina = m.pagina
        if (pdf == null || pagina == null) {
            FilaDeArchivo(m)
            return
        }
        val rutaDelDibujo = remember(m.referencia) {
            m.referencia?.let { com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, it) }
        }
        // La fecha del dibujo entra en la clave: al volver del editor, la hoja se
        // vuelve a componer con lo que se acaba de anotar en vez de con lo de antes.
        val version = remember(rutaDelDibujo, recarga) {
            rutaDelDibujo?.let { File(it).lastModified() } ?: 0L
        }
        var mapa by remember(pdf, pagina) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(pdf, pagina, version) {
            mapa = withContext(Dispatchers.IO) {
                runCatching {
                    paginaAnotada(
                        this@MensajesActivity, pdf, pagina, m.referencia,
                        rutaDelDibujo, ANCHO_DE_LA_HOJA
                    )
                }.getOrNull()
            }
        }
        val hoja = mapa
        if (hoja == null) {
            // Mientras se compone se enseña la ficha del archivo, que ya dice de qué
            // documento y de qué página se trata: un hueco gris no diría ni eso.
            FilaDeArchivo(m)
            return
        }
        var hueco by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        val ventana = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        val alcance = androidx.compose.runtime.rememberCoroutineScope()
        Column {
            androidx.compose.foundation.Image(
                bitmap = hoja.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .heightIn(max = ALTO_DE_LA_HOJA)
                    .clip(RoundedCornerShape(4.dp))
                    .onGloballyPositioned { hueco = it.boundsInWindow() }
                    .then(
                        if (ampliada == null) Modifier
                        else Modifier.pinzaParaAmpliar(
                            alCoger = {
                                val cogida = ampliada.coger(hoja.asImageBitmap(), hueco, ventana)
                                if (cogida) {
                                    // Lo mismo con la página: se recompone en grande, con
                                    // lo anotado encima, para poder leer lo que pone.
                                    alcance.launch {
                                        val fina = withContext(Dispatchers.IO) {
                                            runCatching {
                                                paginaAnotada(
                                                    this@MensajesActivity, pdf, pagina,
                                                    m.referencia, rutaDelDibujo,
                                                    ANCHO_AMPLIADO
                                                )
                                            }.getOrNull()
                                        }
                                        fina?.let { ampliada.afinar(it.asImageBitmap()) }
                                    }
                                }
                                cogida
                            },
                            alMover = { zoom, pan, giro, foco -> ampliada.mover(zoom, pan, giro, foco) },
                            alSoltar = { alcance.launch { ampliada.soltar() } }
                        )
                    )
            )
            Text(
                listOfNotNull(
                    m.nombre.ifBlank { null },
                    "pág. ${pagina + 1}"
                ).joinToString(" · "),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    /**
     * Una mini-app en la conversación: su nombre y **por dónde va**.
     *
     * Lo importante es la segunda línea. Una lista de tareas que solo dice «La compra»
     * obliga a abrirla para saber si queda algo, y entonces deja de servir de recordatorio
     * al pasar por delante; con «3 de 7» y su barrita, la conversación ya lo cuenta. En
     * los gastos, ese sitio lo ocupa el total, que es el número por el que existen.
     */
    @Composable
    private fun FilaDeMiniApp(m: Mensaje, onCambiar: (String) -> Unit) {
        val cual = remember(m.miniapp) { com.forge.pixpin.mini.MiniApp.de(m.miniapp) }
        if (cual == null) {
            // De una versión posterior: se enseña como texto, que es lo que es, en vez de
            // tirar la conversación por no reconocer una palabra.
            Text(m.texto, fontSize = 14.sp)
            return
        }
        val idioma = resources.configuration.locales[0] ?: java.util.Locale.getDefault()
        val titulo = remember(m.texto) {
            com.forge.pixpin.mini.Cabecera.titulo(m.texto).ifBlank { getString(cual.nombre) }
        }
        Column(Modifier.width(IntrinsicSize.Max)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (cual) {
                        com.forge.pixpin.mini.MiniApp.GASTOS -> Icons.Filled.Payments
                        com.forge.pixpin.mini.MiniApp.CRONOMETRO -> Icons.Filled.Timer
                        com.forge.pixpin.mini.MiniApp.TEMPORIZADOR -> Icons.Filled.HourglassEmpty
                        com.forge.pixpin.mini.MiniApp.ALARMA -> Icons.Filled.Alarm
                        com.forge.pixpin.mini.MiniApp.CONTADOR -> Icons.Filled.Tag
                        com.forge.pixpin.mini.MiniApp.RULETA -> Icons.Filled.Casino
                        com.forge.pixpin.mini.MiniApp.TAREAS -> Icons.Filled.Checklist
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    titulo,
                    fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            // **Entera, y no un resumen.**
            //
            // Una lista de la compra que hay que abrir para ver qué falta no sirve en la
            // conversación: lo que se hace al pasar por delante es mirarla y tachar lo
            // que ya se tiene. Por eso las tareas se marcan **aquí mismo**, sin abrir
            // nada, y los gastos enseñan sus líneas y su total como se ven en el pin.
            when (cual) {
                com.forge.pixpin.mini.MiniApp.TAREAS -> {
                    val tareas = remember(m.texto) { com.forge.pixpin.mini.Tareas.leer(m.texto) }
                    tareas.take(FILAS_DE_LA_MINIAPP).forEachIndexed { i, tarea ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCambiar(
                                        com.forge.pixpin.mini.Tareas.escribir(
                                            com.forge.pixpin.mini.Cabecera.titulo(m.texto),
                                            com.forge.pixpin.mini.Tareas.alternar(tareas, i)
                                        )
                                    )
                                }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (tarea.hecha) Icons.Filled.CheckBox
                                else Icons.Filled.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = if (tarea.hecha) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                            Text(
                                tarea.texto,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textDecoration = if (tarea.hecha) {
                                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                                } else null,
                                color = if (tarea.hecha) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                    Sobrantes(tareas.size)
                }
                // Los tres de tiempo enseñan su número en grande, que es todo lo que
                // tienen que decir de un vistazo: lo que lleva el cronómetro, lo que
                // falta del temporizador, a qué hora suena la alarma.
                // El contador enseña su número, que es la mini-app entera.
                com.forge.pixpin.mini.MiniApp.CONTADOR -> {
                    val c = remember(m.texto) { com.forge.pixpin.mini.Contador.leer(m.texto) }
                    Text(
                        c.valor.toString(),
                        fontSize = 26.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Y la ruleta, a quién le puede tocar: los nombres son lo que hay que
                // repasar antes de girar, y caben de sobra en una burbuja.
                com.forge.pixpin.mini.MiniApp.RULETA -> {
                    val nombres = remember(m.texto) {
                        com.forge.pixpin.mini.RuletaDoc.leer(m.texto)
                    }
                    nombres.take(FILAS_DE_LA_MINIAPP).forEach { nombre ->
                        Text(nombre, fontSize = 14.sp, maxLines = 1)
                    }
                    Sobrantes(nombres.size)
                    if (nombres.isEmpty()) {
                        Text(
                            getString(com.forge.pixpin.R.string.miniapp_sin_nada),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                com.forge.pixpin.mini.MiniApp.CRONOMETRO,
                com.forge.pixpin.mini.MiniApp.TEMPORIZADOR,
                com.forge.pixpin.mini.MiniApp.ALARMA -> {
                    // **Corriendo, se ve correr aquí mismo.**
                    //
                    // Un temporizador que en la conversación enseña el número con el que
                    // nació no dice nada: lo que uno quiere saber al mirar el chat es
                    // cuánto le falta. Se refresca **una vez por segundo y solo mientras
                    // corre**; parado no gasta ni un repintado, que es la diferencia
                    // entre un reloj en una lista y una lista con un reloj dentro.
                    val cronometro = remember(m.texto) {
                        if (cual == com.forge.pixpin.mini.MiniApp.CRONOMETRO) {
                            com.forge.pixpin.mini.Tiempos.leerCronometro(m.texto)
                        } else null
                    }
                    val temporizador = remember(m.texto) {
                        if (cual == com.forge.pixpin.mini.MiniApp.TEMPORIZADOR) {
                            com.forge.pixpin.mini.Tiempos.leerTemporizador(m.texto)
                        } else null
                    }
                    val enMarcha = cronometro?.corriendo == true || temporizador?.corriendo == true
                    var ahora by remember(m.id) {
                        androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())
                    }
                    LaunchedEffect(enMarcha) {
                        while (enMarcha) {
                            ahora = System.currentTimeMillis()
                            delay(1000)
                        }
                    }
                    val seAcabo = temporizador?.vencido(ahora) == true
                    val texto = when {
                        cronometro != null ->
                            com.forge.pixpin.mini.Tiempos.comoSeLeeCorto(
                                cronometro.transcurrido(ahora)
                            )
                        temporizador != null ->
                            com.forge.pixpin.mini.Tiempos.comoSeLeeCorto(
                                temporizador.restante(ahora)
                            )
                        else -> cual.resumen(m.texto, idioma).texto
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            texto,
                            fontSize = 26.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = when {
                                seAcabo -> MaterialTheme.colorScheme.error
                                enMarcha -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        // El punto que late dice «esto sigue andando» sin leer el número.
                        if (enMarcha) {
                            Box(
                                Modifier
                                    .padding(start = 8.dp)
                                    .size(8.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                        }
                    }
                }
                com.forge.pixpin.mini.MiniApp.GASTOS -> {
                    val libro = remember(m.texto, idioma) {
                        com.forge.pixpin.mini.Gastos.leer(m.texto, idioma)
                    }
                    libro.gastos.take(FILAS_DE_LA_MINIAPP).forEach { g ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                g.concepto,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                com.forge.pixpin.mini.Gastos.textoDeImporte(
                                    g.centimos, libro.moneda, idioma
                                ),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                    Sobrantes(libro.gastos.size)
                    if (libro.gastos.isNotEmpty()) {
                        androidx.compose.material3.HorizontalDivider(
                            Modifier.padding(vertical = 4.dp)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(getString(com.forge.pixpin.R.string.miniapp_total), fontSize = 14.sp)
                        Text(
                            com.forge.pixpin.mini.Gastos.textoDeImporte(
                                libro.total, libro.moneda, idioma
                            ),
                            fontSize = 15.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        }
    }

    /**
     * «Y otras cuatro», cuando la mini-app no cabe entera en la burbuja.
     *
     * Con una lista de cuarenta líneas, enseñarlas todas convierte la conversación en un
     * documento por el que hay que desplazarse para llegar al mensaje siguiente. Se
     * enseña lo que cabe de un vistazo y se dice cuánto queda, que es lo que decide si
     * hace falta abrirla.
     */
    @Composable
    private fun Sobrantes(cuantas: Int) {
        val fuera = cuantas - FILAS_DE_LA_MINIAPP
        if (fuera <= 0) return
        Text(
            resources.getQuantityString(
                com.forge.pixpin.R.plurals.miniapp_y_mas, fuera, fuera
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }

    @Composable
    private fun FilaDeVoz(m: Mensaje) {
        // **Una nota de voz de verdad: botón, onda y por dónde va.**
        //
        // Antes era un icono de micrófono y una duración, que es tanto como enseñar el
        // nombre de un fichero: no se sabe si son tres palabras o dos minutos de
        // divagación, y sobre todo no se sabe **por dónde va** al escucharla, que es lo
        // que uno mira para decidir si aguanta hasta el final o se salta el principio.
        //
        // Los números son los de Telegram (`SeekBarWaveform.java:385, :418-424`):
        // barras de 2 puntos cada 3, sobre una franja de 14, y el botón redondo de 44.
        val activo = sonando == m.ruta
        var avance by remember(m.id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
        // Solo se pregunta por la posición **mientras suena esta**: un bucle por fila
        // en una lista de cien notas sería cien relojes despertando a la vez.
        LaunchedEffect(activo) {
            while (activo) {
                avance = porDondeVa()
                delay(LATIDO_DE_LA_ONDA)
            }
            if (!activo) avance = 0f
        }
        val barras = remember(m.picos) { aBarras(m.picos) }
        val acento = MaterialTheme.colorScheme.primary
        val apagado = acento.copy(alpha = ALFA_DE_LO_NO_OIDO)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(BOTON_DEL_ARCHIVO)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        androidx.compose.foundation.shape.CircleShape
                    )
                    .clickable { m.ruta?.let { sonar(it) } },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (activo) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = getString(
                        if (activo) com.forge.pixpin.R.string.voz_pausar
                        else com.forge.pixpin.R.string.voz_oir
                    ),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(Modifier.padding(start = 10.dp)) {
                if (barras.isEmpty()) {
                    // Las notas grabadas antes de guardar los picos no tienen onda. Una
                    // onda inventada mentiría sobre lo que se dijo, así que no se pinta.
                    Spacer(Modifier.size(4.dp))
                } else {
                    androidx.compose.foundation.Canvas(
                        Modifier
                            .width(ANCHO_DE_LA_ONDA)
                            .height(ALTO_DE_LA_ONDA)
                            // Tocar la onda salta a ese punto, que es para lo que uno
                            // mira una onda: para volver al trozo que importa.
                            .pointerInput(m.id) {
                                detectTapGestures { d ->
                                    if (activo) {
                                        val f = d.x / size.width
                                        saltarA(f)
                                        avance = f.coerceIn(0f, 1f)
                                    } else m.ruta?.let { sonar(it) }
                                }
                            }
                    ) {
                        val paso = size.width / barras.size
                        val grueso = paso * PARTE_LLENA_DE_LA_BARRA
                        barras.forEachIndexed { i, valor ->
                            val alto = (size.height * valor).coerceAtLeast(grueso)
                            drawRoundRect(
                                color = if (i.toFloat() / barras.size <= avance) acento
                                        else apagado,
                                topLeft = androidx.compose.ui.geometry.Offset(
                                    i * paso, (size.height - alto) / 2f
                                ),
                                size = androidx.compose.ui.geometry.Size(grueso, alto),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                    grueso / 2f
                                )
                            )
                        }
                    }
                }
                Text(
                    duracionLegible(
                        if (activo && m.duracionMs > 0) {
                            (m.duracionMs * (1f - avance)).toInt()
                        } else m.duracionMs
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    @Composable
    private fun FilaDeArchivo(m: Mensaje) {
        // **El botón redondo de 44 puntos** (`ChatMessageCell.java:13996`). No es
        // adorno: es lo que da un blanco al dedo y lo que separa de un vistazo un
        // archivo de una nota larga en una lista de cien cosas. Un icono suelto de 24
        // no se distingue del texto que tiene al lado.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(BOTON_DEL_ARCHIVO)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when (m.clase) {
                        Clase.PROYECTO -> Icons.Filled.Folder
                        Clase.PAGINA -> Icons.AutoMirrored.Filled.MenuBook
                        else -> Icons.Filled.Description
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(Modifier.padding(start = 10.dp)) {
                // Dos líneas y recorte por el medio: los nombres de archivo se
                // distinguen por el final («…informe_v3_FINAL.pdf»), y cortarlos ahí
                // deja todos los de una carpeta con el mismo aspecto.
                Text(
                    m.nombre.ifBlank { "Archivo" },
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis
                )
                // Tamaño y **extensión en mayúsculas**, como Telegram
                // (`ChatMessageCell.java:12847`): «1,2 MB PDF» dice de un golpe qué es
                // y cuánto pesa, que es justo lo que se pregunta antes de tocarlo.
                val detalle = listOfNotNull(
                    tamanoLegible(m.bytes).ifBlank { null },
                    extensionDe(m.nombre),
                    m.pagina?.let { "pág. ${it + 1}" }
                ).joinToString(" ")
                if (detalle.isNotBlank()) {
                    Text(
                        detalle,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun Vacio(seccion: Seccion, buscando: Boolean) {
        // **Esto es ahora lo primero que ve alguien que acaba de instalar la app**, así
        // que una línea gris en medio de la nada no basta: no dice qué es esta pantalla
        // ni qué se supone que hay que hacer aquí. Con un icono grande, una frase de
        // qué es y otra de por dónde empezar, la pantalla vacía enseña en vez de callar.
        //
        // Buscando sin resultados es otra cosa: ahí sí se sabe qué es la pantalla y lo
        // único que hace falta es decir que no hay nada, sin lecciones.
        Column(
            Modifier.fillMaxWidth().padding(top = 60.dp, start = 40.dp, end = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (buscando) {
                Text(
                    getString(com.forge.pixpin.R.string.guardados_sin_resultados),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return
            }
            Box(
                Modifier
                    .size(84.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (seccion == Seccion.BUZON) Icons.Filled.Inbox
                    else Icons.Filled.BookmarkBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Text(
                getString(
                    when (seccion) {
                        Seccion.TODO -> com.forge.pixpin.R.string.guardados_vacio
                        Seccion.BUZON -> com.forge.pixpin.R.string.guardados_buzon_vacio
                        else -> com.forge.pixpin.R.string.guardados_seccion_vacia
                    }
                ),
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp)
            )
            if (seccion == Seccion.TODO || seccion == Seccion.BUZON) {
                Text(
                    getString(
                        if (seccion == Seccion.BUZON) {
                            com.forge.pixpin.R.string.guardados_buzon_aviso
                        } else com.forge.pixpin.R.string.guardados_vacio_pista
                    ),
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    /** Escribir, adjuntar y grabar. Los tres gestos de mandarse algo a uno mismo. */
    @Composable
    private fun BarraDeEscribir(
        escrito: String,
        grabando: Boolean,
        onEscrito: (String) -> Unit,
        onAdjuntar: () -> Unit,
        onEmpezarVoz: () -> Boolean,
        onSoltarVoz: (guardar: Boolean) -> Unit,
        onMandar: () -> Unit
    ) {
        val hayTexto = escrito.isNotBlank()
        // **Grabar es mantener pulsado, no dos toques.**
        //
        // Con dos toques hay que acertar dos veces y, sobre todo, hay que acordarse de
        // parar: quien se distrae deja el micrófono abierto. Manteniendo, la nota dura
        // exactamente lo que dura el dedo encima, que es lo que uno espera.
        //
        // Y con dos salidas, las dos de Telegram: **arrastrar a la izquierda tira** lo
        // grabado —el recorrido es `min(ancho×0,35, 140)` (`:3137-3139`) y al soltar por
        // debajo del 45 % se descarta (`:3051`)— y **subir 57 puntos lo deja fijo**
        // (`:2106`), para poder seguir hablando sin el dedo puesto.
        var fijado by remember { mutableStateOf(false) }
        var avanceDeCancelar by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
        val densidad = androidx.compose.ui.platform.LocalDensity.current
        val ventana = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        val vibrar = androidx.compose.ui.platform.LocalHapticFeedback.current
        val recorridoParaTirar = remember(ventana) {
            minOf(ventana.width * PARTE_DEL_ANCHO_PARA_TIRAR,
                  with(densidad) { RECORRIDO_MAXIMO_PARA_TIRAR.toPx() })
        }
        val subidaParaFijar = with(densidad) { SUBIDA_PARA_FIJAR.toPx() }
        Surface(shadowElevation = 8.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // **El campo es una píldora, no un formulario.**
                //
                // El `TextField` de Material trae subrayado y unos 16 puntos de relleno
                // interno: en una barra de escribir eso son catorce puntos de alto
                // regalados y una raya que grita «rellene aquí». Telegram usa un campo
                // pelado dentro de un fondo redondeado, con 18 de letra, sin relleno de
                // fuente y pegado abajo (`ChatActivityEnterView.java:5750-5763`).
                Surface(
                    shape = RoundedCornerShape(ESQUINA_DEL_CAMPO),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f)
                ) {
                    if (grabando) {
                        // **Mientras se graba, el campo dice lo que está pasando.**
                        //
                        // Un micrófono en rojo y nada más deja dos preguntas sin
                        // responder: cuánto llevo, y cómo salgo de aquí. El punto que
                        // late, el reloj y el aviso de deslizar contestan a las dos, y
                        // el aviso **se va apagando conforme arrastras**, que es la
                        // forma de decir «un poco más y lo tiro» sin escribirlo.
                        var segundos by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                        LaunchedEffect(Unit) {
                            while (true) { delay(1000); segundos++ }
                        }
                        val late by androidx.compose.animation.core.rememberInfiniteTransition(
                            label = "punto"
                        ).animateFloat(
                            initialValue = 1f,
                            targetValue = 0.2f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                androidx.compose.animation.core.tween(LATIDO_DEL_PUNTO),
                                androidx.compose.animation.core.RepeatMode.Reverse
                            ),
                            label = "punto"
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = FILA_DE_LA_BARRA)
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .graphicsLayer { alpha = late }
                                    .background(
                                        MaterialTheme.colorScheme.error,
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                            Text(
                                duracionLegible(segundos * 1000),
                                fontSize = 15.sp,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                            Text(
                                getString(
                                    if (fijado) com.forge.pixpin.R.string.guardados_toca_mandar
                                    else com.forge.pixpin.R.string.guardados_desliza_cancelar
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                                    .graphicsLayer { alpha = avanceDeCancelar }
                            )
                            if (fijado) {
                                androidx.compose.material3.TextButton(onClick = {
                                    fijado = false
                                    onSoltarVoz(false)
                                }) {
                                    Text(
                                        getString(com.forge.pixpin.R.string.cancel),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        return@Surface
                    }
                    Row(
                        Modifier.heightIn(min = FILA_DE_LA_BARRA),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = escrito,
                            onValueChange = onEscrito,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                    includeFontPadding = false
                                )
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                MaterialTheme.colorScheme.primary
                            ),
                            // Seis renglones y a partir de ahí se desplaza dentro: es el
                            // tope de Telegram (`:5754`). Sin tope, pegar tres párrafos
                            // tapa la conversación entera con lo que estás escribiendo.
                            maxLines = 6,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 14.dp, top = 9.dp, bottom = 10.dp),
                            decorationBox = { campo ->
                                if (escrito.isEmpty()) {
                                    Text(
                                        getString(com.forge.pixpin.R.string.guardados_escribe),
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                campo()
                            }
                        )
                        // **El clip va dentro del campo y a la derecha** (`:2794`), y se
                        // retira al escribir para dejarle el sitio al texto. Es lo que
                        // hace que la barra parezca responder a lo que uno hace, en vez
                        // de ser una fila de botones fijos alrededor de un hueco.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !hayTexto,
                            enter = androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(RETIRADA_DEL_CLIP)
                            ) + androidx.compose.animation.scaleIn(
                                androidx.compose.animation.core.tween(RETIRADA_DEL_CLIP),
                                initialScale = 0.5f
                            ),
                            exit = androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(RETIRADA_DEL_CLIP)
                            ) + androidx.compose.animation.scaleOut(
                                androidx.compose.animation.core.tween(RETIRADA_DEL_CLIP),
                                targetScale = 0.5f
                            )
                        ) {
                            IconButton(
                                onClick = onAdjuntar,
                                modifier = Modifier.size(FILA_DE_LA_BARRA)
                            ) {
                                Icon(
                                    Icons.Filled.AttachFile,
                                    contentDescription = getString(
                                        com.forge.pixpin.R.string.guardados_adjuntar
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                // **Un sitio, dos caras.** El botón no se mueve nunca: crece el que
                // entra desde 0,1 y se encoge el que sale. Y los tiempos no son
                // simétricos —220 ms al aparecer, 150 al irse (`:8187-8188`, `:8604`)—
                // porque aparecer tiene que sentirse suave y desaparecer, inmediato.
                Box(
                    Modifier.size(FILA_DE_LA_BARRA + 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = hayTexto,
                        enter = androidx.compose.animation.scaleIn(
                            androidx.compose.animation.core.tween(
                                ENTRADA_DEL_BOTON, easing = SALIDA_SUAVE
                            ),
                            initialScale = 0.1f
                        ) + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.scaleOut(
                            androidx.compose.animation.core.tween(SALIDA_DEL_BOTON),
                            targetScale = 0.1f
                        ) + androidx.compose.animation.fadeOut()
                    ) {
                        IconButton(onClick = onMandar) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = getString(
                                    com.forge.pixpin.R.string.guardados_mandar
                                ),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !hayTexto,
                        enter = androidx.compose.animation.scaleIn(
                            androidx.compose.animation.core.tween(
                                ENTRADA_DEL_BOTON, easing = SALIDA_SUAVE
                            ),
                            initialScale = 0.1f
                        ) + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.scaleOut(
                            androidx.compose.animation.core.tween(SALIDA_DEL_BOTON),
                            targetScale = 0.1f
                        ) + androidx.compose.animation.fadeOut()
                    ) {
                        Box(
                            Modifier
                                .size(FILA_DE_LA_BARRA)
                                .pointerInput(fijado) {
                                    // Fijada, el botón vuelve a ser un botón: se toca
                                    // para mandar lo que se lleva grabado.
                                    if (fijado) {
                                        detectTapGestures {
                                            fijado = false
                                            onSoltarVoz(true)
                                        }
                                        return@pointerInput
                                    }
                                    awaitEachGesture {
                                        val abajo = awaitFirstDown()
                                        if (!onEmpezarVoz()) return@awaitEachGesture
                                        vibrar.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback
                                                .HapticFeedbackType.LongPress
                                        )
                                        avanceDeCancelar = 1f
                                        var tirar = false
                                        while (true) {
                                            val evento = awaitPointerEvent()
                                            val dedo = evento.changes.firstOrNull()
                                                ?: break
                                            if (!dedo.pressed) break
                                            dedo.consume()
                                            val dx = dedo.position.x - abajo.position.x
                                            val dy = dedo.position.y - abajo.position.y
                                            avanceDeCancelar =
                                                (1f + dx / recorridoParaTirar)
                                                    .coerceIn(0f, 1f)
                                            if (avanceDeCancelar <= 0f) {
                                                tirar = true
                                                break
                                            }
                                            if (-dy >= subidaParaFijar) {
                                                fijado = true
                                                vibrar.performHapticFeedback(
                                                    androidx.compose.ui.hapticfeedback
                                                        .HapticFeedbackType.LongPress
                                                )
                                                return@awaitEachGesture
                                            }
                                        }
                                        onSoltarVoz(
                                            !tirar && avanceDeCancelar >= MINIMO_PARA_MANDAR
                                        )
                                        avanceDeCancelar = 1f
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (grabando && fijado) Icons.Filled.Stop
                                else Icons.Filled.Mic,
                                contentDescription = getString(
                                    com.forge.pixpin.R.string.voz_grabar
                                ),
                                tint = if (grabando) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BotonDeAdjuntar(
        icono: androidx.compose.ui.graphics.vector.ImageVector,
        texto: Int,
        ancho: androidx.compose.ui.unit.Dp = 96.dp,
        onClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(ancho).clickable(onClick = onClick)
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icono,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                getString(texto),
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }

    /**
     * Una línea del menú de un mensaje.
     *
     * **Con icono y con nombre.** El nombre es lo que hace falta para lo que no se usa a
     * diario —«fijar» y «sacar a la pantalla» son dos cosas distintas y con dibujitos no
     * se distinguen—, pero el icono es lo que permite encontrar la línea de siempre sin
     * leerse las nueve: a la tercera vez la mano va al dibujo, no a la palabra.
     *
     * Borrar va en rojo, icono incluido: es la única que no se deshace.
     */
    @Composable
    private fun DelMenu(
        texto: Int,
        icono: androidx.compose.ui.graphics.vector.ImageVector? = null,
        peligro: Boolean = false,
        onClick: () -> Unit
    ) {
        val color = if (peligro) MaterialTheme.colorScheme.error
        else androidx.compose.ui.graphics.Color.Unspecified
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(getString(texto), color = color) },
            leadingIcon = icono?.let {
                {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = if (peligro) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            onClick = onClick
        )
    }

    @Composable
    private fun Opcion(texto: Int, onClick: () -> Unit) {
        androidx.compose.material3.TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(getString(texto), modifier = Modifier.fillMaxWidth(), fontSize = 15.sp)
        }
    }

    /**
     * En qué fila de la lista cae un mensaje, contando los separadores de día.
     *
     * La lista no es «un elemento por mensaje»: cada día mete una fila más. Sin contarlos,
     * saltar a una cita lleva a un sitio cada vez más equivocado cuanto más abajo esté.
     */
    private fun filaDe(tramos: List<Tramo>, id: String): Int {
        var fila = 0
        for (tramo in tramos) {
            fila++ // el separador del día
            for (m in tramo.mensajes) {
                if (m.id == id) return fila
                fila++
            }
        }
        return -1
    }

    /**
     * De qué día es la fila que está arriba del todo.
     *
     * Se recorre igual que [filaDe] pero al revés, contando los separadores: la lista no
     * es «un elemento por mensaje», y sin contarlos la pastilla enseñaría el día
     * equivocado justo donde más se nota, que es al final de una conversación larga.
     */
    private fun cuandoDeLaFila(tramos: List<Tramo>, fila: Int): Long? {
        var n = 0
        for (tramo in tramos) {
            if (fila <= n) return tramo.mensajes.firstOrNull()?.cuando
            n++
            for (m in tramo.mensajes) {
                if (fila <= n) return m.cuando
                n++
            }
        }
        return tramos.lastOrNull()?.mensajes?.lastOrNull()?.cuando
    }

    // ---- Acciones --------------------------------------------------------

    private fun cargar(): List<Mensaje> {
        val todos = almacen.leer()
        // El buzón se limpia **al entrar**, que es cuando toca: hacerlo con un
        // temporizador obligaría a mantener algo despierto para borrar archivos.
        val fuera = caducados(todos, System.currentTimeMillis())
        if (fuera.isEmpty()) return todos
        fuera.forEach { almacen.borrarAdjunto(it.ruta) }
        val quedan = todos - fuera.toSet()
        almacen.reescribir(quedan)
        return quedan
    }

    /**
     * Guarda un proyecto **como acceso directo**, no como copia.
     *
     * Lo único que se apunta es su identificador y su nombre: al tocarlo se abre el
     * proyecto de verdad, con lo que tenga en ese momento. Una copia se habría quedado
     * congelada el día que se guardó, y sería mentira desde el día siguiente.
     */
    private fun guardarProyecto(p: com.forge.pixpin.motor.Proyecto) {
        almacen.anadir(
            Mensaje(
                id = UUID.randomUUID().toString(),
                cuando = System.currentTimeMillis(),
                clase = Clase.PROYECTO,
                proyecto = chatDe,
                nombre = p.nombre,
                referencia = p.id
            )
        )
    }

    /** Y una página suelta, con lo justo para poder volver a ella: el PDF y el número. */
    private fun guardarPagina(p: com.forge.pixpin.motor.Proyecto, hoja: com.forge.pixpin.motor.Hoja) {
        val pagina = hoja.pagina ?: return
        almacen.anadir(
            Mensaje(
                id = UUID.randomUUID().toString(),
                cuando = System.currentTimeMillis(),
                clase = Clase.PAGINA,
                proyecto = chatDe,
                // **El número de página, en el propio nombre.**
                //
                // Adjuntando tres hojas del mismo documento salían tres mensajes que
                // decían exactamente lo mismo —el nombre del proyecto— y no había forma
                // de saber cuál era cuál sin abrirlas. El número va aquí y no solo debajo
                // porque es lo que viaja: se ve en la cita, en la lista de
                // conversaciones, al reenviarla y en el nombre del archivo al compartir.
                nombre = p.nombre + " · " +
                    getString(com.forge.pixpin.R.string.proyecto_pagina, pagina + 1),
                // **Por dónde se lee el documento, no por dónde se leía.** Si el
                // archivo original se movió o se perdió, la copia limpia sigue siendo
                // legible; con `pdfOrigen` a secas, la página adjunta apuntaba a un
                // hueco y en la conversación no salía ni la hoja ni nada.
                ruta = com.forge.pixpin.motor.Proyectos.rutaDelDocumento(p) {
                    File(it).exists()
                },
                pagina = pagina,
                // El dibujo de la hoja: es lo que hace que al abrirla salga **con lo que
                // ya habías anotado encima** y no la página en blanco.
                referencia = hoja.dibujo ?: "hoja-${p.id}-$pagina"
            )
        )
    }

    /**
     * Copia lo elegido con el clip y lo da de alta.
     *
     * **Fuera del hilo de la interfaz, y no es una precaución de manual**: el aviso del
     * selector de archivos llega al hilo principal, y aquí se copia el archivo entero.
     * Con un PDF de veinte megas eso es leer y escribir cuarenta con la pantalla
     * bloqueada, y si el archivo viene de una nube el `openInputStream` puede tardar
     * segundos: no es un tirón, es la aplicación muerta y el sistema ofreciendo cerrarla.
     */
    private fun guardarArchivo(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) { copiarLoElegido(uri) }
    }

    private suspend fun copiarLoElegido(uri: Uri) {
        runCatching {
            val nombre = nombreDe(uri)
            val temporal = File(cacheDir, "adj_${System.currentTimeMillis()}")
            contentResolver.openInputStream(uri)?.use { entrada ->
                temporal.outputStream().use { entrada.copyTo(it) }
            } ?: return
            val ruta = almacen.copiarAdjunto(temporal, nombre)
            val bytes = temporal.length()
            temporal.delete()
            if (ruta == null) return
            val esImagen = contentResolver.getType(uri)?.startsWith("image/") == true
            almacen.anadir(
                Mensaje(
                    id = UUID.randomUUID().toString(),
                    cuando = System.currentTimeMillis(),
                    clase = if (esImagen) Clase.IMAGEN else Clase.ARCHIVO,
                    proyecto = chatDe,
                    ruta = ruta,
                    nombre = nombre,
                    bytes = bytes
                )
            )
            withContext(Dispatchers.Main) { recargarLaLista?.invoke() }
        }
    }

    /**
     * Cómo se le dice a la pantalla que vuelva a leer.
     *
     * Lo pone la propia pantalla al componerse. Existe porque hay trabajo que acaba
     * fuera del hilo de la interfaz —copiar un archivo, reescribir el archivo de datos—
     * y desde ahí no se puede tocar el estado de Compose directamente.
     */
    private var recargarLaLista: (() -> Unit)? = null

    /**
     * Guarda la lista entera **sin bloquear el toque que la ha cambiado**.
     *
     * Reescribir es serializar todos los mensajes y volcarlos a disco; con quinientos,
     * eso son décimas de segundo. Hacerlo dentro del `onClick` de fijar o de borrar
     * dejaba la pantalla congelada justo en el gesto que tiene que sentirse instantáneo.
     */
    private fun guardarAparte(lista: List<Mensaje>, luego: () -> Unit = {}) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { almacen.reescribir(lista) }
            luego()
        }
    }

    private var grabador: android.media.MediaRecorder? = null
    private var destinoDeVoz: File? = null

    /**
     * Los picos del micrófono mientras se graba, para dibujar la onda después.
     *
     * Se anotan **al grabar** y no al abrir la lista: sacar la forma de un `.m4a`
     * obliga a descodificarlo entero, y hacer eso por cada nota visible dejaría el
     * desplazamiento a trompicones. Aquí sale gratis: ya se está grabando.
     */
    private var picosDeVoz: Picos? = null
    private var midiendoPicos: kotlinx.coroutines.Job? = null

    private fun empezarVoz(): Boolean {
        val tiene = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!tiene) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1
            )
            return false
        }
        val destino = File(almacen.carpetaDeAdjuntos(), "voz_${System.currentTimeMillis()}.m4a")
        destinoDeVoz = destino
        grabador = Voz.empezar(this, destino)
        if (grabador != null) {
            val picos = Picos()
            picosDeVoz = picos
            midiendoPicos = lifecycleScope.launch {
                while (true) {
                    picos.anota(Voz.pico(grabador))
                    delay(Voz.MS_ENTRE_PICOS)
                }
            }
        }
        return grabador != null
    }

    /**
     * Tira lo que se estaba grabando sin guardar nada.
     *
     * Es lo que pasa al deslizar el dedo a la izquierda mientras se graba: el fichero se
     * borra ahí mismo. Dejarlo en disco «por si acaso» llenaría el teléfono de restos
     * que nadie va a volver a oír.
     */
    private fun tirarVoz() {
        midiendoPicos?.cancel()
        midiendoPicos = null
        picosDeVoz = null
        Voz.parar(grabador)
        grabador = null
        destinoDeVoz?.delete()
        destinoDeVoz = null
    }

    private fun pararVoz() {
        midiendoPicos?.cancel()
        midiendoPicos = null
        val picos = picosDeVoz?.lista().orEmpty()
        picosDeVoz = null
        val bien = Voz.parar(grabador)
        grabador = null
        val archivo = destinoDeVoz
        destinoDeVoz = null
        if (!bien || archivo == null || !archivo.exists()) {
            archivo?.delete()
            return
        }
        val duracion = Voz.duracion(archivo.absolutePath)
        if (duracion < Voz.MINIMO_MS) {
            archivo.delete()
            Toast.makeText(this, com.forge.pixpin.R.string.voz_muy_corta, Toast.LENGTH_SHORT).show()
            return
        }
        almacen.anadir(
            Mensaje(
                id = UUID.randomUUID().toString(),
                cuando = System.currentTimeMillis(),
                clase = Clase.VOZ,
                ruta = archivo.absolutePath,
                duracionMs = duracion,
                picos = picos,
                proyecto = chatDe
            )
        )
    }

    /**
     * Crea una mini-app y la manda a la conversación.
     *
     * Nace vacía y con su nombre puesto, y se abre en el acto: quien acaba de pedir una
     * lista de la compra quiere escribir la primera línea, no mirar una burbuja vacía y
     * tener que tocarla otra vez.
     */
    private fun mandarMiniApp(cual: com.forge.pixpin.mini.MiniApp) {
        val idioma = resources.configuration.locales[0] ?: java.util.Locale.getDefault()
        val id = UUID.randomUUID().toString()
        almacen.anadir(
            Mensaje(
                id = id,
                cuando = System.currentTimeMillis(),
                clase = Clase.MINIAPP,
                texto = cual.documentoNuevo(getString(cual.nombre), idioma),
                nombre = getString(cual.nombre),
                miniapp = cual.id,
                proyecto = chatDe
            )
        )
        com.forge.pixpin.mini.MiniActivity.abrir(this, id)
    }

    /**
     * Abre la portada: los permisos, los ajustes y los proyectos.
     *
     * Sigue existiendo entera; lo que ha cambiado es que ya no es el peaje de cada
     * arranque. Se entra por el menú, o sola cuando falta algún permiso —ver
     * [comprobarLaPortada]—, porque sin el permiso de dibujar encima la mitad de la
     * aplicación no funciona y una conversación vacía no lo explicaría.
     */
    private fun abrirLaPortada(enProyectos: Boolean) {
        startActivity(
            Intent(this, com.forge.pixpin.MainActivity::class.java).apply {
                if (enProyectos) putExtra(com.forge.pixpin.EXTRA_PROYECTOS, true)
            }
        )
    }

    /**
     * Al abrir por primera vez sin permisos, se va derecho a la portada.
     *
     * Quien instala la aplicación y se encuentra un cajón vacío no tiene forma de
     * saber que le falta conceder nada, y los pines simplemente no aparecerían. Solo
     * se comprueba **el de dibujar encima** porque es el que impide que la aplicación
     * haga lo suyo; los demás se piden cuando toca.
     */
    private fun comprobarLaPortada() {
        if (yaMiroLosPermisos) return
        yaMiroLosPermisos = true
        if (!android.provider.Settings.canDrawOverlays(this)) abrirLaPortada(enProyectos = false)
    }

    private var yaMiroLosPermisos = false

    /** Saca el mensaje a la pantalla como pin: es el puente con el resto de PixPin. */
    /**
     * Saca cualquier mensaje a la pantalla como pin.
     *
     * **Cualquiera, sin excepciones.** Antes esto era un `when` con tres casos y un
     * «los demás, si tienen archivo»: el dibujo, el proyecto y las mini-apps no tienen
     * archivo, así que no pasaba nada de nada — y encima salía el aviso de «puesto en la
     * pantalla», que era mentira. Un botón que a veces no hace nada y siempre dice que
     * sí es peor que un botón que no está.
     *
     * Cada clase se saca como lo que es: el dibujo apuntando a su mismo archivo, la
     * página como la hoja anotada, el proyecto como su documento y la mini-app como su
     * texto. Y si de verdad no hay nada que sacar, se dice.
     */
    private fun pinear(m: Mensaje, avisar: Boolean = true) {
        val gestor = (application as? PixPinApp)?.overlayManager ?: return
        when (m.clase) {
            // **Anotada, se saca el dibujo y no la foto.**
            //
            // Sacando la foto original a la pantalla, lo dibujado encima desaparecía: uno
            // pone el pin justo para tener delante lo que señaló, y salía la captura
            // limpia. El dibujo lleva la foto dentro, así que sacándolo se ve todo — y
            // además se sigue pudiendo dibujar en él, que es el mismo archivo.
            Clase.IMAGEN -> {
                val dibujo = m.referencia?.let {
                    com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, it)
                }
                when {
                    dibujo != null && File(dibujo).exists() -> gestor.pinDibujo(dibujo)
                    m.ruta != null -> gestor.pinImage(m.ruta!!)
                    else -> avisarDeQueNoHay()
                }
            }
            Clase.VOZ -> m.ruta?.let { gestor.pinVoz(it) } ?: avisarDeQueNoHay()
            Clase.NOTA -> gestor.pinTexto(m.texto)

            // El dibujo va **al mismo archivo**: dibujar en el pin es dibujar en lo que
            // guarda la conversación, no en una copia que luego no se sabe cuál manda.
            Clase.DIBUJO -> m.referencia?.let { dibujo ->
                gestor.pinDibujo(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, dibujo))
            } ?: avisarDeQueNoHay()

            // Una página se saca **con lo anotado encima**, que es lo que uno quiere
            // tener delante mientras trabaja. Se compone fuera del hilo de la pantalla.
            Clase.PAGINA -> {
                val pdf = m.ruta
                val pagina = m.pagina
                if (pdf == null || pagina == null) return avisarDeQueNoHay()
                lifecycleScope.launch {
                    val archivo = withContext(Dispatchers.IO) {
                        val hoja = paginaAnotada(
                            this@MensajesActivity, pdf, pagina, m.referencia,
                            m.referencia?.let {
                                com.forge.pixpin.motor.ExcalidrawStore.rutaDe(
                                    this@MensajesActivity, it
                                )
                            },
                            ANCHO_DE_LA_HOJA
                        ) ?: return@withContext null
                        com.forge.pixpin.pin.ImageStore.saveBitmap(
                            this@MensajesActivity, hoja, "pagina_${System.currentTimeMillis()}"
                        )
                    }
                    if (archivo == null) avisarDeQueNoHay() else gestor.pinImage(archivo)
                }
            }

            // Un proyecto saca su documento, que es lo que se mira de un proyecto.
            Clase.PROYECTO -> {
                val app = application as? PixPinApp
                val proyecto = app?.proyectos?.proyectos?.value
                    ?.firstOrNull { it.id == m.referencia }
                val doc = proyecto?.let {
                    com.forge.pixpin.motor.Proyectos.rutaDelDocumento(it) { r -> File(r).exists() }
                }
                if (doc != null) gestor.pinFile(doc, m.nombre, "application/pdf")
                else avisarDeQueNoHay()
            }

            // Y la mini-app, su texto: una lista de la compra pegada en la pantalla
            // mientras se hace la compra es exactamente para lo que existe pinear.
            Clase.MINIAPP -> gestor.pinTexto(m.texto)

            Clase.ARCHIVO -> m.ruta?.let { gestor.pinFile(it, m.nombre, "*/*") }
                ?: avisarDeQueNoHay()
        }
        // Sacando varios de golpe el aviso lo da quien los saca, una sola vez: cinco
        // tostadas seguidas tapan la pantalla justo donde acaban de aparecer los pines.
        if (avisar && m.clase != Clase.PAGINA) {
            Toast.makeText(
                this, com.forge.pixpin.R.string.guardados_pineado, Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Copia unos mensajes a otra conversación.
     *
     * **El adjunto se copia también**, y no es un detalle: dos mensajes apuntando al
     * mismo archivo parecen dos cosas, pero borrar uno se lleva el archivo y deja al otro
     * apuntando a un hueco. Quien reenvía una foto a la conversación de un proyecto
     * espera que esa foto siga ahí aunque limpie la general.
     */
    private fun reenviarA(cuales: List<Mensaje>, aDondeVa: String?) {
        val ahora = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            cuales.sortedBy { it.cuando }.forEachIndexed { i, m ->
                val copia = reenviado(m, aDondeVa, ahora + i, UUID.randomUUID().toString())
                val conArchivo = m.ruta?.let { origen ->
                    val archivo = File(origen)
                    if (!archivo.exists()) return@let copia.copy(ruta = null)
                    copia.copy(ruta = almacen.copiarAdjunto(archivo, m.nombre) ?: return@let copia)
                } ?: copia
                almacen.anadir(conArchivo)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MensajesActivity,
                    com.forge.pixpin.R.string.guardados_reenviado,
                    Toast.LENGTH_SHORT
                ).show()
                recargarLaLista?.invoke()
            }
        }
    }

    /** Cuando el archivo de un mensaje ya no está, se dice en vez de no hacer nada. */
    private fun avisarDeQueNoHay() {
        Toast.makeText(
            this, com.forge.pixpin.R.string.guardados_ya_no_esta, Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Comparte un mensaje **componiendo lo que se ve**, no mandando su archivo.
     *
     * Lo que se ve y el archivo no son lo mismo en dos casos, que son justo los dos que
     * fallaban: una foto anotada guarda la foto por un lado y lo dibujado por otro —así
     * que se mandaba la foto limpia, o no se mandaba nada—, y una página de un PDF es una
     * de doce, así que se mandaba el documento entero cuando se pedía una hoja.
     *
     * @param comoPdf con qué cara sale. Lo elige quien comparte; ver [ETIQUETAS] de la
     *  hoja de compartir.
     */
    private fun compartirCompuesto(m: Mensaje, comoPdf: Boolean) {
        lifecycleScope.launch {
            val archivo = withContext(Dispatchers.IO) {
                val rutaDelDibujo = m.referencia?.let {
                    com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this@MensajesActivity, it)
                }
                val mapa = when {
                    m.clase == Clase.PAGINA && m.ruta != null && m.pagina != null ->
                        paginaAnotada(
                            this@MensajesActivity, m.ruta!!, m.pagina!!,
                            m.referencia, rutaDelDibujo, ANCHO_AMPLIADO
                        )
                    rutaDelDibujo != null && File(rutaDelDibujo).exists() ->
                        com.forge.pixpin.motor.ExcalidrawStore.cargar(rutaDelDibujo)?.let { e ->
                            com.forge.pixpin.motor.DrawExport.aBitmap(e, ESCALA_COMPARTIDA) { id ->
                                e.files[id]?.path?.let {
                                    com.forge.pixpin.pin.ImageStore.load(it)
                                }
                            }
                        }
                    m.ruta != null ->
                        com.forge.pixpin.pin.ImageStore.load(m.ruta!!, ANCHO_AMPLIADO)
                    else -> null
                } ?: return@withContext null
                val nombre = nombreDeLoCompartido(m)
                if (comoPdf) Compartir.comoPdf(this@MensajesActivity, mapa, nombre)
                else Compartir.comoImagen(this@MensajesActivity, mapa, nombre)
            }
            if (archivo == null) {
                avisarDeQueNoHay()
                return@launch
            }
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MensajesActivity, "$packageName.fileprovider", archivo
                )
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = if (comoPdf) "application/pdf" else "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        null
                    )
                )
            }
        }
    }

    /** Cómo se llama lo que sale, con su página si viene de un documento. */
    private fun nombreDeLoCompartido(m: Mensaje): String {
        val base = m.nombre.ifBlank { getString(com.forge.pixpin.R.string.guardados_titulo) }
        val pagina = m.pagina ?: return base
        return "${base.substringBeforeLast('.')} - " +
            getString(com.forge.pixpin.R.string.proyecto_pagina, pagina + 1)
    }

    /** Lo que se puede componer: una foto anotada o una página. Lo demás va tal cual. */
    private fun sePuedeComponer(m: Mensaje): Boolean =
        m.clase == Clase.PAGINA ||
            (m.clase == Clase.IMAGEN && m.referencia != null) ||
            m.clase == Clase.DIBUJO

    private fun compartir(m: Mensaje) {
        runCatching {
            val envio = Intent(Intent.ACTION_SEND)
            if (m.ruta != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", File(m.ruta)
                )
                envio.type = "*/*"
                envio.putExtra(Intent.EXTRA_STREAM, uri)
                envio.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                envio.type = "text/plain"
                envio.putExtra(Intent.EXTRA_TEXT, m.texto)
            }
            startActivity(Intent.createChooser(envio, null))
        }
    }

    /** Abrir: cada clase se abre donde vive. Un dibujo en el lienzo, un PDF en su visor. */
    private fun abrir(m: Mensaje) {
        when (m.clase) {
            Clase.VOZ -> m.ruta?.let { sonar(it) }
            // Una nota con un enlace dentro se abre en el navegador al tocarla: es lo que
            // espera cualquiera que se manda una dirección a sí mismo para leerla luego.
            // Sin enlace no hay nada que abrir, y tocarla no hace nada — que es correcto:
            // el texto ya está entero delante.
            Clase.NOTA -> primerEnlace(m.texto)?.let { abrirEnlace(it.url) }
            // Una página se abre **en el editor, sobre su PDF**: es donde se anotó y
            // donde se sigue anotando. Abrir el PDF a secas perdería lo dibujado.
            Clase.PAGINA -> {
                val pdf = m.ruta
                val pagina = m.pagina
                val dibujo = m.referencia
                if (pdf != null && pagina != null && dibujo != null) {
                    com.forge.pixpin.motor.DrawEditorActivity.abrirPaginaDePdf(
                        this, dibujo,
                        com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, dibujo),
                        pdf, pagina
                    )
                }
            }
            // Un proyecto abre la pantalla de proyectos. No se busca la fila: la lista es
            // corta y llevar el desplazamiento hasta ella sería más código que valor.
            // Un proyecto adjunto abre **ese** proyecto, no la lista donde vive.
            Clase.PROYECTO -> startActivity(
                Intent(this, com.forge.pixpin.MainActivity::class.java)
                    .putExtra(com.forge.pixpin.EXTRA_PROYECTOS, true)
                    .putExtra(com.forge.pixpin.EXTRA_PROYECTO, m.referencia)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Clase.MINIAPP -> com.forge.pixpin.mini.MiniActivity.abrir(this, m.id)
            Clase.DIBUJO -> m.referencia?.let { dibujo ->
                com.forge.pixpin.motor.DrawEditorActivity.abrir(
                    this, dibujo,
                    com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, dibujo), null
                )
            }
            // **Una foto se abre en el editor, no en un visor.**
            //
            // Aquí una foto casi nunca es un recuerdo: es una captura, un recibo o una
            // pizarra que se guardó para señalar algo encima. Abrirla en la galería
            // obliga a exportar, editar fuera y volver a guardar, y lo que vuelve ya es
            // otra cosa distinta de la que estaba aquí.
            //
            // La primera vez se le asigna un dibujo y **se anota en el propio mensaje**:
            // así la segunda vez no se empieza de cero, se sigue donde se dejó. Sin ese
            // apunte, cada apertura sería una hoja en blanco sobre la misma foto.
            Clase.IMAGEN -> m.ruta?.let { foto ->
                val yaTenia = m.referencia
                val dibujo = yaTenia ?: UUID.randomUUID().toString()
                if (yaTenia == null) {
                    // El apunte se guarda **después de abrir el editor**, no antes: leer
                    // y reescribir el archivo entero aquí dejaba el toque congelado justo
                    // en el gesto que tiene que responder al momento. Que el apunte tarde
                    // dos décimas no lo nota nadie; que el editor tarde en abrirse, sí.
                    lifecycleScope.launch(Dispatchers.IO) {
                        almacen.reescribir(
                            almacen.leer().map {
                                if (it.id == m.id) it.copy(referencia = dibujo) else it
                            }
                        )
                    }
                }
                com.forge.pixpin.motor.DrawEditorActivity.abrir(
                    this, dibujo,
                    com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, dibujo),
                    if (yaTenia == null) foto else null
                )
            }
            else -> m.ruta?.let { abrirFuera(it) }
        }
    }

    private var reproductor: android.media.MediaPlayer? = null

    /**
     * Qué nota está sonando ahora mismo, si es que suena alguna.
     *
     * Es estado de Compose y no un campo a secas porque la fila tiene que enterarse:
     * sin esto el botón se queda en «reproducir» mientras suena, y con dos notas
     * seguidas uno acaba tocando la segunda para parar la primera.
     */
    private var sonando by mutableStateOf<String?>(null)

    /**
     * Pone o quita una nota de voz. El mismo botón hace las dos cosas.
     *
     * Tocar la que ya suena la para, como en cualquier reproductor; tocar otra corta la
     * anterior, porque dos voces a la vez no se entienden y nadie quiere eso.
     */
    private fun sonar(ruta: String) {
        if (sonando == ruta) {
            runCatching { reproductor?.release() }
            reproductor = null
            sonando = null
            return
        }
        runCatching { reproductor?.release() }
        reproductor = runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(ruta)
                prepare()
                setOnCompletionListener { sonando = null }
                start()
            }
        }.getOrNull()
        sonando = if (reproductor != null) ruta else null
    }

    /** Por dónde va la nota que suena, de 0 a 1. */
    private fun porDondeVa(): Float {
        val p = reproductor ?: return 0f
        val total = runCatching { p.duration }.getOrDefault(0)
        if (total <= 0) return 0f
        val donde = runCatching { p.currentPosition }.getOrDefault(0)
        return (donde.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Salta a un punto de la nota que suena. */
    private fun saltarA(fraccion: Float) {
        val p = reproductor ?: return
        val total = runCatching { p.duration }.getOrDefault(0)
        if (total > 0) runCatching { p.seekTo((total * fraccion.coerceIn(0f, 1f)).toInt()) }
    }

    private fun abrirFuera(ruta: String) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", File(ruta)
            )
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { reproductor?.release() }
        reproductor = null
        if (grabador != null) {
            midiendoPicos?.cancel()
            midiendoPicos = null
            picosDeVoz = null
            Voz.parar(grabador)
            grabador = null
            destinoDeVoz?.delete()
            destinoDeVoz = null
        }
    }

    // ---- Texto -----------------------------------------------------------

    private fun nombreDe(seccion: Seccion): String = getString(
        when (seccion) {
            Seccion.TODO -> com.forge.pixpin.R.string.guardados_todo
            Seccion.FOTOS -> com.forge.pixpin.R.string.guardados_fotos
            Seccion.ARCHIVOS -> com.forge.pixpin.R.string.guardados_archivos
            Seccion.VOZ -> com.forge.pixpin.R.string.guardados_voz
            Seccion.DIBUJOS -> com.forge.pixpin.R.string.guardados_dibujos
            Seccion.FIJADOS -> com.forge.pixpin.R.string.guardados_fijados
            Seccion.BUZON -> com.forge.pixpin.R.string.guardados_buzon
        }
    )

    private fun nombreDe(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull() ?: "archivo"

    /** La extensión en mayúsculas, o nada si el nombre no la lleva. */
    private fun extensionDe(nombre: String): String? =
        nombre.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it.length <= LARGO_DE_EXTENSION }
            ?.uppercase()

    /**
     * El texto con el trozo que casa con la búsqueda marcado.
     *
     * Sin esto la lista filtrada enseña resultados sin decir **por qué** casó cada uno,
     * y en una nota larga hay que releerla entera para encontrar la palabra. Telegram lo
     * pinta con su `chat_textSearchSelectionPaint` (`ChatMessageCell.java:23442`); allí
     * se desvanece a los 850 ms porque acompaña a un salto, pero aquí el filtro sigue
     * puesto mientras se mira, así que la marca se queda.
     */
    @Composable
    private fun conLoQueCasa(
        texto: String,
        consulta: String?
    ): androidx.compose.ui.text.AnnotatedString {
        if (consulta.isNullOrBlank()) return androidx.compose.ui.text.AnnotatedString(texto)
        val marca = MaterialTheme.colorScheme.primary.copy(alpha = ALFA_DE_LO_QUE_CASA)
        return remember(texto, consulta, marca) {
            val tramos = tramosQueCasan(texto, consulta)
            if (tramos.isEmpty()) {
                androidx.compose.ui.text.AnnotatedString(texto)
            } else {
                androidx.compose.ui.text.buildAnnotatedString {
                    append(texto)
                    tramos.forEach { tramo ->
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(background = marca),
                            tramo.first, tramo.last + 1
                        )
                    }
                }
            }
        }
    }

    private fun resumen(m: Mensaje): String = when {
        m.texto.isNotBlank() -> m.texto.take(60)
        m.nombre.isNotBlank() -> m.nombre
        m.clase == Clase.VOZ -> getString(com.forge.pixpin.R.string.guardados_voz)
        else -> getString(com.forge.pixpin.R.string.guardados_titulo)
    }

    /** La fecha corta, sin año: en el buzón nada dura más de una semana. */
    private fun cortaDe(cuando: Long): String {
        val idioma = resources.configuration.locales[0]
        return conPatron(cuando, idioma, "d MMM")
    }

    private fun horaDe(cuando: Long): String =
        android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(cuando))

    /**
     * Cómo se rotula un día en el separador. Ver [nombreDelDia] para el porqué.
     *
     * El patrón no se escribe a mano: `getBestDateTimePattern` da el orden que usa cada
     * idioma —«17 de agosto» aquí, «August 17» en inglés— a partir de los campos que se
     * piden. Escribir «d de MMMM» dejaría la aplicación hablando en español con las
     * palabras de otro idioma.
     */
    private fun fechaDe(cuando: Long): String {
        val idioma = resources.configuration.locales[0]
        return when (nombreDelDia(cuando, System.currentTimeMillis())) {
            NombreDelDia.HOY -> getString(com.forge.pixpin.R.string.guardados_hoy)
            NombreDelDia.AYER -> getString(com.forge.pixpin.R.string.guardados_ayer)
            NombreDelDia.ESTE_ANO -> conPatron(cuando, idioma, "d MMMM")
            NombreDelDia.CON_ANO -> conPatron(cuando, idioma, "d MMMM y")
        }
    }

    private fun conPatron(cuando: Long, idioma: java.util.Locale, campos: String): String =
        java.text.SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(idioma, campos), idioma
        ).format(java.util.Date(cuando))

    /** El día al que pertenece un instante, en la zona horaria de quien mira. */
    private fun diaDe(cuando: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = cuando
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }
}


/** Lo que se puede arrastrar una burbuja hacia el lado. Pasado esto, deja de seguir. */
private val ARRASTRE_MAXIMO = 80.dp

/**
 * Y a partir de dónde soltar ya comenta.
 *
 * Cincuenta puntos, que es el número que usa Telegram (`ChatActivity.java:4976`). No es
 * un número redondo cualquiera: por debajo, el gesto salta sin querer al desplazarse en
 * diagonal; por encima, hay que hacer un viaje.
 */
private val ARRASTRE_PARA_RESPONDER = 50.dp


/** Lo que tarda la pastilla de la fecha en aparecer y en irse. Telegram usa 150 ms. */
/** Lo que puede ocupar una foto en la conversación, y a qué resolución se lee. */
private val ALTO_DE_LA_FOTO = 380.dp
private const val ANCHO_DE_LA_FOTO = 1000

/**
 * Las esquinas de una foto que va a pelo, sin burbuja alrededor.
 *
 * **Las mismas que la burbuja**: la foto no va dentro de una burbuja, la foto **es** la
 * burbuja. Con 15 frente a 17 se veía que eran dos formas distintas puestas en la misma
 * columna, sin que nada explicara por qué.
 */
private val RADIO_DE_LA_FOTO = RADIO_BURBUJA.dp

/** Lo tapada que queda la foto tras la píldora de la hora. */
private const val ALFA_DE_LA_PILDORA = 0.6f

/**
 * Las etiquetas que se ofrecen.
 *
 * Seis y no un teclado de emojis entero: lo que se etiqueta cabe en seis ideas
 * —importante, hecho, pendiente, idea, dinero, sitio— y con mil opciones se tarda más en
 * elegir la etiqueta que en releer el mensaje.
 */
/** A qué escala se compone un dibujo para mandarlo fuera. */
private const val ESCALA_COMPARTIDA = 2.0

/** A qué escala se compone el sello de una cita. */
private const val ESCALA_DEL_SELLO = 0.12

private val ETIQUETAS = listOf("⭐", "✅", "⏳", "💡", "💰", "📍")

/** A qué escala se recompone una foto anotada al ampliarla con los dedos. */
private const val ESCALA_DE_LA_FOTO_AMPLIADA = 2.0

/** A qué ancho se vuelve a leer algo cuando se amplía con los dedos. */
private const val ANCHO_AMPLIADO = 2000

/** A qué ancho se compone la hoja de un PDF para la lista, y cuánto puede ocupar. */
private const val ANCHO_DE_LA_HOJA = 700
private val ALTO_DE_LA_HOJA = 300.dp

/** A qué escala se pinta el dibujo de una foto editada para la lista. */
private const val ESCALA_DE_LA_FOTO = 0.5

/** Lo que dura el destello del mensaje al que se acaba de saltar, y lo que tarda en irse. */
private const val LO_QUE_DURA_EL_DESTELLO = 1000L
private const val APAGADO_DEL_DESTELLO = 300

/** La barra de fijados: 48 de alto y como mucho tres rayitas. */
private val ALTO_DE_LOS_FIJADOS = 48.dp
private const val RAYITAS_A_LA_VEZ = 3
private const val ALFA_DE_LA_RAYITA = 0.44f

/** El alto de una ficha: los 38 de los chips de Telegram. */
private val ALTO_DE_LA_FICHA = 38.dp

/** Lo que se ve del fondo del chat, arriba y abajo del degradado. */
private const val ALTO_DEL_FONDO = 0.06f
private const val BAJO_DEL_FONDO = 0.10f

/** Lo que se aparta la burbuja para dejar sitio a la casilla, y lo que tarda. */
private val HUECO_DE_LA_CASILLA = 35.dp
private val CASILLA_DE_MARCAR = 21.dp
private const val APARTARSE_DE_LA_BURBUJA = 200

/** Más ancha que esto, una burbuja deja de leerse de un vistazo. */
private val ANCHO_TOPE_DE_BURBUJA = 480.dp

/** El nombre del hueco que el texto reserva para la hora. */
private const val HUECO_DE_LA_HORA = "hora"

/** Lo que tiñe el trozo de texto que casa con la búsqueda. */
private const val ALFA_DE_LO_QUE_CASA = 0.35f

/** Cada cuánto se pregunta por dónde va la nota que suena. */
private const val LATIDO_DE_LA_ONDA = 60L

/** Lo que se ve de la parte de la onda que aún no se ha oído. */
private const val ALFA_DE_LO_NO_OIDO = 0.3f

/** La onda: barras de 2 puntos cada 3, sobre una franja de 14. */
private val ANCHO_DE_LA_ONDA = 150.dp
private val ALTO_DE_LA_ONDA = ALTO_DE_LA_ONDA_DP.dp
private const val PARTE_LLENA_DE_LA_BARRA = 2f / 3f

/** El tamaño de la hora, el mismo en la burbuja y sobre una foto. */
private val TAMANO_DE_LA_HORA = 11.sp

/** Un día en milisegundos, para contar lo que le queda a algo del buzón. */
private const val UN_DIA = 24L * 60 * 60 * 1000

/** Cuántas líneas de una mini-app caben en la burbuja antes de resumir el resto. */
private const val FILAS_DE_LA_MINIAPP = 8

/** El botón redondo del archivo y de la nota de voz. */
private val BOTON_DEL_ARCHIVO = 44.dp

/** Más allá de esto no es una extensión, es parte del nombre. */
private const val LARGO_DE_EXTENSION = 5

/** Lo que se ve del color de acento en el fondo de una cita. */
private const val ALFA_DE_LA_CITA = 0.10f

/** Qué parte del ancho de la pantalla puede ocupar una burbuja como mucho. */
private const val PARTE_DE_LA_PANTALLA = 0.78f

/** Cuántos botones de adjuntar caben antes de que el siguiente asome. */
private const val BOTONES_A_LA_VISTA = 4.5f

/** Lo que tarda el punto rojo en apagarse y volver. */
private const val LATIDO_DEL_PUNTO = 700

/** Cuánto hay que arrastrar a la izquierda para tirar lo grabado. */
private const val PARTE_DEL_ANCHO_PARA_TIRAR = 0.35f
private val RECORRIDO_MAXIMO_PARA_TIRAR = 140.dp

/** Y cuánto hay que subir para dejar la grabación fija sin el dedo. */
private val SUBIDA_PARA_FIJAR = 57.dp

/** Por debajo de esto, soltar tira la nota en vez de mandarla. */
private const val MINIMO_PARA_MANDAR = 0.45f

/** El alto de una fila de la barra de escribir: los 44 de Telegram. */
private val FILA_DE_LA_BARRA = 44.dp

/** Lo redondo que es el campo. La mitad del alto: una píldora. */
private val ESQUINA_DEL_CAMPO = 22.dp

/** Lo que tarda el clip en apartarse al empezar a escribir. */
private const val RETIRADA_DEL_CLIP = 100

/** Lo que tarda en aparecer el botón que entra, y lo que tarda en irse el que sale. */
private const val ENTRADA_DEL_BOTON = 220
private const val SALIDA_DEL_BOTON = 150

/** El `EASE_OUT_QUINT` de Telegram: arranca de golpe y frena largo. */
private val SALIDA_SUAVE =
    androidx.compose.animation.core.CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

private const val FUNDIDO_DE_LA_FECHA = 150

/**
 * Y lo que espera quieta antes de desvanecerse.
 *
 * Medio segundo, como Telegram (`ChatActivity.java:609`). Menos y parpadea entre dos
 * empujones del dedo; más y se queda encima de lo que ibas a leer.
 */
private const val ESPERA_DE_LA_FECHA = 500L


/** Cuánto hay que subir para que salga el botón de bajar, y cuánto bajar para que se vaya. */
private val SUBIDA_PARA_EL_BOTON = 100.dp
