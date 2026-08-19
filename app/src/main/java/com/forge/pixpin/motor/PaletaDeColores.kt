package com.forge.pixpin.motor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * La paleta: **colores que pegan entre sí, y todos a la vista**.
 *
 * Elegir colores de uno en uno de una lista plana sale mal casi siempre: quedan
 * siete tonos que no tienen nada que ver y el dibujo se ve sucio sin que se sepa
 * por qué. Aquí se eligen **por paletas**: cada recuadro es una combinación
 * pensada entera, y cojas el tono que cojas va a pegar con los otros de su
 * recuadro.
 *
 * ## Densa a propósito
 *
 * Los tonos van **pegados**, sin aire entre ellos, y cada paleta ocupa
 * exactamente su recuadro. Con muestras separadas y redondas cabían seis
 * colores por pantalla y había que desplazar para ver el resto — que es lo
 * contrario de lo que hace falta cuando lo que se busca es comparar tonos. Así
 * caben doce paletas de ocho tonos de un vistazo, y el recuadro entero se lee
 * como una muestra de la combinación.
 *
 * ## Dos tintas, y solo las que aplican
 *
 * Arriba se elige si lo que se está tocando es **la tinta del lápiz o la del
 * relleno**. La segunda solo sale cuando lo que se va a dibujar admite fondo:
 * ofrecerla con el lápiz o con una flecha sería un botón que no puede hacer
 * nada. Ver [propiedadesPara].
 *
 * ## Y se mueve, y se puede clavar
 *
 * Se arrastra por su asa a donde no estorbe. Clavada no se cierra al elegir:
 * cambiando de color cada dos trazos, un desplegable que se cierra obliga a
 * abrirlo veinte veces.
 */

/** Una combinación con su nombre y sus tonos, en orden. */
data class Combinacion(val nombre: String, val colores: List<String>)

/** Qué se está tiñendo: el trazo o el fondo. */
enum class Tinta { LAPIZ, RELLENO }

/**
 * Las paletas que vienen puestas.
 *
 * Doce combinaciones de ocho tonos, ordenadas de la más neutra a la más
 * saliente. Todas empiezan por **un oscuro casi neutro**, porque el trazo de
 * estructura es casi siempre ese: así el primer tono de cualquier paleta es uno
 * con el que se puede dibujar sin pensar.
 */
val COMBINACIONES: List<Combinacion> = listOf(
    Combinacion(
        "Excalidraw",
        listOf("#1e1e1e", "#343a40", "#e03131", "#c2255c", "#9c36b5", "#1971c2", "#2f9e44", "#f08c00")
    ),
    Combinacion(
        "Tinta",
        listOf("#000000", "#1b1b1f", "#343a40", "#495057", "#868e96", "#adb5bd", "#ced4da", "#f1f3f5")
    ),
    Combinacion(
        "Plano",
        listOf("#1f2933", "#3e4c59", "#52606d", "#7b8794", "#9aa5b1", "#b91c1c", "#0b7285", "#d97706")
    ),
    Combinacion(
        "Tierra",
        listOf("#2b2118", "#3e3b32", "#6f4e37", "#8d6e5c", "#a9746e", "#c9a227", "#7d8c5c", "#d9c8a9")
    ),
    Combinacion(
        "Océano",
        listOf("#03045e", "#023e8a", "#0077b6", "#0096c7", "#00b4d8", "#48cae4", "#90e0ef", "#caf0f8")
    ),
    Combinacion(
        "Bosque",
        listOf("#081c15", "#1b4332", "#2d6a4f", "#40916c", "#52b788", "#74c69d", "#95d5b2", "#b7e4c7")
    ),
    Combinacion(
        "Atardecer",
        listOf("#3d1308", "#7f2d00", "#c1440e", "#e2711d", "#e97451", "#f4a261", "#ffb703", "#ffd166")
    ),
    Combinacion(
        "Uva",
        listOf("#10002b", "#240046", "#3c096c", "#5a189a", "#7b2cbf", "#9d4edd", "#c77dff", "#e0aaff")
    ),
    Combinacion(
        "Neón",
        listOf("#14213d", "#3a86ff", "#4cc9f0", "#8338ec", "#b5179e", "#ff006e", "#fb5607", "#ffbe0b")
    ),
    Combinacion(
        "Pastel",
        listOf("#495057", "#ffc9c9", "#ffd8a8", "#ffec99", "#b2f2bb", "#99e9f2", "#a5d8ff", "#d0bfff")
    ),
    Combinacion(
        "Vino",
        listOf("#2b0307", "#590d22", "#800f2f", "#a4133c", "#c9184a", "#ff4d6d", "#ff8fa3", "#ffccd5")
    ),
    Combinacion(
        "Cuaderno",
        listOf("#22223b", "#4a4e69", "#9a8c98", "#c9ada7", "#f2e9e4", "#1d3557", "#457b9d", "#e63946")
    )
)

/**
 * La paleta, flotando sobre el lienzo.
 *
 * [tintas] son las que se pueden tocar ahora mismo; con una sola no salen las
 * pestañas, que no habría nada que elegir.
 */
@Composable
fun PaletaDeColores(
    /** El color que hay puesto en la tinta que se está mirando. */
    actual: String,
    tinta: Tinta,
    tintas: Set<Tinta>,
    clavada: Boolean,
    onTinta: (Tinta) -> Unit,
    onElegir: (String) -> Unit,
    onClavar: (Boolean) -> Unit,
    onCerrar: () -> Unit,
    /** El lienzo está en modo noche. Ver la nota de la muestra. */
    noche: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Dónde se ha arrastrado. En píxeles y con `offset { }`, que se aplica al
    // colocar y no obliga a recomponer: arrastrar tiene que ir suelto.
    val densidad = androidx.compose.ui.platform.LocalDensity.current
    var dx by remember { mutableFloatStateOf(with(densidad) { SITIO_INICIAL.toPx() }) }
    var dy by remember { mutableFloatStateOf(with(densidad) { SITIO_INICIAL.toPx() }) }
    // Lo que mide. Se estira por la esquina de abajo a la derecha, como la
    // ventana de la referencia y como cualquier ventana: **con el ancla en la
    // esquina contraria**, para que crezca hacia donde va el dedo.
    var ancho by remember { mutableStateOf(ANCHO_DE_LA_PALETA) }
    var alto by remember { mutableStateOf(ALTO_DE_LA_PALETA) }

    /**
     * Si se ve la cabecera, o solo los colores.
     *
     * Igual que en la ventana de la imagen, y por lo mismo: la ventana está
     * **encima del dibujo**, y una vez colocada la cabecera solo tapa. Un toque
     * en el hueco de la paleta la esconde y otro la devuelve; los tonos siguen
     * respondiendo, porque el toque de un tono lo coge él antes de llegar aquí.
     */
    var conCabecera by remember { mutableStateOf(true) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        shadowElevation = if (clavada) 12.dp else 8.dp,
        modifier = modifier
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .size(width = ancho, height = alto)
    ) {
        Box(
            Modifier.pointerInput(Unit) {
                detectTapGestures { conCabecera = !conCabecera }
            }
        ) {
        Column(Modifier.padding(10.dp)) {
            if (conCabecera) BarraDeVentana(
                onArrastrar = { ax, ay -> dx += ax; dy += ay },
                onAmpliar = null,
                onCerrar = onCerrar,
                extras = {
                    Box(
                        Modifier
                            .size(ALTO_DE_LA_CABECERA)
                            .pointerInput(clavada) {
                                detectTapGestures {
                                    onClavar(!clavada)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "Dejar la paleta a la vista",
                            tint = if (clavada) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            )

            // Las dos tintas. Con una sola no se enseñan: sería una pestaña que
            // no lleva a ningún sitio.
            if (conCabecera && tintas.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Pestana("Lápiz", tinta == Tinta.LAPIZ, Modifier.weight(1f)) {
                        onTinta(Tinta.LAPIZ)
                    }
                    Pestana("Relleno", tinta == Tinta.RELLENO, Modifier.weight(1f)) {
                        onTinta(Tinta.RELLENO)
                    }
                }
            }

            // **Sin relleno**, que solo tiene sentido en la tinta del fondo y es
            // la única forma de apagarlo desde que el color vive aquí.
            if (conCabecera && tinta == Tinta.RELLENO) {
                SinRelleno(isTransparent(actual)) { onElegir(Element.TRANSPARENT) }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                // Lo que quede: la ventana se estira y la rejilla con ella, que
                // es lo que uno espera al agrandar algo que se desplaza.
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(COMBINACIONES, key = { it.nombre }) { combinacion ->
                    Recuadro(combinacion, actual, noche, onElegir)
                }
            }
        }

        // La esquina de estirar, donde la busca la mano. Solo con la cabecera
        // puesta: sin ella la ventana ya está colocada y lo que se quiere es
        // que no estorbe nada.
        if (conCabecera) Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(ESQUINA_DE_LA_PALETA)
                .pointerInput(Unit) {
                    detectDragGestures { cambio, arrastre ->
                        cambio.consume()
                        ancho = (ancho + arrastre.x.toDp()).coerceIn(ANCHO_MINIMO, ANCHO_MAXIMO)
                        alto = (alto + arrastre.y.toDp()).coerceIn(ALTO_MINIMO, ALTO_MAXIMO)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.OpenInFull,
                contentDescription = "Redimensionar la paleta",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
        }
        }
    }
}

/** Una pestaña de tinta: lápiz o relleno. */
@Composable
private fun Pestana(
    texto: String,
    puesta: Boolean,
    modifier: Modifier = Modifier,
    onTocar: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (puesta) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable { onTocar() }
    ) {
        Text(
            texto,
            fontSize = 12.sp,
            color = if (puesta) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** La casilla de «sin relleno»: el hueco a cuadros de toda la vida. */
@Composable
private fun SinRelleno(puesto: Boolean, onTocar: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                if (puesto) 2.dp else 0.dp,
                if (puesto) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .clickable { onTocar() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("∅", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Sin relleno",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * Una paleta entera en un recuadro: **ocho tonos pegados, sin aire**.
 *
 * El recuadro es cuadrado y los tonos lo llenan en dos filas de cuatro, así que
 * la paleta **es** su recuadro: se lee como una muestra de la combinación y a la
 * vez cada trozo es tocable. El redondeo va en el recuadro, no en cada tono; los
 * de dentro salen rectos y pegados, que es lo que hace que quepan.
 */
@Composable
private fun Recuadro(
    combinacion: Combinacion,
    actual: String,
    noche: Boolean,
    onElegir: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(12.dp)
                )
        ) {
            combinacion.colores.chunked(4).forEach { fila ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    fila.forEach { hex ->
                        val puesto = hex.equals(actual, ignoreCase = true)
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                // **La muestra se enseña como se va a ver.**
                                //
                                // De noche el lienzo pasa cada color por su
                                // filtro, así que la paleta enseñaba una cosa y
                                // el trazo salía de otra: elegías un azul y
                                // pintaba otro azul. La paleta guarda el color
                                // de siempre —el del archivo— pero lo pinta ya
                                // filtrado, que es lo que uno está eligiendo.
                                .background(Color(DrawTheme.filtrar(parseColor(hex), noche)))
                                .clickable { onElegir(hex) },
                            contentAlignment = Alignment.Center
                        ) {
                            // El puesto se marca con un aro **dentro** del tono:
                            // cambiarle el color dejaría de enseñar el color que
                            // va a salir, que es lo que se está mirando.
                            if (puesto) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(3.dp)
                                        .border(
                                            2.dp,
                                            Color.White,
                                            RoundedCornerShape(4.dp)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            combinacion.nombre,
            fontSize = 9.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Lo ancho que se deja la paleta.
 *
 * **Estrecha a propósito.** Los recuadros están bien de tamaño —se reconoce la
 * combinación de un vistazo— así que lo que se recorta es la ventana: dos por
 * fila y a desplazar. Flotando encima del dibujo, una ventana ancha tapa
 * justamente aquello para lo que estás eligiendo el color.
 */
private val ANCHO_DE_LA_PALETA: Dp = 232.dp

/** Lo que mide de alto al nacer, y hasta dónde se la puede estirar. */
private val ALTO_DE_LA_PALETA: Dp = 330.dp
private val ANCHO_MINIMO: Dp = 150.dp
private val ANCHO_MAXIMO: Dp = 420.dp
private val ALTO_MINIMO: Dp = 180.dp
private val ALTO_MAXIMO: Dp = 560.dp

/** Dónde nace, contando desde la esquina de arriba a la izquierda. */
private val SITIO_INICIAL: Dp = 70.dp

/** El blanco de la esquina de estirar. */
private val ESQUINA_DE_LA_PALETA: Dp = 22.dp
