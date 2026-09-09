# Plan: sincronizar entre dispositivos sin internet ni servidores

Fecha: 9-sep-2026. Lo pidió el usuario: multiplataforma, por la red local, sin que nadie
tenga que montar ni pagar un servidor, y construido sobre el número que ya lleva cada mensaje.

> **Esto es un plan, no código.** Nada de lo de aquí está implementado todavía. Las preguntas
> abiertas están al final y hay que responderlas antes de empezar la parte 3.

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

- Al arrancar por primera vez, el dispositivo se da una **letra** libre (`a`, `b`, `t`, `l`…)
  y un nombre legible («Teléfono de Max»). La letra **no se puede cambiar** una vez que ha
  repartido señas: cambiarla renombraría cosas que ya viajaron.
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

1. **La letra, ¿la elige el usuario o se reparte sola?** Elegirla es bonito («l» de laptop)
   pero se agota y choca. Propongo: se reparte sola y **se puede poner un nombre** al aparato.
2. **¿Sincroniza todo o por proyectos?** Todo es más simple de explicar; por proyectos evita
   traerse a la tableta los 300 MB de planos del trabajo.
3. **¿Hace falta que sea segura de verdad?** Cifrar lo que viaja con la clave del grupo cuesta
   poco y evita que alguien en la misma Wi-Fi lea los planos. Doy por hecho que sí.
4. **El «5b» y el «5b tal» del mensaje del usuario**: entendí que la seña **no cambia** al
   viajar —`5b` sigue siendo `5b` en los tres aparatos—, porque si cambiara dejaría de servir
   para reconocer el mismo archivo. Confirmar.
