#!/bin/sh
# Rehace los fixtures de PxofTest (app/src/test/resources/office/): un Word y un PowerPoint
# pequeños hechos a mano (hacer-documentos.py) pasados a PXOF con el binario de ./compilar.sh.
# Las letras se dan con ruta relativa al repositorio, para que el fixture no lleve rutas de esta máquina.
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$AQUI/../../.." && pwd)
R=${RUST_TERMUX:-/data/data/com.termux/files/home/.cache/pixpin-rust-temporal}
TMP=$(mktemp -d)
python3 -I "$AQUI/hacer-documentos.py" "$TMP"
cd "$REPO"
for t in docx pptx; do
    LD_LIBRARY_PATH="$R/lib:/data/data/com.termux/files/usr/lib" \
        herramientas/pixpin-office-android/target/release/office-a-pxof "$TMP/prueba.$t" "$TMP/$t" app/src/main/assets/fuentes-ttf
done
D=app/src/test/resources/office
mkdir -p "$D"
cp "$TMP/docx/pagina-1.pxof" "$D/word-1.pxof"
cp "$TMP/docx/pagina-2.pxof" "$D/word-2.pxof"
cp "$TMP/pptx/pagina-1.pxof" "$D/powerpoint-1.pxof"
rm -rf "$TMP"
ls -l "$D"
