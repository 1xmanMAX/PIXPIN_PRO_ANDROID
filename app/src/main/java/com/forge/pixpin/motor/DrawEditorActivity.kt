package com.forge.pixpin.motor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.AlignHorizontalLeft
import androidx.compose.material.icons.filled.AlignHorizontalRight
import androidx.compose.material.icons.filled.AlignVerticalBottom
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material.icons.filled.AlignVerticalTop
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.HorizontalDistribute
import androidx.compose.material.icons.filled.VerticalDistribute
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
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
        /**
         * Cuánto se espera a que la mano pare antes de escribir a disco.
         *
         * Corto de sobra para que no se pierda nada —al salir se fuerza— y
         * largo de sobra para que un arrastre entero sea **una** escritura y no
         * cuarenta. Ver [guardar].
         */
        private const val ESPERA_PARA_GUARDAR = 350L

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
        // **Fuera la escritura a mano del sistema.**
        //
        // Android 14 trae el «scribble»: con un stylus, tocar un campo de texto
        // abre su propio reconocedor de escritura a mano y se traga el lápiz.
        // Aquí eso es justo lo contrario de lo que se quiere —el lápiz es para
        // dibujar, y el texto se teclea— así que el cuadro de escribir se
        // quedaba secuestrado en cuanto se acercaba la punta.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            window.decorView.isAutoHandwritingEnabled = false
        }
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
        fijarLaFoto(imagenPath != null)
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

        ensenarAMedirElTexto()
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
    /**
     * Y **se vuelve a esconder al recuperar el foco**.
     *
     * Se escondían una sola vez, al arrancar. Cualquier cosa que le quite el
     * foco a la pantalla —bajar el panel de notificaciones, ir a elegir una
     * imagen y volver— las devuelve, y ya no se iban: se dibujaba con la hora y
     * la batería encima del lienzo el resto de la sesión.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) aPantallaCompleta()
    }

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
    private fun fijarLaFoto(hayFoto: Boolean) {
        // **Solo la del fondo, no todas.** Clavando cualquier imagen se clavaban
        // también las que uno mete encima a mano, y al volver a abrir el dibujo
        // ya no había forma de moverlas ni de quitarlas. La del pin es la
        // primera del montón: entra antes que nada, por debajo de lo dibujado.
        if (!hayFoto) return
        val foto = controller.scene.elements.firstOrNull { it.type == ElementType.IMAGE } ?: return
        if (foto.locked) return
        controller.fijarAlFondo(setOf(foto.id))
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

    /**
     * Guarda el dibujo, pero **no en cada fotograma**.
     *
     * Guardar es serializar la escena entera a JSON y comprimirla a disco, y
     * eso pasaba en cada cambio. Mientras se dibuja apenas se nota —un cambio
     * por trazo— pero arrastrando un deslizador de estilo hay un cambio por
     * píxel recorrido: treinta o cuarenta escrituras por segundo en el mismo
     * hilo que pinta. Ahí es donde la interfaz se volvía pastosa.
     *
     * Se espera a que la mano pare. Y si la actividad se va antes, [onPause] lo
     * fuerza: es el único momento que Android garantiza.
     */
    private fun guardar() {
        if (pdfDeFondo != null) faltaDevolverAlPdf = true
        guardadoPendiente?.cancel()
        guardadoPendiente = lifecycleScope.launch {
            kotlinx.coroutines.delay(ESPERA_PARA_GUARDAR)
            guardarYa()
        }
    }

    private var guardadoPendiente: kotlinx.coroutines.Job? = null

    /** Escribe ahora, sin esperar. */
    private fun guardarYa() {
        guardadoPendiente?.cancel()
        guardadoPendiente = null
        ExcalidrawStore.guardar(this, dibujoId, controller.scene)
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
        // Primero lo escrito, que es de lo que se saca todo lo demás.
        guardarYa()
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

        // La imagen que se está copiando, si se ha traído alguna. Ver
        // [VentanaDeReferencia].
        var referencia by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        // El de la imagen de referencia: mismo selector, otro destino. La que se
        // elige aquí **no entra en el dibujo**, se queda en su ventanita.
        val selectorDeReferencia = rememberImagePicker { uri ->
            cargarReferencia(uri)?.let { referencia = it }
        }
        val selectorImagen = rememberImagePicker { uri ->
            colocarImagenElegida(uri)
            cambiado()
        }

        var panelAbierto by remember { mutableStateOf(false) }
        var tablaAbierta by remember { mutableStateOf<String?>(null) }
        // La lista de figuras y el diálogo de la tabla pegada. Van juntas
        // porque se llega a la segunda desde la primera. Ver [PanelDeFiguras].
        var figurasAbiertas by remember { mutableStateOf(false) }
        var tablaPegadaAbierta by remember { mutableStateOf(false) }
        // La paleta de combinaciones, y si se ha dejado clavada. Clavada no se
        // cierra al elegir y flota sobre el lienzo. Ver [PaletaDeColores].
        var paletaAbierta by remember { mutableStateOf(false) }
        var paletaClavada by remember { mutableStateOf(false) }
        var tinta by remember { mutableStateOf(Tinta.LAPIZ) }
        // La ventana de ajustes, el fondo pautado y la imagen de referencia.
        var ajustesAbiertos by remember { mutableStateOf(false) }
        var cuadricula by remember { mutableStateOf(Cuadricula.NINGUNA) }
        // Las mías se leen del disco una vez y se refrescan al guardar o quitar;
        // las de fábrica se arman con el estilo puesto, para que salgan del
        // color y del grosor que se está usando. Ver [figurasDeFabrica].
        var misFiguras by remember { mutableStateOf(BibliotecaStore.cargar(this)) }
        // Qué grupo de la barra está desplegado. Vive aquí y no dentro de la
        // barra porque las hermanas salen **fuera** de ella, pegadas al lateral
        // de la pantalla, y una barra no puede pintar fuera de sí misma.
        var grupoDesplegado by remember { mutableStateOf<List<Tool>?>(null) }
        var zoomBloqueado by remember { mutableStateOf(false) }
        val ajustes by (application as? com.forge.pixpin.PixPinApp)?.settings?.settings
            ?.collectAsState(initial = com.forge.pixpin.data.Settings())
            ?: remember { mutableStateOf(com.forge.pixpin.data.Settings()) }
        val zurdo = ajustes.zurdo
        // **Los ajustes del imán llegan al controlador.** Sin esto, apagar una
        // clase en la pantalla de ajustes no hacía nada aquí: el controlador se
        // quedaba con los valores de fábrica.
        // El interruptor de figura perfecta llega al controlador igual que los
        // ajustes del imán: es estado del editor, no del dibujo.
        controller.keepAspectRatio = figurasPerfectas
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
                    // **Dibujar cierra las hermanas.** Se cerraban al elegir una
                    // de ellas, pero no al usar la que ya venía puesta: se
                    // tocaba el grupo, salían, y como no hacía falta cambiar se
                    // seguía dibujando con la columna ahí plantada tapando el
                    // lienzo hasta que uno se acordaba de cerrarla.
                    grupoDesplegado = null
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
                zurdo = zurdo,
                zoomBloqueado = zoomBloqueado,
                figurasPerfectas = figurasPerfectas,
                cuadricula = cuadricula,
                modoLapiz = modoLapiz,
                onLapizDetectado = { if (lapizAutomatico) modoLapiz = true }
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
            val seleccionado = controller.selectedElements()
            val aplican = propiedadesPara(controller.tool, seleccionado)
            // **El botón del otro lado es de las acciones, no del estilo.** Desde
            // que el estilo vive en el lateral, ahí dentro solo quedan ordenar,
            // voltear, agrupar, alinear y los números de una raya —y todo eso
            // necesita algo seleccionado—. Sin nada marcado el botón abriría un
            // panel vacío, así que no sale.
            val hayQueAjustar = gruposPara(seleccionado).isNotEmpty() ||
                seleccionado.singleOrNull()?.isLinear == true

            // **Dos cajas y no una.** Pegada al canto lo que se pulsa a ciegas
            // —salir, deshacer, rehacer—, que no se puede mover de sitio; al
            // lado, el resto en un carrusel que se desplaza y que puede crecer.
            // Ver [CajaDeNavegacion] y [CarruselDeFunciones].
            //
            // La fila **ocupa todo el ancho** y solo le deja sitio a la isla de
            // enfrente cuando esa isla existe. Estaba fija en dos tercios, y el
            // tercio reservado se lo quitaba al carrusel siempre — incluso sin
            // nada marcado, que es cuando no hay nada enfrente.
            val carrusel: @Composable RowScope.() -> Unit = {
                Isla(Modifier.weight(1f)) {
                    CarruselDeFunciones(
                        tick = tick,
                        cambiado = { cambiado() },
                        onColor = { paletaAbierta = true },
                        onFiguras = { figurasAbiertas = true },
                        onTablas = {
                            tablaAbierta = controller.scene.tablas.firstOrNull()?.id
                                ?: controller.addTabla(centroDeLaVista()).also { cambiado() }.id
                        },
                        onAjustes = { ajustesAbiertos = true }
                    )
                }
            }
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    // **Pegada al canto por su lado.** Contra el borde de la
                    // pantalla hay un tope físico: el pulgar llega hasta el final
                    // y ahí está el botón. Separada, hay que apuntar.
                    //
                    // Y se lleva **la franja entera**: lo de la selección ya no
                    // le disputa el sitio, porque va debajo. Ver abajo.
                    .padding(top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // **El orden se invierte con la mano.** La caja va siempre
                // pegada al canto de su lado, así que tiene que ir la última
                // cuando ese canto es el derecho: puesta la primera dentro de
                // una fila anclada a la derecha, quedaba en mitad de la pantalla.
                //
                // Y las dos han cambiado de lado: la caja de salir se va al canto
                // contrario a la mano y el carrusel ocupa el de la mano. Lo que
                // se busca con la vista queda debajo del pulgar, y lo que se
                // pulsa a ciegas —cerrar, deshacer— lejos de él, que además es
                // donde menos se toca sin querer.
                if (zurdo) {
                    Isla { CajaDeNavegacion(tick) { cambiado() } }
                    Spacer(Modifier.width(6.dp))
                    carrusel()
                } else {
                    carrusel()
                    Spacer(Modifier.width(6.dp))
                    Isla { CajaDeNavegacion(tick) { cambiado() } }
                }
            }

            // **Lo de la selección va en su propia fila, debajo.**
            //
            // Compartía franja con la barra de arriba y se le montaba encima:
            // duplicar y borrar caían sobre el carrusel, y con la mano zurda
            // sobre la caja de salir. Reservarle un hueco a lo ancho tampoco
            // valía —se lo quitaba al carrusel el resto del tiempo, que es
            // cuando no hay nada marcado—. Debajo no se estorban nunca y cada
            // una se lleva su ancho entero.
            if (hayQueAjustar) {
                Isla(
                    Modifier
                        .align(if (zurdo) Alignment.TopStart else Alignment.TopEnd)
                        .padding(horizontal = 8.dp)
                        .padding(top = BAJO_LA_BARRA)
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
                        .padding(horizontal = 8.dp)
                        .padding(top = BAJO_LA_BARRA + ALTO_DE_UNA_ISLA)
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
            if (aplican.isNotEmpty() && editandoTexto == null) {
                PanelLateralDeEstilo(
                    aplican = aplican,
                    // **Los mandos enseñan lo que hay marcado**, y si no hay
                    // nada, el pincel. Enseñando siempre el pincel, tocar uno
                    // le encajaba a la figura marcada todo lo demás de paso.
                    // Ver [DrawController.estiloActivo].
                    estilo = controller.estiloActivo(),
                    // El bote y las regiones ya puestas: ahí la trama manda siempre.
                    rellenoObligatorio = controller.tool == Tool.RELLENO ||
                        seleccionado.any { it.isRegion },
                    zurdo = zurdo,
                    // Las marcas se guardan **fuera del dibujo**: son de quien
                    // dibuja, no del dibujo. El mismo grosor de siempre tiene que
                    // estar ahí al abrir otra lámina.
                    marcas = remember(ajustes.marcasDeDeslizadores) {
                        marcasDeTexto(ajustes.marcasDeDeslizadores)
                    },
                    onMarcas = { cual, valores ->
                        val todas = marcasDeTexto(ajustes.marcasDeDeslizadores) + (cual to valores)
                        lifecycleScope.launch {
                            (application as? com.forge.pixpin.PixPinApp)?.settings
                                ?.setMarcas(marcasATexto(todas))
                        }
                    },
                    onEstilo = { nuevo -> aplicarEstilo(nuevo); cambiado() },
                    zoom = controller.scene.viewport.zoom.toFloat(),
                    anchoPintado = anchoDelTrazoAMano(seleccionado),
                    modifier = Modifier
                        .align(if (zurdo) Alignment.CenterEnd else Alignment.CenterStart)
                )
            }

            // **A qué zoom se está mirando, y el candado.**
            //
            // El zoom era invisible: se llegaba al 340 % sin saberlo y el trazo
            // salía «raro» sin motivo aparente, porque cuatro puntos a ese zoom
            // no son cuatro píxeles. Y el candado hace falta trabajando de
            // cerca: la mano apoya, el segundo dedo roza, y el encuadre que
            // costó encontrar se va sin querer.
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VisorDeZoom(
                    tick = tick,
                    bloqueado = zoomBloqueado,
                    onBloquear = { zoomBloqueado = !zoomBloqueado },
                    onCien = { alZoomCien(); cambiado() }
                )
                Spacer(Modifier.height(6.dp))
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
                        // Igual que el panel de estilo: separada del canto y
                        // fuera del gesto de «atrás» de Android, que si no se
                        // queda el arrastre y cierra el editor.
                        .padding(horizontal = 14.dp)
                        .systemGestureExclusion()
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

            // La lista de figuras, con el mismo velo: mientras se elige una no
            // se está dibujando. Ver [PanelDeFiguras].
            if (figurasAbiertas) {
                val estiloDeLasFiguras = controller.scene.style
                // Las de fábrica se arman con el pincel que haya puesto, así que
                // se rehacen cuando cambia. Es barato y evita una lista de
                // figuras negras sobre un dibujo hecho en rojo.
                val deFabrica = remember(estiloDeLasFiguras) {
                    figurasDeFabrica(estiloDeLasFiguras) { texto, tamano ->
                        medirTexto(texto, estiloDeLasFiguras.fontFamily, tamano)
                    }
                }
                Velo {
                    PanelDeFiguras(
                        figuras = deFabrica + misFiguras,
                        onInsertar = { figura ->
                            controller.insertar(estampar(figura, centroDeLaVista()))
                            figurasAbiertas = false
                            guardar()
                            cambiado()
                        },
                        onQuitar = { figura ->
                            misFiguras = BibliotecaStore.quitar(this@DrawEditorActivity, figura.id)
                        },
                        puedeGuardar = seleccionado.isNotEmpty(),
                        onGuardarSeleccion = { nombre ->
                            figuraDeLaSeleccion(nombre, seleccionado)?.let {
                                misFiguras =
                                    BibliotecaStore.anadir(this@DrawEditorActivity, it)
                            }
                        },
                        onPegarTabla = {
                            figurasAbiertas = false
                            tablaPegadaAbierta = true
                        },
                        onCerrar = { figurasAbiertas = false }
                    )
                }
            }

            // **La paleta.** Suelta va con velo, como cualquier desplegable, y
            // se cierra al elegir. Clavada **no lleva velo**: se queda flotando
            // encima del lienzo y se sigue dibujando con ella puesta, que es
            // para lo que se clava. Ver [PaletaDeColores].
            if (paletaAbierta) {
                // Qué tintas se pueden tocar ahora: la del lápiz siempre, la del
                // relleno solo si lo que se va a dibujar admite fondo. Es la
                // misma tabla que decide los mandos del lateral.
                val tintas = buildSet {
                    if (Propiedad.TRAZO in aplican) add(Tinta.LAPIZ)
                    if (Propiedad.FONDO in aplican) add(Tinta.RELLENO)
                    // **Siempre queda al menos la del lápiz.** Con el borrador o
                    // la mano puestos no aplica ninguna propiedad, y sin esto la
                    // paleta se cerraba a sí misma en el mismo fotograma en que
                    // se abría: pulsabas el color y no pasaba nada, hasta que
                    // abrías otro menú y cambiaba la herramienta. Elegir color
                    // sin nada que pintar tiene sentido de sobra — es el color
                    // con el que se va a dibujar a continuación.
                    if (isEmpty()) add(Tinta.LAPIZ)
                }
                // Y si la tinta que estaba puesta deja de aplicar —se cambia de
                // herramienta con la paleta clavada— se vuelve a la del lápiz en
                // vez de dejar unas pestañas señalando a algo que ya no está.
                val laTinta = if (tinta in tintas) tinta else Tinta.LAPIZ
                val estilo = controller.estiloActivo()
                val elegir: (String) -> Unit = { hex ->
                    aplicarEstilo(
                        if (laTinta == Tinta.RELLENO) estilo.copy(backgroundColor = hex)
                        else estilo.copy(strokeColor = hex)
                    )
                    if (!paletaClavada) paletaAbierta = false
                    cambiado()
                }
                val paleta: @Composable (Modifier) -> Unit = { m ->
                    PaletaDeColores(
                        actual =
                            if (laTinta == Tinta.RELLENO) estilo.backgroundColor
                            else estilo.strokeColor,
                        tinta = laTinta,
                        tintas = tintas,
                        clavada = paletaClavada,
                        onTinta = { tinta = it },
                        onElegir = elegir,
                        onClavar = { paletaClavada = it },
                        onCerrar = { paletaAbierta = false },
                        noche = noche,
                        modifier = m
                    )
                }
                // El velo va **detrás y aparte**, no envolviéndola: la paleta
                // se ancla siempre en la misma esquina —para que estirarla
                // crezca hacia donde va el dedo— y centrada dentro del velo
                // no podría. Sin clavar, el velo se sigue comiendo los toques
                // que fallan; clavada no hay velo y se dibuja con ella puesta.
                if (!paletaClavada) Velo { }
                paleta(Modifier.align(Alignment.TopStart))
            }

            // **La imagen de referencia**, encima del lienzo y sin velo: se
            // dibuja mirándola, así que estorbar lo mínimo es todo su trabajo.
            referencia?.let { mapa ->
                // **Anclada arriba a la izquierda**, y colocada con su propio
                // desplazamiento. Estaba centrada a la derecha, y ahí crecer de
                // ancho la empujaba hacia la izquierda: se estiraba de la esquina
                // de abajo a la derecha y la ventana se iba en la dirección
                // contraria. Con el ancla en una esquina fija, estirar la hace
                // crecer hacia donde va el dedo.
                VentanaDeReferencia(
                    imagen = mapa,
                    onOtraImagen = { referencia = null; selectorDeReferencia() },
                    onCerrar = { referencia = null },
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }

            // La ventana de ajustes: también sin velo, que se deja abierta a un
            // lado mientras se sigue dibujando.
            if (ajustesAbiertos) {
                VentanaDeAjustes(
                    cuadricula = cuadricula,
                    onCuadricula = { cuadricula = it; cambiado() },
                    zoomBloqueado = zoomBloqueado,
                    onZoomBloqueado = { zoomBloqueado = it },
                    modoDedo = controller.modoDedo,
                    onModoDedo = { controller.modoDedo = it; cambiado() },
                    presionFirme = controller.estiloActivo().presionFirme,
                    onPresionFirme = { firme ->
                        aplicarEstilo(controller.estiloActivo().copy(presionFirme = firme))
                        cambiado()
                    },
                    onPapel = { alZoomDeHoja(it); cambiado() },
                    onAProyecto = { aUnProyecto() },
                    // Lo clavado no se puede seleccionar, así que soltarlo no
                    // puede depender de tenerlo marcado: iría aquí o no iría.
                    onSoltarClavados = if (!controller.hayClavados) null else {
                        { controller.soltarTodo(); guardar(); cambiado() }
                    },
                    referenciasVisibles =
                        if (controller.hayReferencias) controller.referenciasVisibles else null,
                    onReferencias = { controller.alternarReferencias(); cambiado() },
                    modoLapiz = modoLapiz,
                    onModoLapiz = { modoLapiz = it; lapizAutomatico = false },
                    onImagenDeReferencia = { selectorDeReferencia() },
                    formatos = formatosDeSalida(),
                    exportando = exportando,
                    onCerrar = { ajustesAbiertos = false },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                )
            }

            // Y la tabla que se acaba de copiar de la hoja de cálculo.
            if (tablaPegadaAbierta) {
                Velo {
                    EditorDeTablaPegada(
                        leerPortapapeles = {
                            com.forge.pixpin.clipboard.TableData.parse(textoDelPortapapeles())
                        },
                        onInsertar = { filas, conCabecera ->
                            insertarTablaPegada(filas, conCabecera)
                            tablaPegadaAbierta = false
                            cambiado()
                        },
                        onCancelar = { tablaPegadaAbierta = false }
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
        // La sombra de su `--shadow-island` es de tres capas muy suaves; en
        // Compose solo hay una, así que se baja y se le añade el filo de un
        // punto que ellos ponen con `0 0 0 1px`. Una sombra dura sobre un
        // lienzo blanco se ve como un recorte pegado encima.
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
            shadowElevation = 3.dp
        ) {
            Box(Modifier.padding(horizontal = 2.dp, vertical = 1.dp)) { contenido() }
        }
    }

    /** Salir, deshacer, rehacer y compartir. Lo que no depende de la selección. */
    /**
     * El velo que se pone detrás de un diálogo.
     *
     * Oscurece y —esto es lo que importa— **se come los toques**: sin el
     * `clickable` vacío, el dedo que iba al diálogo y falla por poco cae en el
     * lienzo de detrás y deja una raya debajo de lo que estabas rellenando.
     */
    @Composable
    private fun Velo(contenido: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxSize()
                .background(Color(0x66000000))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { },
            contentAlignment = Alignment.Center
        ) { contenido() }
    }

    /**
     * Salir, deshacer y rehacer: **en su propia caja**.
     *
     * Iban en la misma fila que todo lo demás y no son lo mismo. Estos tres se
     * tocan a ciegas, cada pocos segundos y sin mirar —deshacer es el botón más
     * pulsado de cualquier editor—, así que tienen que estar **siempre en el
     * mismo sitio**. El resto se busca con la vista y aguanta ir en un carrusel
     * que se desplaza; estos no: si se corrieran, deshacer dejaría de poder
     * pulsarse sin mirar y esa es toda su gracia.
     */
    @Composable
    private fun CajaDeNavegacion(tick: Int, cambiado: () -> Unit) {
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
        }
    }

    /**
     * El resto de funciones, **en un carrusel**.
     *
     * Son las que se buscan con la vista y las que van a seguir creciendo —cada
     * cosa nueva del editor acaba pidiendo su botón aquí—, así que la fila tiene
     * que poder desbordarse sin empujar a nadie ni encogerse hasta que no se
     * acierte. Se desplaza, y lo que no cabe está a un dedo de distancia.
     *
     * Va en `LazyRow` y no en un `Row` que se desplaza: solo se compone lo que
     * se ve. Con cinco botones da igual, pero es la lista que va a crecer y la
     * regla de la casa es esa.
     */
    @Composable
    private fun CarruselDeFunciones(
        tick: Int,
        cambiado: () -> Unit,
        onColor: () -> Unit,
        onFiguras: () -> Unit,
        onTablas: () -> Unit,
        onAjustes: () -> Unit
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        androidx.compose.foundation.lazy.LazyRow(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // **Volver a lo que estás dibujando.**
            //
            // Es lo que más falta hacía. El lienzo es infinito, así que
            // apartarse de lo dibujado es un gesto de nada y volver a pulso es
            // imposible: no hay bordes contra los que orientarse. Anotando una
            // página de un PDF es peor todavía —te vas del papel y ves un vacío
            // blanco sin saber hacia dónde tirar—, y ahí este botón es la única
            // salida que no pasa por cerrar y volver a abrir.
            item {
                IconButton(onClick = { encuadrar(); cambiado() }) {
                    Icon(
                        Icons.Filled.CenterFocusWeak,
                        contentDescription = getString(R.string.cd_encuadrar)
                    )
                }
            }
            // **Girar la vista, y solo cuando hay algo en volumen que girar.**
            //
            // Son cuatro cuartos de vuelta y no una órbita libre, que es la
            // decisión de diseño del lienzo en volumen: con órbita el punto
            // sobre el que se gira acaba fuera de la pantalla, el giro empieza
            // a sentirse como un zoom y quien se pierde no sabe volver. Cuatro
            // posiciones fijas dan el segundo punto de vista que hace falta
            // para comprobar dónde está apoyada una caja, y siempre se puede
            // volver dando la vuelta entera.
            //
            // El botón aparece solo con alguna caja en el dibujo: en un
            // esquema plano, girar no haría absolutamente nada y sería un
            // botón más que estorba.
            if (controller.scene.elements.any { it.type == ElementType.SOLIDO }) {
                item {
                    IconButton(onClick = { controller.girarVista(); cambiado() }) {
                        Icon(
                            Icons.Filled.Rotate90DegreesCcw,
                            contentDescription = getString(R.string.girar_vista)
                        )
                    }
                }
            }
            // **Figuras perfectas, con interruptor.**
            //
            // Ya se podía —apoyando el segundo dedo mientras se traza— y ese
            // gesto se queda, que es el bueno para un círculo suelto. Pero
            // dibujando un diagrama entero son todos los círculos redondos y
            // todas las rectas al eje, y ahí apoyar el segundo dedo cada vez
            // cansa y falla: si se mueve un pelo, el trazo se cancela y pasa a
            // encuadrar. Con el interruptor puesto no hay que sujetar nada.
            item {
                IconButton(onClick = { figurasPerfectas = !figurasPerfectas }) {
                    Icon(
                        Icons.Filled.SquareFoot,
                        contentDescription = getString(R.string.figuras_perfectas),
                        tint = if (figurasPerfectas) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // **El color, aquí arriba y no solo en el lateral.** La bolita del
            // lateral es para ir rápido entre cinco; esto abre la paleta entera,
            // que es a donde se va cuando el dibujo pasa de tres colores. La
            // muestra enseña el que hay puesto, para no tener que abrirla solo
            // para mirar. Ver [PaletaDeColores].
            item {
                IconButton(onClick = onColor) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(parseColor(controller.estiloActivo().strokeColor)))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                    )
                }
            }
            // **La lista de figuras.** Va aquí, al lado de encuadrar, porque es
            // de lo mismo: cosas que se hacen antes de ponerse a dibujar y no
            // mientras se dibuja. Ver [PanelDeFiguras].
            item {
                IconButton(onClick = onFiguras) {
                    Icon(
                        Icons.Filled.Category,
                        contentDescription = getString(R.string.figuras_abrir)
                    )
                }
            }
            item {
                IconButton(onClick = onTablas) {
                    Icon(
                        Icons.Filled.GridOn,
                        contentDescription = getString(R.string.tabla_abrir)
                    )
                }
            }
            // **Exportar ya no tiene botón propio.** Es una pestaña de la
            // ventana de ajustes: se toca al terminar, no dibujando, y tenerlo
            // en dos sitios era justo lo que había que quitar. Ver
            // [VentanaDeAjustes].
            item {
                IconButton(onClick = onAjustes) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = getString(R.string.ajustes_titulo)
                    )
                }
            }
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
    /**
     * El PDF del proyecto en curso, si lo hay.
     *
     * Es a lo que se le puede añadir esta lámina. Se mira el proyecto en curso
     * —el último que se tocó— y no se pregunta a cuál: si acabas de anotar un
     * plano, la hoja que dibujas después va con él. Ver [Proyectos.enCurso].
     */
    /**
     * Las formas de sacar el dibujo de aquí, para la pestaña de exportar.
     *
     * Se arma aquí y no en la ventana porque **cuáles hay depende de la
     * escena**: la de añadir al PDF del proyecto solo tiene sentido con uno
     * abierto, y ofrecerla siempre sería un botón que a veces no hace nada.
     */
    @Composable
    private fun formatosDeSalida(): List<FormatoDeSalida> {
        val delProyecto = pdfDeUnProyecto()
        return buildList {
            add(FormatoDeSalida(Icons.Filled.Image, getString(R.string.formato_imagen)) {
                compartirImagen()
            })
            add(FormatoDeSalida(Icons.Filled.PictureAsPdf, getString(R.string.formato_pdf)) {
                compartirPdf()
            })
            add(FormatoDeSalida(Icons.Filled.Polyline, getString(R.string.formato_svg)) {
                compartirSvg()
            })
            add(FormatoDeSalida(Icons.Filled.Edit, getString(R.string.formato_editable)) {
                compartir()
            })
            if (delProyecto != null) {
                add(
                    FormatoDeSalida(
                        Icons.Filled.NoteAdd,
                        getString(R.string.formato_hoja_del_pdf)
                    ) { aniadirAlPdfDelProyecto() }
                )
            }
        }
    }

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
                // **El candado, y sale solo con imágenes.**
                //
                // Una foto en el lienzo está casi siempre para dibujar encima, y
                // entonces lo único que hace es estorbar: se arrastra al apoyar
                // la mano y el borrador se la lleva al pasar por un hueco. Con
                // esto se clava donde está y deja de existir para el dedo. Para
                // el resto de figuras no hace falta —se mueven porque uno las
                // está colocando— y un botón más en la fila sería ruido.
                if (controller.selectedElements().all { it.type == ElementType.IMAGE } &&
                    controller.selectedIds.isNotEmpty()
                ) {
                    // **Y el mismo botón lo suelta.** Clavado se enseña abierto:
                    // el candado es un interruptor, no un viaje de ida.
                    val clavada = controller.seleccionClavada
                    IconButton(
                        onClick = {
                            if (clavada) controller.soltarSeleccion()
                            else controller.clavarSeleccion()
                            guardar()
                            cambiado()
                        }
                    ) {
                        Icon(
                            if (clavada) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            getString(if (clavada) R.string.cd_soltar else R.string.cd_clavar),
                            tint = if (clavada) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
     * A qué zoom se está mirando, y el candado para clavarlo.
     *
     * Va pegado encima de la barra y pequeño: es un dato, no un mando. El número
     * **es** el botón de volver al cien por cien, que es lo único que se le pide
     * a un indicador de zoom aparte de mirarlo.
     */
    @Composable
    private fun VisorDeZoom(
        tick: Int,
        bloqueado: Boolean,
        onBloquear: () -> Unit,
        onCien: () -> Unit
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        val porcentaje = (controller.scene.viewport.zoom * 100).toInt()
        Surface(
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$porcentaje %",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clickable(onClick = onCien)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Box(
                    Modifier
                        .clickable(onClick = onBloquear)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        if (bloqueado) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = getString(
                            if (bloqueado) R.string.zoom_desbloquear else R.string.zoom_bloquear
                        ),
                        tint = if (bloqueado) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    /**
     * Vuelve al cien por cien **sin moverse de sitio**.
     *
     * Se ancla en el centro de la pantalla: lo que estabas mirando se queda
     * donde está y solo cambia el tamaño. Anclando en una esquina, volver al
     * cien por cien desde un zoom alto te deja mirando a otra parte del dibujo.
     */
    /**
     * Pone el zoom a la escala de una hoja, centrado en lo que se está mirando.
     *
     * Se recoloca alrededor del centro de la pantalla, igual que el botón del
     * cien por cien: cambiar de escala saltando a otro sitio del lienzo deja
     * buscando dónde estaba uno.
     */
    /**
     * Manda lo que hay en el lienzo **al proyecto en curso**, como una hoja más.
     *
     * Es lo que faltaba para que editar una imagen sirviera de algo más que de rato: se
     * anota una captura, se guarda, y al día siguiente está en el proyecto con las demás,
     * lista para entrar en el PDF. Antes lo editado se quedaba dentro del propio pin y no
     * había manera de llevarlo a ninguna parte sin exportarlo a mano.
     *
     * Va al proyecto en curso —el mismo criterio que una nota— y si no hay ninguno se abre
     * uno: elegir proyecto en una lista antes de poder guardar es la clase de paso que
     * hace que uno no guarde nada.
     *
     * **La hoja apunta al dibujo, no copia nada.** Es el mismo identificador con el que se
     * está editando, así que lo que se siga dibujando después también queda dentro; una
     * copia se habría quedado congelada en el momento de darle al botón.
     */
    private fun aUnProyecto() {
        val app = application as? com.forge.pixpin.PixPinApp ?: return
        guardarYa()
        val ahora = System.currentTimeMillis()
        val proyecto = Proyectos.enCurso(app.proyectos.proyectos.value)
            ?: app.proyectos.nuevo(getString(R.string.proyecto_nuevo_nombre), ahora)

        // Si esta hoja ya está en el proyecto no se mete otra vez: el botón se toca dos
        // veces sin querer, y dos hojas iguales apuntando al mismo dibujo se editan a la
        // vez y confunden a cualquiera.
        if (proyecto.hojas.any { it.dibujo == dibujoId }) {
            android.widget.Toast.makeText(this, R.string.hoja_ya_esta, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        app.proyectos.guardar(
            Proyectos.conHoja(
                proyecto,
                com.forge.pixpin.motor.Hoja(id = "h-$ahora", dibujo = dibujoId),
                ahora
            )
        )
        android.widget.Toast.makeText(this, R.string.hoja_a_proyecto, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun alZoomDeHoja(papel: Papel) {
        val v = controller.scene.viewport
        if (v.zoom <= 0.0) return
        val m = resources.displayMetrics
        val destino = papel.zoomPara(m.widthPixels.toDouble(), m.heightPixels.toDouble())
        val centro = androidx.compose.ui.geometry.Offset(
            m.widthPixels / 2f, m.heightPixels / 2f
        )
        controller.setViewport(
            zoomAnchored(v, factor = (destino / v.zoom).toFloat(), from = centro, to = centro)
        )
    }

    private fun alZoomCien() {
        val v = controller.scene.viewport
        if (v.zoom <= 0.0) return
        val m = resources.displayMetrics
        val centro = androidx.compose.ui.geometry.Offset(
            m.widthPixels / 2f, m.heightPixels / 2f
        )
        controller.setViewport(
            zoomAnchored(v, factor = (1.0 / v.zoom).toFloat(), from = centro, to = centro)
        )
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
                // El estilo se toca en el lateral y el deshacer está arriba con
                // el rehacer: los dos aquí serían el mismo control dos veces, y
                // entre ambos se llevaban una fila entera de barra sobre el
                // dibujo. Ver [PanelLateralDeEstilo] y [BotonesNavegacion].
                mostrarEstilo = false,
                mostrarDeshacer = false,
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
                modoReferencia = if (ajustes.guiaEnEditor) controller.modoReferencia else null,
                onModoReferencia = if (!ajustes.guiaEnEditor) null else {
                    {
                        controller.modoReferencia = !controller.modoReferencia
                        cambiado()
                    }
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

    /**
     * Mete en el dibujo la tabla que se acaba de corregir en el diálogo.
     *
     * Se dibuja en el origen y se traslada después, porque hasta que no está
     * construida no se sabe lo que ocupa: el ancho sale del texto más largo de
     * cada columna, y eso hay que medirlo. Ver [elementosDeTabla].
     */
    private fun insertarTablaPegada(filas: List<List<String>>, conCabecera: Boolean) {
        val estilo = controller.scene.style
        val elementos = elementosDeTabla(
            filas = filas,
            estilo = estilo,
            origen = Pt(0.0, 0.0),
            medir = { texto, tamano -> medirTexto(texto, estilo.fontFamily, tamano) },
            conCabecera = conCabecera
        )
        if (elementos.isEmpty()) return
        val caja = getCommonBounds(elementos)
        val centro = centroDeLaVista()
        controller.insertar(
            dragElements(elementos, centro.x - caja.midX, centro.y - caja.midY)
        )
        guardar()
    }

    /**
     * Lo ancho que va a salir el trazo si lo que se está ajustando es el lápiz,
     * o null si es cualquier otra cosa.
     *
     * Se pregunta por lo que hay marcado y, si no hay nada, por la herramienta
     * puesta — que es el mismo criterio con el que [propiedadesPara] decide qué
     * controles salen, así que la muestra y el panel hablan siempre de lo mismo.
     */
    private fun anchoDelTrazoAMano(seleccion: List<Element>): Double? {
        if (seleccion.isNotEmpty()) {
            val trazo = seleccion.singleOrNull()?.takeIf { it.isFreeDraw } ?: return null
            return anchoPintadoDelLapiz(trazo.strokeWidth)
        }
        if (!controller.tool.isFreehand) return null
        val grosor = ItemStyle.freedrawWidthFor(controller.scene.style.strokeWidth)
        val engorde =
            if (controller.tool == Tool.HIGHLIGHTER) ItemStyle.ENGORDE_DEL_MARCADOR else 1.0
        return anchoPintadoDelLapiz(grosor * engorde)
    }

    /**
     * Las figuras salen perfectas sin tener que sujetar nada.
     *
     * Vive en la actividad y no dentro de la pantalla porque lo leen dos: el
     * botón que lo enciende y el lienzo, que tiene que volver a **este** valor
     * cuando se levanta el segundo dedo en vez de volver a apagado.
     */
    private var figurasPerfectas by mutableStateOf(false)

    /**
     * Solo el lápiz dibuja y el dedo mueve el papel.
     *
     * Se enciende **solo** al ver el primer toque de stylus, que es lo cómodo:
     * quien saca el lápiz no tiene que ir a buscar un ajuste. Pero se puede
     * apagar, y hace falta — se pierde el lápiz, se acaba la batería, o
     * simplemente se quiere seguir con el dedo, y sin interruptor el lienzo se
     * quedaba bloqueado sin decir por qué.
     */
    private var modoLapiz by mutableStateOf(false)

    /**
     * Si el modo lápiz sigue decidiéndose solo.
     *
     * En cuanto se toca el interruptor deja de hacerlo: apagarlo y que el
     * siguiente roce del lápiz volviera a encenderlo sería un ajuste que no
     * obedece, que es peor que no tenerlo.
     */
    private var lapizAutomatico = true

    /**
     * Trae una imagen del carrete para tenerla delante mientras se dibuja.
     *
     * Se descodifica **reducida**: una foto de doce megapíxeles metida entera en
     * una ventanita de dos dedos es memoria tirada y un tirón al abrirla. Se pide
     * al descodificador que la baje de golpe, que es lo único que no obliga a
     * cargarla antes de encogerla. Ver [VentanaDeReferencia].
     */
    private fun cargarReferencia(uri: Uri): android.graphics.Bitmap? = runCatching {
        val medida = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, medida)
        }
        val mayor = maxOf(medida.outWidth, medida.outHeight)
        var recorte = 1
        while (mayor / recorte > LADO_DE_LA_REFERENCIA) recorte *= 2
        val opciones = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = recorte
        }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opciones)
        }
    }.getOrNull()

    /**
     * Los dos intervalos del plano: cada cuánto un número y cada cuánto una raya.
     *
     * Son **su configuración**, no su estilo: no dicen de qué color va sino qué
     * está midiendo. Con paso 1 salen los enteros, con 0,5 los medios y con 10
     * las decenas, y la rejilla puede ir más fina que los números —cinco cuadros
     * por unidad es lo de un papel milimetrado—. Ver [Plano].
     */
    @Composable
    private fun AjustesDelPlano(plano: Element, cambiado: () -> Unit) {
        Text(
            getString(R.string.plano_titulo),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        PasoDelPlano(
            getString(R.string.plano_numeros),
            plano.pasoDeNumerosDelPlano
        ) { nuevo ->
            controller.mutarSeleccion { it.copy(pasoDeNumeros = nuevo).touched() }
            cambiado()
        }
        PasoDelPlano(
            getString(R.string.plano_cuadros),
            plano.pasoDeCuadrosDelPlano
        ) { nuevo ->
            controller.mutarSeleccion { it.copy(pasoDeCuadros = nuevo).touched() }
            cambiado()
        }
    }

    /**
     * Un intervalo, elegido entre los que se usan.
     *
     * Botones y no un campo de teclear: los intervalos que se usan de verdad son
     * media docena —décimas, medios, unidades, cincos, decenas— y teclearlos
     * abriría el teclado numérico encima del plano que se está mirando para
     * decidir.
     */
    @Composable
    private fun PasoDelPlano(titulo: String, actual: Double, onPaso: (Double) -> Unit) {
        Text(titulo, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.padding(bottom = 4.dp)) {
            PASOS_DEL_PLANO.forEach { paso ->
                val puesto = kotlin.math.abs(paso - actual) < 1e-9
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (puesto) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clickable { if (!puesto) onPaso(paso) }
                ) {
                    Text(
                        escrito(paso),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (puesto) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }

    private fun aplicarEstilo(nuevo: ItemStyle) {
        // Qué campos se tocan y a quién le aplican lo decide el motor, en un
        // solo sitio; aquí solo queda lo que necesita Android: volver a medir la
        // caja de un texto al que se le ha cambiado la letra. Sin eso el
        // elemento se queda con las medidas de la fuente anterior y la selección
        // deja de cuadrar con lo que se ve pintado.
        controller.cambiarEstilo(nuevo) { e ->
            if (e.type != ElementType.TEXT) return@cambiarEstilo e
            val (ancho, alto) = medirTexto(
                e.text.orEmpty(),
                e.fontFamily ?: nuevo.fontFamily,
                e.fontSize ?: nuevo.fontSize
            )
            e.copy(width = ancho, height = alto)
        }
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

    /**
     * Un botón de icono del panel, del tamaño del dedo y con su nombre.
     *
     * Sustituye a los glifos de texto: «⤓⤒» hay que descifrarlo, y encima cada
     * fuente lo dibuja a su manera. Un icono de Material dice lo mismo igual en
     * todas partes, y su descripción lo cuenta al lector de pantalla, que con un
     * glifo se quedaba mudo.
     */
    @Composable
    private fun IconoBoton(
        icono: androidx.compose.ui.graphics.vector.ImageVector,
        descripcion: String,
        onClick: () -> Unit
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
            Icon(
                icono,
                contentDescription = descripcion,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
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
        val seleccion = controller.selectedElements()
        val grupos = gruposPara(seleccion)

        // **Aquí ya no hay estilos.** Estaban aquí y en el panel lateral a la
        // vez, que es la peor forma de tener una opción: dos sitios donde
        // buscarla, dos que mantener, y la duda de si son la misma. El estilo se
        // toca en el lateral —ver [PanelLateralDeEstilo]— y aquí se quedan las
        // acciones sobre lo seleccionado, que no son estilo: ordenar, voltear,
        // agrupar, alinear y los números de una raya.
        Column(
            Modifier.width(240.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ---- La geometría de una raya: cuánto mide y hacia dónde va ----
            val raya = seleccion.singleOrNull()?.takeIf { it.isLinear }
            if (raya != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                LargoYAngulo(raya) { cambiado() }
            }

            // ---- Y la del plano: cada cuánto un número y cada cuánto una raya ----
            val plano = seleccion.singleOrNull()?.takeIf { it.isPlano }
            if (plano != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                AjustesDelPlano(plano) { cambiado() }
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
            // **Alinear, en dos filas y con iconos de verdad.**
            //
            // Estaba en una sola con cinco glifos de texto —«⇤ ⇹ ⇥ ⤄ ⤓⤒»— y
            // faltaban **las tres verticales**: no había forma de alinear arriba,
            // al medio o abajo, que es la mitad de para lo que sirve esto.
            // Partido por ejes se lee de un vistazo: una fila para lo horizontal
            // y otra para lo vertical, cada una con sus tres y su repartir.
            if (GrupoAcciones.ALINEAR in grupos) {
                Fila("Horizontal") {
                    IconoBoton(Icons.Filled.AlignHorizontalLeft, "Alinear a la izquierda") {
                        controller.align(AlignAxis.X, AlignPosition.START); cambiado()
                    }
                    IconoBoton(Icons.Filled.AlignHorizontalCenter, "Centrar en horizontal") {
                        controller.align(AlignAxis.X, AlignPosition.CENTER); cambiado()
                    }
                    IconoBoton(Icons.Filled.AlignHorizontalRight, "Alinear a la derecha") {
                        controller.align(AlignAxis.X, AlignPosition.END); cambiado()
                    }
                    Separador()
                    IconoBoton(Icons.Filled.HorizontalDistribute, "Repartir en horizontal") {
                        controller.distribute(AlignAxis.X); cambiado()
                    }
                }
                Fila("Vertical") {
                    IconoBoton(Icons.Filled.AlignVerticalTop, "Alinear arriba") {
                        controller.align(AlignAxis.Y, AlignPosition.START); cambiado()
                    }
                    IconoBoton(Icons.Filled.AlignVerticalCenter, "Centrar en vertical") {
                        controller.align(AlignAxis.Y, AlignPosition.CENTER); cambiado()
                    }
                    IconoBoton(Icons.Filled.AlignVerticalBottom, "Alinear abajo") {
                        controller.align(AlignAxis.Y, AlignPosition.END); cambiado()
                    }
                    Separador()
                    IconoBoton(Icons.Filled.VerticalDistribute, "Repartir en vertical") {
                        controller.distribute(AlignAxis.Y); cambiado()
                    }
                }
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
            // **El intro hace otra línea; no cierra.**
            //
            // Estaba puesto para aceptar, y el resultado era que **desde el teclado del
            // móvil no se podía escribir un segundo renglón**: un texto de dos líneas solo
            // existía pegándolo del portapapeles. Aceptar ya se hace tocando fuera, que es
            // el gesto de todas las demás cosas del lienzo.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Default,
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
            ),
            modifier = Modifier
                .offset { IntOffset(esquina.x.toInt(), esquina.y.toInt()) }
                .widthIn(min = 120.dp)
                .focusRequester(foco)
        )
    }

    /**
     * Le enseña al motor a medir renglones.
     *
     * El motor no puede: medir letras es de Android. Con esto, estrechar la caja de un
     * texto lo reparte en más renglones y la caja crece de alto sola, que es lo que hace
     * cualquier caja de texto. Ver [DrawController.altoDelTexto].
     */
    private fun ensenarAMedirElTexto() {
        controller.altoDelTexto = { e, ancho ->
            val tam = e.fontSize ?: com.forge.pixpin.motor.ItemStyle().fontSize
            val regla = DrawFonts.reglaDeAnchos(this, e.fontFamily, tam)
            val renglones = renglonesQueCaben(e.text.orEmpty(), ancho, regla)
            altoDeRenglones(renglones.size, tam)
        }
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

/**
 * Cuánto de ancho se lleva la barra de arriba a la izquierda.
 *
 * Dos tercios: el otro tercio es donde salen los ajustes de lo que haya
 * marcado, y las dos cosas no se pueden pisar. El carrusel se desplaza, así que
 * quedarse con menos ancho no le quita ninguna función — solo hace falta un dedo
 * más para llegar a la última.
 */
/**
 * Dónde empieza la segunda fila: **justo debajo de la barra de arriba**.
 *
 * Los ocho de la barra más su aire. Va como número y no como una columna que
 * las apile porque las dos filas se anclan a lados distintos según la mano, y
 * apilarlas obligaría a que las dos vivieran en el mismo contenedor.
 */
private val BAJO_LA_BARRA = 60.dp

/** Lo que ocupa una isla de botones, para colgar la siguiente debajo. */
private val ALTO_DE_UNA_ISLA = 52.dp

/**
 * Lo grande que se descodifica la imagen de referencia, en píxeles.
 *
 * La ventanita mide dos dedos, así que mil doscientos dan de sobra para
 * acercarse a mirar un detalle. Una foto entera de doce megapíxeles ahí dentro
 * son cuarenta megas de memoria para no ver ni un píxel más.
 */
private const val LADO_DE_LA_REFERENCIA = 1200

/**
 * Los intervalos que se ofrecen para el plano.
 *
 * Media docena, que son los que se usan: décimas para una función que se mueve
 * poco, medios y unidades para lo corriente, cincos y decenas para lo que crece
 * deprisa. Ver [Plano].
 */
private val PASOS_DEL_PLANO = listOf(0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0)

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
