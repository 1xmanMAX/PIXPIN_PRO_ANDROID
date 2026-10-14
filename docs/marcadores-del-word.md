# Marcadores de un Word (o PowerPoint) que viajan — guía para el PC

*14-oct-2026 · PixPin Android v0.123.3*

El usuario: «en Word el marcador no se sincroniza con la PC». En los dos aparatos el Word
**paginado** (el maquetado como en Office, dentro del lector de PDF) guardaba sus marcadores solo
en ese aparato; viajaban únicamente los del Word **como texto**.

## Lo que hace ahora Android

El lector paginado de un Word o PowerPoint del chat lee y escribe **el mismo archivo que el Word
como texto**: `pins/draw/anot-<uid>.marcas`, con su formato de siempre `id:fraccion:emoji`
separados por `|` (`Lectura.aTexto`). La fracción es del documento entero:

- al leer: hoja = ⌊fracción × hojas⌋, y lo que sobra es la altura en la hoja (`Marcas.deFracciones`);
- al guardar: fracción = (hoja + altura) / hojas (`Marcas.aFracciones`); un marcador que no se
  movió vuelve con su fracción exacta.

## Lo que falta en el PC

`lector_pdf.rs` (el pin de Office) guarda sus marcas en `a.marcas` (formato de PDF `id:x:y:emoji`)
y `anotado_del_adjunto::escribir_lo_del_mensaje` con `pdf = false` solo escribe `a.marcadores`, así
que **los marcadores puestos en el pin de Office del PC no salen del PC**. Para que viajen: en un
documento de Office, convertir sus marcas a fracciones del documento con la misma cuenta de arriba y
escribirlas en `.marcas`; al leer, al revés.
