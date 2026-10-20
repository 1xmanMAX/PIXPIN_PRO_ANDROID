//! De `asistente/ocr.rs` del PC: qué es una imagen o un PDF y elegir el adjunto.

use std::path::Path;

use super::turno::Adjunto;

/// El adjunto que se pide: por nombre (sin mayusculas, vale un trozo) o, sin
/// nombre, el ultimo.
pub fn elegir<'a>(adjuntos: &'a [Adjunto], nombre: Option<&str>) -> Result<&'a Adjunto, String> {
    if adjuntos.is_empty() {
        return Err("no hay ningun PDF ni imagen adjunto en esta conversacion; pide al usuario que lo adjunte".into());
    }
    let Some(nombre) = nombre.map(str::trim).filter(|n| !n.is_empty()) else {
        return Ok(adjuntos.last().expect("no esta vacia"));
    };
    let buscado = nombre.to_lowercase();
    adjuntos
        .iter()
        .rev()
        .find(|a| a.nombre.to_lowercase() == buscado)
        .or_else(|| adjuntos.iter().rev().find(|a| a.nombre.to_lowercase().contains(&buscado)))
        .ok_or_else(|| {
            let hay: Vec<&str> = adjuntos.iter().map(|a| a.nombre.as_str()).collect();
            format!("no hay ningun adjunto llamado «{nombre}»; hay: {}", hay.join(", "))
        })
}

pub fn es_pdf(ruta: &Path) -> bool {
    ruta.extension().is_some_and(|e| e.eq_ignore_ascii_case("pdf"))
}

pub fn es_imagen(ruta: &Path) -> bool {
    ruta.extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_ascii_lowercase())
        .is_some_and(|e| matches!(e.as_str(), "png" | "jpg" | "jpeg" | "bmp" | "gif" | "webp" | "tif" | "tiff" | "heic"))
}

