package com.forge.pixpin.croquis

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.DashPathEffect
import android.graphics.Paint as APaint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.capture.Export
import com.forge.pixpin.pin.ImageStore
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

private enum class Modo { DIBUJAR, MEDIR, CALIBRAR }

private enum class Herramienta(val etiqueta: String) {
    LINEA("Línea"),
    POLILINEA("Polilínea"),
    RECT("Rect"),
    CIRCULO("Círculo"),
    COTA("Cota"),

    /** Se toca la línea que corta y luego el trozo que sobra. */
    RECORTAR("Recortar"),

    /** Se toca la línea a alargar y luego hasta dónde. */
    EXTENDER("Extender");

    /** Las que trabajan sobre líneas ya dibujadas, no poniendo puntos. */
    val esEdicion: Boolean get() = this == RECORTAR || this == EXTENDER
}

/** Cuál de los dos puntos en juego se está arrastrando. */
private enum class Agarre { NINGUNO, A, B }

/**
 * El editor del croquis, a pantalla completa.
 *
 * No vive en el pin flotante a propósito: una hoja de trabajo y una barra de
 * entrada numérica no caben en una ventana pequeña. El pin sigue enseñando el
 * resultado y sirve para copiarlo.
 */
class CroquisEditorActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_RUTA = "croquis_ruta"
        private const val EXTRA_ID = "croquis_id"
        private const val EXTRA_FONDO = "croquis_fondo"

        fun abrir(context: Context, id: String, rutaCroquis: String?, fondoPath: String?) {
            context.startActivity(
                Intent(context, CroquisEditorActivity::class.java).apply {
                    putExtra(EXTRA_ID, id)
                    putExtra(EXTRA_RUTA, rutaCroquis)
                    putExtra(EXTRA_FONDO, fondoPath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    private lateinit var croquisId: String
    private var fondoBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        croquisId = intent.getStringExtra(EXTRA_ID) ?: System.currentTimeMillis().toString()
        val cargado = CroquisStore.cargar(intent.getStringExtra(EXTRA_RUTA)) ?: Croquis()
        val fondoPath = intent.getStringExtra(EXTRA_FONDO)
        fondoBitmap = fondoPath?.let { ImageStore.load(it) }

        val inicial = if (fondoPath != null && cargado.fondo == null) {
            cargado.copy(fondo = Fondo(fondoPath, P(0.0, 0.0), metrosPorPixel = 0.01))
        } else cargado

        setContent {
            PixPinTheme {
                Editor(inicial, fondoBitmap, ::guardar) { guardar(it); finish() }
            }
        }
    }

    /**
     * Se guarda en cada cambio, no al salir.
     *
     * Reabrir el croquis tiene que devolverlo **exactamente como estaba**, y en
     * esta aplicación el proceso muere sin avisar más de lo normal: guardar solo
     * en `onPause` sería confiar en que siempre llega.
     */
    private fun guardar(croquis: Croquis) {
        CroquisStore.guardar(this, croquisId, croquis)
    }

    @Composable
    private fun Editor(
        inicial: Croquis,
        fondo: Bitmap?,
        alGuardar: (Croquis) -> Unit,
        alSalir: (Croquis) -> Unit
    ) {
        var croquis by remember { mutableStateOf(inicial) }
        var vista by remember { mutableStateOf(Vista(P(0.0, 0.0), 120.0)) }
        var modo by remember { mutableStateOf(Modo.DIBUJAR) }
        var herramienta by remember { mutableStateOf(Herramienta.LINEA) }
        var orto by remember { mutableStateOf(false) }

        var puntoA by remember { mutableStateOf<P?>(null) }
        var puntoB by remember { mutableStateOf<P?>(null) }
        var agarre by remember { mutableStateOf(Agarre.NINGUNO) }
        var imantadoEn by remember { mutableStateOf<P?>(null) }
        var campo by remember { mutableStateOf("") }
        var campoAngulo by remember { mutableStateOf("") }
        /** El ángulo se teclea como pendiente en %, que es como se habla en obra. */
        var enPorcentaje by remember { mutableStateOf(false) }
        /** Vértices de la polilínea en curso: encadena hasta que se cierra. */
        var cadena by remember { mutableStateOf<List<P>>(emptyList()) }
        /** Primera línea señalada al recortar o extender. */
        var señalada by remember { mutableStateOf<Int?>(null) }
        var aviso by remember { mutableStateOf<String?>(null) }

        var ancho by remember { mutableStateOf(1) }
        var alto by remember { mutableStateOf(1) }
        val densidad = resources.displayMetrics.density

        /** Radio, en metros del mundo, dentro del cual el dedo agarra un punto. */
        fun radioAgarreM() = 34.0 * densidad / vista.pixelsPorMetro
        /** Radio de imantado, algo menor: conectar es más fino que agarrar. */
        fun radioImanM() = 22.0 * densidad / vista.pixelsPorMetro

        /**
         * Coloca un punto aplicando imantado y, si toca, orto.
         * Deja constancia en [imantadoEn] para poder pintar el cuadradito.
         */
        fun colocar(bruto: P, desde: P?): P {
            val extremos = croquis.extremos()
            val pegado = CroquisGeometria.imantar(bruto, extremos, radioImanM())
            imantadoEn = if (pegado != bruto) pegado else null
            if (pegado != bruto) return pegado
            // Con orto encendido la restricción es dura: horizontal o vertical.
            // Apagado queda la ayuda suave de 5°, que corrige el pulso sin
            // impedir dibujar en diagonal.
            return if (desde == null) bruto
            else if (orto) CroquisGeometria.orto(desde, bruto, 46.0)
            else CroquisGeometria.orto(desde, bruto, 5.0)
        }

        fun limpiar() {
            puntoA = null; puntoB = null; campo = ""; campoAngulo = ""
            imantadoEn = null; agarre = Agarre.NINGUNO; señalada = null
        }

        /**
         * Qué hace un toque, según la herramienta.
         *
         * Las de edición trabajan sobre líneas ya dibujadas y no ponen puntos;
         * la polilínea encadena; el resto ponen los dos puntos de siempre.
         */
        fun tocar(bruto: P) {
            val tolerancia = 30.0 * densidad / vista.pixelsPorMetro

            if (herramienta.esEdicion) {
                val i = CroquisGeometria.lineaMasCercana(croquis, bruto, tolerancia)
                if (i == null) { aviso = "Ahí no hay ninguna línea"; return }
                val primera = señalada
                if (primera == null) {
                    señalada = i
                    aviso = if (herramienta == Herramienta.RECORTAR)
                        "Ahora toca el trozo que sobra" else "Ahora toca hasta dónde alargar"
                    return
                }
                if (primera == i) { aviso = "Tiene que ser otra línea"; return }
                val lineas = croquis.entidades.toMutableList()
                if (herramienta == Herramienta.RECORTAR) {
                    // La primera señalada es la cuchilla; la segunda, la que se
                    // corta — y el punto tocado dice qué mitad sobra.
                    val cuchilla = lineas[primera] as Entidad.Linea
                    val victima = lineas[i] as Entidad.Linea
                    val r = CroquisGeometria.recortar(victima, cuchilla, bruto)
                    if (r == null) { aviso = "Esas dos no se cruzan"; señalada = null; return }
                    lineas[i] = r
                } else {
                    val quien = lineas[primera] as Entidad.Linea
                    val hasta = lineas[i] as Entidad.Linea
                    val r = CroquisGeometria.extender(quien, hasta)
                    if (r == null) { aviso = "Son paralelas: nunca se encuentran"; señalada = null; return }
                    lineas[primera] = r
                }
                croquis = croquis.copy(entidades = lineas)
                alGuardar(croquis)
                señalada = null
                aviso = null
                return
            }

            if (herramienta == Herramienta.POLILINEA) {
                val anterior = cadena.lastOrNull()
                cadena = cadena + colocar(bruto, anterior)
                return
            }

            if (puntoA == null) puntoA = colocar(bruto, null)
            else if (puntoB == null) puntoB = colocar(bruto, puntoA)
        }

        /** Cierra la polilínea en curso y la deja dibujada. */
        fun terminarCadena(cerrada: Boolean) {
            if (cadena.size >= 2) {
                croquis = croquis.copy(
                    entidades = croquis.entidades + Entidad.Polilinea(cadena, cerrada)
                )
                alGuardar(croquis)
            }
            cadena = emptyList()
        }

        fun confirmar() {
            val a = puntoA ?: return
            val b = puntoB ?: return
            when (modo) {
                Modo.CALIBRAR -> {
                    val metros = campo.replace(',', '.').toDoubleOrNull()
                    val f = croquis.fondo
                    val escala = if (metros == null || metros <= 0.0 || f == null) null else {
                        val px = CroquisGeometria.distancia(a, b) / f.metrosPorPixel
                        if (px > 0) metros / px else null
                    }
                    if (escala == null) {
                        aviso = "Traza la regla sobre una medida conocida y escribe cuánto mide"
                        return
                    }
                    croquis = croquis.copy(fondo = f!!.copy(metrosPorPixel = escala))
                    alGuardar(croquis)
                    aviso = "Calibrado. Ya puedes medir en metros reales"
                    modo = Modo.MEDIR
                }

                Modo.DIBUJAR -> {
                    // Longitud y ángulo son independientes: se puede fijar solo
                    // una. El ángulo se teclea en grados o en pendiente %, según
                    // el conmutador.
                    val metros = campo.replace(',', '.').toDoubleOrNull()
                        ?: CroquisGeometria.distancia(a, b)
                    val gradosTecleados = campoAngulo.replace(',', '.').toDoubleOrNull()
                    val grados = when {
                        gradosTecleados == null -> CroquisGeometria.gradosDe(a, b)
                        enPorcentaje -> CroquisGeometria.porcentajeAGrados(gradosTecleados)
                        else -> gradosTecleados
                    }
                    val destino =
                        if (metros > 0) CroquisGeometria.desdePolar(a, metros, grados) else b
                    val nueva: Entidad = when (herramienta) {
                        Herramienta.LINEA -> Entidad.Linea(a, destino)
                        Herramienta.POLILINEA -> Entidad.Polilinea(listOf(a, destino))
                        Herramienta.RECT -> Entidad.Rect(a, destino)
                        Herramienta.CIRCULO ->
                            Entidad.Circulo(a, CroquisGeometria.distancia(a, destino))
                        Herramienta.COTA -> Entidad.Cota(a, destino, desplazamiento = 0.4)
                        // Recortar y extender actúan al tocar líneas: nunca
                        // llegan aquí con dos puntos puestos.
                        Herramienta.RECORTAR, Herramienta.EXTENDER -> return
                    }
                    croquis = croquis.copy(entidades = croquis.entidades + nueva)
                    alGuardar(croquis)
                    aviso = null
                }

                Modo.MEDIR -> Unit
            }
            limpiar()
        }

        Column(Modifier.fillMaxSize().background(Color(0xFF14181D))) {

            BarraSuperior(
                modo = modo,
                herramienta = herramienta,
                orto = orto,
                hayFondo = croquis.fondo != null,
                puedeDeshacer = croquis.entidades.isNotEmpty(),
                alModo = { modo = it; limpiar(); aviso = null },
                alHerramienta = { herramienta = it },
                alOrto = { orto = !orto },
                alDeshacer = {
                    croquis = croquis.copy(entidades = croquis.entidades.dropLast(1))
                    alGuardar(croquis)
                },
                alSalir = { alSalir(croquis) }
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF2A2F36))
                    // UN SOLO gesto para todo.
                    //
                    // Antes había tres detectores encadenados y se robaban los
                    // eventos entre ellos: el zoom iba a rachas y el punto se
                    // escapaba del dedo. Peor aún, el detector llevaba puntoA y
                    // puntoB en su clave, así que Compose lo cancelaba y lo
                    // reiniciaba en cuanto el punto se movía — o sea, siempre.
                    //
                    // La clave es Unit y no se reinicia nunca: los valores se
                    // leen a través de sus delegados, que siempre dan el actual.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val abajo = awaitFirstDown(requireUnconsumed = false)
                            val origen = abajo.position

                            val mundoAbajo = CroquisGeometria.aMundo(
                                Px(origen.x, origen.y), vista, ancho, alto
                            )
                            val radio = radioAgarreM()
                            val dA = puntoA?.let { CroquisGeometria.distancia(mundoAbajo, it) }
                            val dB = puntoB?.let { CroquisGeometria.distancia(mundoAbajo, it) }
                            var agarrado = when {
                                dB != null && dB <= radio && (dA == null || dB <= dA) -> Agarre.B
                                dA != null && dA <= radio -> Agarre.A
                                else -> Agarre.NINGUNO
                            }
                            agarre = agarrado

                            // Se conserva la separación con la que agarraste, en
                            // vez de teletransportar el punto bajo el dedo. Si
                            // agarraste casi encima, se separa a la fuerza: un
                            // punto debajo de la mano no se ve.
                            var apartado = Offset.Zero
                            if (agarrado != Agarre.NINGUNO) {
                                val p = if (agarrado == Agarre.A) puntoA!! else puntoB!!
                                val q = CroquisGeometria.aPantalla(p, vista, ancho, alto)
                                apartado = Offset(q.x - origen.x, q.y - origen.y)
                                if (apartado.getDistance() < 26f * densidad) {
                                    apartado = Offset(DESPLAZA_X * densidad, -DESPLAZA_Y * densidad)
                                }
                            }

                            var movido = false
                            var conDosDedos = false
                            var evento: androidx.compose.ui.input.pointer.PointerEvent

                            do {
                                evento = awaitPointerEvent()
                                val vivos = evento.changes.filter { it.pressed }

                                if (vivos.size >= 2) {
                                    // Dos dedos son SIEMPRE navegar, pase lo que
                                    // pase: si empezaste agarrando un punto y
                                    // apoyas el segundo dedo, sueltas el punto.
                                    conDosDedos = true
                                    agarrado = Agarre.NINGUNO
                                    agarre = Agarre.NINGUNO
                                    val zoom = evento.calculateZoom()
                                    val pan = evento.calculatePan()
                                    if (zoom != 1f || pan != Offset.Zero) {
                                        val ppm = (vista.pixelsPorMetro * zoom)
                                            .coerceIn(0.05, 200_000.0)
                                        vista = Vista(
                                            P(
                                                vista.centro.x - pan.x / ppm,
                                                vista.centro.y + pan.y / ppm
                                            ),
                                            ppm
                                        )
                                        movido = true
                                    }
                                    evento.changes.forEach { it.consume() }
                                } else if (vivos.size == 1 && !conDosDedos) {
                                    val dedo = vivos[0]
                                    if (!movido &&
                                        (dedo.position - origen).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) movido = true

                                    if (movido) {
                                        if (agarrado != Agarre.NINGUNO) {
                                            val destino = Px(
                                                dedo.position.x + apartado.x,
                                                dedo.position.y + apartado.y
                                            )
                                            val bruto = CroquisGeometria.aMundo(
                                                destino, vista, ancho, alto
                                            )
                                            if (agarrado == Agarre.A) {
                                                puntoA = colocar(bruto, puntoB)
                                            } else {
                                                puntoB = colocar(bruto, puntoA)
                                            }
                                        } else {
                                            // Un dedo sobre el vacío desplaza la
                                            // hoja: en obra se maneja con una mano.
                                            val d = dedo.positionChange()
                                            val ppm = vista.pixelsPorMetro
                                            vista = Vista(
                                                P(
                                                    vista.centro.x - d.x / ppm,
                                                    vista.centro.y + d.y / ppm
                                                ),
                                                ppm
                                            )
                                        }
                                        dedo.consume()
                                    }
                                }
                            } while (evento.changes.any { it.pressed })

                            agarre = Agarre.NINGUNO

                            // Sin desplazamiento, era un toque: coloca punto.
                            if (!movido && !conDosDedos) {
                                tocar(
                                    CroquisGeometria.aMundo(
                                        Px(origen.x, origen.y), vista, ancho, alto
                                    )
                                )
                            }
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    ancho = size.width.toInt().coerceAtLeast(1)
                    alto = size.height.toInt().coerceAtLeast(1)
                    drawIntoCanvas { lienzo ->
                        val c = lienzo.nativeCanvas
                        val hoja = CroquisGeometria.hojaA4(croquis, vista, ancho, alto)
                        dibujarHoja(c, hoja, vista, ancho, alto)
                        CroquisRenderer.dibujar(c, croquis, vista, ancho, alto, fondo, AColor.BLACK)
                        dibujarPendiente(
                            c, puntoA, puntoB, cadena, señalada, croquis, imantadoEn,
                            vista, ancho, alto, densidad
                        )
                    }
                }
            }

            BarraInferior(
                modo = modo,
                herramienta = herramienta,
                puntoA = puntoA,
                puntoB = puntoB,
                vertices = cadena.size,
                decimales = croquis.decimales,
                campo = campo,
                campoAngulo = campoAngulo,
                enPorcentaje = enPorcentaje,
                aviso = aviso,
                alEscribir = { campo = it },
                alEscribirAngulo = { campoAngulo = it },
                alCambiarUnidadAngulo = { enPorcentaje = !enPorcentaje; campoAngulo = "" },
                alConfirmar = { confirmar() },
                alCancelar = { limpiar(); cadena = emptyList(); aviso = null },
                alCerrarCadena = { terminarCadena(true) },
                alTerminarCadena = { terminarCadena(false) },
                alPdf = { exportar(croquis, true) { aviso = it } },
                alJpg = { exportar(croquis, false) { aviso = it } }
            )
        }
    }

    private fun exportar(croquis: Croquis, comoPdf: Boolean, avisar: (String) -> Unit) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (comoPdf) {
                    CroquisExport.aPdf(this@CroquisEditorActivity, croquis, fondoBitmap) != null
                } else {
                    val bmp = CroquisExport.aBitmap(croquis, fondoBitmap)
                        ?: return@withContext false
                    val uri = Export.saveToGallery(
                        this@CroquisEditorActivity, bmp, Bitmap.CompressFormat.JPEG, 92
                    )
                    if (!bmp.isRecycled) bmp.recycle()
                    uri != null
                }
            }
            avisar(
                if (!ok) "No hay nada que exportar todavía"
                else if (comoPdf) "PDF guardado en Descargas/PixPin"
                else "JPG guardado en la galería"
            )
        }
    }
}

/** Cuánto se separa del dedo el punto que se arrastra, en dp. */
private const val DESPLAZA_X = 26f
private const val DESPLAZA_Y = 34f

/** La hoja que va a salir en el PDF, a rayas: lo de fuera no se exporta. */
private fun dibujarHoja(
    c: android.graphics.Canvas, hoja: Caja?, vista: Vista, ancho: Int, alto: Int
) {
    if (hoja == null) return
    val a = CroquisGeometria.aPantalla(P(hoja.min.x, hoja.max.y), vista, ancho, alto)
    val b = CroquisGeometria.aPantalla(P(hoja.max.x, hoja.min.y), vista, ancho, alto)
    c.drawRect(a.x, a.y, b.x, b.y, APaint().apply { color = AColor.WHITE })
    c.drawRect(a.x, a.y, b.x, b.y, APaint(APaint.ANTI_ALIAS_FLAG).apply {
        style = APaint.Style.STROKE
        strokeWidth = 2f
        color = AColor.rgb(150, 160, 175)
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    })
}

/** Lo que está a medio poner: en azul, porque aún no es parte del croquis. */
private fun dibujarPendiente(
    c: android.graphics.Canvas,
    a: P?, b: P?,
    cadena: List<P>,
    señalada: Int?,
    croquis: Croquis,
    iman: P?,
    vista: Vista, ancho: Int, alto: Int, densidad: Float
) {
    // La línea señalada para recortar o extender, resaltada en naranja.
    señalada?.let { i ->
        (croquis.entidades.getOrNull(i) as? Entidad.Linea)?.let { l ->
            val pa = CroquisGeometria.aPantalla(l.a, vista, ancho, alto)
            val pb = CroquisGeometria.aPantalla(l.b, vista, ancho, alto)
            c.drawLine(pa.x, pa.y, pb.x, pb.y, APaint(APaint.ANTI_ALIAS_FLAG).apply {
                color = AColor.rgb(255, 145, 0)
                strokeWidth = 5f * densidad / 2f
                style = APaint.Style.STROKE
            })
        }
    }
    val azul = APaint(APaint.ANTI_ALIAS_FLAG).apply {
        color = AColor.rgb(0, 132, 255); strokeWidth = 3f * densidad / 2f
    }
    val hueco = APaint(APaint.ANTI_ALIAS_FLAG).apply {
        color = AColor.WHITE; style = APaint.Style.FILL
    }
    fun nodo(p: P) {
        val q = CroquisGeometria.aPantalla(p, vista, ancho, alto)
        c.drawCircle(q.x, q.y, 11f * densidad / 2f, hueco)
        c.drawCircle(q.x, q.y, 11f * densidad / 2f, azul.apply { style = APaint.Style.STROKE })
    }
    if (a != null && b != null) {
        val pa = CroquisGeometria.aPantalla(a, vista, ancho, alto)
        val pb = CroquisGeometria.aPantalla(b, vista, ancho, alto)
        c.drawLine(pa.x, pa.y, pb.x, pb.y, azul.apply { style = APaint.Style.STROKE })
    }
    // La polilínea en curso, con un nodo por vértice puesto.
    if (cadena.size >= 2) {
        for (i in 0 until cadena.size - 1) {
            val p1 = CroquisGeometria.aPantalla(cadena[i], vista, ancho, alto)
            val p2 = CroquisGeometria.aPantalla(cadena[i + 1], vista, ancho, alto)
            c.drawLine(p1.x, p1.y, p2.x, p2.y, azul.apply { style = APaint.Style.STROKE })
        }
    }
    cadena.forEach { nodo(it) }

    a?.let { nodo(it) }
    b?.let { nodo(it) }

    // El cuadradito del imantado: la señal de que ESO va a conectar de verdad.
    iman?.let {
        val q = CroquisGeometria.aPantalla(it, vista, ancho, alto)
        val r = 13f * densidad / 2f
        c.drawRect(q.x - r, q.y - r, q.x + r, q.y + r, APaint(APaint.ANTI_ALIAS_FLAG).apply {
            style = APaint.Style.STROKE
            strokeWidth = 2.5f * densidad / 2f
            color = AColor.rgb(255, 145, 0)
        })
    }
}

// ---------- Barras ----------

@Composable
private fun Chip(texto: String, activo: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (activo) Color(0xFF29B8DB) else Color(0xFF272D35),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
        modifier = Modifier
            .height(30.dp)
            .pointerInput(texto) { detectTapGestures { onClick() } }
    ) {
        Box(Modifier.padding(horizontal = 10.dp).fillMaxSize(), Alignment.Center) {
            Text(
                texto,
                color = if (activo) Color(0xFF06222B) else Color(0xFFC9D2DC),
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BarraSuperior(
    modo: Modo,
    herramienta: Herramienta,
    orto: Boolean,
    hayFondo: Boolean,
    puedeDeshacer: Boolean,
    alModo: (Modo) -> Unit,
    alHerramienta: (Herramienta) -> Unit,
    alOrto: () -> Unit,
    alDeshacer: () -> Unit,
    alSalir: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Chip("‹", false, alSalir)
            Chip("Dibujar", modo == Modo.DIBUJAR) { alModo(Modo.DIBUJAR) }
            Chip("Medir", modo == Modo.MEDIR) { alModo(Modo.MEDIR) }
            // «Escalar» y no «Calibrar»: es la palabra que se busca cuando lo
            // que se quiere es que la captura mida en metros de verdad.
            if (hayFondo) Chip("⇔ Escalar", modo == Modo.CALIBRAR) { alModo(Modo.CALIBRAR) }
        }
        // Las herramientas solo existen en modo dibujar: en medir el dibujo
        // está deshabilitado de verdad, no escondido detrás de un if.
        if (modo == Modo.DIBUJAR) {
            Row(
                Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Herramienta.entries.forEach { h ->
                    Chip(h.etiqueta, herramienta == h) { alHerramienta(h) }
                }
                Chip(if (orto) "⊾ Orto" else "⊾", orto, alOrto)
                if (puedeDeshacer) Chip("↶", false, alDeshacer)
            }
        }
    }
}

@Composable
private fun BarraInferior(
    modo: Modo,
    herramienta: Herramienta,
    puntoA: P?,
    puntoB: P?,
    vertices: Int,
    decimales: Int,
    campo: String,
    campoAngulo: String,
    enPorcentaje: Boolean,
    aviso: String?,
    alEscribir: (String) -> Unit,
    alEscribirAngulo: (String) -> Unit,
    alCambiarUnidadAngulo: () -> Unit,
    alConfirmar: () -> Unit,
    alCancelar: () -> Unit,
    alCerrarCadena: () -> Unit,
    alTerminarCadena: () -> Unit,
    alPdf: () -> Unit,
    alJpg: () -> Unit
) {
    val medida = if (puntoA != null && puntoB != null)
        CroquisGeometria.distancia(puntoA, puntoB) else null
    val grados = if (puntoA != null && puntoB != null)
        Math.toDegrees(atan2(puntoB.y - puntoA.y, puntoB.x - puntoA.x)) else null
    val enCurso = puntoA != null && puntoB != null && modo != Modo.MEDIR

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(
            when {
                aviso != null -> aviso
                modo == Modo.MEDIR && medida != null ->
                    "◄─► " + CroquisGeometria.formatear(medida, decimales) +
                        "   ∠ " + "%.1f°".format(grados) +
                        "   ↗ " + "%.1f%%".format(CroquisGeometria.gradosAPorcentaje(grados!!))
                modo == Modo.MEDIR -> "Toca dos puntos para medir"
                modo == Modo.CALIBRAR && puntoA == null -> "Traza una medida que ya conozcas"
                modo == Modo.CALIBRAR -> "Escribe cuánto mide de verdad"
                herramienta.esEdicion -> "Toca una línea"
                herramienta == Herramienta.POLILINEA ->
                    if (vertices == 0) "Toca dónde empieza" else "$vertices vértices · sigue tocando"
                puntoA == null -> "Toca dónde empieza"
                medida == null -> "Toca dónde acaba"
                else -> CroquisGeometria.formatear(medida, decimales) +
                    "   ∠ " + "%.1f°".format(grados) + "   ·  arrastra los puntos para ajustar"
            },
            color = Color(0xFFC9D2DC),
            fontSize = 12.sp
        )

        if (herramienta == Herramienta.POLILINEA && vertices >= 2 && modo == Modo.DIBUJAR) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Chip("Terminar", false, alTerminarCadena)
                Chip("Cerrar", false, alCerrarCadena)
                Chip("✕", false, alCancelar)
            }
        } else if (enCurso) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = campo,
                    onValueChange = alEscribir,
                    placeholder = { Text("largo m", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                    modifier = Modifier.width(95.dp).height(50.dp)
                )
                if (modo != Modo.CALIBRAR) {
                    OutlinedTextField(
                        value = campoAngulo,
                        onValueChange = alEscribirAngulo,
                        placeholder = {
                            Text(if (enPorcentaje) "pend %" else "áng °", fontSize = 11.sp)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                        modifier = Modifier.width(95.dp).height(50.dp)
                    )
                    // El mismo dato en las dos unidades con las que se habla:
                    // grados en un plano, pendiente % en una rasante.
                    Chip(if (enPorcentaje) "%" else "°", true, alCambiarUnidadAngulo)
                }
                Button(
                    onClick = alConfirmar,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 0.dp
                    ),
                    modifier = Modifier.height(34.dp).defaultMinSize(minWidth = 1.dp)
                ) { Text("✓", fontSize = 14.sp) }
                Chip("✕", false, alCancelar)
            }
        } else {
            // Exportar solo cuando no hay nada a medio poner: así la barra no
            // crece ni se mueve bajo el pulgar mientras se dibuja.
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Chip("PDF", false, alPdf)
                Chip("JPG", false, alJpg)
            }
        }
    }
}
