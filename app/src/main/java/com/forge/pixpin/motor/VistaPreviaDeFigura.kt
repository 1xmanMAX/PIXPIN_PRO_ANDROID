package com.forge.pixpin.motor

/**
 * **Ver una figura antes de usarla** (27-oct-2026, el usuario: tocar una figura abre una vista
 * rápida a pantalla completa, solo para mirar —pellizcar, arrastrar, doble toque para encajarla—
 * con su nombre y su categoría, y «Usar» o «Cerrar»).
 *
 * Hacía falta porque la miniatura de 120 puntos de una plantilla de página es un sello: se ve que
 * es un planificador, no qué casillas tiene. Aquí se ve **tal como va a quedar**: con el mismo
 * [Renderer] que el lienzo, sobre papel de día, y la de noche con su paleta (sus piezas se pintan
 * como se diseñaron, ver [PapelDeLaHoja]).
 *
 * Las cuentas del encuadre son puras ([encajar], [pellizcar], [dobleToque]) y se prueban en la JVM.
 */
object VistaPreviaDeFigura {

    /** El margen alrededor de la figura encajada, en píxeles de pantalla. */
    const val MARGEN = 24.0

    /** Cuánto acerca el doble toque desde la figura encajada. */
    const val AUMENTO_DEL_DOBLE_TOQUE = 2.5

    /** Lo más que se acerca, sobre lo encajado: para leer la letra pequeña de una hoja, y no más. */
    const val AUMENTO_MAXIMO = 12.0

    /** Lo más que se aleja, sobre lo encajado: un poco, para ver que no hay más. */
    const val AUMENTO_MINIMO = 0.5

    /** La figura entera en [ancho] × [alto], centrada y con [MARGEN]. */
    fun encajar(caja: Bounds, ancho: Double, alto: Double): Viewport {
        if (caja.width <= 0 && caja.height <= 0 || ancho <= 0 || alto <= 0) return Viewport()
        val zoom = minOf(
            (ancho - 2 * MARGEN).coerceAtLeast(1.0) / caja.width.coerceAtLeast(1e-6),
            (alto - 2 * MARGEN).coerceAtLeast(1.0) / caja.height.coerceAtLeast(1e-6)
        ).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM)
        return Viewport(
            scrollX = ancho / (2 * zoom) - caja.midX,
            scrollY = alto / (2 * zoom) - caja.midY,
            zoom = zoom
        )
    }

    /**
     * **El gesto de dos dedos**: el punto de la figura que estaba bajo [centro] sigue bajo él
     * movido [dx], [dy], con el aumento por [factor] y entre los topes respecto a [encajada].
     */
    fun pellizcar(v: Viewport, encajada: Viewport, factor: Double, centro: Pt, dx: Double, dy: Double): Viewport {
        val zoom = (v.zoom * factor).coerceIn(encajada.zoom * AUMENTO_MINIMO, encajada.zoom * AUMENTO_MAXIMO)
        val debajo = v.toScene(centro.x, centro.y)
        val destino = Pt(centro.x + dx, centro.y + dy)
        return Viewport(scrollX = destino.x / zoom - debajo.x, scrollY = destino.y / zoom - debajo.y, zoom = zoom)
    }

    /** Si está como recién encajada (o más lejos): entonces el doble toque acerca. */
    fun estaEncajada(v: Viewport, encajada: Viewport): Boolean = v.zoom <= encajada.zoom * 1.05

    /** **El doble toque**: encajada → acerca a [donde]; acercada → vuelve a encajar. */
    fun dobleToque(v: Viewport, encajada: Viewport, donde: Pt): Viewport =
        if (estaEncajada(v, encajada)) pellizcar(encajada, encajada, AUMENTO_DEL_DOBLE_TOQUE, donde, 0.0, 0.0)
        else encajada
}
