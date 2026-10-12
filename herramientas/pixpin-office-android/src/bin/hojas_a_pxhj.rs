//! Abre un libro de Excel como lo hará la app y deja en una carpeta lo que recibe Kotlin: el
//! libro (PXHL), la geometría de la hoja (PXHG) y sus trozos de filas (PXHT), con los tiempos.
//! Sirve para medir y para hacer los fixtures de las pruebas de Kotlin.
//!
//! ```text
//! hojas-a-pxhj <libro.xlsx> <carpeta de salida> [hoja] [filas por trozo] [trozos que guardar]
//! ```

use std::time::Instant;

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    if a.len() < 2 {
        eprintln!("uso: hojas-a-pxhj <libro.xlsx> <salida> [hoja] [filas por trozo] [trozos que guardar]");
        std::process::exit(2);
    }
    let salida = std::path::Path::new(&a[1]);
    std::fs::create_dir_all(salida).expect("carpeta de salida");
    let por_trozo: u32 = a.get(3).and_then(|s| s.parse().ok()).unwrap_or(64);
    let guardar: u32 = a.get(4).and_then(|s| s.parse().ok()).unwrap_or(2);
    let t = Instant::now();
    let mut l = pixpinoffice::hojas::LibroAndroid::abrir(a[0].as_ref(), pixpinoffice::hojas::regional_de("es\n.\n,\n/\ndd/mm/yyyy"))
        .unwrap_or_else(|e| {
            eprintln!("no se pudo abrir: {e}");
            std::process::exit(1)
        });
    let i: usize = a.get(2).and_then(|s| s.parse().ok()).unwrap_or(l.libro.hoja_activa);
    std::fs::write(salida.join("libro.pxhl"), l.bytes_libro()).expect("escribir");
    println!("abrir el libro: {:.1} ms ({} hojas)", ms(t), l.libro.hojas.len());
    // La primera pantalla: como la app (60 filas, geometría y el primer trozo).
    let t = Instant::now();
    l.abrir_hoja(i, 60);
    let g = l.bytes_geometria(i).expect("geometría");
    let p = l.bytes_trozo(i, 0, por_trozo - 1, 0).expect("trozo");
    println!("primera pantalla: {:.1} ms (geometría {} B, trozo {} B)", ms(t), g.len(), p.len());
    // El resto, por tandas como la app.
    let t = Instant::now();
    let mut tandas = 0;
    while l.cargar(i, 150_000) == Some(false) {
        tandas += 1;
    }
    println!("leer la hoja entera: {:.1} ms ({} tandas)", ms(t), tandas + 1);
    let t = Instant::now();
    let g = l.bytes_geometria(i).expect("geometría");
    println!("geometría: {:.1} ms, {} KB", ms(t), g.len() / 1024);
    std::fs::write(salida.join("geometria.pxhg"), &g).expect("escribir");
    // Todos los trozos, de cabo a rabo (lo que costaría bajar la hoja entera desplazándose).
    let ultima = pixpinoffice::hojas::ultima_fila(&l, i);
    let t = Instant::now();
    let mut conocidos = 0u32;
    let mut bytes = 0usize;
    let mut n = 0u32;
    let mut f0 = 0u32;
    let mut peor = 0f64;
    while f0 <= ultima {
        let tt = Instant::now();
        let b = l.bytes_trozo(i, f0, f0 + por_trozo - 1, conocidos).expect("trozo");
        peor = peor.max(ms(tt));
        conocidos += u32::from_le_bytes([b[26], b[27], b[28], b[29]]);
        if n < guardar {
            std::fs::write(salida.join(format!("trozo-{n}.pxht")), &b).expect("escribir");
        }
        bytes += b.len();
        n += 1;
        f0 += por_trozo;
    }
    println!(
        "{n} trozos de {por_trozo} filas: {:.1} ms ({:.2} ms el trozo, el peor {:.1} ms), {} KB, {} estilos",
        ms(t),
        ms(t) / n.max(1) as f64,
        peor,
        bytes / 1024,
        conocidos
    );
    let t = Instant::now();
    let s = l.copiar(i, pixpin_hojas::Rango { fila0: 0, col0: 0, fila1: ultima.min(9999), col1: 9 }).unwrap_or_default();
    println!("copiar {} filas × 10 columnas: {:.1} ms, {} KB", ultima.min(9999) + 1, ms(t), s.len() / 1024);
}
