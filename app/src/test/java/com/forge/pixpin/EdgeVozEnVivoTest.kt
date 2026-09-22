package com.forge.pixpin

import org.robolectric.RuntimeEnvironment
import com.forge.pixpin.ui.VozDeEdge
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **Habla de verdad con Microsoft**: solo con `EDGE_EN_VIVO=1` en el entorno, que las pruebas de
 * siempre no dependan de la red ni de un servicio que no es nuestro. Sirve para ver, cuando falle
 * en el teléfono, si es que Microsoft cambió algo.
 */
@RunWith(RobolectricTestRunner::class)
class EdgeVozEnVivoTest {
    @Test
    fun `microsoft devuelve la lista de voces y el mp3 de una frase`() = runBlocking {
        assumeTrue(System.getenv("EDGE_EN_VIVO") == "1")
        val app = RuntimeEnvironment.getApplication()
        val voces = VozDeEdge.voces(app)
        println("voces: ${voces.size}, en español: ${voces.count { it.idioma.startsWith("es") }}")
        assertTrue(voces.size > 100)
        val f = VozDeEdge.archivo(app, "Hola, esto es PixPin leyendo en voz alta con Microsoft.", "es-ES-ElviraNeural")
        println("mp3: ${f.length()} bytes")
        java.io.File(System.getenv("EDGE_SALIDA") ?: "/dev/null").let { runCatching { f.copyTo(it, overwrite = true) } }
        assertTrue(f.length() > 5_000)
        // Empieza como un MP3 (marco de sincronía 0xFFF…) o con etiqueta ID3.
        val b = f.readBytes()
        assertTrue((b[0].toInt() and 0xFF) == 0xFF || String(b, 0, 3) == "ID3")
    }
}
