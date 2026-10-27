package com.forge.pixpin.guardados

/**
 * **Vídeos y enlaces, con su pestaña** (25-oct-2026, como el PC: `es_video` y `tiene_enlace` de
 * `crates/pixpin-proyecto/src/cuaderno.rs`). La ficha del chat al estilo de Telegram: «todo,
 * fotos, vídeos, archivos, enlaces…». Un vídeo deja de contar en Fotos y en Archivos.
 *
 * Sin Android: se prueba en `SeccionesDelChatTest`.
 */
object SeccionesDelChat {
    /** Las de la familia «vídeo» de los colores por extensión del PC (`color_de_extension`). */
    val EXTENSIONES_DE_VIDEO = setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "m4v", "wmv", "flv")

    /**
     * Si un mensaje es un vídeo: una foto o un archivo con extensión de vídeo. Por su nombre, y si
     * no lo tiene (lo de antes), por el de su ruta.
     */
    fun esVideo(m: Mensaje): Boolean {
        if (m.clase != Clase.IMAGEN && m.clase != Clase.ARCHIVO) return false
        val nombre = m.nombre.ifBlank { m.ruta?.substringAfterLast('/').orEmpty() }
        val ext = nombre.substringAfterLast('.', "")
        return ext.isNotEmpty() && ext.lowercase() in EXTENSIONES_DE_VIDEO
    }

    private val ABRE = charArrayOf('(', '[', '<', '«', '"', '\'')
    private val CIERRA = charArrayOf('.', ',', ';', ':', ')', ']', '>', '»', '"')

    /**
     * Si el texto lleva una dirección web: una palabra que empieza por `http://`, `https://` o
     * `www.` con un punto en el servidor. Lo justo para la sección «Enlaces».
     */
    fun tieneEnlace(texto: String): Boolean =
        texto.split(Regex("\\s+")).any { palabra ->
            val p = palabra.trimStart(*ABRE).lowercase()
            val resto = listOf("https://", "http://", "www.").firstOrNull { p.startsWith(it) }?.let { p.removePrefix(it) }
                ?: return@any false
            val servidor = resto.split('/', '?', '#').first().trimEnd(*CIERRA)
            '.' in servidor && !servidor.startsWith('.')
        }

    // ---- La ficha del chat (25-oct-2026, PC `8c92ba4`, `99f918a` y `2439c5c`). Ver [FichaDelChat]. ----

    /**
     * **Las pestañas de la ficha, en su orden**: las de los mensajes. «Todo» no va: en el teléfono
     * la ficha tapa el chat entero, y «todo» es el propio chat, a un toque de cerrarla. El buzón
     * tampoco: tiene su ficha en la cabecera y su aviso de que caduca.
     *
     * **Sin «Proyectos» delante** (27-oct-2026, pedido del usuario): la lista de chats que había
     * aquí repetía la de la pantalla principal. Las carpetas de páginas siguen en «Archivos».
     */
    val DE_LA_FICHA = listOf(
        Seccion.FOTOS, Seccion.VIDEOS, Seccion.ARCHIVOS,
        Seccion.ENLACES, Seccion.VOZ, Seccion.DIBUJOS, Seccion.FIJADOS
    )

    /**
     * **Dónde se abre la ficha** (27-oct-2026): en «Fotos», como Telegram, o, si no hay ninguna, en
     * la primera pestaña que tenga algo; con el chat vacío, en «Fotos», que dice que no hay. Así no
     * se abre en una pestaña vacía teniendo archivos o enlaces al lado. [cuentas]: lo de cada una.
     */
    fun dondeSeAbre(cuentas: Map<Seccion, Int>): Seccion =
        DE_LA_FICHA.firstOrNull { (cuentas[it] ?: 0) > 0 } ?: Seccion.FOTOS

    /** Las que se ven como cuadrícula de miniaturas; las demás, como filas. */
    fun esCuadricula(s: Seccion) = s == Seccion.FOTOS || s == Seccion.VIDEOS || s == Seccion.DIBUJOS

    /**
     * **Lo que enseña la ficha** (`pixpin_ui::info::Cosa` del PC): un mensaje entero o, si es de
     * una nota rica, **qué foto suya** (en el orden de [fotosDeLaNota]). Va por el id del mensaje y
     * no por su posición: una sincronización que mete mensajes en medio, o un borrado, dejaba la
     * previa abierta enseñando otra cosa (el PC lo arregló igual). Ver [reubicar].
     */
    data class Cosa(val id: String, val foto: Int? = null)

    /**
     * Las fotos de una nota rica, en el orden en que se ven en su burbuja (`fotos_de_la_nota`). Lo
     * que no es una foto (un PDF, un audio) no cuenta. Sin `![` no se parte el texto: la ficha
     * pregunta por cada nota.
     */
    fun fotosDeLaNota(texto: String): List<NotaRica.Imagen> {
        if ("![" !in texto) return emptyList()
        return NotaRica.trozos(texto.trim()).flatMap { (it as? NotaRica.Trozo.Album)?.fotos.orEmpty() }
    }

    /**
     * **Lo que sale en «Fotos»** (`con_las_fotos_de_las_notas` del PC): los mensajes de foto
     * ([fotos]) y, de cada nota ([notas]), cada una de sus fotos, todo en el orden del chat. Las dos
     * listas son posiciones en los mensajes, de menor a mayor; [cuantasFotos] dice cuántas lleva la
     * nota de esa posición. Una nota sin fotos no sale. Devuelve (posición, foto de la nota o null).
     */
    fun conLasFotosDeLasNotas(fotos: List<Int>, notas: List<Int>, cuantasFotos: (Int) -> Int): List<Pair<Int, Int?>> {
        val salida = ArrayList<Pair<Int, Int?>>(fotos.size)
        var f = 0
        var n = 0
        while (f < fotos.size || n < notas.size) {
            if (f < fotos.size && (n >= notas.size || fotos[f] <= notas[n])) {
                salida += fotos[f] to null
                f++
            } else {
                val b = notas[n]
                for (k in 0 until cuantasFotos(b)) salida += b to k
                n++
            }
        }
        return salida
    }

    /**
     * **Las cosas de una pestaña de la ficha**, en el orden del chat (el más viejo primero; la
     * ficha las enseña por meses con lo nuevo arriba, ver [porMeses]). En «Fotos» entran las fotos
     * de las notas ricas, cada una como la suya; en las demás, los mensajes de [deSeccion].
     */
    fun cosasDe(mensajes: List<Mensaje>, seccion: Seccion): List<Cosa> {
        if (seccion != Seccion.FOTOS) return deSeccion(mensajes, seccion).map { Cosa(it.id) }
        val enOrden = mensajes.filter { !it.enBuzon }.sortedBy { it.cuando }
        val fotos = deSeccion(mensajes, Seccion.FOTOS).mapTo(HashSet()) { it.id }
        val posFotos = enOrden.indices.filter { enOrden[it].id in fotos }
        val posNotas = enOrden.indices.filter { enOrden[it].clase == Clase.NOTA && "![" in enOrden[it].texto }
        return conLasFotosDeLasNotas(posFotos, posNotas) { fotosDeLaNota(enOrden[it].texto).size }
            .map { (i, k) -> Cosa(enOrden[i].id, k) }
    }

    /**
     * **Dónde está ahora la cosa abierta en la previa** (`reubicar_la_abierta` del PC), en [cosas];
     * null si ya no está (se borró, o la sincronización se la llevó): entonces se vuelve a la
     * rejilla, nunca a otra cosa que no se eligió.
     */
    fun reubicar(cosas: List<Cosa>, abierta: Cosa?): Int? =
        abierta?.let { a -> cosas.indexOf(a).takeIf { it >= 0 } }

    /** Un mes de la ficha: «Octubre de 2026» y lo suyo. [mes] de 1 a 12. */
    data class Mes<T>(val anio: Int, val mes: Int, val cosas: List<T>)

    /**
     * **El contenido va por meses, lo más nuevo arriba** (como el panel de medios de Telegram y el
     * PC desde `99f918a`): cada mes con lo suyo, y dentro también lo nuevo primero. [cuando] dice
     * la fecha de cada cosa, en la zona [zona].
     */
    fun <T> porMeses(cosas: List<T>, zona: java.util.TimeZone = java.util.TimeZone.getDefault(), cuando: (T) -> Long): List<Mes<T>> {
        val cal = java.util.Calendar.getInstance(zona)
        val salida = ArrayList<Mes<T>>()
        var actual: Pair<Int, Int>? = null
        var lista = ArrayList<T>()
        for (c in cosas.sortedByDescending(cuando)) {
            cal.timeInMillis = cuando(c)
            val clave = cal.get(java.util.Calendar.YEAR) to cal.get(java.util.Calendar.MONTH) + 1
            if (clave != actual) {
                actual?.let { salida += Mes(it.first, it.second, lista) }
                actual = clave
                lista = ArrayList()
            }
            lista += c
        }
        actual?.let { salida += Mes(it.first, it.second, lista) }
        return salida
    }
}
