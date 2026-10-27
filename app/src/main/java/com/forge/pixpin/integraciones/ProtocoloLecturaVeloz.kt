package com.forge.pixpin.integraciones

import com.forge.pixpin.motor.Lectura
import com.forge.pixpin.motor.Marcas

/**
 * **Las cuentas del protocolo con Lectura Veloz, sin Android** (27-oct-2026, protocolo v1). Lo que
 * toca el sistema —abrir la otra aplicación, el receptor— está en [LecturaVeloz] y
 * [LecturaVelozReceiver]; aquí solo lo que se puede comprobar con pruebas. Documentado en
 * `docs/integracion-lectura-veloz.md`.
 */
object ProtocoloLecturaVeloz {
    const val VERSION = 1
    const val ESQUEMA = "lecturaveloz"
    const val ANFITRION = "pixpin"
    const val RUTA = "/abrir"

    /** La acción del aviso que manda Lectura Veloz a PixPin (difusión explícita a `com.forge.pixpin`). */
    const val ACCION = "com.forge.pixpin.LECTURA_VELOZ"

    const val TIPO_PDF = "pdf"
    const val TIPO_TEXTO = "texto"

    /** Lo que se le manda a Lectura Veloz, en el orden de la dirección. */
    fun parametros(
        uid: String, nombre: String, uri: String, tipo: String,
        pagina: Int, frac: Float, fraccion: Float, numPaginas: Int, t: Long
    ): List<Pair<String, String>> = listOf(
        "v" to VERSION.toString(),
        "doc" to uid,
        "nombre" to nombre,
        "uri" to uri,
        "tipo" to tipo,
        "pagina" to pagina.coerceAtLeast(0).toString(),
        "frac" to fraccionLimpia(frac).toString(),
        "fraccion" to fraccionLimpia(fraccion).toString(),
        "paginas" to numPaginas.coerceAtLeast(0).toString(),
        "t" to t.coerceAtLeast(0L).toString()
    )

    fun fraccionLimpia(f: Float): Float = if (f.isFinite()) f.coerceIn(0f, 1f) else 0f

    /** Por dónde va un PDF de hojas, en fracción del documento entero: `(página + fracción) / páginas`. */
    fun fraccionDelPdf(pagina: Int, frac: Float, paginas: Int): Float =
        if (paginas <= 0) 0f else fraccionLimpia((pagina.coerceAtLeast(0) + fraccionLimpia(frac)) / paginas)

    /**
     * **Un código de documento que se puede creer**: el de [com.forge.pixpin.sincro.Codigos], diez
     * letras o cifras (sin pedir el alfabeto exacto de los grupos, por si otro aparato usa otro).
     * Nada de barras ni puntos: con él se buscan archivos.
     */
    fun uidValido(uid: String?): Boolean =
        uid != null && uid.length == com.forge.pixpin.sincro.Codigos.LARGO && uid.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }

    /** Lo que llega de Lectura Veloz, ya validado y acotado. */
    data class Llegada(
        val doc: String, val tipo: String, val pagina: Int, val frac: Float, val fraccion: Float,
        val t: Long, val palabra: Int, val total: Int
    ) {
        companion object {
            /** Null si no vale: sin documento, con un código raro o un tipo que no es de los dos. */
            fun de(doc: String?, tipo: String?, pagina: Int?, frac: Float?, fraccion: Float?, t: Long?, palabra: Int?, total: Int?): Llegada? {
                val d = doc?.trim()
                if (!uidValido(d)) return null
                val ti = tipo?.trim()?.lowercase()?.takeIf { it == TIPO_PDF || it == TIPO_TEXTO } ?: return null
                val tot = (total ?: 0).coerceAtLeast(0)
                val pal = (palabra ?: 0).coerceAtLeast(0).let { if (tot > 0) it.coerceAtMost(tot) else it }
                // Sin fracción del documento, la de la palabra.
                val fr = fraccion?.takeIf { it.isFinite() }?.let { fraccionLimpia(it) }
                    ?: if (tot > 0) fraccionLimpia(pal.toFloat() / tot) else 0f
                return Llegada(
                    doc = d!!, tipo = ti,
                    pagina = (pagina ?: 0).coerceIn(0, PAGINAS_MAX),
                    frac = fraccionLimpia(frac ?: 0f),
                    fraccion = fr,
                    t = (t ?: 0L).coerceAtLeast(0L),
                    palabra = pal, total = tot
                )
            }
        }
    }

    /** Ninguna hoja de verdad pasa de aquí; un número mayor es un error de quien lo manda. */
    const val PAGINAS_MAX = 100_000

    /**
     * **Dónde vive el verde del documento que se mandó**: lo apunta PixPin al abrir Lectura Veloz
     * y el receptor solo escribe ahí. Así un aviso con un código que PixPin nunca mandó no crea nada.
     *
     * - [HOJAS_ARCHIVO]: un `.marcas` del lector de hojas ([Marcas], `id:x:y:emoji`); [valor] es su ruta.
     * - [HOJAS_PREFS]: lo mismo en las preferencias `marcas` (un PDF que no es del chat); [valor], la clave.
     * - [FRACCIONES_ARCHIVO]: el `.marcas` de un Word o un PowerPoint ([Lectura.Marcador]); [valor], su ruta.
     * - [VOZ]: el verde de la voz de un Word, un libro o un PDF como texto; [valor], la ruta del documento.
     */
    data class Destino(val formato: String, val valor: String) {
        fun aTexto() = "$formato\t$valor"

        companion object {
            const val HOJAS_ARCHIVO = "hojas-archivo"
            const val HOJAS_PREFS = "hojas-prefs"
            const val FRACCIONES_ARCHIVO = "fracciones-archivo"
            const val VOZ = "voz"
            private val FORMATOS = setOf(HOJAS_ARCHIVO, HOJAS_PREFS, FRACCIONES_ARCHIVO, VOZ)

            fun deTexto(t: String?): Destino? {
                val i = t?.indexOf('\t') ?: return null
                if (i <= 0) return null
                val f = t.substring(0, i)
                val v = t.substring(i + 1)
                return if (f in FORMATOS && v.isNotBlank() && v.none { it == '\n' }) Destino(f, v) else null
            }
        }
    }

    /** La clave del registro: un mismo documento puede ir de hojas y como texto, cada uno con su verde. */
    fun claveDelRegistro(uid: String, tipo: String) = "$uid|$tipo"

    /**
     * **El verde de un lector de hojas, movido** a [pagina] + [frac]: los demás marcadores se quedan;
     * el verde de antes se va (hay uno solo).
     */
    fun conVerdeEnHojas(texto: String?, pagina: Int, frac: Float, ahora: Long): String {
        val sinVerde = Marcas.deTexto(texto).filterNot { it.emoji == Lectura.EMOJI_DE_VOZ }
        val (x, y) = Marcas.enLaPagina(pagina, fraccionLimpia(frac).toDouble())
        return Marcas.aTexto(Marcas.con(sinVerde, x, y, Lectura.EMOJI_DE_VOZ, ahora))
    }

    /** Lo mismo con los marcadores en fracción del documento ([Lectura.Marcador]). */
    fun conVerdeEnFracciones(texto: String?, fraccion: Float, ahora: Long): String {
        val sinVerde = Lectura.deTexto(texto).filterNot { it.emoji == Lectura.EMOJI_DE_VOZ }
        return Lectura.aTexto(Lectura.conMarcador(sinVerde, fraccionLimpia(fraccion), Lectura.EMOJI_DE_VOZ, ahora))
    }

    /**
     * **Cuándo se movió el verde** a partir de la [fecha] del archivo donde vive, la que tenía
     * cuando lo [escrito] el último aviso y el [tLecturaVeloz] de ese aviso. Si el archivo no ha
     * cambiado desde el aviso, vale el `t` de Lectura Veloz (no la hora a la que llegó: dos avisos
     * seguidos no se pisan entre sí); si cambió, lo tocó PixPin o el PC y vale su fecha. 0, no se sabe.
     */
    fun cuandoSeMovio(fecha: Long, escrito: Long, tLecturaVeloz: Long): Long = when {
        fecha <= 0L -> tLecturaVeloz.coerceAtLeast(0L)
        tLecturaVeloz > 0L && fecha == escrito -> tLecturaVeloz
        else -> fecha
    }

    /** Si lo que llega es más viejo que lo que ya hay: entonces no se pisa. Un 0 es «no lo sé». */
    fun esViejo(llegada: Long, actual: Long): Boolean = llegada > 0L && actual > 0L && llegada < actual
}
