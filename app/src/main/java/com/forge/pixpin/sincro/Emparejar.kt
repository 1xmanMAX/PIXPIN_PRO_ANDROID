package com.forge.pixpin.sincro

/**
 * **Emparejar sin la dirección y el QR que lee cualquier cámara** (3-nov-2026, puerto de
 * `pindesk-sincro/src/emparejar.rs` del PC; `docs/emparejar.md`).
 *
 * - [Enlace]: `pindesk://emparejar?v=1&c=<código>&h=<ips>&p=<puerto>&n=<nombre>&e=<caduca>`. Lo
 *   enseña Sincronizar como QR y lo abre la app al leerlo con la cámara del teléfono.
 * - Unirse con el código busca por mDNS **y por la baliza** a la vez ([BalizaDelMovil.buscarParaUnirse]).
 */
object Emparejar {
    const val ESQUEMA = "pindesk"
    const val ANFITRION = "emparejar"
    const val VERSION_ENLACE = 1
    const val TOPE_DIRECCIONES = 4
    const val VIGENCIA_QR_MS = 10 * 60_000L
    const val MARGEN_RELOJ_MS = 5 * 60_000L
    const val PUERTO_POR_OMISION = 47474

    data class Enlace(val codigo: String, val hosts: List<String>, val puerto: Int, val nombre: String, val expira: Long?) {
        fun texto(): String = buildString {
            append("$ESQUEMA://$ANFITRION?v=$VERSION_ENLACE&c=${Grupo.limpiar(codigo)}")
            val h = hosts.take(TOPE_DIRECCIONES)
            if (h.isNotEmpty()) append("&h=" + h.joinToString(","))
            append("&p=$puerto")
            if (nombre.isNotBlank()) append("&n=" + cifrar(nombre.trim()))
            if (expira != null) append("&e=$expira")
        }

        fun vigente(ahora: Long) = expira == null || ahora <= expira + MARGEN_RELOJ_MS

        companion object {
            fun leer(texto: String): Enlace? {
                val t = texto.trim()
                val i = t.indexOf("://")
                if (i < 0) return null
                val esquema = t.substring(0, i)
                if (!esquema.equals(ESQUEMA, true) && !esquema.equals("pixpin", true)) return null
                val resto = t.substring(i + 3)
                val anfitrion = resto.substringBefore('?')
                if (!anfitrion.trimEnd('/').equals(ANFITRION, true)) return null
                val consulta = resto.substringAfter('?', "").substringBefore('#')
                var codigo: String? = null
                var hosts = emptyList<String>()
                var puerto = PUERTO_POR_OMISION
                var nombre = ""
                var expira: Long? = null
                for (par in consulta.split('&')) {
                    if (par.isEmpty()) continue
                    val k = par.substringBefore('=')
                    val v = descifrar(par.substringAfter('=', ""))
                    when (k) {
                        "v" -> if ((v.toIntOrNull() ?: return null) > VERSION_ENLACE) return null
                        "c" -> codigo = Grupo.limpiar(v)
                        "h" -> hosts = v.split(',').map { it.trim() }.filter(::esIpv4).take(TOPE_DIRECCIONES)
                        "p" -> puerto = v.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                        "n" -> nombre = v
                        "e" -> expira = v.toLongOrNull()
                    }
                }
                val c = codigo?.takeIf { it.length == Grupo.LARGO } ?: return null
                return Enlace(c, hosts, puerto, nombre, expira)
            }
        }
    }

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun esIpv4(t: String): Boolean = IPV4.matchEntire(t)?.groupValues?.drop(1)?.all { (it.toIntOrNull() ?: 999) <= 255 } == true

    /** Las direcciones que van en el enlace: solo las de la red local (privadas y 100.64/10), sin repetir, como mucho 4. */
    fun direccionesParaElEnlace(redes: List<String>): List<String> = redes.filter { ip ->
        val o = IPV4.matchEntire(ip)?.groupValues?.drop(1)?.map { it.toInt() } ?: return@filter false
        o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 100 && (o[1] and 0xc0) == 64)
    }.distinct().take(TOPE_DIRECCIONES)

    private fun cifrar(t: String): String = buildString {
        for (b in t.toByteArray()) {
            val c = b.toInt() and 0xff
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) append(c.toChar())
            else append("%%%02X".format(c))
        }
    }

    private fun descifrar(t: String): String {
        val salida = java.io.ByteArrayOutputStream()
        val b = t.toByteArray()
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt().toChar()
            if (c == '%' && i + 2 < b.size + 0 && i + 2 <= b.size - 1) {
                val h = String(b, i + 1, 2).toIntOrNull(16)
                if (h != null) { salida.write(h); i += 3; continue }
            }
            salida.write(if (c == '+') ' '.code else b[i].toInt())
            i++
        }
        return salida.toByteArray().decodeToString()
    }
}
