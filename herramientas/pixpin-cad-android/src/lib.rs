//! **Planos DWG y DXF en Android** (9-oct-2026): la lectura del visor del PC
//! (`crates/pixpin-cad` de PIXPIN_PRO_WINDOWS, copiada en `pc/` por
//! `traer-del-pc.sh`) con una sola entrada para la app: convertir un plano al
//! formato de la caché del PC (PXCAD v3, `Modelo::a_bytes`). Lo dibuja
//! Kotlin con OpenGL ES (`planos/` en la app).
//!
//! Se llama desde un proceso aparte (`:planos`): si un plano roto tumba la
//! lectura, solo cae ese proceso.

#[path = "pc/convertir.rs"]
pub mod convertir;
#[path = "pc/leer.rs"]
pub mod leer;
#[path = "pc/modelo.rs"]
pub mod modelo;
#[path = "pc/relleno.rs"]
pub mod relleno;
#[path = "pc/shx.rs"]
pub mod shx;
#[path = "pc/teselar.rs"]
pub mod teselar;
#[path = "pc/texto.rs"]
pub mod texto;

use std::path::{Path, PathBuf};
use std::sync::Mutex;

static CARPETA_SHX: Mutex<String> = Mutex::new(String::new());

/// Donde la app deja las fuentes SHX que el usuario quiera usar (en un
/// teléfono no hay AutoCAD que las traiga).
pub(crate) fn carpetas_shx_android() -> Vec<PathBuf> {
    let c = CARPETA_SHX.lock().map(|c| c.clone()).unwrap_or_default();
    if c.is_empty() { Vec::new() } else { vec![PathBuf::from(c)] }
}

/// Lee `entrada` y escribe el modelo en `salida` (por un temporal y
/// `rename`: nadie ve nunca medio archivo). Devuelve un resumen.
pub fn convertir_a(entrada: &Path, salida: &Path, carpeta_shx: &str) -> Result<String, String> {
    if let Ok(mut c) = CARPETA_SHX.lock() {
        *c = carpeta_shx.to_string();
    }
    let (modelo, cuentas) = std::panic::catch_unwind(|| convertir::convertir_fichero(entrada))
        .map_err(|e| {
            e.downcast_ref::<String>()
                .cloned()
                .or_else(|| e.downcast_ref::<&str>().map(|s| s.to_string()))
                .unwrap_or_else(|| "el lector de planos se cayó".into())
        })??;
    let temporal = salida.with_extension("tmp");
    std::fs::write(&temporal, modelo.a_bytes()).map_err(|e| e.to_string())?;
    std::fs::rename(&temporal, salida).map_err(|e| e.to_string())?;
    Ok(format!(
        "{} entidades, {} letras, {} MB en la tarjeta",
        cuentas.entidades,
        cuentas.letras,
        modelo.bytes_gpu() / 1_000_000
    ))
}

/// `PlanoNativo.convertir(entrada, salida, carpetaShx): String?` — null si
/// salió bien; si no, por qué.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_planos_PlanoNativo_convertir<'a>(
    mut env: jni::JNIEnv<'a>,
    _clase: jni::objects::JClass<'a>,
    entrada: jni::objects::JString<'a>,
    salida: jni::objects::JString<'a>,
    shx: jni::objects::JString<'a>,
) -> jni::sys::jstring {
    let mut texto = |s: &jni::objects::JString<'a>| -> String { env.get_string(s).map(String::from).unwrap_or_default() };
    let (e, s, f) = (texto(&entrada), texto(&salida), texto(&shx));
    match convertir_a(Path::new(&e), Path::new(&s), &f) {
        Ok(_) => std::ptr::null_mut(),
        Err(m) => env.new_string(m).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}
