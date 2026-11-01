package com.forge.pixpin.mini

import com.forge.pixpin.horario.Asignaturas
import com.forge.pixpin.horario.Horario
import java.time.LocalDateTime

/**
 * **Qué te va a llamar por teléfono, dicho en Tareas** (1-nov-2026). El usuario: «si un grupo o una
 * tarea tiene activada la función de recordatorio con llamada, que se vea algo que lo indique, un
 * 📞 y "te llamará todos los días a las 8:00" o algo así, para que entienda que eso me va a llamar,
 * y que lo pueda activar o desactivar fácilmente».
 *
 * Hay dos cosas que llaman (con la llamada secreta, [com.forge.pixpin.pin.LlamadaSecretaActivity]):
 *
 * - **Un grupo**: la lista de una asignatura (en el chat «Asignaturas», [Asignaturas]) cuya clase
 *   está en el horario: antes de cada clase suena su llamada ([com.forge.pixpin.horario.AvisosDelHorario]).
 *   Su encabezado dice cuándo («Te llamará de lunes a viernes a las 7:50») y tocarlo apaga o
 *   enciende las llamadas de esa clase ([conGrupo], [avisosQueLlaman]).
 * - **Una tarea con hora** (`⏰`, [Tareas.horaDe]): hasta ahora sonaba como un pin encima de todo;
 *   con la llamada puesta suena como una llamada, y «Volver a llamar en…» le vuelve a poner la hora
 *   ([conHoraOtraVez]). Su tarjeta dice «📞 Te llamará hoy 10:00» y se enciende y apaga con un toque.
 *
 * **Las dos son solo de este teléfono, a propósito.** El horario ya lo es (en el PC es «solo de
 * este equipo y no viaja», `horario::ruta`), y el formato de las tareas del PC no tiene dónde
 * guardar «esta llama»: inventar una marca en su texto (`📞`) la enseñaría en el PC como parte del
 * título y en cualquier versión vieja. Así que lo que llama se guarda aparte, en el teléfono
 * ([LlamadasGuardadas]), y la tarea sigue viajando igual: en el PC suena con su aviso de siempre.
 *
 * Puro: lo prueba `LlamadasDeTareasTest`.
 */
object LlamadasDeTareas {

    /** El símbolo de lo que llama. */
    const val TELEFONO = "📞"

    /** Lo que va delante del id de la llamada de una tarea («Volver a llamar en…»). */
    const val PREFIJO = "llamada-tarea:"

    private const val SEPARADOR = '\u0000'

    // ---- Las tareas ------------------------------------------------------

    /**
     * **Lo que identifica a una tarea que llama**: su lista (el id del mensaje) y su texto crudo
     * sin la hora. Sin la hora porque la hora cambia (otra hora, o se quita al sonar) y la llamada
     * tiene que seguir siendo de esa tarea; con la fecha de creación (`➕`), que no cambia y separa
     * dos tareas que se llamen igual.
     */
    fun clave(mensaje: String, crudo: String): String = mensaje + SEPARADOR + Tareas.sinHora(crudo).trim()

    /** Si la tarea [crudo] de la lista [mensaje] llama. */
    fun llama(claves: Set<String>, mensaje: String, crudo: String): Boolean = clave(mensaje, crudo) in claves

    /** Las claves con la llamada de esa tarea puesta ([llamar]) o quitada. */
    fun conLlamada(claves: Set<String>, mensaje: String, crudo: String, llamar: Boolean): Set<String> =
        if (llamar) claves + clave(mensaje, crudo) else claves - clave(mensaje, crudo)

    /** El id de la llamada de una tarea: lo que lleva la pantalla de la llamada para volver a llamar. */
    fun idDeLlamada(mensaje: String, crudo: String): String = PREFIJO + clave(mensaje, crudo)

    /** La lista y el texto sin hora de un id de [idDeLlamada]; null si no es de una tarea. */
    fun deIdDeLlamada(id: String): Pair<String, String>? {
        if (!id.startsWith(PREFIJO)) return null
        val resto = id.removePrefix(PREFIJO)
        val corte = resto.indexOf(SEPARADOR)
        if (corte <= 0) return null
        return resto.substring(0, corte) to resto.substring(corte + 1)
    }

    /**
     * **«Volver a llamar en…»** de una tarea: el documento con la hora [hora] puesta otra vez en la
     * tarea pendiente cuyo texto sin hora es [sinHora] (al sonar se le quitó, como en el PC). null
     * si ya no está pendiente (la tacharon o la quitaron entretanto): entonces no se vuelve a llamar.
     */
    fun conHoraOtraVez(documento: String, sinHora: String, hora: LocalDateTime): String? {
        val tareas = Tareas.leer(documento)
        val i = tareas.indexOfFirst { !it.hecha && Tareas.sinHora(it.texto).trim() == sinHora }
        if (i < 0) return null
        val nuevas = tareas.toMutableList().also { it[i] = it[i].copy(texto = Tareas.conHora(it[i].texto, hora)) }
        return Tareas.escribir(Cabecera.titulo(documento), nuevas)
    }

    /** «Te llamará hoy 10:00»: lo que dice la tarjeta de una tarea que llama. */
    fun textoDeTarea(hora: LocalDateTime, ahora: LocalDateTime = LocalDateTime.now()): String =
        "Te llamará ${Tareas.textoDeHora(hora, ahora)}"

    /**
     * Las claves que siguen siendo de algo: las de listas que ya no están se olvidan (borradas, o la
     * tarea movida a otra lista). [listas] son los ids de los mensajes que hay.
     */
    fun vigentes(claves: Set<String>, listas: Set<String>): Set<String> =
        claves.filterTo(LinkedHashSet()) { it.substringBefore(SEPARADOR) in listas }

    // ---- Los grupos del horario -----------------------------------------

    /** Las llamadas de un grupo: las clases del horario que son suyas y si están apagadas. */
    data class DeGrupo(
        /** Las clases de su asignatura, en el orden del horario. */
        val clases: List<Horario.Clase>,
        /** Los minutos de antes en que avisa el horario si la clase no dice otros. */
        val antelacion: Int,
        val apagada: Boolean
    )

    /** Cómo se guarda una asignatura apagada: su nombre sin mayúsculas ni tildes. */
    fun nombreDeGrupo(nombre: String): String = Horario.norm(nombre.trim())

    /**
     * Las llamadas del grupo [titulo] del chat [chat], o null si no llama: solo las listas del chat
     * «Asignaturas» llaman, y solo si su clase está en el horario (por su nombre, sin mirar
     * mayúsculas ni tildes, como [Asignaturas.lista]).
     */
    fun deGrupo(titulo: String, chat: String, h: Horario.Leido, apagadas: Set<String>): DeGrupo? {
        if (chat != Asignaturas.NOMBRE_CHAT) return null
        val n = nombreDeGrupo(titulo)
        if (n.isEmpty()) return null
        val clases = h.clases.filter { nombreDeGrupo(it.nombre) == n }
        if (clases.isEmpty()) return null
        return DeGrupo(clases, h.antelacion, n in apagadas)
    }

    /** Las asignaturas apagadas con [nombre] encendido ([encender]) o apagado. */
    fun conGrupo(apagadas: Set<String>, nombre: String, encender: Boolean): Set<String> =
        if (encender) apagadas - nombreDeGrupo(nombre) else apagadas + nombreDeGrupo(nombre)

    /**
     * **Los avisos que de verdad llaman**: los del horario sin las clases apagadas. Las evaluaciones
     * siguen llamando: son las que «sí o sí» hay que recordar, y no son del grupo sino del horario.
     */
    fun avisosQueLlaman(h: Horario.Leido, avisos: List<Horario.Aviso>, apagadas: Set<String>): List<Horario.Aviso> =
        if (apagadas.isEmpty()) avisos
        else avisos.filter { a ->
            val q = a.que
            !(q is Horario.Que.DeClase && h.clases.getOrNull(q.i)?.let { nombreDeGrupo(it.nombre) in apagadas } == true)
        }

    /**
     * «Te llamará de lunes a viernes a las 7:50»; con clases a horas distintas, cada una («… los
     * lunes a las 7:50 y los viernes a las 9:50»). Apagada: «Llamadas apagadas».
     */
    fun textoDeGrupo(g: DeGrupo): String {
        if (g.apagada) return "Llamadas apagadas"
        val partes = g.clases.map { c ->
            var minuto = c.inicio - (c.antelacion ?: g.antelacion)
            var dias = c.dias
            // Una clase de las 00:05 con 10 min de antelación llama la víspera a las 23:55.
            while (minuto < 0) { minuto += 1440; dias = dias.map { Math.floorMod(it - 1, 7) }.sorted() }
            "${dias(dias)} ${aLas(minuto % 1440)}"
        }.distinct()
        return "Te llamará " + juntar(partes)
    }

    private val DIAS_CORTOS = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
    private val LOS_DIAS = listOf("los lunes", "los martes", "los miércoles", "los jueves", "los viernes", "los sábados", "los domingos")

    /** «todos los días», «de lunes a viernes», «los martes», «lun, mié y vie» (0 es el lunes). */
    fun dias(dias: List<Int>): String {
        val d = dias.filter { it in 0..6 }.distinct().sorted()
        return when {
            d.size == 7 -> "todos los días"
            d == listOf(0, 1, 2, 3, 4) -> "de lunes a viernes"
            d == listOf(0, 1, 2, 3, 4, 5) -> "de lunes a sábado"
            d == listOf(5, 6) -> "los fines de semana"
            d.size == 1 -> LOS_DIAS[d[0]]
            else -> juntar(d.map { DIAS_CORTOS[it] })
        }
    }

    /** «a las 7:50», «a la 1:05» (minuto del día, en 24 horas). */
    fun aLas(minuto: Int): String {
        val h = minuto / 60
        return (if (h == 1) "a la " else "a las ") + "%d:%02d".format(h, minuto % 60)
    }

    /** «a», «a y b», «a, b y c». */
    private fun juntar(v: List<String>): String = when (v.size) {
        0 -> ""
        1 -> v[0]
        else -> v.dropLast(1).joinToString(", ") + " y " + v.last()
    }
}
