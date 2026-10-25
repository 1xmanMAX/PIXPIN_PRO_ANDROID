package com.forge.pixpin.motor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **Los mandos de las láminas en el lateral** (25-oct-2026), los del panel de propiedades del PC
 * (`pixpin-ui/src/propiedades.rs`): «Cierre del arco», «Relleno degradado», «Arquitos», qué pone
 * la herramienta Ángulo, la letra de una marca y el festón de la nube. Cada uno sale solo si su
 * [Propiedad] aplica ([conLasDeLamina]) y se toca como todos: se posa el dedo y se baja.
 */
@Composable
fun LosMandosDeLaminas(
    aplican: Set<Propiedad>,
    estilo: ItemStyle,
    onEstilo: (ItemStyle) -> Unit,
    tinta: Color,
    tintaDelFondo: Color,
    haciaLaIzquierda: Boolean,
    bola: Dp
) {
    if (Propiedad.CIERRE_ARCO in aplican) {
        val cierres = listOf<String?>(null, "sector", "segmento")
        SelectorArrastrable(
            opciones = cierres,
            // Un cierre de una versión futura se enseña como abierto y se deja hasta que se elija otro.
            actual = cierres.indexOf(estilo.cierreArco).coerceAtLeast(0),
            haciaLaIzquierda = haciaLaIzquierda,
            bola = bola,
            descripcion = "Cierre del arco",
            tinta = tinta,
            onElegir = { onEstilo(estilo.copy(cierreArco = cierres[it])) }
        ) { cierre, t -> Canvas(Modifier.size(20.dp)) { dibujarCierre(cierre, t) } }
    }

    // El degradado va del color de fondo a uno más claro: sin fondo no hay de dónde salir.
    if (Propiedad.DEGRADADO in aplican && !isTransparent(estilo.backgroundColor)) {
        val tipos = listOf<String?>(null, "lineal", "radial")
        SelectorArrastrable(
            opciones = tipos,
            actual = tipos.indexOf(estilo.degradado).coerceAtLeast(0),
            haciaLaIzquierda = haciaLaIzquierda,
            bola = bola,
            descripcion = "Relleno degradado",
            tinta = tintaDelFondo,
            onElegir = { onEstilo(estilo.copy(degradado = tipos[it])) }
        ) { tipo, t -> Canvas(Modifier.size(20.dp)) { dibujarDegradado(tipo, t) } }
    }

    if (Propiedad.MODO_ANGULO in aplican) {
        val modos = Laminas.ModoAngulo.entries
        SelectorArrastrable(
            opciones = modos,
            actual = modos.indexOf(Laminas.ModoAngulo.desdePalabra(estilo.modoAngulo)).coerceAtLeast(0),
            haciaLaIzquierda = haciaLaIzquierda,
            bola = bola,
            descripcion = "Qué pone el ángulo",
            tinta = tinta,
            onElegir = { onEstilo(estilo.copy(modoAngulo = modos[it].palabra)) }
        ) { modo, t ->
            when (modo) {
                Laminas.ModoAngulo.GRIEGA -> Text("α", color = t, fontSize = 15.sp)
                Laminas.ModoAngulo.SIMPLE -> Canvas(Modifier.size(20.dp)) { dibujarArquitos(1, t) }
                Laminas.ModoAngulo.RECTO -> Canvas(Modifier.size(20.dp)) { dibujarRecto(t) }
            }
        }
    }

    if (Propiedad.ANGULO in aplican) {
        val arcos = listOf(1, 2, 3)
        SelectorArrastrable(
            opciones = arcos,
            actual = arcos.indexOf(estilo.arcosAngulo).coerceAtLeast(0),
            haciaLaIzquierda = haciaLaIzquierda,
            bola = bola,
            descripcion = "Arquitos",
            tinta = tinta,
            onElegir = { onEstilo(estilo.copy(arcosAngulo = arcos[it])) }
        ) { n, t -> Canvas(Modifier.size(20.dp)) { dibujarArquitos(n, t) } }
    }

    // **La letra de la marca elegida**, recorriendo la serie del PC (α, β, θ, γ, ω…): es lo que
    // allí hacen la rueda y las flechas. Sin letra también se puede.
    if (Propiedad.LETRA_ANGULO in aplican) {
        val letras = listOf<String?>(null) + Laminas.LETRAS_DE_ANGULO +
            listOfNotNull(estilo.letraAngulo?.takeIf { it !in Laminas.LETRAS_DE_ANGULO })
        SelectorArrastrable(
            opciones = letras,
            actual = letras.indexOf(estilo.letraAngulo).coerceAtLeast(0),
            haciaLaIzquierda = haciaLaIzquierda,
            bola = bola,
            descripcion = "Letra del ángulo",
            tinta = tinta,
            onElegir = { onEstilo(estilo.copy(letraAngulo = letras[it])) }
        ) { letra, t -> Text(letra ?: "–", color = t, fontSize = 15.sp) }
    }

    if (Propiedad.NUBE in aplican) {
        val radios = NubeDeRevision.RADIOS_DEL_PANEL
        val masCerca = radios.indices.minByOrNull { kotlin.math.abs(radios[it] - estilo.nubeRadio) } ?: 2
        SelectorArrastrable(
            opciones = radios,
            actual = masCerca,
            haciaLaIzquierda = haciaLaIzquierda,
            bola = bola,
            descripcion = "Festón de la nube",
            tinta = tinta,
            onElegir = { onEstilo(estilo.copy(nubeRadio = radios[it])) }
        ) { r, t -> Canvas(Modifier.size(20.dp)) { dibujarFestones(r, t) } }
        val bases = NubeDeRevision.Base.entries
        SelectorArrastrable(
            opciones = bases,
            actual = bases.indexOf(NubeDeRevision.Base.desdePalabra(estilo.nubeBase)).coerceAtLeast(0),
            haciaLaIzquierda = haciaLaIzquierda,
            bola = bola,
            descripcion = "Forma de la nube",
            tinta = tinta,
            onElegir = { onEstilo(estilo.copy(nubeBase = bases[it].palabra)) }
        ) { base, t ->
            Canvas(Modifier.size(20.dp)) {
                val m = 3.dp.toPx()
                if (base == NubeDeRevision.Base.ELIPSE) {
                    drawOval(t, Offset(m, m * 2), Size(size.width - 2 * m, size.height - 4 * m), style = Stroke(1.5.dp.toPx()))
                } else {
                    drawRect(t, Offset(m, m * 2), Size(size.width - 2 * m, size.height - 4 * m), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

/** La muestra del raya-punto: raya, hueco, punto, hueco. */
fun DrawScope.dibujarRayaPunto(tinta: Color) {
    val y = size.height / 2
    val g = 2.dp.toPx()
    val raya = size.width * 0.32f
    val hueco = size.width * 0.12f
    var x = 0f
    while (x < size.width) {
        drawLine(tinta, Offset(x, y), Offset(minOf(x + raya, size.width), y), strokeWidth = g)
        x += raya + hueco
        if (x < size.width) drawCircle(tinta, radius = g / 2, center = Offset(x, y))
        x += hueco + g
    }
}

private fun DrawScope.dibujarCierre(cierre: String?, t: Color) {
    val c = Offset(size.width * 0.2f, size.height * 0.8f)
    val r = size.width * 0.7f
    val arco = Path().apply {
        arcTo(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r), -90f, 90f, true)
    }
    val trazo = Stroke(1.5.dp.toPx())
    when (cierre) {
        "sector" -> {
            val p = Path().apply { moveTo(c.x, c.y); lineTo(c.x, c.y - r); addPath(arco); lineTo(c.x, c.y); close() }
            drawPath(p, t.copy(alpha = 0.3f))
            drawPath(p, t, style = trazo)
        }
        "segmento" -> {
            val p = Path().apply { addPath(arco); lineTo(c.x, c.y - r); close() }
            drawPath(p, t.copy(alpha = 0.3f))
            drawPath(p, t, style = trazo)
        }
        else -> drawPath(arco, t, style = trazo)
    }
}

private fun DrawScope.dibujarDegradado(tipo: String?, t: Color) {
    val claro = Color(
        red = t.red + (1 - t.red) * 0.8f, green = t.green + (1 - t.green) * 0.8f,
        blue = t.blue + (1 - t.blue) * 0.8f, alpha = t.alpha
    )
    val pincel = when (tipo) {
        "lineal" -> Brush.horizontalGradient(listOf(t, claro))
        "radial" -> Brush.radialGradient(listOf(t, claro), center = center, radius = size.minDimension * 0.7f)
        else -> Brush.linearGradient(listOf(t, t))
    }
    drawRoundRect(pincel, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
}

private fun DrawScope.dibujarArquitos(n: Int, t: Color) {
    val v = Offset(size.width * 0.12f, size.height * 0.85f)
    drawLine(t, v, Offset(size.width, v.y), strokeWidth = 1.2.dp.toPx())
    drawLine(t, v, Offset(size.width * 0.75f, size.height * 0.1f), strokeWidth = 1.2.dp.toPx())
    for (k in 0 until n) {
        val r = size.width * (0.5f - k * 0.12f)
        drawArc(
            t, startAngle = -50f, sweepAngle = 50f, useCenter = false,
            topLeft = Offset(v.x - r, v.y - r), size = Size(2 * r, 2 * r), style = Stroke(1.2.dp.toPx())
        )
    }
}

private fun DrawScope.dibujarRecto(t: Color) {
    val v = Offset(size.width * 0.15f, size.height * 0.85f)
    val l = size.width * 0.35f
    drawLine(t, v, Offset(size.width, v.y), strokeWidth = 1.2.dp.toPx())
    drawLine(t, v, Offset(v.x, 0f), strokeWidth = 1.2.dp.toPx())
    val p = Path().apply { moveTo(v.x + l, v.y); lineTo(v.x + l, v.y - l); lineTo(v.x, v.y - l) }
    drawPath(p, t, style = Stroke(1.2.dp.toPx()))
}

/** Unos festones del tamaño de [r], a escala de la muestra: más grandes, menos caben. */
private fun DrawScope.dibujarFestones(r: Double, t: Color) {
    val n = (6 - (r / 6).toInt()).coerceIn(2, 5)
    val d = size.width / n
    for (i in 0 until n) {
        drawArc(
            t, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(i * d, size.height / 2 - d / 2), size = Size(d, d), style = Stroke(1.5.dp.toPx())
        )
    }
}
