# PixPin Android — Diagnóstico y correcciones de estabilidad y fluidez

**Fecha:** 2026-07-27
**Base:** commit `eff9abb` (F1–F5)
**Alcance acordado con el usuario:** estabilidad + fluidez (sin funciones nuevas de PixPin)

---

## 1. Por qué fallaba

### 1.1 Crash al capturar en Android 14/15/16 (bloqueante)

`CaptureService.onCreate()` llamaba a
`startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` **antes** de pedir el
consentimiento de captura. Desde Android 14 el orden obligatorio es el contrario:

1. `MediaProjectionManager.createScreenCaptureIntent()` y consentimiento del usuario
2. arrancar el servicio en primer plano de tipo `mediaProjection`
3. `getMediaProjection()`

Si no se cumplen los requisitos, *«the system throws a SecurityException after you call
startForeground()»* (documentación oficial de tipos de FGS). Android 10 y 12 no hacen esa
comprobación: de ahí que fallara solo en los dispositivos nuevos.

### 1.2 La segunda captura nunca podría funcionar

Un token de MediaProjection sirve para **una sola** llamada a `createVirtualDisplay()`
(«your app must request user consent before each media projection session, where a session
is a single call to createVirtualDisplay(); a MediaProjection token must be used only
once»). `FrameGrabber` creaba un `VirtualDisplay` nuevo en cada captura.

### 1.3 Los pines de archivos e imágenes del portapapeles fallaban en silencio

`ShareReceiverActivity` y `ClipboardPinActivity` llamaban a `finish()` y copiaban el
archivo *después*, en una corrutina del scope de la app. Al terminar la actividad, Android
revoca el permiso sobre la URI → la copia fallaba siempre.

### 1.4 El pin de texto casi nunca leía el portapapeles

Se leía en `onCreate()`, cuando la ventana todavía no tiene **foco de entrada**, requisito
de Android 10+ para acceder al portapapeles. Resultado habitual: «no hay nada que pinear».

### 1.5 Tirones al arrastrar

El arrastre de la bola y de los pines usaba los deltas de gestos de Compose, que son
relativos a la propia ventana **que se está moviendo**: al desplazar la ventana bajo el
dedo, el delta siguiente se anula. Movimiento a saltos, tanto peor cuanto más rápido el
gesto.

### 1.6 Otros defectos encontrados

| Defecto | Efecto |
|---|---|
| La captura incluía los overlays de PixPin | La bola y los pines salían dentro de la propia captura |
| `hideView()` + `show()` no conservaba la posición | «Ocultar todo / mostrar todo» teletransportaba los pines a su sitio original |
| Ancla de selección recalculada en cada evento | Recortar hacia arriba o a la izquierda daba un rectángulo erróneo |
| Mosaico cacheado por `hashCode()` de la anotación en curso | Un `createScaledBitmap` por fotograma durante el trazo |
| Overlays sin `PixPinTheme` | Colores por defecto de Material, no los de la app |
| `ImageStore.saveBitmap` sin protección | Cualquier fallo de disco tumbaba la app |
| `MediaProjection.stop()` reentrante | Riesgo de recursión al cerrar la sesión |
| Recorte y color leídos del fotograma pero mostrando la pantalla viva | Lo que veías no era lo que capturabas |

---

## 2. Qué se cambió

### 2.1 Motor de captura (`ProjectionSession` + `CaptureFlow` + `CaptureService`)

- **`ProjectionSession`**: crea **un solo** `VirtualDisplay` al conceder el permiso y lo
  mantiene mientras dura la sesión. En reposo se le quita la superficie
  (`virtualDisplay.surface = null`) y solo se le engancha el `ImageReader` durante el
  instante de la captura — permitido sin nuevo consentimiento y sin coste de batería.
- **`CaptureFlow`**: punto de entrada único (bola, tile, notificación). Si la sesión está
  viva captura al instante; si no, abre el consentimiento.
- **`CaptureService`**: solo se arranca *después* del consentimiento, cumpliendo el orden
  que exige Android 14+. `START_NOT_STICKY`, y su notificación lleva botón «Detener».
- Los overlays se ocultan durante el fotograma y se restauran al terminar.

### 2.2 Modo de captura configurable (decisión del usuario)

- **Rápido**: la sesión se mantiene → capturas instantáneas, con el icono de grabación del
  sistema visible mientras dure.
- **Discreto**: la sesión se cierra en cuanto se tiene el fotograma → sin icono
  permanente, pero Android pide permiso en cada captura.

### 2.3 Gestos (`OverlayTouchHandler`)

Reconocedor propio sobre `MotionEvent.getRawX/getRawY` (coordenadas absolutas de pantalla,
inmunes al movimiento de la ventana), instalado en un contenedor que intercepta los toques
antes que el contenido Compose.

| Gesto | Pin | Bola |
|---|---|---|
| Arrastrar | Mover (soltar sobre la bola = burbuja) | Mover con imán al borde |
| Pellizcar | Escalar | — |
| 2 dedos ↕ | Opacidad | — |
| Toque | Copiar texto/color · abrir archivo | Menú |
| Doble toque | Minimizar/restaurar en burbuja | Capturar |
| Pulsación larga | Barra de 4 acciones | Ocultar/mostrar todo |

Se retiró el menú grande del pin (con deslizador y seis botones) y el tirador de
redimensionar: sobran con los gestos.

### 2.4 Pantalla de captura

Fotograma congelado a pantalla completa (lo que ves es lo que recortas), arrastrar para
recortar, tocar para la pantalla entera, y **una sola barra**: **Pin** destacado +
anotar / guardar / copiar / compartir / cerrar. Fuera los conmutadores de proporción y
esquinas redondeadas.

### 2.5 Robustez

- `CrashLog`: guarda la traza de cualquier cierre inesperado y la pantalla principal
  ofrece compartirla (no hace falta cable ni logcat).
- Scope de app con `CoroutineExceptionHandler`: un fallo de fondo ya no tumba la app.
- Burbujas aparcadas en columna junto a la bola, sin solaparse.
- Ocultar/mostrar conserva posición, escala y opacidad.

---

## 3. Verificación

- `assembleDebug` ✅ · `testDebugUnitTest` 32 tests ✅ · `lintDebug` sin errores ✅
- Tests nuevos: `SelectionGeometryTest` (5) cubre el recorte en las cuatro direcciones,
  el anclaje de esquinas y los límites; `PinStateSerializationTest` cubre además el
  formato antiguo (pines guardados por versiones anteriores).
- **Pendiente de validación en dispositivo real** (obligatoria en los tres Android):
  captura en Android 15/16, pin de texto desde el portapapeles, pin de documento
  compartido, arrastre y pellizco de pines, burbujas y restauración tras reinicio.

---

## 4. Fuera de alcance (siguiente tanda)

Herramientas de anotación restantes (nº de serie, polilínea, spotlight, marca de agua),
procesado de imagen del pin (rotar/voltear/grises/invertir), grupos de pines, OCR local
con ML Kit, reconocimiento de QR, captura con scroll y grabación GIF/MP4.
