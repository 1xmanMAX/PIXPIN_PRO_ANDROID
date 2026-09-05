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
        /**
         * Un encuadre a medida, si se quiere uno concreto.
         *
         * Existe por un caso muy real: una foto anotada con un trazo que se sale de ella.
         * Encuadrando el contenido —lo de siempre— el resultado crece para que quepa el
         * trazo, y en la conversación la foto sale más pequeña y con bandas alrededor,
         * como si se hubiera encogido. Pasando aquí la caja de la propia foto se recorta
         * a lo que hay dentro de ella, que es lo que uno espera ver.
         */
        recorte: Bounds? = null,
        /**
         * La página del PDF que se ve de fondo, si la hay: su píxel (0,0) es
         * el (0,0) de la escena. Sin esto, exportar la anotación de una página
         * sacaba los trazos flotando sobre el vacío. Va ANTES de la lambda por
         * lo mismo que `scale`: la sintaxis de bloque final tiene que seguir
         * cogiendo el `imageProvider`.
         */
        papel: Bitmap? = null,
        imageProvider: (String) -> Bitmap? = { null }
    ): Bitmap? = runCatching {
        val visible = scene.contenidoVisible
        if (visible.isEmpty() && papel == null) return null

        // **Con hoja manda la hoja.** Sin ella, con página manda la página (y
        // lo que se salga de ella); sin ninguna, el encuadre lo da el
        // contenido, que es lo de siempre. Con hoja no se añade margen: el
        // margen ya lo decides tú al colocar el marco.
        val marco = scene.marco
        val b = recorte
            ?: if (marco != null) getElementBounds(marco)
            else if (papel != null) {
                val pagina = Bounds(0.0, 0.0, papel.width.toDouble(), papel.height.toDouble())
                if (visible.isEmpty()) pagina else getCommonBounds(visible).let { c ->
                    Bounds(
                        kotlin.math.min(pagina.x1, c.x1), kotlin.math.min(pagina.y1, c.y1),
                        kotlin.math.max(pagina.x2, c.x2), kotlin.math.max(pagina.y2, c.y2)
                    )
                }
            } else getCommonBounds(visible)
        val margen = if (marco != null || recorte != null || papel != null) 0.0 else EXPORT_PADDING
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

        // **Y con el modo noche puesto si el papel es oscuro.**
        //
        // Aquí se pintaba el papel de la escena —que en modo noche es negro— y en cambio se
        // montaba el renderizador sin avisarle, o sea con la tinta **sin** filtrar. El
        // resultado era negro sobre negro: en la vista de proyectos, una tabla de letras
        // negras sobre una miniatura negra, ilegible, mientras dentro del editor se veía
        // perfectamente. No era la miniatura la que estaba mal, era que el papel y la tinta
        // no se habían puesto de acuerdo.
        //
        // El modo noche **no cambia el dibujo**, es un filtro de pintado (ver [DrawTheme]),
        // así que se decide igual que en el editor: del papel. Con eso, lo que sale de aquí
        // —miniatura, imagen compartida, imagen guardada— se ve como lo que hay en pantalla,
        // que es lo único que se espera de una previsualización.
        val noche = DrawTheme.esDeNoche(scene.backgroundColor)

        // Se reutiliza el renderizador de pantalla con un viewport a medida:
        // encuadrar es solo elegir desplazamiento y zoom.
        Renderer(
            imageProvider, dark = noche, paraExportar = true,
            backdrop = papel, papelALaVista = papel != null
        ).renderScene(
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
