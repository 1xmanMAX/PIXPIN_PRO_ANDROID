package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **El plano del PDF preparado para pintarlo en la pantalla.**
 *
 * Lo que aquí se comprueba es la traducción: de las órdenes que salen del PDF —mover, línea,
 * curva, cerrar, en pasos de punto fijo— a lo que Skia sabe pintar de un tirón: rayas sueltas
 * en un `FloatArray`, ya en unidades del dibujo. Lo demás —pintar— necesita pantalla.
 *
 * Ver [PlanoEnPantalla]; la lectura del PDF, que es donde está la dificultad, se comprueba sin
 * dispositivo en [PlanoDePdfTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanoEnPantallaTest {

    private val M = PlanoDePdf.MOVER
    private val L = PlanoDePdf.LINEA
    private val C = PlanoDePdf.CURVA
    private val Z = PlanoDePdf.CERRAR

    /** Un punto del papel, en pasos del punto fijo. */
    private fun paso(v: Double) = (v * PlanoDePdf.FINEZA).toInt()

    private fun brocha(
        ops: List<Byte>,
        puntos: List<Pair<Double, Double>>,
        relleno: Boolean = false,
        grosor: Double = 1.0
    ) = PlanoDePdf.Brocha(
        -1, 0x336699, 1.0, grosor, relleno, DoubleArray(0),
        ops.toByteArray(),
        puntos.map { paso(it.first) }.toIntArray(),
        puntos.map { paso(it.second) }.toIntArray()
    )

    private fun plano(
        brochas: List<PlanoDePdf.Brocha>,
        textos: List<PlanoDePdf.Texto> = emptyList()
    ) = PlanoDePdf.Plano(100.0, 50.0, emptyList(), brochas, textos, emptyList(), 0, false)

    /** Cien puntos de papel son mil unidades: todo sale multiplicado por diez. */
    private fun hecho(p: PlanoDePdf.Plano) = PlanoEnPantalla.de(p, 1000.0)

    @Test
    fun `el papel se mide en unidades del dibujo`() {
        val hecho = hecho(plano(listOf(brocha(listOf(M, L), listOf(0.0 to 0.0, 10.0 to 10.0)))))
        assertEquals(1000.0, hecho.ancho, 0.001)
        assertEquals(500.0, hecho.alto, 0.001)
    }

    @Test
    fun `una raya son cuatro números`() {
        val hecho = hecho(plano(listOf(brocha(listOf(M, L), listOf(1.0 to 2.0, 3.0 to 4.0)))))
        val tanda = hecho.tandas.single()
        assertEquals(listOf(10f, 20f, 30f, 40f), tanda.puntos.toList())
        assertEquals("la caja de la tanda", 10f, tanda.x0, 0.001f)
        assertEquals(40f, tanda.y1, 0.001f)
    }

    @Test
    fun `una polilínea encadena sus rayas`() {
        val hecho = hecho(
            plano(listOf(brocha(listOf(M, L, L), listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0))))
        )
        val tanda = hecho.tandas.single()
        assertEquals("dos rayas", 8, tanda.puntos.size)
        assertEquals(listOf(0f, 0f, 10f, 0f, 10f, 0f, 10f, 10f), tanda.puntos.toList())
    }

    /** Cerrar es volver al principio: sin esa raya, un contorno queda abierto por un lado. */
    @Test
    fun `cerrar vuelve al primer punto`() {
        val hecho = hecho(
            plano(listOf(brocha(listOf(M, L, L, Z), listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0))))
        )
        val tanda = hecho.tandas.single()
        assertEquals("tres rayas: dos y el cierre", 12, tanda.puntos.size)
        assertEquals(10f, tanda.puntos[8], 0.001f)
        assertEquals(0f, tanda.puntos[11], 0.001f)
    }

    /**
     * Las curvas se parten **al abrir y no al pintar**: lo que se pinta sesenta veces por
     * segundo no puede pagar nada que se pueda pagar una vez.
     */
    @Test
    fun `una curva llega partida en rayas`() {
        val hecho = hecho(
            plano(
                listOf(
                    brocha(
                        listOf(M, C),
                        listOf(0.0 to 0.0, 5.0 to 0.0, 10.0 to 5.0, 10.0 to 10.0)
                    )
                )
            )
        )
        val tanda = hecho.tandas.single()
        assertTrue("una curva tiene que dar varias rayas", tanda.puntos.size >= 4 * 4)
        // Empieza donde empezaba y acaba donde acababa.
        assertEquals(0f, tanda.puntos.first(), 0.001f)
        assertEquals(100f, tanda.puntos.last(), 0.001f)
    }

    @Test
    fun `de lejos se dejan fuera las rayas que no se verían`() {
        val larga = brocha(listOf(M, L), listOf(0.0 to 0.0, 10.0 to 0.0))
        val corta = brocha(listOf(M, L), listOf(0.0 to 20.0, 0.005 to 20.0), grosor = 0.0)
        val hecho = hecho(plano(listOf(larga, corta)))
        val tandas = hecho.tandas
        assertEquals(2, tandas.size)
        assertEquals("la larga se ve de lejos", 4, tandas[0].gordas.size)
        assertTrue("la corta no", tandas[1].gordas.isEmpty())
        assertEquals("pero de cerca sigue estando", 4, tandas[1].puntos.size)
    }

    @Test
    fun `una mancha va como camino y no como rayas`() {
        val hecho = hecho(
            plano(
                listOf(
                    brocha(
                        listOf(M, L, L, Z),
                        listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0),
                        relleno = true
                    )
                )
            )
        )
        assertTrue("no puede haber rayas", hecho.tandas.isEmpty())
        assertEquals(1, hecho.rellenos.size)
        assertTrue("y el camino tiene que tener algo", !hecho.rellenos[0].camino.isEmpty)
    }

    @Test
    fun `el grosor del papel se pasa a unidades del dibujo`() {
        val hecho = hecho(plano(listOf(brocha(listOf(M, L), listOf(0.0 to 0.0, 1.0 to 0.0), grosor = 2.0))))
        // Dos puntos de papel, con el papel diez veces más grande en unidades: veinte.
        assertEquals(20f, hecho.tandas.single().grosor, 0.001f)
    }

    /**
     * **Fuera del papel no se pinta nada.**
     *
     * Las láminas van en RGB_565, sin transparencia, y la fina sobresale del papel: sin
     * recortarla, lo de fuera salía negro y al mover parpadeaba entre blanco y negro (lo
     * reportó el usuario con un PDF corriente). Robolectric no rasteriza, así que aquí se
     * cuentan **las órdenes al lienzo**: antes de poner una lámina tiene que haber un recorte
     * exactamente del tamaño del papel.
     */
    @Test
    fun `la lamina se recorta al papel antes de pintarse`() {
        val hecho = hecho(plano(listOf(brocha(listOf(M, L), listOf(0.0 to 0.0, 100.0 to 50.0)))))
        val llegadas = java.util.concurrent.CountDownLatch(2)
        val ambito = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        hecho.conObrero(ambito) { llegadas.countDown() }

        val ordenes = ArrayList<String>()
        val bmp = android.graphics.Bitmap.createBitmap(200, 100, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = object : android.graphics.Canvas(bmp) {
            override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
                ordenes += "recorte $left $top $right $bottom"
                return super.clipRect(left, top, right, bottom)
            }
            override fun drawBitmap(
                bitmap: android.graphics.Bitmap, src: android.graphics.Rect?,
                dst: android.graphics.RectF, paint: android.graphics.Paint?
            ) {
                ordenes += "lamina ${dst.left} ${dst.top} ${dst.right} ${dst.bottom}"
                super.drawBitmap(bitmap, src, dst, paint)
            }
        }
        val vista = Bounds(-500.0, -250.0, 1500.0, 750.0)
        hecho.pintar(canvas, vista, 1.0)                  // pide las láminas
        assertTrue("las láminas no llegaron", llegadas.await(20, java.util.concurrent.TimeUnit.SECONDS))
        ordenes.clear()
        hecho.pintar(canvas, vista, 1.0)                  // ahora con lámina
        val recorte = ordenes.indexOfFirst { it.startsWith("recorte") }
        val lamina = ordenes.indexOfFirst { it.startsWith("lamina") }
        assertTrue("no hubo recorte: $ordenes", recorte >= 0)
        assertTrue("no se pintó ninguna lámina: $ordenes", lamina >= 0)
        assertTrue("el recorte tiene que ir antes que la lámina: $ordenes", recorte < lamina)
        assertEquals("y ser justo el papel", "recorte 0.0 0.0 1000.0 500.0", ordenes[recorte])
        // La lámina fina sobresale del papel: es justo lo que el recorte tiene que tapar.
        val fina = ordenes.filter { it.startsWith("lamina") }.maxByOrNull { it.length }!!
        assertTrue("la lámina fina tenía que sobresalir del papel: $fina", fina.contains("-"))
        hecho.soltar()
    }

    @Test
    fun `un rotulo llega con su matriz y su letra`() {
        val texto = PlanoDePdf.Texto(
            capa = -1, color = 0x000000, alfa = 1.0, texto = "A-1",
            a = 4.0, b = 0.0, c = 0.0, d = 4.0, x = 10.0, y = 20.0,
            ancho = 1.5, familia = "monospace", negrita = true, cursiva = false
        )
        val hecho = hecho(plano(listOf(brocha(listOf(M, L), listOf(0.0 to 0.0, 1.0 to 0.0))), listOf(texto)))
        val r = hecho.textos.single()
        assertEquals("A-1", r.texto)
        assertEquals("el tamaño, en unidades", 40f, r.tam, 0.001f)
        assertEquals("el ancho va en emes", 1.5f, r.ancho, 0.001f)
        assertNotNull(r.familia)
        val v = FloatArray(9)
        r.matriz.getValues(v)
        assertEquals("hacia dónde se escribe", 40f, v[0], 0.001f)
        assertEquals("dónde empieza", 100f, v[2], 0.001f)
        assertEquals(200f, v[5], 0.001f)
    }
    // ---------------------------------------------------------------------
    // Lo que llega al Canvas
    // ---------------------------------------------------------------------

    /** Un lienzo que no pinta: solo cuenta lo que le mandan. */
    private class Contador : android.graphics.Canvas(
        android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
    ) {
        var segmentos = 0
        var fotos = 0
        override fun drawLines(pts: FloatArray, paint: android.graphics.Paint) {
            segmentos += pts.size / 4
        }
        override fun drawBitmap(
            bitmap: android.graphics.Bitmap,
            matrix: android.graphics.Matrix,
            paint: android.graphics.Paint?
        ) { fotos++ }
    }

    private fun foto(x: Double, y: Double, lado: Double) = PlanoDePdf.Imagen(
        capa = -1, alfa = 1.0, tipo = "image/jpeg", datos = ByteArray(64),
        a = lado, b = 0.0, c = 0.0, d = lado, x = x, y = y
    )

    /**
     * **La foto que no se ve no se pinta.**
     *
     * Se pintaban todas en cada fotograma. En el plano del usuario (9-sep-2026) eso eran 81
     * mapas de bits de 992×877 por fotograma **mirando el 10 % de la página**, y era el tirón
     * que reportó. Una raya que sobra cuesta unos flotantes; una foto que sobra, un mapa de
     * bits entero.
     */
    @Test
    fun `una foto fuera de la vista no se pinta`() {
        val pl = PlanoDePdf.Plano(
            100.0, 100.0, emptyList(),
            listOf(brocha(listOf(M, L), listOf(0.0 to 0.0, 90.0 to 90.0))),
            emptyList(),
            // Una en la esquina de arriba a la izquierda y otra en la de abajo a la derecha.
            listOf(foto(0.0, 0.0, 10.0), foto(80.0, 80.0, 10.0)),
            0, false
        )
        val p = PlanoEnPantalla.de(pl, 1000.0)   // el papel se estira a mil unidades
        val cerca = Contador()
        p.pintar(cerca, Bounds(0.0, 0.0, 200.0, 200.0), 1.0)
        assertEquals("solo la de esa esquina", 1, cerca.fotos)
        val todo = Contador()
        p.pintar(todo, Bounds(0.0, 0.0, 1000.0, 1000.0), 1.0)
        assertEquals("mirando la página entera, las dos", 2, todo.fotos)
    }

    /**
     * **Un plano corriente se pinta entero por lejos que se mire.**
     *
     * El nivel basto tira las rayas cortas, y en el plano del usuario eso era **el 53 %**: casi
     * la mitad del dibujo desaparecía al abrirlo. Sueltas son detalles de menos de un píxel; a
     * cientos son el rayado y las curvas, o sea lo que hace que un plano parezca un plano.
     * Abaratar solo tiene sentido cuando hay rayas de sobra. Ver [PlanoEnPantalla].
     */
    @Test
    fun `un plano pequeno no pierde rayas al mirarlo de lejos`() {
        // Rayas cortas, de las que el nivel basto tiraría.
        val cortas = (0 until 50).map {
            brocha(listOf(M, L), listOf(it * 0.4 to 0.0, it * 0.4 + 0.2 to 0.3))
        }
        val pl = PlanoDePdf.Plano(100.0, 100.0, emptyList(), cortas, emptyList(), emptyList(), 0, false)
        val p = PlanoEnPantalla.de(pl, 100.0)
        val c = Contador()
        // Un aumento diminuto: la página entera en un sello, que es lo más lejos que se mira.
        p.pintar(c, Bounds(-500.0, -500.0, 600.0, 600.0), 0.02)
        assertEquals("no se ha quedado ninguna fuera", 50, c.segmentos)
    }

    /**
     * **Las tandas se reparten por sitio, no por orden de dibujo.**
     *
     * Se partían cada tantas mil rayas seguidas, y en un plano mil rayas seguidas del archivo
     * van repartidas por toda la hoja: la caja de esa tanda es la hoja entera y el descarte no
     * descarta. Medido en el plano del usuario (9-sep-2026), mirando el 10 % de la página se
     * recorrían 5.888 rayas de 6.366; repartidas por zonas, 988. Ver [PlanoEnPantalla].
     */
    @Test
    fun `acercarse a un rincon no recorre el plano entero`() {
        // Una rejilla de rayitas repartidas por toda la hoja, en el orden en que las escribiría
        // un archivo: fila por fila, que es justo lo que engañaba al reparto por orden.
        val muchas = (0 until 40).flatMap { fila ->
            (0 until 40).map { col ->
                brocha(listOf(M, L), listOf(col * 2.5 to fila * 2.5, col * 2.5 + 1.0 to fila * 2.5 + 1.0))
            }
        }
        val pl = PlanoDePdf.Plano(100.0, 100.0, emptyList(), muchas, emptyList(), emptyList(), 0, false)
        val p = PlanoEnPantalla.de(pl, 100.0)
        val entera = Contador()
        p.pintar(entera, Bounds(0.0, 0.0, 100.0, 100.0), 1.0)
        assertEquals("mirándolo entero se pintan todas", 1600, entera.segmentos)
        val rincon = Contador()
        p.pintar(rincon, Bounds(0.0, 0.0, 10.0, 10.0), 10.0)
        assertTrue(
            "un rincón del 1 % no puede costar como la hoja entera (fueron ${rincon.segmentos})",
            rincon.segmentos < entera.segmentos / 4
        )
        assertTrue("y tiene que pintar lo que hay ahí", rincon.segmentos > 0)
    }

}
