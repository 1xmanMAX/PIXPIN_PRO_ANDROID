//! El PC de verdad (su almacen y su protocolo en Rust) para probar contra Android.
use std::io::Write;
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use pixpin_proyecto::almacen::{self, Ficha, Indice};
use pixpin_proyecto::cuaderno::{self, Clase, Cuaderno, Mensaje, Sello};
use pixpin_proyecto::vista::DiscoPc;
use pixpin_sincro::disco::Disco;
use pixpin_sincro::disco_android::prueba::{crear_grupo, presentar};
use pixpin_sincro::protocolo::{Hecho, Respondedor, Sesion};

fn ahora() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64
}

fn aparato() -> String {
    pixpin_proyecto::codigos::de_aparato("id-pc")
}

fn sello(f: &Ficha, numero: i64) -> Sello {
    std::thread::sleep(std::time::Duration::from_millis(2));
    Sello { cuando: ahora(), numero, aparato: aparato(), proyecto: f.id.clone() }
}

fn poner(raiz: &Path, f: &Ficha, m: &Mensaje) {
    cuaderno::anadir(&almacen::carpeta(raiz, &f.id), m).unwrap();
}

fn preparar(raiz: &Path, codigo: &str) {
    let pc = DiscoPc::nuevo(raiz);
    presentar(&pc, "id-pc", "Portátil");
    let g = almacen::asegurar_guardados(raiz, ahora(), &aparato()).unwrap();
    // Mensajes guardados del PC.
    let hola = Mensaje::nota("Hola desde el PC", &sello(&g, 1));
    poner(raiz, &g, &hola);
    let mut respuesta = Mensaje::nota("Respuesta en el PC", &sello(&g, 2));
    respuesta.responde_a = Some(hola.id.clone());
    poner(raiz, &g, &respuesta);
    let ruta = almacen::guardar_adjunto(raiz, &g.id, "informe (1).pdf", b"%PDF-1.4 del PC").unwrap();
    poner(raiz, &g, &Mensaje::adjunto(Clase::Archivo, "informe (1).pdf", &ruta, 15, &sello(&g, 3)));
    let ruta = almacen::guardar_adjunto(raiz, &g.id, "foto.jpg", b"JPG del PC").unwrap();
    let mut foto = Mensaje::adjunto(Clase::Imagen, "foto.jpg", &ruta, 10, &sello(&g, 4));
    foto.nombre = "Fachada norte.jpg".into();
    poner(raiz, &g, &foto);
    let ruta = almacen::guardar_adjunto(raiz, &g.id, "grabacion.m4a", b"AAC del PC").unwrap();
    let mut voz = Mensaje::adjunto(Clase::Voz, "Clase de estructuras", &ruta, 10, &sello(&g, 5));
    voz.duracion_ms = 4200;
    poner(raiz, &g, &voz);
    poner(raiz, &g, &Mensaje::miniapp(
        "tareas", "Compras",
        "Compras\n- [ ] comprar cemento ➕ 2026-10-03\n- [x] llamar al ingeniero ➕ 2026-10-01\n",
        &sello(&g, 6),
    ));
    // Comentarios de la nota, donde los pone el PC.
    let c = pixpin_proyecto::comentarios_de_notas::de_la_nota(raiz, &g.id, &hola.codigo_unico()).unwrap();
    pixpin_proyecto::comentarios_de_notas::escribir(&c, "{\"v\":1,\"comentarios\":[]}").unwrap();

    // Un proyecto nacido en el PC: nota, foto y un lienzo con imagen.
    let ficha = Ficha::nueva("Obra del PC", ahora(), &aparato());
    let mut i = Indice::leer(raiz);
    i.proyectos.push(ficha.clone());
    i.guardar(raiz).unwrap();
    poner(raiz, &ficha, &Mensaje::nota("medir la cocina", &sello(&ficha, 1)));
    let ruta = almacen::guardar_adjunto(raiz, &ficha.id, "captura.png", b"PNG del PC").unwrap();
    poner(raiz, &ficha, &Mensaje::adjunto(Clase::Imagen, "captura.png", &ruta, 10, &sello(&ficha, 2)));
    let carpeta = almacen::carpeta(raiz, &ficha.id);
    std::fs::create_dir_all(carpeta.join("lienzos")).unwrap();
    std::fs::create_dir_all(carpeta.join("imagenes")).unwrap();
    std::fs::write(carpeta.join("imagenes/img1"), b"foto del lienzo").unwrap();
    std::fs::write(
        carpeta.join("lienzos/d9.excalidraw"),
        r#"{"type":"excalidraw","elements":[{"id":"A","type":"rectangle","x":1,"version":1,"versionNonce":3,"updated":1}],"files":{"img1":{"id":"img1","mimeType":"image/png","path":"imagenes/img1"}}}"#,
    ).unwrap();
    let mut dib = Mensaje::adjunto(Clase::Dibujo, "d9.excalidraw", "lienzos/d9.excalidraw", 100, &sello(&ficha, 3));
    dib.referencia = Some("d9".into());
    poner(raiz, &ficha, &dib);
    // El grupo lo crea el PC; el movil se une.
    crear_grupo(&pc, codigo, ahora());
}

fn nonce() -> [u8; 32] {
    let mut n = [0u8; 32];
    let t = ahora().to_le_bytes();
    for (i, b) in n.iter_mut().enumerate() { *b = t[i % 8] ^ (i as u8).wrapping_mul(31); }
    n
}

fn responder(raiz: &Path, archivo_del_puerto: &str) {
    let pc = DiscoPc::nuevo(raiz);
    let escucha = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    std::fs::write(archivo_del_puerto, escucha.local_addr().unwrap().port().to_string()).unwrap();
    for flujo in escucha.incoming() {
        let Ok(flujo) = flujo else { continue };
        let r = Respondedor { disco: &pc, estado: &|_| {}, ahora: &ahora, mi_puerto: 0, al_saludar: &|_, _| {} };
        if let Err(e) = r.atender(flujo, nonce()) { eprintln!("PC: {e:?}"); }
    }
}

fn dirigir(raiz: &Path, puerto: u16) {
    let pc = DiscoPc::nuevo(raiz);
    let flujo = TcpStream::connect(("127.0.0.1", puerto)).unwrap();
    let mut s = Sesion::conectar(flujo, &pc, false, None, ahora, 0, nonce()).unwrap();
    let mut hecho = Hecho::default();
    let v = pixpin_sincro::vuelta::una(&mut s, None, &mut hecho, "", &ahora, &mut |_| {}).unwrap();
    s.adios();
    println!("{:?}", v.avisos);
}

fn ver(raiz: &Path) {
    let mut fichas = Indice::leer(raiz).proyectos;
    if let Ok(g) = almacen::asegurar_guardados(raiz, 0, &aparato()) { if !fichas.iter().any(|f| f.id == g.id) { fichas.push(g) } }
    let mut chats = Vec::new();
    for f in fichas {
        let ms = Cuaderno::leer_de(&almacen::carpeta(raiz, &f.id)).map(|c| c.mensajes).unwrap_or_default();
        let lista: Vec<_> = ms.iter().map(|m| serde_json::json!({
            "id": m.id, "uid": m.codigo_unico(), "clase": m.clase.as_ref().map(|c| format!("{c:?}")),
            "nombre": m.nombre, "texto": m.texto, "respondeA": m.responde_a, "ruta": m.ruta,
        })).collect();
        chats.push(serde_json::json!({"id": f.id, "nombre": f.nombre, "mensajes": lista}));
    }
    let mut archivos = Vec::new();
    fn recorrer(base: &Path, d: &Path, sal: &mut Vec<String>) {
        if let Ok(rd) = std::fs::read_dir(d) { for e in rd.flatten() {
            let p = e.path();
            if p.is_dir() { recorrer(base, &p, sal) } else { sal.push(p.strip_prefix(base).unwrap().to_string_lossy().into_owned()) }
        } }
    }
    recorrer(raiz, raiz, &mut archivos);
    archivos.sort();
    let mut o = std::io::stdout();
    writeln!(o, "{}", serde_json::json!({"chats": chats, "archivos": archivos})).unwrap();
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let raiz = Path::new(&a[2]);
    match a[1].as_str() {
        "preparar" => preparar(raiz, &a[3]),
        "responder" => responder(raiz, &a[3]),
        "dirigir" => dirigir(raiz, a[3].parse().unwrap()),
        "ver" => ver(raiz),
        _ => panic!("orden desconocida"),
    }
}
