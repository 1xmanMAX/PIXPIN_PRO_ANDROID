#!/bin/sh
# Compila «el PC» para SincronizarConElPcTest: los crates de verdad del repositorio de Windows
# (PIXPIN_PRO_WINDOWS), con `pixpin-shell` cambiado por uno sin Windows (solo las uniones de
# directorio, como enlaces simbólicos). Uso: herramientas/pc-simulado/preparar.sh <repo del PC>
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
PC=${1:?Falta la ruta del repositorio PIXPIN_PRO_WINDOWS}
rm -rf "$AQUI/copia-del-pc" && mkdir "$AQUI/copia-del-pc"
(cd "$PC" && tar --exclude=./target --exclude=./.git -cf - .) | tar -xf - -C "$AQUI/copia-del-pc"
SH="$AQUI/copia-del-pc/crates/pixpin-shell"
rm -rf "$SH/src" "$SH/tests" "$SH/benches" "$SH/build.rs" && mkdir "$SH/src"
cp "$AQUI/shell-falso/src/lib.rs" "$SH/src/lib.rs"
printf '[package]\nname = "pixpin-shell"\nversion.workspace = true\nedition.workspace = true\nrust-version.workspace = true\nlicense.workspace = true\n' > "$SH/Cargo.toml"
cp "$AQUI/copia-del-pc/Cargo.lock" "$AQUI/Cargo.lock"
(cd "$AQUI" && cargo build -q)
echo "Listo. Corre las pruebas con:"
echo "  PIXPIN_PC_SIMULADO=$AQUI/target/debug/pc-simulado ./gradlew testDebugUnitTest --tests '*SincronizarConElPcTest'"
