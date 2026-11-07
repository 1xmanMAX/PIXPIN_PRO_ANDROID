// **Las funciones de PinDesk y cómo empiezan** (7-nov-2026, el relanzamiento: «que parezca que la app solo tiene los
// visores, el asistente, las tareas y la cuenta»). Lo que no está aquí, la app lo trata como oculto. Desde el panel
// se cambia el estado de cada una (oculta · gratis · pro) o se programa un cambio para una fecha, sin publicar una
// versión nueva. Los ids son los mismos que en la app (`cuenta/Funciones.kt`).
export const CATALOGO_INICIAL = {
  revision: 1,
  funciones: {
    // Lo que se ve desde el primer día.
    "visores":            { estado: "gratis", nombre: "Visores (PDF, Office, planos, 3D…)" },
    "asistente":          { estado: "gratis", nombre: "Asistente con IA" },
    "tareas":             { estado: "gratis", nombre: "Tareas" },
    "cuenta":             { estado: "gratis", nombre: "Cuenta de Google" },
    "pines":              { estado: "gratis", nombre: "Pines flotantes" },
    // La barra lateral de los visores: cada botón por su cuenta.
    "riel.marcador":      { estado: "oculta", nombre: "Marcadores en los visores" },
    "riel.lapiz":         { estado: "oculta", nombre: "Dibujar y anotar en los visores" },
    "riel.marco":         { estado: "oculta", nombre: "Frames (marcos) en los visores" },
    // Lo demás, para ir abriéndolo semana a semana.
    "marcas.texto":       { estado: "oculta", nombre: "Resaltar texto" },
    "comentarios":        { estado: "oculta", nombre: "Comentarios en los documentos" },
    "exportar":           { estado: "oculta", nombre: "Exportar con anotaciones" },
    "chats":              { estado: "oculta", nombre: "Chats y proyectos" },
    "capturas":           { estado: "oculta", nombre: "Capturas" },
    "grabar":             { estado: "oculta", nombre: "Grabar clases y notas de voz" },
    "lienzo":             { estado: "oculta", nombre: "Lienzo y figuras" },
    "horario":            { estado: "oculta", nombre: "Horario" },
    "sincronizar":        { estado: "oculta", nombre: "Sincronizar con el PC y la nube" },
    "escaner":            { estado: "oculta", nombre: "Escáner" },
    "musica":             { estado: "oculta", nombre: "Música y karaoke" },
    "anki":               { estado: "oculta", nombre: "Tarjetas Anki" },
    "planos.medir":       { estado: "oculta", nombre: "Medir en planos" },
    "widgets":            { estado: "oculta", nombre: "Widgets" },
    "comunidad":          { estado: "oculta", nombre: "Comunidad (galería compartida)" },
  },
};
