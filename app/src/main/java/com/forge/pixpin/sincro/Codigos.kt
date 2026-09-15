package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * **Los tres códigos que dicen qué es cada cosa, esté donde esté** (idea del usuario, 15-sep-2026).
 *
 * Todo lo que entra al chat —un lienzo, una tabla, un croquis, un PDF, una nota— nace con tres
 * códigos que **no cambian nunca**, se comparta las veces que se comparta:
 *
 * 1. **El código único** ([Mensaje.uid], [Hoja.uid], [Proyecto.uid]): diez signos al azar, oculto.
 *    Es el que se busca primero al recibir algo: si aquí hay algo con el mismo, es candidato a
 *    ponerse al día. Con 31 signos, diez dan ~8·10¹⁴ combinaciones: aunque un aparato cree un millón
 *    de cosas, la probabilidad de que dos coincidan es de una entre mil seiscientas veces eso.
 * 2. **El código de chat** ([deChat]): el número del mensaje y el código del aparato donde nació,
 *    `47·K7Q2`. Se ve en el chat. Antes era una letra por aparato dentro de un grupo (`47a`), que
 *    fuera del grupo no decía nada: la `a` de otra persona es otro teléfono.
 * 3. **La fecha de creación** ([Mensaje.cuando]): se ve en el chat, y la cosa aparece en ese sitio
 *    del chat en todos los aparatos.
 *
 * **Solo se pone al día algo si coinciden los tres** ([mismos]). Si falta uno o no cuadra, se crea
 * aparte: ante la duda, duplicar y no pisar. Y **copiar a propósito** da tres códigos nuevos, o la
 * copia y el original se pisarían entre sí.
 *
 * Lo guardado antes de esto no los tenía: se le ponen una vez y **sacados de su id** ([de]), no al
 * azar, para que dos aparatos que ya tenían lo mismo (mismo id, porque lo sincronizaron) le pongan
 * el mismo código cada uno por su lado sin hablarse.
 */
object Codigos {

    const val LARGO = 10

    fun nuevo(azar: SecureRandom = AZAR): String =
        String(CharArray(LARGO) { Grupo.SIGNOS[azar.nextInt(Grupo.SIGNOS.length)] })

    /** Un código fijo sacado de [semilla]: el mismo en todos los aparatos. */
    fun de(semilla: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(semilla.toByteArray())
        return String(CharArray(LARGO) { Grupo.SIGNOS[(h[it].toInt() and 0xff) % Grupo.SIGNOS.length] })
    }

    /** El separador del código de chat: se lee como «cuarenta y siete de K7Q2». */
    const val PUNTO = '·'

    /** `47·K7Q2`; el de antes, `47a`; null si aún no tiene número. */
    fun deChat(m: Mensaje): String? = when {
        m.numero <= 0 -> null
        m.aparato != null -> "${m.numero}$PUNTO${m.aparato}"
        m.letra != null -> "${m.numero}${m.letra}"
        else -> null
    }

    /** El código único de un mensaje: el suyo, o el que sale de su id si es de antes. */
    fun unico(m: Mensaje): String = m.uid ?: de("m:" + m.id)

    fun unico(h: Hoja): String = h.uid ?: de("h:" + h.id)

    fun unico(p: Proyecto): String = p.uid ?: de("p:" + p.id)

    /**
     * **Si dos mensajes son la misma cosa**: los tres códigos iguales. Es lo único que deja que lo
     * que llega ponga al día lo que hay.
     */
    fun mismos(a: Mensaje, b: Mensaje): Boolean =
        unico(a) == unico(b) && deChat(a) != null && deChat(a) == deChat(b) && a.cuando == b.cuando

    /** Un proyecto: su código único, su fecha de creación y el aparato donde nació. */
    fun mismos(a: Proyecto, b: Proyecto): Boolean =
        unico(a) == unico(b) && a.creado == b.creado && a.aparato == b.aparato

    /**
     * **Le pone a un mensaje los códigos que le falten.** El número lo pone quien lo guarda (es de su
     * conversación); aquí, el código único y el aparato. Lo que ya los tiene no se toca.
     */
    fun sellar(m: Mensaje, aparato: String?): Mensaje {
        val uid = m.uid ?: unico(m)
        val suAparato = m.aparato ?: if (m.letra == null) aparato else null
        return if (uid == m.uid && suAparato == m.aparato) m else m.copy(uid = uid, aparato = suAparato)
    }

    /** Lo mismo con un proyecto y sus hojas. La fecha de creación de lo de antes sale de su id, si la lleva. */
    fun sellar(p: Proyecto, aparato: String? = null): Proyecto {
        val hojas = p.hojas.map { h -> if (h.uid != null) h else h.copy(uid = unico(h)) }
        val creado = if (p.creado > 0) p.creado else com.forge.pixpin.guardados.RegistroDelChat.horaEn(p.id) ?: 0L
        val puesto = p.copy(uid = p.uid ?: unico(p), creado = creado, aparato = p.aparato ?: aparato, hojas = hojas)
        return if (puesto == p) p else puesto
    }

    /**
     * **Copiar a propósito: tres códigos nuevos.** Un duplicado con los códigos del original se
     * tomaría por él al compartirse y lo pisaría.
     */
    fun renovar(m: Mensaje): Mensaje = m.copy(uid = nuevo(), numero = 0, letra = null, aparato = null, origen = null)

    /** El código de este aparato (`K7Q2`), o null si no se puede leer su identidad. */
    fun deEsteAparato(filesDir: java.io.File): String? = runCatching { IdentidadEnDisco(filesDir).leer().yo.codigo }.getOrNull()

    private val AZAR = SecureRandom()
}
