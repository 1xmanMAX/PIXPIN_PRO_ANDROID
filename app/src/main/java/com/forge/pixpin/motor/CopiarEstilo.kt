package com.forge.pixpin.motor

/**
 * **El estilo tomado de una figura, listo para pegarlo en otras** (25-oct-2026, el cuentagotas de
 * estilo del PC: `estilo.rs`, `EstiloCopiado`, y la herramienta `CopiarEstilo` de `mano.rs`).
 *
 * Con el dedo, «ponle a esta caja lo mismo que a aquella» eran seis mandos del panel, uno por uno,
 * mirando cuál estaba puesto. Con esto son dos toques: el modelo y el destino, y se sigue tocando
 * destinos sin volver a la barra.
 *
 * Guarda el estilo **entero** ([estiloDe]) y de qué tipo venía; lo que se escribe al pegar es el
 * cruce de las dos tablas ([propiedadesDe] del origen y del destino), la misma regla del PC: de un
 * rectángulo a un texto pasa el color y no el rayado, y de un texto a un rectángulo no se le borra
 * el fondo que ya tenía. Y solo lo de [QUE_SE_COPIA], las del PC: el tamaño de la letra, la punta
 * de la flecha o el aumento de una lupa no son «estilo».
 */
data class EstiloCopiado(val estilo: ItemStyle, val origen: Set<Propiedad>) {

    /**
     * [destino] con el estilo puesto en lo que admiten los dos, o el mismo objeto si no cambia
     * nada (para no ensuciar el historial, como el PC).
     */
    fun pegarEn(destino: Element): Element {
        if (destino.locked || destino.isDeleted) return destino
        val comunes = propiedadesDe(destino) intersect origen intersect QUE_SE_COPIA
        if (comunes.isEmpty()) return destino
        val propio = estiloDe(destino)
        var s = propio
        if (Propiedad.TRAZO in comunes) s = s.copy(strokeColor = estilo.strokeColor)
        if (Propiedad.FONDO in comunes) s = s.copy(backgroundColor = estilo.backgroundColor, degradado = estilo.degradado)
        if (Propiedad.RELLENO in comunes) s = s.copy(fillStyle = estilo.fillStyle)
        if (Propiedad.LINEA in comunes) s = s.copy(strokeStyle = estilo.strokeStyle, rayaPunto = estilo.rayaPunto)
        // El grosor viaja como el número del mando, no como el del elemento: el lápiz guarda la
        // mitad ([ItemStyle.freedrawWidthFor]) y así sale en el mismo escalón y no en otro.
        if (Propiedad.GROSOR in comunes) s = s.copy(strokeWidth = estilo.strokeWidth)
        if (Propiedad.RUGOSIDAD in comunes) s = s.copy(roughness = estilo.roughness)
        if (Propiedad.ESQUINAS in comunes) s = s.copy(roundness = estilo.roundness)
        if (Propiedad.MATERIAL in comunes) s = s.copy(material = estilo.material, dureza = estilo.dureza)
        if (Propiedad.OPACIDAD in comunes) s = s.copy(opacity = estilo.opacity)
        if (s == propio) return destino
        var out = conEstilo(destino, s)
        // La dureza es del grafito: lo que deja de serlo no se queda con una que ya no significa nada.
        if (Propiedad.MATERIAL in comunes && out.material != MaterialDeTinta.CUADRITOS) out = out.copy(dureza = null)
        return if (out == destino) destino else out.touched()
    }

    companion object {
        /** Lo que se lleva el cuentagotas: lo del PC (`Propiedad` de `estilo.rs`). */
        val QUE_SE_COPIA = setOf(
            Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO, Propiedad.LINEA, Propiedad.GROSOR,
            Propiedad.RUGOSIDAD, Propiedad.ESQUINAS, Propiedad.MATERIAL, Propiedad.OPACIDAD
        )

        /** **Copia el estilo** de [e]. */
        fun de(e: Element): EstiloCopiado = EstiloCopiado(estiloDe(e), propiedadesDe(e))
    }
}
