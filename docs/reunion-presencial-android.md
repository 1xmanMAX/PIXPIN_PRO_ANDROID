# Reunión presencial en Android (28-oct-2026)

Lo que pide §3.3 de `docs/novedades-2026-10-25-para-los-otros-aparatos.md` del PC («En el móvil es el
uso natural»), hecho en el teléfono con **el mismo formato** que el PC (`crates/pixpin-proyecto/src/presencial.rs`,
commits `69ac5a89`, `f4aa22db`, `ae055085`, `da5f7739`, `be891856`, `d1ffc126`, `92b2843e`).

El usuario: «supón que estoy en una reunión PRESENCIAL: que siga grabando aunque haya salido de la app,
que aparezca un botón flotante para tomar FOTOS de la reunión y, como las capturas, que todo se adjunte
a la reunión; y las fotos y las capturas a resolución COMPLETA».

## Lo que ve el usuario

1. En el chat, «Grabar clase». Arriba, tres modos: **Audio y capturas**, **Pantalla y sonido** y
   **Presencial** (se recuerda, con la misma palabra que el PC: `presencial`).
2. En «Presencial» elige **Solo audio** (el móvil en la mesa) o **Vídeo de la sala** (apoyado mirando
   a la sala) y, con vídeo, la calidad: **1080p**, **720p** o **Automática** (1080p la primera hora y
   720p después). Y si quiere **la burbuja flotante** encima de otras apps.
3. «Empezar» pide de una vez el micrófono, la cámara y los avisos (Android 13+). Sin cámara se graba
   solo el audio; sin micrófono no se empieza. Con la burbuja y sin permiso de dibujar encima, lleva a
   los ajustes, como la barra de siempre.
4. La pantalla se cierra y queda **la notificación «Grabando…»** con el reloj y **Foto**, **Clip** y
   **Terminar**. Sus botones no abren la app: se puede hacer una foto con el teléfono bloqueado. Si se
   pidió, **la burbuja** (se arrastra) lleva el reloj, Foto (con cuántas van), Clip (con su reloj
   mientras graba), pausa y Terminar.
5. Se puede salir de PixPin, cambiar de app o apagar la pantalla: sigue grabando y la cámara sigue
   haciendo fotos.
6. **Foto**: una foto con la cámara de atrás a su resolución entera, en su minuto (el de cuando se
   pulsó). «Foto guardada en el 1:05».
7. **Clip**: un toque empieza, otro lo para.
8. **Terminar**: entra en el chat **una tarjeta** (la carpeta de la grabación) con la nota, el vídeo,
   las fotos («⏱ 1:05») y los clips («▶ 1:05») en su minuto; la nota se pasa a texto (`[m:ss]` cada
   trozo, como la clase) y sale el aviso «Abrir para estudiar».
9. En **Estudiar la clase** las fotos van como las capturas (en la tira y en grande en su minuto) y
   cada clip va como **▶ «Vídeo · 0:18»** («Vídeo del móvil · 0:18» si `fuente: "movil"`) entre los
   marcadores y en la barra del tiempo; un toque lleva a su minuto y lo abre. Al acercar una foto con
   los dedos se carga **entera** (no la reducida de 2048).

## Lo que se guarda (confirmado contra el PC)

Lo mismo de la tabla del §3 del PC, con sus dos campos nuevos. Las claves de cada objeto, en orden
alfabético, como `serde_json` del PC.

| Mensaje | Lo que lleva |
|---|---|
| La nota de voz (`VOZ`, `Reunión 28-10 10h05.m4a`) | `grabacion: "clase"`, `capturas: [{id, ms}]` (las fotos; siempre, aunque vacía), `video: {id}` con vídeo, y `clips: [{duracionMs, id, ms}]` en orden de `ms` (**sin clips la clave no está**, nunca `[]`) |
| El vídeo de la sala (`ARCHIVO`, `Reunión ….mp4`) | `enGrabacion: {de, ms: 0}` (como el vídeo del §3, sin `fuente`) |
| Cada foto (`IMAGEN`, `foto-<hora>-<n>.jpg`, pie `⏱ m:ss · Reunión …`) | `enGrabacion: {de, ms}` y `fuente: "camara"` |
| Cada clip (`ARCHIVO`, `Reunión … · clip <n>.mp4`) | `enGrabacion: {de, duracionMs, ms}` y `fuente: "camara"` |
| La carpeta (`MINIAPP` «carpeta», §3.2) | `tipo: "grabacion"`, `voz`, `video`, `duracionMs`; la nota y el vídeo sin título, las fotos «⏱ m:ss» y los clips «▶ m:ss», cada uno con su `ms` |

- **`fuente` y nunca `origen`**. Al leer, la línea antigua del PC con `"origen"` dos veces se rescata
  como en el PC (`cuaderno::rescatar`): gana el último, y si es `camara` o `movil` pasa a `fuente` y
  `origen` se queda vacío. Va en el serializador de `Mensaje` (`MensajeSerializer`), así que lo hacen
  todos los que leen mensajes (el chat, la sincronización, los envíos, las copias). Sin eso, el
  teléfono guardaba `origen: "camara"`: el PC lo tomaba por el aparato de donde se copió, y dos fotos
  con el mismo `origen` se pisaban en un envío por Wi-Fi.
- **Conservar al reescribir**: `Mensaje` tiene ahora `clips` y `fuente` (como `capturas`,
  `enGrabacion`, `video`, `marcadores`). `enGrabacion` ya era un objeto JSON entero, así que
  `duracionMs` viajaba; `clips` y `fuente` se perdían al fijar o reaccionar a un mensaje del PC.

## Cómo está hecho

| Pieza | Dónde |
|---|---|
| Lo puro (modos, cámara, resolución, bits, clips, fotos, `fuente`, rescate, la imagen entera, tramos, los mensajes) | `guardados/Presencial.kt` (pruebas: `PresencialTest`, `PresencialEnElAlmacenTest`) |
| El servicio en primer plano `microphone|camera`, la notificación y la burbuja | `guardados/ReunionPresencialService.kt` |
| CameraX (fotos y vídeo a la vez) | `guardados/CamaraDeLaReunion.kt` |
| Recortar clips, unir tramos, poner sonido, la foto en JPEG derecho | `guardados/Mp4DeLaReunion.kt` |
| Preparar (el tercer modo) | `guardados/GrabarClaseActivity.kt` |
| La carpeta con «▶» | `GrabarClase.carpetaDeLaGrabacion`, `CarpetaActivity` |
| Estudiar: clips ▶, la entera al acercar, fotos derechas | `guardados/EstudiarClaseActivity.kt` |

- **La cámara con la app fuera**: el servicio es el `LifecycleOwner` al que se ata CameraX (un
  `LifecycleService` hecho a mano con `LifecycleRegistry`, sin añadir `lifecycle-service`).
- **Fotos y vídeo a la vez con una sola cámara**: se atan juntos un `ImageCapture` (la mayor
  resolución 4:3, `CAPTURE_MODE_MAXIMIZE_QUALITY`) y un `Preview` cuya superficie es la de un
  `MediaRecorder`. La foto sale del mismo flujo mientras se graba: es la combinación «PRIV PREVIEW +
  JPEG MAXIMUM», garantizada desde el nivel LEGACY, así que **grabar no baja la resolución de la foto**.
- **El códec**: `MediaRecorder` y no el `Recorder` de `camera-video`, que no deja elegir HEVC ni el
  sonido. HEVC si el teléfono lo codifica por hardware a ese tamaño (`MediaCodecList`), si no H.264;
  30 fps; 5 Mbps a 1080p (4 con HEVC), 2,5 a 720p (2 con HEVC); AAC mono 48 kHz a 96 kb/s; el giro del
  sensor apuntado en el MP4.
- **La nota sale siempre**: con vídeo, la pista de audio copiada a `.m4a` sin recomprimir.
- **Las fotos**: JPEG de la cámara tal cual si va derecho; si su EXIF dice que va girada se endereza
  (aquí todo lee las fotos sin mirar el EXIF, como `FotoDerecha`) a JPEG 92 **conservando el EXIF**
  (fecha, cámara, exposición, GPS); un HEIC se pasa a JPEG 92 igual. Siempre a resolución entera.
- **El minuto de cada foto** es el de cuando se pulsó, sin las pausas (`GrabarClase.Cronometro`); en
  pausa, el de la pausa.

## Capturas de pantalla a resolución completa

Comprobado: en «Audio y capturas» la captura sale de `ProjectionSession` a los píxeles reales de la
pantalla (`getRealMetrics`), se guarda en PNG y entra en el chat con `aligerar = false, enderezar =
false` (los bytes tal cual); solo se reducen las miniaturas al pintarlas. En «Pantalla y sonido» las
capturas son fotogramas del vídeo (su tamaño es el del vídeo elegido, como en el PC).

## En qué se aparta del PC (y por qué)

- **La calidad automática, en dos tramos**: un codificador no cambia de tamaño a mitad de fichero.
  Pasada la hora se cierra el vídeo de 1080p y sigue otro a 720p (el cronómetro se para el medio
  segundo del cambio). El primero es el `video` de la nota; el segundo entra como un clip largo
  («Reunión … · parte 2.mp4») en su minuto, en `clips`, con `fuente: "camara"`. La nota une el audio
  de los dos tramos.
- **Los clips con vídeo de la sala** se recortan del propio vídeo de la reunión al terminar (la
  cámara ya está grabando): empiezan en el fotograma clave anterior (≤ 1 s antes). Uno que cruce el
  cambio de tramo se corta al final del primero.
- **Los clips con solo audio** se graban sin micrófono (lo tiene la nota) y al terminar se les pone el
  trozo de la nota que sonaba: su sonido es el de la nota (AAC de la nota), no AAC 96 kb/s propio.
- **La cámara se elige sola** (la recordada o la de atrás, `camaraElegida` del PC); no hay selector en
  Preparar.
- **`fallo_de_codigo`** (los `HRESULT` de Windows) no se trae: en Android los fallos de la cámara se
  dicen con «No se pudo hacer la foto» / «No se pudo abrir la cámara».
- **La notificación lleva Foto, Clip y Terminar**; la pausa va en la burbuja.
- Sin nota (muy corta o sin audio), lo de la cámara entra suelto con `fuente: "camara"` y sin
  `enGrabacion`, como el PC.

## Lo que falta ver en un teléfono

- Que la cámara siga con la pantalla apagada en cada marca (algunas capas de fabricante cortan la
  cámara en segundo plano aunque el servicio sea de tipo `camera`).
- La resolución real de la foto mientras se graba vídeo (debería ser la mayor 4:3) y el tiempo que
  tarda la primera foto.
- HEVC: que `prepare()` acepte HEVC con superficie en el teléfono, y si no, que caiga a H.264.
- El giro del vídeo con el teléfono tumbado al empezar, y el de las fotos al girarlo después.
- El cambio a 720p pasada la hora (el hueco y el audio unido de los dos tramos).
- Los botones de la notificación con el teléfono bloqueado, y la burbuja encima de otras apps.
- Que el PC lea los clips recortados y los de solo audio con su sonido.

## Para la cámara del móvil (§3.4)

Esto solo **enseña** lo de `fuente: "movil"` («Vídeo del móvil · 0:18» en Estudiar) y conserva
`clips`/`fuente`. `deAparato` y `tomadaEn` (los campos propios del §3.4) no se conservan aún al
reescribir un mensaje aquí: los añade quien haga la cámara del móvil.
