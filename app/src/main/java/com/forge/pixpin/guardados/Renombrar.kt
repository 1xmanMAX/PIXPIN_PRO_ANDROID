package com.forge.pixpin.guardados

/**
 * **Cambiar el nombre de un archivo del chat** (4-oct-2026): cualquiera, también los audios.
 * Cambia [Mensaje.nombre], que es lo que se enseña y lo que encuentra el buscador; el archivo en
 * disco no se toca, porque la sincronización empareja por su ruta. Sin Android: `RenombrarTest`.
 */
object Renombrar {
    /**
     * Fotos, archivos y audios, cuyo nombre es solo el del mensaje; y los lienzos, que cambian
     * también el de su hoja ([NombreDelLienzo]). Una página, una tabla o un croquis llevan el nombre
     * de su hoja del proyecto: cambiar solo el del mensaje los dejaría con dos nombres.
     */
    private val CON_NOMBRE = setOf(Clase.IMAGEN, Clase.ARCHIVO, Clase.VOZ)
    /** Con al menos una letra: «Plano v2.0» no tiene extensión «.0». */
    private val EXTENSION = Regex("\\.(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{1,5}$")

    /** Si [m] es un archivo al que se le puede poner nombre (no una lección, ni algo del buzón). */
    fun sePuede(m: Mensaje): Boolean = !m.enBuzon && !com.forge.pixpin.lecciones.LeccionesStore.esLeccion(m) && (
        (m.clase in CON_NOMBRE && m.ruta != null) || (m.clase == Clase.DIBUJO && m.referencia != null)
    )

    /** El nombre del que se toma la extensión: el puesto o, sin él, el del archivo. */
    fun nombreDeBase(m: Mensaje): String = m.nombre.ifBlank { m.ruta?.substringAfterLast('/').orEmpty() }

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
