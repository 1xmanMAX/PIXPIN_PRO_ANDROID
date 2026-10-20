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
/// El plano por capas y a DXF, para exportarlo (14-oct-2026).
pub mod capas;
pub mod letras;

use std::path::{Path, PathBuf};
use std::sync::Mutex;

static CARPETA_SHX: Mutex<String> = Mutex::new(String::new());

/// Donde la app deja las fuentes SHX que el usuario quiera usar (en un
/// teléfono no hay AutoCAD que las traiga).
pub(crate) fn carpetas_shx_android() -> Vec<PathBuf> {
    // Varias, una por renglón: primero las del usuario, luego las de PixPin.
    let c = CARPETA_SHX.lock().map(|c| c.clone()).unwrap_or_default();
    c.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with("ttf:")).map(PathBuf::from).collect()
}

/// La carpeta de las TrueType de PixPin (el renglón «ttf:» de las carpetas que
/// manda la app): las que miden igual que las de Windows.
pub(crate) fn carpeta_ttf_android() -> Option<PathBuf> {
    let c = CARPETA_SHX.lock().map(|c| c.clone()).unwrap_or_default();
    c.lines().map(str::trim).find_map(|l| l.strip_prefix("ttf:")).filter(|l| !l.is_empty()).map(PathBuf::from)
}

/// **La de PixPin que mide igual que una letra de Windows** (por el nombre de
/// su fichero): Liberation Sans/Narrow/Serif/Mono hacen de Arial, Arial
/// Narrow, Times New Roman y Courier New con los mismos anchos; Carlito, de
/// Calibri; Caladea, de Cambria. Con Roboto en su lugar los textos salían más
/// anchos y altos que en AutoCAD y se montaban o se salían de sus recuadros
/// (el usuario, 11-oct-2026). Las que no tienen pareja exacta y se le parecen
/// (Swiss 721, Helvetica, Tahoma, Verdana) van a Liberation Sans.
pub(crate) fn fuente_parecida(fichero: &str) -> Option<&'static str> {
    let f = fichero.rsplit(['/', '\\']).next().unwrap_or(fichero).to_ascii_lowercase();
    Some(match f.as_str() {
        "arial.ttf" | "ariali.ttf" | "segoeui.ttf" | "swiss.ttf" | "swissl.ttf" | "helvetica.ttf" | "tahoma.ttf" | "verdana.ttf" => {
            if f == "ariali.ttf" { "LiberationSans-Italic.ttf" } else { "LiberationSans-Regular.ttf" }
        }
        "arialbd.ttf" | "arialbi.ttf" | "swissb.ttf" | "tahomabd.ttf" | "verdanab.ttf" | "segoeuib.ttf" => "LiberationSans-Bold.ttf",
        "arialn.ttf" | "arialni.ttf" | "swissc.ttf" | "swisscl.ttf" => "LiberationSansNarrow-Regular.ttf",
        "arialnb.ttf" | "arialnbi.ttf" | "swisscb.ttf" => "LiberationSansNarrow-Bold.ttf",
        "times.ttf" | "timesi.ttf" => "LiberationSerif-Regular.ttf",
        "timesbd.ttf" | "timesbi.ttf" => "LiberationSerif-Bold.ttf",
        "cour.ttf" | "couri.ttf" => "LiberationMono-Regular.ttf",
        "courbd.ttf" | "courbi.ttf" => "LiberationMono-Bold.ttf",
        "calibri.ttf" | "calibril.ttf" | "calibrii.ttf" => "Carlito-Regular.ttf",
        "calibrib.ttf" | "calibriz.ttf" => "Carlito-Bold.ttf",
        "cambria.ttc" | "cambria.ttf" | "cambriai.ttf" => "Caladea-Regular.ttf",
        "cambriab.ttf" | "cambriaz.ttf" => "Caladea-Bold.ttf",
        _ => return None,
    })
}

/// Las familias de Windows (como las nombra un MTEXT con `\f` o un estilo)
/// que tienen pareja en PixPin, con su fichero de Windows.
const FAMILIAS_DE_WINDOWS: [(&str, &str); 24] = [
    ("arial", "arial.ttf"),
    ("arial bold", "arialbd.ttf"),
    ("arial negrita", "arialbd.ttf"),
    ("arial italic", "ariali.ttf"),
    ("arial narrow", "arialn.ttf"),
    ("arial narrow bold", "arialnb.ttf"),
    ("arial narrow negrita", "arialnb.ttf"),
    ("times new roman", "times.ttf"),
    ("times new roman bold", "timesbd.ttf"),
    ("times new roman negrita", "timesbd.ttf"),
    ("courier new", "cour.ttf"),
    ("courier new bold", "courbd.ttf"),
    ("calibri", "calibri.ttf"),
    ("calibri bold", "calibrib.ttf"),
    ("calibri negrita", "calibrib.ttf"),
    ("cambria", "cambria.ttf"),
    ("cambria bold", "cambriab.ttf"),
    ("swis721 bt", "swiss.ttf"),
    ("swis721 cn bt", "swissc.ttf"),
    ("swis721 lt bt", "swissl.ttf"),
    ("helvetica", "helvetica.ttf"),
    ("tahoma", "tahoma.ttf"),
    ("verdana", "verdana.ttf"),
    ("segoe ui", "segoeui.ttf"),
];

/// Pone en `mapa` (familia → fichero) las familias de Windows que PixPin trae
/// con las mismas medidas. Mandan sobre las del sistema.
pub(crate) fn familias_android(mapa: &mut std::collections::HashMap<String, PathBuf>) {
    familias_del_usuario(mapa);
    let Some(carpeta) = carpeta_ttf_android() else { return };
    for (familia, fichero) in FAMILIAS_DE_WINDOWS {
        if let Some(f) = fuente_parecida(fichero) {
            let ruta = carpeta.join(f);
            if ruta.is_file() {
                mapa.entry(familia.to_string()).or_insert(ruta);
            }
        }
    }
}

/// Dónde leer la letra `nombre` (un fichero de Windows como «arialn.ttf», o
/// una ruta entera): la pareja de PixPin si la hay, si no la del sistema.
pub(crate) fn ruta_de_fuente(carpeta_sistema: &str, nombre: &str) -> PathBuf {
    // Las que puso el usuario (copiadas de su Windows o de su AutoCAD) mandan.
    if let Some(r) = del_usuario(nombre) {
        return r;
    }
    if let (Some(c), Some(f)) = (carpeta_ttf_android(), fuente_parecida(nombre)) {
        let r = c.join(f);
        if r.is_file() {
            return r;
        }
    }
    Path::new(carpeta_sistema).join(nombre)
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

/// Las carpetas de letras que manda la app (una por renglón).
pub(crate) fn poner_carpetas_shx(carpeta_shx: &str) {
    if let Ok(mut c) = CARPETA_SHX.lock() {
        *c = carpeta_shx.to_string();
    }
}

/// Lee `entrada` y escribe el modelo en `salida` (por un temporal y
/// `rename`: nadie ve nunca medio archivo). Devuelve un resumen.
pub fn convertir_a(entrada: &Path, salida: &Path, carpeta_shx: &str) -> Result<String, String> {
    poner_carpetas_shx(carpeta_shx);
    sacar_lo_que_falta();
    let (mut modelo, cuentas) = std::panic::catch_unwind(|| convertir::convertir_fichero(entrada))
        .map_err(|e| {
            e.downcast_ref::<String>()
                .cloned()
                .or_else(|| e.downcast_ref::<&str>().map(|s| s.to_string()))
                .unwrap_or_else(|| "el lector de planos se cayó".into())
        })??;
    // Las letras que pide el plano y no están, para decirlas en la app.
    for f in sacar_lo_que_falta() {
        modelo.sin_dibujar.push((format!("{FUENTE_QUE_FALTA}{f}"), 1));
    }
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

/// `PlanoNativo.capas(entrada, salida, carpetaShx): String?` — el plano por
/// capas (`PXCAPAS1`) para exportarlo; null si salió bien.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_planos_PlanoNativo_capas<'a>(
    mut env: jni::JNIEnv<'a>, _c: jni::objects::JClass<'a>, entrada: jni::objects::JString<'a>, salida: jni::objects::JString<'a>, shx: jni::objects::JString<'a>,
) -> jni::sys::jstring {
    let (e, s, f) = (texto_de(&mut env, &entrada), texto_de(&mut env, &salida), texto_de(&mut env, &shx));
    let r = capas::capas_a(Path::new(&e), Path::new(&s), &f).map(|_| ());
    resultado(&mut env, r)
}

/// `PlanoNativo.aDxf(entrada, salida): String?` — un DWG pasado a DXF; null si salió bien.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_forge_pixpin_planos_PlanoNativo_aDxf<'a>(
    mut env: jni::JNIEnv<'a>, _c: jni::objects::JClass<'a>, entrada: jni::objects::JString<'a>, salida: jni::objects::JString<'a>,
) -> jni::sys::jstring {
    let (e, s) = (texto_de(&mut env, &entrada), texto_de(&mut env, &salida));
    let r = capas::dxf_a(Path::new(&e), Path::new(&s));
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

// ------------------------------------------------------------- las letras del usuario

/// Las carpetas de letras del usuario: las de las SHX menos las de PixPin
/// (que acaban en «-pixpin»). Ahí deja la app las SHX, TTF, OTF y TTC que el
/// usuario copia de su Windows o de su AutoCAD: PixPin no puede traer las de
/// Autodesk ni las de Microsoft, que no se pueden repartir.
fn carpetas_del_usuario() -> Vec<PathBuf> {
    carpetas_shx_android().into_iter().filter(|c| !c.to_string_lossy().ends_with("-pixpin")).collect()
}

/// El fichero `nombre` (sin mirar mayúsculas) en las carpetas del usuario.
pub(crate) fn del_usuario(nombre: &str) -> Option<PathBuf> {
    let base = nombre.rsplit(['/', '\\']).next().unwrap_or(nombre).trim();
    if base.is_empty() || Path::new(nombre).is_absolute() {
        return None;
    }
    for c in carpetas_del_usuario() {
        let Ok(d) = std::fs::read_dir(&c) else { continue };
        for e in d.flatten() {
            if e.file_name().to_string_lossy().eq_ignore_ascii_case(base) {
                return Some(e.path());
            }
        }
    }
    None
}

/// Las familias de las TrueType del usuario, por su nombre de verdad (la
/// tabla `name`: «Arial Narrow», y con «bold» si es negrita), como el
/// registro de Windows.
fn familias_del_usuario(mapa: &mut std::collections::HashMap<String, PathBuf>) {
    for c in carpetas_del_usuario() {
        let Ok(d) = std::fs::read_dir(&c) else { continue };
        for e in d.flatten() {
            let ruta = e.path();
            let ext = ruta.extension().map(|x| x.to_string_lossy().to_ascii_lowercase()).unwrap_or_default();
            if !["ttf", "otf", "ttc"].contains(&ext.as_str()) {
                continue;
            }
            let Ok(datos) = std::fs::read(&ruta) else { continue };
            let Ok(cara) = ttf_parser::Face::parse(&datos, 0) else { continue };
            let nombre = |id: u16| {
                cara.names().into_iter().filter(|n| n.name_id == id && n.is_unicode()).find_map(|n| n.to_string())
            };
            let Some(familia) = nombre(ttf_parser::name_id::TYPOGRAPHIC_FAMILY).or_else(|| nombre(ttf_parser::name_id::FAMILY)) else { continue };
            let sub = nombre(ttf_parser::name_id::TYPOGRAPHIC_SUBFAMILY).or_else(|| nombre(ttf_parser::name_id::SUBFAMILY)).unwrap_or_default().to_lowercase();
            let familia = familia.trim().to_lowercase();
            let clave = if sub.is_empty() || sub == "regular" || sub == "normal" { familia.clone() } else { format!("{familia} {sub}") };
            mapa.insert(clave, ruta.clone());
            if sub.contains("bold") {
                mapa.insert(format!("{familia} negrita"), ruta.clone());
            }
        }
    }
}

/// **Las familias que se conocen**, rehechas si cambian las carpetas o lo
/// que hay en las del usuario (el proceso de los planos vive mucho y el
/// usuario puede añadir letras entre un plano y otro).
pub(crate) fn fuentes_android(sistema: &str) -> &'static std::collections::HashMap<String, PathBuf> {
    static CACHE: Mutex<Option<(String, &'static std::collections::HashMap<String, PathBuf>)>> = Mutex::new(None);
    let mut firma = CARPETA_SHX.lock().map(|c| c.clone()).unwrap_or_default();
    for c in carpetas_del_usuario() {
        if let Ok(d) = std::fs::read_dir(&c) {
            let mut n: Vec<String> = d.flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
            n.sort();
            firma.push_str(&n.join("|"));
        }
    }
    let mut g = CACHE.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((f, m)) = g.as_ref()
        && *f == firma
    {
        return m;
    }
    let mut mapa = std::collections::HashMap::new();
    // Las que miden como las de Windows (del usuario y de PixPin), primero: mandan.
    familias_android(&mut mapa);
    if let Ok(d) = std::fs::read_dir(sistema) {
        for e in d.flatten() {
            let ruta = e.path();
            let Some(base) = ruta.file_stem().map(|s| s.to_string_lossy().to_lowercase()) else { continue };
            mapa.entry(base.replace('-', " ")).or_insert_with(|| ruta.clone());
            if let Some(familia) = base.strip_suffix("-regular") {
                mapa.entry(familia.to_string()).or_insert_with(|| ruta.clone());
            }
        }
    }
    let m: &'static std::collections::HashMap<String, PathBuf> = Box::leak(Box::new(mapa));
    *g = Some((firma, m));
    m
}

// ------------------------------------------------------------- lo que falta

static FALTAN: Mutex<std::collections::BTreeSet<String>> = Mutex::new(std::collections::BTreeSet::new());

/// Apunta una letra que el plano pide y no está (se dice en la app, para que
/// el usuario la copie de su PC).
pub(crate) fn falta(nombre: &str) {
    let n = nombre.trim();
    if !n.is_empty()
        && let Ok(mut f) = FALTAN.lock()
    {
        f.insert(n.to_string());
    }
}

/// Una SHX que no está: solo si de verdad es SHX (la reserva la conoce o
/// termina en .shx); un estilo «Standard» sin fichero no cuenta.
pub(crate) fn falta_shx(clave: &str) {
    if crate::shx::reserva_para(clave).is_some() {
        let base = clave.rsplit(['/', '\\']).next().unwrap_or(clave);
        falta(&if base.to_ascii_lowercase().ends_with(".shx") { base.to_string() } else { format!("{base}.shx") });
    }
}

/// Una TrueType pedida por su fichero (el `font_file` de un estilo) que no
/// está ni tiene pareja en PixPin.
pub(crate) fn revisar_ttf(clave: &str, sistema: &str) {
    let c = clave.trim().to_ascii_lowercase();
    if c.starts_with("ttf:") || !(c.ends_with(".ttf") || c.ends_with(".otf") || c.ends_with(".ttc")) {
        return;
    }
    let base = c.rsplit(['\\', '/']).next().unwrap_or(&c).to_string();
    if !ruta_de_fuente(sistema, &base).is_file() {
        falta(&base);
    }
}

/// Lo apuntado desde la última vez, y se vacía.
pub(crate) fn sacar_lo_que_falta() -> Vec<String> {
    FALTAN.lock().map(|mut f| std::mem::take(&mut *f).into_iter().collect()).unwrap_or_default()
}

/// Cómo se apunta en `sin_dibujar` una letra que falta (lo lee la app).
pub const FUENTE_QUE_FALTA: &str = "fuente que falta: ";

#[cfg(test)]
mod pruebas_letras_del_usuario {
    #[test]
    fn la_letra_del_usuario_se_encuentra_y_no_falta() {
        let d = std::env::temp_dir().join(format!("pixpin-letras-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("MiaLetra.TTF"), b"x").unwrap();
        *super::CARPETA_SHX.lock().unwrap() = format!("{}\nttf:/no/existe", d.display());
        assert_eq!(super::del_usuario("mialetra.ttf"), Some(d.join("MiaLetra.TTF")));
        super::sacar_lo_que_falta();
        super::revisar_ttf("mialetra.ttf", "/system/fonts");
        super::revisar_ttf("rara.ttf", "/system/fonts");
        assert_eq!(super::sacar_lo_que_falta(), vec!["rara.ttf".to_string()]);
        let _ = std::fs::remove_dir_all(&d);
    }
}
