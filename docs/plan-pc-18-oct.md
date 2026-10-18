# Plan: lo del PC del 15 al 18-oct en Android

Repo del PC revisado hasta `cf46430` (18-oct-2026), desde `882d465` (14-oct, ya portado en v0.126).
Rama de trabajo: `pc-18-oct`. Cada paso: implementar, pruebas del área, commit. Al final, suite
completa, relectura del diff, versión v0.127.0.

## Pasos (por orden: primero lo que afecta a la sincronización)

1. **Hojas de cálculo, un solo formato** (PC `01dccd4`, `docs/manual-hojas-android.md` §6):
   no abrir en blanco lo que no se lee, lectura tolerante con `viejo_<campo>`, `tocado` al migrar,
   no escribir más `tablas/libro-*.json` de trabajo.
2. **Láminas de geometría** (PC `6d3ed23`, `55d4ea4`, `1aa63ae`; `docs/manual-geometria-android.md`):
   `cierreArco`, `marcaAngulo`, `degradado`, `trazoRayaPunto`, ángulos con letra griega; pintar,
   conservar lo desconocido, y las herramientas del panel.
3. **Texto rico del lienzo** (PC `ccccb4e`, `050260e`; `docs/texto-rico-del-lienzo.md`): tramos con
   estilo y fórmulas en línea; pintar y conservar.
4. **Galería que viaja con el texto de cada captura** (PC `8258b31`, `pixpin-sincro/src/galeria.rs`).
5. **Enviar a los aparatos del grupo sin escanear** (PC `ccab4f0`): lista del grupo en «Enviar por
   Wi-Fi», directo por el canal cifrado y aviso al recibir.
6. **Tareas** (PC `bfdd89a`, `18aaf99`): tareas que se entienden al escribirlas (`tareas/frase.rs`),
   pin de una tarea con su nota, horario de clases que llama, vista Kanban.
7. **Notas y visores** (PC `18aaf99`, `918c973`, `b8403bc`): fotos en el sitio del cursor del editor
   de notas, visores de Markdown/HTML con la barra común, enlaces pulsables en la caja del chat.
8. **Asistente** (PC `36ade5b`, `c871730`, `c56820a`, `1aa63ae`, `99eb481`, `db0aa9a`, `75d73ad`,
   `5f53d60`, `433c17d`, `f8f9c4f`): modelo en el chat, conversación nueva, fechas al minuto, pensar
   solo en tareas complejas, herramientas nuevas (crear/usar lienzos, fotos y texto a un lienzo por
   nombre, banco de documentos, anotar con criterio, ojos en la nube, tablas de foto).
9. **Calcar a figuras exactas** (PC `7d24b58`, `c9ba22e`, `6f63d73`, `7da020b` y arreglos del 17-oct):
   cotejar con la rama `calcar-figuras` y portar lo que encaje sobre los pasos 2 y 3.
10. **Conexión con Google Drive** (PC `4706817`, `docs/conexiones-google-drive.md`), si cabe.

## Fuera (solo Windows)

Servidor MCP, extensión de Chrome/Edge, imprimir a PixPin y MSIX, línea de órdenes/Explorador/
PowerShell/AutoHotkey, Flow y notificaciones de Windows, Escape por capas, el lienzo en su hilo.

## Estado

(se rellena al acabar cada paso)

- **Paso 1 hecho** (hojas, un solo formato): `TablaDeCalculo.deJson` tolerante (`rescatar`, `viejo_<campo>`,
  estilos-palabra) y `aJsonSobre` que lo conserva; `TablasEnDisco.leer` distingue NoHay/NoSeLee y
  `guardar` no pisa lo que no se lee; `TablaActivity` no abre en blanco lo ilegible; hojas migradas
  con `tocado = editadaA`; la copia de trabajo de un Excel leído va a `files/tablas-leidas/` (no viaja).
  Pruebas: `TablaUnSoloFormatoTest`, `TablasDelChatTest`.
- **Paso 4 hecho** (galería con el texto de cada captura): `GaleriaCompartida.Entrada.texto` (vacío no se
  escribe, mismo JSON que el PC), `Local.texto`; `alDia` lo apunta sin tocar `cambiado`, `juntar` no lo
  pierde aunque gane la entrada sin él, `GaleriaQueViaja.aplicarJuntas` completa lo que llega sin textos y
  llama a `CapturasDelAparato.apuntarTexto` (no-op en el teléfono: el texto vive en el estado). La galería
  busca por él, saca «Con texto» si alguna lo tiene y enseña «Lo que pone» (elegible, «Copiar texto»).
  Android no tiene OCR propio. Pruebas: `GaleriaCompartidaTest`, `GaleriaQueViajaTest` (casos de
  `galeria/pruebas.rs` + teléfono sin OCR), `SincronizarDeVerdadTest`.
- **Paso 5 hecho** (enviar a los aparatos del grupo sin escanear): `sincro/AlGrupo.kt` (sin Android) es el cliente
  `suelto` del PC (`al_lienzo::Conexion` + `al_movil::mandar_ya`): mismo cable (hola sin ocupar, `suelto` con
  `destino:"abierto"`, vale antes de los trozos, `listo`/`donde`/`chat`, `adios`), misma tabla de tipos, mismos
  fallos y avisos (`SinSoporte` dice «que use el código de abajo»). `EnviarActivity`: caja «Tus aparatos · sin
  código» encima del código (solo con archivos, no proyectos ni lienzos), quién se ve por PING/mDNS
  (`AlDia.aparatos`, cada 15 s), «Enviar» por fila y «Enviar a todos los que se ven». Al recibir un `suelto`,
  `Presencia` saca un aviso (Toast) con de quién, qué y dónde quedó (`al-frente-*` del PC). Pruebas:
  `AlGrupoTest` (Android→Android por el `Respondedor` real; lienzo delante, otro código, PixPin viejo, sin grupo; y
  Android→PC de verdad con `pc-simulado recibir-suelto`, modo nuevo), `SueltoTest`, `SincronizarDeVerdadTest`,
  `SincronizarConElPcTest`. Sin probar en teléfono.
