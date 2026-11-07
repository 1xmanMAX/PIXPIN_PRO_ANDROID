// **Las firmas del servidor de PinDesk** (7-nov-2026). Como un banco: lo que decide qué puede usar cada persona (el
// catálogo de funciones, su licencia, la versión de la app) sale firmado con una clave privada que SOLO está en el
// servidor (ECDSA P-256 con SHA-256). La app lleva la clave pública y comprueba la firma: si alguien cambia una
// coma por el camino, o se inventa una licencia, la firma no cuadra y la app no la cree.
//
// Un documento firmado viaja así:  {"datos": "<JSON en base64url>", "firma": "<firma DER en base64url>", "clave": "<id>"}
// Se firma el texto exacto de "datos" (los bytes del base64url), así nadie depende de cómo se ordena un JSON.
//
// Las sesiones (lo que prueba que una petición es de quien dice) van con HMAC-SHA256 y un secreto del servidor.

import crypto from "node:crypto";

export const b64url = (buf) => Buffer.from(buf).toString("base64url");
export const deB64url = (s) => Buffer.from(String(s), "base64url");

/** Una clave privada ECDSA P-256 en PEM (PKCS#8) y su pública (SPKI), nuevas. */
export function clavesNuevas() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
  return {
    privada: privateKey.export({ type: "pkcs8", format: "pem" }),
    publica: publicKey.export({ type: "spki", format: "der" }).toString("base64"),
  };
}

/** La clave pública (SPKI DER en base64) de una privada PEM: la que se pone en la app. */
export function publicaDe(privadaPem) {
  return crypto.createPublicKey(privadaPem).export({ type: "spki", format: "der" }).toString("base64");
}

/** El id corto de una clave pública: así la app sabe con cuál se firmó (para cambiarla algún día). */
export function idDeClave(publicaB64) {
  return crypto.createHash("sha256").update(Buffer.from(publicaB64, "base64")).digest("hex").slice(0, 12);
}

export function firmar(objeto, privadaPem) {
  const datos = b64url(JSON.stringify(objeto));
  const firma = crypto.sign("sha256", Buffer.from(datos), privadaPem); // DER: lo que lee SHA256withECDSA en Java
  return { datos, firma: b64url(firma), clave: idDeClave(publicaDe(privadaPem)) };
}

/** Comprueba un documento firmado con la pública (SPKI base64); devuelve el objeto o null. Es lo que hace la app. */
export function comprobar(doc, publicaB64) {
  try {
    const publica = crypto.createPublicKey({ key: Buffer.from(publicaB64, "base64"), format: "der", type: "spki" });
    if (!crypto.verify("sha256", Buffer.from(doc.datos), publica, deB64url(doc.firma))) return null;
    return JSON.parse(deB64url(doc.datos).toString("utf8"));
  } catch { return null; }
}

// ---------------------------------------------------------------- sesiones

export function sesionNueva(datos, secreto) {
  const cuerpo = b64url(JSON.stringify(datos));
  const mac = crypto.createHmac("sha256", secreto).update(cuerpo).digest();
  return `${cuerpo}.${b64url(mac)}`;
}

/** Los datos de una sesión si su HMAC cuadra y no ha caducado ([x] en ms); si no, null. Comparación en tiempo fijo. */
export function leerSesion(token, secreto, ahora = Date.now()) {
  const [cuerpo, mac] = String(token || "").split(".");
  if (!cuerpo || !mac) return null;
  const esperado = crypto.createHmac("sha256", secreto).update(cuerpo).digest();
  const dado = deB64url(mac);
  if (dado.length !== esperado.length || !crypto.timingSafeEqual(dado, esperado)) return null;
  try {
    const d = JSON.parse(deB64url(cuerpo).toString("utf8"));
    return d.x && d.x > ahora ? d : null;
  } catch { return null; }
}

/** Compara dos textos sin dar pistas por el tiempo que tarda (para la clave del panel). */
export function iguales(a, b) {
  const x = crypto.createHash("sha256").update(String(a)).digest();
  const y = crypto.createHash("sha256").update(String(b)).digest();
  return crypto.timingSafeEqual(x, y);
}

// ---------------------------------------------------------------- el código de 6 cifras del panel (TOTP, RFC 6238)

const BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
export function deBase32(s) {
  const limpio = String(s).toUpperCase().replace(/[^A-Z2-7]/g, "");
  let bits = 0, valor = 0; const salida = [];
  for (const c of limpio) {
    valor = (valor << 5) | BASE32.indexOf(c); bits += 5;
    if (bits >= 8) { salida.push((valor >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(salida);
}
export function base32Nuevo(bytes = 20) {
  const b = crypto.randomBytes(bytes); let bits = 0, valor = 0, s = "";
  for (const x of b) { valor = (valor << 8) | x; bits += 8; while (bits >= 5) { s += BASE32[(valor >>> (bits - 5)) & 31]; bits -= 5; } }
  if (bits > 0) s += BASE32[(valor << (5 - bits)) & 31];
  return s;
}
export function totp(secretoB32, ahora = Date.now(), paso = 30) {
  const contador = Buffer.alloc(8);
  contador.writeBigUInt64BE(BigInt(Math.floor(ahora / 1000 / paso)));
  const h = crypto.createHmac("sha1", deBase32(secretoB32)).update(contador).digest();
  const o = h[h.length - 1] & 0xf;
  const n = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(n % 1_000_000).padStart(6, "0");
}
/** Vale el código de ahora y el de 30 s antes o después (relojes que no van exactos). */
export function totpValido(secretoB32, codigo, ahora = Date.now()) {
  return [-1, 0, 1].some((d) => iguales(totp(secretoB32, ahora + d * 30_000), String(codigo || "").trim()));
}
