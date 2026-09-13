# Plan: sincronizar entre dispositivos sin internet ni servidores

Fecha: 9-sep-2026. Lo pidió el usuario: multiplataforma, por la red local, sin que nadie
tenga que montar ni pagar un servidor, y construido sobre el número que ya lleva cada mensaje.

> **Implementado el 13-sep-2026** (v0.34.0), salvo Windows y Wi-Fi Direct. Lo que se hizo y
> cómo probarlo está en «Cómo quedó», al final. Probado solo con dos «aparatos» simulados en la
> JVM que se hablan por un socket (`SincronizarDeVerdadTest`); **nada probado en teléfonos**.

---

## La idea en una frase

Cada dispositivo tiene una **letra**; cada mensaje, un **número**. Juntos —`5b`, `12a`— dan un
nombre único en todo el conjunto sin que nadie tenga que coordinarse. Los dispositivos que
comparten un **código secreto** forman un grupo, y dentro del grupo se pasan lo que les falta
por la red local, cuando el usuario lo pide.

## Por qué esto funciona sin servidor

El problema difícil de sincronizar sin servidor es **ponerse de acuerdo en los nombres**: si dos
aparatos crean algo a la vez, ¿quién es el 5? Con la letra por dispositivo el problema
desaparece: mi teléfono solo reparte números con `a`, la tableta solo con `t`, y **nunca pueden
chocar**. Es lo mismo que hace un contador vectorial, pero legible: `5b` se puede decir en voz
alta y buscar en el chat.

De ahí sale lo demás casi solo:
- **Qué le falta al otro** = qué señas tiene él que yo no.
- **Dónde va lo que llega** = por la hora de creación del mensaje, que ya se guarda.
- **Si es el mismo archivo** = misma seña. No hace falta comparar contenidos.

---

## Las cuatro partes

### 1 · Identidad: la letra del dispositivo y el grupo

- Al arrancar por primera vez, el dispositivo **se reparte solo** una letra libre (`a`, `b`,
  `t`…) — decidido el 9-sep-2026, ver las preguntas del final— y el usuario le pone un nombre
  legible («Teléfono de Max»), que es lo que se ve al sincronizar. La letra es de la máquina y
  **no se puede cambiar** una vez que ha repartido señas: cambiarla renombraría cosas que ya
  viajaron.
- **El grupo** es un secreto compartido. Un dispositivo lo crea y enseña un código corto; los
  demás lo teclean. De ese secreto salen (a) la prueba de que son del mismo grupo y (b) la
  clave con la que se cifra lo que viaja.
- Al emparejar se intercambian las letras y **se comprueba que no chocan**. Si dos aparatos
  eligieron la misma, el que se acaba de unir cambia (aún no ha repartido nada dentro del
  grupo).

**Qué hay ya**: el número por mensaje ([[pixpin-numero-recordatorio-monton]]). Falta la letra,
el grupo y que la seña sea `número+letra` en vez de solo número.

### 2 · Encontrarse en la red local

- **mDNS/NSD** (`android.net.nsd`), que es lo que usa Android sin permisos raros: cada
  dispositivo anuncia un servicio `_pixpin._tcp` con su letra y su nombre.
- El que quiere sincronizar ve la lista de los suyos y elige. **Nunca automático**: el usuario
  pulsa «sincronizar», como pidió.
- El transporte es **TCP dentro de la misma Wi-Fi**, que da decenas de MB/s: un proyecto con
  planos viaja en segundos. Nada sale a internet.
- Si no hay Wi-Fi común, más adelante: **Wi-Fi Direct**, que monta la red entre los dos
  aparatos sin router. Se deja para después de que funcione lo básico.

### 3 · Qué se manda, y cómo se decide

El intercambio tiene tres pasos y **cabe en una pantalla**:

1. **Se cuentan lo que tienen.** Cada uno manda su lista: para cada mensaje, su seña, su hora
   de creación, su hora de última edición, y si está borrado. Es una lista de líneas, no de
   archivos: un proyecto entero son unos kilobytes.
2. **Se calcula la diferencia.** Sale sola de comparar señas:
   - la tiene él y yo no → **me la traigo** (salvo que yo la haya borrado, ver abajo);
   - la tengo yo y él no → **se la mando**;
   - la tenemos los dos → se mira si cambió.
3. **Se pasan solo los archivos que hagan falta**, y se colocan en el chat **por su hora de
   creación**, que es lo que mantiene el orden que el usuario ya conoce.

**Cuando los dos lo tocaron** (lo que el usuario llamó «cuál conservar»):

| Situación | Qué pasa |
|---|---|
| Cambió en uno solo | **Gana el que cambió**, sin preguntar. Es el caso corriente y preguntar aquí solo molesta. |
| Cambió en los dos | **Se pregunta.** Se enseñan los dos con su hora y una vista previa, y lo que se elija se copia a los dos lados. |
| Borrado en uno, intacto en el otro | **Se pregunta**, y por omisión gana el borrado: borrar es una decisión, no un descuido. |
| Borrado en uno, cambiado en el otro | **Se pregunta siempre.** Aquí no hay respuesta obvia. |

> **Por qué se pregunta y no gana el más reciente sin más.** El usuario lo dudó en voz alta y
> acabó en preguntar, y creo que tiene razón: los relojes de dos aparatos no coinciden, y «el
> último» puede ser el que tenía el reloj adelantado. Preguntar solo pasa cuando **los dos**
> tocaron la misma cosa entre dos sincronizaciones, que es raro. El resto es automático.

**Para saber si algo cambió** no vale solo la hora: se guarda además un **resumen del
contenido** (un hash). Si los resúmenes coinciden, no cambió nada aunque las horas difieran, y
no se pregunta ni se manda nada. Esto es lo que hace que sincronizar dos veces seguidas sea
instantáneo.

**Un borrado deja rastro.** Si al borrar no queda nada, el otro dispositivo cree que es un
mensaje nuevo y **lo resucita** en la siguiente sincronización. Así que borrar deja una marca
(«esta seña está borrada, a esta hora») que también viaja.

### 4 · La versión de Windows

El motor de PixPin —dibujo, PDF como líneas, exportar a página web— es Kotlin **sin Android**:
es lo que permite que 2.252 pruebas corran en la JVM sin teléfono. Esa parte se puede compilar
para escritorio tal cual. Lo que hay que rehacer es la pantalla.

Dos caminos, y hay que elegir:

- **Compose Multiplatform** (JetBrains). El mismo Kotlin y el mismo Compose que ya está
  escrito; en el mejor caso, mucha pantalla se reaprovecha. Es la vía natural.
- **La página web que ya exportamos**, envuelta en una ventana de escritorio. Mucho menos
  trabajo, pero es un visor: no tendría los editores.

El manual de compilación para Windows va aparte, cuando se elija; escribirlo antes sería
inventarse los pasos de algo que aún no compila.

---

## En qué orden se hace

1. **La letra y la seña** (`número+letra`) en el propio dispositivo, sin red. Se puede probar
   entero en la JVM.
2. **La diferencia entre dos listas**: dadas dos listas de señas, qué se manda, qué se trae y
   qué se pregunta. Es **pura**, y es donde están todas las decisiones difíciles, así que va
   con pruebas exhaustivas antes de que exista un solo socket.
3. **El grupo y el emparejamiento** por código.
4. **Encontrarse y hablar** por la red local.
5. **La pantalla**: elegir dispositivo, ver el progreso, resolver los choques.
6. Windows, después de todo lo anterior.

Los pasos 1 y 2 son la mitad del trabajo y **no necesitan red ni segundo aparato**, que es lo
que aquí se puede comprobar de verdad. Del 3 en adelante hace falta probar con dos aparatos, y
eso solo lo puede hacer el usuario.

---

## Preguntas abiertas

1. ~~**La letra, ¿la elige el usuario o se reparte sola?**~~ **Decidido (9-sep-2026): se
   reparte sola.** El usuario le pone un **nombre** al aparato («Teléfono de Max»), que es lo
   que se ve en la lista al sincronizar; la letra es de la máquina y no se enseña salvo dentro
   de la seña (`5b`). Elegirla a mano era bonito —«l» de laptop— pero se agota, choca entre
   aparatos, y obligaría a resolver ese choque justo en el momento de emparejar, que es cuando
   menos ganas hay de leer un aviso.
2. ~~**¿Sincroniza todo o por proyectos?**~~ **Decidido (13-sep-2026): por proyectos.** La
   primera vez con cada aparato se marcan cuáles viajan; así la tableta no se trae los 300 MB
   de planos del trabajo. La base (lo acordado la última vez) se guarda por aparato y proyecto.
3. **¿Hace falta que sea segura de verdad?** Cifrar lo que viaja con la clave del grupo cuesta
   poco y evita que alguien en la misma Wi-Fi lea los planos. Doy por hecho que sí.
4. ~~**El «5b» del mensaje del usuario**~~ **Confirmado (13-sep-2026):** la seña **no cambia**
   al viajar; `5b` es `5b` en los tres aparatos.
5. **Decidido (13-sep-2026): siempre en los dos sentidos.** Aunque el usuario diga «de la
   tablet a la laptop», los dos quedan iguales. No hay sincronización de ida sola.
6. **Decidido (13-sep-2026): borrado contra intacto se pregunta**, con «borrar en los dos»
   marcado por omisión (lo que ya hace `Diferencia`).
7. **Decidido (13-sep-2026): todo nace en el chat.** Crear un lienzo, abrir un PDF o una
   imagen tiene que dejar un mensaje con su número; lo que se lo salte no tiene seña y no se
   puede sincronizar. Hay que revisar las entradas que hoy no pasan por el chat.
8. **Los relojes.** No hacen falta iguales para decidir (se compara el resumen y la base),
   pero sí ordenan el chat. Se pensó corregir la hora de lo que llega; al escribirlo se vio que
   eso cambia el mensaje en un solo lado y lo haría chocar en la vuelta siguiente, así que **se
   avisa** en vez de corregir (ver «Cómo quedó»).

---

## Cómo quedó (13-sep-2026)

### Dónde está cada pieza (`app/src/main/java/com/forge/pixpin/sincro/`)

| Archivo | Qué hace | ¿Android? |
|---|---|---|
| `Sena.kt` | número + letra | no |
| `Diferencia.kt` | qué traer, mandar o preguntar, con la base | no |
| `Identidad.kt` | el aparato (id, nombre, letra), el código del grupo, la clave (PBKDF2) y la etiqueta que se anuncia | no |
| `Canal.kt` | tramos AES-GCM con clave por sesión y por sentido; con otro código no se descifra ni el saludo | no |
| `Disco.kt` | lee y escribe la carpeta `files`: sellar, apuntes, marcas de borrado, qué archivos son de un chat, rutas portátiles, base | no |
| `Mezcla.kt` | junta dos versiones de un proyecto a tres bandas, sin preguntar | no |
| `Protocolo.kt` | `Sesion` (el que dirige) y `Respondedor` (el otro) | no |
| `Red.kt` | escuchar en el puerto 47474, anunciarse y buscar por mDNS (`_pixpin._tcp`) | sí |
| `SincronizarActivity.kt` | la pantalla | sí |

Fuera de `sincro/`: `Mensaje.letra`; `MensajesStore.anadir` pone la letra; `MensajesStore.reescribir`
deja las marcas de borrado; la chapa del chat enseña `#47a`; `reenviado` quita número y letra
(la copia es un mensaje nuevo en su chat); botón «Sincronizar» en la portada.

### Decisiones tomadas al escribirlo

- **Qué viaja de un chat**: sus mensajes (por seña), su proyecto (si es de un proyecto) y los
  archivos que alcanzan: adjuntos, lienzos (`pins/draw/<id>.excalidraw.gz`) con sus fotos,
  tablas, croquis, el PDF del proyecto y los adjuntos de sus notas. Lo que esté fuera de `files`
  (una ruta a Descargas) no viaja.
- **Dos pasos por chat**: primero mensajes y proyecto; después, con los dos chats ya iguales,
  los archivos. Así un archivo de un mensaje borrado no vuelve.
- **Rutas portátiles**: la carpeta del aparato se cambia por `pixpin:files/` al salir y por la
  del otro al entrar, también dentro de los lienzos. Los resúmenes se hacen sobre la forma
  portátil, así que el mismo lienzo da el mismo resumen en los dos.
- **El proyecto no pregunta**: hojas añadidas en los dos lados quedan todas; una quitada en un
  lado se quita; si los dos cambiaron la misma hoja, gana el proyecto tocado más tarde.
- **Un archivo cambiado en los dos** se pregunta nombrado por su mensaje (`#3a · Planta baja`),
  con el editado más tarde marcado. Un mensaje cambiado en los dos: marcado el de este aparato.
  Borrado contra intacto: marcado el borrado.
- **Lo acordado solo vale si los dos lo recuerdan igual** (se compara su sello). Si una vuelta se
  cortó a medias, la siguiente hace como la primera: pregunta de más, nunca pisa.
- **El reloj**: no se corrige nada. Si el otro va desfasado más de 2 minutos, al acabar se avisa
  de que ponga la hora automática.
- **Una sincronización a la vez por aparato**: el que llega segundo recibe «ocupado».
- **La pantalla abierta es lo que hace a un aparato encontrable.** No hay servicio en segundo
  plano: para sincronizar, los dos tienen Sincronizar abierto.

### Cómo probarlo con dos aparatos

1. Los dos en la misma Wi-Fi, con la v0.34.0.
2. En el primero: portada → **Sincronizar** → ponerle nombre → **Crear un grupo** → «Enseñar» el código.
3. En el segundo: **Sincronizar** → **Unirme con un código** → teclearlo. Si en 25 s no aparece,
   escribir la dirección que enseña el primero («Dirección en la Wi-Fi»).
4. Con los dos en Sincronizar, en uno aparece el otro en «Aparatos cerca» → **Sincronizar** →
   elegir chats.
5. Casos que merece la pena probar: una foto y un PDF en un proyecto; editar la misma nota en
   los dos; borrar un mensaje en uno; dibujar en el mismo lienzo en los dos; un tercer aparato.

### Lo que falta

- Probarlo en teléfonos (mDNS cambia mucho de un router a otro; por eso existe la dirección a mano).
- Proyectos borrados: no se propaga el borrado de un proyecto entero.
- Sincronizar sin la pantalla abierta (un servicio en primer plano) si hace falta.
- Wi-Fi Direct y la versión de Windows.
