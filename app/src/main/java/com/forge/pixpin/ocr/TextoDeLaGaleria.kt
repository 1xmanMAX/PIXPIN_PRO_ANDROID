package com.forge.pixpin.ocr

import android.content.Context
import android.net.Uri
import com.forge.pixpin.capture.GaleriaCompartida
import com.forge.pixpin.ocr.indra.MotorIndra
import java.io.File

/**
 * **El texto de dentro de las capturas, para buscarlas** (26-oct-2026, `lanzar_lector_de_texto`
 * de `galeria_capturas.rs` del PC): con Indra, la galería lee en segundo plano las capturas que aún
 * no tienen texto, de una en una y las más nuevas primero, y lo guarda en dos sitios:
 *
 * - en la caché (`cache/galeria-texto/<nombre>-<bytes>.txt`, como el `.txt` junto a la miniatura
 *   del PC): la segunda vez que se abre la galería no se relee, y una captura leída sin texto
 *   tampoco (fichero vacío);
 * - en su entrada de [GaleriaCompartida] ([GaleriaCompartida.ponerTexto]), **que viaja**: el PC
 *   la encuentra al buscar sin leerla, y al revés —lo que leyó el PC llega aquí y no se relee—.
 *   Mismo formato que el PC ([ParrafosOcr]).
 */
object TextoDeLaGaleria {

    /** Una captura que se puede leer: su nombre, su tamaño (si cambia, se relee), su tipo y cómo abrirla. */
    data class Leible(val nombre: String, val bytes: Long, val mime: String, val uri: Uri?)

    fun carpeta(context: Context) = File(context.applicationContext.cacheDir, "galeria-texto")

    /** El fichero de la caché de una captura; el nombre se limpia (no debe salirse de la carpeta). */
    fun enCache(carpeta: File, nombre: String, bytes: Long) =
        File(carpeta, nombre.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + bytes + ".txt")

    /**
     * **Lo que ya se sabe** de cada captura: el texto de su entrada compartida (puede venir del PC)
     * o el de la caché. Ausente = sin leer. Disco: no en el hilo principal.
     */
    fun conocidos(context: Context, raiz: File, capturas: List<Leible>): Map<String, String> {
        val compartidas = runCatching { GaleriaCompartida.leer(raiz).porNombre }.getOrDefault(emptyMap())
        val dir = carpeta(context)
        val r = HashMap<String, String>()
        for (c in capturas) {
            val t = compartidas[c.nombre]?.texto?.takeIf { it.isNotEmpty() }
                ?: enCache(dir, c.nombre, c.bytes).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            if (t != null) r[c.nombre] = t
        }
        return r
    }

    /** **Las que faltan por leer** (puro): imágenes sin texto conocido, en el orden de la lista (la galería va de la más nueva a la más vieja). */
    fun pendientes(capturas: List<Leible>, conocidos: Set<String>): List<Leible> =
        capturas.filter { it.mime.startsWith("image/") && it.nombre !in conocidos }

    /**
     * **Lee una captura** con Indra y guarda lo leído (caché y entrada compartida). Null si no hay
     * lector o no se pudo (sin caché, por si la próxima vez sí). Hilo de fondo.
     */
    fun leer(context: Context, raiz: File, c: Leible): String? {
        val uri = c.uri ?: return null
        val r = context.applicationContext.contentResolver
        val texto = MotorIndra.textoDeImagen(context) { runCatching { r.openInputStream(uri) }.getOrNull() } ?: return null
        val dir = carpeta(context)
        runCatching { dir.mkdirs(); enCache(dir, c.nombre, c.bytes).writeText(texto) }
        if (texto.isNotBlank()) runCatching { GaleriaCompartida.cambiar(raiz) { GaleriaCompartida.ponerTexto(it, mapOf(c.nombre to texto)) } }
        return texto
    }
}
