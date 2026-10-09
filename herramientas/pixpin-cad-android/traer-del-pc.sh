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
# Fuentes SHX: la carpeta que diga la app (AutoCAD no está en un teléfono).
cambiar("shx.rs", 'pub fn carpetas() -> Vec<PathBuf> {\n    let mut v = Vec::new();',
        'pub fn carpetas() -> Vec<PathBuf> {\n    let mut v: Vec<PathBuf> = crate::carpetas_shx_android();')
FIN
echo "Traído del PC $(cat "$AQUI/src/pc/COMMIT_DEL_PC"). Compila con ./compilar.sh"
