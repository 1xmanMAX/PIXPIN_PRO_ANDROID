package com.forge.pixpin.motor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.AlignHorizontalLeft
import androidx.compose.material.icons.filled.AlignHorizontalRight
import androidx.compose.material.icons.filled.AlignVerticalBottom
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material.icons.filled.AlignVerticalTop
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HorizontalDistribute
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LineStyle
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalDistribute
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.R
import com.forge.pixpin.pin.ImageStore
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        fun abrir(
            context: Context, id: String, rutaDibujo: String?, imagenPath: String?,
            /** Si se viene de la zona de proyectos: cerrar tiene que devolver ahí. */
            desdeProyecto: String? = null
        ) {
            abrir(context, id, rutaDibujo, imagenPath, null, -1, desdeProyecto)
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
            context: Context, id: String, rutaDibujo: String?, pdf: String, pagina: Int,
            desdeProyecto: String? = null
        ) {
            abrir(context, id, rutaDibujo, null, pdf, pagina, desdeProyecto)
        }

        private fun abrir(
            context: Context, id: String, rutaDibujo: String?, imagenPath: String?,
            pdf: String?, pagina: Int, desdeProyecto: String? = null
        ) {
            context.startActivity(
                Intent(context, DrawEditorActivity::class.java).apply {
                    // La seña de este documento: con `documentLaunchMode`, el mismo dibujo
                    // vuelve a su tarea y otro dibujo abre la suya. Ver el manifiesto.
                    data = android.net.Uri.parse("pixpin://dibujo/" + android.net.Uri.encode(id))
                    putExtra(EXTRA_ID, id)
                    putExtra(EXTRA_RUTA, rutaDibujo)
                    putExtra(EXTRA_IMAGEN, imagenPath)
                    putExtra(EXTRA_PDF, pdf)
                    putExtra(EXTRA_PAGINA, pagina)
                    // De dónde se vino, para volver ahí al cerrar. Ver [EXTRA_DESDE_PROYECTO].
                    if (desdeProyecto != null) {
                        putExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO, desdeProyecto)
                    }
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
                    // **Primero las líneas y solo después, si no las hay, los cuadros.**
                    //
                    // Las dos cosas leen el mismo PDF y las dos cuestan: hacerlas a la vez era
                    // rasterizar ochocientos cuadros mientras se interpretaba el mismo
                    // archivo, o sea el doble de trabajo justo en el momento en que se abre el
                    // plano y todo va lento. Mientras se lee se ve la página de una pieza, que
                    // es lo que ya pintaba el mosaico al empezar. Ver [traerElPlanoEnLineas].
                    lifecycleScope.launch {
                        val quiere = (application as? com.forge.pixpin.PixPinApp)
                            ?.settings?.settings?.first()?.planoEnLineas ?: true
                        val hecho = quiere && traerElPlanoEnLineas(ruta, it.width.toDouble())
                        if (!hecho) prepararElMosaico(ruta, it.width.toDouble(), it.height.toDouble())
                    }
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
            PixPinTheme { Editor() }
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
     * **Que la pantalla encienda por encima del blanco, si sabe.**
     *
     * Es lo mismo que hace el croquis en el espacio, y a propósito: es la misma tinta. Sin
     * esto, todo lo de la tinta de luz —el resplandor, la suma, el blanqueo— es **pintura**:
     * hace que un trazo *parezca* que alumbra usando los colores de siempre, y eso tiene un
     * techo insalvable, el blanco. Por mucho que se sume, un píxel de una interfaz normal no
     * puede pasar de ahí, así que en un OLED la raya sale blanca y punto — brillante como el
     * fondo de un menú, no como una luz. Es la queja de fondo de esa tinta y no se arregla
     * eligiendo mejor el color: se arregla teniendo dónde subir.
     *
     * Los paneles de ahora sí tienen dónde, y desde Android 15 se puede **pedir ese margen
     * para la ventana**. Con él, el filamento se sale del blanco de verdad. Y **se apunta si
     * se ha conseguido**, porque de ello depende cómo se pinta una luz: ver [ElBrilloDeMas].
     */
    /**
     * **Le pide a la ventana el margen que haga falta ahora mismo, y ni un poco más.**
     *
     * Se llama cada vez que se mueve la llave de las luces, no al abrir: ver
     * [ajustarElBrilloDeMas], que es donde está el porqué —pedirlo apaga el resto del lienzo—.
     */
    private fun ajustarElBrilloDeMas() {
        val luces = controller.scene.luces
        val sePasa = (luces.cuanto - 1.0).coerceAtLeast(0.0).toFloat()
        // Y solo si de verdad hay algo que encender: subir la llave sin una sola tinta de luz
        // en el dibujo apagaría el papel a cambio de nada.
        val hayLuz = sePasa > 0f &&
            controller.scene.elements.any { !it.isDeleted && it.material.alumbra }
        ajustarElBrilloDeMas(
            window,
            if (android.os.Build.VERSION.SDK_INT >= 30) display else null,
            if (hayLuz) sePasa else 0f
        )
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
            // **La escritura, fuera del hilo de la interfaz.** Serializar y
            // comprimir la escena entera es proporcional a lo que haya dibujado,
            // y con un plano importado eso son miles de elementos: hecho aquí
            // mismo, cada trazo terminado congelaba la pantalla un instante. La
            // escena se captura en el hilo principal —es inmutable, viajar es
            // gratis— y el almacén va sincronizado, así que un `guardarYa` de
            // `onPause` que llegue a la vez no se entrelaza con esta escritura:
            // el que escribe después escribe el estado más nuevo.
            val escena = controller.scene
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val escrito = ExcalidrawStore.guardar(this@DrawEditorActivity, dibujoId, escena)
                if (escrito != null) purgarHojasDeMarcosPerdidos(escena)
            }
        }
    }

    private var guardadoPendiente: kotlinx.coroutines.Job? = null

    /** Escribe ahora, sin esperar. */
    private fun guardarYa() {
        guardadoPendiente?.cancel()
        guardadoPendiente = null
        val escena = controller.scene
        if (ExcalidrawStore.guardar(this, dibujoId, escena) != null) {
            purgarHojasDeMarcosPerdidos(escena)
        }
    }

    /**
     * **Si un lienzo pierde marcos, el proyecto pierde las hojas que apuntaban a ellos.**
     *
     * Borrar una lámina del cuaderno (un marco) dentro del editor solo escribe la escena: las
     * hojas del proyecto que vivían de ese marco —`Hoja` con `marco` puesto— se quedaban
     * colgadas y, al no encontrar su marco, se enseñaban como el lienzo entero, duplicando la
     * hoja principal. Aquí se mira cada proyecto con hojas de este dibujo y se quita la hoja
     * cuyo marco ya no exista. El lienzo entero (`Hoja` sin `marco`) se conserva siempre, y el
     * proyecto solo se reescribe si algo cambió. Ver [Proyectos.sinHoja].
     */
    private fun purgarHojasDeMarcosPerdidos(escena: com.forge.pixpin.motor.Scene) {
        val app = application as? com.forge.pixpin.PixPinApp ?: return
        val ahora = System.currentTimeMillis()
        val vivos = escena.marcos.map { it.id }.toHashSet()
        for (proyecto in app.proyectos.proyectos.value) {
            var cambiado = proyecto
            for (hoja in proyecto.hojas) {
                if (hoja.dibujo == dibujoId && hoja.marco != null && hoja.marco !in vivos) {
                    cambiado = Proyectos.sinHoja(cambiado, hoja.id, ahora)
                }
            }
            if (cambiado != proyecto) app.proyectos.guardar(cambiado)
        }
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

    override fun onDestroy() {
        // Los trozos del plano son megas de mapas de bits: se sueltan al cerrar, que es lo
        // que hace que abrir y cerrar planos grandes no vaya llenando la memoria.
        mosaico?.soltar()
        mosaico = null
        planoVectorial?.soltar()
        planoVectorial = null
        laminaFina?.soltar()
        laminaFina = null
        super.onDestroy()
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
        /** Lo que ocupa el visor del zoom, para no ponerle el mando encima. Ver abajo. */
        var altoDeLoDeAbajo by remember { mutableIntStateOf(0) }
        /** Y lo que ocupa la barra de arriba, para que el rótulo no le quede detrás. */
        var altoDeLaBarraDeArriba by remember { mutableIntStateOf(0) }
        /**
         * **El aumento, al día en cada fotograma del pellizco.**
         *
         * Va aparte del [tick] a propósito: que se puedan tener las dos cosas —la muestra al
         * día y el panel quieto— es porque quien lo lee lo lee **al pintar**. El punto del
         * grosor se dibuja en un `Canvas`, y una lectura dentro de un `Canvas` la registra el
         * pintado y no la composición: cambiar esto repinta un círculo de dos milímetros y no
         * recompone nada. Es el mismo acuerdo que la rueda del color con su tono.
         */
        val zoomVivo = remember { mutableFloatStateOf(controller.scene.viewport.zoom.toFloat()) }
        var editandoTexto by remember { mutableStateOf<String?>(null) }
        // **El papel manda, y el modo noche sale de él.**
        //
        // Eran dos cosas sueltas —un interruptor de noche y un fondo— y se podían
        // contradecir: papel a oscuras con el modo día puesto deja el dibujo negro sobre
        // negro. Ahora hay una sola decisión, el color del papel, y de ella salen las otras
        // dos: con qué filtro se pinta la tinta y de qué color va la cuadrícula. Ver
        // [DrawTheme.esDeNoche].
        //
        // **Y el modo noche de la aplicación pone el papel oscuro** cuando el dibujo lleva
        // el blanco de fábrica: un dibujo se guarda siempre con sus colores de día —eso no
        // cambia—, pero mirarlo a oscuras con el papel blanco a tope es justo lo que el
        // ajuste «Modo noche» viene a evitar. Solo el blanco de fábrica: un papel hueso o
        // gris es una elección, y se respeta. El botón del papel sigue mandando: tocarlo
        // deja el papel claro **en esta sesión** aunque la aplicación esté de noche.
        val nocheDeLaApp = com.forge.pixpin.ui.theme.deNoche()
        var papelClaroForzado by remember { mutableStateOf(false) }
        val papelGuardado = controller.scene.backgroundColor
        val papel =
            if (nocheDeLaApp && !papelClaroForzado &&
                papelGuardado.equals(DrawTheme.FONDO_DIA, ignoreCase = true)
            ) DrawTheme.fondoDe(true, (application as? com.forge.pixpin.PixPinApp)?.ajustes?.oledNegro ?: false)
            else papelGuardado
        val noche = DrawTheme.esDeNoche(papel)

        /**
         * Algo ha cambiado: se apunta para guardar y **se pone al día la barra**.
         *
         * Salvo con el dedo todavía puesto. Subir el contador rehace el editor entero —la
         * barra, los paneles, la caja de navegación, los botones—, y un trazo avisa en cada
         * punto: eran doscientas pantallas por segundo para que la barra siguiera diciendo
         * lo mismo. El lienzo no lo necesita, se repinta él solo. Ver
         * [DrawCanvas.onChange].
         */
        fun cambiado(mientrasSeTraza: Boolean = false) {
            // **El aumento sí llega con el dedo puesto; el tick no.** Aquí está toda la idea:
            // el tick rehace el editor entero —barra, paneles, mandos— y por eso solo sube al
            // levantar el dedo. Pero la muestra del grosor **es** lo que va a salir en la
            // pantalla, así que tiene que seguir al zoom mientras se pellizca o dice lo que no
            // es justo cuando se la está mirando. Ver [zoomVivo].
            //
            // Escribir estado desde aquí es legal porque `cambiado` responde siempre a un
            // evento y no se llama nunca componiendo.
            zoomVivo.floatValue = controller.scene.viewport.zoom.toFloat()
            if (!mientrasSeTraza) tick++
            guardar()
        }

        // **Y el margen de brillo se reajusta con la llave de las luces**, también al abrir un
        // dibujo que ya venía con ellas subidas. La cuenta se rehace solo cuando esa llave
        // cambia, no en cada trazo: mira todos los elementos. Ver [ajustarElBrilloDeMas].
        LaunchedEffect(controller.scene.luces) { ajustarElBrilloDeMas() }

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
        // La gráfica de una función: se pide desde la lista de figuras. Ver [Graficas].
        var graficaAbierta by remember { mutableStateOf(false) }
        // La paleta de combinaciones, y si se ha dejado clavada. Clavada no se
        // cierra al elegir y flota sobre el lienzo. Ver [PaletaDeColores].
        var paletaAbierta by remember { mutableStateOf(false) }
        var paletaClavada by remember { mutableStateOf(false) }
        // **El taller del color del lateral**: la misma rueda que sale al arrastrar el mando,
        // con su tira de luz y los colores de la paleta puesta. Lo abre un toque en el mando.
        //
        // Se pinta aquí y no dentro del panel porque ahí se podría **ver** pero no **tocar**:
        // lo que se sale de un contenedor no recibe el dedo. Ver [PanelLateralDeEstilo].
        var tallerDeColor by remember { mutableStateOf(false) }
        /**
         * **Solo el dibujo**: fuera todos los mandos, con la herramienta que hubiera puesta.
         *
         * Se entra y se sale con el mismo toque de cuatro dedos. Ver [elToqueDeCuatroDedos].
         */
        var soloElDibujo by remember { mutableStateOf(false) }
        // El agarre del botón flotante de la pantalla completa. Ver [BotonFlotante].
        val agarre = remember { AgarreDelBoton() }
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
                            papel
                        )
                    )
                )
        ) {
            DrawCanvas(
                controller = controller,
                // El gesto va **encima del lienzo y en la pasada inicial**, sin consumir
                // mientras no haya cuatro dedos: así trazar y encuadrar siguen igual. Ver
                // [elToqueDeCuatroDedos].
                modifier = Modifier
                    .fillMaxSize()
                    // **Dos dedos deshacen, tres rehacen.** Sobre un texto marcado, dos
                    // dedos lo abren para escribir, que es lo que hacían antes.
                    .elToqueDeVariosDedos { dedos ->
                        if (dedos == 2) {
                            val texto = controller.selectedElements()
                                .firstOrNull { it.type == ElementType.TEXT }
                            if (texto != null) editandoTexto = texto.id
                            else if (controller.canUndo) { controller.undo(); cambiado() }
                        } else if (dedos == 3 && controller.canRedo) {
                            controller.redo(); cambiado()
                        }
                    }
                    .elToqueDeCuatroDedos(
                        alJuntarse = {
                            // El primer dedo llega unas milésimas antes que los otros tres,
                            // así que a estas alturas ya hay un punto empezado: sin esto,
                            // esconder los mandos dejaba una mota en el dibujo.
                            controller.cancel()
                            cambiado()
                        },
                        alTocar = {
                            soloElDibujo = !soloElDibujo
                            if (soloElDibujo) {
                                // Lo que hubiera abierto se cierra: un panel flotando sobre
                                // una pantalla sin mandos no se puede ni cerrar.
                                panelAbierto = false
                                paletaAbierta = false
                                figurasAbiertas = false
                                ajustesAbiertos = false
                                tallerDeColor = false
                                grupoDesplegado = null
                                Toast.makeText(
                                    this@DrawEditorActivity,
                                    getString(R.string.editor_solo_el_dibujo),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            cambiado()
                        }
                    ),
                imageProvider = ::bitmapDe,
                dark = noche,
                // Lo que se toca en la barra —deshacer, esconder las guías,
                // borrar— también tiene que repintar el lienzo. Ver [DrawCanvas].
                // El tick del mosaico entra por aquí: un trozo recién llegado no cambia el
                // dibujo, solo hay que volver a pintarlo. Ver [ElMosaicoDelPapel].
                cambios = tick + tickDelMosaico,
                // Dos dedos: **editar directamente**. Si lo seleccionado es un
                // texto, se abre para escribir ahí mismo; si es cualquier otra
                // cosa, se abren sus ajustes. Es el mismo gesto para «déjame
                // tocar esto», y hace lo que toque según lo que haya debajo.
                // El toque de dos dedos ya no abre el panel: es deshacer, junto con el de
                // tres, que es rehacer. Ver [elToqueDeVariosDedos] más arriba.
                onTwoFingerTap = {},
                onTocar = { ajustesAbiertos = false },
                coloresRapidos = COMBINACIONES.first().colores,
                onColorRapido = { hex ->
                    aplicarEstilo(controller.estiloActivo().copy(strokeColor = hex))
                    cambiado()
                },
                // El atajo de la pantalla completa: el abanico con el botón flotante
                // mantenido. Ver [DrawCanvas.pantallaCompleta] y [BotonFlotante].
                pantallaCompleta = soloElDibujo,
                onHerramientaRapida = { controller.selectTool(it); cambiado() },
                onGrosorRapido = { grosor ->
                    aplicarEstilo(controller.estiloActivo().copy(strokeWidth = grosor))
                    cambiado()
                },
                agarre = agarre,
                onChange = { mientrasSeTraza ->
                    cambiado(mientrasSeTraza)
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
                // El plano por trozos, si lo hay. Ver [prepararElMosaico].
                mosaico = mosaico,
                // Y el PDF a la resolución de la pantalla, para leerlo de cerca.
                laminaFina = laminaFina,
                // O, si el plano se abrió como líneas, las líneas: entonces no hace falta ni
                // el mosaico ni la lámina. Ver [PlanoEnPantalla] y [traerElPlanoEnLineas].
                planoVectorial = planoVectorial,
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

            // Lo que hay marcado y lo que se le puede tocar: lo miran tanto los mandos
            // como los paneles que salen debajo, así que se sacan una sola vez y **fuera**
            // del cromo, que va y viene con el gesto de los cuatro dedos.
            val seleccionado = controller.selectedElements()
            val aplican = propiedadesPara(controller.tool, seleccionado)
            // Los colores marcados en la rueda. Van en los ajustes y no en el dibujo: son de
            // quien dibuja. Ver [Settings.coloresMarcados].
            val marcasDeColor = remember(ajustes.coloresMarcados) {
                ajustes.coloresMarcados.split(',')
                    .map { it.trim() }
                    .filter { it.startsWith("#") && it.length == 7 }
                    .take(MARCAS_DE_COLOR)
            }

            // **Y todos los mandos se van con cuatro dedos.**
            //
            // Lo de aquí dentro es el cromo: el mando de lo elegido, las barras, el lateral,
            // el visor y los paneles. Lo de fuera es el dibujo y lo que se está escribiendo,
            // que son las dos cosas que tienen que seguir estando. Ver
            // [elToqueDeCuatroDedos].
            if (!soloElDibujo) {
                // **El mando de lo elegido**, el mismo que el del croquis en el espacio.
                //
                // **En la esquina de abajo**, del lado de la mano. Estaba a media altura del
                // canto y ahí estorbaba: media pantalla a ese lado es por donde uno arrastra
                // lo que acaba de elegir, así que el mando se ponía encima de la figura que
                // venía a mover. En la esquina no tapa nada y el pulgar sigue llegando.
                //
                // Sigue el lado de [zurdo] como todo lo demás del editor: para quien dibuja
                // con la izquierda, la esquina que cae bajo el pulgar es la otra.
                //
                // Solo sale con algo elegido, porque sin nada elegido no manda nada. Ver
                // [Mando].
                if (controller.selectedIds.isNotEmpty()) {
                    Mando(
                        controller = controller,
                        zoom = controller.scene.viewport.zoom,
                        alCambiar = { cambiado() },
                        modifier = Modifier
                            // **Siempre en la esquina de abajo a la derecha**, en los dos
                            // lienzos, y sin seguir el lado de la mano: es el mando que se
                            // busca con la vista y tiene que estar donde uno ya sabe, no
                            // donde le toque según un ajuste.
                            .align(Alignment.BottomEnd)
                            // **Menos lo que al mando le sobra de caja.** Su dibujo no está
                            // centrado en su cuadro —ver [LO_QUE_SOBRA_A_LA_DERECHA]— así que
                            // sin descontarlo lo que tocaba la esquina era el aire y el mando
                            // se leía torcido hacia dentro.
                            .offset(x = LO_QUE_SOBRA_A_LA_DERECHA, y = LO_QUE_SOBRA_ABAJO)
                            // Levantado lo que mide lo de abajo, medido y no supuesto.
                            .padding(
                                horizontal = 2.dp,
                                vertical = with(LocalDensity.current) {
                                    altoDeLoDeAbajo.toDp()
                                } + 10.dp
                            )
                    )
                }

                // **Qué estás editando.** El editor era un lienzo sin nombre: se
                // abría una página de un plano de doce y no había nada que dijera
                // cuál, así que había que acordarse de qué miniatura se tocó. Solo
                // sale anotando un PDF; en un dibujo suelto no hay nada que decir y
                // un rótulo vacío es ruido.
                // **Por debajo de la barra, no detrás de ella.** Iba a 10 dp del canto y la
                // barra ocupa esa franja: se solapaban y ganaba la barra, así que el número
                // de página de un PDF quedaba tapado justo cuando sirve para algo. Se aparta
                // lo que la barra mida **de verdad**, que cambia con las herramientas que
                // haya y con el alto de la pantalla.
                RotuloDeLaHoja(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            top = with(LocalDensity.current) { altoDeLaBarraDeArriba.toDp() } +
                                10.dp
                        )
                )

                // **Las islas cambian de lado con la mano.** El brazo entra por el
                // lado de su mano y tapa lo que hay debajo: lo que se toca a menudo
                // va donde llega el pulgar, y lo que se mira, al otro lado.
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
                    // **La barra mide lo que miden sus botones.**
                    //
                    // Con `weight(1f)` se estiraba de canto a canto siempre, así que con
                    // cuatro herramientas quedaba una pastilla vacía cruzando la pantalla
                    // por encima del dibujo. `fill = false` le da el mismo tope —no puede
                    // pasarse ni comerse la isla de enfrente— pero la deja encogerse hasta
                    // lo que ocupa de verdad.
                    Isla(Modifier.weight(1f, fill = false)) {
                        CarruselDeFunciones(
                            tick = tick,
                            cambiado = { cambiado() },
                            onColor = { paletaAbierta = !paletaAbierta },
                            onFiguras = { figurasAbiertas = true },
                            onTablas = {
                                tablaAbierta = controller.scene.tablas.firstOrNull()?.id
                                    ?: controller.addTabla(centroDeLaVista()).also { cambiado() }.id
                            },
                            onAjustes = { ajustesAbiertos = !ajustesAbiertos }
                        )
                    }
                }
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .onSizeChanged { altoDeLaBarraDeArriba = it.height }
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

                // **Pasar de hoja, en su propia fila debajo de la barra.**
                //
                // Estaban en el carrusel de arriba, entre lo que sirve para dibujar, y ahí
                // molestaban de dos maneras: apretaban al carrusel —que es donde se busca la
                // herramienta— y quedaban lejos del pulgar en un cuaderno de veinte hojas,
                // que es cuando se usan a todas horas. Van al canto contrario al de los
                // botones de lo marcado, para que no se peleen cuando salen los dos.
                if (controller.scene.marcos.isNotEmpty()) {
                    Isla(
                        Modifier
                            .align(if (zurdo) Alignment.TopEnd else Alignment.TopStart)
                            .padding(horizontal = 8.dp)
                            .padding(top = BAJO_LA_BARRA)
                    ) {
                        Row {
                            IconButton(onClick = { pasarDeHoja(-1); cambiado() }) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = getString(R.string.cd_hoja_anterior)
                                )
                            }
                            IconButton(onClick = { pasarDeHoja(1); cambiado() }) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = getString(R.string.cd_hoja_siguiente)
                                )
                            }
                        }
                    }
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
                // **Y debajo, en su propia caja, deshacer y rehacer.**
                //
                // Estaban arriba, en la caja de salir. Deshacer es el botón más pulsado de
                // cualquier editor y se pulsa **sin mirar**, así que su sitio es donde ya está la
                // mano —el lateral— y no al otro extremo de la pantalla. Van juntos en una
                // columna con el panel para que los dos se muevan de lado con la mano zurda de
                // una sola vez, y en caja aparte porque no son lo mismo: arriba, con qué se
                // dibuja; abajo, qué hacer con lo dibujado. Ver [CajaDeDeshacer].
                Column(
                    Modifier.align(if (zurdo) Alignment.CenterEnd else Alignment.CenterStart),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
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
                            // Diferido a propósito: se lee al pintar la muestra, no al
                            // componer el panel. Ver [zoomVivo].
                            zoom = { zoomVivo.floatValue },
                            anchoPintado = anchoDelTrazoAMano(seleccionado),
                            onAbrirColor = { tallerDeColor = !tallerDeColor },
                            marcasDeColor = marcasDeColor,
                            // Para que la bolita enseñe la tinta que va a salir y no la
                            // guardada: sobre papel oscuro no son la misma.
                            noche = noche
                        )
                    }
                    CajaDeDeshacer(
                        puedeDeshacer = controller.canUndo,
                        puedeRehacer = controller.canRedo,
                        onDeshacer = { controller.undo(); cambiado() },
                        onRehacer = { controller.redo(); cambiado() },
                        modifier = Modifier.padding(
                            horizontal = SEPARACION_DEL_BORDE,
                            vertical = 4.dp
                        )
                    )
                }

                // **El taller del color, al lado del lateral.**
                //
                // Sale de un toque en el mando del color y lleva la misma rueda que el arrastre,
                // la tira de negro a blanco y los colores de la paleta puesta. Con su velo
                // detrás: sin él, el dedo que iba al aro y falla por poco cae en el lienzo y deja
                // una raya. Ver [ElTallerDelColor].
                if (tallerDeColor) {
                    if (aplican.isEmpty() || editandoTexto != null) {
                        tallerDeColor = false
                    } else {
                        Velo(alTocar = { tallerDeColor = false }) { }
                        val estilo = controller.estiloActivo()
                        ElTallerDelColor(
                            actual = estilo.strokeColor,
                            // Los de uno primero y la paleta puesta detrás, sin repetir. Con los
                            // de uno **en vez de** los de siempre, guardar el primer color haría
                            // desaparecer el negro: se gana uno y se pierden ocho.
                            guardados = marcasDeColor + COMBINACIONES.first().colores
                                .filterNot { c -> marcasDeColor.any { it.equals(c, true) } },
                            marcas = marcasDeColor,
                            onElegir = { hex ->
                                aplicarEstilo(estilo.copy(strokeColor = hex))
                                cambiado()
                            },
                            onGuardar = { hex ->
                                val limpio = hex.lowercase()
                                val nuevas = (listOf(limpio) +
                                    marcasDeColor.filterNot { it.lowercase() == limpio })
                                    .take(MARCAS_DE_COLOR)
                                lifecycleScope.launch {
                                    (application as? com.forge.pixpin.PixPinApp)?.settings
                                        ?.setColoresMarcados(nuevas.joinToString(","))
                                }
                            },
                            modifier = Modifier
                                .align(if (zurdo) Alignment.CenterEnd else Alignment.CenterStart)
                                .padding(horizontal = ANCHO_DEL_LATERAL)
                        )
                    }
                }

                // **A qué zoom se está mirando, y el candado.**
                //
                // El zoom era invisible: se llegaba al 340 % sin saberlo y el trazo
                // salía «raro» sin motivo aparente, porque cuatro puntos a ese zoom
                // no son cuatro píxeles. Y el candado hace falta trabajando de
                // cerca: la mano apoya, el segundo dedo roza, y el encuadre que
                // costó encontrar se va sin querer.
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        // Lo que ocupa, para que el mando de la esquina se levante justo lo
                        // necesario y no se le monte encima. Ver [Mando].
                        .onSizeChanged { altoDeLoDeAbajo = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    VisorDeZoom(
                        // **El aumento se lee del vivo, no del tick.** El tick solo sube al
                        // levantar el dedo —rehace el editor entero— así que el porcentaje se
                        // quedaba clavado mientras se pellizcaba, que es justo cuando se está
                        // mirando. Ver [zoomVivo].
                        zoom = zoomVivo.floatValue,
                        bloqueado = zoomBloqueado,
                        onBloquear = { zoomBloqueado = !zoomBloqueado },
                        onCien = { alZoomCien(); cambiado() }
                    )
                    Spacer(Modifier.height(6.dp))
                    BarraHerramientas(
                        tick,
                        onImagen = { selectorImagen() },
                        noche = noche,
                        onAlternarNoche = {
                            // El botón de noche es el papel: pone el oscuro o devuelve el
                            // blanco. Ver [DrawTheme.PAPELES].
                            controller.ponerElPapel(
                                if (noche) DrawTheme.FONDO_DIA
                                else DrawTheme.fondoDe(true, ajustes.oledNegro)
                            )
                            // Pedir el claro de noche es pedirlo de verdad: ver [papel].
                            papelClaroForzado = noche
                            cambiado()
                        },
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
                        onCerrar = { figurasAbiertas = false },
                        onGrafica = {
                            figurasAbiertas = false
                            graficaAbierta = true
                        }
                    )
                }
            }

            if (graficaAbierta) {
                DialogoDeGrafica(
                    onCerrar = { graficaAbierta = false },
                    onAceptar = { peticion ->
                        graficaAbierta = false
                        val estilo = controller.scene.style
                        val elementos = Graficas.elementos(peticion, estilo) { texto, tamano ->
                            medirTexto(texto, estilo.fontFamily, tamano)
                        }
                        if (elementos.isNullOrEmpty()) {
                            android.widget.Toast.makeText(
                                this@DrawEditorActivity, R.string.grafica_formula_mal,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            controller.insertar(
                                estampar(
                                    FiguraGuardada("grafica", peticion.formula, elementos),
                                    centroDeLaVista()
                                )
                            )
                            guardar()
                            cambiado()
                        }
                    }
                )
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

            // **El botón flotante, solo en pantalla completa.** Se aprieta con la mano
            // que no dibuja mientras el lápiz elige o ajusta en el lienzo, y se mueve por
            // su asa. Ver [BotonFlotante].
            if (soloElDibujo) BotonFlotante(zurdo, agarre)

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

            if (pidiendoFuncionesWeb) {
                val marcadas = ajustes.funcionesWeb ?: ExportarHtml.Opciones.NOMBRES.toSet()
                DialogoDeFuncionesWeb(
                    marcadas = marcadas,
                    onCambio = { clave, puesta ->
                        val ahora = marcadas.toMutableSet()
                        if (puesta) ahora += clave else ahora -= clave
                        lifecycleScope.launch {
                            (application as? com.forge.pixpin.PixPinApp)?.settings?.setFuncionesWeb(ahora)
                        }
                    },
                    onCompartir = { pidiendoFuncionesWeb = false; compartirHtml() },
                    onCerrar = { pidiendoFuncionesWeb = false },
                    calidadDeAudio = ExportarHtml.calidadDeAudio(marcadas),
                    // Del lienzo salen dibujos, nunca notas: aquí no hay audio que ajustar.
                    hayAudio = false,
                    onCalidadDeAudio = { c ->
                        lifecycleScope.launch {
                            (application as? com.forge.pixpin.PixPinApp)?.settings?.setFuncionesWeb(ExportarHtml.conCalidadDeAudio(marcadas, c))
                        }
                    },
                    hojasDelLienzo = ExportarHtml.hojasDelLienzo(marcadas),
                    onHojasDelLienzo = { h ->
                        lifecycleScope.launch {
                            (application as? com.forge.pixpin.PixPinApp)?.settings?.setFuncionesWeb(ExportarHtml.conHojasDelLienzo(marcadas, h))
                        }
                    }
                )
            }
            // La ventana de ajustes: también sin velo, que se deja abierta a un
            // lado mientras se sigue dibujando.
            if (ajustesAbiertos) {
                // Lo que pesa este dibujo y su proyecto, medido fuera del hilo principal:
                // sumar un proyecto es abrir cada uno de sus lienzos. Ver [Detalle].
                val detalle by androidx.compose.runtime.produceState(Pair(emptyList<Pair<String, String>>(), emptyList<Pair<String, String>>()), tick) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val archivo = Detalle.delLienzo(this@DrawEditorActivity, dibujoId)
                        val app = application as? com.forge.pixpin.PixPinApp
                        val proyecto = app?.proyectos?.let { repo ->
                            intent?.getStringExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO)?.let { repo.porId(it) }
                                ?: Detalle.proyectoDelLienzo(repo.proyectos.value, dibujoId)
                        }
                        val delProyecto = proyecto?.let { Detalle.delProyecto(this@DrawEditorActivity, it) }
                        Pair(
                            listOf("Nombre" to archivo.nombre, "Peso" to Detalle.legible(archivo.bytes)),
                            delProyecto?.let { listOf("Nombre" to it.nombre, "Hojas" to "${it.hojas}", "Peso" to Detalle.legible(it.bytes)) } ?: emptyList()
                        )
                    }
                }
                VentanaDeAjustes(
                    detalleDelArchivo = detalle.first,
                    detalleDelProyecto = detalle.second,
                    cuadricula = cuadricula,
                    onCuadricula = { cuadricula = it; cambiado() },
                    zoomBloqueado = zoomBloqueado,
                    onZoomBloqueado = { zoomBloqueado = it },
                    modoDedo = controller.modoDedo,
                    onModoDedo = { controller.modoDedo = it; cambiado() },
                    // **Las dos maneras de traer un plano**, y solo cuando hay uno debajo.
                    // Ver [PlanoEnPantalla] y [traerElPlanoEnLineas].
                    planoEnLineas = if (pdfDeFondo != null) ajustes.planoEnLineas else null,
                    onPlanoEnLineas = { quiere ->
                        lifecycleScope.launch {
                            (application as? com.forge.pixpin.PixPinApp)?.settings
                                ?.setPlanoEnLineas(quiere)
                        }
                        val ruta = pdfDeFondo
                        val ancho = fondo?.width?.toDouble()
                        if (quiere) {
                            if (ruta != null && ancho != null && planoVectorial == null) {
                                lifecycleScope.launch { traerElPlanoEnLineas(ruta, ancho) }
                            }
                        } else {
                            // Se vuelve a la imagen: el mosaico se rehace desde cero, que es
                            // como estaba antes de leer las líneas.
                            planoVectorial?.soltar()
                            planoVectorial = null
                            planoParaLaWeb = null
                            planoLeido = null
                            anchoDelPlano = 0.0
                            if (ruta != null && ancho != null && mosaico == null) {
                                prepararElMosaico(ruta, ancho, fondo!!.height.toDouble())
                            }
                            tickDelMosaico++
                        }
                    },
                    presionFirme = controller.estiloActivo().presionFirme,
                    onPresionFirme = { firme ->
                        aplicarEstilo(controller.estiloActivo().copy(presionFirme = firme))
                        cambiado()
                    },
                    // La llave de paso de las luces: una para todo el dibujo, y guardada con
                    // él. Ver [LucesDelDibujo].
                    luces = controller.scene.luces,
                    onLuces = { controller.ponerLasLuces(it); ajustarElBrilloDeMas(); cambiado() },
                    // Las marcas del grosor y de la opacidad se ponen aquí desde que esos
                    // dos son mandos: un mando no tiene mango que tocar. El panel solo las
                    // respeta. Ver [MarcasGuardadas] y [MarcasDelDeslizador].
                    estilo = controller.estiloActivo(),
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
                    papel = papel,
                    onPapel2 = { controller.ponerElPapel(it); cambiado() },
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
                    // Sin botón de cerrar en la ventana, la misma entrada la quita.
                    onImagenDeReferencia = { if (referencia != null) referencia = null else selectorDeReferencia() },
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
            // **Del color de un contenedor levantado, no del fondo de la aplicación.**
            //
            // Iba del `surface` de fábrica, que es exactamente el color del fondo: con el
            // tema oscuro y el lienzo en negro, la isla era un rectángulo negro sobre negro
            // y lo único que la separaba era el filo de un punto. Los botones de dentro
            // parecían flotar sueltos sobre el dibujo, y los apagados no se veían en
            // absoluto. `surfaceContainerHigh` está para esto: una superficie que se levanta
            // sobre la de debajo, y que se despega tanto sobre un lienzo blanco como sobre
            // uno negro.
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
    private fun Velo(alTocar: () -> Unit = {}, contenido: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxSize()
                .background(Color(0x66000000))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { alTocar() },
            contentAlignment = Alignment.Center
        ) { contenido() }
    }

    /**
     * Salir: **en su propia caja**, pegada al canto.
     *
     * Iba en la misma fila que todo lo demás y no es lo mismo. Se pulsa a ciegas, así que
     * tiene que estar **siempre en el mismo sitio**; el resto se busca con la vista y aguanta
     * ir en un carrusel que se desplaza.
     *
     * Estuvieron aquí también deshacer y rehacer, por esa misma razón. Y estaban mal de
     * sitio por otra: se pulsan cada pocos segundos **mientras se dibuja**, y lo que se toca
     * dibujando va donde ya está la mano —el lateral— y no al otro extremo de la pantalla.
     * Ver [CajaDeDeshacer].
     */
    @Composable
    private fun CajaDeNavegacion(tick: Int, cambiado: () -> Unit) {
        @Suppress("UNUSED_EXPRESSION") tick
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { cerrarYVolver() }) {
                Icon(Icons.Filled.Close, contentDescription = getString(R.string.cd_close))
            }
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
     * ## Y en `Row`, no en `LazyRow`
     *
     * Aquí se rompe la regla de la casa —listas largas, siempre perezosas— y con motivo. Un
     * `LazyRow` **siempre ocupa todo el ancho que le den**: no sabe encogerse a lo que lleva
     * dentro, porque no sabe qué lleva dentro hasta que lo compone. Y lo que hace falta es
     * justo eso, que la barra mida lo que miden sus botones en vez de cruzar la pantalla
     * medio vacía.
     *
     * El precio es componer todos los botones aunque no se vean. Son menos de diez y cada uno
     * es un icono: la regla existe para listas de mil, no para esta. Si algún día esto pasa a
     * ser una lista de verdad, el problema vuelve y habrá que elegir otra vez.
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
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
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
            IconButton(onClick = { encuadrar(); cambiado() }) {
                Icon(
                    Icons.Filled.CenterFocusWeak,
                    contentDescription = getString(R.string.cd_encuadrar)
                )
            }
            // **Añadir hoja**, que es lo que convierte el lienzo en un cuaderno.
            //
            // Aquí y no en la barra de herramientas: no se dibuja nada con ella, se
            // añade algo de un toque — que es lo que hay en este carrusel. Y con hojas
            // puestas aparecen los dos de pasar página, que sin cuaderno no dirían nada.
            // Ver [Cuaderno].
            IconButton(onClick = {
                controller.anadirHoja(TamanoDePapel.A4, centroDeLaVista()); cambiado()
            }) {
                Icon(
                    Icons.Filled.NoteAdd,
                    contentDescription = getString(R.string.cd_anadir_hoja)
                )
            }
            // Pasar de hoja **ya no vive aquí**: ver [PasarDeHoja]. En el carrusel iba
            // apretando a lo que se usa para dibujar, y con varias hojas se usa a todas
            // horas: se ha bajado a su propia fila, debajo de la barra, como los botones
            // de lo que está marcado.
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
                    IconButton(onClick = { controller.girarVista(); cambiado() }) {
                        Icon(
                            Icons.Filled.Rotate90DegreesCcw,
                            contentDescription = getString(R.string.girar_vista)
                        )
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
            //
            // **Y solo con la herramienta que la usa en la mano.** Lo que hace es enderezar
            // rectas y redondear óvalos, así que con el lápiz, el borrador o la mano no
            // significa nada: era un interruptor encendido que no hacía nada, de los que
            // enseñan a desconfiar de la barra. Ver [Tool.isShape] y [Tool.isLinear].
            val herramienta = controller.tool
            if (herramienta.isShape || herramienta.isLinear) {
                IconButton(onClick = { figurasPerfectas = !figurasPerfectas }) {
                    Icon(
                        Icons.Filled.SquareFoot,
                        contentDescription = getString(R.string.figuras_perfectas),
                        tint = if (figurasPerfectas) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // **El color no está aquí.** Estuvo, cuando el lateral solo sabía cinco tonos
            // y había que subir a por la paleta entera. Desde que el mando del lateral
            // lleva la rueda —y el taller a un toque— esto era un segundo sitio para lo
            // mismo, justo el reparto que hace dudar dónde se cambia el color. El color se
            // cambia donde se dibuja. Ver [ElColorQueHay] y [PaletaDeColores].
            // **La lista de figuras.** Va aquí, al lado de encuadrar, porque es
            // de lo mismo: cosas que se hacen antes de ponerse a dibujar y no
            // mientras se dibuja. Ver [PanelDeFiguras].
            IconButton(onClick = onFiguras) {
                Icon(
                    Icons.Filled.Category,
                    contentDescription = getString(R.string.figuras_abrir)
                )
            }
            IconButton(onClick = onTablas) {
                Icon(
                    Icons.Filled.GridOn,
                    contentDescription = getString(R.string.tabla_abrir)
                )
            }
            // **Exportar ya no tiene botón propio.** Es una pestaña de la
            // ventana de ajustes: se toca al terminar, no dibujando, y tenerlo
            // en dos sitios era justo lo que había que quitar. Ver
            // [VentanaDeAjustes].
            IconButton(onClick = onAjustes) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = getString(R.string.ajustes_titulo)
                )
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
        // **Suscrito, no leído de una vez.** Con `.value` la composición no se apunta al
        // flujo, así que añadir una hoja dejaba el rótulo diciendo el total de antes: el
        // número solo se corregía cuando algo *aparte* obligaba a recomponer.
        val todos by app.proyectos.proyectos.collectAsState()
        val proyecto = Proyectos.deEstePdf(todos, ruta)
        val total = proyecto?.hojas?.size ?: 0
        val nombre = proyecto?.nombre ?: File(ruta).nameWithoutExtension

        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
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

    /** Pasa a la hoja de al lado y la encuadra. Ver [DrawController.pasarDeHoja]. */
    private fun pasarDeHoja(delta: Int) {
        val m = resources.displayMetrics
        controller.pasarDeHoja(delta, m.widthPixels.toDouble(), m.heightPixels.toDouble())
    }

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
            add(FormatoDeSalida(Icons.Filled.Language, getString(R.string.formato_html)) {
                // Primero qué lleva, y compartir desde ahí. Ver [DialogoDeFuncionesWeb].
                pidiendoFuncionesWeb = true
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
        zoom: Float,
        bloqueado: Boolean,
        onBloquear: () -> Unit,
        onCien: () -> Unit
    ) {
        val porcentaje = (zoom * 100).toInt()
        Surface(
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
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
                // El estilo y el deshacer se tocan los dos en el lateral: tenerlos
                // también aquí sería el mismo control dos veces, y entre ambos se
                // llevaban una fila entera de barra sobre el dibujo. Ver
                // [PanelLateralDeEstilo] y [CajaDeDeshacer].
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
    
    /** Cambia la forma de la flecha, en el pincel y en lo seleccionado. */
    
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
            getString(
                when (plano.type) {
                    ElementType.RECTA -> R.string.recta_titulo
                    ElementType.ESPACIO -> R.string.espacio_titulo
                    else -> R.string.plano_titulo
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        // El espacio, además, se mira desde algún sitio: cuánto se le ha dado
        // la vuelta (lo mismo que hace el tirador de giro) y cuánto desde
        // arriba. Ver [Espacio].
        if (plano.isEspacio) {
            GradosDelEspacio(
                getString(R.string.espacio_giro), plano.azimutDelEspacio, -180f..180f
            ) { nuevo ->
                controller.mutarSeleccion { it.copy(azimut = nuevo).touched() }
                cambiado()
            }
            GradosDelEspacio(
                getString(R.string.espacio_inclinacion), plano.elevacionDelEspacio, 0f..85f
            ) { nuevo ->
                controller.mutarSeleccion { it.copy(elevacion = nuevo).touched() }
                cambiado()
            }
        }
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

    /** Un ángulo del espacio, en grados, con su valor escrito al lado. */
    @Composable
    private fun GradosDelEspacio(
        titulo: String,
        actual: Double,
        rango: ClosedFloatingPointRange<Float>,
        onGrados: (Double) -> Unit
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(titulo, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("${Math.round(actual)}°", style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = actual.toFloat().coerceIn(rango.start, rango.endInclusive),
            onValueChange = { onGrados(it.toDouble()) },
            valueRange = rango,
            modifier = Modifier.padding(bottom = 2.dp)
        )
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

    /**
     * **Cerrar devuelve a donde se vino.** El editor vive en su propia tarea —tiene que ser
     * así para que abrirlo desde la bola no traiga la aplicación entera al frente—, y eso
     * hace que atrás no sea el proyecto que se estaba mirando sino lo que hubiera debajo.
     * Ver [com.forge.pixpin.EXTRA_DESDE_PROYECTO].
     */
    private fun cerrarYVolver() {
        val vuelta = intent?.getStringExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO)
        if (vuelta != null) com.forge.pixpin.volverALosProyectos(this, vuelta)
        finish()
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

    /**
     * Lo que se le dice a un cronograma que no se pueda decir arrastrando.
     *
     * Las barras se mueven y se estiran **encima del dibujo**, que es donde uno está
     * mirando; aquí quedan las dos cuentas —cuántas filas y cuántas columnas— y los
     * nombres, que hay que teclear.
     */
    @Composable
    private fun AjustesDelCronograma(plan: Element, cambiado: () -> Unit) {
        Text(
            getString(R.string.cronograma_titulo),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Fila(getString(R.string.cronograma_filas)) {
            GlifoBoton("−") { controller.quitarTarea(); cambiado() }
            Text(
                "${plan.tareas.size}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlifoBoton("+") { controller.anadirTarea(); cambiado() }
        }
        Fila(getString(R.string.cronograma_escala)) {
            GlifoBoton("−") { controller.cambiarPeriodos(-1); cambiado() }
            Text(
                "${plan.periodos}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlifoBoton("+") { controller.cambiarPeriodos(1); cambiado() }
        }
        // Un campo por fila y no un diálogo: con tres o cuatro tareas, abrir y cerrar una
        // ventana por cada nombre cuesta más que teclearlos seguidos.
        for ((i, t) in plan.tareas.withIndex()) {
            OutlinedTextField(
                value = t.nombre,
                onValueChange = { controller.renombrarTarea(i, it); cambiado() },
                placeholder = { Text(getString(R.string.cronograma_tarea, i + 1)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )
        }
    }

    /**
     * Lo que se le puede decir a una caja aparte de estirarla: **qué pieza es y
     * cuántas van**.
     *
     * Las medidas no están aquí a propósito: se cambian con los tiradores, encima del
     * dibujo, que es donde uno está mirando. Aquí quedan las dos cosas que no son un
     * arrastre.
     */
    @Composable
    private fun AjustesDeLaCaja(caja: Element, cambiado: () -> Unit) {
        Text(
            getString(R.string.caja_titulo),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        // **Macizo o de alambre.** Macizo se lee como bulto; en alambre se ve por dónde
        // entra una pieza en otra, que con las caras tapando no hay forma de enseñarlo.
        Fila(getString(R.string.caja_modo)) {
            GlifoBoton(if (!caja.esqueleto) "◼" else "◻") {
                controller.cambiarEsqueleto(false); cambiado()
            }
            GlifoBoton(if (caja.esqueleto) "⧉" else "⬚") {
                controller.cambiarEsqueleto(true); cambiado()
            }
        }
        // **Una copia por toque**, pegada a la anterior y en la dirección que se pulse.
        // Tres toques son tres módulos, y se ve crecer la fila. Ver [repetirSolido].
        Fila(getString(R.string.caja_repetir)) {
            GlifoBoton("→") { controller.repetirLaCaja(EjeDeRepeticion.ANCHO); cambiado() }
            GlifoBoton("↘") { controller.repetirLaCaja(EjeDeRepeticion.FONDO); cambiado() }
            GlifoBoton("↑") { controller.repetirLaCaja(EjeDeRepeticion.ALTO); cambiado() }
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
            val plano = seleccion.singleOrNull()?.takeIf { it.esInstrumento }
            if (plano != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                AjustesDelPlano(plano) { cambiado() }
            }

            // ---- Y la de la caja: qué pieza es y cuántas van ----
            val caja = seleccion.singleOrNull()?.takeIf { it.isSolido }
            if (caja != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                AjustesDeLaCaja(caja) { cambiado() }
            }

            // ---- La hoja: de qué tamaño es y qué pauta trae ----
            val hoja = seleccion.singleOrNull()?.takeIf { it.isFrame }
            if (hoja != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    getString(R.string.hoja_titulo),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Fila(getString(R.string.hoja_tamano)) {
                    for (t in TamanoDePapel.entries) {
                        GlifoBoton(if (hoja.papel == t) "▣" else "▢") {
                            controller.cambiarPapel(t); cambiado()
                        }
                    }
                }
                Fila(getString(R.string.hoja_pauta)) {
                    GlifoBoton(if (hoja.pauta == PautaDeHoja.LISA) "▣" else "▢") {
                        controller.cambiarPauta(PautaDeHoja.LISA); cambiado()
                    }
                    GlifoBoton(if (hoja.pauta == PautaDeHoja.RAYADA) "▤" else "☰") {
                        controller.cambiarPauta(PautaDeHoja.RAYADA); cambiado()
                    }
                    GlifoBoton(if (hoja.pauta == PautaDeHoja.CUADROS) "▦" else "⊞") {
                        controller.cambiarPauta(PautaDeHoja.CUADROS); cambiado()
                    }
                    GlifoBoton(if (hoja.pauta == PautaDeHoja.PUNTOS) "⁙" else "⋯") {
                        controller.cambiarPauta(PautaDeHoja.PUNTOS); cambiado()
                    }
                }
            }

            // ---- El cronograma: cuántas filas, cuántas columnas y cómo se llaman ----
            val plan = seleccion.singleOrNull()?.takeIf { it.type == ElementType.CRONOGRAMA }
            if (plan != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                AjustesDelCronograma(plan) { cambiado() }
            }

            // ---- Y la de una imagen: de cara o tumbada en el suelo ----
            val foto = seleccion.singleOrNull()?.takeIf { it.type == ElementType.IMAGE }
            if (foto != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    getString(R.string.imagen_titulo),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                // Tumbada sirve para meter un plano de planta debajo de un croquis en
                // volumen y levantar las cajas encima, a escala. Ver [Element.enElSuelo].
                Fila(getString(R.string.imagen_plano)) {
                    GlifoBoton(if (!foto.enElSuelo) "▦" else "▤") {
                        controller.tumbarImagen(false); cambiado()
                    }
                    GlifoBoton(if (foto.enElSuelo) "◱" else "◰") {
                        controller.tumbarImagen(true); cambiado()
                    }
                }
            }

            // ---- Las acciones, agrupadas por lo que hacen ----
            if (grupos.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (GrupoAcciones.ORDEN in grupos) Fila("Orden") {
                GlifoBoton("⤓") { controller.sendToBack(); cambiado() }
                GlifoBoton("↓") { controller.sendBackward(); cambiado() }
                GlifoBoton("↑") { controller.bringForward(); cambiado() }
                GlifoBoton("⤒") { controller.bringToFront(); cambiado() }
            }
            // **El candado, que el modelo tenía y la interfaz no ofrecía.**
            //
            // `Element.locked` existe desde el principio y el borrador ya lo respeta
            // —ver [intocableParaElBorrador]—, pero no había ningún sitio donde ponerlo:
            // era una promesa sin puerta. Una tabla o un esquema de fondo, con el
            // candado echado, dejan de irse por delante al pasar el borrador buscando
            // una raya, que es de las cosas que más rabia dan.
            if (seleccion.isNotEmpty()) {
                val abierto = seleccion.any { !it.locked }
                Fila(getString(R.string.candado)) {
                    IconoBoton(
                        if (abierto) Icons.Filled.LockOpen else Icons.Filled.Lock,
                        getString(if (abierto) R.string.candado_echar else R.string.candado_quitar)
                    ) { controller.toggleLockSelection(); cambiado() }
                }
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
    /**
     * Una opción que es **la letra a su tamaño**.
     *
     * Para el tamaño de fuente, dibujar una «A» de cada tamaño dice más que
     * numerarlas «1 2 3 4»: se elige mirando cuál se parece a lo que quieres,
     * no traduciendo un número.
     */
    /** Una opción del panel, representada por un glifo. */
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

    private fun aviso(texto: String) {
        android.widget.Toast.makeText(this, texto, android.widget.Toast.LENGTH_LONG).show()
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
    /**
     * La página del PDF que se ve de fondo, para que las salidas la lleven.
     *
     * Solo la de un PDF: la foto de un pin ya entra como elemento clavado y
     * saldría dos veces.
     */
    /**
     * **Cualquier PDF de fondo se trae por trozos.**
     *
     * La imagen de una sola pieza se saca a 1400 píxeles de ancho **sea cual sea la página**,
     * así que el techo de calidad no lo pone el tamaño del plano: lo pone esa cifra. En un A0
     * son cinco píxeles por centímetro y no se lee una cota, pero es que en un A3 con la
     * letra pequeña pasa lo mismo en cuanto uno se acerca. Estuvo puesto solo para páginas
     * mayores que un A0 y ese era el motivo de que un plano corriente siguiera viéndose mal.
     *
     * Con el mosaico, lo que se pinta se rasteriza **a la resolución que pide el aumento**,
     * como hacen los mapas, y no cuesta nada cuando se está mirando el plano entero: ahí el
     * nivel cero son treinta cuadros. Ver [ElMosaicoDelPapel] y [MosaicoDePdf].
     */
    private fun prepararElMosaico(ruta: String, ancho: Double, alto: Double) {
        val memoria = (getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.memoryClass ?: 128
        mosaico = ElMosaicoDelPapel(
            ruta, paginaDeFondo, ancho, alto, lifecycleScope, cacheDir,
            MosaicoDePdf.presupuestoDe(memoria)
        ) {
            // Un trozo recién llegado no cambia nada del dibujo: solo hay que repintar.
            runOnUiThread { tickDelMosaico++ }
        }
        // **Y lo que se está mirando, pedido al PDF a la resolución de la pantalla.** Los
        // cuadros del mosaico son una imagen fija y llegan hasta donde llegan; pasado ese
        // aumento, leer una letra solo lo da volver al documento. Ver [LaminaDeCerca].
        laminaFina = LaminaDeCerca(ruta, paginaDeFondo, ancho, alto, lifecycleScope) {
            runOnUiThread { tickDelMosaico++ }
        }
    }

    /** El PDF a la resolución de la pantalla, para leerlo de cerca. Ver [LaminaDeCerca]. */
    private var laminaFina: LaminaDeCerca? = null

    /** El plano por trozos, si el papel es más grande que un A0. Ver [prepararElMosaico]. */
    private var mosaico: ElMosaicoDelPapel? = null

    /** Sube cada vez que llega un trozo del plano: es lo que pide repintar. */
    private var tickDelMosaico by mutableIntStateOf(0)

    /**
     * **El plano leído como líneas**, si el PDF se dejó y el ajuste está puesto.
     *
     * Es la otra manera de tener el papel: ver [PlanoEnPantalla]. Mientras se lee —unos
     * segundos en un plano grande— se sigue viendo el mosaico, y en cuanto está, se cambia.
     */
    private var planoVectorial by mutableStateOf<PlanoEnPantalla?>(null)

    /**
     * **El plano leído como geometría**, para poder empaquetarlo para la web si se exporta.
     *
     * La lectura es lo caro; una vez leído, [PlanoWeb.aJson] se hace solo cuando hace falta
     * (al exportar) y se recuerda en [planoParaLaWeb]. Antes el empaquetado se hacía siempre
     * al abrir —aunque nadie fuera a exportar— y un plano grande tardaba el doble en
     * aparecer en pantalla. Ver [PlanoWeb] y [compartirHtml].
     */
    private var planoLeido: PlanoDePdf.Plano? = null
    private var anchoDelPlano = 0.0

    /** El mismo plano, ya empaquetado para la página web, o null si no hace falta aún. */
    private var planoParaLaWeb: String? = null

    /** Si se está eligiendo qué lleva la página web antes de compartirla. */
    private var pidiendoFuncionesWeb by mutableStateOf(false)

    /**
     * Lee el PDF como geometría, en segundo plano.
     *
     * Se hace fuera del hilo de la pantalla porque un plano de verdad son un par de segundos
     * de lectura, y se comprueba que el resultado siga sirviendo —que no se haya cerrado el
     * dibujo entretanto— antes de tocar nada.
     */
    private suspend fun traerElPlanoEnLineas(ruta: String, ancho: Double): Boolean {
        val hecho = withContext(Dispatchers.IO) {
            runCatching {
                val plano = PlanoDePdf.deArchivo(ruta, paginaDeFondo) ?: return@runCatching null
                if (!plano.valeLaPena || plano.sinEntender > 0) return@runCatching null
                // **Solo la pantalla: la web se empaqueta cuando se exporta.** Empaquetar aquí,
                // aunque nadie fuera a exportar, hacía que un plano grande tardara el doble en
                // aparecer (ver [planoLeido]/[planoParaLaWeb]).
                PlanoEnPantalla.de(plano, ancho) to plano
            }.getOrNull()
        }
        val leido = hecho?.first ?: return false
        if (pdfDeFondo != ruta) return false
        planoLeido = hecho.second
        anchoDelPlano = ancho
        planoParaLaWeb = null
        // El obrero que pinta la lámina, y a quién avisar cuando esté. Ver
        // [PlanoEnPantalla.pintar].
        planoVectorial = leido.conObrero(lifecycleScope) { runOnUiThread { tickDelMosaico++ } }
        // Los cuadros y la lámina, si los había, ya no hacen falta: sueltan su memoria y dejan
        // de rasterizar. Es justo lo que se quería quitar de en medio — que acercarse dejara
        // de tener que cargar nada.
        mosaico?.soltar()
        mosaico = null
        laminaFina?.soltar()
        laminaFina = null
        tickDelMosaico++
        return true
    }

    private fun papelDeFondo(): android.graphics.Bitmap? =
        if (pdfDeFondo != null) fondo else null

    private fun compartirSvg() = exportando(DrawSvg.MIME_TYPE) {
        DrawSvg.aArchivo(this, controller.scene, dibujoId, ::bitmapDe, papelDeFondo())
    }

    /**
     * El dibujo como **página HTML que se ve sola**: quien la reciba la abre en
     * cualquier navegador y puede pasear y hacer zoom, sin instalar nada. El
     * dibujo va dentro como SVG —el mismo del formato SVG— y el visor, escrito
     * en la propia página. Ver [ExportarHtml].
     */
    private fun compartirHtml() = exportando(ExportarHtml.MIME_TYPE) {
        val escena = controller.scene
        val papel = papelDeFondo()
        val fondo = Svg.hex(parseColor(escena.backgroundColor))
        // **Una hoja del dibujo, una página del documento**, como en el PDF: si hay tres
        // marcos es que se están montando tres láminas y se mandan juntas, con su menú
        // para pasar de una a otra. Anotando un PDF no: ahí la página es la del PDF.
        val marcos = if (papel != null) emptyList() else escena.marcos.filter { m ->
            val c = getElementBounds(m)
            c.width > 0 && c.height > 0 && escena.contenidoDe(m).isNotEmpty()
        }
        // **Qué hojas salen** lo dice el panel de la web: solo los marcos, el lienzo entero,
        // o ambos (el entero primero y detrás cada marco). Antes, con dos marcos, salían
        // solo los marcos y el lienzo entero no se podía mandar (lo pidió el usuario el
        // 6-sep-2026). Sin marcos, siempre el entero.
        val funciones = (application as? com.forge.pixpin.PixPinApp)?.settings?.settings?.first()?.funcionesWeb
        val cuales = ExportarHtml.hojasDelLienzo(funciones)
        val deMarcos = if (marcos.isNotEmpty() && cuales != ExportarHtml.HOJAS_ENTERO) {
            marcos.mapIndexedNotNull { i, m ->
                val svg = DrawSvg.aTexto(this, escena, ::bitmapDe, soloEstaHoja = m)
                    ?: return@mapIndexedNotNull null
                ExportarHtml.HojaWeb.Dibujo(
                    m.name?.takeIf { it.isNotBlank() } ?: "Hoja ${i + 1}", svg, fondo,
                    escala = escena.escala
                )
            }
        } else emptyList()
        val entera = if (marcos.isEmpty() || cuales != ExportarHtml.HOJAS_MARCOS) {
            // **Si el PDF de debajo son líneas, viajan las líneas y no una foto suya.** Un
            // plano leído como geometría se ve nítido a cualquier aumento, trae sus capas
            // para encender y apagar, y encima pesa menos que la página rasterizada. Cuando
            // no se puede —una página escaneada, que por dentro es una imagen— se manda la
            // página al detalle, como hasta ahora. Ver [PlanoWeb] y [PdfDoc.paraLaWeb].
            val plano = planoParaLaWeb
                ?: planoLeido?.let { p ->
                    // Empaquetado perezoso: se hace aquí, al exportar, y se recuerda para que
                    // exportar dos veces no lo repita. Ver [traerElPlanoEnLineas].
                    PlanoWeb.aJson(p, anchoDelPlano).also { planoParaLaWeb = it }
                }
                ?: pdfDeFondo?.takeIf { paginaDeFondo >= 0 && papel != null }
                    ?.let { PlanoWeb.deArchivo(it, paginaDeFondo, papel!!.width.toDouble()) }
            val fina = if (plano != null) null else pdfDeFondo?.takeIf { paginaDeFondo >= 0 }
                ?.let { PdfDoc.paraLaWeb(it, paginaDeFondo) }
            val svg = DrawSvg.aTexto(
                this, escena, ::bitmapDe, papel, fina, papelAparte = plano != null
            ) ?: return@exportando null
            listOf(ExportarHtml.HojaWeb.Dibujo(if (deMarcos.isEmpty()) "" else "Lienzo", svg, fondo, plano, escala = escena.escala))
        } else emptyList()
        val hojas = entera + deMarcos
        if (hojas.isEmpty()) return@exportando null
        // **Sin `runCatching` aquí a propósito.** Lo había, y convertía cualquier fallo al
        // montar la página —una hoja que no cabe en memoria, un medio que no se deja
        // leer— en un `null`, que arriba se anunciaba como «el dibujo está vacío». Que
        // suba: [exportando] ya distingue el fallo del documento vacío y lo registra.
        val opciones = ExportarHtml.Opciones.de(funciones)
        val pagina = ExportarHtml.paginas(
            hojas, titulo = getString(R.string.formato_html_titulo), opciones = opciones
        )
        val carpeta = File(cacheDir, "share").apply { mkdirs() }
        File(carpeta, "$dibujoId.html").also { it.writeText(pagina) }
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
            val bitmap = DrawExport.aBitmap(
                controller.scene, imageProvider = ::bitmapDe, papel = papelDeFondo()
            )
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
            // **Un fallo no es un dibujo vacío, y decirlo mal cuesta caro.**
            //
            // Esto era `runCatching { ... }.getOrNull()`: cualquier excepción al escribir
            // —una fuente que no carga, un PDF que no se deja leer, memoria que no
            // alcanza— salía por pantalla como «El dibujo está vacío». El usuario lo
            // reportó el 8-sep-2026 exportando un lienzo con cosas dentro a página web:
            // «me dice que no hay nada, que el dibujo está vacío y que no se puede hacer
            // nada». No había forma de saber qué pasaba, ni desde fuera ni desde dentro,
            // porque la excepción se tiraba sin registrarla.
            //
            // Ahora son dos cosas distintas: **null** es que de verdad no hay nada que
            // sacar, y **excepción** es que algo se rompió — se registra con su traza y
            // se dice que ha fallado, no que esté vacío.
            val salida = withContext(Dispatchers.IO) { runCatching { escribir() } }
            exportando = false
            val fallo = salida.exceptionOrNull()
            if (fallo != null) {
                android.util.Log.e("PixPinExportar", "exportar a " + mime + ": " + fallo, fallo)
                android.widget.Toast.makeText(
                    this@DrawEditorActivity,
                    getString(R.string.export_fallo, fallo.javaClass.simpleName),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val archivo = salida.getOrNull()
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
    // **Cualquier archivo se ofrece como archivo o como enlace.** Ver [CompartirEnlaceActivity].
    private fun compartirArchivo(archivo: File, mime: String) {
        com.forge.pixpin.ui.CompartirEnlaceActivity.abrir(
            this, archivo, archivo.name.substringBeforeLast('.'), mime
        )
    }

    /**
     * **Editable: un `.pixpin` con este lienzo.** Es el formato único de PixPin —un ZIP con el
     * lienzo en JSON de Excalidraw, sus fotos y, si lo hay, la nota— que se abre y se sigue
     * editando en otro aparato o en la versión de escritorio. Ver [PaquetePixpin].
     */
    private fun compartir() = exportando(PaquetePixpin.MIME_TYPE) {
        guardarYa()
        // En `share/` y no en la raíz de la caché: el FileProvider solo publica
        // esa subcarpeta (`res/xml/file_paths.xml`), y desde fuera el archivo
        // daría un fallo de permisos.
        val carpeta = File(cacheDir, "share").apply { mkdirs() }
        val nombre = dibujoId.ifBlank { "dibujo" }
        val suelto = Proyecto(
            id = "suelto-$dibujoId", nombre = nombre, tocado = System.currentTimeMillis(),
            hojas = listOf(Hoja(id = "hoja-$dibujoId", nombre = nombre, dibujo = dibujoId, pagina = paginaDeFondo.takeIf { it >= 0 })),
            pdfOrigen = pdfDeFondo, pdfLimpio = null
        )
        PaquetePixpin.escribir(this, suelto, File(carpeta, "${ExportarProyecto.nombreDeArchivo(nombre)}.${PaquetePixpin.EXTENSION}"))
    }
}

// -------------------------------------------------------------------------
// Paletas
//
// Son las de Excalidraw, no unas cualquiera: un dibujo hecho aquí y abierto
// allí —o al revés— tiene que verse igual, y los colores forman parte de eso.
// -------------------------------------------------------------------------


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
 * Lo ancho que se lleva la barra lateral, para dejar el taller del color justo a su lado.
 *
 * Es su hueco del borde más la bolita más su relleno. Medirlo de verdad —con
 * `onSizeChanged`— sería más exacto y costaría una vuelta de medida para colocar algo que
 * siempre mide lo mismo. Ver [PanelLateralDeEstilo].
 */
private val ANCHO_DEL_LATERAL = 76.dp

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

