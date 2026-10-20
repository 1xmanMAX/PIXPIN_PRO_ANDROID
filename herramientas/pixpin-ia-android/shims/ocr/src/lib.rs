//! **`pixpin-ocr` del PC con el OCR del teléfono**: mismas firmas; quien reconoce es ML Kit, por la
//! función que pone el puente JNI al arrancar ([`poner`]).

use std::sync::OnceLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Rect {
    pub x: i32,
    pub y: i32,
    pub ancho: u32,
    pub alto: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Palabra {
    pub caja: Rect,
    pub texto: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Linea {
    pub caja: Rect,
    pub texto: String,
    pub palabras: Vec<Palabra>,
}

#[derive(Debug)]
pub enum ErrorOcr {
    SinIdiomas,
    ImagenInvalida,
    Sistema(String),
}

impl std::fmt::Display for ErrorOcr {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ErrorOcr::SinIdiomas => f.write_str("el OCR del teléfono no está disponible (hace falta Google Play)"),
            ErrorOcr::ImagenInvalida => f.write_str("la imagen no es coherente con sus medidas"),
            ErrorOcr::Sistema(e) => write!(f, "el reconocimiento fallo: {e}"),
        }
    }
}

impl std::error::Error for ErrorOcr {}

/// El reconocedor del teléfono: RGBA de `ancho` x `alto` a sus líneas, o el motivo.
pub type Reconocedor = fn(u32, u32, &[u8]) -> Result<Vec<Linea>, String>;

static RECONOCEDOR: OnceLock<Reconocedor> = OnceLock::new();

pub fn poner(r: Reconocedor) {
    let _ = RECONOCEDOR.set(r);
}

pub fn disponible() -> bool {
    RECONOCEDOR.get().is_some()
}

pub fn reconocer(ancho: u32, alto: u32, pixeles_rgba: &[u8]) -> Result<Vec<Linea>, ErrorOcr> {
    let n = (ancho as usize).checked_mul(alto as usize).and_then(|n| n.checked_mul(4));
    match n {
        Some(n) if ancho > 0 && alto > 0 && pixeles_rgba.len() >= n => {}
        _ => return Err(ErrorOcr::ImagenInvalida),
    }
    let r = RECONOCEDOR.get().ok_or(ErrorOcr::SinIdiomas)?;
    r(ancho, alto, pixeles_rgba).map_err(ErrorOcr::Sistema)
}
