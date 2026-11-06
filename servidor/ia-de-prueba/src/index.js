// **El servidor de la IA de la fase de prueba de PinDesk** (6-nov-2026).
//
// La clave de Claude vive solo aquí (secreto CLAUDE_API_KEY): las apps no la llevan. Quien quiere usar el asistente
// entra con su cuenta de Google; si su correo está en la lista (secreto PERMITIDOS) recibe una sesión, y con ella sus
// preguntas pasan por aquí hacia Claude. Quitar un correo de la lista lo deja sin acceso en la siguiente pregunta.
//
// Rutas:
//   POST /entrar   {"idToken": "<el de Google>"}  ->  {"sesion", "email", "dias"}  |  403 {"error":"sin_acceso"}
//   GET  /yo       (Authorization: Bearer <sesion>) ->  {"email", "quedan"}
//   *    /v1/...   (Authorization: Bearer <sesion>) ->  lo mismo que api.anthropic.com/v1/..., con la clave de aquí
//
// Secretos (npx wrangler secret put NOMBRE): CLAUDE_API_KEY, PERMITIDOS (correos separados por comas),
// GOOGLE_CLIENT_IDS (los ID de cliente OAuth que valen: el «web» que usa Android y el de escritorio de Windows,
// separados por comas) y SECRETO_DE_SESION (una frase larga al azar).

const ANTHROPIC = "https://api.anthropic.com";
const VERSION_ANTHROPIC = "2023-06-01";
const CERTS_DE_GOOGLE = "https://www.googleapis.com/oauth2/v3/certs";

export default {
  async fetch(peticion, env) {
    const url = new URL(peticion.url);
    try {
      if (peticion.method === "OPTIONS") return new Response(null, { status: 204, headers: cors() });
      if (url.pathname === "/entrar" && peticion.method === "POST") return await entrar(peticion, env);
      const quien = await sesionDe(peticion, env);
      if (!quien) return json({ error: "sin_sesion" }, 401);
      if (!permitido(env, quien.e)) return json({ error: "sin_acceso" }, 403);
      if (url.pathname === "/yo") return json({ email: quien.e, quedan: await quedan(env, quien.e) });
      if (url.pathname.startsWith("/v1/")) return await aClaude(peticion, env, url, quien.e);
      return json({ error: "no_existe" }, 404);
    } catch (e) {
      return json({ error: "fallo", detalle: String(e && e.message || e).slice(0, 200) }, 500);
    }
  },
};

// ---------------------------------------------------------------- entrar con Google

async function entrar(peticion, env) {
  const { idToken } = await peticion.json().catch(() => ({}));
  if (!idToken) return json({ error: "falta_idToken" }, 400);
  const datos = await verificarGoogle(idToken, env);
  if (!datos) return json({ error: "token_no_valido" }, 401);
  const email = String(datos.email || "").toLowerCase();
  if (!permitido(env, email)) return json({ error: "sin_acceso", email }, 403);
  const dias = parseInt(env.DIAS_DE_SESION || "30", 10);
  const sesion = await firmar(env, { e: email, x: Date.now() + dias * 86400000 });
  return json({ sesion, email, dias });
}

/** El id_token de Google: firma RS256 con sus certificados, emisor, destinatario, caducidad y correo verificado. */
async function verificarGoogle(token, env) {
  const partes = token.split(".");
  if (partes.length !== 3) return null;
  const cabecera = JSON.parse(texto(b64url(partes[0])));
  const datos = JSON.parse(texto(b64url(partes[1])));
  const certs = await (await fetch(CERTS_DE_GOOGLE, { cf: { cacheTtl: 3600 } })).json();
  const jwk = (certs.keys || []).find((k) => k.kid === cabecera.kid);
  if (!jwk || cabecera.alg !== "RS256") return null;
  const llave = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const bien = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", llave, b64url(partes[2]), new TextEncoder().encode(partes[0] + "." + partes[1]));
  if (!bien) return null;
  const clientes = String(env.GOOGLE_CLIENT_IDS || "").split(",").map((s) => s.trim()).filter(Boolean);
  if (!["accounts.google.com", "https://accounts.google.com"].includes(datos.iss)) return null;
  if (!clientes.includes(datos.aud)) return null;
  if (!datos.exp || datos.exp * 1000 < Date.now()) return null;
  if (datos.email_verified !== true && datos.email_verified !== "true") return null;
  return datos;
}

function permitido(env, email) {
  const lista = String(env.PERMITIDOS || "").toLowerCase().split(",").map((s) => s.trim()).filter(Boolean);
  return lista.includes(String(email || "").toLowerCase());
}

// ---------------------------------------------------------------- la sesión (firmada con HMAC)

async function llaveDeSesion(env) {
  if (!env.SECRETO_DE_SESION) throw new Error("falta SECRETO_DE_SESION");
  return crypto.subtle.importKey("raw", new TextEncoder().encode(env.SECRETO_DE_SESION), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

async function firmar(env, datos) {
  const cuerpo = aB64url(new TextEncoder().encode(JSON.stringify(datos)));
  const firma = await crypto.subtle.sign("HMAC", await llaveDeSesion(env), new TextEncoder().encode(cuerpo));
  return cuerpo + "." + aB64url(new Uint8Array(firma));
}

async function sesionDe(peticion, env) {
  const a = peticion.headers.get("Authorization") || "";
  const t = a.startsWith("Bearer ") ? a.slice(7).trim() : "";
  const [cuerpo, firma] = t.split(".");
  if (!cuerpo || !firma) return null;
  const bien = await crypto.subtle.verify("HMAC", await llaveDeSesion(env), b64url(firma), new TextEncoder().encode(cuerpo));
  if (!bien) return null;
  const datos = JSON.parse(texto(b64url(cuerpo)));
  if (!datos.e || !datos.x || datos.x < Date.now()) return null;
  return datos;
}

// ---------------------------------------------------------------- hacia Claude, con cupo

async function quedan(env, email) {
  if (!env.CUPOS) return null;
  const n = parseInt((await env.CUPOS.get(claveDelDia(email))) || "0", 10);
  return Math.max(0, parseInt(env.CUPO_DIARIO || "200", 10) - n);
}

function claveDelDia(email) {
  return email + ":" + new Date().toISOString().slice(0, 10);
}

async function aClaude(peticion, env, url, email) {
  if (!env.CLAUDE_API_KEY) return json({ error: "falta_la_clave_del_servidor" }, 500);
  // Lo que cuenta para el cupo: las preguntas (POST a /v1/messages), no mirar los modelos.
  if (env.CUPOS && peticion.method === "POST") {
    const k = claveDelDia(email);
    const n = parseInt((await env.CUPOS.get(k)) || "0", 10);
    if (n >= parseInt(env.CUPO_DIARIO || "200", 10)) return json({ type: "error", error: { type: "rate_limit_error", message: "Cupo de hoy agotado en la fase de prueba" } }, 429);
    await env.CUPOS.put(k, String(n + 1), { expirationTtl: 2 * 86400 });
  }
  const cabeceras = new Headers();
  cabeceras.set("x-api-key", env.CLAUDE_API_KEY);
  cabeceras.set("anthropic-version", peticion.headers.get("anthropic-version") || VERSION_ANTHROPIC);
  const beta = peticion.headers.get("anthropic-beta");
  if (beta) cabeceras.set("anthropic-beta", beta);
  if (peticion.method !== "GET") cabeceras.set("content-type", "application/json");
  const r = await fetch(ANTHROPIC + url.pathname + url.search, {
    method: peticion.method,
    headers: cabeceras,
    body: peticion.method === "GET" ? undefined : peticion.body,
  });
  // La respuesta tal cual, también la que llega a trozos (stream).
  const h = new Headers(r.headers);
  for (const [k, v] of Object.entries(cors())) h.set(k, v);
  return new Response(r.body, { status: r.status, headers: h });
}

// ---------------------------------------------------------------- utilidades

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json", ...cors() } });
}
function cors() {
  return { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, content-type, anthropic-version, anthropic-beta" };
}
function b64url(s) {
  const b = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
}
function aB64url(bytes) {
  let s = "";
  for (const x of bytes) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function texto(bytes) {
  return new TextDecoder().decode(bytes);
}
