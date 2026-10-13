//! **Las hojas de Excel del PC en Android** (11-oct-2026).
//!
//! La lectura es la del PC (`pixpin-hojas`, copiado sin tocar en `pc/`): estilos ya resueltos,
//! formatos de número y fecha, combinaciones, paneles, tablas con estilo, formato condicional…
//! Aquí se hace lo que en el PC hace el pintor (`pixpin_render::hoja_office`) **antes** de pintar,
//! y se entrega a Kotlin en bytes para que pinte con Canvas sin saber nada de Excel:
//!
//! - **PXHL**: el libro (sus hojas y la que estaba delante);
//! - **PXHG**: la geometría de una hoja (dónde empieza cada columna y cada fila, en px al 100 %
//!   a 96 ppp como el PC), paneles inmovilizados, combinaciones, enlaces y dibujos;
//! - **PXHT**: un **trozo de filas**: cada celda con su texto ya formateado y un número de
//!   estilo (letra, color, relleno, alineación: lo de la tabla y el formato condicional ya
//!   puesto), y los bordes ya decididos lado a lado con la prioridad de Excel. Los estilos se
//!   mandan una sola vez: cada trozo trae solo los que Kotlin aún no tiene.
//!
//! Así nunca se copian cien mil filas de golpe: Kotlin pide los trozos que se ven.
//!
//! Todo en little-endian; `str` = u32 bytes + UTF-8. Lo lee `com.forge.pixpin.excel.Pxhj`.
//!
//! ```text
//! PXHL: "PXHL" u16 versión, u32 n × { str nombre, u8 visibilidad (0 visible 1 oculta 2 muy oculta),
//!       u8 tipo (0 hoja 1 gráfico 2 otra) }, u32 activa
//! PXHG: "PXHG" u16 versión, u64 versión de la hoja, u8 completa, u32 filas fijas, u32 columnas
//!       fijas, u8 (1 cuadrícula, 2 encabezados), u16 zoom %, u32 primera fila, u32 primera
//!       columna, i32 fila activa, i32 columna activa, u32 color de la pestaña (0 sin),
//!       u32 ancho por defecto, u32 alto por defecto, u32 última fila usada, u32 última columna
//!       usada, u32 ancho usado, u32 alto usado,
//!       u32 nx, (nx + 1) × i32 x de cada columna (de ahí en adelante, el ancho por defecto);
//!       u32 ny filas leídas, u32 n × { u32 fila, u32 alto } las que no miden el alto por
//!       defecto (las demás y las de más allá, sí),
//!       u32 n × { u32 min, u32 max, u32 argb } rellenos de columnas enteras,
//!       u32 n × { u32 f0, c0, f1, c1 } combinaciones,
//!       u32 n × { u32 f0, c0, f1, c1, u8 externo, str destino } enlaces,
//!       u32 n × dibujo,
//!       u32 n × { u32 fila, u32 columna, u8 filtrado } botones del autofiltro (de la hoja y de
//!       las tablas),
//!       u32 n × { u32 n × { u32 f0, c0, f1, c1 } } listas desplegables (sus rangos)
//!   dibujo = f32 x, y, ancho, alto (px al 100 %), u8 tipo y:
//!     0 imagen:  str ruta en el ZIP, f32 × 4 recorte (izq, arr, der, aba en fracción)
//!     1 gráfico: str título, str tipo, u32 n × str categoría, u32 n × { str nombre,
//!                u32 argb (0 sin), u32 n × f64 }
//!     2 forma:   str texto, u32 relleno, u32 línea, u8 alinear, u8 vertical
//! PXHT: "PXHT" u16 versión, u64 versión de la hoja, u32 f0, u32 f1, u32 desde, u32 n × estilo
//!       (los estilos desde el número `desde`), u32 n × fila, u32 n × lado, u32 n × minigráfico,
//!       u32 n × comentario
//!   estilo = str familia, f32 puntos, u16 letra (1 negrita 2 cursiva 4 tachado 8 subrayado
//!            16 doble subrayado 32 superíndice 64 subíndice), u32 argb del texto, u32 argb del
//!            relleno (0 sin), u8 horizontal, u8 vertical, u8 (1 ajustar 2 encoger), u8 sangría,
//!            u16 giro (el del XML: 0-180, 255 apilado)
//!   fila   = u32 índice, u8 (1 oculta, 2 sin alto propio), u32 argb del relleno de la fila
//!            (0 sin), u32 n × celda
//!   celda  = u32 columna, u32 estilo, u8 clase (0 vacía 1 número 2 texto 3 lógico 4 error),
//!            u8 (1 barra, 2 relleno contable, 4 tramos, 8 icono), str texto,
//!            [barra: f32 desde, f32 hasta (fracciones del ancho), u32 argb, u8 (1 degradado),
//!                    u32 argb del borde (0 sin), f32 eje (fracción; < 0 sin), u32 argb del eje]
//!            [contable: u32 corte en UTF-16]
//!            [tramos: u32 n × { u32 largo en UTF-16, str familia, f32 puntos, u16 letra, u32 argb }]
//!            [icono: u8 conjunto (el orden de `Conjunto::TODOS`), u8 icono (0 = el peor)]
//!   lado   = u8 tipo (0 horizontal: arriba de la fila; 1 vertical: a la izquierda de la
//!            columna; 2 diagonal que baja; 3 diagonal que sube), u32 fila, u32 columna,
//!            u8 estilo de línea (el orden de `EstiloLinea`), u32 argb
//!   minigráfico = u32 fila, u32 columna, u8 tipo (0 línea, 1 columnas, 2 ganancia/pérdida),
//!            u8 (1 marcadores, 2 alto, 4 bajo, 8 primero, 16 último, 32 negativos, 64 eje,
//!            128 unir los huecos), f32 grosor en puntos, f64 mínimo y máximo del eje,
//!            u32 argb × 8 (serie, negativo, eje, marcadores, primero, último, alto, bajo),
//!            u32 n × f32 puntos (NaN = hueco; ya del revés si va de derecha a izquierda)
//!   comentario = u32 fila, u32 columna, u8 encadenado, str autor, str texto,
//!            u32 n × { str autor, str texto } respuestas
//! ```
//! Los lados van de menos a más fuertes: pintados en orden, los gruesos tapan las esquinas.
//!
//! v2 (13-oct-2026, PC 9975385): barras de datos completas, iconos, minigráficos, comentarios,
//! botones del autofiltro, listas desplegables y los `.xls` antiguos.

use std::collections::HashMap;
use std::path::Path;

use pixpin_hojas::condicional::Efecto;
use pixpin_hojas::estilos::{Dxf, EstiloLinea, Fuente, Horizontal, Linea, Relleno, Rgb, Subrayado, Vertical};
use pixpin_hojas::{Celda, Clase, ContenidoDibujo, Hoja, Libro, Rango, Regional, Rejilla, TipoHoja, Valor, Visibilidad};

pub const VERSION: u16 = 2;

/// Un libro abierto y sus hojas (cada una se abre la primera vez que se pide).
pub struct LibroAndroid {
    pub libro: Libro,
    hojas: Vec<Option<HojaAndroid>>,
}

/// Una hoja abierta: lo leído, su rejilla y los estilos ya mandados a Kotlin.
pub struct HojaAndroid {
    pub hoja: Hoja,
    pub rejilla: Rejilla,
    estilos: Vec<Vec<u8>>,
    indice: HashMap<Vec<u8>, u32>,
}

/// La configuración regional que manda la app: un renglón por campo —idioma (`es`/`en`),
/// separador decimal, de miles, de fechas y la fecha corta en código de Excel (`dd/mm/yyyy`)—.
/// Lo que falte, el del idioma (como `regional_de` del PC con lo de Windows).
pub fn regional_de(texto: &str) -> Regional {
    let mut l = texto.lines();
    let idioma = l.next().unwrap_or("es").trim();
    let mut r = if idioma.starts_with("en") { Regional::en_us() } else { Regional::es_pe() };
    let primero = |t: Option<&str>| t.and_then(|t| t.chars().next());
    if let Some(d) = primero(l.next()) {
        r.decimal = d;
    }
    if let Some(m) = primero(l.next()) {
        r.miles = m;
    }
    if r.miles == r.decimal {
        r.miles = if r.decimal == ',' { '.' } else { ',' };
    }
    if let Some(f) = primero(l.next()) {
        r.separador_fecha = f;
    }
    if let Some(f) = l.next().map(str::trim).filter(|f| !f.is_empty() && f.contains('d') && f.contains('m')) {
        r.fecha_corta = f.to_string();
    }
    // Los minutos al este de UTC del teléfono: el «hoy» del formato condicional (el PC lo saca de
    // Windows, `fijar_hora_local`).
    if let Some(m) = l.next().and_then(|m| m.trim().parse::<i32>().ok()) {
        pixpin_hojas::condicional::fijar_desfase_horario(m);
    }
    r
}

/// Si el archivo empieza como un ZIP (un `.xlsx`) o como un `.xls` antiguo (un archivo compuesto
/// de OLE, que el PC lee sin Office desde 9975385).
pub fn se_lee(ruta: &Path) -> bool {
    use std::io::Read;
    let mut cabeza = [0u8; 8];
    std::fs::File::open(ruta).and_then(|mut f| f.read_exact(&mut cabeza)).is_ok() && (cabeza.starts_with(b"PK\x03\x04") || pixpin_hojas::cfb::es_cfb(&cabeza))
}

fn argb(c: Rgb) -> u32 {
    0xFF00_0000 | (c.r as u32) << 16 | (c.g as u32) << 8 | c.b as u32
}

/// Quien escribe los bytes.
#[derive(Default)]
pub struct Bytes {
    pub b: Vec<u8>,
}

impl Bytes {
    fn u8(&mut self, v: u8) {
        self.b.push(v);
    }
    fn u16(&mut self, v: u16) {
        self.b.extend_from_slice(&v.to_le_bytes());
    }
    fn u32(&mut self, v: u32) {
        self.b.extend_from_slice(&v.to_le_bytes());
    }
    fn i32(&mut self, v: i32) {
        self.b.extend_from_slice(&v.to_le_bytes());
    }
    fn u64(&mut self, v: u64) {
        self.b.extend_from_slice(&v.to_le_bytes());
    }
    fn f32(&mut self, v: f32) {
        self.b.extend_from_slice(&(if v.is_finite() { v } else { 0.0 }).to_le_bytes());
    }
    /// Un f32 tal cual, también NaN (los huecos de un minigráfico).
    fn f32_crudo(&mut self, v: f32) {
        self.b.extend_from_slice(&v.to_le_bytes());
    }
    fn f64(&mut self, v: f64) {
        self.b.extend_from_slice(&(if v.is_finite() { v } else { 0.0 }).to_le_bytes());
    }
    fn str(&mut self, s: &str) {
        self.u32(s.len() as u32);
        self.b.extend_from_slice(s.as_bytes());
    }
    fn magia(&mut self, m: &[u8; 4]) {
        self.b.extend_from_slice(m);
        self.u16(VERSION);
    }
}

fn bits_de_letra(f: &Fuente) -> u16 {
    let mut b = 0u16;
    if f.negrita {
        b |= 1;
    }
    if f.cursiva {
        b |= 2;
    }
    if f.tachado {
        b |= 4;
    }
    match f.subrayado {
        Subrayado::Simple => b |= 8,
        Subrayado::Doble => b |= 16,
        Subrayado::Ninguno => {}
    }
    if f.vertical > 0 {
        b |= 32;
    } else if f.vertical < 0 {
        b |= 64;
    }
    b
}

fn horizontal(h: Horizontal) -> u8 {
    match h {
        Horizontal::General => 0,
        Horizontal::Izquierda => 1,
        Horizontal::Centro => 2,
        Horizontal::Derecha => 3,
        Horizontal::Rellenar => 4,
        Horizontal::Justificar => 5,
        Horizontal::CentroSeleccion => 6,
        Horizontal::Distribuido => 7,
    }
}

fn vertical(v: Vertical) -> u8 {
    match v {
        Vertical::Arriba => 0,
        Vertical::Centro => 1,
        Vertical::Abajo => 2,
        Vertical::Justificar => 3,
        Vertical::Distribuido => 4,
    }
}

fn linea_u8(e: EstiloLinea) -> u8 {
    e as u8
}

impl LibroAndroid {
    pub fn abrir(ruta: &Path, regional: Regional) -> Result<LibroAndroid, String> {
        if !se_lee(ruta) {
            return Err("no es un libro de Excel".into());
        }
        let libro = Libro::abrir(ruta, regional).map_err(|e| e.to_string())?;
        let n = libro.hojas.len();
        Ok(LibroAndroid { libro, hojas: (0..n).map(|_| None).collect() })
    }

    /// PXHL.
    pub fn bytes_libro(&self) -> Vec<u8> {
        let mut w = Bytes::default();
        w.magia(b"PXHL");
        w.u32(self.libro.hojas.len() as u32);
        for h in &self.libro.hojas {
            w.str(&h.nombre);
            w.u8(match h.visibilidad {
                Visibilidad::Visible => 0,
                Visibilidad::Oculta => 1,
                Visibilidad::MuyOculta => 2,
            });
            w.u8(match h.tipo {
                TipoHoja::Hoja => 0,
                TipoHoja::Grafico => 1,
                TipoHoja::Otra => 2,
            });
        }
        w.u32(self.libro.hoja_activa as u32);
        w.b
    }

    /// Abre la hoja `i` (si no lo estaba) y lee hasta la fila `hasta` (la primera pantalla).
    pub fn abrir_hoja(&mut self, i: usize, hasta: u32) -> bool {
        if i >= self.hojas.len() {
            return false;
        }
        if self.hojas[i].is_none() {
            let mut hoja = self.libro.abrir_hoja(i);
            hoja.cargar_hasta_fila(hasta);
            let rejilla = Rejilla::nueva(&hoja, &self.libro.estilos.fuente_normal);
            self.hojas[i] = Some(HojaAndroid { hoja, rejilla, estilos: Vec::new(), indice: HashMap::new() });
        }
        true
    }

    fn hoja_mut(&mut self, i: usize) -> Option<(&Libro, &mut HojaAndroid)> {
        let LibroAndroid { libro, hojas } = self;
        hojas.get_mut(i)?.as_mut().map(|h| (&*libro, h))
    }

    /// Lee una tanda de unas `celdas` celdas más. `Some(true)` si la hoja ya está entera.
    pub fn cargar(&mut self, i: usize, celdas: usize) -> Option<bool> {
        let (_, h) = self.hoja_mut(i)?;
        let completa = h.hoja.cargar_tanda(celdas);
        h.rejilla.actualizar(&h.hoja);
        Some(completa)
    }

    /// Lo que mide la hoja ya leída.
    pub fn bytes_geometria(&mut self, i: usize) -> Option<Vec<u8>> {
        let (libro, h) = self.hoja_mut(i)?;
        h.rejilla.actualizar(&h.hoja);
        Some(geometria(libro, &h.hoja, &h.rejilla))
    }

    /// Las filas `f0..=f1`, leyéndolas si aún no habían llegado. `conocidos`: cuántos estilos
    /// tiene ya Kotlin.
    pub fn bytes_trozo(&mut self, i: usize, f0: u32, f1: u32, conocidos: u32) -> Option<Vec<u8>> {
        let (libro, h) = self.hoja_mut(i)?;
        if !h.hoja.completa && h.hoja.filas.last().is_none_or(|f| f.indice <= f1) {
            h.hoja.cargar_hasta_fila(f1.saturating_add(1));
            h.rejilla.actualizar(&h.hoja);
        }
        Some(trozo(libro, h, f0, f1.max(f0), conocidos))
    }

    /// Lo elegido como texto con tabuladores (lo que se ve en cada celda), como el PC.
    pub fn copiar(&mut self, i: usize, r: Rango) -> Option<String> {
        let (libro, h) = self.hoja_mut(i)?;
        if !h.hoja.completa {
            h.hoja.cargar_hasta_fila(r.fila1.saturating_add(1));
        }
        let hoja = &h.hoja;
        let mut s = String::new();
        for f in r.fila0..=r.fila1 {
            let cs = hoja.fila(f).map(|x| hoja.celdas_de(x)).unwrap_or(&[]);
            for c in r.col0..=r.col1 {
                if c > r.col0 {
                    s.push('\t');
                }
                let v = cs
                    .binary_search_by_key(&c, |x| x.col)
                    .ok()
                    .and_then(|k| libro.contenido(hoja, &cs[k]))
                    .map(|x| x.mostrado.texto)
                    .unwrap_or_default();
                if v.contains(['\t', '\n', '\r', '"']) {
                    s.push('"');
                    s.push_str(&v.replace('"', "\"\""));
                    s.push('"');
                } else {
                    s.push_str(&v);
                }
            }
            s.push_str("\r\n");
        }
        Some(s)
    }
}

fn geometria(libro: &Libro, hoja: &Hoja, r: &Rejilla) -> Vec<u8> {
    let mut w = Bytes::default();
    w.magia(b"PXHG");
    w.u64(hoja.version);
    w.u8(hoja.completa as u8);
    let v = hoja.vista();
    w.u32(r.filas_fijas);
    w.u32(r.columnas_fijas);
    w.u8((v.cuadricula as u8) | (v.encabezados as u8) << 1);
    w.u16(v.zoom.min(400) as u16);
    w.u32(v.primera_fila);
    w.u32(v.primera_columna);
    let (fa, ca) = v.celda_activa.map_or((-1, -1), |(f, c)| (f as i32, c as i32));
    w.i32(fa);
    w.i32(ca);
    w.u32(hoja.cabecera.color_pestana.map_or(0, argb));
    w.u32(r.ancho_defecto.round().max(0.0) as u32);
    w.u32(r.alto_defecto.round().max(0.0) as u32);
    let dim = hoja.cabecera.dimension;
    let ultima_fila = hoja.max_fila().max(dim.map_or(0, |d| d.fila1));
    let ultima_col = hoja.max_col.max(dim.map_or(0, |d| d.col1));
    w.u32(ultima_fila);
    w.u32(ultima_col);
    let (au, alu) = r.tamano_usado(hoja);
    w.u32(au.round().max(0.0) as u32);
    w.u32(alu.round().max(0.0) as u32);
    // Las x hasta la última columna con algo o con ancho propio (de ahí en adelante, por defecto).
    let definidas = hoja.cabecera.columnas.iter().map(|c| c.max.saturating_add(1)).max().unwrap_or(0);
    let nx = ultima_col.saturating_add(2).max(definidas).min(pixpin_hojas::rejilla::MAX_COLUMNAS);
    w.u32(nx);
    for c in 0..=nx {
        w.i32(r.x(c).round() as i32);
    }
    // Las filas: solo las que no miden lo de por defecto (cien mil filas iguales no son 400 KB).
    let ny = hoja.filas.last().map_or(0, |f| f.indice + 1).min(pixpin_hojas::rejilla::MAX_FILAS);
    let distintas: Vec<(u32, u32)> = hoja
        .filas
        .iter()
        .filter(|f| f.indice < ny)
        .map(|f| (f.indice, r.alto(f.indice).round().max(0.0) as u32))
        .filter(|&(_, a)| a as f64 != r.alto_defecto.round())
        .collect();
    w.u32(ny);
    w.u32(distintas.len() as u32);
    for (f, a) in distintas {
        w.u32(f);
        w.u32(a);
    }
    // Rellenos de columnas enteras.
    let est = &libro.estilos;
    let cols: Vec<_> = hoja
        .cabecera
        .columnas
        .iter()
        .filter_map(|c| match est.relleno(est.xf(c.xf)) {
            Relleno::Liso(color) => Some((c.min, c.max, argb(color))),
            Relleno::Nada => None,
        })
        .collect();
    w.u32(cols.len() as u32);
    for (a, b, c) in cols {
        w.u32(a);
        w.u32(b);
        w.u32(c);
    }
    let rango = |w: &mut Bytes, r: &Rango| {
        w.u32(r.fila0);
        w.u32(r.col0);
        w.u32(r.fila1);
        w.u32(r.col1);
    };
    w.u32(hoja.cola.combinadas.len() as u32);
    for c in &hoja.cola.combinadas {
        rango(&mut w, c);
    }
    w.u32(hoja.cola.hipervinculos.len() as u32);
    for h in &hoja.cola.hipervinculos {
        rango(&mut w, &h.rango);
        w.u8(h.externo as u8);
        w.str(&h.destino);
    }
    w.u32(hoja.cola.dibujos.len() as u32);
    for d in &hoja.cola.dibujos {
        let [x, y, an, al] = r.caja_dibujo(d);
        w.f32(x as f32);
        w.f32(y as f32);
        w.f32(an as f32);
        w.f32(al as f32);
        match &d.contenido {
            ContenidoDibujo::Imagen { ruta, recorte } => {
                w.u8(0);
                w.str(ruta);
                for v in recorte {
                    w.f32(*v);
                }
            }
            ContenidoDibujo::Grafico { titulo, tipo, categorias, series } => {
                w.u8(1);
                w.str(titulo);
                w.str(tipo);
                w.u32(categorias.len() as u32);
                for c in categorias {
                    w.str(c);
                }
                w.u32(series.len() as u32);
                for s in series {
                    w.str(&s.nombre);
                    w.u32(s.color.map_or(0, argb));
                    w.u32(s.valores.len() as u32);
                    for v in &s.valores {
                        w.f64(*v);
                    }
                }
            }
            ContenidoDibujo::Forma { texto, relleno, linea, alinear, vertical } => {
                w.u8(2);
                w.str(texto);
                w.u32(relleno.map_or(0, argb));
                w.u32(linea.map_or(0, argb));
                w.u8(*alinear);
                w.u8(*vertical);
            }
        }
    }
    // Los botones del autofiltro: el de la hoja y los de las tablas con encabezado.
    let tablas = hoja.cola.tablas.iter().filter(|t| t.encabezado > 0).filter_map(|t| t.filtro.as_ref());
    let mut botones: Vec<(u32, u32, bool)> = Vec::new();
    for f in hoja.cola.autofiltro.iter().chain(tablas) {
        for col in f.columnas_con_boton() {
            botones.push((f.rango.fila0, col, f.tiene_filtro(col)));
        }
    }
    w.u32(botones.len() as u32);
    for (f, c, filtrado) in botones {
        w.u32(f);
        w.u32(c);
        w.u8(filtrado as u8);
    }
    // Las listas desplegables (las que llevan flecha).
    let listas: Vec<_> = hoja
        .cola
        .validaciones
        .iter()
        .filter(|v| v.tipo == pixpin_hojas::validacion::TipoValidacion::Lista && v.flecha)
        .collect();
    w.u32(listas.len() as u32);
    for v in listas {
        w.u32(v.rangos.len() as u32);
        for r in &v.rangos {
            rango(&mut w, r);
        }
    }
    w.b
}

/// Lo que la tabla y el formato condicional ponen en una celda (como `extra_de` del PC), más el
/// relleno de la tabla y el efecto entero.
fn extra_de(libro: &Libro, hoja: &Hoja, fila: u32, c: &Celda) -> (Option<Dxf>, Option<Rgb>, Option<Efecto>) {
    let mut d: Option<Dxf> = None;
    let mut relleno_tabla = None;
    for t in &hoja.cola.tablas {
        if t.rango.contiene(fila, c.col) {
            if let Some(e) = libro.estilo_de_tabla(t) {
                let x = pixpin_hojas::tablas::formato_en(t, &e, fila, c.col);
                relleno_tabla = x.relleno;
                d = Some(x);
            }
            break;
        }
    }
    let ef = libro.efecto_condicional(hoja, fila, c);
    if let Some(ef) = &ef {
        let mut base = d.unwrap_or_default();
        base.sobre(&ef.dxf);
        d = Some(base);
    }
    (d, relleno_tabla, ef)
}

/// Escribe un estilo en `w`.
fn estilo_bytes(fuente: &Fuente, color: Rgb, relleno: Option<Rgb>, h: u8, al: &pixpin_hojas::Alineacion) -> Vec<u8> {
    let mut w = Bytes::default();
    w.str(&fuente.nombre);
    w.f32(fuente.tam);
    w.u16(bits_de_letra(fuente));
    w.u32(argb(color));
    w.u32(relleno.map_or(0, argb));
    w.u8(h);
    w.u8(vertical(al.vertical));
    w.u8((al.ajustar as u8) | (al.encoger as u8) << 1);
    w.u8(al.sangria);
    w.u16(al.giro);
    w.b
}

impl HojaAndroid {
    fn estilo(&mut self, b: Vec<u8>) -> u32 {
        if let Some(&i) = self.indice.get(&b) {
            return i;
        }
        let i = self.estilos.len() as u32;
        self.indice.insert(b.clone(), i);
        self.estilos.push(b);
        i
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
struct Lado {
    tipo: u8,
    fila: u32,
    col: u32,
}

fn trozo(libro: &Libro, h: &mut HojaAndroid, f0: u32, f1: u32, conocidos: u32) -> Vec<u8> {
    let est = &libro.estilos;
    // Las celdas de cada fila, con las de las tablas que no existen como vacías con su estilo
    // (la tabla les pone relleno y bordes).
    let mut filas_w = Bytes::default();
    let mut nfilas = 0u32;
    let tablas: Vec<_> = h.hoja.cola.tablas.iter().filter(|t| t.rango.fila1 >= f0 && t.rango.fila0 <= f1).map(|t| t.rango).collect();
    let mut celdas_tmp: Vec<Celda> = Vec::new();
    for f in f0..=f1 {
        let fila = h.hoja.fila(f).copied();
        let en_tabla = tablas.iter().any(|r| r.fila0 <= f && r.fila1 >= f);
        if fila.is_none() && !en_tabla {
            continue;
        }
        celdas_tmp.clear();
        if let Some(fi) = &fila {
            celdas_tmp.extend_from_slice(h.hoja.celdas_de(fi));
        }
        if en_tabla {
            for r in tablas.iter().filter(|r| r.fila0 <= f && r.fila1 >= f) {
                for col in r.col0..=r.col1 {
                    if celdas_tmp.binary_search_by_key(&col, |c| c.col).is_err() {
                        let xf = h.hoja.xf_en(f, col);
                        let k = celdas_tmp.partition_point(|c| c.col < col);
                        celdas_tmp.insert(k, Celda { col, xf, valor: Valor::Vacio });
                    }
                }
            }
        }
        nfilas += 1;
        filas_w.u32(f);
        let (oculta, sin_alto, xf_fila) = fila.map_or((false, false, None), |x| (x.oculta, x.sin_alto, x.xf));
        filas_w.u8((oculta as u8) | (sin_alto as u8) << 1);
        filas_w.u32(match xf_fila.map(|x| est.relleno(est.xf(x))) {
            Some(Relleno::Liso(c)) => argb(c),
            _ => 0,
        });
        // Las celdas que pintan algo: con valor, o con estilo distinto del 0.
        let utiles: Vec<Celda> = celdas_tmp.iter().copied().filter(|c| c.valor != Valor::Vacio || c.xf != 0).collect();
        filas_w.u32(utiles.len() as u32);
        for c in &utiles {
            let xf = est.xf(c.xf);
            let al = xf.alineacion;
            let mut fuente = est.fuente(xf).clone();
            let (extra, relleno_tabla, ef) = extra_de(libro, &h.hoja, f, c);
            if let Some(d) = &extra {
                fuente.negrita = d.negrita.unwrap_or(fuente.negrita);
                fuente.cursiva = d.cursiva.unwrap_or(fuente.cursiva);
                fuente.tachado = d.tachado.unwrap_or(fuente.tachado);
                if let Some(s) = d.subrayado {
                    fuente.subrayado = if s { Subrayado::Simple } else { Subrayado::Ninguno };
                }
            }
            let cont = libro.contenido(&h.hoja, c);
            let mut color = match &cont {
                Some(x) => libro.color_texto(xf, &x.mostrado),
                None => fuente.color.unwrap_or(Rgb::NEGRO),
            };
            if let Some(d) = &extra {
                if let (None, Some(cf)) = (cont.as_ref().and_then(|x| x.mostrado.color), d.color_fuente) {
                    color = cf;
                }
            }
            let propio = match est.relleno(xf) {
                Relleno::Liso(x) => Some(x),
                Relleno::Nada => None,
            };
            let condicional = ef.as_ref().and_then(|e| e.escala.or(e.dxf.relleno));
            let relleno = condicional.or(propio).or(relleno_tabla);
            let hz = cont.as_ref().map_or(al.horizontal, |x| x.horizontal);
            let id = h.estilo(estilo_bytes(&fuente, color, relleno, horizontal(hz), &al));
            filas_w.u32(c.col);
            filas_w.u32(id);
            let (clase, texto) = match &cont {
                Some(x) => (
                    match x.clase {
                        Clase::Numero => 1,
                        Clase::Texto => 2,
                        Clase::Logico => 3,
                        Clase::Error => 4,
                    },
                    if ef.as_ref().is_some_and(|e| e.ocultar_valor) { "" } else { x.mostrado.texto.as_str() },
                ),
                None => (0u8, ""),
            };
            let barra = ef.as_ref().and_then(|e| e.barra.clone());
            let icono = ef.as_ref().and_then(|e| e.icono);
            let corte = cont
                .as_ref()
                .and_then(|x| x.mostrado.relleno)
                .filter(|&k| k > 0 && k < texto.len() && texto.is_char_boundary(k))
                .map(|k| texto[..k].encode_utf16().count() as u32);
            let tramos = cont.as_ref().and_then(|x| x.tramos.as_ref()).filter(|_| !texto.is_empty());
            filas_w.u8(clase);
            filas_w.u8((barra.is_some() as u8) | (corte.is_some() as u8) << 1 | (tramos.is_some() as u8) << 2 | (icono.is_some() as u8) << 3);
            filas_w.str(texto);
            if let Some(b) = barra {
                filas_w.f32(b.desde);
                filas_w.f32(b.hasta);
                filas_w.u32(argb(b.color));
                filas_w.u8(b.degradado as u8);
                filas_w.u32(b.borde.map_or(0, argb));
                filas_w.f32(b.eje.map_or(-1.0, |e| e.0));
                filas_w.u32(b.eje.map_or(0, |e| argb(e.1)));
            }
            if let Some(k) = corte {
                filas_w.u32(k);
            }
            if let Some(ts) = tramos {
                filas_w.u32(ts.len() as u32);
                for t in ts {
                    filas_w.u32(t.texto.encode_utf16().count() as u32);
                    filas_w.str(&t.fuente.nombre);
                    filas_w.f32(t.fuente.tam);
                    filas_w.u16(bits_de_letra(&t.fuente));
                    filas_w.u32(argb(t.fuente.color.unwrap_or(color)));
                }
            }
            if let Some((conjunto, i)) = icono {
                filas_w.u8(pixpin_hojas::condicional::Conjunto::TODOS.iter().position(|c| *c == conjunto).unwrap_or(0) as u8);
                filas_w.u8(i);
            }
        }
    }
    // Los bordes: el mapa de lados con la línea más fuerte de cada uno (como el PC).
    let mut lados: HashMap<Lado, Linea> = HashMap::new();
    let mut poner = |l: Lado, li: Linea| {
        if li.estilo == EstiloLinea::Ninguno {
            return;
        }
        if l.tipo == 0 && (l.fila < f0 || l.fila > f1 + 1) || l.tipo == 1 && (l.fila < f0 || l.fila > f1) {
            return;
        }
        let e = lados.entry(l).or_insert(li);
        if li > *e {
            *e = li;
        }
    };
    let mut diagonales: Vec<(Lado, Linea)> = Vec::new();
    let hoja = &h.hoja;
    for fi in hoja.filas_entre(f0.saturating_sub(1), f1.saturating_add(1)) {
        for c in hoja.celdas_de(fi) {
            if c.xf == 0 {
                continue;
            }
            let b = est.borde(est.xf(c.xf));
            let (r, k) = (fi.indice, c.col);
            poner(Lado { tipo: 0, fila: r, col: k }, b.arriba);
            poner(Lado { tipo: 0, fila: r + 1, col: k }, b.abajo);
            poner(Lado { tipo: 1, fila: r, col: k }, b.izquierda);
            poner(Lado { tipo: 1, fila: r, col: k + 1 }, b.derecha);
            if b.diagonal.estilo != EstiloLinea::Ninguno && r >= f0 && r <= f1 {
                if b.diagonal_baja {
                    diagonales.push((Lado { tipo: 2, fila: r, col: k }, b.diagonal));
                }
                if b.diagonal_sube {
                    diagonales.push((Lado { tipo: 3, fila: r, col: k }, b.diagonal));
                }
            }
        }
    }
    for t in &hoja.cola.tablas {
        let r = t.rango;
        if r.fila1.saturating_add(1) < f0 || r.fila0 > f1.saturating_add(1) {
            continue;
        }
        let Some(e) = libro.estilo_de_tabla(t) else { continue };
        for f in r.fila0.max(f0.saturating_sub(1))..=r.fila1.min(f1.saturating_add(1)) {
            for c in r.col0..=r.col1 {
                let d = pixpin_hojas::tablas::formato_en(t, &e, f, c);
                let lado = |o: Option<Linea>| o.unwrap_or_default();
                poner(Lado { tipo: 0, fila: f, col: c }, lado(d.arriba));
                poner(Lado { tipo: 0, fila: f + 1, col: c }, lado(d.abajo));
                poner(Lado { tipo: 1, fila: f, col: c }, lado(d.izquierda));
                poner(Lado { tipo: 1, fila: f, col: c + 1 }, lado(d.derecha));
            }
        }
    }
    let combinadas: Vec<Rango> = hoja.combinadas_en(f0.saturating_sub(1), f1.saturating_add(1), 0, u32::MAX).copied().collect();
    let mut lista: Vec<(Lado, Linea)> = lados
        .into_iter()
        .filter(|(l, _)| {
            // Los lados de dentro de una combinación no se pintan.
            !if l.tipo == 0 {
                l.fila > 0 && combinadas.iter().any(|r| r.contiene(l.fila - 1, l.col) && r.contiene(l.fila, l.col))
            } else {
                l.col > 0 && combinadas.iter().any(|r| r.contiene(l.fila, l.col - 1) && r.contiene(l.fila, l.col))
            }
        })
        .collect();
    lista.sort_by(|a, b| a.1.cmp(&b.1).then(a.0.tipo.cmp(&b.0.tipo)).then(a.0.fila.cmp(&b.0.fila)).then(a.0.col.cmp(&b.0.col)));
    lista.extend(diagonales);

    let mut w = Bytes::default();
    w.magia(b"PXHT");
    w.u64(h.hoja.version);
    w.u32(f0);
    w.u32(f1);
    let desde = conocidos.min(h.estilos.len() as u32);
    w.u32(desde);
    w.u32(h.estilos.len() as u32 - desde);
    for e in &h.estilos[desde as usize..] {
        w.b.extend_from_slice(e);
    }
    w.u32(nfilas);
    w.b.extend_from_slice(&filas_w.b);
    w.u32(lista.len() as u32);
    for (l, li) in lista {
        w.u8(l.tipo);
        w.u32(l.fila);
        w.u32(l.col);
        w.u8(linea_u8(li.estilo));
        w.u32(argb(li.color));
    }
    minigraficos(&mut w, libro, &h.hoja, f0, f1);
    comentarios(&mut w, &h.hoja, f0, f1);
    w.b
}

/// **Los minigráficos** de las filas `f0..=f1` con sus puntos y su eje ya calculados (lo que el PC
/// hace en `pintar_minigraficos` antes de dibujar): Kotlin solo los dibuja.
fn minigraficos(w: &mut Bytes, libro: &Libro, hoja: &Hoja, f0: u32, f1: u32) {
    use pixpin_hojas::minigraficos::{TipoMini, Vacias, puntos_de};
    let minis = &hoja.cola.minigraficos;
    let desde = minis.partition_point(|x| x.fila < f0);
    let mut cuerpo = Bytes::default();
    let mut n = 0u32;
    for mi in minis[desde..].iter().take_while(|x| x.fila <= f1) {
        let Some(g) = hoja.cola.grupos_mini.get(mi.grupo as usize) else { continue };
        let valores = libro.valores_mini(hoja, mi);
        let puntos = puntos_de(g, &valores);
        let Some((lo, hi)) = libro.eje_mini(hoja, mi, &puntos) else { continue };
        n += 1;
        cuerpo.u32(mi.fila);
        cuerpo.u32(mi.col);
        cuerpo.u8(match g.tipo {
            TipoMini::Linea => 0,
            TipoMini::Columna => 1,
            TipoMini::GananciaPerdida => 2,
        });
        cuerpo.u8(
            g.marcadores as u8
                | (g.alto as u8) << 1
                | (g.bajo as u8) << 2
                | (g.primero as u8) << 3
                | (g.ultimo as u8) << 4
                | (g.negativos as u8) << 5
                | (g.eje_x as u8) << 6
                | ((g.vacias == Vacias::Unir) as u8) << 7,
        );
        cuerpo.f32(g.peso);
        cuerpo.f64(lo);
        cuerpo.f64(hi);
        for c in [g.c_serie, g.c_negativo, g.c_eje, g.c_marcadores, g.c_primero, g.c_ultimo, g.c_alto, g.c_bajo] {
            cuerpo.u32(argb(c));
        }
        cuerpo.u32(puntos.len() as u32);
        for p in &puntos {
            cuerpo.f32_crudo(p.map_or(f32::NAN, |v| v as f32));
        }
    }
    w.u32(n);
    w.b.extend_from_slice(&cuerpo.b);
}

/// Las notas y los comentarios de las filas `f0..=f1` (el triángulo rojo y lo que se lee al tocar).
fn comentarios(w: &mut Bytes, hoja: &Hoja, f0: u32, f1: u32) {
    let com = &hoja.cola.comentarios;
    let desde = com.partition_point(|c| c.fila < f0);
    let estos: Vec<_> = com[desde..].iter().take_while(|c| c.fila <= f1).collect();
    w.u32(estos.len() as u32);
    for c in estos {
        w.u32(c.fila);
        w.u32(c.col);
        w.u8(c.encadenado as u8);
        w.str(&c.autor);
        w.str(&c.texto);
        w.u32(c.respuestas.len() as u32);
        for (a, t) in &c.respuestas {
            w.str(a);
            w.str(t);
        }
    }
}

/// **Los valores de la lista desplegable** de la celda, uno por renglón (solo para ver, como el
/// PC: no se cambia nada). `None` si la celda no tiene lista.
pub fn lista_en(l: &mut LibroAndroid, i: usize, fila: u32, col: u32) -> Option<String> {
    let (libro, h) = l.hoja_mut(i)?;
    let v = h.hoja.lista_en(fila, col)?;
    Some(pixpin_hojas::validacion::valores_lista(libro, &h.hoja, v).join("\n"))
}

/// La última fila con algo de la hoja `i` (o la de su `dimension`), si está abierta.
pub fn ultima_fila(l: &LibroAndroid, i: usize) -> u32 {
    l.hojas.get(i).and_then(|h| h.as_ref()).map_or(0, |h| h.hoja.max_fila().max(h.hoja.cabecera.dimension.map_or(0, |d| d.fila1)))
}
