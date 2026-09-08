package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La política de la tasa de refresco, probada sin teléfono.**
 *
 * Lo único que se puede comprobar aquí es la decisión: si con estos fps y en este instante
 * toca subir, bajar o quedarse. Que la pantalla obedezca —que 120 Hz sean 120 Hz— no se ve
 * desde la JVM, y tampoco desde Robolectric, que no rasteriza nada.
 *
 * Por eso [PoliticaDeFluidez] no toca Android: es justo la mitad del problema que sí se
 * puede dejar clavada aquí. Ver [com.forge.pixpin.motor.PantallaFluida].
 */
class PantallaFluidaTest {

    /** Llena la memoria entera con fotogramas de este coste, para que la media sea esa. */
    private fun PoliticaDeFluidez.aEstosFps(fps: Float) = aEsteCoste((1_000_000_000.0 / fps).toLong())

    /** Lo mismo, dando el coste en nanosegundos: hace falta para apuntar al filo exacto. */
    private fun PoliticaDeFluidez.aEsteCoste(ns: Long) {
        repeat(numeros.memoriaDeFotogramas) { anota(ns) }
    }

    @Test
    fun `la media sale de lo que cuesta cada fotograma`() {
        val politica = PoliticaDeFluidez()
        assertEquals(0f, politica.fpsMedios, 0.001f)
        politica.aEstosFps(50f)
        assertEquals(50f, politica.fpsMedios, 0.5f)
        // Una duración imposible no cuenta: el sistema entrega alguna a cero al arrancar,
        // y una sola bastaría para desviar la media entera.
        politica.anota(0L)
        politica.anota(-5L)
        assertEquals(50f, politica.fpsMedios, 0.5f)
    }

    /** Sin fotogramas suficientes no se opina: `RefreshRateController.java:162`. */
    @Test
    fun `no decide nada mientras calienta`() {
        val politica = PoliticaDeFluidez()
        val lento = (1_000_000_000.0 / 20f).toLong()
        repeat(politica.numeros.fotogramasDeCalentamiento - 1) { politica.anota(lento) }
        assertTrue(politica.calentando)
        // Aunque hayan pasado horas con la media por los suelos.
        assertNull(politica.decide(0))
        assertNull(politica.decide(3_600_000))
        assertEquals(TasaDePantalla.MAXIMA, politica.tasa)

        politica.anota(lento)
        assertFalse(politica.calentando)
        assertNull(politica.decide(10_000))          // aquí empieza la racha
        assertEquals(TasaDePantalla.SESENTA, politica.decide(11_800))
    }

    /**
     * **Baja cuando los fps son malos, pero solo si aguantan.**
     *
     * La ventana estable son 1800 ms (`RefreshRateController.java:48`): un bajón suelto
     * —un vídeo que se decodifica, el teclado que aparece— no debe costar la tasa alta.
     */
    @Test
    fun `baja con fps malos sostenidos`() {
        val politica = PoliticaDeFluidez()
        politica.aEstosFps(50f)  // 20 ms por fotograma: no cabe ni en 60 Hz

        assertNull(politica.decide(0))
        assertNull(politica.decide(1_000))
        assertNull(politica.decide(1_799))
        assertEquals(TasaDePantalla.SESENTA, politica.decide(1_800))
        assertEquals(TasaDePantalla.SESENTA, politica.tasa)
        // Y una vez bajada, no vuelve a anunciar lo mismo cada fotograma.
        assertNull(politica.decide(1_801))
    }

    /** Un respiro rompe la racha: no se acumulan bajones sueltos separados en el tiempo. */
    @Test
    fun `un bajon suelto no baja la tasa`() {
        val politica = PoliticaDeFluidez()
        politica.aEstosFps(50f)
        assertNull(politica.decide(0))       // empieza la racha
        assertNull(politica.decide(1_000))

        politica.aEstosFps(90f)              // vuelve a ir sobrado
        assertNull(politica.decide(1_100))   // la racha se corta aquí
        politica.aEstosFps(50f)
        assertNull(politica.decide(1_200))   // y empieza de cero
        assertNull(politica.decide(2_900))   // 1700 ms: todavía no
        assertEquals(TasaDePantalla.SESENTA, politica.decide(3_000))
    }

    /**
     * **Sube cuando sobra margen, y no antes de que pasen 3 s del último cambio.**
     *
     * Los 3000 ms son `RefreshRateController.java:51`. Sin ese suelo, una pantalla que
     * ande justo al filo cambiaría de tasa cada 1,8 s, y cada cambio de tasa se ve.
     */
    @Test
    fun `sube con fps buenos y respetando el minimo entre cambios`() {
        val politica = PoliticaDeFluidez()

        politica.aEstosFps(50f)
        assertNull(politica.decide(0))
        assertEquals(TasaDePantalla.SESENTA, politica.decide(1_800))  // último cambio: 1800

        politica.aEstosFps(90f)                     // 11 ms por fotograma: cabe de sobra
        assertNull(politica.decide(2_000))          // empieza la racha alta
        // Racha cumplida (1900 ms) pero solo han pasado 2100 ms desde el cambio: se espera.
        assertNull(politica.decide(3_900))
        assertEquals(TasaDePantalla.SESENTA, politica.tasa)
        assertNull(politica.decide(4_799))
        assertEquals(TasaDePantalla.MAXIMA, politica.decide(4_800))
    }

    /**
     * **La histéresis: entre 55 y 58,5 no se toca nada.**
     *
     * Es lo que impide el pingpong. Con un solo umbral, una pantalla que rondara ese
     * punto —lo normal en un croquis con muchas líneas— estaría cambiando de tasa cada dos
     * segundos para siempre. Los dos números son `RefreshRateController.java:58` y `:59`.
     */
    @Test
    fun `en la banda de histeresis no cambia en ningun sentido`() {
        // Yendo a tope: 57 fps no es «malo» todavía, así que no baja.
        val arriba = PoliticaDeFluidez()
        arriba.aEstosFps(57f)
        for (t in 0L..60_000L step 500L) assertNull(arriba.decide(t))
        assertEquals(TasaDePantalla.MAXIMA, arriba.tasa)

        // Y yendo a 60: 57 fps tampoco es «bueno», así que no sube.
        val abajo = PoliticaDeFluidez()
        abajo.aEstosFps(50f)
        abajo.decide(0)
        assertEquals(TasaDePantalla.SESENTA, abajo.decide(1_800))
        abajo.aEstosFps(57f)
        for (t in 2_000L..60_000L step 500L) assertNull(abajo.decide(t))
        assertEquals(TasaDePantalla.SESENTA, abajo.tasa)
    }

    /**
     * Justo en el filo: 55 baja (la comparación es `<=`) y 58,5 sube (es `>=`).
     *
     * Se dan los nanosegundos a mano y no los fps porque redondear 1/55 s hacia abajo deja
     * la media un pelo **por encima** de 55, que es justo el otro lado del filo.
     */
    @Test
    fun `los umbrales entran por el borde`() {
        val justo = PoliticaDeFluidez()
        justo.aEsteCoste(18_181_819L)   // 54,99999 fps: entra por «malo»
        assertNull(justo.decide(0))
        assertEquals(TasaDePantalla.SESENTA, justo.decide(1_800))

        justo.aEsteCoste(17_094_017L)   // 58,50000 fps: entra por «bueno»
        assertNull(justo.decide(2_000))
        assertEquals(TasaDePantalla.MAXIMA, justo.decide(4_800))
    }

    /**
     * Al dejar de mirar una pantalla se olvida lo medido, pero no la tasa que se pidió.
     *
     * Lo que costaba dibujar en la sesión anterior no dice nada de esta; arrastrarlo haría
     * que la primera decisión al volver se tomara con datos de otro sitio.
     */
    @Test
    fun `olvidar borra el historial y no la tasa`() {
        val politica = PoliticaDeFluidez()
        politica.aEstosFps(50f)
        politica.decide(0)
        assertEquals(TasaDePantalla.SESENTA, politica.decide(1_800))

        politica.olvida()
        assertEquals(TasaDePantalla.SESENTA, politica.tasa)
        assertEquals(0f, politica.fpsMedios, 0.001f)
        assertTrue(politica.calentando)
        assertNull(politica.decide(100_000))
    }

    /**
     * La memoria es un anillo de tamaño fijo: lo viejo se cae solo.
     *
     * Importa porque es lo que hace que esto no reserve memoria por fotograma, que es la
     * norma de la casa; y porque si lo viejo no se cayera, media hora de dibujo lento
     * pesaría lo mismo que el segundo que acaba de pasar.
     */
    @Test
    fun `la memoria olvida lo viejo sin crecer`() {
        val politica = PoliticaDeFluidez(NumerosDeFluidez(memoriaDeFotogramas = 4, fotogramasDeCalentamiento = 4))
        val malo = (1_000_000_000.0 / 10f).toLong()
        val bueno = (1_000_000_000.0 / 120f).toLong()
        repeat(4) { politica.anota(malo) }
        assertEquals(10f, politica.fpsMedios, 0.5f)
        repeat(4) { politica.anota(bueno) }
        assertEquals(120f, politica.fpsMedios, 1f)
    }

    /** Los números de fábrica son los de Telegram; si alguien los mueve, que sea a sabiendas. */
    @Test
    fun `los numeros son los que se citaron`() {
        val n = NumerosDeFluidez()
        assertEquals(1_800L, n.ventanaEstableMs)
        assertEquals(3_000L, n.minimoEntreCambiosMs)
        assertEquals(55f, n.fpsParaBajar, 0.001f)
        assertEquals(58.5f, n.fpsParaSubir, 0.001f)
        assertEquals(30, n.fotogramasDeCalentamiento)
        assertEquals(240, n.memoriaDeFotogramas)
        assertTrue("el hueco de la histéresis no puede ser cero", n.fpsParaSubir > n.fpsParaBajar)
    }
}
