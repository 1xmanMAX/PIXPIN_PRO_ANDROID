package com.forge.pixpin.croquis3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.forge.pixpin.motor.Pt

/**
 * **El cubo de las vistas**: el mismo de cualquier programa de tres dimensiones.
 *
 * Hace dos cosas a la vez y por eso vale la pena el sitio que ocupa:
 *
 * - **Dice desde dónde se está mirando.** Girando en libertad es facilísimo acabar en una
 *   postura rara sin saber cómo se ha llegado; el cubo se gira con la vista y de un vistazo
 *   se ve qué cara se tiene delante y cuánto se está mirando desde arriba.
 * - **Y lleva a las vistas de siempre.** Se toca una cara y la cámara se pone de frente a
 *   ella: alzado, perfil, planta. Son las vistas en las que se mide y en las que se dibuja
 *   en serio, y buscarlas a pulso girando con el dedo es imposible.
 *
 * Al encajar en una cara se endereza también el balanceo: una vista técnica ladeada no es
 * una vista técnica.
 *
 * Se pinta en ortográfica aunque la cámara lleve la lente abierta. El cubo no es parte de
 * la escena, es un instrumento: tiene que decir la orientación y nada más, y un cubo
 * deformado por un ojo de pez cuenta peor la orientación que uno recto.
 */
@Composable
fun Croquis3DCubo(controlador: Croquis3DControlador, modifier: Modifier = Modifier) {
    val hayEleccion = controlador.seleccion.isNotEmpty()

    /**
     * **Con algo elegido, el cubo cuenta la postura de la figura y no la de la vista.**
     *
     * Volteando en libertad se pierde de vista cuál era el frente a los tres arrastres, y
     * un cubo que no se mueve mientras la figura da vueltas no sirve de nada: dice desde
     * dónde se mira, que es lo único que no está cambiando. Sumándole lo volteado, el cubo
     * gira con el dedo y de un vistazo se ve **cómo ha quedado puesta la pieza**.
     *
     * **Es una función y no un valor leído aquí arriba, y eso es lo que hace que girar la
     * vista vaya suave.** Leyendo la cámara al componer, el cubo se volvía a montar entero
     * en cada fotograma de cualquier giro — y con él, sus dos atendedores de toques, que se
     * armaban y se tiraban sesenta veces por segundo llevándose por delante los gestos a
     * medias. Preguntada dentro del pintado, lo que se vuelve a hacer es el pintado del
     * cubo; y preguntada dentro del gesto, se contesta con lo que haya en ese momento.
     */
    fun postura(): Camara3D {
        val camara = controlador.camara
        if (!hayEleccion) return camara
        val vuelta = controlador.vueltaDeLoElegido
        return camara.copy(
            giro = camara.giro + vuelta.giro,
            // Sin recortar al cenit a propósito: la figura se puede voltear del todo, y el
            // cubo tiene que poder darse la vuelta con ella o dejaría de contar la verdad.
            inclinacion = camara.inclinacion + vuelta.inclinacion
        )
    }
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    val tinta = MaterialTheme.colorScheme.onSurfaceVariant
    val realce = MaterialTheme.colorScheme.primary

    Canvas(
        modifier
            .size(LADO.dp)
            // Armado una sola vez: ver [postura]. La postura se pregunta al tocar, que es
            // cuando hace falta y cuando ya se sabe cuál es.
            .pointerInput(Unit) {
                detectTapGestures { donde ->
                    val caras = carasALaVista(postura(), size.width.toDouble())
                    val tocada = caras.firstOrNull { dentroDe(it.esquinas, donde) }
                    if (tocada != null) controlador.pedirVista(tocada.cara.postura)
                }
            }
            // **Y se arrastra. Lo que gira depende de si hay algo elegido.**
            //
            // Sin nada elegido gira la vista, con la misma cuenta y la misma sensibilidad
            // que un dedo sobre el dibujo: el cubo no es solo un cartel que dice desde
            // dónde se mira, es por donde se agarra la vista sin taparla con la mano.
            //
            // **Con algo elegido gira lo elegido**, en libertad y alrededor de los ejes de
            // la pantalla. Es la mejor manera de voltear una pieza que hay: se mira la
            // figura y se la va poniendo como se quiere, sin elegir eje, sin pensar en
            // cuál es cuál y sin que haya ninguna postura a la que no se llegue. Y se
            // hace **aquí**, en una esquina, y no encima del dibujo: la mano no tapa
            // justo lo que se está colocando. Ver [Croquis3DControlador.girarLaSeleccionConLaMano].
            .pointerInput(hayEleccion) {
                detectDragGestures { cambio, arrastre ->
                    cambio.consume()
                    if (hayEleccion) {
                        controlador.girarLaSeleccionConLaMano(
                            arrastre.x.toDouble(), arrastre.y.toDouble()
                        )
                    } else {
                        controlador.orbitar(
                            arrastre.x / VUELTA_ENTERA * Math.PI * 2,
                            arrastre.y / VUELTA_ENTERA * Math.PI * 2,
                            1.0
                        )
                    }
                }
            }
    ) {
        val lado = size.minDimension.toDouble()
        // Con algo elegido, el cubo se pone del color de lo elegido: el mismo cubo hace dos
        // cosas distintas según lo que haya, y hay que poder verlo antes de arrastrarlo.
        if (hayEleccion) {
            drawCircle(
                realce.copy(alpha = 0.16f),
                radius = (lado / 2).toFloat(),
                center = center
            )
        }
        for (pintada in carasALaVista(postura(), lado)) {
            val camino = ElCaminoDeLaCara.also { it.rewind() }
            pintada.esquinas.forEachIndexed { i, e ->
                if (i == 0) camino.moveTo(e.x.toFloat(), e.y.toFloat())
                else camino.lineTo(e.x.toFloat(), e.y.toFloat())
            }
            camino.close()
            // Cuanto más de frente se ve una cara, más maciza: es lo que hace que el cubo
            // se lea como un cubo y no como seis rombos sueltos.
            drawPath(camino, fondo.copy(alpha = (0.55f + 0.4f * pintada.deFrente).coerceIn(0f, 0.95f)))
            drawPath(
                camino,
                if (hayEleccion) realce.copy(alpha = 0.75f) else tinta.copy(alpha = 0.45f),
                style = Stroke(width = 1.2f)
            )
            // El nombre solo en la cara que se ve bien de frente; en las escorzadas no cabe
            // y llenaría el cubo de letras ilegibles.
            if (pintada.deFrente > 0.55f) {
                rotulo(pintada, if (pintada.deFrente > 0.9f) realce else tinta)
            }
        }
    }
}

private fun DrawScope.rotulo(pintada: CaraPintada, color: Color) {
    val centro = pintada.esquinas.fold(Pt(0.0, 0.0)) { a, e -> Pt(a.x + e.x / 4, a.y + e.y / 4) }
    drawContext.canvas.nativeCanvas.apply {
        // Reaprovechado: un `Paint` lleva memoria de las de Android por debajo, y esto se
        // pinta tres veces por fotograma en cada giro. Ver [ElPincelDeLosRotulos].
        val pincel = ElPincelDeLosRotulos
        pincel.textSize = size.minDimension * 0.13f
        pincel.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        drawText(
            pintada.cara.nombre,
            centro.x.toFloat(),
            (centro.y + pincel.textSize * 0.35f).toFloat(),
            pincel
        )
    }
}

/**
 * El pincel de los rótulos del cubo y el camino de sus caras, hechos una vez.
 *
 * Por lo mismo que los cuadernos del lienzo: el cubo se repinta en cada fotograma de
 * cualquier giro, y fabricar un `Paint` y seis `Path` por fotograma no se ve como memoria
 * gastada — se ve como el tirón al girar. Se usan y se mandan a pintar en el acto, en un
 * solo hilo, así que uno de cada basta.
 */
private val ElPincelDeLosRotulos by lazy {
    android.graphics.Paint().also {
        it.isAntiAlias = true
        it.textAlign = android.graphics.Paint.Align.CENTER
    }
}

private val ElCaminoDeLaCara by lazy { Path() }

/** Una cara del cubo ya proyectada, con lo de frente que se ve. */
private class CaraPintada(val cara: CaraDelCubo, val esquinas: List<Pt>, val deFrente: Float)

/**
 * Las caras que dan a la cámara, ya proyectadas.
 *
 * Se descartan las de atrás por su normal: en un cubo, con eso basta y no hace falta
 * ordenar nada por profundidad — las que quedan no se tapan entre sí.
 */
private fun carasALaVista(camara: Camara3D, lado: Double): List<CaraPintada> {
    val mira = Camara3D(
        giro = camara.giro,
        inclinacion = camara.inclinacion,
        zoom = lado * RADIO,
        balanceo = camara.balanceo
    )
    val adelante = mira.adelante
    return CaraDelCubo.entries.mapNotNull { cara ->
        val deFrente = -escalar(cara.normal, adelante)
        if (deFrente <= 1e-3) return@mapNotNull null
        CaraPintada(
            cara,
            cara.esquinas().map { mira.aPantalla(it, lado, lado) },
            deFrente.toFloat()
        )
    }
}

/** Si un punto cae dentro del cuadrilátero, por el signo de los productos cruzados. */
private fun dentroDe(esquinas: List<Pt>, p: Offset): Boolean {
    var positivos = 0
    var negativos = 0
    for (i in esquinas.indices) {
        val a = esquinas[i]
        val b = esquinas[(i + 1) % esquinas.size]
        val cruz = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        if (cruz > 0) positivos++ else if (cruz < 0) negativos++
    }
    return positivos == 0 || negativos == 0
}

/** El lado del cubo en `dp`, y cuánto ocupa dentro de su cuadro. */
private const val LADO = 74
private const val RADIO = 0.20
