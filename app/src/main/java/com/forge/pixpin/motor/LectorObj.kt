package com.forge.pixpin.motor

import java.io.File

/**
 * **Un OBJ como malla** (14-sep-2026): el formato de modelos 3D que abre cualquier programa, y el
 * que sale de Revit con los complementos de exportación, de SketchUp, Blender o Rhino.
 *
 * OBJ va con la `y` hacia arriba y el croquis con la `z`: un punto `(x, y, z)` del archivo
 * cae en `(x, −z, y)`, el giro inverso del de `ExportarObj` del croquis. Se
 * suponen metros, que es lo que exportan casi todos; si sale enorme o diminuto, se escala con
 * el mando. Colores: los `Kd` de su `.mtl` si está al lado y, si no, uno por material.
 */
object LectorObj {

    fun esObj(nombre: String) = nombre.substringAfterLast('.', "").lowercase() == "obj"

    fun leer(archivo: File, mtl: File? = null): Malla3D {
        val colores = mtl?.takeIf { it.exists() }?.let { leerMtl(it) } ?: emptyMap()
        val salida = Malla3D.Constructor()
        val v = ArrayList<Double>(1 shl 16)
        var color = 0xFFC8C8C8.toInt()
        var pieza = "Modelo"
        val paleta = intArrayOf(0xFFD9D4CB.toInt(), 0xFFA8B3BF.toInt(), 0xFFBFA58A.toInt(), 0xFF9FB59A.toInt(), 0xFFC9B8D6.toInt(), 0xFFD6C38F.toInt())
        val materiales = HashMap<String, Int>()
        val contorno = ArrayList<Int>()
        archivo.bufferedReader().useLines { lineas ->
            for (bruta in lineas) {
                val l = bruta.trim()
                if (l.isEmpty() || l[0] == '#') continue
                when {
                    l.startsWith("v ") -> {
                        val p = l.substring(2).trim().split(Regex("\\s+"))
                        val x = p.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                        val y = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                        val z = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                        v += x; v += -z; v += y
                    }
                    l.startsWith("f ") -> {
                        contorno.clear()
                        val nv = v.size / 3
                        for (tok in l.substring(2).trim().split(Regex("\\s+"))) {
                            val k = tok.substringBefore('/').toIntOrNull() ?: continue
                            val idx = if (k < 0) nv + k else k - 1
                            if (idx in 0 until nv) contorno += idx
                        }
                        if (contorno.size < 3) continue
                        val base = salida.cuantosVertices
                        val puntos = DoubleArray(contorno.size * 3)
                        contorno.forEachIndexed { i, idx ->
                            puntos[i * 3] = v[idx * 3]; puntos[i * 3 + 1] = v[idx * 3 + 1]; puntos[i * 3 + 2] = v[idx * 3 + 2]
                            salida.vertice(puntos[i * 3], puntos[i * 3 + 1], puntos[i * 3 + 2])
                        }
                        val tri = if (contorno.size == 3) intArrayOf(0, 1, 2) else Triangular.poligono(puntos)
                        var t = 0
                        while (t < tri.size) { salida.triangulo(base + tri[t], base + tri[t + 1], base + tri[t + 2], color); t += 3 }
                        if (salida.cuantosTriangulos > LectorIfc.TOPE_DE_TRIANGULOS) return@useLines
                    }
                    l.startsWith("usemtl ") -> {
                        val nombre = l.substring(7).trim()
                        color = colores[nombre] ?: materiales.getOrPut(nombre) { paleta[materiales.size % paleta.size] }
                    }
                    l.startsWith("o ") || l.startsWith("g ") -> {
                        salida.cerrarPieza(pieza, "OBJ")
                        pieza = l.substring(2).trim().ifBlank { "Pieza" }
                    }
                }
            }
        }
        salida.cerrarPieza(pieza, "OBJ")
        if (salida.cuantosTriangulos == 0) throw LectorIfc.NoSeLee("El OBJ no trae caras")
        return salida.malla()
    }

    /** El nombre del `.mtl` que pide el OBJ, si pide alguno. */
    fun mtlQuePide(archivo: File): String? = archivo.bufferedReader().useLines { l ->
        l.take(2000).firstOrNull { it.startsWith("mtllib ") }?.substring(7)?.trim()
    }

    private fun leerMtl(f: File): Map<String, Int> {
        val r = HashMap<String, Int>()
        var actual: String? = null
        var alfa = 1.0
        f.forEachLine { bruta ->
            val l = bruta.trim()
            when {
                l.startsWith("newmtl ") -> { actual = l.substring(7).trim(); alfa = 1.0 }
                l.startsWith("d ") -> alfa = l.substring(2).trim().toDoubleOrNull() ?: 1.0
                l.startsWith("Kd ") -> actual?.let { n ->
                    val p = l.substring(3).trim().split(Regex("\\s+")).map { it.toDoubleOrNull() ?: 0.8 }
                    fun c(x: Double) = (x.coerceIn(0.0, 1.0) * 255).toInt()
                    r[n] = (c(alfa) shl 24) or (c(p.getOrElse(0) { 0.8 }) shl 16) or (c(p.getOrElse(1) { 0.8 }) shl 8) or c(p.getOrElse(2) { 0.8 })
                }
            }
        }
        return r
    }
}
