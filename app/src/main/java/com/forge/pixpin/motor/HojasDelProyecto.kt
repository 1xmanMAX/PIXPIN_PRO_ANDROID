package com.forge.pixpin.motor

/**
 * Lo que enseña un proyecto: sus hojas, ya desplegadas.
 *
 * ## Por qué hace falta desplegarlas
 *
 * En el proyecto una hoja puede ser **un lienzo entero**, y un lienzo con cuatro
 * marcos son cuatro láminas, no una. Quien mira el proyecto quiere ver las
 * cuatro —son las páginas que va a entregar—, así que aquí se abre cada lienzo
 * en sus marcos.
 *
 * Se **deriva**, no se guarda. Si al añadir el lienzo se hubieran copiado sus
 * marcos como hojas fijas, dibujar un marco más luego no aparecería, y borrar
 * uno dejaría una hoja fantasma señalando a nada. Preguntando cada vez, lo que
 * se ve es siempre lo que hay.
 */
object HojasDelProyecto {

    /**
     * Una página del proyecto, ya concreta: o una del PDF, o un marco de un
     * lienzo, o un lienzo sin marcos, o una nota.
     */
    data class Pagina(
        /** La hoja del proyecto de la que sale. */
        val hoja: Hoja,
        /** El marco, si sale de uno. */
        val marco: String? = null,
        /** Su nombre, para la etiqueta de debajo. */
        val nombre: String = ""
    ) {
        /**
         * Con qué se distingue de las demás **y se recuerda si está marcada**.
         *
         * Lleva el marco dentro a propósito: dos páginas del mismo lienzo son la
         * misma hoja del proyecto, y sin el marco marcar una marcaría las dos.
         */
        val clave: String get() = "${hoja.id}/${marco.orEmpty()}"
    }

    /**
     * Las páginas de un proyecto, en el orden en que saldrán.
     *
     * [escenaDe] devuelve el dibujo de un lienzo, o null si no se puede leer.
     * Entra como función para que esto no sepa nada de discos ni de contextos y
     * se pueda comprobar entero en la JVM.
     */
    fun paginas(proyecto: Proyecto, escenaDe: (String) -> Scene?): List<Pagina> =
        proyecto.hojas.flatMap { hoja ->
            when {
                hoja.nota != null -> listOf(
                    Pagina(hoja, nombre = hoja.nombre.ifBlank { "Nota" })
                )

                hoja.pagina != null -> listOf(
                    Pagina(hoja, nombre = "${hoja.pagina!! + 1}")
                )

                hoja.dibujo != null -> {
                    val escena = escenaDe(hoja.dibujo!!)
                    val marcos = escena?.marcos.orEmpty()
                    if (marcos.isEmpty()) {
                        // Un lienzo sin marcos es una lámina y ya: lo que haya
                        // dibujado, entero. Es lo que hace la exportación.
                        listOf(Pagina(hoja, nombre = hoja.nombre.ifBlank { "Lienzo" }))
                    } else {
                        marcos.mapIndexed { i, marco ->
                            Pagina(
                                hoja = hoja,
                                marco = marco.id,
                                nombre = marco.text?.takeIf { it.isNotBlank() }
                                    ?: "${i + 1}"
                            )
                        }
                    }
                }

                else -> emptyList()
            }
        }

    /**
     * De qué color se enmarca cada lienzo.
     *
     * **Un color por lienzo**, para que se vea de un vistazo qué láminas vienen
     * del mismo sitio cuando un proyecto junta varios. Sale del identificador
     * del dibujo, así que es el mismo siempre y en todas partes: no depende del
     * orden en que se añadieron ni de cuántos haya.
     *
     * Las páginas de un PDF y las notas no llevan color: no vienen de un lienzo,
     * y pintarlas de uno diría algo que no es.
     */
    fun colorDe(hoja: Hoja): Int? {
        val dibujo = hoja.dibujo ?: return null
        if (hoja.pagina != null) return null
        return COLORES[Math.floorMod(dibujo.hashCode(), COLORES.size)]
    }

    /**
     * Los colores del marco.
     *
     * Distinguibles entre sí y con suficiente contraste sobre fondo claro y
     * oscuro: es un borde de dos puntos, no un relleno, así que tienen que
     * verse sin depender del tema.
     */
    private val COLORES = intArrayOf(
        0xFF4C7DF0.toInt(), // azul
        0xFF2FA84F.toInt(), // verde
        0xFFE0803A.toInt(), // naranja
        0xFF9B59D0.toInt(), // morado
        0xFFD64B6A.toInt(), // rojo
        0xFF12A5A5.toInt() // turquesa
    )
}
