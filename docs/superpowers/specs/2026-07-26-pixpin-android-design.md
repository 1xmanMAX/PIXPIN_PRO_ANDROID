# PixPin Android — Documento de Diseño

**Fecha:** 2026-07-26
**Estado:** Aprobado por el usuario (pendiente de revisión final del spec)
**Referencia:** `PixPin_Detailed_Report (2).md` (reporte de funciones de PixPin desktop)
**Objetivo del producto:** app de **uso personal** (instalación por APK, sin restricciones de Play Store) que replica en Android la esencia de PixPin: *capturar, anotar y fijar pines flotantes siempre visibles*.

---

## 1. Decisiones fundamentales

| Decisión | Valor | Motivo |
|---|---|---|
| Stack | **Kotlin + Jetpack Compose** | Las funciones clave son 100% nativas (overlays, MediaProjection, canvas de anotaciones). Máximo rendimiento = "altamente fluida". |
| minSdk | **29 (Android 10)** | El usuario tiene dispositivos con Android 10, 12 y la última versión; debe funcionar en los tres. |
| Distribución | APK personal | Sin políticas de Play Store: libertad total con permisos de overlay y captura. |
| Nombre de trabajo | "PixPin Android" (`com.forge.pixpin`, renombrable) | Uso personal, sin conflicto de marca. |

## 2. Adaptaciones de plataforma (Android ≠ Windows)

| PixPin desktop | En Android |
|---|---|
| Hotkeys globales (`Ctrl+1`, `Ctrl+2`) | **Bola flotante**, **tile de ajustes rápidos**, **notificación persistente** |
| Captura instantánea | **MediaProjection** con consentimiento **una vez por sesión** (servicio en primer plano mantiene el token vivo) |
| Pin siempre visible | **Ventana overlay** (`TYPE_APPLICATION_OVERLAY`, permiso "mostrar sobre otras apps") |
| `Ctrl+2` lee portapapeles | Android 10+ bloquea lectura en segundo plano; al **tocar la bola la ventana obtiene foco** y sí puede leerlo → flujo de un toque |
| Opacidad con `Ctrl+rueda` | Deslizador de opacidad en el menú del pin (vista en vivo) |
| Penetración de ratón | **Click-through**: flags táctiles de la ventana overlay dejan pasar los toques |
| Rueda del ratón = zoom | Pellizco (pinch-zoom) |

**Imposibles en Android (fuera de alcance permanente):** Window Pin (proyección de ventana viva), gestos globales `Win+drag`, arrastrar contenido hacia otras apps, hotkeys globales.

**Descartados por el usuario:** pin LaTeX, motor de scripts, sincronización en la nube.

## 3. Experiencia de usuario

### 3.1 Disparadores
1. **Bola flotante** — orbe arrastrable siempre visible; se retrae a medias en el borde. Tap → menú: *Capturar* / *Pinear portapapeles* / *Ocultar todo* / lista de pines.
2. **Tile de ajustes rápidos** — tap → captura.
3. **Notificación persistente** — botones *Capturar* y *Pinear*.

### 3.2 Flujo de captura
1. Disparo → fotograma de pantalla al instante (sin diálogo si el token está vivo).
2. Pantalla de captura a pantalla completa con la imagen congelada:
   - Selección de región por arrastre.
   - **Lupa + selector de color** (copia HEX/RGB con un toque).
   - Ajuste fino por tiradores en esquinas/bordes.
   - Esquinas redondeadas opcionales; bloqueo de proporción.
3. Barra de acciones: **Anotar**, **Pin** 📌, **Guardar**, **Copiar**, **Compartir**, ✕.

### 3.3 Anotación (v1: 7 herramientas)
Rectángulo/elipse, flecha, lápiz, resaltador, mosaico/desenfoque, texto, borrador.
- Deshacer/rehacer (pila de comandos).
- Paleta de colores + grosor.
- Modelo vectorial serializable → las anotaciones de un pin se pueden **re-editar** después.

### 3.4 Pines (ventanas overlay siempre visibles)
Gestos comunes: arrastrar (mover), pellizcar (zoom), pulsación larga (menú), doble tap (cerrar, configurable).

| Tipo | Origen | Comportamiento |
|---|---|---|
| **Imagen** | Captura o portapapeles | Zoom, re-anotar, guardar |
| **Texto** ⭐ | Texto copiado → tap en bola | Nota flotante con texto seleccionable, ancho máximo con ajuste de línea, colores configurables |
| **Color** | Portapapeles con `#29B8DB`, `rgb(...)`, etc. | Muestrario; toque → copiar en otros formatos (HEX/RGB) |

Menú del pin (pulsación larga): **opacidad** (deslizador en vivo), **click-through**, **bloquear**, anotar, guardar, cerrar, destruir.

- **Opacidad:** deslizador por pin + opacidad por defecto en ajustes.
- **Click-through:** los toques atraviesan el pin; un borde sutil de color indica el estado activo. Se desactiva desde la bola (lista de pines) o con el toggle global.
- **Ocultar/Mostrar todo:** botón en bola y notificación; oculta todos los pines sin cerrarlos.
- **Historial:** los pines cerrados van a un historial (tamaño configurable); restaurar el último desde la bola.
- **Restauración al reiniciar:** los pines no cerrados reaparecen tras reiniciar el teléfono.

## 4. Arquitectura técnica

Componentes con una responsabilidad clara cada uno:

1. **`CaptureService`** — servicio en primer plano (tipo `mediaProjection`). Mantiene vivo el token de captura entre screenshots; extrae fotogramas vía `VirtualDisplay` → `ImageReader` → `Bitmap` (buffers de hardware).
2. **`OverlayManager`** — crea/destruye **una ventana overlay independiente por pin** (mover un pin nunca recompone los demás). Flags táctiles por pin (normal / click-through / oculto), orden Z, restauración en arranque (`BOOT_COMPLETED`).
3. **`PinWindowController`** — uno por pin: vista Compose dentro del overlay (con `LifecycleOwner` propio), gestos, menú, estado serializado (posición, tamaño, opacidad, bloqueo, click-through, tipo, referencia al contenido).
4. **`CaptureActivity`** — actividad translúcida a pantalla completa sobre el fotograma congelado: selección de región, lupa, color picker, lienzo de anotaciones.
5. **`AnnotationEngine`** — modelo vectorial de anotaciones (forma, puntos, color, grosor serializables) + pila de comandos undo/redo + render en un solo `Canvas` acelerado por hardware.
6. **`ClipboardPinReader`** — al tocar la bola (ventana con foco): lee portapapeles y detecta tipo (texto / imagen / color / URI) → crea el pin correspondiente.
7. **Persistencia** — Room (pines, historial), DataStore (ajustes), imágenes en almacenamiento privado (`filesDir/pins/`).
8. **Onboarding** — flujo guiado de permisos: overlay, captura de pantalla, exención de optimización de batería.

**Flujo de datos de una captura:**
`Bola/Tile/Notificación → CaptureService (fotograma) → CaptureActivity (región + anotación) → resultado → Pin | Guardar | Copiar | Compartir`

**Estructura de paquetes (módulo único):**
```
com.forge.pixpin/
├── capture/      (CaptureService, CaptureActivity, selección, lupa)
├── annotate/     (AnnotationEngine, herramientas, undo/redo)
├── pin/          (OverlayManager, PinWindowController, tipos de pin)
├── clipboard/    (ClipboardPinReader, detección de tipo)
├── floating/     (bola flotante, tile, notificación)
├── data/         (Room, DataStore, repositorios)
└── settings/     (UI de ajustes, onboarding)
```

## 5. Rendimiento

- **Una ventana por pin** — mover/redimensionar es una operación del `WindowManager`, no recomposición.
- **Anotaciones a 60/120 fps** — el trazo en curso se dibuja directo en `Canvas` acelerado por hardware, fuera del estado de Compose; solo se recomponen los controles.
- **Fotogramas por GPU** — `ImageReader` con buffers de hardware; bitmaps reciclados y escalados en memoria (crítico en el Android 10).
- **Captura instantánea** — token vivo → tomar el último frame sin diálogos.

## 6. Manejo de errores

| Caso | Comportamiento |
|---|---|
| Permiso de overlay ausente | Onboarding guiado; la bola no aparece hasta concederlo |
| Token de captura revocado/expirado | Re-pedir consentimiento de forma elegante en la próxima captura |
| Servicio matado por ahorro de batería (Xiaomi/Huawei…) | Detección + aviso con atajo a ajustes de batería |
| Poca memoria en fotogramas grandes | Escalado automático + aviso; nunca crash por OOM |
| Portapapeles sin contenido pineable | Mensaje claro ("no hay nada que pinear") |

## 7. Testing

- **Unit tests:** parser de color del portapapeles, `AnnotationEngine` (geometría, undo/redo), detección de tipo de contenido, estado de pines.
- **Tests de UI (Compose):** barras de herramientas, menú del pin.
- **Matriz manual en los 3 dispositivos reales** (Android 10, 12, último): overlay y MediaProjection se comportan distinto entre versiones — validación en dispositivo obligatoria.

## 8. Roadmap

| Fase | Contenido |
|---|---|
| **1 — MVP** | Onboarding/permisos; bola flotante + tile + notificación; captura con selección/lupa/color picker; 7 herramientas de anotación; pines de imagen/texto/color con opacidad + click-through + ocultar todo + bloqueo; guardar/copiar/compartir; historial; restauración al reiniciar |
| **2** | 4 herramientas restantes (nº serie, polilínea, spotlight, marca de agua); pin de archivo; grupos de pines; procesado de imagen (rotar/voltear/grises/invertir/brillo); modo miniatura |
| **3** | OCR local (ML Kit), reconocimiento QR, traducción |
| **4** | Grabación MP4/GIF/WebP, captura con scroll (vía Accessibility) |

*Eliminado del roadmap a petición del usuario: pin LaTeX, motor de scripts, sincronización en la nube.*

## 9. Criterios de éxito (MVP)

1. Capturar una región y fijarla como pin en **≤ 3 toques** desde cualquier app.
2. Copiar texto en cualquier app → un toque en la bola → nota flotante visible.
3. Anotar con lápiz/flecha/texto a 60 fps sin tirones en el Android 10.
4. Los pines sobreviven al reinicio del teléfono.
5. Click-through y ocultar-todo funcionan de forma fiable y reversible.
