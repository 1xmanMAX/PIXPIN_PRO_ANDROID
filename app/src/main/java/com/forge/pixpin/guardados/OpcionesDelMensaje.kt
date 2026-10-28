package com.forge.pixpin.guardados

import java.util.Locale

/**
 * **Qué lleva el menú de mantener pulsado un mensaje, según lo que es** (28-oct-2026, lo pidió el
 * usuario: «mantengo pulsado un PDF y me sale "recordatorio", "llamada falsa"… ¿cómo funcionaría
 * eso en un PDF? Es absurdo y solo ocupa espacio; analiza por tipo —archivo, texto, audio,
 * imagen…— y quita lo que no aplica, que la lista es demasiado larga»).
 *
 * Antes el menú era uno para todo, con cada opción tapada por su propio `if` repartido por la
 * pantalla: un PDF llevaba «Recordármelo» y «Hacer lección», una carpeta «Copiar» (copiaba su
 * JSON), una canción «Llamada secreta». Ahora se decide aquí, de una vez y sin Android, qué
 * [Tipo] es el mensaje y qué le toca, y la pantalla solo pinta la lista.
 *
 * **El orden es el del PC** (`ventana_chat.rs`, `menu_v2`): lo diario arriba —copiar, responder,
 * pinear—, luego lo de cada tipo, luego mandarlo a otro sitio y acordarse, y lo raro en «Más».
 * Dos diferencias a propósito: **cambiar el nombre va lo primero** (el usuario, 4-oct: «que sea
 * notorio»), y **pinear solo donde la burbuja no lleva ya su botón** (22-sep: «muchas de esas
 * opciones ya están repetidas»). Fijar, poner una etiqueta y elegir van siempre, en «Más» y en
 * ese orden; borrar, siempre, aparte y en rojo, lo pone la pantalla.
 *
 * **Nada se pierde**: lo que tenía sentido y se usa poco baja a «Más»; solo sale lo que en ese
 * tipo no hace nada útil (una alarma en un PDF, una llamada en una canción, copiar el JSON de una
 * carpeta). A la vista, como mucho unas ocho entradas contando «Más».
 *
 * Sin Android: se prueba en `OpcionesDelMensajeTest`.
 */
object OpcionesDelMensaje {

    /** Lo que es el mensaje, a efectos de su menú. */
    enum class Tipo {
        /** Una nota escrita. */
        NOTA,
        /** Una nota escrita que lleva una dirección web. */
        ENLACE,
        /** Una nota de voz. */
        VOZ,
        /** Una canción ([esMusica]). */
        MUSICA,
        /** La nota de una clase o reunión grabada (con `grabacion` o sus capturas por minuto). */
        GRABACION,
        FOTO,
        VIDEO,
        /** Un documento que se lee: PDF, Word, libro, página, texto… (lo de [LecturaVeloz.EXTENSIONES]). */
        DOCUMENTO,
        /** Cualquier otro archivo: un zip, un plano, un APK, una lección. */
        ARCHIVO,
        /** Algo que se abre como hoja: un lienzo, una página de un PDF, una tabla, un croquis. */
        HOJA,
        /** Una lista de tareas u otra mini-app. */
        MINIAPP,
        /** Una carpeta de páginas, la carpeta de una grabación o el acceso a un proyecto. */
        CARPETA,
        /** Una respuesta del asistente. */
        ASISTENTE
    }

    /** Cada entrada posible del menú. La pantalla pone el rótulo, el icono y qué hace. */
    enum class Opcion {
        RENOMBRAR, COPIAR, RESPONDER, PINEAR,
        EDITAR_EN_ESCANER, LECTURA_VELOZ, DESCRIBIR, TRANSCRIBIR, LETRA, ESTUDIAR, ABRIR_CARPETA,
        REENVIAR, COMPARTIR,
        /** Recordármelo; en una nota de voz, «Llamada secreta»; con uno puesto, quitarlo. */
        RECORDAR,
        HACER_LECCION, ANADIR_A_CARPETA, ENVIAR_A_ANKI, GUARDAR_RECETA,
        // Las de «Más».
        FIJAR, ETIQUETAR, ELEGIR,
        ABRIR_CON, ALIGERAR, SOLO_LA_FOTO, FOTO_DEL_CHAT, INSERTAR_EN_NOTA,
        VER_HILO, UNIR_AL_PROYECTO, DEVOLVER_AL_PROYECTO, RESCATAR,
        /**
         * **Las fotos por su hora** (28-oct-2026, §12-§14 de la guía del PC): en la nota de una clase,
         * «Que todos los del chat aporten fotos» / «Solo mis fotos en esta grabación»; en una foto que
         * aún no es de ninguna, «Adjuntar a la grabación».
         */
        APORTAN_TODOS, ADJUNTAR_A_LA_GRABACION
    }

    /**
     * Lo que no sale del propio mensaje y sabe la pantalla: de quién es, qué hay alrededor y qué
     * puede este aparato. Todo con un valor por defecto «no», que es lo que no ofrece nada de más.
     */
    data class Contexto(
        /** De otra persona, en un chat compartido: no se le cambia el nombre ni el escaneo a la vista. */
        val ajeno: Boolean = false,
        /** El aparato sabe pasar audio a texto. */
        val puedeTranscribir: Boolean = false,
        /** Es de una clase que se puede estudiar (su nota, una captura suya o su vídeo). */
        val puedeEstudiar: Boolean = false,
        /** Alguien lo ha comentado: hay un hilo que ver. */
        val hayHilo: Boolean = false,
        /** Se puede meter en las hojas de su proyecto. */
        val puedeUnir: Boolean = false,
        /** Se quitó de su proyecto y se puede devolver. */
        val puedeDevolver: Boolean = false,
        /** Se puede invitar a los del chat a aportar fotos a esta grabación (§14). */
        val puedeInvitar: Boolean = false,
        /** Se puede adjuntar esta foto a una grabación del chat (§12, a mano). */
        val puedeAdjuntarALaGrabacion: Boolean = false
    )

    /** El menú de un mensaje: lo que se ve al abrirlo y lo de «Más». Borrar va siempre aparte. */
    data class Menu(val visibles: List<Opcion>, val enMas: List<Opcion>) {
        operator fun contains(o: Opcion) = o in visibles || o in enMas
    }

    /** Qué [Tipo] es [m]. */
    fun tipoDe(m: Mensaje): Tipo = when {
        com.forge.pixpin.asistente.ChatDelAsistente.esRespuesta(m) -> Tipo.ASISTENTE
        CarpetaDePaginas.esCarpeta(m) || CarpetaDePaginas.esGrabacion(m) || m.clase == Clase.PROYECTO -> Tipo.CARPETA
        m.clase == Clase.NOTA -> if (SeccionesDelChat.tieneEnlace(m.texto)) Tipo.ENLACE else Tipo.NOTA
        m.clase == Clase.VOZ -> when {
            m.esMusica -> Tipo.MUSICA
            m.grabacion != null || m.capturas != null -> Tipo.GRABACION
            else -> Tipo.VOZ
        }
        SeccionesDelChat.esVideo(m) -> Tipo.VIDEO
        m.clase == Clase.IMAGEN -> Tipo.FOTO
        m.clase == Clase.ARCHIVO ->
            if (!com.forge.pixpin.lecciones.LeccionesStore.esLeccion(m) && LecturaVeloz.admite(m.nombre, m.ruta)) Tipo.DOCUMENTO
            else Tipo.ARCHIVO
        m.clase == Clase.MINIAPP -> Tipo.MINIAPP
        else -> Tipo.HOJA // DIBUJO, PAGINA, TABLA, CROQUIS
    }

    /** **El menú de [m]**, en su orden. */
    fun para(m: Mensaje, contexto: Contexto = Contexto()): Menu {
        val tipo = tipoDe(m)
        val v = mutableListOf<Opcion>()
        val mas = mutableListOf<Opcion>()
        val conTexto = m.texto.isNotBlank()
        val esArchivo = m.ruta != null
        val leccion = com.forge.pixpin.lecciones.LeccionesStore.esLeccion(m)
        val transcrita = !m.transcripcion.isNullOrBlank()

        // ---- Lo diario ----
        // Cambiar el nombre, lo primero (4-oct); de lo de otra persona, en «Más».
        if (Renombrar.sePuede(m)) (if (contexto.ajeno) mas else v) += Opcion.RENOMBRAR
        // Copiar donde hay texto que copiar. En una foto o un archivo el texto es su descripción:
        // se copia poco, va en «Más». Una mini-app o una carpeta guardan JSON: nada que copiar.
        if (conTexto) when (tipo) {
            Tipo.NOTA, Tipo.ENLACE, Tipo.ASISTENTE -> v += Opcion.COPIAR
            Tipo.MINIAPP, Tipo.CARPETA -> Unit
            else -> mas += Opcion.COPIAR
        }
        v += Opcion.RESPONDER
        // Pinear lo que no lleva el botón en su burbuja: una nota, una nota de voz, una lista.
        when (tipo) {
            Tipo.NOTA, Tipo.ENLACE, Tipo.VOZ, Tipo.MINIAPP -> v += Opcion.PINEAR
            Tipo.MUSICA, Tipo.GRABACION, Tipo.ASISTENTE -> mas += Opcion.PINEAR
            else -> Unit
        }

        // ---- Lo de cada tipo ----
        if (m.escaneado != null) (if (contexto.ajeno) mas else v) += Opcion.EDITAR_EN_ESCANER
        if (tipo == Tipo.DOCUMENTO && esArchivo) v += Opcion.LECTURA_VELOZ
        if (Descripcion.sePuede(m)) when (tipo) {
            Tipo.FOTO, Tipo.VIDEO, Tipo.DOCUMENTO, Tipo.ARCHIVO -> v += Opcion.DESCRIBIR
            else -> mas += Opcion.DESCRIBIR
        }
        if (tipo == Tipo.CARPETA && CarpetaDePaginas.esGrabacion(m)) v += Opcion.ABRIR_CARPETA
        if (contexto.puedeEstudiar) v += Opcion.ESTUDIAR
        // Volver a pasar a texto, en «Más»: sin texto, ya lo hace el botón de la burbuja, y con él
        // se repite pocas veces.
        if ((tipo == Tipo.VOZ || tipo == Tipo.GRABACION) && esArchivo && contexto.puedeTranscribir && transcrita) mas += Opcion.TRANSCRIBIR
        if (m.clase == Clase.VOZ && esArchivo) (if (tipo == Tipo.MUSICA) v else mas) += Opcion.LETRA

        // ---- Mandarlo a otro sitio y acordarse ----
        v += Opcion.REENVIAR
        v += Opcion.COMPARTIR
        // **Recordármelo** solo donde tiene sentido que te lo pongan delante a una hora: una nota,
        // un enlace, una lista; en una nota de voz es la llamada secreta. Una foto o una hoja, en
        // «Más». Nunca en un documento, un archivo, una canción, una clase o una carpeta. Con uno
        // ya puesto se ofrece quitarlo siempre: si no, no habría por dónde.
        when {
            m.recuerdaEn != null -> v += Opcion.RECORDAR
            tipo == Tipo.NOTA || tipo == Tipo.ENLACE || tipo == Tipo.MINIAPP || tipo == Tipo.VOZ -> v += Opcion.RECORDAR
            tipo == Tipo.FOTO || tipo == Tipo.HOJA -> mas += Opcion.RECORDAR
        }
        // Una lección sale de un texto, una nota de voz o una foto; de un archivo no (PC, 8-oct:
        // «las lecciones no aceptan archivos»).
        if (!leccion) when (tipo) {
            Tipo.NOTA, Tipo.VOZ, Tipo.GRABACION -> v += Opcion.HACER_LECCION
            Tipo.ENLACE, Tipo.FOTO, Tipo.HOJA, Tipo.ASISTENTE -> mas += Opcion.HACER_LECCION
            else -> Unit
        }
        // Una hoja a un cuaderno: a la vista un lienzo, una página o una foto; lo demás, en «Más».
        if (CarpetaDePaginas.puedeIr(m) && tipo != Tipo.CARPETA && tipo != Tipo.VOZ && tipo != Tipo.MUSICA &&
            tipo != Tipo.GRABACION && tipo != Tipo.ASISTENTE
        ) (if (tipo == Tipo.HOJA || tipo == Tipo.FOTO) v else mas) += Opcion.ANADIR_A_CARPETA
        if (com.forge.pixpin.anki.AnkiDelMensaje.sePuede(m) && tipo != Tipo.CARPETA && tipo != Tipo.MINIAPP && tipo != Tipo.MUSICA)
            (if (tipo == Tipo.GRABACION) v else mas) += Opcion.ENVIAR_A_ANKI
        if (tipo == Tipo.ASISTENTE && !contexto.ajeno) v += Opcion.GUARDAR_RECETA

        // ---- «Más»: siempre las tres de todos, y luego lo raro de cada uno ----
        mas.addAll(0, listOf(Opcion.FIJAR, Opcion.ETIQUETAR, Opcion.ELEGIR))
        if (esArchivo) {
            mas += Opcion.ABRIR_CON
            if (m.ruta!!.lowercase(Locale.ROOT).endsWith(".pdf")) mas += Opcion.ALIGERAR
        }
        if (m.clase == Clase.IMAGEN && m.referencia != null) mas += Opcion.SOLO_LA_FOTO
        if (m.proyecto != null && m.clase == Clase.IMAGEN && esArchivo && !m.enBuzon && tipo == Tipo.FOTO) mas += Opcion.FOTO_DEL_CHAT
        if (tipo != Tipo.CARPETA && tipo != Tipo.MUSICA) mas += Opcion.INSERTAR_EN_NOTA
        if (contexto.hayHilo) mas += Opcion.VER_HILO
        if (contexto.puedeUnir) mas += Opcion.UNIR_AL_PROYECTO
        if (contexto.puedeDevolver) mas += Opcion.DEVOLVER_AL_PROYECTO
        if (m.enBuzon) mas += Opcion.RESCATAR
        if (contexto.puedeInvitar && tipo == Tipo.GRABACION && !contexto.ajeno) mas += Opcion.APORTAN_TODOS
        if (contexto.puedeAdjuntarALaGrabacion && tipo == Tipo.FOTO && m.enGrabacion == null) mas += Opcion.ADJUNTAR_A_LA_GRABACION
        return Menu(v.distinct(), mas.distinct().filterNot { it in v })
    }
}
