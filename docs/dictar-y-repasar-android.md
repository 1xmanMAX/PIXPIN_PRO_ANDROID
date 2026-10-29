# Dictar al chat y repasar en voz alta en Android (29-oct-2026)

Lo de §6 «Dictar al chat» y §7 «Repasar en voz alta» de `docs/jev.md` del PC, y sus listas
«### Android: dictar al chat» y «### Android: repasar en voz alta», hecho en el teléfono con **las
mismas preguntas y las mismas tablas** (PC `origin/main` `50e87978`; commits `15811c76` el chat,
`5282d2f5` el estudio, `7371be90` los interruptores y textos, `20d3f70b` la documentación, `3dff9259` y
`d782e4c1` la lógica de `pixpin-typesafe`). Lo general de Jev en el teléfono está en
[jev-android.md](jev-android.md).

## Lo que ve el usuario

### Sin clave de TypeSafe (o con el interruptor apagado)

**Nada cambia.** En la caja del chat no sale el botón 🗣 y en «Estudiar la clase» el menú ⋮ no trae
«Repasar en voz alta». Nada se manda a ningún sitio. (Si se llegara a la pantalla del repaso de otra
forma, dice que hace falta la clave y el interruptor, y no escucha.)

### Con clave (Ajustes › Asistente › «TypeSafe (Jev)»)

Dos interruptores nuevos, encendidos de fábrica, con lo que manda cada uno:

- **Dictar al chat**: «Al dictar en la caja del chat (el botón 🗣 de la caja), «anota en el chat de la
  obra que faltan tornillos» propone anotarlo en ese chat: tocar la propuesta lo anota, la ✕ la quita.
  Se manda cada frase dictada, la anterior y los nombres de tus chats recientes.»
- **Repasar en voz alta**: «En «Estudiar la clase», explica la clase de viva voz y Jev marca qué puntos
  clave cubres, cuáles dices mal y cuáles faltan. Se manda lo que explicas, el título y los puntos
  clave.»

#### Dictar al chat

1. En la caja de escribir de cualquier chat sale **🗣** (junto al clip). Tocarlo dicta en la caja con
   el reconocedor del teléfono, frase a frase (lo que se va oyendo sale encima, «🗣 …»); tocarlo otra
   vez para. Cada frase cerrada se pega detrás de lo escrito (mayúscula si empieza frase), como el
   dictado del PC.
2. Por cada frase de tres palabras o más, **una petición** a Jev (como mucho una en vuelo; lo que se
   dicta mientras tanto espera, las tres últimas; 4 s por petición).
3. Si Jev dice que se pide anotar algo, encima de la caja sale **la chapa**:
   - «**Anotar en «Obra Calle Lima»: faltan tornillos**» → tocarla lo anota en ese chat (una nota
     normal, como escrita allí; el chat sube en la lista), quita de la caja lo dictado para pedirlo y
     avisa «Anotado en «Obra Calle Lima»».
   - Sin chat dicho («anótalo, que la grúa se va el viernes»): «**¿En qué chat anoto «…»? Elegir chat**»
     → tocarla abre **la lista de chats** (los 30 más recientes, sin el abierto ni el del asistente).
   - La **✕** la quita.
4. **Nunca se anota solo.** Si Jev duda (entre 0,3 y 0,7) o dice que no, no sale nada.
5. **Sin red o si Jev falla**: lo dictado se queda en la caja y encima sale, unos segundos, «Sin
   conexión: Jev no pudo mirar si había que anotarlo en otro chat. Lo dictado sigue en la caja.» (o el
   motivo). Si el reconocedor del teléfono necesita red y no la hay, se dice «No se pudo dictar: sin
   conexión: el reconocedor de voz de este teléfono necesita red» y el botón se apaga.

#### Repasar en voz alta

1. En «Estudiar la clase», menú **⋮ › «Repasar en voz alta»** (solo con clave y el interruptor; no en
   las canciones). Abre su propia pantalla.
2. Arriba «Repasar en voz alta · <clase>» y la explicación del PC; debajo, **los puntos clave**, cada
   uno con su marca: **✓** cubierto (verde), **✗** dicho mal (rojo), **·** falta (gris), **?** no queda
   claro (dorado). Sin puntos, dice de dónde salen.
3. «**Empezar a hablar**» abre el micrófono (el reconocedor del teléfono, frase a frase) y se explica
   la clase de viva voz; «Parar» lo cierra. Abajo, el recuento («3 de 8 cubiertos · 1 mal · 4 faltan ·
   0 dudosos») y lo último dicho (o lo que se va oyendo).
4. Lo dicho mal y lo que falta llevan **«▶ 12:34»**: un toque pone el audio de la clase en ese minuto
   y vuelve a «Estudiar la clase».
5. «**Empezar otra vez**» lo pone todo a cero (lo que estuviera en vuelo se tira). «Cerrar» sale.
6. **Si Jev falla**: «Jev no contestó: los puntos se quedan como estaban.» (con «Sin conexión.» o el
   motivo). Un fallo nunca es un «falta».
7. **Nada se guarda**: es un ejercicio.

## Qué se pregunta

### Dictar al chat

- Estado (`vivo::estado` con solo la parte `chats`): `{"antes": ["Yo: …", "Yo: …"], "frase": {"quien":
  "Yo", "dice": "…"}}`. `antes` son las dos frases dictadas antes o, si es la primera, lo que ya había
  escrito en la caja; si no hay, no va. Ni `reunion`, ni `participantes`, ni `agenda`.
- Preguntas (`preguntas::vivo`, copiadas tal cual): `dictado` (Noul), `dictado_chat_dicho` (Noul),
  `dictado_chat` (Choice entre los nombres de los chats, con la clave de cada opción de `vivo::clave`:
  minúsculas, sin tildes, `_`; repetidas con `_2`…) y, si hay dos textos, `dictado_que` (Choice entre
  `lo_que_sigue_en_la_frase` y `la_frase_anterior`).
- **El texto no se genera**: `dictado::textos` saca lo que sigue a «:» o al primer « que » tras «chat»
  o «grupo» (dos palabras o más) y la frase anterior (para «anota esto»); Jev elige cuál.
- **La tabla** (`dictado::decidir`): `dictado` ≥ 0,7 y `dictado_chat_dicho` ≥ 0,7 → nota en el chat
  elegido; `dictado` ≥ 0,7 y chat no dicho → se pregunta en qué chat; ≥ 0,7 sin texto → nada; entre 0,3
  y 0,7 → nada (dudoso); ≤ 0,3 → nada.
- Código: `jev/DictadoAlChat.kt` (lo de la biblioteca: `textos`, `clave`, `estado`, `preparar`, `leer`,
  `decidir`) y `guardados/DictarAlChat.kt` (lo de la app: `quitarDicho`, `juntar`, `chatsPara`,
  `propuestaDe`, la cola, la chapa y la lista).

### Repasar en voz alta

- Los puntos (`puntos_clave` del PC, `guardados/RepasarClase.kt`): el renglón de cada `importantes` de
  tipo **examen** (primero) o **decisión**, los **marcadores con nota** (después del examen) y los
  **temas tratados** (`temas` de la nota, `hecho: true`); sin repetir (sin distinguir mayúsculas), doce
  como mucho, en el orden de la clase. Los temas se leen del renglón de la nota en `guardados.jsonl`, así
  que valen tanto si llegan del PC como si los apunta el móvil.
- Estado: `{"clase": "<título>", "explicacion": "<lo explicado, lo último 4 000 letras>"}`.
- Preguntas (`preguntas::repaso`): por cada punto aún no «mal», `cubre_<id>` (`explica`) y
  `contradice_<id>` (`lo_contradice`), con el punto dentro; los `id` son `p1`, `p2`…
- La tabla de una petición (`leer_punto`): contradice ≥ 0,7 → mal; si no, cubre ≥ 0,7 → cubierto; ≤ 0,3
  → falta; en medio, dudoso. Entre peticiones (`Repaso::decidir`): lo mal se queda, lo cubierto solo
  pasa a mal, lo dudoso sube a cubierto o mal, lo que falta puede pasar a cualquiera.
- Código: `jev/RepasoOral.kt` y `guardados/RepasarActivity.kt`. 6 s por petición, una en vuelo; lo
  dicho mientras tanto va en la siguiente.

## Privacidad y lo que viaja

- Solo con clave y el interruptor, solo a `api.typesafe.ai` por HTTPS. **Dictar**: cada frase dictada,
  la anterior (o lo escrito) y los nombres de los chats recientes. **Repasar**: el título de la clase,
  los puntos clave y lo explicado (pasado a texto en el teléfono). Nunca el audio.
- **Nada nuevo viaja**: lo anotado es un mensaje normal del chat elegido; la propuesta sin aceptar y el
  repaso no salen del teléfono ni se guardan. Los interruptores son de este aparato.
- El reconocedor: se prefiere el del aparato (`createOnDeviceSpeechRecognizer`,
  `EXTRA_PREFER_OFFLINE`); el manifiesto declara la consulta de `android.speech.RecognitionService`
  para que Android 11+ lo vea.

## Las preguntas, copia entera

`jev/Preguntas.kt` es ahora **la copia entera y en su orden** de `crates/pixpin-typesafe/src/preguntas.rs`
(`reunion`, `temas`, `anki`, `urgencia`, `modelo`, `vivo`, `repaso`, `captura_a_chat`, `etiquetas`,
`mejor_foto`, `pizarra`, `ocr`, `alertas`, `horario_de_chat`). Un guion compara cada cadena, en orden,
con los `{x}` del PC y los `$x` de Kotlin como lo mismo: **271 cadenas, iguales**. El fichero es byte a byte el mismo
que el de la rama de la reunión (`reunion`), que hizo la misma copia a la vez.

## Pruebas

- `jev/DictadoYRepasoTest`: `vivo::clave`, `dictado::textos` y su tabla, el estado y las preguntas que
  van, el dictado y el repaso con respuestas grabadas (y los fallos como errores), la tabla de un punto
  y la de entre peticiones, lo explicado cortado por delante y doce puntos como mucho, las preguntas de
  los temas.
- `guardados/DictarYRepasarTest`: quitar lo dictado de la caja y pegarlo, los chats que se ofrecen, la
  propuesta con respuestas grabadas, «anota esto» y sin chat, los puntos clave (las dos pruebas del PC y
  más), `lo_ultimo`, el minuto, `temas_de`.

## Lo que necesita un teléfono o una clave

- El reconocedor de voz de verdad (que dé frases sueltas, cómo se porta sin red, si comparte el
  micrófono con un audio sonando) y el permiso del micrófono.
- Las preguntas contra la API de verdad (como en el PC, se escribieron sin probarlas contra la API).
- Cómo se ve la chapa encima de la caja con el teclado abierto, y el repaso en pantallas pequeñas.
