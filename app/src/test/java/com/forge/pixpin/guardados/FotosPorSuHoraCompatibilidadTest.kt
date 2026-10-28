package com.forge.pixpin.guardados

import com.forge.pixpin.sincro.Disco
import com.forge.pixpin.sincro.FotosPorSuHora
import com.forge.pixpin.sincro.Identidad
import com.forge.pixpin.sincro.Aparato
import com.forge.pixpin.sincro.codigo
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Los campos nuevos del PC sobreviven a que el teléfono reescriba el mensaje** (28-oct-2026, «Fotos
 * por su hora, de la PC al móvil y colaboradores» de `docs/manual-sincronizar-lo-nuevo.md` del PC).
 * Una línea escrita por el PC (la nota de una clase con `reloj`, `colaboradores` y sus `capturas` con
 * autor; una foto con `tomadaEn`, `tomadaEnExif`, `desfaseExif`, `paraGrabacion`; la copia de la
 * cámara en directo con `fotoUid`) se lee, se cambia aquí (fijarla, como el usuario) y se vuelve a
 * escribir: todo lo del PC tiene que seguir igual, byte a byte en cada campo.
 */
class FotosPorSuHoraCompatibilidadTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Como las escribe el PC (lo que llega por la sincronización), una por línea. */
    private val delPc = listOf(
        """{"id":"1793178000000","cuando":1793178000000,"clase":"VOZ","ruta":"Clase.m4a","nombre":"Clase.m4a","bytes":3,"duracionMs":100000,"proyecto":"p1","uid":"ABCDEFGHJK","aparato":"PC01","grabacion":"clase","capturas":[{"aparato":"ANA1","horaSinComprobar":true,"id":"1793178020000","ms":20000,"uid":"u-f2"},{"id":"1793178060000","ms":60000}],"reloj":{"aparato":"PC01","fin":1793178130000,"grabacion":"g-7","huecos":[[1793178100000,1793178110000]],"inicio":1793178000000,"pausas":[[1793178040000,1793178070000]],"vistoEn":1793178129000},"colaboradores":["*","K7Q2"]}""",
        """{"id":"1793178020000","cuando":1793178020000,"clase":"IMAGEN","ruta":"f2.jpg","nombre":"f2.jpg","bytes":4,"proyecto":"p1","uid":"u-f2","aparato":"ANA1","tomadaEn":1793178020000,"tomadaEnExif":"2026:10:28 10:05:03","desfaseExif":"+01:00","paraGrabacion":"1793178000000"}""",
        """{"id":"1793178060000","cuando":1793178060000,"clase":"IMAGEN","ruta":"copia.jpg","nombre":"copia.jpg","bytes":4,"proyecto":"p1","aparato":"PC01","enGrabacion":{"de":"1793178000000","ms":60000},"fuente":"movil","deAparato":"Teléfono","tomadaEn":1793178067000,"fotoUid":"toma-1"}"""
    )

    private val nuevos = listOf("reloj", "colaboradores", "capturas", "tomadaEn", "tomadaEnExif", "desfaseExif", "paraGrabacion", "fotoUid", "enGrabacion", "fuente", "deAparato")

    private fun obj(t: String): JsonObject = Json.parseToJsonElement(t).jsonObject

    @Test
    fun `cada campo nuevo del PC va y vuelve al reescribir el mensaje aqui`() {
        for (linea in delPc) {
            val m = json.decodeFromString(Mensaje.serializer(), linea)
            val escrita = json.encodeToString(Mensaje.serializer(), m.copy(fijado = true))
            val antes = obj(linea)
            val despues = obj(escrita)
            for (k in nuevos) assertEquals("«$k» en $linea", antes[k], despues[k])
        }
        // Y el teléfono los entiende.
        val voz = json.decodeFromString(Mensaje.serializer(), delPc[0])
        assertEquals("g-7", RelojDePared.de(voz)!!.grabacion)
        assertEquals(listOf(listOf(1793178100000L, 1793178110000L)), RelojDePared.de(voz)!!.huecos)
        assertTrue(PorSuHora.abiertaATodos(voz))
        assertTrue(PorSuHora.sinComprobar(voz, "1793178020000"))
        assertEquals("ANA1", PorSuHora.autorEnLaNota(voz, "1793178020000"))
        val foto = json.decodeFromString(Mensaje.serializer(), delPc[1])
        assertEquals(1793178020000L, PorSuHora.fotoDe(foto)!!.tomadaEn)
        assertEquals("1793178000000", PorSuHora.fotoDe(foto)!!.para)
    }

    @Test
    fun `el reloj escrito aqui es byte a byte el del PC`() {
        val r = RelojDePared.de(json.decodeFromString(Mensaje.serializer(), delPc[0]).reloj)!!
        assertEquals(obj(delPc[0])["reloj"].toString(), r.aJson().toString())
        // Y el mismo que escribe el diario de la grabación (`RelojDeLaGrabacion.enJson`).
        assertEquals(r.aJson().toString(), RelojDeLaGrabacion.enJson(r).toString())
    }

    @Test
    fun `lo que no es de esto no gana campos al reescribirse`() {
        val nota = """{"id":"n1","cuando":1,"clase":"NOTA","texto":"hola"}"""
        val escrita = json.encodeToString(Mensaje.serializer(), json.decodeFromString(Mensaje.serializer(), nota))
        for (k in listOf("reloj", "colaboradores", "tomadaEnExif", "desfaseExif", "paraGrabacion", "fotoUid")) assertTrue(k, !obj(escrita).containsKey(k))
    }

    @Test
    fun `el repaso en disco del telefono pone lo de un colaborador y lo deja viajar`() {
        val dir = Files.createTempDirectory("por-su-hora").toFile()
        try {
            val disco = Disco(dir)
            disco.identidad.guardar(Identidad(Aparato("id-movil", "Teléfono")))
            val miCodigo = disco.identidad.leer().yo.codigo
            val inicio = 1_793_178_000_000L
            val voz = Mensaje(
                id = "voz1", cuando = inicio, clase = Clase.VOZ, duracionMs = 100_000, proyecto = "p1", grabacion = GrabarClase.CLASE,
                capturas = GrabarClase.capturasEnJson(emptyList()), colaboradores = kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("*"))),
                reloj = RelojDePared(aparato = miCodigo, inicio = inicio, fin = inicio + 100_000).aJson()
            )
            val deAna = Mensaje(id = "f1", cuando = 5, clase = Clase.IMAGEN, ruta = "f1.jpg", proyecto = "p1", uid = "u-f1", aparato = "ANA1", tomadaEn = inicio + 42_000)
            disco.cambiarMensajes { listOf(voz, deAna) }
            assertEquals(mapOf<String?, Int>("p1" to 1), FotosPorSuHora.repasar(disco))
            assertEquals(emptyMap<String?, Int>(), FotosPorSuHora.repasar(disco))
            val leida = disco.leerMensajes().first { it.id == "voz1" }
            assertEquals(listOf(GrabarClase.Enlace(42_000, "f1")), GrabarClase.capturasDe(leida))
            assertTrue(PorSuHora.sinComprobar(leida, "f1"))
            // La de la otra persona no se tocó.
            assertEquals(deAna, disco.leerMensajes().first { it.id == "f1" })
        } finally { dir.deleteRecursively() }
    }
}
