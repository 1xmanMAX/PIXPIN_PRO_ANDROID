//! **PXOF**: una página de Office ya maquetada, en bytes, para que Kotlin la pinte.
//!
//! Es la `pixpin_office::Pagina` del PC tal cual (textos, rectángulos, líneas, imágenes,
//! trayectos y grupos, en puntos, origen arriba a la izquierda) con dos cosas más hechas aquí
//! para que el pintor de Kotlin no tenga que saber de letras:
//!
//! - cada texto lleva, carácter a carácter, **la cara que lo pinta, su glifo y su x**, medidos
//!   con las mismas [`Letras`] con que se maquetó (el PC pinta igual: `DrawGlyphRun` con los
//!   avances medidos);
//! - el subrayado y el tachado van ya como líneas, con las medidas de su letra.
//!
//! Todo en little-endian. Lo lee `com.forge.pixpin.office.Pxof` (Kotlin).
//!
//! ```text
//! "PXOF" u16 versión
//! f32 ancho, f32 alto, u32 número, relleno fondo
//! u16 caras × { str ruta, u16 índice, u8 (1 negrita fingida, 2 cursiva fingida) }
//! u32 elementos × elemento
//! elemento = u8 tipo y:
//!   1 texto:    f32 x, f32 y, f32 tam, u32 argb, u8 (1 = total de páginas), f32 ancho,
//!               str enlace, str texto, u32 n × { u16 cara, u16 glifo, u32 carácter, f32 x }
//!   2 rect:     f32 x, y, ancho, alto, relleno, trazo
//!   3 línea:    f32 x1, y1, x2, y2, trazo
//!   4 imagen:   str id, f32 x, y, ancho, alto, u8 hay recorte [f32 ×4], f32 opacidad
//!   5 trayecto: relleno, trazo, u16 figuras × { u8 (1 cerrada, 2 rellenar, 4 trazar; bits 3-5 el
//!               tinte: 0 ninguno, 1 oscuro, 2 oscuro suave, 3 claro, 4 claro suave, 5 aparte),
//!               f32 x, y, u32 tramos × { u8 0 = línea f32 x, y | 1 = bézier f32 ×6 } }
//!   6 grupo:    f32 ×6 (a b c d e f), u8 hay recorte [f32 x, y, ancho, alto], u32 n × elemento
//!   7 efectos:  u8 n × efecto, u32 n × elemento (lo envuelto)
//! efecto  = u8 1 sombra exterior: u32 argb, f32 desenfoque, dx, dy, escala x, y, sesgo x, y (grados),
//!                                  ancla x, y (fracciones de la caja)
//!         | u8 2 sombra interior: u32 argb, f32 desenfoque, dx, dy
//!         | u8 3 brillo:          u32 argb, f32 radio
//!         | u8 4 borde suave:     f32 radio
//! relleno = u8 0 nada | 1 u32 argb | 2 f32 ángulo, u16 n × { f32 pos, u32 argb } | 3 u16 n × {…}
//! trazo   = u8 0 nada | 1 u32 argb, f32 grosor, u8 discontinuo (0 sólida 1 rayas 2 puntos 3 raya-punto)
//! str     = u32 bytes + UTF-8
//! ```
//!
//! v2 (13-oct-2026, PC 954e77e y f38c011): los efectos de DrawingML (7) y el tinte de las caras de
//! las formas predefinidas (bits 3-5 de la figura).

use std::collections::HashMap;

use pixpin_office::pagina::{Campo, Color, Discontinuo, Efecto, Elemento, Figura, Pagina, Relleno, Sombra, Subrayado, Texto, Tramo, Trazo};

use crate::letras::{Glifo, Letras};

pub const VERSION: u16 = 2;

/// Quien escribe una página: lleva la cuenta de las caras usadas.
pub struct Escritor<'a> {
    letras: &'a Letras,
    pub b: Vec<u8>,
    /// Cara de [`Letras`] → número de cara en esta página.
    caras: HashMap<usize, u16>,
    orden: Vec<usize>,
    glifos: Vec<Glifo>,
}

impl<'a> Escritor<'a> {
    pub fn nuevo(letras: &'a Letras) -> Escritor<'a> {
        Escritor { letras, b: Vec::with_capacity(16 * 1024), caras: HashMap::new(), orden: Vec::new(), glifos: Vec::new() }
    }

    pub fn u8(&mut self, v: u8) {
        self.b.push(v);
    }
    pub fn u16(&mut self, v: u16) {
        self.b.extend_from_slice(&v.to_le_bytes());
    }
    pub fn u32(&mut self, v: u32) {
        self.b.extend_from_slice(&v.to_le_bytes());
    }
    pub fn f32(&mut self, v: f32) {
        self.b.extend_from_slice(&(if v.is_finite() { v } else { 0.0 }).to_le_bytes());
    }
    pub fn str(&mut self, s: &str) {
        self.u32(s.len() as u32);
        self.b.extend_from_slice(s.as_bytes());
    }
    pub fn color(&mut self, c: Color) {
        self.u32(argb(c));
    }

    pub fn relleno(&mut self, r: Option<&Relleno>) {
        match r {
            None => self.u8(0),
            Some(Relleno::Solido(c)) => {
                self.u8(1);
                self.color(*c);
            }
            Some(Relleno::Lineal { paradas, angulo }) => {
                self.u8(2);
                self.f32(*angulo);
                self.paradas(paradas);
            }
            Some(Relleno::Radial { paradas }) => {
                self.u8(3);
                self.paradas(paradas);
            }
        }
    }

    fn paradas(&mut self, p: &[(f32, Color)]) {
        let n = p.len().min(u16::MAX as usize);
        self.u16(n as u16);
        for (pos, c) in &p[..n] {
            self.f32(*pos);
            self.color(*c);
        }
    }

    pub fn trazo(&mut self, t: Option<&Trazo>) {
        match t {
            None => self.u8(0),
            Some(t) => {
                self.u8(1);
                self.color(t.color);
                self.f32(t.grosor);
                self.u8(match t.discontinuo {
                    Discontinuo::Solida => 0,
                    Discontinuo::Rayas => 1,
                    Discontinuo::Puntos => 2,
                    Discontinuo::RayaPunto => 3,
                });
            }
        }
    }

    fn cara(&mut self, i: usize) -> u16 {
        if let Some(c) = self.caras.get(&i) {
            return *c;
        }
        let n = self.orden.len() as u16;
        self.caras.insert(i, n);
        self.orden.push(i);
        n
    }

    /// Cuántos elementos ocupa `e` en el formato (el subrayado y el tachado son líneas aparte).
    fn cuantos(e: &Elemento) -> u32 {
        match e {
            Elemento::Texto(t) => {
                1 + match t.subrayado {
                    Some(Subrayado::Doble) => 2,
                    Some(_) => 1,
                    None => 0,
                } + t.tachado as u32
            }
            _ => 1,
        }
    }

    pub fn elementos(&mut self, els: &[Elemento]) {
        let n: u32 = els.iter().map(Self::cuantos).sum();
        self.u32(n);
        for e in els {
            self.elemento(e);
        }
    }

    pub fn elemento(&mut self, e: &Elemento) {
        match e {
            Elemento::Texto(t) => self.texto(t),
            Elemento::Rect { rect, relleno, borde } => {
                self.u8(2);
                for v in [rect.x, rect.y, rect.ancho, rect.alto] {
                    self.f32(v);
                }
                self.relleno(relleno.as_ref());
                self.trazo(borde.as_ref());
            }
            Elemento::Linea { desde, hasta, trazo } => self.linea(*desde, *hasta, trazo),
            Elemento::Imagen { id, rect, recorte, opacidad } => {
                self.u8(4);
                self.str(id);
                for v in [rect.x, rect.y, rect.ancho, rect.alto] {
                    self.f32(v);
                }
                match recorte {
                    Some(r) => {
                        self.u8(1);
                        for v in r {
                            self.f32(*v);
                        }
                    }
                    None => self.u8(0),
                }
                self.f32(*opacidad);
            }
            Elemento::Trayecto { figuras, relleno, borde } => {
                self.u8(5);
                self.relleno(relleno.as_ref());
                self.trazo(borde.as_ref());
                let n = figuras.len().min(u16::MAX as usize);
                self.u16(n as u16);
                for f in &figuras[..n] {
                    self.figura(f);
                }
            }
            Elemento::Grupo { transformada, recorte, elementos } => {
                self.u8(6);
                for v in transformada {
                    self.f32(*v);
                }
                match recorte {
                    Some(r) => {
                        self.u8(1);
                        for v in [r.x, r.y, r.ancho, r.alto] {
                            self.f32(v);
                        }
                    }
                    None => self.u8(0),
                }
                self.elementos(elementos);
            }
            Elemento::Efecto { efectos, elementos } => {
                self.u8(7);
                let n = efectos.len().min(u8::MAX as usize);
                self.u8(n as u8);
                for ef in &efectos[..n] {
                    self.efecto(ef);
                }
                self.elementos(elementos);
            }
        }
    }

    fn efecto(&mut self, e: &Efecto) {
        match *e {
            Efecto::SombraExterior { color, desenfoque, dx, dy, escala, sesgo, ancla } => {
                self.u8(1);
                self.color(color);
                for v in [desenfoque, dx, dy, escala.0, escala.1, sesgo.0, sesgo.1, ancla.0, ancla.1] {
                    self.f32(v);
                }
            }
            Efecto::SombraInterior { color, desenfoque, dx, dy } => {
                self.u8(2);
                self.color(color);
                for v in [desenfoque, dx, dy] {
                    self.f32(v);
                }
            }
            Efecto::Brillo { color, radio } => {
                self.u8(3);
                self.color(color);
                self.f32(radio);
            }
            Efecto::BordeSuave { radio } => {
                self.u8(4);
                self.f32(radio);
            }
        }
    }

    fn figura(&mut self, f: &Figura) {
        let tinte: u8 = match f.sombra {
            Sombra::Ninguna => 0,
            Sombra::Oscura => 1,
            Sombra::OscuraSuave => 2,
            Sombra::Clara => 3,
            Sombra::ClaraSuave => 4,
            Sombra::Aparte => 5,
        };
        self.u8(f.cerrada as u8 | (f.rellenar as u8) << 1 | (f.trazar as u8) << 2 | tinte << 3);
        self.f32(f.inicio.0);
        self.f32(f.inicio.1);
        self.u32(f.tramos.len() as u32);
        for t in &f.tramos {
            match *t {
                Tramo::Linea(x, y) => {
                    self.u8(0);
                    self.f32(x);
                    self.f32(y);
                }
                Tramo::Bezier(a, b, c) => {
                    self.u8(1);
                    for v in [a.0, a.1, b.0, b.1, c.0, c.1] {
                        self.f32(v);
                    }
                }
            }
        }
    }

    fn linea(&mut self, a: (f32, f32), b: (f32, f32), t: &Trazo) {
        self.u8(3);
        for v in [a.0, a.1, b.0, b.1] {
            self.f32(v);
        }
        self.trazo(Some(t));
    }

    fn texto(&mut self, t: &Texto) {
        let mut glifos = std::mem::take(&mut self.glifos);
        glifos.clear();
        self.letras.glifos(&t.texto, &t.letra, &mut glifos);
        self.u8(1);
        self.f32(t.x);
        self.f32(t.y);
        self.f32(t.letra.tam);
        self.color(t.color);
        self.u8((t.campo == Some(Campo::TotalPaginas)) as u8);
        // Las x de cada letra, como las pinta el PC: avance medido + interletrado, y el
        // justificado en los espacios.
        let tam = t.letra.tam;
        let mut x = 0.0f32;
        let mut xs = Vec::with_capacity(glifos.len());
        for g in &glifos {
            xs.push(x);
            let mut av = g.avance * tam + t.espaciado;
            if g.caracter == ' ' {
                av += t.extra_espacio;
            }
            x += av;
        }
        let ancho = if t.campo == Some(Campo::TotalPaginas) { x } else { t.ancho.max(x - t.extra_espacio) };
        self.f32(ancho);
        self.str(t.enlace.as_deref().unwrap_or(""));
        self.str(&t.texto);
        self.u32(glifos.len() as u32);
        for (g, x) in glifos.iter().zip(&xs) {
            let c = self.cara(g.cara);
            self.u16(c);
            self.u16(g.glifo);
            self.u32(g.caracter as u32);
            self.f32(*x);
        }
        // Subrayado y tachado con las medidas de la letra (como `PintorOffice::texto`).
        let m = glifos.first().and_then(|g| self.letras.con_cara(g.cara, |c| c.m)).unwrap_or([0.9, 0.25, 0.0, -0.1, 0.05, 0.3, 0.05]);
        if let Some(s) = t.subrayado {
            let chars: Vec<char> = t.texto.chars().collect();
            let fin = t.texto.trim_end().chars().count();
            let ancho_sub = if fin < chars.len() {
                let quitar: f32 = glifos[fin.min(glifos.len())..].iter().map(|g| g.avance * tam + t.espaciado + t.extra_espacio).sum();
                (ancho - quitar).max(0.0)
            } else {
                ancho
            };
            let grosor = (m[4] * tam).max(0.5) * if s == Subrayado::Grueso { 2.0 } else { 1.0 };
            let y = t.y - m[3] * tam;
            let trazo = Trazo {
                color: t.color,
                grosor,
                discontinuo: match s {
                    Subrayado::Puntos => Discontinuo::Puntos,
                    Subrayado::Rayas => Discontinuo::Rayas,
                    _ => Discontinuo::Solida,
                },
            };
            self.linea((t.x, y), (t.x + ancho_sub, y), &trazo);
            if s == Subrayado::Doble {
                self.linea((t.x, y + grosor * 2.0), (t.x + ancho_sub, y + grosor * 2.0), &trazo);
            }
        }
        if t.tachado {
            let y = t.y - m[5] * tam;
            self.linea((t.x, y), (t.x + ancho, y), &Trazo::solido(t.color, (m[6] * tam).max(0.5)));
        }
        self.glifos = glifos;
    }

    /// La página entera: cabecera, caras usadas y elementos.
    pub fn pagina(letras: &Letras, p: &Pagina) -> Vec<u8> {
        let mut cuerpo = Escritor::nuevo(letras);
        cuerpo.elementos(&p.elementos);
        let mut w = Escritor::nuevo(letras);
        w.b.extend_from_slice(b"PXOF");
        w.u16(VERSION);
        w.f32(p.ancho);
        w.f32(p.alto);
        w.u32(p.numero);
        w.relleno(p.fondo.as_ref());
        cuerpo.cabecera_de_caras(&mut w);
        w.b.extend_from_slice(&cuerpo.b);
        w.b
    }

    /// Escribe en `w` la tabla de caras que usó este escritor.
    pub fn cabecera_de_caras(&self, w: &mut Escritor) {
        w.u16(self.orden.len() as u16);
        for &i in &self.orden {
            let (ruta, indice, flags) = self
                .letras
                .con_cara(i, |c| (c.ruta.clone(), c.indice, c.negrita_falsa as u8 | (c.cursiva_falsa as u8) << 1))
                .unwrap_or_default();
            w.str(&ruta);
            w.u16(indice as u16);
            w.u8(flags);
        }
    }
}

pub fn argb(c: Color) -> u32 {
    (c.a as u32) << 24 | (c.r as u32) << 16 | (c.g as u32) << 8 | c.b as u32
}
