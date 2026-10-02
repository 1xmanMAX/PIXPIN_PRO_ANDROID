package com.forge.pixpin.atajos

import android.content.Context
import android.os.Build
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.builtintypes.PotentialAction
import androidx.appsearch.builtintypes.Thing
import androidx.appsearch.platformstorage.PlatformStorage
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Proyecto
import java.io.File

/**
 * **Todos los archivos del chat en el buscador del teléfono** (2-oct-2026).
 *
 * Los atajos ([Atajos]) los lee cualquier buscador, pero el sistema no deja tener más de unos
 * quince: ahí solo caben los últimos. Para que salgan **todos**, Android 12 trae un índice de
 * búsqueda del sistema (AppSearch, «PlatformStorage») que leen el buscador del Pixel y los
 * lanzadores que usan la búsqueda global. Cada archivo entra como un `Thing` —el tipo estándar
 * que esos buscadores saben enseñar— con su nombre, de qué chat es y una acción que lo abre:
 * la misma seña `pixpin://atajo/archivo/<id>` que usan los atajos.
 *
 * Huawei y Honor sin servicios de Google tienen su propio buscador cerrado: ahí solo llegan los
 * atajos. En Android 10 y 11 este índice no existe y esto no hace nada.
 *
 * Solo se escribe lo que cambió: se guarda la firma de cada documento en un archivo al lado.
 * Trabajo de disco: llamar fuera del hilo principal.
 */
object IndiceDelBuscador {
    private const val BASE = "pixpin"
    private const val ARCHIVOS = "archivos"
    private const val PROYECTOS = "proyectos"
    private const val TANDA = 100

    private var sesion: AppSearchSession? = null
    private val CERROJO = Any()

    fun ponerAlDia(context: Context, proyectos: List<Proyecto>, mensajes: List<Mensaje>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        synchronized(CERROJO) {
            runCatching { ponerAlDiaYa(context.applicationContext, proyectos, mensajes) }
                .onFailure { android.util.Log.w("PixPin", "índice del buscador", it) }
        }
    }

    private fun ponerAlDiaYa(context: Context, proyectos: List<Proyecto>, mensajes: List<Mensaje>) {
        val nombreDelProyecto = proyectos.associate { it.id to it.nombre }
        val docs = LinkedHashMap<String, Pair<String, Thing>>()
        for (m in mensajes) {
            if (!Atajos.esArchivo(m)) continue
            val nombre = Atajos.nombreDe(m) ?: continue
            val chat = m.proyecto?.let { nombreDelProyecto[it] }
            val donde = if (chat != null) "Chat de $chat" else "Mensajes guardados"
            val clave = "$ARCHIVOS/${m.id}"
            docs[clave] = (nombre + "\u0001" + donde) to cosa(
                ARCHIVOS, m.id, nombre, "$donde · PixPin", m.cuando,
                Atajos.sena(Atajos.ARCHIVO, m.id).toString(), listOfNotNull(chat, m.ruta?.substringAfterLast('/'))
            )
        }
        for (p in proyectos) {
            if (p.archivado || p.nombre.isBlank()) continue
            docs["$PROYECTOS/${p.id}"] = p.nombre to cosa(
                PROYECTOS, p.id, p.nombre, "Proyecto · PixPin", p.creado.takeIf { it > 0 } ?: p.tocado,
                Atajos.sena(Atajos.PROYECTO, p.id).toString(), emptyList()
            )
        }

        val apunte = File(context.filesDir, "indice-buscador.txt")
        val antes = runCatching {
            apunte.readLines().mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        }.getOrDefault(emptyMap())
        val nuevos = docs.filter { (clave, v) -> antes[clave] != v.first.hashCode().toString() }
        val sobran = antes.keys - docs.keys
        if (nuevos.isEmpty() && sobran.isEmpty()) return

        val s = abrir(context)
        nuevos.values.map { it.second }.chunked(TANDA).forEach { tanda ->
            s.putAsync(PutDocumentsRequest.Builder().addDocuments(tanda).build()).get()
        }
        sobran.groupBy({ it.substringBefore('/') }, { it.substringAfter('/') }).forEach { (espacio, ids) ->
            s.removeAsync(RemoveByDocumentIdRequest.Builder(espacio).addIds(ids).build()).get()
        }
        s.requestFlushAsync().get()
        apunte.writeText(docs.entries.joinToString("\n") { it.key + "\t" + it.value.first.hashCode() })
    }

    private fun cosa(
        espacio: String, id: String, nombre: String, descripcion: String, cuando: Long,
        sena: String, otrosNombres: List<String>
    ): Thing = Thing.Builder(espacio, id)
        .setName(nombre)
        .setDescription(descripcion)
        .setAlternateNames(otrosNombres.filter { it.isNotBlank() && it != nombre })
        .setUrl(sena)
        .setCreationTimestampMillis(cuando)
        .addPotentialAction(PotentialAction.Builder().setName("Abrir").setUri(sena).build())
        .build()

    private fun abrir(context: Context): AppSearchSession {
        sesion?.let { return it }
        val s = PlatformStorage.createSearchSessionAsync(
            PlatformStorage.SearchContext.Builder(context, BASE).build()
        ).get()
        // El esquema, una vez por sesión. «Que lo enseñe el sistema» es justo lo que se busca.
        s.setSchemaAsync(
            SetSchemaRequest.Builder()
                .addDocumentClasses(Thing::class.java)
                .setForceOverride(true)
                .build()
        ).get()
        sesion = s
        return s
    }
}
