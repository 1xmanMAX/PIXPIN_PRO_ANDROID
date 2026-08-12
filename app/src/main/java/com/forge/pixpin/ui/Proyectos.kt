package com.forge.pixpin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import com.forge.pixpin.motor.ExportarProyecto
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.Color
import com.forge.pixpin.motor.DrawExport
import com.forge.pixpin.motor.HojasDelProyecto
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Draw
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.forge.pixpin.markdown.Menus
import com.forge.pixpin.motor.PdfDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import com.forge.pixpin.markdown.MarkdownEditorActivity
import com.forge.pixpin.markdown.Paginado
import com.forge.pixpin.motor.DrawEditorActivity
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos

/**
 * Vuelve a poner en pantalla el PDF de un proyecto.
 *
 * Un pin cerrado no se pierde —el archivo sigue donde estaba y el proyecto
 * sabe dónde— pero sin esto había que buscarlo otra vez con el gestor de
 * archivos y volver a compartirlo a PixPin. El proyecto es lo que sabe de qué
 * documento se trata, así que es desde donde tiene que poder volver.
 */
private fun volverAPinear(app: PixPinApp, p: Proyecto) {
    val ruta = p.pdfOrigen ?: return
    app.overlayManager.pinFile(ruta, p.nombre, "application/pdf")
}

/**
 * La lista de proyectos: **por dónde se vuelve a lo empezado**.
 *
 * Sin ella el motor del PDF era fontanería sin grifo — todo construido y sin
 * forma de llegar. Es lo único que hacía falta para que anotar un plano de doce
 * hojas sea algo que se pueda hacer en dos ratos y no de una sentada.
 *
 * ## Poco, y en un orden
 *
 * Lo que está en marcha arriba y lo reciente primero; lo archivado al fondo, no
 * en otra pantalla — son pocos, y esconderlos detrás de un filtro obliga a
 * acordarse de que existe el filtro.
 *
 * De cada proyecto se dice **su nombre y cuántas hojas lleva anotadas**, que es
 * lo único que uno quiere saber de un vistazo: por dónde iba. Lo demás —cambiar
 * el orden, renombrar— se toca dentro, no aquí.
 */
@Composable
fun PantallaDeProyectos(app: PixPinApp, onVolver: () -> Unit) {
    val proyectos by app.proyectos.proyectos.collectAsState()
    val ordenados = Proyectos.ordenados(proyectos)

    Scaffold { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onVolver) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResourceSafe(R.string.cd_close)
                    )
                }
                Text(
                    stringResourceSafe(R.string.proyectos_titulo),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
                val plantilla = stringResourceSafe(R.string.proyecto_nuevo_nombre)
                IconButton(
                    onClick = {
                        app.proyectos.nuevo(plantilla, System.currentTimeMillis())
                    }
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResourceSafe(R.string.proyecto_nuevo)
                    )
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

            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 4.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ordenados, key = { it.id }) { p -> FilaDeProyecto(app, p) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FilaDeProyecto(app: PixPinApp, p: Proyecto) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    // Lo marcado para exportar, por la clave de cada página. Vive aquí y no en
    // el proyecto: es una decisión del momento, no algo que haya que guardar.
    var marcadas by remember(p.id) { mutableStateOf(setOf<String>()) }
    var renombrando by remember { mutableStateOf(false) }
    var exportando by remember { mutableStateOf(false) }
    val tick = remember(p) { p.tocado }
    val anotadas = Proyectos.anotadas(p).size
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        p.nombre,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (p.archivado) FontWeight.Normal else FontWeight.SemiBold,
                        color =
                            if (p.archivado) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        // Lo único que uno quiere saber de un vistazo: por dónde
                        // iba. Con un PDF detrás se dice de cuántas, que es lo
                        // que convierte «tres hojas» en «tres de doce».
                        if (p.pdfOrigen != null) {
                            stringResourceSafe(R.string.proyecto_hojas_de, anotadas, p.hojas.size)
                        } else {
                            stringResourceSafe(R.string.proyecto_hojas, p.hojas.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // **Con su nombre escrito.** Un icono suelto en una fila no dice lo
            // que hace: el usuario preguntó qué era ese botón, y tenía razón —
            // «archivar» y «borrar» se parecen lo bastante como para que
            // adivinarlo salga caro una vez.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.pdfOrigen != null) {
                    TextButton(onClick = { volverAPinear(app, p) }) {
                        Icon(Icons.Filled.PushPin, contentDescription = null, Modifier.size(16.dp))
                        Text(
                            stringResourceSafe(R.string.proyecto_pinear),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        app.proyectos.guardar(
                            Proyectos.archivado(p, !p.archivado, System.currentTimeMillis())
                        )
                    }
                ) {
                    Icon(
                        if (p.archivado) Icons.Filled.Unarchive else Icons.Filled.Archive,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResourceSafe(
                            if (p.archivado) R.string.proyecto_desarchivar
                            else R.string.proyecto_archivar
                        ),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                TextButton(onClick = { renombrando = true }) {
                    Icon(
                        Icons.Filled.Edit, contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResourceSafe(R.string.proyecto_renombrar),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                // Exportar solo lo marcado, con el nombre del proyecto.
                TextButton(
                    onClick = {
                        if (marcadas.isEmpty()) {
                            avisar(contexto, R.string.proyecto_nada_marcado)
                        } else {
                            exportando = true
                        }
                    }
                ) {
                    Icon(
                        Icons.Filled.Share, contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResourceSafe(R.string.proyecto_exportar),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                TextButton(onClick = { app.proyectos.borrar(p.id) }) {
                    Icon(
                        Icons.Filled.Delete, contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        stringResourceSafe(R.string.cd_delete),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // Las hojas, en su orden: el mismo con el que saldrán del PDF.
            //
            // En **tira horizontal y con miniatura**, no en lista. Una lista de
            // nombres de un PDF de cuarenta páginas es cuarenta renglones que
            // dicen «página 1, página 2…» y ocupan la pantalla entera sin
            // enseñar nada; una tira de miniaturas cabe en un dedo de alto y
            // deja ver por dónde vas de un vistazo.
            if (p.hojas.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // **Las filas crecen con el documento.** Dos para un
                // proyecto corto y hasta cuatro para un PDF largo: repartir
                // cuarenta páginas en dos filas obliga a desplazarse veinte
                // hojas para llegar al final, y en cuatro son diez.
                val paginas = remember(p, tick) {
                    HojasDelProyecto.paginas(p) { dibujo ->
                        ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, dibujo))
                    }
                }
                val filas = when {
                    paginas.size <= 8 -> 2
                    paginas.size <= 21 -> 3
                    else -> 4
                }
                val porFila = (paginas.size + filas - 1) / filas

                Column(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    (0 until filas).forEach { f ->
                        val trozo = paginas.drop(f * porFila).take(porFila)
                        if (trozo.isEmpty()) return@forEach
                        Row {
                            trozo.forEach { pagina ->
                                HojaDelProyecto(
                                    app = app,
                                    p = p,
                                    pagina = pagina,
                                    marcada = pagina.clave in marcadas,
                                    onMarcar = {
                                        marcadas = if (pagina.clave in marcadas) {
                                            marcadas - pagina.clave
                                        } else {
                                            marcadas + pagina.clave
                                        }
                                    }
                                )
                            }
                        }
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

            if (exportando) {
                LaunchedEffect(marcadas) {
                    val archivo = withContext(Dispatchers.IO) {
                        ExportarProyecto.aArchivo(contexto, p, marcadas, { dibujo ->
                            ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, dibujo))
                        })
                    }
                    exportando = false
                    if (archivo == null) {
                        avisar(contexto, R.string.pdf_no_se_pudo)
                    } else {
                        compartir(contexto, archivo)
                    }
                }
            }

            if (p.pdfOrigen == null) {
                TextButton(
                    onClick = {
                        val ahora = System.currentTimeMillis()
                        val hoja = Hoja(id = "h-$ahora", dibujo = "dib-$ahora")
                        app.proyectos.guardar(Proyectos.conHoja(p, hoja, ahora))
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                    Text(
                        stringResourceSafe(R.string.proyecto_hoja_nueva),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HojaDelProyecto(
    app: PixPinApp,
    p: Proyecto,
    pagina: HojasDelProyecto.Pagina,
    marcada: Boolean,
    onMarcar: () -> Unit
) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val h = pagina.hoja
    val color = HojasDelProyecto.colorDe(h)

    val abrir: () -> Unit = {
        when {
            h.nota != null -> MarkdownEditorActivity.abrir(contexto, h.id, h.nota!!)
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
                    if (color != null) {
                        Modifier.border(2.dp, Color(color), RoundedCornerShape(6.dp))
                    } else {
                        Modifier
                    }
                )
                // Un toque abre; una pulsación larga marca para exportar. Abrir
                // es lo que se hace casi siempre, así que se lleva el toque.
                .combinedClickable(onClick = abrir, onLongClick = onMarcar),
            contentAlignment = Alignment.Center
        ) {
            when {
                p.pdfOrigen != null && h.pagina != null ->
                    MiniaturaDePagina(p.pdfOrigen!!, h.pagina!!)

                h.nota != null -> Text(
                    text = h.nota!!.lineSequence()
                        .filter { it.isNotBlank() }
                        .take(6)
                        .joinToString("\n") { Menus.convertir(it, null).take(24) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(6.dp)
                )

                else -> MiniaturaDeLienzo(contexto, h.dibujo, pagina.marco)
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
            } else if (h.dibujo != null && h.pagina != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            androidx.compose.foundation.shape.CircleShape
                        )
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

/** La miniatura de un lienzo, o de uno de sus marcos. */
@Composable
private fun MiniaturaDeLienzo(
    contexto: android.content.Context,
    dibujo: String?,
    marco: String?
) {
    if (dibujo == null) return
    var mapa by remember(dibujo, marco) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val ruta = remember(dibujo) { ExcalidrawStore.rutaDe(contexto, dibujo) }
    val version = remember(ruta) { java.io.File(ruta).lastModified() }

    LaunchedEffect(ruta, marco, version) {
        mapa = withContext(Dispatchers.IO) {
            runCatching {
                val escena = ExcalidrawStore.cargar(ruta) ?: return@runCatching null
                // Con marco, solo esa lámina: es la página que representa.
                val suya = if (marco == null) {
                    escena
                } else {
                    escena.copy(elements = escena.elements.filter { e ->
                        e.id == marco || escena.marcos.none { it.id == marco } ||
                            escena.contenidoDe(escena.marcos.first { it.id == marco })
                                .any { it.id == e.id }
                    })
                }
                DrawExport.aBitmap(suya, 0.18)
            }.getOrNull()
        }
    }
    mapa?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

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

/** Lo que mide cada hoja en la tira. Un folio en pequeño. */
private val ANCHO_DE_HOJA = 76.dp
private val ALTO_DE_HOJA = 104.dp

/**
 * La miniatura de una página del PDF.
 *
 * Se dibuja fuera del hilo de la interfaz y **con la fecha del archivo en la
 * clave**: sin ella, anotar una página y volver aquí seguiría enseñando la de
 * antes, y parecería que no se guardó nada.
 */
@Composable
private fun MiniaturaDePagina(pdf: String, pagina: Int) {
    var mapa by remember(pdf, pagina) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val version = remember(pdf) { java.io.File(pdf).lastModified() }
    LaunchedEffect(pdf, pagina, version) {
        mapa = withContext(Dispatchers.IO) {
            runCatching { PdfDoc.render(pdf, pagina, PdfDoc.THUMB_WIDTH) }.getOrNull()
        }
    }
    mapa?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** `stringResource` sin arrastrar el import a cada línea. */
@Composable
private fun stringResourceSafe(id: Int, vararg args: Any): String =
    if (args.isEmpty()) androidx.compose.ui.res.stringResource(id)
    else androidx.compose.ui.res.stringResource(id, *args)
