# Las tres ramas principales (7-nov-2026)

El usuario: «mantén 3 versiones: una que lo tenga todo completo, otra que sea para la Play Store y otra con
las funciones restringidas. Que sean como 3 ramas principales». (Antes eran dos, del 1-nov: `main` y
`play-store`; la tercera es la del relanzamiento.)

| Rama | Edición | Qué es | Qué sale de ella |
|---|---|---|---|
| **`main`** | completa | **El código general, con todo abierto.** Aquí entra **todo lo nuevo**. | El APK `v<versión>-prueba` (`prueba.yml` sobre `main`) |
| **`restringida`** | restringida | `main` con `app/edicion.txt` = `restringida`: el **catálogo del servidor** decide qué se ve (de fábrica: visores, asistente, tareas, cuenta y pines). Es la del relanzamiento, con cuentas, Pro y las funciones que se abren semana a semana. | El APK `v<versión>-restringida` (`prueba.yml` sobre `restringida`) |
| **`play-store`** | completa | `main` **más** lo que exige Google Play, y nada más. | El AAB (`./gradlew :app:bundlePlay`, `play.yml` sobre `play-store`) |

## Cómo se diferencian

- **La edición** está en `app/edicion.txt` (una palabra) y llega a la app como `BuildConfig.EDICION`. En la
  completa, `Disponible.todoAbierto` abre todo y el catálogo no manda (no hay «Qué incluye Pro» ni avisos de
  Pro). La cuenta de Google y el asistente con cupo siguen funcionando si hay servidor.
- `restringida` solo cambia ese archivo. `main` no lo vuelve a tocar, así que juntar `main` en `restringida`
  nunca choca.
- `play-store` además tiene lo de Google Play (ver abajo) y **no se actualiza sola** (`Actualizar.mirar` con
  `PARA_PLAY`: Play prohíbe que una app de la tienda se actualice por fuera). Tampoco vende nada por fuera de
  Play: es la completa.

## Lo que solo está en `play-store`

- El tipo de compilación `play` y la firma con la clave de subida (`app/build.gradle.kts`).
- `app/src/play/AndroidManifest.xml`: quita REQUEST_INSTALL_PACKAGES, USE_FULL_SCREEN_INTENT y
  USE_EXACT_ALARM, y pone SCHEDULE_EXACT_ALARM.
- `BuildConfig.PARA_PLAY` y lo que cambia con él: sin voces de Microsoft (`ui/VozDeEdge.kt`), los APK se
  comparten en vez de instalarse (`ui/AbrirCon.kt`), los recordatorios se reponen al arrancar (`PixPinApp.kt`),
  sin actualizarse sola (`cuenta/Actualizar.kt`).
- La guía de publicación `docs/google-play.md`.

## Cómo se trabaja

1. Todo cambio nuevo va a **`main`** (y se publica su APK como siempre).
2. Después, **`main` se junta en las otras dos**:
   `git checkout restringida && git merge main` y `git checkout play-store && git merge main`.
3. Un cambio que **solo** pide Google Play se hace en `play-store`; uno que solo es de la restringida, en
   `restringida`. Nunca en `main`.
4. Nunca se juntan `play-store` ni `restringida` en `main`.
5. El `versionCode` sube en `main` y las otras lo heredan al juntar. Las tres llevan el mismo paquete
   (`com.forge.pixpin`): una se instala encima de otra (con la misma firma), no a la vez.

La separación de Play se hizo con un commit en `main` que quita lo de Play y se juntó en `play-store` con
`git merge -s ours` (se registra sin aplicarlo): por eso juntar `main` en `play-store` no borra nada de Play.
