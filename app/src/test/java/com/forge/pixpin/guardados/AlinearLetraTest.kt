package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.AlinearLetra.PalabraOida
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La letra pegada con los tiempos de lo oído** (29-oct-2026): **todas las pruebas de
 * `alinear_letra.rs` del PC** (`origin/main` 50e87978), con los mismos datos y los mismos números,
 * para que el PC y el móvil den los mismos minutos; y detrás las de Android que siguen valiendo.
 */
class AlinearLetraTest {

    /** Lo oído de unas líneas, palabra a palabra desde `desde` ms, a `paso` ms por palabra (`cantar` del PC). */
    private fun cantar(lineas: List<Pair<String, Long>>, paso: Long): MutableList<PalabraOida> {
        val v = ArrayList<PalabraOida>()
        for ((texto, desde) in lineas) AlinearLetra.normalizar(texto).forEachIndexed { k, p -> v += PalabraOida(p, desde + k * paso) }
        return v
    }

    private fun tiempos(a: AlinearLetra.Alineado): List<Long> = a.lineas.map { it.ms }

    private val LETRA = listOf(
        "Bésame, bésame mucho",
        "como si fuera esta noche la última vez",
        "que tengo miedo a perderte",
        "perderte después",
    )

    private fun enOrden(t: List<Long>) = t.zipWithNext().all { (a, b) -> a <= b }

    // --- Las pruebas del PC, una a una ----------------------------------------------------------

    @Test
    fun normalizar_quita_tildes_puntuacion_y_mayusculas() {
        assertEquals(listOf("besame", "mucho"), AlinearLetra.normalizar("¡Bésame, MUCHO!"))
        assertEquals(listOf("cant", "stop", "now"), AlinearLetra.normalizar("can't stop—now"))
        assertEquals(listOf("corazon"), AlinearLetra.normalizar("Corazón (x2)"))
        assertEquals(emptyList<String>(), AlinearLetra.normalizar("  ...  "))
        assertEquals(listOf("ano", "2x2"), AlinearLetra.normalizar("año 2x2"))
    }

    @Test
    fun el_parecido_es_la_distancia_de_edicion() {
        assertEquals(1f, AlinearLetra.parecido("hola", "hola"))
        assertEquals(6f / 7f, AlinearLetra.parecido("corazon", "corason"), 1e-6f)
        assertEquals(0f, AlinearLetra.parecido("abc", "xyz"))
        assertEquals(1f, AlinearLetra.parecido("", ""))
    }

    @Test
    fun lo_oido_igual_que_la_letra_da_el_minuto_de_cada_linea() {
        val oido = cantar(listOf(LETRA[0] to 12_000L, LETRA[1] to 16_000L, LETRA[2] to 22_000L, LETRA[3] to 27_000L), 400)
        val a = AlinearLetra.alinear(LETRA, oido, null)!!
        assertEquals(listOf(12_000L, 16_000L, 22_000L, 27_000L), tiempos(a))
        assertEquals(4, a.ancladas)
        assertTrue(a.lineas.all { it.confianza > 0.95f })
        // Y se guarda con el texto pegado, no con lo oído.
        val guardado = LetraDeCancion.aGuardar(a.letra(LETRA))
        assertTrue(guardado, guardado.startsWith("[0:12] Bésame, bésame mucho\n\n[0:16] como si"))
    }

    @Test
    fun los_errores_del_reconocedor_no_lo_tumban() {
        // Whisper con música: palabras mal oídas, alguna que falta, y una inventada en la intro.
        val oido = cantar(
            listOf(
                "gracias por ver el video" to 1_000L,
                "besame besamé muchos" to 12_000L,
                "como si fuera esta noche la ultima" to 16_000L,
                "tengo midio a perderte" to 22_400L,
                "perder te despues" to 27_000L,
            ), 400
        )
        val a = AlinearLetra.alinear(LETRA, oido, 200_000)!!
        val t = tiempos(a)
        assertEquals(12_000L, t[0])
        assertEquals(16_000L, t[1])
        // «que» no se oyó: el minuto de «tengo», corrido una palabra atrás.
        assertTrue("$t", t[2] in 21_900..22_400)
        assertTrue("$t", t[3] in 26_000..27_500)
        assertTrue(a.lineas[2].anclada)
    }

    @Test
    fun el_estribillo_repetido_casa_con_su_vez() {
        val letra = listOf(
            "en la orilla del mar te conoci",
            "y tu risa me llevo",
            "baila baila conmigo esta noche",
            "que la luna nos mira",
            "otro verano otra ciudad distinta",
            "baila baila conmigo esta noche",
            "que la luna nos mira",
        )
        val oido = cantar(
            listOf(
                letra[0] to 10_000L, letra[1] to 14_000L, letra[2] to 20_000L, letra[3] to 24_000L,
                letra[4] to 40_000L, letra[5] to 50_000L, letra[6] to 54_000L,
            ), 300
        )
        val a = AlinearLetra.alinear(letra, oido, null)!!
        assertEquals(listOf(10_000L, 14_000L, 20_000L, 24_000L, 40_000L, 50_000L, 54_000L), tiempos(a))
    }

    @Test
    fun una_linea_que_no_se_oye_se_reparte_entre_sus_vecinas() {
        val oido = cantar(listOf(LETRA[0] to 12_000L, LETRA[1] to 16_000L, LETRA[3] to 27_000L), 400)
        val a = AlinearLetra.alinear(LETRA, oido, null)!!
        val t = tiempos(a)
        assertFalse(a.lineas[2].anclada)
        assertEquals(0f, a.lineas[2].confianza)
        assertTrue("$t", t[1] < t[2] && t[2] < t[3])
    }

    @Test
    fun los_coros_de_fondo_y_los_yeah_no_mueven_nada() {
        val oido = cantar(
            listOf(
                LETRA[0] to 12_000L,
                "yeah yeah oh oh oh uoh" to 14_000L,
                LETRA[1] to 16_000L,
                "eh eh baby" to 20_000L,
                LETRA[2] to 22_000L,
                LETRA[3] to 27_000L,
                "uh uh uh na na na" to 30_000L,
            ), 400
        )
        val a = AlinearLetra.alinear(LETRA, oido, null)!!
        assertEquals(listOf(12_000L, 16_000L, 22_000L, 27_000L), tiempos(a))
    }

    @Test
    fun el_hueco_instrumental_se_queda_antes_de_la_estrofa_siguiente() {
        val letra = listOf(
            "primera estrofa que se canta",
            "una linea que el reconocedor no pillo",
            "despues del solo de guitarra larguisimo",
        )
        val oido = cantar(listOf(letra[0] to 10_000L, letra[2] to 90_000L), 400)
        val a = AlinearLetra.alinear(letra, oido, null)!!
        val t = tiempos(a)
        // Al ritmo, detrás de la primera, y no a mitad del solo (50 s).
        assertTrue("$t", t[1] > 10_000 && t[1] < 15_000)
        assertEquals(90_000L, t[2])
    }

    @Test
    fun un_rotulo_va_con_la_linea_que_lo_sigue() {
        val letra = listOf("[Coro]", "baila baila conmigo", "[Final]")
        val oido = cantar(listOf("baila baila conmigo" to 5_000L), 300)
        val a = AlinearLetra.alinear(letra, oido, null)!!
        assertEquals(listOf(5_000L, 5_000L, 5_000L), tiempos(a))
        assertFalse(a.lineas[0].anclada)
    }

    @Test
    fun las_lineas_del_final_no_pasan_de_lo_que_dura() {
        val letra = listOf("la primera linea cantada", "segunda", "tercera", "cuarta")
        val oido = cantar(listOf(letra[0] to 170_000L), 300)
        val a = AlinearLetra.alinear(letra, oido, 172_000)!!
        val t = tiempos(a)
        assertTrue("$t", enOrden(t))
        assertTrue("$t", t[3] < 172_000)
    }

    @Test
    fun sin_nada_reconocible_no_se_inventan_tiempos() {
        val oido = cantar(listOf("zzz qqq www" to 1_000L), 300)
        assertNull(AlinearLetra.alinear(LETRA, oido, null))
        assertNull(AlinearLetra.alinear(LETRA, emptyList(), null))
        assertNull(AlinearLetra.alinear(emptyList(), oido, null))
    }

    @Test
    fun las_palabras_oidas_de_las_tandas_reparten_su_tiempo() {
        val s = listOf(Transcriptor.Segmento.entre(10_000, 12_000, "Hola, mundo"))
        assertEquals(listOf(PalabraOida("hola", 10_000), PalabraOida("mundo", 11_000)), AlinearLetra.palabrasOidas(s))
    }

    @Test
    fun se_adivina_el_idioma_de_la_letra() {
        assertEquals("es", AlinearLetra.idiomaDeLaLetra(LETRA))
        val ingles = listOf(
            "I will always love you",
            "and I hope that life treats you kind",
            "you know that I'm not what you need",
        )
        assertEquals("en", AlinearLetra.idiomaDeLaLetra(ingles))
        assertNull(AlinearLetra.idiomaDeLaLetra(listOf("la la la")))
    }

    @Test
    fun las_lineas_salen_siempre_en_orden() {
        // Lo oído desordenado en el tiempo (una tanda mal medida) no puede dar un minuto antes que
        // el de la línea de antes.
        val oido = cantar(listOf(LETRA[0] to 12_000L, LETRA[1] to 16_000L), 400)
        oido += cantar(listOf(LETRA[2] to 15_000L, LETRA[3] to 27_000L), 100)
        val t = tiempos(AlinearLetra.alinear(LETRA, oido, null)!!)
        assertTrue("$t", enOrden(t))
    }

    @Test
    fun la_voz_son_los_tramos_con_palabras_oidas() {
        val v = AlinearLetra.Voz.de(listOf(0L, 1_000L, 2_000L, 10_000L).map { PalabraOida("x", it) })
        assertEquals(listOf(0L to 2_600L, 10_000L to 10_600L), v.tramos)
        assertEquals(3_200L, v.dentro(0, 20_000))
        assertEquals(1_900L, v.dentro(1_000, 10_300))
        // Lo que no se canta (de 2,6 a 10 s) se salta.
        assertEquals(10_400L, v.avanzar(0, 3_000))
        assertNull(v.avanzar(0, 9_000))
        assertEquals(2_200L, v.retroceder(10_600, 1_000))
        assertEquals(10_600L, v.fin())
    }

    @Test
    fun una_estrofa_mal_oida_se_reparte_por_donde_se_canto() {
        // Medido con canciones de verdad (29-oct-2026, PC): entre dos anclas, Whisper oye la estrofa
        // pero mal. Se cantó de 10 a 60 s: las líneas van por ahí, no amontonadas al ritmo.
        val letra = listOf(
            "primera linea que se oye bien clara",
            "segunda linea que nadie entiende",
            "tercera linea que nadie entiende",
            "cuarta linea que nadie entiende",
            "la ultima linea que se oye bien clara",
        )
        val oido = cantar(listOf(letra[0] to 10_000L), 400)
        for (k in 0 until 30) oido += PalabraOida("brrr", 13_000L + k * 1_500L)
        oido += cantar(listOf(letra[4] to 60_000L), 400)
        val t = tiempos(AlinearLetra.alinear(letra, oido, null)!!)
        assertEquals(10_000L to 60_000L, t[0] to t[4])
        assertTrue("$t", t[1] in 20_000..30_000)
        assertTrue("$t", t[2] in 30_000..40_000)
        assertTrue("$t", t[3] in 40_000..52_000)
    }

    @Test
    fun lo_que_whisper_se_invente_en_la_intro_no_adelanta_la_primera_linea() {
        val letra = listOf(
            "la primera linea no se entiende",
            "la segunda tampoco se entiende",
            "esta si se oye perfectamente bien",
            "y esta tambien se oye perfectamente",
        )
        // Una intro de 30 s con palabras inventadas, y se canta desde 40 s.
        val oido = (0 until 20).map { PalabraOida("gracias", it * 1_500L) }.toMutableList()
        oido += cantar(listOf(letra[2] to 50_000L, letra[3] to 54_000L), 500)
        val t = tiempos(AlinearLetra.alinear(letra, oido, null)!!)
        assertTrue("$t", t[0] > 35_000)
        assertTrue("$t", t[0] < t[1] && t[1] < 50_000)
    }

    @Test
    fun el_estribillo_repetido_que_se_oyo_una_vez_va_a_la_vez_que_toca() {
        val estribillo = "ay ay ay canta y no llores que cantando se alegran"
        val letra = listOf("una linea que se oye antes de todo", estribillo, estribillo, "la ultima linea que se oye al final")
        // El estribillo solo se oyó la primera vez, justo detrás de la primera línea.
        val oido = cantar(listOf(letra[0] to 10_000L, estribillo to 13_500L, letra[3] to 30_000L), 400)
        val a = AlinearLetra.alinear(letra, oido, null)!!
        val t = tiempos(a)
        assertTrue("$a", a.lineas[1].anclada && !a.lineas[2].anclada)
        assertTrue("$t", t[1] in 13_000..14_000)
        assertTrue("$t", t[2] > t[1] && t[2] < 30_000)
    }

    // --- Lo de Android ------------------------------------------------------------------------------

    @Test
    fun va_a_ojo_con_menos_de_un_tercio_de_las_lineas_oidas() {
        assertTrue(AlinearLetra.vaAOjo(9, 30))
        assertFalse(AlinearLetra.vaAOjo(10, 30))
        assertFalse(AlinearLetra.vaAOjo(12, 30))
        assertTrue(AlinearLetra.vaAOjo(0, 1))
        assertFalse(AlinearLetra.vaAOjo(1, 3))
        assertFalse(AlinearLetra.vaAOjo(0, 0))
    }

    @Test
    fun el_idioma_de_la_letra_manda_y_si_no_el_de_ajustes_con_su_segundo() {
        val ingles = listOf("I will always love you", "and I hope that life treats you kind", "you know that I'm not what you need")
        assertEquals("en" to "", AlinearLetra.idiomasParaOir(ingles, "es", "en"))
        assertEquals("es" to "", AlinearLetra.idiomasParaOir(LETRA, "en", "fr"))
        assertEquals("es-PE" to "en", AlinearLetra.idiomasParaOir(listOf("la la la"), "es-PE", "en"))
        // Dos idiomas igualados: no está claro.
        assertNull(AlinearLetra.idiomaDeLaLetra(listOf("the love and the you", "que el los las del")))
    }

    /** Lo oído como trozos del reconocedor, palabra a palabra (como da Vosk). */
    private fun trozos(vararg frases: Pair<Int, String>, paso: Int = 400): List<Transcriptor.Segmento> =
        frases.flatMap { (desde, texto) -> texto.split(" ").filter { it.isNotEmpty() }.mapIndexed { i, p -> Transcriptor.Segmento.entre(desde + i * paso, desde + i * paso + 300, p) } }

    @Test
    fun la_letra_guardada_es_la_pegada_tal_cual_con_su_minuto_delante() {
        val pegada = "Bésame, bésame mucho,\nComo si fuera esta noche\n\nLa última vez."
        val guardada = AlinearLetra.letraConTiempos(
            pegada, trozos(12_000 to "besame besame mucho", 18_000 to "como si fuera esta noche", 25_000 to "la ultima vez")
        )
        assertEquals("[0:12] Bésame, bésame mucho,\n\n[0:18] Como si fuera esta noche\n\n[0:25] La última vez.", guardada)
        // Y la leen igual la letra y la pantalla de estudiar (el formato del PC).
        val leida = LetraDeCancion.leer(guardada!!)
        assertEquals(listOf("Bésame, bésame mucho,", "Como si fuera esta noche", "La última vez."), leida.lineas.map { it.texto })
        assertEquals(listOf(12_000L, 18_000L, 25_000L), EstudiarClase.lineasDe(guardada).map { it.ms })
    }

    @Test
    fun una_letra_que_ya_traia_tiempos_se_alinea_de_nuevo_sin_ellos() {
        assertEquals(listOf("Hola", "Adiós"), AlinearLetra.lineasDeLaLetra("[00:01.00]Hola\n\n[00:05.00]Adiós"))
    }

    @Test
    fun las_lineas_son_las_de_sincronizar_a_toques() {
        val pegada = "  Primera  \n\n\nSegunda\n"
        assertEquals(SincronizadorDeLetra.de(LetraDeCancion.leer(pegada)).textos, AlinearLetra.lineasDeLaLetra(pegada))
        assertEquals(listOf("Primera", "Segunda"), AlinearLetra.lineasDeLaLetra(pegada))
    }

    @Test
    fun sin_letra_pegada_lo_oido_sale_como_letra_con_tiempos() {
        val propuesta = AlinearLetra.propuesta(
            null,
            listOf(Transcriptor.Segmento.entre(3_000, 6_000, "hola que tal."), Transcriptor.Segmento.entre(20_000, 23_000, "adiós amigo"))
        )
        assertNotNull(propuesta)
        assertEquals("[0:03] hola que tal.\n\n[0:20] adiós amigo", propuesta)
    }

    @Test
    fun sin_nada_parecido_no_hay_letra_con_tiempos() {
        assertNull(AlinearLetra.letraConTiempos("Bésame mucho", trozos(0 to "lorem ipsum dolor")))
        assertNull(AlinearLetra.letraConTiempos("", trozos(0 to "hola")))
    }

    @Test
    fun una_intro_hablada_antes_de_la_letra_no_cuenta() {
        val letra = listOf("Bésame, bésame mucho", "como si fuera esta noche la última vez")
        val oido = AlinearLetra.palabrasOidas(
            trozos(
                0 to "buenas noches a todos esta cancion se llama besame mucho gracias",
                20_000 to "besame besame mucho", 26_000 to "como si fuera esta noche la ultima vez"
            )
        )
        val t = tiempos(AlinearLetra.alinear(letra, oido)!!)
        // «bésame mucho» ya sale en la intro, pero la línea entera encaja mejor donde se canta.
        assertEquals(listOf(20_000L, 26_000L), t)
    }

    @Test
    fun una_estrofa_que_no_se_oyo_se_reparte_entre_sus_vecinas() {
        val letra = listOf("Primera estrofa que empieza aquí", "Esta estrofa nunca se canta en el disco", "Tercera estrofa con su final")
        val oido = AlinearLetra.palabrasOidas(trozos(5_000 to "primera estrofa que empieza aqui", 30_000 to "tercera estrofa con su final"))
        val t = tiempos(AlinearLetra.alinear(letra, oido)!!)
        assertEquals(5_000L, t[0])
        assertEquals(30_000L, t[2])
        assertTrue("la saltada, entre las dos: $t", t[1] in 5_001 until 30_000)
    }

    @Test
    fun el_estribillo_cantado_dos_veces_y_escrito_una_no_descoloca_lo_que_sigue() {
        val letra = listOf("Primera estrofa que empieza aquí", "Y este es el estribillo bonito", "Segunda estrofa distinta del todo")
        val oido = AlinearLetra.palabrasOidas(
            trozos(
                5_000 to "primera estrofa que empieza aqui", 15_000 to "y este es el estribillo bonito",
                25_000 to "y este es el estribillo bonito", 40_000 to "segunda estrofa distinta del todo"
            )
        )
        val t = tiempos(AlinearLetra.alinear(letra, oido)!!)
        assertEquals(5_000L, t[0])
        assertTrue("el estribillo, en una de sus dos veces: $t", t[1] in 14_600..15_400 || t[1] in 24_600..25_400)
        assertEquals(40_000L, t[2])
    }
}
