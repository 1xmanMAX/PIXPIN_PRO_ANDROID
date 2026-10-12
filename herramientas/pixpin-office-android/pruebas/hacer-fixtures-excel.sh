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
rm -rf "$TMP"
ls -l "$D"
