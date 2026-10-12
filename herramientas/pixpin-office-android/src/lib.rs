//! **Word y PowerPoint del PC en Android** (11-oct-2026).
//!
//! La maquetación de Word y PowerPoint (`pixpin-office`) es la del PC, copiada sin tocar en `pc/`
//! (ver `traer-del-pc.sh`). Lo de aquí es lo de Android:
//!
//! - [`letras`]: el medidor, con los TTF del teléfono y los de PixPin que miden como los de
//!   Windows (el PC mide con DirectWrite);
//! - [`pxof`]: cada página maquetada, en bytes, con la cara, el glifo y la x de cada letra, para
//!   que Kotlin la pinte en vectores (el PC pinta con Direct2D);
//! - la entrada JNI (`com.forge.pixpin.office.OfficeNativo`);
//! - y las hojas de Excel ([`hojas`]: `pixpin-hojas` del PC; JNI `com.forge.pixpin.excel.HojasNativo`).
//!
//! Todo se llama desde un hilo de la app que no es el de la pantalla. Un documento roto que
//! haga caer la lectura no tumba la app: cada llamada va dentro de `catch_unwind`.

pub mod hojas;
pub mod letras;
pub mod pxof;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::{Arc, Mutex};

use pixpin_office::{Documento, Tipo};

use crate::letras::{Carpetas, Letras};

/// Las carpetas de letras que mandó la app (`configurar`).
static CARPETAS: Mutex<String> = Mutex::new(String::new());

pub fn carpetas() -> Carpetas {
    Carpetas::de_texto(&CARPETAS.lock().map(|c| c.clone()).unwrap_or_default())
}

pub fn configurar(texto: &str) {
    if let Ok(mut c) = CARPETAS.lock() {
        *c = texto.to_string();
    }
}

/// Un Word o un PowerPoint abierto, con las letras con que se mide.
pub struct DocAndroid {
    pub doc: Documento,
    pub letras: Letras,
}

impl DocAndroid {
    /// Lee el paquete y sus estilos, sin maquetar nada (milisegundos).
    pub fn abrir(ruta: &Path, carpetas: Carpetas) -> Result<DocAndroid, String> {
        let doc = catch_unwind(|| Documento::abrir(ruta))
            .map_err(|_| "la lectura del documento se cayó".to_string())?
            .map_err(|e| e.to_string())?;
        Ok(DocAndroid { doc, letras: Letras::nuevo(carpetas) })
    }

    pub fn tipo(&self) -> Tipo {
        self.doc.tipo()
    }

    /// La página `n` (desde 0) en PXOF, maquetándola si hace falta (en Word, con las de antes).
    pub fn pagina(&mut self, n: usize) -> Option<Vec<u8>> {
        let DocAndroid { doc, letras } = self;
        catch_unwind(AssertUnwindSafe(|| {
            let p = doc.pagina(n, &*letras)?;
            Some(pxof::Escritor::pagina(letras, &p))
        }))
        .ok()
        .flatten()
    }

    pub fn imagen(&self, id: &str) -> Option<Arc<[u8]>> {
        self.doc.recursos().bytes(id)
    }
}

// ------------------------------------------------------------------------------------------ JNI

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};

static ULTIMO_ERROR: Mutex<String> = Mutex::new(String::new());

fn texto_de<'a>(env: &mut JNIEnv<'a>, s: &JString<'a>) -> String {
    env.get_string(s).map(String::from).unwrap_or_default()
}

fn bytes_java(env: &mut JNIEnv, b: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(b).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn doc<'a>(h: jlong) -> Option<&'a Mutex<DocAndroid>> {
    if h == 0 {
        return None;
    }
    // SAFETY: `h` es el puntero que dio `abrir` (Box::into_raw) y Kotlin no lo usa tras `cerrar`.
    Some(unsafe { &*(h as *const Mutex<DocAndroid>) })
}

/// `OfficeNativo.configurar(carpetas)`: dónde están las letras (ver [`Carpetas::de_texto`]).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_office_OfficeNativo_configurar<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, t: JString<'a>) {
    let t = texto_de(&mut env, &t);
    configurar(&t);
}

/// `OfficeNativo.abrir(ruta): Long` — 0 si no se pudo (el porqué, en `ultimoError`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_office_OfficeNativo_abrir<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, ruta: JString<'a>) -> jlong {
    let ruta = texto_de(&mut env, &ruta);
    match DocAndroid::abrir(Path::new(&ruta), carpetas()) {
        Ok(d) => Box::into_raw(Box::new(Mutex::new(d))) as jlong,
        Err(e) => {
            if let Ok(mut u) = ULTIMO_ERROR.lock() {
                *u = e;
            }
            0
        }
    }
}

/// `OfficeNativo.ultimoError(): String?`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_office_OfficeNativo_ultimoError<'a>(env: JNIEnv<'a>, _c: JClass<'a>) -> jstring {
    let e = ULTIMO_ERROR.lock().map(|e| e.clone()).unwrap_or_default();
    env.new_string(e).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// `OfficeNativo.tipo(h): Int` — 0 Word, 1 PowerPoint, -1 nada.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_office_OfficeNativo_tipo<'a>(_env: JNIEnv<'a>, _c: JClass<'a>, h: jlong) -> jint {
    doc(h).and_then(|d| d.lock().ok().map(|d| if d.tipo() == Tipo::Word { 0 } else { 1 })).unwrap_or(-1)
}

/// `OfficeNativo.pagina(h, n): ByteArray?` — la página en PXOF, o null si no la hay.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_office_OfficeNativo_pagina<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, h: jlong, n: jint) -> jbyteArray {
    let Some(d) = doc(h) else { return std::ptr::null_mut() };
    let b = match d.lock() {
        Ok(mut d) => d.pagina(n.max(0) as usize),
        Err(_) => None,
    };
    match b {
        Some(b) => bytes_java(&mut env, &b),
        None => std::ptr::null_mut(),
    }
}

/// `OfficeNativo.listas(h): Int` — páginas ya maquetadas (Word) o diapositivas.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_office_OfficeNativo_listas<'a>(_env: JNIEnv<'a>, _c: JClass<'a>, h: jlong) -> jint {
    doc(h).and_then(|d| d.lock().ok().map(|d| d.doc.paginas_listas() as jint)).unwrap_or(0)
}

/// `OfficeNativo.total(h): Int` — el total si se sabe, si no la estimación de Word.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_office_OfficeNativo_total<'a>(_env: JNIEnv<'a>, _c: JClass<'a>, h: jlong) -> jint {
    doc(h).and_then(|d| d.lock().ok().map(|d| d.doc.total() as jint)).unwrap_or(0)
}

/// `OfficeNativo.terminado(h): Boolean` — si ya se sabe cuántas páginas hay.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_office_OfficeNativo_terminado<'a>(_env: JNIEnv<'a>, _c: JClass<'a>, h: jlong) -> jboolean {
    doc(h).and_then(|d| d.lock().ok().map(|d| d.doc.terminado() as jboolean)).unwrap_or(1)
}

/// `OfficeNativo.imagen(h, id): ByteArray?` — los bytes de una imagen tal como vienen.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_office_OfficeNativo_imagen<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, h: jlong, id: JString<'a>) -> jbyteArray {
    let id = texto_de(&mut env, &id);
    let b = doc(h).and_then(|d| d.lock().ok().and_then(|d| d.imagen(&id)));
    match b {
        Some(b) => bytes_java(&mut env, &b),
        None => std::ptr::null_mut(),
    }
}

/// `OfficeNativo.cerrar(h)`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_office_OfficeNativo_cerrar<'a>(_env: JNIEnv<'a>, _c: JClass<'a>, h: jlong) {
    if h != 0 {
        // SAFETY: el puntero de `abrir`, que Kotlin cierra una sola vez.
        drop(unsafe { Box::from_raw(h as *mut Mutex<DocAndroid>) });
    }
}

// ------------------------------------------------------------------------------- JNI de Excel

use crate::hojas::LibroAndroid;

fn libro<'a>(h: jlong) -> Option<&'a Mutex<LibroAndroid>> {
    if h == 0 {
        return None;
    }
    // SAFETY: `h` es el puntero que dio `HojasNativo.abrir` (Box::into_raw) y Kotlin no lo usa
    // tras `cerrar`.
    Some(unsafe { &*(h as *const Mutex<LibroAndroid>) })
}

/// Hace `f` con el libro, recogiendo un fallo de la lectura (un libro raro no tumba la app).
fn con_libro<T>(h: jlong, f: impl FnOnce(&mut LibroAndroid) -> Option<T>) -> Option<T> {
    let m = libro(h)?;
    let mut l = match m.lock() {
        Ok(l) => l,
        Err(e) => e.into_inner(),
    };
    catch_unwind(AssertUnwindSafe(|| f(&mut l))).ok().flatten()
}

fn o_null(env: &mut JNIEnv, b: Option<Vec<u8>>) -> jbyteArray {
    match b {
        Some(b) => bytes_java(env, &b),
        None => std::ptr::null_mut(),
    }
}

/// `HojasNativo.abrir(ruta, regional): Long` — 0 si no se pudo (el porqué, en `ultimoError`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_excel_HojasNativo_abrir<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, ruta: JString<'a>, regional: JString<'a>) -> jlong {
    let ruta = texto_de(&mut env, &ruta);
    let regional = hojas::regional_de(&texto_de(&mut env, &regional));
    let r = catch_unwind(|| LibroAndroid::abrir(Path::new(&ruta), regional)).unwrap_or_else(|_| Err("la lectura del libro se cayó".into()));
    match r {
        Ok(l) => Box::into_raw(Box::new(Mutex::new(l))) as jlong,
        Err(e) => {
            if let Ok(mut u) = ULTIMO_ERROR.lock() {
                *u = e;
            }
            0
        }
    }
}

/// `HojasNativo.ultimoError(): String?`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_excel_HojasNativo_ultimoError<'a>(env: JNIEnv<'a>, c: JClass<'a>) -> jstring {
    Java_com_forge_pixpin_office_OfficeNativo_ultimoError(env, c)
}

/// `HojasNativo.libro(h): ByteArray?` — PXHL.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_excel_HojasNativo_libro<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, h: jlong) -> jbyteArray {
    let b = con_libro(h, |l| Some(l.bytes_libro()));
    o_null(&mut env, b)
}

/// `HojasNativo.abrirHoja(h, i, hasta): Boolean` — abre la hoja y lee hasta esa fila.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_excel_HojasNativo_abrirHoja<'a>(_env: JNIEnv<'a>, _c: JClass<'a>, h: jlong, i: jint, hasta: jint) -> jboolean {
    con_libro(h, |l| Some(l.abrir_hoja(i.max(0) as usize, hasta.max(0) as u32))).unwrap_or(false) as jboolean
}

/// `HojasNativo.cargar(h, i, celdas): Int` — 1 ya entera, 0 falta, -1 no se pudo.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_excel_HojasNativo_cargar<'a>(_env: JNIEnv<'a>, _c: JClass<'a>, h: jlong, i: jint, celdas: jint) -> jint {
    match con_libro(h, |l| l.cargar(i.max(0) as usize, celdas.max(1) as usize)) {
        Some(true) => 1,
        Some(false) => 0,
        None => -1,
    }
}

/// `HojasNativo.geometria(h, i): ByteArray?` — PXHG.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_excel_HojasNativo_geometria<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, h: jlong, i: jint) -> jbyteArray {
    let b = con_libro(h, |l| l.bytes_geometria(i.max(0) as usize));
    o_null(&mut env, b)
}

/// `HojasNativo.trozo(h, i, f0, f1, conocidos): ByteArray?` — PXHT.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_excel_HojasNativo_trozo<'a>(
    mut env: JNIEnv<'a>,
    _c: JClass<'a>,
    h: jlong,
    i: jint,
    f0: jint,
    f1: jint,
    conocidos: jint,
) -> jbyteArray {
    let b = con_libro(h, |l| l.bytes_trozo(i.max(0) as usize, f0.max(0) as u32, f1.max(0) as u32, conocidos.max(0) as u32));
    o_null(&mut env, b)
}

/// `HojasNativo.copiar(h, i, f0, c0, f1, c1): String?` — texto con tabuladores.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_excel_HojasNativo_copiar<'a>(
    env: JNIEnv<'a>,
    _c: JClass<'a>,
    h: jlong,
    i: jint,
    f0: jint,
    c0: jint,
    f1: jint,
    c1: jint,
) -> jstring {
    let r = pixpin_hojas::Rango { fila0: f0.max(0) as u32, col0: c0.max(0) as u32, fila1: f1.max(f0).max(0) as u32, col1: c1.max(c0).max(0) as u32 };
    match con_libro(h, |l| l.copiar(i.max(0) as usize, r)) {
        Some(s) => env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// `HojasNativo.imagen(h, ruta): ByteArray?` — los bytes de una parte del ZIP (una imagen).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_excel_HojasNativo_imagen<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, h: jlong, ruta: JString<'a>) -> jbyteArray {
    let ruta = texto_de(&mut env, &ruta);
    let b = con_libro(h, |l| l.libro.parte(&ruta));
    o_null(&mut env, b)
}

/// `HojasNativo.cerrar(h)`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_excel_HojasNativo_cerrar<'a>(_env: JNIEnv<'a>, _c: JClass<'a>, h: jlong) {
    if h != 0 {
        // SAFETY: el puntero de `abrir`, que Kotlin cierra una sola vez.
        drop(unsafe { Box::from_raw(h as *mut Mutex<LibroAndroid>) });
    }
}
