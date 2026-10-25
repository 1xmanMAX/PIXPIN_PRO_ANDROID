package com.forge.pixpin.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * **AnkiDroid por su proveedor de contenido** (25-oct-2026): lo que en el PC hace AnkiConnect
 * (`conectar.rs`). Las tarjetas entran directas en su mazo, con sus imágenes.
 *
 * **Sin la librería `com.ichi2.anki:api`, a propósito**: esa librería es solo una envoltura de
 * este mismo contrato (`FlashCardsContract`, estable desde AnkiDroid 2.5) y aquí se usan cuatro
 * cosas: los mazos, los tipos de nota, las notas y los medios. Una dependencia menos que resolver
 * desde los espejos de Gradle, y los nombres de las columnas son los del contrato público.
 *
 * El permiso `com.ichi2.anki.permission.READ_WRITE_DATABASE` es «peligroso»: se pide al usar
 * ([EnviarAAnkiActivity]), no al instalar. Sin AnkiDroid, sin permiso o con su API apagada, todo
 * esto falla con [Fallo] y quien llama cae al fichero para importar.
 */
object AnkiDroid {

    const val PAQUETE = "com.ichi2.anki"
    const val PERMISO = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private const val AUTORIDAD = "com.ichi2.anki.flashcards"
    private val MAZOS = Uri.parse("content://$AUTORIDAD/decks")
    private val MODELOS = Uri.parse("content://$AUTORIDAD/models")
    private val NOTAS = Uri.parse("content://$AUTORIDAD/notes")
    private val MEDIOS = Uri.parse("content://$AUTORIDAD/media")

    /** El separador de campos de Anki (`Utils.joinFields`). */
    private const val SEP = "\u001f"

    /**
     * El tipo de nota propio, **básico de dos campos**: el «Basic» de AnkiDroid se llama distinto
     * en cada idioma («Básico», «Basis»…) y buscarlo por nombre fallaría según el teléfono.
     */
    private const val MODELO = "PixPin Básico"
    private val CAMPOS = listOf("Frente", "Reverso")

    class Fallo(motivo: String) : Exception(motivo)

    fun instalado(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PAQUETE, 0); true }.getOrDefault(false)

    fun tienePermiso(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISO) == PackageManager.PERMISSION_GRANTED

    /**
     * **Manda las tarjetas** al [mazo] (creándolo si no está) y devuelve cuántas entraron. Las
     * imágenes se copian a la carpeta de medios de AnkiDroid y el `<img src>` se cambia por el nombre
     * que les dio. Trabajo de disco y de proveedor: fuera del hilo principal.
     */
    fun anadir(context: Context, mazo: String, tarjetas: List<Tarjetas.Tarjeta>): Int {
        val r = context.contentResolver
        val deck = mazo(context, mazo)
        val modelo = modelo(context, deck)
        // Cada imagen una vez, aunque salga en varias tarjetas.
        val nombres = mutableMapOf<String, String>()
        var hechas = 0
        for (t in tarjetas) {
            var frente = t.frente
            var reverso = t.reverso
            for (i in t.imagenes) {
                val enAnki = nombres.getOrPut(i.nombre) { medio(context, i) ?: i.nombre }
                if (enAnki != i.nombre) {
                    frente = frente.replace("\"${i.nombre}\"", "\"$enAnki\"")
                    reverso = reverso.replace("\"${i.nombre}\"", "\"$enAnki\"")
                }
            }
            val valores = ContentValues().apply {
                put("mid", modelo)
                put("flds", frente + SEP + reverso)
                put("tags", t.etiquetas.joinToString(" "))
            }
            val nota = r.insert(NOTAS, valores) ?: continue
            hechas++
            // Las tarjetas de la nota, al mazo (`AddContentApi.addNoteForContentValues`).
            val cartas = Uri.withAppendedPath(nota, "cards")
            runCatching {
                r.query(cartas, null, null, null, null)?.use { c ->
                    val ord = c.getColumnIndex("ord")
                    while (c.moveToNext()) {
                        val u = Uri.withAppendedPath(cartas, c.getString(ord))
                        r.update(u, ContentValues().apply { put("deck_id", deck) }, null, null)
                    }
                }
            }
        }
        if (hechas == 0 && tarjetas.isNotEmpty()) throw Fallo("AnkiDroid no admitió las tarjetas")
        return hechas
    }

    /** El id del mazo de ese nombre, creándolo si no está. */
    private fun mazo(context: Context, nombre: String): Long {
        val r = context.contentResolver
        r.query(MAZOS, arrayOf("deck_id", "deck_name"), null, null, null)?.use { c ->
            val id = c.getColumnIndex("deck_id"); val n = c.getColumnIndex("deck_name")
            while (c.moveToNext()) if (c.getString(n) == nombre) return c.getLong(id)
        } ?: throw Fallo("AnkiDroid no contesta (¿su API está apagada?)")
        val u = r.insert(MAZOS, ContentValues().apply { put("deck_name", nombre) })
            ?: throw Fallo("AnkiDroid no creó el mazo")
        return u.lastPathSegment?.toLongOrNull() ?: throw Fallo("AnkiDroid no dio el mazo")
    }

    /** El id del tipo de nota [MODELO], creándolo la primera vez (`addNewCustomModel`). */
    private fun modelo(context: Context, mazo: Long): Long {
        val r = context.contentResolver
        r.query(MODELOS, arrayOf("_id", "name"), null, null, null)?.use { c ->
            val id = c.getColumnIndex("_id"); val n = c.getColumnIndex("name")
            while (c.moveToNext()) if (c.getString(n) == MODELO) return c.getLong(id)
        }
        val valores = ContentValues().apply {
            put("name", MODELO)
            put("field_names", CAMPOS.joinToString(SEP))
            put("num_cards", 1)
            put("css", ".card { font-family: sans-serif; font-size: 20px; text-align: center; } img { max-width: 100%; }")
            put("deck_id", mazo)
            put("sort_field_index", 0)
        }
        val u = r.insert(MODELOS, valores) ?: throw Fallo("AnkiDroid no creó el tipo de nota")
        r.update(Uri.withAppendedPath(Uri.withAppendedPath(u, "templates"), "0"), ContentValues().apply {
            put("name", "Tarjeta 1")
            put("question_format", "{{Frente}}")
            put("answer_format", "{{FrontSide}}<hr id=answer>{{Reverso}}")
        }, null, null)
        return u.lastPathSegment?.toLongOrNull() ?: throw Fallo("AnkiDroid no dio el tipo de nota")
    }

    /**
     * Una imagen a los medios de AnkiDroid: se le pasa por `FileProvider` una copia en
     * `cache/share/anki/` (con permiso de leerla) y devuelve el nombre con que la guardó.
     */
    private fun medio(context: Context, i: Tarjetas.Imagen): String? = runCatching {
        val copia = copiaCompartible(context, i) ?: return null
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", copia)
        context.grantUriPermission(PAQUETE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val valores = ContentValues().apply {
            put("file_uri", uri.toString())
            put("preferred_name", i.nombre.substringBeforeLast('.'))
        }
        val hecho = context.contentResolver.insert(MEDIOS, valores) ?: return null
        File(hecho.path ?: return null).name
    }.getOrNull()

    /** La carpeta de lo que se comparte con Anki (dentro de `cache/share`, que da el `FileProvider`). */
    fun carpeta(context: Context): File = File(context.cacheDir, "share/anki").apply { mkdirs() }

    /** Una copia de la imagen con su nombre de Anki, para dársela a otra app. */
    fun copiaCompartible(context: Context, i: Tarjetas.Imagen): File? = runCatching {
        val origen = File(i.ruta).takeIf { it.isFile } ?: return null
        File(carpeta(context), i.nombre).also { origen.copyTo(it, overwrite = true) }
    }.getOrNull()
}
