package com.forge.pixpin.sincro

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * **Añadir a una persona a un chat compartido** (13-oct-2026): como emparejar un aparato del grupo,
 * con un código corto y su QR, pero quien entra **recibe ese chat y nada más**. Ver [Compartidos].
 *
 * Quien invita abre una puerta en su Wi-Fi mientras tiene la pantalla delante y enseña el código
 * (y el QR, que además dice dónde está). Quien entra lo escanea o lo teclea: con la clave que sale
 * del código se hablan una sola vez ([Canal.MAGIA_DE_INVITACION]); le da su ficha (su clave
 * pública y su nombre), quien invita la firma y le devuelve la lista del chat. Desde ahí ya se
 * reconocen por sus claves, y el chat en sí viaja por una conversación entre personas
 * ([Sesion.conectarConPersona]), que no depende de lo corto del código.
 */
object Invitacion {

    /** Ocho signos sin letras que se confundan: ~40 bits, de sobra para lo que dura abierta. */
    const val LARGO = 8
    const val TIPO = "_pixpinchat._tcp."

    fun nuevoCodigo(azar: SecureRandom = SecureRandom()): String =
        String(CharArray(LARGO) { Grupo.SIGNOS[azar.nextInt(Grupo.SIGNOS.length)] })

    fun limpiar(tecleado: String): String = Grupo.limpiar(tecleado).take(LARGO)

    fun valido(codigo: String) = codigo.length == LARGO && codigo.all { it in Grupo.SIGNOS }

    /** `K7Q2-M9XP`. */
    fun legible(codigo: String) = if (codigo.length == LARGO) codigo.take(4) + "-" + codigo.drop(4) else codigo

    fun clave(codigo: String): ByteArray = Grupo.clave("chat-$codigo")

    /** Lo que se anuncia en la red para que quien teclea el código encuentre a quien invita. */
    fun etiqueta(codigo: String): String = Grupo.etiqueta(clave(codigo))

    fun textoDelQr(codigo: String, host: String?, puerto: Int): String = "pixpin-chat:1:$codigo:${host.orEmpty()}:$puerto"

    data class DelQr(val codigo: String, val host: String?, val puerto: Int)

    fun leerQr(texto: String): DelQr? {
        val p = texto.trim().split(':')
        if (p.size < 5 || p[0] != "pixpin-chat") return null
        val codigo = p[2].takeIf(::valido) ?: return null
        return DelQr(codigo, p[3].ifBlank { null }, p[4].toIntOrNull() ?: 0)
    }

    /**
     * **Por dónde se entra en un chat compartido** (27-oct-2026). El usuario no podía unirse: las
     * instrucciones decían «PixPin → ⋮ → Entrar en chat», y esos tres puntos solo existían dentro de
     * «Mensajes guardados», que ya no es lo primero que se ve. Ahora la puerta es el «+» de la lista
     * de chats (como el PC, que la pone en el menú de su «+», `cc-menu-entrar`), y todos los textos
     * que la explican salen de aquí para no volver a quedarse atrás.
     */
    const val UNIRSE = "Unirse a un chat compartido"
    const val UNIRSE_DETALLE = "Con el código o el QR de quien te invita"
    const val COMO_UNIRSE = "En el otro teléfono: PixPin → el «+» de abajo en la lista de chats → «$UNIRSE». " +
        "Que escanee este QR o teclee el código."
    const val COMO_INVITAR = "Quien te invita mantiene pulsado el chat en su lista (o lo abre → ⋮) → Personas → Añadir, " +
        "y te enseña un QR y un código. Solo recibirás ese chat: nada más de su teléfono, y tú no le das nada tuyo."

    /** Lo que sale de entrar: el chat, y quién me invitó y dónde escucha (para apuntar su dirección). */
    class Entrada(val chat: ChatCompartido, val quien: Aparato, val puerto: Int)

    /**
     * **Entrar con el código** por una conexión ya abierta con quien invita. Guarda la lista del chat
     * y devuelve lo que se sabe; el chat en sí se trae después con [VueltaConPersona].
     */
    fun entrar(entrada: InputStream, salida: OutputStream, disco: Disco, codigo: String, ahora: () -> Long = System::currentTimeMillis): Entrada {
        val compartidos = CompartidosEnDisco(disco.filesDir)
        val yo = compartidos.yo(disco.identidad.leer())
        val canal = Canal.abrir(entrada, salida, clave(codigo), inicia = true, magia = Canal.MAGIA_DE_INVITACION)
        Protocolo.enviar(canal, Protocolo.Peticion(
            "unirme",
            hola = Protocolo.Hola(Aparato(yo.id, yo.nombre), reloj = ahora()),
            persona = Protocolo.fichaATexto(yo.ficha())
        ))
        val r = try {
            Protocolo.leerRespuesta(canal)
        } catch (e: Canal.CodigoDistinto) {
            throw IOException("El código no es el bueno")
        } catch (e: java.io.EOFException) {
            // Quien invita no pudo descifrar el saludo (otro código) y colgó.
            throw IOException("El código no es el bueno")
        }
        val hola = r.hola ?: throw IOException("No saludó")
        val suyo = Protocolo.deTexto(r.compartidos).singleOrNull() ?: throw IOException("No llegó el chat")
        val junto = compartidos.cambiar { l -> Compartidos.juntarTodos(l, listOf(suyo)) { true } }.first { it.chat == suyo.chat }
        // Que de verdad me haya metido, con su firma: si no, la lista no vale para nada.
        if (!Compartidos.dentro(junto, yo.id)) throw IOException("No se pudo entrar en «${suyo.nombre}»")
        runCatching { Protocolo.enviar(canal, Protocolo.Peticion("adios")); Protocolo.leerRespuesta(canal) }
        return Entrada(junto, hola.yo, hola.puerto)
    }
}

/**
 * **Una vuelta con una persona** (13-oct-2026): se juntan las listas de los chats que compartimos
 * y luego cada chat donde estamos los dos, igual que con un aparato del grupo ([Sesion.preparar],
 * [Sesion.aplicar], los archivos, [Sesion.cerrar]). Sin preguntas ni borrados de proyectos: lo de
 * una persona se junta siempre ([Fusion]). Sin Android: la usan la pantalla, la sincronización
 * automática y las pruebas.
 */
class VueltaConPersona(private val disco: Disco, private val miPuerto: Int = 0, private val ahora: () -> Long = System::currentTimeMillis) {

    class Resultado(val quien: Aparato, val puerto: Int, val chats: List<String>, val hecho: Sesion.Hecho, val bytes: Long) {
        val trajoAlgo: Boolean get() = hecho.traidos > 0 || hecho.fusionados > 0 || hecho.borrados > 0
    }

    /** [aQuien]: el id del aparato al que se llama, si se sabe. [soloEstos]: solo esos chats. */
    fun con(
        entrada: InputStream, salida: OutputStream, aQuien: String? = null, soloEstos: Set<String>? = null,
        estado: (String) -> Unit = {}
    ): Resultado {
        val sesion = Sesion.conectarConPersona(entrada, salida, disco, aQuien, ahora, miPuerto)
        try {
            estado("Mirando quién está en cada chat…")
            sesion.juntarMiembros()
            val acceso = sesion.acceso!!
            val chats = acceso.chats().filter { soloEstos == null || it in soloEstos }
            val nombres = sesion.catalogo().associate { it.id to it.nombre }
            val hecho = Sesion.Hecho()
            for (chat in chats) {
                estado("Juntando «${nombres[chat] ?: chat}» con ${sesion.otro.nombre}…")
                val prep = sesion.preparar(chat)
                sesion.aplicar(prep, hecho)
                sesion.aplicarArchivos(sesion.prepararArchivos(prep), hecho)
                sesion.cerrar(prep, ahora())
            }
            val bytes = sesion.enviados + sesion.recibidos
            sesion.adios()
            return Resultado(sesion.otro, sesion.puertoDelOtro, chats, hecho, bytes)
        } finally {
            sesion.soltar()
        }
    }
}
