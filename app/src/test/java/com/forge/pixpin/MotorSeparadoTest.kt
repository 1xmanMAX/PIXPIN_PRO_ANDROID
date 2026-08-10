package com.forge.pixpin.motor

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El motor de dibujo está **separado**, y esta prueba lo mantiene así.
 *
 * `com.forge.pixpin.motor` es todo lo relacionado con editar: el modelo de
 * elementos, la escena, la geometría, el trazo a mano alzada, el controlador de
 * gestos, el renderizador y la interfaz del editor. La frontera importa por dos
 * motivos:
 *
 * 1. **El motor no puede depender de la app.** Si un archivo del motor importa
 *    `com.forge.pixpin.pin` o `com.forge.pixpin.capture`, deja de ser un motor y
 *    pasa a ser parte del pin, con lo que ya no se puede usar en la captura ni
 *    sacar a otro sitio.
 * 2. **Su núcleo no puede depender de Android.** Es lo que permite comprobar la
 *    geometría, el trazo y la máquina de estados del dedo sin dispositivo, que
 *    es como se encontró el rizo de las esquinas y como se prueba el lápiz.
 *
 * Se comprueba leyendo los archivos porque no hay otra forma: nada en el
 * lenguaje impide escribir el import.
 */
class MotorSeparadoTest {

    private val motor = File("src/main/java/com/forge/pixpin/motor")

    /**
     * El núcleo, sin Android. Lo que queda fuera es la capa que **tiene** que
     * hablar con el sistema: pintar en un `Canvas`, cargar una fuente, componer
     * la interfaz o guardar un archivo.
     */
    private val conAndroid = setOf(
        "Renderer.kt", "DrawCanvas.kt", "DrawToolbar.kt", "DrawEditorActivity.kt",
        "DrawExport.kt", "DrawPdf.kt", "DrawSvg.kt", "DrawTablas.kt", "DrawFonts.kt",
        "Theme.kt", "ExcalidrawStore.kt",
        // Los dos de escribir un PDF: sacan el perfil de las letras y meten
        // imágenes, y las dos cosas son de Android. La geometría que usan sí es
        // del núcleo —[Caminos], [Rough], [Perimetros]— y por eso el PDF se
        // comprueba casi entero sin dispositivo.
        "Glifos.kt", "PdfLienzo.kt", "PdfDoc.kt", "PdfDelProyecto.kt"
    )

    private fun archivos(): List<File> =
        motor.listFiles { f -> f.extension == "kt" }?.toList().orEmpty()

    @Test
    fun `el motor existe y no está vacío`() {
        assertTrue("no encuentro ${motor.absolutePath}", motor.isDirectory)
        assertTrue("el motor está vacío", archivos().size > 15)
    }

    @Test
    fun `el motor no depende del resto de la aplicación`() {
        val prohibidos = listOf("pixpin.pin", "pixpin.capture", "pixpin.croquis", "pixpin.annotate")
        for (f in archivos()) {
            val texto = f.readText()
            for (p in prohibidos) {
                // `ImageStore` es la excepción reconocida: guardar imágenes es
                // del almacén de la app y duplicarlo no arreglaría nada.
                val lineas = texto.lines().filter { it.contains(p) && !it.contains("ImageStore") }
                assertTrue(
                    "${f.name} depende de $p:\n${lineas.joinToString("\n")}",
                    lineas.isEmpty()
                )
            }
        }
    }

    @Test
    fun `el núcleo del motor no toca Android`() {
        for (f in archivos()) {
            if (f.name in conAndroid) continue
            val lineas = f.readText().lines().filter {
                it.trimStart().startsWith("import android.") ||
                    it.trimStart().startsWith("import androidx.")
            }
            assertTrue(
                "${f.name} es del núcleo y toca Android; " +
                    "o se saca esa parte, o el archivo pasa a la capa de arriba:\n" +
                    lineas.joinToString("\n"),
                lineas.isEmpty()
            )
        }
    }

    /** Lo que hay en la lista de «con Android» tiene que existir de verdad. */
    @Test
    fun `la lista de la capa de Android está al día`() {
        val nombres = archivos().map { it.name }.toSet()
        val fantasmas = conAndroid - nombres
        assertTrue("archivos que ya no existen: $fantasmas", fantasmas.isEmpty())
    }
}
