package com.forge.pixpin

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El motor MD es **suyo**: se puede sacar del proyecto y seguir funcionando.
 *
 * Es la misma frontera que vigila `MotorSeparadoTest` para el motor de dibujo, y
 * por el mismo motivo: lo que interpreta y compone Markdown no tiene por qué
 * saber que existe un pin flotante, una captura de pantalla ni un proyecto.
 * Mientras eso se cumpla, casi todo se comprueba en la JVM —y de hecho así es:
 * el grueso de las pruebas de esta app son de aquí— y el día que haga falta
 * usarlo en otro sitio se lleva la carpeta y ya.
 *
 * La regla no es «sin Android»: es **sin Android donde no hace falta**. Pintar
 * una nota es Android por definición, así que los archivos que pintan van
 * declarados aquí abajo y el resto tiene que estar limpio.
 */
class MotorMdSeparadoTest {

    private val motorMd = File("src/main/java/com/forge/pixpin/motormd")

    /**
     * Los que sí pueden tocar Android, y por qué.
     *
     * Se enumeran a mano a propósito: añadir uno obliga a pararse a pensar si de
     * verdad hace falta, que es justo la pregunta que evita que la frontera se
     * deshaga sola.
     */
    private val conAndroid = setOf(
        // Todo lo que pinta o escucha el dedo.
        "MarkdownText.kt", "EditorVivo.kt", "FormulaUi.kt", "RejillaDeTabla.kt",
        "BarraDeFormatoUi.kt",
        // El almacén y los adjuntos: archivos y contexto.
        "TextoStore.kt", "Adjuntos.kt",
        // Lleva la posición del cursor, que es un tipo de Compose.
        "Vivo.kt"
    )

    private fun archivos(): List<File> =
        motorMd.listFiles { f -> f.extension == "kt" }?.toList().orEmpty()

    @Test
    fun `el motor MD existe y tiene sustancia`() {
        assertTrue("no encuentro ${motorMd.absolutePath}", motorMd.isDirectory)
        assertTrue("está casi vacío", archivos().size >= 15)
    }

    /**
     * Lo que de verdad lo hace un motor: no sabe nada del resto de la app.
     *
     * Ni del pin flotante, ni de la captura, ni de los proyectos. Si alguna vez
     * necesita algo de ahí, se le pasa como parámetro —así es como recibe las
     * escenas, las imágenes y el azar el resto del proyecto.
     */
    @Test
    fun `el motor MD no depende del resto de la aplicación`() {
        val prohibidos = listOf(
            "pixpin.pin", "pixpin.capture", "pixpin.croquis",
            "pixpin.annotate", "pixpin.clipboard", "pixpin.data", "pixpin.ui"
        )
        for (f in archivos()) {
            val texto = f.readText()
            for (p in prohibidos) {
                assertTrue(
                    "${f.name} importa $p: el motor MD tiene que valerse solo",
                    !texto.contains("import com.forge.$p")
                )
            }
        }
    }

    /** Y tampoco del motor de dibujo: son dos motores, no uno con dos mitades. */
    @Test
    fun `el motor MD no depende del motor de dibujo`() {
        for (f in archivos()) {
            assertTrue(
                "${f.name} importa el motor de dibujo",
                !f.readText().contains("import com.forge.pixpin.motor.")
            )
        }
    }

    @Test
    fun `solo pintan Android los que tienen que pintar`() {
        for (f in archivos()) {
            if (f.name in conAndroid) continue
            val lineas = f.readText().lines().filter {
                it.trimStart().startsWith("import android.") ||
                    it.trimStart().startsWith("import androidx.")
            }
            assertTrue(
                "${f.name} toca Android sin estar declarado: $lineas",
                lineas.isEmpty()
            )
        }
    }

    /** Nadie declarado de más: una lista que sobra deja de vigilar. */
    @Test
    fun `la lista de los que tocan Android no tiene sobrantes`() {
        val nombres = archivos().map { it.name }.toSet()
        val fantasmas = conAndroid - nombres
        assertTrue("declarados y ya no existen: $fantasmas", fantasmas.isEmpty())
    }
}
