#!/usr/bin/env python3
"""Iconos del HUD de Ope Ope no Mi.

Genera los PNG de assets/opeope/textures/gui/sprites/hud/ (sprites opeope:hud/<nombre>) y una hoja de contactos
×8 en build/iconos-hoja.png (build/ no se sube al repositorio).

Solo usa la biblioteca estándar (zlib + struct) y es determinista: el mismo código da siempre los mismos bytes.
Se ejecuta desde la raíz del repositorio:

    python3 -I tools/iconos.py

Estilo:
- Blanco puro (se tiñe al dibujar), sin antialiasing, 1 píxel transparente de margen.
- Alfa 255 para los trazos, 56 para los rellenos y 140 para los detalles.
- 12x12 casi todos; el candado 8x8; los ratones 7x9 (van dentro de las teclas).
"""

import os
import struct
import zlib

STROKE = 255
DETAIL = 140
FILL = 56

OUT_DIR = os.path.join("src", "main", "resources", "assets", "opeope", "textures", "gui", "sprites", "hud")
SHEET = os.path.join("build", "iconos-hoja.png")

# El orden de Icons.ALL en el cliente (dev.opeope.client.hud.Icons).
ORDER = [
    "room", "takt", "shambles", "piedra", "scan", "rroom", "curtain", "silent", "radio_knife", "kroom",
    "injection", "anestesia", "gamma", "shock_wille", "despertar", "fila2", "sterben", "almas", "union", "cirugia",
    "lluvia", "mes", "counter", "puncture", "pilar", "muralla", "sima", "meseta", "patron", "alcance",
    "suspender", "orbita", "aparcar", "posar", "soltar", "lanzar_todo", "zona", "lo_cortado", "rejuntar", "unir",
    "lanza", "hoja", "martillo", "estacas", "muralla_forja", "amputar", "bisturi", "corazon", "aviso", "check",
    "fruta", "libro", "engranaje", "teclado", "candado", "raton_izq", "raton_der", "raton_rueda", "raton_lado",
]

SIZES = {"candado": (8, 8), "raton_izq": (7, 9), "raton_der": (7, 9), "raton_rueda": (7, 9), "raton_lado": (7, 9)}


# ---------------------------------------------------------------------------------------------- lienzo y trazos

class Icon:
    """Un lienzo de alfas (el color es siempre blanco). Las coordenadas son del área útil: (0,0) es el primer píxel
    dentro del margen de 1 píxel."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.a = [[0] * w for _ in range(h)]

    # -- primitivas
    def set(self, x, y, a=STROKE):
        """Pone un píxel (sobrescribe). Fuera del área útil no hace nada."""
        x += 1
        y += 1
        if 1 <= x < self.w - 1 and 1 <= y < self.h - 1:
            self.a[y][x] = a

    def get(self, x, y):
        x += 1
        y += 1
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.a[y][x]
        return 0

    def line(self, x0, y0, x1, y1, a=STROKE):
        """Bresenham, extremos incluidos."""
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.set(x0, y0, a)
            if x0 == x1 and y0 == y1:
                return
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def polyline(self, pts, a=STROKE):
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            self.line(x0, y0, x1, y1, a)

    def rect(self, x0, y0, x1, y1, a=STROKE):
        """Contorno de un rectángulo (extremos incluidos)."""
        self.line(x0, y0, x1, y0, a)
        self.line(x0, y1, x1, y1, a)
        self.line(x0, y0, x0, y1, a)
        self.line(x1, y0, x1, y1, a)

    def fill_rect(self, x0, y0, x1, y1, a=FILL):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, a)

    def _disc(self, cx, cy, r):
        """Píxeles cuyo centro cae dentro del círculo (cx, cy pueden ser medios: 4.5 = entre 4 y 5)."""
        out = set()
        for y in range(-1, self.h):
            for x in range(-1, self.w):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r + 0.01:
                    out.add((x, y))
        return out

    def circle(self, cx, cy, r, a=STROKE):
        """Contorno de 1 píxel (vecindad 4: sin esquinas dobles)."""
        d = self._disc(cx, cy, r)
        for (x, y) in d:
            if any((x + ox, y + oy) not in d for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                self.set(x, y, a)

    def fill_circle(self, cx, cy, r, a=FILL):
        for (x, y) in self._disc(cx, cy, r):
            self.set(x, y, a)

    def art(self, rows, ox=0, oy=0):
        """Dibujo a mano: '#' trazo, '+' detalle, ':' relleno, '.' nada (no toca), '0' borra."""
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch == '#':
                    self.set(ox + i, oy + j, STROKE)
                elif ch == '+':
                    self.set(ox + i, oy + j, DETAIL)
                elif ch == ':':
                    self.set(ox + i, oy + j, FILL)
                elif ch == '0':
                    self.set(ox + i, oy + j, 0)
                elif ch != '.':
                    raise ValueError("carácter raro %r" % ch)

    def rgba_rows(self):
        return [[(255, 255, 255, v) if v else (0, 0, 0, 0) for v in row] for row in self.a]


def grid(name, rows):
    """Comprueba que el dibujo a mano mide exactamente el área útil del icono."""
    w, h = SIZES.get(name, (12, 12))
    assert len(rows) == h - 2, "%s: %d filas, se esperaban %d" % (name, len(rows), h - 2)
    for r in rows:
        assert len(r) == w - 2, "%s: fila %r de %d, se esperaban %d" % (name, r, len(r), w - 2)
    ic = Icon(w, h)
    ic.art(rows)
    return ic


# ---------------------------------------------------------------------------------------------- PNG

def png_bytes(rows):
    """PNG RGBA de 8 bits, filtro 0 en cada fila."""
    h = len(rows)
    w = len(rows[0])
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def png_size(path):
    """Lee el ancho y el alto de la cabecera IHDR."""
    with open(path, "rb") as f:
        head = f.read(24)
    assert head[:8] == b"\x89PNG\r\n\x1a\n" and head[12:16] == b"IHDR", path
    return struct.unpack(">II", head[16:24])


# ---------------------------------------------------------------------------------------------- los iconos

ICONS = {}


def icon(fn):
    ICONS[fn.__name__] = fn
    return fn


def sword(ic, base, tip, a=STROKE):
    """Hoja recta en diagonal (↗) de 2 píxeles de grueso, de la base a la punta."""
    (x0, y0), (x1, y1) = base, tip
    ic.line(x0, y0, x1, y1, a)
    ic.line(x0, y0 - 1, x1 - 1, y1, a)


def band(ic, u0, u1, v0, v1, edge=STROKE, inner=FILL):
    """Una barra en diagonal (↗): los píxeles con u = x + y en [u0, u1] y v = x − y en [v0, v1].
    El borde (primera y última u, extremos de v) va en trazo y el interior en relleno."""
    for y in range(10):
        for x in range(10):
            u, v = x + y, x - y
            if u0 <= u <= u1 and v0 <= v <= v1:
                on_edge = u <= u0 + 0 or u >= u1 or v <= v0 + 1 or v >= v1 - 1
                ic.set(x, y, edge if on_edge else inner)


def arrow_ne(ic, tx, ty, length, a=STROKE):
    """Flecha hacia arriba a la derecha (↗): punta en esquina en (tx, ty) y astil de `length` píxeles."""
    for (x, y) in ((tx - 2, ty), (tx - 1, ty), (tx, ty), (tx, ty + 1), (tx, ty + 2)):
        ic.set(x, y, a)
    for i in range(1, length + 1):
        ic.set(tx - i, ty + i, a)


DOME = [
    "...####...",
    ".##::::##.",
    ".#::::::#.",
    "#::::::::#",
    "#::::::::#",
    "#::::::::#",
    "#::::::::#",
    "##########",
]


@icon
def room():
    # la esfera del ROOM con su anillo de puntos
    return grid("room", [
        "...####...",
        ".##::::##.",
        ".#::++::#.",
        "#::::::::#",
        "#:+::::+:#",
        "#:+::::+:#",
        "#::::::::#",
        ".#::++::#.",
        ".##::::##.",
        "...####...",
    ])



@icon
def takt():
    # una mano que señala arriba y lo que levanta
    return grid("takt", [
        "......###.",
        "..#...#:#.",
        ".#:#..###.",
        ".#:#.+....",
        ".#:####...",
        "##:#:#:#..",
        "#::::::#..",
        "#::::::#..",
        ".#::::#...",
        "..####....",
    ])




@icon
def shambles():
    return grid("shambles", [
        "..........",
        "......#...",
        ".......#..",
        ".########.",
        ".......#..",
        "...#..#...",
        "..#.......",
        ".########.",
        "..#.......",
        "...#......",
    ])


@icon
def piedra():
    return grid("piedra", [
        "..........",
        "......###.",
        ".....#:::#",
        "++..#::::#",
        "...#:::::#",
        "++.#::::#.",
        "...#:::#..",
        ".++.###...",
        "..........",
        "..........",
    ])


@icon
def scan():
    return grid("scan", [
        "..........",
        "..........",
        "...####...",
        ".##:++:##.",
        "#::+##+::#",
        "#::+##+::#",
        ".##:++:##.",
        "...####...",
        "..........",
        "..........",
    ])


@icon
def rroom():
    ic = grid("rroom", [".........."] * 2 + DOME)
    ic.art([
        "###.",
        "#..#",
        "###.",
        "#.#.",
        "#..#",
    ], 3, 4)
    return ic


@icon
def curtain():
    ic = grid("curtain", [".........."] * 2 + [r.replace(":", ".") for r in DOME])
    for x in (2, 4, 5, 7):
        ic.line(x, 5 if x in (2, 7) else 4, x, 8, DETAIL)
    return ic


@icon
def silent():
    return grid("silent", [
        "..........",
        "....#.....",
        "...##.....",
        "###:#.....",
        "#:::#.#..#",
        "#:::#..##.",
        "#:::#..##.",
        "###:#.#..#",
        "...##.....",
        "....#.....",
    ])


def _sword_with_guard(ic, guard_both=True):
    sword(ic, (3, 6), (9, 0))
    ic.line(2, 5, 1, 4)
    if guard_both:
        ic.line(4, 7, 5, 8)
    ic.line(2, 7, 1, 8)
    ic.set(0, 9)


@icon
def radio_knife():
    # la espada y un destello
    ic = Icon(12, 12)
    _sword_with_guard(ic)
    ic.set(2, 2)
    for (x, y) in ((2, 1), (1, 2), (3, 2), (2, 3), (2, 0), (0, 2)):
        ic.set(x, y, DETAIL)
    return ic



@icon
def kroom():
    ic = Icon(12, 12)
    _sword_with_guard(ic, guard_both=False)
    ic.art([
        "..##..",
        ".#::#.",
        "#::::#",
        "######",
    ], 4, 6)
    return ic


def _syringe(ic):
    band(ic, 8, 11, -5, 3)
    ic.set(1, 8)
    ic.set(0, 9)
    ic.set(7, 2)
    ic.set(8, 1)
    ic.line(7, 0, 9, 2)


@icon
def injection():
    ic = Icon(12, 12)
    _syringe(ic)
    return ic


@icon
def anestesia():
    ic = Icon(12, 12)
    _syringe(ic)
    ic.art([
        "####",
        "..#.",
        ".#..",
        "####",
    ], 0, 0)
    return ic


@icon
def gamma():
    ic = Icon(12, 12)
    ic.art([
        "..+++++..",
        ".+..#..+.",
        "+.#.#.#.+",
        "+.......+",
        "+##.#.##+",
        "+.......+",
        "+.#.#.#.+",
        ".+..#..+.",
        "..+++++..",
    ])
    return ic


@icon
def shock_wille():
    # la onda que sale del corazón: ((•))
    return grid("shock_wille", [
        "..........",
        ".+......+.",
        "+..#..#..+",
        "+.#....#.+",
        "+.#.##.#.+",
        "+.#.##.#.+",
        "+.#....#.+",
        "+..#..#..+",
        ".+......+.",
        "..........",
    ])




@icon
def despertar():
    ic = Icon(12, 12)
    ic.art([
        "....#....",
        ".+..#..+.",
        "...#:#...",
        "..#:::#..",
        "##:::::##",
        "..#:::#..",
        "...#:#...",
        ".+..#..+.",
        "....#....",
    ])
    return ic


@icon
def fila2():
    return grid("fila2", [
        "..........",
        "..........",
        ".++++++++.",
        ".+......+.",
        ".++++++++.",
        "..........",
        "##########",
        "#::::::::#",
        "##########",
        "..........",
    ])


@icon
def sterben():
    return grid("sterben", [
        ".......#.#",
        "...#....#.",
        "...#...#.#",
        "..#.#.....",
        "..#.#.....",
        "..#..#....",
        "##...#..++",
        ".....#.#..",
        "......#...",
        "..........",
    ])



@icon
def almas():
    # dos cuerpos (uno hueco, otro lleno) y el cambio entre ellos
    return grid("almas", [
        ".##....##.",
        "#..#..####",
        "#..#..####",
        ".##....##.",
        ".##....##.",
        "#..#..####",
        "..........",
        "..#....#..",
        ".########.",
        "..#....#..",
    ])




@icon
def union():
    return grid("union", [
        "..........",
        "###....###",
        "#:#+..+#:#",
        "#:#.++.#:#",
        "#:#+..+#:#",
        "#:#.++.#:#",
        "#:#+..+#:#",
        "###....###",
        "..........",
        "..........",
    ])


@icon
def cirugia():
    ic = Icon(12, 12)
    ic.art([
        "..###..",
        "..#:#..",
        "###:###",
        "#:::::#",
        "###:###",
        "..#:#..",
        "..###..",
    ])
    ic.line(5, 9, 6, 8)
    sword(ic, (7, 8), (9, 6))
    ic.set(9, 5)
    return ic


@icon
def lluvia():
    return grid("lluvia", [
        "...####...",
        ".##::::##.",
        "#::::::::#",
        "##########",
        "..........",
        "..#..#..#.",
        ".#..#..#..",
        "..........",
        ".#..#..#..",
        "#..#..#...",
    ])


@icon
def mes():
    return grid("mes", [
        "##########",
        "#........#",
        "#.##..##.#",
        "#.######.#",
        "#.######.#",
        "#..####..#",
        "#...##...#",
        "#........#",
        "##########",
        "..........",
    ])


@icon
def counter():
    return grid("counter", [
        "........#.",
        ".#.#...#..",
        ".#.#.#.###",
        ".#.#.#..#.",
        ".#.#.#.#..",
        "##:#:#....",
        "#::::#....",
        "#::::#....",
        ".#::#.....",
        "..##......",
    ])


@icon
def puncture():
    ic = Icon(12, 12)
    ic.circle(4.5, 4.5, 4.6, DETAIL)
    ic.line(0, 9, 8, 1)
    ic.art([
        "####",
        "...#",
        "...#",
        "...#",
    ], 6, 0)
    return ic


@icon
def pilar():
    return grid("pilar", [
        "..######..",
        "...####...",
        "...#::#...",
        "...#::#...",
        "...#::#...",
        "...#::#...",
        "...#::#...",
        "..######..",
        "##########",
        "..........",
    ])



@icon
def muralla():
    return grid("muralla", [
        "....#.....",
        "...###....",
        "..#.#.#...",
        "....#.....",
        "##########",
        "#:::#::::#",
        "##########",
        "#::#::::##",
        "##########",
        "..........",
    ])


@icon
def sima():
    return grid("sima", [
        "....##....",
        "....##....",
        "..######..",
        "...####...",
        "....##....",
        "###....###",
        "..#....#..",
        "...#..#...",
        "....##....",
        "..........",
    ])


@icon
def meseta():
    return grid("meseta", [
        "....##....",
        "...####...",
        "..######..",
        "....##....",
        "..........",
        "..######..",
        "..#::::#..",
        "..#::::#..",
        "###::::###",
        "..........",
    ])


@icon
def patron():
    ic = Icon(12, 12)
    ic.art([
        "#########",
        "#:::#:::#",
        "#:::#:::#",
        "#:::#:::#",
        "#########",
        "#:::#:::#",
        "#:::#:::#",
        "#:::#:::#",
        "#########",
    ])
    return ic


@icon
def alcance():
    ic = Icon(12, 12)
    ic.art([
        "....#....",
        "..#####..",
        ".#..#..#.",
        ".#.....#.",
        "###.#.###",
        ".#.....#.",
        ".#..#..#.",
        "..#####..",
        "....#....",
    ])
    return ic


@icon
def suspender():
    return grid("suspender", [
        "..........",
        "..######..",
        "..#::::#..",
        "..#::::#..",
        "..######..",
        "..........",
        "...+..+...",
        "..........",
        "...+..+...",
        "##########",
    ])


@icon
def orbita():
    return grid("orbita", [
        "...++++...",
        ".++....##.",
        ".+.....##.",
        "+........+",
        "+...##...+",
        "+...##...+",
        "+........+",
        ".+......+.",
        ".++....++.",
        "...++++...",
    ])



@icon
def aparcar():
    return grid("aparcar", [
        "##########",
        "#::::::::#",
        "#:####:::#",
        "#:#::#:::#",
        "#:####:::#",
        "#:#::::::#",
        "#:#::::::#",
        "#::::::::#",
        "##########",
        "..........",
    ])


@icon
def posar():
    return grid("posar", [
        "....##....",
        "....##....",
        "....##....",
        "....##....",
        "..######..",
        "...####...",
        "....##....",
        "..........",
        "##########",
        "..........",
    ])


@icon
def soltar():
    return grid("soltar", [
        "..+....+..",
        "..+....+..",
        "..........",
        "..+....+..",
        "..........",
        "..######..",
        "..#::::#..",
        "..#::::#..",
        "..#::::#..",
        "..######..",
    ])


@icon
def lanzar_todo():
    # tres flechas que salen a la vez
    ic = Icon(12, 12)
    arrow_ne(ic, 4, 1, 4)
    arrow_ne(ic, 9, 0, 6)
    arrow_ne(ic, 8, 5, 4)
    return ic




@icon
def zona():
    return grid("zona", [
        "##.####.##",
        "#........#",
        "..........",
        "#........#",
        "#...##...#",
        "#...##...#",
        "#........#",
        "..........",
        "#........#",
        "##.####.##",
    ])


@icon
def lo_cortado():
    return grid("lo_cortado", [
        "#######...",
        "#::::#....",
        "#:::#.....",
        "#::#.....#",
        "#:#.....##",
        "##.....#:#",
        "#.....#::#",
        ".....#:::#",
        "....#::::#",
        "...#######",
    ])


@icon
def rejuntar():
    return grid("rejuntar", [
        "#........#",
        ".#......#.",
        "..##..##..",
        "..#....#..",
        "....##....",
        "....##....",
        "..#....#..",
        "..##..##..",
        ".#......#.",
        "#........#",
    ])


@icon
def unir():
    return grid("unir", [
        "######....",
        "#::::#....",
        "#::::#....",
        "#::#######",
        "#::#:::::#",
        "####:::::#",
        "...#:::::#",
        "...#:::::#",
        "...#######",
        "..........",
    ])


@icon
def lanza():
    ic = Icon(12, 12)
    ic.line(0, 9, 7, 2)
    ic.art([
        "####",
        ".###",
        "..##",
        "...#",
    ], 6, 0)
    return ic


@icon
def hoja():
    return grid("hoja", [
        "....##....",
        "...#::#...",
        "...#::#...",
        "...#::#...",
        "...#::#...",
        "...#::#...",
        ".########.",
        "....##....",
        "....##....",
        "...####...",
    ])



@icon
def martillo():
    return grid("martillo", [
        "..######..",
        "..#::::#..",
        "..#::::#..",
        "..######..",
        "....##....",
        "....##....",
        "....##....",
        "....##....",
        "....##....",
        "..........",
    ])


@icon
def estacas():
    return grid("estacas", [
        "##........",
        "##..##....",
        "##..##..##",
        "##..##..##",
        "##..##..##",
        "##..##..##",
        "#+..##..##",
        "#...#+..##",
        "....#...#+",
        "........#.",
    ])



@icon
def muralla_forja():
    return grid("muralla_forja", [
        "..........",
        "##########",
        "#::#::::##",
        "##########",
        "#::::#:::#",
        "##########",
        "#::#::::##",
        "##########",
        "..........",
        "..........",
    ])


@icon
def amputar():
    ic = Icon(12, 12)
    disc = {(x, y) for y in range(8) for x in range(8) if (x - 3.5) ** 2 + (y - 3.5) ** 2 <= 4.1 ** 2}
    top = {(x, y) for (x, y) in disc if x + y <= 6}
    bot = {(x + 2, y + 2) for (x, y) in disc if x + y >= 8}
    for part in (top, bot):
        for (x, y) in part:
            edge = any((x + dx, y + dy) not in part for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            ic.set(x, y, STROKE if edge else FILL)
    ic.line(9, 0, 0, 9, DETAIL)
    return ic



@icon
def bisturi():
    ic = Icon(12, 12)
    ic.art([
        ".......###",
        "......#::#",
        ".....#::#.",
        "....#::#..",
        "...####...",
    ], 0, 0)
    ic.line(2, 5, 0, 7)
    ic.line(3, 5, 1, 7)
    ic.set(0, 9, DETAIL)
    ic.set(2, 9, DETAIL)
    ic.set(4, 9, DETAIL)
    return ic


@icon
def corazon():
    return grid("corazon", [
        "..........",
        ".###..###.",
        "##########",
        "##########",
        "##########",
        ".########.",
        "..######..",
        "...####...",
        "....##....",
        "..........",
    ])


@icon
def aviso():
    return grid("aviso", [
        "....##....",
        "...#::#...",
        "...#::#...",
        "..#:##:#..",
        "..#:##:#..",
        ".#::##::#.",
        ".#::::::#.",
        "#:::##:::#",
        "##########",
        "..........",
    ])


@icon
def check():
    return grid("check", [
        "..........",
        "..........",
        ".........#",
        "........##",
        "#......##.",
        "##....##..",
        ".##..##...",
        "..####....",
        "...##.....",
        "..........",
    ])


@icon
def fruta():
    return grid("fruta", [
        "......##..",
        ".....#++#.",
        ".###.#.##.",
        "#:::##:::#",
        "#:++::++:#",
        "#:+:::+::#",
        ".#::++::#.",
        "..#:+::#..",
        "...#::#...",
        "....##....",
    ])


@icon
def libro():
    return grid("libro", [
        "..........",
        "##......##",
        "#:##..##:#",
        "#:::##:::#",
        "#:++##++:#",
        "#:::##:::#",
        "#:++##++:#",
        "#:::##:::#",
        "##########",
        "..........",
    ])



@icon
def engranaje():
    return grid("engranaje", [
        "....##....",
        ".#.#..#.#.",
        "..#::::#..",
        ".#::##::#.",
        "#::#..#::#",
        "#::#..#::#",
        ".#::##::#.",
        "..#::::#..",
        ".#.#..#.#.",
        "....##....",
    ])


@icon
def teclado():
    return grid("teclado", [
        "..........",
        "##########",
        "#:+:+:+:+#",
        "#::::::::#",
        "#+:+:+:+:#",
        "#::::::::#",
        "#::++++::#",
        "##########",
        "..........",
        "..........",
    ])


@icon
def candado():
    return grid("candado", [
        "..##..",
        ".#..#.",
        ".#..#.",
        "######",
        "##00##",
        "######",
    ])


@icon
def raton_izq():
    return grid("raton_izq", [
        ".###.",
        "##+.#",
        "##+.#",
        "#####",
        "#:::#",
        "#:::#",
        ".###.",
    ])


@icon
def raton_der():
    return grid("raton_der", [
        ".###.",
        "#.+##",
        "#.+##",
        "#####",
        "#:::#",
        "#:::#",
        ".###.",
    ])


@icon
def raton_rueda():
    return grid("raton_rueda", [
        ".###.",
        "#.#.#",
        "#.#.#",
        "#####",
        "#:::#",
        "#:::#",
        ".###.",
    ])


@icon
def raton_lado():
    return grid("raton_lado", [
        ".###.",
        "#.+.#",
        "#.+.#",
        "#####",
        "##::#",
        "##::#",
        ".###.",
    ])


# ---------------------------------------------------------------------------------------------- salida

def contact_sheet(images, scale=8, cols=10, pad=8):
    """Hoja de contactos: cada icono ×scale sobre fondo oscuro, en el orden de Icons.ALL."""
    cell = 12 * scale + pad
    rows = (len(images) + cols - 1) // cols
    W, H = cols * cell + pad, rows * cell + pad
    bg = (11, 25, 35, 255)
    tile = (5, 13, 19, 255)
    px = [[bg] * W for _ in range(H)]
    for k, (name, ic) in enumerate(images):
        cx = pad + (k % cols) * cell
        cy = pad + (k // cols) * cell
        for y in range(12 * scale):
            for x in range(12 * scale):
                px[cy + y][cx + x] = tile
        ox = cx + (12 - ic.w) * scale // 2
        oy = cy + (12 - ic.h) * scale // 2
        for y in range(ic.h):
            for x in range(ic.w):
                a = ic.a[y][x]
                if not a:
                    continue
                # blanco sobre el fondo de la casilla, mezclado con su alfa
                c = tuple((255 * a + tile[i] * (255 - a)) // 255 for i in range(3)) + (255,)
                for yy in range(scale):
                    row = px[oy + y * scale + yy]
                    for xx in range(scale):
                        row[ox + x * scale + xx] = c
    return px


def main():
    assert sorted(ORDER) == sorted(ICONS), "faltan o sobran iconos: %s" % (set(ORDER) ^ set(ICONS))
    assert len(ORDER) == 59 and len(set(ORDER)) == 59
    os.makedirs(OUT_DIR, exist_ok=True)
    made = []
    for name in ORDER:
        ic = ICONS[name]()
        w, h = SIZES.get(name, (12, 12))
        assert (ic.w, ic.h) == (w, h), name
        # margen transparente de 1 píxel
        for y in range(h):
            for x in range(w):
                if x in (0, w - 1) or y in (0, h - 1):
                    assert ic.a[y][x] == 0, "%s: el margen no está vacío en (%d,%d)" % (name, x, y)
        path = os.path.join(OUT_DIR, name + ".png")
        with open(path, "wb") as f:
            f.write(png_bytes(ic.rgba_rows()))
        assert png_size(path) == (w, h), path
        made.append((name, ic))
    os.makedirs(os.path.dirname(SHEET), exist_ok=True)
    with open(SHEET, "wb") as f:
        f.write(png_bytes(contact_sheet(made)))
    print("%d iconos en %s; hoja en %s" % (len(made), OUT_DIR, SHEET))


if __name__ == "__main__":
    main()
