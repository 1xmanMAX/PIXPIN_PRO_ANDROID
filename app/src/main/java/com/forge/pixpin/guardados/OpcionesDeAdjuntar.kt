package com.forge.pixpin.guardados

/**
 * **La hoja del clip, ordenada como la del «+» del PC** (27-oct-2026, pedido del usuario;
 * `ventana_chat/hoja_adjuntar.rs` del PC). Antes era una sola fila de dieciséis botones iguales que
 * se deslizaba de lado: lo de todos los días y lo de una vez al mes con el mismo peso, y lo del
 * final no lo encontraba nadie. Ahora, como el PC:
 *
 * - **Un buscador arriba**: se escribe y se filtra por el nombre y sus sinónimos, sin tildes
 *   («pdf» encuentra Archivo, «dibujo» Lienzo).
 * - **Lo de todos los días** en una cuadrícula de cuatro, cada uno con su círculo de color: los
 *   del PC en su orden (Foto o vídeo, Archivo, Lienzo, Nota, Lista de tareas, Conversación) y,
 *   donde el PC pone «Captura» y «Tabla», lo del teléfono: **Escanear** (la cámara) y **Grabar
 *   clase**. La tabla no se crea desde el teléfono.
 * - **Más**, debajo y más pequeño: lo que se usa menos (Figura, Página de un proyecto, Proyecto
 *   entero, Carpeta de páginas, Croquis 3D, Teleprónter, Pronunciar y las demás mini apps).
 * - En el chat del **Asistente**, primero **Figura** y lo que se le da a mirar
 *   (`Hoja::para_el_asistente` del PC).
 *
 * Nota y Croquis 3D solo en el chat de un proyecto: son hojas suyas.
 *
 * Cada opción hace exactamente lo que hacía su botón de antes; aquí solo se decide cómo se elige.
 * Sin Android: lo prueba `OpcionesDeAdjuntarTest`.
 */
object OpcionesDeAdjuntar {

    /**
     * [rotulo] es el español, el que se busca; [texto], el rótulo en el idioma puesto (1-nov-2026).
     * Las [palabras] llevan también las inglesas, para que el buscador sirva en los dos idiomas.
     */
    enum class Adjunto(val rotulo: String, @androidx.annotation.StringRes val texto: Int, val palabras: String, val color: Long) {
        FOTO("Foto o vídeo", com.forge.pixpin.R.string.adjuntar_foto, "imagen foto video galeria photo image gallery", 0xFF0A6CD6),
        /**
         * **La cámara del teléfono** (5-nov-2026, el usuario: «tomar directamente la foto con la cámara nativa
         * del celular y que se adjunte automáticamente»): la foto entra en el cuadro de enviar, como las elegidas.
         */
        CAMARA("Cámara", com.forge.pixpin.R.string.cm_camara, "camara foto hacer tomar sacar camera take photo picture", 0xFF0A6CD6),
        ARCHIVO("Archivo", com.forge.pixpin.R.string.adjuntar_archivo, "pdf documento word excel fichero file document", 0xFF636366),
        LIENZO("Lienzo", com.forge.pixpin.R.string.adjuntar_lienzo, "dibujo pizarra canvas draw nuevo board new", 0xFFC77700),
        NOTA("Nota", com.forge.pixpin.R.string.adjuntar_nota, "nota markdown texto note text", 0xFF248A3D),
        TAREAS("Lista de tareas", com.forge.pixpin.R.string.adjuntar_tareas, "tareas lista todo checklist mini-app tasks task list to-do", 0xFF1E7A4A),
        CONVERSACION("Conversación", com.forge.pixpin.R.string.adjuntar_conversacion, "conversacion voz turnos traducir conversation voice translate", 0xFF9A3FC4),
        ESCANEAR("Escanear", com.forge.pixpin.R.string.adjuntar_escanear, "escanear camara documento captura scan camera", 0xFFC93C35),
        CLASE("Grabar clase", com.forge.pixpin.R.string.adjuntar_clase, "grabar clase reunion audio capturas record class meeting", 0xFFB0306A),
        FIGURA("Figura", com.forge.pixpin.R.string.adjuntar_figura, "figura plantilla biblioteca dibujo shape mejorar template library", 0xFF8E44B8),
        PAGINA("Página de un proyecto", com.forge.pixpin.R.string.adjuntar_pagina, "pagina hoja proyecto page sheet project", 0xFF5B6B7F),
        PROYECTO("Proyecto entero", com.forge.pixpin.R.string.adjuntar_proyecto, "proyecto entero project whole", 0xFF5B6B7F),
        CARPETA("Carpeta de páginas", com.forge.pixpin.R.string.adjuntar_carpeta, "carpeta cuaderno diario libreta folder notebook diary", 0xFF5B6B7F),
        CROQUIS("Croquis 3D", com.forge.pixpin.R.string.adjuntar_croquis, "croquis 3d modelo boceto sketch model", 0xFF5B6B7F),
        MUSICA("Música", com.forge.pixpin.R.string.adjuntar_musica, "musica cancion letra karaoke lrc mp3 song lyrics music", 0xFF5B6B7F),
        TELEPRONTER("Teleprónter", com.forge.pixpin.R.string.adjuntar_telepronter, "teleprompter leer en voz alta read aloud", 0xFF5B6B7F),
        PRONUNCIAR("Pronunciar", com.forge.pixpin.R.string.adjuntar_pronunciar, "pronunciar ingles pronounce english", 0xFF5B6B7F),
        /**
         * **Sin «Cámara para la reunión del PC»** (29-oct-2026, pedido del usuario): aquí quedaba
         * demasiado escondida. Ahora es el botón de la cámara junto al «+» de la lista de chats, que
         * solo sale mientras un PC del grupo graba. Ver `ui/ListaDeChats`.
         */
        /**
         * **Pegar lo copiado** (2-nov-2026, la «Adjuntar» del PC, `f87468aa`): lo mismo que pegar —una
         * foto o un archivo copiado entra como adjunto; un texto va a la caja de escribir—. Va
         * abajo, aparte, y no sale si no hay nada copiado.
         */
        PEGAR("Pegar lo copiado", com.forge.pixpin.R.string.adjuntar_pegar, "pegar portapapeles copiado paste clipboard copied", 0xFF5B6B7F),
        MINIAPPS("Mini apps", com.forge.pixpin.R.string.adjuntar_miniapps, "mini-app cronometro temporizador contador ruleta gastos alarma stopwatch timer counter wheel expenses alarm", 0xFF5B6B7F);

        /** Donde busca el buscador: el nombre y sus sinónimos, sin tildes. */
        val buscable: String = sinAcentos("$rotulo $palabras")
    }

    /** Las dos partes de la hoja: la cuadrícula de todos los días y los «Más». */
    data class Hoja(val deSiempre: List<Adjunto>, val mas: List<Adjunto>)

    private val DE_SIEMPRE = listOf(
        Adjunto.CAMARA, Adjunto.FOTO, Adjunto.ARCHIVO, Adjunto.LIENZO, Adjunto.NOTA,
        Adjunto.TAREAS, Adjunto.CONVERSACION, Adjunto.ESCANEAR, Adjunto.CLASE
    )
    private val MAS = listOf(
        Adjunto.FIGURA, Adjunto.PAGINA, Adjunto.PROYECTO, Adjunto.CARPETA,
        Adjunto.CROQUIS, Adjunto.MUSICA, Adjunto.TELEPRONTER, Adjunto.PRONUNCIAR, Adjunto.MINIAPPS
    )

    /** Lo del asistente delante, en este orden (como `para_el_asistente` del PC). */
    private val DEL_ASISTENTE = listOf(Adjunto.FIGURA, Adjunto.CAMARA, Adjunto.FOTO, Adjunto.ARCHIVO, Adjunto.ESCANEAR, Adjunto.LIENZO, Adjunto.NOTA)

    /**
     * **La hoja de este chat**. [conProyecto]: si el chat es de un proyecto (tiene Nota y Croquis);
     * [asistente]: si es el chat del Asistente.
     */
    /** La función del catálogo de la que depende cada cosa de la hoja (7-nov-2026): oculta, no sale. */
    fun delCatalogo(a: Adjunto): String = when (a) {
        Adjunto.CAMARA, Adjunto.FOTO, Adjunto.ARCHIVO -> com.forge.pixpin.cuenta.Disponible.VISORES
        Adjunto.LIENZO, Adjunto.FIGURA, Adjunto.CROQUIS -> com.forge.pixpin.cuenta.Disponible.LIENZO
        Adjunto.TAREAS -> com.forge.pixpin.cuenta.Disponible.TAREAS
        Adjunto.ESCANEAR -> com.forge.pixpin.cuenta.Disponible.ESCANER
        Adjunto.CLASE -> com.forge.pixpin.cuenta.Disponible.GRABAR
        Adjunto.MUSICA -> com.forge.pixpin.cuenta.Disponible.MUSICA
        else -> com.forge.pixpin.cuenta.Disponible.CHATS
    }

    fun hoja(conProyecto: Boolean, asistente: Boolean): Hoja {
        val vale = { a: Adjunto -> (conProyecto || (a != Adjunto.NOTA && a != Adjunto.CROQUIS)) && com.forge.pixpin.cuenta.Disponible.ve(delCatalogo(a)) }
        if (asistente) {
            val delante = DEL_ASISTENTE.filter(vale)
            return Hoja(delante, (DE_SIEMPRE + MAS).filter { vale(it) && it !in delante })
        }
        return Hoja(DE_SIEMPRE.filter(vale), MAS.filter(vale))
    }

    /** **Lo que deja el buscador**, en el mismo orden; sin nada escrito, todo. */
    fun filtrar(hoja: Hoja, consulta: String): Hoja {
        val q = sinAcentos(consulta.trim())
        if (q.isEmpty()) return hoja
        val casa = { a: Adjunto -> a.buscable.contains(q) }
        return Hoja(hoja.deSiempre.filter(casa), hoja.mas.filter(casa))
    }
}
