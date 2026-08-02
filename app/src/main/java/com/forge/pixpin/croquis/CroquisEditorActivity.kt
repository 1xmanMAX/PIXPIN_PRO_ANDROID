package com.forge.pixpin.croquis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.atan2

/** Qué se está haciendo con el dedo. */
private enum class Modo { DIBUJAR, MEDIR, CALIBRAR }

/** Con qué se dibuja. */
private enum class Herramienta { LINEA, POLILINEA, RECT, CIRCULO, COTA }

/**
 * El editor del croquis, a **pantalla completa**.
 *
 * No vive en el pin flotante a propósito: una hoja infinita y una barra de
 * entrada numérica no caben en una ventana pequeña, y la precisión que se le
 * pide a esto necesita sitio. El pin sigue enseñando la vista reducida.
 *
 * `taskAffinity` propia, como el resto de actividades que se lanzan desde el
 * overlay: sin ella, Android trae toda la tarea de PixPin al frente.
 */
class CroquisEditorActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_RUTA = "croquis_ruta"
        private const val EXTRA_ID = "croquis_id"
        private const val EXTRA_FONDO = "croquis_fondo"

        /** Abre el editor; [fondoPath] es la captura sobre la que medir, si la hay. */
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

        // Una captura recién traída llega sin calibrar: el fondo se registra ya,
        // con escala provisional, y el usuario la fija con la regla.
        val inicial = if (fondoPath != null && cargado.fondo == null) {
            cargado.copy(fondo = Fondo(fondoPath, P(0.0, 0.0), metrosPorPixel = 0.01))
        } else cargado

        setContent {
            PixPinTheme {
                Editor(
                    inicial = inicial,
                    fondo = fondoBitmap,
                    alGuardar = { guardar(it) },
                    alSalir = { guardar(it); finish() }
                )
            }
        }
    }

    private fun guardar(croquis: Croquis) {
        val ruta = CroquisStore.guardar(this, croquisId, croquis)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RUTA, ruta))
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

        // Los dos puntos en juego: sirven para la linea pendiente, para la
        // medicion efimera y para la regla de calibrar.
        var puntoA by remember { mutableStateOf<P?>(null) }
        var puntoB by remember { mutableStateOf<P?>(null) }
        var campoLongitud by remember { mutableStateOf("") }
        var aviso by remember { mutableStateOf<String?>(null) }

        var lienzoAncho by remember { mutableStateOf(1) }
        var lienzoAlto by remember { mutableStateOf(1) }

        /** Imanta y, en línea, endereza. La tolerancia se pasa de dp a metros. */
        fun ajustar(bruto: P, desde: P?): P {
            val toleranciaM = 14.0 / vista.pixelsPorMetro
            var p = CroquisGeometria.imantar(bruto, croquis.extremos(), toleranciaM)
            if (desde != null && p == bruto) p = CroquisGeometria.orto(desde, p, 5.0)
            return p
        }

        fun confirmar() {
            val a = puntoA
            val b = puntoB
            if (a == null || b == null) return
            when (modo) {
                Modo.CALIBRAR -> {
                    val metros = campoLongitud.replace(',', '.').toDoubleOrNull()
                    val f = croquis.fondo
                    val escala = if (metros == null || f == null) null else {
                        // Los puntos estan en metros de la escala provisional:
                        // se vuelven a pixeles de imagen para recalibrar.
                        val px = CroquisGeometria.distancia(a, b) / f.metrosPorPixel
                        if (px > 0) metros / px else null
                    }
                    if (escala == null) {
                        aviso = "Escala no válida: traza la regla y escribe cuánto mide"
                    } else {
                        croquis = croquis.copy(fondo = f!!.copy(metrosPorPixel = escala))
                        aviso = "Calibrado: 1 px = ${"%.4f".format(escala)} m"
                        modo = Modo.MEDIR
                    }
                }

                Modo.DIBUJAR -> {
                    val metros = campoLongitud.replace(',', '.').toDoubleOrNull()
                    val destino = if (metros != null) {
                        CroquisGeometria.conLongitud(a, b, metros) ?: b
                    } else b
                    val nueva: Entidad = when (herramienta) {
                        Herramienta.LINEA -> Entidad.Linea(a, destino)
                        Herramienta.POLILINEA -> Entidad.Polilinea(listOf(a, destino))
                        Herramienta.RECT -> Entidad.Rect(a, destino)
                        Herramienta.CIRCULO ->
                            Entidad.Circulo(a, CroquisGeometria.distancia(a, destino))
                        Herramienta.COTA -> Entidad.Cota(a, destino, desplazamiento = 0.4)
                    }
                    croquis = croquis.copy(entidades = croquis.entidades + nueva)
                    alGuardar(croquis)
                }

                Modo.MEDIR -> Unit
            }
            puntoA = null
            puntoB = null
            campoLongitud = ""
        }

        Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {

            BarraSuperior(
                modo = modo,
                herramienta = herramienta,
                hayFondo = croquis.fondo != null,
                alCambiarModo = { modo = it; puntoA = null; puntoB = null; campoLongitud = "" },
                alCambiarHerramienta = { herramienta = it },
                alDeshacer = {
                    if (croquis.entidades.isNotEmpty()) {
                        croquis = croquis.copy(entidades = croquis.entidades.dropLast(1))
                        alGuardar(croquis)
                    }
                },
                alExportar = { exportar(croquis, it) { m -> aviso = m } },
                alSalir = { alSalir(croquis) }
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White)
                    // Dos dedos siempre desplazan y amplian, en cualquier modo:
                    // moverse por la hoja no puede depender de la herramienta.
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val ppm = (vista.pixelsPorMetro * zoom).coerceIn(0.01, 500_000.0)
                            vista = Vista(
                                centro = P(
                                    vista.centro.x - pan.x / ppm,
                                    vista.centro.y + pan.y / ppm
                                ),
                                pixelsPorMetro = ppm
                            )
                        }
                    }
                    .pointerInput(modo, herramienta, croquis) {
                        detectTapGestures { offset ->
                            val bruto = CroquisGeometria.aMundo(
                                Px(offset.x, offset.y), vista, lienzoAncho, lienzoAlto
                            )
                            if (puntoA == null) {
                                puntoA = ajustar(bruto, null)
                            } else {
                                puntoB = ajustar(bruto, puntoA)
                                if (modo == Modo.MEDIR) campoLongitud = ""
                            }
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    lienzoAncho = size.width.toInt()
                    lienzoAlto = size.height.toInt()
                    drawIntoCanvas { lienzo ->
                        val nativo = lienzo.nativeCanvas
                        CroquisRenderer.dibujar(
                            nativo, croquis, vista, lienzoAncho, lienzoAlto, fondo, AColor.BLACK
                        )
                        // Lo pendiente se pinta aparte y en otro color: aun no
                        // forma parte del croquis.
                        val a = puntoA
                        val b = puntoB
                        if (a != null) {
                            val pa = CroquisGeometria.aPantalla(a, vista, lienzoAncho, lienzoAlto)
                            val tinta = android.graphics.Paint().apply {
                                color = AColor.rgb(0, 140, 255)
                                strokeWidth = 3f
                                isAntiAlias = true
                            }
                            nativo.drawCircle(pa.x, pa.y, 9f, tinta)
                            if (b != null) {
                                val pb = CroquisGeometria.aPantalla(
                                    b, vista, lienzoAncho, lienzoAlto
                                )
                                nativo.drawLine(pa.x, pa.y, pb.x, pb.y, tinta)
                                nativo.drawCircle(pb.x, pb.y, 9f, tinta)
                            }
                        }
                    }
                }
            }

            BarraInferior(
                modo = modo,
                puntoA = puntoA,
                puntoB = puntoB,
                decimales = croquis.decimales,
                campo = campoLongitud,
                alEscribir = { campoLongitud = it },
                alConfirmar = { confirmar() },
                alCancelar = { puntoA = null; puntoB = null; campoLongitud = "" },
                aviso = aviso
            )
        }
    }

    private fun exportar(croquis: Croquis, comoPdf: Boolean, avisar: (String) -> Unit) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (comoPdf) {
                    CroquisExport.aPdf(this@CroquisEditorActivity, croquis, fondoBitmap) != null
                } else {
                    val bmp = CroquisExport.aBitmap(croquis, fondoBitmap) ?: return@withContext false
                    val uri = Export.saveToGallery(
                        this@CroquisEditorActivity, bmp, Bitmap.CompressFormat.JPEG, 92
                    )
                    if (!bmp.isRecycled) bmp.recycle()
                    uri != null
                }
            }
            avisar(
                if (ok) (if (comoPdf) "PDF guardado en Descargas" else "JPG guardado en la galería")
                else "No hay nada que exportar"
            )
        }
    }
}

@Composable
private fun BarraSuperior(
    modo: Modo,
    herramienta: Herramienta,
    hayFondo: Boolean,
    alCambiarModo: (Modo) -> Unit,
    alCambiarHerramienta: (Herramienta) -> Unit,
    alDeshacer: () -> Unit,
    alExportar: (Boolean) -> Unit,
    alSalir: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = alSalir) { Text("‹ Salir") }
            FilterChip(
                selected = modo == Modo.DIBUJAR,
                onClick = { alCambiarModo(Modo.DIBUJAR) },
                label = { Text("Dibujar") }
            )
            FilterChip(
                selected = modo == Modo.MEDIR,
                onClick = { alCambiarModo(Modo.MEDIR) },
                label = { Text("Medir") }
            )
            if (hayFondo) {
                FilterChip(
                    selected = modo == Modo.CALIBRAR,
                    onClick = { alCambiarModo(Modo.CALIBRAR) },
                    label = { Text("Calibrar") }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // En modo medir las herramientas desaparecen: el dibujo esta
            // deshabilitado de verdad, no solo ignorado.
            if (modo == Modo.DIBUJAR) {
                Herramienta.entries.forEach { h ->
                    FilterChip(
                        selected = herramienta == h,
                        onClick = { alCambiarHerramienta(h) },
                        label = { Text(etiqueta(h), fontSize = 12.sp) }
                    )
                }
                TextButton(onClick = alDeshacer) { Text("Deshacer") }
            }
            TextButton(onClick = { alExportar(true) }) { Text("PDF") }
            TextButton(onClick = { alExportar(false) }) { Text("JPG") }
        }
    }
}

private fun etiqueta(h: Herramienta): String = when (h) {
    Herramienta.LINEA -> "Línea"
    Herramienta.POLILINEA -> "Polilínea"
    Herramienta.RECT -> "Rect"
    Herramienta.CIRCULO -> "Círculo"
    Herramienta.COTA -> "Cota"
}

@Composable
private fun BarraInferior(
    modo: Modo,
    puntoA: P?,
    puntoB: P?,
    decimales: Int,
    campo: String,
    alEscribir: (String) -> Unit,
    alConfirmar: () -> Unit,
    alCancelar: () -> Unit,
    aviso: String?
) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        val medida = if (puntoA != null && puntoB != null) {
            CroquisGeometria.distancia(puntoA, puntoB)
        } else null
        val angulo = if (puntoA != null && puntoB != null) {
            Math.toDegrees(atan2(puntoB.y - puntoA.y, puntoB.x - puntoA.x))
        } else null

        Text(
            when {
                aviso != null -> aviso
                modo == Modo.MEDIR && medida != null ->
                    "Distancia: " + CroquisGeometria.formatear(medida, decimales)
                modo == Modo.CALIBRAR && puntoA == null -> "Toca el primer punto de la regla"
                modo == Modo.CALIBRAR -> "Toca el segundo y escribe cuánto mide de verdad"
                puntoA == null -> "Toca dónde empieza"
                medida != null -> "Longitud: " + CroquisGeometria.formatear(medida, decimales) +
                    "   ·   " + "%.1f°".format(angulo)
                else -> "Toca dónde acaba"
            },
            color = Color(0xFFDDE3EA),
            fontSize = 13.sp
        )

        if (puntoA != null && puntoB != null && modo != Modo.MEDIR) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = campo,
                    onValueChange = alEscribir,
                    label = { Text(if (modo == Modo.CALIBRAR) "Mide (m)" else "Longitud (m)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(170.dp),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = alConfirmar) { Text("Confirmar") }
                TextButton(onClick = alCancelar) { Text("Cancelar") }
            }
        }
    }
}
