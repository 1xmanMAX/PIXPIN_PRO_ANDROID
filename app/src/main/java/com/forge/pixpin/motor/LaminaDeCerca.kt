package com.forge.pixpin.motor

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * **Lo que se está mirando del PDF, rasterizado a la resolución de la pantalla.**
 *
 * El mosaico ([ElMosaicoDelPapel]) parte el plano en cuadros y los rasteriza a la mejor
 * resolución que cabe en el teléfono, pero eso sigue siendo **una imagen fija**: tiene los
 * píxeles que tiene y ni uno más. El lienzo amplía hasta [Viewport.MAX_ZOOM] = 400 aumentos,
 * y pasados los doce o dieciséis que da el mosaico lo que se ve es esa imagen estirada — o
 * sea, las letras del plano hechas una mancha. No hay número de cuadros que arregle eso: para
 * llegar a 400 aumentos harían falta cientos de miles de millones de píxeles.
 *
 * Lo que sí llega es **volver al PDF y pedirle ese pedazo**, que es vectorial y por tanto no
 * tiene resolución: se rasteriza el rectángulo que se está mirando, a los píxeles que tiene
 * la pantalla y ni uno más, y se pinta encima del mosaico. Es lo que hace cualquier lector de
 * PDF de escritorio, y es lo único que deja leer una cota a cincuenta aumentos.
 *
 * ## Lo que cuesta y cómo se paga
 *
 * Rasterizar una pantalla es barato; **la pasada al documento no**. En el A0 de arquitectura
 * que sirve de banco de pruebas —un millón y medio de órdenes de dibujo— una pasada son unos
 * diez segundos, y eso vale igual si se pide la página entera que si se pide un sello: el
 * trabajo es recorrer la lista, no pintar píxeles. En un PDF corriente, en cambio, son
 * milisegundos.
 *
 * De ahí las tres reglas:
 *
 * - Se pide **con margen** ([MosaicoDePdf.MARGEN_DE_LA_LAMINA]), así apartar el dedo un poco
 *   no vuelve al PDF.
 * - La que hay **se estira mientras valga** ([MosaicoDePdf.HOLGURA_DE_LA_LAMINA]).
 * - **Al obrero no se le cancela.** Estuvo así en el mosaico y la lección vale igual aquí: si
 *   cada fotograma cancelara y volviera a empezar, con el dedo puesto no se terminaría
 *   ninguna. Se deja apuntado dónde está la vista, y al terminar una se mira si hace falta
 *   otra. Mientras tanto se sigue viendo el mosaico, que no es un hueco: es lo mismo más
 *   gordo.
 *
 * Quien decide cuándo pintarla es [Renderer]: encima de los cuadros, y solo cuando aporta.
 */
class LaminaDeCerca(
    private val ruta: String,
    private val pagina: Int,
    /** Lo que mide el papel en unidades del dibujo. */
    private val ancho: Double,
    private val alto: Double,
    private val ambito: CoroutineScope,
    /** Que alguien vuelva a pintar: la lámina ya está. */
    private val alLlegar: () -> Unit
) {

    /** Una lámina lista para pintar, con dónde va y cómo de fina es. */
    class Puesta(val bitmap: Bitmap, val donde: Bounds, val fineza: Double)

    private var hecha: MosaicoDePdf.Lamina? = null
    private var bitmap: Bitmap? = null

    /** Y la que el PDF no supo dar: no se le vuelve a pedir la misma. */
    private var fallada: MosaicoDePdf.Lamina? = null

    private var obrero: Job? = null
    private var soltada = false

    /**
     * **Se apunta lo que se está mirando y se pone al obrero si no estaba.**
     *
     * Lo llama quien pinta, o sea en cada fotograma: aquí no se puede hacer nada caro ni
     * lanzar nada dos veces. Si la lámina que hay vale para esta vista, no se hace nada.
     */
    @Synchronized
    fun aTrabajar(vista: Bounds, zoom: Double) {
        if (soltada || obrero?.isActive == true) return
        hecha?.let { if (MosaicoDePdf.valeLaLamina(it, vista, zoom)) return }
        val pedido = MosaicoDePdf.laminaPara(vista, ancho, alto, zoom) ?: return
        // La misma que ya está, o la misma que no se pudo: no se vuelve a pedir. Sin esto, una
        // lámina que el presupuesto deja más blanda de lo que pide el aumento nunca «valdría»
        // y se estaría rasterizando para siempre.
        if (pedido == hecha || pedido == fallada) return
        obrero = ambito.launch(Dispatchers.IO) { hacer(pedido) }
    }

    private fun hacer(pedido: MosaicoDePdf.Lamina) {
        val bmp = PdfDoc.lamina(
            ruta, pagina, pedido.donde, ancho, pedido.ancho, pedido.alto
        )
        // **A `RGB_565` en cuanto está.** `PdfRenderer` solo sabe dar `ARGB_8888` —cuatro
        // bytes por píxel— y una página no tiene transparencia: guardada ocupa la mitad, que
        // en una pantalla grande son ocho megas en vez de dieciséis.
        val ligera = bmp?.let { crudo ->
            runCatching { crudo.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
                .also { if (it != null && it !== crudo) crudo.recycle() }
        } ?: bmp
        synchronized(this) {
            if (soltada) {
                ligera?.recycle()
                return
            }
            if (ligera == null) {
                fallada = pedido
                return
            }
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = ligera
            hecha = pedido
        }
        alLlegar()
    }

    /**
     * **La lámina que haya**, si tapa algo de lo que se mira.
     *
     * Se devuelve aunque no valga del todo —se ha movido un poco, o se ha acercado más—
     * porque estirar la anterior un instante se ve mucho mejor que quitarla y volver de golpe
     * al mosaico. La buena llega en cuanto el obrero termina.
     */
    @Synchronized
    fun loQueHaya(vista: Bounds): Puesta? {
        if (soltada) return null
        val l = hecha ?: return null
        val b = bitmap?.takeIf { !it.isRecycled } ?: return null
        if (vista.x2 <= l.donde.x1 || vista.x1 >= l.donde.x2) return null
        if (vista.y2 <= l.donde.y1 || vista.y1 >= l.donde.y2) return null
        return Puesta(b, l.donde, l.fineza)
    }

    /** Se cierra el dibujo: fuera los megas del mapa de bits. */
    @Synchronized
    fun soltar() {
        soltada = true
        obrero?.cancel()
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = null
        hecha = null
    }
}
