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

/**
 * **Un trozo del chat, tal como lo ve el universo.** Se le pasa ya masticado para que esto no
 * sepa nada de Android ni del almacén de mensajes. Ver [Universos.desdeElChat].
 */
data class NodoDelChat(
    val id: String,
    val nombre: String,
    /** A qué contesta, si es una respuesta. Null: cuelga del proyecto. */
    val respondeA: String? = null,
    /** El archivo que enseña, si tiene miniatura. */
    val ruta: String? = null,
    /** Si es una página de un documento: su número, empezando en 1. */
    val pagina: Int? = null,
    /** Si lo que hay dentro son páginas de un documento (un PDF del chat). */
    val esDocumento: Boolean = false,
    /** Si es solo texto —un comentario suelto— y no un archivo. */
    val esTexto: Boolean = false
)

/**
 * **El universo se arma solo con la forma del chat** (21-sep-2026, pedido por el usuario).
 *
 * La idea suya, y encaja con lo que el chat ya guarda:
 *
 * - **El proyecto es el universo**: su espacio raíz.
 * - **Cada archivo del chat es un sistema solar**: un cuerpo en la raíz que abre su propio
 *   espacio, donde él hace de sol.
 * - **Lo que se le responde son sus planetas**: dentro de ese espacio.
 * - **Un PDF tiene sus páginas de planetas**, y lo que se responde a una página son **los
 *   satélites de esa página**, dentro del espacio de la página.
 *
 * Y lo que la idea no decía, resuelto igual porque el chat sí lo tiene:
 *
 * - **Un hilo puede seguir**: se contesta a un comentario, y a ese otro. No hay nombre para el
 *   cuerpo que gira alrededor de una luna, pero la regla es la misma a cualquier hondura: lo que
 *   contesta a algo vive **dentro** de ese algo. Así que se aplica hasta el fondo ([HONDURA_MAXIMA]
 *   para que un hilo que se responde a sí mismo no dé vueltas para siempre).
 * - **Los comentarios sueltos** —texto sin archivo— también son cuerpos, pero nacen pequeños: en
 *   un proyecto de verdad la conversación es casi todo, y a tamaño de sol no dejaría ver nada.
 * - **Lo que se añadió a mano no se toca**: esto solo pone lo que falta. Un cuerpo movido de
 *   sitio se queda donde el usuario lo dejó, aunque se vuelva a armar.
 * - **Lo que se quitó no vuelve a aparecer solo** ([quitados]): si no, quitar algo del universo
 *   sería inútil mientras siga en el chat.
 */
fun Universos.desdeElChat(
    proyecto: String,
    nodos: List<NodoDelChat>,
    ahora: Long,
    /** Los `ref` que el usuario quitó a mano: no se reponen. */
    quitados: Set<String> = emptySet()
): Universos {
    val porPadre = nodos.groupBy { it.respondeA }
    var u = this
    var reloj = ahora

    fun poner(enEspacio: String, nodo: NodoDelChat, hondura: Int) {
        if (hondura > HONDURA_MAXIMA) return
        if (nodo.id in quitados) return
        val espacio = u.espacio(enEspacio)
        val hijos = porPadre[nodo.id].orEmpty()
        // Si ya está puesto se respeta **tal cual**: su sitio, su tamaño y su color son del usuario.
        val existente = espacio.cuerpos.firstOrNull { it.clase == Cuerpo.MENSAJE && it.ref == nodo.id }
        val cuerpo = existente ?: Cuerpo(
            id = "c:${reloj++}",
            clase = Cuerpo.MENSAJE,
            texto = nodo.nombre,
            ref = nodo.id,
            ruta = nodo.ruta,
            // **Un comentario nace pequeño; un archivo, entero.** Lo que lleva algo dentro se ve
            // un pelo más grande, que es lo que invita a entrar.
            tamano = when {
                nodo.esTexto -> 0.6f
                hijos.isNotEmpty() || nodo.esDocumento -> 1.2f
                else -> 1f
            }
        ).let { nuevo ->
            val sitio = espacio.sitioLibre()
            nuevo.copy(x = sitio.x, y = sitio.y)
        }
        if (existente == null) u = u.con(u.espacio(enEspacio).conCuerpo(cuerpo))
        if (hijos.isEmpty()) return
        // Lo que le contesta vive dentro de él: hace falta su espacio.
        val (conEspacio, dentro) = u.conSubespacio(enEspacio, cuerpo.id, reloj++)
        u = conEspacio
        val id = dentro ?: return
        for (h in hijos.sortedBy { it.pagina ?: Int.MAX_VALUE }) poner(id, h, hondura + 1)
    }

    val raiz = Universos.deProyecto(proyecto)
    if (u.espacios[raiz] == null) u = u.con(Espacio(id = raiz, nombre = ""))
    for (n in porPadre[null].orEmpty()) poner(raiz, n, 0)
    return u
}

/** Hasta dónde se sigue un hilo hacia dentro. Ver [desdeElChat]. */
const val HONDURA_MAXIMA = 12
