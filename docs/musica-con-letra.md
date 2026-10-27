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
| Karaoke, editor de letra y «Sincronizar la letra» | `guardados/MusicaActivity.kt` |
| Leer LRC / guardar / línea actual / sincronizador (puro) | `guardados/LetraDeCancion.kt` |
| Biblioteca de audio: la música abre el karaoke | `guardados/BibliotecaDeAudioActivity.kt` |

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
