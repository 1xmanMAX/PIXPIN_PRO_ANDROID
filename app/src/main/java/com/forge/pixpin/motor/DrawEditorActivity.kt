package com.forge.pixpin.motor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.CircularProgressIndicator
import com.forge.pixpin.R
import com.forge.pixpin.pin.ImageStore
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.File

/**
 * El editor del dibujo, a pantalla completa.
 *
 * No vive en el pin flotante por lo mismo que el croquis: una barra de
 * herramientas y un panel de estilos no caben en una ventana pequeña. El pin
 * sigue enseñando el resultado y sirve para copiarlo.
 */
class DrawEditorActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "draw_id"
        private const val EXTRA_RUTA = "draw_ruta"
        private const val EXTRA_IMAGEN = "draw_imagen"

        /**
         * Abre el editor de un pin.
         *
         * [imagenPath], si viene, se coloca como elemento de imagen en el
         * origen la primera vez: es la vía de «capturar y anotar». El lienzo
         * sigue siendo infinito alrededor de ella, así que el margen de trabajo
         * existe mientras dibujas pero no sale en la exportación recortada.
         */
        fun abrir(context: Context, id: String, rutaDibujo: String?, imagenPath: String?) {
            context.startActivity(
                Intent(context, DrawEditorActivity::class.java).apply {
                    putExtra(EXTRA_ID, id)
                    putExtra(EXTRA_RUTA, rutaDibujo)
                    putExtra(EXTRA_IMAGEN, imagenPath)
                    // Igual que el croquis: con su propia taskAffinity, una
                    // instancia viva se trae al frente sin pasar por `onCreate`
                    // y seguiría enseñando el dibujo del pin anterior.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        }
    }

    private lateinit var dibujoId: String
    private val controller = DrawController()

    /** Caché de bitmaps por `fileId`: el renderer los pide en cada fotograma. */
    private val bitmaps = HashMap<String, Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dibujoId = intent.getStringExtra(EXTRA_ID) ?: System.currentTimeMillis().toString()

        val cargada = ExcalidrawStore.cargar(intent.getStringExtra(EXTRA_RUTA)) ?: Scene()
        controller.load(cargada)

        val imagenPath = intent.getStringExtra(EXTRA_IMAGEN)
        // **Se mira si ya hay foto, no si la escena está vacía.** Un pin de
        // imagen abierto en la edición avanzada trae su escena con lo anotado
        // encima pero sin la foto —el pin la pinta él, por debajo—, y con la
        // condición de antes se editaba a ciegas: los trazos flotando sobre un
        // lienzo en blanco, sin ver sobre qué estaban puestos.
        if (imagenPath != null && cargada.elements.none { it.type == ElementType.IMAGE }) {
            colocarImagenInicial(imagenPath)
        }
        // **La foto que se está anotando no se toca, venga como venga.** Un pin
        // de imagen sigue pintando su foto por su cuenta, por debajo del dibujo;
        // si aquí la foto se puede arrastrar, al volver al pin se ven **las dos**
        // —la que pinta el pin y la que se movió— y parece que el dibujo se ha
        // duplicado. Además, moverla descoloca todo lo anotado respecto de
        // aquello sobre lo que se anotó, que es lo que daba sentido al dibujo.
        //
        // Se hace aquí y no solo al colocarla porque los pines de antes ya
        // llevan su imagen guardada suelta: al abrirlos hay que clavarla igual.
        fijarLaFoto()
        // La foto del pin ya estaba puesta de una vez anterior: se carga igual
        // para que el mosaico tenga de dónde sacar sus píxeles. Solo en este
        // caso, que es el único en el que se sabe que la imagen ocupa la escena
        // desde el origen y a tamaño natural.
        if (imagenPath != null && fondo == null) fondo = ImageStore.load(imagenPath)

        aPantallaCompleta()
        setContent {
            // El negro de verdad tiñe **todo el editor**, no solo el lienzo: con
            // el lienzo negro y las barras grises se ve un recuadro negro
            // rodeado de gris, que es peor que no haberlo tocado.
            val ajustes by (application as? com.forge.pixpin.PixPinApp)?.settings?.settings
                ?.collectAsState(initial = com.forge.pixpin.data.Settings())
                ?: remember { mutableStateOf(com.forge.pixpin.data.Settings()) }
            PixPinTheme(oled = ajustes.oledNegro) { Editor() }
        }
    }

    /**
     * Sin barra de estado ni de navegación.
     *
     * Aquí se dibuja, y la hora, la batería y los iconos de notificación se
     * meten justo en la franja donde acaba cayendo el trazo. Se ocultan pero no
     * se bloquean: siguen saliendo si deslizas desde el borde, que es lo que
     * espera cualquiera que quiera mirar la hora un momento.
     */
    private fun aPantallaCompleta() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val controlador =
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controlador.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controlador.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * Coloca la captura a tamaño natural, con la esquina en el origen.
     *
     * El origen importa: la escena de un pin de imagen se mide en píxeles de la
     * foto, así que dejarla en (0, 0) a tamaño natural es lo que hace que lo
     * anotado en el pin caiga exactamente donde estaba al abrirlo aquí.
     */
    private fun colocarImagenInicial(path: String) {
        val bmp = ImageStore.load(path) ?: return
        val file = ExcalidrawStore.guardarImagen(this, File(path), "image/png") ?: return
        bitmaps[file.id] = bmp
        fondo = bmp
        controller.placeImage(
            file,
            at = Pt(bmp.width / 2.0, bmp.height / 2.0),
            width = bmp.width.toDouble(),
            height = bmp.height.toDouble(),
            alFondo = true
        )
        guardar()
    }

    /**
     * Clava la foto del pin: **al fondo del montón y bloqueada**.
     *
     * Bloqueada quiere decir intocable de verdad — ni se selecciona, ni se
     * arrastra, ni se la lleva el borrador—, que es lo que se espera de aquello
     * sobre lo que estás dibujando. Lo que sí se puede es dibujar encima y, si
     * hace falta más sitio, estirar la hoja.
     */
    private fun fijarLaFoto() {
        val fotos = controller.scene.elements.filter { it.type == ElementType.IMAGE }
        if (fotos.none { !it.locked }) return
        controller.fijarAlFondo(fotos.map { it.id }.toSet())
        guardar()
    }

    /**
     * De dónde saca el mosaico sus píxeles, si se está dibujando sobre una foto.
     *
     * Sin esto, pixelar en la edición avanzada dejaba una placa esmerilada en
     * vez de tapar el dato: el renderizador no inventa píxeles, los coge del
     * fondo, y en el lienzo infinito no hay ninguno.
     */
    private var fondo: Bitmap? = null

    /**
     * Se guarda en cada cambio, no al salir.
     *
     * En esta aplicación el proceso muere sin avisar más de lo normal: confiar
     * en `onPause` es confiar en que siempre llega.
     */
    private fun guardar() {
        ExcalidrawStore.guardar(this, dibujoId, controller.scene)
    }

    private fun bitmapDe(fileId: String): Bitmap? = bitmaps.getOrPut(fileId) {
        controller.scene.files[fileId]?.path?.let { ImageStore.load(it) }
    }

    // ---------------------------------------------------------------------
    // Interfaz
    // ---------------------------------------------------------------------

    @Composable
    private fun Editor() {
        // El controlador no es estado de Compose; este contador es lo que ata
        // los dos mundos, igual que en `DrawCanvas`.
        var tick by remember { mutableIntStateOf(0) }
        var editandoTexto by remember { mutableStateOf<String?>(null) }
        // Arranca en el modo del sistema: si el móvil está en oscuro, el lienzo
        // también. A partir de ahí manda el botón de la barra.
        var noche by remember {
            mutableStateOf(
                resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            )
        }

        fun cambiado() {
            tick++
            guardar()
        }

        val selectorImagen = rememberImagePicker { uri ->
            colocarImagenElegida(uri)
            cambiado()
        }

        var panelAbierto by remember { mutableStateOf(false) }
        var tablaAbierta by remember { mutableStateOf<String?>(null) }
        val ajustes by (application as? com.forge.pixpin.PixPinApp)?.settings?.settings
            ?.collectAsState(initial = com.forge.pixpin.data.Settings())
            ?: remember { mutableStateOf(com.forge.pixpin.data.Settings()) }
        val zurdo = ajustes.zurdo
        // **Los ajustes del imán llegan al controlador.** Sin esto, apagar una
        // clase en la pantalla de ajustes no hacía nada aquí: el controlador se
        // quedaba con los valores de fábrica.
        controller.enganche = AjustesEnganche(
            activo = ajustes.imanActivo,
            esquinas = ajustes.imanEsquinas,
            medios = ajustes.imanMedios,
            centros = ajustes.imanCentros,
            intersecciones = ajustes.imanIntersecciones,
            eje = ajustes.imanEje,
            bordeDeGuia = ajustes.imanBordeDeGuia,
            bordeDeFigura = ajustes.imanBordeDeFigura
        )

        // **El lienzo ocupa la pantalla entera y las barras flotan encima.**
        // Antes iba encajado entre la barra de arriba y las dos de abajo, y se
        // veía como un recuadro que no llegaba a los bordes: al arrastrar algo
        // hacia abajo se metía debajo del cromo y parecía salirse de una hoja.
        // No había tal hoja — el lienzo es infinito— sino un hueco mal repartido.
        Box(
            Modifier.fillMaxSize()
                .background(
                    Color(
                        android.graphics.Color.parseColor(
                            DrawTheme.fondoDe(noche, ajustes.oledNegro)
                        )
                    )
                )
        ) {
            DrawCanvas(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                imageProvider = ::bitmapDe,
                dark = noche,
                // Lo que se toca en la barra —deshacer, esconder las guías,
                // borrar— también tiene que repintar el lienzo. Ver [DrawCanvas].
                cambios = tick,
                // Dos dedos: **editar directamente**. Si lo seleccionado es un
                // texto, se abre para escribir ahí mismo; si es cualquier otra
                // cosa, se abren sus ajustes. Es el mismo gesto para «déjame
                // tocar esto», y hace lo que toque según lo que haya debajo.
                onTwoFingerTap = {
                    val texto = controller.selectedElements()
                        .firstOrNull { it.type == ElementType.TEXT }
                    if (texto != null) editandoTexto = texto.id else panelAbierto = !panelAbierto
                },
                onChange = {
                    cambiado()
                    controller.pendingTextId?.let { editandoTexto = it }
                    // El bote no ha encontrado hueco cerrado: se dice. Callarse
                    // dejaría a alguien tocando una y otra vez sin entender por
                    // qué no pasa nada.
                    // No había cruce, extremo ni centro donde se tocó. Se dice,
                    // por lo mismo que el bote: callarse deja a alguien tocando
                    // una y otra vez sin entender por qué no aparece nada.
                    if (controller.puntoSinSitio) {
                        controller.limpiarAvisoDePunto()
                        android.widget.Toast.makeText(
                            this@DrawEditorActivity,
                            R.string.punto_sin_sitio,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (controller.rellenoSinCerrar) {
                        controller.limpiarAvisoRelleno()
                        android.widget.Toast.makeText(
                            this@DrawEditorActivity,
                            R.string.relleno_sin_cerrar,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                backdrop = fondo,
                zurdo = zurdo
            )

            EditorEnSitio(tick, editandoTexto, noche) { editandoTexto = it; cambiado() }

            // **Las islas cambian de lado con la mano.** El brazo entra por el
            // lado de su mano y tapa lo que hay debajo: lo que se toca a menudo
            // va donde llega el pulgar, y lo que se mira, al otro lado.
            Isla(
                Modifier
                    .align(if (zurdo) Alignment.TopEnd else Alignment.TopStart)
                    .padding(8.dp)
            ) {
                BotonesNavegacion(tick, { cambiado() }) {
                    tablaAbierta = controller.scene.tablas.firstOrNull()?.id
                        ?: controller.addTabla(centroDeLaVista()).also { cambiado() }.id
                }
            }

            // El botón de ajustes solo existe si hay algo que ajustar: con la
            // mano o el borrador puestos y sin nada seleccionado, no sale.
            val hayQueAjustar = propiedadesPara(
                controller.tool, controller.selectedElements()
            ).isNotEmpty()
            if (hayQueAjustar) {
                Isla(
                    Modifier
                        .align(if (zurdo) Alignment.TopStart else Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    BotonesAjustes(tick, panelAbierto, { cambiado() }) { panelAbierto = !panelAbierto }
                }
            } else if (panelAbierto) {
                panelAbierto = false
            }

            if (panelAbierto && hayQueAjustar) {
                Isla(
                    Modifier
                        .align(if (zurdo) Alignment.TopStart else Alignment.TopEnd)
                        .padding(top = 56.dp, end = 8.dp, start = 8.dp)
                ) {
                    PanelAjustes(tick) { cambiado() }
                }
            }

            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
                BarraHerramientas(
                    tick,
                    onImagen = { selectorImagen() },
                    noche = noche,
                    onAlternarNoche = { noche = !noche },
                    cambiado = { cambiado() }
                )
            }

            // La tabla de coordenadas, encima de todo y con velo detrás:
            // mientras se teclean números no se puede estar dibujando.
            controller.scene.tablas.firstOrNull { it.id == tablaAbierta }?.let { tabla ->
                Box(
                    Modifier.fillMaxSize()
                        .background(Color(0x66000000))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { },
                    contentAlignment = Alignment.Center
                ) {
                    EditorDeTabla(
                        tabla = tabla,
                        escala = controller.scene.escala,
                        onAceptar = {
                            controller.updateTabla(it)
                            tablaAbierta = null
                            cambiado()
                        },
                        onBorrar = {
                            controller.removeTabla(tabla.id)
                            tablaAbierta = null
                            cambiado()
                        },
                        onCancelar = { tablaAbierta = null },
                        // Otro color es **otra serie**: se guarda lo tecleado y
                        // se salta a la tabla de ese color, creándola si aún no
                        // existía. Así el color de un punto dice siempre de qué
                        // tabla salió.
                        textoPegado = ::textoDelPortapapeles,
                        onCambiarDeSerie = { color, actual ->
                            controller.updateTabla(actual)
                            tablaAbierta = controller.tablaDeColor(color, centroDeLaVista()).id
                            cambiado()
                        }
                    )
                }
            }

            // Al soltar la raya de escalar hay que decir cuánto mide. Va lo
            // último de la pila —encima de las barras— y con el velo detrás:
            // mientras se teclea la medida no se puede estar dibujando.
            controller.pendingScaleElement()?.let { cota ->
                Box(
                    Modifier.fillMaxSize()
                        .background(Color(0x66000000))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { },
                    contentAlignment = Alignment.Center
                ) {
                    DialogoEscala(
                        largoPx = longitudDe(cota),
                        onCalibrar = { medida, unidad ->
                            controller.applyScale(medida, unidad)
                            cambiado()
                        },
                        onCancelar = { controller.cancelScale(); cambiado() }
                    )
                }
            }

            // Y el de la cota: se abre nada más trazarla, con el mismo teclado.
            // Es el momento en que uno sabe la medida; mandarle a buscar un
            // panel después es perder el número por el camino.
            controller.pendingCotaElement()?.let { cota ->
                Box(
                    Modifier.fillMaxSize()
                        .background(Color(0x66000000))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { },
                    contentAlignment = Alignment.Center
                ) {
                    val escala = controller.scene.escala
                    DialogoDeCota(
                        largoActual = largoEnUnidades(cota, escala),
                        anguloActual = anguloDe(cota),
                        unidad = if (escala != null && escala.valida) escala.unidad else "px",
                        onAceptar = { largo, grados ->
                            controller.aplicarCota(largo, grados)
                            cambiado()
                        },
                        onCancelar = { controller.cancelarCota(); cambiado() }
                    )
                }
            }
        }

    }

    /** Una barra flotante: el recurso que usa el original para no comer lienzo. */
    @Composable
    private fun Isla(modifier: Modifier = Modifier, contenido: @Composable () -> Unit) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 6.dp
        ) {
            Box(Modifier.padding(horizontal = 2.dp, vertical = 1.dp)) { contenido() }
        }
    }

    /** Salir, deshacer, rehacer y compartir. Lo que no depende de la selección. */
    @Composable
    private fun BotonesNavegacion(tick: Int, cambiado: () -> Unit, onTablas: () -> Unit) {
        @Suppress("UNUSED_EXPRESSION") tick
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { finish() }) {
                Icon(Icons.Filled.Close, contentDescription = getString(R.string.cd_close))
            }
            IconButton(
                onClick = { controller.undo(); cambiado() },
                enabled = controller.canUndo
            ) { Icon(Icons.Filled.Undo, contentDescription = getString(R.string.cd_undo)) }
            IconButton(
                onClick = { controller.redo(); cambiado() },
                enabled = controller.canRedo
            ) { Icon(Icons.Filled.Redo, contentDescription = getString(R.string.cd_redo)) }
            IconButton(onClick = onTablas) {
                Icon(
                    Icons.Filled.GridOn,
                    contentDescription = getString(R.string.tabla_abrir)
                )
            }
            MenuDeExportar()
        }
    }

    /**
     * El centro de lo que se está mirando, en coordenadas de escena.
     *
     * Es donde nace el origen de una tabla nueva. En (0,0) de la escena no
     * serviría: en un lienzo infinito ese punto está a saber dónde, y lo que
     * quiere quien va a teclear coordenadas es que su origen caiga donde está
     * mirando.
     */
    /**
     * El texto que haya en el portapapeles, para pegar una tabla de Excel.
     *
     * Se lee aquí y no en el editor de tablas porque el portapapeles es del
     * sistema y aquel archivo es del motor. Aquí ya estamos en una actividad
     * con la ventana enfocada, que es la única condición que Android pone para
     * poder leerlo.
     */
    private fun textoDelPortapapeles(): String? = runCatching {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        // Se juntan todos los trozos: una selección de varias celdas puede
        // llegar repartida en varios `Item`, y quedarse con el primero traería
        // una fila de las cincuenta.
        (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it)?.coerceToText(this)?.toString() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { null }
    }.getOrNull()

    private fun centroDeLaVista(): Pt {
        val m = resources.displayMetrics
        return controller.scene.viewport.toScene(m.widthPixels / 2.0, m.heightPixels / 2.0)
    }

    /**
     * Sacar el dibujo de aquí: imagen, PDF o `.excalidraw`.
     *
     * **Los tres bajo el mismo botón**, y no tres botones en la barra. Compartir
     * no es una acción que se haga cada dos minutos como deshacer, así que no
     * merece sitio permanente por triplicado; y agrupados se entiende de un
     * vistazo que son tres formas de lo mismo, en vez de tres cosas distintas.
     */
    @Composable
    private fun MenuDeExportar() {
        var abierto by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { abierto = true }, enabled = !exportando) {
                // Mientras se escribe el archivo, la ruedecita. Es lo único que
                // distingue «está trabajando» de «no ha hecho nada al tocarlo»,
                // y ahora que el trabajo va por detrás hace falta decirlo.
                if (exportando) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = getString(R.string.cd_draw_export)
                    )
                }
            }
            androidx.compose.material3.DropdownMenu(
                expanded = abierto,
                onDismissRequest = { abierto = false }
            ) {
                // **Una palabra por línea, y un icono delante.**
                //
                // Eran cuatro líneas que empezaban por «Compartir como…», así
                // que para elegir había que leerse las cuatro enteras y llegar
                // al final de cada una. Lo que cambia entre ellas es la última
                // palabra: pues esa es la que se enseña. El verbo ya lo dice el
                // botón que abrió el menú.
                Formato(Icons.Filled.Image, R.string.formato_imagen) { compartirImagen() }
                Formato(Icons.Filled.PictureAsPdf, R.string.formato_pdf) { compartirPdf() }
                Formato(Icons.Filled.Polyline, R.string.formato_svg) { compartirSvg() }
                Formato(Icons.Filled.Edit, R.string.formato_editable) { compartir() }
            }
        }
    }

    /** Una línea del menú de exportar: icono, una palabra y ya. */
    @Composable
    private fun Formato(
        icono: androidx.compose.ui.graphics.vector.ImageVector,
        texto: Int,
        alTocar: () -> Unit
    ) {
        androidx.compose.material3.DropdownMenuItem(
            leadingIcon = { Icon(icono, contentDescription = null) },
            text = { Text(getString(texto)) },
            onClick = alTocar
        )
    }

    /**
     * El botón que abre los ajustes, más lo que hay que tener a un toque.
     *
     * Duplicar y borrar se quedan fuera del panel a propósito: son las dos
     * acciones que se usan sin pensar, y meterlas dentro las pondría a dos
     * toques de distancia.
     */
    @Composable
    private fun BotonesAjustes(
        tick: Int,
        abierto: Boolean,
        cambiado: () -> Unit,
        alternar: () -> Unit
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        val haySeleccion = controller.selectedIds.isNotEmpty()
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (haySeleccion) {
                // **Y avisando de que algo ha cambiado.** Guardar en disco no
                // repinta: el lienzo no es estado de Compose, así que borrando
                // sin este aviso lo borrado seguía en pantalla hasta que tocabas
                // el lienzo, y la papelera parecía no funcionar.
                IconButton(onClick = { controller.duplicateSelection(); guardar(); cambiado() }) {
                    Icon(Icons.Filled.ContentCopy, getString(R.string.cd_duplicate))
                }
                IconButton(onClick = { controller.deleteSelection(); guardar(); cambiado() }) {
                    Icon(Icons.Filled.Delete, getString(R.string.cd_delete))
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (abierto) MaterialTheme.colorScheme.primary else Color.Transparent
            ) {
                IconButton(onClick = alternar) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = getString(R.string.cd_ajustes),
                        tint = if (abierto) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    /** La barra de abajo: la misma [DrawToolbar] que el pin y la captura. */
    @Composable
    private fun BarraHerramientas(
        tick: Int,
        onImagen: () -> Unit,
        noche: Boolean,
        onAlternarNoche: () -> Unit,
        cambiado: () -> Unit
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        // El reparto se elige en los ajustes, igual que el del pin y el de la
        // capa. De fábrica están todas, que aquí sitio hay; pero tener sitio no
        // obliga a enseñarlo todo, y con veintitantas herramientas la fila se
        // hace larga para quien siempre usa las mismas cuatro.
        val app = applicationContext as com.forge.pixpin.PixPinApp
        val ajustes by app.settings.settings.collectAsState(
            initial = com.forge.pixpin.data.Settings()
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawToolbar(
                permitidas = ajustes.editorToolSet,
                grupos = ajustes.editorGroupList,
                tool = controller.tool,
                onTool = { controller.selectTool(it); cambiado() },
                style = controller.scene.style,
                onStyle = { nuevo -> aplicarEstilo(nuevo); cambiado() },
                canUndo = controller.canUndo,
                onUndo = { controller.undo(); cambiado() },
                onImage = onImagen,
                dark = noche,
                onToggleDark = onAlternarNoche,
                escala = controller.scene.escala,
                onQuitarEscala = { controller.clearScale(); cambiado() },
                seriePuntos = controller.seriePuntos,
                onSeriePuntos = {
                    // Rueda entre las tres: son tres, así que un botón que gira
                    // basta y sobra. Un menú para elegir entre tres cosas es un
                    // menú de más.
                    controller.seriePuntos = when (controller.seriePuntos) {
                        SerieDePunto.MAYUSCULAS -> SerieDePunto.MINUSCULAS
                        SerieDePunto.MINUSCULAS -> SerieDePunto.NUMEROS
                        SerieDePunto.NUMEROS -> SerieDePunto.MAYUSCULAS
                    }
                    cambiado()
                },
                pedirLaMedida = controller.pedirLaMedida,
                onPedirLaMedida = {
                    controller.pedirLaMedida = !controller.pedirLaMedida
                    cambiado()
                },
                modoReferencia = controller.modoReferencia,
                onModoReferencia = {
                    controller.modoReferencia = !controller.modoReferencia
                    cambiado()
                },
                referenciasVisibles = controller.referenciasVisibles,
                onAlternarReferencias = { controller.alternarReferencias(); cambiado() },
                hayReferencias = controller.hayReferencias
            )
        }
    }

    /**
     * Lleva el estilo nuevo a la selección **y** al pincel, que es la conducta
     * del original: tocar un color con algo seleccionado lo recolorea y además
     * deja cargado ese color para lo siguiente que se dibuje.
     */
    /** Pixelar o desenfocar, en el pincel y en lo seleccionado. */
    private fun aplicarMosaico(desenfocar: Boolean) {
        controller.changeStyle(
            change = { it.copy(mosaicBlur = desenfocar) },
            toElement = {
                if (it.type == ElementType.MOSAIC) it.copy(mosaicBlur = desenfocar) else it
            }
        )
    }

    /** Cambia la forma de la flecha, en el pincel y en lo seleccionado. */
    private fun aplicarFlecha(elbowed: Boolean, curva: Boolean) {
        val redondeo = if (curva) Roundness(Roundness.PROPORTIONAL_RADIUS) else null
        controller.changeStyle(
            change = { it.copy(elbowed = elbowed, roundness = redondeo) },
            toElement = {
                if (it.type == ElementType.ARROW) {
                    it.copy(elbowed = elbowed, roundness = redondeo)
                } else it
            }
        )
    }

    private fun aplicarEstilo(nuevo: ItemStyle) {
        controller.changeStyle(
            change = { nuevo },
            toElement = { e ->
                // Lo que aplica a cada tipo lo decide el motor, en un solo
                // sitio; aquí solo queda lo que necesita Android: re-medir la
                // caja de un texto al que se le cambia la letra.
                var cambiado = estiloAplicado(e, nuevo)
                // Cambiar de letra con un texto seleccionado tiene que re-medir
                // su caja: si no, el elemento se queda con las medidas de la
                // fuente anterior y la selección deja de cuadrar con lo pintado.
                if (e.type == ElementType.TEXT && e.fontFamily != nuevo.fontFamily) {
                    val tam = e.fontSize ?: nuevo.fontSize
                    val (ancho, alto) = medirTexto(e.text.orEmpty(), nuevo.fontFamily, tam)
                    cambiado = cambiado.copy(
                        fontFamily = nuevo.fontFamily, width = ancho, height = alto
                    )
                }
                cambiado
            }
        )
    }

    /**
     * Cuánto mide la raya y hacia dónde va, para teclearlo.
     *
     * **El principio se queda clavado donde está.** Es lo que hace que esto sea
     * corregir y no volver a empezar: con el dedo se acierta dónde empieza una
     * medida —se apoya en una esquina, en un cruce— y no se acierta nunca ni el
     * largo ni el ángulo. Moviendo el principio al corregir el número habría que
     * recolocarlo otra vez, y ya no se habría corregido nada.
     *
     * La longitud va **en las unidades en las que se esté midiendo** si el
     * dibujo está calibrado, y en píxeles si no: quien acota un plano en metros
     * teclea metros, no la cuenta de cuántos píxeles son.
     */
    @Composable
    private fun LargoYAngulo(raya: Element, cambiado: () -> Unit) {
        val escala = controller.scene.escala
        val unidad = if (escala != null && escala.valida) escala.unidad else "px"
        // La clave ata los campos al elemento y a su geometría: sin ella, al
        // cambiar de raya seguirían enseñando los números de la anterior.
        val huella = "${raya.id}:${raya.points}"
        var largo by remember(huella) {
            mutableStateOf(formatearMedida(largoEnUnidades(raya, escala)))
        }
        var angulo by remember(huella) {
            mutableStateOf(formatearMedida(anguloDe(raya)))
        }

        fun aplicar() {
            val l = Escala.leerNumero(largo) ?: return
            val g = Escala.leerNumero(angulo) ?: return
            val id = raya.id
            controller.mutarSeleccion { e ->
                if (e.id == id) conLargoYAngulo(e, largoEnPixeles(l, escala), g) else e
            }
            cambiado()
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = largo,
                onValueChange = { largo = it },
                label = { Text("${getString(R.string.medida_largo)} ($unidad)") },
                singleLine = true,
                modifier = Modifier.width(120.dp)
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = angulo,
                onValueChange = { angulo = it },
                label = { Text("${getString(R.string.medida_angulo)} (°)") },
                singleLine = true,
                modifier = Modifier.width(96.dp)
            )
            TextButton(onClick = { aplicar() }) {
                Text(getString(R.string.medida_aplicar))
            }
        }
    }

    @Composable
    private fun GlifoBoton(glifo: String, onClick: () -> Unit) {
        TextButton(onClick = onClick) {
            Text(glifo, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Separador() {
        Box(
            Modifier.size(width = 1.dp, height = 24.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }

    /**
     * El panel de estilos.
     *
     * Cada control cambia **la selección y el pincel a la vez**, que es la
     * conducta del original: tocar un color con algo seleccionado lo recolorea
     * y además deja cargado ese color para lo siguiente.
     */
    @Composable
    private fun PanelAjustes(tick: Int, cambiado: () -> Unit) {
        @Suppress("UNUSED_EXPRESSION") tick
        val estilo = controller.scene.style
        val seleccion = controller.selectedElements()
        // **Solo lo que aplica.** La tabla vive en `DrawProperties`, aparte y
        // comprobable sin dispositivo.
        val aplican = propiedadesPara(controller.tool, seleccion)
        val grupos = gruposPara(seleccion)

        Column(
            Modifier.width(300.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (Propiedad.TRAZO in aplican) Fila("Trazo") {
                STROKE_COLORS.forEach { hex ->
                    Muestra(hex, estilo.strokeColor == hex) {
                        controller.changeStyle(
                            { it.copy(strokeColor = hex) }, { it.copy(strokeColor = hex) }
                        ); cambiado()
                    }
                }
            }
            if (Propiedad.FONDO in aplican) Fila("Fondo") {
                BACKGROUND_COLORS.forEach { hex ->
                    Muestra(hex, estilo.backgroundColor == hex) {
                        controller.changeStyle(
                            { it.copy(backgroundColor = hex) }, { it.copy(backgroundColor = hex) }
                        ); cambiado()
                    }
                }
            }
            if (Propiedad.RELLENO in aplican) Fila("Relleno") {
                FillStyle.entries.forEach { fs ->
                    Opcion(FILL_GLYPHS[fs] ?: "?", estilo.fillStyle == fs) {
                        controller.changeStyle(
                            { it.copy(fillStyle = fs) }, { it.copy(fillStyle = fs) }
                        ); cambiado()
                    }
                }
            }
            if (Propiedad.LINEA in aplican) Fila("Línea") {
                StrokeStyle.entries.forEach { ss ->
                    Opcion(STROKE_GLYPHS[ss] ?: "?", estilo.strokeStyle == ss) {
                        controller.changeStyle(
                            { it.copy(strokeStyle = ss) }, { it.copy(strokeStyle = ss) }
                        ); cambiado()
                    }
                }
            }
            if (Propiedad.GROSOR in aplican) Fila("Grosor") {
                ItemStyle.STROKE_WIDTHS.forEachIndexed { i, w ->
                    Opcion("${i + 1}", estilo.strokeWidth == w) {
                        controller.changeStyle(
                            { it.copy(strokeWidth = w) }, { it.copy(strokeWidth = w) }
                        ); cambiado()
                    }
                }
            }
            if (Propiedad.RUGOSIDAD in aplican) Fila("A mano") {
                ROUGHNESS_GLYPHS.forEach { (r, glifo) ->
                    Opcion(glifo, estilo.roughness == r) {
                        controller.changeStyle(
                            { it.copy(roughness = r) }, { it.copy(roughness = r) }
                        ); cambiado()
                    }
                }
            }
            // Pixelar o desenfocar. Va por elemento y no por estilo del pincel
            // porque es lo que se corrige mirando el resultado: se tapa, se ve
            // que el bloque queda feo sobre una cara, y se pasa a mancha.
            if (Propiedad.MOSAICO in aplican) Fila("Tapar") {
                Opcion("▦", !estilo.mosaicBlur) { aplicarMosaico(false); cambiado() }
                Opcion("◍", estilo.mosaicBlur) { aplicarMosaico(true); cambiado() }
            }
            if (Propiedad.FORMA_FLECHA in aplican) Fila("Flecha") {
                // Las tres formas: recta, curva y de codos. La curva **va** de
                // un sitio a otro; la de codos **estructura**, que es lo que
                // pide un mapa mental. Ver [Elbow].
                Opcion("╱", !estilo.elbowed && estilo.roundness == null) {
                    aplicarFlecha(elbowed = false, curva = false); cambiado()
                }
                Opcion("⌒", !estilo.elbowed && estilo.roundness != null) {
                    aplicarFlecha(elbowed = false, curva = true); cambiado()
                }
                Opcion("⌐", estilo.elbowed) {
                    aplicarFlecha(elbowed = true, curva = false); cambiado()
                }
            }
            if (Propiedad.ESQUINAS in aplican) Fila("Esquinas") {
                Opcion("▢", estilo.roundness != null) {
                    val r = Roundness(Roundness.ADAPTIVE_RADIUS)
                    controller.changeStyle({ it.copy(roundness = r) }, { it.copy(roundness = r) })
                    cambiado()
                }
                Opcion("▭", estilo.roundness == null) {
                    controller.changeStyle({ it.copy(roundness = null) }, { it.copy(roundness = null) })
                    cambiado()
                }
            }
            if (Propiedad.FUENTE in aplican) Fila("Letra") {
                ItemStyle.FONT_FAMILIES.forEach { f ->
                    Opcion(
                        DrawFonts.nombreDe(f).take(4),
                        ItemStyle.fontFamilyResuelta(estilo.fontFamily) == f
                    ) { aplicarEstilo(estilo.copy(fontFamily = f)); cambiado() }
                }
            }
            if (Propiedad.FUENTE in aplican) Fila("Tamaño") {
                ItemStyle.FONT_SIZES.forEachIndexed { i, s ->
                    Opcion("${i + 1}", estilo.fontSize == s) {
                        controller.changeStyle(
                            { it.copy(fontSize = s) },
                            { if (it.type == ElementType.TEXT) it.copy(fontSize = s) else it }
                        ); cambiado()
                    }
                }
            }
            if (Propiedad.OPACIDAD in aplican) Fila("Opacidad") {
                listOf(30, 60, 100).forEach { o ->
                    Opcion("$o", estilo.opacity == o) {
                        controller.changeStyle(
                            { it.copy(opacity = o) }, { it.copy(opacity = o) }
                        ); cambiado()
                    }
                }
            }

            // ---- La geometría de una raya: cuánto mide y hacia dónde va ----
            val raya = seleccion.singleOrNull()?.takeIf { it.isLinear }
            if (raya != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                LargoYAngulo(raya) { cambiado() }
            }

            // ---- Las acciones, agrupadas por lo que hacen ----
            if (grupos.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (GrupoAcciones.ORDEN in grupos) Fila("Orden") {
                GlifoBoton("⤓") { controller.sendToBack(); cambiado() }
                GlifoBoton("↓") { controller.sendBackward(); cambiado() }
                GlifoBoton("↑") { controller.bringForward(); cambiado() }
                GlifoBoton("⤒") { controller.bringToFront(); cambiado() }
            }
            if (GrupoAcciones.VOLTEO in grupos) Fila("Voltear") {
                GlifoBoton("⇋") { controller.flipSelectionHorizontal(); cambiado() }
                GlifoBoton("⇅") { controller.flipSelectionVertical(); cambiado() }
            }
            if (GrupoAcciones.AGRUPAR in grupos) Fila("Agrupar") {
                GlifoBoton("⧉") { controller.group(); cambiado() }
                GlifoBoton("⿴") { controller.ungroup(); cambiado() }
            }
            if (GrupoAcciones.ALINEAR in grupos) Fila("Alinear") {
                GlifoBoton("⇤") { controller.align(AlignAxis.X, AlignPosition.START); cambiado() }
                GlifoBoton("⇹") { controller.align(AlignAxis.X, AlignPosition.CENTER); cambiado() }
                GlifoBoton("⇥") { controller.align(AlignAxis.X, AlignPosition.END); cambiado() }
                GlifoBoton("⤄") { controller.distribute(AlignAxis.X); cambiado() }
                GlifoBoton("⤓⤒") { controller.distribute(AlignAxis.Y); cambiado() }
            }
        }
    }

    /** Una fila del panel: su nombre y sus opciones. */
    @Composable
    private fun Fila(nombre: String, contenido: @Composable () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                nombre,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp)
            )
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) { contenido() }
        }
    }


    /**
     * Cuánto ocupa un texto, **medido de verdad**.
     *
     * Antes se estimaba con `nº de caracteres × tamaño × 0,55`, y con eso la
     * caja del elemento no coincidía con lo pintado: picar el texto fallaba por
     * los bordes, la selección salía descuadrada y una eme y una i contaban
     * igual. Con la fuente cargada se puede medir con el mismo `Paint` que
     * luego dibuja, así que la caja es exacta.
     */
    /** Una muestra de color del panel. */
    @Composable
    private fun Muestra(hex: String, elegido: Boolean, onClick: () -> Unit) {
        Box(
            Modifier
                .padding(2.dp)
                .size(if (elegido) 24.dp else 20.dp)
                .background(
                    if (isTransparent(hex)) Color.Transparent else Color(parseColor(hex)),
                    CircleShape
                )
                .border(
                    width = if (elegido) 3.dp else 1.dp,
                    color = if (elegido) MaterialTheme.colorScheme.primary else Color.Gray,
                    shape = CircleShape
                )
                .clickable(onClick = onClick)
        )
    }

    /** Una opción del panel, representada por un glifo. */
    @Composable
    private fun Opcion(glifo: String, elegido: Boolean, onClick: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (elegido) MaterialTheme.colorScheme.primary else Color.Transparent,
            modifier = Modifier.padding(1.dp)
        ) {
            TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                Text(
                    glifo,
                    fontSize = 14.sp,
                    color = if (elegido) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    /**
     * Escribir **encima del dibujo**, donde va a quedar el texto.
     *
     * Antes se abría un diálogo: tapaba el lienzo, te sacaba de donde estabas
     * escribiendo y había que aceptar para ver cómo quedaba. Ahora el cuadro se
     * coloca en el sitio del elemento, con su letra, su tamaño y su color, y lo
     * que ves mientras tecleas es lo que va a quedar.
     *
     * Se posiciona en píxeles y no con un `Layout`: la posición sale del
     * viewport —desplazamiento y zoom—, que no es estado de Compose y cambia
     * bajo los pies.
     */
    @Composable
    private fun EditorEnSitio(
        tick: Int,
        id: String?,
        noche: Boolean,
        onCerrar: (String?) -> Unit
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        if (id == null) return
        val e = controller.scene.byId(id) ?: return

        val vp = controller.scene.viewport
        val esquina = vp.toScreen(Pt(e.x, e.y))
        val tam = e.fontSize ?: controller.scene.style.fontSize
        val familia = e.fontFamily ?: controller.scene.style.fontFamily
        var texto by remember(id) { mutableStateOf(e.text.orEmpty()) }
        val foco = remember(id) { androidx.compose.ui.focus.FocusRequester() }

        LaunchedEffect(id) { runCatching { foco.requestFocus() } }

        fun cerrar() {
            if (texto.isBlank()) {
                // Un texto vacío no deja rastro: sería un elemento invisible que
                // roba toques al picar.
                controller.setSelection(setOf(id))
                controller.deleteSelection()
            } else {
                val (ancho, alto) = medirTexto(texto, familia, tam)
                controller.updateText(id, texto, ancho, alto)
            }
            controller.clearPendingText()
            onCerrar(null)
        }

        // Un toque fuera cierra: es lo que hace el original y evita buscar un
        // botón de aceptar. Va DEBAJO del cuadro para no robarle los toques.
        Box(
            Modifier.fillMaxSize().clickable(
                indication = null,
                interactionSource = remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                }
            ) { cerrar() }
        )

        androidx.compose.foundation.text.BasicTextField(
            value = texto,
            onValueChange = { texto = it },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = composeFontFamily(familia),
                // **En píxeles de pantalla, no en `sp`.** Los `sp` los escala
                // el tamaño de letra del sistema, así que en un móvil con la
                // letra grande el cuadro salía enorme mientras el texto dibujado
                // seguía siendo pequeño: escribías una cosa y aparecía otra.
                fontSize = with(androidx.compose.ui.platform.LocalDensity.current) {
                    (tam * vp.zoom).toFloat().toSp()
                },
                // **Con el filtro del modo noche puesto.** Sin él, lo que se
                // escribía salía del color crudo y al aceptar cambiaba de tono:
                // el lienzo pinta el modo noche como un filtro sobre el color
                // guardado, y el cuadro de escribir se lo saltaba. Se veía como
                // un texto que cambia de color al darle a intro.
                color = Color(DrawTheme.filtrar(parseColor(e.strokeColor), noche))
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.primary
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { cerrar() }),
            modifier = Modifier
                .offset { IntOffset(esquina.x.toInt(), esquina.y.toInt()) }
                .widthIn(min = 120.dp)
                .focusRequester(foco)
        )
    }

    private fun medirTexto(texto: String, familia: Int, tamano: Double): Pair<Double, Double> =
        DrawFonts.medirTexto(this, texto, familia, tamano)

    // ---------------------------------------------------------------------
    // Imágenes y exportación
    // ---------------------------------------------------------------------

    @Composable
    private fun rememberImagePicker(onElegida: (Uri) -> Unit): () -> Unit {
        val lanzador = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri -> uri?.let(onElegida) }
        return { lanzador.launch("image/*") }
    }

    private fun colocarImagenElegida(uri: Uri) {
        runCatching {
            val temporal = File(cacheDir, "draw_import_${System.currentTimeMillis()}")
            contentResolver.openInputStream(uri)?.use { entrada ->
                temporal.outputStream().use { entrada.copyTo(it) }
            } ?: return
            val file = ExcalidrawStore.guardarImagen(this, temporal, "image/png") ?: return
            temporal.delete()

            val bmp = ImageStore.load(file.path) ?: return
            bitmaps[file.id] = bmp
            // Se coloca en el centro de lo que se está mirando, no en el origen
            // de la escena: en un lienzo infinito el origen puede estar lejos.
            val v = controller.scene.viewport
            controller.placeImage(
                file,
                at = Pt(-v.scrollX + 200.0, -v.scrollY + 200.0),
                width = bmp.width.toDouble(),
                height = bmp.height.toDouble()
            )
            guardar()
        }
    }

    /**
     * Comparte el dibujo como `.excalidraw`.
     *
     * Es la red de seguridad del módulo: lo que esta versión nativa no sepa
     * hacer se termina en excalidraw.com.
     */
    /**
     * El dibujo como PDF, listo para mandar.
     *
     * Es la vía de «esto lo tiene que poder abrir cualquiera»: un `.excalidraw`
     * solo lo entiende Excalidraw y un PNG grande pesa y no se imprime bien.
     */
    private fun compartirPdf() = exportando(DrawPdf.MIME_TYPE) {
        DrawPdf.aArchivo(this, controller.scene, dibujoId, ::bitmapDe)
    }

    /**
     * El dibujo como SVG, para **pegarlo dentro de un documento**.
     *
     * Es lo que el PDF no cubre: un PDF se manda, pero no se inserta en medio de
     * un párrafo de Word ni en una diapositiva. Sale vectorial, así que se puede
     * estirar cuanto haga falta sin que se pixele, y el texto va convertido en
     * curvas para que se vea igual en un ordenador que no tenga estas fuentes.
     * Ver [DrawSvg].
     */
    private fun compartirSvg() = exportando(DrawSvg.MIME_TYPE) {
        DrawSvg.aArchivo(this, controller.scene, dibujoId, ::bitmapDe)
    }

    /**
     * El dibujo como imagen, con el formato elegido en los ajustes.
     *
     * Se escribe aquí y no a través del exportador de la captura **porque el
     * motor no puede depender de la aplicación**: si importara `capture`, el
     * motor dejaría de poder usarse en la captura, que es justo donde también
     * vive. Ver `MotorSeparadoTest`. Del formato solo se toma el dato —qué
     * compresor y qué extensión—, que es de la capa de ajustes.
     */
    private fun compartirImagen() {
        val formato = (application as? com.forge.pixpin.PixPinApp)?.ajustes?.copyFormat
            ?: com.forge.pixpin.data.CopyFormat.PNG
        exportando(formato.mime) {
            val bitmap = DrawExport.aBitmap(controller.scene, imageProvider = ::bitmapDe)
                ?: return@exportando null
            val archivo = runCatching {
                val carpeta = File(cacheDir, "share").apply { mkdirs() }
                File(carpeta, "$dibujoId.${formato.extension}").also { destino ->
                    destino.outputStream().use { bitmap.compress(formato.compresor, 100, it) }
                }
            }.getOrNull()
            if (!bitmap.isRecycled) bitmap.recycle()
            archivo
        }
    }

    /**
     * Escribe el archivo **fuera del hilo principal** y luego lo comparte.
     *
     * Aquí había un cuelgue esperando. Todo esto corría en el hilo de la
     * pantalla: comprimir un PNG de cuatro mil píxeles, pasar una foto a base64
     * para meterla en un SVG, dibujar las páginas de un PDF. Con un garabato no
     * se nota —y por eso nunca saltó—, pero con un plano y una captura dentro
     * son segundos con la pantalla congelada, y Android acaba ofreciendo cerrar
     * la aplicación.
     *
     * Mientras dura, el botón de compartir se cambia por una ruedecita: no es
     * un adorno, es lo único que distingue «está trabajando» de «no ha hecho
     * nada al tocarlo».
     */
    private fun exportando(mime: String, escribir: suspend () -> File?) {
        if (exportando) return
        exportando = true
        lifecycleScope.launch {
            val archivo = withContext(Dispatchers.IO) { runCatching { escribir() }.getOrNull() }
            exportando = false
            if (archivo == null) {
                android.widget.Toast.makeText(
                    this@DrawEditorActivity,
                    R.string.pin_draw_empty,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            compartirArchivo(archivo, mime)
        }
    }

    /**
     * Si hay una exportación en marcha.
     *
     * Es estado de Compose porque la barra lo lee: con `var` normal el botón no
     * cambiaría a ruedecita, por lo mismo que el lienzo no se repinta solo. Y
     * sirve además de cerrojo — tocar compartir tres veces seguidas escribía el
     * mismo archivo tres veces a la vez, cada una pisando a la anterior.
     */
    private var exportando by mutableStateOf(false)

    /** Manda un archivo ya escrito en `cache/share`, que es lo que ve el proveedor. */
    private fun compartirArchivo(archivo: File, mime: String) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", archivo
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    getString(R.string.cd_draw_export)
                )
            )
        }
    }

    private fun compartir() = exportando(ExcalidrawStore.MIME_TYPE) {
        // En `share/` y no en la raíz de la caché: el FileProvider solo publica
        // esa subcarpeta (`res/xml/file_paths.xml`), y desde fuera el archivo
        // daría un fallo de permisos.
        val carpeta = File(cacheDir, "share").apply { mkdirs() }
        File(carpeta, "$dibujoId.excalidraw").also {
            it.writeText(ExcalidrawStore.exportar(controller.scene))
        }
    }
}

// -------------------------------------------------------------------------
// Paletas
//
// Son las de Excalidraw, no unas cualquiera: un dibujo hecho aquí y abierto
// allí —o al revés— tiene que verse igual, y los colores forman parte de eso.
// -------------------------------------------------------------------------

private val STROKE_COLORS = listOf(
    "#1e1e1e", "#e03131", "#2f9e44", "#1971c2", "#f08c00"
)

private val BACKGROUND_COLORS = listOf(
    "transparent", "#ffc9c9", "#b2f2bb", "#a5d8ff", "#ffec99"
)

private val FILL_GLYPHS = mapOf(
    FillStyle.HACHURE to "╱",
    FillStyle.CROSS_HATCH to "╳",
    FillStyle.SOLID to "■",
    FillStyle.ZIGZAG to "〰",
    FillStyle.LINEAS to "≡"
)

private val STROKE_GLYPHS = mapOf(
    StrokeStyle.SOLID to "──",
    StrokeStyle.DASHED to "╌╌",
    StrokeStyle.DOTTED to "┈┈"
)

private val ROUGHNESS_GLYPHS = listOf(
    Element.ROUGHNESS_ARCHITECT to "▁",
    Element.ROUGHNESS_ARTIST to "▂",
    Element.ROUGHNESS_CARTOONIST to "▃"
)
