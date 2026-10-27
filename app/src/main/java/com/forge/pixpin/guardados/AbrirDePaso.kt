package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.forge.pixpin.guardados.ModoDePaso.Visor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * **Una pantalla que abre visores de paso** (27-oct-2026). Los visores se abren con su `abrir` de
 * siempre —el de cada uno sabe qué extras lleva— y esta pantalla, mientras [conPaso] dura, retoca
 * cada intento al salir ([AbrirDePaso.decorar]): su propia tarea, sin «de qué chat se vino» y con
 * las señas del modo de paso. Así ningún visor cambia su `abrir` para esto.
 *
 * Tiene que ser una **pantalla**: con un `Context` cualquiera, [com.forge.pixpin.Volver.abrirEnLaApp]
 * pondría la lista de chats debajo, que es justo lo que este modo no quiere.
 */
abstract class LanzadorDePaso : ComponentActivity() {
    private var decorando: AbrirDePaso.Estado? = null

    /** Abre lo que [abrir] abra en modo de paso con [estado]. */
    inline fun <T> conPaso(estado: AbrirDePaso.Estado, abrir: () -> T): T {
        empezar(estado)
        try { return abrir() } finally { acabar() }
    }

    fun empezar(estado: AbrirDePaso.Estado) { decorando = estado }
    fun acabar() { decorando = null }

    override fun startActivity(intent: Intent, options: Bundle?) {
        decorando?.let { AbrirDePaso.decorar(this, intent, it) }
        super.startActivity(intent, options)
    }
}

/**
 * **Abrir de paso, y guardar si se quiere** (27-oct-2026). Lo de Android del [ModoDePaso]:
 * traer el archivo, decidir su visor y abrirlo, la limpieza, y el «Guardar» que lo mete en un chat
 * con lo anotado.
 */
object AbrirDePaso {
    private const val EXTRA_SESION = "paso_sesion"
    private const val EXTRA_RUTA = "paso_ruta"
    private const val EXTRA_NOMBRE = "paso_nombre"
    private const val EXTRA_MIME = "paso_mime"
    /** El mensaje en que quedó guardado: con él, el visor ya es el de siempre y el botón dice «Guardado ✓». */
    private const val EXTRA_GUARDADO = "paso_guardado"

    /**
     * **Las señas del modo de paso** que lleva el visor. [ruta] es el archivo que se ve: el temporal
     * de la sesión o, ya guardado, el del chat. [guardado], el mensaje si ya se guardó.
     */
    data class Estado(val sesion: String, val ruta: String, val nombre: String, val mime: String?, val guardado: String? = null) {
        val uid: String get() = ModoDePaso.uidDeLaSesion(sesion)
    }

    /** Las señas que trae [intent], o null si no es de paso. */
    fun de(intent: Intent?): Estado? {
        val sesion = intent?.getStringExtra(EXTRA_SESION)?.takeIf { ModoDePaso.esSesion(it) } ?: return null
        val ruta = intent.getStringExtra(EXTRA_RUTA) ?: return null
        return Estado(sesion, ruta, intent.getStringExtra(EXTRA_NOMBRE).orEmpty().ifBlank { File(ruta).name }, intent.getStringExtra(EXTRA_MIME), intent.getStringExtra(EXTRA_GUARDADO))
    }

    private fun poner(i: Intent, e: Estado) {
        i.putExtra(EXTRA_SESION, e.sesion).putExtra(EXTRA_RUTA, e.ruta).putExtra(EXTRA_NOMBRE, e.nombre).putExtra(EXTRA_MIME, e.mime)
        if (e.guardado != null) i.putExtra(EXTRA_GUARDADO, e.guardado) else i.removeExtra(EXTRA_GUARDADO)
    }

    /**
     * **Cómo sale cada visor de paso.** En su propia tarea y de raíz (`NEW_DOCUMENT` y
     * `MULTIPLE_TASK`, con una categoría de la sesión para que un visor con
     * `documentLaunchMode="intoExisting"` no se meta en la tarea del mismo visor abierto desde un
     * chat). Así atrás lo cierra y se vuelve a la aplicación que lo mandó, sin la lista de chats de
     * por medio (la excepción a [com.forge.pixpin.Volver]), y al cerrarse desaparece de Recientes.
     * Se le quitan las señas de «desde dónde» —incluida la «desde dentro» del lienzo, que lo
     * mandaría a la lista de chats al salir—.
     */
    fun decorar(c: Context, i: Intent, e: Estado) {
        if (i.component?.packageName != c.packageName) return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        i.addCategory("${c.packageName}.paso.${e.sesion}")
        i.removeExtra(com.forge.pixpin.EXTRA_DESDE_CHAT)
        i.removeExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO)
        i.removeExtra(com.forge.pixpin.motor.DrawEditorActivity.EXTRA_DESDE_DENTRO)
        poner(i, e)
    }

    // ------------------------------------------------------------ traer

    private val AZAR = SecureRandom()

    fun carpetaDe(c: Context, sesion: String) = File(File(c.cacheDir, ModoDePaso.CARPETA), sesion)

    /** El nombre que dice quien lo manda, o el final de la ruta. */
    fun nombreDe(c: Context, uri: Uri): String = runCatching {
        c.contentResolver.query(uri, null, null, null, null)?.use { fila ->
            val columna = fila.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (columna >= 0 && fila.moveToFirst()) fila.getString(columna).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("").ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }

    /**
     * **Copia [uri] a una sesión nueva** con su nombre de verdad (y, si no lo trae, la extensión de su
     * tipo, que es por lo que se elige el visor). La copia es lo único que se hace con él: el permiso
     * de leer la URI se va con la pantalla que la recibió. Null si no se pudo leer.
     */
    fun traer(c: Context, uri: Uri, nombreDado: String? = null, mimeDado: String? = null): Estado? {
        val mime = mimeDado ?: runCatching { c.contentResolver.getType(uri) }.getOrNull()
        val crudo = nombreDado?.takeIf { it.isNotBlank() } ?: nombreDe(c, uri)
        var nombre = crudo.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").takeLast(120).ifBlank { "archivo" }
        if ('.' !in nombre) ModoDePaso.extensionDe(null, mime).takeIf { it.isNotEmpty() }?.let { nombre = "$nombre.$it" }
        val sesion = ModoDePaso.sesionDe(AZAR.nextLong())
        val carpeta = carpetaDe(c, sesion).apply { mkdirs() }
        val destino = File(carpeta, nombre)
        val bien = runCatching {
            c.contentResolver.openInputStream(uri)?.use { entra -> destino.outputStream().use { entra.copyTo(it) } } != null
        }.getOrDefault(false)
        if (!bien || destino.length() == 0L) { carpeta.deleteRecursively(); return null }
        return Estado(sesion, destino.absolutePath, nombre, mime)
    }

    // ------------------------------------------------------------ abrir

    /**
     * **Abre [e] en su visor** desde [a]. Lo que hay que convertir antes (un libro, un Word sin el
     * .so) se convierte aquí, fuera del hilo que pinta, para que el visor salga decorado. Falso si
     * PixPin no tiene visor para esto: quien llama ofrece otra aplicación.
     *
     * Es la misma tabla que la del chat al tocar un adjunto (`MensajesActivity.abrir`). Con
     * [Estado.guardado], el visor recibe su mensaje como cuando se abre desde el chat.
     */
    suspend fun abrirEnSuVisor(a: LanzadorDePaso, e: Estado): Boolean {
        val ruta = e.ruta
        val nombre = e.nombre
        val mensaje = e.guardado
        // Sin extensión ni tipo que diga nada, se mira dentro: un texto sin nombre se ve como texto.
        val visor = ModoDePaso.visorPara(nombre, e.mime).let { v ->
            if (v != Visor.NINGUNO) v
            else withContext(Dispatchers.IO) { ModoDePaso.visorPara(nombre, e.mime, cabezaDe(ruta)) }
        }
        // Unos puntos de Civil 3D (un .txt, .csv o .xml) van al visor 3D, como en el chat.
        val deCivil = (visor == Visor.TEXTO || visor == Visor.HOJA || visor == Visor.NINGUNO) &&
            withContext(Dispatchers.IO) { runCatching { com.forge.pixpin.planos.LectorDePlanos.esDeCivil(ruta, nombre) }.getOrDefault(false) }
        if (deCivil) return a.conPaso(e) { com.forge.pixpin.planos.Visor3DActivity.abrir(a, ruta, nombre); true }
        return when (visor) {
            Visor.PDF -> a.conPaso(e) { com.forge.pixpin.pdf.LectorPdfActivity.abrir(a, ruta, nombre, mensaje = mensaje); true }
            Visor.OFFICE -> when {
                com.forge.pixpin.office.AbrirOffice.seMaqueta(nombre, ruta) ->
                    a.conPaso(e) { com.forge.pixpin.pdf.LectorPdfActivity.abrir(a, ruta, nombre, mensaje = mensaje); true }
                com.forge.pixpin.motor.DocxAHtml.esDocx(nombre) -> {
                    val pagina = withContext(Dispatchers.IO) {
                        runCatching {
                            val carpeta = File(carpetaDeLaVista(a, e), "vista").apply { mkdirs() }
                            File(carpeta, "pagina.html").also { it.writeText(com.forge.pixpin.motor.DocxAHtml.convertir(File(ruta), nombre.substringBeforeLast('.'))) }
                        }.getOrNull()
                    } ?: return false
                    a.conPaso(e) {
                        com.forge.pixpin.ui.VisorHtmlActivity.abrir(a, pagina.absolutePath, nombre, comparte = ruta, sinGuion = true, mensaje = mensaje, documento = true)
                        true
                    }
                }
                // Un PowerPoint sin el .so se volvería un proyecto: eso no es mirar de paso.
                else -> false
            }
            Visor.EPUB -> {
                val pagina = withContext(Dispatchers.IO) {
                    runCatching { com.forge.pixpin.motor.EpubAHtml.convertir(File(ruta), File(carpetaDeLaVista(a, e), "libro"), nombre.substringBeforeLast('.')) }.getOrNull()
                } ?: return false
                a.conPaso(e) {
                    com.forge.pixpin.ui.VisorHtmlActivity.abrir(a, pagina.absolutePath, nombre, comparte = ruta, sinGuion = true, mensaje = mensaje, enSuSitio = true, documento = true)
                    true
                }
            }
            Visor.HTML -> a.conPaso(e) { com.forge.pixpin.ui.VisorHtmlActivity.abrir(a, ruta, nombre, mensaje = mensaje); true }
            Visor.HOJA -> a.conPaso(e) { com.forge.pixpin.excel.ExcelActivity.abrir(a, ruta, nombre, mensaje = mensaje); true }
            Visor.PLANO -> a.conPaso(e) { com.forge.pixpin.planos.PlanoActivity.abrir(a, ruta, nombre); true }
            Visor.MODELO_3D -> a.conPaso(e) { com.forge.pixpin.planos.Visor3DActivity.abrir(a, ruta, nombre); true }
            Visor.VIDEO -> a.conPaso(e) { com.forge.pixpin.guardados.VisorDeVideoActivity.abrir(a, ruta, nombre); true }
            // **Un `.md` o un texto, por su ruta** (27-oct-2026). Iba con el texto entero dentro del
            // intento, y un archivo de unos cientos de KB pasaba del límite de Android para un intento
            // (`TransactionTooLargeException`): `startActivity` fallaba callado, esta pantalla se
            // cerraba y no se abría nada. Ahora el editor lee el archivo él mismo, y el texto llano
            // se ve tal cual, sin pasar por el Markdown. Ver [FormatosDeTexto].
            Visor.MARKDOWN, Visor.TEXTO -> a.conPaso(e) {
                com.forge.pixpin.ui.MarkdownEditorActivity.abrirArchivo(a, ruta, nombre, idDeLaNota(e), llano = visor == Visor.TEXTO)
            }
            // **Una foto, en el lienzo**, como en el chat: se anota encima. De paso, en un dibujo de
            // la sesión ([ModoDePaso.dibujoDeLaFoto]); guardada, en el de su mensaje.
            Visor.IMAGEN -> {
                val dibujo = mensaje?.let { ModoDePaso.dibujoDeLaFotoGuardada(it) } ?: ModoDePaso.dibujoDeLaFoto(e.sesion)
                val ya = File(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(a, dibujo)).exists()
                a.conPaso(e) {
                    com.forge.pixpin.motor.DrawEditorActivity.abrir(a, dibujo, com.forge.pixpin.motor.ExcalidrawStore.rutaDe(a, dibujo), if (ya) null else ruta)
                    true
                }
            }
            Visor.NINGUNO -> false
        }
    }

    /**
     * Dónde se deja lo convertido para verlo (la página de un Word o de un libro): en la sesión, que
     * se borra con ella; ya guardado, aparte, porque el visor de paso que se cierra al guardar se
     * lleva la sesión entera y dejaría al nuevo sin su página.
     */
    private fun carpetaDeLaVista(c: Context, e: Estado): File =
        e.guardado?.let { File(File(c.cacheDir, "visor-paso-guardado"), it.take(36)) } ?: carpetaDe(c, e.sesion)

    /** Los primeros bytes de [ruta], para mirar qué es lo que llega sin extensión. */
    private fun cabezaDe(ruta: String): ByteArray? = runCatching {
        File(ruta).inputStream().use { entra ->
            val b = ByteArray(8192)
            var n = 0
            while (n < b.size) { val r = entra.read(b, n, b.size - n); if (r < 0) break; n += r }
            b.copyOf(n)
        }
    }.getOrNull()

    /** La nota con que el editor de Markdown abre un `.md` de paso (o el del mensaje, ya guardado). */
    fun idDeLaNota(e: Estado) = e.guardado?.let { "md-$it" } ?: "md-${e.uid}"

    // ------------------------------------------------------------ fuera y compartir

    /** **Abrir con…**: la lista de las otras aplicaciones que lo abren (sin PixPin). Ver [com.forge.pixpin.ui.AbrirCon]. */
    fun abrirCon(c: Context, e: Estado) = com.forge.pixpin.ui.AbrirCon.abrir(c, File(e.ruta), e.mime)

    /**
     * **«Por Wi-Fi a mis aparatos»** en la hoja de compartir del visor, si no lo trae ya: el envío a
     * los aparatos del grupo ([com.forge.pixpin.sincro.AlGrupo], en la pantalla de Enviar). Va el
     * archivo tal cual; lo anotado sale con los otros formatos de la hoja.
     */
    fun conWifi(c: Context, compartible: com.forge.pixpin.ui.Compartible, e: Estado?): com.forge.pixpin.ui.Compartible {
        if (e == null || compartible.formatos.any { it.id == "wifi" || it.id == ID_WIFI }) return compartible
        val wifi = com.forge.pixpin.ui.Compartible.Formato(
            ID_WIFI, Icons.Filled.Wifi, "Por Wi-Fi a mis aparatos", com.forge.pixpin.ui.Compartible.NINGUNA,
            accion = { com.forge.pixpin.sincro.EnviarActivity.enviarArchivo(c, e.ruta, e.nombre) }
        )
        return com.forge.pixpin.ui.Compartible(compartible.titulo, compartible.paginas, compartible.formatos + wifi, compartible.marcadas, compartible.contenido)
    }

    private const val ID_WIFI = "wifi-aparatos"

    // ------------------------------------------------------------ limpiar

    /** Las sesiones que se están guardando: al cerrarse su visor no se borra lo que se lleva el chat. */
    private val guardandose = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * **Que el visor de paso se lleve lo suyo al cerrarse.** Se llama en su `onCreate`; si no es de
     * paso no hace nada. Al cerrarse de verdad (no al girar) borra la sesión: el archivo copiado y
     * lo anotado sin guardar. Devuelve las señas, o null.
     */
    fun vigilar(actividad: ComponentActivity): Estado? {
        val e = de(actividad.intent) ?: return null
        if (e.guardado != null) return e
        actividad.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (actividad.isFinishing && !actividad.isChangingConfigurations) {
                    val c = actividad.applicationContext
                    Thread { limpiar(c, e.sesion) }.start()
                }
            }
        })
        return e
    }

    /** Borra la sesión [sesion]: su carpeta y lo de `pins/draw/` que aún lleve su código. */
    fun limpiar(c: Context, sesion: String) {
        runCatching { carpetaDe(c, sesion).deleteRecursively() }
        val dibujos = File(c.filesDir, com.forge.pixpin.sincro.AnotacionesDelAdjunto.CARPETA)
        val conservar = sesion in guardandose
        dibujos.list()?.filter { ModoDePaso.esDeLaSesion(it, sesion) }?.forEach { n ->
            val f = File(dibujos, n)
            // Las fotos que se metieron en el dibujo de una foto de paso, si no se guardó.
            if (!conservar && n == ModoDePaso.dibujoDeLaFoto(sesion) + ".excalidraw.gz") {
                com.forge.pixpin.motor.ExcalidrawStore.cargar(f.absolutePath)?.files?.values?.forEach { sf ->
                    val img = File(sf.path)
                    if (img.parentFile?.parentFile == dibujos) img.delete()
                }
            }
            f.delete()
        }
    }

    /**
     * **Al arrancar la aplicación**: lo de las sesiones que quedaron (un cierre a la fuerza no avisa
     * al visor). Solo las de hace más de una hora: un visor de paso que Android reconstruye desde
     * Recientes tras matar el proceso aún necesita su archivo. Las fotos metidas en un dibujo de paso
     * huérfano no se tocan: no se sabe si un «Guardar» a medias las llevó al chat.
     */
    fun limpiarAlArrancar(c: Context) {
        Thread {
            runCatching {
                val raiz = File(c.cacheDir, ModoDePaso.CARPETA)
                val hace = System.currentTimeMillis() - 60 * 60 * 1000L
                raiz.listFiles()?.forEach { if (it.lastModified() < hace) it.deleteRecursively() }
                val vivas = raiz.list()?.toSet().orEmpty()
                val dibujos = File(c.filesDir, com.forge.pixpin.sincro.AnotacionesDelAdjunto.CARPETA)
                dibujos.list()?.forEach { n ->
                    val s = ModoDePaso.sesionDelNombre(n) ?: return@forEach
                    if (s !in vivas) File(dibujos, n).delete()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ guardar

    /**
     * **Guardar lo abierto de paso** en el chat [proyecto] (null = Mensajes guardados), **como si se
     * hubiera compartido a PixPin** (lo mismo que hace [GuardarCompartidoActivity]: copia al almacén
     * —un PDF aligerado, una foto enderezada— y un mensaje nuevo), y **con lo anotado**: cada
     * `anot-paso<sesión>…` pasa a `anot-<código del mensaje>…` ([ModoDePaso.renombres]) y el dibujo de
     * una foto, al de su mensaje. Desde ahí viaja como lo de cualquier adjunto.
     *
     * Devuelve las señas ya guardadas (con el archivo del chat y el mensaje), o null si no se pudo.
     * Fuera del hilo que pinta.
     */
    fun guardar(c: Context, e: Estado, proyecto: String?): Estado? {
        if (e.guardado != null) return e
        guardandose += e.sesion
        val temporal = File(e.ruta)
        if (!temporal.isFile) return null
        // Un `.md` o un `.txt` editado en el editor: lo escrito, no lo que llegó.
        com.forge.pixpin.motormd.TextoStore.leer(idDeLaNota(e))?.takeIf { it.isNotEmpty() }?.let { runCatching { temporal.writeText(it) } }
        val almacen = MensajesStore(c)
        val ruta = almacen.copiarAdjunto(temporal, e.nombre, ModoDePaso.extensionDe(null, e.mime).ifBlank { null }) ?: return null
        val id = UUID.randomUUID().toString()
        val esImagen = ModoDePaso.visorPara(e.nombre, e.mime) == Visor.IMAGEN
        val dibujoDePaso = File(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(c, ModoDePaso.dibujoDeLaFoto(e.sesion)))
        val m = Mensaje(
            id = id,
            cuando = System.currentTimeMillis(),
            clase = if (esImagen) Clase.IMAGEN else Clase.ARCHIVO,
            ruta = ruta,
            nombre = e.nombre,
            bytes = File(ruta).length(),
            // La foto anotada lleva su dibujo, como la que se abrió desde el chat.
            referencia = if (esImagen && dibujoDePaso.isFile) ModoDePaso.dibujoDeLaFotoGuardada(id) else null,
            proyecto = proyecto
        )
        // Lo anotado, antes del mensaje: así la sincronización no ve nunca el mensaje sin su tinta.
        val dibujos = File(c.filesDir, com.forge.pixpin.sincro.AnotacionesDelAdjunto.CARPETA)
        val nombres = dibujos.list()?.toList().orEmpty()
        for ((de, a) in ModoDePaso.renombres(nombres, e.sesion, com.forge.pixpin.sincro.Codigos.unico(m), id)) {
            val origen = File(dibujos, de)
            val destino = File(dibujos, a)
            if (!origen.renameTo(destino)) runCatching { origen.copyTo(destino, overwrite = true); origen.delete() }
        }
        almacen.anadir(m)
        return e.copy(ruta = ruta, guardado = id)
    }

    /** Lo que se puede elegir en «Guardar → En un chat…»: los proyectos sin archivar. */
    fun chats(c: Context): List<Pair<String, String>> =
        (c.applicationContext as? com.forge.pixpin.PixPinApp)?.proyectos?.proyectos?.value.orEmpty()
            .filterNot { it.archivado }.map { it.id to it.nombre }

    /** Pone las señas de paso en un intento hacia [GuardarDePasoActivity]. */
    internal fun alGuardar(i: Intent, e: Estado): Intent { poner(i, e); return i }
}
