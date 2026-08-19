package com.forge.pixpin.guardados

/**
 * Los enlaces dentro de una nota: encontrarlos y saber qué enseñar de ellos.
 *
 * ## Qué se puede enseñar sin red
 *
 * En las aplicaciones de mensajería la tarjeta del enlace trae el título y la imagen de la
 * página, y eso sale de **descargar la página**. PixPin no pide permiso de internet —no lo
 * ha necesitado nunca— y añadirlo para adornar un enlace es un intercambio que no se puede
 * hacer sin preguntar: una aplicación que guarda documentos privados y no habla con nadie
 * es una promesa concreta, y cada dirección visitada saldría del teléfono.
 *
 * Así que la tarjeta se construye con lo que **la propia dirección** ya dice, que es más de
 * lo que parece: de dónde es y de qué va. `github.com/forge/pixpin` enseña «github.com» y
 * «forge / pixpin», y con eso uno reconoce el enlace sin abrirlo, que es el noventa por
 * ciento de para qué sirve la tarjeta.
 *
 * ## Por qué es un fichero aparte
 *
 * Porque encontrar una dirección dentro de un texto escrito por una persona está lleno de
 * casos raros —el punto final de la frase, el paréntesis que la envuelve, la coma
 * detrás— y todos se equivocan en silencio: el enlace se abre con un carácter de más y la
 * página no existe. Eso se comprueba, no se supone.
 */

/** Cómo se enseña un enlace en la tarjeta. */
data class Enlace(
    /** La dirección completa, la que se abre. */
    val url: String,
    /** De dónde es: `github.com`. Es lo primero que se lee y lo que da confianza. */
    val donde: String,
    /** De qué va: el camino, con las barras aireadas para poder leerlo. */
    val deQue: String
)

/** Los caracteres que suelen ir pegados al final de una dirección sin ser suyos. */
private const val PEGADOS_AL_FINAL = ".,;:!?)]}\"'»"

private val PROTOCOLO = Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE)
private val SIN_PROTOCOLO = Regex("""\bwww\.\S+""", RegexOption.IGNORE_CASE)

/**
 * El primer enlace del texto, si lo hay.
 *
 * El primero y no todos: la tarjeta es una, y con dos enlaces en una nota lo que se
 * previsualiza en cualquier mensajería es el primero. Los demás siguen ahí, en el texto.
 */
fun primerEnlace(texto: String): Enlace? {
    val encontrado = PROTOCOLO.find(texto) ?: SIN_PROTOCOLO.find(texto) ?: return null
    val crudo = recortarLoPegado(encontrado.value)
    if (crudo.isEmpty()) return null

    val sinProtocolo = crudo.substringAfter("://", crudo)
    val servidor = sinProtocolo.substringBefore('/').substringBefore('?').lowercase()
    if (!servidor.contains('.')) return null
    // El «www.» se enseña quitado, como en cualquier mensajería: no distingue nada —no
    // hay dos sitios que se diferencien en eso— y roba cuatro caracteres de una línea
    // donde lo que importa es reconocer el dominio de un vistazo. La dirección que se
    // abre sí lo conserva: ahí sí puede importar.
    val donde = servidor.removePrefix("www.")

    val camino = sinProtocolo.removePrefix(servidor).trim('/')
    return Enlace(
        url = if (crudo.contains("://")) crudo else "https://$crudo",
        donde = donde,
        // Las barras con aire alrededor: un camino largo pegado es una tira de letras que
        // no se lee, y separado se ve de qué sección es.
        deQue = camino.substringBefore('?').split('/')
            .filter { it.isNotEmpty() }
            .joinToString(" / ")
    )
}

/**
 * Quita lo que la puntuación deja pegado al final.
 *
 * «Mira esto: ejemplo.com/cosa.» acabaría abriendo `cosa.`, que no existe. Los paréntesis
 * se cuentan: si la dirección lleva uno abierto dentro —los enlaces de enciclopedias lo
 * hacen— el que cierra **sí es suyo** y quitarlo rompería el enlace de verdad.
 */
private fun recortarLoPegado(crudo: String): String {
    var fin = crudo.length
    while (fin > 0) {
        val c = crudo[fin - 1]
        if (c !in PEGADOS_AL_FINAL) break
        if (c == ')' && crudo.take(fin).count { it == '(' } > crudo.take(fin).count { it == ')' } - 1) {
            break
        }
        fin--
    }
    return crudo.take(fin)
}
