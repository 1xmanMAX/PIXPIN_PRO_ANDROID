package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * **El croquis como modelo 3D**: un OBJ con su MTL, para abrirlo en Blender, en el visor
 * de Windows, en el de macOS o en cualquier programa de tres dimensiones.
 *
 * Es lo que hace Feather con sus «export to OBJ / glTF», y es lo que convierte un croquis
 * en algo que sale de la aplicación: un trazo dibujado en el aire pasa a ser un tubo de
 * verdad, con su grueso y su color, que se puede imprimir, iluminar o meter en una escena.
 *
 * ## Cómo se vuelve malla lo que aquí son rayas
 *
 * - **Cada trazo es un tubo.** Por cada punto del trazo se planta un anillo de [LADOS]
 *   vértices con el radio de su calibre, orientado por un marco que avanza por transporte
 *   paralelo —el normal del anillo anterior, proyectado al plano del tramo siguiente— para
 *   que el tubo no se retuerza en las curvas. Las dos bocas se tapan con un abanico.
 * - **Cada hoja son sus tiras**, dos triángulos por tira, con la transparencia de la hoja.
 *   La bola también: sus tiras son su malla.
 * - Lo escondido no sale, como en Feather: «solo los grupos visibles».
 *
 * ## El sistema de ejes
 *
 * El croquis tiene la `z` hacia arriba y la `y` hacia dentro. Casi todo lo que abre un OBJ
 * espera la `y` hacia arriba y la `z` hacia el que mira, así que se escribe `(x, z, -y)`:
 * el modelo aparece de pie y mirando a quien lo abre, en vez de tumbado.
 *
 * Los colores van dos veces a propósito: en el MTL —que es lo que entiende todo el mundo—
 * y como color de vértice detrás de cada `v` (`v x y z r g b`), que es una extensión que
 * Blender y MeshLab leen y que salva el color cuando el MTL se pierde por el camino.
 *
 * Sin Android: entra el croquis y salen dos textos. Se comprueba sin dispositivo.
 */
object ExportarObj {

    /** Cuántos lados tiene cada anillo del tubo. Ocho se ve redondo y pesa poco. */
    const val LADOS = 8

    class Salida(val obj: String, val mtl: String, val vertices: Int, val caras: Int)

    /** El croquis entero, o null si no hay nada visible que escribir. */
    fun escribir(croquis: Croquis, nombre: String): Salida? {
        val obj = StringBuilder()
        val materiales = LinkedHashMap<String, String>()
        var vertices = 0
        var normales = 0
        var caras = 0

        fun materialDe(color: String, opacidad: Double): String {
            val (r, g, b) = rgb(color)
            val clave = "m_" + color.trim().removePrefix("#").lowercase(Locale.ROOT) +
                "_" + (opacidad.coerceIn(0.0, 1.0) * 100).toInt()
            materiales.getOrPut(clave) {
                "newmtl $clave\nKd ${n(r)} ${n(g)} ${n(b)}\nKa ${n(r * 0.2)} ${n(g * 0.2)} ${n(b * 0.2)}\n" +
                    "Ks 0.1 0.1 0.1\nNs 20\nd ${n(opacidad.coerceIn(0.0, 1.0))}\nillum 2\n"
            }
            return clave
        }

        fun vertice(p: Pt3, color: Triple<Double, Double, Double>) {
            // El cambio de ejes: z arriba → y arriba.
            obj.append("v ").append(n(p.x)).append(' ').append(n(p.z)).append(' ').append(n(-p.y))
            obj.append(' ').append(n(color.first)).append(' ').append(n(color.second))
                .append(' ').append(n(color.third)).append('\n')
            vertices++
        }

        fun normal(d: Pt3) {
            val u = normalizado(d)
            obj.append("vn ").append(n(u.x)).append(' ').append(n(u.z)).append(' ').append(n(-u.y)).append('\n')
            normales++
        }

        obj.append("# PixPin — croquis en el espacio: ").append(nombre).append('\n')
        obj.append("# Unidades del croquis. Ejes: y arriba, z hacia quien mira.\n")
        obj.append("mtllib ").append(nombre).append(".mtl\n")

        for (t in croquis.trazos) {
            if (t.oculto || t.puntos.size < 2) continue
            val radio = ((t.calibre ?: t.grosor) / 2.0).coerceAtLeast(0.05)
            val color = rgb(t.color)
            val puntos = sinRepetidos(t.puntos)
            if (puntos.size < 2) continue
            obj.append("o trazo_").append(limpio(t.id)).append('\n')
            obj.append("usemtl ").append(materialDe(t.color, t.opacidad)).append('\n')
            val marcos = marcos(puntos)
            val primerVertice = vertices + 1
            val primeraNormal = normales + 1
            // Los anillos, uno por punto, con su normal por vértice.
            for ((i, p) in puntos.withIndex()) {
                val (u, v) = marcos[i]
                for (k in 0 until LADOS) {
                    val a = 2 * Math.PI * k / LADOS
                    val radial = mas(por(u, cos(a)), por(v, sin(a)))
                    vertice(mas(p, por(radial, radio)), color)
                    normal(radial)
                }
            }
            // Las caras entre anillo y anillo: dos triángulos por cuadro.
            for (i in 0 until puntos.size - 1) {
                for (k in 0 until LADOS) {
                    val k2 = (k + 1) % LADOS
                    val a = primerVertice + i * LADOS + k
                    val b = primerVertice + i * LADOS + k2
                    val c = primerVertice + (i + 1) * LADOS + k2
                    val d = primerVertice + (i + 1) * LADOS + k
                    val na = primeraNormal + i * LADOS + k
                    val nb = primeraNormal + i * LADOS + k2
                    val nc = primeraNormal + (i + 1) * LADOS + k2
                    val nd = primeraNormal + (i + 1) * LADOS + k
                    obj.append("f $a//$na $b//$nb $c//$nc\n")
                    obj.append("f $a//$na $c//$nc $d//$nd\n")
                    caras += 2
                }
            }
            // Las dos bocas: un vértice en el centro y un abanico.
            val tangenteInicio = menos(puntos[1], puntos[0])
            val tangenteFin = menos(puntos[puntos.size - 1], puntos[puntos.size - 2])
            vertice(puntos.first(), color)
            normal(por(tangenteInicio, -1.0))
            val centroInicio = vertices
            val normalInicio = normales
            for (k in 0 until LADOS) {
                val k2 = (k + 1) % LADOS
                obj.append("f $centroInicio//$normalInicio ${primerVertice + k2}//$normalInicio ${primerVertice + k}//$normalInicio\n")
                caras++
            }
            vertice(puntos.last(), color)
            normal(tangenteFin)
            val centroFin = vertices
            val normalFin = normales
            val ultimoAnillo = primerVertice + (puntos.size - 1) * LADOS
            for (k in 0 until LADOS) {
                val k2 = (k + 1) % LADOS
                obj.append("f $centroFin//$normalFin ${ultimoAnillo + k}//$normalFin ${ultimoAnillo + k2}//$normalFin\n")
                caras++
            }
        }

        for (l in croquis.laminas) {
            val tiras = l.tiras()
            if (tiras.isEmpty()) continue
            val color = rgb(l.color)
            obj.append("o hoja_").append(limpio(l.id)).append('\n')
            obj.append("usemtl ").append(materialDe(l.color, (l.opacidad * 0.35).coerceIn(0.05, 1.0))).append('\n')
            for (tira in tiras) {
                if (tira.size < 3) continue
                val nrm = producto(menos(tira[1], tira[0]), menos(tira[tira.lastIndex], tira[0]))
                if (largo(nrm) < 1e-12) continue
                normal(nrm)
                val ni = normales
                val primero = vertices + 1
                for (p in tira) vertice(p, color)
                // El polígono, en abanico desde el primero.
                for (i in 1 until tira.size - 1) {
                    obj.append("f $primero//$ni ${primero + i}//$ni ${primero + i + 1}//$ni\n")
                    caras++
                }
            }
        }

        if (caras == 0) return null
        val mtl = StringBuilder("# PixPin — materiales de ").append(nombre).append('\n')
        for (m in materiales.values) mtl.append(m).append('\n')
        return Salida(obj.toString(), mtl.toString(), vertices, caras)
    }

    /**
     * Un marco (dos vectores perpendiculares a la tangente) por punto, por **transporte
     * paralelo**: el normal de cada punto es el del anterior proyectado al plano
     * perpendicular a la tangente nueva. Es la receta de Wang para marcos de rotación
     * mínima, en su forma corta; lo que evita es que el tubo se retuerza en las curvas.
     */
    internal fun marcos(puntos: List<Pt3>): List<Pair<Pt3, Pt3>> {
        val n = puntos.size
        val tangentes = (0 until n).map { i ->
            val a = puntos[(i - 1).coerceAtLeast(0)]
            val b = puntos[(i + 1).coerceAtMost(n - 1)]
            normalizado(menos(b, a)).let { if (largo(it) < 1e-9) Pt3(0.0, 1.0, 0.0) else it }
        }
        val salida = ArrayList<Pair<Pt3, Pt3>>(n)
        // El primer normal: cualquiera perpendicular a la primera tangente.
        val t0 = tangentes[0]
        val ayuda = if (abs(t0.z) < 0.9) Pt3(0.0, 0.0, 1.0) else Pt3(1.0, 0.0, 0.0)
        var u = normalizado(producto(t0, ayuda))
        for (i in 0 until n) {
            val t = tangentes[i]
            // Proyectar el normal anterior al plano perpendicular a esta tangente.
            val sinTangente = menos(u, por(t, escalar(u, t)))
            u = if (largo(sinTangente) < 1e-9) {
                val otra = if (abs(t.z) < 0.9) Pt3(0.0, 0.0, 1.0) else Pt3(1.0, 0.0, 0.0)
                normalizado(producto(t, otra))
            } else normalizado(sinTangente)
            val v = normalizado(producto(t, u))
            salida += u to v
        }
        return salida
    }

    private fun sinRepetidos(puntos: List<Pt3>): List<Pt3> {
        val salida = ArrayList<Pt3>(puntos.size)
        for (p in puntos) {
            val ultimo = salida.lastOrNull()
            if (ultimo == null || largo(menos(p, ultimo)) > 1e-6) salida += p
        }
        return salida
    }

    internal fun rgb(color: String): Triple<Double, Double, Double> {
        val hex = color.trim().removePrefix("#")
        val n = hex.toLongOrNull(16) ?: return Triple(0.2, 0.2, 0.2)
        val v = if (hex.length == 8) (n and 0xFFFFFF) else n
        return Triple(((v shr 16) and 0xff) / 255.0, ((v shr 8) and 0xff) / 255.0, (v and 0xff) / 255.0)
    }

    private fun limpio(id: String) = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "x" }

    private fun n(x: Double): String = String.format(Locale.ROOT, "%.4f", x).trimEnd('0').trimEnd('.').ifEmpty { "0" }
        .let { if (it == "-0") "0" else it }
}
