package com.forge.pixpin.ia

/**
 * **Lo que el asistente sabe de PixPin para Android**: un resumen para las instrucciones y un
 * índice corto para la herramienta `ayuda_app` (`conocimiento.rs` del PC: el mismo algoritmo, con
 * las respuestas de ESTA app).
 *
 * Un modelo de 2B no conoce la app y, si se le pregunta, inventa menús. Con 8192 tokens de
 * contexto no cabe un manual: el **resumen** va siempre en las instrucciones, y el detalle se
 * busca en el **índice** solo cuando se pregunta. Sacado de `docs/guia.md` y de las pantallas de
 * la app (15-oct-2026). Si cambia un menú, hay que cambiarlo aquí.
 */
object Conocimiento {
    const val RESUMEN = "PixPin es una app de Android para escribir y dibujar con el lapiz (lienzos con el mismo motor en todas partes), " +
        "con un chat por proyectos (con «Mensajes guardados») que guarda fotos, archivos, notas de voz, notas, lienzos y tablas, " +
        "y lectores de PDF, Word, libros, Excel y planos. Ademas: capturas de pantalla y pines flotantes con la bola, " +
        "galeria de capturas, Tareas con Inbox, Lecciones aprendidas, buscador y sincronizacion por Wi-Fi con los otros aparatos y con el PC."

    data class Entrada(val tema: String, val claves: List<String>, val respuesta: String)

    private fun e(tema: String, claves: List<String>, respuesta: String) = Entrada(tema, claves, respuesta)

    /** **El índice.** */
    val INDICE: List<Entrada> = listOf(
        e("Capturar la pantalla", listOf("captur", "screenshot", "pantallazo", "recort", "zona"),
            "Con la bola flotante: un toque abre su menu y doble toque captura. Eliges zona, region libre, ventana o captura larga. La primera vez Android pide permiso para grabar la pantalla."),
        e("La bola flotante", listOf("bola", "flotante", "burbuja", "ocultar pines"),
            "La bola flotante se arrastra y se imanta al borde. Toque: menu; doble toque: capturar; pulsacion larga: oculta o muestra todos los pines. Necesita el permiso «Mostrar sobre otras apps»."),
        e("Pines", listOf("pin", "pinear", "fijar", "flotante", "encima"),
            "Un pin es una imagen, nota, lista o archivo flotando sobre las demas apps. Se mueve arrastrando, se escala pellizcando y la pulsacion larga abre su barra (dibujar, editar, PDF, guardar, cerrar)."),
        e("Galeria de capturas", listOf("galeria", "capturas", "recientes", "ultimas capturas"),
            "Las capturas estan en la Galeria de capturas (desde el menu de la bola o desde Proyectos). Desde ahi se conservan, se mandan al chat o se borran."),
        e("Caducidad de las capturas", listOf("caduc", "dura", "conservar", "no borr"),
            "Las capturas de la galeria caducan solas; «Conservar» la guarda en «Mensajes guardados» y deja de caducar."),
        e("El lienzo", listOf("lienzo", "dibujar", "dibujo", "pizarra", "lapiz", "grafito"),
            "El lienzo tiene figuras, lapiz de presion, grafito con siete durezas (4H a 8B), texto, cotas y guias. Un dedo dibuja y dos mueven el papel; con lapiz a la vista, el dedo mueve el papel. Un lienzo nuevo se crea desde un chat o un proyecto."),
        e("Varios lienzos a la vez", listOf("multitarea", "varios lienzos", "baraja", "tres dedos", "cuatro dedos"),
            "Hasta tres lienzos abiertos lado a lado: tres dedos al lado pasan al vecino, cuatro dedos arriba abren la baraja con todos, y el asa entre dos reparte el tamano."),
        e("El chat y Mensajes guardados", listOf("chat", "guardados", "mensajes", "buzon", "conversacion"),
            "Todo se guarda mandandolo a un chat: «Mensajes guardados» o el chat de cada proyecto. Lo compartido desde otras apps cae en el buzon. Cada mensaje admite emoji, comentario y fijarlo."),
        e("Proyectos", listOf("proyecto", "hojas", "planos", "carpeta"),
            "En Proyectos cada proyecto tiene sus hojas (lienzos, notas, PDF, tablas, croquis) y su propio chat. Lo que mandas al chat de un proyecto sale con un punto rojo y se anade desde su menu."),
        e("Notas", listOf("nota", "markdown", "apunte", "escribir"),
            "Una nota es Markdown: se crea en un proyecto o se manda al chat como texto, y se edita en el editor de notas."),
        e("Leer PDF, Word y libros", listOf("pdf", "word", "docx", "epub", "libro", "leer", "lector", "anotar"),
            "Un PDF, Word o libro del chat se abre dentro de la app a pantalla completa; con el lapiz se anota encima y lo anotado viaja con el documento. El boton «Dedo» deja mover con el dedo."),
        e("Excel y tablas", listOf("excel", "xlsx", "tabla", "formula", "celda"),
            "Los Excel se abren en su visor de hojas. Las tablas del chat calculan con formulas y se pueden pegar de Excel o Sheets."),
        e("Planos DWG/DXF y 3D", listOf("plano", "dwg", "dxf", "cad", "ifc", "revit", "3d"),
            "Los planos DWG/DXF se abren en el visor de planos (acotar, marcos, anotar) y los modelos IFC o Revit en el visor 3D."),
        e("Notas de voz y transcribir", listOf("voz", "grabar", "microfono", "audio", "transcrib", "whisper", "dictar"),
            "Las notas de voz se graban desde el chat o la grabadora y se transcriben con Whisper, Vosk o Google (Ajustes → Voz). Suenan en su burbuja; la Biblioteca de audio las junta."),
        e("Tareas", listOf("tarea", "inbox", "pendiente", "lista", "recordatorio", "checklist"),
            "Las listas de tareas son mensajes del chat con casillas. La pantalla Tareas las junta todas, con el Inbox primero; una tarea con ⏰ fecha avisa a su hora. Tambien hay un widget de tareas."),
        e("Lecciones aprendidas", listOf("leccion", "lecciones", "aprendi", "repaso"),
            "Lecciones aprendidas (en Proyectos): una ficha con que paso, por que y la proxima vez; se buscan y se repasan."),
        e("Buscar en PixPin", listOf("buscar", "busqueda", "encontrar", "buscador"),
            "«Buscar en PixPin» busca archivos, tareas, lecciones y capturas de todos los chats; tambien hay un atajo en el buscador del telefono."),
        e("Compartir y exportar", listOf("compartir", "exportar", "html", "pagina web", "enlace", "png", "svg"),
            "Todo se comparte con la misma hoja: imagen, PDF, SVG, pagina web, .pixpin editable o Wi-Fi, y se puede pedir un enlace que caduca."),
        e("Sincronizar", listOf("sincroniz", "sync", "wifi", "grupo", "pc", "ordenador", "windows", "movil"),
            "En Sincronizar, los aparatos con el mismo codigo de grupo se encuentran en la misma Wi-Fi (tambien el PC con PixPin para Windows) y juntan lo cambiado. «Enviar una vez» da un codigo de seis cifras y un QR."),
        e("Ajustes", listOf("ajustes", "configuracion", "opciones", "preferencias", "modo oscuro", "noche", "tema"),
            "Los Ajustes estan en el menu de la pantalla principal; cada herramienta del lienzo, gesto o funcion se enciende o apaga ahi. El modo noche se ajusta solo por la hora y la luz."),
        e("El asistente: ponerlo", listOf("asistente", "ia", "inteligencia", "modelo", "clave", "claude", "deepseek", "nube"),
            "El asistente contesta con la nube: en Ajustes → Asistente se elige Claude o DeepSeek y se pega tu clave, que se guarda cifrada y no viaja."),
        e("El asistente: que hace", listOf("que haces", "que sabes", "puedes", "asistente", "@asistente"),
            "Con internet y tu clave: contesta, dibuja en el lienzo, hace graficas, lee PDFs, busca en tus cosas y usa la app (notas, tareas, lienzos, lecciones, capturas). En otro chat se le llama con @asistente."),
        e("El asistente: conversacion nueva y memoria", listOf("conversacion nueva", "nueva conversacion", "olvida", "contexto", "memoria", "recuerda", "empezar de cero"),
            "En el chat «Asistente», «🧹 Conversación nueva» corta: lo de arriba sigue a la vista pero ya no se le manda. Lo duradero (tus preferencias) lo guarda en su memoria, que va a tus aparatos en guardados/asistente/memoria. Mientras trabaja puedes escribirle para corregirle o decirle «para»."),
        e("Reproductor de audio", listOf("audio", "musica", "reproduc", "escuchar", "cancion"),
            "Las notas de voz suenan en su burbuja. La Biblioteca de audio (menu de Mensajes guardados) las junta todas.")
    )

    private val VACIAS = setOf(
        "el", "la", "los", "las", "un", "una", "de", "del", "en", "y", "o", "a", "al", "que", "como", "donde", "esta",
        "estan", "hago", "hace", "hacer", "puedo", "puede", "quiero", "tengo", "cual", "cuando", "cuanto", "se", "me",
        "mi", "mis", "por", "para", "con", "es", "lo", "pixpin", "una", "unos", "unas"
    )

    private fun toca(e: Entrada, p: String): Boolean =
        e.claves.any { c -> ' ' !in c && (p.startsWith(c) || (p.toByteArray().size >= 4 && c.startsWith(p))) } ||
            (p.toByteArray().size >= 4 && Acciones.normalizar(e.tema).contains(p))

    private fun puntos(e: Entrada, pregunta: String, palabras: List<String>, cuantas: List<Int>): Double {
        var s = 0.0
        for (c in e.claves.filter { ' ' in it }) if (pregunta.contains(c)) s += 3.0
        for ((p, n) in palabras.zip(cuantas)) if (n > 0 && toca(e, p)) s += 2.0 / Math.sqrt(n.toDouble())
        return s
    }

    /** **Busca en el índice** lo que responde a la pregunta: las 2 entradas más a tono. */
    fun responder(pregunta: String): String {
        val q = Acciones.normalizar(pregunta)
        val palabras = q.split(Regex("[^\\p{L}\\p{N}+]+")).filter { it.isNotEmpty() && it !in VACIAS }
        val cuantas = palabras.map { p -> INDICE.count { toca(it, p) } }
        val puntuadas = INDICE.map { puntos(it, q, palabras, cuantas) to it }.filter { it.first > 0.0 }.sortedByDescending { it.first }
        if (puntuadas.isEmpty()) return "No encuentro eso en la ayuda de PixPin. Di que no lo sabes; no inventes menus."
        val mejor = puntuadas[0].first
        return puntuadas.take(2).filter { it.first >= mejor * 0.6 }.joinToString("\n") { "${it.second.tema}: ${it.second.respuesta}" }
    }
}
