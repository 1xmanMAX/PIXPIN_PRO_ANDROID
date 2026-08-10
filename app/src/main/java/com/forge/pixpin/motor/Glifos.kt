package com.forge.pixpin.motor

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.ceil
import kotlin.math.max

/**
 * El **perfil de las letras**: una palabra convertida en su silueta.
 *
 * Lo necesitan las dos salidas vectoriales, y por el mismo motivo: un archivo
 * que viaja solo no puede decir «esto va en Excalifont», porque el ordenador que
 * lo abra no la tiene y sustituirá la fuente — cambian los anchos, el texto se
 * sale de su sitio y el dibujo deja de coincidir consigo mismo. En curvas se ve
 * idéntico en cualquier parte.
 *
 * Vivía dentro del exportador de SVG. Se saca aquí porque el de PDF necesita
 * exactamente lo mismo, y dos copias de esto es lo último que conviene tener por
 * duplicado: es donde más fácil sería que una salida y la otra empezaran a
 * escribir las letras distinto sin que nadie lo notara.
 *
 * ## Cómo se saca
 *
 * `getTextPath` da la silueta —respetando la fuente, el tamaño y la alineación,
 * igual que `drawText`— y [PathMeasure] la recorre **un contorno cada vez**, que
 * es lo que hace falta para que el hueco de una «o» siga siendo un hueco. Se
 * muestrea denso y luego se simplifica: sale mucho más ligero que muestrear
 * justo, y sin decidir de antemano dónde hace falta detalle.
 *
 * No se usa `Path.approximate`, que haría lo mismo de una vez: no marca dónde
 * acaba un contorno y empieza el siguiente, así que los huecos de las letras se
 * unirían con el trazo de fuera y una «o» saldría como una mancha.
 */
object Glifos {

    /**
     * Cada cuántos píxeles se toma un punto al seguir el perfil.
     *
     * Es el paso del muestreo, no el resultado: después pasa por
     * [douglasPeucker], que tira todo lo que queda en línea recta. En los palos
     * de una «l» sobrevive un punto por esquina y en la panza de una «o» los que
     * hagan falta, que es exactamente el reparto que uno querría hacer a mano.
     */
    const val PASO = 0.7

    /** Cuánto se le permite desviarse al perfil simplificado, en píxeles. */
    const val TOLERANCIA = 0.12

    /**
     * El perfil de [texto], escrito con [pincel] en [x], [y].
     *
     * El pincel llega ya configurado —fuente, tamaño, alineación, negrita— para
     * que quien llame no tenga que repetir aquí la configuración que ya hizo
     * para medir. Se devuelve una lista de contornos cerrados, en coordenadas
     * de escena.
     */
    fun perfilDe(pincel: Paint, texto: String, x: Double, y: Double): List<List<Pt>> {
        if (texto.isEmpty()) return emptyList()
        val camino = Path()
        pincel.getTextPath(texto, 0, texto.length, x.toFloat(), y.toFloat(), camino)
        if (camino.isEmpty) return emptyList()

        val contornos = ArrayList<List<Pt>>()
        val medida = PathMeasure(camino, false)
        val pos = FloatArray(2)
        do {
            val largo = medida.length
            if (largo > 0f) {
                val pasos = max(3, ceil(largo / PASO).toInt())
                val puntos = ArrayList<Pt>(pasos + 1)
                for (i in 0..pasos) {
                    val d = largo * i / pasos
                    if (medida.getPosTan(d, pos, null)) {
                        puntos.add(Pt(pos[0].toDouble(), pos[1].toDouble()))
                    }
                }
                if (puntos.size >= 3) contornos.add(douglasPeucker(puntos, TOLERANCIA))
            }
        } while (medida.nextContour())
        return contornos
    }
}
