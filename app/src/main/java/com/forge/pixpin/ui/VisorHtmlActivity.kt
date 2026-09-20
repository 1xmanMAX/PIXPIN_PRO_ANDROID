package com.forge.pixpin.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.forge.pixpin.R
import com.forge.pixpin.guardados.aPantallaCompleta
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.ByteArrayInputStream
import java.io.File
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Ver una página web guardada, sin salir de PixPin.**
 *
 * Un `.html` de la conversación salía con «abrir con» a un navegador, y la mitad de los
 * navegadores de Android ni se ofrecen para un archivo local. Y justo los `.html` que más
 * circulan por aquí son **los nuestros**: lo que saca el exportador «Página web»
 * ([com.forge.pixpin.motor.ExportarHtml]) es un solo archivo, interactivo, con su guion dentro.
 * Que la aplicación que los hace no supiera enseñarlos era raro (usuario, 19-sep-2026).
 *
 * **Sin ninguna librería**: el `WebView` es del sistema, como el `PdfRenderer` del lector de
 * PDF. Ver [com.forge.pixpin.pdf.LectorPdfActivity], del que esto es el hermano pequeño.
 *
 * ## Lo que se le deja hacer a la página, y lo que no
 *
 * - **JavaScript y almacenamiento DOM, sí**: las páginas exportadas son un visor entero
 *   —capas, medir, pasar hojas— y sin guion son una hoja en blanco.
 * - **Archivos, solo su carpeta.** La página no se abre donde está guardada —al lado de
 *   todos los demás adjuntos del chat— sino **una copia en una carpeta propia** de la caché,
 *   y todo `file://` que apunte fuera de esa carpeta se corta (ver [Cliente]). Una página
 *   que llegó de otro no tiene por qué poder asomarse a los archivos de la aplicación.
 * - **Los enlaces de la red salen al navegador**: esto es un visor de un documento, no un
 *   navegador; lo que la página cargue de la red para pintarse (una fuente, una imagen) sí
 *   se deja, como en cualquier sitio.
 */
class VisorHtmlActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_RUTA = "ruta"
        private const val EXTRA_NOMBRE = "nombre"
        private const val EXTRA_COMPARTE = "comparte"
        private const val EXTRA_SIN_GUION = "sinGuion"
        private const val EXTRA_MENSAJE = "mensaje"
        private const val EXTRA_EN_SU_SITIO = "enSuSitio"
        private const val EXTRA_DOCUMENTO = "documento"

        /**
         * Lo que encoge el documento en la vista alejada: **el texto y un margen** —la columna más
         * dos tercios: 5/3—, no los dos a la vez (corrección del usuario, 20-sep-2026: con los dos
         * el texto quedaba demasiado pequeño para leerlo mientras se anota al lado). Se ve el texto
         * con el margen de la izquierda o con el de la derecha; se pasa de uno a otro deslizando.
         */
        private const val ALEJADO = 3f / 5f

        /** Lo más que se acerca un documento. Lo menos es 1: el texto de borde a borde. */
        private const val AUMENTO_MAXIMO = 5f

        /** Lo que tarda la burbuja en esconderse sola, en milisegundos. */
        private const val LO_QUE_DURA_LA_BURBUJA = 3500L

        /** Lo más grande que se acepta de una descarga de la página, en caracteres de base64 (~30 MB). */
        private const val TOPE_DE_LO_BAJADO = 40_000_000

        /**
         * Lo que se le inyecta a la página: `window.print` pasa por el puente, y un enlace con
         * `download` que apunte a un `blob:` o un `data:` se lee y se entrega por el puente.
         * En ES5, que el `WebView` de un teléfono viejo no sabe más.
         */
        private const val GUION_DEL_VISOR = """(function(){
  if(window.__pixpinVisor) return; window.__pixpinVisor=1;
  window.print=function(){ PixPinVisor.imprimir(); };
  function huella(t){ var h=5381,i=t.length; while(i) h=(h*33)^t.charCodeAt(--i); return (h>>>0)+':'+t.length; }
  var tocado=false, base=null;
  function ahora(){ try{ return typeof window.paginaAnotada==='function'?huella(window.paginaAnotada()):null; }catch(e){ return null; } }
  setTimeout(function(){ base=ahora(); },600);
  document.addEventListener('input',function(){ tocado=true; },true);
  document.addEventListener('change',function(){ tocado=true; },true);
  window.__pixpinHayCambios=function(){ var a=ahora(); return a!==null&&base!==null ? a!==base : tocado; };
  window.__pixpinGuardado=function(){ base=ahora(); tocado=false; };
  function bajar(a){
    try{
      fetch(a.href).then(function(r){ return r.blob(); }).then(function(b){
        var fr=new FileReader();
        fr.onload=function(){ PixPinVisor.guardar(a.download||'archivo', String(fr.result).split(',')[1]||''); };
        fr.readAsDataURL(b);
      });
    }catch(e){}
  }
  function esDeBajar(a){ return a && a.hasAttribute && a.hasAttribute('download') && /^(blob:|data:)/.test(a.href||''); }
  var clic=HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click=function(){ if(esDeBajar(this)){ bajar(this); return; } return clic.apply(this,arguments); };
  document.addEventListener('click',function(ev){
    var a=ev.target&&ev.target.closest?ev.target.closest('a'):null;
    if(esDeBajar(a)){ ev.preventDefault(); bajar(a); }
  },true);
})();"""

        /** La carpeta de la caché donde se deja la copia que se enseña. */
        private const val CARPETA = "visor-html"

        /** Si por el nombre (o la ruta) es una página web. */
        fun esHtml(nombre: String?): Boolean =
            when (nombre.orEmpty().substringAfterLast('.', "").lowercase()) {
                "html", "htm", "xhtml" -> true
                else -> false
            }

        /**
         * [nombre] es lo que se lee arriba. Los otros dos son para cuando la página **no es el
         * documento, sino lo que se ha sacado de él** —un Word pasado a HTML por
         * [com.forge.pixpin.motor.DocxAHtml]—: [comparte] es el archivo que sale al compartir
         * (el `.docx` de verdad, no la página fabricada), y [sinGuion] apaga JavaScript, que
         * una página así no lo lleva y lo que llega de otro no tiene por qué ejecutar nada.
         * Sin ellos, todo sigue como siempre: se comparte la propia página y el guion va encendido.
         */
        fun abrir(
            context: Context, ruta: String, nombre: String,
            comparte: String? = null, sinGuion: Boolean = false,
            /** El mensaje del chat del que sale: es al que se le cambia el nombre desde la burbuja. */
            mensaje: String? = null,
            /**
             * La página **no se copia**: se enseña donde está, con lo que tenga al lado. Es para un
             * libro (`.epub`) ya desempaquetado en su carpeta de la caché, con sus imágenes.
             */
            enSuSitio: Boolean = false,
            /**
             * Es un **documento para leer** (un Word, un libro): el menú ofrece verlo como saldría
             * impreso y meterlo en un proyecto como un PDF.
             */
            documento: Boolean = false
        ) {
            val intent = Intent(context, VisorHtmlActivity::class.java)
                .putExtra(EXTRA_RUTA, ruta)
                .putExtra(EXTRA_NOMBRE, nombre)
                .putExtra(EXTRA_SIN_GUION, sinGuion)
            if (comparte != null) intent.putExtra(EXTRA_COMPARTE, comparte)
            if (mensaje != null) intent.putExtra(EXTRA_MENSAJE, mensaje)
            intent.putExtra(EXTRA_EN_SU_SITIO, enSuSitio).putExtra(EXTRA_DOCUMENTO, documento)
            context.startActivity(intent)
        }
    }

    /** El de ahora, para que «atrás» pueda volver dentro de la página y para soltarlo al salir. */
    private var web: WebView? = null

    /** El archivo que sale al compartir cuando no es la propia página; y si la página va sin guion. Ver [abrir]. */
    private var comparte: File? = null
    private var sinGuion = false

    /** El mensaje del chat al que se le cambia el nombre, si se vino de uno. */
    private var mensaje: String? = null
    private var enSuSitio = false
    private var esDocumento = false

    /** El archivo de verdad: el que se reescribe cuando la página se guarda a sí misma. */
    private var elOriginal: File? = null

    // ---- Leer a gusto: la letra y los marcadores. Ver [com.forge.pixpin.motor.Lectura]. ----
    private val prefsDeLectura by lazy { getSharedPreferences("lectura", Context.MODE_PRIVATE) }
    private var tamanoDeLetra by mutableStateOf(100)
    private var grosorDeLetra by mutableStateOf(1)
    private var tipoDeLetra by mutableStateOf(0)
    private var marcadores by mutableStateOf(emptyList<com.forge.pixpin.motor.Lectura.Marcador>())

    /** A qué fracción del documento hay que ir en cuanto la página esté cargada, o −1. */
    private var fraccionPendiente = -1f

    /** Con qué se guarda lo de este documento: el archivo de verdad, no la página fabricada. */
    private val claveDelDocumento: String get() = "doc:" + (comparte ?: elOriginal)?.absolutePath.orEmpty()

    /** Lo alto que es el documento entero, en píxeles de pantalla. */
    @Suppress("DEPRECATION")
    private fun altoDelDocumento(): Float = web?.let { it.contentHeight * it.scale } ?: 0f

    private fun fraccionDeAhora(): Float {
        val alto = altoDelDocumento()
        return if (alto <= 0f) 0f else ((web?.scrollY ?: 0) / alto).coerceIn(0f, 1f)
    }

    /** Lleva lo alto de la pantalla a esa fracción del documento. Si aún no se ha medido, reintenta. */
    private fun irALaFraccion(f: Float, intentos: Int = 12) {
        val vista = web ?: return
        val alto = altoDelDocumento()
        if (alto <= vista.height && intentos > 0 && !(intentos < 6 && alto > 0f)) {
            vista.postDelayed({ irALaFraccion(f, intentos - 1) }, 120)
            return
        }
        @Suppress("DEPRECATION")
        val x = columnaDeAnotar?.let { (com.forge.pixpin.motor.Lectura.margenDe(it) * vista.scale).toInt() } ?: 0
        vista.scrollTo(x, (f * alto).toInt().coerceAtLeast(0))
        if (entrarAlCargar) {
            entrarAlCargar = false
            vista.post { ponerLaCapaDondeElDocumento(); anotando = true }
        }
    }

    private fun guardarMarcadores() {
        prefsDeLectura.edit().putString(claveDelDocumento + ":marcadores", com.forge.pixpin.motor.Lectura.aTexto(marcadores)).apply()
    }

    /** Reescribe la página que se enseña con la letra pedida, y la recarga **sin perder el sitio**. */
    private fun ponerLaLetra(grosor: Int, tipo: Int) {
        grosorDeLetra = grosor
        tipoDeLetra = tipo
        prefsDeLectura.edit().putInt("grosor", grosor).putInt("tipo", tipo).apply()
        val pagina = laPaginaQueSeVe ?: return
        fraccionPendiente = fraccionDeAhora()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { pagina.writeText(com.forge.pixpin.motor.Lectura.conEstilo(pagina.readText(), grosor, tipo, columnaDeAnotar, esDeNoche())) }
            }
            web?.reload()
        }
    }

    private fun ponerElTamano(t: Int) {
        tamanoDeLetra = com.forge.pixpin.motor.Lectura.tamanoValido(t)
        prefsDeLectura.edit().putInt("tamano", tamanoDeLetra).apply()
        val donde = fraccionDeAhora()
        web?.settings?.textZoom = tamanoDeLetra
        // El documento se alarga o se encoge: se vuelve al mismo párrafo, no al mismo píxel.
        web?.postDelayed({ irALaFraccion(donde) }, 150)
    }

    private var laPaginaQueSeVe: File? = null

    /** Si el aparato está en modo oscuro: decide el papel del documento y, con él, la tinta de lo anotado. */
    private fun esDeNoche(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    // ---- Anotar encima del documento, con el motor del lienzo. Ver [CapaDeAnotar]. ----

    /** El ancho de la columna de texto, en píxeles CSS, desde que se anotó por primera vez; null si nunca. */
    private var columnaDeAnotar by mutableStateOf<Int?>(null)
    private var anotando by mutableStateOf(false)
    /** Hay que entrar a anotar en cuanto la página recargada (ya con márgenes) esté lista. */
    private var entrarAlCargar = false
    /** Por dónde va el documento y a qué escala, para pintar la capa en su sitio mientras se lee. */
    private var corridoX by mutableStateOf(0)
    private var corridoY by mutableStateOf(0)
    private var escalaWeb by mutableStateOf(1f)
    private var cambiosEnLaCapa by mutableStateOf(0)
    /** Lo que se redibuja en los mandos de anotar; y si se ha visto un lápiz (entonces el dedo mueve). */
    private var tickDeAnotar by mutableStateOf(0)
    private var conLapiz by mutableStateOf(false)

    /**
     * **El aumento de un documento es nuestro, no del `WebView`** (20-sep-2026). Con el suyo, el
     * texto se ampliaba por su cuenta —en otro hilo— y la capa de lo anotado se enteraba tarde:
     * mientras se pellizcaba, **la tinta «vibraba»** y solo se quedaba quieta al parar. Ahora el
     * documento y la capa van **dentro de la misma capa de pintado**, y el pellizco la estira
     * entera: no hay dos cosas que poner de acuerdo, así que nada tiembla. De 1 —el texto de borde
     * a borde, como se abre; **no se aleja más que eso**— a [AUMENTO_MAXIMO], leyendo y anotando.
     */
    private var aumento by mutableStateOf(1f)
    private var corridoDelAumento by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)

    /**
     * **Alejarse hasta ver el texto con sus dos márgenes** (20-sep-2026). Con márgenes para anotar,
     * «de borde a borde» ya no es lo más lejos que interesa: lo más lejos es **la columna y los dos
     * tercios de cada lado a la vez**, para ver de un golpe el texto y lo que hay anotado junto a
     * él. Estirar la capa no sirve para eso —encogería lo que ya se ve, no enseñaría más—, así que
     * aquí se le pide al documento que se pinte **más pequeño** (3/7: la columna entre la columna
     * más sus márgenes) y nuestro aumento vuelve a contar desde 1 sobre esa vista. Es un cambio de
     * una vez, al pasar el pellizco de un lado a otro, no algo continuo: nada tiembla.
     */
    private var vistaEntera by mutableStateOf(false)

    private fun ponerLaVistaEntera(entera: Boolean) {
        val vista = web ?: return
        if (columnaDeAnotar == null || entera == vistaEntera) return
        vistaEntera = entera
        aumento = 1f
        corridoDelAumento = androidx.compose.ui.geometry.Offset.Zero
        vista.zoomBy(if (entera) ALEJADO else 1f / ALEJADO)
        // Al volver al texto, la columna al centro.
        if (!entera) vista.postDelayed({
            @Suppress("DEPRECATION")
            columnaDeAnotar?.let { vista.scrollTo((com.forge.pixpin.motor.Lectura.margenDe(it) * vista.scale).toInt(), vista.scrollY) }
            if (anotando) ponerLaCapaDondeElDocumento()
        }, 160)
    }

    /** Lo anotado sobre este documento: un dibujo del motor de siempre, guardado como cualquier otro. */
    private val idDeLaCapa: String get() = "capa-doc-" + claveDelDocumento.hashCode().toUInt().toString(16)
    private val laCapa: com.forge.pixpin.motor.DrawController by lazy {
        com.forge.pixpin.motor.DrawController(
            com.forge.pixpin.motor.ExcalidrawStore.cargar(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, idDeLaCapa))
                ?: com.forge.pixpin.motor.Scene()
        ).also { it.pedirLaMedida = false; it.selectTool(com.forge.pixpin.motor.Tool.FREEDRAW) }
    }
    private val hayAnotaciones: Boolean get() = columnaDeAnotar != null && laCapa.scene.elements.any { !it.isDeleted }

    private fun guardarLaCapa() {
        if (columnaDeAnotar == null) return
        val escena = laCapa.scene
        val id = idDeLaCapa
        val contexto = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { runCatching { com.forge.pixpin.motor.ExcalidrawStore.guardar(contexto, id, escena) } }
    }

    /**
     * **El lápiz.** La primera vez el documento se ensancha —la columna de texto se queda como
     * está y se abren dos tercios de margen a cada lado— y desde entonces **la letra queda
     * fijada**: si cambiara, el texto se recolocaría y lo anotado se quedaría en el aire.
     */
    @Suppress("DEPRECATION")
    private fun empezarAAnotar() {
        val vista = web ?: return
        if (columnaDeAnotar != null) { ponerLaCapaDondeElDocumento(); anotando = true; return }
        val columna = (vista.width / vista.scale.coerceAtLeast(0.1f)).toInt().coerceAtLeast(200)
        columnaDeAnotar = columna
        prefsDeLectura.edit()
            .putInt(claveDelDocumento + ":columna", columna)
            .putString(claveDelDocumento + ":letra", "$tamanoDeLetra,$grosorDeLetra,$tipoDeLetra")
            .apply()
        val pagina = laPaginaQueSeVe ?: return
        fraccionPendiente = fraccionDeAhora()
        entrarAlCargar = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { pagina.writeText(com.forge.pixpin.motor.Lectura.conEstilo(pagina.readText(), grosorDeLetra, tipoDeLetra, columna, esDeNoche())) }
            }
            vista.settings.loadWithOverviewMode = false
            vista.reload()
        }
    }

    /** La vista del lienzo, donde está ahora el documento: mismas unidades (píxeles CSS), misma escala. */
    @Suppress("DEPRECATION")
    private fun ponerLaCapaDondeElDocumento() {
        val vista = web ?: return
        val e = vista.scale.coerceAtLeast(0.1f).toDouble()
        escalaWeb = e.toFloat()
        laCapa.setViewport(com.forge.pixpin.motor.Viewport(scrollX = -vista.scrollX / e, scrollY = -vista.scrollY / e, zoom = e))
    }

    /** En la vista alejada no hay término medio: o el texto con un margen o con el otro. Se va al más cercano. */
    private fun aUnLado() {
        val vista = web ?: return
        val columna = columnaDeAnotar ?: return
        if (!vistaEntera || anotando) return
        @Suppress("DEPRECATION")
        val tope = ((com.forge.pixpin.motor.Lectura.anchoConMargenes(columna) * vista.scale) - vista.width).toInt().coerceAtLeast(0)
        val meta = if (vista.scrollX < tope / 2) 0 else tope
        if (meta != vista.scrollX) android.animation.ObjectAnimator.ofInt(vista, "scrollX", vista.scrollX, meta).setDuration(200).start()
    }

    /** Dónde estaba el documento, a lo ancho, al posar el dedo. Para el imán del centro. */
    private var corridoAlPosar = 0
    private var xDelLienzoAlPosar = Double.NaN

    /** **Leyendo**: si el gesto venía hacia el centro, el documento se va al centro. Ver [com.forge.pixpin.motor.Lectura.imanDelCentro]. */
    @Suppress("DEPRECATION")
    private fun encajarEnElCentro() {
        val vista = web ?: return
        val columna = columnaDeAnotar ?: return
        if (anotando) return
        if (vistaEntera) { aUnLado(); return }
        val margen = com.forge.pixpin.motor.Lectura.margenDe(columna) * vista.scale.toDouble()
        val meta = com.forge.pixpin.motor.Lectura.imanDelCentro(corridoAlPosar.toDouble(), vista.scrollX.toDouble(), margen, margen) ?: return
        android.animation.ObjectAnimator.ofInt(vista, "scrollX", vista.scrollX, meta.toInt()).setDuration(220).start()
    }

    /** **Anotando**: lo mismo, moviendo la vista del lienzo, que es quien lleva el documento. */
    private fun encajarElLienzoEnElCentro() {
        val columna = columnaDeAnotar ?: return
        if (vistaEntera) { xDelLienzoAlPosar = Double.NaN; return }
        val margen = com.forge.pixpin.motor.Lectura.margenDe(columna).toDouble()
        val ahora = -laCapa.scene.viewport.scrollX
        val antes = xDelLienzoAlPosar.takeIf { !it.isNaN() } ?: ahora
        xDelLienzoAlPosar = Double.NaN
        val meta = com.forge.pixpin.motor.Lectura.imanDelCentro(antes, ahora, margen, margen) ?: return
        lifecycleScope.launch(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
            androidx.compose.animation.core.animate(ahora.toFloat(), meta.toFloat()) { v, _ ->
                laCapa.setViewport(laCapa.scene.viewport.copy(scrollX = -v.toDouble()))
                tickDeAnotar++
            }
        }
    }

    /** Anotando manda el lienzo: el documento va a donde él vaya, sin salirse de sus bordes. */
    private fun llevarElDocumentoConLaCapa() {
        val vista = web ?: return
        val columna = columnaDeAnotar ?: return
        val v = laCapa.scene.viewport
        val e = v.zoom.coerceAtLeast(0.1)
        val (x, y) = com.forge.pixpin.motor.Lectura.dentroDelDocumento(
            -v.scrollX, -v.scrollY,
            com.forge.pixpin.motor.Lectura.anchoConMargenes(columna).toDouble(), vista.contentHeight.toDouble(),
            vista.width / e, vista.height / e
        )
        if (x != -v.scrollX || y != -v.scrollY) laCapa.setViewport(v.copy(scrollX = -x, scrollY = -y))
        val px = (x * e).toInt()
        val py = (y * e).toInt()
        if (px != vista.scrollX || py != vista.scrollY) vista.scrollTo(px, py)
    }

    /** Sube cada vez que el dedo toca la página, y cada vez que la mueve: la burbuja los mira. */
    private var toquesEnLaPagina by mutableStateOf(0)
    private var movidasDeLaPagina by mutableStateOf(0)

    /** Lo que la página ha puesto a pantalla completa (presentar), y cómo decirle que se acabó. */
    private var aPantalla: android.view.View? = null
    private var alAcabarLaPantalla: android.webkit.WebChromeClient.CustomViewCallback? = null
    private var presentando by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aPantallaCompleta()
        val ruta = intent?.getStringExtra(EXTRA_RUTA)
        if (ruta == null) { finish(); return }
        val nombre = intent?.getStringExtra(EXTRA_NOMBRE).orEmpty().ifBlank { File(ruta).name }
        comparte = intent?.getStringExtra(EXTRA_COMPARTE)?.let { File(it) }
        sinGuion = intent?.getBooleanExtra(EXTRA_SIN_GUION, false) == true
        mensaje = intent?.getStringExtra(EXTRA_MENSAJE)
        enSuSitio = intent?.getBooleanExtra(EXTRA_EN_SU_SITIO, false) == true
        esDocumento = intent?.getBooleanExtra(EXTRA_DOCUMENTO, false) == true
        elOriginal = File(ruta)
        if (esDocumento) {
            tamanoDeLetra = com.forge.pixpin.motor.Lectura.tamanoValido(prefsDeLectura.getInt("tamano", 100))
            grosorDeLetra = prefsDeLectura.getInt("grosor", 1)
            tipoDeLetra = prefsDeLectura.getInt("tipo", 0)
            marcadores = com.forge.pixpin.motor.Lectura.deTexto(prefsDeLectura.getString(claveDelDocumento + ":marcadores", null))
            // Y se vuelve a donde se dejó de leer.
            fraccionPendiente = prefsDeLectura.getFloat(claveDelDocumento + ":sitio", -1f)
            // **Un documento anotado conserva su letra y su columna**, las de cuando se anotó.
            prefsDeLectura.getInt(claveDelDocumento + ":columna", 0).takeIf { it > 0 }?.let { columna ->
                columnaDeAnotar = columna
                prefsDeLectura.getString(claveDelDocumento + ":letra", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
                    ?.takeIf { it.size == 3 }?.let { (t, g, l) -> tamanoDeLetra = t; grosorDeLetra = g; tipoDeLetra = l }
                if (fraccionPendiente < 0f) fraccionPendiente = 0f
            }
        }
        setContent { PixPinTheme { Pantalla(File(ruta), nombre) } }
    }

    override fun onPause() {
        guardarLaCapa()
        if (esDocumento && altoDelDocumento() > 0f) {
            prefsDeLectura.edit().putFloat(claveDelDocumento + ":sitio", fraccionDeAhora()).apply()
        }
        super.onPause()
    }

    override fun onDestroy() {
        // Un `WebView` que no se destruye se queda con su proceso de pintar y su guion vivos.
        web?.let { runCatching { it.stopLoading(); it.destroy() } }
        web = null
        super.onDestroy()
    }

    @Composable
    private fun Pantalla(original: File, nombre: String) {
        // La copia que se enseña; nula mientras se hace. Copiar es disco: fuera del hilo que pinta.
        var copia by remember(original) { mutableStateOf<File?>(null) }
        var fallo by remember(original) { mutableStateOf(false) }
        LaunchedEffect(original) {
            val hecha = withContext(Dispatchers.IO) { runCatching { if (enSuSitio) original.takeIf { it.exists() } else copiaParaVer(original) }.getOrNull() }
            if (hecha != null && esDocumento) withContext(Dispatchers.IO) {
                runCatching { hecha.writeText(com.forge.pixpin.motor.Lectura.conEstilo(hecha.readText(), grosorDeLetra, tipoDeLetra, columnaDeAnotar, esDeNoche())) }
            }
            laPaginaQueSeVe = hecha
            if (hecha == null) fallo = true else copia = hecha
        }
        // **Atrás vuelve primero dentro de la página** —un índice con sus hojas es ir y volver—
        // y solo cuando no queda a dónde volver, sale.
        var preguntandoSiSalir by remember { mutableStateOf(false) }
        fun salir() {
            when {
                aPantalla != null -> dejarLaPantallaCompleta()
                anotando -> { anotando = false; guardarLaCapa() }
                web?.canGoBack() == true -> web?.goBack()
                // **Salir con cambios sin guardar avisa.** Se le pregunta a la propia página.
                sinGuion || web == null -> finish()
                else -> web?.evaluateJavascript("(function(){try{return window.__pixpinHayCambios?window.__pixpinHayCambios():false;}catch(e){return false;}})()") { r ->
                    if (r == "true") preguntandoSiSalir = true else finish()
                }
            }
        }
        BackHandler { salir() }

        // **Solo la página, y su nombre flotando** (19-sep-2026, segunda vuelta). Sin botones:
        // se sale con atrás y se guarda con el botón de la propia página. El nombre es una
        // pastilla **siempre semitransparente** que sale al entrar y al tocar la pantalla, se va
        // sola a los pocos segundos y **se va en el acto al mover el contenido**. Tocarla lo deja
        // cambiar ahí mismo, **sin la extensión**, que no se puede cambiar. Lo demás —imprimir,
        // compartir, la vista de impresión— cabe en los tres puntos de su lado.
        var suNombre by remember { mutableStateOf(nombre) }
        var aLaVista by remember { mutableStateOf(true) }
        var cambiando by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var ocupado by remember { mutableStateOf<String?>(null) }
        var conLaLetra by remember { mutableStateOf(false) }
        var poniendoMarcador by remember { mutableStateOf(false) }
        var quitandoMarcadores by remember { mutableStateOf(false) }
        LaunchedEffect(toquesEnLaPagina) { if (toquesEnLaPagina > 0) aLaVista = true }
        LaunchedEffect(movidasDeLaPagina) { if (movidasDeLaPagina > 0 && !cambiando && !menu) aLaVista = false }
        @Suppress("UNUSED_VARIABLE") val sinUsar = menu
        LaunchedEffect(aLaVista, toquesEnLaPagina, cambiando, menu) {
            if (aLaVista && !cambiando && !menu) {
                kotlinx.coroutines.delay(LO_QUE_DURA_LA_BURBUJA)
                aLaVista = false
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                val pagina = copia
                when {
                    fallo -> Text(
                        getString(R.string.visor_html_no),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                    pagina != null -> Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (esDocumento) Modifier.pellizcoDelDocumento() else Modifier)
                            .graphicsLayer {
                                scaleX = aumento; scaleY = aumento
                                translationX = corridoDelAumento.x; translationY = corridoDelAumento.y
                            }
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { contexto -> nuevoWeb(contexto, pagina) }
                        )
                        if (esDocumento && columnaDeAnotar != null) CapaDeAnotar()
                    }
                }
                if (esDocumento && anotando) MandosDeAnotar()
                if (!presentando) androidx.compose.animation.AnimatedVisibility(
                    visible = aLaVista && !anotando,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp)
                ) {
                    Row(
                        Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .background(androidx.compose.ui.graphics.Color(0x8C14182B))
                            .padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val blanco = androidx.compose.ui.graphics.Color.White
                        if (cambiando) {
                            var texto by remember { mutableStateOf(sinExtension(suNombre)) }
                            val foco = remember { androidx.compose.ui.focus.FocusRequester() }
                            LaunchedEffect(Unit) { foco.requestFocus() }
                            androidx.compose.foundation.text.BasicTextField(
                                value = texto,
                                onValueChange = { texto = it.take(80).replace("\n", "") },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = blanco, fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(blanco),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                                    cambiando = false
                                    val limpio = conSuExtension(texto.trim(), suNombre)
                                    if (texto.isNotBlank() && limpio != suNombre) { suNombre = limpio; renombrar(limpio) }
                                }),
                                modifier = Modifier.weight(1f, fill = false).widthIn(min = 120.dp).focusRequester(foco).padding(vertical = 10.dp)
                            )
                        } else {
                            Text(
                                sinExtension(suNombre), color = blanco, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).clickable { cambiando = true }.padding(vertical = 10.dp)
                            )
                        }
                        // **En una página web, solo el nombre**: sus botones son los de la propia
                        // página. **En un Word o un libro, el nombre, el marcador y el engranaje**
                        // (tercera vuelta del usuario, 19-sep-2026): los tres puntos se fueron, y
                        // con ellos imprimir y compartir —se comparte desde el chat—. Los dos
                        // botones van con la pastilla: salen al tocar y se van al mover.
                        if (!esDocumento) androidx.compose.foundation.layout.Spacer(Modifier.size(width = 10.dp, height = 1.dp))
                        else {
                            // **Anotar**: una capa del motor del lienzo encima del documento. Ver [CapaDeAnotar].
                            IconButton(onClick = { conLaLetra = false; poniendoMarcador = false; empezarAAnotar() }, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Filled.Edit, contentDescription = "Anotar", tint = blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp))
                            }
                            IconButton(onClick = { poniendoMarcador = true; conLaLetra = false }, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Filled.BookmarkAdd, contentDescription = "Marcador aquí", tint = blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp))
                            }
                            IconButton(onClick = { conLaLetra = true; poniendoMarcador = false }, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Filled.Settings, contentDescription = "Letra", tint = blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }
                if (esDocumento && !presentando && !anotando) {
                    LateralDeMarcadores(Modifier.align(Alignment.CenterEnd))
                    if (conLaLetra) PanelDeLetra(
                        onCerrar = { conLaLetra = false },
                        onImpresion = { conLaLetra = false; ocupado = "Preparando las páginas…"; comoPdf(suNombre, alProyecto = false) { ocupado = null } },
                        onAlProyecto = { conLaLetra = false; ocupado = "Pasándolo a PDF…"; comoPdf(suNombre, alProyecto = true) { ocupado = null } },
                        onQuitarMarcadores = { conLaLetra = false; quitandoMarcadores = true }
                    )
                    if (poniendoMarcador) ElegirEmoji(Modifier.align(Alignment.BottomCenter), onCerrar = { poniendoMarcador = false }) { emoji ->
                        poniendoMarcador = false
                        marcadores = com.forge.pixpin.motor.Lectura.conMarcador(marcadores, fraccionDeAhora(), emoji, System.currentTimeMillis())
                        guardarMarcadores()
                    }
                }
                ocupado?.let { que ->
                    Row(
                        Modifier
                            .align(Alignment.Center)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Color(0xCC14182B))
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
                        Text(que, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
        if (quitandoMarcadores) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { quitandoMarcadores = false },
                title = { Text("Quitar marcadores") },
                text = {
                    Column {
                        marcadores.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    marcadores = marcadores.filterNot { it.id == m.id }
                                    guardarMarcadores()
                                    if (marcadores.isEmpty()) quitandoMarcadores = false
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(m.emoji, fontSize = 22.sp)
                                Text("  " + (m.fraccion * 100).toInt() + " % del documento", modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.Close, contentDescription = "Quitar", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { quitandoMarcadores = false }) { Text("Hecho") } }
            )
        }
        if (preguntandoSiSalir) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { preguntandoSiSalir = false },
                title = { Text("Hay cambios sin guardar") },
                text = { Text("Si sales ahora se pierden. Para conservarlos, vuelve y pulsa guardar en la página.") },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { preguntandoSiSalir = false; finish() }) { Text("Salir") } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { preguntandoSiSalir = false }) { Text("Volver") } }
            )
        }
    }

    /**
     * **La capa de anotar, encima del documento** (20-sep-2026, segunda vuelta).
     *
     * El usuario lo quería así y no de otra forma: **en el mismo visor**, sobre el texto tal como
     * se lee —una columna hasta el fondo—, con dos tercios de margen a cada lado para escribir, y
     * **con el motor del lienzo**: las mismas herramientas y tintas, sin duplicar nada. (La
     * primera versión pasaba el documento a un PDF por páginas; lo rechazó: «secciones muy
     * pequeñas» y cambiar de una a otra tardaba.)
     *
     * Son dos caras de la misma capa:
     * - **Leyendo**, la capa **solo se pinta** —con el [com.forge.pixpin.motor.Renderer] de
     *   siempre— en el sitio y a la escala del documento; no coge ningún toque, así que el
     *   documento se desplaza como siempre y lo anotado va con él.
     * - **Anotando**, encima va el [com.forge.pixpin.motor.DrawCanvas] entero con su barra
     *   (las herramientas del editor rápido, elegidas en Ajustes). Un dedo dibuja y dos mueven,
     *   como en el lienzo; con lápiz, el dedo mueve. **Manda el lienzo** y el documento le sigue:
     *   cada fotograma se lleva el `WebView` a donde esté la vista del lienzo, sin salirse del
     *   documento. El aumento va fijado: las unidades del dibujo son los píxeles del documento.
     */
    @Composable
    private fun CapaDeAnotar() {
        val deNoche = esDeNoche()
        val fotos = remember { HashMap<String, android.graphics.Bitmap?>() }
        val foto: (String) -> android.graphics.Bitmap? = { f -> fotos.getOrPut(f) { laCapa.scene.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } } }
        if (!anotando) {
            if (!hayAnotaciones) return
            val pintor = remember(deNoche) { com.forge.pixpin.motor.Renderer(foto, com.forge.pixpin.motor.DrawFonts.provider(this), dark = deNoche) }
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION") cambiosEnLaCapa
                val e = escalaWeb.coerceAtLeast(0.1f).toDouble()
                val escena = laCapa.scene.copy(
                    viewport = com.forge.pixpin.motor.Viewport(scrollX = -corridoX / e, scrollY = -corridoY / e, zoom = e)
                )
                drawContext.canvas.nativeCanvas.let { pintor.renderScene(it, escena, size.width.toDouble(), size.height.toDouble()) }
            }
            return
        }
        // El documento sigue al lienzo, fotograma a fotograma, mientras se anota.
        LaunchedEffect(Unit) {
            while (true) {
                androidx.compose.runtime.withFrameNanos { }
                llevarElDocumentoConLaCapa()
            }
        }
        Box(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") tickDeAnotar
            com.forge.pixpin.motor.DrawCanvas(
                controller = laCapa,
                modifier = Modifier.fillMaxSize(),
                imageProvider = foto,
                dark = deNoche,
                onChange = { trazando ->
                    // Al posarse se apunta por dónde iba la vista; al soltar, el imán del centro.
                    if (trazando) { if (xDelLienzoAlPosar.isNaN()) xDelLienzoAlPosar = -laCapa.scene.viewport.scrollX }
                    else { tickDeAnotar++; cambiosEnLaCapa++; encajarElLienzoEnElCentro() }
                },
                // El aumento lo lleva el pellizco del documento, que estira esto y el texto a la vez.
                zoomBloqueado = true,
                modoLapiz = conLapiz,
                onLapizDetectado = { conLapiz = true }
            )
        }
    }

    /** «Listo», la barra de herramientas y el cuadro del texto: **fuera** de la capa que se estira. */
    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.MandosDeAnotar() {
        val ajustes by (application as com.forge.pixpin.PixPinApp).settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
        @Suppress("UNUSED_EXPRESSION") tickDeAnotar
        EscribirEnElLienzo(laCapa, tickDeAnotar) { tickDeAnotar++; cambiosEnLaCapa++ }
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Color(0xB314182B))
                .clickable { anotando = false; guardarLaCapa(); cambiosEnLaCapa++ }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
            Text("Listo", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(start = 6.dp))
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp, start = 6.dp, end = 6.dp)) {
            com.forge.pixpin.ui.theme.SuperficieDeCristal(Modifier, androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                com.forge.pixpin.motor.DrawToolbar(
                    tool = laCapa.tool,
                    onTool = { laCapa.selectTool(it); tickDeAnotar++ },
                    style = laCapa.scene.style,
                    onStyle = { nuevo -> laCapa.cambiarEstilo(nuevo) { it }; tickDeAnotar++ },
                    canUndo = laCapa.canUndo,
                    onUndo = { laCapa.undo(); tickDeAnotar++; cambiosEnLaCapa++ },
                    permitidas = ajustes.lectorToolSet - com.forge.pixpin.motor.LECTOR_TOOLS_FUERA,
                    grupos = ajustes.lectorGroupList.map { g -> g.filterNot { it in com.forge.pixpin.motor.LECTOR_TOOLS_FUERA } }.filter { it.isNotEmpty() }
                )
            }
        }
    }

    /**
     * **El pellizco de un documento.** Con dos dedos se amplía —nunca por debajo de 1— y el punto
     * entre los dedos se queda quieto. Leyendo, esos dos dedos también pasean por lo ampliado y
     * el gesto se consume, para que el texto no se desplace a la vez. Anotando **solo amplía**: el
     * paseo de los dos dedos es del lienzo, que es quien lleva el documento. Un dedo no se toca.
     */
    private fun Modifier.pellizcoDelDocumento(): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            var deMenos = 1f
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val dedos = e.changes.count { it.pressed }
                if (dedos == 0) {
                    // En la vista entera, acercarse hasta que el texto llena el ancho es volver al texto.
                    if (vistaEntera && aumento >= 1f / ALEJADO * 0.92f) ponerLaVistaEntera(false)
                    else if (vistaEntera) web?.postDelayed({ aUnLado() }, 140)
                    break
                }
                if (dedos < 2) continue
                val antes = aumento
                // Seguir cerrando los dedos con el texto ya de borde a borde: a la vista entera.
                if (!vistaEntera && columnaDeAnotar != null && antes <= 1.001f) {
                    deMenos *= e.calculateZoom()
                    if (deMenos < 0.82f) { ponerLaVistaEntera(true); deMenos = 1f }
                }
                val ahora = (antes * e.calculateZoom()).coerceIn(1f, if (vistaEntera) AUMENTO_MAXIMO / ALEJADO else AUMENTO_MAXIMO)
                val centro = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val foco = e.calculateCentroid(useCurrent = false)
                var movido = if (ahora == antes) corridoDelAumento
                else foco - centro - (foco - centro - corridoDelAumento) * (ahora / antes)
                if (!anotando) movido += e.calculatePan()
                val topeX = (ahora - 1f) * size.width / 2f
                val topeY = (ahora - 1f) * size.height / 2f
                aumento = ahora
                corridoDelAumento = androidx.compose.ui.geometry.Offset(movido.x.coerceIn(-topeX, topeX), movido.y.coerceIn(-topeY, topeY))
                if (!anotando) e.changes.forEach { if (it.pressed) it.consume() }
            }
        }
    }

    /**
     * **Los marcadores, en el lateral**: un punto por marcador, en el orden del documento, cada uno
     * con su emoticono. Se pasa el dedo por ellos —**vibra al cambiar de uno a otro**— y al
     * soltar se va al que quedó debajo, con lo alto de la pantalla justo en el marcador. Un toque
     * a secas en uno hace lo mismo. En reposo van pequeños y semitransparentes; con el dedo
     * encima crecen, para ver bien a cuál se va.
     */
    @Composable
    private fun LateralDeMarcadores(modifier: Modifier) {
        val lista = marcadores
        if (lista.isEmpty()) return
        val vibrar = androidx.compose.ui.platform.LocalHapticFeedback.current
        val listaYa = androidx.compose.runtime.rememberUpdatedState(lista)
        var bajoElDedo by remember { mutableStateOf(-1) }
        val paso = 38.dp
        Column(
            modifier
                .padding(end = 2.dp)
                .pointerInput(Unit) {
                    val pasoPx = paso.toPx()
                    awaitEachGesture {
                        val abajo = awaitFirstDown(requireUnconsumed = false)
                        abajo.consume()
                        bajoElDedo = com.forge.pixpin.motor.Lectura.puntoBajoElDedo(abajo.position.y, pasoPx, listaYa.value.size)
                        vibrar.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        while (true) {
                            val e = awaitPointerEvent()
                            val dedo = e.changes.firstOrNull { it.pressed } ?: break
                            dedo.consume()
                            val i = com.forge.pixpin.motor.Lectura.puntoBajoElDedo(dedo.position.y, pasoPx, listaYa.value.size)
                            if (i != bajoElDedo) {
                                bajoElDedo = i
                                vibrar.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            }
                        }
                        listaYa.value.getOrNull(bajoElDedo)?.let { irALaFraccion(it.fraccion, intentos = 0) }
                        bajoElDedo = -1
                    }
                },
            horizontalAlignment = Alignment.End
        ) {
            lista.forEachIndexed { i, m ->
                val elegido = i == bajoElDedo
                val conDedo = bajoElDedo >= 0
                Box(Modifier.size(width = if (conDedo) 64.dp else 30.dp, height = paso), contentAlignment = Alignment.CenterEnd) {
                    // **El elegido salta delante del dedo** (20-sep-2026): crecía, pero debajo del
                    // dedo, y no se veía cuál era. Ahora sale hacia dentro de la pantalla, grande, y
                    // al pasar al siguiente vuelve a su sitio y salta el otro.
                    val salto = androidx.compose.animation.core.animateDpAsState(
                        if (elegido) (-78).dp else 0.dp,
                        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 700f), label = "salto"
                    )
                    Box(
                        Modifier
                            .offset(x = salto.value)
                            .size(if (elegido) 52.dp else if (conDedo) 28.dp else 20.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(androidx.compose.ui.graphics.Color(if (elegido) 0xE614182B else 0x6614182B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            m.emoji,
                            fontSize = if (elegido) 30.sp else if (conDedo) 15.sp else 10.sp,
                            modifier = Modifier.alpha(if (conDedo) 1f else 0.75f)
                        )
                    }
                }
            }
        }
    }

    /**
     * **El engranaje**: una hoja que se despliega desde abajo, con todo a la vista y a un toque.
     * El tamaño y el grosor son **una barra de puntos** cada uno —cada punto, un valor; se toca o
     * se pasa el dedo y vibra al cambiar—, el tipo de letra son fichas, y debajo lo demás.
     */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun PanelDeLetra(onCerrar: () -> Unit, onImpresion: () -> Unit, onAlProyecto: () -> Unit, onQuitarMarcadores: () -> Unit) {
        val lectura = com.forge.pixpin.motor.Lectura
        val fijada = hayAnotaciones
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = onCerrar,
            containerColor = androidx.compose.ui.graphics.Color(0xF214182B),
            contentColor = androidx.compose.ui.graphics.Color.White,
            scrimColor = androidx.compose.ui.graphics.Color.Transparent
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
                val gris = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f)
                if (fijada) Text(
                    "La letra está fijada: este documento tiene anotaciones, y si cambiara se quedarían en el aire.",
                    color = androidx.compose.ui.graphics.Color(0xFFFFD27A), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tamaño", color = gris, modifier = Modifier.weight(1f))
                    Text("$tamanoDeLetra %", color = gris)
                }
                BarraDePuntos(
                    cuantos = lectura.TAMANOS.size,
                    elegido = lectura.TAMANOS.indexOfFirst { it >= tamanoDeLetra }.let { if (it < 0) lectura.TAMANOS.lastIndex else it },
                    crece = true
                ) { if (!fijada) ponerElTamano(lectura.TAMANOS[it]) }

                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Grosor", color = gris, modifier = Modifier.weight(1f))
                    Text(lectura.GROSORES.getOrElse(grosorDeLetra) { lectura.GROSORES[1] }.second, color = gris)
                }
                BarraDePuntos(cuantos = lectura.GROSORES.size, elegido = grosorDeLetra, crece = false) { if (!fijada) ponerLaLetra(it, tipoDeLetra) }

                Text("Letra", color = gris, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                    lectura.LETRAS.forEachIndexed { i, (_, nombre) -> FichaDeLetra(nombre, i == tipoDeLetra) { if (!fijada) ponerLaLetra(grosorDeLetra, i) } }
                }

                Text("Documento", color = gris, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                    FichaDeLetra("Vista de impresión", false, onImpresion)
                    FichaDeLetra("Al proyecto", false, onAlProyecto)
                    if (marcadores.isNotEmpty()) FichaDeLetra("Quitar marcadores", false, onQuitarMarcadores)
                }
            }
        }
    }

    /**
     * Una barra de puntos: cada punto es un valor. Se toca uno, o se pasa el dedo por la barra y
     * se va cambiando **con un toque de vibración en cada punto**. Con [crece], los puntos van de
     * pequeño a grande, que es como se lee «tamaño» sin una sola letra.
     */
    @Composable
    private fun BarraDePuntos(cuantos: Int, elegido: Int, crece: Boolean, onElegir: (Int) -> Unit) {
        val vibrar = androidx.compose.ui.platform.LocalHapticFeedback.current
        val elegir = androidx.compose.runtime.rememberUpdatedState(onElegir)
        val elegidoYa = androidx.compose.runtime.rememberUpdatedState(elegido)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
                .pointerInput(cuantos) {
                    awaitEachGesture {
                        val abajo = awaitFirstDown(requireUnconsumed = false)
                        fun punto(x: Float) = ((x / size.width) * cuantos).toInt().coerceIn(0, cuantos - 1)
                        var ultimo = -1
                        fun poner(i: Int) {
                            if (i == ultimo) return
                            ultimo = i
                            if (i != elegidoYa.value) {
                                vibrar.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                elegir.value(i)
                            }
                        }
                        poner(punto(abajo.position.x))
                        while (true) {
                            val e = awaitPointerEvent()
                            val dedo = e.changes.firstOrNull { it.pressed } ?: break
                            dedo.consume()
                            poner(punto(dedo.position.x))
                        }
                    }
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until cuantos) {
                Box(Modifier.weight(1f).size(height = 34.dp, width = 1.dp), contentAlignment = Alignment.Center) {
                    val lado = if (crece) (7 + 13f * i / (cuantos - 1).coerceAtLeast(1)).dp else 12.dp
                    Box(
                        Modifier
                            .size(if (i == elegido) lado + 8.dp else lado)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = if (i == elegido) 1f else 0.35f))
                    )
                }
            }
        }
    }

    @Composable
    private fun FichaDeLetra(texto: String, puesta: Boolean, onToque: () -> Unit) {
        Text(
            texto, color = androidx.compose.ui.graphics.Color.White, maxLines = 1,
            modifier = Modifier
                .padding(3.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = if (puesta) 0.28f else 0.1f))
                .clickable(onClick = onToque)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }

    /** Con qué emoticono se pone el marcador: una fila para elegir de un toque. */
    @Composable
    private fun ElegirEmoji(modifier: Modifier, onCerrar: () -> Unit, onElegir: (String) -> Unit) {
        Row(
            modifier
                .navigationBarsPadding()
                .padding(12.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Color(0xD914182B))
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.forge.pixpin.motor.Lectura.EMOJIS.forEach { e ->
                Text(e, fontSize = 24.sp, modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { onElegir(e) }.padding(8.dp))
            }
            IconButton(onClick = onCerrar, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Cancelar", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }

    /** El nombre como se enseña y se edita: sin la extensión, que no se puede cambiar. */
    private fun sinExtension(nombre: String): String {
        val ext = nombre.substringAfterLast('.', "")
        return if (ext.isNotBlank() && ext.length <= 5 && nombre.length > ext.length + 1) nombre.dropLast(ext.length + 1) else nombre
    }

    /**
     * **El documento, como saldría impreso**: se le pide al `WebView` el PDF que mandaría a la
     * impresora (A4) y se abre en el lector de PDF, que ya enseña páginas. Con [alProyecto], ese
     * PDF entra en los proyectos como cualquier otro: una hoja por página, para anotarlo en los
     * lienzos. Ver [android.print.PdfDesdeWeb].
     */
    private fun comoPdf(nombre: String, alProyecto: Boolean, alAcabar: () -> Unit) {
        val vista = web
        if (vista == null) { alAcabar(); return }
        val titulo = sinExtension(nombre).ifBlank { "Documento" }
        val ahora = System.currentTimeMillis()
        val destino = if (alProyecto) File(File(filesDir, "proyectos").apply { mkdirs() }, "doc-$ahora.pdf")
        else File(File(cacheDir, "visor-pdf").apply { deleteRecursively(); mkdirs() }, "impresion.pdf")
        val atributos = android.print.PrintAttributes.Builder()
            .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
            .setResolution(android.print.PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setMinMargins(android.print.PrintAttributes.Margins(590, 590, 590, 590))
            .build()
        android.print.PdfDesdeWeb.escribir(vista.createPrintDocumentAdapter(titulo), atributos, destino) { bien ->
            if (!bien) {
                alAcabar()
                Toast.makeText(this, "No se pudo pasar a PDF", Toast.LENGTH_SHORT).show()
                return@escribir
            }
            if (!alProyecto) {
                alAcabar()
                com.forge.pixpin.pdf.LectorPdfActivity.abrir(this, destino.absolutePath, "$titulo · impresión")
                return@escribir
            }
            lifecycleScope.launch {
                val hecho = withContext(Dispatchers.IO) {
                    runCatching {
                        val paginas = android.graphics.pdf.PdfRenderer(
                            android.os.ParcelFileDescriptor.open(destino, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                        ).use { it.pageCount }
                        (application as com.forge.pixpin.PixPinApp).proyectos.deEstePdf(destino.absolutePath, titulo, paginas, ahora)
                    }.getOrNull()
                }
                alAcabar()
                if (hecho == null) Toast.makeText(this@VisorHtmlActivity, "No se pudo crear el proyecto", Toast.LENGTH_SHORT).show()
                else { com.forge.pixpin.volverALosProyectos(this@VisorHtmlActivity, hecho.id); finish() }
            }
        }
    }

    /** El nombre nuevo conserva la extensión del de antes: por ella se sabe con qué se abre. */
    private fun conSuExtension(nuevo: String, antes: String): String {
        val ext = antes.substringAfterLast('.', "")
        if (nuevo.isBlank() || ext.isBlank() || ext.length > 5) return nuevo
        return if (nuevo.endsWith(".$ext", ignoreCase = true)) nuevo else "$nuevo.$ext"
    }

    /** El nombre se cambia **en el mensaje del chat** del que salió la página; sin él, solo aquí. */
    private fun renombrar(nombre: String) {
        val id = mensaje ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { com.forge.pixpin.guardados.MensajesStore(applicationContext).actualizar(id) { it.copy(nombre = nombre) } }
        }
    }

    /**
     * **Imprimir**: el `WebView` de Android **no tiene `window.print()`** —la función existe y no
     * hace nada—, así que el botón de imprimir de una página exportada se quedaba mudo. Aquí se
     * imprime con el servicio del sistema, que es lo que hace un navegador por debajo.
     */
    private fun imprimir(nombre: String) {
        val vista = web ?: return
        runCatching {
            val servicio = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            servicio.print(nombre.ifBlank { "PixPin" }, vista.createPrintDocumentAdapter(nombre.ifBlank { "PixPin" }), android.print.PrintAttributes.Builder().build())
        }.onFailure { Toast.makeText(this, "No se pudo imprimir", Toast.LENGTH_SHORT).show() }
    }

    private fun dejarLaPantallaCompleta() {
        val vista = aPantalla ?: return
        (window.decorView as? android.widget.FrameLayout)?.removeView(vista)
        aPantalla = null
        presentando = false
        runCatching { alAcabarLaPantalla?.onCustomViewHidden() }
        alAcabarLaPantalla = null
    }

    /**
     * **Presentar**: la página pide pantalla completa (`requestFullscreen`) y un `WebView` a secas
     * no sabe darla: hay que recoger la vista que entrega y ponerla encima de todo. Sin esto, el
     * botón de presentar de la página exportada no hacía nada.
     */
    private inner class Cromo : android.webkit.WebChromeClient() {
        override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
            if (aPantalla != null) { callback.onCustomViewHidden(); return }
            aPantalla = view
            alAcabarLaPantalla = callback
            presentando = true
            (window.decorView as? android.widget.FrameLayout)?.addView(
                view, android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        override fun onHideCustomView() = dejarLaPantallaCompleta()
    }

    /**
     * **El puente con la página**: dos recados y nada más. Imprimir, y **guardar** —las páginas
     * exportadas bajan archivos con un enlace `blob:` y un `WebView` no tiene descargas: el
     * botón decía «Guardado» y no se guardaba nada—. Lo bajado **no se escribe en ningún sitio
     * por su cuenta**: sale la hoja de compartir y el usuario decide a dónde va, que una página
     * llegada de otro no tiene por qué poder dejar archivos en el teléfono.
     */
    private inner class Puente {
        @android.webkit.JavascriptInterface
        fun imprimir() = runOnUiThread { this@VisorHtmlActivity.imprimir(intent?.getStringExtra(EXTRA_NOMBRE).orEmpty()) }

        @android.webkit.JavascriptInterface
        fun guardar(nombre: String, enBase64: String) {
            if (enBase64.length > TOPE_DE_LO_BAJADO) return
            // **Guardar es guardar** (19-sep-2026): si lo que baja la página es ella misma —un
            // `.html`, el botón de guardar de una página exportada—, se reescribe el archivo de
            // verdad, el del chat, y no se saca ninguna hoja de compartir. Antes de escribir se
            // comprueba que lo que llega es una página entera: es la trampa del «HTML que se
            // come su guion», y un archivo a medias dejaría el original muerto.
            val destino = elOriginal
            if (esHtml(nombre) && destino != null && !enSuSitio && !sinGuion) {
                lifecycleScope.launch {
                    val bien = withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = android.util.Base64.decode(enBase64, android.util.Base64.DEFAULT)
                            val texto = String(bytes, Charsets.UTF_8)
                            require(bytes.size > 200 && texto.contains("</html>", ignoreCase = true))
                            val tmp = File(destino.path + ".tmp")
                            tmp.writeBytes(bytes)
                            check(tmp.renameTo(destino) || runCatching { tmp.copyTo(destino, overwrite = true); tmp.delete(); true }.getOrDefault(false))
                        }.isSuccess
                    }
                    if (bien) web?.evaluateJavascript("window.__pixpinGuardado&&window.__pixpinGuardado();", null)
                    Toast.makeText(this@VisorHtmlActivity, if (bien) "Guardado" else "No se pudo guardar", Toast.LENGTH_SHORT).show()
                }
                return
            }
            lifecycleScope.launch {
                val archivo = withContext(Dispatchers.IO) {
                    runCatching {
                        val limpio = nombre.replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").takeLast(80).ifBlank { "archivo" }
                        File(File(cacheDir, "share").apply { mkdirs() }, limpio).also {
                            it.writeBytes(android.util.Base64.decode(enBase64, android.util.Base64.DEFAULT))
                        }
                    }.getOrNull()
                } ?: return@launch
                compartir(archivo, archivo.name)
            }
        }
    }

    /**
     * El `WebView` ya cargado con [pagina]. Los permisos, uno a uno y a la vista: lo que no
     * está aquí encendido está apagado. Ver el porqué en la documentación de la clase.
     */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun nuevoWeb(contexto: Context, pagina: File): WebView = WebView(contexto).apply {
        settings.javaScriptEnabled = !sinGuion
        settings.domStorageEnabled = !sinGuion
        // Desde Android 11 viene apagado, y sin él un `file://` no carga ni la propia página.
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        // Que el guion de la página no pueda leer otros archivos por su cuenta.
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        // Un plano exportado se mira acercándose: el pellizco, sin los botones de + y −.
        settings.builtInZoomControls = !esDocumento
        settings.displayZoomControls = false
        // El aumento del `WebView` solo lo usamos nosotros, para [ponerLaVistaEntera]: los dos dedos no le llegan.
        if (esDocumento) { settings.setSupportZoom(true); settings.builtInZoomControls = true }
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        webViewClient = Cliente(pagina.parentFile ?: pagina)
        webChromeClient = Cromo()
        if (esDocumento) settings.textZoom = tamanoDeLetra
        if (columnaDeAnotar != null) settings.loadWithOverviewMode = false
        // Que el visor no oscurezca por su cuenta un documento al que ya se le ha puesto su papel:
        // saldría invertido dos veces. Ver [com.forge.pixpin.motor.Lectura.estilo].
        if (esDocumento) runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = false
            else if (android.os.Build.VERSION.SDK_INT >= 29) @Suppress("DEPRECATION") settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
        }
        setOnScrollChangeListener { _, x, y, _, _ -> corridoX = x; corridoY = y }
        // El dedo sobre la página: un toque enseña el nombre, moverla lo esconde. No se consume nada.
        val margen = android.view.ViewConfiguration.get(contexto).scaledTouchSlop
        var x0 = 0f
        var y0 = 0f
        var seMovio = false
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; seMovio = false; corridoAlPosar = scrollX }
                android.view.MotionEvent.ACTION_MOVE ->
                    if (!seMovio && (kotlin.math.abs(e.x - x0) > margen || kotlin.math.abs(e.y - y0) > margen)) { seMovio = true; movidasDeLaPagina++ }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!seMovio) toquesEnLaPagina++
                    // Un respiro, por si la página sigue con la inercia del gesto; luego, el imán.
                    else postDelayed({ encajarEnElCentro() }, 140)
                }
            }
            false
        }
        if (!sinGuion) addJavascriptInterface(Puente(), "PixPinVisor")
        loadUrl(Uri.fromFile(pagina).toString())
        web = this
    }

    /**
     * **El portero**: de `file://` solo pasa lo que está en la carpeta de la copia, y los
     * enlaces a la red se abren fuera.
     */
    private inner class Cliente(carpeta: File) : WebViewClient() {
        private val raiz: String = runCatching { carpeta.canonicalPath }.getOrDefault(carpeta.absolutePath) + File.separator

        private fun dentro(uri: Uri): Boolean {
            val camino = uri.path ?: return false
            return runCatching { File(camino).canonicalPath.startsWith(raiz) }.getOrDefault(false)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                // `true` es «no lo cargues»: un `file://` de fuera de la carpeta se queda en nada.
                "file" -> !dentro(uri)
                "http", "https", "mailto", "tel" -> {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    true
                }
                // `data:`, `blob:`, `about:`: lo que la propia página se fabrica.
                else -> false
            }
        }

        /** Al acabar de cargar se le enseña a la página a imprimir y a guardar aquí dentro. */
        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
            escalaWeb = newScale
            if (anotando) view.post { ponerLaCapaDondeElDocumento() }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            @Suppress("DEPRECATION")
            escalaWeb = view.scale
            if (!sinGuion) view.evaluateJavascript(GUION_DEL_VISOR, null)
            if (esDocumento && fraccionPendiente >= 0f) {
                val f = fraccionPendiente
                fraccionPendiente = -1f
                view.postDelayed({ irALaFraccion(f) }, 80)
            }
        }

        /** Y lo mismo para lo que la página pide sin navegar: una imagen, un guion, un marco. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            if (uri.scheme?.lowercase() == "file" && !dentro(uri)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }
    }

    /**
     * La copia que se enseña, **sola en su carpeta**: así «solo su carpeta» es de verdad solo
     * ella, y no los demás adjuntos del chat. La carpeta se vacía antes: es caché de usar y
     * tirar, y de otro modo cada página abierta se quedaría ahí para siempre.
     */
    private fun copiaParaVer(original: File): File? {
        if (!original.exists()) return null
        val carpeta = File(File(cacheDir, CARPETA), original.absolutePath.hashCode().toString())
        File(cacheDir, CARPETA).listFiles()?.forEach { if (it != carpeta) it.deleteRecursively() }
        carpeta.mkdirs()
        return original.copyTo(File(carpeta, "pagina.html"), overwrite = true)
    }

    /**
     * Manda **el archivo original**, con su nombre. Pasa por `cache/share`, que es lo que el
     * `FileProvider` sabe servir venga el archivo de donde venga. Ver `res/xml` y
     * `MensajesActivity.compartibleDe`.
     */
    private fun compartir(original: File, nombre: String) {
        // Copiar es disco —una página exportada con sus fotos dentro pesa megas—: fuera del hilo que pinta.
        // La extensión **del archivo que se manda**: un Word convertido se comparte como el
        // `.docx` que es, no como la página que se fabricó para verlo.
        val suya = original.extension.lowercase().ifBlank { nombre.substringAfterLast('.', "").lowercase() }
        val esPagina = suya.isBlank() || esHtml("x.$suya")
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val conExtension = when {
                        esPagina -> if (esHtml(nombre)) nombre else "$nombre.html"
                        nombre.substringAfterLast('.', "").lowercase() == suya -> nombre
                        else -> "$nombre.$suya"
                    }
                    val limpio = conExtension.replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").takeLast(80)
                    val destino = File(File(cacheDir, "share").apply { mkdirs() }, limpio)
                    original.copyTo(destino, overwrite = true)
                    FileProvider.getUriForFile(this@VisorHtmlActivity, "$packageName.fileprovider", destino)
                }.getOrNull()
            }
            val salio = uri != null && runCatching {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = if (esPagina) "text/html"
                            else android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(suya) ?: "*/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        null
                    )
                )
            }.isSuccess
            if (!salio) Toast.makeText(this@VisorHtmlActivity, getString(R.string.visor_html_no_compartir), Toast.LENGTH_SHORT).show()
        }
    }
}
