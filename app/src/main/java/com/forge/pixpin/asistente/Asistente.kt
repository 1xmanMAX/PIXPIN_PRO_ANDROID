package com.forge.pixpin.asistente

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
import com.forge.pixpin.ia.ErrorIa
import com.forge.pixpin.ia.Herramientas
import com.forge.pixpin.ia.Nube
import com.forge.pixpin.ia.Seguir
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
 * **El asistente del chat, en el teléfono** (`apps/pixpin/src/asistente/` del PC): cada pregunta,
 * contestada en vivo en una nota normal con aparato «IA», por la nube (Claude o DeepSeek con la
 * clave del usuario).
 *
 * **Sin modelo en el teléfono** (25-oct-2026, lo pidió el usuario): el llama.cpp que iba en el APK
 * y los GGUF que se bajaban se quitaron; la nube es el único motor. [limpiarModelosLocales] borra
 * al arrancar lo que quedara bajado de antes.
 *
 * **Nada de esto bloquea la pantalla**: cada turno va en `Dispatchers.IO` sobre el `scope` de la
 * aplicación (sobrevive a girar el teléfono o a cerrar el chat). La pantalla solo lee el estado de
 * aquí ([enVivo]), que es estado de Compose y se escribe siempre en el hilo principal.
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

    // ----------------------------------------------------- `preguntar_usuario` (22-oct en el PC)

    /** **La duda con opciones** que espera en la burbuja de una respuesta ([com.forge.pixpin.ia.Preguntar]). */
    data class Duda(val respuesta: String, val pregunta: com.forge.pixpin.ia.Preguntar.Pregunta)

    var duda by mutableStateOf<Duda?>(null)
        private set

    @Volatile private var contestacionDuda: java.util.concurrent.CompletableFuture<com.forge.pixpin.ia.Preguntar.Contestada>? = null

    /** El usuario eligió una opción, escribió lo suyo («Otro…») o lo escribió en el chat. */
    fun contestarDuda(c: com.forge.pixpin.ia.Preguntar.Contestada) {
        contestacionDuda?.complete(c)
    }

    /**
     * **Enseña la duda y espera** (en el hilo del turno), como `preguntar_en_la_burbuja` del PC.
     * Pasados 5 min, parar o cerrar: sin respuesta, y el turno sigue con la recomendada.
     */
    private fun preguntarDuda(respuesta: String, p: com.forge.pixpin.ia.Preguntar.Pregunta): com.forge.pixpin.ia.Preguntar.Contestada {
        val f = java.util.concurrent.CompletableFuture<com.forge.pixpin.ia.Preguntar.Contestada>()
        contestacionDuda = f
        enPantalla { duda = Duda(respuesta, p) }
        val hasta = System.currentTimeMillis() + PREGUNTA_CADUCA_MS
        var r: com.forge.pixpin.ia.Preguntar.Contestada? = null
        try {
            while (System.currentTimeMillis() < hasta && !parar.get()) {
                r = runCatching { f.get(250, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
                if (r != null) break
            }
        } finally {
            contestacionDuda = null
            enPantalla { if (duda?.respuesta == respuesta) duda = null }
        }
        return r ?: com.forge.pixpin.ia.Preguntar.Contestada.SinRespuesta
    }

    // ------------------------------------------- lo que se escribe mientras trabaja (25-oct en el PC)

    /** Lo que el usuario escribió en el chat del turno en curso y aún no ha leído el modelo. */
    private val entrantes = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** El chat del turno en curso (null si no hay turno o es el de Mensajes guardados). */
    @Volatile private var chatEnCurso: String? = null

    private fun recogerEntrantes(): List<String> {
        val v = ArrayList<String>()
        while (true) v += entrantes.poll() ?: break
        return v
    }

    private val parar = AtomicBoolean(false)
    private val ocupado = AtomicBoolean(false)

    private fun enPantalla(bloque: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(bloque)

    // ------------------------------------------------------------------- lo que quedó de antes

    /**
     * **Lo que dejó el modelo del teléfono, fuera** (25-oct-2026): los GGUF bajados
     * (`noBackupFilesDir/modelos`, hasta ~6 GB con la «mejor vista») y la carpeta de trabajo del
     * servidor (`noBackupFilesDir/asistente`). Ya nada los usa; sin esto se quedarían ocupando el
     * teléfono para siempre. Barato cuando no hay nada: se llama al arrancar, fuera del hilo de
     * la pantalla.
     */
    fun limpiarModelosLocales(c: Context) {
        for (nombre in listOf("modelos", "asistente")) {
            val carpeta = File(c.noBackupFilesDir, nombre)
            if (carpeta.exists() && !carpeta.deleteRecursively()) Log.w(TAG, "no se pudo borrar ${carpeta.name}")
        }
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
     * **«Conversación nueva»** (`ventana_chat/asistente.rs::nueva_conversacion` del PC, 17-oct-2026;
     * el usuario: «que pueda borrar el chat y crear uno nuevo de forma que no tenga el contexto de
     * todo el chat cada vez»): una nota del asistente que marca el corte
     * ([ChatDelAsistente.TEXTO_NUEVA_CONVERSACION], `nuevaConversacionIa: true`). Lo de arriba sigue
     * a la vista y viaja como siempre, pero el siguiente turno empieza de cero: sin historial, sin
     * la hoja de trabajo y sin las fotos ni los adjuntos de antes. Lo duradero de la que se cierra
     * va a la memoria de largo plazo ([MemoriaQueViaja.alCerrar]), en segundo plano.
     *
     * Con un turno en marcha no se corta (devuelve el aviso); si ya se acaba de cortar, no se
     * repite (null, sin nada que decir). Trabajo de disco: fuera del hilo de la pantalla.
     */
    fun nuevaConversacion(c: Context, chat: String?): String? {
        if (ocupado.get()) return "El asistente está contestando: espera a que acabe o páralo."
        val app = c.applicationContext as PixPinApp
        val almacen = MensajesStore(app)
        val delChat = almacen.leer().filter { it.proyecto == chat && !it.enBuzon }
        if (ChatDelAsistente.yaCortado(delChat)) return null
        MemoriaQueViaja.alCerrar(app, ChatDelAsistente.laQueSeCierra(delChat))
        return runCatching {
            almacen.anadir(
                Mensaje(
                    id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.NOTA,
                    texto = ChatDelAsistente.TEXTO_NUEVA_CONVERSACION, proyecto = chat,
                    aparato = ChatDelAsistente.APARATO, nuevaConversacionIa = true
                )
            )
            null
        }.getOrElse { Log.w(TAG, "no se pudo empezar una conversación nueva", it); "No se pudo empezar una conversación nueva." }
    }

    /**
     * **Contesta a [pregunta]**, que ya está guardada en el chat. La respuesta nace al instante con
     * «💭 …», se escribe en vivo y se guarda cada 2 s y al acabar. Una a la vez: si ya está
     * contestando, no hace nada (devuelve false).
     */
    fun preguntar(c: Context, pregunta: Mensaje, chatDelAsistente: String?): Boolean {
        val app = c.applicationContext as PixPinApp
        if (!ocupado.compareAndSet(false, true)) {
            // **Escribirle mientras trabaja** (25-oct-2026 en el PC, «como en Claude Code»): en el
            // chat del turno en curso, lo escrito contesta la duda que espera o le llega al modelo
            // antes de su vuelta siguiente («para» a secas lo corta). En otro chat, no.
            if (pregunta.proyecto != chatEnCurso) return false
            val texto = ChatDelAsistente.sinMencion(pregunta.texto).ifBlank { pregunta.texto }.trim()
            if (texto.isEmpty()) return true
            if (contestacionDuda != null) contestarDuda(com.forge.pixpin.ia.Preguntar.Contestada.Texto(texto)) else entrantes += texto
            return true
        }
        entrantes.clear()
        chatEnCurso = pregunta.proyecto
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
                chatEnCurso = null
                entrantes.clear()
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
    const val SIN_PERMISO_DOCUMENTOS = "el usuario no permite mandar sus documentos a la nube; dile que puede permitirlo en Ajustes › Asistente"

    /**
     * **El motor de este turno** (§1.4): Claude o DeepSeek con su clave. null y el motivo si falta
     * la clave.
     */
    private fun motorDelTurno(app: PixPinApp, ajustes: com.forge.pixpin.data.Settings): Pair<com.forge.pixpin.ia.Motor?, String?> {
        val proveedor = Nube.Proveedor.elegido(ajustes.asistenteMotor)
        val clave = ClavesDeLaNube.leer(app, proveedor) ?: return null to "Falta la clave de ${proveedor.nombre}: pégala en Ajustes → Asistente."
        return Nube.Motor(proveedor, clave, ligero = ajustes.asistentePensar == "no") to null
    }

    private fun contestar(app: PixPinApp, almacen: MensajesStore, pregunta: Mensaje, respuesta: Mensaje, enSuChat: Boolean) {
        val ajustes = runBlocking { app.settings.settings.first() }
        fun escribir(texto: String, lienzo: String? = null, fuentes: kotlinx.serialization.json.JsonArray? = null) =
            almacen.actualizar(respuesta.id) { it.copy(texto = texto, lienzoIa = lienzo ?: it.lienzoIa, fuentesIa = fuentes ?: it.fuentesIa) }
        val conFoto = ChatDelAsistente.fotoDe(almacen.leer(), pregunta) != null
        val (motor0, motivo) = motorDelTurno(app, ajustes)
        // Una orden simple sale sin modelo (camino rápido); sin motor, el resto avisa.
        val esOrden = !conFoto && com.forge.pixpin.ia.Rapido.interpretar(ChatDelAsistente.sinMencion(pregunta.texto), com.forge.pixpin.ia.Momento.ahora()) != null
        val motor = motor0 ?: if (esOrden) Agente.SinMotor(motivo.orEmpty()) else null
        if (motor == null) {
            escribir("⚠️ " + motivo.orEmpty())
            return
        }
        val mensajes = almacen.leer()
        val fotoArchivo = ChatDelAsistente.fotoDe(mensajes, pregunta)?.ruta?.let { File(it) }
        val foto = fotoArchivo?.let { FotoParaElModelo.deArchivo(it) }?.let { it to "image/jpeg" }
        // `@lienzo:Nombre` fija dónde se dibuja y se le dice al modelo como «el lienzo «Nombre»».
        val (pedido0, destino) = ChatDelAsistente.resolverLienzo(ChatDelAsistente.sinMencion(pregunta.texto), mensajes.filter { it.proyecto == pregunta.proyecto })
        // Las preguntas Sí/No y las dudas contestadas quedan en el texto de la nota (`❓ … → ✅ Sí`).
        val preguntas = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pedido = pedido0
        // «🌐 leyendo ugr.es…» mientras el ejecutor baja cada página (se conecta a la burbuja abajo).
        var alLeerLaWeb: (String) -> Unit = {}
        val ejecutor = EjecutorDelChat(
            app, almacen, pregunta.proyecto, motor, parar,
            confirmar = { q ->
                val r = preguntarSiNo(respuesta.id, q)
                preguntas += ChatDelAsistente.lineaDePregunta(q, r)
                r == true
            },
            pedido = pedido, foto = fotoArchivo, destino = destino, vocabularioOcr = ajustes.ocrVocabulario,
            permisoDocumentos = {
                when (runBlocking { app.settings.settings.first().asistenteDocumentosNube }) {
                    "si" -> null
                    "no" -> SIN_PERMISO_DOCUMENTOS
                    else -> {
                        val r = preguntarSiNo(respuesta.id, PREGUNTA_DOCUMENTOS)
                        preguntas += ChatDelAsistente.lineaDePregunta("¿Mando trozos de tus documentos a la nube?", r)
                        if (r != null) runBlocking { app.settings.setAsistenteDocumentosNube(if (r) "si" else "no") }
                        if (r == true) null else SIN_PERMISO_DOCUMENTOS
                    }
                }
            },
            // `preguntar_usuario`: la tarjeta con opciones en esta burbuja, y lo contestado al texto.
            dudar = { p ->
                val c = preguntarDuda(respuesta.id, p)
                preguntas += com.forge.pixpin.ia.Preguntar.lineaContestada(p, c)
                c
            },
            alLeerLaWeb = { dominio -> alLeerLaWeb(dominio) }
        )
        // La memoria del turno (§2.5): la hoja de trabajo del chat, el contexto medido y, si ya se
        // reinició alguna vez, buscar en lo guardado. A la nube va el catálogo entero. **Desde la
        // última «Conversación nueva»**: lo de antes (su historial, su hoja, sus fotos) no se manda.
        val delChat = ChatDelAsistente.desdeElCorte(mensajes.filter { it.proyecto == pregunta.proyecto && !it.enBuzon })
        val anteriores = delChat.filter { it.cuando <= pregunta.cuando && it.id != pregunta.id && it.id != respuesta.id }
        val hoja = PensadoDelChat.hojaDelChat(app, anteriores)
        val ventana = Nube.VENTANA
        // **La web** (28-oct-2026): si está encendida en Ajustes, `buscar_en_la_web` y `abrir_pagina`
        // (con Claude, `Nube.Claude.cuerpo` las cambia por `web_search` y `web_fetch` de la API).
        val herramientas = Herramientas.catalogo() + if (ajustes.asistenteWeb) com.forge.pixpin.asistente.web.Web.herramientas() else emptyList()
        // **El lienzo destino** pegado al pedido (`edicion::anadir_resumen` del PC): lo que ya hay,
        // con sus #id, para editarlo en vez de dibujar otro al lado.
        val paraElModelo = (if (com.forge.pixpin.ia.LienzoDestino.vieneACuento(pedido0)) ejecutor.resumenDelLienzo() else null)
            ?.let { com.forge.pixpin.ia.LienzoDestino.conResumen(pedido0, it) } ?: pedido0
        val (historial, reiniciado) = if (enSuChat) {
            val nuevo = if (foto != null) com.forge.pixpin.ia.MensajeIa.usuarioConImagen(paraElModelo, foto.first, foto.second) else com.forge.pixpin.ia.MensajeIa.usuario(paraElModelo)
            PensadoDelChat.historial(Agente.instrucciones(), hoja, anteriores, nuevo, ventana, herramientas)
        } else ChatDelAsistente.historial(Agente.instrucciones(), mensajes, pregunta.copy(texto = paraElModelo), enSuChat, foto) to false
        val fuentesDelChat = PensadoDelChat.fuentes(anteriores, hoja)
        val opciones = Agente.Opciones(
            planificar = true,
            pensar = when (ajustes.asistentePensar) { "si" -> true; "no" -> false; else -> false },
            hoja = hoja,
            ventana = ventana,
            buscar = if (enSuChat && (reiniciado || hoja.reinicios > 0)) { c -> com.forge.pixpin.ia.Contexto.buscar(fuentesDelChat, c, 3) } else null,
            rapido = com.forge.pixpin.ia.Momento.ahora(),
            // La memoria de largo plazo, la misma que la del PC (viaja por el chat «Asistente»).
            memoria = runCatching { MemoriaQueViaja.de(app, Nube.Proveedor.elegido(ajustes.asistenteMotor).nombre) }.getOrNull(),
            entrantes = { recogerEntrantes() },
            vueltas = Agente.VUELTAS_NUBE,
            empujar = true,
            pasos = true
        )
        val recogido = PensadoDelChat.Recogido()
        var vivo = EnVivo(respuesta.proyecto, respuesta.id, ChatDelAsistente.PENSANDO, reiniciado = reiniciado)

        // Lo que se va diciendo: el texto de esta vuelta, la línea de estado y los fallos.
        val texto = StringBuilder()
        var estado: String? = null
        var trasResultado = false
        // La lista de pasos del pedido, mientras trabaja (`pasos_del_pedido`), encima del estado.
        var pasosVivos = ""
        val fallos = mutableListOf<String>()
        var ultimoPintado = 0L
        var ultimoGuardado = System.currentTimeMillis()
        fun pintar(forzar: Boolean = false) {
            val ahora = System.currentTimeMillis()
            if (!forzar && ahora - ultimoPintado < 60) return
            ultimoPintado = ahora
            val conPasos = if (pasosVivos.isEmpty()) estado else listOfNotNull(pasosVivos, estado).joinToString("\n")
            val visible = ChatDelAsistente.conPreguntas(preguntas, ChatDelAsistente.mostrar(ChatDelAsistente.sinPensado(texto.toString()), conPasos, escribiendo = true))
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
        alLeerLaWeb = { dominio ->
            estado = ChatDelAsistente.rotuloDe("${com.forge.pixpin.asistente.web.Web.ABRIR}:$dominio")
            pintar(forzar = true)
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
                    is Agente.Evento.Tareas -> pasosVivos = com.forge.pixpin.ia.Pasos.lineas(e.pasos)
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
