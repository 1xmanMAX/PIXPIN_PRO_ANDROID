package com.forge.pixpin.motor

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import java.io.File

/**
 * Dónde viven las figuras que uno se guarda.
 *
 * **En un archivo aparte y no dentro de cada dibujo.** Es lo que hace que sirvan
 * para algo: una figura guardada mientras se hace un croquis tiene que estar
 * puesta al abrir el siguiente, que es cuando se acuerda uno de que la tenía. Si
 * viviera en la escena sería una copia más de lo mismo, no una biblioteca.
 *
 * Mismo trato que [ExcalidrawStore] y por lo mismo: JSON con el formato del
 * propio motor, escritura a un temporal y renombrado —el proceso muere sin
 * avisar más de lo normal— y un contador para que la interfaz se entere.
 *
 * No se comprime: son unas cuantas figuras de unos pocos elementos, y el archivo
 * se lee al abrir la lista. Comprimirlo ahorraría kilobytes y quitaría lo único
 * cómodo que tiene, que es poder abrirlo y ver qué hay dentro.
 */
object BibliotecaStore {

    /** Sube en cada escritura: la lista se vuelve a leer sola. */
    val revision: MutableIntState = mutableIntStateOf(0)

    private const val ARCHIVO = "figuras.json"

    private fun archivo(context: Context): File =
        File(File(context.filesDir, "pins/draw").apply { mkdirs() }, ARCHIVO)

    /**
     * Las figuras propias, o la lista vacía si todavía no hay ninguna.
     *
     * Un archivo ilegible se trata como si no hubiera: perder las figuras
     * guardadas es malo, pero no poder abrir el editor por culpa de ellas es
     * peor, y lo primero ya ha pasado cuando se llega aquí.
     */
    fun cargar(context: Context): List<FiguraGuardada> = runCatching {
        val f = archivo(context)
        if (!f.exists()) return emptyList()
        ExcalidrawJson.decodeFromString<List<FiguraGuardada>>(f.readText())
    }.getOrDefault(emptyList())

    /** Escribe la lista entera. Devuelve si se pudo. */
    fun guardar(context: Context, figuras: List<FiguraGuardada>): Boolean = runCatching {
        val destino = archivo(context)
        val temporal = File(destino.parentFile, "$ARCHIVO.tmp")
        temporal.writeText(ExcalidrawJson.encodeToString(figuras))
        if (destino.exists()) destino.delete()
        if (!temporal.renameTo(destino)) return false
        revision.intValue++
        true
    }.getOrDefault(false)

    /**
     * Añade una figura y devuelve la lista resultante.
     *
     * Las nuevas van **al principio**: lo último que se guarda es lo que se está
     * usando, y buscarlo al final de una lista larga sería empezar por
     * desplazar.
     */
    fun anadir(context: Context, figura: FiguraGuardada): List<FiguraGuardada> {
        val lista = listOf(figura) + cargar(context)
        guardar(context, lista)
        return lista
    }

    /** Quita una figura por id y devuelve la lista resultante. */
    fun quitar(context: Context, id: String): List<FiguraGuardada> {
        val lista = cargar(context).filter { it.id != id }
        guardar(context, lista)
        return lista
    }
}
