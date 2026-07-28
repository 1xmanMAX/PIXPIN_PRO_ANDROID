package com.forge.pixpin.pin

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
    val minimized: Boolean = false
)
