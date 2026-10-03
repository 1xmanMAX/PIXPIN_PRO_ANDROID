# Lecciones aprendidas: estudio y diseño (3-oct-2026)

Pedido del usuario: una función de lecciones aprendidas con **acceso súper fácil**, **llenado
rapidísimo**, **gran capacidad de búsqueda**, **etiquetado automático** y todo lo que ayude a
**no volver a cometer errores parecidos**. Antes de escribir código se estudió cómo se hace
fuera (ingeniería, construcción, aviación, medicina, estudiantes) y cómo guarda PixPin lo suyo.

## 1. Cómo se hace fuera y por qué suele fallar

| Práctica | Qué registra | Fuente |
|---|---|---|
| NASA LLIS | Suceso que la originó, lección, recomendación y **prueba de que no se repitió** | https://llis.nasa.gov/lesson/6456 |
| Revisión después de la acción (ejército de EE. UU.) | ¿Qué debía pasar? ¿Qué pasó? ¿Por qué la diferencia? ¿Qué mantener o cambiar? | https://fs-prod-nwcg.s3.us-gov-west-1.amazonaws.com/s3fs-public/2023-06/army-seizing-chance-to-learn.pdf |
| PMI, registro de lecciones | Problema, solución, impacto, consejo; categoría y palabras clave | https://pmstudycircle.com/lessons-learned-register/ |
| ASRS (aviación) | Cadena de sucesos, factores humanos, causa y cómo evitar que se repita | https://akama.arc.nasa.gov/asrs_ers/general.html |
| CII (construcción) | Se recoge **cuando ocurre** y se aplica cambiando procesos | https://www.construction-institute.org/effective-management-practices-and-technologies-for-lessons-learned-programs |

**Por qué fracasan:**
- **Nadie las consulta.** La GAO (2002) vio que los jefes de proyecto de la NASA no buscaban ni
  aportaban a LLIS. https://www.gao.gov/products/gao-02-195
- **Cuesta rellenarlas y «no traen nada para mí».** La auditoría de 2012: el sistema es viejo y
  no devuelve nada relevante. https://www.oversight.gov/reports/audit/review-nasas-lessons-learned-information-system
- **La norma existe pero no se sigue.** El 62 % de las organizaciones tiene procedimiento y solo
  el 11,7 % lo sigue de cerca (Williams, 2008). https://hull-repository.worktribe.com/output/417989/how-do-organizations-learn-lessons-from-projects-and-do-they
- **Apuntada no es aprendida.** Si la misma lección se apunta una y otra vez, no se aprendió.
  http://www.nickmilton.com/2023/01/the-11-steps-of-closed-lesson-learning.html
- El «5 porqués» lleva a una sola causa: sirve como pista, no como verdad. https://qualitysafety.bmj.com/content/26/8/671

**Qué funciona:**
- Convertir la lección en **lista de comprobación**. La lista de cirugía de la OMS bajó la
  mortalidad del 1,5 % al 0,8 %. https://www.nejm.org/doi/full/10.1056/NEJMsa0810119
- Escribir lo que se hará como **«si pasa X, haré Y»** (meta-análisis, d = 0,65). https://cancercontrol.cancer.gov/sites/default/files/2020-06/goal_intent_attain.pdf
- **Que la lección salga cuando hace falta**, sin tener que acordarse de buscarla.
- **Repaso espaciado** (Cepeda, 2006) y **recordar en vez de releer**: 61 % frente a 40 % a la
  semana (Roediger y Karpicke, 2006). https://augmentingcognition.com/assets/Cepeda2006.pdf
- Los **cuadernos de errores** de estudiantes mejoran exámenes, sobre todo si se clasifica la
  causa. https://journals.sagepub.com/doi/10.1177/2158244020931938

## 2. Cómo guarda PixPin lo suyo, y dónde encaja la lección

- El chat es `guardados.jsonl`: un mensaje por línea. Los adjuntos van en `guardados/`.
- **La sincronización va por chats**: cada chat (el general o el de un proyecto) lleva sus mensajes
  y los archivos que esos mensajes nombran (`sincro/Disco.alcance`). Solo se escribe dentro de
  unas carpetas fijas (`guardados/`, `pins/`, `proyectos/`, `tablas/`…).
- Borrar un mensaje deja su **lápida**, para que no vuelva desde otro aparato.
- La app de Windows sincroniza con el mismo protocolo.

**Se descartó:**
- Un archivo de lecciones aparte con su propio canal de sincronización: hay que tocar el
  protocolo y la app de Windows, y una versión vieja rechazaría la carpeta nueva.
- Un campo nuevo dentro del mensaje: las versiones viejas lo borrarían al reescribir el chat.
- Un tipo de mensaje nuevo (`Clase.LECCION`): un valor de enum desconocido rompe la lectura en
  las versiones viejas y en Windows.

**Se eligió:** cada lección es un archivo `guardados/lecciones/<id>.leccion` (JSON) y un mensaje
del chat de tipo archivo que lo señala, con un resumen legible en su texto. Así viaja con la
sincronización tal cual, se borra con lápida, entra en copias, en el `.pixpin` y en el buscador
del teléfono, y una lección de un proyecto sale en su chat (decisión del usuario). Windows la ve
como un adjunto más. El archivo es la verdad; el mensaje, su escaparate.

## 3. Qué se hizo, punto por punto

| Necesidad | Cómo se resuelve | Dónde |
|---|---|---|
| Acceso fácil | Botón de la bola (encima de cualquier app), atajos «Nueva lección» y «Lecciones» en el icono y el buscador, «Hacer lección» en el menú de cualquier mensaje, «Compartir → Lección», menú del chat | `LeccionActivity`, `shortcuts.xml`, `FloatingBallController`, `MensajesActivity` |
| Llenado rápido | Solo «qué aprendiste» es obligatorio; se dicta de corrido y se reparte en qué pasó / por qué / la próxima vez; tocar fuera guarda | `Dictado`, `LeccionActivity` |
| Etiquetas automáticas | Diccionario de conceptos (obra, estudio, trabajo, vida), lo que uno suele etiquetar, `#etiquetas` y «etiqueta X» dichas; también área, tipo y causas | `Etiquetador` |
| Buscar | Sin acentos ni plurales, con erratas, por peso de campo, por concepto («obra» encuentra el encofrado), palabras de referencia ocultas, por voz | `Texto`, `Buscador` |
| No repetir | Aviso de «se parece a una que ya tienes → pasó otra vez», contador de repeticiones que sube gravedad y orden, repaso espaciado con recuerdo activo, lista de comprobación de «la próxima vez», aviso en el chat del proyecto, relacionadas | `Repaso`, `Buscador.parecidas`, `LeccionesActivity`, `AvisoDeLecciones` |
| Buscador del teléfono | Las lecciones son archivos del chat: entran en el índice del sistema y en los atajos | `atajos/` |

## 4. Pendiente (fases siguientes)

- Bloques ricos dentro de la ficha (mini lienzo, tabla, foto, nota de voz con audio).
- Aviso al abrir un PDF o un lienzo, además del chat del proyecto.
- Recordatorio del repaso como notificación diaria.
- Exportar a página web con buscador.
- La guía con IA (solo recibe las 10–20 lecciones más parecidas; las privadas no salen).
- Lecciones en la app de Windows (hoy las ve como adjuntos `.leccion`).
- Tareas, en la segunda pestaña del Cuaderno.
