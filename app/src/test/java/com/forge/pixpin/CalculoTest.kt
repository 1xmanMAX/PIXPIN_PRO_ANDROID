package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El motor de las tablas: que las cuentas den lo que da Excel, que las fórmulas se muevan
 * bien al copiar y al meter filas, y que lo pegado desde Excel y Sheets entre entero.
 */
class CalculoTest {

    private fun hoja(vararg celdas: Pair<String, String>): Calculadora {
        val c = Calculadora()
        for ((dir, v) in celdas) {
            val p = Celdas.leer(dir)!!
            c.poner(p[0], p[1], v)
        }
        return c
    }

    private fun Calculadora.ver(dir: String): String {
        val p = Celdas.leer(dir)!!
        return texto(p[0], p[1])
    }

    /** Una fórmula suelta en Z99, sobre las celdas dadas. */
    private fun da(formula: String, vararg celdas: Pair<String, String>): String =
        hoja(*celdas, "Z99" to formula).ver("Z99")

    @Test
    fun `direcciones y columnas`() {
        assertEquals("A", Celdas.letras(0))
        assertEquals("Z", Celdas.letras(25))
        assertEquals("AA", Celdas.letras(26))
        assertEquals("ZZ", Celdas.letras(701))
        assertEquals(27, Celdas.columna("AB"))
        assertEquals(listOf(6, 1), Celdas.leer("\$B\$7")!!.toList())
    }

    @Test
    fun `aritmetica y precedencia como en Excel`() {
        assertEquals("7", da("=1+2*3"))
        assertEquals("9", da("=(1+2)*3"))
        assertEquals("4", da("=-2^2"))
        assertEquals("0.5", da("=2^-1"))
        assertEquals("0.1", da("=10%"))
        assertEquals("0.3333333333", da("=1/3"))
        assertEquals("VERDADERO", da("=0.1+0.2=0.3"))
        assertEquals("#¡DIV/0!", da("=1/0"))
        assertEquals("a1", da("=\"a\"&1"))
        assertEquals("FALSO", da("=\"abc\">\"abd\""))
        assertEquals("VERDADERO", da("=\"ABC\"=\"abc\""))
        assertEquals("#¿NOMBRE?", da("=NOEXISTE(1)"))
        assertEquals("#¿NOMBRE?", da("=1+"))
        assertEquals("#N/D", da("=#N/A"))
    }

    @Test
    fun `numeros escritos de muchas formas`() {
        assertEquals(1234.5, CalculoFormato.numero("1,234.50")!!, 0.0)
        assertEquals(1234.5, CalculoFormato.numero("1.234,50")!!, 0.0)
        assertEquals(1500.0, CalculoFormato.numero("1,500")!!, 0.0)
        assertEquals(12.5, CalculoFormato.numero("12,5")!!, 0.0)
        assertEquals(0.5, CalculoFormato.numero("0,500")!!, 0.0)
        assertEquals(120.0, CalculoFormato.numero("S/ 120")!!, 0.0)
        assertEquals(0.15, CalculoFormato.numero("15%")!!, 1e-15)
        assertEquals(-300.0, CalculoFormato.numero("(300)")!!, 0.0)
        assertEquals(1500000.0, CalculoFormato.numero("1 500 000")!!, 0.0)
        assertEquals(null, CalculoFormato.numero("10/09/2026"))
        assertEquals(null, CalculoFormato.numero("abc"))
    }

    @Test
    fun `la vista general`() {
        assertEquals("1E+20", CalculoFormato.general(1e20))
        assertEquals("123456789.1", CalculoFormato.general(123456789.123))
        assertEquals("1.5E-10", CalculoFormato.general(1.5e-10))
        assertEquals("-0.5", CalculoFormato.general(-0.5))
        assertEquals("100", CalculoFormato.general(100.0))
        assertEquals("1.23457E+15", CalculoFormato.general(1234567890123456.7))
    }

    @Test
    fun `referencias rangos y funciones basicas`() {
        val c = hoja(
            "A1" to "5", "A2" to "7", "A3" to "=A1+A2", "A4" to "=SUMA(A1:A3)", "A5" to "=SUM(A1:A2)",
            "B1" to "=PROMEDIO(A1:A2)", "B2" to "=C9", "B3" to "=SUMA(A:A)", "B4" to "=CONTAR(A1:A10)",
            "B5" to "=MAX(A1:A3)", "B6" to "=MIN(A1:A3)", "B7" to "=CONTARA(A1:A10)"
        )
        assertEquals("12", c.ver("A3"))
        assertEquals("24", c.ver("A4"))
        assertEquals("12", c.ver("A5"))
        assertEquals("6", c.ver("B1"))
        assertEquals("0", c.ver("B2"))
        assertEquals("60", c.ver("B3"))
        assertEquals("5", c.ver("B4"))
        assertEquals("12", c.ver("B5"))
        assertEquals("5", c.ver("B6"))
        assertEquals("5", c.ver("B7"))
    }

    @Test
    fun `los dos separadores y los nombres en ingles`() {
        assertEquals("sí", da("=SI(A1>1;\"sí\";\"no\")", "A1" to "2"))
        assertEquals("no", da("=IF(A1>1,\"sí\",\"no\")", "A1" to "0"))
        assertEquals("FALSO", da("=SI(A1>1;\"sí\")", "A1" to "0"))
        assertEquals("3", da("=RAÍZ(9)"))
        assertEquals("2026", da("=AÑO(FECHA(2026;9;10))"))
    }

    @Test
    fun `referencias circulares y cadenas largas`() {
        val c = hoja("B1" to "=B2", "B2" to "=B1")
        assertEquals("#¡CIRC!", c.ver("B1"))
        val larga = Calculadora()
        larga.poner(0, 0, "1")
        for (f in 1 until 5000) larga.poner(f, 0, "=A$f+1")
        assertEquals("5000", larga.texto(4999, 0))
        // Y al revés: cada una depende de la de abajo.
        val alReves = Calculadora()
        alReves.poner(4999, 0, "1")
        for (f in 0 until 4999) alReves.poner(f, 0, "=A${f + 2}+1")
        assertEquals("5000", alReves.texto(0, 0))
    }

    @Test
    fun `condicionales y busquedas`() {
        val datos = arrayOf(
            "A1" to "manzana", "B1" to "3", "C1" to "x",
            "A2" to "pera", "B2" to "8", "C2" to "y",
            "A3" to "mango", "B3" to "10", "C3" to "x",
            "A4" to "kiwi", "B4" to "1", "C4" to "x"
        )
        assertEquals("18", da("=SUMAR.SI(B1:B4;\">5\")", *datos))
        assertEquals("2", da("=CONTAR.SI(A1:A4;\"ma*\")", *datos))
        assertEquals("14", da("=SUMAR.SI(C1:C4;\"x\";B1:B4)", *datos))
        assertEquals("13", da("=SUMAR.SI.CONJUNTO(B1:B4;C1:C4;\"x\";B1:B4;\">2\")", *datos))
        assertEquals("2", da("=CONTAR.SI.CONJUNTO(C1:C4;\"x\";B1:B4;\">2\")", *datos))
        assertEquals("8", da("=BUSCARV(\"pera\";A1:B4;2;FALSO)", *datos))
        assertEquals("#N/D", da("=BUSCARV(\"uva\";A1:B4;2;0)", *datos))
        assertEquals("3", da("=COINCIDIR(\"mango\";A1:A4;0)", *datos))
        assertEquals("y", da("=INDICE(A1:C4;2;3)", *datos))
        assertEquals("kiwi", da("=BUSCARX(1;B1:B4;A1:A4)", *datos))
        assertEquals("174", da("=SUMAPRODUCTO(B1:B4;B1:B4)", *datos))
        // Aproximada: la lista de tramos ordenada.
        val tramos = arrayOf("A1" to "0", "B1" to "D", "A2" to "50", "B2" to "C", "A3" to "70", "B3" to "B", "A4" to "90", "B4" to "A")
        assertEquals("C", da("=BUSCARV(65;A1:B4;2)", *tramos))
        assertEquals("A", da("=BUSCARV(95;A1:B4;2;VERDADERO)", *tramos))
        assertEquals("7", da("=CONTAR.SI(A1:A10;\"\")", "A1" to "1", "A3" to "x", "A10" to "2"))
        assertEquals("0", da("=SI.ERROR(1/0;0)"))
    }

    @Test
    fun `texto fechas y redondeo`() {
        assertEquals("Hol", da("=IZQUIERDA(\"Hola\";3)"))
        assertEquals("ol", da("=EXTRAE(\"Hola\";2;2)"))
        assertEquals("b-b", da("=SUSTITUIR(\"a-a\";\"a\";\"b\")"))
        assertEquals("b-a", da("=SUSTITUIR(\"a-a\";\"a\";\"b\";1)"))
        assertEquals("HOLA", da("=MAYUSC(\"hola\")"))
        assertEquals("4", da("=LARGO(\"hola\")"))
        assertEquals("a b", da("=ESPACIOS(\"  a   b \")"))
        assertEquals("1,234.50", da("=TEXTO(1234.5;\"#,##0.00\")"))
        assertEquals("25.6%", da("=TEXTO(0.256;\"0.0%\")"))
        assertEquals("S/ 12.00", da("=TEXTO(12;\"S/ 0.00\")"))
        assertEquals("10/09/2026", da("=FECHA(2026;9;10)"))
        assertEquals("11/09/2026", da("=FECHA(2026;9;10)+1"))
        assertEquals("11/09/2026", da("=A1+1", "A1" to "10/09/2026"))
        assertEquals("30", da("=A2-A1", "A1" to "10/09/2026", "A2" to "10/10/2026"))
        assertEquals("01/03/2026", da("=FECHA(2026;2;29)"))
        assertEquals("2.68", da("=REDONDEAR(2.675;2)"))
        assertEquals("-3", da("=REDONDEAR(-2.5;0)"))
        assertEquals("1300", da("=REDONDEAR(1250;-2)"))
        assertEquals("3.15", da("=REDONDEAR.MAS(3.141;2)"))
        assertEquals("3.14", da("=REDONDEAR.MENOS(3.149;2)"))
        assertEquals("1", da("=RESIDUO(-3;2)"))
    }

    @Test
    fun `mover formulas al copiar`() {
        assertEquals("=B2+\$B\$2+C\$3", CalculoLexico.desplazar("=A1+\$B\$2+B\$3", 1, 1))
        assertEquals("=#¡REF!+1", CalculoLexico.desplazar("=A1+1", -1, 0))
        assertEquals("=SUMA(B:B)", CalculoLexico.desplazar("=SUMA(A:A)", 5, 1))
        assertEquals("=\"A1\"&B2", CalculoLexico.desplazar("=\"A1\"&A1", 1, 1))
        assertEquals("=LOG10(B2)", CalculoLexico.desplazar("=LOG10(A1)", 1, 1))
    }

    @Test
    fun `meter y quitar filas corrige las formulas`() {
        assertEquals("=SUMA(A1:A4)", CalculoLexico.alInsertar("=SUMA(A1:A3)", false, 1, 1))
        assertEquals("=SUMA(A1:A3)", CalculoLexico.alInsertar("=SUMA(A1:A3)", false, 3, 1))
        assertEquals("=SUMA(A1:A2)", CalculoLexico.alInsertar("=SUMA(A1:A3)", false, 1, -1))
        assertEquals("=#¡REF!", CalculoLexico.alInsertar("=A2", false, 1, -1))
        assertEquals("=B1+\$D\$1", CalculoLexico.alInsertar("=A1+\$C\$1", true, 0, 1))
        assertEquals("=SUMA(#¡REF!)", CalculoLexico.alInsertar("=SUMA(A2:A3)", false, 1, -2))

        val t = TablaViva(TablaDeCalculo(celdas = mapOf("A1" to "1", "A2" to "2", "A3" to "=SUMA(A1:A2)")))
        t.insertar(columnas = false, en = 1, cuantos = 1)
        assertEquals("=SUMA(A1:A3)", t.calc.crudo(3, 0))
        assertEquals("3", t.calc.texto(3, 0))
        assertTrue(t.deshacer())
        assertEquals("=SUMA(A1:A2)", t.calc.crudo(2, 0))
        assertTrue(t.rehacer())
        assertEquals("=SUMA(A1:A3)", t.calc.crudo(3, 0))
    }

    @Test
    fun `pegar desde Google Sheets con formulas`() {
        val html = "<meta charset=\"utf-8\"><google-sheets-html-origin><style>td{}</style>" +
            "<table xmlns=\"http://www.w3.org/1999/xhtml\"><tbody>" +
            "<tr><td style=\"font-weight:bold;\">Precio</td><td>Uds &amp; más</td></tr>" +
            "<tr><td data-sheets-value=\"{&quot;1&quot;:3,&quot;3&quot;:10}\">10</td><td>2</td></tr>" +
            "<tr><td data-sheets-formula=\"=R[-1]C[0]*R[-1]C[1]\">20</td><td data-sheets-formula=\"=SUM(R2C1:R[-1]C[0])\">2</td></tr>" +
            "</tbody></table>"
        val b = PortapapelesDeTabla.leer(html, "Precio\tUds\n10\t2\n20\t2")!!
        assertEquals(3, b.size)
        assertTrue(b[0][0].negrita)
        assertEquals("Uds & más", b[0][1].texto)
        val t = TablaViva(TablaDeCalculo())
        t.pegar(4, 2, b)
        assertEquals("=C6*D6", t.calc.crudo(6, 2))
        assertEquals("=SUM(\$A\$2:D6)", t.calc.crudo(6, 3))
        assertEquals("20", t.calc.texto(6, 2))
        assertEquals(true, t.estilo(4, 2)?.n)
        // Un solo deshacer quita el texto y la negrita.
        assertTrue(t.deshacer())
        assertEquals("", t.calc.crudo(4, 2))
        assertEquals(null, t.estilo(4, 2))
        assertFalse(t.puedeDeshacer)
    }

    @Test
    fun `texto con tabuladores y comillas de Excel`() {
        val filas = PortapapelesDeTabla.deTsv("a\t\"dos\nlineas\"\tc\r\n\"x\"\"y\"\t2\r\n")
        assertEquals(listOf(listOf("a", "dos\nlineas", "c"), listOf("x\"y", "2")), filas)
        assertEquals("a\t\"dos\nlineas\"", PortapapelesDeTabla.aTsv(listOf(listOf("a", "dos\nlineas"))))
        assertEquals(listOf(listOf("\"hola\" dijo")), PortapapelesDeTabla.deTsv("\"hola\" dijo"))
    }

    @Test
    fun `R1C1 de ida y vuelta`() {
        assertEquals("=SUM(A1:A3)", PortapapelesDeTabla.r1c1("=SUM(R[-3]C[0]:R[-1]C[0])", 3, 0))
        assertEquals("=B4*2", PortapapelesDeTabla.r1c1("=RC[1]*2", 3, 0))
        assertEquals("=ROUND(\"R1C1\";2)", PortapapelesDeTabla.r1c1("=ROUND(\"R1C1\";2)", 3, 0))
        val r = PortapapelesDeTabla.aR1c1("=SUMA(A1:A3)+\$B\$1", 3, 0)
        assertEquals("=SUMA(R[-3]C[0]:R[-1]C[0])+R1C2", r)
        assertEquals("=SUMA(C1:C3)+\$B\$1", PortapapelesDeTabla.r1c1(r, 3, 2))
    }

    @Test
    fun `copiar dentro de la app mueve las formulas`() {
        val t = TablaViva(TablaDeCalculo(celdas = mapOf("A1" to "2", "B1" to "=A1*10")))
        val copia = t.crudos(0, 1, 0, 1).map { f -> f.map { PortapapelesDeTabla.Pegada(it) } }
        t.pegar(1, 1, copia, origen = intArrayOf(0, 1))
        assertEquals("=A2*10", t.calc.crudo(1, 1))
        t.rellenar(0, 1, 3, 1, haciaAbajo = true)
        assertEquals("=A4*10", t.calc.crudo(3, 1))
        assertEquals("=SUMA(B1:B4)", t.autosuma(4, 1))
        assertEquals("=SUMA(A1:B1)", t.autosuma(0, 2))
    }

    @Test
    fun `las referencias de una formula a medias y la zona ocupada`() {
        val r = CalculoLexico.referencias("=SUMA(A1:B3)+\$C\$2*")
        assertEquals(2, r.size)
        assertEquals(ReferenciaEnFormula(0, 0, 2, 1, 6, 11), r[0])
        assertEquals(ReferenciaEnFormula(1, 2, 1, 2, 13, 17), r[1])
        assertEquals(listOf(ReferenciaEnFormula(0, 1, Celdas.MAX_FILAS - 1, 2, 6, 9)), CalculoLexico.referencias("=SUMA(B:C"))
        assertTrue(CalculoLexico.referencias("hola A1").isEmpty())
        assertEquals(null, Calculadora().caja())
        assertEquals(listOf(1, 1, 4, 3), hoja("B2" to "x", "D5" to "1").caja()!!.toList())
        assertEquals(listOf(0, 0, 5, 4), VisorTabla.marco(hoja("B2" to "x", "D5" to "1")).toList())
        assertEquals(listOf(0, 0, 1, 1), VisorTabla.marco(Calculadora()).toList())
    }

    @Test
    fun `proteger una tabla y marcar sus celdas editables`() {
        val t = TablaViva(TablaDeCalculo(celdas = mapOf("A1" to "2", "B1" to "=A1*3")))
        t.alternarEditables(0, 0, 1, 0)
        assertEquals(true, t.estilo(0, 0)?.e)
        assertEquals(true, t.estilo(1, 0)?.e)
        t.alternarEditables(0, 0, 0, 0)
        assertEquals("si ya lo era, se desmarca", null, t.estilo(0, 0))
        t.proteger(true)
        val json = TablaDeCalculo.aJson(t.aTabla(1))
        val otra = TablaViva(TablaDeCalculo.deJson(json)!!)
        assertTrue(otra.protegida)
        assertEquals(true, otra.estilo(1, 0)?.e)
        assertTrue(t.deshacer())
        assertFalse("proteger se deshace", t.protegida)
        assertTrue(VisorTabla.estatica(otra.aTabla(1)).contains("class=\"ed\""))
        assertFalse(VisorTabla.estatica(t.aTabla(1)).contains("\"ed\""))
    }

    @Test
    fun `la tabla se guarda y se lee igual`() {
        val t = TablaViva(TablaDeCalculo(nombre = "Gastos", celdas = mapOf("A1" to "Total", "B1" to "=2+2")))
        t.cambiarEstilo(0, 0, 0, 0) { (it ?: EstiloDeCelda()).copy(n = true) }
        t.ponerAncho(1, 140)
        val json = TablaDeCalculo.aJson(t.aTabla(5))
        assertFalse("sin valores por defecto", json.contains("\"a\":null"))
        val otra = TablaViva(TablaDeCalculo.deJson(json)!!)
        assertEquals("4", otra.calc.texto(0, 1))
        assertEquals(true, otra.estilo(0, 0)?.n)
        assertEquals(140, otra.ancho(1))
        assertEquals("Gastos", otra.nombre)
        val md = TablaViva.comoMarkdown(t.aTabla(5))
        assertTrue(md, md.contains("| **Total** | 4 |"))
    }
}
