package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * **Toda la app como herramientas del asistente, sacadas de UNA tabla** (`acciones.rs` y
 * `acciones_documentos.rs` del PC, portados tal cual).
 *
 * Cada fila de [ACCIONES] dice su nombre para el modelo, la `accion` del pedido del protocolo
 * (`docs/protocolo-pedidos.md` del PC), lo fijo del pedido, sus parámetros (tipo, si es
 * obligatorio, a qué campo del pedido va), si hay que preguntar antes y la frase corta de «hecho».
 * [herramienta] genera el JSON Schema y [validar] el pedido, para todas: **el mismo esquema que el
 * PC byte a byte** (lo comprueba `AccionesTest` contra el catálogo volcado del PC).
 *
 * La tabla lleva también las filas que solo tienen sentido en Windows (pines en el escritorio, la
 * caja para soltar…): así la selección por turno da lo mismo que en el PC. Cuáles se ofrecen en
 * el teléfono lo decide [Herramientas.catalogo].
 *
 * **Lo que borra o saca algo del aparato pide permiso** ([Accion.confirmar]).
 *
 * **Las tareas por su nombre**: los parámetros con destino [POR_RESOLVER] no van al pedido sino a
 * [AccionApp.porResolver], y [Tareas.resolver] los cambia por los `codigo`, `indice` y `proyecto`
 * de verdad.
 */
object Acciones {
    enum class Categoria { ABRIR, NOTAS, TAREAS, CAPTURAS, AUDIO, LECCIONES, MOVIL }

    sealed class Tipo {
        object Texto : Tipo()
        /** Una ruta de fichero (texto, pero se le quitan las comillas). */
        object Ruta : Tipo()
        object Rutas : Tipo()
        object Textos : Tipo()
        object Entero : Tipo()
        object SiNo : Tipo()
        class Opciones(val o: List<String>) : Tipo()
        object Numeros : Tipo()
    }

    data class Param(
        val nombre: String,
        /** Dónde va en el pedido, con puntos (`que.ruta`). Vacío: su nombre. `?`: lo resuelve [Tareas]. */
        val destino: String = "",
        val tipo: Tipo,
        val obligatorio: Boolean,
        val descripcion: String,
        /** Lo que vale si el modelo no lo pone (JSON). */
        val defecto: String? = null,
        val largo: Int? = null
    )

    data class Accion(
        val herramienta: String,
        val accion: String,
        val descripcion: String,
        val categoria: Categoria,
        val claves: List<String>,
        val fijos: String,
        val params: List<Param>,
        val confirmar: String?,
        val hecho: String
    )

    const val LARGO_TEXTO = 2000
    const val LARGO_LARGO = 8000
    const val POR_RESOLVER = "?"
    /** Lo que hace el ejecutor del chat y le devuelve al modelo lo que salió (PC `EN_EL_CHAT`). */
    const val EN_EL_CHAT = "(en el chat)"
    val MODOS_DE_CAPTURA = listOf("zona", "copiar", "pin", "en_vivo", "anotar", "texto", "scroll", "repetir", "retardo")
    val OPERACIONES_DE_PINES = listOf("ocultar_mostrar", "devolver_ultimo", "paso_de_clics")
    /**
     * **Las filas del 15-oct, solo para la nube** (PC `acciones::SOLO_NUBE`): el usuario, «la IA local
     * aún déjala, no la toques». El modelo local no las ve en su selección por turno.
     */
    val SOLO_NUBE = setOf(
        "capturar_con_modo", "abrir_otra_ventana", "ir_al_chat", "ir_a_pagina", "grabar_pantalla", "cerrar_pines", "manejar_pines",
        "apuntar_en_timeline", "leer_en_voz_alta", "parar_lectura", "comprimir_pdf", "transcribir_audio", "leer_excel", "resumen_de_actividad",
        "leer_documento", "lienzos_del_chat", "crear_lienzo", "usar_lienzo",
        "buscar_en_conexion", "leer_de_conexion", "bajar_de_conexion", "subir_a_conexion", "ver_calendario"
    )

    fun esSoloNube(herramienta: String) = herramienta in SOLO_NUBE

    const val LIENZOS_DEL_CHAT = "lienzos_del_chat"
    const val CREAR_LIENZO = "crear_lienzo"
    const val USAR_LIENZO = "usar_lienzo"
    const val CONSULTA = "(consulta)"

    private fun p(nombre: String, tipo: Tipo, obligatorio: Boolean, descripcion: String) =
        Param(nombre = nombre, tipo = tipo, obligatorio = obligatorio, descripcion = descripcion)
    private fun a(destino: String, p: Param) = p.copy(destino = destino)
    private fun d(defecto: String, p: Param) = p.copy(defecto = defecto)
    private fun r(p: Param) = p.copy(destino = POR_RESOLVER)
    private fun l(largo: Int, p: Param) = p.copy(largo = largo)
    private fun op(vararg o: String) = Tipo.Opciones(o.toList())

    private const val RUTA = "ruta absoluta del fichero en este PC"
    private const val LISTA = "nombre de la lista; omitir si no la dice"
    private const val TAREA = "texto de la tarea (o un trozo)"
    private const val FECHA = "fecha tal como la dijo («mañana a las 10», «el viernes»)"
    private const val CHAT = "chat de la lista, solo si lo dice"

    /** **La tabla de la app.** Una fila por herramienta, en el orden del PC. */
    val ACCIONES: List<Accion> = listOf(
        Accion("abrir_chat", "ventana_principal", "Abre (o trae delante) la ventana del chat de proyectos.", Categoria.ABRIR,
            listOf("chat", "proyecto", "ventana principal", "conversacion"), "{}", emptyList(), null, "Abriendo el chat."),
        Accion("abrir_fichero", "abrir", "Abre un fichero en PixPin (PDF, Word, Excel, imagen, plano...), como «Abrir con PixPin».", Categoria.ABRIR,
            listOf("abre", "abrir", "archivo", "fichero", "documento", "pdf", "docx", "xlsx", "dwg", ":\\"),
            """{"que":{"tipo":"fichero"}}""", listOf(a("que.ruta", p("ruta", Tipo.Ruta, true, RUTA))), null, "Abriendo {ruta}."),
        Accion("abrir_mensaje", "abrir", "Abre el chat en un mensaje concreto, resaltado.", Categoria.ABRIR,
            listOf("mensaje"), """{"que":{"tipo":"mensaje"}}""", listOf(a("que.codigo", p("codigo", Tipo.Texto, true, "id del mensaje"))), null, "Abriendo el mensaje."),
        Accion("abrir_nota", "abrir", "Abre una nota existente en el editor de notas.", Categoria.NOTAS,
            listOf("abre la nota", "editar nota", "abrir nota"), """{"que":{"tipo":"nota"}}""",
            listOf(a("que.codigo", p("codigo", Tipo.Texto, true, "id del mensaje de la nota"))), null, "Abriendo la nota."),
        Accion("abrir_hoja", "abrir", "Abre un lienzo u hoja de un proyecto por su referencia.", Categoria.ABRIR,
            listOf("hoja", "abre el lienzo", "abrir lienzo"), """{"que":{"tipo":"hoja"}}""",
            listOf(a("que.referencia", p("referencia", Tipo.Texto, true, "referencia de la hoja o lienzo"))), null, "Abriendo «{referencia}»."),
        Accion("escribir_en_chat", "chat", "Deja un mensaje de texto en el chat de «Mensajes guardados».", Categoria.NOTAS,
            listOf("guarda en el chat", "escribe en el chat", "manda al chat", "mensaje al chat", "apunta en el chat"), "{}",
            listOf(l(LARGO_LARGO, p("texto", Tipo.Texto, true, "el mensaje"))), null, "Escrito en «Mensajes guardados»."),
        Accion("nota_nueva", "nota_nueva", "Crea una nota nueva (Markdown) y la abre en el editor.", Categoria.NOTAS,
            listOf("nota", "apunte", "markdown", "escribe una nota"), "{}",
            listOf(l(LARGO_LARGO, p("texto", Tipo.Texto, false, "el contenido de la nota"))), null, "Nota nueva creada[: «{texto}»]."),
        Accion("lienzo_nuevo", "lienzo_nuevo", "Crea un lienzo nuevo, vacio, y lo abre.", Categoria.NOTAS,
            listOf("lienzo nuevo", "nuevo lienzo", "crea un lienzo", "pizarra nueva", "lienzo llamado"), "{}",
            listOf(p("nombre", Tipo.Texto, false, "nombre del lienzo")), null, "Lienzo nuevo creado[: «{nombre}»]."),
        Accion("grabar_voz", "grabar", "Saca el microfono flotante y graba una nota de voz con ese nombre.", Categoria.AUDIO,
            listOf("graba", "grabar", "nota de voz", "audio", "microfono"), "{}",
            listOf(p("nombre", Tipo.Texto, true, "nombre de la grabacion")), null, "Grabando «{nombre}»…"),
        Accion("reproducir_audio", "reproducir", "Hace sonar un audio (m4a, mp3, wav) en el reproductor flotante.", Categoria.AUDIO,
            listOf("reproduc", "escucha", "suena", "pon el audio", "mp3", "m4a", "wav"), "{}",
            listOf(p("ruta", Tipo.Ruta, true, RUTA), p("titulo", Tipo.Texto, false, "lo que se lee en la barra")), null, "Reproduciendo[ «{titulo}»]."),
        Accion("lista_nueva", "lista_nueva", "Crea una lista de tareas nueva con ese titulo (en «Mensajes guardados» si no se dice el chat).", Categoria.TAREAS,
            listOf("lista", "checklist"), "{}",
            listOf(p("titulo", Tipo.Texto, true, "titulo de la lista"), r(p("chat", Tipo.Texto, false, CHAT))), null, "Lista «{titulo}» creada[ en «{chat}»]."),
        Accion("anadir_tarea", "anadir_tarea", "Apunta UNA tarea pendiente, con su fecha si la dice (va al Inbox si no se dice la lista).", Categoria.TAREAS,
            listOf("tarea", "pendiente", "apunta", "recuerdame", "por hacer", "inbox", "to do", "todo", "anade", "agrega"), "{}",
            listOf(
                p("texto", Tipo.Texto, true, "lo que hay que hacer, sin la fecha"),
                r(p("lista", Tipo.Texto, false, LISTA)),
                r(p("fecha", Tipo.Texto, false, FECHA)),
                r(p("chat", Tipo.Texto, false, CHAT))
            ), null, "Apuntada «{texto}»[ en «{lista}»][ para {fecha}]."),
        Accion("anadir_tareas", "anadir_tarea", "Apunta VARIAS tareas (de un acta, foto o documento) en una lista existente o nueva.", Categoria.TAREAS,
            listOf("tareas de", "saca las tareas", "acta", "varias tareas", "estas tareas", "pendientes de"), "{}",
            listOf(
                r(p("tareas", Tipo.Textos, true, "las tareas, una por elemento, cortas y sin numerar")),
                r(p("lista", Tipo.Texto, false, LISTA)),
                r(p("lista_nueva", Tipo.Texto, false, "titulo de una lista NUEVA, si la pide")),
                r(p("chat", Tipo.Texto, false, CHAT))
            ), null, "Apuntadas {n} tareas en «{lista}»."),
        Accion("marcar_tarea", "marcar_tarea", "Marca una tarea como hecha (o la vuelve a dejar pendiente).", Categoria.TAREAS,
            listOf("marca", "hecha", "completa", "termin", "tacha", "desmarca", "acabe", "hice"), "{}",
            listOf(
                r(p("tarea", Tipo.Texto, true, TAREA)),
                r(p("lista", Tipo.Texto, false, LISTA)),
                d("true", p("hecha", Tipo.SiNo, false, "true hecha, false pendiente otra vez"))
            ), null, "«{tarea}» marcada como {estado}."),
        Accion("mover_tarea", "mover_tarea", "Pasa una tarea a otra lista.", Categoria.TAREAS,
            listOf("mueve", "mover", "pasa la tarea", "llevate"), "{}",
            listOf(
                r(p("tarea", Tipo.Texto, true, TAREA)),
                r(p("lista", Tipo.Texto, false, "lista donde esta ahora; omitir si no la dice")),
                r(p("a_lista", Tipo.Texto, true, "nombre de la lista de destino"))
            ), null, "«{tarea}» movida a «{a_lista}»."),
        Accion("editar_tarea", "corregir_tarea", "Cambia el texto de una tarea o le pone otra fecha.", Categoria.TAREAS,
            listOf("cambia la tarea", "edita", "renombra", "corrige", "cambiale", "pon la tarea", "posterga", "aplaza"), "{}",
            listOf(
                r(p("tarea", Tipo.Texto, true, TAREA)),
                r(p("lista", Tipo.Texto, false, LISTA)),
                p("texto", Tipo.Texto, false, "el texto nuevo; omitir si solo cambia la fecha"),
                r(p("fecha", Tipo.Texto, false, FECHA))
            ), null, "Tarea cambiada: «{texto}»[ para {fecha}]."),
        Accion("quitar_tarea", "quitar_tarea", "Quita (borra) una tarea de su lista.", Categoria.TAREAS,
            listOf("quita", "elimina la tarea", "borra la tarea"), "{}",
            listOf(r(p("tarea", Tipo.Texto, true, TAREA)), r(p("lista", Tipo.Texto, false, LISTA))),
            "¿Quito la tarea «{tarea}» de «{lista}»?", "Quitada «{tarea}» de «{lista}»."),
        Accion("borrar_lista", "borrar_lista", "Borra una lista de tareas entera.", Categoria.TAREAS,
            listOf("borra", "elimina", "borrar lista"), "{}",
            listOf(r(p("lista", Tipo.Texto, true, "nombre de la lista que hay que borrar")), r(p("chat", Tipo.Texto, false, CHAT))),
            "¿Borro la lista «{lista}» entera, con todas sus tareas? No se puede deshacer.", "Lista «{lista}» borrada."),
        Accion("consultar_tareas", CONSULTA, "Lee las tareas: pendientes, hechas o vencidas, por periodo, lista o chat.", Categoria.TAREAS,
            listOf("que tengo", "pendientes", "vencid", "venc", "atrasad", "mis tareas", "que tareas", "cuantas tareas", "que me falta"), "{}",
            listOf(
                r(d("\"pendientes\"", p("estado", op("pendientes", "hechas", "vencidas", "todas"), false, "que tareas"))),
                r(d("\"todo\"", p("periodo", op("hoy", "semana", "mes", "todo"), false, "por su fecha: hoy, esta semana, este mes o todas"))),
                r(p("lista", Tipo.Texto, false, LISTA)),
                r(p("chat", Tipo.Texto, false, CHAT)),
                r(p("buscar", Tipo.Texto, false, "palabras que deben tener las tareas"))
            ), null, ""),
        Accion("abrir_ventana", "ventana", "Abre una ventana de PixPin: tareas, galeria (de capturas), lecciones o sincronizar.", Categoria.ABRIR,
            listOf("ventana", "galeria", "tareas", "lecciones", "sincronizar", "abre", "ensena", "muestra"), "{}",
            listOf(p("cual", op("tareas", "galeria", "lecciones", "sincronizar"), true, "que ventana")), null, "Abriendo la ventana de {cual}."),
        Accion("capturar_pantalla", "capturar", "Empieza una captura de una zona de la pantalla (el usuario elige la zona).", Categoria.CAPTURAS,
            listOf("captura", "capturar", "pantallazo", "screenshot", "recorta la pantalla"), """{"modo":"zona"}""", emptyList(), null,
            "Elige la zona que quieres capturar."),
        // --- 15-oct, solo para la nube (PC `SOLO_NUBE`): el modelo local sigue con las de arriba.
        Accion("capturar_con_modo", "capturar", "Empieza una captura de pantalla de otra clase (el usuario elige la zona). modo: copiar (directo al portapapeles), pin (dejarla flotando), en_vivo (pin que se actualiza), anotar, texto (copiar el texto que se lea), scroll (captura larga), repetir (la ultima zona, sin elegir), retardo (tras una cuenta atras), zona (la de siempre, con barra).", Categoria.CAPTURAS,
            listOf("captura", "copia el texto de la pantalla", "captura larga", "con scroll", "zona de la pantalla", "texto que sale"), "{}",
            listOf(p("modo", Tipo.Opciones(MODOS_DE_CAPTURA), true, "que captura")), null, "Hecho: captura en marcha ({modo}); el usuario elige la zona."),
        Accion("abrir_otra_ventana", "ventana", "Abre los ajustes de PixPin, el timeline o el buscador de PixPin.", Categoria.ABRIR,
            listOf("ajustes", "configuracion", "timeline", "buscador"), "{}",
            listOf(p("cual", op("ajustes", "timeline", "buscar"), true, "que ventana")), null, "Abriendo la ventana de {cual}."),
        Accion("ir_al_chat", "ventana_principal", "Abre la ventana del chat en un chat concreto, por su nombre.", Categoria.ABRIR,
            listOf("al chat", "el chat de", "el chat"), "{}", listOf(r(p("chat", Tipo.Texto, true, "nombre del chat"))), null, "Abriendo el chat «{chat}»."),
        Accion("ir_a_pagina", "abrir", "Lleva un PDF, Word o PowerPoint (por su ruta) a una pagina o a un texto, abierto o no.", Categoria.ABRIR,
            listOf("pagina", "ve a la pagina", "llevame a la pagina"), """{"que":{"tipo":"fichero"}}""",
            listOf(
                a("que.ruta", p("ruta", Tipo.Ruta, true, RUTA)),
                a("que.pagina", p("pagina", Tipo.Entero, false, "pagina, desde 1")),
                a("que.buscar", p("buscar", Tipo.Texto, false, "texto que buscar y marcar"))
            ), null, "Llevando {ruta}[ a la página {pagina}][ a «{buscar}»]."),
        Accion("grabar_pantalla", "capturar", "Graba como GIF animado una zona de la pantalla: sale el marco, el usuario lo ajusta y pulsa Grabar.", Categoria.CAPTURAS,
            listOf("graba la pantalla", "grabar la pantalla", "grabar pantalla", "graba un video", "video de la pantalla", "gif", "screencast", "graba lo que hago"),
            """{"modo":"gif"}""", emptyList(), null, "Hecho: ya sale el marco de grabar; el usuario lo ajusta y pulsa Grabar."),
        Accion("cerrar_pines", "pines", "Cierra todos los pines de la pantalla.", Categoria.CAPTURAS,
            listOf("cierra los pines", "cierra todos los pines", "quita los pines", "cerrar pines", "borra los pines", "todos los pines", "los pines que", "quitame", "cierra"),
            """{"operacion":"cerrar"}""", emptyList(), "¿Cierro todos los pines de la pantalla?", "Pines cerrados (el último se puede devolver)."),
        Accion("manejar_pines", "pines", "Los pines de la pantalla (para cerrarlos: cerrar_pines): ocultar_mostrar (esconderlos todos o devolverlos), devolver_ultimo (el ultimo que se cerro), paso_de_clics (que dejen pasar el clic).", Categoria.CAPTURAS,
            listOf("oculta los pines", "esconde los pines", "muestra los pines", "devuelve el pin", "ultimo pin", "pines", "paso de clics"), "{}",
            listOf(p("operacion", Tipo.Opciones(OPERACIONES_DE_PINES), true, "que hacer")), null, "Hecho ({operacion})."),
        Accion("pinear_ultima_captura", "pinear_ultima", "Saca a la pantalla como pin la captura mas reciente.", Categoria.CAPTURAS,
            listOf("pin", "pinea", "ultima captura", "fija"), "{}", emptyList(), null, "La última captura, como pin en la pantalla."),
        Accion("pinear_fichero", "pinear", "Saca un fichero (imagen, PDF...) a la pantalla como pin.", Categoria.CAPTURAS,
            listOf("pin", "pinea", "fija en pantalla", "saca a la pantalla"), "{}", listOf(p("ruta", Tipo.Ruta, true, RUTA)), null,
            "{ruta}, como pin en la pantalla."),
        Accion("conservar_captura", "conservar_captura", "Conserva una captura (no caduca y va a «Mensajes guardados»).", Categoria.CAPTURAS,
            listOf("conserva", "no borres", "guarda la captura", "caduc"), "{}", listOf(p("ruta", Tipo.Ruta, true, "ruta de la captura")), null,
            "Captura conservada."),
        Accion("borrar_captura", "borrar_captura", "Manda una captura a la papelera de PixPin.", Categoria.CAPTURAS,
            listOf("borra la captura", "elimina la captura", "papelera"), "{}", listOf(p("ruta", Tipo.Ruta, true, "ruta de la captura")),
            "¿Mando a la papelera la captura {ruta}?", "Captura enviada a la papelera."),
        Accion("copiar_imagen", "copiar_imagen", "Pone una imagen en el portapapeles, como imagen.", Categoria.CAPTURAS,
            listOf("copia la imagen", "portapapeles", "copiar imagen"), "{}", listOf(p("ruta", Tipo.Ruta, true, RUTA)), null,
            "Imagen copiada al portapapeles."),
        Accion("caja_para_soltar", "soltar", "Saca una caja flotante a la que soltar ficheros para meterlos en el chat.", Categoria.ABRIR,
            listOf("soltar", "arrastrar", "caja flotante", "suelta"), "{}", emptyList(), null, "Suelta los ficheros en la caja."),
        Accion("leccion_nueva", "leccion_nueva", "Abre la ficha de una leccion aprendida nueva (paso que..., porque..., la proxima vez...).", Categoria.LECCIONES,
            listOf("leccion nueva", "nueva leccion", "aprendi", "aprendida"), "{}",
            listOf(l(LARGO_LARGO, p("texto", Tipo.Texto, false, "lo que paso y lo aprendido"))), null, "Ficha de lección nueva abierta."),
        Accion("buscar_lecciones", "lecciones", "Abre la lista de lecciones aprendidas, buscando lo que se diga.", Categoria.LECCIONES,
            listOf("lecciones", "leccion", "repasar", "repaso"), "{}", listOf(p("consulta", Tipo.Texto, false, "lo que se busca")), null,
            "Abriendo las lecciones[ de «{consulta}»]."),
        Accion("sincronizar_movil", "sincronizar", "Sincroniza ahora con el movil (o con todos los aparatos del grupo).", Categoria.MOVIL,
            listOf("sincroniz", "sync", "movil", "celular", "telefono", "android"), "{}",
            listOf(d("\"todos\"", p("aparato", Tipo.Texto, false, "id del aparato; omitir para todos"))),
            "¿Sincronizo ahora con el movil?", "Sincronizando con el móvil."),
        Accion("enviar_al_movil", "enviar_al_movil", "Manda imagenes al lienzo abierto en el movil.", Categoria.MOVIL,
            listOf("envia", "manda", "pasa", "movil", "celular", "telefono"), "{}",
            listOf(p("imagenes", Tipo.Rutas, true, "rutas absolutas de las imagenes"), d("\"todos\"", p("aparato", Tipo.Texto, false, "id del aparato; omitir para todos"))),
            "¿Mando {imagenes} al movil?", "Enviado al móvil."),
        Accion("crear_automatizacion", "automatizacion", "Crea una automatizacion (se dispara sola y hace pasos).", Categoria.TAREAS,
            listOf("cada vez", "cada dia", "todos los dias", "cada manana", "cada semana", "siempre que", "automatiz", "al capturar", "cuando capture", "cuando sincronice"),
            """{"operacion":"crear"}""",
            listOf(
                a("receta.nombre", p("nombre", Tipo.Texto, true, "nombre corto")),
                a("receta.disparador.tipo", p("disparador", op("al_capturar", "a_la_hora", "al_sincronizar", "a_mano"), true, "cuando")),
                a("receta.disparador.hora", p("hora", Tipo.Texto, false, "HH:MM")),
                a("receta.pasos", l(LARGO_LARGO, p("pasos", Tipo.Texto, true, "lista JSON: texto para el asistente u {herramienta,args,proyecto}; {captura} {resultado}")))
            ),
            "¿Creo la automatización «{nombre}» ({disparador}) con estos pasos: {pasos}?", "Automatización «{nombre}» creada."),
        // --- 15-oct: «que la gente pueda hacer TODO con PixPin a través del asistente».
        Accion("apuntar_en_timeline", "timeline", "Apunta un momento en el timeline (lo que pasa ahora, con su hora).", Categoria.NOTAS,
            listOf("timeline", "linea de tiempo", "bitacora", "diario de obra", "momento"), "{}",
            listOf(l(LARGO_LARGO, p("texto", Tipo.Texto, true, "lo que paso; la primera frase es el titulo"))), null, "Apuntado en el timeline: «{texto}»."),
        Accion("leer_en_voz_alta", EN_EL_CHAT, "Lee en voz alta un texto o un documento del chat (PDF, Word, nota...) con la voz de PixPin.", Categoria.AUDIO,
            listOf("en voz alta", "leemelo", "lee en voz", "leelo en voz", "dimelo en voz", "escuchar el", "escuchar la", "narra", "voz alta"), "{}",
            listOf(
                l(LARGO_LARGO, p("texto", Tipo.Texto, false, "el texto que leer; omitir para leer un documento")),
                p("archivo", Tipo.Texto, false, "nombre o descripcion del documento; omitir: el adjunto mas reciente")
            ), null, "Leyendo en voz alta."),
        Accion("parar_lectura", "leer_en_voz", "Calla la lectura en voz alta que este sonando («deja de leer», «callate», «basta»).", Categoria.AUDIO,
            listOf("para de leer", "deja de leer", "callate", "deja de hablar", "para la voz", "deten la lectura"), """{"parar":true}""", emptyList(), null, "Lectura parada."),
        Accion("comprimir_pdf", EN_EL_CHAT, "Comprime (aligera) un PDF y deja la copia ligera en el chat; el original no se toca.", Categoria.ABRIR,
            listOf("comprime", "comprimir", "aligera", "aligerar", "reduce el pdf", "menos pesado", "pesa mucho", "achica el pdf", "ligero", "pesa", "por correo"), "{}",
            listOf(p("archivo", Tipo.Texto, false, "nombre o descripcion del PDF; omitir: el PDF mas reciente del chat")), null, "PDF aligerado."),
        Accion("transcribir_audio", EN_EL_CHAT, "Pasa a texto un audio o nota de voz del chat (Whisper, en este PC) y te devuelve lo dicho.", Categoria.AUDIO,
            listOf("transcrib", "pasa a texto", "pasalo a texto", "que dice el audio", "que dice la nota de voz", "audio a texto", "este audio", "el audio de", "dictado"), "{}",
            listOf(p("archivo", Tipo.Texto, false, "nombre o descripcion del audio; omitir: el audio mas reciente del chat")), null, "Audio pasado a texto."),
        Accion("leer_excel", EN_EL_CHAT, "Lee las celdas de un Excel del chat (una hoja o un rango A1:D20) para contestar, calcular o hacer una grafica.", Categoria.NOTAS,
            listOf("del excel", "el excel", "en el excel", "xlsx", "hoja de calculo", "celda", "rango", "columna", "suma de la", "total de la"), "{}",
            listOf(
                p("archivo", Tipo.Texto, false, "nombre o descripcion del Excel; omitir: el mas reciente del chat"),
                p("hoja", Tipo.Texto, false, "nombre de la hoja; omitir: la activa"),
                p("rango", Tipo.Texto, false, "rango como A1:D20; omitir: lo usado de la hoja")
            ), null, "Excel leído."),
        Accion("leer_documento", EN_EL_CHAT, "Lee el texto entero de un archivo del chat (PDF, Word, PowerPoint, Excel, nota, o una foto/escaneo con OCR), pagina a pagina, para resumirlo, contestar sobre el, revisarlo o sacar sus formulas. Usala ANTES de contestar sobre un archivo adjunto (buscar_en_mis_cosas solo da trozos sueltos). Si el archivo es una foto que ya ves, no hace falta.", Categoria.NOTAS,
            emptyList(), "{}",
            listOf(
                p("archivo", Tipo.Texto, false, "nombre o descripcion del archivo; omitir: el mas reciente del chat"),
                p("paginas", Tipo.Texto, false, "paginas o diapositivas, como \"3\" o \"2-5\"; omitir: todo")
            ), null, "Documento leído."),
        Accion("resumen_de_actividad", EN_EL_CHAT, "Cuenta lo hecho en PixPin hoy, esta semana o este mes: mensajes y notas, fotos, capturas, momentos del timeline y tareas.", Categoria.TAREAS,
            listOf("cuanto hice", "que hice hoy", "mi actividad", "resumen del dia", "estadistic", "cuantas capturas", "cuantos mensajes", "productiv"), "{}",
            listOf(d("\"hoy\"", p("periodo", op("hoy", "semana", "mes"), false, "de cuando"))), null, "Hecho."),
        // 17-oct: el bot crea lienzos o usa los que hay, y los mira (cambian adónde va lo del turno).
        Accion(LIENZOS_DEL_CHAT, EN_EL_CHAT, "Lista los lienzos de este chat (nombre y cuantas cosas tiene cada uno) y dice en cual trabajas ahora.", Categoria.NOTAS,
            emptyList(), "{}", emptyList(), null, "Lienzos listados."),
        Accion(CREAR_LIENZO, EN_EL_CHAT, "Crea un lienzo NUEVO con ese nombre en este chat, sin abrirlo, y lo deja como destino: lo que dibujes, edites o mires con ver_lienzo despues va a el.", Categoria.NOTAS,
            emptyList(), "{}", listOf(p("nombre", Tipo.Texto, true, "nombre del lienzo nuevo")), null, "Lienzo «{nombre}» creado."),
        Accion(USAR_LIENZO, EN_EL_CHAT, "Pasa a trabajar en un lienzo EXISTENTE de este chat por su nombre: devuelve el resumen de sus elementos (con #id) y lo deja como destino de lo que dibujes, edites o mires con ver_lienzo (para revisarlo y corregirlo).", Categoria.NOTAS,
            emptyList(), "{}", listOf(p("nombre", Tipo.Texto, true, "nombre del lienzo (o un trozo)")), null, "Trabajando en «{nombre}».")
    )

    // ------------------------------------------------------------------------------- documentos

    const val TABLA = "tabla_de_imagen"
    const val ANOTAR = "anotar_documento"
    const val BUSCAR_EN = "buscar_en_documento"
    const val ABRIR = "abrir_por_nombre"
    const val EXPORTAR = "exportar_documento"
    const val HOJA_A_LIENZO = "hoja_a_lienzo"

    val TIPOS_DE_ANOTACION = listOf("resaltar", "subrayar", "tachar", "nota", "encerrar", "marco", "dibujar")
    val FORMATOS = listOf("pdf", "png", "jpg", "svg", "web", "dxf", "csv", "texto", "md", "original")

    private const val ARCHIVO = "nombre o descripcion del documento (ej. «plano de senalizacion Tahuantinsuyo»); omitir: el adjunto mas reciente del chat"

    /** **La tabla de los documentos** (`acciones_documentos.rs`): las hace el ejecutor del chat. */
    val ACCIONES_DOCUMENTOS: List<Accion> = listOf(
        Accion(TABLA, TABLA, "Pasa la tabla de una foto o un PDF (escaneado o no) a una tabla del chat o a un Excel. Las celdas las lee PixPin; tu solo eliges destino, nombre y si la primera fila es cabecera.", Categoria.NOTAS,
            listOf("tabla", "excel", "xlsx", "hoja de calculo", "celdas", "columnas", "filas"), "{}",
            listOf(
                d("\"tabla\"", p("destino", op("tabla", "excel"), false, "tabla del chat o fichero Excel")),
                p("nombre", Tipo.Texto, false, "nombre de la tabla"),
                p("cabecera", Tipo.SiNo, false, "si la primera fila son titulos; omitir si no se sabe"),
                p("archivo", Tipo.Texto, false, ARCHIVO),
                p("pagina", Tipo.Entero, false, "pagina del PDF, desde 1; omitir: la primera con tabla")
            ), null, "Hecho."),
        Accion(ANOTAR, ANOTAR, "Anota un PDF, Word o plano del chat donde lo vera el usuario al abrirlo (y en el movil): resaltar, subrayar o tachar un texto; encerrar o marcar (marco) un texto o una zona; poner una nota; dibujar un trazo.", Categoria.NOTAS,
            listOf("resalta", "subraya", "tacha", "anota", "anotale", "encierra", "rodea", "circula", "marca en", "marca el",
                "marca la", "pon una nota", "ponle una nota", "nota en", "senala", "dibuja sobre", "dibuja en el plano",
                "dibuja en el pdf", "en la pagina"), "{}",
            listOf(
                p("tipo", Tipo.Opciones(TIPOS_DE_ANOTACION), true, "que se hace"),
                p("texto", Tipo.Texto, false, "el texto del documento sobre el que va (se busca)"),
                p("nota", Tipo.Texto, false, "lo que dice la nota"),
                p("archivo", Tipo.Texto, false, ARCHIVO),
                p("pagina", Tipo.Entero, false, "pagina, desde 1; omitir: donde este el texto"),
                p("zona", Tipo.Numeros, false, "[x, y, ancho, alto] en fracciones 0-1 de la pagina o del plano, si no hay texto"),
                p("puntos", Tipo.Numeros, false, "dibujar: [x1, y1, x2, y2, ...] en fracciones 0-1"),
                p("color", Tipo.Texto, false, "amarillo, rojo, verde, azul... omitir: el de siempre")
            ), null, "Hecho."),
        Accion(BUSCAR_EN, BUSCAR_EN, "Busca un texto dentro de un plano (DWG/DXF), PDF o Word y abre el visor ahi, ampliado y marcado. Usala para «¿donde dice...?» o «llevame a...».", Categoria.ABRIR,
            listOf("donde dice", "donde sale", "donde aparece", "donde pone", "busca en el plano",
                "busca en el pdf", "busca en el documento", "llevame", "lleva a", "encuentra en", "ubica", "localiza"), "{}",
            listOf(
                p("texto", Tipo.Texto, true, "lo que se busca, tal cual"),
                p("archivo", Tipo.Texto, false, ARCHIVO),
                d("true", p("abrir", Tipo.SiNo, false, "abrir el visor en lo encontrado"))
            ), null, "Hecho."),
        Accion(ABRIR, ABRIR, "Abre un documento, plano, Excel o imagen de los chats del usuario por su nombre o de que trata. Si hay varios parecidos te los dice para que preguntes cual.", Categoria.ABRIR,
            listOf("abre el", "abre la", "abre mi", "abreme", "abrir el", "abrir la", "muestrame el", "muestrame la",
                "ensename el", "ensename la", "plano de", "pdf de", "documento de", "archivo de", "excel de"), "{}",
            listOf(p("descripcion", Tipo.Texto, true, "el nombre o lo que trata, con las palabras del usuario")), null, "Hecho."),
        Accion(EXPORTAR, EXPORTAR, "Exporta un documento, plano, Excel o imagen (con lo anotado) en otro formato y lo deja en el chat.", Categoria.ABRIR,
            listOf("exporta", "exportar", "exportalo", "guarda como", "guardalo como", "convierte a", "convertir a", "pasalo a", "en pdf", "a pdf", "a png", "a dxf", "como imagen", "en png"), "{}",
            listOf(p("formato", Tipo.Opciones(FORMATOS), true, "el formato"), p("archivo", Tipo.Texto, false, ARCHIVO)), null, "Hecho."),
        // Anotar con cabeza (15-oct, [Anotacion]): lo hace el ejecutor del chat (`asistente/AnotarInteligente.kt`).
        // Sin claves a propósito: solo para la nube, que la elige por su descripción.
        Accion(Anotacion.NOMBRE, Anotacion.NOMBRE, "Anota con criterio un PDF, Word, PowerPoint, Excel, foto/escaneo o plano: lee el texto de la pagina y pone en su capa " +
            "recuadros sobre lo importante con la explicacion al margen, pasos numerados, errores con su correccion, " +
            "el resumen de la pagina o de cada seccion, un glosario o preguntas de repaso. Usala para «señalame lo importante», " +
            "«explicame esta formula al costado», «marca los errores», «resume cada seccion al margen». " +
            "Para resaltar o encerrar un texto concreto que dice el usuario, anotar_documento.", Categoria.NOTAS,
            emptyList(), "{}",
            listOf(
                p("pedido", Tipo.Texto, true, "lo que pidio el usuario, con sus palabras"),
                p("modo", op("importante", "pasos", "errores", "resumen", "glosario", "preguntas", "explicar", "mixto"), false, "que anotar; omitir: lo decide el pedido"),
                p("foco", Tipo.Texto, false, "el trozo exacto a explicar (una formula, una frase), si el usuario lo dijo"),
                p("archivo", Tipo.Texto, false, ARCHIVO),
                p("pagina", Tipo.Entero, false, "pagina desde 1; omitir: la que el usuario tiene abierta (o la 1)"),
                p("hasta", Tipo.Entero, false, "ultima pagina, para anotar varias seguidas (como mucho 5)"),
                p("celdas", Tipo.Texto, false, "en un Excel, el rango a mirar (\"A1:F40\"); omitir: lo usado de la hoja"),
                p("hoja", Tipo.Texto, false, "en un Excel, el nombre de la hoja; omitir: la primera")
            ), null, "Hecho."),
        Accion(HOJA_A_LIENZO, HOJA_A_LIENZO, "Pasa al lienzo una hoja (foto, escaneo o pagina de un PDF) interpretandola: parrafos y titulos como texto, tablas como tabla, formulas como ecuacion, fotos recortadas y rayas, en su sitio y en orden de lectura. Usala para «pasa esta hoja/foto/pagina al lienzo» o «digitaliza esto» (mejor que calcarla si tiene texto).", Categoria.NOTAS,
            listOf("digitaliza", "hoja al lienzo", "pagina al lienzo", "escaneo al lienzo", "hoja en el lienzo", "pasa la hoja", "pasa esta hoja"), "{}",
            listOf(
                p("archivo", Tipo.Texto, false, "la foto o el PDF (nombre o descripcion); omitir: la imagen o el PDF mas reciente del chat"),
                p("pagina", Tipo.Entero, false, "pagina del PDF, desde 1; omitir: la primera"),
                p("ancho", Tipo.Entero, false, "ancho de la hoja en el lienzo en px; omitir: 1000"),
                d("\"editable\"", p("modo", op("editable", "fiel"), false, "editable: texto corrido y tablas; fiel: cada renglon en su sitio exacto"))
            ), null, "Hecho.")
    )

    /** **Todas las filas**: las de la app y las de los documentos. */
    val TODAS: List<Accion> get() = ACCIONES + ACCIONES_DOCUMENTOS

    fun porNombre(nombre: String): Accion? = TODAS.firstOrNull { it.herramienta == nombre }

    fun esDeDocumentos(herramienta: String) = ACCIONES_DOCUMENTOS.any { it.herramienta == herramienta }

    /** «¿Dónde dice JR. ANTISUYO en el plano?»: las de documentos que encajan con una frase entera. */
    fun paraUnaPregunta(q: String): List<Accion> =
        ACCIONES_DOCUMENTOS.filter { a -> a.herramienta == BUSCAR_EN && a.claves.any { ' ' in it && q.contains(it) } }

    /** **La herramienta que ve el modelo**, generada de la fila (`herramienta` del PC). */
    fun herramienta(a: Accion): Herramienta {
        val propiedades = LinkedHashMap<String, JsonElement>()
        val obligatorios = mutableListOf<String>()
        for (p in a.params) {
            val esquema = LinkedHashMap<String, JsonElement>()
            when (val t = p.tipo) {
                Tipo.Texto, Tipo.Ruta -> { esquema["type"] = JsonPrimitive("string"); esquema["maxLength"] = JsonPrimitive(p.largo ?: LARGO_TEXTO) }
                Tipo.Rutas, Tipo.Textos -> {
                    esquema["type"] = JsonPrimitive("array")
                    esquema["items"] = buildJsonObject { put("type", "string"); put("maxLength", p.largo ?: LARGO_TEXTO) }
                }
                Tipo.Entero -> esquema["type"] = JsonPrimitive("integer")
                Tipo.SiNo -> esquema["type"] = JsonPrimitive("boolean")
                is Tipo.Opciones -> { esquema["type"] = JsonPrimitive("string"); esquema["enum"] = JsonArray(t.o.map { JsonPrimitive(it) }) }
                Tipo.Numeros -> { esquema["type"] = JsonPrimitive("array"); esquema["items"] = buildJsonObject { put("type", "number") } }
            }
            esquema["description"] = JsonPrimitive(p.descripcion)
            propiedades[p.nombre] = JsonObject(esquema)
            if (p.obligatorio) obligatorios += p.nombre
        }
        val parametros = LinkedHashMap<String, JsonElement>()
        parametros["type"] = JsonPrimitive("object")
        parametros["properties"] = JsonObject(propiedades)
        if (obligatorios.isNotEmpty()) parametros["required"] = JsonArray(obligatorios.map { JsonPrimitive(it) })
        return Herramienta(a.herramienta, a.descripcion, JsonObject(parametros))
    }

    /** Una acción de la app ya validada: el pedido listo y, si hay que preguntar, la pregunta. */
    data class AccionApp(
        val herramienta: String,
        /** `{"pixpin":1,"accion":...}` completo (sin lo que falta resolver). */
        val pedido: JsonObject,
        val confirmar: String?,
        /** Nombres de listas, tareas, chats o fechas: los cambia por ids [Tareas.resolver]. */
        val porResolver: JsonObject
    ) {
        val fila: Accion get() = porNombre(herramienta) ?: error("salió de la tabla")
        val esConsulta: Boolean get() = fila.accion == CONSULTA

        /** Todos los valores tal cual, por nombre de parámetro: para las frases de [Accion.hecho]. */
        fun valores(): List<Pair<String, String>> {
            val v = mutableListOf<Pair<String, String>>()
            for (p in fila.params) {
                val x: JsonElement? = if (p.destino.startsWith(POR_RESOLVER)) porResolver[p.nombre]
                else {
                    var actual: JsonElement? = pedido
                    for (t in (p.destino.ifEmpty { p.nombre }).split('.')) actual = (actual as? JsonObject)?.get(t)
                    actual?.takeIf { it !is JsonNull }
                }
                if (x != null) v += p.nombre to leible(x)
            }
            return v
        }
    }

    /** Un valor JSON como se lee en una frase. */
    fun leible(v: JsonElement): String = when {
        v is JsonPrimitive && v.isString -> v.content
        v is JsonArray -> v.joinToString(", ") { leible(it) }
        else -> v.toString()
    }

    /**
     * **Rellena una plantilla** de [Accion.hecho] o [Accion.confirmar]: `{campo}` por su valor y los
     * trozos `[...]` a los que les falte un campo, fuera.
     */
    fun rellenar(plantilla: String, valores: List<Pair<String, String>>): String {
        fun sustituir(s: String): String? {
            val out = StringBuilder()
            var resto = s
            while (true) {
                val i = resto.indexOf('{').takeIf { it >= 0 } ?: break
                out.append(resto, 0, i)
                val j = resto.indexOf('}', i).takeIf { it >= 0 } ?: return null
                val campo = resto.substring(i + 1, j)
                val v = valores.firstOrNull { (k, x) -> k == campo && x.isNotBlank() } ?: return null
                out.append(v.second)
                resto = resto.substring(j + 1)
            }
            out.append(resto)
            return out.toString()
        }
        val out = StringBuilder()
        var resto = plantilla
        while (true) {
            val i = resto.indexOf('[').takeIf { it >= 0 } ?: break
            val j = resto.indexOf(']', i).takeIf { it >= 0 } ?: break
            out.append(resto, 0, i)
            sustituir(resto.substring(i + 1, j))?.let { out.append(it) }
            resto = resto.substring(j + 1)
        }
        out.append(resto)
        val s0 = out.toString()
        return sustituir(s0) ?: run {
            var s = s0
            while (true) {
                val i = s.indexOf('{')
                val j = s.indexOf('}')
                if (i < 0 || j < 0 || j < i) break
                s = s.removeRange(i, j + 1)
            }
            s.replace("«»", "").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    /** Minúsculas y sin tildes (para las opciones y para elegir herramientas). */
    fun normalizar(s: String): String = buildString(s.length) {
        for (c in s.lowercase()) append(
            when (c) {
                'á', 'à' -> 'a'
                'é', 'è' -> 'e'
                'í' -> 'i'
                'ó' -> 'o'
                'ú', 'ü' -> 'u'
                'ñ' -> 'n'
                else -> c
            }
        )
    }

    private fun mal(m: String) = ErrorHerramienta.Parametro(m)

    private fun numeroJson(v: JsonElement): Double? {
        val p = v as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull || p.booleanOrNull != null) return null
        return p.doubleOrNull?.takeIf { it.isFinite() }
    }

    private fun valor(p: Param, v: JsonElement): JsonElement {
        val campo = p.nombre
        val texto = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (val t = p.tipo) {
            Tipo.Texto -> when {
                texto != null && texto.isNotBlank() -> JsonPrimitive(texto.trim().let { s ->
                    val n = p.largo ?: LARGO_TEXTO
                    if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))
                })
                v is JsonPrimitive && !v.isString && v !is JsonNull && v.booleanOrNull == null -> JsonPrimitive(numeroComoTexto(v))
                else -> throw mal("«$campo» tiene que ser un texto")
            }
            Tipo.Ruta -> {
                texto ?: throw mal("«$campo» tiene que ser una ruta")
                val r = texto.trim().trim('"', '\'', '«', '»')
                if (r.isEmpty()) throw mal("«$campo» esta vacio")
                JsonPrimitive(r)
            }
            Tipo.Rutas, Tipo.Textos -> when {
                texto != null && texto.isNotBlank() -> JsonArray(listOf(JsonPrimitive(texto.trim())))
                v is JsonArray && v.isNotEmpty() -> {
                    val todos = v.map { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: throw mal("«$campo» tiene que ser una lista de textos") }
                        .filter { it.isNotEmpty() }
                    if (todos.isEmpty()) throw mal("«$campo» esta vacio")
                    JsonArray(todos.map { JsonPrimitive(it) })
                }
                else -> throw mal("«$campo» tiene que ser una lista de textos")
            }
            Tipo.Entero -> {
                val n = numeroJson(v) ?: texto?.trim()?.toDoubleOrNull()
                if (n != null && n >= 0.0 && n % 1.0 == 0.0 && n < 100_000.0) JsonPrimitive(n.toLong())
                else throw mal("«$campo» tiene que ser un numero entero desde 0")
            }
            Tipo.SiNo -> {
                val b = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                when {
                    b != null -> JsonPrimitive(b)
                    texto != null -> when (normalizar(texto.trim())) {
                        "true", "si", "yes", "hecha" -> JsonPrimitive(true)
                        "false", "no", "pendiente" -> JsonPrimitive(false)
                        else -> throw mal("«$campo» tiene que ser true o false")
                    }
                    else -> throw mal("«$campo» tiene que ser true o false")
                }
            }
            Tipo.Numeros -> {
                val lista = if (texto != null) runCatching { Json.parseToJsonElement(texto.trim()) }.getOrNull() else v
                val n = mutableListOf<Double>()
                val bien = lista is JsonArray && lista.isNotEmpty() && lista.all { e ->
                    if (e is JsonArray) e.all { x -> numeroJson(x)?.also { n += it } != null }
                    else numeroJson(e)?.also { n += it } != null
                }
                if (!bien || n.size > 400) throw mal("«$campo» tiene que ser una lista de numeros")
                JsonArray(n.map { JsonPrimitive(it) })
            }
            is Tipo.Opciones -> {
                val s = texto?.let { normalizar(it.trim()) } ?: ""
                val x = t.o.firstOrNull { it == s || (s.length >= 4 && it.startsWith(s.substring(0, 4))) }
                    ?: throw mal("«$campo» tiene que ser una de: ${t.o.joinToString(", ")}")
                JsonPrimitive(x)
            }
        }
    }

    /** Un número JSON como lo escribe serde (`12`, `1.5`). */
    private fun numeroComoTexto(v: JsonPrimitive): String = v.content

    /** Mete [v] en [pedido] siguiendo `que.ruta`. */
    private fun poner(pedido: MutableMap<String, JsonElement>, destino: String, v: JsonElement) {
        val trozos = destino.split('.')
        if (trozos.size == 1) { pedido[destino] = v; return }
        val hijo = (pedido[trozos[0]] as? JsonObject)?.toMutableMap() ?: LinkedHashMap()
        poner(hijo, trozos.drop(1).joinToString("."), v)
        pedido[trozos[0]] = JsonObject(hijo)
    }

    /** **Valida** los argumentos del modelo contra la fila y arma el pedido. */
    fun validar(a: Accion, args: JsonObject): AccionApp {
        val pedido = LinkedHashMap<String, JsonElement>()
        (runCatching { Json.parseToJsonElement(a.fijos) }.getOrNull() as? JsonObject)?.let { pedido.putAll(it) }
        pedido["pixpin"] = JsonPrimitive(1)
        pedido["accion"] = JsonPrimitive(a.accion)
        val porResolver = LinkedHashMap<String, JsonElement>()
        for (p in a.params) {
            val crudo = args[p.nombre]?.takeIf { it !is JsonNull && !((it as? JsonPrimitive)?.isString == true && it.content.isBlank()) }
            val v = when {
                crudo != null -> valor(p, crudo)
                p.defecto != null -> Json.parseToJsonElement(p.defecto)
                p.obligatorio -> throw mal("falta «${p.nombre}» (${p.descripcion})")
                else -> continue
            }
            if (p.destino.startsWith(POR_RESOLVER)) porResolver[p.nombre] = v
            else poner(pedido, p.destino.ifEmpty { p.nombre }, v)
        }
        val hecha = AccionApp(a.herramienta, JsonObject(pedido), null, JsonObject(porResolver))
        return hecha.copy(confirmar = a.confirmar?.let { rellenar(it, hecha.valores()) })
    }
}
