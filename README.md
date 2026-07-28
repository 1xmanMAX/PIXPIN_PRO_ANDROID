# PixPin Android

Captura, anota y **fija notas flotantes** sobre cualquier app de tu móvil.

Adaptación a Android de la idea central de [PixPin](https://pixpin.com/) para Windows —
*capturar cualquier cosa y fijarla en pantalla*— repensada para una pantalla táctil: sin
atajos de teclado, sin menús anidados y sin diálogos intermedios. Todo se hace con gestos.

> **Proyecto personal, no oficial.** No está afiliado a PixPin ni a DepthPixel; no es un
> port de su código, sino una implementación propia en Kotlin de las funciones que me
> resultan útiles en el móvil. Se instala por APK, no está en Google Play.

**Estado:** funcional en Android 10, 12 y 15/16 · Kotlin + Jetpack Compose · 109 tests

[**⬇ Descargar el APK**](https://github.com/1xmanMAX/PIXPIN_PRO_ANDROID/releases/latest)

---

## Índice

- [Qué hace](#qué-hace)
- [Gestos](#gestos)
- [Permisos y por qué](#permisos-y-por-qué)
- [Instalación](#instalación)
- [Compilar desde el código](#compilar-desde-el-código)
- [Arquitectura](#arquitectura)
- [Restricciones de Android que moldean el diseño](#restricciones-de-android-que-moldean-el-diseño)
- [Limitaciones conocidas](#limitaciones-conocidas)
- [Roadmap](#roadmap)
- [Licencia](#licencia)

---

## Qué hace

### Tres formas de disparar una captura

| Disparador | Cómo |
|---|---|
| **Bola flotante** | Un orbe arrastrable siempre visible que se imanta al borde de la pantalla. Un toque abre su menú; **doble toque captura directamente**; una pulsación larga oculta o muestra todos los pines. |
| **Tile de ajustes rápidos** | Se añade al panel desplegable del sistema y captura de un toque. |
| **Notificación persistente** | Botones de *Capturar* y *Pin*, siempre a mano. |

### Captura y recorte

Al disparar, la pantalla se congela y aparece la pantalla de recorte. **Lo que ves es
exactamente lo que se recorta**: se trabaja sobre el fotograma congelado, no sobre la
pantalla en vivo, así que nada se mueve bajo el dedo.

- **Arrastra para recortar** o **toca para la pantalla entera**.
- Las esquinas se ajustan con tiradores; el recorte se puede mover entero.
- **Lupa con selector de color**: mientras ajustas, una lupa muestra el píxel exacto bajo
  el dedo y su valor HEX, que se copia con un toque.
- Los overlays de PixPin (bola y pines) se ocultan durante el fotograma: no salen dentro
  de tu propia captura.
- Una sola barra de acciones, con **Pin** destacado: anotar · scroll · copiar · compartir · cerrar.

### Captura con scroll

Para páginas más largas que la pantalla. Eliges la zona, pulsas **Scroll** y aparece una
barrita abajo: desplazas con el dedo a ritmo normal y PixPin va cosiendo, enseñando cuántas
pantallas lleva. Al pulsar *Listo* la imagen larga entra en la pantalla de siempre, lista
para anotar, fijar, guardar o compartir.

Android no ofrece ninguna API para esto —la captura larga del sistema solo funciona dentro
de apps que la implementan—, así que el desplazamiento se deduce comparando fotogramas.
Las consecuencias, dichas de frente:

- **Ante la duda no cose.** Un tramo mal encajado estropea la imagen entera y no se ve
  hasta el final; descartarlo solo cuesta unos milisegundos. Se rechazan los fotogramas
  ambiguos, los de zonas sin textura y los saltos demasiado grandes; si vas muy rápido, la
  barrita te lo dice.
- **Las cabeceras fijas salen repetidas** si quedan dentro de la zona elegida. La solución
  es elegir la zona sin ellas.
- Tope de **5 pantallas** de alto, por memoria.
- Necesita el **modo Rápido** (sesión de captura abierta). En Discreto te lo pide y retoma
  la captura tras concederlo.

### Anotación

Once herramientas sobre el recorte, con deshacer y rehacer ilimitados:

rectángulo · elipse · flecha · lápiz · resaltador · mosaico/pixelado · texto · borrador ·
**nº de serie** · **polilínea** · **foco**

- **Nº de serie**: cada toque coloca un círculo numerado 1, 2, 3… para explicar pasos.
  Deshacer devuelve la cuenta atrás sola.
- **Polilínea**: rectas encadenadas; se cierra con el botón de la barra o al cambiar de
  herramienta.
- **Foco**: oscurece todo menos la zona marcada.

Las anotaciones son **vectoriales y serializables** (no se "queman" hasta exportar), con
paleta de colores y grosor ajustable. El mosaico se calcula una sola vez sobre la imagen
completa, así que arrastrarlo va fluido aunque tapes media pantalla.

### Escribir a mano y con lápiz óptico

El dibujo a mano alzada está pensado para el lápiz, no solo para el dedo:

- El trazo empieza **en el primer contacto**, sin margen muerto: un punto, una tilde o la
  barra de una «t» se dibujan igual que un trazo largo.
- Se aprovechan **todas las muestras** del digitalizador, no solo una por fotograma —un
  lápiz muestrea a cientos de hercios y el resto viajan como puntos históricos.
- Trazo **suavizado por curvas**, no por segmentos rectos.
- **Presión**: el grosor sigue la fuerza del lápiz. Con el dedo se mantiene constante,
  porque ahí la presión es ruido.
- **Rechazo de palma**: mientras el lápiz está en uso, la mano apoyada no pinta.

### Pines: notas flotantes siempre visibles

Cada pin es una **ventana independiente** del sistema, así que mover uno no afecta a los
demás y todos siguen visibles sobre cualquier app.

| Tipo | Origen | Al tocarlo |
|---|---|---|
| **Imagen** | Una captura, o una imagen del portapapeles | Se copia al portapapeles, con lo que hayas dibujado encima |
| **Texto** | Texto copiado en cualquier app | Se copia al portapapeles |
| **Color** | Un color copiado en formato CSS: `#29B8DB`, `rgb(41,184,219)`, `41, 184, 219`, `orange`… | Se copia el HEX |
| **Archivo** | Cualquier documento compartido a PixPin desde otra app | Se abre con la app que corresponda |

Además de eso:

- **Dibujar sobre el pin sin abrir nada**: el lápiz de la barra del pin activa el modo
  anotación, el pin se queda quieto y se dibuja encima. La barrita de una sola fila
  —herramienta · color · grosor · deshacer · listo— se pega al borde de la pantalla que
  menos tape del pin, y la herramienta activa hace de botón que despliega las demás solo
  mientras eliges. Como las anotaciones se guardan en coordenadas de la imagen, da igual
  anotar con el pin diminuto o a pantalla completa: se ven bien en cualquier tamaño,
  escalan con él, siguen siendo re-editables y se hornean al guardar o copiar.
- **Zoom más allá de la pantalla**: un pin de imagen crece con el pellizco hasta el triple
  de lo que cabe en pantalla, para acercarse a leer letra pequeña. Nunca se pierde: al
  soltarlo siempre queda un trozo agarrable, y arrastrarlo lo recorre.
- **Opacidad por pin**, ajustable con dos dedos en vivo.
- **Toques a través** (*click-through*): los toques atraviesan el pin hasta la app de
  abajo; un borde de color indica que está activo. Se desactiva desde la lista de pines.
- **Minimizar en burbuja**: doble toque y el pin se reduce a una burbuja que se queda
  donde estaba; otro doble toque lo devuelve. Arrastrarlo sobre la bola flotante lo aparca
  en una columna ordenada junto a ella.
- **Grupos**: se marcan varios pines en la lista y se agrupan. A partir de ahí se mueven
  juntos —arrastrar uno arrastra a los demás conservando las distancias—, se minimizan en
  **una sola burbuja** y se cierran juntos; un borde del color del grupo los identifica. La
  escala y la opacidad siguen siendo de cada pin: agrupar sirve para colocar, no para
  uniformar.
- **Ocultar todo / mostrar todo** sin cerrar nada.
- **Historial**: los pines cerrados se pueden recuperar; los eliminados, no.
- **Sobreviven al reinicio** del teléfono, con su posición, tamaño y opacidad.
- Los pines pueden salirse de los bordes de la pantalla, pero nunca del todo: siempre
  queda un trozo agarrable.

### Guardar, copiar y compartir

**No hay botón de guardar: se guarda solo.** Toda captura con la que hagas algo —fijarla,
copiarla o compartirla— queda en `Pictures/PixPin`, visible en la galería. Si la cierras
con la ✕ es que no la querías, y entonces no se guarda nada. Al terminar de dibujar sobre
un pin, la versión con lo dibujado también se guarda sola.

### Si algo falla

- **Informe de fallo**: si la app se cierra de forma inesperada, guarda la traza y la
  pantalla principal ofrece compartirla. No hace falta cable ni `adb`.
- **Modo seguro**: mientras haya un informe sin revisar, PixPin no se activa sola al
  reiniciar el móvil, para que un fallo al crear una ventana flotante no pueda dejar la
  app imposible de abrir.

---

## Gestos

Toda la interacción con un pin, sin menús:

| Gesto | Efecto |
|---|---|
| Arrastrar | Mover el pin |
| Arrastrar sobre la bola | Minimizar en burbuja aparcada junto a ella |
| Pellizcar | Escalar, con el punto entre los dedos clavado en su sitio |
| Dos dedos arriba/abajo | Opacidad en vivo |
| Toque | Copiar (imagen, texto, color) o abrir (archivo) |
| Doble toque | Minimizar en burbuja / restaurar |
| Pulsación larga | Barra de acciones: toques a través · **dibujar encima** · cerrar · eliminar |

Y con la bola flotante:

| Gesto | Efecto |
|---|---|
| Toque | Menú: capturar · pin del portapapeles · ocultar todo · lista de pines |
| Doble toque | Capturar directamente |
| Pulsación larga | Ocultar / mostrar todos los pines |
| Arrastrar | Moverla; se imanta al borde más cercano |

---

## Permisos y por qué

| Permiso | Para qué | ¿Imprescindible? |
|---|---|---|
| **Mostrar sobre otras apps** | Los pines y la bola son ventanas del sistema sobre las demás apps | Sí |
| **Grabación de pantalla** (`MediaProjection`) | Tomar el fotograma que se recorta. Se pide con el diálogo estándar de Android | Sí, para capturar |
| **Notificaciones** | Barra persistente con los accesos rápidos | Recomendado |
| **Excluir del ahorro de batería** | Evita que el sistema cierre el servicio y desaparezcan los pines | Recomendado |
| **Arranque al iniciar** | Restaurar los pines tras reiniciar el móvil | Opcional |

Nada sale del teléfono: no hay red, ni analítica, ni cuentas. Las imágenes de los pines
viven en el almacenamiento privado de la app.

### Modo de captura

Android 14+ ya no permite «un permiso, capturas para siempre», así que el modo se elige en
los ajustes:

- **Rápido** — la sesión de captura se mantiene abierta: las capturas son instantáneas y
  fiables. Mientras dura, consume como una grabación de pantalla y el sistema muestra su
  icono; se cierra desde la notificación.
- **Discreto** — la sesión se cierra tras cada captura: sin icono permanente ni gasto,
  pero Android pide permiso cada vez.

---

## Instalación

1. Descarga el APK desde la [última versión](https://github.com/1xmanMAX/PIXPIN_PRO_ANDROID/releases/latest)
   (o compílalo tú, más abajo).
2. Permite la instalación de orígenes desconocidos en tu navegador o gestor de archivos.
3. Abre PixPin y concede los permisos que pide el onboarding.
4. Pulsa **Comenzar**: aparece la bola flotante.

Requiere **Android 10 (API 29) o superior**. Probado en Android 10, 12 y 15/16.

---

## Compilar desde el código

```bash
# JDK 17+ (el JBR de Android Studio sirve)
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"

./gradlew assembleDebug        # APK de depuración
./gradlew testDebugUnitTest    # 109 tests unitarios
./gradlew lintDebug            # análisis estático
```

Necesitas el SDK de Android con la plataforma **android-36** y `build-tools 36`. La ruta
del SDK va en `local.properties` (no se sube al repositorio):

```properties
sdk.dir=C\:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk
```

> El `build.gradle.kts` raíz redirige la carpeta de compilación fuera del proyecto
> (`%LOCALAPPDATA%/pixpin-build`). Es para que el proyecto pueda vivir en una carpeta
> sincronizada en la nube sin que el sincronizador pelee con los archivos temporales; si
> no lo necesitas, borra ese bloque.

---

## Arquitectura

Kotlin + Jetpack Compose, un solo módulo, sin inyección de dependencias ni base de datos:
el estado es pequeño y se guarda en JSON.

```
com.forge.pixpin/
├── capture/    Sesión de MediaProjection, pantalla de recorte, exportación
├── annotate/   Modelo vectorial de anotaciones, undo/redo, dibujado y horneado
├── pin/        Ventanas overlay: gestos, tipos de pin, almacenes, gestor global
├── clipboard/  Lectura y clasificación del portapapeles, receptor de "compartir"
├── floating/   Bola flotante, tile de ajustes rápidos, servicio ambiental
├── data/       Ajustes (DataStore), persistencia de pines (JSON), informe de fallos
└── ui/theme/   Tema Material 3
```

### Piezas principales

| Componente | Responsabilidad |
|---|---|
| `ProjectionSession` | Dueña de la sesión de captura: un único `VirtualDisplay` que vive mientras dura la sesión y conserva el último fotograma recibido |
| `CaptureFlow` | Punto de entrada único de «quiero capturar», venga de donde venga |
| `CaptureService` | Servicio en primer plano que sostiene la sesión (tipo `mediaProjection`) |
| `CaptureActivity` | Recorte, lupa, color, anotación y acciones sobre el fotograma congelado |
| `OverlayManager` | Crea y gobierna todos los pines: visibilidad, historial, persistencia |
| `PinWindowController` | Un pin = una ventana. Gestos, contenido y estado serializable |
| `OverlayTouchHandler` | Reconocedor de gestos en coordenadas absolutas de pantalla |
| `StrokeTouchReader` | Motor de trazo: lee el `MotionEvent` crudo para no perder ni el arranque ni las muestras del lápiz. Lo comparten la pantalla de captura y los pines |
| `StrokeSmoothing` | Suavizado por puntos medios y grosor según presión; alimenta tanto el dibujado en vivo como el horneado |
| `PinZoom` | Matemática pura del pellizco: foco entre los dedos clavado y tope deducido del tamaño natural y la pantalla |
| `PinGroups` | Matemática pura de los grupos: pertenencia, arrastre solidario y disposición al plegar y desplegar la burbuja |
| `OverlayComposeWindow` | Andamiaje para meter Compose en una ventana sin Activity detrás |
| `SelectionGeometry` | Matemática pura del recorte (anclas, esquinas, límites) |
| `ScrollMatcher` | Matemática pura del cosido: resume cada fila en una firma y busca el desplazamiento entre fotogramas, rechazando lo ambiguo |
| `ScrollStitcher` | Guarda solo las tiras nuevas de cada fotograma y las junta al terminar |

La lógica delicada está extraída en objetos puros (`PinZoom`, `PinGroups`,
`SelectionGeometry`, `ContentClassifier`, `AnnotationGeometry`, `UndoStack`,
`StrokeSmoothing`, `ScrollMatcher`) precisamente para poder probarla sin dispositivo. El
resto se cubre con Robolectric, que en los tests del cosido corre en modo gráfico nativo
para trabajar con píxeles de verdad.

### Flujo de una captura

```
Bola / Tile / Notificación
        └─ CaptureFlow ─┬─ ¿sesión viva? ── ProjectionSession.grab()
                        └─ si no: ConsentActivity → CaptureService → sesión → grab()
                                        │
                          (overlays ocultos durante el fotograma)
                                        ↓
                                 CaptureActivity
                       recorte + anotación → Pin | Copiar | Compartir | Scroll
                                    (y guardado automático)
```

---

## Restricciones de Android que moldean el diseño

Buena parte del diseño es consecuencia directa de reglas de la plataforma que no son
evidentes. Quedan aquí por si le ahorran tiempo a alguien:

1. **El consentimiento de captura va ANTES del servicio.** Desde Android 14, arrancar un
   servicio en primer plano de tipo `mediaProjection` sin haber obtenido antes el permiso
   hace que `startForeground()` lance `SecurityException` y el sistema mate la app.
2. **Un token de captura sirve para una sola `createVirtualDisplay()`.** No se puede crear
   un display por captura: hay que crear uno y mantenerlo mientras dure la sesión.
3. **Un espejo de pantalla solo emite fotogramas cuando la pantalla cambia.** Enganchar la
   superficie justo al capturar no garantiza recibir nada; por eso se conserva siempre el
   último fotograma (si no ha llegado uno nuevo es porque nada ha cambiado).
4. **El portapapeles solo se lee con la ventana enfocada**, no basta con que la actividad
   exista: hay que leerlo en `onWindowFocusChanged`.
5. **El permiso sobre una URI compartida muere con la actividad**: el archivo hay que
   copiarlo antes de llamar a `finish()`.
6. **Las actividades lanzadas desde un overlay necesitan `taskAffinity` propio.** Si
   comparten tarea con la pantalla principal, Android trae toda la tarea al frente y el
   usuario acaba viendo —y capturando— la propia app en lugar de la suya.
7. **Compose dentro de una ventana overlay** busca los `ViewTree*Owner` subiendo hasta la
   vista raíz: si solo los tiene un hijo, revienta al adjuntarse.
8. **Atenuar la ventana entera la vuelve intocable** a partir de cierto punto (protección
   contra overlays engañosos). La transparencia se aplica al contenido, no a la ventana.
9. **Arrastrar con los gestos de Compose no funciona si mueves la propia ventana**: los
   deltas son relativos a una ventana que se mueve bajo el dedo y se anulan entre sí. Hay
   que trabajar con `MotionEvent.getRawX/getRawY`.
10. **Una ventana `WRAP_CONTENT` no puede medir más que la pantalla.** Es el techo con el
    que los pines dejaban de crecer mientras la escala seguía subiendo. La salida es darle
    a la ventana un **tamaño explícito en píxeles**, que sí puede pasarse de la pantalla.
11. **Cambiar de tamaño una ventana no recompone nada.** Hay nueva medida y nueva
    disposición, pero Compose solo recompone si cambia un estado que se haya leído. Un
    tamaño guardado en un campo suelto se queda con el valor de la primera vez — y las
    anotaciones dejan de seguir a la imagen al escalarla.
12. **Los gestos de Compose se comen el arranque del trazo** (esperan al *touch slop*) y
    entregan una muestra por fotograma. Un lápiz óptico muestrea a cientos de hercios y el
    resto viajan dentro del evento como puntos históricos: sin leerlos, se tira la mayor
    parte del trazo.
13. **No hay API de captura con scroll.** La del sistema solo funciona dentro de apps que
    la implementan; desde fuera solo queda coser fotogramas y deducir el desplazamiento.

---

## Limitaciones conocidas

- **Imposibles en Android** (fuera de alcance permanente): proyectar una ventana viva de
  otra app, atajos de teclado globales, gestos globales de ratón y arrastrar el contenido
  de un pin hacia otra app.
- **Contenido protegido**: las apps que marcan su ventana como segura (banca, vídeo con
  DRM) salen en negro en la captura. Es una protección del sistema.
- **Fabricantes agresivos con la batería** (Xiaomi, Huawei, Oppo…): si el sistema mata el
  servicio, los pines desaparecen hasta volver a abrir la app. Excluir PixPin del ahorro
  de batería lo evita.
- Aún **sin OCR, sin QR y sin grabación** de GIF/vídeo.
- La **captura con scroll** funciona cosiendo fotogramas, no con una API del sistema: en
  pantallas sin textura o con cabeceras fijas el resultado puede no ser perfecto.
- El APK de depuración pesa ~65 MB porque incluye el paquete completo de iconos de
  Material y no está minificado.

---

## Roadmap

| Fase | Contenido |
|---|---|
| ✅ **1 — MVP** | Disparadores, captura y recorte, 8 herramientas de anotación, pines de imagen/texto/color/archivo, gestos, burbujas, historial, restauración al reinicio |
| ✅ **1.5** | Motor de trazo para lápiz óptico (presión, rechazo de palma, suavizado), anotar sobre un pin flotante, zoom del pin hasta el borde de la pantalla, nº de serie, polilínea y foco |
| ✅ **2** | Grupos de pines |
| ✅ **3** | Captura con scroll |
| **4** | OCR local con ML Kit, reconocimiento de QR, traducción, grabación en GIF/MP4 |

Descartados a propósito: pin de fórmulas LaTeX, motor de scripts y sincronización en la nube.

---

## Documentación de diseño

- [`docs/superpowers/specs/2026-07-26-pixpin-android-design.md`](docs/superpowers/specs/2026-07-26-pixpin-android-design.md) — diseño original y decisiones de producto
- [`docs/superpowers/specs/2026-07-27-correcciones-estabilidad-fluidez.md`](docs/superpowers/specs/2026-07-27-correcciones-estabilidad-fluidez.md) — diagnóstico de estabilidad y fluidez

---

## Licencia

MIT. Ver [LICENSE](LICENSE).

El nombre y la aplicación **PixPin** pertenecen a DepthPixel. Este proyecto es una
implementación personal e independiente, sin relación con ellos ni con su código.
