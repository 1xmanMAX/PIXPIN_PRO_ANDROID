//! **Los enlaces `pixpin://` del PC, en el teléfono** (`pixpin-shell/orden.rs`, 15-oct-2026):
//! `pixpin://<verbo>/<trozos>?campo=valor` (y `pixpinmax://`, y la seña de los atajos
//! `pixpin://atajo/…`) a los pedidos del protocolo de siempre, con la misma tabla de verbos y la
//! misma **seguridad**: lo inofensivo pasa directo, lo demás pregunta, y lo que abre una ruta que no
//! es de este aparato o crea una automatización no pasa nunca. Los hace el teléfono
//! (`asistente/EnlacesDelTelefono.kt`).

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use serde_json::{Value, json};

use crate::orden::{self, Orden, Origen, Seguridad};

fn texto(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn seguridad(p: &Value, firmado: bool) -> String {
    if firmado {
        return "directa".into();
    }
    match orden::seguridad(p) {
        Seguridad::Directa => "directa".into(),
        Seguridad::Confirmar(m) => format!("confirmar:{m}"),
        Seguridad::Prohibida(m) => format!("prohibida:{m}"),
    }
}

/// Lo que dice un enlace: `{"pedidos": [{"pedido", "seguridad", "dice"}]}`, `{"ayuda"}` o
/// `{"error", "codigo"}`.
pub fn leer(url: &str) -> Value {
    match orden::de_enlace(url) {
        Ok(l) => {
            let firmado = matches!(l.origen, Origen::Enlace { firmado: true });
            match l.orden {
                Orden::Pedidos(v) => json!({
                    "pedidos": v.iter().map(|p| json!({ "pedido": p, "seguridad": seguridad(p, firmado), "dice": orden::describir(p) })).collect::<Vec<_>>(),
                    "firmado": firmado,
                }),
                Orden::Ayuda => json!({ "ayuda": orden::ayuda() }),
                _ => json!({ "error": "eso no se puede pedir por enlace", "codigo": orden::codigo::RECHAZADO }),
            }
        }
        Err(f) => json!({ "error": f.motivo, "codigo": f.codigo }),
    }
}

/// `leer(url, clave): String`; [clave]: donde vive la clave de los enlaces firmados de este aparato.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_EnlacesNativos_leer<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, url: JString<'a>, clave: JString<'a>) -> jstring {
    let (url, clave) = (texto(&mut env, &url), texto(&mut env, &clave));
    if !clave.is_empty() {
        let _ = orden::firma::CLAVE.set(std::path::PathBuf::from(clave));
    }
    let v = std::panic::catch_unwind(|| leer(&url)).unwrap_or_else(|_| json!({ "error": "el enlace no se pudo leer" }));
    env.new_string(v.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// `enlace(verbo, campos)`: arma un enlace de la casa (`pixpin://verbo?…`), sin firmar.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_EnlacesNativos_armar<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, verbo: JString<'a>, campos: JString<'a>) -> jstring {
    let (verbo, campos) = (texto(&mut env, &verbo), texto(&mut env, &campos));
    let pares: Vec<(String, String)> = serde_json::from_str::<serde_json::Map<String, Value>>(&campos)
        .unwrap_or_default()
        .into_iter()
        .map(|(k, v)| (k, v.as_str().map(str::to_string).unwrap_or_else(|| v.to_string())))
        .collect();
    let refs: Vec<(&str, &str)> = pares.iter().map(|(k, v)| (k.as_str(), v.as_str())).collect();
    let s = orden::enlace_con("pixpin", &verbo, &refs);
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
