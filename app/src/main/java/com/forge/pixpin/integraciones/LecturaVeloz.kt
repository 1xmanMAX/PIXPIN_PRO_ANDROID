package com.forge.pixpin.integraciones

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.forge.pixpin.integraciones.ProtocoloLecturaVeloz.Destino
import com.forge.pixpin.motor.Lectura
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import com.forge.pixpin.ui.ExportarDocumentoAnotado
import java.io.File

/**
 * **Leer rápido en Lectura Veloz y volver con el marcador verde puesto** (27-oct-2026, protocolo v1).
 *
 * Lectura Veloz (`com.lecturaveloz.app`) es la otra aplicación del usuario: lee palabra a palabra.
 * Desde un visor de PixPin se le manda el documento, por dónde va (el verde si lo hay) y cuándo se
 * movió ese verde por última vez; ella, al leer, avisa a PixPin en segundo plano
 * ([LecturaVelozReceiver]) y el verde se mueve aquí aunque PixPin esté cerrado. Lo que se puede
 * comprobar sin Android está en [ProtocoloLecturaVeloz]; el protocolo, en
 * `docs/integracion-lectura-veloz.md`.
 */
object LecturaVeloz {
    const val PAQUETE = "com.lecturaveloz.app"
    const val DESCARGA = "https://github.com/1xmanMAX/Speed-Reading-Tool/releases"

    /** La etiqueta que se pone al mensaje leído en Lectura Veloz, si no tenía ninguna. */
    const val ETIQUETA = "⚡"

    private const val TAG = "LecturaVeloz"
    private const val REGISTRO = "lectura_veloz"
    private const val REGISTRO_MAXIMO = 300

    fun instalada(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(PAQUETE, 0); true
    }.getOrDefault(false)

    /**
     * Abre [ruta] en Lectura Veloz. [destino] dice dónde vive el verde de este documento para que el
     * aviso de vuelta lo encuentre; sin él, Lectura Veloz lee pero su posición no vuelve.
     */
    fun abrir(
        ctx: Context, ruta: String, nombre: String, uid: String, tipo: String /* "pdf" | "texto" */,
        pagina: Int, frac: Float, fraccion: Float, numPaginas: Int, t: Long,
        destino: Destino? = null
    ) {
        if (!instalada(ctx)) {
            Toast.makeText(ctx, "Instala Lectura Veloz para usar la lectura rápida", Toast.LENGTH_LONG).show()
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DESCARGA)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val archivo = File(ruta)
        if (!archivo.isFile) {
            Toast.makeText(ctx, "El archivo ya no está", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val contenido = com.forge.pixpin.ui.AbrirCon.uriDe(ctx, archivo)
            val b = Uri.Builder().scheme(ProtocoloLecturaVeloz.ESQUEMA).authority(ProtocoloLecturaVeloz.ANFITRION).path(ProtocoloLecturaVeloz.RUTA)
            for ((k, v) in ProtocoloLecturaVeloz.parametros(uid, nombre, contenido.toString(), tipo, pagina, frac, fraccion, numPaginas, t)) {
                b.appendQueryParameter(k, v)
            }
            val intento = Intent(Intent.ACTION_VIEW, b.build()).apply {
                setPackage(PAQUETE)
                clipData = ClipData.newRawUri(nombre, contenido)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            destino?.let { recordar(ctx, uid, tipo, it) }
            ctx.startActivity(intento)
            // La etiqueta, en segundo plano: reescribir el chat es disco.
            val app = ctx.applicationContext
            Thread { runCatching { etiquetar(app, uid) }.onFailure { Log.w(TAG, "sin etiqueta", it) } }.start()
        }.onFailure {
            Log.w(TAG, "no se pudo abrir", it)
            Toast.makeText(ctx, "No se pudo abrir Lectura Veloz: ${it.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    /** ⚡ al mensaje de [uid], si es del chat y no tiene etiqueta. Una puesta a mano no se pisa. */
    private fun etiquetar(ctx: Context, uid: String) {
        val almacen = com.forge.pixpin.guardados.MensajesStore(ctx)
        val m = almacen.leer().firstOrNull { com.forge.pixpin.sincro.Codigos.unico(it) == uid } ?: return
        if (m.emoji != null) return
        almacen.actualizar(m.id) { if (it.emoji == null) it.copy(emoji = ETIQUETA) else it }
    }

    /**
     * El código con que se manda un documento que no es del chat ni de un proyecto (un PDF abierto
     * desde fuera): sale de su ruta, así que es el mismo cada vez.
     */
    fun codigoDeRuta(ruta: String): String = com.forge.pixpin.sincro.Codigos.de("lectura-veloz:$ruta")

    // ---- El registro: qué se mandó y dónde va su verde. ----

    private fun registro(ctx: Context) = ctx.getSharedPreferences(REGISTRO, Context.MODE_PRIVATE)

    fun recordar(ctx: Context, uid: String, tipo: String, destino: Destino) {
        val p = registro(ctx)
        val clave = ProtocoloLecturaVeloz.claveDelRegistro(uid, tipo)
        val e = p.edit().putString(clave, destino.aTexto()).putLong("cuando:$clave", System.currentTimeMillis())
        // Que no crezca sin fin: se olvidan los más viejos.
        val todas = p.all.keys.filter { it.contains('|') && ':' !in it.substringBefore('|') && it != clave }
        if (todas.size >= REGISTRO_MAXIMO) {
            todas.sortedBy { p.getLong("cuando:$it", 0L) }.take(todas.size - REGISTRO_MAXIMO + 1)
                .forEach { e.remove(it).remove("cuando:$it").remove("t:$it").remove("escrito:$it") }
        }
        e.apply()
    }

    fun destinoDe(ctx: Context, uid: String, tipo: String): Destino? =
        Destino.deTexto(registro(ctx).getString(ProtocoloLecturaVeloz.claveDelRegistro(uid, tipo), null))

    /** El archivo donde vive el verde de [destino], o null si va en las preferencias. */
    private fun archivoDe(ctx: Context, destino: Destino): File? = runCatching {
        when (destino.formato) {
            Destino.HOJAS_ARCHIVO, Destino.FRACCIONES_ARCHIVO -> File(destino.valor)
            Destino.VOZ -> ExportarDocumentoAnotado.baseDe(ctx, File(destino.valor))?.let { AnotacionesDelAdjunto.voz(ctx.filesDir, it) }
            else -> null
        }
    }.getOrNull()

    /**
     * **Cuándo se movió el verde por última vez** (milisegundos), el `t` del protocolo. Si lo último
     * que lo movió fue un aviso de Lectura Veloz, su `t`; si después lo tocó PixPin (o llegó del
     * PC), la fecha del archivo. 0 si no se sabe. Ver [ProtocoloLecturaVeloz.cuandoSeMovio].
     */
    fun cuando(ctx: Context, uid: String, tipo: String, destino: Destino): Long = runCatching {
        val clave = ProtocoloLecturaVeloz.claveDelRegistro(uid, tipo)
        val p = registro(ctx)
        val fecha = archivoDe(ctx, destino)?.takeIf { it.isFile }?.lastModified() ?: 0L
        ProtocoloLecturaVeloz.cuandoSeMovio(fecha, p.getLong("escrito:$clave", 0L), p.getLong("t:$clave", 0L))
    }.getOrDefault(0L)

    /** Un archivo del registro solo vale si es un `.marcas` de lo anotado: nada fuera de esa carpeta. */
    private fun marcasDeConfianza(ctx: Context, ruta: String): File? = runCatching {
        val carpeta = File(ctx.filesDir, AnotacionesDelAdjunto.CARPETA).canonicalFile
        File(ruta).canonicalFile.takeIf { it.parentFile == carpeta && it.name.startsWith("anot-") && it.name.endsWith(".marcas") }
    }.getOrNull()

    /**
     * **Lo que llega de Lectura Veloz, a su sitio.** Disco: fuera del hilo principal. Devuelve si se
     * movió el verde.
     */
    fun aplicar(ctx: Context, l: ProtocoloLecturaVeloz.Llegada): Boolean {
        val destino = destinoDe(ctx, l.doc, l.tipo)
        if (destino == null) {
            Log.w(TAG, "aviso de un documento que no se mandó: ${l.doc} (${l.tipo})")
            return false
        }
        if (ProtocoloLecturaVeloz.esViejo(l.t, cuando(ctx, l.doc, l.tipo, destino))) {
            Log.i(TAG, "aviso más viejo que el verde de aquí: se ignora")
            return false
        }
        val ahora = System.currentTimeMillis()
        when (destino.formato) {
            Destino.HOJAS_ARCHIVO -> {
                val f = marcasDeConfianza(ctx, destino.valor) ?: return false
                AnotacionesDelAdjunto.escribir(f, ProtocoloLecturaVeloz.conVerdeEnHojas(AnotacionesDelAdjunto.leer(f), l.pagina, l.frac, ahora))
            }
            Destino.FRACCIONES_ARCHIVO -> {
                val f = marcasDeConfianza(ctx, destino.valor) ?: return false
                AnotacionesDelAdjunto.escribir(f, ProtocoloLecturaVeloz.conVerdeEnFracciones(AnotacionesDelAdjunto.leer(f), l.fraccion, ahora))
            }
            Destino.HOJAS_PREFS -> {
                val p = ctx.getSharedPreferences("marcas", Context.MODE_PRIVATE)
                // commit: el proceso puede morir en cuanto el receptor acaba.
                p.edit().putString(destino.valor, ProtocoloLecturaVeloz.conVerdeEnHojas(p.getString(destino.valor, null), l.pagina, l.frac, ahora)).commit()
            }
            Destino.VOZ -> {
                val doc = File(destino.valor)
                ExportarDocumentoAnotado.ponerVoz(ctx, "doc:" + doc.absolutePath, Lectura.vozDeFraccion(l.fraccion))
                // Y por dónde se iba leyendo: al abrirlo se vuelve ahí.
                ExportarDocumentoAnotado.guardarSitio(ctx, doc, ExportarDocumentoAnotado.baseDe(ctx, doc), l.fraccion)
            }
            else -> return false
        }
        // Lo que se aplicó y cuándo quedó el archivo: así se sabe si luego lo tocó otro. Ver [cuando].
        val clave = ProtocoloLecturaVeloz.claveDelRegistro(l.doc, l.tipo)
        registro(ctx).edit()
            .putLong("t:$clave", if (l.t > 0L) l.t else ahora)
            .putLong("escrito:$clave", archivoDe(ctx, destino)?.takeIf { it.isFile }?.lastModified() ?: 0L)
            .commit()
        Log.i(TAG, "verde movido: ${l.doc} ${l.tipo} hoja ${l.pagina} + ${l.frac} (${l.fraccion})")
        return true
    }
}
