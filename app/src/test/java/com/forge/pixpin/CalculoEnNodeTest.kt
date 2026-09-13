package com.forge.pixpin.motor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * **La tabla exportada calcula lo mismo que la aplicación.**
 *
 * La página web lleva el motor escrito en JavaScript ([VisorTabla.CALCULO]) y la aplicación
 * lo lleva en Kotlin. Si un día alguien toca uno y no el otro, quien reciba la página verá
 * otro total que quien la mandó, y nadie sabrá por qué. Aquí se pasa lo mismo por los dos
 * —fórmulas, números escritos de mil maneras, la vista «General», redondeos, formatos de
 * `TEXTO`, fórmulas movidas y R1C1— y se compara **lo que se enseña**, carácter a carácter.
 *
 * Necesita node; donde no lo haya, la prueba se salta.
 */
class CalculoEnNodeTest {

    private fun node(): String? {
        val candidatos = ArrayList<String>()
        System.getenv("NODE")?.let { candidatos += it }
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).forEach { candidatos += "$it/node" }
        File("/root/.nvm/versions/node").listFiles()?.forEach { candidatos += "${it.path}/bin/node" }
        candidatos += listOf("/usr/bin/node", "/usr/local/bin/node")
        return candidatos.firstOrNull { File(it).canExecute() }
    }

    private fun correr(programa: String): String {
        val archivo = File.createTempFile("calculo", ".js").apply { writeText(programa); deleteOnExit() }
        val p = ProcessBuilder(node()!!, archivo.path).redirectErrorStream(true).start()
        val salida = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        assertEquals("node terminó con error:\n$salida", 0, p.exitValue())
        return salida
    }

    private val datos = mapOf(
        "A1" to "manzana", "B1" to "3", "C1" to "x", "D1" to "10/09/2026",
        "A2" to "pera", "B2" to "8", "C2" to "y", "D2" to "1,234.50",
        "A3" to "mango", "B3" to "10", "C3" to "x", "D3" to "15%",
        "A4" to "kiwi", "B4" to "1", "C4" to "x", "D4" to "VERDADERO",
        "A5" to "0", "B5" to "=B1/A5", "C5" to "'=texto", "D5" to "S/ 1.250,75",
        "E1" to "=E2", "E2" to "=E1"
    )

    private val formulas = listOf(
        "=1+2*3", "=-2^2", "=2^-1", "=10%", "=1/3", "=2/3", "=0.1+0.2", "=0.1+0.2=0.3", "=1/0",
        "=\"a\"&1", "=\"abc\">\"abd\"", "=NOEXISTE(1)", "=1+", "=#N/A", "=(1+2", "=SUMA(B1:B4)",
        "=SUM(B1:B4)", "=PROMEDIO(B1:B4)", "=MAX(B1:B4)", "=MIN(B1:D4)", "=CONTAR(A1:D5)", "=CONTARA(A1:D5)",
        "=CONTAR.BLANCO(A1:F6)", "=PRODUCTO(B1:B4)", "=MEDIANA(B1:B4)", "=DESVEST(B1:B4)", "=SUMA(B:B)",
        "=SI(B1>2;\"sí\";\"no\")", "=IF(B1>20,\"sí\",\"no\")", "=SI(B1>20;1)", "=SI.CONJUNTO(B1>5;1;B1>2;2)",
        "=Y(B1>1;B2>1)", "=O(B1>5;B2>5)", "=NO(VERDADERO)", "=SI.ERROR(B5;\"error\")", "=ESERROR(B5)",
        "=ESBLANCO(F9)", "=ESNUMERO(B1)", "=ESTEXTO(A1)", "=REDONDEAR(2.675;2)", "=REDONDEAR(-2.5;0)",
        "=REDONDEAR(1250;-2)", "=REDONDEAR.MAS(3.141;2)", "=REDONDEAR.MENOS(-3.149;2)", "=TRUNCAR(8.9)",
        "=ENTERO(-8.9)", "=ABS(-4)", "=RAIZ(2)", "=RAIZ(-1)", "=POTENCIA(2;0.5)", "=RESIDUO(-3;2)",
        "=RESIDUO(5;0)", "=PI()", "=EXP(1)", "=LN(10)", "=LOG(8;2)", "=LOG10(1000)", "=SENO(1)", "=COS(1)",
        "=TAN(1)", "=GRADOS(PI())", "=RADIANES(180)", "=SIGNO(-3)", "=FECHA(2026;9;10)", "=FECHA(2026;14;35)",
        "=D1+1", "=D1-FECHA(2026;1;1)", "=DIA(D1)", "=MES(D1)", "=AÑO(D1)", "=D2*2", "=D3*100", "=D5+0.25",
        "=CONCATENAR(A1;\" y \";A2)", "=CONCAT(A1:A3)", "=LARGO(A1)", "=MAYUSC(A1)", "=MINUSC(\"ÁRBOL\")",
        "=ESPACIOS(\"  a   b \")", "=IZQUIERDA(A1;3)", "=DERECHA(A1;2)", "=EXTRAE(A1;2;3)",
        "=ENCONTRAR(\"a\";A1)", "=HALLAR(\"Z\";A1)", "=SUSTITUIR(\"a-a-a\";\"a\";\"b\";2)", "=REPETIR(\"ab\";3)",
        "=TEXTO(1234.5;\"#,##0.00\")", "=TEXTO(0.256;\"0.0%\")", "=TEXTO(D1;\"dd/mm/aaaa\")",
        "=TEXTO(12;\"0.00 \"\"dólares\"\"\")", "=VALOR(\"1.234,5\")", "=VALOR(\"x\")",
        "=BUSCARV(\"pera\";A1:C4;2;FALSO)", "=BUSCARV(\"uva\";A1:C4;2;0)", "=BUSCARV(5;B1:C4;2)",
        "=BUSCARH(\"pera\";A1:D2;2;FALSO)", "=COINCIDIR(\"mango\";A1:A4;0)", "=COINCIDIR(9;B1:B4;1)",
        "=COINCIDIR(\"m*\";A1:A4;0)", "=INDICE(A1:C4;2;3)", "=INDICE(A1:D1;3)", "=BUSCARX(1;B1:B4;A1:A4)",
        "=BUSCARX(99;B1:B4;A1:A4;\"no\")", "=SUMAR.SI(B1:B4;\">5\")", "=CONTAR.SI(A1:A4;\"ma*\")",
        "=SUMAR.SI(C1:C4;\"x\";B1:B4)", "=PROMEDIO.SI(C1:C4;\"x\";B1:B4)", "=CONTAR.SI(A1:A10;\"\")",
        "=CONTAR.SI(C1:C4;\"<>x\")", "=CONTAR.SI(B1:B4;8)", "=SUMAR.SI.CONJUNTO(B1:B4;C1:C4;\"x\";B1:B4;\">2\")",
        "=CONTAR.SI.CONJUNTO(C1:C4;\"x\";B1:B4;\">2\")", "=PROMEDIO.SI.CONJUNTO(B1:B4;C1:C4;\"x\")",
        "=SUMAPRODUCTO(B1:B4;B1:B4)", "=ELEGIR(2;\"a\";\"b\")", "=FILA()", "=COLUMNA(C3)", "=E1",
        "=SUMA(A1:A4)", "=A1+1", "=C5", "=\$B\$1*B2", "=SUMA(B1:B4)/CONTAR(B1:B4)", "=B1&\"\"",
        "=1E+3", "=1.5e-3*2", "=SI(;1;2)", "=SUMA(1;;2)", "=\"a\"\"b\"", "=Año(D1)"
    )

    private val numeros = listOf(
        1.0 / 3, 2.0 / 3, 0.1 + 0.2, 1e15, 999999999999999.9, 1234567.891, -0.000123456789, 1.23456789e-10,
        1.7976931348623157e308, 123456789012.5, 0.5, 2.5, 1e21, 4.9e-324, 0.000000001, 0.00000000099999999,
        99999.99999999999, 123.45000000001, -1e-5, 6.02214076e23, 314159.2653589793
    )

    private val textos = listOf(
        "1,234.50", "1.234,50", "1,500", "12,5", "0,500", "S/ 120", "15%", "(300)", "1 500 000", "10/09/2026",
        "abc", "-", ".5", "5.", "1e3", "1,2,3", "1.2.3", "US$ 9.99", "€ 1.000", "+7", "--7", "( 4 )", "3 %",
        "1,23", "1234,567", "0.1.2", "", " 42 "
    )

    @Test
    fun `el motor de la pagina web ensena lo mismo que el de la app`() {
        assumeTrue("no hay node en esta máquina", node() != null)

        val casos = buildJsonArray {
            for (f in formulas) add(buildJsonObject {
                put("en", "H8")
                put("celdas", buildJsonObject { for ((k, v) in datos) put(k, v); put("H8", f) })
            })
        }
        val redondeos = listOf(2.675 to 2, -2.5 to 0, 1250.0 to -2, 1.005 to 2, 0.125 to 2, 1e-7 to 8, 123.456 to -1)
        val formatos = listOf(
            1234.5 to "#,##0.00", 0.256 to "0.0%", -1234567.891 to "#,##0", 12.0 to "S/ 0.00", 46275.0 to "dd/mm/aaaa",
            0.5 to "0", 2.5 to "0", 1.005 to "0.00"
        )
        val desplazados = listOf(
            Triple("=A1+\$B\$2+B\$3", 1, 1), Triple("=A1+1", -1, 0), Triple("=SUMA(A:A)", 5, 1),
            Triple("=\"A1\"&A1", 1, 1), Triple("=LOG10(A1)", 1, 1), Triple("=SUMA(A1:B3)*\$C1", 2, -1)
        )
        val r1c1s = listOf(
            Triple("=SUM(R[-3]C[0]:R[-1]C[0])", 3, 0), Triple("=RC[1]*2", 3, 0), Triple("=ROUND(\"R1C1\";2)", 3, 0),
            Triple("=R1C1+R[2]C", 0, 4), Triple("=SUM(C[0]:C[1])", 0, 2)
        )

        val programa = buildString {
            append(VisorTabla.CALCULO)
            append("\nvar casos=").append(casos).append(";\n")
            append("var out={refs:[],formulas:[],general:[],numero:[],redondear:[],formato:[],desplazar:[],r1c1:[],ar1c1:[]};\n")
            append("casos.forEach(function(k){var c=Calculo.crear();for(var d in k.celdas){var p=Calculo.leer(d);c.poner(p[0],p[1],k.celdas[d]);}var q=Calculo.leer(k.en);out.formulas.push(c.texto(q[0],q[1]));});\n")
            append("[").append(numeros.joinToString(",") { it.toString() }).append("].forEach(function(n){out.general.push(Calculo.general(n));out.general.push(Calculo.general(-n));});\n")
            append(JsonArray(textos.map { JsonPrimitive(it) }).toString())
            append(".forEach(function(t){var n=Calculo.numero(t);out.numero.push(n===null?'null':Calculo.general(n));});\n")
            for ((x, d) in redondeos) append("out.redondear.push(Calculo.general(Calculo.redondear($x,$d,0)));\n")
            for ((x, f) in formatos) append("out.formato.push(Calculo.conFormato($x,").append(JsonPrimitive(f)).append("));\n")
            for ((f, a, b) in desplazados) append("out.desplazar.push(Calculo.desplazar(").append(JsonPrimitive(f)).append(",$a,$b));\n")
            for ((f, a, b) in r1c1s) {
                append("out.r1c1.push(Calculo.r1c1(").append(JsonPrimitive(f)).append(",$a,$b));\n")
            }
            for ((f, a, b) in desplazados) append("out.ar1c1.push(Calculo.aR1c1(").append(JsonPrimitive(f)).append(",$a,$b));\n")
            for (f in formulas) {
                append("out.refs.push(Calculo.referencias(").append(JsonPrimitive(f))
                append(").map(function(r){return [r.f1,r.c1,r.f2,r.c2,r.desde,r.hasta].join(',');}).join(';'));\n")
            }
            append("process.stdout.write(JSON.stringify(out));\n")
        }
        val js = Json.parseToJsonElement(correr(programa))
        fun lista(nombre: String) = (js as kotlinx.serialization.json.JsonObject)[nombre]!!.jsonArray.map { it.jsonPrimitive.content }

        val kotlin = formulas.map { f ->
            val c = Calculadora()
            for ((k, v) in datos + ("H8" to f)) { val p = Celdas.leer(k)!!; c.poner(p[0], p[1], v) }
            c.texto(7, 7)
        }
        for ((i, f) in formulas.withIndex()) assertEquals("fórmula $f", kotlin[i], lista("formulas")[i])
        assertEquals(numeros.flatMap { listOf(CalculoFormato.general(it), CalculoFormato.general(-it)) }, lista("general"))
        assertEquals(textos.map { t -> CalculoFormato.numero(t)?.let { CalculoFormato.general(it) } ?: "null" }, lista("numero"))
        assertEquals(redondeos.map { (x, d) -> CalculoFormato.general(CalculoFormato.redondear(x, d, 0)) }, lista("redondear"))
        assertEquals(formatos.map { (x, f) -> CalculoFormato.conFormato(x, f) }, lista("formato"))
        assertEquals(desplazados.map { (f, a, b) -> CalculoLexico.desplazar(f, a, b) }, lista("desplazar"))
        assertEquals(r1c1s.map { (f, a, b) -> PortapapelesDeTabla.r1c1(f, a, b) }, lista("r1c1"))
        assertEquals(desplazados.map { (f, a, b) -> PortapapelesDeTabla.aR1c1(f, a, b) }, lista("ar1c1"))
        assertEquals(
            formulas.map { f -> CalculoLexico.referencias(f).joinToString(";") { listOf(it.f1, it.c1, it.f2, it.c2, it.desde, it.hasta).joinToString(",") } },
            lista("refs")
        )
    }

    /** El guion entero de una página con tabla —motor, visor y armazón— se deja compilar. */
    @Test
    fun `el guion de una pagina con tabla compila`() {
        assumeTrue("no hay node en esta máquina", node() != null)
        val tabla = TablaDeCalculo(celdas = mapOf("A1" to "1", "A2" to "=A1*2"))
        val html = ExportarHtml.paginas(
            listOf(ExportarHtml.HojaWeb.Tabla("T", tabla), ExportarHtml.HojaWeb.Nota("N", "<p>x</p>", "#ffffff")), "T"
        )
        val guion = Regex("<script>([\\s\\S]*?)</script>").findAll(html).last().groupValues[1]
        val archivo = File.createTempFile("guion", ".js").apply { writeText(guion); deleteOnExit() }
        val salida = correr(
            "const vm=require('vm'),fs=require('fs');new vm.Script(fs.readFileSync(" +
                JsonPrimitive(archivo.path) + ",'utf8'));process.stdout.write('ok');"
        )
        assertEquals("ok", salida)
    }
}
