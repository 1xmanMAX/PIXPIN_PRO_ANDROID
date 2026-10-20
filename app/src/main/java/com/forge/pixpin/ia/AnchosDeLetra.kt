package com.forge.pixpin.ia

/**
 * **Lo que mide cada letra sin pantalla** (PC `pixpin-motor2d/src/exportar.rs`, `ANCHOS_SEGOE_UI`): las
 * milésimas de em de Segoe UI, con las que el PC mide los textos de la pizarra cuando no hay DirectWrite
 * (sus pruebas y su banco). Aquí, para las pruebas que comparan con el PC; en el teléfono se mide con la
 * letra de verdad.
 */
object AnchosDeLetra {
    private val ASCII = intArrayOf(
        274, 284, 392, 591, 539, 818, 800, 230, 302, 302, 417, 684, 217, 400, 217, 390,
        539, 539, 539, 539, 539, 539, 539, 539, 539, 539, 217, 217, 684, 684, 684, 448,
        955, 645, 573, 619, 701, 506, 488, 686, 710, 266, 357, 580, 471, 898, 748, 754,
        560, 754, 598, 531, 524, 687, 621, 934, 590, 553, 570, 302, 379, 302, 684, 415,
        268, 509, 588, 462, 589, 523, 313, 589, 566, 242, 242, 497, 242, 861, 566, 586,
        588, 589, 348, 424, 339, 566, 479, 723, 459, 484, 452, 302, 239, 302, 684
    )
    private val LATIN1 = intArrayOf(
        274, 284, 539, 539, 556, 539, 239, 448, 414, 890, 392, 506, 684, 400, 890, 415,
        377, 684, 366, 366, 282, 577, 458, 217, 205, 351, 431, 506, 906, 931, 952, 448,
        645, 645, 645, 645, 645, 645, 860, 619, 506, 506, 506, 506, 266, 266, 266, 266,
        701, 748, 754, 754, 754, 754, 754, 684, 754, 687, 687, 687, 687, 553, 560, 544,
        509, 509, 509, 509, 509, 509, 832, 462, 523, 523, 523, 523, 242, 242, 242, 242,
        559, 566, 586, 586, 586, 586, 586, 684, 586, 566, 566, 566, 566, 484, 588, 484
    )

    fun deCaracter(c: Int): Int = when (c) {
        in 32..126 -> ASCII[c - 32]
        in 160..255 -> LATIN1[c - 160]
        else -> 566
    }

    /** Lo que mide [texto] a tamaño [tam] (PC `ancho_de_texto`): por puntos de código, como `chars()` de Rust. */
    fun ancho(texto: String, tam: Double): Double {
        var s = 0.0f
        texto.codePoints().forEach { s += deCaracter(it).toFloat() }
        return (s * tam.toFloat() / 1000.0f).toDouble()
    }

    /** La medida de la pizarra del PC sin DirectWrite (`medir_con`): el renglón más ancho y 1,25 de alto por renglón. */
    val MEDIDA: com.forge.pixpin.motor.MedidaDeTexto = { t, tam ->
        val renglones = t.split('\n')
        renglones.maxOf { ancho(it, tam) } to renglones.size * tam * 1.25
    }
}
