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

        /** El mismo que usa la sincronización para escribir el chat: no pueden cruzarse. */
        private val CERROJO = com.forge.pixpin.sincro.Cerrojos.chat
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

    /**
     * Añade uno. Es una línea al final del archivo: no toca lo que ya había.
     *
     * **[transcribir] viene apagado, y esto es a propósito.** Antes toda nota de voz se
     * pasaba a texto sola en cuanto se guardaba; el usuario lo pidió al revés el
     * 8-sep-2026: «si subo un vídeo o grabo un vídeo no se traduzca o se saque su texto
     * inmediatamente automáticamente, sino al darle el botón recién se haga ese proceso».
     * Y tiene razón por tres motivos que se ven en el aparato: un audio largo tiene al
     * teléfono minutos con el reconocedor puesto sin que nadie lo haya pedido, gasta
     * batería, y llena la conversación de hojas de transcripción que nadie quería.
     *
     * Ahora el texto sale **solo** al pulsar el botón de la nota de voz
     * (`BotonDeTexto` en `MensajesActivity`). Lo único que sigue pidiéndolo solo es
     * practicar pronunciación, y ahí el texto **es** la función: sin él no hay con qué
     * comparar lo que has leído (`PronunciarActivity`, que pasa `transcribir = true`).
     */
    fun anadir(mensaje: Mensaje, transcribir: Boolean = false, idioma: String? = null) {
        // **Lo que entra en el chat de un proyecto ya NO entra solo en el proyecto** (usuario,
        // 19-sep-2026). Desde el 5-sep una foto o un PDF mandados al chat de un proyecto se hacían
        // hoja sin preguntar, y el proyecto se llenaba de cosas que solo se querían tener a mano en
        // la conversación. Ahora el mensaje se queda en el chat con su **punto rojo** y se añade
        // **a mano**, desde su menú («Añadir al proyecto»); entonces el punto pasa a verde. Ver
        // `MensajesActivity.unirA` y [UnirAlProyecto.sePuedeUnir].
        //
        // Lo que llega ya unido (el PDF con el que nació el proyecto, una zona, lo que trae la
        // sincronización o un `.pixpin`) sigue como estaba: su relación no se toca aquí.
        // **Un lienzo que llega de otro proyecto entra en este** (14-sep-2026): reenviado o
        // adjuntado desde otro proyecto, el mensaje se quedaba en el chat y el lienzo no salía
        // en las hojas. Un lienzo del propio proyecto ya es una hoja y no se toca.
        val app = context.applicationContext as? com.forge.pixpin.PixPinApp
        val destino = mensaje.proyecto?.let { app?.proyectos?.porId(it) }
        val lienzoDeFuera = !mensaje.unido && mensaje.clase == Clase.DIBUJO && destino != null &&
            mensaje.referencia != null && destino.hojas.none { it.dibujo == mensaje.referencia }
        var apuntado = if (lienzoDeFuera) mensaje.copy(unido = true) else mensaje
        // **Su número en la conversación**, que no se reutiliza jamás: uno más que el mayor
        // que haya, y nunca menos que cuantos hay —eso es lo que deja seguir la cuenta de
        // los mensajes de antes, que no llevan número, sin renumerar nada. Ver [Mensaje.numero].
        if (apuntado.numero <= 0) {
            val suyos = leer().filter { it.proyecto == apuntado.proyecto }
            val mayor = suyos.maxOfOrNull { it.numero } ?: 0
            apuntado = apuntado.copy(numero = maxOf(mayor, suyos.size) + 1)
        }
        // **Y sus códigos**: el único, oculto, y el de este aparato, que con el número da el código de
        // chat (`48·K7Q2`) y no choca con el 48 que otro aparato haya creado a la vez. Lo que llega de
        // otro aparato ya trae los suyos y no se tocan. Ver [com.forge.pixpin.sincro.Codigos].
        apuntado = com.forge.pixpin.sincro.Codigos.sellar(
            apuntado,
            com.forge.pixpin.sincro.Codigos.deEsteAparato(context.filesDir)
        )
        // **La música no se pasa a texto: se le pega la letra a mano.** Y qué es música lo
        // dicen las etiquetas del archivo (artista, álbum, título…), no su largo: una nota de
        // voz importada de diez minutos es una nota de voz (lo reportó el usuario el
        // 6-sep-2026). Sin etiquetas, se transcribe; y desde el menú siempre se puede
        // poner letra o pasar a texto, elija lo que elija esto.
        if (apuntado.clase == Clase.VOZ && apuntado.estadoDelTexto == null && apuntado.ruta != null && pareceMusica(apuntado.ruta)) {
            apuntado = apuntado.copy(estadoDelTexto = TEXTO_LETRA)
        }
        runCatching {
            synchronized(CERROJO) { archivo.appendText(json.encodeToString(Mensaje.serializer(), apuntado) + "\n") }
        }
        if (lienzoDeFuera && app != null && destino != null) {
            val m = apuntado
            app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val ahora = System.currentTimeMillis()
                    val p = app.proyectos.porId(destino.id) ?: return@runCatching
                    if (p.hojas.any { it.dibujo == m.referencia }) return@runCatching
                    // El mismo dibujo que señala el mensaje —ya es una copia suya, ver
                    // [RamaDeMensaje]— y apuntando al mensaje: borrarlo del chat lo quita.
                    app.proyectos.conHoja(
                        p,
                        com.forge.pixpin.motor.Hoja(
                            id = "hoja-$ahora", nombre = m.nombre.ifBlank { "Lienzo" },
                            dibujo = m.referencia, deMensaje = m.id
                        ),
                        ahora
                    )
                }
            }
        }
        // **Solo si quien llama lo pide**, que hoy es únicamente practicar pronunciación.
        // Ver el porqué en la documentación de esta función.
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
                            cuerpo = texto, audio = File(ruta), proyecto = m.proyecto, cuando = cuando, deMensaje = m.id
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
    fun apuntarTranscripcion(
        titulo: String, cuerpo: String, audio: File?, proyecto: String?, cuando: Long,
        /** El audio del que sale: la hoja lo lleva, y así no se vuelve a contar en el chat. */
        deMensaje: String? = null
    ): String? {
        val app = context.applicationContext as? com.forge.pixpin.PixPinApp ?: return null
        val elProyecto = proyecto?.let { app.proyectos.porId(it) } ?: return null
        val adjunto = audio?.takeIf { it.exists() }?.let {
            com.forge.pixpin.motormd.Adjuntos.importarArchivo(context, it, cuando, "voz-$cuando." + it.name.substringAfterLast('.', "m4a"))
        }
        val texto = if (adjunto == null) "# $titulo\n\n$cuerpo" else "# $titulo\n\n![audio]($adjunto)\n\n$cuerpo"
        val id = "nota-$cuando"
        app.proyectos.conHoja(elProyecto, com.forge.pixpin.motor.Hoja(id = id, nombre = titulo, nota = texto, deMensaje = deMensaje), cuando)
        return id
    }

    private fun avisar(texto: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, texto, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * **El peso que se enseña, el del archivo de verdad** (21-sep-2026). Un PDF se aligera
     * **después** de entrar, en segundo plano ([com.forge.pixpin.pdf.PdfSqueezeService]), y el
     * mensaje seguía diciendo lo que pesaba al llegar. Aquí se mira el archivo de cada PDF y, si
     * no cuadra, se corrige. Devuelve si cambió algo. Trabajo de disco.
     */
    fun ponerPesosAlDia(): Boolean = synchronized(CERROJO) {
        val antes = leer()
        var cambio = false
        val ahora = antes.map { m ->
            val ruta = m.ruta?.takeIf { it.endsWith(".pdf", ignoreCase = true) } ?: return@map m
            val pesa = File(ruta).takeIf { it.isFile }?.length() ?: return@map m
            if (pesa > 0 && pesa != m.bytes) { cambio = true; m.copy(bytes = pesa) } else m
        }
        if (cambio) reescribir(ahora)
        cambio
    }

    /**
     * Reescribe la lista entera.
     *
     * Para borrar, fijar y editar. Se escribe en un archivo aparte y se cambia al final:
     * quedarse a medias aquí significaría perder todo lo guardado, y eso no puede pasar
     * por cerrar la aplicación en mal momento.
     */
    fun reescribir(mensajes: List<Mensaje>): Unit = synchronized(CERROJO) {
        cambios.value = cambios.value + 1
        // **Lo que se va deja su marca**, para que la sincronización no lo resucite desde otro
        // aparato. Siempre, aunque aún no haya grupo: con los códigos únicos cualquier cosa puede
        // haber viajado ya a otro aparato por un envío.
        runCatching {
            val disco = com.forge.pixpin.sincro.Disco(context.filesDir)
            val letra = com.forge.pixpin.sincro.IdentidadEnDisco(context.filesDir).letraSiHay()
            disco.anotarBorrados(com.forge.pixpin.sincro.Disco.borradosEntre(leer(), mensajes, letra, System.currentTimeMillis()))
        }
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
        Unit
    }

    /**
     * Copia un archivo de fuera a la carpeta propia y devuelve la ruta.
     *
     * **Copiar y no apuntar** a donde estaba: lo que se guarda tiene que seguir ahí dentro
     * de un mes, y una ruta a la galería o a descargas se rompe en cuanto el usuario
     * ordena su móvil. Guardar una referencia que se puede evaporar es no guardar nada.
     */
    fun copiarAdjunto(
        origen: File,
        nombre: String,
        deSuTipo: String? = null,
        /**
         * Si un PDF se aligera al copiarlo. **Lo que llega sincronizando, no**: los dos aparatos
         * comparan los archivos por su resumen, así que reescribir aquí lo que acaba de llegar los
         * dejaría distintos para siempre y volverían a pasárselo en cada vuelta.
         */
        aligerar: Boolean = true
    ): String? = runCatching {
        // Con su extensión: sin ella, al abrirlo después Android no sabe de qué es. Ver
        // [nombreConExtension].
        val comoSeLlama = nombreConExtension(nombre, deSuTipo)
        val destino = File(carpetaDeAdjuntos(), "${System.currentTimeMillis()}_$comoSeLlama")
        origen.copyTo(destino, overwrite = true)
        // **Un PDF se aligera en cuanto entra**, aquí y no más tarde: por este sitio pasan todos
        // los adjuntos del chat, y lo que entra pesando cinco megas pesa lo mismo cada vez que se
        // comparte, se sincroniza o se manda por Wi-Fi. Lo pidió el usuario el 14-sep-2026. Se
        // llama desde un hilo de disco (ver quien llama). Ver [com.forge.pixpin.pdf.ComprimirPdf].
        if (aligerar && destino.name.endsWith(".pdf", ignoreCase = true)) {
            com.forge.pixpin.pdf.ComprimirPdf.enSuSitio(destino)
        }
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
