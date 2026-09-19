package com.forge.pixpin.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.forge.pixpin.motor.DrawExport
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.PdfMiniaturas
import com.forge.pixpin.data.Abiertos
import com.forge.pixpin.data.LienzoAbierto
import com.forge.pixpin.ui.theme.Cristal
import com.forge.pixpin.ui.theme.cristal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * **Las cuentas de la tira de lienzos**, calcadas de la multitarea del Xiaomi 18 Fold que enseñó
 * el usuario (dos vídeos, 18-sep-2026):
 *
 * - Los lienzos abiertos son **columnas vivas**, una detrás de otra. De pie, en columna (arriba y
 *   abajo); tumbada, en fila, y caben dos o tres a la vez.
 * - Cada columna tiene **su tamaño**, y el **divisor** entre dos se arrastra para repartirlo
 *   («调节两个软件的大小»). Lo mínimo que se queda una es la **pestaña** ([PESTANA]): una rayita
 *   por la que asoma y por la que se la trae con un toque.
 * - **Cuatro dedos al lado** saltan a la columna siguiente o anterior, entera («三指滑动切换»).
 * - **Cuatro dedos que se cierran** abren la baraja («四指一捏»); abiertos, la columna a pantalla
 *   entera.
 */
object Multilienzo {
    /**
     * Lo que asoma del lienzo de al lado, en dp. Una rayita, pero **que se pueda tocar**: con 18
     * el usuario no acertaba (18-sep-2026); 28 se toca sin ser un panel.
     */
    const val PESTANA = 28f

    /** Lo que separa un lienzo del siguiente, en dp. */
    const val AIRE = 6f

    /** Si la tira va en columna: la pantalla más alta que ancha. */
    fun enColumna(anchoDp: Int, altoDp: Int) = altoDp > anchoDp

    /**
     * Cuántos caben a la vez de fábrica: **tres** en una pantalla ancha como la del plegable del
     * vídeo, dos en una tumbada normal, uno de pie.
     */
    fun porPantalla(anchoDp: Int, altoDp: Int) = when {
        enColumna(anchoDp, altoDp) -> 1
        anchoDp >= 840 -> 3
        anchoDp >= 600 -> 2
        else -> 1
    }

    /**
     * **La unidad**: lo que mide un hueco. La pantalla se parte en [porPantalla] huecos iguales
     * (descontadas las pestañas y los aires) y cada columna ocupa **uno, dos o los tres**: nada de
     * tamaños a ojo. Lo pidió así el usuario (18-sep-2026).
     */
    fun unidad(pantalla: Int, pestana: Int, aire: Int, porPantalla: Int, antes: Int = pestana, despues: Int = pestana): Int =
        ((pantalla - antes - despues - (porPantalla - 1) * aire) / porPantalla).coerceAtLeast(1)

    /** Lo que mide una columna que ocupa [huecos] huecos. */
    fun tamano(huecos: Int, unidad: Int, aire: Int, porPantalla: Int): Int {
        val h = huecos.coerceIn(1, porPantalla.coerceAtLeast(1))
        return h * unidad + (h - 1) * aire
    }

    /**
     * **El tamaño de una columna es libre** (lo decidió el usuario, 18-sep-2026): se arrastra su
     * divisor y ella sola cambia; el vecino **conserva su medida** y se aparta, dejando su
     * pestaña. Lo mínimo son dos pestañas; lo máximo, la pantalla menos una pestaña, para que por
     * un lado siempre asome el de al lado.
     */
    fun tamanoLibre(
        t: Int, pantalla: Int, pestana: Int, antes: Int = pestana, i: Int = 1, cuantos: Int = 3, despues: Int = pestana
    ): Int = t.coerceIn(pestana * 2, maximo(pantalla, pestana, antes, i, cuantos, despues))

    /**
     * **Lo más que mide un lienzo: la pantalla menos las dos pestañas**, la del de antes y la del
     * de después (lo fijó el usuario, 18-sep-2026; antes era «menos una»). Así, se ponga como se
     * ponga el tamaño, **siempre queda por dónde tocar al vecino de cada lado** y nadie se queda
     * encerrado en un lienzo. En columna la pestaña de antes ([antes]) es más alta: lleva encima
     * la barra de arriba, y lo que tiene que poder tocarse es lo que asoma **por debajo** de ella.
     * Las barritas flotantes pequeñas (el porcentaje de zoom) no cuentan.
     */
    /*
     * **La pantalla es fija; lo que cambia es la pestaña** (así lo quiere el usuario, 19-sep-2026):
     * en columna, la de arriba mide la pestaña más la barra de arriba si está, y la de abajo la
     * pestaña más la barra de abajo si está ([antes] y [despues]). Con las barras escondidas, las
     * dos se quedan en lo mínimo y el lienzo puede crecer lo que ellas ocupaban.
     */
    fun maximo(pantalla: Int, pestana: Int, antes: Int = pestana, i: Int = 1, cuantos: Int = 3, despues: Int = pestana): Int {
        // **El primero y el último solo tienen vecino a un lado** (lo afinó el usuario el mismo
        // día): no guardan pestaña para nadie por el canto de la pantalla, así que llegan hasta
        // él y su tope es la pantalla menos **una** pestaña, la del único vecino.
        val laDeAntes = if (i > 0) antes else 0
        val laDeDespues = if (i < cuantos - 1) despues else 0
        return (pantalla - laDeAntes - laDeDespues).coerceAtLeast(pestana * 2)
    }

    /**
     * **El elástico**: lo que se estira de más al tirar del divisor pasado el tope (o el mínimo).
     * Cede cada vez menos y nunca pasa de [limite]; al soltar vuelve a cero.
     */
    fun elastico(sobra: Float, limite: Float): Float {
        if (limite <= 0f || sobra == 0f) return 0f
        val d = kotlin.math.abs(sobra)
        return kotlin.math.sign(sobra) * limite * (1f - 1f / (d / limite + 1f))
    }

    /**
     * **Un imán suave en los huecos**: si el tamaño cae cerca de uno, dos o tres huecos justos
     * (a menos de [IMAN] de unidad), se queda ahí. Así se puede ir a ojo y también clavar los
     * tercios sin puntería.
     */
    fun imantar(t: Int, unidad: Int, aire: Int, porPantalla: Int): Int {
        for (h in 1..porPantalla.coerceAtLeast(1)) {
            val justo = tamano(h, unidad, aire, porPantalla)
            if (kotlin.math.abs(t - justo) <= unidad * IMAN) return justo
        }
        return t
    }

    private const val IMAN = 0.08f

    /** Dónde empieza cada columna, en píxeles, una detrás de otra según sus tamaños. */
    fun sitios(tamanos: IntArray, aire: Int): IntArray {
        val xs = IntArray(tamanos.size)
        var x = 0
        for (i in tamanos.indices) {
            xs[i] = x
            x += tamanos[i] + aire
        }
        return xs
    }

    fun largo(tamanos: IntArray, aire: Int): Int =
        if (tamanos.isEmpty()) 0 else sitios(tamanos, aire).last() + tamanos.last()

    /** Dónde tiene que estar la tira para que mande la columna [i]: con su pestaña en el canto. */
    fun paraVer(i: Int, tamanos: IntArray, aire: Int, antes: Int): Float =
        (sitios(tamanos, aire).getOrElse(i) { 0 } - antes).toFloat()

    /**
     * El desplazamiento, sin salirse de la tira. **El primero y el último llegan hasta el canto
     * de la pantalla**: a ese lado no hay vecino al que dejarle sitio, así que no se retiran
     * (lo pidió el usuario, 18-sep-2026; antes quedaba ahí una pestaña vacía con un «+»).
     */
    fun limitar(corrida: Float, tamanos: IntArray, aire: Int, pestana: Int, pantalla: Int): Float {
        val tope = (largo(tamanos, aire) - pantalla).toFloat().coerceAtLeast(0f)
        return corrida.coerceIn(0f, tope)
    }

    /**
     * **A qué columna se salta al soltar los cuatro dedos**: a la siguiente si el gesto fue hacia
     * un lado, a la anterior si fue hacia el otro, y a la misma si apenas se movió. Un salto
     * entero por gesto: nada de quedarse a mitad de camino.
     */
    fun alSoltar(desde: Int, corridoPorElDedo: Float, umbral: Float, cuantos: Int): Int {
        val paso = when {
            corridoPorElDedo <= -umbral -> 1
            corridoPorElDedo >= umbral -> -1
            else -> 0
        }
        return (desde + paso).coerceIn(0, (cuantos - 1).coerceAtLeast(0))
    }

    /**
     * En qué ranura se pinta la tarjeta [i] mientras la [enElDedo] va camino de [hueco]: las de
     * en medio se corren una para dejarle el sitio, y ella cuenta ya como puesta en él.
     */
    fun ranuraDelAbanico(i: Int, enElDedo: Int, hueco: Int): Int = when {
        enElDedo < 0 -> i
        i == enElDedo -> hueco
        i in (enElDedo + 1)..hueco -> i - 1
        i in hueco until enElDedo -> i + 1
        else -> i
    }

    /**
     * El marco de cada lienzo: **plomo**, ni blanco ni negro. Era blanco y sobre un lienzo de
     * papel blanco no se veía dónde acababa uno y empezaba el otro (usuario, 19-sep-2026). Un gris
     * medio opaco contrasta con el papel claro y con el oscuro, y con los de color.
     */
    val MARCO = Color(0xFF7D8590)
}

/** La miniatura de un lienzo, ya pintada. Se recuerdan unas pocas: son mapas de bits. */
private val MINIATURAS = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
}

/** Pinta el lienzo [l] en pequeño, o null si no se puede. Va fuera del hilo de la pantalla. */
private suspend fun miniaturaDe(contexto: Context, l: LienzoAbierto): Bitmap? = withContext(Dispatchers.IO) {
    val ruta = l.ruta ?: ExcalidrawStore.rutaDe(contexto, l.id)
    val version = java.io.File(ruta).lastModified()
    val clave = "${l.id}@$version"
    MINIATURAS[clave]?.let { return@withContext it }
    val mapa = runCatching {
        val escena = ExcalidrawStore.cargar(ruta)
        if (escena != null && escena.elements.isNotEmpty()) {
            DrawExport.aBitmap(escena, ESCALA_DE_LA_MINIATURA) { id ->
                escena.files[id]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) }
            }
        } else if (l.pdf != null && l.pagina >= 0) {
            PdfMiniaturas.enMemoria(l.pdf, l.pagina) ?: PdfMiniaturas.de(contexto, l.pdf, l.pagina)
        } else {
            null
        }
    }.getOrNull()
    if (mapa != null) MINIATURAS[clave] = mapa
    mapa
}

private const val ESCALA_DE_LA_MINIATURA = 0.22

/**
 * **Un lienzo al lado**: su dibujo en pequeño y su nombre. No recibe el lápiz —eso es del que
 * está en el medio—; tocarlo lo trae al medio.
 */
@Composable
fun VistaDeLienzo(
    lienzo: LienzoAbierto,
    onTocar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contexto = LocalContext.current
    var mapa by remember(lienzo.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(lienzo.id, lienzo.visto) { mapa = miniaturaDe(contexto, lienzo) }
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Cristal.barra)
            .border(1.dp, Cristal.filo, RoundedCornerShape(18.dp))
            .clickable(onClick = onTocar)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            lienzo.nombre.ifBlank { "Sin nombre" },
            style = MaterialTheme.typography.labelMedium,
            color = Cristal.tinta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            val actual = mapa
            if (actual != null) {
                Image(
                    actual.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp)
                )
            } else {
                Icon(Icons.Filled.Description, contentDescription = null, tint = Cristal.tinta.copy(alpha = 0.5f))
            }
        }
    }
}

/**
 * **Los lienzos abiertos, en abanico que se desliza**: nacido de la baraja del plegable del
 * usuario (vídeo `vt.tiktok.com/ZSqsKM6nr`, segundos 43–52) y corregido por él el 18-sep-2026.
 *
 * - Las tarjetas van **giradas sobre su eje vertical** y montadas una sobre otra, pero **con
 *   aire**: a media tarjeta de paso «no se ve casi nada de su contenido».
 * - **La lista se desliza** con el dedo (y con inercia), y al parar se asienta en una tarjeta.
 *   **La que queda en el centro se revela**: gira hacia quien mira —sin llegar a plana—, crece un
 *   poco, pasa delante y las vecinas se le apartan. Todo eso sale de lo lejos que está cada una
 *   del centro y se lee **al pintar**: deslizar no recompone nada.
 * - **Mover una tarjeta es mantenerla pulsada y arrastrar**; arrastrar a secas desliza. Las demás
 *   se apartan con muelle al hueco donde caería, y si se lleva hacia un canto la lista corre.
 * - **Cada tarjeta guarda la forma de su lienzo en la tira**: en fila todas del mismo alto y
 *   cada una con su ancho; [apilados] (en el teléfono el ancho no se toca) todas del mismo ancho
 *   y cada una con su alto.
 * - Un botón cambia el orden de la tira en pantalla: en fila o en columna.
 */
@Composable
fun VistaDeTodos(
    lienzos: List<LienzoAbierto>,
    actual: String,
    onAbrir: (LienzoAbierto) -> Unit,
    onCerrar: (LienzoAbierto) -> Unit,
    onOrdenar: (de: Int, a: Int) -> Unit,
    onSalir: () -> Unit,
    /** Abrir uno que no está en la lista. */
    onOtro: () -> Unit = {},
    /** Si la tira va en columna (uno encima de otro), y el botón que lo cambia. */
    apilados: Boolean = false,
    onCambiarElOrden: () -> Unit = {},
    /** **Los grupos de pestañas guardados** (19-sep-2026): guardar lo abierto y volver de un toque. */
    grupos: List<com.forge.pixpin.data.GrupoDeAbiertos> = emptyList(),
    onGuardarGrupo: (String) -> Unit = {},
    onAbrirGrupo: (com.forge.pixpin.data.GrupoDeAbiertos) -> Unit = {},
    onBorrarGrupo: (com.forge.pixpin.data.GrupoDeAbiertos) -> Unit = {},
    /** Fuera del editor de lienzos no hay tira que ordenar ni lista de hojas que ofrecer. */
    conElBotonDeOrden: Boolean = true,
    conAbrirOtro: Boolean = true,
    modifier: Modifier = Modifier,
    /** Ancho partido por alto de lo que ocupa cada lienzo en la tira. */
    formaDeTarjeta: (LienzoAbierto) -> Float = { 1f },
    /**
     * **Lo que va dentro de cada tarjeta**: el lienzo **tal como se dejó**, encogido, como la
     * captura de una app en la multitarea del teléfono. Lo pone quien tiene los lienzos vivos.
     * Nulo: la miniatura de siempre.
     */
    contenido: (@Composable (LienzoAbierto) -> Unit)? = null
) {
    val densidad = LocalDensity.current.density
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color(0xE6070B1A))
            .pointerInput(Unit) { detectTapGestures { onSalir() } }
    ) {
        val altoDeLaCarta = maxHeight * 0.66f
        val altoDelLienzo = (altoDeLaCarta - CABECERA_DE_CARTA.dp).coerceAtLeast(40.dp)
        val anchoMaximo = maxWidth * 0.7f
        // En columna todas miden lo mismo de ancho —el de la pantalla, en pequeño— y cambia el alto.
        val anchoFijo = (altoDelLienzo * (maxWidth / maxHeight)).coerceAtMost(anchoMaximo)
        fun medidaDe(l: LienzoAbierto): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
            val forma = formaDeTarjeta(l).coerceIn(0.05f, 8f)
            return if (apilados) {
                anchoFijo to ((anchoFijo / forma).coerceIn(ANCHO_MINIMO_DE_CARTA.dp, altoDelLienzo) + CABECERA_DE_CARTA.dp)
            } else {
                (altoDelLienzo * forma).coerceIn(ANCHO_MINIMO_DE_CARTA.dp, anchoMaximo.coerceAtLeast(ANCHO_MINIMO_DE_CARTA.dp)) to altoDeLaCarta
            }
        }
        val n = lienzos.size
        val medidas = lienzos.map { medidaDe(it) }
        val anchoMedio = (medidas.map { it.first.value }.average().takeIf { !it.isNaN() } ?: 120.0).toFloat() * densidad
        val paso = anchoMedio * PASO_DEL_ABANICO
        val aparte = anchoMedio * APARTE_DEL_CENTRO

        // Por dónde va la lista, en píxeles de la propia lista: la ranura i está en i·paso.
        val corrido = remember { mutableFloatStateOf((lienzos.indexOfFirst { it.id == actual }.coerceAtLeast(0)) * paso) }
        val tope = n * paso
        // **Con freno** (19-sep-2026): las tarjetas van tan juntas que, siguiendo al dedo punto por
        // punto, un gesto corto pasaba tres de golpe —«se mueve muy rápido»—. La lista corre
        // [FRENO_DE_LA_BARAJA] de lo que corre el dedo —un gesto largo, una tarjeta— y **sin
        // inercia**: al soltar se asienta en la más cercana y ahí se queda.
        val deslizable = androidx.compose.foundation.gestures.rememberScrollableState { delta ->
            val antes = corrido.floatValue
            corrido.floatValue = (antes - delta * FRENO_DE_LA_BARAJA).coerceIn(0f, tope)
            if (corrido.floatValue == antes) 0f else delta
        }
        val sinInercia = remember {
            object : androidx.compose.foundation.gestures.FlingBehavior {
                override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float = 0f
            }
        }
        // Al parar, a la tarjeta más cercana: siempre hay una revelada del todo.
        LaunchedEffect(deslizable.isScrollInProgress, paso) {
            if (!deslizable.isScrollInProgress && paso > 0f) {
                val justo = ((corrido.floatValue / paso).roundToInt() * paso).coerceIn(0f, tope)
                androidx.compose.animation.core.animate(corrido.floatValue, justo) { v, _ -> corrido.floatValue = v }
            }
        }
        val enElCentro by remember(paso) {
            androidx.compose.runtime.derivedStateOf { if (paso > 0f) (corrido.floatValue / paso).roundToInt() else 0 }
        }

        var enElDedo by remember { mutableStateOf<String?>(null) }
        var hueco by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        LaunchedEffect(lienzos.map { it.id }) { enElDedo = null }
        val k = lienzos.indexOfFirst { it.id == enElDedo }
        val anchoPx = constraints.maxWidth.toFloat()

        Box(
            Modifier
                .fillMaxSize()
                .scrollable(deslizable, androidx.compose.foundation.gestures.Orientation.Horizontal, flingBehavior = sinInercia)
        ) {
            lienzos.forEachIndexed { i, l ->
                key(l.id) {
                    val ranura = Multilienzo.ranuraDelAbanico(i, k, hueco)
                    CartaDeLaBaraja(
                        lienzo = l,
                        esElActual = l.id == actual,
                        ancho = medidas[i].first,
                        alto = medidas[i].second,
                        suSitio = ranura * paso,
                        corrido = corrido,
                        paso = paso,
                        aparte = aparte,
                        altura = if (ranura == enElCentro) 50f else -kotlin.math.abs(ranura - enElCentro).toFloat(),
                        onAbrir = { onAbrir(l) },
                        onCerrar = { onCerrar(l) },
                        alMover = { x ->
                            enElDedo = l.id
                            hueco = (x / paso).roundToInt().coerceIn(0, n - 1)
                            // Llevada hacia un canto, la lista corre para que se llegue a todas.
                            val enPantalla = anchoPx / 2f + (x - corrido.floatValue)
                            val empuje = when {
                                enPantalla < anchoPx * 0.18f -> -paso * 0.08f
                                enPantalla > anchoPx * 0.82f -> paso * 0.08f
                                else -> 0f
                            }
                            val antes = corrido.floatValue
                            corrido.floatValue = (antes + empuje).coerceIn(0f, tope)
                            corrido.floatValue - antes
                        },
                        alSoltar = {
                            val de = lienzos.indexOfFirst { it.id == l.id }
                            if (de >= 0 && hueco != de) onOrdenar(de, hueco) else enElDedo = null
                        },
                        contenido = contenido,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            if (conAbrirOtro) CartaDeMas(
                ANCHO_DE_LA_CARTA_DE_MAS.dp, altoDeLaCarta * 0.7f, onOtro,
                Modifier
                    .align(Alignment.Center)
                    .zIndex(if (enElCentro >= n) 50f else -(n - enElCentro).toFloat())
                    .graphicsLayer {
                        val d = if (paso > 0f) (n * paso - corrido.floatValue) / paso else 0f
                        val cerca = 1f - kotlin.math.abs(d).coerceAtMost(1f)
                        translationX = (n * paso - corrido.floatValue) + d.coerceIn(-1f, 1f) * aparte
                        rotationY = GIRO_DEL_ABANICO * (1f - ENDEREZA_EL_CENTRO * cerca)
                        cameraDistance = 14f * density
                    }
            )
        }
        Text(
            "Tus lienzos",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 36.dp)
        )
        // **Cómo se ordena la tira en pantalla**: en fila (se corre a los lados) o en columna
        // (arriba y abajo). Antes solo se podía arrastrando una tarjeta hacia abajo.
        if (conElBotonDeOrden) Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 30.dp, end = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onCambiarElOrden)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (apilados) Icons.Filled.ViewStream else Icons.Filled.ViewColumn,
                contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)
            )
            Text(
                if (apilados) "En columna" else "En fila",
                color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        BarraDeGrupos(
            grupos, onGuardarGrupo, onAbrirGrupo, onBorrarGrupo,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp)
        )
        Text(
            "Desliza para verlos · mantén pulsada una tarjeta para moverla",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp)
        )
    }
}

/**
 * **Los grupos de pestañas**: «Guardar grupo» apunta lo abierto ahora con un nombre; cada grupo
 * guardado es una pastilla —un toque lo abre entero, en su orden; mantenerla pulsada lo borra—.
 */
@Composable
private fun BarraDeGrupos(
    grupos: List<com.forge.pixpin.data.GrupoDeAbiertos>,
    onGuardar: (String) -> Unit,
    onAbrir: (com.forge.pixpin.data.GrupoDeAbiertos) -> Unit,
    onBorrar: (com.forge.pixpin.data.GrupoDeAbiertos) -> Unit,
    modifier: Modifier = Modifier
) {
    var pidiendoNombre by remember { mutableStateOf(false) }
    var paraBorrar by remember { mutableStateOf<com.forge.pixpin.data.GrupoDeAbiertos?>(null) }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Pastilla("Guardar grupo", Icons.Filled.Add, fuerte = true, onToque = { pidiendoNombre = true })
        grupos.forEach { g ->
            key(g.id) {
                Pastilla(
                    g.nombre + " · " + g.lienzos.size, null, fuerte = false,
                    onToque = { onAbrir(g) }, onLargo = { paraBorrar = g }
                )
            }
        }
    }
    if (pidiendoNombre) {
        var nombre by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pidiendoNombre = false },
            title = { Text("Guardar grupo") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    nombre, { nombre = it.take(30) }, singleLine = true,
                    placeholder = { Text("Grupo " + (grupos.size + 1)) }
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { pidiendoNombre = false; onGuardar(nombre) }) { Text("Guardar") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pidiendoNombre = false }) { Text("Cancelar") }
            }
        )
    }
    paraBorrar?.let { g ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { paraBorrar = null },
            title = { Text("¿Borrar «" + g.nombre + "»?") },
            text = { Text("Solo se borra el grupo: sus lienzos y tablas siguen donde están.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { paraBorrar = null; onBorrar(g) }) { Text("Borrar") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { paraBorrar = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun Pastilla(
    texto: String,
    icono: androidx.compose.ui.graphics.vector.ImageVector?,
    fuerte: Boolean,
    onToque: () -> Unit,
    onLargo: (() -> Unit)? = null
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = if (fuerte) 0.22f else 0.12f))
            .pointerInput(texto) { detectTapGestures(onLongPress = { onLargo?.invoke() }) { onToque() } }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icono != null) Icon(icono, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp).padding(end = 2.dp))
        Text(texto, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** La tarjeta de «abrir otro», en la última ranura del abanico. */
@Composable
private fun CartaDeMas(
    ancho: androidx.compose.ui.unit.Dp,
    alto: androidx.compose.ui.unit.Dp,
    onOtro: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .width(ancho)
            .height(alto)
            .padding(top = CABECERA_DE_CARTA.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A2040))
            .border(1.dp, Multilienzo.MARCO, RoundedCornerShape(20.dp))
            .clickable(onClick = onOtro),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Abrir otro lienzo", tint = Color.White, modifier = Modifier.size(30.dp))
        Text("Abrir", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun CartaDeLaBaraja(
    lienzo: LienzoAbierto,
    esElActual: Boolean,
    ancho: androidx.compose.ui.unit.Dp,
    alto: androidx.compose.ui.unit.Dp,
    /** Dónde está su ranura en la lista, en píxeles. Va hacia ahí sola salvo que esté en el dedo. */
    suSitio: Float,
    /** Por dónde va la lista. Se lee al pintar. */
    corrido: androidx.compose.runtime.MutableFloatState,
    paso: Float,
    /** Lo que se apartan las vecinas de la que está en el centro, en píxeles. */
    aparte: Float,
    altura: Float,
    onAbrir: () -> Unit,
    onCerrar: () -> Unit,
    /** Mientras se arrastra: por dónde va. Devuelve lo que ha corrido la lista por ello. */
    alMover: (Float) -> Float,
    alSoltar: () -> Unit,
    contenido: (@Composable (LienzoAbierto) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val contexto = LocalContext.current
    var mapa by remember(lienzo.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(lienzo.id, lienzo.visto, contenido == null) { if (contenido == null) mapa = miniaturaDe(contexto, lienzo) }
    val x = remember { androidx.compose.animation.core.Animatable(suSitio) }
    var arrastrando by remember { mutableStateOf(false) }
    val ambito = androidx.compose.runtime.rememberCoroutineScope()
    val mover = androidx.compose.runtime.rememberUpdatedState(alMover)
    val soltar = androidx.compose.runtime.rememberUpdatedState(alSoltar)
    // A su ranura, con muelle: es lo que hace que las demás **se aparten** al pasar una por encima.
    LaunchedEffect(suSitio, arrastrando) {
        if (!arrastrando) x.animateTo(suSitio, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f))
    }
    val levantada = androidx.compose.animation.core.animateFloatAsState(if (arrastrando) 1f else 0f, label = "levantada")

    Column(
        modifier
            .zIndex(if (arrastrando) 100f else altura)
            .width(ancho)
            .height(alto)
            .graphicsLayer {
                // **Lo cerca que está del centro lo decide todo**: el giro, el tamaño y lo que
                // se le apartan las de al lado. Sin llegar nunca a plana.
                val d = if (paso > 0f) (x.value - corrido.floatValue) / paso else 0f
                val cerca = maxOf(1f - kotlin.math.abs(d).coerceAtMost(1f), levantada.value)
                translationX = (x.value - corrido.floatValue) + d.coerceIn(-1f, 1f) * aparte * (1f - levantada.value)
                rotationY = GIRO_DEL_ABANICO * (1f - ENDEREZA_EL_CENTRO * cerca)
                cameraDistance = 14f * density
                val crece = 0.88f + 0.12f * cerca + 0.05f * levantada.value
                scaleX = crece
                scaleY = crece
            }
            .pointerInput(lienzo.id) {
                // **Solo se mueve manteniéndola pulsada** (lo pidió el usuario dos veces): el
                // arrastre a secas es de la lista, que si no no hay forma de recorrerla.
                detectDragGesturesAfterLongPress(
                    onDragStart = { arrastrando = true },
                    onDragEnd = { soltar.value(); arrastrando = false },
                    onDragCancel = { soltar.value(); arrastrando = false }
                ) { cambio, delta ->
                    cambio.consume()
                    val nuevo = x.value + delta.x
                    val corridoPorElCanto = mover.value(nuevo)
                    ambito.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { x.snapTo(nuevo + corridoPorElCanto) }
                }
            }
            .pointerInput(lienzo.id) { detectTapGestures { onAbrir() } }
    ) {
        // Arriba, la X y el nombre. **La X va a la izquierda**: la derecha de cada tarjeta queda
        // debajo de la siguiente. De alto fijo, ver [CABECERA_DE_CARTA].
        Row(
            Modifier
                .fillMaxWidth()
                .height(CABECERA_DE_CARTA.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onCerrar),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, "Cerrar este lienzo", tint = Color.White, modifier = Modifier.size(14.dp))
            }
            Text(
                lienzo.nombre.ifBlank { "Sin nombre" },
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (esElActual) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0E1330))
                .border(
                    if (esElActual) 2.dp else 1.5.dp,
                    if (esElActual) Cristal.puesto else Multilienzo.MARCO,
                    RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            val actual = mapa
            if (!lienzo.esLienzo) {
                TarjetaDeOtraCosa(lienzo, Modifier.fillMaxSize())
            } else if (contenido != null) {
                contenido(lienzo)
            } else if (actual != null) {
                Image(
                    actual.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp)
                )
            } else {
                Icon(Icons.Filled.Description, contentDescription = null, tint = Color.White.copy(alpha = 0.35f))
            }
        }
    }
}

/** Lo girada que va cada tarjeta del abanico sobre su eje vertical, en grados. */
private const val GIRO_DEL_ABANICO = 38f

/**
 * Cada cuánto va una tarjeta, en anchos de tarjeta. **Pegaditas** (tercera corrección del usuario,
 * 18-sep-2026): con 0,86 quedaban demasiado sueltas. La que se ve entera es solo la del centro,
 * a la que las demás le hacen sitio ([APARTE_DEL_CENTRO]).
 */
private const val PASO_DEL_ABANICO = 0.34f

/** Cuánto corre la baraja por cada punto que corre el dedo. Ver [VistaDeTodos]. */
private const val FRENO_DE_LA_BARAJA = 0.4f

/** Lo que se apartan las vecinas de la del centro, en anchos de tarjeta: lo justo para verla entera. */
private const val APARTE_DEL_CENTRO = 0.5f

/** Cuánto se endereza la del centro: de 38° a unos 14°. Se ve bien y **sigue inclinada**, no plana. */
private const val ENDEREZA_EL_CENTRO = 0.62f

/** El ancho de la tarjeta de «abrir otro», en dp. */
private const val ANCHO_DE_LA_CARTA_DE_MAS = 76f

/** Lo que mide la cabecera de una tarjeta (el nombre y la X), en dp. Fija: ver [VistaDeTodos]. */
private const val CABECERA_DE_CARTA = 34f

/** Lo más estrecha que se deja una tarjeta, en dp: una columna de dos pestañas se toca igual. */
private const val ANCHO_MINIMO_DE_CARTA = 56f



/**
 * **Con un solo lienzo abierto, el gesto ofrece abrir otro** (lo pidió el usuario el
 * 17-sep-2026). Salen las hojas del proyecto en el que se está —las que no estén ya en la
 * rueda— y la opción de empezar uno en blanco. Lo que se abra se añade a la lista.
 */
@Composable
fun ElegirOtroLienzo(
    candidatos: List<LienzoAbierto>,
    onAbrir: (LienzoAbierto) -> Unit,
    onNuevo: () -> Unit,
    onCerrar: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Abrir otro lienzo") },
        text = {
            Column {
                Text(
                    if (candidatos.isEmpty()) "No hay más lienzos en este proyecto."
                    else "Se añadirá a tus lienzos abiertos: con cuatro dedos al lado pasas de uno a otro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(candidatos, key = { it.id }) { l ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onAbrir(l) }
                                .padding(horizontal = 6.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Description, contentDescription = null, Modifier.size(20.dp))
                            Text(
                                l.nombre.ifBlank { "Sin nombre" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onNuevo) { Text("Lienzo nuevo") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCerrar) { Text("Cancelar") }
        }
    )
}

/**
 * **La tira de lienzos: columnas vivas, con divisores que se arrastran.**
 *
 * Es la multitarea de dentro, calcada de la del plegable del usuario (18-sep-2026): todos los
 * lienzos **vivos y ya abiertos**; moverse a otro es moverse, no abrirlo. Cada columna tiene su
 * tamaño y el **divisor** entre dos se arrastra para repartirlo; lo mínimo es la **pestaña**, la
 * rayita por la que asoma el vecino y por la que se le trae con un toque. De pie, la tira va en
 * columna (arriba y abajo).
 *
 * La posición es un [Animatable] y los tamaños viven en [tamanos]; los dos se leen **al medir**,
 * así que correr la tira o arrastrar un divisor no recompone ningún lienzo.
 */
@Composable
fun TiraDeLienzos(
    lienzos: List<LienzoAbierto>,
    actual: String,
    corrida: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    /** El tamaño de cada columna en píxeles, por id. Se lee al medir. */
    tamanos: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>,
    enColumna: Boolean,
    porPantalla: Int,
    onMandar: (LienzoAbierto) -> Unit,
    /** Al soltar un divisor, para guardar los tamaños. */
    onTamanos: () -> Unit,
    /**
     * **Siempre hay pestaña a los dos lados**: donde no hay vecino sale una con un «+» que abre
     * otro lienzo. Lo pidió el usuario: que nunca falte por dónde tocar.
     */
    onOtro: () -> Unit = {},
    /**
     * En columna (de pie, apilados), lo que ocupan las barras de arriba y de abajo, en píxeles:
     * la tira vive entre ellas, así la pestaña del de abajo asoma **encima** de la barra y se
     * puede tocar sin pelearse con las herramientas.
     */
    arriba: Int = 0,
    abajo: Int = 0,
    /**
     * Lo que mide la pestaña del lienzo **de antes**, en píxeles (0: como la otra). En columna es
     * más alta que la de después: lleva la barra de arriba encima. Ver [Multilienzo.maximo].
     */
    pestanaDeAntes: Int = 0,
    /** Lo mismo para el lienzo **de después**: en columna lleva debajo la barra de abajo. */
    pestanaDeDespues: Int = 0,
    panel: @Composable (LienzoAbierto, Modifier) -> Unit
) {
    val elActual = lienzos.firstOrNull { it.id == actual }
    if (lienzos.size <= 1 || elActual == null) {
        panel(elActual ?: LienzoAbierto(id = actual), Modifier.fillMaxSize())
        return
    }
    val densidad = LocalDensity.current.density
    val pestana = (Multilienzo.PESTANA * densidad).toInt()
    val aire = (Multilienzo.AIRE * densidad).toInt()
    val medidas = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val pantalla = if (enColumna) (medidas.height - arriba - abajo).coerceAtLeast(1) else medidas.width
    val antes = pestanaDeAntes.takeIf { it > 0 } ?: pestana
    val despues = pestanaDeDespues.takeIf { it > 0 } ?: pestana
    val unidad = Multilienzo.unidad(pantalla, pestana, aire, porPantalla, antes, despues)
    fun tope(t: Int, i: Int) = Multilienzo.tamanoLibre(t, pantalla, pestana, antes, i, lienzos.size, despues)
    fun tamanoDe(i: Int) = tope(tamanos[lienzos[i].id] ?: unidad, i)
    // **El último no tiene divisor por su canto de fuera** —ahí no hay nadie: va pegado al borde—.
    // Se le cambia el tamaño desde el divisor que lo separa del anterior, **cuando la tira está
    // en su final**: tirar hacia dentro lo agranda, y él sigue pegado al borde.
    var conElUltimo by remember { mutableStateOf(false) }
    var pegadoAlFinal by remember { mutableStateOf(false) }
    var alEmpezarElUltimo by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // **El elástico del divisor**: lo que lleva estirado de más el lienzo [estirado], en píxeles.
    // Se lee al medir; al soltar vuelve a cero con muelle.
    val estiron = remember { androidx.compose.animation.core.Animatable(0f) }
    var estirado by remember { mutableStateOf<String?>(null) }
    val ambito = androidx.compose.runtime.rememberCoroutineScope()

    Layout(
        content = {
            lienzos.forEach { l ->
                key(l.id) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(CANTO_DEL_LIENZO.dp))
                            .border(2.dp, Multilienzo.MARCO, RoundedCornerShape(CANTO_DEL_LIENZO.dp))
                    ) {
                        panel(l, Modifier.fillMaxSize())
                        if (l.id != actual) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(l.id) { detectTapGestures { onMandar(l) } }
                            )
                        }
                    }
                }
            }
            // Los divisores, uno entre cada dos columnas: arrastrarlos cambia el tamaño de la
            // columna de antes, libremente y con un imán suave en los huecos justos.
            // Uno por columna, **la última también**: su canto derecho es su divisor, que si no
            // era la única que no se podía redimensionar (lo vio el usuario).
            // **Y de pie también**: con un solo hueco por pantalla no salían y en el teléfono el
            // usuario se quedó sin la barrita (18-sep-2026).
            for (i in 0 until lienzos.size - 1) {
                key("divisor-" + lienzos[i].id) {
                    val ultimo = lienzos.size - 1
                    Divisor(
                        enColumna,
                        tamanoAhora = {
                            // Se decide al empezar a arrastrar: ¿es el divisor del último, con la
                            // tira en su final?
                            val largoAhora = Multilienzo.largo(IntArray(lienzos.size) { tamanoDe(it) }, aire)
                            conElUltimo = i == ultimo - 1 && corrida.value >= (largoAhora - pantalla).toFloat() - 1f
                            pegadoAlFinal = conElUltimo
                            if (conElUltimo) tamanoDe(ultimo).also { alEmpezarElUltimo = it } else tamanoDe(i)
                        },
                        onTamano = { pedido ->
                            // Con el último el arrastre va al revés: hacia dentro crece.
                            val cual = if (conElUltimo) ultimo else i
                            val nuevo = if (conElUltimo) 2 * alEmpezarElUltimo - pedido else pedido
                            val justo = tope(nuevo, cual)
                            val t = tope(Multilienzo.imantar(justo, unidad, aire, porPantalla), cual)
                            if (t != tamanoDe(cual)) tamanos[lienzos[cual].id] = t
                            // Pasado el tope no crece: **se estira como una goma** y vuelve.
                            estirado = lienzos[cual].id
                            val goma = Multilienzo.elastico((nuevo - justo).toFloat(), pestana * 2.5f)
                            ambito.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { estiron.snapTo(goma) }
                        },
                        onSoltar = {
                            val eraElUltimo = conElUltimo
                            ambito.launch {
                                estiron.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 400f))
                                if (eraElUltimo) {
                                    // La tira se queda donde se ha estado pintando: en su final.
                                    val largoAhora = Multilienzo.largo(IntArray(lienzos.size) { tamanoDe(it) }, aire)
                                    corrida.snapTo((largoAhora - pantalla).toFloat().coerceAtLeast(0f))
                                    pegadoAlFinal = false
                                }
                            }
                            onTamanos()
                        },
                        // La zona del divisor tapa la pestaña del vecino: un toque en ella
                        // trae al lienzo de ese lado, igual que tocar la pestaña.
                        onTocar = { despuesDelDivisor ->
                            lienzos.getOrNull(if (despuesDelDivisor) i + 1 else i)?.takeIf { it.id != actual }?.let(onMandar)
                        }
                    )
                }
            }
            // El «+» de los extremos **se quitó** (18-sep-2026): otro lienzo se abre desde la baraja.
        }
    ) { medibles, restricciones ->
        val ancho = restricciones.maxWidth
        val alto = restricciones.maxHeight
        // De pie y apilados, la tira vive entre las barras: lo que mide y desde dónde.
        val largoVisible = if (enColumna) (alto - arriba - abajo).coerceAtLeast(1) else ancho
        val desde = if (enColumna) arriba else 0
        val grueso = if (enColumna) ancho else alto
        val goma = estiron.value.toInt()
        val suyos = IntArray(lienzos.size) { tamanoDe(it) + if (lienzos[it].id == estirado) goma else 0 }
        val xs = Multilienzo.sitios(suyos, aire)
        // Nunca más allá de la tira —un lienzo que encoge con la tira en su final dejaba un vacío
        // detrás—, y pegada al final mientras se le cambia el tamaño al último.
        val finDeLaTira = (Multilienzo.largo(suyos, aire) - largoVisible).toFloat().coerceAtLeast(0f)
        val desplazada = if (pegadoAlFinal) finDeLaTira else corrida.value.coerceIn(0f, finDeLaTira)
        val n = lienzos.size
        val cuantosDivisores = (medibles.size - n).coerceAtLeast(0)
        fun fijo(largo: Int) =
            if (enColumna) androidx.compose.ui.unit.Constraints.fixed(grueso, largo)
            else androidx.compose.ui.unit.Constraints.fixed(largo, grueso)
        // Solo se mide lo que se ve **y lo que está a una pantalla de verse**: así el vecino ya
        // está medido y pintado cuando entra, y la tira no se atasca a medio camino al correrla.
        val piezas = medibles.take(n).mapIndexed { i, m ->
            val x = xs[i] - desplazada
            if (x > 2 * largoVisible || x + suyos[i] < -largoVisible) null else m.measure(fijo(suyos[i])) to x.toInt()
        }
        // El divisor es **solo su asa**: fuera de ella el canto del lienzo es del lienzo.
        val asa = (grueso * 0.2f).toInt().coerceAtLeast(pestana * 3)
        val divisores = medibles.drop(n).take(cuantosDivisores).mapIndexed { i, m ->
            val x = xs[i] + suyos[i] - desplazada
            if (x > largoVisible || x + aire < 0) null
            else m.measure(
                if (enColumna) androidx.compose.ui.unit.Constraints.fixed(asa, aire + 2 * pestana)
                else androidx.compose.ui.unit.Constraints.fixed(aire + 2 * pestana, asa)
            ) to (x.toInt() - pestana)
        }
        layout(ancho, alto) {
            fun poner(p: androidx.compose.ui.layout.Placeable, x: Int, aLoAncho: Int = 0) {
                if (enColumna) p.place(aLoAncho, desde + x) else p.place(x, aLoAncho)
            }
            piezas.forEach { it?.let { (p, x) -> poner(p, x) } }
            divisores.forEach { it?.let { (p, x) -> poner(p, x, (grueso - asa) / 2) } }
        }
    }
}

/**
 * El divisor entre dos columnas: fino, del ancho del aire más las dos pestañas. Se arrastra y la
 * columna de antes cambia de tamaño **libremente**; la de después conserva el suyo y se aparta.
 * Al levantar, lo que haya se guarda.
 */
@Composable
private fun Divisor(
    enColumna: Boolean,
    tamanoAhora: () -> Int,
    onTamano: (Int) -> Unit,
    onSoltar: () -> Unit,
    /** Un toque a secas: `true` si cayó del lado de la columna de después. */
    onTocar: (Boolean) -> Unit = {}
) {
    val tocar = androidx.compose.runtime.rememberUpdatedState(onTocar)
    // Los topes cambian con las barras y con el orden de la lista: el gesto, que vive más que
    // una composición, tiene que llamar siempre a lo último.
    val tamanoAhoraYa = androidx.compose.runtime.rememberUpdatedState(tamanoAhora)
    val onTamanoYa = androidx.compose.runtime.rememberUpdatedState(onTamano)
    val onSoltarYa = androidx.compose.runtime.rememberUpdatedState(onSoltar)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(enColumna) {
                detectTapGestures { donde ->
                    tocar.value(if (enColumna) donde.y > size.height / 2f else donde.x > size.width / 2f)
                }
            }
            .pointerInput(enColumna) {
                var acumulado = 0
                var alEmpezar = 0
                // **Con más de un dedo en la pantalla el divisor no existe**: uno de los tres
                // dedos de correr la tira caía aquí y ganaba el redimensionar. Si llega otro
                // dedo a medio arrastre, el tamaño vuelve a como estaba.
                var cedido = false
                detectDragGestures(
                    onDragStart = { acumulado = 0; alEmpezar = tamanoAhoraYa.value(); cedido = false },
                    onDragEnd = { acumulado = 0; onSoltarYa.value() },
                    onDragCancel = { acumulado = 0; onSoltarYa.value() }
                ) { cambio, delta ->
                    if (cedido) return@detectDragGestures
                    if (com.forge.pixpin.motor.ManoEntera.dedos > 1) {
                        cedido = true
                        onTamanoYa.value(alEmpezar)
                        return@detectDragGestures
                    }
                    cambio.consume()
                    acumulado += (if (enColumna) delta.y else delta.x).toInt()
                    onTamanoYa.value(alEmpezar + acumulado)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // **Solo el asa pequeña del medio** (18-sep-2026): la línea de arriba abajo partía la
        // pantalla y no iba con el resto de la interfaz; el usuario solo pedía el asa.
        Box(
            Modifier
                .then(if (enColumna) Modifier.height(6.dp).fillMaxWidth(0.6f) else Modifier.width(6.dp).fillMaxHeight(0.6f))
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White)
                .border(1.dp, Color(0xCC000000), RoundedCornerShape(3.dp))
        )
    }
}

/** Lo redondeado del marco de cada lienzo, en dp. */
private const val CANTO_DEL_LIENZO = 16f

/** Para que el editor pueda pintar el borde del que manda sin repetir el color. */
@Composable
internal fun Modifier.marcoDelActivo(): Modifier =
    this.border(2.dp, Cristal.puesto.copy(alpha = 0.7f), RoundedCornerShape(18.dp))

/** El fondo de cristal de la multitarea, por si hace falta fuera. */
@Composable
internal fun Modifier.cristalDeMultilienzo(): Modifier = this.cristal()
