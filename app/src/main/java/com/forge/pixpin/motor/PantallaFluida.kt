package com.forge.pixpin.motor

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import android.view.WindowManager
import kotlin.math.abs

/**
 * **Pedirle a la pantalla que vaya deprisa.**
 *
 * Un teléfono de 120 Hz no va a 120 Hz porque sí. Android arranca cada ventana a la tasa
 * «segura» —60 Hz en la mayoría— y solo sube si la aplicación lo pide, poniendo
 * `WindowManager.LayoutParams.preferredRefreshRate`. PixPin no lo pedía en ninguna
 * ventana, así que el dedo iba al doble de velocidad que la raya que dejaba detrás: eso es
 * exactamente lo que se nota al comparar con las aplicaciones de dibujo del fabricante,
 * que sí lo piden.
 *
 * La técnica está mirada en Telegram (12.10.1, GPL-2; aquí está **reescrita**, no
 * copiada):
 *
 * - `AndroidUtilities.java:2822` — `setPreferredMaxRefreshRate(Window, float)`: pone
 *   `preferredRefreshRate` en los atributos de la ventana y llama a
 *   `wm.updateViewLayout(window.getDecorView(), params)`, tragándose la excepción.
 * - `AndroidUtilities.java:2835` — la variante para ventanas sin `Window` (las flotantes):
 *   se toca el `LayoutParams` a pelo y solo se rehace el layout si la vista está pegada.
 * - `AndroidUtilities.java:2770` — la tasa máxima sale de recorrer
 *   `Display.getSupportedRefreshRates()` y quedarse con la mayor.
 *
 * ## Y por qué no basta con pedir la máxima
 *
 * A 120 Hz hay 8,3 ms para dibujar un fotograma en vez de 16,6. Si una pantalla no llega
 * —un croquis 3D pesado, un plano con miles de líneas—, correr a 120 Hz no la hace más
 * fluida: la hace **peor**, porque falla la mitad de los plazos en vez de acertarlos todos
 * a 60. Por eso hay un vigilante que mide el coste real de cada fotograma y baja a 60 Hz
 * cuando no da, con histéresis para que no vaya y venga. La política —la parte que
 * decide— vive en [PoliticaDeFluidez], **sin una sola línea de Android**, para poder
 * probarla en la JVM; aquí abajo queda solo la fontanería.
 *
 * Todo esto se puede apagar desde Ajustes → Aspecto → «Máxima fluidez»
 * ([com.forge.pixpin.data.Settings.maximaFluidez]), encendido de fábrica.
 *
 * ## Aquí solo hay fontanería
 *
 * Qué tasa da esta pantalla y cómo se le pide a una ventana. Lo que mide la pantalla se
 * guarda una vez y se reutiliza: recorrer los modos del display cuesta un salto al
 * sistema y esto se llama al abrir cada pin flotante.
 */
object PantallaFluida {

    /** La mayor que anuncia la pantalla; 0 mientras no se haya mirado. */
    @Volatile
    private var tasaMaxima = 0f

    /** Y la que hace de «60»: casi nunca es 60,0 exacto, suele ser 59,94. */
    @Volatile
    private var tasaDeSesenta = 60f

    /**
     * El hilo donde se cuentan los fotogramas.
     *
     * Uno solo para toda la aplicación, y **no el principal**: la cuenta se hace una vez
     * por fotograma y el hilo principal es justo el que no debe tener trabajo de más.
     * Telegram lo hace en el principal (`RefreshRateController.java:127`); aquí no, porque
     * la norma de la casa es que la fluidez manda. Solo se salta al principal cuando hay
     * un cambio de verdad que aplicar, que son unas pocas veces por sesión.
     */
    private val elCronometro: Handler by lazy {
        Handler(HandlerThread("pixpin-fotogramas").apply { start() }.looper)
    }

    private val laManoPrincipal by lazy { Handler(Looper.getMainLooper()) }

    /** ¿Tiene sentido vigilar? En una pantalla de 60 Hz no hay nada entre lo que elegir. */
    fun hayEntreQueElegir(): Boolean = tasaMaxima > tasaDeSesenta + 0.2f

    /** La tasa en hercios que corresponde a cada opción. */
    private fun hercios(cual: TasaDePantalla): Float =
        if (cual == TasaDePantalla.MAXIMA) tasaMaxima else tasaDeSesenta

    /**
     * Mira una vez qué tasas admite la pantalla.
     *
     * `getSupportedRefreshRates` está marcada como obsoleta desde API 23 en favor de
     * `getSupportedModes`, pero para esto da la misma lista y sin objetos intermedios; es
     * también la que usa Telegram (`AndroidUtilities.java:2770`). Si algo falla se queda
     * en 60 y todo esto se convierte en no hacer nada, que es el comportamiento de antes.
     */
    private fun mideLaPantalla(wm: WindowManager?) {
        if (tasaMaxima > 0f || wm == null) return
        @Suppress("DEPRECATION")
        val tasas = runCatching { wm.defaultDisplay?.supportedRefreshRates }.getOrNull()
        val mayor = tasas?.maxOrNull() ?: 60f
        // La candidata a «60» es la más cercana a 60 dentro de la ventana en la que
        // Telegram considera que un modo *es* el de 60 (`RefreshRateController.java:238`).
        val sesenta = tasas?.filter { it >= 58f && it <= 62.5f }?.minByOrNull { abs(it - 60f) }
        tasaDeSesenta = sesenta ?: 60f
        tasaMaxima = if (mayor > 0f) mayor else 60f
    }

    /** ¿Quiere el usuario esto encendido? De fábrica sí; se apaga en Ajustes → Aspecto. */
    private fun encendida(contexto: Context?): Boolean =
        (contexto?.applicationContext as? com.forge.pixpin.PixPinApp)?.ajustes?.maximaFluidez ?: true

    /** Le pide a la ventana la mayor tasa que dé la pantalla. */
    fun pedirTasaMaxima(ventana: Window?) = pedirTasa(ventana, TasaDePantalla.MAXIMA)

    /**
     * Le pide a la ventana una de las dos tasas.
     *
     * `updateViewLayout` puede lanzar —la vista aún no está pegada, o ya se fue—, y no hay
     * nada que hacer al respecto salvo seguir: por eso va envuelto, igual que en
     * `AndroidUtilities.java:2828`.
     */
    fun pedirTasa(ventana: Window?, cual: TasaDePantalla) {
        val wm = ventana?.windowManager ?: return
        mideLaPantalla(wm)
        val quiere = if (encendida(ventana.context)) hercios(cual) else 0f
        val atributos = ventana.attributes ?: return
        // Si ya la pide, no se rehace el layout: rehacerlo cuesta un fotograma entero.
        if (abs(atributos.preferredRefreshRate - quiere) < 0.2f) return
        atributos.preferredRefreshRate = quiere
        runCatching { wm.updateViewLayout(ventana.decorView, atributos) }
    }

    /**
     * Lo mismo para una ventana flotante, que no tiene [Window] detrás.
     *
     * Aquí se toca el `LayoutParams` **antes** de añadir la vista, que es lo cómodo: no
     * hay nada que rehacer porque todavía no existe. Estas son las que más se mueven de
     * toda la aplicación —los pines se arrastran, la capa se dibuja encima de otra app—,
     * así que son las que más falta les hace.
     */
    fun pedirTasaMaxima(atributos: WindowManager.LayoutParams, contexto: Context) {
        mideLaPantalla(contexto.getSystemService(WindowManager::class.java))
        atributos.preferredRefreshRate = if (encendida(contexto)) tasaMaxima else 0f
    }

    /**
     * Deja vigiladas **todas** las pantallas de la aplicación, las de hoy y las de mañana.
     *
     * Son veinte `ComponentActivity` sin una clase base común; engancharse aquí evita
     * tocarlas una a una y, sobre todo, evita que la próxima nazca sin esto. Ver
     * [com.forge.pixpin.PixPinApp].
     */
    fun vigilarLasVentanas(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            private val vigilantes = HashMap<Activity, ElVigilanteDeFotogramas>()

            override fun onActivityResumed(actividad: Activity) {
                // Al volver se pide otra vez la máxima: si la sesión anterior acabó a 60
                // porque aquella pantalla iba justa, esta empieza con el beneficio de la duda.
                pedirTasaMaxima(actividad.window)
                vigilantes.getOrPut(actividad) { ElVigilanteDeFotogramas(actividad.window) }.empieza()
            }

            override fun onActivityPaused(actividad: Activity) {
                vigilantes[actividad]?.para()
            }

            override fun onActivityDestroyed(actividad: Activity) {
                vigilantes.remove(actividad)?.para()
            }

            override fun onActivityCreated(actividad: Activity, estado: Bundle?) = Unit
            override fun onActivityStarted(actividad: Activity) = Unit
            override fun onActivityStopped(actividad: Activity) = Unit
            override fun onActivitySaveInstanceState(actividad: Activity, estado: Bundle) = Unit
        })
    }

    /** Para el vigilante: le da el reloj y el sitio donde aplicar lo que decida. */
    internal fun cronometro(): Handler = elCronometro

    internal fun enElHiloPrincipal(que: () -> Unit) {
        laManoPrincipal.post(que)
    }

    internal fun laPantallaEstaMedida(ventana: Window?) {
        mideLaPantalla(ventana?.windowManager)
    }

    internal fun fluidezEncendida(contexto: Context?): Boolean = encendida(contexto)
}

/**
 * **El vigilante de fotogramas**: mide lo que cuesta dibujar y baja la tasa si no da.
 *
 * Se engancha con `Window.addOnFrameMetricsAvailableListener`, que es la única forma de
 * saber lo que tardó un fotograma **de verdad** sin dibujar nada extra: lo cuenta el
 * propio sistema al entregarlo. Toda la cuenta ocurre en el hilo del cronómetro
 * ([PantallaFluida.cronometro]); al principal solo se salta cuando hay un cambio.
 *
 * Quién decide es [PoliticaDeFluidez]; esto solo le pasa números y obedece.
 */
class ElVigilanteDeFotogramas(private val ventana: Window) {

    private val politica = PoliticaDeFluidez()
    private var oyente: Window.OnFrameMetricsAvailableListener? = null

    /** Empieza a mirar. Llamar dos veces no engancha dos veces. */
    fun empieza() {
        if (oyente != null) return
        PantallaFluida.laPantallaEstaMedida(ventana)
        // En una pantalla de 60 Hz no hay decisión que tomar, y en ese caso medir sería
        // trabajo por fotograma a cambio de nada.
        if (!PantallaFluida.hayEntreQueElegir()) return
        if (!PantallaFluida.fluidezEncendida(ventana.context)) return

        val nuevo = Window.OnFrameMetricsAvailableListener { _, metricas, _ ->
            val nanos = metricas.getMetric(FrameMetrics.TOTAL_DURATION)
            if (nanos > 0L) {
                politica.anota(nanos)
                val nueva = politica.decide(SystemClock.uptimeMillis())
                if (nueva != null) {
                    PantallaFluida.enElHiloPrincipal { PantallaFluida.pedirTasa(ventana, nueva) }
                }
            }
        }
        runCatching { ventana.addOnFrameMetricsAvailableListener(nuevo, PantallaFluida.cronometro()) }
            .onSuccess { oyente = nuevo }
    }

    /** Deja de mirar y olvida lo medido. Ver [PoliticaDeFluidez.olvida]. */
    fun para() {
        val actual = oyente ?: return
        oyente = null
        runCatching { ventana.removeOnFrameMetricsAvailableListener(actual) }
        politica.olvida()
    }
}

/**
 * Añade una vista flotante pidiendo de paso la tasa máxima.
 *
 * Existe para que **no se pueda olvidar**: hay dieciséis sitios que abren ventanas
 * flotantes —pines, barras, la bola, la capa de dibujo, el visor— y poner la línea en cada
 * uno significa que la decimoséptima no la lleva. Lanza lo mismo que `addView`, así que
 * los `runCatching` de quien llama siguen valiendo igual.
 */
fun WindowManager.añadirVistaFluida(vista: View, atributos: WindowManager.LayoutParams) {
    PantallaFluida.pedirTasaMaxima(atributos, vista.context)
    addView(vista, atributos)
}
