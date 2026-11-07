// **La galería de la comunidad** (7-nov-2026): la gente sube lo que hizo (miniapps, figuras, plantillas, lienzos,
// planos…), los demás lo ven, le dan «me gusta», lo descargan y lo usan; y siguen subiendo versiones nuevas.
//
// Lo que se cuida:
//  - **Para subir hace falta la cuenta de Google** (y no estar bloqueado), con un tope por día.
//  - **Revisión antes de publicar** (GALERIA_REVISION=si, lo de siempre): lo nuevo entra como «pendiente» y solo lo
//    ven los demás cuando lo apruebas en el panel. Con «no», se publica al momento (y las denuncias lo esconden).
//  - **Tres denuncias de personas distintas lo esconden** hasta que lo mires.
//  - **Nada de lo subido se abre en el navegador desde aquí**: se sirve siempre como descarga (octet-stream,
//    attachment, nosniff y CSP sandbox); así una miniapp de alguien no puede tocar el panel, que vive en esta misma
//    dirección. La vista previa solo si es de verdad un PNG o un JPEG (se miran sus primeros bytes).
//  - Los archivos, en `DATOS/galeria/`, con su huella SHA-256; los datos, en `galeria.json`.

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

export const TIPOS = ["miniapp", "figura", "plantilla", "lienzo", "plano", "documento", "otro"];
const DIA = 86_400_000;

export function crearGaleria({ almacen, cfg, ahora, carpeta }) {
  const dir = path.join(carpeta, "galeria");
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const revision = cfg.galeriaRevision !== false;
  const maxArchivo = cfg.galeriaMaxBytes ?? 3_000_000;
  const maxVista = 400_000;
  const porDia = cfg.galeriaPorDia ?? 10;

  const todo = () => almacen.leer("galeria") ?? { items: {} };
  const cambiar = (f) => almacen.cambiar("galeria", (g) => { const c = g ?? { items: {} }; f(c); return c; });
  const archivoDe = (id, cual) => path.join(dir, `${id}.${cual}`);

  /** Lo que ve cualquiera de una contribución (nunca el correo del autor ni quién le dio me gusta). */
  function publico(it, u) {
    return {
      id: it.id, tipo: it.tipo, titulo: it.titulo, descripcion: it.descripcion, etiquetas: it.etiquetas,
      archivo: { nombre: it.archivo.nombre, bytes: it.archivo.bytes, sha256: it.archivo.sha256 },
      vista: !!it.vista, autor: it.autorNombre, creada: it.creada, actualizada: it.actualizada ?? it.creada, version: it.version ?? 1,
      gustos: it.gustos.length, descargas: it.descargas, meGusta: !!(u && it.gustos.includes(u.uid)),
      ...(u && it.autor === u.uid ? { mia: true, estado: it.estado, motivo: it.motivo ?? null } : {}),
    };
  }

  const texto = (v, max) => String(v ?? "").replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "").trim().slice(0, max);
  const nombreLimpio = (n) => texto(n, 80).replace(/[\\/:*?"<>|]/g, "_").replace(/^\.+/, "") || "archivo";

  /** Si son los bytes de un PNG o un JPEG de verdad. */
  function esImagen(b) {
    return (b.length > 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) || (b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff);
  }

  function lista(url, u) {
    const tipo = url.searchParams.get("tipo");
    const q = (url.searchParams.get("q") ?? "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
    const orden = url.searchParams.get("orden") ?? "populares";
    const desde = Math.max(0, parseInt(url.searchParams.get("desde") ?? "0", 10) || 0);
    const n = Math.min(60, Math.max(1, parseInt(url.searchParams.get("n") ?? "30", 10) || 30));
    let l = Object.values(todo().items).filter((it) => it.estado === "publicada");
    if (tipo && TIPOS.includes(tipo)) l = l.filter((it) => it.tipo === tipo);
    if (q) l = l.filter((it) => `${it.titulo} ${it.descripcion} ${it.etiquetas.join(" ")} ${it.autorNombre}`.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").includes(q));
    const t = ahora();
    // «Populares»: me gusta y descargas, con lo reciente un poco por delante (que lo nuevo bueno pueda subir).
    const puntos = (it) => (it.gustos.length * 3 + it.descargas) / Math.pow(1 + (t - (it.actualizada ?? it.creada)) / (7 * DIA), 0.6);
    l.sort(orden === "nuevas" ? (a, b) => (b.actualizada ?? b.creada) - (a.actualizada ?? a.creada)
      : orden === "descargas" ? (a, b) => b.descargas - a.descargas
      : (a, b) => puntos(b) - puntos(a));
    return { total: l.length, items: l.slice(desde, desde + n).map((it) => publico(it, u)) };
  }

  function subir(p, u) {
    if (u.bloqueada) return [403, { error: "cuenta_bloqueada" }];
    const tipo = TIPOS.includes(p.tipo) ? p.tipo : null;
    if (!tipo) return [400, { error: "tipo_no_valido", tipos: TIPOS }];
    const titulo = texto(p.titulo, 80);
    if (titulo.length < 3) return [400, { error: "falta_titulo" }];
    let bytes;
    try { bytes = Buffer.from(String(p.contenido ?? ""), "base64"); } catch { bytes = Buffer.alloc(0); }
    if (!bytes.length) return [400, { error: "falta_archivo" }];
    if (bytes.length > maxArchivo) return [413, { error: "archivo_demasiado_grande", maximo: maxArchivo }];
    let vista = null;
    if (p.vista) {
      vista = Buffer.from(String(p.vista), "base64");
      if (vista.length > maxVista || !esImagen(vista)) vista = null;
    }
    const t = ahora();
    const g = todo();
    const hoy = Object.values(g.items).filter((it) => it.autor === u.uid && (it.actualizada ?? it.creada) > t - DIA).length;
    if (hoy >= porDia) return [429, { error: "demasiadas_hoy", maximo: porDia }];
    // Una versión nueva de algo suyo: mismo id, la versión sube y (con revisión) vuelve a pendiente.
    const previo = p.reemplaza ? g.items[p.reemplaza] : null;
    if (p.reemplaza && (!previo || previo.autor !== u.uid || previo.estado === "borrada")) return [404, { error: "no_es_tuya" }];
    const id = previo?.id ?? crypto.randomBytes(9).toString("base64url");
    fs.writeFileSync(archivoDe(id, "bin"), bytes, { mode: 0o600 });
    if (vista) fs.writeFileSync(archivoDe(id, "img"), vista, { mode: 0o600 });
    else if (previo && p.vista === null) fs.rmSync(archivoDe(id, "img"), { force: true });
    const it = {
      ...(previo ?? { id, autor: u.uid, creada: t, gustos: [], descargas: 0, denuncias: [], version: 0 }),
      tipo, titulo, descripcion: texto(p.descripcion, 1000),
      etiquetas: [...new Set((Array.isArray(p.etiquetas) ? p.etiquetas : []).map((e) => texto(e, 24).toLowerCase()).filter(Boolean))].slice(0, 8),
      archivo: { nombre: nombreLimpio(p.nombre), bytes: bytes.length, sha256: crypto.createHash("sha256").update(bytes).digest("hex") },
      vista: vista ? true : (previo?.vista && p.vista !== null) || false,
      autorNombre: texto((u.nombre ?? "").split(" ")[0] || "Anónimo", 40),
      estado: revision ? "pendiente" : "publicada", motivo: null,
      actualizada: t,
    };
    it.version = (previo?.version ?? 0) + 1;
    cambiar((c) => { c.items[id] = it; });
    return [200, { ok: true, item: publico(it, u) }];
  }

  /** Las rutas `/v1/galeria…`; null si no es de aquí. */
  async function manejar(url, req, res, { quien, cuerpo, responder }) {
    if (!url.pathname.startsWith("/v1/galeria")) return null;
    const u = quien(req);
    const partes = url.pathname.split("/").filter(Boolean); // v1, galeria, id?, accion?
    if (partes.length === 2) {
      if (req.method === "GET") return responder(res, 200, lista(url, u));
      if (req.method === "POST") {
        if (!u) return responder(res, 401, { error: "sin_sesion" });
        const [e, r] = subir(await cuerpo(req, Math.ceil((maxArchivo + maxVista) * 1.4) + 20_000), u);
        return responder(res, e, r);
      }
    }
    if (partes.length === 3 && partes[2] === "mias" && req.method === "GET") {
      if (!u) return responder(res, 401, { error: "sin_sesion" });
      const l = Object.values(todo().items).filter((it) => it.autor === u.uid && it.estado !== "borrada").sort((a, b) => (b.actualizada ?? 0) - (a.actualizada ?? 0));
      return responder(res, 200, { items: l.map((it) => publico(it, u)) });
    }
    const it = partes[2] ? todo().items[partes[2]] : null;
    const visible = it && (it.estado === "publicada" || (u && it.autor === u.uid && it.estado !== "borrada"));
    if (!visible) return responder(res, 404, { error: "no_existe" });
    const accion = partes[3];
    if (!accion && req.method === "GET") return responder(res, 200, publico(it, u));
    if (accion === "archivo" && req.method === "GET") {
      if (!fs.existsSync(archivoDe(it.id, "bin"))) return responder(res, 404, { error: "no_existe" });
      if (it.estado === "publicada" && !(u && it.autor === u.uid)) cambiar((c) => { c.items[it.id].descargas++; });
      res.writeHead(200, {
        "content-type": "application/octet-stream",
        "content-disposition": `attachment; filename*=UTF-8''${encodeURIComponent(it.archivo.nombre)}`,
        "content-security-policy": "sandbox",
        "x-sha256": it.archivo.sha256,
      });
      return res.end(fs.readFileSync(archivoDe(it.id, "bin")));
    }
    if (accion === "vista" && req.method === "GET") {
      if (!it.vista || !fs.existsSync(archivoDe(it.id, "img"))) return responder(res, 404, { error: "sin_vista" });
      const b = fs.readFileSync(archivoDe(it.id, "img"));
      res.writeHead(200, { "content-type": b[0] === 0x89 ? "image/png" : "image/jpeg", "content-security-policy": "sandbox", "cache-control": "public, max-age=600" });
      return res.end(b);
    }
    if (req.method !== "POST") return responder(res, 404, { error: "no_existe" });
    if (!u) return responder(res, 401, { error: "sin_sesion" });
    if (u.bloqueada) return responder(res, 403, { error: "cuenta_bloqueada" });
    if (accion === "gusta") {
      const p = await cuerpo(req);
      const c = cambiar((g) => {
        const x = g.items[it.id];
        const ya = x.gustos.includes(u.uid);
        const quiere = p.si === undefined ? !ya : !!p.si;
        if (quiere && !ya) x.gustos.push(u.uid);
        if (!quiere && ya) x.gustos = x.gustos.filter((v) => v !== u.uid);
      });
      return responder(res, 200, publico(c.items[it.id], u));
    }
    if (accion === "denunciar") {
      const p = await cuerpo(req);
      cambiar((g) => {
        const x = g.items[it.id];
        if (!x.denuncias.some((d) => d.uid === u.uid)) x.denuncias.push({ uid: u.uid, motivo: texto(p.motivo, 300), t: ahora() });
        // Tres personas distintas: se esconde hasta que lo mires en el panel.
        if (x.estado === "publicada" && x.denuncias.length >= 3) { x.estado = "pendiente"; x.motivo = "denunciada"; }
      });
      return responder(res, 200, { ok: true });
    }
    if (accion === "borrar") {
      if (it.autor !== u.uid) return responder(res, 403, { error: "no_es_tuya" });
      cambiar((g) => { g.items[it.id].estado = "borrada"; });
      fs.rmSync(archivoDe(it.id, "bin"), { force: true }); fs.rmSync(archivoDe(it.id, "img"), { force: true });
      return responder(res, 200, { ok: true });
    }
    return responder(res, 404, { error: "no_existe" });
  }

  /** El panel: lo pendiente (y lo denunciado) primero; publicar, rechazar u ocultar; bajar para mirarlo. */
  async function panel(ruta, url, req, res, { cuerpo, responder, usuarios }) {
    if (ruta === "galeria" && req.method === "GET") {
      const us = usuarios();
      const orden = { pendiente: 0, publicada: 1, oculta: 2, rechazada: 3, borrada: 4 };
      const l = Object.values(todo().items).filter((it) => it.estado !== "borrada")
        .sort((a, b) => (orden[a.estado] - orden[b.estado]) || (b.actualizada ?? 0) - (a.actualizada ?? 0))
        .map((it) => ({ ...publico(it, null), estado: it.estado, motivo: it.motivo ?? null, autorCorreo: us[it.autor]?.email ?? null,
          denuncias: it.denuncias.map((d) => ({ motivo: d.motivo, t: d.t })) }));
      return responder(res, 200, { items: l, revision });
    }
    if (ruta === "galeria/estado" && req.method === "POST") {
      const p = await cuerpo(req);
      if (!["publicada", "rechazada", "oculta", "pendiente"].includes(p.estado)) return responder(res, 400, { error: "estado_no_valido" });
      if (!todo().items[p.id]) return responder(res, 404, { error: "no_existe" });
      const c = cambiar((g) => {
        const x = g.items[p.id];
        x.estado = p.estado; x.motivo = p.motivo ? texto(p.motivo, 300) : null;
        // Publicarla otra vez después de mirarla: las denuncias de antes ya se atendieron.
        if (p.estado === "publicada") x.denuncias = [];
      });
      return responder(res, 200, { ok: true, item: c.items[p.id] });
    }
    if (ruta === "galeria/archivo" && req.method === "GET") {
      const it = todo().items[url.searchParams.get("id") ?? ""];
      if (!it || !fs.existsSync(archivoDe(it.id, "bin"))) return responder(res, 404, { error: "no_existe" });
      res.writeHead(200, { "content-type": "application/octet-stream", "content-disposition": `attachment; filename*=UTF-8''${encodeURIComponent(it.archivo.nombre)}`, "content-security-policy": "sandbox" });
      return res.end(fs.readFileSync(archivoDe(it.id, "bin")));
    }
    return null;
  }

  return { manejar, panel };
}
