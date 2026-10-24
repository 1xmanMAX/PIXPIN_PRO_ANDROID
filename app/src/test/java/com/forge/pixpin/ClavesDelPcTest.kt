package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que el lienzo del PC escribe y el teléfono aún no pinta (texto rico, sectores, marcas de
 * ángulo, degradados, raya-punto) **vuelve tal cual** al guardar aquí.
 */
class ClavesDelPcTest {
    private val delPc = """{"id":"a","seed":1,"type":"pixpin-arc","x":0,"y":0,"width":10,"height":10,"arcStart":0,"arcSweep":1.57,
        "cierreArco":"espiral","marcaAngulo":{"arcos":2,"recto":false,"valor":"45°"},
        "degradado":{"tipo":"lineal","desde":"#ff0000","hasta":"#ffffff","origen":[0,0],"destino":[1,1]},
        "strokeStyle":"dashed","trazoRayaPunto":true}"""

    @Test fun lasClavesDeGeometriaVuelvenIntactas() {
        val e = ExcalidrawJson.decodeFromString(Element.serializer(), delPc)
        assertEquals("espiral", e.cierreArco)
        val vuelta = ExcalidrawJson.encodeToString(Element.serializer(), e.copy(x = 5.0))
        assertTrue(vuelta.contains("\"cierreArco\":\"espiral\""))
        assertTrue(vuelta.contains("\"valor\":\"45°\""))
        assertTrue(vuelta.contains("\"degradado\""))
        assertTrue(vuelta.contains("\"trazoRayaPunto\":true"))
    }

    @Test fun elTextoRicoVuelveYSinElNoSeEscribeNada() {
        val t = ExcalidrawJson.decodeFromString(
            Element.serializer(),
            """{"id":"t","seed":2,"type":"text","x":0,"y":0,"width":10,"height":10,"text":"Nos piden X","textoRico":"Nos piden ${'$'}\\color{rojo}{X}${'$'}"}"""
        )
        assertTrue(ExcalidrawJson.encodeToString(Element.serializer(), t).contains("textoRico"))
        val sin = ExcalidrawJson.encodeToString(Element.serializer(), t.copy(textoRico = null))
        assertFalse(sin.contains("textoRico"))
        assertFalse(sin.contains("cierreArco"))
    }
}
