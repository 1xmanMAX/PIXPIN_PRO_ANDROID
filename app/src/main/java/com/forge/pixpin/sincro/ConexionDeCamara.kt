package com.forge.pixpin.sincro

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * **Una conexión con el PC para su grabación** (28-oct-2026, la `Conexion` de `al_lienzo.rs` y
 * `camara.rs` del PC): el saludo de siempre por el canal cifrado del grupo, diciendo
 * [Protocolo.CAPACIDADES] (con `camara1`), y luego preguntar cómo va la grabación ([preguntar]) o
 * mandarle fotos y clips ([mandar]), varias en la misma conexión, y [adios] al final.
 *
 * **No ocupa** este teléfono ([Protocolo.ocupar]): como «Enviar a tus aparatos» ([AlGrupo]),
 * mandar no escribe nada de aquí. Un «no» del PC antes del «vale» ([Protocolo.ErrorDelOtro]) deja
 * la conexión sana; cualquier otra [IOException] es que se cortó.
 */
class ConexionDeCamara private constructor(
    private val canal: Canal,
    /** El PC, tal como se presentó. */
    val otro: Aparato,
    /** Dónde escucha él, para recordarlo. */
    val puertoDelOtro: Int,
    /** Lo que dijo saber hacer en su `hola`. */
    val capacidades: Set<String>
) {
    private var despedida = false
    /** Aún no se ha leído ninguna respuesta tras el `hola` ([recibir]). */
    private var primera = true

    /**
     * **La siguiente respuesta, sin el «ocupado» suelto del PC** (28-oct-2026, §11.6): el PC que graba
     * se da por ocupado y, tras el `hola`, manda **por su cuenta** un [Protocolo.OCUPADO]
     * (`contestar_ocupado` de `protocolo.rs`) antes de atender la cámara y las fotos como siempre. Si
     * la primera respuesta es ese «ocupado» a secas, no es la de lo pedido: se salta y se lee la de
     * verdad. Un aparato de antes que corta tras su «ocupado» da aquí un corte ([IOException]).
     */
    private fun recibir(): String {
        while (true) {
            val (tipo, datos) = canal.recibir()
            if (tipo != Protocolo.JSON_) throw IOException("Se esperaba una respuesta")
            val texto = datos.decodeToString()
            if (primera) {
                primera = false
                if (esElOcupadoSuelto(texto)) continue
            }
            return texto
        }
    }

    private fun respuesta(): Protocolo.Respuesta = Protocolo.json.decodeFromString(Protocolo.Respuesta.serializer(), recibir())

    /** Si el otro sabe de la cámara del móvil (`camara1` en su `hola`). */
    val sabeDeCamara: Boolean get() = CamaraRemota.CAPACIDAD in capacidades

    /** Lo que contestó el PC al preguntar, con lo medido de paso. */
    data class Respuesta(
        val anuncio: CamaraRemota.Anuncio,
        /** Lo que va adelantado el reloj de aquí sobre el del PC (`−desfase(t0, t1, t2)`). */
        val adelanto: Long,
        /** La mitad de la vuelta: lo que hace que el PC escribió su anuncio. */
        val ida: Long
    )

    /**
     * **Pregunta al PC cómo va su grabación** y mide el desfase de los relojes de paso:
     * `{"t":"camara_remota","reloj":t0}` → el anuncio con `reloj: t1`, que llega en `t2`. Lanza
     * [Protocolo.ErrorDelOtro] si el PC es de antes («No sé qué es «camara_remota»»): la conexión
     * sigue sana.
     */
    fun preguntar(ahora: () -> Long = System::currentTimeMillis): Respuesta {
        val t0 = ahora()
        canal.enviar(Protocolo.JSON_, CamaraRemota.aTexto(CamaraRemota.Anuncio(reloj = t0)).toByteArray())
        val texto = recibir()
        val t2 = ahora()
        val r = CamaraRemota.deTexto(texto)
        r.error?.let { throw Protocolo.ErrorDelOtro(it) }
        val adelanto = if (r.reloj > 0) -CamaraRemota.desfase(t0, r.reloj, t2) else 0L
        return Respuesta(r, adelanto, ((t2 - t0) / 2).coerceAtLeast(0))
    }

    /**
     * **Manda una foto o un clip a la grabación del PC** (`suelto` con `destino: "grabacion"`) y
     * devuelve el minuto donde quedó. El diálogo (§2.3 de la guía):
     *
     * ```
     * móvil → suelto (con grabacion, ms, tomada_en, reloj, clave y, un clip, duracionMs)
     * PC    → {} · {"desdeByte":N} (ya tiene eso de otro intento) · {"error":"…"} (no sale nada)
     * móvil → TROZO… (bytes − desdeByte) + {"resumen":"<sha256 de lo mandado>"}
     * PC    → {"t":"listo","donde":"grabacion","ms":754000} · {"error":"…"}
     * ```
     *
     * Un «no» del PC lanza [Protocolo.ErrorDelOtro] con la conexión sana. [avance] recibe los
     * bytes que salen.
     */
    fun mandar(fichero: File, t: ColaDeLaCamara.Toma, avance: (Long) -> Unit = {}): Long {
        if (!fichero.isFile) throw java.io.FileNotFoundException("«${t.nombre}» ya no está en el teléfono")
        val largo = fichero.length()
        Protocolo.enviar(
            canal,
            Protocolo.Peticion(
                "suelto", nombre = t.nombre, mime = t.mime, bytes = largo, destino = CamaraRemota.DESTINO,
                // **Por su hora** (§12.4): una foto hecha sin saber de ninguna grabación va sin
                // `grabacion` ni `ms`, solo con su hora y su desfase; quien graba decide dónde cae.
                grabacion = t.grabacion.ifEmpty { null }, ms = t.ms.takeIf { t.grabacion.isNotEmpty() },
                tomadaEn = t.tomadaEn, reloj = t.reloj,
                duracionMs = if (t.esClip) t.duracionMs else 0, clave = t.clave
            )
        )
        val vale = respuesta()
        vale.error?.let { throw Protocolo.ErrorDelOtro(it) }
        // Lo que ya tenía de otro intento (solo con clave): lo demás.
        val desde = if (vale.desdeByte > 0 && t.clave.isNotEmpty()) vale.desdeByte.coerceIn(0, largo) else 0L
        val bien = Protocolo.salidaDeArchivo(fichero, desde).mandar(canal, avance)
        val listo = respuesta()
        if (bien == null) throw Protocolo.ErrorDelOtro("El archivo cambió mientras se mandaba")
        listo.error?.let { throw Protocolo.ErrorDelOtro(it) }
        return listo.ms ?: t.ms
    }

    /**
     * **Quien graba aquí avisa de la suya al otro** (28-oct-2026, §13, `anunciar_la_mia` del PC): la
     * misma `camara_remota` que [preguntar], pero con la grabación de este teléfono en `suya`. El otro
     * la apunta (sale «Captura para la grabación del móvil») y contesta con la suya. Devuelve su
     * anuncio y lo que va adelantado el reloj de aquí sobre el suyo. Lanza [Protocolo.ErrorDelOtro]
     * si el otro es de antes, con la conexión sana.
     */
    fun anunciarLaMia(mia: CamaraRemota.Anuncio, ahora: () -> Long = System::currentTimeMillis): Respuesta {
        val t0 = ahora()
        val pregunta = CamaraRemota.Pregunta(reloj = t0, suya = mia.copy(t = CamaraRemota.PETICION, reloj = t0))
        canal.enviar(Protocolo.JSON_, CamaraRemota.aTexto(pregunta).toByteArray())
        val texto = recibir()
        val t2 = ahora()
        val r = CamaraRemota.deTexto(texto)
        r.error?.let { throw Protocolo.ErrorDelOtro(it) }
        val adelanto = if (r.reloj > 0) -CamaraRemota.desfase(t0, r.reloj, t2) else 0L
        return Respuesta(r, adelanto, ((t2 - t0) / 2).coerceAtLeast(0))
    }

    /** Se despide (`adios`). Si la conexión ya se rompió, no pasa nada. */
    fun adios() {
        if (despedida) return
        despedida = true
        runCatching {
            Protocolo.enviar(canal, Protocolo.Peticion("adios"))
            respuesta()
        }
    }

    companion object {
        /** Si [texto] es un «ocupado» y nada más (`{"error":"Está sincronizando…"}`). */
        internal fun esElOcupadoSuelto(texto: String): Boolean = runCatching {
            Protocolo.json.decodeFromString(Protocolo.Respuesta.serializer(), texto) == Protocolo.Respuesta(error = Protocolo.OCUPADO)
        }.getOrDefault(false)

        /** La clave del grupo cuesta (PBKDF2): se calcula una vez por código. */
        private val claves = ConcurrentHashMap<String, ByteArray>()

        /**
         * **Saluda al PC** por una conexión ya abierta, como «Enviar a tus aparatos» pero diciendo
         * lo que sabe hacer este teléfono. Lanza si el otro no es del grupo, contesta un error (está
         * sincronizando: [Protocolo.OCUPADO]) o habla otra versión.
         */
        fun abrir(
            entrada: InputStream,
            salida: OutputStream,
            identidad: Identidad,
            miPuerto: Int = 0,
            ahora: () -> Long = System::currentTimeMillis
        ): ConexionDeCamara {
            val codigo = identidad.codigo ?: throw IOException("Este teléfono no está en un grupo")
            val clave = claves.getOrPut(codigo) { Grupo.clave(codigo) }
            val canal = Canal.abrir(entrada, salida, clave, inicia = true)
            Protocolo.enviar(
                canal,
                Protocolo.Peticion("hola", hola = Protocolo.Hola(identidad.yo, identidad.miembros, ahora(), puerto = miPuerto, capacidades = Protocolo.CAPACIDADES))
            )
            val r = try {
                Protocolo.leerRespuestaSinLanzar(canal)
            } catch (e: java.io.EOFException) {
                throw Canal.CodigoDistinto()
            } catch (e: java.net.SocketException) {
                throw Canal.CodigoDistinto()
            }
            r.error?.let { throw Protocolo.ErrorDelOtro(it) }
            val hola = r.hola ?: throw IOException("El PC no saludó")
            if (hola.version != Protocolo.VERSION) throw IOException("El PC habla la versión ${hola.version} del protocolo")
            return ConexionDeCamara(canal, hola.yo, hola.puerto, hola.capacidades.toSet())
        }
    }
}

/**
 * **Subir lo pendiente de la cola a un PC** (28-oct-2026): lo que hace el servicio de subida
 * ([SubidaDeLaCamaraService]) con cada conexión, sin Android para poder probarlo contra el PC de
 * verdad (`CamaraDelPcTest`).
 */
object SubidaALaGrabacion {

    /** Cómo fue una pasada: cuántas entraron, cuántas negó el PC y si se cortó (hay que reintentar). */
    data class Pasada(val subidas: Int, val negadas: Int, val reintentar: Boolean, val motivo: String? = null)

    /**
     * **Sube por [con] todo lo pendiente de [pc]**, de lo más viejo a lo más nuevo. Lo que llega
     * queda «lista» con su minuto; lo que el PC niega para siempre, «negada»; lo demás (un corte,
     * la cámara apagada en el PC) sigue pendiente con un fallo más, y si fue un corte se para aquí.
     */
    fun pasar(con: ConexionDeCamara, cola: ColaDeLaCamara, pc: String, ahora: () -> Long = System::currentTimeMillis, avance: (Long) -> Unit = {}): Pasada {
        var subidas = 0
        var negadas = 0
        var reintentar = false
        var motivo: String? = null
        if (!con.sabeDeCamara) return Pasada(0, 0, true, "El PC no sabe de la cámara del móvil: actualiza PixPin en el PC")
        // Lo de este PC y lo que va por su hora (a cualquier PC del grupo, §12.4).
        val tomas = cola.pendientes().filter { it.pc == pc || it.pc.isEmpty() }
        // **Por su hora, con el reloj medido ahora** si la toma no lo sabía: lo que va adelantado este
        // teléfono sobre el PC, para pasar su hora a la del PC.
        val adelanto by lazy { runCatching { con.preguntar(ahora).adelanto }.getOrNull() }
        for (t0 in tomas) {
            val f = cola.fichero(t0)
            if (!f.isFile) { cola.negada(t0.clave, "«${t0.nombre}» ya no está en el teléfono", ahora()); negadas++; continue }
            val t = if (t0.porSuHora && t0.reloj == 0L) adelanto?.let { t0.copy(reloj = it) } ?: t0 else t0
            try {
                val ms = con.mandar(f, t, avance)
                cola.lista(t.clave, ms, ahora())
                subidas++
            } catch (e: Protocolo.ErrorDelOtro) {
                val m = e.message.orEmpty()
                if (CamaraRemota.esNoParaSiempre(m)) { cola.negada(t.clave, m, ahora()); negadas++ }
                else { cola.fallo(t.clave, m); reintentar = true; motivo = m }
                // Un PC de antes no aprenderá a mitad de la tanda.
                if (CamaraRemota.esSinSoporte(m) || m == Protocolo.OCUPADO) break
            } catch (e: IOException) {
                cola.fallo(t.clave, e.message ?: e.javaClass.simpleName)
                return Pasada(subidas, negadas, true, e.message ?: "Se cortó la conexión")
            }
        }
        return Pasada(subidas, negadas, reintentar, motivo)
    }

    /** Saluda, sube lo pendiente de [pc] y se despide. Lanza si no se pudo ni saludar. */
    fun subir(
        entrada: InputStream, salida: OutputStream, identidad: Identidad, cola: ColaDeLaCamara, pc: String,
        miPuerto: Int = 0, ahora: () -> Long = System::currentTimeMillis, avance: (Long) -> Unit = {}
    ): Pasada {
        val con = ConexionDeCamara.abrir(entrada, salida, identidad, miPuerto, ahora)
        val p = pasar(con, cola, pc, ahora, avance)
        // Tras un corte, `adios` falla sin más: no hay a quién despedirse.
        con.adios()
        return p
    }
}
