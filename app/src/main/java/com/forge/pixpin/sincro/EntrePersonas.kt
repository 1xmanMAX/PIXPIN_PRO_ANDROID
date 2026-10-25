package com.forge.pixpin.sincro

/**
 * # Un chat compartido como una conversación de verdad (25-oct-2026, del PC `b89c058`/`e3f1d60`)
 *
 * El usuario: «cuando sea compartido, que el chat se vuelva como un chat real: mis burbujas a la
 * derecha y las de la otra persona al otro lado». Un chat normal sigue siendo un cuaderno propio
 * (todo a un lado); uno compartido es una conversación entre varias personas, como un grupo de
 * Telegram.
 *
 * Aquí va solo lo que se decide sin pantalla y se prueba en la JVM —es `pixpin-ui/conversacion.rs`
 * del PC, tal cual—: **de quién es cada mensaje** (por el código de aparato que lleva,
 * [com.forge.pixpin.guardados.Mensaje.aparato]), a qué lado va y qué número de autor le toca para
 * agrupar las tandas ([com.forge.pixpin.guardados.sitios]). El nombre va encima de la primera
 * burbuja de cada tanda de otra persona ([conNombre]).
 *
 * ## La regla de «mío»
 *
 * Es mío lo que escribió cualquiera de **mis** aparatos: este, los del grupo de sincronizar
 * (`identidad.json`: `yo` y `miembros`; mi tableta, mi PC) y los aparatos de la lista del chat cuyo
 * `id` es uno de esos (mi PC entra solo en mis chats compartidos al sincronizar el grupo; es mío
 * aunque esté en la lista con mi nombre). Lo que no lleva código, o lleva uno que nadie de la
 * lista reclama, **también es mío**: el chat era mi cuaderno antes de compartirlo. Solo es de otro
 * lo que lleva el código de un aparato de OTRA persona de la lista.
 *
 * **Antes** (13-oct-2026) solo se dejaba fuera el código de **este** aparato, así que lo que
 * escribía desde mi PC o mi tableta salía con mi propio nombre encima; el PC lo arregló igual.
 *
 * No cambia nada de lo que viaja: ni `compartidos.json`, ni `persona.json`, ni las firmas.
 */
sealed class Quien {
    /** De cualquiera de mis aparatos: a la derecha, con mi burbuja. */
    object Yo : Quien()

    /** De otra persona, por su nombre: a la izquierda, con su nombre encima. Sus aparatos que se llaman igual son ella. */
    data class Otro(override val nombre: String) : Quien()

    val esMio: Boolean get() = this is Yo

    /** El nombre que va encima de la tanda, si no es mío. */
    open val nombre: String? get() = null
}

/**
 * **Quién es quién en un chat compartido**: mis códigos de aparato y, de los demás, el nombre de la
 * persona de cada código. Es `Lados` del PC.
 */
data class LadosDelChat(private val mios: Set<String>, private val otros: Map<String, String>) {

    /** De quién es un mensaje con este código de aparato (ya corto, de cuatro signos; ver [EntrePersonas.aparatoCorto]). */
    fun quien(aparato: String?): Quien {
        val a = aparato?.trim()?.takeIf { it.isNotEmpty() } ?: return Quien.Yo
        if (a in mios) return Quien.Yo
        return otros[a]?.let { Quien.Otro(it) } ?: Quien.Yo
    }

    /** Cuántas personas distintas, aparte de mí, constan. */
    fun otrasPersonas(): Int = otros.values.toSet().size

    companion object {
        /**
         * [mios]: los códigos (`K7Q2`) de mis aparatos. [personas]: de cada aparato de la lista del
         * chat, su código y el nombre de su persona, por orden de entrada (si un código saliera dos
         * veces, gana el último); los que están también en [mios] se quedan como míos.
         */
        fun nuevo(mios: Iterable<String>, personas: Iterable<Pair<String, String>>): LadosDelChat {
            val m = mios.filter { it.isNotEmpty() }.toSet()
            val otros = LinkedHashMap<String, String>()
            for ((c, n) in personas) {
                if (c.isEmpty() || c in m) continue
                // Sin nombre se le llama por su código: algo hay que poner encima, y el código es lo
                // que lleva su chapa.
                otros[c] = n.trim().ifEmpty { c }
            }
            return LadosDelChat(m, otros)
        }
    }
}

/**
 * **Un chat compartido visto desde el chat** (`EnElChat` del PC): la chapa de la lista y de la
 * cabecera, y de quién es cada burbuja.
 */
data class EnElChat(
    /** El id del chat tal como viaja (el de `compartidos.json`): aquí, el del proyecto. */
    val chat: String,
    /** Las personas que están dentro, yo incluido, cada una una vez ([Compartidos.nombresDentro]). */
    val personas: Int,
    val lados: LadosDelChat
)

object EntrePersonas {

    /**
     * **De quién es cada cosa** en un chat compartido, sin disco: [mios] son los ids de todos mis
     * aparatos ([misAparatos]). Una persona de la lista cuyo aparato es mío es mía.
     */
    fun enElChat(c: ChatCompartido, mios: Set<String>): EnElChat {
        val codigosMios = mios.map { Aparato(it, "").codigo } +
            c.personas.filter { it.id in mios }.map { it.codigo }
        return EnElChat(
            chat = c.chat,
            personas = Compartidos.nombresDentro(c).size,
            lados = LadosDelChat.nuevo(codigosMios, c.personas.sortedBy { it.desde }.map { it.codigo to it.nombre })
        )
    }

    /** Los ids de este aparato y de todos los míos: este y los del grupo de sincronizar. */
    fun misAparatos(i: Identidad): Set<String> = i.miembros.mapTo(LinkedHashSet()) { it.id } + i.yo.id

    /**
     * **Los chats compartidos en los que estoy dentro**, por el id de su chat. [identidad] se pide
     * solo si la lista no está vacía (del PC `cad5e20`): leer la identidad la crea, y quien no ha
     * compartido nada no tiene por qué estrenarla por mirar la lista de chats.
     */
    fun porChat(lista: List<ChatCompartido>, identidad: () -> Identidad): Map<String, EnElChat> {
        if (lista.isEmpty()) return emptyMap()
        val i = identidad()
        val mios = misAparatos(i)
        return lista.filter { Compartidos.dentro(it, i.yo.id) }.associate { it.chat to enElChat(it, mios) }
    }

    /**
     * El código corto (`K7Q2`) del aparato donde nació un mensaje. Unos se guardaron con el id
     * entero del aparato: de ese sale el corto por el mismo camino que [Aparato.codigo] (el PC,
     * `aparato_corto`, corta en más de seis signos).
     */
    fun aparatoCorto(aparato: String?): String? {
        val a = aparato?.trim() ?: return null
        return if (a.codePointCount(0, a.length) > 6) Aparato(a, "").codigo else a
    }

    /**
     * El número de autor de cada mensaje, para agrupar: `0` lo mío, y a cada otra persona el suyo
     * por orden de aparición. Dos seguidos de la misma persona se agrupan; de dos distintas, no,
     * aunque los dos vayan a la izquierda.
     */
    fun numerar(quienes: List<Quien>): List<Int> {
        val vistos = HashMap<String, Int>()
        return quienes.map { q ->
            when (q) {
                Quien.Yo -> 0
                is Quien.Otro -> vistos.getOrPut(q.nombre) { vistos.size + 1 }
            }
        }
    }

    /** El autor de las respuestas del asistente en un chat compartido: a la izquierda y sin nombre (`u32::MAX` del PC). */
    const val AUTOR_ASISTENTE = Int.MAX_VALUE

    /**
     * Si un mensaje lleva el nombre de quien lo escribió encima: solo el **primero de cada tanda**
     * de otra persona ([abreTanda] = no está pegado al de arriba), como un grupo de Telegram. Lo
     * mío nunca.
     */
    fun conNombre(quien: Quien?, abreTanda: Boolean): Boolean = abreTanda && quien is Quien.Otro

    /** La inicial del círculo de una persona: la primera letra, en mayúscula. */
    fun inicial(nombre: String): String {
        val t = nombre.trim()
        if (t.isEmpty()) return ""
        return String(Character.toChars(t.codePointAt(0))).uppercase()
    }

    /**
     * Qué color de la paleta le toca a una persona (de [cuantos]): siempre el mismo para el mismo
     * nombre, en cualquier aparato. FNV-1a de 32 bits sobre los bytes UTF-8 del nombre sin espacios
     * a los lados, igual que el PC (`conversacion::tono`), para que Ana tenga el mismo color en
     * todos.
     */
    fun tono(nombre: String, cuantos: Int): Int {
        if (cuantos <= 0) return 0
        var h = 0x811c9dc5.toInt()
        for (b in nombre.trim().toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xff)
            h *= 0x01000193
        }
        return ((h.toLong() and 0xffffffffL) % cuantos).toInt()
    }

    /** Los siete colores de avatar de Telegram, los mismos que el PC (`COLORES_AVATAR`). */
    val COLORES = longArrayOf(0xFFE17076, 0xFFFAA774, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE)

    /** El color (ARGB) de una persona: su nombre encima de sus burbujas y su círculo. */
    fun colorDe(nombre: String): Long = COLORES[tono(nombre, COLORES.size)]

    /** El verde de la chapa de chat compartido (`VERDE_COMPARTIDO` del PC): ni el de lo mío ni ningún color de avatar. */
    const val VERDE_COMPARTIDO = 0xFF2E9E62

    /** «Compartido · 3 personas», como `cc-chapa-personas` del PC. */
    fun chapa(personas: Int): String =
        if (personas == 1) "Compartido · 1 persona" else "Compartido · $personas personas"
}
