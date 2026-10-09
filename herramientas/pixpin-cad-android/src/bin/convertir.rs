//! Para probar desde la consola:
//!   `convertir-plano <plano.dwg|dxf> <salida.pxcad> [carpetas SHX]`
//!   `convertir-plano --3d <modelo> <salida.px3d>` (plano en 3D, LandXML, puntos, IFC, Revit)
//!   `convertir-plano --revit <modelo.rvt> <salida.ifc>`
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let t = std::time::Instant::now();
    let r = match a.get(1).map(String::as_str) {
        Some("--3d") if a.len() >= 4 => pixpincad::convertir_3d_a(std::path::Path::new(&a[2]), std::path::Path::new(&a[3])),
        Some("--revit") if a.len() >= 4 => pixpincad::revit_a_ifc_en(std::path::Path::new(&a[2]), std::path::Path::new(&a[3])).map(|_| "IFC escrito".into()),
        _ if a.len() >= 3 => pixpincad::convertir_a(std::path::Path::new(&a[1]), std::path::Path::new(&a[2]), a.get(3).map(String::as_str).unwrap_or("")),
        _ => {
            eprintln!("uso: convertir-plano [--3d|--revit] <entrada> <salida> [carpetas SHX]");
            std::process::exit(2);
        }
    };
    match r {
        Ok(r) => println!("{} en {:?}", r, t.elapsed()),
        Err(e) => {
            eprintln!("ERROR: {e}");
            std::process::exit(1);
        }
    }
}
