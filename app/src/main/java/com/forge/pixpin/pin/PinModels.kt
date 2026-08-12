package com.forge.pixpin.pin

import com.forge.pixpin.annotate.Annotation
import kotlinx.serialization.Serializable

/**
 * Qué es un pin.
 *
 * [CROQUIS] es **historia**: era el editor de tipo CAD, retirado cuando medir
 * pasó a ser dos herramientas del motor (escalar y acotar). El valor se queda
 * en el enum porque los pines guardados llevan el nombre escrito en el JSON, y
 * quitarlo haría que la lista entera fallara al leerse. Los pines de ese tipo
 * se descartan al cargar; ver `PinRepository.read`.
 */
enum class PinType {
    IMAGE, TEXT, COLOR, FILE, TIMER, CHECKLIST, COUNTER, LEDGER, TABLE, CROQUIS, DRAW,
    RULETA
}

/**
 * Estado de las mini-aplicaciones.
 *
 * Va todo en una sola clase, con un campo por herramienta, en vez de una
 * jerarquía sellada: la serialización polimórfica obligaría a registrar cada
 * subtipo y a migrar lo ya guardado en disco, y aquí son cuatro campos sueltos
 * con valor por defecto que no le cuestan nada a quien no los usa.
 */
@Serializable
data class WidgetState(
    /** Casillas marcadas de la lista, una por línea del texto. */
    val checked: List<Boolean> = emptyList(),
    /** Valor del contador. */
    val count: Int = 0,
    /**
     * La ruleta ya está lista para girar: se acabó de escribir la lista.
     *
     * Hacen falta las dos fases porque son dos cosas distintas —escribir a
     * quiénes se sortea y sortear—, y mezclarlas dejaría el teclado encima de la
     * rueda justo cuando se quiere mirar.
     */
    val ruletaLista: Boolean = false,
    /** A quién le tocó en el último giro, o -1 si aún no se ha girado. */
    val ruletaElegido: Int = -1,
    /** Minutos configurados en el temporizador; 0 = solo reloj. */
    val timerMinutes: Int = 0,
    /**
     * Momento en que vence la cuenta atrás, en milisegundos de reloj del
     * sistema. Se guarda el VENCIMIENTO y no lo que queda para que el
     * temporizador siga siendo correcto aunque el pin se cierre o el móvil se
     * reinicie por el camino.
     */
    val timerEndsAt: Long? = null,
    /** El pin cuenta hacia arriba en vez de hacia abajo. */
    val stopwatch: Boolean = false,
    /**
     * Instante en que se puso en marcha, o null si está parado. Igual que el
     * temporizador, se guarda el instante y no lo transcurrido: así la cuenta
     * sigue siendo correcta aunque el pin pase un rato cerrado.
     */
    val runningSince: Long? = null,
    /** Lo acumulado en las vueltas anteriores, antes de la pausa actual. */
    val accumulatedMs: Long = 0
)

/** Estado serializable de un pin: todo lo necesario para restaurarlo. */
@Serializable
data class PinState(
    val id: String,
    val type: PinType,
    val text: String? = null,
    val imagePath: String? = null,
    val colorArgb: Int? = null,
    val filePath: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val x: Int = 100,
    val y: Int = 200,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val clickThrough: Boolean = false,
    val minimized: Boolean = false,
    /** Grupo al que pertenece: los del mismo grupo se mueven, ocultan y cierran juntos. */
    val groupId: String? = null,
    /**
     * Lo dibujado encima del pin, en coordenadas de la imagen original: por eso
     * se ve igual de bien con el pin diminuto o a pantalla completa, y sigue
     * siendo re-editable. Vacío en los pines que no son de imagen.
     */
    val annotations: List<Annotation> = emptyList(),
    /** Marca el pin como guardado: sobrevive al cierre y se muestra en la lista de guardados. */
    val isPinned: Boolean = false,
    /** Ancho del cuadro de texto en dp (solo para TEXT pins). */
    val textBoxWidth: Int = 330,
    /** Alto máximo del cuadro de texto en dp; null = se ajusta al texto. */
    val textBoxHeight: Int? = null,
    /**
     * Pin prioritario. Nace siempre en false y se alterna con pulsación larga
     * sobre su nombre en la lista de pines.
     */
    val priority: Boolean = false,
    /** Emoji pegado en la esquina, a modo de pegatina; null = sin pegatina. */
    val emoji: String? = null,
    /** Estado de la mini-aplicación, si el pin es una. */
    val widget: WidgetState = WidgetState(),
    /**
     * Ruta del JSON del croquis, si el pin es uno.
     *
     * Va la ruta y no el contenido: un croquis puede llevar cientos de
     * entidades y este registro se lee entero al arrancar para restaurar los
     * pines. Mismo trato que `imagePath` y `filePath`.
     */
    val croquisPath: String? = null,

    /**
     * Ruta del `.excalidraw` comprimido del dibujo, si el pin es uno.
     *
     * Mismo trato y misma razón que [croquisPath]: una escena puede llevar
     * cientos de elementos y este registro se lee entero al arrancar.
     */
    val drawPath: String? = null
)
