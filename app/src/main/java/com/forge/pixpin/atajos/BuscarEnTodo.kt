package com.forge.pixpin.atajos

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.lecciones.LeccionesStore
import com.forge.pixpin.lecciones.Texto

/**
 * **Buscar en todo PixPin** (4-oct-2026): lo que encuentra la tarjeta de [BuscarActivity].
 *
 * El usuario lo pidió así: «escribir la P y luego lo que busco, y que me aparezca solo lo de
 * PixPin». El buscador del teléfono no deja a una app quedarse con una letra —mezcla siempre
 * contactos, ajustes y la web—, así que la «P» abre esta tarjeta, que busca solo aquí: las
 * **funciones** (grabar, capturas, lecciones…), los **proyectos**, las **lecciones**, los
 * **archivos** del chat, las **notas** y las **tareas**.
 *
 * Sin Android: se prueba en `BuscarEnTodoTest`.
 */
object BuscarEnTodo {

    enum class Tipo(val nombre: String) {
        FUNCION("Funciones"), PROYECTO("Proyectos"), LECCION("Lecciones"),
        ARCHIVO("Archivos"), TAREAS("Tareas"), NOTA("Notas y mensajes")
    }

    /**
     * Una cosa que se puede encontrar. [accion] es la seña de [Atajos] para las funciones y
     * proyectos; [mensaje] el del chat para lo demás.
     */
    data class Elemento(
        val tipo: Tipo,
        val titulo: String,
        val detalle: String,
        val cuando: Long,
        val emoji: String,
        val accion: String? = null,
        val id: String? = null,
        val mensaje: Mensaje? = null,
        /** Palabras que lo encuentran sin estar en el título: «voz» encuentra «Grabar». */
        val otras: String = ""
    ) {
        internal val enTitulo: String = Texto.normal(titulo)
        internal val enTodo: String = Texto.normal("$titulo $detalle $otras")
    }

    /** Lo que hace PixPin, con las palabras con las que uno lo pediría. */
    val FUNCIONES: List<Elemento> = listOf(
        Elemento(Tipo.FUNCION, "Grabar nota de voz", "Micrófono flotante", 0, "🎙", Atajos.GRABAR, otras = "voz audio microfono grabadora llamada"),
        Elemento(Tipo.FUNCION, "Nueva tarea", "Apuntar sin abrir la app", 0, "✅", Atajos.TAREA, otras = "pendiente hacer lista recordar"),
        Elemento(Tipo.FUNCION, "Tareas", "Todas las listas e Inbox", 0, "☑️", Atajos.TAREAS, otras = "pendientes listas inbox hechas"),
        Elemento(Tipo.FUNCION, "Nueva lección", "Lo que aprendiste", 0, "💡", Atajos.LECCION, otras = "aprendi error leccion aprendida"),
        Elemento(Tipo.FUNCION, "Lecciones", "Buscar y repasar", 0, "📚", Atajos.LECCIONES, otras = "aprendidas repaso errores"),
        Elemento(Tipo.FUNCION, "Capturar pantalla", "Recortar y fijar", 0, "✂️", Atajos.CAPTURAR, otras = "captura recorte pantallazo"),
        Elemento(Tipo.FUNCION, "Galería de capturas", "Pictures/PixPin", 0, "🖼", Atajos.CAPTURAS, otras = "capturas fotos imagenes galeria"),
        Elemento(Tipo.FUNCION, "Soltar archivos", "Recuadro para arrastrar desde otra app", 0, "📥", Atajos.SOLTAR, otras = "arrastrar soltar pantalla partida"),
        Elemento(Tipo.FUNCION, "Nota nueva", "Markdown", 0, "📝", Atajos.NOTA, otras = "markdown escribir texto apunte"),
        Elemento(Tipo.FUNCION, "Mensajes guardados", "El chat", 0, "💬", Atajos.MENSAJES, otras = "chat mensajes guardados"),
        Elemento(Tipo.FUNCION, "Proyectos", "Todos los proyectos", 0, "📁", Atajos.PROYECTOS, otras = "carpetas lista"),
        Elemento(Tipo.FUNCION, "Sincronizar", "Por Wi-Fi con el PC u otro teléfono", 0, "🔄", Atajos.SINCRONIZAR, otras = "wifi enviar pc ordenador")
    )

    /** Todo lo buscable, sacado del chat y de los proyectos. Trabajo de CPU: fuera del hilo principal. */
    fun elementos(mensajes: List<Mensaje>, proyectos: List<Pair<String, String>>): List<Elemento> {
        val nombreDe = proyectos.toMap()
        val salida = ArrayList<Elemento>(FUNCIONES.size + proyectos.size + mensajes.size)
        salida += FUNCIONES
        proyectos.forEach { (id, nombre) ->
            if (nombre.isNotBlank()) salida += Elemento(Tipo.PROYECTO, nombre, "Proyecto", 0, "🪐", Atajos.PROYECTO, id)
        }
        val vistos = HashSet<String>()
        // Lo que cuelga de una lección se ve en la lección, no en el chat: ir allí no llevaría a nada.
        val lecciones = mensajes.mapNotNullTo(HashSet()) { m -> m.id.takeIf { LeccionesStore.esLeccion(m) } }
        for (m in mensajes) {
            // Un mensaje repetido en el chat saldría dos veces con la misma clave.
            if (m.enBuzon || !vistos.add(m.id) || m.respondeA in lecciones) continue
            val chat = m.proyecto?.let { nombreDe[it] } ?: "Mensajes guardados"
            salida += when {
                LeccionesStore.esLeccion(m) -> Elemento(
                    Tipo.LECCION, m.nombre.removePrefix("💡 ").ifBlank { "Lección" }, chat, m.cuando, "💡",
                    id = m.ruta?.substringAfterLast('/')?.removeSuffix(LeccionesStore.EXTENSION), mensaje = m, otras = m.texto
                )
                m.clase == Clase.MINIAPP && m.miniapp == "tareas" -> Elemento(
                    Tipo.TAREAS, m.texto.lineSequence().firstOrNull { it.isNotBlank() }?.trim('#', ' ')?.take(80) ?: "Tareas",
                    chat, m.cuando, "☑️", mensaje = m, otras = m.texto
                )
                Atajos.esArchivo(m) -> Elemento(
                    Tipo.ARCHIVO, Atajos.nombreDe(m) ?: continue, chat, m.cuando, emojiDe(m.clase), mensaje = m, otras = m.texto
                )
                m.texto.isNotBlank() -> Elemento(
                    Tipo.NOTA, m.texto.lineSequence().first { it.isNotBlank() }.take(90), chat, m.cuando, "📝", mensaje = m, otras = m.texto
                )
                else -> continue
            }
        }
        return salida
    }

    private fun emojiDe(c: Clase) = when (c) {
        Clase.IMAGEN -> "🖼"; Clase.VOZ -> "🎙"; Clase.DIBUJO, Clase.PAGINA -> "✏️"
        Clase.CROQUIS -> "🧊"; Clase.TABLA -> "📊"; else -> "📄"
    }

    /**
     * **Las pestañas** (5-oct-2026, como «Buscar en PixPin v2» del PC, `buscar_todo/modelo.rs`):
     * Todo, Archivos, Tareas, Lecciones y Acciones. La de Capturas del PC no está: aquí las capturas
     * no entran en el buscador (se ven en su galería, que sí sale en Acciones).
     */
    enum class Pestana(val nombre: String) {
        TODO("Todo"), ARCHIVOS("Archivos"), TAREAS("Tareas"), LECCIONES("Lecciones"), ACCIONES("Acciones")
    }

    /** A qué pestaña va cada cosa. Como en el PC, los proyectos y las notas van con los archivos. */
    fun pestanaDe(t: Tipo): Pestana = when (t) {
        Tipo.ARCHIVO, Tipo.PROYECTO, Tipo.NOTA -> Pestana.ARCHIVOS
        Tipo.TAREAS -> Pestana.TAREAS
        Tipo.LECCION -> Pestana.LECCIONES
        Tipo.FUNCION -> Pestana.ACCIONES
    }

    /** Los grupos de «Todo», en el orden del PC: lo mejor arriba y luego por clase. */
    enum class Grupo(val nombre: String) {
        MEJOR("Mejor resultado"), TAREAS_Y_LECCIONES("Tareas y lecciones"), ARCHIVOS("Archivos"), ACCIONES("Acciones")
    }

    private fun grupoDe(t: Tipo): Grupo = when (pestanaDe(t)) {
        Pestana.TAREAS, Pestana.LECCIONES -> Grupo.TAREAS_Y_LECCIONES
        Pestana.ARCHIVOS -> Grupo.ARCHIVOS
        Pestana.TODO, Pestana.ACCIONES -> Grupo.ACCIONES
    }

    /** Cuántos hay en cada pestaña (la de «Todo», todos). */
    fun cuentas(resultados: List<Elemento>): Map<Pestana, Int> {
        val m = Pestana.entries.associateWithTo(LinkedHashMap()) { 0 }
        m[Pestana.TODO] = resultados.size
        for (e in resultados) pestanaDe(e.tipo).let { m[it] = m.getValue(it) + 1 }
        return m
    }

    /**
     * **Las líneas de la lista**: en «Todo», el mejor resultado arriba y el resto por grupos, cada
     * uno en el orden en que vino; en otra pestaña, solo lo suyo y sin cabeceras. Una cabecera es
     * un [Grupo]; una fila, un [Elemento].
     */
    fun lineas(resultados: List<Elemento>, pestana: Pestana): List<Any> {
        if (pestana != Pestana.TODO) return resultados.filter { pestanaDe(it.tipo) == pestana }
        if (resultados.isEmpty()) return emptyList()
        val v = ArrayList<Any>(resultados.size + 5)
        v.add(Grupo.MEJOR); v.add(resultados[0])
        val resto = resultados.drop(1)
        for (g in listOf(Grupo.TAREAS_Y_LECCIONES, Grupo.ARCHIVOS, Grupo.ACCIONES)) {
            val del = resto.filter { grupoDe(it.tipo) == g }
            if (del.isNotEmpty()) { v.add(g); v.addAll(del) }
        }
        return v
    }

    /**
     * Con la caja vacía y una pestaña que no es «Todo»: todo lo de esa pestaña, lo más reciente
     * primero (como en el PC, donde «Tareas» sin escribir enseña las listas).
     */
    fun deLaPestana(todo: List<Elemento>, pestana: Pestana, cuantos: Int = 60): List<Elemento> =
        if (pestana == Pestana.TODO) emptyList()
        else todo.asSequence().filter { pestanaDe(it.tipo) == pestana }
            .sortedByDescending { it.cuando }.take(cuantos).toList()

    /**
     * Quita la «p» de delante: quien escribe «p tesis» en el buscador del teléfono y llega aquí
     * sigue escribiendo igual. Solo si va sola y seguida de algo.
     */
    fun sinLaP(consulta: String): String {
        val t = consulta.trimStart()
        return if (t.length > 2 && (t[0] == 'p' || t[0] == 'P') && t[1] == ' ') t.substring(2) else consulta
    }

    /**
     * Lo que casa con [consulta]: **todas** sus palabras tienen que estar (como principio de
     * palabra o dentro), sin acentos ni mayúsculas. Primero lo que casa en el título, luego lo
     * más reciente. Vacía no devuelve nada (la tarjeta enseña entonces las funciones).
     */
    fun buscar(todo: List<Elemento>, consulta: String, cuantos: Int = 60): List<Elemento> {
        val palabras = Texto.normal(sinLaP(consulta)).split(' ', ',', '.').filter { it.isNotBlank() }
        if (palabras.isEmpty()) return emptyList()
        // Uno por palabra, no uno por cada cosa que casa: con miles de mensajes se nota.
        val alEmpezarPalabra = palabras.associateWith { Regex("(^|[^a-z0-9ñ])" + Regex.escape(it)) }
        return todo.asSequence()
            .filter { e -> palabras.all { it in e.enTodo } }
            .map { e ->
                val puntos = palabras.sumOf { p ->
                    when {
                        e.enTitulo.startsWith(p) -> 4
                        alEmpezarPalabra.getValue(p).containsMatchIn(e.enTitulo) -> 3
                        p in e.enTitulo -> 2
                        else -> 0
                    }
                } + if (e.tipo == Tipo.FUNCION || e.tipo == Tipo.PROYECTO) 1 else 0
                e to puntos
            }
            .sortedWith(compareByDescending<Pair<Elemento, Int>> { it.second }.thenByDescending { it.first.cuando })
            .take(cuantos)
            .map { it.first }
            .toList()
    }
}
