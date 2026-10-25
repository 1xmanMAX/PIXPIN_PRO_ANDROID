package com.forge.pixpin.motor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope

/** El rojo del láser de Excalidraw (el del PC: 1, 0,1, 0,1 al 90 %). */
private val COLOR_DEL_LASER = Color(red = 1f, green = 0.1f, blue = 0.1f, alpha = 0.9f)

/**
 * **Pinta la estela** del láser sobre el lienzo, en píxeles de pantalla: el grueso no crece con el
 * zoom, es una señal para quien mira y no parte del dibujo. [aPantalla] pasa de escena a pantalla.
 */
fun DrawScope.pintarElLaser(laser: PunteroLaser, ms: Long, aPantalla: (Pt) -> Offset) {
    val ancho = PunteroLaser.ANCHO_DP * density
    for (t in laser.tramos(ms)) {
        drawLine(
            COLOR_DEL_LASER, aPantalla(t.a), aPantalla(t.b),
            strokeWidth = (ancho * t.grueso).toFloat(), cap = StrokeCap.Round
        )
    }
    laser.punta()?.let { drawCircle(COLOR_DEL_LASER, radius = ancho * 0.8f, center = aPantalla(it)) }
}
