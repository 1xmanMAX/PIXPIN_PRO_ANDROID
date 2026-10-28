package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.forge.pixpin.pin.Voz
import java.io.File
import java.util.UUID

/**
 * **Meter una grabación en el chat** (28-oct-2026, «Grabar sin perder nada»): lo que hacían por su
 * cuenta «Terminar» de [GrabarClaseService] y de [ReunionPresencialService], sacado aquí para que **la
 * grabación recuperada tras un corte entre por el mismo camino** ([RecuperarGrabacion]), como el PC
 * (`recuperar_una` llama al mismo `guardar` que «Terminar»).
 *
 * No dice nada al usuario (devuelve un [Resultado]: lo pasa a texto quien llama) y **no borra nada**:
 * lo que entró y lo que sobra lo recoge quien llama, que sabe si la grabación sigue en su diario.
 */
object GuardarLaGrabacion {

    /** Cómo fue. */
    enum class Que {
        /** La nota y lo demás, en el chat. */
        LISTA,
        /** No había nota pero sí vídeo: entró el vídeo solo (la clase), o lo de la cámara suelto (la reunión). */
        SIN_NOTA,
        /** Menos de [Voz.MINIMO_MS]: un resbalón en el botón. */
        MUY_CORTA,
        /** No había nada que meter. */
        NADA
    }

    data class Resultado(
        val que: Que,
        /** La nota que entró (para «Abrir para estudiar»). */
        val notaId: String? = null,
        val capturas: Int = 0,
        val clips: Int = 0,
        /** Si se mandó a pasar a texto. */
        val conTexto: Boolean = false
    )

    /** Una captura o foto con su minuto, ya en el disco. */
    data class Imagen(val ms: Long, val archivo: File)

    /** Un clip con su minuto: su MP4, o null si se recorta del vídeo de la reunión. */
    data class ClipHecho(val ms: Long, val duracionMs: Long, val archivo: File?)

    /**
     * **Una clase** (pantalla o capturas): la nota y detrás el vídeo y cada captura, enlazadas (como
     * `guardar` del PC), todo en su carpeta y la nota a pasar a texto. [duracionConocida], la del
     * respaldo al recuperar (se fía de ella si el reproductor no sabe leer la nota).
     */
    fun clase(
        context: Context, nota: File?, duracionConocida: Int?, proyecto: String?, nombre: String, ahora: Long,
        picos: List<Int>, capturas: List<Imagen>, video: File?, reloj: RelojDePared?, pasarATexto: Boolean = true
    ): Resultado {
        val almacen = MensajesStore(context)
        val conNota = nota != null && nota.isFile
        if (!conNota && video != null && video.length() > 0) {
            // Vídeo sin nota: entra solo, como un archivo más.
            AlChat.meterArchivo(context, video, GrabarClase.nombreDelVideo(nombre), "video/mp4", proyecto)
            return Resultado(Que.SIN_NOTA)
        }
        if (!conNota) return Resultado(Que.NADA)
        val duracion = Voz.duracion(nota!!.absolutePath).takeIf { it > 0 } ?: duracionConocida ?: 0
        if (duracion < Voz.MINIMO_MS) return Resultado(Que.MUY_CORTA)
        val vozId = UUID.randomUUID().toString()
        // **El vídeo, justo detrás de la nota** (como el PC): se renombra en su sitio, sin copiar un
        // fichero que puede pesar un giga.
        val elVideo = video?.takeIf { it.isFile && it.length() > 0 }?.let { v ->
            val nombreDelVideo = GrabarClase.nombreDelVideo(nombre)
            val final = File(v.parentFile, "${ahora}_${nombreConExtension(nombreDelVideo, "mp4")}")
            val ruta = if (v.renameTo(final)) final else v
            Mensaje(
                id = UUID.randomUUID().toString(),
                cuando = ahora + 1,
                clase = Clase.ARCHIVO,
                ruta = ruta.absolutePath,
                nombre = nombreDelVideo,
                bytes = ruta.length(),
                duracionMs = Voz.duracion(ruta.absolutePath),
                proyecto = proyecto,
                enGrabacion = GrabarClase.enGrabacion(vozId, 0)
            )
        }
        val primera = if (elVideo != null) 2 else 1
        val enlazadas = ArrayList<GrabarClase.Enlace>()
        val imagenes = ArrayList<Mensaje>()
        capturas.sortedBy { it.ms }.forEachIndexed { i, h ->
            val fichero = "clase-$ahora-${i + 1}.png"
            // Ni se aligera ni se endereza: es una captura de pantalla recién hecha.
            val ruta = almacen.copiarAdjunto(h.archivo, fichero, "png", aligerar = false, enderezar = false) ?: return@forEachIndexed
            val id = UUID.randomUUID().toString()
            val ms = h.ms.coerceIn(0, duracion.toLong())
            imagenes += Mensaje(
                id = id,
                cuando = ahora + primera + i,
                clase = Clase.IMAGEN,
                texto = GrabarClase.pie(ms, nombre),
                ruta = ruta,
                nombre = fichero,
                bytes = File(ruta).length(),
                proyecto = proyecto,
                enGrabacion = GrabarClase.enGrabacion(vozId, ms)
            )
            enlazadas += GrabarClase.Enlace(ms, id)
        }
        val voz = Mensaje(
            id = vozId,
            cuando = ahora,
            clase = Clase.VOZ,
            ruta = nota.absolutePath,
            nombre = GrabarClase.nombreDeLaNota(nombre),
            bytes = nota.length(),
            duracionMs = duracion,
            picos = picos,
            proyecto = proyecto,
            // Como el PC desde `df01da9`: siempre, aunque no haya capturas.
            capturas = GrabarClase.capturasEnJson(enlazadas),
            grabacion = GrabarClase.CLASE,
            video = elVideo?.let { GrabarClase.videoEnJson(it.id) },
            reloj = reloj?.let(RelojDeLaGrabacion::enJson)
        )
        // En orden y con números seguidos, como el PC: la nota, el vídeo y las capturas.
        almacen.anadir(voz)
        elVideo?.let { almacen.anadir(it) }
        imagenes.forEach { almacen.anadir(it) }
        // **Y todo dentro de su carpeta** (como el PC desde `e015e2b`).
        runCatching {
            val carpeta = GrabarClase.carpetaDeLaGrabacion(vozId, duracion.toLong(), elVideo?.id, enlazadas)
            val cuando = CarpetaDePaginas.cuandoLibre(System.currentTimeMillis(), almacen.leer())
            almacen.anadir(CarpetaDePaginas.mensajeNuevo(nombre, cuando, proyecto, carpeta))
        }.onFailure { android.util.Log.w("PixPinClase", "la clase quedó sin su carpeta", it) }
        // **Y se pasa a texto**, por la cola ([ColaDeTexto]).
        val conTexto = pasarATexto && Transcriptor.disponible(context)
        if (conTexto && ColaDeTexto.entraSola(voz, hayAudio = true, grabadoAqui = true)) almacen.transcribir(voz)
        return Resultado(Que.LISTA, vozId, enlazadas.size, 0, conTexto)
    }

    /** Un trozo del vídeo de la reunión ya junto (con el sonido de la nota): su fichero y su minuto. */
    data class Parte(val archivo: File, val desdeMs: Long)

    /**
     * **Una reunión presencial**: la nota, el vídeo (la primera [partes]; las siguientes, si cambió la
     * calidad, como clips en su minuto), las fotos y los clips enlazados ([Presencial.mensajes]) y todo
     * en su carpeta. Los clips sin fichero se recortan de la parte del vídeo en que caen.
     */
    fun reunion(
        context: Context, nota: File?, duracionConocida: Int?, proyecto: String?, nombre: String, ahora: Long,
        picos: List<Int>, fotos: List<Imagen>, clips: List<ClipHecho>, partes: List<Parte>, reloj: RelojDePared?,
        pasarATexto: Boolean = true
    ): Resultado {
        val almacen = MensajesStore(context)
        val carpeta = almacen.carpetaDeAdjuntos()
        val m4a = nota?.takeIf { it.isFile }
        val duracion = m4a?.let { Voz.duracion(it.absolutePath).takeIf { d -> d > 0 } ?: duracionConocida } ?: 0
        val conNota = m4a != null && duracion >= Voz.MINIMO_MS
        val vozId = UUID.randomUUID().toString()
        val buenas = partes.filter { it.archivo.isFile && it.archivo.length() > 0 }
        val enLaNota = buenas.map { Presencial.Tramo(it.desdeMs, Voz.duracion(it.archivo.absolutePath).toLong().coerceAtLeast(0)) }

        // Los clips: recortados del vídeo de la reunión, o con el sonido de la nota puesto.
        val clipsListos = clips.sortedBy { it.ms }.mapIndexedNotNull { i, c ->
            val final = File(carpeta, "${ahora}_${nombreConExtension(Presencial.nombreDelClip(nombre, i + 1), "mp4")}")
            val hecho = if (c.archivo == null) {
                val (k, dentro, dura) = Presencial.tramoDelClip(enLaNota, c.ms, c.duracionMs) ?: return@mapIndexedNotNull null
                Mp4DeLaReunion.recortar(buenas[k].archivo, dentro, dura, final)
            } else {
                val conSonido = m4a != null && Mp4DeLaReunion.ponerSonido(c.archivo, m4a, c.ms, final)
                if (conSonido) c.archivo.delete() else c.archivo.renameTo(final)
                final.exists()
            }
            if (!hecho) return@mapIndexedNotNull null
            val dura = Voz.duracion(final.absolutePath).toLong().takeIf { it > 0 } ?: c.duracionMs
            Presencial.ClipGuardado(UUID.randomUUID().toString(), c.ms, dura, final.absolutePath, Presencial.nombreDelClip(nombre, i + 1), final.length())
        }
        // Las fotos: con su nombre del PC (`foto-<hora>-<n>.jpg`), los bytes tal cual.
        val fotosListas = fotos.sortedBy { it.ms }.mapIndexedNotNull { i, f ->
            val ext = f.archivo.extension.ifEmpty { "jpg" }
            val fichero = Presencial.nombreDeLaFoto(true, ahora, i + 1, ext)
            val final = File(carpeta, "${ahora}_$fichero")
            if (!f.archivo.renameTo(final)) return@mapIndexedNotNull null
            Presencial.FotoGuardada(UUID.randomUUID().toString(), Presencial.msDeLaFoto(f.ms, duracion.toLong().takeIf { conNota }), final.absolutePath, fichero, final.length())
        }
        // El vídeo de la reunión: la primera parte; las siguientes (720p pasada la hora), como clips.
        val video = buenas.firstOrNull()?.archivo?.let { v ->
            val n = GrabarClase.nombreDelVideo(nombre)
            val final = File(carpeta, "${ahora}_${nombreConExtension(n, "mp4")}")
            val ruta = if (v.renameTo(final)) final else v
            Presencial.VideoGuardado(UUID.randomUUID().toString(), ruta.absolutePath, n, ruta.length(), Voz.duracion(ruta.absolutePath))
        }
        val otrasPartes = buenas.drop(1).zip(enLaNota.drop(1)).mapIndexed { i, (t, tr) ->
            val n = "$nombre · parte ${i + 2}.mp4"
            val final = File(carpeta, "${ahora}_${nombreConExtension(n, "mp4")}")
            val ruta = if (t.archivo.renameTo(final)) final else t.archivo
            Presencial.ClipGuardado(UUID.randomUUID().toString(), tr.desdeMs, tr.duracionMs, ruta.absolutePath, n, ruta.length())
        }
        val todosLosClips = (clipsListos + otrasPartes).sortedBy { it.ms }

        if (!conNota) {
            // Sin nota no hay reunión que enlazar: lo de la cámara entra suelto, como el PC.
            var cuando = ahora
            fun suelto(m: Mensaje) = almacen.anadir(m)
            video?.let { suelto(Mensaje(id = it.id, cuando = cuando++, clase = Clase.ARCHIVO, ruta = it.ruta, nombre = it.nombre, bytes = it.bytes, duracionMs = it.duracionMs, proyecto = proyecto)) }
            for (f in fotosListas) suelto(Mensaje(id = f.id, cuando = cuando++, clase = Clase.IMAGEN, ruta = f.ruta, nombre = f.nombre, bytes = f.bytes, proyecto = proyecto, fuente = Presencial.DE_CAMARA))
            for (c in todosLosClips) suelto(Mensaje(id = c.id, cuando = cuando++, clase = Clase.ARCHIVO, ruta = c.ruta, nombre = c.nombre, bytes = c.bytes, duracionMs = c.duracionMs.toInt(), proyecto = proyecto, fuente = Presencial.DE_CAMARA))
            val algo = video != null || fotosListas.isNotEmpty() || todosLosClips.isNotEmpty()
            return Resultado(if (algo) Que.SIN_NOTA else if (m4a != null) Que.MUY_CORTA else Que.NADA, null, fotosListas.size, todosLosClips.size)
        }
        val final = File(carpeta, "${ahora}_${nombreConExtension(GrabarClase.nombreDeLaNota(nombre), "m4a")}")
        val rutaVoz = if (m4a!!.renameTo(final)) final else m4a
        val mensajes = Presencial.mensajes(
            vozId, nombre, ahora, proyecto, rutaVoz.absolutePath, rutaVoz.length(), duracion, picos,
            video, fotosListas, todosLosClips
        ).map { if (it.id == vozId) it.copy(reloj = reloj?.let(RelojDeLaGrabacion::enJson)) else it }
        // En orden y con números seguidos, como el PC: la nota, el vídeo, las fotos y los clips.
        mensajes.forEach { almacen.anadir(it) }
        runCatching {
            val doc = Presencial.carpeta(vozId, duracion.toLong(), video?.id, fotosListas, todosLosClips)
            val cuando = CarpetaDePaginas.cuandoLibre(System.currentTimeMillis(), almacen.leer())
            almacen.anadir(CarpetaDePaginas.mensajeNuevo(nombre, cuando, proyecto, doc))
        }.onFailure { android.util.Log.w("PixPinReunion", "la reunión quedó sin su carpeta", it) }
        val conTexto = pasarATexto && Transcriptor.disponible(context)
        if (conTexto && ColaDeTexto.entraSola(mensajes.first(), hayAudio = true, grabadoAqui = true)) almacen.transcribir(mensajes.first())
        return Resultado(Que.LISTA, vozId, fotosListas.size, todosLosClips.size, conTexto)
    }

    /** Un trozo del vídeo con el grupo al que pertenece (los del mismo tamaño se juntan). */
    data class TrozoDeGrupo(val archivo: File, val desdeMs: Long, val grupo: Int)

    /**
     * **Junta los trozos del vídeo** en partes (una por grupo, o más si un trozo no pega con el anterior),
     * cada una con el sonido de [nota] que le toca ([TrozosDeVideo.unir]). Los trozos que entraron se
     * borran; los que no se abren se quedan. [salida] da el fichero de cada parte.
     */
    fun juntarVideo(trozos: List<TrozoDeGrupo>, nota: File?, salida: (Int) -> File): List<Parte> {
        val partes = ArrayList<Parte>()
        var n = 1
        for ((_, delGrupo) in trozos.groupBy { it.grupo }.toSortedMap()) {
            var quedan = delGrupo.sortedBy { it.desdeMs }.map { TrozosDeVideo.Trozo(it.archivo, it.desdeMs) }
                .filter { GrabacionAMedias.mirar(it.archivo).seAbre }
            while (quedan.isNotEmpty()) {
                val f = salida(n)
                val entraron = TrozosDeVideo.unir(quedan, nota, f)
                if (entraron.isEmpty()) {
                    // El primero no se deja leer: se salta (se queda donde está) y se sigue.
                    quedan = quedan.drop(1)
                    continue
                }
                partes += Parte(f, entraron.first().desdeMs)
                entraron.forEach { it.archivo.delete() }
                quedan = quedan.drop(quedan.indexOf(entraron.last()) + 1)
                n++
            }
        }
        return partes
    }

    /** `<nota>.parte`: donde se graba la nota hasta cerrarse. */
    fun parteDe(nota: File): File = File(nota.path + ".parte")

    /**
     * **Cierra la nota [nota]** (28-oct-2026, la tabla «Al parar» de la guía del PC): si el `.parte` se
     * cerró ([bien], con su `moov`), se pone en su nombre; si no (el codificador falló, el disco), se
     * rehace del respaldo WAV en el acto. Null si no queda nota: entonces el `.parte` y el respaldo **se
     * quedan** (para recuperarlos cuando haya sitio).
     */
    fun cerrarLaNota(nota: File, bien: Boolean): File? {
        val parte = parteDe(nota)
        if (bien && GrabacionAMedias.mirar(parte) == GrabacionAMedias.EstadoMp4.CERRADO && parte.renameTo(nota)) return nota
        val respaldo = com.forge.pixpin.audio.RespaldoWav.rutaDelRespaldo(nota)
        val ms = com.forge.pixpin.audio.RespaldoWav.duracionMs(respaldo) ?: 0
        if (ms >= GrabacionAMedias.MINIMO_NOTA_MS && com.forge.pixpin.audio.RehacerM4a.rehacer(respaldo, nota) != null) {
            android.util.Log.i("PixPinClase", "la nota no se cerró: rehecha del respaldo ($ms ms)")
            return nota
        }
        return null
    }

    /** El nombre del chat [proyecto] para enseñarlo («» el general). */
    fun nombreDelChat(context: Context, proyecto: String?): String =
        proyecto?.let { runCatching { (context.applicationContext as? com.forge.pixpin.PixPinApp)?.proyectos?.porId(it)?.nombre }.getOrNull() }.orEmpty()

    /**
     * **Las capturas de una clase con vídeo**, sacadas de sus fotogramas a PNG en [dir]: el permiso de la
     * pantalla no da para otra captura mientras se graba el vídeo.
     */
    fun fotogramas(mp4: File, minutos: List<Long>, dir: File): List<Imagen> {
        if (minutos.isEmpty()) return emptyList()
        dir.mkdirs()
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(mp4.absolutePath)
            minutos.mapIndexedNotNull { i, ms ->
                val foto = VideoDeLaClase.fotograma(r, ms) ?: return@mapIndexedNotNull null
                val f = File(dir, "fotograma-$i-$ms.png")
                val bien = runCatching { f.outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) } }.isSuccess
                foto.recycle()
                if (bien) Imagen(ms, f) else null
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { r.release() }
        }
    }
}
