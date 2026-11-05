package com.forge.pixpin

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * **De qué chat se vino**: el id del proyecto, o [CHAT_GENERAL] para «Mensajes guardados».
 *
 * **26-oct-2026, el usuario: «si estoy en el lienzo, al salir debe volver al chat desde donde se
 * abrió, luego otro atrás al listado de chats. Ahora al retroceder en el lienzo se cierra la
 * app».** Lo pone el chat a todo lo que abre (ver `MensajesActivity.startActivity`), así que
 * ningún visor tiene que acordarse de pedirlo: el lienzo, el croquis, la nota, la tabla y el
 * lector, que viven **cada uno en su tarea** (ver el manifiesto), lo leen al cerrarse y vuelven a
 * ese chat en vez de dejar al usuario en el escritorio.
 */
const val EXTRA_DESDE_CHAT = "desde_chat"

/** El chat sin proyecto, «Mensajes guardados». El mismo nombre que le da la sincronización. */
const val CHAT_GENERAL = com.forge.pixpin.sincro.Disco.GENERAL

/**
 * **Atrás sigue la pila natural: visor → el chat de donde se abrió → la lista de chats → fuera**
 * (26-oct-2026). Ver [EXTRA_DESDE_CHAT].
 *
 * ## Por qué hacía falta
 *
 * - Los editores (lienzo, croquis, nota, tabla) y el lector de PDF viven en **su propia tarea**
 *   —tiene que ser así para que abrirlos desde la bola no traiga la aplicación entera al frente—, y
 *   al cerrarse la raíz de una tarea Android no vuelve al chat: vuelve a lo que hubiera detrás,
 *   casi siempre el escritorio.
 * - Lo que había para remediarlo (`volverALosProyectos`) llamaba a la pantalla principal, que es
 *   `singleTask`: traerla al frente **borraba el chat que estaba encima** y se acababa en la
 *   pantalla de Proyectos, y desde ella atrás ya salía de la aplicación.
 * - El chat abierto desde fuera (la bola, un atajo, «Abrir con») con `NEW_TASK` y sin la
 *   aplicación abierta era la raíz de su tarea: atrás también salía.
 *
 * Aquí está la decisión, sin Android, para probarla ([adonde]); [alCerrar] la cumple.
 */
object Volver {

    /**
     * **El [EXTRA_DESDE_CHAT] de lo que se abre desde la lista de chats** (27-oct-2026): no es un
     * chat, es la lista. Lo pone `MainActivity.startActivity`, como el chat pone el suyo. Hacía falta
     * desde que la búsqueda de la lista abre lo encontrado directamente en su visor (el usuario: «al
     * tocar un PDF, que lo abra directamente»): sin marca, el lector, que vive en su propia tarea,
     * al cerrarse dejaba en el escritorio en vez de volver a los resultados. Con ella vuelve a la
     * lista, que sigue con lo buscado (no se rehace: `singleTask` la trae tal cual estaba).
     */
    const val DESDE_LA_LISTA = "@lista"

    /** Adónde ir al cerrar una pantalla. */
    sealed interface Destino {
        /** Nada que hacer: debajo, en la misma tarea, está ya de donde se vino (o se vino de fuera). */
        data object Quedarse : Destino

        /** Al chat [chat] (un proyecto o [CHAT_GENERAL]), con la lista de chats debajo. */
        data class AlChat(val chat: String) : Destino

        /** A la lista de chats. */
        data object ALaLista : Destino
    }

    /**
     * **La decisión.**
     *
     * @param esRaiz si la pantalla que se cierra es la raíz de su tarea (`isTaskRoot`): si no lo
     *   es, lo de debajo es de donde se vino y `finish()` ya deja ahí.
     * @param desdeChat el [EXTRA_DESDE_CHAT] que trae, si lo trae.
     * @param desdeProyecto el `EXTRA_DESDE_PROYECTO` de siempre: el id del proyecto (que es su
     *   chat), o vacío si se vino de la lista. Se sigue leyendo por los atajos y lo ya abierto.
     * @param desdeDentro si se abrió desde dentro de PixPin. Lo que llega **de fuera** (compartir,
     *   abrir un archivo desde otra aplicación) vuelve a quien lo mandó, que es lo que espera quien
     *   lo mandó.
     */
    fun adonde(esRaiz: Boolean, desdeChat: String?, desdeProyecto: String?, desdeDentro: Boolean): Destino {
        if (!esRaiz) return Destino.Quedarse
        if (desdeChat == DESDE_LA_LISTA) return Destino.ALaLista
        val chat = desdeChat?.takeIf { it.isNotBlank() } ?: desdeProyecto?.takeIf { it.isNotBlank() }
        return when {
            chat != null -> Destino.AlChat(chat)
            desdeDentro || desdeProyecto != null -> Destino.ALaLista
            else -> Destino.Quedarse
        }
    }

    /**
     * **Lo que hace una pantalla al cerrarse** (antes de su `finish()`): calcula [adonde] con lo que
     * trae su intento y lo cumple. [desdeDentro] lo dice quien llama cuando lo sabe mejor que el
     * intento (el lienzo lleva su propia seña).
     */
    fun alCerrar(actividad: Activity, desdeDentro: Boolean = false) {
        // **Primero, la pantalla que la abrió** (5-nov-2026, el usuario: «que retroceder funcione bien: si
        // entro a otra ventana, al retroceder vuelvo atrás según se dio clic a los elementos»). Un lienzo
        // abierto desde una nota (o desde el lector, o desde el plano) vive en su propia tarea: al cerrarlo
        // se iba al chat o a la lista y la nota quedaba detrás. Si la tarea de quien la abrió sigue viva,
        // se vuelve a ella. Ver [PilaDeTareas].
        if (actividad.isTaskRoot && PilaDeTareas.volverALaAnterior(actividad)) return
        val i = actividad.intent
        val destino = adonde(
            esRaiz = actividad.isTaskRoot,
            desdeChat = i?.getStringExtra(EXTRA_DESDE_CHAT),
            desdeProyecto = i?.getStringExtra(EXTRA_DESDE_PROYECTO),
            desdeDentro = desdeDentro || i?.getStringExtra(EXTRA_DESDE_CHAT) != null
        )
        cumplir(actividad, destino)
    }

    fun cumplir(context: Context, destino: Destino) {
        when (destino) {
            Destino.Quedarse -> Unit
            is Destino.AlChat -> alChat(context, destino.chat)
            Destino.ALaLista -> aLaLista(context)
        }
    }

    /**
     * **Abre el chat [chat]** (el id de un proyecto, o [CHAT_GENERAL] / null para «Mensajes
     * guardados») en la tarea de la aplicación.
     *
     * Con `NEW_TASK` y la afinidad de siempre, Android lo pone en la tarea de la lista de chats: si
     * ese chat ya está arriba, `singleTop` lo reutiliza tal cual estaba (por `onNewIntent`); si no,
     * se apila encima de la lista. Y si la aplicación no estaba abierta, el chat queda de raíz y
     * es él quien, al cerrarse, abre la lista (ver `MensajesActivity.finish`).
     */
    fun alChat(context: Context, chat: String?) {
        val proyecto = chat?.takeIf { it.isNotBlank() && it != CHAT_GENERAL }
        val i = Intent(context, com.forge.pixpin.guardados.MensajesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (proyecto != null) {
            val nombre = (context.applicationContext as? PixPinApp)?.proyectos?.porId(proyecto)?.nombre.orEmpty()
            com.forge.pixpin.guardados.MensajesActivity.ponerChat(i, proyecto, nombre)
        }
        runCatching { context.startActivity(i) }
    }

    /**
     * Las pantallas que, de raíz, hacen de **tarea de la aplicación**: la lista de chats, un chat y la
     * configuración. Un visor abierto desde fuera se apila sobre la que haya.
     */
    private val RAICES = setOf(
        MainActivity::class.java.name,
        com.forge.pixpin.guardados.MensajesActivity::class.java.name,
        ConfiguracionActivity::class.java.name
    )

    /**
     * **Si un visor que se abre necesita la lista de chats debajo** (26-oct-2026). Desde una pantalla
     * ([desdeUnaPantalla]) se apila encima de ella y atrás vuelve ahí: no. Desde un pin, la bola o una
     * notificación (un `Context` que no es pantalla) va con `NEW_TASK` a la tarea de la aplicación si
     * la hay —una de [raicesVivas] es de [RAICES]— y atrás vuelve a lo que haya en ella; si no la hay,
     * el visor quedaría solo en su tarea y atrás sacaría de PixPin: entonces sí, la lista debajo.
     */
    fun conLaListaDebajo(desdeUnaPantalla: Boolean, raicesVivas: List<String?>): Boolean =
        !desdeUnaPantalla && raicesVivas.none { it in RAICES }

    /**
     * **Abre [intento] (un visor de la tarea de la aplicación) con la pila bien hecha.** Ver
     * [conLaListaDebajo]: desde fuera y sin la aplicación abierta, va con la lista de chats debajo
     * (`TaskStackBuilder`), para que atrás no deje al usuario en el escritorio.
     */
    fun abrirEnLaApp(context: Context, intento: Intent) {
        val desdeUnaPantalla = context is Activity
        if (!desdeUnaPantalla) intento.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val raices = if (desdeUnaPantalla) emptyList() else runCatching {
            context.getSystemService(android.app.ActivityManager::class.java).appTasks.map { it.taskInfo.baseIntent.component?.className }
        }.getOrElse { RAICES.toList() }
        runCatching {
            if (conLaListaDebajo(desdeUnaPantalla, raices)) {
                android.app.TaskStackBuilder.create(context)
                    .addNextIntent(Intent(context, MainActivity::class.java))
                    .addNextIntent(intento)
                    .startActivities()
            } else context.startActivity(intento)
        }
    }

    /** **La lista de chats**, la pantalla principal. */
    fun aLaLista(context: Context) {
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/**
 * **De qué tarea se vino a cada tarea** (5-nov-2026). Los editores y el lector viven cada uno en su
 * tarea (`documentLaunchMode`), así que Android no sabe volver a la pantalla que los abrió. Aquí se
 * apunta, al crearse la primera pantalla de una tarea, cuál era la tarea de la pantalla que estaba
 * delante en ese momento (si se acababa de ir: un toque dentro de PixPin, no la bola desde el
 * escritorio), y [volverALaAnterior] la trae al frente al cerrar.
 */
object PilaDeTareas : android.app.Application.ActivityLifecycleCallbacks {
    /** Lo que tarda como mucho una pantalla de PixPin en abrir otra: más, ya no se vino de ella. */
    private const val VENTANA_MS = 2_000L

    private val deDonde = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Volatile private var delante = -1
    @Volatile private var seFue = 0L
    @Volatile private var hayDelante = false

    override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {
        val t = a.taskId
        val antes = delante
        val reciente = hayDelante || android.os.SystemClock.uptimeMillis() - seFue < VENTANA_MS
        if (b == null && antes != -1 && antes != t && reciente && a.isTaskRoot) deDonde[t] = antes
    }

    override fun onActivityResumed(a: Activity) { delante = a.taskId; hayDelante = true }
    override fun onActivityPaused(a: Activity) { hayDelante = false; seFue = android.os.SystemClock.uptimeMillis() }
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
    override fun onActivityDestroyed(a: Activity) {
        if (a.isFinishing && a.isTaskRoot) deDonde.remove(a.taskId)
    }

    /** La tarea de la que se vino a [t], si se sabe. Para las pruebas. */
    internal fun anterior(t: Int): Int? = deDonde[t]

    /** Trae al frente la tarea de la que se vino a la de [actividad], si sigue viva. Devuelve si lo hizo. */
    fun volverALaAnterior(actividad: Activity): Boolean {
        val antes = deDonde[actividad.taskId] ?: return false
        val am = actividad.getSystemService(android.app.ActivityManager::class.java) ?: return false
        val tarea = runCatching { am.appTasks.firstOrNull { it.taskInfo.taskId == antes && it.taskInfo.numActivities > 0 } }.getOrNull() ?: return false
        return runCatching { tarea.moveToFront(); true }.getOrDefault(false)
    }
}
