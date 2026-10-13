package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Una hoja calcula igual en el PC y en el móvil** (13-oct-2026). Es la tabla de pruebas del
 * motor de fórmulas del PC (`crates/pixpin-proyecto/src/formula/pruebas_funciones.rs`, la
 * sección 6 de `docs/investigacion/2026-10-11-formulas-android.md`), traducida tal cual: cada
 * fila es la fórmula y lo que el PC enseña. Se compara la vista «General» del valor
 * ([Calculadora.mostrarGeneral]): el móvil además enseña como fecha los números que salen de
 * una fecha, y eso es formato, no cálculo.
 *
 * `CalculoEnNodeTest` pasa estas mismas filas por el gemelo en JavaScript de la página web.
 */
class FormulasComoElPcTest {

    /** Una fila: con los datos de [DATOS] o en una hoja vacía, la fórmula (sin `=`) y lo esperado. */
    class C(val conDatos: Boolean, val formula: String, val esperado: String)

    companion object {
        /** Los datos que usan muchas filas: los de `datos()` del PC. */
        val DATOS = listOf(
            "A1" to "10", "A2" to "20", "A3" to "30", "A4" to "x", "A5" to "40",
            "B1" to "1", "B2" to "2", "B3" to "3", "B4" to "4", "B5" to "5",
            "C1" to "Ana", "C2" to "Anibal", "C3" to "Beto", "D1" to "20",
            "H1" to "10", "H2" to "20", "H3" to "30", "H4" to "40",
            "I1" to "a", "I2" to "b", "I3" to "c", "I4" to "d",
            "J1" to "1", "J2" to "2", "J3" to "3", "J4" to "4",
            "L1" to "1", "M1" to "2", "N1" to "3", "O1" to "4",
            "L2" to "w", "M2" to "x", "N2" to "y", "O2" to "z",
            "P1" to "30", "P2" to "20", "P3" to "10", "Q3" to "z",
            "R1" to "1", "R2" to "1", "R3" to "2", "S1" to "a", "S2" to "b", "S3" to "c",
            "F1" to "=1/0"
        )

        val CASOS = listOf(
        C(false, "1+2*3", "7"),
        C(false, "(1+2)*3", "9"),
        C(false, "2^3", "8"),
        C(false, "2^3^2", "64"),
        C(false, "-2^2", "4"),
        C(false, "2^-1", "0.5"),
        C(false, "50%", "0.5"),
        C(false, "200*10%", "20"),
        C(false, "2*50%+1", "2"),
        C(false, "1E3+1", "1001"),
        C(false, "--3", "3"),
        C(false, "1+2&3", "33"),
        C(false, "1+1=2", "VERDADERO"),
        C(false, "\"a\"&\"b\"", "ab"),
        C(false, "\"n\"&1.5", "n1.5"),
        C(false, "\"a\"\"b\"", "a\"b"),
        C(false, "1&2", "12"),
        C(false, "\"x\"&VERDADERO", "xVERDADERO"),
        C(false, "\"\"&\"\"", ""),
        C(false, "1<2", "VERDADERO"),
        C(false, "2<=1", "FALSO"),
        C(false, "2>=2", "VERDADERO"),
        C(false, "1<>1", "FALSO"),
        C(false, "1=1.0", "VERDADERO"),
        C(false, "\"A\"=\"a\"", "VERDADERO"),
        C(false, "\"b\">\"a\"", "VERDADERO"),
        C(false, "1<\"a\"", "VERDADERO"),
        C(false, "\"a\"<VERDADERO", "VERDADERO"),
        C(false, "VERDADERO", "VERDADERO"),
        C(false, "FALSO", "FALSO"),
        C(false, "TRUE", "VERDADERO"),
        C(false, "false", "FALSO"),
        C(false, "0.1+0.2=0.3", "VERDADERO"),
        C(false, "VERDADERO+1", "2"),
        C(false, "B9=0", "VERDADERO"),
        C(false, "B9=\"\"", "VERDADERO"),
        C(false, "B9<1", "VERDADERO"),
        C(false, "B9=FALSO", "VERDADERO"),
        C(false, "B9=\"a\"", "FALSO"),
        C(false, "SI(1>0;\"si\";\"no\")", "si"),
        C(false, "SI(1<0;\"si\";\"no\")", "no"),
        C(false, "IF(1,2,3)", "2"),
        C(false, "SI(FALSO;1)", "FALSO"),
        C(false, "SI(0;1;2)", "2"),
        C(false, "SI(\"VERDADERO\";1;2)", "1"),
        C(false, "SI(VERDADERO;1;1/0)", "1"),
        C(false, "SI(FALSO;1/0;2)", "2"),
        C(false, "SI(B9;1;2)", "2"),
        C(false, "SI(1)", "#¡VALOR!"),
        C(false, "SI(1;2;3;4)", "#¡VALOR!"),
        C(false, "SI()", "#¡VALOR!"),
        C(false, "SI(\"x\";1;2)", "#¡VALOR!"),
        C(false, "SI(1/0;1;2)", "#¡DIV/0!"),
        C(false, "SI(VERDADERO;1/0;2)", "#¡DIV/0!"),
        C(true, "Y(1;1)", "VERDADERO"),
        C(true, "Y(1;0)", "FALSO"),
        C(true, "AND(1>0,2>1,VERDADERO)", "VERDADERO"),
        C(true, "O(0;0;1)", "VERDADERO"),
        C(true, "OR(0,0)", "FALSO"),
        C(true, "NO(0)", "VERDADERO"),
        C(true, "NOT(VERDADERO)", "FALSO"),
        C(true, "Y(A1:A3)", "VERDADERO"),
        C(true, "O(B9:B10;0)", "FALSO"),
        C(true, "Y(A1:A5)", "VERDADERO"),
        C(true, "Y(\"hola\")", "#¡VALOR!"),
        C(true, "Y(1;\"verdadero\")", "VERDADERO"),
        C(true, "Y(C1:C3)", "#¡VALOR!"),
        C(true, "SI.ERROR(1/0;\"x\")", "x"),
        C(true, "IFERROR(5;\"x\")", "5"),
        C(true, "SI.ERROR(F1;0)", "0"),
        C(true, "SI.ERROR(SUMA(F1:F2);-1)", "-1"),
        C(true, "SI.ERROR(\"a\"+1;\"malo\")", "malo"),
        C(true, "SI.ND(BUSCARV(9;H1:J4;2;0);\"no\")", "no"),
        C(true, "SI.ND(1/0;\"no\")", "#¡DIV/0!"),
        C(false, "SI.CONJUNTO(FALSO;1;VERDADERO;2;VERDADERO;3)", "2"),
        C(false, "IFS(1>2,\"a\",2>1,\"b\")", "b"),
        C(false, "SI.CONJUNTO(FALSO;1;FALSO;2)", "#N/A"),
        C(false, "SI.CONJUNTO(VERDADERO;1;FALSO)", "#¡VALOR!"),
        C(false, "SI.CONJUNTO()", "#¡VALOR!"),
        C(false, "SI.CONJUNTO(VERDADERO)", "#¡VALOR!"),
        C(false, "SI.CONJUNTO(\"x\";1)", "#¡VALOR!"),
        C(false, "SI.CONJUNTO(VERDADERO;1;1/0;2)", "1"),
        C(false, "REDONDEAR(2.675;2)", "2.68"),
        C(false, "ROUND(1.005;2)", "1.01"),
        C(false, "REDONDEAR(2.5;0)", "3"),
        C(false, "REDONDEAR(-2.5;0)", "-3"),
        C(false, "REDONDEAR(2.4;0)", "2"),
        C(false, "REDONDEAR(1234.567;-2)", "1200"),
        C(false, "REDONDEAR(1250;-2)", "1300"),
        C(false, "REDONDEAR(3.14159;3)", "3.142"),
        C(false, "REDONDEAR.MAS(2.1;0)", "3"),
        C(false, "ROUNDUP(-2.1;0)", "-3"),
        C(false, "REDONDEAR.MAS(2.123;2)", "2.13"),
        C(false, "REDONDEAR.MAS(3;0)", "3"),
        C(false, "REDONDEAR.MENOS(2.9;0)", "2"),
        C(false, "ROUNDDOWN(-2.9;0)", "-2"),
        C(false, "REDONDEAR.MENOS(2.999;2)", "2.99"),
        C(false, "REDONDEAR.MENOS(1999;-3)", "1000"),
        C(false, "REDONDEAR(0;2)", "0"),
        C(false, "ENTERO(2.9)", "2"),
        C(false, "INT(-1.5)", "-2"),
        C(false, "REDONDEAR(1)", "#¡VALOR!"),
        C(false, "REDONDEAR(1;2;3)", "#¡VALOR!"),
        C(false, "REDONDEAR(\"x\";1)", "#¡VALOR!"),
        C(false, "REDONDEAR(1/0;1)", "#¡DIV/0!"),
        C(false, "ENTERO()", "#¡VALOR!"),
        C(false, "ENTERO(\"a\")", "#¡VALOR!"),
        C(false, "ABS(-3)", "3"),
        C(false, "ABS(3)", "3"),
        C(false, "RESIDUO(10;3)", "1"),
        C(false, "RESIDUO(-3;2)", "1"),
        C(false, "MOD(3;-2)", "-1"),
        C(false, "RESIDUO(5.5;2)", "1.5"),
        C(false, "POTENCIA(2;10)", "1024"),
        C(false, "POWER(9;0.5)", "3"),
        C(false, "RAIZ(16)", "4"),
        C(false, "SQRT(2)", "1.414213562"),
        C(false, "RAIZ(0)", "0"),
        C(false, "PI()", "3.141592654"),
        C(false, "REDONDEAR(PI();2)", "3.14"),
        C(false, "RESIDUO(1;0)", "#¡DIV/0!"),
        C(false, "RAIZ(-1)", "#¡NUM!"),
        C(false, "POTENCIA(0;0)", "#¡NUM!"),
        C(false, "POTENCIA(-8;0.5)", "#¡NUM!"),
        C(false, "POTENCIA(0;-1)", "#¡DIV/0!"),
        C(false, "ABS()", "#¡VALOR!"),
        C(false, "ABS(1;2)", "#¡VALOR!"),
        C(false, "ABS(\"x\")", "#¡VALOR!"),
        C(false, "PI(1)", "#¡VALOR!"),
        C(false, "RAIZ()", "#¡VALOR!"),
        C(false, "RESIDUO(1)", "#¡VALOR!"),
        C(false, "POTENCIA(2)", "#¡VALOR!"),
        C(true, "PRODUCTO(2;3;4)", "24"),
        C(true, "PRODUCT(A1:A3)", "6000"),
        C(true, "PRODUCTO(A1:A5)", "240000"),
        C(true, "MEDIANA(1;3;2)", "2"),
        C(true, "MEDIAN(1;2;3;4)", "2.5"),
        C(true, "MEDIANA(A1:A5)", "25"),
        C(true, "MEDIANA(5)", "5"),
        C(true, "MEDIANA(F1:F2)", "#¡DIV/0!"),
        C(true, "CONTARA(A1:A5)", "5"),
        C(true, "COUNTA(A1:A100)", "5"),
        C(true, "CONTARA(A1:A2;\"x\";9)", "4"),
        C(true, "CONTARA(F1:F2)", "1"),
        C(true, "CONTAR.BLANCO(A1:A10)", "5"),
        C(true, "COUNTBLANK(A1:A5)", "0"),
        C(true, "CONTAR.BLANCO(Q1:Q9)", "8"),
        C(true, "CONTAR.BLANCO(A1)", "0"),
        C(true, "CONTAR.BLANCO(A9)", "1"),
        C(true, "SUMAR.SI(A1:A5;\">15\")", "90"),
        C(true, "SUMIF(A1:A5,\">15\")", "90"),
        C(true, "SUMAR.SI(A1:A5;\">15\";B1:B5)", "10"),
        C(true, "SUMAR.SI(A1:A5;\"x\";B1:B5)", "4"),
        C(true, "SUMAR.SI(A1:A5;20)", "20"),
        C(true, "CONTAR.SI(A1:A5;\">15\")", "3"),
        C(true, "COUNTIF(A1:A5,\"<>10\")", "4"),
        C(true, "CONTAR.SI(A1:A5;10)", "1"),
        C(true, "CONTAR.SI(A1:A5;\"=x\")", "1"),
        C(true, "CONTAR.SI(A1:A5;\"X\")", "1"),
        C(true, "CONTAR.SI(A1:A5;D1)", "1"),
        C(true, "CONTAR.SI(A1:A5;\">=20\")", "3"),
        C(true, "CONTAR.SI(A1:A5;\"<=20\")", "2"),
        C(true, "CONTAR.SI(A1:A5;\"<30\")", "2"),
        C(true, "CONTAR.SI(C1:C4;\"An*\")", "2"),
        C(true, "CONTAR.SI(C1:C4;\"?eto\")", "1"),
        C(true, "CONTAR.SI(C1:C4;\"*\")", "3"),
        C(true, "CONTAR.SI(C1:C4;\"*i*\")", "1"),
        C(true, "CONTAR.SI(C1:C4;\"ana\")", "1"),
        C(true, "CONTAR.SI(C1:C4;\"\")", "1"),
        C(true, "CONTAR.SI(C1:C4;\"<>\")", "3"),
        C(true, "CONTAR.SI(A1:A100;\"\")", "95"),
        C(true, "CONTAR.SI(A1:A1000000;\"\")", "999995"),
        C(true, "CONTAR.SI(A1:A100;\"<>\")", "5"),
        C(true, "SUMAR.SI(A1:A1000000;\">0\")", "100"),
        C(true, "CONTAR.SI(A1:A5;\">100\")", "0"),
        C(true, "SUMAR.SI(A1:A5;\">100\")", "0"),
        C(true, "CONTAR.SI(A1:A5)", "#¡VALOR!"),
        C(true, "CONTAR.SI(A1:A5;1;2)", "#¡VALOR!"),
        C(true, "CONTAR.SI(5;1)", "#¡VALOR!"),
        C(true, "SUMAR.SI(A1:A5)", "#¡VALOR!"),
        C(true, "SUMAR.SI(A1:A5;1;B1:B5;1)", "#¡VALOR!"),
        C(true, "CONTAR.SI(A1:A5;1/0)", "#¡DIV/0!"),
        C(true, "SUMAR.SI(A1:A5;\">0\";F1:F5)", "#¡DIV/0!"),
        C(true, "CONTAR.SI(F1:F2;\">0\")", "0"),
        C(true, "SUMAR.SI.CONJUNTO(B1:B5;A1:A5;\">15\";B1:B5;\"<5\")", "5"),
        C(true, "SUMIFS(B1:B5,A1:A5,\">15\")", "10"),
        C(true, "CONTAR.SI.CONJUNTO(A1:A5;\">=10\";B1:B5;\"<=2\")", "2"),
        C(true, "COUNTIFS(A1:A5,\">=10\")", "4"),
        C(true, "CONTAR.SI.CONJUNTO(A1:A5;\">100\";B1:B5;\"<=2\")", "0"),
        C(true, "PROMEDIO.SI(A1:A5;\">15\")", "30"),
        C(true, "AVERAGEIF(A1:A5,\">15\",B1:B5)", "3.333333333"),
        C(true, "PROMEDIO.SI(A1:A5;\">15\";B1:B5)", "3.333333333"),
        C(true, "CONTAR.SI.CONJUNTO(A1:A100;\"\";B1:B100;\"\")", "95"),
        C(true, "CONTAR.SI.CONJUNTO(A1:A100;\"\";B1:B100;\">0\")", "0"),
        C(true, "CONTAR.SI.CONJUNTO(A1:A5;\">1\";B1:B4;\">1\")", "#¡VALOR!"),
        C(true, "SUMAR.SI.CONJUNTO(B1:B4;A1:A5;\">1\")", "#¡VALOR!"),
        C(true, "CONTAR.SI.CONJUNTO(A1:A5)", "#¡VALOR!"),
        C(true, "CONTAR.SI.CONJUNTO(A1:A5;\">1\";B1:B5)", "#¡VALOR!"),
        C(true, "SUMAR.SI.CONJUNTO(B1:B5)", "#¡VALOR!"),
        C(true, "SUMAR.SI.CONJUNTO(B1:B5;A1:A5)", "#¡VALOR!"),
        C(true, "PROMEDIO.SI(A1:A5;\">100\")", "#¡DIV/0!"),
        C(true, "PROMEDIO.SI(A1:A5;\"x\")", "#¡DIV/0!"),
        C(true, "PROMEDIO.SI(A1:A5)", "#¡VALOR!"),
        C(true, "BUSCARV(20;H1:J4;2;FALSO)", "b"),
        C(true, "VLOOKUP(20,H1:J4,2,0)", "b"),
        C(true, "BUSCARV(40;H1:J4;3;0)", "4"),
        C(true, "BUSCARV(25;H1:J4;2;VERDADERO)", "b"),
        C(true, "BUSCARV(25;H1:J4;2)", "b"),
        C(true, "BUSCARV(99;H1:J4;2)", "d"),
        C(true, "BUSCARV(10;H1:J4;2)", "a"),
        C(true, "BUSCARV(1;H1:J4;1;1)", "#N/A"),
        C(true, "BUSCARV(5;H1:J4;2)", "#N/A"),
        C(true, "BUSCARV(5;H1:J4;2;0)", "#N/A"),
        C(true, "BUSCARV(25;H1:J4;2;0)", "#N/A"),
        C(true, "BUSCARV(\"B\";I1:J4;2;0)", "2"),
        C(true, "BUSCARV(\"c*\";I1:J4;2;0)", "3"),
        C(true, "BUSCARV(\"c*\";I1:J4;2;1)", "3"),
        C(true, "BUSCARV(\"zz\";I1:J4;2)", "4"),
        C(true, "BUSCARV(\"20\";H1:J4;2;0)", "#N/A"),
        C(true, "BUSCARV(20;A1:B5;2;0)", "2"),
        C(true, "SI.ERROR(BUSCARV(5;H1:J4;2;0);\"no\")", "no"),
        C(true, "ESND(BUSCARV(5;H1:J4;2;0))", "VERDADERO"),
        C(true, "BUSCARV(20;H1:J4;4;0)", "#¡REF!"),
        C(true, "BUSCARV(20;H1:J4;0;0)", "#¡VALOR!"),
        C(true, "BUSCARV(20;H1:J4;-1;0)", "#¡VALOR!"),
        C(true, "BUSCARV(20;H1:J4)", "#¡VALOR!"),
        C(true, "BUSCARV(20;H1:J4;2;0;1)", "#¡VALOR!"),
        C(true, "BUSCARV(20;H1:J4;2;\"x\")", "#¡VALOR!"),
        C(true, "BUSCARV(20;H1:J4;\"x\";0)", "#¡VALOR!"),
        C(true, "BUSCARV(1/0;H1:J4;2;0)", "#¡DIV/0!"),
        C(true, "BUSCARV(1;X1:Y5;2;0)", "#N/A"),
        C(true, "BUSCARV(1;X1:Y5;2)", "#N/A"),
        C(true, "BUSCARH(3;L1:O2;2;0)", "y"),
        C(true, "HLOOKUP(3,L1:O2,2,FALSO)", "y"),
        C(true, "BUSCARH(9;L1:O2;2)", "z"),
        C(true, "BUSCARH(2.5;L1:O2;1)", "2"),
        C(true, "BUSCARH(9;L1:O2;1;0)", "#N/A"),
        C(true, "BUSCARH(3;L1:O2;3;0)", "#¡REF!"),
        C(true, "BUSCARH(3;L1:O2;0;0)", "#¡VALOR!"),
        C(true, "BUSCARH(3;L1:O2)", "#¡VALOR!"),
        C(true, "INDICE(H1:J4;2;3)", "2"),
        C(true, "INDEX(H1:J4,4,2)", "d"),
        C(true, "INDICE(H1:H4;3)", "30"),
        C(true, "INDICE(L1:O1;3)", "3"),
        C(true, "INDICE(H1:H4;3;1)", "30"),
        C(true, "INDICE(H1:J4;5;1)", "#¡REF!"),
        C(true, "INDICE(H1:J4;1;4)", "#¡REF!"),
        C(true, "INDICE(H1:J4;2)", "#¡VALOR!"),
        C(true, "INDICE(H1:J4;0;1)", "#¡VALOR!"),
        C(true, "INDICE(H1:J4;-1;1)", "#¡VALOR!"),
        C(true, "INDICE(H1:J4)", "#¡VALOR!"),
        C(true, "INDICE(Q1:Q5;1)", ""),
        C(true, "COINCIDIR(30;H1:H4;0)", "3"),
        C(true, "MATCH(30,H1:H4,0)", "3"),
        C(true, "COINCIDIR(35;H1:H4;1)", "3"),
        C(true, "COINCIDIR(35;H1:H4)", "3"),
        C(true, "COINCIDIR(25;P1:P3;-1)", "1"),
        C(true, "COINCIDIR(\"C\";I1:I4;0)", "3"),
        C(true, "COINCIDIR(\"c?\";I1:I4;0)", "#N/A"),
        C(true, "COINCIDIR(\"?\";I1:I4;0)", "1"),
        C(true, "COINCIDIR(3;L1:O1;0)", "3"),
        C(true, "COINCIDIR(\"z\";Q1:Q5;0)", "3"),
        C(true, "COINCIDIR(35;H1:H4;0)", "#N/A"),
        C(true, "COINCIDIR(5;H1:H4;1)", "#N/A"),
        C(true, "COINCIDIR(1;H1:J4;0)", "#N/A"),
        C(true, "COINCIDIR(1;H1:H4;0;1)", "#¡VALOR!"),
        C(true, "COINCIDIR(1)", "#¡VALOR!"),
        C(true, "BUSCARX(20;H1:H4;I1:I4)", "b"),
        C(true, "XLOOKUP(20,H1:H4,I1:I4)", "b"),
        C(true, "BUSCARX(25;H1:H4;I1:I4)", "#N/A"),
        C(true, "BUSCARX(25;H1:H4;I1:I4;\"nada\")", "nada"),
        C(true, "BUSCARX(20;H1:H4;I1:I4;\"nada\")", "b"),
        C(true, "BUSCARX(25;H1:H4;I1:I4;\"-\";-1)", "b"),
        C(true, "BUSCARX(25;H1:H4;I1:I4;\"-\";1)", "c"),
        C(true, "BUSCARX(99;H1:H4;I1:I4;\"-\";1)", "-"),
        C(true, "BUSCARX(1;H1:H4;I1:I4;\"-\";-1)", "-"),
        C(true, "BUSCARX(\"c*\";I1:I4;H1:H4;\"-\";2)", "30"),
        C(true, "BUSCARX(\"c*\";I1:I4;H1:H4;\"-\")", "-"),
        C(true, "BUSCARX(1;R1:R3;S1:S3)", "a"),
        C(true, "BUSCARX(1;R1:R3;S1:S3;\"x\";0;-1)", "b"),
        C(true, "BUSCARX(3;L1:O1;L2:O2)", "y"),
        C(true, "BUSCARX(3;L1:O1;H1:H4)", "30"),
        C(true, "BUSCARX(20;H1:H4;I1:I3)", "#¡VALOR!"),
        C(true, "BUSCARX(20;H1:H4;I1:J4)", "#¡VALOR!"),
        C(true, "BUSCARX(20;H1:J4;I1:I4)", "#N/A"),
        C(true, "BUSCARX(20;H1:H4;I1:I4;\"-\";7)", "#¡VALOR!"),
        C(true, "BUSCARX(20;H1:H4;I1:I4;\"-\";0;0)", "#¡VALOR!"),
        C(true, "BUSCARX(20;H1:H4)", "#¡VALOR!"),
        C(true, "BUSCARX(20;H1:H4;I1:I4;1;2;3;4)", "#¡VALOR!"),
        C(true, "BUSCARX(1/0;H1:H4;I1:I4)", "#¡DIV/0!"),
        C(true, "CONCATENAR(\"a\";1;\"b\")", "a1b"),
        C(true, "CONCATENATE(\"x\",\"y\")", "xy"),
        C(true, "CONCATENAR(C1;\"-\";C3)", "Ana-Beto"),
        C(true, "CONCAT(C1:C3)", "AnaAnibalBeto"),
        C(true, "CONCAT(\"a\";C1:C2;1)", "aAnaAnibal1"),
        C(true, "UNIRCADENAS(\"-\";VERDADERO;\"a\";\"\";\"b\")", "a-b"),
        C(true, "TEXTJOIN(\"-\",FALSO,\"a\",\"\",\"b\")", "a--b"),
        C(true, "UNIRCADENAS(\", \";VERDADERO;C1:C3)", "Ana, Anibal, Beto"),
        C(true, "UNIRCADENAS(\"-\";FALSO;C1:C4)", "Ana-Anibal-Beto-"),
        C(true, "UNIRCADENAS(\"-\";VERDADERO;C1:C4)", "Ana-Anibal-Beto"),
        C(true, "UNIRCADENAS(\"-\";FALSO;A1:A5)", "10-20-30-x-40"),
        C(true, "IZQUIERDA(\"hola\";2)", "ho"),
        C(true, "LEFT(\"hola\")", "h"),
        C(true, "IZQUIERDA(\"hola\";10)", "hola"),
        C(true, "IZQUIERDA(\"hola\";0)", ""),
        C(true, "IZQUIERDA(12345;2)", "12"),
        C(true, "IZQUIERDA(\"añb\";2)", "añ"),
        C(true, "DERECHA(\"hola\";3)", "ola"),
        C(true, "RIGHT(\"hola\")", "a"),
        C(true, "DERECHA(\"hola\";10)", "hola"),
        C(true, "EXTRAE(\"hola mundo\";6;5)", "mundo"),
        C(true, "MID(\"hola\",2,2)", "ol"),
        C(true, "EXTRAE(\"hola\";10;2)", ""),
        C(true, "EXTRAE(\"hola\";3;99)", "la"),
        C(true, "LARGO(\"año\")", "3"),
        C(true, "LEN(123)", "3"),
        C(true, "LARGO(\"\")", "0"),
        C(true, "LARGO(Q9)", "0"),
        C(false, "IZQUIERDA(\"hola\";-1)", "#¡VALOR!"),
        C(false, "DERECHA(\"hola\";-1)", "#¡VALOR!"),
        C(false, "IZQUIERDA()", "#¡VALOR!"),
        C(false, "IZQUIERDA(\"a\";1;2)", "#¡VALOR!"),
        C(false, "IZQUIERDA(\"a\";\"x\")", "#¡VALOR!"),
        C(false, "EXTRAE(\"hola\";0;1)", "#¡VALOR!"),
        C(false, "EXTRAE(\"hola\";1;-1)", "#¡VALOR!"),
        C(false, "EXTRAE(\"hola\";1)", "#¡VALOR!"),
        C(false, "LARGO()", "#¡VALOR!"),
        C(false, "LARGO(1/0)", "#¡DIV/0!"),
        C(false, "MAYUSC(\"añb\")", "AÑB"),
        C(false, "UPPER(\"abc\")", "ABC"),
        C(false, "MINUSC(\"ABC Ñ\")", "abc ñ"),
        C(false, "LOWER(\"ABC\")", "abc"),
        C(false, "NOMPROPIO(\"hola mUNDO o'neil\")", "Hola Mundo O'Neil"),
        C(false, "PROPER(\"ana-maria 2da\")", "Ana-Maria 2Da"),
        C(false, "NOMPROPIO(\"\")", ""),
        C(false, "ESPACIOS(\"  a   b  \")", "a b"),
        C(false, "TRIM(\"a\")", "a"),
        C(false, "ESPACIOS(\"   \")", ""),
        C(false, "MAYUSC(12)", "12"),
        C(false, "SUSTITUIR(\"a-b-c\";\"-\";\"+\")", "a+b+c"),
        C(false, "SUBSTITUTE(\"a-b-c\",\"-\",\"+\")", "a+b+c"),
        C(false, "SUSTITUIR(\"a-b-c\";\"-\";\"+\";2)", "a-b+c"),
        C(false, "SUSTITUIR(\"a-b-c\";\"-\";\"+\";5)", "a-b-c"),
        C(false, "SUSTITUIR(\"abc\";\"\";\"x\")", "abc"),
        C(false, "SUSTITUIR(\"ABC\";\"b\";\"x\")", "ABC"),
        C(false, "SUSTITUIR(\"aaa\";\"aa\";\"b\")", "ba"),
        C(false, "SUSTITUIR(\"abc\";\"b\";\"\")", "ac"),
        C(false, "ENCONTRAR(\"b\";\"abcb\")", "2"),
        C(false, "FIND(\"b\",\"abcb\",3)", "4"),
        C(false, "ENCONTRAR(\"\";\"abc\")", "1"),
        C(false, "ENCONTRAR(\"ñ\";\"año\")", "2"),
        C(false, "HALLAR(\"B\";\"abc\")", "2"),
        C(false, "SEARCH(\"a?c\",\"xxabcx\")", "3"),
        C(false, "HALLAR(\"a*c\";\"xxabcx\")", "3"),
        C(false, "HALLAR(\"~?\";\"a?b\")", "2"),
        C(false, "ENCONTRAR(\"B\";\"abc\")", "#¡VALOR!"),
        C(false, "ENCONTRAR(\"z\";\"abc\")", "#¡VALOR!"),
        C(false, "ENCONTRAR(\"a\";\"abc\";0)", "#¡VALOR!"),
        C(false, "ENCONTRAR(\"a\";\"abc\";9)", "#¡VALOR!"),
        C(false, "HALLAR(\"z\";\"abc\")", "#¡VALOR!"),
        C(false, "SUSTITUIR(\"a\";\"a\")", "#¡VALOR!"),
        C(false, "SUSTITUIR(\"a\";\"a\";\"b\";0)", "#¡VALOR!"),
        C(false, "ENCONTRAR(\"a\")", "#¡VALOR!"),
        C(false, "TEXTO(1234.5;\"#,##0.00\")", "1,234.50"),
        C(false, "TEXT(1234,\"#,##0\")", "1,234"),
        C(false, "TEXTO(1234567.891;\"#,##0.0\")", "1,234,567.9"),
        C(false, "TEXTO(-1234.5;\"#,##0.0\")", "-1,234.5"),
        C(false, "TEXTO(0.256;\"0%\")", "26%"),
        C(false, "TEXTO(0.256;\"0.0%\")", "25.6%"),
        C(false, "TEXTO(5;\"000\")", "005"),
        C(false, "TEXTO(3.14159;\"0.00\")", "3.14"),
        C(false, "TEXTO(3;\"0.00\")", "3.00"),
        C(false, "TEXTO(3.5;\"0.##\")", "3.5"),
        C(false, "TEXTO(3;\"0.##\")", "3"),
        C(false, "TEXTO(1.005;\"0.00\")", "1.01"),
        C(false, "TEXTO(0.5;\"#.00\")", ".50"),
        C(false, "TEXTO(0;\"0.00\")", "0.00"),
        C(false, "TEXTO(12;\"\$#,##0.00\")", "\$12.00"),
        C(false, "TEXTO(12;\"0 \"\"kg\"\"\")", "12 kg"),
        C(false, "TEXTO(2.5;\"0\")", "3"),
        C(false, "TEXTO(\"hola\";\"0\")", "hola"),
        C(false, "TEXTO(\"\";\"0.00\")", ""),
        C(false, "TEXTO(7;\"General\")", "7"),
        C(false, "TEXTO(1)", "#¡VALOR!"),
        C(false, "TEXTO(1;\"0\";2)", "#¡VALOR!"),
        C(false, "TEXTO(1/0;\"0\")", "#¡DIV/0!"),
        C(false, "TEXTO(1;\"0.0E+00\")", "#¡VALOR!"),
        C(false, "TEXTO(36526;\"dd/mm/aaaa\")", "01/01/2000"),
        C(false, "TEXT(36526,\"dd/mm/yyyy\")", "01/01/2000"),
        C(false, "TEXTO(36526;\"yyyy-mm-dd\")", "2000-01-01"),
        C(false, "TEXTO(36526;\"d/m/yy\")", "1/1/00"),
        C(false, "TEXTO(46306;\"dd/mm/aaaa\")", "11/10/2026"),
        C(false, "TEXTO(46306;\"mmmm\")", "octubre"),
        C(false, "TEXTO(46306;\"mmm\")", "oct"),
        C(false, "TEXTO(46306;\"dddd\")", "domingo"),
        C(false, "TEXTO(36526;\"dddd d \"\"de\"\" mmmm\")", "sábado 1 de enero"),
        C(false, "TEXTO(0.75;\"hh:mm\")", "18:00"),
        C(false, "TEXTO(0.5;\"h:mm AM/PM\")", "12:00 PM"),
        C(false, "TEXTO(0.25;\"hh:mm:ss\")", "06:00:00"),
        C(false, "TEXTO(46306.5;\"dd/mm/aaaa hh:mm\")", "11/10/2026 12:00"),
        C(false, "TEXTO(60;\"dd/mm/yyyy\")", "29/02/1900"),
        C(false, "TEXTO(\"2000-01-01\";\"dd/mm/yyyy\")", "01/01/2000"),
        C(false, "VALOR(\"12,5\")", "12.5"),
        C(false, "VALUE(\"1.234,50\")", "1234.5"),
        C(false, "VALOR(\"15%\")", "0.15"),
        C(false, "VALOR(\"\")", "0"),
        C(false, "VALOR(5)", "5"),
        C(false, "VALOR(\"2000-01-01\")", "36526"),
        C(false, "VALOR(\"1/1/2000\")", "36526"),
        C(false, "VALOR(\" 7 \")", "7"),
        C(false, "VALOR(\"abc\")", "#¡VALOR!"),
        C(false, "VALOR(\"12a\")", "#¡VALOR!"),
        C(false, "VALOR(VERDADERO)", "#¡VALOR!"),
        C(false, "VALOR()", "#¡VALOR!"),
        C(false, "VALOR(1/0)", "#¡DIV/0!"),
        C(false, "FECHA(2026;10;11)", "46306"),
        C(false, "DATE(2000,1,1)", "36526"),
        C(false, "FECHA(1900;1;1)", "1"),
        C(false, "FECHA(1900;3;1)", "61"),
        C(false, "FECHA(1900;2;29)", "60"),
        C(false, "AÑO(FECHA(2026;14;1))", "2027"),
        C(false, "MES(FECHA(2026;14;1))", "2"),
        C(false, "DIA(FECHA(2026;1;0))", "31"),
        C(false, "MES(FECHA(2026;1;0))", "12"),
        C(false, "MES(FECHA(2026;0;15))", "12"),
        C(false, "DIA(FECHA(2026;3;0))", "28"),
        C(false, "AÑO(FECHA(99;1;1))", "1999"),
        C(false, "AÑO(36526)", "2000"),
        C(false, "YEAR(36526)", "2000"),
        C(false, "ANO(36526)", "2000"),
        C(false, "MES(36526)", "1"),
        C(false, "MONTH(46306)", "10"),
        C(false, "DIA(36526)", "1"),
        C(false, "DAY(46306)", "11"),
        C(false, "DIA(60)", "29"),
        C(false, "MES(60)", "2"),
        C(false, "DIA(59)", "28"),
        C(false, "AÑO(\"2000-01-01\")", "2000"),
        C(false, "MES(\"11/10/2026\")", "10"),
        C(false, "DIAS(FECHA(2026;10;11);FECHA(2026;1;1))", "283"),
        C(false, "DAYS(36527,36526)", "1"),
        C(false, "DIAS(36526;36527)", "-1"),
        C(false, "DIASEM(36526)", "7"),
        C(false, "WEEKDAY(36526,2)", "6"),
        C(false, "DIASEM(36526;3)", "5"),
        C(false, "DIASEM(1)", "1"),
        C(false, "DIASEM(46306)", "1"),
        C(false, "FECHA(-1;1;1)", "#¡NUM!"),
        C(false, "FECHA(10000;1;1)", "#¡NUM!"),
        C(false, "FECHA(2026;1)", "#¡VALOR!"),
        C(false, "FECHA(2026;1;1;1)", "#¡VALOR!"),
        C(false, "FECHA(\"x\";1;1)", "#¡VALOR!"),
        C(false, "AÑO(-1)", "#¡NUM!"),
        C(false, "AÑO(\"hola\")", "#¡VALOR!"),
        C(false, "AÑO()", "#¡VALOR!"),
        C(false, "MES(1;2)", "#¡VALOR!"),
        C(false, "DIA(\"31/04/2026\")", "#¡VALOR!"),
        C(false, "DIASEM(36526;9)", "#¡NUM!"),
        C(false, "DIAS(1)", "#¡VALOR!"),
        C(false, "HOY(1)", "#¡VALOR!"),
        C(false, "AHORA(1)", "#¡VALOR!"),
        C(true, "ESERROR(1/0)", "VERDADERO"),
        C(true, "ISERROR(F1)", "VERDADERO"),
        C(true, "ESERROR(BUSCARV(5;H1:J4;2;0))", "VERDADERO"),
        C(true, "ESERROR(1)", "FALSO"),
        C(true, "ESERROR(\"x\")", "FALSO"),
        C(true, "ESBLANCO(A9)", "VERDADERO"),
        C(true, "ISBLANK(A1)", "FALSO"),
        C(true, "ESBLANCO(\"\")", "VERDADERO"),
        C(true, "ESBLANCO(F1)", "FALSO"),
        C(true, "ESNUMERO(1)", "VERDADERO"),
        C(true, "ISNUMBER(A1)", "VERDADERO"),
        C(true, "ESNUMERO(\"1\")", "FALSO"),
        C(true, "ESNUMERO(A4)", "FALSO"),
        C(true, "ESNUMERO(F1)", "FALSO"),
        C(true, "ESNUMERO(VERDADERO)", "FALSO"),
        C(true, "ESTEXTO(A4)", "VERDADERO"),
        C(true, "ISTEXT(A1)", "FALSO"),
        C(true, "ESND(1/0)", "FALSO"),
        C(true, "ESERROR()", "#¡VALOR!"),
        C(true, "ESBLANCO(1;2)", "#¡VALOR!"),
        C(true, "ESNUMERO()", "#¡VALOR!"),
        C(false, "\"abc", "#¡VALOR!"),
        C(false, "\"a\"&1/0", "#¡DIV/0!"),
        C(false, "1/0=1", "#¡DIV/0!"),
        C(false, "1=1/0", "#¡DIV/0!"),
        C(false, "\"3\"+4", "7"),
        C(false, "1+\"abc\"", "#¡VALOR!"),
        C(false, "0^0", "#¡NUM!"),
        C(false, "(-8)^0.5", "#¡NUM!"),
        C(false, "0^-1", "#¡DIV/0!"),
        C(false, "10^400", "#¡NUM!"),
        C(false, "1E308*10", "#¡NUM!"),
        C(false, "(-8)^(1/3)", "#¡NUM!"),
        C(false, "(-2)^3", "-8"),
        C(false, "Y()", "#¡VALOR!"),
        C(false, "NO()", "#¡VALOR!"),
        C(false, "NO(1;2)", "#¡VALOR!"),
        C(false, "Y(1;1/0)", "#¡DIV/0!"),
        C(false, "NO(\"x\")", "#¡VALOR!"),
        C(false, "SI.ERROR(1)", "#¡VALOR!"),
        C(false, "SI.ERROR(1;2;3)", "#¡VALOR!"),
        C(false, "MEDIANA(Q1:Q9)", "#¡NUM!"),
        C(false, "PRODUCTO(Q1:Q9)", "0"),
        C(false, "PRODUCTO()", "#¡VALOR!"),
        C(false, "MEDIANA()", "#¡VALOR!"),
        C(false, "ALEATORIO.ENTRE(5;5)", "5"),
        C(false, "ALEATORIO.ENTRE(6;1)", "#¡NUM!"),
        C(false, "ALEATORIO.ENTRE(1)", "#¡VALOR!"),
        C(false, "ALEATORIO(1)", "#¡VALOR!"),
        C(false, "RANDBETWEEN(2.2;2.8)", "#¡NUM!"),
        C(false, "CONTARA()", "#¡VALOR!"),
        C(false, "CONTAR.BLANCO()", "#¡VALOR!"),
        C(false, "CONTAR.BLANCO(A1;A2)", "#¡VALOR!"),
        C(true, "CONCATENAR(C1:C3)", "#¡VALOR!"),
        C(true, "CONCATENAR()", "#¡VALOR!"),
        C(true, "CONCAT()", "#¡VALOR!"),
        C(true, "UNIRCADENAS(\"-\";VERDADERO)", "#¡VALOR!"),
        C(true, "UNIRCADENAS(\"-\";\"x\";\"a\")", "#¡VALOR!"),
        C(true, "CONCAT(F1:F2)", "#¡DIV/0!"),
        C(true, "CONCATENAR(\"a\";1/0)", "#¡DIV/0!"),
        C(false, "TEXTO(-1;\"dd/mm/yyyy\")", "#¡NUM!"),
        C(false, "TEXTO(\"hola\";\"dd/mm/yyyy\")", "hola"),
        C(false, "NOEXISTE(1)", "#¿NOMBRE?"),
        C(false, "SUMAA(1)", "#¿NOMBRE?"),
        C(true, "SUMA(A1;A2,A3)", "60"),
        C(true, "SI(1<2,\"a\";\"b\")", "a"),
        C(false, "CONCATENAR(\"a,b\";\";\")", "a,b;"),
        C(true, "A1:A3*2", "#¡VALOR!"),
        C(true, "SI(A1:A3;1;2)", "#¡VALOR!"),
        C(true, "A1:A1*2", "20"),
        C(true, "SUMA(SI(VERDADERO;A1:A3;B1:B3))", "60"),
        )

        fun hoja(celdas: List<Pair<String, String>>): Calculadora {
            val c = Calculadora()
            // El reloj del PC en sus pruebas: 11-oct-2026 15:30.
            c.reloj = { java.time.LocalDateTime.of(2026, 10, 11, 15, 30) }
            for ((dir, v) in celdas) {
                val p = Celdas.leer(dir)!!
                c.poner(p[0], p[1], v)
            }
            return c
        }

        fun ver(c: Calculadora, dir: String): String {
            val p = Celdas.leer(dir)!!
            return c.mostrarGeneral(p[0], p[1])
        }

        /** Lo que sale en `Z99` al escribir ahí `=<formula>`, como `fc` del PC. */
        fun da(celdas: List<Pair<String, String>>, formula: String): String = ver(hoja(celdas + ("Z99" to "=$formula")), "Z99")
    }

    @Test
    fun `cada fila de la tabla del PC da lo mismo`() {
        val mal = CASOS.mapNotNull { k ->
            val r = runCatching { da(if (k.conDatos) DATOS else emptyList(), k.formula) }.getOrElse { "EXCEPCIÓN ${it.javaClass.simpleName}" }
            if (r == k.esperado) null else "=${k.formula}  →  «$r» y el PC da «${k.esperado}»"
        }
        assertEquals("${mal.size} de ${CASOS.size}:\n" + mal.joinToString("\n"), 0, mal.size)
    }

    @Test
    fun `un texto vacio devuelto por una formula tambien es blanco`() {
        assertEquals("2", da(listOf("A1" to "=\"\"", "A2" to "1"), "CONTAR.BLANCO(A1:A3)"))
        assertEquals("1", da(listOf("A1" to "=\"\"", "A2" to "1"), "CONTARA(A1:A3)"))
    }

    @Test
    fun `buscarv con la columna desordenada para en el primero que se pasa`() {
        val t = listOf("A1" to "5", "A2" to "50", "A3" to "7", "B1" to "a", "B2" to "b", "B3" to "c")
        assertEquals("a", da(t, "BUSCARV(8;A1:B3;2;1)"))
    }

    @Test
    fun `los ciclos se dicen y no se tapan`() {
        fun a1(vararg celdas: Pair<String, String>) = ver(hoja(celdas.toList()), "A1")
        assertEquals("#¡CICLO!", a1("A1" to "=BUSCARV(1;A1:B2;2;0)", "A2" to "1", "B2" to "5"))
        assertEquals("#¡CICLO!", a1("A1" to "=BUSCARV(2;B1:C3;2;0)", "B2" to "2", "C2" to "=A1"))
        assertEquals("#¡CICLO!", a1("A1" to "=INDICE(A1:A3;1)", "A2" to "5"))
        assertEquals("#¡CICLO!", a1("A1" to "=COINCIDIR(5;A1:A3;0)", "A2" to "5"))
        assertEquals("#¡CICLO!", a1("A1" to "=CONTAR.SI(A1:A3;\">0\")", "A2" to "5", "A3" to "6"))
        assertEquals("#¡CICLO!", a1("A1" to "=CONTARA(A1:A3)", "A2" to "5"))
        assertEquals("#¡CICLO!", a1("A1" to "=CONTAR.BLANCO(A1:A3)", "A2" to "5"))
        assertEquals("#¡CICLO!", a1("A1" to "=SI.ERROR(A1;0)"))
        assertEquals("#¡CICLO!", a1("A1" to "=ESERROR(A1)"))
        assertEquals("#¡CICLO!", a1("A1" to "=SUMA(A1:A3)", "A2" to "2", "A3" to "3"))
        // INDICE solo lee la celda que devuelve: desde A1, la A2 no es ciclo.
        assertEquals("5", a1("A1" to "=INDICE(A1:A3;2)", "A2" to "5"))
        // El ciclo no envenena a quien no está dentro.
        val h = hoja(listOf("A1" to "=B1", "B1" to "=A1", "C1" to "5", "D1" to "=C1*2"))
        assertEquals("#¡CICLO!", ver(h, "A1"))
        assertEquals("10", ver(h, "D1"))
        val b = hoja(listOf("A1" to "=BUSCARV(1;A1:B2;2;0)", "A2" to "1", "B2" to "5", "D1" to "1", "E1" to "7", "F1" to "=BUSCARV(1;D1:E1;2;0)"))
        assertEquals("7", ver(b, "F1"))
    }

    @Test
    fun `una columna larga encadenada se calcula entera`() {
        val celdas = ArrayList<Pair<String, String>>()
        celdas += "A1" to "1"
        for (f in 2..2000) celdas += "A$f" to "=A${f - 1}+1"
        val h = hoja(celdas)
        assertEquals("10", ver(h, "A10"))
        assertEquals("2000", ver(h, "A2000"))
    }

    @Test
    fun `hoy y ahora usan el reloj del calculo`() {
        val h = hoja(listOf("A1" to "=HOY()", "A2" to "=AHORA()", "A3" to "=ENTERO(AHORA())=HOY()"))
        val p1 = Celdas.leer("A1")!!; val p2 = Celdas.leer("A2")!!
        assertEquals(46306.0, (h.valor(p1[0], p1[1]) as Valor.Num).n, 0.0)
        assertEquals(46306.645833333, (h.valor(p2[0], p2[1]) as Valor.Num).n, 1e-6)
        assertEquals("VERDADERO", ver(h, "A3"))
    }

    @Test
    fun `aleatorio entre queda entre los limites`() {
        val celdas = (1..60).flatMap { listOf("A$it" to "=ALEATORIO.ENTRE(1;6)", "B$it" to "=ALEATORIO()") }
        val h = hoja(celdas)
        val vistos = HashSet<Double>()
        for (f in 0 until 60) {
            val a = (h.valor(f, 0) as Valor.Num).n
            assertTrue("$a", a in 1.0..6.0 && a == Math.floor(a))
            vistos += a
            val b = (h.valor(f, 1) as Valor.Num).n
            assertTrue("$b", b >= 0.0 && b < 1.0)
        }
        assertTrue("sesenta tiradas dieron solo $vistos", vistos.size >= 4)
    }

    @Test
    fun `el nombre en ingles da lo mismo que en espanol`() {
        val pares = listOf(
            "SUMA(1;2;3)" to "SUM(1,2,3)", "SI(1>0;\"a\";\"b\")" to "IF(1>0,\"a\",\"b\")",
            "SI.ERROR(1/0;7)" to "IFERROR(1/0,7)", "CONTAR.SI(A1:A5;\">15\")" to "COUNTIF(A1:A5,\">15\")",
            "SUMAR.SI(A1:A5;\">15\";B1:B5)" to "SUMIF(A1:A5,\">15\",B1:B5)", "BUSCARV(20;H1:J4;2;0)" to "VLOOKUP(20,H1:J4,2,0)",
            "INDICE(H1:J4;2;2)" to "INDEX(H1:J4,2,2)", "COINCIDIR(30;H1:H4;0)" to "MATCH(30,H1:H4,0)",
            "IZQUIERDA(\"hola\";2)" to "LEFT(\"hola\",2)", "NOMPROPIO(\"ab cd\")" to "PROPER(\"ab cd\")",
            "AÑO(36526)" to "YEAR(36526)", "ESNUMERO(A1)" to "ISNUMBER(A1)", "REDONDEAR(2.675;2)" to "ROUND(2.675,2)",
            "MAX(A1:A3)" to "MAXIMO(A1:A3)", "MAX(A1:A3)" to "max(a1:a3)"
        )
        for ((es, en) in pares) assertEquals("$es / $en", da(DATOS, es), da(DATOS, en))
    }

    @Test
    fun `cada nombre del catalogo del PC lo atiende alguna funcion`() {
        for (nombre in NOMBRES_DEL_PC) for (n in 0..6) {
            val args = List(n) { "1" }.joinToString(";")
            assertTrue("$nombre con $n argumentos", da(DATOS, "$nombre($args)") != "#¿NOMBRE?")
        }
        assertEquals("#¿NOMBRE?", da(emptyList(), "NOEXISTE(1)"))
        assertEquals("#¿NOMBRE?", da(emptyList(), "SUMAA(1)"))
    }

    @Test
    fun `formulas a medias y basura siguen siendo error`() {
        for (mala in listOf(
            "SI(", "SI(1;2", "SUMA(1;;2)", "SUMA(;1)", "1 +* 2", "((1)", "1)", "\"a\" \"b\"", "A1:", "A1:5",
            "{1,2}", "1 <> ", "&1", "TRUE FALSE", "NOEXISTE", "5%%%%%%%%%%%%%%%%%%%% 3"
        )) {
            val r = da(emptyList(), mala)
            assertTrue("«=$mala» tendría que ser un error y dio «$r»", r.startsWith("#"))
        }
        // Diez mil paréntesis, funciones o signos no revientan la pila.
        assertEquals("#¡VALOR!", da(emptyList(), "(".repeat(10_000) + "1" + ")".repeat(10_000)))
        assertEquals("#¡VALOR!", da(emptyList(), "SUMA(".repeat(10_000) + "1" + ")".repeat(10_000)))
        assertEquals("#¡VALOR!", da(emptyList(), "-".repeat(10_000) + "1"))
    }

    @Test
    fun `las cinco funciones de siempre se muestran igual`() {
        val h = hoja(listOf(
            "A1" to "10", "A2" to "20.5", "A3" to "x", "B1" to "=SUMA(A1:A3)", "B2" to "=CONTAR(A1:A3)",
            "B3" to "=PROMEDIO(A1:A3)", "B4" to "=MIN(A1:A3)", "B5" to "=MAX(A1:A3)", "B6" to "=SUMA()",
            "B7" to "=MAX()", "B8" to "=PROMEDIO()"
        ))
        assertEquals(listOf("30.5", "2", "15.25", "10", "20.5", "0", "0", "#¡DIV/0!"), (1..8).map { ver(h, "B$it") })
    }
}

/** Los nombres de `motor::CATALOGO` del PC, español e inglés. */
private val NOMBRES_DEL_PC = listOf(
    "SI", "IF", "Y", "AND", "O", "OR", "NO", "NOT", "SI.ERROR", "IFERROR", "SI.ND", "IFNA", "SI.CONJUNTO", "IFS", "VERDADERO", "TRUE", "FALSO", "FALSE", "SUMA", "SUM", "CONTAR", "COUNT", "PROMEDIO", "AVERAGE", "MIN", "MINIMO", "MAX", "MAXIMO", "REDONDEAR", "ROUND", "REDONDEAR.MAS", "ROUNDUP", "REDONDEAR.MENOS", "ROUNDDOWN", "ENTERO", "INT", "ABS", "RESIDUO", "MOD", "POTENCIA", "POWER", "RAIZ", "SQRT", "PRODUCTO", "PRODUCT", "MEDIANA", "MEDIAN", "PI", "ALEATORIO", "RAND", "ALEATORIO.ENTRE", "RANDBETWEEN", "CONTARA", "COUNTA", "CONTAR.BLANCO", "COUNTBLANK", "SUMAR.SI", "SUMIF", "SUMAR.SI.CONJUNTO", "SUMIFS", "CONTAR.SI", "COUNTIF", "CONTAR.SI.CONJUNTO", "COUNTIFS", "PROMEDIO.SI", "AVERAGEIF", "BUSCARV", "VLOOKUP", "BUSCARH", "HLOOKUP", "INDICE", "INDEX", "COINCIDIR", "MATCH", "BUSCARX", "XLOOKUP", "CONCATENAR", "CONCATENATE", "CONCAT", "UNIRCADENAS", "TEXTJOIN", "IZQUIERDA", "LEFT", "DERECHA", "RIGHT", "EXTRAE", "MID", "LARGO", "LEN", "MAYUSC", "UPPER", "MINUSC", "LOWER", "NOMPROPIO", "PROPER", "ESPACIOS", "TRIM", "SUSTITUIR", "SUBSTITUTE", "ENCONTRAR", "FIND", "HALLAR", "SEARCH", "TEXTO", "TEXT", "VALOR", "VALUE", "HOY", "TODAY", "AHORA", "NOW", "FECHA", "DATE", "AÑO", "ANO", "YEAR", "MES", "MONTH", "DIA", "DAY", "DIAS", "DAYS", "DIASEM", "WEEKDAY", "ESERROR", "ISERROR", "ESBLANCO", "ISBLANK", "ESNUMERO", "ISNUMBER", "ESND", "ISNA", "ESTEXTO", "ISTEXT"
)
