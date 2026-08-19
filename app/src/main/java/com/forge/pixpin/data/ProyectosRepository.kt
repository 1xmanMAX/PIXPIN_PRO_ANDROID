package com.forge.pixpin.data

import android.content.Context
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.PdfDelProyecto
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

    /**
     * El contexto de la aplicación, para reponer el PDF de un proyecto.
     *
     * Se guarda el de la aplicación **a propósito**: este repositorio vive
     * mientras vive el proceso, y quedarse con el de una pantalla sería
     * quedarse con una pantalla que se cerró hace rato. Ver [reponerLosPdf].
     */
    private val app = context.applicationContext
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

    /**
     * Si este archivo es el documento de algún proyecto.
     *
     * Lo pregunta el gestor de pines antes de borrar el archivo de uno que se
     * cae del historial: un PDF que se convirtió en proyecto **ya no es del
     * pin**. Ver [Proyectos.usanElArchivo].
     *
     * Es un recorrido de unas decenas de entradas que ya están en memoria, así
     * que se puede preguntar sin pensárselo.
     */
    fun esDeUnProyecto(ruta: String?): Boolean =
        Proyectos.usanElArchivo(_proyectos.value, ruta)

    /**
     * Repone el PDF de los proyectos a los que se les borró **desde la copia
     * limpia**.
     *
     * ## Qué avería arregla
     *
     * El PDF de un proyecto entraba como archivo de un pin y se borraba con él
     * —al cerrarse y caer del historial, o al tirar el pin—, pero el proyecto
     * seguía en la lista señalando a esa ruta. Desde fuera se veía así: **las
     * miniaturas de casi todos los PDF salían en blanco y las de uno sí**, el
     * del único pin que seguía vivo. Y no era solo la foto: sin archivo tampoco
     * se puede abrir una página para anotarla ni entregar el documento.
     *
     * Que no vuelva a pasar lo arregla [esDeUnProyecto]; esto es para los que ya
     * se quedaron sin él.
     *
     * ## Por qué se puede reponer
     *
     * Porque **lo que manda es el dibujo**, no el archivo: las anotaciones viven
     * en sus escenas y el documento se rehace desde la copia limpia cada vez que
     * se guarda una hoja. Ver [Proyecto.pdfLimpio] y [PdfDelProyecto.rehacer].
     * Por eso se rehace aquí mismo en vez de dejar el PDF pelado esperando al
     * próximo guardado: el archivo es lo que se entrega, y tiene que llevar
     * dentro lo anotado desde el primer momento.
     *
     * **Fuera del hilo de la interfaz**, que copia un PDF entero y le vuelve a
     * pintar sus páginas. Lo llama [PixPinApp] al arrancar, en el hilo de disco.
     * Cuando no hay nada roto —lo normal— son un par de preguntas al sistema de
     * archivos y ya.
     *
     * @return cuántos se repusieron.
     */
    fun reponerLosPdf(): Int {
        val rotos = Proyectos.sinSuPdf(_proyectos.value) { File(it).exists() }
        var repuestos = 0
        for (p in rotos) {
            val hecho = runCatching {
                File(p.pdfLimpio!!).copyTo(File(p.pdfOrigen!!), overwrite = true)
                // Y con lo anotado dentro otra vez. Si esto falla, el documento
                // queda como el original: mejor el PDF de partida que ninguno,
                // y el próximo guardado de cualquier hoja lo vuelve a intentar.
                runCatching { PdfDelProyecto.rehacer(app, p) }
                true
            }.getOrDefault(false)
            if (hecho) repuestos++
        }
        return repuestos
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
