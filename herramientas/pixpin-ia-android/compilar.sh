#!/bin/sh
# **libpixpinia.so** (arm64): la hoja al lienzo del PC, para app/src/main/jniLibs/arm64-v8a/.
#
# 1. Copia el repositorio del PC (sin target ni .git) y le quita lo de Windows que no hace falta,
#    como el oráculo (herramientas/oraculo-del-pc/preparar.sh): la voz, WinHTTP, DPAPI.
# 2. Trae lo de la app del PC (traer-del-pc.py) y mete este crate y los de relleno (shims/) en
#    su espacio de trabajo.
# 3. Compila con el Rust de Termux (ya es aarch64-linux-android; ver la memoria «PC contra
#    Android»), con páginas de 16 KB.
#
# Uso: herramientas/pixpin-ia-android/compilar.sh <repo del PC>
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
PC=${1:?Falta la ruta del repositorio PIXPIN_PRO_WINDOWS}
R=${RUST_TERMUX:-/data/data/com.termux/files/home/.cache/pixpin-rust-temporal}
C="$AQUI/copia-del-pc"
rm -rf "$C" && mkdir "$C"
(cd "$PC" && tar --exclude=./target --exclude=./.git -cf - .) | tar -xf - -C "$C"
python3 - "$C" <<'PY'
import os, re, sys
c = sys.argv[1] + "/crates/"
ia = c + "pixpin-ia/"
s = open(ia + "Cargo.toml").read()
s = re.sub(r'pixpin-voz = \{[^\n]*\n', '', s)
s = re.sub(r'windows = \{ version = "0.62.2", features = \[.*?\] \}\n', '', s, flags=re.S)
open(ia + "Cargo.toml", "w").write(s.split("[dev-dependencies]")[0])
open(ia + "src/servidor.rs", "w").write("pub const CONTEXTO: u32 = 8192;\n")
open(ia + "src/descarga.rs", "w").write("")
h = open(ia + "src/nube/http.rs").read()
open(ia + "src/nube/http.rs", "w").write("use std::time::Duration;\nuse crate::motor::Seguir;\n" + h[h.index("/// Por que no salio."):h.index("/// **Manda el POST**")] +
    'pub fn enviar(_p: &Pedido, _a: &mut dyn FnMut(&[u8]) -> Seguir) -> Result<bool, Fallo> { Err(Fallo::Red("sin red".into())) }\n'
    '#[derive(Debug, Clone, PartialEq, Eq)]\npub struct Respuesta { pub codigo: u32, pub cuerpo: Vec<u8>, pub ubicacion: Option<String> }\n'
    # La red de las conexiones la pone el teléfono (HttpURLConnection, por el puente).
    'pub type Pedidor = fn(&str, &str, &[(&\'static str, String)], &[u8], usize) -> Result<Respuesta, Fallo>;\n'
    'pub static PEDIDOR: std::sync::OnceLock<Pedidor> = std::sync::OnceLock::new();\n'
    'pub fn pedir(m: &str, u: &str, c: &[(&\'static str, String)], b: &[u8], l: usize) -> Result<Respuesta, Fallo> { match PEDIDOR.get() { Some(f) => f(m, u, c, b, l), None => Err(Fallo::Red("sin red".into())) } }\n')
k = open(ia + "src/nube/claves.rs").read()
open(ia + "src/nube/claves.rs", "w").write(k[k.index("/// Lo que se ensena de una clave"):k.index("/// **Cifra** con DPAPI")])
# El cifrado de lo guardado lo pone el teléfono (la Keystore de Android, por el puente).
open(ia + "src/nube/claves.rs", "a").write(
    "\npub fn carpeta() -> Option<std::path::PathBuf> { None }\n"
    "pub type Cifrar = fn(&[u8]) -> Result<Vec<u8>, String>;\n"
    "pub static CIFRADOR: std::sync::OnceLock<(Cifrar, Cifrar)> = std::sync::OnceLock::new();\n"
    "pub fn cifrar(c: &[u8]) -> Result<Vec<u8>, String> { match CIFRADOR.get() { Some(f) => (f.0)(c), None => Err(\"sin cifrado\".into()) } }\n"
    "pub fn descifrar(c: &[u8]) -> Result<Vec<u8>, String> { match CIFRADOR.get() { Some(f) => (f.1)(c), None => Err(\"sin cifrado\".into()) } }\n")
o = open(ia + "src/conexiones/oauth.rs").read()
i = o.index("pub fn aleatorio(n: usize) -> Vec<u8> {")
j = o.index("\n}\n", i) + 3
open(ia + "src/conexiones/oauth.rs", "w").write(o[:i] + "pub fn aleatorio(n: usize) -> Vec<u8> { (0..n).map(|i| (i * 37 + 11) as u8).collect() }\n" + o[j:])
n = c + "pixpin-notas/"
os.system("mv %ssrc %ssrc_old && mkdir %ssrc && cp %ssrc_old/formulas.rs %ssrc/" % (n, n, n, n, n))
open(n + "src/lib.rs", "w").write("pub mod formulas;\n")
open(n + "Cargo.toml", "w").write('[package]\nname = "pixpin-notas"\nversion.workspace = true\nedition.workspace = true\nrust-version.workspace = true\nlicense.workspace = true\n[dependencies]\npixpin-motor2d = { path = "../pixpin-motor2d" }\n')
# Un fallo del motor no tumba la aplicación: se recoge en el puente (catch_unwind).
r = open(sys.argv[1] + "/Cargo.toml").read()
r = r.replace('panic = "abort"', 'panic = "unwind"').replace('lto = "fat"', 'lto = "thin"')
open(sys.argv[1] + "/Cargo.toml", "w").write(r)
PY
python3 "$AQUI/traer-del-pc.py" "$PC" "$AQUI/crate/src"
for s in codec ocr shell pdf; do cp -r "$AQUI/shims/$s" "$C/crates/shim-$s"; done
cp -r "$AQUI/crate" "$C/crates/pixpin-ia-android"
(cd "$PC" && git rev-parse --short HEAD) > "$AQUI/COMMIT_DEL_PC"
export LD_LIBRARY_PATH="$R/lib:/data/data/com.termux/files/usr/lib"
export RUSTC="$R/bin/rustc" PATH="$R/bin:$PATH"
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-soname,libpixpinia.so"
(cd "$C" && CARGO_TARGET_DIR="$AQUI/target" "$R/bin/cargo" build --release -p pixpin-ia-android)
cp "$AQUI/target/release/libpixpinia.so" "$AQUI/../../app/src/main/jniLibs/arm64-v8a/libpixpinia.so"
ls -l "$AQUI/../../app/src/main/jniLibs/arm64-v8a/libpixpinia.so"
