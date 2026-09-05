package com.forge.pixpin.guardados

import kotlinx.serialization.Serializable

/**
 * Mensajes guardados: **un chat con uno mismo**.
 *
 * ## Por qué una conversación y no una lista de archivos
 *
 * Un gestor de documentos con carpetas obliga a decidir dónde va cada cosa **antes** de
 * guardarla, y esa decisión es justo la que hace que uno no guarde nada: se queda con «ya
 * lo ordenaré» y ahí muere. Una conversación no pregunta nada — se manda y ya está — y
 * encima ordena sola por lo único que uno recuerda de verdad: cuándo fue.
 *
 * Y deja **comentar**, que es lo que un cajón de archivos no deja: al lado de un PDF puede
 * ir una frase diciendo por qué se guardó, y las dos cosas se leen juntas.
 *
 * ## Qué se copia de Telegram y qué no
 *
 * Se copia lo que hace que aquello funcione: las **secciones por tipo** —fotos, archivos,
 * voz— cada una con su propia lista en vez de un filtro sobre el montón; el orden por
 * fecha con separadores de día; los fijados arriba; y que el sitio no pregunte nada al
 * guardar.
 *
 * No se copia nada de red, ni chats de otros, ni reenviar a nadie. Aquí solo hay uno.
 *
 * ## Todo lo que decide algo vive en este archivo
 *
 * Sin Android: qué entra en cada sección, qué encuentra una búsqueda, cómo se agrupa por
 * días y qué se ha caducado. Son las cuatro cosas que, mal hechas, hacen que uno no
 * encuentre lo que guardó — y las cuatro se pueden comprobar aquí mismo.
 */

/** De qué es cada mensaje. Decide su sección y cómo se pinta. */
@Serializable
enum class Clase {
    /** Solo texto: una nota, un comentario, un enlace escrito. */
    NOTA,
    IMAGEN,
    ARCHIVO,
    VOZ,

    /** Un dibujo del lienzo, con su identificador para volver a abrirlo. */
    DIBUJO,

    /** Una página concreta de un PDF de un proyecto. */
    PAGINA,

    /**
     * Un proyecto entero, **como acceso directo**.
     *
     * No se copia nada: el proyecto sigue donde estaba y esto es solo la puerta. Copiarlo
     * daría dos proyectos que se editan por separado y divergen, que es la peor forma de
     * perder trabajo — la que no se nota hasta que ya has escrito en el equivocado.
     */
    PROYECTO,

    /**
     * Una mini-aplicación: una lista de tareas, unos gastos.
     *
     * El documento entero vive en [Mensaje.texto] y de qué tipo es, en [Mensaje.miniapp].
     * No va en un archivo aparte como los dibujos porque **es texto**: así se busca con
     * el buscador de la conversación, se copia y se pega en cualquier sitio, y sobrevive
     * a que la aplicación cambie por dentro. Ver `MiniApp`.
     */
    MINIAPP
}

/**
 * **El nombre de un archivo guardado, con la extensión que le toca.**
 *
 * Quien comparte no siempre dice cómo se llama el archivo: a veces solo manda el asunto, y un
 * asunto no lleva extensión. El archivo se guardaba entonces como `Plano de la nave`, sin más,
 * y al abrirlo después Android no tenía de dónde sacar de qué era —el tipo lo deduce de la
 * extensión— así que lo ofrecía como «archivo» a secas y el PDF dejaba de abrirse con el
 * lector de PDF. Eso es lo que se ve como «pierde el formato».
 *
 * [deSuTipo] es la extensión que corresponde a su tipo, si se sabe. Si el nombre ya trae una
 * que parece extensión, se respeta: quien la puso sabía lo que hacía.
 */
fun nombreConExtension(nombre: String, deSuTipo: String?): String {
    val sano = nombre.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifBlank { "compartido" }
    val laQueTrae = sano.substringAfterLast('.', "")
    val pareceExtension = laQueTrae.isNotBlank() && laQueTrae.length in 1..5 &&
        laQueTrae.all { it.isLetterOrDigit() }
    if (pareceExtension) return sano
    val suya = deSuTipo?.trim()?.removePrefix(".").orEmpty()
    return if (suya.isBlank()) sano else "$sano.$suya"
}

/**
 * Un mensaje guardado.
 *
 * [ruta] es el archivo si lo hay; [referencia] es el identificador de lo que abre —un
 * dibujo, una hoja de proyecto—. Se guardan por separado a propósito: un dibujo **no es un
 * archivo**, es algo que se abre en el editor, y confundirlos llevaría a intentar
 * compartir un identificador o a abrir un PDF en el lienzo.
 */
@Serializable
data class Mensaje(
    val id: String,
    val cuando: Long,
    val clase: Clase,
    val texto: String = "",
    val ruta: String? = null,
    val nombre: String = "",
    val bytes: Long = 0,
    val referencia: String? = null,
    /** Página dentro de un PDF, cuando [clase] es [Clase.PAGINA]. */
    val pagina: Int? = null,
    /** Duración del audio en milisegundos, cuando es una nota de voz. */
    val duracionMs: Int = 0,
    /**
     * Los picos del micrófono de una nota de voz, para dibujar su onda. Ver [aBarras].
     *
     * Se capturan **al grabar** y se guardan aquí. Vacía por defecto: las notas grabadas
     * antes de que esto existiera —y cualquier línea del JSONL escrita sin este campo— se
     * siguen leyendo igual, sencillamente sin onda.
     */
    val picos: List<Int> = emptyList(),

    /**
     * Qué mini-aplicación es, cuando [clase] es [Clase.MINIAPP].
     *
     * Se guarda la palabra y no el número de un enum: un número cambia de significado en
     * cuanto alguien reordena la lista, y lo guardado no se puede reordenar.
     */
    val miniapp: String? = null,

    /**
     * De qué conversación es: el proyecto al que pertenece, o **nada** para la general.
     *
     * Un proyecto no es solo un montón de hojas: mientras se anota un plano de doce
     * páginas uno va dejando notas, fotos de referencia y cosas por hacer, y todo eso
     * acababa mezclado con la lista de la compra en la conversación general. Con esto,
     * cada proyecto tiene la suya y lo suyo se queda con lo suyo.
     *
     * Nulo —y no una cadena vacía— para la general, porque es lo que ya tenían escrito
     * todos los mensajes guardados antes de que esto existiera: sin tocar nada, siguen
     * siendo de la conversación general, que es donde estaban.
     */
    val proyecto: String? = null,

    /**
     * La etiqueta que se le ha puesto: un emoji, o nada.
     *
     * Es la idea de las etiquetas de Telegram en Mensajes guardados, y funciona por un
     * motivo muy concreto: **una carpeta obliga a decidir dónde va cada cosa antes de
     * guardarla**, y guardar tiene que costar un toque. Un emoji se pone después, cuando
     * ya se sabe de qué iba aquello, y sirve para encontrarlo sin recordar ni una palabra
     * de lo que ponía.
     *
     * Uno por mensaje y no varios: con varios hay que decidir cuáles, y decidir es
     * exactamente el trabajo que esto viene a quitar.
     */
    val emoji: String? = null,

    /**
     * Si de una foto anotada se enseña **solo la foto** o el dibujo entero.
     *
     * Dibujando fuera del borde de la foto, encuadrar el dibujo entero la encoge y la
     * rodea de blanco: parece otra foto. Por eso lo normal es recortar a la foto, como se
     * ve en el editor. Pero a veces el trazo de fuera **es** lo que importa —una flecha
     * que señala desde el margen, una nota al lado— y entonces hay que poder verlo todo.
     * Va por mensaje y no en los ajustes: es una decisión de esa foto, no de la app.
     */
    val soloLaFoto: Boolean = true,
    /** Fijado arriba del todo. Lo que uno consulta cada día. */
    val fijado: Boolean = false,
    /** A qué mensaje contesta, si es un comentario sobre otro. */
    val respondeA: String? = null,
    /**
     * Está en el buzón, esperando a que uno decida.
     *
     * Lo que llega solo —una captura, algo compartido desde otra aplicación— entra aquí en
     * vez de mezclarse con lo que uno guardó a mano. Ver [caducados].
     */
    val enBuzon: Boolean = false
)

/** Las secciones de la cabecera, en su orden. */
enum class Seccion { TODO, FOTOS, ARCHIVOS, VOZ, DIBUJOS, FIJADOS, BUZON }

/**
 * Qué clases entran en cada sección.
 *
 * Las páginas de PDF cuentan como archivos: quien busca «aquel PDF» no distingue entre el
 * documento entero y una página suya, y separarlos daría dos sitios donde mirar.
 */
fun clasesDe(seccion: Seccion): Set<Clase> = when (seccion) {
    Seccion.TODO -> Clase.entries.toSet()
    Seccion.FOTOS -> setOf(Clase.IMAGEN)
    // Las mini-apps van con los archivos: son documentos, no conversación, y es donde
    // uno las busca cuando quiere «esa lista de la compra» y no recuerda cuándo la hizo.
    Seccion.ARCHIVOS -> setOf(Clase.ARCHIVO, Clase.PAGINA, Clase.PROYECTO, Clase.MINIAPP)
    Seccion.VOZ -> setOf(Clase.VOZ)
    Seccion.DIBUJOS -> setOf(Clase.DIBUJO)
    Seccion.FIJADOS -> Clase.entries.toSet()
    Seccion.BUZON -> Clase.entries.toSet()
}

/**
 * Los mensajes de una sección, **en orden de conversación**: el más viejo arriba.
 *
 * Al revés que una bandeja de correo, y a propósito: una conversación se lee hacia abajo y
 * lo último queda a mano, que es donde el dedo ya está. En los fijados manda lo reciente,
 * porque ahí no hay conversación que seguir, hay una lista de cosas importantes.
 */
/**
 * Los de una conversación: la general o la de un proyecto.
 *
 * Se filtra antes que nada, porque **todo lo demás cuelga de aquí**: las secciones, la
 * búsqueda, el buzón y los fijados son de una conversación concreta. Buscar «factura» en
 * el proyecto de la reforma no puede sacar la factura del viaje.
 */
fun delChat(mensajes: List<Mensaje>, proyecto: String?): List<Mensaje> =
    mensajes.filter { it.proyecto == proyecto }

fun deSeccion(mensajes: List<Mensaje>, seccion: Seccion): List<Mensaje> {
    // El buzón tampoco es conversación: lo más nuevo arriba. Lo de abajo es lo que está a
    // punto de irse solo, y ahí es donde hay que mirar antes de que se vaya.
    if (seccion == Seccion.BUZON) {
        // **También hacia abajo, como el resto.**
        //
        // Estaba al revés —lo más nuevo arriba, como una bandeja de correo— y era la
        // única sección que se leía en otra dirección. En una pantalla con forma de
        // conversación eso desorienta: se abre por el final, así que lo más nuevo tiene
        // que estar donde el dedo ya está. Lo que está a punto de irse se ve subiendo,
        // que es hacia donde uno mira cuando busca lo viejo.
        return mensajes.filter { it.enBuzon }.sortedBy { it.cuando }
    }
    val clases = clasesDe(seccion)
    // **Lo del buzón no se mezcla con lo demás, y esto faltaba.**
    //
    // Sin este filtro, cualquier cosa que entrara al buzón salía en TODO, en FOTOS y en
    // ARCHIVOS como si uno la hubiera guardado — y a los siete días **desaparecía sola**
    // de una lista donde uno creía haberla puesto a salvo. Un cajón que borra lo que
    // guardas es peor que no tener cajón.
    val filtrados = mensajes.filter {
        !it.enBuzon && it.clase in clases && (seccion != Seccion.FIJADOS || it.fijado)
    }
    return if (seccion == Seccion.FIJADOS) filtrados.sortedByDescending { it.cuando }
    else filtrados.sortedBy { it.cuando }
}

/**
 * Lo que encuentra una búsqueda.
 *
 * Busca en el texto **y en el nombre del archivo**, sin distinguir mayúsculas ni acentos.
 * Lo de los acentos importa más de lo que parece: uno escribe «practica» con prisa y lo
 * que guardó se llamaba «Práctica 3». Una búsqueda que no encuentra eso se abandona a la
 * segunda vez.
 */
fun buscar(mensajes: List<Mensaje>, consulta: String): List<Mensaje> {
    val q = sinAcentos(consulta.trim())
    if (q.isEmpty()) return mensajes
    return mensajes.filter {
        sinAcentos(it.texto).contains(q) || sinAcentos(it.nombre).contains(q)
    }
}

/** Minúsculas y sin tildes, para comparar como compara la cabeza y no la tabla ASCII. */
fun sinAcentos(texto: String): String {
    val bajo = texto.lowercase()
    val sb = StringBuilder(bajo.length)
    for (c in bajo) {
        sb.append(
            when (c) {
                'á', 'à', 'ä', 'â' -> 'a'
                'é', 'è', 'ë', 'ê' -> 'e'
                'í', 'ì', 'ï', 'î' -> 'i'
                'ó', 'ò', 'ö', 'ô' -> 'o'
                'ú', 'ù', 'ü', 'û' -> 'u'
                'ñ' -> 'n'
                else -> c
            }
        )
    }
    return sb.toString()
}

/**
 * Los mensajes partidos en días, con el día por delante.
 *
 * Es el separador de fecha de cualquier mensajería, y no es decoración: sin él, veinte
 * cosas guardadas son veinte filas sin asidero, y con él uno recuerda «esto fue el martes»
 * y llega de un vistazo. El día se pasa como el número de días desde la época, que es lo
 * único que se puede comparar sin depender de la zona horaria de quien lo lea.
 */
fun porDias(mensajes: List<Mensaje>, diaDe: (Long) -> Long): List<Tramo> {
    val salida = mutableListOf<Tramo>()
    var dia: Long? = null
    val actual = mutableListOf<Mensaje>()
    for (m in mensajes) {
        val suyo = diaDe(m.cuando)
        if (dia == null || suyo != dia) {
            if (dia != null) salida.add(Tramo(dia, actual.toList()))
            dia = suyo
            actual.clear()
        }
        actual.add(m)
    }
    if (dia != null && actual.isNotEmpty()) salida.add(Tramo(dia, actual.toList()))
    return salida
}

/** Un día con sus mensajes. */
data class Tramo(val dia: Long, val mensajes: List<Mensaje>)

/**
 * Los del buzón que ya han caducado.
 *
 * El buzón es lo que entra solo, y su gracia es que **se limpia sin que nadie lo limpie**:
 * lo que uno no rescata en una semana es lo que no le importaba. Sin caducidad, el buzón
 * es otra carpeta llena que da pereza mirar, que es exactamente lo que se quería evitar.
 *
 * Lo fijado y lo que se rescató no caducan nunca: eso ya lo decidió alguien.
 */
fun caducados(mensajes: List<Mensaje>, ahora: Long, diasQueDura: Int = DIAS_DEL_BUZON): List<Mensaje> {
    val limite = ahora - diasQueDura * 24L * 60 * 60 * 1000
    return mensajes.filter { it.enBuzon && !it.fijado && it.cuando < limite }
}

/**
 * Los emojis que se están usando, **los más usados primero**.
 *
 * Ese orden es el que hace que la fila de etiquetas sirva: las dos o tres con las que uno
 * marca de verdad quedan siempre a mano, y las que se pusieron una vez no empujan a las
 * demás fuera de la pantalla. Es el mismo criterio con el que Telegram ordena sus
 * etiquetas guardadas.
 */
fun emojisUsados(mensajes: List<Mensaje>): List<String> =
    mensajes.mapNotNull { it.emoji }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

/** Los que llevan esa etiqueta. Sin etiqueta elegida, todos. */
fun porEmoji(mensajes: List<Mensaje>, emoji: String?): List<Mensaje> =
    if (emoji == null) mensajes else mensajes.filter { it.emoji == emoji }

/**
 * Una copia de [m] para otra conversación.
 *
 * **Copia y no mudanza**: reenviar algo no puede quitarlo de donde estaba, que es lo que
 * uno menos espera de un botón que dice «reenviar». Lleva identificador nuevo y hora
 * nueva porque en la conversación de destino es un mensaje que llega ahora.
 *
 * Lo que **no** se lleva: lo fijado, el buzón y a quién contestaba. Fijado es de la
 * conversación donde se fijó; el buzón es una cuenta atrás que ya corrió; y la respuesta
 * apunta a un mensaje que en el destino no existe, así que sería una cita rota.
 */
fun reenviado(m: Mensaje, aDondeVa: String?, ahora: Long, idNuevo: String): Mensaje = m.copy(
    id = idNuevo,
    cuando = ahora,
    proyecto = aDondeVa,
    fijado = false,
    enBuzon = false,
    respondeA = null
)

/** Cuánto aguanta algo en el buzón antes de irse solo. */
const val DIAS_DEL_BUZON = 7

/**
 * El tamaño escrito corto: `4 kB`, `2,3 MB`.
 *
 * En kilobytes de mil, no de mil veinticuatro: es lo que dice cualquier sistema operativo
 * de hoy y lo que espera quien mira, aunque a un informático le duela.
 */
fun tamanoLegible(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "${bytes / 1_000} kB"
    bytes < 1_000_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    else -> String.format("%.1f GB", bytes / 1_000_000_000.0)
}

/**
 * Lo que entra solo al buzón, a partir de un pin recién puesto.
 *
 * ## La regla
 *
 * **Todo lo que se pinea o se edita y no vive ya en un proyecto pasa por aquí**, y se borra
 * solo si nadie lo toca. Es la bandeja de entrada de siempre: capturar ahora, decidir
 * después. Lo que tiene un sitio —una hoja de un proyecto— no entra, porque ya está
 * guardado en algún lado y tenerlo dos veces obliga a preguntarse cuál manda.
 *
 * ## Lo que no entra, y por qué
 *
 * Un color, un contador, un temporizador o una ruleta vacía **no tienen contenido**: son
 * trastos que se vuelven a fabricar en dos segundos y que nadie echaría de menos. Llenar
 * con ellos la bandeja es la forma más rápida de que uno deje de mirarla, y una bandeja que
 * no se mira no salva nada.
 *
 * Devuelve null cuando no hay nada que guardar.
 */
fun delPin(
    id: String,
    cuando: Long,
    clase: Clase?,
    texto: String?,
    ruta: String?,
    nombre: String,
    referencia: String? = null
): Mensaje? {
    if (clase == null) return null
    val hayTexto = !texto.isNullOrBlank()
    val hayArchivo = !ruta.isNullOrBlank() || referencia != null
    if (!hayTexto && !hayArchivo) return null
    return Mensaje(
        id = id,
        cuando = cuando,
        clase = clase,
        texto = texto.orEmpty(),
        ruta = ruta,
        nombre = nombre,
        referencia = referencia,
        enBuzon = true
    )
}

/**
 * Rescatar: sacar del buzón y dejarlo guardado para siempre.
 *
 * Se le pone la fecha de **cuando se rescata**, no la de cuando entró. Es lo que hace que
 * aparezca abajo en la conversación, junto a lo demás que uno ha guardado hoy — que es
 * donde se busca lo que acaba de decidir salvar. Con la fecha vieja se hundiría entre lo
 * de la semana pasada nada más rescatarlo.
 */
fun rescatado(m: Mensaje, ahora: Long): Mensaje = m.copy(enBuzon = false, cuando = ahora)

/**
 * Lo que ya hay en el buzón para esta referencia, si es que hay algo.
 *
 * Editar un dibujo diez veces no puede dejar diez entradas del mismo dibujo. Se busca por
 * lo que lo identifica y se actualiza en vez de repetirlo.
 */
fun yaEnBuzon(mensajes: List<Mensaje>, referencia: String?): Mensaje? {
    if (referencia == null) return null
    return mensajes.firstOrNull { it.enBuzon && it.referencia == referencia }
}
