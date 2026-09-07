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

        /** Por dónde va cada nota de voz que se está pasando a texto (id → 0..1). */
        val avances = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Float>>(emptyMap())

        private val CERROJO = Any()
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
    fun anadir(mensaje: Mensaje, transcribir: Boolean = true, idioma: String? = null) {
        // **Lo que entra en el chat de un proyecto entra en el proyecto.** Una foto de la
        // obra o el PDF del cliente se guardan en la conversación del proyecto porque es lo
        // que está a mano, y de ahí a las hojas iba un menú: ahora van solos, como hoja, sin
        // tocar nada (lo pidió el usuario el 5-sep-2026). Se hace **fuera del hilo** que
        // guarda —un PDF de cuarenta páginas se rasteriza— y se apunta el mensaje ya como
        // unido para que el menú no lo ofrezca otra vez. Ver [UnirAlProyecto.seUneSolo].
        val seUne = mensaje.proyecto != null && UnirAlProyecto.seUneSolo(mensaje)
        var apuntado = if (seUne) mensaje.copy(unido = true) else mensaje
        // **Su número en la conversación**, que no se reutiliza jamás: uno más que el mayor
        // que haya, y nunca menos que cuantos hay —eso es lo que deja seguir la cuenta de
        // los mensajes de antes, que no llevan número, sin renumerar nada. Ver [Mensaje.numero].
        if (apuntado.numero <= 0) {
            val suyos = leer().filter { it.proyecto == apuntado.proyecto }
            val mayor = suyos.maxOfOrNull { it.numero } ?: 0
            apuntado = apuntado.copy(numero = maxOf(mayor, suyos.size) + 1)
        }
        // **La música no se pasa a texto: se le pega la letra a mano.** Y qué es música lo
        // dicen las etiquetas del archivo (artista, álbum, título…), no su largo: una nota de
        // voz importada de diez minutos es una nota de voz (lo reportó el usuario el
        // 6-sep-2026). Sin etiquetas, se transcribe; y desde el menú siempre se puede
        // poner letra o pasar a texto, elija lo que elija esto.
        if (apuntado.clase == Clase.VOZ && apuntado.estadoDelTexto == null && apuntado.ruta != null && pareceMusica(apuntado.ruta)) {
            apuntado = apuntado.copy(estadoDelTexto = TEXTO_LETRA)
        }
        runCatching {
            archivo.appendText(json.encodeToString(Mensaje.serializer(), apuntado) + "\n")
        }
        if (seUne) unirAlProyecto(apuntado)
        // **Una nota de voz se pasa a texto** en cuanto se guarda. Ver [transcribir].
        if (transcribir && apuntado.clase == Clase.VOZ && apuntado.ruta != null && !apuntado.esMusica) transcribir(apuntado, idioma)
        cambios.value = cambios.value + 1
    }

    /**
     * **La nota de voz, pasada a texto, se queda en la propia nota.** Ver [Transcriptor].
     *
     * Mientras dura, [avances] lleva por dónde va (0 a 1) para que el chat lo enseñe; al
     * acabar, el texto y cómo acabó se escriben en el mensaje ([Mensaje.transcripcion],
     * [Mensaje.estadoDelTexto]) y, en el chat de un proyecto, además en una hoja de notas
     * con el audio adjunto. Antes salía como una nota aparte que contestaba al audio y no
     * se podía abrir ni se leía entera (lo reportó el usuario el 5-sep-2026).
     */
    fun transcribir(m: Mensaje, idioma: String? = null) {
        val ruta = m.ruta ?: return
        if (!Transcriptor.disponible(context)) {
            // Se dice por qué, una vez: sin el reconocedor en el dispositivo no hay texto.
            Thread { actualizar(m.id) { it.copy(estadoDelTexto = TEXTO_MAL) } }.start()
            avisar(context.getString(com.forge.pixpin.R.string.guardados_transcripcion_no))
            return
        }
        ponerAvance(m.id, 0f)
        // [idioma] solo lo pone quien practica otro idioma (ver `PronunciarActivity`); si no, el de Ajustes.
        // Una conversación por turnos se reconoce turno a turno, con el nombre delante de cada uno.
        val tramos = m.turnos.map { Transcriptor.Tramo(it.desdeMs, it.hastaMs, "**${it.quien}:** ") }.ifEmpty { null }
        Transcriptor.transcribir(context, File(ruta), idioma = idioma, avance = { ponerAvance(m.id, it) }, tramos = tramos) { r ->
            Thread {
                when (r) {
                    is Transcriptor.Resultado.Texto -> {
                        val cuando = System.currentTimeMillis()
                        // Con sus tiempos: cada párrafo dice en qué minuto va, y se salta ahí.
                        val texto = r.porParrafos ?: if (r.segmentos.isEmpty()) r.texto else Transcriptor.conTiempos(r.segmentos)
                        val hoja = apuntarTranscripcion(
                            titulo = context.getString(com.forge.pixpin.R.string.guardados_transcripcion),
                            cuerpo = texto, audio = File(ruta), proyecto = m.proyecto, cuando = cuando
                        )
                        actualizar(m.id) {
                            it.copy(
                                transcripcion = texto,
                                estadoDelTexto = if (r.avisos > 0) TEXTO_CON_AVISOS else TEXTO_BIEN,
                                hojaDelTexto = hoja
                            )
                        }
                    }
                    is Transcriptor.Resultado.DescargandoIdioma -> {
                        actualizar(m.id) { it.copy(estadoDelTexto = TEXTO_MAL) }
                        avisar(context.getString(com.forge.pixpin.R.string.guardados_transcripcion_idioma))
                    }
                    is Transcriptor.Resultado.Fallo -> {
                        actualizar(m.id) { it.copy(estadoDelTexto = TEXTO_MAL) }
                        val aviso = when (r.codigo) {
                            Transcriptor.NADA -> null
                            Transcriptor.NO_SE_LEE -> com.forge.pixpin.R.string.guardados_transcripcion_no
                            Transcriptor.SIN_MOTOR -> com.forge.pixpin.R.string.guardados_transcripcion_sin_motor
                            Transcriptor.SIN_IDIOMA -> com.forge.pixpin.R.string.guardados_transcripcion_sin_idioma
                            else -> com.forge.pixpin.R.string.guardados_transcripcion_fallo
                        }
                        // Con el detalle técnico detrás: es lo que permite saber qué pasó sin el registro.
                        if (aviso != null) avisar(context.getString(aviso) + (r.detalle?.let { "\n($it)" } ?: ""))
                    }
                }
                quitarAvance(m.id)
            }.start()
        }
    }

    /** Si el archivo lleva etiquetas de canción: artista, álbum, género o título. */
    private fun pareceMusica(ruta: String): Boolean = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(ruta)
            listOf(
                android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST,
                android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM,
                android.media.MediaMetadataRetriever.METADATA_KEY_GENRE,
                android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
            ).any { !r.extractMetadata(it).isNullOrBlank() }
        } finally { runCatching { r.release() } }
    }.getOrDefault(false)

    /** Cambia un mensaje ya guardado, con el archivo entero bajo llave. */
    fun actualizar(id: String, cambio: (Mensaje) -> Mensaje) {
        synchronized(CERROJO) {
            val lista = leer()
            if (lista.none { it.id == id }) return
            reescribir(lista.map { if (it.id == id) cambio(it) else it })
        }
    }

    private fun ponerAvance(id: String, v: Float) { avances.value = avances.value + (id to v) }
    private fun quitarAvance(id: String) { avances.value = avances.value - id }

    /**
     * Deja una transcripción como hoja de notas del proyecto —**con el audio dentro**,
     * copiado a los adjuntos de las notas, para que se escuche mientras se lee y sobreviva
     * a que se borre el mensaje—. Devuelve el id de la hoja, o null si no hay proyecto.
     * Ver `MedioUi` en el editor y [com.forge.pixpin.motormd.MarkdownHtml]. La usan la
     * transcripción de una nota de voz y la conversación por turnos.
     */
    fun apuntarTranscripcion(titulo: String, cuerpo: String, audio: File?, proyecto: String?, cuando: Long): String? {
        val app = context.applicationContext as? com.forge.pixpin.PixPinApp ?: return null
        val elProyecto = proyecto?.let { app.proyectos.porId(it) } ?: return null
        val adjunto = audio?.takeIf { it.exists() }?.let {
            com.forge.pixpin.motormd.Adjuntos.importarArchivo(context, it, cuando, "voz-$cuando." + it.name.substringAfterLast('.', "m4a"))
        }
        val texto = if (adjunto == null) "# $titulo\n\n$cuerpo" else "# $titulo\n\n![audio]($adjunto)\n\n$cuerpo"
        val id = "nota-$cuando"
        app.proyectos.conHoja(elProyecto, com.forge.pixpin.motor.Hoja(id = id, nombre = titulo, nota = texto), cuando)
        return id
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
