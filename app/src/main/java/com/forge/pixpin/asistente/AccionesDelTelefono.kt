package com.forge.pixpin.asistente

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.ia.Conocimiento
import com.forge.pixpin.ia.Figuras
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.ia.PedidoFiguras
import com.forge.pixpin.ia.Tareas
import com.forge.pixpin.mini.MiniApp
import com.forge.pixpin.mini.TodasLasTareas
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Scene
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.forge.pixpin.mini.Tareas as Casillas

/**
 * **Las acciones de la app, hechas en el teléfono** (lo que en el PC es `pedidos::atender` con
 * los pedidos que arman las filas de [Acciones]): abrir chats, ficheros, notas y lienzos; notas,
 * lienzos y listas nuevas; las tareas por su nombre ([Tareas.resolver]); ventanas; capturar,
 * pines y portapapeles; lecciones; voz y audio; la ayuda ([Conocimiento]) y la biblioteca de
 * figuras.
 *
 * Todo corre en el hilo del turno (`Dispatchers.IO`): el disco aquí, y lo que toca pantallas
 * (abrir una actividad, un pin, el reproductor) se manda al hilo principal. Las rutas que da el
 * modelo no son de este aparato: se buscan entre los adjuntos de los chats por la ruta o por el
 * nombre del fichero (regla de la casa: el modelo nunca pide rutas al usuario).
 */
class AccionesDelTelefono(
    private val contexto: Context,
    private val almacen: MensajesStore,
    /** El chat donde se preguntó (null: Mensajes guardados). */
    private val chat: String?,
    /** Pregunta Sí/No en la burbuja; false si dice que no o no contesta. */
    private val confirmar: (String) -> Boolean
) {
    private val app get() = contexto.applicationContext as PixPinApp

    private fun enPantalla(bloque: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { bloque(); return }
        val hecho = CountDownLatch(1)
        var fallo: Throwable? = null
        Handler(Looper.getMainLooper()).post { try { bloque() } catch (e: Throwable) { fallo = e } finally { hecho.countDown() } }
        hecho.await(10, TimeUnit.SECONDS)
        fallo?.let { throw it }
    }

    private fun abrir(i: Intent) = enPantalla { contexto.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    private fun texto(o: JsonObject, campo: String): String? =
        (o[campo] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** La ayuda de la app. */
    fun ayuda(pregunta: String): Result<String> = Result.success(Conocimiento.responder(pregunta))

    // ------------------------------------------------------------------------------- acciones

    /** **Hace una acción de la tabla.** `success`: la frase de «hecho»; `failure`: el motivo. */
    fun hacer(a: Acciones.AccionApp): Result<String> = runCatching {
        val fila = a.fila
        if (fila.categoria == Acciones.Categoria.TAREAS && fila.herramienta != "crear_automatizacion") {
            return@runCatching try {
                Tareas.hacer(a, fuenteDeTareas(), confirmar) { mandarTarea(it) }
            } catch (e: Tareas.NoCasa) {
                error(e.message ?: Tareas.NO_HICE_NADA)
            }
        }
        a.confirmar?.let { if (!confirmar(it)) error("el usuario dijo que no; no se hizo nada") }
        val p = a.pedido
        val hecho = Acciones.rellenar(fila.hecho, a.valores())
        when (a.herramienta) {
            "abrir_chat" -> { abrir(Intent(contexto, com.forge.pixpin.guardados.MensajesActivity::class.java)); hecho }
            "abrir_fichero" -> {
                val ruta = texto(p["que"] as JsonObject, "ruta")!!
                val m = adjunto(ruta) ?: error("no encuentro «${File(ruta).name}» entre los adjuntos de los chats")
                enPantalla { com.forge.pixpin.guardados.MensajesActivity.abrirYAbrir(contexto, m.id) }
                "Abriendo «${m.nombre.ifBlank { File(m.ruta!!).name }}»."
            }
            "abrir_mensaje", "abrir_nota" -> {
                val codigo = texto(p["que"] as JsonObject, "codigo")!!
                val m = almacen.leer().firstOrNull { it.id == codigo || it.uid == codigo } ?: error("no hay ningun mensaje «$codigo»")
                enPantalla {
                    if (a.herramienta == "abrir_nota") com.forge.pixpin.guardados.MensajesActivity.abrirYAbrir(contexto, m.id)
                    else com.forge.pixpin.guardados.MensajesActivity.irAlMensaje(contexto, m.proyecto, nombreDelChat(m.proyecto), m.id)
                }
                hecho
            }
            "abrir_hoja" -> {
                val ref = texto(p["que"] as JsonObject, "referencia")!!
                val m = almacen.leer().lastOrNull { it.referencia == ref && (it.clase == Clase.DIBUJO || it.clase == Clase.PAGINA) }
                    ?: almacen.leer().lastOrNull { it.clase == Clase.DIBUJO && Tareas.parecido(ref, it.nombre) >= 70 }
                if (m != null) enPantalla { com.forge.pixpin.guardados.MensajesActivity.abrirYAbrir(contexto, m.id) }
                else {
                    val ruta = ExcalidrawStore.rutaDe(contexto, ref)
                    if (!File(ruta).isFile) error("no hay ningun lienzo «$ref»")
                    enPantalla { com.forge.pixpin.motor.DrawEditorActivity.abrir(contexto, ref, ruta, null) }
                }
                hecho
            }
            "escribir_en_chat" -> {
                nota(texto(p, "texto")!!, null)
                hecho
            }
            "nota_nueva" -> {
                val t = texto(p, "texto")
                if (t != null) {
                    nota(t, chat)
                    "Nota nueva creada en el chat: «${t.lineSequence().first().take(60)}»."
                } else {
                    enPantalla { com.forge.pixpin.ui.MarkdownEditorActivity.abrir(contexto, "", "") }
                    hecho
                }
            }
            "lienzo_nuevo" -> {
                val ahora = System.currentTimeMillis()
                val id = "dib-$ahora"
                val nombre = texto(p, "nombre") ?: "Lienzo"
                ExcalidrawStore.guardar(contexto, id, Scene()) ?: error("no se pudo crear el lienzo")
                almacen.anadir(Mensaje(id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.DIBUJO, nombre = nombre, referencia = id, proyecto = chat))
                enPantalla { com.forge.pixpin.motor.DrawEditorActivity.abrir(contexto, id, ExcalidrawStore.rutaDe(contexto, id), null) }
                hecho
            }
            "grabar_voz" -> {
                enPantalla { com.forge.pixpin.pin.GrabadoraActivity.abrir(contexto) }
                "Abierta la grabadora: pulsa grabar para «${texto(p, "nombre")}» (la nota de voz va a «Mensajes guardados»)."
            }
            "reproducir_audio" -> {
                val ruta = texto(p, "ruta")!!
                val m = adjunto(ruta, soloAudio = true) ?: error("no encuentro el audio «${File(ruta).name}»")
                val titulo = texto(p, "titulo") ?: m.nombre.ifBlank { File(m.ruta!!).nameWithoutExtension }
                enPantalla { com.forge.pixpin.guardados.Reproductor.cargar(m.ruta!!, titulo, true) }
                "Reproduciendo «$titulo»."
            }
            "abrir_ventana" -> {
                when (texto(p, "cual")) {
                    "tareas" -> enPantalla { com.forge.pixpin.mini.TodasLasTareasActivity.abrir(contexto) }
                    "galeria" -> enPantalla { com.forge.pixpin.ui.GaleriaDeCapturasActivity.abrir(contexto) }
                    "lecciones" -> enPantalla { com.forge.pixpin.lecciones.LeccionesActivity.abrir(contexto) }
                    else -> abrir(Intent(contexto, com.forge.pixpin.sincro.SincronizarActivity::class.java))
                }
                hecho
            }
            "capturar_pantalla" -> {
                enPantalla { com.forge.pixpin.capture.CaptureFlow.requestCapture(contexto) }
                "Elige la zona que quieres capturar (con la bola flotante)."
            }
            "pinear_ultima_captura" -> {
                val c = com.forge.pixpin.capture.BarrenderoDeCapturas.listar(contexto)?.firstOrNull { !it.esVideo }
                    ?: error("no hay ninguna captura")
                sinPermisoDeSuperponer()
                app.scope.launch(kotlinx.coroutines.Dispatchers.Main) { app.overlayManager.pinFromUri(c.uri) }
                hecho
            }
            "pinear_fichero" -> {
                val ruta = texto(p, "ruta")!!
                val m = adjunto(ruta) ?: error("no encuentro «${File(ruta).name}» entre los adjuntos de los chats")
                val f = File(m.ruta!!)
                sinPermisoDeSuperponer()
                enPantalla {
                    if (m.clase == Clase.IMAGEN) app.overlayManager.pinImage(f.path)
                    else app.overlayManager.pinFile(f.path, m.nombre.ifBlank { f.name }, mime(f))
                }
                "«${m.nombre.ifBlank { f.name }}», como pin en la pantalla."
            }
            "copiar_imagen" -> {
                val ruta = texto(p, "ruta")!!
                val m = adjunto(ruta, soloImagen = true) ?: error("no encuentro la imagen «${File(ruta).name}»")
                val bm = android.graphics.BitmapFactory.decodeFile(m.ruta) ?: error("no se pudo leer la imagen")
                var ok = false
                enPantalla { ok = com.forge.pixpin.capture.Export.copyToClipboard(contexto, bm) }
                if (!ok) error("no se pudo copiar al portapapeles")
                hecho
            }
            "leccion_nueva" -> {
                enPantalla { com.forge.pixpin.lecciones.LeccionActivity.nueva(contexto, texto(p, "texto"), proyecto = chat) }
                hecho
            }
            "buscar_lecciones" -> {
                enPantalla { com.forge.pixpin.lecciones.LeccionesActivity.abrir(contexto, null, texto(p, "consulta")) }
                hecho
            }
            // ---- 15-oct, solo con la nube (PC `SOLO_NUBE`) ----
            "capturar_con_modo" -> {
                val modo = texto(p, "modo") ?: "zona"
                when (modo) {
                    "zona", "copiar", "pin", "anotar", "texto" -> enPantalla { com.forge.pixpin.capture.CaptureFlow.requestCapture(contexto) }
                    else -> error("en el teléfono la captura «$modo» no existe: hay la de zona (y desde ella copiar, pinear o anotar)")
                }
                "Elige la zona que quieres capturar (con la bola flotante)" + if (modo != "zona") "; luego «$modo» desde la barra de la captura." else "."
            }
            "abrir_otra_ventana" -> {
                when (texto(p, "cual")) {
                    "ajustes" -> abrir(Intent(contexto, com.forge.pixpin.MainActivity::class.java).putExtra(com.forge.pixpin.MainActivity.EXTRA_AJUSTES, true))
                    "buscar" -> abrir(Intent(contexto, com.forge.pixpin.atajos.BuscarActivity::class.java))
                    else -> error("el teléfono no tiene timeline")
                }
                hecho
            }
            "ir_al_chat" -> {
                val nombre = a.porResolver["chat"]?.let { (it as? JsonPrimitive)?.content }?.trim().orEmpty()
                val proyectos = app.proyectos.proyectos.value.filter { !it.archivado }
                val q = com.forge.pixpin.guardados.sinAcentos(nombre)
                val p2 = proyectos.firstOrNull { com.forge.pixpin.guardados.sinAcentos(it.nombre) == q }
                    ?: proyectos.firstOrNull { com.forge.pixpin.guardados.sinAcentos(it.nombre).contains(q) }
                val general = q.isEmpty() || q.contains("guardad") || q.contains("general")
                if (p2 == null && !general) error("no hay ningun chat «$nombre»")
                enPantalla {
                    if (p2 != null) com.forge.pixpin.guardados.MensajesActivity.abrirChatDe(contexto, p2.id, p2.nombre)
                    else contexto.startActivity(Intent(contexto, com.forge.pixpin.guardados.MensajesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "Abriendo el chat «${p2?.nombre ?: "Mensajes guardados"}»."
            }
            "ir_a_pagina" -> {
                val que = p["que"] as JsonObject
                val ruta = texto(que, "ruta")!!
                val m = adjunto(ruta) ?: error("no encuentro «${File(ruta).name}» entre los adjuntos de los chats")
                val pagina = (que["pagina"] as? JsonPrimitive)?.content?.toIntOrNull()
                enPantalla { com.forge.pixpin.pdf.LectorPdfActivity.abrir(contexto, m.ruta!!, m.nombre.ifBlank { File(m.ruta).name }, mensaje = m.id, pagina = pagina?.minus(1)) }
                hecho.replace(ruta, m.nombre.ifBlank { File(m.ruta).name })
            }
            "grabar_pantalla" -> error("en el teléfono no se graba la pantalla desde PixPin: usa la grabación de pantalla del sistema")
            "cerrar_pines" -> {
                enPantalla { app.overlayManager.cerrarTodos() }
                hecho
            }
            "manejar_pines" -> {
                when (texto(p, "operacion")) {
                    "ocultar_mostrar" -> enPantalla { app.overlayManager.toggleHideAll() }
                    "devolver_ultimo" -> enPantalla { app.overlayManager.restoreLastClosed() }
                    else -> error("en el teléfono los pines no dejan pasar el toque desde aquí: mantén el pin y elige «Dejar pasar los toques»")
                }
                hecho
            }
            "apuntar_en_timeline" -> {
                // El teléfono no tiene timeline: el momento queda como nota con su hora en el chat.
                val t = texto(p, "texto")!!
                nota("🕒 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) + " · " + t, chat)
                "Apuntado como nota con su hora (el teléfono no tiene timeline): «${t.take(60)}»."
            }
            "parar_lectura" -> {
                enPantalla { VozDelAsistente.callar(contexto) }
                hecho
            }
            Acciones.ABRIR -> abrirPorNombre(texto(p, "descripcion")!!)
            // Las capturas (PC «conservar» y «a la papelera»): las de la galería de PixPin, por su nombre.
            "conservar_captura" -> {
                val c = captura(texto(p, "ruta")!!)
                val raiz = com.forge.pixpin.capture.BarrenderoDeCapturas.raiz(contexto)
                if (!com.forge.pixpin.guardados.AlChat.meter(contexto, c.uri, null, c.nombre)) error("no se pudo meter «${c.nombre}» en Mensajes guardados")
                com.forge.pixpin.capture.CaducidadDeCapturas.cambiar(raiz, System.currentTimeMillis()) { it.copy(conservadas = it.conservadas + c.nombre) }
                "Captura «${c.nombre}» conservada: está en Mensajes guardados y ya no se borra."
            }
            "borrar_captura" -> {
                val c = captura(texto(p, "ruta")!!)
                if (com.forge.pixpin.capture.BarrenderoDeCapturas.aLaPapelera(contexto, listOf(c.uri)).isNotEmpty()) {
                    error("«${c.nombre}» no es de PixPin: bórrala desde la galería de capturas (pide permiso al sistema)")
                }
                val raiz = com.forge.pixpin.capture.BarrenderoDeCapturas.raiz(contexto)
                runCatching { com.forge.pixpin.capture.CaducidadDeCapturas.cambiar(raiz, System.currentTimeMillis()) { com.forge.pixpin.capture.CaducidadDeCapturas.sinEstas(it, listOf(c.nombre)) } }
                "Captura «${c.nombre}» en la papelera (se recupera desde la galería)."
            }
            // La caja flotante del PC: en el teléfono, el chat con el selector de archivos abierto.
            "soltar" -> {
                enPantalla { com.forge.pixpin.guardados.MensajesActivity.adjuntar(contexto, chat, nombreDelChat(chat)) }
                "Elige los archivos: entran al chat «${nombreDelChat(chat)}»."
            }
            // Sincronizar ahora con los aparatos del grupo que se vean (el PC, el portátil…).
            "sincronizar" -> {
                enPantalla { com.forge.pixpin.sincro.SincronizarActivity.abrir(contexto, ya = true) }
                "Sincronizando con los aparatos del grupo que estén en la Wi-Fi."
            }
            // Mandar fotos a lo que tengan abierto los otros aparatos (en el PC, «al móvil»; aquí, al PC).
            "enviar_al_movil" -> enviarAlGrupo(p)
            "automatizacion" -> {
                val receta = p["receta"] as? JsonObject ?: error("falta la receta")
                val ahora = System.currentTimeMillis()
                val a = try {
                    com.forge.pixpin.ia.Automatizaciones.deReceta(receta, com.forge.pixpin.ia.Automatizaciones.idNuevo(ahora), ahora)
                } catch (e: IllegalArgumentException) { error("la automatización no vale: ${e.message}") }
                RecetasDelTelefono.cambiar(contexto) { it.anadir(a) }
                val disparo = (a["disparador"] as JsonObject)["tipo"].toString().trim('"')
                "$hecho Corre en el PC (el teléfono no lanza automatizaciones solas; ${if (disparo == "a_mano") "a mano, desde Ajustes → Asistente" else "se dispara sola allí"})."
            }
            else -> error("«${a.herramienta}» no se puede hacer en el teléfono")
        }
    }

    /** Una captura de la galería de PixPin por su nombre (o su ruta). */
    private fun captura(ruta: String): com.forge.pixpin.capture.BarrenderoDeCapturas.Captura {
        val nombre = File(ruta).name
        val todas = com.forge.pixpin.capture.BarrenderoDeCapturas.listar(contexto) ?: error("no se pudo leer la galería de capturas")
        return todas.firstOrNull { it.nombre == nombre } ?: todas.firstOrNull { it.nombre.equals(nombre, true) }
            ?: error("no hay ninguna captura «$nombre» en la galería de PixPin")
    }

    /** **Fotos a los aparatos del grupo** por el canal cifrado (`suelto`, como «p s» del PC): a lo que tengan abierto. */
    private fun enviarAlGrupo(p: JsonObject): String {
        val rutas = (p["imagenes"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val ficheros = rutas.map(::File).filter { it.isFile }
        if (ficheros.isEmpty()) error("no encuentro ninguna de esas imágenes en el teléfono")
        val disco = com.forge.pixpin.sincro.Red.disco(contexto)
        val quien = texto(p, "aparato")?.takeIf { it != "todos" }
        val vistos = runCatching { kotlinx.coroutines.runBlocking { com.forge.pixpin.sincro.AlDia.aparatos(contexto) } }.getOrDefault(emptyList()).associateBy { it.id }
        val grupo = com.forge.pixpin.sincro.AlGrupo.aparatos(disco).filter { quien == null || it.id == quien || it.nombre.equals(quien, true) }
        if (grupo.isEmpty()) error(if (quien != null) "no hay ningún aparato «$quien» en el grupo" else "este teléfono no está en ningún grupo: únelo en Sincronizar")
        val dichos = grupo.map { a ->
            val hecho = com.forge.pixpin.sincro.AlGrupo.mandarYa(disco, a.id, ficheros.map { com.forge.pixpin.sincro.AlGrupo.Fichero(it, it.name) },
                miPuerto = runCatching { com.forge.pixpin.sincro.Presencia.red.puerto }.getOrDefault(0),
                localizar = { vistos[it.id]?.let { v -> v.host to v.puerto } ?: it.direccion })
            com.forge.pixpin.sincro.AlGrupo.aviso(hecho)
        }
        return "Enviado: " + dichos.joinToString("; ")
    }

    private fun sinPermisoDeSuperponer() {
        if (!android.provider.Settings.canDrawOverlays(contexto)) error("falta el permiso «Mostrar sobre otras apps» para los pines")
    }

    private fun mime(f: File): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

    private fun nombreDelChat(proyecto: String?): String =
        proyecto?.let { app.proyectos.porId(it)?.nombre } ?: TodasLasTareas.GUARDADOS

    /** Una nota de texto en un chat, como la escribe el usuario (con el aparato de este teléfono). */
    private fun nota(texto: String, proyecto: String?) {
        almacen.anadir(Mensaje(id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.NOTA, texto = texto, proyecto = proyecto))
    }

    /**
     * **El adjunto que nombra el modelo**: por su ruta exacta; si no, por el nombre del fichero
     * (las rutas del PC no existen aquí); si no, el que más se parece por su nombre.
     */
    private fun adjunto(ruta: String, soloAudio: Boolean = false, soloImagen: Boolean = false): Mensaje? {
        val todos = almacen.leer().filter { m ->
            val r = m.ruta ?: return@filter false
            File(r).isFile && (!soloAudio || m.clase == Clase.VOZ || r.substringAfterLast('.').lowercase() in setOf("m4a", "mp3", "wav", "ogg", "aac", "opus")) &&
                (!soloImagen || m.clase == Clase.IMAGEN)
        }
        todos.lastOrNull { it.ruta == ruta }?.let { return it }
        val nombre = ruta.substringAfterLast('/').substringAfterLast('\\')
        todos.lastOrNull { it.nombre.equals(nombre, true) || File(it.ruta!!).name.equals(nombre, true) }?.let { return it }
        return todos.map { Tareas.parecido(nombre.substringBeforeLast('.'), it.nombre.ifBlank { File(it.ruta!!).name }) to it }
            .filter { it.first >= 60 }.maxByOrNull { it.first }?.second
    }

    /**
     * **Abrir un documento por su nombre o de qué trata** (`abrir_por_nombre`): si uno destaca, se
     * abre; si hay varios igual de parecidos, se le dice al modelo cuáles para que pregunte.
     */
    private fun abrirPorNombre(descripcion: String): String {
        val candidatos = almacen.leer().filter { m ->
            !m.enBuzon && m.ruta != null && File(m.ruta).isFile &&
                m.clase in setOf(Clase.ARCHIVO, Clase.IMAGEN, Clase.PAGINA, Clase.VOZ)
        }
        val puntuados = candidatos.map { m ->
            val nombre = m.nombre.ifBlank { File(m.ruta!!).name }.substringBeforeLast('.')
            maxOf(Tareas.parecido(descripcion, nombre), Tareas.parecido(descripcion, m.texto).let { if (it > 0) it - 20 else 0 }) to m
        }.filter { it.first > 0 }.sortedWith(compareByDescending<Pair<Int, Mensaje>> { it.first }.thenByDescending { it.second.cuando })
        if (puntuados.isEmpty()) error("no encuentro ningun documento que se parezca a «$descripcion» en los chats")
        val mejor = puntuados[0].first
        val empatados = puntuados.filter { it.first == mejor }
        if (empatados.size > 1) {
            val lista = empatados.take(5).joinToString(", ") { (_, m) -> "«${m.nombre.ifBlank { File(m.ruta!!).name }}» (${nombreDelChat(m.proyecto)})" }
            return "Hay varios parecidos: $lista. No abri nada: pregunta al usuario cual."
        }
        val m = puntuados[0].second
        enPantalla { com.forge.pixpin.guardados.MensajesActivity.abrirYAbrir(contexto, m.id) }
        return "Abriendo «${m.nombre.ifBlank { File(m.ruta!!).name }}» (${nombreDelChat(m.proyecto)})."
    }

    // ---------------------------------------------------------------------------------- tareas

    /** Las listas y los chats como los ve [Tareas]: «Mensajes guardados» es el chat de id "". */
    private fun fuenteDeTareas(): Tareas.Fuente = object : Tareas.Fuente {
        override fun listas(): List<Tareas.VistaLista> {
            val nombres = app.proyectos.proyectos.value.associate { it.id to it.nombre }
            return TodasLasTareas.reunir(almacen.leer().filter { !it.enBuzon }, nombres).map { l ->
                Tareas.VistaLista(
                    proyecto = l.proyecto ?: "", chat = l.chat, guardados = l.guardados, codigo = l.codigo, titulo = l.titulo, cuando = l.cuando,
                    tareas = l.filas.map { f ->
                        Tareas.VistaTarea(f.indice, f.texto, f.hecha, Casillas.horaDe(f.crudo)?.let { Momento(Fechas.diasDe(it.year.toLong(), it.monthValue.toLong(), it.dayOfMonth.toLong()), it.hour * 60 + it.minute) })
                    }
                )
            }
        }

        override fun chats(): List<Tareas.Chat> =
            listOf(Tareas.Chat("", TodasLasTareas.GUARDADOS, true)) +
                app.proyectos.proyectos.value.filter { !it.archivado }.map { Tareas.Chat(it.id, it.nombre, false) }

        override fun ahora(): Momento = Momento.ahora()
    }

    private fun proyectoDe(p: JsonObject, campo: String = "proyecto"): String? = texto(p, campo)?.takeIf { it.isNotEmpty() }

    /** **Un pedido de tareas del protocolo**, hecho sobre los mensajes del chat. */
    private fun mandarTarea(p: JsonObject) {
        val accion = texto(p, "accion")
        val codigo = texto(p, "codigo")
        val indice = (p["indice"] as? JsonPrimitive)?.intOrNull
        fun cambiarLista(id: String, cambio: (String) -> String?) {
            var hecho = false
            almacen.cambiar { todos -> todos.map { m -> if (m.id == id) cambio(m.texto)?.let { hecho = true; m.copy(texto = it) } ?: m else m } }
            if (!hecho) error("la lista cambio mientras tanto; vuelve a intentarlo")
        }
        fun casillas(doc: String) = Casillas.leer(doc)
        fun titulo(doc: String) = com.forge.pixpin.mini.Cabecera.titulo(doc)
        when (accion) {
            "lista_nueva" -> almacen.anadir(
                Mensaje(
                    id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.MINIAPP, miniapp = MiniApp.TAREAS.id,
                    texto = Casillas.escribir(texto(p, "titulo") ?: "Tareas", emptyList()), proyecto = proyectoDe(p)
                )
            )
            "anadir_tarea" -> {
                val t = texto(p, "texto") ?: error("la tarea esta vacia")
                if (Casillas.saneado(t).isEmpty()) error("la tarea esta vacia")
                when {
                    codigo != null -> cambiarLista(codigo) { TodasLasTareas.conTarea(it, t) }
                    proyectoDe(p) != null -> {
                        val ultima = almacen.leer().lastOrNull { it.proyecto == proyectoDe(p) && TodasLasTareas.esLista(it) && !it.enBuzon }
                        if (ultima != null) cambiarLista(ultima.id) { TodasLasTareas.conTarea(it, t) }
                        else almacen.anadir(
                            Mensaje(
                                id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.MINIAPP, miniapp = MiniApp.TAREAS.id,
                                texto = TodasLasTareas.conTarea(Casillas.escribir("Tareas", emptyList()), t), proyecto = proyectoDe(p)
                            )
                        )
                    }
                    else -> { com.forge.pixpin.mini.TareaRapidaActivity.alInbox(contexto, t, entender = false) }
                }
            }
            "marcar_tarea" -> cambiarLista(codigo!!) { doc ->
                val ts = casillas(doc); if (indice !in ts.indices) null
                else Casillas.escribir(titulo(doc), Casillas.marcar(ts, indice!!, (p["hecha"] as? JsonPrimitive)?.booleanOrNull ?: true))
            }
            "corregir_tarea" -> cambiarLista(codigo!!) { doc ->
                val ts = casillas(doc); if (indice !in ts.indices) null
                else Casillas.escribir(titulo(doc), Casillas.renombrar(ts, indice!!, texto(p, "texto") ?: return@cambiarLista null))
            }
            "quitar_tarea" -> cambiarLista(codigo!!) { doc ->
                val ts = casillas(doc); if (indice !in ts.indices) null else Casillas.escribir(titulo(doc), Casillas.borrar(ts, indice!!))
            }
            "mover_tarea" -> {
                val destino = texto(p, "a_codigo")!!
                var hecho = false
                almacen.cambiar { todos ->
                    val o = todos.firstOrNull { it.id == codigo } ?: return@cambiar todos
                    val d = todos.firstOrNull { it.id == destino } ?: return@cambiar todos
                    val ts = casillas(o.texto)
                    if (indice !in ts.indices) return@cambiar todos
                    val tarea = ts[indice!!]
                    val nuevoO = Casillas.escribir(titulo(o.texto), Casillas.borrar(ts, indice))
                    val nuevoD = Casillas.escribir(titulo(d.texto), casillas(d.texto) + tarea)
                    hecho = true
                    todos.map { m -> when (m.id) { o.id -> m.copy(texto = nuevoO); d.id -> m.copy(texto = nuevoD); else -> m } }
                }
                if (!hecho) error("la lista cambio mientras tanto; vuelve a intentarlo")
            }
            "borrar_lista" -> almacen.cambiar { todos -> todos.filterNot { it.id == codigo } }
            else -> error("pedido de tareas desconocido: $accion")
        }
    }

    // -------------------------------------------------------------------------------- figuras

    /** **La biblioteca de figuras** (la misma del menú del lienzo). */
    fun figuras(pedido: PedidoFiguras, alPoner: (List<com.forge.pixpin.motor.Element>) -> Result<String>): Result<String> = runCatching {
        val lista = com.forge.pixpin.motor.BibliotecaStore.cargar(contexto)
        when (pedido) {
            PedidoFiguras.Lista -> Figuras.lista(lista.map { it.nombre })
            is PedidoFiguras.Poner -> {
                val i = Figuras.buscar(lista.map { it.nombre }, pedido.nombre)
                    ?: error("no hay ninguna figura «${pedido.nombre}». ${Figuras.lista(lista.map { it.nombre })}")
                alPoner(lista[i].elementos).getOrThrow().let { "puesta «${lista[i].nombre}»: $it" }
            }
            is PedidoFiguras.Guardar -> {
                val ultimo = ChatDelAsistente.lienzoPropio(almacen.leer(), chat)?.referencia
                    ?: error("no hay nada dibujado por el asistente en este chat que guardar")
                val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, ultimo)) ?: error("no se pudo leer el lienzo")
                val elementos = escena.elements.filter { !it.isDeleted }
                if (elementos.isEmpty()) error("el lienzo esta vacio")
                val figura = com.forge.pixpin.motor.FiguraGuardada(
                    id = "fig-${System.currentTimeMillis()}", nombre = pedido.nombre,
                    elementos = com.forge.pixpin.motor.normalizarFigura(elementos), propia = true
                )
                com.forge.pixpin.motor.BibliotecaStore.anadir(contexto, figura)
                "guardada «${pedido.nombre}» (${elementos.size} elementos) en la biblioteca de figuras"
            }
        }
    }

    private object Fechas {
        fun diasDe(y: Long, m: Long, d: Long) = com.forge.pixpin.ia.Fechas.diasDe(y, m, d)
    }
}
