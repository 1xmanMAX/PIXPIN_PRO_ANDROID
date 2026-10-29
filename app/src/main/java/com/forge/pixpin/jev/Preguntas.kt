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
     * **Qué temas de la agenda ya se trataron** en una reunión que se está grabando, **en vivo**
     * (29-oct-2026): una petición cada vez que llega texto nuevo (frase a frase, como mucho una cada
     * 1,5 s), con tres Nouls por cada tema aún pendiente (el tema va dentro de la pregunta: cada tema,
     * su pregunta). Estado: `reunion` (el título, si lo hay), `agenda` (todos los temas, para que se
     * sepa de qué otros se hablará) y `conversacion`, los últimos ~40 s pasados a texto:
     * `conversacion.antes` (lo ya mirado en la petición anterior; si no hay, no va) y
     * `conversacion.ahora` (lo que se acaba de decir y aún nadie miró). Lo copia el móvil tal cual
     * (`docs/jev.md`).
     */
    object Temas {
        /** El nombre de la respuesta de «se trató» del tema [id]. */
        fun tratado(id: String) = "tratado_$id"

        /** El de «quedó cerrado». */
        fun cerrado(id: String) = "cerrado_$id"

        /** Y el de «se habla de él en lo último». */
        fun ahora(id: String) = "ahora_$id"

        /**
         * Por encima de esto en dos peticiones, la segunda con algo nuevo del tema, tratado (ver
         * [com.forge.pixpin.jev.Temas]): una conversación larga sobre un tema se reparte en muchas
         * frases y al principio parece a medias.
         */
        const val CASI = 0.5

        /** Cuántos temas pendientes van en una petición (tres preguntas cada uno). Los demás, en la siguiente. */
        const val POR_PETICION = 20

        /** **¿Se trató el tema en `conversacion`?** El caso que confunde, dicho en el «no»: nombrarlo de pasada o dejarlo para luego no es tratarlo. */
        fun seTrato(tema: String): Pregunta = Pregunta.noul(
            "In `conversacion`, do the speakers actually discuss the agenda topic «$tema»?",
            "They talk about it in substance: they explain it, give opinions, ask and answer about it, or decide something about it, e.g. for «presupuesto 2027»: «el presupuesto queda en cuarenta mil, lo aprobamos».",
            "It is only named in passing or announced for later, e.g. «del presupuesto hablamos al final» or «eso lo vemos la próxima semana»; or `conversacion` is about other topics."
        )

        /** **¿Quedó cerrado?** Una decisión, una respuesta o un siguiente paso acordado. */
        fun quedoCerrado(tema: String): Pregunta = Pregunta.noul(
            "In `conversacion`, is the agenda topic «$tema» settled with a decision, an answer or an agreed next step?",
            "Something is concluded about it, e.g. «entonces queda así», «lo aprobamos» or «Ana se encarga y lo revisamos el lunes».",
            "It is still open: options are being compared, it is postponed, e.g. «lo dejamos para la próxima», or it is not talked about in `conversacion`."
        )

        /**
         * **¿Lo que se acaba de decir es de ese tema?** Para que dos peticiones a medias no cuenten dos
         * veces lo mismo: la segunda tiene que traer algo nuevo del tema.
         */
        fun enLoUltimo(tema: String): Pregunta = Pregunta.noul(
            "Is `conversacion.ahora` about the agenda topic «$tema»?",
            "What was just said adds something about it: a comment, a figure, a question, an answer or a decision about it, e.g. for «presupuesto 2027»: «y en personal nos quedamos en doce mil».",
            "`conversacion.ahora` is about something else, or only names it to leave it for later, e.g. «del presupuesto hablamos luego»; what was said only in `conversacion.antes` does not count."
        )

        /** Las tres preguntas de cada tema, con su nombre. */
        fun preguntas(temas: List<Pair<String, String>>): List<Pair<String, Pregunta>> =
            temas.flatMap { (id, texto) ->
                listOf(
                    tratado(id) to seTrato(texto),
                    cerrado(id) to quedoCerrado(texto),
                    ahora(id) to enLoUltimo(texto)
                )
            }
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

    /**
     * **Lo que se dice en vivo, frase a frase** (29-oct-2026, los prototipos de
     * `docs/probar-ideas-sin-interfaz.md` del PC): **una sola petición por frase** con las preguntas de
     * varias ideas, porque todas comparten el estado (ver [com.forge.pixpin.jev.Vivo]). Estado: `reunion`
     * (el título), `frase.quien` y `frase.dice` (quién habla y lo que acaba de decir), `antes` (las dos
     * frases anteriores, «Quién: lo que dijo»; si no hay, no va) y, solo si van sus preguntas,
     * `participantes` (los nombres) y `agenda` (los temas).
     *
     * Lo que va dentro de cada pregunta (una tarea, una pregunta abierta, las expresiones de fecha, los
     * chats) va en su texto o en sus opciones: cada cosa, su pregunta.
     */
    object Vivo {
        const val COMPROMISO = "compromiso"
        const val QUIEN_DICHO = "quien_dicho"
        const val QUIEN = "quien"
        const val PLAZO_DICHO = "plazo_dicho"
        const val PLAZO = "plazo"
        const val URGENTE = "urgente"
        const val DECISION = "decision"
        const val PREGUNTA = "pregunta"
        const val CITA = "cita"
        const val CITA_CUANDO = "cita_cuando"
        const val CIERRE = "cierre"
        const val DE_LA_AGENDA = "de_la_agenda"
        const val TEMA = "tema"
        const val DICTADO = "dictado"
        const val DICTADO_CHAT_DICHO = "dictado_chat_dicho"
        const val DICTADO_CHAT = "dictado_chat"
        const val DICTADO_QUE = "dictado_que"

        /** La opción de [QUIEN] para «todos». */
        const val TODOS = "todos"

        /** Las opciones de [DICTADO_QUE]. */
        const val LO_QUE_SIGUE = "lo_que_sigue_en_la_frase"
        const val LA_ANTERIOR = "la_frase_anterior"

        /** Cuántas tareas y cuántas preguntas abiertas van en una petición. */
        const val POR_PETICION = 8

        /** «¿La frase es de la tarea [id]?» */
        fun sobre(id: String) = "sobre_$id"

        /** «¿La apura?» */
        fun apura(id: String) = "apura_$id"

        /** «¿Contesta la pregunta abierta [id]?» */
        fun respondida(id: String) = "respondida_$id"

        fun compromiso(): Pregunta = Pregunta.noul(
            "Does `frase.dice` contain an action item: something concrete that has to be done after this moment?",
            "A job is taken on, given or stated as needed, with or without a person, e.g. «yo me encargo de mandar la cotización el viernes», «Luis, tú llamas al proveedor» or «hay que pedir los permisos este mes».",
            "It describes, discusses or reports something and no job comes out of it, e.g. «la cotización llegó ayer» or an idea still open, «podríamos pedir otra»."
        )

        fun quienDicho(): Pregunta = Pregunta.noul(
            "Does `frase.dice` say who will do it?",
            "A person or group is named, or the speaker takes it on, e.g. «yo me encargo», «Marta lo llama», «lo hacemos todos».",
            "Nobody is named, e.g. «hay que mandar la cotización»."
        )

        /** [opciones]: (clave, nombre) de cada participante; [habla], el nombre de quien habla. */
        fun quien(opciones: List<Pair<String, String>>, habla: String): Pregunta {
            val v = opciones.map { (k, n) ->
                val d = if (n == habla) "«$n», the person speaking in `frase.quien`, will do it (e.g. «yo me encargo»)."
                else "«$n» will do it."
                k to d
            } + (TODOS to "Everyone in the meeting will do it together.")
            return Pregunta.Choice("Who will do what `frase.dice` commits to?", v)
        }

        fun plazoDicho(): Pregunta = Pregunta.noul(
            "Does `frase.dice` give a day or time by which something has to be done?",
            "A deadline is set, e.g. «la mando el viernes», «esto es para hoy», «para mañana a las 10».",
            "No deadline is set: a day only names a topic or a past event, e.g. «la cotización del viernes» or «el material llegó el lunes»."
        )

        /** [opciones]: (clave, palabras) de cada expresión de fecha de la frase. */
        fun plazo(opciones: List<Pair<String, String>>): Pregunta = Pregunta.Choice(
            "Which words in `frase.dice` say by when it has to be done?",
            opciones.map { (k, t) -> k to "«$t»" }
        )

        fun urgente(): Pregunta = Pregunta.noul(
            "Does `frase.dice` stress that something is urgent?",
            "Hurry is expressed, e.g. «es urgente», «cuanto antes», «sin falta hoy».",
            "No hurry is expressed, e.g. «cuando puedas» or only a plain date."
        )

        fun decision(): Pregunta = Pregunta.noul(
            "Does `frase.dice` announce a decision that the group has made?",
            "Something is settled from now on, e.g. «entonces se decidió: compramos los proyectores en enero» or «queda aprobado».",
            "Options are only discussed or proposed, e.g. «podríamos comprarlos en enero» or «yo propondría otra imprenta»."
        )

        fun pregunta(): Pregunta = Pregunta.noul(
            "Does `frase.dice` ask a question that expects an answer from someone else in the meeting?",
            "A real question to the others, e.g. «¿alguien sabe cuándo llega el material?».",
            "There is no question, or the speaker asks and answers at once, e.g. «¿y por qué? Porque…», or it is rhetorical."
        )

        /** La pregunta abierta va dentro. */
        fun contesta(pregunta: String): Pregunta = Pregunta.noul(
            "Does `frase.dice` answer the question «$pregunta»?",
            "It gives what was asked, also a plain yes or no, e.g. for «¿cuándo llega el material?»: «llega el jueves».",
            "It is about something else, or only puts the answer off, e.g. «lo averiguo y te digo»."
        )

        fun cita(): Pregunta = Pregunta.noul(
            "Does `frase.dice` set a day or time for people to meet or for an event to take place?",
            "A meeting, visit, class or event gets a moment, e.g. «nos vemos el martes a las 3» or «la visita a la obra es el jueves a las 9».",
            "A day is only a deadline for a task or names a past event, e.g. «mándalo el viernes» or «el material llegó el lunes»."
        )

        fun citaCuando(opciones: List<Pair<String, String>>): Pregunta = Pregunta.Choice(
            "Which words in `frase.dice` say when they meet or when the event is?",
            opciones.map { (k, t) -> k to "«$t»" }
        )

        fun cierre(): Pregunta = Pregunta.noul(
            "Is the speaker wrapping up the meeting in `frase.dice`?",
            "The meeting is being closed, e.g. «bueno, si no hay nada más, lo dejamos aquí» or «vamos cerrando».",
            "The discussion goes on, e.g. a new point is opened or someone is still explaining."
        )

        fun deLaAgenda(): Pregunta = Pregunta.noul(
            "Is `frase.dice` about one of the topics in `agenda`?",
            "It talks about one of them: a figure, an opinion, a question or a decision about it, also through «eso» or «esto» pointing to `antes`.",
            "It is about something else: greetings, jokes, the room or the call, or a matter that is not in `agenda`; or a topic is only named to leave it for later, e.g. «eso lo vemos al final»."
        )

        /** [opciones]: (clave, tema) de cada tema de la agenda. [conAnterior]: si el estado lleva `tema_anterior`. */
        fun tema(opciones: List<Pair<String, String>>, conAnterior: Boolean): Pregunta {
            val instrucciones = if (conAnterior)
                "Which topic in `agenda` is being discussed in `frase.dice`? `antes` is what was said just before, and it was about `tema_anterior`."
            else
                "Which topic in `agenda` is being discussed in `frase.dice`, given what was said just before in `antes`?"
            return Pregunta.Choice(instrucciones, opciones.map { (k, t) -> k to "The topic «$t»." })
        }

        fun dictado(): Pregunta = Pregunta.noul(
            "Does the speaker in `frase.dice` ask for something to be written down?",
            "They ask to note, write or post something, with or without saying where, e.g. «anota esto en el chat de la obra», «apúntalo» or «pon en el grupo de compras que faltan tornillos».",
            "Nothing is to be written down; a chat may only be mentioned, e.g. «lo vi en el chat de la obra»."
        )

        fun dictadoChatDicho(): Pregunta = Pregunta.noul(
            "Does `frase.dice` say in which chat to note it?",
            "A chat is named, e.g. «en el chat de la obra» or «en el grupo de compras».",
            "No chat is named, e.g. «anótalo»."
        )

        /** [opciones]: (clave, nombre) de cada chat. */
        fun dictadoChat(opciones: List<Pair<String, String>>): Pregunta = Pregunta.Choice(
            "In which chat does the speaker want it noted?",
            opciones.map { (k, n) -> k to "The chat «$n»." }
        )

        /** [opciones]: (clave, texto) de lo que se podría anotar. */
        fun dictadoQue(opciones: List<Pair<String, String>>): Pregunta = Pregunta.Choice(
            "What does the speaker want noted?",
            opciones.map { (k, t) -> k to "«$t»" }
        )

        /** La tarea va dentro. */
        fun esDeLaTarea(tarea: String): Pregunta = Pregunta.noul(
            "Is `frase.dice` about the task «$tarea»?",
            "It talks about doing it, its date or its state, also through «esto» or «eso» pointing to it in `antes`.",
            "It is about other things."
        )

        fun laApura(tarea: String): Pregunta = Pregunta.noul(
            "Does `frase.dice` ask for the task «$tarea» to be done sooner or say it is more urgent?",
            "e.g. «esto es para hoy», «lo necesito ya», «hay que adelantarlo al lunes».",
            "No hurry is added for it, or it is given more time, e.g. «eso puede esperar»."
        )
    }

    /**
     * **Repaso oral**: lo que explica un alumno, contra los puntos clave de la clase. Estado: `clase`
     * (el título) y `explicacion` (lo dicho hasta ahora). Dos Nouls por punto, con el punto dentro.
     */
    object Repaso {
        fun cubre(id: String) = "cubre_$id"

        fun contradice(id: String) = "contradice_$id"

        /** Cuántos puntos van en una petición. */
        const val POR_PETICION = 12

        fun explica(punto: String): Pregunta = Pregunta.noul(
            "Does `explicacion` explain the key point «$punto»?",
            "The student states the idea in their own words, e.g. for «las plantas producen oxígeno»: «al hacer la fotosíntesis la planta suelta oxígeno».",
            "The point is missing or only named, e.g. «y también está lo del oxígeno»."
        )

        fun loContradice(punto: String): Pregunta = Pregunta.noul(
            "Does `explicacion` say something that contradicts the key point «$punto»?",
            "Something said is wrong about it, e.g. for «las plantas producen oxígeno»: «las plantas producen dióxido de carbono en la fotosíntesis».",
            "What is said about it agrees with it, or it is not mentioned."
        )
    }

    // ------------------------------------------------------------------
    // Más casos (29-oct-2026, `docs/probar-ideas-sin-interfaz.md` del PC, «Más casos»): fuera de las
    // reuniones. Cada idea, su módulo de la biblioteca con su tabla; aquí solo el texto.
    // ------------------------------------------------------------------

    /**
     * **A qué chat va una captura**: el texto que el OCR sacó de la captura y el título de su ventana,
     * contra los chats del usuario. Estado: `captura.ventana`, `captura.texto` y `chats` (cada uno,
     * «Nombre: de qué trata»). Un Noul de «es de alguno» y una Choice de cuál (con el nombre y de qué
     * trata en la opción).
     */
    object CapturaAChat {
        const val DE_ALGUNO = "de_algun_chat"
        const val CHAT = "chat"

        fun deAlguno(): Pregunta = Pregunta.noul(
            "Is `captura` about the subject of one of the chats in `chats`?",
            "What the screenshot shows belongs with one of them, e.g. a plan or a budget of a building site when there is a chat about that site.",
            "It is about something none of the chats deal with, e.g. a recipe, a game or a news article when the chats are about work and classes."
        )

        /** [opciones]: (clave, «Nombre: de qué trata») de cada chat. */
        fun chat(opciones: List<Pair<String, String>>): Pregunta = Pregunta.Choice(
            "In which chat of `chats` should `captura` be saved?",
            opciones.map { (k, n) -> k to "The chat «$n»." }
        )
    }

    /**
     * **Etiquetas de una nota o captura**, de las que el usuario ya tiene. Un Noul por etiqueta (puede
     * valer más de una), con la etiqueta dentro. Estado: `nota.titulo` (si lo hay) y `nota.texto`.
     */
    object Etiquetas {
        /** El nombre de la respuesta de la etiqueta de clave [id]. */
        fun nombre(id: String) = "etiqueta_$id"

        /** Cuántas etiquetas van en una petición. */
        const val POR_PETICION = 30

        fun va(etiqueta: String): Pregunta = Pregunta.noul(
            "Would the user file `nota` under their tag «$etiqueta»?",
            "`nota` is the kind of thing the tag «$etiqueta» collects, also when the tag names a part of life rather than the subject of the text, e.g. the vet's bill for the dog under a tag «Mascotas».",
            "`nota` belongs with other things, or «$etiqueta» only comes up in passing, e.g. one word in a long text about something else."
        )
    }

    /**
     * **La mejor foto de una ráfaga**: las fotos de la ráfaga van en el estado (`fotos.foto_1`,
     * `fotos.foto_2`...), y por cada una cinco Nouls (nítida, ojos cerrados, oscura, quemada, torcida)
     * con la ruta de esa foto dentro; y una Choice de cuál se quedaría una persona.
     */
    object MejorFoto {
        fun clave(i: Int) = "foto_${i + 1}"

        fun nitida(i: Int) = "nitida_${i + 1}"

        fun ojos(i: Int) = "ojos_cerrados_${i + 1}"

        fun oscura(i: Int) = "oscura_${i + 1}"

        fun quemada(i: Int) = "quemada_${i + 1}"

        fun torcida(i: Int) = "torcida_${i + 1}"

        const val MEJOR = "mejor"

        /** Cuántas fotos de una ráfaga se miran como mucho. */
        const val COMO_MUCHO = 6

        fun esNitida(i: Int): Pregunta {
            val f = clave(i)
            return Pregunta.noul(
                "Is the main subject of `fotos.$f` sharp?",
                "Edges and details are crisp: the camera was focused and held still.",
                "The picture is blurred: out of focus, or smeared by movement of the camera or the subject."
            )
        }

        fun ojosCerrados(i: Int): Pregunta {
            val f = clave(i)
            return Pregunta.noul(
                "Is someone in `fotos.$f` caught with their eyes closed?",
                "At least one person has their eyes shut or half shut, as when blinking.",
                "Everyone's eyes are open, or there are no people in the picture."
            )
        }

        /** Oscura y quemada, dos preguntas (una sola con «ni oscura ni quemada» dudaba con las quemadas: 0.50). */
        fun esOscura(i: Int): Pregunta {
            val f = clave(i)
            return Pregunta.noul(
                "Is `fotos.$f` too dark?",
                "It is underexposed: the subject is hard to make out in the dark.",
                "There is enough light to see the subject clearly."
            )
        }

        fun esQuemada(i: Int): Pregunta {
            val f = clave(i)
            return Pregunta.noul(
                "Is `fotos.$f` washed out by too much light?",
                "It is overexposed: bright parts turn to plain white and the colours look faded.",
                "Bright areas and colours keep their detail."
            )
        }

        fun estaTorcida(i: Int): Pregunta {
            val f = clave(i)
            return Pregunta.noul(
                "Is `fotos.$f` visibly tilted?",
                "The horizon, the walls or the edges of the main subject lean clearly to one side.",
                "The picture is level: vertical lines are upright and horizontal lines are flat."
            )
        }

        fun mejor(n: Int): Pregunta = Pregunta.Choice(
            "Which picture of `fotos`, all taken in a quick burst of the same scene, would a person keep?",
            (0 until n).map { i ->
                val f = clave(i)
                f to "The picture `fotos.$f`."
            }
        )
    }

    /** **Recortar la pizarra o la hoja** de una foto: un Noul de «hay una», un Box de dónde está y un Noul de si se ve entera. Estado: `foto`. */
    object Pizarra {
        const val HAY = "hay_pizarra"
        const val DONDE = "donde"
        const val ENTERA = "entera"

        fun hay(): Pregunta = Pregunta.noul(
            "Does `foto` show a whiteboard, a blackboard or a sheet of paper with writing or drawings on it?",
            "There is a board or a page whose writing someone would want to keep, e.g. a whiteboard with a diagram, or notes on a sheet on a desk.",
            "There is nothing written to keep, e.g. a wall with a painting or a window, or an empty desk."
        )

        fun donde(): Pregunta = Pregunta.caja(
            "the whiteboard, blackboard or sheet of paper with writing in `foto`, edge to edge"
        )

        fun entera(): Pregunta = Pregunta.noul(
            "Is the whole board or sheet in `foto` inside the picture?",
            "All four of its edges can be seen.",
            "Part of it is cut off by the border of the picture."
        )
    }

    /**
     * **Qué OCR lee una imagen**: si tiene texto, de qué clase es (la Choice con la que el código elige
     * el motor) y, si ya hay lecturas de los dos motores, cuál copia mejor el texto. Estado: `imagen`.
     */
    object Ocr {
        const val HAY_TEXTO = "hay_texto"
        const val TIPO = "tipo"
        const val MEJOR = "mejor_lectura"

        const val CAPTURA = "captura_de_pantalla"
        const val IMPRESO = "impreso"
        const val TABLA = "tabla"
        const val MANUSCRITO = "manuscrito"

        fun hayTexto(): Pregunta = Pregunta.noul(
            "Is there text to read in `imagen`?",
            "Words or numbers can be read, printed, on a screen or written by hand.",
            "It is a picture with no words in it, e.g. a landscape or a face."
        )

        fun tipo(): Pregunta = Pregunta.choice(
            "What kind of text is in `imagen`?",
            listOf(
                CAPTURA to "A screenshot: the windows, menus, buttons or chat bubbles of a program or a web page, captured from a screen.",
                IMPRESO to "A photo or a scan of a sheet of paper with printed paragraphs, e.g. a book page, a letter or a printed form lying on a table.",
                TABLA to "A table: values arranged in rows and columns, printed or on a screen.",
                MANUSCRITO to "Text written by hand with a pen, a pencil or a marker."
            )
        )

        /** [lecturas]: (motor, lo que leyó). */
        fun mejor(lecturas: List<Pair<String, String>>): Pregunta = Pregunta.Choice(
            "Which of these readings copies the text of `imagen` most faithfully?",
            lecturas.map { (k, t) -> k to "«$t»" }
        )
    }

    /**
     * **Avisos que interrumpen o esperan**: lo que llega (una sincronización, un mensaje, un aviso de una
     * app) mientras el usuario está en una reunión, grabando o presentando. Lo que hace el usuario lo
     * sabe el código; Jev solo juzga el aviso. Estado: `aviso.app`, `aviso.de`, `aviso.texto` y, si
     * está en una reunión, `reunion` (su título).
     */
    object Alertas {
        const val RUTINA = "rutina"
        const val PIDE_YA = "pide_ya"
        const val GRAVE = "grave"
        const val DE_LA_REUNION = "de_la_reunion"

        fun rutina(): Pregunta = Pregunta.noul(
            "Is `aviso` an automatic notice from an app rather than something a person wrote?",
            "e.g. «sincronización completada», «hay una actualización disponible», «tu copia de seguridad terminó» or an advert.",
            "A person wrote it, e.g. a message from a colleague or a family member, or a reminder the user set for something to do."
        )

        fun pideYa(): Pregunta = Pregunta.noul(
            "Does `aviso` ask the user to answer or act within the next few minutes?",
            "e.g. «¿me abres la puerta?», «llámame en cuanto puedas», «el taxi está abajo».",
            "It can wait, e.g. «cuando puedas mírate esto», «mañana hablamos» or a plain piece of news."
        )

        fun grave(): Pregunta = Pregunta.noul(
            "Would waiting an hour to read `aviso` cause real harm?",
            "e.g. a family emergency, the server is down for customers, the flight gate is closing, a payment fails today.",
            "Waiting only delays something, e.g. a question about next week, a document to review or a social chat."
        )

        fun deLaReunion(): Pregunta = Pregunta.noul(
            "Is `aviso` about the meeting `reunion` that the user is in right now?",
            "It is about that meeting, e.g. someone says they will be late to it, or sends the document being discussed.",
            "It is about other matters."
        )
    }

    /**
     * **Citas en los mensajes de un chat** (fuera de las reuniones): «¿te va el jueves a las 5?» →
     * «vale». Como [Vivo.cita], pero en un chat se propone en un mensaje y se acepta en otro. Estado:
     * `chat` (su nombre), `mensaje.de`, `mensaje.texto` y `antes` (los dos anteriores, «Quién: texto»;
     * si no hay, no va).
     */
    object HorarioDeChat {
        const val CITA = "cita"
        const val CUANDO = "cuando"
        const val ACORDADA = "acordada"
        const val ACEPTA = "acepta"

        fun cita(): Pregunta = Pregunta.noul(
            "Does `mensaje.texto` give a day or time for people to meet or for an appointment or event?",
            "A meeting, a visit, a call, a class or an appointment gets a moment, e.g. «¿quedamos el jueves a las 5?» or «la cena es el sábado a las 9».",
            "A day is only a deadline for a task or names a past event, e.g. «mándamelo el viernes» or «el lunes pasado fui al médico»."
        )

        fun cuando(opciones: List<Pair<String, String>>): Pregunta = Pregunta.Choice(
            "Which words in `mensaje.texto` say when they meet or when the event is?",
            opciones.map { (k, t) -> k to "«$t»" }
        )

        fun acordada(): Pregunta = Pregunta.noul(
            "Does `mensaje.texto` state the moment as settled, rather than propose it or ask about it?",
            "It is fixed or confirmed, e.g. «quedamos el jueves a las 5», «la cita es el martes a las 10» or «perfecto, el martes entonces».",
            "It is only proposed or asked, e.g. «¿te va bien el jueves?» or «podríamos vernos el martes»."
        )

        /** La propuesta pendiente va dentro. */
        fun acepta(propuesta: String): Pregunta = Pregunta.noul(
            "Does `mensaje.texto` accept the proposal «$propuesta»?",
            "It says yes to it, e.g. «vale», «perfecto, ahí nos vemos» or «sí, me va bien».",
            "It turns it down, proposes another moment or talks about something else, e.g. «ese día no puedo» or «mejor el viernes»."
        )
    }
}
