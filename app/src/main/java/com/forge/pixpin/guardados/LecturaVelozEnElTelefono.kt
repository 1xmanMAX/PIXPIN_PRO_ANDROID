package com.forge.pixpin.guardados

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.forge.pixpin.motor.Lectura
import com.forge.pixpin.motor.Marcas
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import com.forge.pixpin.sincro.Codigos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * **Lectura Veloz, en el teléfono** (27-oct-2026): lo que toca Android de [LecturaVeloz] —abrirla
 * con el documento, recibir su punto y guardarlo como el marcador verde de PixPin—.
 *
 * Qué se guarda y dónde:
 * - **El marcador**, donde siempre: `pins/draw/anot-<uid>.voz` (en un PDF, el `anot-<uid>-texto.voz`
 *   de leerlo como texto, y además el 🟢 de `anot-<uid>.marcas`, el del lector de hojas). Viajan.
 * - **En el teléfono** (preferencias `lectura_veloz`, por el código del mensaje): de qué documento es
 *   cada código que se le dio a Lectura Veloz (`<doc>:base`, `:tipo`, `:paginas`) y lo último que
 *   ella mandó ([LecturaVeloz.Ultimo], `<doc>:ultimo`), que es la chapa y la hora de su punto.
 * - **El mapa de párrafos** del visor ([LecturaVeloz.Mapa]), en `files/lectura-veloz/<base>.mapa`:
 *   no viaja, cada aparato numera sus párrafos.
 */
object LecturaVelozEnElTelefono {
    private const val PREFS = "lectura_veloz"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _cambios = MutableStateFlow(0)
    /** Sube cada vez que llega un punto: la chapa del chat se vuelve a mirar. */
    val cambios: StateFlow<Int> = _cambios

    /** Un documento que se puede mandar: su código, el archivo, cómo se llama y dónde va su verde. */
    data class Documento(val doc: String, val archivo: File, val nombre: String, val base: String, val tipo: String, val paginas: Int = 0) {
        val esPdf get() = tipo == LecturaVeloz.TIPO_PDF
    }

    /**
     * **Un adjunto del chat**: con el código de su mensaje ([uid], o el que tenga la ruta). Null si no
     * es de ningún mensaje o si Lectura Veloz no lo lee. Toca disco: fuera del hilo que pinta.
     */
    fun deArchivo(c: Context, archivo: File, nombre: String?, uid: String? = null, paginas: Int = 0): Documento? {
        val doc = uid ?: AnotacionesDelAdjunto.uidDe(c.filesDir, archivo.absolutePath) ?: return null
        if (!LecturaVeloz.docValido(doc) || !LecturaVeloz.admite(nombre, archivo.name)) return null
        val tipo = LecturaVeloz.tipoDe(nombre, archivo.name)
        return Documento(
            doc, archivo, LecturaVeloz.nombreConExtension(nombre, archivo.name),
            AnotacionesDelAdjunto.delDocumento(doc, archivo), tipo, paginas
        )
    }

    /** Un mensaje del chat con un archivo que Lectura Veloz lee. */
    fun deMensaje(c: Context, m: Mensaje): Documento? {
        if (m.clase != Clase.ARCHIVO) return null
        val ruta = m.ruta ?: return null
        if (!LecturaVeloz.admite(m.nombre, ruta)) return null
        return deArchivo(c, File(ruta), m.nombre, Codigos.unico(m))
    }

    /** Si el menú del mensaje lleva «Lectura veloz»: un archivo que ella lee. Sin disco. */
    fun sePuede(m: Mensaje): Boolean = m.clase == Clase.ARCHIVO && m.ruta != null && LecturaVeloz.admite(m.nombre, m.ruta)

    /**
     * **Una nota en Markdown**: se escribe como `.md` en la caché que se comparte y su verde va con
     * el código de la nota (`anot-<uid>.voz`). El mapa de párrafos sale del propio texto.
     */
    fun deNota(c: Context, uid: String, titulo: String, texto: String): Documento? {
        if (!LecturaVeloz.docValido(uid)) return null
        val nombre = LecturaVeloz.nombreConExtension(titulo.replace(Regex("""[^\p{L}\p{N} ()._-]"""), "_").take(80).ifBlank { "Nota" }, "nota.md")
        val dir = File(c.cacheDir, "share/lectura-veloz/$uid").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val archivo = File(dir, nombre)
        return runCatching {
            archivo.writeText(texto)
            val base = AnotacionesDelAdjunto.delPdf(uid)
            LecturaVeloz.Mapa.deTextoPlano(texto)?.let { guardarMapa(c, base, it) }
            Documento(uid, archivo, nombre, base, LecturaVeloz.TIPO_TEXTO)
        }.getOrNull()
    }

    // ---- Lo guardado en el teléfono ----

    fun ultimo(c: Context, doc: String): LecturaVeloz.Ultimo? =
        LecturaVeloz.Ultimo.deTexto(prefs(c).getString("$doc:ultimo", null))

    /** El porcentaje de la chapa, o null si este documento nunca se leyó en Lectura Veloz. */
    fun porcentaje(c: Context, doc: String?): Int? =
        doc?.let { ultimo(c, it) }?.let { LecturaVeloz.porcentaje(it.fraccion) }

    private fun archivoDelMapa(c: Context, base: String) = File(c.filesDir, "lectura-veloz/$base.mapa")

    fun mapaDe(c: Context, base: String): LecturaVeloz.Mapa? =
        LecturaVeloz.Mapa.deTexto(AnotacionesDelAdjunto.leer(archivoDelMapa(c, base)))

    /** Lo deja el visor al numerar los párrafos para la voz. */
    fun guardarMapa(c: Context, base: String, mapa: LecturaVeloz.Mapa) {
        val f = archivoDelMapa(c, base)
        val texto = mapa.aTexto()
        if (AnotacionesDelAdjunto.leer(f) != texto) AnotacionesDelAdjunto.escribir(f, texto)
    }

    internal fun apuntar(c: Context, d: Documento) {
        prefs(c).edit()
            .putString("${d.doc}:base", d.base)
            .putString("${d.doc}:tipo", d.tipo)
            .putInt("${d.doc}:paginas", d.paginas)
            .apply()
    }

    // ---- El marcador de PixPin ----

    private fun archivoDeVoz(c: Context, base: String) = AnotacionesDelAdjunto.voz(c.filesDir, base)
    private fun archivoDeMarcasDelPdf(c: Context, doc: String) = AnotacionesDelAdjunto.marcas(c.filesDir, AnotacionesDelAdjunto.delPdf(doc))

    private class Marcador(val voz: String?, val horaVoz: Long, val verde: com.forge.pixpin.motor.Marca?, val horaVerde: Long) {
        val hora get() = maxOf(horaVoz, horaVerde)
    }

    private fun marcador(c: Context, doc: String, base: String, esPdf: Boolean, ultimo: LecturaVeloz.Ultimo?): Marcador {
        val f = archivoDeVoz(c, base)
        val voz = AnotacionesDelAdjunto.leer(f)?.trim()?.takeIf { Lectura.vozDeTexto(it) != null }
        val horaVoz = LecturaVeloz.horaDeLaVoz(voz, f.lastModified(), ultimo)
        val verde = if (!esPdf) null
        else Marcas.deTexto(AnotacionesDelAdjunto.leer(archivoDeMarcasDelPdf(c, doc))).firstOrNull { it.emoji == Lectura.EMOJI_DE_VOZ }
        val horaVerde = LecturaVeloz.horaDeLaMarca(verde?.y, verde?.id ?: 0L, ultimo)
        return Marcador(voz, horaVoz, verde, horaVerde)
    }

    // ---- Abrir ----

    private fun enlaceDePrueba() = Intent(Intent.ACTION_VIEW, Uri.parse("${LecturaVeloz.ESQUEMA}://${LecturaVeloz.HOST}/abrir")).setPackage(LecturaVeloz.PAQUETE)

    /** Si sabe abrir documentos de PixPin (el enlace `lecturaveloz://pixpin`). Necesita el `<queries>`. */
    fun sabeAbrir(pm: PackageManager): Boolean = runCatching { enlaceDePrueba().resolveActivity(pm) != null }.getOrDefault(false)

    fun estaInstalada(pm: PackageManager): Boolean =
        sabeAbrir(pm) || runCatching { pm.getLaunchIntentForPackage(LecturaVeloz.PAQUETE) != null }.getOrDefault(false)

    /** Las hojas de un PDF, si no se sabían: para la fracción del documento. */
    private fun hojasDelPdf(archivo: File): Int = runCatching {
        android.os.ParcelFileDescriptor.open(archivo, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { it.pageCount }
        }
    }.getOrDefault(0)

    /**
     * **La petición**: el enlace con el documento y el punto ([LecturaVeloz.posicionParaAbrir]), y el
     * permiso de lectura de su `content://` para Lectura Veloz —va dentro del enlace y no en los datos
     * del intent, así que se da a mano además de con el `ClipData`—. Apunta el documento en el
     * teléfono para reconocer luego su punto. Toca disco.
     */
    fun peticion(c: Context, d0: Documento): Intent {
        val d = if (d0.esPdf && d0.paginas <= 0) d0.copy(paginas = hojasDelPdf(d0.archivo)) else d0
        apuntar(c, d)
        val uri = com.forge.pixpin.ui.AbrirCon.uriDe(c, d.archivo)
        val ultimo = ultimo(c, d.doc)
        val m = marcador(c, d.doc, d.base, d.esPdf, ultimo)
        val pos = LecturaVeloz.posicionParaAbrir(
            m.voz?.let { Lectura.vozDeTexto(it) }, m.horaVoz, m.verde?.y, m.horaVerde, ultimo,
            mapaDe(c, d.base), d.esPdf, d.paginas
        )
        val url = LecturaVeloz.enlace(d.doc, d.nombre, uri.toString(), d.tipo, pos, d.paginas)
        runCatching { c.grantUriPermission(LecturaVeloz.PAQUETE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(LecturaVeloz.PAQUETE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri(d.nombre, uri) }
            .also { if (c !is Activity) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /**
     * **«Lectura veloz»**: comprueba, prepara fuera del hilo que pinta y la abre. [alNoPoder] recibe
     * por qué no (en el hilo principal), para el aviso de cada pantalla. [documento] se calcula
     * también fuera (busca el código del mensaje en disco).
     */
    fun abrir(a: Activity, documento: () -> Documento?, alNoPoder: (LecturaVeloz.AlAbrir) -> Unit) {
        val pm = a.packageManager
        val principal = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val d = runCatching { documento() }.getOrNull()
            val que = LecturaVeloz.queHacerAlAbrir(estaInstalada(pm), sabeAbrir(pm), d?.doc, d?.nombre, d?.archivo?.name)
            val i = if (que == LecturaVeloz.AlAbrir.ABRIR && d != null && d.archivo.isFile) runCatching { peticion(a, d) }.getOrNull() else null
            principal.post {
                when {
                    que != LecturaVeloz.AlAbrir.ABRIR -> alNoPoder(que)
                    i == null -> alNoPoder(LecturaVeloz.AlAbrir.NO_SE_PUEDE)
                    else -> try {
                        a.startActivity(i)
                    } catch (e: android.content.ActivityNotFoundException) {
                        alNoPoder(LecturaVeloz.AlAbrir.FALTA_LA_APP)
                    } catch (e: SecurityException) {
                        alNoPoder(LecturaVeloz.AlAbrir.FALTA_LA_APP)
                    }
                }
            }
        }.start()
    }

    /** El aviso corto de los visores cuando no se puede (el chat lleva su cuadro). */
    fun avisoCorto(que: LecturaVeloz.AlAbrir): String = when (que) {
        LecturaVeloz.AlAbrir.FALTA_LA_APP -> "Hace falta instalar «Lectura Veloz» en este teléfono"
        LecturaVeloz.AlAbrir.APP_ANTIGUA -> "Pon al día «Lectura Veloz»: esta versión aún no abre documentos de PixPin"
        LecturaVeloz.AlAbrir.NO_SE_PUEDE -> "Este documento no se puede leer en Lectura Veloz"
        LecturaVeloz.AlAbrir.ABRIR -> ""
    }

    // ---- La vuelta ----

    /** Un documento que no se apuntó al abrirlo (se borraron los datos): se busca en el chat. */
    private fun buscarEnElChat(c: Context, doc: String): Documento? = runCatching {
        MensajesStore(c).leer().firstOrNull { sePuede(it) && Codigos.unico(it) == doc }?.let { deMensaje(c, it) }
    }.getOrNull()

    /**
     * **Lo que manda Lectura Veloz** ([LecturaVelozReceiver]): se comprueba, se guarda lo último suyo
     * (la chapa) y, si es más nuevo que el marcador de PixPin, se mueve el verde. Devuelve qué se
     * hizo, o null si no se reconoció. Toca disco.
     */
    fun recibir(c: Context, intent: Intent, ahora: Long = System.currentTimeMillis()): LecturaVeloz.AlLlegar? {
        if (intent.action != LecturaVeloz.ACCION_DE_VUELTA) return null
        val p = runCatching {
            LecturaVeloz.progresoDe(
                intent.getStringExtra("doc"), intent.getStringExtra("tipo"),
                intent.getIntExtra("pagina", 0), intent.getFloatExtra("frac", 0f), intent.getFloatExtra("fraccion", 0f),
                intent.getLongExtra("t", 0L), intent.getIntExtra("palabra", 0), intent.getIntExtra("total", 0), ahora
            )
        }.getOrNull() ?: return null
        val pr = prefs(c)
        val base: String
        val tipo: String
        val paginas: Int
        val apuntada = pr.getString("${p.doc}:base", null)
        if (apuntada != null) {
            base = apuntada
            tipo = pr.getString("${p.doc}:tipo", null) ?: LecturaVeloz.TIPO_TEXTO
            paginas = pr.getInt("${p.doc}:paginas", 0)
        } else {
            val d = buscarEnElChat(c, p.doc) ?: return null
            apuntar(c, d)
            base = d.base; tipo = d.tipo; paginas = d.paginas
        }
        // El código es parte de un nombre de archivo: nunca otra carpeta.
        if (base.contains('/') || base.contains("..")) return null
        val esPdf = tipo == LecturaVeloz.TIPO_PDF
        val ultimo = ultimo(c, p.doc)
        val m = marcador(c, p.doc, base, esPdf, ultimo)
        val que = LecturaVeloz.alLlegar(p, ultimo, m.hora)
        when (que) {
            LecturaVeloz.AlLlegar.IGNORAR -> return que
            LecturaVeloz.AlLlegar.SOLO_LA_CHAPA ->
                guardarUltimo(c, p.doc, LecturaVeloz.Ultimo(p.t, p.pagina, p.frac, p.fraccion))
            LecturaVeloz.AlLlegar.ESCRIBIR -> {
                val (parrafo, f) = LecturaVeloz.lvAVoz(p.posicion, mapaDe(c, base), esPdf)
                val voz = Lectura.vozATexto(parrafo, f)
                AnotacionesDelAdjunto.escribir(archivoDeVoz(c, base), voz)
                var marca: Double? = null
                if (esPdf) {
                    val archivo = archivoDeMarcasDelPdf(c, p.doc)
                    val sinVerde = Marcas.deTexto(AnotacionesDelAdjunto.leer(archivo)).filterNot { it.emoji == Lectura.EMOJI_DE_VOZ }
                    val y = LecturaVeloz.lvAMarca(p.posicion, paginas)
                    val con = Marcas.con(sinVerde, 0.5, y, Lectura.EMOJI_DE_VOZ, p.t)
                    val texto = Marcas.aTexto(con)
                    AnotacionesDelAdjunto.escribir(archivo, texto)
                    // Como queda escrito (con dos decimales), para reconocerlo luego.
                    marca = Marcas.deTexto(texto).firstOrNull { it.emoji == Lectura.EMOJI_DE_VOZ }?.y
                }
                guardarUltimo(c, p.doc, LecturaVeloz.Ultimo(p.t, p.pagina, p.frac, p.fraccion, escrito = true, voz = voz, marca = marca))
            }
        }
        _cambios.value = _cambios.value + 1
        return que
    }

    private fun guardarUltimo(c: Context, doc: String, u: LecturaVeloz.Ultimo) {
        prefs(c).edit().putString("$doc:ultimo", u.aTexto()).commit()
    }
}
