#!/bin/sh
# Vuelve a hacer las .shx de app/src/main/assets/fuentes-shx desde las de Hershey (ver hershey-a-shx.py).
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
DESTINO="$AQUI/../../../app/src/main/assets/fuentes-shx"
for par in rowmans:romans rowmand:romand rowmant:romant scripts:scripts scriptc:scriptc gothiceng:gothice greeks:greeks timesi:italic; do
  python3 -I "$AQUI/hershey-a-shx.py" "$AQUI/hershey/${par%%:*}.jhf" "$DESTINO/${par##*:}.shx" "${par##*:}"
done
