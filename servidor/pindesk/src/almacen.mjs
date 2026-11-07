// **Dónde se guardan los datos**: un archivo JSON por cosa (usuarios, catálogo, versión, cupos) en la carpeta DATOS.
// Para cientos o miles de usuarios sobra y no hay base de datos que mantener. Se escribe en un archivo aparte y se
// renombra encima (atómico): si se va la luz a mitad, queda el de antes entero, nunca uno a medias. Una copia del
// anterior se queda como `.anterior` por si hay que volver atrás a mano.

import fs from "node:fs";
import path from "node:path";

export class Almacen {
  constructor(carpeta) {
    this.carpeta = carpeta;
    fs.mkdirSync(carpeta, { recursive: true, mode: 0o700 });
    this.cache = new Map();
  }
  archivo(nombre) { return path.join(this.carpeta, `${nombre}.json`); }
  leer(nombre) {
    if (this.cache.has(nombre)) return this.cache.get(nombre);
    const a = this.archivo(nombre);
    const v = fs.existsSync(a) ? JSON.parse(fs.readFileSync(a, "utf8")) : null;
    this.cache.set(nombre, v);
    return v;
  }
  guardar(nombre, valor) {
    const a = this.archivo(nombre);
    const tmp = `${a}.${process.pid}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(valor, null, 1), { mode: 0o600 });
    if (fs.existsSync(a)) fs.copyFileSync(a, `${a}.anterior`);
    fs.renameSync(tmp, a);
    this.cache.set(nombre, valor);
    return valor;
  }
  /** Lee, cambia y guarda de una vez (Node es de un solo hilo: no se pisan dos cambios). */
  cambiar(nombre, f) { return this.guardar(nombre, f(this.leer(nombre))); }
}
