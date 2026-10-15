package com.forge.pixpin.planos

import com.forge.pixpin.asistente.ChatDelAsistente
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.sincro.Codigos
import com.forge.pixpin.sincro.Disco
import com.forge.pixpin.sincro.Rutas
import com.forge.pixpin.sincro.codigo
import java.io.File

/**
 * **Las letras de los planos viajan con los aparatos propios** (14-oct-2026, como
 * `apps/pixpin/src/letras_que_viajan.rs` del PC y `docs/investigacion/2026-10-13-para-android.md`).
 *
 * El usuario tiene AutoCAD en el PC y no quiere que el teléfono le pida letras al abrir planos. Las
 * que **él mismo** pone con «Aa» → «Añadir letras…» se llevan a sus otros aparatos, **nunca** a
 * personas de chats compartidos: PixPin no reparte letras de nadie (las de Autodesk y Microsoft no
 * se copian ni se suben a ningún sitio; solo viajan las que el usuario añadió).
 *
 * **Cómo viajan**: un mensaje `ARCHIVO` por letra en el chat «Asistente» (que no se comparte), con
 * id fijo [idDe] y ruta `pixpin:files/guardados/fuentes-shx/<nombre>` ([rutaPortatil]). Uno por
 * letra: la sincronización manda solo lo nuevo o cambiado (compara cada archivo por su resumen),
 * dos aparatos que añaden a la vez no se pisan, y **borrar el mensaje borra el archivo en el otro
 * aparato** (lo de `guardados/` se va con su mensaje: `adjuntosFuera` de [Disco.aplicarMensajes]).
 *
 * Por eso la carpeta del teléfono es `files/guardados/fuentes-shx` ([carpeta]) y no
 * `files/fuentes-shx` como antes: esa no está en las carpetas que viajan y, fuera de `guardados/`,
 * borrar el mensaje no se llevaba el archivo. La de antes se mueve la primera vez ([moverLaDeAntes]).
 *
 * Sin Android: trabaja sobre un [Disco], y así se prueba con dos aparatos de mentira
 * (`LetrasQueViajanDeVerdadTest`). Lo de la pantalla está en [LectorDePlanos].
 */
object LetrasQueViajan {

    /** El principio del id del mensaje de cada letra. */
    const val PREFIJO_ID = "auto-letra-"

    /** Una letra más grande no viaja (las SHX pesan KB; una TrueType normal, menos de 1 MB). */
    const val TOPE_POR_LETRA = 10L * 1024 * 1024

    /** Entre todas las que viajan. */
    const val TOPE_TOTAL = 64L * 1024 * 1024

    /** El texto del mensaje (el del PC). */
    const val TEXTO = "🔤 Letra de los planos (botón «Aa» del visor; solo va a tus aparatos)"

    /** Las letras, por su terminación (las de [LectorDePlanos.TIPOS_DE_LETRA]). */
    val TIPOS = setOf("shx", "ttf", "otf", "ttc")

    /** **La carpeta de las letras del usuario**: dentro de `guardados/`, para que viajen y se borren con su mensaje. */
    fun carpeta(filesDir: File): File = File(filesDir, Disco.CARPETA_DE_LETRAS.trimEnd('/'))

    /** La de antes del 14-oct-2026: no viajaba. */
    fun carpetaDeAntes(filesDir: File): File = File(filesDir, "fuentes-shx")

    /** El id fijo del mensaje de la letra [nombre] (sin mirar mayúsculas): `auto-letra-romans.shx`. */
    fun idDe(nombre: String): String = PREFIJO_ID + limpio(nombre.lowercase())

    /** Dónde la deja el otro aparato: `pixpin:files/guardados/fuentes-shx/<nombre>`. */
    fun rutaPortatil(nombre: String): String = Rutas.PORTATIL + Disco.CARPETA_DE_LETRAS + nombre

    /** Como `disco::limpio` del PC (y el de [Disco]): lo que no es ASCII sencillo, `_`. */
    private fun limpio(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Si [nombre] termina como una letra. */
    fun esLetra(nombre: String): Boolean = nombre.substringAfterLast('.', "").lowercase() in TIPOS

    /**
     * **Si el archivo es de verdad una letra**: su terminación ([nombre]) y sus primeros bytes. Las
     * SHX empiezan por «AutoCAD-86»; las TrueType por `00 01 00 00`, `true` o `typ1`; las OpenType
     * por `OTTO`; las colecciones por `ttcf`. Un archivo cualquiera rebautizado `.shx` no pasa.
     */
    fun pareceLetra(f: File, nombre: String = f.name): Boolean {
        if (!esLetra(nombre) || !f.isFile) return false
        val cabeza = ByteArray(10)
        val n = runCatching { f.inputStream().use { i -> var leidos = 0; while (leidos < 10) { val r = i.read(cabeza, leidos, 10 - leidos); if (r < 0) break; leidos += r }; leidos } }.getOrDefault(0)
        fun empieza(m: ByteArray) = n >= m.size && m.indices.all { cabeza[it] == m[it] }
        return empieza("AutoCAD-86".toByteArray(Charsets.US_ASCII)) ||
            listOf(byteArrayOf(0, 1, 0, 0), "true".toByteArray(), "typ1".toByteArray(), "OTTO".toByteArray(), "ttcf".toByteArray()).any { empieza(it) }
    }

    /**
     * Un nombre que puede viajar tal cual: el plano pide la letra por su nombre, así que no se
     * cambia; uno que corta la ruta portátil dentro de un texto (comillas, paréntesis…) o que sale
     * de la carpeta se queda en este aparato.
     */
    fun nombreQueViaja(nombre: String): Boolean =
        nombre.isNotBlank() && !nombre.startsWith('.') && !nombre.contains("..") &&
            nombre.none { it in "/\\\"'()<>\n\r" || it.isISOControl() }

    /** Las letras de [carpeta], por nombre (sin los temporales). */
    fun letrasEn(carpeta: File): List<File> =
        carpeta.listFiles()?.filter { it.isFile && esLetra(it.name) }?.sortedBy { it.name.lowercase() }.orEmpty()

    /** Si una ruta relativa a `files` es de la carpeta de letras: una persona no la toca nunca. */
    fun esDeLetras(rel: String): Boolean = rel.startsWith(Disco.CARPETA_DE_LETRAS)

    /**
     * **La carpeta de antes, a la nueva**, una vez: lo que ya había en `files/fuentes-shx` pasa a
     * `files/guardados/fuentes-shx` (si ya hay una con ese nombre, manda la nueva). Devuelve cuántas.
     */
    fun moverLaDeAntes(filesDir: File): Int {
        val antes = carpetaDeAntes(filesDir)
        if (!antes.isDirectory) return 0
        val nueva = carpeta(filesDir).apply { mkdirs() }
        var n = 0
        for (f in antes.listFiles().orEmpty()) {
            if (!f.isFile) continue
            val destino = File(nueva, f.name)
            if (destino.exists()) { f.delete(); continue }
            if (f.renameTo(destino) || runCatching { f.copyTo(destino); f.delete() }.isSuccess) n++
        }
        if (antes.listFiles().isNullOrEmpty()) antes.delete()
        return n
    }

    /** El chat «Asistente», si ya está (sin crearlo). */
    fun chatDelAsistente(disco: Disco): String? = ChatDelAsistente.buscar(disco.leerProyectos(), disco.leerMensajes())?.id

    /**
     * **Pone el mensaje de cada letra de la carpeta que no lo tenga**, dentro de los topes. Si ya
     * está, no se toca: el archivo cambia, el mensaje no (la sincronización mira el archivo).
     * [asegurarChat] da el chat «Asistente», creándolo si hace falta; solo se llama si hay letras.
     * Devuelve cuántos puso.
     */
    fun senalar(disco: Disco, ahora: Long, asegurarChat: () -> String?): Int {
        val letras = letrasEn(carpeta(disco.filesDir)).filter { pareceLetra(it) && nombreQueViaja(it.name) }
        if (letras.isEmpty()) return 0
        val chat = asegurarChat() ?: return 0
        val delChat = disco.leerMensajes().filter { Disco.chatDe(it) == chat }
        val ya = delChat.mapNotNullTo(HashSet()) { m -> m.id.takeIf { it.startsWith(PREFIJO_ID) } }
        var total = letras.filter { idDe(it.name) in ya }.sumOf { it.length() }
        var numero = (delChat.maxOfOrNull { it.numero } ?: 0) + 1
        val aparato = runCatching { disco.identidad.leer().yo.codigo }.getOrNull()
        val vistos = HashSet<String>()
        val nuevos = ArrayList<Mensaje>()
        for (l in letras) {
            val id = idDe(l.name)
            if (id in ya || !vistos.add(id)) continue
            val bytes = l.length()
            // Lo que pasa de los topes se queda en este aparato: aquí se usa igual.
            if (bytes > TOPE_POR_LETRA || total + bytes > TOPE_TOTAL) continue
            nuevos += Mensaje(
                id = id, cuando = ahora, clase = Clase.ARCHIVO, texto = TEXTO, ruta = l.absolutePath,
                nombre = l.name, bytes = bytes, proyecto = chat, numero = numero++,
                uid = Codigos.nuevo(), aparato = aparato
            )
            total += bytes
        }
        if (nuevos.isNotEmpty()) disco.aplicarMensajes(chat, nuevos.map { disco.portatil(it) }, emptyList(), ahora)
        return nuevos.size
    }

    /**
     * Quita los mensajes de letra del chat «Asistente» que cumplan [cuales], **con su marca de
     * borrado** (así el otro aparato los quita, y con ellos sus archivos). De lo que nombran solo
     * se borra lo que está en la carpeta de letras: un mensaje de letra falso que señala un adjunto
     * de otro chat no se lo lleva. Devuelve los quitados.
     */
    private fun quitarMensajes(disco: Disco, ahora: Long, cuales: (Mensaje) -> Boolean): List<Mensaje> {
        val chat = chatDelAsistente(disco) ?: return emptyList()
        val van = disco.leerMensajes().filter { Disco.chatDe(it) == chat && it.id.startsWith(PREFIJO_ID) && cuales(it) }
        if (van.isEmpty()) return emptyList()
        val noBorrar = van.mapNotNullTo(HashSet()) { m ->
            m.ruta?.let { disco.relativa(it) }?.takeIf { rel -> !(esDeLetras(rel) && rel.removePrefix(Disco.CARPETA_DE_LETRAS).let { n -> n.isNotEmpty() && '/' !in n }) }
        }
        disco.aplicarMensajes(chat, emptyList(), van.map { Codigos.unico(it) }, ahora, noBorrar = noBorrar)
        return van
    }

    /** **«Quitar las mías»**: fuera las letras de la carpeta y sus mensajes, con marca. Devuelve cuántas. */
    fun quitarTodas(disco: Disco, ahora: Long): Int {
        val letras = letrasEn(carpeta(disco.filesDir))
        val n = letras.count { it.delete() }
        quitarMensajes(disco, ahora) { true }
        return n
    }

    /**
     * **Rechaza lo que llegó y no es una letra**: un archivo con nombre de letra que por dentro no
     * lo es, o un mensaje de letra que señala otra cosa. Fuera el archivo y su mensaje (con marca,
     * para que el otro aparato lo quite también). Devuelve cuántos.
     */
    fun limpiar(disco: Disco, ahora: Long): Int {
        val carpeta = carpeta(disco.filesDir)
        val malos = letrasEn(carpeta).filter { !pareceLetra(it) }.mapTo(HashSet()) { it.name }
        val quitados = quitarMensajes(disco, ahora) { m ->
            m.nombre in malos || !esLetra(m.nombre) || m.ruta?.let { disco.relativa(it) } != Disco.CARPETA_DE_LETRAS + m.nombre
        }
        val fuera = HashSet(malos)
        for (m in quitados) {
            val n = m.ruta?.let { disco.relativa(it) }?.takeIf { esDeLetras(it) }?.removePrefix(Disco.CARPETA_DE_LETRAS) ?: continue
            if (n.isNotEmpty() && '/' !in n && '\\' !in n && ".." !in n) fuera += n
        }
        val borrados = fuera.count { File(carpeta, it).delete() }
        return maxOf(borrados, quitados.size)
    }

    /** **Al abrir un plano**: rechazar lo que no vale y hacer viajar las letras que ya había (las de antes, o dejadas a mano). */
    fun alAbrir(disco: Disco, ahora: Long, asegurarChat: () -> String?) {
        moverLaDeAntes(disco.filesDir)
        limpiar(disco, ahora)
        senalar(disco, ahora, asegurarChat)
    }
}
