package com.forge.pixpin.mini

/**
 * La mini-app de **lista de tareas**, entera y sin Android.
 *
 * ## No es un formato nuevo: son casillas de Markdown
 *
 * El documento es exactamente esto:
 *
 * ```
 * # La compra
 *
 * - [x] pan
 * - [ ] leche
 * ```
 *
 * Que es lo que `motormd` ya entiende como [MarkdownBlock.Tarea] y ya pinta con su
 * casilla. Aquí no se inventa nada: se lee y se escribe **la misma sintaxis**, para que la
 * lista se pueda abrir con el editor de notas, copiar a cualquier otro programa o leer a
 * pelo dentro del JSONL. Ver el apartado de formato en `MiniApps.kt`.
 *
 * El parser de aquí es propio y aun así **no duplica** al de `motormd`, porque no hace lo
 * mismo: aquel devuelve el texto ya interpretado —negritas resueltas, marcas quitadas— y
 * una lista de tareas necesita **el texto crudo**, con sus asteriscos, para poder volver a
 * escribirlo igual que estaba. Que las dos lecturas coincidan no se deja a la fe: hay una
 * prueba que escribe una lista con esto y la vuelve a leer con `Markdown.parse`, y salta
 * en cuanto una de las dos sintaxis se mueva.
 *
 * ## Todo devuelve una lista nueva
 *
 * Ninguna operación toca la que recibe. Es lo que espera Compose —un estado que se
 * sustituye, no que se muta por dentro— y lo que hace que deshacer sea guardar la lista de
 * antes en vez de reconstruirla.
 */

/** Una línea de la lista: lo que hay que hacer, y si ya está. */
data class Tarea(val texto: String, val hecha: Boolean = false)

object Tareas {

    /**
     * Una casilla de GitHub, la misma que reconoce `Markdown.TAREA`.
     *
     * Se admiten los tres marcadores de lista (`-`, `*`, `+`) al leer aunque solo se
     * escriba con `-`: es lo que hace cualquier lector de Markdown, y una lista pegada de
     * fuera que use asteriscos no tiene por qué perderse.
     *
     * El espacio tras el corchete es opcional (`\s?`) para que `- [ ]` a secas —una tarea
     * en blanco, que es lo que deja pulsar intro— siga siendo una tarea y no un párrafo.
     */
    private val CASILLA = Regex("""^\s*[-*+]\s+\[([ xX])]\s?(.*)$""")

    /** Marcadores de lista al principio, para quitarlos de un texto pegado. Ver [saneado]. */
    private val MARCA = Regex("""^\s*(?:[-*+]\s+(?:\[[ xX]]\s?)?|\d+[.)]\s+)""")

    // ---- Leer y escribir -------------------------------------------------

    /**
     * Las tareas de un documento.
     *
     * Lo que no sea una casilla **se ignora sin quejarse**: un título, un renglón en
     * blanco, un párrafo que alguien dejó al editar la nota a mano. Ignorar es mejor que
     * fallar porque una línea rara no puede hacer desaparecer una lista de la compra
     * entera; y es mejor que convertirla en tarea porque entonces el título de la lista
     * saldría como la primera cosa que hacer.
     */
    fun leer(documento: String): List<Tarea> =
        documento.split('\n').mapNotNull { linea ->
            CASILLA.find(linea)?.let { m ->
                Tarea(
                    texto = m.groupValues[2].trim(),
                    hecha = m.groupValues[1].lowercase() == "x"
                )
            }
        }

    /**
     * El documento entero: su título y sus casillas.
     *
     * Escribe **solo tareas**. Lo que hubiera de más en el documento anterior no se
     * conserva, y es a propósito: esta pantalla enseña casillas y nada más, así que
     * guardar un párrafo invisible sería guardar algo que el usuario no puede ver ni
     * borrar. Quien quiera un documento mixto tiene el editor de notas, que es la
     * herramienta para eso.
     */
    fun escribir(titulo: String, tareas: List<Tarea>): String {
        val cuerpo = tareas.joinToString("\n") { t ->
            "- [${if (t.hecha) "x" else " "}] ${enUnaLinea(t.texto)}".trimEnd()
        }
        return Cabecera.linea(titulo) + cuerpo
    }

    // ---- Operaciones -----------------------------------------------------

    /**
     * Añade una tarea al final.
     *
     * Lo vacío no entra: una tarea sin texto es una casilla que no dice qué hay que hacer,
     * y se cuela sola cada vez que uno toca «añadir» sin escribir nada. Se devolvería una
     * lista con una fila fantasma que además cuenta en el «3 de 7» de la burbuja.
     */
    /**
     * Añade una tarea **con su fecha de creación** al final del texto (`pan ➕ 2026-10-02`), la
     * marca del plugin Tasks de Obsidian que acordó el PC (2-oct-2026): va dentro del texto de la
     * casilla porque es lo único que sobrevive a [escribir], también en versiones viejas. Si lo
     * pegado ya traía su fecha, se respeta la suya. Fecha local, sin hora.
     */
    fun anadir(tareas: List<Tarea>, texto: String, hecha: Boolean = false, hoy: java.time.LocalDate = java.time.LocalDate.now()): List<Tarea> {
        val limpio = saneado(texto)
        if (limpio.isEmpty()) return tareas
        val conFecha = if (partir(limpio).second != null) limpio else "$limpio ➕ $hoy"
        return tareas + Tarea(conFecha, hecha)
    }

    /** `➕ AAAA-MM-DD` como última cosa del texto, con un blanco (o nada) delante. */
    private val CREADA = Regex("""(?:^|\s)➕\s*(\d{4}-\d{2}-\d{2})\s*$""")

    /**
     * **El texto que se enseña y la fecha de creación**, si la lleva. Una fecha que no existe
     * (`2026-02-30`) se queda como texto, igual que en el PC (`mini::partir`).
     */
    fun partir(texto: String): Pair<String, java.time.LocalDate?> {
        val m = CREADA.find(texto) ?: return texto.trim() to null
        val fecha = runCatching { java.time.LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return texto.trim() to null
        return texto.substring(0, m.range.first).trim() to fecha
    }

    /** Días desde que se creó, por calendario y nunca negativos (un reloj adelantado da «hoy»). */
    fun diasDesde(fecha: java.time.LocalDate, hoy: java.time.LocalDate = java.time.LocalDate.now()): Long =
        java.time.temporal.ChronoUnit.DAYS.between(fecha, hoy).coerceAtLeast(0)

    /** Cambia el estado de una. Fuera de rango se queda como estaba. */
    fun marcar(tareas: List<Tarea>, indice: Int, hecha: Boolean): List<Tarea> {
        if (indice !in tareas.indices) return tareas
        if (tareas[indice].hecha == hecha) return tareas
        return tareas.toMutableList().also { it[indice] = it[indice].copy(hecha = hecha) }
    }

    /** Marcar o desmarcar con el mismo gesto, que es como se usa una casilla. */
    fun alternar(tareas: List<Tarea>, indice: Int): List<Tarea> =
        if (indice !in tareas.indices) tareas else marcar(tareas, indice, !tareas[indice].hecha)

    /** Cambia el texto de una, ya saneado. Si queda vacío no se toca: para eso está [borrar]. */
    /** Corrige el texto **conservando la fecha** que tenía (como `mini::renombrar` del PC). */
    fun renombrar(tareas: List<Tarea>, indice: Int, texto: String): List<Tarea> {
        if (indice !in tareas.indices) return tareas
        val limpio = saneado(texto)
        if (partir(limpio).first.isEmpty()) return tareas
        val fecha = partir(tareas[indice].texto).second
        val nuevo = if (fecha != null && partir(limpio).second == null) "$limpio ➕ $fecha" else limpio
        return tareas.toMutableList().also { it[indice] = it[indice].copy(texto = nuevo) }
    }

    fun borrar(tareas: List<Tarea>, indice: Int): List<Tarea> {
        if (indice !in tareas.indices) return tareas
        return tareas.filterIndexed { i, _ -> i != indice }
    }

    /**
     * Mueve una tarea de sitio.
     *
     * [hasta] se recorta al rango en vez de rechazarse: arrastrando con el dedo uno se pasa
     * del final constantemente, y ahí lo que se quiere decir es «al final», no «no hagas
     * nada». Cancelar el gesto por pasarse un píxel es lo que hace que reordenar se sienta
     * roto.
     */
    fun mover(tareas: List<Tarea>, desde: Int, hasta: Int): List<Tarea> {
        if (desde !in tareas.indices) return tareas
        val destino = hasta.coerceIn(0, tareas.size - 1)
        if (destino == desde) return tareas
        return tareas.toMutableList().also { it.add(destino, it.removeAt(desde)) }
    }

    /**
     * Quita las que ya están hechas.
     *
     * Es el «limpiar» de cualquier lista: cuando la compra está terminada, lo que queda son
     * las tres cosas que no había en la tienda, y esas son la lista de mañana.
     */
    fun sinLasHechas(tareas: List<Tarea>): List<Tarea> = tareas.filter { !it.hecha }

    // ---- El resumen de la burbuja ---------------------------------------

    /**
     * «3 de 7», sin abrir la lista.
     *
     * Es la razón de que una lista de tareas sea una mini-app y no una nota. Se cuenta
     * sobre el documento guardado —no sobre un contador aparte— porque un contador
     * guardado se desincroniza en cuanto alguien edita el texto por otro camino, y
     * entonces la burbuja miente, que es peor que no decir nada.
     */
    fun resumen(documento: String): ResumenMini = resumenDe(leer(documento))

    fun resumenDe(tareas: List<Tarea>): ResumenMini {
        val total = tareas.size
        val hechas = tareas.count { it.hecha }
        return ResumenMini(
            texto = "$hechas de $total",
            hechas = hechas,
            de = total,
            // Sin tareas no hay avance: 0/0 no es «nada hecho», es «nada que hacer», y una
            // barra vacía diría que queda todo por delante cuando no hay nada delante.
            avance = if (total == 0) null else hechas.toFloat() / total,
            vacia = total == 0
        )
    }

    // ---- Limpieza de lo que se escribe ----------------------------------

    /**
     * El texto de una tarea, listo para guardarse.
     *
     * Hace dos cosas, y las dos son contra el mismo accidente —pegar texto de otro sitio—:
     *
     * 1. Lo deja en **una línea** (ver [enUnaLinea]). Un párrafo pegado partiría el
     *    documento y las líneas de después dejarían de ser tareas.
     * 2. Le quita **el marcador de lista de delante**, y las veces que haga falta. Pegar
     *    «- [x] comprar pan» copiado de otra lista guardaría una tarea llamada
     *    «- [x] comprar pan» que, al releer el documento, se leería como una tarea
     *    **ya hecha** llamada «comprar pan». O sea: una tarea que se tacha sola. Quitando
     *    la marca, lo que se ve escrito es lo que se guarda.
     *
     * El bucle tiene tope porque el texto viene de fuera: «- - - - x» repetido cien mil
     * veces no puede dejar la aplicación pensando mientras alguien espera.
     */
    fun saneado(texto: String): String {
        var t = enUnaLinea(texto)
        var vueltas = 0
        while (vueltas < MAXIMO_DE_MARCAS) {
            val m = MARCA.find(t) ?: break
            if (m.value.isEmpty()) break
            t = t.removeRange(0, m.value.length)
            vueltas++
        }
        return t.trim()
    }

    /** Cuántos marcadores encadenados se quitan antes de dejarlo estar. Ver [saneado]. */
    private const val MAXIMO_DE_MARCAS = 8
}
