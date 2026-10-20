//! **`pixpin-shell` del PC, lo poco que usa la hoja al lienzo**: el COM de un hilo (en Android no
//! hay nada que iniciar) y la hora.

pub struct ComDelHilo;

impl ComDelHilo {
    pub fn iniciar() -> ComDelHilo {
        ComDelHilo
    }
}

pub mod entorno {
    pub fn ahora_utc_ms() -> i64 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
    }
}
