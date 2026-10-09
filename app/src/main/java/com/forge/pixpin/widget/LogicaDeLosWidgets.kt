package com.forge.pixpin.widget

import com.forge.pixpin.capture.CaducidadDeCapturas
import com.forge.pixpin.capture.GaleriaLogica
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.mini.TodasLasTareas
import java.time.LocalDate
import java.util.TimeZone

/**
 * **Lo que deciden los widgets de la pantalla de inicio, sin Android** (8-oct-2026). El usuario:
 * «widgets en los que pueda ver mis tareas, marcar y agregar de forma rápida ahí mismo; y uno que
 * muestre la galería».
 *
 * Los widgets ([WidgetDeTareas], [WidgetDeGaleria]) solo pintan lo que sale de aquí: qué tareas
 * salen y en qué orden, el texto de cada fila, cómo se marca una, qué capturas salen y qué pone su
 * chapita. Aparte para poder probarlo en JUnit (`LogicaDeLosWidgetsTest`), como el resto del proyecto.
 *
 * **No hay una segunda copia de nada**: las tareas son las de [TodasLasTareas] (las mismas listas,
 * el mismo orden que la pantalla de Tareas) y la caducidad, la de [CaducidadDeCapturas] y
 * [GaleriaLogica]. Lo que cambia es la forma: un widget es estrecho y no se puede escribir en él.
 */
object LogicaDeLosWidgets {

    // ---- Tareas ----------------------------------------------------------

    /**
     * Cuántas filas como mucho. Un widget de colecciones aguanta muchas, pero cada una es un
     * `RemoteViews` que cruza al lanzador: con cientos de tareas viejas el lanzador tardaría en
     * desplazarse, y nadie repasa doscientas tareas en la pantalla de inicio.
     */
    const val MAX_TAREAS = 200

    /** Lo que se ve de una tarea que solo tiene imágenes: en el widget no se pintan. */
    const val SOLO_IMAGEN = "Tarea con imagen"

    /** Una fila del widget de tareas. */
    data class FilaDeTarea(
        /** El `id` del mensaje de la lista: a quién se marca. */
        val codigo: String,
        /** Su número en el documento. */
        val indice: Int,
        /** El texto guardado tal cual: antes de marcar se comprueba que sigue siendo este. */
        val crudo: String,
        /** Lo que se lee: sin la fecha ni las imágenes, en una línea. */
        val texto: String,
        /** La línea pequeña de debajo: la lista (y su chat) y cuánto lleva. */
        val detalle: String
    ) {
        /** Lo que la distingue para el lanzador (`getItemId`): el mensaje, el sitio y el texto. */
        val clave: String get() = "$codigo/$indice/$crudo"
    }

    /**
     * Las tareas pendientes de **todas** las listas, en el orden de la pantalla de Tareas: las
     * listas como las ordena [TodasLasTareas.ORDEN_DE_LISTAS] (el Inbox primero) y, dentro de cada
     * una, lo pendiente como [TodasLasTareas.ORDEN_DE_PENDIENTE] (lo que más lleva esperando
     * arriba). Lo hecho no sale: el widget es para lo que queda por hacer.
     */
    fun filasDeTareas(listas: List<TodasLasTareas.Lista>, hoy: LocalDate, max: Int = MAX_TAREAS): List<FilaDeTarea> {
        val salida = ArrayList<FilaDeTarea>()
        for (l in listas.sortedWith(TodasLasTareas.ORDEN_DE_LISTAS)) {
            val pendientes = l.filas.filter { !it.hecha }.sortedWith(TodasLasTareas.ORDEN_DE_PENDIENTE)
            for (f in pendientes) {
                if (salida.size >= max) return salida
                salida += FilaDeTarea(l.codigo, f.indice, f.crudo, textoDeFila(f.texto), detalleDeFila(l, f.creada, hoy, com.forge.pixpin.mini.Tareas.horaDe(f.crudo)))
            }
        }
        return salida
    }

    /** Todo lo pendiente, aunque no quepa en [MAX_TAREAS]: es el número de la cabecera. */
    fun pendientes(listas: List<TodasLasTareas.Lista>): Int = listas.sumOf { it.pendientes }

    private val BLANCOS = Regex("""\s+""")

    /** El texto de la fila, en una línea; una tarea que solo era una foto dice que lo es. */
    fun textoDeFila(texto: String): String = texto.replace(BLANCOS, " ").trim().ifEmpty { SOLO_IMAGEN }

    /**
     * «Inbox», «Pendientes · Obra Miraflores», con «· hace 3 días» detrás si lleva fecha. El chat
     * general no se nombra: casi todo lo de «Mensajes guardados» es el Inbox, y repetirlo en cada
     * fila solo quita sitio.
     */
    fun detalleDeFila(l: TodasLasTareas.Lista, creada: LocalDate?, hoy: LocalDate, hora: java.time.LocalDateTime? = null): String {
        val donde = if (l.guardados || l.chat == l.titulo) l.titulo.ifBlank { l.chat } else "${l.titulo.ifBlank { "Tareas" }} · ${l.chat}"
        val edad = TodasLasTareas.edad(creada, hoy)
        val sinHora = if (edad == null) donde else "$donde · $edad"
        // Con hora de recordar (`⏰`), delante: es lo que más importa de la fila.
        return if (hora == null) sinHora else "${com.forge.pixpin.mini.Tareas.RELOJ} ${com.forge.pixpin.mini.Tareas.textoDeHora(hora, hoy.atTime(java.time.LocalTime.now()))} · $sinHora"
    }

    /** El número de la cabecera: vacío si no queda nada (la lista ya lo dice), y «99+» si no cabe. */
    fun cuenta(n: Int): String = when {
        n <= 0 -> ""
        n > 99 -> "99+"
        else -> n.toString()
    }

    /** Lo que dice el widget sin filas. */
    fun vacioDeTareas(hayListas: Boolean): String =
        if (hayListas) "Nada pendiente. Toca «+» para apuntar." else "Aún no hay tareas. Toca «+» para apuntar una."

    /**
     * Las [mensajes] con la tarea [indice] de la lista [codigo] marcada como hecha, por el mismo
     * camino que la pantalla de Tareas ([TodasLasTareas.marcar]); es lo que se escribe en
     * `guardados.jsonl` y viaja al sincronizar. null si no hay nada que escribir: la lista ya no
     * está, ya no es una lista de tareas, o la tarea de ese número ya no es [crudo] (la lista
     * cambió en el chat o llegó cambiada del PC desde que se pintó el widget). Marcar dos veces es
     * no tocar nada.
     */
    fun marcarEn(mensajes: List<Mensaje>, codigo: String, indice: Int, crudo: String): List<Mensaje>? {
        val m = mensajes.firstOrNull { it.id == codigo }?.takeIf { TodasLasTareas.esLista(it) } ?: return null
        val fila = TodasLasTareas.Fila(indice, crudo, "", false, null, emptyList())
        val nuevo = TodasLasTareas.marcar(m.texto, fila, hecha = true) ?: return null
        if (nuevo == m.texto) return null
        return mensajes.map { if (it.id == codigo) it.copy(texto = nuevo) else it }
    }

    // ---- Galería ---------------------------------------------------------

    /** Cuántas miniaturas como mucho: las más recientes. Cada una es un mapa de bits que cruza al lanzador. */
    const val MAX_CAPTURAS = 12

    /** Lado, en píxeles, de cada miniatura: cuadrada y pequeña, para no pasar el límite de los `RemoteViews`. */
    const val LADO_MINIATURA = 256

    /** Una casilla de la rejilla de la galería. */
    data class Casilla(
        /** Su sitio en la lista que se pasó. */
        val indice: Int,
        /** El texto corto de la chapita («hoy», «mañana», «5 d»); null en las conservadas, que no se van. */
        val chapita: String?,
        /** El color de la chapita; null si no lleva. */
        val tono: GaleriaLogica.Tono?,
        /** Lo que se lee en voz alta: «Se borra en 2 días», «Conservada». */
        val descripcion: String
    )

    /**
     * Las capturas que salen en el widget, de [lista] (nombre y fecha en ms UTC, ya de la más nueva
     * a la más vieja, como las da `BarrenderoDeCapturas.listar`). **Las ya caducadas no salen**: el
     * barrendero se las lleva a la papelera en cuanto pasa, y enseñarlas sería prometer una foto
     * que al tocarla ya no está.
     */
    fun casillas(
        lista: List<Pair<String, Long>>,
        registro: CaducidadDeCapturas.Registro,
        dias: Int,
        ahora: Long,
        max: Int = MAX_CAPTURAS,
        zona: TimeZone = TimeZone.getDefault()
    ): List<Casilla> {
        val salida = ArrayList<Casilla>()
        for ((i, c) in lista.withIndex()) {
            if (salida.size >= max) break
            val seVa = CaducidadDeCapturas.seVaEl(registro, c.first, c.second, dias)
            if (seVa != null && seVa <= ahora) continue
            val p = GaleriaLogica.plazo(seVa, ahora, zona)
            val conservada = p == GaleriaLogica.Plazo.Conservada
            salida += Casilla(
                i,
                if (conservada) null else chapita(seVa!!, ahora, zona),
                if (conservada) null else GaleriaLogica.tono(p),
                GaleriaLogica.textoDelPlazo(p, zona)
            )
        }
        return salida
    }

    /** Cuántas capturas hay de verdad (sin las caducadas que aún no barrió nadie): el número de la cabecera. */
    fun vivas(lista: List<Pair<String, Long>>, registro: CaducidadDeCapturas.Registro, dias: Int, ahora: Long): Int =
        lista.count { (n, c) -> CaducidadDeCapturas.seVaEl(registro, n, c, dias)?.let { it > ahora } ?: true }

    /**
     * El texto de la chapita: «hoy», «mañana» o los días que quedan («5 d»). Corto a propósito:
     * va encima de una miniatura de un dedo de ancho, y «Se borra el 14 oct» no cabría.
     */
    fun chapita(seVa: Long, ahora: Long, zona: TimeZone = TimeZone.getDefault()): String {
        val d = GaleriaLogica.diasQueFaltan(seVa, ahora, zona)
        return when {
            d <= 0 -> "hoy"
            d == 1L -> "mañana"
            else -> "$d d"
        }
    }

    /** Lo que dice el widget sin capturas. [fallo]: Android no dejó preguntar (no es lo mismo que no haber ninguna). */
    fun vacioDeGaleria(fallo: Boolean): String =
        if (fallo) "No se pudieron leer las capturas. Toca para abrir la galería."
        else "Aún no hay capturas. Toca para abrir la galería."

    /**
     * El recorte cuadrado del centro de una imagen de [ancho] × [alto]: (x, y, lado). Las
     * miniaturas del widget son cuadradas, y recortarlas aquí deja el mapa de bits en
     * [LADO_MINIATURA]² en vez de mandar la foto alargada para que la recorte el lanzador.
     */
    fun recorteCuadrado(ancho: Int, alto: Int): Triple<Int, Int, Int> {
        if (ancho <= 0 || alto <= 0) return Triple(0, 0, 0)
        val lado = minOf(ancho, alto)
        return Triple((ancho - lado) / 2, (alto - lado) / 2, lado)
    }
}
