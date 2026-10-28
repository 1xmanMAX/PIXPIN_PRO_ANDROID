package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Los trozos del vídeo** (28-oct-2026): dónde va cada uno al juntarlos y cuánto ocupa uno de la cámara. */
class TrozosDeVideoTest {

    @Test
    fun `cada trozo va en su minuto y nunca encima del anterior`() {
        // Tres trozos de 3 min, con unas décimas perdidas en cada cambio: van en su minuto.
        assertEquals(
            listOf(0L, 180_400L, 360_900L),
            TrozosDeVideo.colocar(listOf(5_000, 185_400, 365_900), listOf(180_000, 180_000, 42_000))
        )
        // Si el anterior dura más de lo que dice el reloj (el trozo cerró tarde), justo detrás.
        assertEquals(
            listOf(0L, 181_000L),
            TrozosDeVideo.colocar(listOf(0, 180_000), listOf(181_000, 10_000))
        )
        // Un minuto que va hacia atrás (no debería) no hace retroceder.
        assertEquals(listOf(0L, 1_000L), TrozosDeVideo.colocar(listOf(10_000, 2_000), listOf(1_000, 1_000)))
        // Caso negativo: sin trozos, nada.
        assertTrue(TrozosDeVideo.colocar(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `un trozo de la camara son unos tres minutos de su tasa`() {
        // 5 Mbps × 180 s = 112,5 MB.
        assertEquals(112_500_000L, TrozosDeVideo.bytesDelTrozo(5_000_000))
        // Con una tasa absurda, al menos 8 MB (no se cambia de fichero cada segundo).
        assertEquals(8L * 1024 * 1024, TrozosDeVideo.bytesDelTrozo(1_000))
    }
}
