package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.dibujoDeLaFoto
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * # Versiones vinculadas en los chats compartidos (27-oct-2026)
 *
 * El usuario: «en los chats compartidos puede pasar que yo edito lo que alguien me pasó y esa
 * persona edita lo que yo le pasé, y al sincronizar hay incongruencias. Mantener dos versiones, la
 * no tocada y la tocada: si quiero tocar lo que la otra persona hizo, que se cree un nuevo archivo,
 * un duplicado vinculado al original, como si contestaras un mensaje».
 *
 * **Por qué no basta con fusionar.** Entre mis aparatos la fusión de tres vías ([Fusion]) junta lo
 * que hice en el teléfono y en el PC porque los dos cambios son **míos** y van hacia lo mismo. Entre
 * dos personas no: si Ana mueve la planta de su lienzo y yo la borro en el mío, juntar los dos no da
 * «lo de Ana» ni «lo mío», da una tercera cosa que nadie dibujó. Y lo anotado sobre un PDF ajeno
 * acababa encima de la tinta de su autor, sin forma de separar de quién era cada raya.
 *
 * ## La regla
 *
 * Solo en un **chat compartido** con otras personas (`compartidos.json`); en uno mío, y entre mis
 * aparatos, todo sigue como siempre.
 *
 * 1. **Lo mío se edita en su sitio.** Mío es lo que escribió cualquiera de mis aparatos
 *    ([LadosDelChat.quien], la regla de [EntrePersonas]).
 * 2. **Lo ajeno no se toca nunca.** Al primer cambio sobre un mensaje de otra persona se hace una
 *    **copia mía** ([nueva]): id, código único, rutas y dibujos nuevos, mi aparato, y dos campos que
 *    la atan al original: [Mensaje.versionDe] (el código único del original) y [Mensaje.versionN]
 *    (qué versión es: 1, 2…). Además **contesta al original** ([Mensaje.respondeA]), así que en
 *    cualquier PixPin —también en uno que no sepa de versiones— sale debajo, citándolo.
 * 3. **Lo que se siga cambiando va a la copia**, que ya es mía: regla 1.
 * 4. Si otra persona toca lo mío, es ella la que hace su copia: a mí me llega como un mensaje nuevo
 *    que contesta al mío. El mío no cambia.
 *
 * Volver a abrir el original y cambiarlo otra vez hace **otra** versión (la 2): el original siempre
 * se abre tal cual es, y mezclar lo nuevo con la versión anterior sería otra vez juntar dos cosas.
 * Para seguir con la mía, se abre la mía.
 *
 * ## Qué se versiona ([como])
 *
 * - **Lienzos, páginas de PDF y fotos anotadas** (el dibujo de `pins/draw`), y **lo anotado sobre un
 *   documento** (PDF, Word, PowerPoint, libro, página web, hoja de cálculo: los `anot-<uid>*` de
 *   [AnotacionesDelAdjunto]): **borrador** ([Como.BORRADOR]). Al abrir se copia todo a nombres
 *   nuevos y el editor trabaja sobre la copia; el mensaje solo nace si al volver algo cambió
 *   ([cerrar]). Abrir para mirar no deja nada.
 * - **Mini-aplicaciones** (gastos, una carpeta de páginas…) que se editan por el id de su mensaje:
 *   **provisional** ([Como.PROVISIONAL]). La copia entra en el chat al abrir, porque el editor la
 *   busca ahí, y se quita al volver si no cambió.
 * - **Notas y cambios de un toque** (describir un archivo, tocar algo dentro de la burbuja de una
 *   mini-app): **al momento** ([Como.AL_MOMENTO]), con el cambio ya hecho.
 * - **No** se versionan las **listas de tareas** —tachar en una lista compartida es lo que se quiere,
 *   y la fusión las junta tarea a tarea—, las **tablas del chat** (hoy solo se miran) ni lo que no
 *   se edita aquí (audios, vídeos, archivos que abre otra aplicación, croquis, proyectos).
 *
 * ## Lo anotado sobre un documento ajeno
 *
 * Mi tinta sobre el PDF de Ana va en **mi versión del PDF**: un mensaje mío con una copia del
 * archivo y lo que Ana ya tenía anotado copiado a mi código (`anot-<mi uid>*`). Así lo anotado
 * sigue la misma regla que todo lo demás —va con el código único de su mensaje— sin capas por
 * aparato que el PC tendría que aprender, y la tinta de cada uno se ve por separado: la de Ana en
 * el suyo, la mía en el mío. Lo que es de leer y no de editar (por dónde iba, el marcador verde de
 * la voz, el marco de la hoja) no cuenta como cambio ([huella]).
 *
 * Todo esto es sin Android: las pruebas lo ejercitan en dos carpetas que sincronizan de verdad
 * (`VersionesVinculadasDeVerdadTest`). Lo de pantalla está en `guardados/VersionesEnElChat.kt`.
 */
object VersionesVinculadas {

    /** Cómo se hace la versión de un mensaje ajeno. Ver la documentación del objeto. */
    enum class Como { BORRADOR, PROVISIONAL, AL_MOMENTO }

    /**
     * **Un borrador o una versión provisional que se está editando**, apuntado en disco para que
     * sobreviva a que Android cierre la aplicación con el editor abierto.
     */
    @Serializable
    data class EnCurso(
        /** La versión preparada: con sus rutas, su dibujo y su código nuevos. */
        val version: Mensaje,
        /** El id del original. */
        val original: String,
        /** La [huella] de la versión al abrir, que es la del original: si sigue igual, no se tocó. */
        val huella: String,
        /** Si ya está en el chat ([Como.PROVISIONAL]); si no, es un borrador. */
        val provisional: Boolean = false,
        /** Una foto que aún no tenía dibujo: la foto que pone el editor de fondo no es un cambio. */
        val sinFotos: Boolean = false,
        /** Cuándo se abrió. */
        val desde: Long = 0
    )

    /** Lo que hay que hacer en el chat al volver de los editores: añadir versiones y quitar provisionales que no se tocaron. */
    data class Cierre(val anadir: List<Mensaje>, val quitar: Set<String>) {
        val nada: Boolean get() = anadir.isEmpty() && quitar.isEmpty()
    }

    /**
     * Un borrador sin cambios se guarda por si el editor sigue abierto en otra tarea (los lienzos y
     * el lector de PDF se abren como documentos aparte y se puede volver al chat sin cerrarlos); a
     * las doce horas se tira.
     */
    const val CADUCA_MS = 12L * 60 * 60 * 1000

    private const val DIBUJOS = "pins/draw"
    private val COPIA = Regex("^\\d+_v\\d+_")

    /** Lo que se anota con el lector, el visor o las hojas: el resto de archivos se abre fuera y no se edita aquí. */
    private val DOCUMENTOS = setOf(
        "pdf", "doc", "docx", "odt", "rtf", "ppt", "pptx", "odp", "epub", "html", "htm",
        "xls", "xlsx", "xlsm", "ods", "csv", "tsv"
    )
    private val VIDEOS = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v")

    // ------------------------------------------------------------------ decidir

    /**
     * **Si [m] es de otra persona** en un chat compartido. [lados] es el chat visto desde aquí
     * ([EnElChat.lados]); nulo en un chat que no es compartido, y entonces nada es ajeno. Lo que no
     * lleva código, o lleva uno que no es de nadie de la lista (las respuestas del asistente), es mío.
     */
    fun esAjeno(m: Mensaje, lados: LadosDelChat?): Boolean =
        lados != null && lados.quien(EntrePersonas.aparatoCorto(m.aparato)) is Quien.Otro

    private fun extension(m: Mensaje): String =
        (m.ruta ?: m.nombre).substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** Cómo se versiona [m] si es ajeno, o null si se abre y se cambia como siempre. */
    fun como(m: Mensaje): Como? = when (m.clase) {
        Clase.DIBUJO, Clase.PAGINA -> if (m.referencia != null) Como.BORRADOR else null
        Clase.IMAGEN -> if (m.ruta != null && extension(m) !in VIDEOS) Como.BORRADOR else null
        Clase.ARCHIVO -> if (m.ruta != null && extension(m) in DOCUMENTOS) Como.BORRADOR else null
        Clase.MINIAPP -> when {
            com.forge.pixpin.mini.TodasLasTareas.esLista(m) -> null
            com.forge.pixpin.guardados.TablasDelChat.esTabla(m) -> null
            else -> Como.PROVISIONAL
        }
        Clase.NOTA -> Como.AL_MOMENTO
        else -> null
    }

    /** Si tocar [m] hace una versión en vez de cambiarlo: es ajeno y es de lo que se versiona. */
    fun seVersiona(m: Mensaje, lados: LadosDelChat?): Boolean = esAjeno(m, lados) && como(m) != null

    // ------------------------------------------------------------------ el vínculo

    /** Las versiones de [original] que hay en [lista], de la más vieja a la más nueva. */
    fun versionesDe(lista: List<Mensaje>, original: Mensaje): List<Mensaje> {
        val uid = Codigos.unico(original)
        return lista.filter { it.versionDe == uid && it.id != original.id }.sortedBy { it.cuando }
    }

    /** El original de la versión [v], si está en [lista]. */
    fun originalDe(lista: List<Mensaje>, v: Mensaje): Mensaje? {
        val uid = v.versionDe ?: return null
        return lista.firstOrNull { Codigos.unico(it) == uid }
    }

    /**
     * **La versión de [original], sin archivos**: otro mensaje, mío, que contesta al original y
     * dice de cuál es. Códigos nuevos (con los del original se tomaría por él al sincronizar y lo
     * pisaría, ver [Codigos.renovar]); el número en el chat lo pone quien la guarda ([conVersiones]).
     * Lo que es de este aparato o de la organización del chat (fijado, etiqueta, recordatorio,
     * buzón, unido a las hojas) no se copia.
     */
    fun nueva(original: Mensaje, id: String, uid: String, aparato: String?, ahora: Long, n: Int): Mensaje = original.copy(
        id = id,
        uid = uid,
        cuando = ahora,
        numero = 0,
        letra = null,
        aparato = aparato,
        origen = null,
        recibidoDe = null,
        respondeA = original.id,
        versionDe = Codigos.unico(original),
        versionN = n,
        fijado = false,
        emoji = null,
        enBuzon = false,
        recuerdaEn = null,
        unido = false,
        hojaDelTexto = null,
        // Una nota que abre una hoja de proyecto: la copia ya no es esa hoja.
        referencia = if (original.clase == Clase.NOTA) null else original.referencia
    )

    /** Los mensajes de [lista] con los de [cierre]: sin los quitados y con los nuevos al final, cada uno con su número. */
    fun conVersiones(lista: List<Mensaje>, cierre: Cierre): List<Mensaje> {
        val salida = lista.filterNot { it.id in cierre.quitar }.toMutableList()
        for (v in cierre.anadir) if (salida.none { it.id == v.id }) salida += conNumero(salida, v)
        return salida
    }

    /** El número en su chat, como [com.forge.pixpin.guardados.MensajesStore.anadir]: uno más que el mayor, nunca menos que cuantos hay. */
    fun conNumero(lista: List<Mensaje>, v: Mensaje): Mensaje {
        if (v.numero > 0) return v
        val suyos = lista.filter { it.proyecto == v.proyecto }
        return v.copy(numero = maxOf(suyos.maxOfOrNull { it.numero } ?: 0, suyos.size) + 1)
    }

    // ------------------------------------------------------------------ hacer la versión

    /**
     * **Empieza a editar [original] como versión mía**: prepara la copia con sus archivos y la apunta.
     * Si ya había un borrador suyo sin tocar (se abrió para mirar), se sigue con ese en vez de
     * llenar el disco de copias. El que llama pone en el chat la de [EnCurso.provisional] y abre el
     * editor sobre [EnCurso.version].
     */
    fun empezar(
        filesDir: File, original: Mensaje, lista: List<Mensaje>, aparato: String?, ahora: Long,
        id: String = UUID.randomUUID().toString(), uid: String = Codigos.nuevo()
    ): EnCurso = synchronized(CERROJO) {
        val como = como(original)
        val apuntados = enCurso(filesDir)
        apuntados.firstOrNull { e ->
            e.original == original.id && (!e.provisional || lista.any { it.id == e.version.id }) &&
                huella(filesDir, if (e.provisional) lista.first { it.id == e.version.id } else e.version, e.sinFotos) == e.huella
        }?.let { return it }
        val v = conArchivos(filesDir, original, nueva(original, id, uid, aparato, ahora, versionesDe(lista, original).size + 1))
        val sinFotos = original.clase == Clase.IMAGEN && v.referencia == null
        val e = EnCurso(v, original.id, huella(filesDir, v, sinFotos), como == Como.PROVISIONAL, sinFotos, ahora)
        guardarEnCurso(filesDir, apuntados + e)
        e
    }

    /**
     * **Una versión de un toque** ([Como.AL_MOMENTO]): la copia con [cambio] ya hecho, lista para
     * añadir al chat. Nada queda apuntado.
     */
    fun alMomento(
        filesDir: File, original: Mensaje, lista: List<Mensaje>, aparato: String?, ahora: Long,
        cambio: (Mensaje) -> Mensaje,
        id: String = UUID.randomUUID().toString(), uid: String = Codigos.nuevo()
    ): Mensaje {
        val v = conArchivos(filesDir, original, nueva(original, id, uid, aparato, ahora, versionesDe(lista, original).size + 1))
        // Lo que identifica a la versión no lo cambia el cambio.
        return cambio(v).copy(
            id = v.id, uid = v.uid, aparato = v.aparato, versionDe = v.versionDe, versionN = v.versionN,
            respondeA = v.respondeA, cuando = v.cuando, ruta = v.ruta, referencia = v.referencia
        )
    }

    /**
     * **Al volver de los editores**: lo que cambió se queda como versión y lo que no, se tira. Un
     * borrador cambiado da un mensaje nuevo (a esta hora, para que salga abajo, como una respuesta);
     * una provisional sin cambios se quita del chat con sus archivos; un borrador sin cambios se
     * guarda hasta [CADUCA_MS] por si su editor sigue abierto. [lista] es el chat tal como está.
     */
    fun cerrar(filesDir: File, lista: List<Mensaje>, ahora: Long): Cierre = synchronized(CERROJO) {
        val apuntados = enCurso(filesDir)
        if (apuntados.isEmpty()) return Cierre(emptyList(), emptySet())
        val quedan = ArrayList<EnCurso>()
        val anadir = ArrayList<Mensaje>()
        val quitar = HashSet<String>()
        for (e in apuntados) {
            // Una provisional que ya no está (se borró a mano): se olvida.
            val actual = if (e.provisional) lista.firstOrNull { it.id == e.version.id } ?: continue else e.version
            val cambiada = huella(filesDir, actual, e.sinFotos) != e.huella
            when {
                cambiada && !e.provisional -> anadir += e.version.copy(cuando = maxOf(ahora, e.version.cuando))
                cambiada -> {}
                e.provisional -> { quitar += e.version.id; borrarArchivos(filesDir, e.version) }
                ahora - e.desde > CADUCA_MS -> borrarArchivos(filesDir, e.version)
                else -> quedan += e
            }
        }
        guardarEnCurso(filesDir, quedan)
        Cierre(anadir, quitar)
    }

    /** El código único del borrador cuyo adjunto es [ruta], si lo hay: su tinta va con él aunque aún no sea mensaje. */
    fun uidDeBorrador(filesDir: File, ruta: String): String? {
        if (!registro(filesDir).isFile) return null
        return enCurso(filesDir).firstOrNull { !it.provisional && it.version.ruta == ruta }?.version?.uid
    }

    // ------------------------------------------------------------------ los archivos

    private fun dibujo(filesDir: File, id: String) = File(filesDir, "$DIBUJOS/$id.excalidraw.gz")

    /** El dibujo [id] y lo que va a su lado (marcadores, vistas), copiado a [nuevo]. */
    private fun copiarDibujo(filesDir: File, id: String, nuevo: String) {
        for (fin in listOf(".excalidraw.gz", ".marcas", ".vistas.json")) {
            val f = File(filesDir, "$DIBUJOS/$id$fin")
            if (f.isFile) runCatching { f.copyTo(File(filesDir, "$DIBUJOS/$nuevo$fin"), overwrite = true) }
        }
    }

    /**
     * **La versión [v] con sus propios archivos**: el adjunto copiado a otra ruta, el dibujo a otro
     * id y lo anotado con el código del original copiado al suyo. Copias y no enlaces: si el
     * original se borra, se lleva su adjunto, y la versión tiene que seguir entera.
     */
    fun conArchivos(filesDir: File, original: Mensaje, v: Mensaje): Mensaje {
        var salida = v
        original.ruta?.let { r ->
            val f = File(r)
            if (!f.isFile) return@let
            val carpeta = File(filesDir, "guardados").apply { mkdirs() }
            val destino = File(carpeta, "${v.cuando}_v${v.versionN ?: 1}_${f.name.replace(Regex("^\\d+_"), "")}")
            runCatching { f.copyTo(destino, overwrite = true) }.onSuccess { salida = salida.copy(ruta = destino.absolutePath) }
        }
        when (original.clase) {
            Clase.DIBUJO, Clase.PAGINA -> original.referencia?.let { ref ->
                val nuevo = "dib-${v.id}"
                copiarDibujo(filesDir, ref, nuevo)
                salida = salida.copy(referencia = nuevo)
            }
            // La foto: su dibujo si ya lo tenía. Si no, el editor lo hará con el nombre de la copia
            // (`foto-<id>`, ver [dibujoDeLaFoto]).
            Clase.IMAGEN -> {
                val suyo = original.dibujoDeLaFoto
                salida = if (dibujo(filesDir, suyo).isFile) {
                    val nuevo = "foto-${v.id}"
                    copiarDibujo(filesDir, suyo, nuevo)
                    salida.copy(referencia = nuevo)
                } else salida.copy(referencia = null)
            }
            else -> {}
        }
        val de = Codigos.unico(original)
        val a = v.uid ?: Codigos.unico(v)
        for (rel in AnotacionesDelAdjunto.porUid(filesDir)[de].orEmpty()) {
            val f = File(filesDir, rel)
            runCatching { f.copyTo(File(f.parentFile, f.name.replaceFirst("anot-$de", "anot-$a")), overwrite = true) }
        }
        return salida
    }

    /** Todo lo que se copió para [v]: al tirar un borrador o una provisional que no se tocó. */
    fun borrarArchivos(filesDir: File, v: Mensaje) {
        // Solo la copia (`<hora>_v<n>_…` en `guardados`): si copiar falló, la ruta es la del original.
        v.ruta?.let { File(it) }?.takeIf {
            it.parentFile?.absolutePath == File(filesDir, "guardados").absolutePath && COPIA.containsMatchIn(it.name)
        }?.delete()
        val ref = when (v.clase) {
            Clase.DIBUJO, Clase.PAGINA -> v.referencia
            Clase.IMAGEN -> v.dibujoDeLaFoto
            else -> null
        }
        ref?.let { r -> for (fin in listOf(".excalidraw.gz", ".marcas", ".vistas.json")) File(filesDir, "$DIBUJOS/$r$fin").delete() }
        v.uid?.let { u -> AnotacionesDelAdjunto.porUid(filesDir)[u].orEmpty().forEach { File(filesDir, it).delete() } }
    }

    // ------------------------------------------------------------------ si cambió

    /**
     * **La huella de lo editable de [m]**: su texto y nombre, su dibujo y lo anotado con su código.
     * No depende de los nombres de los archivos —la del borrador recién copiado es la del
     * original— ni de cómo los vuelva a escribir cada editor: de un dibujo cuentan los elementos
     * vivos con su `version`, que sube con cada cambio. Lo que es de leer (`.sitio`, `.voz`) y el
     * marco de la hoja (`.hoja`, que sale de la página) no cuentan.
     */
    fun huella(filesDir: File, m: Mensaje, sinFotos: Boolean = false): String {
        val partes = sortedMapOf<String, String>()
        partes["texto"] = m.texto
        partes["nombre"] = m.nombre
        val ref = when (m.clase) {
            Clase.DIBUJO, Clase.PAGINA -> m.referencia
            Clase.IMAGEN -> m.dibujoDeLaFoto
            else -> null
        }
        ref?.let { r ->
            partes["dibujo"] = huellaDeDibujo(dibujo(filesDir, r), sinFotos)
            for (fin in listOf(".marcas", ".vistas.json")) File(filesDir, "$DIBUJOS/$r$fin").takeIf { it.isFile }?.let { partes["dibujo$fin"] = it.readText().trim() }
        }
        val uid = Codigos.unico(m)
        for (rel in AnotacionesDelAdjunto.porUid(filesDir)[uid].orEmpty()) {
            val f = File(filesDir, rel)
            val fin = f.name.removePrefix("anot-$uid")
            if (fin.endsWith(".sitio") || fin.endsWith(".voz") || fin.endsWith(".hoja")) continue
            partes["anot$fin"] = if (fin.endsWith(".excalidraw.gz")) huellaDeDibujo(f, false) else runCatching { f.readText().trim() }.getOrDefault("")
        }
        return sha256(partes.entries.joinToString("\u0000") { "${it.key}=${it.value}" }.toByteArray())
    }

    /** Los elementos vivos de un dibujo, `id:version`, ordenados; vacío si no hay dibujo. */
    fun huellaDeDibujo(f: File, sinFotos: Boolean): String {
        if (!f.isFile) return ""
        val texto = runCatching { GZIPInputStream(f.inputStream()).use { it.readBytes().decodeToString() } }.getOrNull()
            ?: return sha256(f.readBytes())
        val elementos = (runCatching { Json.parseToJsonElement(texto) }.getOrNull() as? JsonObject)?.get("elements") as? JsonArray
            ?: return sha256(texto.toByteArray())
        return elementos.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if ((o["isDeleted"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            if (sinFotos && (o["type"] as? JsonPrimitive)?.contentOrNull == "image") return@mapNotNull null
            "${(o["id"] as? JsonPrimitive)?.contentOrNull}:${(o["version"] as? JsonPrimitive)?.contentOrNull}"
        }.sorted().joinToString(",")
    }

    // ------------------------------------------------------------------ el apunte

    private val CERROJO = Any()
    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun registro(filesDir: File) = File(filesDir, "sincro/versiones-en-curso.json")

    /** Los borradores y provisionales que se están editando. No viaja: es de este aparato. */
    fun enCurso(filesDir: File): List<EnCurso> = runCatching {
        JSON.decodeFromString(ListSerializer(EnCurso.serializer()), registro(filesDir).readText())
    }.getOrDefault(emptyList())

    private fun guardarEnCurso(filesDir: File, lista: List<EnCurso>) {
        val f = registro(filesDir)
        if (lista.isEmpty()) { f.delete(); return }
        f.parentFile?.mkdirs()
        val temporal = File(f.parentFile, f.name + ".nuevo")
        temporal.writeText(JSON.encodeToString(ListSerializer(EnCurso.serializer()), lista))
        if (!temporal.renameTo(f)) { temporal.copyTo(f, overwrite = true); temporal.delete() }
    }
}
