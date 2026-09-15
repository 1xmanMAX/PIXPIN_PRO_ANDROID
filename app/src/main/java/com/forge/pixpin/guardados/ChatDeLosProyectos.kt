package com.forge.pixpin.guardados

import android.content.Context
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.motor.AvisosDelProyecto
import com.forge.pixpin.motor.Proyecto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * **Lo que se crea en un proyecto aparece en su chat**, con su vista previa. Ver
 * [AvisosDelProyecto] para qué se cuenta y qué no.
 *
 * Se llama desde `ProyectosRepository.guardar`, que es por donde pasa todo cambio de un
 * proyecto, venga del botón que venga: así un sitio nuevo que añada hojas no puede olvidarse
 * de avisar. El trabajo va **fuera del hilo de la pantalla y en fila**: leer el chat para no
 * repetir un mensaje es leer un archivo, y dos guardados seguidos no pueden cruzarse y dejar el
 * mismo lienzo dos veces.
 *
 * Nunca repite: antes de escribir mira si en ese chat ya hay un mensaje que apunte a lo mismo.
 * La nota es la excepción buscada: si ya tiene mensaje, el mensaje **se pone al día** con el
 * texto nuevo, que es lo que hace que el chat no enseñe una versión vieja.
 */
object ChatDeLosProyectos {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val enFila = Dispatchers.IO.limitedParallelism(1)

    fun alGuardar(context: Context, antes: Proyecto?, despues: Proyecto) {
        val novedades = AvisosDelProyecto.novedades(antes, despues)
        if (novedades.isEmpty()) return
        val app = context.applicationContext as? PixPinApp ?: return
        app.scope.launch(enFila) {
            runCatching { publicar(app, despues, novedades) }
        }
    }

    /**
     * **Pone en el chat lo que está en los proyectos y falta en él.** Ver [RegistroDelChat]. Se llama
     * al arrancar y cada vez que la sincronización o un envío escriben proyectos.
     */
    fun reparar(context: Context) {
        val app = context.applicationContext as? PixPinApp ?: return
        app.scope.launch(enFila) {
            runCatching {
                val chat = MensajesStore(app)
                val todos = chat.leer()
                val faltan = app.proyectos.proyectos.value.flatMap { p ->
                    RegistroDelChat.queFalta(p, todos) { _, h ->
                        h.dibujo?.let { java.io.File(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(app, it)) }
                            ?.takeIf { it.exists() }?.lastModified()
                    }
                }
                // En orden de creación: así el número que recibe cada uno también sigue la hora.
                for (m in faltan.sortedBy { it.cuando }) chat.anadir(m)
                if (faltan.isNotEmpty()) android.util.Log.i("PixPinChat", "Reparados ${faltan.size} mensajes que faltaban en el chat")
            }
        }
    }

    private fun publicar(context: Context, p: Proyecto, novedades: List<AvisosDelProyecto.Novedad>) {
        val chat = MensajesStore(context)
        val suyos = chat.leer().filter { it.proyecto == p.id }
        fun yaEsta(clase: Clase, referencia: String?) = referencia != null && suyos.any { it.referencia == referencia && it.clase == clase }
        fun nuevo(clase: Clase, nombre: String, referencia: String, texto: String = "") = Mensaje(
            id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = clase,
            proyecto = p.id, nombre = nombre, referencia = referencia, texto = texto,
            // Ya es una hoja del proyecto: el menú no tiene que ofrecer «unir» otra vez.
            unido = true
        )
        for (n in novedades) when (n) {
            is AvisosDelProyecto.Novedad.Lienzo -> {
                val dibujo = n.hoja.dibujo ?: continue
                if (!yaEsta(Clase.DIBUJO, dibujo)) chat.anadir(nuevo(Clase.DIBUJO, n.hoja.nombre.ifBlank { "Lienzo" }, dibujo))
            }
            is AvisosDelProyecto.Novedad.Tabla -> {
                val tabla = n.hoja.tabla ?: continue
                if (!yaEsta(Clase.TABLA, tabla)) chat.anadir(nuevo(Clase.TABLA, n.hoja.nombre.ifBlank { "Tabla" }, tabla))
            }
            is AvisosDelProyecto.Novedad.Croquis ->
                if (!yaEsta(Clase.CROQUIS, n.id)) chat.anadir(nuevo(Clase.CROQUIS, "Croquis ${n.numero}", n.id))
            is AvisosDelProyecto.Novedad.Nota -> {
                val texto = n.hoja.nota ?: continue
                // La transcripción de un audio ya se ve debajo del audio: no se cuenta otra vez.
                if (suyos.any { it.hojaDelTexto == n.hoja.id }) continue
                val existente = suyos.firstOrNull { it.clase == Clase.NOTA && it.referencia == n.hoja.id }
                if (existente == null) chat.anadir(nuevo(Clase.NOTA, n.hoja.nombre, n.hoja.id, texto))
                else if (existente.texto != texto) chat.actualizar(existente.id) { it.copy(texto = texto) }
            }
        }
    }
}
