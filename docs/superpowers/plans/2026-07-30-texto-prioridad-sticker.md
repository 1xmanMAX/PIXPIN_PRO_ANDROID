# Cuadros de texto, prioridad binaria y sticker de emoji — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hacer redimensionable el cuadro de los pines de texto, dar cuadros con tamaño ajustable a la herramienta TEXTO de anotación, sustituir las cinco etiquetas muertas por una prioridad binaria que se alterna con pulsación larga, y permitir pegar un emoji rotado en la esquina de cada pin.

**Architecture:** Todo el estado nuevo vive en `PinState` (serializado a JSON con `kotlinx.serialization`) y en `Annotation`, ambos con valores por defecto para que lo guardado en disco siga cargando. Los gestos de las ventanas overlay se reconocen en `OverlayTouchHandler`, que gana un modo `RESIZE`; la lógica calculable se extrae a objetos puros (`TextBoxSize`, junto al ya existente `PinZoom`) para poder probarla en JVM sin dispositivo.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2026.02.01), Material 3, kotlinx.serialization, AGP 9.3.1, Gradle 9.5, JUnit 4 + Robolectric 4.15.1.

**Spec:** [`docs/superpowers/specs/2026-07-30-texto-prioridad-sticker-design.md`](../specs/2026-07-30-texto-prioridad-sticker-design.md)

## Global Constraints

- **Rama de trabajo:** `mejoras-v0.2`. No se toca `main`.
- **Idioma:** todo el código, comentarios, KDoc y mensajes de commit en **español**, siguiendo el estilo del repositorio (los comentarios explican *por qué*, no *qué*).
- **Cadenas de UI:** ningún literal de texto visible en el código Kotlin. Todo a `app/src/main/res/values/strings.xml` y se lee con `context.getString(...)` / `app.getString(...)` / `stringResource(...)`.
- **Compatibilidad de datos:** todo campo nuevo de `PinState` y `Annotation` lleva valor por defecto. El `Json` del repositorio ya usa `ignoreUnknownKeys = true`. Un `pins.json` de la v0.2 debe seguir cargando sin errores tras cada tarea.
- **minSdk = 29**, `compileSdk = 36`, Java 17.
- **Comando de test** (desde la raíz del proyecto, PowerShell):
  ```powershell
  $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
  ```
  El `java` del PATH es Java 8 y no sirve: **hay que exportar `JAVA_HOME` en cada sesión de shell nueva**.
- **Para un solo test:** añadir `--tests "com.forge.pixpin.NombreDelTest"`.
- Los artefactos de compilación van a `%LOCALAPPDATA%\pixpin-build`, no al repositorio (ver `build.gradle.kts:9-12`).
- **Commit al final de cada tarea**, nunca a mitad.

---

## Estructura de archivos

| Archivo | Responsabilidad | Tareas |
|---|---|---|
| `app/src/main/java/com/forge/pixpin/pin/PinModels.kt` | `PinState`: +`textBoxHeight`, +`priority`, +`emoji`, −`savedCategory` | 1 |
| `app/src/main/java/com/forge/pixpin/pin/TextBoxSize.kt` | **Nuevo.** Límites del redimensionado del cuadro de texto. Función pura | 2 |
| `app/src/main/java/com/forge/pixpin/pin/OverlayTouch.kt` | `OverlayTouchHandler`: modo `RESIZE` y zona-handle | 3 |
| `app/src/main/java/com/forge/pixpin/pin/PinWindowController.kt` | Cuerpo del pin de texto y handle (4), sticker y tamaño de ventana (6), selector de emoji (7) | 1, 4, 6, 7 |
| `app/src/main/java/com/forge/pixpin/pin/OverlayManager.kt` | Borrado de etiquetas (1), prioridad en la lista (5), emoji en la lista (6) | 1, 5, 6 |
| `app/src/main/java/com/forge/pixpin/annotate/Annotation.kt` | `Annotation.boxWidth` y geometría del cuadro de texto | 8 |
| `app/src/main/java/com/forge/pixpin/annotate/AnnotationCanvas.kt` | Previsualización Compose del cuadro de texto | 8 |
| `app/src/main/java/com/forge/pixpin/annotate/AnnotationRenderer.kt` | Horneado del cuadro de texto al bitmap | 8 |
| `app/src/main/java/com/forge/pixpin/annotate/AnnotationController.kt` | `addText` con tamaño y ancho; reedición | 9 |
| `app/src/main/java/com/forge/pixpin/capture/CaptureActivity.kt` | Diálogo de texto con vista previa y steppers | 9 |
| `app/src/main/res/values/strings.xml` | Cadenas nuevas | 1, 5, 7, 9 |

Tests, todos en `app/src/test/java/com/forge/pixpin/`: `PinStateSerializationTest` (1), `TextBoxSizeTest` (2, nuevo), `OverlayTouchHandlerTest` (3), `AnnotationGeometryTest` (8).

**Orden.** El bloque A del spec (tareas 2-4) va primero porque toca `OverlayTouchHandler`, del que dependen todos los pines y la bola flotante; si algo se rompe ahí conviene enterarse antes de construir encima. La tarea 1 se le adelanta porque cambia el modelo del que come todo lo demás. Después C (5), D (6-7) y B (8-9), como dice el spec §10.

---

### Task 0: Entorno y línea base

Sin esto no compila nada: `local.properties` está en `.gitignore` y no viene en el clon, y el `java` del PATH es la versión 8.

**Files:**
- Create: `local.properties` (ignorado por git, no se commitea)

- [ ] **Step 1: Crear `local.properties`**

```properties
sdk.dir=C\:\\Users\\MaxBook\\AppData\\Local\\Android\\Sdk
```

- [ ] **Step 2: Ejecutar la suite actual**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`. Salen ~10 avisos de deprecación de iconos (`Icons.Filled.Undo`, `InsertDriveFile`, …) y de `LocalLifecycleOwner`: son previos a este trabajo y **no se arreglan aquí**, no forman parte del alcance.

- [ ] **Step 3: Sin commit**

`local.properties` está en `.gitignore` a propósito: es de esta máquina.

---

### Task 1: Modelo de datos y borrado de las etiquetas

Las cinco etiquetas se escribían en `savedCategory` y no se leían en ninguna parte (defecto 5 del spec): borrarlas no quita ninguna función.

**Files:**
- Modify: `app/src/main/java/com/forge/pixpin/pin/PinModels.kt:36-38`
- Modify: `app/src/main/java/com/forge/pixpin/pin/PinWindowController.kt:357-365`
- Modify: `app/src/main/java/com/forge/pixpin/pin/OverlayManager.kt:101`, `:517-535`, `:869-898`
- Test: `app/src/test/java/com/forge/pixpin/PinStateSerializationTest.kt`

**Interfaces:**
- Produces: `PinState.textBoxHeight: Int?`, `PinState.priority: Boolean`, `PinState.emoji: String?`. Desaparece `PinState.savedCategory`.

- [ ] **Step 1: Escribir los tests que fallan**

Añadir a `PinStateSerializationTest.kt`:

```kotlin
    @Test
    fun `roundtrip con los campos nuevos`() {
        val state = PinState(
            id = "abc",
            type = PinType.TEXT,
            text = "hola",
            textBoxWidth = 240,
            textBoxHeight = 180,
            priority = true,
            emoji = "🔥"
        )
        val restored = json.decodeFromString<PinState>(json.encodeToString(state))
        assertEquals(state, restored)
    }

    @Test
    fun `los campos nuevos tienen valores por defecto seguros`() {
        val minimal = """{"id":"x","type":"TEXT","text":"hola"}"""
        val restored = json.decodeFromString<PinState>(minimal)
        assertEquals(330, restored.textBoxWidth)
        assertEquals(null, restored.textBoxHeight)
        assertEquals(false, restored.priority)
        assertEquals(null, restored.emoji)
    }

    /** Un pins.json de la v0.2 lleva savedCategory; el campo ya no existe y debe ignorarse. */
    @Test
    fun `un pin de la version anterior sigue cargando`() {
        val v02 = """{"id":"x","type":"IMAGE","imagePath":"/f/a.png",""" +
            """"isPinned":true,"savedCategory":"⭐ Importante","textBoxWidth":330}"""
        val restored = json.decodeFromString<PinState>(v02)
        assertEquals("/f/a.png", restored.imagePath)
        assertEquals(true, restored.isPinned)
        assertEquals(false, restored.priority)
    }
```

- [ ] **Step 2: Ejecutar y comprobar que fallan**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --tests "com.forge.pixpin.pin.PinStateSerializationTest" --console=plain
```

Esperado: FALLO de compilación — `Cannot find a parameter with this name: textBoxHeight`.

- [ ] **Step 3: Cambiar `PinState`**

En `PinModels.kt`, sustituir las líneas 35-38 (el `savedCategory` y el `textBoxWidth`) por:

```kotlin
    /** Ancho del cuadro de texto en dp (solo para TEXT pins). */
    val textBoxWidth: Int = 330,
    /** Alto máximo del cuadro de texto en dp; null = se ajusta al texto. */
    val textBoxHeight: Int? = null,
    /**
     * Pin prioritario. Nace siempre en false y se alterna con pulsación larga
     * sobre su nombre en la lista de pines.
     */
    val priority: Boolean = false,
    /** Emoji pegado en la esquina, a modo de pegatina; null = sin pegatina. */
    val emoji: String? = null
```

Es decir: se borra la línea de `savedCategory` con su KDoc y se añaden los tres campos. `isPinned` se queda como está.

- [ ] **Step 4: Quitar la escritura de `savedCategory` en `togglePinned`**

En `PinWindowController.kt`, sustituir el cuerpo de `togglePinned()` (líneas 358-365) por:

```kotlin
    /** Marca/desmarca el pin como guardado. */
    fun togglePinned() {
        pin.value = pin.value.copy(isPinned = !pin.value.isPinned)
        callbacks.onPinChanged(this)
    }
```

Esto arregla de paso el defecto 4 del spec: guardar un pin ya no pisa nada.

- [ ] **Step 5: Borrar las etiquetas de `OverlayManager`**

Tres borrados:

1. La línea 101, el estado `private val showingTagsFor = mutableStateOf<String?>(null)`.
2. El método `assignCategory` completo (líneas 517-535).
3. El composable `TagsRow` completo (líneas 869-898).

Y en `PinListRow` y `SavedPinRow` desaparecen sus dos referencias a `showingTagsFor`. En `PinListRow`, sustituir:

```kotlin
        val groupColor = pin.groupId?.let { Color(PinGroups.colorFor(it)) }
        val showTags = showingTagsFor.value == pin.id

        Column(modifier = Modifier.fillMaxWidth()) {
```

por:

```kotlin
        val groupColor = pin.groupId?.let { Color(PinGroups.colorFor(it)) }

        Column(modifier = Modifier.fillMaxWidth()) {
```

y borrar el bloque final del `Column`:

```kotlin
            if (showTags) {
                TagsRow(pin)
            }
```

En `SavedPinRow`, borrar igualmente la línea `val showTags = showingTagsFor.value == pin.id` y su bloque `if (showTags) { TagsRow(pin) }`.

El `detectTapGestures(onLongPress = { showingTagsFor.value = pin.id })` de ambas filas se sustituye por un cuerpo vacío temporal —`detectTapGestures(onLongPress = { })`— porque la tarea 5 le dará su función definitiva. Dejar el gesto ahí y sin efecto durante una tarea es preferible a borrarlo y volver a escribirlo.

Si tras los borrados quedan imports sin usar (`Arrangement`, `TextButton`, `height`), quitarlos.

- [ ] **Step 6: Ejecutar los tests**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`, con los 3 tests nuevos en verde y los 13 anteriores sin romperse.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/forge/pixpin/pin/PinModels.kt app/src/main/java/com/forge/pixpin/pin/PinWindowController.kt app/src/main/java/com/forge/pixpin/pin/OverlayManager.kt app/src/test/java/com/forge/pixpin/PinStateSerializationTest.kt
git commit -m "Modelo: prioridad, emoji y alto del cuadro de texto; fuera las etiquetas muertas"
```

---

### Task 2: `TextBoxSize`, los límites del redimensionado

Función pura, sin Android, siguiendo el patrón de `PinZoom`: es lo único del redimensionado con lógica que merece prueba.

**Files:**
- Create: `app/src/main/java/com/forge/pixpin/pin/TextBoxSize.kt`
- Test: `app/src/test/java/com/forge/pixpin/TextBoxSizeTest.kt` (nuevo)

**Interfaces:**
- Produces: `data class TextBoxDims(val width: Int, val height: Int)` y `object TextBoxSize` con `resize(startWidth: Int, startHeight: Int, dxDp: Float, dyDp: Float): TextBoxDims` y las constantes `MIN_WIDTH = 120`, `MAX_WIDTH = 500`, `MIN_HEIGHT = 60`, `MAX_HEIGHT = 900`.

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/forge/pixpin/TextBoxSizeTest.kt`:

```kotlin
package com.forge.pixpin.pin

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBoxSizeTest {

    @Test
    fun `arrastrar en diagonal crece en los dos ejes`() {
        val dims = TextBoxSize.resize(startWidth = 200, startHeight = 100, dxDp = 60f, dyDp = 40f)
        assertEquals(260, dims.width)
        assertEquals(140, dims.height)
    }

    @Test
    fun `arrastrar hacia dentro encoge`() {
        val dims = TextBoxSize.resize(startWidth = 300, startHeight = 200, dxDp = -80f, dyDp = -50f)
        assertEquals(220, dims.width)
        assertEquals(150, dims.height)
    }

    @Test
    fun `no baja de los minimos por mucho que se arrastre`() {
        val dims = TextBoxSize.resize(startWidth = 200, startHeight = 100, dxDp = -9000f, dyDp = -9000f)
        assertEquals(TextBoxSize.MIN_WIDTH, dims.width)
        assertEquals(TextBoxSize.MIN_HEIGHT, dims.height)
    }

    @Test
    fun `no pasa de los maximos por mucho que se arrastre`() {
        val dims = TextBoxSize.resize(startWidth = 200, startHeight = 100, dxDp = 9000f, dyDp = 9000f)
        assertEquals(TextBoxSize.MAX_WIDTH, dims.width)
        assertEquals(TextBoxSize.MAX_HEIGHT, dims.height)
    }

    @Test
    fun `sin arrastre no cambia nada`() {
        val dims = TextBoxSize.resize(startWidth = 330, startHeight = 120, dxDp = 0f, dyDp = 0f)
        assertEquals(330, dims.width)
        assertEquals(120, dims.height)
    }
}
```

- [ ] **Step 2: Ejecutar y comprobar que falla**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --tests "com.forge.pixpin.pin.TextBoxSizeTest" --console=plain
```

Esperado: FALLO de compilación — `Unresolved reference: TextBoxSize`.

- [ ] **Step 3: Implementar**

Crear `app/src/main/java/com/forge/pixpin/pin/TextBoxSize.kt`:

```kotlin
package com.forge.pixpin.pin

/** Tamaño del cuadro de un pin de texto, en dp. */
data class TextBoxDims(val width: Int, val height: Int)

/**
 * Límites del cuadro de un pin de texto.
 *
 * Está aparte del controlador, como [PinZoom], porque es lo único del gesto de
 * redimensionar que se puede comprobar sin un dispositivo delante.
 *
 * Los topes no son decorativos: por debajo del mínimo el cuadro deja de poder
 * agarrarse por su esquina, y por encima del máximo el pin tapa la pantalla que
 * está anotando, que es justo lo contrario de para lo que sirve.
 */
object TextBoxSize {

    const val MIN_WIDTH = 120
    const val MAX_WIDTH = 500
    const val MIN_HEIGHT = 60
    const val MAX_HEIGHT = 900

    /**
     * @param startWidth/startHeight tamaño en dp al empezar el gesto
     * @param dxDp/dyDp desplazamiento del dedo desde que empezó, en dp
     */
    fun resize(startWidth: Int, startHeight: Int, dxDp: Float, dyDp: Float): TextBoxDims =
        TextBoxDims(
            width = (startWidth + dxDp).toInt().coerceIn(MIN_WIDTH, MAX_WIDTH),
            height = (startHeight + dyDp).toInt().coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        )
}
```

- [ ] **Step 4: Ejecutar y comprobar que pasa**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --tests "com.forge.pixpin.pin.TextBoxSizeTest" --console=plain
```

Esperado: `BUILD SUCCESSFUL`, 5 tests en verde.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/forge/pixpin/pin/TextBoxSize.kt app/src/test/java/com/forge/pixpin/TextBoxSizeTest.kt
git commit -m "Límites del cuadro de texto en una función pura, con pruebas"
```

---

### Task 3: Modo `RESIZE` en `OverlayTouchHandler`

**Files:**
- Modify: `app/src/main/java/com/forge/pixpin/pin/OverlayTouch.kt`
- Test: `app/src/test/java/com/forge/pixpin/OverlayTouchHandlerTest.kt`

**Interfaces:**
- Produces: `OverlayTouchHandler.handleRect: android.graphics.Rect?` (coordenadas **locales** de la vista, en px) y tres métodos nuevos en `OverlayTouchHandler.Listener`: `onResizeStart()`, `onResize(dxFromDown: Float, dyFromDown: Float)`, `onResizeEnd()`. Los tres con cuerpo vacío por defecto, para no obligar a `FloatingBallController.BallGestures` a implementarlos.

- [ ] **Step 1: Escribir los tests que fallan**

En `OverlayTouchHandlerTest.kt`, ampliar el `Recorder` con:

```kotlin
        var resizes = 0
        var lastResizeDx = 0f
        var lastResizeDy = 0f
        var resizeEnds = 0

        override fun onResize(dxFromDown: Float, dyFromDown: Float) {
            resizes++
            lastResizeDx = dxFromDown
            lastResizeDy = dyFromDown
        }

        override fun onResizeEnd() { resizeEnds++ }
```

Y añadir tres tests:

```kotlin
    /**
     * El handle es pequeño: tiene que responder al primer píxel, sin esperar al
     * touch slop, o resultaría imposible de agarrar.
     */
    @Test
    fun `el toque en la esquina redimensiona en vez de arrastrar`() {
        handler.handleRect = android.graphics.Rect(170, 270, 200, 300)
        send(down(180f, 280f))
        send(move(240f, 320f))

        assertEquals("no debe arrastrar", 0, recorder.drags)
        assertTrue("debe redimensionar", recorder.resizes > 0)
        assertEquals(60f, recorder.lastResizeDx, 0.01f)
        assertEquals(40f, recorder.lastResizeDy, 0.01f)

        send(up(240f, 320f))
        assertEquals(1, recorder.resizeEnds)
    }

    @Test
    fun `fuera de la esquina se sigue arrastrando`() {
        handler.handleRect = android.graphics.Rect(170, 270, 200, 300)
        send(down(100f, 200f))
        send(move(160f, 260f))

        assertTrue(recorder.drags > 0)
        assertEquals(0, recorder.resizes)
    }

    @Test
    fun `sin handle el comportamiento es el de siempre`() {
        handler.handleRect = null
        send(down(180f, 280f))
        send(move(240f, 320f))

        assertTrue(recorder.drags > 0)
        assertEquals(0, recorder.resizes)
    }
```

- [ ] **Step 2: Ejecutar y comprobar que fallan**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --tests "com.forge.pixpin.pin.OverlayTouchHandlerTest" --console=plain
```

Esperado: FALLO de compilación — `Unresolved reference: handleRect`.

- [ ] **Step 3: Implementar el modo `RESIZE`**

En `OverlayTouch.kt`, cuatro cambios.

**a)** Añadir al `Listener`, después de `onOpacityEnd()`:

```kotlin
        /**
         * Arranca el redimensionado: el dedo ha bajado dentro de [handleRect].
         * No espera al touch slop — un handle de 30 dp no da margen para eso.
         */
        fun onResizeStart() {}
        fun onResize(dxFromDown: Float, dyFromDown: Float) {}
        fun onResizeEnd() {}
```

**b)** Ampliar el enum y añadir la propiedad, justo debajo de `private val slop`:

```kotlin
    private enum class Mode { NONE, DRAG, SCALE, OPACITY, RESIZE }
```

```kotlin
    /**
     * Esquina que redimensiona en vez de arrastrar, en coordenadas LOCALES de la
     * vista (no raw): el dueño la calcula midiendo su propio contenido. null =
     * la ventana no se redimensiona.
     */
    var handleRect: android.graphics.Rect? = null
```

**c)** En `ACTION_DOWN`, tras `multiTouch = false`:

```kotlin
                if (handleRect?.contains(event.x.toInt(), event.y.toInt()) == true) {
                    mode = Mode.RESIZE
                    listener.onResizeStart()
                }
```

**d)** Al principio de `handleMove`, antes de la rama de dos dedos:

```kotlin
    private fun handleMove(event: MotionEvent) {
        // Los deltas van en coordenadas raw: la ventana cambia de tamaño bajo el
        // dedo, así que los locales se falsearían igual que con el arrastre.
        if (mode == Mode.RESIZE) {
            listener.onResize(event.rawX - downX, event.rawY - downY)
            return
        }
```

**e)** En `finishGesture`, añadir la rama al `when`:

```kotlin
            Mode.RESIZE -> listener.onResizeEnd()
```

No hace falta tocar `idle()`: ya exige `mode == Mode.NONE`, así que con `RESIZE` activo el toque, el doble toque y la pulsación larga quedan inhibidos solos.

- [ ] **Step 4: Ejecutar y comprobar que pasan**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`. Los 4 tests que ya había en `OverlayTouchHandlerTest` deben seguir en verde: son la garantía de que arrastrar, pellizcar y la opacidad no se han roto.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/forge/pixpin/pin/OverlayTouch.kt app/src/test/java/com/forge/pixpin/OverlayTouchHandlerTest.kt
git commit -m "Gestos: zona-handle que redimensiona en vez de arrastrar"
```

---

### Task 4: El pin de texto se mide solo y se redimensiona

Aquí se arreglan los defectos 1, 2 y 3 del spec: el gesto de 3 dedos que nunca se ejecutaba, el alto fijo de 120 px y el ancho que no se persistía.

**Files:**
- Modify: `app/src/main/java/com/forge/pixpin/pin/PinWindowController.kt`

**Interfaces:**
- Consumes: `TextBoxSize.resize(...)` y `TextBoxDims` (tarea 2); `OverlayTouchHandler.handleRect`, `onResizeStart/onResize/onResizeEnd` (tarea 3); `PinState.textBoxHeight` (tarea 1).

- [ ] **Step 1: Guardar una referencia al reconocedor de gestos**

El handler se crea en dos sitios (`show()` y `exitAnnotateMode()`) y hay que poder ponerle el `handleRect` en cualquier momento. Añadir junto a los demás campos, después de `private var annotateBar`:

```kotlin
    private var touchHandler: OverlayTouchHandler? = null

    /** Esquina agarrable del cuadro de texto, en coordenadas de la ventana. */
    private var resizeHandle: android.graphics.Rect? = null
```

Y un constructor único, justo antes de `// ---- Gestos ----`:

```kotlin
    /**
     * Un reconocedor nuevo hereda el handle vivo: se recrea al salir del modo
     * anotación y sin esto el cuadro de texto dejaba de poder redimensionarse
     * hasta la siguiente recomposición.
     */
    private fun newTouchHandler(): OverlayTouchHandler =
        OverlayTouchHandler(context, GestureListener()).also {
            it.handleRect = resizeHandle
            touchHandler = it
        }

    private fun setResizeHandle(rect: android.graphics.Rect?) {
        resizeHandle = rect
        touchHandler?.handleRect = rect
    }
```

Sustituir las dos llamadas existentes `OverlayTouchHandler(context, GestureListener())` (en `show()`, línea ~274, y en `exitAnnotateMode()`, línea ~703) por `newTouchHandler()`.

En `minimize()`, tras `exitAnnotateMode()`, añadir `setResizeHandle(null)`: la burbuja no se redimensiona, y sin esto conservaría el rect del pin abierto.

- [ ] **Step 2: Quitar el tamaño explícito de los pines de texto**

En `PinRoot()`, borrar entero el `LaunchedEffect` (líneas 984-991):

```kotlin
        LaunchedEffect(s.type) {
            if (s.type == PinType.TEXT && naturalW == 0) {
                val screenW = context.resources.displayMetrics.widthPixels
                naturalW = (screenW * 0.5f).toInt().coerceAtLeast(1)
                naturalH = 120
                applyContentSize()
            }
        }
```

Con `naturalW == 0`, `applyContentSize()` sale sola (línea 452) y la ventana se queda en `WRAP_CONTENT`: mide lo que mida el texto. Si el import de `LaunchedEffect` queda sin uso, dejarlo: lo usa `rememberPinBitmap`.

- [ ] **Step 3: Mover el tope de zoom del texto a la rama correcta**

El tope de 5× estaba colgando de la rama de tamaño explícito, que los pines de texto ya no toman. En `onScaleStart()`, sustituir las líneas 506-523 por:

```kotlin
            zoomMax = if (naturalW > 0) {
                // Imagen con tamaño explícito: puede pasar del borde de la
                // pantalla para acercarse y leer.
                PinZoom.maxScaleFor(
                    realW = naturalW, realH = naturalH, currentScale = 1f,
                    screenW = metrics.widthPixels, screenH = metrics.heightPixels,
                    overzoom = PinZoom.IMAGE_OVERZOOM
                )
            } else {
                // Texto, color y archivo se miden solos, y ahí la ventana no
                // puede pasar de la pantalla: el tope es llenarla. El texto se
                // queda además en 5×, que es donde deja de leerse mejor.
                val max = PinZoom.maxScaleFor(
                    realW = scaleStartW, realH = scaleStartH, currentScale = scaleStart,
                    screenW = metrics.widthPixels, screenH = metrics.heightPixels
                )
                if (pin.value.type == PinType.TEXT) max.coerceAtMost(5f) else max
            }
```

- [ ] **Step 4: Reescribir `TextPinBody`**

Sustituir el composable entero (líneas 1128-1171) por:

```kotlin
    /**
     * El ancho y el alto los manda el estado, no la medida del texto: es lo que
     * permite estirar el cuadro por su esquina. Sin alto fijado, se ajusta al
     * texto; con él, lo que sobre se desplaza dentro.
     */
    @Composable
    private fun TextPinBody(s: PinState) {
        val zoom by scale
        val density = LocalDensity.current
        // Se lee aquí y no dentro del Canvas: en un DrawScope no hay MaterialTheme.
        val handleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        Box(
            Modifier
                .width(s.textBoxWidth.dp)
                .then(
                    if (s.textBoxHeight != null) Modifier.height(s.textBoxHeight.dp)
                    else Modifier
                )
                // El handle se mide de verdad en vez de calcularse aparte: es lo
                // único que garantiza que la zona que responde al dedo y el
                // triangulito que se ve sean el mismo sitio.
                .onGloballyPositioned { coords ->
                    val origin = coords.positionInRoot()
                    val side = with(density) { HANDLE_DP.dp.roundToPx() }
                    val right = (origin.x + coords.size.width).toInt()
                    val bottom = (origin.y + coords.size.height).toInt()
                    setResizeHandle(
                        android.graphics.Rect(right - side, bottom - side, right, bottom)
                    )
                }
        ) {
            Text(
                text = s.text.orEmpty(),
                fontSize = (14f * zoom).sp,
                lineHeight = (20f * zoom).sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    // fillMaxWidth y no fillMaxSize: sin alto fijado, el Box se
                    // ajusta al texto, y un hijo que llenase el alto máximo lo
                    // estiraría hasta el tope de la pantalla.
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            )
            Canvas(
                modifier = Modifier
                    .size(HANDLE_DP.dp)
                    .align(Alignment.BottomEnd)
            ) {
                // Tres rayas en diagonal, el gesto universal de "estírame".
                val stroke = 1.5.dp.toPx()
                val color = handleColor
                for (i in 1..3) {
                    val inset = size.width * (i / 4f)
                    drawLine(
                        color = color,
                        start = Offset(size.width - inset, size.height),
                        end = Offset(size.width, size.height - inset),
                        strokeWidth = stroke
                    )
                }
            }
        }
    }
```

Añadir a la `companion object` privada, junto a `BUBBLE_DP`:

```kotlin
        /** Lado de la esquina agarrable del cuadro de texto, en dp. */
        const val HANDLE_DP = 30
```

**Imports a añadir:** `androidx.compose.foundation.Canvas`, `androidx.compose.foundation.layout.height`, `androidx.compose.foundation.verticalScroll`.

**Imports que quedan sin uso y hay que quitar:** `androidx.compose.ui.input.pointer.pointerInput` y `androidx.compose.runtime.mutableIntStateOf` — los usaba solo el gesto de 3 dedos que se acaba de borrar. `widthIn` y `TextOverflow` **se quedan**: los sigue usando `FilePinBody`.

- [ ] **Step 5: Conectar el gesto en `GestureListener`**

Añadir en la clase `GestureListener`, después de `onOpacityEnd()`:

```kotlin
        private var resizeStartW = 0
        private var resizeStartH = 0

        override fun onResizeStart() {
            closeActionBar()
            val density = context.resources.displayMetrics.density
            resizeStartW = pin.value.textBoxWidth
            // Sin alto fijado aún, se parte del que tenga el pin ahora mismo:
            // así el cuadro no pega un salto en el primer píxel de arrastre.
            resizeStartH = pin.value.textBoxHeight
                ?: ((window?.view?.height ?: 0) / density).toInt()
                    .coerceAtLeast(TextBoxSize.MIN_HEIGHT)
        }

        override fun onResize(dxFromDown: Float, dyFromDown: Float) {
            val density = context.resources.displayMetrics.density
            val dims = TextBoxSize.resize(
                startWidth = resizeStartW,
                startHeight = resizeStartH,
                dxDp = dxFromDown / density,
                dyDp = dyFromDown / density
            )
            if (dims.width != pin.value.textBoxWidth ||
                dims.height != pin.value.textBoxHeight
            ) {
                pin.value = pin.value.copy(
                    textBoxWidth = dims.width,
                    textBoxHeight = dims.height
                )
            }
        }

        override fun onResizeEnd() {
            keepReachable()
            // Se persiste al soltar, no en cada muestra: scheduleSave ya hace
            // debounce, pero escribir el estado por píxel arrastrado es ruido.
            callbacks.onPinChanged(this@PinWindowController)
        }
```

- [ ] **Step 6: Compilar y pasar la suite**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`. No hay tests nuevos aquí: lo probable ya se probó en las tareas 2 y 3, y lo que queda es cableado de Compose.

- [ ] **Step 7: Comprobación manual**

Instalar y comprobar cuatro cosas, que es lo que ninguna prueba JVM cubre:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:installDebug --console=plain
```

1. Copiar un texto de 30 líneas y pinearlo: se ve entero y se desplaza, no se corta a 120 px.
2. Arrastrar la esquina inferior derecha: el cuadro cambia de ancho y alto y **no se mueve** el pin.
3. Arrastrar desde cualquier otro punto: el pin se mueve como siempre.
4. Cerrar PixPin desde la notificación y volver a abrir: el cuadro conserva su tamaño.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/forge/pixpin/pin/PinWindowController.kt
git commit -m "Pin de texto: se mide solo y se estira por su esquina"
```

---

### Task 5: Prioridad binaria en la lista de pines

**Files:**
- Modify: `app/src/main/java/com/forge/pixpin/pin/OverlayManager.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `PinState.priority` (tarea 1).
- Produces: `OverlayManager.togglePriority(pinId: String)` y el composable `PinLabel(pin: PinState, modifier: Modifier)`, que usan tanto `PinListRow` como `SavedPinRow`.

- [ ] **Step 1: Añadir las cadenas**

En `strings.xml`, dentro del bloque `<!-- Pines -->`:

```xml
    <string name="priority_chip">PRIORIDAD</string>
    <string name="pin_image_default">PixPin</string>
    <string name="pin_image_priority">Prioridad</string>
    <string name="cd_toggle_priority">Alternar prioridad</string>
```

- [ ] **Step 2: Escribir `togglePriority`**

En `OverlayManager.kt`, donde estaba `assignCategory` (que se borró en la tarea 1):

```kotlin
    /**
     * Alterna la prioridad de un pin. Va por dos caminos porque el pin puede
     * estar vivo en pantalla o ser un guardado que ya se cerró: en el primer
     * caso manda el controlador, en el segundo el archivo de guardados.
     */
    private fun togglePriority(pinId: String) {
        val active = pins[pinId]
        if (active != null) {
            val state = active.snapshot()
            active.updateState(state.copy(priority = !state.priority))
            saveNow()
            if (active.isPinned) {
                scope.launch(Dispatchers.IO) { repo.saveSavedPin(active.snapshot()) }
            }
            refreshPinList()
            return
        }
        scope.launch(Dispatchers.IO) {
            val saved = repo.loadSavedPins().map {
                if (it.id == pinId) it.copy(priority = !it.priority) else it
            }
            repo.saveSavedPins(saved)
            refreshPinList()
        }
    }
```

- [ ] **Step 3: Cambiar `labelFor`**

Sustituir el método (líneas ~900-905) por:

```kotlin
    /**
     * En la imagen el nombre ES el estado: no lleva chip, cambia entero. Antes
     * todas las imágenes se llamaban igual y seis capturas eran seis filas
     * idénticas.
     */
    private fun labelFor(pin: PinState): String = when (pin.type) {
        PinType.IMAGE -> app.getString(
            if (pin.priority) R.string.pin_image_priority else R.string.pin_image_default
        )
        PinType.COLOR -> pin.colorArgb?.let { ContentClassifier.toHex(it) } ?: "Color"
        PinType.TEXT -> pin.text.orEmpty().replace('\n', ' ').take(30)
        PinType.FILE -> pin.fileName ?: app.getString(R.string.pin_type_file)
    }
```

- [ ] **Step 4: Escribir `PinLabel` y el chip**

Añadir, justo antes de `labelFor`:

```kotlin
    /**
     * El nombre del pin en la lista, con su pulsación larga para alternar la
     * prioridad. Lo comparten la fila de activos y la de guardados: eran dos
     * copias del mismo bloque y se desincronizaban a cada cambio.
     */
    @Composable
    private fun PinLabel(pin: PinState, modifier: Modifier = Modifier) {
        val view = LocalView.current
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.pointerInput(pin.id) {
                detectTapGestures(onLongPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    togglePriority(pin.id)
                })
            }
        ) {
            if (pin.priority && pin.type != PinType.IMAGE) {
                PriorityChip()
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = labelFor(pin),
                style = MaterialTheme.typography.bodySmall,
                color = if (pin.priority) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun PriorityChip() {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = app.getString(R.string.priority_chip),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
```

Imports nuevos: `androidx.compose.material3.Surface`, `androidx.compose.ui.platform.LocalView`, `android.view.HapticFeedbackConstants`.

- [ ] **Step 5: Usar `PinLabel` en las dos filas**

En `PinListRow`, sustituir el `Box` del nombre (el que quedó con `detectTapGestures(onLongPress = { })` tras la tarea 1) por:

```kotlin
                PinLabel(
                    pin = pin,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
```

En `SavedPinRow`, lo mismo pero con `horizontal = 6.dp`, que es el padding que tenía:

```kotlin
                PinLabel(
                    pin = pin,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                )
```

- [ ] **Step 6: Compilar y pasar la suite**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Comprobación manual**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:installDebug --console=plain
```

1. Abrir la lista de pines con un pin de texto y uno de imagen.
2. Mantener pulsado el nombre del de texto: vibra y aparece el chip **PRIORIDAD**.
3. Mantenerlo otra vez: el chip desaparece.
4. Mantener pulsado el de imagen: el nombre pasa de «PixPin» a «Prioridad», sin chip.
5. Guardar un pin con la estrella, cerrarlo, y alternarle la prioridad desde la sección de guardados: también funciona y sobrevive a reabrir la lista.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/forge/pixpin/pin/OverlayManager.kt app/src/main/res/values/strings.xml
git commit -m "Prioridad binaria con pulsación larga en la lista de pines"
```

---

### Task 6: El sticker en la esquina del pin

**Files:**
- Modify: `app/src/main/java/com/forge/pixpin/pin/PinWindowController.kt`
- Modify: `app/src/main/java/com/forge/pixpin/pin/OverlayManager.kt`

**Interfaces:**
- Consumes: `PinState.emoji` (tarea 1), `PinLabel` (tarea 5).

- [ ] **Step 1: Añadir el margen al tamaño de la ventana**

Una ventana overlay recorta lo que se dibuje fuera de sus límites: sin margen, un sticker que sobresale no sobresale, se corta.

En `PinWindowController.kt`, añadir a la `companion object` privada:

```kotlin
        /** Hueco que se le deja al sticker fuera del recuadro del pin, en dp. */
        const val STICKER_INSET_DP = 20

        /** Lado del sticker. Mayor que el hueco: por eso pisa la esquina. */
        const val STICKER_SIZE_DP = 34
```

Añadir un campo junto a `naturalW`/`naturalH`:

```kotlin
    /** Margen del sticker ya aplicado a la ventana, para compensar al cambiarlo. */
    private var appliedMargin = 0
```

Y sustituir `applyContentSize()` (líneas 450-462) por:

```kotlin
    /**
     * Lleva la ventana al tamaño que le toca por la escala actual, más el hueco
     * del sticker si lo hay.
     *
     * [naturalW]/[naturalH] siguen siendo el tamaño de la IMAGEN, no el de la
     * ventana: AnnotationCanvas calcula su rectángulo con naturalW × zoom, y si
     * incluyeran el margen los trazos se despegarían de la foto.
     */
    private fun applyContentSize() {
        val p = lp ?: return
        if (naturalW <= 0) return
        if (minimized.value) {
            // La burbuja se mide sola: es un círculo de tamaño fijo.
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            val margin = stickerMarginPx()
            // El margen va arriba: sin compensar, poner un emoji empujaba la
            // imagen hacia abajo y el pin parecía saltar.
            if (margin != appliedMargin) {
                p.y -= margin - appliedMargin
                appliedMargin = margin
            }
            p.width = (naturalW * scale.floatValue).toInt().coerceAtLeast(1) + margin
            p.height = (naturalH * scale.floatValue).toInt().coerceAtLeast(1) + margin
        }
        applyLayout()
    }

    private fun stickerMarginPx(): Int =
        if (pin.value.emoji == null) 0
        else (STICKER_INSET_DP * context.resources.displayMetrics.density).toInt()
```

Los pines de texto, color y archivo, y la burbuja, son ventanas `WRAP_CONTENT`: el margen les crece solo al medirse y no necesitan nada de esto.

- [ ] **Step 2: Dibujar el sticker en `PinRoot`**

Sustituir `PinRoot()` (líneas 980-1003) por:

```kotlin
    @Composable
    private fun PinRoot() {
        val s by pin
        val small by minimized
        Box(modifier = Modifier.graphicsLayer { alpha = contentAlpha.floatValue }) {
            Surface(
                shape = if (small) CircleShape else RoundedCornerShape(12.dp),
                shadowElevation = 8.dp,
                border = when {
                    s.clickThrough -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
                    else -> s.groupId?.let { BorderStroke(2.dp, Color(PinGroups.colorFor(it))) }
                },
                modifier = if (s.emoji != null) {
                    Modifier.padding(top = STICKER_INSET_DP.dp, end = STICKER_INSET_DP.dp)
                } else {
                    Modifier
                }
            ) {
                if (small) BubbleContent(s) else PinBodyContent(s)
            }
            // La pegatina va DESPUÉS del Surface para quedar por encima, y se
            // dibuja dentro de su propia caja: rotado 30°, un glifo de 22 dp
            // ocupa unos 30 dp, así que en una caja de 34 dp no se sale ni se
            // corta contra el borde de la ventana.
            s.emoji?.let { emoji ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(STICKER_SIZE_DP.dp)
                        .rotate(30f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = if (small) 16.sp else 22.sp)
                }
            }
        }
    }
```

Import nuevo: `androidx.compose.ui.draw.rotate`.

- [ ] **Step 3: Mostrar el emoji en la lista**

En `OverlayManager.kt`, dentro de `PinLabel`, antes del bloque del chip:

```kotlin
            pin.emoji?.let {
                // Sin rotar: la pegatina se inclina sobre el pin, no en una lista.
                Text(text = it, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(4.dp))
            }
```

- [ ] **Step 4: Compilar y pasar la suite**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`. En este punto no hay forma de asignar un emoji desde la app todavía — eso es la tarea 7. Para ver el sticker antes de tenerla, se puede poner `emoji = "🔥"` a mano en `newPin()` y quitarlo después; no se commitea ese cambio.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/forge/pixpin/pin/PinWindowController.kt app/src/main/java/com/forge/pixpin/pin/OverlayManager.kt
git commit -m "Pegatina de emoji en la esquina del pin, rotada y desbordando el recuadro"
```

---

### Task 7: Selector de emoji

**Files:**
- Modify: `app/src/main/java/com/forge/pixpin/pin/PinWindowController.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `PinState.emoji` (tarea 1), `applyContentSize()` con margen (tarea 6).

- [ ] **Step 1: Añadir las cadenas**

```xml
    <string name="cd_emoji">Pegatina</string>
    <string name="emoji_none">Sin pegatina</string>
```

- [ ] **Step 2: La lista de emojis**

Al final de `PinWindowController.kt`, junto a `PIN_PALETTE` y `ANNOTATE_TOOLS`, que siguen ese mismo patrón:

```kotlin
/**
 * Pegatinas disponibles. Es una lista fija y no el teclado de emojis del
 * sistema porque una ventana overlay no es enfocable y no puede abrirlo.
 */
private val PIN_EMOJIS = listOf(
    "⭐", "🔥", "❗", "✅", "❌", "⏳",
    "📌", "💡", "🔒", "💰", "📈", "🎯",
    "❤️", "👀", "🔔", "📅", "✏️", "🧠",
    "🚀", "🐛", "☕", "🎵", "📷", "🗑️"
)
```

- [ ] **Step 3: La ventana del selector**

Añadir el campo junto a `annotateBar`:

```kotlin
    private var emojiPicker: OverlayComposeWindow? = null
```

Y los métodos, después de `closeAnnotateBar()`:

```kotlin
    /**
     * Va en ventana propia por el mismo motivo que la barrita de anotación: la
     * del pin se queda con TODOS los toques y ningún botón dentro de ella se
     * enteraría.
     */
    private fun openEmojiPicker() {
        if (emojiPicker != null) return
        closeActionBar()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        val picker = OverlayComposeWindow(context) { EmojiPickerContent() }
        emojiPicker = picker
        runCatching {
            wm.addView(picker.view, params)
            picker.onAttached()
        }.onFailure { emojiPicker = null }
    }

    private fun closeEmojiPicker() {
        val picker = emojiPicker ?: return
        runCatching { wm.removeView(picker.view) }
        picker.onDetached()
        emojiPicker = null
    }

    private fun setEmoji(value: String?) {
        pin.value = pin.value.copy(emoji = value)
        // Poner o quitar la pegatina cambia el tamaño que necesita la ventana.
        applyContentSize()
        closeEmojiPicker()
        callbacks.onPinChanged(this)
    }
```

- [ ] **Step 4: El contenido del selector**

Después de `AnnotateBarContent()`:

```kotlin
    @Composable
    private fun EmojiPickerContent() {
        Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PIN_EMOJIS.chunked(6).forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        row.forEach { emoji ->
                            Text(
                                text = emoji,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .padding(6.dp)
                                    .clickable { setEmoji(emoji) }
                            )
                        }
                    }
                }
                TextButton(onClick = { setEmoji(null) }) {
                    Text(context.getString(R.string.emoji_none))
                }
            }
        }
    }
```

Imports nuevos: `androidx.compose.material3.TextButton`.

- [ ] **Step 5: El botón en la barra de acciones**

En `ActionBarContent()`, entre el bloque `if (s.type == PinType.IMAGE) { ... }` y el `IconButton` del marcador:

```kotlin
                IconButton(onClick = { openEmojiPicker() }) {
                    Icon(
                        Icons.Filled.EmojiEmotions,
                        contentDescription = context.getString(R.string.cd_emoji),
                        tint = if (s.emoji != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
```

Import nuevo: `androidx.compose.material.icons.filled.EmojiEmotions`.

- [ ] **Step 6: Cerrar la ventana en cada salida**

El selector es una ventana más y se queda huérfana flotando si el pin desaparece — el mismo fallo que ya se corrigió en su día con la barra de acciones. Añadir `closeEmojiPicker()` en tres sitios:

- En `hideView()`, junto a `closeActionBar()`.
- En `setViewVisible(visible)`, dentro del `if (!visible)`.
- En `minimize()`, junto a `closeActionBar()`.

- [ ] **Step 7: Compilar y pasar la suite**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Comprobación manual**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:installDebug --console=plain
```

1. Mantener pulsado un pin de imagen: sale la barra con **cinco** botones.
2. Tocar el de la carita: sale la rejilla. Elegir uno: se pega en la esquina superior derecha, torcido, sobresaliendo del recuadro y **sin cortarse**.
3. Comprobar que la imagen **no da un salto** al ponerlo ni al quitarlo.
4. Pellizcar para agrandar: el sticker sigue en su esquina.
5. Dibujar encima con el lápiz: los trazos siguen cuadrando con la foto (es lo que rompería si `naturalW` incluyera el margen).
6. Doble toque para minimizar: el emoji viaja en la burbuja.
7. Repetir con un pin de texto y uno de archivo.
8. Reiniciar PixPin: el emoji sigue ahí.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/forge/pixpin/pin/PinWindowController.kt app/src/main/res/values/strings.xml
git commit -m "Selector de pegatinas en la barra de acciones del pin"
```

---

### Task 8: `Annotation.boxWidth` y los dos renderizadores

**Files:**
- Modify: `app/src/main/java/com/forge/pixpin/annotate/Annotation.kt:38-47`, y `AnnotationGeometry.boundingBox`
- Modify: `app/src/main/java/com/forge/pixpin/annotate/AnnotationCanvas.kt:216-227`
- Modify: `app/src/main/java/com/forge/pixpin/annotate/AnnotationRenderer.kt:81-90`
- Test: `app/src/test/java/com/forge/pixpin/AnnotationGeometryTest.kt`

**Interfaces:**
- Produces: `Annotation.boxWidth: Float?` (px de imagen; null = texto suelto de una línea, como hasta ahora), `AnnotationGeometry.TEXT_BOX_PAD: Float` y `AnnotationGeometry.textBoxBounds(a: Annotation): FloatArray` que devuelve `[left, top, right, bottom]`.

- [ ] **Step 1: Escribir los tests que fallan**

Añadir a `AnnotationGeometryTest.kt`:

```kotlin
    @Test
    fun `un texto con cuadro ocupa el ancho del cuadro`() {
        val a = Annotation(
            type = AnnotationType.TEXT,
            points = listOf(Pt(100f, 50f)),
            color = 0xFFFF0000.toInt(),
            strokeWidth = 20f,
            text = "hola",
            boxWidth = 200f
        )
        val b = AnnotationGeometry.textBoxBounds(a)
        assertEquals(100f, b[0], 0.01f)
        assertEquals(50f, b[1], 0.01f)
        assertEquals(300f, b[2], 0.01f)
        assertTrue("debe tener alto", b[3] > b[1])
    }

    @Test
    fun `un texto largo con cuadro estrecho ocupa mas alto que uno corto`() {
        fun bounds(text: String) = AnnotationGeometry.textBoxBounds(
            Annotation(
                type = AnnotationType.TEXT,
                points = listOf(Pt(0f, 0f)),
                color = 0,
                strokeWidth = 20f,
                text = text,
                boxWidth = 120f
            )
        )
        val corto = bounds("hola")
        val largo = bounds("hola ".repeat(40))
        assertTrue("el largo debe ocupar más alto", largo[3] > corto[3])
    }

    @Test
    fun `un texto sin cuadro sigue midiendose en una linea`() {
        val a = Annotation(
            type = AnnotationType.TEXT,
            points = listOf(Pt(10f, 10f)),
            color = 0,
            strokeWidth = 20f,
            text = "hola"
        )
        val b = AnnotationGeometry.textBoxBounds(a)
        assertEquals(10f, b[0], 0.01f)
        assertTrue(b[2] > b[0])
    }
```

Comprobar que el archivo importa `org.junit.Assert.assertTrue`; si no, añadirlo.

- [ ] **Step 2: Ejecutar y comprobar que fallan**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --tests "com.forge.pixpin.annotate.AnnotationGeometryTest" --console=plain
```

Esperado: FALLO de compilación — `Cannot find a parameter with this name: boxWidth`.

Nota: el paquete del test debe coincidir con el que ya tenga el archivo; si está en `com.forge.pixpin`, usar ese en el `--tests`.

- [ ] **Step 3: Añadir el campo y la geometría**

En `Annotation.kt`, dentro de la `data class Annotation`, después de `variant`:

```kotlin
    val variant: Int = 0, // MOSAIC: 0 = pixelado, 1 = desenfoque
    /**
     * Solo en TEXT: ancho del cuadro en px de imagen. Con null el texto se
     * dibuja suelto en una línea, que es como se guardaron los de la v0.2.
     */
    val boxWidth: Float? = null
```

Y en `object AnnotationGeometry`, añadir:

```kotlin
    /** Margen interior del cuadro de texto, en px de imagen. */
    const val TEXT_BOX_PAD = 8f

    /**
     * Rectángulo [l, t, r, b] que ocupa una anotación de texto.
     *
     * El alto se estima contando caracteres por línea en vez de medirlo: medirlo
     * de verdad exige StaticLayout, que es de Android, y esto solo lo usan el
     * borrador y el toque para reeditar — ahí una aproximación basta y se puede
     * comprobar sin dispositivo.
     */
    fun textBoxBounds(a: Annotation): FloatArray {
        val p = a.points.first()
        val avgChar = a.strokeWidth * 0.55f
        val width = a.boxWidth
        if (width == null) {
            val w = avgChar * (a.text?.length ?: 0)
            return floatArrayOf(p.x, p.y - a.strokeWidth, p.x + w, p.y)
        }
        val perLine = ((width - TEXT_BOX_PAD * 2) / avgChar).toInt().coerceAtLeast(1)
        val lines = a.text.orEmpty().split('\n').sumOf { line ->
            maxOf(1, (line.length + perLine - 1) / perLine)
        }
        val h = lines * a.strokeWidth * 1.3f + TEXT_BOX_PAD * 2
        return floatArrayOf(p.x, p.y, p.x + width, p.y + h)
    }
```

Y hacer que `boundingBox` delegue en él para los textos, añadiendo al principio del método:

```kotlin
    fun boundingBox(annotation: Annotation): FloatArray {
        // El texto no se mide por sus puntos: solo tiene uno, el de anclaje.
        if (annotation.type == AnnotationType.TEXT) return textBoxBounds(annotation)
        var l = Float.MAX_VALUE
```

- [ ] **Step 4: Ejecutar y comprobar que pasan**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`. Si algún test previo de `AnnotationGeometryTest` o `AnnotationControllerTest` se rompe por el cambio de `boundingBox`, revisarlo: el borrador ahora acierta más sobre los textos, y si el test daba por buena la medida vieja, hay que actualizarlo dejando por escrito el porqué.

- [ ] **Step 5: Dibujar el cuadro en la previsualización Compose**

En `AnnotationCanvas.kt`, sustituir la rama `AnnotationType.TEXT` (líneas 216-227) por:

```kotlin
                AnnotationType.TEXT -> {
                    val pos = a.points[0].toView()
                    val style = TextStyle(
                        color = Color(a.color),
                        fontSize = (a.strokeWidth * scale).toSp()
                    )
                    val boxW = a.boxWidth
                    if (boxW == null) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = a.text.orEmpty(),
                            topLeft = pos,
                            style = style
                        )
                    } else {
                        val pad = AnnotationGeometry.TEXT_BOX_PAD * scale
                        val outer = boxW * scale
                        val layout = textMeasurer.measure(
                            text = a.text.orEmpty(),
                            style = style,
                            constraints = Constraints(
                                maxWidth = (outer - pad * 2).toInt().coerceAtLeast(1)
                            )
                        )
                        drawRect(
                            color = Color(a.color),
                            topLeft = pos,
                            size = Size(outer, layout.size.height + pad * 2),
                            style = Stroke(width = 1.5f * scale)
                        )
                        drawText(layout, topLeft = Offset(pos.x + pad, pos.y + pad))
                    }
                }
```

Imports nuevos: `androidx.compose.ui.unit.Constraints`. `Stroke`, `Size` y `Offset` ya se usan en el archivo; comprobarlo.

- [ ] **Step 6: Dibujar el cuadro en el horneado**

En `AnnotationRenderer.kt`, sustituir la rama `AnnotationType.TEXT` (líneas 81-90) por:

```kotlin
                AnnotationType.TEXT -> {
                    paint.style = Paint.Style.FILL
                    paint.textSize = a.strokeWidth
                    val boxW = a.boxWidth
                    val left = a.points[0].x - ox
                    val top = a.points[0].y - oy
                    if (boxW == null) {
                        canvas.drawText(
                            a.text.orEmpty(), left, top + a.strokeWidth * 0.9f, paint
                        )
                    } else {
                        // Mismo cálculo que en AnnotationCanvas: si los dos
                        // renderizadores no coinciden, lo exportado no es lo
                        // que se vio en pantalla.
                        val pad = AnnotationGeometry.TEXT_BOX_PAD
                        val body = a.text.orEmpty()
                        val inner = (boxW - pad * 2).toInt().coerceAtLeast(1)
                        val layout = StaticLayout.Builder
                            .obtain(body, 0, body.length, TextPaint(paint), inner)
                            .build()
                        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = a.color
                            style = Paint.Style.STROKE
                            strokeWidth = 1.5f
                        }
                        canvas.drawRect(
                            left, top, left + boxW, top + layout.height + pad * 2, border
                        )
                        canvas.save()
                        canvas.translate(left + pad, top + pad)
                        layout.draw(canvas)
                        canvas.restore()
                    }
                }
```

Imports nuevos: `android.text.StaticLayout`, `android.text.TextPaint`.

- [ ] **Step 7: Compilar y pasar la suite**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/forge/pixpin/annotate/Annotation.kt app/src/main/java/com/forge/pixpin/annotate/AnnotationCanvas.kt app/src/main/java/com/forge/pixpin/annotate/AnnotationRenderer.kt app/src/test/java/com/forge/pixpin/AnnotationGeometryTest.kt
git commit -m "Anotación de texto: cuadro con ajuste de línea en los dos renderizadores"
```

---

### Task 9: Diálogo de texto con tamaño y ancho ajustables

**Files:**
- Modify: `app/src/main/java/com/forge/pixpin/annotate/AnnotationController.kt:199-207`
- Modify: `app/src/main/java/com/forge/pixpin/capture/CaptureActivity.kt:371-382`, `:494-519`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `Annotation.boxWidth` (tarea 8), `AnnotationGeometry.boundingBox` (tarea 8).
- Produces: `AnnotationController.addText(pt: Pt, text: String, fontSize: Float, boxWidth: Float?)`, `AnnotationController.replaceText(index: Int, text: String, fontSize: Float, boxWidth: Float?)`, `AnnotationController.textAt(pt: Pt): Int` (índice en `annotations`, o −1), y el estado recordado `AnnotationController.lastTextSize` / `lastTextBoxWidth`.

- [ ] **Step 1: Añadir las cadenas**

```xml
    <string name="text_size">Tamaño</string>
    <string name="text_box_width">Ancho</string>
    <string name="text_no_box">Sin cuadro</string>
    <string name="text_preview">Vista previa</string>
```

- [ ] **Step 2: Ampliar `AnnotationController`**

Sustituir `addText` (líneas 199-207) por:

```kotlin
    /**
     * Tamaño y ancho del último texto puesto. Poner tres seguidos del mismo
     * tamaño no debería obligar a ajustarlo tres veces.
     */
    val lastTextSize = mutableStateOf(40f)
    val lastTextBoxWidth = mutableStateOf<Float?>(240f)

    fun addText(pt: Pt, text: String, fontSize: Float, boxWidth: Float?) {
        if (text.isBlank()) return
        lastTextSize.value = fontSize
        lastTextBoxWidth.value = boxWidth
        undoStack.push(annotations.toList())
        annotations.add(
            Annotation(
                AnnotationType.TEXT, listOf(pt), color.value,
                fontSize, text = text, boxWidth = boxWidth
            )
        )
        refreshUndoState()
    }

    /** Índice del texto que hay bajo [pt], o −1 si no hay ninguno. */
    fun textAt(pt: Pt): Int = annotations.indexOfLast {
        it.type == AnnotationType.TEXT && AnnotationGeometry.boundingBox(it).let { b ->
            pt.x >= b[0] && pt.x <= b[2] && pt.y >= b[1] && pt.y <= b[3]
        }
    }

    /** Sustituye un texto ya puesto, conservando su punto de anclaje. */
    fun replaceText(index: Int, text: String, fontSize: Float, boxWidth: Float?) {
        val old = annotations.getOrNull(index) ?: return
        if (text.isBlank()) return
        lastTextSize.value = fontSize
        lastTextBoxWidth.value = boxWidth
        undoStack.push(annotations.toList())
        annotations[index] = old.copy(
            text = text, strokeWidth = fontSize, boxWidth = boxWidth
        )
        refreshUndoState()
    }
```

`indexOfLast` y no `indexOfFirst`: si hay dos textos solapados, se edita el de encima, que es el que se está viendo.

- [ ] **Step 3: Estado del diálogo en `CaptureActivity`**

Junto a las variables `textPoint`, `textInput` y `showTextDialog` que ya existen, añadir:

```kotlin
    var textSize by remember { mutableStateOf(controller.lastTextSize.value) }
    var textBoxWidth by remember { mutableStateOf(controller.lastTextBoxWidth.value) }
    var editingIndex by remember { mutableIntStateOf(-1) }
```

- [ ] **Step 4: El toque abre para crear o para editar**

Sustituir el `pointerInput` de la línea 371-382 por:

```kotlin
                .pointerInput(annotateMode, controller.tool.value) {
                    if (!annotateMode || controller.tool.value != AnnotationType.TEXT) {
                        return@pointerInput
                    }
                    detectTapGestures { pos ->
                        toImage(pos)?.let { pt ->
                            // Tocar un texto ya puesto lo reabre en vez de
                            // plantar otro encima.
                            val hit = controller.textAt(pt)
                            editingIndex = hit
                            if (hit >= 0) {
                                val a = controller.annotations[hit]
                                textInput = a.text.orEmpty()
                                textSize = a.strokeWidth
                                textBoxWidth = a.boxWidth
                            } else {
                                textPoint = pt
                                textInput = ""
                                textSize = controller.lastTextSize.value
                                textBoxWidth = controller.lastTextBoxWidth.value
                            }
                            showTextDialog = true
                        }
                    }
                }
```

- [ ] **Step 5: El diálogo con vista previa y steppers**

Sustituir el `AlertDialog` (líneas 495-519) por:

```kotlin
        if (showTextDialog) {
            AlertDialog(
                onDismissRequest = { showTextDialog = false },
                title = { Text(stringResource(R.string.add_text_title)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        Stepper(
                            label = stringResource(R.string.text_size),
                            value = "${textSize.toInt()}",
                            onLess = { textSize = (textSize - 4f).coerceAtLeast(12f) },
                            onMore = { textSize = (textSize + 4f).coerceAtMost(96f) }
                        )
                        Stepper(
                            label = stringResource(R.string.text_box_width),
                            value = textBoxWidth?.toInt()?.toString()
                                ?: stringResource(R.string.text_no_box),
                            onLess = {
                                val w = textBoxWidth
                                // Por debajo del mínimo se sale del cuadro: es
                                // la forma de volver al texto suelto de siempre.
                                textBoxWidth = if (w == null || w <= 80f) null else w - 20f
                            },
                            onMore = {
                                val w = textBoxWidth
                                textBoxWidth = if (w == null) 80f else (w + 20f).coerceAtMost(600f)
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.text_preview),
                            style = MaterialTheme.typography.labelSmall
                        )
                        // La previsualización evita el ciclo de poner, mirar,
                        // deshacer y repetir. El tamaño va en px de imagen, así
                        // que aquí se muestra tal cual, sin escalar.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .border(1.dp, Color.Gray)
                                .padding(4.dp)
                        ) {
                            Text(
                                text = textInput,
                                color = Color(controller.color.value),
                                fontSize = (textSize / 3f).sp,
                                modifier = if (textBoxWidth != null) {
                                    Modifier.width((textBoxWidth!! / 3f).dp)
                                } else {
                                    Modifier
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (editingIndex >= 0) {
                            controller.replaceText(editingIndex, textInput, textSize, textBoxWidth)
                        } else {
                            controller.addText(textPoint, textInput, textSize, textBoxWidth)
                        }
                        showTextDialog = false
                    }) { Text(stringResource(R.string.add)) }
                },
                dismissButton = {
                    TextButton(onClick = { showTextDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
```

El `/3f` de la previsualización es el factor entre px de imagen y dp de pantalla en un móvil típico: la previsualización orienta sobre la proporción entre tamaño de letra y ancho de cuadro, que es lo que hay que decidir, no sobre el tamaño absoluto final.

- [ ] **Step 6: El composable `Stepper`**

Añadir en `CaptureActivity.kt`, junto a los demás composables privados del archivo (por ejemplo antes de `ColorChip`):

```kotlin
/** Etiqueta, valor y dos botones. No hay sitio para un slider en el diálogo. */
@Composable
private fun Stepper(
    label: String,
    value: String,
    onLess: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onLess) { Text("−", fontSize = 20.sp) }
        Text(value, style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onMore) { Text("+", fontSize = 20.sp) }
    }
}
```

Comprobar que están importados `Column`, `Box`, `Spacer`, `heightIn`, `border`, `IconButton`, `mutableIntStateOf`; añadir los que falten.

- [ ] **Step 7: Compilar y pasar la suite**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
```

Esperado: `BUILD SUCCESSFUL`. Si `AnnotationControllerTest` llamaba a `addText(pt, text)` con dos argumentos, actualizar esas llamadas a la firma nueva.

- [ ] **Step 8: Comprobación manual**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:installDebug --console=plain
```

1. Capturar, entrar en anotar, elegir la herramienta de texto y tocar la imagen.
2. Escribir un párrafo largo: la previsualización lo ajusta al ancho elegido.
3. Subir y bajar el tamaño con −/+: la previsualización responde.
4. Bajar el ancho hasta «Sin cuadro»: el texto pasa a una línea.
5. Confirmar: el texto sale sobre la imagen con su recuadro.
6. Tocar ese texto otra vez: se reabre con su contenido, tamaño y ancho.
7. Guardar la captura y abrir el archivo: **lo exportado coincide con lo que se veía** — es la comprobación que justifica haber tocado los dos renderizadores.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/forge/pixpin/annotate/AnnotationController.kt app/src/main/java/com/forge/pixpin/capture/CaptureActivity.kt app/src/main/res/values/strings.xml
git commit -m "Herramienta de texto: cuadro, tamaño ajustable y reedición"
```

---

### Task 10: Verificación final y documentación

**Files:**
- Modify: `README.md` (si documenta los gestos)
- Modify: `docs/superpowers/specs/2026-07-30-texto-prioridad-sticker-design.md` (solo si algo se implementó distinto)

- [ ] **Step 1: Suite completa y lint**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain
```

Esperado: `BUILD SUCCESSFUL`. Los avisos de deprecación de iconos siguen ahí y no son de este trabajo.

- [ ] **Step 2: Compilar el APK**

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug --console=plain
```

- [ ] **Step 3: Prueba de compatibilidad con datos viejos**

La que ninguna prueba automática cubre del todo: instalar la v0.2 anterior, crear tres pines (una imagen, un texto y un archivo), guardarlos con la estrella, instalar la versión nueva **por encima sin desinstalar** y comprobar que los tres siguen ahí, en su sitio y con su contenido.

- [ ] **Step 4: Actualizar el README si hace falta**

Si `README.md` enumera los gestos del pin, añadir el arrastre de la esquina en los de texto, la pulsación larga en la lista para la prioridad y el botón de pegatina.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/
git commit -m "Documentación al día con los cuadros de texto, la prioridad y las pegatinas"
```

---

## Notas sobre riesgos conocidos

- **`OverlayTouchHandler` lo comparten los pines y la bola flotante.** Los tres métodos nuevos del `Listener` llevan cuerpo por defecto justamente para que `FloatingBallController.BallGestures` no tenga que implementarlos. Si alguna vez se le pone un `handleRect` a la bola sin implementarlos, el gesto se comería el arrastre en silencio.
- **El margen del sticker no escala con el zoom.** Introduce una desviación pequeña en el anclaje del foco del pellizco, que usa `v.width`/`v.height`. Con 20 dp sobre un pin de varios cientos de px es imperceptible; la alternativa era meter el margen en `PinZoom.step`, que hoy es una función pura fácil de probar.
- **La estimación de alto de `textBoxBounds` es aproximada.** Solo la usan el borrador y el toque para reeditar. Si al usarlo resulta que cuesta acertarle a un texto, el arreglo es subir el factor `1.3f`, no medir de verdad: medir exige `StaticLayout` y sacaría la función de la JVM.
- **`pixpin-build/` está commiteado en el repositorio** (923 archivos de salida de compilación). No es de este trabajo y no se toca aquí, pero conviene sacarlo en algún momento: infla el clon y no aporta nada.
