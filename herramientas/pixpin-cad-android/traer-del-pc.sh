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
for f in convertir leer modelo relleno shx teselar texto proxy convertir3d modelo3d; do cp "$SRC/$f.rs" "$AQUI/src/pc/"; done
# Lo de BIM y Civil 3D (crates/pixpin-bim): en un solo crate aquí, así que `pixpin_cad::` es `crate::`.
mkdir -p "$AQUI/src/pc/bim"
cp "$PC/crates/pixpin-bim/src/civil.rs" "$AQUI/src/pc/bim/civil.rs"
cp "$PC/crates/pixpin-bim/src/niveles.rs" "$AQUI/src/pc/bim/niveles.rs"
cp "$PC/crates/pixpin-bim/src/lib.rs" "$AQUI/src/pc/bim/mod.rs"
sed -i 's/pixpin_cad::/crate::/g' "$AQUI/src/pc/bim/civil.rs" "$AQUI/src/pc/bim/niveles.rs" "$AQUI/src/pc/bim/mod.rs"
# Android abre el Revit pasándolo a IFC (y el IFC lo enseña el croquis 3D): esa función, pública.
sed -i 's/^fn revit_a_ifc(/pub fn revit_a_ifc(/' "$AQUI/src/pc/bim/mod.rs"
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
# Las fuentes por familia: en Windows salen del registro; aquí, de los nombres de /system/fonts.
import re
t = (d / "texto.rs").read_text()
ini = t.find("pub fn fuentes_instaladas()")
if ini < 0:
    sys.exit("texto.rs: no encuentro fuentes_instaladas; el PC lo movió")
fin = t.find("\n}\n", ini) + 3
t = t[:ini] + '''pub fn fuentes_instaladas() -> &'static HashMap<String, std::path::PathBuf> {
    // Android: no hay registro de fuentes; la familia es el nombre del archivo
    // («roboto-regular» → «roboto», «roboto regular»).
    static F: std::sync::OnceLock<HashMap<String, std::path::PathBuf>> = std::sync::OnceLock::new();
    F.get_or_init(|| {
        let mut mapa = HashMap::new();
        if let Ok(d) = std::fs::read_dir(CARPETA_FUENTES) {
            for e in d.flatten() {
                let ruta = e.path();
                let Some(base) = ruta.file_stem().map(|s| s.to_string_lossy().to_lowercase()) else { continue };
                mapa.entry(base.replace('-', " ")).or_insert_with(|| ruta.clone());
                if let Some(familia) = base.strip_suffix("-regular") {
                    mapa.entry(familia.to_string()).or_insert_with(|| ruta.clone());
                }
            }
        }
        mapa
    })
}
''' + t[fin:]
(d / "texto.rs").write_text(t)
# Roboto es tan ancha como Arial: donde el PC usa Arial Narrow (estilos SHX sin su fuente, o
# «arial narrow»), aquí se estrecha al ancho de esa (≈ 0,82). Sin esto los textos se montaban.
cambiar("texto.rs", "    escala_ttf: f32,\n}", "    escala_ttf: f32,\n    /// Android: cuánto se estrecha la letra (Roboto haciendo de Arial Narrow).\n    estrecho: f32,\n}")
cambiar("texto.rs", "                        escala_ttf: if con_shx { 1.0 / mayus } else { 1.0 },\n",
        "                        escala_ttf: if con_shx { 1.0 / mayus } else { 1.0 },\n                        estrecho: crate::estrecho_android(&clave, &nombre),\n")
cambiar("texto.rs", """                    let k = self.escala_ttf;
                    if k != 1.0 {
                        for p in &mut l.triangulos {
                            p[0] *= k;
                            p[1] *= k;
                        }
                        l.avance *= k;
                    }""", """                    let k = self.escala_ttf;
                    let kx = k * self.estrecho;
                    if k != 1.0 || kx != 1.0 {
                        for p in &mut l.triangulos {
                            p[0] *= kx;
                            p[1] *= k;
                        }
                        l.avance *= kx;
                    }""")
# SHX que no está: la de PixPin que más se le parece (las de Hershey, ver fuentes/). El PC las
# lleva dentro (`include_bytes!`, desde c493abb): las mismas que los assets de la app.
s = (d / "shx.rs").read_text()
if s.count('include_bytes!("../fuentes-shx/') != 8:
    sys.exit("shx.rs: no encuentro las 8 SHX de PixPin que el PC lleva dentro")
(d / "shx.rs").write_text(s.replace('include_bytes!("../fuentes-shx/', 'include_bytes!("../../../../app/src/main/assets/fuentes-shx/'))
# Fuentes SHX: la carpeta que diga la app (AutoCAD no está en un teléfono).
cambiar("shx.rs", 'pub fn carpetas() -> Vec<PathBuf> {\n    let mut v = Vec::new();',
        'pub fn carpetas() -> Vec<PathBuf> {\n    let mut v: Vec<PathBuf> = crate::carpetas_shx_android();')
FIN
echo "Traído del PC $(cat "$AQUI/src/pc/COMMIT_DEL_PC"). Compila con ./compilar.sh"
