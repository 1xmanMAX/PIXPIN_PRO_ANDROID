package com.forge.pixpin.guardados

import java.util.concurrent.ConcurrentHashMap

/**
 * **El historial no salta al subir** (1-nov-2026, `ancla` y `hueco_de_pagina` de `pixpin-ui` y
 * `ventana_chat` del PC, bd006fce y f41f79e5).
 *
 * La lista del chat ya guarda su sitio por la clave de cada mensaje (`LazyColumn` con `key = id`): lo que se
 * añade arriba no mueve lo que se lee. Lo que sí lo movía era **una foto o una hoja de PDF que entra por
 * arriba mientras aún se lee**: se pintaba la ficha del archivo (una fila baja) y, al llegar la imagen,
 * la burbuja crecía de golpe y empujaba hacia abajo todo lo que se estaba leyendo. En el PC se arregló
 * pintando ya **una caja del alto final**, en tono apagado, mientras su hilo la lee; aquí, lo mismo:
 *
 * - **La medida de cada foto** se sabe sin leerla entera (solo su cabecera, `inJustDecodeBounds`) y a la
 *   medida a que se leerá ([com.forge.pixpin.pin.ImageStore.medidaNitida]); la de una hoja de PDF, desde la
 *   primera vez que se compuso. Se apunta aquí ([apuntar]) y se reparte a todos los chats del proceso.
 * - **Se precarga** al abrir el chat, en su hilo y **primero por el final** —donde se abre y desde donde
 *   se sube— ([enOrdenDePrecarga]).
 * - [caja] es la cuenta pura de lo que ocupará en la burbuja, la misma que hace `Image` al encajarla.
 */
object HuecoDeLaFoto {
    private val medidas = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** Lo que se recuerda como mucho (unas cuantas por cada chat de muchas fotos). */
    private const val TOPE = 4_000

    /** La medida (en píxeles) a la que se pintará lo de [clave], si se sabe. */
    fun sabida(clave: String?): Pair<Int, Int>? = clave?.let { medidas[it] }

    /** Apunta la medida de lo de [clave] (una foto ya leída, o su cabecera). */
    fun apuntar(clave: String, ancho: Int, alto: Int) {
        if (ancho <= 0 || alto <= 0) return
        if (medidas.size >= TOPE) medidas.clear()
        medidas[clave] = ancho to alto
    }

    /** La clave de una hoja de PDF (su ruta y su página). */
    fun deHoja(pdf: String, pagina: Int): String = "$pdf#$pagina"

    /**
     * **En qué orden se precargan** las medidas de [rutas] (en el orden del chat, de arriba abajo): primero
     * las del final, que es donde se abre y desde donde se sube, sin repetir ni volver a mirar las sabidas.
     */
    fun enOrdenDePrecarga(rutas: List<String>, sabida: (String) -> Boolean = { medidas.containsKey(it) }): List<String> =
        rutas.asReversed().distinct().filterNot(sabida)

    /**
     * **Lo que ocupará en la burbuja** una imagen de [ancho]×[alto] píxeles con [maxAncho]×[maxAlto] de
     * sitio: su medida si cabe; si no, encogida sin deformarla hasta caber (lo que hace `Image` con
     * `ContentScale.Fit`). Todo en píxeles.
     */
    fun caja(ancho: Int, alto: Int, maxAncho: Int, maxAlto: Int): Pair<Int, Int> {
        if (ancho <= 0 || alto <= 0 || maxAncho <= 0 || maxAlto <= 0) return 0 to 0
        val f = minOf(1.0, maxAncho.toDouble() / ancho, maxAlto.toDouble() / alto)
        if (f >= 1.0) return ancho to alto
        return Math.round(ancho * f).toInt().coerceIn(1, maxAncho) to Math.round(alto * f).toInt().coerceIn(1, maxAlto)
    }

    /**
     * **Mira la cabecera de una foto** y apunta la medida a la que la leerá la burbuja ([lado] y
     * [maxPixeles] de [com.forge.pixpin.pin.ImageStore.cargarNitida]). Trabajo de disco: en su hilo.
     */
    fun medir(ruta: String, lado: Int, maxPixeles: Long) {
        if (medidas.containsKey(ruta)) return
        runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(ruta, o)
            if (o.outWidth > 0 && o.outHeight > 0) {
                val (a, b) = com.forge.pixpin.pin.ImageStore.medidaNitida(o.outWidth, o.outHeight, lado, maxPixeles)
                apuntar(ruta, a, b)
            }
        }
    }
}
