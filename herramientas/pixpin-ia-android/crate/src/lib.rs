//! **libpixpinia.so: la hoja al lienzo del PC en el teléfono** (14 al 18-oct-2026 en Windows).
//!
//! Lo que hace el PC (`pixpin_ia::hoja`: seccionar, recuadros, montar cada zona, diagramas exactos,
//! comparar; y la orquestación de la app, `hoja_a_lienzo.rs` con su planificador y su supervisor)
//! corre aquí tal cual. Lo que en Windows es del sistema lo hace Kotlin, por un **puente** que se
//! le pasa a cada llamada ([`puente`]): leer la foto o la página del PDF, el OCR (ML Kit), la nube
//! (el motor del asistente del teléfono), meter cada zona en el lienzo y pintar lo hecho para
//! comprobarlo.
//!
//! Entrada desde Kotlin: `com.forge.pixpin.ia.HojaNativa.hacer(args, adjuntos, semilla, puente)`,
//! que devuelve JSON `{"ok": "…"}` o `{"error": "…"}`; y `esLamina(ruta, puente)`.

pub mod asistente;
pub mod compartido;
pub mod conexiones;
pub mod enlaces;
pub mod jump_list;
pub mod mensajero;
#[allow(dead_code, unused_imports)]
pub mod orden;
pub mod puente;

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jlong, jstring};
use serde_json::{Value, json};

use asistente::hoja_a_lienzo;

fn texto(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn devolver(env: &mut JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// `hacer(args: String, adjuntos: String, semilla: Long, puente: Puente): String`.
/// `adjuntos`: `[{"nombre": "...", "ruta": "..."}]`, del más viejo al más nuevo.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_HojaNativa_hacer<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass<'a>,
    args: JString<'a>,
    adjuntos: JString<'a>,
    semilla: jlong,
    puente: JObject<'a>,
) -> jstring {
    let args = texto(&mut env, &args);
    let adjuntos = texto(&mut env, &adjuntos);
    let _sesion = match puente::empezar(&mut env, &puente) {
        Ok(s) => s,
        Err(e) => return devolver(&mut env, json!({ "error": e })),
    };
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let args: Value = serde_json::from_str(&args).unwrap_or(json!({}));
        let adjuntos: Vec<asistente::turno::Adjunto> = serde_json::from_str::<Vec<Value>>(&adjuntos)
            .unwrap_or_default()
            .into_iter()
            .filter_map(|a| {
                Some(asistente::turno::Adjunto {
                    nombre: a["nombre"].as_str().unwrap_or_default().to_string(),
                    ruta: std::path::PathBuf::from(a["ruta"].as_str()?),
                })
            })
            .collect();
        let vision = puente::motor();
        let parar = puente::parar();
        let mut destino = puente::DestinoKotlin;
        let mut progreso = |n: usize, total: usize| puente::progreso(n, total);
        hoja_a_lienzo::hacer(&args, &adjuntos, vision, semilla as u64, &parar, &mut destino, &mut progreso)
    }));
    let v = match r {
        Ok(Ok(t)) => json!({ "ok": t }),
        Ok(Err(e)) => json!({ "error": e }),
        Err(_) => json!({ "error": "la hoja al lienzo fallo por dentro (un fallo del motor del PC)" }),
    };
    devolver(&mut env, v)
}

/// `esLamina(ruta: String, puente: Puente): Boolean`: si una imagen es una lámina (texto y figuras) y
/// lo que se pide calcar se pasa entonces como hoja (PC `lamina_de_calcar`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_HojaNativa_esLamina<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass<'a>,
    ruta: JString<'a>,
    puente: JObject<'a>,
) -> jboolean {
    let ruta = texto(&mut env, &ruta);
    let Ok(_sesion) = puente::empezar(&mut env, &puente) else { return 0 };
    let si = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| hoja_a_lienzo::leer(std::path::Path::new(&ruta), None).map(|p| hoja_a_lienzo::es_lamina(&p)).unwrap_or(false)))
        .unwrap_or(false);
    si as jboolean
}

/// `lamina(ruta: String, ancho: Double, semilla: Long, puente: Puente): String`: **una lámina que se
/// pidió calcar, pasada como hoja** (PC `ejecutor::lamina_al_lienzo`): con la nube y si es lámina, el
/// texto rico de la nube y figuras exactas, al ancho de la imagen (`ancho` ≤ 0: el de la original).
/// `{"ok": …}`, `{"no": true}` si no es una lámina (entonces se calca) o `{"error": …}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_HojaNativa_lamina<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass<'a>,
    ruta: JString<'a>,
    ancho: f64,
    semilla: jlong,
    puente: JObject<'a>,
) -> jstring {
    let ruta = texto(&mut env, &ruta);
    let _sesion = match puente::empezar(&mut env, &puente) {
        Ok(s) => s,
        Err(e) => return devolver(&mut env, json!({ "error": e })),
    };
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Option<String>, String> {
        let vision = puente::motor();
        let p = pixpin_ia::calcar::Parametros::default();
        let Some(pagina) = hoja_a_lienzo::lamina_de_calcar(std::path::Path::new(&ruta), &p, vision.as_ref()) else {
            return Ok(None);
        };
        let mut o = pixpin_ia::hoja::Opciones::default();
        o.rico = true;
        o.ancho = if ancho > 0.0 { ancho } else { pagina.ancho as f64 }.clamp(200.0, 4000.0);
        let parar = puente::parar();
        let mut destino = puente::DestinoKotlin;
        let mut progreso = |n: usize, total: usize| puente::progreso(n, total);
        let inf = hoja_a_lienzo::pasar(&pagina, vision, &o, false, semilla as u64, &parar, &mut destino, &mut progreso)?;
        let r = hoja_a_lienzo::contar(&pagina, &inf)?;
        Ok(Some(format!("{r} (era una lamina con texto y figuras: se paso con figuras exactas y texto editable, no calcada)")))
    }));
    let v = match r {
        Ok(Ok(Some(t))) => json!({ "ok": t }),
        Ok(Ok(None)) => json!({ "no": true }),
        Ok(Err(e)) => json!({ "error": e }),
        Err(_) => json!({ "error": "la hoja al lienzo fallo por dentro (un fallo del motor del PC)" }),
    };
    devolver(&mut env, v)
}
