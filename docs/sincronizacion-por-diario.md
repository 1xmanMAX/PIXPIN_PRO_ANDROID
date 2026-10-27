# Sincronización por diario de cambios

*27-oct-2026 · Android v0.145.0 en adelante · protocolo 4 (sin cambiar la versión)*

El usuario pidió: «ahora inicia una comprobación paso a paso, cosa por cosa. Sería mejor guardar
todos los cambios que se hacen en el dispositivo en un solo archivo (qué se añadió, cambió o quitó
y cuándo) y que al sincronizar la app lea solo ese archivo y sincronice solo eso». Esto es cómo lo
hace Android y lo que el PC tiene que hacer para hacer lo mismo **sin dejar de entenderse con
quien no lo hace**.

Resumen en tres líneas:

1. Cada aparato lleva un **diario** (`sincro/diario/diario.jsonl`): una línea por cada cosa que
   cambia en lo que viaja, con un número que solo crece.
2. Al cerrar un chat con otro aparato, si los dos quedaron **idénticos**, cada uno apunta un
   **cursor**: «hasta el número N de mi diario, este chat está igual que en ese aparato».
3. En la vuelta siguiente, un chat que **no tiene apuntes después de su cursor en ninguno de los
   dos lados** no se mira: ni inventario, ni copia de seguridad, ni resumir archivos, ni cerrar.

Nunca es la única verdad: si el diario falta, está roto o no lo explica todo, se hace la vuelta
de siempre. Un fallo del diario cuesta tiempo, nunca datos.

---

## 1. El diario (`files/sincro/diario/diario.jsonl`)

JSON por líneas, UTF-8, `\n` al final de cada línea.

**Primera línea, la cabecera:**

```json
{"libro":"6f1c9a1e-0c1f-4b5e-9a7d-0c2b9f3e1a77","piso":0,"v":1}
```

- `libro`: identificador al azar (UUID) de este diario. **Cambia si el diario vuelve a nacer**
  (se borró, no tenía cabecera). Los cursores guardan el libro: con otro libro no valen.
- `piso`: números por debajo de este se olvidaron al compactar (ver §1.3). Un cursor por debajo
  del piso no vale.
- `v`: versión del formato (1).

**Cada línea siguiente, un apunte:**

```json
{"n":42,"t":1761550000123,"k":"m","c":"general","o":"Q2F7…","a":"cambio","r":"guardados.jsonl","h":"183920:1761550000123456789:(dev=fd01,ino=3141)"}
```

| campo | qué | notas |
|---|---|---|
| `n` | número de orden | empieza en 1 y **solo crece**; no se repite nunca dentro de un libro |
| `t` | hora de este aparato (ms) | solo para leerla; el orden lo da `n` (un reloj cambiado a mano no estropea nada) |
| `k` | qué es | `m` mensaje · `p` proyecto · `f` archivo · `l` lápida de un proyecto · `x` **«no se sabe: todo»** |
| `c` | chat | id del chat (`general` o el id del proyecto). **Falta en `f` y en `x`** |
| `o` | cuál | `m`: código único del mensaje (`uid`) · `p`/`l`: id del proyecto · `f`: ruta relativa a `files` (`pins/draw/abc.excalidraw.gz`) · `x`: el motivo |
| `a` | qué pasó | `alta` · `cambio` · `baja` (por omisión `cambio`, y entonces no se escribe) |
| `r` | archivo del disco escrito | opcional; ver el barrido (§1.2) |
| `h` | huella de `r` tras escribirlo | `bytes:fecha_ns:nodo`, o `-` si ya no existe |

Los campos con su valor por omisión no se escriben (como el resto del protocolo:
`encodeDefaults = false`). Quien lea ignora los campos que no conozca.

### 1.1 Quién apunta

En Android, quien escribe:

- `MensajesStore` (la aplicación): al añadir un mensaje (`m alta`) y al reescribir el chat
  (`m alta/cambio/baja`, comparando la lista de antes y la de después por `(chat, id)`).
- `ProyectosRepository` (la aplicación): `p alta/cambio/baja` por proyecto.
- `Disco` (la sincronización, recepciones, copias restauradas): los mensajes, las marcas de
  borrado (`m baja` con `r = sincro/borrados.jsonl`), los proyectos, las lápidas (`l`), y cada
  archivo que escribe o borra (`f`).

**Se apunta después de escribir**, con la llave del chat puesta. Así, si al cerrar una vuelta se
lee el número N y luego se mira cómo está el chat, cualquier cosa escrita después tiene un número
mayor que N (ver §2).

### 1.2 El barrido: lo escrito sin apuntar

Muchas partes de la aplicación escriben archivos (el editor de lienzos, las tablas, el croquis,
la tinta sobre un PDF, el proceso aparte que aligera los PDF…). En vez de poner un gancho en cada
una, **al empezar cada vuelta, en los dos lados**, se recorre lo que viaja **sin leerlo**: para
cada archivo, tamaño, fecha de modificación **en nanosegundos** y nodo (inodo) —una sola llamada
al sistema por archivo—. Se compara con lo guardado la vez anterior en
`sincro/diario/huellas.tsv`:

```
#1761550000123
guardados/informe.pdf	183920	1761549000123456789	(dev=fd01,ino=3141)	-
pins/draw/abc.excalidraw.gz	5120	1761550000100000000	(dev=fd01,ino=4242)	2874019221
```

(columnas: ruta, bytes, fecha en ns, nodo, CRC32 o `-`).

- Lo que cambió, apareció o desapareció **y no lo explica un apunte** (el último apunte con
  `r` = esa ruta tiene `h` igual a la huella de ahora) se apunta: `f` con la ruta, o **`x`** si es
  uno de los índices de muchos chats (`guardados.jsonl`, `proyectos/proyectos.json`,
  `sincro/borrados.jsonl`, `sincro/chatsborrados.jsonl`): no se sabe de qué chat, así que todos.
- **Lo escrito hace menos de 3 s** (y de menos de 8 MB) lleva además su CRC32. Es lo que git llama
  «racily clean»: dos escrituras del mismo tamaño en el mismo tic del reloj del núcleo dejan la
  misma fecha; la próxima vez, si la huella es igual pero el CRC no, cambió.
- Lo que recorre: `guardados/`, `pins/`, `proyectos/`, `tablas/`, `croquis3d/`, `notas/`, `voz/` y
  los cuatro índices. Fuera los temporales: `*.tmp`, `*.sincro`, `*.nuevo`, `*.parcial`,
  `*.parcial.json`.
- Sin `huellas.tsv` (primera vez, o perdido) se toma la foto sin apuntar nada; si el diario ya
  tenía apuntes, además un `x` («sin huellas»).

### 1.3 Se compacta solo

Para saber «¿cambió el chat C después de N?» basta el **último** apunte de cada cosa
(`k|c|o`; en `f`, `k|o`). Cuando el archivo pasa de 4 000 líneas y tiene más del triple de líneas
que cosas distintas, se reescribe con una línea por cosa (a un temporal, `fsync`, y se renombra
encima). Si aun así quedan más de 200 000 cosas, se olvida la mitad más vieja y **sube el
`piso`**. El último número no baja nunca.

### 1.4 Lo que no se entiende es «todo»

- Una línea que no se lee (rota a mitad: la aplicación murió escribiéndola) → se corta lo que
  sobra y lo primero que se apunte después es un `x`.
- No se pudo apuntar (disco lleno) → igual: `x` en cuanto se pueda.
- El índice de archivos (§1.5) perdido o de otro libro → `x`.
- Sin diario, o sin cabecera → nace otro, con **otro libro** (todos los cursores dejan de valer).

### 1.5 De qué chat es un archivo: el índice

`sincro/diario/indice.tsv`: la primera línea `#<libro>`; luego `chat \t ruta␁ruta␁…` (separadas
por U+0001). Se rehace cada vez que se calcula lo que es de un chat (su **alcance**: adjuntos,
lienzos, tablas, croquis, PDF del proyecto, lo anotado y las fotos de dentro de los lienzos).

Un apunte `f` cuenta para los chats que el índice da para esa ruta. Si el índice no la conoce
(un archivo recién llegado), se buscan los mensajes y proyectos que la nombran
(`Disco.chatsQueNombran`, sin abrir lienzos). Si nadie la nombra, no es de ningún chat y no
cuenta. (Una foto nueva dentro de un lienzo llega con el lienzo, que sí está en el índice.)

---

## 2. Cursores: hasta dónde quedó igual cada chat con cada aparato

`sincro/diario/cursores/<id del otro aparato>.json`:

```json
{"libro":"6f1c…","chats":{"general":{"n":118,"alli":"9b0c…","sello":"5e1d…"},"p-17":{"n":96}},"verificado":1761550000000,"vueltas":3}
```

- `chats[c].n`: el número de **mi** diario cuando, al cerrar ese chat con ese aparato, **los dos
  lados estaban idénticos**. Se lee **antes** de mirar cómo quedó el chat.
- `alli` (solo quien dirigió): la huella del inventario del otro en ese momento (§4.3).
- `sello` (solo quien dirigió): el sello de lo acordado (`Base.sello`).
- `verificado`, `vueltas`: para la verificación entera (§5).

**Idénticos** quiere decir: el plan de mensajes con lo acordado sale vacío
(`Diferencia.plan(mios, suyos, base.mensajes)`), el de archivos también (lo que una persona de un
chat compartido no puede ver no cuenta) y el proyecto es el mismo en JSON canónico (o no hay en
ninguno). Si no, el cursor de ese chat **se quita**.

Un chat está **quieto aquí** si tiene cursor con ese aparato, el libro del cursor es el de ahora,
`piso ≤ n ≤ último`, y no hay ningún apunte con número mayor que `n` que sea de ese chat (o `x`).

Al salir del grupo se borran todos los cursores (como lo acordado).

---

## 3. El saludo: capacidades

El `hola` (en `Peticion.hola` y en `Respuesta.hola`) lleva un campo nuevo:

```json
{"t":"hola","hola":{"yo":{"id":"…","nombre":"Teléfono","letra":"b"},"reloj":1761550000123,"version":4,"puerto":47823,"capacidades":["diario1","reanudar1"]}}
```

- `diario1`: sabe contestar `novedades` y entiende `entero` en `base` (§4).
- `reanudar1`: sabe reanudar archivos grandes (§6).

**La versión del protocolo sigue siendo 4.** Una capacidad solo se usa si **los dos** la dicen
(la intersección). El PC de hoy:

- lee `Hola` con `#[serde(default)]` y **sin** `deny_unknown_fields`
  (`crates/pixpin-sincro/src/mensajes.rs`), así que `capacidades` ni lo ve;
- no manda `capacidades`, así que Android no le pide nada nuevo y le habla exactamente como antes.

Comprobado contra el PC de verdad (`herramientas/pc-simulado`, `SincronizarConElPcTest`, «con el
PC - el saludo con capacidades no le molesta…»), en los dos sentidos.

---

## 4. La vuelta con diario

### 4.1 Al empezar

Con el aparato ocupado (una vuelta a la vez), **los dos** lados, antes de nada:

1. Tiran los restos de una vuelta que murió a medias: todo `*.sincro` de las carpetas que viajan,
   y lo llegado a medias (`*.parcial`) de más de 7 días.
2. Barren (§1.2).

### 4.2 `novedades` (solo si los dos tienen `diario1`)

Petición (la manda quien dirige, una vez, la primera vez que la necesita):

```json
{"t":"novedades"}
```

Respuesta:

```json
{"quietos":["general","p-17"]}
```

Los chats que **el que responde** tiene quietos respecto a quien pregunta (su cursor para ese
aparato existe y no hay apuntes después). Con una persona de un chat compartido, solo los chats
que comparten. Quien no sabe contesta `{"error":"No sé qué es «novedades»"}` y se sigue sin ello;
cualquier otro error (p. ej. «ocupado») es de verdad.

### 4.3 Cada chat (`Sesion.preparar`)

Quien dirige, para el chat C:

1. Si C **no** está quieto aquí → la vuelta de siempre.
2. Si el otro tiene `diario1`: si C está en sus `quietos` → **C no se mira** (ni una petición).
3. Si el otro **no** tiene diario (el PC de hoy): se pide `inventario` y `archivos` de C como
   siempre; si `selloDeBase` es el sello del cursor (y el de mi `Base` también) y la huella de lo
   que dice tener es la del cursor (`alli`) → **C no se mira más** (ni copia, ni sellar, ni leer
   el chat aquí, ni cerrar). Si no, se sigue con la vuelta de siempre aprovechando ese inventario.

**Huella de un inventario** (`Diario.huellaDeInventario`), SHA-256 en hexadecimal minúsculo de:

```
<sena>=<resumen>[!]\n      por cada apunte, ordenados por sena; «!» si es una marca de borrado
--\n
<proyecto en JSON canónico, o nada>\n
--\n
<ruta>=<resumen>\n         por cada archivo, ordenados por ruta (sin fechas ni tamaños)
```

Vector de prueba: apuntes `[{sena:"b",resumen:"2"},{sena:"a",resumen:"1",borrado:true}]`, sin
proyecto, archivos `[{ruta:"x.pdf",resumen:"ff"}]` → se resume el texto
`"a=1!\nb=2\n--\n\n--\nx.pdf=ff\n"`.

### 4.4 Al cerrar cada chat (`Sesion.cerrar`)

Quien dirige:

1. Lee el último número de su diario (`N`) **antes** de mirar nada.
2. Pide `inventario` y `archivos` (como siempre), calcula lo acordado (como siempre).
3. Manda `base` como siempre y, si el otro tiene `diario1`, con `"entero":true` cuando quedaron
   idénticos (§2):

   ```json
   {"t":"base","chat":"general","base":{…},"entero":true}
   ```

4. Guarda lo acordado y su cursor: `{n: N, alli: huella(inventario, archivos), sello: base.sello}`
   si quedaron idénticos; si no, quita el de ese chat.

Quien responde, al recibir `base` de quien tiene `diario1`: si `entero`, cursor
`{n: <su último número al contestar el último «inventario» de ese chat en esta conexión>}`; si no,
quita el suyo. Lo que escriba después (el usuario mientras tanto) queda por encima.

---

## 5. La verificación entera

Cada **25 vueltas** con un aparato o **cada día** (o si el reloj fue hacia atrás), la vuelta se
hace sin mirar el diario (todo como antes) y vuelve a apuntar los cursores. Cura lo que el
diario no viera. Las vueltas recortadas de las tareas al instante no cuentan.

---

## 6. Reanudar archivos grandes (`reanudar1`)

Para archivos que **no** son de texto (no lienzos, croquis ni tablas) de **1 MiB o más**, si los
dos tienen `reanudar1`:

**Traer** (`dame`):

```json
{"t":"dame","chat":"general","ruta":"guardados/clase.mp4","desdeByte":2097152}
```

→ `{"bytes":<lo que falta>,"desdeByte":2097152}` y los trozos desde ese byte, con la cola de
siempre (resumen **de lo mandado**). Quien recibe añade a `<ruta>.parcial` (con su ficha
`<ruta>.parcial.json`: `{"resumen":…,"largo":…}`) y, al completarlo, comprueba el **SHA-256 del
archivo entero** contra el resumen del inventario antes de ponerlo en su sitio (renombrar). Si se
corta, lo llegado se queda (con `fsync`) para la vuelta siguiente. Si la versión cambió, se tira.

**Mandar** (`pon`):

```json
{"t":"pon","chat":"general","ruta":"guardados/obra.mp4","bytes":4194304,"resumen":"<sha256 del archivo entero>","reanudar":true}
```

→ quien recibe contesta **antes de los trozos** `{"desdeByte":<lo que ya tiene de esa versión>}`
(o un error, y entonces no llega ningún trozo), y luego llegan solo los que faltan, la cola, y
`{"saltado":false}` como siempre.

Sin `reanudar1` en los dos, `dame` y `pon` son los de siempre (un `desdeByte` de más lo ignoraría
el otro y mandaría el archivo entero: por eso solo se manda a quien lo dijo).

---

## 7. Lo que se arregló para que sea fiable

| caso | qué pasaba | ahora |
|---|---|---|
| Se corta a mitad de un archivo | el temporal se tiraba; un vídeo empezaba de cero cada vez | se reanuda (§6) |
| Archivo recibido | se borraba el de antes y luego se renombraba (morir en medio: sin archivo) | `fsync` del temporal y **renombrar encima** de un paso |
| Lo recibido no cuadra | ya se comprobaba la cola | igual, y lo reanudado se comprueba **entero** |
| Trozo repetido o de más | podía escribir de más | `Llegó más de lo prometido`; un tramo repetido o fuera de orden (cada tramo lleva su número en el cifrado) corta con «trozo dañado o repetido» en vez de «otro código de grupo» |
| Disco lleno | la excepción mataba la conversación a mitad | se mira el sitio antes (16 MB de margen); si se llena a mitad, lo que llega se lee y se tira (la conversación sigue), queda «saltado» y la vuelta avisa «No queda espacio» |
| La aplicación muere en mitad | quedaban `*.sincro` para siempre | se limpian al empezar la vuelta (§4.1) |
| Dos aparatos se llaman a la vez | los dos oían «ocupado» y esperaban 10 minutos | reintento en 1–4 s con azar; un fallo, 5 s, 10 s, 20 s… hasta 5 min (±25 %) |
| Borrado en uno y cambiado en el otro | ya: gana lo cambiado | igual (el diario no se salta un chat tocado en cualquiera de los dos) |
| Relojes distintos | el desfase ya corregía fechas al fusionar | igual; el diario ordena por número, no por hora |
| Segunda vuelta | lo miraba todo otra vez para no mover nada | no mira nada (y sigue sin mover nada) |
| `proyectos.json` | la aplicación lo escribía encima tal cual | temporal y renombrar |

---

## 8. Lo que el PC tiene que hacer para hacer lo mismo

Sin esto todo sigue funcionando; con esto, el PC también se salta lo quieto.

1. **Su diario**, con este formato (§1), en su carpeta de datos. Apuntar donde escribe: mensajes,
   proyectos, lápidas, marcas de borrado y archivos de lo que viaja; y barrer al empezar cada
   vuelta.
2. **Su índice** de archivos por chat (§1.5), al calcular el alcance.
3. **Cursores** por aparato (§2), con la misma regla de «idénticos».
4. **`capacidades`** en su `Hola`: `["diario1"]` (y `"reanudar1"` si hace §6). Usar solo la
   intersección.
5. Como **quien responde**: contestar `novedades` (§4.2) y apuntar el cursor en `base` con
   `entero` (§4.4).
6. Como **quien dirige**: pedir `novedades`, saltarse lo quieto (§4.3), mandar `entero` y apuntar
   su cursor al cerrar (§4.4). Con un Android sin diario, la huella del inventario (§4.3).
7. Verificación entera cada 25 vueltas o cada día (§5).
8. Opcional: reanudar (§6).

Ver también `docs/guia-sincronizacion-para-windows.md`, que lo junta con el orden por prioridad y
lo demás de esta versión.
