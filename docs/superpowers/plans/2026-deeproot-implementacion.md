# Plan DEEPROOT — Implementación y mejora (registro de trabajo)

> Documento de continuidad. Rama: **DEEPROOT**. Repo: `F:\THE FORGE\PIXPIN ANDROID`.
> Objetivo registrado: `goal-138c4dcf-2240-44fb-8bc7-4291cadb588a` (tras un reinicio,
> si el usuario dice "continúa", rearmar con `update_goal action=resume`).

## Estado al último guardado
- WP1 `518b9a2` · WP2+3 `1092bb0` · WP4 `41426b0` · WP6 `1389ff1` · WP7 `c3bec25` ·
  WP8 (lógica) `f8968dc` · WP9a `bdca245` · **WP5 fase-1 `6ca530a`** (empaquetado web perezoso:
  `planoLeido` + `PlanoWeb.aJson` solo al exportar) · **estabilización caché miniaturas
  `71194ce`** (provisional único + `Files.move` atómico; elimina el flake de
  `PdfMiniaturasTest`) — todas pusheadas en `origin/DEEPROOT`.
- **Pendientes**: WP5 fases 2-3 (raster de fondo y `devolverAlPdf` fuera del hilo principal;
  trazo incremental opcional), WP8-UI, WP9b, WP10.
- Verificación manual en dispositivo/navegador pendiente (WP2+3/WP4 y las UI nuevas).

## Notas para retomar WP5 (fases 2-3), WP8-UI, WP9b y WP10
- **WP5**: el intento de delegarlo a un subagente no avanzó (se canceló). Hacerlo con alcance
  mínimo propio y pruebas JVM: (1) `DrawEditorActivity.kt` ~305-309 raster de fondo fuera del
  hilo principal o perezoso; (2) en `traerElPlanoEnLineas` (~3438-3465) construir antes
  `PlanoEnPantalla` y `PlanoWeb.aJson` solo si se va a exportar; (3) `devolverAlPdf` (~587-622)
  en IO. El trazo incremental (elemento en curso fuera de `scene.elements`) es opcional y
  arriesgado: solo si DrawControllerTest sigue verde.
- **WP8-UI**: en `MensajesActivity.Pantalla()` la lista es una `LazyColumn` (~737-865) que
  itera `tramos` (porDias). Tras `visibles`/`tramos`, calcular `rachas = rachasDeOrigen(tramo.mensajes)`
  por tramo y emitir items que colapsen cada racha (≥2) en una fila de miniaturas pequeñas
  (usar las funciones de miniatura existentes a tamaño reducido + «+N»), manteniendo
  `abrir(m)` por miembro; tocar los helpers que asumen 1 item = 1 mensaje:
  `filaDe` ~4798, `cuandoDeLaFila` ~4817, saltos `animateScrollToItem` ~699-727 y el arrastre
  de selección `registrarFranja` ~851-861 (líneas aproximadas; verificar sobre el archivo).
- **WP9a**: `PastillaDeAtajo` (~4030-4058) + `pinear(m)` (~5177-5258) ya existen y `ARCHIVO`
  ya tiene píldora (FilaDeArchivo ~4319). Falta envolver `IMAGEN` (`Miniatura` ~2823/3022) y
  `PAGINA` (`MiniaturaDePagina` ~2830/3386) en fila/píldora superpuesta (cuidar `soloLaFoto`,
  ~2863); `VOZ` ya tiene transcribir.
- **WP9b**: lector PDF ligero al tocar un PDF-ARCHIVO (hoy `abrirFuera` ~5561 = visor externo):
  pantalla nueva usando `PdfDoc.pageCount/render` + caché `PdfMiniaturas`; referencia de rejilla
  `PinWindowController.openPdfViewer`/`PdfViewerContent` (2077-2260); anotar por página con el
  patrón `guardarPagina` (MensajesActivity ~4865-4914).
- **WP10**: gesto 3 dedos arriba dentro de los editores 2D/3D/Markdown → hoja de apuntes
  flotante (block de notas) con ocultar/pegar al lienzo. En 2D el toque 2-3 dedos SIN
  movimiento es undo/redo (`DrawCanvas.kt` 1562-1621); en 3D los 3 dedos ladean cámara (usar
  flick vertical o botón alternativo). v1 = pin "hoja" patrón `OverlayManager.kt:494-509`.

## Entorno verificado
- SDK 36 en `C:\Users\MaxBook\AppData\Local\Android\Sdk`; `local.properties` creado (ignorado).
- Java del sistema es 1.8 → **usar siempre** antes de gradle:
  `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'`
- Credencial GitHub: `credential.helper = store --file=.git/credentials-deeproot`
  (`https://1xmanMAX:<TOKEN>@github.com`). `git push` sin preguntar.
- Node v24 disponible (para smokes JS).
- Receta de tests: `.\gradlew.bat testDebugUnitTest` (con `--tests "com.forge.pixpin.motor.Nombre"` para filtrar; los tests del motor van en el paquete `com.forge.pixpin.motor.*`).

## Estado por WP
- [x] WP0 Entorno + línea base verde.
- [x] **WP1** "Dibujo vacío" al exportar HTML desde lienzo → HECHA (commit `518b9a2`).
- [x] WP2+WP3 Reserva de pintado del plano al mover vista + roundtrip HTML con plano → HECHA (código, `1092bb0`; verificación en navegador pendiente).
- [x] WP4 Medir en el HTML: flechas+cota, movibles, persistentes, varias, imán → HECHA (`41426b0`; verificación en navegador pendiente).
- [ ] WP5 Rendimiento de planos PDF enormes → fase-1 HECHA (`6ca530a`: web perezosa); faltan fases 2-3 (raster de fondo y devolverAlPdf fuera del hilo principal, ~305-309 y ~587-622 de `DrawEditorActivity.kt`; trazo incremental opcional).
- [x] WP6 Compartir: opciones/aviso de audio solo cuando hay audio → HECHA (`1389ff1`).
- [x] WP7 Borrado de hojas/marcos del canvas se refleja en el proyecto → HECHA (`c3bec25`).
- [~] WP8 Agrupación por rachas de origen en Guardar → lógica HECHA (`f8968dc`); falta plegar la UI de la LazyColumn.
- [ ] WP9 Píldoras "hacer pin" en burbujas (IMAGEN/PAGINA; VOZ opcional) + lector PDF ligero.
- [ ] WP10 Hoja de apuntes rápida (block de notas) con 3 dedos en canvas 2D/3D/Markdown.
- [ ] (Descartado por el usuario) Sección-pizarra acumuladora de pines copiados — era para PC.

## WP1 — HECHA (resumen, commit 518b9a2)
- `motor/DrawSvg.kt`: `aTexto()` y `aArchivo()` ya NO tragan excepciones con
  `runCatching{…}.getOrNull()`; `null` queda solo para el vacío real (nada pintable y sin
  papel; caja sin tamaño). Los fallos suben y `DrawEditorActivity.exportando()` los anuncia
  como fallo (con traza), no como «El dibujo está vacío».
- `app/src/test/.../DrawSvgTest.kt`: test de regresión nuevo
  `un fallo al escribir no se anuncia como dibujo vacío`.
- Suite completa verde; commit `518b9a2` pusheado (`origin/DEEPROOT`).

## WP2+WP3 — HECHA (código, commit 1092bb0; verificación manual pendiente)
- **Causa atacada**: al pintar el plano de una hoja oculta (mide 0) se llenaba `pintado` con
  una reserva vacía; al asomarse a la hoja no se repintaba hasta que algo movía la vista → el
  plano salía en blanco al pasar a su página o al reabrir un archivo guardado.
- **Cambio**: `motor/VisorPlano.kt` `pintar()` — si `medir()` devuelve una hoja de menos de 2 px
  (`m.anL<2||m.alL<2`) se deja `pintado=null` y se sale; al hacerse visible la hoja, `ver`
  repinta entero. El parche histórico de pan (reserva de pintado ×MARGEN, `cubierto()` +
  `alSiguienteFotograma()`, ~623-652) ya estaba en HEAD.
- Tests: suite completa verde. **Pendiente de aceptación en navegador/dispositivo**: pasear un
  plano sin huecos; abrir→anotar→guardar→reabrir y ver el plano + trazos.

## Notas para el resto de WPs (anclas de la investigación; leer antes de tocar)
- **WP4** medir en HTML: hoy efímero en grupo `#medida` (ExportarHtml.kt JS 734-763, `medirEn`
  751-762, `medida[]` se resetea al 2º punto 753); lógica numérica en `motor/Medida.kt` (JVM,
  testeada en MedidaTest); escala viaja en `data-escala/data-unidad/data-decimales` (211-215).
  Diseño confirmado por el usuario: flechas con remate + cota con número en medio, varias a la
  vez, arrastrables (extremos/centro) con clic, persistentes al re-guardar (serializar como
  contenido propio —nuevo `serial()` junto a `rayas()`— porque `#medida` no sobrevive), pan de
  la vista clic-arrastrando en zona vacía, e imán a puntos del dibujo/plano.
- **WP5** rendimiento: `DrawController.updateCreating/replace` (2013-2136/3168-3172) copia la
  lista por pointerMove → elemento "en curso" fuera de `scene.elements` (patrón
  `pendingScaleId`/`pendingCotaId`, 1721-1748); `DrawEditorActivity:305-309` raster en hilo
  principal; parse+`PlanoWeb.aJson`+`PlanoEnPantalla` en un job (3438-3465); `devolverAlPdf`/
  `PdfDelProyecto.rehacer` en hilo principal (587-603/41-109).
- **WP6** audio al compartir: `motor/VentanaDeAjustes.kt` `DialogoDeFuncionesWeb` param
  `hayAudio` (default true → false) ~770/805-814; callers `ui/Proyectos.kt:234/2122`,
  `MensajesActivity.kt:429/5397`, `DrawEditorActivity.kt:1542`; aviso `ui/ExportarWebDe.kt:79`
  (añade «· audio:…» solo si `audios>0`); predicado robusto sobre las mismas páginas
  exportadas + existencia/tope del audio (`ExportarProyectoWeb.audioEnDatos` 192-217).
- **WP7** borrado canvas↔proyecto: `Proyectos.sinHoja` (motor/Proyectos.kt:346) sin uso →
  purgar `Hoja(dibujo==id, marco!=null)` sin marco tras guardar; refresco de
  `ui/Proyectos.kt` `PaginaDeProyecto` (589/611-625) con `ExcalidrawStore.revisionDe`;
  `HojasDelProyecto.paginas` (54-113) no debe caer al lienzo entero si falta el marco
  (91-94 y ExportarProyecto.kt:76-95).
- **WP8** agrupación por origen: `Mensaje` (guardados/Mensajes.kt 110-238) sin campo de
  origen; derivar: PAGINA→`"pdf:<ruta>"`, DIBUJO→`"dibujo:<referencia>"`,
  VOZ→`"voz:<proyecto>"`; rachas contiguas ≥2 en función pura (Agrupacion.kt); lista
  LazyColumn (MensajesActivity 737-865) con ítems de grupo de miniaturas pequeñas; helpers
  1-item=1-msg: `filaDe` 4798, `cuandoDeLaFila` 4817, saltos 699-727, `registrarFranja` 851-861.
- **WP9a** píldoras pin: `PastillaDeAtajo` (MensajesActivity 4030-4058) + `pinear(m)`
  (5177-5258) ya universal; falta en IMAGEN (`Miniatura` 2823/3022) y PAGINA
  (`MiniaturaDePagina` 2830/3386); ARCHIVO ya la tiene (FilaDeArchivo 4319-4332); VOZ tiene
  transcribir (FilaDeVoz 3949).
- **WP9b** lector PDF ligero: tocar PDF-ARCHIVO hoy = visor externo (`abrirFuera` 5561).
  Piezas: `PdfDoc.pageCount/render` + caché `PdfMiniaturas`; rejilla del pin
  `PinWindowController.openPdfViewer`/`PdfViewerContent` (2077-2260) como referencia; anotar
  por página con patrón `guardarPagina` (MensajesActivity 4865-4914).
- **WP10** hoja 3 dedos: solo dentro de editores (2D/3D/Markdown). En 2D el toque 2-3 dedos
  SIN movimiento es undo/redo (`DrawCanvas.kt` 1562-1621; un barrido no dispara); en 3D
  3 dedos ladean cámara → flick vertical o botón alternativo; ventana overlay pequeña
  (`OverlayComposeWindow`), ocultar/mostrar, pegar al lienzo (elemento texto vía DrawController)
  y en Markdown al cursor. Decidir con el usuario: v1 = pin "hoja" que abre editor
  (patrón `OverlayManager.kt:494-509`), v2 = ventana pequeña con edición inline.

## Convenciones
- Un commit por WP, mensajes en español sin prefijos tipo "fix:", estilo del repo.
- Lógica nueva que pueda ser pura → al motor/objetos puros con tests JVM; respetar
  `MotorSeparadoTest` (frontera JVM del motor) y los 13 mandamientos del README.
- Push tras cada commit (`git push origin DEEPROOT`; ya trackea origin/DEEPROOT).
