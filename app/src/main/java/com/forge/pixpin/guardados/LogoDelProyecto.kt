package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **El logo de un proyecto**: una imagen en lugar del círculo con la inicial (4-oct-2026, lo del
 * PC: «el logo de los proyectos está un poco feo, así que dame la opción de ponerle de logo una
 * imagen»). Se pone desde una foto del chat del proyecto.
 *
 * Mismo formato que el PC (`ventana_chat/logo.rs`): un **PNG cuadrado de 256** sacado del centro
 * de la foto. Y como allí, **no viaja**: el PC lo guarda en la carpeta del proyecto y su
 * sincronización no lo mira, así que aquí vive en `files/logos/`, fuera de las carpetas que se
 * sincronizan ([com.forge.pixpin.sincro.Disco.permitida]). Para que viajara harían falta los dos
 * lados a la vez; mandarlo solo desde aquí dejaría al PC con un archivo que no sabe leer.
 *
 * El círculo no se hornea en los píxeles como en el PC: Compose recorta redondo al pintar.
 */
object LogoDelProyecto {
    /** Cambia cada vez que se pone o se quita un logo: quien lo pinta vuelve a mirar. */
    val version = MutableStateFlow(0)

    /** Leídos, por proyecto. Un nulo es «no tiene»: no se vuelve a mirar el disco por él. */
    private val leidos = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<ImageBitmap>>()

    private fun carpeta(context: Context) = File(context.filesDir, "logos")

    /** Dónde vive el logo de un proyecto. El id va limpio: nada de barras. */
    fun archivo(context: Context, proyecto: String): File =
        File(carpeta(context), proyecto.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")

    fun tiene(context: Context, proyecto: String): Boolean = archivo(context, proyecto).isFile

    /**
     * Toma el cuadrado central de [origen], lo reduce a 256 y lo deja de logo. Desde un hilo de
     * disco. Entero o nada: un logo a medio escribir se quedaría roto para siempre.
     */
    fun poner(context: Context, proyecto: String, origen: File): Boolean = runCatching {
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(origen.absolutePath, medidas)
        if (medidas.outWidth <= 0 || medidas.outHeight <= 0) return false
        // No hace falta leer los doce megapíxeles para quedarse con 256.
        val leida = BitmapFactory.decodeFile(
            origen.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = CuentasDelLogo.muestreo(medidas.outWidth, medidas.outHeight) }
        ) ?: return false
        val (x, y, lado) = CuentasDelLogo.cuadradoCentral(leida.width, leida.height)
        if (lado <= 0) return false
        val final = CuentasDelLogo.ladoGuardado(lado)
        val cuadrada = Bitmap.createBitmap(leida, x, y, lado, lado)
        val reducida = if (final < lado) Bitmap.createScaledBitmap(cuadrada, final, final, true) else cuadrada
        val destino = archivo(context, proyecto).also { it.parentFile?.mkdirs() }
        val tmp = File(destino.parentFile, destino.name + ".tmp")
        tmp.outputStream().use { reducida.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(destino)) { tmp.delete(); return false }
        olvidar(proyecto)
        true
    }.getOrElse { false }

    /** Quita el logo: vuelve el círculo de siempre. Quitar lo que no está no es un error. */
    fun quitar(context: Context, proyecto: String) {
        runCatching { archivo(context, proyecto).delete() }
        olvidar(proyecto)
    }

    private fun olvidar(proyecto: String) {
        leidos.remove(proyecto)
        version.value = version.value + 1
    }

    /** El logo leído, o nulo si no tiene. Desde un hilo de disco; después sale de memoria. */
    fun leer(context: Context, proyecto: String): ImageBitmap? {
        leidos[proyecto]?.let { return it.orElse(null) }
        val f = archivo(context, proyecto)
        val b = if (f.isFile) runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() else null
        leidos[proyecto] = java.util.Optional.ofNullable(b)
        return b
    }

    /** Lo ya leído, sin tocar el disco: para el primer fotograma, que no espere. */
    fun enMemoria(proyecto: String): ImageBitmap? = leidos[proyecto]?.orElse(null)
}

/**
 * El logo de [proyecto] para pintarlo, o nulo (y se pinta el círculo de siempre). Se lee fuera
 * del hilo de la pantalla y se vuelve a leer al ponerlo o quitarlo.
 */
@Composable
fun logoDe(proyecto: String?): ImageBitmap? {
    if (proyecto == null) return null
    val contexto = LocalContext.current
    val version by LogoDelProyecto.version.collectAsState()
    val logo by produceState(LogoDelProyecto.enMemoria(proyecto), proyecto, version) {
        value = withContext(Dispatchers.IO) { LogoDelProyecto.leer(contexto, proyecto) }
    }
    return logo
}
