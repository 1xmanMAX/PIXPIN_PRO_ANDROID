# Lo del PC del 15 al 24-oct, en Android (v0.127.0)

Del PC (`1xmanMAX/PIXPIN_PRO_WINDOWS`, 158 commits entre el 15 y el 24-oct) y del pedido del
usuario del 24-oct: «que la previsualización tenga una barra superior y una barra lateral; en la
lateral siempre tres opciones —el frame, el lápiz y los marcadores—, en todos los formatos».
La guía del PC para Android es su `docs/sincronizar-marcos-vistas-y-tinta.md`.
**Nada de esto se ha probado en un teléfono**: compila y pasan las pruebas JVM.

## El riel de la derecha, el mismo en todos los visores

`ui/RielDelVisor.kt`: la isla de cristal oscuro del PC con **marcador** (`BookmarkAdd`), **lápiz**
(`Edit`) y **marco para imprimir** (`CropFree`), lo puesto en azul `#0060DF`; mantener el dedo dice
su nombre. Debajo, los puntos de los marcadores (`RielDeMarcas`). El lápiz y el marcador salen de la
pastilla de arriba, que se queda con lo propio de cada formato.

| Visor | Marcador | Lápiz | Marco |
|---|---|---|---|
| Lienzo | ✓ (como antes) | **Abre siempre mirando**; el lápiz pasa a editar con la barra entera y atrás vuelve a mirar | ✓ coge la herramienta Marco (fuera de la barra de arriba, como el PC) |
| PDF y Word/PowerPoint por hojas | ✓ y **con su vista** (zoom y sitio, `.vistas.json`) | ✓ (anotar) | ✓ |
| Word y libros (lectura), PDF como texto | ✓ | ✓ | ✓ |
| Página web (HTML) del chat | ✓ (nuevo, `anot-<uid>.marcas` en fracción) | — | ✓ |
| Excel | ✓ (nuevo: `id:columna:hoja·10⁷+fila:emoji`, con vista) | ✓ (nuevo: tinta por hoja, `anot-<uid>-h<i>`) | ✓ (`parte` = hoja) |
| Plano DWG/DXF | ✓ (nuevo: la vista entera, `Marcador2d`) | ✓ (el anotar de antes) | ✓ (los marcos del plano de antes) |
| Modelo 3D (IFC/Revit) | ✓ (nuevo: la cámara y el corte, `Marcador3d`) | — | — |
| Notas | ✓ (fracción) | **Abre mirando**; el lápiz pasa a escribir | — |
| Pin de imagen flotante | — | el mismo icono `Edit` | — |

## Marcos numerados (`motor/MarcosDelDocumento.kt`, `ui/CapaDeMarcos.kt`)

La lógica de `marcos_doc.rs`: arrastrar más de 8 px pone uno al final; su número es su sitio;
«Quitar el último», «Borrar», «Imprimir (n)»; fichas 1, 2, 3… abajo a la izquierda que llevan a cada
uno; raya azul `#0078D4` y el número blanco en su cuadrito. Imprimir se acerca a cada marco hasta
llenar la pantalla, lo fotografía sin interfaz y manda una hoja por marco al diálogo del sistema.

- Se pintan **con el documento en el mismo fotograma** (en el PDF, con el aumento y el desplazamiento
  que se leen al pintar; en Excel, los pinta la propia hoja), así no se quedan atrás al moverlo.
- Viajan: `anot-<uid>.marcos.json` y `anot-<uid>.vistas.json` entran en `TERMINACIONES`. Vacíos se
  escriben `[]`/`{}`, por un temporal y solo si cambian.
- Unidades del PC: PDF en las del lector (1400 de ancho por hoja, 18 de hueco:
  `MarcosDelDocumento.ColumnaDeHojas`), web en píxeles CSS, Excel en píxeles de la hoja al 100 %.
- En el lienzo los marcos son los de siempre (elementos `frame`), ahora a trazos azules de grosor fijo
  con su número, pintados con la escena.

## Herramientas por formato

`HerramientasPorSitio`: tres sitios nuevos, **PDF**, **Word y libros** y **Excel**, que llevan la
barra del lector hasta que se tocan (`formato_<sitio>_tools/_groups`). En Ajustes → Herramientas:
dónde.

## Lo que el PC escribe y aquí se conserva

| Del PC | En Android |
|---|---|
| Texto rico, `cierreArco`, `marcaAngulo`, `degradado`, `trazoRayaPunto` en los lienzos | Campos de `Element`: se leen y se reescriben tal cual (aún no se pintan; se ve la reserva) |
| Grabar clase: `capturas`, `grabacion`, `enGrabacion` | Campos de `Mensaje`, conservados |
| Galería: el `texto` OCR de cada captura | `GaleriaCompartida.Entrada.texto`; al juntar no se olvida |
| Un solo chat Asistente | Con varios marcados, el más antiguo (`creado`, id), el mismo que elige el PC |

## Más del PC

| Del PC | En Android |
|---|---|
| Tareas inteligentes (`pixpin-frase/src/tarea.rs`, 17/18-oct): urgencia ⏫/🔼, `#etiquetas`, nota tras 💬, ⏰ y ➕, fechas como se dicen | `mini/TareaInteligente.kt` (con sus pruebas, las mismas del PC) y `ChapitasDeTarea.kt`: las listas, el widget y los avisos enseñan el título limpio con chapitas; lo escrito se guarda como lo guarda el PC. `ia/Fechas.kt` al día con el `fechas.rs` del 18-oct («5pm», «el 20») |
| Lo grabado bajito se oye más alto (`pixpin-audio/src/volumen.rs`, cd0cc30) | `audio/RealceDeVolumen.kt` (las cuentas) y `RealceAlOir.kt` (un `LoudnessEnhancer` con esa ganancia) en el reproductor del chat, las notas, las lecciones y el pin |

## Lo que no, y por qué

- **Tinta en páginas web y en el 3D, marcos en el 3D y en las notas**: el PC los hace dentro de
  WebView2 o con su cámara 3D; aquí faltan la capa de tinta en esas vistas y la foto del 3D.
- **El riel junto al pin de imagen flotante** (lápiz y marco en una columna aparte): el pin es una
  ventana superpuesta; se igualó el icono del lápiz.
- **Aislar y ocultar por clave estable en los marcadores 3D** (`aislado_clave`, `n:=`): se conserva
  lo que trae el PC, pero este teléfono aún no lo aplica.
- **Figuras de serie** (266 plantillas y símbolos): llegan ya en `figuras.json` al sincronizar; la
  ventana de figuras con previsualizador del PC no se portó.
- **Palabras de urgencia a gusto y vista Kanban de las tareas**: Android no tiene Ajustes → Tareas.
- **Asistente** (orquesta, revisión con rúbrica, memoria, seguir con el chat cerrado, Drive,
  Calendar), **horario semanal**, **Anki** y **calcar**: o son del PC o necesitan más que un porte.
- Lo del escritorio: WebView2, Office por COM, MCP, Flow Launcher, extensión del navegador, grabar el
  sonido del equipo, MSIX, el corrector de Windows.

## Sin probar

Todo lo de la interfaz: el riel, los marcos y su impresión (PixelCopy), la tinta de Excel, los
marcadores de planos y 3D, y que un marco puesto aquí caiga en el mismo sitio en el PC.
