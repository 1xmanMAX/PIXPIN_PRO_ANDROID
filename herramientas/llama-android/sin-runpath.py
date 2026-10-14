#!/usr/bin/env python3
"""Deja vacío el RUNPATH que el clang de Termux mete siempre (-rpath=$PREFIX/lib).

En el teléfono esa carpeta no existe y el enlazador de Android no la necesita (el binario solo
pide libc, libm y libdl del sistema), pero mejor que no busque en una ruta de Termux. Se pone a
cero el primer byte de la cadena en .dynstr: queda un RUNPATH vacío, que bionic ignora.

Uso: sin-runpath.py <ejecutable ELF64 little-endian>
"""
import struct
import sys

DT_NULL, DT_STRTAB, DT_RPATH, DT_RUNPATH = 0, 5, 15, 29


def main(ruta):
    with open(ruta, "r+b") as f:
        datos = bytearray(f.read())
        assert datos[:4] == b"\x7fELF" and datos[4] == 2 and datos[5] == 1, "no es ELF64 LE"
        (e_phoff,) = struct.unpack_from("<Q", datos, 0x20)
        e_phentsize, e_phnum = struct.unpack_from("<HH", datos, 0x36)
        cargas, dinamica = [], None
        for i in range(e_phnum):
            p_type, _, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from("<IIQQQQQQ", datos, e_phoff + i * e_phentsize)
            if p_type == 1:
                cargas.append((p_vaddr, p_offset, p_filesz))
            elif p_type == 2:
                dinamica = (p_offset, p_filesz)
        assert dinamica, "sin PT_DYNAMIC"

        def a_fichero(vaddr):
            for v, o, n in cargas:
                if v <= vaddr < v + n:
                    return o + (vaddr - v)
            raise ValueError(hex(vaddr))

        strtab, rutas = None, []
        off, fin = dinamica[0], dinamica[0] + dinamica[1]
        while off < fin:
            etiqueta, valor = struct.unpack_from("<qQ", datos, off)
            if etiqueta == DT_NULL:
                break
            if etiqueta == DT_STRTAB:
                strtab = a_fichero(valor)
            elif etiqueta in (DT_RPATH, DT_RUNPATH):
                rutas.append(valor)
            off += 16
        for r in rutas:
            pos = strtab + r
            antes = datos[pos:datos.index(0, pos)].decode()
            datos[pos] = 0
            print(f"RUNPATH «{antes}» → vacío")
        f.seek(0)
        f.write(datos)


if __name__ == "__main__":
    main(sys.argv[1])
