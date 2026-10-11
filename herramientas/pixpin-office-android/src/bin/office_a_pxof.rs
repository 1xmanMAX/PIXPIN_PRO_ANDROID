//! Maqueta un Word o un PowerPoint como lo hará la app y deja cada página en PXOF, con los
//! tiempos. Sirve para medir y para hacer los fixtures de las pruebas de Kotlin.
//!
//! ```text
//! office-a-pxof <documento> <carpeta de salida> [carpeta de las letras de PixPin] [máx. páginas]
//! ```

use std::time::Instant;

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    if a.len() < 2 {
        eprintln!("uso: office-a-pxof <documento> <salida> [letras de PixPin] [máx. páginas]");
        std::process::exit(2);
    }
    let mut conf = String::from("sistema:/system/fonts\n");
    if let Some(p) = a.get(2) {
        conf.push_str(&format!("pixpin:{p}\n"));
    }
    let maximo: usize = a.get(3).and_then(|s| s.parse().ok()).unwrap_or(usize::MAX);
    let salida = std::path::Path::new(&a[1]);
    std::fs::create_dir_all(salida).expect("carpeta de salida");
    let t = Instant::now();
    let mut d = pixpinoffice::DocAndroid::abrir(a[0].as_ref(), pixpinoffice::letras::Carpetas::de_texto(&conf))
        .unwrap_or_else(|e| {
            eprintln!("no se pudo abrir: {e}");
            std::process::exit(1)
        });
    println!("abrir: {:.1} ms ({:?}, total estimado {})", t.elapsed().as_secs_f64() * 1000.0, d.tipo(), d.doc.total());
    let mut n = 0;
    let todo = Instant::now();
    let mut bytes = 0usize;
    while n < maximo {
        let t = Instant::now();
        let Some(p) = d.pagina(n) else { break };
        let ms = t.elapsed().as_secs_f64() * 1000.0;
        if n < 3 || ms > 50.0 {
            println!("página {}: {:.1} ms, {} bytes", n + 1, ms, p.len());
        }
        bytes += p.len();
        std::fs::write(salida.join(format!("pagina-{}.pxof", n + 1)), &p).expect("escribir");
        n += 1;
    }
    println!(
        "{n} páginas en {:.1} ms ({} KB en PXOF); terminado: {}",
        todo.elapsed().as_secs_f64() * 1000.0,
        bytes / 1024,
        d.doc.terminado()
    );
}
