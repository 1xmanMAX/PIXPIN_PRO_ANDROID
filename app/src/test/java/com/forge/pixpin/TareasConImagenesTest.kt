package com.forge.pixpin.mini

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **Tareas con imágenes** (3-oct-2026): los mismos casos que `crates/pixpin-proyecto/src/mini.rs`
 * del PC (`las_imagenes_de_una_tarea_…`, `una_ficha_sin_imagen_…`, `cambiar_enlaces_…`,
 * `caso_negativo_lo_que_no_es_una_imagen_…`) y la tabla del apartado 5 de
 * `docs/investigacion/2026-10-03-tareas-con-imagenes-android.md`. Si una de las dos lecturas se
 * mueve, la lista que escribe un aparato se ve rota en el otro.
 */
class TareasConImagenesTest {

    private val foto = "pixpin:files/guardados/pc/general/archivos/tarea-1759500000000-01.png"
    private val hoy = LocalDate.of(2026, 10, 3)

    @Test
    fun `las imagenes de una tarea van antes de la fecha y se separan`() {
        val texto = Tareas.fichasAImagenes("comprar yeso [img 01]", listOf(1 to foto))
        assertEquals("comprar yeso ![img 01]($foto)", texto)
        val d = Tareas.escribir("Inbox", Tareas.anadir(emptyList(), texto, hoy = hoy))
        assertEquals("# Inbox\n\n- [ ] comprar yeso ![img 01]($foto) ➕ 2026-10-03", d)
        // La fecha se sigue encontrando al final, y la imagen sale aparte.
        val t = Tareas.leer(d)[0]
        val (visible, creada) = Tareas.partir(t.texto)
        assertEquals(hoy, creada)
        assertEquals("comprar yeso" to listOf(foto), Tareas.imagenes(visible))
        assertEquals("comprar yeso", Tareas.legible(t.texto))
        // Ida y vuelta: idéntica.
        assertEquals(d, Tareas.escribir("Inbox", Tareas.leer(d)))
        // En medio del texto también: los blancos se juntan.
        assertEquals("yeso y arena" to listOf(foto), Tareas.imagenes("yeso ![img 01]($foto) y arena"))
        assertEquals("pan ![img 01](a.png) ![img 02](b.png)", Tareas.conImagenes("pan", listOf("a.png", "b.png")))
    }

    /** La tabla de lectura del apartado 5, entera. */
    @Test
    fun `la tabla de lectura del PC`() {
        fun caso(entrada: String, lee: String, enlaces: List<String>, fecha: LocalDate?) {
            val (visible, f) = Tareas.partir(entrada)
            assertEquals(entrada, fecha, f)
            assertEquals(entrada, lee to enlaces, Tareas.imagenes(visible))
        }
        caso("comprar yeso ![img 01](pixpin:files/a.png) ➕ 2026-10-03", "comprar yeso", listOf("pixpin:files/a.png"), hoy)
        caso("yeso ![img 01](x.png) y arena", "yeso y arena", listOf("x.png"), null)
        caso("![img 01](a.png) ➕ 2026-10-03", "", listOf("a.png"), hoy)
        caso("ñandú ![img 01](ñ.png) ★", "ñandú ★", listOf("ñ.png"), null)
        caso("pan", "pan", emptyList(), null)
    }

    @Test
    fun `una ficha sin imagen se queda y una imagen sin ficha va al final`() {
        // La 2 no tiene ficha: va detrás. La 3 no tiene imagen: es texto.
        assertEquals(
            "![img 01](a.png) mira [img 03] ![img 02](b.png)",
            Tareas.fichasAImagenes("[img 01] mira [img 03]", listOf(1 to "a.png", 2 to "b.png"))
        )
        // Solo imágenes, sin palabras: vale, y se lee vacía de texto.
        val solo = Tareas.fichasAImagenes("", listOf(1 to "a.png"))
        assertEquals("![img 01](a.png)", solo)
        assertEquals("" to listOf("a.png"), Tareas.imagenes(solo))
        // Con la fecha ya puesta, las imágenes entran delante de ella.
        assertEquals("pan ![img 01](a.png) ➕ 2026-10-01", Tareas.fichasAImagenes("pan ➕ 2026-10-01", listOf(1 to "a.png")))
        // Una tarea de solo imagen entra en la lista con su fecha.
        assertEquals("![img 01](a.png) ➕ 2026-10-03", Tareas.anadir(emptyList(), solo, hoy = hoy)[0].texto)
    }

    @Test
    fun `cambiar enlaces solo toca los enlaces y en su sitio`() {
        val t = "a ![img 01](x.png) b ![img 02](y.png) ➕ 2026-10-03"
        assertEquals(
            "a ![img 01](z.png) b ![img 02](y.png) ➕ 2026-10-03",
            Tareas.cambiarEnlaces(t) { if (it == "x.png") "z.png" else null }
        )
        assertEquals("sin nada", Tareas.cambiarEnlaces("sin nada") { "q" })
    }

    @Test
    fun `caso negativo lo que no es una imagen se queda como texto`() {
        for (raro in listOf(
            "un [enlace](x.png) normal",
            "![sin cierre](x.png",
            "![vacia]()",
            "![con blanco](mi foto.png)",
            "! [separada](x.png)",
            "![alt sin parentesis] x"
        )) {
            assertEquals(raro, raro to emptyList<String>(), Tareas.imagenes(raro))
            assertEquals(raro, raro, Tareas.legible(raro))
        }
        // Una tarea de antes, sin imágenes, se lee igual que siempre.
        assertEquals("pan" to emptyList<String>(), Tareas.imagenes("pan"))
        assertEquals("pan", Tareas.fichasAImagenes("pan", emptyList()))
    }

    @Test
    fun `ida y vuelta con imagenes y marcar solo cambia la casilla`() {
        val d = "# Inbox\n\n" +
            "- [ ] comprar yeso ![img 01]($foto) ➕ 2026-10-03\n" +
            "- [ ] ![img 01](pixpin:files/guardados/pc/general/archivos/tarea-1759500000000-02.png) ➕ 2026-10-03\n" +
            "- [x] grieta ![img 01](pixpin:files/guardados/grieta.jpg) en el muro ![img 02](pixpin:files/guardados/pc/obra/archivos/tarea-1759500000000-03.png) ➕ 2026-10-01"
        val t = Tareas.leer(d)
        assertEquals(d, Tareas.escribir("Inbox", t))
        assertEquals(d.replaceFirst("- [ ] comprar", "- [x] comprar"), Tareas.escribir("Inbox", Tareas.alternar(t, 0)))
        assertEquals("grieta en el muro", Tareas.legible(t[2].texto))
        // Corregir el texto conserva sus imágenes y su fecha.
        val (conFichas, imgs) = Tareas.aFichas(Tareas.partir(t[2].texto).first)
        assertEquals("grieta [img 01] en el muro [img 02]", conFichas)
        val corregida = Tareas.renombrar(t, 2, Tareas.fichasAImagenes(conFichas.replace("muro", "techo"), imgs))
        assertEquals(
            "grieta ![img 01](pixpin:files/guardados/grieta.jpg) en el techo ![img 02](pixpin:files/guardados/pc/obra/archivos/tarea-1759500000000-03.png) ➕ 2026-10-01",
            corregida[2].texto
        )
    }

    @Test
    fun `las fichas del campo se meten separadas y solo cuentan las que siguen`() {
        assertEquals("pan [img 01] " to 13, Tareas.meterFicha("pan", 3, 1))
        assertEquals("[img 02] pan" to 9, Tareas.meterFicha("pan", 0, 2))
        assertEquals("pan [img 01] " to 13, Tareas.meterFicha("pan ", 4, 1))
        assertEquals(3, Tareas.siguienteNumero(listOf(1 to "a", 2 to "b")))
        assertEquals(1, Tareas.siguienteNumero(emptyList()))
        // La 1 se borró del campo: se descarta. Van en el orden del texto.
        val imgs = listOf(1 to "a", 2 to "b", 3 to "c")
        assertEquals(listOf(3 to "c", 2 to "b"), Tareas.conFicha("[img 03] y [img 02]", imgs))
        assertEquals(emptyList<Pair<Int, String>>(), Tareas.conFicha("nada", imgs))
    }

    @Test
    fun `un retroceso dentro de una chapa la borra entera`() {
        val antes = "pan [img 01] sal"
        // Borrar el «]» de la chapa: se va entera.
        assertEquals("pan  sal" to 4, Tareas.sinFichaRota(antes, "pan [img 01 sal", listOf(1)))
        // Caso negativo: borrar fuera de la chapa, o una ficha que no es de ninguna imagen.
        assertNull(Tareas.sinFichaRota(antes, "pa [img 01] sal", listOf(1)))
        assertNull(Tareas.sinFichaRota(antes, "pan [img 01 sal", listOf(2)))
        // Ni al escribir, ni al borrar dos de golpe.
        assertNull(Tareas.sinFichaRota(antes, "pan [img 01] sala", listOf(1)))
        assertNull(Tareas.sinFichaRota(antes, "pan [img 0 sal", listOf(1)))
        assertEquals(listOf(4 until 12), Tareas.fichasEn(antes, listOf(1, 7)))
    }

    @Test
    fun `quitar y reponer la devuelve a su sitio con su fecha`() {
        val l = listOf(Tarea("a ➕ 2026-10-01"), Tarea("b ![img 01](x.png) ➕ 2026-10-02", true), Tarea("c"))
        val sin = Tareas.borrar(l, 1)
        assertEquals(l, Tareas.reponer(sin, 1, l[1]))
        // Si la lista se acortó entretanto, va al final; nunca revienta.
        assertEquals(listOf(Tarea("a"), l[1]), Tareas.reponer(listOf(Tarea("a")), 5, l[1]))
        assertEquals(listOf(l[1]), Tareas.reponer(emptyList(), -1, l[1]))
    }

    @Test
    fun `la ruta de una imagen en este aparato`() {
        assertEquals("/data/f/guardados/x.jpg", Tareas.rutaDeImagen("pixpin:files/guardados/x.jpg", "/data/f"))
        assertEquals("/data/f/guardados/x.jpg", Tareas.rutaDeImagen("pixpin:files/guardados/x.jpg", "/data/f/"))
        assertEquals("/data/f/guardados/x.jpg", Tareas.rutaDeImagen("/data/f/guardados/x.jpg", "/otra"))
        // Caso negativo: un nombre suelto no se sabe dónde está.
        assertNull(Tareas.rutaDeImagen("a.png", "/data/f"))
        assertEquals("tarea-1759500000000-02.jpg", Tareas.nombreDeCopia(1759500000000, 2, "jpg"))
    }
}
