package com.forge.pixpin.excel

import com.forge.pixpin.motor.Celdas
import com.forge.pixpin.motor.EstiloDeCelda
import com.forge.pixpin.motor.TablaDeCalculo
import com.forge.pixpin.motor.TablaViva
import com.forge.pixpin.motor.Valor

/**
 * **Las tablas de PixPin en el visor de hojas** (25-oct-2026, el usuario: «quita la visualización
 * de Excel antigua y todo lo incorporas en la nueva… solo visualización normal»).
 *
 * Una [TablaDeCalculo] —la mini-app `tabla` del chat, una hoja de un Excel editada aquí o en el PC
 * (`libro-<mensaje>-<hoja>`), la tabla de un proyecto o un `.ods`/`.csv`/`.xls` leído con
 * [com.forge.pixpin.motor.ImportarHojas]— pasada a lo mismo que llega de `libpixpinoffice.so`
 * ([Pxhj.Geometria], [Pxhj.Trozo]), para que [VistaHoja] la pinte como un Excel.
 *
 * Es lo que hace el PC con `pixpin_proyecto::tabla_xlsx` (las tablas a un `.xlsx` en memoria que
 * lee el visor): lleva **los valores ya calculados** (el visor no calcula: enseña el resultado,
 * como Excel al abrir), el formato que tiene una tabla (negrita, cursiva, subrayado, tachado,
 * color de letra, fondo, alineación, ajuste y formato de número) y los anchos; las mismas medidas
 * por defecto (columna de [ANCHO_COLUMNA] px y fila de [ALTO_FILA] px al 100 %) y Calibri 11.
 * Aquí se salta el `.xlsx`: el .so puede no estar (la JVM de las pruebas, un teléfono que no es
 * arm64) y la tabla ya está en memoria. Sin nada de Android: se prueba en la JVM.
 */
object TablasEnElVisor {
    /** `pixpin_proyecto::tabla_xlsx::ANCHO_COLUMNA`: el ancho de una columna sin ancho propio. */
    const val ANCHO_COLUMNA = 88

    /** `tabla_xlsx::ALTO_FILA`: el alto de una fila, en px al 100 %. */
    const val ALTO_FILA = 22

    /** La letra de las tablas en el PC (`<name val="Calibri"/>`, `<sz val="11"/>`). */
    const val FAMILIA = "Calibri"
    const val PUNTOS = 11f

    private const val NEGRO = 0xFF000000.toInt()

    /** Una hoja lista para el visor: su nombre (el de la pestaña) y lo que se pinta de ella. */
    class Hoja(
        val nombre: String,
        val geometria: Pxhj.Geometria,
        /** Todos los estilos de la hoja; el 0 es el de siempre. */
        val estilos: List<Pxhj.Estilo>,
        /** Solo las filas con algo (texto o formato), en orden. */
        val filas: List<Pxhj.Fila>
    ) {
        private val porIndice = filas.associateBy { it.indice }

        fun fila(f: Int): Pxhj.Fila? = porIndice[f]

        /** Las filas [f0]..[f1] como un trozo; [conocidos]: los estilos que ya tiene quien lo pide. */
        fun trozo(f0: Int, f1: Int, conocidos: Int): Pxhj.Trozo {
            val desde = conocidos.coerceIn(0, estilos.size)
            val dentro = filas.filter { it.indice in f0..f1 }
            return Pxhj.Trozo(
                version = 1, f0 = f0, f1 = f1, desde = desde,
                estilos = estilos.subList(desde, estilos.size).toList(),
                filas = dentro,
                lados = Pxhj.Lados(ByteArray(0), IntArray(0), IntArray(0), ByteArray(0), IntArray(0))
            )
        }

        /** Lo que se ve en el rango, con tabuladores y saltos de línea (lo de «Copiar»). */
        fun copiar(f0: Int, c0: Int, f1: Int, c1: Int): String {
            val a = minOf(f1, geometria.ultimaFila)
            return (f0..maxOf(f0, a)).joinToString("\n") { f ->
                val fila = fila(f)
                (c0..c1).joinToString("\t") { c -> fila?.celda(c)?.texto.orEmpty() }
            }
        }
    }

    /**
     * El nombre de la pestaña de una tabla de libro: «Cuentas · Gastos» → «Gastos». Una tabla suelta
     * se queda con el suyo. Como `hoja_pin::libro::nombre_de_pestana` del PC.
     */
    fun nombreDePestana(nombre: String): String {
        val i = nombre.lastIndexOf(" · ")
        if (i < 0) return nombre.ifBlank { "Tabla" }
        val h = nombre.substring(i + 3)
        return if (h.isBlank()) nombre else h
    }

    /** El libro (la lista de hojas) de unas tablas: todas visibles, la primera delante. */
    fun libro(hojas: List<Hoja>, activa: Int = 0): Pxhj.Libro =
        Pxhj.Libro(hojas.map { Pxhj.InfoHoja(it.nombre, 0, 0) }, activa.coerceIn(0, maxOf(0, hojas.size - 1)))

    /** `#rrggbb` → ARGB opaco; lo que no es un color, null (como `color_rgb` del PC). */
    fun color(t: String?): Int? {
        val s = t?.trim()?.removePrefix("#") ?: return null
        if (s.length != 6 || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return (0xFF000000 or s.toLong(16)).toInt()
    }

    /** La hoja de [t], con el nombre de pestaña [nombre]. Calcula sus fórmulas: fuera de la pantalla. */
    fun hoja(t: TablaDeCalculo, nombre: String = nombreDePestana(t.nombre)): Hoja {
        val v = TablaViva(t)
        val nf = v.calc.filas
        val nc = v.calc.cols
        // Las columnas: las de los anchos puestos también cuentan (una columna ancha y vacía se ve).
        val conAncho = t.anchos.keys.mapNotNull { l -> Celdas.columna(l).takeIf { it in 0 until Celdas.MAX_COLS } }
        val conEstilo = t.estilos.keys.mapNotNull { Celdas.leer(it) }
        val columnas = maxOf(nc, (conAncho.maxOrNull() ?: -1) + 1, (conEstilo.maxOfOrNull { it[1] } ?: -1) + 1)
        val filasUsadas = maxOf(nf, (conEstilo.maxOfOrNull { it[0] } ?: -1) + 1)
        val xs = IntArray(columnas + 1)
        for (c in 0 until columnas) xs[c + 1] = xs[c] + anchoDe(t, c)
        val ys = IntArray(filasUsadas + 1) { it * ALTO_FILA }

        val estilos = LinkedHashMap<Pxhj.Estilo, Int>()
        val normal = estilo(null, 0)
        estilos[normal] = 0
        // Por filas y, dentro, por columnas (el orden de [Pxhj.Fila.celda]).
        val porFila = HashMap<Int, java.util.TreeMap<Int, Pxhj.Celda>>()
        fun poner(f: Int, c: Int) {
            val e = v.estilo(f, c)
            val crudo = v.calc.crudo(f, c)
            if (crudo.isEmpty() && (e == null || e.sinFormato)) return
            val valor = if (crudo.isEmpty()) Valor.Vacio else v.calc.valor(f, c)
            val clase = when (valor) {
                is Valor.Num -> 1
                is Valor.Txt -> if (crudo.isEmpty()) 0 else 2
                is Valor.Log -> 3
                is Valor.Err -> 4
                Valor.Vacio -> 0
            }
            val texto = if (crudo.isEmpty()) "" else v.visto(f, c)
            val horizontal = when (e?.a) {
                "i" -> 1
                "c" -> 2
                "d" -> 3
                // Sin alineación propia, la que toca por el valor (como «General» en Excel).
                else -> when (if (crudo.isEmpty()) 'i' else v.calc.alineacion(f, c)) { 'd' -> 3; 'c' -> 2; else -> 1 }
            }
            val es = estilo(e, horizontal)
            val i = estilos.getOrPut(es) { estilos.size }
            porFila.getOrPut(f) { java.util.TreeMap() }[c] = Pxhj.Celda(c, i, if (texto.isEmpty()) 0 else clase, texto, null, -1, null)
        }
        for (k in v.calc.todo().keys) poner(Celdas.filaDe(k), Celdas.colDe(k))
        for (d in conEstilo) if (porFila[d[0]]?.containsKey(d[1]) != true) poner(d[0], d[1])
        val filas = porFila.toSortedMap().map { (f, celdas) -> Pxhj.Fila(f, false, false, 0, celdas.values.toTypedArray()) }

        val geo = Pxhj.Geometria(
            version = 1, completa = true, filasFijas = 0, columnasFijas = 0,
            cuadricula = true, encabezados = true, zoom = 100,
            primeraFila = 0, primeraColumna = 0, filaActiva = 0, columnaActiva = 0, colorPestana = 0,
            anchoDefecto = ANCHO_COLUMNA, altoDefecto = ALTO_FILA,
            ultimaFila = maxOf(0, filasUsadas - 1), ultimaColumna = maxOf(0, columnas - 1),
            anchoUsado = xs[columnas], altoUsado = ys[filasUsadas],
            xs = xs, ys = ys, rellenosDeColumnas = IntArray(0),
            combinadas = emptyList(), enlaces = emptyList(), dibujos = emptyList()
        )
        return Hoja(nombre, geo, estilos.keys.toList(), filas)
    }

    /** El ancho de la columna [c]: el puesto a mano (en px, como lo lee el PC) o [ANCHO_COLUMNA]. */
    fun anchoDe(t: TablaDeCalculo, c: Int): Int =
        t.anchos[Celdas.letras(c)]?.takeIf { it > 0 } ?: ANCHO_COLUMNA

    /** El estilo del visor de un [EstiloDeCelda] (o del de siempre, null) con la alineación ya decidida. */
    fun estilo(e: EstiloDeCelda?, horizontal: Int): Pxhj.Estilo {
        var letra = 0
        if (e?.n == true) letra = letra or 1
        if (e?.k == true) letra = letra or 2
        if (e?.t == true) letra = letra or 4
        if (e?.s == true) letra = letra or 8
        return Pxhj.Estilo(
            familia = FAMILIA, puntos = PUNTOS, letra = letra,
            colorTexto = color(e?.c) ?: NEGRO,
            relleno = color(e?.f) ?: 0,
            horizontal = horizontal,
            // Abajo, como Excel por defecto.
            vertical = 2,
            ajustar = e?.w == true, encoger = false, sangria = 0, giro = 0
        )
    }
}
