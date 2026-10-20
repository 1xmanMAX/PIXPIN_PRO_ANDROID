//! De `pixpin-shell/compartido.rs` del PC: qué guarda el chat como foto.

use std::path::Path;

/// Lo que el chat guarda como foto (el resto, como adjunto: un GIF animado o
/// un SVG se quedan tal cual).
pub fn es_foto(ruta: &Path) -> bool {
    let ext = ruta
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    matches!(ext.as_str(), "png" | "jpg" | "jpeg" | "bmp")
}

