package com.forge.pixpin.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Coger una hoja con dos dedos y **sacarla de su sitio** para verla en grande.
 *
 * Es el gesto de las fotos de Telegram: la hoja se despega de la fila, crece
 * bajo los dedos hasta llenar la pantalla, se puede girar y mover mientras se
 * sujeta, y **al soltar vuelve sola a su hueco**. No hay que abrir nada ni que
 * volver de ningún sitio, que es lo que lo hace servir para mirar diez páginas
 * seguidas: mientras el gesto dura, la lista sigue debajo tal como estaba.
 *
 * ## Cómo se paga tan poco
 *
 * - **Lo que ya estaba pintado.** El gesto empieza con la misma miniatura que
 *   la fila ya tenía en memoria: no se abre el PDF ni se dibuja nada para
 *   arrancar, así que la hoja salta al instante. La versión nítida se pide
 *   aparte y entra cuando llega, si es que da tiempo.
 * - **Solo la GPU.** Crecer, girar y desplazarse son propiedades de una capa
 *   [graphicsLayer], y el velo se pinta en un `drawBehind`. Ninguna de las dos
 *   cosas recompone: se leen dentro de la lambda, o sea en el momento de
 *   dibujar. El gesto entero no compone ni un solo nodo nuevo por fotograma.
 * - **En una ventana suelta.** Va en un [Popup], que es lo que le permite
 *   salirse de la fila —una lista recorta lo que se sale de ella— sin tener que
 *   subir el estado hasta la raíz de la pantalla ni envolver nada.
 */
@Stable
class ZoomDeHoja {

    companion object {
        /**
         * Cuánto más allá de llenar la pantalla se deja ampliar.
         *
         * Cuatro veces es lo que hace falta para leer un rótulo de plano o una cifra
         * escrita a mano encima de una página. Más que eso ya son píxeles inventados
         * incluso con la versión nítida delante.
         */
        const val VECES_MAS = 4f

        /**
         * Lo que se tarda en pedir la versión nítida desde que bajan los dos dedos.
         *
         * No es un adorno: ver [momentoDeAfinar]. Un quinto de segundo es lo que dura
         * separar los dedos lo justo para que la hoja despegue, y en ese rato lo que se
         * ve es la miniatura a escala 1, o sea exactamente lo que ya había en la fila.
         */
        private const val ESPERA_DE_LA_NITIDA = 200L
    }


    /** Lo que se está mirando. Nulo mientras no hay nada cogido. */
    internal var mapa by mutableStateOf<ImageBitmap?>(null)
        private set

    /** El hueco del que salió, en coordenadas de la ventana. */
    internal var origen by mutableStateOf(Rect.Zero)
        private set

    /** Si hay algo puesto encima ahora mismo. */
    var visible by mutableStateOf(false)
        private set

    // Los tres valores vivos del gesto. Se leen **solo dentro de lambdas de
    // dibujo**, así que cambiarlos redibuja pero no recompone.
    internal var escala by mutableFloatStateOf(1f)
        private set
    internal var giro by mutableFloatStateOf(0f)
        private set
    internal var desplazamiento by mutableStateOf(Offset.Zero)
        private set

    /** Hasta dónde puede crecer: lo justo para llenar la pantalla. */
    private var techo = 1f

    /** Mientras vuelve a su sitio no se coge otra vez, o se quedaría a medias. */
    private var volviendo = false

    /**
     * Coge una hoja. Devuelve si se ha cogido de verdad.
     *
     * @param hueco dónde está la hoja en la ventana, que es a donde volverá.
     * @param pantalla lo que mide la ventana, para saber cuánto puede crecer.
     */
    fun coger(imagen: ImageBitmap, hueco: Rect, pantalla: IntSize): Boolean {
        if (volviendo || hueco.width <= 0f || hueco.height <= 0f) return false
        mapa = imagen
        origen = hueco
        escala = 1f
        giro = 0f
        desplazamiento = Offset.Zero
        // **El tope no es llenar la pantalla: es bastante más.**
        //
        // Con el tope en «que quepa justo», ampliar una miniatura de sesenta puntos daba
        // una imagen del tamaño de la pantalla y ahí se plantaba — que para leer la letra
        // pequeña de un plano o comprobar una cifra anotada se queda corto, que es
        // justo para lo que uno amplía. Ahora se puede seguir hasta [VECES_MAS], y la
        // versión nítida que pide quien la sujeta (ver [afinar]) es la que hace que al
        // llegar ahí se vea de verdad en vez de verse el mismo sello estirado.
        techo = (min(pantalla.width / hueco.width, pantalla.height / hueco.height) * VECES_MAS)
            .coerceAtLeast(VECES_MAS)
        visible = true
        return true
    }

    /** La versión nítida, ya dibujada, para la hoja que se está sujetando. */
    fun afinar(imagen: ImageBitmap) {
        if (visible) mapa = imagen
    }

    /**
     * Espera al momento de ponerse a componer la nítida. Devuelve si aún se sujeta.
     *
     * **El pellizco empezaba con un tirón.** Componer la versión grande —descodificar
     * una foto de dos mil puntos, rasterizar una página de PDF, volver a pintar un
     * dibujo entero al doble— son decenas de milisegundos de procesador y hasta treinta
     * megas de memoria que había que reservar. Iba fuera del hilo de la pantalla, sí,
     * pero arrancaba **en el mismo fotograma** en que baja el segundo dedo: justo cuando
     * la hoja tiene que despegar, el móvil estaba ocupado en otra cosa y el arranque del
     * gesto se notaba a trompicones.
     *
     * Ese rato no hay nada que ganar componiendo: a escala 1 lo que se ve es la misma
     * miniatura que ya estaba en la fila, y hasta que uno no ha separado los dedos de
     * verdad no hay nitidez que echar en falta. Así que se deja pasar
     * [ESPERA_DE_LA_NITIDA] y solo entonces se compone —y no se compone en absoluto si
     * para entonces ya se ha soltado, que es lo que pasa cuando el pellizco fue un
     * resbalón.
     */
    suspend fun momentoDeAfinar(): Boolean {
        delay(ESPERA_DE_LA_NITIDA)
        return visible
    }

    /**
     * Lo que los dedos acaban de hacer, **anclado al punto entre ellos**.
     *
     * ## Por qué el punto importa
     *
     * Antes se escalaba desde el centro de la hoja: al ampliar una esquina, esa esquina
     * se iba de la pantalla y había que perseguirla arrastrando. La imagen crecía, sí,
     * pero no debajo de los dedos — y ampliar sirve para mirar **algo concreto**, no para
     * hacer grande la hoja en abstracto.
     *
     * La cuenta es la de siempre: el punto que está bajo los dedos tiene que seguir
     * estando bajo los dedos después de escalar. Si un punto se pinta en
     * `centro + p·escala + desplazamiento`, dejarlo quieto al pasar de una escala a otra
     * obliga a mover el desplazamiento en `p·(escala vieja − escala nueva)`.
     *
     * Los tres valores son **de este paso**, no acumulados desde que se cogió: acumular
     * fuera y aplicar aquí obligaba a rehacer la cuenta del ancla con totales, y con el
     * tope de por medio los totales dejan de corresponderse con lo que se ve.
     */
    fun mover(zoomPaso: Float, panPaso: Offset, giroPaso: Float, foco: Offset) {
        if (!visible) return
        // Por debajo de 1 no se baja: sería encogerla dentro de su propio hueco.
        val nueva = (escala * zoomPaso).coerceIn(1f, techo)
        val centro = Offset(origen.width / 2f, origen.height / 2f)
        // Dónde cae el punto de los dedos en la hoja sin transformar. `foco` viene en
        // coordenadas del elemento, que es donde lo da el gesto.
        val enLaHoja = (foco - centro - desplazamiento) / escala
        desplazamiento += enLaHoja * (escala - nueva) + panPaso
        escala = nueva
        giro += giroPaso
    }

    /**
     * Se suelta y **vuelve a su hueco**, animada.
     *
     * Volver sola es lo que hace que el gesto no sea un modo: no se ha entrado
     * en ninguna pantalla, así que no hay nada que cerrar. Se interpola hacia la
     * identidad —que es exactamente el hueco de partida— y al llegar se quita.
     */
    suspend fun soltar() {
        if (!visible) return
        volviendo = true
        val e = escala
        val d = desplazamiento
        val g = giro
        animate(1f, 0f, animationSpec = tween(200, easing = FastOutSlowInEasing)) { t, _ ->
            escala = 1f + (e - 1f) * t
            desplazamiento = d * t
            giro = g * t
        }
        visible = false
        // Se suelta la imagen: la grande puede ocupar varios megas y la caché ya
        // la guarda por su cuenta si hace falta otra vez.
        mapa = null
        volviendo = false
    }
}

/** Uno por pantalla: solo se puede sujetar una hoja a la vez. */
@Composable
fun recordarZoomDeHoja(): ZoomDeHoja = remember { ZoomDeHoja() }

/**
 * El gesto: dos dedos sobre algo lo despegan y lo amplían.
 *
 * **Con dos y no con uno** a propósito: con uno se desplaza la fila, que es lo
 * que se hace el 90 % de las veces. Hasta que no baja el segundo dedo no se
 * toca nada, así que hojear sigue funcionando exactamente igual que antes.
 */
@Composable
fun Modifier.pinzaParaAmpliar(
    /** Coge la hoja; si devuelve false, el gesto se deja pasar tal cual. */
    alCoger: () -> Boolean,
    alMover: (zoom: Float, pan: Offset, giro: Float, foco: Offset) -> Unit,
    alSoltar: () -> Unit
): Modifier {
    // **Las tres funciones se leen frescas, y esto era un fallo de verdad.**
    //
    // `pointerInput(Unit)` no se reinicia nunca, así que se quedaba con las funciones de
    // la **primera** composición. En la conversación eso se veía clarísimo: una foto
    // anotada se enseña primero como estaba y la versión con lo dibujado llega un momento
    // después, desde el hilo de disco — y al ampliarla con dos dedos salía la de antes,
    // sin nada encima. Parecía que el zoom borraba lo dibujado.
    //
    // Con `rememberUpdatedState` el gesto sigue sin reiniciarse —reiniciarlo a mitad de un
    // pellizco lo cortaría— pero llama siempre a la última versión de cada función.
    val coger by rememberUpdatedState(alCoger)
    val mover by rememberUpdatedState(alMover)
    val soltar by rememberUpdatedState(alSoltar)
    return pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var cogida = false
        while (true) {
            val evento = awaitPointerEvent()
            val dedos = evento.changes.count { it.pressed }
            if (dedos == 0) break
            if (!cogida && dedos >= 2) {
                cogida = coger()
                // Si no había nada que ampliar —la miniatura aún no está—, se
                // abandona el gesto en vez de comérselo: la lista se sigue
                // desplazando como si esto no existiera.
                if (!cogida) break
            }
            if (cogida) {
                // **Pasos, no totales**, y con el punto entre los dedos: es lo que
                // permite anclar el zoom ahí en vez de al centro. Ver [ZoomDeHoja.mover].
                mover(
                    evento.calculateZoom(),
                    evento.calculatePan(),
                    evento.calculateRotation(),
                    evento.calculateCentroid(useCurrent = false)
                )
                // Consumido para que la fila no se desplace a la vez: sin esto
                // la hoja crece bajo los dedos mientras la lista corre debajo.
                evento.changes.forEach { if (it.pressed) it.consume() }
            }
        }
        if (cogida) soltar()
    }
    }
}

/**
 * La hoja sujetada, encima de todo.
 *
 * Se pone una vez por pantalla y no hace nada mientras no hay nada cogido.
 */
@Composable
fun CapaDeAmpliacion(estado: ZoomDeHoja) {
    if (!estado.visible) return
    val imagen = estado.mapa ?: return
    val densidad = LocalDensity.current
    Popup(
        popupPositionProvider = EN_LA_VENTANA,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            // Sin esto la ventana emergente recorta a su tamaño medido y la
            // hoja no podría pasar de donde nació, que es todo el gesto.
            clippingEnabled = false
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            // El velo se oscurece con lo que se ha crecido: es lo que separa la
            // hoja del resto sin que haya que entrar en ninguna pantalla.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            Color.Black,
                            alpha = ((estado.escala - 1f) / 2f).coerceIn(0f, 0.72f)
                        )
                    }
            )
            Image(
                bitmap = imagen,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            estado.origen.left.roundToInt(),
                            estado.origen.top.roundToInt()
                        )
                    }
                    .size(
                        with(densidad) { estado.origen.width.toDp() },
                        with(densidad) { estado.origen.height.toDp() }
                    )
                    .graphicsLayer {
                        scaleX = estado.escala
                        scaleY = estado.escala
                        rotationZ = estado.giro
                        translationX = estado.desplazamiento.x
                        translationY = estado.desplazamiento.y
                    }
            )
        }
    }
}

/**
 * La ventana emergente, pegada a la esquina de la pantalla.
 *
 * Así sus coordenadas son las mismas que las de `boundsInWindow`, que es de
 * donde sale el hueco de la hoja. Colocándola junto a quien la abre habría que
 * andar restando la posición de la fila, y esa resta se equivoca en cuanto algo
 * se desplaza.
 */
private val EN_LA_VENTANA = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = IntOffset.Zero
}
