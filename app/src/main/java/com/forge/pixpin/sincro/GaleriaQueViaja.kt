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
        val aqui = c.listar()?.map { it.nombre }?.toSet() ?: emptySet()
        val tirar = GaleriaCompartida.aTirar(juntas, aqui)
        if (tirar.isNotEmpty()) c.tirar(tirar)
        GaleriaCompartida.cambiar(c.raiz) { it.copy(entradas = juntas, tenia = it.tenia - tirar.toSet()) }
        CaducidadDeCapturas.cambiar(c.raiz, ahora) { GaleriaCompartida.aplicar(it, juntas) }
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
