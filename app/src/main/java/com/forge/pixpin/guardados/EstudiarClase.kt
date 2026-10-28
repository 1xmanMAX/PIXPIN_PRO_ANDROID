package com.forge.pixpin.guardados

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * **Estudiar una clase grabada** (25-oct-2026, PC `3af2647` y `ce6c058`, el usuario: «una ventana
 * especial … donde se vea la grabación del vídeo con su transcripción al lado; al otro lado las
 * capturas, los marcadores … y que se pueda hacer scroll de las capturas y del texto, que una siga a
 * la otra»). Lo puro de [EstudiarClaseActivity], las mismas cuentas que
 * `crates/pixpin-proyecto/src/clase.rs` del PC, y se prueba en `EstudiarClaseTest`.
 *
 * - **Los marcadores** de una nota de voz: `marcadores: [{ms, texto}]` en orden ([Mensaje.marcadores],
 *   en el `resto` del PC). Distintos de las banderitas ([Mensaje.marcas], solo milisegundos): un
 *   marcador lleva su nota. **Sin ninguno la clave se quita** (null, nunca `[]`), como las
 *   banderitas: el resumen de sincronizar es un hash del JSON, y una nota que nunca tuvo marcadores
 *   sigue siendo byte a byte la de antes. Las claves `ms` y `texto` van en ese orden, el alfabético
 *   en que las escribe el PC.
 * - **Qué toca en un milisegundo**: la línea del texto y la captura.
 * - **El scroll que se sigue**: de un punto de una lista a su milisegundo, y de ahí al punto de la
 *   otra, con su ancla a un tercio.
 * - **La barra del tiempo**: de x a milisegundo, y un toque cerca de una captura o de un marcador
 *   se queda en él.
 */
object EstudiarClase {
    /** Lo que mide como mucho la nota de un marcador: es una nota al margen, no un documento. */
    const val NOTA_MAXIMA = 200

    /** Un marcador puesto al estudiar: en qué milisegundo del audio y qué dice. */
    data class Marcador(val ms: Long, val texto: String)

    /** Los marcadores de una nota, en orden. Lo que no se entiende se salta sin llevarse lo demás. */
    fun marcadoresDe(lista: JsonArray?): List<Marcador> =
        lista.orEmpty().mapNotNull { e ->
            val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            val ms = runCatching { o["ms"]?.jsonPrimitive?.takeIf { !it.isString }?.longOrNull }.getOrNull()
                ?: return@mapNotNull null
            val texto = runCatching { o["texto"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull }.getOrNull().orEmpty()
            Marcador(ms.coerceAtLeast(0), texto)
        }.sortedBy { it.ms }

    /** La lista que se guarda: null sin ninguno (la clave se quita). */
    fun aJson(v: List<Marcador>): JsonArray? =
        if (v.isEmpty()) null else buildJsonArray {
            for (m in v.sortedBy { it.ms }) add(buildJsonObject {
                put("ms", JsonPrimitive(m.ms))
                put("texto", JsonPrimitive(m.texto))
            })
        }

    /**
     * Pone un marcador en [ms] con [texto] (los blancos juntos, sin saltos de línea y cortado a
     * [NOTA_MAXIMA]). Si ya había uno en ese mismo milisegundo, se le cambia la nota: dos marcadores
     * en el mismo sitio no se distinguen en la barra.
     */
    fun ponerMarcador(lista: JsonArray?, ms: Long, texto: String): JsonArray? {
        val limpio = texto.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val corto = limpio.codePoints().limit(NOTA_MAXIMA.toLong()).toArray().let { String(it, 0, it.size) }
        val donde = ms.coerceAtLeast(0)
        val v = marcadoresDe(lista).toMutableList()
        val i = v.indexOfFirst { it.ms == donde }
        if (i >= 0) v[i] = Marcador(donde, corto) else v += Marcador(donde, corto)
        return aJson(v)
    }

    /** Quita el marcador de [ms]: (la lista nueva, si había alguno). */
    fun quitarMarcador(lista: JsonArray?, ms: Long): Pair<JsonArray?, Boolean> {
        val v = marcadoresDe(lista)
        val quedan = v.filter { it.ms != ms }
        return if (quedan.size == v.size) lista to false else aJson(quedan) to true
    }

    // --- Qué toca en un milisegundo ---------------------------------------------------------

    /**
     * El último de [tiempos] (en orden) que ya pasó en [ms]. Antes del primero, el primero: en una
     * lista de capturas algo hay que enseñar. Null si la lista está vacía.
     */
    fun ultimoAntes(tiempos: List<Long>, ms: Long): Int? {
        if (tiempos.isEmpty()) return null
        return tiempos.indexOfLast { it <= ms }.takeIf { it >= 0 } ?: 0
    }

    /**
     * La línea del texto que suena en [ms]: la última con minuto que ya pasó. Null antes de la
     * primera o si ninguna lleva minuto (resaltar una al azar sería mentir).
     */
    fun lineaEn(lineas: List<Long?>, ms: Long): Int? =
        lineas.indexOfLast { it != null && it in 0..ms }.takeIf { it >= 0 }

    /** Una fila de la columna del texto: una línea de la transcripción o un marcador, por su sitio. */
    sealed interface Fila {
        data class Linea(val i: Int) : Fila
        data class DeMarcador(val j: Int) : Fila
    }

    /**
     * **El texto con los marcadores metidos en su minuto.** Las líneas conservan su orden; cada
     * marcador va delante de la primera línea con minuto posterior a él. Una línea sin minuto no
     * mueve a nadie.
     */
    fun mezclar(lineas: List<Long?>, marcadores: List<Long>): List<Fila> {
        val v = ArrayList<Fila>(lineas.size + marcadores.size)
        var m = 0
        lineas.forEachIndexed { i, t ->
            if (t != null) while (m < marcadores.size && marcadores[m] < t) v += Fila.DeMarcador(m++)
            v += Fila.Linea(i)
        }
        while (m < marcadores.size) v += Fila.DeMarcador(m++)
        return v
    }

    /**
     * El milisegundo de cada fila para seguir el scroll: el suyo, o el de la anterior si no lo
     * tiene (0 al principio). Nunca baja: una lista que retrocede en el tiempo no se puede seguir.
     */
    fun tiemposDeFilas(filas: List<Fila>, lineas: List<Long?>, marcadores: List<Long>): List<Long> {
        var ultimo = 0L
        return filas.map { f ->
            val t = when (f) {
                is Fila.Linea -> lineas.getOrNull(f.i)
                is Fila.DeMarcador -> marcadores.getOrNull(f.j)
            }
            ultimo = maxOf(t ?: ultimo, ultimo)
            ultimo
        }
    }

    // --- El scroll que se sigue -------------------------------------------------------------

    /** Lo más abajo que se puede bajar una lista en una vista de [altoVista]. */
    fun tope(altos: List<Float>, altoVista: Float): Float = (altos.sum() - altoVista).coerceAtLeast(0f)

    /**
     * **El milisegundo de un punto [y] de una lista** (sin scroll). Dentro de una fila se reparte
     * hasta el milisegundo de la siguiente: así una fila larga avanza poco a poco y la otra lista se
     * mueve suave, no a saltos. Null si está vacía.
     */
    fun msEn(tiempos: List<Long>, altos: List<Float>, y: Float): Long? {
        val n = minOf(tiempos.size, altos.size)
        if (n == 0) return null
        var arriba = 0f
        for (i in 0 until n) {
            val alto = altos[i].coerceAtLeast(0f)
            if (y < arriba + alto || i + 1 == n) {
                val t0 = tiempos[i]
                val t1 = if (i + 1 < n) tiempos[i + 1] else t0
                val f = if (alto > 0f) ((y - arriba) / alto).coerceIn(0f, 1f) else 0f
                return t0 + Math.round((t1 - t0).toFloat() * f)
            }
            arriba += alto
        }
        return null
    }

    /** **El punto de una lista donde cae [ms]** (lo contrario de [msEn]). Con [suave], dentro de su fila. */
    fun yDe(tiempos: List<Long>, altos: List<Float>, ms: Long, suave: Boolean): Float? {
        val n = minOf(tiempos.size, altos.size)
        if (n == 0) return null
        val i = ultimoAntes(tiempos.subList(0, n), ms) ?: return null
        val arriba = altos.subList(0, i).sumOf { it.coerceAtLeast(0f).toDouble() }.toFloat()
        if (!suave || i + 1 >= n) return arriba
        val t0 = tiempos[i]
        val t1 = tiempos[i + 1]
        if (t1 <= t0 || ms <= t0) return arriba
        val f = ((ms - t0).toFloat() / (t1 - t0)).coerceIn(0f, 1f)
        return arriba + altos[i].coerceAtLeast(0f) * f
    }

    /** Dónde se lee una lista que sigue al tiempo: a un tercio de su alto. Lo que viene después se ve. */
    const val ANCLA = 0.3f

    /** **El scroll de una lista para que [ms] quede en su ancla**, sin salirse. */
    fun scrollPara(tiempos: List<Long>, altos: List<Float>, altoVista: Float, ms: Long, suave: Boolean): Float {
        val y = yDe(tiempos, altos, ms, suave) ?: return 0f
        return (y - altoVista * ANCLA).coerceIn(0f, tope(altos, altoVista))
    }

    /**
     * **El milisegundo que se está leyendo** en una lista con [scroll]: el de su ancla. Al bajar del
     * todo, el ancla baja con la vista hasta el final: si no, las últimas filas no se leerían nunca.
     */
    fun msLeido(tiempos: List<Long>, altos: List<Float>, altoVista: Float, scroll: Float): Long? {
        val t = tope(altos, altoVista)
        val ancla = if (t > 0f && scroll >= t - 0.5f) altoVista else altoVista * ANCLA
        return msEn(tiempos, altos, scroll + ancla)
    }

    /** **El scroll de la otra lista** cuando se mueve una: pone en su ancla el mismo milisegundo. */
    fun seguir(
        deTiempos: List<Long>, deAltos: List<Float>, deVista: Float, deScroll: Float,
        aTiempos: List<Long>, aAltos: List<Float>, aVista: Float
    ): Float? {
        val ms = msLeido(deTiempos, deAltos, deVista, deScroll) ?: return null
        if (aTiempos.isEmpty()) return null
        val t = tope(deAltos, deVista)
        // Al final de la que se mueve, la otra también al final.
        if (t > 0f && deScroll >= t - 0.5f) return tope(aAltos, aVista)
        return scrollPara(aTiempos, aAltos, aVista, ms, true)
    }

    // --- La barra del tiempo ----------------------------------------------------------------

    /** El milisegundo de un punto de la barra: [x] desde su izquierda. */
    fun msDeLaBarra(x: Float, ancho: Float, duracionMs: Long): Long {
        if (ancho <= 0f || duracionMs <= 0) return 0
        return Math.round((x / ancho).coerceIn(0f, 1f) * duracionMs.toDouble())
    }

    /** La x de un milisegundo en la barra. */
    fun xEnLaBarra(ms: Long, ancho: Float, duracionMs: Long): Float {
        if (duracionMs <= 0) return 0f
        return (ms.toFloat() / duracionMs).coerceIn(0f, 1f) * ancho
    }

    /**
     * **Un toque en la barra cerca de una señal se queda en la señal**: las capturas y los
     * marcadores son adonde se quiere ir, y acertar con el dedo en un palito es imposible.
     * [tolerancia] en píxeles.
     */
    fun pegarASenal(x: Float, ancho: Float, duracionMs: Long, senales: List<Long>, tolerancia: Float): Long {
        val ms = msDeLaBarra(x, ancho, duracionMs)
        return senales.map { it to Math.abs(xEnLaBarra(it, ancho, duracionMs) - x) }
            .filter { it.second <= tolerancia }
            .minByOrNull { it.second }?.first ?: ms
    }

    /** Salta [delta] desde [ms] sin salirse de la nota (sin duración, sin fin). */
    fun saltar(ms: Long, delta: Long, duracionMs: Long): Long {
        val fin = if (duracionMs > 0) duracionMs else Long.MAX_VALUE
        val suma = if (delta > 0 && ms > Long.MAX_VALUE - delta) Long.MAX_VALUE else ms + delta
        return suma.coerceIn(0, fin)
    }

    // --- Lo que se lee de la nota -----------------------------------------------------------

    /** Una línea del texto: en qué milisegundo empieza (o null) y qué dice. */
    data class Linea(val ms: Long?, val texto: String)

    /**
     * Las líneas de la transcripción, como la letra ([LetraActivity]): un párrafo por bloque y, si
     * empieza por `[m:ss]`, su minuto. Fuera lo de Markdown que no se lee (`#`, `**`, `![…]`).
     */
    fun lineasDe(transcripcion: String?): List<Linea> {
        if (transcripcion.isNullOrBlank()) return emptyList()
        return transcripcion.split(Regex("\n\\s*\n")).mapNotNull { bloque ->
            val limpio = bloque.lines()
                .filterNot { it.trimStart().startsWith("![") }
                .joinToString("\n") { it.trimStart('#', ' ').replace("**", "").replace("__", "") }
                .trim()
            if (limpio.isBlank()) return@mapNotNull null
            val con = Transcriptor.tiempoDe(limpio)
            if (con != null) Linea(con.first.toLong(), con.second.trim()) else Linea(null, limpio)
        }
    }

    /**
     * **Las líneas de una canción** (28-oct-2026, la música se estudia en esta misma pantalla): las de
     * su letra ([LetraDeCancion.leer]), con su minuto si lo llevan. Una letra sin tiempos va renglón a
     * renglón (no por párrafos: una estrofa entera sería una sola línea) y sus renglones en blanco se
     * quedan, que separan las estrofas. Una marca sin texto (un silencio del LRC) es una línea vacía con
     * su minuto: la que sonaba deja de resaltarse.
     */
    fun lineasDeLaCancion(letra: String?): List<Linea> {
        if (letra.isNullOrBlank()) return emptyList()
        return LetraDeCancion.leer(letra).lineas.map { Linea(it.ms?.toLong(), it.texto) }
    }

    /**
     * **Las líneas de lo que se estudia**: la letra si es una canción ([lineasDeLaCancion]), la
     * transcripción si no ([lineasDe]).
     */
    fun lineasDeLaNota(m: Mensaje?): List<Linea> =
        if (m?.esMusica == true) lineasDeLaCancion(m.transcripcion) else lineasDe(m?.transcripcion)

    /**
     * **Las señales de la barra**: los marcadores y las banderitas del móvil juntos y en orden, como
     * la ventana del PC, que enseña las dos cosas a la vez. (Cada una se quita de su campo.)
     */
    fun senales(marcadores: List<Marcador>, banderitas: List<Int>): List<Long> =
        (marcadores.map { it.ms } + banderitas.map { it.toLong() }).distinct().sorted()

    /** Si un mensaje es la nota de voz de una clase grabada (se puede estudiar). */
    fun esDeUnaClase(m: Mensaje): Boolean = m.clase == Clase.VOZ && (m.grabacion == "clase" || m.capturas != null)

    /**
     * La nota de la clase de un mensaje: ella misma si es la nota; la de su `enGrabacion.de` si es
     * una captura o el vídeo de una clase. Null si no es de ninguna.
     */
    fun notaDe(m: Mensaje, todos: List<Mensaje>): Mensaje? {
        if (esDeUnaClase(m)) return m
        // **La carpeta de una grabación** se estudia desde su nota (25-oct-2026, `clase_que_estudia`
        // del PC, e1150e8): es su vista natural. Sin nota (no se pudo cerrar), no hay qué estudiar.
        CarpetaDePaginas.deMensaje(m)?.takeIf { it.esGrabacion && it.voz.isNotEmpty() }?.let { c ->
            return todos.firstOrNull { it.id == c.voz }
        }
        val de = runCatching { m.enGrabacion?.get("de")?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return null
        return todos.firstOrNull { it.id == de }?.takeIf { esDeUnaClase(it) }
    }

    /**
     * **Lo que se enseña como posición mientras se arrastra la barra** (25-oct-2026, `posicion_a_la_vista`
     * del PC, 8d7fe93): donde está el dedo, no donde dice el reproductor. El vídeo, al buscar, cae en
     * el fotograma clave más cercano y en una grabación de pantalla dos claves pueden estar a medio
     * vídeo: el tirador saltaba lejos del dedo.
     */
    fun posicionALaVista(medioMs: Long, arrastrando: Long?): Long = arrastrando ?: medioMs

    // --- Cómo se reparte la pantalla ------------------------------------------------------------

    /**
     * **Cómo se reparte la pantalla** (27-oct-2026, el usuario: «una forma de agrandar la zona de la
     * imagen para hacer zoom y ver más la captura o el vídeo»). Tres formas:
     *
     * - [Forma.VERTICAL] (el teléfono de pie): la imagen arriba y grande; debajo, apretados, la barra
     *   del tiempo, la tira de capturas y los mandos; abajo el texto, **que se puede esconder**, y
     *   entonces la imagen se queda con su sitio.
     * - [Forma.APAISADA] (el teléfono tumbado): **nada de mandos debajo**, que se comen el alto. La
     *   imagen a un lado y, en el lado del riel (el derecho), los mandos en columna con la barra del
     *   tiempo de pie y la tira de capturas en columna. El texto empieza escondido y, si se pide, va
     *   en otra columna al lado.
     * - [Forma.ENTERA] (el botón de la esquina, como YouTube): solo la imagen; los mandos van encima
     *   y un toque los enseña o los esconde.
     *
     * El texto se enciende y se apaga **por separado en cada forma** ([textoEnVertical],
     * [textoEnApaisada]): esconderlo tumbado no lo esconde al volver a ponerlo de pie.
     */
    enum class Forma { VERTICAL, APAISADA, ENTERA }

    /** Lo que se enseña y cuánto mide (en dp). Sale de [reparto]. */
    data class Reparto(
        val forma: Forma,
        /** Si se ve la columna del texto. */
        val conTexto: Boolean,
        /** Si se ve la tira de capturas (no la hay sin capturas ni a pantalla entera). */
        val conTira: Boolean,
        /** De pie y con texto, el alto de la imagen; null es «lo que quede» (sin texto, o tumbado). */
        val altoImagen: Float?,
        /** Tumbado y con texto, el ancho de su columna (0 sin ella). */
        val anchoTexto: Float,
        /** El tope del alto de las miniaturas: **el alto es fijo** y el ancho sale de cada captura. */
        val altoMiniatura: Float,
        /** Lo más y lo menos que se ensancha una miniatura (una captura muy alargada no se come la tira). */
        val anchoMaximoMiniatura: Float,
        val anchoMinimoMiniatura: Float
    )

    /** De pie: lo que ocupan la barra del tiempo (30) y la fila de los mandos (44). */
    const val ALTO_DE_LOS_MANDOS = 74f

    /** Lo que ocupa el minuto escrito debajo de cada miniatura. */
    const val ALTO_DEL_MINUTO = 16f

    /** Lo menos que se deja al texto de pie: unas tres líneas. */
    const val ALTO_MINIMO_DEL_TEXTO = 140f

    /** La parte del alto que se lleva la imagen de pie y con texto: casi la mitad, más que antes (260 dp). */
    const val PARTE_DE_LA_IMAGEN = 0.48f

    /** Lo mismo para el cartel de la línea que suena (sin imagen): un tercio largo. */
    const val PARTE_DEL_CARTEL = 0.34f

    /**
     * **El reparto para una pantalla de [ancho]×[alto] dp.** Tumbada si es más ancha que alta;
     * [pantallaEntera] gana a todo.
     */
    fun reparto(
        ancho: Float, alto: Float, pantallaEntera: Boolean,
        textoEnVertical: Boolean, textoEnApaisada: Boolean, hayCapturas: Boolean,
        /**
         * Si arriba va una imagen (el vídeo o las capturas). Sin ella (una nota de voz, una canción)
         * arriba va **el cartel con la línea que suena en grande**, que no necesita tanto sitio:
         * [PARTE_DEL_CARTEL] en vez de [PARTE_DE_LA_IMAGEN] (28-oct-2026).
         */
        conImagen: Boolean = true
    ): Reparto {
        val forma = when {
            pantallaEntera -> Forma.ENTERA
            ancho > alto -> Forma.APAISADA
            else -> Forma.VERTICAL
        }
        return when (forma) {
            Forma.ENTERA -> Reparto(forma, false, false, null, 0f, 52f, 96f, 26f)
            Forma.VERTICAL -> {
                val miniatura = 52f
                val conTira = hayCapturas
                val conTexto = textoEnVertical
                val altoImagen = if (!conTexto) null else {
                    val fijo = ALTO_DE_LOS_MANDOS + (if (conTira) miniatura + ALTO_DEL_MINUTO else 0f)
                    val maximo = (alto - fijo - ALTO_MINIMO_DEL_TEXTO).coerceAtLeast(120f)
                    (alto * (if (conImagen) PARTE_DE_LA_IMAGEN else PARTE_DEL_CARTEL)).coerceIn(minOf(160f, maximo), maximo)
                }
                Reparto(forma, conTexto, conTira, altoImagen, 0f, miniatura, 96f, 26f)
            }
            Forma.APAISADA -> {
                val conTexto = textoEnApaisada
                // El texto, una columna que se lee (≥ 200 dp) sin dejar la imagen en una rendija.
                val anchoTexto = if (conTexto) (ancho * 0.32f).coerceIn(200f, 360f).coerceAtMost(ancho * 0.4f) else 0f
                Reparto(forma, conTexto, hayCapturas, null, anchoTexto, 44f, 80f, 24f)
            }
        }
    }

    /**
     * **La medida de una miniatura** de una captura de [ancho]×[alto] píxeles (27-oct-2026, el usuario:
     * «que las capturas mantengan su proporción real: si es A×A, cuadrada; si es A×B, proporcional»).
     * El alto es [topeAlto] y el ancho sale de la proporción, entre [anchoMinimo] y [anchoMaximo]. Una
     * muy ancha que topa con el máximo baja de alto para no deformarse; una muy alta se queda en el
     * mínimo y la imagen va encajada dentro. Sin medidas (aún no se ha leído), cuadrada.
     */
    fun medidaDeMiniatura(ancho: Int, alto: Int, topeAlto: Float, anchoMinimo: Float, anchoMaximo: Float): Pair<Float, Float> {
        if (ancho <= 0 || alto <= 0 || topeAlto <= 0f) return topeAlto.coerceAtLeast(0f) to topeAlto.coerceAtLeast(0f)
        val proporcion = ancho.toFloat() / alto
        val w = (topeAlto * proporcion).coerceIn(minOf(anchoMinimo, anchoMaximo), anchoMaximo)
        val h = minOf(topeAlto, w / proporcion)
        return w to h
    }
}
