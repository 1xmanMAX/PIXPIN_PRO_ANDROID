package com.forge.pixpin.croquis3d

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/**
 * **Lo que hay delante, detrás del croquis.**
 *
 * Es la mitad de asomarse al mundo: la vista de la cámara puesta al fondo, y el croquis
 * pintado encima con el fondo transparente. La otra mitad es que la vista mire a donde mira
 * el aparato, y esa es [ElSensorDeLaPostura].
 *
 * ## Se para sola
 *
 * La cámara se enciende cuando esto entra en la pantalla y se apaga cuando sale, atada al
 * ciclo de vida de quien la hospeda. Es lo que hace que salir del modo, salir de la
 * aplicación o que llegue una llamada suelten la cámara sin que nadie tenga que acordarse:
 * una cámara que se queda abierta es un aparato caliente, una batería que se va y —peor— un
 * punto verde encendido en la barra de estado de alguien que ya no está usando esto.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun VistaDeLaCamara(
    modifier: Modifier = Modifier,
    /**
     * A quién decirle lo que abarca la cámara en vertical, en radianes, en cuanto se sabe.
     *
     * Es lo que cuadra la lente del croquis con la del aparato. Ver [LenteDelAparato] y
     * [Croquis3DControlador.lenteDelAparato].
     */
    alSaberLaLente: (Double) -> Unit = {}
) {
    val contexto = LocalContext.current
    val duenoDelCiclo = LocalLifecycleOwner.current
    val avisar = rememberUpdatedState(alSaberLaLente)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val vista = PreviewView(ctx)
            // **Rellenando y recortando, no encajando con bandas.** Lo que se ve tiene
            // que ocupar la pantalla entera: con bandas negras a los lados, el croquis
            // se pinta encima de ellas y lo que se ve es un dibujo flotando sobre un
            // marco, que es justo lo contrario de estar puesto en el sitio.
            vista.scaleType = PreviewView.ScaleType.FILL_CENTER
            // La de compatibilidad y no la de superficie: la de superficie va en su
            // propia ventana **por debajo** de la de la aplicación, así que el croquis
            // se pintaría detrás de la imagen en vez de encima.
            vista.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

            // **La cámara se ata una vez, al nacer la vista, y no en cada `update`.** El
            // bloque de actualización corre cada vez que Compose lo decide, y con el
            // sensor de postura repintando cincuenta veces por segundo eso era arriesgarse
            // a volver a abrir la cámara a cada rato: la imagen parpadeaba y el aparato
            // se calentaba.
            val futuro = ProcessCameraProvider.getInstance(ctx)
            futuro.addListener({
                val proveedor = runCatching { futuro.get() }.getOrNull() ?: return@addListener
                val mirilla = Preview.Builder().build()
                    .also { it.surfaceProvider = vista.surfaceProvider }
                val camara = runCatching {
                    proveedor.unbindAll()
                    proveedor.bindToLifecycle(
                        duenoDelCiclo, CameraSelector.DEFAULT_BACK_CAMERA, mirilla
                    )
                }.getOrNull() ?: return@addListener
                // Lo que abarca, medido en cuanto la vista sabe lo que mide: recién atada,
                // la vista previa aún puede no tener tamaño.
                vista.post { medirLaLente(vista, camara, mirilla)?.let { avisar.value(it) } }
            }, ContextCompat.getMainExecutor(ctx))
            vista
        },
        onRelease = { vista ->
            runCatching {
                ProcessCameraProvider.getInstance(vista.context).get().unbindAll()
            }
        }
    )
    // Y al salir, la cámara se suelta pase lo que pase: `onRelease` no corre si la vista se
    // recompone en vez de irse.
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(contexto).get().unbindAll()
            }
        }
    }
}

/**
 * Lo que abarca en vertical, en esta pantalla, la cámara que se acaba de atar.
 *
 * Los números salen de las características de Camera2 —el tamaño físico del sensor, su
 * matriz de píxeles, la parte activa y la focal— y de lo que CameraX dice de la vista
 * previa: su resolución y cuánto hay que girar el sensor para esta pantalla. La cuenta en
 * sí es de [LenteDelAparato], que no toca Android. Null si falta cualquier dato: entonces
 * el croquis se queda con la lente de un teléfono corriente.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun medirLaLente(
    vista: PreviewView,
    camara: androidx.camera.core.Camera,
    mirilla: Preview
): Double? = runCatching {
    val info = Camera2CameraInfo.from(camara.cameraInfo)
    val fisico = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        ?: return null
    val pixeles = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        ?: return null
    val activo = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        ?: return null
    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.firstOrNull() ?: return null
    val flujo = mirilla.resolutionInfo?.resolution ?: return null
    val giro = camara.cameraInfo.getSensorRotationDegrees(
        vista.display?.rotation ?: Surface.ROTATION_0
    )
    val ancho = vista.width.takeIf { it > 0 } ?: vista.resources.displayMetrics.widthPixels
    val alto = vista.height.takeIf { it > 0 } ?: vista.resources.displayMetrics.heightPixels
    LenteDelAparato.campoVertical(
        sensorMm = fisico.width.toDouble() to fisico.height.toDouble(),
        pixeles = pixeles.width to pixeles.height,
        activo = activo.width() to activo.height(),
        focalMm = focal.toDouble(),
        flujo = flujo.width to flujo.height,
        giroDelSensor = giro,
        pantalla = ancho to alto
    )
}.getOrNull()

/**
 * **Hacia dónde está mirando el aparato**, fotograma a fotograma.
 *
 * Del vector de rotación y no del acelerómetro con la brújula: el vector de rotación ya
 * viene fusionado —giróscopo para lo rápido, acelerómetro y magnetómetro para que no se
 * vaya con el tiempo—, que es exactamente la mezcla que hace falta y la que sale mal
 * hecha a mano.
 *
 * **Y remapeada a cómo está puesta la pantalla.** El sensor habla en coordenadas del
 * aparato, que no cambian al girar la pantalla; lo que hay que mover con ella es qué es la
 * derecha y qué es el arriba de lo que se ve. Sin esto, en apaisado el croquis sale tumbado.
 */
@Composable
fun ElSensorDeLaPostura(
    encendido: Boolean,
    alMoverse: (PosturaDelTelefono) -> Unit,
    /**
     * Cuánto se ha desplazado el aparato desde la muestra anterior, en metros y en el
     * sistema del mundo, según su acelerómetro. Ver [InerciaDelTelefono].
     */
    alDesplazarse: (com.forge.pixpin.motor.Pt3) -> Unit = {},
    /** El podómetro del aparato ha contado un paso. */
    alDarUnPaso: () -> Unit = {}
) {
    val contexto = LocalContext.current
    val ultimo = rememberUpdatedState(alMoverse)
    val desplazar = rememberUpdatedState(alDesplazarse)
    val paso = rememberUpdatedState(alDarUnPaso)

    DisposableEffect(encendido, contexto) {
        if (!encendido) return@DisposableEffect onDispose { }
        val sensores = contexto.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val cual = sensores?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensores == null || cual == null) return@DisposableEffect onDispose { }

        val crudo = FloatArray(9)
        val puesta = FloatArray(9)
        var hayPostura = false
        val inercia = InerciaDelTelefono()
        val oyente = object : SensorEventListener {
            override fun onSensorChanged(evento: SensorEvent) {
                when (evento.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(crudo, evento.values)
                        hayPostura = true
                        val (x, y) = ejesDeLaPantalla(giroDeLaPantalla(contexto))
                        SensorManager.remapCoordinateSystem(crudo, x, y, puesta)
                        PosturaDelTelefono.deLaMatriz(puesta)?.let { ultimo.value(it) }
                    }

                    // **La aceleración lineal, pasada al mundo.** El sensor habla en los
                    // ejes del aparato; la matriz de giro cruda —la del aparato, no la
                    // remapeada a la pantalla— la lleva al mundo, que es donde se integra:
                    // así inclinar el teléfono no se confunde con moverlo.
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        if (!hayPostura) return
                        val v = evento.values
                        val ax = crudo[0] * v[0] + crudo[1] * v[1] + crudo[2] * v[2]
                        val ay = crudo[3] * v[0] + crudo[4] * v[1] + crudo[5] * v[2]
                        val az = crudo[6] * v[0] + crudo[7] * v[1] + crudo[8] * v[2]
                        inercia.muestra(ax.toDouble(), ay.toDouble(), az.toDouble(), evento.timestamp)
                            ?.let { if (largo(it) > 0.0) desplazar.value(it) }
                    }

                    Sensor.TYPE_STEP_DETECTOR -> {
                        inercia.pasoDado()
                        paso.value()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, precision: Int) = Unit
        }
        // **Al ritmo de la pantalla y no al máximo que dé.** Un sensor a la velocidad más
        // alta manda muestras mucho más deprisa de lo que se pinta, y cada una obliga a
        // repintar: es trabajo que no se ve y batería que sí se nota. Ver
        // [Croquis3DControlador.mirarComoElTelefono].
        sensores.registerListener(oyente, cual, SensorManager.SENSOR_DELAY_GAME)
        // Los otros dos, si el aparato los tiene: sin ellos se mira alrededor y ya.
        sensores.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensores.registerListener(oyente, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensores.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sensores.registerListener(oyente, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        onDispose { sensores.unregisterListener(oyente) }
    }
}

/**
 * Cuánto está girada la pantalla ahora mismo.
 *
 * **`Context.display` es de Android 11 y aquí se admite desde el 10.** Estaba a pelo dentro
 * de un `runCatching`, así que en un móvil con Android 10 no reventaba: se tragaba el
 * `NoSuchMethodError` y devolvía «sin girar» *siempre*. Y eso no se ve como un fallo, se ve
 * como que el croquis mira hacia donde no toca en cuanto pones el teléfono de lado — el giro
 * es justo lo que decide el reparto de ejes de [ejesDeLaPantalla].
 *
 * Por debajo del 11 se pregunta por el camino viejo, que es el que había para eso.
 */
@Suppress("DEPRECATION")
private fun giroDeLaPantalla(contexto: Context): Int = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        contexto.display?.rotation
    } else {
        contexto.getSystemService(android.view.WindowManager::class.java)
            ?.defaultDisplay?.rotation
    } ?: Surface.ROTATION_0
}.getOrDefault(Surface.ROTATION_0)

/**
 * En qué ejes del aparato caen la derecha y el arriba de la pantalla, según cómo esté
 * girada. Es lo que espera `SensorManager.remapCoordinateSystem`.
 */
private fun ejesDeLaPantalla(giro: Int): Pair<Int, Int> = when (giro) {
    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
}
