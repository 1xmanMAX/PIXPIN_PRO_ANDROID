package com.forge.pixpin.sincro

import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import kotlinx.serialization.json.JsonElement

/**
 * **Juntar dos versiones de un proyecto sin preguntar y sin que mande nadie.**
 *
 * Un proyecto es un índice: qué hojas tiene, en qué orden, cómo se llama. Si en el teléfono se
 * añade una hoja y en la tableta otra, lo que uno espera es **tener las dos**, no elegir entre dos
 * listas.
 *
 * Hasta el 15-sep-2026 un aparato podía ponerse de **maestro** y ganar los empates; ese día, con un
 * proyecto roto en un aparato, la sincronización tomó un lienzo que faltaba por un borrado y lo quitó
 * en los dos. El usuario pidió entonces **fusionar sin predominancia** (A+B y A+C → A+B+C), y así es
 * ahora:
 *
 * - **Las hojas se suman.** Una hoja que falta en un lado **solo se quita si allí se quitó a mano**
 *   —la marca de [Proyecto.quitadas]— y **ni así si en el otro lado se cambió** (su lienzo, su tabla,
 *   su nota): lo modificado gana a lo borrado. Una hoja que simplemente falta, sin marca, se devuelve.
 *   Es el conjunto «observed-remove» de Bieniusa et al. (2012): borrar quita lo que uno vio.
 * - **Cada hoja y los datos del proyecto se juntan campo a campo** con [Fusion]: una nota por
 *   párrafos, el nombre cambiado en un solo lado pasa; cambiado en los dos, gana el proyecto tocado
 *   más tarde.
 * - **El orden**: el del mío con lo nuevo del otro detrás de su vecina ([Fusion.ordenJunto]).
 */
object Mezcla {

    fun proyecto(
        mio: Proyecto?,
        suyo: Proyecto?,
        base: Proyecto?,
        /**
         * Las hojas (por id) y croquis cuyos archivos cambiaron **en este aparato** desde lo acordado.
         * Una hoja quitada allí y cambiada aquí vuelve.
         */
        cambiadasAqui: Set<String> = emptySet(),
        /** Lo mismo en el otro aparato. */
        cambiadasAlli: Set<String> = emptySet(),
        /** Cuánto va adelantado el reloj del otro. */
        desfase: Long = 0L
    ): Proyecto? {
        if (mio == null) return suyo
        if (suyo == null) return mio
        if (mio == suyo) return mio
        val criterio = Fusion.Criterio(mioMasNuevo = mio.tocado >= suyo.tocado - desfase, desfase = desfase)

        // Los datos sueltos del proyecto (nombre, archivado, PDF, códigos), campo a campo.
        fun campos(p: Proyecto?): JsonElement? = p?.let {
            Proyectos.json.encodeToJsonElement(Proyecto.serializer(), it.copy(hojas = emptyList(), croquis = emptyList(), quitadas = emptyList(), tocado = 0L))
        }
        val datos = Fusion.json(campos(base), campos(mio), campos(suyo), criterio)
            ?.let { runCatching { Proyectos.json.decodeFromJsonElement(Proyecto.serializer(), it) }.getOrNull() }
            ?: if (criterio.mioMasNuevo) mio else suyo

        val hm = unicas(mio.hojas).associateBy { it.id }
        val hs = unicas(suyo.hojas).associateBy { it.id }
        val hb = base?.hojas?.let(::unicas)?.associateBy { it.id } ?: emptyMap()
        val quitadasAqui = mio.quitadas.toHashSet()
        val quitadasAlli = suyo.quitadas.toHashSet()
        val orden = Fusion.ordenJunto(hm.keys.toList(), hs.keys.toList(), base?.hojas?.map { it.id }) { k ->
            val m = hm[k]; val s = hs[k]; val b = hb[k]
            when {
                m != null && s != null -> true
                // Falta en el suyo: se queda salvo que allí se quitara a mano y aquí nadie la tocara.
                m != null -> k !in quitadasAlli || k in cambiadasAqui || (b != null && m != b)
                s != null -> k !in quitadasAqui || k in cambiadasAlli || (b != null && s != b)
                else -> false
            }
        }
        val hojas = orden.map { k -> hoja(hb[k], hm[k], hs[k], criterio) }

        val croquis = Fusion.ordenJunto(mio.croquis.distinct(), suyo.croquis.distinct(), base?.croquis?.distinct()) { c ->
            val m = c in mio.croquis; val s = c in suyo.croquis
            val enBase = base?.croquis?.contains(c) == true
            when {
                m && s -> true
                !enBase -> true
                m -> c in cambiadasAqui
                else -> c in cambiadasAlli
            }
        }
        val quedan = orden.toHashSet()
        return datos.copy(
            id = mio.id,
            hojas = hojas,
            croquis = croquis,
            tocado = maxOf(mio.tocado, suyo.tocado),
            quitadas = (mio.quitadas + suyo.quitadas).distinct().filter { it !in quedan }.takeLast(Proyectos.MARCAS_DE_QUITADAS)
        )
    }

    private fun hoja(b: Hoja?, m: Hoja?, s: Hoja?, criterio: Fusion.Criterio): Hoja {
        if (m == null) return s!!
        if (s == null || m == s) return m
        fun j(h: Hoja?) = h?.let { Proyectos.json.encodeToJsonElement(Hoja.serializer(), it) }
        return Fusion.json(j(b), j(m), j(s), criterio)
            ?.let { runCatching { Proyectos.json.decodeFromJsonElement(Hoja.serializer(), it) }.getOrNull() }
            ?.copy(id = m.id)
            ?: if (criterio.mioMasNuevo) m else s
    }

    private fun unicas(h: List<Hoja>): List<Hoja> {
        val vistas = HashSet<String>()
        return h.filter { vistas.add(it.id) }
    }

    /**
     * **Qué hojas y croquis cambiaron** en un lado desde lo acordado, por sus archivos: [archivos] es
     * ruta → resumen de ese lado y [acordado] lo de la base. Un archivo que no estaba en la base
     * también cuenta como cambiado.
     */
    fun cambiadas(p: Proyecto?, archivos: Map<String, String>, acordado: Map<String, String>): Set<String> {
        if (p == null) return emptySet()
        fun cambio(rel: String?) = rel != null && archivos[rel] != null && archivos[rel] != acordado[rel]
        val salida = HashSet<String>()
        for (h in p.hojas) {
            if (cambio(h.dibujo?.let { "pins/draw/$it.excalidraw.gz" }) || cambio(h.croquis?.let { "croquis3d/$it.croquis.gz" }) ||
                cambio(h.tabla?.let { "tablas/${it.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json" })
            ) salida += h.id
        }
        for (c in p.croquis) if (cambio("croquis3d/$c.croquis.gz")) salida += c
        return salida
    }
}
