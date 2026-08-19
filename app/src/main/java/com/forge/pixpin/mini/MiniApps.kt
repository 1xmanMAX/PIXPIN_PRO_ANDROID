package com.forge.pixpin.mini

import com.forge.pixpin.R
import java.util.Locale

/**
 * Las **mini-aplicaciones** del chat: una lista de tareas, un control de gastos, y lo que
 * venga.
 *
 * ## Qué son y por qué no son otro editor
 *
 * Una mini-app es un mensaje que **hace algo** en vez de limitarse a estar guardado. Una
 * lista de la compra se tacha; unos gastos se suman. Lo que las separa de una nota no es el
 * contenido —es texto igual— sino que la burbuja **cuenta**: «3 de 7», «60,50 €». Ese
 * número en la conversación es la mini-app entera; si hay que abrirla para saber cómo va,
 * no sirve de recordatorio y vuelve a ser una nota.
 *
 * ## La decisión de formato: **Markdown dentro de [Mensaje.texto]**
 *
 * Se barajaron tres, y se eligió la primera:
 *
 * 1. **Markdown en el propio mensaje** (lo que se hace).
 * 2. Un JSON propio en el mismo JSONL.
 * 3. Un archivo aparte referenciado, como los dibujos.
 *
 * ### Por qué Markdown y no un formato nuevo
 *
 * Porque **una lista de tareas ya existe en este proyecto**. `motormd` lleva desde el
 * principio `MarkdownBlock.Tarea`: entiende `- [ ]` y `- [x]`, los pinta con su casilla
 * (`MarkdownText.kt`), los edita sin enseñar las marcas (`EditorVivo.kt`) y sabe continuar
 * la lista al pulsar intro (`Vivo.kt`). Inventar aquí un `data class ItemTodo` con su
 * serializador sería tener **dos listas de tareas** en la misma aplicación, con dos
 * parsers que se van separando y un día no se abren la una a la otra. La norma de la casa
 * es no duplicar herramientas, y esta es exactamente esa situación.
 *
 * Lo mismo con los gastos: una tabla de conceptos e importes **es una tabla de Markdown**,
 * y `Tablas.kt` ya lee y escribe tablas, con su escape de `\|` incluido.
 *
 * ### Por qué dentro del mensaje y no en un archivo aparte
 *
 * Un dibujo vive en su archivo porque es grande y binario; una lista de la compra son
 * cuatrocientos bytes. Metiéndola en [Mensaje.texto] se gana de golpe:
 *
 * - **La busca [buscar] gratis**, que ya mira dentro de `texto`. «¿Dónde apunté lo del
 *   fontanero?» encuentra el gasto sin que nadie escriba una búsqueda nueva.
 * - **No hay archivos huérfanos.** Borrar el mensaje borra la mini-app; no hay una carpeta
 *   que se llene de listas de la compra de hace dos años.
 * - **Una línea rota se lleva una mini-app, no todas**, que es la promesa del JSONL.
 *
 * Se paga un precio y conviene decirlo: marcar una casilla **reescribe el archivo entero**
 * (`MensajesStore.reescribir`), porque editar siempre lo hace. En un chat con uno mismo el
 * archivo son unos pocos kilobytes y no se nota; el día que se note, la salida es mover
 * solo las mini-apps a archivo aparte, y el documento seguiría siendo el mismo Markdown.
 *
 * ### Por qué sobrevive a versiones futuras
 *
 * Porque no hay nada que versionar. El documento es texto que se lee a simple vista, lo
 * abre cualquier programa del mundo y lo entiende cualquier persona sin la aplicación
 * delante. Un JSON propio, en cambio, es un formato que hay que mantener y migrar, y del
 * que nadie puede sacar sus datos si esto deja de existir.
 *
 * ## Cómo se añade la mini-app número tres
 *
 * Un valor más en [MiniApp] con sus dos funciones. El compilador obliga a escribirlas —son
 * abstractas— y **no hay nada más que tocar**: ni el almacén, ni la clase de mensaje, ni la
 * búsqueda. Esa es la puerta que se deja abierta.
 */

/**
 * Lo que la burbuja enseña de una mini-app **sin abrirla**.
 *
 * [texto] viene ya compuesto porque el resumen es un número con su forma —«3 de 7»,
 * «60,50 €»— y componerlo necesita saber de tareas o de monedas, que es justo lo que sabe
 * el núcleo y no sabe la pantalla. La palabra «de» va escrita aquí, sin `R.string`, por una
 * razón concreta: resolver un recurso necesita un `Context`, y con él este archivo dejaría
 * de poder comprobarse en la JVM. Los números van aparte en [hechas] y [de] para que una
 * pantalla que quiera decirlo de otra forma no tenga que volver a contar.
 */
data class ResumenMini(
    val texto: String,
    /** Cuántas van, cuando la mini-app cuenta cosas. */
    val hechas: Int = 0,
    /** Cuántas hay en total. */
    val de: Int = 0,
    /** De 0 a 1, para una barrita de avance. Null cuando no hay avance que enseñar. */
    val avance: Float? = null,
    /** Todavía no tiene nada dentro: recién creada y sin tocar. */
    val vacia: Boolean = true
)

/**
 * Las mini-aplicaciones que existen, en el orden en que conviene ofrecerlas.
 *
 * [id] es lo que se guarda en `Mensaje.miniapp` y **no se cambia nunca**: es la única
 * palabra que ata un mensaje ya escrito con la pantalla que lo abre. Renombrarla dejaría
 * las mini-apps viejas sin dueño. El nombre bonito, que sí se puede cambiar, va en
 * [nombre] como id de `R.string` —igual que en `BloqueDelChat`— para que traducirlo no
 * obligue a meter Android aquí dentro.
 */
enum class MiniApp(val id: String, val nombre: Int) {

    /** Cosas que se tachan. Ver [Tareas]. */
    TAREAS("tareas", R.string.miniapp_tareas) {
        override fun documentoNuevo(titulo: String, locale: Locale): String =
            Tareas.escribir(titulo, emptyList())

        override fun resumen(documento: String, locale: Locale): ResumenMini =
            Tareas.resumen(documento)
    },

    /** Conceptos con importe, y su total. Ver [Gastos]. */
    GASTOS("gastos", R.string.miniapp_gastos) {
        override fun documentoNuevo(titulo: String, locale: Locale): String =
            Gastos.escribir(Libro(titulo, Gastos.monedaDe(locale), emptyList()), locale)

        override fun resumen(documento: String, locale: Locale): ResumenMini =
            Gastos.resumen(documento, locale)
    },

    /**
     * Tiempo que sube. Ver [Cronometro].
     *
     * El resumen sale con lo que llevaba **la última vez que se tocó**, no con lo que
     * lleva ahora: la burbuja de la conversación no es un reloj y no puede estar
     * repintándose sesenta veces por segundo dentro de una lista. Quien quiera verlo
     * correr, lo abre.
     */
    CRONOMETRO("cronometro", R.string.miniapp_cronometro) {
        override fun documentoNuevo(titulo: String, locale: Locale): String =
            Tiempos.escribirCronometro(titulo, Cronometro())

        override fun resumen(documento: String, locale: Locale): ResumenMini {
            val c = Tiempos.leerCronometro(documento)
            return ResumenMini(
                texto = Tiempos.comoSeLeeCorto(c.llevado),
                de = c.vueltas.size,
                vacia = c.llevado == 0L && !c.corriendo
            )
        }
    },

    /** Tiempo que baja. Ver [Temporizador]. */
    TEMPORIZADOR("temporizador", R.string.miniapp_temporizador) {
        override fun documentoNuevo(titulo: String, locale: Locale): String =
            Tiempos.escribirTemporizador(titulo, Temporizador())

        override fun resumen(documento: String, locale: Locale): ResumenMini {
            val t = Tiempos.leerTemporizador(documento)
            return ResumenMini(
                texto = Tiempos.comoSeLeeCorto(t.duracion),
                vacia = !t.corriendo
            )
        }
    },

    /** Un número que sube y baja. Ver [Contador]. */
    CONTADOR("contador", R.string.miniapp_contador) {
        override fun documentoNuevo(titulo: String, locale: Locale): String =
            Contador.escribir(titulo, Cuenta())

        override fun resumen(documento: String, locale: Locale): ResumenMini {
            val c = Contador.leer(documento)
            return ResumenMini(texto = c.valor.toString(), vacia = c.valor == 0L)
        }
    },

    /** Nombres y un sorteo. Ver [Ruleta] y [RuletaDoc]. */
    RULETA("ruleta", R.string.miniapp_ruleta) {
        override fun documentoNuevo(titulo: String, locale: Locale): String =
            RuletaDoc.escribir(titulo, emptyList())

        override fun resumen(documento: String, locale: Locale): ResumenMini {
            val nombres = RuletaDoc.leer(documento)
            return ResumenMini(texto = "", de = nombres.size, vacia = nombres.isEmpty())
        }
    },

    /** Una hora a la que avisar. Ver [Alarma]. */
    ALARMA("alarma", R.string.miniapp_alarma) {
        override fun documentoNuevo(titulo: String, locale: Locale): String =
            Tiempos.escribirAlarma(titulo, Alarma())

        override fun resumen(documento: String, locale: Locale): ResumenMini {
            val a = Tiempos.leerAlarma(documento)
            return ResumenMini(
                texto = "${a.hora}:${a.minuto.toString().padStart(2, '0')}",
                vacia = !a.activa
            )
        }
    };

    /**
     * El documento con el que **nace** la mini-app, ya listo para guardarse.
     *
     * Nace con su cabecera puesta —el título, y en los gastos también la moneda— y sin
     * ninguna fila. Nacer vacía y no con un ejemplo dentro es deliberado: un ejemplo hay
     * que borrarlo antes de empezar, y ese borrado es el primer trabajo que uno se
     * encuentra al abrir algo que acaba de crear.
     */
    abstract fun documentoNuevo(titulo: String = "", locale: Locale = Locale.getDefault()): String

    /** Lo que enseña la burbuja. Ver [ResumenMini]. */
    abstract fun resumen(documento: String, locale: Locale = Locale.getDefault()): ResumenMini

    companion object {
        /**
         * La mini-app que dice ese [id], o null si no la conocemos.
         *
         * Null y no una excepción: un mensaje guardado por una versión **posterior** —con
         * una mini-app que aquí todavía no existe— tiene que poder seguir en la lista como
         * lo que es, un texto, en vez de tirar la conversación entera al abrirla.
         */
        fun de(id: String?): MiniApp? = entries.firstOrNull { it.id == id }
    }
}

/**
 * La primera línea del documento, cuando es un título de Markdown (`# La compra`).
 *
 * El título va en el documento y no en `Mensaje.nombre` porque tiene que viajar con el
 * texto: quien copie la lista y la pegue en otro sitio se lleva su nombre, y quien abra el
 * archivo a pelo ve de qué es la lista. Un título guardado aparte se pierde en cuanto el
 * texto sale de aquí.
 */
object Cabecera {

    private val TITULO = Regex("""^#{1,6}\s+(.*)$""")

    /** El título, o vacío si el documento no empieza por uno. */
    fun titulo(documento: String): String {
        val primera = documento.lineEndsSequence().firstOrNull()?.trim() ?: return ""
        return TITULO.find(primera)?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** El documento sin su línea de título. Es lo que leen [Tareas] y [Gastos]. */
    fun cuerpo(documento: String): String {
        if (titulo(documento).isEmpty()) return documento
        return documento.substringAfter('\n', "")
    }

    /**
     * La línea del título lista para encabezar un documento, con su renglón en blanco.
     *
     * Vacía cuando no hay título: un `#` suelto se leería como un encabezado sin texto y
     * ensuciaría el documento con una línea que no dice nada.
     *
     * Se le quitan los saltos de línea y las almohadillas de delante. Sin eso, un título
     * pegado desde otro sitio con dos renglones partiría el documento en dos y la segunda
     * mitad dejaría de ser la lista.
     */
    fun linea(titulo: String): String {
        val limpio = enUnaLinea(titulo).trimStart('#', ' ').trim()
        return if (limpio.isEmpty()) "" else "# $limpio\n\n"
    }
}

/**
 * El texto en **una sola línea**, que es lo que cabe en una tarea o en un concepto.
 *
 * Es la defensa contra lo que rompe el formato de guardado de verdad: se pega un párrafo
 * de tres renglones en una tarea y, al escribirlo, los renglones dos y tres salen del
 * documento **sin su casilla delante** — o sea, dejan de ser tareas y aparecen como texto
 * suelto que la mini-app ya no sabe leer. Convertir el salto en un espacio conserva lo que
 * se escribió y mantiene la regla de «una línea, una cosa».
 *
 * Se tratan las tres formas de partir línea que existen —`\r\n`, `\r` y `\n`— porque el
 * texto puede venir del portapapeles de cualquier sitio, y una sola de ellas sin tratar es
 * el documento roto.
 */
fun enUnaLinea(texto: String): String =
    texto.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim()

/** Las líneas del texto sin fabricar la lista entera, para mirar solo la primera. */
private fun String.lineEndsSequence(): Sequence<String> = splitToSequence('\n')
