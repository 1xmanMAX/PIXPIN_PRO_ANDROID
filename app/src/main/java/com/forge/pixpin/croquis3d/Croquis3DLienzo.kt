package com.forge.pixpin.croquis3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Pt3
import com.forge.pixpin.motor.ElBrilloDeMas
import com.forge.pixpin.motor.VECES_EL_BLANCO
import com.forge.pixpin.motor.parseColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * **Lo que hay que pintar del croquis**: lo que está dibujado, no lo que está en la mano.
 *
 * Va aparte de [LoQueVaEnLaMano] **por fluidez, y es de las cosas que más se notan**. Un
 * croquis se dibuja moviendo el dedo, y cada muestra del lápiz cambia el trazo en curso
 * —doscientas veces por segundo—. Estando todo en un mismo montón, leer cualquier cosa para
 * pintar el punto nuevo apuntaba al pintado entero como interesado, así que **cada punto
 * volvía a montar la escena entera**: proyectar los quinientos trazos que ya había, armar
 * sus contornos y tirarlos a la basura para que el siguiente punto los volviera a armar.
 * Eso es trabajo por lo que ya está quieto, y es de donde salen los tirones al trazar
 * deprisa y el calor del aparato.
 *
 * Partido en dos, cada capa se entera de lo suyo: la escena solo se vuelve a pintar cuando
 * cambia **el croquis o desde dónde se mira**, y mientras se traza se repinta la mano y
 * nada más. Ver [Croquis3DLienzo], que es donde están las dos capas y por qué son dos.
 *
 * Se pide con una función y no con los datos sueltos por la misma razón de siempre: leído
 * dentro del pintado, lo que se apunta como interesado es el pintado y no la composición,
 * así que la pantalla de arriba —la barra, el cubo, los paneles— no se entera de nada.
 */
class LoQueSePinta(
    val croquis: Croquis,
    val camara: Camara3D,
    /** Los trazos elegidos, que van de otro color. */
    val seleccion: Set<String>,
    /** Si el espejo está puesto: entonces se pinta su eje. */
    val espejoPuesto: Boolean,
    /** De qué color es el espacio, para que lo auxiliar se pinte de lo contrario. */
    val fondo: Color,
    /**
     * Si se está mirando el croquis **puesto en el sitio**, con la cámara detrás.
     *
     * Lo único que cambia es dónde se planta la retícula del suelo, y no es un detalle: ver
     * [pintarSuelo]. Ver [Croquis3DControlador.realidad].
     */
    val puestoEnElSitio: Boolean = false,
    /**
     * Lo que se está subrayando **ahora mismo**, si es que se está subrayando.
     *
     * Es lo único que está en la mano y aun así entra en la escena, y tiene que ser así:
     * un rodillo se pinta unido a su montón, que es lo que hace que pasar el rotulador por
     * encima de otra pasada no se vea más oscuro hasta soltar el dedo. Pintado por su
     * cuenta tendría su propia capa y se sumaría con lo de debajo. Ver [pintarLosRodillos].
     *
     * Y por eso quien lo da **solo mira lo que está en la mano cuando hay un rodillo
     * puesto**: leerlo siempre volvería a atar la escena a cada muestra del lápiz, que es
     * justo lo que esta separación evita. Ver [Croquis3DControlador.elRodilloEnLaMano].
     */
    val subrayandoAhora: List<Trazo3D> = emptyList()
)

/**
 * **Lo que está en la mano**: el trazo en curso y los mandos con los que se está trazando.
 *
 * Es la capa de arriba y la que se repinta con cada muestra del lápiz. Lleva **un solo
 * trazo**, así que repintarla cuesta lo mismo con el croquis vacío que con el croquis
 * lleno — que es exactamente lo que hacía falta. Ver [LoQueSePinta].
 */
class LoQueVaEnLaMano(
    val camara: Camara3D,
    /**
     * El croquis, del que aquí solo se miran tres cosas: hasta dónde llega —para repartir
     * el cuerpo de las tintas viejas—, el eje del espejo y si hay hojas donde reflejar.
     *
     * Se lee entero, sí, pero **cambia al soltar el dedo y no al moverlo**: mientras se
     * traza, lo que cambia es el trazo en curso, y el croquis es el mismo objeto de antes.
     */
    val croquis: Croquis,
    /** Lo que se está trazando ahora mismo, ya puesto en el mundo. */
    val trazoEnCurso: List<Pt3>,
    /** Y cuánto apretaba la mano en cada punto de lo que se está trazando. */
    val presionEnCurso: List<Double>,
    /** Y la hora de cada punto — la llave del régimen nuevo. Ver [Trazo3D.tiempos]. */
    val tiemposEnCurso: List<Double> = emptyList(),
    /** Y sobre qué plano se está trazando, para que el rodillo nazca ya tumbado en él. */
    val normalEnCurso: Pt3?,
    /** Y la de cada punto, si se está trazando sobre una hoja curva. Ver [Trazo3D.normales]. */
    val normalesEnCurso: List<Pt3> = emptyList(),
    /** Cuánto tapa la tinta que se está usando. */
    val opacidad: Double,
    /** Y cuánto alumbra, si alumbra. */
    val luz: Double,
    /** Y cómo está la punta puesta. Ver [PuntaDelPincel]. */
    val punta: PuntaDelPincel,
    val herramienta: Herramienta3D,
    val color: String,
    val grosor: Double,
    /** Con qué punta, para que lo que se está trazando se vea ya como va a quedar. */
    val pincel: Pincel,
    /** Dónde está el plano en el que caería el dedo ahora mismo, para señalarlo. */
    val apoyo: Pt3?,
    /** Dónde está la bolita de seleccionar, si se está pasando. */
    val bolita: Pt?,
    /** Si el trazo en curso se ha enderezado por haber parado el dedo, para avisarlo. */
    val enderezandoSolo: Boolean,
    /** Si el espejo está puesto: entonces se pinta lo que va a salir del otro lado. */
    val espejoPuesto: Boolean,
    /** De qué color es el espacio, para que las guías se pinten de lo contrario. */
    val fondo: Color,
    /** De qué color está el tubo apagado, para lo que se está trazando. */
    val colorApagada: String? = null,
    /** La zona que se lleva marcada, si se está recortando. */
    val elRecorte: Recorte?
)

/**
 * El lienzo del croquis en el espacio: **lo que se ve y lo que se toca**.
 *
 * ## El orden de pintado
 *
 * De atrás hacia delante, por lo lejos que está cada cosa del que mira. Es el algoritmo
 * del pintor, el mismo del lienzo en volumen, y aquí basta por lo mismo: trazos y láminas
 * no se cruzan entre sí lo suficiente como para que la media mienta. Un motor de verdad
 * usaría un búfer de profundidad; eso pide una superficie con GL, y toda esta aplicación
 * cabe en un `Canvas` corriente.
 *
 * ## Y por qué esto va fluido sin escribir una línea de GL
 *
 * Es la pregunta que hay que hacerse mirando un juego: **¿cómo corre eso en un teléfono
 * modesto, con miles de triángulos y luces, cuando aquí cuatro rayas iban a tirones?** No es
 * que la tarjeta gráfica del teléfono se quede corta —este croquis no le llega ni al tobillo
 * a un juego—: es que un juego **no rehace el escenario en cada fotograma** y esto sí lo
 * hacía. La tarjeta estaba parada esperando; el que sudaba era el procesador, montando otra
 * vez la misma geometría sesenta veces por segundo.
 *
 * El lienzo de Android **ya va por la tarjeta gráfica**: lo que se manda a pintar no se
 * dibuja píxel a píxel, se apunta en una lista de órdenes que ejecuta la tarjeta. Así que no
 * hacía falta cambiar de tecnología, hacía falta **dejar de rehacer la lista**. De un juego
 * se copian tres cosas, y son las tres que hay aquí:
 *
 * 1. **Lo quieto se arma una vez** (el escenario). El croquis ya dibujado va en su propia
 *    capa ([Modifier.graphicsLayer]): mientras no cambie ni él ni la vista, su lista de
 *    órdenes **se vuelve a echar sin volver a montarla**. Trazar una raya ya no cuesta
 *    quinientos trazos, cuesta uno. Ver la caja de abajo.
 * 2. **Lo que no se ve no se pinta** (el recorte por vista). Lo que cae fuera del cristal se
 *    descarta con dos proyecciones, sin recorrer sus puntos. Ver [fueraDeLaVista].
 * 3. **Lo que se mueve no se mira con lupa** (los niveles de detalle). Girando o acercándose
 *    no hay más remedio que volver a proyectar, así que se proyecta con menos puntos, con
 *    menos peldaños en las luces y con los trazos finos pintados como rayas; en cuanto la
 *    vista para, se repinta entero y fino. Ver [LaCalidad].
 *
 * Y una cuarta que no es de juegos sino de este lienzo: **no fabricar basura por fotograma**.
 * Los caminos, los contornos y las listas de trabajo se reaprovechan, porque un objeto por
 * trazo y por fotograma no se ve como memoria gastada — se ve como el tirón de cada pocos
 * segundos. Ver [LosCaminos], [CuadernoDelContorno] y [Borrador].
 *
 * ## El suelo
 *
 * Se pinta una malla en el plano `z = 0`, y no es decoración: sin ella, un croquis
 * suelto en el espacio no dice desde dónde se está mirando —una raya en el vacío se ve
 * igual desde arriba que desde abajo— y girar la vista desorienta en vez de orientar.
 *
 * Y sus cuadros son de un tamaño fijo del mundo, no del aumento: es lo que da la noción de
 * medida y de proporción, porque contar cuadros es medir. Ver [pisosDeLaMalla].
 */
@Composable
fun Croquis3DLienzo(
    /**
     * Si se está marcando la zona que se va a congelar.
     *
     * Aparte de [laEscena] porque **no es algo que se pinte, es un modo del dedo**: de
     * él depende qué hace el gesto, así que cambia el atendedor de toques y no el pintado.
     */
    recortando: Boolean,
    /** Las imágenes ya cargadas, por su ruta. Ver [Imagen3D]. */
    imagenes: Map<String, android.graphics.Bitmap>,
    /**
     * **El croquis que hay que pintar, pedido en el momento de pintarlo y no antes.**
     *
     * Es una función y no los datos sueltos por una razón de fluidez, no de gusto. Pasados
     * como parámetros sueltos, quien llama tiene que **leerlos al componer**, y leer un
     * estado al componer apunta a la composición como la interesada: cada cambio volvía a
     * montar la pantalla entera —la barra, el cubo, los mandos, los paneles— para acabar
     * pintando una raya. Pedidos aquí dentro, la lectura ocurre **mientras se pinta**, así
     * que lo que se apunta como interesado es el propio pintado.
     *
     * Y va aparte de [laMano] para que además cada capa se entere solo de lo suyo. Ver
     * [LoQueSePinta].
     */
    laEscena: () -> LoQueSePinta,
    /** Lo que está en la mano: el trazo en curso y sus mandos. Ver [LoQueVaEnLaMano]. */
    laMano: () -> LoQueVaEnLaMano,
    /**
     * Desde dónde se mira, a secas.
     *
     * Es lo único que se lee fuera del pintado, y es para saber **cuándo se ha dejado de
     * mover la vista**: mientras se mueve se pinta a lo basto y en cuanto para se repinta
     * fino. Ver [LaCalidad].
     */
    laCamara: () -> Camara3D,
    modifier: Modifier = Modifier,
    /**
     * Un punto del trazo, **con la hora en la que se tocó de verdad**.
     *
     * La hora la trae el evento y no el reloj de quien lo atiende: entre las muestras que
     * llegan juntas en un fotograma hay milisegundos de diferencia, y lo que se saca de ahí
     * —lo deprisa que iba la mano, si el dedo se ha parado— sale mal si a todas se les pone
     * la misma. Ver [Croquis3DControlador.tocar].
     */
    alTocar: (Pt, Fase, Double, Long) -> Unit,
    /**
     * Un gesto de navegación: cuánto se ha arrastrado, cuánto se ha pellizcado, **dónde**
     * y si se pide desplazar en vez de girar.
     *
     * El dónde importa: acercar tiene que acercarse a donde están los dedos y no al centro
     * de la pantalla. Sin eso, para mirar de cerca una esquina hay que acercarse y
     * después perseguirla desplazando, que es la mitad del trabajo de navegar.
     */
    alNavegar: (pan: Offset, zoom: Float, centro: Offset, gesto: Gesto) -> Unit,
    /**
     * Dos toques seguidos con un dedo, en cualquier parte: **encaja la vista**.
     *
     * En cualquier parte y no sobre el cubo: el gesto es «ponme recto», y pedirlo tendría
     * que costar menos que apuntar a una cara de setenta píxeles con la mano en el aire.
     */
    alDobleToque: () -> Unit,
    /** El rectángulo que se va marcando, y si el dedo ya lo ha soltado. */
    alRecortar: (Recorte?, Boolean) -> Unit,
    alMedirLaVista: (Double, Double) -> Unit
) {
    var ancho by remember { mutableStateOf(0.0) }
    var alto by remember { mutableStateOf(0.0) }

    // **El detalle con el que se pinta la escena, y quién lo decide.** Ver [ElDetalle].
    val elDetalle = remember { ElDetalle() }
    // Y el lienzo pequeño donde se pinta cuando se pide pixelado. Ver [ElCuadernoDelPixelado].
    val elPixelado = remember { ElCuadernoDelPixelado() }
    // Y la cabeza del trazo en curso, ya pintada, para no repintarla por muestra. Ver [ElTrazoAsentado].
    val elAsentado = remember { ElTrazoAsentado() }
    // Y el reloj que dice «la vista lleva un rato quieta». `collectLatest` cancela la espera
    // en cuanto la vista se vuelve a mover, así que mientras se arrastra no salta ni una vez
    // y en cuanto se suelta salta una sola: **un repintado fino, ni uno más**. Parado no
    // cuesta nada, porque sin cambios no hay nada que recoger.
    LaunchedEffect(Unit) {
        snapshotFlow { laCamara() }.collectLatest {
            delay(LO_QUE_SE_ESPERA_A_AFINAR)
            elDetalle.pulso++
        }
    }

    Box(
        modifier
            .pointerInput(recortando) {
                // **Marcando la zona, el dedo no dibuja ni gira: encuadra.**
                //
                // Es un modo y no un gesto más porque lo que se está haciendo no tiene nada
                // que ver con dibujar: se está diciendo qué trozo de lo que se ve va a la
                // lámina. Sale solo en cuanto se levanta el dedo.
                if (recortando) {
                    awaitEachGesture {
                        val abajo = awaitFirstDown()
                        abajo.consume()
                        val ancho = size.width.toFloat().coerceAtLeast(1f)
                        val alto = size.height.toFloat().coerceAtLeast(1f)
                        fun comoVa(hasta: Offset) = Recorte(
                            (abajo.position.x / ancho).toDouble().coerceIn(0.0, 1.0),
                            (abajo.position.y / alto).toDouble().coerceIn(0.0, 1.0),
                            (hasta.x / ancho).toDouble().coerceIn(0.0, 1.0),
                            (hasta.y / alto).toDouble().coerceIn(0.0, 1.0)
                        )
                        var ultimo = abajo.position
                        while (true) {
                            val evento = awaitPointerEvent()
                            val dedo = evento.changes.firstOrNull { it.pressed } ?: break
                            ultimo = dedo.position
                            dedo.consume()
                            alRecortar(comoVa(ultimo), false)
                        }
                        alRecortar(comoVa(ultimo), true)
                    }
                    return@pointerInput
                }
                // **El lápiz dibuja y la mano navega.**
                //
                // Es el reparto de una mesa de dibujo, y resuelve de un plumazo lo que
                // ningún gesto resuelve del todo bien: no hay que adivinar si un dedo que
                // se arrastra quería trazar o quería girar. De regalo viene el rechazo de
                // palma —la palma es un dedo y los dedos no pintan— así que se puede
                // apoyar la mano en la pantalla como en el papel.
                //
                // **Y la mano navega siempre, se tenga puesto lo que se tenga**: uno gira,
                // dos desplazan y acercan, tres tocan la cámara misma (la ladean y le
                // cambian la lente). No hay herramienta que apague eso, y por eso tampoco
                // hace falta una «mano» que elegir: mover la vista es lo que más se hace en
                // un croquis en el espacio, y lo que más se hace no se elige, se hace.

                // Entre un gesto y el siguiente se recuerda el último toque suelto: dos
                // seguidos, cerca y a tiempo son un doble toque. Vive aquí fuera porque un
                // doble toque son **dos gestos**, no uno.
                var elToqueAnterior = 0L
                var dondeElAnterior = Offset.Zero
                awaitEachGesture {
                    val primero = awaitFirstDown(requireUnconsumed = false)
                    val conLapiz = primero.type == PointerType.Stylus
                    var dedos = 1
                    var dibujando = conLapiz
                    var yaSeNavego = false
                    var acabaDeCambiar = false
                    // Lo que hace falta para saber, al levantar, si esto fue un toque: que
                    // no se haya ido lejos, que no haya durado y que fuera un solo dedo.
                    var loQueSeFue = 0f
                    var seLevantoEn = primero.uptimeMillis
                    var unSoloDedo = true
                    // **Dónde estaba la punta la última vez que pintó.** El LEVANTA tiene
                    // que llegar con esa posición y no con una de relleno: el Motor Pluma
                    // clava la punta donde diga el evento — con (0,0) el remate de cola
                    // caminaba hasta la esquina de la pantalla (la raya que se disparaba
                    // al borde al soltar) y una recta enderezada se clavaba donde el rayo
                    // por la esquina cortara el plano (la raya recta que aparecía en otra
                    // parte).
                    var ultimaPunta = primero.position
                    if (dibujando) {
                        alTocar(
                            Pt(primero.position.x.toDouble(), primero.position.y.toDouble()),
                            Fase.BAJA,
                            primero.pressure.toDouble(),
                            primero.uptimeMillis
                        )
                    }

                    while (true) {
                        val evento = awaitPointerEvent()
                        val apoyados = evento.changes.filter { it.pressed }
                        if (apoyados.isEmpty()) break
                        apoyados.first().let {
                            seLevantoEn = it.uptimeMillis
                            loQueSeFue =
                                maxOf(loQueSeFue, (it.position - primero.position).getDistance())
                        }
                        if (apoyados.size > 1) unSoloDedo = false

                        if (dibujando) {
                            // Con el lápiz apoyado, **los dedos no existen**: ni navegan
                            // ni cancelan el trazo. Es lo que deja apoyar la palma, y
                            // también dar la vuelta al dispositivo con la otra mano sin
                            // que el trazo pegue un salto.
                            val punta = apoyados.firstOrNull { it.type == PointerType.Stylus }
                                ?: apoyados.first()
                            // **Y con los puntos que pasaron entre fotograma y fotograma.**
                            //
                            // Un lápiz manda doscientas muestras por segundo y la pantalla
                            // enseña sesenta u ochenta: quedándose con la última de cada
                            // fotograma se tiran dos de cada tres, y las que se tiran son
                            // justo las de las curvas —donde la mano va deprisa—. Peor aún,
                            // eso empeora **según se llena el croquis**: cuanto más cuesta
                            // pintar, menos fotogramas, menos muestras, y lo que se traza
                            // deja de ser una curva para ser tres rectas cosidas. Android
                            // guarda las que no llegaron a tiempo en el propio evento, así
                            // que se leen todas y el trazo sale igual de fino vaya la
                            // aplicación a ochenta fotogramas o a veinte.
                            //
                            // La presión de las de en medio es la del evento: no viene
                            // apuntada, y entre dos muestras de cinco milisegundos la mano
                            // no cambia de fuerza.
                            for (antes in punta.historical) {
                                alTocar(
                                    Pt(antes.position.x.toDouble(), antes.position.y.toDouble()),
                                    Fase.MUEVE,
                                    punta.pressure.toDouble(),
                                    antes.uptimeMillis
                                )
                            }
                            alTocar(
                                Pt(punta.position.x.toDouble(), punta.position.y.toDouble()),
                                Fase.MUEVE,
                                punta.pressure.toDouble(),
                                punta.uptimeMillis
                            )
                            ultimaPunta = punta.position
                            apoyados.forEach { it.consume() }
                            continue
                        }

                        // Un lápiz que baja a media navegación manda: se corta el gesto de
                        // la mano y se empieza a dibujar, sin tener que levantar los dedos.
                        val lapizNuevo = apoyados.firstOrNull { it.type == PointerType.Stylus }
                        if (lapizNuevo != null) {
                            dibujando = true
                            alTocar(
                                Pt(lapizNuevo.position.x.toDouble(), lapizNuevo.position.y.toDouble()),
                                Fase.BAJA,
                                lapizNuevo.pressure.toDouble(),
                                lapizNuevo.uptimeMillis
                            )
                            ultimaPunta = lapizNuevo.position
                            apoyados.forEach { it.consume() }
                            continue
                        }

                        val cuantos = apoyados.size
                        if (cuantos != dedos) {
                            dedos = cuantos
                            // **El fotograma en el que cambia el número de dedos se tira.**
                            //
                            // Al posar o levantar uno, el centro de los dedos salta de
                            // golpe —pasa de estar en un sitio a estar en la media de dos—
                            // y el arrastre de ese instante vale ese salto entero: la
                            // vista pegaba un tirón justo al empezar a girar.
                            acabaDeCambiar = true
                        }
                        apoyados.forEach { it.consume() }
                        if (acabaDeCambiar) {
                            acabaDeCambiar = false
                            continue
                        }

                        if (cuantos == 1) {
                            // No se gira con un dedo si antes hubo dos: al levantar uno
                            // siempre queda el otro apoyado, y la vista se iría sola justo
                            // al acabar el pellizco.
                            if (yaSeNavego) continue
                            val dedo = apoyados.first()
                            alNavegar(
                                dedo.position - dedo.previousPosition, 1f, dedo.position, Gesto.GIRAR
                            )
                        } else {
                            yaSeNavego = true
                            alNavegar(
                                evento.calculatePan(),
                                evento.calculateZoom(),
                                evento.calculateCentroid(useCurrent = true),
                                if (cuantos == 2) Gesto.DESPLAZAR else Gesto.LENTE
                            )
                        }
                    }
                    if (dibujando) {
                        alTocar(
                            Pt(ultimaPunta.x.toDouble(), ultimaPunta.y.toDouble()),
                            Fase.LEVANTA, 1.0, seLevantoEn
                        )
                    }

                    // **Y si no fue nada de lo anterior, a lo mejor fue un toque.**
                    //
                    // Un toque es un dedo que baja y sube sin irse a ningún sitio. Se mide
                    // contra el mismo umbral con el que el sistema decide que un arrastre
                    // es un arrastre, para que el listón sea el de siempre.
                    val fueUnToque = !dibujando && unSoloDedo &&
                        loQueSeFue <= viewConfiguration.touchSlop &&
                        seLevantoEn - primero.uptimeMillis <= LO_QUE_DURA_UN_TOQUE
                    if (fueUnToque) {
                        val seguido = primero.uptimeMillis - elToqueAnterior <= ENTRE_LOS_DOS
                        val cerca = (primero.position - dondeElAnterior).getDistance() <=
                            viewConfiguration.touchSlop * 4
                        if (seguido && cerca) {
                            // Y se olvida, o un tercer toque volvería a encajar.
                            elToqueAnterior = 0L
                            alDobleToque()
                        } else {
                            elToqueAnterior = primero.uptimeMillis
                            dondeElAnterior = primero.position
                        }
                    }
                }
            }
    ) {
        // **Dos capas, y esta es la razón de que el croquis vaya fluido.**
        //
        // Un juego no vuelve a montar el escenario en cada fotograma: lo tiene armado y lo
        // que cambia de un fotograma al siguiente es lo poco que se mueve. Aquí pasaba lo
        // contrario: en un solo lienzo, **cualquier cosa que cambiara obligaba a repintarlo
        // todo**, y lo que cambia doscientas veces por segundo es el punto que está saliendo
        // de la punta del lápiz. Así que cada punto volvía a proyectar los quinientos trazos
        // ya dibujados, a armar sus contornos y a tirarlos. Trabajo entero por lo que está
        // quieto, sesenta veces por segundo — el tirón al trazar deprisa, y el calor.
        //
        // Partido en dos capas, cada una se entera solo de lo suyo:
        //
        // - **La escena** —el suelo, las hojas, lo dibujado— se vuelve a montar únicamente
        //   cuando cambia el croquis o desde dónde se mira. Lleva [Modifier.graphicsLayer]
        //   a propósito: eso le da su propia lista de órdenes de pintado, así que mientras
        //   no cambie **no se vuelve a montar, se vuelve a echar** — y echarla es cosa de la
        //   tarjeta gráfica, que es justo para lo que está. Sin la capa, invalidar la de
        //   arriba arrastraría a esta y no habríamos separado nada.
        // - **La mano** —el trazo que está saliendo, la bolita, el apoyo— se repinta con
        //   cada muestra del lápiz, y pinta **un trazo**: cuesta lo mismo con el croquis
        //   vacío que con el croquis lleno.
        //
        // Y esta va **sin** capa propia, aposta: las tintas que alumbran suman sobre lo que
        // ya hay pintado ([pintarEncendido]), y metidas en una capa aparte sumarían sobre el
        // vacío de esa capa en vez de sobre la escena. Sin capa, sus órdenes caen sobre el
        // mismo lienzo donde acaba de echarse la escena, que es lo que hace que una luz
        // trazada encima de algo se vea encendida sobre ello.
        Canvas(
            Modifier
                .fillMaxSize()
                // **Su capa, pero sin lienzo aparte.**
                //
                // La capa es lo que hace que la escena no se vuelva a montar mientras no
                // cambie. Lo que no puede hacer es abrirse un lienzo suyo donde pintar y
                // volcarlo después: ahí las tintas que alumbran perderían lo que las hace
                // luces —el color por encima del blanco, que un lienzo intermedio recorta a
                // ocho bits— y una luz a tope se vería como cualquier tinta clara. Ver
                // [pintarEncendido].
                //
                // `ModulateAlpha` dice justo eso: llévate la lista de órdenes, pero échala
                // sobre el mismo cristal. Sin ella, la decisión se la queda el sistema.
                .graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha }
        ) {
            if (size.width.toDouble() != ancho || size.height.toDouble() != alto) {
                ancho = size.width.toDouble()
                alto = size.height.toDouble()
                alMedirLaVista(ancho, alto)
            }
            val w = size.width.toDouble()
            val h = size.height.toDouble()

            // Leído aquí dentro y no arriba: ver [laEscena].
            val loQueHay = laEscena()
            val croquis = loQueHay.croquis
            val camara = loQueHay.camara

            // **A lo basto mientras la vista se mueve, y fino en cuanto para.**
            //
            // Es lo que hace cualquier juego con la distancia: lo que se está moviendo no se
            // mira con lupa. Girando o acercándose, el croquis entero se vuelve a proyectar
            // en cada fotograma —eso no hay cómo evitarlo, la vista es otra— así que lo que
            // se puede bajar es **cuánto detalle se monta en cada uno**. En movimiento no se
            // distingue, y parado se repinta entero y fino. Ver [LaCalidad].
            LaCalidad.moviendo = elDetalle.seMueve(camara)

            // **Y con qué apertura.** El punto enfocado es del mundo, así que su hondura sale
            // de mirarlo desde donde se está mirando ahora: girar el croquis no cambia lo que
            // está enfocado. Ver [LaApertura].
            LaApertura.cuanto = croquis.apertura
            LaApertura.enfocadoEn =
                escalar(croquis.enfoque ?: camara.centro, camara.adelante)

            // **El fondo del espacio lo pinta el lienzo.** Lo ponía la caja de arriba con un
            // `background`, y para eso hay que saber el color **al componer**: es el croquis
            // quien lo lleva, así que leerlo allí volvía a atar la pantalla entera al
            // croquis. Aquí es una brocha más, la primera de todas — la pinta cada rama, que
            // pixelando va dentro del lienzo pequeño.
            if (cuantoPixela(croquis.pixelado) <= 1) drawRect(loQueHay.fondo)
            // **Lo auxiliar se pinta de lo contrario que el fondo.**
            //
            // Una línea de construcción no es del dibujo: es una marca sobre la mesa, y lo
            // que tiene que hacer es verse. Del color de la tinta se perdía entre lo
            // dibujado, y sobre el fondo de pizarra —negro sobre negro— sencillamente no
            // estaba. Negra sobre claro y blanca sobre oscuro se ve siempre, sin depender de
            // qué color llevara puesto el lápiz.
            //
            // Y el detalle se apaga pase lo que pase: las miniaturas de las vistas usan este
            // mismo pintado y no se están moviendo — si se quedara encendido, saldrían
            // bastas para siempre.
            try {
                val empezo = System.nanoTime()
                val cuadro = cuantoPixela(croquis.pixelado)
                if (cuadro <= 1) {
                    pintarLaEscena(
                        croquis, camara, w, h, loQueHay.seleccion, imagenes,
                        loQueHay.espejoPuesto, loContrarioDe(loQueHay.fondo),
                        loQueHay.puestoEnElSitio, loQueHay.subrayandoAhora
                    )
                } else {
                    // **Pixelado: se pinta pequeño y se amplía sin suavizar.**
                    //
                    // Encogen las tres cosas a la vez —el lienzo, el aumento y la densidad—,
                    // que es lo que hace que salga el mismo croquis y no un recorte de él.
                    // Ver [ElCuadernoDelPixelado].
                    val aw = kotlin.math.max(1, kotlin.math.ceil(w / cuadro).toInt())
                    val ah = kotlin.math.max(1, kotlin.math.ceil(h / cuadro).toInt())
                    val lienzo = elPixelado.lienzoDe(aw, ah)
                    elPixelado.brocha.draw(
                        Density(density / cuadro, fontScale),
                        layoutDirection,
                        androidx.compose.ui.graphics.Canvas(lienzo),
                        Size(aw.toFloat(), ah.toFloat())
                    ) {
                        // El lienzo es el de antes, así que hay que vaciarlo: sobre un fondo
                        // transparente —asomado al mundo— el fotograma anterior se quedaría
                        // debajo y el croquis iría dejando estela.
                        drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                        drawRect(loQueHay.fondo)
                        pintarLaEscena(
                            croquis, camara.copy(zoom = camara.zoom / cuadro),
                            aw.toDouble(), ah.toDouble(), loQueHay.seleccion, imagenes,
                            loQueHay.espejoPuesto, loContrarioDe(loQueHay.fondo),
                            loQueHay.puestoEnElSitio, loQueHay.subrayandoAhora
                        )
                    }
                    drawImage(
                        lienzo,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        // Sin suavizar: **esto es el pixelado**. Con el suavizado de siempre,
                        // lo que sale de ampliar un lienzo pequeño es un croquis borroso.
                        filterQuality = FilterQuality.None
                    )
                }
                // Lo que ha costado, para que el detalle del fotograma siguiente lo
                // tenga en cuenta. Ver [LaCalidad.apuntarLoQueCosto]. Se mide **aquí y no en
                // las miniaturas**: aquellas se pintan una vez y no dicen nada de cómo va la
                // aplicación.
                LaCalidad.apuntarLoQueCosto(System.nanoTime() - empezo)
            } finally {
                LaCalidad.moviendo = false
                // Y el desenfoque se apaga aquí: lo que está en la mano se pinta nítido
                // siempre. Nadie dibuja a ciegas porque la raya que está saliendo caiga en el
                // lado borroso del enfoque.
                LaApertura.cuanto = 0.0
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width.toDouble()
            val h = size.height.toDouble()
            val enLaMano = laMano()
            val croquis = enLaMano.croquis
            val camara = enLaMano.camara
            val trazoEnCurso = enLaMano.trazoEnCurso
            val presionEnCurso = enLaMano.presionEnCurso
            val tiemposEnCurso = enLaMano.tiemposEnCurso
            val normalEnCurso = enLaMano.normalEnCurso
            val normalesEnCurso = enLaMano.normalesEnCurso
            val opacidad = enLaMano.opacidad
            val luz = enLaMano.luz
            val punta = enLaMano.punta
            val herramienta = enLaMano.herramienta
            val color = enLaMano.color
            val grosor = enLaMano.grosor
            val pincel = enLaMano.pincel
            val apoyo = enLaMano.apoyo
            val bolita = enLaMano.bolita
            val enderezandoSolo = enLaMano.enderezandoSolo
            val espejoPuesto = enLaMano.espejoPuesto
            val colorApagada = enLaMano.colorApagada
            val elRecorte = enLaMano.elRecorte
            val contraste = loContrarioDe(enLaMano.fondo)
            // Lo que hace falta para pintar lo que está en la mano: hasta dónde llega el
            // croquis —para repartir el cuerpo de la tinta sobre lo que hay— y el eje del
            // espejo, para enseñar por dónde va a salir el otro lado.
            val (hondoCerca, hondoLejos) = loHondoQueLlega(croquis, camara)
            val espejo = croquis.espejo?.takeIf { espejoPuesto }

            // Lo que se está trazando ahora mismo, encima de todo: es lo que se mira.
            if (trazoEnCurso.size > 1) {
                if (herramienta == Herramienta3D.ESPEJO) {
                    // El eje que se está trazando, a rayas y de punta a punta: sale recto
                    // pase por donde pase el dedo, así que se pinta recto desde el principio.
                    pintarARayas(
                        listOf(trazoEnCurso.first(), trazoEnCurso.last()),
                        Color(parseColor(COLOR_DEL_ESPEJO, 0xFF)), camara, w, h
                    )
                } else if (herramienta == Herramienta3D.ESFERA) {
                    // **La bola se ve crecer mientras se abre el compás.** A rayas, como el
                    // plano: todavía no es una superficie, es el gesto de ponerla. Y con el
                    // radio que hay ahora mismo, no con el que había al empezar: lo que se ve
                    // mientras se arrastra es lo que va a quedar al soltar.
                    val radio = largo(menos(trazoEnCurso.last(), trazoEnCurso.first()))
                    pintarARayas(
                        circuloEnElPlano(
                            trazoEnCurso.first(), radio, camara.adelante, camara.derecha
                        ),
                        contraste, camara, w, h
                    )
                    pintarARayas(
                        listOf(trazoEnCurso.first(), trazoEnCurso.last()), contraste, camara, w, h
                    )
                } else if (
                    herramienta == Herramienta3D.LAMINA ||
                    herramienta == Herramienta3D.PLANO_COMPUESTO
                ) {
                    // **El plano se traza a rayas, no con tinta.**
                    //
                    // Lo que se está trazando con la lámina no es un dibujo: es la mesa donde
                    // se va a dibujar después. Pintándolo con el pincel puesto salía igual que
                    // un trazo del lápiz —mismo color, mismo cuerpo, mismo brillo— y hasta que
                    // uno no levantaba el dedo no sabía cuál de las dos cosas estaba haciendo.
                    // Discontinua es como se marca una línea auxiliar en cualquier plano desde
                    // que se dibuja a mano, y no hay que explicarla.
                    //
                    // **Y una sola raya, no tres.** Se pintaban además los dos bordes de la
                    // hoja, y la hoja nace de canto: los tres caían casi en el mismo sitio de
                    // la pantalla, cada uno con sus rayitas empezando en un punto distinto. Lo
                    // que se veía no era una línea auxiliar sino tres batiendo entre sí, y eso
                    // se lee como que las rayas andan solas. La que dice algo es la que se está
                    // trazando; las otras dos no enseñan nada que no esté ya debajo.
                    pintarARayas(trazoEnCurso, contraste, camara, w, h)
                } else {
                    // **La mano estrena la MISMA pluma que la escena**, cocida en
                    // transitorio (la identidad del trazo en curso cambia por muestra: el
                    // armario se inundaría). Así lo que se ve trazando ES ya, muestra a
                    // muestra, la spline con sus anchos y su luz que va a quedar al soltar
                    // — cero salto al levantar el dedo. El reflejo del espejo sigue por el
                    // camino viejo: es una guía de dónde va a caer, no el trazo.
                    //
                    // **Y por trozos, como los bloques de un juego.** Cocer y barrer el
                    // trazo entero por muestra era gratis con veinte puntos y un tirón con
                    // trescientos: cada fotograma volvía a proyectar y rellenar todo lo que
                    // ya estaba en la pantalla igual que en el anterior. Lo que ya está
                    // asentado —todo menos la cola que sigue moviéndose— se pinta UNA vez en
                    // su propio lienzo cada [TROZO_DE_LA_MANO] puntos, y por muestra solo se
                    // cuece y se barre la cola. Ver [ElTrazoAsentado]. El reparto solo vale
                    // para lo que sabe pintar la pluma; lo demás sigue por el camino viejo.
                    val n = trazoEnCurso.size
                    val laPlumaPuede = pincel.vigente != Pincel.LUZ && !punta.alumbra &&
                        !punta.plana &&
                        tiemposEnCurso.size == n && n in 2..TOPE_EN_LA_MANO &&
                        !punta.tienePerfil && !punta.tieneDibujo && !punta.deCanto
                    fun trozoVivo(desde: Int, hasta: Int, id: String) = Trazo3D(
                        id = id, puntos = trazoEnCurso.subList(desde, hasta), color = color,
                        grosor = grosor, pincel = pincel, calibre = grosor,
                        presiones = presionEnCurso.takeIf { it.size == n }?.subList(desde, hasta),
                        tiempos = tiemposEnCurso.subList(desde, hasta), opacidad = opacidad,
                        luz = luz, punta = punta, normal = normalEnCurso,
                        normales = normalesEnCurso.takeIf { it.size == n }?.subList(desde, hasta)
                    )
                    var laPintoLaPluma = false
                    // El lápiz de anotar se pinta liso también mientras se traza: si no,
                    // saldría con bulto en la mano y sin él al soltar. Ver [pintarRayaLisa].
                    if (punta.plana) {
                        pintarRayaLisa(
                            trazoEnCurso, parseColor(color, 255), grosor, opacidad, camara, w, h
                        )
                        laPintoLaPluma = true
                    }
                    if (laPlumaPuede) {
                        val base = camara.base(w, h)
                        val luzMundo = luzDelMundo(croquis)
                        val cabezaHasta = elAsentado.cabezaPara(n)
                        var cabezaBien = true
                        if (cabezaHasta > 0) {
                            if (elAsentado.hayQueRepintar(cabezaHasta, camara, w, h)) {
                                val lienzo = elAsentado.lienzoDe(w.toInt(), h.toInt())
                                val cabeza = trozoVivo(0, cabezaHasta, "@mano-cabeza")
                                // **La cabeza no acaba aquí**: el corte con la cola no es una
                                // punta, así que ni se afila ni se cierra en domo. Sin esto el
                                // trazo se estrechaba a menos de la mitad justo en su mitad.
                                val esqueletoDeLaCabeza = cocerEsqueleto(cabeza, esPuntaN = false)
                                var pintada = false
                                elAsentado.brocha.draw(
                                    Density(density, fontScale), layoutDirection,
                                    androidx.compose.ui.graphics.Canvas(lienzo),
                                    Size(w.toFloat(), h.toFloat())
                                ) {
                                    drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                                    if (esqueletoDeLaCabeza != null) {
                                        pintada = pintarTrazoPluma(
                                            cabeza, esqueletoDeLaCabeza, base, luzMundo, false
                                        )
                                    }
                                }
                                elAsentado.apuntar(cabezaHasta, camara, w, h, pintada)
                            }
                            cabezaBien = elAsentado.pintada
                            if (cabezaBien) elAsentado.lienzo?.let { drawImage(it) }
                        }
                        // La cola, solapada unos puntos con la cabeza: las puntas afiladas
                        // de una y otra quedan tapadas por el cuerpo de la otra.
                        val desde = (cabezaHasta - SOLAPE_DE_LA_MANO).coerceAtLeast(0)
                        val cola = trozoVivo(desde, n, "@mano")
                        // Y la cola no empieza: empezó la cabeza. Solo su final es punta —la
                        // que va siguiendo al dedo—, y esa sí se afila.
                        val esqueletoDeLaCola = cocerEsqueleto(cola, esPunta0 = desde == 0)
                        laPintoLaPluma = cabezaBien && esqueletoDeLaCola != null &&
                            pintarTrazoPluma(cola, esqueletoDeLaCola, base, luzMundo, false)
                    }
                    if (!laPintoLaPluma) pintarTrazo(
                        trazoEnCurso, color, grosor, pincel, camara, w, h,
                        hondoCerca, hondoLejos,
                        // Lo que va a medir en el mundo en cuanto se levante el dedo, para que
                        // lo que se ve mientras se traza sea ya lo que va a quedar.
                        calibre = grosor,
                        presiones = presionEnCurso, opacidad = opacidad, luz = luz, punta = punta,
                        normal = normalEnCurso, normales = normalesEnCurso,
                        colorApagada = colorApagada
                    )
                    // **Y lo que va a salir del otro lado, mientras se traza.** Sin esto, la
                    // mitad simétrica aparece de golpe al levantar el dedo y uno dibuja a
                    // ciegas la única parte que le importa: por dónde se cruzan las dos.
                    espejo?.let {
                        pintarTrazo(
                            it.reflejo(trazoEnCurso), color, grosor, pincel, camara, w, h,
                            hondoCerca, hondoLejos, calibre = grosor,
                            presiones = presionEnCurso, opacidad = opacidad, luz = luz,
                            punta = punta,
                            normal = normalEnCurso?.let { n -> it.reflejoDeDireccion(n) },
                            normales = normalesEnCurso.map { n -> it.reflejoDeDireccion(n) }
                        )
                    }
                }

                // **Y si se ha enderezado solo, se dice.** Una raya que se pone recta de
                // golpe sin avisar se lee como que se ha perdido lo que se llevaba trazado.
                // Dos puntas marcadas dicen lo que es ahora el trazo —un segmento entre dos
                // puntos— y que sigue en la mano: se puede estirar hasta donde se quiera.
                if (enderezandoSolo) {
                    for (extremo in listOf(trazoEnCurso.first(), trazoEnCurso.last())) {
                        val v = camara.aPantalla(extremo, w, h)
                        val donde = Offset(v.x.toFloat(), v.y.toFloat())
                        val r = PUNTA_DE_LA_RECTA.dp.toPx()
                        drawCircle(Color.White, radius = r, center = donde)
                        drawCircle(
                            Color(parseColor(color, 0xFF)), radius = r, center = donde,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }

            // El rectángulo de la zona que se va a congelar: por fuera se apaga lo que no va a
            // salir, que es lo que hace que se vea qué se está llevando uno y qué no.
            elRecorte?.let { r ->
                val x0 = (minOf(r.x0, r.x1) * w).toFloat()
                val y0 = (minOf(r.y0, r.y1) * h).toFloat()
                val x1 = (maxOf(r.x0, r.x1) * w).toFloat()
                val y1 = (maxOf(r.y0, r.y1) * h).toFloat()
                val velo = Color.Black.copy(alpha = 0.45f)
                drawRect(velo, Offset.Zero, Size(size.width, y0))
                drawRect(velo, Offset(0f, y1), Size(size.width, size.height - y1))
                drawRect(velo, Offset(0f, y0), Size(x0, y1 - y0))
                drawRect(velo, Offset(x1, y0), Size(size.width - x1, y1 - y0))
                drawRect(
                    Color.White, Offset(x0, y0), Size(x1 - x0, y1 - y0),
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // La bolita de seleccionar, en la pantalla y no en el mundo: es un gesto sobre el
            // cristal, no una figura del croquis.
            bolita?.let { b ->
                val en = Offset(b.x.toFloat(), b.y.toFloat())
                val r = Croquis3DControlador.RADIO_DE_LA_BOLITA.toFloat()
                drawCircle(Color(0x226965DB), radius = r, center = en)
                drawCircle(Color(0xCC6965DB.toInt()), radius = r, center = en, style = Stroke(width = 2f))
            }

            // Y el punto donde caería el dedo: sin él, dibujar sobre una lámina es adivinar.
            apoyo?.let {
                val p = camara.aPantalla(it, w, h)
                drawCircle(
                    Color(0x9969_65DB.toInt()),
                    radius = 5f,
                    center = Offset(p.x.toFloat(), p.y.toFloat())
                )
            }
        }
    }
}

/**
 * **Los caminos de usar y tirar, que no se tiran.**
 *
 * Un `Path` no es un objeto cualquiera: por debajo lleva una figura de las de Android, con su
 * memoria aparte de la del programa. Fabricar uno por trazo y por fotograma —quinientos, y
 * sesenta veces por segundo— es de lo más caro que se hacía aquí, y encima es de lo que peor
 * se ve: no aparece como memoria gastada, aparece como el tirón de cada pocos segundos.
 *
 * Se reaprovechan por lo mismo y con las mismas condiciones que [CuadernoDelContorno]: esto
 * se pinta en un solo hilo, y **mandar un camino a pintar guarda una copia suya** —la figura
 * de Android se copia sola en cuanto la original cambia—, así que vaciarlo y volver a usarlo
 * no toca lo que ya se mandó.
 *
 * Uno por sitio, y no un montón común: dos que se usaran a la vez se pisarían, y buscando por
 * qué no se pinta un trazo nadie va a sospechar de un camino compartido. Cada uno se usa,
 * se manda a pintar y se acabó.
 */
private object LosCaminos {
    /** El de una raya: el atajo de [pintarTrazo] y las guías. */
    val raya = Path()

    /** El de una sombra. Ver [pintarLasSombras]. */
    val sombra = Path()

    /** El del tapón de una vuelta cerrada. Ver el `capa` de [pintarTrazo]. */
    val tapon = Path()

    /** Vaciado y listo. */
    fun limpio(cual: Path): Path {
        cual.rewind()
        return cual
    }
}

/**
 * **El cuaderno de un trazo**: donde se apunta lo suyo mientras se pinta.
 *
 * Un trazo se pinta a partir de sus puntos ya proyectados, lo cerca que pasa cada uno y lo
 * que apretaba la mano ahí. Eso son cuatro listas, y se armaban nuevas **en cada trazo y en
 * cada fotograma**: con el croquis lleno son un par de miles de objetos por fotograma, todos
 * para tirarlos en el acto. No se ve como memoria gastada — se ve como el tirón cada pocos
 * segundos justo mientras se gira la vista, que es cuando peor cae.
 *
 * Se reaprovechan con las mismas condiciones que [CuadernoDelContorno]: esto se pinta en un
 * solo hilo y **un trazo no se pinta dentro de otro**, así que uno basta. Crecen hasta lo
 * que pida el trazo más largo del croquis y ahí se quedan.
 */
private object CuadernoDelTrazo {
    val en = ArrayList<Offset>(256)
    val enElMundo = ArrayList<Pt3>(256)
    var cercania = FloatArray(256)
        private set
    var aprieta = DoubleArray(256)
        private set

    /** Vacío y con sitio para tantos. */
    fun listo(cuantos: Int) {
        en.clear()
        enElMundo.clear()
        if (cercania.size < cuantos) {
            cercania = FloatArray(cuantos)
            aprieta = DoubleArray(cuantos)
        }
    }

    /** Quédate con los que han tenido que crecer sobre la marcha. */
    fun quedarse(losSuyos: FloatArray, losOtros: DoubleArray) {
        cercania = losSuyos
        aprieta = losOtros
    }
}

/**
 * **Un montón de rayas sueltas, mandadas a pintar de una sola vez.**
 *
 * Es lo que hacen el suelo y la retícula de una hoja: doscientas y pico rayas rectas, todas
 * del mismo color y del mismo grueso. Metidas en un `Path`, cada una es un trozo suelto de
 * una figura y quien pinta tiene que tratarlas como tal —mirarle las vueltas, los cruces y
 * los remates— para acabar pintando doscientos segmentos rectos. Mandadas como lo que son,
 * el lienzo de Android tiene un camino corto para ellas y las pinta de una tacada.
 *
 * Y se acumulan en un vector de números que se reaprovecha, así que el suelo entero no
 * fabrica ni un objeto por fotograma — y el suelo se pinta en **todos**, hasta con el
 * croquis vacío, que es justo cuando más raro se hace que la vista vaya a tirones.
 */
private object ElMontonDeRayas {
    var puntos = FloatArray(2048)
        private set
    var cuantos = 0
        private set

    fun vaciar() {
        cuantos = 0
    }

    fun raya(ax: Float, ay: Float, bx: Float, by: Float) {
        if (cuantos + 4 > puntos.size) puntos = puntos.copyOf(puntos.size * 2)
        puntos[cuantos++] = ax
        puntos[cuantos++] = ay
        puntos[cuantos++] = bx
        puntos[cuantos++] = by
    }
}

/** Y el pincel con el que se vuelcan, uno solo. Ver [ElMontonDeRayas]. */
private val ELPINCEL_DE_LAS_RAYAS by lazy {
    android.graphics.Paint().also {
        it.isAntiAlias = true
        it.style = android.graphics.Paint.Style.STROKE
        it.strokeCap = android.graphics.Paint.Cap.BUTT
    }
}

/** Vuelca lo apuntado en [ElMontonDeRayas] y lo deja vacío para el siguiente montón. */
private fun DrawScope.volcarLasRayas(tinta: Color, gordo: Float) {
    if (ElMontonDeRayas.cuantos == 0) return
    val pincel = ELPINCEL_DE_LAS_RAYAS
    pincel.color = tinta.toArgb()
    pincel.strokeWidth = gordo
    drawIntoCanvas {
        it.nativeCanvas.drawLines(ElMontonDeRayas.puntos, 0, ElMontonDeRayas.cuantos, pincel)
    }
    ElMontonDeRayas.vaciar()
}

/**
 * **El pixelado: el croquis pintado de verdad a menos resolución.**
 *
 * No es una cuadrícula pintada encima ni un filtro que emborrona: la escena se pinta en un
 * lienzo pequeño —uno de cada tantos puntos— y se amplía **sin suavizar**, que es exactamente
 * lo que hacía una pantalla de las de antes. La diferencia se nota: los cuadros salen
 * alineados entre sí, girar la vista los mueve como movería cualquier otra cosa del dibujo, y
 * un trazo fino desaparece o se hace un bloque entero según dónde caiga, que es lo que le pasa
 * a un dibujo de verdad a poca resolución.
 *
 * Para que salga bien hay que encoger **tres cosas a la vez** y no solo el lienzo: el aumento
 * de la cámara, el tamaño de la pantalla que se le dice y **la densidad** —de ella salen todos
 * los gruesos en `dp`—. Encogiendo solo el lienzo, lo que se conseguía era un recorte.
 *
 * Se guarda el lienzo pequeño de un fotograma al siguiente: fabricar un mapa de bits por
 * fotograma es de lo más caro que hay, y mientras no cambie el tamaño vale el mismo.
 */
private class ElCuadernoDelPixelado {
    private var lienzo: ImageBitmap? = null
    private var ancho = 0
    private var alto = 0
    val brocha = CanvasDrawScope()

    fun lienzoDe(w: Int, h: Int): ImageBitmap {
        val suyo = lienzo
        if (suyo != null && ancho == w && alto == h) return suyo
        val nuevo = ImageBitmap(w, h)
        lienzo = nuevo
        ancho = w
        alto = h
        return nuevo
    }
}

/**
 * **La cabeza del trazo en curso, ya pintada.**
 *
 * Es el «no vuelvas a cargar el bloque que ya está» de los juegos de bloques, aplicado al
 * trazo que se está haciendo: lo que ya se ha trazado no cambia de un fotograma al
 * siguiente, así que se pinta una vez en este lienzo y cada fotograma solo lo vuelca. Se
 * repinta cuando la cabeza crece un trozo más —[TROZO_DE_LA_MANO] puntos— o cuando
 * cambia desde dónde se mira, porque entonces sí cambia lo que hay que ver.
 *
 * Guarda el lienzo de un trazo al siguiente: fabricar un mapa de bits del tamaño de la
 * pantalla es de lo más caro que hay.
 */
private class ElTrazoAsentado {
    var lienzo: ImageBitmap? = null
        private set
    private var ancho = 0
    private var alto = 0
    val brocha = CanvasDrawScope()

    /** Hasta qué punto (excluido) del trazo en curso está pintado, y con qué vista. */
    private var hasta = 0
    private var camara: Camara3D? = null
    private var w = 0.0
    private var h = 0.0

    /** Si la pluma pudo pintar la cabeza; si no, el trazo entero va por el camino viejo. */
    var pintada = false
        private set

    fun lienzoDe(w: Int, h: Int): ImageBitmap {
        val suyo = lienzo
        if (suyo != null && ancho == w && alto == h) return suyo
        val nuevo = ImageBitmap(w, h)
        lienzo = nuevo
        ancho = w
        alto = h
        return nuevo
    }

    /**
     * Hasta qué punto llega la cabeza de un trazo de [n] puntos: todo menos la cola, en
     * trozos enteros; cero si aún no hay un trozo. Un trazo más corto que la cabeza
     * pintada es un trazo nuevo: se olvida lo que había.
     */
    fun cabezaPara(n: Int): Int {
        if (n < hasta) olvidar()
        val asentados = n - COLA_DE_LA_MANO
        if (asentados < TROZO_DE_LA_MANO) return 0
        return (asentados / TROZO_DE_LA_MANO) * TROZO_DE_LA_MANO
    }

    fun hayQueRepintar(cabezaHasta: Int, camara: Camara3D, w: Double, h: Double): Boolean =
        cabezaHasta != hasta || camara != this.camara || w != this.w || h != this.h

    fun apuntar(cabezaHasta: Int, camara: Camara3D, w: Double, h: Double, pintada: Boolean) {
        hasta = cabezaHasta
        this.camara = camara
        this.w = w
        this.h = h
        this.pintada = pintada
    }

    fun olvidar() {
        hasta = 0
        camara = null
        pintada = false
    }
}

/** Cuántos puntos del trazo en curso siguen cociéndose por muestra: la cola que se mueve. */
private const val COLA_DE_LA_MANO = 48

/** De cuántos en cuántos puntos se asienta la cabeza. */
private const val TROZO_DE_LA_MANO = 32

/** Cuántos puntos comparten cabeza y cola, para que las puntas de una queden bajo la otra. */
private const val SOLAPE_DE_LA_MANO = 6

/**
 * Hasta cuántos puntos se cuece el trazo en curso con la pluma. Estaba en 600 cociendo
 * entero por muestra; por trozos, la cabeza se cuece una vez cada [TROZO_DE_LA_MANO] puntos
 * y la cola es siempre corta, así que cabe mucho más.
 */
private const val TOPE_EN_LA_MANO = 2400

/**
 * De cuántos puntos de pantalla se hace un cuadro, según lo pixelado que se pida.
 *
 * Uno es sin pixelar. Va a saltos enteros a propósito: con un factor a medias, los cuadros no
 * caen todos del mismo tamaño —unos de tres puntos y otros de cuatro— y lo que se ve es una
 * trama irregular en vez de una rejilla.
 */
private fun cuantoPixela(cuanto: Double): Int =
    if (cuanto <= 0.01) 1 else (1 + cuanto * (LO_MAS_PIXELADO - 1)).toInt().coerceIn(1, LO_MAS_PIXELADO)

/** Lo más grande que se deja un cuadro, en puntos de pantalla. */
private const val LO_MAS_PIXELADO = 12

/**
 * **La apertura del objetivo: qué está enfocado y cuánto se deshace lo demás.**
 *
 * Es lo que hace una cámara con el diafragma abierto, y aquí sale casi solo: el croquis ya se
 * pinta **ordenado por lo lejos que está cada cosa** —es el algoritmo del pintor, ver
 * [pintarLaEscena]—, así que la hondura de cada trazo ya está calculada. Lo único que falta es
 * decidir, con ella, si el trazo sale nítido o blando.
 *
 * Y blando no se hace difuminando la pantalla: se hace **pintando el trazo varias veces, cada
 * vez más ancho y más flojo**. Es la misma escalera del tubo encendido puesta al revés —allí
 * las capas suman para deslumbrar, aquí se reparten para deshacer— y por eso vale para
 * cualquier punta sin escribir nada nuevo: la silueta la sigue dando la tinta.
 *
 * Va en un objeto suelto por lo mismo que [LaCalidad]: lo pregunta el pintado a cuatro niveles
 * de hondura y va en un solo hilo.
 */
internal object LaApertura {
    /** Cuánto se abre, de cero —todo nítido— a uno. */
    var cuanto = 0.0

    /** A qué hondura está el plano enfocado, medida por la mirada. */
    var enfocadoEn = 0.0

    /** Si hay algo que desenfocar. */
    val hayQueDesenfocar: Boolean get() = cuanto > 0.005

    /**
     * Cuánto se deshace un trazo que está a esa hondura, en veces su ancho.
     *
     * Cero es nítido. Crece con lo lejos que esté del plano enfocado y con lo abierta que
     * vaya la apertura, y se para en un tope: pasado cierto punto, una mancha más grande no se
     * lee como más desenfocada, se lee como un borrón que tapa.
     */
    fun loBlandoDe(hondura: Double, zoom: Double): Float {
        if (!hayQueDesenfocar) return 0f
        val fuera = kotlin.math.abs(hondura - enfocadoEn) * zoom
        return (fuera / DISTANCIA_DEL_DESENFOQUE * cuanto).toFloat()
            .coerceIn(0f, LO_MAS_BLANDO)
    }
}

/**
 * A cuántos píxeles de pantalla del plano de enfoque se alcanza el desenfoque entero.
 *
 * Medido en la pantalla y no en el mundo: lo que uno ve de una foto desenfocada depende de la
 * escala a la que la mira, y aquí la escala es el aumento. Así, alejarse no lo deshace todo.
 */
private const val DISTANCIA_DEL_DESENFOQUE = 900.0

/** Y lo más blando que se deja: en veces el ancho del trazo. */
private const val LO_MAS_BLANDO = 6f

/** En cuántas pasadas se reparte una mancha desenfocada. */
private const val PASADAS_DEL_DESENFOQUE = 5

/**
 * Cuánto tapa cada pasada de una mancha desenfocada.
 *
 * Bajo: son cinco puestas una encima de otra, así que lo que se ve es lo que suman. Alto, un
 * trazo desenfocado saldría **más** marcado que uno nítido, que es justo al revés de lo que
 * hace un objetivo.
 */
private const val ALFA_DE_UNA_PASADA = 0.30f

/**
 * **Con cuánto detalle se está pintando ahora mismo.**
 *
 * Es la idea que hace que un juego corra en un teléfono modesto: **lo que se está moviendo
 * no se mira con lupa**. Al girar la vista o acercarse, el croquis entero se vuelve a
 * proyectar en cada fotograma —eso no hay cómo evitarlo, la vista es otra—, así que lo que
 * se puede bajar es cuánto detalle se monta en cada uno. En movimiento nadie distingue si
 * una curva lleva sesenta puntos o veinticinco, y sí distingue si el croquis se mueve a
 * tirones. Parado, se repinta entero y fino; ahí sí se mira de cerca. Ver [ElDetalle].
 *
 * Va en un objeto suelto y no en un parámetro por lo mismo que [Borrador] y los cuadernos
 * del contorno: esto lo leen quince funciones a cuatro niveles de hondura, y pasarlo de mano
 * en mano ensuciaría todas sus firmas para decir siempre lo mismo. El pintado va en un solo
 * hilo, así que un valor suelto vale.
 *
 * **Se apaga al acabar la escena.** Las miniaturas de las vistas usan el mismo pintado y no
 * se están moviendo: se pintan una vez y se quedan, así que van finas siempre.
 */
internal object LaCalidad {
    /** Si la vista se está moviendo ahora mismo. */
    var moviendo = false

    /**
     * Cuánto se agranda lo que cuenta como «dos puntos que caen juntos».
     *
     * **Y no depende de si la vista se está moviendo, a propósito.** Lo hacía —moviéndose se
     * aligeraba dos veces y pico más— y eso es de las optimizaciones que se ven: girando, las
     * rayas del croquis cambiaban de forma y al soltar volvían a la suya. Lo que se lee de
     * eso no es «va suave», es **que el dibujo no está quieto en el espacio**, que es justo
     * lo contrario de lo que tiene que decir un croquis: un punto está donde está, se mire
     * como se mire y se mueva uno como se mueva.
     *
     * Lo que sí queda es [elDelAparato], que mide cuánto le está costando a este teléfono y
     * se mueve despacio y a pasitos: eso no se ata al gesto, así que no hay nada que salte al
     * agarrar ni al soltar.
     */
    val grano: Float get() = elDelAparato

    /**
     * **Y cuánto más basto hay que ir en este aparato**, medido y no supuesto.
     *
     * Es lo que hace un juego con la resolución cuando la cosa se pone cuesta arriba: si los
     * fotogramas se alargan, baja el detalle hasta que vuelven a caber. Aquí es lo mismo con
     * lo que cuesta montar la escena.
     *
     * **Medido y no supuesto** a propósito. Se podría mirar la versión de Android o el modelo
     * del teléfono y decidir por ahí, y sería adivinar dos veces: un teléfono de hace cuatro
     * años con un croquis de diez rayas va sobrado, y uno nuevo con un croquis de mil no. Lo
     * que importa no es el aparato, es **si le está costando ahora mismo**, y eso se sabe
     * mirando el reloj.
     *
     * Sube y baja despacio y a pasitos. Es un lazo —bajar el detalle abarata el fotograma, y
     * un fotograma barato invita a subirlo otra vez— así que corrigiendo de golpe lo que se
     * consigue es que respire: basto, fino, basto, fino. Moviéndose poco a poco, se queda
     * quieto en el punto donde los fotogramas caben.
     */
    private var elDelAparato = 1f

    /** Lo que costó montar la escena la última vez, suavizado. En milisegundos. */
    private var cuantoCuesta = 0.0

    /** Se apunta lo que ha costado este fotograma y se ajusta el detalle para el siguiente. */
    fun apuntarLoQueCosto(nanos: Long) {
        val ahora = nanos / 1_000_000.0
        // Suavizado largo: lo que manda es cómo va la cosa, no el fotograma en el que se
        // abrió un panel.
        cuantoCuesta = if (cuantoCuesta <= 0.0) ahora else cuantoCuesta * 0.9 + ahora * 0.1
        elDelAparato = when {
            cuantoCuesta > UN_FOTOGRAMA_LARGO -> (elDelAparato + UN_PASO).coerceAtMost(LO_MAS_BASTO)
            cuantoCuesta < UN_FOTOGRAMA_COMODO -> (elDelAparato - UN_PASO / 2).coerceAtLeast(1f)
            else -> elDelAparato
        }
    }

    /** Cuántos peldaños como mucho lleva el degradado de una luz. Ver [pintarTrazo]. */
    val peldanosDeLaLuz: Int get() = if (moviendo) PELDANOS_EN_MOVIMIENTO else PELDANOS_MAXIMOS

    /**
     * **Cuántos escalones de tono lleva un trazo, y por qué nunca ninguno.**
     *
     * Moviendo la vista se pintaba **a tono pleno**: un solo escalón, o sea el trazo plano,
     * sin sombra. Era la optimización más barata que había —el tono no toca la geometría— y
     * es de las que se ven: el usuario lo dijo mirándolo, «al moverlo las sombras
     * desaparecen y al soltar vuelven». Y lo que eso cuenta no es «va suave»: cuenta que el
     * croquis **no es una cosa que esté ahí**, porque una cosa que está ahí no se aplana
     * cuando la miras desde otro lado. Es el mismo motivo por el que [grano] tampoco depende
     * de si la vista se mueve.
     *
     * Así que el tono se queda **siempre**. Lo que sí puede recortarse es cuántos escalones,
     * y solo si a este aparato le está costando de verdad ([elDelAparato], medido y no
     * supuesto): la mitad de escalones sigue leyéndose como un tubo; ninguno, no.
     */
    val escalonesDeTono: Int
        get() = if (moviendo && elDelAparato > APARATO_APURADO) ESCALONES_A_LA_MITAD
        else ESCALONES_ENTEROS

    /**
     * Si toca volver a pintar las hojas por delante de cada luz.
     *
     * Es lo más caro que hay por trazo —repinta hojas enteras, una por luz— y lo que arregla
     * es un matiz: que un tubo detrás de un tabique se vea atenuado. Moviéndose, no.
     */
    val luzQueRespetaLasHojas: Boolean get() = !moviendo
}

/**
 * **Cuánto detalle toca, decidido mirando si la vista se ha movido.**
 *
 * Lo lleva el lienzo entre un pintado y el siguiente. Dos cosas y una trampa:
 *
 * - Si la cámara no es la del pintado anterior, la vista se está moviendo: a lo basto.
 * - Si lo es, está quieta: fino.
 * - Y la trampa: **parado no llegan más pintados**. Sin nada que cambie, nadie vuelve a
 *   pintar, así que el último fotograma de un giro se quedaría basto para siempre. De eso se
 *   encarga [pulso], que salta un rato después de que la vista pare y, por estar leído
 *   dentro del pintado, lo caduca y obliga a repetirlo — ya con la vista quieta.
 */
private class ElDetalle {
    /** Salta cuando la vista lleva un rato quieta. Ver arriba. */
    var pulso by mutableStateOf(0)

    private var laUltima: Camara3D? = null
    private var elUltimoPulso = -1

    /** Si la vista se mueve, dicho desde dentro del pintado. */
    fun seMueve(camara: Camara3D): Boolean {
        val ahora = pulso
        // Si el reloj ha saltado desde el pintado anterior es que la vista ya paró, y este
        // pintado es justo el que se pidió para afinarla.
        val yaParo = ahora != elUltimoPulso
        // Y el primero de todos va fino: abrir el croquis no es moverlo, y lo primero que se
        // ve de una aplicación no puede ser su versión basta.
        val movida = laUltima != null && camara != laUltima
        laUltima = camara
        elUltimoPulso = ahora
        return movida && !yaParo
    }
}

/** Lo que se espera, sin que la vista se mueva, antes de repintarla fina. */
private const val LO_QUE_SE_ESPERA_A_AFINAR = 110L


/**
 * A partir de cuántos milisegundos se considera que un fotograma se está alargando.
 *
 * A sesenta fotogramas por segundo hay dieciséis y medio para todo, y montar la escena es
 * **una parte** de ese todo: falta pintarla, componerla y enseñarla. Nueve deja sitio para lo
 * demás; por debajo de cuatro y medio sobra tanto que se puede volver a subir el detalle.
 */
private const val UN_FOTOGRAMA_LARGO = 9.0
private const val UN_FOTOGRAMA_COMODO = 4.5

/** Cuánto se mueve el detalle de un fotograma al siguiente, y hasta dónde. */
private const val UN_PASO = 0.03f
private const val LO_MAS_BASTO = 2.2f

/**
 * A partir de qué holgura del aparato se recortan los escalones de tono, y a cuántos.
 *
 * Ocho es lo que hace que una cinta girando se lea como volumen sin parecer un aerógrafo;
 * cuatro sigue teniendo lomo y flanco, que es lo que hace falta para no perder el bulto.
 * Ver [LaCalidad.escalonesDeTono].
 */
private const val APARATO_APURADO = 1.4f
private const val ESCALONES_ENTEROS = 8
private const val ESCALONES_A_LA_MITAD = 4

/** Ver [LaCalidad.peldanosDeLaLuz]. */
private const val PELDANOS_EN_MOVIMIENTO = 5

/**
 * **Todo el croquis, pintado**: el suelo, el eje del espejo y las piezas por hondura.
 *
 * Aparte de la pantalla porque lo pinta **dos veces**: en el lienzo, a tamaño completo y
 * desde donde se está mirando, y en cada miniatura de la galería de vistas, pequeñito y
 * desde la cámara que se guardó. Una miniatura que dibujara otra cosa —un icono, un
 * cuadrado de color— no diría a qué vista se vuelve, que es lo único que hace falta saber
 * de ella.
 *
 * Lo que no entra aquí es lo que está en la mano: el trazo en curso, la bolita, el apoyo.
 * Eso no es el croquis, es lo que se está haciendo con él.
 */
internal fun DrawScope.pintarLaEscena(
    croquis: Croquis,
    camara: Camara3D,
    w: Double,
    h: Double,
    seleccion: Set<String>,
    imagenes: Map<String, android.graphics.Bitmap>,
    espejoPuesto: Boolean,
    /** De qué color va lo que no es dibujo —la hoja, su retícula—, para que se vea. */
    contraste: Color = Color.Gray,
    /** Si se mira desde dentro del sitio. Ver [LoQueSePinta.puestoEnElSitio]. */
    puestoEnElSitio: Boolean = false,
    /**
     * Lo que se está subrayando **ahora mismo**, si es que se está subrayando.
     *
     * Entra aquí y no se pinta aparte para que se una a su montón, que es lo que hace que
     * no se sume con lo que ya había. Pintado por su cuenta tiene su propia capa, así que
     * pasar el rotulador por encima de otra pasada **se veía más oscuro hasta soltar el
     * dedo** — y al soltarlo se aclaraba de golpe. Un rotulador no hace eso ni mientras se
     * está pasando. Ver [pintarLosRodillos].
     */
    subrayandoAhora: List<Trazo3D> = emptyList()
) {
    pintarSuelo(camara, w, h, contraste, puestoEnElSitio, croquis.pautaDeLasHojas)

    // Las sombras, entre el suelo y el dibujo: están en el suelo, así que van debajo de
    // todo lo que se haya dibujado y encima de la retícula.
    // La luna primero: su sombra es la floja, y si las dos alumbran a la vez la del sol
    // manda —que es lo que pasa cuando salen las dos—.
    croquis.luna?.let { pintarLasSombras(croquis, it, camara, w, h, LA_DE_LA_LUNA) }
    croquis.sol?.let { pintarLasSombras(croquis, it, camara, w, h, LA_DEL_SOL) }

    // El eje del espejo, antes que nada: es la mesa, no el dibujo.
    val espejo = croquis.espejo?.takeIf { espejoPuesto }
    espejo?.let { pintarElEspejo(it, camara, w, h) }

    // Todo junto y ordenado por profundidad: las láminas y los trazos se tapan entre
    // sí, así que pintarlos en dos tandas dejaría siempre unos encima de otros.
    // Lo cerca y lo lejos que llega el croquis, para que el cuerpo de la tinta se
    // reparta sobre lo que hay y no sobre una escala inventada.
    val (hondoCerca, hondoLejos) = loHondoQueLlega(croquis, camara)

    // **La pluma del fotograma**: la base de cámara congelada y la luz del mundo, pagadas
    // UNA vez. La base garantiza igualdad exacta con aPantalla (tiene prueba); la luz sale
    // del sol del croquis o de la del aire — fija al MUNDO, que es lo que hace que el lado
    // claro de un trazo no ruede con la cámara. Ver [BaseDeCamara] y [luzDelMundo].
    val base = camara.base(w, h)
    val luzDePluma = luzDelMundo(croquis)

    // **El reparto por hondura, sin fabricar un cierre por pieza.**
    //
    // Era una lista de parejas «hondura, cómo se pinta», y esa segunda mitad es **un objeto
    // nuevo por trazo y por fotograma**: con quinientos trazos, quinientos objetos que nacen
    // y mueren sesenta veces por segundo, más otros tantos por la pareja, más la lista que
    // devolvía ordenarlos. Nada de eso se ve; lo que se ve es el tirón cuando al basurero le
    // toca recogerlo.
    //
    // Ahora son tres listas planas —qué es cada pieza, cuál es, y a qué hondura— y un orden
    // de índices que se reaprovecha entre fotogramas. Pintar es recorrer ese orden y mirar
    // qué es cada uno, que es lo que el cierre estaba envolviendo.
    Borrador.vaciar()

    // **Las hojas van debajo del dibujo, y no ordenadas con él.**
    //
    // Entraban en el reparto por hondura como una pieza más, y eso es tratarlas como lo que
    // no son. Una hoja es la mesa: ocupa toda la pantalla y su hondura es un promedio de algo
    // que llega hasta el fondo, así que en cuanto uno gira, ese promedio cruza al de lo
    // dibujado y **la mesa pasa por delante del dibujo de golpe** — «giro un poco más y las
    // tintas se van detrás del plano». Y encima es lo que menos sentido tiene: lo que se
    // dibuja sobre una hoja está clavado a ella, medio cuerpo a cada lado, así que no hay una
    // respuesta buena a quién va delante.
    //
    // Debajo de todo y translúcida, que es lo que es una mesa de dibujo: se ve a través de
    // ella lo que haya detrás, y lo que uno ha dibujado se ve siempre y de su color.
    //
    // **Con el contraste del fondo.** Se pintaba con el gris de fábrica, así que sobre un
    // fondo de pizarra los cantos y la retícula se quedaban en un gris que no está ni en el
    // fondo ni en el dibujo: se veía la mesa peor cuanto más oscuro era el papel.
    // De lejos a cerca, que es como se pintan las cosas que se tapan entre sí.
    val lasHojas = croquis.laminas
        .map { it to honduraDeLaHoja(it, camara) }
        .sortedByDescending { it.second }
    for ((l, _) in lasHojas) {
        pintarLamina(l, camara, w, h, contraste)
    }
    // Lo que puede asomar por fuera de la caja de un trazo: su grueso, y algo más si alumbra.
    fun loQueAsoma(t: Trazo3D): Double =
        (t.calibre ?: (t.grosor / camara.zoom)) * RESPLANDOR

    for ((n, t) in croquis.trazos.withIndex()) {
        if (t.oculto || esUnRodilloDePintura(t)) continue
        // **Lo que no se ve no se encola siquiera.** Un croquis crece, y en cuanto uno se
        // acerca a mirar un detalle la mayor parte de lo dibujado cae fuera del cristal.
        // Descartarlo aquí —con dos proyecciones, no con las de todos sus puntos— es lo que
        // hace que un fotograma cueste lo que se ve en él y no lo que se lleva dibujado.
        if (fueraDeLaVista(t.puntos, camara, w, h, loQueAsoma(t))) continue
        // **Lo elegido se pinta de otro color, no con un adorno alrededor.** Un halo
        // se pierde entre trazos gordos y encima de un fondo con retícula; cambiar la
        // tinta entera se ve de un vistazo desde cualquier ángulo y a cualquier
        // aumento, que es lo que tiene que hacer una selección.
        Borrador.apuntar(ES_UN_TRAZO, n, hondura(t.puntos, camara))
    }
    // Las imágenes, cada una a su hondura como todo lo demás.
    for ((k, i) in croquis.imagenes.withIndex()) {
        if (i.oculto) continue
        if (!imagenes.containsKey(i.ruta)) continue
        if (fueraDeLaVista(i.esquinas, camara, w, h, 0.0)) continue
        Borrador.apuntar(ES_UNA_IMAGEN, k, hondura(i.esquinas, camara))
    }

    // **Los rodillos, por grupos y de una sola pasada.**
    //
    // Un rodillo deja tinta translúcida, así que dos pasadas cruzadas se suman y el
    // cruce sale del doble de oscuro: uno va a resaltar una zona, la repasa un par de
    // veces para cubrirla entera y lo que queda es un mapa de manchas de lo que hizo la
    // mano. Pintando el grupo entero **en una capa aparte y volcándola de una vez con
    // su transparencia**, dentro se pisan cuanto quieran —dentro son opacos— y lo que
    // llega a la pantalla es una mancha de un solo tono. Se puede rayar la zona como
    // sea, que el color es el mismo en todas partes.
    //
    // Un grupo por tinta y por hoja: dos colores distintos sí tienen que verse
    // superpuestos, y dos hojas distintas no son la misma capa.
    //
    // Y solo se agrupa **si hay alguno**: montar un mapa de grupos en cada fotograma para
    // descubrir que no hay ni un rodillo es el trabajo más caro de todo esto en el croquis
    // más corriente, que es el que no tiene ninguno.
    // Y sin fabricar la lista para descubrir que está vacía: en el croquis corriente no hay
    // ni un rodillo, y `filter` recorre los quinientos trazos y monta una lista de cero
    // elementos en cada fotograma para averiguarlo. Mirar si hay alguno no monta nada.
    // Los encendidos no entran: un rodillo de luz no es una mano de pintura, es una banda
    // encendida, y lo suyo es sumarse con lo que haya debajo en vez de fundirse con ello. Va
    // por el camino de siempre, como cualquier otra tinta que alumbre. Ver [Tinta.de].
    val hayRodillos = subrayandoAhora.isNotEmpty() ||
        croquis.trazos.any { esUnRodilloDePintura(it) }
    if (hayRodillos) {
        val losRodillos = croquis.trazos.filter { esUnRodilloDePintura(it) } + subrayandoAhora
        for (grupo in rodillosPorPlano(losRodillos, seleccion)) {
            val delGrupo =
                grupo.filterNot { fueraDeLaVista(it.puntos, camara, w, h, loQueAsoma(it)) }
            if (delGrupo.isEmpty()) continue
            // La hondura, la del primero que se ve: los del grupo comparten tinta y hoja, así
            // que están juntos. Aplanar sus puntos para promediarlos era recorrer el grupo.
            Borrador.apuntar(
                ES_UN_RODILLO, Borrador.rodillos.size,
                hondura(delGrupo.first().puntos, camara)
            )
            Borrador.rodillos += delGrupo
        }
    }

    // De atrás adelante, ordenando **números y no piezas**: ver [Borrador.enOrden].
    Borrador.enOrden { k ->
        when (Borrador.queEs[k]) {
            ES_UN_TRAZO -> {
                val t = croquis.trazos[Borrador.cual[k]]
                // **Lo elegido se pinta de otro color, no con un adorno alrededor.** Un halo
                // se pierde entre trazos gordos y encima de un fondo con retícula; cambiar la
                // tinta entera se ve de un vistazo desde cualquier ángulo y a cualquier
                // aumento, que es lo que tiene que hacer una selección.
                val tinta = if (t.id in seleccion) COLOR_DE_LO_ELEGIDO else t.color
                val cuantaLuz = t.luz * croquis.luces.cuanto
                // **El régimen nuevo, por trazo y no por época.** Si trae tiempos, la pluma
                // lo pinta fijo al mundo; si la pluma no sabe (puntas con geometría
                // especial), cae sola al camino de siempre devolviendo false. LUZ y lo que
                // alumbra van a propósito por el viejo: su neón y su respeto a las hojas
                // aún no viven en la pluma. Ver [pintarTrazoPluma].
                val esqueleto =
                    if (t.tiempos != null && t.pincel.vigente != Pincel.LUZ &&
                        !t.punta.alumbra && !t.punta.plana
                    ) {
                        ElArmarioDeLosEsqueletos.de(t, nivelDePluma(t.calibre, camara.zoom))
                    } else {
                        null
                    }
                val laPintoLaPluma = esqueleto != null && pintarTrazoPluma(
                    if (t.id in seleccion) t.copy(color = COLOR_DE_LO_ELEGIDO) else t,
                    esqueleto, base, luzDePluma, LaCalidad.moviendo
                )
                // **El lápiz va por su cuenta y es lo más barato que hay.** Una raya lisa del
                // grueso que se pidió: es una anotación, no una pieza. Ver [pintarRayaLisa].
                if (!laPintoLaPluma && t.punta.plana) {
                    pintarRayaLisa(
                        t.puntos, parseColor(tinta, 255), t.calibre ?: t.grosor, t.opacidad,
                        camara, w, h
                    )
                } else if (!laPintoLaPluma) pintarTrazo(
                    t.puntos, tinta, t.grosor, t.pincel, camara, w, h,
                    hondoCerca, hondoLejos, t.calibre, t.presiones, t.opacidad,
                    // La llave de paso de las luces multiplica a lo que lleve el trazo: la
                    // mezcla que uno montó se sube y se baja entera. Ver [Luces3D].
                    cuantaLuz,
                    t.punta, t.ejeDeLaPunta, t.normal, t.normales, t.colorApagada
                )
                // **Y una luz respeta lo que tiene delante.**
                //
                // Las hojas se pintan todas al principio y por debajo de todo —hay una razón
                // para eso y sigue valiendo: una hoja llega hasta el fondo, así que su
                // hondura media cruza a la de lo dibujado en cuanto uno gira, y la mesa
                // pasaría por delante del dibujo de golpe—. Pero con una luz eso se nota mal
                // de verdad: un tubo que está **detrás** de un tabique se ve encendido a
                // través de él, porque suma sobre lo que ya hay pintado. Una luz de verdad
                // no atraviesa una pared.
                //
                // Así que, solo detrás de una luz, se vuelven a poner las hojas que le quedan
                // por delante. Como son translúcidas, lo que hacen es **atenuarla**, que es
                // exactamente lo que hace una superficie translúcida con una luz detrás: se
                // ve, pero apagada y a través de algo. Y solo las de delante, así que una luz
                // que está por delante de la hoja no se toca.
                if (cuantaLuz > 0.005 && LaCalidad.luzQueRespetaLasHojas &&
                    (t.punta.alumbra || t.pincel.vigente == Pincel.LUZ)
                ) {
                    val suya = hondura(t.puntos, camara)
                    for ((l, cuanHonda) in lasHojas) {
                        if (cuanHonda < suya) {
                            pintarLamina(l, camara, w, h, contraste)
                        }
                    }
                }
            }

            ES_UNA_IMAGEN -> {
                val i = croquis.imagenes[Borrador.cual[k]]
                imagenes[i.ruta]?.let { pintarImagen(i, it, camara, w, h, i.id in seleccion) }
            }

            else -> {
                val grupo = Borrador.rodillos[Borrador.cual[k]]
                val tinta =
                    if (grupo.first().id in seleccion) COLOR_DE_LO_ELEGIDO
                    else grupo.first().color
                pintarLosRodillos(grupo, tinta, camara, w, h)
            }
        }
    }
}

/**
 * Si un trazo es una mano de pintura de rodillo, que se pinta unida a su montón.
 *
 * El rodillo **de luz** no lo es: alumbra, y lo que hace una luz es sumarse. Ver [Tinta.de]
 * y [pintarLosRodillos].
 */
private fun esUnRodilloDePintura(t: Trazo3D): Boolean =
    !t.oculto && t.pincel.vigente == Pincel.RESALTADOR && !t.punta.alumbra

/**
 * Lo gordo que sale un trazo, **a ojo y sin recorrerlo**: para cuando cabe en un punto.
 *
 * Vale porque a esa escala el reparto del cuerpo no se distingue: lo que se ve es un punto
 * del tamaño del trazo, y de eso solo hace falta el orden de magnitud.
 */
private fun anchaMediaAproximada(enPantalla: Float, calibre: Double?, minimo: Float): Float =
    (enPantalla * if (calibre == null) CUERPO_MINIMO + CUERPO_RANGO / 2 else 1f)
        .coerceAtLeast(minimo)

/**
 * Cuántas veces el diámetro de un trazo se le conceden de recorrido antes de recorrerlo a
 * saltos.
 *
 * Ocho: un garabato apretado recorre bastante más pantalla que su caja, y pasarse por
 * arriba solo cuesta proyectar de más — pasarse por abajo se ve. Ver [pintarTrazo].
 */
private const val HOLGURA_DEL_SALTO = 8f

/** Qué es cada pieza del reparto por hondura. Ver [pintarLaEscena]. */
private const val ES_UN_TRAZO = 0
private const val ES_UNA_IMAGEN = 1
private const val ES_UN_RODILLO = 2

/**
 * **Los cuadernos de borrador del pintado**, que se reaprovechan de un fotograma al siguiente.
 *
 * Pintar la escena es una cuenta que se rehace sesenta veces por segundo y siempre con la
 * misma forma, así que las listas en las que se apunta el trabajo pueden ser las mismas. Sin
 * esto, cada fotograma dejaba atrás unas cuantas listas del tamaño del croquis, y eso no se
 * ve como memoria gastada: se ve como un tirón cada pocos segundos, cuando al basurero le
 * toca recogerlas — que es justo lo que no puede pasar mientras alguien está dibujando.
 *
 * Vale porque **todo esto se pinta en un solo hilo**, el de la pantalla, y una escena no se
 * pinta dentro de otra: la miniatura de una vista guardada y el lienzo son dos pasadas
 * seguidas, nunca anidadas.
 */
private object Borrador {
    /** Qué es cada pieza apuntada y cuál de las suyas es. */
    var queEs = IntArray(CABEN)
        private set
    var cual = IntArray(CABEN)
        private set

    /** Los grupos de rodillo, que sí son listas: son pocos y se arman una vez. */
    val rodillos = ArrayList<List<Trazo3D>>(16)

    /**
     * La hondura y el número de cada pieza, **metidos en un solo número**.
     *
     * Ordenar por hondura pide llevar juntos «lo lejos que está» y «quién es», y en cuanto eso
     * es una pareja de objetos vuelve el problema que se quería quitar: un objeto por pieza y
     * por fotograma. Metiendo la hondura en la mitad de arriba de un entero largo y el número
     * en la de abajo, **ordenar por el número entero ordena por hondura**, y eso se hace sobre
     * un array de los de siempre, sin fabricar nada.
     */
    private var claves = LongArray(CABEN)
    private var cuantas = 0

    fun vaciar() {
        cuantas = 0
        rodillos.clear()
    }

    fun apuntar(que: Int, suyo: Int, hondo: Double) {
        if (cuantas == queEs.size) {
            queEs = queEs.copyOf(cuantas * 2)
            cual = cual.copyOf(cuantas * 2)
            claves = claves.copyOf(cuantas * 2)
        }
        queEs[cuantas] = que
        cual[cuantas] = suyo
        claves[cuantas] = clave(hondo, cuantas)
        cuantas++
    }

    /** Recorre lo apuntado **de atrás adelante**, que es el orden en que se pinta. */
    inline fun enOrden(pintar: (Int) -> Unit) {
        val cuantas = ordenadas()
        for (k in cuantas - 1 downTo 0) pintar(quienEs(k))
    }

    fun ordenadas(): Int {
        java.util.Arrays.sort(claves, 0, cuantas)
        return cuantas
    }

    fun quienEs(k: Int): Int = (claves[k] and 0xFFFFFFFFL).toInt()

    /**
     * La hondura arriba y el número abajo.
     *
     * En coma flotante de treinta y dos bits, que da de sobra para ordenar —lo que se decide
     * aquí es quién va delante de quién, no cuánto—, y con el truco de siempre para que
     * ordenar los bits ordene los valores también con los negativos.
     */
    private fun clave(hondo: Double, suyo: Int): Long {
        val bits = java.lang.Float.floatToIntBits(hondo.toFloat())
        val ordenable = if (bits < 0) bits.inv() else bits xor Int.MIN_VALUE
        return (ordenable.toLong() shl 32) or (suyo.toLong() and 0xFFFFFFFFL)
    }

    /** Con cuántas piezas nace. Más que las de casi cualquier croquis, y crece si hace falta. */
    private const val CABEN = 256
}

/** En qué momento del gesto va el dedo. */
enum class Fase { BAJA, MUEVE, LEVANTA, CANCELA }

/** Con qué se dibuja —y la mano, que no dibuja: mueve la vista. */
enum class Herramienta3D {
    LAPIZ,
    LAMINA,
    ESFERA,

    /**
     * **El plano compuesto: una línea lo crea y la otra lo dobla.**
     *
     * Un plano de barrer sale prismático —la raya corrida hacia el fondo— y eso vale para un
     * muro, una mesa o un suelo. Pero medio croquis no es eso: el faldón que va del alero a
     * la esquina, la vela entre dos cables, el capó entre dos perfiles, la rampa que sube
     * torciéndose. Todas son lo mismo, **dos curvas y la regla que va de una a otra**, y es
     * la superficie más fácil de dar a mano porque las dos aristas se dibujan igualmente.
     *
     * Ya se podía hacer, pero pidiendo cuatro pasos: trazar dos líneas con el lápiz, coger la
     * flecha, marcar las dos y buscar «unir». Y con el lápiz **no se puede trazar en el
     * aire** —todo se apoya en la hoja que haya— así que la segunda arista salía pegada a la
     * primera superficie en vez de donde uno la quería. Aquí las dos líneas se trazan sobre
     * la pantalla, como se traza cualquier plano: se da la primera, se gira la vista, se da
     * la segunda, y lo de en medio sale solo. Ver [Croquis3DControlador.elPlanoAMedias].
     */
    PLANO_COMPUESTO,

    ESPEJO,
    SELECCION,
    BORRADOR,

    /**
     * **Licuar**: empujar y tirar de lo dibujado con el dedo, como el «3D Liquify» de
     * Feather. No traza nada; recoloca los puntos que caen bajo el pincel. Ver
     * [Croquis3DControlador.licuar].
     */
    LICUAR,
    MANO
}

/** Qué le está pidiendo la mano a la vista, según cuántos dedos haya apoyados. */
enum class Gesto { GIRAR, DESPLAZAR, LENTE }

private const val FONDO_EN_PANTALLA = 420.0

/**
 * Cuántos píxeles de arrastre son una vuelta entera de la vista.
 *
 * Setecientos: con novecientos había que barrer la pantalla dos veces para ver el otro
 * lado de lo dibujado, y girar dejaba de ser algo que se hace de paso. Menos de esto y el
 * dedo se pasa de largo buscando un ángulo concreto.
 */
const val VUELTA_ENTERA = 700f

/**
 * Lo más cerca y lo más lejos que llega el croquis, **de una pasada y sin listas de por medio**.
 *
 * Se sacaba juntando en una lista todos los puntos de todos los trazos y todas las esquinas
 * de todas las imágenes, y midiendo esa lista. Eso son **dos listas nuevas del tamaño del
 * croquis entero, dos veces por fotograma** —una aquí y otra para lo que está en la mano—, y
 * es de las cosas que no se notan con diez trazos y se notan mucho con mil: el basurero se
 * pone a trabajar justo mientras se dibuja. La cuenta es la misma; lo que sobraba eran las
 * listas.
 *
 * Ya solo hace falta para los croquis de antes del calibre, que reparten el cuerpo de la
 * tinta sobre lo que hay. Ver [pintarTrazo].
 */
private fun loHondoQueLlega(croquis: Croquis, camara: Camara3D): Pair<Double, Double> {
    // **Y una sola vez por croquis y por vista.**
    //
    // Lo piden las dos capas del lienzo, y la de la mano lo pide **con cada muestra del
    // lápiz**. En un croquis de antes del calibre eso es recorrer punto a punto todo lo
    // dibujado doscientas veces por segundo para sacar dos números que no han cambiado: ni el
    // croquis ni la cámara son otros entre una muestra y la siguiente. Guardado el último, la
    // segunda pregunta y las doscientas siguientes salen gratis.
    ElHondoGuardado.suyo(croquis, camara)?.let { return it }
    // **Y solo si hace falta.** Ya únicamente lo miran los trazos de antes del calibre; en un
    // croquis de hoy no lo mira nadie, así que recorrerlo entero dos veces por fotograma para
    // dárselo a quien no lo va a usar es trabajo tirado.
    if (croquis.trazos.all { it.calibre != null }) {
        return ElHondoGuardado.apuntar(croquis, camara, 0.0 to 0.0)
    }
    val a = camara.adelante
    var cerca = Double.MAX_VALUE
    var lejos = -Double.MAX_VALUE
    fun mirar(p: Pt3) {
        val hondo = escalar(p, a)
        if (hondo < cerca) cerca = hondo
        if (hondo > lejos) lejos = hondo
    }
    for (t in croquis.trazos) if (!t.oculto) for (p in t.puntos) mirar(p)
    for (i in croquis.imagenes) if (!i.oculto) for (p in i.esquinas) mirar(p)
    if (cerca > lejos) {
        // Sin nada dibujado, las hojas: es lo único que hay a lo que mirar. Ver
        // [Croquis.puntos].
        for (l in croquis.laminas) for (p in l.esquinas()) mirar(p)
    }
    return ElHondoGuardado.apuntar(
        croquis, camara, if (cerca > lejos) 0.0 to 0.0 else cerca to lejos
    )
}

/**
 * Lo último que contestó [loHondoQueLlega], para no volver a contestarlo igual.
 *
 * Uno solo y no un almacén: lo que se pregunta una y otra vez es **el croquis que se está
 * mirando desde donde se está mirando**, y eso es siempre el mismo par. Se comparan por
 * identidad porque los dos son valores que se sustituyen enteros al cambiar: si es el mismo
 * objeto, es el mismo croquis.
 */
private object ElHondoGuardado {
    private var croquis: Croquis? = null
    private var camara: Camara3D? = null
    private var respuesta: Pair<Double, Double>? = null

    fun suyo(deQuien: Croquis, desdeDonde: Camara3D): Pair<Double, Double>? =
        respuesta?.takeIf { croquis === deQuien && camara == desdeDonde }

    fun apuntar(
        deQuien: Croquis,
        desdeDonde: Camara3D,
        cual: Pair<Double, Double>
    ): Pair<Double, Double> {
        croquis = deQuien
        camara = desdeDonde
        respuesta = cual
        return cual
    }
}

/**
 * Lo lejos que está una hoja, **sin fabricarle sus esquinas**.
 *
 * Las esquinas de una hoja se calculan cada vez que se piden —es una lista nueva—, así que
 * pasarlas por la caja guardada era guardar una caja distinta en cada fotograma: el almacén se
 * llenaba de cajas de un solo uso y echaba a las de los trazos, que son las que sí sirven. El
 * medio de su perfil dice lo mismo y no fabrica nada. Ver [cajaDe].
 */
private fun honduraDeLaHoja(lamina: Lamina3D, camara: Camara3D): Double {
    lamina.esfera?.let { return escalar(it.centro, camara.adelante) }
    val perfil = lamina.perfil
    if (perfil.isEmpty()) return 0.0
    return escalar(perfil[perfil.size / 2], camara.adelante)
}

/**
 * **La caja de una pieza**: su centro y su radio en el mundo.
 *
 * Con ella se contestan de un vistazo las dos preguntas que se hacen en cada fotograma sobre
 * cada trazo del croquis —«¿se ve?» y «¿a qué distancia está, para pintarlo en orden?»— sin
 * recorrer sus puntos. Recorrerlos es lo que hacía que un croquis grande costara lo mismo
 * mirado de cerca que entero: la mayor parte de lo dibujado cae fuera de la pantalla en cuanto
 * uno se acerca, y aun así se proyectaba punto a punto para acabar descartándolo.
 *
 * Se guarda **por quién es la lista de puntos y no por cómo es**: los puntos de un trazo son
 * el mismo objeto mientras el trazo exista —nunca se tocan, se sustituye el trazo entero—, así
 * que compararla por identidad cuesta nada y compararla por contenido costaría justo lo que se
 * quería ahorrar.
 */
private class CajaDeLaPieza(val centro: Pt3, val radio: Double)

private class QuienEs(val cual: Any) {
    override fun hashCode(): Int = System.identityHashCode(cual)
    override fun equals(other: Any?): Boolean = other is QuienEs && other.cual === cual
}

private val cajasDeLasPiezas =
    object : LinkedHashMap<QuienEs, CajaDeLaPieza>(256, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<QuienEs, CajaDeLaPieza>
        ): Boolean = size > TOPE_DE_LAS_CAJAS
    }

private const val TOPE_DE_LAS_CAJAS = 1024

private fun cajaDe(puntos: List<Pt3>): CajaDeLaPieza =
    cajasDeLasPiezas.getOrPut(QuienEs(puntos)) {
        if (puntos.isEmpty()) return@getOrPut CajaDeLaPieza(Pt3(0.0, 0.0, 0.0), 0.0)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var z0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var z1 = -Double.MAX_VALUE
        for (q in puntos) {
            if (q.x < x0) x0 = q.x
            if (q.y < y0) y0 = q.y
            if (q.z < z0) z0 = q.z
            if (q.x > x1) x1 = q.x
            if (q.y > y1) y1 = q.y
            if (q.z > z1) z1 = q.z
        }
        val centro = Pt3((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
        CajaDeLaPieza(centro, largo(menos(Pt3(x1, y1, z1), centro)))
    }

/**
 * Si una pieza cae del todo fuera de la pantalla, **sin proyectar sus puntos**.
 *
 * Se proyectan dos: su centro y un punto de su borde, que dan dónde cae y cuánto ocupa. Con
 * eso ya se sabe si hay que molestarse en lo demás. El margen es lo que puede asomar por
 * fuera de su caja: el grueso del trazo, y algo más si alumbra.
 */
private fun fueraDeLaVista(
    puntos: List<Pt3>,
    camara: Camara3D,
    ancho: Double,
    alto: Double,
    margen: Double
): Boolean {
    if (puntos.isEmpty()) return true
    val caja = cajaDe(puntos)
    return fueraDeLaVista(caja.centro, caja.radio, camara, ancho, alto, margen)
}

/** Lo mismo, con la bola dada: para lo que no tiene puntos, como la sombra de un trazo. */
private fun fueraDeLaVista(
    centroEnElMundo: Pt3,
    radioEnElMundo: Double,
    camara: Camara3D,
    ancho: Double,
    alto: Double,
    margen: Double
): Boolean {
    val centro = camara.aPantalla(centroEnElMundo, ancho, alto)
    val orilla =
        camara.aPantalla(mas(centroEnElMundo, por(camara.derecha, radioEnElMundo)), ancho, alto)
    val radio = kotlin.math.hypot(orilla.x - centro.x, orilla.y - centro.y) + margen
    return centro.x + radio < 0 || centro.y + radio < 0 ||
        centro.x - radio > ancho || centro.y - radio > alto
}

/** Lo lejos que está un conjunto de puntos, para ordenar el pintado. */
private fun hondura(puntos: List<Pt3>, camara: Camara3D): Double {
    if (puntos.isEmpty()) return 0.0
    // El centro de su caja, que ya está sacado: sumar todos sus puntos en cada fotograma es
    // recorrer el croquis entero para ordenar veinte cosas. Ver [cajaDe].
    return escalar(cajaDe(puntos).centro, camara.adelante)
}
/**
 * Pinta un trazo: **una sola figura rellena, de ancho variable**.
 *
 * ## De dónde salían las bolas
 *
 * El ancho de un trazo crece con lo cerca que pasa —es lo que le da hondura—, y eso se
 * resolvía partiéndolo en **tiradas**: tramos seguidos que caen en la misma banda de
 * profundidad, cada uno pintado como una raya de su propio ancho. Y ahí estaba el
 * defecto: donde acaba una tirada y empieza la siguiente hay dos tapas redondas de
 * anchos distintos, una encima de otra. La más gorda asoma por debajo de la más fina y
 * deja **un bulto**, y con las capas que no van opacas —el brillo, la sombra— el sitio
 * donde se pisan sale además más cargado. Un trazo que cruzaba la escena en diagonal
 * pasaba por seis bandas y salía con seis cuentas ensartadas, como un rosario. Eso son
 * las bolas.
 *
 * Ahora no hay tiradas. Cada capa es **el contorno del trazo**, cerrado y relleno: se
 * recorre un lado apartándose media anchura, se le da la vuelta a la punta y se vuelve
 * por el otro lado. El ancho puede cambiar en cada punto sin que aparezca ninguna
 * juntura, porque no hay junturas: es una figura sola. Y por eso el trazo se ve firme —el
 * borde lo pone el propio relleno, no la suma de veinte rayas—.
 *
 * ## Y las curvas, curvas
 *
 * Cada lado del contorno pasa por sus puntos con cuadráticas por los puntos medios, que
 * es el trazado de toda la vida para que una ristra de puntos salga suave. Uniendo con
 * rectas, un trazo despacio —que trae los puntos casi pegados— enseña cada esquinita.
 *
 * ## El volumen se lo da el tamaño, no el sombreado
 *
 * El rotulador llevaba encima una silueta oscura, un cuerpo corrido hacia la luz y un
 * brillo, más un halo alrededor, para que se leyera como un tubo. Eso no se ve como un
 * tubo: se ve como **una raya de dos colores con sombra alrededor**, y el color que se
 * eligió en la barra no aparece limpio en ninguna parte. Ahora el trazo va del color que
 * se pidió, liso y de un solo tono.
 *
 * Lo que lo hace sólido es que **mide lo que mide en el espacio** ([Trazo3D.calibre]): al
 * acercarse se ve gordo, como cualquier cosa que está ahí. Eso es un sólido; lo otro era
 * un dibujo de un sólido.
 *
 * Las puntas que sí van por capas —el listón y la plumilla— las conservan, porque ahí el
 * relieve **es** la punta: lo único que distingue un listón de una raya gorda es su arista.
 * Ver [capa].
 */
/**
 * **Una raya lisa en el espacio**: la del lápiz de anotar. Se proyectan sus puntos y se unen
 * con cuadráticas por los puntos medios —el mismo trazado suave que usa el lienzo plano— con
 * el grueso que se pidió y el color tal cual. Sin sección barrida, sin tono y sin pulso:
 * cuesta una fracción de lo que cuesta un trazo con bulto, que es lo que conviene a lo que se
 * traza a puñados. Ver [PuntaDelPincel.plana].
 */
internal fun DrawScope.pintarRayaLisa(
    puntos: List<Pt3>,
    tinta: Int,
    calibre: Double,
    opacidad: Double,
    camara: Camara3D,
    ancho: Double,
    alto: Double
) {
    if (puntos.size < 2) return
    val base = camara.base(ancho, alto)
    val camino = Path()
    var previo: Pt? = null
    for (p in puntos) {
        val v = base.aPantalla(p)
        val anterior = previo
        if (anterior == null) {
            camino.moveTo(v.x.toFloat(), v.y.toFloat())
        } else {
            camino.quadraticTo(
                anterior.x.toFloat(), anterior.y.toFloat(),
                ((anterior.x + v.x) / 2).toFloat(), ((anterior.y + v.y) / 2).toFloat()
            )
        }
        previo = v
    }
    previo?.let { camino.lineTo(it.x.toFloat(), it.y.toFloat()) }
    val grueso = (calibre * camara.zoom).dp.toPx().coerceAtLeast(1f)
    drawPath(
        camino,
        Color(tinta).copy(alpha = opacidad.toFloat().coerceIn(0f, 1f)),
        style = Stroke(width = grueso, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

internal fun DrawScope.pintarTrazo(
    puntos: List<Pt3>,
    color: String,
    grosor: Double,
    pincel: Pincel,
    camara: Camara3D,
    ancho: Double,
    alto: Double,
    hondoCerca: Double = 0.0,
    hondoLejos: Double = 0.0,
    /** Lo que mide en el mundo, si lo sabe. Ver [Trazo3D.calibre]. */
    calibre: Double? = null,
    /** Cuánto apretaba la mano en cada punto. Ver [Trazo3D.presiones]. */
    presiones: List<Double>? = null,
    /** Cuánto tapa: uno es opaca. Ver [Trazo3D.opacidad]. */
    opacidad: Double = 1.0,
    /** Cuánto alumbra, de cero a uno. Solo lo mira la tinta de luz. Ver [Trazo3D.luz]. */
    luz: Double = 1.0,
    /** Cómo está la punta: lo ancha, lo alta, ladeada y con pulso. */
    punta: PuntaDelPincel = PuntaDelPincel(),
    /** Hacia dónde mira su canto en el mundo. Ver [Trazo3D.ejeDeLaPunta]. */
    ejeDeLaPunta: Pt3? = null,
    /** La normal de la superficie sobre la que se trazó. Ver [Trazo3D.normal]. */
    normal: Pt3? = null,
    /** Y la de cada punto, si la hoja se curva. Ver [Trazo3D.normales]. */
    normales: List<Pt3>? = null,
    /** De qué color está el tubo apagado, si lleva uno propio. Ver [Trazo3D.colorApagada]. */
    colorApagada: String? = null
) {
    if (puntos.size < 2) return
    // **El rodillo con plano no se pinta aquí en absoluto.**
    //
    // Va con la escena, unido a su montón, que es lo que impide que dos pasadas se sumen.
    // Y la puerta está arriba del todo a propósito: más abajo hay un atajo para lo que ya se
    // ve como una raya, y una banda fina se colaba por él —así que el mismo subrayado se
    // pintaba dos veces, una aquí y otra con la escena, y donde se pisaban salía más
    // oscuro—. Ver [pintarLaEscena] y [pintarLosRodillos].
    // Y el encendido **sí** pasa por aquí: no se une a ningún montón, porque lo que hace una
    // luz es justo lo contrario —sumarse—. Ver [Tinta.de].
    if (pincel.vigente == Pincel.RESALTADOR && normal != null && !punta.alumbra) return
    // Lo que tapa el trazo multiplica a todo: al color, y a las capas del listón, que
    // llevan su propio alfa. Así una arista translúcida sigue teniendo arista.
    fun conLoQueTapa(base: Int): Int = (base * opacidad).toInt().coerceIn(0, 255)
    // **Una luz tiene dos colores: el del cristal apagado y el del gas encendido.**
    //
    // El que se elige en la barra es el de encendido —que es el que uno quiere ver— y el
    // otro, si lo lleva, es de dónde sale al apagarse. Con la luz a media asta se mezclan,
    // que es lo que pasa cuando un tubo arranca: el cristal sigue ahí y el gas va ganando.
    // Sin color propio de apagado se queda el mismo, que es como se comportaba. Ver
    // [Trazo3D.colorApagada].
    val encendida = parseColor(color, conLoQueTapa(255))
    val tinta = colorApagada
        ?.takeIf { pincel.vigente == Pincel.LUZ || punta.alumbra }
        ?.let { apagada ->
            val cuanta = luz.toFloat().coerceIn(0f, 1f)
            mezclados(parseColor(apagada, conLoQueTapa(255)), encendida, cuanta)
        }
        ?: encendida
    val adelante = camara.adelante
    val recorrido = (hondoLejos - hondoCerca).takeIf { it > 1e-6 }

    // **Lo gordo que sale, y por qué crece al acercarse.**
    //
    // Un trazo es una barra que está en el espacio, no una raya pintada sobre el cristal:
    // se acerca uno y se ve más gorda, como se ve más largo lo que mide un metro. Antes el
    // grosor se medía en la pantalla, así que al ampliar el dibujo crecía y las líneas no
    // —el croquis se iba quedando de alambre y mirar de cerca no enseñaba nada nuevo—.
    //
    // Se mide en `dp` y no en píxeles crudos porque un píxel de una pantalla de hoy es la
    // tercera parte de lo que se cree cualquiera que escriba un cuatro: en `dp` el número
    // significa lo mismo en cualquier pantalla, y es lo que ya prometía la bolita del
    // selector. Los trazos de antes del calibre no tienen medida en el mundo, así que se
    // siguen pintando a lo ancho de la pantalla, como se dibujaron.
    // **El trazo mide lo que mide en el mundo, y punto.**
    //
    // Estuvo un rato atado a la pantalla —el mismo grueso en píxeles pasara lo que pasara—
    // y se ve mal en cuanto uno se aleja: el dibujo se hace pequeño, los trazos no, y lo que
    // queda es un garabato de rayas gordas donde había un croquis. Un trazo del espacio es
    // una cosa que está ahí y tiene su grueso: de lejos se ve fino, como se ve fino todo lo
    // que está lejos. Ver [Trazo3D.calibre].
    val enPantalla = (calibre?.let { it * camara.zoom } ?: grosor).dp.toPx()

    // Los puntos, ya en la pantalla, y lo cerca que pasa cada uno: uno es lo más cercano
    // del croquis y cero lo más lejano. Sin nada con lo que comparar —un croquis con un
    // solo trazo— se quedan en medio, que es lo mismo que no modular nada.
    //
    // **Y los que caen en el mismo píxel se tiran.** Un trazo guarda los puntos que hacen
    // falta para que su forma no se mueva ni un tercio de píxel de los de su momento; visto
    // después desde lejos, o alejando la vista, esos píxeles son décimas, y el contorno
    // tendría que apartarse media anchura desde
    // puntos amontonados —la perpendicular de dos puntos pegados no significa nada y la
    // figura se retuerce sobre sí misma justo ahí—. Además de verse mejor, se pinta menos.
    // **Y con los puntos que hagan falta para lo gordo que se ve, no con todos.**
    //
    // Un trazo guarda los puntos que piden sus curvas, **medidas en su momento**. Pero lo que
    // se ve de una raya de veinte píxeles de ancho no cambia porque su eje tenga un punto cada
    // tres o cada siete: la curva la pone el trazado suave, y el detalle que sobra se lo come el
    // propio grosor. Y sí cuesta: **el sólido barrido multiplica el número de puntos por el
    // de caras del corte**, así que un trazo gordo con doscientos puntos y cinco rasgos son
    // decenas de miles de puntos de camino por fotograma. Ahí estaba lo de «algunas tintas
    // son muy pesadas».
    //
    // El paso sale del propio ancho —una fracción de él—, con el suelo de siempre para que un
    // trazo fino no se aligere. Y **una esquina se queda aunque caiga cerca**: aligerando solo
    // por distancia, un ángulo vivo se redondea, y eso sí se ve. Ver [LO_QUE_SE_NOTA_DEL_ANCHO].
    val minimo = MINIMO_EN_PANTALLA_DEL_TRAZO.dp.toPx()
    val gordoAproximado =
        (enPantalla * if (calibre == null) CUERPO_MINIMO + CUERPO_RANGO else 1f)
            .coerceAtLeast(minimo)
    // Y por el grano que toque: moviéndose, más de sobra. Ver [LaCalidad].
    val juntosDeSuAncho =
        maxOf(JUNTOS, gordoAproximado * LO_QUE_SE_NOTA_DEL_ANCHO) * LaCalidad.grano

    // **Y al alejarse, ni se proyectan los puntos que se van a tirar.**
    //
    // El aligerado de abajo mide en la pantalla, así que **primero proyecta y después
    // decide**: alejando la vista, un trazo de doscientos puntos se queda en tres, pero se
    // han hecho las doscientas proyecciones igual. Y al alejarse no se descarta nada —se ve
    // todo el croquis a la vez—, así que eso es el croquis entero proyectado punto a punto
    // en cada fotograma. Ahí está el tirón de alejar la vista.
    //
    // Lo que ocupa el trazo en la pantalla se sabe con **dos** proyecciones —su caja ya está
    // sacada y guardada, ver [cajaDe]— y de ahí sale cuántos puntos pueden llegar a
    // distinguirse. Si trae muchos más, se recorre a saltos. Con holgura de sobra: un
    // garabato apretado recorre mucha más pantalla que su caja, así que se le conceden ocho
    // veces su diámetro antes de saltarse nada, y las dos puntas se quedan siempre.
    val caja = cajaDe(puntos)
    val enElCentro = camara.aPantalla(caja.centro, ancho, alto)
    val enLaOrilla =
        camara.aPantalla(mas(caja.centro, por(camara.derecha, caja.radio)), ancho, alto)
    val loQueOcupa = kotlin.math.hypot(
        enLaOrilla.x - enElCentro.x, enLaOrilla.y - enElCentro.y
    ).toFloat() * 2

    // **Pero un trazo nunca se queda sin forma, por gordo que sea.**
    //
    // Lo que cuenta como «dos puntos que caen juntos» sale del ancho del trazo, y eso está
    // bien: en una raya gorda, dos puntos a un pelo no se distinguen. Lo que no está bien es
    // que la cuenta no mire **cuánto ocupa el trazo entero**. Un rodillo es seis veces más
    // ancho que un rotulador, así que su listón sale enorme, y girando —donde el grano sube
    // todavía más— se le comía todo menos las dos puntas: la curva que uno acababa de trazar
    // se veía **recta mientras se movía la vista y curva al soltar**, parpadeando entre las
    // dos. Con un mínimo de puntos garantizados, la forma se conserva siempre; lo que se
    // aligera es el detalle, no el dibujo.
    val juntos = kotlin.math.min(
        juntosDeSuAncho,
        (loQueOcupa / PUNTOS_QUE_HACEN_LA_FORMA).coerceAtLeast(JUNTOS)
    )

    // Cabe entero en un punto: eso es lo que hay que pintar. Recorrerlo sería recorrerlo
    // para acabar aquí de todas formas.
    if (loQueOcupa <= juntos) {
        drawCircle(
            Color(tinta),
            radius = (anchaMediaAproximada(enPantalla, calibre, minimo) / 2)
                .coerceAtLeast(MINIMO_DEL_RADIO),
            center = Offset(enElCentro.x.toFloat(), enElCentro.y.toFloat())
        )
        return
    }

    val puedenDistinguirse = (HOLGURA_DEL_SALTO * loQueOcupa / juntos).toInt() + 4
    val salto =
        if (puntos.size > puedenDistinguirse * 2) puntos.size / puedenDistinguirse else 1

    val cuantosCaben = puntos.size / salto + 2
    // **Los cuadernos del trazo, y no listas nuevas.** Ver [CuadernoDelTrazo]: esto se hace
    // por trazo y por fotograma, así que con el croquis lleno son un par de miles de listas
    // y vectores que nacen y mueren sesenta veces por segundo. Nada de eso se ve como
    // memoria; se ve como el tirón al girar.
    CuadernoDelTrazo.listo(cuantosCaben)
    val en = CuadernoDelTrazo.en
    val enElMundo = CuadernoDelTrazo.enElMundo
    var cercania = CuadernoDelTrazo.cercania
    var aprieta = CuadernoDelTrazo.aprieta
    var cuantos = 0
    var haciaX = 0f
    var haciaY = 0f
    /** Si en algún punto el trazo se da la vuelta sobre sí mismo. Ver [capa]. */
    var vueltaCerrada = false
    for (i in puntos.indices) {
        // A saltos, pero **sin perder las puntas**: un trazo tiene que seguir empezando y
        // acabando donde empieza y acaba.
        if (salto > 1 && i % salto != 0 && i != puntos.size - 1) continue
        val v = camara.aPantalla(puntos[i], ancho, alto)
        val donde = Offset(v.x.toFloat(), v.y.toFloat())
        val hondo = escalar(puntos[i], adelante)
        val suya = (
            recorrido?.let { 1.0 - ((hondo - hondoCerca) / it).coerceIn(0.0, 1.0) } ?: 0.5
            ).toFloat()
        val ultimo = en.lastOrNull()
        if (ultimo != null) {
            val dx = donde.x - ultimo.x
            val dy = donde.y - ultimo.y
            val cuanto = kotlin.math.hypot(dx, dy)
            // Dobla lo bastante como para que se note: el punto se queda, esté donde esté.
            val coseno =
                if (cuanto > 1e-4f) dx / cuanto * haciaX + dy / cuanto * haciaY else 1f
            // **Y si el trazo se da la vuelta sobre sí mismo, se apunta.** Ver [capa].
            if (cuantos >= 2 && coseno < COSENO_DE_UNA_VUELTA) vueltaCerrada = true
            val dobla = cuantos >= 2 && cuanto > 1e-4f && coseno < COSENO_DE_UNA_ESQUINA
            if (cuanto < juntos && !dobla) {
                // La punta manda: si el que sobra es el último, sustituye al que había, para
                // que el trazo siga acabando donde acaba de verdad.
                if (i == puntos.size - 1) {
                    en[en.size - 1] = donde
                    enElMundo[enElMundo.size - 1] = puntos[i]
                    cercania[cuantos - 1] = suya
                    aprieta[cuantos - 1] = presiones?.getOrElse(i) { 1.0 } ?: 1.0
                }
                continue
            }
            if (cuanto > 1e-4f) {
                haciaX = dx / cuanto
                haciaY = dy / cuanto
            }
        }
        en += donde
        enElMundo += puntos[i]
        if (cuantos == cercania.size) {
            cercania = cercania.copyOf(cuantos * 2 + 1)
            aprieta = aprieta.copyOf(cuantos * 2 + 1)
            CuadernoDelTrazo.quedarse(cercania, aprieta)
        }
        cercania[cuantos] = suya
        aprieta[cuantos] = presiones?.getOrElse(i) { 1.0 } ?: 1.0
        cuantos++
    }

    val n = en.size
    var suma = 0f
    for (k in 0 until n) suma += cercania[k]
    val media = suma / n
    // **Un trazo con medida en el mundo no se reparte sobre nada.**
    //
    // El cuerpo de la tinta se repartía sobre lo cerca y lo lejos que llega **el croquis
    // entero**: el trazo más cercano salía gordo, el más lejano fino, y los de en medio
    // proporcionalmente. Eso ataba cada trazo a todos los demás, y por ahí venía lo de «las
    // líneas ya pintadas se van modificando»: **dibujar una raya nueva un poco más allá
    // estira el reparto y cambia el grueso de todo lo que había**, sin que nadie lo haya
    // tocado. Y encima contradice lo que el trazo ya es: una barra que mide lo que mide en
    // el espacio ([Trazo3D.calibre]), que de lejos se ve fina porque está lejos y no porque
    // se le haya pintado más fina.
    //
    // Así que el reparto se queda solo para los croquis de antes del calibre, que no tienen
    // medida en el mundo y sin él se quedarían planos. Lo demás va a su tamaño y punto: lo
    // dibujado no se mueve porque se dibuje al lado.
    val reparteElCuerpo = calibre == null
    fun cuerpoDe(i: Int): Float =
        if (reparteElCuerpo) CUERPO_MINIMO + CUERPO_RANGO * cercania[i] else 1f
    // **Y un grosor mínimo, que ninguna línea baja de ahí.**
    //
    // Un trazo mide lo que mide en el espacio, así que alejándose se ve fino — hasta que se
    // ve **menos de un píxel** y entonces no se ve: el croquis se va quedando en nada por los
    // bordes justo cuando uno se aleja para mirarlo entero. Un pelo de grosor no es mucho y es
    // lo que hace que la línea siga estando. Ver [MINIMO_EN_PANTALLA_DEL_TRAZO].
    val anchaMedia =
        (enPantalla * if (reparteElCuerpo) CUERPO_MINIMO + CUERPO_RANGO * media else 1f)
            .coerceAtLeast(minimo)

    // A un aumento muy pequeño, un trazo entero cabe en un punto. Un punto es lo que hay
    // que pintar entonces: dejarlo sin pintar haría desaparecer el croquis al alejarse.
    if (n < 2) {
        drawCircle(
            Color(tinta),
            radius = (anchaMedia / 2).coerceAtLeast(MINIMO_DEL_RADIO),
            center = en.first()
        )
        return
    }
    // El pulso solo lo lleva la redonda: es la punta de dibujar. Un listón que adelgaza
    // donde la mano corría deja de ser un listón, y un resaltador tiene el ancho que tiene.
    // Y solo cuando el trazo trae el pulso apuntado: una figura de compás —una rueda, una
    // recta— no lo trae, y ahí el ancho parejo es lo que hay que respetar.
    // Y la sensibilidad decide cuánto se le hace caso: en cero, la raya sale pareja.
    val cuantoPulso =
        (if (punta.pulso) punta.sensibilidad else 0.0).toFloat().coerceIn(0f, 2f)
    val pulso =
        if (Tinta.de(pincel, punta).obedeceAlPulso && presiones != null && cuantoPulso > 0.01f) {
            val crudo = pulsoDe(en, aprieta, cuantos)
            FloatArray(n) { 1f + (crudo[it] - 1f) * cuantoPulso }
        } else FloatArray(n) { 1f }
    // Y el resaltador va **de ancho parejo de punta a punta**: ni pulso, ni el
    // adelgazamiento de lo que queda lejos. Su ancho es el del rotulador y no es asunto de
    // nadie más; lo cerca que pasa lo lleva en lo cargada que va la tinta.
    // Quién obedece al pulso y quién no lo dice el motor de tintas, no una lista de casos
    // repartida por el pintado. Ver [Tinta].
    val laTinta = Tinta.de(pincel, punta)
    val deAnchoFijo = !laTinta.obedeceAlPulso
    // Lo que diga la punta multiplica a todo: es el mando de lo gordo que sale, a igualdad
    // de grosor elegido. Ver [PuntaDelPincel].
    // Sin sección dibujada, el ancho de la punta es lo que hace el trazo fino o gordo. Con
    // ella, el ancho y el alto estiran la figura y el grueso sale de ahí. Ver [barridoDelPerfil].
    val delAncho = if (punta.tienePerfil) 1f else punta.ancho.toFloat().coerceIn(0.2f, 4f)
    val anchos = FloatArray(n) {
        (delAncho * if (deAnchoFijo) enPantalla else enPantalla * cuerpoDe(it) * pulso[it])
            .coerceAtLeast(minimo)
    }

    // **El ladeo de la punta sale del mundo, no de la pantalla.**
    //
    // Con el ángulo guardado a secas, la punta va clavada al cristal: se gira la vista y el
    // mismo trazo pasa de gordo a fino, como si la plumilla se hubiera movido sola. Con el
    // eje guardado en el plano donde se dibujó, se proyecta como cualquier otra cosa del
    // croquis y la punta **gira con el dibujo**. Ver [Trazo3D.ejeDeLaPunta].
    val ladeo = ejeDeLaPunta?.let { eje ->
        val medio = puntos[puntos.size / 2]
        val a = camara.aPantalla(medio, ancho, alto)
        val b = camara.aPantalla(mas(medio, por(normalizado(eje), 1.0)), ancho, alto)
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (kotlin.math.hypot(dx, dy) < 1e-9) punta.angulo else atan2(dy, dx)
    } ?: punta.angulo
    // **Y las perpendiculares, solo cuando hacen falta.**
    //
    // Las usa el contorno de las tintas que se pintan por capas —el rotulador, la luz—, no las
    // que barren su corte, que son las más caras y las que más hay. Sacarlas siempre era
    // recorrer el trazo entero y fabricar una lista más por trazo y por fotograma para
    // tirarla sin mirarla.
    var lasPerpendiculares: List<Offset>? = null
    fun perpendiculares(): List<Offset> =
        lasPerpendiculares ?: perpendicularesDe(en).also { lasPerpendiculares = it }

    val brillo = Color(aclarado(tinta, MEZCLA_DEL_BRILLO, conLoQueTapa(ALFA_DEL_BRILLO)))
    val sombra = Color(aclarado(tinta, -MEZCLA_DE_LA_SOMBRA, conLoQueTapa(ALFA_DE_LA_SOMBRA)))
    val cuerpo = Color(tinta)

    /**
     * El tapón, suelto, para las tintas que no se pintan por capas.
     *
     * El barrido de una sección tiene el mismo agujero por la misma razón: sus caras se
     * arman lado a lado y en el codo de una vuelta cerrada se cruzan. Ver el `capa` de
     * arriba, que es donde está el porqué entero.
     *
     * Va a una parte del más fino y no al más fino entero: una sección que se ve **de canto**
     * —un listón puesto de perfil— es más estrecha que su ancho, y un tapón del ancho entero
     * asomaría por los lados. A media anchura cabe en cualquier postura y el codo se rellena
     * igual, porque el agujero está justo en el eje.
     */
    fun taponDeLaVuelta(
        tono: Color,
        parte: Float = 1f,
        mezcla: BlendMode = BlendMode.SrcOver,
        deSuAncho: Float = 1f
    ) {
        if (!vueltaCerrada) return
        var masFino = Float.MAX_VALUE
        for (a in anchos) if (a < masFino) masFino = a
        val eje = LosCaminos.limpio(LosCaminos.tapon)
        porUnLado(eje, en, arranca = true)
        drawPath(
            eje,
            tono,
            style = Stroke(
                width = masFino * parte * deSuAncho,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            ),
            blendMode = mezcla
        )
    }

    /**
     * Una capa: el contorno del trazo a una parte de su ancho, corrido hacia la luz.
     *
     * **De dónde viene la luz lo decide la pantalla, no el trazo.** El lado claro salía de
     * la perpendicular del tramo tal cual, así que trazar la misma raya de izquierda a
     * derecha o de derecha a izquierda la iluminaba por caras opuestas, y una curva se iba
     * alumbrando por donde tocara: con cada trazo alumbrado a su aire, ninguno se lee como
     * un bulto. Proyectando una luz fija sobre esa perpendicular, el lado claro es siempre
     * el que mira a la luz, y un manojo de trazos se ve alumbrado a la vez —que es de
     * donde sale la sensación de volumen—.
     *
     * En cero —el trazo va justo hacia la luz— el bulto se queda centrado, que es lo que
     * le pasa de verdad a una barra alumbrada de punta: se ve igual de clara por los dos
     * lados. No hay que forzar nada, la propia cuenta lo hace.
     */
    fun capa(
        tono: Color,
        parteDelAncho: Float,
        corrimiento: Float = 0f,
        tapa: Tapa = Tapa.REDONDA,
        /** Cómo se mezcla con lo que hay debajo. La luz **suma**; la tinta tapa. */
        mezcla: BlendMode = BlendMode.SrcOver
    ) {
        drawPath(
            contornoDe(en, perpendiculares(), anchos, parteDelAncho, corrimiento, tapa),
            tono,
            blendMode = mezcla
        )
        // **Y el tapón de las vueltas cerradas: el agujero de la U.**
        //
        // El contorno se arma yendo por un lado y volviendo por el otro, y eso es una figura
        // cerrada mientras el trazo no se dé la vuelta sobre sí mismo. En cuanto lo hace —una
        // U, un gancho, una vuelta en horquilla— **el lado de dentro se cruza consigo mismo**
        // en el codo: se forma un lacito, y un lacito recorrido al revés que el resto no se
        // rellena, se descuenta. Lo que se veía es exactamente eso: una U con el codo vacío,
        // dibujada solo por sus bordes.
        //
        // Arreglarlo en el contorno es recortar cada lado contra sí mismo, que es caro y hay
        // que hacerlo bien en todos los casos. Se tapa: se pinta el recorrido con una raya del
        // grueso más fino que tenga el trazo. Va **por dentro** de la figura —el ancho más
        // fino cabe en cualquier punto— así que no cambia ni un pelo de la silueta, y el
        // agujero del codo queda relleno del mismo color. Solo se paga en los trazos que se
        // dan la vuelta, que son los pocos que lo necesitan.
        if (corrimiento != 0f) return
        taponDeLaVuelta(tono, parteDelAncho, mezcla)
    }

    // La cinta de esta tinta sobre su hoja, **armada una sola vez**: la piden todos los
    // peldaños del encendido, y montarla en cada uno sería recorrer el trazo catorce veces
    // para pintar el mismo trazo.
    var suCinta: CintaEnElPlano? = null
    fun laCintaEnLaHoja(dePie: Boolean): CintaEnElPlano =
        suCinta ?: cintaEnElPlano(
            enElMundo, normal!!, normales,
            (calibre ?: (grosor / camara.zoom)).dp.toPx(),
            camara, ancho, alto, dePie = dePie
        ).also { suCinta = it }

    /**
     * **La silueta de esta tinta, a una parte de su ancho.**
     *
     * Es lo único que distingue a una punta de otra cuando lo que se pinta es luz, y por eso
     * está aquí suelto: el encendido de un tubo es pintar esto varias veces, cada vez más
     * estrecho y más claro. Ver [pintarComoUnTubo].
     *
     * Cada punta da la suya con la misma forma que tendría apagada: la sección dibujada a
     * mano barrida, la lámina de la cuchilla saliendo de su hoja, la banda del rodillo
     * tumbada en ella, el corte en escuadra del listón, y el contorno de siempre para el
     * resto. Sin hoja debajo, las dos que la necesitan caen en el contorno redondo, que es lo
     * único honesto que se puede hacer con una raya que no está sobre nada.
     */
    fun siluetaDeLaTinta(parte: Float): Path = when {
        punta.tienePerfil ->
            barridoDelPerfil(en, FloatArray(n) { anchos[it] * parte }, punta, ladeo)

        pincel.vigente == Pincel.CUCHILLA && normal != null ->
            laCintaEnLaHoja(dePie = true).caminoDe(parte, 0f, desdeElEje = true)

        pincel.vigente == Pincel.RESALTADOR && normal != null ->
            laCintaEnLaHoja(dePie = false).caminoDe(parte, 0f)

        // La punta de canto conserva su cinta al sesgo también encendida: lo que la
        // distingue es por dónde va respecto del canto, y eso no lo cambia el material.
        punta.deCanto -> cintaAlSesgo(
            en,
            anchaMedia * delAncho * punta.largo.toFloat().coerceIn(1f, 8f) / 2 * parte,
            kotlin.math.cos(ladeo).toFloat(),
            kotlin.math.sin(ladeo).toFloat()
        )

        pincel.vigente == Pincel.CUADRADO ->
            contornoDe(en, perpendiculares(), anchos, parte, 0f, Tapa.CUADRADA)

        pincel.vigente == Pincel.RESALTADOR ->
            contornoDe(en, perpendiculares(), anchos, parte, 0f, Tapa.PLANA)

        else -> contornoDe(en, perpendiculares(), anchos, parte, 0f, Tapa.REDONDA)
    }

    /**
     * **Un tubo encendido, sea cual sea la punta.**
     *
     * Tres cosas y en este orden: el cuerpo de la tinta, el degradado que se apaga hacia
     * fuera y, por dentro, el filamento. Lo que hace que se lea como encendido y no como
     * pintado es que **el centro deslumbra y los bordes no**, porque una fuente de luz satura
     * el ojo por el medio; pintado todo del mismo color, por muy vivo que sea, lo que se ve
     * es una raya gorda.
     *
     * ## Y el cuerpo va debajo, encendido o no
     *
     * El degradado **suma** sobre lo que hay pintado, y sumar poco es no pintar casi nada:
     * bajando la llave de las luces, el trazo se quedaba en un velo transparente antes de
     * llegar a cero y en el cero volvía de golpe a ser tinta maciza —porque a cero se pinta
     * por la rama de siempre—. Con el cuerpo debajo, las dos ramas dicen lo mismo en el cero y
     * la llave se mueve sin ningún escalón. Un tubo apagado tampoco desaparece: se ve el
     * cristal.
     *
     * ## Una escalera y no tres capas
     *
     * Eran tres —resplandor, cuerpo y filamento— y las tres se veían: tres anillos con su
     * borde, que es lo que hace un cartel de tres colores y no una luz. Lo que hace una fuente
     * de verdad es caer de golpe por el medio y despacio por fuera, sin ningún canto. Con
     * bastantes peldaños no se ve ni uno. **Cuántos, depende de lo gorda que se vea**: una luz
     * de cuatro píxeles no necesita catorce pasadas para no tener cantos.
     */
    fun pintarComoUnTubo() {
        // **La llave de las luces llega al doble.** Hasta uno, la luz se enciende: el color se
        // va hacia su tono vivo y el filamento hacia el blanco. De uno para arriba ya no queda
        // color al que ir y lo que sube es **cuánto se sale del blanco**, que es lo que hace
        // una fuente cuando le subes la corriente estando ya encendida. Ver [pintarEncendido].
        val mando = luz.coerceAtLeast(0.0).toFloat()
        val cuanta = mando.coerceAtMost(1f)
        val deMas = mando.coerceIn(0f, 2f)
        val vivo = haciaElBrillo(tinta, cuanta)

        // **Y el cuerpo se va apagando conforme sube la luz.**
        //
        // A cero es la tinta que se eligió, maciza; a tope no queda nada de él y lo que se ve
        // es luz pura sumando sobre el fondo, que es lo que la hace deslumbrar por encima del
        // blanco de la pantalla. Con el cuerpo puesto a todas horas, un tubo a tope llevaba
        // debajo una raya opaca del color de la tinta y lo que salía era una tinta clara con
        // un halo: el brillo de más dejaba de notarse. Ver [pintarEncendido].
        val loQueQuedaDelCristal = 1f - cuanta
        if (loQueQuedaDelCristal > 0.01f) {
            drawPath(
                siluetaDeLaTinta(1f),
                cuerpo.copy(alpha = cuerpo.alpha * loQueQuedaDelCristal)
            )
        }

        // De lejos, ni degradado ni nada: una luz de tres píxeles no tiene sitio para ningún
        // canto, así que los peldaños de más son trabajo que se lo come el antialias.
        val peldanos = (anchaMedia / DP_POR_PELDANO.dp.toPx())
            .toInt().coerceIn(PELDANOS_MINIMOS, LaCalidad.peldanosDeLaLuz)
        val masAncho = 1f + (RESPLANDOR - 1f) * cuanta
        for (k in peldanos downTo 1) {
            // De fuera adentro. `t` va de cero en el borde del resplandor a uno en el eje: el
            // ancho baja con él y lo que aporta cada peldaño sube.
            val t = k.toFloat() / peldanos
            // Al cuadrado, que es como se apaga la luz al alejarse de su fuente: casi toda la
            // claridad se queda pegada al eje y lo de fuera es un velo.
            val fuerza = t * t
            pintarEncendido(
                siluetaDeLaTinta((masAncho * t).coerceAtLeast(FILAMENTO)),
                aclarado(
                    vivo,
                    // Y hacia el blanco por el centro: una fuente satura el ojo por el medio.
                    // Sin margen de brillo, antes: es lo único que queda para que se lea como
                    // encendida. Ver [ElBrilloDeMas].
                    (fuerza * cuanta * ElBrilloDeMas.prisaHaciaElBlanco).coerceAtMost(1f)
                        .toDouble(),
                    conLoQueTapa(
                        (ALFA_DEL_PELDANO * fuerza * ElBrilloDeMas.cargaDelResplandor).toInt()
                    )
                ),
                // Lo que se sale del blanco lleva el mando entero, no la mitad.
                deMas * fuerza
            )
        }
    }

    // **Apagada, la luz es tinta lisa.** No es un caso raro: es lo que promete el mando —en
    // cero, el color que se eligió, ni más ni menos— y lo que hace la llave de paso de todo
    // el croquis. Sin esto, apagarlas dejaba las capas de sumar puestas y lo que quedaba era
    // una raya lavada que no era el color de nadie. Ver [Luces3D].
    val comoLuz = (punta.alumbra || pincel.vigente == Pincel.LUZ) && luz > 0.005

    /**
     * **El cuerpo del trazo**: la tinta, sin el grano.
     *
     * Va aparte porque el grano se estampa **encima y recortado a la silueta**, y el cuerpo
     * sale por una decena de puertas distintas —el atajo de lo que ya es una raya, la marca
     * dibujada, el perfil, el listón, la punta de canto, cada tinta del `when`—. Metiendo la
     * trama en cada una de ellas habría que acordarse de las diez; así se pinta lo que sea
     * que pinte el cuerpo y después se le pasa el grano por encima, una sola vez.
     */
    fun elCuerpo() {
        // **Y por debajo de un par de píxeles, una raya es una raya.**
        //
        // Un croquis se mira de lejos la mitad del tiempo, y de lejos casi todo lo dibujado
        // ocupa dos píxeles de ancho. Ahí, barrer el corte de la punta es armar veinte caras,
        // descartar diez y pintar las otras diez **para que salga una raya de dos píxeles**: el
        // trabajo entero se tira por el desagüe del antialias. Se pinta la raya y ya, que es lo
        // que se iba a ver de todas formas.
        //
        // No es un apaño: es el nivel de detalle de cualquier motor, y aquí es de lo que más se
        // nota, porque lo que se ahorra es justo lo que hay de más en una escena llena. La luz
        // no entra —sus tres capas **son** lo que se ve de ella incluso pequeña—.
            // **Y moviéndose, el listón sube bastante.**
        //
        // Es el escalón de detalle que más se nota de todos, y es el mismo de siempre visto
        // en movimiento: barrer el corte de la punta para acabar en una raya de cuatro
        // píxeles es tirar el trabajo por el desagüe del antialias, y **en movimiento la
        // vista tampoco distingue una raya de cinco de un prisma de cinco**. Con la vista
        // quieta se repinta cada uno con su corte, que es cuando se mira de cerca.
        //
        // No le toca a las puntas que llevan forma propia —una sección dibujada a mano, una
        // marca, la cuchilla con su hondura—: ahí la forma **es** la tinta, y perderla al
        // girar se lee como que el croquis ha cambiado, no como que va suave.
        // **Y el umbral es el mismo moviéndose que quieto.** Era más alto girando —más
        // trazos se pintaban como una raya sin cuerpo— y eso se ve: el croquis adelgaza al
        // girar y engorda al soltar. Un trazo es una barra que está en el espacio; lo que
        // mide no puede depender de si uno está moviendo la cámara. Ver [LaCalidad.grano].
        val yaEsUnaRaya = LO_QUE_YA_ES_UNA_RAYA
        if (anchaMedia <= yaEsUnaRaya.dp.toPx() && !comoLuz) {
            // Reaprovechado: este es el camino que más veces se arma de todo el croquis, y
            // desde que el aligerado manda aquí a la mayoría de los trazos, más. Ver
            // [LosCaminos].
            val camino = LosCaminos.limpio(LosCaminos.raya)
            porUnLado(camino, en, arranca = true)
            drawPath(
                camino,
                Color(if (pincel.vigente == Pincel.RESALTADOR) parseColor(color, conLoQueTapa(ALFA_DEL_RESALTADOR)) else tinta),
                style = Stroke(width = anchaMedia, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            return
        }

        // **Y con una marca dibujada a mano, manda la marca.**
        //
        // Se estampa a lo largo del recorrido, girada con él: eso es lo que hace que se noten
        // las curvas de la punta y que dos tintas distintas se distingan de verdad. Con sus
        // colores dentro —cada rasgo lleva el suyo—, así que una tinta puede tener tres.
        // Ver [PuntaDelPincel.dibujo].
        if (punta.tieneDibujo) {
            barridoSolido(
                enElMundo, anchos, camara, ancho, alto,
                elEjeDelCorte(normal, ejeDeLaPunta),
                corteGuardadoDeLaMarca(
                    punta.dibujo!!,
                    (opacidad.coerceIn(0.0, 1.0) * 255).toInt(),
                    detalleDelCorte(anchaMedia, punta.dibujo!!.size)
                ),
                punta, tinta
            )
            return
        }

        // **Y con perfil dibujado, manda el perfil.**
        //
        // Es la forma general de todo esto: una punta redonda, un listón y una plumilla no son
        // tres cosas distintas, son tres secciones. Con una dibujada a mano, el trazo es
        // **barrer esa figura a lo largo del recorrido**, que es literalmente lo que hace una
        // punta contra el papel. Ver [PuntaDelPincel.perfil].

        // **Y lo que está fuera de foco no se pinta como una raya, se pinta como una mancha.**
        //
        // Se reparte en unas cuantas pasadas cada vez más anchas y más flojas: eso es lo que
        // hace un objetivo con el diafragma abierto —el punto se convierte en un disco— y es
        // lo que separa «lejos» de «pequeño». Ver [LaApertura].
        val loBlando = LaApertura.loBlandoDe(hondura(puntos, camara), camara.zoom)
        if (loBlando > 0.15f) {
            val tono = if (pincel.vigente == Pincel.RESALTADOR) {
                Color(parseColor(color, conLoQueTapa(ALFA_DEL_RESALTADOR)))
            } else cuerpo
            for (k in PASADAS_DEL_DESENFOQUE downTo 1) {
                val t = k.toFloat() / PASADAS_DEL_DESENFOQUE
                drawPath(
                    siluetaDeLaTinta(1f + loBlando * t),
                    // Lo de fuera aporta menos, que es lo que hace que la mancha se apague
                    // hacia sus bordes en vez de acabar en un canto.
                    tono.copy(alpha = tono.alpha * ALFA_DE_UNA_PASADA * (1f - t * 0.6f))
                )
            }
            return
        }

        // **Alumbrando, cualquier punta se pinta como un tubo encendido.**
        //
        // Y **con su propia forma**, que es lo que faltaba. La luz dejó de ser una punta para
        // ser un material precisamente para que un listón encendido siguiera siendo un
        // listón; pero el pintado seguía teniendo una sola manera de pintar una luz —la del
        // rotulador— así que elegir el material de luz devolvía cualquier punta a una barra
        // redonda. Un rodillo encendido salía sin encender siquiera.
        //
        // Un tubo encendido es **su silueta pintada varias veces, cada vez más estrecha y más
        // clara, sumando**. Lo que cambia de una punta a otra no es eso: es la silueta. Así
        // que la silueta la da cada punta —el perfil dibujado, la lámina de la cuchilla, la
        // banda del rodillo tumbada en su hoja, el corte en escuadra del listón— y el
        // encendido es el mismo para todas. Ver [siluetaDeLaTinta].
        if (comoLuz) {
            pintarComoUnTubo()
            return
        }

        if (punta.tienePerfil) {
            val tono =
                if (pincel.vigente == Pincel.RESALTADOR) {
                    parseColor(color, conLoQueTapa(ALFA_DEL_RESALTADOR))
                } else tinta
            barridoSolido(
                enElMundo, anchos, camara, ancho, alto,
                elEjeDelCorte(normal, ejeDeLaPunta),
                corteDeLaSeccion(punta.perfil!!, tono),
                punta, tinta
            )
            taponDeLaVuelta(Color(tono), deSuAncho = PARTE_DEL_TAPON)
            return
        }

        // **El listón se barre como lo que es: un cuadrado que está en el espacio.**
        //
        // Se pintaba con el contorno de siempre y las tapas en escuadra, y ese contorno se
        // calcula **mirando a la cámara**: el cuadrado de su sección estaba siempre de cara, así
        // que al girar la vista el trazo giraba con ella —«la tinta cuadrada me sigue»— en vez
        // de quedarse quieto como se pintó. Barriendo la sección de verdad, con su eje guardado
        // en el mundo, el listón se escorza al girar como se escorzaría una barra puesta ahí.
        if (pincel.vigente == Pincel.CUADRADO && !punta.tienePerfil && !punta.deCanto && !comoLuz) {
            // **Liso, de un solo tono, y la forma que se ve es la de verdad.**
            //
            // Llevaba encima una barra oscura y el cuerpo un pelo más fino, para que al cortarse
            // quedara «la arista». Pero esa arista era **un borde pintado alrededor del
            // contorno**, y un contorno no sabe hacia dónde mira la cámara: se veía igual desde
            // todos lados, así que girando la vista el trazo no se escorzaba —«tiene bordes
            // negros y no cambian al girar»—. Y encima contradecía lo de siempre: la tinta va
            // de un color, sin halos ni dos tonos.
            //
            // Lo que distingue un listón de una raya gorda no hace falta pintarlo: **ya está en
            // su silueta**. Barriendo el cuadrado de verdad, con su eje clavado en el mundo, de
            // frente se ve una banda ancha y de canto un pelo, y al girar pasa de una a otra
            // sola. Eso es un prisma; lo otro era un dibujo de un prisma.
            barridoSolido(
                enElMundo, anchos, camara, ancho, alto,
                elEjeDelCorte(normal, ejeDeLaPunta),
                corteDeLaSeccion(SeccionDePunta.CUADRADO.puntos(), tinta),
                punta, tinta
            )
            taponDeLaVuelta(cuerpo, deSuAncho = PARTE_DEL_TAPON)
            return
        }

        // **La punta de canto se pinta aparte, sea la tinta que sea.**
        //
        // Con la punta alargada, lo que deja el trazo ya no lo decide su sección redonda o
        // cuadrada: lo decide **hacia dónde va respecto del canto**. De través pinta todo el
        // ancho de la punta y a lo largo deja un pelo, que es lo que hace una plumilla y lo que
        // uno busca al alargarla. El color y lo que tapa siguen siendo los de su tinta, así que
        // un listón alargado sigue teniendo su arista y una luz alargada sigue alumbrando.
        if (punta.deCanto && pincel.vigente != Pincel.RESALTADOR) {
            val filo = anchaMedia * delAncho * punta.largo.toFloat().coerceIn(1f, 8f) / 2
            val cos = kotlin.math.cos(ladeo).toFloat()
            val sen = kotlin.math.sin(ladeo).toFloat()
            val tono = when (pincel.vigente) {
                Pincel.LUZ -> Color(aclarado(haciaElBrillo(tinta, luz.toFloat()), 0.0, conLoQueTapa(255)))
                else -> cuerpo
            }
            drawPath(cintaAlSesgo(en, filo, cos, sen), tono)
            // El canto de la cinta, que es lo que le da espesor: un filo claro por donde le da
            // la luz y otro oscuro enfrente. Sin ellos, una punta alargada es una mancha plana.
            val haciaLaLuz = LUZ_X * cos + LUZ_Y * sen >= 0
            bisel(en, filo * DENTRO_DEL_BISEL, if (haciaLaLuz) brillo else sombra, cos, sen)
            bisel(en, -filo * DENTRO_DEL_BISEL, if (haciaLaLuz) sombra else brillo, cos, sen)
            // Y el ancho fino que deja aunque vaya justo a lo largo de su canto: sin él, el
            // trazo se corta en seco en las curvas donde el barrido se queda sin área.
            capa(cuerpo, CANTO_DE_LA_PUNTA)
            return
        }

        when (
            // Una tinta de luz apagada se pinta como el rotulador: una capa lisa del color que
            // se le puso. Encendida no llega aquí — se ha ido por [pintarComoUnTubo].
            if (pincel.vigente == Pincel.LUZ) Pincel.REDONDO else pincel.vigente
        ) {
            // **Cuadrado: un listón, liso y en escuadra.**
            //
            // Aquí solo llega el listón que no puede barrer su sección —de canto, o alumbrando—,
            // y va de un tono: las caras claras y oscuras que llevaba encima estaban pegadas a
            // la pantalla y no a la barra. Las tapas sí van en escuadra: con las redondas, una
            // barra de sección cuadrada acaba en dos casquetes y vuelve a parecer un tubo.
            Pincel.CUADRADO -> capa(cuerpo, 1f, tapa = Tapa.CUADRADA)

            // **Cuchilla: fina de frente y honda de canto.**
            //
            // Una lámina de pie sobre la hoja. El selector no la ensancha, la hace **más
            // honda**: lo ancho lo pone el recorrido del dedo, que es lo que uno decide
            // trazando, y lo hondo es lo que uno decide con el mando. Ver [Pincel.CUCHILLA].
            //
            // Sin hoja a la que agarrarse no hay perpendicular por la que bajar, y entonces no
            // hay cuchilla que valga: se pinta como la redonda, que es lo único honesto que se
            // puede hacer con una raya que no está sobre nada.
            Pincel.CUCHILLA -> {
                if (normal == null) capa(cuerpo, 1f)
                else {
                    val honda = calibre ?: (grosor / camara.zoom)
                    // **Y la lámina es un sólido, no una cara.**
                    //
                    // Se pintaba como una sola figura rellena —los dos rieles y vuelta—, y de
                    // ahí venían sus dos defectos. Uno, que **no tiene espesor**: girando la
                    // vista hasta ponerla de canto desaparecía, y una pieza que desaparece al
                    // girar no se lee como una pieza. Y otro, que una figura rellena que se
                    // cruza consigo misma **se descuenta** donde se pisa: pasando la cuchilla
                    // de ida y de vuelta por el mismo sitio, lo superpuesto se despintaba.
                    //
                    // Barrida como sólido no pasa ninguna de las dos: es un tabique con su
                    // grueso, sus dos caras, sus cantos y sus tapas, y cada cara se pinta por
                    // su cuenta —así que dos pasadas se tapan, no se restan—. Es el mismo
                    // barrido del listón con otro corte: alto lo que baje la cuchilla y fino
                    // como un tabique. Ver [barridoSolido].
                    val hondoEnElMundo = honda.dp.toPx().toDouble()
                    val deLaHoja = FloatArray(n) { (hondoEnElMundo * camara.zoom).toFloat() }
                    barridoSolido(
                        enElMundo, deLaHoja, camara, ancho, alto,
                        elEjeDelCorte(normal, ejeDeLaPunta),
                        corteDeLaSeccion(elCorteDeLaCuchilla(), tinta),
                        // Sin estirar ni ladear: lo que la hace cuchilla es su corte, y el
                        // ancho y el ladeo de la punta ya han dicho lo suyo en el recorrido.
                        PuntaDelPincel(),
                        tinta
                    )
                    taponDeLaVuelta(cuerpo, deSuAncho = PARTE_DEL_TAPON)
                    // **Y el canto de arriba marcado.** Una lámina pintada de un tono liso se
                    // lee como una mancha; el filo por donde se trazó es lo que dice de dónde
                    // sale y por dónde va, que es lo que uno acaba de dibujar con el dedo. Va
                    // del color de la tinta subido, no de otro color: es la misma pieza vista
                    // por su canto, no un adorno.
                    val filo = Path()
                    porUnLado(filo, en, arranca = true)
                    drawPath(
                        filo,
                        Color(aclarado(tinta, MEZCLA_DEL_BRILLO, conLoQueTapa(255))),
                        style = Stroke(
                            width = FILO_DE_LA_CUCHILLA.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            // **Resaltador: la banda de subrayar, y de través por donde vaya.**
            //
            // Es una punta de canto, pero la punta **no está clavada a un ángulo**: se gira
            // sola para ir siempre perpendicular a por dónde va el trazo, que es lo que uno
            // hace con la mano sin pensarlo al subrayar un renglón torcido. Iba fija a
            // cuarenta y cinco grados y eso es caligrafía: dejaba una tira en diagonal, con
            // las puntas al sesgo, y en cuanto el trazo iba en la dirección de la punta no
            // pintaba nada. Ahora la banda sale del mismo contorno que las demás tintas, que
            // es justo eso: media anchura a cada lado de la perpendicular.
            //
            // Ancha ([Croquis3DControlador.ENGORDE_DEL_RESALTADOR]), translúcida y con
            // las puntas cortadas a ras: un resaltador empieza donde lo posas y acaba donde lo
            // levantas, sin casquetes que asomen por los cantos del renglón.
            //
            // Y lo cerca que pasa lo lleva en **lo cargada que va la tinta** y no en el ancho:
            // el ancho es el del rotulador y no cambia.
            // **La banda está tumbada en la hoja, no de cara a quien mira.**
            //
            // Se construía en la pantalla —media anchura a cada lado de la perpendicular de lo
            // que se ve—, y eso es un cartel que siempre te mira: al girar la vista la banda
            // giraba con ella y se despegaba del dibujo, en vez de escorzarse y acabar viéndose
            // de canto como la raya de pintura que es. Ahora los dos bordes se calculan **en el
            // mundo** y se proyectan como cualquier otra cosa del croquis.
            //
            // Aquí solo llega **el que se está trazando ahora mismo**: los ya dibujados van por
            // grupos y en una sola capa, para que las pasadas no se sumen entre sí. Ver
            // [pintarLosRodillos].
            Pincel.RESALTADOR -> {
                // **Con plano, esto ya está pintado con la escena y no se toca aquí.**
                //
                // Se une a su montón allí, que es lo que hace que pasar el rotulador por encima
                // de otra pasada no se vea más oscuro mientras se está pasando. Ver
                // [pintarLaEscena] y su `subrayandoAhora`.
                //
                // Sin plano al que agarrarse no hay montón ni nada que tumbar: entonces sí, de
                // cara al que mira y suelto, que es lo único que se puede hacer con él.
                run {
                    capa(Color(parseColor(color, conLoQueTapa(ALFA_DEL_RESALTADOR))), 1f, tapa = Tapa.PLANA)
                }
            }

            // **Redonda: el color que se ha elegido, con el pulso de la mano dentro.**
            //
            // Una sola capa lisa —ni silueta oscura, ni brillo, ni halo—: un rotulador deja
            // tinta lisa, y con tres capas de relieve lo que se veía era una raya de dos
            // colores con sombra alrededor, sin el color que se eligió en la barra por ninguna
            // parte. El bulto se lo da que el trazo **mide lo que mide en el mundo**.
            //
            // Lo que sí lleva es el pulso: engorda donde se apretó y adelgaza donde la mano
            // corría. Ver [pulsoDe]. Aquí caen también las dos puntas retiradas.
            else -> capa(cuerpo, 1f)
        }
    }

    elCuerpo()

    // **Y el grano encima, recortado a lo que se acaba de pintar.**
    //
    // Después del cuerpo y no en su lugar: una tinta tramada sigue siendo su tinta —su
    // color, su ancho, su relieve— con un grano dentro. Ver [pintarLaTrama].
    //
    // No en lo que ya se ve como una raya: en dos píxeles de ancho no cabe ninguna trama, y
    // estampar veinte marcas para que se las coma el antialias es el mismo trabajo tirado
    // que evita el atajo de arriba.
    // La cuchilla se queda fuera: su cuerpo **no es su contorno**, es una lámina que cuelga
    // del trazo hacia dentro de la hoja, así que el grano recortado al contorno caería en el
    // filo y no en la lámina — un grano pintado donde no está la tinta.
    if (laTinta.trama.hayQuePintarla &&
        pincel.vigente != Pincel.CUCHILLA &&
        anchaMedia > LO_QUE_YA_SE_TRAMA.dp.toPx()
    ) {
        pintarLaTrama(
            contornoDe(en, perpendiculares(), anchos, 1f, 0f, Tapa.REDONDA),
            en, anchos, laTinta.trama,
            Color(aclarado(tinta, -HONDO_DE_LA_TRAMA, conLoQueTapa(255)))
        )
    }
}

/**
 * **El grano de una tinta tramada**, estampado dentro de la silueta del trazo.
 *
 * ## Recortado, y no dibujado al borde
 *
 * Las marcas se trazan **más largas que ancho tiene el trazo** y se recortan a su silueta.
 * Es lo contrario de calcular dónde acaba cada una, y es lo que hace que el grano case con
 * el trazo sin trabajo: el trazo engorda con el pulso, se estrecha al alejarse y acaba en un
 * casquete redondo, y una marca calculada para el ancho de su punto se quedaría corta en
 * unos sitios y asomaría en otros. Recortada, cada marca acaba exactamente en el canto de la
 * tinta, sea cual sea.
 *
 * ## De través a por donde va la raya
 *
 * Y no en una retícula de la pantalla, que es como se hace un tramado en un dibujo plano.
 * Aquí eso sería un papel pintado pegado al cristal: se gira la vista y la trama se queda
 * quieta mientras el croquis gira debajo, que es exactamente lo que delata que un dibujo es
 * plano. Colgada del recorrido, la trama se escorza y gira con el trazo.
 *
 * ## Todo en un camino por familia
 *
 * Cien marcas son cien llamadas a pintar si se mandan una a una, y esto va encima de cada
 * trazo tramado de cada fotograma. Van todas en un `Path` por familia —las de través en uno,
 * las de a lo largo en otro— y se pintan de una pasada.
 */
private fun DrawScope.pintarLaTrama(
    silueta: Path,
    en: List<Offset>,
    anchos: FloatArray,
    trama: Trama,
    tono: Color
) {
    val n = en.size
    if (n < 2) return

    // **El paso y el grueso salen del ancho del trazo**, que es lo que hace que una trama
    // sea una trama y no una medida: tantas marcas por ancho, se mire de cerca o de lejos.
    var suma = 0f
    for (a in anchos) suma += a
    val ancho = suma / n
    val paso = (ancho * PASO_DE_LA_TRAMA).toFloat()
        .coerceAtLeast(MINIMO_ENTRE_MARCAS.dp.toPx())
    val pelo = (ancho * GRUESO_DE_LA_TRAMA).toFloat().coerceAtLeast(1f)
    // De sobra hacia los dos lados: lo que sobre lo corta la silueta.
    val cruza = ancho * 0.8f

    val deTraves = Path()
    val puntos = Path()
    var falta = 0f
    var cual = 0
    for (i in 0 until n - 1) {
        // **Un tope de marcas por trazo.** Es el gasto por fotograma de esto, y sin tope lo
        // pone el largo del trazo: una raya que cruza la pantalla al máximo aumento pediría
        // miles de marcas de las que se ven cuatro. Al llegar aquí, el grano ya está tan
        // apretado que una marca más no cambia nada de lo que se ve.
        if (cual >= MARCAS_DE_SOBRA) break
        val a = en[i]
        val b = en[i + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val largo = kotlin.math.hypot(dx, dy)
        if (largo < 1e-4f) continue
        val ux = dx / largo
        val uy = dy / largo
        var recorrido = falta
        while (recorrido <= largo) {
            val x = a.x + ux * recorrido
            val y = a.y + uy * recorrido
            if (trama == Trama.PUNTOS) {
                // Alternando de lado, que si no lo que sale es una raya de puntos por el
                // centro y no un punteado.
                val ladeo = if (cual % 2 == 0) cruza * 0.28f else -cruza * 0.28f
                puntos.addOval(
                    Rect(
                        Offset(x - uy * ladeo, y + ux * ladeo),
                        pelo * 1.5f
                    )
                )
            } else {
                deTraves.moveTo(x - uy * cruza, y + ux * cruza)
                deTraves.lineTo(x + uy * cruza, y - ux * cruza)
            }
            recorrido += paso
            cual++
        }
        falta = recorrido - largo
    }

    clipPath(silueta) {
        if (trama == Trama.PUNTOS) {
            drawPath(puntos, tono)
            return@clipPath
        }
        drawPath(
            deTraves, tono,
            style = Stroke(width = pelo, cap = StrokeCap.Butt)
        )
        // El cruzado lleva además dos rayas a lo largo: con las de través solas, lo que se
        // ve es un peine, y lo que dice que algo está en sombra es la retícula.
        if (trama == Trama.CRUZADO) {
            val aLoLargo = Path()
            for (lado in intArrayOf(-1, 1)) {
                val corrido = cruza * 0.34f * lado
                for (i in 0 until n) {
                    val a = en[if (i == 0) 0 else i - 1]
                    val b = en[if (i == n - 1) n - 1 else i + 1]
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val largo = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-4f)
                    val px = -dy / largo * corrido
                    val py = dx / largo * corrido
                    val x = en[i].x + px
                    val y = en[i].y + py
                    if (i == 0) aLoLargo.moveTo(x, y) else aLoLargo.lineTo(x, y)
                }
            }
            drawPath(aLoLargo, tono, style = Stroke(width = pelo, cap = StrokeCap.Butt))
        }
    }
}

/**
 * Por debajo de este ancho en pantalla, un trazo no se trama.
 *
 * Con menos, entre dos marcas no cabe tinta: lo que sale no es un rayado, es una raya
 * mordida. Y estampar veinte marcas para que se las coma el antialias es el mismo trabajo
 * tirado que evita el atajo de la raya. Ver [pintarLaTrama].
 */
private const val LO_QUE_YA_SE_TRAMA = 5.0

/** Lo más juntas que se dejan dos marcas de la trama, en `dp`. */
private const val MINIMO_ENTRE_MARCAS = 3.0

/**
 * Cuántas marcas de trama se pintan como mucho en un trazo.
 *
 * Es el tope de gasto, y va alto: con el paso mínimo son metro y medio de raya tramada, más
 * de lo que cabe en una pantalla. Ver [pintarLaTrama].
 */
private const val MARCAS_DE_SOBRA = 400

/**
 * **Una imagen en el espacio**: sus cuatro esquinas, y el mapa de bits estirado hasta ellas.
 *
 * Se pinta con la transformación que lleva las cuatro esquinas del archivo a las cuatro
 * esquinas proyectadas, que es lo que hace que la imagen se escorce **con la hoja** en vez
 * de quedarse de cara al que mira. Con cuatro puntos eso ya no es un estiramiento cualquiera
 * sino una perspectiva, y Android la sabe hacer de una pasada: es la misma cuenta con la que
 * se endereza la foto de un documento.
 *
 * Vuelta de espaldas se pinta igual: una imagen tiene dos caras, como una lámina, y no ver
 * nada al girar media vuelta se lee como que se ha perdido.
 */
private fun DrawScope.pintarImagen(
    imagen: Imagen3D,
    bmp: android.graphics.Bitmap,
    camara: Camara3D,
    ancho: Double,
    alto: Double,
    elegida: Boolean
) {
    val en = imagen.esquinas.map { camara.aPantalla(it, ancho, alto) }
    if (en.size < 4) return
    val destino = FloatArray(8)
    for (i in 0 until 4) {
        destino[i * 2] = en[i].x.toFloat()
        destino[i * 2 + 1] = en[i].y.toFloat()
    }
    val origen = floatArrayOf(
        0f, 0f,
        bmp.width.toFloat(), 0f,
        bmp.width.toFloat(), bmp.height.toFloat(),
        0f, bmp.height.toFloat()
    )
    val matriz = android.graphics.Matrix()
    if (!matriz.setPolyToPoly(origen, 0, destino, 0, 4)) return
    val pincel = android.graphics.Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        alpha = (imagen.opacidad * 255).toInt().coerceIn(0, 255)
    }
    drawContext.canvas.nativeCanvas.drawBitmap(bmp, matriz, pincel)

    // Elegida, se le marca el contorno: una imagen no puede cambiar de color como un trazo
    // —es la imagen que es—, así que lo que dice que está cogida es su borde.
    if (elegida) {
        val camino = Path()
        en.forEachIndexed { i, p ->
            if (i == 0) camino.moveTo(p.x.toFloat(), p.y.toFloat())
            else camino.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        camino.close()
        drawPath(
            camino,
            Color(parseColor(COLOR_DE_LO_ELEGIDO, 0xFF)),
            style = Stroke(width = BORDE_DE_LO_ELEGIDO.dp.toPx())
        )
    }
}

/** Lo gordo que va el contorno de una imagen elegida, en dp. */
private const val BORDE_DE_LO_ELEGIDO = 2.0

/**
 * **Las sombras de lo dibujado, tiradas en el suelo.**
 *
 * Cada trazo se proyecta punto a punto siguiendo el rayo del sol hasta el plano `z = 0` y se
 * pinta ahí, oscuro y translúcido. No hay más: ni oclusión, ni sombras sobre otras cosas, ni
 * penumbra. Y es lo que hace falta —**decir dónde está apoyada cada cosa**—, que es
 * justamente lo que un croquis no puede decir sin girar la vista.
 *
 * Se pinta con una raya y no con el contorno de la punta: una sombra no tiene punta, y a
 * ras de suelo lo que se ve de ella es por dónde pasa.
 */
private fun DrawScope.pintarLasSombras(
    croquis: Croquis,
    sol: Sol3D,
    camara: Camara3D,
    ancho: Double,
    alto: Double,
    /** De qué color va: la del sol es negra y corta; la de la luna, azulada y floja. */
    tinta: Color
) {
    // **Sin fabricar la sombra antes de pintarla, y a saltos como el trazo.**
    //
    // Se armaba una lista con la sombra de cada punto y después se recorría para pintarla:
    // una lista más por trazo y por fotograma, y con ella un objeto por punto — el croquis
    // entero duplicado en la basura, sesenta veces por segundo, para dibujar unas rayas
    // grises. Ahora se proyecta y se pinta en la misma vuelta, sin guardar nada.
    //
    // Y a saltos: una sombra es una raya gris de un píxel de gorda, así que no hay ninguna
    // razón para que lleve más puntos de los que se distinguen. Lo que se salta es lo que no
    // se ve. Ver el aligerado de [pintarTrazo], que hace lo mismo con el trazo que la tira.
    fun sombraDe(puntos: List<Pt3>, gordo: Float, salto: Int) {
        val camino = LosCaminos.limpio(LosCaminos.sombra)
        var cuantos = 0
        var i = 0
        while (i < puntos.size) {
            val donde = sol.sombraDe(puntos[i])
            if (donde != null) {
                val v = camara.aPantalla(donde, ancho, alto)
                if (cuantos == 0) camino.moveTo(v.x.toFloat(), v.y.toFloat())
                else camino.lineTo(v.x.toFloat(), v.y.toFloat())
                cuantos++
            }
            // Sin perder el último: una sombra tiene que acabar donde acaba su trazo.
            i = if (i == puntos.size - 1) i + 1
            else minOf(i + salto, puntos.size - 1)
        }
        if (cuantos < 2) return
        drawPath(
            camino, tinta,
            style = Stroke(width = gordo, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    // **Y solo la de lo que se ve.** Una sombra se pinta punto a punto como el trazo que la
    // tira, así que sin descartarla antes el sol duplicaba el precio del croquis entero —también
    // el de lo que cae fuera de la pantalla—. La caja de la sombra es la del trazo, aplastada
    // contra el suelo y estirada por lo bajo que esté el sol: se descarta con dos proyecciones.
    val rasante = 1.0 / kotlin.math.max(kotlin.math.sin(sol.altura), SOL_MAS_RASANTE)
    for (t in croquis.trazos) {
        if (t.oculto) continue
        val caja = cajaDe(t.puntos)
        val donde = sol.sombraDe(caja.centro) ?: caja.centro
        if (fueraDeLaVista(donde, caja.radio * rasante, camara, ancho, alto, 0.0)) continue
        val gordo = ((t.calibre?.let { it * camara.zoom } ?: t.grosor)).dp.toPx()
        // Cuántos puntos pueden llegar a distinguirse en la sombra: los mismos que en el
        // trazo, medidos sobre lo que ocupa en la pantalla y con la misma holgura.
        val enElCentro = camara.aPantalla(donde, ancho, alto)
        val enLaOrilla = camara.aPantalla(
            mas(donde, por(camara.derecha, caja.radio * rasante)), ancho, alto
        )
        val loQueOcupa = kotlin.math.hypot(
            enLaOrilla.x - enElCentro.x, enLaOrilla.y - enElCentro.y
        ).toFloat() * 2
        val puedenDistinguirse =
            (HOLGURA_DEL_SALTO * loQueOcupa / (JUNTOS * LaCalidad.grano)).toInt() + 4
        val salto =
            if (t.puntos.size > puedenDistinguirse * 2) t.puntos.size / puedenDistinguirse
            else 1
        sombraDe(t.puntos, gordo.coerceAtLeast(1f), salto)
    }
    // Una imagen tira la sombra de su contorno: por dentro es una foto, y una foto no dice
    // qué parte suya es opaca.
    for (i in croquis.imagenes) {
        if (i.oculto || i.esquinas.size < 4) continue
        sombraDe(i.esquinas + i.esquinas.first(), 2.dp.toPx(), 1)
    }
}

/**
 * Lo más rasante que se cuenta el sol al medir hasta dónde llega una sombra.
 *
 * Con el sol en el horizonte la sombra se va al infinito, y una caja infinita no descarta
 * nada; con este suelo, una sombra muy larga se da por visible y se pinta, que es lo correcto
 * cuando no se sabe.
 */
private const val SOL_MAS_RASANTE = 0.15

/**
 * De qué color va cada sombra.
 *
 * La del sol, negra y de un tercio: es una sombra, no una mancha. La de la luna, **azulada
 * y la mitad de floja**: la luna alumbra poco y frío, y una sombra de luna tan marcada como
 * la del mediodía no se lee como de noche, se lee como un error.
 */
private val LA_DEL_SOL = Color.Black.copy(alpha = 0.33f)
private val LA_DE_LA_LUNA = Color(0xFF2B3A67).copy(alpha = 0.20f)

/**
 * **Un lazo del corte de la punta**: una figura cerrada, con un color por lado.
 *
 * Los puntos van en orden y dentro del cuadrado de lado dos centrado en el cero —sin tamaño,
 * que lo pone el grueso—, y el color del punto `k` pinta el lado que va de `k` a `k + 1`.
 */
private class LazoDelCorte(val puntos: List<Pt>, val colores: IntArray)

/**
 * **Un lazo, siempre en el mismo sentido.**
 *
 * De esto depende que se vea algo. La cara que hay entre dos puntos del lazo sale de un
 * producto vectorial, así que **hacia dónde mira lo decide el sentido en el que da la vuelta
 * la figura**; y como del sólido se pintan solo las caras que miran hacia aquí, un lazo del
 * revés tiene todas sus caras mirando hacia dentro y **no se pinta ni una**: la tinta
 * desaparece entera, no a trozos.
 *
 * Las secciones de fábrica vienen bien dadas, pero la cinta que envuelve un rasgo sale al
 * contrario —va por un lado y vuelve por el otro, y eso invierte la vuelta—, así que no puede
 * quedar a merced de cómo se escribiera cada una. Se enderezan todas aquí, y los colores se
 * giran con la figura para que el lado `k` siga siendo el lado `k`.
 */
private fun lazoDelCorte(puntos: List<Pt>, colores: IntArray): LazoDelCorte {
    if (areaConSigno(puntos) >= 0) return LazoDelCorte(puntos, colores)
    val n = colores.size
    return LazoDelCorte(
        puntos.reversed(),
        IntArray(n) { colores[((n - 2 - it) % n + n) % n] }
    )
}

/**
 * El área con signo de una figura cerrada: **su signo es hacia qué lado da la vuelta**.
 *
 * Positiva es la vuelta buena, la que deja las caras del sólido mirando hacia fuera. Ver
 * [lazoDelCorte].
 */
internal fun areaConSigno(figura: List<Pt>): Double {
    if (figura.size < 3) return 0.0
    var suma = 0.0
    for (i in figura.indices) {
        val a = figura[i]
        val b = figura[(i + 1) % figura.size]
        suma += a.x * b.y - b.x * a.y
    }
    return suma / 2
}

/**
 * **El corte de la punta**: de qué está hecha la marca que deja contra el papel.
 *
 * ## Por qué son varios lazos y no una silueta
 *
 * Estuvo un rato siendo **la silueta** de lo dibujado —el contorno convexo que lo envuelve—,
 * y eso resuelve el color de las caras pero se come la forma: **una uve sale rellena**.
 * Dibujas una uve, que por dentro está hueca, y lo que se pinta es un triángulo, porque el
 * contorno convexo de una uve es un triángulo. Y una punta en uve no es un triángulo: es lo
 * que la hace una punta en uve.
 *
 * Así que el corte es **un lazo por rasgo**: la cinta que envuelve el rasgo tal y como se
 * trazó, con su grueso. Una uve da una cinta en uve, y el hueco de en medio es hueco de
 * verdad porque no hay nada ahí. Tres bandas dan tres cintas, cada una con su color, su cara
 * de arriba, su costado y su espalda; y una figura maciza —el cuadrado del listón, la sección
 * que trae una tinta— es un lazo suyo tal cual, que ya venía cerrado.
 *
 * ## Y siguen teniendo vuelta
 *
 * Que es lo que hacía falta y lo que sigue estando: un lazo cerrado tiene lados que miran a
 * sitios distintos del mundo, así que girando la vista se ponen de cara unos y de espaldas
 * otros. Lo que se quitó de en medio no es el volumen: es el relleno que nadie pidió.
 *
 * [propios] dice si los colores los puso uno, y entonces se sombrean poco — son suyos.
 */
private class CorteDeLaPunta(
    val lazos: List<LazoDelCorte>,
    val propios: Boolean = false
) {
    val vale: Boolean get() = lazos.isNotEmpty()

    /** Cuántas caras tiene en total. Es lo que cuesta pintarlo. */
    val caras: Int get() = lazos.sumOf { it.puntos.size }
}

/**
 * **Los cortes ya sacados, guardados por marca.**
 *
 * Armar el corte cuesta lo que ocupa la marca, y eso se hacía **en cada fotograma y para cada
 * trazo**: con doscientos trazos de la misma tinta, la misma cuenta doscientas veces por
 * fotograma. Y no cambia nunca: una tinta es la que es.
 *
 * Se guarda por **quién es la marca y no por cómo es**: la lista de rasgos de un trazo es el
 * mismo objeto mientras el trazo exista, así que compararla por identidad cuesta nada, y
 * compararla por contenido costaría justo lo que se quería ahorrar. El detalle se redondea a
 * saltos ([DETALLE_A_SALTOS]): al acercarse y alejarse, el que pide el corte cambia de
 * continuo, y con un corte guardado por cada número la memoria se llenaría de figuras que no
 * se distinguen entre sí.
 */
private class LlaveDelCorte(val marca: List<TrazoDeLaPunta>, val tapa: Int, val detalle: Int) {
    override fun hashCode(): Int =
        (System.identityHashCode(marca) * 31 + tapa) * 31 + detalle

    override fun equals(other: Any?): Boolean =
        other is LlaveDelCorte && other.marca === marca &&
            other.tapa == tapa && other.detalle == detalle
}

/** Los últimos cortes armados, y se tira el que lleve más sin usarse. */
private val cortesGuardados =
    object : LinkedHashMap<LlaveDelCorte, CorteDeLaPunta>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<LlaveDelCorte, CorteDeLaPunta>
        ): Boolean = size > CORTES_GUARDADOS
    }

/** Cuántos cortes se guardan. Muchos más de los que tiene un croquis a la vez. */
private const val CORTES_GUARDADOS = 96

/** El corte de una marca, armado una vez y guardado. Ver [corteDeLaMarca]. */
private fun corteGuardadoDeLaMarca(
    marca: List<TrazoDeLaPunta>,
    tapa: Int,
    detalle: Int
): CorteDeLaPunta =
    cortesGuardados.getOrPut(LlaveDelCorte(marca, tapa, detalle)) {
        corteDeLaMarca(marca, tapa, detalle)
    }

/** El corte de una marca dibujada a mano: **una cinta por rasgo, tal y como se trazó**. */
private fun corteDeLaMarca(
    marca: List<TrazoDeLaPunta>,
    tapa: Int,
    detalle: Int
): CorteDeLaPunta {
    val lazos = ArrayList<LazoDelCorte>(marca.size)
    for (rasgo in marca) {
        val tono = parseColor(rasgo.color, tapa)
        val cinta = cintaDelRasgo(rasgo.puntos, (rasgo.grosor / 2).coerceAtLeast(MINIMO_DEL_RASGO), detalle)
        if (cinta.size >= 3) lazos += lazoDelCorte(cinta, IntArray(cinta.size) { tono })
    }
    return CorteDeLaPunta(lazos, propios = true)
}

/** El corte de una sección de siempre —el cuadrado del listón, la que trae la tinta—. */
private fun corteDeLaSeccion(seccion: List<Pt>, tono: Int): CorteDeLaPunta =
    if (seccion.size < 3) CorteDeLaPunta(emptyList())
    else CorteDeLaPunta(listOf(lazoDelCorte(seccion, IntArray(seccion.size) { tono })))

/**
 * **La cinta que envuelve un rasgo**: el rasgo con su grueso, cerrado.
 *
 * Un rasgo es una raya trazada a mano y lo que deja contra el papel es esa raya **con su
 * ancho**: una cinta que va por un lado, da la vuelta a la punta y vuelve por el otro. Eso es
 * lo que respeta la forma —una uve sigue siendo una uve, hueca por dentro— frente a envolver
 * lo dibujado en su contorno convexo, que la rellenaba.
 *
 * Se aligera antes a [detalle] puntos por lado, repartidos **por recorrido y no de tantos en
 * tantos**: cogiendo uno de cada N se pierden justo los puntos donde la mano iba despacio —o
 * sea, las curvas— y un círculo sale cuadrado. Y el número lo pide lo gordo que se ve el
 * trazo, que es lo que hace que una escena llena de trazos pequeños cueste lo que ocupa.
 *
 * Un toque suelto —quien pone un punto en el taller lo pone a propósito— da una cinta redonda
 * de su tamaño, que es lo que deja una punta redonda.
 */
internal fun cintaDelRasgo(puntos: List<Pt>, radio: Double, detalle: Int): List<Pt> {
    val pocos = repartidosEnElCorte(puntos, detalle)
    if (pocos.size < 2) {
        val c = pocos.firstOrNull() ?: return emptyList()
        val lados = (detalle + 2).coerceIn(6, 14)
        return (0 until lados).map {
            val t = 2 * Math.PI * it / lados
            Pt(c.x + kotlin.math.cos(t) * radio, c.y + kotlin.math.sin(t) * radio)
        }
    }
    val n = pocos.size
    // La perpendicular en cada punto, promediando la de los tramos que llegan y salen: con la
    // del tramo, la cinta pega un escalón en cada codo.
    val perpendiculares = ArrayList<Pt>(n)
    var ultima = Pt(0.0, 1.0)
    for (i in 0 until n) {
        val a = pocos[if (i == 0) 0 else i - 1]
        val b = pocos[if (i == n - 1) n - 1 else i + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val largo = kotlin.math.hypot(dx, dy)
        if (largo > 1e-9) ultima = Pt(-dy / largo, dx / largo)
        perpendiculares += ultima
    }
    val ida = ArrayList<Pt>(n)
    val vuelta = ArrayList<Pt>(n)
    for (i in 0 until n) {
        val p = pocos[i]
        val q = perpendiculares[i]
        ida += Pt(p.x + q.x * radio, p.y + q.y * radio)
        vuelta += Pt(p.x - q.x * radio, p.y - q.y * radio)
    }
    // Y las dos puntas cortadas a escuadra, medio radio hacia fuera: sin ellas la cinta acaba
    // en pico y una punta en pico deja el trazo afilándose donde no debe.
    fun haciaFuera(cual: Int, vecino: Int): Pt {
        val d = Pt(pocos[cual].x - pocos[vecino].x, pocos[cual].y - pocos[vecino].y)
        val largo = kotlin.math.hypot(d.x, d.y)
        return if (largo < 1e-9) Pt(0.0, 0.0) else Pt(d.x / largo * radio, d.y / largo * radio)
    }
    val alFinal = haciaFuera(n - 1, n - 2)
    val alPrincipio = haciaFuera(0, 1)
    return ida +
        listOf(
            Pt(ida[n - 1].x + alFinal.x, ida[n - 1].y + alFinal.y),
            Pt(vuelta[n - 1].x + alFinal.x, vuelta[n - 1].y + alFinal.y)
        ) +
        vuelta.asReversed() +
        listOf(
            Pt(vuelta[0].x + alPrincipio.x, vuelta[0].y + alPrincipio.y),
            Pt(ida[0].x + alPrincipio.x, ida[0].y + alPrincipio.y)
        )
}

/** El rasgo con menos puntos, repartidos por recorrido: la curva se mantiene. */
private fun repartidosEnElCorte(puntos: List<Pt>, cuantos: Int): List<Pt> {
    if (puntos.size <= cuantos || cuantos < 2) return puntos
    val tramos = puntos.zipWithNext().map { (a, b) -> kotlin.math.hypot(b.x - a.x, b.y - a.y) }
    val total = tramos.sum()
    if (total < 1e-12) return listOf(puntos.first())
    val salida = ArrayList<Pt>(cuantos)
    var tramo = 0
    var llevado = 0.0
    for (k in 0 until cuantos) {
        val meta = total * k / (cuantos - 1)
        while (tramo < tramos.size - 1 && llevado + tramos[tramo] < meta) {
            llevado += tramos[tramo]; tramo++
        }
        val dentro = if (tramos[tramo] < 1e-12) 0.0 else (meta - llevado) / tramos[tramo]
        val a = puntos[tramo]
        val b = puntos[tramo + 1]
        salida += Pt(a.x + (b.x - a.x) * dentro, a.y + (b.y - a.y) * dentro)
    }
    return salida
}

/** Lo más fino que puede ser un rasgo. Por debajo, la cinta se cerraría sobre la línea. */
private const val MINIMO_DEL_RASGO = 0.02

/**
 * **El «arriba» del corte, y nunca el de la cámara.**
 *
 * Es la perpendicular de la hoja sobre la que se trazó: con ella, el corte de la punta va
 * clavado en el mundo y girando la vista se le ven las caras. Los croquis de antes de que se
 * guardara la hoja no la traen, y ahí lo que había era **el arriba de la cámara**: eso es un
 * cartel que siempre te mira —se gira la vista, el corte gira con ella y por muchas vueltas
 * que uno dé sale siempre la misma cara—, que es justo lo que no puede hacer la pintura.
 *
 * Sin hoja apuntada se usa el eje de la punta —que va dentro de la hoja, así que sirve de
 * referencia fija aunque no sea la normal— y, en último caso, la vertical del mundo. Los dos
 * están quietos, que es lo único que hace falta.
 */
private fun elEjeDelCorte(normal: Pt3?, ejeDeLaPunta: Pt3?): Pt3 =
    normal?.takeIf { largo(it) > 1e-9 }
        ?: ejeDeLaPunta?.takeIf { largo(it) > 1e-9 }
        ?: Pt3(0.0, 0.0, 1.0)

/**
 * **El trazo como sólido barrido: lazos con vuelta, y solo las caras que se ven.**
 *
 * ## Cómo se hace
 *
 * Cada lado de cada lazo del corte, barrido a lo largo del recorrido, da **una cara** del
 * sólido. Y de esas caras se pinta **solo la mitad**: la que mira hacia aquí. Es el descarte
 * de caras traseras de toda la vida, y aquí hace dos cosas a la vez —quita la mitad del
 * trabajo y quita la mitad de los errores—, porque una cara de detrás pintada encima de una
 * de delante es exactamente un trozo de trazo que desaparece.
 *
 * ## Por tramos, que es lo que arreglaba el trazo que se corta
 *
 * Una cara **no está de cara todo el rato**: en una curva, el mismo lado del corte mira hacia
 * aquí al principio del trazo y hacia allá al final. Mirándolo una sola vez —en el medio del
 * recorrido, que es lo que se hacía— la cara entera se pintaba o se tiraba de golpe, y de ahí
 * salía lo de «en las curvas desaparece a trozos»: media cara buena tirada, o media mala
 * pintada encima de lo bueno.
 *
 * Se mira **punto a punto** y se pintan los tramos seguidos en los que está de cara. Una
 * curva parte una cara en dos o tres trozos y cada uno va a su sitio, ordenados de atrás
 * adelante con todos los demás.
 *
 * ## El corte va clavado en el mundo
 *
 * Sus dos ejes son **la perpendicular al trazo dentro de la hoja** y **la normal de la
 * hoja**, las dos del mundo. Una tinta trazada tumbada se queda tumbada: al girar la vista se
 * la ve de canto, y de canto una figura plana es una raya —que es lo que pasa con la pintura
 * de verdad—. Nada de esto mira a la cámara.
 */
private fun DrawScope.barridoSolido(
    enElMundo: List<Pt3>,
    anchos: FloatArray,
    camara: Camara3D,
    ancho: Double,
    alto: Double,
    /** Hacia dónde mira la hoja sobre la que se trazó: es el «arriba» del corte. */
    eje: Pt3,
    corte: CorteDeLaPunta,
    punta: PuntaDelPincel,
    tinta: Int
) {
    val n = enElMundo.size
    if (n < 2 || !corte.vale) return

    // Los ejes del corte en cada punto del recorrido: la perpendicular al trazo dentro de
    // la hoja, y la normal de la hoja. Cuando el trazo va justo hacia la normal —dibujando
    // de canto— no hay perpendicular que valga y se hereda la del punto anterior, que es lo
    // que hace que la punta no pegue un vuelco en mitad de una curva.
    val arriba = normalizado(eje)
    val unos = arrayOfNulls<Pt3>(n)
    val otros = arrayOfNulls<Pt3>(n)
    var ultimoUno: Pt3? = null
    for (i in 0 until n) {
        val a = enElMundo[if (i == 0) 0 else i - 1]
        val b = enElMundo[if (i == n - 1) n - 1 else i + 1]
        val t = menos(b, a)
        var u = producto(arriba, t)
        if (largo(u) < 1e-9) u = ultimoUno ?: producto(arriba, Pt3(arriba.z, arriba.x, arriba.y))
        if (largo(u) < 1e-9) continue
        u = normalizado(u)
        ultimoUno = u
        unos[i] = u
        otros[i] = normalizado(producto(t, u)).takeIf { largo(it) > 1e-9 } ?: arriba
    }
    val algunUno = unos.firstNotNullOfOrNull { it } ?: return

    val cos = kotlin.math.cos(punta.angulo)
    val sen = kotlin.math.sin(punta.angulo)
    val estiraAncho = punta.ancho
    val estiraLargo = punta.ancho * punta.largo

    fun enElEspacio(i: Int, p: Pt): Pt3 {
        val u = unos[i] ?: algunUno
        val v = otros[i] ?: arriba
        val r = anchos[i] / (2.0 * camara.zoom)
        // Se ladea dentro de su propio plano —eso es girar la punta en la mano— y luego se
        // estira: el ancho por su eje y el largo por el otro.
        val x = (p.x * cos - p.y * sen) * estiraAncho * r
        val y = (p.x * sen + p.y * cos) * estiraLargo * r
        return mas(enElMundo[i], mas(por(u, x), por(v, -y)))
    }

    // Por dónde va el trazo en cada punto: con eso y el lado del corte sale hacia dónde mira
    // la cara que hay entre los dos.
    val avances = Array(n) { i ->
        val a = enElMundo[if (i == 0) 0 else i - 1]
        val b = enElMundo[if (i == n - 1) n - 1 else i + 1]
        menos(b, a)
    }

    val adelante = camara.adelante
    val trozos = ArrayList<TrozoDeCara>(corte.caras)

    for (lazo in corte.lazos) {
        val vueltas = lazo.puntos.size
        if (vueltas < 3) continue
        // Un riel por cada punto del lazo, proyectado una vez: lo usan las dos caras que lo
        // comparten, que es la mitad del trabajo de proyectar.
        val rieles = Array(vueltas) { k ->
            val p = lazo.puntos[k]
            ArrayList<Offset>(n).also { riel ->
                for (i in 0 until n) {
                    val v = camara.aPantalla(enElEspacio(i, p), ancho, alto)
                    riel += Offset(v.x.toFloat(), v.y.toFloat())
                }
            }
        }
        val hondosDeCadaUno = Array(vueltas) { k ->
            val p = lazo.puntos[k]
            DoubleArray(n) { i -> escalar(enElEspacio(i, p), adelante) }
        }

        // **Dónde está el dentro del sólido, para saber qué cara mira al aire.**
        //
        // El centro del corte y no el eje del trazo: hay cortes que **no envuelven al eje**
        // —la lámina de una cuchilla sale del trazo hacia un lado, no alrededor de él— y con
        // el eje por referencia sus caras salían indecisas. El centro del corte está dentro
        // de él por definición, así que apartarse de él es apartarse del sólido.
        val centroDelCorte = Pt(
            lazo.puntos.sumOf { it.x } / vueltas,
            lazo.puntos.sumOf { it.y } / vueltas
        )

        for (k in 0 until vueltas) {
            val otro = (k + 1) % vueltas
            val relieve = if (corte.propios) RELIEVE_DE_LO_DIBUJADO else RELIEVE_DE_LAS_CARAS
            val base = lazo.colores.getOrElse(k) { tinta }

            // De cara o de espaldas **en cada punto**, y los tramos seguidos que estén de
            // cara se pintan enteros. Ver la cabecera.
            var desde = -1
            var sumaHonda = 0.0
            var sumaDeCara = 0.0
            var cuantos = 0

            fun cerrar(hasta: Int) {
                if (desde < 0 || hasta - desde < 1) { desde = -1; return }
                val camino = Path()
                porUnLado(camino, rieles[k].subList(desde, hasta + 1), arranca = true)
                porUnLado(
                    camino, rieles[otro].subList(desde, hasta + 1).asReversed(), arranca = false
                )
                camino.close()
                val deCara = (sumaDeCara / cuantos).toFloat()
                trozos += TrozoDeCara(
                    sumaHonda / cuantos,
                    camino,
                    Color(aclarado(base, (deCara - 0.5) * relieve, (base shr 24) and 0xFF))
                )
                desde = -1
            }

            for (i in 0 until n) {
                val deAqui = enElEspacio(i, lazo.puntos[k])
                val deAlla = enElEspacio(i, lazo.puntos[otro])
                val lado = menos(deAlla, deAqui)
                // **Hacia dónde mira esta cara, sabiéndolo de verdad y no por convenio.**
                //
                // Aquí estaba lo de «los trazos se ven huecos por un lado y macizos por el
                // otro». La cara sale de un producto vectorial entre por dónde va el trazo y
                // el lado del corte, así que **hacia dónde apunta depende del sentido en que
                // se recorra el corte y del sentido en que se trazara la raya**: la misma
                // figura dibujada de izquierda a derecha o de derecha a izquierda daba
                // normales opuestas. Y del descarte de caras traseras depende todo: con las
                // normales del revés se pintaban las de detrás y se descartaban las de
                // delante, o sea que se veía el sólido **por dentro** — un tubo hueco.
                //
                // El sentido no hay que suponerlo, se sabe: el corte envuelve el recorrido,
                // así que una cara suya **se aparta del eje del trazo**. Si la normal apunta
                // hacia el eje, es que está del revés y se le da la vuelta. Con eso, cada
                // trazo es un sólido cerrado mire uno por donde mire: se pintan las caras que
                // dan al aire y se descartan las que quedan dentro del propio trazo, que es
                // lo único que hay que descartar.
                var cara = producto(avances[i], lado)
                val dentro = enElEspacio(i, centroDelCorte)
                val haciaElAire = Pt3(
                    (deAqui.x + deAlla.x) / 2 - dentro.x,
                    (deAqui.y + deAlla.y) / 2 - dentro.y,
                    (deAqui.z + deAlla.z) / 2 - dentro.z
                )
                if (escalar(cara, haciaElAire) < 0.0) cara = por(cara, -1.0)
                val mide = largo(cara)
                val mirando = if (mide < 1e-12) 0.0 else escalar(cara, adelante) / mide
                if (mirando < 0) {
                    if (desde < 0) {
                        desde = i; sumaHonda = 0.0; sumaDeCara = 0.0; cuantos = 0
                    }
                    sumaHonda += (hondosDeCadaUno[k][i] + hondosDeCadaUno[otro][i]) / 2
                    sumaDeCara += -mirando
                    cuantos++
                } else if (desde >= 0) {
                    // Se cierra **en este punto y no en el anterior**: el tramo tiene que
                    // llegar hasta donde la cara se pone de canto, o entre un trozo y el
                    // siguiente queda un hueco sin pintar.
                    sumaHonda += (hondosDeCadaUno[k][i] + hondosDeCadaUno[otro][i]) / 2
                    cuantos++
                    cerrar(i)
                }
            }
            cerrar(n - 1)
        }
    }

    trozos.sortByDescending { it.hondo }
    for (t in trozos) {
        drawPath(t.camino, t.tono)
        // Y un pelo de contorno del mismo color: dos caras vecinas comparten su borde
        // exactamente, pero cada una se pinta por su cuenta y con los cantos suavizados, así
        // que en la juntura asoma un hilo del fondo. Repasándolas por fuera no tiene por
        // dónde asomar, y no se puede abrir un agujero como sí lo abría rellenar la silueta.
        drawPath(t.camino, t.tono, style = Stroke(width = COSTURA_DE_LAS_CARAS))
    }

    // **La tapa de la punta, que aquí no es un adorno: es lo que cierra el sólido.**
    //
    // Barriendo el corte a lo largo del recorrido sale un tubo, y un tubo **está abierto por
    // los extremos**: mientras se pintaban también las caras de detrás, el fondo del tubo
    // hacía de tapón sin querer, pero descartándolas se ve el hueco — un trazo mirado por su
    // punta se quedaba en un anillo. Rellenando el corte ahí se cierra, y de paso es donde se
    // ve la figura de frente: los tres colores de una tinta, o el hueco de una punta en uve,
    // que **sigue siendo hueco** porque se rellenan los lazos y no lo que los envuelve.
    //
    // Solo la que mira hacia aquí: la otra queda detrás del sólido.
    for (i in listOf(0, n - 1)) {
        val haciaFuera = if (i == 0) menos(enElMundo[0], enElMundo[minOf(1, n - 1)])
        else menos(enElMundo[n - 1], enElMundo[maxOf(0, n - 2)])
        if (largo(haciaFuera) < 1e-12) continue
        val deFrente = -escalar(normalizado(haciaFuera), adelante)
        if (deFrente <= TAPA_QUE_SE_VE) continue
        tapaDelCorte(corte, i, ::enElEspacio, camara, ancho, alto)
    }
}

/**
 * **El corte de una cuchilla**: fino de través y alto hacia arriba, colgando del trazo.
 *
 * Va de cero a menos dos en su eje alto porque ahí es donde el barrido pone el trazo: el
 * corte se coloca respecto de la raya que se trazó, y la cuchilla **sale de ella**, no la
 * envuelve. Así la raya sigue siendo el canto de arriba de la lámina, que es lo que uno acaba
 * de dibujar con el dedo. Ver [barridoSolido].
 */
private fun elCorteDeLaCuchilla(): List<Pt> = listOf(
    Pt(-GRUESO_DE_LA_CUCHILLA, -2.0),
    Pt(GRUESO_DE_LA_CUCHILLA, -2.0),
    Pt(GRUESO_DE_LA_CUCHILLA, 0.0),
    Pt(-GRUESO_DE_LA_CUCHILLA, 0.0)
)

/**
 * Lo gorda que es una cuchilla, en tanto por uno de lo que baja.
 *
 * Un tabique: lo justo para que se le vea el canto al girar y no se lea como una barra. Cero
 * no vale — una lámina sin espesor desaparece de canto, que es de lo que se venía.
 */
private const val GRUESO_DE_LA_CUCHILLA = 0.05

/** Un tramo de una cara del sólido, listo para pintar: lo lejos que está, su forma y su tono. */
private class TrozoDeCara(val hondo: Double, val camino: Path, val tono: Color)

/**
 * La tapa de una punta del trazo: **el corte, relleno, en el sitio en el que está**.
 *
 * Se rellena **lazo a lazo y no lo que los envuelve**, que es la diferencia entre tapar el
 * tubo y volver a rellenar la figura: el hueco de una punta en uve no pertenece a ningún
 * lazo, así que sigue siendo hueco también visto de frente. Y cada lazo va de su color, con
 * lo que la tapa enseña de una vez los tres colores de una tinta de tres bandas.
 */
private fun DrawScope.tapaDelCorte(
    corte: CorteDeLaPunta,
    i: Int,
    enElEspacio: (Int, Pt) -> Pt3,
    camara: Camara3D,
    ancho: Double,
    alto: Double
) {
    for (lazo in corte.lazos) {
        if (lazo.puntos.size < 3) continue
        val camino = Path()
        lazo.puntos.forEachIndexed { k, p ->
            val v = camara.aPantalla(enElEspacio(i, p), ancho, alto)
            if (k == 0) camino.moveTo(v.x.toFloat(), v.y.toFloat())
            else camino.lineTo(v.x.toFloat(), v.y.toFloat())
        }
        camino.close()
        val tono = Color(lazo.colores.firstOrNull() ?: continue)
        drawPath(camino, tono)
        // El mismo pelo de contorno que llevan las caras, por lo mismo: la tapa y la cara que
        // muere en ella comparten borde, y sin repasarlo asoma el fondo por la juntura.
        drawPath(camino, tono, style = Stroke(width = COSTURA_DE_LAS_CARAS))
    }
}

/**
 * Con cuántos puntos se aligera cada rasgo del corte.
 *
 * Con los que pida **lo gordo que se ve el trazo en la pantalla**, y no siempre los mismos: un
 * trazo de dos píxeles no necesita doce puntos por rasgo para parecerse a lo que se dibujó, y
 * una escena llena de trazos pequeños se pintaba entera al detalle de un primer plano. Es la
 * misma cuenta que hace cualquier nivel de detalle, y aquí se nota en la mano.
 */
private fun detalleDelCorte(anchoEnPantalla: Float, cuantosRasgos: Int): Int {
    var pide = (anchoEnPantalla * 0.6f).toInt().coerceIn(DETALLE_MINIMO, DETALLE_MAXIMO)
    // **Y repartido entre los rasgos que haya, con un techo para el corte entero.**
    //
    // Cada rasgo da su propia cinta, y cada punto de la cinta es una cara que se barre a lo
    // largo de todo el recorrido. Sin techo, una tinta de cinco rasgos cuesta cinco veces una
    // de uno **con el mismo trazo**, y eso es exactamente lo de «algunas tintas son muy
    // pesadas»: no lo era la tinta, era que nadie le había puesto un límite. Con el techo, una
    // tinta rica se dibuja igual de rica y cuesta lo que cuesta una normal.
    val leTocan = (TOPE_DE_CARAS / maxOf(1, cuantosRasgos) - LO_QUE_CIERRA_LA_CINTA) / 2
    pide = minOf(pide, leTocan)
    // A saltos, para que acercarse y alejarse no pida un corte distinto en cada fotograma.
    return ((pide + DETALLE_A_SALTOS - 1) / DETALLE_A_SALTOS * DETALLE_A_SALTOS)
        .coerceIn(DETALLE_MINIMO, DETALLE_MAXIMO)
}

private const val DETALLE_MINIMO = 3
private const val DETALLE_MAXIMO = 12
private const val DETALLE_A_SALTOS = 3

/**
 * Cuántas caras puede tener el corte de una tinta, todas sus cintas juntas.
 *
 * Cuarenta y ocho. Cada una se barre a lo largo del recorrido entero, así que el trabajo de
 * pintar un trazo es sus caras por sus puntos: es el número que decide si una tinta pesa.
 */
private const val TOPE_DE_CARAS = 48

/** Los cuatro puntos que le cierran las dos puntas a una cinta. Ver [cintaDelRasgo]. */
private const val LO_QUE_CIERRA_LA_CINTA = 4

/** Lo que se repasa por fuera cada cara para que no asome la juntura, en píxeles. */
private const val COSTURA_DE_LAS_CARAS = 0.8f

/** Cuánto se aclara u oscurece una cara según cómo esté puesta. Lo justo para leerla. */
private const val RELIEVE_DE_LAS_CARAS = 0.85

/**
 * Y lo que se mueve la de una tinta dibujada a mano: **poco**.
 *
 * Los colores de una tinta los eligió alguien, y lo que hace falta no es repintarlos sino
 * que se distingan unas caras de otras. Con el relieve de una punta lisa, un rojo y un
 * naranja de la misma tinta acababan siendo cuatro tonos que no eran ninguno de los dos.
 */
private const val RELIEVE_DE_LO_DIBUJADO = 0.30

/** Desde qué escorzo se pinta la tapa de una punta. Más de canto, es una raya. */
private const val TAPA_QUE_SE_VE = 0.12

/** Cuánto se pisan las cerdas y cuántas puede tener un rasgo como mucho. */
private const val SOLAPE_DE_LAS_CERDAS = 0.5
private const val CERDAS_POR_RASGO = 20

/**
 * **El barrido de un perfil a lo largo del trazo.**
 *
 * La figura de la punta se estampa en cada punto del recorrido y se cosen las estampaciones
 * consecutivas con una banda: lo que queda relleno es exactamente por donde ha pasado la
 * punta. Es la suma de Minkowski del recorrido con la sección, dicho sin decirlo, y es lo
 * que hace que una punta en cuña deje trazo grueso en una dirección y fino en la de al lado
 * sin que haya que programar esa regla en ninguna parte: sale de la forma.
 *
 * Todas las piezas se meten en **un solo camino y con la misma vuelta**, así que al
 * rellenarlo con la regla de siempre se unen en una figura: sin eso, dos estampaciones
 * superpuestas se restarían y aparecerían agujeros justo donde el trazo va más lento.
 */
private fun barridoDelPerfil(
    en: List<Offset>,
    anchos: FloatArray,
    punta: PuntaDelPincel,
    ladeo: Double
): Path {
    val perfil = punta.perfil ?: return Path()
    val cos = kotlin.math.cos(ladeo)
    val sen = kotlin.math.sin(ladeo)
    val ancho = punta.ancho.coerceIn(0.15, 6.0)
    val alto = punta.largo.coerceIn(0.15, 6.0)
    // La sección, estirada de ancho y de alto y ladeada lo que se pida. Se deja siempre con
    // la misma vuelta para que las piezas se sumen en vez de restarse.
    val forma = enSuVuelta(
        perfil.map { p ->
            val x = p.x * ancho
            val y = p.y * alto
            Offset((x * cos - y * sen).toFloat(), (x * sen + y * cos).toFloat())
        }
    )

    val camino = Path()
    for (i in en.indices) {
        estampar(camino, en[i], forma, anchos[i] / 2)
        if (i > 0) coser(camino, en[i - 1], en[i], forma, anchos[i - 1] / 2, anchos[i] / 2)
    }
    return camino
}

/** La misma figura, siempre en el mismo sentido. */
private fun enSuVuelta(forma: List<Offset>): List<Offset> =
    if (area(forma) < 0) forma.reversed() else forma

/** El área con signo de un polígono: su signo es hacia qué lado da la vuelta. */
private fun area(puntos: List<Offset>): Float {
    var suma = 0f
    for (i in puntos.indices) {
        val a = puntos[i]
        val b = puntos[(i + 1) % puntos.size]
        suma += a.x * b.y - b.x * a.y
    }
    return suma / 2
}

/** La sección estampada en un punto del recorrido. */
private fun estampar(camino: Path, donde: Offset, forma: List<Offset>, radio: Float) {
    forma.forEachIndexed { i, v ->
        val x = donde.x + v.x * radio
        val y = donde.y + v.y * radio
        if (i == 0) camino.moveTo(x, y) else camino.lineTo(x, y)
    }
    camino.close()
}

/**
 * La banda entre dos estampaciones: **por dónde ha pasado la punta al ir de una a otra**.
 *
 * Se cose por las dos esquinas de la figura que más sobresalen de través, que son las que
 * dejan huella al avanzar. Las demás quedan dentro de las propias estampaciones.
 */
private fun coser(
    camino: Path,
    desde: Offset,
    hasta: Offset,
    forma: List<Offset>,
    radioDesde: Float,
    radioHasta: Float
) {
    val dx = hasta.x - desde.x
    val dy = hasta.y - desde.y
    val largo = kotlin.math.hypot(dx, dy)
    if (largo < 1e-4f) return
    val perpX = -dy / largo
    val perpY = dx / largo
    var arriba = forma[0]
    var abajo = forma[0]
    var masArriba = -Float.MAX_VALUE
    var masAbajo = Float.MAX_VALUE
    for (v in forma) {
        val cuanto = v.x * perpX + v.y * perpY
        if (cuanto > masArriba) { masArriba = cuanto; arriba = v }
        if (cuanto < masAbajo) { masAbajo = cuanto; abajo = v }
    }
    val banda = listOf(
        Offset(desde.x + arriba.x * radioDesde, desde.y + arriba.y * radioDesde),
        Offset(hasta.x + arriba.x * radioHasta, hasta.y + arriba.y * radioHasta),
        Offset(hasta.x + abajo.x * radioHasta, hasta.y + abajo.y * radioHasta),
        Offset(desde.x + abajo.x * radioDesde, desde.y + abajo.y * radioDesde)
    )
    estampar(camino, Offset.Zero, enSuVuelta(banda), 1f)
}

/**
 * La cinta que deja una punta de canto: **el rastro de un filo al barrer**.
 *
 * Va como una figura rellena y no como una raya gruesa: el ancho de la cinta no lo pone el
 * pincel, lo pone hacia dónde va el trazo, y eso es justo lo que hace que una punta
 * alargada engorde de través y adelgace a lo largo. De una pieza además, que es lo que
 * permite que una tinta translúcida no salga con las juntas más oscuras.
 *
 * El canto va orientado **respecto de la pantalla** y no del mundo. Es una decisión: fijado
 * al mundo, el trazo conservaría su carácter al girar la vista, pero girar dejaría de
 * responder a cómo uno mira. Fijado a la pantalla, girar es girar el papel bajo la
 * plumilla, que es un gesto que existe y que se entiende.
 */
private fun cintaAlSesgo(en: List<Offset>, largoDelFilo: Float, cos: Float, sen: Float): Path {
    val px = cos * largoDelFilo
    val py = sen * largoDelFilo
    // Con las curvas suaves, como cualquier otra tinta: uniendo los puntos con rectas, una
    // plumilla enseñaba cada esquinita de la ristra, y una tinta tiene que salir fluida sea
    // la que sea.
    val figura = Path()
    porUnLado(figura, en.map { Offset(it.x + px, it.y + py) }, arranca = true)
    porUnLado(figura, en.asReversed().map { Offset(it.x - px, it.y - py) }, arranca = false)
    figura.close()
    return figura
}

/** El filo largo de una cinta, corrido a un lado del canto. */
private fun DrawScope.bisel(
    en: List<Offset>,
    corrimiento: Float,
    tono: Color,
    cos: Float,
    sen: Float
) {
    val camino = Path()
    porUnLado(
        camino,
        en.map { Offset(it.x + cos * corrimiento, it.y + sen * corrimiento) },
        arranca = true
    )
    drawPath(
        camino, tono,
        style = Stroke(
            width = FILO_DE_LA_CINTA.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

/** Cuánto se mete hacia dentro el filo de una cinta, y lo gordo que va, en dp. */
private const val DENTRO_DEL_BISEL = 0.86f
private const val FILO_DE_LA_CINTA = 0.8

/** El pelo que deja una punta de canto yendo justo a lo largo de su filo. */
private const val CANTO_DE_LA_PUNTA = 0.24f

/**
 * **Las pasadas de rodillo, repartidas por el plano en el que están.**
 *
 * Cada montón se vuelca de una vez en su propia capa, y eso es lo que hace que dos pasadas
 * cruzadas no se sumen: ver [pintarLosRodillos]. Así que de este reparto depende lo único
 * que un resaltador tiene que cumplir — **dentro de un mismo plano, todo del mismo tono**.
 *
 * ## Por qué con holgura y no con una clave
 *
 * Iba por una clave de texto: el color, lo que tapa y el plano, escritos con unos decimales
 * y comparados como cadenas. Y una clave así **se parte por nada**. Dos pasadas sobre la
 * misma hoja dan la misma distancia al plano hasta el último bit; en cuanto la cuenta se
 * hace con un punto u otro del trazo, la diferencia es de milésimas — y si esa milésima cae
 * justo encima de un redondeo, una pasada escribe `10.00` y la otra `10.01`. Dos claves, dos
 * montones, dos capas: **y donde se cruzan, el doble de oscuro**. Es un fallo que aparece y
 * desaparece según dónde se haya empezado a rayar, que es lo peor que puede hacer un fallo.
 *
 * Con holgura no hay borde que cruzar: cada pasada se une al montón que ya tenga su color y
 * su plano, y solo abre uno nuevo si de verdad está en otro sitio.
 */
private fun rodillosPorPlano(
    rodillos: List<Trazo3D>,
    seleccion: Set<String>
): List<List<Trazo3D>> {
    val montones = ArrayList<ArrayList<Trazo3D>>()
    for (t in rodillos) {
        val suyo = montones.firstOrNull { elMismoPlano(it.first(), t, seleccion) }
        if (suyo != null) suyo += t else montones += arrayListOf(t)
    }
    return montones
}

/** Si dos pasadas son de la misma tinta y están en el mismo plano. */
private fun elMismoPlano(a: Trazo3D, b: Trazo3D, seleccion: Set<String>): Boolean {
    // Lo elegido va de otro color, así que no puede ir en la misma capa que lo que no lo está.
    if ((a.id in seleccion) != (b.id in seleccion)) return false
    if (a.color != b.color) return false
    if (abs(a.opacidad - b.opacidad) > 1e-3) return false
    val na = a.normal
    val nb = b.normal
    if ((na == null) != (nb == null)) return false
    if (na == null || nb == null) return true
    // Que miren al mismo sitio, y que estén a la misma altura de él. El uno menos coseno se
    // compara contra un ángulo de nada: dos trazos de la misma hoja traen la misma normal
    // salvo el ruido de las cuentas.
    if (escalar(na, nb) < COSENO_DEL_MISMO_PLANO) return false
    val da = escalar(na, a.puntos.first())
    val db = escalar(nb, b.puntos.first())
    // La holgura crece con lo lejos que esté el plano del origen, porque el ruido también.
    return abs(da - db) <= HOLGURA_DEL_PLANO * (1.0 + abs(da))
}

/** Lo poco que pueden diferir dos normales para ser la misma. Medio grado. */
private val COSENO_DEL_MISMO_PLANO = kotlin.math.cos(Math.toRadians(0.5))

/** Y lo poco que pueden diferir dos alturas sobre ese plano, en tanto por uno. */
private const val HOLGURA_DEL_PLANO = 1e-3


/**
 * **Un grupo de rodillos, volcado de una vez.**
 *
 * Las bandas se pintan opacas dentro de una capa aparte —ahí dentro se pisan sin sumarse,
 * porque lo opaco tapa a lo opaco y queda igual— y la capa entera se vuelca sobre el dibujo
 * con la transparencia de la tinta. El resultado es una mancha de **un solo tono** por muy
 * cruzadas que vayan las pasadas, que es lo que deja un rodillo de verdad sobre una pared.
 *
 * La capa se abre solo sobre lo que ocupan las bandas y no sobre la pantalla entera: una
 * capa aparte es memoria y una pasada más del pintor, y no hay razón para pagarla por los
 * píxeles donde no hay nada.
 */
private fun DrawScope.pintarLosRodillos(
    grupo: List<Trazo3D>,
    color: String,
    camara: Camara3D,
    ancho: Double,
    alto: Double
) {
    // **Y un grosor mínimo también aquí.** Una pasada de rodillo mide lo que mide en el
    // espacio, así que alejándose se estrecha; por debajo de un pelo de píxel deja de verse
    // y lo resaltado deja de estar resaltado, que es lo único que se le pide.
    val minimo = MINIMO_DEL_RESALTADO.dp.toPx()

    // **Todas tumbadas en su hoja.** Se salían de ahí por dos caminos —el trazo sin medida
    // en el mundo, y la banda que se quedaba fina— y los dos daban lo mismo: un cartel que
    // gira con la vista en vez de una raya de pintura pegada al plano. Ahora la medida se
    // saca del aumento cuando no está guardada, y la banda fina se ensancha **en el
    // mundo** hasta el mínimo, que la deja visible sin despegarla de la hoja: sigue
    // escorzándose y sigue viéndose de canto al mirarla de perfil.
    val cintas = grupo.mapNotNull { t ->
        if (t.puntos.size < 2) return@mapNotNull null
        val normal = t.normal ?: return@mapNotNull null
        val enElMundo = (t.calibre ?: (t.grosor / camara.zoom)).dp.toPx()
        val enPantalla = (enElMundo * camara.zoom).toFloat()
        val puesto =
            if (enPantalla >= minimo) enElMundo else enElMundo * (minimo / enPantalla)
        // Las dos caras de la mano de pintura: la pegada a la hoja y la de su espesor.
        // Ver [conEspesor].
        val espesor = puesto * ESPESOR_DEL_RODILLO
        cintaEnElPlano(t.puntos, normal, t.normales, puesto, camara, ancho, alto) to
            cintaEnElPlano(
                t.puntos, normal, t.normales, puesto, camara, ancho, alto,
                despegada = espesor
            )
    }
    // Sin plano al que agarrarse no hay nada que tumbar: un croquis de antes de que la banda
    // supiera sobre qué hoja está se pinta de cara, que es como se pintaba.
    val deCara = grupo.mapNotNull { t ->
        if (t.puntos.size < 2 || t.normal != null) return@mapNotNull null
        val enPantalla = (t.calibre?.let { it * camara.zoom } ?: t.grosor).dp.toPx()
        cintaDeCara(t.puntos, maxOf(enPantalla, minimo), camara, ancho, alto)
    }
    if (cintas.isEmpty() && deCara.isEmpty()) return

    // La franja entera se arma **una vez**: sirve para saber sobre qué píxeles hay que
    // abrir la capa y para pintarla. Armarla dos veces es recorrer el trazo dos veces.
    // Las dos caras se arman **una vez**: sirven para saber sobre qué píxeles hay que abrir
    // la capa y para pintarlas. Armarlas dos veces es recorrer el trazo dos veces.
    val enteras = cintas.map { (abajo, arriba) ->
        abajo.caminoDe(1f, 0f) to arriba.caminoDe(1f, 0f)
    }
    var caja: Rect? = null
    fun meter(r: Rect) { caja = caja?.expandirHasta(r) ?: r }
    for ((abajo, arriba) in enteras) { meter(abajo.getBounds()); meter(arriba.getBounds()) }
    for (c in deCara) meter(c.getBounds())
    val marco = caja ?: return

    val opaco = Color(parseColor(color, 255))
    val pincel = Paint().also {
        it.alpha = ALFA_DEL_RESALTADOR / 255f * grupo.first().opacidad.toFloat()
    }
    drawContext.canvas.saveLayer(marco, pincel)
    for ((k, caras) in enteras.withIndex()) {
        val (deAbajo, deArriba) = cintas[k]
        conEspesor(deAbajo, deArriba, caras.first, caras.second, parseColor(color, 255))
    }
    for (c in deCara) drawPath(c, opaco)
    drawContext.canvas.restore()
}

/**
 * **Una franja de rodillo, de un solo tono.** Dentro de la capa que la vuelca.
 *
 * Llevó un rato tres tiras —la franja, un canto de sombra y uno de brillo— para darle
 * bulto, y **está mal**: dentro de la capa lo opaco tapa a lo opaco, así que el canto de una
 * pasada cae encima del cuerpo de la de al lado y lo que aparece es una raya donde no hay
 * ninguna, y una zona más oscura donde se cruzan. Un rotulador de subrayar no hace eso: deja
 * una mancha lisa, pases una vez o pases cinco.
 *
 * El bulto de esta tinta **no es sombreado, es que está tumbada en la hoja**: mide lo que
 * mide en el mundo, se escorza al girar la vista y se ve de canto de perfil. Eso es lo que
 * la pone en el espacio; pintarle luces encima solo rompe lo único que un resaltador tiene
 * que cumplir, que es ser uniforme. Ver [cintaEnElPlano].
 */
private fun DrawScope.conCuerpo(entera: Path, tinta: Int) {
    drawPath(entera, Color(tinta))
}

/**
 * **Una mano de pintura tiene canto**: la cara de abajo, el espesor y la de arriba.
 *
 * Salía como una superficie sin espesor —una franja pegada a la hoja y nada más—, y eso se
 * ve como una mancha recortada en el papel, no como pintura puesta encima. Una mano de
 * pintura de verdad **sobresale**: mirándola de lado se le ve el canto, y por eso se sabe
 * que está encima y no dentro.
 *
 * Son dos caras y no un sólido: la de abajo pegada a la hoja y en un tono más oscuro —es la
 * que queda en sombra debajo del reborde— y la de arriba a su espesor y del color entero.
 * Entre las dos, la diferencia al proyectarlas deja ver el canto por donde la vista lo pilla
 * de lado, y de frente se tapan la una a la otra y se ve la mancha lisa de siempre. Que es
 * exactamente lo que hace una mano de pintura: de frente no tiene espesor y de lado sí.
 *
 * Dos rellenos y no una malla de canto porque están **dentro de la capa** que funde las
 * pasadas ([pintarLosRodillos]): ahí dentro lo opaco tapa a lo opaco, así que un canto
 * dibujado aparte de una pasada saldría cruzando por encima del cuerpo de la de al lado —que
 * es el fallo de las rayas que ya se quitó una vez—. Dos caras del mismo tono no pueden
 * hacer eso.
 */
private fun DrawScope.conEspesor(
    abajo: CintaEnElPlano,
    arriba: CintaEnElPlano,
    caraDeAbajo: Path,
    caraDeArriba: Path,
    tinta: Int
) {
    drawPath(caraDeAbajo, Color(aclarado(tinta, -MEZCLA_DEL_CANTO, 255)))

    // **Y las paredes que las unen, que es lo que la hace un sólido.**
    //
    // Eran dos caras paralelas y nada entre ellas: la de abajo pegada a la hoja y la de
    // arriba flotando un pelo por encima. De frente colaba, y girando hasta ponerla de canto
    // se veía lo que era — **dos láminas sueltas con aire en medio**, sin nada que se leyera
    // como el canto de una mano de pintura. Una mano de pintura tiene borde: se ve dónde
    // acaba y cuánto levanta del papel.
    //
    // Las paredes salen de coser el mismo borde de las dos cintas, de un lado y del otro, y
    // las tapas de coser los dos bordes en cada punta. Con eso, la pasada es una caja cerrada
    // y mirada desde donde sea se ve como pintura puesta encima, no como un calco.
    val canto = Color(aclarado(tinta, -MEZCLA_DEL_CANTO / 2, 255))
    for (haciaDonde in floatArrayOf(1f, -1f)) {
        val pared = Path()
        porUnLado(pared, abajo.bordeDe(haciaDonde), arranca = true)
        porUnLado(pared, arriba.bordeDe(haciaDonde).asReversed(), arranca = false)
        pared.close()
        drawPath(pared, canto)
    }
    // Las dos puntas, que si no la pasada se ve hueca por donde empieza y por donde acaba.
    val unLado = abajo.bordeDe(1f)
    val otroLado = abajo.bordeDe(-1f)
    val unLadoArriba = arriba.bordeDe(1f)
    val otroLadoArriba = arriba.bordeDe(-1f)
    for (i in intArrayOf(0, unLado.size - 1)) {
        if (i < 0) continue
        val tapa = Path()
        tapa.moveTo(unLado[i].x, unLado[i].y)
        tapa.lineTo(otroLado[i].x, otroLado[i].y)
        tapa.lineTo(otroLadoArriba[i].x, otroLadoArriba[i].y)
        tapa.lineTo(unLadoArriba[i].x, unLadoArriba[i].y)
        tapa.close()
        drawPath(tapa, canto)
    }

    drawPath(caraDeArriba, Color(tinta))
}

/**
 * El espesor de una mano de pintura, en tanto por uno de lo ancha que es la pasada.
 *
 * Una cuarentava parte: lo justo para que se le vea el canto de lado y no se note de frente.
 * Sale del ancho y no de un número fijo porque una pasada de rodillo ancha se da con más
 * pintura, igual que una raya gorda deja más tinta.
 */
private const val ESPESOR_DEL_RODILLO = 1.0 / 40.0

/** Cuánto se oscurece la cara de abajo, que es la que queda en sombra bajo el reborde. */
private const val MEZCLA_DEL_CANTO = 0.30


/** La caja que cabe a las dos. */
private fun Rect.expandirHasta(otra: Rect) = Rect(
    minOf(left, otra.left), minOf(top, otra.top),
    maxOf(right, otra.right), maxOf(bottom, otra.bottom)
)

/**
 * La cinta de cara al que mira: media anchura a cada lado de lo que se ve.
 *
 * Es lo que les queda a los rodillos de los croquis viejos, que no guardaron sobre qué hoja
 * se dieron. Ver [cintaTumbada], que es lo que se hace ahora.
 */
private fun cintaDeCara(
    puntos: List<Pt3>,
    ancho: Float,
    camara: Camara3D,
    anchoDeLaVista: Double,
    altoDeLaVista: Double
): Path {
    val en = puntos.map {
        val v = camara.aPantalla(it, anchoDeLaVista, altoDeLaVista)
        Offset(v.x.toFloat(), v.y.toFloat())
    }
    val perpendiculares = perpendicularesDe(en)
    val medio = ancho / 2
    val izquierda = en.mapIndexed { i, p ->
        Offset(p.x + perpendiculares[i].x * medio, p.y + perpendiculares[i].y * medio)
    }
    val derecha = en.mapIndexed { i, p ->
        Offset(p.x - perpendiculares[i].x * medio, p.y - perpendiculares[i].y * medio)
    }
    val camino = Path()
    porUnLado(camino, izquierda, arranca = true)
    porUnLado(camino, derecha.asReversed(), arranca = false)
    camino.close()
    return camino
}

/**
 * La cinta del rodillo, **tumbada sobre la hoja**.
 *
 * Los dos bordes se apartan del trazo por dentro del plano en el que se dio la pasada: la
 * perpendicular sale del producto de la normal de la hoja por hacia dónde va el trazo, así
 * que la banda queda pegada a la hoja y **escorzada como ella**. Girando la vista se ve
 * cada vez más de canto, hasta quedar en una raya: que es lo que hace una raya de pintura
 * sobre una pared cuando uno se pone de perfil.
 *
 * El ancho va en unidades del mundo, no en píxeles: el rodillo dio la pasada que dio, y esa
 * pasada mide lo que mide esté uno mirando desde donde esté.
 *
 * Si el trazo va justo en la dirección de la normal —cosa que no puede pasar dibujando
 * sobre la hoja, pero sí con puntos amontonados— se hereda la perpendicular anterior, o el
 * borde se cerraría en un pico.
 */
/**
 * **La cinta del rodillo, tumbada en la hoja.** Sus dos bordes y su eje, ya en la pantalla.
 *
 * Una pasada de rodillo es pintura **sobre** el papel: no es una cosa que flote y mire a
 * quien la mira, es una franja pegada a la hoja. Así que los dos bordes se calculan en el
 * mundo —a media anchura a cada lado, **por dentro del plano**— y se proyectan como
 * cualquier otra cosa del croquis. Girando la vista se escorza y acaba viéndose de canto,
 * que es lo que le pasa a una raya de pintura sobre una mesa.
 *
 * Construida en la pantalla era un cartel que siempre te mira: la banda giraba con la vista
 * y se despegaba del dibujo. Y las puntas, igual: se cortan a ras **dentro del plano**, así
 * que el eje y los dos extremos están siempre en la hoja, sin casquetes que asomen por
 * fuera del renglón.
 *
 * Se guarda el eje y el medio lado de cada punto —los dos ya proyectados— para poder sacar
 * la banda a la anchura que se quiera y corrida hacia donde se quiera, que es lo que hace
 * falta para darle cuerpo. Ver [caminoDe].
 */
private class CintaEnElPlano(val eje: List<Offset>, val lado: List<Offset>)

/**
 * La cinta de [puntos], de [ancho] en el mundo, tumbada en el plano de [normal].
 *
 * El lado sale de multiplicar la normal del plano por la dirección del trazo: eso da la
 * perpendicular **dentro del plano**, que es la única que deja la banda pegada a la hoja.
 */
private fun cintaEnElPlano(
    puntos: List<Pt3>,
    normal: Pt3,
    /**
     * La perpendicular de la hoja **en cada punto**, si la hoja se curva. Ver
     * [Trazo3D.normales].
     *
     * Con una sola perpendicular para todo el trazo, una banda sobre una bola se aparta
     * hacia dentro en cuanto la superficie ha girado, y lo que queda es una franja doblada
     * sobre sí misma con trozos que no se ven: el «se pinta incompleto» de subrayar una
     * esfera. Con la de cada punto, la banda va pegada a la superficie de punta a punta.
     */
    normales: List<Pt3>?,
    ancho: Float,
    camara: Camara3D,
    anchoDeLaVista: Double,
    altoDeLaVista: Double,
    /**
     * Si la cinta se levanta de la hoja en vez de tumbarse en ella.
     *
     * Tumbada es el rodillo: una franja de pintura pegada al papel. De pie es la cuchilla:
     * un tabique que sale de él. La cuenta es la misma y solo cambia hacia dónde se aparta
     * de la línea — dentro del plano o por su normal—, que es exactamente la diferencia
     * entre las dos cosas. Ver [Pincel.CUCHILLA].
     */
    dePie: Boolean = false,
    /**
     * Cuánto se despega de la hoja, en unidades del mundo.
     *
     * Es el espesor de una mano de pintura: la cara de arriba de la franja va aquí y la de
     * abajo va pegada a la hoja. Ver [conCuerpo].
     */
    despegada: Double = 0.0
): CintaEnElPlano {
    val n = puntos.size
    val medio = ancho / 2.0
    val eje = ArrayList<Offset>(n)
    val lado = ArrayList<Offset>(n)
    var ultimo = por(normalizado(normal), medio)
    for (i in 0 until n) {
        val a = puntos[if (i == 0) 0 else i - 1]
        val b = puntos[if (i == n - 1) n - 1 else i + 1]
        val suya = normales?.getOrNull(i) ?: normal
        // De pie, el lado **es** la normal y no depende de por dónde vaya el trazo: una
        // cuchilla baja siempre por la perpendicular de la hoja, dé el trazo la vuelta que
        // dé. Tumbada sí depende: la franja va de través a por donde se pasa el rodillo.
        val cruz = if (dePie) suya else producto(suya, menos(b, a))
        if (largo(cruz) > 1e-9) ultimo = por(normalizado(cruz), medio)
        val donde =
            if (despegada == 0.0) puntos[i]
            else mas(puntos[i], por(normalizado(suya), despegada))
        val centro = camara.aPantalla(donde, anchoDeLaVista, altoDeLaVista)
        val orilla = camara.aPantalla(mas(donde, ultimo), anchoDeLaVista, altoDeLaVista)
        eje += Offset(centro.x.toFloat(), centro.y.toFloat())
        lado += Offset((orilla.x - centro.x).toFloat(), (orilla.y - centro.y).toFloat())
    }
    return CintaEnElPlano(eje, lado)
}

/**
 * La banda a [parte] de su anchura, corrida [corrimiento] hacia la luz.
 *
 * Es la misma cuenta que el contorno de las demás tintas ([contornoDe]) hecha sobre el lado
 * del plano en vez de sobre la perpendicular de la pantalla: a parte entera y sin correr
 * sale la franja completa, y a media parte corrida sale la tira del canto. Las tapas van a
 * ras y no redondas, porque un rodillo empieza donde lo posas y acaba donde lo levantas.
 */
/**
 * Uno de los dos bordes de la cinta, en la pantalla.
 *
 * Hace falta para levantarle las paredes: una mano de pintura tiene dos caras **y un canto**,
 * y el canto va de un borde de la de abajo al mismo borde de la de arriba. Ver [conEspesor].
 */
private fun CintaEnElPlano.bordeDe(haciaDonde: Float): List<Offset> =
    List(eje.size) { i ->
        Offset(eje[i].x + lado[i].x * haciaDonde, eje[i].y + lado[i].y * haciaDonde)
    }

private fun CintaEnElPlano.caminoDe(
    parte: Float,
    corrimiento: Float,
    /**
     * Si la banda **cuelga del eje** en vez de repartirse a los dos lados.
     *
     * Es la diferencia entre el rodillo y la cuchilla, y era el fallo de la cuchilla. Una
     * mano de pintura se reparte a los dos lados de por donde pasó el rodillo: el trazo va
     * por su centro. Una cuchilla no: **la raya que se traza es su canto**, y la lámina sale
     * de ahí hacia dentro de la hoja. Repartida a los dos lados, medio tabique quedaba por
     * encima de donde uno había trazado —el «un lado relleno y el otro vacío» al verla de
     * canto— y el filo, que va por el eje, partía la lámina por la mitad en vez de
     * rematarla por arriba. Ver [Pincel.CUCHILLA].
     */
    desdeElEje: Boolean = false
): Path {
    val n = eje.size
    val izquierda = ArrayList<Offset>(n)
    val derecha = ArrayList<Offset>(n)
    for (i in 0 until n) {
        val medio = lado[i]
        // Cuánto se corre hacia la luz: lo mismo que hacen las demás tintas, proyectando una
        // luz fija sobre el lado de la banda. Con la banda apuntando a la luz no se corre
        // nada, que es lo que le pasa de verdad a una franja alumbrada de punta.
        val cuanto = corrimiento * (medio.x * LUZ_X + medio.y * LUZ_Y)
        val centro = Offset(eje[i].x + medio.x * cuanto, eje[i].y + medio.y * cuanto)
        if (desdeElEje) {
            // Un borde es el eje y el otro, la hondura entera: así lo que mide de alto es lo
            // que dice el mando, y no la mitad.
            izquierda += centro
            derecha += Offset(centro.x + medio.x * parte * 2, centro.y + medio.y * parte * 2)
        } else {
            izquierda += Offset(centro.x + medio.x * parte, centro.y + medio.y * parte)
            derecha += Offset(centro.x - medio.x * parte, centro.y - medio.y * parte)
        }
    }
    val camino = Path()
    porUnLado(camino, izquierda, arranca = true)
    porUnLado(camino, derecha.asReversed(), arranca = false)
    camino.close()
    return camino
}


/**
 * **El pulso del trazo**: cuánto engorda por apretar y cuánto adelgaza por correr.
 *
 * Es lo único que separa una raya de un trazo a mano alzada. Una línea de ancho parejo se
 * lee como algo trazado con instrumento; la mano deja siempre una línea que nace fina,
 * engorda donde se apoya, adelgaza en los sitios rápidos y se va afilando al levantar. Sin
 * eso, un apunte a mano alzada se ve como un diagrama.
 *
 * ## De dónde sale
 *
 * De dos sitios, y se multiplican:
 *
 * - **De lo que aprieta la punta**, cuando el lápiz óptico lo mide. Es lo bueno: es
 *   exactamente lo que hizo la mano.
 * - **De lo rápido que iba**, siempre. Se lee en lo separados que quedaron los puntos —el
 *   trazo se guarda cada tantos, así que ir deprisa los separa— y se compara con lo
 *   separados que están **en ese mismo trazo**: así vale igual con el dedo que con lápiz, a
 *   cualquier aumento y a cualquier velocidad de mano, sin ninguna constante que calibrar.
 *
 * Con el dedo, o con un lápiz que no mida presión, llega todo a uno: entonces manda la
 * velocidad, que es lo que hace cualquiera que dibuja simulando presión.
 *
 * ## Y las rectas no
 *
 * Un trazo de dos o tres puntos —una raya con regla, o una enderezada por pararse— sale de
 * ancho parejo. Afinándole las puntas, una recta se convertiría en una lenteja, y una
 * arista tiene que ser una arista.
 */
private fun pulsoDe(en: List<Offset>, aprieta: DoubleArray, cuantos: Int): FloatArray {
    val n = en.size
    if (n < MINIMO_PARA_EL_PULSO) return FloatArray(n) { 1f }

    // Lo separados que están los puntos, y lo separados que están de normal en este trazo.
    val huecos = FloatArray(n)
    for (i in 1 until n) huecos[i] = (en[i] - en[i - 1]).getDistance()
    huecos[0] = huecos[1]
    // La mediana **sin ordenar una copia**: con la selección rápida de siempre, que es la
    // misma cuenta sin fabricar un array nuevo por trazo y por fotograma.
    val tipico = laDeEnMedio(huecos.copyOf(), n).coerceAtLeast(1e-3f)

    // ¿Dice algo la presión? Si llega toda igual —el dedo, o un lápiz que no la mide—, no
    // se le hace caso: multiplicar por una constante solo adelgazaría el trazo entero.
    var floja = Double.MAX_VALUE
    var fuerte = -Double.MAX_VALUE
    for (i in 0 until minOf(cuantos, n)) {
        if (aprieta[i] < floja) floja = aprieta[i]
        if (aprieta[i] > fuerte) fuerte = aprieta[i]
    }
    val hayPresion = (fuerte - floja) > VARIACION_QUE_CUENTA

    val salida = FloatArray(n)
    for (i in 0 until n) {
        val deprisa = ((huecos[i] / tipico - 1f) / RANGO_DEL_PULSO).coerceIn(0f, 1f)
        var suyo = 1f - deprisa * (1f - MINIMO_DEL_PULSO)
        if (hayPresion) {
            suyo *= (MINIMO_DEL_PULSO + (1f - MINIMO_DEL_PULSO) * aprieta[i].toFloat())
        }
        // Y las dos puntas se afilan: es donde la mano posa y levanta, y un trazo que
        // acaba en un tajo recto no parece hecho a mano.
        val delBorde = minOf(i, n - 1 - i)
        if (delBorde < PUNTAS_QUE_SE_AFILAN) {
            val cuanto = (delBorde + 1f) / (PUNTAS_QUE_SE_AFILAN + 1f)
            suyo *= AFILADO + (1f - AFILADO) * cuanto
        }
        salida[i] = suyo
    }

    // Alisado de tres: sin él, un punto suelto que llegó lejos deja un estrangulamiento en
    // mitad del trazo, y se ve como un fallo y no como pulso.
    val liso = FloatArray(n)
    for (i in 0 until n) {
        val a = salida[if (i == 0) 0 else i - 1]
        val b = salida[if (i == n - 1) n - 1 else i + 1]
        liso[i] = (a + salida[i] * 2f + b) / 4f
    }
    return liso
}

/**
 * El valor de en medio de los primeros [cuantos], **sin ordenarlos todos**.
 *
 * Es la selección rápida de toda la vida: se reparte alrededor de un pivote y se sigue solo
 * por el lado en el que cae la mitad. Ordenar entero cuesta más y además fabricaba un array
 * nuevo por trazo y por fotograma. Se le pasa una copia porque reparte de sitio.
 */
private fun laDeEnMedio(de: FloatArray, cuantos: Int): Float {
    var desde = 0
    var hasta = cuantos - 1
    val meta = cuantos / 2
    while (desde < hasta) {
        val pivote = de[(desde + hasta) / 2]
        var i = desde
        var j = hasta
        while (i <= j) {
            while (de[i] < pivote) i++
            while (de[j] > pivote) j--
            if (i <= j) {
                val t = de[i]; de[i] = de[j]; de[j] = t
                i++; j--
            }
        }
        if (meta <= j) hasta = j else if (meta >= i) desde = i else break
    }
    return de[meta]
}

/**
 * Por debajo de cuántos puntos no hay pulso que valga: es una raya, no un trazo.
 *
 * Y lo demás: cuánto adelgaza lo rápido, a cuántas veces el hueco normal se llega a ese
 * mínimo, cuánta variación tiene que traer la presión para hacerle caso, cuántos puntos se
 * afilan en cada punta y a cuánto llegan.
 */
private const val MINIMO_PARA_EL_PULSO = 6
private const val MINIMO_DEL_PULSO = 0.55f
private const val RANGO_DEL_PULSO = 2.0f
private const val VARIACION_QUE_CUENTA = 0.02
private const val PUNTAS_QUE_SE_AFILAN = 3
private const val AFILADO = 0.45f

/**
 * Cómo acaba un trazo: en casquete, en escuadra o a ras.
 *
 * [PLANA] corta justo donde se levantó la mano y no asoma nada: es la del resaltador, que
 * empieza y acaba en el canto del renglón. [CUADRADA] sí sobresale media anchura, que es
 * lo que hace una barra de sección cuadrada al acabar.
 */
private enum class Tapa { REDONDA, CUADRADA, PLANA }

/**
 * El contorno del trazo: **la figura que hay que rellenar para que salga el trazo**.
 *
 * Se va por un lado apartándose media anchura de cada punto, se da la vuelta a la punta,
 * se vuelve por el otro y se cierra. Como la anchura se pide punto a punto, el trazo puede
 * engordar y adelgazar a lo largo sin una sola juntura — que es lo que quita las cuentas
 * que dejaba pintarlo a tramos.
 *
 * Los dos lados pasan por sus puntos con cuadráticas por los puntos medios, igual que
 * pasaba antes el eje: sin eso, un trazo trazado despacio enseña una esquinita por punto.
 *
 * El corrimiento aparta el trazo entero por su perpendicular, hacia el lado en que le da la
 * luz: es lo que deja poner el cuerpo un poco descentrado dentro de la silueta y que
 * asome, por el lado de la sombra, la media luna que hace el bulto.
 *
 * Se rellena con la regla de siempre —lo que queda dentro, dentro—, así que en una curva
 * muy cerrada, donde el contorno se cruza consigo mismo, el trazo sale macizo en vez de
 * agujereado.
 */
/**
 * **Los cuadernos del contorno**, que se reaprovechan de una capa a la siguiente.
 *
 * Armar el contorno de un trazo son tres listas y un camino, y hay tintas que lo arman
 * varias veces seguidas para el mismo trazo —la luz, que se pinta como un degradado de
 * varios peldaños—. Con las listas nuevas cada vez, una sola luz deja dos docenas de objetos
 * por fotograma para el basurero, y eso no se ve como memoria: se ve como el tirón cada
 * pocos segundos.
 *
 * Vale por lo mismo que [Borrador]: **todo esto se pinta en un solo hilo** y un contorno no
 * se arma dentro de otro — se arma, se manda a pintar y se arma el siguiente. El camino
 * también se reaprovecha, porque mandar a pintar copia lo que hay dentro.
 */
private object CuadernoDelContorno {
    val izquierda = ArrayList<Offset>(256)
    val derecha = ArrayList<Offset>(256)
    val centros = ArrayList<Offset>(256)
    var radios = FloatArray(256)
    val camino = Path()

    fun listo(n: Int) {
        izquierda.clear()
        derecha.clear()
        centros.clear()
        if (radios.size < n) radios = FloatArray(n)
        camino.rewind()
    }
}

private fun contornoDe(
    en: List<Offset>,
    perpendiculares: List<Offset>,
    anchos: FloatArray,
    parte: Float,
    corrimiento: Float,
    tapa: Tapa
): Path {
    val n = en.size
    CuadernoDelContorno.listo(n)
    val izquierda = CuadernoDelContorno.izquierda
    val derecha = CuadernoDelContorno.derecha
    val centros = CuadernoDelContorno.centros
    val radios = CuadernoDelContorno.radios
    for (i in 0 until n) {
        val perpendicular = perpendiculares[i]
        val radio = (anchos[i] * parte / 2).coerceAtLeast(MINIMO_DEL_RADIO)
        val cuanto =
            corrimiento * anchos[i] * (perpendicular.x * LUZ_X + perpendicular.y * LUZ_Y)
        val centro = Offset(
            en[i].x + perpendicular.x * cuanto,
            en[i].y + perpendicular.y * cuanto
        )
        radios[i] = radio
        centros += centro
        izquierda += Offset(centro.x + perpendicular.x * radio, centro.y + perpendicular.y * radio)
        derecha += Offset(centro.x - perpendicular.x * radio, centro.y - perpendicular.y * radio)
    }

    val camino = CuadernoDelContorno.camino
    porUnLado(camino, izquierda, arranca = true)
    tapaDe(camino, centros[n - 1], radios[n - 1], izquierda[n - 1], derecha[n - 1], haciaFuera(en, n - 1), tapa)
    porUnLado(camino, derecha.asReversed(), arranca = false)
    tapaDe(camino, centros[0], radios[0], derecha[0], izquierda[0], haciaFuera(en, 0), tapa)
    camino.close()
    return camino
}

/** Un lado del contorno, con sus curvas suaves. */
private fun porUnLado(camino: Path, lado: List<Offset>, arranca: Boolean) {
    val n = lado.size
    if (arranca) camino.moveTo(lado[0].x, lado[0].y) else camino.lineTo(lado[0].x, lado[0].y)
    for (i in 1 until n - 1) {
        val a = lado[i]
        val b = lado[i + 1]
        camino.quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
    }
    camino.lineTo(lado[n - 1].x, lado[n - 1].y)
}

/** Hacia dónde sale el trazo por una de sus puntas, para saber por dónde cerrarla. */
private fun haciaFuera(en: List<Offset>, punta: Int): Offset {
    val vecino = if (punta == 0) 1 else punta - 1
    val d = Offset(en[punta].x - en[vecino].x, en[punta].y - en[vecino].y)
    val largo = kotlin.math.hypot(d.x, d.y)
    return if (largo < 1e-4f) Offset(1f, 0f) else Offset(d.x / largo, d.y / largo)
}

/**
 * La punta del trazo: el casquete que la cierra, o la escuadra del listón.
 *
 * Redonda va por el lado que da a fuera —la mitad de circunferencia que pasa por delante
 * de la punta— y no por el de dentro, que cortaría el trazo por la mitad.
 */
private fun tapaDe(
    camino: Path,
    centro: Offset,
    radio: Float,
    desde: Offset,
    hasta: Offset,
    salida: Offset,
    tapa: Tapa
) {
    if (tapa == Tapa.PLANA) {
        camino.lineTo(hasta.x, hasta.y)
        return
    }
    if (tapa == Tapa.CUADRADA) {
        camino.lineTo(desde.x + salida.x * radio, desde.y + salida.y * radio)
        camino.lineTo(hasta.x + salida.x * radio, hasta.y + salida.y * radio)
        camino.lineTo(hasta.x, hasta.y)
        return
    }
    val arranque = atan2((desde.y - centro.y).toDouble(), (desde.x - centro.x).toDouble())
    // Por delante de la punta: se barre hacia el lado en el que está la salida.
    var haciaLaSalida = atan2(salida.y.toDouble(), salida.x.toDouble()) - arranque
    while (haciaLaSalida > Math.PI) haciaLaSalida -= Math.PI * 2
    while (haciaLaSalida < -Math.PI) haciaLaSalida += Math.PI * 2
    val sentido = if (haciaLaSalida >= 0) 1.0 else -1.0
    for (k in 1..PASOS_DE_LA_TAPA) {
        val angulo = arranque + sentido * Math.PI * k / PASOS_DE_LA_TAPA
        camino.lineTo(
            centro.x + (cos(angulo) * radio).toFloat(),
            centro.y + (sin(angulo) * radio).toFloat()
        )
    }
}

/**
 * La perpendicular en cada punto, promediando la de los tramos que llegan y salen.
 *
 * Promediada y no la del tramo: con la del tramo, el corrimiento hacia la luz pega un
 * salto en cada punto y las capas corridas salen con escalones justo donde el trazo dobla,
 * que es donde más se mira.
 *
 * Y cuando dos puntos caen en el mismo sitio no hay perpendicular que valga: se hereda la
 * del punto anterior. Devolviendo un cero, el contorno se cerraría a un pincho ahí mismo.
 */
private fun perpendicularesDe(en: List<Offset>): List<Offset> {
    val n = en.size
    val salida = ArrayList<Offset>(n)
    var ultima = Offset(0f, 1f)
    for (i in 0 until n) {
        val a = en[if (i == 0) 0 else i - 1]
        val b = en[if (i == n - 1) n - 1 else i + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val largo = kotlin.math.hypot(dx, dy)
        if (largo >= 1e-4f) {
            var nx = -dy / largo
            var ny = dx / largo
            // **La perpendicular no se da la vuelta.** Donde el trazo vuelve sobre sí mismo
            // —la subida y la bajada de una «l», el cierre de una «o»— la tangente se
            // invierte y con ella la perpendicular: el borde izquierdo pasaba a ser el
            // derecho, los dos bordes se cruzaban en la punta y ahí salía el escaloncito que
            // reportó el usuario. Manteniendo el mismo lado que la anterior, los dos tramos
            // comparten pasillo y la punta se cierra sola.
            if (nx * ultima.x + ny * ultima.y < 0f) { nx = -nx; ny = -ny }
            ultima = Offset(nx, ny)
        }
        salida += ultima
    }
    return salida
}

/**
 * Lo más fino que se pinta una línea, en `dp`.
 *
 * Un trazo mide lo que mide en el espacio y por eso de lejos se ve fino, que está bien; lo
 * que no está bien es que se vea **menos de un píxel**, porque entonces no se ve y el croquis
 * se deshace por los bordes justo al alejarse para mirarlo entero. Poco más de un pelo: lo
 * justo para que la línea siga estando sin que un croquis alejado se convierta en una maraña
 * de rayas todas del mismo gordo.
 */
private const val MINIMO_EN_PANTALLA_DEL_TRAZO = 1.1

/**
 * Por debajo de este ancho en pantalla, un trazo se pinta como una raya y no como un sólido.
 *
 * Dos dedos de píxel: por debajo de eso, el corte de la punta —sea el que sea— no puede
 * enseñar nada que no sea una raya del color de la tinta.
 */
private const val LO_QUE_YA_ES_UNA_RAYA = 1.6

/**
 * Y lo más fina que se pinta una pasada de rodillo, en `dp`.
 *
 * Un pelo más que la de un trazo: un resaltado es una banda, y una banda de un píxel se lee
 * como una raya de color en vez de como algo subrayado.
 */
private const val MINIMO_DEL_RESALTADO = 2.0

/**
 * Qué fracción de su propio ancho es el paso mínimo entre dos puntos del eje de un trazo.
 *
 * Un tercio. Lo que hay dentro de eso no lo enseña una raya de ese grosor —se lo come ella
 * misma— y en cambio se paga entero: cada punto del eje se multiplica por las caras del corte
 * al barrer el sólido. Es el nivel de detalle del **recorrido**, hermano del del corte
 * ([detalleDelCorte]), y es el que más quita en un croquis lleno.
 */
private const val LO_QUE_SE_NOTA_DEL_ANCHO = 0.34f

/**
 * Cuántos puntos se le respetan a un trazo por gorda que sea su raya, pase lo que pase.
 *
 * Es el suelo del aligerado: por debajo de esto, lo que se está quitando ya no es detalle,
 * es la forma. Dieciséis bastan para que una curva se lea como una curva. Ver [pintarTrazo].
 */
private const val PUNTOS_QUE_HACEN_LA_FORMA = 16f

/**
 * A partir de qué coseno se considera que el trazo **se da la vuelta sobre sí mismo**.
 *
 * Medio negativo son ciento veinte grados: por debajo de eso, el lado de dentro del contorno
 * se cruza consigo mismo y deja el codo sin rellenar. Ver el `capa` de [pintarTrazo].
 */
private const val COSENO_DE_UNA_VUELTA = -0.5f

/**
 * A qué parte de su ancho va el tapón de una vuelta cerrada en las tintas que barren su
 * sección.
 *
 * Media: una sección vista de canto es más estrecha que su ancho, y el tapón tiene que caber
 * en cualquier postura sin asomar por los lados. Ver el `taponDeLaVuelta` de [pintarTrazo].
 */
private const val PARTE_DEL_TAPON = 0.5f

/**
 * A partir de qué doblez un punto se queda aunque caiga cerca del anterior.
 *
 * Coseno de unos veinticinco grados. Aligerando solo por distancia, un ángulo vivo trazado
 * despacio pierde justo el punto de la esquina y sale redondeado: se nota, y es de las cosas
 * que hacen que un croquis parezca dibujado por otro.
 */
private const val COSENO_DE_UNA_ESQUINA = 0.906f

/** Más cerca que esto, dos puntos del trazo son el mismo punto. En píxeles. */
private const val JUNTOS = 0.75f

/** Por debajo de este radio, en píxeles, una capa dejaría de verse. */
private const val MINIMO_DEL_RADIO = 0.35f

/** En cuántos tramos se parte el casquete de una punta. Ocho no se distingue de una curva. */
private const val PASOS_DE_LA_TAPA = 8

/**
 * El color, subido hacia lo más que da la pantalla **lo que diga el interruptor**: en cero
 * se queda como está y en uno llega al tope. Ver [aTodoBrillo].
 */
/**
 * **El color, encendido por encima del blanco.** Lo que hace que una luz sea una luz.
 *
 * Una pantalla pinta de negro a blanco y ahí se acaba: pintar «a tope» da exactamente el
 * mismo brillo que el papel que tiene al lado, así que por muy bien elegido que esté el
 * color, lo que se ve es una raya clara y no algo encendido. Es el techo de toda la tinta de
 * luz, y no se sube eligiendo mejor: **hace falta salirse del blanco**.
 *
 * Los paneles de ahora saben hacerlo —es lo que hace que una foto en HDR deslumbre junto al
 * texto de al lado— y se les pide escribiendo el color en un espacio de **rango extendido**,
 * donde uno no es el techo sino la referencia: dos es el doble de brillante que el blanco de
 * la interfaz. Ver [Croquis3DActivity.pedirElBrilloDeMas], que es quien pide el margen.
 *
 * ## Y lo manda la luz, no un interruptor
 *
 * Cuánto se sale del blanco es **cuánta luz lleva ese trazo**, multiplicado por la llave de
 * paso de todo el croquis. Es la misma cuenta que ya decidía lo blanco que salía el
 * filamento, y así sigue siendo una sola cosa la que se maneja: se baja la luz de un trazo y
 * deja de deslumbrar antes de dejar de verse, que es lo que hace una luz de verdad al bajarle
 * la corriente. Con la luz a cero no se sale nada del blanco y esto es tinta lisa. Ver
 * [Luces3D].
 *
 * ## Se pide, y si no lo hay no pasa nada
 *
 * En un panel sin margen —o con el brillo bajo, o con el ahorro puesto— el sistema recorta a
 * uno y lo que queda es exactamente lo de antes. Por eso no hay dos caminos de pintado: hay
 * uno, y el margen se lo pone la pantalla al componer.
 */
private fun DrawScope.pintarEncendido(camino: Path, argb: Int, cuantaLuz: Float) {
    // **Sin margen de brillo, ni se intenta.**
    //
    // Escribir el color en rango extendido solo sirve si la ventana tiene dónde subir, y eso
    // pide Android 15 y un panel que lo dé. Donde no lo hay —un teléfono con Android 12— el
    // sistema recorta el color a uno de todas formas, así que lo único que quedaba de esto era
    // el precio: un color en otro espacio obliga a convertirlo en cada pasada, y una luz son
    // seis pasadas por trazo y por fotograma. Ahí se pinta por el camino corto y **suma
    // igual**, que es lo que hace el resplandor. Ver [ElBrilloDeMas].
    if (!ElBrilloDeMas.hay) {
        drawPath(camino, Color(argb), blendMode = BlendMode.Plus)
        return
    }
    val alfa = ((argb ushr 24) and 0xFF) / 255f
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    // Uno es el blanco de la interfaz; de ahí para arriba, lo que la luz pida.
    val sube = 1f + (VECES_EL_BLANCO - 1f) * cuantaLuz.coerceIn(0f, 1f)
    val pincel = ELPINCEL_ENCENDIDO
    pincel.setColor(
        android.graphics.Color.pack(
            r * sube, g * sube, b * sube, alfa,
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.EXTENDED_SRGB)
        )
    )
    drawIntoCanvas { it.nativeCanvas.drawPath(camino.asAndroidPath(), pincel) }
}


/**
 * **El pincel de lo encendido, con el color escrito en sesenta y cuatro bits.**
 *
 * Aquí estaba lo que hacía que el brillo de más no se notara en absoluto. Un color de Compose
 * sabe llevar rango extendido —valores por encima de uno, que es lo que significa «más
 * brillante que el blanco»—, pero al mandarlo a pintar lo pasa por `toArgb()` y lo mete en
 * un entero de ocho bits por canal: **ahí se recorta a uno y no queda nada del margen**. Se
 * escribía la luz a cuatro veces el blanco y llegaba al cristal exactamente igual que
 * cualquier tinta clara, que es justo lo que se veía.
 *
 * El lienzo de Android sí sabe: `Paint.setColor(long)` toma un color empaquetado con su
 * espacio, y en `EXTENDED_SRGB` uno es el blanco de la interfaz y de ahí para arriba se
 * sigue. Así que las tintas que alumbran se pintan por el lienzo de debajo y las demás por
 * el camino de siempre — que es lo correcto además de lo barato: **solo las luces necesitan
 * esto**, y son las menos.
 *
 * Suman en vez de tapar, como antes: es lo que hace que donde una luz se cruza consigo misma
 * brille más, y lo que deja que lo de debajo se aclare en vez de desaparecer.
 *
 * Se reaprovecha uno solo por lo mismo que los cuadernos del contorno: esto se pinta en un
 * solo hilo y una luz no se pinta dentro de otra.
 *
 * **Y se fabrica al usarlo, no al cargar el archivo.** Un `Paint` es de Android, y montarlo
 * en la inicialización del archivo deja **todo lo que hay aquí dentro** sin poder cargarse
 * fuera de un dispositivo: las cuentas de las cintas y de los cortes se comprueban en la
 * JVM, y se caían todas con un error de inicialización que no decía nada de lo que pasaba.
 * Ver [Perimetros] y las pruebas del croquis.
 */
private val ELPINCEL_ENCENDIDO by lazy {
    android.graphics.Paint().also {
        it.isAntiAlias = true
        it.style = android.graphics.Paint.Style.FILL
        it.blendMode = android.graphics.BlendMode.PLUS
    }
}

/**
 * Cuánto se encienden el resplandor y el cuerpo de un tubo, respecto del filamento.
 *
 * El filamento va al máximo que dé la luz; el cuerpo a la mitad y el resplandor a un cuarto.
 * Es la misma caída que ya tenía el degradado y por la misma razón: lo que hace que algo se
 * lea como encendido es **que el centro deslumbre y los bordes no**, no que todo brille
 * igual — eso último es una mancha luminosa, que es otra cosa.
 */
private const val CUERPO_ENCENDIDO = 0.5f
private const val RESPLANDOR_ENCENDIDO = 0.25f

private fun haciaElBrillo(argb: Int, cuanto: Float): Int {
    if (cuanto <= 0f) return argb
    val techo = aTodoBrillo(argb)
    fun canal(desplazamiento: Int): Int {
        val a = (argb shr desplazamiento) and 0xFF
        val b = (techo shr desplazamiento) and 0xFF
        return (a + (b - a) * cuanto).toInt().coerceIn(0, 255)
    }
    return (argb.toLong() and 0xFF000000L).toInt() or
        (canal(16) shl 16) or (canal(8) shl 8) or canal(0)
}

/**
 * El mismo color, **a todo lo que da la pantalla**: el canal más alto puesto a tope.
 *
 * Mantiene el tono y sube el brillo hasta el techo, que es lo que uno espera de una luz:
 * un azul encendido es el azul más azul que la pantalla sabe dar, no un azul a media luz.
 * Un color casi negro se va al blanco, y está bien: un tubo apagado no es una tinta, es un
 * tubo que no se ve.
 */
private fun aTodoBrillo(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val techo = maxOf(r, g, b)
    if (techo == 0) return (argb.toLong() or 0xFFFFFFL).toInt()
    val cuanto = 255.0 / techo
    fun sube(v: Int) = (v * cuanto).toInt().coerceIn(0, 255)
    return (argb.toLong() and 0xFF000000L).toInt() or
        (sube(r) shl 16) or (sube(g) shl 8) or sube(b)
}

/** Un color acercado al blanco (positivo) o al negro (negativo), con el alfa que se pida. */
private fun aclarado(argb: Int, cuanto: Double, alfa: Int): Int {
    fun canal(desplazamiento: Int): Int {
        val v = (argb shr desplazamiento) and 0xFF
        val destino = if (cuanto >= 0) 255 else 0
        return (v + (destino - v) * kotlin.math.abs(cuanto)).toInt().coerceIn(0, 255)
    }
    return (alfa shl 24) or (canal(16) shl 16) or (canal(8) shl 8) or canal(0)
}

/** De qué color se pone lo elegido: el violeta de la aplicación. */
private const val COLOR_DE_LO_ELEGIDO = "#6965DB"

/** Cuánto adelgaza lo lejano y cuánto engorda lo cercano. */
private const val CUERPO_MINIMO = 0.75f
private const val CUERPO_RANGO = 0.85f

/**
 * De dónde viene la luz, en la pantalla: **de arriba y un poco a la izquierda**.
 *
 * Es de donde la pone todo el mundo desde que se dibuja en pantallas, y no por capricho:
 * el ojo da por hecho que la luz cae de arriba, y con ella al revés lo abultado se lee
 * como hundido. Un poco a la izquierda porque de frente y perfectamente vertical las
 * rayas horizontales se quedan sin lado claro.
 *
 * Fija en la pantalla y no en el mundo, igual que la punta de la plumilla: girar la vista
 * es girar el papel bajo el flexo, no llevarse el flexo.
 */
private const val LUZ_X = -0.5523f
private const val LUZ_Y = -0.8336f

/**
 * Las dos caras del listón y su arista: cuánto se aclara la que da a la luz, cuánto se
 * oscurece la otra y lo negro que va el filo que las separa.
 *
 * Es el único relieve que queda en toda la aplicación, y se queda porque ahí el relieve
 * **es** la punta: sin la arista, un listón es una raya gorda. El filo va opaco o no
 * recorta.
 */
private const val MEZCLA_DEL_BRILLO = 0.62
private const val ALFA_DEL_BRILLO = 0xE8
private const val MEZCLA_DE_LA_SOMBRA = 0.42
private const val ALFA_DE_LA_SOMBRA = 0xE8

/**
 * La luz: lo ancho que se le va el resplandor, lo que se ve, y lo estrechos que van el
 * anillo de color y el filamento.
 *
 * El resplandor a dos veces y media el ancho del trazo y a un cuarto de alfa —es lo que se
 * escapa al aire—, el anillo de color a siete décimas, y el filamento a poco más de un
 * cuarto. **Los dos de dentro, estrechos**: con el color a todo lo ancho lo único que
 * crecía era la mancha, y con el filamento gordo se pierde el color por el centro y queda
 * una raya blanca con borde. Estrechos, se ve lo que hay que ver: una línea blanca dentro
 * de un anillo de color dentro de un resplandor.
 */
private const val RESPLANDOR = 2.6f
private const val ALFA_DEL_RESPLANDOR = 0x44
private const val ANILLO_DE_COLOR = 0.70f
private const val FILAMENTO = 0.28f

/**
 * Lo que tapa el rodillo: **un tercio, y el mismo en todas partes**.
 *
 * Un tercio deja leer por debajo, que es lo que hace que sea un resaltador y no pintura. Y
 * uno solo, no dos: el grupo entero se vuelca de una vez, así que si cada pasada llevara su
 * propia carga según lo lejos que pase, dentro de la misma mancha se verían las pasadas.
 * Ver [pintarLosRodillos].
 */
/**
 * Lo que tapa un rodillo: **todo**.
 *
 * Nació como un resaltador —una tinta translúcida, la de subrayar un renglón— y con eso se
 * quedaba corto para lo que de verdad se le pide en un croquis del espacio: darle **una mano
 * de color** a una cara, a una pieza, a una zona. Un marcador tiñe lo que hay debajo; un
 * rodillo lo cubre, y esa es la diferencia entre resaltar algo y pintarlo.
 *
 * Lo que no cambia es lo que ya cumplía y ninguna otra tinta cumple: por muy cruzadas que
 * vayan las pasadas, **queda una sola mano**. Eso lo hace la capa aparte, no la
 * transparencia. Ver [pintarLosRodillos].
 *
 * Y quien lo quiera translúcido lo tiene en el mando de lo que tapa la tinta, que sigue
 * multiplicando a esto: se baja y se pinta un velo. La diferencia es de qué lado está lo de
 * fábrica. Ver [Trazo3D.opacidad].
 */
private const val ALFA_DEL_RESALTADOR = 0xFF

/**
 * **El relleno de una lámina**: sus cuadriláteros, todos en el mismo sentido de giro.
 *
 * El contorno viene de [Lamina3D.contorno] —un lado de ida y el otro de vuelta—, así que el
 * cuadro `i` son los puntos `i` e `i+1` de la ida y sus dos parejas de la vuelta. Emitidos
 * todos con el mismo giro, una hoja que se dobla sobre sí misma en la pantalla se rellena
 * entera en vez de descontarse justo donde se cruza.
 */
private fun mancharLaHoja(
    contorno: List<Pt3>,
    camara: Camara3D,
    ancho: Double,
    alto: Double
): Path {
    val camino = Path()
    val cuantos = contorno.size
    val xs = FloatArray(cuantos)
    val ys = FloatArray(cuantos)
    for (i in 0 until cuantos) {
        val v = camara.aPantalla(contorno[i], ancho, alto)
        xs[i] = v.x.toFloat(); ys[i] = v.y.toFloat()
    }
    val m = cuantos / 2
    if (m < 2) return camino
    for (i in 0 until m - 1) {
        val a = i
        val b = i + 1
        val c = cuantos - 2 - i
        val d = cuantos - 1 - i
        val area = (xs[a] * ys[b] - xs[b] * ys[a]) + (xs[b] * ys[c] - xs[c] * ys[b]) +
            (xs[c] * ys[d] - xs[d] * ys[c]) + (xs[d] * ys[a] - xs[a] * ys[d])
        camino.moveTo(xs[a], ys[a])
        if (area >= 0f) {
            camino.lineTo(xs[b], ys[b]); camino.lineTo(xs[c], ys[c]); camino.lineTo(xs[d], ys[d])
        } else {
            camino.lineTo(xs[d], ys[d]); camino.lineTo(xs[c], ys[c]); camino.lineTo(xs[b], ys[b])
        }
        camino.close()
    }
    return camino
}

private fun DrawScope.pintarLamina(
    lamina: Lamina3D,
    camara: Camara3D,
    ancho: Double,
    alto: Double,
    /** De qué color van sus cantos y los meridianos de una bola: lo contrario del fondo. */
    contraste: Color = Color.Gray
) {
    // **La hoja que no se acaba a lo hondo se pinta recortada a lo que se ve.**
    //
    // Su barrido llega a treinta pantallas de aquí, así que pintarla entera sería pintar un
    // contorno que nadie va a ver y una retícula de doce líneas repartidas por todo eso —o
    // sea, dos rayas sueltas cruzando la pantalla—. Se pinta **la franja que cae delante**,
    // centrada en donde uno está mirando, y así la hoja acompaña a la vista: es lo que
    // significa que no se acabe. Ver [Lamina3D.infinita] y [laFranjaQueSeVe].
    val laHoja = if (lamina.infinita) laFranjaQueSeVe(lamina, camara, ancho, alto) else lamina

    // **La bola se pinta aparte**: su contorno depende de desde dónde se mire, así que no lo
    // puede llevar guardado. Lo que se ve de una esfera es siempre lo mismo —un disco— y
    // encima sus meridianos y paralelos, que son los que dicen que aquello es una bola y
    // hacia dónde está girada. Sin ellos, un disco translúcido es un agujero.
    laHoja.esfera?.let { bola ->
        // **Sus meridianos van del contrario del espacio, no del contrario de su color.**
        //
        // Iban del contrario de su color **a plena opacidad**, y una hoja no se ve nunca a
        // plena opacidad: lleva un velo del quince por ciento largo. Una bola blanca sobre un
        // espacio negro **se ve casi negra**, y encima se le pintaban los meridianos en
        // negro: rayas negras sobre fondo negro, que es no pintar nada. Lo que hay que
        // leer es la bola contra el espacio, y para eso ya está el contraste del espacio.
        pintarLaBola(bola, camara, ancho, alto, contraste, laHoja.opacidad)
        return
    }

    val contorno = laHoja.contorno()
    if (contorno.size < 3) return
    // **La mancha, cuadro a cuadro y todos en el mismo sentido.**
    //
    // Iba como un solo polígono —el contorno entero— y eso vale mientras la hoja se vea
    // desplegada. Una hoja **curva mirada de lado** se proyecta encima de sí misma: el
    // contorno se cruza, y la regla de relleno de siempre descuenta el trozo que va al
    // revés en vez de rellenarlo. Lo que se veía es que la hoja curva desaparecía por
    // trozos al mirarla de canto y parecía plana (lo reportó el usuario el 6-sep-2026).
    // Partida en sus cuadros y todos girando igual, donde se solapa consigo misma las
    // vueltas se suman. Ver [mancharLaHoja].
    val camino = mancharLaHoja(contorno, camara, ancho, alto)

    run {
        // **Translúcida, y bastante.** Una lámina es un apoyo para dibujar encima, no un
        // muro: opaca taparía lo que hay detrás, que es justo lo que uno está mirando
        // cuando gira. Y el tono depende de cuánto la ve de canto, que es lo que hace que
        // dos láminas cruzadas se distingan sin dibujarles el borde más grueso.
        val decanto = abs(escalar(normalDe(laHoja), camara.adelante))
        // Lo que se ve la hoja lo deciden **cuánto se la mira de canto y su propio mando**:
        // de canto casi no se ve —es una raya—, de frente se nota, y encima de eso lo que se
        // le haya subido o bajado con el dedo. Ver [Lamina3D.opacidad].
        //
        // **Menos tapa y más color.** Estaba tan cargada que la mesa competía con lo
        // dibujado: puesta de frente y con el mando alto se acercaba a tres cuartos de tapar,
        // y entonces lo que hay detrás —que es justo lo que uno mira mientras gira— se perdía
        // detrás de un cristal ahumado. Lo que hace que una hoja se lea no es cuánto tapa,
        // es **que su color se distinga del espacio**, y para eso el color tiene que llegar
        // vivo en vez de lavado contra el fondo. Así que la mancha baja y quien dice dónde
        // está la hoja pasan a ser sus cantos, que van en contraste con el espacio.
        val alfa = ((VELO_DE_LA_HOJA + (VELO_DE_FRENTE * decanto)) * laHoja.opacidad)
            .toInt().coerceIn(0, TOPE_DEL_VELO)
        // **Y la mancha va del mismo lado que sus cantos.**
        //
        // Iba del color de la hoja, y la hoja nace blanca: sobre el papel claro del tema eso
        // es blanco sobre blanco. La superficie no estaba —lo único que quedaba eran los dos
        // cantos, que sí van en contraste— y el mando de la hoja no cambiaba nada, porque lo
        // que subía y bajaba era cuánto blanco había puesto encima del blanco. Ver
        // [laManchaDeLaHoja].
        if (alfa > 2) drawPath(camino, laManchaDeLaHoja(laHoja.color, contraste, alfa))

        // **Y los recuadros, que son lo que enseña cómo se dobla.**
        //
        // Rellena de color liso, una superficie curva se ve **exactamente igual que una
        // plana**: no hay nada en una mancha uniforme que delate un pliegue. Se notaba en que
        // se trazaba un plano curvo, salía con pinta de plano recto, y sin embargo lo que se
        // dibujaba encima sí caía en la curva — o sea, la geometría estaba bien y lo que
        // mentía era el pintado.
        //
        // Van **recortados a la hoja** (`clipPath`) para que una hoja sin final no reparta
        // rayas por toda la pantalla, y **salteados** para que un perfil de trescientos
        // puntos no cueste trescientas líneas por fotograma: lo que dice que algo se dobla
        // son unos cuantos recuadros, no todos. Ver [MAXIMOS_DEL_PERFIL].
        if (alfa > 2 && laHoja.perfil.size >= 2) {
            val pauta = contraste.copy(
                alpha = (PAUTA_DE_LA_HOJA * laHoja.opacidad).toFloat().coerceIn(0f, 1f)
            )
            val paso = kotlin.math.max(1, laHoja.perfil.size / MAXIMOS_DEL_PERFIL)
            clipPath(camino) {
                // Las costillas: una por punto del perfil, cruzando de un riel al otro. Son
                // las que dibujan el pliegue, porque siguen la curva que se trazó.
                var i = 0
                while (i < laHoja.perfil.size) {
                    val a = camara.aPantalla(laHoja.unLado(i), ancho, alto)
                    val b = camara.aPantalla(laHoja.otroLado(i), ancho, alto)
                    drawLine(
                        pauta,
                        Offset(a.x.toFloat(), a.y.toFloat()),
                        Offset(b.x.toFloat(), b.y.toFloat()),
                        strokeWidth = GRUESO_DE_LA_PAUTA
                    )
                    i += paso
                }
                // Y las del barrido, que cierran los recuadros: el perfil repetido a varias
                // alturas entre un riel y el otro.
                for (t in ALTURAS_DE_LA_PAUTA) {
                    val raya = Path()
                    var k = 0
                    var primero = true
                    while (k < laHoja.perfil.size) {
                        val a = laHoja.unLado(k)
                        val b = laHoja.otroLado(k)
                        val p = camara.aPantalla(
                            Pt3(
                                a.x + (b.x - a.x) * t,
                                a.y + (b.y - a.y) * t,
                                a.z + (b.z - a.z) * t
                            ),
                            ancho, alto
                        )
                        if (primero) {
                            raya.moveTo(p.x.toFloat(), p.y.toFloat()); primero = false
                        } else {
                            raya.lineTo(p.x.toFloat(), p.y.toFloat())
                        }
                        k += paso
                    }
                    drawPath(raya, pauta, style = Stroke(width = GRUESO_DE_LA_PAUTA))
                }
            }
        }
    }
    // **La hoja vuelve a llevar recuadros, y el porqué de que se quitaran sigue en pie.**
    //
    // Se retiraron con un argumento bueno —la hoja es la mesa, y una mesa con trama compite
    // con lo dibujado— pero se llevaron por delante lo único que enseñaba la curvatura. Con
    // el velo de ahora, que es tres veces más flojo que el de entonces, la trama ya no
    // compite: se lee el pliegue y se sigue viendo lo de encima. Lo que sigue valiendo del
    // argumento viejo:
    //
    // La llevaba, con un argumento bueno: rellena de color liso, una superficie curva se ve
    // igual que una plana, y la cuadrícula enseña cómo se dobla. Pero en la práctica pesa más
    // la contra: la hoja es **la mesa**, y una mesa con la trama encima compite con lo único
    // que hay que mirar, que es lo dibujado. Con dos hojas cruzadas eran dos tramas
    // encimadas, y con la mesa translúcida sobre un espacio oscuro las rayas caían negras
    // sobre negro. La referencia de medida ya la da la malla del suelo, que es fija y no se
    // subdivide; la hoja dice dónde está con sus dos cantos y ya.
    // **El borde va fuerte.** Es el único plano que hay y todo lo que se dibuje va a él,
    // así que no compite con nada: lo que tiene que quedar claro es hasta dónde llega. En
    // `dp` por lo mismo que el grosor del trazo: en píxeles crudos era un pelo de menos de
    // un dp, y con los trazos ya con cuerpo la hoja se quedaba sin contorno visible.
    if (laHoja.infinita) {
        // **Solo los dos cantos de verdad.** Los otros dos lados del contorno son el corte
        // que se ha hecho para pintar, no un borde de la hoja: dibujarlos sería poner una
        // raya donde la hoja sigue, que es justo lo que uno necesita saber que no pasa.
        // Los cantos van del color contrario al fondo y no del de la hoja: una hoja blanca
        // sobre papel blanco no tiene cantos que valgan, y es la que nace de fábrica.
        // Y lo marcados que van **los dice el mando también**: son lo que de verdad se ve de
        // una hoja, así que sin esto bajar el mando a cero la dejaba quitada por la mancha y
        // puesta por los cantos. Ver [Lamina3D.opacidad].
        val tinta = contraste.copy(
            alpha = (ALFA_DEL_CANTO * laHoja.opacidad).toFloat().coerceIn(0f, 1f)
        )
        val gordo = BORDE_DE_LA_HOJA.dp.toPx()
        for (i in listOf(0, laHoja.perfil.size - 1)) {
            // De un borde al otro por sus dos rieles: la franja ya no es simétrica —viene
            // recortada contra el ojo— así que su medio barrido no dice dónde acaba.
            val a = camara.aPantalla(laHoja.unLado(i), ancho, alto)
            val b = camara.aPantalla(laHoja.otroLado(i), ancho, alto)
            drawLine(
                tinta,
                Offset(a.x.toFloat(), a.y.toFloat()),
                Offset(b.x.toFloat(), b.y.toFloat()),
                strokeWidth = gordo
            )
        }
    } else {
        drawPath(
            camino,
            // El mismo criterio que la franja: color propio salvo que no se distinga del
            // espacio, y obedeciendo al mando. Ver [laManchaDeLaHoja].
            laManchaDeLaHoja(
                laHoja.color, contraste,
                (0xFF * laHoja.opacidad).toInt().coerceIn(0, 0xFF)
            ),
            style = Stroke(width = BORDE_DE_LA_HOJA.dp.toPx())
        )
    }
}

/**
 * **La franja de la hoja que cae delante de quien mira, y solo delante.**
 *
 * Una hoja que no se acaba a lo hondo no se puede pintar entera: se coge el trozo que hay
 * alrededor de lo que se está mirando y se pinta ese, corrido a donde esté la cámara. Como se
 * recalcula en cada fotograma, la franja acompaña a la vista y la hoja parece —y es— que no
 * se acaba.
 *
 * ## Por qué se recorta contra el ojo
 *
 * La franja se estira hacia los dos lados de lo que se mira, así que **la mitad de atrás
 * queda a la espalda de quien mira**. Sin lente eso da igual: la cámara ortográfica no tiene
 * ojo, tiene una dirección, y lo de atrás se proyecta como lo de delante. Con lente sí lo
 * tiene, y lo que le queda detrás **no se proyecta: se envuelve** —sale disparado por los
 * bordes, que es lo que hace un ojo de pez—, así que el contorno de la hoja se retuerce sobre
 * sí mismo y lo que se ve es media hoja, o una hoja partida.
 *
 * Y empeora al acercarse, que es justo como aparecía: **el ojo se mete en la escena** al
 * hacer zoom —está a la focal partida por el aumento— así que llega un punto en que atraviesa
 * la hoja. Es literalmente la cámara chocando con el plano.
 *
 * Se recorta punto a punto contra un pelo por delante del ojo, que es lo que hace cualquier
 * motor con su plano cercano. Como el recorte da un trozo distinto en cada punto del perfil,
 * la franja deja de ser simétrica y se devuelve con **sus dos bordes explícitos**
 * ([Lamina3D.otroPerfil]), que es la forma que ya tenía la hoja para eso.
 *
 * ## Y llega hasta donde llega la pantalla
 *
 * El alcance sale de la diagonal de la vista y no de un número fijo: con la lente abierta, el
 * mismo trozo de mundo ocupa mucha menos pantalla, y una franja medida en píxeles
 * ortográficos se quedaba corta justo cuando más ancho se ve.
 */
private fun laFranjaQueSeVe(
    lamina: Lamina3D,
    camara: Camara3D,
    ancho: Double,
    alto: Double
): Lamina3D {
    val direccion = normalizado(lamina.direccion)
    if (largo(direccion) < 0.5 || lamina.perfil.isEmpty()) return lamina
    val alcance = (DIAGONALES_DE_LA_FRANJA * kotlin.math.hypot(ancho, alto) / camara.zoom)
        .coerceAtMost(lamina.fondo)
    val corrimiento = escalar(menos(camara.centro, lamina.perfil.first()), direccion)
    val ojo = camara.ojo(alto)
    val adelante = camara.adelante
    // Cuánto avanza la franja hacia donde se mira por cada paso: si es cero, la franja entera
    // está a la misma hondura y no hay nada que recortar contra el ojo.
    val avanza = if (ojo == null) 0.0 else escalar(direccion, adelante)
    val cerca = if (ojo == null) 0.0 else CERCA_DEL_OJO / camara.zoom

    val unos = ArrayList<Pt3>(lamina.perfil.size)
    val otros = ArrayList<Pt3>(lamina.perfil.size)
    for (p in lamina.perfil) {
        val base = mas(p, por(direccion, corrimiento))
        var desde = -alcance
        var hasta = alcance
        if (ojo != null) {
            val hondo = escalar(menos(base, ojo), adelante)
            when {
                avanza > 1e-9 -> desde = maxOf(desde, (cerca - hondo) / avanza)
                avanza < -1e-9 -> hasta = minOf(hasta, (cerca - hondo) / avanza)
                // De canto al ojo: o está toda delante o está toda detrás.
                hondo < cerca -> { desde = 0.0; hasta = 0.0 }
            }
            if (desde > hasta) { desde = hasta }
        }
        unos += mas(base, por(direccion, desde))
        otros += mas(base, por(direccion, hasta))
    }
    return lamina.copy(
        perfil = unos,
        otroPerfil = otros,
        fondo = 0.0,
        // La visual de cada punto era para que naciera de canto; recortada y corrida ya no
        // significa nada, y los dos bordes ya vienen puestos.
        direcciones = null
    )
}

/**
 * Cuántas diagonales de pantalla se pintan de una hoja sin fin hacia cada lado.
 *
 * Dos: una llega justo, y justo no basta en cuanto la hoja se ve escorzada —entonces su
 * franja se acorta en pantalla— ni con la lente abierta, que enseña mucho más mundo del que
 * cabe en la misma cuenta.
 */
private const val DIAGONALES_DE_LA_FRANJA = 2.0

/** Lo más cerca del ojo que se deja llegar a una hoja, en píxeles de pantalla. */
private const val CERCA_DEL_OJO = 4.0

/**
 * **La bola, de frente**: un disco translúcido y su malla encima.
 *
 * El disco lo da la geometría —una esfera se ve redonda desde donde se la mire, y su radio
 * en pantalla es el suyo por el aumento—, y la malla es lo que le da el volumen: los
 * meridianos y los paralelos se aprietan hacia los bordes solos, que es exactamente lo que
 * hace que un círculo plano se lea como una esfera. Se pintan **enteros, delante y detrás**:
 * la hoja es una superficie para dibujar encima, no un cuerpo opaco, y viendo solo la mitad
 * de delante no se sabe por dónde va lo que uno dibujó al otro lado.
 */
private fun DrawScope.pintarLaBola(
    bola: Esfera3D,
    camara: Camara3D,
    ancho: Double,
    alto: Double,
    contraste: Color,
    opacidad: Double
) {
    val medio = camara.aPantalla(bola.centro, ancho, alto)
    // El tamaño se mide proyectando, y por su eje más largo: con lente puesta no vale
    // multiplicar por el aumento, y con la bola estirada su radio a secas no dice cuánto ocupa.
    val masLargo = maxOf(bola.porUno, bola.porOtro, bola.porTercero)
    val orilla = camara.aPantalla(mas(bola.centro, por(camara.derecha, masLargo)), ancho, alto)
    if (kotlin.math.hypot(orilla.x - medio.x, orilla.y - medio.y) < 1.0) return

    // **La silueta de la bola es la que le toque, y estirada ya no es un círculo.**
    //
    // Se pintaba con un círculo del radio de la bola, sin mirar lo estirada que estuviera.
    // Los meridianos sí se estiraban, así que en cuanto uno hacía un huevo con el mando lo
    // que quedaba era el huevo **dentro de un círculo**: un aro y una mancha redondos donde
    // ya no había nada redondo. Ver [siluetaDeLaBola].
    val alfa = (0x22 * opacidad).toInt().coerceIn(0, 0xC0)
    // **El disco es de la bola.** Un cilindro, un cono o un anillo no tienen una silueta
    // que se sepa de antemano: la suya la dan sus caras, y para eso están las tiras de la
    // malla, que se pintan como velo cara a cara.
    if (bola.forma == FormaDeBola.BOLA) {
        val contorno = siluetaDeLaBola(bola, camara)
        val camino = Path()
        contorno.forEachIndexed { i, p ->
            val v = camara.aPantalla(p, ancho, alto)
            if (i == 0) camino.moveTo(v.x.toFloat(), v.y.toFloat())
            else camino.lineTo(v.x.toFloat(), v.y.toFloat())
        }
        camino.close()
        drawPath(camino, contraste.copy(alpha = alfa / 255f))
        drawPath(
            camino, contraste.copy(alpha = (alfa * 2.5f / 255f).coerceAtMost(0.5f)),
            style = Stroke(width = 1.5f)
        )
    } else {
        val velo = Path()
        for (tira in bola.malla()) {
            tira.forEachIndexed { i, p ->
                val v = camara.aPantalla(p, ancho, alto)
                if (i == 0) velo.moveTo(v.x.toFloat(), v.y.toFloat())
                else velo.lineTo(v.x.toFloat(), v.y.toFloat())
            }
            velo.close()
        }
        // Un solo velo con todas las caras: donde se cruzan las de delante y las de
        // atrás no se oscurece el doble, que es lo que hace que se lea como cuerpo hueco.
        drawPath(velo, contraste.copy(alpha = alfa / 255f))
    }

    val tinta = contraste.copy(
        alpha = (ALFA_DE_LA_REJILLA / 255f * opacidad.toFloat()).coerceIn(0f, 0.6f)
    )
    // Los doce aros, en un camino: doce llamadas de pintado para doce curvas del mismo color
    // es doce veces el precio de mandar algo a pintar. Ver [pintarSuelo].
    val malla = Path()
    fun aro(puntos: List<Pt3>) {
        puntos.forEachIndexed { i, p ->
            val v = camara.aPantalla(p, ancho, alto)
            if (i == 0) malla.moveTo(v.x.toFloat(), v.y.toFloat())
            else malla.lineTo(v.x.toFloat(), v.y.toFloat())
        }
    }

    val pasos = Esfera3D.MERIDIANOS
    if (bola.forma == FormaDeBola.BOLA) {
        for (m in 0 until MERIDIANOS_QUE_SE_PINTAN) {
            val vuelta = Math.PI * m / MERIDIANOS_QUE_SE_PINTAN
            aro((0..pasos).map { bola.punto(vuelta, -Math.PI / 2 + Math.PI * it / pasos) } +
                (0..pasos).map { bola.punto(vuelta + Math.PI, Math.PI / 2 - Math.PI * it / pasos) })
        }
        for (q in 1 until PARALELOS_QUE_SE_PINTAN) {
            val subida = -Math.PI / 2 + Math.PI * q / PARALELOS_QUE_SE_PINTAN
            aro((0..pasos).map { bola.punto(2 * Math.PI * it / pasos, subida) })
        }
    } else {
        // Las generatrices, una por gajo, y los aros de arriba abajo (con los dos cantos).
        for (m in 0 until 2 * MERIDIANOS_QUE_SE_PINTAN) {
            val vuelta = Math.PI * m / MERIDIANOS_QUE_SE_PINTAN
            aro((0..pasos).map { bola.punto(vuelta, -Math.PI / 2 + Math.PI * it / pasos) })
        }
        for (q in 0..PARALELOS_QUE_SE_PINTAN) {
            val subida = -Math.PI / 2 + Math.PI * q / PARALELOS_QUE_SE_PINTAN
            aro((0..pasos).map { bola.punto(2 * Math.PI * it / pasos, subida) })
        }
    }
    drawPath(malla, tinta, style = Stroke(width = GRUESO_DE_LA_REJILLA))
}

/**
 * **El borde que se le ve a la bola desde aquí**, estirada como esté.
 *
 * Una esfera se ve siempre igual —un disco— y por eso valía un círculo. Una bola estirada no:
 * es un elipsoide, y lo que se le ve es una elipse que depende de por dónde se la mire. Con
 * el círculo puesto, hacer un huevo dejaba el huevo dentro de un aro redondo que no era el
 * borde de nada.
 *
 * La cuenta no tiene misterio. Un elipsoide es la bola de siempre estirada por sus tres ejes
 * (`D`), así que sus puntos son `centro + D·u` con `u` de la bola unidad. Para cada dirección
 * de la pantalla, el punto del borde es el que más lejos llega en ella, y ese es el de
 * `u = D·w / |D·w|`, con `w` esa dirección puesta en el mundo. Barriendo `w` alrededor sale
 * el borde entero.
 *
 * Se devuelven **puntos del mundo** y no de la pantalla: así los proyecta quien los pinta, y
 * con la lente abierta el borde sale escorzado como todo lo demás en vez de quedarse en una
 * elipse dibujada sobre el cristal.
 */
private fun siluetaDeLaBola(bola: Esfera3D, camara: Camara3D): List<Pt3> {
    val a = bola.porUno
    val b = bola.porOtro
    val c = bola.porTercero
    val u = bola.unos
    val v = bola.otros
    val w = bola.terceros
    val derecha = camara.derecha
    val arriba = camara.arriba
    return (0 until PUNTOS_DE_LA_SILUETA).map { k ->
        val t = 2 * Math.PI * k / PUNTOS_DE_LA_SILUETA
        val cos = kotlin.math.cos(t)
        val sen = kotlin.math.sin(t)
        // Esa dirección de la pantalla, puesta en el mundo.
        val hacia = Pt3(
            derecha.x * cos + arriba.x * sen,
            derecha.y * cos + arriba.y * sen,
            derecha.z * cos + arriba.z * sen
        )
        // Y medida contra los tres ejes de la bola, estirada cada uno por lo suyo: el punto
        // que más lejos llega en esa dirección es el que toca el borde.
        val ka = a * escalar(u, hacia)
        val kb = b * escalar(v, hacia)
        val kc = c * escalar(w, hacia)
        val cuanto = kotlin.math.sqrt(ka * ka + kb * kb + kc * kc)
        if (cuanto < 1e-12) return@map bola.centro
        val pa = a * ka / cuanto
        val pb = b * kb / cuanto
        val pc = c * kc / cuanto
        Pt3(
            bola.centro.x + u.x * pa + v.x * pb + w.x * pc,
            bola.centro.y + u.y * pa + v.y * pb + w.y * pc,
            bola.centro.z + u.z * pa + v.z * pb + w.z * pc
        )
    }
}

/** Con cuántos puntos se traza ese borde. Treinta y dos ya no tiene esquinas a la vista. */
private const val PUNTOS_DE_LA_SILUETA = 32

/** Cuántos meridianos y paralelos se le pintan a la bola. Los justos para leerla redonda. */
private const val MERIDIANOS_QUE_SE_PINTAN = 6
private const val PARALELOS_QUE_SE_PINTAN = 6

/**
 * El eje del espejo: **una raya larga, a rayas y de su color**.
 *
 * Larga a propósito —se sale de la pantalla por los dos lados—: un eje de simetría no
 * empieza ni acaba en ningún sitio, y pintándolo del largo que se trazó parecería un trazo
 * más del dibujo. A rayas por lo mismo que la auxiliar del plano: no es tinta, es una marca
 * sobre la mesa.
 */
private fun DrawScope.pintarElEspejo(
    espejo: Espejo3D,
    camara: Camara3D,
    ancho: Double,
    alto: Double
) {
    val medio = LARGO_DEL_ESPEJO / camara.zoom
    val tinta = Color(parseColor(COLOR_DEL_ESPEJO, 0xFF))
    val deUnLado = menos(espejo.punto, por(espejo.direccion, medio))
    val deOtro = mas(espejo.punto, por(espejo.direccion, medio))

    // **Y el plano, no solo su eje.**
    //
    // Un espejo no es una raya: es **una superficie**, y de qué lado de ella está cada cosa es
    // justo lo que hay que ver antes de dibujar. Con solo el eje pintado, uno sabe por dónde
    // pasa el espejo mirándolo de frente y no tiene ni idea en cuanto gira la vista: la raya
    // sigue ahí, igual, sin decir hacia dónde se abre el plano. Pintándolo como lo que es
    // —una lámina translúcida que sale del eje por su perpendicular— se ve de canto cuando
    // está de canto y de frente cuando está de frente, que es lo que dice dónde está.
    //
    // Muy flojo: es una guía, no una pieza. Lo que tiene que verse es el dibujo.
    val haciaArriba = normalizado(producto(espejo.normal, espejo.direccion))
    if (largo(haciaArriba) > 0.5) {
        val hasta = por(haciaArriba, medio * ALTO_DEL_ESPEJO)
        val camino = Path()
        for ((k, p) in listOf(
            menos(deUnLado, hasta), menos(deOtro, hasta),
            mas(deOtro, hasta), mas(deUnLado, hasta)
        ).withIndex()) {
            val v = camara.aPantalla(p, ancho, alto)
            if (k == 0) camino.moveTo(v.x.toFloat(), v.y.toFloat())
            else camino.lineTo(v.x.toFloat(), v.y.toFloat())
        }
        camino.close()
        drawPath(camino, tinta.copy(alpha = ALFA_DEL_ESPEJO))
    }

    pintarARayas(listOf(deUnLado, deOtro), tinta, camara, ancho, alto)
}

/** Cuánto se levanta el plano del espejo a cada lado de su eje, en veces lo largo que es. */
private const val ALTO_DEL_ESPEJO = 0.5

/** Y lo poco que se ve: es una guía, no una pieza. */
private const val ALFA_DEL_ESPEJO = 0.10f

/** Lo que se sale el eje del espejo por cada lado, en píxeles de pantalla. */
private const val LARGO_DEL_ESPEJO = 2400.0

/** De qué color va el eje del espejo: el violeta de la aplicación, como lo elegido. */
private const val COLOR_DEL_ESPEJO = "#6965DB"

/**
 * La línea auxiliar: **el perfil del plano mientras se traza**.
 *
 * A rayas y del color de trabajo, sin cuerpo, sin brillo y sin halo. No es tinta, es una
 * marca sobre la mesa: en cuanto se levanta el dedo desaparece y en su sitio queda la
 * lámina. Pintándola con el pincel puesto no se distinguía de un trazo del lápiz.
 */
private fun DrawScope.pintarARayas(
    puntos: List<Pt3>,
    color: Color,
    camara: Camara3D,
    ancho: Double,
    alto: Double
) {
    if (puntos.size < 2) return
    val camino = Path()
    puntos.forEachIndexed { i, p ->
        val v = camara.aPantalla(p, ancho, alto)
        if (i == 0) camino.moveTo(v.x.toFloat(), v.y.toFloat())
        else camino.lineTo(v.x.toFloat(), v.y.toFloat())
    }
    drawPath(
        camino,
        color,
        style = Stroke(
            width = LA_AUXILIAR.dp.toPx(),
            // **Las rayas se cortan a escuadra.** Con la tapa redonda, cada rayita sale
            // con un casquete en cada punta: a este grosor eso es una rayita con dos
            // bolitas, y una fila de bolitas no se lee como una línea auxiliar sino como
            // un fallo de pintado. A escuadra, la auxiliar es lo que es en cualquier
            // plano dibujado a mano.
            cap = StrokeCap.Butt,
            join = StrokeJoin.Round,
            pathEffect = rayas()
        )
    )
}

/**
 * El patrón de rayas, en `dp` para que se vea igual de picada en cualquier pantalla.
 *
 * Raya y hueco parejos y anchos: apretadas se leían como una línea gris sucia en vez de
 * como una auxiliar, y una auxiliar tiene que verse **auxiliar** de un vistazo.
 */
private fun DrawScope.rayas(): PathEffect = PathEffect.dashPathEffect(
    floatArrayOf(RAYA.dp.toPx(), HUECO.dp.toPx())
)

/**
 * Lo que mide cada rayita y **lo que se deja entre una y la siguiente**.
 *
 * El hueco, medio más largo que la raya: parejos se leían como una línea gris sucia con la
 * trama muy apretada, y una auxiliar tiene que verse auxiliar de un vistazo, no de cerca.
 */
private const val RAYA = 8.0
private const val HUECO = 12.0

/** Por encima de esta luz, un fondo es claro y lo auxiliar va negro. Por debajo, blanco. */
private const val MITAD_DE_LA_LUZ = 0.45f

/**
 * Lo que puede durar un toque, y lo que puede pasar entre los dos de un doble toque.
 *
 * Los mismos números que usa Android para lo mismo desde siempre. Un toque que dura más de
 * un tercio de segundo ya no es un toque, es un dedo apoyado, y dos toques separados por
 * más de otro tercio son dos toques y no uno doble.
 */
private const val LO_QUE_DURA_UN_TOQUE = 300L
private const val ENTRE_LOS_DOS = 300L

/** Lo gordas que son las dos puntas que marcan una recta enderezada sola. */
private const val PUNTA_DE_LA_RECTA = 4.0

/**
 * Lo gorda que va la línea auxiliar del plano, en dp.
 *
 * Casi el doble que el borde de la hoja ya puesta: mientras se traza es lo único que hay en
 * pantalla de lo que se está haciendo, y a un pelo de grosor, sobre una retícula y con el
 * dedo encima, no se veía.
 */
private const val LA_AUXILIAR = 2.6

/** Lo gordo que va el contorno de la hoja, en dp. */
private const val BORDE_DE_LA_HOJA = 1.4

/** Lo que se marcan los recuadros de la hoja con el mando a uno. Flojos: son la mesa. */
private const val PAUTA_DE_LA_HOJA = 0.16

/** Lo fina que va su raya. */
private const val GRUESO_DE_LA_PAUTA = 1f

/** A qué alturas entre un riel y el otro se cierran los recuadros. */
private val ALTURAS_DE_LA_PAUTA = floatArrayOf(0.25f, 0.5f, 0.75f)

/**
 * Lo marcados que van los cantos de una hoja con el mando como nace.
 *
 * Era un 0,75 fijo. Multiplicado por [Lamina3D.opacidad] sale ese mismo 0,75 de fábrica, así
 * que nada cambia sin tocar el mando — y ahora el mando sí llega a lo que de verdad se ve.
 */
private const val ALFA_DEL_CANTO = 0.75

/** Cuántas rayas del perfil se reparten a lo largo del barrido, y cuántas del barrido. */
private const val PASOS_DEL_BARRIDO = 4
private const val MAXIMAS_DEL_BARRIDO = 12

/**
 * En cuántos tramos como mucho se pinta una raya de guía que sigue el perfil de una hoja.
 *
 * El perfil lo traza un dedo, así que puede traer doscientos puntos; una raya de guía con
 * doscientos tramos se ve exactamente igual que con cuarenta y ocho y cuesta cuatro veces
 * más — en cada fotograma de cada giro, y por cada una de las rayas de la retícula.
 */
private const val MAXIMOS_DEL_PERFIL = 48
private const val ALFA_DE_LA_REJILLA = 0x33

/** La normal de una lámina, sacada de su primera tira. */
fun normalDe(lamina: Lamina3D): Pt3 {
    // De sus dos rieles y sin armar sus tiras: hacía falta la primera y se fabricaban todas.
    if (lamina.perfil.size < 2) return Pt3(0.0, 0.0, 1.0)
    val a = lamina.unLado(0)
    return normalizado(
        producto(menos(lamina.unLado(1), a), menos(lamina.otroLado(0), a))
    )
}

/**
 * La malla del suelo.
 *
 * Se dibuja **por líneas del mundo y no por celdas de la pantalla**: así se ve en
 * perspectiva isométrica igual que la vería uno de pie sobre ella, y girar la vista la
 * gira con el dibujo. Es la única referencia fija que hay aquí, y fija quiere decir las dos
 * cosas: no se mueve al desplazarse —las rayas están en los múltiplos del cuadro contados
 * desde el origen— y **sus cuadros miden siempre lo mismo**. Ver [pisosDeLaMalla].
 */
private fun DrawScope.pintarSuelo(
    camara: Camara3D,
    ancho: Double,
    alto: Double,
    /** De qué color va, para que se lea sobre el espacio que haya. Ver [loContrarioDe]. */
    contraste: Color,
    /** Si se mira desde dentro del sitio. Ver abajo: cambia dónde se planta la retícula. */
    puestoEnElSitio: Boolean = false,
    /**
     * Si se pinta la malla, o solo los dos ejes.
     *
     * Los ejes se quedan siempre: son el cero del mundo, y sin ellos no hay forma de saber
     * dónde está uno. Lo que se apaga es la retícula, que es lo que estorba enseñando un
     * croquis acabado. Ver [Croquis.pautaDeLasHojas].
     */
    conMalla: Boolean = true
) {
    // **Puesto en el sitio, la retícula se planta bajo los pies y no bajo la mirada.**
    //
    // Va centrada en lo que se mira, y eso está bien con la cámara de órbita: se orbita algo
    // y el suelo lo enmarca. Mirando el croquis puesto en el sitio está mal, y es **el fallo
    // que hace que no parezca puesto en ningún sitio**: mirar alrededor mueve el punto
    // mirado, así que la retícula se iba con la mirada y se veía exactamente igual mirase
    // uno a donde mirase. Girándose, lo único que cambiaba era la imagen de la cámara — o
    // sea, parecía un fondo cambiado y no un croquis plantado en la habitación.
    //
    // Con el suelo clavado bajo los pies, girarse lo barre: las rayas pasan por delante, la
    // perspectiva del suelo gira con uno, y **eso** es lo que dice que hay un sitio.
    //
    // Ojo: esto dice **por dónde se corta** la malla, no dónde caen sus rayas. Las rayas
    // están donde están en el mundo. Ver [pisosDeLaMalla].
    val donde = (if (puestoEnElSitio) camara.ojo(alto) else null) ?: camara.centro

    // Cuánto mundo hay que cubrir a cada lado: la media diagonal de la vista, en unidades,
    // con un margen porque el suelo se escorza y su franja se acorta en pantalla.
    if (camara.zoom <= 0.0) return
    val alcance = kotlin.math.hypot(ancho, alto) / 2.0 / camara.zoom * MARGEN_DEL_SUELO
    val pisos = if (conMalla) pisosDeLaMalla(camara.zoom, alcance) else emptyList()

    // **Las rayas van en un camino por piso, no en una llamada por raya.**
    //
    // Son todas del mismo color y del mismo grueso dentro de su piso, así que mandarlas una
    // a una es pagar cien veces el precio de mandar algo a pintar para dibujar lo mismo. Es
    // de lo más barato que se puede hacer por la fluidez y se nota en cada fotograma, porque
    // el suelo está siempre.
    fun raya(a: Pt3, b: Pt3) {
        val pa = camara.aPantalla(a, ancho, alto)
        val pb = camara.aPantalla(b, ancho, alto)
        ElMontonDeRayas.raya(
            pa.x.toFloat(), pa.y.toFloat(), pb.x.toFloat(), pb.y.toFloat()
        )
    }

    for ((n, piso) in pisos.withIndex()) {
        val lado = piso.lado
        // El piso de encima —el de los cuadros diez veces más grandes— pinta sus mismas
        // rayas más marcadas, así que aquí se saltan: pintarlas debajo no las mejora y una
        // raya pintada dos veces con dos alfas no queda del alfa de ninguna de las dos.
        val hayGordo = n + 1 < pisos.size
        fun rayasEn(desde: Double, hasta: Double, cruzando: Double, enX: Boolean) {
            for (i in rayasDelPiso(lado, desde, hasta)) {
                // El cero es un eje y se pinta aparte, y los múltiplos de diez son del piso
                // de encima.
                if (i == 0) continue
                if (hayGordo && i % PISOS_POR_DECENA == 0) continue
                val d = i * lado
                if (enX) {
                    raya(Pt3(d, cruzando - alcance, 0.0), Pt3(d, cruzando + alcance, 0.0))
                } else {
                    raya(Pt3(cruzando - alcance, d, 0.0), Pt3(cruzando + alcance, d, 0.0))
                }
            }
        }
        rayasEn(donde.x - alcance, donde.x + alcance, donde.y, enX = true)
        rayasEn(donde.y - alcance, donde.y + alcance, donde.x, enX = false)
        // **El suelo se pinta del contrario del espacio, no de un morado fijo.**
        //
        // Iba de un morado clavado en el código, y por eso «no se ve»: es un color concreto,
        // así que sobre un espacio claro se pierde, sobre uno oscuro se pierde, y sobre uno
        // morado no está. El suelo no es una decoración con su color: es la referencia de por
        // dónde se está mirando, y una referencia tiene que leerse **sobre cualquier fondo**.
        // Ver [loContrarioDe].
        //
        // Y más gruesas: a un pelo de píxel, el antialias se come media línea y lo que queda
        // es una trama gris que no se lee como una retícula.
        volcarLasRayas(
            contraste.copy(alpha = alfaDelPiso(piso.decena) * piso.cuanto),
            GRUESO_DEL_SUELO
        )
    }

    // Y los dos ejes del mundo, los más marcados: son el cero, y con la malla clavada en el
    // mundo el cero está donde está y no debajo de la mirada. Ver [pisosDeLaMalla].
    raya(Pt3(0.0, donde.y - alcance, 0.0), Pt3(0.0, donde.y + alcance, 0.0))
    raya(Pt3(donde.x - alcance, 0.0, 0.0), Pt3(donde.x + alcance, 0.0, 0.0))
    volcarLasRayas(contraste.copy(alpha = ALFA_DE_LOS_EJES), GRUESO_DE_LOS_EJES)
}

/**
 * Un piso de la malla del suelo: cuánto mide su cuadro y cuánto se ve.
 *
 * Ver [pisosDeLaMalla], que es donde está el porqué.
 */
internal data class PisoDeLaMalla(
    /** Cuántas decenas por encima del cuadro base: -1 es la décima, 0 el cuadro, 1 la decena. */
    val decena: Int,
    /** El lado de su cuadro, en unidades del mundo. */
    val lado: Double,
    /** De cero a uno: cuánto de su marcado le toca a este aumento, para que no aparezca de golpe. */
    val cuanto: Float
)

/**
 * **Los pisos de la malla del suelo a este aumento: cuadros de tamaño fijo.**
 *
 * ## El fallo que arregla
 *
 * La malla iba con un paso que se doblaba y se partía según el aumento —cuarenta unidades,
 * ochenta, ciento sesenta— para no cerrarse en una trama gris al alejarse ni quedarse en dos
 * rayas al acercarse. Se veía siempre bien y **no medía nada**: el mismo cuadro valía
 * cuarenta unidades en un momento y mil doscientas ochenta en otro, así que contar cuadros
 * no decía cuánto mide algo, ni si esto es el doble de aquello, ni si el croquis de hoy está
 * a la escala del de ayer. Una referencia que cambia de tamaño sin avisar no es una
 * referencia: es una textura de fondo.
 *
 * ## Lo que hace ahora
 *
 * El cuadro **es siempre el mismo**, [LADO_DEL_CUADRO] unidades del mundo, y las rayas caen
 * en los múltiplos de ese lado **contados desde el origen** — no desde donde se esté mirando.
 * Así la malla está clavada en el mundo: al desplazarse pasa por debajo en vez de venirse
 * con uno, y dos croquis distintos traen cuadros que valen lo mismo.
 *
 * ## Y por qué hay más de un piso, si el cuadro es fijo
 *
 * Porque el aumento va de un cuarentavo a cuarenta veces, y un cuadro fijo no sobrevive a
 * eso: en un extremo son dos píxeles —trama gris— y en el otro es más ancho que la pantalla
 * —ninguna raya—. Así que se pintan a la vez el cuadro y sus vecinos **de diez en diez**: la
 * décima parte, el cuadro, la decena, la centena. Se ve el que se lee y se desvanece el que
 * no, y eso sigue midiendo, porque todos los cuadros que se llegan a ver son el mismo cuadro
 * o una potencia de diez de él. De diez en diez y no de dos en dos a propósito: diez se
 * cuenta de un vistazo —cada raya gorda son diez finas— y el papel milimetrado lleva un
 * siglo enseñando que así se lee.
 *
 * Además se desvanecen en vez de aparecer de golpe, para que un pellizco no haga parpadear
 * el suelo entero.
 *
 * @param alcance cuánto mundo hay que cubrir a cada lado, en unidades. De él sale el tope de
 *   rayas: un piso que pidiera más de [RAYAS_DE_UN_LADO] a cada lado no se pinta, y por eso
 *   en una pantalla grande el piso fino entra un poco más tarde.
 */
internal fun pisosDeLaMalla(zoom: Double, alcance: Double): List<PisoDeLaMalla> {
    if (zoom <= 0.0 || alcance <= 0.0 || !zoom.isFinite() || !alcance.isFinite()) return emptyList()
    // Lo más apretado que se deja ver un piso: lo que se lee en la pantalla, y nunca más
    // rayas de las que se pueden pintar por fotograma.
    // Y en movimiento, más suelto: el piso más fino es el que más rayas trae de todos —diez
    // veces las del siguiente— y es el primero que se pierde girando. Ver [LaCalidad].
    val apretado = kotlin.math.max(MINIMO_EN_PANTALLA, alcance * zoom / RAYAS_DE_UN_LADO) *
        LaCalidad.grano
    val pisos = ArrayList<PisoDeLaMalla>(3)
    for (decena in DECENA_MAS_FINA..DECENA_MAS_GORDA) {
        val lado = LADO_DEL_CUADRO * Math.pow(PISOS_POR_DECENA.toDouble(), decena.toDouble())
        // De cero a uno entre lo apretado y un poco más: es el desvanecido.
        val cuanto = ((lado * zoom / apretado - 1.0) / (DESVANECIDO - 1.0))
            .toFloat().coerceIn(0f, 1f)
        if (cuanto <= 0f) continue
        pisos += PisoDeLaMalla(decena, lado, cuanto)
    }
    return pisos
}

/**
 * Lo marcado que va un piso: el cuadro base es la referencia y cada decena por encima pesa
 * un poco más, como las rayas gordas del papel milimetrado. Nunca más que los ejes, que son
 * los únicos que tienen que ganar siempre.
 */
/**
 * **Qué rayas de un piso hacen falta para cubrir de [desde] a [hasta].**
 *
 * Son múltiplos del lado **contados desde el origen del mundo**, y ahí está lo que importa:
 * la malla no se planta debajo de la mirada, está donde está. Desplazarse la pasa por debajo
 * en vez de llevársela, que es lo que hace que se note que uno se mueve, y dos vistas del
 * mismo croquis desde sitios distintos traen las rayas en los mismos sitios del mundo.
 *
 * Se redondea hacia fuera por los dos lados: con la raya justa se vería el borde de la malla
 * asomando por una esquina.
 */
internal fun rayasDelPiso(lado: Double, desde: Double, hasta: Double): IntRange =
    kotlin.math.floor(desde / lado).toInt()..kotlin.math.ceil(hasta / lado).toInt()

internal fun alfaDelPiso(decena: Int): Float = when {
    decena < 0 -> ALFA_DEL_SUELO * ALFA_DE_LO_FINO
    decena == 0 -> ALFA_DEL_SUELO
    else -> kotlin.math.min(ALFA_DE_LOS_EJES, ALFA_DEL_SUELO * (1f + ALFA_POR_DECENA * decena))
}

/**
 * **De qué color se pinta lo que tiene que leerse encima de [fondo]: blanco o negro.**
 *
 * No es un umbral a la mitad, y esa es la parte que importa. Con la mitad, cualquier color
 * vivo de brillo medio —un naranja, un rojo, un verde hierba— cae del lado oscuro por poco y
 * se le pinta encima una retícula negra que no se distingue: sobre un naranja, una raya
 * negra fina es una raya que no está. Lo que se lee bien sobre un color saturado es el
 * blanco, casi siempre, porque el blanco se separa de **todos** los tonos y el negro solo de
 * los claros.
 *
 * Así que el corte va alto: se pinta blanco salvo que el fondo sea de verdad claro. Sobre
 * papel blanco o gris claro, negro; sobre cualquier color con cuerpo, blanco.
 */
private fun loContrarioDe(fondo: Color): Color =
    if (fondo.luminance() > LO_QUE_YA_ES_CLARO) Color.Black else Color.White

/**
 * De qué color va la mancha de una hoja: **la suya, salvo que no se distinga del espacio**.
 *
 * Una hoja de color se lee por su color, que es lo que se quiso al ponérselo. Pero la que nace
 * de fábrica es blanca, y sobre el papel claro del tema una mancha blanca al catorce por
 * doscientos cincuenta y cinco no es una mancha: **no está**. Eso era el plano invisible —solo
 * se veían sus dos cantos, que ya iban en contraste— y de paso el mando de la hoja parecía
 * roto, porque lo que graduaba era cuánto blanco había encima del blanco.
 *
 * El contraste ya dice de qué lado cae el fondo. Si la hoja cae del mismo lado, su color no
 * llega y manda el contraste — el mismo criterio que ya seguían sus cantos y el relleno de la
 * bola. Ver [loContrarioDe].
 */
private fun laManchaDeLaHoja(color: String, contraste: Color, alfa: Int): Color {
    val suyo = Color(parseColor(color, 0xFF))
    val delMismoLado = (suyo.luminance() > LO_QUE_YA_ES_CLARO) == (contraste == Color.Black)
    return (if (delMismoLado) contraste else suyo).copy(alpha = alfa / 255f)
}

/**
 * A partir de qué claridad un fondo cuenta como claro.
 *
 * Alto a propósito: ver [loContrarioDe]. Un naranja anda por el 0,48 y tiene que llevar
 * retícula blanca; un gris de papel anda por el 0,8 y tiene que llevarla negra.
 */
private const val LO_QUE_YA_ES_CLARO = 0.62f

/**
 * Lo gruesos que van los meridianos y paralelos de una bola.
 *
 * Por encima del pelo de un píxel: con los trazos ya con cuerpo, una raya de un píxel se
 * queda por debajo de todo lo demás y la bola deja de leerse como una bola.
 */
private const val GRUESO_DE_LA_REJILLA = 1.3f

/** Lo gruesas que van las rayas del suelo y sus dos ejes, y cuánto se ven. */
private const val GRUESO_DEL_SUELO = 1.4f
private const val GRUESO_DE_LOS_EJES = 2.2f
internal const val ALFA_DEL_SUELO = 0.16f
internal const val ALFA_DE_LOS_EJES = 0.42f

/** Lo que se aparta del cuadro base cada piso: el fino menos, y cada decena un poco más. */
private const val ALFA_DE_LO_FINO = 0.6f
private const val ALFA_POR_DECENA = 0.6f

/**
 * De cuánto en cuánto va un piso de la malla al siguiente.
 *
 * Diez y no dos: cada raya gorda son diez finas, que se cuentan de un vistazo. Ver
 * [pisosDeLaMalla].
 */
internal const val PISOS_POR_DECENA = 10

/**
 * Hasta dónde llega la malla hacia lo fino y hacia lo gordo, en decenas del cuadro base.
 *
 * Cubren el aumento entero: con el cuadro en cuarenta unidades, la centésima parte se lee al
 * máximo aumento y la centena al mínimo. Fuera de ahí no hay nada que pintar.
 */
/**
 * **Y no hay pisos más finos que el cuadro.**
 *
 * Los había —la décima y la centésima— y aparecían al acercarse: la malla se iba llenando de
 * rayas nuevas según uno se metía en una zona. Eso rompe lo único para lo que sirve una
 * malla, que es **ser la misma referencia siempre**: si acercarse la subdivide, contar
 * cuadros no dice nada, porque los cuadros no son los mismos que hace un segundo.
 *
 * Los pisos más gordos sí se quedan, y no es lo mismo: aparecen **alejándose**, cuando el
 * cuadro base ya no se lee, y traen *menos* rayas y no más. Acercarse no añade nunca ninguna.
 */
private const val DECENA_MAS_FINA = 0
private const val DECENA_MAS_GORDA = 2

/** Lo más apretada que se deja ver una malla en la pantalla, en píxeles. */
private const val MINIMO_EN_PANTALLA = 22.0

/**
 * Cuántas veces lo apretado tarda un piso en verse del todo.
 *
 * Corto a propósito: el desvanecido está para que un pellizco no haga parpadear el suelo
 * entero, no para que la malla ande medio puesta la mitad del rato. En cuanto el cuadro se
 * lee cómodo, se ve entero.
 */
private const val DESVANECIDO = 1.4

/**
 * Cuántas rayas de un piso se dejan pintar a cada lado de la mirada.
 *
 * Es el tope de gasto por fotograma, y el suelo se pinta en todos. Un piso que pidiera más
 * no se pinta: es siempre el más fino, o sea el que menos se ve. Ver [pisosDeLaMalla].
 */
internal const val RAYAS_DE_UN_LADO = 64

/**
 * Cuánto más allá de la media diagonal de la vista se pinta la malla.
 *
 * Más de uno porque el suelo se ve escorzado: su franja se acorta en pantalla y con el
 * alcance justo se le vería el borde por arriba.
 */
internal const val MARGEN_DEL_SUELO = 1.25


/** Lo marcado que va el filo por donde se trazó, en `dp`. */
private const val FILO_DE_LA_CUCHILLA = 1.6f

/**
 * Cada cuántos `dp` de anchura se añade un peldaño al degradado de la luz.
 *
 * Dos: por debajo de eso dos peldaños seguidos caen en el mismo píxel y uno de los dos es
 * trabajo tirado. Ver el pintado de [Pincel.LUZ].
 */
private const val DP_POR_PELDANO = 7f

/**
 * Y entre cuántos y cuántos peldaños se queda, pase lo que pase.
 *
 * **Pocos, y por eso el degradado no se ve a saltos.** Estuvo en dieciséis y era caro de
 * verdad: cada peldaño arma el contorno entero del trazo —tres listas y un camino por
 * vuelta—, así que una luz costaba dieciséis veces lo que cuesta una tinta corriente, y con
 * unas cuantas encendidas eso son las decenas de milisegundos por fotograma que se sienten
 * como que la aplicación se arrastra.
 *
 * Lo que quita las bandas no es el número de peldaños, es que **el salto entre dos sea más
 * fino de lo que el ojo distingue**, y eso depende de cuánto aporta cada uno, no de cuántos
 * hay. Con la caída al cuadrado y el alfa bajo, cinco peldaños ya no dejan canto: los de
 * fuera aportan tan poco que la diferencia entre dos vecinos se queda por debajo de un par
 * de niveles de gris. Ver el pintado de [Pincel.LUZ].
 */
private const val PELDANOS_MINIMOS = 3
private const val PELDANOS_MAXIMOS = 6

/**
 * Cuánto aporta cada peldaño del degradado, a lo sumo.
 *
 * Bajo a propósito: son muchos y **suman**, así que si cada uno aportara lo que aportaba una
 * de las tres capas de antes, el centro se iría a blanco mucho antes de llegar al filamento
 * y el degradado se aplanaría justo donde tiene que notarse.
 */
private const val ALFA_DEL_PELDANO = 118f

/**
 * Lo que tapa una hoja: de canto, de frente y como mucho.
 *
 * Números bajos a propósito. Una hoja es la mesa de dibujo y su trabajo es **estar sin
 * estorbar**: se nota que hay una superficie, se ve su color, y se sigue viendo lo que hay
 * detrás. Quien dice dónde acaba y cómo se dobla es su retícula, no la mancha. Ver
 * [pintarLamina].
 */
private const val VELO_DE_LA_HOJA = 0x0E
private const val VELO_DE_FRENTE = 0x1C
private const val TOPE_DEL_VELO = 0x5A

/**
 * **El cuadro: la unidad de medida de todo el croquis, en unidades del mundo.**
 *
 * De aquí salen los cuadros de la pauta de una hoja y los de la malla del suelo, y salen del
 * mismo sitio a propósito: **un cuadro es un cuadro**, esté en la mesa o esté en el suelo, y
 * así contar sobre una hoja y contar sobre el suelo dan el mismo número. Ver [pintarSuelo]
 * y [pisosDeLaMalla].
 */
internal const val LADO_DEL_CUADRO = 40.0

/**
 * Entre qué y qué se le deja verse a un cuadro en la pantalla.
 *
 * Exactamente diez veces uno de otro, y no es un número redondo por gusto: el cuadro solo
 * puede cambiar de decena en decena —o deja de medir—, así que si la ventana fuera más
 * estrecha que una decena habría aumentos sin ningún cuadro que quepa, y si fuera más ancha
 * los dos límites se pisarían. Ver [ladoDelCuadro].
 */
internal const val MINIMO_DEL_CUADRO = 26.0
internal const val MAXIMO_DEL_CUADRO = MINIMO_DEL_CUADRO * PISOS_POR_DECENA

/**
 * **Cuánto mide el cuadro de la pauta de una hoja a este aumento.**
 *
 * El fijo, [LADO_DEL_CUADRO], siempre que se lea en la pantalla; y si no se lee, el de diez
 * veces más o el de diez veces menos. **Nunca uno cualquiera**: un cuadro que se ajustara al
 * aumento se vería siempre igual de bien y no diría cuánto mide nada, que es justo para lo
 * que está. De diez en diez, uno cuenta lo que ve y sabe por cuánto multiplicar.
 *
 * Es la misma regla que la malla del suelo, y del mismo lado: ver [pisosDeLaMalla].
 */
internal fun ladoDelCuadro(zoom: Double): Double {
    if (zoom <= 0.0 || !zoom.isFinite()) return LADO_DEL_CUADRO
    var lado = LADO_DEL_CUADRO
    while (lado * zoom < MINIMO_DEL_CUADRO) lado *= PISOS_POR_DECENA
    while (lado * zoom > MAXIMO_DEL_CUADRO) lado /= PISOS_POR_DECENA
    return lado
}

/** Dos colores mezclados, canal a canal: cero es el primero y uno es el segundo. */
private fun mezclados(a: Int, b: Int, cuanto: Float): Int {
    val t = cuanto.coerceIn(0f, 1f)
    fun canal(d: Int): Int {
        val x = (a shr d) and 0xFF
        val y = (b shr d) and 0xFF
        return (x + (y - x) * t).toInt().coerceIn(0, 255)
    }
    return (canal(24) shl 24) or (canal(16) shl 16) or (canal(8) shl 8) or canal(0)
}
