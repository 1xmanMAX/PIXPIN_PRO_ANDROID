package com.forge.pixpin.motor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * **El plano partido en hojas pequeñas, todas hechas y todas guardadas.**
 *
 * Al abrir se rasteriza el papel entero por cuadros —empezando por donde se está mirando— y a
 * partir de ahí no se vuelve a tocar el archivo: acercarse, alejarse y pasear salen de lo que
 * ya está hecho.
 *
 * ## Cada hojita, comprimida y en el almacenamiento
 *
 * Un cuadro en crudo son medio mega (512 × 512 en `RGB_565`), así que el plano entero de un A0
 * a la resolución que pide serían casi trescientos megas: en memoria no cabía, había que ir
 * soltando cuadros y volver a ellos era volver al PDF. En **WEBP** ocupan del orden de diez
 * veces menos, y **se escriben en el teléfono como archivos normales**
 * ([CuadrosEnDisco]): de un plano ya extraído no se vuelve a tocar el PDF nunca, ni hoy ni la
 * semana que viene. Lo que se tiene en memoria —lo único que cuesta de verdad— es lo que se
 * está mirando y su contorno, descomprimido. Descomprimir un cuadro son unos milisegundos;
 * rasterizarlo del PDF, decenas.
 *
 * Es lo que pidió el usuario (4-sep-2026): «que las hojas pequeñas tengan un formato que sea
 * ligero y de alta calidad», y «una vez extraído y guardado, que se quede guardado en memoria
 * de almacenamiento como un archivo normal».
 *
 * ## Lo que se probó antes y por qué no valía
 *
 * Estuvo con **pirámide de niveles**, como las teselas de un mapa: se pedía el nivel que
 * pidiera el aumento y se rasterizaba al acercarse. Es lo correcto en un mapa porque las
 * teselas vienen de internet; aquí el archivo está en el teléfono y lo único que aportaba era
 * lo malo —al acercarse tocaba rasterizar otra vez, y se veía cargar por pedazos—. Con una
 * sola rejilla hecha entera al abrir, lo que se paga se paga una vez.
 *
 * Y es **la única capa de papel**: no hay una imagen de una pieza debajo tapando el hueco. Dos
 * capas de papel no se ven las dos —se ve la de encima— así que la de abajo solo servía para
 * que en algún momento se estuviera mirando la peor. La imagen de una pieza vuelve, ella
 * sola, cuando los cuadros no valen para la vista: ver [tapaLaVista] y [Renderer].
 *
 * ## Lo que cuesta
 *
 * La resolución la pide **el tamaño de verdad de la página**, no el del dibujo: así una cota
 * se lee igual de bien en un A4 que en un A0. Ver [MosaicoDePdf.pixelesPorUnidad].
 */
class ElMosaicoDelPapel(
    private val ruta: String,
    private val pagina: Int,
    /** Lo que mide el papel en unidades del dibujo. */
    private val ancho: Double,
    private val alto: Double,
    private val ambito: CoroutineScope,
    /** Dónde se guardan las hojitas ya extraídas. Suele ser la caché de la app. */
    private val guarderia: File,
    /** Cuánta memoria se le concede a los cuadros abiertos. Ver [MosaicoDePdf.presupuestoDe]. */
    private val presupuesto: Long = MosaicoDePdf.PRESUPUESTO,
    /** Que alguien vuelva a pintar: acaban de llegar cuadros. */
    private val alLlegar: () -> Unit
) {

    /** A cuántos píxeles por unidad se rasteriza este papel. */
    val fineza: Double = MosaicoDePdf.pixelesPorUnidad(ancho, alto)

    /** Lo que mide un cuadro en unidades del dibujo. */
    val lado: Double = MosaicoDePdf.ladoDelCuadro(fineza)

    /** Un cuadro descomprimido a una escala: a la mitad, a un cuarto… Ver [MosaicoDePdf.muestraPara]. */
    private data class Abierto(val cuadro: MosaicoDePdf.Cuadro, val muestra: Int)

    /** La rejilla de cuadros: cuántas columnas y cuántas filas. */
    private val rejilla = MosaicoDePdf.rejilla(ancho, alto, lado)

    /** Cuántos cuadros tiene el papel. */
    private val cuantos = rejilla.first * rejilla.second

    /**
     * **Cuántas filas de cuadros se rasterizan de una pasada.** Ver [PdfDoc.franjas]: lo caro
     * de un plano es recorrer sus órdenes de dibujo, así que se hacen pocas pasadas anchas.
     */
    private val filasPorFranja = MosaicoDePdf.filasPorFranja(rejilla.first)

    /**
     * **La carpeta de este plano**, con un archivo por cuadro. Va con la seña del PDF y la
     * resolución en el nombre, así que si el archivo cambia, la carpeta que le toca es otra.
     */
    private val carpeta = CuadrosEnDisco.carpetaDe(guarderia, File(ruta), pagina, fineza)

    /** Qué cuadros están ya escritos. Se lee la carpeta una vez, al abrir. */
    private val guardados: MutableSet<MosaicoDePdf.Cuadro> = CuadrosEnDisco.losQueHay(carpeta)

    /**
     * Los cuadros **abiertos**, listos para pintar, los de menos uso primero: se tienen los de
     * la vista y su contorno, y los de más allá se cierran. Volver a ellos es leer el archivo
     * y descomprimirlo, que son milisegundos, no rasterizar.
     *
     * El tope va **por lo que ocupan** y no por cuántos son, porque a un octavo caben
     * sesenta y cuatro veces más: mirando el plano entero se tienen todos sus cuadros
     * pequeños, y de cerca unos pocos grandes.
     */
    private val abiertos = LinkedHashMap<Abierto, Bitmap>(64, 0.75f, true)

    /** Lo que ocupan los abiertos, en bytes. */
    private var pesoAbierto = 0L

    /** Suelta los de menos uso hasta bajar del presupuesto. */
    private fun recortar() {
        val it = abiertos.entries.iterator()
        while (it.hasNext() && pesoAbierto > presupuesto && abiertos.size > 1) {
            val e = it.next()
            pesoAbierto -= pesoDe(e.value)
            if (!e.value.isRecycled) e.value.recycle()
            it.remove()
        }
    }

    private fun pesoDe(b: Bitmap): Long = b.width.toLong() * b.height * 2

    private var obrero: Job? = null
    private var soltado = false

    /** Por dónde se está mirando: el obrero lo relee para hacer eso primero. */
    private var mirando: Bounds = Bounds(0.0, 0.0, ancho, alto)

    /** Y a qué escala hace falta: mirando el plano entero, cuadros pequeños. */
    private var muestra: Int = MosaicoDePdf.MUESTRA_MAXIMA

    /**
     * **Si no se pudo hacer ni un cuadro.** Entonces quien pinta vuelve a la imagen de una
     * pieza: es peor de calidad, pero infinitamente mejor que una pantalla en blanco.
     */
    @get:Synchronized
    var fallo: Boolean = false
        private set

    /** **Si ya está el plano entero extraído.** A partir de ahí no se abre más el PDF. */
    @get:Synchronized
    val entero: Boolean get() = guardados.size >= cuantos

    /**
     * **Si ahora mismo hay cuadros para tapar [vista] entera.**
     *
     * Mientras no los hay —el plano se está haciendo, y una franja tarda unos segundos— quien
     * pinta pone debajo la imagen de una pieza: se ve con grano un rato, pero se ve. En cuanto
     * los cuadros tapan la vista, la imagen se quita y no vuelve. Ver [Renderer].
     */
    @Synchronized
    fun cubre(vista: Bounds): Boolean {
        if (soltado) return false
        for (c in MosaicoDePdf.losDe(vista, ancho, alto, lado)) {
            if (laQueSea(c, 1) == null) return false
        }
        return true
    }

    /** Un cuadro listo para pintar. */
    class Puesto(val bitmap: Bitmap, val donde: Bounds)

    /**
     * **Apunta por dónde se mira y pone al obrero si no estaba.**
     *
     * Lo llama quien pinta, o sea **en cada fotograma**. Estuvo cancelando al obrero y
     * poniendo otro cada vez que se le llamaba, y eso es una pescadilla: cada cuadro que
     * llegaba pedía repintar, el repintado cancelaba al obrero, el obrero empezaba de cero…
     * y no se terminaba ninguno. Como además ya no hay imagen de una pieza debajo, lo que se
     * veía era **una pantalla en blanco**. Aquí no se cancela nada: se deja apuntado dónde
     * está la vista y el obrero lo relee entre tanda y tanda.
     */
    @Synchronized
    fun aTrabajar(mirando: Bounds, zoom: Double) {
        this.mirando = mirando
        this.muestra = MosaicoDePdf.muestraPara(fineza, zoom)
        if (soltado || obrero?.isActive == true) return
        // **Y no se pone al obrero a dar una vuelta en balde.** Terminar una vuelta pide
        // repintar, y repintar llama aquí: si se lanzara sin nada que hacer, el dibujo se
        // estaría repintando solo para siempre. Ver [hayTrabajo].
        if (!hayTrabajo()) return
        obrero = ambito.launch(Dispatchers.IO) { trabajar() }
    }

    /** Si queda algo por rasterizar, o algo por abrir de lo que se está mirando. */
    private fun hayTrabajo(): Boolean {
        if (guardados.size < cuantos && !fallo) return true
        return MosaicoDePdf.losDeAlrededor(mirando, ancho, alto, lado)
            .any { Abierto(it, muestra) !in abiertos && it in guardados }
    }

    private suspend fun trabajar() {
        while (!soltado) {
            // Primero rasterizar lo que falte del plano —eso solo se hace una vez en la vida
            // del dibujo— y cuando ya está entero, abrir lo que haga falta para pintar.
            val hubo = if (!entero) rasterizarUnaFranja() else false
            val abrio = abrirUnaTanda()
            // Nada que hacer: se para. **Y sin avisar**, que avisar es pedir un repintado y el
            // repintado vuelve a llamar al obrero. Ver [aTrabajar].
            if (!hubo && !abrio) break
            if (!soltado) alLlegar()
            kotlinx.coroutines.yield()
        }
    }

    /**
     * **Rasteriza una franja entera del plano y la trocea en cuadros.**
     *
     * La franja se decide **ahora**, con la vista de ahora: la de lo que se está mirando. Se
     * hace así y no cuadro a cuadro porque en un plano de verdad lo caro no es pintar píxeles
     * sino recorrer sus órdenes de dibujo —ochocientas mil líneas en un A0 de arquitectura—, y
     * eso se paga **una vez por pasada**: con un cuadro por pasada eran ochocientas pasadas y
     * el plano no llegaba a hacerse nunca. Ver [PdfDoc.franjas].
     */
    private fun rasterizarUnaFranja(): Boolean {
        val (columnas, filas) = rejilla
        val cual = synchronized(this) {
            if (soltado || fallo) return false
            val faltan = MosaicoDePdf.todos(ancho, alto, lado).filter { it !in guardados }
            if (faltan.isEmpty()) return false
            MosaicoDePdf.franjaDe(
                MosaicoDePdf.porDondeEmpezar(faltan, mirando, lado).first(), filasPorFranja
            )
        }
        var llego = false
        PdfDoc.franjas(
            ruta, pagina,
            columnas * MosaicoDePdf.LADO_EN_PIXELES,
            filas * MosaicoDePdf.LADO_EN_PIXELES,
            ancho * fineza,
            filasPorFranja * MosaicoDePdf.LADO_EN_PIXELES,
            listOf(cual)
        ) { f, bmp ->
            val primera = f * filasPorFranja
            val ultima = minOf(primera + filasPorFranja, filas)
            for (fila in primera until ultima) {
                for (col in 0 until columnas) {
                    if (soltado) break
                    val c = MosaicoDePdf.Cuadro(col, fila)
                    if (synchronized(this) { c in guardados }) continue
                    val trozo = runCatching {
                        Bitmap.createBitmap(
                            bmp, col * MosaicoDePdf.LADO_EN_PIXELES,
                            (fila - primera) * MosaicoDePdf.LADO_EN_PIXELES,
                            MosaicoDePdf.LADO_EN_PIXELES, MosaicoDePdf.LADO_EN_PIXELES
                        )
                    }.getOrNull() ?: continue
                    val apretado = comprimir(trozo)
                    trozo.recycle()
                    if (apretado != null && CuadrosEnDisco.escribir(carpeta, c, apretado)) {
                        synchronized(this) { guardados += c }
                        llego = true
                    }
                }
            }
            bmp.recycle()
            !soltado
        }
        if (!llego) {
            // El PDF no sabe dar esta página: se deja dicho para que quien pinta vuelva a la
            // imagen de una pieza en vez de enseñar una hoja en blanco.
            synchronized(this) { fallo = true }
            return false
        }
        return true
    }

    /** Descomprime unos cuantos de los que hacen falta para pintar lo que se mira. */
    private fun abrirUnaTanda(): Boolean {
        val aQue = synchronized(this) { muestra }
        val tanda = synchronized(this) {
            if (soltado) return false
            MosaicoDePdf.porDondeEmpezar(
                MosaicoDePdf.losDeAlrededor(mirando, ancho, alto, lado)
                    .filter { Abierto(it, aQue) !in abiertos && it in guardados },
                mirando, lado
            ).take(POR_TANDA * aQue.coerceAtMost(8))
        }
        if (tanda.isEmpty()) return false
        for (c in tanda) {
            if (soltado) return true
            abrir(c, aQue)
        }
        return true
    }

    /** Lee un cuadro del almacenamiento, lo descomprime **a la escala pedida** y lo deja puesto. */
    private fun abrir(c: MosaicoDePdf.Cuadro, aQue: Int): Bitmap? {
        val apretado = CuadrosEnDisco.leer(carpeta, c) ?: return null
        val bmp = runCatching {
            BitmapFactory.decodeByteArray(apretado, 0, apretado.size, opciones(aQue))
        }.getOrNull() ?: return null
        synchronized(this) {
            if (soltado) {
                bmp.recycle()
                return null
            }
            abiertos[Abierto(c, aQue)] = bmp
            pesoAbierto += pesoDe(bmp)
            recortar()
        }
        return bmp
    }

    /**
     * **Los cuadros que tapan [vista]**, abriendo por el camino los pocos que falten.
     *
     * Se abren aquí como mucho [POR_FOTOGRAMA] —descomprimir es rápido, pero esto corre en el
     * hilo que pinta— y del resto se encarga el obrero, que avisa al terminar.
     */
    @Synchronized
    fun loQueHaya(vista: Bounds, zoom: Double): List<Puesto> {
        if (soltado) return emptyList()
        val aQue = MosaicoDePdf.muestraPara(fineza, zoom)
        val quiero = MosaicoDePdf.losDe(vista, ancho, alto, lado)
        val salida = ArrayList<Puesto>(quiero.size)
        var abiertosAhora = 0
        for (c in quiero) {
            var bmp = abiertos[Abierto(c, aQue)]
            // Si a esta escala aún no está, vale la que haya de ese mismo cuadro: se ve más
            // gorda o más fina un instante, pero **nunca un hueco**. La buena llega enseguida.
            if (bmp == null) bmp = laQueSea(c, aQue)
            if (bmp == null && abiertosAhora < POR_FOTOGRAMA) {
                bmp = abrir(c, aQue)
                if (bmp != null) abiertosAhora++
            }
            if (bmp != null && !bmp.isRecycled) salida += Puesto(bmp, MosaicoDePdf.donde(c, lado))
        }
        return salida
    }

    /** Cualquier escala que haya de este cuadro, empezando por la más parecida a la pedida. */
    private fun laQueSea(c: MosaicoDePdf.Cuadro, aQue: Int): Bitmap? {
        var m = 1
        var mejor: Bitmap? = null
        var distancia = Int.MAX_VALUE
        while (m <= MosaicoDePdf.MUESTRA_MAXIMA) {
            val b = abiertos[Abierto(c, m)]
            if (b != null && !b.isRecycled) {
                val d = Math.abs(Integer.numberOfTrailingZeros(m) - Integer.numberOfTrailingZeros(aQue))
                if (d < distancia) {
                    distancia = d
                    mejor = b
                }
            }
            m *= 2
        }
        return mejor
    }

    /**
     * **Se cierra el dibujo: fuera los cuadros abiertos.**
     *
     * Lo extraído **no** se borra: se queda escrito para que volver a abrir este mismo plano
     * no vuelva a pasar por el PDF. Lo que se suelta es lo caro de tener en memoria —los
     * cuadros descomprimidos—, que es medio mega cada uno.
     */
    @Synchronized
    fun soltar() {
        soltado = true
        obrero?.cancel()
        abiertos.values.forEach { if (!it.isRecycled) it.recycle() }
        abiertos.clear()
        pesoAbierto = 0
    }

    /** A WEBP, que en un plano —líneas sobre blanco— pesa una décima parte y no se nota. */
    private fun comprimir(bmp: Bitmap): ByteArray? = runCatching {
        val bolsa = ByteArrayOutputStream(64 * 1024)
        @Suppress("DEPRECATION")
        val formato =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.WEBP
        if (!bmp.compress(formato, MosaicoDePdf.CALIDAD_DEL_CUADRO, bolsa)) return null
        bolsa.toByteArray()
    }.getOrNull()

    init {
        // Este plano cuenta como usado ahora —decide a quién se borra antes— y de paso se mira
        // si de tanto plano guardado hay que hacer sitio. Ver [CuadrosEnDisco.limpiar].
        ambito.launch(Dispatchers.IO) {
            CuadrosEnDisco.tocar(carpeta)
            CuadrosEnDisco.limpiar(guarderia, carpeta)
        }
    }

    /** Los cuadros se abren en `RGB_565`: un papel no tiene transparencia y ocupa la mitad. */
    private fun opciones(aQue: Int) = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inSampleSize = aQue
    }

    private companion object {
        /**
         * Cuántos cuadros se rasterizan con el documento abierto antes de volver a mirar por
         * dónde anda la vista. Ocho tapan una pantalla; más sería seguir trabajando para una
         * vista que ya ha cambiado.
         */
        const val POR_TANDA = 8

        /** Y cuántos se descomprimen dentro del pintado, que es lo que no se puede atascar. */
        const val POR_FOTOGRAMA = 2
    }
}
