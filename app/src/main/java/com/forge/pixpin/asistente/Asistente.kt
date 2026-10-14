package com.forge.pixpin.asistente

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Agente
import com.forge.pixpin.ia.Descarga
import com.forge.pixpin.ia.ErrorIa
import com.forge.pixpin.ia.Herramientas
import com.forge.pixpin.ia.Modelo
import com.forge.pixpin.ia.Modelos
import com.forge.pixpin.ia.Seguir
import com.forge.pixpin.ia.ServidorLlama
import com.forge.pixpin.motor.Proyecto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **El asistente del chat, en el teléfono** (`apps/pixpin/src/asistente/` del PC): un motor por
 * proceso, cargado al primer uso y soltado a los 5 min sin usar; la descarga de la primera vez; y
 * cada pregunta, contestada en vivo en una nota normal con aparato «IA».
 *
 * **Nada de esto bloquea la pantalla**: la descarga, el arranque del modelo y cada turno van en
 * `Dispatchers.IO` sobre el `scope` de la aplicación (sobreviven a girar el teléfono o a cerrar el
 * chat). La pantalla solo lee el estado de aquí ([enVivo], [progreso]), que es estado de Compose y
 * se escribe siempre en el hilo principal.
 */
object Asistente {
    private const val TAG = "PixPinAsistente"

    /** La respuesta que se está escribiendo ahora, para pintarla en vivo en su burbuja. */
    data class EnVivo(
        val chat: String?,
        /** El id de la nota de la respuesta. */
        val respuesta: String,
        /** Lo que enseña la burbuja ya montado ([ChatDelAsistente.mostrar]). */
        val visible: String
    )

    var enVivo by mutableStateOf<EnVivo?>(null)
        private set

    /** La descarga en curso, para la barra de la tarjeta; null si no se baja nada. */
    var progreso by mutableStateOf<Descarga.Progreso?>(null)
        private set

    /** Lo que salió mal en la última descarga, dicho para el usuario; null si nada. */
    var avisoDeDescarga by mutableStateOf<String?>(null)
        private set

    /** Sube cuando cambia lo bajado (para que la tarjeta y los ajustes se repinten). */
    var bajados by mutableStateOf(0)
        private set

    private val cancelarDescarga = AtomicBoolean(false)
    private val parar = AtomicBoolean(false)
    private val ocupado = AtomicBoolean(false)

    private var motor: ServidorLlama? = null
    private var motorDe: String? = null

    fun raiz(c: Context): File = File(c.noBackupFilesDir, "modelos")

    /** El ejecutable: solo existe en los teléfonos arm64 (es lo único que se compila). */
    fun ejecutable(c: Context): File = File(c.applicationInfo.nativeLibraryDir, "libllamaserver.so")

    fun hayMotor(c: Context): Boolean = ejecutable(c).isFile

    fun ramTotal(c: Context): Long = runCatching {
        ActivityManager.MemoryInfo().also { (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem
    }.getOrDefault(0L)

    /** El modelo que toca: el del ajuste, o el que va con la RAM del teléfono. */
    fun modelo(c: Context, ajuste: String?): Modelo = Modelos.elegido(ajuste, ramTotal(c))

    fun listo(c: Context, m: Modelo): Boolean = Descarga.modeloListo(raiz(c), m)

    private fun enPantalla(bloque: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(bloque)

    // ------------------------------------------------------------------------------- descarga

    /** **Baja [m]** (con progreso y cancelar); reanuda lo que hubiera a medias. */
    fun bajar(c: Context, m: Modelo) {
        val app = c.applicationContext as PixPinApp
        if (progreso != null) return
        cancelarDescarga.set(false)
        avisoDeDescarga = null
        progreso = Descarga.Progreso(0, m.bytes, m.pesos.nombre, false)
        app.scope.launch(Dispatchers.IO) {
            var ultimo = 0L
            val r = runCatching {
                Descarga.asegurar(raiz(app), m, { p ->
                    val ahora = System.currentTimeMillis()
                    if (ahora - ultimo > 250 || p.verificando) { ultimo = ahora; enPantalla { progreso = p } }
                }, cancelarDescarga)
            }
            val error = r.exceptionOrNull()
            if (error != null && error !== ErrorIa.Cancelado) Log.w(TAG, "descarga", error)
            enPantalla {
                progreso = null
                bajados++
                avisoDeDescarga = when {
                    error == null || error === ErrorIa.Cancelado -> null
                    error is ErrorIa -> error.paraElUsuario()
                    else -> "No se pudo bajar: ${error.message ?: "sin red"}. Lo bajado se guarda y sigue al reintentar."
                }
            }
        }
    }

    fun cancelarDescarga() = cancelarDescarga.set(true)

    /** Borra un modelo bajado (Ajustes). Si es el cargado, lo suelta antes. */
    fun borrar(c: Context, m: Modelo) {
        val app = c.applicationContext as PixPinApp
        app.scope.launch(Dispatchers.IO) {
            synchronized(this@Asistente) { if (motorDe == m.id) { motor?.soltar(); motor = null; motorDe = null } }
            Descarga.borrar(raiz(app), m)
            enPantalla { bajados++ }
        }
    }

    // --------------------------------------------------------------------------------- motor

    /** **Un motor por proceso**: el del modelo elegido; si se cambió de modelo, se suelta el otro. */
    @Synchronized
    private fun motorPara(c: Context, m: Modelo): ServidorLlama {
        motor?.let { if (motorDe == m.id) return it; it.soltar() }
        val carpeta = Descarga.carpetaModelo(raiz(c), m)
        val nuevo = ServidorLlama(
            ServidorLlama.Config(
                servidor = ejecutable(c),
                modelo = File(carpeta, m.pesos.nombre),
                vision = m.vision?.let { File(carpeta, it.nombre) },
                trabajo = File(c.noBackupFilesDir, "asistente"),
                hilos = ServidorLlama.hilosParaEsteAparato()
            )
        )
        motor = nuevo
        motorDe = m.id
        return nuevo
    }

    /**
     * **Soltar la memoria** si no está contestando (la app se va al fondo, o el sistema pide
     * memoria). El modelo vuelve a cargarse solo en la siguiente pregunta. Fuera del hilo de la
     * pantalla: matar el proceso espera a que muera.
     */
    fun soltarSiNoContesta(c: Context) {
        if (ocupado.get()) return
        val app = c.applicationContext as? PixPinApp ?: return
        app.scope.launch(Dispatchers.IO) { synchronized(this@Asistente) { if (!ocupado.get()) motor?.soltar() } }
    }

    /** Que la primera respuesta no espere a cargar el modelo (al abrir el chat «Asistente»). */
    fun precalentar(c: Context, m: Modelo) {
        if (!hayMotor(c) || !listo(c, m)) return
        val app = c.applicationContext as PixPinApp
        app.scope.launch(Dispatchers.IO) { runCatching { motorPara(app, m).precalentar() } }
    }

    // ------------------------------------------------------------------------- chat «Asistente»

    /**
     * **El chat del asistente, creándolo si no hay ninguno** (§3.3: uno solo). Trabajo de disco:
     * fuera del hilo de la pantalla.
     */
    fun asegurarChat(c: Context): Proyecto {
        val app = c.applicationContext as PixPinApp
        synchronized(this) {
            val mensajes = MensajesStore(app).leer()
            ChatDelAsistente.buscar(app.proyectos.proyectos.value, mensajes)?.let { return it }
            val ahora = System.currentTimeMillis()
            val nuevo = Proyecto(id = "pr-$ahora", nombre = ChatDelAsistente.NOMBRE, tocado = ahora, asistente = true)
            app.proyectos.guardar(nuevo)
            return app.proyectos.porId(nuevo.id) ?: nuevo
        }
    }

    /** Si hay una respuesta en curso en este chat (el botón de mandar se vuelve «Parar»). */
    fun contestandoEn(chat: String?): Boolean = enVivo?.let { it.chat == chat } == true

    fun parar() = parar.set(true)

    /**
     * **Contesta a [pregunta]**, que ya está guardada en el chat. La respuesta nace al instante con
     * «💭 …», se escribe en vivo y se guarda cada 2 s y al acabar. Una a la vez: si ya está
     * contestando, no hace nada (devuelve false).
     */
    fun preguntar(c: Context, pregunta: Mensaje, chatDelAsistente: String?): Boolean {
        val app = c.applicationContext as PixPinApp
        if (!ocupado.compareAndSet(false, true)) return false
        parar.set(false)
        val enSuChat = chatDelAsistente != null && pregunta.proyecto == chatDelAsistente
        val respuesta = Mensaje(
            id = UUID.randomUUID().toString(),
            cuando = pregunta.cuando + 1,
            clase = Clase.NOTA,
            texto = ChatDelAsistente.PENSANDO,
            proyecto = pregunta.proyecto,
            respondeA = if (enSuChat) null else pregunta.id,
            aparato = ChatDelAsistente.APARATO
        )
        enVivo = EnVivo(pregunta.proyecto, respuesta.id, ChatDelAsistente.PENSANDO)
        app.scope.launch(Dispatchers.IO) {
            val almacen = MensajesStore(app)
            try {
                almacen.anadir(respuesta)
                contestar(app, almacen, pregunta, respuesta, enSuChat)
            } catch (e: Throwable) {
                Log.e(TAG, "turno", e)
                runCatching { almacen.actualizar(respuesta.id) { it.copy(texto = "⚠️ No pude contestar por un fallo del teléfono. Prueba otra vez.") } }
            } finally {
                ocupado.set(false)
                // Un momento para que el chat relea la nota ya escrita: así no asoma el texto de
                // hace dos segundos entre lo vivo y lo guardado.
                kotlinx.coroutines.delay(400)
                withContext(Dispatchers.Main) { if (enVivo?.respuesta == respuesta.id) enVivo = null }
            }
        }
        return true
    }

    private fun contestar(app: PixPinApp, almacen: MensajesStore, pregunta: Mensaje, respuesta: Mensaje, enSuChat: Boolean) {
        val ajuste = runBlocking { app.settings.settings.first().asistenteModelo }
        val m = modelo(app, ajuste)
        fun escribir(texto: String, lienzo: String? = null) =
            almacen.actualizar(respuesta.id) { it.copy(texto = texto, lienzoIa = lienzo ?: it.lienzoIa) }
        if (!hayMotor(app)) {
            escribir("⚠️ El asistente local solo va en teléfonos de 64 bits (arm64).")
            return
        }
        if (!listo(app, m)) {
            escribir("⚠️ " + ErrorIa.Falta(m.nombre).paraElUsuario())
            return
        }
        val motor = motorPara(app, m)
        val mensajes = almacen.leer()
        val foto = ChatDelAsistente.fotoDe(mensajes, pregunta)?.ruta?.let { File(it) }
            ?.let { FotoParaElModelo.deArchivo(it) }?.let { it to "image/jpeg" }
        val historial = ChatDelAsistente.historial(Agente.instrucciones(), mensajes, pregunta, enSuChat, foto)
        val ejecutor = EjecutorDelChat(app, almacen, pregunta.proyecto, motor, parar)
        val pedido = ChatDelAsistente.sinMencion(pregunta.texto)

        // Lo que se va diciendo: el texto de esta vuelta, la línea de estado y los fallos.
        val texto = StringBuilder()
        var estado: String? = null
        var trasResultado = false
        val fallos = mutableListOf<String>()
        var ultimoPintado = 0L
        var ultimoGuardado = System.currentTimeMillis()
        fun pintar(forzar: Boolean = false) {
            val ahora = System.currentTimeMillis()
            if (!forzar && ahora - ultimoPintado < 60) return
            ultimoPintado = ahora
            val visible = ChatDelAsistente.mostrar(ChatDelAsistente.sinPensado(texto.toString()), estado, escribiendo = true)
            enPantalla { if (enVivo?.respuesta == respuesta.id) enVivo = EnVivo(respuesta.proyecto, respuesta.id, visible) }
            // En disco cada 2 s, como el PC: una sincronización a mitad se lleva un texto a medias,
            // que se arregla en la vuelta siguiente.
            if (ahora - ultimoGuardado >= 2_000) {
                ultimoGuardado = ahora
                runCatching { escribir(ChatDelAsistente.mostrar(ChatDelAsistente.sinPensado(texto.toString()), estado, escribiendo = false)) }
            }
        }
        val final = try {
            Agente.turno(motor, historial, Herramientas.paraElPedido(pedido), ejecutor) { e ->
                when (e) {
                    is Agente.Evento.Texto -> {
                        if (trasResultado) { texto.setLength(0); trasResultado = false }
                        texto.append(e.texto); estado = null
                    }
                    is Agente.Evento.Llamando -> estado = ChatDelAsistente.rotuloDe(e.nombre)
                    is Agente.Evento.Herramienta -> estado = ChatDelAsistente.rotuloDe(e.llamada.nombre)
                    is Agente.Evento.Resultado -> {
                        if (!e.ok) fallos += e.texto.removePrefix("ERROR: ")
                        estado = ChatDelAsistente.PENSANDO; trasResultado = true
                    }
                    is Agente.Evento.Reintento -> estado = ChatDelAsistente.CORRIGIENDO
                }
                pintar(forzar = e !is Agente.Evento.Texto)
                if (parar.get()) Seguir.PARAR else Seguir.SI
            }
        } catch (e: ErrorIa) {
            Log.w(TAG, "turno: ${e.message}")
            escribir("⚠️ " + e.paraElUsuario(), ejecutor.lienzo)
            return
        }
        val dicho = if (final.cortado) ChatDelAsistente.sinPensado(texto.toString()) else final.texto
        escribir(ChatDelAsistente.textoFinal(ChatDelAsistente.sinPensado(dicho), fallos, final.cortado), ejecutor.lienzo)
    }
}
