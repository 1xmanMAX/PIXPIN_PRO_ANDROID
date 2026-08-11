package com.forge.pixpin.markdown

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * El editor avanzado de notas, hermano del editor avanzado de dibujo.
 *
 * ## Qué se copia de Telegram y qué no
 *
 * Se copia **toda la organización**: los mismos formatos, en el orden de su
 * `FloatingToolbar.STYLE_BUTTONS`, repartidos entre panel principal y
 * desbordamiento como su `FloatingToolbarPopup`, con los botones encendidos
 * según lo que cubra la selección y alternando por cobertura como su
 * `toggleStyleForSelection`. Y su diálogo de enlace, con el botón de pegar que
 * solo asoma cuando hay algo que pegar. Todo eso vive en [BarraDeFormatoUi] y en
 * [MarkdownEdit], compartido con la nota flotante.
 *
 * No se copia una cosa: que el suyo sea **wysiwyg**. En Telegram nunca ves un
 * asterisco; el formato va en spans, aparte del texto. Aquí el texto que se
 * escribe **es** lo que se guarda, y de él viven la exportación, el PDF y el
 * SVG. Convertir spans a Markdown y de vuelta en cada pulsación metería un
 * traductor entre el dedo y el archivo, y ese traductor es justo donde se pierde
 * lo que el usuario escribió. Así que aquí se escribe Markdown y se ve el
 * resultado al lado, con el botón de arriba.
 */
class MarkdownEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val inicial = intent.getStringExtra(EXTRA_TEXTO).orEmpty()

        setContent {
            PixPinTheme {
                Pantalla(
                    inicial = inicial,
                    onGuardar = { texto ->
                        if (id.isNotEmpty()) TextoStore.guardar(id, texto)
                        finish()
                    },
                    onDescartar = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "md_id"
        private const val EXTRA_TEXTO = "md_texto"

        /**
         * Abre la nota [id] con [texto].
         *
         * El texto va en el intent y no se lee de disco porque el pin puede
         * tener cambios sin guardar en el momento de abrir, y empezar a editar
         * perdiendo lo último escrito es el peor estreno posible. Al cerrar
         * vuelve por [TextoStore].
         */
        fun abrir(context: Context, id: String, texto: String) {
            val i = Intent(context, MarkdownEditorActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TEXTO, texto)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Pantalla(
    inicial: String,
    onGuardar: (String) -> Unit,
    onDescartar: () -> Unit
) {
    var valor by remember {
        mutableStateOf(TextFieldValue(inicial, TextRange(inicial.length)))
    }
    var pidiendoUrl by remember { mutableStateOf(false) }
    var viendoCatalogo by remember { mutableStateOf(false) }
    var pidiendoArchivo by remember { mutableStateOf<TipoDeBloque?>(null) }
    var viendoAdjuntar by remember { mutableStateOf(false) }

    // Deshacer y rehacer, como su `historyButtons`. Se anota cada cambio y el
    // propio historial decide qué agrupar; ver [Historial].
    val historial = remember { Historial().also { it.empezar(inicial) } }
    var pasos by remember { mutableIntStateOf(0) }

    fun cambia(nuevo: TextFieldValue) {
        if (nuevo.text != valor.text) {
            historial.anota(nuevo.text)
            pasos++
        }
        valor = nuevo
    }

    val contexto = LocalContext.current
    val selector = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val tipo = pidiendoArchivo
        pidiendoArchivo = null
        if (uri == null || tipo == null) return@rememberLauncherForActivityResult
        val ruta = Adjuntos.importar(contexto, uri, System.currentTimeMillis())
        if (ruta == null) {
            Toast.makeText(contexto, "No se pudo copiar el archivo", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        // La plantilla deja el cursor entre los paréntesis, así que la ruta se
        // escribe justo ahí y el bloque queda entero de una vez.
        val r = Comandos.elegir(valor.text, valor.selection.start, tipo)
        val conRuta = r.text.substring(0, r.selStart) + ruta + r.text.substring(r.selStart)
        val fin = r.selStart + ruta.length
        cambia(TextFieldValue(conRuta, TextRange(fin)))
    }

    LaunchedEffect(pidiendoArchivo) {
        val tipo = pidiendoArchivo ?: return@LaunchedEffect
        selector.launch(arrayOf(filtroDe(tipo)))
    }

    // Lo tecleado tras una barra, si es que se está tecleando un comando.
    val consulta = remember(valor.text, valor.selection) {
        if (valor.selection.collapsed) {
            Comandos.consulta(valor.text, valor.selection.start)
        } else {
            null
        }
    }

    /** El texto del bloque donde está el cursor. */
    fun trozoDelCursor(v: TextFieldValue): String? {
        val trozos = trozosDe(v.text)
        val i = trozoEn(trozos, v.selection.start)
        return if (i < 0) null else trozos[i].de(v.text)
    }

    /** Cambia de tipo el bloque de debajo del cursor, conservando lo escrito. */
    fun convertirBloque(tipo: TipoDeBloque?) {
        val trozos = trozosDe(valor.text)
        val i = trozoEn(trozos, valor.selection.start)
        if (i < 0) return
        val trozo = trozos[i]
        val fuente = trozo.de(valor.text)
        // Los saltos del final son del documento, no del bloque: si entran en
        // la conversión, cada cambio de tipo se los va comiendo.
        val cola = fuente.takeLastWhile { it == '\n' }
        val nuevo = Menus.convertir(fuente.trimEnd('\n'), tipo) + cola
        val entero = valor.text.substring(0, trozo.desde) + nuevo +
            valor.text.substring(trozo.hasta)
        cambia(TextFieldValue(entero, TextRange(trozo.desde + nuevo.trimEnd('\n').length)))
    }

    fun enLaTabla(op: OpTabla) {
        val trozos = trozosDe(valor.text)
        val i = trozoEn(trozos, valor.selection.start)
        if (i < 0) return
        val trozo = trozos[i]
        val fuente = trozo.de(valor.text)
        val cola = fuente.takeLastWhile { it == '\n' }
        val tabla = fuente.trimEnd('\n')
        val local = (valor.selection.start - trozo.desde).coerceIn(0, tabla.length)

        val nueva = when (op) {
            OpTabla.FILA_MAS -> Tablas.añadirFila(tabla, Tablas.filaDe(tabla, local))
            OpTabla.FILA_MENOS -> Tablas.quitarFila(tabla, Tablas.filaDe(tabla, local))
            OpTabla.COLUMNA_MAS -> Tablas.añadirColumna(tabla, Tablas.columnaDe(tabla, local))
            OpTabla.COLUMNA_MENOS -> Tablas.quitarColumna(tabla, Tablas.columnaDe(tabla, local))
            OpTabla.ALINEAR -> Tablas.rotarAlineacion(tabla, Tablas.columnaDe(tabla, local))
        }
        val entero = valor.text.substring(0, trozo.desde) + nueva + cola +
            valor.text.substring(trozo.hasta)
        cambia(
            TextFieldValue(
                entero,
                TextRange((trozo.desde + local).coerceIn(0, entero.length))
            )
        )
    }

    /** Sale de la tabla dejando un renglón nuevo detrás, listo para escribir. */
    fun fueraDeLaTabla() {
        val trozos = trozosDe(valor.text)
        val i = trozoEn(trozos, valor.selection.start)
        if (i < 0) return
        val fin = trozos[i].hasta
        val entero = if (fin >= valor.text.length) valor.text + "\n" else valor.text
        cambia(TextFieldValue(entero, TextRange(entero.length.coerceAtMost(fin + 1))))
    }

    fun insertarBloque(tipo: TipoDeBloque) {
        if (Bloques.pideArchivo(tipo)) {
            pidiendoArchivo = tipo
            return
        }
        val r = Comandos.elegir(valor.text, valor.selection.start, tipo)
        cambia(TextFieldValue(r.text, TextRange(r.selStart, r.selEnd)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nota") },
                navigationIcon = {
                    IconButton(onClick = onDescartar) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Descartar"
                        )
                    }
                },
                actions = {
                    // Deshacer y rehacer arriba a la derecha, como su
                    // `historyButtons`. `pasos` está solo para que Compose sepa
                    // que hay que volver a mirar si están habilitados: el
                    // historial es un objeto normal y no un estado observable.
                    @Suppress("UNUSED_EXPRESSION") pasos
                    IconButton(
                        onClick = {
                            historial.deshacer(valor.text)?.let {
                                valor = TextFieldValue(it, TextRange(it.length))
                                pasos++
                            }
                        },
                        enabled = historial.puedeDeshacer
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Deshacer")
                    }
                    IconButton(
                        onClick = {
                            historial.rehacer(valor.text)?.let {
                                valor = TextFieldValue(it, TextRange(it.length))
                                pasos++
                            }
                        },
                        enabled = historial.puedeRehacer
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Rehacer")
                    }

                    IconButton(onClick = { onGuardar(valor.text) }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Guardar",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { hueco ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(hueco)
                .imePadding()
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                EditorVivo(valor = valor, onValor = { cambia(it) })
            }

            run {
                // La lista de comandos va **encima** de la barra y por delante
                // del teclado: es lo que estás mirando mientras tecleas.
                val sugerencias = remember(consulta) {
                    if (consulta == null) emptyList() else Bloques.buscar(consulta!!)
                }
                if (sugerencias.isNotEmpty()) {
                    ListaDeComandos(sugerencias) { tipo -> insertarBloque(tipo) }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // El cambio de panel es suyo: sin selección no hay nada que
                    // poner en negrita, y lo que quieres es empezar un bloque.
                    val enTabla = remember(valor.text, valor.selection) {
                        trozoDelCursor(valor)?.let { Tablas.esTabla(it) } == true
                    }
                    if (enTabla) {
                        // Dentro de una tabla la barra es de tabla: es lo que se
                        // necesita ahí y nada más. Su editor hace lo mismo.
                        BarraDeTablaUi(
                            onOperacion = { op -> enLaTabla(op) },
                            onSalir = { fueraDeLaTabla() }
                        )
                    } else if (valor.selection.collapsed) {
                        BarraDeFamiliasUi(
                            tipoActual = remember(valor.text, valor.selection) {
                                trozoDelCursor(valor)?.let { Menus.tipoDe(it) }
                            },
                            onConvertir = { tipo -> convertirBloque(tipo) },
                            onInsertar = { tipo -> insertarBloque(tipo) },
                            onCatalogo = { viendoCatalogo = true }
                        )
                    } else {
                        BarraDeFormatoUi(
                            valor = valor,
                            onValor = { cambia(it) },
                            onPedirUrl = { pidiendoUrl = true }
                        )
                    }
                }
            }
        }
    }

    if (viendoAdjuntar) {
        HojaDeAdjuntar(
            onCerrar = { viendoAdjuntar = false },
            onElegir = { tipo ->
                viendoAdjuntar = false
                insertarBloque(tipo)
            }
        )
    }

    if (viendoCatalogo) {
        CatalogoDeBloques(
            onCerrar = { viendoCatalogo = false },
            onElegir = { tipo ->
                viendoCatalogo = false
                insertarBloque(tipo)
            }
        )
    }

    if (pidiendoUrl) {
        DialogoDeEnlace(
            onCerrar = { pidiendoUrl = false },
            onAceptar = { url ->
                cambia(conEnlace(valor, url))
                pidiendoUrl = false
            }
        )
    }
}

/**
 * El diálogo de enlace de Telegram (`EditTextCaption.makeSelectedUrl`).
 *
 * Dos detalles suyos que valen lo que cuestan: el campo empieza con `https://`
 * escrito, y hay un **botón de pegar que solo asoma cuando el campo está intacto
 * y el portapapeles tiene algo**. El camino real es copiar la dirección en el
 * navegador, volver, seleccionar la palabra y darle a enlace; con el botón eso
 * son dos toques y sin él es abrir el teclado y buscar el menú de pegar.
 */
@Composable
private fun DialogoDeEnlace(onCerrar: () -> Unit, onAceptar: (String) -> Unit) {
    val contexto = LocalContext.current
    val porDefecto = "https://"
    var url by remember { mutableStateOf(porDefecto) }

    val hayQuePegar = remember(url) {
        if (url.isNotEmpty() && url != porDefecto) {
            false
        } else {
            val cb = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.hasPrimaryClip() == true
        }
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Enlace") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("Dirección") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (hayQuePegar) {
                    TextButton(onClick = {
                        val cb = contexto.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager
                        val texto = runCatching {
                            cb?.primaryClip?.getItemAt(0)?.coerceToText(contexto)?.toString()
                        }.getOrNull()
                        if (!texto.isNullOrEmpty()) url = texto
                    }) {
                        Text("Pegar")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Solo el prefijo no es una dirección: se trata como vacío y el
                // enlace queda con los paréntesis listos para rellenar.
                onAceptar(if (url == porDefecto) "" else url.trim())
            }) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}

/** Qué se le pide al selector de archivos según el bloque. */
private fun filtroDe(tipo: TipoDeBloque): String = when (tipo) {
    TipoDeBloque.IMAGEN -> "image/*"
    TipoDeBloque.VIDEO -> "video/*"
    TipoDeBloque.AUDIO -> "audio/*"
    else -> "*/*"
}

/**
 * La lista que sale al teclear `/`, como su `RichCommandSuggestions`.
 *
 * Cada fila lleva el icono, el nombre y **el atajo a la derecha**, tal como la
 * suya (`RichCommand.View`: icono, texto, espacio elástico, texto2). Ese atajo a
 * la derecha es lo que hace que la lista se deje de usar: la ves tres veces,
 * aprendes que la tabla es `/tabla`, y a la cuarta ya no miras.
 *
 * Va apoyada abajo y crece hacia arriba, porque nace justo encima de la barra.
 */
@Composable
private fun ListaDeComandos(bloques: List<Bloque>, onElegir: (TipoDeBloque) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(max = 220.dp)
    ) {
        LazyColumn {
            items(bloques) { bloque ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onElegir(bloque.tipo) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = iconoDeBloque(bloque.tipo),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(bloque.nombre, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = bloque.atajos.first(),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * El catálogo entero, tras el `+`.
 *
 * Es la misma lista que sale tecleando, y a propósito: quien la abre por el
 * botón ve el atajo de cada bloque a la derecha y la próxima vez ya no necesita
 * el botón. Enseñar el atajo donde se busca la función es lo que convierte un
 * menú en algo que se deja de usar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogoDeBloques(onCerrar: () -> Unit, onElegir: (TipoDeBloque) -> Unit) {
    ModalBottomSheet(onDismissRequest = onCerrar) {
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(Bloques.todos) { bloque ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onElegir(bloque.tipo) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = iconoDeBloque(bloque.tipo),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(bloque.nombre, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = bloque.atajos.first(),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

/**
 * La hoja de adjuntar, tras el botón propio de la barra.
 *
 * Telegram abre aquí su `ChatAttachAlertRichLayout`, una rejilla con la galería
 * dentro. Esta es más sosa a propósito: son cuatro filas que llevan al selector
 * del sistema. Enseñar la galería dentro de la nota obligaría a pedir permiso de
 * fotos para poder adjuntar un PDF, y el selector del sistema no pide ninguno.
 *
 * Las cuatro son las suyas —foto, vídeo, audio— más el archivo suelto, que en un
 * documento hace más falta que en un artículo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaDeAdjuntar(onCerrar: () -> Unit, onElegir: (TipoDeBloque) -> Unit) {
    val opciones = listOf(
        TipoDeBloque.IMAGEN,
        TipoDeBloque.VIDEO,
        TipoDeBloque.AUDIO,
        TipoDeBloque.ARCHIVO
    )
    ModalBottomSheet(onDismissRequest = onCerrar) {
        Column(Modifier.navigationBarsPadding()) {
            opciones.forEach { tipo ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onElegir(tipo) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = iconoDeBloque(tipo),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(Bloques.de(tipo).nombre, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
