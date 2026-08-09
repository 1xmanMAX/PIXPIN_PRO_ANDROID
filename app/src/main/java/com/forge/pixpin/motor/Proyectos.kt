package com.forge.pixpin.motor

import kotlinx.serialization.Serializable

/**
 * El proyecto: **varias hojas que se entregan juntas**.
 *
 * Hasta ahora cada dibujo vivía solo. Se podían poner varios marcos en un mismo
 * lienzo y salían varias páginas, pero eso obliga a tenerlo todo en el mismo
 * tablero, y una planta, un alzado y un detalle no se dibujan encima unos de
 * otros. Y las páginas de un PDF anotadas una a una no tenían dónde juntarse.
 *
 * Un proyecto es la carpeta que faltaba. Se abre, se le van metiendo hojas
 * —dibujos nuevos, o páginas de un PDF— y al final **sale entero, en orden y de
 * una vez**. Cuando ya no hace falta, se archiva: no se borra, se quita de en
 * medio.
 *
 * ## De dónde sale cada hoja
 *
 * Dos orígenes, y el proyecto no los mezcla por capricho:
 *
 * - Una **hoja en blanco**: un lienzo del editor de siempre. Al exportar, sale
 *   como una página nueva.
 * - Una **página de un PDF**: se abre la hoja de ese PDF, se dibuja encima y lo
 *   dibujado vuelve **dentro del PDF**, como capa. Ver [PdfAnotado].
 *
 * ## La regla que decide qué sale al exportar
 *
 * Es la que evita que esto se convierta en dos maneras de hacer lo mismo:
 *
 * | El proyecto tiene | Lo que sale |
 * |---|---|
 * | Solo hojas en blanco | Un PDF nuevo, una página por hoja |
 * | Un PDF de origen | **Ese mismo PDF**, con sus capas — su texto sigue vivo |
 * | Las dos cosas | Ese PDF, y las hojas en blanco añadidas detrás |
 *
 * Lo importante es la segunda fila. Un proyecto nacido de un PDF **no** se
 * reconstruye página a página al exportar: eso convertiría el texto del
 * original en dibujo y se perdería lo que más costó conseguir. El PDF de origen
 * es el resultado, no una fuente que se copia.
 */
@Serializable
data class Proyecto(
    val id: String,
    val nombre: String,
    /** Las hojas, **en el orden en que saldrán**. */
    val hojas: List<Hoja> = emptyList(),
    /**
     * Archivado: fuera de la lista de lo que se está haciendo, pero entero.
     *
     * No es borrar. Un proyecto terminado se consulta meses después —«¿cómo
     * quedó aquello?»— y borrarlo por haberlo entregado es perderlo.
     */
    val archivado: Boolean = false,
    /** Cuándo se tocó por última vez. Es lo que ordena la lista. */
    val tocado: Long = 0L,
    /**
     * El PDF sobre el que se trabaja, si el proyecto nació de uno.
     *
     * Es **la ruta del resultado**, no la del archivo que trajo el usuario: se
     * trabaja sobre una copia. El original que tenga en Descargas no se toca
     * nunca, y eso no es una precaución de más — es la diferencia entre
     * equivocarse y estropearle un documento a alguien.
     */
    val pdfOrigen: String? = null
)

/** Una hoja del proyecto. */
@Serializable
data class Hoja(
    val id: String,
    val nombre: String = "",
    /** El dibujo, por su identificador en el almacén de escenas. */
    val dibujo: String,
    /**
     * Qué página del PDF es, contando desde cero, o null si es hoja en blanco.
     *
     * Es lo que hoy no se guarda en ninguna parte: al sacar una página de un
     * PDF se creaba un pin de imagen suelto y **se perdía de dónde venía**, así
     * que lo dibujado no tenía camino de vuelta. Con esto lo tiene.
     */
    val pagina: Int? = null
)

/**
 * Lo que se puede hacer con los proyectos, sin tocar el disco ni Android.
 *
 * Todo devuelve listas nuevas en vez de modificar: así el repositorio solo tiene
 * que guardar lo que le den, y esto se comprueba entero sin dispositivo.
 */
object Proyectos {

    /** Tope de hojas por proyecto. Un PDF de doscientas páginas no es un proyecto. */
    const val MAX_HOJAS = 200

    /**
     * El nombre que se propone para el siguiente, sin repetir.
     *
     * Pedir un nombre antes de dejarte empezar es una pregunta en el peor
     * momento: todavía no sabes qué va a ser esto. Se le pone uno y se renombra
     * cuando apetezca, que es cuando ya se sabe.
     */
    fun nombreLibre(existentes: List<Proyecto>, plantilla: String): String {
        val usados = existentes.map { it.nombre.trim().lowercase() }.toSet()
        var n = existentes.size + 1
        while (true) {
            val propuesto = "$plantilla $n"
            if (propuesto.lowercase() !in usados) return propuesto
            n++
        }
    }

    /**
     * El orden de la lista: **lo que está en marcha primero, y lo reciente
     * arriba**.
     *
     * Lo archivado va al fondo, no a otra pantalla: son pocos y esconderlos
     * detrás de un filtro obliga a acordarse de que existe el filtro.
     */
    fun ordenados(proyectos: List<Proyecto>): List<Proyecto> =
        proyectos.sortedWith(
            compareBy<Proyecto> { it.archivado }
                .thenByDescending { it.tocado }
                .thenBy { it.nombre.lowercase() }
        )

    fun conHoja(proyecto: Proyecto, hoja: Hoja, cuando: Long): Proyecto {
        // Una página del PDF no se mete dos veces: se vuelve a abrir la que hay.
        // Sin esto, anotar la página 3 el lunes y el martes daría dos hojas 3 y
        // la de después taparía a la de antes al exportar.
        val yaEsta = proyecto.hojas.any { it.pagina != null && it.pagina == hoja.pagina }
        if (yaEsta || proyecto.hojas.size >= MAX_HOJAS) return proyecto
        return proyecto.copy(hojas = proyecto.hojas + hoja, tocado = cuando)
    }

    fun sinHoja(proyecto: Proyecto, hojaId: String, cuando: Long): Proyecto =
        proyecto.copy(hojas = proyecto.hojas.filter { it.id != hojaId }, tocado = cuando)

    /**
     * Cambia una hoja de sitio.
     *
     * El orden de las hojas es el de las páginas al exportar, así que tiene que
     * poder cambiarse: en un proyecto de obra la portada se hace la última.
     */
    fun movida(proyecto: Proyecto, hojaId: String, a: Int, cuando: Long): Proyecto {
        val desde = proyecto.hojas.indexOfFirst { it.id == hojaId }
        if (desde < 0) return proyecto
        val destino = a.coerceIn(0, proyecto.hojas.size - 1)
        if (destino == desde) return proyecto
        val lista = proyecto.hojas.toMutableList()
        lista.add(destino, lista.removeAt(desde))
        return proyecto.copy(hojas = lista, tocado = cuando)
    }

    fun renombrado(proyecto: Proyecto, nombre: String, cuando: Long): Proyecto {
        val limpio = nombre.trim()
        if (limpio.isEmpty()) return proyecto
        return proyecto.copy(nombre = limpio, tocado = cuando)
    }

    fun archivado(proyecto: Proyecto, archivar: Boolean, cuando: Long): Proyecto =
        proyecto.copy(archivado = archivar, tocado = cuando)

    /** Sustituye un proyecto por su versión nueva, o lo añade si no estaba. */
    fun actualizada(proyectos: List<Proyecto>, nuevo: Proyecto): List<Proyecto> =
        if (proyectos.none { it.id == nuevo.id }) proyectos + nuevo
        else proyectos.map { if (it.id == nuevo.id) nuevo else it }

    /**
     * En qué proyecto se está trabajando ahora mismo.
     *
     * El más reciente de los que no están archivados. Es lo que permite que una
     * hoja nueva vaya a alguna parte **sin preguntar a cuál**: si acabas de
     * anotar la página 3 de un plano, la 7 va con ella.
     */
    fun enCurso(proyectos: List<Proyecto>): Proyecto? =
        proyectos.filter { !it.archivado }.maxByOrNull { it.tocado }

    /**
     * El proyecto de un PDF concreto, si ya se empezó.
     *
     * Reabrir el mismo PDF tiene que llevar al mismo proyecto: si cada vez
     * empezara uno nuevo, las páginas anotadas ayer no estarían.
     */
    fun deEstePdf(proyectos: List<Proyecto>, pdf: String): Proyecto? =
        proyectos.firstOrNull { it.pdfOrigen == pdf }

    /** Qué sale al exportar. Ver la tabla de [Proyecto]. */
    fun salidaDe(proyecto: Proyecto): SalidaDeProyecto {
        val enBlanco = proyecto.hojas.filter { it.pagina == null }
        return when {
            proyecto.pdfOrigen == null -> SalidaDeProyecto.PdfNuevo(enBlanco)
            enBlanco.isEmpty() -> SalidaDeProyecto.ElPdfDeOrigen
            else -> SalidaDeProyecto.ElPdfConHojasDetras(enBlanco)
        }
    }
}

/** Qué hay que hacer para entregar un proyecto. */
sealed interface SalidaDeProyecto {
    /** No hay PDF de origen: se escribe uno, una página por hoja. */
    data class PdfNuevo(val hojas: List<Hoja>) : SalidaDeProyecto

    /**
     * El PDF de origen **es** el resultado: sus capas ya están dentro.
     *
     * No hay nada que montar, que es la consecuencia buena de escribir cada
     * anotación en cuanto se cierra la página.
     */
    data object ElPdfDeOrigen : SalidaDeProyecto

    /** El PDF de origen, y las hojas en blanco añadidas al final. */
    data class ElPdfConHojasDetras(val hojas: List<Hoja>) : SalidaDeProyecto
}
