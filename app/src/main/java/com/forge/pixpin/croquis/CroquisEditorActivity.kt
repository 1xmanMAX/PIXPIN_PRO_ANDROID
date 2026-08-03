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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.AutoFixNormal
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

/**
 * Los iconos son los mismos que usa el editor de anotaciones sobre imagen
 * (`PinWindowController`), para que las dos barras se lean igual.
 */
private enum class Herramienta(
    val etiqueta: String,
    val icono: androidx.compose.ui.graphics.vector.ImageVector
) {
    LINEA("Línea", Icons.Filled.Remove),
    POLILINEA("Polilínea", Icons.Filled.Timeline),
    RECT("Rectángulo", Icons.Filled.CropSquare),
    CIRCULO("Círculo", Icons.Filled.RadioButtonUnchecked),
    COTA("Cota", Icons.Filled.SwapHoriz),

    /** Se toca la línea que corta y luego el trozo que sobra. */
    RECORTAR("Recortar", Icons.Filled.ContentCut),

    /** Se toca la línea a alargar y luego hasta dónde. */
    EXTENDER("Extender", Icons.Filled.OpenInFull),

    /** Un toque sobre algo dibujado y desaparece. */
    BORRADOR("Borrar", Icons.Filled.AutoFixNormal);

    /** Las que trabajan sobre líneas ya dibujadas, no poniendo puntos. */
    val esEdicion: Boolean get() = this == RECORTAR || this == EXTENDER

    /** Las que dibujan trazo, y por tanto tienen grosor y color. */
    val pinta: Boolean get() = !esEdicion && this != BORRADOR
}

/**
 * Qué se está arrastrando.
 *
 * Un índice y no un enum porque los vértices de una polilínea a medio hacer
 * también se agarran, y son tantos como haya: [A] y [B] son los dos puntos
 * sueltos, y cualquier valor >= 0 es la posición dentro de la cadena.
 */
private object Agarre {
    const val NADA = Int.MIN_VALUE
    const val A = -1
    const val B = -2
}

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
                    // CLEAR_TASK además de NEW_TASK, y no es un adorno: con su
                    // propia taskAffinity, una instancia ya existente se trae al
                    // frente en vez de crearse otra. Sin `onCreate`, el editor
                    // seguía enseñando el croquis del pin ANTERIOR y había que
                    // cerrarlo a mano para que leyera el nuevo.
                    //
                    // Vaciar la tarea es seguro porque el editor guarda en cada
                    // cambio: no hay nada pendiente que se pueda perder.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
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
        var grosor by remember { mutableFloatStateOf(1f) }
        /** 0 = la tinta por defecto; cualquier otro, un color elegido. */
        var colorTrazo by remember { mutableIntStateOf(0) }

        var puntoA by remember { mutableStateOf<P?>(null) }
        var puntoB by remember { mutableStateOf<P?>(null) }
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
            imantadoEn = null; señalada = null
        }

        // `confirmar` se define más abajo porque necesita a `limpiar`, pero
        // `tocar` tiene que poder llamarla: este hueco los enlaza.
        var cerrarTrazo: () -> Unit = {}

        /**
         * Qué hace un toque, según la herramienta.
         *
         * Las de edición trabajan sobre líneas ya dibujadas y no ponen puntos;
         * la polilínea encadena; el resto ponen los dos puntos de siempre.
         */
        fun tocar(bruto: P) {
            val tolerancia = 30.0 * densidad / vista.pixelsPorMetro

            if (herramienta == Herramienta.BORRADOR) {
                val i = CroquisGeometria.entidadMasCercana(croquis, bruto, tolerancia)
                if (i == null) { aviso = "Ahí no hay nada que borrar"; return }
                croquis = croquis.copy(
                    entidades = croquis.entidades.filterIndexed { j, _ -> j != i }
                )
                alGuardar(croquis)
                aviso = null
                return
            }

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

            when {
                puntoA == null -> puntoA = colocar(bruto, null)
                puntoB == null -> puntoB = colocar(bruto, puntoA)
                // Con los dos puestos, tocar en un hueco **cierra el trazo**.
                // Antes había que buscar el botón de confirmar; ahora ese botón
                // es solo para cuando además se teclea una medida.
                else -> cerrarTrazo()
            }
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
                    val e = Estilo(grosor = grosor, colorArgb = colorTrazo)
                    val nueva: Entidad = when (herramienta) {
                        Herramienta.LINEA -> Entidad.Linea(a, destino, e)
                        Herramienta.POLILINEA -> Entidad.Polilinea(listOf(a, destino), false, e)
                        Herramienta.RECT -> Entidad.Rect(a, destino, e)
                        Herramienta.CIRCULO ->
                            Entidad.Circulo(a, CroquisGeometria.distancia(a, destino), e)
                        Herramienta.COTA -> Entidad.Cota(a, destino, 0.4, e)
                        // Recortar, extender y borrar actúan al tocar: nunca
                        // llegan aquí con dos puntos puestos.
                        Herramienta.RECORTAR, Herramienta.EXTENDER,
                        Herramienta.BORRADOR -> return
                    }
                    croquis = croquis.copy(entidades = croquis.entidades + nueva)
                    alGuardar(croquis)
                    aviso = null
                }

                Modo.MEDIR -> Unit
            }
            limpiar()
        }
        cerrarTrazo = { confirmar() }

        Column(Modifier.fillMaxSize().background(Color(0xFF14181D))) {

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
                            // Se busca entre los dos puntos sueltos Y los
                            // vértices de la polilínea a medio hacer: retocar
                            // uno antes de cerrarla es lo normal.
                            val radio = radioAgarreM()
                            var agarrado = Agarre.NADA
                            var mejorD = Double.MAX_VALUE
                            fun probar(p: P?, indice: Int) {
                                if (p == null) return
                                val d = CroquisGeometria.distancia(mundoAbajo, p)
                                if (d <= radio && d < mejorD) { mejorD = d; agarrado = indice }
                            }
                            probar(puntoA, Agarre.A)
                            probar(puntoB, Agarre.B)
                            cadena.forEachIndexed { i, p -> probar(p, i) }

                            // Se conserva la separación con la que agarraste, en
                            // vez de teletransportar el punto bajo el dedo. Si
                            // agarraste casi encima, se separa a la fuerza: un
                            // punto debajo de la mano no se ve.
                            var apartado = Offset.Zero
                            if (agarrado != Agarre.NADA) {
                                val p = when {
                                    agarrado == Agarre.A -> puntoA!!
                                    agarrado == Agarre.B -> puntoB!!
                                    else -> cadena[agarrado]
                                }
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
                                    agarrado = Agarre.NADA
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
                                        if (agarrado != Agarre.NADA) {
                                            val destino = Px(
                                                dedo.position.x + apartado.x,
                                                dedo.position.y + apartado.y
                                            )
                                            val bruto = CroquisGeometria.aMundo(
                                                destino, vista, ancho, alto
                                            )
                                            when {
                                                agarrado == Agarre.A ->
                                                    puntoA = colocar(bruto, puntoB)
                                                agarrado == Agarre.B ->
                                                    puntoB = colocar(bruto, puntoA)
                                                else -> {
                                                    val vecino = cadena.getOrNull(agarrado - 1)
                                                    cadena = cadena.toMutableList().also {
                                                        it[agarrado] = colocar(bruto, vecino)
                                                    }
                                                }
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
                grosor = grosor,
                colorTrazo = colorTrazo,
                alGrosor = { grosor = it },
                alColor = { colorTrazo = it }
            )

            BarraPrincipal(
                modo = modo,
                herramienta = herramienta,
                orto = orto,
                hayFondo = croquis.fondo != null,
                puedeDeshacer = croquis.entidades.isNotEmpty(),
                alModo = { modo = it; limpiar(); cadena = emptyList(); aviso = null },
                alHerramienta = { herramienta = it; limpiar(); cadena = emptyList() },
                alOrto = { orto = !orto },
                alDeshacer = {
                    croquis = croquis.copy(entidades = croquis.entidades.dropLast(1))
                    alGuardar(croquis)
                },
                alSalir = { alSalir(croquis) }
            )
        }
    }

    // Exportar ya no vive aquí: el PDF sale desde el propio pin, que es donde
    // se decide qué hacer con el croquis terminado. El editor solo dibuja.
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

/** Botón de icono de la barra, con el mismo tamaño y trato para todos. */
@Composable
private fun Boton(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    descripcion: String,
    activo: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (activo) Color(0xFF29B8DB) else Color(0xFF272D35),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier
            .size(42.dp)
            .pointerInput(descripcion) { detectTapGestures { onClick() } }
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            androidx.compose.material3.Icon(
                icono,
                contentDescription = descripcion,
                tint = if (activo) Color(0xFF06222B) else Color(0xFFC9D2DC),
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

/**
 * La barra principal, **abajo**, como la de anotar una imagen.
 *
 * Todo con iconos y en una sola fila que se desplaza. La fila anterior no se
 * desplazaba, y con más de tres o cuatro botones los últimos quedaban fuera de
 * la pantalla sin manera de alcanzarlos: por eso «no aparecían» ni las
 * herramientas ni la opción de escalar.
 */
@Composable
private fun BarraPrincipal(
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
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B2027))
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Boton(Icons.Filled.Close, "Salir", false, alSalir)
        Separador()
        Boton(Icons.Filled.Edit, "Dibujar", modo == Modo.DIBUJAR) { alModo(Modo.DIBUJAR) }
        Boton(Icons.Filled.SquareFoot, "Medir", modo == Modo.MEDIR) { alModo(Modo.MEDIR) }
        if (hayFondo) {
            Boton(Icons.Filled.ZoomOutMap, "Escalar la imagen", modo == Modo.CALIBRAR) {
                alModo(Modo.CALIBRAR)
            }
        }
        if (modo == Modo.DIBUJAR) {
            Separador()
            Herramienta.entries.forEach { h ->
                Boton(h.icono, h.etiqueta, herramienta == h) { alHerramienta(h) }
            }
            Separador()
            // Cruz de cuatro flechas y no una rejilla: la rejilla se leía como
            // «tabla» y no decía nada de lo que hace, que es obligar al trazo a
            // ir recto en horizontal o en vertical.
            Boton(Icons.Filled.OpenWith, "Recto: horizontal o vertical", orto, alOrto)
            if (puedeDeshacer) Boton(Icons.Filled.Undo, "Deshacer", false, alDeshacer)
        }
    }
}

/** Grosores en múltiplos del trazo normal: fino, normal, grueso. */
private val GROSORES = listOf("─" to 0.6f, "━" to 1f, "▬" to 2f)

/** El 0 es «la tinta por defecto», que se adapta al fondo. */
private val COLORES = listOf(
    0,
    0xFFD61818.toInt(), 0xFF1E88E5.toInt(), 0xFF2E7D32.toInt(),
    0xFFF9A825.toInt(), 0xFF8E24AA.toInt()
)

/** Pastilla de color; la del 0 lleva una diagonal, que es «el color normal». */
@Composable
private fun Muestra(argb: Int, activa: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (argb == 0) Color(0xFF3A424C) else Color(argb),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
        border = if (activa)
            androidx.compose.foundation.BorderStroke(2.dp, Color.White) else null,
        modifier = Modifier
            .size(30.dp)
            .pointerInput(argb) { detectTapGestures { onClick() } }
    ) {
        if (argb == 0) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("A", color = Color(0xFFC9D2DC), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Separador() {
    Box(
        Modifier
            .size(width = 1.dp, height = 26.dp)
            .background(Color(0xFF39414B))
    )
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
    grosor: Float,
    colorTrazo: Int,
    alGrosor: (Float) -> Unit,
    alColor: (Int) -> Unit
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
        } else if (herramienta.pinta && modo == Modo.DIBUJAR) {
            // Grosor y color: aparecen solo con una herramienta que pinte, que
            // es cuando significan algo. El borrador y las de edición no tienen
            // trazo propio.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GROSORES.forEach { (etiqueta, valor) ->
                    Chip(etiqueta, grosor == valor) { alGrosor(valor) }
                }
                Separador()
                COLORES.forEach { argb ->
                    Muestra(argb, colorTrazo == argb) { alColor(argb) }
                }
            }
        }
    }
}
