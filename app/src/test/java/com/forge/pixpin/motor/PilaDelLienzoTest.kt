package com.forge.pixpin.motor

import com.forge.pixpin.asistente.EnlacesDelTelefono
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** **La pila del pedido `al_lienzo`** (PC `pila_lienzo.rs`): columna a la derecha, una debajo de otra, sin pisar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PilaDelLienzoTest {
    private fun caja(id: String, x: Double, y: Double, w: Double, h: Double) =
        newElement(ElementType.RECTANGLE, x, y, ItemStyle(), w, h).copy(id = id)

    @Test fun `la primera va a la derecha de todo y las demas debajo`() {
        val antes = listOf(caja("a", 0.0, 10.0, 100.0, 50.0), caja("b", 200.0, 300.0, 100.0, 50.0))
        assertEquals(300.0 + PilaDelLienzo.AIRE_COLUMNA to 10.0, PilaDelLienzo.sitio(antes, 100.0 to 40.0))
        val apilado = antes + caja("${PilaDelLienzo.PREFIJO}1", 380.0, 10.0, 100.0, 40.0)
        assertEquals(380.0 to 50.0 + PilaDelLienzo.HUECO, PilaDelLienzo.sitio(apilado, 100.0 to 40.0))
        // Lo que el usuario puso en la columna no se pisa: se baja por debajo.
        val estorbo = apilado + caja("c", 370.0, 70.0, 120.0, 200.0)
        assertEquals(380.0 to 270.0 + PilaDelLienzo.HUECO, PilaDelLienzo.sitio(estorbo, 100.0 to 40.0))
        // Vacío: en el origen. Y una foto ancha entra a lo ancho de la columna.
        assertEquals(0.0 to 0.0, PilaDelLienzo.sitio(emptyList(), 10.0 to 10.0))
        assertEquals(PilaDelLienzo.ANCHO_COLUMNA to 240.0, PilaDelLienzo.tamano(960, 480))
        assertEquals(100.0 to 50.0, PilaDelLienzo.tamano(100, 50))
    }

    @Test fun `al_lienzo apila el texto en el lienzo cerrado por su nombre`() {
        val app = RuntimeEnvironment.getApplication()
        ExcalidrawStore.guardar(app, "dib-7", Scene(elements = listOf(caja("a", 0.0, 0.0, 100.0, 100.0))))
        MensajesStore(app).anadir(Mensaje(id = "m1", cuando = 1L, clase = Clase.DIBUJO, nombre = "Plano Casa", referencia = "dib-7"))
        val e = EnlacesDelTelefono(app)
        val p = Json.parseToJsonElement("""{"pixpin":1,"accion":"al_lienzo","lienzo":"plano casa","texto":"la puerta [img 01]"}""") as JsonObject
        e.alLienzo(p, MensajesStore(app))
        e.alLienzo(p, MensajesStore(app))
        val els = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(app, "dib-7"))!!.elements
        val pila = els.filter { it.id.startsWith(PilaDelLienzo.PREFIJO) }
        assertEquals(2, pila.size)
        assertEquals("la puerta", pila[0].text)
        assertTrue("a la derecha de lo que había", pila[0].x >= 100.0 + PilaDelLienzo.AIRE_COLUMNA - 1)
        assertTrue("la segunda debajo de la primera", pila[1].y >= pila[0].y + pila[0].height + PilaDelLienzo.HUECO - 1)
        // Caso negativo: sin texto ni fotos no se hace nada.
        assertTrue(runCatching { e.alLienzo(Json.parseToJsonElement("""{"accion":"al_lienzo","lienzo":"x"}""") as JsonObject, MensajesStore(app)) }.isFailure)
    }
}
