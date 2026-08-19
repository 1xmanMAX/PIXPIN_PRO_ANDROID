package com.forge.pixpin.motor

/**
 * Los formatos de papel, para **mirar el lienzo a la escala de una hoja**.
 *
 * El lienzo es infinito y eso está bien para trabajar, pero lo que se entrega
 * casi siempre es una hoja: un plano en A3, un apunte en A5. Poner el zoom «a
 * A3» es ver el dibujo del tamaño al que va a salir impreso, que es la única
 * forma de saber si el texto se va a leer o si la línea fina se va a perder.
 *
 * Las medidas van en puntos de PDF (1/72 de pulgada), que es lo que usa el
 * exportador: así lo que se ve encaja con lo que se guarda sin más cuentas.
 */
enum class Papel(val etiqueta: String, val ancho: Double, val alto: Double) {
    A5("A5", 420.0, 595.0),
    A4("A4", 595.0, 842.0),
    A3("A3", 842.0, 1191.0),
    A2("A2", 1191.0, 1684.0),
    A1("A1", 1684.0, 2384.0);

    /**
     * A qué zoom hay que ponerse para que esta hoja quepa en la pantalla.
     *
     * Con un margen alrededor: pegada a los bordes no se ve dónde acaba la hoja,
     * y lo que se está mirando es precisamente si algo se sale de ella.
     */
    fun zoomPara(anchoPantalla: Double, altoPantalla: Double, margen: Double = 0.9): Double {
        if (ancho <= 0 || alto <= 0) return 1.0
        return minOf(anchoPantalla / ancho, altoPantalla / alto) * margen
    }
}
