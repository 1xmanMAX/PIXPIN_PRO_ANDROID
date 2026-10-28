# Grabar sin perder nada — Android

> «Todo tiene que ser rápido y fiable al grabar: si se perdiera algo sería malo, se perdería una
> reunión.» — el usuario, 28-oct-2026

Lo de `docs/grabar-sin-perder-nada.md` del PC (`origin/main` 995fd94b; commits c7004fbb, 97ca055d,
5595c79f, d2937bed, 28602e7c, 9b61bda7, 309acc50, 958a35bb, 509c7de8, 4fec53d3, 48761c01), con su
sección «Android» como lista. Vale para «Grabar clase» en sus tres modos: **pantalla** (vídeo),
**capturas** y **presencial** (la reunión con la cámara, en sus formas «vídeo de la sala» y «solo
audio»).

## Lo que ve el usuario

- **Grabando, nada cambia**, salvo tres avisos nuevos en la barra (o la burbuja de la reunión):
  - «Queda poco sitio en el teléfono» (menos de 2 GB libres);
  - «Teléfono casi lleno: el vídeo se cerró y el audio sigue» (menos de 500 MB);
  - «El teléfono paró la app N min; la grabación siguió al volver» (un hueco del reloj de pared).
  - Con menos de 500 MB **al empezar**, el vídeo ni empieza: «Queda muy poco sitio: se graba solo el
    audio».
  - Si el sistema corta la pantalla (el chip de «dejar de compartir», bloquear en algunos teléfonos), el
    vídeo acaba ahí y **la nota sigue**: «El vídeo se cortó; el audio sigue grabándose». Antes eso
    terminaba la clase.
- **Si la app muere a mitad** (Android la mata, se apaga el teléfono, se cierra desde recientes), al
  volver a abrir PixPin, encima de la lista de chats:

  > **Grabación cortada** — Se cortó una grabación (Clase 28-10 10h05, 47 min) en «Física»
  > · Luego · Descartar · **Recuperar**

  «Recuperar» mete en el chat la nota, el vídeo, las capturas, las fotos y los clips **cada uno en su
  minuto**, en su carpeta, exactamente como «Terminar» (es la misma función), con el reloj de pared en
  la nota, y la pasa a texto. «Descartar» vuelve a preguntar («¿Borrar lo grabado…?», con «No»
  delante). «Luego» la deja para la próxima vez que se abra la app.
- Si Android vuelve a crear el servicio de grabar sin intent (`START_STICKY`), sale una notificación
  «Grabación cortada» que abre PixPin con la tarjeta.
- Si guardar falla al terminar, ya no se borra nada: «No se pudo guardar la grabación: queda a salvo y
  se ofrecerá recuperarla al abrir PixPin».

## Cómo

### La nota: `AudioRecord` + respaldo WAV (y por qué así)

Todas las grabadoras de voz (la clase, la reunión, `GrabadoraActivity`, la nota del chat) pasan por
`Voz.empezar`, que **ya usaba `AudioRecord` + `MediaCodec` + `MediaMuxer`** (`Grabador.Realzado`, con el
realce de la voz); `MediaRecorder` solo queda de reserva si el codificador no arranca. Así que no hizo
falta una segunda captura del micrófono (dos `AudioRecord` a la vez no son fiables en todos los
teléfonos) ni cambiar de motor: el mismo PCM que va al codificador —ya realzado, con los silencios de
relleno— se copia a una cola y **un hilo propio** lo escribe en `<nota>.respaldo.wav`
(`audio/RespaldoWav.kt`, el `respaldo.rs` del PC número a número: cabecera «sin fin», búfer de 256 KB,
`FileDescriptor.sync()` cada 5 s de sonido). El hilo del micrófono no espera nunca: si la cola se llena
(el disco atascado), el respaldo se calla y la nota sigue.

- La nota se graba en `<nota>.parte` y se pone en su nombre al cerrarse; si no se cerró (sin `moov`),
  se rehace del respaldo en el acto (`audio/RehacerM4a.kt`: WAV → `MediaCodec` AAC → `MediaMuxer`, a
  `.rehecho.parte` y renombrado).
- Solo la clase y la reunión piden respaldo (`Voz.empezar(..., respaldo = …)`); las notas cortas del
  chat no. Con la grabadora de reserva (`MediaRecorder`) no hay PCM y no hay respaldo.
- Mono a 22 050 Hz (la nota del teléfono): 44 KB/s, unos 160 MB por hora, que se borran al guardar.
- **Auriculares Bluetooth que se van**: un `AudioDeviceCallback` vigila los aparatos; si se quita el
  que daba el sonido, el bucle vuelve a abrir el `AudioRecord` en el acto y rellena el hueco con
  silencio al paso del reloj (lo que ya hacía con `ERROR_DEAD_OBJECT`). El respaldo recibe lo mismo.

### El vídeo: sin sonido y en trozos

`MediaMuxer` no hace MP4 fragmentado. Así que el vídeo **ya no lleva el micrófono** (antes la nota se
sacaba del vídeo: un corte lo perdía todo) y se graba **en trozos** (`guardados/TrozosDeVideo.kt`):

- **Pantalla** (`VideoDeLaClase`): cada 3 min (de grabación, no en pausa) se prepara otra
  `MediaRecorder`, se le da la pantalla con `VirtualDisplay.setSurface` y se cierra la anterior, que
  queda como un MP4 entero. Cada trozo empieza en un fotograma clave (es el primero de su codificador).
- **Cámara** (`CamaraDeLaReunion`, el vídeo de la sala): `setMaxFileSize` (unos 3 min a su tasa) +
  `setNextOutputFile`; el escritor de Android pasa al fichero siguiente en el siguiente fotograma clave,
  sin soltar la cámara. El paso a 720p pasada la hora sigue siendo otro tramo (otro **grupo**).
- Al terminar (o al recuperar) `GuardarLaGrabacion.juntarVideo` junta los trozos de cada grupo **sin
  recodificar** (`MediaExtractor` → `MediaMuxer`), cada uno en su minuto (`TrozosDeVideo.colocar`: donde
  empezó en la nota, o justo detrás del anterior) y **con la pista de la nota** desde ese minuto. Un
  trozo con otra imagen (otro tamaño, otras cabeceras) empieza otra parte.
- Si Android mata la app, se pierde como mucho **el trozo en curso** del vídeo; la nota, como mucho
  los últimos 5 s.

### El diario: `filesDir/grabaciones-a-medias/<marca>/estado.json`

`guardados/GrabacionAMedias.kt` (el `grabacion_a_medias.rs` del PC) y `guardados/DiarioEnMarcha.kt` (lo
de `a_salvo.rs`). Escrito con `AtomicFile` (el `.tmp` + renombrar del PC) en un hilo propio y en orden:

- al empezar, en cada captura o foto (su fichero `captura-N.png/jpg` **se escribe en la carpeta del
  diario en el acto**; en memoria solo queda la ruta), en cada clip (al empezar con su minuto y al
  cerrarse con lo que dura), en cada trozo del vídeo, en cada pausa y **cada 5 s** (el latido);
- **el latido** compara la hora de pared con lo que lleva escrito la nota (`Grabador.llevadoMs`, las
  muestras de verdad, no un cronómetro): si la hora corrió más de 10 s por delante sin estar en pausa
  (Doze, la app congelada), apunta un **hueco** en el reloj;
- **el disco** cada 20 s: `StatFs(filesDir).availableBytes` con los umbrales del PC.

Al guardar bien, el diario y el respaldo se borran. Si guardar falla, se queda todo.

### Recuperar al abrir

`guardados/RecuperarGrabacion.kt`: la tarjeta (`TarjetasDeGrabacionesCortadas`, encima de la lista de
chats en `MainActivity`) mira el disco al ponerse delante; `hallar` → `plan` como el PC (la nota: la ya
cerrada → el `.parte` cerrado → el respaldo de ≥ 700 ms; lo que dura, del respaldo o del último latido;
el vídeo, de sus trozos que se abren). Las que no guardan nada (se cortó al abrir el micrófono) se van
solas, como en el PC. La que se está grabando ahora no sale. Los picos de la onda salen del respaldo.

«Recuperar» llama a `GuardarLaGrabacion.clase` / `.reunion`, **las mismas que «Terminar»** (se sacaron
de los dos servicios para eso).

### Soltar sin recoger (PC `9b61bda7`)

Un clip de la cámara que no se pudo parar bien **ya no se borra**: si lo grabado se abre entra en la
reunión; si no, se queda en el disco y en el diario. Un trozo de vídeo que no se abre tampoco se borra
nunca (ni al recuperar). Lo único que borra lo grabado: ✕ dos veces mientras se graba, «Descartar» (dos
veces) y una nota de menos de 700 ms (un resbalón, como siempre).

## El formato, campo por campo con el PC

### `estado.json`

Los mismos nombres, el mismo orden y los nulos escritos, como `serde_json::to_vec_pretty`:

| Campo | Tipo | Igual que el PC |
|---|---|---|
| `version` | 1 | sí |
| `marca` | la hora de empezar en ms, en texto | sí |
| `proyecto` / `nombre_proyecto` | el chat (vacío: el general) y su nombre | sí |
| `nombre` | «Clase 28-10 10h05» / «Reunión …» | sí |
| `modo` | `pantalla`, `capturas`, `presencial` | sí |
| `microfono` / `equipo` | `true` / `false` (el teléfono no graba el sonido del equipo) | sí |
| `audio` | la nota sin `.parte` (el respaldo es `<audio>.respaldo.wav`) | sí |
| `video` | el vídeo ya junto, o `null` | sí (ver abajo) |
| `reloj` | `{inicio, fin, pausas: [{desde, hasta}], huecos: [{desde, hasta}]}` | sí |
| `latido` | `{utc, ms, pausa}` | sí |
| `capturas` | `[{ms, fichero, de_camara}]` | sí |
| `clips` | `[{ms, ruta, duracion_ms}]` | sí; `ruta` vacía = se recorta del vídeo de la sala |
| `marcadores` | `[{ms, texto}]` | sí (el teléfono aún no pone marcadores grabando) |
| `movil` | `null` (el teléfono no anuncia su grabación a nadie todavía) | sí |
| `solo_la_nota` | `false` | sí |
| `trozos` | `[{ms, ruta, duracion_ms, grupo}]` | **solo el teléfono** |
| `capturas_del_video` | `[ms]` | **solo el teléfono** |

El diario no viaja (es local de cada aparato); los dos campos de más van detrás y el PC los ignoraría.

### `resto.reloj` en la nota

`Mensaje.reloj` (se conserva al reescribir el mensaje) con la forma de `RelojDeGrabacion` del PC. El
modelo, `guardados/RelojDePared.kt`, es **el mismo fichero, byte a byte, que el del trabajo de «las
fotos por su hora»** (y la misma línea de `Mensaje`), para que al juntar las dos ramas no haya dos; lo
de la grabación (el paso desde el diario, la cuenta y cómo se escribe) está aparte, en
`guardados/RelojDeLaGrabacion.kt`. Se escribe con **las claves en orden alfabético** (el PC lo mete en
el `resto` con `to_value`, un `BTreeMap`: así sale byte a byte) y sin lo vacío:

| Campo | Se escribe |
|---|---|
| `aparato` | siempre (el código de este teléfono, `Codigos.deEsteAparato`) |
| `fin` | si acabó (≠ 0) |
| `grabacion` | si se anunció a otro aparato (hoy nunca desde el teléfono) |
| `huecos` | `[[desde, hasta]]`, si hay |
| `inicio` | siempre |
| `pausas` | `[[desde, hasta]]` (`hasta` 0: abierta), si hay |
| `vistoEn` | el último latido, solo si no acabó (una recuperada) |

`RelojDeLaGrabacion.delDiario` es `RelojDeGrabacion::del_diario` y `Diario.msEn` usa
`RelojDeLaGrabacion.msHasta`: un solo reloj de pared. Mientras se graba, el de la grabación en curso
está en `RelojDeLaGrabacion.enCurso` (para lo que llegue con su hora). Lo pone el teléfono al guardar
**y** al recuperar.

## Desviaciones

- **Trozos de la cámara por tamaño**, no por tiempo: `MediaRecorder` solo cambia de fichero al
  acercarse a `setMaxFileSize`; a la tasa de la cámara son unos 3 min.
- **El nombre de la clase** sale de la hora de empezar (antes, de la de terminar): es el del diario.
- **La pantalla con vídeo**: el sonido ya no sale del `MediaRecorder` del vídeo sino de la nota
  (`CAMCORDER` igual), y el `.mp4` final lleva la pista de la nota. Las capturas con vídeo siguen
  saliendo de los fotogramas al terminar; si el vídeo se cortó antes (disco), las de después salen del
  último fotograma.
- **Una clase con vídeo que no pega** (dos partes): entra la primera; la otra queda en el disco.
- La cola de fotos del móvil haciendo de cámara del PC (`sincro/camara-movil` en el PC) no es de este
  trabajo: en el teléfono la lleva `ColaDeLaCamara`, que ya era a prueba de cortes.

## Pruebas

En la JVM: `GrabacionAMediasTest` (el JSON del PC campo por campo y en su orden, el diario nuevo, el
reloj con pausas y una suspensión de 30 min, la cuenta de los huecos igual a la de `resto.reloj`, la
línea de tiempo, `mirar` un MP4 cerrado/cortado/fragmentado/64 bits/nada, el plan desde lo que quedó,
el umbral de 700 ms, los trozos del vídeo, «solo la nota», «Descartar» que no se lleva lo ajeno y la
guarda de espacio), `RespaldoWavTest` (cabecera, cerrado, cortado a mitad de una muestra, volcado sin
cerrar, lo que no es un WAV, `/dev/full`, el hilo, el tiempo al rehacer), `RelojDeLaGrabacionTest` (la
forma exacta y la cuenta) y `TrozosDeVideoTest`. Con Robolectric, `RecuperarGrabacionTest`: el diario con
`AtomicFile` y una escritura a medias, y **una clase cortada que se recupera en su chat** (nota,
capturas en su minuto, onda del respaldo, reloj con pausa y hueco, carpeta; el diario y el respaldo
borrados después), «Descartar» y la que se va sola.

**Hace falta un teléfono** para: matar la app a mitad de una clase (con y sin vídeo) y de una reunión
(con vídeo de la sala) y recuperarla; rehacer la nota del respaldo (`MediaCodec`); juntar los trozos
(que `setSurface` y `setNextOutputFile` cambian de trozo sin cortes visibles en ese teléfono); llenar
el disco a mitad; quitar unos auriculares Bluetooth grabando; y dejar el teléfono con la pantalla
apagada un buen rato para ver los huecos.
