package com.forge.pixpin.motor

/**
 * **Qué herramientas salen en cada sitio, y la lista general** (5-oct-2026, porte del PC
 * `pixpin-store/src/herramientas.rs`, commit 55db60d).
 *
 * Android ya tenía una barra por sitio —el lienzo, el pin, la capa sobre la pantalla y el editor
 * rápido de los lectores—, cada una con lo suyo. Lo que trae el PC es **encima** una lista general:
 * una herramienta apagada ahí no sale en ningún sitio, sin tener que quitarla de cuatro barras. El
 * usuario lo pidió así: «elegir cuántas herramientas mostrar en diferentes situaciones… para que no
 * se muestren siempre todas y saturen».
 *
 * Las reglas son las del PC, tal cual:
 *
 * - **Apagada en todos manda**: no sale en ningún sitio aunque su barra la tenga.
 * - Apagar en un sitio no la quita de los demás ni de la general.
 * - **Encender en un sitio una apagada en todos la deja solo ahí**: sale de la lista general y se
 *   apaga en los demás sitios, que así siguen como estaban.
 * - Lo que la barra de un sitio tenía de las apagadas en todos **se conserva** al reordenarla: al
 *   volver a encenderla en general, vuelve a salir donde estaba.
 *
 * Sin Android: se prueba en `HerramientasPorSitioTest`.
 */
object HerramientasPorSitio {

    /**
     * Los sitios con barra propia, en el orden del PC (`SITIOS`: pantalla, pin, lienzo, lector).
     * [nombre] es el del PC; [porDefecto] lo que sale ahí mientras nadie lo toque.
     */
    enum class Sitio(val nombre: String, val rotulo: String) {
        PANTALLA("pantalla", "Pantalla"),
        PIN("pin", "Pin"),
        LIENZO("lienzo", "Lienzo"),
        LECTOR("lector", "Lector");

        val porDefecto: Set<Tool>
            get() = when (this) {
                PANTALLA -> CAPA_TOOLS_POR_DEFECTO
                PIN -> PIN_TOOLS_POR_DEFECTO
                LIENZO -> ALL_TOOLS.toSet()
                LECTOR -> LECTOR_TOOLS_POR_DEFECTO
            }
    }

    /**
     * Lo que hay: las apagadas en todos y lo que lleva cada sitio, **ya resuelto** (un sitio sin
     * tocar vale lo de fábrica). Lo que salga de aquí se guarda entero.
     */
    data class Reparto(
        val apagadas: Set<Tool>,
        val porSitio: Map<Sitio, Set<Tool>>
    ) {
        fun activa(t: Tool): Boolean = t !in apagadas

        /** Si sale en [s]: ni apagada en todos ni fuera de su barra. */
        fun activaEn(s: Sitio, t: Tool): Boolean = activa(t) && t in enSitio(s)

        fun enSitio(s: Sitio): Set<Tool> = porSitio[s] ?: s.porDefecto

        /** Lo que de verdad sale en [s]. */
        fun visibles(s: Sitio): Set<Tool> = enSitio(s) - apagadas

        /** Enciende o apaga en todos los sitios a la vez. */
        fun alternarEnTodos(t: Tool): Reparto =
            copy(apagadas = if (t in apagadas) apagadas - t else apagadas + t)

        /** Enciende o apaga solo en [s], con la regla de «la deja solo ahí». */
        fun alternarEn(s: Sitio, t: Tool): Reparto =
            if (activaEn(s, t)) conSitio(s, enSitio(s) - t) else encenderSoloAqui(s, t)

        /**
         * Una barra reordenada en [s]: [nuevas] es lo que el usuario deja a la vista. Lo apagado en
         * todos que esa barra tenía se queda guardado; lo apagado en todos que acaba de meter, se
         * enciende solo ahí.
         */
        fun conBarra(s: Sitio, nuevas: Set<Tool>): Reparto {
            var r = conSitio(s, nuevas + (enSitio(s) intersect apagadas))
            for (t in nuevas intersect apagadas) r = r.encenderSoloAqui(s, t)
            return r
        }

        private fun encenderSoloAqui(s: Sitio, t: Tool): Reparto {
            var r = this
            if (!r.activa(t)) {
                r = r.copy(apagadas = r.apagadas - t)
                for (otro in Sitio.entries) if (otro != s) r = r.conSitio(otro, r.enSitio(otro) - t)
            }
            return r.conSitio(s, r.enSitio(s) + t)
        }

        private fun conSitio(s: Sitio, tools: Set<Tool>): Reparto = copy(porSitio = porSitio + (s to tools))
    }

    /** Los nombres guardados, a herramientas. Los que ya no existen se ignoran en silencio. */
    fun leer(nombres: Set<String>?): Set<Tool> =
        nombres.orEmpty().mapNotNullTo(LinkedHashSet()) { n -> runCatching { Tool.valueOf(n.trim()) }.getOrNull() }

    fun escribir(tools: Set<Tool>): Set<String> = tools.mapTo(LinkedHashSet()) { it.name }
}
