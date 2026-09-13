# Plan: compartir unificado, lienzos por Wi-Fi, figuras, índice HTML y zonas (13-sep-2026)

Pedido por el usuario el 13-sep-2026. Se implementa **una función cada vez**, en este orden, con su
compilación y sus pruebas antes de pasar a la siguiente. Las dudas se preguntan antes de escribir.

## Decisiones tomadas con el usuario

- Menú de compartir: **hoja que sube desde abajo**, la misma en toda la app.
- «Guardar en un proyecto» se quita del menú de exportar del lienzo (hecho en 0.35.0).
- Figuras: solo las tres de la barra de figuras (plano cartesiano, espacio xyz, recta). El borrador
  no las borra; se mueven **manteniendo pulsado y arrastrando**; con candado no se mueven nunca.
  Cuadrado, círculo, rombo, flecha: normales.
- Zona sin interruptor: se arrastra **una foto de la zona** (como una captura).
- Zona con interruptor: va al chat del proyecto; la marca en el origen es **borde discontinuo +
  icono de enlace**.
- Lienzo recibido por Wi-Fi: si ya estaba, **se sustituye** (quien envía manda). En el chat se ve
  quién lo mandó con un **código fijo por aparato** de 4 signos, esté o no en un grupo.
- **No hay lienzos sueltos.** Todo lo que entra (PDF, imagen…) pide proyecto: nuevo, uno existente,
  o solo pinearlo a la pantalla. Tres niveles: **proyecto → lienzo → sublienzo**; un sublienzo es la
  zona de un lienzo mandada al chat.

## F1 · La hoja de compartir unificada

Un solo componente (`ui/HojaDeCompartir.kt`) que sube desde abajo:

- Arriba, **qué páginas**: «Lienzo completo», «Página 1», «Página 2»… (los marcos del lienzo, o las
  páginas de lo marcado en proyectos). En PDF y página web se marcan varias; en imagen y SVG, una.
- Botones redondos con los formatos que tocan: Imagen · PDF · SVG · Página web · Editable (.pixpin) ·
  Enviar por Wi-Fi. Con más de una página marcada, Imagen y SVG desaparecen.
- **El peso**: se genera el archivo en segundo plano con lo elegido y se enseña su tamaño real
  («1,4 MB»); compartir usa ese mismo archivo, así que no se hace dos veces.
- Sale por `CompartirEnlaceActivity` (archivo o enlace), como hasta ahora.
- El icono de compartir de iOS (`IosShare`) en todos los sitios.

Sitios que pasan a usarla: editor del lienzo, proyectos (caja de arriba y menú de la tarjeta), chat
(menú del mensaje), croquis 3D, tablas, visor de PDF del pin, captura.

Arreglo de paso: compartir imagen/PDF desde el chat escribía en `cache/compartir`, que el proveedor de
archivos no publica, y fallaba sin avisar.

## F2 · Enviar un lienzo suelto por Wi-Fi

- Desde la hoja de compartir del lienzo y desde el mensaje del chat: «Enviar por Wi-Fi».
- Viaja un `.pixpin` con el proyecto (su nombre e identidad) y **solo esa hoja**.
- Al recibir: si tiene ese proyecto (misma identidad) → la hoja se añade, o sustituye a la misma hoja
  si ya vino antes; si no → se crea el proyecto con el mismo nombre y esa hoja. Un segundo lienzo del
  mismo proyecto cae en el mismo proyecto.
- En el chat del proyecto aparece el lienzo con su hora de creación de origen y «Recibido de Max phone
  · K7Q2». El código de 4 signos sale del id del aparato y no cambia.

## F3 · Las tres figuras protegidas

- `Scene.intocableParaElBorrador` también para `esInstrumento`.
- En la herramienta de seleccionar, tocar y arrastrar un instrumento **no lo mueve**; hay que dejar el
  dedo quieto 450 ms encima y entonces arrastrar. Se nota con un pequeño salto de la selección.
- Candado: el que ya hay en el panel de acciones; con él puesto no se mueve ni se borra.

## F4 · Índice clicable en la página web

- Cada hoja lleva `id="hoja-N"`; `#hoja-N` en la dirección abre esa hoja (y al revés, cambiar de hoja
  actualiza la dirección, así un enlace lleva a una hoja concreta).
- Un índice con enlaces a todas las hojas, a la vista y plegable, además del botón «Páginas».

## F5 · Herramienta «Zona»

- Nueva herramienta en el lienzo: se arrastra un rectángulo.
- **Sin interruptor**: al soltar se hace una foto de la zona (lo que se ve: PDF de fondo y trazos) y
  queda encima con borde, seleccionada, para arrastrarla a otro sitio.
- **Con interruptor**: la foto va al chat del proyecto del lienzo como imagen, con debajo «PDF «Plano»
  → página 3» (o «Lienzo «Planta»»). Esa imagen es un **sublienzo**: una hoja del proyecto colgada de
  la hoja de origen, que se abre para resolver encima. En el lienzo de origen queda la marca (borde
  discontinuo + icono de enlace) que abre el sublienzo. El borrador no quita la marca.
- Si el lienzo no está en ningún proyecto: se pide proyecto (nuevo o existente) antes de mandar.

## F6 · Tres niveles en proyectos

- `Hoja.padre`: la hoja de la que salió un sublienzo.
- En la lista de hojas, los sublienzos se ven debajo de su lienzo, sangrados, con el vínculo.

## F7 · Todo entra en un proyecto

- Al abrir/compartir un PDF o una imagen hacia PixPin: tres opciones —nuevo proyecto, añadir a uno
  existente, o solo pinearlo—. Revisar qué entradas crean hoy lienzos fuera de proyectos.

---

## Cómo quedó (13-sep-2026, v0.36.0)

- **F1** · `ui/HojaDeCompartir.kt` (la hoja, genérica: formatos, páginas, peso real) y
  `ui/CompartirPaginas.kt` (los formatos de «unas páginas de unos proyectos»). La usan el editor del
  lienzo, proyectos (caja de arriba y menú de la tarjeta), el chat (menú del mensaje), el croquis 3D,
  las tablas. El visor de PDF del pin y la captura solo cambian de icono (son ventanas flotantes o
  un solo formato). Motor: `HojasDelProyecto.conEntero/elegidas` («Lienzo completo»),
  `PdfUnion.soloPaginas` (páginas del PDF del proyecto **vectoriales** en el PDF compartido; antes no
  salían).
- **F2** · `Envio.LIENZO`, `EnviarActivity.enviarLienzo/enviarArchivo`, `Recepcion.guardarLienzo`,
  `Hoja.origen`, `Mensaje.recibidoDe`, `Aparato.codigo` (4 signos del id).
- **F3** · `Scene.intocableParaElBorrador` + `Gesture.EsperandoInstrumento` (450 ms quieto).
- **F4** · `<details id="indice-fijo">` + `id="hoja-N"` + `#hoja-N` en la dirección.
- **F5/F6** · `Tool.ZONA`, `DrawController.alSoltarLaZona/alTocarEnlace/marcarZona`,
  `Element.enlace` (icono pintado por `Renderer.pintarIconoDeEnlace`, no se exporta),
  `DrawEditorActivity.alSoltarLaZona/mandarLaZona`, `Mensaje.vieneDe`, `Hoja.padre`. El sublienzo va
  **detrás de su lienzo** en el proyecto y se llama «↳ Zona de la página N». Sin proyecto, se pide.

Pendiente de decidir con el usuario: si los sublienzos se quieren **sangrados** en la rejilla de
proyectos (ahora van detrás de su lienzo, con «↳» en el nombre).
