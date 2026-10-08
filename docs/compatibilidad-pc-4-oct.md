# Lo del PC del 3 al 7-oct, en Android (v0.105.0)

Seis frentes portados del PC (`1xmanMAX/PIXPIN_PRO_WINDOWS`) y unidos en la rama `pc-4-oct`.
**Nada de esto se ha probado en un teléfono**: solo compila y pasan las pruebas JVM.

## Lo que se portó

| Frente | Qué | Dónde |
|---|---|---|
| Tareas con imágenes | `![img NN](ruta)` dentro de la tarea, con las mismas reglas que `imagenes_de` del PC; miniaturas de 28 dp en la fila; adjuntar (fotos, cámara, pegar) con chapas `[img NN]`; quitar con Deshacer; borrar la lista entera (viaja con su marca de borrado) | `mini/Tareas.kt`, `mini/ImagenesDeTareas.kt`, `mini/MiniActivity.kt` |
| Todas las tareas | Una pantalla con las listas de todos los chats, Inbox, buscador (sin tildes, por día), mover a otra lista copiando las imágenes, quitar con Deshacer; «Tarea rápida» sin proyecto va al Inbox | `mini/TodasLasTareas.kt`, `mini/TodasLasTareasActivity.kt`, `mini/TareaRapidaActivity.kt` |
| Lecciones v2 | Barra «¿Qué aprendiste?» que propone área, proyecto y gravedad; ficha en tres bloques con guardado solo; repaso 1/2/3 con «A medias»; borrar con Deshacer. El formato `.leccion` no cambia | `lecciones/Rapida.kt`, `Leccion.kt`, `Etiquetador.kt`, `LeccionesActivity.kt`, `LeccionActivity.kt` |
| Galería de capturas v2 | Caducidad (7 días de fábrica, conservar, «7 días más») con `capturas-caducidad.json` igual que el PC; barrido al arrancar y cada hora a la papelera del sistema; filtros, búsqueda por nombre y fecha, grupos por día, elegir varias | `capture/CaducidadDeCapturas.kt`, `BarrenderoDeCapturas.kt`, `GaleriaLogica.kt`, `ui/GaleriaDeCapturasActivity.kt` |
| Chat | Logo del proyecto (solo local, como en el PC), descripción en fotos y archivos (`Mensaje.texto`), menú en dos tandas con «Más», enviar hasta 20 fotos con orden y descripción, fotos derechas según su EXIF al entrar | `guardados/LogoDelProyecto.kt`, `Descripcion.kt`, `FotoDerecha.kt`, `CuentasDeLaFoto.kt`, `MensajesActivity.kt` |
| Ajustes v2 y Buscar v2 | Buscador de ajustes, punto azul de lo cambiado, «De fábrica», «Restablecer sección» y Deshacer; herramientas apagadas en todos los sitios o en uno; Buscar con recientes, pestañas y vista previa | `ajustes/`, `motor/HerramientasPorSitio.kt`, `atajos/BuscarActivity.kt`, `BuscarEnTodo.kt` |

Al unir: la imagen dentro de una tarea se lee en un solo sitio (`Tareas.imagenes`,
`Tareas.cambiarEnlaces`, `Tareas.rutaDeImagen`, `Tareas.nombreDeCopia`, `Tareas.reponer`), y la
pantalla de todas las tareas usa la misma caché de miniaturas que la lista. «Días hasta borrar»
entra en el catálogo de Ajustes v2 (sección Capturar).

## Lo que no, y por qué

- **Búsqueda por el texto de las capturas y filtro «Con texto»**: Android no lleva OCR.
- **Papelera propia de capturas**: se usa la del sistema; en Android 10, que no tiene, se borra del todo.
- **Mover tareas entre listas desde la lista**: solo desde la pantalla de todas las tareas.
- **Borrar las copias `tarea-*` al borrar una lista o quitar una imagen ya guardada**: igual que borrar en el chat, solo se borran las copias nuevas que no llegaron a ninguna tarea.
- **Que el logo viaje**: el PC no lo lee; habría que cambiarlo en las dos apps a la vez.
- **Cuadrícula con buscador en la hoja del clip**: se conserva la fila al estilo de Telegram.
- **Lo propio del escritorio** (tres columnas, atajos de teclado, Ctrl+V, flechas y foco).
- **Croquis 3D en el reparto de herramientas**: no tiene barra del motor.
- **Pestaña «Capturas» en Buscar**: las capturas no entran en el buscador; la galería sale en Acciones.

## Sin probar

Cámara, pegar desde Gboard, visor de fotos de tareas, papelera del sistema (Android 10–15),
barrido horario, arrastre de fotos en el envío múltiple y EXIF: solo compilados.

## Lo del 6 y 7-oct

| Del PC | En Android |
|---|---|
| **Foto al lienzo del móvil** (`p móvil` en Flow Launcher, pedido `enviar_al_movil`) | Hecho. Petición `suelto` del protocolo (sigue en v4, opcional), tal como la describe la guía del PC (`docs/investigacion/2026-10-06-foto-al-lienzo-android.md`): `Respondedor.recibirSuelto` en `sincro/Protocolo.kt`; `motor/LienzoAlFrente.kt` sabe qué lienzo está delante (lo pone el `onResume` del editor, lo quita su `onPause`); `DrawEditorActivity.recibirImagen` la centra en lo que se mira y la encoge al 60 % de la vista si es más grande; `Presencia.alLienzoOAlChat` la manda a la Conversación general si no hay lienzo delante o no es una imagen. Funciona con cualquier pantalla de PixPin a la vista (y 60 s después), sin abrir Sincronizar. |
| Lecciones del timeline con **nota de voz** | Ya se entendía: el móvil lee los mensajes `VOZ` que responden a una lección como sus adjuntos. |
| Timeline (momentos, Hoy/Momentos/Estado) | No viaja: el PC lo guarda en `timeline/momentos.jsonl`, fuera del cuaderno que se sincroniza. No hay nada que entender aquí. |
| Tareas en tarjetas, emoticonos como etiquetas, «Editar» una tarea | Nada que cambiar: los emoticonos salen del texto y «Editar» es el mismo `renombrar` que ya hace el móvil. |
| Salida para arrastrar, anotaciones fusionadas, galería simplificada, diseño v2 | Del escritorio; el formato no cambia. |

Probado: `SueltoTest` (11 pruebas) contra el `Respondedor` de verdad, una de ellas con el
**cliente Rust del PC** (`herramientas/pc-simulado suelto`, con `PIXPIN_PC_SIMULADO`): una foto
al lienzo, otra al chat y un móvil «de antes» que el PC reconoce como tal.
**Sin probar en teléfono**: que la foto aparezca en el lienzo sin tocar la pantalla.

## Lo de la noche del 7-oct (v0.106.0)

`p s` en Flow Launcher manda **cualquier archivo** a lo que el móvil tenga abierto (guía del PC:
`docs/investigacion/2026-10-07-archivos-al-chat-abierto-android.md`).

| Delante en el móvil | Una foto | Otro archivo |
|---|---|---|
| Un chat | a ese chat (`chat_abierto` + su nombre) | a ese chat |
| Un lienzo | al lienzo | **se niega antes del «vale»**: «<móvil> tiene un lienzo abierto: solo acepta fotos» |
| Nada | Conversación general | Conversación general |

- `sincro/LoAbierto.kt`: la tabla, sin Android. Con pantalla partida manda el que se puso delante el último (`OrdenAlFrente`).
- `guardados/ChatAlFrente.kt`: lo pone el `onResume` de `MensajesActivity`; el chat se pregunta en el momento, porque dentro de esa pantalla se cambia de conversación sin `onResume`.
- `Respondedor`: `alAceptarSuelto` (antes del vale) y `alRecibirSuelto` (devuelve dónde y qué chat). Tope: 2 GB, a disco por trozos.
- Al llegar entero se vuelve a mirar qué hay delante: con un vídeo el usuario puede haber cambiado de pantalla.
- Lo que entra en un chat no se aligera (un PDF llega tal cual) y lleva «recibido de».
- Sin portar: el PC atiende `suelto` aunque esté sincronizando con otro; el móvil sigue contestando «ocupado» (atiende las conexiones de una en una).

Probado: `SueltoTest` (16), con el cliente Rust del PC del 7-oct: PDF con lienzo delante → `SoloFotos` y la foto de detrás entra.
