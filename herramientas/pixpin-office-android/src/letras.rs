//! **Las letras de Office en Android**: el [`Medidor`] de la maquetación, con los mismos TTF con
//! que Kotlin pinta después.
//!
//! En el PC mide DirectWrite y pinta glifo a glifo con esos mismos avances
//! (`pixpin-render::pagina_office`). Aquí se hace igual, pero leyendo los archivos de letra con
//! `ttf-parser`: cada carácter se mide en la cara que lo va a pintar, y esa cara (su archivo y su
//! índice en una colección) viaja a Kotlin con cada trozo de texto ([`crate::pxof`]). Así donde
//! la maquetación partió un renglón es exactamente donde se ve partido, sin un solo JNI por letra.
//!
//! Las letras de Windows que no hay en un teléfono se cambian por las de PixPin que miden igual
//! (las mismas que ya usa el visor de planos, `assets/fuentes-ttf`): Carlito por Calibri, Caladea
//! por Cambria, Liberation por Arial, Arial Narrow, Times New Roman y Courier New. Las demás, por
//! su nombre entre las del sistema y las que añadió el usuario; si no, de palo seco o con remates
//! según su nombre (como el PC: Arial o Times New Roman). Lo que no tenga la letra pedida sale de
//! una de reserva (símbolos, matemáticas, CJK, emoji...).

use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use pixpin_office::medidor::{Medidor, Metricas};
use pixpin_office::pagina::Letra;

/// Dónde buscar las letras.
#[derive(Debug, Clone, Default)]
pub struct Carpetas {
    /// Las de PixPin que miden como las de Windows (Liberation, Carlito, Caladea).
    pub pixpin: Option<PathBuf>,
    /// Las que añadió el usuario (botón «Aa» del visor de planos): mandan.
    pub usuario: Option<PathBuf>,
    /// Las del sistema.
    pub sistema: PathBuf,
}

impl Carpetas {
    /// De lo que manda la app, un renglón por carpeta: `pixpin:<ruta>`, `usuario:<ruta>`,
    /// `sistema:<ruta>` (por defecto `/system/fonts`).
    pub fn de_texto(t: &str) -> Carpetas {
        let mut c = Carpetas { sistema: PathBuf::from("/system/fonts"), ..Default::default() };
        for l in t.lines().map(str::trim) {
            if let Some(r) = l.strip_prefix("pixpin:") {
                c.pixpin = Some(PathBuf::from(r));
            } else if let Some(r) = l.strip_prefix("usuario:") {
                c.usuario = Some(PathBuf::from(r));
            } else if let Some(r) = l.strip_prefix("sistema:") {
                c.sistema = PathBuf::from(r);
            }
        }
        c
    }
}

/// Un archivo de letra abierto una vez para todo el proceso (mapeado, no leído: una colección
/// CJK son decenas de megas y solo se tocan las páginas que se usan).
fn datos(ruta: &Path) -> Option<&'static [u8]> {
    static ABIERTOS: OnceLock<Mutex<HashMap<PathBuf, Option<&'static [u8]>>>> = OnceLock::new();
    let m = ABIERTOS.get_or_init(Default::default);
    let mut m = m.lock().ok()?;
    if let Some(d) = m.get(ruta) {
        return *d;
    }
    let d = std::fs::File::open(ruta).ok().and_then(|f| {
        // SAFETY: el archivo es de solo lectura (las letras del sistema o de la app) y el mapa
        // vive hasta que acaba el proceso (se filtra a propósito: hay uno por archivo de letra).
        let mapa = unsafe { memmap2::Mmap::map(&f) }.ok()?;
        let mapa: &'static memmap2::Mmap = Box::leak(Box::new(mapa));
        Some(&mapa[..])
    });
    m.insert(ruta.to_path_buf(), d);
    d
}

/// Una cara de letra ya abierta.
pub struct Cara {
    pub ruta: String,
    pub indice: u32,
    cara: ttf_parser::Face<'static>,
    em: f32,
    /// Ascenso, descenso, hueco, posición y grosor del subrayado, posición y grosor del
    /// tachado, en fracción de em (como `Cara::m` del PC).
    pub m: [f32; 7],
    /// La negrita y la cursiva que hay que fingir al pintar (el archivo no las trae).
    pub negrita_falsa: bool,
    pub cursiva_falsa: bool,
}

impl Cara {
    fn abrir(ruta: &Path, indice: u32, negrita_falsa: bool, cursiva_falsa: bool) -> Option<Cara> {
        let d = datos(ruta)?;
        let cara = ttf_parser::Face::parse(d, indice).ok()?;
        let em = cara.units_per_em().max(1) as f32;
        // Las medidas que da DirectWrite (las del PC): las de Windows del OS/2, con el hueco que
        // falta para llegar a las del hhea; o las tipográficas si la letra pide usarlas.
        let hhea = cara.tables().hhea;
        let (mut asc, mut desc, mut hueco) = (hhea.ascender as f32, (hhea.descender as f32).abs(), hhea.line_gap.max(0) as f32);
        if let Some(os2) = cara.tables().os2 {
            if os2.use_typographic_metrics() {
                asc = os2.typographic_ascender() as f32;
                desc = (os2.typographic_descender() as f32).abs();
                hueco = os2.typographic_line_gap().max(0) as f32;
            } else if os2.windows_ascender() != 0 || os2.windows_descender() != 0 {
                let (wa, wd) = (os2.windows_ascender() as f32, (os2.windows_descender() as f32).abs());
                hueco = (hhea.ascender as f32 + (hhea.descender as f32).abs() + hhea.line_gap as f32 - wa - wd).max(0.0);
                asc = wa;
                desc = wd;
            }
        }
        let sub = cara.underline_metrics();
        let tach = cara.strikeout_metrics();
        let m = [
            asc / em,
            desc / em,
            hueco / em,
            sub.map(|s| s.position as f32 / em).unwrap_or(-0.1),
            sub.map(|s| (s.thickness.max(1)) as f32 / em).unwrap_or(0.05),
            tach.map(|s| s.position as f32 / em).unwrap_or(0.3),
            tach.map(|s| (s.thickness.max(1)) as f32 / em).unwrap_or(0.05),
        ];
        Some(Cara { ruta: ruta.to_string_lossy().into_owned(), indice, cara, em, m, negrita_falsa, cursiva_falsa })
    }

    /// El glifo de un carácter y su avance en em. Glifo 0 = no lo tiene.
    fn glifo(&self, c: char) -> (u16, f32) {
        match self.cara.glyph_index(c) {
            Some(g) if g.0 != 0 => (g.0, self.cara.glyph_hor_advance(g).unwrap_or(0) as f32 / self.em),
            _ => (0, 0.0),
        }
    }
}

/// Una letra que se puede abrir: archivo, índice en la colección y lo que habrá que fingir.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct Eleccion {
    ruta: PathBuf,
    indice: u32,
    negrita_falsa: bool,
    cursiva_falsa: bool,
}

/// Las letras de PixPin que miden como las de Windows: su grupo por el nombre de la familia.
fn grupo_de_pixpin(familia: &str) -> Option<&'static str> {
    Some(match familia {
        "calibri" | "calibri light" | "carlito" | "aptos" | "aptos display" | "aptos narrow" | "aptos light" | "aptos serif" => "Carlito",
        "cambria" | "cambria math" | "caladea" => "Caladea",
        "arial" | "helvetica" | "liberation sans" | "arimo" | "arial unicode ms" | "helvetica neue" => "LiberationSans",
        "arial narrow" | "liberation sans narrow" => "LiberationSansNarrow",
        "times new roman" | "times" | "liberation serif" | "tinos" => "LiberationSerif",
        "courier new" | "courier" | "liberation mono" | "cousine" => "LiberationMono",
        _ => return None,
    })
}

/// Como el PC (`es_serif`): si una familia que no se encuentra es de las de remates.
pub fn es_serif(familia: &str) -> bool {
    let f = familia.to_ascii_lowercase();
    ["times", "serif", "georgia", "garamond", "cambria", "book", "roman", "palatino", "bodoni", "minion", "century", "baskerville", "mincho", "song"]
        .iter()
        .any(|k| f.contains(k))
        && !f.contains("sans")
}

/// Si una familia que no se encuentra es de ancho fijo.
fn es_fija(familia: &str) -> bool {
    let f = familia.to_ascii_lowercase();
    ["mono", "consolas", "courier", "code", "lucida console", "fixed"].iter().any(|k| f.contains(k))
}

/// Las del sistema y las del usuario por el nombre de su familia (tabla `name`), una vez por
/// proceso y por carpeta: (familia en minúsculas) → [(archivo, índice, negrita, cursiva)].
type Indice = HashMap<String, Vec<(PathBuf, u32, bool, bool)>>;

fn indice_de(carpeta: &Path) -> &'static Indice {
    static HECHOS: OnceLock<Mutex<HashMap<PathBuf, &'static Indice>>> = OnceLock::new();
    let m = HECHOS.get_or_init(Default::default);
    if let Some(i) = m.lock().ok().and_then(|m| m.get(carpeta).copied()) {
        return i;
    }
    let mut indice = Indice::new();
    let mut archivos: Vec<PathBuf> = std::fs::read_dir(carpeta)
        .map(|d| d.flatten().map(|e| e.path()).collect())
        .unwrap_or_default();
    archivos.sort();
    for ruta in archivos {
        let ext = ruta.extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase();
        if !matches!(ext.as_str(), "ttf" | "otf" | "ttc" | "otc") {
            continue;
        }
        let Some(d) = datos(&ruta) else { continue };
        let n = ttf_parser::fonts_in_collection(d).unwrap_or(1);
        for i in 0..n.min(16) {
            let Ok(cara) = ttf_parser::Face::parse(d, i) else { continue };
            let mut nombres: Vec<String> = Vec::new();
            for nombre in cara.names() {
                if (nombre.name_id == ttf_parser::name_id::FAMILY || nombre.name_id == ttf_parser::name_id::TYPOGRAPHIC_FAMILY)
                    && let Some(s) = nombre.to_string()
                {
                    let s = s.trim().to_lowercase();
                    if !s.is_empty() && !nombres.contains(&s) {
                        nombres.push(s);
                    }
                }
            }
            let (negrita, cursiva) = (cara.is_bold() || cara.weight().to_number() >= 600, cara.is_italic() || cara.is_oblique());
            for s in nombres {
                indice.entry(s).or_default().push((ruta.clone(), i, negrita, cursiva));
            }
        }
    }
    let hecho: &'static Indice = Box::leak(Box::new(indice));
    if let Ok(mut m) = m.lock() {
        m.insert(carpeta.to_path_buf(), hecho);
    }
    hecho
}

/// La mejor de una lista de variantes para lo pedido: la que tiene la negrita y la cursiva; si
/// no, la que más se acerca, y lo que falte se finge.
fn mejor_variante(v: &[(PathBuf, u32, bool, bool)], negrita: bool, cursiva: bool) -> Option<Eleccion> {
    let puntos = |&(_, _, b, i): &(PathBuf, u32, bool, bool)| -> i32 {
        // Lo que sobra (negrita sin pedirla) no se puede quitar: pesa más que lo que falta.
        let mut p = 0;
        if b == negrita { p += 2 } else if b { p -= 4 }
        if i == cursiva { p += 1 } else if i { p -= 4 }
        p
    };
    let (ruta, indice, b, i) = v.iter().max_by_key(|x| puntos(x))?.clone();
    Some(Eleccion { ruta, indice, negrita_falsa: negrita && !b, cursiva_falsa: cursiva && !i })
}

/// Los archivos de reserva, por orden, para los caracteres que la letra pedida no tiene (los del
/// PC son Segoe UI Symbol, Cambria Math, YaHei...; aquí, sus parecidos de Android).
const RESERVAS: [&str; 14] = [
    "Roboto-Regular.ttf",
    "RobotoStatic-Regular.ttf",
    "NotoSerif-Regular.ttf",
    "NotoSansSymbols-Regular-Subsetted.ttf",
    "NotoSansSymbols-Regular-Subsetted2.ttf",
    "NotoSansSymbols2-Regular.ttf",
    "NotoSansMath-Regular.otf",
    "NotoSansMath-Regular.ttf",
    "DroidSans.ttf",
    "NotoSansHebrew-Regular.ttf",
    "NotoNaskhArabic-Regular.ttf",
    "NotoSansCJK-Regular.ttc",
    "NotoColorEmoji.ttf",
    "NotoSansThai-Regular.ttf",
];

/// **El medidor de Android**, que además sabe con qué cara y glifo se pinta cada carácter.
pub struct Letras {
    carpetas: Carpetas,
    caras: RefCell<Vec<Cara>>,
    abiertas: RefCell<HashMap<Eleccion, Option<usize>>>,
    por_letra: RefCell<HashMap<(String, bool, bool), Option<usize>>>,
    glifos: RefCell<HashMap<(usize, char), (u16, f32)>>,
    /// Las de reserva ya abiertas, y si ya se miraron todas las del sistema.
    reservas: RefCell<Vec<usize>>,
    reservas_hechas: RefCell<bool>,
    todas_hechas: RefCell<bool>,
    sin_nadie: RefCell<HashSet<char>>,
    por_caracter: RefCell<HashMap<char, Option<usize>>>,
}

impl Letras {
    pub fn nuevo(carpetas: Carpetas) -> Letras {
        Letras {
            carpetas,
            caras: RefCell::new(Vec::new()),
            abiertas: RefCell::new(HashMap::new()),
            por_letra: RefCell::new(HashMap::new()),
            glifos: RefCell::new(HashMap::new()),
            reservas: RefCell::new(Vec::new()),
            reservas_hechas: RefCell::new(false),
            todas_hechas: RefCell::new(false),
            sin_nadie: RefCell::new(HashSet::new()),
            por_caracter: RefCell::new(HashMap::new()),
        }
    }

    fn abrir(&self, e: &Eleccion) -> Option<usize> {
        if let Some(i) = self.abiertas.borrow().get(e) {
            return *i;
        }
        let i = Cara::abrir(&e.ruta, e.indice, e.negrita_falsa, e.cursiva_falsa).map(|c| {
            let mut v = self.caras.borrow_mut();
            v.push(c);
            v.len() - 1
        });
        self.abiertas.borrow_mut().insert(e.clone(), i);
        i
    }

    /// La de PixPin de un grupo (`Carlito`, `LiberationSans`...) con la variante pedida.
    fn de_pixpin(&self, grupo: &str, negrita: bool, cursiva: bool) -> Option<Eleccion> {
        let carpeta = self.carpetas.pixpin.as_ref()?;
        let mut v = Vec::new();
        for (sufijo, b, i) in [("Regular", false, false), ("Bold", true, false), ("Italic", false, true), ("BoldItalic", true, true)] {
            let r = carpeta.join(format!("{grupo}-{sufijo}.ttf"));
            if r.is_file() {
                v.push((r, 0, b, i));
            }
        }
        mejor_variante(&v, negrita, cursiva)
    }

    fn por_nombre(&self, familia: &str, negrita: bool, cursiva: bool) -> Option<Eleccion> {
        for carpeta in [self.carpetas.usuario.as_ref(), Some(&self.carpetas.sistema)].into_iter().flatten() {
            if let Some(v) = indice_de(carpeta).get(familia)
                && let Some(e) = mejor_variante(v, negrita, cursiva)
            {
                return Some(e);
            }
        }
        None
    }

    /// La cara con que se mide y se pinta una letra del documento: la pedida (las del usuario
    /// mandan), la de PixPin que mide igual, una del sistema con ese nombre o la genérica.
    pub fn cara(&self, letra: &Letra) -> Option<usize> {
        let familia = if letra.familia.trim().is_empty() { "calibri".to_string() } else { letra.familia.trim().to_lowercase() };
        let clave = (familia.clone(), letra.negrita, letra.cursiva);
        if let Some(c) = self.por_letra.borrow().get(&clave) {
            return *c;
        }
        let (b, i) = (letra.negrita, letra.cursiva);
        let usuario = self.carpetas.usuario.as_ref().and_then(|c| indice_de(c).get(&familia)).and_then(|v| mejor_variante(v, b, i));
        let eleccion = usuario
            .or_else(|| grupo_de_pixpin(&familia).and_then(|g| self.de_pixpin(g, b, i)))
            .or_else(|| self.por_nombre(&familia, b, i))
            .or_else(|| {
                let g = if es_fija(&familia) {
                    "LiberationMono"
                } else if es_serif(&familia) {
                    "LiberationSerif"
                } else {
                    "LiberationSans"
                };
                self.de_pixpin(g, b, i)
            })
            .or_else(|| {
                // Sin las de PixPin (pruebas): las del sistema que siempre están.
                let v: Vec<(PathBuf, u32, bool, bool)> = ["Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "DroidSans.ttf", "NotoSerif-Regular.ttf"]
                    .iter()
                    .map(|n| self.carpetas.sistema.join(n))
                    .filter(|r| r.is_file())
                    .map(|r| (r, 0, false, false))
                    .collect();
                mejor_variante(&v, b, i)
            });
        let c = eleccion.and_then(|e| self.abrir(&e));
        self.por_letra.borrow_mut().insert(clave, c);
        c
    }

    fn glifo(&self, cara: usize, c: char) -> (u16, f32) {
        if let Some(g) = self.glifos.borrow().get(&(cara, c)) {
            return *g;
        }
        let g = self.caras.borrow().get(cara).map(|k| k.glifo(c)).unwrap_or((0, 0.0));
        self.glifos.borrow_mut().insert((cara, c), g);
        g
    }

    /// La de reserva que tiene `c`: primero la lista corta; si ninguna, todas las del sistema.
    fn reserva_para(&self, c: char) -> Option<usize> {
        if let Some(r) = self.por_caracter.borrow().get(&c) {
            return *r;
        }
        if self.sin_nadie.borrow().contains(&c) {
            return None;
        }
        if !*self.reservas_hechas.borrow() {
            *self.reservas_hechas.borrow_mut() = true;
            let mut v = Vec::new();
            for n in RESERVAS {
                let r = self.carpetas.sistema.join(n);
                if r.is_file()
                    && let Some(i) = self.abrir(&Eleccion { ruta: r, indice: 0, negrita_falsa: false, cursiva_falsa: false })
                {
                    v.push(i);
                }
            }
            *self.reservas.borrow_mut() = v;
        }
        let lista = self.reservas.borrow().clone();
        let mut hallada = lista.into_iter().find(|&r| self.glifo(r, c).0 != 0);
        if hallada.is_none() && !*self.todas_hechas.borrow() {
            // Una vez: todas las del sistema entran en la lista de reserva (por nombre).
            *self.todas_hechas.borrow_mut() = true;
            let mut archivos: Vec<PathBuf> =
                std::fs::read_dir(&self.carpetas.sistema).map(|d| d.flatten().map(|e| e.path()).collect()).unwrap_or_default();
            archivos.sort();
            for r in archivos {
                let n = r.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
                let ext = n.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
                // Las de cada escritura, en su versión normal: con eso basta para medir.
                if !matches!(ext.as_str(), "ttf" | "otf" | "ttc") || n.contains("Bold") || n.contains("Italic") || RESERVAS.contains(&n.as_str()) {
                    continue;
                }
                if let Some(i) = self.abrir(&Eleccion { ruta: r, indice: 0, negrita_falsa: false, cursiva_falsa: false }) {
                    self.reservas.borrow_mut().push(i);
                }
            }
            let lista = self.reservas.borrow().clone();
            hallada = lista.into_iter().find(|&r| self.glifo(r, c).0 != 0);
        }
        if hallada.is_none() {
            self.sin_nadie.borrow_mut().insert(c);
        }
        self.por_caracter.borrow_mut().insert(c, hallada);
        hallada
    }

    /// Para cada carácter de `texto`: la cara que lo pinta, el carácter que se pinta (los
    /// símbolos de Symbol/Wingdings en F0xx pasan a Unicode), su glifo y su avance en em.
    pub fn glifos(&self, texto: &str, letra: &Letra, salida: &mut Vec<Glifo>) {
        let Some(principal) = self.cara(letra) else {
            return;
        };
        for c in texto.chars() {
            let (g, a) = self.glifo(principal, c);
            if g != 0 || c == '\u{200B}' || c == '\u{AD}' || c.is_control() {
                salida.push(Glifo { cara: principal, caracter: c, glifo: g, avance: a });
                continue;
            }
            let c2 = simbolo_a_unicode(c).unwrap_or(c);
            if c2 != c {
                let (g, a) = self.glifo(principal, c2);
                if g != 0 {
                    salida.push(Glifo { cara: principal, caracter: c2, glifo: g, avance: a });
                    continue;
                }
            }
            match self.reserva_para(c2) {
                Some(r) => {
                    let (g, a) = self.glifo(r, c2);
                    salida.push(Glifo { cara: r, caracter: c2, glifo: g, avance: a });
                }
                None => salida.push(Glifo { cara: principal, caracter: c2, glifo: 0, avance: self.glifo(principal, '?').1 }),
            }
        }
    }

    /// Lo que se sabe de una cara abierta, para el formato que lee Kotlin.
    pub fn con_cara<R>(&self, i: usize, f: impl FnOnce(&Cara) -> R) -> Option<R> {
        self.caras.borrow().get(i).map(f)
    }
}

/// Un carácter ya resuelto.
#[derive(Debug, Clone, Copy)]
pub struct Glifo {
    pub cara: usize,
    pub caracter: char,
    pub glifo: u16,
    /// En em.
    pub avance: f32,
}

/// Los símbolos más comunes de Symbol y Wingdings (en F0xx) a Unicode (los del PC).
pub fn simbolo_a_unicode(c: char) -> Option<char> {
    let n = c as u32;
    if !(0xF000..=0xF0FF).contains(&n) {
        return None;
    }
    Some(match n - 0xF000 {
        0xB7 => '•',
        0xA7 => '▪',
        0xA8 => '◆',
        0x6E => '■',
        0x71 => '❑',
        0x76 => '❖',
        0xD8 => '➢',
        0xFC => '✓',
        0xFB => '✗',
        0x6F => '○',
        0x6C => '●',
        0xE0 => '→',
        0xE8 => '➔',
        0x2D => '−',
        b if (0x20..0x7F).contains(&b) => char::from_u32(b)?,
        _ => '•',
    })
}

impl Medidor for Letras {
    fn avances(&self, texto: &str, letra: &Letra, salida: &mut Vec<f32>) {
        let mut g = Vec::with_capacity(texto.len());
        self.glifos(texto, letra, &mut g);
        if g.is_empty() {
            salida.extend(texto.chars().map(|_| letra.tam * 0.5));
            return;
        }
        salida.extend(g.iter().map(|x| x.avance * letra.tam));
    }

    fn existe(&self, familia: &str) -> bool {
        let f = familia.trim().to_lowercase();
        grupo_de_pixpin(&f).is_some()
            || self.carpetas.usuario.as_ref().is_some_and(|c| indice_de(c).contains_key(&f))
            || indice_de(&self.carpetas.sistema).contains_key(&f)
    }

    fn metricas(&self, letra: &Letra) -> Metricas {
        match self.cara(letra).and_then(|c| self.con_cara(c, |k| k.m)) {
            Some(m) => Metricas { ascenso: m[0] * letra.tam, descenso: m[1] * letra.tam, hueco: m[2] * letra.tam },
            None => Metricas { ascenso: letra.tam * 0.9, descenso: letra.tam * 0.25, hueco: 0.0 },
        }
    }
}
