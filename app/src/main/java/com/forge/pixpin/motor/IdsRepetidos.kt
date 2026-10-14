package com.forge.pixpin.motor

/**
 * **Dos figuras con el mismo id** (13-oct-2026): se arreglan solas al leer y al guardar.
 *
 * El PC nombraba lo nuevo `pc<n>` contando desde el número de elementos leídos, y si el móvil había
 * quitado uno (lo borrado no se guarda), el siguiente trazo del PC salía con el id del último que
 * nació allí. Hay dibujos del usuario así (`anot-34FJGS8TT2-p3`, `-p4`, `FM5RQ7G7DE`,
 * `Z2C3WQFTHJ`). Con dos figuras de igual id todo lo que va **por id** se lía: la fusión no puede
 * juntar figura por figura (`Fusion.conIds`) y se queda con una lista entera u otra; el deshacer
 * ([applyDelta]) junta las dos en una; tocar una marca las dos. El PC ya no los crea; esto arregla
 * los que ya hay.
 *
 * **La regla**: de cada grupo de repetidos conserva el id el de **versión mayor** (a igualdad, el
 * que va antes en la lista, que suele ser el viejo, el que el otro aparato ya conocía); los demás
 * reciben `<id>-<n>`, con `n` su puesto dentro del grupo. **No es azar a propósito**: el mismo
 * dibujo arreglado aquí al abrirlo y arreglado en la fusión ([Fusion]) sobre el archivo del otro
 * aparato tiene que dar **el mismo id**, o la figura arreglada saldría dos veces al juntar.
 *
 * Sin Android: lo usan [ExcalidrawStore] y la fusión de `sincro`.
 */
object IdsRepetidos {

    /**
     * El id que le toca a cada posición, o null si no hay ninguno repetido (lo normal: entonces no
     * se reserva nada). [versiones] va en paralelo a [ids].
     */
    fun idsSinRepetir(ids: List<String>, versiones: List<Long>): List<String>? {
        val vistos = HashSet<String>(ids.size * 2)
        var hayRepetidos = false
        for (id in ids) if (!vistos.add(id)) { hayRepetidos = true; break }
        if (!hayRepetidos) return null

        val salida = ids.toMutableList()
        val usados = ids.toHashSet()
        // Los grupos, en el orden en que aparece su primer elemento: así el resultado no depende
        // de cómo recorra un mapa.
        val grupos = LinkedHashMap<String, MutableList<Int>>()
        for ((i, id) in ids.withIndex()) grupos.getOrPut(id) { ArrayList(2) }.add(i)
        for ((id, sitios) in grupos) {
            if (sitios.size < 2) continue
            // El de versión mayor se queda el id; a igualdad, el primero.
            var queda = sitios[0]
            for (i in sitios) if (versiones[i] > versiones[queda]) queda = i
            for ((puesto, i) in sitios.withIndex()) {
                if (i == queda) continue
                var n = puesto + 1
                var nuevo = "$id-$n"
                while (nuevo in usados) nuevo = "$id-${++n}"
                usados += nuevo
                salida[i] = nuevo
            }
        }
        return salida
    }

    /**
     * [elementos] con un id distinto cada uno. Al que cambia de id se le sube la `version` y se le
     * pone [ahora] en `updated`, como a cualquier cosa que se edita: así el arreglo gana a la copia
     * vieja del otro aparato y viaja. Sin repetidos devuelve **la misma lista**, sin tocar nada.
     */
    fun arreglar(elementos: List<Element>, ahora: Long = System.currentTimeMillis()): List<Element> {
        val nuevos = idsSinRepetir(elementos.map { it.id }, elementos.map { it.version.toLong() })
            ?: return elementos
        return elementos.mapIndexed { i, e ->
            if (nuevos[i] == e.id) e else e.copy(id = nuevos[i], version = e.version + 1, updated = ahora)
        }
    }

    /** [scene] con ids únicos; la misma escena si ya los tenía. */
    fun arreglar(scene: Scene, ahora: Long = System.currentTimeMillis()): Scene {
        val elementos = arreglar(scene.elements, ahora)
        return if (elementos === scene.elements) scene else scene.copy(elements = elementos)
    }
}
