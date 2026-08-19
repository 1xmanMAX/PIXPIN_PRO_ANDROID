package com.forge.pixpin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.motor.CapaDeAnotacion
import com.forge.pixpin.motor.DrawEditorActivity
import com.forge.pixpin.motor.DrawExport
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.ExportarProyecto
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.HojasDelProyecto
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.PdfMiniaturas
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import com.forge.pixpin.motormd.Markdown
import com.forge.pixpin.motormd.MarkdownText
import com.forge.pixpin.motormd.Paginado
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Vuelve a poner en pantalla el PDF de un proyecto.
 *
 * Un pin cerrado no se pierde —el archivo sigue donde estaba y el proyecto
 * sabe dónde— pero sin esto había que buscarlo otra vez con el gestor de
 * archivos y volver a compartirlo a PixPin. El proyecto es lo que sabe de qué
 * documento se trata, así que es desde donde tiene que poder volver.
 */
private fun volverAPinear(app: PixPinApp, p: Proyecto) {
    // Si el original se perdió, se pinea la copia limpia: mejor el documento sin lo
    // anotado que ningún documento. Ver [Proyectos.rutaDelDocumento].
    val ruta = Proyectos.rutaDelDocumento(p) { java.io.File(it).exists() } ?: return
    app.overlayManager.pinFile(ruta, p.nombre, "application/pdf")
}

/**
 * Los proyectos: **uno por pantalla, y se pasan como se pasan los vídeos**.
 *
 * Sin esta pantalla el motor del PDF era fontanería sin grifo — todo construido
 * y sin forma de llegar. Es lo único que hacía falta para que anotar un plano de
 * doce hojas sea algo que se pueda hacer en dos ratos y no de una sentada.
 *
 * ## Por qué una lista dejó de servir
 *
 * En una lista de tarjetas cada proyecto tenía que caber en un trozo de
 * pantalla, y lo que se veía de un documento eran cuatro sellos de correos. El
 * dedo hacía el trabajo de decidir dónde parar, y las tarjetas de en medio
 * pasaban por delante sin que nadie las mirase.
 *
 * Aquí **una página es un proyecto**: se ve entero —nombre grande, la portada
 * del documento a tamaño de mirar, la tira de páginas y lo que se puede hacer
 * con él— y el desplazamiento vertical **encaja** en el siguiente. No se ojea:
 * se está en uno. Los que están en marcha salen primero y lo archivado al
 * fondo, que es el mismo orden de siempre ([Proyectos.ordenados]).
 *
 * ## Y hacia los lados está su conversación
 *
 * Arrastrando la tarjeta a un lado se abre el chat del proyecto. Ver
 * [deslizarAlChat] para por qué es un arrastre y no otra página de un paginador
 * horizontal — que es lo que primero se probó y lo que se peleaba con la tira de
 * hojas.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PantallaDeProyectos(
    app: PixPinApp,
    onVolver: () -> Unit,
    /**
     * Si viene puesto, esta pantalla enseña **solo ese proyecto**.
     *
     * Es lo que hace que un acceso directo lleve a lo que promete: se abre el proyecto,
     * no el sitio donde vive. Con la lista entera había que volver a buscarlo entre los
     * demás, que es justo el trabajo que el acceso directo venía a ahorrar.
     */
    soloEste: String? = null
) {
    val proyectos by app.proyectos.proyectos.collectAsState()
    val todos = Proyectos.ordenados(proyectos)
    val ordenados = if (soloEste == null) todos else todos.filter { it.id == soloEste }
    val contexto = LocalContext.current

    // Lo marcado vive **en la pantalla**, no en cada tarjeta: sobrevive a que la
    // página se recomponga y se sabe desde arriba qué hay que exportar. De un
    // proyecto a la vez, que es como se entrega: marcar en otro empieza de cero.
    var marcado by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
    var exportando by remember { mutableStateOf(false) }

    // Se recuerda aquí y no dentro del paginador porque la cabecera enseña por
    // cuál se va, y esa cuenta es lo que dice que hay más debajo.
    val paginador = rememberPagerState(pageCount = { ordenados.size })

    Scaffold { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            // **La cabecera se queda fina a propósito.** Todo lo que es del
            // proyecto —su nombre, sus botones— bajó a la tarjeta, que ahora
            // tiene sitio de sobra; arriba solo queda lo que es de la pantalla:
            // salir, por dónde vas y crear otro.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onVolver) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResourceSafe(R.string.cd_close)
                    )
                }
                // **La puerta a Mensajes guardados, que si no se queda sin ninguna.**
                //
                // La conversación general ya no es lo que abre la aplicación: se llega
                // desde la bola. Pero la bola solo está si el servicio corre, y quien
                // aún no lo ha arrancado —o lo tiene parado— se quedaría sin forma de
                // entrar a lo que tiene guardado. Aquí cuesta un icono y no se puede dar
                // el caso de un cajón al que no se llega.
                if (soloEste == null) {
                    IconButton(onClick = {
                        contexto.startActivity(
                            android.content.Intent(
                                contexto,
                                com.forge.pixpin.guardados.MensajesActivity::class.java
                            )
                        )
                    }) {
                        Icon(
                            Icons.Filled.BookmarkBorder,
                            contentDescription = stringResourceSafe(R.string.guardados_titulo)
                        )
                    }
                }
                Text(
                    // Con un solo proyecto a la vista, el título es su nombre: decir
                    // «Proyectos» encima de uno solo es rotular la estantería teniendo
                    // el libro abierto delante.
                    if (soloEste != null) {
                        ordenados.singleOrNull()?.nombre.orEmpty()
                    } else {
                        stringResourceSafe(R.string.proyectos_titulo)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
                // **Por cuál vas de cuántos.** Es la señal de que esto sigue
                // hacia abajo, y no cuesta un fotograma: vive en su propia
                // función para que leer la posición del paginador no obligue a
                // recomponer la pantalla entera cada vez que se pasa de página.
                if (soloEste == null && ordenados.size > 1) {
                    ContadorDeProyectos(paginador, ordenados.size)
                }
                // Y no se ofrece crear otro cuando se ha venido a uno concreto: la lista
                // está filtrada a este, así que el botón de más solo puede acabar en un
                // proyecto vacío que no se ve.
                if (soloEste == null) {
                    val plantilla = stringResourceSafe(R.string.proyecto_nuevo_nombre)
                    IconButton(
                        onClick = { app.proyectos.nuevo(plantilla, System.currentTimeMillis()) }
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResourceSafe(R.string.proyecto_nuevo)
                        )
                    }
                }
            }

            if (exportando) {
                val cual = marcado
                LaunchedEffect(cual) {
                    val proyecto = ordenados.firstOrNull { it.id == cual?.first }
                    val archivo = if (proyecto == null) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            ExportarProyecto.aArchivo(
                                contexto, proyecto, cual!!.second,
                                { dibujo ->
                                    ExcalidrawStore.cargar(
                                        ExcalidrawStore.rutaDe(contexto, dibujo)
                                    )
                                }
                            )
                        }
                    }
                    exportando = false
                    if (archivo == null) avisar(contexto, R.string.pdf_no_se_pudo)
                    else compartir(contexto, archivo)
                }
            }

            if (ordenados.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResourceSafe(R.string.proyectos_vacio),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp)
                    )
                }
                return@Column
            }

            // Cómo se marca y se desmarca una hoja, igual para el proyecto suelto
            // que para los del paginador.
            val marcarEn: (Proyecto, String) -> Unit = { p, clave ->
                val suyas = if (marcado?.first == p.id) marcado!!.second else emptySet()
                val nuevas = if (clave in suyas) suyas - clave else suyas + clave
                marcado = if (nuevas.isEmpty()) null else p.id to nuevas
            }

            // **Con uno solo no hay nada que paginar.** Se enseña la misma
            // tarjeta, sin paginador y sin el asomo de abajo: asomar la página
            // siguiente cuando no hay siguiente es prometer algo que no existe.
            val unico = if (soloEste != null) ordenados.firstOrNull() else null
            if (unico != null) {
                PaginaDeProyecto(
                    app = app,
                    p = unico,
                    enPrimerPlano = true,
                    marcadas = if (marcado?.first == unico.id) marcado!!.second else emptySet(),
                    onMarcar = { clave -> marcarEn(unico, clave) },
                    onExportar = { exportando = true },
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                )
                return@Column
            }

            VerticalPager(
                state = paginador,
                modifier = Modifier.fillMaxSize(),
                // **El asomo de la siguiente**, que es lo único que hace falta
                // para que se entienda que esto baja. Un indicador que parpadea
                // hay que explicarlo; medio centímetro de la tarjeta de abajo,
                // no — y encima dice cuál viene.
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = ASOMO),
                pageSpacing = 10.dp,
                // **No se preparan páginas que no se ven.** Con el asomo, la
                // siguiente ya entra en la ventana y se compone sola: no hace
                // falta pedirle al paginador que tenga listas otras tantas, que
                // en un móvil con veinte proyectos es abrir veinte PDF para
                // enseñar uno. Ver [PaginaDeProyecto] y su `enPrimerPlano`.
                beyondViewportPageCount = 0,
                key = { i -> ordenados.getOrNull(i)?.id ?: i }
            ) { indice ->
                val p = ordenados.getOrNull(indice) ?: return@VerticalPager
                PaginaDeProyecto(
                    app = app,
                    p = p,
                    // Lo caro —dibujar la portada en grande y preparar la tanda
                    // de miniaturas— se hace **solo del que se ha parado
                    // delante**. El que asoma por abajo enseña lo que ya
                    // estuviera en memoria y no pide nada.
                    enPrimerPlano = paginador.settledPage == indice,
                    marcadas = if (marcado?.first == p.id) marcado!!.second else emptySet(),
                    onMarcar = { clave -> marcarEn(p, clave) },
                    onExportar = { exportando = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Por cuál se va, de cuántos.
 *
 * Aparte para que la lectura de [PagerState.currentPage] —que cambia con cada
 * página— invalide este texto y **nada más**. Leyéndola donde se llama al
 * paginador, cada cambio de página rehacía la pantalla entera, incluida la
 * tarjeta que se acaba de poner delante.
 */
@Composable
private fun ContadorDeProyectos(paginador: PagerState, total: Int) {
    Text(
        stringResourceSafe(R.string.proyecto_posicion, paginador.currentPage + 1, total),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

/**
 * Un proyecto, a pantalla completa.
 *
 * ## Qué se ve y en qué orden
 *
 * De arriba abajo: **el nombre**, grande, con lo que lleva anotado debajo; **la
 * página que se esté mirando**, ocupando todo lo que sobra; **la tira de
 * hojas**, que hace de mando de esa portada; y **lo que se hace a diario** en
 * una fila de botones al pie. Lo que se toca una vez al mes —archivar, cambiar
 * el nombre, borrar— se fue al menú de la esquina: tener el botón de borrar
 * pegado al de abrir el chat era una tarjeta con una trampa.
 *
 * @param enPrimerPlano si es la página en la que el paginador se ha parado. Lo
 *   que cuesta trabajo se hace solo entonces; ver [PantallaDeProyectos].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PaginaDeProyecto(
    app: PixPinApp,
    p: Proyecto,
    enPrimerPlano: Boolean,
    marcadas: Set<String>,
    onMarcar: (String) -> Unit,
    onExportar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contexto = LocalContext.current
    var renombrando by remember { mutableStateOf(false) }
    var menu by remember(p.id) { mutableStateOf(false) }
    val tick = remember(p) { p.tocado }

    // **Anotadas de verdad, no «abiertas alguna vez».** A una hoja se le asigna
    // su dibujo al tocarla y el editor guarda al salir aunque no se haya trazado
    // nada: contando archivos, abrir cinco páginas y cerrarlas decía que el
    // documento estaba anotado entero. Ver [CapaDeAnotacion].
    val conDibujo = remember(p, tick) { p.hojas.mapNotNull { it.dibujo } }
    val anotadasDeVerdad by produceState(emptySet<String>(), conDibujo) {
        value = withContext(Dispatchers.IO) {
            CapaDeAnotacion.conContenido(contexto, conDibujo)
        }
    }

    // **Un PDF se arma al vuelo; un proyecto de lienzos, en el hilo de disco.**
    //
    // Armar la lista de páginas de un PDF es repartir números y no toca el
    // disco, así que hacerlo aquí mismo es instantáneo y evita el parpadeo de
    // una lista que aparece un fotograma después. Pero un proyecto de lienzos
    // **carga la escena de cada hoja** para saber cuántas láminas tiene, y eso
    // son tantos archivos JSON leídos y analizados como hojas — en el hilo de la
    // interfaz, que es donde estaba. Solo ese caso se va fuera.
    val hayLienzos = remember(p) { p.hojas.any { it.pagina == null && it.dibujo != null } }
    val paginas: List<HojasDelProyecto.Pagina> = if (!hayLienzos) {
        remember(p, tick) { HojasDelProyecto.paginas(p) { null } }
    } else {
        // `produceState` conserva lo último mientras rehace, así que la lista no
        // se queda vacía al volver de anotar una hoja.
        produceState(emptyList<HojasDelProyecto.Pagina>(), p, tick) {
            value = withContext(Dispatchers.IO) {
                HojasDelProyecto.paginas(p) { dibujo ->
                    ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, dibujo))
                }
            }
        }.value
    }

    // **Dos formas de mirar el mismo documento.** La portada con su tira es la
    // de trabajar —una página grande, y se pasa con el pulgar—; la rejilla es la
    // de buscar, que en un plano de doscientas hojas es media pantalla de
    // sellos y llegas al final en dos gestos. Las dos son perezosas, así que
    // cambiar de una a otra no cuesta nada.
    var rejilla by remember(p.id) { mutableStateOf(false) }

    val alcance = rememberCoroutineScope()
    val desplazamiento = remember(p.id) { Animatable(0f) }
    val alChat = {
        com.forge.pixpin.guardados.MensajesActivity.abrirChatDe(contexto, p.id, p.nombre)
    }

    Box(modifier) {
        // La pista del gesto va **debajo** de la tarjeta: no se ve hasta que la
        // tarjeta se aparta, que es justo cuando hay algo que explicar.
        PistaDeChat(desplazamiento)

        Card(
            Modifier
                .fillMaxSize()
                // Se mueve en la capa de dibujo, leyendo el valor dentro de la
                // lambda: el arrastre no recompone ni un nodo por fotograma.
                .graphicsLayer { translationX = desplazamiento.value }
                .deslizarAlChat(desplazamiento, alcance, p.id, alChat)
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(
                        Modifier
                            .weight(1f)
                            // **Se renombra manteniéndolo pulsado.** Un botón de
                            // «cambiar el nombre» es un botón para algo que se
                            // hace una vez; el nombre ya está ahí y es donde se
                            // busca. También está en el menú, para quien no dé
                            // con el gesto.
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { renombrando = true }
                            )
                    ) {
                        Text(
                            p.nombre,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = if (p.archivado) FontWeight.Normal else FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color =
                                if (p.archivado) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (p.pdfOrigen != null) {
                                stringResourceSafe(
                                    R.string.proyecto_hojas_de,
                                    anotadasDeVerdad.size,
                                    p.hojas.size
                                )
                            } else {
                                stringResourceSafe(R.string.proyecto_hojas, p.hojas.size)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (paginas.isNotEmpty()) {
                        IconButton(onClick = { rejilla = !rejilla }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                if (rejilla) Icons.Filled.ViewCarousel else Icons.Filled.GridView,
                                contentDescription = stringResourceSafe(
                                    if (rejilla) R.string.proyecto_vista_carrusel
                                    else R.string.proyecto_vista_rejilla
                                ),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResourceSafe(R.string.proyecto_mas),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // **Lo de una vez, detrás de un toque.** Archivar se hace
                        // al terminar y borrar casi nunca; puestos a la vista se
                        // pulsan sin querer, y el de borrar no tiene vuelta.
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResourceSafe(R.string.proyecto_renombrar)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { menu = false; renombrando = true }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResourceSafe(
                                            if (p.archivado) R.string.proyecto_desarchivar
                                            else R.string.proyecto_archivar
                                        )
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (p.archivado) Icons.Filled.Unarchive
                                        else Icons.Filled.Archive,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    menu = false
                                    app.proyectos.guardar(
                                        Proyectos.archivado(
                                            p, !p.archivado, System.currentTimeMillis()
                                        )
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResourceSafe(R.string.cd_delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = { menu = false; app.proyectos.borrar(p.id) }
                            )
                        }
                    }
                }

                if (renombrando) {
                    DialogoDeNombre(p.nombre, onCerrar = { renombrando = false }) { nuevo ->
                        app.proyectos.guardar(
                            Proyectos.renombrado(p, nuevo, System.currentTimeMillis())
                        )
                        renombrando = false
                    }
                }

                Spacer(Modifier.height(10.dp))

                // La hoja que se esté sujetando con los dedos, encima de todo.
                // Ver [ZoomDeHoja]: no hace nada mientras no hay ninguna.
                val ampliada = recordarZoomDeHoja()

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        paginas.isEmpty() -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResourceSafe(R.string.proyecto_hojas, 0),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        rejilla -> RejillaDeHojas(
                            app = app,
                            p = p,
                            paginas = paginas,
                            marcadas = marcadas,
                            anotadas = anotadasDeVerdad,
                            onMarcar = onMarcar,
                            pedirMiniaturas = enPrimerPlano
                        )

                        else -> PortadaConTira(
                            app = app,
                            p = p,
                            paginas = paginas,
                            marcadas = marcadas,
                            anotadas = anotadasDeVerdad,
                            onMarcar = onMarcar,
                            ampliada = ampliada,
                            enPrimerPlano = enPrimerPlano
                        )
                    }
                }

                CapaDeAmpliacion(ampliada)

                Spacer(Modifier.height(6.dp))
                BarraDeAcciones(
                    app = app,
                    p = p,
                    marcadas = marcadas.size,
                    onChat = alChat,
                    onExportar = onExportar
                )
            }
        }

        // **El tirador del lado.** Un pelo de color pegado al borde derecho: no
        // dice nada por sí solo, pero es lo que hace que el dedo pruebe a
        // empujarlo. Ver [deslizarAlChat] para lo que pasa entonces.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .width(3.dp)
                .height(44.dp)
                .graphicsLayer {
                    // Se aparta con la tarjeta, o se quedaría flotando encima de
                    // la pista que él mismo anuncia.
                    translationX = desplazamiento.value
                    alpha = 0.35f
                }
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }
}

/**
 * Deslizar la tarjeta a un lado abre **la conversación del proyecto**.
 *
 * Mientras se anota un plano de doce hojas uno va dejando notas, fotos de
 * referencia y cosas por hacer; sin un sitio propio, todo eso acababa mezclado
 * con la lista de la compra. Ver [com.forge.pixpin.guardados.MensajesActivity].
 *
 * ## Por qué un arrastre y no otra página de un paginador
 *
 * Lo primero que se probó fue un `HorizontalPager` de dos páginas —la tarjeta y
 * una que abre el chat al asentarse—. Se descartó por tres cosas:
 *
 * 1. **Se pelea con la tira de hojas.** Un paginador es un `scrollable`, y un
 *    `scrollable` recoge por encadenamiento lo que su hijo no consumió: al
 *    llegar al final de la tira de páginas del PDF —que es un `LazyRow` y pasa
 *    todos los días— el resto del empujón se lo quedaba el paginador y se
 *    abría el chat sin que nadie lo pidiera. Con un arrastre a nivel de dedo la
 *    repartición es la contraria y es la buena: el hijo mira el evento primero,
 *    y si la tira lo consume este gesto **se cancela solo** —así funciona
 *    `detectHorizontalDragGestures`, que abandona en cuanto ve el toque
 *    consumido—. La tira se queda con su gesto entero.
 * 2. **No hay una página que enseñar.** La segunda página de ese paginador
 *    estaba vacía a la fuerza —lo que se abre es otra Activity—, así que a mitad
 *    de gesto se veía un hueco, y al volver del chat había que devolver el
 *    paginador a su sitio sin que se notara.
 * 3. **Cuesta el doble.** Dos páginas compuestas por proyecto, dentro de otro
 *    paginador que ya tiene la suya y la que asoma.
 *
 * Se abre **al soltar pasado el umbral**, no al cruzarlo: hasta que el dedo se
 * levanta se puede volver atrás, que es lo que hace que un gesto no dé miedo. Y
 * la tarjeta vuelve sola a su sitio, porque lo que se abre es otra pantalla y al
 * volver de ella esto tiene que estar como estaba.
 */
private fun Modifier.deslizarAlChat(
    desplazamiento: Animatable<Float, AnimationVector1D>,
    alcance: CoroutineScope,
    clave: Any,
    alAbrir: () -> Unit
): Modifier = this.pointerInput(clave) {
    val tope = size.width * TOPE_DEL_DESLIZAMIENTO
    val umbral = size.width * UMBRAL_DEL_DESLIZAMIENTO
    val volver = {
        alcance.launch {
            desplazamiento.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
            )
        }
    }
    detectHorizontalDragGestures(
        onDragEnd = {
            val abre = abs(desplazamiento.value) >= umbral
            volver()
            if (abre) alAbrir()
        },
        onDragCancel = { volver() }
    ) { cambio, delta ->
        // Consumido para que el paginador vertical no interprete lo que quede de
        // diagonal como un cambio de proyecto a mitad de gesto.
        cambio.consume()
        alcance.launch {
            desplazamiento.snapTo((desplazamiento.value + delta).coerceIn(-tope, tope))
        }
    }
}

/**
 * Lo que aparece por el hueco cuando la tarjeta se aparta.
 *
 * Se pinta a los dos lados y cada uno se enciende con **su** dirección, así que
 * el gesto vale igual tirando a izquierda o a derecha: no hay que acordarse de
 * hacia dónde era. La opacidad se lee dentro de `graphicsLayer` —o sea, al
 * dibujar—, que es lo que permite que el arrastre no recomponga nada.
 */
@Composable
private fun PistaDeChat(desplazamiento: Animatable<Float, AnimationVector1D>) {
    val texto = stringResourceSafe(R.string.proyecto_chat)
    Box(Modifier.fillMaxSize()) {
        listOf(Alignment.CenterStart to 1f, Alignment.CenterEnd to -1f).forEach { (lado, signo) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(lado)
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        // Se mide contra una distancia fija y no contra el ancho
                        // de esta columna: lo que hay aquí es un icono estrecho,
                        // y dividir por su ancho encendería la pista con el
                        // primer milímetro de arrastre.
                        val avance = (desplazamiento.value * signo) / PISTA_ENTERA_A.toPx()
                        alpha = avance.coerceIn(0f, 1f)
                    }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    texto,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * Lo que se hace con el proyecto a diario, en una fila al pie.
 *
 * Entrar al chat y volver a poner el PDF en pantalla son las dos cosas de todos
 * los días, y por eso están a mano. Exportar aparece **solo cuando hay algo
 * marcado**: es una acción de lo que acabas de elegir, no una opción del
 * proyecto que esté siempre ahí ocupando sitio.
 */
@Composable
private fun BarraDeAcciones(
    app: PixPinApp,
    p: Proyecto,
    marcadas: Int,
    onChat: () -> Unit,
    onExportar: () -> Unit
) {
    val contexto = LocalContext.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        BotonDeAccion(
            Icons.AutoMirrored.Filled.Chat, R.string.proyecto_chat, R.string.proyecto_chat, onChat
        )
        if (p.pdfOrigen != null) {
            BotonDeAccion(
                Icons.Filled.PushPin, R.string.proyecto_pdf_corto, R.string.proyecto_pinear
            ) { volverAPinear(app, p) }
        } else {
            // En un proyecto de lienzos, añadir hoja y añadir nota **son** el
            // trabajo diario: no pueden estar detrás de un menú.
            BotonDeAccion(
                Icons.Filled.Add, R.string.proyecto_hoja_corta, R.string.proyecto_hoja_nueva
            ) {
                val ahora = System.currentTimeMillis()
                val hoja = Hoja(id = "h-$ahora", dibujo = "dib-$ahora")
                app.proyectos.guardar(Proyectos.conHoja(p, hoja, ahora))
            }
            // Un proyecto se entrega con texto dentro —la portada, la explicación
            // de un plano, el presupuesto— y hasta que esto estuvo aquí la única
            // forma de meterlo era abrir el editor por otro sitio y mandarlo «al
            // proyecto en curso», que es adivinar. Nace vacía y abre el editor:
            // lo que se escriba vuelve a la hoja al guardar.
            BotonDeAccion(
                Icons.AutoMirrored.Filled.Notes,
                R.string.proyecto_nota_corta,
                R.string.proyecto_nota_nueva
            ) {
                val ahora = System.currentTimeMillis()
                val id = "n-$ahora"
                app.proyectos.guardar(Proyectos.conHoja(p, Hoja(id = id, nota = ""), ahora))
                MarkdownEditorActivity.abrir(contexto, id, "")
            }
        }
        Spacer(Modifier.weight(1f))
        // Exportar no tiene la forma de los demás **a propósito**: no es una cosa
        // que el proyecto sepa hacer siempre, es lo que hay que hacer con lo que
        // acabas de marcar, y lleva la cuenta dentro para que no haya que ir a
        // contar las hojas con el tic.
        if (marcadas > 0) {
            TextButton(onClick = onExportar, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(
                    Icons.Filled.Share,
                    // El texto de al lado lleva la cuenta; la frase entera se
                    // queda aquí, para quien no ve la pantalla.
                    contentDescription = stringResourceSafe(R.string.proyecto_exportar),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    stringResourceSafe(R.string.proyecto_exportar_n, marcadas),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

/**
 * Un botón de la fila del pie: icono y palabra debajo.
 *
 * La palabra **corta** y el nombre entero en la descripción: con las tres
 * acciones de un proyecto de lienzos y el botón de exportar, una fila de
 * etiquetas largas no cabe en un móvil, y lo que se sale de una fila es lo que
 * desaparece. Un icono solo tampoco vale — «volver a pinear» no es un chincheta
 * para nadie que no lo haya escrito.
 */
@Composable
private fun BotonDeAccion(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    etiqueta: Int,
    descripcion: Int,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(66.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Icon(
            icono,
            contentDescription = stringResourceSafe(descripcion),
            modifier = Modifier.size(22.dp)
        )
        Text(
            stringResourceSafe(etiqueta),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/**
 * La portada y su tira, que son una sola cosa: la tira manda sobre la portada.
 *
 * Van juntas en su propia función **para que la hoja en la que está el dedo no
 * salga de aquí**. Ese número cambia cada vez que la tira pasa una página, y
 * teniéndolo arriba, en la tarjeta, cada empujón de la tira rehacía también el
 * nombre, el menú y la fila de botones — que no se han enterado de nada.
 */
@Composable
private fun PortadaConTira(
    app: PixPinApp,
    p: Proyecto,
    paginas: List<HojasDelProyecto.Pagina>,
    marcadas: Set<String>,
    anotadas: Set<String>,
    onMarcar: (String) -> Unit,
    ampliada: ZoomDeHoja,
    enPrimerPlano: Boolean
) {
    var enFoco by remember(p.id) { mutableIntStateOf(0) }
    // Si la tira se quedó apuntando a una hoja que ya no está —se borró desde el
    // editor—, la portada vuelve a la primera en vez de quedarse en blanco.
    val portada = paginas.getOrNull(enFoco) ?: paginas.first()

    Column(Modifier.fillMaxSize()) {
        PortadaDelProyecto(
            app = app,
            p = p,
            pagina = portada,
            marcada = portada.clave in marcadas,
            onMarcar = onMarcar,
            ampliada = ampliada,
            enPrimerPlano = enPrimerPlano,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        TiraDeHojas(
            app = app,
            p = p,
            paginas = paginas,
            marcadas = marcadas,
            anotadas = anotadas,
            enFoco = enFoco,
            onFoco = { enFoco = it },
            onMarcar = onMarcar,
            pedirMiniaturas = enPrimerPlano
        )
    }
}

/**
 * La página que se está mirando, del tamaño de mirarla.
 *
 * Es lo que gana la pantalla completa: en la lista lo más grande que se veía de
 * un documento era un sello de sesenta puntos, y con eso se reconoce una página
 * pero no se lee. Aquí se lee el título de un plano sin abrir nada.
 *
 * Un toque la abre para anotarla y una pulsación larga la marca —lo mismo que en
 * la tira, para que no haya dos idiomas—, y con dos dedos se despega y llena la
 * pantalla. Ver [ZoomDeHoja].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PortadaDelProyecto(
    app: PixPinApp,
    p: Proyecto,
    pagina: HojasDelProyecto.Pagina,
    marcada: Boolean,
    onMarcar: (String) -> Unit,
    ampliada: ZoomDeHoja,
    enPrimerPlano: Boolean,
    modifier: Modifier = Modifier
) {
    val contexto = LocalContext.current
    val h = pagina.hoja
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { abrirHoja(contexto, app, p, pagina) },
                onLongClick = { onMarcar(pagina.clave) }
            ),
        contentAlignment = Alignment.Center
    ) {
        // **Los píxeles siguen al hueco, por escalones.** A medida exacta, cada
        // pantalla y cada giro pedirían un dibujo distinto de la misma página y
        // la caché se llenaría de versiones que no se van a volver a pedir.
        val densidad = LocalDensity.current
        val ancho = if (!enPrimerPlano) {
            // **El que asoma por abajo sí prepara su portada, pero pequeña.**
            // Es una página dibujada, no veinte: lo justo para que al llegar a
            // él haya algo en pantalla en vez de un hueco gris. Lo grande se
            // pide en cuanto el paginador se para aquí, y como el ancho entra en
            // la clave del efecto, se pide solo. Ver [PantallaDeProyectos].
            PdfDoc.THUMB_WIDTH
        } else {
            val px = with(densidad) { maxWidth.toPx() }
            PASOS_DE_NITIDEZ.firstOrNull { it >= px } ?: PASOS_DE_NITIDEZ.last()
        }

        when {
            p.pdfOrigen != null && h.pagina != null -> MiniaturaDePagina(
                p.pdfOrigen!!, h.pagina!!, ancho, ampliada, dibujo = h.dibujo
            )
            // A tamaño grande la nota se compone con letra de leer, no con la
            // de tres puntos y medio de la tira.
            h.nota != null -> MiniaturaDeNota(pagina.texto ?: h.nota!!, tamaño = 9f)
            else -> MiniaturaDeLienzo(
                contexto, h.dibujo, pagina.marco,
                escala = if (enPrimerPlano) 0.6 else 0.18,
                ampliada = ampliada
            )
        }

        // Qué página es, en una esquina. En la tira se ve por dónde va el dedo,
        // pero la portada por sí sola no dice de cuál es.
        Text(
            pagina.nombre,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )

        if (marcada) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            )
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp)
            )
        }
    }
}

/**
 * La tira de hojas: **el mando de la portada**.
 *
 * Una fila, con encaje al soltar —es lo que separa «pasar hojas» de «empujar una
 * lista»— y la hoja de la izquierda es la que se está viendo arriba. Tocar una
 * sigue abriéndola para anotar, que es lo que se hace veinte veces al día;
 * mantenerla pulsada la marca para exportar.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TiraDeHojas(
    app: PixPinApp,
    p: Proyecto,
    paginas: List<HojasDelProyecto.Pagina>,
    marcadas: Set<String>,
    anotadas: Set<String>,
    enFoco: Int,
    onFoco: (Int) -> Unit,
    onMarcar: (String) -> Unit,
    pedirMiniaturas: Boolean
) {
    val estado = remember(p.id) { LazyListState() }
    LazyRow(
        state = estado,
        modifier = Modifier.fillMaxWidth(),
        // Al soltar se para en una hoja: si la portada sigue a la primera de la
        // fila, la fila tiene que dejar siempre una hoja en la primera posición.
        flingBehavior = rememberSnapFlingBehavior(estado)
    ) {
        items(paginas.size, key = { it }) { i ->
            HojaDelProyecto(
                app = app,
                p = p,
                pagina = paginas[i],
                marcada = paginas[i].clave in marcadas,
                onMarcar = { onMarcar(paginas[i].clave) },
                onElegir = { onFoco(i) },
                anotada = paginas[i].hoja.dibujo in anotadas,
                enFoco = i == enFoco,
                pedirMiniatura = pedirMiniaturas
            )
        }
    }

    // **La portada NO sigue a la tira.** Antes cambiaba con el desplazamiento, y eso
    // convertía un gesto de mirar en un gesto de elegir: barrer treinta hojas para ver
    // cuál era la buena repintaba la portada treinta veces, cada una pidiendo su página
    // grande al lector de PDF, y el que uno buscaba pasaba de largo. Ahora la tira sirve
    // para buscar y **el toque es el que decide**, que es lo que uno espera de un
    // carrusel de miniaturas: se mira barriendo y se elige tocando.
    PreparadorDeMiniaturas(p, paginas, estado, filas = 1, pedir = pedirMiniaturas)
}

/**
 * Todas las hojas a la vez, para llegar lejos.
 *
 * **Las filas salen de lo que mide el hueco**, no de un número escrito: la
 * tarjeta ocupa la pantalla y eso son cuatro o cinco filas en un móvil y más en
 * una tableta. Y no se hacen más filas de las que tiene sentido: un proyecto de
 * seis páginas en cinco filas serían cinco columnas de una hoja.
 */
@Composable
private fun RejillaDeHojas(
    app: PixPinApp,
    p: Proyecto,
    paginas: List<HojasDelProyecto.Pagina>,
    marcadas: Set<String>,
    anotadas: Set<String>,
    onMarcar: (String) -> Unit,
    pedirMiniaturas: Boolean
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val caben = (maxHeight / ALTO_DE_FILA).toInt().coerceIn(1, 6)
        // Cuatro columnas antes de abrir otra fila: por debajo de eso la rejilla
        // deja de ser una rejilla.
        val filas = caben.coerceAtMost(((paginas.size + 3) / 4).coerceAtLeast(1))
        val porFila = (paginas.size + filas - 1) / filas
        val estado = remember(p.id, filas) { LazyListState() }

        // **Una fila de columnas**, no una columna de filas. Con la primera mitad
        // arriba y la segunda abajo, la columna número x lleva las páginas x,
        // x+porFila, x+2·porFila… así que sale el mismo reparto y el
        // desplazamiento horizontal es el de un contenedor normal, sin filas
        // anidadas dentro de algo que se desplaza —que es donde se torcía y
        // salían en vertical.
        //
        // **Perezosa, y esto es la mitad del arreglo.** Con un `Row` que se
        // desplaza, las doscientas páginas de un PDF se componen todas a la vez
        // —aunque solo se vean cuatro— y cada una pide su miniatura nada más
        // nacer. Con `LazyRow` solo existe lo que se ve, así que un documento de
        // mil páginas cuesta lo mismo que uno de diez. Ver [PdfMiniaturas] para
        // la otra mitad.
        LazyRow(
            state = estado,
            modifier = Modifier.fillMaxSize(),
            flingBehavior = ScrollableDefaults.flingBehavior()
        ) {
            items(porFila, key = { it }) { x ->
                Column {
                    (0 until filas).forEach { f ->
                        val pagina = paginas.getOrNull(f * porFila + x)
                        if (pagina != null) {
                            HojaDelProyecto(
                                app = app,
                                p = p,
                                pagina = pagina,
                                marcada = pagina.clave in marcadas,
                                onMarcar = { onMarcar(pagina.clave) },
                                anotada = pagina.hoja.dibujo in anotadas,
                                enFoco = false,
                                pedirMiniatura = pedirMiniaturas
                            )
                        }
                    }
                }
            }
        }
        PreparadorDeMiniaturas(p, paginas, estado, filas, pedirMiniaturas)
    }
}

/**
 * Las miniaturas se dibujan **por tandas, mirando por dónde va el dedo**.
 *
 * Pedirlas de una en una abre el PDF una vez por página, que es lo que costaba
 * los segundos. Aquí se prepara de un tirón la ventana de lo que se está viendo
 * y un poco de lo que viene: una apertura del documento para veintitantas
 * páginas.
 *
 * Solo del proyecto que se tiene delante ([pedir]). El que asoma por abajo del
 * paginador enseña lo que ya hubiera en memoria: preparar la tanda de un
 * proyecto que nadie está mirando es abrir un PDF y dibujar veinte páginas para
 * nada, y encima el turno de [PdfMiniaturas] es uno solo — le quitaría el sitio
 * al que sí se está viendo.
 */
@Composable
private fun PreparadorDeMiniaturas(
    p: Proyecto,
    paginas: List<HojasDelProyecto.Pagina>,
    estado: LazyListState,
    filas: Int,
    pedir: Boolean
) {
    val contexto = LocalContext.current
    val pdf = remember(p.pdfOrigen, p.pdfLimpio) {
        Proyectos.rutaDelDocumento(p) { java.io.File(it).exists() }
    }
    if (pdf == null || !pedir) return
    val porFila = (paginas.size + filas - 1) / filas
    LaunchedEffect(pdf, porFila, filas, paginas.size) {
        snapshotFlow { estado.firstVisibleItemIndex }.collect { primera ->
            // **Las que se ven, no las que van seguidas.** La columna que hace
            // número x lleva las páginas x, x+porFila, x+2·porFila…, así que un
            // tramo seguido de la lista prepara la fila de arriba y páginas del
            // final que no está mirando nadie. Ver [HojasDelProyecto.enColumnas].
            PdfMiniaturas.preparar(
                contexto,
                pdf,
                HojasDelProyecto.enColumnas(
                    total = paginas.size,
                    filas = filas,
                    porFila = porFila,
                    desde = primera - 2,
                    hasta = primera + ANCHO_DE_TANDA
                ).mapNotNull { paginas[it].hoja.pagina },
                PdfDoc.THUMB_WIDTH
            )
        }
    }
}

/**
 * Abre una hoja para trabajarla.
 *
 * Fuera de la composición porque lo hacen dos sitios —la portada y la tira— y
 * tienen que abrir exactamente lo mismo: que tocar la hoja grande lleve a otro
 * lado que tocar su miniatura sería una trampa.
 */
private fun abrirHoja(
    contexto: android.content.Context,
    app: PixPinApp,
    p: Proyecto,
    pagina: HojasDelProyecto.Pagina
) {
    val h = pagina.hoja
    when {
        // La nota abre **por la hoja que se ha tocado**: el editor no tiene
        // páginas —el corte cambiaría con cada letra— pero sí sabe empezar por
        // donde estabas. Ver [MarkdownEditorActivity.abrir].
        h.nota != null -> MarkdownEditorActivity.abrir(
            contexto,
            h.id,
            h.nota!!,
            if (pagina.nota >= 0) {
                Paginado.deTexto(h.nota!!).getOrNull(pagina.nota)?.desde ?: -1
            } else {
                -1
            }
        )
        else -> {
            val dibujo = h.dibujo ?: "dib-${System.currentTimeMillis()}"
            if (h.dibujo == null) {
                app.proyectos.guardar(
                    Proyectos.conDibujo(p, h.id, dibujo, System.currentTimeMillis())
                )
            }
            val ruta = ExcalidrawStore.rutaDe(contexto, dibujo)
            if (p.pdfOrigen != null && h.pagina != null) {
                DrawEditorActivity.abrirPaginaDePdf(
                    contexto, dibujo, ruta, p.pdfOrigen!!, h.pagina!!
                )
            } else {
                DrawEditorActivity.abrir(contexto, dibujo, ruta, null)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HojaDelProyecto(
    app: PixPinApp,
    p: Proyecto,
    pagina: HojasDelProyecto.Pagina,
    marcada: Boolean,
    onMarcar: () -> Unit,
    /** Si esta hoja lleva algo dibujado encima. Ver [CapaDeAnotacion]. */
    anotada: Boolean = false,
    /** Si es la que la portada está enseñando ahora mismo. */
    enFoco: Boolean = false,
    /** Subirla a la portada. La tira elige, la portada abre. */
    onElegir: () -> Unit = {},
    /** Si toca dibujarla o basta con lo que hubiera en memoria. */
    pedirMiniatura: Boolean = true
) {
    val contexto = LocalContext.current
    val h = pagina.hoja
    val color = HojasDelProyecto.colorDe(h)
    // El foco manda sobre el color del lienzo: uno dice de dónde viene la hoja
    // —que no cambia nunca— y el otro dónde está el dedo ahora.
    val marco = when {
        enFoco -> MaterialTheme.colorScheme.primary
        color != null -> Color(color)
        else -> null
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(end = 8.dp, bottom = 6.dp)
            .width(ANCHO_DE_HOJA)
    ) {
        Box(
            Modifier
                .width(ANCHO_DE_HOJA)
                .height(ALTO_DE_HOJA)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                // **El marco dice de qué lienzo viene.** Con dos lienzos en el
                // mismo proyecto, el color separa sus láminas de un vistazo.
                .then(
                    if (marco != null) {
                        Modifier.border(2.dp, marco, RoundedCornerShape(6.dp))
                    } else {
                        Modifier
                    }
                )
                // **Un toque la sube a la portada; dos, la abren.**
                //
                // Tocar una miniatura de sesenta puntos para caer directamente en el
                // editor era mucho compromiso para un dedo que está buscando: a esa
                // escala uno reconoce la página pero no está seguro, y salir del editor
                // para volver a mirar cuesta más que la duda. El primer toque la enseña
                // en grande, que es donde se comprueba, y desde ahí un toque abre.
                .combinedClickable(
                    onClick = { onElegir() },
                    onDoubleClick = { abrirHoja(contexto, app, p, pagina) },
                    onLongClick = onMarcar
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                // Sin pinza para ampliar: aquí las hojas son diminutas y dos
                // dedos encima de una de ellas es casi siempre alguien
                // intentando desplazar la fila. Se amplía desde la portada, que
                // es donde una hoja ocupa lo bastante como para cogerla.
                p.pdfOrigen != null && h.pagina != null -> MiniaturaDePagina(
                    p.pdfOrigen!!, h.pagina!!, PdfDoc.THUMB_WIDTH,
                    ampliada = null, pedir = pedirMiniatura, dibujo = h.dibujo
                )

                // **La hoja de la nota, compuesta en pequeño.** Antes esto era un
                // recorte de las seis primeras líneas en crudo, que no se parecía
                // a lo que luego salía en el PDF. Ahora es el mismo pintor que el
                // pin y el mismo corte en páginas: lo que se ve aquí es la hoja
                // que se va a entregar.
                h.nota != null -> MiniaturaDeNota(pagina.texto ?: h.nota!!)

                else -> MiniaturaDeLienzo(contexto, h.dibujo, pagina.marco)
            }

            // **Un puntito si esa hoja lleva algo encima.**
            //
            // Una página anotada y una en blanco se ven casi iguales en una
            // miniatura de sesenta puntos: la anotación son cuatro trazos sobre
            // un plano lleno de líneas. Así se sabe por dónde ibas sin abrirlas
            // una a una, que es la pregunta que se le hace a esta pantalla.
            //
            // Solo en las páginas de un PDF: en un lienzo, tener dibujo no
            // significa nada —todos lo tienen— y el punto estaría siempre.
            //
            // Y **solo si de verdad hay algo pintado**: el punto salía por haber
            // abierto la página una vez, porque el editor guarda al salir aunque
            // no se haya trazado nada. Un indicador que se enciende solo no
            // informa. Ver [CapaDeAnotacion].
            if (h.pagina != null && anotada) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(7.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }

            if (marcada) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                )
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp)
                )
            }
        }

        Text(
            text = pagina.nombre,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * La miniatura de una hoja de nota.
 *
 * Se compone de verdad, con el pintor de siempre a letra diminuta: la tabla se
 * ve como tabla y el título como título. La rejilla encoge sus mínimos con la
 * letra —ver [RejillaDeTabla]—, que es lo que evita que una tabla de tres
 * columnas se salga cinco veces de la hoja.
 */
@Composable
private fun MiniaturaDeNota(texto: String, tamaño: Float = 3.2f) {
    val bloques = remember(texto) { Markdown.parse(texto) }
    MarkdownText(
        blocks = bloques,
        baseSizeSp = tamaño,
        modifier = Modifier.fillMaxSize().padding(if (tamaño > 5f) 14.dp else 4.dp)
    )
}

/**
 * La miniatura de un lienzo, o de uno de sus marcos.
 *
 * [escala] es a cuánto se dibuja la escena. En la tira basta con la vigésima
 * parte; en la portada se sube, que si no lo que se ve a pantalla completa es la
 * miniatura estirada — o sea, un borrón.
 */
@Composable
private fun MiniaturaDeLienzo(
    contexto: android.content.Context,
    dibujo: String?,
    marco: String?,
    escala: Double = 0.18,
    /**
     * Con quién se amplía al pellizcarla. Nulo en la tira, donde las hojas son sellos y
     * dos dedos encima son casi siempre alguien desplazando la fila.
     */
    ampliada: ZoomDeHoja? = null
) {
    if (dibujo == null) return
    var mapa by remember(dibujo, marco, escala) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val ruta = remember(dibujo) { ExcalidrawStore.rutaDe(contexto, dibujo) }
    val version = remember(ruta) { java.io.File(ruta).lastModified() }

    LaunchedEffect(ruta, marco, version, escala) {
        mapa = withContext(Dispatchers.IO) {
            runCatching {
                val escena = ExcalidrawStore.cargar(ruta) ?: return@runCatching null
                // Con marco, solo esa lámina: es la página que representa.
                val suyo = marco?.let { id -> escena.marcos.firstOrNull { it.id == id } }
                val suya = if (suyo == null) {
                    escena
                } else {
                    escena.copy(elements = escena.contenidoDe(suyo) + suyo)
                }
                // Las imágenes van por identificador y hay que ir a buscarlas: sin
                // esto, un lienzo con una foto dentro se previsualizaba sin la foto.
                DrawExport.aBitmap(suya, escala) { id ->
                    suya.files[id]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) }
                }
            }.getOrNull()
        }
    }
    val actual = mapa ?: return
    var hueco by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val ventana = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val alcance = androidx.compose.runtime.rememberCoroutineScope()
    Image(
        bitmap = actual.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { hueco = it.boundsInWindow() }
            .then(
                if (ampliada == null) Modifier else Modifier.pinzaParaAmpliar(
                    alCoger = {
                        val cogida = ampliada.coger(actual.asImageBitmap(), hueco, ventana)
                        if (cogida) {
                            // **La versión nítida, solo de la que se está sujetando.**
                            // Ampliar cuatro veces un dibujo compuesto al 60 % sería
                            // mirar píxeles estirados; se vuelve a componer en grande
                            // mientras los dedos siguen encima, y aparece al llegar.
                            alcance.launch {
                                val fina = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val escena = ExcalidrawStore.cargar(ruta)
                                            ?: return@runCatching null
                                        val suyo = marco?.let { id ->
                                            escena.marcos.firstOrNull { it.id == id }
                                        }
                                        val suya = if (suyo == null) escena
                                        else escena.copy(
                                            elements = escena.contenidoDe(suyo) + suyo
                                        )
                                        DrawExport.aBitmap(suya, ESCALA_AMPLIADA) { id ->
                                            suya.files[id]?.path?.let {
                                                com.forge.pixpin.pin.ImageStore.load(it)
                                            }
                                        }
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
}

/** A qué escala se recompone una hoja de lienzo cuando se amplía con los dedos. */
private const val ESCALA_AMPLIADA = 2.0

/**
 * Pregunta el nombre del proyecto.
 *
 * Es el mismo que llevará el PDF al exportar, así que cambiarlo aquí cambia lo
 * que le llega al que lo reciba. Ver [ExportarProyecto.nombreDeArchivo].
 */
@Composable
private fun DialogoDeNombre(
    actual: String,
    onCerrar: () -> Unit,
    onAceptar: (String) -> Unit
) {
    var texto by remember { mutableStateOf(actual) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(stringResourceSafe(R.string.proyecto_renombrar)) },
        text = {
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it.take(80) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onAceptar(texto.trim().ifBlank { actual }) }) {
                Text(stringResourceSafe(R.string.cd_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text(stringResourceSafe(R.string.cancel)) }
        }
    )
}

private fun avisar(contexto: android.content.Context, id: Int) {
    android.widget.Toast.makeText(contexto, id, android.widget.Toast.LENGTH_SHORT).show()
}

/** Manda el PDF a donde el usuario elija. */
private fun compartir(contexto: android.content.Context, archivo: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            contexto, "${contexto.packageName}.fileprovider", archivo
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        contexto.startActivity(
            android.content.Intent.createChooser(intent, archivo.name)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Cuántas columnas por delante se dejan hechas al desplazarse.
 *
 * Una pantalla y media: lo que se ve más lo que va a entrar en cuanto el dedo
 * empuje. Más sería dibujar páginas que a lo mejor nadie mira, y en un PDF largo
 * eso es justo lo que se venía a quitar.
 */
private const val ANCHO_DE_TANDA = 8

/**
 * A cuántos píxeles se dibuja la portada, por escalones.
 *
 * Redondear es lo que evita rehacer la página cada vez que cambia el hueco: dos
 * pantallas parecidas caen en el mismo paso y reaprovechan lo que ya estaba
 * dibujado.
 */
private val PASOS_DE_NITIDEZ = listOf(360, 720, 1080, 1440, 2000)

private val ANCHO_DE_HOJA = 76.dp
private val ALTO_DE_HOJA = 104.dp

/** La hoja más su etiqueta y su hueco: lo que ocupa una fila de la rejilla. */
private val ALTO_DE_FILA: Dp = ALTO_DE_HOJA + 24.dp

/**
 * Cuánto asoma la tarjeta siguiente por abajo.
 *
 * Lo justo para que se vea que hay otra y se lea el principio de su nombre. Más
 * sería una lista otra vez; menos, un borde que parece un margen.
 */
private val ASOMO: Dp = 46.dp

/** Hasta dónde se puede apartar la tarjeta, en anchos de tarjeta. */
private const val TOPE_DEL_DESLIZAMIENTO = 0.32f

/** A partir de dónde, al soltar, se abre la conversación. */
private const val UMBRAL_DEL_DESLIZAMIENTO = 0.2f

/** Con cuánto arrastre se ve del todo la pista del chat. Ver [PistaDeChat]. */
private val PISTA_ENTERA_A: Dp = 72.dp

/**
 * La miniatura de una página del PDF.
 *
 * Se dibuja fuera del hilo de la interfaz y **con la fecha del archivo en la
 * clave**: sin ella, anotar una página y volver aquí seguiría enseñando la de
 * antes, y parecería que no se guardó nada.
 *
 * Con [pedir] en falso no se dibuja nada: se enseña lo que hubiera en memoria y
 * ya. Es lo que hace que **la tira entera** del proyecto que asoma por debajo
 * del paginador no cueste nada —de ese solo se prepara la portada, y pequeña—.
 * En cuanto [pedir] se pone a cierto, el efecto se reinicia y la pide.
 */
@Composable
private fun MiniaturaDePagina(
    pdf: String,
    pagina: Int,
    ancho: Int = PdfDoc.THUMB_WIDTH,
    ampliada: ZoomDeHoja? = null,
    pedir: Boolean = true,
    /**
     * El dibujo anotado sobre esta página, si lo tiene.
     *
     * Sin él la miniatura enseñaba **la página limpia**: uno anotaba doce hojas y el
     * proyecto seguía pareciendo intacto, que es justo lo contrario de para qué sirve
     * una previsualización. La conversación ya lo hacía bien; esto lo iguala.
     */
    dibujo: String? = null
) {
    val contexto = LocalContext.current
    // **Se arranca con lo que ya hubiera en memoria**, aunque sea la miniatura
    // pequeña de la tira. Ampliada se ve borrosa un instante, pero se ve: el
    // hueco gris que había antes mientras llegaba la buena era lo que hacía
    // parecer que la portada «cargaba», cuando en realidad ya tenía la página.
    var mapa by remember(pdf, pagina, ancho) {
        mutableStateOf(
            PdfMiniaturas.enMemoria(pdf, pagina, ancho)
                ?: PdfMiniaturas.enMemoria(pdf, pagina, PdfDoc.THUMB_WIDTH)
        )
    }
    val rutaDelDibujo = remember(dibujo) {
        dibujo?.let { ExcalidrawStore.rutaDe(contexto, it) }
    }
    // La fecha del dibujo entra en la cuenta: al volver del editor, la miniatura se
    // recompone con lo que se acaba de anotar en vez de con lo de antes.
    val version = remember(pdf, rutaDelDibujo) {
        java.io.File(pdf).lastModified() +
            (rutaDelDibujo?.let { java.io.File(it).lastModified() } ?: 0L)
    }
    LaunchedEffect(pdf, pagina, ancho, version, pedir) {
        if (!pedir) return@LaunchedEffect
        // **Pasar hojas deprisa no puede encargar cuarenta páginas grandes.**
        //
        // La portada cambia con cada empujón de la tira, y dibujar una página a
        // mil píxeles no es gratis: [PdfMiniaturas] dibuja de uno en uno por
        // turno, así que cuarenta encargos se ponen en fila y le quitan el sitio
        // a las miniaturas de la propia tira, que son las que se están viendo.
        // Esperar un suspiro antes de pedirla convierte el barrido en un solo
        // encargo —el de la página donde el dedo se para—, porque al cambiar de
        // página este efecto se cancela y el de antes no llega a pedir nada.
        // Solo para lo grande: la miniatura de la tira sale de la caché.
        if (ancho > PdfDoc.THUMB_WIDTH) kotlinx.coroutines.delay(90)
        // [PdfMiniaturas.de] ya se va sola al hilo de disco, así que aquí no
        // hace falta envolver nada: memoria, disco y solo en último caso el PDF.
        runCatching {
            if (rutaDelDibujo != null) {
                com.forge.pixpin.guardados.paginaAnotada(
                    contexto, pdf, pagina, dibujo, rutaDelDibujo, ancho
                )
            } else {
                PdfMiniaturas.de(contexto, pdf, pagina, ancho)
            }
        }.getOrNull()?.let { mapa = it }
    }

    val actual = mapa ?: return
    val alcance = rememberCoroutineScope()
    var hueco by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val ventana = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize

    Image(
        bitmap = actual.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { hueco = it.boundsInWindow() }
            .then(
                if (ampliada == null) Modifier else Modifier.pinzaParaAmpliar(
                    alCoger = {
                        val cogida = ampliada.coger(actual.asImageBitmap(), hueco, ventana)
                        if (cogida) {
                            // La versión nítida se pide **solo de la que se está
                            // sujetando**: es la única página del documento que
                            // se va a ver a pantalla completa.
                            alcance.launch {
                                PdfMiniaturas.de(contexto, pdf, pagina, PdfDoc.ZOOM_WIDTH)
                                    ?.let { ampliada.afinar(it.asImageBitmap()) }
                            }
                        }
                        cogida
                    },
                    alMover = { zoom, pan, giro, foco -> ampliada.mover(zoom, pan, giro, foco) },
                    alSoltar = { alcance.launch { ampliada.soltar() } }
                )
            )
    )
}

/** `stringResource` sin arrastrar el import a cada línea. */
@Composable
private fun stringResourceSafe(id: Int, vararg args: Any): String =
    if (args.isEmpty()) androidx.compose.ui.res.stringResource(id)
    else androidx.compose.ui.res.stringResource(id, *args)
