# La app se llama PinDesk también en Android (1-nov-2026)

El PC pasó a llamarse **PinDesk** (ver `docs/nombre-pindesk.md` del repo de Windows): «PixPin» es
el nombre de otro producto (el de DepthPixel). Aquí se sigue su regla para los agentes de Android:
**cambia solo el nombre que ve el usuario; nada que guarde datos o que lea otro aparato**.

## Qué cambió

| Dónde | Antes | Ahora |
|---|---|---|
| `app_name` (nombre del icono y de la app), bienvenida, canal y aviso de la bola, fallo, versión, permisos (`values/strings*.xml` y `values-en/strings*.xml`) | PixPin | PinDesk |
| Etiquetas del manifiesto: «Soltar en…», «Buscar en…» (hoja de compartir y atajos) | PixPin | PinDesk |
| Avisos y toasts en Kotlin (Sincronizar, Enviar a…, cámara del PC, alarmas exactas, instalar APK, imprimir, borrar datos, Lectura Veloz, Soltar, guardar compartido…) | «abre PixPin…», «actualiza PixPin…» | «abre PinDesk…», «actualiza PinDesk…» (como el PC: «Actualiza PinDesk en { $movil }») |
| Rótulo del formato editable al compartir | «PixPin (.pixpin)» | «PinDesk (.pixpin)» (igual que `compartir-pixpin` del PC) |
| Familia de colores de los `.pixpin` (descripción accesible del icono) | PixPin | PinDesk |
| Asistente: instrucciones de sistema (`Agente`, `Nube`, `Memoria`), ayuda (`Conocimiento`), descripciones de las acciones (`Acciones`) y el catálogo del PC (`ia/catalogo-del-pc.json` y su volcado de prueba `herramientas-del-pc.json`) | «el asistente de PixPin» | «el asistente de PinDesk» (el PC ya lo dice así; la prueba compara byte a byte) |
| Exportaciones: `generator` de los HTML (lienzo, clase, plano), `Application` y autor por omisión del `.docx`, cabecera de los `.obj`/`.mtl`, asunto del informe de fallo y del reporte de IA | PixPin | PinDesk |
| Nombre del trabajo de impresión, álbum de «Leyendo en voz alta», etiquetas del portapapeles, título por omisión del visor HTML | PixPin | PinDesk |
| Lo que dice el buscador del sistema («Proyecto · …», «… · PinDesk») y el nombre alternativo «PinDesk <nombre>» | PixPin | PinDesk |
| Cómo se presenta en «Escáner Pro Max» (`CALLER_LABEL`: «Adjuntar a …») | PixPin | PinDesk |
| `docs/privacidad.html` (con «antes PixPin»), `README.md`, `docs/guia.md`, `docs/img/portada.svg` y `lapiz.svg` | PixPin | PinDesk |

Pruebas cambiadas, solo las que miran texto de la interfaz: `FormatosDeCompartirTest`,
`CompartirCarpetaTest`, `EscanerProMaxTest`, `EscaneoEditableTest`, `AlGrupoTest` y el volcado
`herramientas-del-pc.json`.

## Qué se queda igual, a propósito

| Se queda | Por qué |
|---|---|
| `com.forge.pixpin` (paquete y `applicationId`), los paquetes y nombres de Kotlin (`PixPinApp`, `AbrirConPixPinActivity`, `Theme.PixPin`…) | Internos; cambiar el `applicationId` sería otra app |
| La extensión `.pixpin`, su formato, el cuaderno, el índice, `aplicacion`/`source` de los JSON | Los leen el PC, Mac, iPad y las versiones viejas |
| Sincronizar: `_pixpin._tcp`, los nombres de instancia «PixPin <aparato>» de `anunciar(…)`, la baliza, las etiquetas HMAC, la versión del protocolo | El PC nuevo y el viejo buscan eso; el PC enseña el nombre de instancia pero no lo compara |
| El esquema `pixpin://` | Está en enlaces, notificaciones y Lectura Veloz |
| Las capas de los PDF («PixPin · hoja N», «PixPin — página», la marca `(PixPin`) | Las escribe y las reconoce también el PC |
| Las fotos `PixPin_…` y las carpetas `Pictures/PixPin` y `Movies/PixPin` (y el texto «Guardado en Pictures/PixPin», que dice la carpeta real) | Son las carpetas donde ya están las capturas del usuario y las que lee la galería que viaja |
| Los mazos de Anki «PixPin::…» y el modelo «PixPin Básico» | Datos del usuario en Anki: otro nombre partiría sus mazos |
| Las etiquetas de `Log` («PixPin») y el agente HTTP `PixPinMax/1.0` | Nadie los ve |
| Las carpetas «PixPin Max» del PC simulado en las pruebas | Son la carpeta de datos del PC, que tampoco cambia |
| Los comentarios del código, los documentos de desarrollo e historia (`docs/*.md` salvo la guía, `docs/superpowers/`) y las librerías de Rust de `herramientas/` | Cuentan lo que pasó con el nombre de entonces |
| La nota de la licencia («No afiliado a PixPin ni a DepthPixel») | Habla del otro producto |
