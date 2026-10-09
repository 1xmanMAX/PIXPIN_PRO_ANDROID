#!/bin/sh
# Trae del repositorio del PC (PIXPIN_PRO_WINDOWS) la parte del visor de planos que no depende de
# Windows —leer el DWG/DXF y pasarlo a rayas, rellenos, arcos y letras— y le cambia solo dónde
# buscar las fuentes: en Android son las de /system/fonts y las SHX que el usuario deje en la
# carpeta que diga la app. El dibujo (Direct3D) no se trae: aquí lo hace OpenGL ES desde Kotlin.
#
# Uso: herramientas/pixpin-cad-android/traer-del-pc.sh <repo del PC>
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
PC=${1:?Falta la ruta del repositorio PIXPIN_PRO_WINDOWS}
SRC="$PC/crates/pixpin-cad/src"
mkdir -p "$AQUI/src/pc"
for f in convertir leer modelo relleno shx teselar texto; do cp "$SRC/$f.rs" "$AQUI/src/pc/"; done
(cd "$PC" && git rev-parse --short HEAD) > "$AQUI/src/pc/COMMIT_DEL_PC"
python3 - "$AQUI/src/pc" <<'FIN'
import sys, pathlib
d = pathlib.Path(sys.argv[1])
def cambiar(nombre, viejo, nuevo):
    p = d / nombre
    t = p.read_text()
    if viejo not in t:
        sys.exit(f"{nombre}: no encuentro el trozo a cambiar; el PC lo movió:\n{viejo}")
    p.write_text(t.replace(viejo, nuevo, 1))
# Fuentes TrueType: las del sistema Android.
cambiar("texto.rs", 'const CARPETA_FUENTES: &str = "C:\\\\Windows\\\\Fonts";',
        'const CARPETA_FUENTES: &str = "/system/fonts";')
cambiar("texto.rs", '    v.extend(["arialn.ttf".to_string(), "arial.ttf".to_string(), "segoeui.ttf".to_string()]);',
        '    v.extend(["arialn.ttf".to_string(), "arial.ttf".to_string(), "segoeui.ttf".to_string()]);\n'
        '    // Android: no hay Arial; Roboto se le parece y siempre está.\n'
        '    v.extend(["RobotoStatic-Regular.ttf", "Roboto-Regular.ttf", "DroidSans.ttf", "NotoSans-Regular.ttf"].map(String::from));')
# Fuentes SHX: la carpeta que diga la app (AutoCAD no está en un teléfono).
cambiar("shx.rs", 'pub fn carpetas() -> Vec<PathBuf> {\n    let mut v = Vec::new();',
        'pub fn carpetas() -> Vec<PathBuf> {\n    let mut v: Vec<PathBuf> = crate::carpetas_shx_android();')
FIN
echo "Traído del PC $(cat "$AQUI/src/pc/COMMIT_DEL_PC"). Compila con ./compilar.sh"
