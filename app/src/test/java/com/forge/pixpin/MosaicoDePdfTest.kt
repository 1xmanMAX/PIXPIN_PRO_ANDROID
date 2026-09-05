package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El plano partido en hojas pequeñas. Ver [MosaicoDePdf].
 *
 * Aquí está todo lo que decide **cuántos cuadros hay, de qué tamaño y en qué orden se
 * hacen**. Si esto está bien, lo que queda es abrir el PDF y pintar; si está mal, se ven
 * costuras, huecos, o el teléfono rasterizando una esquina mientras miras la otra.
 */
class MosaicoDePdfTest {

    /**
     * **La resolución la marca el aumento, no los puntos por pulgada del papel.**
     *
     * Esto estuvo atado al tamaño de verdad del papel —260 ppp, lo que se pide para
     * imprimir— y el plano se seguía viendo con grano: en una pantalla el papel no se mira a
     * su tamaño, se amplía. Como el dibujo mide siempre 1400 unidades de ancho sea un A4 o un
     * A0, lo que decide lo que se ve es **cuántos píxeles de imagen hay por unidad**, y con
     * 260 ppp un A4 salían 1,53: grano en cuanto se pasaba del doble de aumento.
     */
    @Test
    fun `la fineza da para ampliar de verdad`() {
        val ancho = 1400.0
        val alto = 1400.0 * Math.sqrt(2.0)
        val fineza = MosaicoDePdf.pixelesPorUnidad(ancho, alto)
        assertTrue("salió a $fineza píxeles por unidad", fineza >= 8.0)

        // Y lo que se gasta es lo presupuestado, ni más ni menos.
        val pixeles = ancho * alto * fineza * fineza
        assertTrue(pixeles <= MosaicoDePdf.PIXELES_DEL_PLANO * 1.01)
        assertTrue(pixeles >= MosaicoDePdf.PIXELES_DEL_PLANO * 0.99)
    }

    /** Y por grande o pequeño que sea el dibujo, la fineza no se dispara ni se hunde. */
    @Test
    fun `la fineza se queda dentro de lo razonable`() {
        assertTrue(MosaicoDePdf.pixelesPorUnidad(100.0, 100.0) <= MosaicoDePdf.LO_MAS_FINO)
        assertTrue(MosaicoDePdf.pixelesPorUnidad(100000.0, 100000.0) >= MosaicoDePdf.LO_MENOS_FINO)
        assertEquals(MosaicoDePdf.LO_MENOS_FINO, MosaicoDePdf.pixelesPorUnidad(0.0, 1400.0), 1e-9)
    }

    /**
     * **La escala a la que se descomprime sigue al aumento.**
     *
     * Es lo que permite pintar el plano entero con sus propios cuadros en vez de con la
     * imagen de una pieza: mirando el papel entero, cada cuadro se abre a un octavo —ocho
     * kilobytes en vez de medio mega— y se ve lo mismo, porque a ese aumento sus píxeles no
     * caben en la pantalla. Ver [MosaicoDePdf.muestraPara].
     */
    @Test
    fun `la escala de descompresion sigue al aumento`() {
        val fineza = MosaicoDePdf.pixelesPorUnidad(1400.0, 1400.0 * Math.sqrt(2.0))
        // El papel entero en una pantalla: la unidad del dibujo no llega ni a un píxel.
        assertTrue(MosaicoDePdf.muestraPara(fineza, 0.77) >= 8)
        // De cerca, el cuadro entero y sin encoger.
        assertEquals(1, MosaicoDePdf.muestraPara(fineza, fineza))
        assertEquals(1, MosaicoDePdf.muestraPara(fineza, 400.0))
        // Y por lejos que se vaya, no se encoge más de la cuenta.
        assertTrue(MosaicoDePdf.muestraPara(fineza, 0.0001) <= MosaicoDePdf.MUESTRA_MAXIMA)
    }

    /**
     * **Y así, con el plano entero delante, sus cuadros caben en memoria.** Antes no: a tamaño
     * completo eran ochocientos medios megas y había que volver a la imagen de una pieza, que
     * es lo que se veía con grano.
     */
    @Test
    fun `el plano entero a la vista cabe en memoria`() {
        val ancho = 1400.0
        val alto = 1400.0 * Math.sqrt(2.0)
        val fineza = MosaicoDePdf.pixelesPorUnidad(ancho, alto)
        val cuadros = MosaicoDePdf.todos(ancho, alto, MosaicoDePdf.ladoDelCuadro(fineza)).size
        val muestra = MosaicoDePdf.muestraPara(fineza, 0.77)
        val lado = MosaicoDePdf.LADO_EN_PIXELES / muestra
        val pesa = cuadros.toLong() * lado * lado * 2
        assertTrue(
            "$cuadros cuadros a un $muestra avo son ${pesa / 1024 / 1024} MB",
            pesa < MosaicoDePdf.PRESUPUESTO
        )
        // A tamaño completo no habrían cabido, que es de donde viene todo esto.
        val entero = cuadros.toLong() * MosaicoDePdf.LADO_EN_PIXELES * MosaicoDePdf.LADO_EN_PIXELES * 2
        assertTrue(entero > MosaicoDePdf.PRESUPUESTO)
    }

    /**
     * **En crudo el plano entero no cabe en memoria; comprimido y en disco, de sobra.** Es de
     * donde viene todo lo demás: por eso los cuadros se escriben en el teléfono
     * ([CuadrosEnDisco]) y en memoria solo vive lo que se está mirando, a la escala en la que
     * se mira. La cuenta del comprimido va a un cuarto de byte por píxel, que para un plano
     * —líneas sobre blanco— es pesimista. Si alguien sube la fineza sin mirar, esto se queja.
     */
    @Test
    fun `el plano entero cabe en disco y no en memoria`() {
        val ancho = 1400.0
        val alto = 1400.0 * Math.sqrt(2.0)
        val lado = MosaicoDePdf.ladoDelCuadro(MosaicoDePdf.pixelesPorUnidad(ancho, alto))
        val cuadros = MosaicoDePdf.todos(ancho, alto, lado).size
        val pixeles = cuadros.toLong() * MosaicoDePdf.LADO_EN_PIXELES * MosaicoDePdf.LADO_EN_PIXELES

        val crudo = pixeles * 2
        assertTrue("en crudo son ${crudo / 1024 / 1024} MB", crudo > MosaicoDePdf.PRESUPUESTO)

        // Y guardado caben varios planos como este, que es lo que se le pide al techo del disco.
        val comprimido = pixeles / 4
        assertTrue(
            "$cuadros cuadros, ${comprimido / 1024 / 1024} MB comprimidos",
            comprimido * 4 < CuadrosEnDisco.TECHO
        )
        assertTrue(MosaicoDePdf.CALIDAD_DEL_CUADRO in 85..95)
    }

    /**
     * **Las franjas: pocas pasadas y anchas, no una por cuadro.**
     *
     * Es el arreglo de lo que hacía que el plano no llegara a hacerse nunca. En un plano de
     * verdad —el A0 del proyecto que mandó el usuario tiene ochocientas mil líneas— rasterizar
     * la página tarda **casi lo mismo a 18 puntos por pulgada que a 144**, porque el trabajo
     * es recorrer las órdenes de dibujo, no pintar píxeles. Con un cuadro por pasada eso son
     * ochocientas pasadas; por franjas, unas decenas. Ver [PdfDoc.franjas].
     */
    @Test
    fun `el plano se hace en pocas pasadas y no en cientos`() {
        val ancho = 1400.0
        val alto = 1400.0 * Math.sqrt(2.0)
        val lado = MosaicoDePdf.ladoDelCuadro(MosaicoDePdf.pixelesPorUnidad(ancho, alto))
        val (columnas, filas) = MosaicoDePdf.rejilla(ancho, alto, lado)
        val porFranja = MosaicoDePdf.filasPorFranja(columnas)
        val pasadas = MosaicoDePdf.cuantasFranjas(filas, porFranja)

        assertTrue("son $pasadas pasadas para ${columnas * filas} cuadros", pasadas <= 64)
        assertTrue(pasadas < columnas * filas / 10)

        // Y una franja cabe en su presupuesto: se pide en ARGB_8888, que es lo único que
        // acepta PdfRenderer, o sea cuatro bytes por píxel.
        val pesa = columnas.toLong() * MosaicoDePdf.LADO_EN_PIXELES *
            porFranja * MosaicoDePdf.LADO_EN_PIXELES * 4
        assertTrue("una franja son ${pesa / 1024 / 1024} MB", pesa <= MosaicoDePdf.PRESUPUESTO_DE_LA_FRANJA)
    }

    /** Cada cuadro cae en una franja y solo en una, y ninguna se queda sin hacer. */
    @Test
    fun `las franjas reparten todas las filas`() {
        val porFranja = 3
        assertEquals(0, MosaicoDePdf.franjaDe(MosaicoDePdf.Cuadro(5, 0), porFranja))
        assertEquals(0, MosaicoDePdf.franjaDe(MosaicoDePdf.Cuadro(5, 2), porFranja))
        assertEquals(1, MosaicoDePdf.franjaDe(MosaicoDePdf.Cuadro(0, 3), porFranja))
        assertEquals(3, MosaicoDePdf.cuantasFranjas(9, porFranja))
        assertEquals(4, MosaicoDePdf.cuantasFranjas(10, porFranja))
        assertEquals(0, MosaicoDePdf.cuantasFranjas(0, porFranja))
        // Con una rejilla estrechísima siempre se hace al menos una fila por pasada.
        assertTrue(MosaicoDePdf.filasPorFranja(1000) >= 1)
    }

    /** Nunca se guardan tan pocos que no se pueda tapar la pantalla. */
    @Test
    fun `siempre caben unos cuantos`() {
        assertTrue(MosaicoDePdf.cuantosCaben(1) >= MosaicoDePdf.MINIMO_DE_CUADROS)
    }

    /** Lo de alrededor incluye lo que se ve y algo más, para tenerlo listo al apartar. */
    @Test
    fun `lo de alrededor abarca mas que lo que se ve`() {
        val lado = 100.0
        val vista = Bounds(300.0, 300.0, 400.0, 400.0)
        val dentro = MosaicoDePdf.losDe(vista, 1000.0, 1000.0, lado)
        val conContorno = MosaicoDePdf.losDeAlrededor(vista, 1000.0, 1000.0, lado)
        assertTrue(conContorno.size > dentro.size)
        assertTrue(conContorno.containsAll(dentro))
    }

    /** La rejilla tapa el papel entero y ni un cuadro más. */
    @Test
    fun `la rejilla tapa el papel justo`() {
        val lado = 100.0
        assertEquals(3 to 2, MosaicoDePdf.rejilla(250.0, 150.0, lado))
        assertEquals(2 to 2, MosaicoDePdf.rejilla(200.0, 200.0, lado))
        assertEquals(6, MosaicoDePdf.todos(250.0, 150.0, lado).size)
    }

    @Test
    fun `los cuadros que se piden son los que se ven`() {
        val lado = 100.0
        val uno = MosaicoDePdf.losDe(Bounds(0.0, 0.0, 100.0, 100.0), 400.0, 300.0, lado)
        assertEquals(1, uno.size)
        assertEquals(MosaicoDePdf.Cuadro(0, 0), uno.first())

        val cuatro = MosaicoDePdf.losDe(Bounds(90.0, 90.0, 110.0, 110.0), 400.0, 300.0, lado)
        assertEquals(4, cuatro.size)
    }

    /** Lo que se sale del papel no se pide, y mirando fuera del todo no se pide nada. */
    @Test
    fun `no se piden cuadros fuera del papel`() {
        val lado = 100.0
        val todos = MosaicoDePdf.losDe(Bounds(-500.0, -500.0, 900.0, 900.0), 400.0, 300.0, lado)
        assertEquals(MosaicoDePdf.todos(400.0, 300.0, lado).size, todos.size)
        assertTrue(
            MosaicoDePdf.losDe(Bounds(900.0, 900.0, 1000.0, 1000.0), 400.0, 300.0, lado).isEmpty()
        )
    }

    /**
     * **Se empieza por donde se mira.** Con un plano de doscientos cuadros, empezar por la
     * esquina de arriba deja al que mira el centro esperando a que llegue su turno.
     */
    @Test
    fun `los primeros cuadros son los que se estan mirando`() {
        val lado = 100.0
        val todos = MosaicoDePdf.todos(1000.0, 1000.0, lado)
        val mirando = Bounds(500.0, 500.0, 600.0, 600.0)
        val orden = MosaicoDePdf.porDondeEmpezar(todos, mirando, lado)
        assertEquals(todos.size, orden.size)
        assertEquals(MosaicoDePdf.Cuadro(5, 5), orden.first())
        // Y el último es una esquina, lo más lejos de donde se mira.
        val ultimo = orden.last()
        assertTrue(
            "el último tenía que ser una esquina y fue $ultimo",
            (ultimo.columna == 0 || ultimo.columna == 9) && (ultimo.fila == 0 || ultimo.fila == 9)
        )
    }

    // -------------------------------------------------------------------------------------
    // La lámina de cerca
    // -------------------------------------------------------------------------------------

    /**
     * **Se rasteriza lo que se mira y un poco más**, sin salirse del papel: pedirle al PDF
     * fuera de la página es rasterizar blanco.
     */
    @Test
    fun `la lamina lleva margen y se recorta al papel`() {
        val l = MosaicoDePdf.laminaPara(Bounds(400.0, 400.0, 600.0, 800.0), 1400.0, 2000.0, 4.0)!!
        // Doscientas unidades de ancho con un cuarto de margen: 250, centradas en las mismas.
        assertEquals(375.0, l.donde.x1, 1e-6)
        assertEquals(625.0, l.donde.x2, 1e-6)
        // Y a cuatro píxeles por unidad, mil píxeles de ancho: lo que se ve y ni uno más.
        assertEquals(1000, l.ancho)

        // Pegada al borde no se sale: el margen se come contra el papel.
        val borde = MosaicoDePdf.laminaPara(Bounds(0.0, 0.0, 200.0, 200.0), 1400.0, 2000.0, 4.0)!!
        assertEquals(0.0, borde.donde.x1, 1e-6)
        assertEquals(0.0, borde.donde.y1, 1e-6)
    }

    /** Fuera del papel no hay nada que pedir. */
    @Test
    fun `sin papel debajo no hay lamina`() {
        assertNull(MosaicoDePdf.laminaPara(Bounds(3000.0, 10.0, 3200.0, 200.0), 1400.0, 2000.0, 8.0))
        assertNull(MosaicoDePdf.laminaPara(Bounds(0.0, 0.0, 100.0, 100.0), 1400.0, 2000.0, 0.0))
    }

    /**
     * **Si no cabe se baja la resolución, no el trozo.** Media pantalla sin lámina se ve
     * peor que la pantalla entera un punto más blanda.
     */
    @Test
    fun `una lamina enorme se afloja pero no se recorta`() {
        val vista = Bounds(0.0, 0.0, 1400.0, 2000.0)
        val l = MosaicoDePdf.laminaPara(vista, 1400.0, 2000.0, 40.0, presupuesto = 4_000_000.0)!!
        assertEquals(1400.0, l.donde.width, 1e-6)
        assertEquals(2000.0, l.donde.height, 1e-6)
        assertTrue("se pasó del presupuesto: ${l.ancho}×${l.alto}", l.ancho.toLong() * l.alto <= 4_200_000)
        assertTrue("no se aflojó la resolución: ${l.fineza}", l.fineza < 40.0)
    }

    /**
     * **Alejarse y moverse un poco no vuelven al PDF**, que es lo caro; asomarse fuera de la
     * lámina o acercarse de verdad, sí.
     */
    @Test
    fun `la lamina que hay se estira mientras valga`() {
        val vista = Bounds(400.0, 400.0, 600.0, 800.0)
        val l = MosaicoDePdf.laminaPara(vista, 1400.0, 2000.0, 4.0)!!
        assertTrue(MosaicoDePdf.valeLaLamina(l, vista, 4.0))
        // Alejarse le deja píxeles de sobra, mientras siga tapando.
        assertTrue(MosaicoDePdf.valeLaLamina(l, Bounds(410.0, 410.0, 590.0, 790.0), 2.0))
        // Acercarse un pelo se aguanta con la holgura; acercarse de verdad, no.
        assertTrue(MosaicoDePdf.valeLaLamina(l, vista, 4.8))
        assertFalse(MosaicoDePdf.valeLaLamina(l, vista, 8.0))
        // Y asomarse fuera del trozo pide otra.
        assertFalse(MosaicoDePdf.valeLaLamina(l, Bounds(400.0, 400.0, 900.0, 800.0), 4.0))
    }

    /**
     * **Solo se vuelve al PDF cuando el mosaico ya no da más de sí.** Mientras sus cuadros
     * tengan más píxeles por unidad que el aumento, la lámina no aportaría nada y costaría
     * una pasada al documento.
     */
    @Test
    fun `la lamina se pide solo pasado el aumento del mosaico`() {
        assertFalse(MosaicoDePdf.haceFaltaLamina(12.0, 1.0))
        assertFalse(MosaicoDePdf.haceFaltaLamina(12.0, 12.0))
        assertTrue(MosaicoDePdf.haceFaltaLamina(12.0, 30.0))
        // Sin mosaico, cualquier aumento la pide.
        assertTrue(MosaicoDePdf.haceFaltaLamina(0.0, 0.5))
    }
}
