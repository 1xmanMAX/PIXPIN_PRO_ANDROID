//! De `asistente/documentos_acciones.rs` del PC: la tabla de una foto al lienzo, con su aspecto
//! (la usa el especialista de tablas de la hoja al lienzo).

use std::path::Path;

use pixpin_ia::acciones::normalizar;
use pixpin_ia::tabla_escaneada::{self, Palabra, TablaLeida};
use pixpin_motor2d::{ColorRgba, Elemento};
use serde_json::{Value, json};

/// El ancho al que se pinta una hoja para el OCR (~200 ppp en A4).
const ANCHO_OCR: u32 = 1700;

/// Las palabras de una imagen o de la hoja `pagina` (desde 0; `None`: la
/// primera con texto) de un PDF: la capa de texto si la tiene, si no OCR.
pub fn palabras_de(ruta: &Path, pagina: Option<usize>) -> Result<(Vec<Palabra>, usize), String> {
    if super::ocr::es_imagen(ruta) {
        let img = pixpin_codec::imagen::cargar(ruta).map_err(|e| format!("no se pudo abrir la imagen: {e}"))?;
        return Ok((palabras_de_ocr(&img.pixeles, img.ancho, img.alto)?, 0));
    }
    // La capa de texto del PDF (exacta y al instante).
    if let Ok(trozos) = pixpin_pdf::texto::trozos_de_fichero(ruta) {
        let elegida = match pagina {
            Some(p) => Some(p),
            None => trozos.iter().position(|t| t.len() >= 4),
        };
        if let Some(p) = elegida
            && let Some(t) = trozos.get(p)
            && t.len() >= 4
        {
            let ps = t
                .iter()
                .map(|t| Palabra::nueva(&t.texto, t.x as f64, (t.y - t.alto * 0.8) as f64, (t.fin - t.x).max(0.0) as f64, t.alto as f64))
                .collect();
            return Ok((ps, p));
        }
    }
    // Escaneado: la hoja pintada, por el OCR.
    let doc = pixpin_pdf::Documento::abrir(ruta).map_err(|e| format!("no se pudo abrir el PDF: {e}"))?;
    let p = pagina.unwrap_or(0);
    if p as u32 >= doc.paginas() {
        return Err(format!("el PDF tiene {} paginas", doc.paginas()));
    }
    let img = doc.renderizar(p as u32, ANCHO_OCR).map_err(|e| e.to_string())?;
    Ok((palabras_de_ocr(&img.pixeles, img.ancho, img.alto)?, p))
}

fn palabras_de_ocr(rgba: &[u8], ancho: u32, alto: u32) -> Result<Vec<Palabra>, String> {
    if !pixpin_ocr::disponible() {
        return Err("el OCR de Windows no esta disponible (falta el idioma)".into());
    }
    let lineas = pixpin_ocr::reconocer(ancho, alto, rgba).map_err(|e| format!("el OCR fallo: {e:?}"))?;
    Ok(lineas
        .iter()
        .flat_map(|l| l.palabras.iter())
        .map(|p| Palabra::nueva(&p.texto, p.caja.x as f64, p.caja.y as f64, p.caja.ancho as f64, p.caja.alto as f64))
        .collect())
}

/// **Lee la tabla** del archivo (sin mandar nada).
pub fn leer_tabla(ruta: &Path, pagina: Option<usize>) -> Result<TablaLeida, String> {
    let (ps, p) = palabras_de(ruta, pagina)?;
    let t = tabla_escaneada::reconstruir(&ps);
    if t.filas.len() < 2 || t.columnas() < 2 {
        return Err(format!(
            "en la pagina {} no se ve una tabla (salieron {} filas y {} columnas)",
            p + 1,
            t.filas.len(),
            t.columnas()
        ));
    }
    Ok(t)
}

/// **El aspecto de la tabla** (`pixpin_ia::tabla_aspecto`): en la foto, o
/// en la pagina del PDF pintada, con las palabras del OCR en sus mismos
/// pixeles. Con el ancho de esa imagen (para pasarlo a la escala del lienzo).
/// El fondo a blanco y lo que se aparta de el, oscurecido de mas
/// (`(v/fondo)^4`): la raya casi del color del fondo queda gris y se mide.
pub(crate) fn rayas_marcadas(px: &mut [u8]) {
    let mut g: Vec<u8> = px.chunks_exact(4).step_by(5).map(|p| ((p[0] as u32 * 299 + p[1] as u32 * 587 + p[2] as u32 * 114) / 1000) as u8).collect();
    g.sort_unstable();
    let fondo = g.get(g.len() / 2).copied().unwrap_or(255).max(1) as f32;
    for p in px.chunks_exact_mut(4) {
        for c in &mut p[..3] {
            *c = (255.0 * (*c as f32 / fondo).min(1.0).powi(4)).round() as u8;
        }
    }
}

pub fn aspecto_de_tabla(ruta: &Path, pagina: Option<usize>) -> Option<(pixpin_ia::tabla_aspecto::Medida, u32)> {
    let img = if super::ocr::es_imagen(ruta) {
        pixpin_codec::imagen::cargar(ruta).ok()?
    } else {
        let doc = pixpin_pdf::Documento::abrir(ruta).ok()?;
        let p = match pagina {
            Some(p) => p,
            None => palabras_de(ruta, None).ok()?.1,
        };
        doc.renderizar(p as u32, ANCHO_OCR).ok()?
    };
    let ps = palabras_de_ocr(&img.pixeles, img.ancho, img.alto).ok()?;
    let mut m = pixpin_ia::tabla_aspecto::medir(&img.pixeles, img.ancho, img.alto, &ps)?;
    // **Rayas tenues** (18-oct, una tabla de tema oscuro leida en negativo):
    // sin rejilla, se mide otra vez sobre una copia con las rayas marcadas,
    // con las palabras de la de verdad (el OCR lee mejor la normal).
    if m.columnas.is_empty() {
        let mut fuerte = img.pixeles.clone();
        rayas_marcadas(&mut fuerte);
        if let Some(mut m2) = pixpin_ia::tabla_aspecto::medir(&fuerte, img.ancho, img.alto, &ps)
            && !m2.columnas.is_empty()
        {
            // (Los colores, los de la foto: la copia los oscurece.)
            m2.texto = m.texto;
            m = m2;
        }
    }
    // Torcida: el OCR otra vez sobre la foto enderezada (lee mucho mejor:
    // «p-OI» era «P-01») y la medida con esas palabras.
    if m.inclinacion != 0.0
        && !m.columnas.is_empty()
        && let derecha = pixpin_ia::tabla_aspecto::enderezar(&img.pixeles, img.ancho, img.alto, m.inclinacion)
        && let Ok(ps2) = palabras_de_ocr(&derecha, img.ancho, img.alto)
        && let Some(mut m2) = pixpin_ia::tabla_aspecto::medir(&derecha, img.ancho, img.alto, &ps2)
    {
        m2.inclinacion = m.inclinacion;
        return Some((m2, img.ancho));
    }
    Some((m, img.ancho))
}

/// Las celdas que se usan: las de la rejilla medida en una foto (sin el
/// texto de la pagina, enderezada si estaba torcida) o, si no hay rejilla o
/// es un PDF que ya cuadra con ella, las de [`leer_tabla`] (en un PDF, su
/// capa de texto, exacta).
pub fn filas_de(leida: &TablaLeida, m: &pixpin_ia::tabla_aspecto::Medida, ruta: &Path) -> Vec<Vec<String>> {
    let rejilla = !m.celdas.is_empty() && m.celdas.iter().flatten().any(|c| !c.is_empty());
    let pdf_que_cuadra = !super::ocr::es_imagen(ruta) && m.cuadra(leida.filas.len(), leida.columnas());
    if rejilla && !pdf_que_cuadra { m.celdas.clone() } else { leida.filas.clone() }
}

/// La tabla con otras celdas: si cambian, sus combinadas (de la lectura de
/// antes) ya no valen.
fn con_filas(leida: TablaLeida, filas: Vec<Vec<String>>) -> TablaLeida {
    if filas == leida.filas { leida } else { TablaLeida { filas, combinadas: Vec::new() } }
}

/// **La tabla de un fichero (foto o pagina de PDF) para el lienzo**: lo de
/// [`tabla_para_el_lienzo`] sin elegir el adjunto. Tambien la usa calcar
/// (18-oct) con el recorte de una zona que el planificador dijo que es una
/// tabla. Devuelve ademas donde empieza la tabla en la imagen (pixeles):
/// los elementos salen con su esquina en (0, 0).
/// La letra de las tablas del lienzo: una del catalogo del lienzo (18-oct:
/// con «Segoe UI», que no lo esta, el fichero guardaba la manuscrita).
const LETRA_DE_TABLA: &str = "Work Sans";

pub fn tabla_de_fichero(
    ruta: &Path,
    nombre: &str,
    pagina: Option<usize>,
    cabecera: Option<bool>,
    escala: Option<f32>,
    corregir: Option<&dyn pixpin_ia::Motor>,
) -> Result<(Vec<Elemento>, String, (f64, f64)), String> {
    use pixpin_motor2d::tabla_dibujada::{Aspecto, Celda, elementos_de_tabla_con_aspecto};
    struct C<'a> {
        ruta: &'a Path,
        nombre: &'a str,
    }
    let c = C { ruta, nombre };
    let leida = leer_tabla(c.ruta, pagina)?;
    let cabecera = cabecera.unwrap_or_else(|| leida.parece_cabecera());
    let (mut m, ancho_img) = aspecto_de_tabla(c.ruta, pagina).ok_or("no se pudo medir la tabla en la imagen")?;
    // Lo que el OCR leyo mal (tildes, «°»): con la vista de la nube, si la hay.
    let mut corregidas = 0;
    if let Some(motor) = corregir
        && !m.celdas.is_empty()
    {
        corregidas = corregir_con_la_nube(motor, &c.ruta, pagina, &mut m);
    }
    let filas = filas_de(&leida, &m, &c.ruta);
    let leida = con_filas(leida, filas);
    let k = escala.unwrap_or(if super::ocr::es_imagen(&c.ruta) { 1.0 } else { 1400.0 / ancho_img as f32 });
    let col = |c: [u8; 3]| ColorRgba::opaco(c[0] as f32 / 255.0, c[1] as f32 / 255.0, c[2] as f32 / 255.0);
    let (filas, columnas) = (leida.filas.len(), leida.columnas());
    let cuadra = m.cuadra(filas, columnas);
    let aspecto = Aspecto {
        anchos: cuadra.then(|| m.anchos().iter().map(|a| *a as f32 * k).collect()),
        altos: cuadra.then(|| m.altos().iter().map(|a| *a as f32 * k).collect()),
        fondos: cuadra.then(|| m.fondos.iter().map(|f| f.iter().map(|x| x.map(col)).collect()).collect()),
        borde: m.borde.map(|(c, g)| (col(c), (g as f32 * k).max(0.75))),
        // (El aire medido, como mucho una letra: en la de tema oscuro salia
        // de cuatro y el texto quedaba en mitad de la celda.)
        aire: m.aire.filter(|_| cuadra).map(|a| a.min(m.tam_letra) as f32 * k),
    };
    let celdas: Vec<Vec<Celda>> = leida
        .filas
        .iter()
        .enumerate()
        .map(|(f, fila)| fila.iter().map(|t| Celda { negrita: cabecera && f == 0, ..Celda::de(t.clone()) }).collect())
        .collect();
    let tam = (m.tam_letra as f32 * k).clamp(6.0, 120.0);
    // (Una tabla de tema oscuro: si la letra medida casi no se distingue del
    // fondo de sus celdas, clara sobre oscuro y oscura sobre claro.)
    let texto = {
        let fs: Vec<[u8; 3]> = m.fondos.iter().flatten().flatten().copied().collect();
        let gris = |c: [u8; 3]| c[0] as f32 * 0.299 + c[1] as f32 * 0.587 + c[2] as f32 * 0.114;
        match fs.get(fs.len() / 2) {
            Some(&f) if (gris(f) - gris(m.texto)).abs() < 70.0 => {
                if gris(f) < 128.0 { [235, 235, 235] } else { [25, 25, 25] }
            }
            _ => m.texto,
        }
    };
    let medir = |s: &str, t: f32| pixpin_motor2d::texto::medida(s, t, LETRA_DE_TABLA, Default::default());
    // (Que cada renglon quepa en su columna medida: la letra del lienzo es
    // mas ancha que la de la foto y se salia de la celda.)
    let tam = match &aspecto.anchos {
        Some(anchos) => {
            let aire = aspecto.aire.unwrap_or(tam * 0.5) * 2.0;
            let mut k_fit = 1.0f32;
            for fila in &leida.filas {
                for (c, t) in fila.iter().enumerate() {
                    let Some(&ancho) = anchos.get(c) else { continue };
                    let peso = if cabecera { 1.06 } else { 1.0 };
                    for l in t.lines() {
                        let (w, _) = medir(l, tam);
                        if w * peso > 0.0 {
                            k_fit = k_fit.min(((ancho - aire).max(ancho * 0.5)) / (w * peso));
                        }
                    }
                }
            }
            (tam * k_fit.clamp(0.5, 1.0)).max(6.0)
        }
        None => tam,
    };
    let estilo = pixpin_motor2d::ecuacion::Estilo { color: col(texto), tam, opacidad: 1.0, familia: LETRA_DE_TABLA.into() };
    let mut els = elementos_de_tabla_con_aspecto(&celdas, &estilo, pixpin_motor2d::vector::Punto2::nuevo(0.0, 0.0), &medir, cabecera, &aspecto);
    if els.is_empty() {
        return Err("no salio ninguna celda que dibujar".into());
    }
    // **La negrita de la foto** (18-oct): la celda entera, negrita de verdad;
    // un tramo, como texto rico del lienzo. Unas marcas sueltas, fuera.
    for e in &mut els {
        if let pixpin_motor2d::elemento::Figura::Texto { texto, .. } = &mut e.figura
            && texto.contains("**")
        {
            let t = texto.clone();
            if t.matches("**").count() % 2 == 1 {
                *texto = t.replace("**", "");
            } else if let Some(dentro) = t.trim().strip_prefix("**").and_then(|x| x.strip_suffix("**")).filter(|x| !x.contains("**")) {
                *texto = dentro.to_string();
                e.extras.negrita = true;
            } else {
                pixpin_motor2d::texto_rico::poner_fuente(e, &t);
            }
        }
    }
    // El texto de alrededor (un titulo, una nota): en su sitio respecto de
    // la tabla, con su letra (16-oct: faltaba el titulo).
    if cuadra {
        let (x0, y0) = (m.columnas[0], m.filas[0]);
        for r in &m.fuera {
            let t = (r.tam as f32 * k).clamp(6.0, 160.0);
            let (w, h) = medir(&r.texto, t);
            els.push(pixpin_motor2d::ecuacion::elemento_texto(&r.texto, ((r.x - x0) as f32) * k, ((r.y - y0) as f32) * k, (w, h), t, false, &estilo));
        }
    }
    let grupo = format!("tabla-foto-{}", pixpin_shell::entorno::ahora_utc_ms());
    for e in &mut els {
        e.grupos = vec![grupo.clone()];
    }
    let fondos = m.fondos.iter().flatten().filter(|f| f.is_some()).count();
    let fuera = if cuadra { m.fuera.len() } else { 0 };
    let origen = if cuadra { (m.columnas[0], m.filas[0]) } else { (0.0, 0.0) };
    Ok((
        els,
        format!(
            "hecho: la tabla de «{}» ya esta en el lienzo, a la vista y como en la foto ({filas} filas x {columnas} columnas{}{}{}{}). No la dibujes otra vez ni la muevas",
            c.nombre,
            if cuadra { ", con su letra, anchos y altos" } else { "" },
            if fondos > 0 { ", con los colores de fondo" } else { "" },
            if fuera > 0 { format!(", y {fuera} renglon(es) de texto de alrededor") } else { String::new() },
            if corregidas > 0 { format!(", {corregidas} lectura(s) del OCR corregidas mirando la foto") } else { String::new() }
        ),
        origen,
    ))
}

/// Lo que se parecen dos lecturas sin tildes ni signos: la correccion de la
/// nube solo puede arreglar letras, no inventar celdas.
fn se_parece(a: &str, b: &str) -> bool {
    let plano = |s: &str| -> Vec<char> { normalizar(s).chars().filter(|c| c.is_alphanumeric()).collect() };
    let (a, b) = (plano(a), plano(b));
    let mut d: Vec<usize> = (0..=b.len()).collect();
    for i in 1..=a.len() {
        let mut antes = d[0];
        d[0] = i;
        for j in 1..=b.len() {
            let t = d[j];
            d[j] = (d[j] + 1).min(d[j - 1] + 1).min(antes + usize::from(a[i - 1] != b[j - 1]));
            antes = t;
        }
    }
    d[b.len()] <= (a.len().max(b.len()) / 5).max(2)
}

/// **La pasada de la nube**: la foto de la tabla (recortada a ella y a su
/// texto, enderezada si hacia falta) y lo leido en JSON; vuelve lo mismo con
/// las letras corregidas. Solo se acepta lo que se parece a lo leido.
/// Devuelve cuantas lecturas cambio.
fn corregir_con_la_nube(motor: &dyn pixpin_ia::Motor, ruta: &Path, pagina: Option<usize>, m: &mut pixpin_ia::tabla_aspecto::Medida) -> usize {
    use pixpin_ia::motor::{Mensaje, Peticion, Seguir};
    let img = if super::ocr::es_imagen(ruta) {
        pixpin_codec::imagen::cargar(ruta).ok()
    } else {
        pixpin_pdf::Documento::abrir(ruta).ok().and_then(|d| d.renderizar(pagina.unwrap_or(0) as u32, ANCHO_OCR).ok())
    };
    let Some(mut img) = img else { return 0 };
    if m.inclinacion != 0.0 {
        img.pixeles = pixpin_ia::tabla_aspecto::enderezar(&img.pixeles, img.ancho, img.alto, m.inclinacion);
    }
    // Recortada a la tabla y a lo que la rodea, con aire.
    let mut caja = [m.columnas[0], m.filas[0], *m.columnas.last().unwrap_or(&0.0), *m.filas.last().unwrap_or(&0.0)];
    for r in &m.fuera {
        caja = [caja[0].min(r.x), caja[1].min(r.y), caja[2].max(r.x + r.tam * r.texto.chars().count() as f64 * 0.6), caja[3].max(r.y + r.tam * 1.3)];
    }
    let (x0, y0) = ((caja[0] - 10.0).max(0.0) as u32, (caja[1] - 10.0).max(0.0) as u32);
    let (x1, y1) = (((caja[2] + 10.0) as u32).min(img.ancho), ((caja[3] + 10.0) as u32).min(img.alto));
    if x1 <= x0 + 8 || y1 <= y0 + 8 {
        return 0;
    }
    let (w, h) = (x1 - x0, y1 - y0);
    let mut px = Vec::with_capacity((w * h * 4) as usize);
    for y in y0..y1 {
        let i = ((y * img.ancho + x0) * 4) as usize;
        px.extend_from_slice(&img.pixeles[i..i + (w * 4) as usize]);
    }
    let mut recorte = pixpin_codec::ImagenRgba { ancho: w, alto: h, pixeles: px };
    if w > 1500 {
        match pixpin_codec::imagen::redimensionar(recorte, 1500, (h * 1500 / w).max(1)) {
            Ok(r) => recorte = r,
            Err(_) => return 0,
        }
    }
    let Ok(jpg) = pixpin_codec::imagen::codificar_jpg(&recorte, 88) else { return 0 };
    let leido = json!({"celdas": m.celdas, "fuera": m.fuera.iter().map(|r| r.texto.clone()).collect::<Vec<_>>()});
    let pedido = format!(
        "Esta es la foto de una tabla y, en JSON, lo que leyo el OCR. Corrige SOLO las letras mal leidas mirando la foto \
         (tildes, eñe, grados, simbolos, mayusculas, 0 por O): no cambies, anadas, quites ni muevas celdas ni palabras. \
         Las palabras que en la foto estan en NEGRITA van entre **...** (solo esas: un tramo o la celda entera). \
         Si una celda de la foto tiene texto y en el JSON esta vacia, escribelo. \
         Contesta solo el JSON con la misma forma.\n{leido}"
    );
    let mut p = Peticion::nueva(vec![Mensaje::usuario_con_imagen(pedido, jpg, "image/jpeg")]);
    p.max_tokens = 3000;
    p.temperatura = 0.0;
    let Ok(r) = motor.completar(&p, &mut |_| Seguir::Si) else { return 0 };
    let (Some(a), Some(b)) = (r.texto.find('{'), r.texto.rfind('}')) else { return 0 };
    let Ok(v) = serde_json::from_str::<Value>(&r.texto[a..=b]) else { return 0 };
    let mut cambios = 0;
    if let Some(filas) = v["celdas"].as_array()
        && filas.len() == m.celdas.len()
    {
        for (f, fila) in filas.iter().enumerate() {
            let Some(fila) = fila.as_array().filter(|x| x.len() == m.celdas[f].len()) else { continue };
            for (c, nuevo) in fila.iter().enumerate() {
                // (Las marcas de negrita no cuentan al compararlo; una celda
                // que el OCR dejo vacia se acepta como venga.)
                if let Some(n) = nuevo.as_str().map(str::trim)
                    && n != m.celdas[f][c]
                    && (m.celdas[f][c].trim().is_empty() && n.chars().count() <= 80 || se_parece(&n.replace("**", ""), &m.celdas[f][c]))
                {
                    m.celdas[f][c] = n.to_string();
                    cambios += 1;
                }
            }
        }
    }
    if let Some(fuera) = v["fuera"].as_array()
        && fuera.len() == m.fuera.len()
    {
        for (r, nuevo) in m.fuera.iter_mut().zip(fuera) {
            if let Some(n) = nuevo.as_str().map(str::trim)
                && n != r.texto
                && se_parece(n, &r.texto)
            {
                r.texto = n.to_string();
                cambios += 1;
            }
        }
    }
    cambios
}

