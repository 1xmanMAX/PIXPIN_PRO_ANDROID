package com.forge.pixpin.guardados

import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.sincro.ChatQueViaja
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Un lienzo hecho en el PC o en el Mac, en el teléfono** (1-nov-2026).
 *
 * El PC (`ventana_chat::escribir_lienzo`) y el Mac (`Nucleo.crearLienzo`, el mismo núcleo) guardan
 * un lienzo nuevo en `lienzos/<código>.excalidraw` y apuntan su mensaje `DIBUJO` con ese fichero de
 * nombre; allí la fila dice «Lienzo» (`nombre_de_la_fila`). Al sincronizar sale con la ruta del
 * teléfono (`vista::virtual_de`: `pixpin:files/pins/draw/<código>.excalidraw.gz`). Aquí llegaba tal
 * cual a la burbuja, y su `.excalidraw.gz` se compartía como «Archivo». El usuario: «Excalidraw es
 * una extensión que ya quitamos».
 *
 * Lo que se comprueba: que nada de eso enseña `.excalidraw`, que el mensaje se guarda **sin tocar**
 * (lo que viaja de vuelta es lo mismo que llegó) y que, dentro de un `.pixpin`, el lienzo va como su
 * hoja y no como un fichero adjunto (PC `1984938a`: «el `.excalidraw` suelto ya no se ofrece»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LienzoDelPcTest {

    @get:Rule
    val carpeta = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    /** Un lienzo nuevo del PC, como lo escribe en su `guardados.jsonl` (`Mensaje::adjunto`). */
    private val enElPc =
        """{"id":"1793541600000","cuando":1793541600000,"clase":"DIBUJO","texto":"","ruta":"lienzos/VRQH2DA7AB.excalidraw",""" +
            """"nombre":"VRQH2DA7AB.excalidraw","bytes":104,"referencia":"VRQH2DA7AB","numero":12,"uid":"MPX4KQ7ZR2","aparato":"W9PC","proyecto":"pr-1793500000000"}"""

    /** El mismo, como llega al teléfono: la ruta hecha portátil (`vista::virtual_de`) y el chat por su id. */
    private val alTelefono =
        """{"id":"1793541600000","cuando":1793541600000,"clase":"DIBUJO","texto":"","ruta":"pixpin:files/pins/draw/VRQH2DA7AB.excalidraw.gz",""" +
            """"nombre":"VRQH2DA7AB.excalidraw","bytes":104,"referencia":"VRQH2DA7AB","numero":12,"uid":"MPX4KQ7ZR2","aparato":"W9PC","proyecto":"obra-norte"}"""

    private fun mensaje(linea: String) = json.decodeFromString(Mensaje.serializer(), linea)

    @Test
    fun elLienzoDelPcSeLlamaLienzoEnTodasPartes() {
        for (linea in listOf(enElPc, alTelefono)) {
            val m = mensaje(linea)
            assertEquals(Clase.DIBUJO, m.clase)
            assertEquals("VRQH2DA7AB", m.referencia)
            assertTrue(NombreDelLienzo.esDeSerie(m.nombre, m.referencia))
            assertEquals("Lienzo", NombreDelLienzo.visible(m))
            assertEquals("Lienzo", NombreDelLienzo.deLaHoja(m))
            assertEquals("✏️ Lienzo", com.forge.pixpin.ui.vistazo(m))
            assertEquals("Lienzo", com.forge.pixpin.atajos.Atajos.nombreDe(m))
            // El asistente lo llama por su número, como a uno de aquí sin nombre.
            assertEquals("Lienzo 12", com.forge.pixpin.asistente.ChatDelAsistente.nombreDeLienzo(m))
        }
    }

    @Test
    fun unNombrePuestoSeEnsenaSinLaExtensionVieja() {
        val base = mensaje(alTelefono)
        assertEquals("Planta baja", NombreDelLienzo.visible(base.copy(nombre = "Planta baja")))
        assertEquals("Planta baja", NombreDelLienzo.visible(base.copy(nombre = "Planta baja.excalidraw")))
        assertEquals("Planta baja", NombreDelLienzo.deLaHoja(base.copy(nombre = "Planta baja.EXCALIDRAW")))
        assertEquals("Planta baja", com.forge.pixpin.asistente.ChatDelAsistente.nombreDeLienzo(base.copy(nombre = "Planta baja.excalidraw")))
        // El de serie del teléfono, y uno vacío, también «Lienzo».
        assertEquals("Lienzo", NombreDelLienzo.visible(base.copy(nombre = "Lienzo")))
        assertEquals("Lienzo", NombreDelLienzo.visible(base.copy(nombre = "")))
        // Caso negativo: un nombre con otro punto no pierde nada («Plano v2.0» no es una extensión vieja).
        assertEquals("Plano v2.0", NombreDelLienzo.visible(base.copy(nombre = "Plano v2.0")))
        assertFalse(NombreDelLienzo.esDeSerie("Otro.excalidraw", "VRQH2DA7AB"))
    }

    @Test
    fun unExcalidrawSueltoSeLlamaPorSuNombre() {
        val m = Mensaje(id = "a", cuando = 1, clase = Clase.ARCHIVO, nombre = "boceto.excalidraw", ruta = "/x/boceto.excalidraw")
        assertTrue(NombreDelLienzo.esExcalidraw(m.nombre))
        assertEquals("boceto", NombreDelLienzo.visible(m))
        // Caso negativo: un PDF sigue con su nombre entero, y uno sin nombre con el de serie.
        val pdf = m.copy(nombre = "plano.pdf", ruta = "/x/plano.pdf")
        assertFalse(NombreDelLienzo.esExcalidraw(pdf.nombre))
        assertEquals("plano.pdf", NombreDelLienzo.visible(pdf))
        assertEquals("Archivo", NombreDelLienzo.visible(pdf.copy(nombre = ""), siVacio = "Archivo"))
    }

    @Test
    fun laEtiquetaDeLaSincronizacionDiceLienzo() {
        val rel = "pins/draw/VRQH2DA7AB.excalidraw.gz"
        assertEquals("#12·W9PC · Lienzo", NombreDelLienzo.deEtiqueta("#12·W9PC · VRQH2DA7AB.excalidraw", rel))
        assertEquals("Lienzo", NombreDelLienzo.deEtiqueta("VRQH2DA7AB.excalidraw.gz", rel))
        assertEquals("#3 · Fachada", NombreDelLienzo.deEtiqueta("#3 · Fachada.excalidraw", rel))
        // Caso negativo: lo que no es un lienzo no se toca.
        assertEquals("#4 · informe.pdf", NombreDelLienzo.deEtiqueta("#4 · informe.pdf", "guardados/informe.pdf"))
    }

    @Test
    fun elMensajeDelPcSeGuardaSinTocar() {
        // Lo que se enseña no es lo que se guarda: el nombre del PC vuelve igual, y la próxima vuelta
        // no ve ningún cambio que mandarle.
        val vuelta = Json { ignoreUnknownKeys = true; encodeDefaults = false }.encodeToString(Mensaje.serializer(), mensaje(alTelefono))
        val o = json.parseToJsonElement(vuelta) as JsonObject
        assertEquals("VRQH2DA7AB.excalidraw", o["nombre"]!!.jsonPrimitive.content)
        assertEquals("VRQH2DA7AB", o["referencia"]!!.jsonPrimitive.content)
        assertEquals("pixpin:files/pins/draw/VRQH2DA7AB.excalidraw.gz", o["ruta"]!!.jsonPrimitive.content)
    }

    @Test
    fun enUnPixpinElLienzoVaComoSuHojaNoComoAdjunto() {
        // El fichero del lienzo, donde lo deja la sincronización (la ruta, ya local).
        val gz = carpeta.newFile("VRQH2DA7AB.excalidraw.gz")
        gz.writeBytes(gzip("""{"elements":[]}"""))
        val m = mensaje(alTelefono).copy(ruta = gz.absolutePath)
        val p = Proyecto(id = "obra-norte", nombre = "Obra norte", hojas = listOf(Hoja(id = "h1", nombre = "Lienzo", dibujo = "VRQH2DA7AB")))
        val paquete = ChatQueViaja.preparar(p, listOf(m))!!
        assertTrue("sin el .excalidraw.gz de adjunto: ${paquete.adjuntos.keys}", paquete.adjuntos.isEmpty())
        val linea = json.parseToJsonElement(paquete.texto.lines().single()) as JsonObject
        assertNull("sin ruta: lo señala su referencia", linea["ruta"])
        assertEquals("VRQH2DA7AB", linea["referencia"]!!.jsonPrimitive.content)
        // Y en el cuaderno del PC, por la ruta con que el PC apunta un lienzo.
        val delPc = json.parseToJsonElement(CompartirCarpeta.chatParaElPc(paquete.texto).trim()) as JsonObject
        assertEquals("lienzos/VRQH2DA7AB.excalidraw", delPc["ruta"]!!.jsonPrimitive.content)
        // Caso negativo: un archivo de verdad sigue yendo de adjunto.
        val pdf = carpeta.newFile("informe.pdf").apply { writeText("%PDF-1.4") }
        val conPdf = ChatQueViaja.preparar(p, listOf(Mensaje(id = "f", cuando = 2, clase = Clase.ARCHIVO, nombre = "informe.pdf", ruta = pdf.absolutePath)))!!
        assertEquals(1, conPdf.adjuntos.size)
    }

    @Test
    fun unExcalidrawDelPcSeAbreAunqueTraigaFigurasQueNoSeEntienden() {
        // Un `.excalidraw` de antes del PC: el papel en `appState`, los puntos como pares y una
        // figura de una versión más nueva que aquí no se conoce.
        val texto = """{"type":"excalidraw","version":2,"source":"pixpin","elements":[
            {"id":"t1","type":"freedraw","x":0,"y":0,"width":10,"height":10,"points":[[0,0],[10,10]]},
            {"id":"r1","type":"rectangle","x":20,"y":20,"width":40,"height":30},
            {"id":"zz","type":"cosa-del-futuro","x":0,"y":0}
        ],"appState":{"viewBackgroundColor":"#fdf6e3"},"files":{}}"""
        val escena = ExcalidrawStore.escenaSinFotos(texto)
        assertNotNull(escena)
        assertEquals("#fdf6e3", escena!!.backgroundColor)
        assertEquals(listOf("t1", "r1"), escena.elements.map { it.id })
        assertEquals(ElementType.FREEDRAW, escena.elements.first().type)
        // Comprimido como los de este aparato, igual.
        assertEquals(texto, ExcalidrawStore.textoDe(gzip(texto)))
        // Caso negativo: lo que no es un lienzo no da escena.
        assertNull(ExcalidrawStore.escenaSinFotos("""{"hola":1}"""))
        assertNull(ExcalidrawStore.escenaSinFotos("no es json"))
    }

    @Test
    fun elLienzoDeUnExcalidrawSueltoEsSiempreElMismo() {
        val m = Mensaje(id = "1793/a b", cuando = 1, clase = Clase.ARCHIVO, nombre = "boceto.excalidraw")
        assertEquals("suelto-1793_a_b", AbrirMensaje.dibujoDelSuelto(m))
        assertEquals("suelto-MPX4KQ7ZR2", AbrirMensaje.dibujoDelSuelto(m.copy(uid = "MPX4KQ7ZR2")))
    }

    private fun gzip(texto: String): ByteArray =
        ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(texto.toByteArray()) } }.toByteArray()
}
