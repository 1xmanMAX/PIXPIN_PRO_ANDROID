# Versiones vinculadas en los chats compartidos

*27-oct-2026 · Android v0.145.0 · para que el PC adopte la misma regla*

## El problema

En un chat compartido con otras personas, yo edito lo que alguien me pasó y esa persona edita lo
que yo le pasé. Al sincronizar, la fusión de tres vías junta los dos cambios en el mismo mensaje y
el mismo archivo: entre dos personas eso da una tercera cosa que nadie hizo (un lienzo con la
planta movida por uno y borrada por el otro, tinta de dos personas mezclada sobre el mismo PDF).

## La regla

Solo en **chats compartidos** (`compartidos.json`, con otras personas dentro). En un chat propio, y
entre los aparatos de una misma persona, todo sigue como hasta ahora.

1. **Lo mío se edita en su sitio.** Es mío lo que lleva el código (`aparato`) de cualquiera de mis
   aparatos: este, los de mi grupo de sincronizar y los de la lista del chat cuyo `id` es mío. Lo sin
   código, o con un código que no es de nadie de la lista (el asistente, `"IA"`), también es mío.
   Es la regla de «mío» de las burbujas (`EntrePersonas.kt` / `conversacion.rs`).
2. **Lo ajeno no se toca nunca.** Lo que lleva el código de un aparato de **otra persona** de la
   lista no se cambia. Al primer cambio se crea una **versión**: un mensaje nuevo, mío, con:
   - `id` nuevo, `uid` nuevo (`Codigos.nuevo()`), `aparato` = el mío, `numero` nuevo en el chat,
     `cuando` = cuando nace;
   - **`respondeA` = el `id` del original** — así cualquier PixPin, también uno que no sepa nada de
     versiones, la enseña como una respuesta al original;
   - **`versionDe` = el código único del original** (`uid`, o `Codigos.unico` si no lo tiene): es el
     vínculo que no cambia entre aparatos;
   - **`versionN`** (opcional) = 1 + cuántas versiones de ese original había ya en el chat. Solo
     es una etiqueta («Versión 2»): dos aparatos pueden repetir número y no pasa nada;
   - sin `fijado`, `emoji`, `recuerdaEn`, `enBuzon`, `unido`, `letra`, `origen`, `recibidoDe`,
     `hojaDelTexto` (son de la organización del chat o del aparato);
   - **archivos propios**: el adjunto copiado a otra ruta (`guardados/<hora>_v<n>_<nombre>`), el
     dibujo copiado a otro id (`dib-<id nuevo>` para lienzos y páginas, `foto-<id nuevo>` para una
     foto anotada; con sus `.marcas` y `.vistas.json`), y lo anotado del original
     (`pins/draw/anot-<uid original>*`) copiado a `anot-<uid nuevo>*`. Copias y no enlaces: borrar el
     original se lleva sus archivos, y la versión tiene que seguir entera.
3. **Lo que se siga cambiando va a la versión**, que ya es mía (regla 1).
4. Si otra persona cambia lo mío, ella hace su versión; a mí me llega como un mensaje nuevo que
   contesta al mío. El mío no cambia.
5. Volver a abrir el original y cambiarlo otra vez crea **otra** versión (`versionN` + 1). El original
   se abre siempre como es; para seguir con la mía, se abre la mía.

Ninguna regla de fusión cambia: el original no se toca, así que no hay nada que juntar, y la versión
es un mensaje más con sus propios archivos.

## Qué se versiona

| Qué | Cómo | Cuándo nace la versión |
|---|---|---|
| Lienzo (`DIBUJO`), página de PDF (`PAGINA`) | borrador: el editor trabaja sobre la copia | al volver del editor, si algo cambió |
| Foto (`IMAGEN`, no vídeo) | borrador (su dibujo, o el que hará el editor) | ídem; la foto de fondo que pone el editor no cuenta |
| Documento anotado (`ARCHIVO`: pdf, doc/docx/odt/rtf, ppt/pptx/odp, epub, html, xls/xlsx/xlsm/ods/csv/tsv) | borrador: copia del archivo y de lo anotado | ídem |
| Mini-aplicación (gastos, carpeta de páginas, …) | provisional: la copia entra en el chat al abrir | se quita al volver si no cambió |
| Nota, «describir» un archivo, un toque dentro de la burbuja de una mini-app | al momento | con el cambio |

**No** se versionan: las **listas de tareas** (tachar en una lista compartida es lo que se quiere y
la fusión las junta tarea a tarea), las **tablas del chat** (hoy solo se miran; si se vuelven a
editar, entran como provisionales), audios, vídeos, croquis, proyectos y lo que se abre en otra
aplicación. Fijar, poner una etiqueta o renombrar tampoco: son de la organización del chat.

**Si cambió** se mide sobre la copia, sin depender de cómo reescriba cada editor: el texto y el
nombre; de un dibujo, los elementos vivos con su `version` (`id:version`); de lo anotado, cada
archivo menos lo que es de leer (`.sitio`, `.voz`) y el marco de la hoja (`.hoja`).

Un borrador sin cambios se guarda (`files/sincro/versiones-en-curso.json`, no viaja) por si su editor
sigue abierto en otra tarea, y se tira a las 12 horas. Abrir para mirar no deja nada en el chat.

## Lo anotado sobre un documento ajeno

Mi tinta sobre el PDF de Ana va en **mi versión del PDF**: una copia del archivo en un mensaje mío,
con lo que Ana ya había anotado copiado a mi código. Así lo anotado sigue la regla de siempre (va
con el código único de su mensaje) y no hacen falta capas por aparato: la tinta de Ana se ve en el
suyo y la mía en el mío.

## En pantalla

- Encima de una versión: **«✎ Versión de Ana»** (o «Versión 2 de Ana», «Versión de lo tuyo» si el
  original es mío); tocarlo lleva al original. Debajo va la cita del original, por `respondeA`.
- En el original: un chip **«✎ 2 versiones ▾»** que lista «Versión 1 · tuya», «Versión 2 · Ana»…;
  cada una lleva a la suya.
- Al abrir algo ajeno que se puede editar: aviso «Es de Ana: si lo cambias, se guarda como tu
  versión y lo suyo no se toca». Al volver con cambios: «Se guardó como tu versión».

## Compatibilidad con el PC

- `versionDe` y `versionN` son claves nuevas del mensaje; Android no las escribe si no hay
  (`@EncodeDefault(NEVER)`) y un Android de antes las ignora.
- El PC **las conserva**: `pixpin-proyecto::cuaderno::Mensaje` lleva
  `#[serde(flatten)] resto: Map<String, Value>` («Lo que no se entiende, tal cual: guardar no puede
  perder lo que añada una versión de Android»), y la sincronización del PC guarda lo que llega
  «tal cual viene» (`vista.rs::aplicar_mensajes`, JSON portátil). La fusión de los dos lados trabaja
  sobre el JSON y conserva las claves que no conoce.
- Un PC sin esta regla ve la versión como **un mensaje normal que contesta al original** (con su
  propio lienzo, adjunto y anotado, que viajan como los de cualquier mensaje). Lo único que no hace
  es la regla: si en el PC se edita algo ajeno, se edita en su sitio como hasta ahora.

Para adoptarla en el PC: al editar en un chat compartido un mensaje cuyo `aparato` es de otra
persona (`conversacion.rs::Lados::quien` → `Otro`), en vez de escribir en él, crear la versión de
arriba (mismos campos y mismos nombres de archivo) y llevar el editor a ella; y pintar los dos
rótulos a partir de `versionDe`.
