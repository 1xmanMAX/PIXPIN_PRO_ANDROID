package com.forge.pixpin.asistente.web

/**
 * **Qué direcciones se pueden abrir** (28-oct-2026): la guarda contra SSRF, `direccion.rs` de
 * `crates/pixpin-paginas` del PC portado tal cual, con sus pruebas (`DireccionWebTest`).
 *
 * El asistente abre lo que le pide un modelo, y un modelo puede haber leído una página maliciosa.
 * Así que una dirección solo se abre si:
 *
 * - es `http://` o `https://` (nada de `file:`, `javascript:`, `data:`, `content:`, `intent:`…);
 * - no lleva usuario ni contraseña (`https://yo:clave@…`);
 * - el servidor no es de este teléfono ni de la red de casa: ni `localhost`, ni nombres sin punto
 *   (`router`, `nas`), ni `.local`/`.lan`/`.internal`/`.home.arpa`, ni una IP privada, de enlace
 *   local, CGNAT, de documentación, multicast o reservada ([ipPublica]), escrita como sea
 *   (`127.1`, `0x7f000001` y `2130706433` se rechazan: no son la forma normal);
 * - y, ya resuelto el nombre, **todas** sus IP son públicas ([comprobarIps]): un dominio público
 *   que apunta a `192.168.1.1` tampoco pasa. Lo mismo en cada redirección, que se siguen a mano.
 *
 * Todo es texto y números: se prueba sin red. Las IP se leen aquí, a mano y estrictas: nunca con
 * `InetAddress.getByName`, que con un texto raro preguntaría al DNS.
 */
object Direccion {

    /** Lo que no se abre, con el motivo en palabras para el modelo. */
    class NoSeAbre(motivo: String) : Exception(motivo)

    /** Una dirección ya comprobada. */
    data class Url(
        val segura: Boolean,
        /** En minúsculas; una IPv6 sin corchetes. */
        val servidor: String,
        val puerto: Int,
        /** Ruta y consulta (`/a/b?c=1`), sin el `#fragmento`. Siempre empieza por `/` y va en ASCII. */
        val ruta: String
    ) {
        /** La dirección entera, tal como se pide. */
        fun texto(): String {
            val esquema = if (segura) "https" else "http"
            val s = if (':' in servidor) "[$servidor]" else servidor
            val defecto = if (segura) 443 else 80
            return if (puerto == defecto) "$esquema://$s$ruta" else "$esquema://$s:$puerto$ruta"
        }

        /** El dominio que se le enseña al usuario («🌐 leyendo ugr.es…»). */
        fun dominio(): String = servidor.removePrefix("www.")

        /** La ruta sin la consulta. */
        internal fun camino(): String = ruta.substringBefore('?')

        /** La IP, si el servidor se escribió como IP. */
        fun ip(): Ip? = Ip.de(servidor)
    }

    /** **Una IP** (4 u 16 bytes), leída estricta: sin ceros delante, sin formas raras. */
    class Ip(val bytes: ByteArray) {
        init { require(bytes.size == 4 || bytes.size == 16) }

        override fun toString(): String = if (bytes.size == 4) bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
        else (0 until 8).joinToString(":") { Integer.toHexString(segmento(it)) }

        internal fun segmento(i: Int) = ((bytes[2 * i].toInt() and 0xff) shl 8) or (bytes[2 * i + 1].toInt() and 0xff)

        companion object {
            /** La IP escrita en [texto] (IPv4 con puntos o IPv6), o null si no es una. */
            fun de(texto: String): Ip? = (v4(texto) ?: v6(texto))?.let(::Ip)

            /** `a.b.c.d`: cuatro números de 0 a 255, sin ceros delante (como `Ipv4Addr` de Rust). */
            internal fun v4(s: String): ByteArray? {
                val partes = s.split('.')
                if (partes.size != 4) return null
                val b = ByteArray(4)
                for ((i, p) in partes.withIndex()) {
                    if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
                    if (p.length > 1 && p[0] == '0') return null
                    val n = p.toInt()
                    if (n > 255) return null
                    b[i] = n.toByte()
                }
                return b
            }

            /** IPv6 en texto (con `::` y una IPv4 al final si viene); sin zona (`%`). */
            internal fun v6(s: String): ByteArray? {
                if (s.isEmpty() || ':' !in s || '%' in s) return null
                fun grupos(parte: String, v4AlFinal: Boolean): List<Int>? {
                    if (parte.isEmpty()) return emptyList()
                    val trozos = parte.split(':')
                    val out = ArrayList<Int>()
                    for ((i, t) in trozos.withIndex()) {
                        if (i == trozos.lastIndex && v4AlFinal && '.' in t) {
                            val b = v4(t) ?: return null
                            out += ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
                            out += ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
                            continue
                        }
                        if (t.length !in 1..4 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                        out += t.toInt(16)
                    }
                    return out
                }
                val dobles = s.split("::")
                val todos: List<Int> = when (dobles.size) {
                    1 -> grupos(s, true)?.takeIf { it.size == 8 } ?: return null
                    2 -> {
                        val izq = grupos(dobles[0], false) ?: return null
                        val der = grupos(dobles[1], true) ?: return null
                        if (izq.size + der.size > 7) return null
                        izq + List(8 - izq.size - der.size) { 0 } + der
                    }
                    else -> return null
                }
                val b = ByteArray(16)
                for ((i, g) in todos.withIndex()) { b[2 * i] = (g shr 8).toByte(); b[2 * i + 1] = g.toByte() }
                return b
            }
        }
    }

    /** Los finales de nombre que son de la red de casa o de una empresa. */
    private val FINALES_LOCALES = listOf(".localhost", ".local", ".lan", ".home", ".internal", ".intranet", ".corp", ".home.arpa", ".localdomain")

    private fun minusculasAscii(s: String) = buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + 32 else c) }

    private fun esAlfanumericoAscii(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /**
     * **Lee y comprueba una dirección.** Sin esquema y con pinta de dominio (`ugr.es/horarios`) se
     * toma `https://`. Lanza [NoSeAbre] con el motivo en palabras para el modelo.
     */
    fun leer(texto: String): Url {
        val t = texto.trim().trim('<', '>', '"', '\'', '«', '»')
        if (t.isEmpty()) throw NoSeAbre("falta la direccion")
        if (t.any { it.isISOControl() }) throw NoSeAbre("la direccion lleva caracteres de control")
        val minus = minusculasAscii(t)
        val i = t.indexOf(':')
        val (segura, resto) = when {
            minus.startsWith("https://") -> true to t.substring(8)
            minus.startsWith("http://") -> false to t.substring(7)
            i >= 0 && t.substring(0, i).all { esAlfanumericoAscii(it) || it == '+' || it == '-' || it == '.' } &&
                !(i + 1 < t.length && t[i + 1] in '0'..'9') ->
                throw NoSeAbre("solo se abren direcciones http:// o https:// (no «${t.substring(0, i)}:»)")
            '.' in t && ' ' !in t -> true to t
            else -> throw NoSeAbre("«$t» no es una direccion web")
        }
        val finAutoridad = resto.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) resto.length else it }
        val autoridad = resto.substring(0, finAutoridad)
        val ruta0 = resto.substring(finAutoridad)
        if ('@' in autoridad) throw NoSeAbre("no se abren direcciones con usuario o contrasena")
        val (servidor, puerto) = partirAutoridad(autoridad, segura)
        comprobarServidor(servidor)
        val sinAncla = ruta0.substringBefore('#')
        val ruta = when {
            sinAncla.isEmpty() -> "/"
            sinAncla.startsWith('?') -> "/$sinAncla"
            else -> sinAncla
        }
        return Url(segura, servidor, puerto, aAscii(ruta))
    }

    /** [leer] sin lanzar: null si no se abre. */
    fun leerONull(texto: String): Url? = try { leer(texto) } catch (_: NoSeAbre) { null }

    private fun puertoDe(p: String): Int {
        if (p.isEmpty() || p.length > 5 || !p.all { it in '0'..'9' }) throw NoSeAbre("el puerto no es valido")
        return p.toInt().takeIf { it <= 65535 } ?: throw NoSeAbre("el puerto no es valido")
    }

    private fun partirAutoridad(autoridad: String, segura: Boolean): Pair<String, Int> {
        val defecto = if (segura) 443 else 80
        val (servidor, puerto) = if (autoridad.startsWith('[')) {
            val cierre = autoridad.indexOf(']')
            if (cierre < 0) throw NoSeAbre("una IPv6 sin cerrar el corchete")
            val ip = autoridad.substring(1, cierre)
            val resto = autoridad.substring(cierre + 1)
            val puerto = when {
                resto.startsWith(':') -> puertoDe(resto.substring(1))
                resto.isEmpty() -> defecto
                else -> throw NoSeAbre("la direccion no es valida")
            }
            ip to puerto
        } else {
            val j = autoridad.lastIndexOf(':')
            when {
                j < 0 -> autoridad to defecto
                j == autoridad.length - 1 -> autoridad.substring(0, j) to defecto
                else -> autoridad.substring(0, j) to puertoDe(autoridad.substring(j + 1))
            }
        }
        val s = minusculasAscii(servidor.trimEnd('.'))
        if (s.isEmpty()) throw NoSeAbre("falta el servidor en la direccion")
        if (puerto == 0) throw NoSeAbre("el puerto no es valido")
        return s to puerto
    }

    /** Lo que se comprueba del nombre o la IP del servidor, antes de resolver. */
    private fun comprobarServidor(servidor: String) {
        val no = NoSeAbre("«$servidor» es de este telefono o de la red local: el asistente solo abre paginas publicas de internet")
        Ip.de(servidor)?.let { if (ipPublica(it)) return else throw no }
        if (!servidor.all { it.code < 128 }) throw NoSeAbre("«$servidor» lleva letras fuera de ASCII: escribe el dominio en punycode (xn--...)")
        if (!servidor.all { esAlfanumericoAscii(it) || it == '-' || it == '.' || it == '_' } || servidor.split('.').any { it.isEmpty() })
            throw NoSeAbre("«$servidor» no es un nombre de servidor valido")
        // Una IP escrita de otra forma (`127.1`, `0x7f.0.0.1`, `2130706433`): el sistema la
        // entendería como IP; aquí no se acepta.
        val ultima = servidor.substringAfterLast('.')
        if (ultima.all { it in '0'..'9' } ||
            (ultima.startsWith("0x") && ultima.substring(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        ) throw NoSeAbre("«$servidor» parece una IP escrita de forma rara: usa el nombre del sitio")
        if (servidor == "localhost" || '.' !in servidor || FINALES_LOCALES.any { servidor.endsWith(it) }) throw no
    }

    /**
     * **Si una IP es de internet** (y no de este teléfono, de la red de casa, de documentación,
     * multicast o reservada).
     */
    fun ipPublica(ip: Ip): Boolean = if (ip.bytes.size == 4) v4Publica(ip.bytes) else v6Publica(ip)

    private fun v4Publica(o: ByteArray): Boolean {
        val a = o[0].toInt() and 0xff
        val b = o[1].toInt() and 0xff
        val c = o[2].toInt() and 0xff
        return !(a == 0 ||
            a == 10 ||
            a == 127 ||
            (a == 100 && b in 64 until 128) ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16 until 32) ||
            (a == 192 && b == 0 && (c == 0 || c == 2)) ||
            (a == 192 && b == 88 && c == 99) ||
            (a == 192 && b == 168) ||
            (a == 198 && (b == 18 || b == 19)) ||
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113) ||
            a >= 224)
    }

    private fun v6Publica(ip: Ip): Boolean {
        val s = IntArray(8) { ip.segmento(it) }
        val embebida = { hi: Int, lo: Int -> byteArrayOf((hi shr 8).toByte(), hi.toByte(), (lo shr 8).toByte(), lo.toByte()) }
        // Las que llevan una IPv4 dentro: lo que diga esa IPv4.
        if ((0 until 5).all { s[it] == 0 } && s[5] == 0xffff) return v4Publica(embebida(s[6], s[7]))
        // `::`, `::1` y las «compatibles» `::a.b.c.d` (obsoletas).
        if ((0 until 6).all { s[it] == 0 }) return false
        // NAT64.
        if (s[0] == 0x64 && s[1] == 0xff9b && (2 until 6).all { s[it] == 0 }) return v4Publica(embebida(s[6], s[7]))
        // 6to4.
        if (s[0] == 0x2002) return v4Publica(embebida(s[1], s[2]))
        return !((s[0] and 0xfe00) == 0xfc00 || // únicas locales
            (s[0] and 0xffc0) == 0xfe80 || // de enlace local
            (s[0] and 0xffc0) == 0xfec0 || // de sitio (obsoletas)
            (s[0] and 0xff00) == 0xff00 || // multicast
            (s[0] == 0x2001 && s[1] == 0x0db8) || // documentación
            (s[0] == 0x2001 && s[1] == 0) || // Teredo
            s[0] == 0x100) // descarte
    }

    /**
     * **Las IP a las que resolvió el nombre**: todas tienen que ser públicas (si una no lo es,
     * alguien podría hacer que se use esa). Lanza [NoSeAbre].
     */
    fun comprobarIps(servidor: String, ips: List<Ip>) {
        if (ips.isEmpty()) throw NoSeAbre("no se encontro el servidor «$servidor»")
        if (!ips.all(::ipPublica)) throw NoSeAbre("«$servidor» apunta a una direccion de este telefono o de la red local: no se abre")
    }

    /** Pasa a `%XX` lo que no es ASCII imprimible (espacios, tildes), sin tocar los `%XX` que ya vengan. */
    private fun aAscii(s: String): String {
        val o = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val x = b.toInt() and 0xff
            if (x in 0x21..0x7e) o.append(x.toChar()) else o.append('%').append(String.format("%02X", x))
        }
        return o.toString()
    }

    /** Codifica un texto para ponerlo en la consulta de una URL (`?q=…`). */
    fun codificar(texto: String): String {
        val o = StringBuilder(texto.length * 3)
        for (b in texto.toByteArray(Charsets.UTF_8)) {
            val x = b.toInt() and 0xff
            val c = x.toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' -> o.append(c)
                c == ' ' -> o.append('+')
                else -> o.append('%').append(String.format("%02X", x))
            }
        }
        return o.toString()
    }

    /** Deshace `%XX` (y `+` como espacio si [masEsEspacio]). */
    fun decodificar(texto: String, masEsEspacio: Boolean): String {
        val b = texto.toByteArray(Charsets.UTF_8)
        val o = java.io.ByteArrayOutputStream(b.size)
        fun hex(c: Byte): Int? = Character.digit(c.toInt().toChar(), 16).takeIf { it >= 0 && c >= 0 }
        var i = 0
        while (i < b.size) {
            val c = b[i]
            if (c == '%'.code.toByte() && i + 2 < b.size) {
                val h = hex(b[i + 1])
                val l = hex(b[i + 2])
                if (h != null && l != null) { o.write(h * 16 + l); i += 3; continue }
                o.write(c.toInt())
            } else if (c == '+'.code.toByte() && masEsEspacio) o.write(' '.code)
            else o.write(c.toInt())
            i++
        }
        return String(o.toByteArray(), Charsets.UTF_8)
    }

    /**
     * **Un enlace de la página hecho dirección entera**: absoluto, `//servidor`, `/ruta`,
     * `?consulta` o relativo (con `./` y `../`). null para lo que no es una página (`#ancla`,
     * `mailto:`, `javascript:`, `tel:`).
     */
    fun resolver(base: Url, enlace: String): String? {
        val e = enlace.trim()
        if (e.isEmpty() || e.startsWith('#')) return null
        val minus = minusculasAscii(e)
        if (minus.startsWith("http://") || minus.startsWith("https://")) return e
        if (e.startsWith("//")) return (if (base.segura) "https:" else "http:") + e
        val dosPuntos = e.indexOf(':')
        if (dosPuntos >= 0 && '/' !in e.substring(0, dosPuntos) && '?' !in e.substring(0, dosPuntos)) return null
        val origen = base.copy(ruta = "").texto()
        val sin = e.substringBefore('#')
        if (sin.startsWith('/')) return origen + limpiarPuntos(sin)
        if (sin.startsWith('?')) return origen + base.camino() + sin
        val camino = base.camino()
        val carpeta = camino.substring(0, camino.lastIndexOf('/') + 1)
        return origen + limpiarPuntos(carpeta + sin)
    }

    /** Quita los `.` y `..` de una ruta (la consulta, tal cual). */
    private fun limpiarPuntos(ruta: String): String {
        val q = ruta.indexOf('?')
        val camino = if (q >= 0) ruta.substring(0, q) else ruta
        val consulta = if (q >= 0) ruta.substring(q) else ""
        val partes = ArrayList<String>()
        val trozos = camino.split('/')
        for ((i, t) in trozos.withIndex()) {
            when (t) {
                "." -> if (i + 1 == trozos.size) partes += ""
                ".." -> {
                    if (partes.size > 1) partes.removeAt(partes.lastIndex)
                    if (i + 1 == trozos.size) partes += ""
                }
                else -> partes += t
            }
        }
        var s = partes.joinToString("/")
        if (!s.startsWith('/')) s = "/$s"
        return s + consulta
    }

    /**
     * **La forma de comparar dos direcciones**: sin esquema, sin `www.`, sin `#`, sin la barra
     * final y con el servidor en minúsculas. Para saber si una dirección ya salió en la conversación.
     */
    fun paraComparar(texto: String): String? {
        val u = leerONull(texto) ?: return null
        val puerto = if (u.puerto == 443 || u.puerto == 80) "" else ":${u.puerto}"
        return u.dominio() + puerto + u.ruta.trimEnd('/')
    }
}
