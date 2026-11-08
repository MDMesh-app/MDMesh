"""The README banner (docs/readme/banner-{light,dark}.svg, 1200x300, plays once, 5950 ms).

■ MDMesh and a stack of three console status cards. Sequence: the Signature card enters in its loading state and
verifies (green colour, the chip's edge whooshes right and carries the version in); then Needs attention arrives in
the bottom slot and pushes it up; push 1 comes fully to rest, a 300 ms hold, then Online arrives the same way.
Final order, top to bottom: Signature verified, Needs attention, Online.

Motion model: the three cards sit at their final positions inside two nested translate groups, stack-p1 and stack-p2
(one per push). Before push 1 the groups hold d1 + d2 of offset, so Signature shows in the bottom slot, and the later
cards ride the groups while invisible, arriving in exact lockstep. Cards only animate opacity. Every move uses
`butter` (starts from rest, long deceleration), every fade uses `fade`. The base styles are the finished state, so
prefers-reduced-motion (animation: none) shows the settled banner. Spec: docs in this folder's README.md.
"""
from kit import (TOK, SANS, MONO, GLYPHS, CHECK_D, f, measure, text, dot, shadow_filter, grid, vignette, wordmark,
                 kf, bz, HOLD, svg_doc, write)

W, H, K = 1200, 300, 1.35          # K: console CSS px -> banner units
VERSION = '0.4.0'                  # the running release; keep in step with scripts/readme/versions.mjs
BUTTER, FADE = bz((.3, 0, .1, 1)), bz((.4, 0, .2, 1))
RISE = 12
# Timeline (ms). Durations: rise 990, fade 1210, green 660, edge 836, push 968, card fades 1265.
T = dict(sig_in=165, sig_rise_end=1155, sig_fade_end=1375,
         confirm=2145, green_end=2805, load_out_end=2332, oktx=2288, oktx_end=2750, check=2310, check_end=2882,
         edge=2189, edge_end=3025,
         push1=3377, push1_end=4345, attn_fade_end=4642,
         push2=4645, push2_end=5613, online_fade_end=5910)
D = 5950

def card_rects(t, x, y, w, h, cls):
    r = 14 * K
    return (f'<g class="{cls}-sh"><rect x="{f(x)}" y="{f(y + 8 * K)}" width="{f(w)}" height="{f(h)}" rx="{f(r)}" '
            f'fill="{t["shadow"]}" fill-opacity="{t["shadow_op"]}" filter="url(#sh)"/></g>'
            f'<rect x="{f(x + K / 2)}" y="{f(y + K / 2)}" width="{f(w - K)}" height="{f(h - K)}" rx="{f(r)}" '
            f'fill="{t["surface"]}" stroke="{t["border"]}" stroke-width="{f(K)}"/>')

def stat_dims(label, value):
    fs, fv = 13.5 * K, 15 * K
    return (16 * K * 2 + 17 * K + measure(SANS(500), label, fs) + 14 * K + measure(MONO(500), value, fv), 22 * K + fs * 1.5)

def stat_card(t, x, y, tone, shape, label, value, cls):
    """Fleet-legend row in a card: 8 px mark, label (text-2, 13.5/500), count (mono 500, ink)."""
    w, h = stat_dims(label, value)
    fs, fv = 13.5 * K, 15 * K
    cy = y + h / 2
    tx = x + 33 * K
    inner = dot(t, tone, shape, x + 20 * K, cy, K, size=8)
    inner += text(SANS(500), label, tx, cy + fs * .36, fs, t['text2'])
    inner += text(MONO(500), value, tx + measure(SANS(500), label, fs) + 14 * K, cy + fv * .36, fv, t['ink'])
    return f'<g class="{cls}">{card_rects(t, x, y, w, h, cls)}{inner}</g>'

def sig_geom():
    fs = 12.5 * K
    pill = lambda label: (40 * K + measure(SANS(500), label, fs), fs * 1.5 + 6 * K)
    (w_ok, ph), (w_load, _) = pill('Signature verified'), pill('Verifying signature…')
    padx, pady = 14 * K, 11 * K
    w0 = padx * 2 + w_load
    w1 = padx * 2 + w_ok + 10 * K + measure(MONO(500), VERSION, 13 * K)
    return dict(padx=padx, pady=pady, w_ok=w_ok, w_load=w_load, ph=ph, w0=w0, w1=w1, h=pady * 2 + ph,
                slide=w0 - (padx + w_ok + 10 * K))   # chip's starting right edge minus the version's final left edge

def sig_card(t, x, y, g):
    padx, pady, ph = g['padx'], g['pady'], g['ph']
    r, fs = 14 * K, 12.5 * K
    px, py = x + padx, y + pady
    cy, ix, tx = py + ph / 2, px + 15 * K, px + 27 * K
    out = (f'<rect class="sig-shadow" x="{f(x)}" y="{f(y + 8 * K)}" width="{f(g["w1"])}" height="{f(g["h"])}" rx="{f(r)}" '
           f'fill="{t["shadow"]}" fill-opacity="{t["shadow_op"]}" filter="url(#sh)"/>'
           f'<rect class="sig-body" x="{f(x + K / 2)}" y="{f(y + K / 2)}" width="{f(g["w1"] - K)}" height="{f(g["h"] - K)}" rx="{f(r)}" '
           f'fill="{t["surface"]}" stroke="{t["border"]}" stroke-width="{f(K)}"/>'
           f'<rect class="sig-pill" x="{f(px + K / 2)}" y="{f(py + K / 2)}" width="{f(g["w_ok"] - K)}" height="{f(ph - K)}" rx="{f((ph - K) / 2)}" '
           f'fill="{t["ok_soft"]}" stroke="{t["ok_line"]}" stroke-width="{f(K)}"/>')
    out += (f'<g class="load"><g class="spin"><circle cx="{f(ix)}" cy="{f(cy)}" r="{f(5 * K)}" fill="none" stroke="{t["idle_line"]}" stroke-width="{f(1.6 * K)}"/>'
            f'<circle cx="{f(ix)}" cy="{f(cy)}" r="{f(5 * K)}" fill="none" stroke="{t["idle_ink"]}" stroke-width="{f(1.6 * K)}" '
            f'stroke-linecap="round" pathLength="100" stroke-dasharray="28 100"/></g>'
            + text(SANS(500), 'Verifying signature…', tx, cy + fs * .36, fs, t['idle_ink']) + '</g>')
    out += (f'<g class="oktx"><path class="check" d="{CHECK_D}" pathLength="1" transform="translate({f(ix - 8 * K * .78)} {f(cy - 8 * K * .78)}) scale({f(.78 * K)})" '
            f'fill="none" stroke="{t["ok_ink"]}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
            + text(SANS(500), 'Signature verified', tx, cy + fs * .36, fs, t['ok_ink']) + '</g>')
    out += (f'<clipPath id="sigclip"><rect class="sig-clip" x="{f(x + K)}" y="{f(y + K)}" width="{f(g["w1"] - 2 * K)}" height="{f(g["h"] - 2 * K)}" rx="{f(r - K)}"/></clipPath>'
            f'<g clip-path="url(#sigclip)"><g class="ver">' + text(MONO(500), VERSION, px + g['w_ok'] + 10 * K, cy + 13 * K * .36, 13 * K, t['secondary']) + '</g></g>')
    return out

def styles(t, g, d1, d2):
    css = []
    def add(cls, stops, extra=''):
        css.append(kf(cls, D, stops)); css.append(f'.{cls}{{animation:{cls} {D}ms linear 1 both;{extra}}}')
    y = lambda v: f'transform:translateY({f(v)}px)'
    op = lambda v: f'opacity:{v}'
    add('stack-p1', [(0, y(d1), HOLD), (T['push1'], y(d1), BUTTER), (T['push1_end'], y(0), HOLD)])
    add('stack-p2', [(0, y(d2), HOLD), (T['push2'], y(d2), BUTTER), (T['push2_end'], y(0), HOLD)])
    add('sig-rise', [(0, y(RISE), HOLD), (T['sig_in'], y(RISE), BUTTER), (T['sig_rise_end'], y(0), HOLD)])
    add('sig-op', [(0, op(0), HOLD), (T['sig_in'], op(0), FADE), (T['sig_fade_end'], op(1), HOLD)])
    add('attn-op', [(0, op(0), HOLD), (T['push1'], op(0), FADE), (T['attn_fade_end'], op(1), HOLD)])
    add('online-op', [(0, op(0), HOLD), (T['push2'], op(0), FADE), (T['online_fade_end'], op(1), HOLD)])
    pill = lambda fill, line, w: f'fill:{fill};stroke:{line};width:{f(w)}px'
    add('sig-pill', [(0, pill(t['idle_soft'], t['idle_line'], g['w_load'] - K), HOLD),
                     (T['confirm'], pill(t['idle_soft'], t['idle_line'], g['w_load'] - K), BUTTER),
                     (T['green_end'], pill(t['ok_soft'], t['ok_line'], g['w_ok'] - K), HOLD)])
    add('load', [(0, op(1), HOLD), (T['confirm'], op(1), FADE), (T['load_out_end'], op(0), HOLD)])
    css.append('.load{opacity:0}')
    css.append('@keyframes spin{to{transform:rotate(360deg)}}.spin{transform-box:fill-box;transform-origin:center;animation:spin 1.1s linear infinite}')
    add('oktx', [(0, op(0), HOLD), (T['oktx'], op(0), FADE), (T['oktx_end'], op(1), HOLD)])
    dash = lambda v: f'stroke-dasharray:1;stroke-dashoffset:{v}'
    add('check', [(0, dash(1), HOLD), (T['check'], dash(1), BUTTER), (T['check_end'], dash(0), HOLD)])
    for cls, a, b in (('sig-body', g['w0'] - K, g['w1'] - K), ('sig-shadow', g['w0'], g['w1']), ('sig-clip', g['w0'] - 2 * K, g['w1'] - 2 * K)):
        add(cls, [(0, f'width:{f(a)}px', HOLD), (T['edge'], f'width:{f(a)}px', BUTTER), (T['edge_end'], f'width:{f(b)}px', HOLD)])
    sx = lambda v: f'transform:translateX({f(v)}px)'
    add('ver', [(0, sx(g['slide']), HOLD), (T['edge'], sx(g['slide']), BUTTER), (T['edge_end'], 'transform:none', HOLD)])
    css.append('@media (prefers-reduced-motion:reduce){*{animation:none!important}}')
    return ''.join(css)

def build(theme):
    GLYPHS.clear(); t = TOK[theme]
    g = sig_geom()
    w1, h1 = stat_dims('Online', '211'); w2, h2 = stat_dims('Needs attention', '3')
    wm, _ = wordmark(t, 92, 167, 80)
    tag = text(MONO(500), 'SELF-HOSTED ANDROID MDM', 96, 222, 15, t['secondary'], tr=.16)
    gap = 12
    bottom = (H + h1 + h2 + g['h'] + 2 * gap) / 2      # the stack's fixed bottom edge
    left = W - 84 - max(w1, w2, g['w1'])
    y_on = bottom - h1; y_at = y_on - gap - h2; y_sig = y_at - gap - g['h']
    stack = (f'<g class="stack-p2"><g class="stack-p1">'
             f'<g class="sig-op"><g class="sig-rise">{sig_card(t, left, y_sig, g)}</g></g>'
             f'<g class="attn-op">{stat_card(t, left, y_at, "warn", "diamond", "Needs attention", "3", "attn")}</g>'
             f'<g class="online-op">{stat_card(t, left, y_on, "ok", "circle", "Online", "211", "online")}</g></g></g>')
    css = styles(t, g, h2 + gap, h1 + gap)
    gd, gb = grid(t, W, H, cell=14, recede=.35, vp_y=-1400, rx=W * .62, ry=H * .95)
    vd, vb = vignette(t, W, H)
    defs = (gd + vd + shadow_filter(1.2) +
            f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{t["bg_top"]}"/><stop offset="1" stop-color="{t["bg_bot"]}"/></linearGradient>'
            f'<radialGradient id="glow" cx="72%" cy="60%" r="45%"><stop offset="0" stop-color="{t["online"]}" stop-opacity="{.07 if theme == "light" else .10}"/><stop offset="1" stop-color="{t["online"]}" stop-opacity="0"/></radialGradient>'
            f'<clipPath id="clip"><rect width="{W}" height="{H}" rx="20"/></clipPath>')
    body = (f'<g clip-path="url(#clip)"><rect width="{W}" height="{H}" fill="url(#bg)"/>{gb}<rect width="{W}" height="{H}" fill="url(#glow)"/>{vb}'
            f'{wm}{tag}{stack}</g><rect x=".5" y=".5" width="{W - 1}" height="{H - 1}" rx="19.5" fill="none" stroke="{t["frame"]}"/>')
    write(f'banner-{theme}.svg', svg_doc(W, H, f'0 0 {W} {H}', 'MDMesh', 'MDMesh', defs, css, body))

if __name__ == '__main__':
    for theme in TOK:
        build(theme)
