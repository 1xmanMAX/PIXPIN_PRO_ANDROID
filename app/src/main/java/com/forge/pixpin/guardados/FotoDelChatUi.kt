package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.motor.Proyecto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * **La foto del chat en la pantalla** (25-oct-2026, lo del PC `22bf4dc`): pintarla en todos los
 * sitios del avatar del chat —la lista de chats, la cabecera del chat, la ficha del chat y sus
 * filas— y la hoja para cambiarla, que se abre manteniendo pulsado un chat de la lista («Cambiar
 * foto del chat…») o tocando el avatar de arriba de la ficha del chat. Lo puro, el formato y las
 * cuentas están en [FotoDelChat].
 */
object FotosDelChat {
    /** Las imágenes ya leídas, por ruta. Una ruta nueva es una foto nueva (`avatar-<ms>.png`). */
    private val leidas = ConcurrentHashMap<String, ImageBitmap>()

    /** La imagen de una foto `imagen`, o nulo si aún no está (no llegó por la sincronización). Desde un hilo de disco. */
    fun leer(ruta: String): ImageBitmap? {
        leidas[ruta]?.let { return it }
        val f = File(ruta)
        if (!f.isFile) return null
        val b = runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() ?: return null
        leidas[ruta] = b
        return b
    }

    fun enMemoria(ruta: String?): ImageBitmap? = ruta?.let { leidas[it] }

    /** El proyecto de un chat, de la lista que ya está en memoria. */
    fun proyectoDe(context: Context, id: String?): Proyecto? =
        if (id == null) null else (context.applicationContext as? PixPinApp)?.proyectos?.porId(id)

    /**
     * **Guarda lo elegido en la hoja.** Con una imagen nueva, se recorta su cuadrado, se reduce a
     * [FotoDelChat.LADO] y se escribe entera o nada como `files/guardados/avatar-<ms>.png`. El
     * proyecto queda dado por tocado ([FotoDelChat.conFoto]) y la imagen vieja se va sola
     * ([com.forge.pixpin.sincro.Disco.borrarFotosSobrantes]). Devuelve si escribió algo. Desde un hilo de disco.
     */
    fun guardar(context: Context, proyecto: String, estado: FotoDelChat.Estado, imagen: Bitmap?): Boolean {
        val app = context.applicationContext as? PixPinApp ?: return false
        val p = app.proyectos.porId(proyecto) ?: return false
        val cuando = System.currentTimeMillis()
        val e = estado.eleccion
        val avatar = if (e is FotoDelChat.Eleccion.Nueva && estado.tocado) {
            val b = imagen ?: return false
            val (x, y, lado) = e.recorte.enPixeles(b.width, b.height)
            val cuadrada = Bitmap.createBitmap(b, x, y, lado, lado)
            val final = minOf(lado, FotoDelChat.LADO)
            val reducida = if (final < lado) Bitmap.createScaledBitmap(cuadrada, final, final, true) else cuadrada
            val destino = File(context.filesDir, FotoDelChat.rutaRelativaDeImagen(cuando)).also { it.parentFile?.mkdirs() }
            val tmp = File(destino.parentFile, destino.name + ".tmp")
            tmp.outputStream().use { reducida.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(destino)) { tmp.delete(); return false }
            FotoDelChat.imagen(destino.absolutePath, cuando)
        } else estado.aGuardar(cuando) ?: return false
        app.proyectos.guardar(FotoDelChat.conFoto(p, avatar, cuando))
        // El logo de antes, que era solo de este aparato, ya no hace falta: manda la foto.
        LogoDelProyecto.quitar(context, proyecto)
        return true
    }

    /**
     * **El logo de antes empieza a viajar** (como el `logo.png` del PC, `de_ficha_o_logo`): un
     * proyecto sin foto y con su logo de `files/logos/` ([LogoDelProyecto]) pasa a tener esa imagen
     * de foto del chat, y el logo se va. Sin dar el proyecto por tocado: cambia en un solo lado, así
     * que pasa igual al sincronizar. Se llama al arrancar, desde un hilo de disco.
     */
    fun adoptarLogosDeAntes(context: Context) {
        val app = context.applicationContext as? PixPinApp ?: return
        for (p in app.proyectos.proyectos.value) {
            if (p.avatar != null || !LogoDelProyecto.tiene(context, p.id)) continue
            val cuando = System.currentTimeMillis()
            val destino = File(context.filesDir, FotoDelChat.rutaRelativaDeImagen(cuando)).also { it.parentFile?.mkdirs() }
            val hecho = runCatching { LogoDelProyecto.archivo(context, p.id).copyTo(destino, overwrite = false) }.isSuccess
            if (!hecho) continue
            app.proyectos.guardar(p.copy(avatar = FotoDelChat.imagen(destino.absolutePath, cuando).aJson()))
            LogoDelProyecto.quitar(context, p.id)
            // Dos en el mismo milisegundo se pisarían el nombre.
            Thread.sleep(2)
        }
    }

    /**
     * Una foto elegida (de la galería o pegada), derecha según su EXIF y reducida para trabajar con
     * ella: el lado mayor a 2048 como mucho, como `LADO_DE_TRABAJO` del PC. Nula si no se lee.
     */
    fun leerParaRecortar(context: Context, uri: Uri): Bitmap? = runCatching {
        val r = context.contentResolver
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medidas) }
        if (medidas.outWidth <= 0 || medidas.outHeight <= 0) return null
        var muestra = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / (muestra * 2) >= LADO_DE_TRABAJO) muestra *= 2
        val leida = r.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
        } ?: return null
        val orientacion = runCatching {
            r.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: android.media.ExifInterface.ORIENTATION_NORMAL
        val derecha = FotoDerecha.girada(leida, GiroExif.de(orientacion))
        val mayor = maxOf(derecha.width, derecha.height)
        if (mayor <= LADO_DE_TRABAJO) derecha
        else {
            val k = LADO_DE_TRABAJO.toFloat() / mayor
            Bitmap.createScaledBitmap(derecha, maxOf(1, Math.round(derecha.width * k)), maxOf(1, Math.round(derecha.height * k)), true)
        }
    }.getOrNull()

    fun leerParaRecortar(file: File): Bitmap? = runCatching {
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, medidas)
        if (medidas.outWidth <= 0 || medidas.outHeight <= 0) return null
        var muestra = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / (muestra * 2) >= LADO_DE_TRABAJO) muestra *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = muestra })
    }.getOrNull()

    /** Lo más grande con que se trabaja una foto elegida (el PC, igual). */
    private const val LADO_DE_TRABAJO = 2048

    /** La imagen copiada (una imagen o el fichero de una imagen), si hay. */
    fun uriDelPortapapeles(context: Context): Uri? = runCatching {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        val clip = cm?.primaryClip ?: return null
        val esImagen = (0 until clip.description.mimeTypeCount).any { clip.description.getMimeType(it).startsWith("image/") }
        (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }?.takeIf {
            esImagen || context.contentResolver.getType(it)?.startsWith("image/") == true
        }
    }.getOrNull()
}

/** `0xRRGGBB` a color de Compose. */
private fun color(c: Int) = Color(0xFF000000.toInt() or c)

/** El degradado de arriba abajo de un fondo. */
private fun brocha(f: Pair<Int, Int>) = Brush.verticalGradient(listOf(color(f.first), color(f.second)))

/** La foto del chat [proyecto] tal como está ahora en la lista de proyectos (se recompone al cambiar). */
@Composable
fun fotoDe(proyecto: String?): FotoDelChat.Avatar? {
    if (proyecto == null) return null
    val app = LocalContext.current.applicationContext as? PixPinApp ?: return null
    val lista by app.proyectos.proyectos.collectAsState()
    return remember(lista, proyecto) { FotoDelChat.de(lista.firstOrNull { it.id == proyecto }) }
}

/**
 * **El círculo de un chat con su foto**, en cualquier sitio y tamaño: la imagen recortada al
 * círculo; el emoji grande (~52 % del círculo) sobre su degradado; las iniciales en blanco sobre el
 * degradado elegido. Sin foto (o con una imagen que aún no llegó por la sincronización, que se
 * vuelve a mirar cada 3 s) se pinta [siempre] sobre [fondoDeSiempre]: lo de cada sitio. La chapa de
 * chat compartido la pone quien llama, **encima**.
 */
@Composable
fun CirculoDelChat(
    proyecto: String?,
    nombre: String,
    tamano: Dp,
    fondoDeSiempre: Color,
    modifier: Modifier = Modifier,
    siempre: @Composable BoxScope.() -> Unit
) {
    val a = fotoDe(proyecto)
    val ruta = a?.takeIf { it.esImagen }?.ruta
    val imagen by produceState(FotosDelChat.enMemoria(ruta), ruta) {
        value = FotosDelChat.enMemoria(ruta)
        if (ruta == null) return@produceState
        while (true) {
            val leida = withContext(Dispatchers.IO) { FotosDelChat.leer(ruta) }
            value = leida
            if (leida != null) break
            delay(3_000)
        }
    }
    val fondo = a?.fondo
    val emoji = a?.elEmoji
    val densidad = LocalDensity.current
    val conFondo = if (imagen == null && a != null && !a.esImagen) fondo else null
    Box(
        modifier.size(tamano).clip(CircleShape)
            .then(if (conFondo != null) Modifier.background(brocha(conFondo)) else Modifier.background(fondoDeSiempre)),
        contentAlignment = Alignment.Center
    ) {
        val img = imagen
        when {
            img != null -> Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            a == null || a.esImagen -> siempre()
            emoji != null -> Text(emoji, fontSize = with(densidad) { (tamano * 0.52f).toSp() }, textAlign = TextAlign.Center)
            fondo != null -> Text(
                iniciales(nombre), color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = with(densidad) { (tamano * 0.36f).toSp() }
            )
            else -> siempre()
        }
    }
}

/**
 * **La hoja para cambiar la foto del chat** (la del PC, `ventana_chat/foto_del_chat.rs`, hecha a
 * dedo): el círculo grande con lo que quedará —con una imagen, arrastrar la mueve y pellizcar
 * acerca; lo que se ve es lo que se guarda ([FotoDelChat.Recorte])—, «Elegir imagen…» (la
 * galería), «Pegar imagen» y «Como siempre»; los doce fondos ([FotoDelChat.FONDOS]); la rejilla de
 * emojis con su buscador (en castellano o en inglés, sin tildes y por el principio de las
 * palabras); y Cancelar y Guardar. Guardar sin haber tocado nada no escribe nada. [conImagen]: se
 * abre ya con esa foto (la de un mensaje, «Usar de foto del chat»).
 */
@Composable
fun HojaDeLaFotoDelChat(proyecto: String, nombre: String, conImagen: File? = null, onCerrar: () -> Unit) {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    val inicial = remember(proyecto) { FotoDelChat.de(FotosDelChat.proyectoDe(contexto, proyecto)) }
    val codigo = remember(proyecto) { FotosDelChat.proyectoDe(contexto, proyecto)?.let { com.forge.pixpin.sincro.Codigos.unico(it) } ?: proyecto }
    var estado by remember(proyecto) { mutableStateOf(FotoDelChat.Estado.de(inicial)) }
    var imagen by remember(proyecto) { mutableStateOf<Bitmap?>(null) }
    var busqueda by remember { mutableStateOf("") }
    var guardando by remember { mutableStateOf(false) }

    fun ponerImagen(b: Bitmap?) {
        if (b == null) { Toast.makeText(contexto, R.string.foto_chat_sin_imagen, Toast.LENGTH_SHORT).show(); return }
        imagen = b
        estado = estado.conImagen(b.width, b.height)
    }
    androidx.compose.runtime.LaunchedEffect(conImagen) {
        val f = conImagen ?: return@LaunchedEffect
        ponerImagen(withContext(Dispatchers.IO) { FotosDelChat.leerParaRecortar(f) })
    }
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) alcance.launch { ponerImagen(withContext(Dispatchers.IO) { FotosDelChat.leerParaRecortar(contexto, uri) }) }
    }

    Dialog(onDismissRequest = onCerrar) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.foto_chat_titulo), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                VistaDeLaFoto(estado, imagen, inicial, nombre) { cambio -> estado = estado.conRecorte(cambio) }
                if (estado.eleccion is FotoDelChat.Eleccion.Nueva) Text(
                    stringResource(R.string.foto_chat_recorte), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(stringResource(R.string.foto_chat_imagen))
                    }
                    TextButton(onClick = {
                        val uri = FotosDelChat.uriDelPortapapeles(contexto)
                        if (uri == null) Toast.makeText(contexto, R.string.foto_chat_sin_imagen, Toast.LENGTH_SHORT).show()
                        else alcance.launch { ponerImagen(withContext(Dispatchers.IO) { FotosDelChat.leerParaRecortar(contexto, uri) }) }
                    }) { Text(stringResource(R.string.foto_chat_pegar)) }
                    TextButton(onClick = { estado = estado.comoSiempre(); imagen = null }) { Text(stringResource(R.string.foto_chat_quitar)) }
                }
                Text(
                    stringResource(R.string.foto_chat_fondo), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FotoDelChat.FONDOS.forEachIndexed { i, f ->
                        val puesto = estado.fondo == (f.arriba to f.abajo)
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(brocha(f.arriba to f.abajo))
                                .then(if (puesto) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { estado = estado.conFondo(i) }
                        )
                    }
                }
                OutlinedTextField(
                    busqueda, { busqueda = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.foto_chat_buscar)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                val encontrados = remember(busqueda) { FotoDelChat.buscarEmojis(busqueda) }
                Box(Modifier.fillMaxWidth().height(200.dp).padding(top = 6.dp)) {
                    if (encontrados.isEmpty()) Text(
                        stringResource(R.string.foto_chat_sin_emojis), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.fillMaxSize()) {
                        items(encontrados) { i ->
                            val e = FotoDelChat.EMOJIS[i].first
                            val puesto = (estado.eleccion as? FotoDelChat.Eleccion.ConEmoji)?.emoji == e
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (puesto) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable { estado = estado.conEmoji(e, codigo); imagen = null },
                                contentAlignment = Alignment.Center
                            ) { Text(e, fontSize = 24.sp) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCerrar) { Text(stringResource(R.string.foto_chat_cancelar)) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(enabled = !guardando, onClick = {
                        if (!estado.tocado) onCerrar()
                        else {
                            guardando = true
                            val queda = estado
                            val img = imagen
                            alcance.launch {
                                val hecho = withContext(Dispatchers.IO) { runCatching { FotosDelChat.guardar(contexto, proyecto, queda, img) }.getOrDefault(false) }
                                if (!hecho) Toast.makeText(contexto, R.string.foto_chat_error, Toast.LENGTH_SHORT).show()
                                onCerrar()
                            }
                        }
                    }) { Text(stringResource(R.string.foto_chat_guardar), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** El círculo grande de la hoja con lo que quedará; con una imagen nueva, se mueve y se acerca. */
@Composable
private fun VistaDeLaFoto(
    estado: FotoDelChat.Estado,
    imagen: Bitmap?,
    inicial: FotoDelChat.Avatar?,
    nombre: String,
    onRecorte: ((FotoDelChat.Recorte) -> FotoDelChat.Recorte) -> Unit
) {
    val tamano = 150.dp
    val densidad = LocalDensity.current
    val ladoPx = with(densidad) { tamano.toPx() }
    val e = estado.eleccion
    val nueva = e as? FotoDelChat.Eleccion.Nueva
    val fondo = estado.fondo
    val img = remember(imagen) { imagen?.asImageBitmap() }
    Box(
        Modifier.size(tamano).clip(CircleShape)
            .background(if (fondo != null && nueva == null && e != FotoDelChat.Eleccion.LaDeAntes) brocha(fondo) else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primaryContainer)))
            .then(
                if (nueva != null) Modifier.pointerInput(nueva.ancho, nueva.alto) {
                    detectTransformGestures { centro, mover, zoom, _ ->
                        onRecorte { r ->
                            r.movido(nueva.ancho, nueva.alto, ladoPx, mover.x, mover.y)
                                .acercado(nueva.ancho, nueva.alto, zoom, centro.x / ladoPx, centro.y / ladoPx)
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            nueva != null && img != null -> Canvas(Modifier.fillMaxSize()) {
                val r = nueva.recorte
                val lado = r.lado(nueva.ancho, nueva.alto)
                drawImage(
                    img,
                    srcOffset = IntOffset(Math.round(r.cx - lado / 2f), Math.round(r.cy - lado / 2f)),
                    srcSize = IntSize(Math.round(lado), Math.round(lado)),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
            }
            e == FotoDelChat.Eleccion.LaDeAntes -> {
                val vieja = inicial?.ruta?.let { r ->
                    val b by produceState(FotosDelChat.enMemoria(r), r) { value = withContext(Dispatchers.IO) { FotosDelChat.leer(r) } }
                    b
                }
                if (vieja != null) Image(vieja, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(iniciales(nombre), fontSize = 48.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            e is FotoDelChat.Eleccion.ConEmoji -> Text(e.emoji, fontSize = with(densidad) { (tamano * 0.52f).toSp() })
            else -> Text(
                iniciales(nombre), fontSize = 48.sp, fontWeight = FontWeight.SemiBold,
                color = if (fondo != null) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
