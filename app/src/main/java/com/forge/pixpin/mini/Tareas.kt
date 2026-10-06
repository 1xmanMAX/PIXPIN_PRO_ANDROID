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

    /** `pan ➕ 2026-10-02`, o la marca sola si no hay texto (`mini::con_fecha` del PC). */
    private fun conFecha(visible: String, creada: java.time.LocalDate): String {
        val v = visible.trim()
        return if (v.isEmpty()) "➕ $creada" else "$v ➕ $creada"
    }

    // ---- Las imágenes de una tarea (3-oct-2026) --------------------------
    //
    // Ver `docs/investigacion/2026-10-03-tareas-con-imagenes-android.md` del PC. Como la fecha,
    // van **dentro del texto de la casilla**, como imágenes de Markdown y siempre **delante** de
    // la marca de la fecha, para que [partir] la siga encontrando al final:
    //
    //     - [ ] comprar yeso ![img 01](pixpin:files/guardados/pc/general/archivos/tarea-1759500000000-01.png) ➕ 2026-10-03
    //
    // El enlace es la ruta de siempre: aquí, absoluta (`Disco.aPortatil` la vuelve
    // `pixpin:files/…` al salir y `aLocal` absoluta al entrar), y la sincronización ya viaja con
    // cualquier fichero que el texto de un mensaje nombre así. Todo esto es copia exacta de
    // `mini::imagenes_de`, `con_imagenes`, `fichas_a_imagenes` y `cambiar_enlaces` del PC, con
    // las mismas pruebas.

    /** Lo que se lee de una imagen: `img 01`. Va en el `alt`; el lector no lo mira. */
    fun rotuloDeImagen(numero: Int): String = "img " + numero.toString().padStart(2, '0')

    /** La ficha que ocupa el sitio de la imagen [numero] mientras se escribe: `[img 01]`. */
    fun fichaDeImagen(numero: Int): String = "[${rotuloDeImagen(numero)}]"

    /** Los blancos que corta el PC (`es_blanco`): un enlace con uno de estos no es imagen. */
    private fun esBlanco(c: Char) = c == ' ' || c == '\t' || c == '\u000B' || c == '\u000C' || c == '\r' || c == '\n'

    /** Dónde está cada `![alt](enlace)`: el trozo entero `[inicio, fin)` y el enlace. */
    private class Trozo(val inicio: Int, val fin: Int, val iniEnlace: Int, val finEnlace: Int)

    /**
     * Cada `![alt](enlace)` del texto. El enlace no puede ir vacío ni llevar blancos (la
     * sincronización corta ahí) y el `alt` no puede llevar `]`. Lo demás es texto.
     */
    private fun trozosDeImagen(texto: String): List<Trozo> {
        val salida = ArrayList<Trozo>()
        var desde = 0
        while (true) {
            val inicio = texto.indexOf("![", desde)
            if (inicio < 0) break
            val trasAlt = inicio + 2
            val cierre = texto.indexOf(']', trasAlt)
            if (cierre < 0) break
            if (!texto.startsWith("](", cierre)) { desde = trasAlt; continue }
            val iniEnlace = cierre + 2
            val finEnlace = texto.indexOf(')', iniEnlace)
            if (finEnlace < 0) break
            if (finEnlace == iniEnlace || (iniEnlace until finEnlace).any { esBlanco(texto[it]) }) { desde = trasAlt; continue }
            salida += Trozo(inicio, finEnlace + 1, iniEnlace, finEnlace)
            desde = finEnlace + 1
        }
        return salida
    }

    /** `split_whitespace` + `join(" ")` del PC: los blancos (de cualquier tipo) se juntan en uno. */
    private fun juntarBlancos(s: String): String {
        val salida = StringBuilder(s.length)
        var hueco = false
        for (c in s) {
            if (c.isWhitespace()) { hueco = salida.isNotEmpty(); continue }
            if (hueco) salida.append(' ')
            hueco = false
            salida.append(c)
        }
        return salida.toString()
    }

    /**
     * El texto de una tarea (ya sin la fecha: [partir]) separado en **lo que se lee** y **los
     * enlaces de sus imágenes**, en orden. Los blancos que dejan las imágenes al irse se juntan
     * en uno: «yeso ![a](x) y arena» se lee «yeso y arena».
     */
    fun imagenes(visible: String): Pair<String, List<String>> {
        val trozos = trozosDeImagen(visible)
        if (trozos.isEmpty()) return visible.trim() to emptyList()
        val limpio = StringBuilder(visible.length)
        val enlaces = ArrayList<String>(trozos.size)
        var desde = 0
        for (t in trozos) {
            limpio.append(visible, desde, t.inicio).append(' ')
            enlaces += visible.substring(t.iniEnlace, t.finEnlace)
            desde = t.fin
        }
        limpio.append(visible, desde, visible.length)
        return juntarBlancos(limpio.toString()) to enlaces
    }

    /** Lo que se lee de una casilla: sin la fecha y sin las imágenes. Para la burbuja y el pin. */
    fun legible(textoDeLaCasilla: String): String = imagenes(partir(textoDeLaCasilla).first).first

    /** El texto con sus imágenes detrás, numeradas desde 1: lo contrario de [imagenes]. */
    fun conImagenes(texto: String, enlaces: List<String>): String {
        val s = StringBuilder(texto.trim())
        enlaces.forEachIndexed { n, e ->
            if (s.isNotEmpty()) s.append(' ')
            s.append("![").append(rotuloDeImagen(n + 1)).append("](").append(e).append(')')
        }
        return s.toString()
    }

    /**
     * Cambia las fichas `[img NN]` del texto por su imagen ya guardada (número, enlace). La
     * imagen cuya ficha no está va al final; una ficha sin imagen se queda como texto (alguien
     * lo escribió). La fecha, si el texto ya la traía, sigue al final, detrás de todo.
     */
    fun fichasAImagenes(texto: String, imagenes: List<Pair<Int, String>>): String {
        val (visible, fecha) = partir(texto)
        val s = StringBuilder(visible)
        for ((numero, enlace) in imagenes) {
            val ficha = fichaDeImagen(numero)
            val imagen = "![${rotuloDeImagen(numero)}]($enlace)"
            val i = s.indexOf(ficha)
            if (i >= 0) s.replace(i, i + ficha.length, imagen)
            else {
                if (s.isNotBlank()) s.append(' ')
                s.append(imagen)
            }
        }
        val limpio = s.toString().trim()
        return if (fecha != null) conFecha(limpio, fecha) else limpio
    }

    /** El texto con el enlace de cada imagen cambiado por lo que diga [f] (o igual, con null). */
    fun cambiarEnlaces(texto: String, f: (String) -> String?): String {
        val s = StringBuilder(texto.length)
        var desde = 0
        for (t in trozosDeImagen(texto)) {
            s.append(texto, desde, t.iniEnlace)
            val viejo = texto.substring(t.iniEnlace, t.finEnlace)
            s.append(f(viejo) ?: viejo)
            desde = t.finEnlace
        }
        s.append(texto, desde, texto.length)
        return s.toString()
    }

    // ---- Las fichas del campo de escribir (solo Android) -----------------

    /**
     * Para corregir una tarea: cada imagen del texto pasa a su ficha (`[img 01]`, `[img 02]`…
     * por orden) y se devuelve cuál es cuál. [fichasAImagenes] lo deshace.
     */
    fun aFichas(visible: String): Pair<String, List<Pair<Int, String>>> {
        val trozos = trozosDeImagen(visible)
        if (trozos.isEmpty()) return visible to emptyList()
        val s = StringBuilder(visible.length)
        val imagenes = ArrayList<Pair<Int, String>>(trozos.size)
        var desde = 0
        trozos.forEachIndexed { n, t ->
            s.append(visible, desde, t.inicio).append(fichaDeImagen(n + 1))
            imagenes += (n + 1) to visible.substring(t.iniEnlace, t.finEnlace)
            desde = t.fin
        }
        s.append(visible, desde, visible.length)
        return s.toString() to imagenes
    }

    /**
     * Mete la ficha de la imagen [numero] en el [cursor], separada por blancos de lo que tenga
     * alrededor, como `meter_imagen` del PC. Devuelve el texto y dónde queda el cursor.
     */
    fun meterFicha(texto: String, cursor: Int, numero: Int): Pair<String, Int> {
        val c = cursor.coerceIn(0, texto.length)
        val ficha = buildString {
            if (c > 0 && !texto[c - 1].isWhitespace()) append(' ')
            append(fichaDeImagen(numero)).append(' ')
        }
        return (texto.substring(0, c) + ficha + texto.substring(c)) to c + ficha.length
    }

    /** El número de la siguiente imagen del campo: uno más que la mayor (`meter_imagen`). */
    fun siguienteNumero(imagenes: List<Pair<Int, String>>): Int = (imagenes.maxOfOrNull { it.first } ?: 0) + 1

    /**
     * Las imágenes que van con la tarea: **las que aún tienen su ficha** en el texto, por orden
     * de aparición. Las demás se descartan (y quien llama borra sus copias nuevas).
     */
    fun conFicha(texto: String, imagenes: List<Pair<Int, String>>): List<Pair<Int, String>> =
        imagenes.mapNotNull { im -> texto.indexOf(fichaDeImagen(im.first)).takeIf { it >= 0 }?.let { it to im } }
            .sortedBy { it.first }.map { it.second }

    /**
     * **Una chapa se borra entera con un solo retroceso.** Si [despues] es [antes] con una sola
     * letra menos y esa letra caía dentro de la ficha de una de [numeros], devuelve [antes] sin
     * la ficha entera y dónde queda el cursor; si no, null (el cambio se queda como está).
     */
    fun sinFichaRota(antes: String, despues: String, numeros: Collection<Int>): Pair<String, Int>? {
        if (despues.length != antes.length - 1 || numeros.isEmpty()) return null
        var k = 0
        while (k < despues.length && antes[k] == despues[k]) k++
        if (antes.substring(k + 1) != despues.substring(k)) return null
        for (n in numeros) {
            val ficha = fichaDeImagen(n)
            var i = antes.indexOf(ficha)
            while (i >= 0) {
                if (k in i until i + ficha.length) return antes.removeRange(i, i + ficha.length) to i
                i = antes.indexOf(ficha, i + 1)
            }
        }
        return null
    }

    /** Dónde están las fichas de [numeros] en el texto, `[inicio, fin)`: para pintar sus chapas. */
    fun fichasEn(texto: String, numeros: Collection<Int>): List<IntRange> =
        numeros.mapNotNull { n ->
            val f = fichaDeImagen(n)
            texto.indexOf(f).takeIf { it >= 0 }?.let { it until it + f.length }
        }

    /**
     * El fichero de un enlace, en este aparato: la ruta absoluta tal cual, o la portátil
     * (`pixpin:files/…`) dentro de [filesDir]. Lo demás (un nombre suelto) no se sabe dónde
     * está: null, y la fila enseña el hueco.
     */
    fun rutaDeImagen(enlace: String, filesDir: String): String? = when {
        enlace.startsWith(PORTATIL) -> filesDir.trimEnd('/') + "/" + enlace.removePrefix(PORTATIL)
        enlace.startsWith("/") -> enlace
        else -> null
    }

    /** `tarea-<ms>-<nn>.<ext>`: sin blancos ni paréntesis, que cortarían el enlace. */
    fun nombreDeCopia(ms: Long, numero: Int, extension: String): String =
        "tarea-$ms-${numero.toString().padStart(2, '0')}.$extension"

    private const val PORTATIL = "pixpin:files/"

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
     * «Deshacer» de una tarea quitada con su aspa (4-oct-2026, como `tareas::reponer` del PC):
     * vuelve a su sitio con su fecha, su estado y sus imágenes; o al final, si la lista se acortó
     * entretanto (la sincronización, la burbuja del chat).
     */
    fun reponer(tareas: List<Tarea>, indice: Int, tarea: Tarea): List<Tarea> =
        tareas.toMutableList().also { it.add(indice.coerceIn(0, tareas.size), tarea) }

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
