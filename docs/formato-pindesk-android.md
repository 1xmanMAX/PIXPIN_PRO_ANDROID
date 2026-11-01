# El formato y los nombres de PinDesk en Android (1-nov-2026)

El PC pasó su formato y sus nombres a **PinDesk** (`docs/formato-pindesk.md` del repo de Windows,
commits `c7a42d26..c2ed81ff`). Esto es lo que hace Android con cada cosa, siguiendo la misma regla:
**se escribe el nombre nuevo, se lee también el viejo**; lo que otro aparato tiene que leer y aún
no sabe leer se sigue escribiendo con el viejo (fase B).

El paquete de Android (`com.forge.pixpin`, `applicationId`) **no cambia**.

## Lo que Android escribe ya con el nombre nuevo (fase A) y lo que sigue leyendo

| Qué | Antes | Android escribe | Android lee | Dónde |
|---|---|---|---|---|
| Extensión del proyecto (exportar, compartir, enviar por Wi‑Fi, editable de los visores, cuaderno, croquis, lienzo) | `.pixpin` | `.pindesk` | `.pindesk` y `.pixpin` (abrir, importar, recibir, «Abrir con», compartir a PinDesk, color e icono) | `PaquetePixpin.EXTENSION`, `esPaquete`, `esExtension`; `ModoDePaso`, `NuevoProyectoCompartidoActivity`, `Mensajes.kt`, `ColorDeExtension`, `AndroidManifest.xml` (`pathPattern` de los dos) |
| `manifest.json`: `formato` | `"pixpin"` | `"pindesk"` | `"pindesk"` o `"pixpin"` | `PaquetePixpin.manifiesto`, `esManifiesto` |
| `manifest.json`: `aplicacion` | `"pixpin-android"` | `"pindesk-android"` | cualquiera (informativo) | `PaquetePixpin.APLICACION` |
| `manifest.json`: `formato_de_antes` | — | `"pixpin"` (nuevo campo) | se ignora | Un Android sin actualizar solo abría el paquete si el manifiesto decía `"pixpin"` en algún sitio: así lo sigue abriendo. Nadie más mira el manifiesto al abrir |
| Rótulo al compartir | «PinDesk (.pixpin)» | «PinDesk (.pindesk)» (y los textos de `values*/strings.xml`) | ids `editable`, `pindesk` y `pixpin` | `FormatosDeCompartir` |
| Enlaces de la app | `pixpin://atajo/…`, `pixpin://nota/…`, `pixpin://dibujo/…`… | `pindesk://…` (atajos fijos de `shortcuts.xml`, atajos dinámicos, buscador del sistema, señas de los documentos) | `pindesk://atajo/…` y `pixpin://atajo/…`, mismos verbos y reglas | `Atajos.ESQUEMA`, `Atajos.esEsquema`, `AtajoActivity`, manifiesto |
| Versión de los pedidos | `{"pixpin": 1, …}` | `{"pindesk": 1, …}` (asistente, tareas, citas de documentos) | los dos; si vienen los dos, manda `pindesk` | `ia/Pedidos.kt` (`VERSION`, `version`, `esCampoDeVersion`). El PC acepta `"pindesk": 1` desde la misma versión en que lo escribe |
| Capas de los PDF anotados | `PixPin · hoja N`, `PixPin — página N` | `PinDesk · hoja N`, `PinDesk — página N` | las dos (`hojaDeLaCapa`, `esCapaNuestra`, como `cocido.rs` del PC) | `PdfConAnotaciones`, `PdfDelProyecto`, `DrawPdf`, `PdfAnotado` |
| Mazos de Anki | `PixPin::<chat>`, etiqueta `pixpin` | `PinDesk::<chat>`, etiqueta `pindesk` | — (los mazos de antes se quedan en Anki como están) | `AnkiDelMensaje.MAZO`, `ETIQUETA`; `ExportarGrabacion` |
| mDNS: tipos | `_pixpin._tcp`, `_pixpinenvio._tcp`, `_pixpinrecibe._tcp`, `_pixpinchat._tcp` | **los dos a la vez**: `_pindesk…` y `_pixpin…` | **los dos a la vez**; un aparato que sale en los dos queda una vez, con lo del nuevo (se le reconoce por el TXT `id`, o `g` y el nombre) | `Red.TIPO`, `Envio.TIPO`, `Envio.TIPO_RECIBIR`, `Invitacion.TIPO`; `Red.anunciar`, `Red.buscar`, `Red.tipoDeAntes`, `Red.juntar` |
| mDNS: nombre de la instancia | `PixPin <aparato>` | `PinDesk <aparato>` | cualquiera (se enseña, no se compara) | `Red.instancia` |
| QR de envío, de recibir y de chat (lectura) | `pixpin-envio:`, `pixpin-recibe:`, `pixpin-chat:` | (ver fase B) | `pindesk-…` y `pixpin-…` | `Envio.leerQr`, `Invitacion.leerQr` |
| Lectura Veloz: enlace de ida | `lecturaveloz://pixpin/abrir?…` | `lecturaveloz://pindesk/abrir?…` **si la Lectura Veloz instalada lo recibe**; si no, el de antes | — | `LecturaVeloz.HOST`, `HOST_DE_ANTES`; `LecturaVelozEnElTelefono.hostQueSabe` pregunta al sistema (el filtro de Android de Lectura Veloz dice el anfitrión: hasta la 0.3.1 solo `pixpin`) |
| Lectura Veloz: vuelta | el aviso `com.forge.pixpin.LECTURA_VELOZ` | — | el mismo aviso (en Android no hay enlace de vuelta: el `pindesk://lectura-veloz?…` es del PC) | `LecturaVelozReceiver` |
| Emoji del lienzo: `customData` | `{"pixpin":"emoji"}` | — (Android no crea emojis en el lienzo) | se conserva tal cual, con las dos marcas (`{"pindesk":"emoji","pixpin":"emoji"}` del PC) | `Element.customData` |
| Enlaces de las notas | `pixpin:hoja=…`, `pixpin:mensaje=…` | (ver fase B) | `pindesk:` y `pixpin:` | `Incrustados.enlace` |

## Lo que Android sigue escribiendo con el nombre viejo (fase B)

| Qué | Se escribe (por ahora) | Ya se lee el nuevo |
|---|---|---|
| QR que enseña Android | `pixpin-envio:1:…`, `pixpin-recibe:1:…`, `pixpin-chat:1:…` | sí |
| Rutas portátiles dentro de los datos | `pixpin:files/<ruta>` | no (como el PC) |
| Enlaces de las notas | `pixpin:hoja=…`, `pixpin:mensaje=…` | sí |
| Ficheros al lado de un documento | `.pixpin-lectura`, `.pixpin-anotado/`, `.pixpin-marcos`, `.pixpin-vistas`, `.pixpin-marcas` | no |
| Formatos internos | `.pixpin2d`, `.pixpin-lista` | no |
| Fotos y carpetas | `PixPin_<fecha>.png`, `Pictures/PixPin`, `Movies/PixPin` | — |
| `customData.origen` de lo que dibuja el asistente | `"pixpin-ia"` | cualquiera (informativo) |
| Modelo de AnkiDroid | «PixPin Básico» | — (un modelo nuevo partiría las tarjetas del usuario) |

## Lo que no cambia

Como en el PC: las magias del cable y la baliza (`PXS1`, `PXB1`, `PXC1`, `PXI1`) y la versión del
protocolo, la sal de la clave del grupo (`pixpin-sincro-grupo`), los textos firmados de los chats
compartidos (`pixpin-chat|entra|…`, `pixpin-chat|sale|…`), los alias del almacén de claves y el
nombre de la base del buscador del sistema.

## Con aparatos sin actualizar

- **El PC sin actualizar** busca y anuncia solo `_pixpin…`: Android sigue anunciando y buscando
  también esos tipos, así que se ven igual. Las pruebas contra el PC simulado
  (`SincronizarConElPcTest`, `SincronizarDeVerdadTest`, `herramientas/pc-simulado`) no usan mDNS y
  no cambian.
- **Un Android sin actualizar** abre los `.pindesk` que le lleguen por Wi‑Fi (el tipo del envío
  manda, no la extensión, y el manifiesto lleva `formato_de_antes`), pero no reconoce la extensión
  `.pindesk` si se le comparte el archivo suelto.
- **Lectura Veloz ≤ 0.3.1** solo recibe `lecturaveloz://pixpin/abrir`: se le sigue mandando ese.

## Pruebas

`FormatoPinDeskTest` (QR, mDNS, enlaces, pedidos, capas, Anki, emoji), `PaquetePixpinTest`
(`.pindesk`, manifiesto, paquetes de antes), `ModoDePasoTest`, `ColorDeExtensionTest`,
`FormatosDeCompartirTest`, `CompartirCarpetaTest`, `AccionesTest`, `TareasIaTest`,
`DocumentosTest` (`bm25-del-pc.json` con `"pindesk": 1`, como el PC), `TarjetasTest`,
`LecturaVelozTest` y `LecturaVelozEnElTelefonoTest` (el anfitrión según la Lectura Veloz instalada).
