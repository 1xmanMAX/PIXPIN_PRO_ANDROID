package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** **Las figuras de día se ven de día** (2-nov-2026): la hoja blanca de una plantilla no se da la vuelta a negro en la vista previa. */
class FiguraDeDiaEnLaVistaTest {
    @Test
    fun `la pagina blanca de un planificador de dia sigue blanca en la miniatura`() {
        val f = sequenceOf("src/main/assets/figuras/serie.json.gz", "app/src/main/assets/figuras/serie.json.gz").map(::File).first { it.isFile }
        val fig = f.inputStream().use(FigurasDeSerie::leer).first { it.id == "serie-planificador-diario" }
        val vista = FigurasDeSerie.paraVer(fig.elementos)
        val mapa = PapelDeLaHoja.de(vista)
        val pagina = vista.first()
        assertTrue("la plantilla es una hoja", pagina.id in mapa.comoSeDiseno)
        assertEquals(0xFFFFFFFF.toInt(), PapelDeLaHoja.tinta(mapa, pagina.id, DrawTheme.colorDe(pagina.backgroundColor), 0xFFFFFFFF.toInt()))
        // Caso negativo: sin agrupar (como antes) el blanco se daba la vuelta.
        val sinGrupo = PapelDeLaHoja.de(fig.elementos)
        assertEquals(0xFF000000.toInt(), PapelDeLaHoja.tinta(sinGrupo, pagina.id, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
    }
}
