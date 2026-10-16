"""The README architecture diagram (docs/readme/architecture-{light,dark}.svg, 1200x660, static).

Console, agent, the edge (Caddy or Cloudflare Tunnel) with its routes, the server and PostgreSQL, and the updater
with GitHub Releases; connectors labelled with what flows between them. Text is outlined (see kit.py).
"""
from kit import GLYPHS, SANS, MONO, measure, text, svg_doc, write

C = {
    'light': dict(panel='#fbfbfc', pborder='#e7e9ee', box='#ffffff', bborder='#dfe2e8', ink='#15181d', sec='#586073',
                  faint='#9aa3b0', group='#c9ced7', line='#8a93a1', green='#1aa766', gsoft='#e8f6ef', amber='#c97c1e',
                  asoft='#fbf1e4', chip='#f1f2f5', shadow='rgba(20,30,55,.06)'),
    'dark': dict(panel='#0f1218', pborder='#232833', box='#161922', bborder='#2a303c', ink='#f2f4f8', sec='#9aa3b0',
                 faint='#6c7480', group='#39404e', line='#6c7480', green='#2bb979', gsoft='#13261d', amber='#e0973a',
                 asoft='#2a2015', chip='#1e222c', shadow='rgba(0,0,0,.35)'),
}

W, H = 1200, 660

def build(theme):
    GLYPHS.clear()
    c = C[theme]
    s = []
    A = s.append
    def box(x, y, w, h, title, sub, tag=None, accent=None, icon=None):
        A(f'<rect x="{x}" y="{y + 2}" width="{w}" height="{h}" rx="12" fill="{c["shadow"]}"/>')
        A(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="{c["box"]}" stroke="{c["bborder"]}"/>')
        if accent: A(f'<rect x="{x + 20}" y="{y + 24}" width="9" height="9" fill="{accent}"/>')
        tx = x + (40 if accent else 20)
        A(text(SANS(600), title, tx, y + 34, 18, c['ink']))
        yy = y + 60
        for line in (sub if isinstance(sub, list) else [sub]):
            A(text(SANS(400), line, x + 20, yy, 14, c['sec'])); yy += 21
        if tag:
            tw = measure(MONO(500), tag, 12) + 18
            A(f'<rect x="{x + 20}" y="{y + h - 36}" width="{tw:.1f}" height="22" rx="11" fill="{c["chip"]}"/>')
            A(text(MONO(500), tag, x + 29, y + h - 21, 12, c['sec']))
    def group(x, y, w, h, label):
        A(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="18" fill="none" stroke="{c["group"]}" stroke-dasharray="4 5"/>')
        lw = measure(MONO(500), label, 11.5, 0.12) + 16
        A(f'<rect x="{x + 18}" y="{y - 9}" width="{lw:.1f}" height="18" fill="{c["panel"]}"/>')
        A(text(MONO(500), label, x + 26, y + 4, 11.5, c['faint'], tr=0.12))
    def arrow(pts, col, dashed=False, start=False, end=True, width=1.5):
        d = 'M' + ' L'.join(f'{x},{y}' for x, y in pts)
        mk = f' marker-end="url(#ah-{col[1:]})"' if end else ''
        mk += f' marker-start="url(#as-{col[1:]})"' if start else ''
        da = ' stroke-dasharray="5 5"' if dashed else ''
        A(f'<path d="{d}" fill="none" stroke="{col}" stroke-width="{width}"{da}{mk} stroke-linejoin="round"/>')
    def label(x, y, s_, col, anchor='middle', bg=True):
        w = measure(MONO(500), s_, 12.5)
        bx = x - w / 2 if anchor == 'middle' else (x if anchor == 'start' else x - w)
        if bg: A(f'<rect x="{bx - 7:.1f}" y="{y - 14}" width="{w + 14:.1f}" height="20" rx="10" fill="{c["panel"]}"/>')
        A(text(MONO(500), s_, x, y, 12.5, col, anchor=anchor))

    cols = [c['line'], c['green'], c['amber']]
    defs = ''.join(
        f'<marker id="ah-{k[1:]}" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M1,1.5 L8.5,5 L1,8.5" fill="none" stroke="{k}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></marker>'
        f'<marker id="as-{k[1:]}" viewBox="0 0 10 10" refX="1.5" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M1,1.5 L8.5,5 L1,8.5" fill="none" stroke="{k}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></marker>'
        for k in cols)

    A(f'<rect x=".5" y=".5" width="{W - 1}" height="{H - 1}" rx="20" fill="{c["panel"]}" stroke="{c["pborder"]}"/>')

    # --- groups
    group(32, 52, 262, 214, 'YOUR BROWSER')
    group(32, 330, 262, 278, 'ANDROID DEVICES')
    group(420, 52, 504, 556, 'YOUR HOST · DOCKER OR NATIVE')
    group(1032, 332, 140, 276, 'GITHUB')

    # --- boxes
    box(52, 82, 222, 160, 'Console', ['React 19 · Vite', 'The admin web app.'], 'web/', accent=c['ink'])
    box(52, 360, 222, 224, 'Agent', ['Kotlin · Device Owner', 'Applies configurations,', 'reports device state, runs', 'remote actions.'], 'agent-android/', accent=c['green'])
    # edge (tall)
    ex, ey, ew, eh = 444, 82, 172, 502
    box(ex, ey, ew, eh, 'Edge', ['Caddy, auto-HTTPS', 'or Cloudflare Tunnel'], None)
    # routes inside edge
    ry = ey + 132
    A(text(MONO(500), 'ROUTES', ex + 20, ry, 11, c['faint'], tr=0.12))
    routes = [('/', 'console'), ('/rest', 'server'), ('/files', 'server'), ('/agent/ws', 'server'), ('/update', 'updater'), ('/recovery', 'updater')]
    for k, (p, to) in enumerate(routes):
        yy = ry + 30 + k * 30
        A(f'<line x1="{ex + 20}" y1="{yy + 9}" x2="{ex + ew - 20}" y2="{yy + 9}" stroke="{c["bborder"]}"/>')
        A(text(MONO(500), p, ex + 20, yy, 13, c['ink']))
        A(text(SANS(400), to, ex + ew - 20, yy, 13, c['sec'], anchor='end'))
    A(text(SANS(400), 'TLS ends here. A', ex + 20, ey + eh - 72, 13, c['sec']))
    A(text(SANS(400), 'native install uses', ex + 20, ey + eh - 52, 13, c['sec']))
    A(text(SANS(400), 'your own TLS proxy.', ex + 20, ey + eh - 32, 13, c['sec']))

    sx, sw = 676, 222
    box(sx, 82, sw, 156, 'Server', ['Java 17 · Jersey REST API', 'Devices, configurations', 'and commands.'], 'launcher.war', accent=c['ink'])
    # postgres cylinder
    py = 296
    pw = 178
    A(f'<rect x="{sx}" y="{py + 2}" width="{pw}" height="96" rx="12" fill="{c["shadow"]}"/>')
    A(f'<rect x="{sx}" y="{py}" width="{pw}" height="96" rx="12" fill="{c["box"]}" stroke="{c["bborder"]}"/>')
    cx0, cy0 = sx + 34, py + 30
    A(f'<path d="M{cx0 - 13},{cy0} v22 a13,5 0 0 0 26,0 v-22" fill="none" stroke="{c["sec"]}" stroke-width="1.5"/>'
      f'<ellipse cx="{cx0}" cy="{cy0}" rx="13" ry="5" fill="none" stroke="{c["sec"]}" stroke-width="1.5"/>'
      f'<path d="M{cx0 - 13},{cy0 + 11} a13,5 0 0 0 26,0" fill="none" stroke="{c["sec"]}" stroke-width="1.5"/>')
    A(text(SANS(600), 'PostgreSQL', sx + 60, py + 40, 18, c['ink']))
    A(text(SANS(400), 'All server data.', sx + 60, py + 62, 14, c['sec']))
    box(sx, 452, sw, 132, 'Updater', ['Supervisor · Node', 'Verifies and applies updates.'], 'minisign', accent=c['amber'])
    box(1050, 360, 104, 224, 'Releases', ['Signed', 'manifest,', 'server', 'images,', 'agent APK'], None)

    # --- connectors
    # console <-> edge
    arrow([(274, 150), (444, 150)], c['line'], start=True)
    label(359, 140, 'HTTPS · REST', c['sec'])
    # agent <-> edge : REST + config.apply, and the wake channel
    arrow([(274, 430), (444, 430)], c['line'], start=True)
    label(359, 420, 'REST check-in', c['sec'])
    arrow([(444, 478), (274, 478)], c['green'], width=1.8)
    label(359, 468, 'config.apply', c['green'])
    arrow([(444, 526), (274, 526)], c['green'], dashed=True, width=1.8)
    label(359, 516, 'WebSocket wake', c['green'])
    # edge -> server, edge -> updater
    arrow([(ex + ew, 160), (sx, 160)], c['line'])
    arrow([(ex + ew, 518), (sx, 518)], c['line'])
    # server <-> postgres
    arrow([(sx + pw / 2, 238), (sx + pw / 2, 296)], c['line'], start=True)
    label(sx + pw / 2 + 12, 272, 'SQL', c['sec'], anchor='start', bg=False)
    # updater -> server (applies update), routed on the right
    arrow([(sx + 200, 452), (sx + 200, 238)], c['amber'], dashed=True)
    # releases -> updater
    arrow([(1050, 520), (sx + sw, 520)], c['amber'], width=1.8)
    label((1050 + sx + sw) / 2, 510, 'signed manifest', c['amber'])

    # legend
    lx, ly = 52, 636
    A(f'<line x1="{lx}" y1="{ly - 4}" x2="{lx + 26}" y2="{ly - 4}" stroke="{c["green"]}" stroke-width="1.8"/>')
    A(text(SANS(400), 'to and from devices', lx + 34, ly, 13, c['sec']))
    lx2 = lx + 34 + measure(SANS(400), 'to and from devices', 13) + 28
    A(f'<line x1="{lx2}" y1="{ly - 4}" x2="{lx2 + 26}" y2="{ly - 4}" stroke="{c["amber"]}" stroke-width="1.8"/>')
    A(text(SANS(400), 'signed updates', lx2 + 34, ly, 13, c['sec']))
    lx3 = lx2 + 34 + measure(SANS(400), 'signed updates', 13) + 28
    A(f'<line x1="{lx3}" y1="{ly - 4}" x2="{lx3 + 26}" y2="{ly - 4}" stroke="{c["line"]}" stroke-width="1.6" stroke-dasharray="5 5"/>')
    A(text(SANS(400), 'dashed: only when needed', lx3 + 34, ly, 13, c['sec']))

    label_ = ('MDMesh architecture: the console and the agents reach the server through Caddy or a Cloudflare Tunnel; '
              'the server keeps its data in PostgreSQL; the updater verifies minisign-signed releases from GitHub and applies them.')
    write(f'architecture-{theme}.svg', svg_doc(W, H, f'0 0 {W} {H}', label_, 'MDMesh architecture', defs, '', ''.join(s)))

if __name__ == '__main__':
    for t in C:
        build(t)
