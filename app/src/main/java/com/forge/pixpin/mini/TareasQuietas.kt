package com.forge.pixpin.mini

/**
 * **Lo que no se mueve bajo el dedo en Tareas** (2-nov-2026, `pindesk_ui::tareas_quietas` del PC,
 * commits `d998fe5c` y `8ce19277`). El usuario: «cuando toco la casilla de una tarea y voy a tocar la
 * siguiente, la anterior se va y acabo pulsando otra. Que al pulsar se quede en su sitio, marcada; y
 * cuando salga de Tareas y vuelva, que ya no estén: archivadas en «Hechas» como siempre».
 *
 * **La regla.** Marcar se guarda al momento (lo que viaja al PC no espera a nada); lo que se queda
 * quieto es solo la VISTA:
 *
 * - lo recién tachado se queda arriba, en su sitio, tachado; lo recién destachado (desde «Hechas») se
 *   queda abajo, en su sitio;
 * - el orden que se ve —de las listas y de sus tareas— se congela: ni la prioridad que Jev contesta
 *   tarde ni una lista que se queda sin nada pendiente mueven lo que hay debajo del dedo;
 * - lo nuevo (apuntado aquí o llegado de otro aparato) entra en el hueco que le toca, sin descolocar lo
 *   que ya se veía ([colocar]).
 *
 * Todo se suelta ([soltar]) y se vuelve a ordenar al «salir» de Tareas: cuando la pantalla deja de verse
 * (otra app, el inicio, la pantalla apagada), al volver a pedirla, al cambiar de vista y al buscar otra
 * cosa. Antes se iba sola a los 5 s (`SE_QUEDA_MS`), como el PC de antes.
 *
 * Lo usa la pantalla en el hilo principal (no es seguro entre hilos).
 */
class TareasQuietas {

    /** Una tarea: la clave de su lista y su texto tal cual está guardado. */
    private data class Tarea(val lista: String, val texto: String)

    /** Las recién tachadas: se quedan arriba, en su sitio. */
    private val hechas = HashSet<Tarea>()
    /** Las recién destachadas desde «Hechas»: se quedan abajo. */
    private val destachadas = HashSet<Tarea>()
    /** El orden en que se vieron las listas (sus claves). */
    private var listas: List<String> = emptyList()
    /** Por lista, el orden en que se vio lo de arriba y lo plegado. */
    private val arriba = HashMap<String, List<String>>()
    private val plegadas = HashMap<String, List<String>>()
    /** Lo que se buscaba: buscar otra cosa es mirar de otra manera. */
    private var busqueda = ""

    /** Se marcó ([hecha]) o destachó la tarea [tarea] de la lista [lista]. Deshacer lo recién hecho la deja como estaba. */
    fun marcar(lista: String, tarea: String, hecha: Boolean) {
        val t = Tarea(lista, tarea)
        val (deshace, apunta) = if (hecha) destachadas to hechas else hechas to destachadas
        if (!deshace.remove(t)) apunta.add(t)
    }

    /** La tarea ya no está (se quitó o se movió a otra lista). */
    fun quitar(lista: String, tarea: String) {
        val t = Tarea(lista, tarea)
        hechas.remove(t)
        destachadas.remove(t)
    }

    /** Si una tarea hecha se queda arriba, con lo pendiente. */
    fun sigueArriba(lista: String, tarea: String): Boolean = Tarea(lista, tarea) in hechas

    /** Si una tarea pendiente se queda abajo, con lo hecho. */
    fun siguePlegada(lista: String, tarea: String): Boolean = Tarea(lista, tarea) in destachadas

    /** Cuántas se marcaron o destacharon desde la última vez que se soltó. */
    fun tocadas(): Int = hechas.size + destachadas.size

    /** Se sale de Tareas: lo tachado se va a «Hechas» y todo se vuelve a ordenar como toca. */
    fun soltar() {
        hechas.clear(); destachadas.clear(); listas = emptyList(); arriba.clear(); plegadas.clear()
    }

    /** Lo que se busca ahora. Si es otra cosa que antes, se suelta todo y devuelve `true`. */
    fun buscar(texto: String): Boolean {
        val t = texto.trim()
        if (t == busqueda) return false
        soltar()
        busqueda = t
        return true
    }

    /** Las listas que se ven, en el orden congelado; lo que sale se recuerda para la próxima vez. */
    fun <T> ordenarListas(v: MutableList<T>, clave: (T) -> String) {
        colocar(v, listas, clave)
        listas = v.map(clave)
    }

    /**
     * Las tareas de la lista [lista] que se ven: [arribaV] (lo pendiente y lo recién tachado) y
     * [plegadasV] (lo hecho). Lo recién destachado baja a [plegadasV]; los dos montones salen en el
     * orden congelado.
     */
    fun <T> ordenarTareas(lista: String, arribaV: MutableList<T>, plegadasV: MutableList<T>, clave: (T) -> String) {
        val bajan = arribaV.filter { siguePlegada(lista, clave(it)) }
        arribaV.removeAll { siguePlegada(lista, clave(it)) }
        plegadasV.addAll(bajan)
        colocar(arribaV, arriba[lista].orEmpty(), clave)
        arriba[lista] = arribaV.map(clave)
        colocar(plegadasV, plegadas[lista].orEmpty(), clave)
        plegadas[lista] = plegadasV.map(clave)
    }

    companion object {
        /**
         * **Ordena [v] sin descolocar lo ya visto.** Los sitios de [v] que ocupa algo de [visto] se
         * reparten entre eso mismo, en el orden de [visto]; lo que no estaba se queda en el sitio que le
         * dio el orden de siempre. Dos con la misma clave guardan su orden.
         */
        fun <T> colocar(v: MutableList<T>, visto: List<String>, clave: (T) -> String) {
            if (visto.isEmpty()) return
            val rango = HashMap<String, Int>()
            for (n in visto.indices.reversed()) rango[visto[n]] = n
            val rangos = v.map { rango[clave(it)] }
            val sitios = v.indices.filter { rangos[it] != null }
            val vistos = sitios.sortedWith(compareBy { rangos[it] })
            val cogidos = vistos.map { v[it] }
            for ((sitio, x) in sitios.zip(cogidos)) v[sitio] = x
        }
    }
}
