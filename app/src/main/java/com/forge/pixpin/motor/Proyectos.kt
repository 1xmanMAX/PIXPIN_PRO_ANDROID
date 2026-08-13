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
 * - Un **marco de un lienzo**: dibujas donde quieras y guardas lo que has
 *   encuadrado. En un lienzo infinito uno tiene la planta a un lado, un detalle
 *   al otro y cuatro pruebas en medio; lo que entrega es lo que ha encuadrado.
 * - Una **página de un PDF**: se abre la hoja de ese PDF, se dibuja encima y lo
 *   dibujado vuelve **dentro del PDF**, como capa. Ver [PdfAnotado].
 *
 * ## La hoja del PDF va de fondo, y el fondo no se exporta
 *
 * Al abrir una página se ve la hoja **debajo, como referencia**: no se puede
 * mover ni borrar, porque no es un elemento del dibujo — es el papel. Lo que se
 * traza va encima, en su capa, y **solo eso** vuelve al PDF.
 *
 * No es un detalle de presentación, es lo que evita el peor fallo posible aquí:
 * si la hoja entrara en el dibujo como un elemento más, al escribir la capa se
 * estamparía **una foto de la página encima de la propia página**. El resultado
 * pesaría diez veces más, se vería casi igual… y el texto de debajo habría
 * quedado tapado por una imagen, o sea que dejaría de poder buscarse y
 * seleccionarse. Justo lo que todo esto existe para conservar.
 *
 * Por eso el fondo va como **telón** ([Scene] no lo contiene) y no como
 * elemento: así no es que no se exporte, es que **no puede** exportarse.
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
    val pdfOrigen: String? = null,

    /**
     * Una copia **intacta** del PDF, guardada aparte.
     *
     * Es la base desde la que se rehace el documento cada vez, y su existencia
     * cambia el modelo entero.
     *
     * Escribiendo cada anotación encima de la anterior, el archivo va
     * acumulando capas: retocar la misma página tres veces deja tres capas con
     * tres versiones del mismo trazo, una encima de otra, y no hay forma de
     * quitar la de en medio. Y al segundo guardado ya no se sabe qué había
     * antes de empezar.
     *
     * Con una copia limpia el problema desaparece: **lo que manda es el
     * dibujo**, no lo que se escribió la última vez. Cada guardado parte del
     * original y le pone encima las hojas tal como están ahora, así que borrar
     * un trazo lo borra de verdad y retocar veinte veces sigue dejando una capa
     * por página.
     */
    val pdfLimpio: String? = null
)

/**
 * Una hoja del proyecto.
 *
 * **Es una referencia, no una copia.** El proyecto es un índice de dónde está
 * cada hoja, así que seguir dibujando en el lienzo actualiza lo que saldrá
 * impreso sin tener que volver a guardarla. Copiando, el proyecto se quedaría
 * con la versión del día que la metiste y nadie se enteraría hasta ver el PDF.
 */
@Serializable
data class Hoja(
    val id: String,
    val nombre: String = "",
    /**
     * El dibujo, por su identificador en el almacén de escenas.
     *
     * Puede no haberlo: una página de un PDF recién importada es una hoja del
     * proyecto **antes** de que nadie dibuje nada en ella.
     */
    val dibujo: String? = null,
    /**
     * Qué marco de ese dibujo.
     *
     * Es la unidad que se guarda: **un marco, no el lienzo entero**. En un
     * lienzo infinito uno tiene la planta a un lado, un detalle al otro y
     * cuatro pruebas en medio; lo que quiere entregar es lo que ha encuadrado,
     * no todo lo que hay dibujado. A null, la hoja es el lienzo completo.
     */
    val marco: String? = null,
    /**
     * El texto de una nota, si esta hoja es una nota y no un dibujo.
     *
     * Va el **texto entero**, no una referencia al pin. Una hoja de un proyecto
     * es lo que se va a entregar, como una página impresa: si fuera una
     * referencia, seguir escribiendo en la nota cambiaría lo ya montado sin que
     * nadie lo pidiera, y una hoja añadida hace un mes podría desaparecer al
     * borrar su pin. Ocupa poco y el proyecto se vuelve autosuficiente.
     */
    val nota: String? = null,
    /**
     * Qué página del PDF es, contando desde cero, o null si no viene de uno.
     *
     * Es lo que hoy no se guarda en ninguna parte: al sacar una página de un
     * PDF se creaba un pin de imagen suelto y **se perdía de dónde venía**, así
     * que lo dibujado no tenía camino de vuelta. Con esto lo tiene.
     */
    val pagina: Int? = null
) {
    /**
     * Dos hojas son la misma si señalan al mismo sitio.
     *
     * Una nota señala **a sí misma**, por su identificador, y no a un sitio
     * compartido: dos notas distintas son dos hojas, aunque las dos sean texto y
     * ninguna tenga dibujo ni página. Sin esto, la segunda nota que se añadía a
     * un proyecto se tomaba por repetida de la primera y no entraba.
     */
    val señal: String
        get() = when {
            nota != null -> "nota:$id"
            pagina != null -> "pagina:$pagina"
            marco != null -> "marco:$dibujo/$marco"
            else -> "dibujo:$dibujo"
        }
}

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
        // **Lo mismo no entra dos veces.** Guardar otra vez el mismo marco, o
        // volver a la misma página del PDF, lleva a la hoja que ya hay. Sin
        // esto, guardar el marco cada vez que se retoca daría diez hojas
        // iguales, y anotar la página 3 dos días daría dos hojas 3 de las que
        // una taparía a la otra al exportar, sin que nadie se enterara.
        val yaEsta = proyecto.hojas.any { it.señal == hoja.señal }
        if (yaEsta || proyecto.hojas.size >= MAX_HOJAS) return proyecto
        return proyecto.copy(hojas = proyecto.hojas + hoja, tocado = cuando)
    }

    /** La hoja que ya señala a lo mismo, si la hay. */
    fun hojaComo(proyecto: Proyecto, hoja: Hoja): Hoja? =
        proyecto.hojas.firstOrNull { it.señal == hoja.señal }

    /** La hoja de una página concreta del PDF. */
    fun hojaDePagina(proyecto: Proyecto, pagina: Int): Hoja? =
        proyecto.hojas.firstOrNull { it.pagina == pagina }

    /**
     * Un proyecto con **todas las páginas del PDF dentro**, de una vez.
     *
     * Al pinear un plano de doce hojas uno no quiere doce decisiones: quiere
     * las doce ahí, poder abrir la que le interese y dibujar. Las hojas nacen
     * sin dibujo —todavía no hay nada anotado— y lo reciben cuando se abren.
     *
     * No se renderiza nada aquí: son doce entradas en una lista. Las miniaturas
     * se dibujan cuando se miran, que es lo que evita que abrir un PDF de
     * doscientas páginas se lleve la memoria por delante.
     */
    fun dePdf(
        id: String,
        nombre: String,
        pdf: String,
        paginas: Int,
        cuando: Long,
        idDeHoja: (Int) -> String
    ): Proyecto = Proyecto(
        id = id,
        nombre = nombre,
        pdfOrigen = pdf,
        tocado = cuando,
        hojas = (0 until paginas.coerceIn(0, MAX_HOJAS)).map {
            Hoja(id = idDeHoja(it), pagina = it)
        }
    )

    /**
     * Le pone dibujo a una hoja que aún no lo tenía.
     *
     * Es lo que pasa al abrir una página del PDF y trazar la primera raya: la
     * hoja existía desde que se importó el documento, y ahora tiene encima algo
     * que enseñar.
     */
    fun conDibujo(proyecto: Proyecto, hojaId: String, dibujo: String, cuando: Long): Proyecto {
        if (proyecto.hojas.none { it.id == hojaId }) return proyecto
        return proyecto.copy(
            hojas = proyecto.hojas.map { if (it.id == hojaId) it.copy(dibujo = dibujo) else it },
            tocado = cuando
        )
    }

    /**
     * Cambia el texto de una hoja de nota.
     *
     * Hace falta porque una hoja de proyecto guarda **el texto entero** y no una
     * referencia al pin (ver [Hoja.nota]): sin esto, editar la nota desde el
     * proyecto escribía en el almacén de textos del editor y el proyecto seguía
     * enseñando —y entregando— lo de antes.
     *
     * Solo toca hojas que ya sean notas: convertir un dibujo en nota por
     * escribirle encima no es lo que nadie pediría.
     */
    fun conNota(proyecto: Proyecto, hojaId: String, texto: String, cuando: Long): Proyecto {
        if (proyecto.hojas.none { it.id == hojaId && it.nota != null }) return proyecto
        return proyecto.copy(
            hojas = proyecto.hojas.map {
                if (it.id == hojaId && it.nota != null) it.copy(nota = texto) else it
            },
            tocado = cuando
        )
    }

    /** Las hojas que ya tienen algo dibujado encima. */
    fun anotadas(proyecto: Proyecto): List<Hoja> = proyecto.hojas.filter { it.dibujo != null }

    /** Las hojas que son notas escritas. */
    fun notas(proyecto: Proyecto): List<Hoja> = proyecto.hojas.filter { it.nota != null }

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
