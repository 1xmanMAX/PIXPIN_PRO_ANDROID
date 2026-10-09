#!/usr/bin/env python3
"""
**Las letras de AutoCAD sin AutoCAD** (9-oct-2026).

Las fuentes SHX de AutoCAD con que están hechos casi todos los planos —romans, simplex, romand,
scripts, gothice…— son de Autodesk y no se pueden repartir. Pero salen de las **fuentes de
Hershey** (1967, de dominio público en la práctica: ver HERSHEY-LEEME.txt): `romans.shx` es la
«Roman Simplex» de Hershey, `romand` la «Duplex», `romant` la «Triplex», etc. Este guion convierte
los `.jhf` de Hershey en archivos `.shx` de verdad («AutoCAD-86 shapes 1.0»), que el visor lee igual
que los de AutoCAD (`shx.rs`): mismas formas y, sobre todo, **mismo ancho**. Sin ellos, en el
teléfono los textos salían con Roboto, más ancha, y se montaban unos sobre otros (lo vio el usuario).

Añade lo que Hershey no tiene y en un plano en castellano sale siempre: vocales con tilde, ñ, ü,
°, ±, ², ³, Ø/ø (diámetro), ¿, ¡, º, ª. Se componen con las letras de la propia fuente.

Uso: hershey-a-shx.py <fuente.jhf> <salida.shx> <nombre>
"""
import sys

# --------------------------------------------------------------- leer .jhf

def leer_jhf(ruta):
    """Las letras de un .jhf en orden (la primera es el espacio, ASCII 32): (izq, der, trazos)."""
    texto = open(ruta, encoding="latin-1").read()
    letras = []
    i = 0
    n = len(texto)
    while i < n:
        while i < n and texto[i] in "\r\n":
            i += 1
        if i + 8 > n:
            break
        cuantos = int(texto[i + 5:i + 8])
        i += 8
        pares = []
        while len(pares) < cuantos and i + 1 < n:
            if texto[i] in "\r\n":
                i += 1
                continue
            pares.append((texto[i], texto[i + 1]))
            i += 2
        izq = ord(pares[0][0]) - ord("R")
        der = ord(pares[0][1]) - ord("R")
        trazos, actual = [], []
        for a, b in pares[1:]:
            if a == " " and b == "R":
                if len(actual) > 1:
                    trazos.append(actual)
                actual = []
            else:
                # Hershey: y hacia abajo, la base en y = 9. SHX: y hacia arriba, la base en 0.
                actual.append((ord(a) - ord("R") - izq, 9 - (ord(b) - ord("R"))))
        if len(actual) > 1:
            trazos.append(actual)
        letras.append((der - izq, trazos))
    return letras

# --------------------------------------------------------------- componer

def mover(trazos, dx=0, dy=0, k=1.0):
    return [[(round(x * k + dx), round(y * k + dy)) for x, y in t] for t in trazos]

def girar_180(avance, trazos, alto):
    """¿ y ¡: la letra dada la vuelta (la base pasa arriba de la caída)."""
    return [[(avance - x, alto - 7 - y) for x, y in t] for t in trazos]

def componer(base, ascii_):
    """Las letras de Latin-1 que hacen falta, hechas con las de la fuente."""
    def L(c):
        return base[ord(c) - 32]
    extra = {}
    def con_marca(c, marca, mayus):
        av, tr = L(c)
        cx = av // 2
        y0 = 24 if mayus else 17
        if marca == "aguda":
            m = [[(cx - 1, y0), (cx + 3, y0 + 4)]]
        elif marca == "tilde":
            m = [[(cx - 4, y0 + 1), (cx - 2, y0 + 3), (cx + 2, y0 + 1), (cx + 4, y0 + 3)]]
        else:  # diéresis: dos puntitos
            m = [[(cx - 3, y0 + 1), (cx - 3, y0 + 2)], [(cx + 3, y0 + 1), (cx + 3, y0 + 2)]]
        return (av, tr + m)
    for c, cod in [("a", 225), ("e", 233), ("i", 237), ("o", 243), ("u", 250)]:
        extra[cod] = con_marca(c, "aguda", False)
    for c, cod in [("A", 193), ("E", 201), ("I", 205), ("O", 211), ("U", 218)]:
        extra[cod] = con_marca(c, "aguda", True)
    extra[241] = con_marca("n", "tilde", False)
    extra[209] = con_marca("N", "tilde", True)
    extra[252] = con_marca("u", "dieresis", False)
    extra[220] = con_marca("U", "dieresis", True)
    # ° grados: un círculo pequeño arriba.
    import math
    circ = [(round(5 + 3 * math.cos(a * math.pi / 6)), round(18 + 3 * math.sin(a * math.pi / 6))) for a in range(13)]
    extra[176] = (10, [circ])
    # ± : el + de la fuente y una raya debajo.
    av, tr = L("+")
    extra[177] = (av, mover(tr, 0, 3) + [[(av // 2 - 8, 0), (av // 2 + 8, 0)]])
    # ² ³ : pequeñas y subidas.
    for c, cod in [("2", 178), ("3", 179)]:
        av, tr = L(c)
        extra[cod] = (round(av * 0.6), mover(tr, 0, 12, 0.6))
    # º ª : o/a pequeñas, subidas y con su raya.
    for c, cod in [("o", 186), ("a", 170)]:
        av, tr = L(c)
        w = round(av * 0.6)
        extra[cod] = (w, mover(tr, 0, 12, 0.6) + [[(1, 10), (w - 1, 10)]])
    # Ø ø : la O con su barra (el diámetro, %%c).
    av, tr = L("O")
    extra[216] = (av, tr + [[(2, -1), (av - 2, 22)]])
    av, tr = L("o")
    extra[248] = (av, tr + [[(2, -1), (av - 2, 15)]])
    # ¿ ¡
    for c, cod in [("?", 191), ("!", 161)]:
        av, tr = L(c)
        extra[cod] = (av, girar_180(av, tr, 21))
    return extra

# --------------------------------------------------------------- escribir .shx

def sb(v):
    if not -127 <= v <= 127:
        raise ValueError(v)
    return v & 0xFF

def forma(avance, trazos):
    """Los bytes de una letra: pluma arriba, ir al trazo, bajar, recorrerlo… y al final al avance."""
    b = bytearray([2])
    x = y = 0
    for t in trazos:
        (x0, y0) = t[0]
        if (x0, y0) != (x, y):
            b += bytes([8, sb(x0 - x), sb(y0 - y)])
        x, y = x0, y0
        b.append(1)
        movs = []
        for (px, py) in t[1:]:
            dx, dy = px - x, py - y
            if dx or dy:
                movs.append((dx, dy))
            x, y = px, py
        if movs:
            b.append(9)
            for dx, dy in movs:
                b += bytes([sb(dx), sb(dy)])
            b += bytes([0, 0])
        b.append(2)
    if (avance, 0) != (x, y):
        b += bytes([8, sb(avance - x), sb(0 - y)])
    b.append(0)
    return bytes(b)

def escribir_shx(salida, nombre, formas):
    """`formas`: {número: bytes}. La 0 es la de la fuente: alto de mayúscula y caída."""
    datos = {0: nombre.encode("latin-1") + b"\0" + bytes([21, 7, 0, 0])}
    for num, by in formas.items():
        datos[num] = b"\0" + by  # sin nombre
    nums = sorted(datos)
    out = bytearray(b"AutoCAD-86 shapes 1.0\r\n\x1a")
    out += nums[0].to_bytes(2, "little") + nums[-1].to_bytes(2, "little") + len(nums).to_bytes(2, "little")
    for n in nums:
        out += n.to_bytes(2, "little") + len(datos[n]).to_bytes(2, "little")
    for n in nums:
        out += datos[n]
    open(salida, "wb").write(out)

def main():
    jhf, salida, nombre = sys.argv[1:4]
    letras = leer_jhf(jhf)
    formas = {}
    for i, (av, tr) in enumerate(letras[:95]):
        formas[32 + i] = forma(av, tr)
    if len(letras) >= 95:
        for cod, (av, tr) in componer(letras, None).items():
            formas[cod] = forma(av, tr)
    escribir_shx(salida, nombre, formas)
    print(f"{salida}: {len(formas)} letras")

if __name__ == "__main__":
    main()
