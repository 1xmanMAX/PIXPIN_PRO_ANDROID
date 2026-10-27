package com.forge.pixpin.guardados

import java.util.Locale

/**
 * **La letra de una canción: leerla, guardarla y saber por qué línea va** (27-oct-2026, lo pidió
 * el usuario: «quiero guardar música, con su letra adjunta, para poder cantar cuando quiera»).
 *
 * ## Dónde se guarda, y por qué ahí
 *
 * La música ya existía en el chat (6-sep-2026): una nota de voz ([Clase.VOZ]) con
 * `estadoDelTexto = "letra"` ([TEXTO_LETRA], ver [esMusica]) y la letra en [Mensaje.transcripcion].
 * El PC lo entiende desde hace semanas —`biblioteca_audio.rs::es_musica`, su lápiz de la letra
 * (`voz.rs::escribir_letra`) y su pantalla de la letra—, así que **no se inventa ningún campo
 * nuevo**: la letra sincronizada va en ese mismo texto, con **el formato de tiempos que ya leen los
 * dos** (`Transcriptor.conTiempos` aquí, `pixpin_voz::con_tiempos` en el PC): una línea por
 * párrafo, `[m:ss] lo que se canta`, y un renglón en blanco entre ellas. Así el PC resalta la
 * línea que suena y salta a ella al tocarla **sin cambiar una coma**.
 *
 * Lo que no se hace: un campo `letra` (ya es la letra del aparato, ver [Mensaje.letra]); ni guardar
 * el `.lrc` tal cual, con `[00:12.34]`, que ni el PC ni la pantalla de la letra de aquí reconocen
 * como tiempo (`dos_cifras` exige dos cifras justas) y saldría todo de un bloque con los corchetes a
 * la vista. Lo que se pierde es la centésima: se redondea al segundo, que para seguir una canción
 * cantando sobra. Ver `docs/musica-con-letra.md`.
 *
 * ## Lo que se acepta al pegar
 *
 * - Texto llano: se guarda tal cual, línea a línea.
 * - **LRC**: `[mm:ss.xx]`, `[mm:ss]`, varias marcas en una línea (estribillos), las etiquetas
 *   `[ti:]`, `[ar:]`, `[al:]`, `[by:]`, `[offset:]` (se aplica) y las marcas por palabra `<mm:ss.xx>`
 *   del LRC «mejorado» (se quitan).
 * - Lo que ya está guardado (`[m:ss]` con renglones en blanco, o `[h:mm:ss]`), que vuelve igual.
 *
 * Todo puro, para comprobarlo en el ordenador (`LetraDeCancionTest`).
 */
object LetraDeCancion {

    /** Una línea: en qué milisegundo empieza (null si no lo dice) y qué se canta. */
    data class Linea(val ms: Int?, val texto: String)

    /** Una letra leída: sus líneas y lo que dijera de sí misma (título, artista). */
    data class Letra(val lineas: List<Linea>, val titulo: String? = null, val artista: String? = null) {
        /** Si lleva tiempos: entonces se resalta la línea que suena. */
        val conTiempos: Boolean get() = lineas.any { it.ms != null }
    }

    /** Las etiquetas del LRC que no son letra (`[ti:Título]`, `[offset:+250]`…). */
    private val ETIQUETA = Regex("""^\[([A-Za-z#]+):(.*)]$""")

    /** Las marcas por palabra del LRC mejorado: `<00:12.34>`. */
    private val POR_PALABRA = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /**
     * **Lee una letra pegada**: LRC, la de PixPin con tiempos o texto llano. Las líneas con tiempo
     * salen ordenadas por tiempo (una línea con dos marcas sale dos veces, que es lo que es: el
     * estribillo se canta dos veces).
     */
    fun leer(texto: String): Letra {
        val lineas = texto.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var titulo: String? = null
        var artista: String? = null
        var desfase = 0
        // Primero, si hay algún tiempo: sin ninguno es texto llano y se respeta tal cual.
        val hayTiempos = lineas.any { marcasAlPrincipio(it.trim()).first.isNotEmpty() }
        if (!hayTiempos) {
            val llanas = lineas.map { it.trimEnd() }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            return Letra(llanas.map { Linea(null, it) })
        }
        // Cada entrada lleva su orden de llegada para que ordenar por tiempo no baraje las iguales.
        val salida = ArrayList<Linea>()
        var anteriorEnBlanco = true
        for (cruda in lineas) {
            val linea = cruda.trim()
            if (linea.isEmpty()) { anteriorEnBlanco = true; continue }
            ETIQUETA.matchEntire(linea)?.let { m ->
                val clave = m.groupValues[1].lowercase(Locale.ROOT)
                val valor = m.groupValues[2].trim()
                when (clave) {
                    "ti" -> titulo = valor.ifBlank { null }
                    "ar" -> artista = valor.ifBlank { null }
                    "offset" -> desfase = valor.removePrefix("+").toIntOrNull() ?: 0
                }
                anteriorEnBlanco = true
                continue
            }
            val (marcas, resto) = marcasAlPrincipio(linea)
            val letra = resto.replace(POR_PALABRA, "").trim()
            if (marcas.isEmpty()) {
                // Sin marca: si sigue pegada a una línea con tiempo (un párrafo de PixPin partido en
                // dos renglones), es suya; si no, va suelta.
                val ultima = salida.lastOrNull()
                if (!anteriorEnBlanco && ultima != null) {
                    salida[salida.lastIndex] = ultima.copy(texto = if (ultima.texto.isEmpty()) letra else ultima.texto + "\n" + letra)
                } else salida.add(Linea(null, letra))
            } else {
                // `offset` positivo adelanta la letra (LRC): la línea sale antes.
                marcas.forEach { salida.add(Linea((it - desfase).coerceAtLeast(0), letra)) }
            }
            anteriorEnBlanco = false
        }
        return Letra(ordenar(salida), titulo, artista)
    }

    /**
     * Por tiempo, sin barajar: una línea sin tiempo se queda detrás de la que tenía delante.
     * `sortedBy` es estable, así que las de igual tiempo siguen en su orden.
     */
    private fun ordenar(lineas: List<Linea>): List<Linea> {
        var ultimo = -1
        val claves = lineas.map { l -> l.ms?.also { ultimo = it } ?: ultimo }
        return lineas.indices.sortedBy { claves[it] }.map { lineas[it] }
    }

    /**
     * Las marcas de tiempo con las que empieza [linea] (en ms) y lo que queda detrás. Vale
     * `[m:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]` y `[h:mm:ss]`; lo que no, no es marca.
     */
    fun marcasAlPrincipio(linea: String): Pair<List<Int>, String> {
        val marcas = ArrayList<Int>()
        var resto = linea
        while (resto.startsWith('[')) {
            val cierre = resto.indexOf(']')
            if (cierre < 0) break
            val ms = tiempoDe(resto.substring(1, cierre)) ?: break
            marcas.add(ms)
            resto = resto.substring(cierre + 1).trimStart()
        }
        return marcas to resto
    }

    /** `1:23`, `01:23.45`, `1:02:03` → milisegundos; null si no es un tiempo. */
    fun tiempoDe(dentro: String): Int? {
        val partes = dentro.split(':')
        if (partes.size !in 2..3 || partes.any { it.isEmpty() }) return null
        val ultimo = partes.last()
        val segundos = ultimo.substringBefore('.')
        val fraccion = ultimo.substringAfter('.', "")
        if (segundos.length != 2 || !segundos.all { it.isDigit() }) return null
        if (fraccion.length > 3 || !fraccion.all { it.isDigit() }) return null
        if (!partes.dropLast(1).all { p -> p.all { it.isDigit() } && p.length <= 3 }) return null
        val s = segundos.toInt()
        if (s > 59) return null
        val (h, m) = if (partes.size == 3) {
            if (partes[1].length != 2 || partes[1].toInt() > 59) return null
            partes[0].toInt() to partes[1].toInt()
        } else 0 to partes[0].toInt()
        val ms = if (fraccion.isEmpty()) 0 else fraccion.padEnd(3, '0').toInt()
        return ((h * 3600 + m * 60 + s) * 1000) + ms
    }

    /**
     * **Lo que se guarda en [Mensaje.transcripcion]**: con tiempos, `[m:ss] línea` y un renglón en
     * blanco entre líneas (lo que leen el PC y la pantalla de la letra); sin tiempos, el texto tal
     * cual, línea a línea. Una línea con tiempo y sin texto (un silencio del LRC) se guarda solo con
     * su marca: así la que sonaba deja de resaltarse.
     */
    fun aGuardar(letra: Letra): String {
        if (!letra.conTiempos) return letra.lineas.joinToString("\n") { it.texto }.trim()
        return letra.lineas.mapNotNull { l ->
            // Un párrafo no puede llevar renglones en blanco dentro: partiría la línea en dos.
            val t = l.texto.lines().filter { it.isNotBlank() }.joinToString("\n")
            when {
                l.ms != null -> if (t.isEmpty()) "[${marca(l.ms)}]" else "[${marca(l.ms)}] $t"
                t.isEmpty() -> null
                else -> t
            }
        }.joinToString("\n\n")
    }

    /**
     * El minuto como lo escriben el teléfono y el PC: `1:23`, `1:02:09`. **Redondeado al segundo
     * más cercano**, no truncado: así la línea se resalta como mucho medio segundo antes o después.
     */
    fun marca(ms: Int): String {
        val s = (ms + 500) / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val seg = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, seg) else String.format(Locale.ROOT, "%d:%02d", m, seg)
    }

    /** **La letra en LRC** (`[mm:ss.xx]línea`), para copiarla a otra aplicación de karaoke. */
    fun aLrc(letra: Letra, titulo: String? = letra.titulo, artista: String? = letra.artista): String = buildString {
        titulo?.takeIf { it.isNotBlank() }?.let { append("[ti:").append(it).append("]\n") }
        artista?.takeIf { it.isNotBlank() }?.let { append("[ar:").append(it).append("]\n") }
        letra.lineas.forEach { l ->
            val t = l.texto.lines().filter { it.isNotBlank() }.joinToString(" / ")
            if (l.ms != null) {
                val cs = l.ms / 10
                append(String.format(Locale.ROOT, "[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100)).append(t).append('\n')
            } else if (t.isNotEmpty()) append(t).append('\n')
        }
    }.trimEnd()

    /**
     * **La línea que suena en [ms]**: la última cuyo tiempo ya pasó. -1 antes de la primera o si
     * no hay tiempos (resaltar una al azar sería mentir). Es la misma regla que el PC
     * (`voz.rs::por_donde_va`) y que la pantalla de la letra.
     */
    fun lineaActual(lineas: List<Linea>, ms: Int): Int = lineas.indexOfLast { it.ms != null && it.ms <= ms }

    /**
     * Si un texto del portapapeles parece una letra: más de tres renglones con algo, o un LRC.
     * Es lo que decide si se ofrece «Pegar la letra copiada» al añadir una canción.
     */
    fun pareceLetra(texto: String?): Boolean {
        if (texto.isNullOrBlank()) return false
        if (leer(texto).conTiempos) return true
        return texto.lines().count { it.isNotBlank() } >= 4
    }
}

/**
 * **Sincronizar una letra cantándola** (27-oct-2026): con la canción sonando, un toque al empezar
 * cada línea le pone su tiempo. Es lo que hace quien escribe LRC a mano, sin tener que saber qué
 * es un LRC. Puro: la pantalla le da el milisegundo del reproductor ([marcar]) y al acabar saca la
 * letra con tiempos ([letra]).
 *
 * [textos]: las líneas de la letra, sin las vacías (no se canta un renglón en blanco).
 */
class SincronizadorDeLetra(textos: List<String>) {
    val textos: List<String> = textos.map { it.trim() }.filter { it.isNotEmpty() }
    private val tiempos = arrayOfNulls<Int>(this.textos.size)

    /** La línea que toca marcar ahora. Igual al número de líneas: ya están todas. */
    var siguiente: Int = 0
        private set

    val terminado: Boolean get() = siguiente >= textos.size

    /**
     * Pone [ms] a la línea que toca y pasa a la siguiente. Nunca antes que la anterior (un toque
     * después de saltar atrás no puede desordenar la letra). Devuelve si quedan líneas.
     */
    fun marcar(ms: Int): Boolean {
        if (terminado) return false
        val anterior = if (siguiente > 0) tiempos[siguiente - 1] ?: 0 else 0
        tiempos[siguiente] = maxOf(ms.coerceAtLeast(0), anterior)
        siguiente++
        return !terminado
    }

    /**
     * Quita la última marca. Devuelve a qué milisegundo conviene volver para repetirla (un poco
     * antes de la anterior, para cogerla con tiempo), o null si no había ninguna.
     */
    fun deshacer(): Int? {
        if (siguiente == 0) return null
        siguiente--
        tiempos[siguiente] = null
        val previa = if (siguiente > 0) tiempos[siguiente - 1] ?: 0 else 0
        return (previa - 2000).coerceAtLeast(0)
    }

    /** El tiempo puesto a la línea [i], o null. */
    fun tiempo(i: Int): Int? = tiempos.getOrNull(i)

    /** La letra con lo marcado: las que no se llegaron a marcar van detrás, sin tiempo. */
    fun letra(): LetraDeCancion.Letra = LetraDeCancion.Letra(textos.mapIndexed { i, t -> LetraDeCancion.Linea(tiempos[i], t) })

    companion object {
        /** Las líneas para sincronizar de una letra ya guardada (con o sin tiempos). */
        fun de(letra: LetraDeCancion.Letra): SincronizadorDeLetra =
            SincronizadorDeLetra(letra.lineas.flatMap { it.texto.lines() })
    }
}
