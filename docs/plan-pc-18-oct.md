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
