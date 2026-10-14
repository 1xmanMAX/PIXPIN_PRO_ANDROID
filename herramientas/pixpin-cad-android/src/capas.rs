//! **El plano por capas, para exportarlo** (14-oct-2026, lo del PC del
//! 13-oct): [`crate::convertir::Convertidor::por_capas`] da un modelo por
//! cada capa y grosor; aquí se guarda en el formato del PC (`PXCAPAS1`,
//! `web::a_bytes` de `crates/pixpin-cad/src/web.rs`, copiado tal cual) y la
//! app lo lee para hacer la página web con las capas que se encienden y se
//! apagan (`planos/PaginaDelPlano.kt`). La página la escribe Kotlin: aquí solo la
//! lectura, que es lo que sabe hacer el .so.

use std::path::Path;

use crate::convertir::PlanoPorCapas;

const MAGIA: &[u8; 8] = b"PXCAPAS1";

/// **El plano por capas en bytes** (`web::a_bytes` del PC).
pub fn a_bytes(p: &PlanoPorCapas) -> Vec<u8> {
    let mut b = Vec::new();
    b.extend_from_slice(MAGIA);
    let texto = |b: &mut Vec<u8>, s: &str| {
        b.extend_from_slice(&(s.len() as u32).to_le_bytes());
        b.extend_from_slice(s.as_bytes());
    };
    b.extend_from_slice(&(p.grupos.len() as u32).to_le_bytes());
    for g in &p.grupos {
        texto(&mut b, &g.capa);
        b.extend_from_slice(&g.grosor.to_le_bytes());
        b.push(g.encendida as u8);
        let m = g.modelo.a_bytes();
        b.extend_from_slice(&(m.len() as u32).to_le_bytes());
        b.extend_from_slice(&m);
    }
    b.extend_from_slice(&(p.imagenes.len() as u32).to_le_bytes());
    for i in &p.imagenes {
        texto(&mut b, &i.capa);
        texto(&mut b, &i.ruta);
        for v in [i.esquina, i.u, i.v].iter().flatten() {
            b.extend_from_slice(&v.to_le_bytes());
        }
    }
    b
}

/// Lee `entrada` por capas y lo deja en `salida` (por un temporal y
/// `rename`). Devuelve cuántos grupos salieron.
pub fn capas_a(entrada: &Path, salida: &Path, carpeta_shx: &str) -> Result<usize, String> {
    crate::poner_carpetas_shx(carpeta_shx);
    let p = std::panic::catch_unwind(|| crate::convertir::convertir_fichero_por_capas(entrada))
        .map_err(|_| "el lector de planos se cayó".to_string())??;
    crate::sacar_lo_que_falta();
    let temporal = salida.with_extension("tmp");
    std::fs::write(&temporal, a_bytes(&p)).map_err(|e| e.to_string())?;
    std::fs::rename(&temporal, salida).map_err(|e| e.to_string())?;
    Ok(p.grupos.len())
}

/// **El plano como DXF** (de un DWG, para quien no tenga con qué abrirlo):
/// lo escribe `opencadcodec` (`leer::a_dxf` del PC).
pub fn dxf_a(entrada: &Path, salida: &Path) -> Result<(), String> {
    let temporal = salida.with_extension("tmp");
    std::panic::catch_unwind(|| crate::leer::a_dxf(entrada, &temporal)).map_err(|_| "el lector de planos se cayó".to_string())??;
    std::fs::rename(&temporal, salida).map_err(|e| e.to_string())
}

#[cfg(test)]
mod pruebas {
    #[test]
    fn el_dxf_de_prueba_sale_por_capas_y_a_dxf() {
        let raiz = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../app/src/test/resources/planos/prueba.dxf");
        let d = std::env::temp_dir().join(format!("pixpin-capas-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        let n = super::capas_a(&raiz, &d.join("p.pxcapas"), "").unwrap();
        assert!(n >= 1);
        let b = std::fs::read(d.join("p.pxcapas")).unwrap();
        assert_eq!(&b[..8], super::MAGIA);
        super::dxf_a(&raiz, &d.join("p.dxf")).unwrap();
        assert!(std::fs::read_to_string(d.join("p.dxf")).unwrap().contains("SECTION"));
    }
    use std::path::Path;
}
