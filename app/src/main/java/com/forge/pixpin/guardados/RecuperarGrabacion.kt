package com.forge.pixpin.guardados

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.audio.RehacerM4a
import com.forge.pixpin.audio.RespaldoWav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Recuperar una grabación cortada** (28-oct-2026, «Grabar sin perder nada»; `grabar_clase/recuperar.rs`
 * del PC, `d2937bed` y `509c7de8`).
 *
 * Si quedó algún diario ([GrabacionAMedias]) es que una clase o una reunión no llegó a guardarse:
 * Android mató la app, el teléfono se apagó o falló al guardar. Al abrir PixPin (la lista de chats, ver
 * [TarjetasDeGrabacionesCortadas]) —o al volver a crear Android el servicio de grabar sin intent
 * ([avisarSiHay], una notificación)— se mira (una lectura de carpeta) y, por cada una:
 *
 * > **Se cortó una grabación** (Clase 28-10 10h05, 47 min) en «Física» — Recuperar · Descartar · Luego
 *
 * - **Recuperar** ([recuperar]): la nota (la ya cerrada, el `.parte` si llegó a cerrarse, o rehecha del
 *   respaldo WAV), el vídeo (sus trozos juntos), las capturas, las fotos y los clips del diario, **cada
 *   uno en su minuto**, entran en el chat por el mismo camino que «Terminar» ([GuardarLaGrabacion]), con
 *   el reloj de pared del diario en la nota (`reloj`), y se pasa a texto.
 * - **Descartar** se vuelve a preguntar («No» por defecto) y solo entonces se borra lo grabado.
 * - **Luego**: nada; se pregunta otra vez la próxima vez.
 *
 * Si recuperar falla, el diario y lo grabado se quedan como estaban.
 */
object RecuperarGrabacion {
    private const val TAG = "PixPinRecuperar"
    private const val CANAL = "pixpin_cortadas"
    private const val AVISO = 31

    /** Una grabación cortada: su carpeta, su diario y lo que se puede salvar. */
    data class Cortada(val dir: File, val diario: GrabacionAMedias.Diario, val plan: GrabacionAMedias.Plan) {
        val marca: String get() = diario.marca
        /** Solo queda un vídeo que no se abre: lo único que se puede hacer es descartarlo. */
        val perdida: Boolean get() = !plan.algo()
    }

    private val _cortadas = MutableStateFlow<List<Cortada>>(emptyList())
    /** Las que quedan, para la tarjeta. Al día con [refrescar]. */
    val cortadas: StateFlow<List<Cortada>> get() = _cortadas

    private val _recuperando = MutableStateFlow<Set<String>>(emptySet())
    /** Las que se están recuperando ahora (por su marca). */
    val recuperando: StateFlow<Set<String>> get() = _recuperando

    /** Las que se dejaron para luego en esta sesión de la app. */
    private val luego = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * **Las grabaciones cortadas** que hay en el disco, sin la que se está grabando ahora. Las que no
     * guardan nada que salvar (se cortó al abrir el micrófono) se descartan solas, como el PC.
     */
    fun pendientes(context: Context): List<Cortada> {
        val raiz = context.filesDir
        if (!GrabacionAMedias.carpetaDeDiarios(raiz).isDirectory) return emptyList()
        return GrabacionAMedias.pendientes(raiz)
            .filter { (_, d) -> d.marca !in DiarioEnMarcha.enMarcha }
            .mapNotNull { (dir, d) ->
                val p = GrabacionAMedias.plan(d, GrabacionAMedias.hallar(dir, d) { RespaldoWav.duracionMs(it) })
                if (!p.algo() && p.video != GrabacionAMedias.Video.PERDIDO) {
                    android.util.Log.i(TAG, "grabación cortada sin nada que salvar: fuera (${d.marca})")
                    GrabacionAMedias.descartar(dir, d)
                    null
                } else Cortada(dir, d, p)
            }
    }

    /** Vuelve a mirar el disco (en un hilo de E/S) y pone al día [cortadas]. */
    fun refrescar(context: Context) {
        val app = context.applicationContext as? PixPinApp ?: return
        app.scope.launch(Dispatchers.IO) {
            _cortadas.value = runCatching { pendientes(app) }.getOrDefault(emptyList())
        }
    }

    /**
     * **Los picos de la onda, del respaldo WAV** (lo del PC: «los picos de la onda salen del
     * respaldo»): el más alto de cada [com.forge.pixpin.pin.Voz.MS_ENTRE_PICOS], como al grabar.
     */
    fun picosDelRespaldo(respaldo: File): List<Int> = runCatching {
        val wav = RespaldoWav.leer(respaldo)
        val porPico = maxOf((wav.muestreo * com.forge.pixpin.pin.Voz.MS_ENTRE_PICOS / 1000).toInt(), 1) * wav.canales
        val picos = Picos()
        var maximo = 0
        var vistas = 0
        RespaldoWav.recorrer(respaldo, wav, 4096) { m, n ->
            for (i in 0 until n) {
                val a = kotlin.math.abs(m[i].toInt())
                if (a > maximo) maximo = a
                if (++vistas >= porPico) { picos.anota(minOf(maximo, 32767)); maximo = 0; vistas = 0 }
            }
        }
        picos.lista()
    }.getOrDefault(emptyList())

    /**
     * **Rehace la grabación** de [c] y la mete en el chat, como «Terminar». Devuelve cómo fue, o null si
     * falló (entonces no se ha tocado nada del diario). [pasarATexto] false en las pruebas.
     */
    fun recuperar(context: Context, c: Cortada, pasarATexto: Boolean = true): GuardarLaGrabacion.Resultado? = runCatching {
        val d = c.diario
        val p = c.plan
        val audio = d.audio.takeIf { it.isNotEmpty() }?.let(::File)
        val respaldo = audio?.let { RespaldoWav.rutaDelRespaldo(it) }
        // La nota.
        val nota: File? = when (p.nota) {
            GrabacionAMedias.Nota.LISTA -> audio
            GrabacionAMedias.Nota.DEL_PARTE -> audio?.takeIf { GuardarLaGrabacion.parteDe(it).renameTo(it) }
            GrabacionAMedias.Nota.DEL_RESPALDO -> audio?.takeIf { RehacerM4a.rehacer(respaldo!!, it) != null }
            GrabacionAMedias.Nota.NINGUNA -> null
        }
        if (p.nota != GrabacionAMedias.Nota.NINGUNA && nota == null) android.util.Log.w(TAG, "la nota de la grabación cortada no se pudo rehacer")
        val picos = respaldo?.takeIf { it.isFile }?.let { picosDelRespaldo(it) }.orEmpty()
        // El vídeo: sus trozos que se abren, juntos y con el sonido de la nota.
        val carpeta = audio?.parentFile ?: MensajesStore(context).carpetaDeAdjuntos()
        val partes: List<GuardarLaGrabacion.Parte> = when (p.video) {
            GrabacionAMedias.Video.LISTO -> listOfNotNull(d.video?.let { GuardarLaGrabacion.Parte(File(it), 0) })
            GrabacionAMedias.Video.DEL_PARTE -> if (p.trozos.isNotEmpty()) {
                GuardarLaGrabacion.juntarVideo(
                    p.trozos.map { i -> d.trozos[i].let { GuardarLaGrabacion.TrozoDeGrupo(File(it.ruta), it.ms, it.grupo) } }, nota
                ) { File(carpeta, "recuperada_${d.marca}_video$it.mp4") }
            } else listOfNotNull(d.video?.let { v -> File(v).takeIf { GrabacionAMedias.conSufijo(v, ".parte").renameTo(it) }?.let { GuardarLaGrabacion.Parte(it, 0) } })
            else -> emptyList()
        }
        val duracion = p.duracionMs.toInt().takeIf { it > 0 }
        val reloj = RelojDeLaGrabacion.delDiario(
            d.reloj, runCatching { com.forge.pixpin.sincro.Codigos.deEsteAparato(context.filesDir) }.getOrNull().orEmpty(),
            d.latido.utc, d.movil.orEmpty()
        )
        val proyecto = d.proyecto.ifEmpty { null }
        val ahora = System.currentTimeMillis()
        val r = if (d.modo == Presencial.Modo.PRESENCIAL.palabra) {
            val fotos = p.capturas.map { i -> d.capturas[i].let { GuardarLaGrabacion.Imagen(it.ms, File(c.dir, it.fichero)) } }
            val clips = p.clips.map { i -> d.clips[i].let { GuardarLaGrabacion.ClipHecho(it.ms, it.duracionMs ?: 0, File(it.ruta)) } } +
                p.clipsDelVideo.map { i -> d.clips[i].let { GuardarLaGrabacion.ClipHecho(it.ms, it.duracionMs ?: 0, null) } }
            GuardarLaGrabacion.reunion(context, nota, duracion, proyecto, d.nombre, ahora, picos, fotos, clips, partes, reloj, pasarATexto)
        } else {
            val video = partes.firstOrNull()?.archivo
            val sacadas = if (video != null) GuardarLaGrabacion.fotogramas(video, p.capturasDelVideo, File(context.cacheDir, "recuperada")) else emptyList()
            val capturas = p.capturas.map { i -> d.capturas[i].let { GuardarLaGrabacion.Imagen(it.ms, File(c.dir, it.fichero)) } } + sacadas
            GuardarLaGrabacion.clase(context, nota, duracion, proyecto, d.nombre, ahora, picos, capturas, video, reloj, pasarATexto)
                .also { sacadas.forEach { s -> s.archivo.delete() } }
        }
        // **Los temas de la reunión, con lo ya tratado** (29-oct-2026, como el PC): a la nota y a su lista.
        val notaId = r.notaId
        if (r.que == GuardarLaGrabacion.Que.LISTA && notaId != null) ReunionConJev.cierreDelDiario(d)?.let { cierre ->
            runCatching { ReunionConJev.guardar(context, notaId, cierre) }.onFailure { android.util.Log.w(TAG, "los temas de la grabación cortada no entraron en la nota", it) }
        }
        if (r.que == GuardarLaGrabacion.Que.LISTA || r.que == GuardarLaGrabacion.Que.SIN_NOTA) {
            // Entró: fuera el diario y el respaldo. Lo que no se abría (un trozo sin índice) se queda.
            respaldo?.delete()
            audio?.let { GuardarLaGrabacion.parteDe(it).delete() }
            c.dir.deleteRecursively()
        }
        r
    }.onFailure { android.util.Log.w(TAG, "la grabación cortada no se pudo recuperar", it) }.getOrNull()

    /** **Descartar**: lo eligió el usuario (dos veces). Es lo único que borra lo grabado. */
    fun descartar(c: Cortada) {
        GrabacionAMedias.descartar(c.dir, c.diario)
    }

    /** El texto de la tarjeta: «Se cortó una grabación (Clase 28-10 10h05, 47 min) en «Física»». */
    fun texto(context: Context, c: Cortada): String {
        val base = if (c.perdida) context.getString(R.string.cortada_perdida, c.diario.nombre)
        else context.getString(R.string.cortada_texto, c.diario.nombre, c.plan.minutos().toInt())
        val chat = c.diario.nombreProyecto
        return if (chat.isEmpty()) base else base + " " + context.getString(R.string.cortada_en_chat, chat)
    }

    /**
     * **Un aviso si quedaron grabaciones cortadas** (al volver a crear Android el servicio de grabar sin
     * intent, `START_STICKY`): tocarlo abre PixPin, que enseña la tarjeta. Mira el disco en otro hilo.
     */
    fun avisarSiHay(context: Context) {
        val app = context.applicationContext
        Thread({
            val hay = runCatching { pendientes(app) }.getOrDefault(emptyList())
            if (hay.isEmpty()) return@Thread
            runCatching {
                val nm = app.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CANAL, app.getString(R.string.cortada_titulo), NotificationManager.IMPORTANCE_DEFAULT))
                val abrir = PendingIntent.getActivity(
                    app, AVISO,
                    Intent(app, com.forge.pixpin.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val n = NotificationCompat.Builder(app, CANAL)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(app.getString(R.string.cortada_titulo))
                    .setContentText(texto(app, hay.first()))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(texto(app, hay.first()) + "\n" + app.getString(R.string.cortada_aviso)))
                    .setContentIntent(abrir)
                    .setAutoCancel(true)
                    .build()
                nm.notify(AVISO, n)
            }
        }, "pixpin-cortadas").start()
    }

    /** Recupera [c] fuera de la pantalla (en el alcance de la app) y lo dice. */
    fun recuperarYDecir(context: Context, c: Cortada) {
        val app = context.applicationContext as? PixPinApp ?: return
        if (c.marca in _recuperando.value) return
        _recuperando.update { it + c.marca }
        app.scope.launch(Dispatchers.IO) {
            val r = recuperar(app, c)
            val bien = r != null && (r.que == GuardarLaGrabacion.Que.LISTA || r.que == GuardarLaGrabacion.Que.SIN_NOTA)
            withContext(Dispatchers.Main) {
                Toast.makeText(app, app.getString(if (bien) R.string.cortada_recuperada else R.string.cortada_fallo), Toast.LENGTH_LONG).show()
            }
            _recuperando.update { it - c.marca }
            _cortadas.value = runCatching { pendientes(app) }.getOrDefault(emptyList())
        }
    }

    /** Descarta [c] (ya confirmado) y pone al día la lista. */
    fun descartarYRefrescar(context: Context, c: Cortada) {
        val app = context.applicationContext as? PixPinApp ?: return
        app.scope.launch(Dispatchers.IO) {
            descartar(c)
            _cortadas.value = runCatching { pendientes(app) }.getOrDefault(emptyList())
        }
    }

    /** «Luego»: no se pregunta más por [c] hasta la próxima vez que se abra la app. */
    fun dejarParaLuego(c: Cortada) {
        luego += c.marca
        _cortadas.update { it.toList() }
    }

    internal fun esParaLuego(c: Cortada): Boolean = c.marca in luego
}

/**
 * **La tarjeta de las grabaciones cortadas** (28-oct-2026): «Se cortó una grabación (…, 47 min) —
 * Recuperar / Descartar / Luego», una por grabación, encima de la lista de chats. Sin ninguna no pinta
 * nada. Mira el disco al ponerse delante (`ON_RESUME`).
 */
@Composable
fun TarjetasDeGrabacionesCortadas(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dueno = LocalLifecycleOwner.current
    DisposableEffect(dueno) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) RecuperarGrabacion.refrescar(context) }
        dueno.lifecycle.addObserver(o)
        onDispose { dueno.lifecycle.removeObserver(o) }
    }
    val todas by RecuperarGrabacion.cortadas.collectAsState()
    val recuperando by RecuperarGrabacion.recuperando.collectAsState()
    var aDescartar by remember { mutableStateOf<RecuperarGrabacion.Cortada?>(null) }
    val visibles = todas.filter { !RecuperarGrabacion.esParaLuego(it) }
    if (visibles.isNotEmpty()) {
        Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in visibles) {
                val ocupada = c.marca in recuperando
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(context.getString(R.string.cortada_titulo), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        Text(RecuperarGrabacion.texto(context, c), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (ocupada) {
                                CircularProgressIndicator(Modifier.padding(end = 8.dp))
                                Text(context.getString(R.string.cortada_recuperando))
                            } else {
                                TextButton(onClick = { RecuperarGrabacion.dejarParaLuego(c) }) { Text(context.getString(R.string.cortada_luego)) }
                                TextButton(onClick = { aDescartar = c }) { Text(context.getString(R.string.cortada_descartar)) }
                                if (!c.perdida) {
                                    Button(onClick = { RecuperarGrabacion.recuperarYDecir(context, c) }) { Text(context.getString(R.string.cortada_recuperar)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val confirmar = aDescartar
    if (confirmar != null) {
        // **Descartar se vuelve a preguntar**, con «No» delante (como el PC).
        AlertDialog(
            onDismissRequest = { aDescartar = null },
            title = { Text(context.getString(R.string.cortada_titulo)) },
            text = { Text(context.getString(R.string.cortada_descartar_seguro, confirmar.diario.nombre)) },
            confirmButton = { TextButton(onClick = { aDescartar = null }) { Text(context.getString(R.string.cortada_no)) } },
            dismissButton = {
                TextButton(onClick = {
                    aDescartar = null
                    RecuperarGrabacion.descartarYRefrescar(context, confirmar)
                }) { Text(context.getString(R.string.cortada_si_borrar), color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}
