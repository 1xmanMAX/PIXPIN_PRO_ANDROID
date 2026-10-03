package com.forge.pixpin.motormd

import androidx.compose.foundation.background
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * El tamaño de letra al que se leen las notas de tamaño natural.
 *
 * Sirve de referencia para saber si lo que se está pintando es una nota o una
 * miniatura: lo que se mide en puntos —los mínimos de una tabla, sus bordes—
 * encoge en la misma proporción que la letra. Ver [RejillaDeTabla].
 */
private const val TAMANIO_NORMAL_SP = 14f

/** Cuánto crece cada nivel de título respecto al tamaño base. */
private const val H1 = 1.6f
private const val H2 = 1.35f
private const val H3 = 1.15f

/**
 * Dibuja los bloques ya interpretados por [Markdown].
 *
 * [baseSizeSp] llega **ya multiplicado por el zoom del pin**, y todo lo demás se
 * deriva de él: así el renderizador no sabe nada del pellizco y el pin entero
 * escala en proporción, letra y cuadro a la vez.
 */
/**
 * Dónde cae algo tocable dentro del contenido, en coordenadas SIN escalar.
 *
 * Lleva [url] si es un enlace y [oculto] si es un tapado que hay que destapar.
 * Van juntos porque el toque llega por el mismo sitio —el reconocedor que
 * comparten todos los pines— y quien lo recibe solo necesita saber qué hacer con
 * el rectángulo que pilló.
 */
data class LinkHit(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val url: String? = null,
    val oculto: String? = null
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

/** Si los audios de la nota responden al toque. Falso por defecto: solo el editor lo pone. */
val LocalMediosTocables = androidx.compose.runtime.compositionLocalOf { false }

/**
 * **Quién sabe hacer sonar el audio de la nota.**
 *
 * La nota transcrita lleva su audio dentro y cada párrafo lleva su minuto: en la pantalla de
 * la letra eso ya se usaba —tocar un párrafo salta a ese punto— y en la nota no, aunque es
 * el mismo texto. Lo pidió el usuario (7-sep-2026): que las dos interfaces sirvan.
 *
 * Entra como un trato y no como una llamada directa al reproductor porque el Markdown no
 * sabe —ni tiene por qué— de reproductores ni de mensajes: quien enseña la nota es quien
 * pone el suyo. Sin nadie que lo ponga, el audio se toca como antes, con su propio
 * reproductor de usar y tirar, y los minutos no hacen nada.
 */
interface AudioDeLaNota {
    /** Suena, o se calla si ya sonaba. */
    fun alternar(ruta: String)
    /** Va a ese milisegundo del audio y sigue sonando desde ahí. */
    fun saltar(ruta: String, ms: Int)
    /** Si ese audio está sonando ahora mismo. */
    fun sonando(ruta: String): Boolean
}

val LocalAudioDeLaNota = androidx.compose.runtime.compositionLocalOf<AudioDeLaNota?> { null }

/**
 * **Lo que la nota necesita saber de la aplicación** para enseñar lo que mete el PC (30-sep y
 * 1-oct-2026): un mensaje del chat o una hoja enlazados se pintan como su burbuja, y tocar un
 * archivo lo abre con el lector de PixPin. El Markdown no sabe de chats ni de proyectos; quien
 * enseña la nota pone el suyo ([com.forge.pixpin.ui.IncrustadosDeLaNota]). Sin nadie, todo se ve
 * como antes: un enlace, una tarjeta genérica.
 */
interface ContextoDeLaNota {
    /** Lo que se enseña de un mensaje o una hoja enlazados; null si ya no está. */
    fun vista(enlace: Incrustados.Enlace): VistaIncrustada?
    fun abrirEnlace(enlace: Incrustados.Enlace)
    fun abrirArchivo(ruta: String, nombre: String)
    /** Abre la hoja de una página viva por su código. */
    fun abrirHoja(codigo: String)
    /** El peso del archivo, legible («1,2 MB»), o null. */
    fun peso(ruta: String): String?
}

/** Lo que se pinta de un mensaje o una hoja enlazados. */
class VistaIncrustada(
    val texto: String,
    val detalle: String = "",
    /** El código de chat (`#47·K7Q2`) o la clase de hoja. */
    val chapa: String = "",
    val hora: String = "",
    val miniatura: ImageBitmap? = null,
    /** Nombre de archivo, para su icono de color, si es un archivo. */
    val archivo: String? = null
)

val LocalContextoDeLaNota = androidx.compose.runtime.compositionLocalOf<ContextoDeLaNota?> { null }

/** Sube cuando se repintan las páginas vivas: las fotos se vuelven a leer. */
val LocalVersionDeMedios = androidx.compose.runtime.compositionLocalOf { 0 }

/** El audio de **esta** nota: el primero que lleve dentro. Lo pone [MarkdownText]. */
private val LocalRutaDelAudio = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * El minuto con el que empieza un párrafo, en milisegundos, o -1.
 *
 * `[1:23] lo que se dijo` y `[1:02:09] …`. Es el formato que escribe la transcripción, y
 * aquí se lee sin depender de ella: el Markdown no sabe de transcripciones.
 */
internal fun minutoDelParrafo(texto: String): Int {
    val m = EL_MINUTO.find(texto) ?: return -1
    val a = m.groupValues[1].toIntOrNull() ?: return -1
    val b = m.groupValues[2].toIntOrNull() ?: return -1
    val c = m.groupValues[3].toIntOrNull()
    return if (c == null) (a * 60 + b) * 1000 else ((a * 3600) + b * 60 + c) * 1000
}

private val EL_MINUTO = Regex("""^\s*\[(\d{1,2}):(\d{2})(?::(\d{2}))?]""")

@Composable
fun MarkdownText(
    blocks: List<MarkdownBlock>,
    baseSizeSp: Float,
    modifier: Modifier = Modifier,
    ocultosVisibles: Set<String> = emptySet(),
    onLinks: (List<LinkHit>) -> Unit = {}
) {
    // Los rectángulos de cada bloque se juntan aquí para publicarlos de una vez:
    // quien hace la prueba de impacto necesita la lista entera, no trozos.
    val perBlock = remember { mutableStateMapOf<String, List<LinkHit>>() }
    LaunchedEffect(perBlock.size, blocks) {
        onLinks(perBlock.values.flatten())
    }
    // El audio de la nota: el primero que lleve dentro. Es al que saltan sus minutos.
    val delAudio = remember(blocks) {
        blocks.filterIsInstance<MarkdownBlock.Medio>()
            .firstOrNull { it.clase == ClaseDeMedio.AUDIO }?.ruta
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalRutaDelAudio provides delAudio) {
        Column(modifier = modifier) {
            Bloques(blocks, "", baseSizeSp, ocultosVisibles) { clave, hits -> perBlock[clave] = hits }
        }
    }
}

/**
 * Una tanda de bloques con su separación.
 *
 * Está aparte porque las cajas llevan bloques dentro y tienen que poder volver a
 * entrar aquí. La [ruta] es lo que distingue el tercer bloque de dentro de un
 * plegable del tercero de fuera: sin ella los dos escribirían en la misma
 * casilla y los enlaces de uno taparían los del otro.
 */
@Composable
private fun Bloques(
    blocks: List<MarkdownBlock>,
    ruta: String,
    baseSizeSp: Float,
    ocultosVisibles: Set<String>,
    onLinks: (String, List<LinkHit>) -> Unit
) {
    val gap = (baseSizeSp * 0.35f).dp
    blocks.forEachIndexed { index, block ->
        if (index > 0) Spacer(Modifier.height(gap))
        Bloque("$ruta/$index", block, baseSizeSp, ocultosVisibles, onLinks)
    }
}

@Composable
private fun Bloque(
    clave: String,
    block: MarkdownBlock,
    baseSizeSp: Float,
    ocultosVisibles: Set<String>,
    onLinks: (String, List<LinkHit>) -> Unit
) {
    // Todos los bloques publican sus rectángulos, no solo el párrafo: un enlace
    // dentro de una viñeta o de una cita también se toca.
    val recoge: (List<LinkHit>) -> Unit = { onLinks(clave, it) }

    when (block) {
        is MarkdownBlock.Heading -> Body(
            content = block.content,
            // Seis niveles como sus seis ArticleHeading, cada uno un escalón
            // más pequeño hasta llegar al tamaño del texto normal.
            sizeSp = baseSizeSp * factorDeTitulo(block.level),
            bloque = clave,
            ocultosVisibles = ocultosVisibles,
            weight = FontWeight.Bold,
            onLinks = recoge
        )

        // **Un párrafo que empieza por su minuto es un salto.** Es lo que hace la pantalla
        // de la letra: tocarlo lleva el audio de la nota a ese punto. Aquí igual, cuando la
        // nota trae audio y quien la enseña sabe hacerlo sonar. Ver [AudioDeLaNota].
        is MarkdownBlock.Paragraph -> {
            val contexto = LocalContextoDeLaNota.current
            val incrustado = if (contexto != null) remember(block.content) { Incrustados.soloEnlace(block.content) } else null
            if (contexto != null && incrustado != null) {
                BurbujaIncrustada(incrustado.first, incrustado.second, contexto, baseSizeSp)
                return
            }
            val ruta = LocalRutaDelAudio.current
            val reproductor = LocalAudioDeLaNota.current
            val ms = if (ruta != null && reproductor != null) minutoDelParrafo(block.content.text) else -1
            val cuerpo = @Composable {
                Body(
                    content = block.content,
                    sizeSp = baseSizeSp,
                    bloque = clave,
                    ocultosVisibles = ocultosVisibles,
                    onLinks = recoge
                )
            }
            if (ms < 0 || ruta == null || reproductor == null) cuerpo()
            else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape((baseSizeSp * 0.4f).dp))
                        .clickable { reproductor.saltar(ruta, ms) }
                ) { cuerpo() }
            }
        }

        is MarkdownBlock.Bullet -> Row {
            Marker("\u2022  ", baseSizeSp)
            Body(block.content, baseSizeSp, clave, ocultosVisibles, onLinks = recoge)
        }

        is MarkdownBlock.Numbered -> Row {
            Marker("${block.number}.  ", baseSizeSp)
            Body(block.content, baseSizeSp, clave, ocultosVisibles, onLinks = recoge)
        }

        // La casilla se pinta, no se toca: marcarla cambiaría el texto, y el
        // texto de la nota se edita en el editor, que es donde hay teclado y
        // deshacer. Aquí sería un cambio a ciegas sin forma de volver atrás.
        is MarkdownBlock.Tarea -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (block.hecha) {
                    Icons.Filled.CheckBox
                } else {
                    Icons.Filled.CheckBoxOutlineBlank
                },
                contentDescription = null,
                modifier = Modifier.size((baseSizeSp * 1.15f).dp),
                tint = if (block.hecha) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.width(6.dp))
            Body(
                content = block.content,
                sizeSp = baseSizeSp,
                bloque = clave,
                ocultosVisibles = ocultosVisibles,
                // Tachada al estar hecha: se ve de un vistazo qué queda por
                // hacer sin tener que leer las casillas una por una.
                tachado = block.hecha,
                color = if (block.hecha) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                onLinks = recoge
            )
        }

        is MarkdownBlock.Quote -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height((baseSizeSp * 1.4f).dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(8.dp))
            Body(
                content = block.content,
                sizeSp = baseSizeSp,
                bloque = clave,
                ocultosVisibles = ocultosVisibles,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onLinks = recoge
            )
        }

        is MarkdownBlock.Code -> Column(
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // El lenguaje, si se escribió, en pequeño y arriba: es lo único que
            // dice de qué es el bloque al releer la nota.
            if (block.lenguaje.isNotEmpty()) {
                Text(
                    text = block.lenguaje,
                    fontSize = (baseSizeSp * 0.7f).sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height((baseSizeSp * 0.2f).dp))
            }
            Text(
                text = block.text,
                fontSize = (baseSizeSp * 0.92f).sp,
                lineHeight = (baseSizeSp * 1.3f).sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // La fórmula **se compone**, no se enseña tal cual: la raya de la
        // fracción, el exponente arriba y pequeño, el signo de la raíz. Ver
        // [FormulaUi].
        is MarkdownBlock.Formula -> FormulaUi(
            latex = block.latex,
            tamanoSp = baseSizeSp * 1.05f,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        is MarkdownBlock.Tabla -> TablaUi(block, baseSizeSp, clave, ocultosVisibles, recoge)

        is MarkdownBlock.Medio -> MedioUi(block, baseSizeSp)

        is MarkdownBlock.Caja -> CajaUi(block, clave, baseSizeSp, ocultosVisibles, onLinks)

        MarkdownBlock.Rule -> HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
    }
}

private fun factorDeTitulo(nivel: Int): Float = when (nivel) {
    1 -> H1
    2 -> H2
    3 -> H3
    4 -> 1.05f
    5 -> 1f
    else -> 0.95f
}

/**
 * La tabla, con su fila de encabezados y la alineación por columna.
 *
 * Es su `pageTableCell`: `header` para la primera fila y `align_center` /
 * `align_right` por celda. Aquí la alineación va por columna y no por celda
 * porque es lo que sabe decir la sintaxis de tabla de Markdown, que es la que se
 * escribe; celda a celda haría falta inventarse algo que nadie teclea.
 *
 * Se desplaza a lo ancho por su cuenta: una tabla de seis columnas dentro de un
 * pin estrecho no puede empujar el resto de la nota.
 */
@Composable
private fun TablaUi(
    tabla: MarkdownBlock.Tabla,
    baseSizeSp: Float,
    clave: String,
    ocultosVisibles: Set<String>,
    onLinks: (List<LinkHit>) -> Unit
) {
    Column {
        // El título de la tabla, su `pageBlockTable.title`: centrado y encima.
        if (tabla.titulo.text.isNotEmpty()) {
            Body(
                content = tabla.titulo,
                sizeSp = baseSizeSp * 0.95f,
                bloque = "$clave/titulo",
                ocultosVisibles = ocultosVisibles,
                weight = FontWeight.Bold,
                onLinks = onLinks
            )
            Spacer(Modifier.height(4.dp))
        }

        // **La misma rejilla que en el editor.** Ver [RejillaDeTabla]. La
        // escala sale del tamaño de letra pedido: a tamaño normal es 1 y no
        // cambia nada, y en una miniatura encoge la tabla entera con la letra
        // en vez de dejar unos bordes enormes alrededor de nada.
        RejillaDeTabla(tabla, escala = baseSizeSp / TAMANIO_NORMAL_SP) { ancla ->
            Body(
                content = ancla.celda.contenido,
                color = LocalColorDeLaCelda.current ?: MaterialTheme.colorScheme.onSurface,
                sizeSp = baseSizeSp * 0.92f,
                bloque = "$clave/${ancla.fila}/${ancla.columna}",
                ocultosVisibles = ocultosVisibles,
                weight = if (ancla.celda.cabecera) FontWeight.Bold else FontWeight.Normal,
                onLinks = onLinks
            )
        }
    }
}

/**
 * Un medio: la imagen se ve, y lo demás sale como una tarjeta con su nombre.
 *
 * Telegram reproduce el vídeo y el audio dentro del artículo. Aquí no: una nota
 * es una ventana flotante encima de otra app, y meterle un reproductor dentro es
 * pelearse por el audio y por el foco con lo que haya debajo. La tarjeta dice
 * qué hay y se abre con el reproductor del teléfono, que es el que sabe hacerlo.
 */
@Composable
private fun MedioUi(medio: MarkdownBlock.Medio, baseSizeSp: Float) {
    val contexto = LocalContextoDeLaNota.current
    val (pie, ancho) = remember(medio.alt) { Incrustados.partirAlt(medio.alt) }
    if (medio.clase == ClaseDeMedio.IMAGEN) {
        // La fecha del archivo en la clave: una página viva repintada se vuelve a leer.
        val version = LocalVersionDeMedios.current
        val fecha = remember(medio.ruta, version) { java.io.File(medio.ruta).lastModified() }
        val mapa = remember(medio.ruta, fecha) { cargarImagen(medio.ruta) }
        val viva = remember(medio.ruta) { Incrustados.paginaViva(medio.ruta) }
        if (mapa != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = mapa,
                    contentDescription = pie.ifEmpty { null },
                    modifier = Modifier
                        // El ancho que se le puso (en el PC o aquí), sobre la columna de 720.
                        .fillMaxWidth(((ancho ?: Incrustados.COLUMNA).toFloat() / Incrustados.COLUMNA).coerceIn(0.1f, 1f))
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (viva != null && contexto != null && LocalMediosTocables.current) Modifier.clickable { contexto.abrirHoja(viva) } else Modifier),
                    contentScale = ContentScale.FillWidth
                )
            }
            if (pie.isNotEmpty()) {
                Text(
                    text = pie,
                    fontSize = (baseSizeSp * 0.8f).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                )
            }
            return
        }
        // Sin archivo se cae a la tarjeta: enseñar el hueco de una imagen que no
        // está no dice nada, y el nombre al menos dice cuál falta.
    }

    // **El audio sí se reproduce aquí.** La regla de arriba vale para el vídeo, que se
    // pelea por la pantalla; una nota de voz transcrita —ver `MensajesStore.transcribir`—
    // se escucha mientras se lee su texto, y mandar al reproductor del teléfono para eso
    // es salir de la nota cada vez. Un toque arranca, otro para. Lo pidió el usuario
    // (5-sep-2026).
    var sonando by remember(medio.ruta) { mutableStateOf(false) }
    val reproductor = remember(medio.ruta) { arrayOfNulls<android.media.MediaPlayer>(1) }
    androidx.compose.runtime.DisposableEffect(medio.ruta) {
        onDispose { runCatching { reproductor[0]?.release() }; reproductor[0] = null }
    }
    // Solo donde se lee la nota de verdad: en la vista previa de un proyecto la tarjeta
    // se llevaba el toque —y la pulsación larga— y no se podía abrir ni elegir la hoja.
    val elReproductor = LocalAudioDeLaNota.current
    val esAudio = LocalMediosTocables.current && medio.clase == ClaseDeMedio.AUDIO && java.io.File(medio.ruta).exists()
    if (medio.clase == ClaseDeMedio.ARCHIVO && contexto != null) {
        TarjetaDeArchivo(medio, pie, contexto, baseSizeSp)
        return
    }
    // Con reproductor puesto manda él: así el audio de la nota es **el mismo** que el de la
    // pantalla de la letra y el de la barra de abajo, y los minutos pueden saltar dentro.
    val suena = if (elReproductor != null && esAudio) elReproductor.sonando(medio.ruta) else sonando
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .then(
                if (!esAudio) Modifier
                else if (elReproductor != null) Modifier.clickable { elReproductor.alternar(medio.ruta) }
                else Modifier.clickable {
                    val actual = reproductor[0]
                    if (sonando && actual != null) {
                        runCatching { actual.stop(); actual.release() }
                        reproductor[0] = null
                        sonando = false
                    } else {
                        runCatching {
                            android.media.MediaPlayer().apply {
                                setDataSource(medio.ruta)
                                setOnCompletionListener { sonando = false }
                                prepare(); start()
                            }
                        }.getOrNull()?.let { reproductor[0] = it; sonando = true }
                    }
                }
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (medio.clase) {
                ClaseDeMedio.IMAGEN -> Icons.Filled.Image
                ClaseDeMedio.VIDEO -> Icons.Filled.Movie
                ClaseDeMedio.AUDIO -> if (suena) Icons.Filled.Stop else if (esAudio) Icons.Filled.PlayArrow else Icons.Filled.AudioFile
                ClaseDeMedio.ARCHIVO -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size((baseSizeSp * 1.6f).dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = medio.alt.ifEmpty { medio.ruta.substringAfterLast('/') },
                fontSize = baseSizeSp.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = medio.ruta.substringAfterLast('.', "").uppercase()
                    .ifEmpty { "archivo" },
                fontSize = (baseSizeSp * 0.75f).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Un archivo dentro de la nota, como la fila del chat: el icono de su color, nombre, peso y tipo. */
@Composable
private fun TarjetaDeArchivo(medio: MarkdownBlock.Medio, pie: String, contexto: ContextoDeLaNota, baseSizeSp: Float) {
    val nombre = pie.ifEmpty { medio.ruta.substringAfterLast('/') }
    val existe = remember(medio.ruta) { java.io.File(medio.ruta).exists() }
    val peso = remember(medio.ruta) { contexto.peso(medio.ruta) }
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .then(if (existe && LocalMediosTocables.current) Modifier.clickable { contexto.abrirArchivo(medio.ruta, nombre) } else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.forge.pixpin.ui.IconoDeArchivo(nombre, lado = (baseSizeSp * 2.6f).dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(nombre, fontSize = baseSizeSp.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            val ext = nombre.substringAfterLast('.', "").uppercase()
            Text(
                if (!existe) "No está en este aparato" else listOfNotNull(peso, ext.ifEmpty { null }).joinToString(" · "),
                fontSize = (baseSizeSp * 0.75f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * **Un mensaje del chat o una hoja dentro de la nota**, como su burbuja: lo que dice, su
 * miniatura si la tiene, su chapa y su hora. Si ya no está, «Mensaje borrado» con el texto del
 * enlace, apagado. Tocarlo abre el mensaje en su chat o la hoja.
 */
@Composable
private fun BurbujaIncrustada(enlace: Incrustados.Enlace, texto: String, contexto: ContextoDeLaNota, baseSizeSp: Float) {
    var vista by remember(enlace) { mutableStateOf<VistaIncrustada?>(null) }
    var buscada by remember(enlace) { mutableStateOf(false) }
    LaunchedEffect(enlace) {
        vista = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { contexto.vista(enlace) }.getOrNull() }
        buscada = true
    }
    val v = vista
    val esMensaje = enlace is Incrustados.Enlace.Mensaje
    Column(
        Modifier.fillMaxWidth(0.92f)
            .background(
                if (esMensaje) MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (v == null && buscada) 0.35f else 0.9f)
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
            )
            .clickable(enabled = v != null) { contexto.abrirEnlace(enlace) }
            .padding(10.dp)
    ) {
        if (v?.miniatura != null) {
            Image(
                bitmap = v.miniatura, contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (v?.archivo != null) {
                com.forge.pixpin.ui.IconoDeArchivo(v.archivo, lado = (baseSizeSp * 2.4f).dp)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        v != null -> v.texto
                        buscada -> if (esMensaje) "Mensaje borrado" else "La hoja ya no está"
                        else -> texto
                    },
                    fontSize = baseSizeSp.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (v == null && buscada) 0.6f else 1f),
                    maxLines = 6
                )
                if (v == null && buscada) Text(texto, fontSize = (baseSizeSp * 0.8f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                if (v != null && v.detalle.isNotEmpty()) Text(v.detalle, fontSize = (baseSizeSp * 0.8f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        if (v != null && (v.chapa.isNotEmpty() || v.hora.isNotEmpty())) {
            Text(
                listOf(v.chapa, v.hora).filter { it.isNotEmpty() }.joinToString("  "),
                fontSize = (baseSizeSp * 0.7f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun cargarImagen(ruta: String): ImageBitmap? = runCatching {
    val opciones = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(ruta, opciones)
    if (opciones.outWidth <= 0) return null
    // Se reduce al decodificar: una foto de 12 megapíxeles dentro de una nota
    // flotante es memoria tirada, y el ancho del pin no llega a mil puntos.
    var muestra = 1
    while (opciones.outWidth / muestra > 1440) muestra *= 2
    BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = muestra })
        ?.asImageBitmap()
}.getOrNull()

/**
 * Las cajas: plegable, pie, destacado y las dos alineaciones.
 *
 * El plegable empieza **cerrado**, como su `pageBlockDetails`: es lo que lo hace
 * útil, porque una nota larga se recorre por los títulos y se abre solo lo que
 * hace falta.
 */
@Composable
private fun CajaUi(
    caja: MarkdownBlock.Caja,
    clave: String,
    baseSizeSp: Float,
    ocultosVisibles: Set<String>,
    onLinks: (String, List<LinkHit>) -> Unit
) {
    when (caja.tipo) {
        TipoDeCaja.PLEGABLE -> {
            var abierto by remember(clave) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { abierto = !abierto }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (abierto) {
                            Icons.Filled.ExpandMore
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                        contentDescription = null,
                        modifier = Modifier.size((baseSizeSp * 1.3f).dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = caja.titulo.ifEmpty { "Detalles" },
                        fontSize = baseSizeSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (abierto) {
                    Column(Modifier.padding(start = (baseSizeSp * 1.3f).dp)) {
                        Bloques(caja.dentro, clave, baseSizeSp, ocultosVisibles, onLinks)
                    }
                }
            }
        }

        // El pie es letra pequeña y apagada, separada por una raya: es lo que
        // hace su pageBlockFooter, y lo que dice «esto no es el texto, es la
        // nota al pie del texto».
        TipoDeCaja.PIE -> Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(Modifier.padding(bottom = 4.dp))
            Bloques(caja.dentro, clave, baseSizeSp * 0.82f, ocultosVisibles, onLinks)
        }

        // El destacado es lo contrario: más grande y despegado del resto, para
        // la frase que se quiere que se lea aunque no se lea nada más.
        TipoDeCaja.DESTACADO -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Bloques(caja.dentro, clave, baseSizeSp * 1.15f, ocultosVisibles, onLinks)
        }

        TipoDeCaja.CENTRO -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Bloques(caja.dentro, clave, baseSizeSp, ocultosVisibles, onLinks)
        }

        TipoDeCaja.DERECHA -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            Bloques(caja.dentro, clave, baseSizeSp, ocultosVisibles, onLinks)
        }
    }
}

/** La viñeta o el número, que no deben heredar los estilos del contenido. */
@Composable
private fun Marker(text: String, sizeSp: Float) {
    Text(
        text = text,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.4f).sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Body(
    content: InlineText,
    sizeSp: Float,
    bloque: String,
    ocultosVisibles: Set<String>,
    weight: FontWeight = FontWeight.Normal,
    color: Color = MaterialTheme.colorScheme.onSurface,
    tachado: Boolean = false,
    onLinks: (List<LinkHit>) -> Unit = {}
) {
    val tramos = remember(content) { content.tramos() }
    // Lo tocable: los enlaces, y los tapados que aún no se han destapado.
    val links = tramos.filter {
        (it.tiene(SpanKind.LINK) && it.url != null) ||
            (it.tiene(SpanKind.SPOILER) && claveDeOculto(bloque, it) !in ocultosVisibles)
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Text(
        text = content.annotated(
            tramos = tramos,
            linkColor = MaterialTheme.colorScheme.primary,
            tapado = MaterialTheme.colorScheme.onSurfaceVariant,
            bloque = bloque,
            ocultosVisibles = ocultosVisibles
        ),
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.4f).sp,
        fontWeight = weight,
        color = color,
        textDecoration = if (tachado) TextDecoration.LineThrough else null,
        modifier = if (links.isEmpty()) Modifier else Modifier.onGloballyPositioned {
            origin = it.positionInRoot()
        },
        onTextLayout = { layout ->
            if (links.isEmpty()) return@Text
            // Un enlace puede partirse en varias líneas, así que se recogen las
            // cajas de cada carácter y se agrupan por línea: una sola caja
            // envolvente daría por tocable medio párrafo.
            onLinks(
                links.flatMap { tramo ->
                    val byLine = (tramo.inicio until tramo.fin)
                        .groupBy { layout.getLineForOffset(it) }
                    byLine.values.mapNotNull { offsets ->
                        val boxes = offsets.map { layout.getBoundingBox(it) }
                        if (boxes.isEmpty()) return@mapNotNull null
                        LinkHit(
                            left = origin.x + boxes.minOf { it.left },
                            top = origin.y + boxes.minOf { it.top },
                            right = origin.x + boxes.maxOf { it.right },
                            bottom = origin.y + boxes.maxOf { it.bottom },
                            // Un tapado se destapa antes de abrir nada: si lleva
                            // enlace debajo, el primer toque lo enseña y el
                            // segundo lo abre.
                            url = if (tramo.tiene(SpanKind.SPOILER)) null else tramo.url,
                            oculto = if (tramo.tiene(SpanKind.SPOILER)) {
                                claveDeOculto(bloque, tramo)
                            } else {
                                null
                            }
                        )
                    }
                }
            )
        }
    )
}

/**
 * Cómo se reconoce un tapado entre un toque y el siguiente.
 *
 * El bloque y dónde empieza bastan y no hacen falta identificadores guardados: si
 * el texto cambia, el tapado es otro y volver a esconderlo es lo correcto.
 */
private fun claveDeOculto(bloque: String, tramo: Tramo): String = "$bloque:${tramo.inicio}"

/**
 * Traduce cada tramo a **un** estilo con todo lo que lleva dentro.
 *
 * Antes se recorrían las marcas sueltas y cada una ponía su estilo por su cuenta.
 * Ahora se pintan los tramos ya aplanados —ver [tramosDe]—, que es lo que hace
 * que negrita y cursiva a la vez sean una sola decisión y no dos que hay que
 * confiar en que se sumen bien.
 */
private fun InlineText.annotated(
    tramos: List<Tramo>,
    linkColor: Color,
    tapado: Color,
    bloque: String,
    ocultosVisibles: Set<String>
): AnnotatedString = buildAnnotatedString {
    append(text)
    tramos.forEach { tramo ->
        // El parser trabaja sobre el texto ya limpio, pero un tramo corrupto no
        // puede tumbar el pin: buildAnnotatedString lanza si el rango se sale.
        if (tramo.inicio < 0 || tramo.fin > text.length || tramo.inicio >= tramo.fin) {
            return@forEach
        }

        var style = SpanStyle()
        if (tramo.tiene(SpanKind.BOLD)) style = style.copy(fontWeight = FontWeight.Bold)
        if (tramo.tiene(SpanKind.ITALIC)) style = style.copy(fontStyle = FontStyle.Italic)
        if (tramo.tiene(SpanKind.CODE)) style = style.copy(fontFamily = FontFamily.Monospace)
        if (tramo.tiene(SpanKind.LINK)) {
            style = style.copy(color = linkColor, textDecoration = TextDecoration.Underline)
        }
        // El tachado gana al subrayado del enlace: un enlace tachado es un enlace
        // que ya no vale, y esa es la información que importa.
        if (tramo.tiene(SpanKind.STRIKE)) {
            style = style.copy(textDecoration = TextDecoration.LineThrough)
        }
        // Tapado: la letra y el fondo del mismo color, que deja una barra maciza
        // sin cambiar cuánto ocupa el texto. Al destaparlo se cae solo en su
        // sitio porque nunca dejó de estar ahí.
        if (tramo.tiene(SpanKind.SPOILER) &&
            claveDeOculto(bloque, tramo) !in ocultosVisibles
        ) {
            style = style.copy(color = tapado, background = tapado)
        }

        addStyle(style, tramo.inicio, tramo.fin)
    }
}
