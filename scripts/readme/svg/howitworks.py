"""The README "How it works" animation (docs/readme/how-it-works-{light,dark}.svg, shown 1200x520, viewBox 2400x1040,
loops every 6150 ms).

A configuration change leaves the console's "Save and apply" and reaches a device: the packet travels a dotted line
through four nodes (a deliberate pace through the first two, then a smooth acceleration), passes behind the phone,
bursts into small status dots, and "Applied in 4s" lands. After a hold, the line clears from left to right and loops.

The console panel and the phone are raster parts from compose.mjs (tilted toward each other with real CSS 3D),
embedded as WebP data URIs; .cache/parts/hiw-geom.json gives the projected anchors the line attaches to.
Everything else is vector. Times are absolute ms on one cycle; node times are solved from the motion curves so each
node lights up (and later fades) exactly when the packet's centre (the sweep edge) crosses it.
The base styles are the finished state, so prefers-reduced-motion (animation: none) shows it applied, without motion.
Part names (classes) match docs in ../README.md, for porting to the console.
"""
import base64, json, math, os
from kit import (TOK, MONO, GLYPHS, CACHE, f, measure, text, status_pill, grid, vignette, shadow_filter,
                 kf, bz, bezier, HOLD, svg_doc, write)

W, H, M = 2400, 1040, 24
NODES = 4
OUT_E, EASE, IN = bz((.2, .7, .2, 1)), bz((.45, 0, .25, 1)), bz((.5, 0, .75, 0))
STAGE1 = (.38, 0, .8, .66)          # deliberate first segment, ends at its peak speed

class Motion:
    """Position along the path (0..1) over time; each segment is one cubic-bezier."""
    def __init__(self, segs):
        self.segs = segs   # [(t0, t1, p0, p1, bezier)]
    def time_of(self, p):
        for t0, t1, p0, p1, c in self.segs:
            if p0 <= p <= p1:
                return t0 + (t1 - t0) * bezier(*c)[1]((p - p0) / (p1 - p0))
        raise ValueError(p)
    def stops(self, fmt):
        out = [(t0, fmt(p0), bz(c)) for t0, t1, p0, p1, c in self.segs]
        return out + [(self.segs[-1][1], fmt(self.segs[-1][3]), HOLD)]

def staged(t0, T1, T2, p_join):
    """Two segments joined at p_join with continuous speed: segment 2's y1 is solved so it starts at segment 1's end speed."""
    v1 = (1 - STAGE1[3]) / (1 - STAGE1[2]) * p_join / T1
    a = .32
    s2 = (a, round(v1 * T2 / (1 - p_join) * a, 4), .86, .62)
    return Motion([(t0, t0 + T1, 0, p_join, STAGE1), (t0 + T1, t0 + T1 + T2, p_join, 1, s2)])

def timing(x1, px):
    Lr = px - x1
    nodes = [x1 + Lr * i / (NODES + 1) for i in range(1, NODES + 1)]
    end_x = px + 40                                   # the packet stops 40 units behind the phone's edge
    L = end_x - x1
    p2 = (nodes[1] - x1) / L
    t = dict(label_end=320, spawn=250)
    travel = staged(t['spawn'], 850, 545, p2)
    t['nodes'] = [round(travel.time_of((n - x1) / L)) for n in nodes]
    t['grow_end'] = t['nodes'][0] - 90                # full size just before node 1
    t['behind'] = travel.segs[-1][1]
    t['collide'] = round(travel.time_of((px - x1) / L))
    t['burst_end'] = t['collide'] + 720
    t['check_in'], t['check_end'] = t['collide'] + 120, t['collide'] + 460
    t['applied_in'], t['applied_end'] = t['collide'] + 300, t['collide'] + 680
    t['sweep'] = t['applied_end'] + 1700              # hold on the finished state
    sweep = staged(t['sweep'], 715, 440, p2)
    t['node_out'] = [round(sweep.time_of((n - x1) / L)) for n in nodes]
    t['final_out'] = round(sweep.time_of((px - x1) / L))
    t['final_end'] = t['final_out'] + 280
    t['applied_out_end'] = t['final_end'] + 80
    t['cycle'] = t['applied_out_end'] + 650           # pause, then loop
    return t, nodes, end_x, travel, sweep

def data_uri(path):
    with open(path, 'rb') as fh:
        return 'data:image/webp;base64,' + base64.b64encode(fh.read()).decode()

def build(theme, geom):
    GLYPHS.clear(); tk = TOK[theme]; dark = theme == 'dark'
    g = geom[theme]
    x1, by, px = g['aC']['x'], g['aC']['y'], g['aP']['x']
    t, nodes, end_x, travel, sweep = timing(x1, px)
    D = t['cycle']
    k = 1.75                                          # console CSS px -> viewBox units for the chip and pill
    green, track = tk['online'], tk['border_strong']
    css = []
    def add(cls, stops, origin='center'):
        css.append(kf(cls, D, stops))
        css.append(f'.{cls}{{animation:{cls} {D}ms linear infinite both;transform-box:fill-box;transform-origin:{origin}}}')
    hide = lambda cls: css.append(f'.{cls}{{opacity:0}}')

    add('hiw-label', [(0, 'opacity:0;transform:translateY(6px)', OUT_E), (t['label_end'], 'opacity:1;transform:none', HOLD),
                      (t['final_out'], 'opacity:1;transform:none', IN), (t['final_end'], 'opacity:0;transform:none', HOLD)])
    add('hiw-press', [(0, 'opacity:.7;transform:scale(1)', OUT_E), (520, 'opacity:0;transform:scale(3)', HOLD)]); hide('hiw-press')
    add('hiw-packet', [(0, 'opacity:0;transform:translateX(0)', HOLD)]
        + travel.stops(lambda p: f'opacity:1;transform:translateX({f(p * (end_x - x1))}px)')
        + [(t['behind'] + 1, f'opacity:0;transform:translateX({f(end_x - x1)}px)', HOLD)]); hide('hiw-packet')
    add('hiw-packet-dot', [(0, 'transform:scale(.3)', HOLD), (t['spawn'], 'transform:scale(.3)', OUT_E), (t['grow_end'], 'transform:scale(1)', HOLD)])
    add('hiw-draw', [(0, 'transform:scaleX(0)', HOLD)] + travel.stops(lambda p: f'transform:scaleX({p:.4f})'), origin='left center')
    add('hiw-erase', [(0, 'transform:scaleX(1)', HOLD)] + sweep.stops(lambda p: f'transform:scaleX({1 - p:.4f})'), origin='right center')
    for i, (ta, to) in enumerate(zip(t['nodes'], t['node_out'])):
        add(f'hiw-node-{i}', [(0, 'opacity:0;transform:scale(.5)', HOLD), (ta - 20, 'opacity:0;transform:scale(.5)', OUT_E),
                              (ta + 120, 'opacity:1;transform:scale(1.28)', EASE), (ta + 300, 'opacity:1;transform:scale(1)', HOLD),
                              (to, 'opacity:1;transform:scale(1)', IN), (to + 260, 'opacity:0;transform:scale(.4)', HOLD)])
        add(f'hiw-ring-{i}', [(0, 'opacity:0;transform:scale(1)', HOLD), (ta, 'opacity:.6;transform:scale(1)', OUT_E),
                              (ta + 560, 'opacity:0;transform:scale(2.6)', HOLD)]); hide(f'hiw-ring-{i}')
    # burst: (angle deg, distance, radius), out from behind the phone's edge
    BURST = [(-160, 118, 7), (-134, 96, 5), (-184, 132, 8.5), (-207, 104, 6), (-229, 84, 4.5), (-114, 74, 4), (-248, 66, 4), (-171, 82, 4.5), (-146, 56, 3.5)]
    burst = []
    for j, (ang, dist, r) in enumerate(BURST):
        dx, dy = math.cos(math.radians(ang)) * dist, math.sin(math.radians(ang)) * dist
        delay = (j % 3) * 30
        add(f'hiw-burst-{j}', [(0, 'opacity:0;transform:translate(0,0) scale(.6)', HOLD),
                               (t['collide'] + delay, 'opacity:1;transform:translate(0,0) scale(1)', OUT_E),
                               (t['burst_end'] + delay, f'opacity:0;transform:translate({f(dx)}px,{f(dy)}px) scale(.5)', HOLD)]); hide(f'hiw-burst-{j}')
        burst.append(f'<circle class="hiw-burst-{j}" cx="{f(px + 6)}" cy="{f(by)}" r="{r}" fill="{green}" fill-opacity="{min(1, .55 + .45 * r / 6.5):.2f}"/>')
    add('hiw-check', [(0, 'opacity:0;transform:scale(.4)', HOLD), (t['check_in'], 'opacity:0;transform:scale(.4)', OUT_E),
                      (t['check_in'] + 220, 'opacity:1;transform:scale(1.08)', EASE), (t['check_end'], 'opacity:1;transform:scale(1)', HOLD),
                      (t['final_out'], 'opacity:1;transform:scale(1)', IN), (t['final_end'], 'opacity:0;transform:scale(.9)', HOLD)])
    add('hiw-ripple', [(0, 'opacity:0;transform:scale(1)', HOLD), (t['check_in'] + 160, 'opacity:.55;transform:scale(1)', OUT_E),
                       (t['check_in'] + 760, 'opacity:0;transform:scale(2.2)', HOLD)]); hide('hiw-ripple')
    add('hiw-applied', [(0, 'opacity:0;transform:translateY(10px)', HOLD), (t['applied_in'], 'opacity:0;transform:translateY(10px)', OUT_E),
                        (t['applied_end'], 'opacity:1;transform:none', HOLD), (t['final_out'] + 60, 'opacity:1;transform:none', IN),
                        (t['applied_out_end'], 'opacity:0;transform:none', HOLD)])
    css.append('@media (prefers-reduced-motion:reduce){*{animation:none!important}}')

    # ---------- drawing, back to front ----------
    cc, pc = g['consClip'], g['phoneClip']
    console = f'<image class="hiw-console" href="{data_uri(os.path.join(CACHE, "parts", f"hiw-console-{theme}.webp"))}" x="{cc["x"]}" y="{cc["y"]}" width="{cc["width"]}" height="{cc["height"]}"/>'
    phone = f'<image class="hiw-phone" href="{data_uri(os.path.join(CACHE, "parts", f"hiw-phone-{theme}.webp"))}" x="{pc["x"]}" y="{pc["y"]}" width="{pc["width"]}" height="{pc["height"]}"/>'
    track_line = f'<line class="hiw-track" x1="{f(x1)}" y1="{f(by)}" x2="{f(px)}" y2="{f(by)}" stroke="{track}" stroke-width="2.5" stroke-dasharray="2 10" stroke-linecap="round"/>'
    active = (f'<g class="hiw-draw"><g class="hiw-erase"><line x1="{f(x1)}" y1="{f(by)}" x2="{f(end_x)}" y2="{f(by)}" stroke="{green}" '
              f'stroke-opacity=".55" stroke-width="3"/></g></g>')
    idle = ''.join(f'<circle class="hiw-idle" cx="{f(n)}" cy="{f(by)}" r="8" fill="{tk["surface"]}" stroke="{track}" stroke-width="2.5"/>' for n in nodes)
    lit = ''.join(f'<circle class="hiw-ring-{i}" cx="{f(n)}" cy="{f(by)}" r="10" fill="none" stroke="{green}" stroke-width="2.5"/>'
                  f'<g class="hiw-node-{i}"><circle cx="{f(n)}" cy="{f(by)}" r="15" fill="{green}" fill-opacity=".14"/>'
                  f'<circle cx="{f(n)}" cy="{f(by)}" r="9" fill="{green}"/></g>' for i, n in enumerate(nodes))
    origin = (f'<g class="hiw-origin"><circle cx="{f(x1)}" cy="{f(by)}" r="9" fill="{tk["surface"]}" stroke="{green}" stroke-width="3.5"/>'
              f'<circle class="hiw-press" cx="{f(x1)}" cy="{f(by)}" r="9" fill="none" stroke="{green}" stroke-width="3"/></g>')
    packet = (f'<g class="hiw-packet"><g class="hiw-packet-dot"><circle cx="{f(x1)}" cy="{f(by)}" r="20" fill="{green}" fill-opacity=".18"/>'
              f'<circle cx="{f(x1)}" cy="{f(by)}" r="11" fill="{green}"/></g></g>')
    # the "config.apply · 3 changes" chip: an outline badge, mono, centred over the line
    lab, sub = 'config.apply', '· 3 changes'
    fs = 12.5 * k
    lw, sw = measure(MONO(500), lab, fs), measure(MONO(400), sub, fs)
    cwid, chh = 18 * k + 14 * k + lw + 8 * k + sw, fs * 1.5 + 4 * k
    cx, cy0 = (x1 + px) / 2 - cwid / 2, by - 60 - chh
    chip = (f'<g class="hiw-label"><rect x="{f(cx)}" y="{f(cy0 + 10)}" width="{f(cwid)}" height="{f(chh)}" rx="{f(chh / 2)}" fill="{tk["shadow"]}" fill-opacity="{tk["shadow_op"]}" filter="url(#sh)"/>'
            f'<rect x="{f(cx + k / 2)}" y="{f(cy0 + k / 2)}" width="{f(cwid - k)}" height="{f(chh - k)}" rx="{f((chh - k) / 2)}" fill="{tk["surface"]}" stroke="{tk["border_strong"]}" stroke-width="{f(k)}"/>'
            f'<rect x="{f(cx + 9 * k)}" y="{f(cy0 + chh / 2 - 3.5 * k)}" width="{f(7 * k)}" height="{f(7 * k)}" rx="{f(k)}" fill="{green}"/>'
            + text(MONO(500), lab, cx + 23 * k, cy0 + chh / 2 + fs * .36, fs, tk['text'])
            + text(MONO(400), sub, cx + 23 * k + lw + 8 * k, cy0 + chh / 2 + fs * .36, fs, tk['secondary']) + '</g>')
    ck = px - 4
    check = (f'<g class="hiw-ripple"><circle cx="{f(ck)}" cy="{f(by)}" r="34" fill="none" stroke="{green}" stroke-width="3"/></g>'
             f'<g class="hiw-check"><circle cx="{f(ck)}" cy="{f(by)}" r="46" fill="{green}" fill-opacity=".12"/>'
             f'<circle cx="{f(ck)}" cy="{f(by)}" r="32" fill="{green}"/>'
             f'<path d="M{f(ck - 13)} {f(by + 1)}l9 9 18-19" fill="none" stroke="#fff" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/></g>')
    _, apw, aph = status_pill(tk, 'ok', 'Applied', 0, 0, k, tail='in 4s')
    ax, ay = ck - apw - 16, by + 58
    pill, _, _ = status_pill(tk, 'ok', 'Applied', ax, ay, k, tail='in 4s')
    applied = (f'<g class="hiw-applied"><rect x="{f(ax)}" y="{f(ay + 8)}" width="{f(apw)}" height="{f(aph)}" rx="{f(aph / 2)}" fill="{tk["shadow"]}" '
               f'fill-opacity="{tk["shadow_op"] * .8}" filter="url(#sh)"/>{pill}</g>')
    note = text(MONO(400), 'Phone: the agent’s status screen, drawn from its layout code', W - M - 36, H - M - 26, 17, tk['muted'], anchor='end')
    gd, gb = grid(tk, W - 2 * M, H - 2 * M, cell=16, mask_id='gm', cx=(W - 2 * M) * .62, rx=(W - 2 * M) * .5, ry=(H - 2 * M) * .7)
    vd, vb = vignette(tk, W - 2 * M, H - 2 * M)
    card = (f'<g class="hiw-card"><rect x="{M}" y="{M + 6}" width="{W - 2 * M}" height="{H - 2 * M}" rx="32" fill="{tk["shadow"]}" fill-opacity="{.45 if dark else .10}" filter="url(#sh2)"/>'
            f'<rect x="{M}" y="{M}" width="{W - 2 * M}" height="{H - 2 * M}" rx="32" fill="url(#cardbg)"/>'
            f'<g transform="translate({M} {M})" clip-path="url(#cardclip)">{gb}<rect width="{W - 2 * M}" height="{H - 2 * M}" fill="url(#glow)"/>{vb}</g>'
            f'<rect x="{M + .75}" y="{M + .75}" width="{W - 2 * M - 1.5}" height="{H - 2 * M - 1.5}" rx="31.25" fill="none" stroke="{"rgba(255,255,255,.07)" if dark else tk["frame"]}" stroke-width="1.5"/></g>')
    defs = (gd + vd + shadow_filter(1.4) +
            f'<filter id="sh2" x="-5%" y="-10%" width="110%" height="125%"><feGaussianBlur stdDeviation="14"/></filter>'
            f'<linearGradient id="cardbg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{tk["bg_top"]}"/><stop offset="1" stop-color="{tk["bg_bot"]}"/></linearGradient>'
            f'<radialGradient id="glow" cx="78%" cy="58%" r="40%"><stop offset="0" stop-color="{green}" stop-opacity="{.11 if dark else .08}"/><stop offset="1" stop-color="{green}" stop-opacity="0"/></radialGradient>'
            f'<clipPath id="cardclip"><rect width="{W - 2 * M}" height="{H - 2 * M}" rx="32"/></clipPath>')
    # Layer order: the phone is drawn after the packet and the burst, which hides them "behind" it.
    body = f'{card}{console}{track_line}{active}{idle}{lit}{origin}{packet}<g class="hiw-burst">{"".join(burst)}</g>{phone}{chip}{check}{applied}{note}'
    write(f'how-it-works-{theme}.svg', svg_doc(W // 2, H // 2, f'0 0 {W} {H}',
          'A configuration change travels from the console to a managed Android device and is applied',
          'How it works: config.apply from the console to the device', defs, ''.join(css), body))
    return t

if __name__ == '__main__':
    with open(os.path.join(CACHE, 'parts', 'hiw-geom.json')) as fh:
        geom = json.load(fh)
    for theme in TOK:
        t = build(theme, geom)
    print('how-it-works timing:', {k: t[k] for k in ('nodes', 'collide', 'sweep', 'node_out', 'cycle')})
