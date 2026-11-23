"""Shared pieces for the README's SVG images: outlined IBM Plex text, console tokens, the console's status
components drawn as vectors, the engraved grid, and a keyframe helper.

Text is converted to outlines (glyphs defined once in <defs>, reused with <use>), because GitHub shows README SVGs
through <img>, where web fonts can't load. Fonts are read from the console's own packages
(web/node_modules/@fontsource, .woff files), so the only Python dependency is fontTools.
Sizes follow web/src/ui (Status.css, Badge.css, Card.css) and web/src/tokens.css, in console CSS px times a scale k.
"""
import os
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '../../..'))
OUT = os.path.join(ROOT, 'docs/readme')
CACHE = os.path.join(HERE, '..', '.cache')
FONTS = os.path.join(ROOT, 'web/node_modules/@fontsource')

_F = {}
def font(family, weight):
    key = (family, weight)
    if key not in _F:
        path = f'{FONTS}/{family}/files/{family}-latin-{weight}-normal.woff'
        if not os.path.exists(path):
            raise SystemExit(f'font missing: {path} (run npm install in web/)')
        _F[key] = TTFont(path)
    return _F[key]
SANS = lambda w: font('ibm-plex-sans', w)
MONO = lambda w: font('ibm-plex-mono', w)

def f(v):
    """Compact number for SVG attributes."""
    return f'{v:.2f}'.rstrip('0').rstrip('.')

# ---------- outlined text ----------
GLYPHS = {}   # (font id, glyph) -> (def id, path in font units); cleared per SVG

def measure(fnt, s, size, tr=0.0):
    cmap = fnt.getBestCmap(); k = size / fnt['head'].unitsPerEm
    return sum(fnt['hmtx'][cmap[ord(ch)]][0] * k + tr * size for ch in s) - tr * size

def _glyph(fnt, g):
    key = (id(fnt), g)
    if key not in GLYPHS:
        pen = SVGPathPen(fnt.getGlyphSet(), ntos=lambda v: f'{v:.0f}')
        fnt.getGlyphSet()[g].draw(pen)
        GLYPHS[key] = (f'g{len(GLYPHS)}', pen.getCommands())
    return GLYPHS[key][0]

def text(fnt, s, x, y, size, fill, anchor='start', tr=0.0, op=None):
    """Outlined text with its baseline at y. tr = letter spacing in em."""
    w = measure(fnt, s, size, tr)
    if anchor == 'middle': x -= w / 2
    elif anchor == 'end': x -= w
    cmap = fnt.getBestCmap(); k = size / fnt['head'].unitsPerEm; out = []; cx = x
    for ch in s:
        g = cmap[ord(ch)]
        if ch != ' ':
            out.append(f'<use href="#{_glyph(fnt, g)}" transform="translate({cx:.2f} {y:.2f}) scale({k:.5f} {-k:.5f})"/>')
        cx += fnt['hmtx'][g][0] * k + tr * size
    o = f' fill-opacity="{op}"' if op is not None else ''
    return f'<g fill="{fill}"{o}>' + ''.join(out) + '</g>'

def glyph_defs():
    return ''.join(f'<path id="{i}" d="{d}"/>' for i, d in GLYPHS.values())

# ---------- tokens (web/src/tokens.css) + scene colours ----------
TOK = {
    'light': dict(app='#f6f7f9', surface='#ffffff', surface2='#f1f2f5', border='#e7e9ee', border_strong='#d6dae1',
                  ink='#15181d', text='#1b2230', text2='#384152', secondary='#586073', muted='#6b7686',
                  online='#1aa766', warn='#d98a2b', alert='#d64545', offline='#b6bdc7',
                  ok_ink='#0e7a47', ok_soft='#e6f6ee', ok_line='#bfe8d2', warn_ink='#8f5410', warn_soft='#fcf1e3', warn_line='#f1d3ad',
                  alert_ink='#a92f2f', alert_soft='#fbe9e9', alert_line='#f0d4d4', idle_ink='#586073', idle_soft='#f1f2f5', idle_line='#e7e9ee',
                  shadow='rgb(20,30,55)', shadow_op=.12, esh='#586073',
                  bg_top='#ffffff', bg_bot='#fafbfc', frame='rgba(21,24,29,.08)', grid_dark=.05, grid_light=.9, vignette=.05),
    'dark': dict(app='#0f1115', surface='#161922', surface2='#1e222c', border='#272c37', border_strong='#39404e',
                 ink='#f2f4f8', text='#e7ebf1', text2='#c4ccd7', secondary='#9aa3b0', muted='#8b95a3',
                 online='#2bb979', warn='#e0973a', alert='#e25757', offline='#4a5260',
                 ok_ink='#4fd194', ok_soft='#13261d', ok_line='#1f4a35', warn_ink='#f0b265', warn_soft='#2a2015', warn_line='#4d3a20',
                 alert_ink='#f08a8a', alert_soft='#2a1b1e', alert_line='#45292d', idle_ink='#9aa3b0', idle_soft='#1e222c', idle_line='#272c37',
                 shadow='rgb(0,0,0)', shadow_op=.5, esh='#9aa3b0',
                 bg_top='#14171e', bg_bot='#111319', frame='rgba(255,255,255,.08)', grid_dark=.55, grid_light=.045, vignette=.35),
}
TONE_FILL = {'ok': 'online', 'warn': 'warn', 'alert': 'alert', 'idle': 'offline'}
CHECK_D = 'M3.5 8.2l2.9 2.9L12.5 5'   # the 16-unit check icon used in badges

# ---------- console components ----------
def dot(t, tone, shape, cx, cy, k=1, size=7):
    """StatusDot centred on (cx, cy): ok circle, warn diamond, alert square, idle hollow ring."""
    s = size * k
    fill = t[TONE_FILL[tone]]
    if shape == 'circle':
        return f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{f(s / 2)}" fill="{fill}"/>'
    if shape == 'ring':
        return f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{f(s / 2 - .75 * k)}" fill="none" stroke="{t["muted"]}" stroke-width="{f(1.5 * k)}"/>'
    rect = f'<rect x="{f(cx - s / 2)}" y="{f(cy - s / 2)}" width="{f(s)}" height="{f(s)}" rx="{f(k)}" fill="{fill}"'
    return rect + (f' transform="rotate(45 {f(cx)} {f(cy)})"/>' if shape == 'diamond' else '/>')

def status_pill(t, tone, label, x, y, k=1, tail=None):
    """StatusPill (2x10 padding, r999, 12.5/500, soft fill + line + ink), top-left at (x, y). Returns (svg, w, h)."""
    shape = {'ok': 'circle', 'warn': 'diamond', 'alert': 'square', 'idle': 'ring'}[tone]
    fs = 12.5 * k
    h = fs * 1.5 + 6 * k
    w = (20 + 14 + 2) * k + measure(SANS(500), label, fs) + (6 * k + measure(MONO(500), tail, fs) if tail else 0)
    ink, soft, line = t[f'{tone}_ink'], t[f'{tone}_soft'], t[f'{tone}_line']
    out = f'<rect x="{f(x + k / 2)}" y="{f(y + k / 2)}" width="{f(w - k)}" height="{f(h - k)}" rx="{f((h - k) / 2)}" fill="{soft}" stroke="{line}" stroke-width="{f(k)}"/>'
    cx, cy = x + 11 * k, y + h / 2
    out += dot(t, tone, shape, cx + 3.5 * k, cy, k)
    tx, base = cx + 14 * k, cy + fs * 0.36
    out += text(SANS(500), label, tx, base, fs, ink)
    if tail:
        out += text(MONO(500), tail, tx + measure(SANS(500), label, fs) + 6 * k, base, fs, ink, op=.75)
    return out, w, h

def shadow_filter(k=1, sid='sh'):
    return f'<filter id="{sid}" x="-30%" y="-60%" width="160%" height="220%"><feGaussianBlur stdDeviation="{f(12 * k)}"/></filter>'

def wordmark(t, x0, base, size):
    """■ MDMesh as in the console sidebar: ink square, "MDM" 700, "esh" 500 in the secondary colour."""
    wM = measure(SANS(700), 'MDM', size, -0.01)
    wE = measure(SANS(500), 'esh', size, -0.01)
    mark, gap = 0.44 * size, 0.30 * size
    capH = SANS(700)['OS/2'].sCapHeight * size / SANS(700)['head'].unitsPerEm
    out = f'<rect x="{f(x0)}" y="{f(base - capH / 2 - mark / 2)}" width="{f(mark)}" height="{f(mark)}" fill="{t["ink"]}"/>'
    out += text(SANS(700), 'MDM', x0 + mark + gap, base, size, t['ink'], tr=-0.01)
    out += text(SANS(500), 'esh', x0 + mark + gap + wM, base, size, t['esh'], tr=-0.01)
    return out, mark + gap + wM + wE

# ---------- surfaces ----------
def grid(t, W, H, cell=14, recede=0.0, vp_y=-1600, mask_id='gm', cx=None, cy=None, rx=None, ry=None):
    """Engraved hairline grid (a dark line plus a light line 1 px below/right), optionally receding in perspective,
    fading out toward every edge. Returns (defs, body)."""
    cx = W / 2 if cx is None else cx
    cy = H / 2 if cy is None else cy
    rx = W * .55 if rx is None else rx
    ry = H * .75 if ry is None else ry
    g = lambda v: f'{v:.1f}'.rstrip('0').rstrip('.')
    ys, y = [], H + cell
    while y > -cell:
        ys.append(y)
        y -= cell * (1 - recede * (1 - max(0.0, y / H)))
    xs = [W / 2 + i * cell for i in range(-int(W / cell / 2) - 6, int(W / cell / 2) + 7)]
    def vline(xb, off):
        x_top = W / 2 + (xb - W / 2) * (0 - vp_y) / (H - vp_y) if recede else xb
        return f'M{g(xb + off)} {H}L{g(x_top + off)} 0'
    dark = ''.join(f'M0 {g(yy)}H{W}' for yy in ys) + ''.join(vline(xb, 0) for xb in xs)
    light = ''.join(f'M0 {g(yy + 1)}H{W}' for yy in ys) + ''.join(vline(xb, 1) for xb in xs)
    dark_col = '#000' if t is TOK['dark'] else '#15181d'
    defs = (f'<radialGradient id="{mask_id}g" cx="{f(cx)}" cy="{f(cy)}" r="1" gradientUnits="userSpaceOnUse" '
            f'gradientTransform="translate({f(cx)} {f(cy)}) scale({f(rx)} {f(ry)}) translate({f(-cx)} {f(-cy)})">'
            f'<stop offset="0" stop-color="#fff"/><stop offset=".45" stop-color="#fff" stop-opacity=".45"/><stop offset=".85" stop-color="#fff" stop-opacity="0"/></radialGradient>'
            f'<mask id="{mask_id}" maskUnits="userSpaceOnUse" x="0" y="0" width="{W}" height="{H}"><rect width="{W}" height="{H}" fill="url(#{mask_id}g)"/></mask>')
    body = (f'<g mask="url(#{mask_id})" fill="none" stroke-width="1">'
            f'<path d="{light}" stroke="#fff" stroke-opacity="{t["grid_light"]}"/>'
            f'<path d="{dark}" stroke="{dark_col}" stroke-opacity="{t["grid_dark"]}"/></g>')
    return defs, body

def vignette(t, W, H, vid='vg'):
    """Soft inner shadow, so a panel reads as set into the page."""
    defs = (f'<radialGradient id="{vid}" cx="50%" cy="45%" r="75%"><stop offset=".55" stop-color="{t["shadow"]}" stop-opacity="0"/>'
            f'<stop offset="1" stop-color="{t["shadow"]}" stop-opacity="{t["vignette"]}"/></radialGradient>'
            f'<linearGradient id="{vid}t" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{t["shadow"]}" stop-opacity="{t["vignette"] * 1.2}"/>'
            f'<stop offset=".08" stop-color="{t["shadow"]}" stop-opacity="0"/></linearGradient>')
    return defs, f'<rect width="{W}" height="{H}" fill="url(#{vid})"/><rect width="{W}" height="{H}" fill="url(#{vid}t)"/>'

# ---------- animation ----------
HOLD = 'step-end'
def bz(c):
    return f'cubic-bezier({",".join(str(v) for v in c)})'

def kf(name, D, stops):
    """@keyframes from absolute-ms stops [(ms, 'decls', easing to the next stop)] on a D ms cycle.
    Missing 0% / 100% stops hold the nearest value."""
    stops = sorted(stops, key=lambda s: s[0])
    if stops[0][0] > 0:
        stops = [(0, stops[0][1], HOLD)] + stops
    if stops[-1][0] < D:
        stops = stops + [(D, stops[-1][1], None)]
    body = ''
    for ms, decl, ease in stops:
        p = f'{max(0.0, min(100.0, ms / D * 100)):.3f}'.rstrip('0').rstrip('.') or '0'
        body += f'{p}%{{{decl}' + (f';animation-timing-function:{ease}' if ease else '') + '}'
    return f'@keyframes {name}{{{body}}}'

def bezier(p1x, p1y, p2x, p2y):
    """(y_of_x, x_of_y) for a CSS cubic-bezier, to place beats on an eased motion."""
    bx = lambda t: 3 * (1 - t) ** 2 * t * p1x + 3 * (1 - t) * t ** 2 * p2x + t ** 3
    by = lambda t: 3 * (1 - t) ** 2 * t * p1y + 3 * (1 - t) * t ** 2 * p2y + t ** 3
    def solve(fn, v):
        lo, hi = 0.0, 1.0
        for _ in range(60):
            mid = (lo + hi) / 2
            if fn(mid) < v: lo = mid
            else: hi = mid
        return (lo + hi) / 2
    return (lambda x: by(solve(bx, x))), (lambda y: bx(solve(by, y)))

def svg_doc(W, H, view, label, title, defs, style, body, extra=''):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="{view}" role="img" aria-label="{label}"{extra}>'
            f'<title>{title}</title><defs>{glyph_defs()}{defs}</defs><style>{style}</style>{body}</svg>\n')

def write(name, svg):
    path = os.path.join(OUT, name)
    with open(path, 'w') as fh:
        fh.write(svg)
    print('wrote', os.path.relpath(path, ROOT), f'{round(os.path.getsize(path) / 1024)} KB')
