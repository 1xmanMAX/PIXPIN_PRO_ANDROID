// Pruebas del servidor de PinDesk:  node --test prueba/
// Un Google de mentira (sus certificados) y un Claude de mentira, en puertos locales.
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { crearServidor } from "../src/servidor.mjs";
import { clavesNuevas, comprobar, firmar, totp, b64url, deB64url } from "../src/firma.mjs";

const ID_CLIENTE = "web-123.apps.googleusercontent.com";
const google = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...google.publicKey.export({ format: "jwk" }), kid: "k1", alg: "RS256", use: "sig" };

function idToken(datos) {
  const cab = b64url(JSON.stringify({ alg: "RS256", kid: "k1", typ: "JWT" }));
  const cuerpo = b64url(JSON.stringify({ iss: "https://accounts.google.com", aud: ID_CLIENTE, exp: Math.floor(Date.now() / 1000) + 600, email_verified: true, ...datos }));
  return `${cab}.${cuerpo}.${b64url(crypto.sign("sha256", Buffer.from(`${cab}.${cuerpo}`), google.privateKey))}`;
}

function escuchar(s) { return new Promise((ok) => s.listen(0, "127.0.0.1", () => ok(`http://127.0.0.1:${s.address().port}`))); }

async function montar(extra = {}) {
  const certs = await escuchar(http.createServer((q, r) => { r.writeHead(200, { "content-type": "application/json", "cache-control": "max-age=60" }); r.end(JSON.stringify({ keys: [jwk] })); }));
  let pedidos = [];
  const claude = await escuchar(http.createServer((q, r) => {
    let b = ""; q.on("data", (t) => (b += t)); q.on("end", () => { pedidos.push({ clave: q.headers["x-api-key"], cuerpo: JSON.parse(b) }); r.writeHead(200, { "content-type": "application/json" }); r.end(JSON.stringify({ content: [{ type: "text", text: "hola" }] })); });
  }));
  const datos = fs.mkdtempSync(path.join(os.tmpdir(), "pindesk-"));
  const { privada, publica } = clavesNuevas();
  let t = Date.now();
  const cfg = {
    puerto: 0, host: "127.0.0.1", datos, clavePrivada: privada, secretoSesion: "x".repeat(40),
    googleClientIds: [ID_CLIENTE], googleCerts: certs, claudeKey: "sk-del-servidor", anthropic: claude,
    adminClave: "clave-del-panel", adminTotp: "JBSWY3DPEHPK3PXP", adminCorreos: [],
    diasDePrueba: 30, diasDeSesion: 90, diasDeLicencia: 7, cupoGratis: 2, cupoPro: 5, modelosPermitidos: ["claude-sonnet-5-5"], maxTokens: 1000, ...extra,
  };
  const { servidor } = crearServidor(cfg, () => t);
  const base = await escuchar(servidor);
  const pedir = async (ruta, { metodo = "GET", cuerpo, sesion, admin } = {}) => {
    const h = { "content-type": "application/json" };
    if (sesion) h.authorization = `Bearer ${sesion}`;
    if (admin) { h["x-admin-clave"] = admin.clave ?? cfg.adminClave; h["x-admin-codigo"] = admin.codigo ?? totp(cfg.adminTotp, t); }
    const r = await fetch(base + ruta, { method: metodo, headers: h, body: cuerpo ? JSON.stringify(cuerpo) : undefined });
    return { estado: r.status, json: await r.json().catch(() => null) };
  };
  return { pedir, publica, pasar: (ms) => (t += ms), ahora: () => t, pedidos: () => pedidos, cfg };
}

const datosDe = (doc) => JSON.parse(deB64url(doc.datos).toString("utf8"));

test("el catálogo sale firmado y la firma no se puede tocar", async () => {
  const s = await montar();
  const { json } = await s.pedir("/v1/catalogo");
  const c = comprobar(json, s.publica);
  assert.equal(c.tipo, "catalogo");
  assert.equal(c.funciones.visores.estado, "gratis");
  assert.equal(c.funciones["riel.lapiz"].estado, "oculta");
  // El «hombre en el medio» cambia lápiz a gratis: la firma ya no cuadra.
  const tocado = JSON.parse(deB64url(json.datos)); tocado.funciones["riel.lapiz"].estado = "gratis";
  assert.equal(comprobar({ ...json, datos: b64url(JSON.stringify(tocado)) }, s.publica), null);
  // Y firmado con otra clave (la suya) tampoco vale.
  assert.equal(comprobar(firmar(tocado, clavesNuevas().privada), s.publica), null);
});

test("entrar con Google: 30 días de prueba, licencia firmada; un token falso o de otra app no entra", async () => {
  const s = await montar();
  const r = await s.pedir("/v1/entrar", { metodo: "POST", cuerpo: { idToken: idToken({ sub: "111", email: "Ana@Gmail.com", name: "Ana" }) } });
  assert.equal(r.estado, 200);
  assert.equal(r.json.nueva, true);
  const lic = comprobar(r.json.licencia, s.publica);
  assert.equal(lic.plan, "prueba"); assert.equal(lic.cuenta, "ana@gmail.com");
  assert.equal(lic.hasta - s.ahora(), 30 * 86_400_000);
  // Otra app (otro destinatario), caducado o firmado por otro: fuera.
  assert.equal((await s.pedir("/v1/entrar", { metodo: "POST", cuerpo: { idToken: idToken({ sub: "1", email: "a@b.c", aud: "otra-app" }) } })).estado, 401);
  assert.equal((await s.pedir("/v1/entrar", { metodo: "POST", cuerpo: { idToken: idToken({ sub: "1", email: "a@b.c", exp: 1 }) } })).estado, 401);
  const otro = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const [c, d] = idToken({ sub: "1", email: "a@b.c" }).split(".");
  const falso = `${c}.${d}.${b64url(crypto.sign("sha256", Buffer.from(`${c}.${d}`), otro.privateKey))}`;
  assert.equal((await s.pedir("/v1/entrar", { metodo: "POST", cuerpo: { idToken: falso } })).estado, 401);
  // Sin abrir la app en 90 días, la sesión caduca; usándola, se renueva sola.
  // Una sesión inventada o tocada no vale.
  assert.equal((await s.pedir("/v1/estado", { sesion: r.json.sesion + "x" })).estado, 401);
  assert.equal((await s.pedir("/v1/estado", { sesion: r.json.sesion })).estado, 200);
});

test("al acabar la prueba vuelve a gratis; el panel da Pro y regala funciones", async () => {
  const s = await montar();
  const r = await s.pedir("/v1/entrar", { metodo: "POST", cuerpo: { idToken: idToken({ sub: "222", email: "luis@x.pe" }) } });
  s.pasar(31 * 86_400_000);
  let e = await s.pedir("/v1/estado", { sesion: r.json.sesion });
  assert.equal(comprobar(e.json.licencia, s.publica).plan, "gratis");
  const uid = comprobar(e.json.licencia, s.publica).uid;
  // Sin la clave, o sin el código de 6 cifras bueno, el panel no hace nada.
  assert.equal((await s.pedir("/admin/api/resumen", { admin: { clave: "mala" } })).estado, 401);
  assert.equal((await s.pedir("/admin/api/resumen", { admin: { codigo: "000000" } })).estado, 401);
  assert.equal((await s.pedir("/admin/api/usuario", { metodo: "POST", admin: {}, cuerpo: { uid, proDias: 30, regaladas: ["riel.lapiz"] } })).estado, 200);
  e = await s.pedir("/v1/estado", { sesion: r.json.sesion });
  const lic = comprobar(e.json.licencia, s.publica);
  assert.equal(lic.plan, "pro"); assert.deepEqual(lic.regaladas, ["riel.lapiz"]);
  // Bloquear corta sus sesiones al momento.
  await s.pedir("/admin/api/usuario", { metodo: "POST", admin: {}, cuerpo: { uid, bloqueada: true } });
  s.pasar(1000);
  assert.equal((await s.pedir("/v1/estado", { sesion: r.json.sesion })).estado, 401);
});

test("abrir una función y programarla como Pro para una fecha, sin publicar la app", async () => {
  const s = await montar();
  await s.pedir("/admin/api/funcion", { metodo: "POST", admin: {}, cuerpo: { id: "riel.marcador", estado: "gratis" } });
  const desde = s.ahora() + 7 * 86_400_000;
  await s.pedir("/admin/api/funcion", { metodo: "POST", admin: {}, cuerpo: { id: "riel.marcador", programar: { estado: "pro", desde }, aviso: "Desde el 14 será parte de Pro" } });
  let c = comprobar((await s.pedir("/v1/catalogo")).json, s.publica);
  assert.equal(c.funciones["riel.marcador"].estado, "gratis");
  assert.equal(c.funciones["riel.marcador"].proximo.estado, "pro");
  s.pasar(8 * 86_400_000);
  c = comprobar((await s.pedir("/v1/catalogo")).json, s.publica);
  assert.equal(c.funciones["riel.marcador"].estado, "pro");
});

test("el asistente: con la clave del servidor, el modelo de la lista y un cupo por día", async () => {
  const s = await montar();
  const r = await s.pedir("/v1/entrar", { metodo: "POST", cuerpo: { idToken: idToken({ sub: "333", email: "eva@x.pe" }) } });
  s.pasar(31 * 86_400_000); // ya en gratis: cupo 2
  const preguntar = () => s.pedir("/v1/messages", { metodo: "POST", sesion: r.json.sesion, cuerpo: { model: "claude-otro-caro", max_tokens: 99999, messages: [{ role: "user", content: "hola" }] } });
  assert.equal((await preguntar()).estado, 200);
  assert.equal((await preguntar()).estado, 200);
  assert.equal((await preguntar()).estado, 429);
  const p = s.pedidos()[0];
  assert.equal(p.clave, "sk-del-servidor");
  assert.equal(p.cuerpo.model, "claude-sonnet-5-5");
  assert.equal(p.cuerpo.max_tokens, 1000);
  // Al día siguiente, cupo nuevo. Sin sesión, nada.
  s.pasar(86_400_000);
  assert.equal((await preguntar()).estado, 200);
  assert.equal((await s.pedir("/v1/messages", { metodo: "POST", cuerpo: {} })).estado, 401);
  // Con el asistente oculto en el catálogo, no se puede aunque se tenga sesión.
  await s.pedir("/admin/api/funcion", { metodo: "POST", admin: {}, cuerpo: { id: "asistente", estado: "oculta" } });
  assert.equal((await preguntar()).estado, 403);
});

test("la versión de la app sale firmada (con la huella del APK) y solo con https", async () => {
  const s = await montar();
  assert.equal((await s.pedir("/admin/api/version", { metodo: "POST", admin: {}, cuerpo: { versionCode: 300, url: "http://x/a.apk", sha256: "a".repeat(64) } })).estado, 400);
  await s.pedir("/admin/api/version", { metodo: "POST", admin: {}, cuerpo: { versionCode: 300, versionName: "1.0.0", url: "https://x/a.apk", sha256: "a".repeat(64), notas: "Nuevo", minimo: 280 } });
  const v = comprobar((await s.pedir("/v1/version")).json, s.publica);
  assert.equal(v.versionCode, 300); assert.equal(v.minimo, 280); assert.equal(v.sha256, "a".repeat(64));
});

test("frena a quien prueba a entrar muchas veces", async () => {
  const s = await montar();
  let ultimo;
  for (let i = 0; i < 22; i++) ultimo = await s.pedir("/v1/entrar", { metodo: "POST", cuerpo: { idToken: "x" } });
  assert.equal(ultimo.estado, 429);
});
