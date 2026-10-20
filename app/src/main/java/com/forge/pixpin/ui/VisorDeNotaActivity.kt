package com.forge.pixpin.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Lectura
import com.forge.pixpin.motor.MarcosDoc
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.Viewport
import com.forge.pixpin.motor.VistasDoc
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * **Leer una nota con el riel de los visores** (PC `notas_md/lector_web.rs`, 17/19/20-oct-2026): la
 * misma página que el PC (`lector-md.html`, md-reader con CodeMirror) en modo lectura, con la barra de
 * los visores arriba y el riel al lado —marcador, lápiz de tinta y marcos para imprimir—.
 *
 * **Por qué la página del PC y no el editor del teléfono**: la tinta y los marcos van en píxeles CSS
 * del documento de esa página; con la misma página y las mismas letras (Work Sans y Fraunces, servidas
 * desde las del teléfono) lo anotado en un aparato cae en el mismo sitio del texto en el otro. Viajan
 * con la nota: tinta en `anot-<código>`, marcas en `.marcas` (fracción) y su vista exacta en
 * `.vistas.json`, marcos en `.marcos.json` (los de [AnotacionesDelAdjunto]). Escribir sigue siendo el
 * editor del teléfono (botón de la barra).
 *
 * **Sin retraso al desplazar**, como el PC (`visor_html/capa_web.rs`): lo dibujado y los marcos van
 * DENTRO de la página ([INSTALAR], el guion del PC tal cual) y el navegador los mueve con el texto; la
 * capa de Android solo pinta mientras se traza o se ponen marcos.
 */
class VisorDeNotaActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TEXTO = "texto"
        private const val EXTRA_NOMBRE = "nombre"
        private const val ORIGEN = "https://pixpin.nota/"

        fun abrir(c: Context, id: String, texto: String, nombre: String) {
            c.startActivity(Intent(c, VisorDeNotaActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEXTO, texto).putExtra(EXTRA_NOMBRE, nombre)
                .apply { if (c !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }

        /** El guion del riel del PC (`capa_web::INSTALAR`), sin cambiar nada: dónde va, la capa y las escuchas. */
        const val INSTALAR = """(function(){
if(window.__pixpinRiel)return;
var R={nuevo:true,ctrlp:false,menu:false,capa:null,raf:0};window.__pixpinRiel=R;
function caja(e){if(!e||e===document.body||e===document.documentElement)return false;var o=getComputedStyle(e).overflowY;return(o==='auto'||o==='scroll'||o==='overlay')&&e.scrollHeight>e.clientHeight+1;}
R.donde=function(){var e=document.elementFromPoint(innerWidth/2,innerHeight/2);while(e&&!caja(e))e=e.parentElement;return e;};
function cual(){var e=R.donde();return{raiz:!e,s:e||document.scrollingElement||document.documentElement};}
function maximo(d){return d.raiz?Math.max(0,d.s.scrollHeight-innerHeight):Math.max(0,d.s.scrollHeight-d.s.clientHeight);}
function area(d){var s=d.s,x,y,ww,hh,bx=0,by=0;if(d.raiz){x=0;y=0;ww=document.documentElement.clientWidth||innerWidth;hh=document.documentElement.clientHeight||innerHeight;}else{var c=s.getBoundingClientRect();bx=c.left;by=c.top;x=c.left+s.clientLeft;y=c.top+s.clientTop;ww=s.clientWidth;hh=s.clientHeight;}
var x0=Math.max(0,x),y0=Math.max(0,y),x1=Math.min(innerWidth,x+ww),y1=Math.min(innerHeight,y+hh);return{bx:bx,by:by,ax:x0,ay:y0,aw:Math.max(0,x1-x0),ah:Math.max(0,y1-y0)};}
R.ir=function(modo,a,b){var d=cual(),max=maximo(d),w=d.raiz?window:d.s;
function ir(){var q=area(d);if(modo==='ir')w.scrollTo({top:a*max,behavior:'instant'});if(modo==='bajar')w.scrollBy({top:a,behavior:'instant'});if(modo==='colocar')w.scrollTo({left:a+q.bx-q.ax,top:b+q.by-q.ay,behavior:'instant'});}
if(modo==='mirar')return;ir();if(modo==='colocar'){requestAnimationFrame(ir);setTimeout(ir,80);}};
R.sitio=function(){var d=cual(),s=d.s,max=maximo(d),r;if(d.raiz){r={x:scrollX,y:scrollY,arriba:scrollY,maximo:max};}else{var c=s.getBoundingClientRect();r={x:s.scrollLeft-c.left,y:s.scrollTop-c.top,arriba:s.scrollTop,maximo:max};}
var q=area(d);r.ax=q.ax;r.ay=q.ay;r.aw=q.aw;r.ah=q.ah;var v=window.visualViewport;r.vx=v?v.offsetLeft:0;r.vy=v?v.offsetTop:0;r.escala=v?v.scale:1;r.dpr=devicePixelRatio||1;return r;};
function mandar(o){try{window.chrome.webview.postMessage(JSON.stringify(o));}catch(_){}}
R.colgar=function(){var c=R.capa;if(!c)return;var e=R.donde(),padre=e||document.documentElement;if(!padre)return;if(c.parentNode!==padre)padre.appendChild(c);
if(e){if(getComputedStyle(e).position==='static')e.style.position='relative';c.style.left=(-e.clientLeft)+'px';c.style.top=(-e.clientTop)+'px';}else{c.style.left='0px';c.style.top='0px';}};
R.poner=function(t,m,ver,ctrlp,menu){R.ctrlp=ctrlp;R.menu=menu;var c=R.capa;
if(!c){c=R.capa=document.createElement('div');c.id='pixpin-riel-capa';c.setAttribute('aria-hidden','true');c.style.cssText='position:absolute;left:0;top:0;width:0;height:0;margin:0;padding:0;border:0;overflow:visible;pointer-events:none;user-select:none;z-index:2147483647';c.appendChild(document.createElement('div'));c.appendChild(document.createElement('div'));}
R.colgar();var a=c.firstChild,b=c.lastChild;if(a.__t!==t){a.innerHTML=t;a.__t=t;}a.style.visibility=ver?'visible':'hidden';if(b.__t!==m){b.innerHTML=m;b.__t=m;}
return location.protocol==='about:'?0:1;};
function editable(n){return!!n&&(n.isContentEditable||/^(INPUT|TEXTAREA|SELECT)$/.test(n.tagName||''));}
addEventListener('keydown',function(ev){if(ev.isComposing||ev.altKey||ev.metaKey||ev.repeat)return;var k=(ev.key||'').toLowerCase(),ed=editable(ev.target)||editable(document.activeElement),vk=0;
if(ev.ctrlKey){if(k==='p'&&R.ctrlp)vk=80;else if(k==='m'&&!ed&&!ev.shiftKey)vk=77;}
else if(!ed&&!ev.shiftKey){if(k==='i')vk=73;else if(k==='m')vk=77;else if(k==='a')vk=65;else if(k==='g'&&R.menu)vk=71;}
if(!vk)return;ev.preventDefault();ev.stopPropagation();mandar({pixpin_riel:'tecla',vk:vk,ctrl:!!ev.ctrlKey});},true);
function aviso(){R.raf=0;var r=R.sitio();r.pixpin_riel='sitio';mandar(r);}
function pronto(){if(!R.raf)R.raf=requestAnimationFrame(aviso);}
addEventListener('scroll',pronto,{capture:true,passive:true});addEventListener('resize',pronto,{passive:true});
if(window.visualViewport){visualViewport.addEventListener('scroll',pronto,{passive:true});visualViewport.addEventListener('resize',pronto,{passive:true});}
})();"""

        /**
         * El puente con la página: `chrome.webview` como el de WebView2 (lo que la página espera) sobre
         * la interfaz de Android, y las letras de la nota servidas desde las del teléfono.
         */
        const val PUENTE = "<script>(function(){var l=[];window.chrome=window.chrome||{};window.chrome.webview={" +
            "postMessage:function(m){PixPinNota.mandar(typeof m==='string'?m:JSON.stringify(m));}," +
            "addEventListener:function(t,f){if(t==='message')l.push(f);},removeEventListener:function(t,f){var i=l.indexOf(f);if(i>=0)l.splice(i,1);}};" +
            "window.__pixpinDesdeAndroid=function(s){for(var i=0;i<l.length;i++){try{l[i]({data:s});}catch(e){console.error(e);}}};})();</script>" +
            "<style>@font-face{font-family:'Work Sans';src:url('${ORIGEN}fuente/work_sans.ttf');font-weight:100 900;}" +
            "@font-face{font-family:'Fraunces';src:url('${ORIGEN}fuente/fraunces.ttf');font-weight:100 900;}</style>"

        /** Por dónde va la página (el `Sitio` del PC). */
        data class Sitio(
            val x: Double = 0.0, val y: Double = 0.0, val arriba: Double = 0.0, val maximo: Double = 0.0,
            val dpr: Double = 1.0, val vx: Double = 0.0, val vy: Double = 0.0, val escala: Double = 1.0,
            val ax: Double = 0.0, val ay: Double = 0.0, val aw: Double = 0.0, val ah: Double = 0.0, val nuevo: Boolean = false
        ) {
            /** La esquina de arriba a la izquierda de lo que enseña el documento, en el documento. */
            fun esquina() = doubleArrayOf(x + ax, y + ay)
            fun fraccion() = if (maximo > 0.5) (arriba / maximo).coerceIn(0.0, 1.0) else 0.0
            /** Documento (px CSS) a píxeles de la vista: `(d - cámara) · zoom`. */
            val camX get() = x + vx
            val camY get() = y + vy
            val zoom get() = maxOf(dpr * maxOf(escala, 0.01), 0.1)
            fun valido() = listOf(x, y, dpr, vx, vy, escala, ax, ay, aw, ah).all { it.isFinite() }

            companion object {
                fun de(o: JSONObject) = Sitio(
                    o.optDouble("x", 0.0), o.optDouble("y", 0.0), o.optDouble("arriba", 0.0), o.optDouble("maximo", 0.0),
                    o.optDouble("dpr", 1.0), o.optDouble("vx", 0.0), o.optDouble("vy", 0.0), o.optDouble("escala", 1.0),
                    o.optDouble("ax", 0.0), o.optDouble("ay", 0.0), o.optDouble("aw", 0.0), o.optDouble("ah", 0.0), o.optBoolean("nuevo", false)
                )
            }
        }

        /** El guion que hace una orden y devuelve el sitio (PC `riel::guion`). */
        fun guion(modo: String, a: Double = 0.0, b: Double = 0.0): String {
            fun f(v: Double) = if (v.isFinite()) v else 0.0
            return "$INSTALAR\n(function(modo,a,b){var R=window.__pixpinRiel;var n=R.nuevo;R.nuevo=false;R.colgar();R.ir(modo,a,b);var r=R.sitio();r.nuevo=n;return JSON.stringify(r);})(${JSONObject.quote(modo)},${f(a)},${f(b)})"
        }

        /** Los marcos como SVG para la página (PC `svg_de_los_marcos`): miden en pantalla lo de siempre. */
        fun svgDeLosMarcos(lista: List<com.forge.pixpin.motor.MarcoDoc>, z: Double, e: Double): String {
            val l = lista.filter { it.parte == 0 }
            if (l.isEmpty()) return ""
            val u = e / maxOf(z, 0.01)
            fun n(v: Double) = if (!v.isFinite()) "0" else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
            val s = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\" style=\"position:absolute;left:0;top:0;overflow:visible\">")
            l.forEachIndexed { i, m ->
                s.append("<rect x=\"${n(m.x0)}\" y=\"${n(m.y0)}\" width=\"${n(m.x1 - m.x0)}\" height=\"${n(m.y1 - m.y0)}\" fill=\"none\" stroke=\"#0078d4\" stroke-width=\"${n(2 * u)}\"/>")
                val t = (i + 1).toString(); val tam = 12 * u
                val ancho = t.length * tam * 0.56 + 12 * u
                s.append("<rect x=\"${n(m.x0)}\" y=\"${n(m.y0)}\" width=\"${n(ancho)}\" height=\"${n(20 * u)}\" fill=\"#0078d4\"/>")
                s.append("<text x=\"${n(m.x0 + 6 * u)}\" y=\"${n(m.y0 + 3 * u + 1.079 * tam)}\" font-family=\"Segoe UI, sans-serif\" font-size=\"${n(tam)}\" fill=\"#fff\">$t</text>")
            }
            return s.append("</svg>").toString()
        }
    }

    private var web: WebView? = null
    private var sitio by mutableStateOf(Sitio())
    private var listo = false
    private var texto = ""
    private var nombre = ""
    private var base: String? = null
    private var idDeLaTinta = ""
    private lateinit var tinta: DrawController
    private var marcosDoc = MarcosDoc()
    private var vistasDoc = VistasDoc()
    private var marcadores by mutableStateOf(emptyList<Lectura.Marcador>())
    private var anotando by mutableStateOf(false)
    private var poniendoMarca by mutableStateOf(false)
    private var versionDeMarcos by mutableIntStateOf(0)
    private var pulso by mutableIntStateOf(0)
    private var aLaVista by mutableStateOf(true)
    private var imprimiendo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        texto = intent.getStringExtra(EXTRA_TEXTO).orEmpty()
        nombre = intent.getStringExtra(EXTRA_NOMBRE).orEmpty().ifBlank { "Nota" }
        // De qué cuelga lo del riel: el código de la nota (viaja) o, sin él, algo solo de aquí.
        base = ComentariosDeNota.uidDe(this, id)?.let { AnotacionesDelAdjunto.delPdf(it) }
        val b = base
        fun guardado(terminacion: String, aqui: String) =
            if (b != null) com.forge.pixpin.motor.GuardadoDoc(File(filesDir, "${AnotacionesDelAdjunto.CARPETA}/$b$terminacion"), viaja = true)
            else com.forge.pixpin.motor.GuardadoDoc(File(File(filesDir, "riel-notas").apply { mkdirs() }, "${id.ifBlank { "suelta" }}$aqui"), viaja = false)
        marcosDoc = MarcosDoc.de(guardado(AnotacionesDelAdjunto.MARCOS, ".pixpin-marcos"))
        vistasDoc = VistasDoc.de(guardado(AnotacionesDelAdjunto.VISTAS, ".pixpin-vistas"))
        marcadores = Lectura.deTexto(if (b != null) AnotacionesDelAdjunto.leer(AnotacionesDelAdjunto.marcas(filesDir, b)) else guardado(".marcas", ".marcas").leer())
        idDeLaTinta = b ?: "nota-riel-${id.ifBlank { "suelta" }}"
        tinta = DrawController(ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(this, idDeLaTinta)) ?: Scene())
            .also { it.pedirLaMedida = false; it.selectTool(com.forge.pixpin.motor.Tool.FREEDRAW) }

        setContent {
            PixPinTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { nuevaVista(it) })
                        if (anotando) TintaSobreVisor(
                            controlador = tinta, anotando = true, pulso = pulso,
                            vistaDelVisor = { vistaDeLaTinta() },
                            alSeguir = { v -> seguir(v) },
                            alCambiar = { guardarLaTinta() },
                            alAcabar = { anotando = false; guardarLaTinta(); ponerEnLaPagina() },
                            oscuro = esDeNoche()
                        )
                        if (!anotando && marcosDoc.activo) CapaDeMarcos(
                            marcos = marcosDoc, version = versionDeMarcos,
                            aPantalla = { x, y -> @Suppress("UNUSED_EXPRESSION") sitio; aPantalla(x, y) },
                            aDocumento = { o -> aDocumento(o) },
                            px = { sitio.zoom },
                            alCambiar = { versionDeMarcos++; ponerEnLaPagina() },
                            alIrA = { i -> irAlMarco(i) },
                            alImprimir = { imprimirLosMarcos() },
                            alDesplazar = { pan, _, _ -> web?.evaluateJavascript(guion("colocar", sitio.esquina()[0] - pan.x / sitio.zoom, sitio.esquina()[1] - pan.y / sitio.zoom), null) }
                        )
                        if (!anotando) BarraDeVisor(
                            visible = aLaVista, tipo = "MD", nombre = nombre,
                            botones = listOf(BotonDeLector(Icons.Filled.EditNote, "Editar") { editar(id) }),
                            menu = listOfNotNull(
                                if (marcosDoc.lista.isNotEmpty()) OpcionDeMenu("Imprimir los marcos", Icons.Filled.Print) { imprimirLosMarcos() } else null,
                                if (marcosDoc.lista.isNotEmpty()) OpcionDeMenu("Borrar los marcos", Icons.Filled.DeleteSweep) { marcosDoc.borrar(); versionDeMarcos++; ponerEnLaPagina() } else null
                            ),
                            onCerrar = { finish() },
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                        @Suppress("UNUSED_EXPRESSION") versionDeMarcos
                        if (!anotando) RielDeVisor(
                            botonesDelRiel(
                                poniendoMarca = poniendoMarca, onMarca = { if (marcosDoc.activo) alternarMarcos(); poniendoMarca = !poniendoMarca },
                                anotando = false, onAnotar = { if (marcosDoc.activo) alternarMarcos(); poniendoMarca = false; empezarAAnotar() },
                                enmarcando = marcosDoc.activo, onMarcos = { poniendoMarca = false; alternarMarcos() }
                            ),
                            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 52.dp)
                        )
                        if (!anotando && marcadores.isNotEmpty()) RielDeMarcas(marcadores.size, { i -> marcadores[i].emoji }, Modifier.align(Alignment.CenterEnd)) { i ->
                            marcadores.getOrNull(i)?.let { irALaMarca(it) }
                        }
                        if (poniendoMarca && !anotando) ElegirEmojiDeMarca(Modifier.align(Alignment.BottomCenter), onCerrar = { poniendoMarca = false }) { emoji ->
                            poniendoMarca = false
                            ponerMarca(emoji)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------- la página

    @SuppressLint("SetJavaScriptEnabled")
    private fun nuevaVista(c: Context): WebView = WebView(c).apply {
        web = this
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        setBackgroundColor(if (esDeNoche()) 0xFF15171C.toInt() else 0xFFFFFFFF.toInt())
        addJavascriptInterface(object {
            @JavascriptInterface fun mandar(s: String) { runOnUiThread { deLaPagina(s) } }
        }, "PixPinNota")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, r: WebResourceRequest): WebResourceResponse? = servir(r.url)
            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean {
                if (r.url.host == "pixpin.nota") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, r.url)) }
                return true
            }
        }
        val html = assets.open("lector-md.html").bufferedReader().readText()
        val i = html.indexOf("<head>").takeIf { it >= 0 }?.plus(6) ?: 0
        loadDataWithBaseURL(ORIGEN, html.substring(0, i) + PUENTE + html.substring(i), "text/html", "utf-8", null)
    }

    /** Lo que la página pide bajo `https://pixpin.nota/`: las letras y las fotos de la nota. */
    private fun servir(u: Uri): WebResourceResponse? {
        if (u.host != "pixpin.nota") return null
        val p = u.path.orEmpty()
        if (p.startsWith("/fuente/")) {
            val r = when (p.removePrefix("/fuente/")) { "work_sans.ttf" -> com.forge.pixpin.R.font.work_sans; "fraunces.ttf" -> com.forge.pixpin.R.font.fraunces; else -> return null }
            return WebResourceResponse("font/ttf", null, resources.openRawResource(r))
        }
        if (p == "/archivo") {
            val r = u.getQueryParameter("r") ?: return null
            val f = File(r).takeIf { it.isAbsolute } ?: File(filesDir, r)
            val dentro = runCatching { f.canonicalPath.startsWith(filesDir.canonicalPath) || f.canonicalPath.startsWith(cacheDir.canonicalPath) }.getOrDefault(false)
            if (!dentro || !f.isFile) return WebResourceResponse("text/plain", null, 404, "No", null, null)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
            return WebResourceResponse(mime, null, f.inputStream())
        }
        return null
    }

    private fun aLaPagina(o: JSONObject) {
        web?.evaluateJavascript("window.__pixpinDesdeAndroid(${JSONObject.quote(o.toString())})", null)
    }

    /** Lo que manda la página: lo de la nota y lo del riel (por dónde va). */
    private fun deLaPagina(s: String) {
        val o = runCatching { JSONObject(s) }.getOrNull() ?: return
        when {
            o.optString("pixpin_riel") == "sitio" -> {
                val nuevo = Sitio.de(o)
                if (nuevo.valido()) { sitio = nuevo; pulso++; aLaVista = false }
            }
            o.optString("tipo") == "listo" -> {
                aLaPagina(JSONObject().put("tipo", "abrir").put("idioma", "es").put("nombre", nombre).put("texto", texto).put("enPin", true))
                aLaPagina(JSONObject().put("tipo", "orden").put("que", "leer"))
                listo = true
                // El guion del riel, y la tinta y los marcos dentro.
                web?.postDelayed({ mirar(); ponerEnLaPagina() }, 120)
            }
            o.optString("tipo") == "abrir_enlace" -> o.optString("url").takeIf { it.isNotBlank() }?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
            o.optString("tipo") == "editar" || o.optString("tipo") == "cerrar" -> if (o.optString("tipo") == "cerrar") finish()
        }
    }

    /** Pregunta por dónde va (y deja el guion del riel puesto). */
    private fun mirar(alLlegar: ((Sitio) -> Unit)? = null) {
        web?.evaluateJavascript(guion("mirar")) { r -> leerSitio(r)?.let { sitio = it; pulso++; alLlegar?.invoke(it) } }
    }

    private fun leerSitio(r: String?): Sitio? = runCatching {
        // evaluateJavascript devuelve la cadena JSON entre comillas.
        val t = JSONObject("{\"v\":$r}").getString("v")
        Sitio.de(JSONObject(t)).takeIf { it.valido() }
    }.getOrNull()

    private fun aPantalla(x: Double, y: Double) = Offset(((x - sitio.camX) * sitio.zoom).toFloat(), ((y - sitio.camY) * sitio.zoom).toFloat())

    private fun aDocumento(o: Offset): DoubleArray = doubleArrayOf(sitio.camX + o.x / sitio.zoom, sitio.camY + o.y / sitio.zoom)

    /** La vista de la tinta: el documento en px CSS con la cámara de la página. */
    private fun vistaDeLaTinta() = Viewport(scrollX = -sitio.camX, scrollY = -sitio.camY, zoom = sitio.zoom)

    /** Con dos dedos se mueve la tinta: la página va detrás (el zoom es el de la página). */
    private fun seguir(v: Viewport) {
        val camX = -v.scrollX; val camY = -v.scrollY
        if (kotlin.math.abs(v.zoom - sitio.zoom) > 1e-3) tinta.setViewport(v.copy(zoom = sitio.zoom))
        if (kotlin.math.abs(camX - sitio.camX) < 0.5 && kotlin.math.abs(camY - sitio.camY) < 0.5) return
        val ex = camX - sitio.vx + sitio.ax; val ey = camY - sitio.vy + sitio.ay
        sitio = sitio.copy(x = camX - sitio.vx, y = camY - sitio.vy)
        web?.evaluateJavascript(guion("colocar", ex, ey), null)
    }

    private fun esDeNoche() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    // --------------------------------------------------------------- la tinta y los marcos

    private fun empezarAAnotar() {
        mirar { tinta.setViewport(vistaDeLaTinta()); anotando = true; ponerEnLaPagina() }
    }

    private fun guardarLaTinta() {
        val escena = tinta.scene
        lifecycleScope.launch(Dispatchers.IO) { ExcalidrawStore.guardar(this@VisorDeNotaActivity, idDeLaTinta, escena) }
    }

    private fun alternarMarcos() {
        marcosDoc.alternar(); versionDeMarcos++
        ponerEnLaPagina()
    }

    /**
     * **La tinta y los marcos dentro de la página** (PC `capa_web::guion_poner`): el SVG de la tinta con
     * el papel de noche si lo es, y el de los marcos; mientras se traza o se ponen marcos, los pinta
     * Android y en la página se esconden.
     */
    private fun ponerEnLaPagina(conMarcos: Boolean = true) {
        val vista = web ?: return
        if (!listo) return
        val escena = tinta.scene
        val deNoche = esDeNoche()
        val marcos = if (!conMarcos || marcosDoc.activo) "" else svgDeLosMarcos(marcosDoc.lista, sitio.zoom, resources.displayMetrics.density.toDouble())
        val ver = !anotando
        lifecycleScope.launch {
            val svg = withContext(Dispatchers.IO) {
                runCatching {
                    if (escena.elements.none { !it.isDeleted }) return@runCatching ""
                    val papel = escena.copy(backgroundColor = if (deNoche) "#15171c" else "#ffffff")
                    val foto: (String) -> Bitmap? = { f -> escena.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } }
                    val t = com.forge.pixpin.motor.DrawSvg.aTexto(this@VisorDeNotaActivity, papel, foto, papelAparte = true) ?: return@runCatching ""
                    val caja = com.forge.pixpin.motor.DocumentoAnotado.cajaDe(t) ?: return@runCatching ""
                    com.forge.pixpin.motor.DocumentoAnotado.sinCabecera(t).replaceFirst(
                        "<svg ", "<svg style=\"position:absolute;left:${caja[0]}px;top:${caja[1]}px;width:${caja[2]}px;height:${caja[3]}px;overflow:visible\" "
                    )
                }.getOrDefault("")
            }
            if (web !== vista) return@launch
            vista.evaluateJavascript("$INSTALAR\nwindow.__pixpinRiel.poner(${JSONObject.quote(svg)},${JSONObject.quote(marcos)},$ver,false,false)", null)
        }
    }

    private fun irAlMarco(i: Int) {
        val m = marcosDoc.lista.getOrNull(i) ?: return
        web?.evaluateJavascript(guion("colocar", m.x0 - 24, m.y0 - 24)) { r -> leerSitio(r)?.let { sitio = it; pulso++ } }
    }

    /**
     * **Imprimir los marcos en grupo** (PC `riel.rs`): la vista se lleva a cada marco, se pinta sin las
     * rayas azules (la tinta sí sale) y se fotografía; uno más grande que la vista, por trozos cosidos.
     */
    private fun imprimirLosMarcos() {
        val vista = web ?: return
        if (imprimiendo || marcosDoc.lista.isEmpty()) return
        imprimiendo = true
        if (marcosDoc.activo) alternarMarcos()
        lifecycleScope.launch {
            ponerEnLaPagina(conMarcos = false)
            delay(150)
            val fotos = ArrayList<Bitmap>()
            for (m in marcosDoc.lista.filter { it.parte == 0 }) {
                val anchoDoc = vista.width / sitio.zoom; val altoDoc = vista.height / sitio.zoom
                val z = sitio.zoom
                val w = ((m.x1 - m.x0) * z).toInt().coerceAtLeast(1); val h = ((m.y1 - m.y0) * z).toInt().coerceAtLeast(1)
                if (w.toLong() * h > 40_000_000L) continue
                val foto = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val lienzo = android.graphics.Canvas(foto)
                var y = m.y0
                while (y < m.y1 - 0.5) {
                    var x = m.x0
                    while (x < m.x1 - 0.5) {
                        val s = colocarYEsperar(x, y) ?: break
                        val trozo = Bitmap.createBitmap(vista.width, vista.height, Bitmap.Config.ARGB_8888)
                        vista.draw(android.graphics.Canvas(trozo))
                        // Lo que cae del marco en este trozo: de documento a la vista y al marco.
                        val sx = ((x - s.camX) * z).toFloat(); val sy = ((y - s.camY) * z).toFloat()
                        lienzo.drawBitmap(trozo, ((x - m.x0) * z).toFloat() - sx, ((y - m.y0) * z).toFloat() - sy, null)
                        trozo.recycle()
                        x += anchoDoc
                    }
                    y += altoDoc
                }
                fotos += foto
            }
            ponerEnLaPagina()
            imprimiendo = false
            com.forge.pixpin.guardados.Imprimir.fotos(this@VisorDeNotaActivity, fotos, nombre)
        }
    }

    private suspend fun colocarYEsperar(x: Double, y: Double): Sitio? {
        val vista = web ?: return null
        var r: Sitio? = null
        vista.evaluateJavascript(guion("colocar", x, y)) { r = leerSitio(it) }
        delay(260)
        val final = kotlinx.coroutines.CompletableDeferred<Sitio?>()
        vista.evaluateJavascript(guion("mirar")) { final.complete(leerSitio(it) ?: r) }
        return final.await()?.also { sitio = it }
    }

    // ------------------------------------------------------------------------- los marcadores

    private fun guardarMarcadores() {
        val t = Lectura.aTexto(marcadores)
        val b = base
        if (b != null) AnotacionesDelAdjunto.escribir(AnotacionesDelAdjunto.marcas(filesDir, b), t)
        else File(File(filesDir, "riel-notas").apply { mkdirs() }, "${intent.getStringExtra(EXTRA_ID).orEmpty().ifBlank { "suelta" }}.marcas").writeText(t)
        vistasDoc.soloDe(marcadores.map { it.id.toString() }.toSet())
    }

    /** Un marcador aquí: la fracción (como el Word, para las versiones de antes) y su vista exacta. */
    private fun ponerMarca(emoji: String) {
        mirar { s ->
            val m = Lectura.Marcador(System.currentTimeMillis(), s.fraccion().toFloat(), emoji)
            marcadores = (marcadores + m).sortedBy { it.fraccion }
            vistasDoc.poner(m.id.toString(), VistasDoc.zoomATexto(1.0, s.esquina()[0], s.esquina()[1]))
            guardarMarcadores()
        }
    }

    private fun irALaMarca(m: Lectura.Marcador) {
        val v = VistasDoc.zoomDeTexto(vistasDoc.deMarca(m.id.toString()))
        web?.evaluateJavascript(if (v != null) guion("colocar", v.second, v.third) else guion("ir", m.fraccion.toDouble())) { r -> leerSitio(r)?.let { sitio = it; pulso++ } }
    }

    private fun editar(id: String) {
        MarkdownEditorActivity.abrir(this, id, texto)
        finish()
    }

    override fun onDestroy() {
        web?.destroy(); web = null
        super.onDestroy()
    }
}
