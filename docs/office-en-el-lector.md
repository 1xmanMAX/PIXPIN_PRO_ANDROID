# Word y PowerPoint en el lector de PDF (11-oct-2026)

Lo del PC (`crates/pixpin-office`, commits 4905dbb, 8aa35ae y 6fecb5c): un `.docx` se ve
**página a página como lo imprime Word** y un `.pptx` **diapositiva a diapositiva como lo proyecta
PowerPoint**, dentro del lector de PDF, con lo mismo que un PDF.

## Piezas

- `herramientas/pixpin-office-android/`: el crate del PC copiado tal cual (`traer-del-pc.sh`) y lo
  de Android en `src/`: `letras.rs` (el `Medidor`, con los TTF del teléfono y los de PixPin que
  miden como los de Windows: Carlito, Caladea, Liberation), `pxof.rs` (el formato de cada hoja),
  `lib.rs` (JNI). `compilar.sh` deja `app/src/main/jniLibs/arm64-v8a/libpixpinoffice.so`.
- **Las letras se miden en Rust**, con `ttf-parser`, sobre los mismos archivos con que Kotlin pinta:
  ni un JNI por carácter ni por tanda. Cada texto viaja con la cara, el glifo y la x de cada letra.
- `office/Pxof.kt` lee el PXOF (puro, probado en `PxofTest` con fixtures que escribe el binario
  de Rust: `pruebas/hacer-fixtures.sh`); `office/PintorOffice.kt` lo pinta en un `Canvas`
  (`drawGlyphs` desde Android 12; letra a letra en 10 y 11); `office/DocumentoOffice.kt` es la
  fuente de hojas; `office/OfficeAPdf.kt` lo pasa a PDF en vectores (compartir, imprimir, «Al
  proyecto», nuevo proyecto compartido).
- Entradas: el chat y «Abrir con» (que pasa por el chat). Sin el .so, o si no se lee, lo de antes
  (`office/AbrirOffice.kt`): el Word como texto en el visor de documentos, el PowerPoint a un
  proyecto.

## Las dos vistas de un Word y su tinta

Un Word tiene ahora **dos vistas**, como en el PC, y **cada una su tinta**:

| Vista | Qué es | Tinta | Marcadores |
|---|---|---|---|
| Maquetada (lector de PDF) | las páginas de Word | `anot-<uid>-p<n>.excalidraw.gz` + `anot-<uid>-p<n>.hoja` (marco de la hoja, a 1400 de ancho) | en el teléfono (preferencias `marcas`, por ruta) |
| «Modo lectura» (visor de documentos) | el texto que se refluye | `anot-<uid>.excalidraw.gz` + `.maqueta` (como siempre) | `anot-<uid>.marcas` (como siempre) |

Es lo mismo que hace el PC: su lector abre un Office con `DondeVa::de` (tinta por hoja,
`anot-<uid>-p<n>` con su `.hoja`, igual que un PDF del chat) y sus marcadores de hoja no van a
`anot-<uid>.marcas` (allí escribe los del visor reflujado, otro formato: `escribir_lo_del_mensaje`
con `pdf = false`). Por eso aquí los marcadores y los espacios de las hojas maquetadas **no viajan**
(se quedan por la ruta): si fueran a `anot-<uid>.marcas` pisarían los del modo lectura.

Nada de formato nuevo: los nombres ya existían, ya viajan (`AnotacionesDelAdjunto.TERMINACIONES`)
y se borran con su mensaje. Lo anotado en una vista **no se ve en la otra** (una es por páginas y
la otra por columna de texto: no hay forma fiel de pasar de una a otra).

## Lo que hay y lo que no (frente al PC)

Hay: tinta con el motor de siempre, marcadores, negativo, espacios para anotar, página que baja
sola, aumento nítido (cada escalón se vuelve a pintar en vectores), enlaces web y correo (un
toque), copiar un renglón (mantener) o la hoja entera (engranaje), imprimir (con lo anotado),
compartir (PDF en vectores con o sin lo anotado, y el original), «Modo lectura» y «Escuchar» (por
el modo lectura) en Word, «Presentar» en PowerPoint, «Al proyecto».

No hay: buscar dentro (el lector de PDF de Android tampoco lo tiene), elegir texto arrastrando
(solo renglón u hoja), «Página web (hojas)» para un Office, y el aumento más allá del tope de
píxeles por hoja del lector (4 Mpx) se ve estirado como en un PDF.
