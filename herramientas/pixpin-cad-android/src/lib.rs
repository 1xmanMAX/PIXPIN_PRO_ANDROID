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
#[path = "pc/convertir3d.rs"]
pub mod convertir3d;
#[path = "pc/modelo3d.rs"]
pub mod modelo3d;
#[path = "pc/proxy.rs"]
pub mod proxy;
#[path = "pc/bim/mod.rs"]
pub mod bim;
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
    // Varias, una por renglón: primero las del usuario, luego las de PixPin.
    let c = CARPETA_SHX.lock().map(|c| c.clone()).unwrap_or_default();
    c.lines().map(str::trim).filter(|l| !l.is_empty()).map(PathBuf::from).collect()
}

/// **Cuánto se estrecha la letra de reserva.** En el PC, un estilo cuya fuente
/// SHX no está (o que pide Arial Narrow) se escribe con Arial Narrow, que se
/// parece en ancho a las SHX; en Android no hay Arial Narrow y la reserva es
/// Roboto, tan ancha como Arial: los textos salían un 20 % más anchos y se
/// montaban unos sobre otros (lo vio el usuario el 9-oct-2026). Se estrecha
/// al ancho de Arial Narrow. Si el plano pide Arial (o nada), se deja.
pub(crate) fn estrecho_android(clave: &str, fichero: &str) -> f32 {
    const RESERVAS: [&str; 4] = ["robotostatic-regular.ttf", "roboto-regular.ttf", "droidsans.ttf", "notosans-regular.ttf"];
    let f = fichero.rsplit(['/', '\\']).next().unwrap_or(fichero).to_ascii_lowercase();
    if !RESERVAS.contains(&f.as_str()) {
        return 1.0;
    }
    let c = clave.to_ascii_lowercase();
    if c.is_empty() || (c.contains("arial") && !c.contains("narrow")) {
        return 1.0;
    }
    ANCHO_DE_ARIAL_NARROW
}

/// Arial Narrow mide de ancho un 82 % de Arial (la media de sus letras).
const ANCHO_DE_ARIAL_NARROW: f32 = 0.82;

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

/// Lee un modelo 3D —un plano DWG/DXF en 3D, un LandXML o un fichero de puntos de Civil 3D, un
/// IFC o un Revit— y lo deja en `salida` con el formato del PC (PX3D, `Modelo3d::a_bytes`).
pub fn convertir_3d_a(entrada: &Path, salida: &Path) -> Result<String, String> {
    let modelo = std::panic::catch_unwind(|| bim::convertir_fichero(entrada)).map_err(|_| "el lector de modelos se cayó".to_string())??;
    if modelo.vacio() {
        return Err("no tiene nada que se vea en 3D".into());
    }
    let temporal = salida.with_extension("tmp");
    std::fs::write(&temporal, modelo.a_bytes()).map_err(|e| e.to_string())?;
    std::fs::rename(&temporal, salida).map_err(|e| e.to_string())?;
    Ok(format!("{} vértices, {} elementos", modelo.vertices.len(), modelo.elementos.len()))
}

/// Un Revit pasado a IFC4 (lo enseña el croquis 3D, que ya lee IFC).
pub fn revit_a_ifc_en(entrada: &Path, salida: &Path) -> Result<(), String> {
    let ifc = std::panic::catch_unwind(|| bim::revit_a_ifc(entrada)).map_err(|_| "el lector de Revit se cayó".to_string())??;
    let temporal = salida.with_extension("tmp");
    std::fs::write(&temporal, ifc).map_err(|e| e.to_string())?;
    std::fs::rename(&temporal, salida).map_err(|e| e.to_string())
}

fn texto_de<'a>(env: &mut jni::JNIEnv<'a>, s: &jni::objects::JString<'a>) -> String {
    env.get_string(s).map(String::from).unwrap_or_default()
}

fn resultado(env: &mut jni::JNIEnv, r: Result<(), String>) -> jni::sys::jstring {
    match r {
        Ok(()) => std::ptr::null_mut(),
        Err(m) => env.new_string(m).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

/// `PlanoNativo.convertir3d(entrada, salida): String?` — null si salió bien.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_planos_PlanoNativo_convertir3d<'a>(
    mut env: jni::JNIEnv<'a>, _c: jni::objects::JClass<'a>, entrada: jni::objects::JString<'a>, salida: jni::objects::JString<'a>,
) -> jni::sys::jstring {
    let (e, s) = (texto_de(&mut env, &entrada), texto_de(&mut env, &salida));
    let r = convertir_3d_a(Path::new(&e), Path::new(&s)).map(|_| ());
    resultado(&mut env, r)
}

/// `PlanoNativo.revitAIfc(entrada, salida): String?` — null si salió bien.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_planos_PlanoNativo_revitAIfc<'a>(
    mut env: jni::JNIEnv<'a>, _c: jni::objects::JClass<'a>, entrada: jni::objects::JString<'a>, salida: jni::objects::JString<'a>,
) -> jni::sys::jstring {
    let (e, s) = (texto_de(&mut env, &entrada), texto_de(&mut env, &salida));
    let r = revit_a_ifc_en(Path::new(&e), Path::new(&s));
    resultado(&mut env, r)
}

/// `PlanoNativo.esDeCivil(ruta): Boolean` — un LandXML o un fichero de puntos (mirando dentro).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_planos_PlanoNativo_esDeCivil<'a>(
    mut env: jni::JNIEnv<'a>, _c: jni::objects::JClass<'a>, ruta: jni::objects::JString<'a>,
) -> jni::sys::jboolean {
    let r = texto_de(&mut env, &ruta);
    std::panic::catch_unwind(|| bim::es_de_civil(Path::new(&r))).unwrap_or(false) as jni::sys::jboolean
}
