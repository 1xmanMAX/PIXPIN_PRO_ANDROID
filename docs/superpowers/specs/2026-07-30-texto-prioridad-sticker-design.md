# PixPin Android — Cuadros de texto, prioridad binaria y sticker de emoji

Diseño acordado el 2026-07-30. Continúa el trabajo de
[`2026-07-28-anotacion-grupos-scroll-design.md`](2026-07-28-anotacion-grupos-scroll-design.md).

## 1. Qué se construye y por qué

Del uso de la v0.2 salen cuatro necesidades:

1. **El cuadro de un pin de texto no se puede redimensionar.** Hay un intento en el código que
   no llega a ejecutarse nunca (§2, defecto 1) y el pin se recorta a 120 px de alto.
2. **La herramienta TEXTO de anotación planta texto suelto**, sin cuadro y sin control de
   tamaño: lo que escribes sale del tamaño que salga.
3. **Las etiquetas no sirven.** Hay cinco, se guardan y no se muestran en ninguna parte. Lo que
   hace falta es algo mucho más simple: prioritario o no.
4. **No hay forma de distinguir un pin de otro de un vistazo** cuando tienes seis en pantalla.

Fuera de alcance a propósito, por no haberse pedido: reordenar la lista para que los pines
prioritarios suban, renombrar imágenes a mano y editar el contenido de un pin de texto ya
creado (necesitaría ventana enfocable para el teclado, que es un cambio de otro tamaño).

## 2. Defectos encontrados al revisar el código

| # | Defecto | Dónde | Síntoma que produce |
|---|---|---|---|
| 1 | `GestureHostLayout.dispatchTouchEvent` devuelve `true` sin llamar a `super` cuando hay un touch handler puesto, así que **ningún toque llega a Compose** | `OverlayWindowFactory.kt:90-94` | El `pointerInput` de 3 dedos que redimensiona el cuadro de texto (`PinWindowController.kt:1137-1157`) es código muerto: no se ha ejecutado jamás |
| 2 | `naturalH = 120` a pelo para los pines de texto | `PinWindowController.kt:988` | Un texto de más de ~5 líneas se recorta y no hay forma de verlo entero |
| 3 | El cambio de `textBoxWidth` escribe en `pin.value` sin llamar a `callbacks.onPinChanged` | `PinWindowController.kt:1150` | El ancho no se persiste: se pierde al reiniciar |
| 4 | `togglePinned()` fuerza `savedCategory = "other"` al marcar como guardado | `PinWindowController.kt:362` | Pisa la etiqueta que hubiera puesto el usuario |
| 5 | `savedCategory` se escribe en tres sitios y **no se lee en ninguno** | `PinModels.kt:36`, `OverlayManager.kt:521,527` | Las cinco etiquetas no se muestran nunca: el gesto de asignarlas no produce ningún efecto visible |
| 6 | `labelFor()` devuelve la constante `"Imagen"` para todos los pines de imagen | `OverlayManager.kt:901` | Seis capturas en la lista aparecen como seis filas idénticas |

Los defectos 1, 2 y 3 se arreglan en el bloque A; el 4 y el 5 en el C; el 6 en el C también,
al pasar el nombre de la imagen a reflejar la prioridad.

## 3. Modelo de datos

Cambios en `PinState` (`PinModels.kt`). Todos los campos nuevos llevan valor por defecto y el
`Json` del repositorio ya usa `ignoreUnknownKeys = true`, así que **los pines guardados en
disco siguen cargando sin migración**.

| Campo | Cambio | Motivo |
|---|---|---|
| `textBoxWidth: Int = 330` | se mantiene | ancho del cuadro en dp |
| `textBoxHeight: Int? = null` | **nuevo** | alto máximo en dp; `null` = se ajusta al texto |
| `savedCategory: String?` | **se elimina** | nunca se leyó (defecto 5). Al desaparecer del modelo, `ignoreUnknownKeys` ignora el campo viejo del JSON |
| `priority: Boolean = false` | **nuevo** | prioritario / no prioritario |
| `emoji: String? = null` | **nuevo** | el sticker de la esquina; `null` = sin sticker |

No hay migración de las etiquetas viejas a `priority`: como nunca se mostraron, ningún usuario
las ha asignado con intención.

## 4. Bloque A — Contenedor de texto redimensionable

### A1. La ventana del pin de texto pasa a medirse sola

Se elimina el bloque de `PinRoot` (`PinWindowController.kt:984-991`) que fija
`naturalW = pantalla × 0.5` y `naturalH = 120` para los pines de texto. Con `naturalW == 0`,
`applyContentSize()` ya sale sin tocar nada (línea 452) y la ventana se queda en
`WRAP_CONTENT`: mide lo que mida su contenido. Eso resuelve el defecto 2 por sí solo.

El ancho lo manda entonces el composable: `Modifier.width(textBoxWidth.dp)` en lugar del
`widthIn(max = ...)` actual. El alto se ajusta al texto salvo que `textBoxHeight` tenga valor,
y entonces es `heightIn(max = textBoxHeight.dp)` con `verticalScroll` para el texto que sobre.

`onScaleStart` ya distingue los dos casos (línea 506 vs 519) y con `naturalW == 0` toma la rama
de "se mide solo", que es la correcta. El tope de 5× para texto (línea 515) deja de aplicarse
porque cuelga de la otra rama; se traslada a la rama de medida propia condicionado al tipo.

### A2. Gesto de redimensionar: modo `RESIZE` en `OverlayTouchHandler`

`OverlayTouchHandler` (`OverlayTouch.kt`) gana:

- Una propiedad `handleRect: Rect?` (coordenadas locales de la vista, en px), que el
  controlador actualiza cuando cambia el tamaño de la ventana.
- Un valor `Mode.RESIZE` en el enum interno.
- Dos métodos en `Listener`: `onResizeStart()`, `onResize(dxFromDown, dyFromDown)` y
  `onResizeEnd()`, todos con implementación vacía por defecto para no romper a
  `FloatingBallController`, que implementa el mismo interfaz.

En `ACTION_DOWN`, si `handleRect` contiene el punto (`event.x`, `event.y` — coordenadas
locales, no `raw`, porque el rect es local), el modo pasa directamente a `RESIZE` sin esperar
al *touch slop*: un handle pequeño necesita responder al primer píxel. Mientras el modo sea
`RESIZE`, `handleMove` emite `onResize` con el delta desde el `down` en coordenadas **raw**
—la ventana cambia de tamaño bajo el dedo, así que los deltas locales se falsearían igual que
pasa con el arrastre— y no se entra en `DRAG`, `SCALE` ni `OPACITY`. El detector de toques
(`tapDetector`) ya se inhibe solo: `idle()` exige `mode == Mode.NONE`.

### A3. El handle en el pin

`TextPinBody` deja de tener el `pointerInput` de 3 dedos (código muerto, defecto 1) y dibuja
en la esquina inferior derecha un triangulito de 30 dp, tenue, con el color
`onSurfaceVariant`. Es solo pintura: los toques los reconoce `OverlayTouchHandler`.

El controlador mantiene `handleRect` sincronizado desde el `onGloballyPositioned` del cuerpo
del pin: el rect es siempre los últimos 30 dp de ancho y alto de la vista. Al vivir en un solo
sitio la geometría (medida real, no calculada dos veces) no puede desincronizarse del dibujo.

`onResize` traduce el arrastre a dp y actualiza el estado. El cálculo, que es lo único con
lógica, vive en una función pura probable en JVM —`TextBoxSize.resize(anchoAlEmpezar,
altoAlEmpezar, dxDp, dyDp)`— en un archivo propio junto a `PinZoom`, que sigue ese mismo
patrón:

```
textBoxWidth  = (anchoAlEmpezar + dxDp).coerceIn(120, 500)
textBoxHeight = (altoAlEmpezar  + dyDp).coerceAtLeast(60)
```

`onResizeEnd` llama a `callbacks.onPinChanged(this)`, que es lo que persiste (defecto 3). No se
llama en cada muestra: `scheduleSave()` ya hace *debounce* de 800 ms, pero escribir el estado
en cada píxel de arrastre es ruido innecesario.

## 5. Bloque B — Cuadros de texto en la anotación

### B1. El modelo de anotación

`Annotation` (`Annotation.kt:38`) gana `boxWidth: Float? = null`, en píxeles de imagen igual
que el resto de la geometría. Con `null` el texto se dibuja como hasta ahora (una sola línea
desde el punto), así que las anotaciones ya guardadas en pines no cambian de aspecto. Con
valor, el texto se ajusta a ese ancho y se le dibuja un recuadro fino del color de la
anotación, con 8 px de imagen de margen interior.

`strokeWidth` sigue haciendo de tamaño de fuente, como documenta el comentario del modelo.

### B2. El diálogo de creación

El `AlertDialog` de `CaptureActivity.kt:495-519` pasa de un `OutlinedTextField` suelto a:

- El campo de texto, igual que ahora.
- Una **vista previa en vivo**: el texto renderizado con el tamaño y el ancho elegidos, sobre
  el color de la anotación, dentro del recuadro. Es lo que evita el ciclo de prueba y error de
  poner, mirar, deshacer y repetir.
- Dos *steppers* de −/+: **tamaño de fuente** (de 12 a 96 px de imagen, en pasos de 4) y
  **ancho del cuadro** (de 80 a 600 px de imagen, en pasos de 20, con una posición «sin cuadro»
  al mínimo que deja `boxWidth = null`).

`AnnotationController.addText(pt, text)` pasa a `addText(pt, text, fontSize, boxWidth)`, y deja
de calcular el tamaño como `strokeWidth.value * 5`: lo recibe ya resuelto. El diálogo arranca
con los valores del último texto añadido, guardados en el controlador, para que poner tres
textos seguidos del mismo tamaño no obligue a ajustarlo tres veces.

### B3. Reeditar un texto ya puesto

Con la herramienta TEXTO activa, un toque que caiga dentro del *bounding box* de una anotación
de tipo `TEXT` reabre el diálogo con su contenido, tamaño y ancho, en lugar de crear otra
encima. Confirmar sustituye la anotación (con entrada en la pila de deshacer); cancelar la deja
como estaba. `AnnotationGeometry.boundingBox()` ya existe y sirve tal cual para el impacto.

### B4. Los dos renderizadores

El ajuste de línea y el recuadro hay que implementarlos **en los dos sitios**, o lo exportado
no coincidirá con lo que se ve en pantalla:

- `AnnotationCanvas.kt:216` — previsualización en Compose, con `TextLayoutResult` y
  `constraints` de ancho.
- `AnnotationRenderer.kt:81` — horneado al bitmap, con `StaticLayout` sobre el `TextPaint`.

El punto de la anotación es la **esquina superior izquierda** del cuadro cuando hay `boxWidth`
(hoy es la línea base del texto). El renderizador traduce entre ambos según haya cuadro o no.

## 6. Bloque C — Prioridad binaria

### C1. Qué se borra

Desaparecen de `OverlayManager.kt`: el composable `TagsRow` (líneas 869-898), el método
`assignCategory` (517-535) y el estado `showingTagsFor` (101). De `PinWindowController.kt`
desaparece el `savedCategory = ...` de `togglePinned()` (defecto 4).

### C2. El gesto

La pulsación larga sobre la etiqueta de la fila —el `Box` con `detectTapGestures(onLongPress)`
que hoy abre las etiquetas, en `PinListRow` (740-744) y `SavedPinRow` (832-836)— pasa a
alternar `priority`, con `HapticFeedbackConstants.LONG_PRESS` para confirmar que ha pasado
algo. Un pin nace siempre en `priority = false`.

La escritura va por dos caminos según dónde viva el pin, igual que hace ya `assignCategory`:
si está activo, `pins[id].updateState(...)` y `saveNow()`; si es un guardado cerrado,
`repo.loadSavedPins()` → `copy(priority = ...)` → `repo.saveSavedPins()`. Después,
`refreshPinList()`.

### C3. Cómo se ve

En `labelFor(pin)` y en la fila de la lista:

| Tipo | Sin prioridad | Con prioridad |
|---|---|---|
| `TEXT` | los primeros 30 caracteres | chip **PRIORIDAD** + los primeros 30 caracteres |
| `FILE` | el nombre del archivo | chip **PRIORIDAD** + el nombre del archivo |
| `COLOR` | el hex | chip **PRIORIDAD** + el hex |
| `IMAGE` | `PixPin` | `Prioridad` |

En la imagen el nombre **es** el estado: no lleva chip, cambia el nombre entero. Es lo que
arregla el defecto 6 de paso — seis capturas dejan de ser seis filas idénticas en cuanto
marcas una.

El chip es un `Surface` pequeño de esquinas redondeadas, `primaryContainer` de fondo, con el
texto en `labelSmall`.

## 7. Bloque D — Sticker de emoji

### D1. Por qué hace falta margen

Una ventana overlay recorta lo que se dibuje fuera de sus límites, y el `Surface` con
`RoundedCornerShape` recorta además su contenido. Un sticker que sobresale de la esquina
necesita, por tanto, que **la ventana sea mayor que el cuerpo del pin**.

`PinRoot` pasa a envolver el `Surface` en un `Box` con `padding(top = 16.dp, end = 16.dp)`
—solo cuando el pin tiene emoji— y el emoji se dibuja en ese margen, alineado a la esquina
superior derecha, con `Modifier.rotate(30f)` y por encima del `Surface`.

Solo los pines con tamaño explícito —los de imagen— necesitan que se les ajuste la cuenta. Los
de texto, color y archivo, y la burbuja minimizada, son ventanas `WRAP_CONTENT`: el margen les
crece solo al medirse, sin tocar nada.

`applyContentSize()` cambia en consecuencia: el tamaño de la ventana pasa a ser
*naturalW × escala + margen* × *naturalH × escala + margen*. **`naturalW` y `naturalH` siguen
siendo el tamaño de la imagen, no de la ventana**: `AnnotationCanvas` calcula su rectángulo con
`naturalW × zoom` (`PinWindowController.kt:1097-1098`) y si incluyeran el margen, los trazos se
despegarían de la foto — exactamente el fallo que documenta el comentario de las líneas
216-221. El conversor de coordenadas `toImagePt()` resta `imageOrigin`, que se mide de verdad
con `onGloballyPositioned`, así que absorbe el margen sin tocar nada.

El margen es una constante en dp que **no escala** con el zoom. Introduce una desviación
pequeña en el anclaje del foco del pellizco, que usa `v.width`/`v.height` (línea 540-541): con
16 dp sobre un pin de varios cientos de px es imperceptible, y la alternativa —descontar el
margen en `PinZoom.step`— añade un parámetro a una función pura que hoy se prueba fácil.

En la burbuja minimizada la ventana es `WRAP_CONTENT`, así que el margen crece solo. El emoji
se dibuja también ahí, a menor tamaño: es cuando más falta hace para distinguir unas de otras.
`clampBubbleIntoScreen()` seguirá usando `BUBBLE_DP` y quedará desviado esos 16 dp; es
aceptable porque su único cometido es que la burbuja no se pierda fuera de la pantalla.

### D2. Elegir el emoji

La barra de acciones (`ActionBarContent`, `PinWindowController.kt:780-822`) gana un **quinto
botón** con `Icons.Filled.EmojiEmotions`, entre el de anotar y el de guardar. Al tocarlo se
abre una rejilla en **ventana propia**, por el mismo motivo que la barrita de anotación: la
ventana del pin se queda con todos los toques y ningún botón dentro de ella se enteraría.

La rejilla son 24 emojis en 4 filas de 6, más un botón de «sin emoji» que pone `emoji = null`.
Tocar uno lo asigna, cierra la rejilla y cierra la barra de acciones. Se colocan en una lista
constante en el archivo, junto a `PIN_PALETTE` y `ANNOTATE_TOOLS`, que siguen ese mismo patrón.

Se aplica a los **cuatro tipos** de pin. Al de color le cuesta lo mismo y sería raro que fuera
el único sin ello.

### D3. En la lista

El emoji aparece en `PinListRow` y `SavedPinRow` delante del chip de prioridad, sin rotar y a
tamaño de texto. La rotación es del sticker sobre el pin, no del identificador en una lista.

## 8. Ciclo de vida y limpieza

La rejilla de emojis es una ventana más, con el mismo tratamiento que `annotateBar`: se cierra
en `hideView()`, en `setViewVisible(false)` y al minimizar. Si no, se quedaría flotando
huérfana cuando el pin desaparece — el mismo fallo que ya se corrigió en su día con la barra de
acciones.

## 9. Pruebas

Todo lo verificable sin Android va a tests JVM, siguiendo lo que ya hay en `app/src/test`:

| Qué se prueba | Dónde |
|---|---|
| `PinState` con `priority`, `emoji` y `textBoxHeight` sobrevive a la ida y vuelta a JSON | `PinStateSerializationTest` (ampliar) |
| Un JSON viejo con `savedCategory` sigue cargando y el campo se ignora | `PinStateSerializationTest` (ampliar) |
| Un `ACTION_DOWN` dentro de `handleRect` entra en `RESIZE`; fuera, en `DRAG` | `OverlayTouchHandlerTest` (ampliar) |
| Con `handleRect = null` el comportamiento es idéntico al actual | `OverlayTouchHandlerTest` (ampliar) |
| `boundingBox` de una anotación TEXT con `boxWidth` cubre el cuadro entero | `AnnotationGeometryTest` (ampliar) |
| Los límites de `textBoxWidth` (120-500) y `textBoxHeight` (≥60) se respetan | `TextBoxSizeTest` (nuevo) |

El ajuste de línea del texto y el dibujado del recuadro no se prueban en JVM: dependen de
`StaticLayout` y de `TextLayoutResult`, que son de Android. Se extrae a función pura solo el
cálculo de límites del redimensionado, que es lo que tiene lógica.

## 10. Orden de trabajo

1. **Bloque A** — contenedor de texto. Es el que toca `OverlayTouchHandler`, del que dependen
   todos los pines; si algo se rompe ahí conviene saberlo antes de construir encima.
2. **Bloque C** — prioridad. Independiente de A, y es puro borrado y sustitución.
3. **Bloque D** — sticker. Toca `applyContentSize()`, que A ya habrá dejado estable.
4. **Bloque B** — cuadros de texto en la anotación. El más aislado de todos: solo afecta a la
   pantalla de captura.
