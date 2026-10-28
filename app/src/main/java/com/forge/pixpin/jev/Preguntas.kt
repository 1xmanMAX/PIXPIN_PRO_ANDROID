package com.forge.pixpin.jev

/**
 * **Todas las preguntas que PixPin le hace a Jev**, en un solo sitio (28-oct-2026; copia letra por
 * letra de `crates/pixpin-typesafe/src/preguntas.rs` del PC): su texto, sus criterios, los niveles de
 * cada Score y los umbrales con que se leen. **Ningún texto de pregunta vive fuera de este fichero**;
 * el resto de `jev/` solo arma el estado y decide con las respuestas.
 *
 * Reglas que se siguen (las de la guía de TypeSafe):
 *
 * - **Una sola cosa por pregunta**, la que una persona juzgaría de un vistazo; la decisión la toma el
 *   código con estas respuestas.
 * - Las preguntas nombran los campos del estado por su ruta entre comillas invertidas (`linea`,
 *   `dicho`, `tarea`, `pedido`).
 * - Cada nivel de un Score es una situación que se entiende sola, y el «no» de cada Noul es una
 *   situación concreta, no «lo contrario».
 * - **En inglés**: es el idioma en que Jev acierta más. El estado va tal cual, en el idioma de la
 *   clase o del chat; los ejemplos de los criterios están en español porque es lo que Jev va a leer.
 *
 * **Si el PC cambia un texto, se cambia aquí igual**: así una misma clase da lo mismo en los dos
 * aparatos. Cambiar un texto cambia la huella de la petición y lo del cache deja de valer, que es lo
 * que debe pasar.
 */
object Preguntas {

    /** Por encima, un Noul se lee «sí». */
    const val SI = 0.7

    /** Por debajo, «no». Entre los dos, **sin decidir**: tiene su propia fila en cada tabla. */
    const val NO = 0.3

    /** Cómo se lee un Noul. */
    enum class Lectura { SI, DUDOSO, NO }

    /** Lee una probabilidad con [SI] y [NO]. */
    fun leer(p: Double): Lectura = when {
        p >= SI -> Lectura.SI
        p <= NO -> Lectura.NO
        else -> Lectura.DUDOSO
    }

    /**
     * **Partes importantes de una reunión o clase**, una petición por renglón de la transcripción.
     * Estado: `clase` (el título), `linea` (el renglón), `antes` y `despues` (hasta dos renglones de
     * cada lado; si no hay, el campo no va).
     */
    object Reunion {
        const val TAREA = "tarea"
        const val TAREA_QUIEN = "tarea_quien"
        const val TAREA_CUANDO = "tarea_cuando"
        const val DECISION = "decision"
        const val PREGUNTA = "pregunta"
        const val EXAMEN = "examen"
        const val DATO = "dato"
        const val CHARLA = "charla"

        /** Cuántos renglones de cada lado van de contexto. */
        const val VECINOS = 2

        fun preguntas(): List<Pair<String, Pregunta>> = listOf(
            TAREA to Pregunta.noul(
                "Does `linea` contain an action item: something a person has to do after this moment?",
                "Someone is given a task or says they will do one, e.g. «para el lunes resuelvan el ejercicio 3» or «yo mando el informe mañana».",
                "The speaker explains or discusses the topic and nobody is asked or offers to do anything."
            ),
            // Especulativas: solo se leen si `tarea` es «sí».
            TAREA_QUIEN to Pregunta.noul(
                "Does `linea` say which person or group has to do the task?",
                "It names who does it, e.g. «Ana», «el grupo 2», «todos», «yo».",
                "Nobody is named as responsible, e.g. «hay que revisar el capítulo»."
            ),
            TAREA_CUANDO to Pregunta.noul(
                "Does `linea` say when the task is due?",
                "A day, date or moment is given, e.g. «para el lunes», «antes del parcial», «mañana».",
                "No deadline is mentioned, e.g. «lean el capítulo 4»."
            ),
            DECISION to Pregunta.noul(
                "Does `linea` announce a decision that has been made?",
                "Something is settled from now on, e.g. «entonces el examen será oral» or «queda aprobado el presupuesto».",
                "Options are only being discussed or proposed, e.g. «podríamos hacerlo oral»."
            ),
            PREGUNTA to Pregunta.noul(
                "Does someone in `linea` ask a question that expects an answer from another person?",
                "A real question is asked, e.g. «¿alguien sabe por qué baja la presión?» or «profe, ¿esto entra?».",
                "The speaker asks and answers at once, e.g. «¿y por qué pasa esto? Porque…», or there is no question."
            ),
            EXAMEN to Pregunta.noul(
                "Does `linea` say that something will be on an exam, a test or a graded assignment?",
                "Assessment is named, e.g. «esto entra en el parcial» or «lo voy a tomar en la práctica».",
                "Exams, tests and grades are not mentioned."
            ),
            DATO to Pregunta.noul(
                "Does `linea` state a definition, formula, rule or key fact of the topic of `clase`?",
                "A student would copy it into their notes, e.g. «la velocidad es la derivada de la posición».",
                "It is an example, an anecdote, a transition or a remark about the class itself."
            ),
            CHARLA to Pregunta.noul(
                "Is `linea` small talk unrelated to the topic of `clase`?",
                "Greetings, jokes, chit-chat or the logistics of the room or the call, e.g. «¿me escuchan?» or «buenos días a todos».",
                "The line is about the subject that is being taught or discussed."
            )
        )
    }

    /**
     * **Tarjetas de Anki que valen la pena**: una petición por tarjeta de la clase. Estado: `clase` (el
     * título), `minuto` («12:30») y `dicho` (lo que se dijo en ese tramo, el reverso de la tarjeta).
     */
    object Anki {
        const val VALOR = "valor"
        const val EXAMEN = "examen"

        /** Los niveles de [VALOR], del 0 al 3. */
        val NIVELES: List<String> = listOf(
            "Nothing to study: greetings, logistics, jokes, or words cut off so they make no sense on their own.",
            "An example or a comment that only makes sense while watching the class.",
            "It explains part of the topic, but a student would rather study it from another moment.",
            "It states a definition, formula, rule or key idea of the topic that a student would want to memorise."
        )

        fun preguntas(): List<Pair<String, Pregunta>> = listOf(
            VALOR to Pregunta.score(
                "How useful is `dicho` as the back of a flashcard for studying `clase`?",
                NIVELES
            ),
            EXAMEN to Pregunta.noul(
                "Does `dicho` say that this will be on an exam, a test or a graded assignment?",
                "Assessment is named, e.g. «esto entra en el parcial».",
                "Exams, tests and grades are not mentioned."
            )
        )
    }

    /**
     * **La urgencia de una tarea por su contexto**: una petición por tarea pendiente. Estado: `tarea`
     * (su texto, sin marcas), `lista` (el título de su lista), `chat` (el nombre del chat) y, si hay,
     * `mensajes_recientes` (los últimos del chat, del más viejo al más nuevo). Las fechas **no** van:
     * cuánto falta para la hora de la tarea lo cuenta el código.
     */
    object Urgencia {
        const val CONSECUENCIA = "consecuencia"
        const val BLOQUEA = "bloquea"
        const val PLAZO_AJENO = "plazo_ajeno"
        const val PIDEN_YA = "piden_ya"

        /** Los niveles de [CONSECUENCIA], del 0 al 3. */
        val NIVELES: List<String> = listOf(
            "Nothing happens: it is an idea, a wish or something optional.",
            "A small inconvenience for the person, e.g. a chore at home takes longer.",
            "It hurts work or studies, e.g. a lower grade or a colleague kept waiting.",
            "Serious harm, e.g. a failed course, a missed payment or appointment, or a legal or health problem."
        )

        /** `piden_ya` solo va si hay mensajes recientes: una lista vacía se lee como una señal. */
        fun preguntas(conMensajes: Boolean): List<Pair<String, Pregunta>> {
            val v = mutableListOf(
                CONSECUENCIA to Pregunta.score("What happens if `tarea` is done late?", NIVELES),
                BLOQUEA to Pregunta.noul(
                    "Is someone else waiting for `tarea` to be done before they can continue?",
                    "Other people depend on it, e.g. the group needs this part for the report, or a client waits for the invoice.",
                    "Only the person who wrote it is affected, e.g. «ordenar mis apuntes»."
                ),
                PLAZO_AJENO to Pregunta.noul(
                    "Is `tarea` tied to a deadline that someone else set, such as a teacher, an office or an event?",
                    "e.g. handing in homework, paying a bill, signing up for an exam, a flight.",
                    "The person can choose when to do it, e.g. «leer ese libro» or «ordenar el escritorio»."
                )
            )
            if (conMensajes) v += PIDEN_YA to Pregunta.noul(
                "Does someone in `mensajes_recientes` ask for `tarea` to be done soon?",
                "e.g. «¿ya está lo del informe?» or «lo necesito para hoy».",
                "The recent messages are about other things, or mention it without hurry."
            )
            return v
        }
    }

    /**
     * **Qué modelo contesta al asistente**: una petición por pedido. Estado: `pedido` (lo escrito) y,
     * si hay, `conversacion_reciente` (los últimos mensajes, con quién los dijo).
     */
    object Modelo {
        const val DIFICULTAD = "dificultad"
        const val ORDEN = "orden_simple"
        const val RAZONAR = "razonar"
        const val IMPORTA = "importa"

        /** Los niveles de [DIFICULTAD], del 0 al 3. */
        val NIVELES: List<String> = listOf(
            "A single direct action in the app, e.g. «abre la calculadora» or «apunta comprar pan».",
            "A short answer or a simple drawing, e.g. «¿cuánto es el 15 % de 80?» or «dibuja un círculo rojo».",
            "An explanation or a piece of work with several parts, e.g. «explícame la fotosíntesis con un esquema».",
            "Careful reasoning over a lot of material, e.g. a multi-step physics problem, planning a project or analysing a long document."
        )

        fun preguntas(): List<Pair<String, Pregunta>> = listOf(
            DIFICULTAD to Pregunta.score(
                "How hard is it to do well what `pedido` asks for?",
                NIVELES
            ),
            ORDEN to Pregunta.noul(
                "Is `pedido` a simple command for the app, such as opening, creating or noting something?",
                "e.g. «abre el lector», «pon un temporizador de 5 minutos», «nueva nota».",
                "It asks a question, or asks for an explanation, a text or a drawing to be made."
            ),
            RAZONAR to Pregunta.noul(
                "Does `pedido` need several steps of reasoning, such as solving a problem, proving something or comparing options?",
                "e.g. «resuelve: un tren sale a 80 km/h…», «compara estos dos planes».",
                "The answer is direct, e.g. «¿qué hora es en Tokio?» or «dibuja un triángulo»."
            ),
            IMPORTA to Pregunta.noul(
                "Would a mistake in the answer to `pedido` have real consequences for the user, such as a grade, money, health or a work deadline?",
                "e.g. the homework due tomorrow, a budget, a dose.",
                "A mistake costs nothing, e.g. a doodle, a curiosity or a test of the app."
            )
        )
    }
}
