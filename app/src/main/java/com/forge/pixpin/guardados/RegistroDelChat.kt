package com.forge.pixpin.guardados

import com.forge.pixpin.motor.Proyecto

/**
 * **Todo lo que está en un proyecto está en su chat** (regla del usuario, reparada el 15-sep-2026).
 *
 * El chat es el registro de lo que pasa: algo puede estar en el chat y no en el proyecto, pero no
 * al revés. Proyectos creados antes de la regla, o puestos al día por una sincronización o un
 * envío que se saltaron el chat, tenían lienzos sin mensaje: el usuario vio en «Tesis» dos lienzos
 * en proyectos y uno solo en el chat.
 *
 * Esto dice **qué mensajes faltan**, con la hora a la que se creó cada cosa para que caigan en su
 * sitio del chat y no todos al final:
 *
 * - Cada lienzo, tabla, nota y croquis sin un mensaje que lo señale.
 * - **El PDF, una vez**, como el mensaje con el que nace el proyecto; sus páginas no, que van dentro
 *   del PDF y no son lienzos sueltos.
 * - Las vistas de un croquis tampoco: son del croquis.
 *
 * Sin Android: entra el proyecto con sus mensajes y sale la lista. La aplicación la escribe en
 * `ChatDeLosProyectos.reparar`.
 */
object RegistroDelChat {

    fun queFalta(p: Proyecto, mensajes: List<Mensaje>, horaDeArchivo: (Proyecto, com.forge.pixpin.motor.Hoja) -> Long? = { _, _ -> null }): List<Mensaje> {
        val suyos = mensajes.filter { it.proyecto == p.id }
        val falta = ArrayList<Mensaje>()
        fun hayReferencia(ref: String?, vararg clases: Clase) = ref != null && suyos.any { it.referencia == ref && (clases.isEmpty() || it.clase in clases) } ||
            ref != null && falta.any { it.referencia == ref }
        val horaDelProyecto = horaEn(p.id) ?: p.hojas.firstNotNullOfOrNull { horaEn(it.id) } ?: p.tocado
        fun mensaje(clase: Clase, cuando: Long, nombre: String, referencia: String? = null, texto: String = "", ruta: String? = null) = Mensaje(
            // **El mismo id en todos los aparatos**: si dos reparan su chat antes de sincronizar,
            // al juntarse se reconocen y queda uno. Ver `Disco.aplicarMensajes`.
            id = idPara(p.id, referencia ?: "pdf"), cuando = cuando, clase = clase, proyecto = p.id, nombre = nombre,
            referencia = referencia, texto = texto, ruta = ruta, unido = true
        )

        // El PDF del que nace el proyecto: un mensaje, no uno por página.
        val pdf = p.pdfLimpio ?: p.pdfOrigen
        if (pdf != null) {
            val esta = suyos.any { m ->
                m.ruta != null && (m.ruta == p.pdfLimpio || m.ruta == p.pdfOrigen || (m.clase == Clase.ARCHIVO && m.ruta.endsWith(".pdf", true)))
            }
            if (!esta) {
                val primera = p.hojas.filter { it.pagina != null }.mapNotNull { horaEn(it.id) }.minOrNull()
                falta += mensaje(Clase.ARCHIVO, primera ?: horaDelProyecto, p.nombre.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }, ruta = pdf)
            }
        }

        for (h in p.hojas) {
            if (h.pagina != null || h.croquis != null) continue
            val cuando = horaEn(h.id) ?: h.dibujo?.let(::horaEn) ?: horaDeArchivo(p, h) ?: horaDelProyecto
            if (h.deMensaje != null && suyos.any { it.id == h.deMensaje }) continue
            when {
                h.dibujo != null ->
                    if (!hayReferencia(h.dibujo, Clase.DIBUJO, Clase.PAGINA, Clase.IMAGEN)) falta += mensaje(Clase.DIBUJO, cuando, h.nombre.ifBlank { "Lienzo" }, h.dibujo)
                h.tabla != null ->
                    if (!hayReferencia(h.tabla, Clase.TABLA)) falta += mensaje(Clase.TABLA, cuando, h.nombre.ifBlank { "Tabla" }, h.tabla)
                h.nota != null -> {
                    val esta = suyos.any { it.referencia == h.id || it.hojaDelTexto == h.id }
                    if (!esta) falta += mensaje(Clase.NOTA, cuando, h.nombre, h.id, texto = h.nota)
                }
            }
        }
        for ((i, c) in p.croquis.withIndex()) {
            if (!hayReferencia(c, Clase.CROQUIS)) falta += mensaje(Clase.CROQUIS, horaEn(c) ?: horaDelProyecto, "Croquis ${i + 1}", c)
        }
        return falta
    }

    const val PREFIJO = "registro-"

    fun idPara(proyecto: String, que: String) = "$PREFIJO$proyecto-$que"

    /**
     * La hora que lleva un id dentro: casi todo en PixPin se llama `algo-<milisegundos>` desde que se
     * crea (`hoja-1726243200000`). La primera que sea una fecha con sentido.
     */
    internal fun horaEn(id: String): Long? =
        Regex("\\d{13}").findAll(id).mapNotNull { it.value.toLongOrNull() }
            .firstOrNull { it in DESDE..HASTA }

    private const val DESDE = 1_577_836_800_000L // 2020
    private const val HASTA = 4_102_444_800_000L // 2100
}
