//! El PC de verdad (su almacen y su protocolo en Rust) para probar contra Android.
use std::io::Write;
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use pixpin_proyecto::almacen::{self, Ficha, Indice};
use pixpin_proyecto::carpeta_de_paginas::{self, dentro_de_carpetas, Carpeta};
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
            Local { cuando: hora_de(&r), bytes: std::fs::metadata(&r).unwrap().len() as i64, mime: "image/png".into(), nombre: n, texto: String::new() }
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
        // Como `sincronizar::responder` del PC desde 71e36b67: las fotos con hora, a su grabacion.
        repasar(raiz);
    }
}

// ------------------------------------------------------------ las fotos por su hora (28-oct-2026)

/// `<raiz>/sincro/desfases.json`, como `sincronizar::por_su_hora` del PC.
fn fichero_de_desfases(raiz: &Path) -> PathBuf {
    raiz.join("sincro").join("desfases.json")
}

/// El codigo del PC y los de sus aparatos (los del grupo), como `por_su_hora::yo_y_mios` del PC.
fn yo_y_mios(raiz: &Path) -> (String, std::collections::HashSet<String>) {
    use pixpin_sincro::disco::Disco;
    let yo = aparato();
    let mut mios: std::collections::HashSet<String> = DiscoPc::nuevo(raiz).identidad()
        .map(|i| i.miembros.iter().map(|m| pixpin_proyecto::codigos::de_aparato(&m.id)).collect())
        .unwrap_or_default();
    mios.insert(yo.clone());
    (yo, mios)
}

/// **El repaso del PC** (`por_su_hora::repasar` de la app, sin hilo): lo que haya llegado con hora
/// entra en su grabacion. Escribe en el registro lo que entro.
fn repasar(raiz: &Path) -> Vec<(String, usize)> {
    let (yo, mios) = yo_y_mios(raiz);
    let d = pixpin_proyecto::por_su_hora::Desfases::leer(&fichero_de_desfases(raiz));
    let hechas = pixpin_proyecto::por_su_hora::repasar_todos(raiz, &yo, &mios, &d);
    if !hechas.is_empty() { eprintln!("PC: por su hora {hechas:?}"); }
    hechas
}

/// Apunta lo que va adelantado el reloj del aparato `id` (como `por_su_hora::medido` del PC).
fn medido(raiz: &Path, id: &str, desfase: i64) {
    let f = fichero_de_desfases(raiz);
    let mut d = pixpin_proyecto::por_su_hora::Desfases::leer(&f);
    d.medir(&pixpin_proyecto::codigos::de_aparato(id), ahora(), desfase);
    d.guardar(&f).unwrap();
}

/// **Una clase grabada en el PC con su reloj de pared** (`resto.reloj`, aparato = el PC): la nota de
/// `duracion` ms y su carpeta, en el proyecto `nombre`. Graba de `inicio` a `fin` (ms desde 1970 en
/// el reloj del PC) con una pausa `[pausa_desde, pausa_hasta]` (0 0: sin pausa). Con `todos`, abierta a
/// los colaboradores (`["*"]`). Escribe `{proyecto, voz, carpeta}`.
fn clase_con_reloj(raiz: &Path, a: &[String]) {
    let n = |i: usize| a[i].parse::<i64>().unwrap();
    let f = proyecto_llamado(raiz, &a[0]);
    let (inicio, fin, pd, ph, duracion) = (n(1), n(2), n(3), n(4), n(5));
    let ruta = almacen::guardar_adjunto(raiz, &f.id, "Clase.m4a", b"m4a").unwrap();
    let mut voz = Mensaje::adjunto(Clase::Voz, "Clase.m4a", &ruta, 3, &sello(&f, 1));
    voz.duracion_ms = duracion;
    voz.resto.insert("grabacion".into(), serde_json::json!("clase"));
    voz.resto.insert("capturas".into(), serde_json::json!([]));
    let reloj = pixpin_proyecto::por_su_hora::RelojDeGrabacion {
        aparato: aparato(), inicio, fin,
        pausas: if pd > 0 { vec![[pd, ph]] } else { Vec::new() },
        ..Default::default()
    };
    reloj.poner(&mut voz.resto);
    if a.get(6).is_some_and(|t| t == "todos") {
        pixpin_proyecto::por_su_hora::invitar(&mut voz.resto, pixpin_proyecto::por_su_hora::TODOS, true);
    }
    poner(raiz, &f, &voz);
    let mut c = Carpeta::grabacion(&voz.id, duracion);
    c.anadir_al_minuto(&voz.id, "", 0);
    let car = carpeta_de_paginas::mensaje_de_grabacion("Clase con reloj", &c, &sello(&f, 2));
    poner(raiz, &f, &car);
    println!("{}", serde_json::json!({"proyecto": f.id, "voz": voz.id, "carpeta": car.id}));
}

/// **Una foto del PC con su hora** (`tomadaEn`, en el reloj del PC; `aparato` el del PC salvo que
/// se diga otro) en el proyecto `nombre`. Escribe `{id, uid}`.
fn foto_con_hora(raiz: &Path, nombre: &str, tomada_en: i64, de: Option<&String>) {
    let f = proyecto_llamado(raiz, nombre);
    let fichero = format!("foto-{tomada_en}.png");
    let ruta = almacen::guardar_adjunto(raiz, &f.id, &fichero, b"\x89PNG foto del PC").unwrap();
    let mut s = sello(&f, 9);
    if let Some(a) = de { s.aparato = a.clone(); }
    let mut m = Mensaje::adjunto(Clase::Imagen, &fichero, &ruta, 18, &s);
    m.resto.insert("tomadaEn".into(), serde_json::json!(tomada_en));
    poner(raiz, &f, &m);
    println!("{}", serde_json::json!({"id": m.id, "uid": m.codigo_unico()}));
}

fn dirigir(raiz: &Path, puerto: u16) {
    let pc = DiscoPc::nuevo(raiz);
    let flujo = TcpStream::connect(("127.0.0.1", puerto)).unwrap();
    let g = EnCarpeta::de(raiz);
    let mut s = Sesion::conectar(flujo, &pc, false, None, ahora, 0, nonce()).unwrap();
    // La vuelta del PC lleva la galeria (`vuelta::una` llama a `s.galeria` si `tiene_galeria`).
    s.con_galeria(&g);
    let mut hecho = Hecho::default();
    // Lo que va adelantado su reloj, para poner sus fotos por su hora (`hacer_una_vuelta` del PC).
    medido(raiz, &s.otro.id, s.desfase);
    let v = pixpin_sincro::vuelta::una(&mut s, None, &mut hecho, "", &ahora, &mut |_| {}).unwrap();
    s.adios();
    repasar(raiz);
    println!("{:?}", v.avisos);
    println!("capturas={} tiradas={}", hecho.capturas, hecho.capturas_tiradas);
}

fn ver(raiz: &Path) {
    let mut fichas = Indice::leer(raiz).proyectos;
    if let Ok(g) = almacen::asegurar_guardados(raiz, 0, &aparato()) { if !fichas.iter().any(|f| f.id == g.id) { fichas.push(g) } }
    let mut chats = Vec::new();
    for f in fichas {
        let ms = Cuaderno::leer_de(&almacen::carpeta(raiz, &f.id)).map(|c| c.mensajes).unwrap_or_default();
        let lista: Vec<_> = ms.iter().map(|m| {
            // El fichero que nombra, como lo abre el PC (`vista::ruta_real`): su largo y su huella.
            let fichero = m.ruta.as_deref()
                .and_then(|r| pixpin_proyecto::vista::ruta_real(raiz, &f.id, r))
                .and_then(|r| std::fs::read(r).ok());
            // La carpeta, leida con el PC (`carpeta_de_paginas`).
            let carpeta = Carpeta::de_mensaje(m).map(|c| serde_json::json!({
                "grabacion": c.es_grabacion(), "voz": c.voz, "video": c.video, "duracionMs": c.duracion_ms,
                "paginas": c.paginas.iter().map(|p| p.id.clone()).collect::<Vec<_>>(),
                "minutos": c.paginas.iter().map(|p| p.ms).collect::<Vec<_>>(),
                "capturas": c.capturas().map(|p| p.id.clone()).collect::<Vec<_>>(),
                "adjuntos": c.adjuntos().map(|p| p.id.clone()).collect::<Vec<_>>(),
            }));
            serde_json::json!({
                "id": m.id, "uid": m.codigo_unico(), "clase": m.clase.as_ref().map(|c| format!("{c:?}")),
                "nombre": m.nombre, "texto": m.texto, "respondeA": m.responde_a, "ruta": m.ruta,
                "miniapp": m.miniapp, "resto": m.resto,
                "largo": fichero.as_ref().map(|b| b.len()), "huella": fichero.as_ref().map(|b| huella(b)),
                "carpeta": carpeta,
            })
        }).collect();
        let dentro: Vec<String> = dentro_de_carpetas(&ms).into_iter().collect();
        // La foto del chat (25-oct-2026), tal cual la guarda el PC en su ficha, y si su imagen esta.
        let foto = pixpin_proyecto::avatar::valor_de(&f).cloned();
        let imagen = pixpin_proyecto::avatar::de_ficha(&f)
            .and_then(|a| pixpin_proyecto::avatar::ruta_de_la_imagen(raiz, &f.id, &a))
            .and_then(|r| std::fs::read(r).ok())
            .map(|b| String::from_utf8_lossy(&b).into_owned());
        chats.push(serde_json::json!({"id": f.id, "nombre": f.nombre, "mensajes": lista, "dentro": dentro, "avatar": foto, "imagenDelAvatar": imagen}));
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

/// **Pone la foto del chat** de un proyecto del PC (por su nombre), como la hoja del PC:
/// `imagen <bytes>` la guarda como `avatar-<ms>.png` en su carpeta; `emoji <e>` con un fondo de
/// la paleta; `iniciales`, lo de siempre.
fn foto(raiz: &Path, nombre: &str, tipo: &str, valor: Option<&String>) {
    use pixpin_proyecto::avatar::{self, Avatar};
    let f = Indice::leer(raiz).proyectos.into_iter().find(|f| f.nombre == nombre).expect("no hay ese proyecto");
    let cuando = ahora();
    let a = match tipo {
        "imagen" => {
            let rel = avatar::guardar_imagen(raiz, &f.id, valor.unwrap().as_bytes(), cuando).unwrap();
            Avatar::imagen(&rel, cuando)
        }
        "emoji" => Avatar::emoji(valor.unwrap(), Some(("#FF885E", "#FF516A")), cuando),
        _ => Avatar::iniciales(None, cuando),
    };
    avatar::poner(raiz, &f.id, &a).unwrap();
}

/// **La huella de un fichero** (FNV-1a de 64 bits, en hexadecimal): la prueba la calcula igual en
/// Kotlin para ver que un video llega byte a byte sin pasar sus megas por la salida.
fn huella(b: &[u8]) -> String {
    let mut h: u64 = 0xcbf29ce484222325;
    for x in b { h ^= *x as u64; h = h.wrapping_mul(0x100000001b3); }
    format!("{h:016x}")
}

/// El proyecto del PC que se llama [nombre]; si no lo hay, se crea.
fn proyecto_llamado(raiz: &Path, nombre: &str) -> Ficha {
    let mut i = Indice::leer(raiz);
    if let Some(f) = i.proyectos.iter().find(|f| f.nombre == nombre) { return f.clone() }
    let ficha = Ficha::nueva(nombre, ahora(), &aparato());
    i.proyectos.push(ficha.clone());
    i.guardar(raiz).unwrap();
    ficha
}

/// **Una clase grabada en el PC**, como `grabar_clase::guardar` (25-oct-2026): la nota de voz con
/// `grabacion`/`capturas`/`video`, el `.mp4` (de [megas] MiB y pico: varios tramos del cable) con
/// `enGrabacion`, dos capturas y, detras, la carpeta `tipo: "grabacion"` con todo dentro. Y una
/// carpeta de paginas con una nota y una foto. Escribe en una linea los `id`.
fn clase(raiz: &Path, nombre: &str, megas: usize) {
    let f = proyecto_llamado(raiz, nombre);
    let carpeta = almacen::carpeta(raiz, &f.id);
    std::fs::create_dir_all(carpeta.join("archivos")).unwrap();
    let audio = vec![7u8; 300_000];
    let ruta_voz = almacen::guardar_adjunto(raiz, &f.id, "Clase 25-oct.m4a", &audio).unwrap();
    let mut voz = Mensaje::adjunto(Clase::Voz, "Clase 25-oct.m4a", &ruta_voz, audio.len() as i64, &sello(&f, 1));
    voz.duracion_ms = 90_000;
    let video: Vec<u8> = (0..(megas << 20) + 123).map(|i| (i % 251) as u8).collect();
    // «Clase (1).mp4»: el nombre con parentesis del segundo video del mismo nombre.
    std::fs::write(carpeta.join("archivos/Clase 25-oct (1).mp4"), &video).unwrap();
    let mut mp4 = Mensaje::adjunto(Clase::Archivo, "Clase 25-oct (1).mp4", "archivos/Clase 25-oct (1).mp4", video.len() as i64, &sello(&f, 2));
    mp4.resto.insert("enGrabacion".into(), serde_json::json!({"de": voz.id, "ms": 0}));
    let mut capturas = Vec::new();
    for (n, ms) in [(1, 65_000i64), (2, 80_000)] {
        let fichero = format!("clase-{}-{n}.png", ahora());
        let ruta = almacen::guardar_adjunto(raiz, &f.id, &fichero, format!("PNG captura {n}").as_bytes()).unwrap();
        let mut foto = Mensaje::adjunto(Clase::Imagen, &fichero, &ruta, 13, &sello(&f, 2 + n));
        foto.texto = format!("⏱ 0{}:{:02} · Clase 25-oct", ms / 60_000, ms / 1000 % 60);
        foto.resto.insert("enGrabacion".into(), serde_json::json!({"de": voz.id, "ms": ms}));
        capturas.push((ms, foto));
    }
    voz.resto.insert("grabacion".into(), serde_json::json!("clase"));
    voz.resto.insert("capturas".into(), serde_json::json!(capturas.iter().map(|(ms, m)| serde_json::json!({"ms": ms, "id": m.id})).collect::<Vec<_>>()));
    voz.resto.insert("video".into(), serde_json::json!({"id": mp4.id}));
    poner(raiz, &f, &voz);
    poner(raiz, &f, &mp4);
    for (_, m) in &capturas { poner(raiz, &f, m); }
    let mut c = Carpeta::grabacion(&voz.id, 90_000);
    c.video = mp4.id.clone();
    c.anadir_al_minuto(&voz.id, "", 0);
    c.anadir_al_minuto(&mp4.id, "", 0);
    for (ms, m) in &capturas { c.anadir_al_minuto(&m.id, &format!("⏱ {}:{:02}", ms / 60_000, ms / 1000 % 60), *ms); }
    let car = carpeta_de_paginas::mensaje_de_grabacion("Clase 25-10 10h58", &c, &sello(&f, 5));
    poner(raiz, &f, &car);
    // Una carpeta de paginas: una nota y una foto dentro.
    let nota = Mensaje::nota("Dia 1: replanteo", &sello(&f, 6));
    poner(raiz, &f, &nota);
    let ruta = almacen::guardar_adjunto(raiz, &f.id, "pizarra.jpg", b"JPG de la pizarra").unwrap();
    let foto = Mensaje::adjunto(Clase::Imagen, "pizarra.jpg", &ruta, 17, &sello(&f, 7));
    poner(raiz, &f, &foto);
    let mut d = Carpeta::default();
    d.anadir(&nota.id, "Dia 1");
    d.anadir(&foto.id, "Dia 2");
    let mut diario = carpeta_de_paginas::mensaje_nuevo("Diario de obra", &sello(&f, 8));
    diario.texto = d.escribir();
    poner(raiz, &f, &diario);
    println!("{}", serde_json::json!({
        "proyecto": f.id, "voz": voz.id, "video": mp4.id, "huellaDelVideo": huella(&video), "largoDelVideo": video.len(),
        "capturas": capturas.iter().map(|(_, m)| m.id.clone()).collect::<Vec<_>>(),
        "carpeta": car.id, "diario": diario.id, "nota": nota.id, "foto": foto.id,
    }));
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
        "foto" => foto(raiz, &a[3], &a[4], a.get(5)),
        "clase" => clase(raiz, &a[3], a.get(4).map(|m| m.parse().unwrap()).unwrap_or(5)),
        // Aqui `raiz` es el codigo del grupo: no hace falta disco.
        "suelto" => suelto(&a[2], a[3].parse().unwrap(), &a[4..]),
        "camara" => camara::escuchar(raiz, &a[3]),
        // Las fotos por su hora (28-oct-2026).
        "clase-reloj" => clase_con_reloj(raiz, &a[3..]),
        "foto-hora" => foto_con_hora(raiz, &a[3], a[4].parse().unwrap(), a.get(5)),
        "repasar" => println!("{}", serde_json::json!(repasar(raiz))),
        _ => panic!("orden desconocida"),
    }
}

/// **El PC grabando una reunion con la camara del movil** (28-oct-2026, `docs/movil-como-camara.md`
/// del PC): su `Respondedor` de verdad con `Camaras` y `Recepcion` de `pixpin-proyecto` y diciendo
/// `camara1` (`CAPACIDADES_CON_CAMARA`), como `crates/pixpin-proyecto/tests/movil_como_camara.rs`.
/// El reloj del PC lo mueve la prueba (`hora <ms>`), y lo demas que hace la barra de «Grabar clase»
/// llega por la entrada, una orden por linea, cada una contestada con una linea:
///
/// - `hora <ms>`: el reloj del PC.
/// - `empezar <id> <titulo…>`, `seguir <ms> <pausa 0|1>` (lo que dice la grabadora), `apagar`,
///   `encender`, `codecs h264,hevc`.
/// - `anuncio`: lo que se le contesta al movil (JSON). `nuevas`: lo llegado desde la ultima vez.
/// - `terminar <duracion>`: «Terminar»; la clase se guarda (la nota y su carpeta) y entra lo de la
///   cola. Escribe `{proyecto, voz, carpeta, hechas}`.
/// - `avisar <puerto>`: el PC avisa al movil que escucha ahi (`anunciar_camara`) y mide su reloj.
mod camara {
    use std::io::BufRead;
    use std::net::{TcpListener, TcpStream};
    use std::path::Path;
    use std::sync::atomic::{AtomicI64, Ordering};
    use std::sync::{Arc, Mutex};

    use pixpin_proyecto::almacen::{self, Ficha, Indice};
    use pixpin_proyecto::camara_del_movil::{Camaras, Destino, Recepcion};
    use pixpin_proyecto::carpeta_de_paginas::{self, Carpeta};
    use pixpin_proyecto::cuaderno::{self, Clase, Mensaje, Sello};
    use pixpin_proyecto::vista::DiscoPc;
    use pixpin_sincro::al_lienzo::{Conexion, Recibe};
    use pixpin_sincro::mensajes as m;
    use pixpin_sincro::protocolo::Respondedor;

    static RELOJ: AtomicI64 = AtomicI64::new(0);
    static HORA: AtomicI64 = AtomicI64::new(0);

    fn ahora_pc() -> i64 { RELOJ.load(Ordering::SeqCst) }

    /// La hora de un mensaje nuevo, sin repetir (el id de un mensaje es su hora).
    fn hora_sin_repetir() -> i64 { HORA.fetch_add(1, Ordering::SeqCst) }

    pub fn escuchar(raiz: &Path, archivo_del_puerto: &str) {
        RELOJ.store(super::ahora(), Ordering::SeqCst);
        HORA.store(super::ahora() + 10_000_000, Ordering::SeqCst);
        let camaras = Arc::new(Mutex::new(Camaras::nuevas(raiz.join("sincro").join("camara-movil"), "Portátil")));
        // Como el PC desde da9eab86: su codigo y su almacen, para lo que llega por su hora (§12.4).
        {
            let mut c = camaras.lock().unwrap();
            c.codigo = super::aparato();
            c.almacen = Some((raiz.to_path_buf(), hora_sin_repetir));
        }
        let escucha = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let puerto = escucha.local_addr().unwrap().port();
        {
            let raiz = raiz.to_path_buf();
            let camaras = camaras.clone();
            std::thread::spawn(move || {
                for flujo in escucha.incoming() {
                    let Ok(flujo) = flujo else { continue };
                    let raiz = raiz.clone();
                    let camaras = camaras.clone();
                    std::thread::spawn(move || {
                        let disco = DiscoPc::nuevo(&raiz);
                        let fabrica = |otro: &m::Aparato| -> Box<dyn Recibe> {
                            Box::new(Recepcion { camaras: camaras.clone(), de_id: otro.id.clone(), de_nombre: otro.nombre.clone(), ahora: ahora_pc })
                        };
                        let r = Respondedor { disco: &disco, estado: &|_| {}, ahora: &ahora_pc, mi_puerto: 0, al_saludar: &|_, _| {}, suelto: Some(&fabrica) };
                        if let Err(e) = r.atender_con(flujo, super::nonce(), None, &pixpin_sincro::CAPACIDADES_CON_CAMARA) {
                            eprintln!("PC: {e:?}");
                        }
                    });
                }
            });
        }
        std::fs::write(archivo_del_puerto, puerto.to_string()).unwrap();
        let con = |f: &mut dyn FnMut(&mut Camaras) -> String| -> String {
            let mut c = camaras.lock().unwrap_or_else(|e| e.into_inner());
            f(&mut c)
        };
        for linea in std::io::stdin().lock().lines() {
            let Ok(linea) = linea else { break };
            let partes: Vec<&str> = linea.split_whitespace().collect();
            let Some(orden) = partes.first() else { continue };
            let salida = match *orden {
                "hora" => { RELOJ.store(partes[1].parse().unwrap(), Ordering::SeqCst); "ok".to_string() }
                "empezar" => {
                    let titulo = partes[2..].join(" ");
                    con(&mut |c| { c.empezar(partes[1], &titulo, ahora_pc(), 0); "ok".into() })
                }
                "seguir" => con(&mut |c| { c.seguir_a(ahora_pc(), partes[1].parse().unwrap(), partes[2] == "1"); "ok".into() }),
                "apagar" => con(&mut |c| { c.encendida = false; "ok".into() }),
                "encender" => con(&mut |c| { c.encendida = true; "ok".into() }),
                "codecs" => con(&mut |c| { c.codecs = partes.get(1).map(|l| l.split(',').map(String::from).collect()).unwrap_or_default(); "ok".into() }),
                "anuncio" => con(&mut |c| serde_json::to_string(&c.anuncio(ahora_pc())).unwrap()),
                "nuevas" => con(&mut |c| {
                    let n: Vec<_> = c.tomar_nuevas().iter().map(|n| serde_json::json!({"ms": n.ms, "clip": n.clip, "de": n.de})).collect();
                    serde_json::json!(n).to_string()
                }),
                "terminar" => {
                    let duracion: i64 = partes[1].parse().unwrap();
                    con(&mut |c| {
                        c.acabar(ahora_pc());
                        let (proyecto, voz, carpeta) = guardar_la_clase(raiz, duracion);
                        let destino = Destino {
                            raiz: raiz.to_path_buf(), proyecto: proyecto.clone(), voz: Some(voz.clone()),
                            carpeta: Some(carpeta.clone()), aparato: super::aparato(), hora: hora_sin_repetir,
                        };
                        let hechas = c.guardada(destino, Some(duracion), ahora_pc());
                        serde_json::json!({"proyecto": proyecto, "voz": voz, "carpeta": carpeta, "hechas": hechas}).to_string()
                    })
                }
                "avisar" => {
                    let anuncio = con(&mut |c| serde_json::to_string(&c.anuncio(ahora_pc())).unwrap());
                    let anuncio: Option<pixpin_sincro::camara::Anuncio> = serde_json::from_str(&anuncio).unwrap();
                    match avisar(raiz, partes[1].parse().unwrap(), anuncio) {
                        Ok((id, sabe, desfase)) => {
                            if let Some(d) = desfase { con(&mut |c| { c.medir(&id, d); String::new() }); }
                            serde_json::json!({"sabe": sabe, "desfase": desfase}).to_string()
                        }
                        Err(e) => serde_json::json!({"error": e}).to_string(),
                    }
                }
                // La grabacion del movil que el PC conoce (§13): `{grabacion, de, desfase, ms}` o null.
                "ajena" => con(&mut |c| match c.ajena(ahora_pc()) {
                    Some(a) => serde_json::json!({"grabacion": a.anuncio.grabacion, "de": a.de_id, "desfase": a.desfase,
                        "ms": a.reloj.ms_en(ahora_pc()), "titulo": a.anuncio.titulo}).to_string(),
                    None => "null".into(),
                }),
                // «Captura para la grabacion del movil»: el PC le manda `fichero` al movil que escucha
                // en `puerto`, con el minuto de su grabacion (`aporte::enviar_nueva` del PC).
                "al-movil" => {
                    let ajena = con(&mut |c| c.ajena(ahora_pc()).map(|a| serde_json::to_string(&(a.anuncio.grabacion.clone(), a.desfase, a.reloj.ms_en(ahora_pc()))).unwrap()).unwrap_or_default());
                    if ajena.is_empty() { serde_json::json!({"error": "no hay grabacion del movil"}).to_string() } else {
                        let (grabacion, desfase, ms): (String, i64, i64) = serde_json::from_str(&ajena).unwrap();
                        let foto = pixpin_sincro::camara::FotoDeCamara {
                            grabacion, ms: Some(ms), tomada_en: ahora_pc(), reloj: -desfase,
                            clave: format!("pc-{}-{}", super::aparato(), ahora_pc()), ..Default::default()
                        };
                        match al_movil(raiz, partes[1].parse().unwrap(), Path::new(partes[2]), &foto) {
                            Ok(ms) => serde_json::json!({"ms": ms, "enviado": foto.ms, "clave": foto.clave}).to_string(),
                            Err(e) => serde_json::json!({"error": e}).to_string(),
                        }
                    }
                }
                // Una foto sin grabacion (§12.4) del PC al movil: solo con su hora (`tomada_en <ms>`).
                "por-su-hora-al-movil" => {
                    let foto = pixpin_sincro::camara::FotoDeCamara {
                        tomada_en: partes[3].parse().unwrap(), clave: format!("pc-hora-{}", ahora_pc()), ..Default::default()
                    };
                    match al_movil(raiz, partes[1].parse().unwrap(), Path::new(partes[2]), &foto) {
                        Ok(ms) => serde_json::json!({"ms": ms}).to_string(),
                        Err(e) => serde_json::json!({"error": e}).to_string(),
                    }
                }
                "fin" => break,
                _ => format!("error: no sé qué es «{orden}»"),
            };
            println!("{salida}");
            use std::io::Write;
            let _ = std::io::stdout().flush();
        }
    }

    /// **El PC avisa al movil** como `sincronizar::camara::avisar_a` del PC: el `hola` con
    /// `camara1` y, si el otro tambien lo dice, el anuncio. Devuelve el id del otro, si sabe de la
    /// camara y lo que va adelantado su reloj.
    fn avisar(raiz: &Path, puerto: u16, anuncio: Option<pixpin_sincro::camara::Anuncio>) -> Result<(String, bool, Option<i64>), String> {
        use pixpin_sincro::disco::Disco;
        let id = DiscoPc::nuevo(raiz).identidad().map_err(|e| e.to_string())?;
        let hola = m::Hola {
            yo: id.yo.clone(),
            miembros: id.miembros.clone(),
            reloj: ahora_pc(),
            capacidades: pixpin_sincro::CAPACIDADES_CON_CAMARA.iter().map(|c| c.to_string()).collect(),
            ..Default::default()
        };
        let flujo = TcpStream::connect(("127.0.0.1", puerto)).map_err(|e| e.to_string())?;
        let mut c = Conexion::abrir(flujo, id.codigo.as_deref().unwrap_or_default(), super::nonce(), hola).map_err(|e| e.to_string())?;
        let otro = c.otro.id.clone();
        if !c.sabe_de_camara() {
            c.adios();
            return Ok((otro, false, None));
        }
        let anuncio = anuncio.unwrap_or_else(pixpin_sincro::camara::Anuncio::nada);
        let d = c.anunciar_camara(&anuncio, &ahora_pc).map_err(|e| e.to_string())?;
        c.adios();
        Ok((otro, true, Some(d)))
    }

    /// **El PC manda una captura a la grabacion del movil** (`aporte::mandar` del PC): saluda con
    /// `camara1` y la manda por el `suelto` de la camara. El minuto donde quedo, o el error.
    fn al_movil(raiz: &Path, puerto: u16, fichero: &Path, foto: &pixpin_sincro::camara::FotoDeCamara) -> Result<i64, String> {
        use pixpin_sincro::disco::Disco;
        let id = DiscoPc::nuevo(raiz).identidad().map_err(|e| e.to_string())?;
        let hola = m::Hola {
            yo: id.yo.clone(),
            miembros: id.miembros.clone(),
            reloj: ahora_pc(),
            capacidades: pixpin_sincro::CAPACIDADES_CON_CAMARA.iter().map(|c| c.to_string()).collect(),
            ..Default::default()
        };
        let flujo = TcpStream::connect(("127.0.0.1", puerto)).map_err(|e| e.to_string())?;
        let mut c = Conexion::abrir(flujo, id.codigo.as_deref().unwrap_or_default(), super::nonce(), hola).map_err(|e| e.to_string())?;
        if !c.sabe_de_camara() { c.adios(); return Err("el movil no sabe de la camara".into()) }
        let nombre = fichero.file_name().unwrap().to_string_lossy().into_owned();
        let r = c.mandar_foto(fichero, &nombre, foto).map_err(|e| e.to_string());
        c.adios();
        r
    }

    /// Lo que hace `grabar_clase::guardar` al «Terminar»: la nota de voz y su carpeta (como
    /// `guardar_la_clase` de `movil_como_camara.rs`).
    fn guardar_la_clase(raiz: &Path, duracion: i64) -> (String, String, String) {
        let f = Ficha::nueva("Reunion", ahora_pc(), &super::aparato());
        let mut i = Indice::leer(raiz);
        i.proyectos.push(f.clone());
        i.guardar(raiz).unwrap();
        let carpeta = almacen::carpeta(raiz, &f.id);
        let sello = |n| Sello { cuando: hora_sin_repetir(), numero: n, aparato: super::aparato(), proyecto: f.id.clone() };
        let ruta = almacen::guardar_adjunto(raiz, &f.id, "Clase.m4a", b"m4a").unwrap();
        let mut voz = Mensaje::adjunto(Clase::Voz, "Clase.m4a", &ruta, 3, &sello(1));
        voz.duracion_ms = duracion;
        voz.resto.insert("grabacion".into(), serde_json::json!("clase"));
        cuaderno::anadir(&carpeta, &voz).unwrap();
        let mut c = Carpeta::grabacion(&voz.id, duracion);
        c.anadir_al_minuto(&voz.id, "", 0);
        let m = carpeta_de_paginas::mensaje_de_grabacion("Clase", &c, &sello(2));
        cuaderno::anadir(&carpeta, &m).unwrap();
        (f.id, voz.id, m.id)
    }
}
