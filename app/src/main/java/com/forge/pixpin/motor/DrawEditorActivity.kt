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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NoteAdd
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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.LineStyle
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
        private const val EXTRA_PDF = "draw_pdf"
        private const val EXTRA_PAGINA = "draw_pagina"

        /**
         * Abre el editor de un pin.
         *
         * [imagenPath], si viene, se coloca como elemento de imagen en el
         * origen la primera vez: es la vía de «capturar y anotar». El lienzo
         * sigue siendo infinito alrededor de ella, así que el margen de trabajo
         * existe mientras dibujas pero no sale en la exportación recortada.
         */
        fun abrir(context: Context, id: String, rutaDibujo: String?, imagenPath: String?) {
            abrir(context, id, rutaDibujo, imagenPath, null, -1)
        }

        /**
         * Abre una **página de un PDF** para anotarla.
         *
         * La hoja se ve de fondo, como referencia: no se puede mover ni borrar
         * porque no es un elemento del dibujo, es el papel. Lo que se trace va
         * encima, y al cerrar vuelve **dentro del PDF** como una capa. Ver
         * [Proyecto] y [DrawPdf.anotarPagina].
         */
        fun abrirPaginaDePdf(
            context: Context, id: String, rutaDibujo: String?, pdf: String, pagina: Int
        ) {
            abrir(context, id, rutaDibujo, null, pdf, pagina)
        }

        private fun abrir(
            context: Context, id: String, rutaDibujo: String?, imagenPath: String?,
            pdf: String?, pagina: Int
        ) {
            context.startActivity(
                Intent(context, DrawEditorActivity::class.java).apply {
                    putExtra(EXTRA_ID, id)
                    putExtra(EXTRA_RUTA, rutaDibujo)
                    putExtra(EXTRA_IMAGEN, imagenPath)
                    putExtra(EXTRA_PDF, pdf)
                    putExtra(EXTRA_PAGINA, pagina)
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

    /** El PDF que se está anotando y qué página, si es que se está anotando uno. */
    private var pdfDeFondo: String? = null
    private var paginaDeFondo: Int = -1

    /**
     * Lo que medía la imagen de la página sobre la que se dibujó.
     *
     * Hace falta al devolver la capa: es lo que dice a qué escala está lo
     * anotado, y sin ello no habría forma de saber que un trazo de cien píxeles
     * son dos centímetros de papel y no veinte. Ver [PdfAnotado.matrizDePagina].
     */
    private var medidaDeLaPagina: Pair<Double, Double>? = null

    /**
     * Si hay algo trazado que todavía no ha vuelto al PDF.
     *
     * Sin esta marca se rehacía el documento en cada pausa —abrir el menú de
     * compartir, apagar la pantalla— aunque no hubiera cambiado nada, que es
     * trabajo tirado y un aviso que nadie pidió.
     */
    private var faltaDevolverAlPdf = false

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

        // **La página del PDF, de telón.**
        //
        // Va como fondo y no como elemento a propósito: así no es que no se
        // exporte, es que **no puede** exportarse. Si entrara en el dibujo, al
        // devolver la capa se estamparía una foto de la página encima de la
        // propia página y su texto quedaría tapado por una imagen — o sea,
        // dejaría de poder buscarse, que es justo lo que todo esto conserva.
        pdfDeFondo = intent.getStringExtra(EXTRA_PDF)
        paginaDeFondo = intent.getIntExtra(EXTRA_PAGINA, -1)
        pdfDeFondo?.let { ruta ->
            if (paginaDeFondo >= 0) {
                fondo = com.forge.pixpin.motor.PdfDoc.render(
                    ruta, paginaDeFondo, com.forge.pixpin.motor.PdfDoc.PAGE_WIDTH
                )
                fondo?.let {
                    medidaDeLaPagina = it.width.toDouble() to it.height.toDouble()
                    encajarLaPagina(it.width.toDouble(), it.height.toDouble())
                }
            }
        }

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
    /**
     * Coloca la vista para que **se vea la hoja entera** al abrirla.
     *
     * Una página son unos 1240 píxeles de ancho y la pantalla de un móvil, mil:
     * sin esto se abría enseñando la esquina de arriba a la izquierda y parecía
     * un lienzo en blanco. Lo primero que uno necesita ver de una hoja es la
     * hoja.
     */
    /**
     * Encuadra lo que haya que mirar: la hoja del PDF, o todo lo dibujado.
     *
     * Con una página detrás manda la página **aunque haya trazos fuera**: lo que
     * se entrega es la hoja, y un garabato suelto a dos metros no tiene que
     * decidir el encuadre de nadie.
     */
    private fun encuadrar() {
        medidaDeLaPagina?.let { (ancho, alto) ->
            encajarLaPagina(ancho, alto)
            return
        }
        val visible = controller.scene.contenidoVisible
        if (visible.isEmpty()) {
            // Nada dibujado: se vuelve al origen y al tamaño natural, que es de
            // donde se partió. Dejarlo como está sería no hacer nada, y un botón
            // que a veces no hace nada se deja de tocar.
            controller.setViewport(Viewport(scrollX = 0.0, scrollY = 0.0, zoom = 1.0))
            return
        }
        val b = getCommonBounds(visible)
        val margen = 40.0
        encajarEn(
            b.x1 - margen, b.y1 - margen,
            b.width + margen * 2, b.height + margen * 2
        )
    }

    private fun encajarLaPagina(ancho: Double, alto: Double) {
        encajarEn(0.0, 0.0, ancho, alto)
    }

    /** Deja esa zona de la escena centrada y a la vista. */
    private fun encajarEn(x: Double, y: Double, ancho: Double, alto: Double) {
        if (ancho <= 0 || alto <= 0) return
        val m = resources.displayMetrics
        val aire = 0.94
        val zoom = minOf(m.widthPixels / ancho, m.heightPixels / alto) * aire
        if (!zoom.isFinite() || zoom <= 0) return
        controller.setViewport(
            Viewport(
                scrollX = (m.widthPixels / zoom - ancho) / 2 - x,
                scrollY = (m.heightPixels / zoom - alto) / 2 - y,
                zoom = zoom
            )
        )
    }

    private fun guardar() {
        ExcalidrawStore.guardar(this, dibujoId, controller.scene)
        if (pdfDeFondo != null) faltaDevolverAlPdf = true
    }

    /**
     * Rehace el PDF del proyecto con lo anotado.
     *
     * ## Al pausar, no al cerrar
     *
     * Estaba en `finish()` y era frágil: si el sistema se lleva la actividad por
     * delante, o si se sale por un camino que no pasa por ahí, lo dibujado se
     * quedaba sin escribir **y sin decir nada**. `onPause` es el único momento
     * que Android garantiza antes de que una actividad deje de verse.
     *
     * ## Y se rehace entero, no se añade
     *
     * Antes se escribía una capa encima de la anterior, y eso se estropeaba a la
     * segunda pasada: retocar la misma página dejaba dos capas con dos versiones
     * del mismo trazo, y **borrar una raya no la borraba** porque seguía en la
     * capa de antes. Ahora se parte de una copia intacta y se ponen encima las
     * hojas tal como están: lo que manda es el dibujo. Ver [PdfDelProyecto].
     *
     * ## Los fallos se dicen
     *
     * Iba envuelto en un `runCatching` mudo. Un PDF cifrado, un archivo movido o
     * cualquier otra cosa daban el mismo resultado: nada, sin aviso y con el
     * dibujo aparentemente guardado. Es la misma clase de silencio que escondió
     * lo del proveedor de archivos durante dos versiones.
     */
    private fun devolverAlPdf() {
        if (!faltaDevolverAlPdf) return
        val ruta = pdfDeFondo ?: return
        val app = application as? com.forge.pixpin.PixPinApp ?: return
        val proyecto = Proyectos.deEstePdf(app.proyectos.proyectos.value, ruta) ?: return

        // El dibujo primero: es de donde se rehace el documento.
        guardar()
        faltaDevolverAlPdf = false

        val bien = PdfDelProyecto.rehacer(this, proyecto, ::bitmapDe)
        android.widget.Toast.makeText(
            this,
            if (bien) R.string.pdf_anotado_ok else R.string.pdf_no_se_pudo,
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    override fun onPause() {
        devolverAlPdf()
        super.onPause()
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
        // Qué grupo de la barra está desplegado. Vive aquí y no dentro de la
        // barra porque las hermanas salen **fuera** de ella, pegadas al lateral
        // de la pantalla, y una barra no puede pintar fuera de sí misma.
        var grupoDesplegado by remember { mutableStateOf<List<Tool>?>(null) }
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
                // La hoja del PDF se ve: es el papel. Una foto de un pin no, que
                // esa ya se coloca como elemento clavado al fondo.
                papelALaVista = pdfDeFondo != null,
                zurdo = zurdo
            )

            EditorEnSitio(tick, editandoTexto, noche) { editandoTexto = it; cambiado() }

            // **Qué estás editando.** El editor era un lienzo sin nombre: se
            // abría una página de un plano de doce y no había nada que dijera
            // cuál, así que había que acordarse de qué miniatura se tocó. Solo
            // sale anotando un PDF; en un dibujo suelto no hay nada que decir y
            // un rótulo vacío es ruido.
            RotuloDeLaHoja(Modifier.align(Alignment.TopCenter).padding(top = 10.dp))

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

            // **Lo que más se toca, en el lateral y a un gesto.** El grosor y la
            // opacidad se recorren con el pulgar sin abrir nada, y el color, el
            // relleno y la línea se eligen arrastrando su bolita hacia el
            // lienzo. Va **sin isla**: una superficie con forma recorta a sus
            // hijos, y las opciones tienen que poder salirse del panel. Ver
            // [PanelLateralDeEstilo].
            //
            // Al lado contrario de la mano, que es hacia donde salen: bajo la
            // mano, el brazo taparía justo lo que acaba de aparecer.
            if (!panelAbierto && hayQueAjustar && editandoTexto == null) {
                PanelLateralDeEstilo(
                    aplican = propiedadesPara(controller.tool, controller.selectedElements()),
                    estilo = controller.scene.style,
                    zurdo = zurdo,
                    colores = STROKE_COLORS,
                    coloresDeFondo = BACKGROUND_COLORS,
                    onEstilo = { nuevo -> aplicarEstilo(nuevo); cambiado() },
                    modifier = Modifier
                        .align(if (zurdo) Alignment.CenterEnd else Alignment.CenterStart)
                )
            }

            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
                BarraHerramientas(
                    tick,
                    onImagen = { selectorImagen() },
                    noche = noche,
                    onAlternarNoche = { noche = !noche },
                    cambiado = { cambiado() },
                    grupoDesplegado = grupoDesplegado,
                    onDesplegarGrupo = { grupoDesplegado = it }
                )
            }

            // **Las hermanas del grupo, en vertical y al lado de la mano.**
            //
            // Salen del primer toque, no del segundo: tocar el grupo coge su
            // herramienta *y* enseña las demás, así que se puede seguir
            // dibujando sin más o cambiar de hermana sin un toque de vuelta.
            //
            // En vertical y pegadas al lateral porque en horizontal, encima de
            // la barra, una fila de seis hermanas se comía la parte de abajo del
            // dibujo — que es donde uno está trabajando cuando toca la barra.
            grupoDesplegado?.let { grupo ->
                Isla(
                    Modifier
                        .align(if (zurdo) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 8.dp)
                ) {
                    Column(
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        grupo.forEach { t ->
                            HermanaDelGrupo(t, t == controller.tool) {
                                controller.selectTool(t)
                                grupoDesplegado = null
                                cambiado()
                            }
                        }
                    }
                }
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
            // **Volver a lo que estás dibujando.**
            //
            // Es lo que más falta hacía. El lienzo es infinito, así que
            // apartarse de lo dibujado es un gesto de nada y volver a pulso es
            // imposible: no hay bordes contra los que orientarse. Anotando una
            // página de un PDF es peor todavía —te vas del papel y ves un vacío
            // blanco sin saber hacia dónde tirar—, y ahí este botón es la única
            // salida que no pasa por cerrar y volver a abrir.
            IconButton(onClick = { encuadrar(); cambiado() }) {
                Icon(
                    Icons.Filled.CenterFocusWeak,
                    contentDescription = getString(R.string.cd_encuadrar)
                )
            }
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
     * Un rótulo discreto con el documento y la página.
     *
     * Arriba y en el medio: es el hueco que las dos islas dejan libre, así que
     * no le quita sitio a nada. Y translúcido, porque es una referencia y no una
     * herramienta — se lee cuando se busca y se ignora el resto del tiempo.
     */
    @Composable
    private fun RotuloDeLaHoja(modifier: Modifier = Modifier) {
        val ruta = pdfDeFondo ?: return
        val app = application as? com.forge.pixpin.PixPinApp ?: return
        val proyecto = Proyectos.deEstePdf(app.proyectos.proyectos.value, ruta)
        val total = proyecto?.hojas?.size ?: 0
        val nombre = proyecto?.nombre ?: File(ruta).nameWithoutExtension

        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            shadowElevation = 3.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // **Y se pasa de hoja aquí mismo.**
                //
                // Anotar un plano es ir y venir entre páginas, y hasta ahora
                // cada salto costaba: cerrar el editor, abrir la rejilla,
                // buscar la miniatura. Con las flechas al lado del rótulo, la
                // siguiente está a un toque — y al pasar se guarda lo de esta,
                // porque cambiar de hoja pasa por el mismo sitio que salir.
                IconButton(
                    onClick = { irALaHoja(paginaDeFondo - 1) },
                    enabled = paginaDeFondo > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = getString(R.string.hoja_anterior),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text =
                        if (total > 0) {
                            getString(R.string.hoja_de_documento, nombre, paginaDeFondo + 1, total)
                        } else {
                            getString(
                                R.string.hoja_de_documento_sin_total, nombre, paginaDeFondo + 1
                            )
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
                IconButton(
                    onClick = { irALaHoja(paginaDeFondo + 1) },
                    enabled = total > 0 && paginaDeFondo < total - 1,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = getString(R.string.hoja_siguiente),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    /**
     * Salta a otra página del mismo documento.
     *
     * Se cierra esta y se abre la otra, en vez de recargar por dentro: una
     * actividad nueva entra por `onCreate` con su hoja, su dibujo y su encuadre
     * ya hechos, y **pasa por `onPause`**, que es donde lo anotado vuelve al
     * PDF. Recargando a mano habría que acordarse de repetir esos cuatro pasos
     * en el orden bueno, y olvidarse de uno solo perdería lo dibujado.
     */
    private fun irALaHoja(pagina: Int) {
        val ruta = pdfDeFondo ?: return
        if (pagina < 0) return
        val app = application as? com.forge.pixpin.PixPinApp ?: return
        val proyecto = Proyectos.deEstePdf(app.proyectos.proyectos.value, ruta) ?: return
        val hoja = Proyectos.hojaDePagina(proyecto, pagina) ?: return

        val dibujo = hoja.dibujo ?: "hoja-${proyecto.id}-$pagina"
        if (hoja.dibujo == null) {
            app.proyectos.guardar(
                Proyectos.conDibujo(proyecto, hoja.id, dibujo, System.currentTimeMillis())
            )
        }
        abrirPaginaDePdf(this, dibujo, ExcalidrawStore.rutaDe(this, dibujo), ruta, pagina)
        finish()
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
                // **Meter esta lámina en un PDF que ya existe.** No sale
                // siempre: solo tiene sentido con un proyecto de PDF abierto, y
                // una opción que casi nunca aplica es una opción de más.
                if (pdfDeUnProyecto() != null) {
                    Formato(Icons.Filled.NoteAdd, R.string.formato_hoja_del_pdf) {
                        aniadirAlPdfDelProyecto()
                    }
                }
            }
        }
    }

    /**
     * El PDF del proyecto en curso, si lo hay.
     *
     * Es a lo que se le puede añadir esta lámina. Se mira el proyecto en curso
     * —el último que se tocó— y no se pregunta a cuál: si acabas de anotar un
     * plano, la hoja que dibujas después va con él. Ver [Proyectos.enCurso].
     */
    private fun pdfDeUnProyecto(): String? {
        if (pdfDeFondo != null) return null
        val app = application as? com.forge.pixpin.PixPinApp ?: return null
        return Proyectos.enCurso(app.proyectos.proyectos.value)?.pdfOrigen
    }

    /**
     * Añade el dibujo de ahora como **una hoja más** del PDF del proyecto.
     *
     * Es lo otro que se le pide a un documento de obra: no solo anotar lo que
     * hay, sino meter una lámina propia dentro del mismo archivo que se va a
     * entregar. Ver [DrawPdf.aniadirComoPagina].
     */
    private fun aniadirAlPdfDelProyecto() {
        val ruta = pdfDeUnProyecto() ?: return
        exportandoSinCompartir {
            val original = File(ruta)
            if (!original.exists()) return@exportandoSinCompartir R.string.pdf_no_esta
            val salida = DrawPdf.aniadirComoPagina(
                this, original.readBytes(), controller.scene, ::bitmapDe
            ) ?: return@exportandoSinCompartir R.string.pdf_no_se_pudo
            val temporal = File(original.parentFile, "${original.name}.nuevo")
            temporal.writeBytes(salida)
            if (temporal.length() <= 0) return@exportandoSinCompartir R.string.pdf_no_se_pudo
            temporal.copyTo(original, overwrite = true)
            temporal.delete()
            null
        }
    }

    /**
     * Escribe algo pesado fuera del hilo de la pantalla y **dice cómo ha ido**.
     *
     * Como [exportando] pero sin abrir el menú de compartir: aquí el resultado
     * se queda dentro de un archivo que ya existe, así que lo único que hay que
     * devolver es si salió bien.
     */
    private fun exportandoSinCompartir(trabajo: suspend () -> Int?) {
        if (exportando) return
        exportando = true
        lifecycleScope.launch {
            val fallo = withContext(Dispatchers.IO) {
                runCatching { trabajo() }.getOrElse { R.string.pdf_no_se_pudo }
            }
            exportando = false
            android.widget.Toast.makeText(
                this@DrawEditorActivity,
                fallo ?: R.string.pdf_hoja_aniadida,
                android.widget.Toast.LENGTH_SHORT
            ).show()
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

    /**
     * Una herramienta del grupo desplegado, en la columna del lateral.
     *
     * Lleva su nombre al lado del icono a propósito: en vertical hay sitio para
     * el rótulo, y con seis hermanas parecidas —las cuatro formas, las dos de
     * medir— el icono solo obliga a probar.
     */
    @Composable
    private fun HermanaDelGrupo(t: Tool, activa: Boolean, onClick: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (activa) MaterialTheme.colorScheme.primary else Color.Transparent,
            modifier = Modifier.padding(2.dp)
        ) {
            Row(
                Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tinta =
                    if (activa) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface
                Icon(
                    iconFor(t),
                    contentDescription = null,
                    tint = tinta,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    getString(labelFor(t)),
                    fontSize = 12.sp,
                    color = tinta,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp)
                )
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
        cambiado: () -> Unit,
        grupoDesplegado: List<Tool>? = null,
        onDesplegarGrupo: ((List<Tool>?) -> Unit)? = null
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
                hayReferencias = controller.hayReferencias,
                // Las hermanas del grupo las pinta el editor, en el lateral y en
                // vertical: aquí dentro no cabrían sin comerse el dibujo.
                grupoDesplegado = grupoDesplegado,
                onDesplegarGrupo = onDesplegarGrupo
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

        // **240 en vez de 300.** Los rótulos costaban 64 dp de cada fila y ya no
        // están: lo que queda son las opciones, que es lo que se viene a tocar.
        // Un panel más estrecho tapa menos dibujo, que es de lo que va todo esto.
        Column(
            Modifier.width(240.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (Propiedad.TRAZO in aplican) FilaDe(Icons.Filled.Brush, "Trazo") {
                STROKE_COLORS.forEach { hex ->
                    Muestra(hex, estilo.strokeColor == hex) {
                        controller.changeStyle(
                            { it.copy(strokeColor = hex) }, { it.copy(strokeColor = hex) }
                        ); cambiado()
                    }
                }
            }
            if (Propiedad.FONDO in aplican) FilaDe(Icons.Filled.FormatColorFill, "Fondo") {
                BACKGROUND_COLORS.forEach { hex ->
                    Muestra(hex, estilo.backgroundColor == hex) {
                        controller.changeStyle(
                            { it.copy(backgroundColor = hex) }, { it.copy(backgroundColor = hex) }
                        ); cambiado()
                    }
                }
            }
            if (Propiedad.RELLENO in aplican) FilaDe(Icons.Filled.Texture, "Relleno") {
                FillStyle.entries.forEach { fs ->
                    Boceto(estilo.fillStyle == fs, nombreDelRelleno(fs), {
                        controller.changeStyle(
                            { it.copy(fillStyle = fs) }, { it.copy(fillStyle = fs) }
                        ); cambiado()
                    }) { tinta -> dibujarRelleno(fs, tinta) }
                }
            }
            if (Propiedad.LINEA in aplican) FilaDe(Icons.Filled.LineStyle, "Línea") {
                StrokeStyle.entries.forEach { ss ->
                    Boceto(estilo.strokeStyle == ss, nombreDeLaLinea(ss), {
                        controller.changeStyle(
                            { it.copy(strokeStyle = ss) }, { it.copy(strokeStyle = ss) }
                        ); cambiado()
                    }) { tinta -> dibujarLinea(ss, tinta) }
                }
            }
            if (Propiedad.GROSOR in aplican) FilaDe(Icons.Filled.LineWeight, "Grosor") {
                ItemStyle.STROKE_WIDTHS.forEachIndexed { i, w ->
                    Boceto(estilo.strokeWidth == w, "Grosor ${i + 1}", {
                        controller.changeStyle(
                            { it.copy(strokeWidth = w) }, { it.copy(strokeWidth = w) }
                        ); cambiado()
                    }) { tinta ->
                        // El propio grosor, a escala: la raya fina se ve fina.
                        drawLine(
                            tinta, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                            strokeWidth = (1.5f + i * 2.2f).dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
            }
            if (Propiedad.RUGOSIDAD in aplican) FilaDe(Icons.Filled.Gesture, "A mano") {
                ROUGHNESS_GLYPHS.forEachIndexed { i, (r, _) ->
                    Boceto(estilo.roughness == r, "Pulso ${i + 1}", {
                        controller.changeStyle(
                            { it.copy(roughness = r) }, { it.copy(roughness = r) }
                        ); cambiado()
                    }) { tinta -> dibujarPulso(i, tinta) }
                }
            }
            // Pixelar o desenfocar. Va por elemento y no por estilo del pincel
            // porque es lo que se corrige mirando el resultado: se tapa, se ve
            // que el bloque queda feo sobre una cara, y se pasa a mancha.
            if (Propiedad.MOSAICO in aplican) FilaDe(Icons.Filled.BlurOn, "Tapar") {
                Boceto(!estilo.mosaicBlur, "Pixelar", { aplicarMosaico(false); cambiado() }) { t ->
                    // Cuadros duros: eso es pixelar.
                    val lado = size.width / 4
                    for (fx in 0..3) for (fy in 0..3) {
                        if ((fx + fy) % 2 == 0) {
                            drawRect(t, Offset(fx * lado, fy * lado), Size(lado, lado))
                        }
                    }
                }
                Boceto(estilo.mosaicBlur, "Desenfocar", { aplicarMosaico(true); cambiado() }) { t ->
                    // Aros que se desvanecen: eso es desenfocar.
                    for (i in 3 downTo 1) {
                        drawCircle(t.copy(alpha = 0.28f * i), radius = size.minDimension / 2 * i / 3)
                    }
                }
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
            if (Propiedad.ESQUINAS in aplican) FilaDe(Icons.Filled.RoundedCorner, "Esquinas") {
                Boceto(estilo.roundness != null, "Redondeadas", {
                    val r = Roundness(Roundness.ADAPTIVE_RADIUS)
                    controller.changeStyle({ it.copy(roundness = r) }, { it.copy(roundness = r) })
                    cambiado()
                }) { t ->
                    drawRoundRect(
                        t, size = size, cornerRadius = CornerRadius(size.minDimension / 3),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                Boceto(estilo.roundness == null, "En pico", {
                    controller.changeStyle({ it.copy(roundness = null) }, { it.copy(roundness = null) })
                    cambiado()
                }) { t -> drawRect(t, size = size, style = Stroke(width = 2.dp.toPx())) }
            }
            if (Propiedad.FUENTE in aplican) FilaDe(Icons.Filled.TextFields, "Letra") {
                ItemStyle.FONT_FAMILIES.forEach { f ->
                    Opcion(
                        DrawFonts.nombreDe(f).take(4),
                        ItemStyle.fontFamilyResuelta(estilo.fontFamily) == f
                    ) { aplicarEstilo(estilo.copy(fontFamily = f)); cambiado() }
                }
            }
            if (Propiedad.FUENTE in aplican) FilaDe(Icons.Filled.FormatSize, "Tamaño") {
                ItemStyle.FONT_SIZES.forEachIndexed { i, s ->
                    OpcionLetra("A", 10 + i * 4, estilo.fontSize == s) {
                        controller.changeStyle(
                            { it.copy(fontSize = s) },
                            { if (it.type == ElementType.TEXT) it.copy(fontSize = s) else it }
                        ); cambiado()
                    }
                }
            }
            if (Propiedad.OPACIDAD in aplican) FilaDe(Icons.Filled.Opacity, "Opacidad") {
                listOf(30, 60, 100).forEach { o ->
                    Boceto(estilo.opacity == o, "Opacidad $o", {
                        controller.changeStyle(
                            { it.copy(opacity = o) }, { it.copy(opacity = o) }
                        ); cambiado()
                    }) { t ->
                        // La propia transparencia: se ve lo transparente que es.
                        drawCircle(t.copy(alpha = o / 100f), radius = size.minDimension / 2)
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
     * Una fila del panel, encabezada por **un icono y no por una palabra**.
     *
     * Las palabras costaban 64 dp de los 300 del panel —una quinta parte del
     * ancho gastada en decir «Grosor»— y encima no hacían falta: una fila de
     * rayas de distinto grosor ya dice que va de grosores. El icono queda para
     * lo único que no se distingue solo, que es **trazo contra fondo**: los dos
     * son una fila de colores iguales.
     */
    @Composable
    private fun FilaDe(icono: ImageVector, descripcion: String, contenido: @Composable () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icono,
                contentDescription = descripcion,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).padding(end = 2.dp)
            )
            Spacer(Modifier.width(6.dp))
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) { contenido() }
        }
    }

    /**
     * Una opción que **se dibuja a sí misma**.
     *
     * Es el cambio de fondo del panel. Antes cada opción era un glifo —`⿴`,
     * `⌐`, `⤓`— o un número —«1 2 3» para el grosor—, y ninguna de las dos cosas
     * dice nada: hay que tocarlas para averiguar qué hacen, y los glifos raros
     * ni siquiera se ven igual en todos los móviles, que en algunos salen como
     * un cuadrado vacío.
     *
     * Dibujando la propia opción no hay nada que averiguar: tres rayas de
     * distinto grosor **son** los tres grosores, y un cuadrado rayado **es** el
     * rayado. Se entiende sin leer y sin tocar.
     */
    @Composable
    private fun Boceto(
        elegido: Boolean,
        descripcion: String,
        onClick: () -> Unit,
        dibujo: androidx.compose.ui.graphics.drawscope.DrawScope.(tinta: Color) -> Unit
    ) {
        val tinta =
            if (elegido) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (elegido) MaterialTheme.colorScheme.primary else Color.Transparent,
            modifier = Modifier.padding(2.dp)
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = descripcion },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(22.dp)) { dibujo(tinta) }
            }
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

    /**
     * Una opción que es **la letra a su tamaño**.
     *
     * Para el tamaño de fuente, dibujar una «A» de cada tamaño dice más que
     * numerarlas «1 2 3 4»: se elige mirando cuál se parece a lo que quieres,
     * no traduciendo un número.
     */
    @Composable
    private fun OpcionLetra(letra: String, tam: Int, elegido: Boolean, onClick: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (elegido) MaterialTheme.colorScheme.primary else Color.Transparent,
            modifier = Modifier.padding(2.dp)
        ) {
            Box(
                Modifier.size(34.dp).clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    letra,
                    fontSize = tam.sp,
                    color = if (elegido) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    /**
     * El pulso: una raya cada vez más torcida.
     *
     * Es lo que hace la rugosidad, así que enseñarlo es enseñarla. Con glifos
     * había que probar los tres para saber cuál era cuál.
     */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.dibujarPulso(
        cuanto: Int, tinta: Color
    ) {
        val y = size.height / 2
        val grosor = 2.dp.toPx()
        if (cuanto == 0) {
            drawLine(tinta, Offset(0f, y), Offset(size.width, y), strokeWidth = grosor)
            return
        }
        val vaiven = size.height / 5f * cuanto
        val camino = Path().apply {
            moveTo(0f, y)
            val tramos = 4
            for (i in 1..tramos) {
                val x = size.width * i / tramos
                val alto = if (i % 2 == 0) y - vaiven else y + vaiven
                quadraticTo(x - size.width / (tramos * 2f), alto, x, y)
            }
        }
        drawPath(camino, tinta, style = Stroke(width = grosor))
    }

    private fun nombreDelRelleno(fs: FillStyle): String = when (fs) {
        FillStyle.HACHURE -> "Rayado"
        FillStyle.CROSS_HATCH -> "Cruces"
        FillStyle.SOLID -> "Sólido"
        FillStyle.ZIGZAG -> "Zigzag"
        FillStyle.LINEAS -> "Rayas rectas"
    }

    private fun nombreDeLaLinea(ss: StrokeStyle): String = when (ss) {
        StrokeStyle.SOLID -> "Continua"
        StrokeStyle.DASHED -> "A trazos"
        StrokeStyle.DOTTED -> "De puntos"
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
                // **Dentro de una figura, el texto se ajusta a lo que cabe.**
                //
                // Escrito de corrido se saldría por los lados del rectángulo: lo
                // que hace un diagrama es meter la palabra dentro de la caja, y
                // para eso hay que repartirla en líneas del ancho de la caja.
                // Fuera de una figura se deja tal cual — un texto suelto lo
                // parte quien escribe, donde quiera.
                val caja = controller.scene.byId(id)
                    ?.let { contenedorDe(it, controller.scene.elements) }
                val definitivo =
                    if (caja == null) texto
                    else repartirEnLineas(texto, anchoQueCabe(caja)) { trozo ->
                        medirTexto(trozo, familia, tam).first
                    }.joinToString("\n")
                val (ancho, alto) = medirTexto(definitivo, familia, tam)
                controller.updateText(id, definitivo, ancho, alto)
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
