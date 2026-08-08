package com.forge.pixpin

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Todo lo que declara el manifiesto **existe de verdad**.
 *
 * Esta prueba nace de un fallo concreto: al mover el motor de dibujo de
 * `com.forge.pixpin.draw` a `com.forge.pixpin.motor` se reescribieron los
 * imports de los `.kt` y se dejó atrás el `AndroidManifest.xml`. Kotlin
 * compilaba, las pruebas pasaban y el APK se generaba sin una sola queja —
 * porque el manifiesto es **texto**, y nada en la cadena de compilación
 * comprueba que ese texto apunte a una clase que exista—. La aplicación
 * reventaba al abrir el editor, con un `ActivityNotFoundException`.
 *
 * Se comprueban las clases contra los archivos del árbol de fuentes y no por
 * reflexión: en una prueba de JVM la clase de una `Activity` no se puede cargar
 * sin arrastrar medio Android detrás.
 */
class ManifiestoTest {

    private val manifiesto = File("src/main/AndroidManifest.xml")
    private val fuentes = File("src/main/java")

    /** El paquete de la aplicación, que es lo que sustituye al punto inicial. */
    private val paquete = "com.forge.pixpin"

    /** Los `android:name` que nombran una clase nuestra, con su número de línea. */
    private fun declaraciones(): List<Pair<Int, String>> {
        val re = Regex("""android:name="(\.[^"]+)"""")
        return manifiesto.readLines().mapIndexedNotNull { i, linea ->
            re.find(linea)?.groupValues?.get(1)?.let { (i + 1) to it }
        }
    }

    @Test
    fun `el manifiesto existe y declara componentes`() {
        assertTrue("no encuentro ${manifiesto.absolutePath}", manifiesto.isFile)
        assertTrue("ninguna clase declarada", declaraciones().size > 5)
    }

    @Test
    fun `cada clase del manifiesto existe en las fuentes`() {
        val ausentes = declaraciones().filter { (_, nombre) ->
            val completo = paquete + nombre
            val ruta = File(fuentes, completo.replace('.', '/') + ".kt")
            !ruta.isFile
        }
        assertTrue(
            "el manifiesto apunta a clases que no existen — la aplicación " +
                "reventaría al abrirlas:\n" +
                ausentes.joinToString("\n") { (linea, nombre) ->
                    "  AndroidManifest.xml:$linea  $paquete$nombre"
                },
            ausentes.isEmpty()
        )
    }

    /**
     * Ninguna **clase** nuestra puede nombrarse como texto.
     *
     * Es la otra forma de que un renombrado pase desapercibido: un
     * `Class.forName` o un `ComponentName` con el nombre escrito a mano no lo
     * toca el compilador y falla en tiempo de ejecución, igual que el
     * manifiesto.
     *
     * Se busca solo lo que **parece una clase**: un último tramo en
     * `MayúsculaMinúscula`. Las acciones de intent
     * (`com.forge.pixpin.action.START_SESSION`) y las autoridades de proveedor
     * también llevan el paquete delante, pero van en MAYÚSCULAS y son
     * identificadores elegidos a mano que un renombrado de código **no debe**
     * tocar: cambiarlos rompería los intents ya programados y las
     * notificaciones vivas. De ahí que la minúscula tras la primera letra sea
     * lo que separa una cosa de la otra.
     */
    @Test
    fun `ninguna clase nuestra se nombra como cadena de texto`() {
        val re = Regex(""""com\.forge\.pixpin(?:\.[a-z]\w*)*\.[A-Z][a-z]\w*"""")
        val sospechosos = mutableListOf<String>()
        fuentes.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, linea ->
                re.find(linea)?.let { sospechosos += "  ${f.name}:${i + 1}  ${it.value}" }
            }
        }
        assertTrue(
            "clases nombradas a mano; un renombrado no las tocaría y fallarían " +
                "solo al ejecutarse:\n" +
                sospechosos.joinToString("\n"),
            sospechosos.isEmpty()
        )
    }
}
