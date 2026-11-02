package com.forge.pixpin.sincro.nube

import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.sincro.Canonico
import com.forge.pixpin.sincro.ChatCompartido
import com.forge.pixpin.sincro.Codigos
import com.forge.pixpin.sincro.Compartidos
import com.forge.pixpin.sincro.CompartidosEnDisco
import com.forge.pixpin.sincro.Disco
import com.forge.pixpin.sincro.Fusion
import com.forge.pixpin.sincro.sha256
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Lo que la vuelta pregunta de lo «sin conexión» de los visores. */
interface Retencion {
    /** Si [rel] de [chat] está sin conexión: no se sube. */
    fun retenido(chat: String, rel: String): Boolean
    /** La copia de [rel] (lo de la nube sin lo de aquí), si está retenido. */
    fun copia(chat: String, rel: String): File?

    object Nada : Retencion {
        override fun retenido(chat: String, rel: String) = false
        override fun copia(chat: String, rel: String): File? = null
    }
}

/**
 * **Una vuelta de un chat con la nube** (puerto de `nube/vuelta.rs` del PC): bajar lo que subieron
 * los demás, juntarlo con lo de aquí y subir solo lo que la nube no tiene.
 *
 * - Con `deltas1`: `GET cambios` desde la última revisión, y `PATCH` con los mensajes cambiados, el
 *   proyecto si cambió y los archivos que cambiaron; de un lienzo o una tinta, **solo los trazos
 *   nuevos**. Un 409 baja los cambios del otro, junta y vuelve a sacar el parche (hasta 4 veces).
 * - Sin `deltas1`: el inventario entero con `PUT` y `base`.
 *
 * Lo que llega se junta como por la red local ([Disco.aplicarMensajes], [Fusion.json], los trazos
 * con la regla de [TrazosNube.gana]) y **nunca escribe fuera de su chat** ([Disco.prohibidosPara]).
 */
class VueltaNube(private val d: Disco, private val nube: ClienteNube, private val deltas: Boolean, private val retencion: Retencion = Retencion.Nada, private val ahora: Long = System.currentTimeMillis()) {

    class Informe {
        var subido = false
        var bajados = 0
        var objetos = 0
        var bytesDeObjetos = 0L
        var bytesDeParches = 0L
        var conflictos = 0
    }

    private class Objeto(val sha: String, val bytes: Long, val memoria: ByteArray?, val fichero: File?) {
        fun leer(): ByteArray = memoria ?: fichero!!.readBytes()
    }

    private class Preparado(val parche: Parche, val objetos: List<Objeto>, val nuevo: EstadoDeChat, val mensajes: List<JsonElement>, val proyecto: JsonElement?, val compartido: ChatCompartido?)

    private fun mensajeDe(v: JsonElement): Mensaje? = runCatching { Disco.JSON.decodeFromString(Mensaje.serializer(), d.aLocal(v.toString())) }.getOrNull()
    private fun aJson(m: Mensaje): JsonElement = Nube.JSON.parseToJsonElement(d.portatil(m))
    private fun idDe(v: JsonElement) = ((v as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun resumenDeProyecto(p: JsonElement) = sha256(Canonico.de(p.toString()).toByteArray())
    private fun resumenDeCompartido(c: ChatCompartido) = sha256(Disco.JSON.encodeToString(ChatCompartido.serializer(), c).toByteArray())
    private fun compartidoDe(chat: String) = CompartidosEnDisco(d.filesDir).de(chat)

    // ------------------------------------------------------------------ subir

    private fun preparar(chat: String, est: EstadoDeChat): Preparado {
        var nuevo = est.copy(faltan = emptyList())
        val objetos = ArrayList<Objeto>()
        val nombre = d.chats().firstOrNull { it.id == chat }?.nombre.orEmpty()
        var pNombre: String? = null
        if (nombre != est.nombre) { pNombre = nombre; nuevo = nuevo.copy(nombre = nombre) }
        val compartido = compartidoDe(chat)
        var pCompartido: JsonElement? = null
        if (compartido != null) {
            val r = resumenDeCompartido(compartido)
            if (est.compartido != r) { pCompartido = Nube.JSON.parseToJsonElement(Disco.JSON.encodeToString(ChatCompartido.serializer(), compartido)); nuevo = nuevo.copy(compartido = r) }
        }
        val mensajes = d.mensajesPorClave(chat).values.toList()
        val enJson = ArrayList<JsonElement>()
        val poner = ArrayList<JsonElement>()
        val quitar = ArrayList<String>()
        val mens = nuevo.mensajes.toMutableMap()
        val vistos = HashSet<String>()
        for (m in mensajes) {
            val j = aJson(m)
            enJson += j
            val r = d.resumenDe(m)
            if (est.mensajes[m.id] != r) { poner += j; mens[m.id] = r }
            vistos += m.id
        }
        for (k in est.mensajes.keys) if (k !in vistos) { quitar += k; mens.remove(k) }
        nuevo = nuevo.copy(mensajes = mens)
        val proyecto = d.proyectoPortatil(chat)?.let { Nube.JSON.parseToJsonElement(it) }
        val rp = proyecto?.let(::resumenDeProyecto)
        var pProyecto: JsonElement? = null
        if (rp != est.proyecto) { pProyecto = proyecto ?: JsonNull; nuevo = nuevo.copy(proyecto = rp) }

        val archivos = nuevo.archivos.toMutableMap()
        val aPoner = ArrayList<ArchivoNube>()
        val aQuitar = ArrayList<String>()
        val enAlcance = HashSet<String>()
        for (info in d.archivos(chat)) {
            val rel = info.ruta
            enAlcance += rel
            if (retencion.retenido(chat, rel)) continue
            val prev = est.archivos[rel]
            if (prev != null && prev.local == info.resumen && prev.sha256.isNotEmpty()) continue
            if (TrazosNube.esTrazos(rel)) {
                val texto = d.textoDe(rel)
                val escena = TrazosNube.escenaDe(texto)
                if (prev != null && deltas && prev.conTrazos) {
                    val delta = TrazosNube.calcular(rel, prev.sha256, prev.conocido, escena)
                    if (delta == null) { archivos[rel] = prev.copy(local = info.resumen); continue }
                    val b = comprimir(delta.toString())
                    val n = b.size.toLong()
                    if (!TrazosNube.compactar(prev.deltas.size + 1, prev.bytesDeDeltas + n, prev.bytes)) {
                        val s = sha256(b)
                        val e = prev.copy(deltas = prev.deltas + DeltaGuardado(s, n), local = info.resumen).conConocido(prev.conocido.con(delta))
                        aPoner += ArchivoNube(rel, prev.sha256, prev.bytes, info.tocado, e.losDeltas)
                        objetos += Objeto(s, n, b, null)
                        archivos[rel] = e
                        continue
                    }
                }
                // El original entero: nuevo, o toca compactar.
                val b = bytesDeTexto(rel, texto)
                val s = sha256(b)
                var e = EnLaNube(sha256 = s, bytes = b.size.toLong(), local = info.resumen)
                if (deltas) e = e.conConocido(TrazosNube.Conocido.de(escena))
                val igual = prev != null && prev.sha256 == s && prev.deltas.isEmpty()
                if (!igual) {
                    aPoner += ArchivoNube(rel, s, e.bytes, info.tocado, if (deltas) emptyList() else null)
                    objetos += Objeto(s, e.bytes, b, null)
                }
                archivos[rel] = e
                continue
            }
            val (s, bytes, obj) = if (d.esTexto(rel)) {
                val b = bytesDeTexto(rel, d.textoDe(rel))
                Triple(sha256(b), b.size.toLong(), Objeto(sha256(b), b.size.toLong(), b, null))
            } else Triple(info.resumen, info.bytes.coerceAtLeast(0), Objeto(info.resumen, info.bytes.coerceAtLeast(0), null, d.archivo(rel)))
            if (prev == null || prev.sha256 != s) {
                aPoner += ArchivoNube(rel, s, bytes, info.tocado, null)
                objetos += obj
            }
            archivos[rel] = EnLaNube(sha256 = s, bytes = bytes, local = info.resumen)
        }
        for (rel in est.archivos.keys) if (rel !in enAlcance && !retencion.retenido(chat, rel)) { aQuitar += rel; archivos.remove(rel) }
        nuevo = nuevo.copy(archivos = archivos)
        val p = Parche(
            espacio = est.espacio.ifEmpty { null }, base = est.revision, tocado = ahora, nombre = pNombre, compartido = pCompartido,
            poner = poner, quitar = quitar, proyecto = pProyecto, archivosPoner = aPoner, archivosQuitar = aQuitar
        )
        return Preparado(p, objetos, nuevo, enJson, proyecto, compartido)
    }

    private fun bytesDe(rel: String, s: String): ByteArray? = runCatching {
        val b = if (d.esTexto(rel)) bytesDeTexto(rel, d.textoDe(rel)) else d.archivo(rel).readBytes()
        b.takeIf { sha256(it) == s }
    }.getOrNull()

    private fun subirObjetos(objetos: List<Objeto>, inf: Informe) {
        val hechos = HashSet<String>()
        for (o in objetos) {
            if (!hechos.add(o.sha)) continue
            if (o.bytes > SIN_PREGUNTAR && nube.hayObjeto(o.sha)) continue
            nube.subirObjeto(o.sha, o.leer())
            inf.objetos++; inf.bytesDeObjetos += o.bytes
        }
    }

    private fun subirFaltan(faltan: List<String>, prep: Preparado, inf: Informe): List<String> {
        val quedan = ArrayList<String>()
        for (s in faltan) {
            val b = prep.objetos.firstOrNull { it.sha == s }?.let { runCatching { it.leer() }.getOrNull() }
                ?: prep.nuevo.archivos.entries.firstOrNull { it.value.sha256 == s }?.let { bytesDe(it.key, s) }
            val ok = b != null && runCatching { nube.subirObjeto(s, b) }.isSuccess
            if (ok) { inf.objetos++; inf.bytesDeObjetos += b!!.size } else quedan += s
        }
        return quedan
    }

    private fun inventario(prep: Preparado, base: Long): JsonObject {
        val archivos = prep.nuevo.archivos.entries.sortedBy { it.key }.map { (rel, e) ->
            ArchivoNube(rel, e.sha256, e.bytes, null, if (deltas && TrazosNube.esTrazos(rel)) e.losDeltas else null).json()
        }
        val m = linkedMapOf<String, JsonElement>(
            "nombre" to JsonPrimitive(prep.nuevo.nombre), "tocado" to JsonPrimitive(ahora),
            "mensajes" to kotlinx.serialization.json.JsonArray(prep.mensajes),
            "proyecto" to (prep.proyecto ?: JsonNull),
            "archivos" to kotlinx.serialization.json.JsonArray(archivos)
        )
        if (base > 0) m["base"] = JsonPrimitive(base)
        prep.compartido?.let { m["compartido"] = Nube.JSON.parseToJsonElement(Disco.JSON.encodeToString(ChatCompartido.serializer(), it)) }
        return JsonObject(m)
    }

    /** **Sube lo de aquí** (y en un 409 baja, junta y vuelve a intentarlo). */
    fun subir(chat: String, est0: EstadoDeChat, inf: Informe): EstadoDeChat {
        var est = est0
        repeat(INTENTOS) {
            val prep = preparar(chat, est)
            if (prep.parche.vacio && est.faltan.isEmpty()) return prep.nuevo
            val cuerpo = prep.parche.json()
            val largo = cuerpo.toString().toByteArray().size
            if (deltas && largo <= TOPE_DE_PARCHE) {
                subirObjetos(prep.objetos, inf)
                inf.bytesDeParches += largo
                try {
                    val s = nube.parche(chat, cuerpo)
                    val quedan = subirFaltan((s.faltan + est.faltan).distinct(), prep, inf)
                    inf.subido = true
                    return prep.nuevo.copy(revision = s.revision, espacio = s.espacio.ifEmpty { prep.nuevo.espacio }, faltan = quedan)
                } catch (c: FalloNube.Conflicto) {
                    inf.conflictos++
                    est = bajar(chat, est, inf)
                }
            } else {
                val inv = inventario(prep, est.revision)
                inf.bytesDeParches += inv.toString().toByteArray().size
                try {
                    var s = nube.subirInventario(chat, inv)
                    var faltan = prep.nuevo.faltan
                    if (s.faltan.isNotEmpty()) {
                        val quedan = subirFaltan(s.faltan, prep, inf)
                        s = nube.subirInventario(chat, JsonObject(inv + ("base" to JsonPrimitive(s.revision))))
                        if (quedan.isNotEmpty()) faltan = quedan
                    }
                    inf.subido = true
                    return prep.nuevo.copy(revision = s.revision, espacio = s.espacio.ifEmpty { prep.nuevo.espacio }, faltan = faltan)
                } catch (c: FalloNube.Conflicto) {
                    inf.conflictos++
                    est = bajar(chat, est, inf)
                }
            }
        }
        throw FalloNube.Conflicto(est.revision)
    }

    // ------------------------------------------------------------------ bajar

    /** **Baja lo que subieron los demás** desde la última revisión y lo junta. */
    fun bajar(chat: String, est0: EstadoDeChat, inf: Informe): EstadoDeChat {
        var est = est0
        if (deltas) {
            val c = try { nube.cambios(est.espacio, chat, est.revision) } catch (_: FalloNube.NoEsta) { return est }
            val reiniciar = (c["reiniciar"] as? JsonPrimitive)?.contentOrNull == "true"
            if (!reiniciar) {
                val valla = Valla(chat)
                for (e in (c["cambios"] as? kotlinx.serialization.json.JsonArray).orEmpty()) {
                    val p = Parche.de(e as? JsonObject ?: continue)
                    est = aplicarParche(chat, est, p, valla)
                    p.revision?.let { r -> est = est.copy(revision = maxOf(est.revision, r)) }
                    inf.bajados++
                }
                val rev = ((c["revision"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()) ?: 0
                return est.copy(revision = maxOf(est.revision, rev))
            }
        }
        return bajarEntero(chat, est, inf)
    }

    private fun bajarEntero(chat: String, est: EstadoDeChat, inf: Informe): EstadoDeChat {
        val inv = try { nube.inventario(est.espacio, chat) } catch (_: FalloNube.NoEsta) { return est }
        val p = parcheDeInventario(est, inv)
        var e = aplicarParche(chat, est, p, Valla(chat))
        p.revision?.let { e = e.copy(revision = it) }
        ((inv["espacio"] as? JsonPrimitive)?.contentOrNull)?.takeIf { it.isNotEmpty() }?.let { e = e.copy(espacio = it) }
        inf.bajados++
        return e
    }

    /** **Un inventario como parche** contra lo sabido: lo que difiere. */
    fun parcheDeInventario(est: EstadoDeChat, inv: JsonObject): Parche {
        val todo = Parche.de(JsonObject(mapOf(
            "mensajes" to JsonObject(mapOf("poner" to (inv["mensajes"] ?: kotlinx.serialization.json.JsonArray(emptyList())))),
            "archivos" to JsonObject(mapOf("poner" to (inv["archivos"] ?: kotlinx.serialization.json.JsonArray(emptyList()))))
        )))
        val poner = ArrayList<JsonElement>()
        val vistos = HashSet<String>()
        for (v in todo.poner) {
            val k = idDe(v)
            val m = mensajeDe(v)
            if (m == null || est.mensajes[k] != d.resumenDe(m)) poner += v
            vistos += k
        }
        val proyecto = inv["proyecto"]?.takeIf { it !is JsonNull }
        val rp = proyecto?.let(::resumenDeProyecto)
        val archivos = todo.archivosPoner
        val rels = archivos.map { it.rel }.toSet()
        return Parche(
            base = est.revision, tocado = (inv["tocado"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0,
            nombre = ((inv["nombre"] as? JsonPrimitive)?.contentOrNull)?.takeIf { it != est.nombre },
            compartido = inv["compartido"]?.takeIf { it !is JsonNull },
            poner = poner, quitar = est.mensajes.keys.filter { it !in vistos },
            proyecto = if (rp != null && rp != est.proyecto) proyecto else null,
            archivosPoner = archivos.filter { a -> est.archivos[a.rel].let { it == null || it.sha256 != a.sha256 || it.losDeltas != a.losDeltas } },
            archivosQuitar = est.archivos.keys.filter { it !in rels },
            revision = (inv["revision"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        )
    }

    /** Lo que no se deja escribir a lo que llega: los archivos de los demás chats. */
    private inner class Valla(private val chat: String) {
        private var prohibidos: Set<String>? = null
        fun deja(rel: String): Boolean {
            if (!d.permitida(rel) || rel.startsWith(Disco.CARPETA_DE_LETRAS)) return false
            val p = prohibidos ?: d.prohibidosPara(setOf(chat)).also { prohibidos = it }
            return rel !in p
        }
    }

    /** **Pone un parche de la nube aquí.** */
    private fun aplicarParche(chat: String, est0: EstadoDeChat, p: Parche, valla: Valla): EstadoDeChat {
        var est = est0
        p.nombre?.let { est = est.copy(nombre = it) }
        val c = p.compartido?.let { runCatching { Disco.JSON.decodeFromString(ChatCompartido.serializer(), it.toString()) }.getOrNull() }
        if (c != null && c.chat == chat) {
            CompartidosEnDisco(d.filesDir).cambiar { l -> l.filter { it.chat != chat } + Compartidos.juntar(l.firstOrNull { it.chat == chat }, c) }
            est = est.copy(compartido = resumenDeCompartido(c))
        }
        val proyecto = p.proyecto?.takeIf { it !is JsonNull }
        if (proyecto != null) {
            val rs = resumenDeProyecto(proyecto)
            val mio = d.proyectoPortatil(chat)?.let { Nube.JSON.parseToJsonElement(it) }
            val fin = if (mio != null && resumenDeProyecto(mio) != est.proyecto) Fusion.json(null, mio, proyecto) ?: proyecto else proyecto
            if (mio == null || resumenDeProyecto(mio) != resumenDeProyecto(fin)) d.proyectoDe(fin.toString())?.let { d.guardarProyecto(it) }
            est = est.copy(proyecto = rs)
        }
        if (p.poner.isNotEmpty() || p.quitar.isNotEmpty()) {
            val locales = d.mensajesPorClave(chat).values.associateBy { it.id }
            val mens = est.mensajes.toMutableMap()
            val poner = ArrayList<String>()
            for (v in p.poner) {
                val m = mensajeDe(v) ?: continue
                val k = m.id
                val r = d.resumenDe(m)
                val l = locales[k]
                val fin = if (l != null && mens[k] != d.resumenDe(l) && d.resumenDe(l) != r)
                    Fusion.json(null, aJson(l), aJson(m))?.let(::mensajeDe) ?: m else m
                if (l == null || d.resumenDe(l) != d.resumenDe(fin)) poner += d.portatil(fin)
                mens[k] = r
            }
            val quitar = ArrayList<String>()
            for (k in p.quitar) {
                val l = locales[k]
                if (l != null && mens[k] == d.resumenDe(l)) quitar += Codigos.unico(l)
                mens.remove(k)
            }
            est = est.copy(mensajes = mens)
            if (poner.isNotEmpty() || quitar.isNotEmpty()) d.aplicarMensajes(chat, poner, quitar, ahora)
        }
        for (a in p.archivosPoner) {
            if (!valla.deja(a.rel)) continue
            est = aplicarArchivo(chat, est, a)
        }
        for (rel in p.archivosQuitar) {
            val x = est.archivos[rel] ?: continue
            est = est.copy(archivos = est.archivos - rel)
            if (valla.deja(rel) && !retencion.retenido(chat, rel) && runCatching { d.resumenDeArchivo(rel) }.getOrNull() == x.local) d.archivo(rel).delete()
        }
        return est
    }

    private fun bajarDeltas(ds: List<Delta>): List<JsonObject> = ds.map { x ->
        val v = Nube.JSON.parseToJsonElement(descomprimir(nube.bajarObjeto(x.sha256))) as? JsonObject
        if (v == null || !TrazosNube.esDelta(v)) throw FalloNube.Formato("un delta que no lo es")
        v
    }

    /** **Pone un archivo de la nube aquí**: de un lienzo, juntando trazo a trazo; lo demás, entero. */
    private fun aplicarArchivo(chat: String, est: EstadoDeChat, a: ArchivoNube): EstadoDeChat {
        val rel = a.rel
        val prev = est.archivos[rel]
        if (prev != null && prev.sha256 == a.sha256 && prev.losDeltas == a.losDeltas) return est
        val existe = d.archivo(rel).isFile
        val local = if (existe) runCatching { d.resumenDeArchivo(rel) }.getOrNull() else null
        val cambiadoAqui = local != null && prev?.local != local
        val copia = if (retencion.retenido(chat, rel)) retencion.copia(chat, rel) else null
        if (TrazosNube.esTrazos(rel)) {
            val incremental = prev?.takeIf { existe && it.conTrazos && it.sha256 == a.sha256 && a.losDeltas.take(it.deltas.size) == it.losDeltas }
            val aqui: JsonObject
            val conocido: TrazosNube.Conocido
            val nuevosDeltas: List<JsonObject>
            var entera: JsonObject? = null
            if (incremental != null) {
                nuevosDeltas = bajarDeltas(a.losDeltas.drop(incremental.deltas.size))
                var escena = TrazosNube.escenaDe(d.textoDe(rel))
                var k = incremental.conocido
                for (v in nuevosDeltas) { escena = TrazosNube.aplicar(escena, v).first; k = k.con(v) }
                aqui = escena; conocido = k
            } else {
                val base = descomprimir(nube.bajarObjeto(a.sha256))
                nuevosDeltas = bajarDeltas(a.losDeltas)
                val efectivo = TrazosNube.efectivo(TrazosNube.escenaDe(base), nuevosDeltas)
                conocido = TrazosNube.Conocido.de(efectivo)
                aqui = if (existe) TrazosNube.juntar(TrazosNube.escenaDe(d.textoDe(rel)), efectivo) else efectivo
                entera = efectivo
            }
            d.escribirTexto(rel, aqui.toString())
            if (copia != null) {
                val (t, gz) = leerCopia(copia) ?: ("" to rel.endsWith(".gz"))
                var c = TrazosNube.escenaDe(t)
                c = if (entera != null) TrazosNube.juntar(c, entera) else nuevosDeltas.fold(c) { x, v -> TrazosNube.aplicar(x, v).first }
                escribirCopia(copia, d.aLocal(c.toString()), gz)
            }
            val e = EnLaNube(sha256 = a.sha256, bytes = a.bytes, deltas = a.losDeltas.map { DeltaGuardado(it.sha256, it.bytes) })
                .conConocido(conocido)
                .let { it.copy(local = if (cambiadoAqui) prev?.local.orEmpty() else d.resumenDeArchivo(rel)) }
            return est.copy(archivos = est.archivos + (rel to e))
        }
        var e = EnLaNube(sha256 = a.sha256, bytes = a.bytes)
        if (!d.esTexto(rel) && local == a.sha256) return est.copy(archivos = est.archivos + (rel to e.copy(local = a.sha256)))
        val bytes = nube.bajarObjeto(a.sha256)
        val esJson = rel.endsWith(".json")
        fun juntado(mio: String, suyo: String): String? = runCatching {
            Fusion.json(null, Nube.JSON.parseToJsonElement(mio), Nube.JSON.parseToJsonElement(suyo))?.toString()
        }.getOrNull()
        var escrito = true
        if (d.esTexto(rel)) {
            val suyo = descomprimir(bytes)
            val fin = if (cambiadoAqui && esJson) juntado(d.textoDe(rel), suyo) ?: suyo else suyo
            d.escribirTexto(rel, fin)
            if (copia != null) escribirCopia(copia, d.aLocal(suyo), leerCopia(copia)?.second ?: rel.endsWith(".gz"))
        } else {
            val fin: ByteArray? = if (cambiadoAqui) {
                if (esJson) juntado(runCatching { d.archivo(rel).readText() }.getOrDefault(""), bytes.decodeToString())?.toByteArray()
                else { escrito = false; null }
            } else bytes
            if (fin != null) d.escribirArchivo(rel, fin.size.toLong()) { it.write(fin); true }
            if (copia != null) escribirAtomico(copia, bytes)
        }
        e = e.copy(local = if (cambiadoAqui || !escrito) prev?.local.orEmpty() else d.resumenDeArchivo(rel))
        return est.copy(archivos = est.archivos + (rel to e))
    }

    /** **Una vuelta entera de un chat**: lo sabido, bajar (si toca o es la primera vez), subir, y guardarlo. */
    fun deChat(chat: String, bajarAntes: Boolean): Informe {
        var est = EstadoDeChat.leer(d.filesDir, chat)
        if (est.espacio.isEmpty()) compartidoDe(chat)?.let { est = est.copy(espacio = Nube.espacioCompartido(it.creador, chat)) }
        val inf = Informe()
        try {
            if (bajarAntes || est.revision == 0L) {
                est = try { bajar(chat, est, inf) } catch (e: FalloNube.Llave) {
                    // Un compartido que aún no está en la nube: no sabe si estoy dentro hasta que suba su lista.
                    if (est.revision == 0L) est else throw e
                }
            }
            est = subir(chat, est, inf)
            est = est.copy(alDia = ahora, error = null)
            est.guardar(d.filesDir, chat)
            return inf
        } catch (e: FalloNube) {
            est.copy(error = e.message).guardar(d.filesDir, chat)
            throw e
        }
    }

    companion object {
        /** Hasta este tamaño un objeto se sube sin preguntar antes si ya está. */
        const val SIN_PREGUNTAR = 64L * 1024
        const val INTENTOS = 4
        const val TOPE_DE_PARCHE = 4 * 1024 * 1024

        /** `gzip` sin fecha (sale igual cada vez). */
        fun comprimir(texto: String): ByteArray {
            val o = ByteArrayOutputStream()
            GZIPOutputStream(o).use { it.write(texto.toByteArray()) }
            return o.toByteArray()
        }

        /** El texto de unos bytes, comprimidos o no (se miran los dos primeros). */
        fun descomprimir(b: ByteArray): String = try {
            if (b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) GZIPInputStream(b.inputStream()).use { it.readBytes().decodeToString() }
            else b.decodeToString()
        } catch (e: Exception) { throw FalloNube.Formato(e.message ?: "gzip") }

        fun bytesDeTexto(rel: String, texto: String): ByteArray = if (rel.endsWith(".gz")) comprimir(texto) else texto.toByteArray()

        private fun leerCopia(f: File): Pair<String, Boolean>? = runCatching {
            val b = f.readBytes()
            descomprimir(b) to (b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte())
        }.getOrNull()

        private fun escribirCopia(f: File, texto: String, gz: Boolean) = escribirAtomico(f, if (gz) comprimir(texto) else texto.toByteArray())

        /** Apunta en qué espacio vive un chat (lo que dijo `GET chats`). */
        fun conocerEspacio(filesDir: File, chat: String, espacio: String) {
            val est = EstadoDeChat.leer(filesDir, chat)
            if (est.espacio != espacio) est.copy(espacio = espacio).guardar(filesDir, chat)
        }
    }
}
