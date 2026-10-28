# Pedidos del usuario: checklist

Todo lo que el usuario pidió desde el 26-oct-2026, para no perder nada. Se marca `[x]` cuando está
hecho **y publicado** en un APK; `[~]` cuando está hecho pero aún sin publicar; `[ ]` pendiente.
Al cerrar cada tanda se revisa esta lista punto por punto.

## Publicado

- [x] v0.143.0 — Grabaciones y carpetas: se ven y se sincronizan; tarjetas e iconos como el PC.
- [x] v0.144.0 — OCR con Indra en el teléfono; Whisper como motor de voz por defecto.
- [x] v0.145.0 — Fuera la pantalla de Proyectos; atrás: visor → chat → lista → salir.
- [x] v0.146.0 — Toda la tanda del 27-oct (puntos 1–36, abajo). Probado con pruebas JVM y el PC
      simulado; falta verlo en el teléfono.

## Tanda del 27-oct (publicada en v0.146.0)

### Chat
- [x] 1. Unirse a un chat compartido (el «⋮» de las instrucciones ya no existe): entrada en el «+»
      de la lista, con código/QR, y textos de ayuda corregidos.
- [x] 2. Ficha del chat sin la pestaña Proyectos: Fotos, Vídeos, Archivos, Enlaces, Voz, Dibujos,
      Fijados.
- [x] 3. Asistente: quitar la tira «Letras de plano / N figuras / Otros» que tapa el chat (los
      avisos siguen guardándose y viajando).
- [x] 4. Todos los chats abren en el último mensaje o donde se dejó; botón «↓» como Telegram.
- [x] 5. Estudiar la clase: sin el tema «espacio»; mismo tema que los visores; día/noche.
- [x] 6. Cabecera del chat: quitar «N cosas · X MB»; lupa y ⋮ más juntos.
- [x] 7. Chips de búsqueda (Todos, Fotos…) con fondo legible.
- [x] 8. Quitar «Añadir arrastrando» (es del PC).
- [x] 9. Ajustes abre los ajustes, sin la ventana de bienvenida; «Bienvenido a PixPin» + «Comenzar»
      arriba de los ajustes; fuera «Mensajes guardados» de ahí.
- [x] 10. Adjuntar organizado como el PC, con estilo de teléfono.
- [x] 11. Búsqueda global (lupa de la lista): abrir el archivo directamente desde el resultado, o ir
      al mensaje exacto en su chat (hoy abre el chat sin llevar al mensaje); resultados agrupados
      por tipo como dentro de un chat (Archivos, Fotos…).

### Sincronización
- [x] 12. Pantalla de sincronizar: aparatos en cuadrados a dos columnas (nombre, punto de color,
      botón de sincronizar a mano; sin textos de estado); botón fijo amarillo «+ Añadir»; papelera
      con icono; actividades como están; Recibir/Enviar solo con lo esencial.
- [x] 13. Motor más rápido: diario de cambios (solo lo cambiado), camino rápido entre Android
      negociado; con el PC, el protocolo de siempre.
- [x] 14. Más fiable: cortes a mitad, relojes, rondas simultáneas, app cerrada, disco lleno,
      ficheros enormes, reintentos; pruebas de cada caso.
- [x] 15. Automática por defecto para los aparatos propios y los chats compartidos.
- [x] 16. Prioridades: 1 tareas, 2 chats compartidos, 3 archivos, 4 lienzos/notas/textos, 5 vídeos
      y grabaciones; dentro, lo más ligero primero; una grabación cuenta por su peso total.
- [x] 17. Chats compartidos: editar lo de otra persona crea tu versión vinculada (como una
      respuesta); nunca se mezclan versiones.
- [x] 18. Guía para Windows con todo lo nuevo de la sincronización
      (`docs/guia-sincronizacion-para-windows.md`, `docs/sincronizacion-por-diario.md`,
      `docs/versiones-en-chats-compartidos.md`).

### Figuras
- [x] 19. Miniaturas en modo día por defecto.
- [x] 20. Interruptor ☀/☾ para plantillas de día o de noche (las gemelas «(noche)» del PC).
- [x] 21. Al tocar una figura, vista previa a pantalla completa (zoom, mover) con «Usar».
- [x] 22. Modo noche bien diseñado (no invertir blanco↔negro), como el PC.

### Visores
- [x] 23. La barra lateral se esconde y aparece junto con las de arriba y abajo, en todos (menos el
      pin flotante de imagen, que es otra ventana).
- [x] 24. Notas Markdown con la UI de los demás visores (pantalla completa, pastilla, riel, barras
      que se esconden), siempre en modo ver, sin «Añadir a un proyecto».
- [x] 25. Estudiar la clase, nueva disposición:
      - sin el porcentaje de zoom; sin la barra de arriba «Estudiar la clase»;
      - miniaturas de capturas con su proporción real (el alto es el tope, varía el ancho), más
        pequeñas;
      - botón de pantalla completa / reducir en la esquina, como YouTube, para ver la imagen
        grande;
      - vertical: imagen arriba grande; abajo la barra de tiempo, las miniaturas y play/pausa/
        marcador compactos; la transcripción se puede ocultar o mostrar;
      - horizontal: imagen grande y, en el lateral (derecho), los mandos en vertical y las
        miniaturas con scroll vertical; la transcripción oculta o al costado si se pide.

### Tareas
- [x] 26. Símbolo de «compartido» en el widget de tareas (y en Tarjetas, Kanban, Horario) para las
      tareas de chats compartidos.

### Cuadernos, compartir y música
- [x] 27. Cuadernos (carpeta de páginas): reordenar arrastrando (mantener y jalar arriba/abajo)
      desde el asa del lateral, en vez de tocar las flechas una a una.
- [x] 28. Compartir igual en toda la app: siempre las mismas opciones (imagen, PDF, HTML, PixPin,
      etc. según lo que se comparte), claras.
- [x] 29. Música: guardar canciones con su letra adjunta para cantarlas cuando se quiera
      (sincronizable con el PC; guía para Windows).
- [x] 30. Todo audio (música, notas de voz, grabaciones) se puede escuchar por el altavoz de
      llamadas (el del oído), además del altavoz normal y los audífonos.

### Abrir archivos de fuera (como el «espacio» del PC)
- [x] 31. Abrir cualquier archivo de un formato aceptado (PDF, Word, EPUB, HTML, Excel, imágenes,
      planos, 3D, Markdown…) desde otra app («Abrir con PixPin») directo en su visor, SIN guardarlo
      en Mensajes guardados ni en ningún sitio: entrar, ver y salir.
      - Con el visor completo: riel con lápiz (editor avanzado), marcos, imprimir, marcadores;
        arriba día/noche y las funciones de cada formato; editar y compartir como siempre.
      - Solo en este modo, dos botones nuevos: «Abrir en otra app» y «Guardar» (en Mensajes
        guardados o en un chat), sin preguntar cada vez al salir.
      - «Abrir con…» bien visible, para pasar rápido a una app especializada.
      - Botón «Compartir» ahí mismo: todos los formatos de PixPin y «Por Wi‑Fi a mis aparatos».
      - Compartir a PixPin desde otra app: sin el menú de «a un proyecto / abrir en PixPin / nuevo
        proyecto / Wi‑Fi» para los formatos conocidos (se abren directo en este modo); solo para
        formatos desconocidos, varios archivos o texto, un menú corto con lo esencial.

### Voz
- [x] 32. Whisper «base» por defecto, como el PC (161 MB de descarga; respuesta del usuario del 27-oct).

### Lectura Veloz (repo `1xmanMAX/Speed-Reading-Tool`)
- [x] 33. Opción «Lectura veloz» en los visores (PDF, Word, EPUB, HTML, Markdown…): abre la app
      directo con el archivo cargado, sin exportar, como «Escanear» abre el escáner.
      → Hecho (27-oct): menú del mensaje, engranaje de los visores y ⋮ de la nota; falta probarlo con las dos apps instaladas.
- [x] 34. El marcador de lectura en voz alta se comparte en los dos sentidos y de forma continua:
      Lectura Veloz arranca en el punto de PixPin y, mientras lee, PixPin va actualizando su marcador.
      → Hecho: su aviso mueve el `.voz` de siempre (y el 🟢 de un PDF); gana el más reciente. Falta en el PC (`pixpinmax://lectura-veloz`).
- [x] 35. Mención discreta de que el archivo se está leyendo («Leyendo en Lectura veloz · N %»),
      como la chapa «Escaneado»; quizá una lista «Leyendo».
      → Hecho: chapa «Lectura veloz · N %» en la burbuja y el % junto a la opción del visor; sin lista «Leyendo».
- [x] 36. Completar la integración según el manual de Android que deje Lectura Veloz. Se revisa su
      repo cada 30 min hasta que aparezcan el manual y el código; luego se deja de revisar.
      → Hecho con su código (commit cf7474f); el contrato real está en `docs/integracion-lectura-veloz.md`. Ya no hace falta revisar su repo.

## Tanda del 27-oct (2)

- [~] 37. Abrir archivos Markdown.
      → Hecho, falta probar en el teléfono: un `.md` del chat iba a otra app (no tenía rama); ahora abre en la nota, y de fuera se lee por su ruta (el texto entero en el intento no cabía).
- [~] 38. Formatos de texto como el PC (txt, …): `md`, `markdown` (compuestos) y `txt`, `log`, `ini`, `cfg`, `srt`, `vtt` (tal cual).
      → Hecho, falta probar en el teléfono: la lista del visor y la familia «Texto» del PC; desde el chat (solo mirar y anotar) y desde fuera (de paso).
- [~] 39. Lectura Veloz no abría el archivo. → Causa: Lectura Veloz pedía su comando nativo como
      `plugin:lv-tts|copyUri` y Tauri 2 lo rechaza por permisos (solo deja `copy_uri`); arreglado en
      Lectura Veloz 0.3.1 (en su `main`). PixPin ahora dice el motivo y lo apunta (`PixPinLecturaVeloz`).

## Tanda del 27-oct (3)

- [~] 40. Tinta blanca del plano (PC, modo noche) llega negra al teléfono.
      → Causa: la capa del plano no trae papel y el teléfono adaptaba la tinta contra el blanco de serie (el blanco, un gris, se daba la vuelta a negro); ahora se pinta contra el papel del plano y solo se adapta de noche, como el PC (`TintaDelPlano`). Falta verlo en el teléfono.
- [~] 41. Una capa del plano hecha solo en el PC no aparecía en el teléfono. → El PC escribe los puntos
      como pares `[x, y]` y el teléfono solo leía `{x, y}`: ahora lee los dos (y escribe objetos).

## Tanda del 28-oct

- [~] 43. Reunión presencial: grabar en segundo plano con Foto y Clip, todo en su minuto, a resolución completa.
      → Hecho, falta probar en el teléfono: modo «Presencial» en Grabar clase (vídeo de la sala o solo audio), notificación «Grabando…» con Foto/Clip/Terminar y burbuja, fotos y clips con `fuente: "camara"` y `clips` como el PC; ver `docs/reunion-presencial-android.md`.
