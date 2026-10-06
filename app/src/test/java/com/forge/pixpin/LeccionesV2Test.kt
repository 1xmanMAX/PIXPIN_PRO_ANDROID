package com.forge.pixpin.lecciones

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las lecciones v2 que trae el PC (4-oct-2026): la barra rápida, la gravedad propuesta y el
 * repaso de tres botones. Son las mismas pruebas que `pruebas.rs` del PC, con sus mismos textos:
 * si un lado cambia y el otro no, falla aquí.
 */
class LeccionesV2Test {

    private fun l(id: String, titulo: String) = Leccion(id = id, creada = 1000, titulo = titulo)

    @Test
    fun `el repaso de tres botones dice cuando vuelve`() {
        val dia = Leccion.DIA
        // Caja 3 (14 días): recordar la lleva a 30, a medias a 7, olvidar a 1.
        val x = l("a", "Sellar grietas").copy(caja = 3)
        assertEquals(30L, Repaso.diasHasta(x, Nota.RECORDABA))
        assertEquals(7L, Repaso.diasHasta(x, Nota.A_MEDIAS))
        assertEquals(1L, Repaso.diasHasta(x, Nota.OLVIDE))
        val r = Repaso.calificar(x, Nota.A_MEDIAS, 10 * dia)
        assertEquals(2, r.caja); assertEquals(17 * dia, r.repasar)
        // Solo cambian caja y repasar: el JSON es el mismo que se escribía antes.
        val antes = Json.parseToJsonElement(Leccion.escribir(x)).jsonObject
        val despues = Json.parseToJsonElement(Leccion.escribir(r)).jsonObject
        assertEquals(antes.keys, despues.keys)
        assertEquals(listOf("caja", "repasar"), antes.keys.filter { antes[it] != despues[it] })
        // Caso negativo: a medias en la primera caja no baja de 0 ni de un día.
        val nueva = l("b", "x")
        assertEquals(0, Repaso.aMedias(nueva, 0).caja)
        assertEquals(1L, Repaso.diasHasta(nueva, Nota.A_MEDIAS))
    }

    @Test
    fun `la gravedad se propone por lo que cuenta`() {
        assertEquals(2, Etiquetador.proponerGravedad("La escalera del sótano resbala con el polvo de yeso", null))
        assertEquals(3, Etiquetador.proponerGravedad("Casi hay un accidente con la amoladora", null))
        assertEquals(2, Etiquetador.proponerGravedad("Algo cualquiera", Leccion.TIPO_ERROR))
        // Caso negativo: una frase neutra se queda en leve.
        assertEquals(1, Etiquetador.proponerGravedad("Revisar la escala antes de imprimir", null))
        // Ni «gravedad» por «grave»: palabras enteras.
        assertEquals(1, Etiquetador.proponerGravedad("La gravedad del asunto es baja", null))
    }

    @Test
    fun `la barra rapida rellena area gravedad y proyecto`() {
        val proyectos = listOf("f1" to "Obra Miraflores", "f2" to "Tesis")
        val previa = l("p", "Sellar grietas del muro antes de pintar").copy(area = "Construcción")
        val indices = listOf(Buscador.Indice(previa))
        val deQuien = { id: String -> if (id == "p") "f1" else null }
        val r = Rapida.rellenar(
            "La escalera del sótano en Miraflores resbala con el polvo de yeso; barrer antes de bajar material",
            Etiquetador.Aprendido.VACIO, indices, proyectos, deQuien
        )
        assertEquals(2, r.gravedad)
        assertEquals("el nombre sale en la frase", "f1", r.proyecto)
        // Sin nombre, el de la que más se parece.
        val r2 = Rapida.rellenar("Pintar el muro con grietas sin sellar", Etiquetador.Aprendido.VACIO, indices, proyectos, deQuien)
        assertEquals("f1", r2.proyecto)
        // Caso negativo: nada parecido ni nombrado, sin proyecto.
        val r3 = Rapida.rellenar("Dormir antes del examen", Etiquetador.Aprendido.VACIO, indices, proyectos, deQuien)
        assertNull(r3.proyecto)
        assertEquals(1, Rapida.rellenar("  ", Etiquetador.Aprendido.VACIO, indices, proyectos, deQuien).gravedad)
    }

    @Test
    fun `una palabra corta del nombre sola no elige proyecto`() {
        val proyectos = listOf("f1" to "Obra Miraflores")
        assertNull(Rapida.proyectoPorNombre("Revisar la obra antes de vaciar", proyectos))
        assertEquals("f1", Rapida.proyectoPorNombre("La obra de Miraflores", proyectos))
    }

    @Test
    fun `la barra rapida reparte la frase en sus campos`() {
        val frase = "pasó que pintamos sin sellar porque había prisa, la próxima vez compro el sellador con la pintura #obra"
        val p = Etiquetador.proponer(frase)
        val x = Rapida.leccion(frase, "id1", 500, p, "Trabajo", 2)
        assertEquals("Pintamos sin sellar", x.quePaso)
        assertEquals("Había prisa", x.porQue)
        assertEquals("Compro el sellador con la pintura", x.titulo)
        assertEquals("si hace de título no se repite", "", x.proxima)
        assertEquals("lo cambiado a mano gana a lo propuesto", "Trabajo", x.area)
        assertEquals(2, x.gravedad)
        assertEquals(listOf("obra"), x.etiquetas)
        assertFalse("obra" in x.etiquetasAuto)
        assertEquals("id1", x.id); assertEquals(500L, x.creada)
        // Caso negativo: una gravedad fuera de 1..3 no se guarda tal cual.
        assertEquals(3, Rapida.leccion("x y", "i", 1, p, null, 9).gravedad)
    }

    @Test
    fun `la cola del repaso cuenta las contestadas y no las saltadas`() {
        val c = ColaDeRepaso(listOf("a", "b", "c"))
        assertEquals(1 to 3, c.progreso)
        c.pasar(true)
        c.pasar(false)
        assertEquals("c", c.actual); assertEquals(3 to 3, c.progreso); assertEquals(1, c.hechas)
        c.pasar(true)
        assertNull(c.actual)
        // Caso negativo: pasar al acabar no cuenta de más ni se sale del final.
        c.pasar(true)
        assertEquals(2, c.hechas); assertEquals(3 to 3, c.progreso)
        assertEquals(1, ColaDeRepaso.paso("  ", false))
        assertEquals(2, ColaDeRepaso.paso("reviso", false))
        assertEquals(3, ColaDeRepaso.paso("", true))
        assertEquals("Vuelve mañana", ColaDeRepaso.cuandoVuelve(1))
        assertEquals("Vuelve en 7 días", ColaDeRepaso.cuandoVuelve(7))
    }
}
