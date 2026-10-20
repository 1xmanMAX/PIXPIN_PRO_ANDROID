// ---------------------------------------------------------------------------
// **Lo de Windows, en el teléfono** (lo añade `preparar.sh` al final de `hoja_a_lienzo.rs`, del que
// quita los suyos): leer la hoja y pintar lo hecho para comprobar lo hace Kotlin por el puente.

/// **Lee la hoja** como el PC: la imagen (o la página del PDF pintada a [`ANCHO_PAGINA`]) la da el
/// teléfono, con la capa de texto del PDF si la tiene; sin ella, el OCR ([`ocr`], el del PC con
/// el de ML Kit debajo).
pub fn leer(ruta: &Path, pagina: Option<usize>) -> Result<Pagina, String> {
    let t = Instant::now();
    let leida = crate::puente::leer(ruta, pagina, ANCHO_PAGINA)?;
    let mut img = leida.imagen;
    let palabras = match leida.trozos {
        Some((trozos, ancho_pt)) if trozos.len() >= 4 && ancho_pt > 0.0 => palabras_de_trozos(&trozos, img.ancho as f64 / ancho_pt),
        _ => {
            let mayor = img.ancho.max(img.alto);
            if mayor > LADO_MAXIMO {
                let k = LADO_MAXIMO as f64 / mayor as f64;
                let (w, h) = (((img.ancho as f64 * k) as u32).max(1), ((img.alto as f64 * k) as u32).max(1));
                img = pixpin_codec::redimensionar(img, w, h).map_err(|e| format!("no se pudo reducir la imagen: {e:?}"))?;
            }
            ocr(&img.pixeles, img.ancho, img.alto)?
        }
    };
    Ok(Pagina { rgba: img.pixeles, ancho: img.ancho, alto: img.alto, palabras, ms_leer: t.elapsed().as_millis() })
}

/// **Lo hecho, pintado** como lo vería el lienzo del teléfono (su `Renderer`), a `ancho` píxeles:
/// con la escala de la hoja, sus píxeles caen sobre los del original.
pub fn pintar(hechos: &[Hecho], tam: (f32, f32), ancho: u32) -> Result<(Vec<u8>, u32, u32), String> {
    let elementos: Vec<serde_json::Value> = hechos.iter().flat_map(|h| h.elementos.iter().cloned()).collect();
    let imagenes: Vec<(u64, pixpin_codec::ImagenRgba)> = hechos
        .iter()
        .flat_map(|h| h.imagenes.iter())
        .map(|i| (i.id, pixpin_codec::ImagenRgba { ancho: i.ancho, alto: i.alto, pixeles: i.rgba.clone() }))
        .collect();
    crate::puente::pintar(&pixpin_ia::lienzo::documento(elementos), &imagenes, tam, ancho)
}
