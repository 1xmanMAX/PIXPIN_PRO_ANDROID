//! El `asistente` de la app del PC, lo que necesita la hoja al lienzo: los ficheros del PC traídos
//! (`hoja_a_lienzo`, la parte de la tabla de `documentos_acciones`, lo de `ocr`) y, para lo que en
//! Windows es de la ventana, lo mínimo con sus mismos nombres (`al_lienzo::EnHoja`, `turno::Adjunto`).

// Los ficheros del PC, tal cual: lo que allí usa la ventana y aquí no, sin avisos.
#[allow(dead_code, unused_imports, clippy::all)]
pub mod documentos_acciones;
#[allow(dead_code, unused_imports, unused_variables, clippy::all)]
pub mod hoja_a_lienzo;
#[allow(dead_code)]
pub mod ocr;

pub mod al_lienzo {
    /// Dónde va una zona de la hoja en el lienzo (la del PC, `al_lienzo::EnHoja`).
    #[derive(Debug, Clone, Copy, PartialEq)]
    pub struct EnHoja {
        pub sesion: u64,
        pub zona: usize,
        pub rehacer: bool,
        pub ultima: bool,
        /// Lo que mide la hoja en el lienzo.
        pub tam: (f32, f32),
        /// La imagen de la que sale (su caja en el lienzo), si estaba en él.
        pub junto_a: Option<(f32, f32, f32, f32)>,
    }
}

pub mod turno {
    /// Un adjunto del chat: como lo ve el usuario y dónde está.
    #[derive(Debug, Clone, PartialEq)]
    pub struct Adjunto {
        pub nombre: String,
        pub ruta: std::path::PathBuf,
    }
}
