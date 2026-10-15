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
        otro => panic!("¿{otro}?"),
    }
}
