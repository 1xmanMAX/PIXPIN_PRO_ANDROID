//! Para probar desde la consola: `convertir-plano <plano.dwg|dxf> <salida.pxcad> [carpeta SHX]`.
fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 3 {
        eprintln!("uso: convertir-plano <plano> <salida> [carpeta SHX]");
        std::process::exit(2);
    }
    let t = std::time::Instant::now();
    match pixpincad::convertir_a(std::path::Path::new(&a[1]), std::path::Path::new(&a[2]), a.get(3).map(String::as_str).unwrap_or("")) {
        Ok(r) => println!("{} en {:?}", r, t.elapsed()),
        Err(e) => {
            eprintln!("ERROR: {e}");
            std::process::exit(1);
        }
    }
}
