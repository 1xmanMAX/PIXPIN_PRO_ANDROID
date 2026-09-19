package com.forge.pixpin.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * **Un lienzo abierto**: lo que hace falta para volver a él tal como estaba.
 *
 * Es la rueda de la multitarea de dentro de la aplicación (17-sep-2026): con cuatro dedos se
 * pasa de uno al siguiente sin salir a los proyectos. Ver [com.forge.pixpin.motor.Multilienzo].
 */
@Serializable
data class LienzoAbierto(
    /** El id del dibujo: es lo que lo identifica, también cuando es la página de un PDF. */
    val id: String,
    val nombre: String = "",
    val ruta: String? = null,
    val proyecto: String? = null,
    val pdf: String? = null,
    val pagina: Int = -1,
    /** Cuándo se abrió por última vez. */
    val visto: Long = 0L,
    /**
     * **Qué es** (19-sep-2026): la multitarea ya no es solo de lienzos. Un lienzo vive en la
     * tira, al lado de los otros; una tabla, un croquis 3D o un PDF entero tienen su propia
     * pantalla y en la tira van como una tarjeta que, al tocarla, lleva a ella. Ver [Abiertos].
     */
    val clase: String = Abiertos.LIENZO
) {
    val esLienzo get() = clase == Abiertos.LIENZO
}

/** **Un grupo de pestañas guardado**: los abiertos de un momento, con nombre, para volver de un toque. */
@Serializable
data class GrupoDeAbiertos(
    val id: String,
    val nombre: String,
    val lienzos: List<LienzoAbierto> = emptyList(),
    val guardado: Long = 0L
)

/**
 * Las cuentas de la rueda, **sin Android**, para poder comprobarlas. El orden es el que el
 * usuario deja en la vista de todos los lienzos; abrir uno nuevo lo pone al final.
 */
object Abiertos {
    /**
     * Cuántos pueden estar abiertos a la vez en la multitarea: **tres** (usuario, 19-sep-2026;
     * antes cinco, y antes doce). Con cuatro o cinco la tira distrae más de lo que ayuda. Abrir
     * más lienzos **por separado** —cada uno en su ventana de recientes— sigue como siempre.
     */
    const val CUANTOS = 3

    const val LIENZO = "lienzo"
    const val TABLA = "tabla"
    const val CROQUIS = "croquis3d"
    const val PDF = "pdf"

    /** Cuántos grupos se guardan. Pasados estos, el más viejo se va. */
    const val GRUPOS = 12

    /** Una tabla, como pestaña. El id lleva su clase delante: nunca choca con el de un dibujo. */
    fun deTabla(id: String, nombre: String, proyecto: String? = null) =
        LienzoAbierto(id = "$TABLA:$id", nombre = nombre, ruta = id, proyecto = proyecto, clase = TABLA)

    /** Un croquis 3D: [ruta] es el croquis y [LienzoAbierto.proyecto] el suyo. */
    fun deCroquis(croquis: String, nombre: String, proyecto: String? = null) =
        LienzoAbierto(id = "$CROQUIS:${proyecto.orEmpty()}|$croquis", nombre = nombre, ruta = croquis, proyecto = proyecto, clase = CROQUIS)

    /** Un PDF entero, en su lector (una página suelta para anotar sigue siendo un lienzo). */
    fun dePdf(ruta: String, nombre: String, proyecto: String? = null) =
        LienzoAbierto(id = "$PDF:$ruta", nombre = nombre, proyecto = proyecto, pdf = ruta, clase = PDF)

    /** Guarda [lista] como grupo: uno con el mismo nombre se sustituye; el nuevo va primero. */
    fun conGrupo(grupos: List<GrupoDeAbiertos>, nombre: String, lista: List<LienzoAbierto>, ahora: Long): List<GrupoDeAbiertos> {
        val limpio = nombre.trim().ifBlank { "Grupo " + (grupos.size + 1) }
        val nuevo = GrupoDeAbiertos("g-$ahora", limpio, lista, ahora)
        return (listOf(nuevo) + grupos.filterNot { it.nombre.equals(limpio, ignoreCase = true) }).take(GRUPOS)
    }

    /** Mete [uno] o pone al día el que tuviera su id, **sin cambiarlo de sitio**. */
    fun con(lista: List<LienzoAbierto>, uno: LienzoAbierto): List<LienzoAbierto> {
        val i = lista.indexOfFirst { it.id == uno.id }
        if (i >= 0) return lista.toMutableList().also { it[i] = uno }
        return (lista + uno).takeLast(CUANTOS)
    }

    fun sin(lista: List<LienzoAbierto>, id: String) = lista.filterNot { it.id == id }

    /** Lo arrastrado de un sitio a otro, en la vista de todos. */
    fun mover(lista: List<LienzoAbierto>, de: Int, a: Int): List<LienzoAbierto> {
        if (de !in lista.indices || a !in lista.indices || de == a) return lista
        val m = lista.toMutableList()
        m.add(a, m.removeAt(de))
        return m
    }

    /**
     * El de al lado: [paso] es −1 a la izquierda y +1 a la derecha.
     *
     * **Es una lista, no un carrusel** (lo pidió el usuario el 17-sep-2026): al llegar al final
     * no se sigue por el otro extremo, hay que volver. Null cuando no hay nadie por ese lado —y
     * ahí es donde se ofrece abrir otro lienzo.
     */
    fun alLado(lista: List<LienzoAbierto>, id: String, paso: Int): LienzoAbierto? {
        val i = lista.indexOfFirst { it.id == id }
        if (i < 0) return null
        return lista.getOrNull(i + paso)
    }

    /** Los que se asoman a los lados: el de antes y el de después, si los hay. */
    fun vecinos(lista: List<LienzoAbierto>, id: String): Pair<LienzoAbierto?, LienzoAbierto?> =
        alLado(lista, id, -1) to alLado(lista, id, 1)
}

/**
 * Los lienzos abiertos, guardados en disco.
 *
 * En su propio archivo y no en los ajustes: es una lista que cambia cada vez que se abre un
 * lienzo, y los ajustes se leen enteros en cada pantalla. Es de este aparato: no viaja.
 */
class LienzosAbiertos(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val archivo = File(context.filesDir, "lienzos_abiertos.json")

    private val _lista = MutableStateFlow(leer())
    val lista: StateFlow<List<LienzoAbierto>> = _lista.asStateFlow()

    private fun leer(): List<LienzoAbierto> = runCatching {
        if (!archivo.exists()) emptyList()
        else json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LienzoAbierto.serializer()), archivo.readText())
    }.getOrDefault(emptyList())

    private fun escribir(nueva: List<LienzoAbierto>) {
        _lista.value = nueva
        runCatching {
            val tmp = File(archivo.path + ".tmp")
            tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(LienzoAbierto.serializer()), nueva))
            tmp.renameTo(archivo)
        }
    }

    fun abrir(uno: LienzoAbierto) = escribir(Abiertos.con(_lista.value, uno.copy(visto = System.currentTimeMillis())))

    fun cerrar(id: String) = escribir(Abiertos.sin(_lista.value, id))

    fun mover(de: Int, a: Int) = escribir(Abiertos.mover(_lista.value, de, a))

    /**
     * **Salir cierra la multitarea** (19-sep-2026): al volver a entrar en un lienzo se empieza
     * de cero, con él solo. Lo que se quiera conservar se guarda antes como grupo. Pasar de una
     * pestaña a otra **no** es salir: eso lo hace el relevo, que no llama aquí.
     */
    fun cerrarTodos() = escribir(emptyList())

    fun recargar() {
        _lista.value = leer()
    }

    // ---- Los grupos de pestañas ----

    private val archivoDeGrupos = File(context.filesDir, "grupos_de_abiertos.json")
    private val serieDeGrupos = kotlinx.serialization.builtins.ListSerializer(GrupoDeAbiertos.serializer())
    private val _grupos = MutableStateFlow(
        runCatching {
            if (!archivoDeGrupos.exists()) emptyList() else json.decodeFromString(serieDeGrupos, archivoDeGrupos.readText())
        }.getOrDefault(emptyList())
    )
    val grupos: StateFlow<List<GrupoDeAbiertos>> = _grupos.asStateFlow()

    private fun escribirGrupos(nuevos: List<GrupoDeAbiertos>) {
        _grupos.value = nuevos
        runCatching {
            val tmp = File(archivoDeGrupos.path + ".tmp")
            tmp.writeText(json.encodeToString(serieDeGrupos, nuevos))
            tmp.renameTo(archivoDeGrupos)
        }
    }

    /** Guarda lo abierto ahora como grupo. */
    fun guardarGrupo(nombre: String) {
        if (_lista.value.isEmpty()) return
        escribirGrupos(Abiertos.conGrupo(_grupos.value, nombre, _lista.value, System.currentTimeMillis()))
    }

    fun borrarGrupo(id: String) = escribirGrupos(_grupos.value.filterNot { it.id == id })

    /** **Abrir un grupo es cambiar lo abierto por lo suyo**, en su orden. Devuelve el primero. */
    fun abrirGrupo(id: String): LienzoAbierto? {
        val g = _grupos.value.firstOrNull { it.id == id } ?: return null
        escribir(g.lienzos.take(Abiertos.CUANTOS))
        return g.lienzos.firstOrNull()
    }
}
