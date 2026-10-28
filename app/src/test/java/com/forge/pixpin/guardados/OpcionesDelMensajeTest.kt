package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.OpcionesDelMensaje.Contexto
import com.forge.pixpin.guardados.OpcionesDelMensaje.Opcion
import com.forge.pixpin.guardados.OpcionesDelMensaje.Tipo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El menú de mantener pulsado un mensaje, por tipo (28-oct-2026). Ver [OpcionesDelMensaje]. Lo
 * que pidió el usuario: que un PDF no ofrezca ni recordatorio ni llamada, y que la lista sea corta.
 */
class OpcionesDelMensajeTest {

    private fun msj(
        clase: Clase, texto: String = "", ruta: String? = null, nombre: String = "", referencia: String? = null,
        miniapp: String? = null, aparato: String? = null, transcripcion: String? = null,
        grabacion: String? = null, estadoDelTexto: String? = null, proyecto: String? = null,
        enBuzon: Boolean = false, recuerdaEn: Long? = null, escaneado: Escaneado? = null
    ) = Mensaje(
        id = "m1", cuando = 1L, clase = clase, texto = texto, ruta = ruta, nombre = nombre, referencia = referencia,
        miniapp = miniapp, aparato = aparato, transcripcion = transcripcion, grabacion = grabacion,
        estadoDelTexto = estadoDelTexto, proyecto = proyecto, enBuzon = enBuzon, recuerdaEn = recuerdaEn, escaneado = escaneado
    )

    private val nota = msj(Clase.NOTA, texto = "comprar pan")
    private val enlace = msj(Clase.NOTA, texto = "mira https://example.com/x")
    private val voz = msj(Clase.VOZ, ruta = "/f/nota.m4a", nombre = "nota.m4a")
    private val musica = msj(Clase.VOZ, ruta = "/f/cancion.mp3", nombre = "cancion.mp3", estadoDelTexto = TEXTO_LETRA)
    private val clase = msj(Clase.VOZ, ruta = "/f/clase.m4a", nombre = "clase.m4a", grabacion = "clase", transcripcion = "[0:01] hola")
    private val foto = msj(Clase.IMAGEN, ruta = "/f/foto.jpg", nombre = "foto.jpg")
    private val video = msj(Clase.ARCHIVO, ruta = "/f/v.mp4", nombre = "v.mp4")
    private val pdf = msj(Clase.ARCHIVO, ruta = "/f/informe.pdf", nombre = "informe.pdf")
    private val word = msj(Clase.ARCHIVO, ruta = "/f/carta.docx", nombre = "carta.docx")
    private val epub = msj(Clase.ARCHIVO, ruta = "/f/libro.epub", nombre = "libro.epub")
    private val zip = msj(Clase.ARCHIVO, ruta = "/f/cosas.zip", nombre = "cosas.zip")
    private val lienzo = msj(Clase.DIBUJO, referencia = "d1")
    private val pagina = msj(Clase.PAGINA, referencia = "p1")
    private val tareas = msj(Clase.MINIAPP, texto = "{\"tareas\":[]}", miniapp = "tareas")
    private val carpeta = msj(Clase.MINIAPP, texto = "", miniapp = CarpetaDePaginas.MINIAPP)
    private val asistente = msj(Clase.NOTA, texto = "La respuesta es 42", aparato = com.forge.pixpin.asistente.ChatDelAsistente.APARATO)

    private val todos = listOf(nota, enlace, voz, musica, clase, foto, video, pdf, word, epub, zip, lienzo, pagina, tareas, carpeta, asistente)

    @Test
    fun cadaMensajeTieneSuTipo() {
        assertEquals(Tipo.NOTA, OpcionesDelMensaje.tipoDe(nota))
        assertEquals(Tipo.ENLACE, OpcionesDelMensaje.tipoDe(enlace))
        assertEquals(Tipo.VOZ, OpcionesDelMensaje.tipoDe(voz))
        assertEquals(Tipo.MUSICA, OpcionesDelMensaje.tipoDe(musica))
        assertEquals(Tipo.GRABACION, OpcionesDelMensaje.tipoDe(clase))
        assertEquals(Tipo.FOTO, OpcionesDelMensaje.tipoDe(foto))
        assertEquals(Tipo.VIDEO, OpcionesDelMensaje.tipoDe(video))
        assertEquals(Tipo.DOCUMENTO, OpcionesDelMensaje.tipoDe(pdf))
        assertEquals(Tipo.DOCUMENTO, OpcionesDelMensaje.tipoDe(word))
        assertEquals(Tipo.DOCUMENTO, OpcionesDelMensaje.tipoDe(epub))
        assertEquals(Tipo.ARCHIVO, OpcionesDelMensaje.tipoDe(zip))
        assertEquals(Tipo.HOJA, OpcionesDelMensaje.tipoDe(lienzo))
        assertEquals(Tipo.HOJA, OpcionesDelMensaje.tipoDe(pagina))
        assertEquals(Tipo.MINIAPP, OpcionesDelMensaje.tipoDe(tareas))
        assertEquals(Tipo.CARPETA, OpcionesDelMensaje.tipoDe(carpeta))
        assertEquals(Tipo.ASISTENTE, OpcionesDelMensaje.tipoDe(asistente))
    }

    @Test
    fun unPdfNoLlevaNiRecordatorioNiLlamadaNiLeccion() {
        for (doc in listOf(pdf, word, epub, zip)) {
            val menu = OpcionesDelMensaje.para(doc)
            assertFalse("${doc.nombre}: recordatorio", Opcion.RECORDAR in menu)
            assertFalse("${doc.nombre}: lección", Opcion.HACER_LECCION in menu)
            assertFalse("${doc.nombre}: pinear (lo lleva la burbuja)", Opcion.PINEAR in menu)
            assertFalse("${doc.nombre}: copiar sin texto", Opcion.COPIAR in menu)
        }
    }

    @Test
    fun unPdfLlevaLecturaVelozYLoDeUnArchivo() {
        val menu = OpcionesDelMensaje.para(pdf)
        assertEquals(
            listOf(Opcion.RENOMBRAR, Opcion.RESPONDER, Opcion.LECTURA_VELOZ, Opcion.DESCRIBIR, Opcion.REENVIAR, Opcion.COMPARTIR),
            menu.visibles
        )
        assertTrue(Opcion.ABRIR_CON in menu.enMas)
        assertTrue(Opcion.ALIGERAR in menu.enMas)
        assertFalse("Aligerar es de PDF", Opcion.ALIGERAR in OpcionesDelMensaje.para(word))
    }

    @Test
    fun lecturaVelozSoloEnLoQueLee() {
        for (m in todos) {
            val tiene = Opcion.LECTURA_VELOZ in OpcionesDelMensaje.para(m)
            assertEquals(m.nombre.ifBlank { m.clase.name }, m.clase == Clase.ARCHIVO && LecturaVeloz.admite(m.nombre, m.ruta), tiene)
        }
        assertFalse(Opcion.LECTURA_VELOZ in OpcionesDelMensaje.para(video))
        assertFalse(Opcion.LECTURA_VELOZ in OpcionesDelMensaje.para(zip))
    }

    @Test
    fun laNotaDeTextoLlevaRecordatorioYCopiar() {
        val menu = OpcionesDelMensaje.para(nota)
        assertEquals(
            listOf(Opcion.COPIAR, Opcion.RESPONDER, Opcion.PINEAR, Opcion.REENVIAR, Opcion.COMPARTIR, Opcion.RECORDAR, Opcion.HACER_LECCION),
            menu.visibles
        )
        val conEnlace = OpcionesDelMensaje.para(enlace)
        assertTrue(Opcion.RECORDAR in conEnlace.visibles)
        assertTrue(Opcion.COPIAR in conEnlace.visibles)
        assertTrue("una lección de un enlace, en «Más»", Opcion.HACER_LECCION in conEnlace.enMas)
    }

    @Test
    fun laNotaDeVozLlevaLaLlamadaYLaCancionNo() {
        assertTrue(Opcion.RECORDAR in OpcionesDelMensaje.para(voz).visibles)
        assertTrue(Opcion.PINEAR in OpcionesDelMensaje.para(voz).visibles)
        val cancion = OpcionesDelMensaje.para(musica)
        assertFalse(Opcion.RECORDAR in cancion)
        assertFalse(Opcion.HACER_LECCION in cancion)
        assertTrue("la letra, a la vista en una canción", Opcion.LETRA in cancion.visibles)
        assertTrue("en una nota de voz, en «Más»", Opcion.LETRA in OpcionesDelMensaje.para(voz).enMas)
    }

    @Test
    fun laClaseGrabadaSeEstudiaYNoLlama() {
        val menu = OpcionesDelMensaje.para(clase, Contexto(puedeEstudiar = true, puedeTranscribir = true))
        assertFalse(Opcion.RECORDAR in menu)
        assertTrue(Opcion.ESTUDIAR in menu.visibles)
        assertTrue("volver a pasarla a texto, en «Más»", Opcion.TRANSCRIBIR in menu.enMas)
        assertFalse("sin el transcriptor, nada", Opcion.TRANSCRIBIR in OpcionesDelMensaje.para(clase))
    }

    @Test
    fun laFotoSeDescribeYVaAUnCuaderno() {
        val menu = OpcionesDelMensaje.para(foto)
        assertTrue(Opcion.DESCRIBIR in menu.visibles)
        assertTrue(Opcion.ANADIR_A_CARPETA in menu.visibles)
        assertTrue("recordar una foto, en «Más»", Opcion.RECORDAR in menu.enMas)
        assertFalse(Opcion.LECTURA_VELOZ in menu)
        val conDescripcion = OpcionesDelMensaje.para(foto.copy(texto = "el recibo"))
        assertTrue("su descripción se copia desde «Más»", Opcion.COPIAR in conDescripcion.enMas)
        val anotada = OpcionesDelMensaje.para(foto.copy(referencia = "d1", proyecto = "p"))
        assertTrue(Opcion.SOLO_LA_FOTO in anotada.enMas)
        assertTrue(Opcion.FOTO_DEL_CHAT in anotada.enMas)
    }

    @Test
    fun elVideoNoLlevaNiRecordatorioNiLeccion() {
        val menu = OpcionesDelMensaje.para(video)
        assertFalse(Opcion.RECORDAR in menu)
        assertFalse(Opcion.HACER_LECCION in menu)
        assertTrue(Opcion.ABRIR_CON in menu.enMas)
    }

    @Test
    fun laHojaVaAUnCuadernoSinAbrirCon() {
        for (h in listOf(lienzo, pagina)) {
            val menu = OpcionesDelMensaje.para(h)
            assertTrue(Opcion.ANADIR_A_CARPETA in menu.visibles)
            assertFalse("sin archivo no hay con qué abrir", Opcion.ABRIR_CON in menu)
            assertFalse(Opcion.PINEAR in menu)
            assertFalse(Opcion.RECORDAR in menu.visibles)
        }
        assertTrue("un lienzo se renombra", Opcion.RENOMBRAR in OpcionesDelMensaje.para(lienzo).visibles)
    }

    @Test
    fun laListaDeTareasSeRecuerdaYNoSeCopiaSuJson() {
        val menu = OpcionesDelMensaje.para(tareas)
        assertTrue(Opcion.RECORDAR in menu.visibles)
        assertTrue(Opcion.PINEAR in menu.visibles)
        assertFalse(Opcion.COPIAR in menu)
    }

    @Test
    fun laCarpetaNoCopiaNiRecuerdaNiSeInserta() {
        val menu = OpcionesDelMensaje.para(carpeta.copy(texto = "{\"paginas\":[]}"))
        for (o in listOf(Opcion.COPIAR, Opcion.RECORDAR, Opcion.HACER_LECCION, Opcion.ANADIR_A_CARPETA, Opcion.INSERTAR_EN_NOTA, Opcion.PINEAR))
            assertFalse(o.name, o in menu)
        assertEquals(listOf(Opcion.RESPONDER, Opcion.REENVIAR, Opcion.COMPARTIR), menu.visibles)
    }

    @Test
    fun laRespuestaDelAsistenteSeGuardaComoReceta() {
        val menu = OpcionesDelMensaje.para(asistente)
        assertEquals(listOf(Opcion.COPIAR, Opcion.RESPONDER, Opcion.REENVIAR, Opcion.COMPARTIR, Opcion.GUARDAR_RECETA), menu.visibles)
        assertFalse(Opcion.RECORDAR in menu)
        assertFalse(Opcion.GUARDAR_RECETA in OpcionesDelMensaje.para(nota))
    }

    @Test
    fun loDeOtraPersonaNoSeRenombraALaVista() {
        val mio = OpcionesDelMensaje.para(pdf)
        val ajeno = OpcionesDelMensaje.para(pdf, Contexto(ajeno = true))
        assertTrue(Opcion.RENOMBRAR in mio.visibles)
        assertFalse(Opcion.RENOMBRAR in ajeno.visibles)
        assertTrue("pero no se pierde: en «Más»", Opcion.RENOMBRAR in ajeno.enMas)
        val escaneo = pdf.copy(escaneado = Escaneado("escaner"))
        assertTrue(Opcion.EDITAR_EN_ESCANER in OpcionesDelMensaje.para(escaneo).visibles)
        assertTrue(Opcion.EDITAR_EN_ESCANER in OpcionesDelMensaje.para(escaneo, Contexto(ajeno = true)).enMas)
    }

    @Test
    fun conUnRecordatorioPuestoSiempreSePuedeQuitar() {
        for (m in todos) assertTrue(m.clase.name, Opcion.RECORDAR in OpcionesDelMensaje.para(m.copy(recuerdaEn = 5L)).visibles)
    }

    @Test
    fun loComunVaEnTodosYEnElMismoOrden() {
        for (m in todos) {
            val menu = OpcionesDelMensaje.para(m)
            val v = menu.visibles
            assertTrue(Opcion.RESPONDER in v && Opcion.REENVIAR in v && Opcion.COMPARTIR in v)
            assertTrue("responder antes que reenviar", v.indexOf(Opcion.RESPONDER) < v.indexOf(Opcion.REENVIAR))
            assertEquals("reenviar y compartir, seguidos", v.indexOf(Opcion.REENVIAR) + 1, v.indexOf(Opcion.COMPARTIR))
            assertEquals("fijar, etiquetar y elegir encabezan «Más»", listOf(Opcion.FIJAR, Opcion.ETIQUETAR, Opcion.ELEGIR), menu.enMas.take(3))
            assertTrue("nada repetido", (menu.visibles + menu.enMas).let { it.size == it.toSet().size })
        }
    }

    @Test
    fun aLaVistaComoMuchoOchoContandoMas() {
        val todo = Contexto(puedeTranscribir = true, hayHilo = true, puedeUnir = true, puedeDevolver = true)
        val llenos = todos.map { it.copy(transcripcion = it.transcripcion ?: "texto") } +
            listOf(foto.copy(texto = "¿Qué es? Una foto", proyecto = "p", referencia = "d"))
        // «Estudiar la clase» solo sale en lo que es de una clase: su nota, una captura o su vídeo.
        val deUnaClase = setOf(Tipo.GRABACION, Tipo.FOTO, Tipo.VIDEO)
        for (m in llenos) {
            val v = OpcionesDelMensaje.para(m, todo.copy(puedeEstudiar = OpcionesDelMensaje.tipoDe(m) in deUnaClase)).visibles
            assertTrue("${OpcionesDelMensaje.tipoDe(m)}: ${v.size + 1} entradas ($v)", v.size + 1 <= 8)
        }
    }

    @Test
    fun loDelHiloYElProyectoSoloSiHay() {
        val sin = OpcionesDelMensaje.para(nota)
        assertFalse(Opcion.VER_HILO in sin)
        assertFalse(Opcion.UNIR_AL_PROYECTO in sin)
        val con = OpcionesDelMensaje.para(nota, Contexto(hayHilo = true, puedeUnir = true))
        assertTrue(Opcion.VER_HILO in con.enMas)
        assertTrue(Opcion.UNIR_AL_PROYECTO in con.enMas)
        assertTrue(Opcion.RESCATAR in OpcionesDelMensaje.para(foto.copy(enBuzon = true)).enMas)
    }
}
