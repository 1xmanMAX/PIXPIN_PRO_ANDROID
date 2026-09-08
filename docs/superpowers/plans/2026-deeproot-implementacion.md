# Plan DEEPROOT — Implementación y mejora (registro de trabajo)

> Documento de continuidad: guarda el plan aprobado y el **estado exacto** para reanudar
> tras un reinicio. Rama: **DEEPROOT** (local; `main` en `3e689cd`). Repo:
> `F:\THE FORGE\PIXPIN ANDROID`.

## Entorno verificado
- SDK 36 en `C:\Users\MaxBook\AppData\Local\Android\Sdk`; `local.properties` creado (ignorado por git).
- Java del sistema es 1.8: **usar siempre** `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'` antes de gradle.
- Credencial GitHub registrada: `credential.helper = store --file=.git/credentials-deeproot` (línea `https://1xmanMAX:<TOKEN>@github.com`). Push sin pedir nada.
- Línea base de tests: **verde** (`.gradlew testDebugUnitTest`, 1h37m la 1ª vez en frío; las siguientes ~rápidas).

## Cómo reanudar (receta)
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
cd 'F:\THE FORGE\PIXPIN ANDROID'
git status                       # hay cambios sin commit de la WP1
.\gradlew.bat testDebugUnitTest  # suite completa (o con --tests filtrados)
git add -A && git commit -m "..." && git push -u origin DEEPROOT
```

## Estado por WP (checklist)
- [x] **WP0** Entorno + línea base en verde.
- [ ] **WP1** "Dibujo vacío" al exportar HTML desde lienzo → **EN CURSO** (detalle abajo).
- [ ] WP2+WP3 Reserva de pintado del plano al mover la vista + roundtrip HTML con plano.
- [ ] WP4 Medir en el HTML: flechas+cota, movibles, persistentes, varias, imán (diseño confirmado por el usuario).
- [ ] WP5 Rendimiento de planos PDF enormes manteniendo vectorial.
- [ ] WP6 Compartir: opciones/aviso de audio solo cuando hay audio.
- [ ] WP7 Borrado de hojas/marcos del canvas se refleja en el proyecto.
- [ ] WP8 Agrupación por rachas de origen en Guardar (miniaturas pequeñas).
- [ ] WP9 Píldoras "hacer pin" en burbujas (IMAGEN/PAGINA; VOZ opcional) + lector PDF ligero.
- [ ] WP10 Hoja de apuntes rápida (block de notas) con 3 dedos en canvas 2D/3D/Markdown.
- [ ] (Descartado por el usuario) Sección-pizarra acumuladora de pines copiados — era para PC.

## WP1 — estado exacto (EN CURSO)
**Causa raíz:** `motor\DrawSvg.kt` `aTexto()` envolvía TODO en `runCatching{…}.getOrNull()` (antiguas l.176/247): cualquier excepción (OOM al incrustar imagen, perfiles de glifos…) volvía `null`, y `DrawEditorActivity.exportando()` (l.3579-3619) anuncia `null` como «El dibujo está vacío» (`pin_draw_empty`), aunque el lienzo esté lleno. El usuario lo reportó exportando a HTML un lienzo con cosas.

**Cambios YA aplicados (sin commit):**
1. `app/src/main/java/com/forge/pixpin/motor/DrawSvg.kt`
   - `aTexto()`: firma `): String? = runCatching {` → `): String? {`; final `Svg.documento(...)` → `return Svg.documento(...)`; eliminado `}.getOrNull()`. Los `return null` internos (l.~192 y ~214) siguen siendo el *vacío real* (no hay nada pintable y no hay papel; caja sin tamaño).
   - KDoc actualizado: un fallo al escribir NO es dibujo vacío; las excepciones suben.
   - `aArchivo()`: mismo cambio (quitado `runCatching{…}.getOrNull()`, `return archivo`).
   - Compila (se verificó con `compileDebugKotlin` en la pasada de tests).
2. `app/src/test/java/com/forge/pixpin/DrawSvgTest.kt`
   - AÑADIDO import `org.junit.Assert.fail` (hecho).
   - **PENDIENTE:** insertar tras el test `un dibujo vacío no da archivo` (~l.163) el test de regresión:
     ```kotlin
     @Test
     fun `un fallo al escribir no se anuncia como dibujo vacio`() {
         val foto = Element(
             id = "foto", type = ElementType.IMAGE, x = 0.0, y = 0.0,
             width = 64.0, height = 64.0, seed = 1, fileId = "f"
         )
         try {
             DrawSvg.aTexto(context, escenaCon(foto), imageProvider = {
                 throw RuntimeException("se rompió al incrustar la foto")
             })
             fail("el fallo tenía que subir, no volver null como si no hubiera nada")
         } catch (esperado: RuntimeException) {
             assertEquals("se rompió al incrustar la foto", esperado.message)
         }
     }
     ```
   (Helpers ya existentes en la clase: `context`, `escenaCon(vararg e: Element)`. Paquete del test: `com.forge.pixpin.motor`.)

**PENDIENTE en WP1:**
1. Insertar el test de regresión anterior.
2. Verificar con filtros CORRECTOS (los nombres llevan el paquete `com.forge.pixpin.motor.*`; un intento anterior falló por usar `com.forge.pixpin.*`):
   ```powershell
   .\gradlew.bat testDebugUnitTest --tests "com.forge.pixpin.motor.DrawSvgTest" --tests "com.forge.pixpin.motor.SvgDibujoTest" --tests "com.forge.pixpin.motor.ExportarHtmlTest" --tests "com.forge.pixpin.motor.PdfTodasLasHerramientasTest" --tests "com.forge.pixpin.MotorSeparadoTest"
   ```
3. Correr la suite completa `testDebugUnitTest`.
4. Commit y push (primer commit de la rama):
   `git add app/src/main/java/com/forge/pixpin/motor/DrawSvg.kt app/src/test/java/com/forge/pixpin/DrawSvgTest.kt local.properties`
   - ojo: `local.properties` está ignorado; **no** debe entrar. Mensaje sugerido: `un fallo al exportar ya no se anuncia como dibujo vacío` (estilo del repo: español, sin "fix:").
   `git push -u origin DEEPROOT`

## Notas para el resto de WPs (anclas de la investigación; leer antes de tocar)
- **WP2+WP3** (`motor/VisorPlano.kt` JS, `motor/ExportarHtml.kt` SHELL l.~914-1351, `paginaAnotada` ~1290): la reserva de pintado del canvas del plano = viewport×MARGEN (2D MARGEN=2.0, WebGL 1.0); `estirar`+`programar` (90 ms)+`cubierto` (l.598-652 del JS). Roundtrip: el plano viaja como JSON y se regenera; hay que garantizar repintado al abrir re-guardados y probar con un smoke Node (patrón `js-smoke-visor-espacio.js` en `app/src/test`).
- **WP4** medir en HTML: hoy efímero en grupo `#medida` (ExportarHtml.kt JS 734-763); lógica numérica en `motor/Medida.kt` (JVM); mover a hijos serializables de `#croquis`/grupo propio, flechas+cota, lista, arrastre, imán.
- **WP5** rendimiento: `DrawController.updateCreating/replace` (2013-2136/3168-3172) copia la lista por pointerMove → elemento "en curso" fuera de `scene.elements`; `DrawEditorActivity:305-309` raster en hilo principal; parse+`PlanoWeb.aJson`+`PlanoEnPantalla` en un job (3438-3465); `devolverAlPdf`/`PdfDelProyecto.rehacer` en hilo principal (587-603/41-109).
- **WP6** audio al compartir: `motor/VentanaDeAjustes.kt` `DialogoDeFuncionesWeb` param `hayAudio` (default true → false) l.~770/805-814; callers `ui/Proyectos.kt:234/2122`, `MensajesActivity.kt:429/5397`, `DrawEditorActivity.kt:1542`; aviso `ui/ExportarWebDe.kt:79` («· audio:…» incondicional → solo si `audios>0`); predicado robusto sobre las mismas páginas exportadas + existencia/tope del audio (`ExportarProyectoWeb.audioEnDatos` 192-217).
- **WP7** borrado canvas↔proyecto: `Proyectos.sinHoja` (motor/Proyectos.kt:346) sin uso → purgar `Hoja(dibujo==id, marco!=null)` sin marco tras guardar; refresco de `ui/Proyectos.kt` `PaginaDeProyecto` (589/611-625) con `ExcalidrawStore.revisionDe`; `HojasDelProyecto.paginas` (54-113) no debe caer al lienzo entero si falta el marco (91-94 y ExportarProyecto.kt:76-95).
- **WP8** agrupación por origen: `Mensaje` (guardados/Mensajes.kt 110-238) sin campo de origen; derivar: PAGINA→`"pdf:<ruta>"`, DIBUJO→`"dibujo:<referencia>"`, VOZ→`"voz:<proyecto>"`; rachas contiguas ≥2 en función pura (Agrupacion.kt), lista LazyColumn (MensajesActivity 737-865) con ítems de grupo de miniaturas pequeñas; helpers 1-item=1-msg: `filaDe` 4798, `cuandoDeLaFila` 4817, saltos 699-727, `registrarFranja` 851-861.
- **WP9a** píldoras pin: `PastillaDeAtajo` (MensajesActivity 4030-4058) + `pinear(m)` (5177-5258) ya universal; falta en IMAGEN (`Miniatura` 2823/3022) y PAGINA (`MiniaturaDePagina` 2830/3386); ARCHIVO ya la tiene (FilaDeArchivo 4319-4332); VOZ tiene transcribir (FilaDeVoz 3949).
- **WP9b** lector PDF ligero: tocar PDF-ARCHIVO hoy = visor externo (`abrirFuera` 5561). Piezas: `PdfDoc.pageCount/render` + caché `PdfMiniaturas`; rejilla del pin `PinWindowController.openPdfViewer`/`PdfViewerContent` (2077-2260) como referencia; anotar por página con patrón `guardarPagina` (MensajesActivity 4865-4914).
- **WP10** hoja 3 dedos: solo dentro de editores (2D/3D/Markdown). En 2D el toque 2-3 dedos SIN movimiento es undo/redo (`DrawCanvas.kt` 1562-1621; barrido no dispara); en 3D 3 dedos ladean cámara → flick vertical o botón alternativo; ventana overlay pequeña (`OverlayComposeWindow`), ocultar/mostrar, pegar al lienzo (elemento texto vía DrawController) y en Markdown al cursor.

## Decide con el usuario antes de WP10 si hace falta
- Forma exacta de la "hoja": v1 = pin "hoja" que abre editor (patrón `OverlayManager.kt:494-509`), v2 = ventana pequeña con edición inline. Empezar por v1 salvo que el usuario pida la ventana pequeña.
