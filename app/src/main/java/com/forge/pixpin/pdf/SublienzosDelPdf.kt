package com.forge.pixpin.pdf

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.motor.DrawExport
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.getCommonBounds
import com.forge.pixpin.motor.getElementBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Los sublienzos de cada página del PDF, en el lector.**
 *
 * Lo pidió el usuario el 13-sep-2026: al abrir un PDF, ver dónde están los recortes que se mandaron
 * al chat (un recuadro en la página) y, **alejando esa página con los dedos**, verlos a los lados, a
 * su altura y unidos por una línea a su recuadro. Acercándola, desaparecen. Va por página: las demás
 * no cambian.
 *
 * Ligero a propósito: lo que hace falta saber (dónde está cada recorte) se lee una vez al abrir, y
 * las miniaturas solo se dibujan cuando se abre la página que las tiene.
 */
object SublienzosDelPdf {

    /** Un recorte de una página: su sublienzo y dónde está, en fracciones de la página (0..1). */
    data class Recorte(val dibujo: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val proyecto: String?)

    /**
     * Los recortes de cada página de [ruta], si es el PDF de un proyecto. Trabajo de disco.
     * Las marcas viven en el lienzo de la página ([com.forge.pixpin.motor.Element.enlace]), en
     * unidades del papel: el ancho de la página rasterizada a [PdfDoc.PAGE_WIDTH].
     */
    fun de(context: Context, ruta: String): Map<Int, List<Recorte>> {
        val app = context.applicationContext as? PixPinApp ?: return emptyMap()
        val p = app.proyectos.proyectos.value.firstOrNull { it.pdfOrigen == ruta || it.pdfLimpio == ruta } ?: return emptyMap()
        val salida = HashMap<Int, MutableList<Recorte>>()
        for (h in p.hojas) {
            val pagina = h.pagina ?: continue
            val dibujo = h.dibujo ?: continue
            val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, dibujo)) ?: continue
            val marcas = escena.elements.filter { !it.isDeleted && it.enlace != null }
            if (marcas.isEmpty()) continue
            val (an, al) = PdfDoc.medidaEnPuntos(ruta, pagina) ?: continue
            val anchoPapel = PdfDoc.PAGE_WIDTH.toFloat()
            val altoPapel = (anchoPapel * al / an).toFloat()
            for (m in marcas) {
                val b = getElementBounds(m)
                salida.getOrPut(pagina) { ArrayList() } += Recorte(
                    m.enlace!!,
                    (b.x1 / anchoPapel).toFloat().coerceIn(0f, 1f), (b.y1 / altoPapel).toFloat().coerceIn(0f, 1f),
                    (b.x2 / anchoPapel).toFloat().coerceIn(0f, 1f), (b.y2 / altoPapel).toFloat().coerceIn(0f, 1f),
                    p.id
                )
            }
        }
        return salida.mapValues { (_, l) -> l.sortedBy { it.y0 } }
    }

    /** El PDF que conviene enseñar: el del proyecto con lo anotado, si [ruta] es su copia limpia. */
    fun documentoDe(context: Context, ruta: String): String {
        val app = context.applicationContext as? PixPinApp ?: return ruta
        val p = app.proyectos.proyectos.value.firstOrNull { it.pdfLimpio == ruta } ?: return ruta
        return p.pdfOrigen?.takeIf { File(it).exists() } ?: ruta
    }

    /** La miniatura de un sublienzo: la foto de la zona con lo resuelto encima. */
    fun miniatura(context: Context, dibujo: String, lado: Int): Bitmap? {
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, dibujo)) ?: return null
        val visibles = escena.contenidoVisible
        if (visibles.isEmpty()) return null
        val caja = getCommonBounds(visibles)
        val escala = (lado / maxOf(caja.width, caja.height).coerceAtLeast(1.0)).coerceAtMost(2.0)
        return DrawExport.aBitmap(escena, escala) { id -> escena.files[id]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it, lado) } }
    }
}

private val AZUL_DE_LA_ZONA = Color(0xFF1971C2)

/**
 * Una hoja del lector con sus recortes. [abierta]: alejada, con las miniaturas a los lados.
 * [imagen] es la página ya rasterizada (o null mientras llega) y [proporcion] su ancho / alto.
 */
@Composable
internal fun HojaConSublienzos(
    imagen: Bitmap?,
    proporcion: Float,
    recortes: List<SublienzosDelPdf.Recorte>,
    abierta: Boolean,
    alAbrir: (SublienzosDelPdf.Recorte) -> Unit
) {
    val contexto = LocalContext.current
    // La página encoge al abrirse y deja sitio a los lados; animado, que se entienda qué pasa.
    val ancho by animateFloatAsState(if (abierta && recortes.isNotEmpty()) 0.56f else 1f, label = "hoja")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        val densidad = LocalDensity.current
        val total = constraints.maxWidth.toFloat()
        val anchoHoja = total * ancho
        val altoHoja = anchoHoja / proporcion.coerceAtLeast(0.1f)
        val izquierda = (total - anchoHoja) / 2f
        val ladoMini = (total - anchoHoja) / 2f - with(densidad) { 12.dp.toPx() }
        val visibles = abierta && recortes.isNotEmpty() && ancho < 0.6f

        // Dónde va cada miniatura: a la altura de su recorte, alternando lado, sin montarse.
        data class Puesto(val r: SublienzosDelPdf.Recorte, val derecha: Boolean, val y: Float)
        val puestos = remember(recortes, altoHoja, ladoMini) {
            val finIzq = floatArrayOf(-1f)
            val finDer = floatArrayOf(-1f)
            recortes.mapIndexed { i, r ->
                val centro = (r.y0 + r.y1) / 2f * altoHoja
                val derecha = i % 2 == 1
                val fin = if (derecha) finDer else finIzq
                val y = maxOf(centro - ladoMini / 2f, fin[0] + with(densidad) { 8.dp.toPx() }, 0f)
                fin[0] = y + ladoMini
                Puesto(r, derecha, y)
            }
        }
        val altoTotal = maxOf(altoHoja, if (visibles) (puestos.maxOfOrNull { it.y + ladoMini } ?: 0f) else 0f)

        Box(Modifier.fillMaxWidth().height(with(densidad) { altoTotal.toDp() })) {
            Box(
                Modifier
                    .offset(x = with(densidad) { izquierda.toDp() })
                    .size(with(densidad) { anchoHoja.toDp() }, with(densidad) { altoHoja.toDp() })
                    .background(Color.White)
            ) {
                if (imagen != null) Image(imagen.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                // El recuadro de cada recorte, siempre: dice que ahí hay algo resuelto.
                Canvas(Modifier.fillMaxSize()) {
                    for (r in recortes) {
                        drawRect(
                            AZUL_DE_LA_ZONA,
                            topLeft = Offset(r.x0 * size.width, r.y0 * size.height),
                            size = Size((r.x1 - r.x0) * size.width, (r.y1 - r.y0) * size.height),
                            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
                        )
                    }
                }
                if (!abierta && recortes.isNotEmpty()) {
                    Text(
                        "${recortes.size} ${if (recortes.size == 1) "recorte" else "recortes"} · aleja con dos dedos",
                        color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(AZUL_DE_LA_ZONA.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            if (visibles) {
                // Las líneas, de la miniatura a su recuadro.
                Canvas(Modifier.fillMaxSize()) {
                    for (p in puestos) {
                        val desde = Offset(if (p.derecha) izquierda + anchoHoja + with(densidad) { 6.dp.toPx() } + 0f else izquierda - with(densidad) { 6.dp.toPx() }, p.y + ladoMini / 2f)
                        val hasta = Offset(
                            izquierda + (if (p.derecha) p.r.x1 else p.r.x0) * anchoHoja,
                            (p.r.y0 + p.r.y1) / 2f * altoHoja
                        )
                        drawLine(AZUL_DE_LA_ZONA, desde, hasta, strokeWidth = 2.dp.toPx())
                        drawCircle(AZUL_DE_LA_ZONA, 4.dp.toPx(), hasta)
                    }
                }
                for (p in puestos) {
                    val x = if (p.derecha) izquierda + anchoHoja + with(densidad) { 6.dp.toPx() } else with(densidad) { 6.dp.toPx() }
                    Miniatura(p.r, ladoMini, x, p.y, alAbrir)
                }
            }
        }
    }
}

@Composable
private fun Miniatura(r: SublienzosDelPdf.Recorte, lado: Float, x: Float, y: Float, alAbrir: (SublienzosDelPdf.Recorte) -> Unit) {
    val contexto = LocalContext.current
    val densidad = LocalDensity.current
    var mapa by remember(r.dibujo) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(r.dibujo) {
        mapa = withContext(Dispatchers.IO) { runCatching { SublienzosDelPdf.miniatura(contexto, r.dibujo, 360) }.getOrNull() }
    }
    Box(
        Modifier
            .offset(x = with(densidad) { x.toDp() }, y = with(densidad) { y.toDp() })
            .size(with(densidad) { lado.toDp() })
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(2.dp, AZUL_DE_LA_ZONA, RoundedCornerShape(10.dp))
            .clickable { alAbrir(r) },
        contentAlignment = Alignment.Center
    ) {
        mapa?.let { Image(it.asImageBitmap(), contentDescription = "Sublienzo", modifier = Modifier.fillMaxSize().padding(4.dp)) }
    }
}
