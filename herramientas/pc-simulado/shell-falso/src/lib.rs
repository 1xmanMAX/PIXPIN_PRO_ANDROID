//! Solo para probar fuera de Windows: las uniones de directorio, como enlaces simbólicos.
pub mod union {
    use std::io;
    use std::path::{Path, PathBuf};
    pub fn crear(enlace: &Path, destino: &Path) -> io::Result<()> { std::os::unix::fs::symlink(destino, enlace) }
    pub fn es_union(ruta: &Path) -> bool { std::fs::symlink_metadata(ruta).map(|m| m.file_type().is_symlink()).unwrap_or(false) }
    pub fn destino(ruta: &Path) -> Option<PathBuf> { std::fs::read_link(ruta).ok() }
    pub fn quitar(ruta: &Path) -> io::Result<()> { std::fs::remove_file(ruta) }
    pub fn es_de_red(_ruta: &Path) -> bool { false }
    pub fn sin_prefijo_largo(ruta: &Path) -> PathBuf { ruta.to_path_buf() }
}
