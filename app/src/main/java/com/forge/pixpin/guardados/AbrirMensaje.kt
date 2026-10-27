package com.forge.pixpin.guardados

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Abrir un mensaje en su visor, desde donde sea** (27-oct-2026). Es lo que hace tocar una burbuja
 * del chat —un dibujo en el lienzo, un PDF en su lector, un Excel en el visor de hojas, un vídeo en
 * el suyo, una carpeta en la suya…—, sacado de `MensajesActivity.abrir` tal cual para que **la
 * búsqueda de la lista de chats abra lo encontrado con la misma regla** y no con una copia que se
 * quede atrás el día que se añada un visor. El usuario: «al tocar un PDF, que lo abra
 * directamente». Ver [BuscarEnChats.alTocar] para cuándo la búsqueda abre y cuándo lleva al mensaje.
 *
 * Lo que se abre lo arranca [actividad], así que su `startActivity` decide la vuelta: el chat marca
 * de qué chat viene ([com.forge.pixpin.EXTRA_DESDE_CHAT]) y la lista de chats que viene de la lista
 * ([com.forge.pixpin.Volver.DESDE_LA_LISTA]). Atrás desde el visor vuelve a donde se tocó.
 */
object AbrirMensaje {

    /**
     * Abre [m] donde vive. [chatActual]: el chat desde el que se toca (null en la general o fuera de
     * un chat): un proyecto adjunto que es este mismo chat no se vuelve a abrir encima.
     */
    fun abrir(actividad: ComponentActivity, m: Mensaje, chatActual: String? = null) {
        val almacen = MensajesStore(actividad)
        with(actividad) {
            when (m.clase) {
                Clase.VOZ -> m.ruta?.let { Reproductor.alternar(it, tituloDeAudio(this, m)) }
                // Una nota con un enlace dentro se abre en el navegador al tocarla: es lo que
                // espera cualquiera que se manda una dirección a sí mismo para leerla luego.
                // Sin enlace no hay nada que abrir, y tocarla no hace nada — que es correcto:
                // el texto ya está entero delante.
                // **La nota de un proyecto se abre en su editor**: es la hoja, y lo que se escriba
                // vuelve al proyecto y de ahí a este mensaje. Ver [ChatDeLosProyectos].
                Clase.NOTA -> {
                    val hoja = m.referencia?.let { ref ->
                        (application as? PixPinApp)?.proyectos?.porId(m.proyecto)?.hojas?.firstOrNull { it.id == ref && it.nota != null }
                    }
                    if (hoja != null) com.forge.pixpin.ui.MarkdownEditorActivity.abrir(this, hoja.id, hoja.nota!!)
                    else primerEnlace(m.texto)?.let { abrirEnlace(actividad, it.url) }
                }
                // Una página se abre **en el editor, sobre su PDF**: es donde se anotó y
                // donde se sigue anotando. Abrir el PDF a secas perdería lo dibujado.
                Clase.PAGINA -> {
                    val pdf = m.ruta
                    val pagina = m.pagina
                    val dibujo = m.referencia
                    if (pdf != null && pagina != null && dibujo != null) {
                        com.forge.pixpin.motor.DrawEditorActivity.abrirPaginaDePdf(
                            this, dibujo,
                            com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, dibujo),
                            pdf, pagina
                        )
                    }
                }
                // **Un proyecto adjunto abre su chat** (26-oct-2026, sin pantalla de Proyectos: un
                // proyecto es su chat). Encima de este, así que atrás vuelve aquí. Si ya no existe
                // (borrado en otro aparato), se dice.
                Clase.PROYECTO -> {
                    val p = (application as? PixPinApp)?.proyectos?.porId(m.referencia)
                    if (p == null) Toast.makeText(this, com.forge.pixpin.R.string.guardados_sin_proyecto, Toast.LENGTH_SHORT).show()
                    else if (p.id != chatActual) MensajesActivity.abrirChatDe(this, p.id, p.nombre)
                }
                Clase.MINIAPP ->
                    if (CarpetaDePaginas.esCarpeta(m)) CarpetaActivity.abrir(this, m.id)
                    else if (TablasDelChat.esTabla(m)) {
                        // **Una tabla del chat se ve en el visor de hojas, sin editar** (25-oct-2026, el
                        // usuario: «quita la visualización de Excel antigua»); se anota con el riel. Una hoja
                        // de un libro, con sus hermanas en pestañas, como el Excel. Ver [com.forge.pixpin.excel.EntradaDeHojas].
                        val libro = m.referencia?.let { TablasDelChat.partirLibro(it) }?.first
                        val ref = m.referencia
                        if (libro != null && ref != null) lifecycleScope.launch {
                            val hojas = withContext(Dispatchers.IO) { TablasDelChat.hechas(almacen.leer(), libro).map { LibroDelChat.idDeHoja(libro, it.first) } }
                            com.forge.pixpin.excel.ExcelActivity.abrirDestino(actividad, com.forge.pixpin.excel.EntradaDeHojas.destino(ref, hojas))
                        }
                        else com.forge.pixpin.excel.ExcelActivity.abrirDestino(this, com.forge.pixpin.excel.EntradaDeHojas.destino(TablasDelChat.idDe(m)))
                    }
                    else com.forge.pixpin.mini.MiniActivity.abrir(this, m.id)
                Clase.TABLA -> m.referencia?.let { com.forge.pixpin.excel.ExcelActivity.abrirDestino(this, com.forge.pixpin.excel.EntradaDeHojas.destino(it)) }
                Clase.CROQUIS -> m.referencia?.let {
                    com.forge.pixpin.croquis3d.Croquis3DActivity.abrir(this, m.proyecto, it)
                }
                Clase.DIBUJO -> m.referencia?.let { dibujo ->
                    com.forge.pixpin.motor.DrawEditorActivity.abrir(
                        this, dibujo,
                        com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, dibujo), null
                    )
                }
                // **Una foto se abre en el editor, no en un visor.**
                //
                // Aquí una foto casi nunca es un recuerdo: es una captura, un recibo o una
                // pizarra que se guardó para señalar algo encima. Abrirla en la galería
                // obliga a exportar, editar fuera y volver a guardar, y lo que vuelve ya es
                // otra cosa distinta de la que estaba aquí.
                //
                // La primera vez se le asigna un dibujo y **se anota en el propio mensaje**:
                // así la segunda vez no se empieza de cero, se sigue donde se dejó. Sin ese
                // apunte, cada apertura sería una hoja en blanco sobre la misma foto.
                Clase.IMAGEN -> m.ruta?.let { foto ->
                    val yaTenia = m.referencia
                    // El mismo dibujo que la hoja del proyecto. Ver [dibujoDeLaFoto].
                    val dibujo = yaTenia ?: m.dibujoDeLaFoto
                    if (yaTenia == null) {
                        // El apunte se guarda **después de abrir el editor**, no antes: leer
                        // y reescribir el archivo entero aquí dejaba el toque congelado justo
                        // en el gesto que tiene que responder al momento. Que el apunte tarde
                        // dos décimas no lo nota nadie; que el editor tarde en abrirse, sí.
                        lifecycleScope.launch(Dispatchers.IO) {
                            almacen.cambiar { l ->
                                l.map { if (it.id == m.id) it.copy(referencia = dibujo) else it }
                            }
                        }
                    }
                    com.forge.pixpin.motor.DrawEditorActivity.abrir(
                        this, dibujo,
                        com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, dibujo),
                        if (yaTenia == null) foto else null
                    )
                }
                // **Un PDF se ve aquí dentro.** Salía con `ACTION_VIEW` a otra aplicación, y
                // volver costaba deshacer el camino. Ver [com.forge.pixpin.pdf.LectorPdfActivity].
                // Lo que no sea un PDF sigue saliendo fuera: para eso están las otras aplicaciones.
                else -> m.ruta?.let { ruta ->
                    when {
                        // Una lección abre su ficha, no un visor de archivos. Ver [com.forge.pixpin.lecciones.LeccionesStore].
                        com.forge.pixpin.lecciones.LeccionesStore.esLeccion(m) ->
                            com.forge.pixpin.lecciones.LeccionActivity.abrir(this, File(ruta).name.removeSuffix(com.forge.pixpin.lecciones.LeccionesStore.EXTENSION))
                        ruta.substringAfterLast('.', "").equals("pdf", ignoreCase = true) ->
                            com.forge.pixpin.pdf.LectorPdfActivity.abrir(this, ruta, m.nombre)
                        // **Un vídeo se ve aquí dentro** (26-oct-2026): el de una grabación del PC o del
                        // móvil, o cualquier otro. Ver [VisorDeVideoActivity].
                        SeccionesDelChat.esVideo(m) && VisorDeVideoActivity.seVeAqui(ruta) ->
                            VisorDeVideoActivity.abrir(this, ruta, m.nombre.ifBlank { File(ruta).name })
                        // **Un IFC o un Revit, en el visor 3D** (10-oct-2026, pedido por el usuario: «ábrelos
                        // como en Windows, en una app separada, que en el canvas 3D se ve descolocado»). Es
                        // la lectura del PC (ifc-lite y rvt-rs, en el proceso de los planos), la misma que
                        // en Windows. Desde el visor se puede llevar al croquis. Ver [com.forge.pixpin.planos.Visor3DActivity].
                        com.forge.pixpin.planos.LectorDePlanos.esDelVisor3D(m.nombre) || com.forge.pixpin.planos.LectorDePlanos.esDelVisor3D(ruta) ->
                            com.forge.pixpin.planos.Visor3DActivity.abrir(this, ruta, m.nombre.ifBlank { File(ruta).name })
                        // **Un OBJ abre el croquis en el espacio** (21-sep-2026): el visor 3D no lee OBJ.
                        // Ver [com.forge.pixpin.croquis3d.Croquis3DActivity.abrirConModelo].
                        com.forge.pixpin.motor.LectorObj.esObj(m.nombre) || com.forge.pixpin.motor.LectorObj.esObj(ruta) ->
                            com.forge.pixpin.croquis3d.Croquis3DActivity.abrirConModelo(
                                this, File(ruta), m.nombre.ifBlank { File(ruta).name }
                            )
                        // **Un plano DWG o DXF se ve aquí dentro, a pantalla completa** (9-oct-2026): la
                        // lectura del visor de planos del PC, y el dibujo con la tarjeta gráfica.
                        // Ver [com.forge.pixpin.planos.PlanoActivity].
                        com.forge.pixpin.planos.PlanoActivity.esPlano(m.nombre) || com.forge.pixpin.planos.PlanoActivity.esPlano(ruta) ->
                            com.forge.pixpin.planos.PlanoActivity.abrir(this, ruta, m.nombre.ifBlank { File(ruta).name })
                        // **Civil 3D: un LandXML o un fichero de puntos** (PNEZD, ENZ…), mirando dentro: al visor 3D.
                        com.forge.pixpin.planos.LectorDePlanos.esDeCivil(ruta, m.nombre.ifBlank { null }) ->
                            com.forge.pixpin.planos.Visor3DActivity.abrir(this, ruta, m.nombre.ifBlank { File(ruta).name })
                        // **Un Word se lee aquí dentro** (19-sep-2026): se saca lo que dice —texto, tablas
                        // e imágenes, sin la maquetación de la página— a un HTML que enseña el mismo visor
                        // de las páginas web. Convertir es abrir un ZIP y leer XML: fuera del hilo que
                        // pinta. Va antes que la rama del HTML para que mande el nombre del mensaje.
                        // Ver [com.forge.pixpin.motor.DocxAHtml].
                        // **Un libro (`.epub`) se lee aquí dentro** (19-sep-2026): se desempaqueta en su
                        // carpeta de la caché —capítulos en una sola página, con sus imágenes al lado— y
                        // se enseña en el mismo visor, **donde está** y sin JavaScript. Ver
                        // [com.forge.pixpin.motor.EpubAHtml].
                        com.forge.pixpin.motor.EpubAHtml.esEpub(m.nombre) ||
                            com.forge.pixpin.motor.EpubAHtml.esEpub(ruta) -> lifecycleScope.launch {
                            val titulo = m.nombre.ifBlank { File(ruta).name }
                            val hecho = withContext(Dispatchers.IO) {
                                runCatching {
                                    val original = File(ruta)
                                    val huella = "$ruta|${original.lastModified()}|${original.length()}".hashCode()
                                    val raiz = File(cacheDir, "visor-epub")
                                    val carpeta = File(raiz, huella.toString())
                                    val pagina = File(carpeta, "pagina.html")
                                    if (!pagina.exists() || pagina.length() == 0L) {
                                        // Caché de usar y tirar: solo se guarda el último libro.
                                        raiz.listFiles()?.forEach { if (it != carpeta) it.deleteRecursively() }
                                        com.forge.pixpin.motor.EpubAHtml.convertir(original, carpeta, titulo.substringBeforeLast('.'))
                                    } else pagina
                                }
                            }
                            hecho.onSuccess { pagina ->
                                com.forge.pixpin.ui.VisorHtmlActivity.abrir(
                                    actividad, pagina.absolutePath, titulo,
                                    comparte = ruta, sinGuion = true, mensaje = m.id, enSuSitio = true, documento = true
                                )
                            }.onFailure { e ->
                                Toast.makeText(
                                    actividad,
                                    (e as? com.forge.pixpin.motor.EpubAHtml.NoSeLee)?.message ?: "No se pudo leer el libro",
                                    Toast.LENGTH_LONG
                                ).show()
                                abrirFuera(ruta)
                            }
                        }
                        // **Un Word o un PowerPoint, maquetado como en Office, en el lector de PDF**
                        // (11-oct-2026, lo del PC): páginas y diapositivas con su tinta, marcadores y
                        // aumento nítido. Sin el .so, o si no se lee, lo de antes. Ver [com.forge.pixpin.office.AbrirOffice].
                        com.forge.pixpin.office.AbrirOffice.seMaqueta(m.nombre, ruta) ->
                            com.forge.pixpin.pdf.LectorPdfActivity.abrir(this, ruta, m.nombre.ifBlank { File(ruta).name }, mensaje = m.id)
                        com.forge.pixpin.motor.DocxAHtml.esDocx(m.nombre) ||
                            com.forge.pixpin.motor.DocxAHtml.esDocx(ruta) ->
                            com.forge.pixpin.office.AbrirOffice.wordComoTexto(this, ruta, m.nombre, m.id) { abrirFuera(it) }
                        // **Una página web se ve aquí dentro también** (19-sep-2026): casi siempre es
                        // una «Página web» exportada por PixPin. Ver [com.forge.pixpin.ui.VisorHtmlActivity].
                        com.forge.pixpin.ui.VisorHtmlActivity.esHtml(ruta) ||
                            com.forge.pixpin.ui.VisorHtmlActivity.esHtml(m.nombre) ->
                            com.forge.pixpin.ui.VisorHtmlActivity.abrir(this, ruta, m.nombre, mensaje = m.id)
                        // **Un PowerPoint sin maquetar se convierte en proyecto** (14-sep-2026): una hoja por
                        // diapositiva. Ver [com.forge.pixpin.office.AbrirOffice.diapositivasAProyecto].
                        com.forge.pixpin.motor.Diapositivas.esPresentacion(m.nombre) ||
                            com.forge.pixpin.motor.Diapositivas.esPresentacion(ruta) ->
                            com.forge.pixpin.office.AbrirOffice.diapositivasAProyecto(this, ruta, m.nombre)
                        // **Un Excel se ve como en Excel** (11-oct-2026, lo del PC): estilos, formatos de
                        // número y fecha, combinaciones, paneles… en el visor de hojas, que lo abre al
                        // instante. **Y todo lo demás también** (25-oct-2026, el usuario: «quita la
                        // visualización de Excel antigua y todo lo incorporas en la nueva»): un `.xls`, un
                        // `.ods`, un `.csv` o un libro con hojas editadas se ven por sus tablas en el mismo
                        // visor, solo para mirar y anotar; ya no se abre la vista de Tablas. El visor decide
                        // cómo y, si no se deja leer, lo manda a otra aplicación. Ver [com.forge.pixpin.excel.EntradaDeHojas].
                        com.forge.pixpin.excel.EntradaDeHojas.esHojaDeCalculo(m.nombre, ruta) ->
                            com.forge.pixpin.excel.ExcelActivity.abrir(this, ruta, m.nombre.ifBlank { File(ruta).name }, mensaje = m.id)
                        // **Un `.md`, un `.txt`, un `.log`… se lee aquí dentro** (27-oct-2026, el usuario: «no
                        // puedo abrir archivos Markdown»). No tenían rama y caían en `abrirFuera`: a otra
                        // aplicación, y casi ningún teléfono trae una para Markdown. Ahora, como en el PC, al
                        // editor de notas: el Markdown compuesto y lo demás tal cual, con la pastilla y el riel
                        // (marcador, lápiz, marcos) sobre la nota `md-<mensaje>`, que es con la que viaja lo
                        // anotado. Solo para mirar: lo escrito no tendría adónde volver sin cambiar el
                        // archivo del mensaje. Un `.txt` de puntos de Civil 3D ya se fue arriba, al visor 3D.
                        // Ver [FormatosDeTexto].
                        FormatosDeTexto.delMensaje(m.nombre, ruta) != null ->
                            com.forge.pixpin.ui.MarkdownEditorActivity.abrirArchivo(
                                this, ruta, m.nombre.ifBlank { File(ruta).name }, idDeLaNota(m),
                                llano = FormatosDeTexto.delMensaje(m.nombre, ruta) == FormatosDeTexto.Como.LLANO,
                                soloLeer = true
                            ).let { abierto -> if (!abierto) abrirFuera(ruta) }
                        else -> abrirFuera(ruta)
                    }
                }
            }
        }
    }

    /**
     * **La nota con que se abre un adjunto de texto** en el editor: `md-<mensaje>`, la misma que usa
     * lo abierto de paso ya guardado ([AbrirDePaso.idDeLaNota]). Con ella el editor encuentra el
     * código del mensaje ([com.forge.pixpin.ui.ComentariosDeNota.uidDeLaNota]) y lo anotado viaja.
     */
    fun idDeLaNota(m: Mensaje) = "md-${m.id}"

    /** Un APK se instala; lo demás, con la aplicación que se elija. Ver [com.forge.pixpin.ui.AbrirCon]. */
    private fun ComponentActivity.abrirFuera(ruta: String) {
        com.forge.pixpin.ui.AbrirCon.abrir(this, File(ruta))
    }

    /** Un enlace: los de PixPin aquí dentro ([com.forge.pixpin.ui.EnlacesPixpin]); los demás, en el navegador. */
    fun abrirEnlace(actividad: ComponentActivity, url: String) {
        if (com.forge.pixpin.ui.EnlacesPixpin.abrir(actividad, url)) return
        val abierto = runCatching {
            actividad.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.isSuccess
        if (!abierto) {
            Toast.makeText(actividad, com.forge.pixpin.R.string.guardados_ya_no_esta, Toast.LENGTH_SHORT).show()
        }
    }
}
