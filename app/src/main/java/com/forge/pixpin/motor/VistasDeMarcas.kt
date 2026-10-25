package com.forge.pixpin.motor

import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.math.BigDecimal

/**
 * **Lo que se veía al poner un marcador del lienzo** (25-oct-2026, como las vistas con nombre de
 * AutoCAD y como el PC). El centro de la pantalla y cuánto mundo cabía, en coordenadas del
 * lienzo. Al ir al marcador se vuelve a ver eso mismo: en la misma pantalla, la cámara exacta de
 * antes; en otra (el PC apaisado, el móvil de pie), todo lo guardado, entero y centrado.
 *
 * Es `VistaDeMarca` de `crates/pixpin-motor2d/src/marcas.rs` del PC, con las fórmulas de
 * `docs/sincronizar-marcos-vistas-y-tinta.md` §3.8. La cámara de aquí es [Viewport]: la esquina
 * de arriba a la izquierda del mundo que se ve es `(-scrollX, -scrollY)`.
 */
data class VistaDeMarca(val x: Double, val y: Double, val ancho: Double, val alto: Double) {

    /**
     * **La cámara que la vuelve a enseñar** en un hueco de [w]×[h] píxeles: `zoom =
     * min(w/ancho, h/alto)` acotado a [ZOOM_MINIMO]..[ZOOM_MAXIMO] y la esquina en
     * `(x − w/2z, y − h/2z)`; en Excalidraw, `scrollX = w/2z − x`.
     */
    fun aViewport(w: Double, h: Double): Viewport {
        val ww = w.coerceAtLeast(1.0)
        val hh = h.coerceAtLeast(1.0)
        val zoom = minOf(ww / ancho.coerceAtLeast(1e-9), hh / alto.coerceAtLeast(1e-9))
            .coerceIn(ZOOM_MINIMO, ZOOM_MAXIMO)
        return Viewport(scrollX = ww / (2 * zoom) - x, scrollY = hh / (2 * zoom) - y, zoom = zoom)
    }

    /** `"x y ancho alto"`, a diezmilésimas y sin `.0` en los enteros, como lo escribe el PC. */
    fun aTexto(): String = listOf(x, y, ancho, alto).joinToString(" ") { redondeado(it) }

    companion object {
        /** Los topes del aumento de §3.8, los mismos que el PC (`ZOOM_MINIMO`, `ZOOM_MAXIMO`). */
        const val ZOOM_MINIMO = 0.05
        const val ZOOM_MAXIMO = 30.0

        /**
         * Lo que enseña [v] en un hueco de [w]×[h] píxeles: `ancho = w/zoom`, `alto = h/zoom`,
         * `x = esquina + ancho/2`. Null si el hueco no mide nada.
         */
        fun deCamara(v: Viewport, w: Double, h: Double): VistaDeMarca? {
            if (!(w > 0 && h > 0 && v.zoom > 0 && v.zoom.isFinite())) return null
            val ancho = w / v.zoom
            val alto = h / v.zoom
            return VistaDeMarca(-v.scrollX + ancho / 2, -v.scrollY + alto / 2, ancho, alto)
        }

        /** Y de vuelta: cuatro números finitos, ancho y alto positivos; si no, no es una vista. */
        fun deTexto(t: String): VistaDeMarca? {
            val n = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (n.size < 4) return null
            val v = n.take(4).map { s -> s.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null }
            return VistaDeMarca(v[0], v[1], v[2], v[3]).takeIf { it.ancho > 0 && it.alto > 0 }
        }

        /**
         * El `redondeado` del PC: suelo de `v·10⁴ + ½` (las mitades hacia arriba, también las
         * negativas) y sin notación científica, que `Double.toString` sacaría con `1.0E-4`.
         */
        internal fun redondeado(v: Double): String {
            val r = Math.floor(v * 10_000.0 + 0.5) / 10_000.0
            if (r == Math.floor(r) && Math.abs(r) < 1e15) return r.toLong().toString()
            return BigDecimal(r.toString()).stripTrailingZeros().toPlainString()
        }
    }
}

/**
 * **El fichero de las vistas de un lienzo**: `pins/draw/<d>.vistas.json`, al lado de `<d>.marcas`
 * (cuya línea no cambia), `{"<id de la marca>": "x y ancho alto"}`. Viaja con el dibujo por
 * [AnotacionesDelAdjunto.vistasJuntoA], igual que las marcas.
 *
 * Se lee del disco cada vez (es diminuto): así un cambio que llega por la sincronización vale ya
 * en el siguiente toque. Quitar un marcador quita su entrada y sin ninguna se escribe `{}`, nunca
 * se borra: un fichero que desaparece no le dice nada al otro aparato y volvería.
 *
 * Sin Android: se prueba en `VistasDeMarcasTest`.
 */
object VistasDeMarcas {
    const val TERMINACION = ".vistas.json"

    fun archivo(filesDir: File, dibujo: String) = File(filesDir, "${AnotacionesDelAdjunto.CARPETA}/$dibujo$TERMINACION")

    /** Por id. Lo que no se entiende (un id que no es un número, un texto que no es vista) se salta. */
    fun deTexto(t: String?): Map<Long, VistaDeMarca> {
        if (t.isNullOrBlank()) return emptyMap()
        val o = runCatching { Json.parseToJsonElement(t) as? JsonObject }.getOrNull() ?: return emptyMap()
        val salida = sortedMapOf<Long, VistaDeMarca>()
        for ((id, v) in o) {
            val n = id.trim().toLongOrNull() ?: continue
            val texto = runCatching { v.jsonPrimitive.takeIf { it.isString }?.contentOrNull }.getOrNull() ?: continue
            VistaDeMarca.deTexto(texto)?.let { salida[n] = it }
        }
        return salida
    }

    /** `{}` sin ninguna; por id en orden, como el `BTreeMap` del PC. */
    fun aTexto(vistas: Map<Long, VistaDeMarca>): String =
        JsonObject(vistas.toSortedMap().entries.associate { (id, v) -> id.toString() to JsonPrimitive(v.aTexto()) }).toString()

    fun leer(filesDir: File, dibujo: String): Map<Long, VistaDeMarca> =
        deTexto(AnotacionesDelAdjunto.leer(archivo(filesDir, dibujo)))

    /**
     * Las escribe dejando solo las de [marcas] que quedan (las que [Marcas.con] echó por arriba de
     * [Marcas.MAXIMO] o se quitaron). Sin ninguna y sin fichero, no se crea: un lienzo que nunca
     * tuvo vistas no estrena un `{}` que viajar. Solo si cambia el texto.
     */
    fun escribir(filesDir: File, dibujo: String, vistas: Map<Long, VistaDeMarca>, marcas: List<Marca>) {
        val f = archivo(filesDir, dibujo)
        val ids = marcas.mapTo(HashSet()) { it.id }
        val quedan = vistas.filterKeys { it in ids }
        if (quedan.isEmpty() && !f.exists()) return
        val texto = aTexto(quedan)
        if (AnotacionesDelAdjunto.leer(f) == texto) return
        AnotacionesDelAdjunto.escribir(f, texto)
    }

    /**
     * **Guarda (o actualiza) la vista de la marca [id]** con la cámara [v] en un hueco de [w]×[h].
     * Al poner un marcador y con «Actualizar vista». Devuelve false si el hueco no mide nada.
     */
    fun guardar(filesDir: File, dibujo: String, id: Long, v: Viewport, w: Double, h: Double, marcas: List<Marca>): Boolean {
        val vista = VistaDeMarca.deCamara(v, w, h) ?: return false
        escribir(filesDir, dibujo, leer(filesDir, dibujo) + (id to vista), marcas)
        return true
    }

    /** Después de quitar o recortar marcas: poda las entradas que ya no tienen marca. */
    fun podar(filesDir: File, dibujo: String, marcas: List<Marca>) {
        if (!archivo(filesDir, dibujo).exists()) return
        escribir(filesDir, dibujo, leer(filesDir, dibujo), marcas)
    }

    /** La cámara para ir a la marca [id] en un hueco de [w]×[h]; null si no tiene vista (va como antes). */
    fun camaraPara(filesDir: File, dibujo: String, id: Long, w: Double, h: Double): Viewport? {
        if (!(w > 0 && h > 0)) return null
        return leer(filesDir, dibujo)[id]?.aViewport(w, h)
    }
}
