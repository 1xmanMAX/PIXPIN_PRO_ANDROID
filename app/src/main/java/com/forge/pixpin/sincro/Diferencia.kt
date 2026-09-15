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
    @kotlinx.serialization.Serializable
    data class Apunte(
        /**
         * **Por qué se reconoce en los dos aparatos.** En un mensaje, su código único (ver
         * [Codigos]); en un archivo, su ruta. Una marca de borrado de antes de los códigos, que
         * solo sabe la seña, lleva aquí `sena:<seña>` y se reconoce por [alias].
         */
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
        val borrado: Boolean = false,
        /** El código de chat (`47·K7Q2`, o `47a` en lo de antes): con él se reconocen las marcas viejas. */
        val alias: String? = null
    )

    /** Un paso del plan de sincronización. */
    sealed class Paso {
        abstract val sena: String
        /** Me lo traigo del otro (o su borrado, si allí se borró). */
        data class Traer(override val sena: String) : Paso()
        /** Se lo mando al otro (o mi borrado). */
        data class Mandar(override val sena: String) : Paso()
        /**
         * **Cambió en los dos: se juntan** (15-sep-2026). Ya no se pregunta ni manda un aparato: se
         * suma lo de cada lado figura por figura, celda por celda, párrafo por párrafo. Ver [Fusion].
         */
        data class Fusionar(override val sena: String) : Paso()
    }

    /**
     * El plan para ponerse al día con el otro aparato.
     *
     * [mio] y [suyo] son los inventarios de cada uno. [base] es **lo que se acordó la última
     * vez** con este aparato: la clave y el resumen que tenían cuando terminó aquella
     * sincronización. Con ella se sabe **quién se movió**: el que difiere de la base.
     *
     * **Nadie manda** (lo pidió el usuario el 15-sep-2026, tras perder lienzos con un aparato de
     * maestro). Las reglas, las de la fusión ([Fusion]):
     *
     * - Cambió en un solo lado: pasa al otro.
     * - Cambió en los dos (o nunca se acordó nada y son distintos): **se fusiona**.
     * - Borrado en un lado y **sin tocar** en el otro: se borra en los dos.
     * - Borrado en un lado y **cambiado** en el otro (o sin base con que saberlo): **se queda lo
     *   cambiado**, en los dos. Borrar quita lo que uno vio; lo que no vio, sobrevive.
     *
     * Los pasos salen ordenados por clave para que dos aparatos calculen la misma lista en el
     * mismo orden, que hace mucho más fácil entender un problema cuando lo haya.
     */
    fun plan(
        mio: List<Apunte>,
        suyo: List<Apunte>,
        base: Map<String, String> = emptyMap()
    ): List<Paso> {
        val aqui = conMarcasViejas(mio, suyo).associateBy { it.sena }
        val alli = conMarcasViejas(suyo, mio).associateBy { it.sena }
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
                a.borrado && b.borrado -> {}                       // de acuerdo en que no está
                a.resumen == b.resumen && !a.borrado && !b.borrado -> {}  // idénticos
                // Lo acordado antes de los códigos va por la seña: se busca también por ella.
                else -> pasos += queHacerConLosDos(sena, a, b, base[sena] ?: a.alias?.let(base::get) ?: b.alias?.let(base::get))
            }
        }
        return pasos
    }

    private fun queHacerConLosDos(sena: String, a: Apunte, b: Apunte, base: String?): Paso {
        if (a.borrado != b.borrado) {
            val vivoEsMio = b.borrado
            val vivo = if (vivoEsMio) a else b
            // **Lo modificado gana a lo borrado.** Sin base no se sabe si se tocó: se conserva.
            val tocado = base == null || vivo.resumen != base
            return when {
                tocado -> if (vivoEsMio) Paso.Mandar(sena) else Paso.Traer(sena)
                // Intacto contra borrado: el borrado pasa al lado que aún lo tenía.
                else -> if (vivoEsMio) Paso.Traer(sena) else Paso.Mandar(sena)
            }
        }
        if (base == null) return Paso.Fusionar(sena)
        val yoMeMovi = a.resumen != base
        val elSeMovio = b.resumen != base
        return when {
            !yoMeMovi && elSeMovio -> Paso.Traer(sena)
            yoMeMovi && !elSeMovio -> Paso.Mandar(sena)
            else -> Paso.Fusionar(sena)
        }
    }

    /**
     * **Las marcas de borrado de antes de los códigos** solo sabían la seña (`47a`). Si el otro
     * aparato tiene vivo un mensaje con esa seña, la marca pasa a llevar su código único, y así se
     * sigue reconociendo como el borrado de ese mensaje.
     */
    fun conMarcasViejas(lista: List<Apunte>, delOtro: List<Apunte>): List<Apunte> {
        if (lista.none { it.borrado && it.sena.startsWith(MARCA_VIEJA) }) return lista
        val porAlias = (delOtro + lista).filter { !it.borrado && it.alias != null }.associate { it.alias!! to it.sena }
        val vivos = lista.filter { !it.borrado }.mapTo(HashSet()) { it.sena }
        return lista.mapNotNull { ap ->
            if (!ap.borrado || !ap.sena.startsWith(MARCA_VIEJA)) return@mapNotNull ap
            val clave = porAlias[ap.sena.removePrefix(MARCA_VIEJA)] ?: return@mapNotNull ap
            if (clave in vivos) null else ap.copy(sena = clave)
        }
    }

    const val MARCA_VIEJA = "sena:"

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
