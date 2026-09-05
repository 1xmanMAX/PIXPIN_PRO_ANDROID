package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.ExportarHtml
import com.forge.pixpin.motor.Pt3
import java.util.Locale

/**
 * **El croquis del espacio, listo para meter en un documento web.**
 *
 * Aquí solo se escribe **la geometría**: un JSON compacto que el visor del motor
 * (`motor/VisorEspacio`) sabe pintar. El documento —la barra, las páginas, guardar— lo arma
 * `motor/ExportarHtml`, que es el mismo para los dibujos planos y para esto: una sola
 * interfaz para las dos clases de página.
 *
 * ## Qué viaja
 *
 * - **Los trazos, tal como los ve la aplicación**: no sus puntos crudos, sino los de su
 *   **esqueleto cocido** —la espina suave y el ancho de cada muestra, que es lo que da el
 *   afilado de las puntas y el engorde donde se apretó—. Es la misma cuenta que hace el
 *   Motor Pluma para pintarlos en pantalla, hecha aquí una vez; sin esto lo exportado salía
 *   como una manguera de grosor constante y no se parecía a lo dibujado. Los trazos del
 *   régimen viejo —sin tiempos ni calibre— van con sus puntos y su grosor, como se pintan.
 * - **Las hojas planas**, como su **contorno**: es una sola figura cerrada, pesa una décima
 *   parte que su malla de tiras y se pinta como en la aplicación —un velo más opaco cuanto
 *   más de frente—. Va con su normal, que es lo que decide ese velo.
 * - **Los sólidos** —bola, cilindro, cono, anillo— sí van como sus tiras: son las caras, y
 *   son las que le dan bulto. El visor descarta las de atrás y sombrea cada una.
 * - **Las imágenes puestas en el espacio**, con su textura metida dentro del archivo y sus
 *   cuatro esquinas.
 * - **El sol**, si está encendido: es lo que decide de qué lado se alumbran los sólidos.
 * - Los grupos que se usan, las vistas guardadas y la caja de todo.
 *
 * Sin Android: entran el croquis y una manera de leer una imagen, y sale texto. Se comprueba
 * sin dispositivo.
 */
object ExportarCroquisHtml {

    const val MIME_TYPE = ExportarHtml.MIME_TYPE

    /**
     * La geometría del croquis, en JSON. `null` si no hay nada visible que enseñar.
     *
     * [imagenIncrustada] convierte la ruta de una imagen del croquis en un `data:` con la
     * imagen dentro, o devuelve `null` si no se puede leer. Entra como función para que esto
     * no sepa nada de discos ni de contextos.
     */
    fun datos(
        croquis: Croquis,
        camara: Camara3D,
        imagenIncrustada: (String) -> String? = { null }
    ): String? {
        val trazos = croquis.trazos.filter { !it.oculto && it.puntos.size >= 2 }
        // Las hojas no se esconden ni van en grupos: en el croquis son la mesa de dibujo.
        val hojas = croquis.laminas.filter { it.tiras().isNotEmpty() }
        val imagenes = croquis.imagenes.filter { !it.oculto && it.esquinas.size >= 4 }
            .mapNotNull { im -> imagenIncrustada(im.ruta)?.let { im to it } }
        if (trazos.isEmpty() && hojas.isEmpty() && imagenes.isEmpty()) return null

        val d = StringBuilder(1 shl 16)
        val caja = Caja()
        d.append("{\"f\":\"").append(croquis.colorDelFondo ?: "").append('"')
        // La luz con la que se sombrean los trazos: la del croquis, la misma que en pantalla.
        val rayo = luzDelMundo(croquis)
        d.append(",\"luz\":[").append(n(rayo.x)).append(',').append(n(rayo.y))
            .append(',').append(n(rayo.z)).append(']')
        d.append(",\"c\":").append(camaraJson(camara))

        val usados = LinkedHashSet<String>()
        trazos.forEach { t -> t.grupo?.let { usados.add(it) } }
        imagenes.forEach { (im, _) -> im.grupo?.let { usados.add(it) } }
        val nombres = croquis.grupos.associate { it.id to it.nombre }
        d.append(",\"gr\":[")
        usados.forEachIndexed { i, id ->
            if (i > 0) d.append(',')
            d.append("{\"i\":\"").append(escaparJson(id)).append("\",\"n\":\"")
                .append(escaparJson(nombres[id] ?: id)).append("\"}")
        }
        d.append(']')

        d.append(",\"tr\":[")
        trazos.forEachIndexed { i, t ->
            if (i > 0) d.append(',')
            d.append("{\"c\":\"").append(t.color).append('"')
            d.append(",\"w\":").append(n(t.calibre ?: t.grosor))
            if (t.opacidad < 1.0) d.append(",\"o\":").append(n(t.opacidad))
            t.grupo?.let { g -> d.append(",\"g\":\"").append(escaparJson(g)).append('"') }
            // La espina cocida si la hay, y con ella el ancho de cada muestra: es lo que
            // hace que un trazo exportado se parezca al de la pantalla. Ver [cocerEsqueleto].
            // El lápiz de anotar va como raya lisa también aquí: sin esqueleto ni anchos, que
            // es justo lo que lo hace una anotación. Ver [PuntaDelPincel.plana].
            val hueso = if (t.punta.plana) null else cocerEsqueleto(t)
            if (hueso != null) {
                d.append(",\"p\":[")
                for (k in 0 until hueso.cuantas) {
                    if (k > 0) d.append(',')
                    val x = hueso.xyz[k * 3]; val y = hueso.xyz[k * 3 + 1]; val z = hueso.xyz[k * 3 + 2]
                    d.append(n(x)).append(',').append(n(y)).append(',').append(n(z))
                    caja.mete(Pt3(x, y, z))
                }
                d.append("],\"a\":[")
                for (k in 0 until hueso.cuantas) {
                    if (k > 0) d.append(',')
                    d.append(n(hueso.anchos[k]))
                }
                d.append(']')
            } else {
                d.append(",\"p\":[")
                t.puntos.forEachIndexed { k, p ->
                    if (k > 0) d.append(',')
                    punto(d, p); caja.mete(p)
                }
                d.append(']')
            }
            d.append('}')
        }
        d.append(']')

        d.append(",\"ho\":[")
        hojas.forEachIndexed { i, l ->
            if (i > 0) d.append(',')
            d.append("{\"c\":\"").append(l.color).append('"')
            d.append(",\"o\":").append(n(l.opacidad))
            val contorno = l.contorno()
            if (l.esfera != null || contorno.size < 3) {
                // Un sólido: sus caras. El visor descarta las de atrás y sombrea las de
                // delante, que es lo que le da bulto.
                d.append(",\"k\":1,\"s\":[")
                l.tiras().forEachIndexed { k, tira ->
                    if (k > 0) d.append(',')
                    d.append('[')
                    tira.forEachIndexed { j, p ->
                        if (j > 0) d.append(',')
                        punto(d, p); caja.mete(p)
                    }
                    d.append(']')
                }
                d.append(']')
            } else {
                d.append(",\"b\":[")
                contorno.forEachIndexed { k, p ->
                    if (k > 0) d.append(',')
                    punto(d, p); caja.mete(p)
                }
                d.append(']')
                val nrm = normalDeTira(contorno)
                d.append(",\"n\":[").append(n(nrm.x)).append(',').append(n(nrm.y))
                    .append(',').append(n(nrm.z)).append(']')
            }
            d.append('}')
        }
        d.append(']')

        d.append(",\"im\":[")
        imagenes.forEachIndexed { i, (im, uri) ->
            if (i > 0) d.append(',')
            d.append("{\"u\":\"").append(uri).append('"')
            if (im.opacidad < 1.0) d.append(",\"o\":").append(n(im.opacidad))
            im.grupo?.let { g -> d.append(",\"g\":\"").append(escaparJson(g)).append('"') }
            d.append(",\"e\":[")
            im.esquinas.take(4).forEachIndexed { k, p ->
                if (k > 0) d.append(',')
                punto(d, p); caja.mete(p)
            }
            d.append("]}")
        }
        d.append(']')

        d.append(",\"vi\":[")
        croquis.vistas.forEachIndexed { i, v ->
            if (i > 0) d.append(',')
            d.append("{\"n\":\"").append(escaparJson(v.nombre)).append("\",\"c\":")
                .append(camaraJson(v.camara)).append('}')
        }
        d.append(']')

        // El sol, si alumbra: de él sale de qué lado se ven claros los sólidos.
        croquis.sol?.takeIf { croquis.luces.cuanto > 0.0 }?.let { sol ->
            val r = sol.rayo
            d.append(",\"sol\":[").append(n(-r.x)).append(',').append(n(-r.y))
                .append(',').append(n(-r.z)).append(']')
        }
        d.append(",\"cj\":").append(caja.json())
        d.append('}')
        return d.toString()
    }

    /** El croquis como una página de un documento web. Ver [ExportarHtml.HojaWeb]. */
    fun hoja(
        croquis: Croquis,
        camara: Camara3D,
        nombre: String,
        imagenIncrustada: (String) -> String? = { null },
        /** El papel que se está viendo, si el croquis no ha elegido uno. Ver [FONDO_DE_FABRICA]. */
        papel: String? = null
    ): ExportarHtml.HojaWeb.Espacio? =
        datos(croquis, camara, imagenIncrustada)?.let {
            ExportarHtml.HojaWeb.Espacio(
                nombre, it, croquis.colorDelFondo ?: papel ?: FONDO_DE_FABRICA
            )
        }

    /** El croquis como documento de una sola página. */
    fun pagina(
        croquis: Croquis,
        camara: Camara3D,
        titulo: String,
        imagenIncrustada: (String) -> String? = { null },
        papel: String? = null
    ): String? = hoja(croquis, camara, "", imagenIncrustada, papel)
        ?.let { ExportarHtml.paginas(listOf(it), titulo) }

    /** El papel del croquis cuando no se ha elegido ninguno: la pizarra del tema oscuro. */
    const val FONDO_DE_FABRICA = "#14161c"

    private fun camaraJson(c: Camara3D): String = buildString {
        append("{\"g\":").append(n(c.giro))
        append(",\"i\":").append(n(c.inclinacion))
        append(",\"z\":").append(n(c.zoom))
        append(",\"b\":").append(n(c.balanceo))
        append(",\"l\":").append(n(c.lente))
        append(",\"r\":").append(if (c.rectilinea) 1 else 0)
        append(",\"c\":[").append(n(c.centro.x)).append(',').append(n(c.centro.y))
            .append(',').append(n(c.centro.z)).append("]}")
    }

    private fun punto(sb: StringBuilder, p: Pt3) {
        sb.append(n(p.x)).append(',').append(n(p.y)).append(',').append(n(p.z))
    }

    /** Tres decimales bastan y ahorran la mitad del archivo; sin cola de ceros. */
    internal fun n(v: Double): String {
        if (!v.isFinite()) return "0"
        if (v == Math.floor(v) && Math.abs(v) < 1e15) return v.toLong().toString()
        val s = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    private class Caja {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var z0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var z1 = -Double.MAX_VALUE
        fun mete(p: Pt3) {
            if (p.x < x0) x0 = p.x; if (p.x > x1) x1 = p.x
            if (p.y < y0) y0 = p.y; if (p.y > y1) y1 = p.y
            if (p.z < z0) z0 = p.z; if (p.z > z1) z1 = p.z
        }
        fun json(): String =
            if (x0 > x1) "[0,0,0,0,0,0]"
            else "[${n(x0)},${n(y0)},${n(z0)},${n(x1)},${n(y1)},${n(z1)}]"
    }

    private fun escaparJson(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
        }
    }
}
