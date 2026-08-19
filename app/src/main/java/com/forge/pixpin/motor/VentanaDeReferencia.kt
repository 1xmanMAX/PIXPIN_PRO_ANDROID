package com.forge.pixpin.motor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * La imagen que se está copiando, **flotando encima del dibujo**.
 *
 * Dibujar mirando una referencia en otra aplicación es cambiar de aplicación
 * cada treinta segundos, y en cada vuelta se pierde el sitio por el que ibas.
 * Teniéndola encima, en una ventanita que se aparta a donde no estorbe, no hay
 * que salir del lienzo en toda la faena.
 *
 * ## Los dos modos, y el toque que los cambia
 *
 * La misma ventana sirve para dos cosas que no se pueden hacer a la vez, porque
 * las dos son «arrastrar dentro del recuadro»:
 *
 * - **Con la cabecera puesta** se coloca la ventana: se mueve por el asa y se
 *   estira por la esquina de abajo a la derecha, que es donde la busca la mano.
 * - **Tocando la imagen la cabecera se va**, y a partir de ahí lo que se mueve,
 *   se amplía y se gira es **la imagen dentro de su marco**.
 *
 * Otro toque la devuelve.
 *
 * ## La cabecera va encima, no arriba
 *
 * Superpuesta sobre la propia imagen y no en una fila propia, y esto importa: en
 * una fila, quitarla **encogía la ventana** —la imagen cambiaba de tamaño al
 * entrar y salir del modo, y lo que estabas mirando se movía—. Encima, aparecer
 * y desaparecer no cambia nada de sitio; solo tapa una franja de dos dedos
 * mientras está.
 */
@Composable
fun VentanaDeReferencia(
    imagen: Bitmap,
    /** Traer otra imagen: limpia la de ahora y abre el carrete. */
    onOtraImagen: () -> Unit,
    onCerrar: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Dónde está la ventana y lo que mide. En píxeles y con `offset { }`: se
    // aplica al colocar, sin recomponer, que es lo que hace que arrastrar vaya
    // suelto.
    // **Dónde nace.** Anclada arriba a la izquierda, se coloca con su propio
    // desplazamiento; sin este empujón inicial saldría metida bajo la barra.
    val densidad = androidx.compose.ui.platform.LocalDensity.current
    var x by remember { mutableFloatStateOf(with(densidad) { SITIO_INICIAL_X.toPx() }) }
    var y by remember { mutableFloatStateOf(with(densidad) { SITIO_INICIAL_Y.toPx() }) }
    var ancho by remember { mutableStateOf(LADO_INICIAL) }
    var alto by remember { mutableStateOf(LADO_INICIAL) }

    // El encuadre de la imagen **dentro** del marco.
    var zoom by remember { mutableFloatStateOf(1f) }
    var giro by remember { mutableFloatStateOf(0f) }
    var ix by remember { mutableFloatStateOf(0f) }
    var iy by remember { mutableFloatStateOf(0f) }

    /** Con la cabecera puesta se coloca la ventana; sin ella, la imagen. */
    var conBarra by remember { mutableStateOf(true) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 10.dp,
        modifier = modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
    ) {
        Box(Modifier.size(width = ancho, height = alto)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    // El toque cambia de modo. Va **antes** que el resto para que
                    // un toque limpio no se lea como un arrastre de cero.
                    .pointerInput(Unit) { detectTapGestures { conBarra = !conBarra } }
                    // Y el encuadre solo se toca con la cabecera quitada: con
                    // ella puesta, arrastrar aquí sería mover dos cosas a la vez.
                    .pointerInput(conBarra) {
                        if (conBarra) return@pointerInput
                        // Los tres a la vez, que es como se manejan las fotos en
                        // cualquier sitio: dos dedos abren, giran y arrastran sin
                        // tener que elegir cuál de las tres cosas se está haciendo.
                        detectTransformGestures { _, arrastre, escala, vuelta ->
                            zoom = (zoom * escala).coerceIn(MENOS_ZOOM, MAS_ZOOM)
                            giro += vuelta
                            ix += arrastre.x
                            iy += arrastre.y
                        }
                    }
            ) {
                Image(
                    bitmap = imagen.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            rotationZ = giro
                            translationX = ix
                            translationY = iy
                        }
                )
            }

            if (conBarra) {
                // **Encima de la imagen**, con un velo detrás: sobre una foto
                // clara los iconos blancos no se verían, y sobre una oscura
                // tampoco los negros. El velo resuelve las dos.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .size(width = ancho, height = ALTO_DE_LA_CABECERA)
                        .background(VELO_DE_LA_BARRA)
                ) {
                    BarraDeVentana(
                        onArrastrar = { dx, dy -> x += dx; y += dy },
                        onAmpliar = {
                            // Ampliar es un ida y vuelta: se agranda para mirar
                            // un detalle y se vuelve al tamaño de trabajo.
                            val grande = ancho >= LADO_AMPLIADO
                            ancho = if (grande) LADO_INICIAL else LADO_AMPLIADO
                            alto = if (grande) LADO_INICIAL else LADO_AMPLIADO
                        },
                        onCerrar = onCerrar,
                        tinta = Color.White,
                        extras = {
                            BotonDeLaBarra(
                                Icons.Filled.Refresh,
                                "Poner otra imagen",
                                onOtraImagen
                            )
                        }
                    )
                }

                // **La esquina de estirar, donde la busca la mano.** Abajo a la
                // derecha, como cualquier ventana; y solo con la cabecera
                // puesta, que es el modo de colocar.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(ESQUINA)
                        .pointerInput(Unit) {
                            detectDragGestures { cambio, arrastre ->
                                cambio.consume()
                                ancho = (ancho + arrastre.x.toDp()).coerceIn(MINIMO, MAXIMO)
                                alto = (alto + arrastre.y.toDp()).coerceIn(MINIMO, MAXIMO)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.OpenInFull,
                        contentDescription = "Redimensionar la referencia",
                        tint = Color.White,
                        modifier = Modifier
                            .size(ESQUINA)
                            .background(VELO_DE_LA_BARRA, RoundedCornerShape(topStart = 10.dp))
                            .padding(5.dp)
                    )
                }
            }
        }
    }
}

/** Un botón más de la cabecera, del mismo tamaño que los de dentro. */
@Composable
private fun BotonDeLaBarra(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    descripcion: String,
    alTocar: () -> Unit
) {
    Box(
        Modifier
            .size(ALTO_DE_LA_CABECERA)
            .pointerInput(alTocar) { detectTapGestures { alTocar() } },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icono,
            contentDescription = descripcion,
            tint = Color.White,
            modifier = Modifier.size(15.dp)
        )
    }
}

private val LADO_INICIAL: Dp = 180.dp

/**
 * Dónde aparece la primera vez.
 *
 * A la derecha y a media altura: es donde menos estorba a una mano diestra y
 * donde no la tapa ni la barra de arriba ni el panel del lateral. A partir de
 * ahí se arrastra a donde haga falta.
 */
private val SITIO_INICIAL_X: Dp = 180.dp
private val SITIO_INICIAL_Y: Dp = 140.dp
private val LADO_AMPLIADO: Dp = 340.dp
private val MINIMO: Dp = 90.dp
private val MAXIMO: Dp = 460.dp

/** El blanco de la esquina de estirar: lo justo para acertarla con el dedo. */
private val ESQUINA: Dp = 26.dp

/** El velo de detrás de la cabecera, para que los iconos se lean sobre la foto. */
private val VELO_DE_LA_BARRA = Color(0x7A000000)

private const val MENOS_ZOOM = 0.4f
private const val MAS_ZOOM = 8f
