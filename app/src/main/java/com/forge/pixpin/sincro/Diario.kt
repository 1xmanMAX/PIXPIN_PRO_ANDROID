package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Proyecto
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **El diario de cambios de este aparato** (27-oct-2026).
 *
 * El usuario: «ahora inicia una comprobación paso a paso, cosa por cosa. Sería mejor guardar todos
 * los cambios que se hacen en el dispositivo en un solo archivo (qué se añadió, cambió o quitó y
 * cuándo) y que al sincronizar la app lea solo ese archivo». Hasta hoy cada vuelta, en **cada**
 * chat y en los dos aparatos, hacía la copia de seguridad, leía el chat entero, recorría sus
 * lienzos y volvía a resumir cada archivo pequeño; y otra vez al cerrar. Con veinte chats quietos
 * eso eran segundos de disco para no mover nada.
 *
 * Aquí se apunta, **una línea por cambio**, todo lo que se escribe en lo que viaja: mensajes
 * (alta, cambio, baja), proyectos, lápidas y archivos de las carpetas de PixPin, cada uno con su
 * **número de orden** ([Apunte.n], que solo crece) y su hora. Con eso «¿qué cambió en este chat
 * desde la última vez que quedó igual con el portátil?» es mirar un número, no el disco: ver
 * [Vista.cambio] y los cursores por aparato ([Cursores]), que usa [Sesion.preparar].
 *
 * **Por qué un JSONL y no una base SQLite**: [Disco] vive sin Android para que las pruebas
 * sincronicen dos carpetas de verdad en la JVM, y lo demás de la sincronización ya son JSONL
 * (`borrados.jsonl`, `chatsborrados.jsonl`); añadir una línea con una sola escritura es atómico a
 * efectos prácticos, se lee con los ojos y el PC lo puede leer igual (ver
 * `docs/sincronizacion-por-diario.md`). Lo que SQLite daría (transacciones) no hace falta: el
 * diario **nunca es la única verdad**.
 *
 * **Nunca es la única verdad, y esto es lo importante.** Si falta, está roto o no lo explica todo,
 * la vuelta hace lo de siempre (mirarlo todo), así que un fallo aquí cuesta tiempo, nunca datos:
 *
 * - **El barrido** ([barrer]): al empezar cada vuelta se mira el tamaño, la fecha (en
 *   nanosegundos) y el nodo de cada archivo de las carpetas que viajan —sin leerlos— y lo que
 *   cambió sin que nadie lo apuntara (un proceso aparte, un escritor que no avisa, la aplicación
 *   muerta entre escribir y apuntar) se apunta entonces. Los archivos escritos hace menos de
 *   [RECIENTE_MS] llevan además su CRC, como hace git con los «racily clean»: dos escrituras en el
 *   mismo tic del reloj del núcleo dejan la misma fecha.
 * - **Lo que no se entiende es «todo»**: una línea rota, el índice perdido, el chat
 *   (`guardados.jsonl`) cambiado sin apunte… se apunta [TODO] y la vuelta siguiente lo mira todo.
 * - **La verificación entera** cada [Cursores.VUELTAS_POR_VERIFICACION] vueltas o cada día con
 *   cada aparato (ver [Cursores.tocaVerificar]), que cura lo que se escapara.
 *
 * **Se compacta solo** ([compactar]): de cada cosa basta su último apunte para saber «¿cambió
 * desde N?», así que el archivo crece con lo distinto que se toca, no con cada guardado.
 *
 * Una instancia por carpeta y proceso ([de]); entre procesos (`:planos`, `:pdfsqueeze`) se
 * escribe con un cerrojo de archivo y cada uno relee lo que añadieron los otros.
 */
class Diario private constructor(val filesDir: File) {

    /**
     * Una línea del diario. Nombres de una letra porque hay miles: [n] el número de orden, [t] la
     * hora de este aparato (solo para leerla: el orden lo da [n], y así un reloj cambiado a mano no
     * estropea nada), [k] qué ([MENSAJE], [PROYECTO], [ARCHIVO], [LAPIDA] o [TODO]), [c] de qué chat
     * (nulo en un archivo: se busca en el [indice]), [o] cuál (código único, id del proyecto, ruta),
     * [a] [ALTA], [CAMBIO] o [BAJA], [r] el archivo del disco que se escribió y [h] su huella tras
     * escribirlo, que es lo que deja al [barrer] reconocer lo ya apuntado.
     */
    @Serializable
    data class Apunte(
        val n: Long = 0,
        val t: Long = 0,
        val k: String,
        val c: String? = null,
        val o: String = "",
        val a: String = CAMBIO,
        val r: String? = null,
        val h: String? = null
    ) {
        val clave: String get() = if (k == ARCHIVO) "$k|$o" else "$k|$c|$o"
    }

    /** La primera línea: el libro (cambia si el diario vuelve a nacer) y el piso (lo olvidado). */
    @Serializable
    data class Cabecera(val libro: String, val piso: Long = 0, val v: Int = 1)

    /**
     * **Hasta dónde quedó igual un chat con un aparato**: el número del diario de este aparato
     * cuando, al cerrar la vuelta, los dos lados estaban idénticos ([Sesion.cerrar]). [alli] es la
     * huella de lo que el otro dijo tener entonces ([huellaDeInventario]) y [sello] el de lo
     * acordado: con un aparato que no lleva diario (el PC de hoy) basta pedirle su inventario y
     * ver que sigue siendo ese.
     */
    @Serializable
    data class Cursor(val n: Long, val alli: String = "", val sello: String = "")

    @Serializable
    data class Cursores(
        val libro: String = "",
        val chats: Map<String, Cursor> = emptyMap(),
        /** Cuándo se hizo la última vuelta mirándolo todo con este aparato. */
        val verificado: Long = 0,
        /** Las vueltas desde entonces. */
        val vueltas: Int = 0
    ) {
        /**
         * **Toca mirarlo todo**: cada [VUELTAS_POR_VERIFICACION] vueltas o cada [VERIFICAR_CADA_MS].
         * Barato de sobra (una vuelta como las de antes) y cura lo que el diario no viera.
         */
        fun tocaVerificar(ahora: Long): Boolean =
            // Sin fecha aún (los cursores los puso el otro al dirigir él): cuenta desde la primera vuelta.
            vueltas >= VUELTAS_POR_VERIFICACION || (verificado > 0 && (ahora - verificado >= VERIFICAR_CADA_MS || ahora < verificado))

        companion object {
            const val VUELTAS_POR_VERIFICACION = 25
            const val VERIFICAR_CADA_MS = 24L * 3600_000
        }
    }

    /**
     * Lo que dice el diario, ya por chats: el último número que tocó cada uno y el último
     * [TODO]. [libro] y [piso], para saber si un cursor sigue valiendo.
     */
    class Vista(val libro: String, val piso: Long, val ultimo: Long, val todo: Long, val porChat: Map<String, Long>) {
        /** Si [chat] cambió después de [n]. */
        fun cambio(chat: String, n: Long): Boolean = todo > n || (porChat[chat] ?: 0L) > n
        fun vale(c: Cursores): Boolean = c.libro == libro
        fun vale(c: Cursor): Boolean = c.n >= piso && c.n <= ultimo
        /** El chat sigue como quedó con ese cursor. */
        fun quieto(cs: Cursores, chat: String): Boolean {
            val c = cs.chats[chat] ?: return false
            return vale(cs) && vale(c) && !cambio(chat, c.n)
        }
    }

    // ------------------------------------------------------------------ dónde

    private val carpeta: File get() = File(filesDir, "sincro/diario").apply { mkdirs() }
    private val archivo: File get() = File(carpeta, "diario.jsonl")
    private val archivoDeHuellas: File get() = File(carpeta, "huellas.tsv")
    private val archivoDelIndice: File get() = File(carpeta, "indice.tsv")
    private val carpetaDeCursores: File get() = File(carpeta, "cursores")
    private val archivoDelCerrojo: File get() = File(carpeta, "cerrojo")

    // ------------------------------------------------------------------ en memoria

    private var cargado = false
    private var libro = ""
    private var piso = 0L
    private var ultimo = 0L
    private var leidoHasta = 0L
    private var nodo: Any? = null
    private var lineas = 0
    private var todo = 0L
    private val porChat = HashMap<String, Long>()
    private val porArchivo = HashMap<String, Long>()
    private val claves = HashSet<String>()
    /** La huella con que se apuntó por última vez cada archivo del disco: ver [barrer]. */
    private val huellaApuntada = HashMap<String, String>()
    /** Lo que falló al apuntar: lo primero que se apunte después será un [TODO]. */
    private var perdido = false

    // ------------------------------------------------------------------ leer

    private fun json() = JSON

    /** Pone al día lo de memoria con lo del archivo (lo que añadieron otros procesos). Con el monitor. */
    private fun alDia() {
        val f = archivo
        if (!f.isFile) {
            nacer(if (cargado || ultimo > 0) "el diario desapareció" else null)
            return
        }
        val suNodo = nodoDe(f)
        if (!cargado || suNodo != nodo || f.length() < leidoHasta) {
            vaciarMemoria()
            leerDesde(f, 0)
            nodo = suNodo
            cargado = true
        } else if (f.length() > leidoHasta) {
            leerDesde(f, leidoHasta)
        }
    }

    private fun vaciarMemoria() {
        libro = ""; piso = 0; ultimo = 0; leidoHasta = 0; lineas = 0; todo = 0
        porChat.clear(); porArchivo.clear(); claves.clear(); huellaApuntada.clear()
    }

    private fun leerDesde(f: File, desde: Long) {
        val bytes = RandomAccessFile(f, "r").use { raf ->
            val largo = raf.length()
            if (largo <= desde) return
            val b = ByteArray((largo - desde).toInt())
            raf.seek(desde)
            raf.readFully(b)
            b
        }
        var inicio = 0
        var roto = false
        for (i in bytes.indices) {
            if (bytes[i] != '\n'.code.toByte()) continue
            val linea = String(bytes, inicio, i - inicio, Charsets.UTF_8)
            inicio = i + 1
            if (linea.isBlank()) continue
            if (libro.isEmpty()) {
                val cab = runCatching { json().decodeFromString(Cabecera.serializer(), linea) }.getOrNull()
                if (cab == null || cab.libro.isBlank()) { roto = true; break }
                libro = cab.libro; piso = cab.piso
                continue
            }
            val a = runCatching { json().decodeFromString(Apunte.serializer(), linea) }.getOrNull()
            if (a == null) { roto = true; continue }
            anotarEnMemoria(a)
        }
        leidoHasta = desde + inicio
        // **Lo que quedó a medias al final** (la aplicación muerta mientras escribía una línea): bajo
        // el cerrojo nadie está escribiendo, así que es basura. Se corta y se apunta «todo».
        if (inicio < bytes.size) roto = true
        if (libro.isEmpty()) { nacer("el diario no tenía cabecera"); return }
        if (roto) {
            runCatching { RandomAccessFile(f, "rw").use { it.setLength(leidoHasta) } }
            perdido = true
        }
    }

    private fun anotarEnMemoria(a: Apunte) {
        lineas++
        if (a.n > ultimo) ultimo = a.n
        claves += a.clave
        when (a.k) {
            TODO -> if (a.n > todo) todo = a.n
            ARCHIVO -> porArchivo[a.o] = maxOf(porArchivo[a.o] ?: 0L, a.n)
            else -> a.c?.let { porChat[it] = maxOf(porChat[it] ?: 0L, a.n) }
        }
        if (a.r != null && a.h != null) huellaApuntada[a.r] = a.h
    }

    /** Un diario nuevo, con otro libro: los cursores de antes ya no valen y la vuelta lo mira todo. */
    private fun nacer(motivo: String?) {
        vaciarMemoria()
        val cab = Cabecera(java.util.UUID.randomUUID().toString())
        val tmp = File(carpeta, "diario.jsonl.tmp")
        tmp.writeText(json().encodeToString(Cabecera.serializer(), cab) + "\n")
        if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
        libro = cab.libro
        leidoHasta = archivo.length()
        nodo = nodoDe(archivo)
        cargado = true
        perdido = false
        // El índice era del diario de antes: sin él, un archivo cambiado no sabría de qué chat es.
        // Nace con su índice vacío, del mismo libro: así un índice que falte después es uno perdido.
        indice.clear(); indiceInverso.clear(); indiceLeido = true
        runCatching { File(carpeta, "indice.tsv").writeText("#${cab.libro}\n") }.onFailure { indiceSucio = true }
        if (motivo != null) ultimoMotivo = motivo
    }

    /** Por qué nació el diario la última vez (para las pruebas y el registro). */
    @Volatile var ultimoMotivo: String? = null; private set

    // ------------------------------------------------------------------ escribir

    private inline fun <T> conCerrojo(bloque: () -> T): T {
        RandomAccessFile(archivoDelCerrojo, "rw").use { raf ->
            raf.channel.lock().use { return bloque() }
        }
    }

    /**
     * Añade [nuevos] con sus números. **Nunca lanza**: apuntar no puede tumbar a quien guarda el
     * chat. Si falla, lo primero que se consiga apuntar después será un [TODO].
     */
    internal fun apuntar(nuevos: List<Apunte>) {
        if (nuevos.isEmpty()) return
        try {
            synchronized(this) {
                conCerrojo {
                    alDia()
                    val ahora = System.currentTimeMillis()
                    val lista = ArrayList<Apunte>(nuevos.size + 1)
                    if (perdido) lista += Apunte(k = TODO, o = "perdido", t = ahora)
                    lista += nuevos
                    val numerados = lista.map { a -> a.copy(n = ++ultimo, t = if (a.t == 0L) ahora else a.t) }
                    val texto = numerados.joinToString("") { json().encodeToString(Apunte.serializer(), it) + "\n" }
                    FileOutputStream(archivo, true).use { it.write(texto.toByteArray()) }
                    leidoHasta = archivo.length()
                    numerados.forEach(::anotarEnMemoria)
                    perdido = false
                    if (lineas > COMPACTAR_DESDE && lineas > 3 * claves.size) compactar()
                }
            }
            avisar()
        } catch (_: Throwable) {
            synchronized(this) { perdido = true }
        }
    }

    /**
     * **Deja de cada cosa solo su último apunte.** «¿Cambió el chat X después de N?» sale igual: si
     * alguno de sus apuntes pasaba de N, el último también. Si aun así queda grande (cientos de
     * miles de cosas distintas), se olvida la mitad más vieja y sube el [Cabecera.piso]: los
     * cursores por debajo dejan de valer y esa vuelta lo mira todo.
     */
    private fun compactar() {
        val todos = ArrayList<Apunte>()
        archivo.forEachLine { l ->
            if (l.isBlank()) return@forEachLine
            runCatching { json().decodeFromString(Apunte.serializer(), l) }.getOrNull()?.let { todos += it }
        }
        val ultimos = LinkedHashMap<String, Apunte>()
        for (a in todos) { ultimos.remove(a.clave); ultimos[a.clave] = a }
        var quedan = ultimos.values.sortedBy { it.n }
        var nuevoPiso = piso
        if (quedan.size > TOPE_DE_COSAS) {
            val fuera = quedan.size - TOPE_DE_COSAS / 2
            nuevoPiso = maxOf(piso, quedan[fuera - 1].n)
            quedan = quedan.drop(fuera)
        }
        val tmp = File(carpeta, "diario.jsonl.tmp")
        FileOutputStream(tmp).use { s ->
            s.write((json().encodeToString(Cabecera.serializer(), Cabecera(libro, nuevoPiso)) + "\n").toByteArray())
            s.write(quedan.joinToString("") { json().encodeToString(Apunte.serializer(), it) + "\n" }.toByteArray())
            s.fd.sync()
        }
        if (!tmp.renameTo(archivo)) { tmp.delete(); return }
        val antesUltimo = ultimo
        vaciarMemoria()
        leerDesde(archivo, 0)
        // El último número no baja nunca aunque su línea se fuera con la poda.
        ultimo = maxOf(ultimo, antesUltimo)
        nodo = nodoDe(archivo)
        cargado = true
    }

    // ------------------------------------------------------------------ los ganchos

    /**
     * **El chat se reescribió**: lo que entró, cambió o se fue, por su código único. Lo llaman
     * `MensajesStore` y [Disco] justo después de escribir `guardados.jsonl`, con la llave del chat
     * puesta.
     */
    fun mensajes(antes: List<Mensaje>, despues: List<Mensaje>) {
        val h = runCatching { huella(RUTA_DEL_CHAT) }.getOrNull()
        val a = HashMap<Pair<String, String>, Mensaje>(antes.size * 2)
        for (m in antes) a[Disco.chatDe(m) to m.id] = m
        val nuevos = ArrayList<Apunte>()
        val vistos = HashSet<Pair<String, String>>(despues.size * 2)
        for (m in despues) {
            val k = Disco.chatDe(m) to m.id
            if (!vistos.add(k)) continue
            val v = a[k]
            if (v == null) nuevos += Apunte(k = MENSAJE, c = k.first, o = Codigos.unico(m), a = ALTA)
            else if (v != m) nuevos += Apunte(k = MENSAJE, c = k.first, o = Codigos.unico(m), a = CAMBIO)
        }
        for ((k, m) in a) if (k !in vistos) nuevos += Apunte(k = MENSAJE, c = k.first, o = Codigos.unico(m), a = BAJA)
        if (nuevos.isEmpty()) {
            // Nada distinto, pero el archivo sí se escribió: que el barrido no lo tome por ajeno.
            if (h != null) apuntar(listOf(Apunte(k = MENSAJE, c = null, o = "", a = CAMBIO, r = RUTA_DEL_CHAT, h = h)))
            return
        }
        apuntar(nuevos.mapIndexed { i, x -> if (i == nuevos.lastIndex) x.copy(r = RUTA_DEL_CHAT, h = h) else x })
    }

    /** Un mensaje añadido al final del chat (`MensajesStore.anadir`). */
    fun mensaje(m: Mensaje, accion: String = ALTA) {
        val h = runCatching { huella(RUTA_DEL_CHAT) }.getOrNull()
        apuntar(listOf(Apunte(k = MENSAJE, c = Disco.chatDe(m), o = Codigos.unico(m), a = accion, r = RUTA_DEL_CHAT, h = h)))
    }

    /** Las marcas de borrado se escribieron (`sincro/borrados.jsonl`). */
    fun marcas(nuevas: List<Marca>) {
        val h = runCatching { huella(RUTA_DE_MARCAS) }.getOrNull()
        val l = nuevas.map { Apunte(k = MENSAJE, c = it.chat, o = it.uid ?: it.sena, a = BAJA) }
            .ifEmpty { listOf(Apunte(k = MENSAJE, c = null, o = "", a = CAMBIO)) }
        apuntar(l.mapIndexed { i, x -> if (i == l.lastIndex) x.copy(r = RUTA_DE_MARCAS, h = h) else x })
    }

    /** La lista de proyectos se reescribió: los que entraron, cambiaron o se fueron. */
    fun proyectos(antes: List<Proyecto>, despues: List<Proyecto>) {
        val h = runCatching { huella(RUTA_DE_PROYECTOS) }.getOrNull()
        val a = antes.associateBy { it.id }
        val d = despues.associateBy { it.id }
        val nuevos = ArrayList<Apunte>()
        for ((id, p) in d) {
            val v = a[id]
            if (v == null) nuevos += Apunte(k = PROYECTO, c = id, o = id, a = ALTA)
            else if (v != p) nuevos += Apunte(k = PROYECTO, c = id, o = id, a = CAMBIO)
        }
        for (id in a.keys) if (id !in d) nuevos += Apunte(k = PROYECTO, c = id, o = id, a = BAJA)
        if (nuevos.isEmpty()) nuevos += Apunte(k = PROYECTO, c = null, o = "", a = CAMBIO)
        apuntar(nuevos.mapIndexed { i, x -> if (i == nuevos.lastIndex) x.copy(r = RUTA_DE_PROYECTOS, h = h) else x })
    }

    /** Un proyecto entero borrado (lápida puesta o quitada). */
    fun lapida(chat: String, accion: String) {
        val h = runCatching { huella(RUTA_DE_LAPIDAS) }.getOrNull()
        apuntar(listOf(Apunte(k = LAPIDA, c = chat, o = chat, a = accion, r = RUTA_DE_LAPIDAS, h = h)))
    }

    /** Se escribió o se quitó [rel] (relativa a `files`). */
    fun archivo(rel: String) {
        val h = runCatching { huella(rel) }.getOrNull() ?: return
        apuntar(listOf(Apunte(k = ARCHIVO, o = rel, a = if (h == NO_ESTA) BAJA else CAMBIO, r = rel, h = h)))
    }

    /** Lo mismo para varios a la vez (una vuelta que borra los adjuntos de muchos mensajes). */
    fun archivos(rels: Collection<String>) {
        if (rels.isEmpty()) return
        apuntar(rels.mapNotNull { rel ->
            val h = runCatching { huella(rel) }.getOrNull() ?: return@mapNotNull null
            Apunte(k = ARCHIVO, o = rel, a = if (h == NO_ESTA) BAJA else CAMBIO, r = rel, h = h)
        })
    }

    /** «No sé qué cambió»: la próxima vuelta lo mira todo. */
    fun todo(motivo: String) = apuntar(listOf(Apunte(k = TODO, o = motivo)))

    // ------------------------------------------------------------------ preguntar

    /** El último número apuntado (tras releer lo de otros procesos). */
    fun ultimo(): Long = synchronized(this) { runCatching { conCerrojo { alDia() } }; ultimo }

    /**
     * **Lo que dice el diario por chats.** [resolver] dice de qué chats son los archivos que el
     * [indice] no conoce (ver [Disco.chatsQueNombran]); los que no nombra nadie no son de ningún
     * chat y no cuentan. Solo se resuelven los apuntados después de [desde].
     */
    fun vista(desde: Long = 0, resolver: (Set<String>) -> Map<String, Set<String>> = { emptyMap() }): Vista {
        val (cabeza, chats, archivos) = synchronized(this) {
            runCatching { leerIndice() }
            runCatching { conCerrojo { alDia() } }
            Triple(Triple(libro, piso, ultimo to (if (perdido) Long.MAX_VALUE else todo)), HashMap(porChat), HashMap(porArchivo))
        }
        val desconocidos = HashSet<String>()
        val salida = HashMap(chats)
        for ((rel, n) in archivos) {
            if (n <= desde) continue
            val suyos = synchronized(this) { indiceInverso[rel]?.toList() }
            if (suyos == null) { desconocidos += rel; continue }
            for (c in suyos) salida[c] = maxOf(salida[c] ?: 0L, n)
        }
        if (desconocidos.isNotEmpty()) {
            val resueltos = runCatching { resolver(desconocidos) }.getOrElse { desconocidos.associateWith { setOf(TODOS) } }
            for ((rel, cs) in resueltos) {
                val n = archivos[rel] ?: continue
                for (c in cs) salida[c] = maxOf(salida[c] ?: 0L, n)
            }
        }
        val todoN = maxOf(cabeza.third.second, salida.remove(TODOS) ?: 0L)
        return Vista(cabeza.first, cabeza.second, cabeza.third.first, todoN, salida)
    }

    // ------------------------------------------------------------------ el índice

    /** Archivo → chats que lo nombran, sacado de cada [Disco.alcance] calculado. */
    private val indice = HashMap<String, Set<String>>()
    private val indiceInverso = HashMap<String, MutableSet<String>>()
    private var indiceSucio = false
    private var indiceLeido = false

    /** **Los archivos de [chat] son estos**: lo dice quien acaba de calcular su alcance. */
    fun indexar(chat: String, rels: Collection<String>) = synchronized(this) {
        leerIndice()
        val nuevos = rels.toSet()
        val viejos = indice[chat].orEmpty()
        if (viejos == nuevos) return@synchronized
        for (r in viejos - nuevos) indiceInverso[r]?.let { it.remove(chat); if (it.isEmpty()) indiceInverso.remove(r) }
        for (r in nuevos - viejos) indiceInverso.getOrPut(r) { HashSet() } += chat
        indice[chat] = nuevos
        indiceSucio = true
    }

    private fun leerIndice() {
        if (indiceLeido) return
        runCatching { conCerrojo { alDia() } }
        indiceLeido = true
        val f = archivoDelIndice
        if (!f.isFile) {
            // Sin índice y con un diario que ya tiene historia: un archivo cambiado no sabría de qué
            // chat es. Que se mire todo una vez.
            if (ultimo > 0) perdido = true
            indiceSucio = true
            return
        }
        val lineas = runCatching { f.readLines() }.getOrElse { perdido = true; return }
        if (lineas.firstOrNull() != "#$libro") { perdido = true; indiceSucio = true; return }
        for (l in lineas.drop(1)) {
            val chat = l.substringBefore('\t')
            val rels = l.substringAfter('\t', "").split('\u0001').filter { it.isNotEmpty() }.toSet()
            if (chat.isEmpty()) continue
            indice[chat] = rels
            for (r in rels) indiceInverso.getOrPut(r) { HashSet() } += chat
        }
    }

    /** Escribe el índice si cambió. Va antes que los cursores: un cursor sin su índice no vale. */
    fun guardarIndice() = synchronized(this) {
        leerIndice()
        if (!indiceSucio) return@synchronized
        runCatching {
            val tmp = File(carpeta, "indice.tsv.tmp")
            tmp.writeText("#$libro\n" + indice.entries.joinToString("") { (c, rs) -> c + "\t" + rs.joinToString("\u0001") + "\n" })
            if (!tmp.renameTo(archivoDelIndice)) { archivoDelIndice.delete(); tmp.renameTo(archivoDelIndice) }
            indiceSucio = false
        }
    }

    // ------------------------------------------------------------------ los cursores

    private fun archivoDeCursores(otro: String) = File(carpetaDeCursores, otro.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    fun cursores(otro: String): Cursores = runCatching {
        json().decodeFromString(Cursores.serializer(), archivoDeCursores(otro).readText())
    }.getOrDefault(Cursores())

    /**
     * Cambia los cursores de [otro] con [cambio], sobre el libro de ahora: si los guardados eran
     * de otro libro se tiran antes (ya no valen).
     */
    fun cambiarCursores(otro: String, cambio: (Cursores) -> Cursores) = synchronized(this) {
        runCatching {
            conCerrojo { alDia() }
            guardarIndice()
            val antes = cursores(otro).let { if (it.libro != libro) Cursores(libro, verificado = it.verificado, vueltas = it.vueltas) else it }
            val despues = cambio(antes).copy(libro = libro)
            val f = archivoDeCursores(otro)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json().encodeToString(Cursores.serializer(), despues))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun ponerCursor(otro: String, chat: String, c: Cursor?) = cambiarCursores(otro) { cs ->
        cs.copy(chats = if (c == null) cs.chats - chat else cs.chats + (chat to c))
    }

    /** Fuera todo lo acordado con los demás (salir del grupo). */
    fun olvidarCursores() = synchronized(this) { carpetaDeCursores.deleteRecursively() }

    // ------------------------------------------------------------------ el barrido

    private class Huella(val bytes: Long, val fecha: Long, val nodo: String, val crc: Long?) {
        val texto: String get() = "$bytes:$fecha:$nodo"
    }

    /**
     * **Lo que cambió en las carpetas que viajan sin que nadie lo apuntara**, mirando solo tamaño,
     * fecha y nodo de cada archivo (y el CRC de los recién escritos). Lo encontrado se apunta: un
     * archivo como [ARCHIVO], el chat, los proyectos o sus marcas como [TODO] (no se sabe de qué
     * chat). Va al empezar cada vuelta, en los dos lados. Devuelve cuántos apuntó.
     */
    fun barrer(): Int = try {
        synchronized(this) { barrerYa() }
    } catch (_: Throwable) {
        todo("el barrido falló"); 1
    }

    private fun barrerYa(): Int {
        conCerrojo { alDia() }
        val antes = leerHuellas()
        val ahora = recorrer()
        val tomada = System.currentTimeMillis()
        val nuevos = ArrayList<Apunte>()
        val guardar = HashMap<String, Huella>(ahora.size * 2)
        for ((rel, h) in ahora) {
            val previa = antes?.get(rel)
            val reciente = tomada - h.fecha / 1_000_000 < RECIENTE_MS && h.bytes <= TOPE_DE_CRC
            val iguales = previa != null && previa.texto == h.texto
            var cambio = !iguales
            var crc: Long? = null
            if (iguales && previa!!.crc != null) {
                crc = crcDe(rel)
                if (crc != previa.crc) cambio = true
            }
            if (reciente && crc == null) crc = crcDe(rel)
            guardar[rel] = Huella(h.bytes, h.fecha, h.nodo, crc)
            if (antes == null || !cambio) continue
            // Lo apuntó quien lo escribió, con esta misma huella: ya está en el diario.
            if (huellaApuntada[rel] == h.texto && !(iguales && previa!!.crc != null)) continue
            nuevos += detectado(rel, if (previa == null) ALTA else CAMBIO)
        }
        if (antes != null) for (rel in antes.keys) if (rel !in ahora) {
            if (huellaApuntada[rel] == NO_ESTA) continue
            nuevos += detectado(rel, BAJA)
        }
        if (antes == null && ultimo > 0) nuevos += Apunte(k = TODO, o = "sin huellas")
        if (nuevos.isNotEmpty()) apuntar(nuevos)
        escribirHuellas(guardar, tomada)
        return nuevos.size
    }

    private fun detectado(rel: String, accion: String): Apunte =
        if (rel in INDICES) Apunte(k = TODO, o = rel, a = accion) else Apunte(k = ARCHIVO, o = rel, a = accion)

    private fun leerHuellas(): Map<String, Huella>? {
        val f = archivoDeHuellas
        if (!f.isFile) return null
        return runCatching {
            val salida = HashMap<String, Huella>()
            f.forEachLine { l ->
                if (l.startsWith("#")) return@forEachLine
                val p = l.split('\t')
                if (p.size < 5) return@forEachLine
                salida[p[0]] = Huella(p[1].toLong(), p[2].toLong(), p[3], p[4].takeIf { it != "-" }?.toLong())
            }
            salida
        }.getOrNull()
    }

    private fun escribirHuellas(m: Map<String, Huella>, tomada: Long) {
        val tmp = File(carpeta, "huellas.tsv.tmp")
        val sb = StringBuilder("#$tomada\n")
        for ((rel, h) in m) sb.append(rel).append('\t').append(h.bytes).append('\t').append(h.fecha).append('\t')
            .append(h.nodo).append('\t').append(h.crc?.toString() ?: "-").append('\n')
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(archivoDeHuellas)) { archivoDeHuellas.delete(); tmp.renameTo(archivoDeHuellas) }
    }

    /** Todos los archivos de lo que viaja, con su tamaño, fecha y nodo. Una llamada al sistema por archivo. */
    private fun recorrer(): Map<String, Huella> {
        val salida = HashMap<String, Huella>()
        val base = filesDir.toPath()
        val visitante = object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val rel = base.relativize(file).toString().replace('\\', '/')
                if (!temporal(rel)) salida[rel] = Huella(attrs.size(), fechaDe(attrs), attrs.fileKey()?.toString() ?: "", null)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        }
        for (c in Disco.CARPETAS) {
            val d = File(filesDir, c)
            if (d.isDirectory) Files.walkFileTree(d.toPath(), visitante)
        }
        for (r in INDICES) {
            val f = File(filesDir, r)
            if (!f.isFile || r.substringBefore('/') + "/" in Disco.CARPETAS) continue
            val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
            salida[r] = Huella(a.size(), fechaDe(a), a.fileKey()?.toString() ?: "", null)
        }
        return salida
    }

    private fun crcDe(rel: String): Long? = runCatching {
        val c = CRC32()
        File(filesDir, rel).inputStream().use { e ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = e.read(buf); if (n < 0) break; c.update(buf, 0, n) }
        }
        c.value
    }.getOrNull()

    /** La huella de un archivo ahora: tamaño, fecha en nanosegundos y nodo; [NO_ESTA] si no existe. */
    internal fun huella(rel: String): String {
        val f = File(filesDir, rel)
        if (!f.exists()) return NO_ESTA
        val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        return "${a.size()}:${fechaDe(a)}:${a.fileKey()?.toString() ?: ""}"
    }

    // ------------------------------------------------------------------ avisos

    private fun avisar() { for (o in oyentes) runCatching { o() } }

    companion object {
        const val MENSAJE = "m"
        const val PROYECTO = "p"
        const val ARCHIVO = "f"
        const val LAPIDA = "l"
        /** No se sabe qué cambió: todos los chats. */
        const val TODO = "x"
        const val ALTA = "alta"
        const val CAMBIO = "cambio"
        const val BAJA = "baja"
        const val NO_ESTA = "-"
        /** Un chat de mentira para «todos» al resolver archivos. */
        private const val TODOS = "\u0000todos"

        const val RUTA_DEL_CHAT = "guardados.jsonl"
        const val RUTA_DE_PROYECTOS = "proyectos/proyectos.json"
        const val RUTA_DE_MARCAS = "sincro/borrados.jsonl"
        const val RUTA_DE_LAPIDAS = "sincro/chatsborrados.jsonl"
        /** Los archivos que son índice de muchos chats: cambiados sin apunte, no se sabe de cuál. */
        val INDICES = setOf(RUTA_DEL_CHAT, RUTA_DE_PROYECTOS, RUTA_DE_MARCAS, RUTA_DE_LAPIDAS)

        /** Lo escrito hace menos de esto puede no haber movido la fecha: se mira también su CRC. */
        const val RECIENTE_MS = 3_000L
        /** Por encima, ni el CRC de lo reciente (un vídeo recién llegado no se relee). */
        const val TOPE_DE_CRC = 8L shl 20
        private const val COMPACTAR_DESDE = 4_000
        private const val TOPE_DE_COSAS = 200_000

        /**
         * **El interruptor**: apagado, ninguna vuelta usa el diario (se apunta igual). Para medir
         * (`DiarioDeCambiosTest`) y como salida de emergencia.
         */
        @Volatile var activo: Boolean = true

        /**
         * Si este proceso es el que sincroniza. Los procesos aparte (`:planos`, `:pdfsqueeze`)
         * apuntan igual, con el cerrojo de archivo; lo pone `PixPinApp`.
         */
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        private val porCarpeta = java.util.concurrent.ConcurrentHashMap<String, Diario>()

        /** El diario de la carpeta [filesDir]: uno por carpeta y proceso. */
        fun de(filesDir: File): Diario {
            val clave = runCatching { filesDir.canonicalPath }.getOrDefault(filesDir.absolutePath)
            return porCarpeta.getOrPut(clave) { Diario(File(clave)) }
        }

        /** Quien quiera enterarse de que se apuntó algo (la sincronización automática). */
        val oyentes = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        private fun temporal(rel: String) = rel.endsWith(".tmp") || rel.endsWith(".sincro") || rel.endsWith(".nuevo") ||
            rel.endsWith(Disco.PARCIAL) || rel.endsWith(Disco.PARCIAL + ".json")

        private fun fechaDe(a: BasicFileAttributes): Long = a.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS)

        private fun nodoDe(f: File): Any? = runCatching { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java).fileKey() }.getOrNull()

        /**
         * **La huella de lo que el otro dijo tener** de un chat: sus mensajes y marcas por código y
         * resumen, su proyecto canónico y sus archivos por ruta y resumen (sin fechas: un archivo
         * vuelto a escribir igual no es un cambio). Ver [Cursor.alli].
         */
        fun huellaDeInventario(apuntes: List<Diferencia.Apunte>, proyecto: String?, archivos: List<ArchivoInfo>): String {
            val sb = StringBuilder()
            for (a in apuntes.sortedBy { it.sena }) sb.append(a.sena).append('=').append(a.resumen).append(if (a.borrado) "!" else "").append('\n')
            sb.append("--\n").append(proyecto?.let { Canonico.de(it) } ?: "").append("\n--\n")
            for (f in archivos.sortedBy { it.ruta }) sb.append(f.ruta).append('=').append(f.resumen).append('\n')
            return sha256(sb.toString().toByteArray())
        }
    }
}
