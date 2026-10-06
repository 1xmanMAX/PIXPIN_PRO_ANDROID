package com.forge.pixpin

import com.forge.pixpin.atajos.Atajos
import com.forge.pixpin.atajos.BuscarEnTodo
import com.forge.pixpin.atajos.BuscarEnTodo.Pestana
import com.forge.pixpin.atajos.RecientesDelBuscador
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Buscar en PixPin» v2: recientes y pestañas, como el PC (0c6972b). */
class BuscarV2Test {
    private val mensajes = listOf(
        Mensaje(id = "1", cuando = 10, clase = Clase.NOTA, texto = "Revisar el hormigón de la losa", proyecto = "p1"),
        Mensaje(id = "2", cuando = 20, clase = Clase.ARCHIVO, ruta = "/x/guardados/losa.pdf", nombre = "losa.pdf", proyecto = "p1"),
        Mensaje(id = "3", cuando = 30, clase = Clase.ARCHIVO, ruta = "/x/guardados/lecciones/abc.leccion", nombre = "💡 La losa se mide dos veces", texto = "💡 Lección: losa"),
        Mensaje(id = "4", cuando = 40, clase = Clase.MINIAPP, miniapp = "tareas", texto = "# Losa\n- [ ] cemento")
    )
    private val todo = BuscarEnTodo.elementos(mensajes, listOf("p1" to "Tesis"))

    @Test fun lasBusquedasVanPrimeroSinRepetirseYConTope() {
        var r = RecientesDelBuscador()
        r = r.conBusqueda("grieta").conBusqueda("  factura   temu ").conBusqueda("GRIETA")
        assertEquals(listOf("GRIETA", "factura temu"), r.busquedas)
        for (i in 0 until 20) r = r.conBusqueda("cosa $i")
        assertEquals(RecientesDelBuscador.BUSQUEDAS, r.busquedas.size)
        assertEquals("cosa 19", r.busquedas[0])
        assertEquals("cosa 18", r.sinBusqueda(0).busquedas[0])
        // Caso negativo: una letra sola o vacío no se apunta; quitar fuera de rango no rompe.
        assertEquals(r, r.conBusqueda("t").conBusqueda("   ").sinBusqueda(99))
        assertTrue(r.sinBusquedas().busquedas.isEmpty())
    }

    @Test fun loAbiertoVuelveIgualTrasGuardarYLeerYSinRepetir() {
        val losa = BuscarEnTodo.buscar(todo, "losa pdf").first()
        val tesis = BuscarEnTodo.buscar(todo, "tesis").first { it.tipo == BuscarEnTodo.Tipo.PROYECTO }
        val r = RecientesDelBuscador().conAbierto(losa).conAbierto(tesis).conAbierto(losa).conBusqueda("muro")
        val leido = RecientesDelBuscador.leer(r.aJson())
        assertEquals(r, leido)
        assertEquals(listOf(losa, tesis), leido.abiertosEn(todo))
    }

    @Test fun loQueYaNoEstaNoSaleYUnArchivoRotoEsUnaListaVacia() {
        val losa = BuscarEnTodo.buscar(todo, "losa pdf").first()
        val r = RecientesDelBuscador().conAbierto(losa)
        val sinElMensaje = BuscarEnTodo.elementos(mensajes.filter { it.id != "2" }, listOf("p1" to "Tesis"))
        assertTrue(r.abiertosEn(sinElMensaje).isEmpty())
        assertEquals(RecientesDelBuscador(), RecientesDelBuscador.leer("{ esto no es json"))
        assertEquals(RecientesDelBuscador(), RecientesDelBuscador.leer(null))
    }

    @Test fun loQueCreaAlgoNoSeRecuerdaComoAbierto() {
        val nueva = BuscarEnTodo.FUNCIONES.first { it.accion == Atajos.TAREA }
        assertTrue(RecientesDelBuscador().conAbierto(nueva).abiertos.isEmpty())
        // Y lo que sí es una cosa, sí.
        val galeria = BuscarEnTodo.FUNCIONES.first { it.accion == Atajos.CAPTURAS }
        assertEquals(1, RecientesDelBuscador().conAbierto(galeria).abiertos.size)
    }

    @Test fun cadaCosaVaASuPestanaYLasCuentasCuadran() {
        val r = BuscarEnTodo.buscar(todo, "losa")
        val c = BuscarEnTodo.cuentas(r)
        assertEquals(r.size, c[Pestana.TODO])
        assertEquals(r.size, Pestana.entries.filter { it != Pestana.TODO }.sumOf { c.getValue(it) })
        assertEquals(1, c[Pestana.TAREAS]); assertEquals(1, c[Pestana.LECCIONES])
        assertEquals(
            listOf(BuscarEnTodo.Tipo.TAREAS),
            BuscarEnTodo.lineas(r, Pestana.TAREAS).map { (it as BuscarEnTodo.Elemento).tipo }
        )
        // Caso negativo: en una pestaña sin nada, no sale nada.
        assertTrue(BuscarEnTodo.lineas(r, Pestana.ACCIONES).isEmpty())
    }

    @Test fun enTodoElMejorVaArribaYLuegoPorGrupos() {
        val r = BuscarEnTodo.buscar(todo, "losa")
        val l = BuscarEnTodo.lineas(r, Pestana.TODO)
        assertEquals(BuscarEnTodo.Grupo.MEJOR, l[0])
        assertEquals(r[0], l[1])
        val grupos = l.filterIsInstance<BuscarEnTodo.Grupo>()
        assertEquals(grupos.sortedBy { it.ordinal }, grupos)
        assertEquals(r.size, l.count { it is BuscarEnTodo.Elemento })
        assertTrue(BuscarEnTodo.lineas(emptyList(), Pestana.TODO).isEmpty())
    }

    @Test fun conLaCajaVaciaCadaPestanaEnsenaLoSuyoLoMasRecientePrimero() {
        val archivos = BuscarEnTodo.deLaPestana(todo, Pestana.ARCHIVOS)
        assertTrue(archivos.isNotEmpty() && archivos.all { BuscarEnTodo.pestanaDe(it.tipo) == Pestana.ARCHIVOS })
        assertEquals(archivos.sortedByDescending { it.cuando }, archivos)
        assertEquals(BuscarEnTodo.FUNCIONES.size, BuscarEnTodo.deLaPestana(todo, Pestana.ACCIONES).size)
        assertTrue(BuscarEnTodo.deLaPestana(todo, Pestana.TODO).isEmpty())
    }
}
