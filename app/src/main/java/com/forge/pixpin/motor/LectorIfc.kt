package com.forge.pixpin.motor

import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **Un modelo de Revit, leído de su IFC** (14-sep-2026): lo que el usuario pidió para el croquis
 * 3D. IFC es el formato abierto que Revit exporta de fábrica (Archivo → Exportar → IFC), y el
 * único que guarda **qué es cada cosa**: muro, losa, ventana, puerta… Con eso cada pieza sale
 * con su nombre y su color.
 *
 * ## Cómo se lee sin reventar la memoria
 *
 * Un IFC es texto STEP —una entidad por línea, `#12=IFCWALL(...)`— y uno de un edificio pasa de
 * los cien megas. Cargarlo entero en objetos es lo que tumba un teléfono (ver la muerte por
 * memoria del 14-sep). Así que:
 *
 * 1. El archivo se **mapea** en memoria (no se copia) y se recorre una vez apuntando solo dónde
 *    empieza cada entidad y de qué tipo es: dos enteros por entidad.
 * 2. Los argumentos se leen **cuando hacen falta**, desde esa posición, y solo los de las
 *    entidades que llevan a geometría.
 * 3. La geometría de una representación que se repite —cada ventana igual de una familia es un
 *    `IfcMappedItem` de la misma— se triangula una vez.
 *
 * ## Qué geometría se entiende
 *
 * La que exporta Revit: `IfcExtrudedAreaSolid` (con perfiles rectangular, circular, en I,
 * arbitrario con y sin huecos, polilínea, curva compuesta con arcos, curva por índices),
 * `IfcFacetedBrep`, `IfcTriangulatedFaceSet`, `IfcPolygonalFaceSet`, modelos de superficies,
 * `IfcMappedItem` y los recortes booleanos.
 *
 * **Los huecos de puertas y ventanas se restan** (15-sep-2026): los de `IfcRelVoidsElement` —un
 * `IfcOpeningElement` por hueco, que es como los exporta Revit— y los de `IfcBooleanResult`
 * (resta, intersección, y el recorte por un semiespacio que deja la cabeza de un muro bajo el
 * tejado). Ver [Csg]. Un elemento de más de [TOPE_PARA_RESTAR] triángulos se queda sin restar:
 * a partir de ahí el recorte tarda más de lo que se gana.
 *
 * Colores: los de `IfcStyledItem` del propio sólido o de su material; si no hay, uno por tipo.
 */
object LectorIfc {

    class NoSeLee(mensaje: String) : Exception(mensaje)

    /** Tope de triángulos: más que esto no se mueve con el dedo en un teléfono. */
    const val TOPE_DE_TRIANGULOS = 1_500_000

    /** Hasta cuántos triángulos se le restan huecos a un elemento. */
    const val TOPE_PARA_RESTAR = 6_000

    fun esIfc(nombre: String) = nombre.substringAfterLast('.', "").lowercase() == "ifc"

    fun leer(archivo: File, avance: (Float) -> Unit = {}): Malla3D {
        RandomAccessFile(archivo, "r").use { raf ->
            val canal = raf.channel
            if (canal.size() > Int.MAX_VALUE) throw NoSeLee("El IFC es demasiado grande (más de 2 GB)")
            val mapa = canal.map(FileChannel.MapMode.READ_ONLY, 0, canal.size())
            val cabeza = ByteArray(minOf(64, canal.size().toInt())).also { mapa.get(0, it) }
            val texto = String(cabeza, Charsets.ISO_8859_1)
            if (!texto.contains("ISO-10303-21")) {
                if (texto.startsWith("PK")) throw NoSeLee("Es un IFC comprimido (.ifczip): descomprímelo o expórtalo sin comprimir")
                throw NoSeLee("El archivo no es un IFC")
            }
            return Lector(mapa, avance).leer()
        }
    }

    // ---- Valores STEP ----

    private class Ref(val id: Int)
    private class Enumerado(val nombre: String)
    private class Tipado(val tipo: String, val valor: Any?)

    private class Lector(val b: MappedByteBuffer, val avance: (Float) -> Unit) {
        val n = b.limit()

        // Índice: id → (posición del paréntesis, tipo).
        var ids = IntArray(1 shl 16)
        var posiciones = IntArray(1 shl 16)
        var tipos = IntArray(1 shl 16)
        var cuantas = 0
        val nombresDeTipo = ArrayList<String>()
        val codigoDeTipo = HashMap<String, Int>()
        lateinit var buscar: IntIntMapa

        fun leer(): Malla3D {
            indexar()
            avance(0.2f)
            buscar = IntIntMapa(cuantas * 2)
            for (i in 0 until cuantas) buscar.poner(ids[i], i)
            unidades()
            estilos()
            huecos()
            val salida = Malla3D.Constructor()
            val productos = (0 until cuantas).filter { esProducto(nombresDeTipo[tipos[it]]) }
            for ((k, i) in productos.withIndex()) {
                if (salida.cuantosTriangulos > TOPE_DE_TRIANGULOS) break
                runCatching { producto(i, salida) }
                if (k % 200 == 0) avance(0.2f + 0.8f * k / productos.size.coerceAtLeast(1))
            }
            if (salida.cuantosTriangulos == 0) throw NoSeLee("El IFC no trae geometría que se pueda leer")
            return salida.malla()
        }

        // ---- Índice ----

        private fun indexar() {
            var i = 0
            // Saltar la cabecera hasta DATA;
            val data = "DATA;".toByteArray()
            i = buscarBytes(data, 0).let { if (it < 0) 0 else it + data.size }
            val tipo = StringBuilder()
            while (i < n) {
                val c = b.get(i).toInt()
                if (c == '#'.code) {
                    var j = i + 1
                    var id = 0
                    while (j < n) { val d = b.get(j).toInt(); if (d in 48..57) { id = id * 10 + (d - 48); j++ } else break }
                    while (j < n && b.get(j).toInt().let { it == ' '.code || it == '\t'.code }) j++
                    if (j < n && b.get(j).toInt() == '='.code) {
                        j++
                        while (j < n && b.get(j).toInt().let { it == ' '.code || it == '\t'.code || it == '\r'.code || it == '\n'.code }) j++
                        tipo.setLength(0)
                        while (j < n) {
                            val d = b.get(j).toInt()
                            if (d == '('.code || d == ' '.code) break
                            tipo.append(d.toChar()); j++
                        }
                        while (j < n && b.get(j).toInt() != '('.code) j++
                        val nombre = tipo.toString().uppercase()
                        val codigo = codigoDeTipo.getOrPut(nombre) { nombresDeTipo += nombre; nombresDeTipo.size - 1 }
                        if (cuantas == ids.size) {
                            ids = ids.copyOf(cuantas * 2); posiciones = posiciones.copyOf(cuantas * 2); tipos = tipos.copyOf(cuantas * 2)
                        }
                        ids[cuantas] = id; posiciones[cuantas] = j; tipos[cuantas] = codigo; cuantas++
                        // Hasta el ';' de fuera de las cadenas.
                        i = finDeEntidad(j)
                        continue
                    }
                    i = j
                    continue
                }
                i++
            }
        }

        private fun finDeEntidad(desde: Int): Int {
            var i = desde
            var enCadena = false
            while (i < n) {
                val c = b.get(i).toInt()
                if (enCadena) {
                    if (c == '\''.code) {
                        if (i + 1 < n && b.get(i + 1).toInt() == '\''.code) i++ else enCadena = false
                    }
                } else if (c == '\''.code) enCadena = true
                else if (c == ';'.code) return i + 1
                i++
            }
            return n
        }

        private fun buscarBytes(p: ByteArray, desde: Int): Int {
            val tope = minOf(n, 1 shl 20)
            var i = desde
            while (i + p.size <= tope) {
                var ok = true
                for (k in p.indices) if (b.get(i + k) != p[k]) { ok = false; break }
                if (ok) return i
                i++
            }
            return -1
        }

        // ---- Argumentos ----

        private val cache = HashMap<Int, List<Any?>>()

        fun tipoDe(id: Int): String? = buscar.dar(id).takeIf { it >= 0 }?.let { nombresDeTipo[tipos[it]] }

        fun args(id: Int): List<Any?>? {
            cache[id]?.let { return it }
            val k = buscar.dar(id)
            if (k < 0) return null
            val p = Parser(posiciones[k])
            val lista = p.lista()
            if (cache.size > 200_000) cache.clear()
            cache[id] = lista
            return lista
        }

        inner class Parser(var i: Int) {
            fun blancos() { while (i < n && b.get(i).toInt().let { it == ' '.code || it == '\r'.code || it == '\n'.code || it == '\t'.code }) i++ }
            fun lista(): List<Any?> {
                // En '('
                i++
                val l = ArrayList<Any?>(8)
                blancos()
                if (i < n && b.get(i).toInt() == ')'.code) { i++; return l }
                while (i < n) {
                    blancos()
                    l += valor()
                    blancos()
                    if (i >= n) break
                    val c = b.get(i).toInt()
                    i++
                    if (c == ')'.code) break
                }
                return l
            }
            fun valor(): Any? {
                val c = b.get(i).toInt()
                return when {
                    c == '#'.code -> {
                        i++; var id = 0
                        while (i < n) { val d = b.get(i).toInt(); if (d in 48..57) { id = id * 10 + d - 48; i++ } else break }
                        Ref(id)
                    }
                    c == '$'.code -> { i++; null }
                    c == '*'.code -> { i++; null }
                    c == '('.code -> lista()
                    c == '\''.code -> cadena()
                    c == '.'.code -> {
                        i++; val s = StringBuilder()
                        while (i < n && b.get(i).toInt() != '.'.code) { s.append(b.get(i).toInt().toChar()); i++ }
                        i++; Enumerado(s.toString())
                    }
                    c == '-'.code || c == '+'.code || c in 48..57 -> numero()
                    c == '"'.code -> { i++; while (i < n && b.get(i).toInt() != '"'.code) i++; i++; null }
                    else -> {
                        // Tipado: IFCLABEL('x'), IFCREAL(1.)
                        val s = StringBuilder()
                        while (i < n && b.get(i).toInt().let { it != '('.code && it != ','.code && it != ')'.code }) { s.append(b.get(i).toInt().toChar()); i++ }
                        if (i < n && b.get(i).toInt() == '('.code) {
                            val dentro = lista()
                            Tipado(s.toString().trim().uppercase(), dentro.firstOrNull())
                        } else null
                    }
                }
            }
            fun numero(): Double {
                val s = StringBuilder()
                while (i < n) {
                    val d = b.get(i).toInt()
                    if (d in 48..57 || d == '.'.code || d == '-'.code || d == '+'.code || d == 'E'.code || d == 'e'.code) { s.append(d.toChar()); i++ } else break
                }
                return s.toString().toDoubleOrNull() ?: 0.0
            }
            fun cadena(): String {
                i++
                val bytes = java.io.ByteArrayOutputStream()
                while (i < n) {
                    val d = b.get(i).toInt()
                    if (d == '\''.code) {
                        if (i + 1 < n && b.get(i + 1).toInt() == '\''.code) { bytes.write(d); i += 2; continue }
                        i++; break
                    }
                    bytes.write(d); i++
                }
                return decodificar(bytes.toString(Charsets.ISO_8859_1.name()))
            }
        }

        /** Las secuencias de escape de STEP: `\X2\00F1\X0\` es «ñ», `\S\` y `\X\` también. */
        private fun decodificar(s: String): String {
            if (!s.contains('\\')) return s
            val r = StringBuilder()
            var i = 0
            while (i < s.length) {
                when {
                    s.startsWith("\\X2\\", i) -> {
                        val fin = s.indexOf("\\X0\\", i + 4).takeIf { it > 0 } ?: s.length
                        val hex = s.substring(i + 4, fin)
                        var k = 0
                        while (k + 4 <= hex.length) { r.append(hex.substring(k, k + 4).toInt(16).toChar()); k += 4 }
                        i = fin + 4
                    }
                    s.startsWith("\\X\\", i) && i + 5 <= s.length -> {
                        r.append(s.substring(i + 3, i + 5).toIntOrNull(16)?.toChar() ?: '?'); i += 5
                    }
                    s.startsWith("\\S\\", i) && i + 4 <= s.length -> { r.append((s[i + 3].code + 128).toChar()); i += 4 }
                    else -> { r.append(s[i]); i++ }
                }
            }
            return r.toString()
        }

        fun ref(v: Any?): Int = (v as? Ref)?.id ?: -1
        fun num(v: Any?): Double = when (v) {
            is Double -> v
            is Tipado -> num(v.valor)
            else -> 0.0
        }
        fun texto(v: Any?): String = when (v) {
            is String -> v
            is Tipado -> texto(v.valor)
            else -> ""
        }
        @Suppress("UNCHECKED_CAST")
        fun lista(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()

        // ---- Unidades ----

        var metros = 1.0
        var radianes = 1.0

        private fun unidades() {
            for (i in 0 until cuantas) {
                val t = nombresDeTipo[tipos[i]]
                if (t != "IFCUNITASSIGNMENT") continue
                for (u in lista(args(ids[i])?.getOrNull(0))) {
                    val id = ref(u)
                    val a = args(id) ?: continue
                    when (tipoDe(id)) {
                        "IFCSIUNIT" -> {
                            val cual = (a.getOrNull(1) as? Enumerado)?.nombre
                            val prefijo = (a.getOrNull(2) as? Enumerado)?.nombre
                            val f = when (prefijo) { "MILLI" -> 0.001; "CENTI" -> 0.01; "DECI" -> 0.1; "KILO" -> 1000.0; else -> 1.0 }
                            if (cual == "LENGTHUNIT") metros = f
                            if (cual == "PLANEANGLEUNIT") radianes = 1.0
                        }
                        "IFCCONVERSIONBASEDUNIT" -> {
                            val cual = (a.getOrNull(1) as? Enumerado)?.nombre
                            val medida = args(ref(a.getOrNull(3)))
                            val factor = num(medida?.getOrNull(0))
                            val baseArgs = args(ref(medida?.getOrNull(1)))
                            val prefijo = (baseArgs?.getOrNull(2) as? Enumerado)?.nombre
                            val fb = when (prefijo) { "MILLI" -> 0.001; "CENTI" -> 0.01; else -> 1.0 }
                            if (cual == "LENGTHUNIT" && factor > 0) metros = factor * fb
                            if (cual == "PLANEANGLEUNIT" && factor > 0) radianes = factor
                        }
                    }
                }
                break
            }
        }

        // ---- Estilos ----

        /** Del sólido (id del item de representación) a su color. */
        val colorDeItem = HashMap<Int, Int>()
        /** Del material a su color. */
        val colorDeMaterial = HashMap<Int, Int>()
        /** Del producto a su material. */
        val materialDeProducto = HashMap<Int, Int>()

        private fun estilos() {
            for (i in 0 until cuantas) {
                when (nombresDeTipo[tipos[i]]) {
                    "IFCSTYLEDITEM" -> {
                        val a = args(ids[i]) ?: continue
                        val item = ref(a.getOrNull(0))
                        val color = colorDeEstilos(lista(a.getOrNull(1))) ?: continue
                        if (item >= 0) colorDeItem[item] = color
                    }
                    "IFCMATERIALDEFINITIONREPRESENTATION" -> {
                        val a = args(ids[i]) ?: continue
                        val material = ref(a.getOrNull(3))
                        for (r in lista(a.getOrNull(2))) {
                            val rep = args(ref(r)) ?: continue
                            for (it in lista(rep.getOrNull(3))) {
                                val styled = ref(it)
                                if (tipoDe(styled) != "IFCSTYLEDITEM") continue
                                colorDeEstilos(lista(args(styled)?.getOrNull(1)))?.let { c -> colorDeMaterial[material] = c }
                            }
                        }
                    }
                    "IFCRELASSOCIATESMATERIAL" -> {
                        val a = args(ids[i]) ?: continue
                        val material = primerMaterial(ref(a.getOrNull(5)), 0)
                        if (material < 0) continue
                        for (o in lista(a.getOrNull(4))) materialDeProducto[ref(o)] = material
                    }
                }
            }
        }

        private fun primerMaterial(id: Int, profundidad: Int): Int {
            if (id < 0 || profundidad > 4) return -1
            val a = args(id) ?: return -1
            return when (tipoDe(id)) {
                "IFCMATERIAL" -> id
                "IFCMATERIALLAYERSETUSAGE" -> primerMaterial(ref(a.getOrNull(0)), profundidad + 1)
                "IFCMATERIALLAYERSET" -> lista(a.getOrNull(0)).firstOrNull()?.let { primerMaterial(ref(it), profundidad + 1) } ?: -1
                "IFCMATERIALLAYER" -> primerMaterial(ref(a.getOrNull(0)), profundidad + 1)
                "IFCMATERIALLIST" -> lista(a.getOrNull(0)).firstOrNull()?.let { primerMaterial(ref(it), profundidad + 1) } ?: -1
                "IFCMATERIALCONSTITUENTSET" -> lista(a.getOrNull(2)).firstOrNull()?.let { primerMaterial(ref(it), profundidad + 1) } ?: -1
                "IFCMATERIALCONSTITUENT" -> primerMaterial(ref(a.getOrNull(2)), profundidad + 1)
                "IFCMATERIALPROFILESETUSAGE" -> primerMaterial(ref(a.getOrNull(0)), profundidad + 1)
                "IFCMATERIALPROFILESET" -> lista(a.getOrNull(2)).firstOrNull()?.let { primerMaterial(ref(it), profundidad + 1) } ?: -1
                "IFCMATERIALPROFILE" -> primerMaterial(ref(a.getOrNull(2)), profundidad + 1)
                else -> -1
            }
        }

        private fun colorDeEstilos(estilos: List<Any?>): Int? {
            for (e in estilos) {
                val id = ref(e)
                when (tipoDe(id)) {
                    "IFCPRESENTATIONSTYLEASSIGNMENT" -> colorDeEstilos(lista(args(id)?.getOrNull(0)))?.let { return it }
                    "IFCSURFACESTYLE" -> {
                        for (s in lista(args(id)?.getOrNull(2))) {
                            val sid = ref(s)
                            val t = tipoDe(sid)
                            if (t == "IFCSURFACESTYLERENDERING" || t == "IFCSURFACESTYLESHADING") {
                                val a = args(sid) ?: continue
                                val c = args(ref(a.getOrNull(0))) ?: continue
                                val transparencia = num(a.getOrNull(1)).coerceIn(0.0, 0.9)
                                fun canal(v: Any?) = (num(v).coerceIn(0.0, 1.0) * 255).toInt()
                                val alfa = ((1 - transparencia) * 255).toInt()
                                return (alfa shl 24) or (canal(c.getOrNull(1)) shl 16) or (canal(c.getOrNull(2)) shl 8) or canal(c.getOrNull(3))
                            }
                        }
                    }
                }
            }
            return null
        }

        // ---- Huecos ----

        /** Elemento → sus huecos (`IfcOpeningElement`), de los `IfcRelVoidsElement`. */
        private val huecosDe = HashMap<Int, MutableList<Int>>()

        private fun huecos() {
            val codigo = codigoDeTipo["IFCRELVOIDSELEMENT"] ?: return
            for (i in 0 until cuantas) {
                if (tipos[i] != codigo) continue
                val a = args(ids[i]) ?: continue
                val host = ref(a.getOrNull(4)); val hueco = ref(a.getOrNull(5))
                if (host >= 0 && hueco >= 0) huecosDe.getOrPut(host) { ArrayList() } += hueco
            }
        }

        /** Las representaciones de cuerpo de un producto (su `IfcProductDefinitionShape`). */
        private fun cuerpo(forma: List<Any?>): List<Int> {
            val reps = lista(forma.getOrNull(2)).map { ref(it) }
            // El cuerpo; si no hay, lo que haya que no sea eje, caja o planta.
            return reps.filter { r ->
                val ident = texto(args(r)?.getOrNull(1))
                ident.equals("Body", true) || ident.equals("Facetation", true) || ident.equals("Mesh", true)
            }.ifEmpty {
                reps.filter { r -> texto(args(r)?.getOrNull(1)).let { it !in IGNORAR_REP } }
            }
        }

        /** El elemento que está en [local], con sus huecos restados, a [salida]. */
        private fun restarHuecos(local: Malla3D.Constructor, huecos: List<Int>, colorBase: Int, salida: Malla3D.Constructor) {
            val malla = local.malla()
            if (malla.cuantosTriangulos == 0) return
            if (malla.cuantosTriangulos > TOPE_PARA_RESTAR) { Csg.volcar(Csg.solido(malla), salida); return }
            var solido = Csg.solido(malla)
            val color = malla.colores.firstOrNull() ?: colorBase
            for (h in huecos) {
                val a = args(h) ?: continue
                val colocacion = matrizDeColocacion(ref(a.getOrNull(5)), 0)
                val forma = args(ref(a.getOrNull(6))) ?: continue
                val suyo = Malla3D.Constructor()
                for (r in cuerpo(forma)) {
                    val rep = args(r) ?: continue
                    for (item in lista(rep.getOrNull(3))) geometria(ref(item), colocacion, color, suyo, 0)
                }
                val m = suyo.malla()
                if (m.cuantosTriangulos == 0 || m.cuantosTriangulos > TOPE_PARA_RESTAR) continue
                solido = runCatching { Csg.restar(solido, Csg.solido(m), color) }.getOrDefault(solido)
            }
            Csg.volcar(solido, salida)
        }

        // ---- Productos ----

        private fun esProducto(t: String): Boolean = t in PRODUCTOS

        private fun producto(k: Int, salida: Malla3D.Constructor) {
            val id = ids[k]
            val a = args(id) ?: return
            val tipo = nombresDeTipo[tipos[k]]
            val colocacion = matrizDeColocacion(ref(a.getOrNull(5)), 0)
            val forma = args(ref(a.getOrNull(6))) ?: return
            val material = materialDeProducto[id]
            val colorBase = material?.let { colorDeMaterial[it] } ?: COLOR_POR_TIPO[tipo] ?: 0xFFBDBDBD.toInt()
            val huecos = huecosDe[id]
            val destino = if (huecos != null) Malla3D.Constructor() else salida
            for (r in cuerpo(forma)) {
                val rep = args(r) ?: continue
                for (item in lista(rep.getOrNull(3))) {
                    geometria(ref(item), colocacion, colorBase, destino, 0)
                }
            }
            if (huecos != null) restarHuecos(destino, huecos, colorBase, salida)
            val nombre = texto(a.getOrNull(2)).ifBlank { NOMBRE_POR_TIPO[tipo] ?: tipo.removePrefix("IFC").lowercase() }
            salida.cerrarPieza(nombre, tipo)
        }

        // ---- Colocación ----

        private val matrices = HashMap<Int, DoubleArray>()

        private fun matrizDeColocacion(id: Int, profundidad: Int): DoubleArray {
            if (id < 0 || profundidad > 40) return identidad()
            matrices[id]?.let { return it }
            val a = args(id) ?: return identidad()
            val m = when (tipoDe(id)) {
                "IFCLOCALPLACEMENT" -> {
                    val propia = eje3D(ref(a.getOrNull(1)))
                    val padre = ref(a.getOrNull(0))
                    if (padre >= 0) multiplicar(matrizDeColocacion(padre, profundidad + 1), propia) else propia
                }
                else -> identidad()
            }
            matrices[id] = m
            return m
        }

        /** IfcAxis2Placement3D (o 2D) como matriz 4×4 por columnas… en filas: m[fila*4+col]. */
        fun eje3D(id: Int): DoubleArray {
            val a = args(id) ?: return identidad()
            val t = tipoDe(id)
            val o = punto(ref(a.getOrNull(0)))
            if (t == "IFCAXIS2PLACEMENT2D") {
                val x = direccion(ref(a.getOrNull(1))) ?: doubleArrayOf(1.0, 0.0, 0.0)
                return base(o, doubleArrayOf(0.0, 0.0, 1.0), x)
            }
            val z = direccion(ref(a.getOrNull(1))) ?: doubleArrayOf(0.0, 0.0, 1.0)
            val x = direccion(ref(a.getOrNull(2))) ?: if (abs(z[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0).let { cruz(it, z) } else doubleArrayOf(1.0, 0.0, 0.0)
            return base(o, z, x)
        }

        private fun base(o: DoubleArray, zIn: DoubleArray, xIn: DoubleArray): DoubleArray {
            val z = normal(zIn)
            // x perpendicular a z.
            val d = xIn[0] * z[0] + xIn[1] * z[1] + xIn[2] * z[2]
            var x = normal(doubleArrayOf(xIn[0] - d * z[0], xIn[1] - d * z[1], xIn[2] - d * z[2]))
            if (x.all { it == 0.0 }) x = if (abs(z[0]) < 0.9) normal(cruz(doubleArrayOf(1.0, 0.0, 0.0), z).let { cruz(z, it) }) else doubleArrayOf(0.0, 1.0, 0.0)
            val y = cruz(z, x)
            return doubleArrayOf(
                x[0], y[0], z[0], o[0],
                x[1], y[1], z[1], o[1],
                x[2], y[2], z[2], o[2],
                0.0, 0.0, 0.0, 1.0
            )
        }

        fun punto(id: Int): DoubleArray {
            val a = args(id) ?: return DoubleArray(3)
            val c = lista(a.getOrNull(0)).takeIf { tipoDe(id) != "IFCCARTESIANPOINT" || it.isNotEmpty() }
            val l = c ?: emptyList()
            return doubleArrayOf(num(l.getOrNull(0)) * metros, num(l.getOrNull(1)) * metros, num(l.getOrNull(2)) * metros)
        }

        fun direccion(id: Int): DoubleArray? {
            if (id < 0) return null
            val l = lista(args(id)?.getOrNull(0))
            if (l.isEmpty()) return null
            return normal(doubleArrayOf(num(l.getOrNull(0)), num(l.getOrNull(1)), num(l.getOrNull(2))))
        }

        // ---- Geometría ----

        /** Lo ya triangulado de un item, en sus coordenadas: para los `IfcMappedItem` que se repiten. */
        private val trianguladoDe = HashMap<Int, Triangulado>()

        private inner class Triangulado(val puntos: DoubleArray, val indices: IntArray, val color: Int?)

        private fun geometria(id: Int, m: DoubleArray, colorBase: Int, salida: Malla3D.Constructor, profundidad: Int) {
            if (id < 0 || profundidad > 12) return
            val a = args(id) ?: return
            val color = colorDeItem[id] ?: colorBase
            when (tipoDe(id)) {
                "IFCMAPPEDITEM" -> {
                    val mapa = args(ref(a.getOrNull(0))) ?: return
                    val origen = eje3D(ref(mapa.getOrNull(0)))
                    val operador = operador(ref(a.getOrNull(1)))
                    val total = multiplicar(multiplicar(m, operador), origen)
                    val rep = args(ref(mapa.getOrNull(1))) ?: return
                    for (item in lista(rep.getOrNull(3))) geometria(ref(item), total, color, salida, profundidad + 1)
                }
                "IFCBOOLEANCLIPPINGRESULT", "IFCBOOLEANRESULT" -> {
                    val op = (a.getOrNull(0) as? Enumerado)?.nombre
                    if (op == "UNION") {
                        geometria(ref(a.getOrNull(1)), m, color, salida, profundidad + 1)
                        geometria(ref(a.getOrNull(2)), m, color, salida, profundidad + 1)
                        return
                    }
                    val primero = Malla3D.Constructor()
                    geometria(ref(a.getOrNull(1)), m, color, primero, profundidad + 1)
                    val ma = primero.malla()
                    if (ma.cuantosTriangulos == 0) return
                    val solidoA = Csg.solido(ma)
                    val resultado = if (ma.cuantosTriangulos > TOPE_PARA_RESTAR) null else runCatching {
                        val solidoB = operando(ref(a.getOrNull(2)), m, Csg.caja(solidoA), color, profundidad + 1)
                        when {
                            solidoB == null -> solidoA
                            op == "INTERSECTION" -> Csg.intersecar(solidoA, solidoB)
                            else -> Csg.restar(solidoA, solidoB, ma.colores.firstOrNull() ?: color)
                        }
                    }.getOrNull()
                    Csg.volcar(resultado ?: solidoA, salida)
                }
                else -> {
                    val t = trianguladoDe.getOrPut(id) { triangular(id, a) ?: Triangulado(DoubleArray(0), IntArray(0), null) }
                    volcar(t, m, colorDeItem[id] ?: color, salida)
                }
            }
        }

        /**
         * El segundo operando de un booleano, como sólido. Los semiespacios no tienen fin: se
         * hacen una caja que llega bastante más allá de [caja], lo que ocupa el primero.
         */
        private fun operando(id: Int, m: DoubleArray, caja: DoubleArray, color: Int, profundidad: Int): List<Csg.Poligono>? {
            val a = args(id) ?: return null
            return when (tipoDe(id)) {
                "IFCHALFSPACESOLID", "IFCBOXEDHALFSPACE" -> semiespacio(a, m, caja, color)
                "IFCPOLYGONALBOUNDEDHALFSPACE" -> {
                    val medio = semiespacio(a, m, caja, color) ?: return null
                    val pos = multiplicar(m, eje3D(ref(a.getOrNull(2))))
                    val contorno = curva(ref(a.getOrNull(3))) ?: return medio
                    val (z0, z1) = rangoLocal(pos, caja, 2)
                    Csg.intersecar(medio, Csg.prisma(pos, contorno, z0 - 1, z1 + 1, color))
                }
                else -> {
                    val suyo = Malla3D.Constructor()
                    geometria(id, m, color, suyo, profundidad)
                    val mb = suyo.malla()
                    if (mb.cuantosTriangulos == 0 || mb.cuantosTriangulos > TOPE_PARA_RESTAR) null else Csg.solido(mb)
                }
            }
        }

        /**
         * `IfcHalfSpaceSolid`: lo que queda a un lado de un plano. Con `AgreementFlag` a verdadero
         * la normal del plano **se aleja** del material: el sólido está por debajo.
         */
        private fun semiespacio(a: List<Any?>, m: DoubleArray, caja: DoubleArray, color: Int): List<Csg.Poligono>? {
            val superficie = ref(a.getOrNull(0))
            if (tipoDe(superficie) != "IFCPLANE") return null
            val plano = multiplicar(m, eje3D(ref(args(superficie)?.getOrNull(0))))
            val debajo = (a.getOrNull(1) as? Enumerado)?.nombre != "F"
            val (x0, x1) = rangoLocal(plano, caja, 0)
            val (y0, y1) = rangoLocal(plano, caja, 1)
            val (z0, z1) = rangoLocal(plano, caja, 2)
            val margen = 1.0 + maxOf(x1 - x0, y1 - y0, z1 - z0)
            return if (debajo) Csg.caja(plano, x0 - margen, y0 - margen, minOf(z0, 0.0) - margen, x1 + margen, y1 + margen, 0.0, color)
            else Csg.caja(plano, x0 - margen, y0 - margen, 0.0, x1 + margen, y1 + margen, maxOf(z1, 0.0) + margen, color)
        }

        /** Entre qué valores cae la coordenada [eje] de las esquinas de [caja] vistas desde [m] (rígida). */
        private fun rangoLocal(m: DoubleArray, caja: DoubleArray, eje: Int): Pair<Double, Double> {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (i in 0 until 8) {
                val x = (if (i and 1 != 0) caja[3] else caja[0]) - m[3]
                val y = (if (i and 2 != 0) caja[4] else caja[1]) - m[7]
                val z = (if (i and 4 != 0) caja[5] else caja[2]) - m[11]
                // La inversa de un giro es su traspuesta: la columna del eje por el punto.
                val d = m[eje] * x + m[4 + eje] * y + m[8 + eje] * z
                val l2 = m[eje] * m[eje] + m[4 + eje] * m[4 + eje] + m[8 + eje] * m[8 + eje]
                val v = if (l2 > 1e-18) d / l2 else d
                if (v < lo) lo = v; if (v > hi) hi = v
            }
            return lo to hi
        }

        private fun volcar(t: Triangulado, m: DoubleArray, color: Int, salida: Malla3D.Constructor) {
            if (t.indices.isEmpty()) return
            val base = salida.cuantosVertices
            val p = t.puntos
            var i = 0
            while (i < p.size) {
                val x = p[i]; val y = p[i + 1]; val z = p[i + 2]
                salida.vertice(
                    m[0] * x + m[1] * y + m[2] * z + m[3],
                    m[4] * x + m[5] * y + m[6] * z + m[7],
                    m[8] * x + m[9] * y + m[10] * z + m[11]
                )
                i += 3
            }
            // Una matriz que refleja (escala negativa) da la vuelta a las caras.
            val det = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[1] * (m[4] * m[10] - m[6] * m[8]) + m[2] * (m[4] * m[9] - m[5] * m[8])
            val ix = t.indices
            var k = 0
            while (k < ix.size) {
                if (det >= 0) salida.triangulo(base + ix[k], base + ix[k + 1], base + ix[k + 2], color)
                else salida.triangulo(base + ix[k], base + ix[k + 2], base + ix[k + 1], color)
                k += 3
            }
        }

        private fun operador(id: Int): DoubleArray {
            val a = args(id) ?: return identidad()
            val x = direccion(ref(a.getOrNull(0))) ?: doubleArrayOf(1.0, 0.0, 0.0)
            val y = direccion(ref(a.getOrNull(1)))
            val o = punto(ref(a.getOrNull(2)))
            val escala = (a.getOrNull(3) as? Double) ?: 1.0
            val esNoUniforme = tipoDe(id) == "IFCCARTESIANTRANSFORMATIONOPERATOR3DNONUNIFORM"
            val z = direccion(ref(a.getOrNull(4))) ?: y?.let { cruz(x, it) } ?: doubleArrayOf(0.0, 0.0, 1.0)
            val yy = y ?: cruz(z, x)
            val s2 = if (esNoUniforme) (a.getOrNull(5) as? Double) ?: escala else escala
            val s3 = if (esNoUniforme) (a.getOrNull(6) as? Double) ?: escala else escala
            return doubleArrayOf(
                x[0] * escala, yy[0] * s2, z[0] * s3, o[0],
                x[1] * escala, yy[1] * s2, z[1] * s3, o[1],
                x[2] * escala, yy[2] * s2, z[2] * s3, o[2],
                0.0, 0.0, 0.0, 1.0
            )
        }

        private fun triangular(id: Int, a: List<Any?>): Triangulado? {
            val puntos = ArrayList<Double>()
            val indices = ArrayList<Int>()
            fun anadirPoligono(contorno: DoubleArray, agujeros: List<DoubleArray>) {
                val tri = Triangular.poligono(contorno, agujeros)
                if (tri.isEmpty()) return
                val base = puntos.size / 3
                contorno.forEach { puntos += it }
                agujeros.filter { it.size >= 9 }.forEach { h -> h.forEach { puntos += it } }
                tri.forEach { indices += base + it }
            }
            when (tipoDe(id)) {
                "IFCEXTRUDEDAREASOLID" -> {
                    val perfil = perfil(ref(a.getOrNull(0))) ?: return null
                    val pos = eje3D(ref(a.getOrNull(1)))
                    val dir = direccion(ref(a.getOrNull(2))) ?: doubleArrayOf(0.0, 0.0, 1.0)
                    val largo = num(a.getOrNull(3)) * metros
                    extruir(perfil, dir, largo, pos, puntos, indices)
                }
                "IFCFACETEDBREP", "IFCFACETEDBREPWITHVOIDS", "IFCADVANCEDBREP" -> {
                    caras(ref(a.getOrNull(0)), ::anadirPoligono)
                }
                "IFCSHELLBASEDSURFACEMODEL", "IFCFACEBASEDSURFACEMODEL" -> {
                    for (s in lista(a.getOrNull(0))) caras(ref(s), ::anadirPoligono)
                }
                "IFCCLOSEDSHELL", "IFCOPENSHELL", "IFCCONNECTEDFACESET" -> caras(id, ::anadirPoligono)
                "IFCTRIANGULATEDFACESET" -> {
                    val coords = lista(args(ref(a.getOrNull(0)))?.getOrNull(0))
                    coords.forEach { c -> lista(c).let { puntos += num(it.getOrNull(0)) * metros; puntos += num(it.getOrNull(1)) * metros; puntos += num(it.getOrNull(2)) * metros } }
                    val indicesDeCara = lista(a.getOrNull(3))
                    val pn = lista(a.getOrNull(4)).takeIf { it.isNotEmpty() }
                    for (t in indicesDeCara) {
                        val l = lista(t)
                        if (l.size < 3) continue
                        fun idx(v: Any?): Int { val k = num(v).toInt(); return (if (pn != null) num(pn.getOrNull(k - 1)).toInt() else k) - 1 }
                        indices += idx(l[0]); indices += idx(l[1]); indices += idx(l[2])
                    }
                }
                "IFCPOLYGONALFACESET" -> {
                    val coords = lista(args(ref(a.getOrNull(0)))?.getOrNull(0))
                    val todos = coords.map { c -> lista(c).let { doubleArrayOf(num(it.getOrNull(0)) * metros, num(it.getOrNull(1)) * metros, num(it.getOrNull(2)) * metros) } }
                    val pn = lista(a.getOrNull(3)).takeIf { it.isNotEmpty() }
                    fun pt(k: Int): DoubleArray? = todos.getOrNull((if (pn != null) num(pn.getOrNull(k - 1)).toInt() else k) - 1)
                    for (f in lista(a.getOrNull(2))) {
                        val fa = args(ref(f)) ?: continue
                        val contorno = lista(fa.getOrNull(0)).mapNotNull { pt(num(it).toInt()) }
                        val huecos = if (tipoDe(ref(f)) == "IFCINDEXEDPOLYGONALFACEWITHVOIDS")
                            lista(fa.getOrNull(1)).map { h -> lista(h).mapNotNull { pt(num(it).toInt()) }.plano() } else emptyList()
                        anadirPoligono(contorno.plano(), huecos)
                    }
                }
                else -> return null
            }
            if (indices.isEmpty()) return null
            return Triangulado(puntos.toDoubleArray(), indices.toIntArray(), null)
        }

        private fun List<DoubleArray>.plano(): DoubleArray {
            val r = DoubleArray(size * 3)
            forEachIndexed { i, p -> r[i * 3] = p[0]; r[i * 3 + 1] = p[1]; r[i * 3 + 2] = p[2] }
            return r
        }

        private fun caras(shell: Int, anadir: (DoubleArray, List<DoubleArray>) -> Unit) {
            val a = args(shell) ?: return
            for (f in lista(a.getOrNull(0))) {
                val cara = args(ref(f)) ?: continue
                var contorno: DoubleArray? = null
                val huecos = ArrayList<DoubleArray>()
                for (bnd in lista(cara.getOrNull(0))) {
                    val bid = ref(bnd)
                    val ba = args(bid) ?: continue
                    val loop = args(ref(ba.getOrNull(0))) ?: continue
                    val pts = lista(loop.getOrNull(0)).map { punto(ref(it)) }
                    if (pts.size < 3) continue
                    val invertido = (ba.getOrNull(1) as? Enumerado)?.nombre == "F"
                    val anillo = (if (invertido) pts.reversed() else pts).plano()
                    if (tipoDe(bid) == "IFCFACEOUTERBOUND" && contorno == null) contorno = anillo
                    else if (contorno == null && huecos.isEmpty() && tipoDe(bid) != "IFCFACEOUTERBOUND") contorno = anillo
                    else huecos += anillo
                }
                contorno?.let { anadir(it, huecos) }
            }
        }

        // ---- Perfiles ----

        /** Un perfil: el contorno y sus huecos, en 2D (x, y seguidos), ya en metros. */
        private inner class Perfil(val contorno: DoubleArray, val huecos: List<DoubleArray>)

        private fun perfil(id: Int): Perfil? {
            val a = args(id) ?: return null
            val t = tipoDe(id)
            fun colocado(p: DoubleArray, pos: Int): DoubleArray {
                if (pos < 0) return p
                val m = eje3D(pos)
                val r = DoubleArray(p.size)
                var i = 0
                while (i < p.size) {
                    r[i] = m[0] * p[i] + m[1] * p[i + 1] + m[3]
                    r[i + 1] = m[4] * p[i] + m[5] * p[i + 1] + m[7]
                    i += 2
                }
                return r
            }
            return when (t) {
                "IFCRECTANGLEPROFILEDEF", "IFCROUNDEDRECTANGLEPROFILEDEF" -> {
                    val x = num(a.getOrNull(3)) * metros / 2; val y = num(a.getOrNull(4)) * metros / 2
                    Perfil(colocado(doubleArrayOf(-x, -y, x, -y, x, y, -x, y), ref(a.getOrNull(2))), emptyList())
                }
                "IFCRECTANGLEHOLLOWPROFILEDEF" -> {
                    val x = num(a.getOrNull(3)) * metros / 2; val y = num(a.getOrNull(4)) * metros / 2
                    val e = num(a.getOrNull(5)) * metros
                    val pos = ref(a.getOrNull(2))
                    Perfil(
                        colocado(doubleArrayOf(-x, -y, x, -y, x, y, -x, y), pos),
                        listOf(colocado(doubleArrayOf(-x + e, -y + e, -x + e, y - e, x - e, y - e, x - e, -y + e), pos))
                    )
                }
                "IFCCIRCLEPROFILEDEF", "IFCCIRCLEHOLLOWPROFILEDEF" -> {
                    val r = num(a.getOrNull(3)) * metros
                    val pos = ref(a.getOrNull(2))
                    val huecos = if (t == "IFCCIRCLEHOLLOWPROFILEDEF") {
                        val e = num(a.getOrNull(4)) * metros
                        listOf(colocado(circulo(r - e, true), pos))
                    } else emptyList()
                    Perfil(colocado(circulo(r, false), pos), huecos)
                }
                "IFCELLIPSEPROFILEDEF" -> {
                    val rx = num(a.getOrNull(3)) * metros; val ry = num(a.getOrNull(4)) * metros
                    val c = circulo(1.0, false)
                    for (i in c.indices step 2) { c[i] *= rx; c[i + 1] *= ry }
                    Perfil(colocado(c, ref(a.getOrNull(2))), emptyList())
                }
                "IFCISHAPEPROFILEDEF" -> {
                    val w = num(a.getOrNull(3)) * metros / 2; val h = num(a.getOrNull(4)) * metros / 2
                    val tw = num(a.getOrNull(5)) * metros / 2; val tf = num(a.getOrNull(6)) * metros
                    Perfil(colocado(doubleArrayOf(
                        -w, -h, w, -h, w, -h + tf, tw, -h + tf, tw, h - tf, w, h - tf, w, h, -w, h, -w, h - tf, -tw, h - tf, -tw, -h + tf, -w, -h + tf
                    ), ref(a.getOrNull(2))), emptyList())
                }
                "IFCLSHAPEPROFILEDEF", "IFCTSHAPEPROFILEDEF", "IFCUSHAPEPROFILEDEF", "IFCCSHAPEPROFILEDEF", "IFCZSHAPEPROFILEDEF" -> {
                    // Aproximados por su caja: se ve el volumen aunque no el alma.
                    val x = num(a.getOrNull(3)) * metros / 2
                    val y = num(a.getOrNull(if (t == "IFCUSHAPEPROFILEDEF" || t == "IFCCSHAPEPROFILEDEF" || t == "IFCZSHAPEPROFILEDEF") 4 else 4)) * metros / 2
                    Perfil(colocado(doubleArrayOf(-x, -y, x, -y, x, y, -x, y), ref(a.getOrNull(2))), emptyList())
                }
                "IFCARBITRARYCLOSEDPROFILEDEF" -> curva(ref(a.getOrNull(2)))?.let { Perfil(it, emptyList()) }
                "IFCARBITRARYPROFILEDEFWITHVOIDS" -> curva(ref(a.getOrNull(2)))?.let { c ->
                    Perfil(c, lista(a.getOrNull(3)).mapNotNull { curva(ref(it)) })
                }
                "IFCDERIVEDPROFILEDEF" -> perfil(ref(a.getOrNull(2)))?.let { p ->
                    val m = operador2D(ref(a.getOrNull(3)))
                    fun mover(q: DoubleArray): DoubleArray { val r = DoubleArray(q.size); for (i in q.indices step 2) { r[i] = m[0] * q[i] + m[1] * q[i + 1] + m[2]; r[i + 1] = m[3] * q[i] + m[4] * q[i + 1] + m[5] }; return r }
                    Perfil(mover(p.contorno), p.huecos.map(::mover))
                }
                "IFCCOMPOSITEPROFILEDEF" -> lista(a.getOrNull(2)).firstNotNullOfOrNull { perfil(ref(it)) }
                else -> null
            }
        }

        private fun operador2D(id: Int): DoubleArray {
            val a = args(id) ?: return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            val x = direccion(ref(a.getOrNull(0))) ?: doubleArrayOf(1.0, 0.0, 0.0)
            val y = direccion(ref(a.getOrNull(1))) ?: doubleArrayOf(-x[1], x[0], 0.0)
            val o = punto(ref(a.getOrNull(2)))
            val s = (a.getOrNull(3) as? Double) ?: 1.0
            return doubleArrayOf(x[0] * s, y[0] * s, o[0], x[1] * s, y[1] * s, o[1])
        }

        private fun circulo(r: Double, horario: Boolean): DoubleArray {
            val lados = 24
            val p = DoubleArray(lados * 2)
            for (i in 0 until lados) {
                val t = 2 * PI * i / lados * (if (horario) -1 else 1)
                p[i * 2] = cos(t) * r; p[i * 2 + 1] = sin(t) * r
            }
            return p
        }

        /** Una curva cerrada 2D como polígono (x, y seguidos). */
        private fun curva(id: Int, profundidad: Int = 0): DoubleArray? {
            if (id < 0 || profundidad > 10) return null
            val a = args(id) ?: return null
            val pts = ArrayList<Double>()
            fun anadir(x: Double, y: Double) {
                val n = pts.size
                if (n >= 2 && abs(pts[n - 2] - x) < 1e-9 && abs(pts[n - 1] - y) < 1e-9) return
                pts += x; pts += y
            }
            when (tipoDe(id)) {
                "IFCPOLYLINE" -> lista(a.getOrNull(0)).forEach { val p = punto(ref(it)); anadir(p[0], p[1]) }
                "IFCINDEXEDPOLYCURVE" -> {
                    val lista2 = lista(args(ref(a.getOrNull(0)))?.getOrNull(0)).map { c -> lista(c).let { doubleArrayOf(num(it.getOrNull(0)) * metros, num(it.getOrNull(1)) * metros) } }
                    val segmentos = lista(a.getOrNull(1))
                    if (segmentos.isEmpty()) lista2.forEach { anadir(it[0], it[1]) }
                    else for (s in segmentos) {
                        val t = s as? Tipado ?: continue
                        val ix = lista(t.valor).map { num(it).toInt() - 1 }
                        if (t.tipo == "IFCARCINDEX" && ix.size == 3) {
                            arcoPorTres(lista2.getOrNull(ix[0]), lista2.getOrNull(ix[1]), lista2.getOrNull(ix[2]))?.let { arco ->
                                for (i in arco.indices step 2) anadir(arco[i], arco[i + 1])
                            }
                        } else ix.forEach { k -> lista2.getOrNull(k)?.let { anadir(it[0], it[1]) } }
                    }
                }
                "IFCCOMPOSITECURVE" -> {
                    for (s in lista(a.getOrNull(0))) {
                        val sa = args(ref(s)) ?: continue
                        val mismoSentido = (sa.getOrNull(1) as? Enumerado)?.nombre != "F"
                        val trozo = trozoDeCurva(ref(sa.getOrNull(2)), profundidad + 1) ?: continue
                        val orden = if (mismoSentido) trozo else invertir2D(trozo)
                        for (i in orden.indices step 2) anadir(orden[i], orden[i + 1])
                    }
                }
                "IFCTRIMMEDCURVE", "IFCCIRCLE", "IFCELLIPSE" -> trozoDeCurva(id, profundidad + 1)?.let { t -> for (i in t.indices step 2) anadir(t[i], t[i + 1]) }
                else -> return null
            }
            if (pts.size >= 4 && abs(pts[0] - pts[pts.size - 2]) < 1e-9 && abs(pts[1] - pts[pts.size - 1]) < 1e-9) {
                pts.removeAt(pts.size - 1); pts.removeAt(pts.size - 1)
            }
            return if (pts.size >= 6) pts.toDoubleArray() else null
        }

        private fun invertir2D(p: DoubleArray): DoubleArray {
            val r = DoubleArray(p.size)
            val n = p.size / 2
            for (i in 0 until n) { r[i * 2] = p[(n - 1 - i) * 2]; r[i * 2 + 1] = p[(n - 1 - i) * 2 + 1] }
            return r
        }

        private fun trozoDeCurva(id: Int, profundidad: Int): DoubleArray? {
            val a = args(id) ?: return null
            return when (tipoDe(id)) {
                "IFCTRIMMEDCURVE" -> {
                    val baseId = ref(a.getOrNull(0))
                    val bt = tipoDe(baseId)
                    val sentido = (a.getOrNull(3) as? Enumerado)?.nombre != "F"
                    if (bt == "IFCCIRCLE" || bt == "IFCELLIPSE") {
                        val ba = args(baseId) ?: return null
                        val m = eje3D(ref(ba.getOrNull(0)))
                        val rx = num(ba.getOrNull(1)) * metros
                        val ry = if (bt == "IFCELLIPSE") num(ba.getOrNull(2)) * metros else rx
                        fun parametro(recorte: List<Any?>): Double? {
                            recorte.forEach { v -> if (v is Tipado && v.tipo == "IFCPARAMETERVALUE") return num(v.valor) * radianes }
                            recorte.forEach { v ->
                                if (v is Ref) {
                                    val p = punto(v.id)
                                    // Del punto al ángulo en el sistema del círculo.
                                    val dx = p[0] - m[3]; val dy = p[1] - m[7]
                                    val lx = m[0] * dx + m[4] * dy; val ly = m[1] * dx + m[5] * dy
                                    return kotlin.math.atan2(ly / ry, lx / rx)
                                }
                            }
                            return null
                        }
                        val t1 = parametro(lista(a.getOrNull(1))) ?: return null
                        val t2 = parametro(lista(a.getOrNull(2))) ?: return null
                        var barrido = if (sentido) t2 - t1 else t1 - t2
                        while (barrido <= 0) barrido += 2 * PI
                        val pasos = max(2, (barrido / (2 * PI) * 32).toInt())
                        val p = DoubleArray((pasos + 1) * 2)
                        for (i in 0..pasos) {
                            val t = if (sentido) t1 + barrido * i / pasos else t1 - barrido * i / pasos
                            val lx = cos(t) * rx; val ly = sin(t) * ry
                            p[i * 2] = m[0] * lx + m[1] * ly + m[3]
                            p[i * 2 + 1] = m[4] * lx + m[5] * ly + m[7]
                        }
                        p
                    } else {
                        // Recta o polilínea recortada por dos puntos.
                        val p1 = lista(a.getOrNull(1)).firstOrNull { it is Ref }?.let { punto(ref(it)) }
                        val p2 = lista(a.getOrNull(2)).firstOrNull { it is Ref }?.let { punto(ref(it)) }
                        if (p1 != null && p2 != null) {
                            if (sentido) doubleArrayOf(p1[0], p1[1], p2[0], p2[1]) else doubleArrayOf(p2[0], p2[1], p1[0], p1[1])
                        } else curva(baseId, profundidad + 1)
                    }
                }
                "IFCCIRCLE", "IFCELLIPSE" -> {
                    val m = eje3D(ref(a.getOrNull(0)))
                    val rx = num(a.getOrNull(1)) * metros
                    val ry = if (tipoDe(id) == "IFCELLIPSE") num(a.getOrNull(2)) * metros else rx
                    val c = circulo(1.0, false)
                    for (i in c.indices step 2) { val lx = c[i] * rx; val ly = c[i + 1] * ry; c[i] = m[0] * lx + m[1] * ly + m[3]; c[i + 1] = m[4] * lx + m[5] * ly + m[7] }
                    c
                }
                else -> curva(id, profundidad + 1)
            }
        }

        private fun arcoPorTres(a: DoubleArray?, b: DoubleArray?, c: DoubleArray?): DoubleArray? {
            a ?: return null; b ?: return null; c ?: return null
            val d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]))
            if (abs(d) < 1e-12) return doubleArrayOf(a[0], a[1], b[0], b[1], c[0], c[1])
            val ux = ((a[0] * a[0] + a[1] * a[1]) * (b[1] - c[1]) + (b[0] * b[0] + b[1] * b[1]) * (c[1] - a[1]) + (c[0] * c[0] + c[1] * c[1]) * (a[1] - b[1])) / d
            val uy = ((a[0] * a[0] + a[1] * a[1]) * (c[0] - b[0]) + (b[0] * b[0] + b[1] * b[1]) * (a[0] - c[0]) + (c[0] * c[0] + c[1] * c[1]) * (b[0] - a[0])) / d
            val r = sqrt((a[0] - ux) * (a[0] - ux) + (a[1] - uy) * (a[1] - uy))
            val t1 = kotlin.math.atan2(a[1] - uy, a[0] - ux)
            val t2 = kotlin.math.atan2(b[1] - uy, b[0] - ux)
            val t3 = kotlin.math.atan2(c[1] - uy, c[0] - ux)
            fun ccw(desde: Double, hasta: Double): Double { var x = hasta - desde; while (x < 0) x += 2 * PI; return x }
            // Sentido: el que pasa por b.
            val sentidoPositivo = ccw(t1, t2) < ccw(t1, t3)
            val barrido = if (sentidoPositivo) ccw(t1, t3) else -ccw(t3, t1)
            val pasos = max(2, (abs(barrido) / (2 * PI) * 32).toInt())
            val p = DoubleArray((pasos + 1) * 2)
            for (i in 0..pasos) { val t = t1 + barrido * i / pasos; p[i * 2] = ux + cos(t) * r; p[i * 2 + 1] = uy + sin(t) * r }
            return p
        }

        // ---- Extrusión ----

        private fun extruir(perfil: Perfil, dir: DoubleArray, largo: Double, pos: DoubleArray, puntos: MutableList<Double>, indices: MutableList<Int>) {
            val dx = dir[0] * largo; val dy = dir[1] * largo; val dz = dir[2] * largo
            val anillos = listOf(perfil.contorno) + perfil.huecos
            fun p3(x: Double, y: Double, z: Double) {
                puntos += pos[0] * x + pos[1] * y + pos[2] * z + pos[3]
                puntos += pos[4] * x + pos[5] * y + pos[6] * z + pos[7]
                puntos += pos[8] * x + pos[9] * y + pos[10] * z + pos[11]
            }
            // Tapas: abajo y arriba.
            val abajo = DoubleArray(anillos.sumOf { it.size / 2 } * 3)
            var k = 0
            for (r in anillos) for (i in r.indices step 2) { abajo[k++] = r[i]; abajo[k++] = r[i + 1]; abajo[k++] = 0.0 }
            val tri = Triangular.poligono(perfil.contorno.let { c -> DoubleArray(c.size / 2 * 3).also { o -> for (i in 0 until c.size / 2) { o[i * 3] = c[i * 2]; o[i * 3 + 1] = c[i * 2 + 1] } } },
                perfil.huecos.map { h -> DoubleArray(h.size / 2 * 3).also { o -> for (i in 0 until h.size / 2) { o[i * 3] = h[i * 2]; o[i * 3 + 1] = h[i * 2 + 1] } } })
            // La tapa de abajo mira contra la extrusión y la de arriba a favor.
            val subeZ = dz >= 0
            val base = puntos.size / 3
            for (i in abajo.indices step 3) p3(abajo[i], abajo[i + 1], 0.0)
            val arriba = puntos.size / 3
            for (i in abajo.indices step 3) p3(abajo[i] + dx, abajo[i + 1] + dy, dz)
            var t = 0
            while (t < tri.size) {
                // `tri` sigue la orientación del contorno; si el contorno es antihorario, su
                // cara mira a +z: esa es la de arriba.
                if (subeZ) {
                    indices += arriba + tri[t]; indices += arriba + tri[t + 1]; indices += arriba + tri[t + 2]
                    indices += base + tri[t]; indices += base + tri[t + 2]; indices += base + tri[t + 1]
                } else {
                    indices += arriba + tri[t]; indices += arriba + tri[t + 2]; indices += arriba + tri[t + 1]
                    indices += base + tri[t]; indices += base + tri[t + 1]; indices += base + tri[t + 2]
                }
                t += 3
            }
            // Paredes.
            var inicio = 0
            for (r in anillos) {
                val m = r.size / 2
                for (i in 0 until m) {
                    val j = (i + 1) % m
                    val a0 = base + inicio + i; val a1 = base + inicio + j
                    val b0 = arriba + inicio + i; val b1 = arriba + inicio + j
                    indices += a0; indices += a1; indices += b1
                    indices += a0; indices += b1; indices += b0
                }
                inicio += m
            }
        }
    }

    // ---- Álgebra ----

    private fun identidad() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    private fun multiplicar(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (f in 0 until 4) for (c in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += a[f * 4 + k] * b[k * 4 + c]
            r[f * 4 + c] = s
        }
        return r
    }

    private fun cruz(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun normal(v: DoubleArray): DoubleArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (l < 1e-12) doubleArrayOf(0.0, 0.0, 0.0) else doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    /** Un mapa de enteros sin cajas: millones de entidades en un `HashMap<Int,Int>` son cientos de megas. */
    internal class IntIntMapa(capacidad: Int) {
        private var tam = Integer.highestOneBit(maxOf(16, capacidad) * 2)
        private var claves = IntArray(tam) { -1 }
        private var valores = IntArray(tam)
        fun poner(k: Int, v: Int) {
            var i = (k * -0x61c88647) ushr 1 and (tam - 1)
            while (claves[i] != -1 && claves[i] != k) i = (i + 1) and (tam - 1)
            claves[i] = k; valores[i] = v
        }
        fun dar(k: Int): Int {
            var i = (k * -0x61c88647) ushr 1 and (tam - 1)
            while (true) {
                val c = claves[i]
                if (c == -1) return -1
                if (c == k) return valores[i]
                i = (i + 1) and (tam - 1)
            }
        }
    }

    private val PRODUCTOS = setOf(
        "IFCWALL", "IFCWALLSTANDARDCASE", "IFCWALLELEMENTEDCASE", "IFCSLAB", "IFCSLABSTANDARDCASE", "IFCSLABELEMENTEDCASE",
        "IFCROOF", "IFCBEAM", "IFCBEAMSTANDARDCASE", "IFCCOLUMN", "IFCCOLUMNSTANDARDCASE", "IFCDOOR", "IFCDOORSTANDARDCASE",
        "IFCWINDOW", "IFCWINDOWSTANDARDCASE", "IFCSTAIR", "IFCSTAIRFLIGHT", "IFCRAMP", "IFCRAMPFLIGHT", "IFCRAILING",
        "IFCCURTAINWALL", "IFCPLATE", "IFCPLATESTANDARDCASE", "IFCMEMBER", "IFCMEMBERSTANDARDCASE", "IFCCOVERING",
        "IFCFOOTING", "IFCPILE", "IFCBUILDINGELEMENTPROXY", "IFCFURNISHINGELEMENT", "IFCFURNITURE", "IFCSYSTEMFURNITUREELEMENT",
        "IFCFLOWTERMINAL", "IFCFLOWSEGMENT", "IFCFLOWFITTING", "IFCDISTRIBUTIONELEMENT", "IFCSANITARYTERMINAL",
        "IFCLIGHTFIXTURE", "IFCDUCTSEGMENT", "IFCPIPESEGMENT", "IFCCHIMNEY", "IFCSHADINGDEVICE", "IFCTRANSPORTELEMENT",
        "IFCGEOGRAPHICELEMENT", "IFCCIVILELEMENT", "IFCELEMENTASSEMBLY", "IFCREINFORCINGBAR", "IFCDISCRETEACCESSORY",
        "IFCMECHANICALFASTENER", "IFCFLOWCONTROLLER", "IFCENERGYCONVERSIONDEVICE", "IFCFLOWMOVINGDEVICE",
        "IFCFLOWSTORAGEDEVICE", "IFCFLOWTREATMENTDEVICE", "IFCDISTRIBUTIONCONTROLELEMENT", "IFCELECTRICAPPLIANCE",
        "IFCAIRTERMINAL", "IFCDUCTFITTING", "IFCPIPEFITTING", "IFCVIRTUALELEMENT", "IFCSITE"
    )

    private val IGNORAR_REP = setOf("Axis", "Box", "FootPrint", "Annotation", "Profile", "Plan", "Clearance", "Reference")

    private val COLOR_POR_TIPO = mapOf(
        "IFCWALL" to 0xFFE8E4DC.toInt(), "IFCWALLSTANDARDCASE" to 0xFFE8E4DC.toInt(),
        "IFCSLAB" to 0xFFB8B6B0.toInt(), "IFCSLABSTANDARDCASE" to 0xFFB8B6B0.toInt(),
        "IFCROOF" to 0xFF9C5B45.toInt(), "IFCBEAM" to 0xFF8A8F99.toInt(), "IFCCOLUMN" to 0xFF9AA0A8.toInt(),
        "IFCDOOR" to 0xFF9C6B3F.toInt(), "IFCWINDOW" to 0x994FA3D9.toInt(), "IFCCURTAINWALL" to 0x994FA3D9.toInt(),
        "IFCPLATE" to 0x994FA3D9.toInt(), "IFCSTAIR" to 0xFFA8A29A.toInt(), "IFCSTAIRFLIGHT" to 0xFFA8A29A.toInt(),
        "IFCRAILING" to 0xFF5E6470.toInt(), "IFCFURNISHINGELEMENT" to 0xFFC49A6C.toInt(), "IFCFURNITURE" to 0xFFC49A6C.toInt(),
        "IFCSITE" to 0xFF8DAA6B.toInt(), "IFCCOVERING" to 0xFFD9D2C5.toInt(), "IFCMEMBER" to 0xFF6F7680.toInt()
    )

    private val NOMBRE_POR_TIPO = mapOf(
        "IFCWALL" to "Muro", "IFCWALLSTANDARDCASE" to "Muro", "IFCSLAB" to "Losa", "IFCROOF" to "Cubierta",
        "IFCBEAM" to "Viga", "IFCCOLUMN" to "Columna", "IFCDOOR" to "Puerta", "IFCWINDOW" to "Ventana",
        "IFCSTAIR" to "Escalera", "IFCRAILING" to "Barandilla", "IFCFURNISHINGELEMENT" to "Mobiliario"
    )
}
