package com.forge.pixpin.guardados

/**
 * **Cambiar el nombre de un archivo del chat** (4-oct-2026): cualquiera, también los audios.
 * Cambia [Mensaje.nombre], que es lo que se enseña y lo que encuentra el buscador; el archivo en
 * disco no se toca, porque la sincronización empareja por su ruta. Sin Android: `RenombrarTest`.
 */
object Renombrar {
    private val CON_NOMBRE = setOf(
        Clase.IMAGEN, Clase.ARCHIVO, Clase.VOZ, Clase.DIBUJO, Clase.PAGINA, Clase.TABLA, Clase.CROQUIS
    )
    private val EXTENSION = Regex("\\.[A-Za-z0-9]{1,5}$")

    /** Si [m] es un archivo al que se le puede poner nombre (no una lección, ni algo del buzón). */
    fun sePuede(m: Mensaje): Boolean =
        m.clase in CON_NOMBRE && !m.enBuzon && (m.ruta != null || m.referencia != null) &&
            !com.forge.pixpin.lecciones.LeccionesStore.esLeccion(m)

    /**
     * [nuevo] limpio y **con la extensión que tenía** [viejo] si no se la puso: «informe.pdf»
     * renombrado a «Memoria» queda «Memoria.pdf», y sigue saliendo con su icono rojo y
     * abriéndose con su visor.
     */
    fun conSuExtension(viejo: String, nuevo: String): String {
        val limpio = nuevo.replace('\n', ' ').replace('/', '-').trim().take(120)
        if (limpio.isEmpty()) return ""
        val ext = EXTENSION.find(viejo)?.value ?: return limpio
        return if (limpio.endsWith(ext, ignoreCase = true)) limpio else limpio + ext
    }
}
