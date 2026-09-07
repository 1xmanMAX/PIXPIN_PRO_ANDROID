package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.AudioLigero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Lo que se anulaba al girar**, y la pregunta del audio que salía siempre.
 *
 * Un contorno proyectado que se cruza consigo mismo se **descuenta** con la regla de relleno
 * de siempre: donde la tinta se superponía a la tinta quedaba un agujero, y solo al girar la
 * vista, que es cuando un trazo se proyecta encima de sí mismo. Aquí se fija lo que decide
 * el reparto: saber si la tira se dobla.
 */
class TintaQueSeSuperponeTest {

    /** Los cuatro vértices de un cuadro, como los guarda el borrador: dos filas de n. */
    private fun tira(vararg puntos: Double): DoubleArray = puntos

    @Test
    fun `una tira que no se dobla se pinta de una pieza`() {
        // Dos muestras, riel de arriba y riel de abajo: un rectángulo y el siguiente.
        // fila j = 0: (0,0) (10,0) (20,0);  fila j2 = 1: (0,5) (10,5) (20,5)
        val p = tira(0.0, 0.0, 10.0, 0.0, 20.0, 0.0, 0.0, 5.0, 10.0, 5.0, 20.0, 5.0)
        assertFalse(seDobla(p, j = 0, j2 = 1, desde = 0, hasta = 2, n = 3))
    }

    @Test
    fun `una tira que se vuelve sobre si misma se parte en cuadros`() {
        // El trazo avanza y **se vuelve**: el segundo cuadro va al revés que el primero.
        // fila 0: (0,0) (10,0) (0,0);  fila 1: (0,5) (10,5) (0,5)
        val p = tira(0.0, 0.0, 10.0, 0.0, 0.0, 0.1, 0.0, 5.0, 10.0, 5.0, 0.0, 5.1)
        assertTrue("una tira doblada tiene que partirse", seDobla(p, 0, 1, 0, 2, 3))
    }

    /** El signo del área es lo que dice el sentido, y cambiarlo de orden lo invierte. */
    @Test
    fun `el sentido de un cuadro se lee en el signo de su area`() {
        val p = tira(0.0, 0.0, 10.0, 0.0, 10.0, 5.0, 0.0, 5.0)
        val alDerecho = areaDelCuadro(p, 0, 2, 4, 6)
        val alReves = areaDelCuadro(p, 6, 4, 2, 0)
        assertTrue(alDerecho * alReves < 0.0)
        assertEquals(kotlin.math.abs(alDerecho), kotlin.math.abs(alReves), 1e-9)
    }

    /**
     * **La pregunta del audio solo si hay audio.** Salía siempre, también en un documento
     * que no tiene ni una nota de voz.
     */
    @Test
    fun `se sabe si una nota lleva audio dentro`() {
        assertTrue(AudioLigero.hayAudioEn("# Nota\n\n![audio](voz-123.m4a)\n\ntexto"))
        assertTrue(AudioLigero.hayAudioEn("![](a/b/c.OGG)"))
        assertFalse(AudioLigero.hayAudioEn("# Nota\n\n![foto](obra.jpg)\n\ny nada más"))
        assertFalse(AudioLigero.hayAudioEn("solo texto, sin medios"))
        assertFalse(AudioLigero.hayAudioEn(null))
        assertFalse(AudioLigero.hayAudioEn(""))
        // Un enlace normal no es un medio: `[texto](x.mp3)` sin la admiración no incrusta nada.
        assertFalse(AudioLigero.hayAudioEn("[una canción](cancion.mp3)"))
    }

    /**
     * La lista de extensiones tiene que ser **la misma** que la que usa el Markdown para
     * llamar audio a un medio; si allí se añade una y aquí no, la pregunta desaparecería
     * justo en las notas que sí lo llevan.
     */
    @Test
    fun `las extensiones de audio son las mismas que las del Markdown`() {
        for (ext in AudioLigero.EXTENSIONES_DE_AUDIO) {
            val bloques = com.forge.pixpin.motormd.Markdown.parse("![audio](x.$ext)")
            val medio = bloques.filterIsInstance<com.forge.pixpin.motormd.MarkdownBlock.Medio>().single()
            assertEquals(
                "«$ext» tiene que ser audio también para el Markdown",
                com.forge.pixpin.motormd.ClaseDeMedio.AUDIO, medio.clase
            )
        }
    }
}
