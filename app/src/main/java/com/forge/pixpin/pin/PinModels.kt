package com.forge.pixpin.pin

import com.forge.pixpin.annotate.Annotation
import kotlinx.serialization.Serializable

enum class PinType { IMAGE, TEXT, COLOR, FILE }

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
    val emoji: String? = null
)
