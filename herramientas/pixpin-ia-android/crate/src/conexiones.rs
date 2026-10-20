//! **Las conexiones del asistente del PC, en el teléfono** (`pixpin_ia::conexiones`, 17/18-oct-2026):
//! Google Drive (buscar, leer, bajar, subir) y Google Calendar (solo ver), con el mismo OAuth
//! (PKCE y vuelta a `127.0.0.1`, que en Android también vale) y el mismo guardado.
//!
//! Lo de Windows lo pone Kotlin por su puente (`com.forge.pixpin.ia.ConexionesNativas.Puente`):
//! la red (en el PC, WinHTTP: aquí `HttpURLConnection`, por el gancho de `nube::http::pedir`), el
//! cifrado de lo guardado (en el PC, DPAPI: aquí la Keystore de Android, por el de `nube::claves`),
//! abrir el navegador y lo del chat (elegir un archivo, su texto, dejar uno, preguntar «¿Sí?»).

use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jlong, jstring};
use pixpin_ia::conexiones::{self, Conexion, Servicio, acciones, calendario};
use serde_json::{Value, json};

use crate::puente::{self, CONEXION, bytes, cadena, llamar_en, u32_en};

fn texto(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn devolver(env: &mut JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn resultado(r: std::thread::Result<Result<Value, String>>) -> Value {
    match r {
        Ok(Ok(v)) => json!({ "ok": v }),
        Ok(Err(e)) => json!({ "error": e }),
        Err(_) => json!({ "error": "la conexion fallo por dentro" }),
    }
}

// ---------------------------------------------------------------- la red y el cifrado

/// `nube::http::pedir` del PC, por Kotlin: `[u32 código][u32 largo][JSON {ubicacion?, esperar?, error?}][cuerpo]`.
fn pedir(metodo: &str, url: &str, cabeceras: &[(&'static str, String)], cuerpo: &[u8], limite: usize) -> Result<pixpin_ia::nube::http::Respuesta, pixpin_ia::nube::http::Fallo> {
    use pixpin_ia::nube::http::{Fallo, Respuesta};
    let cab: Value = cabeceras.iter().map(|(k, v)| json!([k, v])).collect::<Vec<_>>().into();
    let cab = cab.to_string();
    let b = llamar_en(CONEXION, |env, p| {
        let (m, u, c) = (env.new_string(metodo)?, env.new_string(url)?, env.new_string(&cab)?);
        let cu = env.byte_array_from_slice(cuerpo)?;
        let o = env
            .call_method(
                p,
                "http",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[BI)[B",
                &[JValue::Object(&m), JValue::Object(&u), JValue::Object(&c), JValue::Object(&cu), JValue::Int(limite.min(i32::MAX as usize) as i32)],
            )?
            .l()?;
        bytes(env, o)
    })
    .map_err(Fallo::Red)?;
    let codigo = u32_en(&b, 0).ok_or_else(|| Fallo::Red("sin respuesta".into()))?;
    let n = u32_en(&b, 4).unwrap_or(0) as usize;
    let cabeza: Value = b.get(8..8 + n).and_then(|t| serde_json::from_slice(t).ok()).unwrap_or(json!({}));
    if let Some(e) = cabeza["error"].as_str() {
        return Err(Fallo::Red(e.to_string()));
    }
    let resto = b.get(8 + n..).unwrap_or_default().to_vec();
    if !(200..300).contains(&codigo) {
        let esperar = cabeza["esperar"].as_u64().map(std::time::Duration::from_secs);
        let texto = String::from_utf8_lossy(&resto[..resto.len().min(2000)]).to_string();
        return Err(Fallo::Http { codigo, cuerpo: texto, esperar });
    }
    Ok(Respuesta { codigo, cuerpo: resto, ubicacion: cabeza["ubicacion"].as_str().map(str::to_string) })
}

fn cifrar_con(metodo: &str, datos: &[u8]) -> Result<Vec<u8>, String> {
    let b = llamar_en(CONEXION, |env, p| {
        let d = env.byte_array_from_slice(datos)?;
        let o = env.call_method(p, metodo, "([B)[B", &[JValue::Object(&d)])?.l()?;
        bytes(env, o)
    })?;
    if b.is_empty() { Err(format!("no se pudo {metodo} lo guardado de la conexion")) } else { Ok(b) }
}

fn cifrar(d: &[u8]) -> Result<Vec<u8>, String> {
    cifrar_con("cifrar", d)
}

fn descifrar(d: &[u8]) -> Result<Vec<u8>, String> {
    cifrar_con("descifrar", d)
}

fn empezar(env: &mut JNIEnv, p: &JObject) -> Result<puente::Sesion, String> {
    let s = puente::empezar_en(CONEXION, env, p)?;
    let _ = pixpin_ia::nube::http::PEDIDOR.set(pedir);
    let _ = pixpin_ia::nube::claves::CIFRADOR.set((cifrar, descifrar));
    Ok(s)
}

fn servicio(id: &str) -> Result<&'static Servicio, String> {
    conexiones::por_id(id).ok_or_else(|| format!("«{id}» no es un servicio que se pueda conectar"))
}

// ------------------------------------------------------------------------ lo del chat

/// Lo del chat del teléfono para las herramientas de las conexiones.
struct DelChat;

impl acciones::Chat for DelChat {
    fn archivo(&self, descripcion: Option<&str>) -> Result<(String, PathBuf), String> {
        let t = llamar_en(CONEXION, |env, p| {
            let d = env.new_string(descripcion.unwrap_or(""))?;
            let o = env.call_method(p, "archivo", "(Ljava/lang/String;)Ljava/lang/String;", &[JValue::Object(&d)])?.l()?;
            cadena(env, o)
        })?;
        let v: Value = serde_json::from_str(&t).map_err(|_| "el chat no contesto".to_string())?;
        if let Some(e) = v["error"].as_str() {
            return Err(e.to_string());
        }
        Ok((v["nombre"].as_str().unwrap_or_default().to_string(), PathBuf::from(v["ruta"].as_str().unwrap_or_default())))
    }

    fn texto_de(&self, ruta: &Path) -> Result<String, String> {
        let r = ruta.to_string_lossy().to_string();
        let t = llamar_en(CONEXION, |env, p| {
            let d = env.new_string(&r)?;
            let o = env.call_method(p, "textoDe", "(Ljava/lang/String;)Ljava/lang/String;", &[JValue::Object(&d)])?.l()?;
            cadena(env, o)
        })?;
        let v: Value = serde_json::from_str(&t).map_err(|_| "el lector no contesto".to_string())?;
        match v["error"].as_str() {
            Some(e) => Err(e.to_string()),
            None => Ok(v["texto"].as_str().unwrap_or_default().to_string()),
        }
    }

    fn al_chat(&self, ruta: &Path, frase: &str) -> Result<(), String> {
        let r = ruta.to_string_lossy().to_string();
        let e = llamar_en(CONEXION, |env, p| {
            let (a, b) = (env.new_string(&r)?, env.new_string(frase)?);
            let o = env.call_method(p, "alChat", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", &[JValue::Object(&a), JValue::Object(&b)])?.l()?;
            cadena(env, o)
        })?;
        if e.is_empty() { Ok(()) } else { Err(e) }
    }

    fn confirmar(&self, pregunta: &str) -> bool {
        llamar_en(CONEXION, |env, p| {
            let a = env.new_string(pregunta)?;
            env.call_method(p, "confirmar", "(Ljava/lang/String;)Z", &[JValue::Object(&a)])?.z()
        })
        .unwrap_or(false)
    }

    fn carpeta(&self) -> PathBuf {
        llamar_en(CONEXION, |env, p| {
            let o = env.call_method(p, "carpeta", "()Ljava/lang/String;", &[])?.l()?;
            cadena(env, o)
        })
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::temp_dir().join("pixpin-conexiones"))
    }
}

// ------------------------------------------------------------------------------ JNI

/// `hacer(herramienta, args, carpeta, puente)`: una de las herramientas de las conexiones (buscar,
/// leer, bajar, subir). `{"ok": "lo que se le cuenta al modelo"}` o `{"error": …}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_ConexionesNativas_hacer<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass<'a>,
    herramienta: JString<'a>,
    args: JString<'a>,
    carpeta: JString<'a>,
    p: JObject<'a>,
) -> jstring {
    let (h, args, carpeta) = (texto(&mut env, &herramienta), texto(&mut env, &args), PathBuf::from(texto(&mut env, &carpeta)));
    let _s = match empezar(&mut env, &p) {
        Ok(s) => s,
        Err(e) => return devolver(&mut env, json!({ "error": e })),
    };
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Value, String> {
        let herramienta = *acciones::NOMBRES.iter().find(|n| **n == h).ok_or_else(|| format!("«{h}» no es de las conexiones"))?;
        let a = pixpin_ia::acciones::AccionApp {
            herramienta,
            pedido: serde_json::from_str(&args).unwrap_or(json!({})),
            confirmar: None,
            por_resolver: Default::default(),
        };
        acciones::hacer(&a, &|s| Conexion::abrir_en(s, s.extremos(), &carpeta), &DelChat).map(Value::String)
    }));
    let v = resultado(r);
    devolver(&mut env, v)
}

/// `estado(servicio, carpeta, puente)`: `{"ok": {"cliente": id?, "conectado": bool, "cuenta": …?}}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_ConexionesNativas_estado<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass<'a>,
    id: JString<'a>,
    carpeta: JString<'a>,
    p: JObject<'a>,
) -> jstring {
    let (id, carpeta) = (texto(&mut env, &id), PathBuf::from(texto(&mut env, &carpeta)));
    let _s = match empezar(&mut env, &p) {
        Ok(s) => s,
        Err(e) => return devolver(&mut env, json!({ "error": e })),
    };
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Value, String> {
        let s = servicio(&id)?;
        let g = conexiones::guardado::leer_de(&carpeta, s);
        Ok(json!({ "cliente": g.cliente.as_ref().map(|c| c.id.clone()), "conectado": g.conectado(), "cuenta": g.cuenta, "nombre": s.nombre, "consola": s.consola }))
    }));
    let v = resultado(r);
    devolver(&mut env, v)
}

/// `guardarCliente(servicio, pegado, carpeta, puente)`: el ID de cliente pegado (o el JSON de Google).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_ConexionesNativas_guardarCliente<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass<'a>,
    id: JString<'a>,
    pegado: JString<'a>,
    carpeta: JString<'a>,
    p: JObject<'a>,
) -> jstring {
    let (id, pegado, carpeta) = (texto(&mut env, &id), texto(&mut env, &pegado), PathBuf::from(texto(&mut env, &carpeta)));
    let _s = match empezar(&mut env, &p) {
        Ok(s) => s,
        Err(e) => return devolver(&mut env, json!({ "error": e })),
    };
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Value, String> {
        let c = conexiones::guardar_cliente_en(servicio(&id)?, &carpeta, &pegado)?;
        Ok(json!(c.id))
    }));
    let v = resultado(r);
    devolver(&mut env, v)
}

/// `conectar(servicio, carpeta, puente)`: abre el navegador (por el puente) y espera la vuelta
/// (como mucho cinco minutos, o hasta `parado`). Bloquea: desde un hilo de fondo. `{"ok": cuenta}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_ConexionesNativas_conectar<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass<'a>,
    id: JString<'a>,
    carpeta: JString<'a>,
    p: JObject<'a>,
) -> jstring {
    let (id, carpeta) = (texto(&mut env, &id), PathBuf::from(texto(&mut env, &carpeta)));
    let _s = match empezar(&mut env, &p) {
        Ok(s) => s,
        Err(e) => return devolver(&mut env, json!({ "error": e })),
    };
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Value, String> {
        let s = servicio(&id)?;
        let parar = AtomicBool::new(false);
        let abrir = |url: &str| -> Result<(), String> {
            let e = llamar_en(CONEXION, |env, p| {
                let u = env.new_string(url)?;
                let o = env.call_method(p, "abrir", "(Ljava/lang/String;)Ljava/lang/String;", &[JValue::Object(&u)])?.l()?;
                cadena(env, o)
            })?;
            if e.is_empty() { Ok(()) } else { Err(e) }
        };
        // (Parar lo pregunta la espera cada 50 ms; un vigía le pregunta al teléfono.)
        let acabado = AtomicBool::new(false);
        std::thread::scope(|sc| {
            sc.spawn(|| {
                use std::sync::atomic::Ordering;
                while !acabado.load(Ordering::SeqCst) {
                    if llamar_en(CONEXION, |env, p| env.call_method(p, "parado", "()Z", &[])?.z()).unwrap_or(false) {
                        parar.store(true, Ordering::SeqCst);
                        break;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(150));
                }
            });
            let cuenta = conexiones::conectar_en(s, &s.extremos(), &carpeta, &abrir, &parar, std::time::Duration::from_secs(300));
            acabado.store(true, std::sync::atomic::Ordering::SeqCst);
            cuenta.map(Value::String)
        })
    }));
    let v = resultado(r);
    devolver(&mut env, v)
}

/// `desconectar(servicio, carpeta, puente)`: borra los tokens (el ID de cliente se queda) y retira el permiso.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_ConexionesNativas_desconectar<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass<'a>,
    id: JString<'a>,
    carpeta: JString<'a>,
    p: JObject<'a>,
) -> jstring {
    let (id, carpeta) = (texto(&mut env, &id), PathBuf::from(texto(&mut env, &carpeta)));
    let _s = match empezar(&mut env, &p) {
        Ok(s) => s,
        Err(e) => return devolver(&mut env, json!({ "error": e })),
    };
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Value, String> {
        let s = servicio(&id)?;
        conexiones::desconectar_en(s, &s.extremos(), &carpeta).map(|_| Value::Null)
    }));
    let v = resultado(r);
    devolver(&mut env, v)
}

/// `eventos(desde_ms, hasta_ms, carpeta, puente)`: los de Google Calendar del tramo (UTC), con la forma
/// del caché del PC (`calendario::a_json`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_ia_ConexionesNativas_eventos<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass<'a>,
    desde: jlong,
    hasta: jlong,
    carpeta: JString<'a>,
    p: JObject<'a>,
) -> jstring {
    let carpeta = PathBuf::from(texto(&mut env, &carpeta));
    let _s = match empezar(&mut env, &p) {
        Ok(s) => s,
        Err(e) => return devolver(&mut env, json!({ "error": e })),
    };
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Value, String> {
        let s = &calendario::GOOGLE_CALENDAR;
        let cx = Conexion::abrir_en(s, s.extremos(), &carpeta)?;
        calendario::eventos(&cx, desde, hasta).map(|e| calendario::a_json(&e))
    }));
    let v = resultado(r);
    devolver(&mut env, v)
}
