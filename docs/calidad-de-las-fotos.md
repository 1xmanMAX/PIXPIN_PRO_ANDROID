# Las fotos viajan y se ven con su calidad (13-oct-2026)

Lo dijo el usuario: lo anotado en pantalla en el PC, al sincronizar, llegaba al móvil «con una
calidad pésima, irreconocible, no se lee ni una letra». Pidió que las imágenes, la galería y todo
lo demás lleguen siempre con su calidad original.

## Qué pasaba

**El archivo viajaba entero.** Ni el PC ni el móvil reducen nada al sincronizar: la captura va al
chat como PNG (`anotador_al_chat::guardar`), su copia a `imagenes/foto<ms>` del lienzo
(`lienzo_de_la_foto::crear`) y la galería manda los bytes tal cual (`GaleriaQueViaja`,
`CapturasEnElTelefono.guardar`). En el móvil se leía mal:

- `ImageStore.load` parte la imagen **por dos** hasta que su lado largo no pasa de 2048. Una
  captura de 2560 se pintaba a 1280; la del escritorio entero (5760, tres monitores), a 1440. El
  editor, el pin y la foto ampliada con dos dedos la pedían así.
- La foto anotada se pintaba en la burbuja a escala fija 0,5 de su lienzo, y el lienzo de una
  captura de 2560 mide 1280 unidades: 640 píxeles.
- La foto sin anotar se leía con `load(ruta, 1000)`: 2560 → 640 (partida por cuatro).

## Qué se cambió en Android

- `ImageStore.cargarNitida`: la imagen con sus píxeles, o reducida **exacta** (no a saltos de
  dos) solo si pasa de 8192 de lado o de 4096² píxeles. Con muchas fotos en un lienzo se reparte
  la memoria (`pixelesParaVarias`), sin bajar nunca de 2048².
- La usan el editor (`DrawEditorActivity.bitmapDe`), el pin, la foto ampliada, compartir y las
  exportaciones de `capture/Export.kt`.
- La burbuja pinta la foto, la foto anotada y el dibujo a 1600 de lado largo, exacto
  (`LADO_DE_LA_BURBUJA`, `escalaParaLaBurbuja`).
- El recorte de una imagen (`crop`, en píxeles del archivo original) se lleva a la medida
  leída (`recorteEnPixeles`): antes se tomaba en píxeles del mapa y, con la imagen a otra medida,
  enseñaba otro trozo.
- La vista grande de la galería, a 1600 en vez de 720.

## Para el PC

**No hay que cambiar nada para que se arregle.** La convención se queda como estaba:

- `lienzo_de_la_foto::LADO_MAXIMO = 2048` y `medida_en_el_lienzo` siguen siendo **las medidas de
  la foto en el lienzo** (unidades), no su resolución. El móvil sigue colocando la foto con esas
  medidas (`DrawEditorActivity.colocarImagenInicial` con `ImageStore.load`), así que lo dibujado
  en un lado cae en el mismo sitio en el otro. **No hay que subirlo**: cambiaría las unidades, no
  la nitidez. Lo que cambió es que ahora se pinta dentro de esa caja con todos los píxeles.
- Lo que el PC tiene que seguir haciendo: mandar la captura **sin reducir ni recomprimir** (hoy
  va en PNG a su tamaño) y escribir la copia de `imagenes/` con los mismos bytes.
- Si el PC pinta una foto del móvil dentro de su lienzo, que la pinte con sus píxeles estirados a
  la caja del elemento (`width`×`height`), no con los de la caja: la foto puede medir el doble que
  su caja y eso es lo que da la nitidez.
- Un `crop` de imagen va en píxeles del **archivo original** y con `naturalWidth`/`naturalHeight`
  puestos: así cada lado lo lleva a la medida con que lea la foto.
