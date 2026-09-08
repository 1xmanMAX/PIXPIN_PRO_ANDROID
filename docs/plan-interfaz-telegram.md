# Plan: la interfaz del chat como Telegram, y el aparato a pleno rendimiento

Fecha: 8-sep-2026. Fuente de Telegram: `/root/hola/telegram`.

> **Corrección (misma tarde).** Dije «12.10.1, commit 62b56a0» y era impreciso: traje esa
> revisión pero solo saqué al árbol `TMessagesProj/src/main/java/org/telegram/`, así que
> **HEAD y `gradle.properties` siguen diciendo 12.9.2 (`45ab8f4`)**. Dos de los tres agentes
> lo cazaron por su cuenta antes de dar ningún dato, que es exactamente para lo que se les
> pidió citar todo. Los **fuentes Java** que se citan abajo son los de `origin/master`; el
> resto del repositorio, no. Y falta `ui/Adapters/` en el checkout parcial, así que nada que
> dependa de `DialogsAdapter` está verificado.

> **La norma de este plan**: nada se afirma de Telegram sin `fichero:línea` de esa copia. De
> memoria salen números inventados. Cada tarea de abajo dice qué hay que citar.

---

## Lo que ya está comprobado

| Hecho | Dónde |
|---|---|
| La barra del chat **flota**: sin fondo y fuera del contenedor | `ui/ChatActivity.java:3686-3688` — `setAddToContainer(false)`, `setCastShadows(false)`, `setBackground(null)` |
| El botón de transcribir mide **30×30 dp**, icono 26 dp, zona de toque con 8 dp de margen | `ui/Components/TranscribeButton.java:89-92` |
| La onda son barras de **2 dp cada 3 dp** | `ui/Components/SeekBarWaveform.java:417` (`dpf2(2)`) y `:318` (`dpf2(3)`) |
| Telegram **pide la tasa de refresco máxima** de la pantalla | `messenger/AndroidUtilities.java:2818-2833` (`setPreferredMaxRefreshRate`) |
| Y la **baja a 60 Hz** si mide que no llega, con histéresis | `messenger/utils/RefreshRateController.java` — ventana estable 1800 ms, mínimo entre cambios 3000 ms, bajar ≤55 fps, subir ≥58,5 fps |
| **PixPin no pide tasa alta en ninguna ventana** | `grep preferredRefreshRate app/src/main` → vacío |

Ese último es la causa candidata de lo que el usuario nota: en un teléfono de 90 o 120 Hz,
sin pedirla, muchos fabricantes dejan la aplicación a 60. Las apps de dibujo del propio
fabricante sí la piden, y por eso «se sienten» más fluidas con el mismo dibujo.

---

## Cinco frentes

### A · El aparato a pleno rendimiento  *(independiente: no toca la interfaz)*

1. `motor/PantallaFluida.kt` (nuevo, puro donde se pueda):
   - `pedirTasaMaxima(window)`: lee `Display.getSupportedRefreshRates()`, se queda con la
     mayor y la pone en `WindowManager.LayoutParams.preferredRefreshRate`.
   - `ElVigilanteDeFotogramas`: `Window.addOnFrameMetricsAvailableListener` para medir fps de
     verdad; si baja de forma estable, deja de pedir la máxima. Copiar la **política** de
     `RefreshRateController` (los cuatro números de arriba), no el código.
   - Lo puro —decidir subir o bajar dado un historial de fps— va en un objeto sin Android,
     con pruebas: es lo único que se puede comprobar sin teléfono.
2. Aplicarlo en **todas** las ventanas: cada `ComponentActivity` del manifiesto y las ventanas
   overlay (`pin/OverlayComposeWindow`, `floating/`), que son las que más se mueven.
3. Ajuste «Máxima fluidez» encendido de fábrica, para poder apagarlo si un teléfono se calienta.

**Cómo se comprueba**: prueba JVM de la política (sube, baja, histéresis, que no oscile).
En el teléfono no se puede medir desde aquí; se deja dicho.

### B · El reproductor de voz, igual que el suyo

**Ya investigado.** Lo que hay que copiar, con su línea:

| Qué | Valor | Dónde |
|---|---|---|
| Barra de la onda | 2 dp de ancho, una cada 3 dp, cápsula de radio 1 dp | `SeekBarWaveform.java:479-488` |
| Alto de barra | de 2 a 16 dp (`heights[i] ∈ [0,7]`, alto = 2h + 2 dp) | `SeekBarWaveform.java:286, 483-487` |
| Bloque de onda | 30 dp de alto, eje a 15 dp | `ChatMessageCell.java:12737`, `SeekBarWaveform.java:480` |
| **Lo oído no se pinta barra a barra** | un `Path` con todas, `clipPath`, y **dos rectángulos** encima | `SeekBarWaveform.java:396-399, 449-451` |
| Botón de reproducir | círculo de 44 dp (radio 22) | `ChatMessageCell.java:13920`, `RadialProgress2.java:97` |
| Duración | 12 dp, y el punto de «no oído» con radio 3 dp a 6 dp del texto | `Theme.java:8385`, `ChatMessageCell.java:14883` |
| Botón de transcribir | **30 × 24 dp**, radio 8, a 8 dp del borde de la burbuja, centrado en el eje de la onda | `ChatMessageCell.java:14835-14859` |
| Su fondo | el color del icono al **15,6 %** de alfa | `TranscribeButton.java:264, 270` |
| Su carga | un trazo de 1,5 dp recorriendo el **perímetro del rectángulo**, ciclo de 5400 ms | `TranscribeButton.java:377, 449-461` |

> **Y un hallazgo que cambia el encargo: Telegram NO resalta la transcripción al reproducir.**
> Ni por palabra, ni por frase. Su texto pasa por el mismo `drawCaptionLayout` que el pie de
> una foto (`ChatMessageCell.java:23182-23200`), y su API (`TL_messages_transcribeAudio`)
> devuelve solo `text`, sin marcas de tiempo. Buscar `karaoke`, `wordTimestamp` o
> `transcriptionHighlight` en todo el árbol da **cero**. Lo que PixPin ya hace —resaltar el
> párrafo que suena y saltar al tocarlo— es **más** de lo que hace Telegram, así que se
> conserva: copiar su fidelidad aquí sería quitar una función.

1. Citar y copiar la geometría: barra 2 dp cada 3 dp, alto de la franja, botón redondo,
   y el **botón de transcribir a 30×30 dp** en su esquina (`ChatMessageCell` lo coloca).
2. **Nunca transcribir solo.** Hoy `MensajesStore.anadir` lo lanza al guardar una nota de voz;
   pasa a hacerlo **solo** el botón. Es lo que pidió el usuario y además ahorra batería.
3. La transcripción sigue dentro de la burbuja (ya está), con lo dicho en tinta y lo que falta
   en gris, y tocar un trozo salta ahí (ya está).

### C · La cabecera y los mandos, flotando

**Ya investigado, y no es lo que parecía.** No es «una barra sin fondo»: Telegram lo llama
**modo vidrio** y son **tres cápsulas flotantes** que dibuja la propia `ActionBar` en su
`dispatchDraw`, con **desenfoque de verdad** del contenido que pasa por debajo.

| Qué | Valor | Dónde |
|---|---|---|
| Se enciende con | `actionBar.setupGlass(...)` | `ChatActivity.java:4576-4579`, `ActionBar.java:214-251` |
| Las tres cápsulas | volver (círculo de 46 dp), central (46 dp de alto, radio 23), menú | `ActionBar.java:2223-2224, 2263, 2267` |
| Su sitio | `t = alto - (altoBarra + 46)/2 - 6` → en retrato, `barraDeEstado + 5 dp`; 6 dp entre cápsulas | `ActionBar.java:2232-2233` |
| La central se mide al contenido | y se **centra** entre las otras dos, con 380 ms `EASE_OUT_QUINT` | `ActionBar.java:2244-2246, 2169` |
| El desenfoque | `RenderEffect.createBlurEffect` de **40 dp sobre una captura reducida 8×** | `blur3/DownscaleScrollableNoiseSuppressor.java:422-424` |
| Solo si | **API 31+** y la clase de rendimiento del aparato da la talla | `ChatActivity.java:2619`, `SharedConfig.java:1748-1754` |
| **Y si no se puede, NO se degrada: se vuelve opaco** | color al 100 % de alfa | `BlurredBackgroundProviderImpl.java:162-165` |
| Con desenfoque | el color va al **76 %** de alfa | `BlurredBackgroundProviderImpl.java:167-169` |
| Lo que la despega del fondo | filetes de **0,55 dp** arriba y abajo, blanco en claro | `BlurredBackgroundProviderImpl.java:171-175` |
| **No se encoge ni se esconde al desplazar** | comprobado por tres vías | `ChatActivity.java:6833-6945`; sin `setAdaptiveBackground` ni `setAlpha` |
| La lista va **a pantalla completa por debajo** | el hueco lo hace el relleno, no un recorte | `ChatActivity.java:11932-11939, 12030-12034, 18578-18579` |
| El velo que hace legible el paso por debajo | dos degradados de **48 dp**, arriba y abajo | `ChatActivityFadeView.java:47-51`, `ChatActivity.java:6979-6980` |
| La barra de abajo es una **isla** | radio 22 dp, 7 dp de margen lateral, 9 dp sobre el inset, alto mínimo 44 dp | `ChatInputViewsContainer.java:27, 30, 81-82, 234` |
| Los mandos laterales | círculos de 44 dp (radio 22) con 6 dp de toque extra y 10 dp de separación | `ChatActivityBlurredRoundButton.java:34-35, 124-125` |

> **Lo que esto obliga a decidir.** El desenfoque de verdad pide API 31 y un aparato que dé la
> talla. Se copia **su política, no solo su aspecto**: con desenfoque, cápsula translúcida al
> 76 %; sin él, **cápsula opaca**, que es lo que ellos hacen. Nada de un desenfoque barato a
> resolución completa: 40 dp sobre una captura reducida 8× es justo lo que lo hace viable, y
> encaja con la norma de que la fluidez manda.

1. Citar cómo lo hace: `ChatActivity.java:3686-3688` más lo que dibuje el fondo de la píldora
   (buscar `blurredView`, `SizeNotifierFrameLayout`, `drawBlurRect`).
2. En PixPin: sacar la cabecera del `Scaffold` y ponerla como capa flotante sobre la lista —
   píldora redondeada con el nombre y el número de mensajes, botón de volver en su propio
   círculo, y el avatar/menú en otro. La lista pasa por **debajo**, con relleno arriba.
3. La barra de escribir y el clip, ya flotantes, se igualan al mismo lenguaje.

### D · Carpetas y lista de chats

**Ya investigado.** Lo importante, con su línea:

| Qué | Valor | Dónde |
|---|---|---|
| Tira de pestañas | 50 dp de alto (36 útiles + 7 y 7 de relleno) | `DialogsActivity.java:5160-5162` |
| Título | 14 dp en negrita, 24 dp de aire por pestaña | `FilterTabsView.java:909-910, 176` |
| **La activa NO lleva subrayado** | una **píldora detrás del texto**: 28 dp de alto, radio 14, **alfa 31/255** | `FilterTabsView.java:915-918, 1509-1511` |
| Salto entre pestañas | 320 ms con `EASE_OUT_QUINT` | `FilterTabsView.java:860-867` |
| Contador de la pestaña | 17,33 dp de alto, radio 11,5, letra de 11 dp | `FilterTabsView.java:178, 493, 907` |
| Reordenar | solo en modo edición (`isLongPressDragEnabled` devuelve `isEditing`) | `FilterTabsView.java:1908-1911` |
| Fila de chat | 70 dp + 1 píxel, avatar de 52 dp a 11 dp del borde, contenido desde 76 dp | `DialogCell.java:171, 2465-2467` |
| Nombre / resumen / hora | 16 dp negrita / 15 dp / 12 dp | `DialogCell.java:1252-1258`, `Theme.java:7891` |
| Separador | **1 píxel** (no 1 dp), desde 72 dp, `#d9d9d9` | `Theme.java:7632-7633`, `DialogCell.java:4772-4784` |

1. Citar `ui/Components/FilterTabsView.java` y cómo `DialogsActivity` las usa.
2. En PixPin ya hay conversaciones por proyecto (`Conversaciones.kt`): darles **pestañas
   arriba** —Todos, y una por proyecto con actividad— y una vista de lista de chats.

### E · Lo que NO se hace, y por qué

- **Copiar código de Telegram tal cual**: es GPL-2 y PixPin es MIT. Se estudia y se
  reimplementa; se citan sus números y sus decisiones, no se pega su código.
- Resaltar la transcripción **palabra a palabra**: no hay tiempos por palabra guardados.

---

## Cómo se reparte, y por qué en este orden

`guardados/MensajesActivity.kt` tiene **5.789 líneas** y los frentes B, C y D lo tocan los
tres. Dos agentes escribiendo a la vez ahí es un conflicto seguro. Así que:

1. **En paralelo y sin escribir nada**: tres agentes de investigación sobre la fuente de
   Telegram (cabecera, reproductor, carpetas). Devuelven `fichero:línea` y números exactos.
   Cero riesgo de pisarse.
2. **En paralelo y en sus propios archivos**: el frente A (rendimiento), que no toca la
   interfaz.
3. **De uno en uno** sobre `MensajesActivity.kt`: B, luego C, luego D, cada uno compilando y
   pasando las 2.224 pruebas antes de pasar al siguiente.

Cada agente entrega: qué cambió, qué citó de Telegram, qué prueba añadió y **qué no pudo
comprobar sin teléfono**.
