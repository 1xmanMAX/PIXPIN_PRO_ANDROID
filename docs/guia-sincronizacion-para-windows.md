# Guía de la sincronización nueva, para Windows

*27-oct-2026 · Android v0.145.0 (sin subir la versión) · protocolo 4 · para que el PC la adopte
sin dejar de entenderse con nadie*

Esta versión de Android cambia **cómo** sincroniza, no **qué** se dice por el cable con quien no
sabe lo nuevo. Con el PC de hoy todo funciona igual que antes (probado contra el PC de verdad con
`herramientas/pc-simulado` y `SincronizarConElPcTest`). Lo que sigue es lo que el PC tiene que
hacer para tener lo mismo, por orden de lo que más se nota.

Índice:

1. [Compatibilidad: qué ve hoy el PC](#1-compatibilidad-qué-ve-hoy-el-pc)
2. [El diario de cambios y el atajo negociado](#2-el-diario-de-cambios-y-el-atajo-negociado)
3. [El orden por prioridad](#3-el-orden-por-prioridad)
4. [Reglas de fiabilidad](#4-reglas-de-fiabilidad)
5. [Automática por defecto](#5-automática-por-defecto)
6. [Versiones vinculadas en los chats compartidos](#6-versiones-vinculadas-en-los-chats-compartidos)
7. [Vectores de prueba](#7-vectores-de-prueba)
8. [Lista de tareas para el PC](#8-lista-de-tareas-para-el-pc)

---

## 1. Compatibilidad: qué ve hoy el PC

| Lo nuevo de Android | Qué le llega al PC de hoy | Por qué no le molesta |
|---|---|---|
| `Hola.capacidades: ["diario1","reanudar1"]` | el campo de más en el saludo | `Hola` es `#[serde(default)]` sin `deny_unknown_fields` (`crates/pixpin-sincro/src/mensajes.rs`); `version` sigue en **4** |
| `novedades`, `entero`, `desdeByte`, `reanudar` | **nada**: solo se mandan si el otro dijo la capacidad | el PC no manda `capacidades`, la intersección es vacía |
| Mensajes primero y archivos de varios chats mezclados | `dame`/`pon`/`parche`/`damecambios` de chats distintos seguidos, sin pedir antes `archivos` de ese chat | cada petición lleva su `chat` y el PC la contesta por su lado (ya calcula el alcance si no lo tenía) |
| Saltarse un chat quieto con el PC | `inventario` + `archivos` de ese chat y nada más (ni `aplicar`, ni `base`) | son peticiones de lectura; lo acordado del PC no cambia |
| Respuestas de Android con campos nuevos (`quietos`, `desdeByte`) | solo a quien los pidió | — |

Cuando el PC **dirige**, Android responde como siempre: lo único nuevo es que Android barre su
diario al empezar y salta la copia de seguridad de un chat que no cambió desde la última.

---

## 2. El diario de cambios y el atajo negociado

La especificación entera, con formatos y ejemplos, está en
**[`docs/sincronizacion-por-diario.md`](sincronizacion-por-diario.md)**. Lo esencial:

### 2.1 El diario (`sincro/diario/diario.jsonl`)

```
{"libro":"<uuid>","piso":0,"v":1}
{"n":1,"t":1761550000123,"k":"m","c":"general","o":"<uid>","a":"alta","r":"guardados.jsonl","h":"183920:1761550000123456789:(dev=fd01,ino=3141)"}
{"n":2,"t":1761550000999,"k":"f","o":"pins/draw/abc.excalidraw.gz","r":"pins/draw/abc.excalidraw.gz","h":"5120:1761550000990000000:(dev=fd01,ino=4242)"}
{"n":3,"t":1761550001500,"k":"x","o":"guardados.jsonl"}
```

- `n` solo crece; `k` ∈ `m` (mensaje), `p` (proyecto), `f` (archivo), `l` (lápida), `x` (todo);
  `c` el chat (no en `f` ni `x`); `o` el código único / id / ruta; `a` ∈ `alta`, `cambio`
  (por omisión), `baja`; `r`/`h` el archivo escrito y su huella `bytes:fecha_ns:nodo` (en Windows
  vale cualquier identificador estable del archivo en lugar del nodo: el índice de archivo de NTFS).
- Se apunta **después** de escribir. Lo que se escribe sin apuntar lo encuentra el **barrido** al
  empezar cada vuelta (tamaño, fecha, nodo; y CRC32 de lo escrito hace menos de 3 s).
- Compactar: una línea por cosa (`k|c|o`, en `f` `k|o`) cuando pasa de 4 000 líneas y hay el
  triple de líneas que de cosas.
- Línea rota, índice perdido, no se pudo apuntar → `x`. Sin diario o sin cabecera → otro `libro`.

### 2.2 Los cursores (`sincro/diario/cursores/<id>.json`)

```json
{"libro":"<uuid>","chats":{"general":{"n":118,"alli":"<sha256>","sello":"<sello de la Base>"}},"verificado":1761550000000,"vueltas":3}
```

`n` = el número de mi diario, leído **antes** de mirar, cuando al cerrar ese chat los dos lados
quedaron **idénticos**: plan de mensajes vacío (`Diferencia.plan` con lo acordado), plan de
archivos vacío y el mismo proyecto canónico. Si no quedaron idénticos, se quita el cursor.

### 2.3 Lo negociado

- `hola` con `"capacidades":["diario1"]` (y `"reanudar1"`). Se usa la **intersección**.
- `{"t":"novedades"}` → `{"quietos":["general","p-17"]}`: los chats que quien responde tiene sin
  apuntes después de su cursor para quien pregunta. Quien no lo sabe: `{"error":"No sé qué es «novedades»"}`.
- `{"t":"base",…,"entero":true}`: quedaron idénticos; quien responde apunta su cursor con el número
  que tenía al contestar el último `inventario` de ese chat en esta conexión. Sin `entero`, lo quita.
- Un chat **quieto en los dos** no se mira. Con quien no tiene diario (el PC de hoy, o un Android
  de antes): se pide `inventario` + `archivos` y, si su huella (§7.1) y el sello son los del
  cursor, tampoco se sigue.

### 2.4 La verificación entera

Cada 25 vueltas con un aparato o cada día (o si el reloj fue hacia atrás) se mira todo como antes
y se vuelven a apuntar los cursores. Las vueltas recortadas de tareas no cuentan.

### 2.5 Lo que gana

`VueltaRapidaTest` (30 chats × 120 mensajes + 6 lienzos + 6 archivos de 64 KB, en la JVM de las
pruebas, los dos «aparatos» en la misma máquina, cargada con otras compilaciones; lo que cuenta es
la proporción):

| vuelta | sin diario (como antes) | con diario | |
|---|---|---|---|
| sin cambios | 10 752 ms · 1 733 869 bytes | 95 ms · 853 bytes | ×113 |
| un mensaje y un lienzo cambiados en dos chats | 13 902 ms · 1 741 136 bytes | 946 ms · 123 625 bytes | ×15 |

La vuelta
sin cambios pasa de mirar 30 chats en los dos lados (copias, inventarios, resumir 360 archivos,
cerrar) a **una** petición (`novedades`).

---

## 3. El orden por prioridad

El usuario: «1) las listas de tareas, primero y lo más rápido; 2) los chats compartidos; 3) los
archivos (PDF y demás); 4) los lienzos, textos, notas y lo demás; 5) al final lo pesado: vídeos y
grabaciones. Dentro de cada nivel, y para los grupos (una grabación = su grupo de ficheros, su peso
total), lo más ligero primero».

La vuelta (`Sesion.vuelta`) va en **tres tiempos**:

1. **Mensajes y proyecto de todos los chats** (`preparar` + `aplicar`), en este orden de chats:
   - los que tienen alguna **lista de tareas** (mensaje `MINIAPP` con `miniapp = "tareas"`) o
     tabla del chat (`miniapp = "tabla"`);
   - los **compartidos** con otras personas (los de `compartidos.json`);
   - los demás, del que tiene **menos mensajes** al que tiene más; a igualdad, por id.
2. **Los archivos de todos esos chats juntos** (`prepararArchivos` de cada uno, y luego uno a uno
   en este orden):
   - nivel de cada archivo, por su extensión: **pesado** (`mp4 m4v mov mkv webm avi 3gp 3gpp m4a
     mp3 wav ogg oga opus aac flac amr wma`) · **lienzos y notas** (`*.excalidraw.gz`,
     `*.croquis.gz`, `tablas/…`, `notas/…`, `.md`, `.json`) · **archivos** (`pdf doc docx xls xlsx
     ppt pptx odt ods odp rtf epub csv txt zip png jpg jpeg webp gif heic heif bmp svg tif tiff dwg
     dxf`) · lo demás, con lienzos y notas;
   - lo que no es pesado **sube** a «tareas» si lo nombra una lista de tareas, o a «compartidos» si
     es de un chat compartido; **lo pesado es pesado siempre**;
   - **grupos**: lo de una carpeta del chat (una clase grabada: su voz, sus capturas, su vídeo) va
     junto; lo de un mensaje suelto (su adjunto y lo anotado), también. El grupo va en el nivel
     **más tardío** de sus piezas y pesa **lo que pesan todas**;
   - orden: nivel del grupo → peso del grupo → grupo → peso del archivo → ruta.
3. **Lo acordado** de cada chat (`cerrar`).

Así, si la conexión se corta a mitad de un vídeo, **las tareas y los chats compartidos ya
llegaron** (prueba: `DiarioDeCambiosTest`, «cortada a mitad del vídeo, las tareas y el chat
compartido ya llegaron»). Es solo el orden de las peticiones de quien dirige: el otro contesta cada
una como siempre. Ejemplo de orden (`PrioridadTest`):

```
tablas/tareas.json (de una lista)      → tareas
guardados/de-ana.pdf (chat compartido) → compartidos
guardados/chico.pdf, guardados/grande.pdf
pins/draw/chico.excalidraw.gz, pins/draw/grande.excalidraw.gz
guardados/video-de-ana.mp4 (1 KB), guardados/video.mp4 (50 MB)
```

y con grupos: `doc.pdf` · clase B (3 MB: captura, voz) · `suelto.mp4` (5 MB) · clase A (10 MB:
captura, voz, vídeo).

---

## 4. Reglas de fiabilidad

| Caso | Regla |
|---|---|
| **Se corta a mitad de un archivo** | Lo llegado de un archivo de ≥ 1 MiB (no de texto) se guarda en `<ruta>.parcial` con su ficha `<ruta>.parcial.json` `{"resumen","largo","cuando"}` y `fsync`. Con `reanudar1`, la vuelta siguiente pide/manda solo lo que falta (`desdeByte`), y al completarse se comprueba el **SHA-256 del archivo entero** antes de renombrarlo a su sitio. Otra versión → se tira y empieza de cero. Más de 7 días → se tira |
| **Escribir lo recibido** | A un temporal (`<ruta>.sincro`), `fsync`, y **renombrar encima** de un paso (nunca borrar antes y renombrar después) |
| **Comprobar antes de poner** | Detrás de los trozos va la cola con el SHA-256 de lo mandado; si no cuadra, se tira. Un trozo que se pasa del largo prometido corta la conexión sin escribirlo |
| **Tramos repetidos o reordenados** | Cada tramo AES-GCM lleva su número de orden en el nonce: uno repetido no se descifra. Si falla el **primero**, «otro código de grupo»; si falla otro, «trozo dañado o repetido» (conexión rota) |
| **Disco lleno** | Antes de recibir se mira el sitio (lo que llega + 16 MB de margen). Si no cabe o se llena a mitad, **lo que viene por el cable se lee y se tira** hasta su cola (la conversación no se descuadra), el temporal se borra y se contesta `{"saltado":true}`; quien dirige avisa «No queda espacio» |
| **La aplicación muere en mitad** | Al empezar la vuelta siguiente (ya con el aparato ocupado), fuera los `*.sincro`. Lo acordado se escribe con temporal y renombrar; si un lado lo guardó y el otro no, el sello no cuadra y se hace como la primera vez (ya era así) |
| **Dos vueltas a la vez** | Una sola por aparato (`ocupado`). Quien oye «ocupado» reintenta en **1–4 s con azar** (dos que chocaron no vuelven a chocar al mismo compás) |
| **Reintentos de la automática** | Un fallo de verdad: 5 s, 10 s, 20 s… hasta **5 min**, ±25 % de azar. Salir bien lo borra. Sin esperar al periodo de 10 min |
| **Relojes distintos** | El desfase del saludo ya corregía las fechas al fusionar y en las lápidas; el diario ordena por **número**, no por hora |
| **Borrado en uno, cambiado en otro** | Gana lo cambiado, en los dos (ya era así). El diario nunca se salta un chat tocado en cualquiera de los dos lados |
| **Versiones distintas** | Lo nuevo va por capacidades; sin ellas, el protocolo 4 de siempre |
| **Idempotencia** | Una segunda vuelta no mueve nada (ya era así) y ahora tampoco mira nada |

---

## 5. Automática por defecto

- La sincronización automática está **encendida si el usuario no la ha apagado**: una instalación
  nueva o un aparato nuevo, encendida; lo que el usuario apagó a mano («Sincronizar sola») se
  respeta. Vale para **los aparatos propios y para las personas de los chats compartidos**.
- Cuándo sale una vuelta: apareció un aparato (o una persona) que no contestaba; cambió algo y
  lleva 30 s quieto; cada 10 min; **al volver la red** (se sondea enseguida); y los reintentos de
  §4. Las tareas tachadas salen al momento (vuelta recortada a esos chats), como ya hacía el PC.
- Sin ruido: solo se dice algo si llega algo del otro lado, si falla tres veces seguidas o si no
  queda espacio.

El PC ya sincroniza solo desde el 8-oct; lo que le falta respecto a esto es lo de §4 (reintentos
con azar, al volver la red) y encenderlo también para las personas de los chats compartidos si no
lo hace.

---

## 6. Versiones vinculadas en los chats compartidos

Lo escribe aparte **[`docs/versiones-en-chats-compartidos.md`](versiones-en-chats-compartidos.md)**.
En corto:

- Solo en chats **compartidos con otras personas**. Lo de otra persona **no se edita nunca**: al
  primer cambio se crea una **versión**, un mensaje nuevo y mío con `respondeA` = el `id` del
  original (cualquier PixPin de antes lo ve como una respuesta), **`versionDe` = el código único
  del original** (el vínculo) y `versionN` opcional (solo etiqueta), con **sus propios archivos**
  copiados (adjunto, dibujo, lo anotado).
- Lo mío se edita en su sitio. Las listas de tareas y las tablas del chat **no** se versionan.
- Para la sincronización no cambia nada: una versión es un mensaje más con sus archivos, y como el
  original no se toca, no hay nada que fusionar. Para el orden de §3 una versión es como cualquier
  otro mensaje del chat compartido.

---

## 7. Vectores de prueba

### 7.1 Huella de un inventario

Texto que se resume con SHA-256 (hex minúsculo): por cada apunte, ordenados por `sena`,
`<sena>=<resumen>` + `!` si es marca de borrado + `\n`; luego `--\n`, el proyecto en JSON
canónico (o nada) y `\n--\n`; luego por cada archivo, ordenados por `ruta`, `<ruta>=<resumen>\n`.

```
apuntes  = [{sena:"b", resumen:"2"}, {sena:"a", resumen:"1", borrado:true}]
proyecto = null
archivos = [{ruta:"x.pdf", resumen:"ff"}]
texto    = "a=1!\nb=2\n--\n\n--\nx.pdf=ff\n"
huella   = 17850c345c3cb2825327a7bef787cab20e302089178cd80d61079c06ea5bf9d7
```

(`DiarioDeCambiosTest`, «la huella de un inventario es la de la guía para el PC».)

### 7.2 Conversación con atajo, entre dos que tienen `diario1`

```
→ {"t":"hola","hola":{"yo":{…},"reloj":…,"version":4,"puerto":…,"capacidades":["diario1","reanudar1"]}}
← {"hola":{"yo":{…},"miembros":[…],"reloj":…,"version":4,"puerto":…,"capacidades":["diario1","reanudar1"]}}
→ {"t":"novedades"}
← {"quietos":["general","p-1","p-2"]}
   (los tres quietos también aquí: ninguna petición más por ellos)
→ {"t":"adios"}
← {}
```

### 7.3 Con el PC de hoy (sin capacidades), chat quieto

```
→ {"t":"hola","hola":{…,"version":4,"capacidades":["diario1","reanudar1"]}}
← {"hola":{…,"version":4}}                 (sin capacidades)
→ {"t":"inventario","chat":"general"}
← {"apuntes":[…],"proyecto":…,"selloDeBase":"<sello del cursor>"}
→ {"t":"archivos","chat":"general"}
← {"archivos":[…]}                         (huella == cursor.alli → quieto, no se pide nada más)
```

### 7.4 Reanudar al traer

```
→ {"t":"dame","chat":"general","ruta":"guardados/clase.mp4","desdeByte":2097152}
← {"bytes":2097152,"desdeByte":2097152}
← TROZO × n (2 MiB)
← {"resumen":"<sha256 de los 2 MiB mandados>"}
   (quien recibe: parcial + esto = 4 MiB; SHA-256 del entero == resumen del inventario → a su sitio)
```

### 7.5 Reanudar al mandar

```
→ {"t":"pon","chat":"general","ruta":"guardados/obra.mp4","bytes":4194304,"resumen":"<sha256 entero>","reanudar":true}
← {"desdeByte":1048576}
→ TROZO × n (3 MiB)
→ {"resumen":"<sha256 de los 3 MiB>"}
← {}                                       ({"saltado":true} si no cuadró el entero o no cupo)
```

---

## 8. Lista de tareas para el PC

Por orden de lo que más se nota; cada punto se puede hacer solo.

1. **Orden por prioridad** (§3) en su vuelta: mensajes de todos primero (tareas, compartidos, del
   más ligero), archivos de todos juntos por niveles y grupos, y cerrar al final.
2. **Fiabilidad** (§4): renombrar encima tras `fsync`; mirar el sitio y leer-y-tirar si no cabe;
   limpiar `*.sincro` al empezar; reintentos con azar y espera que se dobla; distinguir el primer
   tramo que no se descifra de los demás; cortar si un trozo se pasa del largo.
3. **Diario + barrido + índice + cursores** (§2, y la especificación), y saltarse un chat con un
   Android sin preguntarle nada más que su inventario (§2.3, la huella).
4. **`capacidades:["diario1"]`**, `novedades` y `entero` (§2.3).
5. **`reanudar1`** (§4, §7.4–7.5).
6. Automática también con las personas de los chats compartidos y al volver la red (§5).
7. Versiones vinculadas (§6, su documento).

Pruebas que conviene tener en el PC (las de Android que las cubren, entre paréntesis):
segunda vuelta sin peticiones (`la segunda vuelta ni mira los chats…`), un cambio solo mira su
chat, lo escrito sin apuntar también llega, sin diario no se pierde nada, cortada a mitad se
reanuda en los dos sentidos, sin sitio no se descuadra, trozo repetido o de más corta, dos a la
vez, cortada a mitad del vídeo ya trajo las tareas (todas en `DiarioDeCambiosTest`, `PrioridadTest`
y `SincronizarConElPcTest`).
