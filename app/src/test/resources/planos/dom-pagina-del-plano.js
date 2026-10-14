// Corre el guion de la página web de un plano (PaginaDelPlano) en node, con un DOM de mentira, y
// cuenta lo que pinta: cuántas rayas, rellenos, arcos y letras pide al lienzo, con todas las
// capas, sin ninguna y con las de la página al abrir. Uso: node dom-pagina-del-plano.js pagina.html
// Saca una línea JSON con las cuentas. Lo usa PaginaDelPlanoTest.
"use strict";
const fs = require('fs');
const html = fs.readFileSync(process.argv[2], 'utf8');
function trozo(id) {
  const m = html.match(new RegExp('<script id="' + id + '"[^>]*>([\\s\\S]*?)</script>'));
  if (!m) throw new Error('falta ' + id);
  return m[1];
}
const guion = html.match(/<script>\n([\s\S]*?)\n<\/script>/)[1];
const cuentas = { stroke: 0, fill: 0, moveTo: 0, lineTo: 0, arc: 0, addPath: 0, drawImage: 0, errores: [] };
function Ctx() {
  return new Proxy({}, {
    get(o, k) {
      if (k in o) return o[k];
      if (k === 'measureText') return () => ({ width: 10 });
      if (k === 'stroke' || k === 'fill' || k === 'drawImage') return () => { cuentas[k]++; };
      return () => {};
    },
    set(o, k, v) { o[k] = v; return true; }
  });
}
class Path2D {
  moveTo() { cuentas.moveTo++; } lineTo() { cuentas.lineTo++; } closePath() {}
  arc() { cuentas.arc++; } addPath() { cuentas.addPath++; }
}
class DOMMatrix { constructor(m) { this.m = m; } }
const elementos = {};
function El(id) {
  const e = {
    id, hidden: false, textContent: '', style: {}, children: [], className: '', checked: false, oyentes: {},
    classList: { _s: new Set(), add(x) { this._s.add(x); }, remove(x) { this._s.delete(x); }, toggle(x, on) { if (on) this._s.add(x); else this._s.delete(x); } },
    getContext() { return Ctx(); },
    getBoundingClientRect() { return { left: 0, top: 44, width: 1000, height: 700 }; },
    addEventListener(t, f) { this.oyentes[t] = f; },
    setPointerCapture() {},
    appendChild(h) { this.children.push(h); return h; },
    setAttribute(k, v) { this['@' + k] = v; },
    querySelector(s) { return s === 'svg' && /<div id="anot"><svg/.test(html) ? El('svg') : null; },
    click() { if (this.oyentes.click) this.oyentes.click(); },
  };
  Object.defineProperty(e, 'textContent', {
    get() { return e._t !== undefined ? e._t : (id === 'datos' ? trozo('datos') : id === 'plano-gz' ? trozo('plano-gz') : ''); },
    set(v) { e._t = v; e.children = []; }
  });
  return e;
}
const body = El('body');
global.document = {
  body,
  getElementById(id) { return elementos[id] || (elementos[id] = (new RegExp('id="' + id + '"').test(html) ? El(id) : null)); },
  createElement(t) { return El(t); },
  addEventListener() {},
};
global.window = { devicePixelRatio: 2, addEventListener() {} };
global.Path2D = Path2D;
global.DOMMatrix = DOMMatrix;
global.Image = class { set src(v) { this._s = v; } };
let marcos = [];
global.requestAnimationFrame = f => { marcos.push(f); };
function pintarYa() { const m = marcos; marcos = []; m.forEach(f => f()); }
function cero() { for (const k of ['stroke', 'fill', 'moveTo', 'lineTo', 'arc', 'addPath', 'drawImage']) cuentas[k] = 0; }
try { eval(guion); } catch (e) { cuentas.errores.push(String(e && e.stack || e)); }
(async () => {
  for (let i = 0; i < 200 && body['@data-listo'] !== '1'; i++) await new Promise(r => setTimeout(r, 10));
  const salida = { listo: body['@data-listo'] === '1' };
  cero(); pintarYa();
  salida.alAbrir = { stroke: cuentas.stroke, fill: cuentas.fill, arc: cuentas.arc, lineTo: cuentas.lineTo, addPath: cuentas.addPath };
  const panel = document.getElementById('panel');
  const fila = panel.children[0];
  // «Ninguna»: no se pinta nada del plano.
  fila.children[1].onclick(); cero(); pintarYa();
  salida.ninguna = { stroke: cuentas.stroke, fill: cuentas.fill };
  // «Todas»: también las apagadas en el DWG.
  fila.children[0].onclick(); cero(); pintarYa();
  salida.todas = { stroke: cuentas.stroke, fill: cuentas.fill, arc: cuentas.arc };
  salida.capas = panel.children.slice(1).map(l => l.children[2].textContent);
  const anot = document.getElementById('anot');
  salida.transformAnot = anot.style.transform || null;
  salida.errores = cuentas.errores;
  console.log(JSON.stringify(salida));
})().catch(e => { console.log(JSON.stringify({ errores: [String(e && e.stack || e)] })); });
