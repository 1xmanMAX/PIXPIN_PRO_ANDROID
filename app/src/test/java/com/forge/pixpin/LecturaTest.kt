package com.forge.pixpin

import com.forge.pixpin.motor.Lectura
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LecturaTest {
    @Test
    fun `la flecha del riel va de arriba abajo y llega al final con la ultima linea a la vista`() {
        assertEquals(0f, Lectura.progreso(0f, 5000f, 1000f))
        assertEquals(0.5f, Lectura.progreso(2000f, 5000f, 1000f))
        assertEquals(1f, Lectura.progreso(4000f, 5000f, 1000f))
        // Sin salirse, y un documento que cabe entero no tiene recorrido.
        assertEquals(1f, Lectura.progreso(9000f, 5000f, 1000f))
        assertEquals(0f, Lectura.progreso(-50f, 5000f, 1000f))
        assertEquals(0f, Lectura.progreso(0f, 800f, 1000f))
    }

    @Test
    fun `el marcador verde de la voz es uno solo y se guarda aparte`() {
        val lista = listOf(Lectura.Marcador(1, 0.2f, "⭐"), Lectura.Marcador(2, 0.9f, Lectura.EMOJI_DE_VOZ))
        val con = Lectura.conMarcaDeVoz(lista, 0.5f)
        assertEquals(listOf("⭐", Lectura.EMOJI_DE_VOZ), con.map { it.emoji })
        assertEquals(0.5f, con[1].fraccion)
        assertEquals(listOf("⭐"), Lectura.conMarcaDeVoz(lista, null).map { it.emoji })
        assertEquals(12 to 0.25f, Lectura.vozDeTexto(Lectura.vozATexto(12, 0.25f)))
        assertEquals(null, Lectura.vozDeTexto("basura"))
        assertEquals(null, Lectura.vozDeTexto(null))
    }

    @Test
    fun `el estilo se pone antes de cerrar la cabecera y no se acumula`() {
        val pagina = "<html><head><title>x</title></head><body><p>hola</p></body></html>"
        val una = Lectura.conEstilo(pagina, grosor = 2, letra = 1)
        assertTrue(una.contains("font-weight:600 !important"))
        assertTrue(una.contains("font-family:sans-serif !important"))
        assertTrue(una.indexOf("pixpin-lector") < una.indexOf("</head>"))
        val dos = Lectura.conEstilo(una, grosor = 0, letra = 2)
        assertEquals(1, Regex("pixpin-lector").findAll(dos).count())
        assertTrue(dos.contains("font-weight:300") && dos.contains("monospace"))
        // Sin cabecera también vale, y un índice fuera de la lista cae en lo normal.
        assertTrue(Lectura.conEstilo("<p>a</p>", 99, 99).startsWith("<style"))
        assertTrue(Lectura.estilo(99, 99).contains("font-weight:400"))
    }

    @Test
    fun `los marcadores van en el orden del documento y se guardan en una línea`() {
        var m = Lectura.conMarcador(emptyList(), 0.8f, "⭐", 1)
        m = Lectura.conMarcador(m, 0.2f, "🔖", 2)
        m = Lectura.conMarcador(m, 1.7f, "❤️", 3)
        assertEquals(listOf("🔖", "⭐", "❤️"), m.map { it.emoji })
        assertEquals(1f, m.last().fraccion)
        assertEquals(m, Lectura.deTexto(Lectura.aTexto(m)))
        assertTrue(Lectura.deTexto("roto|1:x:y|").isEmpty())
        assertTrue(Lectura.deTexto(null).isEmpty())
    }

    @Test
    fun `el punto bajo el dedo no se sale de la fila`() {
        assertEquals(0, Lectura.puntoBajoElDedo(-30f, 40f, 3))
        assertEquals(1, Lectura.puntoBajoElDedo(55f, 40f, 3))
        assertEquals(2, Lectura.puntoBajoElDedo(900f, 40f, 3))
        assertEquals(-1, Lectura.puntoBajoElDedo(10f, 40f, 0))
    }

    @Test
    fun `para anotar se abren dos tercios a cada lado y la columna se queda en píxeles`() {
        assertEquals(240, Lectura.margenDe(360))
        assertEquals(840, Lectura.anchoConMargenes(360))
        val hoja = Lectura.estilo(1, 0, columna = 360)
        assertTrue(hoja.contains("width:360px") && hoja.contains("margin-left:240px") && hoja.contains("width:840px"))
        // Sin columna, ni rastro de márgenes.
        assertTrue(!Lectura.estilo(1, 0).contains("margin-left"))
        // Y poner y quitar no acumula hojas de estilo.
        val pagina = "<html><head></head><body>x</body></html>"
        val con = Lectura.conEstilo(pagina, 1, 0, 360)
        assertEquals(1, Regex("pixpin-lector").findAll(Lectura.conEstilo(con, 1, 0, null)).count())
    }

    @Test
    fun `el papel del documento lo decide la app, claro u oscuro`() {
        assertTrue(Lectura.estilo(1, 0, oscuro = true).contains("background:#15171c"))
        assertTrue(Lectura.estilo(1, 0, oscuro = false).contains("background:#ffffff"))
        assertTrue(!Lectura.estilo(1, 0).contains("background:"))
    }

    @Test
    fun `el centro tira de la vista cuando se vuelve hacia él, y deja quedarse en un margen`() {
        val centro = 240.0
        val margen = 240.0
        // Ir hacia un margen: se queda donde se deje.
        assertEquals(null, Lectura.imanDelCentro(antes = 240.0, ahora = 60.0, centro = centro, margen = margen))
        assertEquals(null, Lectura.imanDelCentro(60.0, 0.0, centro, margen))
        // Empujar de vuelta hacia el centro: al centro.
        assertEquals(centro, Lectura.imanDelCentro(0.0, 90.0, centro, margen))
        assertEquals(centro, Lectura.imanDelCentro(480.0, 400.0, centro, margen))
        // Casi en el centro, encaja aunque no se viniera hacia él; y ya en él, nada.
        assertEquals(centro, Lectura.imanDelCentro(240.0, 255.0, centro, margen))
        assertEquals(null, Lectura.imanDelCentro(100.0, 240.0, centro, margen))
    }

    @Test
    fun `la vista no se sale del documento`() {
        assertEquals(0.0 to 0.0, Lectura.dentroDelDocumento(-50.0, -9.0, 840.0, 5000.0, 360.0, 700.0))
        assertEquals(480.0 to 4300.0, Lectura.dentroDelDocumento(9999.0, 9999.0, 840.0, 5000.0, 360.0, 700.0))
        // Un documento más corto que la pantalla no se mueve.
        assertEquals(0.0 to 0.0, Lectura.dentroDelDocumento(10.0, 10.0, 300.0, 300.0, 360.0, 700.0))
    }

    @Test
    fun `el tamaño de la letra tiene topes`() {
        assertEquals(Lectura.TAMANO_MIN, Lectura.tamanoValido(10))
        assertEquals(Lectura.TAMANO_MAX, Lectura.tamanoValido(900))
        assertEquals(130, Lectura.tamanoValido(130))
    }
}
