package com.forge.pixpin.mini

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Las imágenes de una tarea, del lado de Android** (3-oct-2026): copiarlas, enseñarlas en la
 * fila y escribirlas en el campo como chapas `[img 01]`. El formato y sus reglas viven en
 * [Tareas] (sin Android, con sus pruebas); aquí solo lo que necesita ficheros y pantalla.
 *
 * Se adjunta **solo dentro de la aplicación**, en la pantalla de la lista: ni el buscador del
 * teléfono, ni la tarea rápida ([TareaRapidaActivity]), ni un acceso directo. Lo dijo el usuario.
 */
object ImagenesDeTareas {

    /** Mayor que esto de lado, la copia se reduce: como una foto del chat que se mira, no se imprime. */
    const val LADO_MAXIMO = 2000

    /** Las miniaturas ya leídas: 28 dp no merecen leer la foto otra vez cada vez que la fila se pinta. */
    private val miniaturas = LruCache<String, ImageBitmap>(64)

    /**
     * **Siempre una copia propia** en `files/guardados/tarea-<ms>-<nn>.<ext>`: lo de la galería o
     * del portapapeles se borra o se mueve, y la tarea se quedaría sin foto. Hilo de disco.
     */
    fun copiar(context: Context, uri: Uri, carpeta: File, ms: Long, n: Int): File? = runCatching {
        val tipo = context.contentResolver.getType(uri).orEmpty().lowercase()
        // Un GIF tal cual (reducirlo lo dejaría quieto). Lo demás se mira por si hay que reducirlo.
        if ("gif" in tipo) {
            val f = File(carpeta, Tareas.nombreDeCopia(ms, n, "gif"))
            context.contentResolver.openInputStream(uri)!!.use { e -> f.outputStream().use { e.copyTo(it) } }
            return@runCatching f
        }
        val conocida = when {
            "png" in tipo -> "png"
            "webp" in tipo -> "webp"
            "jpeg" in tipo || "jpg" in tipo -> "jpg"
            else -> null
        }
        // Sin leer la foto entera: solo su cabecera, para saber su tamaño.
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        val grande = o.outWidth <= 0 || maxOf(o.outWidth, o.outHeight) > LADO_MAXIMO
        val fuente = ImageDecoder.createSource(context.contentResolver, uri)
        if (!grande && conocida != null) {
            val f = File(carpeta, Tareas.nombreDeCopia(ms, n, conocida))
            context.contentResolver.openInputStream(uri)!!.use { e -> f.outputStream().use { e.copyTo(it) } }
            return@runCatching f
        }
        val png = conocida == "png"
        val f = File(carpeta, Tareas.nombreDeCopia(ms, n, if (png) "png" else "jpg"))
        val bmp = decodificar(fuente, LADO_MAXIMO) ?: return@runCatching null
        f.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        f
    }.getOrNull()

    /** La foto de la cámara, que llega del tamaño del sensor: se reduce en su sitio. Hilo de disco. */
    fun reducirEnSuSitio(f: File) {
        runCatching {
            val bmp = decodificar(ImageDecoder.createSource(f), LADO_MAXIMO) ?: return
            val temporal = File(f.parentFile, f.name + ".nuevo")
            temporal.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            if (!temporal.renameTo(f)) temporal.delete()
        }
    }

    /** La imagen a [lado] píxeles de lado como mucho, ya girada según su EXIF (lo hace el descodificador). */
    private fun decodificar(fuente: ImageDecoder.Source, lado: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(fuente) { d, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val mayor = maxOf(w, h)
            if (mayor > lado) {
                val k = lado.toFloat() / mayor
                d.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
            }
            // Software: se va a comprimir o a pintar escalado; uno de hardware no se deja leer.
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    /** La miniatura de [ruta] a [lado] píxeles. Hilo de disco. */
    fun miniatura(ruta: String, lado: Int): ImageBitmap? {
        val clave = "$ruta@$lado"
        miniaturas.get(clave)?.let { return it }
        val f = File(ruta)
        if (!f.isFile) return null
        return decodificar(ImageDecoder.createSource(f), lado)?.asImageBitmap()?.also { miniaturas.put(clave, it) }
    }

    /** Para el visor: grande, pero sin pasar de lo que cabe en memoria con holgura. Hilo de disco. */
    fun paraVer(ruta: String): ImageBitmap? {
        val f = File(ruta)
        if (!f.isFile) return null
        return decodificar(ImageDecoder.createSource(f), 2560)?.asImageBitmap()
    }

    /** Lo que se dice al tocar el hueco de una imagen que aún no ha llegado. */
    fun avisarQueFalta(context: Context) {
        Toast.makeText(context, "Esa imagen aún no está en este equipo: llegará con la sincronización", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Un campo donde se escribe una tarea **con sus imágenes**: el texto con las fichas `[img NN]`
 * y qué imagen es cada número. Lo usan la línea de añadir y el campo de corregir.
 *
 * [nuevas] son las copias hechas mientras se escribía: si al final no van en ninguna tarea
 * (se borró su chapa, se vació el campo, se salió sin añadir) se borran, para no dejar fotos
 * huérfanas en `guardados/`. Las que ya tenía la tarea al corregirla no se tocan: son de la
 * tarea guardada (y quizá del PC).
 */
class CampoConImagenes(private val alcance: CoroutineScope) {
    val estado = TextFieldState()
    val imagenes = mutableStateListOf<Pair<Int, String>>()
    private val nuevas = HashSet<String>()

    /** Retroceso dentro de una chapa: se va entera. Ver [Tareas.sinFichaRota]. */
    val retroceso = InputTransformation {
        val antes = originalText.toString()
        val despues = asCharSequence().toString()
        Tareas.sinFichaRota(antes, despues, imagenes.map { it.first })?.let { (t, cursor) ->
            replace(0, length, t)
            selection = TextRange(cursor)
        }
    }

    val texto: String get() = estado.text.toString()

    /** Mete una imagen ya copiada en el cursor, con su chapa. */
    fun meter(ruta: String, nueva: Boolean = true) {
        val numero = Tareas.siguienteNumero(imagenes)
        imagenes += numero to ruta
        if (nueva) nuevas += ruta
        estado.edit {
            val (t, cursor) = Tareas.meterFicha(asCharSequence().toString(), selection.start, numero)
            replace(0, length, t)
            selection = TextRange(cursor)
        }
    }

    /** Empieza a corregir [visible]: sus imágenes pasan a chapas. */
    fun cargar(visible: String) {
        descartar()
        val (t, imgs) = Tareas.aFichas(visible)
        imagenes.addAll(imgs)
        estado.edit { replace(0, length, t); selection = TextRange(t.length) }
    }

    /**
     * El texto que se guarda: cada chapa que sigue en el campo cambiada por su `![img NN](ruta)`.
     * Las copias nuevas que se quedaron sin chapa se borran, y el campo se vacía.
     */
    fun componer(): String {
        val t = texto
        val van = Tareas.conFicha(t, imagenes)
        val salida = Tareas.fichasAImagenes(t, van)
        borrar(nuevas - van.map { it.second }.toSet())
        nuevas.clear()
        imagenes.clear()
        estado.edit { replace(0, length, "") }
        return salida
    }

    /** Sin añadir nada: se vacía y se borran las copias nuevas. */
    fun descartar() {
        borrar(nuevas.toSet())
        nuevas.clear()
        imagenes.clear()
        estado.edit { replace(0, length, "") }
    }

    /** Quita la chapa de la imagen [numero] (su aspa encima del campo). */
    fun quitar(numero: Int) {
        val f = Tareas.fichaDeImagen(numero)
        estado.edit {
            val s = asCharSequence().toString()
            val i = s.indexOf(f)
            if (i >= 0) replace(i, i + f.length, "")
        }
    }

    private fun borrar(rutas: Set<String>) {
        if (rutas.isEmpty()) return
        alcance.launch(Dispatchers.IO) { rutas.forEach { runCatching { File(it).delete() } } }
    }
}

/**
 * El campo de escribir una tarea: las chapas `[img 01]` pintadas detrás de su ficha (texto en
 * su sitio, fondo `primary` al 24 % y esquinas de 5 dp), y **pegar una imagen** —pulsación larga
 * → Pegar, o el portapapeles de imágenes de Gboard— la adjunta igual que el clip. El texto
 * pegado sigue entrando como texto.
 *
 * Las chapas se buscan **al pintar**, no al componer: escribir una letra no recompone nada.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CampoDeTarea(
    campo: CampoConImagenes,
    placeholder: String,
    alPegarImagenes: (List<Uri>) -> Unit,
    alHecho: () -> Unit,
    modifier: Modifier = Modifier
) {
    var capa by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    val desplazamiento = rememberScrollState()
    val colorDeChapa = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val esquina = with(LocalDensity.current) { 5.dp.toPx() }
    val pegar by rememberUpdatedState(alPegarImagenes)
    val receptor = remember {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                if (!transferableContent.hasMediaType(MediaType.Image)) return transferableContent
                val uris = ArrayList<Uri>()
                val resto = transferableContent.consume { item -> item.uri?.let { uris += it; true } ?: false }
                if (uris.isNotEmpty()) pegar(uris)
                return resto
            }
        }
    }
    BasicTextField(
        state = campo.estado,
        modifier = modifier.contentReceiver(receptor),
        inputTransformation = campo.retroceso,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onKeyboardAction = { alHecho() },
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 5),
        onTextLayout = { leer -> capa = leer },
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        scrollState = desplazamiento,
        decorator = TextFieldDecorator { dentro ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (campo.estado.text.isEmpty()) {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                }
                Box(
                    Modifier.drawBehind {
                        val l = capa?.invoke() ?: return@drawBehind
                        val texto = campo.estado.text.toString()
                        val arriba = desplazamiento.value.toFloat()
                        for (r in Tareas.fichasEn(texto, campo.imagenes.map { it.first })) {
                            if (r.last >= l.layoutInput.text.length) continue
                            val a = l.getBoundingBox(r.first)
                            val b = l.getBoundingBox(r.last)
                            // Una chapa partida entre dos renglones se pinta en el primero.
                            val fin = if (b.top == a.top) b.right else l.getLineRight(l.getLineForOffset(r.first))
                            drawRoundRect(
                                colorDeChapa,
                                topLeft = Offset(a.left - 2f, a.top - arriba),
                                size = androidx.compose.ui.geometry.Size(fin - a.left + 4f, a.height),
                                cornerRadius = CornerRadius(esquina)
                            )
                        }
                    }
                ) { dentro() }
            }
        }
    )
}

/**
 * Una imagen de la fila: 28 dp, recortada al centro y con esquinas de 4 dp. Sin fichero —aún no
 * ha llegado del PC— es el hueco con su icono, y tocarlo lo dice.
 */
@Composable
fun MiniaturaDeTarea(ruta: String?, apagada: Boolean, alTocar: (String) -> Unit) {
    val contexto = LocalContext.current
    val lado = with(LocalDensity.current) { 28.dp.roundToPx() }
    // Mientras se lee, tocar no dice que falta: solo cuando ya se sabe que no está.
    var leida by remember(ruta) { mutableStateOf(false) }
    val imagen by produceState<ImageBitmap?>(null, ruta) {
        value = ruta?.let { withContext(Dispatchers.IO) { ImagenesDeTareas.miniatura(it, lado * 2) } }
        leida = true
    }
    val forma = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(28.dp)
            .graphicsLayer { alpha = if (apagada) 0.55f else 1f }
            .clip(forma)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                val r = ruta
                if (r != null && imagen != null) alTocar(r) else if (leida) ImagenesDeTareas.avisarQueFalta(contexto)
            },
        contentAlignment = Alignment.Center
    ) {
        val i = imagen
        if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/**
 * La imagen a pantalla completa, con pellizco para ampliar: doble toque vuelve a su tamaño,
 * un toque la cierra. La escala y el desplazamiento se leen **en la capa**, al pintar: pellizcar
 * no recompone nada.
 */
@Composable
fun VisorDeImagenDeTarea(ruta: String, alCerrar: () -> Unit) {
    val imagen by produceState<ImageBitmap?>(null, ruta) {
        value = withContext(Dispatchers.IO) { ImagenesDeTareas.paraVer(ruta) }
    }
    var escala by remember { mutableFloatStateOf(1f) }
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = alCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { escala = 1f; x = 0f; y = 0f },
                        onTap = { alCerrar() }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        escala = (escala * zoom).coerceIn(1f, 8f)
                        x += pan.x; y += pan.y
                        if (escala == 1f) { x = 0f; y = 0f }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            imagen?.let {
                Image(
                    it, null,
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = escala; scaleY = escala
                        translationX = x; translationY = y
                    },
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
