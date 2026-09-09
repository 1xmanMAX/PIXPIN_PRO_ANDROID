# Prueba en móvil — rama DEEPROOT

APK de prueba (debug, firmado con clave de depuración → reemplaza instalaciones previas):
`F:\THE FORGE\PIXPIN ANDROID\PIXPIN-DEEPROOT-debug.apk`

Build de la que salió: commit `dfdd92e` de `origin/DEEPROOT` (rama `DEEPROOT`).
Rebuild cuando cambie el código:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
Copy-Item 'C:\Users\MaxBook\AppData\Local\pixpin-build\app\outputs\apk\debug\app-debug.apk' 'F:\THE FORGE\PIXPIN ANDROID\PIXPIN-DEEPROOT-debug.apk' -Force
```

## Qué validar (orden sugerido)
1. **WP6** — Proyectos → seleccionar sin audio → «Página web»: no debe aparecer "Audio de las notas" ni el aviso "· audio: ligero".
2. **WP7** — Proyecto con un lienzo de varias hojas → borrar una lámina dentro del editor → volver: la lista ya no enseña la hoja borrada.
3. **WP9a** — Guardar: las fotos y las páginas de PDF llevan el botón (OpenInNew) para dejarlas en un pin.
4. **WP9b** — Tocar un PDF guardado como archivo abre el lector ligero (Anterior/Siguiente), sin salir de la app.
5. **Web (WP4/WP2-3)** — Exportar un lienzo a HTML: medir con flechas+cota (varias, mover, imán) y que al re-guardar se vea el plano.

## Preguntas para WP8-UI y WP10 (las respuestas guían la implementación)
- **WP8**: en Guardar, con 10 páginas seguidas del mismo PDF, ¿qué debe verse? (fila de miniaturas pequeñas; "PDF · 10"; qué hace cada toque).
- **WP10**: tres dedos hacia arriba en el editor 2D/3D/Markdown → hoja de apuntes (block de notas): ¿escribir/copiar/pegar en el lienzo como texto? ¿ocultar/mostrar rápido?
