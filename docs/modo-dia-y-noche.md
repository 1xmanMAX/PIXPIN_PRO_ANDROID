# Modo día y modo noche (2-nov-2026)

## Lo que se reportó

> «El modo noche se ve bien, todo oscuro. Pero el modo día se ve muy mal: en la vista de chat
> los iconos cambian más o menos, pero el resto de la interfaz está oscura, negra, con letras
> NEGRAS. Negro con letras negras.»

## Qué estaba mal

Se comprobó pintando las pantallas de verdad (Robolectric con gráficos nativos, la lista de
chats y las tareas en «Claro»): fondo negro, iconos y textos casi negros encima.

1. **La ventana es negra y muchas pantallas no se pintan su fondo.** `values/themes.xml` pone
   `android:windowBackground = black`. Desde el aspecto Cosmos (17-sep-2026) el `background` del
   esquema Cosmos es transparente para que se vea el cielo, y varias pantallas —la lista de
   chats de `MainActivity`, `TodasLasTareasActivity`…— dejaron de pintarse fondo propio. De
   noche no se nota (debajo hay cielo o negro); **de día asomaba el negro de la ventana con la
   tinta `onSurface` del esquema claro, casi negra, encima.** Los iconos «cambiaban más o
   menos» porque las pastillas de cristal sí toman los colores del tema.
2. **El esquema claro solo tenía los acentos.** Fondos, contenedores y bordes eran los de
   fábrica de Material 3, gris **lila** (`#FFFBFE`, `#E7E0EC`…), y el primario cian claro
   `#29B8DB` daba **2,3 : 1** como texto sobre blanco: los botones de texto («Conceder»,
   «Añadir»…) apenas se leían.
3. **Las barras del sistema seguían al teléfono, no a la aplicación.** `enableEdgeToEdge()` elige
   iconos claros u oscuros por el modo del teléfono: con el teléfono de noche y PinDesk en
   «Claro», hora y batería blancas sobre blanco.
4. **El papel de los visores también seguía al teléfono** (`Oscuro.delTelefono`, el visor HTML,
   la página web exportada): con el teléfono de noche y PinDesk de día, documento negro dentro
   de una aplicación blanca.

## Qué se cambió

- `ui/theme/Theme.kt`
  - `LightColors` escrito entero en el tono de la marca: papel `#F5FAFB`, escalera de
    contenedores `#EFF5F6 → #DDE4E6`, texto `#161D1F`, texto suave `#40484C`; primario
    `#00687D` (cian hondo, 6,1 : 1 sobre el papel) con blanco encima; secundario, terciario y
    error en su tono 40.
  - `VentanaDelTema` / `pintarLaVentana`: con el tema por el ajuste (sin `darkTheme` forzado) y
    ventana opaca, **de día** la ventana se pinta con `background` del esquema claro y las barras
    del sistema llevan iconos oscuros; **de noche y con Cosmos** la ventana sigue negra como
    siempre (la noche no cambia ni un píxel) y los iconos de las barras, claros.
  - `deNocheEnLaApp(contexto)`: si la aplicación está de noche fuera de Compose, por el ajuste
    «Aspecto» (Claro/Oscuro/Cosmos/Sistema/Automático por la hora).
  - `SiempreOscuro { … }`: para hojas y barras oscuras a propósito encima de un documento o de
    fotos; de día les pone el esquema de noche dentro (botones de texto, interruptores,
    deslizadores), de noche no hace nada.
- `office/Oscuro.kt`, `ui/VisorHtmlActivity.kt`, `ui/CompartirPaginas.kt`,
  `guardados/CompartirCarpeta.kt`, `ui/ExportarDocumentoAnotado.kt`: el «de noche» por omisión
  es el de la aplicación (`deNocheEnLaApp`), no el del teléfono. La luna/sol de cada visor
  sigue mandando si se tocó.
- `ui/MandosDeLector.kt` (`HojaDeLector`) y la barra de elegidas de
  `ui/GaleriaDeCapturasActivity.kt`: envueltas en `SiempreOscuro`.

Los esquemas de noche (`DarkColors`, `OledColors`, Cosmos) no se tocaron.

## Las reglas

1. **Colores de interfaz, del tema**: `MaterialTheme.colorScheme.*` (o `Cristal.*` en barras
   flotantes, `ColoresDelChat.*` en el chat). Nada de `Color(0xFF1E1E22)` ni `Color.Black` como
   fondo de una pantalla normal.
2. **Tinta y fondo de la misma pareja**: `onSurface` sobre `surface`/`surfaceContainer*`,
   `onPrimary` sobre `primary`, `onXxxContainer` sobre `xxxContainer`. Un `Text` sin color dentro
   de un `Surface` ya lo hace solo; fuera de un `Surface`, `LocalContentColor` es negro.
3. **El fondo de una pantalla no se puede dar por puesto.** La ventana la pinta `PixPinTheme`,
   pero si una pantalla se pinta su propio fondo, que sea `colorScheme.background`/`surface`.
4. **Lo oscuro a propósito, oscuro con su tinta clara puesta a mano**: cámara, grabación a
   pantalla completa, visor de imagen, islas sobre un documento (`0x…14182B`), la burbuja
   flotante y la barra de los pines. Texto e iconos en `Color.White` explícito; si dentro hay
   componentes de Material (botones de texto, interruptores), envolver en `SiempreOscuro`.
5. **Fuera de Compose**, el modo es `deNocheEnLaApp(contexto)`, nunca el `uiMode` del teléfono
   (salvo dentro de esa misma función, para el ajuste «Sistema»).
6. **Contraste**: todo texto ≥ 4,5 : 1 contra su fondo (WCAG AA). `EsquemasDeColorTest` lo
   comprueba par a par en los cuatro esquemas; `VentanaDelTemaTest`, la ventana y las barras.
7. Los widgets de la pantalla de inicio (`values/colores_widget.xml`, `values-night/`) son
   Cosmos en los dos modos, con tinta clara: oscuros a propósito, se dejan así.

## Lo que queda

- Al abrir la aplicación en modo día, el instante antes del primer fotograma sigue siendo negro
  (la ventana de arranque la pinta el sistema con `values/themes.xml`, que no sabe el ajuste de
  la aplicación).
