package com.forge.pixpin

import com.forge.pixpin.atajos.Atajos
import com.forge.pixpin.atajos.BuscarEnTodo
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.lecciones.Leccion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuscarEnTodoTest {
    private val mensajes = listOf(
        Mensaje(id = "1", cuando = 10, clase = Clase.NOTA, texto = "Revisar el hormigón de la losa", proyecto = "p1"),
        Mensaje(id = "2", cuando = 20, clase = Clase.ARCHIVO, ruta = "/x/guardados/informe (1).pdf", nombre = "informe (1).pdf", proyecto = "p1"),
        Mensaje(id = "3", cuando = 30, clase = Clase.ARCHIVO, ruta = "/x/guardados/lecciones/abc.leccion", nombre = "💡 Medir dos veces", texto = "💡 Lección: Medir dos veces"),
        Mensaje(id = "4", cuando = 40, clase = Clase.MINIAPP, miniapp = "tareas", texto = "# Compras de obra\n- [ ] cemento")
    )
    private val todo = BuscarEnTodo.elementos(mensajes, listOf("p1" to "Tesis"))

    @Test fun laPDeDelanteNoCuenta() {
        assertEquals("tesis", BuscarEnTodo.sinLaP("p tesis"))
        assertEquals("P tesis".substring(2), BuscarEnTodo.sinLaP("P tesis"))
        assertEquals("pilar", BuscarEnTodo.sinLaP("pilar"))
        val r = BuscarEnTodo.buscar(todo, "P tesis")
        assertEquals(BuscarEnTodo.Tipo.PROYECTO, r.first().tipo)
    }

    @Test fun sinAcentosYTodasLasPalabras() {
        val r = BuscarEnTodo.buscar(todo, "hormigon losa")
        assertEquals(listOf("1"), r.mapNotNull { it.mensaje?.id })
        assertTrue(BuscarEnTodo.buscar(todo, "hormigon tejado").isEmpty())
    }

    @Test fun encuentraFuncionesPorSusOtrasPalabras() {
        val r = BuscarEnTodo.buscar(todo, "galeria")
        assertEquals(Atajos.CAPTURAS, r.first().accion)
        assertEquals(Atajos.GRABAR, BuscarEnTodo.buscar(todo, "voz").first().accion)
    }

    @Test fun leccionesArchivosYTareasConSuTipo() {
        val leccion = BuscarEnTodo.buscar(todo, "medir").first()
        assertEquals(BuscarEnTodo.Tipo.LECCION, leccion.tipo)
        assertEquals("abc", leccion.id)
        assertEquals(BuscarEnTodo.Tipo.ARCHIVO, BuscarEnTodo.buscar(todo, "informe").first().tipo)
        val t = BuscarEnTodo.buscar(todo, "cemento").first()
        assertEquals(BuscarEnTodo.Tipo.TAREAS, t.tipo)
        assertEquals("Compras de obra", t.titulo)
        assertEquals("Tesis", BuscarEnTodo.buscar(todo, "informe").first().detalle)
    }

    @Test fun laLeccionGuardaSusAdjuntosYLasViejasSeLeen() {
        val vieja = Leccion.leer("""{"id":"a","creada":1,"titulo":"x"}""")!!
        assertTrue(vieja.adjuntos.isEmpty())
        val con = Leccion.leer(Leccion.escribir(vieja.copy(adjuntos = listOf("m1", "m2"))))!!
        assertEquals(listOf("m1", "m2"), con.adjuntos)
    }

    @Test fun elChatNoEnsenaLasLeccionesNiSusAdjuntos() {
        val foto = Mensaje(id = "5", cuando = 50, clase = Clase.IMAGEN, ruta = "/x/guardados/f.jpg", respondeA = "3")
        val otra = Mensaje(id = "6", cuando = 60, clase = Clase.NOTA, texto = "hola", respondeA = "1")
        val quedan = com.forge.pixpin.lecciones.LeccionesStore.sinLecciones(mensajes + foto + otra).map { it.id }
        assertEquals(listOf("1", "2", "4", "6"), quedan)
    }
}
