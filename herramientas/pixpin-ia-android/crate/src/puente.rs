//! **El puente con Kotlin** (`com.forge.pixpin.ia.HojaNativa.Puente`): lo que en Windows hace el
//! sistema. Cada llamada de Kotlin a la hoja pone su puente ([`empezar`]) y los hilos de la hoja
//! (los trabajadores de la nube, los de los diagramas) lo llaman pegándose a la máquina de Java.
//!
//! Los datos van en JSON y las imágenes en bytes: `[u32 largo][JSON][RGBA]` al leer, `[u32 ancho]
//! [u32 alto][RGBA]` al pintar, y `[u32 n]` y por imagen `[u32 largo][id][u32 ancho][u32 alto][RGBA]`
//! al entregar (todo en little endian).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

use jni::objects::{GlobalRef, JByteArray, JObject, JValue};
use jni::{JNIEnv, JavaVM};
use pixpin_ia::ErrorIa;
use pixpin_ia::motor::{Motor, Parte, Peticion, Respuesta, Rol, Seguir, Trozo};
use serde_json::{Value, json};

use crate::asistente::al_lienzo::EnHoja;
use crate::asistente::hoja_a_lienzo::{self, Destino};

/// La máquina de Java y el puente de la llamada en curso.
struct Puesto {
    vm: *mut jni::sys::JavaVM,
    puente: GlobalRef,
}

// SAFETY: el puntero de la JavaVM vale para todo el proceso y para cualquier hilo (JNI lo dice así);
// la referencia global también.
unsafe impl Send for Puesto {}

/// Las ranuras del puente: la de la hoja al lienzo y la de las conexiones (Drive, Calendar), que
/// van por separado para que una no espere a la otra.
pub const HOJA: usize = 0;
pub const CONEXION: usize = 1;

static PUESTOS: Mutex<[Option<Puesto>; 2]> = Mutex::new([None, None]);
/// Una llamada a la vez por ranura (su puente es uno).
static UNAS: [Mutex<()>; 2] = [Mutex::new(()), Mutex::new(())];

/// Mientras vive, el puente de esta llamada es el que usan todos los hilos.
pub struct Sesion {
    ranura: usize,
    _una: MutexGuard<'static, ()>,
}

impl Drop for Sesion {
    fn drop(&mut self) {
        if let Ok(mut p) = PUESTOS.lock() {
            p[self.ranura] = None;
        }
    }
}

pub fn empezar(env: &mut JNIEnv, puente: &JObject) -> Result<Sesion, String> {
    empezar_en(HOJA, env, puente)
}

pub fn empezar_en(ranura: usize, env: &mut JNIEnv, puente: &JObject) -> Result<Sesion, String> {
    let una = UNAS[ranura].lock().unwrap_or_else(|e| e.into_inner());
    let vm = env.get_java_vm().map_err(|e| e.to_string())?;
    let g = env.new_global_ref(puente).map_err(|e| e.to_string())?;
    PUESTOS.lock().unwrap_or_else(|e| e.into_inner())[ranura] = Some(Puesto { vm: vm.get_java_vm_pointer(), puente: g });
    pixpin_ocr::poner(ocr_de_kotlin);
    Ok(Sesion { ranura, _una: una })
}

/// Llama al puente de la hoja desde el hilo que sea.
fn llamar<T>(f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> Result<T, String> {
    llamar_en(HOJA, f)
}

/// Llama al puente de [ranura] desde el hilo que sea (pegándolo a Java si no lo estaba).
pub fn llamar_en<T>(ranura: usize, f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> Result<T, String> {
    let (vm, puente) = {
        let p = PUESTOS.lock().unwrap_or_else(|e| e.into_inner());
        let p = p[ranura].as_ref().ok_or("el puente con el teléfono no está puesto")?;
        (p.vm, p.puente.clone())
    };
    // SAFETY: el puntero salió de `get_java_vm` y la máquina vive lo que el proceso.
    let vm = unsafe { JavaVM::from_raw(vm) }.map_err(|e| e.to_string())?;
    let mut env = vm.attach_current_thread().map_err(|e| e.to_string())?;
    let r = f(&mut env, puente.as_obj());
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        return Err("el teléfono falló al hacerlo".into());
    }
    r.map_err(|e| e.to_string())
}

pub fn cadena(env: &mut JNIEnv, o: JObject) -> jni::errors::Result<String> {
    if o.is_null() {
        return Ok(String::new());
    }
    env.get_string(&o.into()).map(|s| s.into())
}

pub fn bytes(env: &mut JNIEnv, o: JObject) -> jni::errors::Result<Vec<u8>> {
    if o.is_null() {
        return Ok(Vec::new());
    }
    env.convert_byte_array(JByteArray::from(o))
}

pub fn u32_en(b: &[u8], i: usize) -> Option<u32> {
    b.get(i..i + 4).map(|x| u32::from_le_bytes([x[0], x[1], x[2], x[3]]))
}

// ------------------------------------------------------------------------------- leer la hoja

/// Lo que da el teléfono de una hoja: la imagen y, si es un PDF con texto, sus trozos (en puntos) y
/// el ancho de la página en puntos.
pub struct Leida {
    pub imagen: pixpin_codec::ImagenRgba,
    pub trozos: Option<(Vec<pixpin_pdf::texto::TrozoDeTexto>, f64)>,
}

pub fn leer(ruta: &std::path::Path, pagina: Option<usize>, ancho: u32) -> Result<Leida, String> {
    let ruta = ruta.to_string_lossy().to_string();
    let b = llamar(|env, p| {
        let r = env.new_string(&ruta)?;
        let o = env
            .call_method(p, "leer", "(Ljava/lang/String;II)[B", &[JValue::Object(&r), JValue::Int(pagina.map_or(-1, |x| x as i32)), JValue::Int(ancho as i32)])?
            .l()?;
        bytes(env, o)
    })?;
    let n = u32_en(&b, 0).ok_or("el teléfono no pudo leer la hoja")? as usize;
    let cabeza: Value = b.get(4..4 + n).and_then(|t| serde_json::from_slice(t).ok()).ok_or("el teléfono no pudo leer la hoja")?;
    if let Some(e) = cabeza["error"].as_str() {
        return Err(e.to_string());
    }
    let (w, h) = (cabeza["ancho"].as_u64().unwrap_or(0) as u32, cabeza["alto"].as_u64().unwrap_or(0) as u32);
    let pixeles = b.get(4 + n..).unwrap_or_default().to_vec();
    if w == 0 || h == 0 || pixeles.len() < (w * h * 4) as usize {
        return Err("la hoja no se pudo leer (sin píxeles)".into());
    }
    let trozos = cabeza["trozos"].as_array().map(|v| {
        let t = v
            .iter()
            .filter_map(|t| {
                let a = t.as_array()?;
                Some(pixpin_pdf::texto::TrozoDeTexto {
                    texto: a.first()?.as_str()?.to_string(),
                    x: a.get(1)?.as_f64()? as f32,
                    y: a.get(2)?.as_f64()? as f32,
                    fin: a.get(3)?.as_f64()? as f32,
                    alto: a.get(4)?.as_f64()? as f32,
                    negrita: a.get(5).and_then(Value::as_bool).unwrap_or(false),
                })
            })
            .collect();
        (t, cabeza["ancho_pt"].as_f64().unwrap_or(0.0))
    });
    Ok(Leida { imagen: pixpin_codec::ImagenRgba { ancho: w, alto: h, pixeles }, trozos })
}

// ------------------------------------------------------------------------------------- OCR

fn ocr_de_kotlin(w: u32, h: u32, rgba: &[u8]) -> Result<Vec<pixpin_ocr::Linea>, String> {
    let t = llamar(|env, p| {
        let a = env.byte_array_from_slice(rgba)?;
        let o = env.call_method(p, "ocr", "([BII)Ljava/lang/String;", &[JValue::Object(&a), JValue::Int(w as i32), JValue::Int(h as i32)])?.l()?;
        cadena(env, o)
    })?;
    let v: Value = serde_json::from_str(&t).map_err(|_| "el OCR del teléfono no contestó".to_string())?;
    if let Some(e) = v["error"].as_str() {
        return Err(e.to_string());
    }
    let caja = |a: &[Value]| pixpin_ocr::Rect {
        x: a.get(1).and_then(Value::as_f64).unwrap_or(0.0) as i32,
        y: a.get(2).and_then(Value::as_f64).unwrap_or(0.0) as i32,
        ancho: a.get(3).and_then(Value::as_f64).unwrap_or(0.0).max(0.0) as u32,
        alto: a.get(4).and_then(Value::as_f64).unwrap_or(0.0).max(0.0) as u32,
    };
    Ok(v.as_array()
        .map(|lineas| {
            lineas
                .iter()
                .filter_map(|l| {
                    let palabras: Vec<pixpin_ocr::Palabra> = l
                        .as_array()?
                        .iter()
                        .filter_map(|p| {
                            let a = p.as_array()?;
                            Some(pixpin_ocr::Palabra { texto: a.first()?.as_str()?.to_string(), caja: caja(a) })
                        })
                        .collect();
                    if palabras.is_empty() {
                        return None;
                    }
                    let (x0, y0) = (palabras.iter().map(|p| p.caja.x).min().unwrap_or(0), palabras.iter().map(|p| p.caja.y).min().unwrap_or(0));
                    let x1 = palabras.iter().map(|p| p.caja.x + p.caja.ancho as i32).max().unwrap_or(0);
                    let y1 = palabras.iter().map(|p| p.caja.y + p.caja.alto as i32).max().unwrap_or(0);
                    Some(pixpin_ocr::Linea {
                        caja: pixpin_ocr::Rect { x: x0, y: y0, ancho: (x1 - x0).max(0) as u32, alto: (y1 - y0).max(0) as u32 },
                        texto: palabras.iter().map(|p| p.texto.as_str()).collect::<Vec<_>>().join(" "),
                        palabras,
                    })
                })
                .collect()
        })
        .unwrap_or_default())
}

// --------------------------------------------------------------------------------- la nube

/// **El motor del asistente del teléfono** visto como el `Motor` del PC: la petición va en JSON
/// (las imágenes en base64) y vuelve el texto.
pub struct MotorKotlin {
    ve: bool,
    a_la_vez: usize,
}

pub fn motor() -> Option<Arc<dyn Motor>> {
    let t = llamar(|env, p| {
        let o = env.call_method(p, "motor", "()Ljava/lang/String;", &[])?.l()?;
        cadena(env, o)
    })
    .ok()?;
    let v: Value = serde_json::from_str(&t).ok()?;
    if !v["nube"].as_bool().unwrap_or(false) {
        return None;
    }
    Some(Arc::new(MotorKotlin { ve: v["ve"].as_bool().unwrap_or(true), a_la_vez: v["a_la_vez"].as_u64().unwrap_or(4).max(1) as usize }))
}

fn rol(r: Rol) -> &'static str {
    match r {
        Rol::Sistema => "sistema",
        Rol::Usuario => "usuario",
        Rol::Asistente => "asistente",
        Rol::Herramienta => "herramienta",
    }
}

fn peticion_json(p: &Peticion) -> String {
    let mensajes: Vec<Value> = p
        .mensajes
        .iter()
        .map(|m| {
            let partes: Vec<Value> = m
                .partes
                .iter()
                .map(|x| match x {
                    Parte::Texto(t) => json!({ "texto": t }),
                    Parte::Imagen { bytes, mime } => json!({ "imagen": pixpin_ia::base64::codificar(bytes), "mime": mime }),
                })
                .collect();
            json!({ "rol": rol(m.rol), "partes": partes })
        })
        .collect();
    json!({ "mensajes": mensajes, "max_tokens": p.max_tokens, "temperatura": p.temperatura }).to_string()
}

impl Motor for MotorKotlin {
    fn completar(&self, peticion: &Peticion, al_trozo: &mut dyn FnMut(&Trozo) -> Seguir) -> Result<Respuesta, ErrorIa> {
        let j = peticion_json(peticion);
        let t = llamar(|env, p| {
            let s = env.new_string(&j)?;
            let o = env.call_method(p, "completar", "(Ljava/lang/String;)Ljava/lang/String;", &[JValue::Object(&s)])?.l()?;
            cadena(env, o)
        })
        .map_err(ErrorIa::Nube)?;
        let v: Value = serde_json::from_str(&t).map_err(|_| ErrorIa::Nube("la nube no contestó".into()))?;
        if let Some(e) = v["error"].as_str() {
            return Err(ErrorIa::Nube(e.to_string()));
        }
        let texto = v["texto"].as_str().unwrap_or_default().to_string();
        let _ = al_trozo(&Trozo::Texto(texto.clone()));
        Ok(Respuesta::de_texto(texto))
    }

    fn catalogo_completo(&self) -> bool {
        true
    }

    fn ve_imagenes(&self) -> bool {
        self.ve
    }

    fn a_la_vez(&self) -> usize {
        self.a_la_vez
    }
}

// ----------------------------------------------------------------------- parar y progreso

/// **Parar**: lo que mira la hoja, puesto por un hilo que le pregunta al teléfono cada 150 ms.
pub struct Parar {
    bandera: Arc<AtomicBool>,
    acabado: Arc<AtomicBool>,
}

impl std::ops::Deref for Parar {
    type Target = AtomicBool;
    fn deref(&self) -> &AtomicBool {
        &self.bandera
    }
}

impl Drop for Parar {
    fn drop(&mut self) {
        self.acabado.store(true, Ordering::SeqCst);
    }
}

pub fn parar() -> Parar {
    let bandera = Arc::new(AtomicBool::new(false));
    let acabado = Arc::new(AtomicBool::new(false));
    let (b, a) = (bandera.clone(), acabado.clone());
    let _ = std::thread::Builder::new().name("hoja-parar".into()).spawn(move || {
        while !a.load(Ordering::SeqCst) {
            if llamar(|env, p| env.call_method(p, "parado", "()Z", &[])?.z()).unwrap_or(false) {
                b.store(true, Ordering::SeqCst);
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(150));
        }
    });
    Parar { bandera, acabado }
}

pub fn progreso(n: usize, total: usize) {
    let _ = llamar(|env, p| env.call_method(p, "progreso", "(II)V", &[JValue::Int(n as i32), JValue::Int(total as i32)]).map(|_| ()));
}

// ---------------------------------------------------------------------- el lienzo y pintar

fn empaquetar(imagenes: &[(u64, pixpin_codec::ImagenRgba)]) -> Vec<u8> {
    let mut v = Vec::new();
    v.extend_from_slice(&(imagenes.len() as u32).to_le_bytes());
    for (id, i) in imagenes {
        let id = pixpin_motor2d::enlace::id_de_texto(*id);
        v.extend_from_slice(&(id.len() as u32).to_le_bytes());
        v.extend_from_slice(id.as_bytes());
        v.extend_from_slice(&i.ancho.to_le_bytes());
        v.extend_from_slice(&i.alto.to_le_bytes());
        v.extend_from_slice(&i.pixeles);
    }
    v
}

/// **Cada zona hecha, al lienzo del teléfono** (lo que en el PC es `al_lienzo::pedir`): el documento
/// de Excalidraw de la zona, sus imágenes y dónde va ([`EnHoja`]).
pub struct DestinoKotlin;

impl Destino for DestinoKotlin {
    fn entregar(&mut self, h: Option<&pixpin_ia::hoja::montaje::Hecho>, en: EnHoja) -> Result<(), String> {
        let (documento, imagenes) = hoja_a_lienzo::documento_de(h);
        let en = json!({
            "sesion": en.sesion.to_string(), "zona": en.zona, "rehacer": en.rehacer, "ultima": en.ultima,
            "tam": [en.tam.0, en.tam.1], "junto_a": en.junto_a.map(|c| vec![c.0, c.1, c.2, c.3]),
        })
        .to_string();
        let paquete = empaquetar(&imagenes);
        let e = llamar(|env, p| {
            let d = env.new_string(&documento)?;
            let i = env.byte_array_from_slice(&paquete)?;
            let s = env.new_string(&en)?;
            let o = env
                .call_method(p, "entregar", "(Ljava/lang/String;[BLjava/lang/String;)Ljava/lang/String;", &[JValue::Object(&d), JValue::Object(&i), JValue::Object(&s)])?
                .l()?;
            cadena(env, o)
        })?;
        if e.is_empty() { Ok(()) } else { Err(e) }
    }
}

pub fn pintar(documento: &str, imagenes: &[(u64, pixpin_codec::ImagenRgba)], tam: (f32, f32), ancho: u32) -> Result<(Vec<u8>, u32, u32), String> {
    let paquete = empaquetar(imagenes);
    let b = llamar(|env, p| {
        let d = env.new_string(documento)?;
        let i = env.byte_array_from_slice(&paquete)?;
        let o = env
            .call_method(p, "pintar", "(Ljava/lang/String;[BFFI)[B", &[JValue::Object(&d), JValue::Object(&i), JValue::Float(tam.0), JValue::Float(tam.1), JValue::Int(ancho as i32)])?
            .l()?;
        bytes(env, o)
    })?;
    let (w, h) = (u32_en(&b, 0).ok_or("no se pudo pintar lo hecho")?, u32_en(&b, 4).ok_or("no se pudo pintar lo hecho")?);
    let px = b.get(8..).unwrap_or_default().to_vec();
    if w == 0 || h == 0 || px.len() < (w * h * 4) as usize {
        return Err("no se pudo pintar lo hecho".into());
    }
    Ok((px, w, h))
}
