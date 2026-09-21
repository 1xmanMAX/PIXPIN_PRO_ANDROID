package com.forge.pixpin.pdf

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * **pdfsqueeze, en un proceso aparte y con el tiempo contado** (21-sep-2026).
 *
 * La biblioteca entró llamándose desde el mismo hilo que guarda lo que se comparte a PixPin, y el
 * usuario lo notó enseguida: compartía un PDF, tocaba «nuevo proyecto» y no pasaba nada. Un
 * compresor que prueba varias codificaciones por imagen tarda lo que tarda —decenas de segundos
 * en un escaneo—, y si el código nativo se cae se lleva la aplicación entera por delante.
 *
 * Así que corre **aquí**, en el proceso `:pdfsqueeze`: si se cae, se cae solo. Quien lo pide
 * ([comprimir]) espera un tiempo razonable mirando un archivo de aviso; si no llega, mata este
 * proceso y sigue con el compresor en Kotlin. Lo compartido entra siempre.
 */
class PdfSqueezeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entrada = intent?.getStringExtra(ENTRADA)?.let { File(it) }
        val salida = intent?.getStringExtra(SALIDA)?.let { File(it) }
        if (entrada == null || salida == null) { stopSelf(startId); return START_NOT_STICKY }
        Thread {
            try {
                val bytes = entrada.readBytes()
                val ligero = dev.pdfsqueeze.PdfSqueeze.compress(bytes, "{\"profile\":\"balanced\",\"threads\":2}")
                if (ligero.isNotEmpty() && ligero.size < bytes.size) salida.writeBytes(ligero)
            } catch (e: Throwable) {
                salida.delete()
            }
            runCatching { File(salida.path + HECHO).writeText("ok") }
            stopSelf(startId)
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        private const val ENTRADA = "entrada"
        private const val SALIDA = "salida"
        private const val HECHO = ".hecho"
        /** El nombre del proceso, como en el manifiesto. */
        const val PROCESO = ":pdfsqueeze"

        /** Lo que se espera como mucho: dos segundos, más uno por mega, hasta doce. */
        fun espera(bytes: Int): Long = (2_000L + bytes / 1024L).coerceAtMost(12_000L)

        /** Los bytes aligerados, o null si no se pudo a tiempo. Bloquea: fuera del hilo de la pantalla. */
        fun comprimir(contexto: Context, bytes: ByteArray): ByteArray? {
            val carpeta = File(contexto.cacheDir, "pdfsqueeze").apply { mkdirs() }
            carpeta.listFiles()?.forEach { it.delete() }
            val entrada = File(carpeta, "entrada.pdf")
            val salida = File(carpeta, "salida.pdf")
            val hecho = File(salida.path + HECHO)
            val servicio = Intent(contexto, PdfSqueezeService::class.java)
                .putExtra(ENTRADA, entrada.path).putExtra(SALIDA, salida.path)
            return try {
                entrada.writeBytes(bytes)
                // Desde segundo plano Android no deja arrancar servicios: entonces, sin él.
                contexto.startService(servicio)
                val tope = System.currentTimeMillis() + espera(bytes.size)
                while (!hecho.exists() && System.currentTimeMillis() < tope) Thread.sleep(60)
                if (hecho.exists() && salida.isFile) salida.readBytes().takeIf { it.isNotEmpty() && it.size < bytes.size } else null
            } catch (e: Throwable) {
                null
            } finally {
                if (!hecho.exists()) matarElProceso(contexto)
                runCatching { contexto.stopService(servicio) }
                carpeta.listFiles()?.forEach { it.delete() }
            }
        }

        /** Un compresor nativo a medias no se para por las buenas: se mata su proceso, que es solo suyo. */
        private fun matarElProceso(contexto: Context) {
            runCatching {
                val gestor = contexto.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                gestor.runningAppProcesses?.firstOrNull { it.processName == contexto.packageName + PROCESO }
                    ?.let { android.os.Process.killProcess(it.pid) }
            }
        }
    }
}
