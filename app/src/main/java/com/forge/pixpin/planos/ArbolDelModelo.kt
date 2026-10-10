package com.forge.pixpin.planos

import java.util.Locale

/**
 * **El árbol del modelo** (PC `9625d0d`, `crates/pixpin-cad/src/ventana3d/arbol.rs`): los niveles y
 * las categorías de un modelo 3D, cada uno con cuántos elementos tiene y su volumen, para
 * mostrarlos u ocultarlos. Un toque muestra u oculta; mantener deja solo ese (de su lista), como
 * Ctrl + clic en el PC. Aquí solo las cuentas: el panel lo pinta [Visor3DActivity].
 *
 * Un elemento se ve si no está oculto ni su nivel ni su categoría.
 */
class ArbolDelModelo private constructor(val niveles: List<Grupo>, val tipos: List<Grupo>, private val nivelesDelModelo: Boolean) {
    /** Qué agrupa una fila: un nivel (-1 = «sin nivel») o una categoría (su nombre legible). */
    sealed interface Clave {
        data class Nivel(val indice: Int) : Clave
        data class Tipo(val nombre: String) : Clave
    }

    /** [elementos]: los índices de los que tiene. */
    class Grupo(val clave: Clave, val nombre: String, val elementos: IntArray, val volumen: Double) {
        val cuenta: Int get() = elementos.size
    }

    /** Si un elemento se ve con [ocultos]. */
    fun visible(el: Modelo3D.Elemento, ocultos: Set<Clave>): Boolean {
        if (ocultos.isEmpty()) return true
        if (nivelesDelModelo && Clave.Nivel(el.nivel) in ocultos) return false
        return Clave.Tipo(TiposIfc.legible(el.tipo)) !in ocultos
    }

    /** Un toque en [clave]: mostrarla si estaba oculta, ocultarla si no. */
    fun alternar(ocultos: Set<Clave>, clave: Clave): Set<Clave> = if (clave in ocultos) ocultos - clave else ocultos + clave

    /** Mantener en [clave]: solo esa de su lista (las de la otra lista se quedan como estaban). */
    fun soloEse(ocultos: Set<Clave>, clave: Clave): Set<Clave> {
        val deNiveles = clave is Clave.Nivel
        val lista = if (deNiveles) niveles else tipos
        return ocultos.filterTo(HashSet()) { (it is Clave.Nivel) != deNiveles } + lista.map { it.clave }.filter { it != clave }
    }

    /** Un byte por elemento, 1 = escondido (lo que quiere [Pintor3D.ocultos]). */
    fun escondidos(m: Modelo3D, ocultos: Set<Clave>): ByteArray =
        ByteArray(m.elementos.size) { if (visible(m.elementos[it], ocultos)) 0 else 1 }

    companion object {
        /** [sinNivel]: el nombre de la fila de los que no están en ningún nivel. */
        fun de(m: Modelo3D, sinNivel: String): ArbolDelModelo {
            val porNivel = HashMap<Int, MutableList<Int>>()
            val porTipo = HashMap<String, MutableList<Int>>()
            for ((k, el) in m.elementos.withIndex()) {
                val n = if (el.nivel in m.niveles.indices) el.nivel else -1
                porNivel.getOrPut(n) { ArrayList() }.add(k)
                porTipo.getOrPut(TiposIfc.legible(el.tipo)) { ArrayList() }.add(k)
            }
            fun volumen(l: List<Int>) = l.sumOf { m.elementos[it].medidas.volumen.toDouble() }
            // Los niveles vacíos no dicen nada; «sin nivel», solo si hay niveles.
            val niveles = m.niveles.indices.mapNotNull { k ->
                porNivel[k]?.let { Grupo(Clave.Nivel(k), m.niveles[k].nombre, it.toIntArray(), volumen(it)) }
            }.toMutableList()
            val sin = porNivel[-1]
            if (sin != null && niveles.isNotEmpty()) niveles += Grupo(Clave.Nivel(-1), sinNivel, sin.toIntArray(), volumen(sin))
            val tipos = porTipo.entries.sortedBy { it.key }.map { (n, l) -> Grupo(Clave.Tipo(n), n, l.toIntArray(), volumen(l)) }
            return ArbolDelModelo(niveles, tipos, niveles.isNotEmpty())
        }

        /** Una medida con su unidad al cubo o al cuadrado si se sabe: «3.82 m³», o «3.82» en un DWG. */
        fun conUnidad(v: Double, unidad: String, potencia: Int): String {
            val u = unidad.trim()
            val n = String.format(Locale.ROOT, "%.2f", v)
            return if (u.isEmpty()) n else "$n $u${if (potencia == 3) "³" else "²"}"
        }

        /** El área que importa: la de una cara en muros (y lo que se mide en alzado), la de planta en losas, techos y zapatas. */
        fun areaQueToca(tipo: String, md: Modelo3D.Medidas): Float? {
            val t = tipo.lowercase(Locale.ROOT)
            return when {
                listOf("wall", "plate", "curtain", "window", "door").any { it in t } -> md.alzado
                listOf("slab", "roof", "covering", "footing", "ramp", "stair").any { it in t } -> md.planta
                else -> null
            }
        }

        /** «Muro · nombre · 1ER PISO · 3.82 m³ · 21.80 m²» (≈ delante del volumen si la malla no es cerrada). */
        fun describir(el: Modelo3D.Elemento, m: Modelo3D, unidad: String): String {
            val tipo = TiposIfc.legible(el.tipo)
            val t = StringBuilder(if (el.nombre.isBlank()) tipo else "$tipo · ${el.nombre}")
            m.niveles.getOrNull(el.nivel)?.let { t.append(" · ").append(it.nombre) }
            val md = el.medidas
            if (md.volumen > 0f) t.append(" · ").append(if (md.cerrado) "" else "≈ ").append(conUnidad(md.volumen.toDouble(), unidad, 3))
            areaQueToca(el.tipo, md)?.takeIf { it > 0f }?.let { t.append(" · ").append(conUnidad(it.toDouble(), unidad, 2)) }
            return t.toString()
        }
    }
}
