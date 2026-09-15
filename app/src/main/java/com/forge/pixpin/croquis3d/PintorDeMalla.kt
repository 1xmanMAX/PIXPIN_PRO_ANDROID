package com.forge.pixpin.croquis3d

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.mutableIntStateOf
import com.forge.pixpin.motor.Malla3D
import com.forge.pixpin.motor.Pt3
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **Los modelos cargados**, por la ruta de su malla. Se cargan fuera del hilo de la pantalla
 * y, al llegar uno, [version] cambia: la escena la lee al pintar, así que se repinta sola.
 */
object AlmacenDeMallas {
    val cargadas = ConcurrentHashMap<String, PintorDeMalla>()
    private val pedidas = ConcurrentHashMap.newKeySet<String>()
    val version = mutableIntStateOf(0)

    /** Carga [ruta] si no lo está. Trabajo de disco: llamar fuera del hilo de la pantalla. */
    fun cargar(ruta: String) {
        if (cargadas.containsKey(ruta) || !pedidas.add(ruta)) return
        val malla = Malla3D.cargar(File(ruta))
        if (malla == null) { pedidas.remove(ruta); return }
        cargadas[ruta] = PintorDeMalla(malla)
        version.intValue++
    }

    /** Los triángulos de [ruta]: los ya cargados, o leídos del disco (fuera del hilo de la pantalla). */
    fun malla(ruta: String): Malla3D? = cargadas[ruta]?.malla ?: Malla3D.cargar(File(ruta))

    /** Suelta lo que ya no está en ningún croquis abierto. */
    fun soltarMenos(rutas: Set<String>) {
        cargadas.keys.filter { it !in rutas }.forEach { cargadas.remove(it); pedidas.remove(it) }
    }
}

/**
 * **Pintar un modelo de miles de triángulos con el `Canvas`, sin tirones.**
 *
 * El croquis no tiene OpenGL ni búfer de profundidad: todo se pinta de lejos a cerca. Para un
 * modelo eso es ordenar sus triángulos por hondura en cada fotograma, y con cien mil
 * triángulos es demasiado. Lo que lo hace posible:
 *
 * - **Con la cámara ortográfica todo es lineal.** La posición en pantalla y la hondura de un
 *   punto del modelo son una suma de sus tres coordenadas por tres números que dependen solo
 *   de la cámara y de dónde está puesto: se calculan una vez por fotograma y el bucle por
 *   vértice son seis multiplicaciones sin objetos.
 * - **El orden solo cambia si gira la vista.** Acercar o desplazar no cambia quién tapa a
 *   quién, así que el orden se reaprovecha mientras la dirección de la mirada no se mueva.
 *   Y cuando hay que rehacerlo es un reparto en cubos (lineal), no una ordenación.
 * - **Mientras se gira, los triángulos grandes.** Se pintan los [TOPE_MOVIENDO] de más
 *   superficie —muros, losas, cubierta: lo que da la forma— y, al parar, todos.
 * - **Un solo `drawVertices` por tanda**, con el color de cada vértice ya sombreado.
 */
class PintorDeMalla(val malla: Malla3D) {

    private val t = malla.cuantosTriangulos
    private val centros = FloatArray(t * 3)
    private val normales = FloatArray(t * 3)
    /** Los triángulos de mayor a menor superficie. */
    private val porTamano: IntArray

    init {
        val v = malla.vertices
        val ix = malla.triangulos
        val areas = FloatArray(t)
        for (k in 0 until t) {
            val a = ix[k * 3] * 3; val b = ix[k * 3 + 1] * 3; val c = ix[k * 3 + 2] * 3
            centros[k * 3] = (v[a] + v[b] + v[c]) / 3f
            centros[k * 3 + 1] = (v[a + 1] + v[b + 1] + v[c + 1]) / 3f
            centros[k * 3 + 2] = (v[a + 2] + v[b + 2] + v[c + 2]) / 3f
            val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
            val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            areas[k] = l
            if (l > 1e-12f) { normales[k * 3] = nx / l; normales[k * 3 + 1] = ny / l; normales[k * 3 + 2] = nz / l }
        }
        porTamano = if (t <= TOPE_MOVIENDO) IntArray(t) { it }
        else (0 until t).sortedByDescending { areas[it] }.take(TOPE_MOVIENDO).toIntArray()
    }

    // Lo del fotograma, reaprovechado.
    private var pantalla = FloatArray(0)
    private var trazo = FloatArray(0)
    private var tintas = IntArray(0)
    private var orden = IntArray(0)
    private var cubos = IntArray(0)
    private var claves = IntArray(0)
    private var sombreado = IntArray(0)

    private var ordenDe: Long = 0
    private var cuantosEnOrden = 0
    private var ultimaMirada = floatArrayOf(0f, 0f, 0f)
    private var sombreadoDe: Int = 0

    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)

    fun pintar(
        lienzo: Canvas,
        modelo: Modelo3D,
        camara: Camara3D,
        ancho: Double,
        alto: Double,
        luz: Pt3,
        elegido: Boolean,
        moviendo: Boolean
    ) {
        if (t == 0) return
        val base = camara.base(ancho, alto)
        val v = malla.vertices
        val nv = v.size / 3
        if (pantalla.size < nv * 2) pantalla = FloatArray(nv * 2)
        val cx = modelo.centro.x; val cy = modelo.centro.y; val cz = modelo.centro.z
        val o = modelo.origen; val ex = modelo.ejeX; val ey = modelo.ejeY; val ez = modelo.ejeZ

        // La pantalla y la hondura como funciones lineales del punto del modelo.
        val ox = o.x - base.cx; val oy = o.y - base.cy; val oz = o.z - base.cz
        fun punto(d0: Double, d1: Double, d2: Double) = doubleArrayOf(
            d0 * ex.x + d1 * ex.y + d2 * ex.z,
            d0 * ey.x + d1 * ey.y + d2 * ey.z,
            d0 * ez.x + d1 * ez.y + d2 * ez.z,
            d0 * ox + d1 * oy + d2 * oz
        )
        val u = punto(base.dx, base.dy, base.dz)
        val w = punto(base.ax, base.ay, base.az)
        val f = punto(base.fx, base.fy, base.fz)
        val lineal = base.campo <= 0.0

        if (lineal) {
            val z = base.zoom
            val ux = (u[0] * z).toFloat(); val uy = (u[1] * z).toFloat(); val uz = (u[2] * z).toFloat()
            val u0 = (base.medioAncho + (u[3] - u[0] * cx - u[1] * cy - u[2] * cz) * z).toFloat()
            val wx = (-w[0] * z).toFloat(); val wy = (-w[1] * z).toFloat(); val wz = (-w[2] * z).toFloat()
            val w0 = (base.medioAlto - (w[3] - w[0] * cx - w[1] * cy - w[2] * cz) * z).toFloat()
            var i = 0; var j = 0
            while (i < v.size) {
                val x = v[i]; val y = v[i + 1]; val q = v[i + 2]
                pantalla[j] = u0 + ux * x + uy * y + uz * q
                pantalla[j + 1] = w0 + wx * x + wy * y + wz * q
                i += 3; j += 2
            }
        } else {
            var i = 0; var j = 0
            while (i < v.size) {
                val p = base.aPantalla(modelo.alMundo(v[i].toDouble(), v[i + 1].toDouble(), v[i + 2].toDouble()))
                pantalla[j] = p.x.toFloat(); pantalla[j + 1] = p.y.toFloat()
                i += 3; j += 2
            }
        }

        // ---- El orden, de lejos a cerca ----
        val usados = if (moviendo) porTamano.size else t
        val fx = f[0].toFloat(); val fy = f[1].toFloat(); val fz = f[2].toFloat()
        val largoF = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-12f)
        val m0 = fx / largoF; val m1 = fy / largoF; val m2 = fz / largoF
        val llave = modelo.hashCode().toLong() * 31 + usados
        val mismaMirada = m0 * ultimaMirada[0] + m1 * ultimaMirada[1] + m2 * ultimaMirada[2] > 0.99995f
        if (!(mismaMirada && llave == ordenDe && cuantosEnOrden == usados)) {
            ordenar(usados, moviendo, fx, fy, fz)
            ordenDe = llave; cuantosEnOrden = usados
            ultimaMirada[0] = m0; ultimaMirada[1] = m1; ultimaMirada[2] = m2
        }

        // ---- El sombreado, fijo al mundo: solo cambia si se gira el modelo o la luz ----
        val lx = luz.x; val ly = luz.y; val lz = luz.z
        val firma = (modelo.ejeX.hashCode() * 31 + modelo.ejeY.hashCode()) * 31 + modelo.ejeZ.hashCode() + luz.hashCode() * 7 + (if (elegido) 1 else 0)
        if (sombreado.size != t || firma != sombreadoDe) {
            if (sombreado.size != t) sombreado = IntArray(t)
            val col = malla.colores
            for (k in 0 until t) {
                val nx = normales[k * 3].toDouble(); val ny = normales[k * 3 + 1].toDouble(); val nz = normales[k * 3 + 2].toDouble()
                // La normal al mundo (con escala uniforme, basta con los ejes).
                val mx = ex.x * nx + ey.x * ny + ez.x * nz
                val my = ex.y * nx + ey.y * ny + ez.y * nz
                val mz = ex.z * nx + ey.z * ny + ez.z * nz
                val l = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-12)
                val d = abs((mx * lx + my * ly + mz * lz) / l)
                val luzHacia = 0.58 + 0.42 * d
                val c = col[k]
                var r = ((c shr 16) and 0xFF) * luzHacia
                var g = ((c shr 8) and 0xFF) * luzHacia
                var b = (c and 0xFF) * luzHacia
                if (elegido) { r = r * 0.6 + 0x19 * 0.4; g = g * 0.6 + 0x71 * 0.4; b = b * 0.6 + 0xC2 * 0.4 }
                sombreado[k] = (c and -0x1000000) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
            }
            sombreadoDe = firma
        }

        // ---- A la pantalla, por tandas ----
        val ix = malla.triangulos
        val tanda = TANDA
        if (trazo.size < tanda * 6) { trazo = FloatArray(tanda * 6); tintas = IntArray(tanda * 3) }
        var n = 0
        val anchoF = ancho.toFloat(); val altoF = alto.toFloat()
        for (s in 0 until usados) {
            val k = orden[s]
            val a = ix[k * 3] * 2; val b = ix[k * 3 + 1] * 2; val c = ix[k * 3 + 2] * 2
            val ax = pantalla[a]; val ay = pantalla[a + 1]
            val bx = pantalla[b]; val by = pantalla[b + 1]
            val qx = pantalla[c]; val qy = pantalla[c + 1]
            // Fuera del cristal no se manda.
            if ((ax < 0 && bx < 0 && qx < 0) || (ay < 0 && by < 0 && qy < 0) ||
                (ax > anchoF && bx > anchoF && qx > anchoF) || (ay > altoF && by > altoF && qy > altoF)
            ) continue
            val o6 = n * 6
            trazo[o6] = ax; trazo[o6 + 1] = ay; trazo[o6 + 2] = bx; trazo[o6 + 3] = by; trazo[o6 + 4] = qx; trazo[o6 + 5] = qy
            val col = sombreado[k]
            val o3 = n * 3
            tintas[o3] = col; tintas[o3 + 1] = col; tintas[o3 + 2] = col
            n++
            if (n == tanda) { soltar(lienzo, n); n = 0 }
        }
        if (n > 0) soltar(lienzo, n)
    }

    private fun soltar(lienzo: Canvas, n: Int) {
        lienzo.drawVertices(
            Canvas.VertexMode.TRIANGLES, n * 6, trazo, 0, null, 0, tintas, 0, null, 0, 0, pintura
        )
    }

    /** Reparto en cubos por hondura: lineal en el número de triángulos. */
    private fun ordenar(usados: Int, moviendo: Boolean, fx: Float, fy: Float, fz: Float) {
        if (orden.size < t) { orden = IntArray(t); claves = IntArray(t) }
        var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
        val hondos = FloatArray(usados)
        for (s in 0 until usados) {
            val k = if (moviendo) porTamano[s] else s
            val h = centros[k * 3] * fx + centros[k * 3 + 1] * fy + centros[k * 3 + 2] * fz
            hondos[s] = h
            if (h < min) min = h
            if (h > max) max = h
        }
        val nCubos = 65536
        if (cubos.size != nCubos + 1) cubos = IntArray(nCubos + 1)
        java.util.Arrays.fill(cubos, 0)
        val escala = if (max > min) (nCubos - 1) / (max - min) else 0f
        for (s in 0 until usados) {
            // De lejos (hondura mayor) a cerca: el cubo 0 es el más lejano.
            val cubo = ((max - hondos[s]) * escala).toInt().coerceIn(0, nCubos - 1)
            claves[s] = cubo
            cubos[cubo + 1]++
        }
        for (c in 1..nCubos) cubos[c] += cubos[c - 1]
        for (s in 0 until usados) {
            val k = if (moviendo) porTamano[s] else s
            orden[cubos[claves[s]]++] = k
        }
    }

    companion object {
        /** Los triángulos que se pintan mientras se gira la vista. */
        const val TOPE_MOVIENDO = 24_000
        /** Triángulos por `drawVertices`. */
        const val TANDA = 20_000
    }
}
