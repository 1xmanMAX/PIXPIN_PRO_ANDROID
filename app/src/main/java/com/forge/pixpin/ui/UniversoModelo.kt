package com.forge.pixpin.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **Los universos: el sistema solar como organizador** (19-sep-2026).
 *
 * Lo pidió el usuario: que la vista estelar sirva **como un gestor de archivos**. Un proyecto es
 * un **universo** que al entrar está **vacío**, y se va llenando a mano: archivos del chat, hojas
 * del proyecto, notas, rótulos de letras, figuras, imágenes y emojis, cada cosa donde uno la
 * deja. Cualquier cuerpo puede abrir **su propio subespacio** —un sistema solar hecho a partir de
 * un solo archivo, con él de sol— al que se le cuelgan notas, imágenes y lo que haga falta; y así
 * hacia dentro, sin fondo. Los cuerpos se **vinculan** entre sí con una raya.
 *
 * Aquí solo consta dónde está cada cosa y qué señala: los archivos **no se copian**. Un cuerpo
 * cuyo mensaje u hoja ya no existe se pinta apagado y se puede quitar. Es de este aparato.
 */
@Serializable
data class Universos(
    /** Todos los espacios, por id: los de los proyectos (`p:<id>`) y los de dentro (`e:<n>`). */
    val espacios: Map<String, Espacio> = emptyMap()
) {
    /** El espacio [id], o uno vacío con ese id si aún no se ha tocado. */
    fun espacio(id: String, nombre: String = ""): Espacio = espacios[id] ?: Espacio(id = id, nombre = nombre)

    fun con(e: Espacio) = copy(espacios = espacios + (e.id to e))

    /**
     * **Abre un subespacio desde un cuerpo**: nace vacío, con el cuerpo de sol, y el cuerpo se
     * queda apuntando a él. Si ya lo tenía, no se toca nada. Devuelve el universo y el id.
     */
    fun conSubespacio(espacio: String, cuerpo: String, ahora: Long): Pair<Universos, String?> {
        val e = espacios[espacio] ?: return this to null
        val c = e.cuerpos.firstOrNull { it.id == cuerpo } ?: return this to null
        c.espacio?.let { return this to it }
        val id = "e:$ahora"
        val nuevo = Espacio(id = id, nombre = c.texto, padre = espacio, sol = c.copy(x = 0f, y = 0f, espacio = null))
        return con(e.conCuerpo(c.copy(espacio = id))).con(nuevo) to id
    }

    /** Quita el cuerpo y, con él, **todo lo que hubiera dentro** de su subespacio, hasta el fondo. */
    fun sinCuerpo(espacio: String, cuerpo: String): Universos {
        val e = espacios[espacio] ?: return this
        val c = e.cuerpos.firstOrNull { it.id == cuerpo } ?: return this
        val fuera = c.espacio?.let { dentroDe(it) }.orEmpty()
        return copy(espacios = (espacios - fuera) + (e.id to e.sinCuerpo(cuerpo)))
    }

    /** El espacio [id] y todos los que cuelgan de él. */
    fun dentroDe(id: String): Set<String> {
        val salida = LinkedHashSet<String>()
        val cola = ArrayDeque(listOf(id))
        while (cola.isNotEmpty()) {
            val uno = cola.removeFirst()
            if (!salida.add(uno)) continue
            espacios[uno]?.cuerpos?.mapNotNullTo(cola) { it.espacio }
        }
        return salida
    }

    /** El camino desde la raíz hasta [id], para las migas de arriba. */
    fun camino(id: String): List<Espacio> {
        val salida = ArrayList<Espacio>()
        var uno = espacios[id]
        while (uno != null && salida.size < 32) {
            salida.add(0, uno)
            uno = uno.padre?.let { espacios[it] }
        }
        return salida
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun leer(texto: String?): Universos =
            if (texto.isNullOrBlank()) Universos()
            else runCatching { json.decodeFromString(serializer(), texto) }.getOrDefault(Universos())

        fun escribir(u: Universos): String = json.encodeToString(serializer(), u)

        /** El espacio raíz de un proyecto. */
        fun deProyecto(proyecto: String) = "p:$proyecto"
    }
}

@Serializable
data class Espacio(
    val id: String,
    val nombre: String = "",
    /** El espacio del que se entró, o null si es el de un proyecto. */
    val padre: String? = null,
    /** El cuerpo que hace de sol en un subespacio: una copia del que lo abrió. */
    val sol: Cuerpo? = null,
    val cuerpos: List<Cuerpo> = emptyList(),
    /** Los vínculos entre cuerpos de este espacio ([SOL] es el sol). */
    val vinculos: List<EnlaceDeGalaxia> = emptyList()
) {
    fun conCuerpo(c: Cuerpo) =
        copy(cuerpos = if (cuerpos.any { it.id == c.id }) cuerpos.map { if (it.id == c.id) c else it } else cuerpos + c)

    fun sinCuerpo(id: String) =
        copy(cuerpos = cuerpos.filterNot { it.id == id }, vinculos = vinculos.filterNot { it.a == id || it.b == id })

    /** Vincula dos cuerpos, o los desvincula si ya lo estaban. */
    fun alternarVinculo(a: String, b: String): Espacio {
        if (a == b) return this
        return if (vinculos.any { it.une(a, b) }) copy(vinculos = vinculos.filterNot { it.une(a, b) })
        else copy(vinculos = vinculos + EnlaceDeGalaxia(minOf(a, b), maxOf(a, b)))
    }

    /** Si ya hay un cuerpo que señala a lo mismo: no se mete dos veces el mismo archivo. */
    fun yaEsta(clase: String, ref: String?) = ref != null && cuerpos.any { it.clase == clase && it.ref == ref }

    /**
     * **Dónde nace el siguiente**: en espiral alrededor del sol, por fuera de lo que ya hay. Así
     * lo que se va añadiendo «poco a poco» no cae encima de lo anterior.
     */
    fun sitioLibre(): PuntoDeGalaxia {
        val p = Galaxia.enEspiral(cuerpos.size + 1)
        return PuntoDeGalaxia(p.x * 0.8f, p.y * 0.8f)
    }

    companion object {
        /** El id con el que un vínculo nombra al sol del espacio. */
        const val SOL = "*sol*"
    }
}

/** Una cosa en un espacio. Qué es lo dice [clase]; qué señala, [ref] y [ruta]. */
@Serializable
data class Cuerpo(
    val id: String,
    val clase: String,
    /** El texto de la nota o del rótulo, el emoji, el nombre de la figura, o el nombre del archivo. */
    val texto: String = "",
    /** El id del mensaje del chat ([MENSAJE]) o de la hoja ([HOJA]). */
    val ref: String? = null,
    /** El archivo de una imagen, o la miniatura posible de un mensaje. */
    val ruta: String? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    val tamano: Float = 1f,
    val color: Int = 0,
    /** El subespacio que abre, si se le ha creado. */
    val espacio: String? = null
) {
    companion object {
        const val NOTA = "nota"
        const val ROTULO = "rotulo"
        const val EMOJI = "emoji"
        const val FIGURA = "figura"
        const val IMAGEN = "imagen"
        const val MENSAJE = "mensaje"
        const val HOJA = "hoja"

        const val TAMANO_MIN = 0.5f
        const val TAMANO_MAX = 4f

        val FIGURAS = listOf("circulo", "cuadro", "triangulo", "estrella", "flecha")
    }
}
