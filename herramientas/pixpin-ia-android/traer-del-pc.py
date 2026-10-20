#!/usr/bin/env python3
"""Trae del PC (apps/pixpin/src/asistente) lo de la hoja al lienzo, quitando lo que es de Windows.

No se cambia ni una línea de los algoritmos: se copian los ficheros y se quitan enteras las piezas
que en el teléfono hace Kotlin (leer la hoja, pintar, la cola de la ventana del chat). Si el PC
mueve o renombra algo, falla diciendo qué: así no se compila nada a medias.

Uso: traer-del-pc.py <repo del PC> <carpeta src del crate>
"""
import os, re, sys

pc, src = sys.argv[1], sys.argv[2]
asis = os.path.join(pc, "apps/pixpin/src/asistente")

def leer(p):
    return open(p, encoding="utf-8").read()

def pieza(texto, empieza):
    """(inicio, fin) de la pieza de primer nivel que empieza por `empieza`, con sus /// y #[...] de encima."""
    m = re.search(r"(?m)^" + re.escape(empieza), texto)
    if not m:
        sys.exit("traer-del-pc: no está «%s» en el PC (¿lo movieron?)" % empieza)
    i = m.start()
    linea = texto[i:texto.index("\n", i)]
    if linea.rstrip().endswith(";"):
        fin = texto.index("\n", i) + 1
    elif linea.rstrip().endswith("["):
        fin = texto.index("\n];\n", i) + 4
    else:
        fin = texto.index("\n}\n", i) + 3
    # Los comentarios de documentación y atributos de encima van con ella.
    while True:
        j = texto.rfind("\n", 0, i - 1) + 1
        anterior = texto[j:i - 1] if i > 0 else ""
        if i > 0 and (anterior.startswith("///") or anterior.startswith("#[")):
            i = j
        else:
            break
    return i, fin

def quitar(texto, empiezan):
    for e in empiezan:
        i, f = pieza(texto, e)
        texto = texto[:i] + texto[f:]
    return texto

def sacar(texto, empiezan):
    return "\n".join(texto[slice(*pieza(texto, e))] for e in empiezan)

dest = os.path.join(src, "asistente")
os.makedirs(os.path.join(dest, "hoja_a_lienzo"), exist_ok=True)

# 1. hoja_a_lienzo.rs, sin lo de Windows (lo pone hoja_reemplazos.rs).
h = leer(os.path.join(asis, "hoja_a_lienzo.rs"))
h = quitar(h, [
    "pub fn leer(", "pub struct PorLaCola", "impl PorLaCola", "impl Destino for PorLaCola",
    "pub fn pintar(", "pub fn motor_de_vision(", "pub fn motor_de_vision_en(",
    "pub struct SinVentana", "impl Destino for SinVentana", "pub fn letra_segoe(",
])
h = re.sub(r"(?m)^#\[cfg\(test\)\]\n(pub\(crate\) )?mod (banco|banco_17oct|banco_diagramas|pruebas);\n", "", h)
h += "\ninclude!(\"hoja_reemplazos.rs\");\n"
open(os.path.join(dest, "hoja_a_lienzo.rs"), "w", encoding="utf-8").write(h)

# 2. Su planificador y su supervisor, tal cual.
for f in ("plan.rs", "supervisor.rs"):
    open(os.path.join(dest, "hoja_a_lienzo", f), "w", encoding="utf-8").write(leer(os.path.join(asis, "hoja_a_lienzo", f)))

# 3. De ocr.rs, lo de elegir el adjunto.
o = leer(os.path.join(asis, "ocr.rs"))
open(os.path.join(dest, "ocr.rs"), "w", encoding="utf-8").write(
    "//! De `asistente/ocr.rs` del PC: qué es una imagen o un PDF y elegir el adjunto.\n\n"
    "use std::path::Path;\n\nuse super::turno::Adjunto;\n\n"
    + sacar(o, ["pub fn elegir<'a>(", "pub fn es_pdf(", "pub fn es_imagen("]) + "\n")

# 4. De documentos_acciones.rs, la tabla de una foto dibujada como en la foto.
d = leer(os.path.join(asis, "documentos_acciones.rs"))
open(os.path.join(dest, "documentos_acciones.rs"), "w", encoding="utf-8").write(
    "//! De `asistente/documentos_acciones.rs` del PC: la tabla de una foto al lienzo, con su aspecto\n"
    "//! (la usa el especialista de tablas de la hoja al lienzo).\n\n"
    "use std::path::Path;\n\n"
    "use pixpin_ia::acciones::normalizar;\n"
    "use pixpin_ia::tabla_escaneada::{self, Palabra, TablaLeida};\n"
    "use pixpin_motor2d::{ColorRgba, Elemento};\n"
    "use serde_json::{Value, json};\n\n"
    + sacar(d, [
        "const ANCHO_OCR", "pub fn palabras_de(", "fn palabras_de_ocr(", "pub fn leer_tabla(",
        "pub(crate) fn rayas_marcadas(", "pub fn aspecto_de_tabla(", "pub fn filas_de(", "fn con_filas(",
        "const LETRA_DE_TABLA", "pub fn tabla_de_fichero(", "fn se_parece(", "fn corregir_con_la_nube(",
    ]) + "\n")
# 5. Los enlaces pixpin:// del PC (`pixpin-shell/orden.rs`, puro): el lector, la tabla de verbos y la
#    seguridad, con lo poco de `mensajero`, `compartido` y `jump_list` que nombra.
sh = os.path.join(pc, "crates/pixpin-shell/src")
o = leer(os.path.join(sh, "orden.rs"))
i, f = pieza(o, "pub fn orden_del_protocolo(")
o = o[:i] + "/// En el teléfono `pixpin://` es de PixPin (lo dice su manifiesto).\npub fn orden_del_protocolo(_esquema: &str) -> Option<String> {\n    None\n}\n" + o[f:]
open(os.path.join(src, "orden.rs"), "w", encoding="utf-8").write(o)
os.makedirs(os.path.join(src, "orden"), exist_ok=True)
fi = leer(os.path.join(sh, "orden", "firma.rs"))
i, f = pieza(fi, "pub fn ruta_de_la_clave(")
fi = fi[:i] + ("/// Donde vive la clave en el teléfono (`files/claves/enlaces.clave`): la pone el puente.\n"
    "pub static CLAVE: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();\n\n"
    "pub fn ruta_de_la_clave() -> Option<PathBuf> {\n    CLAVE.get().cloned()\n}\n") + fi[f:]
i, f = pieza(fi, "fn al_azar(")
fi = fi[:i] + ("fn al_azar() -> std::io::Result<[u8; 32]> {\n    use std::io::Read;\n    let mut b = [0u8; 32];\n"
    "    std::fs::File::open(\"/dev/urandom\")?.read_exact(&mut b)?;\n    Ok(b)\n}\n") + fi[f:]
open(os.path.join(src, "orden", "firma.rs"), "w", encoding="utf-8").write(fi)
# Sus pruebas, sin la de los botones de los avisos de Windows (`notificacion`).
pr = leer(os.path.join(sh, "orden", "pruebas.rs"))
pr = quitar(pr, ["fn una_sola_lista_blanca_para_enlaces_y_botones_de_avisos("])
open(os.path.join(src, "orden", "pruebas.rs"), "w", encoding="utf-8").write(pr)
m = leer(os.path.join(sh, "mensajero.rs"))
open(os.path.join(src, "mensajero.rs"), "w", encoding="utf-8").write(
    "//! De `pixpin-shell/mensajero.rs` del PC: el tamaño, la versión y las acciones del protocolo de pedidos.\n\n"
    + sacar(m, ["pub const TOPE_PEDIDO", "pub const VERSION_PEDIDO", "pub mod respuesta", "pub const ACCIONES", "pub fn validar_pedido("]) + "\n")
c = leer(os.path.join(sh, "compartido.rs"))
open(os.path.join(src, "compartido.rs"), "w", encoding="utf-8").write(
    "//! De `pixpin-shell/compartido.rs` del PC: qué guarda el chat como foto.\n\nuse std::path::Path;\n\n" + sacar(c, ["pub fn es_foto("]) + "\n")
j = leer(os.path.join(sh, "jump_list.rs"))
open(os.path.join(src, "jump_list.rs"), "w", encoding="utf-8").write(
    "//! De `pixpin-shell/jump_list.rs` del PC: el nombre de la orden de los enlaces.\n\n" + sacar(j, ["pub const URL"]) + "\n")
print("traído del PC")
