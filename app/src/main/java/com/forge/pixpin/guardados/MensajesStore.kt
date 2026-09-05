package com.forge.pixpin.guardados

import android.content.Context
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Dónde viven los mensajes guardados.
 *
 * ## Se añade una línea, no se reescribe el archivo
 *
 * Un archivo JSON con todo dentro hay que **volver a escribirlo entero** cada vez que se
 * añade algo, y aquí dentro va texto largo: una nota de mil palabras, el contenido de un
 * markdown compartido. Guardar la línea número doscientos costaría reescribir las
 * doscientas, y eso se nota justo cuando más molesta — al mandar algo deprisa.
 *
 * Así que es un archivo de **una línea por mensaje**: añadir es escribir al final y ya.
 * Solo se reescribe entero al borrar o al editar, que son gestos raros y lentos por
 * naturaleza. Es lo mismo que hace cualquier registro de eventos, y por lo mismo.
 *
 * ## Y una línea rota no se lleva por delante el resto
 *
 * Si una línea no se puede leer —una versión antigua, un guardado a medias por quedarse
 * sin batería— se salta esa y se siguen leyendo las demás. Con un JSON único, esa misma
 * línea rota dejaría **todo** ilegible, que es la diferencia entre perder una cosa y
 * perderlo todo.
 */
class MensajesStore(private val context: Context) {

    companion object {
        /**
         * Sube cada vez que cualquier almacén escribe. La pantalla del chat lo mira para
         * recargar la lista cuando algo entra por otro sitio: una transcripción que llega,
         * una foto compartida desde otra aplicación.
         */
        val cambios = kotlinx.coroutines.flow.MutableStateFlow(0L)
    }


    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val archivo: File get() = File(context.filesDir, "guardados.jsonl")

    /** La carpeta de los adjuntos: copias propias, para que no se las lleve nadie. */
    fun carpetaDeAdjuntos(): File =
        File(context.filesDir, "guardados").apply { mkdirs() }

    /** Todo lo guardado, en el orden en que se escribió. */
    fun leer(): List<Mensaje> {
        val f = archivo
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { linea ->
            if (linea.isBlank()) null
            else runCatching { json.decodeFromString<Mensaje>(linea) }.getOrNull()
        }
    }

    /** Añade uno. Es una línea al final del archivo: no toca lo que ya había. */
    fun anadir(mensaje: Mensaje) {
        // **Lo que entra en el chat de un proyecto entra en el proyecto.** Una foto de la
        // obra o el PDF del cliente se guardan en la conversación del proyecto porque es lo
        // que está a mano, y de ahí a las hojas iba un menú: ahora van solos, como hoja, sin
        // tocar nada (lo pidió el usuario el 5-sep-2026). Se hace **fuera del hilo** que
        // guarda —un PDF de cuarenta páginas se rasteriza— y se apunta el mensaje ya como
        // unido para que el menú no lo ofrezca otra vez. Ver [UnirAlProyecto.seUneSolo].
        val seUne = mensaje.proyecto != null && UnirAlProyecto.seUneSolo(mensaje)
        val apuntado = if (seUne) mensaje.copy(unido = true) else mensaje
        runCatching {
            archivo.appendText(json.encodeToString(Mensaje.serializer(), apuntado) + "\n")
        }
        if (seUne) unirAlProyecto(apuntado)
        // **Una nota de voz se pasa a texto** en cuanto se guarda. Ver [transcribir].
        if (apuntado.clase == Clase.VOZ && apuntado.ruta != null) transcribir(apuntado)
        cambios.value = cambios.value + 1
    }

    /**
     * De la nota de voz a una nota de texto en Markdown, **en el propio teléfono**. Ver
     * [Transcriptor]. El texto entra como una nota que contesta a la de voz —así se ve
     * debajo de ella— y, en el chat de un proyecto, además como hoja de notas del proyecto,
     * que es donde se puede seguir escribiendo en el editor de Markdown.
     */
    fun transcribir(m: Mensaje) {
        val ruta = m.ruta ?: return
        if (!Transcriptor.disponible(context)) return
        Transcriptor.transcribir(context, File(ruta)) { r ->
            when (r) {
                is Transcriptor.Resultado.Texto -> {
                    val cuando = System.currentTimeMillis()
                    val titulo = context.getString(com.forge.pixpin.R.string.guardados_transcripcion)
                    val texto = "# $titulo\n\n" + r.texto
                    anadir(
                        Mensaje(
                            id = java.util.UUID.randomUUID().toString(), cuando = cuando, clase = Clase.NOTA,
                            texto = texto, respondeA = m.id, proyecto = m.proyecto, unido = m.proyecto != null
                        )
                    )
                    val app = context.applicationContext as? com.forge.pixpin.PixPinApp
                    val proyecto = m.proyecto?.let { app?.proyectos?.porId(it) }
                    if (app != null && proyecto != null) {
                        app.proyectos.conHoja(proyecto, com.forge.pixpin.motor.Hoja(id = "nota-$cuando", nombre = titulo, nota = texto), cuando)
                    }
                }
                is Transcriptor.Resultado.DescargandoIdioma -> avisar(context.getString(com.forge.pixpin.R.string.guardados_transcripcion_idioma))
                is Transcriptor.Resultado.Fallo -> avisar(context.getString(com.forge.pixpin.R.string.guardados_transcripcion_no))
            }
        }
    }

    private fun avisar(texto: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, texto, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun unirAlProyecto(m: Mensaje) {
        val app = context.applicationContext as? com.forge.pixpin.PixPinApp ?: return
        val proyecto = m.proyecto ?: return
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cuantas = runCatching {
                UnirAlProyecto.unir(context, app.proyectos, proyecto, listOf(m), System.currentTimeMillis())
            }.getOrDefault(0)
            if (cuantas > 0) {
                val nombre = app.proyectos.porId(proyecto)?.nombre.orEmpty()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        context.resources.getQuantityString(
                            com.forge.pixpin.R.plurals.guardados_unidas, cuantas, cuantas, nombre
                        ),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Reescribe la lista entera.
     *
     * Para borrar, fijar y editar. Se escribe en un archivo aparte y se cambia al final:
     * quedarse a medias aquí significaría perder todo lo guardado, y eso no puede pasar
     * por cerrar la aplicación en mal momento.
     */
    fun reescribir(mensajes: List<Mensaje>) {
        cambios.value = cambios.value + 1
        runCatching {
            val temporal = File(context.filesDir, "guardados.jsonl.nuevo")
            temporal.writeText(
                mensajes.joinToString("") {
                    json.encodeToString(Mensaje.serializer(), it) + "\n"
                }
            )
            // **Renombrar, no copiar.** `copyTo` escribe el archivo entero por segunda
            // vez y, sobre todo, **no es atómico**: morir a mitad de la copia deja
            // `guardados.jsonl` truncado, que es justo lo que el archivo aparte venía a
            // evitar. `renameTo` es un solo paso del sistema de archivos: o está lo
            // viejo o está lo nuevo, nunca medio archivo.
            if (!temporal.renameTo(archivo)) {
                // Renombrar puede fallar si el destino está en otro sistema de archivos;
                // aquí los dos están en `filesDir`, pero si un día dejan de estarlo, más
                // vale una copia que perder lo guardado.
                temporal.copyTo(archivo, overwrite = true)
                temporal.delete()
            }
        }
    }

    /**
     * Copia un archivo de fuera a la carpeta propia y devuelve la ruta.
     *
     * **Copiar y no apuntar** a donde estaba: lo que se guarda tiene que seguir ahí dentro
     * de un mes, y una ruta a la galería o a descargas se rompe en cuanto el usuario
     * ordena su móvil. Guardar una referencia que se puede evaporar es no guardar nada.
     */
    fun copiarAdjunto(origen: File, nombre: String, deSuTipo: String? = null): String? = runCatching {
        // Con su extensión: sin ella, al abrirlo después Android no sabe de qué es. Ver
        // [nombreConExtension].
        val comoSeLlama = nombreConExtension(nombre, deSuTipo)
        val destino = File(carpetaDeAdjuntos(), "${System.currentTimeMillis()}_$comoSeLlama")
        origen.copyTo(destino, overwrite = true)
        destino.absolutePath
    }.getOrNull()

    /** Borra el archivo de un mensaje. Solo al borrar el mensaje, no antes. */
    fun borrarAdjunto(ruta: String?) {
        if (ruta.isNullOrBlank()) return
        // Solo lo que es nuestro: un dibujo del lienzo o una página de un PDF viven en su
        // sitio y los usa alguien más. Borrar ahí sería llevarse el original.
        if (!ruta.contains("/guardados/")) return
        runCatching { File(ruta).delete() }
    }
}
