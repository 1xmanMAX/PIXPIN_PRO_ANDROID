package com.forge.pixpin.motor

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.roundToInt

/**
 * Rasterizado del dibujo. Port de `exportToCanvas` (`scene/export.ts`).
 *
 * Sirve para copiar el pin al portapapeles y para guardarlo en la galería, con
 * el mismo renderizador que pinta la pantalla: si divergieran, lo exportado no
 * se parecería a lo que se ve.
 */
object DrawExport {

    /** Margen alrededor del contenido, en px de escena (`exportPadding`). */
    private const val EXPORT_PADDING = 10.0

    /**
     * Tope de tamaño del bitmap resultante.
     *
     * Un lienzo infinito puede tener dos elementos separados por diez mil
     * píxeles, y una exportación a escala 1 sería un bitmap de cientos de MB.
     * El original topa por área por lo mismo (`cappedElementCanvasSize`).
     */
    private const val MAX_DIMENSION = 4096

    /**
     * La escena como bitmap, encuadrando **todo el contenido**.
     *
     * Devuelve null si no hay nada que pintar: quien llama enseña entonces el
     * aviso de dibujo vacío en vez de un rectángulo blanco.
     */
    fun aBitmap(
        scene: Scene,
        scale: Double = 1.0,
        // La lambda va la última para que la sintaxis de bloque final la coja a
        // ella y no a `scale`. Con el orden contrario, `aBitmap(escena) { ... }`
        // compilaba el bloque como si fuera el `Double` y el error que salía no
        // señalaba a la causa.
        imageProvider: (String) -> Bitmap? = { null }
    ): Bitmap? = runCatching {
        val visible = scene.contenidoVisible
        if (visible.isEmpty()) return null

        // **Con hoja manda la hoja.** Sin ella, el encuadre lo da el contenido,
        // que es lo de siempre. Y con hoja no se añade margen: el margen ya lo
        // decides tú al colocar el marco.
        val marco = scene.marco
        val b = if (marco != null) getElementBounds(marco) else getCommonBounds(visible)
        val margen = if (marco != null) 0.0 else EXPORT_PADDING
        val anchoEscena = b.width + margen * 2
        val altoEscena = b.height + margen * 2
        if (anchoEscena <= 0 || altoEscena <= 0) return null

        // La escala se recorta si el resultado se sale del tope; así una escena
        // enorme se exporta más pequeña en vez de fallar.
        val efectiva = minOf(
            scale,
            MAX_DIMENSION / anchoEscena,
            MAX_DIMENSION / altoEscena
        ).coerceAtMost(scale)

        val ancho = (anchoEscena * efectiva).roundToInt().coerceAtLeast(1)
        val alto = (altoEscena * efectiva).roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(parseColor(scene.backgroundColor))

        // Se reutiliza el renderizador de pantalla con un viewport a medida:
        // encuadrar es solo elegir desplazamiento y zoom.
        Renderer(imageProvider, paraExportar = true).renderScene(
            canvas,
            // Se pinta `contenidoVisible`, no la escena entera: así lo que
            // quedaba fuera de la hoja no aparece, y el marco tampoco se pinta
            // a sí mismo encima del resultado.
            scene.copy(
                elements = visible,
                viewport = Viewport(
                    scrollX = -b.x1 + margen,
                    scrollY = -b.y1 + margen,
                    zoom = efectiva
                )
            ),
            ancho.toDouble(),
            alto.toDouble()
        )
        bitmap
    }.getOrNull()
}
