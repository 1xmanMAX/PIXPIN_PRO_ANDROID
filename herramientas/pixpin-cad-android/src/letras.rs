//! **Las letras del usuario y las que faltan, con los nombres del PC** (20-oct-2026). El PC se llevó
//! a `pixpin-cad/src/letras.rs` lo que hacía este crate (las del usuario, las que faltan) y ahora su
//! `texto.rs` y su `shx.rs` llaman a `crate::letras::…`. Aquí esas llamadas van a lo de Android
//! (`lib.rs`): las carpetas que manda la app, las familias de PixPin que miden como las de Windows.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// Las carpetas de letras que dijo la app (las del usuario y las de PixPin).
pub fn carpetas_del_usuario() -> Vec<PathBuf> {
    crate::carpetas_shx_android()
}

/// Las familias del usuario por su nombre de verdad.
pub fn familias_del_usuario() -> HashMap<String, PathBuf> {
    let mut m = HashMap::new();
    crate::familias_del_usuario(&mut m);
    m
}

pub fn falta(nombre: &str) {
    crate::falta(nombre)
}

pub fn falta_shx(clave: &str) {
    crate::falta_shx(clave)
}

pub fn revisar_ttf(clave: &str, sistema: &Path) {
    crate::revisar_ttf(clave, &sistema.to_string_lossy())
}

pub fn ruta_de_fuente(sistema: &Path, nombre: &str) -> PathBuf {
    crate::ruta_de_fuente(&sistema.to_string_lossy(), nombre)
}
