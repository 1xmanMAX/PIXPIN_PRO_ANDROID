package com.forge.pixpin.croquis

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CroquisSerializationTest {

    private val json = Json

    @Test
    fun `un croquis con todas las entidades sobrevive a la ida y vuelta`() {
        val original = Croquis(
            entidades = listOf(
                Entidad.Linea(P(0.0, 0.0), P(3.0, 4.0)),
                Entidad.Polilinea(listOf(P(0.0, 0.0), P(1.0, 1.0), P(2.0, 0.0)), cerrada = true),
                Entidad.Rect(P(0.0, 0.0), P(2.0, 2.0)),
                Entidad.Circulo(P(1.0, 1.0), 5.0),
                Entidad.Texto(P(0.0, 0.0), "muro de contención", 0.25),
                Entidad.Cota(P(0.0, 0.0), P(4.2, 0.0), 0.5)
            ),
            fondo = Fondo("/datos/captura.png", P(10.0, 20.0), 0.014),
            decimales = 3
        )

        val vuelta = json.decodeFromString<Croquis>(json.encodeToString(original))

        assertEquals(original, vuelta)
    }

    @Test
    fun `un croquis recien nacido es un croquis vacio sin fondo`() {
        val vuelta = json.decodeFromString<Croquis>(json.encodeToString(Croquis()))
        assertEquals(0, vuelta.entidades.size)
        assertEquals(null, vuelta.fondo)
    }

    @Test
    fun `las coordenadas UTM conservan los milimetros al pasar por JSON`() {
        val original = Croquis(
            entidades = listOf(Entidad.Linea(P(709927.003, 8240708.002), P(709930.0, 8240708.0)))
        )
        val vuelta = json.decodeFromString<Croquis>(json.encodeToString(original))
        val linea = vuelta.entidades.first() as Entidad.Linea
        assertEquals(0.003, linea.a.x - 709927.0, 1e-9)
        assertEquals(0.002, linea.a.y - 8240708.0, 1e-9)
    }
}
