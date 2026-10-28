# Jev de TypeSafe en Android (28-oct-2026)

Lo que pide `docs/jev.md` del PC (y su «## Android»), hecho en el teléfono con **el mismo formato y las
mismas preguntas** que el PC (`crates/pixpin-typesafe`, `crates/pixpin-proyecto/src/clase.rs`,
`apps/pixpin/src/jev*`; commits `e6ccca92`, `df8884e2`, `3115502c`, `d8ddb756`, `24fdfa97`, `4c144080`,
`a6b248fe`, `6f70a416`, `a4974705`, `fa4ca12b`, `e51da395`, `c1540add`).

**Jev** es el modelo «System One» de TypeSafe: no escribe texto, contesta **preguntas tipadas** (Noul,
Choice, Score, Box) sobre un estado con **probabilidades calibradas**. La regla, la del PC: **los juicios
de Jev, las decisiones en código** (una tabla con sus pruebas por función).

## Lo que ve el usuario

### Sin clave (lo de siempre, más lo que llega del PC)

- **Estudiar la clase**: si la nota de voz llega del PC con `importantes`, bajo el minuto de cada
  renglón sale **un punto de color por tipo** (azul `#3b82f6` tarea, verde `#22a06b` decisión, morado
  `#8b5cf6` pregunta, rojo `#e5534b` examen) y, encima del texto, el filtro **«Todo · Tarea N ·
  Decisión N · Pregunta N · Examen N»** (N = renglones de ese tipo; solo salen los tipos que hay). Con
  un tipo elegido solo se ven sus renglones (sin capturas ni marcadores, como la página del PC).
- **«+N tareas a Tareas»** apunta cada tarea aún sin apuntar en el **Inbox de Tareas** con su nota
  `💬 🎙 <clase> ⏱ m:ss` y le pone `enTareas: true` (no se vuelve a ofrecer). No necesita clave.
- **La página compartida** (Compartir › Página web de la grabación) lleva las chapitas y el filtro
  del PC (`chip`, `data-tipos`, `filtro-importante`; el CSS y el JS son los del PC tal cual).
- Todo lo demás, exactamente como antes: Anki manda todas las tarjetas, Tareas sin chapitas ✦ ni otro
  orden, el asistente con su modelo de siempre.

### Con clave (Ajustes › Asistente › «TypeSafe (Jev)»)

La sección tiene el campo de la clave («Guardar y probar», «Probar», «Borrar», «Sacar una») y un
interruptor por función (encendidas de fábrica; sin clave no hacen nada):

1. **Lo importante de clases y reuniones**: al acabar de pasar a texto una nota de voz (la cola de
   `PasarATexto`) se buscan sus tareas, decisiones, preguntas y lo de examen (o un dato clave), cuatro
   peticiones a la vez, y se escriben en la nota. También al abrir «Estudiar la clase» si la nota aún
   no se miró. Mientras tanto, «Buscando lo importante…». **Volver a pasarla a texto lo rehace entero**;
   lo ya apuntado en Tareas sigue marcado. Una canción no se mira.
2. **Solo las tarjetas de Anki que valen la pena**: «Enviar a Anki» de una clase (y el formato Anki de
   Compartir) valora lo dicho en el tramo de cada captura y deja fuera la charla; el aviso dice
   «· Jev dejó fuera N capturas sin nada que estudiar». Si falla, todas.
3. **Prioridad de las tareas según el contexto**: en Tareas (Tarjetas y Kanban) sale la chapita
   **«✦ Urgente»**, **«✦ Alta»** o **«✦ Baja»** (la chispa dice que es de Jev; una tarea marcada a mano
   con ⏫/🔼 enseña solo su marca) y lo pendiente se ordena por prioridad (urgente, alta, normal, baja)
   antes que por fecha. Se pregunta en segundo plano (60 por tanda, cuatro a la vez) y Tareas relee al
   acabar. **Se calcula en cada aparato, solo en memoria: ni se guarda ni viaja.** Tras un fallo, esa
   tarea no se vuelve a preguntar en 10 minutos.
4. **Elegir el modelo según el pedido**: en el chat «Asistente», lo que no es una orden del camino
   rápido va a **Claude Sonnet** (`claude-sonnet-5-5`) o **DeepSeek V4 Pro** (`deepseek-v4-pro`) si Jev
   lo juzga difícil, y si no al de siempre (Haiku, DeepSeek Flash), con la misma clave y el mismo
   «pensar». Se decide en el hilo del turno, al pedir la primera respuesta. Encima de la caja del chat
   sale la pastilla **«✦ Claude Sonnet · Jev»** (o el que fuera) durante 15 minutos.

**Un fallo de Jev (sin red, 401, 429, respuesta rota, más de 2 s) es un error, nunca un «no»**: la
función hace lo de siempre y lo apunta en el registro.

## El formato de `importantes` (el del PC, §14 de `manual-sincronizar-lo-nuevo.md`)

En la nota de voz, junto a los demás campos (en el PC, en su `resto`):

```json
"importantes": [
  {"ms": 20000, "p": 0.81, "tipo": "examen"},
  {"enTareas": true, "ms": 45000, "p": 0.94, "quien": true, "tipo": "tarea"}
],
"importantesDe": "jev-1.14.0"
```

- `ms`: el del renglón `[m:ss]` de la transcripción; `tipo`: `tarea`, `decision`, `pregunta` o
  `examen` (uno por elemento; un tipo desconocido se salta sin llevarse los demás); `p`: 0-1 con dos
  decimales, solo lo que pasa de 0.7; en las tareas, `quien`/`cuando` si se sabe y `enTareas: true`
  una vez apuntada. Las claves de cada objeto en orden alfabético (el del `Map` del PC).
- **Sin la clave `importantes`: nunca se miró. `[]`: se miró y no hay nada.** `importantesDe`: el
  modelo que lo halló (se quita si no se sabe).
- Se conservan tal cual al reescribir el mensaje aquí (`Mensaje.importantes`/`importantesDe`); en un
  choque, la regla general (la lista entera, gana el que dirige).
- Las cuentas: `guardados/Importantes.kt` (`de`, `poner`, `marcarEnTareas`, `tiposEn`,
  `seVeConFiltro`, los de `clase.rs`).

## Privacidad: qué se manda

Solo con clave y con la función encendida, solo a `https://api.typesafe.ai/v1/systemone` por HTTPS:

- **Reunión**: el título de la grabación y su transcripción, renglón a renglón con hasta dos vecinos
  de cada lado. Ni el audio ni el vídeo ni las capturas.
- **Anki**: el título y lo dicho en el tramo de cada captura. Las imágenes no.
- **Urgencia**: el texto de cada tarea pendiente (con su nota), el título de su lista, el nombre de su
  chat y los cinco últimos mensajes escritos de ese chat. Las fechas no.
- **Modelo**: el pedido al asistente y los tres últimos mensajes de la conversación.

**La clave** va cifrada con una llave AES-GCM del **Android Keystore** (no exportable) en
`noBackupFilesDir/claves/typesafe.clave`, como las de Claude y DeepSeek: nunca en los ajustes ni en
nada que se sincronice, se exporte o entre en la copia de Android. El registro (`Log.d`, etiqueta
`PixPinJev`) lleva el cuerpo exacto de cada petición y su respuesta, con las imágenes cambiadas por su
tamaño, **y nunca la clave**. Los interruptores (`SharedPreferences` «jev») son de este aparato.

## Dónde está

- `jev/Api.kt`: la petición y la respuesta según el OpenAPI 0.2.0 (`api.rs`).
- `jev/Cliente.kt`: el POST con **2 s**, el **cache** de peticiones idénticas (SHA-256 del cuerpo,
  2048 por proceso), **un reintento con 429/529** si queda tiempo, el registro sin clave, OkHttp y el
  **transporte grabado** de las pruebas (`Grabado`), y `enParalelo`.
- `jev/Preguntas.kt`: **todo el texto de todas las preguntas**, sus criterios, los niveles de cada
  Score y los umbrales (0.7 / 0.3). Copia letra por letra de `preguntas.rs` (las 60 cadenas, en el
  mismo orden). Ningún texto de pregunta vive fuera de ahí.
- `jev/Reunion.kt`, `jev/Anki.kt`, `jev/Urgencia.kt`, `jev/Modelo.kt`: el estado de cada función y su
  tabla de decisión (`decidir`, `veredicto`/`elegir`).
- `jev/Jev.kt`: la clave (Keystore), los interruptores y `para(contexto, función)`.
- `jev/ReunionEnLaApp.kt`, `AnkiEnLaApp.kt`, `UrgenciaEnLaApp.kt`, `ModeloEnLaApp.kt`: dónde se
  engancha cada función; `jev/VistasDeJev.kt`: Ajustes y la pastilla del modelo.
- `guardados/Importantes.kt` y `guardados/VistaDeImportantes.kt`: el campo y lo que se enseña (sin
  clave). Ganchos pequeños en `PasarATexto.kt`, `EstudiarClaseActivity.kt`, `ClaseExportar.kt`,
  `ExportarGrabacion.kt`, `anki/`, `mini/TodasLasTareas*.kt`, `mini/ChapitasDeTarea.kt`,
  `asistente/Asistente.kt` (una línea), `asistente/TarjetasDelAsistente.kt` (una línea) e
  `ia/Nube.kt` (el modelo del motor, opcional).

## Pruebas

En la JVM, sin red ni teléfono: `jev/ApiYClienteTest` (el JSON, errores que no son «no», cache,
reintento, cabecera de imágenes, registro sin clave, con el transporte grabado), `jev/TablasDeJevTest`
(las preguntas y cada tabla fila a fila, cada función con respuestas grabadas), `jev/JevEnLaAppTest`
(Anki con y sin Jev, prioridad y su chapita, el motor que elige, el modelo en el cuerpo),
`guardados/ImportantesTest` (el formato, `[]` frente a nada, sobrevivir a reescribir) y
`ClaseExportarImportantesTest` (las chapitas y el filtro de la página).

**Falta probar con una clave de verdad**: las preguntas se escribieron en el PC sin probarlas contra
la API (no había clave el 28-oct-2026). Con clave, en un teléfono: pasar a texto una clase y mirar las
marcas, mandar una clase a Anki, abrir Tareas, pedir algo difícil al asistente; y la red real (OkHttp,
el tiempo de 2 s desde el móvil). Si hay que ajustar, se ajusta el texto en `Preguntas.kt` (igual que
en `preguntas.rs` del PC), no los umbrales.
