package com.forge.pixpin.guardados

import android.content.Context
import com.forge.pixpin.motor.ImportarHojas
import com.forge.pixpin.motor.TablasEnDisco
import java.io.File

/**
 * **Un Excel del chat es un libro de tablas**, como un PDF del chat es un documento de páginas
 * (lo pidió el usuario el 11-sep-2026).
 *
 * Se lee la primera vez que se abre o que se une a un proyecto, y cada hoja queda como una tabla
 * con un identificador **sacado del mensaje** (`libro-<mensaje>-<hoja>`): así tocar el archivo en
 * el chat y abrir la hoja desde el proyecto llevan a la misma tabla, y lo que se cambie en un
 * sitio se ve en el otro. Es lo mismo que se hace con las fotos: ver [Mensaje.dibujoDeLaFoto].
 */
object LibroDelChat {

    fun esLibro(m: Mensaje): Boolean =
        m.clase == Clase.ARCHIVO && m.ruta != null &&
            (ImportarHojas.esLibro(m.nombre) || ImportarHojas.esLibro(m.ruta))

    fun idDeHoja(m: Mensaje, hoja: Int) = "libro-${m.id}-$hoja"

    /**
     * Las tablas del libro —identificador y nombre—, leyéndolo si aún no se había leído. Trabajo
     * de disco: fuera del hilo principal. Si el archivo no se deja leer, lanza
     * [ImportarHojas.NoSeLee] con el porqué.
     */
    fun tablas(context: Context, m: Mensaje): List<Pair<String, String>> {
        val almacen = TablasEnDisco.de(context.filesDir)
        val hechas = generateSequence(0) { it + 1 }.map { idDeHoja(m, it) }.takeWhile { almacen.archivo(it).exists() }.toList()
        if (hechas.isNotEmpty()) return hechas.map { it to (almacen.cargar(it)?.nombre ?: "Tabla") }
        val ruta = m.ruta ?: return emptyList()
        val hojas = ImportarHojas.leer(File(ruta), m.nombre.ifBlank { File(ruta).name })
        return hojas.mapIndexedNotNull { i, h ->
            val id = idDeHoja(m, i)
            if (almacen.guardar(id, h.tabla)) id to h.nombre else null
        }
    }
}
