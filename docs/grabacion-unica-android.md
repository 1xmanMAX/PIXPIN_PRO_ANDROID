# Una sola grabación en Android (29-oct-2026)

El usuario: «solo debería haber una opción de grabar, no tres (presencial, pantalla, audio más vídeo); que
en la misma barra me dé la opción de grabar la pantalla o pausarla pero se siga grabando el audio; tomar
foto con la cámara; capturar la pantalla; pausar… una opción que funcione para los tres escenarios». Y:
«los iconos de la barrita están muy aglomerados; el botón de play y pausa sobra: en el tiempo, donde está
el puntito rojo, le damos clic y pausa (el punto pasa a pausa), y si lo mantengo presionado, que se
termine». Y: «una opción de pantalla completa donde vea la lista de lo que voy a decir, arriba las
opciones y abajo la transcripción, más grande».

## Lo que ve el usuario

1. **Preparar** («Grabar» en el chat): ya no hay modos. Un interruptor **«Empezar grabando la pantalla»**
   (con su calidad: Ligero, Normal, Nítido), la **burbuja flotante**, los **temas de la reunión** y, en
   **«Más opciones»**, el **vídeo de la sala** con la cámara (la reunión presencial grabada entera). Las
   opciones van en filas que se parten: nada se aprieta en vertical en una pantalla estrecha.
2. **La barra** (la burbuja encima de otras apps y la de dentro de PixPin), en dos filas con aire:
   - arriba: el asa, **el reloj «● 12:34»** (un toque pausa o sigue, y el punto rojo pasa a «❚❚»;
     **mantenerlo termina** y guarda), «☑ 2/5» si hay temas y **⛶ pantalla completa**;
   - abajo, cada uno con su nombre: **Foto** (la cámara), **Captura** (la pantalla entera al instante;
     **mantenerla** deja elegir un trozo), **Pantalla** (enciende o apaga el vídeo de la pantalla; en rojo
     mientras graba; **el audio sigue siempre**) y **Clip**.
   Grabando un clip, encima, la mirilla. Mientras se hace una captura, la barra se esconde.
3. **La notificación**: Foto, Captura y Terminar a la vista; desplegada, la pausa y Pantalla.
4. **La pantalla completa**: arriba el reloj grande, «Terminar» y Foto, Captura, Pantalla y Clip en
   grande; en medio **lo que voy a decir** (los temas, para marcarlos a mano); abajo **lo que se dice**
   (la transcripción en vivo, frase a frase con su minuto, con la clave de Jev). **A− / A+** agrandan el
   texto (se recuerda) y **Descartar** borra la grabación (con su pregunta). «Captura» desde aquí sale de
   la pantalla y captura lo de debajo. «Ver la cámara» enseña la mirilla.

## Cómo va por dentro

- Todo es `ReunionPresencialService` (micrófono, cámara y, con «Pantalla», `mediaProjection`).
  `GrabarClaseService` ya no existe; su recorte de la zona está en `ZonaDeLaCaptura`.
- **La cámara se abre al hacer falta** (la primera foto o clip, o al empezar con el vídeo de la sala):
  en una clase por pantalla no se enciende.
- **El audio va siempre aparte** (la nota con su respaldo WAV). **El vídeo de la pantalla**, sin sonido y
  en trozos de 3 minutos (`VideoDeLaClase`), en grupos desde `Presencial.GRUPO_DE_PANTALLA` (1000) para
  no juntarse con los del vídeo de la sala. Cada vez que se enciende pide el permiso de Android
  (`PermisoDePantallaActivity`, transparente): Android 14 no deja guardarlo.
- **Las capturas**: sin la pantalla grabándose, por la sesión de siempre (`CaptureFlow.conSesion`); con
  ella, se apunta el minuto y la imagen sale del fotograma del vídeo al terminar
  (`GuardarLaGrabacion.fotogramasDePartes`): el permiso da para un solo `VirtualDisplay`.
- **Al guardar** (`GuardarLaGrabacion.reunion(…, pantalla)`): si la pantalla se grabó desde el principio
  (en los primeros 5 s) y no hay vídeo de la sala, es **el vídeo** de la nota (`video: {id}`,
  `enGrabacion.ms = 0`); si no, cada trozo entra como un **clip** en su minuto, «Clase … · pantalla N.mp4».
  Las fotos llevan `fuente: "camara"`; las capturas y los trozos de la pantalla, sin `fuente` (como las
  capturas de la clase del PC). El nombre: «Clase …», o «Reunión …» con el vídeo de la sala.
- **Si algo se corta**, el diario lo recupera igual: los trozos de la pantalla se juntan aparte y sus
  capturas salen de sus fotogramas (`RecuperarGrabacion`).
- **La transcripción en vivo**: `EscuchaDeLaReunion.Vuelta.Frase` con cada frase ya oída, en
  `ReunionConJev.Estado.frases` (las 200 últimas). Solo se enseña: el texto de verdad sale al terminar.

## Compatibilidad con el PC

El formato no cambia: una nota `grabacion: "clase"` con `capturas`, `video` y `clips`, como las del PC.
Lo único nuevo es que en la misma grabación hay imágenes y clips con y sin `fuente`, que el PC ya lee
(su clase tiene capturas sin `fuente` y su reunión, fotos con `fuente: "camara"`).
