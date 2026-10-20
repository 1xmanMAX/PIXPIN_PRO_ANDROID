//! De `pixpin-shell/mensajero.rs` del PC: el tamaño, la versión y las acciones del protocolo de pedidos.

/// Lo mas que puede medir un pedido. Un pedido es una orden corta, no un
/// documento: 64 KB caben de sobra en el texto mas largo que alguien escriba
/// en un lanzador, y un tope evita copiar megas que nadie deberia mandar.
pub const TOPE_PEDIDO: usize = 64 * 1024;

/// La version del protocolo que entiende esta copia (`"pixpin": 1`).
pub const VERSION_PEDIDO: u64 = 1;

/// Las respuestas inmediatas a un pedido (el `LRESULT` de `WM_COPYDATA`).
/// El `0` no esta: es lo que contesta Windows cuando no hay nadie o el
/// mensaje no era para nosotros.
pub mod respuesta {
    /// Aceptado: queda en la cola y el bucle lo hara enseguida.
    pub const ACEPTADO: isize = 1;
    /// Se pidio con una version del protocolo que esta copia no conoce.
    pub const VERSION_NO: isize = 2;
    /// JSON roto, demasiado grande o con una accion que no existe.
    pub const NO_SE_ENTIENDE: isize = 3;
}

/// Las acciones que existen en la version 1. Viven aqui, y no en la
/// aplicacion, porque la ventana tiene que contestar `3` en el acto a una
/// accion desconocida sin esperar al bucle. La aplicacion tiene una prueba
/// que comprueba que entiende todas (`pedidos.rs`): si una se anade aqui y
/// no alli, falla.
pub const ACCIONES: [&str; 37] = [
    "abrir",
    "chat",
    "nota_nueva",
    "lienzo_nuevo",
    "grabar",
    "lista_nueva",
    "anadir_tarea",
    "marcar_tarea",
    "ventana_principal",
    "pinear",
    "iconos",
    "soltar",
    "reproducir",
    "leccion_nueva",
    "lecciones",
    "mover_tarea",
    "ventana",
    "capturar",
    "pinear_ultima",
    "conservar_captura",
    "borrar_captura",
    "copiar_imagen",
    "quitar_tarea",
    "corregir_tarea",
    "borrar_lista",
    "enviar_al_movil",
    "sincronizar",
    // 13-oct: el asistente sin abrir el chat (`p ia …`) y sus
    // automatizaciones.
    "asistente",
    "automatizacion",
    // 14-oct: una tabla del chat ya rellena (el asistente, tabla escaneada).
    "tabla_nueva",
    // 15-oct: «+10 min» de la notificacion de un recordatorio
    // (`notificacion`).
    "posponer",
    // 15-oct: el asistente maneja los pines, el timeline y la voz.
    "pines",
    "timeline",
    // 15-oct: la extension del navegador (`apps/pixpin-puente`): leer en voz
    // alta un texto suelto y pasar el texto de una imagen al portapapeles.
    "leer_en_voz",
    "ocr",
    // 15-oct: una herramienta del asistente con sus argumentos y el
    // resultado por un fichero (el servidor MCP, `apps/pixpin-mcp`).
    "herramienta",
    // 17-oct: fotos y texto a un lienzo por su nombre, apilados.
    "al_lienzo",
];

/// Mira un pedido por encima: lo justo para contestar en el acto.
///
/// Solo comprueba que es un objeto JSON con `"pixpin": 1` y una `accion`
/// conocida; que los campos de esa accion esten bien lo mira la aplicacion
/// despues, en su bucle, y si falta algo lo dice con su aviso. Asi el
/// procedimiento de ventana no hace trabajo de verdad, pero un pedido
/// escrito a mano con una errata en la accion no se da por bueno.
///
/// Una version que no es un numero (o falta) es un pedido roto, no una
/// version distinta: sin `pixpin` no es un pedido nuestro.
pub fn validar_pedido(json: &str) -> isize {
    let Ok(serde_json::Value::Object(pedido)) = serde_json::from_str::<serde_json::Value>(json)
    else {
        return respuesta::NO_SE_ENTIENDE;
    };
    match pedido.get("pixpin").and_then(|v| v.as_u64()) {
        Some(VERSION_PEDIDO) => {}
        Some(_) => return respuesta::VERSION_NO,
        None => return respuesta::NO_SE_ENTIENDE,
    }
    match pedido.get("accion").and_then(|v| v.as_str()) {
        Some(a) if ACCIONES.contains(&a) => respuesta::ACEPTADO,
        _ => respuesta::NO_SE_ENTIENDE,
    }
}

