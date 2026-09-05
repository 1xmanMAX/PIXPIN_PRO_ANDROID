package com.forge.pixpin.motor

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El plano empaquetado y vuelto a abrir.**
 *
 * Lo que aquí se comprueba es que el viaje no pierde nada: se lee un PDF, se aprieta con
 * [PlanoWeb] —empalmando tramos, escribiendo diferencias y comprimiendo— y se desempaqueta
 * **con el mismo algoritmo que el visor**, para acabar comparando los puntos con los que
 * salieron del PDF. Si algún día una de las tres vueltas de tuerca se lleva por delante una
 * raya, esto se cae.
 *
 * El descompresor de esta prueba es el de Java; el que va en la página web está escrito en
 * JavaScript ([VisorPlano.INFLAR]) y se comprueba aparte, corriéndolo en node contra archivos
 * comprimidos por este mismo `Deflater`.
 */
class PlanoWebTest {

    // ---------------------------------------------------------------------
    // Desempaquetar, igual que hace el visor
    // ---------------------------------------------------------------------

    private class Orden(val op: Byte, val puntos: List<Pair<Int, Int>>)

    /** Lee el flujo de una página web y devuelve las órdenes de cada brocha. */
    private fun desempaquetar(json: String): List<List<Orden>> {
        val datos = inflarCrudo(java.util.Base64.getDecoder().decode(entre(json, "\"datos\":\"", "\"")))
        val cuantas = numeros(json, "\"n\":")
        val salida = ArrayList<List<Orden>>()
        var p = 0
        var x = 0
        var y = 0
        fun varint(): Int {
            var r = 0
            var s = 0
            while (true) {
                val c = datos[p++].toInt() and 0xFF
                r = r or ((c and 0x7F) shl s)
                if (c and 0x80 == 0) break
                s += 7
            }
            return (r ushr 1) xor -(r and 1)
        }
        for (n in cuantas) {
            val ordenes = ArrayList<Orden>(n)
            repeat(n) {
                val op = datos[p++]
                val cuantos = when (op) {
                    PlanoDePdf.CURVA -> 3
                    PlanoDePdf.CERRAR -> 0
                    else -> 1
                }
                val puntos = ArrayList<Pair<Int, Int>>(cuantos)
                repeat(cuantos) {
                    x += varint()
                    y += varint()
                    puntos += x to y
                }
                ordenes += Orden(op, puntos)
            }
            salida += ordenes
        }
        assertEquals("han sobrado bytes en el flujo", datos.size, p)
        return salida
    }

    private fun inflarCrudo(datos: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(datos)
        val salida = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0) break
            salida.write(buffer, 0, n)
        }
        inflater.end()
        return salida.toByteArray()
    }

    private fun entre(s: String, desde: String, hasta: String): String {
        val i = s.indexOf(desde) + desde.length
        return s.substring(i, s.indexOf(hasta, i))
    }

    /** Todos los valores de una clave repetida, en orden. */
    private fun numeros(json: String, clave: String): List<Int> {
        val out = ArrayList<Int>()
        var i = json.indexOf(clave)
        while (i >= 0) {
            var j = i + clave.length
            while (j < json.length && (json[j].isDigit() || json[j] == '-')) j++
            out += json.substring(i + clave.length, j).toInt()
            i = json.indexOf(clave, j)
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Un plano de mentira, hecho a mano
    // ---------------------------------------------------------------------

    private fun brocha(
        ops: List<Byte>,
        puntos: List<Pair<Int, Int>>,
        relleno: Boolean = false,
        capa: Int = -1
    ) = PlanoDePdf.Brocha(
        capa, 0x000000, 1.0, 1.0, relleno, DoubleArray(0),
        ops.toByteArray(),
        puntos.map { it.first }.toIntArray(),
        puntos.map { it.second }.toIntArray()
    )

    private fun plano(
        brochas: List<PlanoDePdf.Brocha>,
        capas: List<PlanoDePdf.Capa> = emptyList(),
        textos: List<PlanoDePdf.Texto> = emptyList()
    ) = PlanoDePdf.Plano(100.0, 200.0, capas, brochas, textos, emptyList(), 0, false)

    private val M = PlanoDePdf.MOVER
    private val L = PlanoDePdf.LINEA
    private val C = PlanoDePdf.CURVA
    private val Z = PlanoDePdf.CERRAR

    // ---------------------------------------------------------------------
    // Las pruebas
    // ---------------------------------------------------------------------

    @Test
    fun `lo que entra vuelve a salir punto por punto`() {
        val puntos = listOf(0 to 0, 640 to 1280, -320 to 99999, 7 to -7)
        val json = PlanoWeb.aJson(plano(listOf(brocha(listOf(M, L, L, L), puntos))))
        val ordenes = desempaquetar(json).single()
        assertEquals(listOf(M, L, L, L), ordenes.map { it.op })
        assertEquals(puntos, ordenes.flatMap { it.puntos })
    }

    @Test
    fun `una curva viaja con sus tres puntos`() {
        val puntos = listOf(0 to 0, 10 to 10, 20 to 20, 30 to 0)
        val json = PlanoWeb.aJson(plano(listOf(brocha(listOf(M, C), puntos))))
        val ordenes = desempaquetar(json).single()
        assertEquals(listOf(M, C), ordenes.map { it.op })
        assertEquals(3, ordenes[1].puntos.size)
        assertEquals(puntos, ordenes.flatMap { it.puntos })
    }

    /**
     * **El empalme, que es de donde sale que el archivo quepa.** AutoCAD escribe cada tramo de
     * una polilínea como un camino aparte —levantando la pluma y volviéndola a poner en el
     * mismo sitio—, y aquí se vuelven a coser: tres tramos encadenados salen como un solo
     * camino de cuatro puntos, sin repetir ninguno.
     */
    @Test
    fun `los tramos que se tocan se cosen en un solo camino`() {
        val b = brocha(
            listOf(M, L, M, L, M, L),
            listOf(0 to 0, 10 to 0, 10 to 0, 10 to 10, 10 to 10, 0 to 10)
        )
        val ordenes = desempaquetar(PlanoWeb.aJson(plano(listOf(b)))).single()
        assertEquals(listOf(M, L, L, L), ordenes.map { it.op })
        assertEquals(
            listOf(0 to 0, 10 to 0, 10 to 10, 0 to 10),
            ordenes.flatMap { it.puntos }
        )
    }

    @Test
    fun `dos tramos que no se tocan siguen siendo dos caminos`() {
        val b = brocha(listOf(M, L, M, L), listOf(0 to 0, 10 to 0, 50 to 50, 60 to 60))
        val ordenes = desempaquetar(PlanoWeb.aJson(plano(listOf(b)))).single()
        assertEquals(listOf(M, L, M, L), ordenes.map { it.op })
    }

    /** Lo cerrado no se empalma con nada: un contorno es un contorno. */
    @Test
    fun `un camino cerrado se queda entero`() {
        val b = brocha(
            listOf(M, L, L, Z, M, L),
            listOf(0 to 0, 10 to 0, 10 to 10, 0 to 0, 20 to 20)
        )
        val ordenes = desempaquetar(PlanoWeb.aJson(plano(listOf(b)))).single()
        assertEquals(listOf(M, L, L, Z, M, L), ordenes.map { it.op })
    }

    /**
     * Lo relleno **no se toca de orden**: al trazar da igual quién va antes, pero dos manchas
     * que se pisan se ven distinto según cuál se pinte encima.
     */
    @Test
    fun `las manchas conservan el orden en que se pintaron`() {
        val b = brocha(
            listOf(M, L, L, Z, M, L, L, Z),
            listOf(90 to 90, 95 to 90, 95 to 95, 0 to 0, 5 to 0, 5 to 5),
            relleno = true
        )
        val ordenes = desempaquetar(PlanoWeb.aJson(plano(listOf(b)))).single()
        assertEquals(90 to 90, ordenes[0].puntos[0])
        assertEquals("la segunda mancha ha adelantado a la primera", 0 to 0, ordenes[4].puntos[0])
    }

    @Test
    fun `la cabecera lleva la escala, el papel y las capas`() {
        val json = PlanoWeb.aJson(
            plano(
                listOf(brocha(listOf(M, L), listOf(0 to 0, 64 to 64), capa = 1)),
                capas = listOf(PlanoDePdf.Capa("Muros", true), PlanoDePdf.Capa("Cotas", false))
            ),
            anchoEnUnidades = 1400.0
        )
        assertTrue(json.contains("\"a\":1400"))
        // 200 puntos de alto sobre 100 de ancho: el papel mide el doble de largo.
        assertTrue(json.contains("\"b\":2800"))
        assertTrue(json.contains("\"n\":\"Muros\""))
        // La apagada llega apagada, la encendida no dice nada.
        assertTrue(json.contains("{\"n\":\"Cotas\",\"v\":0}"))
        assertTrue("la brocha tiene que decir de qué capa es", json.contains("\"c\":1"))
    }

    @Test
    fun `un paso del punto fijo son las unidades que dice la escala`() {
        val json = PlanoWeb.aJson(plano(listOf(brocha(listOf(M, L), listOf(0 to 0, 64 to 0)))))
        // 100 puntos de papel son 1400 unidades, repartidas entre los pasos de cada punto.
        val e = entre(json, "\"e\":", ",").toDouble()
        assertEquals(1400.0 / 100.0 / PlanoDePdf.FINEZA, e, 1e-9)
    }

    /** Un nombre de capa con `</script>` dentro cerraría la etiqueta que envuelve al JSON. */
    @Test
    fun `un nombre de capa no puede cerrar la etiqueta`() {
        val json = PlanoWeb.aJson(
            plano(
                listOf(brocha(listOf(M, L), listOf(0 to 0, 1 to 1))),
                capas = listOf(PlanoDePdf.Capa("</script><img src=x> \"comillas\"", true))
            )
        )
        assertTrue("se ha colado una etiqueta", !json.contains("</script>"))
        assertTrue(json.contains("\\u003c"))
    }

    @Test
    fun `un texto viaja con su matriz, su ancho y su tipo de letra`() {
        val t = PlanoDePdf.Texto(
            capa = 0, color = 0xff0000, alfa = 1.0, texto = "A-1",
            a = 10.0, b = 0.0, c = 0.0, d = 10.0, x = 20.0, y = 30.0,
            ancho = 1.5, familia = "serif", negrita = true, cursiva = false
        )
        val json = PlanoWeb.aJson(plano(listOf(brocha(listOf(M, L), listOf(0 to 0, 1 to 1))), textos = listOf(t)))
        assertTrue(json.contains("\"s\":\"A-1\""))
        assertTrue(json.contains("\"w\":1.5"))
        assertTrue(json.contains("\"f\":\"serif\""))
        assertTrue(json.contains("\"n\":1"))
        // La matriz sale en unidades del dibujo: catorce veces más grande que en puntos.
        assertTrue(json.contains("\"m\":[140,0,0,140,280,420]"))
    }

    // ---------------------------------------------------------------------
    // De punta a punta
    // ---------------------------------------------------------------------

    /**
     * El viaje entero: un PDF de verdad, apretado y vuelto a abrir, tiene que dar los mismos
     * puntos que dio al leerlo. Es la prueba que sostiene todo lo demás.
     */
    @Test
    fun `del pdf a la pagina web y de vuelta sin perder un punto`() {
        val contenido = buildString {
            append("2 w 0 0 1 RG\n")
            for (i in 0 until 40) {
                append("${i * 2} ${i} m ${i * 2 + 2} ${i + 1} l S\n")
            }
            append("1 0 0 rg 10 100 30 20 re f\n")
        }
        val bytes = PdfDeMentira.pagina(contenido, 200, 300)
        val leido = PlanoDePdf.de(bytes, 0)!!
        val json = PlanoWeb.aJson(leido)
        val desempaquetado = desempaquetar(json)

        assertEquals(leido.brochas.size, desempaquetado.size)
        for ((i, b) in leido.brochas.withIndex()) {
            val puntosDeVuelta = desempaquetado[i].flatMap { it.puntos }.toSet()
            val puntosDeIda = b.xs.indices.map { b.xs[it] to b.ys[it] }.toSet()
            assertEquals("la brocha $i ha perdido puntos", puntosDeIda, puntosDeVuelta)
        }
        // Las cuarenta rayas iban encadenadas: tienen que haber salido como un solo camino.
        val trazada = desempaquetado[leido.brochas.indexOfFirst { !it.relleno }]
        assertEquals(1, trazada.count { it.op == PlanoDePdf.MOVER })
    }

    @Test
    fun `un pdf que es solo una imagen no se manda como plano`() {
        val bytes = PdfDeMentira.conImagen()
        assertTrue("un escaneo tiene que seguir yendo como foto", !PlanoDePdf.de(bytes, 0)!!.valeLaPena)
    }
}

/** Fabrica PDFs mínimos para las pruebas del plano. Ver [PlanoDePdfTest], que hace lo mismo. */
internal object PdfDeMentira {

    fun pagina(contenido: String, ancho: Int, alto: Int): ByteArray = armar(
        listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $ancho $alto] " +
                "/Contents 4 0 R /Resources << >> >>\nendobj\n",
            "4 0 obj\n<< /Length ${contenido.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n" +
                contenido + "\nendstream\nendobj\n"
        )
    )

    fun conImagen(): ByteArray = armar(
        listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R " +
                "/Resources << /XObject << /Im1 5 0 R >> >> >>\nendobj\n",
            "4 0 obj\n<< /Length 34 >>\nstream\nq 100 0 0 100 0 0 cm /Im1 Do Q\nendstream\nendobj\n",
            "5 0 obj\n<< /Length 4 /Type /XObject /Subtype /Image /Width 2 /Height 2 >>\n" +
                "stream\nxxxx\nendstream\nendobj\n"
        )
    )

    private fun armar(objetos: List<String>): ByteArray {
        val salida = ByteArrayOutputStream()
        salida.write("%PDF-1.5\n".toByteArray(Charsets.ISO_8859_1))
        val donde = mutableListOf<Int>()
        for (o in objetos) {
            donde += salida.size()
            salida.write(o.toByteArray(Charsets.ISO_8859_1))
        }
        val inicio = salida.size()
        val tabla = StringBuilder("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
        for (d in donde) tabla.append("%010d 00000 n \n".format(d))
        tabla.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\n")
        tabla.append("startxref\n$inicio\n%%EOF\n")
        salida.write(tabla.toString().toByteArray(Charsets.ISO_8859_1))
        return salida.toByteArray()
    }
}
