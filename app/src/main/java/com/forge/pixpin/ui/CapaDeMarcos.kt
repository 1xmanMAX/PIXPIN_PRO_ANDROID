package com.forge.pixpin.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motor.MarcosDelDocumento
import com.forge.pixpin.motor.MarcosDelDocumento.Marco
import java.io.File

/**
 * **Los marcos de un documento abierto** (24-oct-2026): la lista, si se están poniendo, y dónde se
 * guardan. Uno por visor; ver [MarcosDelDocumento] para las reglas y el formato.
 *
 * [archivo] null: solo esta sesión (un documento que no es de ningún chat).
 */
class MarcosDelVisor(private val archivo: File?) {
    var lista by mutableStateOf(archivo?.let { MarcosDelDocumento.leer(it) } ?: emptyList())
        private set
    /** Si se están poniendo (el marco del riel, en azul). */
    var activo by mutableStateOf(false)
    /** Fotografiando para imprimir: se pintan sin raya ni número, como en el PC. */
    var imprimiendo by mutableStateOf(false)

    fun poner(nueva: List<Marco>) {
        lista = nueva
        archivo?.let { runCatching { MarcosDelDocumento.guardar(it, nueva) } }
    }

    fun alternar() { activo = !activo }
    fun quitarUltimo() = poner(MarcosDelDocumento.sinElUltimo(lista))
    fun borrar() = poner(emptyList())

    /** Releer del disco (llegó del otro aparato). */
    fun releer() { archivo?.let { lista = MarcosDelDocumento.leer(it) } }
}

/**
 * **Las vistas de los marcadores** de un documento: el zoom y el sitio de cada uno, por su id
 * (`anot-<uid>.vistas.json`). Un marcador sin vista vuelve solo a su sitio, como antes.
 */
class VistasDelVisor(private val archivo: File?) {
    private var de: Map<String, String> = archivo?.let { MarcosDelDocumento.leerVistas(it) } ?: emptyMap()

    fun poner(id: Long, zoom: Double, x: Double, y: Double) {
        de = de + (id.toString() to MarcosDelDocumento.zoomATexto(zoom, x, y))
        archivo?.let { runCatching { MarcosDelDocumento.guardarVistas(it, de) } }
    }

    fun quitar(id: Long) {
        if (id.toString() !in de) return
        de = de - id.toString()
        archivo?.let { runCatching { MarcosDelDocumento.guardarVistas(it, de) } }
    }

    /** (zoom, x, y) del marcador, o null. */
    fun de(id: Long): Triple<Double, Double, Double>? = MarcosDelDocumento.zoomDeTexto(de[id.toString()])
}

/** Un punto del documento: sus coordenadas y su parte (la hoja de un libro de cálculo; si no, 0). */
data class PuntoDelDocumento(val x: Double, val y: Double, val parte: Int = 0)

private val AZUL = Color(MarcosDelDocumento.AZUL)

/**
 * **La capa de los marcos**, encima del documento y a su tamaño.
 *
 * [aPantalla] lleva un punto del documento (de la parte dada) a esta capa, en píxeles; se llama
 * **al pintar**, así que leer ahí el desplazamiento y el zoom del visor hace que los marcos se
 * muevan en el mismo fotograma que el documento: no se quedan atrás. Null si esa parte no se ve.
 *
 * Con el modo puesto, un dedo arrastra un marco nuevo (dos dedos se dejan a [alDosDedos], si el
 * visor sabe moverse así); abajo a la izquierda las fichas 1, 2, 3… —tocar una va a ese marco— y
 * la pista con quitar el último, borrar e imprimir.
 */
@Composable
fun CapaDeMarcos(
    estado: MarcosDelVisor,
    aPantalla: (x: Double, y: Double, parte: Int) -> Offset?,
    aDocumento: (Offset) -> PuntoDelDocumento?,
    pxPorUnidad: (parte: Int) -> Double,
    alIr: (Marco) -> Unit,
    alImprimir: () -> Unit,
    modifier: Modifier = Modifier,
    /** A trazos y de grosor fijo, como los del lienzo; los de los documentos, lisos. */
    discontinuo: Boolean = false,
    alDosDedos: ((pan: Offset, zoom: Float, centro: Offset) -> Unit)? = null,
    /** Si los ya puestos los pinta el propio visor (la hoja de Excel), aquí solo el que se arrastra. */
    pintarLosPuestos: Boolean = true
) {
    val aPantallaYa by rememberUpdatedState(aPantalla)
    val aDocumentoYa by rememberUpdatedState(aDocumento)
    val pxYa by rememberUpdatedState(pxPorUnidad)
    val dosDedosYa by rememberUpdatedState(alDosDedos)
    var nuevo by remember { mutableStateOf<Pair<PuntoDelDocumento, PuntoDelDocumento>?>(null) }
    val texto = remember {
        Paint().apply { isAntiAlias = true; color = android.graphics.Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    }
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().then(
                if (!estado.activo) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val abajo = awaitFirstDown()
                        val inicio = aDocumentoYa(abajo.position)
                        abajo.consume()
                        nuevo = inicio?.let { it to it }
                        var dos = false
                        var ultimo = abajo.position
                        while (true) {
                            val e = awaitPointerEvent()
                            val vivos = e.changes.filter { it.pressed }
                            if (vivos.isEmpty()) break
                            if (vivos.size >= 2) {
                                dos = true; nuevo = null
                                // Sin [alDosDedos], el pellizco es del visor de debajo (o de quien envuelve la capa).
                                val f = dosDedosYa ?: continue
                                run {
                                    val a = vivos[0]; val b = vivos[1]
                                    val antes = (a.previousPosition - b.previousPosition).getDistance()
                                    val ahora = (a.position - b.position).getDistance()
                                    val centro = (a.position + b.position) / 2f
                                    val centroAntes = (a.previousPosition + b.previousPosition) / 2f
                                    f(centro - centroAntes, if (antes > 1f) ahora / antes else 1f, centro)
                                }
                                vivos.forEach { it.consume() }
                                continue
                            }
                            val c = vivos[0]
                            c.consume()
                            ultimo = c.position
                            if (!dos) {
                                val p = aDocumentoYa(c.position)
                                val ini = nuevo?.first
                                if (p != null && ini != null) nuevo = ini to p.copy(parte = ini.parte)
                            }
                        }
                        val par = nuevo
                        nuevo = null
                        if (!dos && par != null) {
                            val fin = aDocumentoYa(ultimo)?.copy(parte = par.first.parte) ?: par.second
                            MarcosDelDocumento.conNuevo(
                                estado.lista, par.first.x, par.first.y, fin.x, fin.y, pxYa(par.first.parte), par.first.parte
                            )?.let { estado.poner(it) }
                        }
                    }
                }
            )
        ) {
            if (estado.imprimiendo) return@Canvas
            val e = density
            val grosor = (if (discontinuo) 1.5f else 2f) * e
            val trazo = Stroke(grosor, pathEffect = if (discontinuo) PathEffect.dashPathEffect(floatArrayOf(6 * e, 4 * e)) else null)
            fun caja(m: Marco): Rect? {
                val a = aPantallaYa(m.x0, m.y0, m.parte) ?: return null
                val b = aPantallaYa(m.x1, m.y1, m.parte) ?: return null
                return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
            }
            if (pintarLosPuestos) estado.lista.forEachIndexed { i, m ->
                val r = caja(m) ?: return@forEachIndexed
                if (r.left > size.width + 40 * e || r.top > size.height + 40 * e || r.right < -40 * e || r.bottom < -40 * e) return@forEachIndexed
                drawRect(AZUL, r.topLeft, r.size, style = trazo)
                // El número blanco en su cuadrito azul, en la esquina de arriba a la izquierda.
                val n = (i + 1).toString()
                texto.textSize = 12 * e
                val w = texto.measureText(n)
                drawRect(AZUL, r.topLeft, androidx.compose.ui.geometry.Size(w + 12 * e, 20 * e))
                drawContext.canvas.nativeCanvas.drawText(n, r.left + 6 * e, r.top + 14.5f * e, texto)
            }
            nuevo?.let { (a, b) ->
                val r = caja(Marco.entre(a.x, a.y, b.x, b.y, a.parte)) ?: return@let
                drawRect(AZUL, r.topLeft, r.size, style = Stroke(1.5f * e))
            }
        }
        if (estado.activo && !estado.imprimiendo) PistaDeMarcos(estado, alIr, alImprimir, Modifier.align(Alignment.BottomStart))
    }
}

/** Las fichas 1, 2, 3… y la pista, abajo a la izquierda (`fichas_de` del PC). */
@Composable
private fun PistaDeMarcos(estado: MarcosDelVisor, alIr: (Marco) -> Unit, alImprimir: () -> Unit, modifier: Modifier) {
    Row(
        modifier.navigationBarsPadding().padding(12.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        estado.lista.forEachIndexed { i, m ->
            Box(
                Modifier.padding(end = 6.dp).size(30.dp).clip(RoundedCornerShape(6.dp)).background(AZUL)
                    .clickable(onClickLabel = "Ir al marco ${i + 1}") { alIr(m) },
                contentAlignment = Alignment.Center
            ) { Text("${i + 1}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
        Row(
            Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50)).background(Color(0xE6141417)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (estado.lista.isEmpty()) "Arrastra para poner un marco" else "Arrastra para otro",
                color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )
            @Composable
            fun Accion(t: String, al: () -> Unit) = Text(
                t, color = Color(0xFF8AB4F8), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = al).padding(horizontal = 8.dp, vertical = 8.dp)
            )
            if (estado.lista.isNotEmpty()) {
                Accion("Quitar el último") { estado.quitarUltimo() }
                Accion("Borrar") { estado.borrar() }
                Accion("Imprimir (${estado.lista.size})", alImprimir)
            }
        }
    }
}

/**
 * **Imprimir los marcos en grupo** (como `marco_imprimir::imprimir_fotos` del PC): el visor se
 * acerca a cada marco hasta llenar la pantalla ([ir]), se pinta sin su interfaz ([MarcosDelVisor.imprimiendo]),
 * se fotografía lo que ocupa ([cajaEnVentana], en píxeles de la ventana) y todas las fotos van
 * juntas, una hoja por marco y en su orden, al diálogo de imprimir del sistema.
 */
object ImprimirMarcos {
    suspend fun imprimir(
        actividad: Activity,
        estado: MarcosDelVisor,
        nombre: String,
        ir: suspend (Marco) -> Unit,
        cajaEnVentana: (Marco) -> Rect?
    ) {
        val marcos = estado.lista
        if (marcos.isEmpty()) return
        estado.imprimiendo = true
        val fotos = ArrayList<Bitmap>()
        try {
            for (m in marcos) {
                ir(m)
                // Dos fotogramas y un respiro: lo que se pinta por teselas (un PDF, una página web) llega tarde.
                kotlinx.coroutines.delay(450)
                val r = cajaEnVentana(m) ?: continue
                fotografiar(actividad, r)?.let { fotos += it }
            }
        } finally {
            estado.imprimiendo = false
        }
        if (fotos.isEmpty()) {
            android.widget.Toast.makeText(actividad, "No se pudo fotografiar ningún marco", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val pdf = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { aPdf(fotos) }
        com.forge.pixpin.guardados.Imprimir.pdf(actividad, pdf, nombre)
    }

    private suspend fun fotografiar(actividad: Activity, r: Rect): Bitmap? {
        val ventana = actividad.window ?: return null
        val raiz = ventana.decorView
        val x0 = r.left.toInt().coerceIn(0, raiz.width - 1)
        val y0 = r.top.toInt().coerceIn(0, raiz.height - 1)
        val x1 = r.right.toInt().coerceIn(x0 + 1, raiz.width)
        val y1 = r.bottom.toInt().coerceIn(y0 + 1, raiz.height)
        val bmp = Bitmap.createBitmap(x1 - x0, y1 - y0, Bitmap.Config.ARGB_8888)
        return kotlinx.coroutines.suspendCancellableCoroutine { k ->
            runCatching {
                android.view.PixelCopy.request(
                    ventana, android.graphics.Rect(x0, y0, x1, y1), bmp,
                    { res -> if (k.isActive) k.resumeWith(Result.success(if (res == android.view.PixelCopy.SUCCESS) bmp else null)) },
                    android.os.Handler(android.os.Looper.getMainLooper())
                )
            }.onFailure { if (k.isActive) k.resumeWith(Result.success(null)) }
        }
    }

    /** Una hoja A4 por foto, encajada con un dedo de margen y del lado que mejor le va. */
    fun aPdf(fotos: List<Bitmap>): ByteArray {
        val doc = android.graphics.pdf.PdfDocument()
        fotos.forEachIndexed { i, f ->
            val apaisada = f.width > f.height
            val (w, h) = if (apaisada) 842 to 595 else 595 to 842
            val pagina = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            val margen = 28f
            val k = minOf((w - 2 * margen) / f.width, (h - 2 * margen) / f.height)
            val dw = f.width * k; val dh = f.height * k
            val dst = android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
            pagina.canvas.drawBitmap(f, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(pagina)
        }
        val salida = java.io.ByteArrayOutputStream()
        doc.writeTo(salida)
        doc.close()
        return salida.toByteArray()
    }
}

