package com.forge.pixpin.croquis3d

import kotlin.math.atan

/**
 * **Cuánto abarca en vertical, en la pantalla, la cámara del aparato.**
 *
 * Es la cuenta que hace que el croquis puesto en el sitio gire al mismo ritmo que la
 * habitación que se ve detrás: la lente del croquis tiene que ser la de la cámara, y la de
 * la cámara no es un número del aparato sino de **lo que llega a la pantalla**, que es un
 * recorte de un recorte:
 *
 * 1. El sensor mide lo que mide (en milímetros) y la lente tiene su focal; de ahí sale lo
 *    que el sensor entero abarca. Solo cuenta la parte activa del sensor.
 * 2. El flujo de vista previa tiene su proporción —cuatro tercios, dieciséis novenos— y
 *    cuando no es la del sensor, el aparato recorta el sensor por el centro para dársela.
 * 3. El sensor va apaisado y la pantalla como esté puesta: en vertical, el lado largo del
 *    sensor es el alto de la pantalla.
 * 4. Y la vista previa **rellena** la pantalla recortando lo que sobra por los lados o por
 *    arriba y abajo, según cuál de las dos proporciones sea más alargada.
 *
 * Sin Android a propósito: entran números y sale un ángulo, y se comprueba sin aparato.
 */
object LenteDelAparato {

    /**
     * El campo vertical que se ve, en radianes, o null si algún dato no vale.
     *
     * @param sensorMm lo que mide el sensor entero (su matriz de píxeles), ancho y alto en
     *   milímetros, tal como lo declara el aparato: apaisado.
     * @param pixeles cuántos píxeles tiene esa matriz entera, ancho y alto.
     * @param activo cuántos de esos píxeles se usan de verdad, ancho y alto.
     * @param focalMm la focal de la lente, en milímetros.
     * @param flujo la resolución de la vista previa, ancho y alto, en la orientación del
     *   sensor.
     * @param giroDelSensor cuántos grados hay que girar la imagen del sensor para que salga
     *   derecha en la pantalla: 0, 90, 180 o 270.
     * @param pantalla lo que mide la vista en píxeles, ancho y alto.
     */
    fun campoVertical(
        sensorMm: Pair<Double, Double>,
        pixeles: Pair<Int, Int>,
        activo: Pair<Int, Int>,
        focalMm: Double,
        flujo: Pair<Int, Int>,
        giroDelSensor: Int,
        pantalla: Pair<Int, Int>
    ): Double? {
        if (sensorMm.first <= 0.0 || sensorMm.second <= 0.0) return null
        if (pixeles.first <= 0 || pixeles.second <= 0) return null
        if (activo.first <= 0 || activo.second <= 0) return null
        if (focalMm <= 0.0) return null
        if (flujo.first <= 0 || flujo.second <= 0) return null
        if (pantalla.first <= 0 || pantalla.second <= 0) return null

        // 1. La parte activa del sensor, en milímetros.
        val activoAncho = sensorMm.first * activo.first / pixeles.first
        val activoAlto = sensorMm.second * activo.second / pixeles.second

        // 2. Recortada por el centro a la proporción del flujo.
        val proporcionDelFlujo = flujo.first.toDouble() / flujo.second
        val proporcionDelSensor = activoAncho / activoAlto
        val vistoAncho: Double
        val vistoAlto: Double
        if (proporcionDelFlujo >= proporcionDelSensor) {
            vistoAncho = activoAncho
            vistoAlto = activoAncho / proporcionDelFlujo
        } else {
            vistoAlto = activoAlto
            vistoAncho = activoAlto * proporcionDelFlujo
        }

        // 3. Girada como la pantalla.
        val deLado = ((giroDelSensor % 360) + 360) % 360 == 90 ||
            ((giroDelSensor % 360) + 360) % 360 == 270
        val previaAncho = if (deLado) vistoAlto else vistoAncho
        val previaAlto = if (deLado) vistoAncho else vistoAlto

        // 4. Rellenando la pantalla: se ve entero el lado que manda y el otro se recorta.
        val proporcionDePantalla = pantalla.first.toDouble() / pantalla.second
        val proporcionDeLaPrevia = previaAncho / previaAlto
        val verticalVisto =
            if (proporcionDePantalla <= proporcionDeLaPrevia) previaAlto
            else previaAncho / proporcionDePantalla

        val campo = 2.0 * atan(verticalVisto / (2.0 * focalMm))
        return campo.takeIf { it.isFinite() && it > 0.0 }
    }
}
