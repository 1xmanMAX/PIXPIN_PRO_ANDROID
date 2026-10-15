use std::io::BufRead;
use serde_json::{json, Value};

fn main() {
    let cmd = std::env::args().nth(1).unwrap_or_default();
    match cmd.as_str() {
        "catalogo" => {
            let c = pixpin_ia::herramientas::catalogo();
            let l: Vec<Value> = c.iter().map(|h| h.a_json()).collect();
            println!("{}", serde_json::to_string_pretty(&l).unwrap());
        }
        "topes" => {
            let n: u64 = std::env::args().nth(2).unwrap().parse().unwrap();
            let c = pixpin_ia::agente::con_topes(&pixpin_ia::herramientas::catalogo(), n);
            let l: Vec<Value> = c.iter().map(|h| h.a_json()).collect();
            println!("{}", serde_json::to_string_pretty(&l).unwrap());
        }
        "seleccion" => {
            for linea in std::io::stdin().lock().lines() {
                let v: Value = serde_json::from_str(&linea.unwrap()).unwrap();
                let p = v["pedido"].as_str().unwrap();
                let img = v["imagen"].as_bool().unwrap_or(false);
                let n: Vec<String> = pixpin_ia::seleccion::para_el_pedido(p, img).into_iter().map(|h| h.nombre).collect();
                let vista = pixpin_ia::seleccion::pide_mejor_vista(p, img);
                println!("{}", json!({"pedido": p, "imagen": img, "herramientas": n, "mejor_vista": vista}));
            }
        }
        "bm25" => {
            use pixpin_ia::documentos::{Abrir, Alcance, Firma, Fuente, Indice, Lector, Tipo, Unidad};
            let mut texto = String::new();
            std::io::Read::read_to_string(&mut std::io::stdin(), &mut texto).unwrap();
            let v: Value = serde_json::from_str(&texto).unwrap();
            struct L(Value);
            impl Lector for L {
                fn leer(&self, f: &Fuente) -> Result<Vec<Unidad>, String> {
                    let x = self.0["fuentes"].as_array().unwrap().iter().find(|x| x["clave"] == f.clave.as_str()).unwrap();
                    if let Some(e) = x["error"].as_str() { return Err(e.into()); }
                    Ok(x["unidades"].as_array().unwrap().iter().map(|u| Unidad {
                        texto: u["texto"].as_str().unwrap().into(),
                        pagina: u["pagina"].as_u64().map(|p| p as u32),
                        seccion: u["seccion"].as_str().map(str::to_string),
                        abrir: u["codigo"].as_str().map(|c| Abrir::Mensaje { proyecto: Some(x["proyecto"].as_str().unwrap().into()), codigo: c.into() }),
                        cuando: u["cuando"].as_i64(),
                    }).collect())
                }
            }
            let fuentes: Vec<Fuente> = v["fuentes"].as_array().unwrap().iter().map(|x| Fuente {
                clave: x["clave"].as_str().unwrap().into(),
                proyecto: x["proyecto"].as_str().unwrap().into(),
                proyecto_nombre: x["proyectoNombre"].as_str().unwrap().into(),
                nombre: x["nombre"].as_str().unwrap().into(),
                tipo: Tipo::de_clave(x["tipo"].as_str().unwrap()).unwrap(),
                ruta: x["ruta"].as_str().unwrap().into(),
                firma: Firma { bytes: 1, modificado: 1 },
                abrir: Abrir::Fichero { ruta: x["ruta"].as_str().unwrap().into(), pagina: None },
            }).collect();
            let mut ix = Indice::nuevo(None);
            ix.poner_al_dia(&fuentes, &L(v.clone()));
            let mut out = Vec::new();
            for c in v["consultas"].as_array().unwrap() {
                let alcance = match c["solo"].as_str() { Some(p) => Alcance::solo(p), None => Alcance::Todo };
                let h = ix.buscar(c["consulta"].as_str().unwrap(), &alcance, 5);
                let mut citas = pixpin_ia::documentos::Citas::default();
                let para_el_modelo = citas.anadir(c["consulta"].as_str().unwrap(), h.clone(), None);
                out.push(json!({"consulta": c["consulta"], "palabras": pixpin_ia::documentos::texto::de_la_consulta(c["consulta"].as_str().unwrap()),
                    "hallazgos": h.iter().map(|x| json!({"nombre": x.nombre, "pagina": x.pagina, "seccion": x.seccion, "fragmento": x.fragmento, "puntos": x.puntos, "pedido": x.abrir.pedido()})).collect::<Vec<_>>(),
                    "modelo": para_el_modelo,
                    "citas": citas.lista.iter().map(pixpin_ia::documentos::herramienta::cita_a_json).collect::<Vec<_>>()}));
            }
            println!("{}", serde_json::to_string_pretty(&out).unwrap());
        }
        "tabla" => {
            let mut texto = String::new();
            std::io::Read::read_to_string(&mut std::io::stdin(), &mut texto).unwrap();
            let v: Value = serde_json::from_str(&texto).unwrap();
            let mut out = Vec::new();
            for caso in v.as_array().unwrap() {
                let ps: Vec<pixpin_ia::tabla_escaneada::Palabra> = caso.as_array().unwrap().iter().map(|p| pixpin_ia::tabla_escaneada::Palabra::nueva(
                    p[0].as_str().unwrap(), p[1].as_f64().unwrap(), p[2].as_f64().unwrap(), p[3].as_f64().unwrap(), p[4].as_f64().unwrap())).collect();
                let t = pixpin_ia::tabla_escaneada::reconstruir(&ps);
                out.push(json!({"filas": t.filas, "combinadas": t.combinadas.iter().map(|c| json!([c.fila, c.columna, c.columnas])).collect::<Vec<_>>(), "cabecera": t.parece_cabecera(),
                    "numeros": t.filas.iter().flatten().map(|c| pixpin_ia::tabla_escaneada::como_numero(c)).collect::<Vec<_>>()}));
            }
            println!("{}", serde_json::to_string(&out).unwrap());
        }
        "calcar" => {
            use pixpin_ia::calcar::{calcar, Detalle, Parametros, Trazo};
            let mut texto = String::new();
            std::io::Read::read_to_string(&mut std::io::stdin(), &mut texto).unwrap();
            let v: Value = serde_json::from_str(&texto).unwrap();
            let mut out = Vec::new();
            let pts = |v: &[(f64, f64)]| v.iter().map(|p| json!([p.0, p.1])).collect::<Vec<_>>();
            for c in v.as_array().unwrap() {
                let rgba = std::fs::read(c["archivo"].as_str().unwrap()).unwrap();
                let p = Parametros {
                    detalle: match c["detalle"].as_str().unwrap_or("medio") { "bajo" => Detalle::Bajo, "alto" => Detalle::Alto, _ => Detalle::Medio },
                    colores: c["colores"].as_bool().unwrap_or(true),
                    solo_contornos: c["solo_contornos"].as_bool().unwrap_or(false),
                    zona: c["zona"].as_array().map(|z| [z[0].as_f64().unwrap(), z[1].as_f64().unwrap(), z[2].as_f64().unwrap(), z[3].as_f64().unwrap()]),
                    imagen: None,
                };
                let r = calcar(&rgba, c["ancho"].as_u64().unwrap() as u32, c["alto"].as_u64().unwrap() as u32, &p);
                let ts: Vec<Value> = r.trazos.iter().map(|t| match t {
                    Trazo::Mancha { contorno, huecos, color } => json!({"tipo": "mancha", "color": color, "puntos": pts(contorno), "huecos": huecos.iter().map(|h| pts(h)).collect::<Vec<_>>()}),
                    Trazo::Linea { puntos, cerrada, color } => json!({"tipo": "linea", "color": color, "cerrada": cerrada, "puntos": pts(puntos)}),
                    Trazo::Fondo { caja, color } => json!({"tipo": "fondo", "color": color, "caja": caja}),
                }).collect();
                out.push(json!({"escala": r.escala, "desplazamiento": [r.desplazamiento.0, r.desplazamiento.1], "trazos": ts}));
            }
            println!("{}", serde_json::to_string(&out).unwrap());
        }
        "llamadas" => {
            // Cada línea: {"nombre", "argumentos"}; sale lo que dibuja con semilla 0xABC (o el error).
            for linea in std::io::stdin().lock().lines() {
                let v: Value = serde_json::from_str(&linea.unwrap()).unwrap();
                let cruda = pixpin_ia::motor::LlamadaCruda { id: "1".into(), nombre: v["nombre"].as_str().unwrap().into(), argumentos: v["argumentos"].to_string() };
                let r = match pixpin_ia::herramientas::validar(&cruda, &pixpin_ia::herramientas::catalogo()) {
                    Ok(val) => json!({"elementos": pixpin_ia::lienzo::elementos(&val.llamada, (0.0, 0.0), 0xABC), "avisos": val.avisos}),
                    Err(e) => json!({"error": e.para_el_modelo(&pixpin_ia::herramientas::catalogo())}),
                };
                println!("{}", r);
            }
        }
        otro => panic!("¿{otro}?"),
    }
}
