//! **`pixpin-pdf` del PC, para lo que lo nombra la tabla de una foto**: aquí los PDF los lee Kotlin
//! (su capa de texto y su hoja pintada) y llegan como imagen; esto solo contesta que no.

use std::path::Path;

pub struct Documento;

impl Documento {
    pub fn abrir(_ruta: &Path) -> Result<Documento, String> {
        Err("los PDF los lee el teléfono".into())
    }
    pub fn paginas(&self) -> u32 {
        0
    }
    pub fn renderizar(&self, _pagina: u32, _ancho: u32) -> Result<shim_codec::ImagenRgba, String> {
        Err("los PDF los lee el teléfono".into())
    }
}

pub mod texto {
    #[derive(Debug, Clone, Default)]
    pub struct TrozoDeTexto {
        pub texto: String,
        pub x: f32,
        pub y: f32,
        pub fin: f32,
        pub alto: f32,
        pub negrita: bool,
    }

    pub fn trozos_de_fichero(_ruta: &std::path::Path) -> Result<Vec<Vec<TrozoDeTexto>>, String> {
        Err("los PDF los lee el teléfono".into())
    }
}
