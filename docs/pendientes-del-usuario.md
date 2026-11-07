# Pedidos del usuario: checklist

Todo lo que el usuario pidió desde el 26-oct-2026, para no perder nada. Se marca `[x]` cuando está
hecho **y publicado** en un APK; `[~]` cuando está hecho pero aún sin publicar; `[ ]` pendiente.
Al cerrar cada tanda se revisa esta lista punto por punto.

## Publicado

- [x] v0.143.0 — Grabaciones y carpetas: se ven y se sincronizan; tarjetas e iconos como el PC.
- [x] v0.144.0 — OCR con Indra en el teléfono; Whisper como motor de voz por defecto.
- [x] v0.145.0 — Fuera la pantalla de Proyectos; atrás: visor → chat → lista → salir.
- [x] v0.147.0 — Markdown y formatos de texto; Lectura Veloz 0.3.1 abre los archivos (puntos 37–39).
- [x] v0.151.0 — Temas de la reunión con Jev, dictar al chat, repasar en voz alta y la letra como el
      PC (puntos 59–61).
- [x] v0.150.0 — Lo nuevo del PC del 28-oct: grabar sin perder nada, baliza, fotos por su hora,
      de la PC al móvil y colaboradores, Jev y la web del asistente (puntos 53–58).
- [x] v0.149.0 — Sin ⚙ en los visores, menú por tipo, una pantalla por clase, transcripción
      siempre, letra con tiempos, cuadernos con todos los formatos (puntos 44–52).
- [x] v0.148.0 — Tinta del plano, reunión presencial y el móvil como cámara del PC (puntos 40–43).
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

- [x] 37. Abrir archivos Markdown.
      → Hecho, falta probar en el teléfono: un `.md` del chat iba a otra app (no tenía rama); ahora abre en la nota, y de fuera se lee por su ruta (el texto entero en el intento no cabía).
- [x] 38. Formatos de texto como el PC (txt, …): `md`, `markdown` (compuestos) y `txt`, `log`, `ini`, `cfg`, `srt`, `vtt` (tal cual).
      → Hecho, falta probar en el teléfono: la lista del visor y la familia «Texto» del PC; desde el chat (solo mirar y anotar) y desde fuera (de paso).
- [x] 39. Lectura Veloz no abría el archivo. → Causa: Lectura Veloz pedía su comando nativo como
      `plugin:lv-tts|copyUri` y Tauri 2 lo rechaza por permisos (solo deja `copy_uri`); arreglado en
      Lectura Veloz 0.3.1 (en su `main`). PixPin ahora dice el motivo y lo apunta (`PixPinLecturaVeloz`).

## Tanda del 27-oct (3)

- [x] 40. Tinta blanca del plano (PC, modo noche) llega negra al teléfono.
      → Causa: la capa del plano no trae papel y el teléfono adaptaba la tinta contra el blanco de serie (el blanco, un gris, se daba la vuelta a negro); ahora se pinta contra el papel del plano y solo se adapta de noche, como el PC (`TintaDelPlano`). Falta verlo en el teléfono.
- [x] 41. Una capa del plano hecha solo en el PC no aparecía en el teléfono. → El PC escribe los puntos
      como pares `[x, y]` y el teléfono solo leía `{x, y}`: ahora lee los dos (y escribe objetos).

## Tanda del 28-oct

- [x] 42. El móvil como cámara de la reunión que graba el PC (fotos y clips en su minuto).
      → Hecho y probado contra el PC de verdad (`CamaraDelPcTest`, `docs/movil-como-camara-android.md`); falta probarlo con el PC y el teléfono en la misma Wi-Fi (la cámara, los clips y la subida en segundo plano).
- [x] 43. Reunión presencial: grabar en segundo plano con Foto y Clip, todo en su minuto, a resolución completa.
      → Hecho, falta probar en el teléfono: modo «Presencial» en Grabar clase (vídeo de la sala o solo audio), notificación «Grabando…» con Foto/Clip/Terminar y burbuja, fotos y clips con `fuente: "camara"` y `clips` como el PC; ver `docs/reunion-presencial-android.md`.

## Tanda del 28-oct (2)

### Lectura veloz y el menú del mensaje
- [x] 44. Quitar el engranaje ⚙ de los visores (PDF, Word…): sus opciones repetían las de la pantalla
      (bloquear desplazamiento, voz…). Lectura veloz se abre solo desde el menú del mensaje.
- [x] 45. Menú al mantener pulsado: solo lo que tiene sentido para cada tipo (archivo, texto, audio,
      imagen…); fuera lo absurdo (p. ej. recordatorio o llamada falsa en un PDF).

### Grabaciones, música y Estudiar la clase
- [x] 46. Una sola interfaz para una misma clase grabada (hoy hay dos parecidas pero distintas).
- [x] 47. Toda grabación se transcribe siempre (también la reunión presencial), con marcas de tiempo
      para saltar entre ellas.
- [x] 48. Música: transcribirla y fusionar la letra pegada (la buena) con la transcripción (los
      tiempos) en una letra con marcas de tiempo.
- [x] 49. Estudiar la clase a pantalla completa: su color sube hasta la cámara, sin franja blanca.
- [x] 52. Reunión presencial: se nota al tomar una foto o un clip, y siempre hay un botón visible
      para terminar (notificación, burbuja y una barra dentro de PixPin).
- [x] 50. La pantalla de la música con su letra, unificada con Estudiar la clase.

### Compartir
- [x] 51. Compartir un cuaderno con todos los formatos (PDF, HTML, PixPin, fotos…), con la misma hoja
      de compartir de siempre.

## Tanda del 28-oct (3): lo nuevo del PC (origin/main 995fd94b, 49 commits)

- [x] 53. Grabar sin perder nada (`docs/grabar-sin-perder-nada.md` del PC, §Android): respaldo WAV de
      la nota, vídeo en trozos, diario de la grabación en disco, «Se cortó una grabación —
      Recuperar / Descartar», guarda de espacio, huecos al suspender, auriculares que se van.
- [x] 54. Descubrir rápido y prioridad al grabar (`movil-como-camara.md` §11): baliza UDP firmada,
      multidifusión, barrido, conexión caliente para las fotos, no sincronizar mientras alguien graba.
- [x] 55. Fotos por su hora (§12): `resto.reloj` en la nota, `tomadaEn`/EXIF en cada foto, la regla de
      dónde va cada foto, `desfases.json`, la cámara en directo sin saber la grabación.
- [x] 56. De la PC a la grabación del móvil (§13: `camara_remota` con `suya`) y colaboradores (§14).
- [x] 57. Jev (`docs/jev.md`): lo importante de una clase (puntos de color, filtro, «+N tareas») en
      Estudiar la clase, la letra y la página compartida; con clave propia, preguntar a Jev igual que el PC
      (importantes, tarjetas de Anki que valen la pena, urgencia de las tareas, elegir el modelo);
      interruptores en Ajustes › Asistente.
- [x] 58. El asistente en la web (`manual-asistente-android.md` §2.6): buscar y leer páginas, con las
      mismas reglas de seguridad que el PC.

## Revisar Windows cada 30 min (pedido del 29-oct)

Se revisan todas las ramas del repo de Windows cada media hora
(`scratchpad/vigia/revisar-windows.sh`, base en `visto.txt`) y todo lo nuevo se porta a Android,
sincronizable. Primera tanda: `origin/main` 50e87978 (70 commits desde 995fd94b).

- [x] 59. Temas de la reunión y lo demás que oye Jev (jev.md §5 y §8): agenda que Jev marca en vivo,
      decisiones, preguntas abiertas, tiempo por tema, propuestas, fichas en la barra y el acta.
- [x] 60. Dictar al chat (§6) y repasar en voz alta (§7) con Jev.
- [x] 61. La letra de las canciones como el PC: «Sincronizar sola», idioma de la letra, el alineador
      igual al del PC, karaoke sin tira y moverse por la letra.

## La barra de grabación y una sola grabación (pedido del 29-oct)

- [x] 62. La barra flotante sin amontonar: el reloj pausa con un toque (el punto rojo pasa a pausa) y
      termina si se mantiene; Foto, Captura, Pantalla y Clip en su fila, cada uno con su nombre.
- [x] 63. Una sola opción de grabar para la clase en línea, la presencial y la reunión: el audio siempre
      y, en la barra, Foto, Captura (mantener: un trozo) y Pantalla (el vídeo de la pantalla, que se
      enciende y se apaga mientras el audio sigue). El vídeo de la sala queda en «Más opciones».
- [x] 64. La pantalla de «Grabar» sin palabras apretadas (ya no hay tres modos en una fila).
- [x] 65. Pantalla completa de la grabación: arriba los mandos, en medio la lista de lo que voy a decir
      y abajo la transcripción en vivo, con A−/A+ para verlo grande.
- [x] 66. El botón de la foto para la grabación del PC junto al «+» de la pantalla de los chats (solo
      mientras el PC graba), y fuera de «Adjuntar».

## Preparar Google Play (pedido del 30-oct)

- [x] 67. La versión «play» (AAB con la clave de subida, `bundlePlay`) sin los permisos que Play no deja;
      librerías nativas alineadas a 16 KB; target API 36. Ver `docs/google-play.md`.
- [x] 68. Recordatorios sin alarma exacta concedida, APK compartido en vez de instalado, llamada secreta
      sin pantalla completa, voces de Edge fuera de Play y secretos fuera de las copias de Google.
- [x] 69. «Privacidad y datos» en Ajustes (política, qué sale del teléfono, borrar todos mis datos) y
      «Reportar» en todo lo que escribe la IA.
- [x] 70. El andamio de los planes de pago (`planes/`) y el plan de monetización, cuentas y servidores
      (`docs/monetizacion-y-cuentas.md`).
- [ ] 71. Lo que hace el usuario: cuenta de desarrollador, la clave de subida, publicar la política,
      la prueba cerrada y las declaraciones de la Play Console (ver `docs/google-play.md`).
- [x] 72. Dos ramas principales: `main` (código general, sin restricciones de Play; el APK de GitHub)
      y `play-store` (`main` + lo que pide Google Play; el AAB). Ver `docs/ramas.md`.

## Todo lo nuevo de Windows, en orden y con un solo agente (pedido del 2-nov, publicado en v0.157.0)

- [x] 73. Tareas quietas: lo tachado se queda en su sitio y el orden no salta mientras se mira.
- [x] 74. Exportar las listas de tareas (página, PDF, imagen, Word, Markdown, texto) en 14 idiomas.
- [x] 75. Cada asignatura del horario con su lista de tareas (`lista:<id>`), renombrar y elegir destino.
- [x] 76. Marcas de texto (resaltar, ondulada, subrayar, tachar, tapar) en notas, páginas web, Word,
      libros, PDF, Office y hojas de cálculo; sincronizables con el PC.
- [x] 77. Color avanzado del lienzo: código, contraste, opacidad, recientes, cuentagotas y el último
      trazo a mano recordado.
- [x] 78. Gestos del trackpad en lo exportado y «Pegar lo copiado» en la hoja del clip.
- [x] 79. La nube de los chats compartidos (deltas: solo sube lo que cambia) con «En línea / Sin
      conexión» en los visores. Ver `docs/compatibilidad-pc-2-nov.md`.

## Figuras y Adjuntar como en el iPhone (pedido del 2-nov, publicado en v0.158.0)

- [x] 80. Las figuras de día se veían negras en el panel (la hoja blanca se «adaptaba» a negro): ahora se
      ven como quedan al ponerlas.
- [x] 81. El panel de Figuras como el del iPhone: «Cerrar», el título con su cuenta, el botón redondo de
      día y noche (gira al cambiar), «Todas» con secciones y su cuenta, tarjetas y el buscador abajo.
- [x] 82. La hoja «Adjuntar» como la del iPhone: «Cerrar» y el título, iconos cuadrados redondeados,
      «Más» en dos columnas de pastillas y «Pegar lo copiado» con su botón «Pegar».

## Pedidos del 3-nov (publicados en v0.159.0)

- [x] 83. Una nota con enlace y fotos: tocar una foto la abre (antes se abría el enlace).
- [x] 84. Abrir una foto sin esperar: se decodifica en segundo plano y se pinta al tenerla.
- [x] 85. Compartir una nota o un Markdown con la hoja de compartir de la app, no directo con Android.
- [x] 86. Aspecto de fábrica: día y noche según el teléfono (el cosmos, solo si se elige); las notas
      Markdown nunca con el fondo galáctico.
- [x] 87. Sincronizar en segundo plano con los demás aparatos y los chats compartidos (servicio con su
      aviso; se apaga en Sincronizar).


## Nuevas del PC y tarjetas de abajo (3-nov, publicadas en v0.160.0)

- [x] 88. Emparejar sin la dirección, como el PC: el código del grupo con su QR (`pindesk://emparejar?…`,
      10 minutos) que lee cualquier cámara; escribir el código busca al PC por mDNS y por la baliza.
- [x] 89. Figuras en una tarjeta que sube desde abajo: la barrita de arriba, bajada, la cierra.
- [x] 90. La vista previa de una figura, dentro de esa misma tarjeta (no en otra ventana) y, al
      acercarla, recortada a su recuadro.
- [x] 91. La galería de capturas, también en una tarjeta que sube desde abajo y se cierra bajándola.
- [x] 94. Mirando, moverse esconde el riel y la barra de arriba en todos los visores: ahora también al
      pellizcar o llevar de lado un PDF/Office y al pellizcar una página web.
- [~] 92. Comentario avanzado (pegatinas con minichat, `docs/comentarios.md` del PC): **hecho en los planos**
      (v0.161.0, ver 96); faltan el lienzo, el PDF/Office, el lector de Word y las hojas.
- [ ] 93. Parámetros de las plantillas de figuras (desde/hasta, por hoja, semanas, mes, año): pendiente;
      necesita el generador de plantillas del PC.

## Pedidos del 4-nov

- [x] 95. Jev no repite la revisión de urgencia: lo dicho de cada tarea se guarda en
      `anot-<uid>.urgencia.json` de su lista y viaja; lo nacido en el teléfono se revisa al momento.
      El PC tiene que leer y escribir el mismo fichero (`docs/urgencia-que-viaja.md`).
- [x] 96. Comentarios en los planos con el emoji: botón abajo a la derecha (con las abiertas en rojo), los
      cuatro tipos (🔴 urgente, 🟠 alta, 🟡 media, 💬 nota), un toque pega la pegatina, mantener y arrastrar
      la mueve, y su minichat (escribir, adjuntar archivos, resolver, cambiar tipo, crear tarea en el Inbox,
      quitar) y la lista «Comentarios (n)». Mismo fichero y misma fusión que el PC: viajan y se juntan.
- [x] 97. Todo lo nuevo sincroniza (v0.162.0): lo anotado con el código de **cualquier** mensaje viaja (antes
      solo el de un archivo o una nota, y la urgencia de una lista de tareas se quedaba); los comentarios
      y la urgencia cambiados en los dos aparatos se juntan y la versión juntada vuelve al otro, para que
      queden iguales. Probado de punta a punta entre dos aparatos (`SincronizarDeVerdadTest`).

## Pedidos del 5-nov (v0.163.0)

- [x] 98. Medir en los planos: la cadena que se está poniendo va pegada al plano (en su mismo fotograma, como
      las cotas hechas); antes se movía con retraso.
- [x] 99. Polilínea de medidas cerrada (soltar cerca de su primer punto la cierra): su zona resaltada y el
      área en el medio (con el perímetro debajo).
- [x] 100. El clip del minichat de los comentarios, como el del chat pero recortado: cámara y galería (fotos y
      vídeos), archivo, lienzo y lista de tareas (casillas que se tachan); las fotos en miniatura (un toque:
      anotarla en el lienzo) y los archivos compactos con el icono de su extensión y su nombre.
- [x] 101. «Abrir con otra app» decía que el documento ya no estaba: el permiso de leerlo no llegaba a la app
      elegida (faltaba en el `ClipData` del selector).
- [x] 102. «Cámara» en el clip del chat (v0.164.0): hace la foto con la cámara del teléfono y entra sola en el
      cuadro de enviar, con su descripción, como las elegidas.
- [x] 103. La cámara de los comentarios no hacía nada: PixPin declara el permiso de la cámara y entonces
      Android no abre la del sistema sin él concedido. Ahora se pide antes (en el chat y en los comentarios).

## Pedidos del 5-nov, tarde

- [x] 104. La herramienta de texto (lápiz → texto) no dejaba escribir en las notas Markdown, Excel, 3D ni la
      clase: el cuadro donde se teclea lo pone ahora el editor de todos los visores.
- [x] 105. Compartir una nota anotada: «PDF con anotaciones», la nota entera como se ve con lo dibujado.
- [x] 106. Excel: fuera la barra de abajo de la celda («Copiar») y el porcentaje del zoom.
- [x] 107. La fecha flotante del chat salía debajo de la cabecera y con el día de un mensaje tapado.
- [x] 108. La cámara salía dos veces en el minichat de los comentarios: solo en el clip.
- [x] 109. Atrás vuelve a la pantalla que abrió cada editor (nota → lienzo → atrás vuelve a la nota), aunque
      cada uno viva en su tarea.
- [x] 110. La ficha del chat «Asistente», como sus tres puntos en el PC: el modelo (Claude o DeepSeek) con el
      elegido marcado, conversación nueva, la clave y sus ajustes, fotos y archivos, y borrar el chat.
- [x] 111. El visor de Markdown con espacios a los costados para anotar (el `.espacios` de la nota, como el
      del PDF, que viaja): el texto se estrecha y la tinta sigue unida a él.
- [x] 112. La barra de arriba de los visores, siempre debajo de la cámara (con la barra de estado escondida su
      hueco era cero y quedaba encima del agujero de la cámara).
- [x] 113. Nota mirando: mantener el dedo en el texto pasa a marcarlo (elegir y marcar con la barrita).
- [x] 114. Lienzo mirando: sin la lupa al tocar o acercar.
- [x] 115. El texto del lápiz en los visores (notas, HTML, PDF, Excel, 3D, clase) se escribe en su sitio, como en el
      lienzo, sin la ventanita; en el PDF, sobre la hoja y a su tamaño.
- [x] 116. Lo escrito aparece al momento (la hoja del PDF, el plano y la clase no se repintaban hasta moverlos).
- [x] 117. La caja de texto en los visores: el alto, el de su texto (estirarla hacia abajo no deja hueco); a lo
      ancho se estira y el texto se reparte (como en el lienzo: `medirTextosCon`).
- [x] 118. El texto rico mientras se escribe se ve ya compuesto (negrita, cursiva, color, tamaño, tachado,
      subrayado), sin las marcas (`**`, `\color{}{}`); la fuente es la misma de siempre y viaja igual al PC.
- [x] 119. La barra del texto rico cabe en la pantalla (se desliza) y lleva tres colores (rojo, azul, verde)
      más los recientes del lienzo.
- [x] 120. Sincronizar de fondo con cabeza (`RedDelTelefono`): con Wi-Fi, lo local arranca solo al conectarse y
      sondea cada 2 min (lo que avise antes —la baliza, un cambio— despierta al momento); con datos móviles, solo
      la nube; sin red, nada (se espera a que Android la devuelva, sin reintentar).
- [x] 121. Sincronizar la letra de una canción, en su propia app: **LetraSync Pro** (`com.letrasyncpro`, repositorio
      `letrasync-pro-android`), integrada como Escáner Pro Max: el botón de sincronizar sola del karaoke la abre con
      la canción y su letra, y la letra con tiempos vuelve sola (`guardados/LetraSyncPro.kt`). Sin ella instalada,
      lo de siempre.
- [ ] 122. Los chats «pr-17…» (mensajes de un chat cuyo nombre no está en este teléfono): falta saber de dónde vienen
      para esconderlos (uno por documento si son de comentarios).
- [x] 123. Fase de prueba: el asistente funciona nada más instalar con la clave de prueba que GitHub mete al compilar
      (secretos `CLAVE_CLAUDE_DE_PRUEBA` / `CLAVE_DEEPSEEK_DE_PRUEBA`; ver `ClavesDeLaNube.deFabrica`). La del usuario
      manda. **Para quitarla: borrar el secreto y volver a compilar.**
- [x] 124. Acceso de prueba al asistente con Google (v0.170.0): el servidor (`servidor/ia-de-prueba`, Cloudflare Worker)
      guarda la clave de Claude y la lista de correos; la app entra con Google y pregunta por él. Para Windows:
      `docs/acceso-de-prueba-ia.md`. **Falta (del usuario): crear la cuenta de Cloudflare y los ID de Google**
      (pasos en `servidor/ia-de-prueba/README.md`).
- [x] 125. (v0.171.0) PinDesk ya no alinea letras: «Sincronizar» / «Vincular letra» de una canción la manda a
      **LetraSync Pro** con su letra, y la letra con tiempos vuelve a PinDesk al terminar. Sin LetraSync instalada,
      un aviso con el enlace para descargarla. Las canciones ya no entran en la cola de «pasar a texto».
- [x] 126. (v0.171.0) El asistente contesta en cualquier chat «Asistente» (si había dos, el segundo no respondía) y un
      turno atascado más de 4 min se suelta solo, para que el siguiente mensaje sí tenga respuesta.
- [x] 127. (v0.172.0) Una sola entrada a la letra de una canción: **«Sincronizar la letra»** (abre LetraSync Pro, donde
      se escribe, pega y sincroniza). En el chat, al mantener la canción; en el karaoke, un solo botón en la
      cabecera (fuera el reloj, «sola» y el lápiz, y las tres opciones repetidas del menú ⋮). La tarjeta de música
      ya no tiene un botón aparte que haga lo mismo que tocarla. Sin LetraSync instalada, el aviso deja también
      «Escribir o pegar la letra» aquí.
