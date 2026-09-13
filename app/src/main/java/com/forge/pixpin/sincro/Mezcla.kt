package com.forge.pixpin.sincro

import com.forge.pixpin.motor.Proyecto

/**
 * **Juntar dos versiones de un proyecto sin preguntar.**
 *
 * Un proyecto es un índice: qué hojas tiene, en qué orden, cómo se llama. Si en el teléfono se
 * añade una hoja y en la tableta otra, lo que uno espera es **tener las dos**, no elegir entre
 * dos listas. Preguntar aquí sería preguntar cada vez que los dos aparatos trabajaron en el mismo
 * proyecto, que es justo lo normal.
 *
 * Se hace con lo acordado la última vez ([base]), como con los mensajes: una hoja que estaba y
 * falta en un lado **se quitó allí**; una que no estaba y aparece **se añadió**. Sin base (la
 * primera vez) no hay forma de saber qué se quitó, así que se juntan todas: perder una hoja es
 * peor que tener una de más.
 *
 * Lo que sí puede chocar —la misma hoja cambiada en los dos lados, el nombre cambiado en los dos—
 * lo gana el proyecto tocado más tarde. Es un dato menor al lado del contenido: lo que se dibuja o
 * se escribe va en los archivos y en los mensajes, y eso sí se pregunta.
 */
object Mezcla {

    fun proyecto(mio: Proyecto?, suyo: Proyecto?, base: Proyecto?): Proyecto? {
        if (mio == null) return suyo
        if (suyo == null) return mio
        if (mio == suyo) return mio
        val ganaElMio = mio.tocado >= suyo.tocado
        fun <T> campo(m: T, s: T, b: T?): T = when {
            base == null -> if (ganaElMio) m else s
            m == b -> s
            s == b -> m
            else -> if (ganaElMio) m else s
        }
        return Proyecto(
            id = mio.id,
            nombre = campo(mio.nombre, suyo.nombre, base?.nombre),
            hojas = lista(mio.hojas, suyo.hojas, base?.hojas, { it.id }, ganaElMio),
            archivado = campo(mio.archivado, suyo.archivado, base?.archivado),
            tocado = maxOf(mio.tocado, suyo.tocado),
            pdfOrigen = campo(mio.pdfOrigen, suyo.pdfOrigen, base?.pdfOrigen) ?: mio.pdfOrigen ?: suyo.pdfOrigen,
            pdfLimpio = campo(mio.pdfLimpio, suyo.pdfLimpio, base?.pdfLimpio) ?: mio.pdfLimpio ?: suyo.pdfLimpio,
            croquis = lista(mio.croquis, suyo.croquis, base?.croquis, { it }, ganaElMio),
            origen = campo(mio.origen, suyo.origen, base?.origen)
        )
    }

    /**
     * Una lista juntada a tres bandas, **en el orden del mío** con lo nuevo del otro metido detrás
     * de la que tenía delante en su lista: así una hoja añadida en medio en la tableta cae en
     * medio también aquí, y no al final.
     */
    fun <T> lista(mio: List<T>, suyo: List<T>, base: List<T>?, clave: (T) -> String, ganaElMio: Boolean): List<T> {
        val enMio = mio.associateBy(clave)
        val enSuyo = suyo.associateBy(clave)
        val enBase = base?.associateBy(clave) ?: emptyMap()
        fun sigue(k: String): Boolean {
            val m = k in enMio
            val s = k in enSuyo
            if (base == null) return m || s
            val b = k in enBase
            return when {
                m && s -> true
                m -> !b   // solo en el mío: si estaba en la base, el otro lo quitó
                s -> !b   // solo en el suyo: ídem al revés
                else -> false
            }
        }
        fun version(k: String): T {
            val m = enMio[k]
            val s = enSuyo[k]
            if (m == null) return s!!
            if (s == null) return m
            val b = enBase[k]
            return when {
                m == s -> m
                b != null && m == b -> s
                b != null && s == b -> m
                else -> if (ganaElMio) m else s
            }
        }
        val salida = ArrayList<String>()
        for (x in mio) { val k = clave(x); if (sigue(k)) salida += k }
        // Lo del otro que falta, detrás de su vecino de delante.
        var anterior: String? = null
        for (x in suyo) {
            val k = clave(x)
            if (k !in enMio && sigue(k) && k !in salida) {
                var donde = if (anterior == null) 0 else salida.indexOf(anterior) + 1
                // Detrás también de lo que yo añadí justo ahí: si los dos añadieron al final, va
                // primero lo mío y luego lo suyo, no intercalado.
                while (donde < salida.size && salida[donde] !in enSuyo && salida[donde] !in enBase) donde++
                salida.add(donde.coerceIn(0, salida.size), k)
            }
            if (k in salida) anterior = k
        }
        return salida.map(::version)
    }
}
