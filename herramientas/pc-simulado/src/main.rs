//! El PC de verdad (su almacen y su protocolo en Rust) para probar contra Android.
use std::io::Write;
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use pixpin_proyecto::almacen::{self, Ficha, Indice};
use pixpin_proyecto::cuaderno::{self, Clase, Cuaderno, Mensaje, Sello};
use pixpin_proyecto::vista::DiscoPc;
use pixpin_sincro::galeria::{self, Caducidad, CapturasDelAparato, Entrada, Local};
use pixpin_sincro::disco_android::prueba::{crear_grupo, presentar};
use pixpin_sincro::protocolo::{Hecho, Respondedor, Resultado, Sesion};

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
    // La galeria del PC, con el registro empezado hace un mes: nada de «lo que ya habia no se va».
    let gal = EnCarpeta::de(raiz);
    let _ = std::fs::remove_file(gal.registro());
    gal.caducidad(ahora() - 30 * galeria::DIA_MS);
    // El grupo lo crea el PC; el movil se une.
    crear_grupo(&pc, codigo, ahora());
}

/// **La galeria del PC**, en una carpeta junto a sus datos (`<raiz>-galeria`): como el
/// `EnCarpeta` de las pruebas del PC (`galeria/pruebas.rs`), con 7 «dias hasta borrar». La hora
/// de cada captura es la de modificacion del fichero (como `CapturasDelPc`).
struct EnCarpeta {
    raiz: PathBuf,
}

impl EnCarpeta {
    fn de(raiz: &Path) -> EnCarpeta {
        let mut n = raiz.as_os_str().to_owned();
        n.push("-galeria");
        let g = EnCarpeta { raiz: PathBuf::from(n) };
        std::fs::create_dir_all(g.carpeta()).unwrap();
        std::fs::create_dir_all(g.papelera()).unwrap();
        g
    }
    fn carpeta(&self) -> PathBuf { self.raiz.join("Pictures/PixPin") }
    fn papelera(&self) -> PathBuf { self.raiz.join("papelera") }
    fn registro(&self) -> PathBuf { self.raiz.join("capturas-caducidad.json") }
}

fn listado(d: &Path) -> Vec<String> {
    let mut v: Vec<String> = std::fs::read_dir(d)
        .map(|l| l.flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect())
        .unwrap_or_default();
    v.sort();
    v
}

fn hora_de(r: &Path) -> i64 {
    std::fs::metadata(r).unwrap().modified().unwrap().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64
}

impl CapturasDelAparato for EnCarpeta {
    fn raiz(&self) -> PathBuf { self.raiz.clone() }
    fn listar(&self) -> Option<Vec<Local>> {
        Some(listado(&self.carpeta()).into_iter().map(|n| {
            let r = self.carpeta().join(&n);
            Local { cuando: hora_de(&r), bytes: std::fs::metadata(&r).unwrap().len() as i64, mime: "image/png".into(), nombre: n }
        }).collect())
    }
    fn abrir(&self, nombre: &str) -> Option<PathBuf> {
        Some(self.carpeta().join(nombre)).filter(|r| r.is_file())
    }
    fn guardar(&self, e: &Entrada, escribir: &mut dyn FnMut(&mut dyn Write) -> Resultado<bool>) -> Resultado<bool> {
        let ruta = self.carpeta().join(&e.nombre);
        let mut f = std::fs::File::create(&ruta)?;
        let bien = escribir(&mut f)?;
        if bien { f.set_modified(UNIX_EPOCH + Duration::from_millis(e.cuando as u64))?; }
        drop(f);
        if !bien { let _ = std::fs::remove_file(&ruta); }
        Ok(bien)
    }
    fn tirar(&self, nombres: &[String]) {
        for n in nombres { let _ = std::fs::rename(self.carpeta().join(n), self.papelera().join(n)); }
    }
    fn dias(&self) -> i64 { 7 }
    fn caducidad(&self, ahora: i64) -> Caducidad {
        if let Ok(t) = std::fs::read_to_string(self.registro()) && let Ok(r) = serde_json::from_str(&t) { return r }
        let r = Caducidad { desde: ahora, ..Default::default() };
        std::fs::write(self.registro(), serde_json::to_vec(&r).unwrap()).unwrap();
        r
    }
    fn cambiar_caducidad(&self, ahora: i64, f: &mut dyn FnMut(&mut Caducidad)) -> std::io::Result<()> {
        let mut r = self.caducidad(ahora);
        f(&mut r);
        std::fs::write(self.registro(), serde_json::to_vec(&r)?)
    }
}

/// Lo que hay en la galeria del PC, para que la prueba lo mire con los ojos del PC: sus
/// capturas (con su hora), su papelera, cuando se va cada una segun su registro, y el estado.
fn ver_galeria(raiz: &Path) {
    let g = EnCarpeta::de(raiz);
    let r = g.caducidad(ahora());
    let capturas: Vec<_> = listado(&g.carpeta()).into_iter().map(|n| {
        let cuando = hora_de(&g.carpeta().join(&n));
        serde_json::json!({"nombre": n, "cuando": cuando,
            "seVa": galeria::se_va_el(&r, &n, cuando, g.dias()),
            "texto": String::from_utf8_lossy(&std::fs::read(g.carpeta().join(&n)).unwrap())})
    }).collect();
    let estado = galeria::leer(&g.raiz);
    let entradas: Vec<_> = estado.entradas.iter().map(|e| serde_json::to_value(e).unwrap()).collect();
    println!("{}", serde_json::json!({
        "capturas": capturas, "papelera": listado(&g.papelera()), "entradas": entradas,
        "conservadas": r.conservadas, "fijadas": r.fijadas,
    }));
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
        let r = Respondedor { disco: &pc, estado: &|_| {}, ahora: &ahora, mi_puerto: 0, al_saludar: &|_, _| {}, suelto: None };
        // Con su galeria, como el PC desde c493abb (`atender_con_galeria`).
        let g = EnCarpeta::de(raiz);
        if let Err(e) = r.atender_con_galeria(flujo, nonce(), Some(&g)) { eprintln!("PC: {e:?}"); }
    }
}

fn dirigir(raiz: &Path, puerto: u16) {
    let pc = DiscoPc::nuevo(raiz);
    let flujo = TcpStream::connect(("127.0.0.1", puerto)).unwrap();
    let g = EnCarpeta::de(raiz);
    let mut s = Sesion::conectar(flujo, &pc, false, None, ahora, 0, nonce()).unwrap();
    // La vuelta del PC lleva la galeria (`vuelta::una` llama a `s.galeria` si `tiene_galeria`).
    s.con_galeria(&g);
    let mut hecho = Hecho::default();
    let v = pixpin_sincro::vuelta::una(&mut s, None, &mut hecho, "", &ahora, &mut |_| {}).unwrap();
    s.adios();
    println!("{:?}", v.avisos);
    println!("capturas={} tiradas={}", hecho.capturas, hecho.capturas_tiradas);
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

/// Archivos del PC a lo que el movil tenga abierto, con el cliente de verdad del PC
/// (`al_lienzo::Conexion`): saluda, manda cada fichero y escribe en una linea
/// donde quedo cada uno (`lienzo`, `chat`) o `error: …`.
fn suelto(codigo: &str, puerto: u16, ficheros: &[String]) {
    use pixpin_sincro::al_lienzo::{Conexion, Donde};
    use pixpin_sincro::mensajes::{Aparato, Hola};
    let flujo = TcpStream::connect(("127.0.0.1", puerto)).unwrap();
    let hola = Hola {
        yo: Aparato { id: "id-pc".into(), nombre: "Portátil".into(), letra: None, desde: 0 },
        reloj: ahora(),
        ..Default::default()
    };
    let mut c = match Conexion::abrir(flujo, codigo, nonce(), hola) {
        Ok(c) => c,
        Err(e) => { println!("error: {e:?}"); return }
    };
    for f in ficheros {
        let ruta = Path::new(f);
        let nombre = ruta.file_name().unwrap().to_string_lossy().into_owned();
        match c.mandar(ruta, &nombre) {
            Ok(l) => match (l.donde, l.chat) {
                (Donde::Lienzo, _) => println!("lienzo"),
                (Donde::ChatAbierto, Some(chat)) => println!("chat_abierto {chat}"),
                (Donde::ChatAbierto, None) => println!("chat_abierto"),
                (Donde::Chat, _) => println!("chat"),
            },
            Err(e) => println!("error: {e:?}"),
        }
    }
    c.adios();
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let raiz = Path::new(&a[2]);
    match a[1].as_str() {
        "preparar" => preparar(raiz, &a[3]),
        "responder" => responder(raiz, &a[3]),
        "dirigir" => dirigir(raiz, a[3].parse().unwrap()),
        "ver" => ver(raiz),
        "galeria" => ver_galeria(raiz),
        // Aqui `raiz` es el codigo del grupo: no hace falta disco.
        "suelto" => suelto(&a[2], a[3].parse().unwrap(), &a[4..]),
        _ => panic!("orden desconocida"),
    }
}
