# PixPin Android — Escritura de calidad, anotación sobre pines, grupos y captura con scroll

Diseño acordado el 2026-07-28. Continúa el trabajo de
[`2026-07-26-pixpin-android-design.md`](2026-07-26-pixpin-android-design.md) y
[`2026-07-27-correcciones-estabilidad-fluidez.md`](2026-07-27-correcciones-estabilidad-fluidez.md).

## 1. Qué se construye y por qué

El MVP está cerrado y en uso real. De ese uso salen cuatro necesidades, en orden de dolor:

1. **La escritura a mano no sirve.** Con lápiz óptico se pierden trazos enteros, los trazos
   cortos no aparecen y el dibujado da tirones.
2. **No se puede anotar un pin que ya está flotando.** Hay que anotar antes de fijarlo, y si
   luego quieres marcar algo, no hay forma.
3. **El pin no crece lo suficiente.** Al pellizcar se queda en un tamaño arbitrario, antes de
   llenar la pantalla.
4. Faltan tres herramientas de anotación útiles y los **grupos de pines**; y la **captura con
   scroll** sigue pendiente.

Fuera de alcance a propósito: marca de agua y procesado de imagen del pin (rotar, voltear,
escala de grises, invertir). No se han pedido.

## 2. Diagnóstico del problema de escritura

No es una carencia de calidad, son cuatro defectos concretos que se suman:

| # | Defecto | Dónde | Síntoma que produce |
|---|---|---|---|
| 1 | `detectDragGestures` no emite nada hasta superar el *touch slop*, y `end()` descarta los trazos de menos de 2 puntos | `CaptureActivity.kt:340`, `AnnotationController.kt:76` | El arranque de cada trazo se pierde; los puntos, tildes y barras cortas **no se dibujan** |
| 2 | Se ignoran los puntos históricos del evento (`PointerInputChange.historical`) | `CaptureActivity.kt:342` | Un digitalizador que muestrea a 240–1000 Hz entrega ~1 punto por fotograma: se tiran entre 2 y 8 de cada 10 |
| 3 | El trazo se dibuja como polilínea recta (`lineTo`) | `AnnotationCanvas.kt:130`, `AnnotationRenderer.kt:64` | Esquinas visibles en las curvas |
| 4 | `current.value = c.copy(points = c.points + pt)` en cada muestra | `AnnotationController.kt:56` | Copia de la lista completa por punto (O(n²)) **y una recomposición por punto** → tirones |

Los cuatro se arreglan juntos en la tanda A; arreglar solo uno no se nota.

## 3. Tanda A — Escritura y anotación

### A1. Motor de trazo

**Entrada.** Un `Modifier` propio, `annotationStrokeInput`, sustituye a `detectDragGestures`
en la capa de anotación. Usa `awaitPointerEventScope` directamente y:

- Empieza el trazo en el **primer contacto**, sin esperar al *slop*.
- Recorre `change.historical` antes de `change.position`, en orden, para no perder muestras.
- Un contacto sin movimiento produce un trazo de **un punto**, que se dibuja como punto
  redondo del grosor actual.
- **Presión**: se guarda por punto. Solo modula el grosor si el puntero es
  `TOOL_TYPE_STYLUS`; con el dedo la presión es ruido y el grosor se mantiene fijo.
- **Rechazo de palma**: mientras haya un puntero de tipo stylus activo, se ignoran los de
  tipo dedo. Sin lápiz, el dedo funciona como siempre.

**Estado del trazo en curso.** `AnnotationController` deja de reconstruir una lista inmutable
por muestra. El trazo vivo pasa a un búfer plano y mutable (`FloatArray` que crece por
duplicación: x, y, presión) más un `mutableIntStateOf` de versión que el Canvas lee para
invalidar. Un punto nuevo = una escritura en el array y un `++version`: sin allocations por
punto y **sin recomposición** (solo redibujado). Al cerrar el trazo se materializa una única
`Annotation` inmutable, como hasta ahora.

**Dibujado.** Los trazos de lápiz y resaltador pasan de `lineTo` a curvas cuadráticas por
puntos medios: para cada par de puntos consecutivos, `quadraticTo(p[i], punto medio de
p[i] y p[i+1])`. Es suavizado local, sin latencia ni post-proceso. Cuando hay presión
variable, el trazo se dibuja como una sucesión de segmentos de grosor interpolado en vez de
un único `Path`, porque un `Path` solo admite un grosor.

Mismo tratamiento en `AnnotationRenderer` (horneado), para que lo guardado coincida
exactamente con lo visto.

**Modelo.** `Pt` gana un tercer campo `p: Float = 1f` (presión normalizada). El valor por
defecto mantiene la compatibilidad con los pines ya serializados en disco.

### A2. Tres herramientas nuevas

Se añaden a `AnnotationType`, al Canvas, al renderer y a la barra de herramientas:

- **Nº de serie** — cada toque coloca un círculo relleno con el número siguiente. El contador
  se reinicia al seleccionar la herramienta. Deshacer devuelve el contador atrás.
- **Polilínea** — cada toque añade un vértice y la línea se previsualiza hasta el dedo; se
  cierra con doble toque o con el botón de la barra.
- **Foco (spotlight)** — se arrastra un rectángulo; todo lo demás se oscurece al 60 %. Se
  dibuja como capa oscura con el hueco recortado, así que el orden respecto a otras
  anotaciones importa: el foco siempre se dibuja al final.

### A3. Anotar sobre un pin ya flotando

**Activación.** La barra de acciones que aparece con la pulsación larga gana un botón de
lápiz. Solo se ofrece en **pines de imagen**: dibujar sobre un pin de texto seleccionable o
sobre una muestra de color no tiene sentido.

**Comportamiento.** Al entrar en modo anotación:

- El `OverlayTouchHandler` del pin desvía los eventos al motor de trazo en lugar de a
  mover/escalar/opacidad. El pin queda clavado mientras dibujas.
- Aparece una **mini barra** en su propia ventana overlay, pegada al pin (mismo patrón que la
  barra de acciones actual): herramienta · color · grosor · deshacer · listo.
- *Listo* devuelve el pin a su comportamiento normal y persiste las anotaciones.

**Coordenadas.** Las anotaciones se guardan en píxeles de la **imagen original**, no de la
pantalla, igual que en la pantalla de captura. Por eso funciona igual anotar con el pin
diminuto que a pantalla completa, y por eso al escalar el pin después las anotaciones escalan
con él sin deformarse.

**Persistencia.** `PinState` gana `annotations: List<Annotation> = emptyList()`. Ya es
`@Serializable`, así que el JSON existente sigue leyéndose. Al guardar, copiar o compartir el
pin se hornean con `AnnotationRenderer`, que ya existe.

### A4. Zoom del pin hasta llenar la pantalla

**Causa actual.** La ventana del pin se añade con `WRAP_CONTENT`, y Android no permite que
una ventana medida así sea mayor que la pantalla. Al llegar a ese techo el contenido deja de
crecer aunque la escala suba, y `PinZoom` detecta el atasco (`stall`) y congela la escala en
un punto que depende de la velocidad del gesto — de ahí que el tope se sienta arbitrario.

**Cambio.** Los `LayoutParams` pasan a tamaño explícito en píxeles: ancho y alto naturales del
contenido multiplicados por la escala. Una ventana con tamaño explícito **sí** puede medir
más que la pantalla, así que el techo desaparece.

**Nuevo límite.** `maxScale = min(pantallaAncho / anchoNatural, pantallaAlto / altoNatural)`,
es decir: crece hasta que **un eje toca el borde de la pantalla** y ahí para. El pin siempre
se ve entero. `MIN_SCALE` se mantiene.

**Consecuencia.** Se elimina la detección de atasco de `PinZoom` (`ZoomState.stall`,
`lastRealW`, `requestedW`) y sus tests, sustituidos por tests del nuevo límite. El tamaño
natural del contenido se mide una vez al crear la ventana y se cachea.

## 4. Tanda B — Grupos de pines

- `PinState` gana `groupId: String? = null`.
- Se agrupa desde la **lista de pines** que ya existe: selección múltiple → *Agrupar*. Se
  deshace con *Desagrupar*. No se usa el gesto de arrastrar un pin sobre otro: ese gesto ya
  significa "minimizar en burbuja".
- Un grupo se **mueve junto** (arrastrar un miembro desplaza a los demás conservando las
  distancias), se **oculta junto** y se **cierra junto**.
- Escalar y la opacidad siguen siendo por pin: agrupar sirve para colocar, no para uniformar.
- Indicador visual: borde fino del color asignado al grupo.
- `OverlayManager` es el dueño de la relación; `PinWindowController` solo notifica "me han
  arrastrado esto" y el gestor propaga.

## 5. Tanda C — Captura con scroll

**Flujo.** Desde la pantalla de captura, con la región ya elegida, un botón *Scroll*. La
actividad se cierra dejando una barrita overlay (*desplaza · Listo · ✕*) y la sesión de
captura viva. El usuario desplaza a ritmo normal con el dedo; PixPin va cosiendo y muestra el
alto acumulado. *Listo* produce la imagen larga y la entrega a la pantalla de captura de
siempre: anotar, pin, guardar, copiar o compartir.

**Cosido.** `ScrollStitcher`, matemática pura y testeable sin dispositivo: recibe la franja
recortada de cada fotograma y devuelve el desplazamiento vertical respecto a lo acumulado,
buscando por correlación de filas (suma de diferencias absolutas normalizada sobre una banda
de filas del borde inferior). Reglas:

- Desplazamiento 0 → fotograma redundante, se descarta.
- Correlación por debajo del umbral de confianza → fotograma dudoso (zona lisa, contenido
  que cambió, animación), se descarta y se espera al siguiente.
- Desplazamiento mayor que la altura de la banda de búsqueda → se ha desplazado demasiado
  rápido; se descarta y se avisa en la barrita.

**Límites.** Alto máximo 10 × la altura de pantalla, por memoria; al llegar, la barrita avisa
y deja de acumular sin perder lo hecho.

**Requisito.** Necesita la sesión de captura viva, o sea el modo **Rápido**. En modo Discreto
la barrita lo explica y ofrece activarlo para esta captura.

**Limitación asumida y documentada:** las barras fijas (cabeceras que no se desplazan)
aparecerán repetidas si quedan dentro de la región. La respuesta es elegir la región sin
ellas, no detectarlas automáticamente.

## 6. Testing

Todo lo delicado sale a objetos puros, siguiendo lo que ya hace el proyecto:

| Unidad | Qué se prueba |
|---|---|
| `StrokeBuffer` | Crecimiento sin perder puntos, orden de los históricos, trazo de un solo punto |
| `StrokeSmoothing` | La curva pasa por los puntos de control esperados; un trazo de 1 y de 2 puntos no revienta |
| `AnnotationGeometry` | Geometría del nº de serie, la polilínea y el foco |
| `PinZoom` | Nuevo límite por eje: llega justo al borde, no lo pasa, y respeta `MIN_SCALE` |
| `PinGroups` | Movimiento solidario conserva distancias; agrupar/desagrupar; cerrar el grupo |
| `ScrollStitcher` | Imágenes sintéticas con desplazamiento conocido; con ruido; franja lisa (debe rechazar); salto excesivo (debe rechazar) |

Con Robolectric, como ahora: el modo anotación del pin (entrar, dibujar, salir, persistir) y
la ventana de pin con tamaño explícito.

## 7. Orden de entrega

1. **Tanda A** — A1 (motor de trazo) → A4 (zoom) → A3 (anotar sobre el pin) → A2 (herramientas).
   A1 primero porque A3 lo reutiliza entero.
2. **Tanda B** — grupos.
3. **Tanda C** — captura con scroll.

Cada tanda se compila, pasa sus tests y se commitea por separado.

## 8. Qué cambió al probarlo

Este documento es el diseño acordado *antes* de escribir el código. Lo que el uso real
obligó a cambiar, para que no quede engañoso:

| Decisión del diseño | Qué acabó siendo | Por qué |
|---|---|---|
| El pin crece hasta llenar la pantalla y ahí para | Crece hasta **el triple** de lo que cabe | Llenar la pantalla se quedaba corto para leer letra pequeña. La ventana con tamaño explícito lo permite, y `keepReachable()` evita perderlo |
| Se mantiene `WRAP_CONTENT` calculando bien el tope | Ventana con **tamaño explícito** en los pines de imagen | Con `WRAP_CONTENT` la imagen topa en el tamaño de la pantalla mientras la escala sigue subiendo, y lo dibujado encima se despega de la foto |
| El foco se dibuja el último, cada uno con sus bandas | **Una sola veladura con varios huecos** | Con dos focos las bandas se solapaban y la pantalla se oscurecía el doble |
| Un grupo se mueve, se oculta y se cierra junto | Además se minimiza en **una sola burbuja**, y guarda su disposición para recuperarla al desplegarse | Varias burbujas atadas se estorbaban y alguna acababa fuera de la pantalla; y al desplegar, el grupo aparecía deshecho |
| La barra de anotación va pegada bajo el pin | Se pega al **borde de la pantalla que menos tape**, en una sola fila con las herramientas plegadas | Con un pin grande tapaba justo la zona que querías dibujar |
| Se conserva el botón de guardar | **Guardado automático** en `Pictures/PixPin`; no hay botón | Petición explícita: si haces algo con una captura, la querías |

Fuera de alcance confirmado: marca de agua y procesado de imagen del pin (rotar, voltear,
escala de grises, invertir).
