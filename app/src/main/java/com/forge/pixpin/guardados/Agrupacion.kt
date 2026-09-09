package com.forge.pixpin.guardados

/**
 * Cómo se **apilan** los mensajes seguidos: la agrupación de burbujas de Telegram.
 *
 * ## Por qué esto no es decoración
 *
 * Cuando uno guarda cinco cosas del tirón, cinco burbujas sueltas con su hueco y sus ocho
 * esquinas redondas se leen como cinco sucesos distintos, y no lo son: es **un rato**. Lo
 * que hace Telegram —y lo que hace que su chat se lea de un vistazo— es apretar lo que pasó
 * seguido: menos aire entre burbujas y la esquina que toca al vecino casi recta, de forma
 * que el grupo se ve como un bloque y el ojo cuenta ratos en vez de contar mensajes.
 *
 * ## La ventana es la de Telegram, no una inventada
 *
 * Cinco minutos ([VENTANA_DE_GRUPO_MS]), tal cual sale en `ChatActivity.java` al calcular
 * `pinnedTop` y `pinnedBottom`: `Math.abs(prev.date - message.date) <= 5 * 60` sobre fechas
 * en segundos. Allí se exige además **mismo autor**; aquí sobra, porque en un chat con uno
 * mismo todos los mensajes son propios. Queda solo el tiempo.
 *
 * ## Por qué se calcula por tramo de día y no sobre la lista entera
 *
 * La lista que se pinta ya viene troceada por [porDias], y entre tramo y tramo se mete el
 * separador de fecha. Dos mensajes a tres minutos uno de otro pero a caballo de la
 * medianoche **no se pueden agrupar**: tienen un separador en medio, y una burbuja con la
 * esquina recortada hacia un vecino que no está debajo se ve rota.
 *
 * Se podría pasar un `mismoDia: (Long, Long) -> Boolean` y comprobarlo mensaje a mensaje,
 * pero sería resolver dos veces el mismo problema —y con dos criterios de día que pueden
 * discrepar—. Aplicando [sitios] a cada [Tramo] por separado, el separador nunca cae dentro
 * de lo que se recorre y el caso desaparece **solo**, sin condición que mantener. El que
 * pinta ya tiene los tramos en la mano: le sale gratis.
 *
 * Todo lo de aquí son números y booleanos: sin Android, comprobable en la máquina de uno.
 */

/**
 * Lo que separa dos mensajes para que dejen de ser el mismo rato: cinco minutos.
 *
 * Telegram lo compara en segundos (`5 * 60`); aquí las fechas van en milisegundos.
 */
const val VENTANA_DE_GRUPO_MS = 5 * 60 * 1000L

/** El redondeo normal de una burbuja. Telegram: `SharedConfig.bubbleRadius = 17`. */
const val RADIO_BURBUJA = 17

/**
 * El redondeo de la esquina que toca al vecino de arriba o de abajo.
 *
 * `ChatMessageCell.java:10978`: `nearRad = min(dp(3), rad)`. No se pone recta del todo —tres
 * puntos de radio bastan para que el bloque se lea junto sin que las burbujas parezcan
 * cajas.
 */
const val RADIO_VECINO = 3

/**
 * Dónde cae un mensaje dentro de su grupo.
 *
 * Los dos `pinnedTop` / `pinnedBottom` de Telegram, con el nombre en claro: [pegadoArriba]
 * es que el de encima es del mismo rato, [pegadoAbajo] que lo es el de debajo. De estos dos
 * booleanos salen el hueco, los radios y el relleno; nada más hace falta saber.
 */
data class Sitio(val pegadoArriba: Boolean, val pegadoAbajo: Boolean) {
    /** Está solo: ni arriba ni abajo tiene compañía. Burbuja entera, con su cola. */
    val suelto: Boolean get() = !pegadoArriba && !pegadoAbajo

    /** Es el de abajo del todo de su grupo — el que lleva la cola. */
    val ultimoDelGrupo: Boolean get() = !pegadoAbajo
}

/**
 * El sitio de cada mensaje de **un tramo de día**, en el mismo orden que entraron.
 *
 * Espera la lista tal y como se pinta: de más viejo a más nuevo, que es lo que devuelve
 * [deSeccion] para la conversación. Un solo recorrido, comparando cada uno con el anterior:
 * O(n), porque esto se recalcula en cada recomposición de la lista.
 *
 * El primero nunca está pegado arriba y el último nunca pegado abajo — no por un caso
 * especial, sino porque no tienen con quién.
 */
fun sitios(mensajes: List<Mensaje>): List<Sitio> {
    if (mensajes.isEmpty()) return emptyList()
    val juntos = BooleanArray(mensajes.size - 1) { i ->
        cabenJuntos(mensajes[i].cuando, mensajes[i + 1].cuando)
    }
    return List(mensajes.size) { i ->
        Sitio(
            pegadoArriba = i > 0 && juntos[i - 1],
            pegadoAbajo = i < juntos.size && juntos[i]
        )
    }
}

/**
 * Los sitios de varios tramos de golpe, indexados por identificador de mensaje.
 *
 * Una `LazyColumn` que pinta días y mensajes mezclados no tiene a mano el índice del mensaje
 * dentro de su día, pero sí el mensaje. Se calcula todo una vez fuera de la lista y cada
 * burbuja mira el suyo. Si algún identificador se repitiera, gana el último — que es también
 * el que se pintaría.
 */
fun sitiosPorId(tramos: List<Tramo>): Map<String, Sitio> {
    val salida = HashMap<String, Sitio>()
    for (tramo in tramos) {
        val suyos = sitios(tramo.mensajes)
        for (i in tramo.mensajes.indices) salida[tramo.mensajes[i].id] = suyos[i]
    }
    return salida
}

/** Dos mensajes son del mismo rato si no los separa más de [VENTANA_DE_GRUPO_MS]. */
fun cabenJuntos(antes: Long, despues: Long): Boolean =
    kotlin.math.abs(despues - antes) <= VENTANA_DE_GRUPO_MS

/**
 * El hueco que va **encima** de la burbuja, en dp.
 *
 * Dos números y toda la diferencia: agrupado aprieta (2), suelto respira (8). Es lo único
 * que separa un bloque del siguiente, así que el hueco pequeño tiene que ser de verdad
 * pequeño o los grupos dejan de leerse como grupos.
 */
fun espacioAntes(sitio: Sitio): Int = if (sitio.pegadoArriba) 2 else 8

/** Los cuatro radios de la burbuja, en dp, en sentido horario desde arriba a la izquierda. */
data class Radios(
    val arribaIzq: Int,
    val arribaDer: Int,
    val abajoDer: Int,
    val abajoIzq: Int
)

/**
 * Los radios de la burbuja según dónde cae.
 *
 * Se recorta **solo el lado propio**, el derecho, porque aquí todo es saliente: es el lado
 * por el que las burbujas se tocan y donde vive la cola. El izquierdo se queda a [RADIO_BURBUJA]
 * siempre, y ese contraste es lo que hace que un grupo se vea como una columna pegada al
 * borde en vez de como tres pastillas sueltas.
 *
 * `ChatMessageCell.java:11016-11029`: con `pinnedTop` va `tr = nearRad` si es propio, y con
 * `pinnedBottom`, `br = nearRad` (para lo ajeno son las esquinas izquierdas).
 */
fun radios(sitio: Sitio): Radios = Radios(
    arribaIzq = RADIO_BURBUJA,
    arribaDer = if (sitio.pegadoArriba) RADIO_VECINO else RADIO_BURBUJA,
    abajoDer = if (sitio.pegadoAbajo) RADIO_VECINO else RADIO_BURBUJA,
    abajoIzq = RADIO_BURBUJA
)

/** El relleno de dentro de la burbuja, en dp. [fin] es el lado de la cola. */
data class Relleno(val inicio: Int, val arriba: Int, val fin: Int, val abajo: Int)

/**
 * El relleno interno, asimétrico a propósito.
 *
 * `ChatMessageCell.java:3364`: `dp(drawPinnedBottom ? 12 : 18)`. Los seis puntos de más del
 * último del grupo no son aire decorativo — es el sitio que ocupa **la cola**, que sale por
 * ese lado. Sin ellos el texto se le mete dentro y la burbuja parece mal dibujada; con ellos
 * en todas, las burbujas agrupadas se ven torcidas respecto a la de la cola.
 */
fun relleno(sitio: Sitio): Relleno = Relleno(
    inicio = 12,
    arriba = 7,
    fin = if (sitio.ultimoDelGrupo) 18 else 12,
    abajo = 7
)

// -----------------------------------------------------------------------------------
// Agrupación por origen (lo que se guardó junto, junto se enseña)
// -----------------------------------------------------------------------------------

/**
 * De qué documento viene un mensaje, a efectos de agrupar las miniaturas.
 *
 * Tres familias se agrupan cuando van seguidas: las páginas de un mismo PDF
 * (comparten [Mensaje.ruta]), las hojas de un mismo lienzo (comparten
 * [Mensaje.referencia]) y las notas de voz. El resto —fotos, archivos sueltos,
 * notas— no tiene origen que agrupar y cada uno va por su cuenta. `null`
 * significa «no agrupar».
 */
fun claveDeOrigen(m: Mensaje): String? = when (m.clase) {
    Clase.PAGINA -> m.ruta?.let { "pdf:$it" }
    Clase.DIBUJO -> m.referencia?.let { "lienzo:$it" }
    Clase.VOZ -> "voz:${m.proyecto.orEmpty()}"
    else -> null
}

/** Un tramo seguido de mensajes del mismo origen: de [desde] a [hasta], ambos dentro. */
data class RachaDeOrigen(val clave: String, val desde: Int, val hasta: Int) {
    val tamano: Int get() = hasta - desde + 1
}

/**
 * Las rachas seguidas del mismo origen dentro de una lista.
 *
 * Solo cuentan las rachas de **dos o más** (una página sola de un PDF no es un
 * grupo), y solo mientras no se interrumpan: si entre dos páginas del mismo PDF
 * se cuela una foto, las dos páginas van por su lado. Devuelve los índices de
 * cada racha sobre [mensajes], para que quien pinta las pueda plegar sin copiar
 * la lista.
 */
fun rachasDeOrigen(mensajes: List<Mensaje>): List<RachaDeOrigen> {
    val salida = ArrayList<RachaDeOrigen>()
    var desde = 0
    var clave: String? = null
    for (i in mensajes.indices) {
        val c = claveDeOrigen(mensajes[i])
        if (c != clave) {
            val racha = RachaDeOrigen(clave ?: "", desde, i - 1)
            if (clave != null && racha.tamano > 1) salida += racha
            clave = c
            desde = i
        }
    }
    val ultima = RachaDeOrigen(clave ?: "", desde, mensajes.size - 1)
    if (clave != null && ultima.tamano > 1) salida += ultima
    return salida
}

/**
 * Lo que la lista enseña por cada mensaje, con las rachas del mismo origen ya plegadas.
 *
 * Quien pinta recibe una entrada por mensaje suelto y **una sola** por cada racha de dos o
 * más del mismo origen —las páginas seguidas de un mismo PDF, las hojas de un mismo lienzo,
 * las notas de voz—. La UI decide cómo dibujar el grupo; aquí solo se decide qué es grupo.
 */
sealed interface EntradaDeLista {
    /** Un mensaje normal, sin compañía del mismo origen. */
    data class MensajeSolo(val mensaje: Mensaje) : EntradaDeLista

    /** La racha entera, en orden, con su clave de origen. */
    data class GrupoDeOrigen(val clave: String, val mensajes: List<Mensaje>) : EntradaDeLista
}

/** Convierte la lista de un tramo en sus entradas, plegando las rachas de [rachasDeOrigen]. */
fun entradasDe(mensajes: List<Mensaje>): List<EntradaDeLista> {
    if (mensajes.isEmpty()) return emptyList()
    val salida = ArrayList<EntradaDeLista>(mensajes.size)
    var i = 0
    for (g in rachasDeOrigen(mensajes)) {
        while (i < g.desde) {
            salida += EntradaDeLista.MensajeSolo(mensajes[i])
            i++
        }
        salida += EntradaDeLista.GrupoDeOrigen(g.clave, mensajes.subList(g.desde, g.hasta + 1))
        i = g.hasta + 1
    }
    while (i < mensajes.size) {
        salida += EntradaDeLista.MensajeSolo(mensajes[i])
        i++
    }
    return salida
}
