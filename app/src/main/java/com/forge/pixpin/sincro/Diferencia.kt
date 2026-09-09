package com.forge.pixpin.sincro

/**
 * **Qué hay que hacer para que dos aparatos queden iguales.**
 *
 * Esto es el corazón de sincronizar, y está **aquí y sin Android a propósito**: todas las
 * decisiones difíciles —quién gana cuando los dos tocaron lo mismo, qué pasa si uno lo borró—
 * viven en una función pura que se puede probar entera en la JVM, sin dos teléfonos, sin red y
 * sin esperar a nada. Lo que va por el cable es lo fácil; lo que se decide aquí es lo que se
 * puede hacer mal y no notarse hasta que alguien pierde un plano.
 *
 * Ver `docs/plan-sincronizacion.md`.
 */
object Diferencia {

    /**
     * Lo que un aparato sabe de un mensaje suyo. Es lo que se manda al empezar: una línea por
     * mensaje, no el mensaje. Un proyecto entero son unos kilobytes.
     */
    data class Apunte(
        /** Ver [Sena]. No cambia al viajar. */
        val sena: String,
        /** Cuándo se creó. **Es lo que ordena el chat**, aquí y en el otro aparato. */
        val creado: Long,
        /** Cuándo se tocó por última vez. */
        val tocado: Long,
        /**
         * **Un resumen del contenido.** Sin esto no se puede saber si algo cambió de verdad:
         * dos relojes nunca coinciden, y comparando solo horas se preguntaría por cosas que
         * están idénticas. Con el resumen, sincronizar dos veces seguidas no manda nada.
         */
        val resumen: String,
        /**
         * **Borrado deja rastro.** Si al borrar no quedara nada, el otro aparato vería un
         * mensaje que él tiene y yo no, me lo mandaría, y **lo resucitaría** en cada
         * sincronización. La marca viaja como cualquier otro apunte.
         */
        val borrado: Boolean = false
    )

    /** Por qué hay que preguntarle al usuario. */
    enum class Choque {
        /** Los dos lo tocaron desde la última vez. Es el caso que el usuario quiso que se preguntara. */
        LOS_DOS_CAMBIARON,
        /** Uno lo borró y el otro lo tiene intacto. Por omisión gana el borrado. */
        BORRADO_CONTRA_INTACTO,
        /** Uno lo borró y el otro lo cambió. Aquí no hay respuesta obvia. */
        BORRADO_CONTRA_CAMBIADO
    }

    /** Un paso del plan de sincronización. */
    sealed class Paso {
        abstract val sena: String
        /** Me lo traigo del otro. */
        data class Traer(override val sena: String) : Paso()
        /** Se lo mando al otro. */
        data class Mandar(override val sena: String) : Paso()
        /** Lo borro aquí, porque allí se borró. */
        data class Borrar(override val sena: String) : Paso()
        /** Hay que preguntar cuál se queda. */
        data class Preguntar(override val sena: String, val porque: Choque) : Paso()
    }

    /**
     * El plan para ponerse al día con el otro aparato.
     *
     * [mio] y [suyo] son los inventarios de cada uno. [base] es **lo que se acordó la última
     * vez** con este aparato: la seña y el resumen que tenían cuando terminó aquella
     * sincronización.
     *
     * **La base es lo que evita preguntar de más.** Sin ella, dos contenidos distintos son
     * siempre un empate y habría que preguntar cada vez, aunque solo uno lo hubiera tocado —que
     * es el caso corriente—. Con ella se sabe **quién se movió**: el que difiere de la base. Es
     * la diferencia entre una sincronización que no molesta y una que pregunta a cada rato, y
     * es justo lo que el usuario pidió: preguntar solo cuando los dos tocaron lo mismo.
     *
     * Los pasos salen ordenados por seña para que dos aparatos calculen la misma lista en el
     * mismo orden, que hace mucho más fácil entender un problema cuando lo haya.
     */
    fun plan(
        mio: List<Apunte>,
        suyo: List<Apunte>,
        base: Map<String, String> = emptyMap()
    ): List<Paso> {
        val aqui = mio.associateBy { it.sena }
        val alli = suyo.associateBy { it.sena }
        val pasos = ArrayList<Paso>()
        for (sena in (aqui.keys + alli.keys).sorted()) {
            val a = aqui[sena]
            val b = alli[sena]
            when {
                // Solo él lo tiene. Si lo suyo es una marca de borrado, no hay nada que
                // traerse: lo que él borró yo ni siquiera lo llegué a tener.
                a == null -> if (b != null && !b.borrado) pasos += Paso.Traer(sena)
                // Solo yo lo tengo: se lo mando, borrado incluido —la marca también viaja, o
                // él me lo devolvería en la siguiente vuelta.
                b == null -> pasos += Paso.Mandar(sena)
                // Los dos lo tenemos.
                a.borrado && b.borrado -> {}                       // de acuerdo en que no está
                a.resumen == b.resumen && !a.borrado && !b.borrado -> {}  // idénticos
                else -> pasos += queHacerConLosDos(sena, a, b, base[sena])
            }
        }
        return pasos
    }

    private fun queHacerConLosDos(sena: String, a: Apunte, b: Apunte, base: String?): Paso {
        // **Borrar es una decisión, no un descuido**, así que nunca se deshace en silencio: se
        // pregunta siempre que uno lo haya borrado y el otro no. Lo que cambia es qué se le
        // cuenta al usuario, porque no es lo mismo haber borrado algo que el otro no tocó que
        // haberlo borrado mientras el otro lo estaba mejorando.
        if (a.borrado != b.borrado) {
            val vivo = if (a.borrado) b else a
            val cambiado = base != null && vivo.resumen != base
            return Paso.Preguntar(
                sena,
                if (cambiado) Choque.BORRADO_CONTRA_CAMBIADO else Choque.BORRADO_CONTRA_INTACTO
            )
        }
        // Sin base no se sabe quién se movió: es la primera vez que estos dos se ven con esta
        // seña en la mano, y dos contenidos distintos son un empate de verdad.
        if (base == null) return Paso.Preguntar(sena, Choque.LOS_DOS_CAMBIARON)
        val yoMeMovi = a.resumen != base
        val elSeMovio = b.resumen != base
        return when {
            !yoMeMovi && elSeMovio -> Paso.Traer(sena)   // cambió solo él: gana él
            yoMeMovi && !elSeMovio -> Paso.Mandar(sena)  // cambié solo yo: gano yo
            else -> Paso.Preguntar(sena, Choque.LOS_DOS_CAMBIARON)
        }
    }

    /**
     * **Dónde va cada mensaje que llega: por su hora de creación.**
     *
     * Es lo que pidió el usuario, y es lo único que funciona sin ponerse de acuerdo: si el
     * orden dependiera de cuándo llegó, cada aparato tendría el chat en un orden distinto y
     * «mira el 47» dejaría de servir para señalar el mismo sitio. Con la hora de creación,
     * cualquier aparato que reciba los mismos mensajes acaba con **la misma lista**.
     *
     * A igualdad de hora ordena la seña, que es única: así ni siquiera dos mensajes creados en
     * el mismo milisegundo en dos aparatos distintos dejan el orden al azar.
     */
    fun ordenar(apuntes: List<Apunte>): List<Apunte> =
        apuntes.sortedWith(compareBy({ it.creado }, { it.sena }))
}
