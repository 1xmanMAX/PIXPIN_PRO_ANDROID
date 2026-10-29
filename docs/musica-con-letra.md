# Música con su letra (Android, 27-oct-2026) — guía para el PC

Lo pidió el usuario: «Quiero guardar música, con su letra adjunta, para poder cantar cuando quiera.»
Y: «Que la música, los audios, todo se pueda reproducir por el altavoz del oído, el que se usa para
llamadas, además del altavoz normal y los audífonos.»

Este documento dice **cómo se guarda** una canción con su letra para que el PC la lea igual, qué ya
funciona en Windows sin tocar nada y qué podría añadir el PC si quiere el mismo karaoke.

## 1. Formato: nada nuevo en el JSONL

Una canción es **un mensaje de voz normal** del chat, con dos campos que ya existían:

| Campo | Valor | Desde |
|---|---|---|
| `clase` | `"VOZ"` | siempre (todo audio que entra en el chat) |
| `ruta`, `nombre`, `bytes`, `duracionMs` | el audio (`.mp3`, `.m4a`, `.ogg`, `.flac`, `.wav`…) | siempre |
| `estadoDelTexto` | `"letra"` → **es música** | 6-sep-2026 (`TEXTO_LETRA`) |
| `transcripcion` | **la letra** | 6-sep-2026 |

Ejemplo (una línea del JSONL, partida aquí para leerla):

```json
{"id":"…","cuando":1793000000000,"clase":"VOZ","ruta":".../Besame mucho.mp3",
 "nombre":"Besame mucho.mp3","bytes":4123456,"duracionMs":187000,
 "estadoDelTexto":"letra",
 "transcripcion":"[0:12] Bésame, bésame mucho\n\n[0:18] como si fuera esta noche la última vez\n\n[0:25]\n\n[0:31] Bésame…",
 "numero":48,"uid":"…","aparato":"K7Q2"}
```

### La letra dentro de `transcripcion`

- **Sin tiempos** (texto llano): tal cual, un renglón por línea, con sus renglones en blanco entre
  estrofas.
- **Con tiempos** (para el karaoke): **el mismo formato que una transcripción con tiempos**
  (`Transcriptor.conTiempos` en Android, `pixpin_voz::con_tiempos` en el PC):
  - una línea de la canción por párrafo: `[m:ss] lo que se canta` (o `[h:mm:ss]` pasada la hora);
  - un **renglón en blanco** entre líneas;
  - una marca **sin texto** (`[0:25]`) es un silencio del LRC: la línea anterior deja de resaltarse;
  - un párrafo sin marca es texto suelto (una nota, «[Coro]»).
- Los tiempos se **redondean al segundo** más cercano (el LRC trae centésimas). Para seguir una
  canción cantando sobra, y es lo que hace que el PC lo lea sin cambios.

### Por qué así y no de otra forma

- **No un campo `letra` nuevo**: `letra` ya es la letra del aparato (`Mensaje.letra`, el `47a` de
  antes de los códigos). Y un campo nuevo obligaría al PC a aprenderlo para enseñar nada.
- **No el `.lrc` tal cual en `transcripcion`**: `[00:12.34]` no es una marca para
  `pixpin_voz::tiempo_de` (`dos_cifras` exige exactamente dos cifras en los segundos) ni para
  `Transcriptor.tiempoDe` de Android; y un LRC no lleva renglones en blanco, así que
  `voz.rs::trozos` lo leería **todo como un único bloque** con los corchetes a la vista.
- **No un `.lrc` al lado del audio**: sería un fichero más que sincronizar y que se puede quedar
  desfasado del mensaje (se edita la letra en un aparato y el `.lrc` del otro sigue viejo). En el
  mensaje viaja con todo lo demás: sincronización, envíos por Wi-Fi y `.pixpin`.

## 2. Lo que el PC ya hace con esto (sin tocar código)

Comprobado contra `origin/main` del PC (`ae96b89e`):

- `crates/pixpin-proyecto/src/cuaderno.rs` — `Mensaje` tiene `transcripcion` declarado y todo lo
  demás va a `resto` (`#[serde(flatten)]`): **`estadoDelTexto` se conserva** al reescribir.
- `apps/pixpin/src/biblioteca_audio.rs::es_musica` — `clase == Voz` y `estadoDelTexto == "letra"`:
  la canción sale en el grupo **Música** de la biblioteca de audio.
- `apps/pixpin/src/voz.rs::trozos` + `pixpin_voz::tiempo_de` — parten la letra por renglones en
  blanco y leen `[m:ss]`: la pantalla de la letra del PC **resalta la línea que suena** y **salta**
  a ella al pulsarla (`por_donde_va`), igual que con una transcripción.
- `voz.rs::escribir_letra` (el lápiz del PC) — escribe `transcripcion` y pone `estadoDelTexto =
  "letra"` si no lo tenía: lo que se edite en Windows vuelve al teléfono como letra.

Una letra con tiempos escrita a mano en el PC con el formato de arriba se canta en el teléfono
con la línea resaltada; un LRC pegado en el lápiz del PC se verá como texto llano (con los
corchetes) hasta que se abra y se guarde en el teléfono, que lo convierte.

## 3. Lo que podría añadir el PC (opcional)

1. **Pegar LRC en el lápiz de la letra**: convertirlo al formato de arriba antes de
   `escribir_letra`. Reglas (las de `LetraDeCancion.leer` en Android, con sus pruebas en
   `LetraDeCancionTest`):
   - marcas `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]`, `[h:mm:ss]`, varias seguidas en
     una línea (el estribillo sale una vez por marca);
   - etiquetas `[ti:]`, `[ar:]` (título y artista), `[offset:±ms]` (se resta a cada tiempo),
     el resto se ignora;
   - las marcas por palabra `<mm:ss.xx>` del LRC mejorado se quitan;
   - se ordena por tiempo (estable) y se escribe `[m:ss] texto` redondeando al segundo, con
     `\n\n` entre líneas.
2. **Tarjeta de música** en la conversación: portada embebida (ID3 `APIC`, `covr` de m4a,
   `METADATA_BLOCK_PICTURE` de flac/ogg), título y artista de las etiquetas, en vez de la onda.
3. **Karaoke**: la línea que suena grande y centrada, las cantadas apagadas.
4. **Sincronizar a toques**: con la canción sonando, un clic (o la barra espaciadora) al empezar
   cada línea pone su tiempo; «deshacer» quita la última y vuelve dos segundos antes de la
   anterior; un toque no puede quedar antes que el anterior.

## 4. Android: dónde está cada cosa

| Qué | Dónde |
|---|---|
| Adjuntar → **Música** (elegir audio + letra, «Pegar la letra copiada») | `guardados/CancionNuevaActivity.kt` |
| Tarjeta de música en el chat (portada, título, artista) | `guardados/TarjetaDeMusica.kt` |
| Cantarla: **«Estudiar la clase»**, que con una canción es el karaoke (29-oct-2026: sin la tira de arriba, portada y título de las etiquetas, A−, A+, reloj, «Sincronizar sola», lápiz, ⋮ y ✕; la letra entera que sigue a la canción) | `guardados/EstudiarClaseActivity.kt` (`Karaoke`), `guardados/KaraokeDeLaCancion.kt` |
| Seguir a la canción o no (6 s tras moverla a mano, puro) | `guardados/SeguirLaLetra.kt` |
| **«Sincronizar sola»** (oír, alinear y guardar; pararla; el aviso de cuántas líneas se oyeron) | `guardados/SincronizarSola.kt` |
| Oír una canción (tandas de 8 s, idioma de la letra, sin «[Música]») | `guardados/OirCancion.kt`, `MotorWhisper.reconocer(cancion = true)` |
| El editor de la letra y el sincronizador a toques | `guardados/LetraEnPantalla.kt` |
| `MusicaActivity` (el karaoke del 27-oct): ahora solo un desvío a la pantalla de estudiar | `guardados/MusicaActivity.kt` |
| Leer LRC / guardar / línea actual / sincronizador (puro) | `guardados/LetraDeCancion.kt` |
| **Poner los tiempos con lo que se oye** (alineación de la letra pegada con la transcripción, puro) | `guardados/AlinearLetra.kt` |
| La cola que oye la canción en segundo plano | `guardados/ColaDeTexto.kt`, `guardados/PasarATexto.kt` |
| Biblioteca de audio: la música abre la pantalla de estudiar | `guardados/BibliotecaDeAudioActivity.kt` |

### Los tiempos, con lo que se oye (28-oct-2026)

El usuario: «para la música, que también la transcriba, pero con un sistema inteligente: yo pego la
letra (el texto bueno); la transcripción da los tiempos; que compare las dos y las fusione en una letra
con tiempos».

- Al guardar una canción sin tiempos (Adjuntar → Música) entra en la cola de pasar a texto: se oye
  entera con el motor de voz elegido (Whisper por defecto) y **se alinea lo oído con la letra pegada**.
  En «Estudiar la clase», ⋮ → «Poner los tiempos a la letra» hace lo mismo a mano y enseña la propuesta
  para aceptarla, seguir editándola o dejarla; «Sincronizar la letra» a toques sigue ahí para afinar.
- **La alineación** (`AlinearLetra`): desde el 29-oct-2026 es **el mismo cálculo que el PC**
  (`pixpin_voz::alinear_letra`, ver más abajo «Sincronizar sola»); el del 28-oct (+3/+2/+1/−2 con
  fonética) se retiró para que los dos aparatos den los mismos minutos. Determinista y sin red.
- **El texto de la letra no cambia ni una letra**: solo se le ponen los minutos. Lo que se guarda es el
  formato de siempre (`[m:ss] línea`, un renglón en blanco entre líneas, `estadoDelTexto: "letra"`), así
  que **el PC no tiene que cambiar nada**: lo lee como cualquier letra con tiempos.
- Sin letra pegada, lo oído se guarda como letra con tiempos (frases de unos 6 s).
- Una letra que el usuario cambió mientras se oía la canción no se pisa: los tiempos solo se guardan si
  la letra sigue igual que cuando se empezó.

### «Sincronizar sola» y el karaoke sin tira (29-oct-2026, PC 058ae2b7, 1940db65, 147492df)

Lo mismo que el PC (`docs/ui-funciones-y-chats.md` del PC, «Sincronizar sola» y «Cómo se alinea la
letra con lo oído»), adaptado al teléfono:

- **El karaoke**: con una canción, «Estudiar la clase» ya no lleva la tira de arriba (la flecha y los
  botones de cristal) ni el cartel. Arriba va la portada con **el título y el artista de las
  etiquetas** (sin etiquetas, nada: nunca el nombre del fichero) y **A−, A+, el reloj** (sincronizar a
  toques), **los subtítulos** («Sincronizar sola»), **el lápiz**, **⋮** (poner los tiempos con la
  propuesta para revisar, copiar como LRC y claro/oscuro) y una **✕** para volver. Sin botón de copiar
  a la vista (el PC lo quitó; aquí sigue en ⋮). Abajo, la barra del tiempo y los mandos (tumbado, en
  columna al lado). Sin «Seguir» ni el botón de esconder el texto: la letra se ve siempre y sigue sola.
- **Moverse por la letra**: arrastrar (o la rueda de un ratón), con tiempos y sin ellos. La línea que
  suena va grande y en color a un tercio de la altura, las cantadas al 40 %, las que vienen al 75 %.
  Mientras suena, la vista sigue a la línea que suena; **moverla a mano lo para 6 s** (o hasta tocar
  una línea) y luego vuelve sola; parada, se queda donde se dejó (`SeguirLaLetra`, la misma regla
  que `sigue_a_la_cancion` del PC). *El fallo que había*: la lista volvía a la línea que suena en
  cuanto cambiaba de línea, aunque se acabara de mover a mano; ahora mover a mano lo pausa y nada lo
  pisa mientras dura (los saltos de seguir no pasan por el desplazamiento anidado; los gestos, sí).
- **Sincronizar sola** (los subtítulos): Whisper oye la canción en su hilo y la letra pegada se alinea
  con lo oído; se guarda en el mismo `[m:ss] línea`. **El texto guardado es siempre el pegado.**
  Arranca sola al guardar (lápiz) una letra sin tiempos **si el reconocedor ya está bajado**; si no, el
  aviso dice que su botón lo baja (una vez, unos 160 MB, el modelo de Ajustes). La pastilla bajo la
  cabecera dice «Sincronizando la letra… 40 %» (o «Bajando el reconocedor de voz… 40 %»). **Pulsar otra
  vez el botón la para, y cerrar la pantalla también** (girar el teléfono no). Al acabar, el aviso dice
  cuántas líneas se oyeron («12 de 30») y, con menos de un tercio, que **va a ojo**. **Si la letra se
  editó mientras, no se guarda.** Una a la vez. La cola (Adjuntar → Música) y ⋮ «Poner los tiempos a la
  letra» usan el mismo oído y el mismo alineador.
- **Cómo se oye una canción** (`OirCancion`): con Whisper, en tandas de frases de **hasta 8 s** (no 15
  como una nota), en **el idioma de la letra** si se nota (`AlinearLetra.idiomaDeLaLetra`: una canción
  en inglés se oye en inglés con PixPin en castellano; si no está claro, el de Ajustes con su segundo
  idioma) y **sin anotaciones** («[Música]», «♪»). *Diferencia con el PC*: el PC le prohíbe a Whisper
  esos tokens al decodificar (`Fichas::sin_habla`) y entonces a veces escribe la letra en su lugar;
  sherpa-onnx no deja tocar el decodificador, así que aquí se quitan del texto (`sinAnotaciones`) y esa
  tanda se queda sin palabras. En un teléfono sin Whisper (32 bits), «Sincronizar sola» lo dice; la cola
  usa el motor de Ajustes en el idioma de la letra.
- **Cómo se alinea** (`AlinearLetra`, puerto tal cual de `alinear_letra.rs`, con sus constantes y
  **todas sus pruebas** en `AlinearLetraTest`): normalizar (minúsculas, sin tildes ni puntuación, el
  apóstrofo junta, los «x2» fuera, un rótulo `[Coro]` no se canta); Needleman–Wunsch palabra a palabra
  (pareja parecida `1 + parecido` desde 0,65 de Levenshtein, cortas de 1-2 letras solo exactas y valen
  0,5; distinta −0,9; suelta de la letra −0,6; suelta oída −0,4; lo oído antes y después, gratis);
  anclas (confianza ≥ 0,4 y dos casadas, o una si la línea tiene una), corridas atrás al ritmo, nunca
  antes que la anterior; **el estribillo en su vez** (una línea repetida que casó con la vez que no toca
  pasa el ancla a su gemela si cae al menos 3 s y la mitad más cerca); el ritmo (mediana, 70 ms/letra
  si no hay, 35-300); y **las líneas sin ancla repartidas sobre lo cantado** (los tramos con palabras
  oídas): un solo de guitarra no estira la estrofa de antes, una estrofa mal oída se reparte por donde se
  cantó, lo que Whisper se invente en la intro no adelanta la primera línea, y detrás de la última hasta
  lo último que se oye sin pasar del final. Los rótulos van con la línea que los sigue.
- **Paridad con el PC**: con lo mismo oído, los mismos minutos (las 18 pruebas de `alinear_letra.rs` pasan aquí con
  los mismos números). Lo que puede diferir es lo oído: el PC prohíbe los tokens de anotación y aquí se
  quitan después (ver arriba), y Whisper de sherpa-onnx y el del PC no son el mismo binario. Android
  guarda además dos cosas propias: sin letra pegada, lo oído como letra (frases de unos 6 s), y la
  propuesta de ⋮ para revisar antes de guardar.

En el chat, un audio es **tarjeta de música** cuando `esMusica` (lo anterior: lo marca solo
`MensajesStore.anadir` si el archivo trae etiquetas de canción, o al entrar por Adjuntar → Música, o
al guardarle una letra en el karaoke).

La «Música» como sección propia de la ficha del chat **no se ha añadido**: las secciones
(`Seccion`) son compartidas con el PC y la cabecera la está tocando otro cambio; la biblioteca de
audio ya separa Música de Notas de voz en los dos.

## 5. Altavoz de llamadas (solo Android)

Todo lo que suena en la app puede ir por el **auricular de llamadas** además del altavoz y los
audífonos: `audio/SalidaDeAudio.kt` (la regla pura en `audio/DecisionDeSalida.kt`). Es una
preferencia **del aparato**, no viaja: el PC no tiene auricular de llamadas. Lo más parecido en
Windows es `crates/pixpin-audio/src/salida.rs::Rol::Comunicaciones` (el aparato de
comunicaciones, `eCommunications`, que ya usa la llamada secreta): si el PC quiere el mismo botón,
es abrir el audio con ese rol. No cambia el formato de nada.

Reglas (las mismas en cualquier reproductor de la app): **los audífonos mandan**; si no, lo
elegido con el botón (altavoz ↔ auricular); y con algo sonando en el reproductor de la app,
**acercarse el teléfono a la oreja** lo pasa al auricular y apaga la pantalla (al apartarlo vuelve
al altavoz en pausa, como Telegram). El modo de llamada solo dura mientras suena.
