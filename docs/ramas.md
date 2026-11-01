# Las dos ramas principales (1-nov-2026)

El usuario: «mantén dos ramas principales: una con todo el código y lo que se publicará en la Play
Store, y otra sin Play Store, para que las restricciones de Play no afecten al código general».

| Rama | Qué es | Qué sale de ella |
|---|---|---|
| **`main`** | **El código general**, sin ninguna restricción de Google Play. Aquí entra **todo lo nuevo**. | El APK de GitHub (`assembleRelease`, flujo `prueba.yml`), con todos los permisos y funciones. |
| **`play-store`** | `main` **más** lo que exige Google Play, y nada más. | El AAB para la Play Store (`./gradlew :app:bundlePlay`, flujo `play.yml` lanzado sobre esta rama). |

## Lo que solo está en `play-store`

- El tipo de compilación `play` y la firma con la clave de subida (`app/build.gradle.kts`).
- `app/src/play/AndroidManifest.xml`: quita REQUEST_INSTALL_PACKAGES, USE_FULL_SCREEN_INTENT y
  USE_EXACT_ALARM, y pone SCHEDULE_EXACT_ALARM.
- `BuildConfig.PARA_PLAY` y lo que cambia con él: sin voces de Microsoft (`ui/VozDeEdge.kt`), los APK
  se comparten en vez de instalarse (`ui/AbrirCon.kt`), los recordatorios se reponen al arrancar
  (`PixPinApp.kt`).
- La guía de publicación `docs/google-play.md`.

Lo que es bueno para las dos (la privacidad en Ajustes, «Reportar» en la IA, `planes/`, las alarmas que
no fallan sin permiso, los secretos fuera de las copias, las .so a 16 KB) está en `main`.

## Cómo se trabaja

1. Todo cambio nuevo va a **`main`** (y se publica el APK de GitHub como siempre).
2. Después, **`main` se junta en `play-store`**: `git checkout play-store && git merge main`. Así Play
   recibe lo nuevo sin perder sus restricciones.
3. Un cambio que **solo** pide Google Play se hace **en `play-store`**, nunca en `main`.
4. Nunca se junta `play-store` en `main`.
5. El `versionCode` sube en `main`; `play-store` lo hereda al juntar. Para subir a Play una
   corrección solo de Play, súbelo en `play-store` por encima del de `main` (y el siguiente de `main`
   por encima de ese).

La separación se hizo con un commit en `main` que quita lo de Play y se juntó en `play-store` con
`git merge -s ours` (se registra sin aplicarlo): por eso juntar `main` en `play-store` no borra nada de
Play.
