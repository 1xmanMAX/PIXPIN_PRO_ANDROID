package com.forge.pixpin.motor

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El volumen orientado en el espacio: **la luz, las formas nuevas y los dos anillos**.
 *
 * Lo que se comprueba aquí es que generalizar la caja —de cuatro lados a los que hagan
 * falta, de recta a volcada, de tres tonos fijos a una luz— no le haya cambiado nada a la
 * caja de siempre. Esa es la prueba que importa: lo nuevo tiene que salir gratis.
 */
class Volumen3DTest {

    private val tol = 1e-9

    private fun pieza(
        ancho: Double = 60.0, fondo: Double = 40.0, alto: Double = 50.0,
        cota: Double = 0.0, giro: Double = 0.0, vuelco: Double = 0.0,
        forma: FormaDeSolido = FormaDeSolido.CAJA
    ) = Solido(0.0, 0.0, ancho, fondo, alto, cota, giro, vuelco, forma)

    // ---- La luz reproduce el reparto de siempre ----

    /**
     * Las tres caras de una caja de pie dan exactamente `+PASO`, `0` y `−PASO`, que es lo
     * que daba la tabla de tres tonos. Si esto se mueve, todos los croquis ya dibujados
     * cambian de aspecto.
     */
    @Test
    fun `la luz da los mismos tres tonos que la tabla`() {
        for (v in Vista.entries) {
            val caras = carasDeSolido(pieza(), v, PASO_DEL_SOLIDO)
            val tapa = caras.first { it.cara == Cara.TAPA }.claridad
            val paredes = caras.filter { it.cara != Cara.TAPA }.map { it.claridad }.sorted()
            assertEquals("vista $v", PASO_DE_CLARIDAD, tapa, 1e-9)
            assertEquals("vista $v", -PASO_DE_CLARIDAD, paredes[0], 1e-9)
            assertEquals("vista $v", 0.0, paredes[1], 1e-9)
        }
    }

    /** Y una cara sin superficie —la que la cuña se come— no tiene tono que dar. */
    @Test
    fun `una cara degenerada no ilumina`() {
        assertEquals(0.0, claridadDeNormal(Pt3(0.0, 0.0, 0.0), Vista.CERO), tol)
    }

    /** El cilindro sí saca partido: sus lados no dan tres tonos, dan un degradado. */
    @Test
    fun `el cilindro se sombrea en degradado`() {
        val caras = carasDeSolido(pieza(forma = FormaDeSolido.CILINDRO), Vista.CERO, PASO_DEL_SOLIDO)
        val tonos = caras.filter { it.cara != Cara.TAPA }.map { it.claridad }.distinct()
        assertTrue("tonos distintos: ${tonos.size}", tonos.size > 3)
    }

    // ---- Las formas nuevas ----

    /** Un cilindro tiene tantos lados como se le pidieron, más tapa y base. */
    @Test
    fun `el cilindro tiene sus lados`() {
        val todas = carasDeSolido(
            pieza(forma = FormaDeSolido.CILINDRO), Vista.CERO, PASO_DEL_SOLIDO,
            soloVisibles = false
        )
        assertEquals(LADOS_DEL_CILINDRO + 2, todas.size)
    }

    /** Y el prisma, tres. */
    @Test
    fun `el prisma triangular tiene tres lados`() {
        val todas = carasDeSolido(
            pieza(forma = FormaDeSolido.PRISMA), Vista.CERO, PASO_DEL_SOLIDO,
            soloVisibles = false
        )
        assertEquals(5, todas.size)
    }

    /** Las plantas redondas caben dentro de la huella que se les dio. */
    @Test
    fun `la planta redonda cabe en su huella`() {
        for (forma in listOf(FormaDeSolido.CILINDRO, FormaDeSolido.PRISMA)) {
            val v = verticesDe(pieza(forma = forma))
            assertTrue(forma.toString(), v.all { it.x >= -tol && it.x <= 60.0 + tol })
            assertTrue(forma.toString(), v.all { it.y >= -tol && it.y <= 40.0 + tol })
        }
    }

    /** La caja sigue siendo su rectángulo exacto, no una elipse inscrita. */
    @Test
    fun `la caja conserva sus esquinas`() {
        val v = verticesDe(pieza())
        assertEquals(Pt3(0.0, 0.0, 0.0), v[0])
        assertEquals(Pt3(60.0, 0.0, 0.0), v[1])
        assertEquals(Pt3(60.0, 40.0, 0.0), v[2])
        assertEquals(Pt3(0.0, 40.0, 0.0), v[3])
    }

    // ---- Volcarla ----

    /** Volcada media vuelta, lo que estaba arriba queda debajo del suelo del origen. */
    @Test
    fun `volcarla baja la tapa`() {
        val recta = verticesDe(pieza(vuelco = 0.0))
        val tumbada = verticesDe(pieza(vuelco = PI / 2))
        assertEquals(50.0, recta[4].z, tol)
        assertEquals(0.0, tumbada[4].z, 1e-9)
    }

    /** Volcar no estira: las aristas siguen midiendo lo mismo. */
    @Test
    fun `volcar no cambia lo que mide`() {
        fun arista(v: List<Pt3>) = kotlin.math.sqrt(
            (v[4].x - v[0].x) * (v[4].x - v[0].x) +
                (v[4].y - v[0].y) * (v[4].y - v[0].y) +
                (v[4].z - v[0].z) * (v[4].z - v[0].z)
        )
        assertEquals(arista(verticesDe(pieza())), arista(verticesDe(pieza(vuelco = 0.7))), 1e-9)
    }

    /**
     * Y la huella de una pieza volcada deja de ser su planta: es lo que taparía el sol.
     *
     * Volcándola hacia atrás, lo que estaba de pie se echa por detrás del origen —la
     * huella se sale por `y` negativa, donde la planta no llegaba nunca— y a la vez el
     * fondo se acorta, porque parte de él se ha ido a lo alto. Ninguna de las dos cosas
     * la dice el rectángulo de la planta, que es justo el motivo de calcular la
     * envolvente.
     */
    @Test
    fun `la huella de una pieza volcada es su envolvente`() {
        val recta = huellaEnElSuelo(pieza())
        val volcada = huellaEnElSuelo(pieza(vuelco = PI / 4))
        assertTrue(volcada.size >= 3)
        assertEquals(0.0, recta.minOf { it.y }, tol)
        assertTrue("mínimo ${volcada.minOf { it.y }}", volcada.minOf { it.y } < -1.0)
        assertTrue("fondo ${volcada.maxOf { it.y }}", volcada.maxOf { it.y } < 40.0)
    }

    /** Tocarla sigue funcionando: el centro de la huella la coge. */
    @Test
    fun `la sombra volcada se toca donde se ve`() {
        val s = pieza(vuelco = PI / 4)
        val huella = huellaEnElSuelo(s)
        val cx = huella.sumOf { it.x } / huella.size
        val cy = huella.sumOf { it.y } / huella.size
        val pantalla = proyectar(cx, cy, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        assertTrue(tocaLaSombra(s, pantalla, Vista.CERO, PASO_DEL_SOLIDO))
    }

    // ---- Los anillos ----

    /** El anillo de planta se ve achatado, y el de vuelco, de canto: no son el mismo. */
    @Test
    fun `los dos anillos se dibujan distintos`() {
        val centro = Pt3(0.0, 0.0, 0.0)
        val plano = anilloDelVolumen(centro, 50.0, EjeDeGiro.EN_PLANTA, Vista.CERO)
        val canto = anilloDelVolumen(centro, 50.0, EjeDeGiro.VOLCADO, Vista.CERO)
        val anchoPlano = plano.maxOf { it.x } - plano.minOf { it.x }
        val anchoCanto = canto.maxOf { it.x } - canto.minOf { it.x }
        assertTrue("$anchoPlano vs $anchoCanto", anchoPlano > anchoCanto)
    }

    /** El ángulo se lee **en el plano del anillo**: dar la vuelta entera son 2π. */
    @Test
    fun `el angulo del anillo es el del mundo`() {
        val centro = Pt3(0.0, 0.0, 0.0)
        for (grados in listOf(0.0, PI / 4, PI / 2, PI)) {
            val enElMundo = Pt3(50 * kotlin.math.cos(grados), 50 * kotlin.math.sin(grados), 0.0)
            val pantalla = proyectar(enElMundo.x, enElMundo.y, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
            val leido = anguloEnElAnillo(centro, EjeDeGiro.EN_PLANTA, pantalla, Vista.CERO)
            assertNotNull(leido)
            assertEquals(grados, leido!!, 1e-6)
        }
    }

    /** Girar un conjunto lo gira **como un bloque**: las piezas no se separan. */
    @Test
    fun `girar dos piezas juntas conserva su distancia`() {
        val a = newElement(ElementType.SOLIDO, 100.0, 100.0, ItemStyle(), 40.0, 40.0)
            .copy(altura = 40.0)
        val b = newElement(ElementType.SOLIDO, 300.0, 160.0, ItemStyle(), 40.0, 40.0)
            .copy(altura = 40.0)
        val (centro, _) = centroDeVolumenes(listOf(a, b), Vista.CERO)!!
        fun mundo(e: Element) = desproyectar(e.x, e.y, Vista.CERO, PASO_DEL_SOLIDO)
        val antes = mundo(a).let { pa ->
            mundo(b).let { pb -> kotlin.math.hypot(pb.x - pa.x, pb.y - pa.y) }
        }
        val ga = giradoEnElEspacio(a, centro, PI / 2, EjeDeGiro.EN_PLANTA, Vista.CERO)
        val gb = giradoEnElEspacio(b, centro, PI / 2, EjeDeGiro.EN_PLANTA, Vista.CERO)
        val despues = mundo(ga).let { pa ->
            mundo(gb).let { pb -> kotlin.math.hypot(pb.x - pa.x, pb.y - pa.y) }
        }
        assertEquals(antes, despues, 1e-6)
        // Y cada una queda girada sobre sí misma lo mismo que el conjunto.
        assertEquals(PI / 2, ga.giroEnPlanta, 1e-9)
        assertEquals(PI / 2, gb.giroEnPlanta, 1e-9)
    }

    // ---- El esqueleto ----

    /** En alambre se ven **todas** las aristas, no solo las tres que no tapa la pieza. */
    @Test
    fun `el alambre enseña las doce aristas`() {
        val e = newElement(ElementType.SOLIDO, 0.0, 0.0, ItemStyle(), 60.0, 40.0)
            .copy(altura = 50.0)
        assertEquals(12, aristasDeElemento(e, Vista.CERO).size)
        assertEquals(3, aristasOcultasDeElemento(e, Vista.CERO).size)
    }

    // ---- El torno ----

    /** Un perfil se convierte en anillos: uno por punto, y de abajo arriba. */
    @Test
    fun `el torno hace un anillo por punto del perfil`() {
        val s = pieza(forma = FormaDeSolido.REVOLUCION).copy(
            perfil = listOf(Pt(1.0, 0.0), Pt(0.5, 0.5), Pt(1.0, 1.0))
        )
        val anillos = anillosDe(s)
        assertEquals(3, anillos.size)
        assertTrue(anillos.all { it.size == LADOS_DEL_CILINDRO })
        // El de en medio es más estrecho: eso es la cintura del jarrón.
        fun radio(a: List<Pt3>) = a.maxOf { it.x } - a.minOf { it.x }
        assertTrue(radio(anillos[1]) < radio(anillos[0]))
    }

    /**
     * **Y un perfil a mano alzada no trae doscientos anillos.**
     *
     * Cada punto es un anillo y entre cada dos va una tira de caras: sin tope, un garabato
     * de doscientos puntos son casi cinco mil caras que repartir y pintar en cada
     * fotograma. La pieza saldría igual y la aplicación se arrastraría.
     */
    @Test
    fun `el perfil se acota`() {
        val muchos = (0..200).map { Pt(0.5 + 0.4 * kotlin.math.sin(it / 8.0), it / 200.0) }
        val s = pieza(forma = FormaDeSolido.REVOLUCION).copy(perfil = muchos)
        assertTrue("${anillosDe(s).size}", anillosDe(s).size <= MAXIMOS_ANILLOS)
        // Y las puntas se conservan: son las que dicen dónde empieza y acaba.
        val anillos = anillosDe(s)
        assertEquals(0.0, anillos.first().minOf { it.z }, 1e-9)
        assertEquals(50.0, anillos.last().maxOf { it.z }, 1e-9)
    }

    /** El radio cero cierra la pieza en punta en vez de dejar un agujero. */
    @Test
    fun `el radio cero hace una punta`() {
        val s = pieza(forma = FormaDeSolido.REVOLUCION).copy(
            perfil = listOf(Pt(1.0, 0.0), Pt(0.0, 1.0))
        )
        val arriba = anillosDe(s).last()
        assertEquals(0.0, arriba.maxOf { it.x } - arriba.minOf { it.x }, 1e-9)
    }

    // ---- Qué aristas se dibujan ----

    /**
     * **Un cilindro no es una persiana.** Con veinticuatro caras planas, repasar el borde
     * de cada una son veinticuatro rayas verticales sobre una superficie que no tiene
     * ninguna: se queda solo con su silueta y sus dos bocas.
     */
    @Test
    fun `un cilindro no enseña sus lados`() {
        fun verticales(s: Solido) = aristasMarcadasDeSolido(s, Vista.CERO, PASO_DEL_SOLIDO)
            .count { kotlin.math.abs(it.second.x - it.first.x) < 1e-6 }

        // Las verticales del costado son las que se veían como una persiana: entre dos
        // caras seguidas no hay quiebro, así que en un tubo redondo solo quedan las dos
        // de la silueta. Las del borde de la tapa **sí** se dibujan: ahí hay un canto de
        // verdad, y es lo que hace que se vea la boca del tubo.
        val redondo = pieza(ancho = 60.0, fondo = 60.0, forma = FormaDeSolido.CILINDRO)
        assertEquals(2, verticales(redondo))

        // Achatado ya no es un tubo: la curva se cierra más por las puntas y ahí sí hay
        // quiebro. Salen unas pocas, no las veinticuatro.
        val ovalado = pieza(ancho = 60.0, fondo = 40.0, forma = FormaDeSolido.CILINDRO)
        assertTrue("${verticales(ovalado)}", verticales(ovalado) < LADOS_DEL_CILINDRO / 3)
    }

    /** Y un cubo sí: sus aristas son quiebros de verdad, y se ven las nueve de delante. */
    @Test
    fun `un cubo conserva sus aristas`() {
        val marcadas = aristasMarcadasDeSolido(pieza(), Vista.CERO, PASO_DEL_SOLIDO)
        assertEquals(9, marcadas.size)
    }

    /** La silueta siempre está: es el borde del bulto contra el fondo. */
    @Test
    fun `la silueta de un cilindro se dibuja`() {
        val marcadas = aristasMarcadasDeSolido(
            pieza(forma = FormaDeSolido.CILINDRO), Vista.CERO, PASO_DEL_SOLIDO
        )
        assertTrue(marcadas.isNotEmpty())
        // Y llega de arriba abajo del dibujo: si solo saliera la tapa, no sería silueta.
        val alto = marcadas.flatMap { listOf(it.first.y, it.second.y) }
        assertTrue((alto.max() - alto.min()) > 40.0)
    }

    // ---- Levantar cualquier figura cerrada ----

    /**
     * Un triángulo levantado da **una barra triangular**, y no porque exista un caso
     * «triángulo»: la planta del volumen es el contorno del dibujo, sea cual sea.
     */
    @Test
    fun `levantar un triangulo da tres lados`() {
        val triangulo = newElement(ElementType.LINE, 0.0, 0.0, ItemStyle())
            .copy(points = listOf(Pt(0.0, 0.0), Pt(60.0, 0.0), Pt(30.0, 50.0), Pt(0.0, 0.0)))
        val cuerpo = extruido(triangulo, Vista.CERO)
        assertNotNull(cuerpo)
        assertEquals(FormaDeSolido.EXTRUSION, cuerpo!!.formaSolida)
        val todas = carasDeSolido(solidoDe(cuerpo), Vista.CERO, PASO_DEL_SOLIDO, soloVisibles = false)
        assertEquals("planta=${cuerpo.planta}", 5, todas.size)
    }

    /** Y un rectángulo da un cubo de cuatro caras, como siempre. */
    @Test
    fun `levantar un rectangulo da cuatro lados`() {
        val caja = newElement(ElementType.RECTANGLE, 0.0, 0.0, ItemStyle(), 60.0, 40.0)
        val cuerpo = extruido(caja, Vista.CERO)!!
        val todas = carasDeSolido(solidoDe(cuerpo), Vista.CERO, PASO_DEL_SOLIDO, soloVisibles = false)
        assertEquals("planta=${cuerpo.planta}", 6, todas.size)
    }

    /** La planta se guarda en tanto por uno: estirar la pieza la estira con ella. */
    @Test
    fun `la planta guardada se estira con la pieza`() {
        val caja = newElement(ElementType.RECTANGLE, 0.0, 0.0, ItemStyle(), 60.0, 40.0)
        val cuerpo = extruido(caja, Vista.CERO)!!
        assertTrue(cuerpo.planta!!.all { it.x in -0.001..1.001 && it.y in -0.001..1.001 })
        val doble = cuerpo.copy(width = 120.0)
        val v = verticesDe(solidoDe(doble))
        assertEquals(120.0, v.maxOf { it.x } - v.minOf { it.x }, 1e-6)
    }

    /** Un contorno enorme no se cuela: cada punto de la planta es una cara. */
    @Test
    fun `la planta se acota`() {
        val garabato = newElement(ElementType.FREEDRAW, 0.0, 0.0, ItemStyle()).copy(
            points = (0..300).map {
                val t = 2 * PI * it / 300
                Pt(50 + 50 * kotlin.math.cos(t), 50 + 50 * kotlin.math.sin(t))
            }
        )
        val cuerpo = extruido(garabato, Vista.CERO)
        if (cuerpo != null) {
            assertTrue("${cuerpo.planta?.size}", (cuerpo.planta?.size ?: 0) <= MAXIMOS_LADOS)
        }
    }

    // ---- Levantar un contorno hecho de rayas sueltas ----

    private fun raya(desde: Pt, hasta: Pt) =
        newElement(ElementType.LINE, desde.x, desde.y, ItemStyle())
            .copy(points = listOf(Pt(0.0, 0.0), Pt(hasta.x - desde.x, hasta.y - desde.y)))

    /**
     * **Cuatro rectas que se tocan son un cuadrado**, aunque sean cuatro elementos.
     *
     * Es como se dibuja una planta en cualquier programa de ingeniería, y antes no se
     * podía levantar: había que redibujarla con la herramienta de rectángulo.
     */
    @Test
    fun `cuatro rayas que cierran dan un aro`() {
        val aro = aroDeRayas(
            listOf(
                raya(Pt(0.0, 0.0), Pt(100.0, 0.0)),
                raya(Pt(100.0, 0.0), Pt(100.0, 80.0)),
                raya(Pt(100.0, 80.0), Pt(0.0, 80.0)),
                raya(Pt(0.0, 80.0), Pt(0.0, 0.0))
            )
        )
        assertNotNull(aro)
        assertEquals(4, aro!!.size)
    }

    /** Da igual el orden en que estén ni hacia dónde se dibujó cada una. */
    @Test
    fun `el aro se encuentra en cualquier orden`() {
        val aro = aroDeRayas(
            listOf(
                raya(Pt(100.0, 80.0), Pt(0.0, 80.0)),
                raya(Pt(0.0, 0.0), Pt(100.0, 0.0)),
                raya(Pt(0.0, 0.0), Pt(0.0, 80.0)),
                raya(Pt(100.0, 80.0), Pt(100.0, 0.0))
            )
        )
        assertNotNull(aro)
        assertEquals(4, aro!!.size)
    }

    /** Y a mano nadie cierra al píxel: un hueco de unos pocos se perdona. */
    @Test
    fun `un hueco pequeño no rompe el aro`() {
        val aro = aroDeRayas(
            listOf(
                raya(Pt(0.0, 0.0), Pt(100.0, 0.0)),
                raya(Pt(100.0, 2.0), Pt(100.0, 80.0)),
                raya(Pt(100.0, 80.0), Pt(0.0, 80.0)),
                raya(Pt(0.0, 78.0), Pt(3.0, 0.0))
            )
        )
        assertNotNull(aro)
    }

    /** Pero un contorno abierto no se cierra a la fuerza: no había figura que levantar. */
    @Test
    fun `un contorno abierto no da aro`() {
        assertNull(
            aroDeRayas(
                listOf(
                    raya(Pt(0.0, 0.0), Pt(100.0, 0.0)),
                    raya(Pt(100.0, 0.0), Pt(100.0, 80.0))
                )
            )
        )
    }

    /** Ni una raya suelta metida en la selección: eso ya no era un contorno. */
    @Test
    fun `una raya suelta invalida el aro`() {
        assertNull(
            aroDeRayas(
                listOf(
                    raya(Pt(0.0, 0.0), Pt(100.0, 0.0)),
                    raya(Pt(100.0, 0.0), Pt(0.0, 0.0)),
                    raya(Pt(900.0, 900.0), Pt(950.0, 950.0))
                )
            )
        )
    }

    /** Y el aro levantado es un volumen con esos mismos lados. */
    @Test
    fun `el aro levantado tiene los lados del contorno`() {
        val cuerpo = extruidoDeRayas(
            listOf(
                raya(Pt(0.0, 0.0), Pt(100.0, 0.0)),
                raya(Pt(100.0, 0.0), Pt(50.0, 80.0)),
                raya(Pt(50.0, 80.0), Pt(0.0, 0.0))
            ),
            Vista.CERO
        )
        assertNotNull(cuerpo)
        assertEquals(FormaDeSolido.EXTRUSION, cuerpo!!.formaSolida)
        assertEquals(3, cuerpo.planta!!.size)
    }

    // ---- Tornear alrededor de una raya ----

    /**
     * **Media circunferencia junto a una raya es una esfera.** Es la prueba que dice que
     * el torno lee la silueta y no el trazo: de todos los puntos que caen a la misma
     * altura manda el más lejano del eje, así que el hueco de dentro se llena.
     */
    @Test
    fun `media circunferencia sobre un eje da una esfera`() {
        val radio = 50.0
        val media = newElement(ElementType.FREEDRAW, 0.0, 0.0, ItemStyle()).copy(
            points = (0..24).map {
                val t = -PI / 2 + PI * it / 24
                Pt(100 + radio * kotlin.math.cos(t), 100 + radio * kotlin.math.sin(t))
            }
        )
        val eje = newElement(ElementType.LINE, 0.0, 0.0, ItemStyle())
            .copy(points = listOf(Pt(100.0, 50.0), Pt(100.0, 150.0)))
        val cuerpo = revolucionado(media, eje, Vista.CERO)
        assertNotNull(cuerpo)
        assertEquals(cuerpo!!.width, cuerpo.altura!!, 2.0)
        val perfil = cuerpo.points!!
        assertTrue(perfil.first().x < 0.3)
        assertTrue(perfil.last().x < 0.3)
        assertTrue(perfil.maxOf { it.x } > 0.9)
    }

    /** Un rectángulo pegado a una raya da un cilindro: radio constante. */
    @Test
    fun `un rectangulo sobre un eje da un cilindro`() {
        val rect = newElement(ElementType.RECTANGLE, 100.0, 50.0, ItemStyle(), 40.0, 100.0)
        val eje = newElement(ElementType.LINE, 0.0, 0.0, ItemStyle())
            .copy(points = listOf(Pt(100.0, 50.0), Pt(100.0, 150.0)))
        val cuerpo = revolucionado(rect, eje, Vista.CERO)!!
        val perfil = cuerpo.points!!
        assertTrue("${perfil.map { it.x }}", perfil.all { it.x > 0.9 })
    }

    /** Sin eje sigue valiendo lo de antes: el propio trazo es el perfil. */
    @Test
    fun `sin eje se tornea el trazo`() {
        val perfil = newElement(ElementType.LINE, 0.0, 0.0, ItemStyle())
            .copy(points = listOf(Pt(0.0, 0.0), Pt(30.0, 50.0)))
        assertNotNull(revolucionado(perfil, null, Vista.CERO))
    }

    // ---- La imagen tumbada ----

    /** Tumbada, la imagen ocupa el paralelogramo del suelo y no su rectángulo. */
    @Test
    fun `la imagen del suelo es un paralelogramo`() {
        val e = newElement(ElementType.IMAGE, 100.0, 100.0, ItemStyle(), 80.0, 60.0)
            .copy(enElSuelo = true)
        val esquinas = imagenEnElSuelo(e, Vista.CERO)
        assertEquals(4, esquinas.size)
        // La esquina de enfrente cae donde dice la proyección, no en (x+w, y+h).
        val esperada = proyectar(80.0, 60.0, 0.0, Vista.CERO, PASO_DEL_SOLIDO)
        assertEquals(100.0 + esperada.x, esquinas[2].x, tol)
        assertEquals(100.0 + esperada.y, esquinas[2].y, tol)
        // Y su envoltura no es la caja del elemento.
        val b = envolturaVisible(e, Vista.CERO)
        assertTrue(abs(b.width - 80.0) > 1.0)
    }
}
