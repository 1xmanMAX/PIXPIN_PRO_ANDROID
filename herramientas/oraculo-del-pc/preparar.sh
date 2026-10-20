#!/bin/sh
# **El oráculo del PC**: el asistente de PixPin para Windows (`crates/pixpin-ia`) compilado aquí,
# para sacar de él los datos con los que se comprueba que el teléfono da lo mismo (catálogo,
# selección por turno, BM25, tabla escaneada, calcar…). Lo que se hace con Windows (llama-server,
# DPAPI, WinHTTP) se cambia por nada: solo hacen falta los algoritmos.
#
# Uso: herramientas/oraculo-del-pc/preparar.sh <repo del PC>
# Luego: herramientas/oraculo-del-pc/oraculo.sh catalogo > app/src/main/resources/ia/catalogo-del-pc.json
#        herramientas/oraculo-del-pc/oraculo.sh topes 16 > app/src/test/resources/ia/herramientas-del-pc.json
#        herramientas/oraculo-del-pc/oraculo.sh seleccion < pedidos.jsonl     (ver las pruebas de ia/)
# Cargo: el de Termux bajo /data (ver la memoria «PC contra Android»), por `CARGO=…`.
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
PC=${1:?Falta la ruta del repositorio PIXPIN_PRO_WINDOWS}
CARGO=${CARGO:-cargo}
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
    # Las conexiones (Drive, 17-oct) piden por aquí: sin red.
    'pub struct Respuesta { pub codigo: u32, pub cuerpo: Vec<u8>, pub ubicacion: Option<String> }\n'
    'pub fn pedir(_m: &str, _u: &str, _c: &[(&\'static str, String)], _b: &[u8], _l: usize) -> Result<Respuesta, Fallo> { Err(Fallo::Red("sin red".into())) }\n')
k = open(ia + "src/nube/claves.rs").read()
open(ia + "src/nube/claves.rs", "w").write(k[k.index("/// Lo que se ensena de una clave"):k.index("/// **Cifra** con DPAPI")])
open(ia + "src/nube/claves.rs", "a").write("\npub fn carpeta() -> Option<std::path::PathBuf> { None }\npub fn cifrar(_c: &[u8]) -> Result<Vec<u8>, String> { Err(\"sin DPAPI\".into()) }\npub fn descifrar(_c: &[u8]) -> Result<Vec<u8>, String> { Err(\"sin DPAPI\".into()) }\n")
o = open(ia + "src/conexiones/oauth.rs").read()
i = o.index("pub fn aleatorio(n: usize) -> Vec<u8> {")
j = o.index("\n}\n", i) + 3
open(ia + "src/conexiones/oauth.rs", "w").write(o[:i] + "pub fn aleatorio(n: usize) -> Vec<u8> { (0..n).map(|i| (i * 37 + 11) as u8).collect() }\n" + o[j:])
n = c + "pixpin-notas/"
os.system("mv %ssrc %ssrc_old && mkdir %ssrc && cp %ssrc_old/formulas.rs %ssrc/" % (n, n, n, n, n))
open(n + "src/lib.rs", "w").write("pub mod formulas;\n")
# Desde el 18-oct las fórmulas viven en `pixpin-motor2d` (pixpin-notas las reexporta).
open(n + "Cargo.toml", "w").write('[package]\nname = "pixpin-notas"\nversion.workspace = true\nedition.workspace = true\nrust-version.workspace = true\nlicense.workspace = true\n[dependencies]\npixpin-motor2d = { path = "../pixpin-motor2d" }\n')
PY
mkdir -p "$C/crates/volcado/src"
cp "$AQUI/src/main.rs" "$C/crates/volcado/src/main.rs"
printf '[package]\nname = "volcado"\nversion.workspace = true\nedition.workspace = true\nrust-version.workspace = true\nlicense.workspace = true\n[dependencies]\npixpin-ia = { path = "../pixpin-ia" }\nserde_json = "1.0"\n' > "$C/crates/volcado/Cargo.toml"
(cd "$C" && CARGO_TARGET_DIR="$AQUI/target" "$CARGO" build -q -p volcado)
printf '#!/bin/sh\nexec "%s/target/debug/volcado" "$@"\n' "$AQUI" > "$AQUI/oraculo.sh"
chmod +x "$AQUI/oraculo.sh"
echo "Listo: $AQUI/oraculo.sh"
