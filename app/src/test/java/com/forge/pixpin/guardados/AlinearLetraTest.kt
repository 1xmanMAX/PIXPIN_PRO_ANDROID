package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La letra pegada con los tiempos de lo oído** (28-oct-2026): la alineación de [AlinearLetra] con
 * palabras mal oídas, estribillos repetidos, estrofas saltadas, intros habladas y lo que se guarda.
 */
class AlinearLetraTest {

    /** Lo oído, palabra a palabra: cada una en su milisegundo (como da Vosk). */
    private fun oido(vararg palabras: Pair<Int, String>): List<Transcriptor.Segmento> =
        palabras.map { (ms, p) -> Transcriptor.Segmento.entre(ms, ms + 300, p) }

    /** Una frase oída de seguido desde [desde], una palabra cada [paso] ms. */
    private fun frase(desde: Int, texto: String, paso: Int = 400): List<Pair<Int, String>> =
        texto.split(" ").filter { it.isNotEmpty() }.mapIndexed { i, p -> (desde + i * paso) to p }

    private fun tiempos(lineas: List<String>, vararg frases: List<Pair<Int, String>>, duracion: Int? = null): List<Int>? =
        AlinearLetra.tiempos(lineas, AlinearLetra.palabrasDe(oido(*frases.flatMap { it }.toTypedArray())), duracion)

    @Test
    fun normalizar_quita_tildes_mayusculas_y_signos() {
        assertEquals("besame", AlinearLetra.normalizar("¡Bésame,"))
        assertEquals("pinguino", AlinearLetra.normalizar("Pingüino!"))
        assertEquals("nino", AlinearLetra.normalizar("Niño"))
        assertEquals(listOf("como", "si", "fuera"), AlinearLetra.palabras("—Como si fuera…"))
    }

    @Test
    fun suenan_igual_b_v_c_s_z_ll_y_y_la_h() {
        assertEquals(AlinearLetra.fonetica("vez"), AlinearLetra.fonetica("bes"))
        assertEquals(AlinearLetra.fonetica("cielo"), AlinearLetra.fonetica("sielo"))
        assertEquals(AlinearLetra.fonetica("haya"), AlinearLetra.fonetica("alla"))
        assertEquals(AlinearLetra.fonetica("querer"), AlinearLetra.fonetica("kerer"))
        assertTrue(AlinearLetra.puntos("mucho", "musho") > 0)
        assertTrue(AlinearLetra.puntos("noche", "mañana") < 0)
    }

    @Test
    fun la_letra_igual_a_lo_oido_toma_el_tiempo_de_su_primera_palabra() {
        val t = tiempos(
            listOf("Bésame, bésame mucho", "como si fuera esta noche", "la última vez"),
            frase(12_000, "besame besame mucho"), frase(18_000, "como si fuera esta noche"), frase(25_000, "la ultima vez")
        )
        assertEquals(listOf(12_000, 18_000, 25_000), t)
    }

    @Test
    fun las_palabras_mal_oidas_no_mueven_las_lineas() {
        // Whisper con música: «vésame», «musho», «fuera» por «fuega», «noche» por «coche».
        val t = tiempos(
            listOf("Bésame, bésame mucho", "como si fuera esta noche", "la última vez"),
            frase(12_000, "vésame bésame musho"), frase(18_000, "como si fuega esta coche"), frase(25_000, "la última bes")
        )!!
        assertEquals(12_000, t[0])
        assertEquals(18_000, t[1])
        // «la» es corta y no fija; «última» sí (va segunda: se retrocede una palabra a ritmo de canto).
        assertTrue("la última línea en su sitio: ${t[2]}", t[2] in 24_400..25_400)
    }

    @Test
    fun el_estribillo_repetido_va_con_su_vez() {
        val letra = listOf(
            "Primera estrofa que empieza aquí", "Y este es el estribillo bonito",
            "Segunda estrofa distinta del todo", "Y este es el estribillo bonito"
        )
        val t = tiempos(
            letra,
            frase(5_000, "primera estrofa que empieza aqui"), frase(15_000, "y este es el estribillo bonito"),
            frase(30_000, "segunda estrofa distinta del todo"), frase(45_000, "y este es el estribillo bonito")
        )!!
        assertEquals(15_000, t[1], 400)
        assertEquals(45_000, t[3], 400)
        assertEquals(30_000, t[2])
    }

    @Test
    fun el_estribillo_cantado_dos_veces_y_escrito_una_no_descoloca_lo_que_sigue() {
        val letra = listOf("Primera estrofa que empieza aquí", "Y este es el estribillo bonito", "Segunda estrofa distinta del todo")
        val t = tiempos(
            letra,
            frase(5_000, "primera estrofa que empieza aqui"), frase(15_000, "y este es el estribillo bonito"),
            frase(25_000, "y este es el estribillo bonito"), frase(40_000, "segunda estrofa distinta del todo")
        )!!
        assertEquals(5_000, t[0])
        assertTrue("el estribillo, en una de sus dos veces: ${t[1]}", t[1] in 14_600..15_400 || t[1] in 24_600..25_400)
        assertEquals(40_000, t[2])
    }

    @Test
    fun una_estrofa_que_no_se_oyo_se_reparte_entre_sus_vecinas() {
        val letra = listOf(
            "Primera estrofa que empieza aquí",
            "Esta estrofa nunca se canta en el disco",
            "Tercera estrofa con su final"
        )
        val t = tiempos(letra, frase(5_000, "primera estrofa que empieza aqui"), frase(30_000, "tercera estrofa con su final"))!!
        assertEquals(5_000, t[0])
        assertEquals(30_000, t[2])
        assertTrue("la saltada, entre las dos: ${t[1]}", t[1] in 5_001 until 30_000)
    }

    @Test
    fun una_intro_hablada_antes_de_la_letra_no_cuenta() {
        val letra = listOf("Bésame, bésame mucho", "como si fuera esta noche la última vez")
        val t = tiempos(
            letra,
            frase(0, "buenas noches a todos esta cancion se llama besame mucho gracias"),
            frase(20_000, "besame besame mucho"), frase(26_000, "como si fuera esta noche la ultima vez")
        )!!
        // «bésame mucho» ya sale en la intro, pero la línea entera encaja mejor donde se canta.
        assertEquals(20_000, t[0])
        assertEquals(26_000, t[1])
    }

    @Test
    fun las_lineas_de_antes_y_despues_de_lo_oido_siguen_al_ritmo_sin_pasarse_del_final() {
        val letra = listOf("Una línea que no se oye", "Esta otra sí se escucha", "Y una al final que tampoco")
        val t = tiempos(letra, frase(10_000, "esta otra si se escucha"), duracion = 11_000)!!
        assertTrue(t[0] < 10_000)
        assertEquals(10_000, t[1])
        assertTrue(t[2] in 10_000..11_000)
    }

    @Test
    fun los_tiempos_nunca_bajan() {
        val letra = listOf("uno dos tres cuatro", "cinco seis siete ocho", "nueve diez once doce", "trece catorce quince")
        val t = tiempos(
            letra, frase(1_000, "uno dos tres cuatro"), frase(2_000, "cinco seis siete ocho"),
            frase(3_000, "trece catorce quince")
        )!!
        for (i in 1 until t.size) assertTrue("$t", t[i] >= t[i - 1])
    }

    @Test
    fun sin_nada_parecido_no_hay_tiempos() {
        assertNull(tiempos(listOf("Bésame mucho"), frase(0, "lorem ipsum dolor")))
        assertNull(AlinearLetra.tiempos(emptyList(), AlinearLetra.palabrasDe(oido(0 to "hola"))))
        assertNull(AlinearLetra.letraConTiempos("", oido(0 to "hola")))
    }

    @Test
    fun la_letra_guardada_es_la_pegada_tal_cual_con_su_minuto_delante() {
        val pegada = "Bésame, bésame mucho,\nComo si fuera esta noche\n\nLa última vez."
        val guardada = AlinearLetra.letraConTiempos(
            pegada,
            oido(*(frase(12_000, "besame besame mucho") + frase(18_000, "como si fuera esta noche") + frase(25_000, "la ultima vez")).toTypedArray())
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
    fun sin_letra_pegada_lo_oido_sale_como_letra_con_tiempos() {
        val propuesta = AlinearLetra.propuesta(
            null,
            listOf(Transcriptor.Segmento.entre(3_000, 6_000, "hola que tal."), Transcriptor.Segmento.entre(20_000, 23_000, "adiós amigo"))
        )
        assertNotNull(propuesta)
        assertEquals("[0:03] hola que tal.\n\n[0:20] adiós amigo", propuesta)
    }

    @Test
    fun alinear_es_semiglobal_y_determinista() {
        val a = listOf("b", "c", "d")
        val b = listOf("x", "y", "b", "c", "d", "z")
        val pares = AlinearLetra.alinear(a, b)!!
        assertEquals(listOf(2, 3, 4), pares.toList())
        assertEquals(pares.toList(), AlinearLetra.alinear(a, b)!!.toList())
    }

    private fun assertEquals(esperado: Int, real: Int, margen: Int) =
        assertTrue("esperaba $esperado ± $margen y salió $real", real in esperado - margen..esperado + margen)
}
