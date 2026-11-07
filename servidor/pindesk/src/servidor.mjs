// **El servidor de PinDesk** (7-nov-2026): cuentas con Google, qué funciones ve cada persona y si las puede usar
// (catálogo + licencia, firmados), la versión de la app para actualizarse sola, y el asistente (Claude) con cupo.
// Pensado para una VPS: Node 22 sin ninguna dependencia, los datos en archivos JSON en una carpeta, y delante Caddy
// para el HTTPS (ver README.md). Nada de esto confía en la app: todo lo que cuesta (la IA) se comprueba aquí.
//
// Rutas públicas (la app):
//   GET  /v1/catalogo                         -> catálogo firmado (igual para todos)
//   GET  /v1/version                          -> la versión más nueva de la app, firmada (para actualizarse)
//   POST /v1/entrar      {"idToken"}          -> {"sesion", "cuenta", "licencia", "catalogo"}   (Google)
//   GET  /v1/estado      Bearer <sesion>      -> {"cuenta", "licencia", "catalogo", "cupo"}
//   POST /v1/messages    Bearer <sesion>      -> lo mismo que api.anthropic.com/v1/messages (con la clave de aquí)
// Panel (tú), con clave + código de 6 cifras:  /admin  y  /admin/api/*

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { firmar, publicaDe, sesionNueva, leerSesion, iguales, totpValido, deB64url } from "./firma.mjs";
import { Almacen } from "./almacen.mjs";
import { CATALOGO_INICIAL } from "./catalogo-inicial.mjs";

const AQUI = path.dirname(fileURLToPath(import.meta.url));
const ANTHROPIC = "https://api.anthropic.com";
const DIA = 86_400_000;

export function configDesdeEntorno(env = process.env) {
  const leer = (n, def) => (env[n] !== undefined && env[n] !== "" ? env[n] : def);
  const clavePrivada = leer("CLAVE_PRIVADA_PEM", null) ?? (leer("CLAVE_PRIVADA_ARCHIVO", null) ? fs.readFileSync(env.CLAVE_PRIVADA_ARCHIVO, "utf8") : null);
  return {
    puerto: parseInt(leer("PUERTO", "8080"), 10),
    host: leer("HOST", "127.0.0.1"),
    datos: leer("DATOS", path.join(AQUI, "..", "datos")),
    clavePrivada,
    secretoSesion: leer("SECRETO_SESION", null),
    googleClientIds: String(leer("GOOGLE_CLIENT_IDS", "")).split(",").map((s) => s.trim()).filter(Boolean),
    googleCerts: leer("GOOGLE_CERTS_URL", "https://www.googleapis.com/oauth2/v3/certs"),
    claudeKey: leer("CLAUDE_API_KEY", null),
    anthropic: leer("ANTHROPIC_URL", ANTHROPIC),
    adminClave: leer("ADMIN_CLAVE", null),
    adminTotp: leer("ADMIN_TOTP", null),
    adminCorreos: String(leer("ADMIN_CORREOS", "")).toLowerCase().split(",").map((s) => s.trim()).filter(Boolean),
    diasDePrueba: parseInt(leer("DIAS_DE_PRUEBA", "30"), 10),
    // La sesión se renueva sola en cada /v1/estado: solo caduca tras estos días sin abrir la app.
    diasDeSesion: parseInt(leer("DIAS_DE_SESION", "90"), 10),
    // Cuánto vale una licencia sin volver a preguntar (la app la renueva sola mucho antes).
    diasDeLicencia: parseInt(leer("DIAS_DE_LICENCIA", "7"), 10),
    cupoGratis: parseInt(leer("CUPO_GRATIS", "15"), 10),
    cupoPro: parseInt(leer("CUPO_PRO", "150"), 10),
    modelosPermitidos: String(leer("MODELOS_PERMITIDOS", "")).split(",").map((s) => s.trim()).filter(Boolean),
    maxTokens: parseInt(leer("MAX_TOKENS", "4096"), 10),
  };
}

export function crearServidor(cfg, ahora = () => Date.now()) {
  if (!cfg.clavePrivada) throw new Error("Falta la clave privada (CLAVE_PRIVADA_ARCHIVO): node src/claves.mjs la crea");
  if (!cfg.secretoSesion || cfg.secretoSesion.length < 32) throw new Error("Falta SECRETO_SESION (32 caracteres o más)");
  const almacen = new Almacen(cfg.datos);
  if (!almacen.leer("catalogo")) almacen.guardar("catalogo", CATALOGO_INICIAL);
  const publica = publicaDe(cfg.clavePrivada);
  let certs = { claves: null, hasta: 0 };
  const intentos = new Map(); // ip -> [ms...]: frena a quien prueba muchas veces

  // ---------------------------------------------------------------- lo que se firma

  /** El catálogo de ahora: las funciones con su estado vigente (los cambios programados ya aplicados). */
  function catalogo() {
    const c = almacen.leer("catalogo") ?? CATALOGO_INICIAL;
    const t = ahora();
    const funciones = {};
    for (const [id, f] of Object.entries(c.funciones ?? {})) {
      let estado = f.estado;
      // Un cambio programado («desde el 1 de diciembre, Pro») manda cuando llega su día.
      for (const p of f.programado ?? []) if (p.desde <= t) estado = p.estado;
      const proximo = (f.programado ?? []).filter((p) => p.desde > t).sort((a, b) => a.desde - b.desde)[0];
      funciones[id] = { estado, ...(f.nombre ? { nombre: f.nombre } : {}), ...(proximo ? { proximo: { estado: proximo.estado, desde: proximo.desde } } : {}), ...(f.aviso ? { aviso: f.aviso } : {}) };
    }
    return firmar({ tipo: "catalogo", emitido: t, revision: c.revision ?? 1, funciones }, cfg.clavePrivada);
  }

  function licenciaDe(u) {
    const t = ahora();
    const pro = u.proHasta && u.proHasta > t;
    const prueba = !pro && u.pruebaHasta && u.pruebaHasta > t;
    return firmar({
      tipo: "licencia", uid: u.uid, cuenta: u.email, nombre: u.nombre ?? null,
      plan: pro ? "pro" : prueba ? "prueba" : "gratis",
      hasta: pro ? u.proHasta : prueba ? u.pruebaHasta : null,
      // Funciones regaladas a esta persona en concreto (los fundadores, una prueba de algo nuevo…).
      regaladas: u.regaladas ?? [],
      bloqueada: !!u.bloqueada,
      emitida: t,
      // La app no se fía de una licencia vieja: pasada esta fecha la pide otra (y si no hay red, vuelve a gratis).
      vence: t + cfg.diasDeLicencia * DIA,
    }, cfg.clavePrivada);
  }

  function versionFirmada() {
    const v = almacen.leer("version");
    return v ? firmar({ tipo: "version", ...v, emitido: ahora() }, cfg.clavePrivada) : null;
  }

  // ---------------------------------------------------------------- Google

  async function certsDeGoogle() {
    if (certs.claves && certs.hasta > ahora()) return certs.claves;
    const r = await fetch(cfg.googleCerts);
    const edad = /max-age=(\d+)/.exec(r.headers.get("cache-control") ?? "")?.[1];
    certs = { claves: (await r.json()).keys ?? [], hasta: ahora() + (edad ? parseInt(edad, 10) * 1000 : 3600_000) };
    return certs.claves;
  }

  /** El id_token de Google: firma RS256 con sus certificados, emisor, destinatario (nuestros ID), caducidad, correo verificado. */
  async function verificarGoogle(token) {
    const partes = String(token || "").split(".");
    if (partes.length !== 3) return null;
    let cab, datos;
    try { cab = JSON.parse(deB64url(partes[0])); datos = JSON.parse(deB64url(partes[1])); } catch { return null; }
    if (cab.alg !== "RS256") return null;
    const jwk = (await certsDeGoogle()).find((k) => k.kid === cab.kid);
    if (!jwk) return null;
    const clave = crypto.createPublicKey({ key: jwk, format: "jwk" });
    if (!crypto.verify("sha256", Buffer.from(`${partes[0]}.${partes[1]}`), clave, deB64url(partes[2]))) return null;
    if (!["accounts.google.com", "https://accounts.google.com"].includes(datos.iss)) return null;
    if (!cfg.googleClientIds.includes(datos.aud)) return null;
    if (!datos.exp || datos.exp * 1000 < ahora()) return null;
    if (datos.email_verified !== true && datos.email_verified !== "true") return null;
    return datos;
  }

  // ---------------------------------------------------------------- usuarios

  const usuarios = () => almacen.leer("usuarios") ?? {};
  function guardarUsuario(u) { almacen.cambiar("usuarios", (todos) => ({ ...(todos ?? {}), [u.uid]: u })); return u; }

  function quien(req) {
    const m = /^Bearer\s+(.+)$/i.exec(req.headers.authorization ?? "");
    const s = m && leerSesion(m[1], cfg.secretoSesion, ahora());
    if (!s) return null;
    const u = usuarios()[s.u];
    // Una sesión vieja de alguien que se bloqueó, o cuyo «cerrar sesiones» es posterior, ya no vale.
    if (!u || (u.sesionesDesde && s.i < u.sesionesDesde)) return null;
    return u;
  }

  function cuentaPublica(u) { return { email: u.email, nombre: u.nombre ?? null, foto: u.foto ?? null, alta: u.alta }; }

  // ---------------------------------------------------------------- cupo del asistente

  function cupoDe(u) {
    const lic = JSON.parse(deB64url(licenciaDe(u).datos));
    const total = lic.plan === "gratis" ? cfg.cupoGratis : cfg.cupoPro;
    const hoy = new Date(ahora()).toISOString().slice(0, 10);
    const usado = (almacen.leer("cupos") ?? {})[hoy]?.[u.uid] ?? 0;
    return { total, usado, quedan: Math.max(0, total - usado), dia: hoy };
  }
  function gastarCupo(u) {
    const hoy = new Date(ahora()).toISOString().slice(0, 10);
    almacen.cambiar("cupos", (c) => {
      const todos = c ?? {};
      // Solo se guardan los cupos de hoy: lo de ayer ya no sirve.
      const delDia = todos[hoy] ?? {};
      return { [hoy]: { ...delDia, [u.uid]: (delDia[u.uid] ?? 0) + 1 } };
    });
  }

  function estadoDeFuncion(id) {
    return JSON.parse(deB64url(catalogo().datos)).funciones[id]?.estado ?? "oculta";
  }

  // ---------------------------------------------------------------- las rutas

  async function manejar(req, res) {
    const url = new URL(req.url, "http://x");
    const ip = req.headers["x-forwarded-for"]?.split(",")[0]?.trim() || req.socket.remoteAddress || "";
    seguridad(res);
    if (req.method === "OPTIONS") return responder(res, 204, null);

    if (url.pathname === "/salud") return responder(res, 200, { ok: true });
    if (url.pathname === "/v1/catalogo" && req.method === "GET") return responder(res, 200, catalogo());
    if (url.pathname === "/v1/clave" && req.method === "GET") return responder(res, 200, { publica });
    if (url.pathname === "/v1/version" && req.method === "GET") return responder(res, 200, versionFirmada() ?? { sin: true });

    if (url.pathname === "/v1/entrar" && req.method === "POST") {
      if (demasiados(intentos, ip, ahora(), 20, 600_000)) return responder(res, 429, { error: "demasiados_intentos" });
      const { idToken } = await cuerpo(req);
      const g = await verificarGoogle(idToken);
      if (!g) return responder(res, 401, { error: "token_no_valido" });
      const email = String(g.email).toLowerCase();
      const uid = "g-" + crypto.createHash("sha256").update(String(g.sub)).digest("hex").slice(0, 24);
      const antes = usuarios()[uid];
      const t = ahora();
      const u = guardarUsuario({
        ...(antes ?? { uid, alta: t, pruebaHasta: t + cfg.diasDePrueba * DIA }),
        email, nombre: g.name ?? antes?.nombre ?? null, foto: g.picture ?? antes?.foto ?? null, ultimaVez: t,
      });
      if (u.bloqueada) return responder(res, 403, { error: "cuenta_bloqueada" });
      const sesion = sesionNueva({ u: uid, i: t, x: t + cfg.diasDeSesion * DIA }, cfg.secretoSesion);
      return responder(res, 200, { sesion, cuenta: cuentaPublica(u), licencia: licenciaDe(u), catalogo: catalogo(), nueva: !antes });
    }

    if (url.pathname === "/v1/estado" && req.method === "GET") {
      const u = quien(req);
      if (!u) return responder(res, 401, { error: "sin_sesion" });
      const t = ahora();
      guardarUsuario({ ...u, ultimaVez: t });
      const sesion = sesionNueva({ u: u.uid, i: t, x: t + cfg.diasDeSesion * DIA }, cfg.secretoSesion);
      return responder(res, 200, { sesion, cuenta: cuentaPublica(u), licencia: licenciaDe(u), catalogo: catalogo(), cupo: cupoDe(u) });
    }

    if (url.pathname === "/v1/messages" && req.method === "POST") {
      const u = quien(req);
      if (!u) return responder(res, 401, { type: "error", error: { type: "authentication_error", message: "sin_sesion" } });
      if (u.bloqueada) return responder(res, 403, { type: "error", error: { type: "permission_error", message: "cuenta_bloqueada" } });
      const estado = estadoDeFuncion("asistente");
      const lic = JSON.parse(deB64url(licenciaDe(u).datos));
      const puede = estado === "gratis" || (estado === "pro" && lic.plan !== "gratis") || lic.regaladas.includes("asistente");
      if (!puede) return responder(res, 403, { type: "error", error: { type: "permission_error", message: "asistente_no_disponible" } });
      const cupo = cupoDe(u);
      if (cupo.quedan <= 0) return responder(res, 429, { type: "error", error: { type: "rate_limit_error", message: "cupo_de_hoy_agotado" } });
      if (!cfg.claudeKey) return responder(res, 503, { type: "error", error: { type: "api_error", message: "sin_clave_de_claude" } });
      const pedido = await cuerpo(req, 2_000_000);
      // Que nadie use la clave para otra cosa: el modelo, de la lista; y un tope de respuesta.
      if (cfg.modelosPermitidos.length && !cfg.modelosPermitidos.includes(pedido.model)) pedido.model = cfg.modelosPermitidos[0];
      pedido.max_tokens = Math.min(pedido.max_tokens ?? cfg.maxTokens, cfg.maxTokens);
      gastarCupo(u);
      return aClaude(pedido, req, res);
    }

    if (url.pathname === "/admin" || url.pathname === "/admin/") {
      res.writeHead(200, { "content-type": "text/html; charset=utf-8", "content-security-policy": "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' https: data:" });
      return res.end(fs.readFileSync(path.join(AQUI, "panel.html")));
    }
    if (url.pathname.startsWith("/admin/api/")) {
      if (demasiados(intentos, "admin:" + ip, ahora(), 30, 600_000)) return responder(res, 429, { error: "demasiados_intentos" });
      if (!admin(req)) return responder(res, 401, { error: "no_autorizado" });
      intentos.delete("admin:" + ip);
      return panel(url, req, res);
    }
    return responder(res, 404, { error: "no_existe" });
  }

  /** El panel: la clave del panel (cabecera) y el código de 6 cifras de la app de autenticación. */
  function admin(req) {
    if (!cfg.adminClave) return false;
    const clave = req.headers["x-admin-clave"] ?? "";
    if (!iguales(clave, cfg.adminClave)) return false;
    if (cfg.adminTotp && !totpValido(cfg.adminTotp, req.headers["x-admin-codigo"], ahora())) return false;
    return true;
  }

  async function panel(url, req, res) {
    const ruta = url.pathname.slice("/admin/api/".length);
    if (ruta === "resumen" && req.method === "GET") {
      const us = Object.values(usuarios()).map((u) => ({ ...u, licencia: JSON.parse(deB64url(licenciaDe(u).datos)) }));
      us.sort((a, b) => (b.ultimaVez ?? 0) - (a.ultimaVez ?? 0));
      return responder(res, 200, { usuarios: us, catalogo: almacen.leer("catalogo"), version: almacen.leer("version") ?? null, publica });
    }
    if (ruta === "usuario" && req.method === "POST") {
      const p = await cuerpo(req);
      const u = usuarios()[p.uid];
      if (!u) return responder(res, 404, { error: "no_existe" });
      const t = ahora();
      const nuevo = { ...u };
      if (p.proDias !== undefined) nuevo.proHasta = p.proDias > 0 ? Math.max(t, u.proHasta ?? 0) + p.proDias * DIA : null;
      if (p.pruebaDias !== undefined) nuevo.pruebaHasta = p.pruebaDias > 0 ? t + p.pruebaDias * DIA : null;
      if (p.regaladas !== undefined) nuevo.regaladas = [...new Set(p.regaladas.filter((x) => typeof x === "string"))];
      if (p.bloqueada !== undefined) { nuevo.bloqueada = !!p.bloqueada; if (p.bloqueada) nuevo.sesionesDesde = t; }
      if (p.nota !== undefined) nuevo.nota = String(p.nota).slice(0, 500);
      guardarUsuario(nuevo);
      return responder(res, 200, { ok: true, usuario: nuevo });
    }
    if (ruta === "funcion" && req.method === "POST") {
      const p = await cuerpo(req);
      if (!/^[a-z0-9.\-_]{2,60}$/.test(p.id ?? "")) return responder(res, 400, { error: "id_no_valido" });
      if (p.estado && !["oculta", "gratis", "pro"].includes(p.estado)) return responder(res, 400, { error: "estado_no_valido" });
      const c = almacen.cambiar("catalogo", (cat) => {
        const actual = cat ?? CATALOGO_INICIAL;
        const f = { ...(actual.funciones[p.id] ?? { estado: "oculta" }) };
        if (p.estado) f.estado = p.estado;
        if (p.nombre !== undefined) f.nombre = String(p.nombre).slice(0, 80);
        if (p.aviso !== undefined) f.aviso = p.aviso ? String(p.aviso).slice(0, 300) : undefined;
        if (p.programar) {
          if (!["oculta", "gratis", "pro"].includes(p.programar.estado)) throw new Error("estado programado no válido");
          f.programado = [...(f.programado ?? []), { estado: p.programar.estado, desde: Number(p.programar.desde) }];
        }
        if (p.quitarProgramado) f.programado = [];
        return { ...actual, revision: (actual.revision ?? 1) + 1, funciones: { ...actual.funciones, [p.id]: f } };
      });
      return responder(res, 200, { ok: true, catalogo: c });
    }
    if (ruta === "version" && req.method === "POST") {
      const p = await cuerpo(req);
      if (!Number.isInteger(p.versionCode) || !p.url || !/^[0-9a-f]{64}$/.test(p.sha256 ?? "")) return responder(res, 400, { error: "faltan_datos" });
      if (!String(p.url).startsWith("https://")) return responder(res, 400, { error: "la_url_tiene_que_ser_https" });
      const v = { versionCode: p.versionCode, versionName: String(p.versionName ?? ""), url: p.url, sha256: p.sha256, tamano: p.tamano ?? null,
        notas: String(p.notas ?? "").slice(0, 2000), minimo: Number.isInteger(p.minimo) ? p.minimo : null };
      almacen.guardar("version", v);
      return responder(res, 200, { ok: true, version: v });
    }
    return responder(res, 404, { error: "no_existe" });
  }

  /** La pregunta a Claude con la clave de aquí; la respuesta (también en streaming) se pasa tal cual. */
  async function aClaude(pedido, req, res) {
    const r = await fetch(`${cfg.anthropic}/v1/messages`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-api-key": cfg.claudeKey, "anthropic-version": req.headers["anthropic-version"] ?? "2023-06-01" },
      body: JSON.stringify(pedido),
    });
    res.writeHead(r.status, { "content-type": r.headers.get("content-type") ?? "application/json" });
    if (!r.body) return res.end();
    for await (const trozo of r.body) res.write(trozo);
    res.end();
  }

  return {
    servidor: http.createServer((req, res) => manejar(req, res).catch((e) => {
      if (!res.headersSent) responder(res, e?.status ?? 500, { error: e?.status ? e.message : "fallo" });
      else res.end();
      if (!e?.status) console.error(e);
    })),
    publica,
    almacen,
  };
}

// ---------------------------------------------------------------- utilidades

function seguridad(res) {
  res.setHeader("x-content-type-options", "nosniff");
  res.setHeader("x-frame-options", "DENY");
  res.setHeader("referrer-policy", "no-referrer");
  res.setHeader("strict-transport-security", "max-age=31536000");
  res.setHeader("cache-control", "no-store");
}

function responder(res, estado, cuerpo) {
  if (cuerpo === null) { res.writeHead(estado); return res.end(); }
  res.writeHead(estado, { "content-type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(cuerpo));
}

function cuerpo(req, tope = 64_000) {
  return new Promise((ok, mal) => {
    let n = 0; const trozos = [];
    req.on("data", (t) => {
      n += t.length;
      if (n > tope) { mal(Object.assign(new Error("demasiado_grande"), { status: 413 })); req.destroy(); return; }
      trozos.push(t);
    });
    req.on("end", () => { try { ok(trozos.length ? JSON.parse(Buffer.concat(trozos).toString("utf8")) : {}); } catch { mal(Object.assign(new Error("json_no_valido"), { status: 400 })); } });
    req.on("error", mal);
  });
}

/** Si [clave] pidió más de [maximo] veces en [ventana] ms. */
function demasiados(mapa, clave, t, maximo, ventana) {
  const lista = (mapa.get(clave) ?? []).filter((x) => x > t - ventana);
  lista.push(t); mapa.set(clave, lista);
  return lista.length > maximo;
}

// Arrancar (node src/servidor.mjs), no al importarlo en las pruebas.
if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const cfg = configDesdeEntorno();
  const { servidor, publica } = crearServidor(cfg);
  servidor.listen(cfg.puerto, cfg.host, () => console.log(`PinDesk servidor en http://${cfg.host}:${cfg.puerto} · clave pública ${publica}`));
}
