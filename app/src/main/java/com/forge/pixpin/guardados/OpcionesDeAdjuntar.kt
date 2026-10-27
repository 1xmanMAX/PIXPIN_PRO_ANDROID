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

    enum class Adjunto(val rotulo: String, val palabras: String, val color: Long) {
        FOTO("Foto o vídeo", "imagen foto video galeria photo image", 0xFF0A6CD6),
        ARCHIVO("Archivo", "pdf documento word excel fichero file", 0xFF636366),
        LIENZO("Lienzo", "dibujo pizarra canvas draw nuevo", 0xFFC77700),
        NOTA("Nota", "nota markdown texto note", 0xFF248A3D),
        TAREAS("Lista de tareas", "tareas lista todo checklist mini-app", 0xFF1E7A4A),
        CONVERSACION("Conversación", "conversacion voz turnos traducir conversation", 0xFF9A3FC4),
        ESCANEAR("Escanear", "escanear camara documento captura scan", 0xFFC93C35),
        CLASE("Grabar clase", "grabar clase reunion audio capturas", 0xFFB0306A),
        FIGURA("Figura", "figura plantilla biblioteca dibujo shape mejorar", 0xFF8E44B8),
        PAGINA("Página de un proyecto", "pagina hoja proyecto page", 0xFF5B6B7F),
        PROYECTO("Proyecto entero", "proyecto entero project", 0xFF5B6B7F),
        CARPETA("Carpeta de páginas", "carpeta cuaderno diario libreta folder notebook", 0xFF5B6B7F),
        CROQUIS("Croquis 3D", "croquis 3d modelo boceto", 0xFF5B6B7F),
        TELEPRONTER("Teleprónter", "teleprompter leer en voz alta", 0xFF5B6B7F),
        PRONUNCIAR("Pronunciar", "pronunciar ingles pronounce", 0xFF5B6B7F),
        MINIAPPS("Mini apps", "mini-app cronometro temporizador contador ruleta gastos alarma", 0xFF5B6B7F);

        /** Donde busca el buscador: el nombre y sus sinónimos, sin tildes. */
        val buscable: String = sinAcentos("$rotulo $palabras")
    }

    /** Las dos partes de la hoja: la cuadrícula de todos los días y los «Más». */
    data class Hoja(val deSiempre: List<Adjunto>, val mas: List<Adjunto>)

    private val DE_SIEMPRE = listOf(
        Adjunto.FOTO, Adjunto.ARCHIVO, Adjunto.LIENZO, Adjunto.NOTA,
        Adjunto.TAREAS, Adjunto.CONVERSACION, Adjunto.ESCANEAR, Adjunto.CLASE
    )
    private val MAS = listOf(
        Adjunto.FIGURA, Adjunto.PAGINA, Adjunto.PROYECTO, Adjunto.CARPETA,
        Adjunto.CROQUIS, Adjunto.TELEPRONTER, Adjunto.PRONUNCIAR, Adjunto.MINIAPPS
    )

    /** Lo del asistente delante, en este orden (como `para_el_asistente` del PC). */
    private val DEL_ASISTENTE = listOf(Adjunto.FIGURA, Adjunto.FOTO, Adjunto.ARCHIVO, Adjunto.ESCANEAR, Adjunto.LIENZO, Adjunto.NOTA)

    /**
     * **La hoja de este chat**. [conProyecto]: si el chat es de un proyecto (tiene Nota y Croquis);
     * [asistente]: si es el chat del Asistente.
     */
    fun hoja(conProyecto: Boolean, asistente: Boolean): Hoja {
        val vale = { a: Adjunto -> conProyecto || (a != Adjunto.NOTA && a != Adjunto.CROQUIS) }
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
