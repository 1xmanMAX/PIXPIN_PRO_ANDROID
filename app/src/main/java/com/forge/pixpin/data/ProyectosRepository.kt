package com.forge.pixpin.data

import android.content.Context
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Los proyectos, guardados en disco.
 *
 * **El progreso es el archivo.** Es lo que pidió el usuario y lo que hace que
 * cerrar la app a media obra no cueste nada: cada cambio se escribe en cuanto
 * ocurre, así que no hay un estado a medias que restaurar ni un momento en el
 * que se pueda perder lo hecho.
 *
 * En JSON y no en una base de datos por lo mismo que los pines: son unas decenas
 * de entradas con una lista de hojas dentro, y una base de datos para eso es
 * andamio con más superficie de fallo que el problema que resuelve.
 *
 * Lo que **no** guarda es el dibujo de cada hoja: eso vive en el almacén de
 * escenas, y aquí solo consta su identificador. Un proyecto es un índice de
 * dónde está cada cosa, no una copia de las cosas. Ver [Proyecto].
 */
class ProyectosRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val carpeta = File(context.filesDir, "proyectos")
    private val archivo = File(carpeta, "proyectos.json")

    private val _proyectos = MutableStateFlow(leer())

    /**
     * La lista, vigilable.
     *
     * Va como flujo y no como una función que se llama cuando toca porque hay
     * dos sitios mirándola a la vez —la pantalla de proyectos y el editor que
     * está anotando una hoja— y si el editor guarda mientras la lista está
     * abierta, la lista tiene que enterarse sin que nadie se lo diga.
     */
    val proyectos: StateFlow<List<Proyecto>> = _proyectos.asStateFlow()

    fun recargar() {
        _proyectos.value = leer()
    }

    /** Mete el proyecto o sustituye al que tuviera su id, y lo escribe. */
    fun guardar(proyecto: Proyecto) {
        _proyectos.value = Proyectos.actualizada(_proyectos.value, proyecto)
        escribir()
    }

    fun borrar(id: String) {
        // La copia limpia se va con su proyecto: sin él no la mira nadie, y son
        // archivos del tamaño de un PDF.
        porId(id)?.pdfLimpio?.let { runCatching { File(it).delete() } }
        _proyectos.value = _proyectos.value.filter { it.id != id }
        escribir()
    }

    fun porId(id: String?): Proyecto? =
        if (id == null) null else _proyectos.value.firstOrNull { it.id == id }

    /**
     * El proyecto de este PDF, creándolo con **todas sus páginas** si es la
     * primera vez.
     *
     * Reabrir el mismo plano tiene que llevar al proyecto de ayer: si cada vez
     * empezara uno nuevo, las páginas anotadas no estarían. Ver
     * [Proyectos.deEstePdf].
     */
    fun deEstePdf(ruta: String, nombre: String, paginas: Int, ahora: Long): Proyecto {
        Proyectos.deEstePdf(_proyectos.value, ruta)?.let { return it }

        // **Una copia intacta, antes de tocar nada.** Es la base desde la que se
        // rehace el documento en cada guardado, y hay que sacarla ahora: en
        // cuanto se anote la primera página, el archivo del pin ya no es el
        // original y no habría de dónde volver. Ver [Proyecto.pdfLimpio].
        val limpio = runCatching {
            val destino = File(carpeta, "limpio-$ahora.pdf")
            File(ruta).copyTo(destino, overwrite = true)
            destino.path
        }.getOrNull()

        val nuevo = Proyectos.dePdf(
            id = "pr-$ahora",
            nombre = nombre,
            pdf = ruta,
            paginas = paginas,
            cuando = ahora,
            idDeHoja = { "h-$ahora-$it" }
        ).copy(pdfLimpio = limpio)
        guardar(nuevo)
        return nuevo
    }

    /** Un proyecto vacío con un nombre que no repita. */
    fun nuevo(plantilla: String, ahora: Long): Proyecto {
        val p = Proyecto(
            id = "pr-$ahora",
            nombre = Proyectos.nombreLibre(_proyectos.value, plantilla),
            tocado = ahora
        )
        guardar(p)
        return p
    }

    /** Añade una hoja al proyecto y devuelve la que quedó: la nueva o la que ya estaba. */
    fun conHoja(proyecto: Proyecto, hoja: Hoja, ahora: Long): Hoja {
        Proyectos.hojaComo(proyecto, hoja)?.let { return it }
        guardar(Proyectos.conHoja(proyecto, hoja, ahora))
        return hoja
    }

    private fun escribir() {
        runCatching {
            archivo.parentFile?.mkdirs()
            archivo.writeText(json.encodeToString(_proyectos.value))
        }
    }

    private fun leer(): List<Proyecto> = runCatching {
        if (!archivo.exists()) emptyList()
        else json.decodeFromString<List<Proyecto>>(archivo.readText())
    }.getOrDefault(emptyList())
}
