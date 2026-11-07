// Prueba del servidor sin Cloudflare: Node 18+ trae fetch, Request, Response y WebCrypto.
import servidor from "../src/index.js";
const kv = new Map();
const env = { CLAUDE_API_KEY: "sk-falsa", PERMITIDOS: "ana@ejemplo.com, LUIS@ejemplo.com", GOOGLE_CLIENT_IDS: "web.apps", SECRETO_DE_SESION: "una frase larga al azar", CUPO_DIARIO: "2",
  CUPOS: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => { kv.set(k, v); } } };
let llamadas = [];
globalThis.fetch = async (u, o) => { llamadas.push([String(u), o && o.headers && o.headers.get("x-api-key")]); return new Response(JSON.stringify({ ok: true }), { status: 200 }); };
// Una sesión firmada como la de /entrar (sin Google): se arma con la misma firma.
const firmar = async (datos) => {
  const b = (u8) => Buffer.from(u8).toString("base64url");
  const cuerpo = b(new TextEncoder().encode(JSON.stringify(datos)));
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.SECRETO_DE_SESION), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return cuerpo + "." + b(new Uint8Array(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(cuerpo))));
};
const pedir = (ruta, sesion, metodo = "GET") => servidor.fetch(new Request("https://x" + ruta, { method: metodo, headers: sesion ? { Authorization: "Bearer " + sesion } : {}, body: metodo === "POST" ? "{}" : undefined }), env);
const assert = (c, m) => { if (!c) { console.error("FALLA: " + m); process.exit(1); } else console.log("ok  " + m); };
const ana = await firmar({ e: "ana@ejemplo.com", x: Date.now() + 1e6 });
assert((await pedir("/yo")).status === 401, "sin sesión, 401");
assert((await (await pedir("/yo", ana)).json()).email === "ana@ejemplo.com", "/yo dice quién es");
assert((await pedir("/v1/messages", ana, "POST")).status === 200, "pregunta 1 pasa");
assert(llamadas[0][0] === "https://api.anthropic.com/v1/messages" && llamadas[0][1] === "sk-falsa", "va a Claude con la clave del servidor");
assert((await pedir("/v1/messages", ana, "POST")).status === 200, "pregunta 2 pasa");
assert((await pedir("/v1/messages", ana, "POST")).status === 429, "la 3 supera el cupo");
const otro = await firmar({ e: "otro@ejemplo.com", x: Date.now() + 1e6 });
assert((await pedir("/v1/messages", otro, "POST")).status === 403, "fuera de la lista, 403");
const caducada = await firmar({ e: "ana@ejemplo.com", x: Date.now() - 1 });
assert((await pedir("/yo", caducada)).status === 401, "caducada, 401");
assert((await pedir("/yo", ana.slice(0, -2) + "xx")).status === 401, "firma tocada, 401");
env.PERMITIDOS = "luis@ejemplo.com";
assert((await pedir("/yo", ana)).status === 403, "quitada de la lista: sin acceso al momento");
assert((await pedir("/entrar", null, "POST")).status === 400, "entrar sin idToken, 400");
console.log("TODO BIEN");
