package com.forge.pixpin.sincro

import com.forge.pixpin.capture.CaducidadDeCapturas
import com.forge.pixpin.capture.GaleriaCompartida
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * **Las capturas de este aparato**, para que la galería viaje sin que la sincronización sepa de
 * MediaStore: en el teléfono es `capture/CapturasEnElTelefono`; en las pruebas, una carpeta.
 */
interface CapturasDelAparato {
    /** Donde viven el registro de caducidad y el estado compartido (`filesDir`). */
    val raiz: File
    /** Las que hay ahora. null si no se pudo preguntar (no es «ninguna»). */
    fun listar(): List<GaleriaCompartida.Local>?
    /** El contenido de una, con su largo; null si no está. */
    fun abrir(nombre: String): Pair<Long, InputStream>?
    /** Guarda una que llega, con lo que dice su entrada. Devuelve si quedó entera. */
    fun guardar(e: GaleriaCompartida.Entrada, escribir: (OutputStream) -> Boolean): Boolean
    /** A la papelera (o fuera, donde no la hay). */
    fun tirar(nombres: Collection<String>)
    /** «Días hasta borrar» de aquí (0 = nunca). */
    fun dias(): Int
    /**
     * El texto de una captura que ya está aquí, llegado de otro aparato: a donde lo busque la
     * galería. En el teléfono no hace falta (la galería lo lee del estado compartido, que ya lo
     * guarda); las que llegan enteras traen el suyo en la entrada de [guardar].
     */
    fun apuntarTexto(nombre: String, texto: String) {}
}

/**
 * **La galería al sincronizar** (ver [GaleriaCompartida]). Es un paso más de cada vuelta, detrás
 * de los chats, y opcional en el cable como `suelto`: un aparato que no lo conoce (el PC de hoy)
 * contesta «No sé qué es «galeria»» y se sigue sin galería. `Protocolo.VERSION` no cambia.
 *
 * ```text
 * dirige → {"t":"galeria"}                    ← {"galeria":"[entradas]","tengo":[nombres]}
 *          {"t":"galeriajunta","parche":"[juntas]"}  ← {}   (el otro tira, apunta fechas)
 *          {"t":"damecaptura","ruta":n}       ← {"bytes":N} + TROZOS + cola
 *          {"t":"poncaptura","ruta":n,"bytes":N} + TROZOS + cola  ← {"saltado":…}
 * ```
 */
object GaleriaQueViaja {

    /** Lo que se pide para empezar. */
    const val PETICION = "galeria"

    /** Lo de aquí, al día y escrito, con el registro de caducidad ya con lo acordado. */
    fun ponerAlDia(c: CapturasDelAparato, aparato: String, ahora: Long): GaleriaCompartida.Estado {
        val locales = c.listar()
        val dias = c.dias()
        val r = CaducidadDeCapturas.leer(c.raiz, ahora)
        val e = GaleriaCompartida.cambiar(c.raiz) { GaleriaCompartida.alDia(it, locales, r, dias, ahora, aparato) }
        CaducidadDeCapturas.cambiar(c.raiz, ahora) { GaleriaCompartida.aplicar(it, e.entradas) }
        return e
    }

    /**
     * **Lo juntado, aquí**: se guardan las entradas, se apunta lo acordado en el registro y lo
     * quitado en otro aparato va a la papelera. Devuelve cuántas se tiraron.
     */
    fun aplicarJuntas(c: CapturasDelAparato, juntas: List<GaleriaCompartida.Entrada>, ahora: Long): Int {
        val locales = c.listar().orEmpty()
        val aqui = locales.map { it.nombre }.toSet()
        val tirar = GaleriaCompartida.aTirar(juntas, aqui)
        if (tirar.isNotEmpty()) c.tirar(tirar)
        // Lo juntado por quien no sabe de textos (un Android o un PC de antes) llega sin ellos: se
        // completan con lo que ya había aquí, en el estado o leído de la captura, para no olvidarlos.
        val textoLocal = locales.filter { it.texto.isNotEmpty() }.associate { it.nombre to it.texto }
        val e = GaleriaCompartida.cambiar(c.raiz) { viejo ->
            val textoViejo = viejo.entradas.filter { it.texto.isNotEmpty() }.associate { it.nombre to it.texto }
            val completas = juntas.map { j ->
                if (j.texto.isNotEmpty()) j
                else (textoViejo[j.nombre] ?: textoLocal[j.nombre])?.let { j.copy(texto = it) } ?: j
            }
            viejo.copy(entradas = completas, tenia = viejo.tenia - tirar.toSet())
        }
        // Y al revés: el texto que trae el otro de una captura que ya está aquí pero aún no se leyó.
        for (j in e.entradas) {
            if (!j.borrada && j.texto.isNotEmpty() && j.nombre in aqui && j.nombre !in textoLocal) c.apuntarTexto(j.nombre, j.texto)
        }
        CaducidadDeCapturas.cambiar(c.raiz, ahora) { GaleriaCompartida.aplicar(it, e.entradas) }
        return tirar.size
    }

    /** Una que acaba de llegar: desde ahora es de las que hay aquí (si se quita, se quita en todos). */
    fun apuntarQueLlego(c: CapturasDelAparato, nombre: String) {
        GaleriaCompartida.cambiar(c.raiz) { it.copy(tenia = it.tenia + nombre) }
    }

    /** El nombre que viene del otro, comprobado: nada de rutas. */
    fun nombreValido(n: String?): String {
        require(!n.isNullOrBlank() && n == Envio.nombreSano(n) && n != "." && n != "..") { "Nombre de captura no válido" }
        return n
    }
}
