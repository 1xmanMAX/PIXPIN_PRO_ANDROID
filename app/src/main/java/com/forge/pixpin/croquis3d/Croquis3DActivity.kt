package com.forge.pixpin.croquis3d

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.CropPortrait
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.CropLandscape
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LightbulbCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.forge.pixpin.R
import com.forge.pixpin.motor.CLARIDAD_MINIMA
import com.forge.pixpin.motor.ElChivato
import com.forge.pixpin.motor.Flechitas
import com.forge.pixpin.motor.LaRuedaDelColor
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.RADIO_DE_LA_RUEDA
import com.forge.pixpin.motor.RECORRIDO_DEL_MANDO
import com.forge.pixpin.motor.deHsv
import com.forge.pixpin.motor.elToqueDeCuatroDedos
import com.forge.pixpin.motor.enHsv
import com.forge.pixpin.motor.enLaRueda
import com.forge.pixpin.motor.enTexto
import com.forge.pixpin.motor.marcaImantada
import com.forge.pixpin.motor.parseColor
import com.forge.pixpin.pin.ImageStore
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **El croquis en el espacio**: la aplicación de dibujar en tres dimensiones.
 *
 * Separada del editor del lienzo a propósito, y no es una decisión de organización. El
 * lienzo es infinito pero **plano**: sus herramientas, sus imanes, su recorte y su
 * exportación dan por hecho que todo pasa en el papel. Meter aquí un lápiz que se sale
 * del papel habría obligado a que cada una de esas cosas preguntase antes «¿y esto en qué
 * plano está?». Son dos formas distintas de pensar el dibujo y merecen dos sitios.
 *
 * Lo que aquí se hace es **croquizar**: dos herramientas, giro libre y nada que
 * configurar. Ver [Croquis].
 */
class Croquis3DActivity : ComponentActivity() {

    private val controlador = Croquis3DControlador()

    /**
     * Qué croquis se está dibujando, y de qué proyecto es si es de alguno.
     *
     * **Un croquis por proyecto.** Un proyecto es una carpeta de lo que se va a entregar, y
     * el croquis en el espacio es una de las cosas que se entregan: si todos los proyectos
     * compartieran el mismo, abrir el de otra obra enseñaría el dibujo de esta.
     */
    private val elProyecto: String? by lazy { intent?.getStringExtra(EL_PROYECTO) }

    /**
     * **Cerrar devuelve a donde se vino.** Ver [com.forge.pixpin.EXTRA_DESDE_PROYECTO]: esta
     * actividad vive en su propia tarea, así que sin decirlo, atrás no es el proyecto que se
     * estaba mirando sino lo que hubiera debajo.
     */
    private fun cerrarYVolver() {
        val vuelta = intent?.getStringExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO)
        if (vuelta != null) com.forge.pixpin.volverALosProyectos(this, vuelta)
        finish()
    }
    /** Si está abierto el diálogo de la gráfica en el espacio. Ver [DialogoDeGrafica3D]. */
    private var grafica3dAbierta by mutableStateOf(false)

    private val elCroquis: String by lazy {
        intent?.getStringExtra(EL_CROQUIS) ?: Croquis3DAlmacen.EL_DE_SIEMPRE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aPantallaCompleta()
        aTodoColor()
        Croquis3DAlmacen.cargar(this, elCroquis)?.let { controlador.cargar(it) }
        // **Y si se ha venido desde una lámina, la vista de esa lámina.** Se pone entera y
        // sin viaje: no se está volviendo de un sitio, se está llegando.
        intent?.getStringExtra(LA_VISTA)?.let { controlador.ponerLaVista(it) }
        setContent { PixPinTheme { Pantalla() } }
    }

    /**
     * **Congela la vista, y si el croquis es de un proyecto, la deja de lámina.**
     *
     * Lo primero es lo de siempre; lo segundo es lo que hace que un croquis en el espacio
     * sea material entregable y no un cuaderno aparte: la vista se **proyecta a vectores** y
     * entra en el proyecto como una hoja más, en orden y exportable con las demás. Ver
     * [HojaDeLaVista].
     *
     * Fuera del hilo de la pantalla: son unos cuantos miles de puntos que proyectar y un
     * archivo que escribir, y ahí sí se vería el tirón.
     */
    private fun congelar(
        densidad: androidx.compose.ui.unit.Density,
        recorte: Recorte? = null
    ) {
        controlador.congelarLaVista(recorte)
        val proyecto = elProyecto ?: return
        val vista = controlador.croquis.vistas.lastOrNull() ?: return
        val croquis = controlador.croquis
        val ancho = controlador.anchoDeLaVista
        val alto = controlador.altoDeLaVista
        Thread {
            HojaDeLaVista.aniadir(
                this, (application as com.forge.pixpin.PixPinApp).proyectos, proyecto,
                elCroquis, croquis, vista, ancho, alto, densidad
            )
        }.start()
    }

    /**
     * **Sin barra de estado ni de navegación**, igual que el editor del lienzo.
     *
     * Aquí no es solo que la hora estorbe: la vista se gira arrastrando por cualquier
     * parte de la pantalla, y las dos franjas de las barras son justo los dos sitios por
     * los que uno empieza a arrastrar cuando quiere dar media vuelta al croquis. Con
     * ellas puestas, ese arrastre bajaba las notificaciones o se iba al lanzador en vez de
     * girar. Y el suelo y el cubo de las vistas piden todo el alto que haya: son las dos
     * referencias de por dónde se está mirando.
     *
     * Se ocultan pero no se bloquean —siguen saliendo si deslizas desde el borde—, y **se
     * vuelven a esconder al recuperar el foco**: cualquier cosa que se lo quite (mirar una
     * notificación, salir y volver) las devuelve, y escondiéndolas solo al arrancar ya no
     * se iban en el resto de la sesión.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) aPantallaCompleta()
    }

    /**
     * **Se le pide a la pantalla todo el color que sepa dar.**
     *
     * De fábrica, una ventana de Android pinta en el color de toda la vida y la pantalla se
     * queda con lo que sobra: en un panel de los de ahora eso es dejar sin usar la parte
     * más viva de lo que puede mostrar. Pidiendo la gama ancha, un rojo o un azul saturados
     * salen **de verdad** saturados, y la tinta de luz —que se apoya en eso, en píxeles
     * encendidos a tope— pasa de ser un color claro a leerse como algo que alumbra.
     *
     * Solo si la pantalla la tiene. Pidiéndosela a una que no, Android la ignora, pero se
     * pregunta antes por no dejar el sistema decidiendo algo que ya se sabe.
     */
    private fun aTodoColor() {
        // La gama ancha sí se pide al abrir: da colores más saturados **dentro del mismo
        // techo**, así que no apaga nada. El margen de brillo es otra cosa y va a demanda.
        if (resources.configuration.isScreenWideColorGamut) {
            runCatching {
                window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            }
        }
    }

    /**
     * **Y que la pantalla encienda por encima del blanco, si sabe.**
     *
     * La gama ancha es una cosa y el brillo de más es otra, y la tinta de luz necesita la
     * segunda. La gama ancha da colores **más saturados**; el techo sigue siendo el blanco
     * de la pantalla, así que una luz «a tope» es exactamente igual de brillante que el
     * papel que tiene al lado — y una luz que brilla lo mismo que el fondo no es una luz, es
     * una raya de color claro. Es la queja de fondo de esa tinta y no se arregla eligiendo
     * mejor el color: se arregla teniendo dónde subir.
     *
     * Los paneles de ahora sí tienen dónde: pueden pasarse del blanco de la interfaz un buen
     * trecho —es lo que hace que una foto en HDR deslumbre al lado del texto de al lado— y
     * desde Android 15 se puede **pedir ese margen para la ventana**. Con él, el filamento
     * de la luz se sale del blanco de verdad, que es lo que hace un tubo de neón mirado en
     * persona.
     *
     * ## Y se pide, no se da por hecho
     *
     * Se pide el margen entero y **manda el sistema**: en un panel que no lo tenga, o con el
     * brillo bajo, o con el ahorro de batería puesto, lo que se concede es menos o nada, y
     * entonces todo se ve exactamente como antes. Por eso no hay nada que cambiar en el
     * pintado: las tintas se siguen escribiendo igual, y el margen —si lo hay— se lo aplica
     * la pantalla al componer.
     */
    /**
     * **Le pide a la ventana el margen que haga falta ahora mismo, y ni un poco más.**
     *
     * Se llama cada vez que se mueve la llave de las luces, no al abrir: pedirlo apaga el
     * resto del croquis, así que se paga solo cuando hay algo que encender por encima del
     * blanco. Ver [ajustarElBrilloDeMas].
     */
    private fun ajustarElBrilloDeMas() {
        val luces = controlador.croquis.luces
        val sePasa = (luces.cuanto - 1.0).coerceAtLeast(0.0).toFloat()
        val hayLuz = sePasa > 0f && controlador.croquis.trazos.any {
            !it.oculto && (it.punta.alumbra || it.pincel.vigente == Pincel.LUZ)
        }
        com.forge.pixpin.motor.ajustarElBrilloDeMas(
            window,
            if (Build.VERSION.SDK_INT >= 30) display else null,
            if (hayLuz) sePasa else 0f
        )
    }

    private fun aPantallaCompleta() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val barras =
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        barras.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        barras.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * Se guarda al salir de la vista y no al dibujar.
     *
     * Escribir en cada trazo sería tocar disco sesenta veces por minuto con el dedo
     * encima; y aquí no hace falta la red de seguridad del editor de notas, porque un
     * croquis se pierde entero o no se pierde: no hay texto a medio escribir.
     */
    override fun onPause() {
        super.onPause()
        Croquis3DAlmacen.guardar(this, elCroquis, controlador.croquis)
    }

    @Composable
    private fun Pantalla() {
        // **Las imágenes, cargadas una vez y guardadas por su ruta.**
        //
        // El lienzo se repinta entero en cada fotograma mientras se gira, así que abrir el
        // archivo ahí dentro sería abrirlo sesenta veces por segundo. Se cargan fuera del
        // hilo de la pantalla —descodificar una foto son decenas de milisegundos, y ahí se
        // vería el tirón— y se quedan en memoria mientras la vista esté abierta.
        val imagenes = remember { mutableStateMapOf<String, android.graphics.Bitmap>() }
        CargarLasImagenes(imagenes)

        // Traer una imagen de la galería: se copia al almacén de la aplicación —lo de fuera
        // se mueve y se borra— y se pone en el espacio con la proporción que traiga.
        val elMarco = rememberCoroutineScope()
        val traerImagen = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            elMarco.launch {
                val puesta = withContext(Dispatchers.IO) {
                    val ruta = ImageStore.importFromUri(this@Croquis3DActivity, uri)
                    ruta?.let { r -> ImageStore.load(r)?.let { r to it } }
                }
                if (puesta == null) return@launch
                val (ruta, bmp) = puesta
                imagenes[ruta] = bmp
                controlador.ponerImagen(ruta, bmp.height.toDouble() / bmp.width.toDouble())
                controlador.herramienta = Herramienta3D.SELECCION
            }
        }

        // Qué panel hay abierto, si hay alguno. Vive en la pantalla y no en el controlador:
        // un panel abierto no es una decisión sobre el croquis. **Uno cada vez**: dos
        // paneles apilados sobre la barra se comen la mitad de la pantalla, que es justo lo
        // que uno está mirando mientras elige.
        var pestana by remember { mutableStateOf(PestanaDelCroquis.DIBUJO) }
        // **Los ajustes son una barra, no un panel más.**
        //
        // De ella salen los paneles, y **se queda debajo mientras uno está abierto**: se
        // abre la punta, se toca la tinta y se pasa de una a otra sin volver a buscar el
        // botón de ajustes. Cerrándose al abrir un panel, cada cambio de idea costaba dos
        // toques y un vistazo para encontrar otra vez por dónde se entraba.
        var ajustes by remember { mutableStateOf(false) }
        // El taller del color, que se abre solo: ver [ElColorQueHay].
        var elColor by remember { mutableStateOf(false) }
        /**
         * **Solo el croquis**: fuera todos los mandos, con la herramienta que hubiera puesta.
         *
         * Se entra y se sale con el mismo toque de cuatro dedos. Ver [elToqueDeCuatroDedos].
         */
        var soloElDibujo by remember { mutableStateOf(false) }

        // **La vista viaja hasta la postura que se le pide, no salta a ella.**
        //
        // Un cuarto de segundo largo, saliendo despacio y entrando despacio: es lo que
        // tarda el ojo en seguir un giro entero sin perder de vista lo que estaba mirando.
        // El viaje lo empieza el doble toque o una cara del cubo, y lo corta cualquier
        // gesto de la mano. Ver [Croquis3DControlador.viaje].
        val viaje = controlador.viaje
        LaunchedEffect(viaje) {
            if (viaje == null) return@LaunchedEffect
            animate(0f, 1f, animationSpec = tween(LO_QUE_TARDA_EN_ENCAJAR, easing = FastOutSlowInEasing)) { v, _ ->
                controlador.andarElViaje(v)
            }
        }

        // **Y al dedo apoyado se le lleva la hora.**
        //
        // Enderezar por pararse se medía dentro del movimiento del dedo, y un dedo quieto
        // de verdad no manda movimientos: el gesto no salía justo cuando se hacía bien.
        // Aquí late un fotograma sí y otro también mientras hay algo en la mano, y ni un
        // fotograma cuando no lo hay. Ver [Croquis3DControlador.latido].
        //
        // **Y con el reloj de los eventos, no con el de la pared.** Los puntos del trazo
        // vienen con la hora que les puso el sistema al recogerlos —la de arranque, la que
        // trae cualquier evento de Android— y el latido tiene que contar en la misma. Con el
        // reloj de la pared, la resta entre las dos son las horas que lleva encendido el
        // aparato: el enderezado se cumplía en el primer latido y **cualquier raya salía
        // recta nada más empezarla**.
        ElLatidoDelTrazo()

        // **El fondo del espacio, si el croquis trae uno.** Sin él, el del tema, que se
        // pone claro u oscuro con el del sistema. Ver [Croquis.colorDelFondo].
        // **De noche se dibuja con tinta clara.** Una tinta casi negra sobre un fondo
        // oscuro no es una tinta: es un trazo que no está, y la aplicación parece no
        // responder. Solo al abrir y solo si nadie ha elegido color todavía.
        // **El margen de brillo se reajusta con la llave de las luces**, también al abrir un
        // croquis que ya venía con ellas subidas. Ver [ajustarElBrilloDeMas].
        LaunchedEffect(controlador.croquis.luces) { ajustarElBrilloDeMas() }

        val deNoche = com.forge.pixpin.ui.theme.deNoche()
        LaunchedEffect(deNoche) {
            val deFabrica = Croquis3DControlador.tintaDeFabrica(!deNoche)
            if (controlador.color == deFabrica) {
                controlador.color = Croquis3DControlador.tintaDeFabrica(deNoche)
            }
        }

        // El color del tema, cogido aquí porque un tema solo se sabe componiendo. Cuál de
        // los dos manda —este o el que traiga el croquis— se decide ya pintando, dentro de
        // [Croquis3DLienzo]: así el fondo del espacio no ata la pantalla al croquis.
        val superficie = MaterialTheme.colorScheme.surface
        // La densidad hace falta para pasar la vista a lámina: aquí dentro se sabe y en un
        // hilo de disco no.
        val densidad = LocalDensity.current

        // **Asomarse al mundo**: la cámara de atrás al fondo y el croquis pintado encima.
        //
        // El permiso se pide al encender el modo y no al abrir la pantalla: quien nunca lo
        // use no tiene por qué contestar por una cámara que no va a encenderse. Denegado, el
        // modo entra igual, con el espacio del croquis de fondo: mirar alrededor girando el
        // teléfono no necesita la cámara, solo el sensor de postura.
        var puedeLaCamara by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    this@Croquis3DActivity, android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            )
        }
        val pedirLaCamara = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { concedido ->
            puedeLaCamara = concedido
            // **Sin cámara también se entra.** Denegada, antes el modo se apagaba: sin
            // imagen detrás parecía que no había nada que enseñar. Pero lo que hace el
            // modo —mirar alrededor girando el teléfono, con el croquis clavado en el
            // sitio— no necesita la cámara para nada: es la misma realidad virtual de
            // unas gafas, con el espacio del croquis de fondo en vez de la habitación.
            controlador.verEnLaRealidad(true)
            if (!concedido) Toast.makeText(
                this@Croquis3DActivity, R.string.croquis_sin_camara, Toast.LENGTH_SHORT
            ).show()
        }

        if (grafica3dAbierta) {
            DialogoDeGrafica3D(
                onCerrar = { grafica3dAbierta = false },
                onAceptar = { trazos ->
                    grafica3dAbierta = false
                    controlador.insertarTrazos(trazos)
                },
                color = controlador.color,
                grosor = controlador.grosor
            )
        }

        ElSensorDeLaPostura(
            controlador.realidad,
            alMoverse = { controlador.mirarComoElTelefono(it) },
            alDesplazarse = { controlador.desplazarseComoElTelefono(it) },
            alDarUnPaso = { controlador.darUnPaso() }
        )

        // **Lo que mide la barra de abajo, medido y no supuesto.**
        //
        // El mando de lo elegido vive en la esquina de abajo a la derecha, y se levantaba un
        // número escrito a mano: en cuanto la barra crecía —otra fila de herramientas, el
        // mando de la hoja— el mando se le quedaba debajo. Midiéndola no hay número que
        // mantener al día y no hay forma de que se solapen.
        var altoDeLaBarra by remember { mutableIntStateOf(0) }
        Box(Modifier.fillMaxSize()) {
            if (controlador.realidad && puedeLaCamara) {
                VistaDeLaCamara(Modifier.fillMaxSize()) { controlador.lenteDelAparato(it) }
            }
            Croquis3DLienzo(
                recortando = controlador.recortando,
                imagenes = imagenes,
                // **Lo que se pinta va en dos montones, y no es manía de ordenar.**
                //
                // Cada uno lo lee la capa que lo necesita, y por eso trazar una raya no
                // obliga a volver a montar el croquis entero: la escena solo se entera de lo
                // suyo. Ver [LoQueSePinta] y [LoQueVaEnLaMano].
                laEscena = {
                    LoQueSePinta(
                        croquis = controlador.croquis,
                        camara = controlador.camara,
                        seleccion = controlador.seleccion,
                        espejoPuesto = controlador.espejoPuesto,
                        // Asomado al mundo, el espacio **no tiene color**: lo que hay
                        // detrás es la habitación de verdad, y pintarle un fondo encima
                        // sería taparla. Ver [Croquis3DControlador.verEnLaRealidad].
                        fondo = elFondoDelEspacio(superficie, puedeLaCamara),
                        puestoEnElSitio = controlador.realidad,
                        // Lo único de la mano que va con la escena, y solo cuando hay un
                        // rodillo puesto. Ver [Croquis3DControlador.elRodilloEnLaMano].
                        subrayandoAhora = controlador.elRodilloEnLaMano()
                    )
                },
                laMano = {
                    LoQueVaEnLaMano(
                        camara = controlador.camara,
                        croquis = controlador.croquis,
                        trazoEnCurso = controlador.trazoEnCurso,
                        presionEnCurso = controlador.presionEnCurso,
                        tiemposEnCurso = controlador.tiemposEnCurso,
                        normalEnCurso = controlador.normalEnCurso,
                        normalesEnCurso = controlador.normalesEnCurso,
                        opacidad = controlador.opacidad,
                        luz = controlador.luz,
                        punta = controlador.punta,
                        herramienta = controlador.herramienta,
                        color = controlador.color,
                        grosor = controlador.grosorDeVerdad,
                        pincel = controlador.pincel,
                        apoyo = controlador.apoyo,
                        bolita = controlador.bolita,
                        enderezandoSolo = controlador.enderezandoSolo,
                        espejoPuesto = controlador.espejoPuesto,
                        fondo = elFondoDelEspacio(superficie, puedeLaCamara),
                        colorApagada = controlador.colorApagada,
                        elRecorte = controlador.elRecorte
                    )
                },
                laCamara = { controlador.camara },
                // El gesto va **encima del lienzo y en la pasada inicial**, sin consumir
                // mientras no haya cuatro dedos: así trazar y girar la vista siguen igual.
                modifier = Modifier
                    .fillMaxSize()
                    // Dónde cae el lienzo en la ventana: es lo que se fotografía como portada
                    // de la página exportada. Ver [portadaDelLienzo].
                    .onGloballyPositioned { rectDelLienzo = it.boundsInWindow() }
                    .elToqueDeCuatroDedos(
                        alJuntarse = {
                            // El primer dedo llega unas milésimas antes que los otros tres,
                            // así que a estas alturas ya hay algo empezado: sin esto,
                            // esconder los mandos dejaba una raya suelta en el croquis.
                            controlador.tocar(Pt(0.0, 0.0), Fase.CANCELA)
                        },
                        alTocar = {
                            soloElDibujo = !soloElDibujo
                            if (soloElDibujo) {
                                // Lo que hubiera abierto se cierra: un panel flotando sobre
                                // una pantalla sin mandos no se puede ni cerrar.
                                ajustes = false
                                elColor = false
                                Toast.makeText(
                                    this@Croquis3DActivity,
                                    getString(R.string.editor_solo_el_dibujo),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ),
                alTocar = { p, fase, presion, cuando ->
                    // **Y lo primero, quitar de en medio el taller del color.**
                    //
                    // Se abre desde la barra y se queda abierto; en cuanto la mano vuelve al
                    // croquis, estorba. Cerrarlo al posar el lápiz es lo que uno espera sin
                    // tener que aprenderlo: se abre para elegir y se va al seguir dibujando.
                    if (fase == Fase.BAJA) elColor = false
                    controlador.tocar(p, fase, cuando, presion)
                },
                alNavegar = { pan, zoom, centro, gesto ->
                    controlador.navegar(
                        pan.x.toDouble(), pan.y.toDouble(), zoom.toDouble(),
                        centro.x.toDouble(), centro.y.toDouble(),
                        gesto
                    )
                },
                alDobleToque = { controlador.encajarEnLaVistaMasCercana() },
                // Marcada la zona y levantado el dedo, se congela con ella: el gesto ya ha
                // dicho todo lo que hacía falta y pedir además un botón de «ahora sí» sería
                // preguntar por preguntar.
                alRecortar = { recorte, terminado ->
                    controlador.marcandoLaZona(recorte)
                    if (terminado) {
                        val zona = recorte?.takeIf { it.sirve }
                        controlador.dejarDeRecortar()
                        if (zona != null) congelar(densidad, zona)
                    }
                },
                alMedirLaVista = { w, h -> controlador.medida(w, h) }
            )

            // **Los mandos van dentro de lo que se ve seguro; el lienzo, no.**
            //
            // A pantalla completa la muesca de la cámara y la barrita de gestos se comen
            // las esquinas, y ahí es donde están la flecha de salir y el cubo. El lienzo
            // sí ocupa hasta el canto —un croquis quiere todo el cristal— y la ✕ de la
            // hoja también, porque su sitio lo dicen las coordenadas del lienzo y
            // apartándola se despegaría de la esquina que señala.
            // **Y todos se van con un toque de cuatro dedos.**
            //
            // Aquí dentro está el cromo entero —la barra, las herramientas, el mando, el
            // cubo, los paneles— y el lienzo se queda fuera, que es justo el reparto que
            // hace falta: escondiéndolo todo queda el croquis y la herramienta que hubiera
            // puesta, y el mismo gesto lo devuelve. Ver [elToqueDeCuatroDedos].
            if (!soloElDibujo) Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                // **Lo de dibujar, a un lado; lo demás, detrás de un botón.**
                //
                // La barra de abajo lo tenía todo en fila —tintas, herramientas,
                // interruptores, paneles, deshacer— y encontrar algo pedía leerla entera con
                // el croquis medio tapado. Ahora en el lateral está **solo lo que se toca
                // dibujando**: con qué tinta, de qué grueso, de qué color y el borrador. El
                // resto vive en ajustes, que se abre cuando toca y se cierra.
                //
                // Al lado y no abajo porque el lateral es el canto que la mano no cruza para
                // llegar: dibujando en el centro de la pantalla, el pulgar cae ahí sin
                // taparle nada al dibujo.
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        // **Centrada en el hueco que queda por encima de deshacer.**
                        //
                        // Con dos cajas el lateral es alto, y centrado en la pantalla entera
                        // la de abajo se le montaba a la de deshacer en un móvil corto.
                        // Reservándole su hueco, las tres caben siempre y en el mismo orden.
                        .padding(bottom = HUECO_DEL_DESHACER),
                    horizontalAlignment = Alignment.Start
                ) {
                    LaBarraDeDibujar {
                        // **El color abre el color, y no la configuración entera.**
                        //
                        // Abría los ajustes en la pestaña de la punta, y desde ahí todavía había
                        // que abrir la rueda: cuatro toques para cambiar de color, que es lo que
                        // más se cambia de todo mientras se dibuja. Ahora sale la rueda, sus
                        // guardados y el material, y nada más. Ver [Croquis3DColor].
                        elColor = !elColor
                        if (elColor) ajustes = false
                    }
                    LasHerramientas(Modifier.padding(top = 8.dp))
                }


                // Deshacer y rehacer, **en su propia barra**: no son con qué se dibuja, son
                // qué hacer con lo dibujado, y a media pantalla estorbarían justo donde cae
                // la mano al trazar.
                Surface(
                    Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 4.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    tonalElevation = 3.dp
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { controlador.deshacer() },
                            enabled = controlador.puedeDeshacer
                        ) { Icon(Icons.Filled.Undo, getString(R.string.cd_undo)) }
                        IconButton(
                            onClick = { controlador.rehacer() },
                            enabled = controlador.puedeRehacer
                        ) { Icon(Icons.Filled.Redo, getString(R.string.cd_redo)) }
                    }
                }

                Row(Modifier.align(Alignment.TopStart)) {
                    IconButton(onClick = { cerrarYVolver() }, modifier = Modifier.padding(8.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = getString(R.string.cd_close)
                        )
                    }
                    IconButton(
                        onClick = {
                            ajustes = !ajustes
                            // Un panel cada vez: dos abiertos a la vez tapan el croquis
                            // entero, que es de lo que se salió cuando se partieron.
                            if (ajustes) elColor = false
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = getString(R.string.croquis_ajustes),
                            tint = if (ajustes) {
                                MaterialTheme.colorScheme.primary
                            } else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                        // El cubo de las vistas, arriba a la derecha, que es donde lo pone todo el
                // mundo. Se gira con la cámara y sus caras llevan a las vistas técnicas.
                Croquis3DCubo(
                    controlador,
                    Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
                )

                // **El aumento y sus dos candados.**
                //
                // El número dice a qué escala se está trabajando, que es lo que uno necesita
                // saber para calcar o para medir. Y a cada lado, lo que se puede clavar: el
                // plano —que si no se cambia con un despiste— y el propio aumento —que si no
                // se va solo, porque nadie arrastra dos dedos perfectamente paralelos—.
                //
                // Arriba y en el medio: se mira de vez en cuando y se toca poco, así que no
                // merece sitio abajo, donde está lo que se usa a todas horas.
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // **El número, aparte del resto de la barra.** Es lo único de aquí
                        // que cambia al navegar, y cambia en cada fotograma del gesto:
                        // leyendo la cámara en la pantalla, un pellizco rehacía la pantalla
                        // entera para mover dos cifras. Leída aquí dentro, lo que se rehace
                        // es este texto.
                        ElAumento()
                        Candado(
                            "⤢",
                            controlador.zoomBloqueado,
                            getString(R.string.croquis_clavar_zoom)
                        ) { controlador.zoomBloqueado = !controlador.zoomBloqueado }
                    }
                }

                // **Los ajustes, arriba y estrechos.**
                //
                // Abajo y a todo lo ancho tapaban media pantalla justo donde se dibuja, y a
                // lo ancho de una tableta las pestañas quedaban a un palmo del contenido.
                // Arriba, en una columna del ancho de una mano, se lee de un vistazo y deja
                // el dibujo a la vista mientras se toquetea.
                // **El taller del color, pegado a la barra y a la altura del botón.**
                //
                // Donde estaba el dedo, no en lo alto de la pantalla: se abre desde la
                // pastilla del color y tiene que salir de ella, o hay que ir a buscarlo.
                if (elColor) {
                    Croquis3DColor(
                        controlador,
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 56.dp, end = 8.dp)
                            .widthIn(max = ANCHO_DEL_COLOR.dp)
                    )
                }

                if (ajustes) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 44.dp)
                            .widthIn(max = ANCHO_DE_LOS_AJUSTES.dp)
                    ) {
                        LosAjustes(
                            pestana,
                            { pestana = it },
                            imagenes,
                            densidad
                        ) { traerImagen.launch("image/*") }
                    }
                }

                // La lente que hay puesta, y solo cuando no es la de siempre. Tocándola se
                // vuelve a la ortográfica: abrirla de un manotazo con tres dedos es fácil, y
                // sin una salida clara uno se queda mirando un croquis abombado sin saber qué
                // ha tocado.
                LaLente()

                // **Asomarse al mundo**, debajo de la lente y encima del mando: es un modo
                // que se enciende y se apaga a menudo —se mira cómo queda, se corrige, se
                // vuelve a mirar—, así que vive a la vista y no dentro de un panel.
                Surface(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 122.dp, end = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (controlador.realidad) controlador.verEnLaRealidad(false)
                            else if (puedeLaCamara) controlador.verEnLaRealidad(true)
                            else pedirLaCamara.launch(android.Manifest.permission.CAMERA)
                        },
                    shape = RoundedCornerShape(12.dp),
                    color =
                        if (controlador.realidad) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                ) {
                    Text(
                        getString(
                            if (controlador.realidad) R.string.croquis_salir_de_la_realidad
                            else R.string.croquis_ver_en_la_realidad
                        ),
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (controlador.realidad) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // **Clavarlo aquí**, y solo asomado al mundo.
                //
                // El croquis se coloca solo al encender el modo, pero el sitio donde uno
                // enciende no suele ser el sitio donde lo quiere. Se apunta a donde va y se
                // toca: se planta ahí y se queda, que es lo que hace que esté puesto en la
                // habitación. Ver [Croquis3DControlador.clavarloDelante].
                if (controlador.realidad) {
                    Surface(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 162.dp, end = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { controlador.clavarloDelante() },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            getString(R.string.croquis_clavar_aqui),
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                // **El mando de lo elegido, en la esquina de abajo a la derecha.**
                //
                // Estaba a media altura del canto derecho, que es donde cae el pulgar — y por
                // eso mismo estorbaba: media pantalla a la derecha es justo por donde uno
                // arrastra lo que acaba de elegir, así que el mando se ponía encima de la
                // pieza que venía a mover. En la esquina no tapa nada: el dibujo se trabaja en
                // el medio, la esquina de abajo está vacía, y el pulgar sigue llegando.
                //
                // Se levanta lo que mide la barra de abajo para no montarse sobre ella.
                if (controlador.seleccion.isNotEmpty()) {
                    Croquis3DMando(
                        controlador,
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = 4.dp,
                                bottom = with(LocalDensity.current) { altoDeLaBarra.toDp() } +
                                    AIRE_SOBRE_LA_BARRA
                            )
                    )
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // **Lo que mide de alto, para que nadie se le monte encima.**
                        // El mando de lo elegido vive en la esquina de abajo y se levantaba
                        // un número escrito a mano: en cuanto la barra crecía —otra fila de
                        // herramientas, el mando de la hoja— se le quedaba debajo. Medida,
                        // no hay número que mantener al día. Ver [Croquis3DMando].
                        .onSizeChanged { altoDeLaBarra = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Encima de la barra y no en un diálogo: el color se elige **viendo el
                    // croquis**, que es de lo que va elegir un fondo. Un diálogo lo taparía
                    // justo mientras se decide.
                    // **El mando de la hoja**: una pastilla, no una ✕ suelta.
                    //
                    // Tocarla la quita, que es lo que hacía antes. Y **arrastrándola de
                    // lado se marca más o menos**: una hoja es una mesa de dibujo y tiene
                    // que verse lo justo —tenue no dice dónde está, fuerte tapa lo que se ha
                    // puesto encima—, y eso cambia con lo que haya dibujado, así que es un
                    // mando y no un número fijo. Los dos gestos en el mismo sitio porque son
                    // lo mismo: cuánta hoja quiero, de nada a toda.
                    //
                    // Y la otra ✕ —la de salir de la herramienta— ya no está: no hacía nada
                    // que no hiciera elegir otra cosa, y dos ✕ pegadas son dos ✕ que leer.
                    // **El interruptor de las luces, del mismo palo.**
                    //
                    // Un toque las apaga y las enciende; arrastrando, sube y baja lo que
                    // alumbran. Es el mismo mando que la hoja porque es la misma pregunta
                    // —cuánto de esto quiero, de nada a todo—, y un interruptor por un lado
                    // y un mando de intensidad por otro son dos cosas que dicen lo mismo y
                    // acaban discrepando. Sale solo con la tinta de luz puesta: para las
                    // demás no significa nada.
                    // **Y es la llave del croquis, no el brillo de la tinta.**
                    //
                    // Movía [Croquis3DControlador.luz], que es lo que va a alumbrar **el
                    // trazo siguiente**: se tocaba con el croquis lleno de tubos encendidos y
                    // no cambiaba nada de lo que se estaba mirando —«el botón flotante no
                    // funciona»—. Y encima había otro mando en los ajustes, la llave de paso
                    // del croquis, que sí se notaba: dos mandos parecidos y solo uno hacía
                    // algo. Este es ahora **el mismo** que aquel, a mano y sin abrir nada:
                    // enciende, apaga y gradúa todo lo que alumbra. El brillo de la tinta
                    // vive donde se elige la tinta, junto a su material. Ver [LasLuces].
                    if (controlador.pincel == Pincel.LUZ || controlador.punta.alumbra) {
                        val luces = controlador.croquis.luces
                        Pastilla(
                            texto = if (!luces.encendidas) "☀ ✕" else "☀",
                            lleno = (luces.cuanto / 2.0).toFloat(),
                            // **Y las luces se leen aquí dentro, no ahí arriba.**
                            //
                            // Arrastrando, cada paso suma sobre **lo que hay puesto ahora**.
                            // Leídas al pintar la pastilla, todos los pasos sumaban sobre el
                            // valor de entonces: el mando se iba al primer escalón, volvía y
                            // se quedaba ahí — «parpadea y no sube del diez por ciento».
                            alCorrer = { paso ->
                                val ahora = controlador.croquis.luces
                                val cuanto = (ahora.fuerza + paso / RECORRIDO_DE_LA_LUZ * 2.0)
                                    .coerceIn(0.0, 2.0)
                                controlador.ponerLasLuces(
                                    ahora.copy(encendidas = true, fuerza = cuanto)
                                )
                            },
                            alTocar = {
                                // Apagadas vuelven a encenderse, y a tope si se habían
                                // quedado a cero: quien las toca apagadas las quiere ver.
                                val ahora = controlador.croquis.luces
                                controlador.ponerLasLuces(
                                    ahora.copy(
                                        encendidas = !ahora.encendidas,
                                        fuerza = if (ahora.fuerza <= 0.02) 1.0 else ahora.fuerza
                                    )
                                )
                            }
                        )
                    }

                    if (controlador.hayPlano) {
                        val hoja = controlador.croquis.laminas.first()
                        Surface(
                            Modifier
                                .padding(bottom = 8.dp)
                                .size(width = 96.dp, height = 34.dp)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val abajo = awaitFirstDown()
                                        var corrido = 0f
                                        var cuanto = controlador.croquis.laminas
                                            .firstOrNull()?.opacidad ?: 1.0
                                        abajo.consume()
                                        while (true) {
                                            val evento = awaitPointerEvent()
                                            val dedo = evento.changes.firstOrNull { it.pressed }
                                                ?: break
                                            val paso = dedo.position.x - dedo.previousPosition.x
                                            corrido += kotlin.math.abs(paso)
                                            cuanto += paso / RECORRIDO_DE_LA_HOJA
                                            controlador.transparentarLaHoja(cuanto)
                                            dedo.consume()
                                        }
                                        // Un toque —no se ha ido a ningún sitio— la quita.
                                        if (corrido < viewConfiguration.touchSlop) {
                                            controlador.quitarLaHoja()
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(17.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                            tonalElevation = 3.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                // Lo llena que está la pastilla dice lo marcada que va la
                                // hoja: se ve lo que se está moviendo sin soltar el dedo.
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(
                                            (hoja.opacidad / Croquis3DControlador.MAXIMO_DE_LA_HOJA)
                                                .toFloat().coerceIn(0f, 1f)
                                        )
                                        .align(Alignment.CenterStart)
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        )
                                )
                                Text(
                                    "▱ ✕",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * **Una pastilla de las de abajo**: se toca y hace algo, se arrastra y regula.
     *
     * Los dos gestos en el mismo sitio porque son la misma pregunta: cuánto de esto quiero,
     * de nada a todo. Y lo llena que va dice en qué punto está, así que se ve mientras se
     * arrastra sin tener que soltar para comprobarlo.
     */
    /**
     * **Al dedo apoyado se le lleva la hora**, y en su propio rincón.
     *
     * Late un fotograma sí y otro también mientras hay algo en la mano, y ni uno cuando no
     * lo hay. Ver [Croquis3DControlador.latido].
     *
     * **Y con el reloj de los eventos, no con el de la pared.** Los puntos del trazo vienen
     * con la hora que les puso el sistema al recogerlos —la de arranque, la que trae
     * cualquier evento de Android— y el latido tiene que contar en la misma. Con el reloj de
     * la pared, la resta entre las dos son las horas que lleva encendido el aparato: el
     * enderezado se cumplía en el primer latido y **cualquier raya salía recta nada más
     * empezarla**.
     *
     * Aparte de la pantalla porque saber si hay algo en la mano es mirar el trazo en curso,
     * **y ese cambia con cada muestra del lápiz**: leyéndolo en la pantalla, la pantalla
     * entera se rehacía doscientas veces por segundo por una pregunta de sí o no. Aquí
     * dentro, lo que se rehace es una función vacía.
     */
    @Composable
    private fun ElLatidoDelTrazo() {
        val trazando = controlador.trazando
        LaunchedEffect(trazando) {
            while (trazando) {
                withFrameNanos { }
                controlador.latido(android.os.SystemClock.uptimeMillis())
            }
        }
    }

    /**
     * **Las imágenes, cargadas una vez y guardadas por su ruta.**
     *
     * El lienzo se repinta entero en cada fotograma mientras se gira, así que abrir el
     * archivo ahí dentro sería abrirlo sesenta veces por segundo. Se cargan fuera del hilo
     * de la pantalla —descodificar una foto son decenas de milisegundos, y ahí se vería el
     * tirón— y se quedan en memoria mientras la vista esté abierta.
     *
     * En su propio rincón por lo mismo que [ElLatidoDelTrazo]: mirar qué imágenes trae el
     * croquis es leer el croquis, y el croquis cambia con cada trazo que se suelta y con
     * cada muestra de un arrastre. Lo que tiene que enterarse de eso es esta función, no la
     * pantalla.
     */
    @Composable
    private fun CargarLasImagenes(
        imagenes: androidx.compose.runtime.snapshots.SnapshotStateMap<String, android.graphics.Bitmap>
    ) {
        val rutas = controlador.croquis.imagenes.map { it.ruta }
        LaunchedEffect(rutas) {
            val recien = rutas.filter { it !in imagenes }.distinct()
            if (recien.isEmpty()) return@LaunchedEffect
            val cargadas = withContext(Dispatchers.IO) {
                recien.mapNotNull { ruta -> ImageStore.load(ruta)?.let { ruta to it } }
            }
            imagenes.putAll(cargadas)
        }
    }

    /**
     * El aumento al que se está trabajando. En su rincón: cambia en cada fotograma al navegar.
     *
     * **Y solo cuando cambia el número, no cuando cambia la cámara.** Mirar `camara.zoom` es
     * mirar la cámara entera, así que girando —donde el aumento no se mueve— este renglón se
     * volvía a montar y a medir sesenta veces por segundo para escribir el mismo «100 %».
     * Medir un texto no es gratis, y esto pasa justo durante el gesto que peor va. Guardando
     * el número ya redondeado, lo que se vuelve a montar es lo que de verdad cambió.
     */
    @Composable
    private fun ElAumento() {
        val cuanto by remember {
            derivedStateOf { (controlador.camara.zoom * 100).roundToInt() }
        }
        Text(
            getString(R.string.croquis_aumento, cuanto),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }

    /**
     * La lente que hay puesta, y solo cuando no es la de siempre.
     *
     * En su rincón por lo mismo que [ElAumento]: mirar qué lente hay puesta es mirar la
     * cámara, y la cámara se mueve en cada fotograma de cualquier gesto de navegar.
     */
    @Composable
    private fun BoxScope.LaLente() {
        // Igual que el aumento: lo que importa es **qué lente hay**, y eso cambia cuando se
        // cambia de lente, no en cada fotograma de un giro.
        val cual by remember {
            derivedStateOf {
                controlador.camara.nombreDeLaLente.takeIf { controlador.camara.lente > 0.02 }
            }
        }
        cual?.let { nombre ->
            Surface(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 82.dp, end = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { controlador.quitarLaLente() },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    nombre,
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }

    @Composable
    private fun Pastilla(
        texto: String,
        lleno: Float,
        alCorrer: (Float) -> Unit,
        alTocar: () -> Unit
    ) {
        // Al día y no como estaban al aparecer, por lo mismo que en [MandoQueSeArrastra]: el
        // atendedor se arma una vez y se quedaría con lo que hubiera entonces.
        val correr by rememberUpdatedState(alCorrer)
        val tocar by rememberUpdatedState(alTocar)
        Surface(
            Modifier
                .padding(bottom = 8.dp)
                .size(width = 96.dp, height = 34.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val abajo = awaitFirstDown()
                        var corrido = 0f
                        abajo.consume()
                        while (true) {
                            val evento = awaitPointerEvent()
                            val dedo = evento.changes.firstOrNull { it.pressed } ?: break
                            val paso = dedo.position.x - dedo.previousPosition.x
                            corrido += kotlin.math.abs(paso)
                            correr(paso)
                            dedo.consume()
                        }
                        if (corrido < viewConfiguration.touchSlop) tocar()
                    }
                },
            shape = RoundedCornerShape(17.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            tonalElevation = 3.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(lleno.coerceIn(0f, 1f))
                        .align(Alignment.CenterStart)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                )
                Text(texto, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    /**
     * **La barra de al lado: el pincel, y nada más que el pincel.**
     *
     * Cinco cosas, y son las cinco que se tocan **mientras se dibuja**: con qué punta, de qué
     * color, de qué grueso, cuánto tapa y cuánto le hace caso al pulso de la mano. Ni una más.
     *
     * Ha tenido de todo —el borrador, encuadrar, lo de la selección, lo de la hoja— y cada
     * cosa que se le añadía la empeoraba de la misma manera: una barra que está al lado del
     * dibujo se lee **de reojo**, sin apartar la vista de lo que se está trazando, y eso solo
     * funciona si uno se sabe de memoria dónde está cada botón. Con cinco botones fijos se
     * sabe; con seis que cambian según la herramienta, no — hay que mirar. Y lo que no se
     * toca dibujando no tiene por qué estar donde la mano ya está.
     *
     * ## Un botón por cosa, y se ajusta arrastrando encima
     *
     * Es lo que hace que quepan cinco y no quince. Cada mando enseña **lo que hay puesto**
     * —la punta que sale, el color que sale, lo gordo que sale— y para cambiarlo se posa el
     * lápiz encima y se sube o se baja, sin levantarlo. No hay tira que ocupe media pantalla,
     * no hay panel que se abra encima del dibujo y no hay que apuntar a un mando de dos
     * milímetros: **el sitio donde se agarra es el botón entero, y el recorrido es toda la
     * pantalla**, que es lo contrario de lo que pasa con una tira, donde apurar el extremo es
     * apuntar a su última esquina.
     *
     * Antes eran dos tiras verticales de cien píxeles de alto pegadas a la barra. Eso es
     * medio croquis tapado por dos mandos que se usan tres segundos, y encima con la puntería
     * al revés: la parte de la tira que más se usa —lo fino— es la que menos sitio tiene.
     * Arrastrando, el recorrido lo pone la mano y no la barra.
     *
     * Y un toque sigue haciendo lo evidente: pasar a la punta siguiente, abrir el taller de
     * tintas, encender o apagar el pulso. Lo demás vive en ajustes. Ver [LosAjustes] y
     * [MandoQueSeArrastra].
     */
    @Composable
    private fun LaBarraDeDibujar(
        modifier: Modifier = Modifier,
        alAbrirLasTintas: () -> Unit
    ) {
        val chivato = remember { ElChivato() }
        // Con la mano fuera, el chivato se va solo: es para mirar de reojo mientras se
        // arrastra, no una etiqueta más pegada a la barra.
        //
        // **El reloj se arma con la cuenta y no con lo que dice.** Arrastrando, lo que dice
        // cambia en cada muestra del lápiz, así que atado a eso se estaría rearmando un
        // reloj por fotograma para no usarlo ninguna vez. Ver [ElChivato.cuenta].
        LaunchedEffect(chivato.cuenta) {
            if (chivato.dice != null && !chivato.agarrado) {
                kotlinx.coroutines.delay(LO_QUE_DURA_EL_CHIVATO)
                chivato.callar()
            }
        }
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            // **Con fondo pintado y no con una `Surface`, y esa es la diferencia que
            // importa.**
            //
            // Una superficie con forma **recorta a sus hijos**, y de aquí tiene que poder
            // salirse la rueda del color: cuelga del mando para que lo que se ve esté
            // exactamente donde el gesto la busca, así que dentro de una `Surface` se
            // pintaba fuera del recorte y no se veía ninguna — el atajo del color dejaba de
            // funcionar sin más. `background` solo pinta detrás. Es lo mismo que hace el
            // panel lateral del lienzo plano, y por lo mismo. Ver [ElColorQueHay].
            Column(
                Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // **La punta**: arrastrando se pasan las que hay, y de un toque, a la
                // siguiente. Un botón y no las cinco en fila, porque en fila son cinco
                // sitios que mirar y aquí solo hay sitio para el que está puesto — que
                // además es la información que hace falta de reojo: con cuál estoy
                // dibujando.
                LaPuntaQueHay(chivato)
                Filete()
                // **El color**, con la muestra de lo que va a salir. Arrastrando se
                // corre el tono; de un toque abre el taller de tintas, que es donde se
                // elige de verdad.
                ElColorQueHay(chivato, alAbrirLasTintas)
                Filete()
                // **Lo gordo**, que es lo que más se cambia de todo.
                ElGrosor(chivato)
                Filete()
                // **Lo que tapa.**
                LoQueTapa(chivato)
                Filete()
                // **Y cuánto manda la mano.** Un toque lo enciende o lo apaga —quien lo
                // apaga lo apaga porque su raya salía temblona— y arrastrando se gradúa.
                ElPulso(chivato)
            }
            LoQueDiceElMando(chivato)
        }
    }

    /**
     * **El chivato: lo que se está ajustando, al lado de la barra y a su tamaño de verdad.**
     *
     * Lleva el nombre y, sobre todo, **la muestra sin recortar**. La del propio mando está
     * metida en un botón de dos centímetros, así que un trazo gordo no cabe: se ve un círculo
     * tocando los bordes y de ahí para arriba todo igual — justo cuando más falta hace saber
     * cuánto es. Aquí flota al lado, crece con lo que se está eligiendo y no la limita
     * ningún contenedor.
     */
    @Composable
    private fun LoQueDiceElMando(chivato: ElChivato) {
        val dice = chivato.dice ?: return
        Surface(
            Modifier.padding(start = 8.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f)
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoQueVaASalir(chivato.deQuien)
                Text(
                    dice,
                    Modifier.padding(start = 8.dp),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }

    /**
     * **Lo que va a salir, a tamaño de verdad**, para el chivato de al lado de la barra.
     *
     * Cada mando enseña lo suyo: lo gordo que va a salir el trazo —medido en el mundo y por
     * el aumento de este momento, que es lo único que dice de verdad cuánto es—, lo que tapa
     * la tinta sobre su damero, o la punta que hay puesta. El pulso no tiene nada que
     * enseñar: su número va dentro del mando y ya lo dice todo.
     */
    @Composable
    private fun LoQueVaASalir(quien: String?) {
        when (quien) {
            ES_EL_GROSOR -> {
                val color = MaterialTheme.colorScheme.inverseOnSurface
                val comoSaldra = controlador.grosorDeVerdad * controlador.camara.zoom
                val lado = (comoSaldra + 8).dp.coerceIn(26.dp, LO_QUE_CABE_EN_EL_CHIVATO.dp)
                Canvas(Modifier.size(lado)) {
                    val radio = (comoSaldra.dp.toPx() / 2)
                        .coerceIn(1.5f, size.minDimension / 2 - 1f)
                    drawCircle(color, radius = radio, center = center)
                }
            }

            ES_LO_QUE_TAPA -> {
                val suyo = Color(parseColor(controlador.color, 255))
                Canvas(Modifier.size(34.dp)) {
                    val cuadro = size.width / 4
                    var y = 0f
                    var impar = false
                    while (y < size.height) {
                        var x = 0f
                        var k = impar
                        while (x < size.width) {
                            drawRect(
                                if (k) Color(0x33FFFFFF) else Color(0x33000000),
                                topLeft = Offset(x, y),
                                size = androidx.compose.ui.geometry.Size(
                                    kotlin.math.min(cuadro, size.width - x),
                                    kotlin.math.min(cuadro, size.height - y)
                                )
                            )
                            x += cuadro
                            k = !k
                        }
                        y += cuadro
                        impar = !impar
                    }
                    drawCircle(
                        suyo.copy(alpha = controlador.opacidad.toFloat().coerceIn(0f, 1f)),
                        radius = size.width * 0.44f,
                        center = center
                    )
                }
            }

            ES_LA_PUNTA -> MuestraDeLaPunta(controlador.pincel, 40.dp)

            else -> Unit
        }
    }

    /**
     * **El mando que se arrastra: el del motor, con la pinta de aquí.**
     *
     * Era una copia del de [com.forge.pixpin.motor.MandoDelPanel] —el mismo gesto escrito dos
     * veces— y las dos copias se fueron separando: el desfase del puntero que se arregló en el
     * lienzo plano nunca llegó aquí, y al revés, lo que aquí funcionaba bien allí no. Ahora hay
     * **una sola implementación del gesto** y esto solo pide el aspecto: aquí los mandos van
     * sobre el dibujo y no dentro de un panel, así que ni engordan, ni vibran, ni llevan
     * pastilla debajo. Lo que se arregle en el gesto se arregla en los dos a la vez.
     */
    @Composable
    private fun MandoQueSeArrastra(
        alAgarrar: () -> Unit,
        alArrastrar: (dx: Float, dy: Float) -> Unit,
        alSoltar: () -> Unit,
        alTocar: () -> Unit = {},
        /** Medir dónde está el dedo, no cuánto se ha movido. */
        desdeElCentro: Boolean = false,
        descripcion: String = "",
        dentro: @Composable BoxScope.() -> Unit
    ) {
        Box(Modifier.padding(vertical = 2.dp)) {
            com.forge.pixpin.motor.MandoDelPanel(
                descripcion = descripcion,
                bola = LADO_DEL_MANDO,
                alAgarrar = alAgarrar,
                alArrastrar = alArrastrar,
                alSoltar = alSoltar,
                alTocar = alTocar,
                desdeElCentro = desdeElCentro,
                engorda = false,
                vibra = false,
                conPastilla = false,
                dentro = dentro
            )
        }
    }

    /**
     * **El número de lo que se está ajustando, dentro del propio mando.**
     *
     * Encima de lo que enseñe el botón y sobre un velo oscuro, que es lo único que se lee
     * igual sobre una pastilla amarilla que sobre una azul marino. Sale solo mientras se está
     * tocando ese mando. Ver [ElChivato.numero].
     */
    @Composable
    private fun BoxScope.ElNumeroDentro(chivato: ElChivato, quien: String) {
        if (chivato.deQuien != quien) return
        val cuanto = chivato.numero ?: return
        Box(
            Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(cuanto, fontSize = 12.sp, color = Color.White)
        }
    }

    /**
     * **La punta que hay puesta.** Arrastrando se pasan las que hay; de un toque, la
     * siguiente.
     *
     * Las puntas son cinco y van en rueda, así que el arrastre da la vuelta por los dos
     * lados: subir del final vuelve al principio. Un paso por cada [PASO_DE_LA_PUNTA] de
     * recorrido — lo bastante largo para que no se cuelen dos de una sacudida y lo bastante
     * corto para recorrerlas todas sin soltar.
     */
    @Composable
    private fun LaPuntaQueHay(chivato: ElChivato) {
        val cuales = Pincel.LAS_QUE_HAY
        var partida by remember { mutableStateOf(0) }
        fun poner(cual: Pincel) {
            if (cual == controlador.pincel) return
            controlador.pincel = cual
            // **Elegir una tinta es ponerse a dibujar con ella.**
            //
            // Con el trazador de planos puesto, la barra seguía cambiando de punta y lo que
            // salía del dedo eran planos —eso sí, del color del lápiz que uno acababa de
            // elegir—. Nadie coge el rotulador para poner otra mesa: si se elige tinta, se
            // vuelve a dibujar. Es lo mismo que hace la fila de puntas del taller.
            controlador.herramienta = Herramienta3D.LAPIZ
            controlador.empincelarLaSeleccion(cual)
        }
        MandoQueSeArrastra(
            alAgarrar = {
                partida = cuales.indexOf(controlador.pincel).coerceAtLeast(0)
                chivato.agarrado = true
                chivato.ensenar(ES_LA_PUNTA, controlador.pincel.comoSeLlama)
            },
            alArrastrar = { _, recorrido ->
                val paso = (recorrido / PASO_DE_LA_PUNTA).roundToInt()
                val cual = cuales[((partida + paso) % cuales.size + cuales.size) % cuales.size]
                poner(cual)
                chivato.ensenar(ES_LA_PUNTA, cual.comoSeLlama)
            },
            alSoltar = { chivato.soltar() },
            alTocar = {
                val cual = cuales[(cuales.indexOf(controlador.pincel) + 1) % cuales.size]
                poner(cual)
                chivato.deReojo(ES_LA_PUNTA, cual.comoSeLlama)
            }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                MuestraDeLaPunta(controlador.pincel)
            }
            Flechitas()
        }
    }

    /**
     * **El color que hay puesto**, y la rueda que sale al arrastrarlo.
     *
     * Dos gestos y dos usos, que es lo que pedía un botón de dos centímetros que se toca
     * treinta veces mientras se dibuja:
     *
     * - **Se arrastra y sale una rueda de color ahí mismo.** El dedo no la toca: la rueda
     *   sale al lado y lo que manda es hacia dónde y cuánto se ha ido el dedo desde donde se
     *   posó —el ángulo es el tono y lo lejos, la viveza—. Se suelta y se queda el color que
     *   hubiera. Es un solo gesto, sin abrir nada y sin apuntar a nada; abrir un panel para
     *   cambiar de rojo a azul es pedir cuatro toques a quien está dibujando.
     * - **Y de un toque se abre el taller del color**, con la rueda de verdad, los colores
     *   guardados y el material. Ahí se elige despacio, que también hace falta.
     *
     * La claridad no la toca el gesto: es la que hace que una tinta sea *esa* tinta, y
     * cambiarla de refilón dejaría el croquis descolorido sin que nadie lo pidiera. Para eso
     * está el taller.
     */
    @Composable
    private fun ElColorQueHay(
        chivato: ElChivato,
        alAbrir: () -> Unit
    ) {
        var claridad by remember { mutableStateOf(1f) }
        // Adónde apunta la marca de la rueda, o nada si no hay mano encima.
        var apunta by remember { mutableStateOf<PuntoDeLaRueda?>(null) }
        // **En `dp`, que es como llega el recorrido.** Estaba en píxeles —`toPx()`— y el
        // arrastre viene en `dp`: en una pantalla de hoy eso pone el centro de la rueda a
        // casi el triple de distancia de donde se pinta, o sea fuera de la pantalla. El dedo
        // no entraba nunca en ella y el color no cambiaba nunca. Ver [MandoQueSeArrastra].
        val alLado = APARTADO_DE_LA_RUEDA
        MandoQueSeArrastra(
            alAgarrar = {
                controlador.empezarARetocar()
                val hsv = enHsv(parseColor(controlador.color, 255))
                // **La claridad de la tinta va tal cual; el mínimo es solo para ver el
                // disco.** Era una sola variable, y esa subida se colaba en el color que se
                // emite: con una tinta oscura, agarrar el mando para mover **el tono** la
                // aclaraba sin que nadie lo pidiera. El mínimo existe para que la rueda no
                // salga negra, que es cosa de cómo se ve y no de qué tinta es. El mando del
                // lienzo plano lleva el mismo arreglo. Ver [CLARIDAD_MINIMA].
                claridad = hsv[2].coerceAtLeast(LO_MENOS_QUE_TAPA.toFloat())
                // La rueda sale **antes** de tocarla, con la marca en el color que hay
                // puesto: es lo que dice hacia dónde hay que llevar el dedo.
                apunta = PuntoDeLaRueda(hsv[0], hsv[1], claridad.coerceAtLeast(CLARIDAD_MINIMA))
                chivato.agarrado = true
            },
            alArrastrar = { lado, subida ->
                // **El dedo tiene que llegar a la rueda, y ahí manda dónde está.**
                //
                // Se medía el ángulo y la distancia **desde donde se posó el dedo**, y la
                // rueda se pinta al lado del botón: dos círculos distintos, uno debajo de la
                // mano y otro a un palmo. Lo que se veía no era donde estaba el dedo sino una
                // traducción, y con la traducción hay tonos a los que no se llega — el medio
                // giro que cae hacia el borde de la pantalla se queda fuera del alcance del
                // pulgar.
                //
                // Ahora es la rueda que se ve: se lleva el dedo hasta ella y **desde que
                // entra** la marca va donde va el dedo, punto por punto. Fuera de la rueda no
                // se cambia nada, que es lo que permite ir a buscarla sin dejar el color por
                // el camino.
                val enElDisco = lado - alLado
                if (kotlin.math.hypot(enElDisco, subida) > RADIO_DE_LA_RUEDA) {
                    return@MandoQueSeArrastra
                }
                // **Y si el dedo pasa por encima de un guardado, cae en él.** Es lo que
                // convierte un punto pintado en un sitio al que se puede volver: a pulso,
                // sobre una rueda de dos dedos, nadie acierta dos veces el mismo tono.
                val guardados = controlador.croquis.favoritos
                val encima = marcaImantada(
                    enElDisco, subida, RADIO_DE_LA_RUEDA,
                    guardados.map { val h = enHsv(parseColor(it, 255)); h[0] to h[1] }
                )
                val cual: String
                if (encima >= 0) {
                    cual = guardados[encima]
                    val h = enHsv(parseColor(cual, 255))
                    apunta = PuntoDeLaRueda(h[0], h[1], h[2])
                } else {
                    val tono = ((Math.toDegrees(
                        kotlin.math.atan2(-subida.toDouble(), enElDisco.toDouble())
                    ) + 360.0) % 360.0).toFloat()
                    val viveza = (kotlin.math.hypot(enElDisco, subida) / RADIO_DE_LA_RUEDA)
                        .coerceIn(0f, 1f)
                    apunta = PuntoDeLaRueda(tono, viveza, claridad.coerceAtLeast(CLARIDAD_MINIMA))
                    cual = enTexto(deHsv(floatArrayOf(tono, viveza, claridad)))
                }
                controlador.color = cual
                controlador.pintarLaSeleccion(cual)
            },
            alSoltar = {
                apunta = null
                chivato.soltar()
            },
            alTocar = alAbrir,
            desdeElCentro = true
        ) {
            // **La rueda cuelga del propio mando**, no de la barra: así lo que se ve está
            // exactamente donde el gesto la busca. Va fuera de la medida —`unbounded`— para
            // que aparecer no le quite sitio a nadie ni empuje a los mandos de al lado.
            apunta?.let { donde ->
                Box(
                    Modifier
                        .wrapContentSize(unbounded = true)
                        .offset(x = APARTADO_DE_LA_RUEDA.dp)
                ) { LaRuedaRapida(donde, controlador.croquis.favoritos) }
            }
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(parseColor(controlador.color, 255)))
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
            Flechitas()
        }
    }

    /** Adónde apunta la rueda rápida: el tono, lo viva y lo clara. Ver [ElColorQueHay]. */
    private class PuntoDeLaRueda(val tono: Float, val viveza: Float, val claridad: Float)

    /**
     * **La rueda rápida**: el disco de color, con la marca en donde está el dedo.
     *
     * No se toca — el dedo sigue en el botón — así que no es un mando, es **lo que dice qué
     * está eligiendo el gesto**. Sin ella, arrastrar el color es a ciegas: se ve el color que
     * va saliendo pero no dónde está uno ni hacia dónde ir para llegar al que se busca.
     *
     * Es **la misma** que la del taller y la misma que la del lienzo plano: una sola rueda en
     * toda la aplicación, así que lo que se aprende con el gesto rápido vale en los tres
     * sitios. Ver [LaRuedaDelColor].
     */
    @Composable
    private fun LaRuedaRapida(donde: PuntoDeLaRueda, marcas: List<String>) {
        LaRuedaDelColor(
            tono = { donde.tono },
            viveza = { donde.viveza },
            marcas = marcas,
            claridad = donde.claridad
        )
    }

    /**
     * **Lo gordo que sale, con la muestra al tamaño de verdad.**
     *
     * La bolita del botón **es** lo que va a salir: lo que mide el trazo en el mundo, por el
     * aumento de este momento. No se puede adivinar del número, porque el mismo seis se ve
     * gordo de cerca y fino de lejos, y el rodillo multiplica por seis. Acotada al botón, que
     * a tope se sale — y que se salga también es una respuesta.
     *
     * ## Y el arrastre va a saltos iguales de tamaño, no de número
     *
     * De uno y medio a sesenta y cuatro hay cuarenta veces, y repartir eso a partes iguales
     * por el recorrido deja lo de dibujar —de dos a seis— en el primer centímetro y el resto
     * de la pantalla para grosores de dar manos de color. Repartido **por veces** (cada tanto
     * de recorrido, otro tanto por ciento de grueso) el mando responde igual de fino abajo
     * que arriba, que es como se percibe el grosor: nadie nota un píxel más sobre sesenta,
     * y sobre dos lo nota todo el mundo.
     */
    @Composable
    private fun ElGrosor(chivato: ElChivato) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        var partida by remember { mutableStateOf(0.0) }
        MandoQueSeArrastra(
            alAgarrar = {
                controlador.empezarARetocar()
                partida = controlador.grosor
                chivato.agarrado = true
                chivato.ensenar(ES_EL_GROSOR, elNombreDelGrosor(), enNumero(controlador.grosor))
            },
            alArrastrar = { _, recorrido ->
                val gordo = (partida * Math.pow(VECES_POR_DP, recorrido.toDouble()))
                    .coerceIn(GROSOR_MINIMO.toDouble(), GROSOR_MAXIMO.toDouble())
                controlador.grosor = gordo
                controlador.engrosarLaSeleccion(gordo)
                chivato.ensenar(ES_EL_GROSOR, elNombreDelGrosor(), enNumero(gordo))
            },
            alSoltar = { chivato.soltar() },
            alTocar = {
                chivato.deReojo(ES_EL_GROSOR, elNombreDelGrosor(), enNumero(controlador.grosor))
            }
        ) {
            Canvas(Modifier.size(30.dp)) {
                val comoSaldra = (controlador.grosorDeVerdad * controlador.camara.zoom).dp.toPx()
                val radio = (comoSaldra / 2).coerceIn(size.width * 0.06f, size.width * 0.46f)
                drawCircle(color, radius = radio, center = Offset(size.width / 2, size.height / 2))
            }
            Flechitas()
            ElNumeroDentro(chivato, ES_EL_GROSOR)
        }
    }

    /** Cómo se llama lo gordo que sale. */
    private fun elNombreDelGrosor(): String = getString(R.string.croquis_punta_ancho)

    /** Un grosor, en un número corto: «6,0». */
    private fun enNumero(gordo: Double): String = "%.1f".format(gordo)

    /**
     * **Lo que tapa la tinta**: la pastilla del color sobre el damero.
     *
     * El damero de debajo es lo que hace que se vea que algo es transparente — sin él, una
     * tinta al veinte por ciento sobre el fondo del panel es un color más claro y no una
     * tinta que deja ver.
     */
    @Composable
    private fun LoQueTapa(chivato: ElChivato) {
        val suyo = Color(parseColor(controlador.color, 255))
        var partida by remember { mutableStateOf(1.0) }
        val nombre = getString(R.string.croquis_tapa)
        fun enNumero(cuanta: Double) = "${(cuanta * 100).roundToInt()} %"
        MandoQueSeArrastra(
            alAgarrar = {
                controlador.empezarARetocar()
                partida = controlador.opacidad
                chivato.agarrado = true
                chivato.ensenar(ES_LO_QUE_TAPA, nombre, enNumero(controlador.opacidad))
            },
            alArrastrar = { _, recorrido ->
                val cuanta = (partida + recorrido / RECORRIDO_DEL_MANDO)
                    .coerceIn(LO_MENOS_QUE_TAPA, 1.0)
                controlador.opacidad = cuanta
                controlador.transparentarLaSeleccion(cuanta)
                chivato.ensenar(ES_LO_QUE_TAPA, nombre, enNumero(cuanta))
            },
            alSoltar = { chivato.soltar() },
            alTocar = {
                chivato.deReojo(ES_LO_QUE_TAPA, nombre, enNumero(controlador.opacidad))
            }
        ) {
            Canvas(Modifier.size(28.dp)) {
                val cuadro = size.width / 4
                var y = 0f
                var impar = false
                while (y < size.height) {
                    var x = 0f
                    var k = impar
                    while (x < size.width) {
                        drawRect(
                            if (k) Color(0x22FFFFFF) else Color(0x33000000),
                            topLeft = Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(
                                kotlin.math.min(cuadro, size.width - x),
                                kotlin.math.min(cuadro, size.height - y)
                            )
                        )
                        x += cuadro
                        k = !k
                    }
                    y += cuadro
                    impar = !impar
                }
                drawCircle(
                    suyo.copy(alpha = controlador.opacidad.toFloat().coerceIn(0f, 1f)),
                    radius = size.width * 0.42f,
                    center = Offset(size.width / 2, size.height / 2)
                )
            }
            Flechitas()
            ElNumeroDentro(chivato, ES_LO_QUE_TAPA)
        }
    }

    /**
     * **Cuánto manda el pulso de la mano.**
     *
     * De un toque se enciende y se apaga —que es lo que se pide nueve de cada diez veces: la
     * raya salía temblona y se quiere pareja— y arrastrando se gradúa: en cero la raya sale
     * de un grueso, en uno responde como responde la mano y por encima exagera, que sirve
     * para que se note en un croquis pequeño. Graduarlo lo enciende, claro: nadie sube la
     * sensibilidad de algo que quiere apagado.
     */
    @Composable
    private fun ElPulso(chivato: ElChivato) {
        val puesto = controlador.punta.pulso
        var partida by remember { mutableStateOf(1.0) }
        fun nombre(encendido: Boolean) =
            if (!encendido) getString(R.string.croquis_pulso_apagado)
            else getString(R.string.croquis_punta_sensible)
        fun enNumero(cuanto: Double, encendido: Boolean) =
            if (!encendido) "✕" else "%.1f".format(cuanto)
        MandoQueSeArrastra(
            alAgarrar = {
                partida = controlador.punta.sensibilidad
                chivato.agarrado = true
                val hay = controlador.punta.pulso
                chivato.ensenar(ES_EL_PULSO, nombre(hay), enNumero(partida, hay))
            },
            alArrastrar = { _, recorrido ->
                val cuanto = (partida + recorrido / RECORRIDO_DEL_MANDO * PULSO_MAXIMO)
                    .coerceIn(0.0, PULSO_MAXIMO)
                val enciende = cuanto > 0.02
                val nueva = controlador.punta.copy(sensibilidad = cuanto, pulso = enciende)
                controlador.punta = nueva
                controlador.empuntarLaSeleccion(nueva)
                chivato.ensenar(ES_EL_PULSO, nombre(enciende), enNumero(cuanto, enciende))
            },
            alSoltar = { chivato.soltar() },
            alTocar = {
                val nueva = controlador.punta.copy(pulso = !controlador.punta.pulso)
                controlador.punta = nueva
                controlador.empuntarLaSeleccion(nueva)
                chivato.deReojo(
                    ES_EL_PULSO,
                    nombre(nueva.pulso),
                    enNumero(nueva.sensibilidad, nueva.pulso)
                )
            }
        ) {
            Icon(
                if (puesto) Icons.Filled.Gesture else Icons.Filled.Remove,
                getString(R.string.croquis_punta_pulso),
                tint =
                    if (puesto) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Flechitas()
            ElNumeroDentro(chivato, ES_EL_PULSO)
        }
    }

    /** Un botón de la barra de al lado, con el mismo aire que los demás. */
    @Composable
    private fun EnLaBarra(
        icono: androidx.compose.ui.graphics.vector.ImageVector,
        descripcion: Int,
        encendido: Boolean = false,
        alTocar: () -> Unit
    ) {
        IconButton(onClick = alTocar) {
            Icon(
                icono,
                getString(descripcion),
                tint =
                    if (encendido) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    /**
     * De qué color es el espacio.
     *
     * Lo preguntan las dos capas del lienzo —la escena lo pinta y la mano lo usa para que
     * sus guías salgan de lo contrario—, así que se responde en un sitio: si las dos
     * dijeran cosas distintas, una línea auxiliar podría acabar del color del fondo.
     */
    /**
     * **De qué color es el papel del croquis ahora mismo**, en `#rrggbb`.
     *
     * Lo apunta el pintado y lo lee el exportador. Sin esto, un croquis que no ha elegido
     * papel se exportaba **siempre sobre pizarra** —el color de fábrica— aunque en la
     * pantalla se estuviera viendo sobre papel claro, y lo compartido no se parecía a lo que
     * el que lo mandó tenía delante.
     */
    private var elPapelDeAhora: String? = null

    private fun elFondoDelEspacio(superficie: Color, conCamara: Boolean): Color =
        // Transparente solo si de verdad hay una cámara detrás: sin ella, un fondo
        // transparente es un agujero negro, y lo que se quiere es el espacio del croquis.
        if (controlador.realidad && conCamara) Color.Transparent
        else (controlador.croquis.colorDelFondo?.let { Color(parseColor(it, 255)) } ?: superficie)
            .also { elPapelDeAhora = "#%06x".format(it.toArgb() and 0xFFFFFF) }

    /** El filete que separa dos tandas de la barra lateral. */
    @Composable
    private fun Filete() {
        Box(
            Modifier
                .padding(vertical = 3.dp)
                .size(width = 22.dp, height = 1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }

    /**
     * **Los ajustes, en pestañas.**
     *
     * Aquí vive todo lo que no se toca mientras se traza: poner un plano, el espejo, elegir,
     * traer una imagen, la punta, la tinta, el sitio y lo que hay montado. Eran dos filas de
     * iconos y había que acordarse de cuál era cuál; en pestañas con su nombre, **buscar es
     * leer cinco palabras**, y dentro de cada una está lo suyo y nada más.
     */
    @Composable
    private fun LosAjustes(
        pestana: PestanaDelCroquis,
        alCambiarDePestana: (PestanaDelCroquis) -> Unit,
        imagenes: Map<String, android.graphics.Bitmap>,
        densidad: androidx.compose.ui.unit.Density,
        alTraerImagen: () -> Unit
    ) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 3.dp
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (cual in PestanaDelCroquis.entries) {
                        val puesta = cual == pestana
                        Text(
                            getString(cual.nombre),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (puesta) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (puesta) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    } else Color.Transparent
                                )
                                .clickable { alCambiarDePestana(cual) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                when (pestana) {
                    PestanaDelCroquis.DIBUJO -> LoDeDibujar(alTraerImagen)
                    PestanaDelCroquis.PUNTA -> Croquis3DPunta(controlador, Modifier.fillMaxWidth())
                    // El fondo se fue a las capas —es la primera de ellas—, así que
                    // aquí queda lo que de verdad es del sitio: la luz que lo alumbra.
                    PestanaDelCroquis.ESCENA -> Column {
                        LaPautaDeLasHojas()
                        ElDiafragma()
                        LasLuces()
                        ElAstro(controlador.croquis.sol, deNoche = false)
                        ElAstro(controlador.croquis.luna, deNoche = true)
                    }
                    PestanaDelCroquis.DETALLE -> ElDetalle()
                    PestanaDelCroquis.PIEZAS -> Column {
                        Croquis3DLista(controlador, Modifier.fillMaxWidth())
                        Croquis3DVistas(
                            controlador, imagenes, Modifier.fillMaxWidth().padding(top = 6.dp),
                            alCongelar = { congelar(densidad) },
                            alRecortar = { controlador.recortando = true }
                        )
                    }
                }
            }
        }
    }

    /**
     * **La pestaña de dibujar: lo que acompaña a una herramienta, no las herramientas.**
     *
     * Las herramientas se fueron **al lateral**, con el pincel. Poner un plano o coger el
     * borrador es parte de dibujar, y lo que es parte de dibujar va donde ya está la mano;
     * detrás del botón de ajustes eran tres toques de ida y tres de vuelta cada vez que se
     * cambiaba de superficie. Ver [LasHerramientas].
     *
     * Y **no se quedaron aquí también**: tenerlas en los dos sitios es el mismo botón dos
     * veces —dos sitios donde buscarlo, dos que mantener y la duda de si hacen lo mismo—,
     * que es de lo que se salió cuando lo de la selección se fue de aquí.
     *
     * Lo que queda es lo que no es una herramienta: reflejar lo que ya está dibujado, que se
     * hace una vez y no es un modo, y traer una imagen, que es abrir el selector de fotos.
     */
    @Composable
    private fun LoDeDibujar(alTraerImagen: () -> Unit) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // **Reflejar lo que ya está.** El espejo puesto refleja lo que se traza a partir
            // de ahí; esto se lleva al otro lado **lo de antes**, que es lo que hace falta
            // cuando la pieza ya está hecha. Sale solo con espejo puesto. Ver
            // [Croquis3DControlador.reflejarLoDibujado].
            if (controlador.croquis.espejo != null) {
                Alternable(
                    encendido = false,
                    icono = Icons.Filled.ContentCopy,
                    descripcion = getString(R.string.croquis_reflejar_lo_dibujado)
                ) {
                    if (!controlador.reflejarLoDibujado()) {
                        Toast.makeText(
                            this@Croquis3DActivity,
                            getString(R.string.croquis_nada_que_reflejar),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                FileteDePie()
            }

            Alternable(
                encendido = false,
                icono = Icons.Filled.AddPhotoAlternate,
                descripcion = getString(R.string.croquis_imagen)
            ) { alTraerImagen() }
            FileteDePie()
            // **Una gráfica en el espacio**: superficie o curva por su fórmula. Ver [Graficas3D].
            Alternable(
                encendido = false,
                icono = Icons.Filled.Functions,
                descripcion = getString(R.string.grafica3d_abrir)
            ) { grafica3dAbierta = true }
            FileteDePie()
            // **El croquis como modelo 3D**, para Blender o cualquier visor. Ver [ExportarObj].
            Alternable(
                encendido = false,
                icono = Icons.Filled.ViewInAr,
                descripcion = getString(R.string.croquis_exportar_modelo)
            ) { exportarElModelo() }
            // **Y como página web**, para quien no tiene con qué abrir un modelo: se abre
            // en el navegador y se gira con el dedo. Ver [ExportarCroquisHtml].
            Alternable(
                encendido = false,
                icono = Icons.Filled.Public,
                descripcion = getString(R.string.croquis_exportar_html)
            ) { exportarLaPagina() }
            // **Y editable**: un `.pixpin` con el croquis, para seguirlo en otro aparato o en
            // el escritorio. Ver [PaquetePixpin].
            Alternable(
                encendido = false,
                icono = Icons.Filled.FolderZip,
                descripcion = getString(R.string.croquis_exportar_paquete)
            ) { exportarElPaquete() }
        }
    }

    /**
     * Una imagen del croquis metida en el archivo, como `data:`. Se lee del almacén y se
     * recomprime a JPEG —o a PNG si tiene transparencia— para que no se dispare el peso.
     */
    private fun imagenIncrustada(ruta: String): String? = runCatching {
        val bmp = com.forge.pixpin.pin.ImageStore.load(ruta, MAX_LADO_DE_LA_TEXTURA) ?: return null
        val salida = java.io.ByteArrayOutputStream()
        val conAlfa = bmp.hasAlpha()
        val formato =
            if (conAlfa) android.graphics.Bitmap.CompressFormat.PNG
            else android.graphics.Bitmap.CompressFormat.JPEG
        bmp.compress(formato, CALIDAD_DE_LA_TEXTURA, salida)
        val tipo = if (conAlfa) "image/png" else "image/jpeg"
        "data:$tipo;base64," + android.util.Base64.encodeToString(
            salida.toByteArray(), android.util.Base64.NO_WRAP
        )
    }.getOrNull()

    /**
     * **El croquis como página web**: un archivo que se abre en cualquier navegador, sin
     * internet y sin instalar nada. Ver [ExportarCroquisHtml].
     */
    /** El croquis como `.pixpin`: un proyecto de una hoja con él dentro. Ver [PaquetePixpin]. */
    private fun exportarElPaquete() {
        Croquis3DAlmacen.guardar(this, elCroquis, controlador.croquis)
        val ahora = System.currentTimeMillis()
        val suelto = com.forge.pixpin.motor.Proyecto(
            id = "suelto-$elCroquis", nombre = elCroquis, tocado = ahora,
            hojas = listOf(com.forge.pixpin.motor.Hoja(id = "hoja-$elCroquis", nombre = elCroquis, croquis = elCroquis)),
            croquis = listOf(elCroquis)
        )
        val carpeta = java.io.File(cacheDir, "share").apply { mkdirs() }
        val archivo = com.forge.pixpin.motor.PaquetePixpin.escribir(
            this, suelto,
            java.io.File(carpeta, "$elCroquis.${com.forge.pixpin.motor.PaquetePixpin.EXTENSION}"),
            croquisDe = { id -> Croquis3DAlmacen.jsonDe(this, id) }
        )
        if (archivo == null) {
            Toast.makeText(this, R.string.croquis_nada_que_exportar, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", archivo)
        startActivity(
            android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = com.forge.pixpin.motor.PaquetePixpin.MIME_TYPE
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.croquis_exportar_paquete)
            )
        )
    }

    /** Dónde está el lienzo dentro de la ventana, para la portada de la página exportada. */
    private var rectDelLienzo: androidx.compose.ui.geometry.Rect? = null

    /**
     * **La portada de la página web**: una foto del lienzo tal como se ve ahora, en JPEG y
     * a lo sumo [LADO_DE_LA_PORTADA] de lado, para que el archivo enseñe el croquis al
     * instante mientras el navegador compila el visor. Se saca con `PixelCopy` de la ventana,
     * recortada al lienzo; si falla, la página va sin portada, que no es grave.
     */
    private fun portadaDelLienzo(luego: (String?) -> Unit) {
        val r = rectDelLienzo
        if (r == null || r.width < 8 || r.height < 8) { luego(null); return }
        runCatching {
            val escala = (LADO_DE_LA_PORTADA / maxOf(r.width, r.height)).coerceAtMost(1f)
            val w = (r.width * escala).toInt().coerceAtLeast(1)
            val h = (r.height * escala).toInt().coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val zona = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
            android.view.PixelCopy.request(window, zona, bmp, { resultado ->
                if (resultado != android.view.PixelCopy.SUCCESS) { luego(null); return@request }
                val salida = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, CALIDAD_DE_LA_PORTADA, salida)
                bmp.recycle()
                luego("data:image/jpeg;base64," + android.util.Base64.encodeToString(salida.toByteArray(), android.util.Base64.NO_WRAP))
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { luego(null) }
    }

    private fun exportarLaPagina() {
        portadaDelLienzo { portada -> exportarLaPaginaCon(portada) }
    }

    private fun exportarLaPaginaCon(portada: String?) {
        // Las imágenes puestas en el espacio viajan dentro del archivo: sin eso, el croquis
        // exportado sale sin sus texturas. Ver [ExportarCroquisHtml.datos].
        val html = ExportarCroquisHtml.pagina(
            controlador.croquis, controlador.camara, elCroquis, ::imagenIncrustada, elPapelDeAhora, portada
        )
        if (html == null) {
            Toast.makeText(this, R.string.croquis_nada_que_exportar, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val carpeta = java.io.File(cacheDir, "share").apply { mkdirs() }
            val archivo = java.io.File(carpeta, "$elCroquis.html").also { it.writeText(html) }
            // **La página web se ofrece como archivo o como enlace.** Ver [CompartirEnlaceActivity].
            com.forge.pixpin.ui.CompartirEnlaceActivity.abrir(this, archivo, elCroquis)
            Toast.makeText(
                this,
                getString(R.string.croquis_pagina_exportada, html.length / 1024),
                Toast.LENGTH_SHORT
            ).show()
        }.onFailure {
            Toast.makeText(this, R.string.croquis_nada_que_exportar, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Escribe el OBJ y su MTL en la carpeta que publica el `FileProvider` y los ofrece
     * juntos: el MTL es lo que lleva los colores, y sin él muchos visores abren el modelo
     * en gris.
     */
    private fun exportarElModelo() {
        val salida = ExportarObj.escribir(controlador.croquis, elCroquis)
        if (salida == null) {
            Toast.makeText(this, R.string.croquis_nada_que_exportar, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val carpeta = java.io.File(cacheDir, "share").apply { mkdirs() }
            val obj = java.io.File(carpeta, "$elCroquis.obj").also { it.writeText(salida.obj) }
            val mtl = java.io.File(carpeta, "$elCroquis.mtl").also { it.writeText(salida.mtl) }
            val uris = arrayListOf(obj, mtl).map {
                androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
            }
            startActivity(
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "application/octet-stream"
                        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    getString(R.string.croquis_exportar_modelo)
                )
            )
            Toast.makeText(
                this,
                getString(R.string.croquis_modelo_exportado, salida.vertices, salida.caras),
                Toast.LENGTH_SHORT
            ).show()
        }.onFailure {
            Toast.makeText(this, R.string.croquis_nada_que_exportar, Toast.LENGTH_SHORT).show()
        }
    }

    /** El filete que separa dos tandas de una fila. El de pie, no el de la barra. */
    @Composable
    private fun FileteDePie() {
        Box(
            Modifier
                .padding(horizontal = 5.dp)
                .size(width = 1.dp, height = 22.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }

    /**
     * **La llave de paso de las luces**: se apagan, se miran y se vuelven a encender.
     *
     * Lo que alumbra en un croquis se decide trazo a trazo y se queda decidido, así que ver
     * la pieza sin sus luces pedía repintarlas una a una —y volver a subirlas después—. Aquí
     * es el mismo gesto que esconder una capa: se apaga, **lo apagado sigue estando ahí** con
     * su color y su grueso, y se enciende. Apagada, una tinta de luz es tinta lisa, que es
     * exactamente lo que es un tubo sin corriente.
     *
     * Y con la tira al lado, porque encender no es una postura sola: multiplica a lo que
     * lleve cada trazo, así que la mezcla que uno montó se sube y se baja entera en vez de
     * aplanarse. Ver [Luces3D].
     */
    /**
     * **La malla del suelo**: la retícula de referencia, puesta o quitada.
     *
     * Puesta es lo que dice cuánto mide todo: sus cuadros son fijos —siempre el mismo lado en
     * unidades del mundo, clavados en el origen— así que contar cuadros mide de verdad, y
     * acercarse no la subdivide. Pero enseñando un croquis acabado lo que importa es el
     * dibujo y no el papel milimetrado de debajo, así que se apaga. Los dos ejes se quedan
     * siempre: son el cero, y sin ellos no se sabe dónde está uno.
     *
     * El interruptor era el de la pauta de las hojas, que ya no existe —una hoja es la mesa y
     * una mesa con trama compite con lo dibujado—. Va guardado con el croquis. Ver
     * [Croquis.pautaDeLasHojas] y [pisosDeLaMalla].
     */
    @Composable
    private fun LaPautaDeLasHojas() {
        val puesta = controlador.croquis.pautaDeLasHojas
        Row(
            Modifier.padding(top = 4.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Alternable(
                encendido = puesta,
                icono = if (puesta) Icons.Filled.GridOn else Icons.Filled.GridOff,
                descripcion = getString(
                    if (puesta) R.string.croquis_pauta else R.string.croquis_pauta_sin
                )
            ) {
                controlador.ponerLaPauta(!puesta)
            }
            Text(
                getString(if (puesta) R.string.croquis_pauta else R.string.croquis_pauta_sin),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }

    /**
     * **La apertura del objetivo**: lo enfocado limpio y lo demás blando.
     *
     * Sirve para lo mismo que en una foto: **decir qué es lo importante**. Un croquis lleno se
     * lee mal porque todo grita igual; con el diafragma abierto, la pieza que se está
     * enseñando queda nítida y el resto se convierte en el sitio donde está.
     *
     * Dos cosas y en este orden: dónde se enfoca —lo elegido, o lo que se esté mirando— y
     * cuánto se abre. El botón primero porque abrir sin haber enfocado nada deja el croquis
     * blando sin saber por qué. Ver [Croquis.apertura].
     */
    @Composable
    private fun ElDiafragma() {
        val tinta = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            Modifier.padding(top = 8.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Alternable(
                encendido = controlador.croquis.enfoque != null,
                icono = Icons.Filled.CenterFocusWeak,
                descripcion = getString(R.string.croquis_enfocar)
            ) {
                controlador.enfocarDondeSeMira()
            }
            Text(
                getString(R.string.croquis_enfocar),
                style = MaterialTheme.typography.labelMedium,
                color = tinta.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        Tira(
            nombre = getString(R.string.croquis_apertura),
            colores = listOf(tinta.copy(alpha = 0.25f), MaterialTheme.colorScheme.primary),
            valor = controlador.croquis.apertura.toFloat(),
            alEmpezar = {}
        ) { cuanto -> controlador.abrirElDiafragma(cuanto.toDouble()) }

        // **Y el pixelado**, aquí al lado por lo mismo: no es del dibujo, es de cómo se
        // mira. Ver [Croquis.pixelado].
        Tira(
            nombre = getString(R.string.croquis_pixelado),
            colores = listOf(tinta.copy(alpha = 0.25f), MaterialTheme.colorScheme.primary),
            valor = controlador.croquis.pixelado.toFloat(),
            alEmpezar = {}
        ) { cuanto -> controlador.pixelar(cuanto.toDouble()) }
    }

    @Composable
    private fun LasLuces() {
        val luces = controlador.croquis.luces
        val tinta = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            Modifier.padding(top = 4.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Alternable(
                encendido = luces.encendidas,
                icono = if (luces.encendidas) Icons.Filled.Lightbulb else Icons.Filled.LightbulbCircle,
                descripcion = getString(
                    if (luces.encendidas) R.string.croquis_luces else R.string.croquis_luces_apagadas
                )
            ) {
                // Al encenderlas con la fuerza a cero volverían apagadas y el interruptor
                // parecería roto: se les devuelve la de fábrica.
                controlador.ponerLasLuces(
                    luces.copy(
                        encendidas = !luces.encendidas,
                        fuerza = if (luces.fuerza <= 0.02) 1.0 else luces.fuerza
                    )
                )
            }
            Text(
                getString(
                    if (luces.encendidas) R.string.croquis_luces else R.string.croquis_luces_apagadas
                ),
                style = MaterialTheme.typography.labelMedium,
                color = tinta.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        if (!luces.encendidas) return
        Tira(
            nombre = getString(R.string.croquis_luces_fuerza),
            colores = listOf(tinta.copy(alpha = 0.25f), Color(0xFFFFC857), Color.White),
            valor = (luces.fuerza / 2.0).toFloat(),
            alEmpezar = {}
        ) { cuanto ->
            val ahora = controlador.croquis.luces
            controlador.ponerLasLuces(ahora.copy(fuerza = (cuanto * 2.0).toDouble()))
        }
    }

    /**
     * **El sol, en una esfera**: se arrastra y la sombra va detrás.
     *
     * Eran dos tiras —una para el rumbo y otra para la altura— y no funcionaba por dos
     * razones. Una, que un sol no es dos números: es un sitio del cielo, y buscarlo con dos
     * deslizadores es hacer a mano lo que el ojo hace solo. Y otra, que cada tira llevaba
     * dentro una copia de dónde estaba el sol cuando se dibujó, así que mover la segunda
     * devolvía la primera a donde estaba —el fallo de «solo se mueve el primero»—.
     *
     * Ahora es una bóveda vista desde arriba: **el borde es el horizonte y el centro es el
     * mediodía**. Se arrastra el sol por donde se quiera y las sombras van detrás en el
     * mismo gesto, que es la única forma de encontrar la que uno buscaba.
     */
    @Composable
    private fun ElAstro(sol: Sol3D?, deNoche: Boolean) {
        val realce = MaterialTheme.colorScheme.primary
        val tinta = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            Modifier.padding(top = 8.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Alternable(
                encendido = sol != null,
                icono = if (deNoche) Icons.Filled.DarkMode else Icons.Filled.WbSunny,
                descripcion = getString(if (deNoche) R.string.croquis_luna else R.string.croquis_sol)
            ) {
                // La luna nace baja: es lo que hace una luna, y una luna en el cenit no se
                // distingue de un sol pálido.
                val nuevo = if (sol == null) Sol3D(altura = if (deNoche) 0.35 else Math.PI / 4) else null
                if (deNoche) controlador.ponerLaLuna(nuevo) else controlador.ponerElSol(nuevo)
            }
            Text(
                getString(
                    when {
                        deNoche && sol == null -> R.string.croquis_luna_apagada
                        deNoche -> R.string.croquis_luna
                        sol == null -> R.string.croquis_sol_apagado
                        else -> R.string.croquis_sol
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = tinta.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        if (sol == null) return

        Canvas(
            Modifier
                .padding(top = 6.dp, start = 4.dp)
                .size(LA_BOVEDA.dp)
                .pointerInput(Unit) {
                    fun mover(donde: Offset) {
                        val medio = Offset(size.width / 2f, size.height / 2f)
                        val d = donde - medio
                        val radio = minOf(size.width, size.height) / 2f
                        // El rumbo es el ángulo y la altura es lo cerca del centro que se
                        // suelte: el centro es el mediodía y el borde, el horizonte.
                        val fuera = (d.getDistance() / radio).coerceIn(0f, 1f)
                        // **Se lee el sol de ahora, no el de cuando se dibujó el mando.**
                        val ahora =
                            (if (deNoche) controlador.croquis.luna else controlador.croquis.sol)
                                ?: Sol3D()
                        val puesto = ahora.copy(
                            azimut = kotlin.math.atan2(d.x.toDouble(), -d.y.toDouble()),
                            altura = (1.0 - fuera) * Math.PI / 2
                        )
                        if (deNoche) controlador.ponerLaLuna(puesto)
                        else controlador.ponerElSol(puesto)
                    }
                    awaitEachGesture {
                        val abajo = awaitFirstDown()
                        mover(abajo.position)
                        abajo.consume()
                        while (true) {
                            val evento = awaitPointerEvent()
                            val dedo = evento.changes.firstOrNull { it.pressed } ?: break
                            mover(dedo.position)
                            dedo.consume()
                        }
                    }
                }
        ) {
            val medio = Offset(size.width / 2f, size.height / 2f)
            val radio = size.minDimension / 2f
            drawCircle(tinta.copy(alpha = 0.10f), radius = radio, center = medio)
            drawCircle(
                tinta.copy(alpha = 0.35f), radius = radio, center = medio,
                style = Stroke(width = 1.5f)
            )
            drawCircle(tinta.copy(alpha = 0.18f), radius = radio / 2, center = medio, style = Stroke(width = 1f))
            drawLine(tinta.copy(alpha = 0.18f), Offset(medio.x, medio.y - radio), Offset(medio.x, medio.y + radio), 1f)
            drawLine(tinta.copy(alpha = 0.18f), Offset(medio.x - radio, medio.y), Offset(medio.x + radio, medio.y), 1f)

            val fuera = (1.0 - sol.altura / (Math.PI / 2)).coerceIn(0.0, 1.0)
            val donde = Offset(
                medio.x + (kotlin.math.sin(sol.azimut) * fuera * radio).toFloat(),
                medio.y - (kotlin.math.cos(sol.azimut) * fuera * radio).toFloat()
            )
            // El rayo desde el sol hasta el centro: es por donde van a caer las sombras.
            drawLine(realce.copy(alpha = 0.5f), donde, medio, 2f)
            drawCircle(realce, radius = LA_BOLA_DEL_SOL, center = donde)
        }
    }

    /** Lo que mide la bóveda del sol, en dp, y su bolita, en píxeles. */
    private val LA_BOVEDA = 108
    private val LA_BOLA_DEL_SOL = 9f

    /** Uno de los botones redondos de encima de la barra. */
    @Composable
    private fun BotonDeSalir(
        glifo: String,
        tamano: androidx.compose.ui.unit.TextUnit,
        alTocar: () -> Unit
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                .clickable(onClick = alTocar),
            contentAlignment = Alignment.Center
        ) {
            Text(glifo, fontSize = tamano, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    /** Una caja de la barra: el fondo, la sombra y el redondeo, iguales para las dos. */
    @Composable
    private fun Caja(dentro: @Composable () -> Unit) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 3.dp
        ) {
            Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) { dentro() }
        }
    }

    /**
     * **Cada tinta, enseñando lo que pinta.**
     *
     * Un `●`, un `■` y un `▭` no dicen nada: son tres símbolos que hay que aprenderse. El
     * botón pinta **una muestra de su propia tinta**, con el color que hay puesto, así que
     * se reconoce de un vistazo y de paso dice de qué color se está dibujando.
     */
    @Composable
    private fun Punta(cual: Pincel) {
        val puesta = controlador.pincel == cual
        val tinta = Color(parseColor(controlador.color, 255))
        Box(
            Modifier
                .padding(vertical = 2.dp)
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (puesta) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else Color.Transparent
                )
                .clickable {
                    controlador.pincel = cual
                    // Elegir una tinta **es** ponerse a dibujar con ella: nadie coge el
                    // rotulador para no dibujar.
                    controlador.herramienta = Herramienta3D.LAPIZ
                    controlador.empincelarLaSeleccion(cual)
                },
            contentAlignment = Alignment.Center
        ) {
            MuestraDeLaPunta(cual)
        }
    }

    /**
     * Lo que pinta una punta, dibujado. Lo usan el botón de la barra y el taller.
     *
     * **Con su material puesto.** La forma la dice la punta y de qué está hecha lo dice el
     * material, así que una muestra que enseñe solo la forma se queda a medias: con la luz
     * puesta, las cuatro puntas salen encendidas, que es lo que va a pasar al trazar. Ver
     * [MaterialDeLaTinta].
     */
    @Composable
    private fun MuestraDeLaPunta(cual: Pincel, lado: androidx.compose.ui.unit.Dp = 26.dp) {
        val tinta = Color(parseColor(controlador.color, 255))
        val material = MaterialDeLaTinta.de(controlador.punta)
        Canvas(Modifier.size(lado)) {
                val izquierda = Offset(0f, size.height * 0.72f)
                val derecha = Offset(size.width, size.height * 0.28f)
                when (cual) {
                    Pincel.CUADRADO -> drawLine(
                        tinta, izquierda, derecha,
                        strokeWidth = size.height * 0.30f, cap = StrokeCap.Square
                    )
                    // El rodillo: una banda ancha, **maciza** y cortada a ras. Iba
                    // translúcida de cuando era un marcador de subrayar, y una muestra que
                    // enseña un velo cuando lo que sale es pintura miente sobre la tinta.
                    Pincel.RESALTADOR -> drawLine(
                        tinta, izquierda, derecha,
                        strokeWidth = size.height * 0.62f, cap = StrokeCap.Butt
                    )
                    // La cuchilla: fina por arriba y con su hondura colgando, que es lo que
                    // hace y lo que la distingue de un trazo normal de un vistazo.
                    Pincel.CUCHILLA -> {
                        val camino = androidx.compose.ui.graphics.Path()
                        camino.moveTo(izquierda.x, izquierda.y)
                        camino.lineTo(derecha.x, derecha.y)
                        camino.lineTo(derecha.x, derecha.y + size.height * 0.34f)
                        camino.lineTo(izquierda.x, izquierda.y + size.height * 0.34f)
                        camino.close()
                        drawPath(camino, tinta.copy(alpha = 0.55f))
                        drawLine(tinta, izquierda, derecha, strokeWidth = size.height * 0.10f)
                    }
                    Pincel.LUZ -> {
                        drawLine(
                            tinta.copy(alpha = 0.35f), izquierda, derecha,
                            strokeWidth = size.height * 0.62f, cap = StrokeCap.Round
                        )
                        drawLine(
                            Color.White, izquierda, derecha,
                            strokeWidth = size.height * 0.14f, cap = StrokeCap.Round
                        )
                    }
                    else -> drawLine(
                        tinta, izquierda, derecha,
                        strokeWidth = size.height * 0.30f, cap = StrokeCap.Round
                    )
                }

                // **Y el material, encima de la forma y no en su lugar.**
                //
                // Estaba antes del `when` y con su propio dibujo, así que con la luz o una
                // trama puestas **la muestra salía igual para las cuatro puntas**: se cambiaba
                // de lápiz y el icono no se movía. La forma la dice la punta y el material la
                // pinta por encima; las dos cosas a la vez, que es lo que va a salir.
                val gordo = size.height * when (cual.vigente) {
                    Pincel.RESALTADOR -> 0.62f
                    Pincel.CUCHILLA -> 0.12f
                    else -> 0.30f
                }
                when {
                    material == MaterialDeLaTinta.LUZ -> {
                        drawLine(
                            tinta.copy(alpha = 0.30f), izquierda, derecha,
                            strokeWidth = gordo * 1.9f, cap = StrokeCap.Round
                        )
                        drawLine(
                            Color.White, izquierda, derecha,
                            strokeWidth = gordo * 0.38f, cap = StrokeCap.Round
                        )
                    }

                    material.trama.hayQuePintarla -> {
                        val grano = Color(tinta.red * 0.55f, tinta.green * 0.55f, tinta.blue * 0.55f)
                        var cuanto = 0.14f
                        while (cuanto < 1f) {
                            val x = izquierda.x + (derecha.x - izquierda.x) * cuanto
                            val y = izquierda.y + (derecha.y - izquierda.y) * cuanto
                            if (material == MaterialDeLaTinta.PUNTOS) {
                                drawCircle(grano, radius = gordo * 0.18f, center = Offset(x, y))
                            } else {
                                drawLine(
                                    grano,
                                    Offset(x, y - gordo * 0.45f),
                                    Offset(x, y + gordo * 0.45f),
                                    strokeWidth = gordo * 0.18f
                                )
                            }
                            cuanto += 0.22f
                        }
                        if (material == MaterialDeLaTinta.CRUZADO) {
                            drawLine(
                                grano, izquierda, derecha,
                                strokeWidth = gordo * 0.16f, cap = StrokeCap.Butt
                            )
                        }
                    }
                }
        }
    }

    /**
     * **Las herramientas, en su propia caja debajo de la barra de dibujar.**
     *
     * ## Por qué bajan aquí desde los ajustes
     *
     * Estaban en la pestaña de dibujar, detrás del botón de ajustes. Y ahí fallan por lo
     * mismo que fallaba el color antes de traerlo: **poner un plano es parte de dibujar**, no
     * una configuración. En un croquis en el espacio uno pone la hoja, traza cuatro rayas,
     * gira, pone otra hoja y sigue — eso son tres toques de ida y tres de vuelta cada vez, y
     * con el croquis medio tapado por un panel mientras tanto. Aquí están donde ya está la
     * mano, y se cambia de herramienta sin apartar la vista.
     *
     * ## En caja aparte, y no colgando de la barra
     *
     * La de arriba dice **con qué** se dibuja —la punta, el color, el grueso, lo que tapa, el
     * pulso— y esta, **qué** se está haciendo. Son dos preguntas distintas y se contestan en
     * momentos distintos: la de arriba se toca a cada trazo y esta, cada tantos. Separadas
     * por su hueco, la mano da con cada una por el sitio antes que por el icono.
     *
     * ## Y en dos columnas
     *
     * Siete botones en fila serían más alto que la pantalla con la barra de dibujar encima.
     * En dos columnas ocupan la mitad y siguen leyéndose por tandas: arriba, **sobre qué se
     * dibuja** —los tres planos y el eje del espejo, que son las cuatro formas de poner la
     * superficie— y abajo, **con qué** —el lápiz, el borrador y la flecha—.
     */
    @Composable
    private fun LasHerramientas(modifier: Modifier = Modifier) {
        Surface(
            modifier,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            tonalElevation = 3.dp
        ) {
            Column(
                Modifier.padding(vertical = 5.dp, horizontal = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // **Sobre qué se dibuja**: las cuatro que ponen superficie.
                Row {
                    Herramienta(
                        Herramienta3D.LAMINA, Icons.Filled.CropLandscape,
                        R.string.croquis_lamina, LADO_EN_EL_LATERAL
                    )
                    // La bola, al lado de la hoja: son la misma cosa —dónde se apoya el
                    // lápiz—.
                    Herramienta(
                        Herramienta3D.ESFERA, Icons.Filled.Circle,
                        R.string.croquis_esfera, LADO_EN_EL_LATERAL
                    )
                }
                Row {
                    // **El plano compuesto, y el botón dice por dónde va el gesto.**
                    //
                    // Es la única herramienta de dos tiempos que hay, así que sin decirlo no
                    // se sabe si la siguiente raya crea un plano o dobla el que hay. Con la
                    // primera pendiente enseña una curva —«traza»— y esperando la segunda,
                    // una quebrada —«ahora la otra»—. Ver
                    // [Croquis3DControlador.esperandoLaSegundaLinea].
                    val aMedias = controlador.esperandoLaSegundaLinea
                    Herramienta(
                        Herramienta3D.PLANO_COMPUESTO,
                        if (aMedias) Icons.Filled.Timeline else Icons.Filled.Waves,
                        if (aMedias) R.string.croquis_plano_compuesto_sigue
                        else R.string.croquis_plano_compuesto,
                        LADO_EN_EL_LATERAL
                    )
                    Herramienta(
                        Herramienta3D.ESPEJO, Icons.Filled.Flip,
                        R.string.croquis_espejo_eje, LADO_EN_EL_LATERAL
                    )
                }

                // **Qué cuerpo pone el compás**, y solo con la bola en la mano: los cuatro
                // se ponen con el mismo gesto, así que basta con decir cuál sale.
                if (controlador.herramienta == Herramienta3D.ESFERA) {
                    Row {
                        for (forma in FormaDeBola.entries) {
                            Alternable(
                                encendido = controlador.formaDeBola == forma,
                                icono = when (forma) {
                                    FormaDeBola.BOLA -> Icons.Filled.Circle
                                    FormaDeBola.CILINDRO -> Icons.Filled.CropPortrait
                                    FormaDeBola.CONO -> Icons.Filled.ChangeHistory
                                    FormaDeBola.ANILLO -> Icons.Filled.DonutLarge
                                },
                                descripcion = getString(
                                    when (forma) {
                                        FormaDeBola.BOLA -> R.string.croquis_forma_bola
                                        FormaDeBola.CILINDRO -> R.string.croquis_forma_cilindro
                                        FormaDeBola.CONO -> R.string.croquis_forma_cono
                                        FormaDeBola.ANILLO -> R.string.croquis_forma_anillo
                                    }
                                ),
                                lado = LADO_EN_EL_LATERAL * 0.8f
                            ) { controlador.formaDeBola = forma }
                        }
                    }
                }

                Filete()

                // **Con qué se dibuja**: las tres que tocan lo dibujado.
                Row {
                    Herramienta(
                        Herramienta3D.LAPIZ, Icons.Filled.Edit,
                        R.string.croquis_lapiz, LADO_EN_EL_LATERAL
                    )
                    Herramienta(
                        Herramienta3D.BORRADOR, Icons.Filled.CleaningServices,
                        R.string.croquis_borrador, LADO_EN_EL_LATERAL
                    )
                }
                Row {
                    Herramienta(
                        Herramienta3D.SELECCION, Icons.Filled.HighlightAlt,
                        R.string.croquis_seleccion, LADO_EN_EL_LATERAL
                    )
                    // Licuar, al lado de seleccionar: las dos tocan lo ya dibujado.
                    if (controlador.herramienta != Herramienta3D.BORRADOR) {
                        Herramienta(
                            Herramienta3D.LICUAR, Icons.Filled.BlurOn,
                            R.string.croquis_licuar, LADO_EN_EL_LATERAL
                        )
                    }
                    // De qué clase es el borrador: fino se lleva **lo que toca**; entero, la
                    // raya que roza. Ocupa el hueco que deja la fila, y solo con el borrador
                    // cogido: con el lápiz no significa nada. Ver
                    // [Croquis3DControlador.borradorFino].
                    if (controlador.herramienta == Herramienta3D.BORRADOR) {
                        Alternable(
                            encendido = controlador.borradorFino,
                            icono =
                                if (controlador.borradorFino) Icons.Filled.Gesture
                                else Icons.Filled.DeleteSweep,
                            descripcion = getString(
                                if (controlador.borradorFino) R.string.croquis_borrador_fino
                                else R.string.croquis_borrador_entero
                            ),
                            lado = LADO_EN_EL_LATERAL
                        ) {
                            controlador.borradorFino = !controlador.borradorFino
                        }
                    } else {
                        Spacer(Modifier.size(LADO_EN_EL_LATERAL + 4.dp))
                    }
                }
            }
        }
    }

    /**
     * **Una herramienta, con su dibujo y no con un símbolo.**
     *
     * Estaban puestas con caracteres —`▱`, `⇄`, `⬚`, `⌫`— y eso obliga a descifrar: son
     * figuras que no significan nada hasta que alguien las explica, y encima cada aparato
     * las dibuja a su manera. Con el icono de verdad, plano es un rectángulo tumbado,
     * espejo es una figura y su reflejo y elegir es un marco de puntos.
     */
    @Composable
    private fun Herramienta(
        cual: Herramienta3D,
        icono: androidx.compose.ui.graphics.vector.ImageVector,
        nombre: Int,
        /** Lo que mide. En el lateral van más apretadas que en un panel: hay menos sitio. */
        lado: androidx.compose.ui.unit.Dp = 42.dp
    ) {
        val puesta = controlador.herramienta == cual
        Box(
            Modifier
                .padding(2.dp)
                .size(lado)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (puesta) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else Color.Transparent
                )
                .clickable {
                    controlador.herramienta = cual
                    // Salir de la flecha suelta lo elegido: dejarlo marcado mientras se
                    // dibuja otra cosa hace creer que el color nuevo va a ir ahí.
                    if (cual != Herramienta3D.SELECCION) controlador.soltarLaSeleccion()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icono,
                contentDescription = getString(nombre),
                tint = if (puesta) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }

    /**
     * Un interruptor de la barra: encendido va del color de la aplicación.
     *
     * Se usa también para las acciones que no son un estado —agrupar, esconder, borrar— con
     * el encendido a la fuerza: son botones que solo salen cuando se pueden usar, así que
     * verlos encendidos es verdad mientras están.
     */
    @Composable
    private fun Alternable(
        encendido: Boolean,
        icono: androidx.compose.ui.graphics.vector.ImageVector,
        descripcion: String,
        /** Lo que mide. Ver [Herramienta]. */
        lado: androidx.compose.ui.unit.Dp = 42.dp,
        alTocar: () -> Unit
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .size(lado)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (encendido) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else Color.Transparent
                )
                .clickable(onClick = alTocar),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icono,
                contentDescription = descripcion,
                tint = if (encendido) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }

    /**
     * Un candado: qué clava y si está echado.
     *
     * **Echado y suelto tienen que verse distintos de un vistazo.** Los dos candados de
     * Material se parecen demasiado a este tamaño, así que lo que separa un estado del otro
     * no es el dibujo: es que el echado va sobre una pastilla del color de la aplicación y
     * el suelto se queda medio apagado. Un candado echado sin querer es peor que no tener
     * candado.
     */
    @Composable
    private fun Candado(glifo: String, echado: Boolean, descripcion: String, alTocar: () -> Unit) {
        val color =
            if (echado) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (echado) MaterialTheme.colorScheme.primary else Color.Transparent
                )
                .clickable(onClick = alTocar)
                .padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(glifo, fontSize = 13.sp, color = color)
            Icon(
                if (echado) Icons.Filled.Lock else Icons.Filled.LockOpen,
                descripcion,
                tint = color,
                modifier = Modifier.padding(start = 2.dp).size(17.dp)
            )
        }
    }

    @Composable
    private fun Separador() {
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .size(width = 1.dp, height = 22.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }

    /** Cuántos píxeles de arrastre van de hoja invisible a hoja bien marcada. */
    private val RECORRIDO_DE_LA_HOJA = 260f

/** Y lo que hay que arrastrar para llevar la luz de apagada a a tope. */
private val RECORRIDO_DE_LA_LUZ = 180f

    /**
     * Lo que mide una herramienta en el lateral, y el hueco que se le reserva a deshacer.
     *
     * Más apretadas que en un panel —treinta y seis y no cuarenta y dos— porque en el lateral
     * van dos columnas y cuatro filas, y con el tamaño de panel la caja se comía media
     * pantalla. Ver [LasHerramientas].
     */
    private val LADO_EN_EL_LATERAL = 36.dp
    private val HUECO_DEL_DESHACER = 96.dp

    /** Lo ancho que se deja el panel de ajustes, en dp: el ancho de una mano. */
    private val ANCHO_DE_LOS_AJUSTES = 340

    /** Y lo ancho que se deja el taller del color, que solo lleva la rueda y el material. */
    private val ANCHO_DEL_COLOR = 300

    /**
     * Lo que se levanta el mando de lo elegido para no montarse sobre la barra de abajo.
     *
     * A ojo no: es lo que mide la barra más un dedo de aire. Ver [Croquis3DMando].
     */
    /** El aire que se deja entre el mando de la esquina y la barra de abajo. */
    private val AIRE_SOBRE_LA_BARRA = 8.dp

    /**
     * Lo que mide un mando de la barra de dibujar.
     *
     * Es el sitio donde se posa el lápiz, no el recorrido: el recorrido es la pantalla
     * entera. Ver [MandoQueSeArrastra].
     */
    private val LADO_DEL_MANDO = 38.dp


    /** Cuánto recorrido cuesta pasar de una punta a la siguiente, en dp. */
    private val PASO_DE_LA_PUNTA = 30f

    /**
     * Cuántas veces engorda el trazo por cada dp de arrastre.
     *
     * Sale de repartir el grosor entero —de [GROSOR_MINIMO] a [GROSOR_MAXIMO], que son
     * cuarenta veces— por [RECORRIDO_DEL_MANDO], **por veces y no a partes iguales**. Ver
     * [ElGrosor].
     */
    private val VECES_POR_DP = 1.0223


    /**
     * Cuánto se aparta del mando el centro de la rueda.
     *
     * Su radio más el del propio mando: pegada, el dedo taparía el trozo de rueda que hay
     * entre los dos, que es justo por donde se sale al empezar el gesto. **Y es parte del
     * gesto**, no solo de la pintura: el dedo se lleva hasta aquí y desde ahí manda dónde
     * está dentro de la rueda. Ver [ElColorQueHay].
     */
    private val APARTADO_DE_LA_RUEDA = RADIO_DE_LA_RUEDA + 34f


    /** Lo menos que se deja tapar: a cero no se pinta nada y parece que se ha roto algo. */
    private val LO_MENOS_QUE_TAPA = 0.06

    /** Hasta dónde llega el mando del pulso: en uno responde como la mano, arriba exagera. */
    private val PULSO_MAXIMO = 2.0


    /** Cuánto se queda el chivato después de soltar, en milisegundos. */
    private val LO_QUE_DURA_EL_CHIVATO = 900L

    /**
     * Lo más grande que se deja la muestra del chivato, en dp.
     *
     * Un trazo a tope y de cerca se sale de cualquier pantalla, así que en algún sitio hay que
     * pararlo — pero muy por encima de lo que cabía en el botón, que es de lo que se trata: a
     * partir de aquí, lo que dice cuánto es, es el número. Ver [LoQueVaASalir].
     */
    private val LO_QUE_CABE_EN_EL_CHIVATO = 96

    /** Quién es cada mando de la barra, para que el número salga dentro del suyo. */
    private val ES_LA_PUNTA = "punta"
    private val ES_EL_GROSOR = "grosor"
    private val ES_LO_QUE_TAPA = "tapa"
    private val ES_EL_PULSO = "pulso"

    /** Hasta dónde llega el grosor que se puede elegir con la tira, en dp. */
    private val GROSOR_MINIMO = 1.5f

    /**
     * Lo más gordo que se puede elegir.
     *
     * Estaba en dieciséis, que es un rotulador grueso y **el techo de dibujar una raya**. En
     * el espacio se hace más que rayas: se marcan zonas, se pintan franjas y se da color a
     * una cara entera, y para eso dieciséis se queda corto siempre —sobre todo con el
     * resaltador, que además multiplica—. Sesenta y cuatro deja el tope donde ya no estorba,
     * y el recorrido del selector no se resiente porque lo que se elige a diario está abajo.
     */
    private val GROSOR_MAXIMO = 64f

    /** Los paneles que se abren sobre la barra. Uno cada vez. */
    /**
     * Las pestañas de los ajustes: **por lo que se va a hacer, no por lo que es**.
     *
     * Estaban todas las funciones en dos filas de iconos y había que acordarse de cuál era
     * cuál. En pestañas con su nombre escrito, buscar es leer cuatro palabras: qué se dibuja,
     * con qué punta, de qué tinta, cómo es el sitio y qué hay montado.
     */
    /** Qué archivo es este croquis y cuánto pesa, y lo mismo de su proyecto. Ver [com.forge.pixpin.motor.Detalle]. */
    @Composable
    private fun ElDetalle() {
        val detalle by androidx.compose.runtime.produceState(emptyList<Pair<String, String>>(), controlador.croquis) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val archivo = com.forge.pixpin.motor.Detalle.delCroquis(this@Croquis3DActivity, elCroquis)
                val proyecto = elProyecto?.let { (application as? com.forge.pixpin.PixPinApp)?.proyectos?.porId(it) }
                val filas = ArrayList<Pair<String, String>>()
                filas += getString(R.string.detalle_archivo) to archivo.nombre
                filas += getString(R.string.detalle_peso) to com.forge.pixpin.motor.Detalle.legible(archivo.bytes)
                if (proyecto != null) {
                    val p = com.forge.pixpin.motor.Detalle.delProyecto(this@Croquis3DActivity, proyecto)
                    filas += getString(R.string.detalle_proyecto) to p.nombre
                    filas += getString(R.string.detalle_hojas) to "${p.hojas}"
                    filas += getString(R.string.detalle_peso_proyecto) to com.forge.pixpin.motor.Detalle.legible(p.bytes)
                } else filas += getString(R.string.detalle_proyecto) to getString(R.string.detalle_sin_proyecto)
                filas
            }
        }
        Column(Modifier.padding(8.dp)) {
            detalle.forEach { (que, cuanto) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(que, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(cuanto, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private enum class PestanaDelCroquis(val nombre: Int) {
        DIBUJO(R.string.croquis_pes_dibujo),
        PUNTA(R.string.croquis_pes_punta),
        ESCENA(R.string.croquis_pes_escena),
        PIEZAS(R.string.croquis_pes_piezas),
        DETALLE(R.string.croquis_pes_detalle)
    }

    companion object {
        /**
         * Cuánto brillo de más se le pide a la pantalla, en veces el blanco de la interfaz.
         *
         * Se pide de sobra a propósito: el sistema recorta a lo que el panel dé y a lo que
         * el brillo de ese momento permita, así que pedir poco es quedarse corto en un panel
         * bueno y pedir mucho no rompe nada en uno malo. Ver [ajustarElBrilloDeMas].
         */
        private const val HOLGURA_DE_BRILLO = 4f

        /**
         * Lo que tarda la vista en encajar, en milisegundos.
         *
         * Doscientos ochenta: por debajo de doscientos el giro se lee como un salto —el ojo
         * no llega a seguirlo y hay que volver a buscar dónde está todo— y por encima de
         * cuatrocientos se hace de esperar, que en un gesto que uno repite cada poco es
         * peor que el salto.
         */
        private const val LO_QUE_TARDA_EN_ENCAJAR = 280

        /** De qué proyecto es este croquis, y cuál de sus croquis es. */
        private const val EL_PROYECTO = "proyecto"
        private const val EL_CROQUIS = "croquis"
        private const val LA_VISTA = "vista"

        /**
         * Abre un croquis en el espacio.
         *
         * Sin nada, el de siempre: el cuaderno suelto que se abre desde la bola. Con
         * proyecto y croquis, **ese** croquis de **ese** proyecto —un proyecto puede tener
         * varios, como tiene varios lienzos— y entonces cada vista que se congele entra en
         * él como una hoja. Ver [HojaDeLaVista].
         */
        fun abrir(
            context: Context,
            proyecto: String? = null,
            croquis: String? = null,
            /** Si se viene de la zona de proyectos: cerrar tiene que devolver ahí. */
            desdeProyectos: Boolean = false,
            /** En qué vista abrirlo, si se viene de una de sus láminas. */
            vista: String? = null
        ) {
            context.startActivity(
                Intent(context, Croquis3DActivity::class.java)
                    // Cada croquis en su tarea; uno nuevo, en una nueva. Ver el manifiesto.
                    .setData(android.net.Uri.parse("pixpin://croquis/" + android.net.Uri.encode(croquis ?: "nuevo-${System.currentTimeMillis()}")))
                    .putExtra(EL_PROYECTO, proyecto)
                    // De dónde se vino, para volver ahí al cerrar. Ver [EXTRA_DESDE_PROYECTO].
                    .also { if (desdeProyectos) it.putExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO, proyecto ?: "") }
                    .putExtra(EL_CROQUIS, croquis)
                    .putExtra(LA_VISTA, vista)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * Lo más grande que se mete una textura en la página exportada, y con qué calidad.
 *
 * Mil pixeles por lado sobran para una foto de referencia mirada desde cualquier ángulo, y
 * en cambio la diferencia de peso entre eso y la foto original —que puede venir de doce
 * megapíxeles— es de dos órdenes de magnitud. Lo que se comparte tiene que abrirse deprisa.
 */
private const val MAX_LADO_DE_LA_TEXTURA = 1024
/** La portada de la página exportada: de lado, y su calidad JPEG. Unos 60-120 KB. */
private const val LADO_DE_LA_PORTADA = 900f
private const val CALIDAD_DE_LA_PORTADA = 62
private const val CALIDAD_DE_LA_TEXTURA = 82
