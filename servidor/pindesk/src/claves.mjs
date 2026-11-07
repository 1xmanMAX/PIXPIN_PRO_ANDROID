// **Prepara las claves del servidor** (una vez, en la VPS):  node src/claves.mjs /etc/pindesk
//   - firma.pem: la clave PRIVADA que firma catálogos y licencias. No sale nunca de la VPS (permisos 600).
//   - secretos.env: SECRETO_SESION, ADMIN_CLAVE y ADMIN_TOTP al azar, para el archivo de entorno del servicio.
//   - Imprime la clave PÚBLICA (va en la app) y el secreto del código de 6 cifras (para Google Authenticator).
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { clavesNuevas, publicaDe, base32Nuevo } from "./firma.mjs";

const dir = process.argv[2] || ".";
fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
const pem = path.join(dir, "firma.pem");
if (!fs.existsSync(pem)) fs.writeFileSync(pem, clavesNuevas().privada, { mode: 0o600 });
const publica = publicaDe(fs.readFileSync(pem, "utf8"));
const env = path.join(dir, "secretos.env");
let totp = null;
if (!fs.existsSync(env)) {
  totp = base32Nuevo();
  const clave = crypto.randomBytes(18).toString("base64url");
  fs.writeFileSync(env, [
    `SECRETO_SESION=${crypto.randomBytes(48).toString("base64url")}`,
    `ADMIN_CLAVE=${clave}`,
    `ADMIN_TOTP=${totp}`,
    "",
  ].join("\n"), { mode: 0o600 });
  console.log(`Clave del panel: ${clave}`);
  console.log(`Código de 6 cifras: añade en Google Authenticator la clave ${totp}`);
  console.log(`  (o este enlace: otpauth://totp/PinDesk:panel?secret=${totp}&issuer=PinDesk)`);
}
console.log(`Clave PÚBLICA para la app (FIRMA_PUBLICA): ${publica}`);
