package com.forge.pixpin.guardados

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * **El orden de las hojas de un cuaderno al arrastrarlas** (27-oct-2026, el usuario: «tengo que
 * tocar una y otra vez para mover una hoja; en pantalla táctil no es útil. Que esas flechas del
 * lateral sean un asa: mantengo, jalo arriba y la hoja sube, jalo abajo y baja»).
 *
 * Lo puro (sin Android), para probarlo en la JVM: mientras se arrastra solo cambia el orden que se
 * ve ([mover] y [segun]); al soltar se escribe **una sola vez** con [CarpetaDePaginas.mover], la misma
 * función que usaban las flechas, así que el documento que viaja (claves, orden de claves, formato)
 * es byte a byte el que habrían dejado tantos toques de «Antes» o «Después».
 */
object OrdenDeHojas {

    /**
     * **Lleva el elemento [desde] al puesto [hasta]** (los dos desde 0; un [hasta] de más cae al
     * final). Fuera de rango o sin moverse, la lista tal cual.
     */
    fun <T> mover(lista: List<T>, desde: Int, hasta: Int): List<T> {
        if (desde !in lista.indices) return lista
        val destino = hasta.coerceIn(0, lista.lastIndex)
        if (desde == destino) return lista
        val l = lista.toMutableList()
        l.add(destino, l.removeAt(desde))
        return l
    }

    /**
     * **La lista en el [orden] que se está arrastrando** (las claves que da [clave]). Si las hojas ya
     * no son las mismas (la sincronización trajo o quitó alguna a medio arrastre), manda la lista
     * que llega: mejor perder el arrastre que enseñar una hoja que ya no está.
     */
    fun <T, K> segun(lista: List<T>, orden: List<K>?, clave: (T) -> K): List<T> {
        if (orden == null || orden.size != lista.size) return lista
        val porClave = lista.associateBy(clave)
        if (porClave.size != lista.size || orden.toSet() != porClave.keys) return lista
        return orden.map { porClave.getValue(it) }
    }

    /**
     * **Lo que se escribe al soltar**: la hoja [id] al puesto [hasta] del cuaderno. Null si no se
     * movió (o ya no está), como [CarpetaDePaginas.mover], y entonces no se escribe nada.
     */
    fun soltar(c: CarpetaDePaginas, id: String, hasta: Int): CarpetaDePaginas? =
        c.posicion(id).takeIf { it >= 0 }?.let { c.mover(it, hasta) }
}

/**
 * **El arrastre de una fila de una [androidx.compose.foundation.lazy.LazyColumn]** (27-oct-2026):
 * la fila sigue al dedo ([desplazamiento], para su `translationY`) y, cuando su centro pasa por
 * encima de otra, se cambian de sitio con [alMover] (el orden que se ve, no el documento). Cerca de
 * los bordes la lista se desplaza sola ([sobrante]), para llevar una hoja de la 2 a la 30.
 *
 * Es el patrón del ejemplo de Compose (`LazyColumnDragAndDropDemo`), pero la fila se sigue por su
 * clave: tras un cambio, hasta que la lista se vuelve a colocar, la fila aún dice su puesto viejo,
 * y por eso [recolocar] espera (si no, la misma fila se cambiaría dos veces y volvería atrás).
 */
class ArrastreDeLista(
    private val estado: LazyListState,
    private val alcance: CoroutineScope,
    private val alMover: (desde: Int, hasta: Int) -> Unit
) {
    /** La clave de la fila que se arrastra, o null. */
    var clave by mutableStateOf<Any?>(null)
        private set

    /** El puesto que le toca a la fila arrastrada (el de después del último cambio). */
    private var indice = -1
    /** Dónde empezó la fila, en la lista, y lo que lleva movido el dedo. */
    private var inicial = 0
    private var arrastrado by mutableFloatStateOf(0f)

    private fun fila(): LazyListItemInfo? =
        clave?.let { k -> estado.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k } }

    /** Cuánto se aparta la fila arrastrada de su sitio en la lista: lo que se pinta debajo del dedo. */
    val desplazamiento: Float
        get() = fila()?.let { inicial + arrastrado - it.offset } ?: 0f

    /** Empieza a arrastrar la fila [k]. False si no está a la vista (no debería pasar: se toca su asa). */
    fun empezar(k: Any): Boolean {
        val f = estado.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k } ?: return false
        clave = k
        indice = f.index
        inicial = f.offset
        arrastrado = 0f
        return true
    }

    /** El dedo se movió [dy]. True si la fila cambió de puesto (para la vibración). */
    fun arrastrar(dy: Float): Boolean {
        if (clave == null) return false
        arrastrado += dy
        return recolocar()
    }

    /**
     * **Cambia de sitio la fila si su centro está sobre otra.** Se llama al mover el dedo y al
     * desplazarse sola la lista. True si hubo cambio.
     */
    fun recolocar(): Boolean {
        val f = fila() ?: return false
        // La lista aún no se ha vuelto a colocar tras el último cambio.
        if (f.index != indice) return false
        val arriba = f.offset + desplazamiento
        val centro = (arriba + f.size / 2f).toInt()
        val otra = estado.layoutInfo.visibleItemsInfo.firstOrNull { it.index != f.index && centro in it.offset..(it.offset + it.size) }
            ?: return false
        // Si se mueve la primera que se ve, la lista se quedaría pegada a su clave y saltaría: se fija donde está.
        if (f.index == estado.firstVisibleItemIndex || otra.index == estado.firstVisibleItemIndex) {
            val primera = estado.firstVisibleItemIndex
            val corrido = estado.firstVisibleItemScrollOffset
            alcance.launch { estado.scrollToItem(primera, corrido) }
        }
        alMover(f.index, otra.index)
        indice = otra.index
        return true
    }

    /**
     * **Cuánto desplazar la lista en este fotograma**: negativo hacia arriba, positivo hacia abajo,
     * más deprisa cuanto más se mete la fila en la franja del borde ([franja] px, hasta [maximo] px).
     * Solo hacia donde va el dedo, para que coger una hoja pegada al borde no mueva la lista.
     */
    fun sobrante(franja: Float, maximo: Float): Float {
        val f = fila() ?: return 0f
        val arriba = f.offset + desplazamiento
        val abajo = arriba + f.size
        val info = estado.layoutInfo
        return when {
            arrastrado < 0 && arriba - info.viewportStartOffset < franja ->
                -maximo * ((franja - (arriba - info.viewportStartOffset)) / franja).coerceIn(0f, 1f)
            arrastrado > 0 && info.viewportEndOffset - abajo < franja ->
                maximo * ((franja - (info.viewportEndOffset - abajo)) / franja).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    /** Se soltó (o se canceló): la fila vuelve a su sitio, que ya es el nuevo. */
    fun terminar() {
        clave = null
        indice = -1
        arrastrado = 0f
    }
}
