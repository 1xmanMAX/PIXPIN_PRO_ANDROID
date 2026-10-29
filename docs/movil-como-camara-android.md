# El móvil como cámara de la reunión del PC — Android (28-oct-2026)

Lo que hace el teléfono de la guía del PC `docs/movil-como-camara.md`
(repositorio de Windows, `origin/main` bb4c0a2d). Lo pidió el usuario en el PC:
«que el MÓVIL tome las fotos y se envíen directamente y se adjunten aquí», cada
una en el minuto de la grabación en que se hizo, y también clips cortos.

## Qué ve el usuario

1. **El botón de la cámara junto al «+»** de la lista de chats (29-oct-2026):
   mientras un PC del grupo graba sale, del mismo tamaño, a la izquierda del
   «+» de chat nuevo, y los dos quedan centrados donde estaba el «+». Abre la
   cámara. Sin grabación no está y el «+» queda como siempre.
2. Ya no hay tira arriba de la lista ni entrada en el clip (adjuntar): la
   entrada es el botón de arriba, que solo sale cuando sirve.
3. **La pantalla de cámara**, a pantalla completa: la vista previa, un
   disparador grande, a su izquierda el de clip (solo si el PC recibe clips),
   y arriba «● 12:34 grabando en el PC» («‖ 12:34 en pausa»; al acabar,
   «■ 50:00 · la grabación del PC terminó»). Abajo, una miniatura por toma
   con su minuto y ✓ cuando el PC dice «listo» («…» subiendo, «!» negada).
4. **Una notificación** «Subiendo 3 fotos y 1 clip al PC…» mientras queda algo
   por subir, aunque la pantalla se cierre o la grabación ya acabara.

## Qué hay (todo en `app/src/main/java/com/forge/pixpin/sincro/`)

| Qué | Dónde |
| --- | --- |
| `camara1` en `Protocolo.CAPACIDADES` (va en el `hola` de `Sesion`, del `Respondedor` y de la cámara) | `Protocolo.kt` |
| El anuncio (`Anuncio`, mismos campos y orden que el PC), el desfase NTP, los bytes de cabecera, el minuto | `CamaraRemota.kt` |
| El reloj de la grabación (copia de `Reloj` de `camara.rs`) y el del móvil (`elapsedRealtime`, congelado en pausa, `duracion` al acabar) | `RelojDeGrabacion.kt` |
| Preguntar (`camara_remota`), mandar una toma (`suelto` con `destino:"grabacion"`, `{}`/`{"desdeByte":N}`/`{"error"}`, trozos + `{"resumen"}` de lo mandado, `{"t":"listo","ms"}`) y `adios` | `ConexionDeCamara.kt` |
| Subir lo pendiente por una conexión (`SubidaALaGrabacion`) | `ConexionDeCamara.kt` |
| La cola persistente (JSON en `filesDir/sincro/camara-movil/cola.json`, ficheros en `tomas/`) y la espera 2, 4, 8… s hasta 1 min | `ColaDeLaCamara.kt` |
| El aviso del PC en el `Respondedor` (contesta `{"t":"camara_remota","reloj":t1}`) | `Protocolo.kt` (`atenderCamara`), enganchado en `Presencia.kt` |
| Preguntar cada 4 s con la cámara abierta y cada 20 s con PixPin a la vista; lo último sabido | `CamaraDelPc.kt` |
| La subida en segundo plano: servicio en primer plano `dataSync` | `SubidaDeLaCamaraService.kt` |
| La pantalla de cámara (CameraX: `ImageCapture`, `VideoCapture<Recorder>`) | `CamaraDelPcActivity.kt` |
| HEIC (u otra cosa) a JPEG copiando el EXIF | `FotoEnJpeg.kt` |
| El botón de la cámara junto al «+» de la lista de chats | `ui/ListaDeChats.kt` |

Los tipos desconocidos se siguen contestando `{"error":"No sé qué es «…»"}` sin
cortar (`Respondedor.responder`, rama `else`); una persona de un chat
compartido recibe «No sé qué es «camara_remota»» (`SOLO_DEL_GRUPO`).

## Cómo va

- **El minuto de cada toma** es el del reloj del móvil **en el disparo** (un
  clip, donde empezó) y va en `ms`; además `tomada_en` (la hora del teléfono),
  `reloj` (lo que va adelantado el teléfono, medido como NTP al preguntar) y
  `clave` (`toma-<uuid>`, fija en cada intento). Un clip lleva `duracionMs`.
- **El reloj** sigue al último anuncio: al recibirlo en `h`, «en `h` iba por
  `ms_actual` (más media vuelta si corre)»; luego corre con `elapsedRealtime`
  sin contar la pausa; con `activa:false`, congelado en `duracion`. Las
  correcciones de menos de 250 ms se dejan (como el PC).
- **Subir**: una conexión por PC y tanda, todas sus tomas pendientes en orden y
  `adios`. Lo que el PC niega para siempre (la grabación ya no está, no es una
  foto o un clip MP4, demasiado grande) queda «negada»; lo demás (un corte, la
  cámara apagada en la barra del PC, ocupado) se reintenta. Un corte a mitad
  de un clip sigue desde `desdeByte` (el PC guarda lo llegado si pasa de 1 MB).
- **Sigue subiendo** con la grabación acabada y la pantalla cerrada. Tras 15
  minutos sin poder subir nada se para; lo pendiente sale al volver a abrir
  PixPin o con la siguiente foto. Si la dirección recordada del PC no contesta,
  se busca por la red (`AlDia.aparatos`).
- **No molestar al grupo**: con la cámara cerrada solo se pregunta cada 20 s a
  quien ya dijo tener una grabación (o avisó); a los demás aparatos, una vez
  cada 5 minutos. Con la cámara abierta, a todos cada 4 s.

## Lo que se aparta de la guía del PC

- **Cola en JSON y no en Room**: el proyecto no usa Room y la cola es corta. Se
  reescribe entera a un `.tmp` y se renombra.
- **Servicio en primer plano y no WorkManager**: el proyecto no usa WorkManager
  y ya tiene el mismo patrón (`PasarATextoService`).
- **Los clips van siempre en H.264**, aunque el PC diga `hevc`: el `Recorder`
  de CameraX 1.5 no deja elegir el códec (solo usa HEVC para vídeo HDR). H.264
  lo ve cualquier PC con Windows. El botón de clip sale si `codecs` no está
  vacío (y el aparato deja vista previa, foto y vídeo a la vez).
- **Clip**: `Quality.FHD` (o lo más cercano), `setTargetVideoEncodingBitRate(5_000_000)`,
  audio AAC (sin el permiso del micrófono, sin sonido), MP4, cortado en
  `clipMaximoMs` con `setDurationLimitMillis`.
- **Foto**: `CAPTURE_MODE_MAXIMIZE_QUALITY` y `HIGHEST_AVAILABLE_STRATEGY`; CameraX
  ya da JPEG con EXIF, que viaja tal cual. `FotoEnJpeg` solo actúa si no es JPEG.
- **El aviso del PC con el teléfono ocupado** (sincronizando con otro): el
  `Respondedor` de Android contesta «ocupado» a todo, como antes; el PC no
  avisa esa vez y el teléfono se entera al preguntar.

## Cómo probarlo

- Sin el PC: `CamaraRemotaTest` (el reloj con los números de `camara.rs`, el
  JSON byte a byte, la cola, el `Respondedor` atendiendo el aviso, «No sé qué
  es» y un teléfono de antes).
- **Contra el PC de verdad**: `CamaraDelPcTest`, con el `pc-simulado`
  (`herramientas/pc-simulado`, orden `camara`: el `Respondedor` de Rust con
  `Camaras`/`Recepcion` y un reloj que mueve la prueba). Es
  `tests/movil_como_camara.rs` del PC con el teléfono de aquí: el PC avisa y
  mide 7 s de adelanto; el teléfono pregunta, hace una foto a los 12 s, otra
  en la pausa (minuto 20), un clip de 5 s en el 22 y otro de 15 s y 3 MB en el 30 que
  se corta a 2,5 MB y sigue desde `desdeByte`; el PC termina (50 s) y entran 4
  en la clase; luego una tarde (minuto 45). Se comprueba con los ojos del PC
  (`ver`): `enGrabacion`, `fuente:"movil"`, `deAparato`, `tomadaEn`, la nota
  con `capturas` y `clips`, la carpeta en su minuto y los bytes tal cual.

  ```sh
  herramientas/pc-simulado/preparar.sh <repo del PC en origin/main>
  PIXPIN_PC_SIMULADO=herramientas/pc-simulado/target/debug/pc-simulado \
    ./gradlew testDebugUnitTest --tests '*CamaraDelPcTest' --tests '*SincronizarConElPcTest'
  ```

- **Con aparatos de verdad** (PC y teléfono en la misma Wi-Fi, en el mismo
  grupo): empezar «Grabar clase» en el PC con «📱 Cámara del móvil» encendida;
  ver el botón de la cámara junto al «+» de la lista de chats; abrir la cámara, hacer fotos (una en
  pausa) y un clip; cerrar la pantalla a mitad de subida; terminar en el PC y
  mirar la clase. Y lo que esto no prueba: la orientación de las fotos, el
  sonido de los clips, el aviso del PC llegando con PixPin a la vista y la
  notificación con la pantalla apagada.
