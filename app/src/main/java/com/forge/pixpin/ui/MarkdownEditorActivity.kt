package com.forge.pixpin.ui

import com.forge.pixpin.motormd.Adjuntos
import com.forge.pixpin.motormd.BarraDeEstilosUi
import com.forge.pixpin.motormd.BarraDeFamiliasUi
import com.forge.pixpin.motormd.Bloque
import com.forge.pixpin.motormd.Bloques
import com.forge.pixpin.motormd.Comandos
import com.forge.pixpin.motormd.EditorVivo
import com.forge.pixpin.motormd.Historial
import com.forge.pixpin.motormd.Inline
import com.forge.pixpin.motormd.InlineText
import com.forge.pixpin.motormd.Menus
import com.forge.pixpin.motormd.Sitio
import com.forge.pixpin.motormd.SpanKind
import com.forge.pixpin.motormd.TextoStore
import com.forge.pixpin.motormd.TipoDeBloque
import com.forge.pixpin.motormd.Vivo
import com.forge.pixpin.motormd.conEnlace
import com.forge.pixpin.motormd.iconoDeBloque
import com.forge.pixpin.motormd.trozoEn
import com.forge.pixpin.motormd.trozosDe

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
import androidx.compose.material.icons.filled.LibraryAdd
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyectos
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * El editor avanzado de notas, hermano del editor avanzado de dibujo.
 *
 * Vive **fuera del motor MD** a propósito: una actividad sabe de proyectos, de
 * temas y de la aplicación entera, y el motor no tiene por qué. Esto es la
 * carcasa —abre, guarda, manda a un proyecto— y todo lo que interpreta, compone
 * y edita Markdown está en `motormd`, que se puede sacar y usar en otro sitio.
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
        val desde = intent.getIntExtra(EXTRA_DESDE, -1)

        setContent {
            PixPinTheme {
                Pantalla(
                    inicial = inicial,
                    desde = desde,
                    onGuardar = { texto ->
                        if (id.isNotEmpty()) TextoStore.guardar(id, texto)
                        guardarEnSuProyecto(id, texto)
                        finish()
                    },
                    onDescartar = { finish() },
                    onAProyecto = { texto -> aUnProyecto(texto) }
                )
            }
        }
    }

    /**
     * Si esta nota **es** una hoja de un proyecto, deja lo escrito dentro.
     *
     * El editor no sabe de dónde le abrieron —recibe un identificador y un
     * texto—, así que se busca por identificador. Es barato: son unos pocos
     * proyectos con unas pocas hojas, y a cambio da igual desde dónde se haya
     * entrado. Sin esto, abrir una nota desde el proyecto y guardarla escribía
     * en el almacén del pin y el proyecto seguía enseñando lo de antes.
     */
    private fun guardarEnSuProyecto(id: String, texto: String) {
        if (id.isEmpty()) return
        val app = application as? PixPinApp ?: return
        val ahora = System.currentTimeMillis()
        app.proyectos.proyectos.value
            .filter { p -> p.hojas.any { it.id == id && it.nota != null } }
            .forEach { p -> app.proyectos.guardar(Proyectos.conNota(p, id, texto, ahora)) }
    }

    /**
     * Mete la nota como una hoja del proyecto en curso.
     *
     * Va al que se esté usando —el mismo criterio que sigue una hoja dibujada—,
     * y si no hay ninguno se abre uno. Así mandar una nota a un proyecto es un
     * toque y no un paseo por una lista.
     */
    private fun aUnProyecto(texto: String) {
        val app = application as? PixPinApp ?: return
        val ahora = System.currentTimeMillis()
        val proyecto = Proyectos.enCurso(app.proyectos.proyectos.value)
            ?: app.proyectos.nuevo(getString(R.string.proyecto_nuevo_nombre), ahora)

        val titulo = texto.lineSequence().firstOrNull { it.isNotBlank() }
            ?.let { Menus.convertir(it, null) }
            ?.take(40)
            .orEmpty()

        app.proyectos.guardar(
            Proyectos.conHoja(
                proyecto,
                Hoja(id = "n-$ahora", nombre = titulo, nota = texto),
                ahora
            )
        )
        Toast.makeText(this, R.string.nota_a_proyecto, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_ID = "md_id"
        private const val EXTRA_TEXTO = "md_texto"
        private const val EXTRA_DESDE = "md_desde"

        /**
         * Abre la nota [id] con [texto], opcionalmente por la letra [desde].
         *
         * El texto va en el intent y no se lee de disco porque el pin puede
         * tener cambios sin guardar en el momento de abrir, y empezar a editar
         * perdiendo lo último escrito es el peor estreno posible. Al cerrar
         * vuelve por [TextoStore].
         *
         * [desde] es por dónde se estaba leyendo —el principio de una página—
         * y solo sirve para **empezar ahí**: el editor sigue siendo un rollo
         * seguido, sin hojas. Ver el visor de páginas de la nota flotante.
         */
        fun abrir(context: Context, id: String, texto: String, desde: Int = -1) {
            val i = Intent(context, MarkdownEditorActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TEXTO, texto)
                .putExtra(EXTRA_DESDE, desde)
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
    onDescartar: () -> Unit,
    onAProyecto: (String) -> Unit = {},
    desde: Int = -1
) {
    var valor by remember {
        mutableStateOf(TextFieldValue(inicial, TextRange(inicial.length)))
    }
    // Dónde se está escribiendo, en coordenadas del texto limpio. Ver [Sitio].
    //
    // Si se entró desde una página de la nota, se empieza **en su primer
    // bloque**: al quedar activo pide el foco, y un campo con el foco se trae
    // solo a la vista. Así el editor abre por donde se estaba leyendo sin tener
    // que saber nada de páginas ni medir la pantalla.
    var sitio by remember {
        mutableStateOf(
            if (desde >= 0) {
                trozosDe(inicial).takeIf { it.isNotEmpty() }
                    ?.let { Sitio(bloque = trozoEn(it, desde)) }
            } else {
                null
            }
        )
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
        val donde = sitio?.let { trozosDe(valor.text).getOrNull(it.bloque)?.hasta }
            ?: valor.text.length
        val r = Comandos.elegir(valor.text, donde, tipo)
        val conRuta = r.text.substring(0, r.selStart) + ruta + r.text.substring(r.selStart)
        cambia(valor.copy(text = conRuta))
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

    /** Cambia de tipo el bloque activo, conservando lo escrito. */
    fun convertirBloque(tipo: TipoDeBloque?) {
        val s2 = sitio ?: return
        val trozos = trozosDe(valor.text)
        val trozo = trozos.getOrNull(s2.bloque) ?: return
        val fuente = trozo.de(valor.text)
        val cola = fuente.takeLastWhile { it == '\n' }
        val nuevo = Menus.convertir(fuente.trimEnd('\n'), tipo) + cola
        cambia(
            valor.copy(
                text = valor.text.substring(0, trozo.desde) + nuevo +
                    valor.text.substring(trozo.hasta)
            )
        )
    }

    /** Sale de la tabla a un bloque nuevo debajo. */
    fun fueraDeLaTabla() {
        val s2 = sitio ?: return
        val (nuevo, donde) = Vivo.bloqueNuevo(valor.text, s2.bloque)
        cambia(valor.copy(text = nuevo))
        sitio = donde
    }

    /**
     * Pone o quita un estilo sobre lo seleccionado, **sin escribir ni una marca**.
     *
     * El texto no se toca: solo cambian sus estilos, y de volverlos a Markdown se
     * encarga [Vivo.conContenido] al guardar el bloque. Es la diferencia entre
     * esto y la barra de la nota flotante, que sí escribe asteriscos porque allí
     * lo que se edita es el Markdown a la vista.
     */
    fun aplicarEstilo(kind: SpanKind, url: String?) {
        val s2 = sitio ?: return
        val contenido = Vivo.contenido(valor.text, s2.bloque) ?: return
        val spans = Inline.alternar(
            contenido.spans, s2.seleccion.min, s2.seleccion.max, kind, url
        )
        cambia(
            valor.copy(
                text = Vivo.conContenido(valor.text, s2.bloque, InlineText(contenido.text, spans))
            )
        )
    }

    fun insertarBloque(tipo: TipoDeBloque) {
        if (Bloques.pideArchivo(tipo)) {
            pidiendoArchivo = tipo
            return
        }
        // El bloque activo se convierte si está vacío, y si no se abre uno
        // nuevo detrás: meter un título en mitad de un párrafo lo partiría.
        val s2 = sitio
        val vacio = s2?.let { Vivo.contenido(valor.text, it.bloque)?.text.isNullOrEmpty() } == true
        if (s2 != null && vacio) {
            convertirBloque(tipo)
            return
        }
        if (s2 != null) {
            val (conNuevo, donde) = Vivo.bloqueNuevo(valor.text, s2.bloque)
            cambia(valor.copy(text = conNuevo))
            sitio = donde
            convertirBloque(tipo)
            return
        }
        val r = Comandos.elegir(valor.text, valor.text.length, tipo)
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

                    // Mandar la nota a un proyecto: se queda como una hoja
                    // más, con sus páginas, junto a los dibujos y las del PDF.
                    IconButton(onClick = { onAProyecto(valor.text) }) {
                        Icon(
                            Icons.Filled.LibraryAdd,
                            contentDescription = "Añadir a un proyecto"
                        )
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
                EditorVivo(
                    texto = valor.text,
                    sitio = sitio,
                    onTexto = { cambia(valor.copy(text = it)) },
                    onSitio = { sitio = it }
                )
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
                    if (sitio != null && !sitio!!.seleccion.collapsed) {
                        val contenido = remember(valor.text, sitio) {
                            sitio?.let { Vivo.contenido(valor.text, it.bloque) }
                        }
                        BarraDeEstilosUi(
                            activos = remember(contenido, sitio) {
                                val c = contenido
                                val s2 = sitio
                                if (c == null || s2 == null) {
                                    emptySet()
                                } else {
                                    Inline.estilosDe(
                                        c.spans,
                                        s2.seleccion.min,
                                        s2.seleccion.max
                                    )
                                }
                            },
                            onEstilo = { kind -> aplicarEstilo(kind, null) },
                            onEnlace = { pidiendoUrl = true }
                        )
                    } else {
                        BarraDeFamiliasUi(
                            tipoActual = remember(valor.text, sitio) {
                                sitio?.let { Vivo.tipo(valor.text, it.bloque) }
                            },
                            onConvertir = { tipo -> convertirBloque(tipo) },
                            onInsertar = { tipo -> insertarBloque(tipo) },
                            onCatalogo = { viendoCatalogo = true },
                            onBorrarBloque = sitio?.let {
                                {
                                    val (doc, donde) = Vivo.borrarBloque(valor.text, it.bloque)
                                    cambia(valor.copy(text = doc))
                                    sitio = donde
                                }
                            }
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
                aplicarEstilo(SpanKind.LINK, url)
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
