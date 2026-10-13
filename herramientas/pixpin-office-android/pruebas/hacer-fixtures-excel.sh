#!/bin/sh
# Rehace los fixtures de PxhjTest (app/src/test/resources/excel/): un libro pequeño hecho a mano
# (hacer-libros.py) pasado a PXHL/PXHG/PXHT con el binario hojas-a-pxhj de ./compilar.sh.
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$AQUI/../../.." && pwd)
TMP=$(mktemp -d)
python3 -I "$AQUI/hacer-libros.py" "$TMP"
"$AQUI/../hojas-a-pxhj.sh" "$TMP/prueba.xlsx" "$TMP/salida" 0 64 1
D="$REPO/app/src/test/resources/excel"
mkdir -p "$D"
cp "$TMP/salida/libro.pxhl" "$D/prueba.pxhl"
cp "$TMP/salida/geometria.pxhg" "$D/prueba.pxhg"
cp "$TMP/salida/trozo-0.pxht" "$D/prueba-0.pxht"
# Lo del 12-oct del PC: barras, iconos, minigráfico, nota, autofiltro y lista desplegable.
"$AQUI/../hojas-a-pxhj.sh" "$TMP/extras.xlsx" "$TMP/extras" 0 64 1
cp "$TMP/extras/geometria.pxhg" "$D/extras.pxhg"
cp "$TMP/extras/trozo-0.pxht" "$D/extras-0.pxht"
# Un .xls antiguo (BIFF8), el de las pruebas del PC: con PIXPIN_PC=<repo del PC>.
if [ -n "$PIXPIN_PC" ]; then
    "$AQUI/../hojas-a-pxhj.sh" "$PIXPIN_PC/crates/pixpin-hojas/tests/muestras/pequeno.xls" "$TMP/xls" 0 64 1
    cp "$TMP/xls/libro.pxhl" "$D/viejo.pxhl"
    cp "$TMP/xls/trozo-0.pxht" "$D/viejo-0.pxht"
fi
rm -rf "$TMP"
ls -l "$D"
