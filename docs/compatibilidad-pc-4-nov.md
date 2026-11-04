# Compatibilidad con el PC (4-nov-2026, Android v0.161.0)

## Comentario avanzado (pegatinas), `docs/comentarios.md` del PC

- Modelo, fichero y fusión en `app/src/main/java/com/forge/pixpin/comentarios/Pegatinas.kt`:
  - `Tipo`, `Ancla`, `Adjunto`, `Entrada`, `Comentario` y `Pegatinas`, con las operaciones de `modelo.rs`;
  - `Fichero`: la clave `pegatinas` del `.comentarios.json`, conservando `comentarios` y todo lo
    desconocido;
  - `Fusion`: `juntar` y `alLlegar` de `fusion.rs`.
- Al llegar un `.comentarios.json` que también cambió aquí, se juntan las pegatinas
  (`Disco.escribirArchivo`).
- `.adjunto` está en `TERMINACIONES`: los adjuntos `anot-<uid>.a<huella>.adjunto` (FNV-1a de 64 bits)
  viajan con su mensaje.
- Visor con pegatinas: **el plano CAD** (`PlanoActivity`). Su ancla es `pagina` 0 en unidades del
  dibujo respecto a su origen, con la y hacia arriba, igual que `UbicarPlano` del PC.
- La interfaz está en `comentarios/PegatinasUi.kt`: botón, tira, pegar con un toque, arrastrar,
  minichat y lista en tarjetas que suben desde abajo. Los textos son los `comentario-*` del PC en los
  14 idiomas, más 6 propios del táctil.
- Falta en Android:
  - las pegatinas del lienzo, el PDF/Office, el lector de Word y las hojas;
  - el espejo del lienzo;
  - los atajos y los lienzos de una entrada (se leen y se conservan, pero aún no se crean).

## La revisión de urgencia que viaja

`anot-<uid>.urgencia.json`, ver `docs/urgencia-que-viaja.md`. El PC tiene que leerlo y escribirlo, y
añadir `.urgencia.json` a sus `TERMINACIONES`; si no, sigue preguntando él solo.
