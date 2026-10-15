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
        val visible: String,
        /** Lo que va pensando (la sección plegable «Pensando…»). */
        val pensamiento: String = "",
        /** Cuánto pensó, cuando acabó de pensar. */
        val segundos: Float? = null,
        /** Si está pensando ahora mismo. */
        val pensando: Boolean = false,
        /** La petición mejorada («📝 Entiendo: …»). */
        val plan: com.forge.pixpin.ia.Pensar.Plan? = null,
        /** Si el turno empezó con el contexto renovado. */
        val reiniciado: Boolean = false
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

    /** La pregunta Sí/No que espera en la burbuja de una respuesta (§4.3 del manual del PC). */
    data class Pregunta(val respuesta: String, val texto: String)

    var pregunta by mutableStateOf<Pregunta?>(null)
        private set

    @Volatile private var contestacion: java.util.concurrent.CompletableFuture<Boolean>? = null

    /** Lo que caduca una pregunta sin contestar (cuenta como no). */
    const val PREGUNTA_CADUCA_MS = 5 * 60_000L

    /** El usuario tocó «Sí» o «No» en la burbuja. */
    fun contestarPregunta(si: Boolean) {
        contestacion?.complete(si)
    }

    /**
     * **Pregunta en la burbuja y espera** (en el hilo del turno). null: sin respuesta (caducó a
     * los 5 min o se pulsó parar). Nada se hace sin el «Sí».
     */
    private fun preguntarSiNo(respuesta: String, texto: String): Boolean? {
        val f = java.util.concurrent.CompletableFuture<Boolean>()
        contestacion = f
        enPantalla { pregunta = Pregunta(respuesta, texto) }
        val hasta = System.currentTimeMillis() + PREGUNTA_CADUCA_MS
        var r: Boolean? = null
        try {
            while (System.currentTimeMillis() < hasta && !parar.get()) {
                r = runCatching { f.get(250, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
                if (r != null) break
            }
        } finally {
            contestacion = null
            enPantalla { if (pregunta?.respuesta == respuesta) pregunta = null }
        }
        return r
    }

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

    /** El texto que se le enseña al usuario la primera vez que la nube buscaría en sus documentos (§4.8). */
    const val PREGUNTA_DOCUMENTOS = "El asistente está usando un modelo de la nube. Para contestar sobre tus documentos le mandaría " +
        "trozos de ellos (lo que encuentre en tus PDF, notas y chats) por internet.\n\n¿Lo permites? Lo que elijas se recuerda y se cambia en Ajustes › Asistente."
    /** Lo que se mide de la ventana con la nube (`pensado::VENTANA_NUBE` del PC). */
    const val VENTANA_NUBE = 64_000

    const val SIN_PERMISO_DOCUMENTOS = "el usuario no permite mandar sus documentos a la nube; dile que puede permitirlo en Ajustes › Asistente o usar el modelo local, que busca sin salir del telefono"

    /**
     * **El motor de este turno** (§1.4): el local, o Claude o DeepSeek con su clave (y el local de
     * respaldo si se puede). null y el motivo si no hay con qué contestar.
     */
    private fun motorDelTurno(app: PixPinApp, ajustes: com.forge.pixpin.data.Settings, mirarFoto: Boolean = false): Pair<com.forge.pixpin.ia.Motor?, String?> {
        // **Mejor vista** (§1.3): con una foto y un pedido de MIRARLA, Gemma si está bajado; el de
        // siempre se suelta antes (nunca dos cargados a la vez: `motorPara` cambia uno por otro).
        val gemma = com.forge.pixpin.ia.Modelos.GEMMA4_E2B
        val m = if (mirarFoto && ajustes.asistenteMejorVista && hayMotor(app) && listo(app, gemma)) gemma else modelo(app, ajustes.asistenteModelo)
        val local = if (hayMotor(app) && listo(app, m)) motorPara(app, m) else null
        val proveedor = com.forge.pixpin.ia.Nube.Proveedor.porId(ajustes.asistenteMotor)
        if (proveedor != null) {
            val clave = ClavesDeLaNube.leer(app, proveedor) ?: return null to "Falta la clave de ${proveedor.nombre}: pégala en Ajustes → Asistente."
            val nube = com.forge.pixpin.ia.Nube.Motor(proveedor, clave, ligero = ajustes.asistentePensar == "no")
            return (if (ajustes.asistenteRespaldoLocal && local != null) com.forge.pixpin.ia.Nube.ConRespaldo(nube, local, proveedor.nombre, Herramientas.DISPONIBLES) else nube) to null
        }
        if (!hayMotor(app)) return null to "El asistente local solo va en teléfonos de 64 bits (arm64)."
        if (local == null) return null to ErrorIa.Falta(m.nombre).paraElUsuario()
        return local to null
    }

    private fun contestar(app: PixPinApp, almacen: MensajesStore, pregunta: Mensaje, respuesta: Mensaje, enSuChat: Boolean) {
        val ajustes = runBlocking { app.settings.settings.first() }
        fun escribir(texto: String, lienzo: String? = null, fuentes: kotlinx.serialization.json.JsonArray? = null) =
            almacen.actualizar(respuesta.id) { it.copy(texto = texto, lienzoIa = lienzo ?: it.lienzoIa, fuentesIa = fuentes ?: it.fuentesIa) }
        val conFoto = ChatDelAsistente.fotoDe(almacen.leer(), pregunta) != null
        val mirar = com.forge.pixpin.ia.Nube.Proveedor.porId(ajustes.asistenteMotor) == null &&
            com.forge.pixpin.ia.Seleccion.pideMejorVista(ChatDelAsistente.sinMencion(pregunta.texto), conFoto)
        val (motor0, motivo) = motorDelTurno(app, ajustes, mirar)
        // Una orden simple sale sin modelo (camino rápido); sin motor, el resto avisa.
        val esOrden = !conFoto && com.forge.pixpin.ia.Rapido.interpretar(ChatDelAsistente.sinMencion(pregunta.texto), com.forge.pixpin.ia.Momento.ahora()) != null
        val motor = motor0 ?: if (esOrden) Agente.SinMotor(motivo.orEmpty()) else null
        if (motor == null) {
            escribir("⚠️ " + motivo.orEmpty())
            return
        }
        val conNube = motor.catalogoCompleto
        val mensajes = almacen.leer()
        val fotoArchivo = ChatDelAsistente.fotoDe(mensajes, pregunta)?.ruta?.let { File(it) }
        val foto = fotoArchivo?.let { FotoParaElModelo.deArchivo(it) }?.let { it to "image/jpeg" }
        // `@lienzo:Nombre` fija dónde se dibuja y se le dice al modelo como «el lienzo «Nombre»».
        val (pedido0, destino) = ChatDelAsistente.resolverLienzo(ChatDelAsistente.sinMencion(pregunta.texto), mensajes.filter { it.proyecto == pregunta.proyecto })
        // La memoria del turno (§2.5): la hoja de trabajo del chat, el contexto medido y, si ya se
        // reinició alguna vez, buscar en lo guardado. El 0.8B no planea: tarda y no le sirve.
        val delChat = mensajes.filter { it.proyecto == pregunta.proyecto && !it.enBuzon }
        val anteriores = delChat.filter { it.cuando <= pregunta.cuando && it.id != pregunta.id && it.id != respuesta.id }
        val hoja = PensadoDelChat.hojaDelChat(app, anteriores)
        val ventana = if (conNube) VENTANA_NUBE else com.forge.pixpin.ia.ServidorLlama.CONTEXTO
        val herramientas = if (conNube) Herramientas.catalogo() else Herramientas.paraElPedido(pedido0, foto != null)
        val (historial, reiniciado) = if (enSuChat) {
            val nuevo = if (foto != null) com.forge.pixpin.ia.MensajeIa.usuarioConImagen(pedido0, foto.first, foto.second) else com.forge.pixpin.ia.MensajeIa.usuario(pedido0)
            PensadoDelChat.historial(Agente.instrucciones(), hoja, anteriores, nuevo, ventana, herramientas)
        } else ChatDelAsistente.historial(Agente.instrucciones(), mensajes, pregunta.copy(texto = pedido0), enSuChat, foto) to false
        val fuentesDelChat = PensadoDelChat.fuentes(anteriores, hoja)
        val ligero = !conNube && modelo(app, ajustes.asistenteModelo).id == com.forge.pixpin.ia.Modelos.QWEN35_08B.id
        val opciones = Agente.Opciones(
            planificar = !ligero,
            pensar = when (ajustes.asistentePensar) { "si" -> true; "no" -> false; else -> false },
            hoja = hoja,
            ventana = ventana,
            buscar = if (enSuChat && (reiniciado || hoja.reinicios > 0)) { c -> com.forge.pixpin.ia.Contexto.buscar(fuentesDelChat, c, 3) } else null,
            ampliar = Herramientas.catalogo(),
            rapido = com.forge.pixpin.ia.Momento.ahora()
        )
        val recogido = PensadoDelChat.Recogido()
        var vivo = EnVivo(respuesta.proyecto, respuesta.id, ChatDelAsistente.PENSANDO, reiniciado = reiniciado)
        // Las preguntas Sí/No contestadas quedan en el texto de la nota (`❓ … → ✅ Sí`).
        val preguntas = mutableListOf<String>()
        val pedido = pedido0
        val ejecutor = EjecutorDelChat(
            app, almacen, pregunta.proyecto, motor, parar,
            confirmar = { q ->
                val r = preguntarSiNo(respuesta.id, q)
                preguntas += ChatDelAsistente.lineaDePregunta(q, r)
                r == true
            },
            pedido = pedido, foto = fotoArchivo, destino = destino,
            permisoDocumentos = {
                if (!conNube) null
                else when (runBlocking { app.settings.settings.first().asistenteDocumentosNube }) {
                    "si" -> null
                    "no" -> SIN_PERMISO_DOCUMENTOS
                    else -> {
                        val r = preguntarSiNo(respuesta.id, PREGUNTA_DOCUMENTOS)
                        preguntas += ChatDelAsistente.lineaDePregunta("¿Mando trozos de tus documentos a la nube?", r)
                        if (r != null) runBlocking { app.settings.setAsistenteDocumentosNube(if (r) "si" else "no") }
                        if (r == true) null else SIN_PERMISO_DOCUMENTOS
                    }
                }
            }
        )

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
            val visible = ChatDelAsistente.conPreguntas(preguntas, ChatDelAsistente.mostrar(ChatDelAsistente.sinPensado(texto.toString()), estado, escribiendo = true))
            vivo = vivo.copy(visible = visible)
            val ahoraVivo = vivo
            enPantalla { if (enVivo?.respuesta == respuesta.id) enVivo = ahoraVivo }
            // En disco cada 2 s, como el PC: una sincronización a mitad se lleva un texto a medias,
            // que se arregla en la vuelta siguiente.
            if (ahora - ultimoGuardado >= 2_000) {
                ultimoGuardado = ahora
                runCatching { escribir(ChatDelAsistente.conPreguntas(preguntas, ChatDelAsistente.mostrar(ChatDelAsistente.sinPensado(texto.toString()), estado, escribiendo = false))) }
            }
        }
        val final = try {
            Agente.turno(motor, historial, herramientas, ejecutor, ventana, opciones) { e ->
                recogido.ver(e)
                when (e) {
                    is Agente.Evento.Pensando -> vivo = vivo.copy(pensamiento = recogido.pensamiento.toString(), pensando = true)
                    is Agente.Evento.PensoListo -> vivo = vivo.copy(pensando = false, segundos = recogido.segundos)
                    is Agente.Evento.Peticion -> vivo = vivo.copy(plan = e.plan)
                    is Agente.Evento.Reinicio -> vivo = vivo.copy(reiniciado = true)
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
        val textoFinal = ChatDelAsistente.conPreguntas(preguntas, ChatDelAsistente.textoFinal(ChatDelAsistente.sinPensado(dicho), fallos, final.cortado))
        // Las citas que nombró (`[2]`) van con la nota: de ahí sale «Abrir fuente» (§3.1).
        val fuentes = ejecutor.documentos.citas.citadas(textoFinal).takeIf { it.isNotEmpty() }
            ?.let { l -> kotlinx.serialization.json.JsonArray(l.map { it.aJson() }) }
        escribir(textoFinal, ejecutor.lienzo, fuentes)
        // Leer cada respuesta en voz alta al acabar, si se pidió en Ajustes.
        if (ajustes.asistenteLeerRespuestas && !final.cortado) enPantalla { VozDelAsistente.leer(app, textoFinal) }
        // «…y ábrelo»: se abre al acabar (si no, la respuesta lleva «Abrir lienzo»).
        ejecutor.lienzo?.takeIf { ChatDelAsistente.pideAbrir(pedido) }?.let { ref ->
            enPantalla { runCatching { com.forge.pixpin.motor.DrawEditorActivity.abrir(app, ref, com.forge.pixpin.motor.ExcalidrawStore.rutaDe(app, ref), null) } }
        }
        // Lo que pensó y la hoja reescrita, junto a su nota (§3.2): viaja con ella.
        if (enSuChat) {
            val incompleto = final.cortado || final.texto.startsWith("No he sabido") || final.texto.startsWith("Hecho en parte")
            val r = recogido.registro(hoja, pedido, textoFinal, incompleto, reiniciado)
            almacen.leer().firstOrNull { it.id == respuesta.id }?.let { PensadoDelChat.guardar(app, it, r) }
        }
    }
}
