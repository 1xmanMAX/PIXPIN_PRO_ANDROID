# Plan: la interfaz del chat como Telegram, y el aparato a pleno rendimiento

Fecha: 8-sep-2026. Fuente de Telegram: `/root/hola/telegram`, **12.10.1 (7038)**, commit `62b56a0`.

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

1. Citar y copiar la geometría: barra 2 dp cada 3 dp, alto de la franja, botón redondo,
   y el **botón de transcribir a 30×30 dp** en su esquina (`ChatMessageCell` lo coloca).
2. **Nunca transcribir solo.** Hoy `MensajesStore.anadir` lo lanza al guardar una nota de voz;
   pasa a hacerlo **solo** el botón. Es lo que pidió el usuario y además ahorra batería.
3. La transcripción sigue dentro de la burbuja (ya está), con lo dicho en tinta y lo que falta
   en gris, y tocar un trozo salta ahí (ya está).

### C · La cabecera y los mandos, flotando

1. Citar cómo lo hace: `ChatActivity.java:3686-3688` más lo que dibuje el fondo de la píldora
   (buscar `blurredView`, `SizeNotifierFrameLayout`, `drawBlurRect`).
2. En PixPin: sacar la cabecera del `Scaffold` y ponerla como capa flotante sobre la lista —
   píldora redondeada con el nombre y el número de mensajes, botón de volver en su propio
   círculo, y el avatar/menú en otro. La lista pasa por **debajo**, con relleno arriba.
3. La barra de escribir y el clip, ya flotantes, se igualan al mismo lenguaje.

### D · Carpetas y lista de chats

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
