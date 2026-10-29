# La reunión con Jev en Android (29-oct-2026)

Porte al teléfono de lo nuevo del PC del 29-oct-2026 (`pixpin_pro_windows`, `origin/main` = `50e87978`,
desde `995fd94b`): **los temas de la reunión tratados** (`docs/jev.md` §5) y **lo demás que se oye en la
reunión** (§8), con el mismo formato en la nota de voz y las mismas preguntas y tablas que el PC. Sigue la
lista «Android: los temas de la reunión en el móvil» de `docs/jev.md` (pasos 1-7).

## Qué ve el usuario

### Sin clave de TypeSafe (o con todo apagado, o sin red)

- **Preparar** («Grabar clase o reunión»): la fila **«Temas de la reunión (opcional)»**. Abre su pantalla:
  se escribe un tema e Intro, **«Pegar»** (uno por línea; se quitan viñetas, casillas `- [ ]`, números
  `1.`/`2)`, títulos `# …`, repetidos; 40 como mucho, 200 caracteres cada uno) o **«De una lista»** (lo
  pendiente de una lista de tareas; cada toque pasa a la siguiente, primero las del chat donde se guarda;
  la reunión queda enlazada a esa lista). Al lado, **«Duración prevista»** (15, 30, 45, 60, 90, 120 o
  180 min). Los temas que se pasaron «a la próxima reunión» salen ya puestos.
- **Grabando** (la barra flotante de la clase, la pastilla de la reunión presencial en la app y su
  pantalla): **«☑ 2/5»** abre la lista con una casilla por tema; **un toque marca o desmarca a mano**
  (`aMano`, con el minuto del toque). La cabecera dice por qué no marca nada solo: «Sin Jev (falta la
  clave de TypeSafe): marca los temas a mano», «Los temas con Jev están apagados en Ajustes…».
- **Al guardar**: `temas` en la nota y la lista de tareas «Temas · <nombre>» (o la elegida) con lo
  tratado marcado, enlazada en `temasLista`.
- **Estudiar la clase**: encima del texto, la agenda con ✓ y su minuto (un toque en el tema salta ahí; la
  casilla lo cambia a mano, también en su lista), los minutos de cada tema («12:30 · 6 min»), lo que quedó
  **sin tratar** en ámbar con «Pasar a la próxima reunión» y «Añadir a Tareas»; «Decisiones · N»,
  «Preguntas sin respuesta · N» (con «Añadir a Tareas») y «Propuestas sin aceptar · N» (con «Aceptar»).
  **Todo esto se ve igual con lo que llega del PC, sin clave ni red.**
- **La página compartida** (HTML y Markdown): la agenda con su estado (✓ y minuto que salta, o «Sin
  tratar»), los minutos de cada tema, las decisiones y las preguntas sin respuesta con su minuto.

### Con clave (Ajustes › Asistente › TypeSafe (Jev))

Además, mientras se graba:

- **Los temas se marcan solos**: lo que se oye se pasa a texto **en el teléfono**, frase a frase, y Jev
  marca lo tratado con ✓ y su minuto («Jev · 12:30», la fila se ilumina en verde) y el aviso «✓ Tema 2
  tratado · 12:30». Lo tocado a mano manda: Jev ya no lo cambia.
- **Las fichas** (las tres más nuevas, debajo de la barra): «＋ Tarea: mandar la cotización · Luis ·
  30/10» y «📅 Evento: nos vemos el martes a las 3 · 3/11 15:00» con ✓ (al Inbox de Tareas, con el mismo
  texto que el PC) y ✕ (descartada); «↑ Pagar al proveedor: Normal → Urgente · ahora para el 29/10» (ya
  aplicado, con «Deshacer»); «⏳ Quedan 4 min y falta: Paro» (tema olvidado). «Decisión apuntada · 12:30».
- Si Jev falla: «Jev no contesta: marca los temas a mano» / «Jev no contesta: por ahora no se propone
  nada». Un fallo nunca es un «no».

## Cómo escucha (`guardados/EscuchaDeLaReunion.kt`)

1. **El oído**: la toma del PCM de la misma nota (`Grabador.oir`, en el hilo del micrófono: copia el
   bloque en una cola de 256 que tira lo que no cabe y vuelve). No se abre otro micrófono.
2. **El texto**, frase a frase, en el teléfono:
   - **El reconocedor del sistema sin red** (`createOnDeviceSpeechRecognizer`, `EXTRA_PREFER_OFFLINE`,
     `EXTRA_PARTIAL_RESULTS`, el idioma instalado que más se parece al de Ajustes). **Android 13+**: se le
     da el sonido de la grabación (a 16 kHz) por una tubería (`EXTRA_AUDIO_SOURCE`) en una sesión por
     tramos (`EXTRA_SEGMENTED_SESSION`): cada `onSegmentResults` es una frase; al acabar la sesión, otra.
     **Android 12**: abre su micrófono y se vuelve a lanzar en cada `onResults`, como `MotorGoogle.kt`.
     Los parciales solo se enseñan (en cursiva, en la lista).
   - **Whisper** (el modelo ya bajado: nunca se baja en una reunión; un reconocedor ligero aparte con dos
     hilos) sobre trozos de 5 s o más cortados en un silencio (`OidoEnVivo.PARA_WHISPER`, el `Frasero` del
     PC), en una **cola de tres que tira la más vieja**: si no hay reconocedor del sistema, si falla tres
     veces seguidas, o **si Android silencia la grabación por él** (`AudioRecordingCallback`,
     `isClientSilenced` de nuestra sesión: la grabación gana y el reconocedor se va).
   - **La grabación va primero** (`OidoEnVivo.Freno`): con la batería por debajo del 20 % (sin cargar) o el
     teléfono caliente (`THERMAL_STATUS_MODERATE` o más) se saltan frases; si una frase de Whisper tardó
     más del 80 % de lo que dura, se salta la siguiente. Lo que no tiene voz no se mira.
3. **Jev, los temas** (`jev/Temas.kt`, `temas.rs`): las frases al rollo de 40 s; con tres palabras nuevas
   y algún tema pendiente, una petición `{reunion, agenda, conversacion: {antes, ahora}}` con tres Nouls
   por tema (hasta 20); como mucho una en vuelo, 1,5 s entre una y otra, 5 s tras un fallo; la tabla
   `Seguimiento.decidir` con lo recordado que caduca a los 60 s. Cliente con 4 s de tiempo; OkHttp reusa
   la conexión.
4. **Jev, lo demás** (`jev/EnLaReunion.kt`, `en_la_reunion.rs`): lo que llega, partido en oraciones
   (`Vivo.partir`), **una petición por oración** a la mesa con las partes de las ideas encendidas; lo que
   llega mientras tanto va junto. Cliente con 3 s. Sin saber quién habla, la frase va como de «Voz» y el
   responsable queda «¿quién?» salvo que se nombre.

**Cuando el teléfono es la cámara de una grabación del PC** (`CamaraDelPc.haciendoDeCamara()`: la pantalla
de cámara abierta y el PC grabando): escucha el PC. El teléfono no pasa a texto ni pregunta («Este
teléfono es la cámara del PC: escucha el PC»).

## Compatibilidad con el PC, campo a campo

En la nota de voz (los nombres y formatos de `docs/jev.md` §5 y §8; **cada clave solo si tiene algo**; se
conservan tal cual al reescribir el mensaje: son campos de `Mensaje`):

| campo | formato | lo lee | lo escribe |
|---|---|---|---|
| `temas` | `[{id, texto, hecho, ms?, p?, porJev, aMano}]`, en el orden de la agenda; `id` `t1`, `t2`… (no se reutiliza); `p` con dos decimales | Estudiar, página, Preparar | al guardar; al cambiar a mano al estudiar (entero, con `aMano: true`) |
| `temasLista` | `{proyecto, id}` (`proyecto` como viaja: `general` es «Mensajes guardados») | Estudiar (la casilla de su lista) | al guardar |
| `decisiones` | `[{ms, texto}]` | Estudiar, página | al guardar |
| `preguntasAbiertas` | `[{ms, texto}]` (las que seguían abiertas al terminar) | Estudiar, página | al guardar |
| `tiempoPorTema` | `{temas: [{id, ms}], otro}` | Estudiar, página | al guardar |
| `propuestas` | `[{id, tipo: tarea/evento, ms, texto, responsable?, fecha? AAAA-MM-DD, hora? HH:MM, prioridad? urgente/alta/normal/baja, estado: pendiente/aceptada/descartada}]` | Estudiar («Aceptar») | al guardar; «Aceptar» la pone `aceptada` |
| `cambiosDeUrgencia` | `[{ms, tarea, antes, despues, fechaAntes?, fechaDespues?, deshecho}]` | — (se conserva) | al guardar |

- Lo que no se entiende se salta (como `serde`): un elemento raro no tira los demás.
- **Lo hecho a mano manda**: ningún aparato deja que Jev cambie un tema con `aMano: true`; cada aparato
  escribe `temas` entero (gana la versión más nueva del mensaje, como siempre).
- **Aceptar** apunta en el Inbox el mismo texto que `jev::en_vivo::texto_de` del PC («mandar la
  cotización 🔼 💬 Luis · 🎙 Coordinación ⏰ 2026-10-30 09:00»).
- La lista de tareas de los temas es una lista normal (`- [ ] texto`): viaja con su chat; lo marcado en
  ella durante la reunión entra al guardar como hecho a mano.
- Lo que **no** viaja: la clave, los interruptores (`jev` de este teléfono), las prioridades calculadas en
  memoria (la fecha nueva de un cambio de urgencia sí: va en el `⏰` del texto) y los temas que esperan a
  la próxima reunión (`files/voz/temas-para-la-proxima.txt`, de este teléfono, como el PC).
- `jev/Preguntas.kt` es **copia entera** de `crates/pixpin-typesafe/src/preguntas.rs` (en el mismo
  orden): las 271 cadenas comprobadas por guion contra el PC.

## Ajustes

Ajustes › Asistente › TypeSafe (Jev): **Temas de la reunión tratados** y los siete de la reunión (tareas
dichas, urgencia en vivo, tema olvidado, minutos de cada tema, decisiones, preguntas sin respuesta, fechas
al horario), encendidos de fábrica; sin clave no hacen nada.

## Ficheros

- `jev/`: `Preguntas.kt` (copia del PC), `Temas.kt` (Rollo, Ritmo, Seguimiento), `Vivo.kt` (`vivo.rs`; la clave de las opciones y los candidatos del dictado son los de `DictadoAlChat.kt`, una sola
  implementación), `FechasDichas.kt` (`fechas.rs`), `IdeasDeLaReunion.kt` (compromisos, citas, pendientes,
  tiempo de temas, urgencia viva), `EnLaReunion.kt` (la mesa).
- `guardados/`: `TemasDeLaReunion.kt` (`temas.rs` de `pixpin-proyecto`), `ActaViva.kt`, `OidoEnVivo.kt`
  (`en_vivo.rs` de `pixpin-voz`), `EscuchaDeLaReunion.kt`, `ReunionConJev.kt`, `PrepararTemas.kt`,
  `TemasEnLaBarra.kt`, `AgendaDeLaReunion.kt`, `TextosDeLaReunion.kt`.
- Tocados: `Mensajes.kt` (los campos), `audio/Grabador.kt` (la toma del PCM), `GrabarClaseActivity`,
  `GrabarClaseService`, `ReunionPresencialService`, `ReunionEnLaApp`, `ReunionEnCursoActivity`,
  `EstudiarClaseActivity` (una línea: la agenda), `ClaseExportar` y `resources/clase_exportar/pagina.css`
  (el del PC), `MotorWhisper` (el ligero), `jev/Jev.kt` y `VistasDeJev.kt` (los interruptores),
  `jev/UrgenciaEnLaApp.kt` (lo sabido de una tarea), `sincro/CamaraDelPc.kt`.

## Pruebas (JVM, sin red)

`TemasEnVivoTest`, `VivoYFechasTest`, `MesaDeLaReunionTest` (las de `temas.rs`, `vivo.rs`, `fechas.rs`, `compromisos.rs`, `citas.rs`, `pendientes.rs`, `tiempo_de_temas.rs`, `urgencia_viva.rs` y
`en_la_reunion.rs`, con respuestas grabadas), `TemasYActaDeLaReunionTest` (`temas.rs` y `acta_viva.rs` de
`pixpin-proyecto`, y la ida y vuelta de una nota escrita por el PC), `OidoEnVivoTest` (`en_vivo.rs`),
`ClaseExportarReunionTest` (la página) y `ReunionConJevTest` (`texto_de`).

## Lo que necesita un teléfono o una clave

- El reconocedor del sistema con `EXTRA_AUDIO_SOURCE` y `EXTRA_SEGMENTED_SESSION` en vivo (Android 13+),
  y si en Android 12 el reconocedor con su micrófono hace que Android silencie la grabación (entonces se
  pasa a Whisper): **no probado en un teléfono** el 29-oct-2026.
- Lo que tarda el ✓ de verdad (se apunta en el registro, etiqueta `PixPinReunion`, con los ms desde que
  se dejó de hablar) y el gasto de batería de una hora de escucha.
- Las preguntas contra la API de verdad (como en el PC: sin clave el 29-oct-2026).
- Pendiente: los temas en el diario de «Grabar sin perder nada» (`temas` y `temas_lista` del PC): una
  reunión recuperada tras un corte entra sin sus temas.
