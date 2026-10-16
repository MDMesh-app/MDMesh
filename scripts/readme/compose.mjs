// Step 2: compose the raster README images from the captures (capture.mjs), in light and dark.
//   docs/readme/hero-<theme>.webp       flat cutout of the Overview screen in a soft card, with two floating
//                                        status cards drawn with the console's own component styles
//   docs/readme/features-<theme>.webp   the bento grid: six tiles, each a crop of one real card
//   .cache/parts/hiw-{console,phone}-<theme>.webp + hiw-geom.json
//                                        the "How it works" parts, tilted toward each other with real CSS 3D,
//                                        and the projected anchors svg/howitworks.py attaches the line to
// The phone shows the agent's own status screen (agent-android MainActivity), drawn from its layout code.
import fs from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';
import { OUT, PARTS, SHOTS, THEMES, T, closeBrowser, fontCSS, openPage, readJSON, shot } from './common.mjs';

const CARDS = readJSON(path.join(SHOTS, 'cards.json'));

// ---------- shared styles ----------
/** Engraved hairline grid (dark line + light line 1px below/right), radial fade to every edge, soft inner shadow. */
function gridCSS(theme, { recede = 0, cell = 14, mask = 'ellipse 62% 68% at 58% 58%' } = {}) {
  const dark = theme === 'dark';
  const D = dark ? 'rgba(0,0,0,.55)' : 'rgba(21,24,29,.05)';
  const L = dark ? 'rgba(255,255,255,.04)' : 'rgba(255,255,255,.9)';
  const sh = T[theme].shadow;
  return `
.sg{position:absolute;inset:${recede ? '-12% -12% 0 -12%' : '0'};pointer-events:none;
  background-image:linear-gradient(${D} 1px,transparent 1px),linear-gradient(90deg,${D} 1px,transparent 1px),
    linear-gradient(transparent 1px,${L} 1px,${L} 2px,transparent 2px),linear-gradient(90deg,transparent 1px,${L} 1px,${L} 2px,transparent 2px);
  background-size:${cell}px ${cell}px;background-position:center bottom;
  ${recede ? `transform:perspective(1400px) rotateX(${recede}deg);transform-origin:50% 100%;` : ''}
  -webkit-mask-image:radial-gradient(${mask},#000 0%,rgba(0,0,0,.45) 45%,transparent 85%)}
.sgv{position:absolute;inset:0;pointer-events:none;border-radius:inherit;
  box-shadow:inset 0 1px 8px rgba(${sh},${dark ? '.35' : '.045'}),inset 0 0 80px rgba(${sh},${dark ? '.30' : '.035'})}`;
}
const tileBg = (dark) => (dark ? 'linear-gradient(180deg,#14171e,#111319)' : 'linear-gradient(180deg,#ffffff,#fafbfc)');
const tileRing = (dark) => (dark ? 'rgba(255,255,255,.07)' : 'rgba(21,24,29,.07)');

function page(theme, w, h, css, body) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>${fontCSS()}
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:${w}px;height:${h}px;overflow:hidden;background:transparent;font-family:'IBM Plex Sans',sans-serif;-webkit-font-smoothing:antialiased}
${css}</style></head><body>${body}</body></html>`;
}

async function webp(pngBuffer, file, quality = 86) {
  await sharp(pngBuffer).webp({ quality, alphaQuality: 90, effort: 6, smartSubsample: true }).toFile(file);
  console.log('wrote', path.relative(process.cwd(), file), `${Math.round(fs.statSync(file).size / 1024)} KB`);
}

// ---------- 1. hero: the Overview screen as a flat cutout, with two floating console status cards ----------
function heroHTML(theme) {
  const c = T[theme]; const dark = theme === 'dark';
  const W = 2400, H = 1300, M = 24, zoom = 2, ox = 112, oy = 112, k = 2;
  const card = `.scard{position:absolute;display:inline-flex;align-items:center;gap:${14 * k}px;padding:${11 * k}px ${16 * k}px;background:${c.surface};
  border:${k}px solid ${c.border};border-radius:${14 * k}px;box-shadow:${c.shadowFloat.replace(/(\d+)px/g, (m, n) => `${n * k}px`)};line-height:1.5;white-space:nowrap}
.lab{display:inline-flex;align-items:center;gap:${9 * k}px;font-size:${13.5 * k}px;font-weight:500;color:${c.text2}}
.val{font-family:'IBM Plex Mono',monospace;font-weight:500;font-size:${15 * k}px;color:${c.ink}}
.sub{font-family:'IBM Plex Mono',monospace;font-weight:500;font-size:${13 * k}px;color:${c.secondary}}
.sdot{display:inline-block;width:${8 * k}px;height:${8 * k}px;background:${c.warn};border-radius:${k}px;transform:rotate(45deg)}
.okb{display:inline-flex;align-items:center;gap:${6 * k}px;padding:${k}px ${9 * k}px;border:${k}px solid ${c.okLine};background:${c.okSoft};color:${c.okInk};border-radius:999px;font-size:${12.5 * k}px;font-weight:500}`;
  const check = `<svg viewBox="0 0 16 16" width="${12 * k}" height="${12 * k}"><path d="M3.5 8.2l2.9 2.9L12.5 5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
  const css = `${gridCSS(theme, { recede: 14, mask: 'ellipse 60% 70% at 62% 62%' })}${card}
.card{position:absolute;left:${M}px;top:${M}px;width:${W - 2 * M}px;height:${H - 2 * M}px;border-radius:32px;overflow:hidden;background:${tileBg(dark)};
  box-shadow:0 0 0 1px ${tileRing(dark)},0 1px 0 ${dark ? 'rgba(255,255,255,.04)' : 'rgba(255,255,255,.9)'} inset,0 18px 30px -22px rgba(${c.shadow},${dark ? '.7' : '.16'})}
.card::before{content:'';position:absolute;inset:0;background:radial-gradient(1100px 600px at 88% 100%,${dark ? 'rgba(43,185,121,.10)' : 'rgba(26,167,102,.07)'},transparent 70%),
  radial-gradient(900px 500px at 0% 0%,${dark ? 'rgba(224,151,58,.04)' : 'rgba(217,138,43,.035)'},transparent 70%)}
.shot{position:absolute;left:${ox}px;top:${oy}px;right:0;bottom:0;border-top-left-radius:${12 * zoom}px;overflow:hidden;
  -webkit-mask-image:linear-gradient(90deg,#000 calc(100% - 360px),transparent calc(100% - 20px)),linear-gradient(180deg,#000 calc(100% - 300px),transparent calc(100% - 10px));
  -webkit-mask-composite:source-in}
.shot img{display:block;width:${1440 * zoom}px;height:${900 * zoom}px}
.shot .frame{position:absolute;inset:0;border-top-left-radius:${12 * zoom}px;box-shadow:inset 1px 1px 0 ${dark ? 'rgba(255,255,255,.09)' : 'rgba(21,24,29,.10)'}}
.under{position:absolute;left:${ox}px;top:${oy}px;right:0;bottom:0;border-top-left-radius:${12 * zoom}px;box-shadow:-10px -6px 60px -10px rgba(${c.shadow},${dark ? '.6' : '.12'});
  -webkit-mask-image:linear-gradient(90deg,#000 50%,transparent),linear-gradient(180deg,#000 50%,transparent);-webkit-mask-composite:source-in}`;
  // The floating cards repeat what the screen says: 2 devices need attention; 0.1.15 is signature-verified.
  const body = `<div class="card"><div class="sg"></div><div class="sgv"></div><div class="under"></div>
  <div class="shot"><img src="${shot('screen-overview', theme)}"><div class="frame"></div></div>
  <div class="scard" style="left:1340px;top:396px"><span class="lab"><span class="sdot"></span>Needs attention</span><span class="val">2</span></div>
  <div class="scard" style="left:1870px;top:1150px"><span class="okb">${check}Signature verified</span><span class="sub">0.1.15</span></div></div>`;
  return page(theme, W, H, css, body);
}

// ---------- 2. features: the bento grid ----------
// Tile layout on a 2400x1500 canvas. img: k = card, z = zoom (x CSS px; the captures are 2x, so z <= 2 stays crisp),
// x/y = position in the tile, cy = card CSS offset shown at the crop's top, ch = visible CSS height,
// fadeR/fadeB/fadeT = soft bleed edges, yb = distance from the tile's bottom, float = lifted shadow.
const R1 = 64, R2 = 762, TH = 674;
const TILES = [
  { x: 64, y: R1, w: 900, eyebrow: 'Overview', title: 'Needs attention, first', line: 'Failed applies, old agents and offline devices, each with its next step.',
    imgs: [{ k: 'attention', z: 1.3, x: 56, y: 236, fadeR: true, fadeB: true }] },
  { x: 988, y: R1, w: 600, eyebrow: 'Overview', title: 'Sync, per configuration', line: 'Spot drift before your users do.',
    imgs: [{ k: 'sync', z: 1.22, x: 36, y: 236 }] },
  { x: 1612, y: R1, w: 724, eyebrow: 'Enroll', title: 'Enroll with a QR code', line: 'Factory-reset, tap six times, scan.',
    imgs: [{ k: 'enroll', z: 1.18, x: 56, y: 236, fadeR: true, fadeB: true }] },
  { x: 64, y: R2, w: 1100, eyebrow: 'Configurations', title: 'Tri-state settings, changes marked', line: 'Not managed, Allow or Block. Every edit is flagged before you save and apply.',
    imgs: [{ k: 'cfg', z: 1.12, x: 56, y: 222, cy: 84, ch: 280, fadeT: true }, { k: 'cfgbar', z: 1.12, x: 56, yb: 40, float: true }] },
  { x: 1188, y: R2, w: 560, eyebrow: 'Device', title: 'Remote actions', line: 'Destructive actions ask first.',
    imgs: [{ k: 'remote', z: 1.12, x: 40, y: 222, cy: 222, fadeR: true, fadeT: true }] },
  { x: 1772, y: R2, w: 564, eyebrow: 'Settings', title: 'Staged agent rollouts', line: 'Canary devices first, then everyone.',
    imgs: [{ k: 'rollout', z: 1.2, x: 40, y: 236, fadeR: true }] },
];

function featuresHTML(theme) {
  const c = T[theme]; const dark = theme === 'dark';
  const crop = (t, m) => {
    const { w: cw0, h: ch0 } = CARDS[m.k];
    const cy = m.cy || 0;
    let vw = cw0 * m.z, vh = (m.ch || ch0 - cy) * m.z;
    if (m.fadeR) vw = Math.min(vw, t.w - m.x);
    if (m.fadeB) vh = Math.min(vh, TH - m.y);
    const top = m.yb != null ? `bottom:${m.yb}px` : `top:${m.y}px`;
    const r = 12 * m.z;
    const rad = `${cy === 0 ? r : 0}px ${cy === 0 ? r : 0}px ${!m.ch ? r : 0}px ${!m.ch ? r : 0}px`;
    const mask = [m.fadeR && 'linear-gradient(90deg,#000 calc(100% - 150px),transparent)', m.fadeB && 'linear-gradient(180deg,#000 calc(100% - 130px),transparent)',
      m.fadeT && 'linear-gradient(0deg,#000 calc(100% - 46px),transparent)'].filter(Boolean);
    const maskCss = mask.length ? `-webkit-mask-image:${mask.join(',')};-webkit-mask-composite:source-in;` : '';
    return `<div class="crop${m.float ? ' float' : ''}" style="left:${m.x}px;${top};width:${vw}px;height:${vh}px;border-radius:${rad};${maskCss}">
      <img src="${shot(m.k, theme)}" style="width:${cw0 * m.z}px;height:${ch0 * m.z}px;margin-top:${-cy * m.z}px"></div>`;
  };
  const css = `${gridCSS(theme, { mask: 'ellipse 70% 75% at 50% 60%' })}
.tile{position:absolute;height:${TH}px;border-radius:32px;overflow:hidden;background:${tileBg(dark)};
  box-shadow:0 0 0 1px ${tileRing(dark)},0 1px 0 ${dark ? 'rgba(255,255,255,.04)' : 'rgba(255,255,255,.9)'} inset,0 30px 60px -40px rgba(${c.shadow},${dark ? '.8' : '.18'})}
.tile::before{content:'';position:absolute;inset:0;background:radial-gradient(700px 360px at 85% 100%,${dark ? 'rgba(43,185,121,.07)' : 'rgba(26,167,102,.05)'},transparent 70%)}
.txt{position:absolute;left:64px;top:56px;right:64px}
.eb{font-family:'IBM Plex Mono',monospace;font-size:20px;font-weight:500;letter-spacing:.06em;text-transform:uppercase;color:${c.online}}
.tt{margin-top:12px;font-size:40px;font-weight:600;letter-spacing:-.015em;color:${c.ink};line-height:1.15}
.ln{margin-top:10px;font-size:25px;color:${c.secondary};line-height:1.4}
.crop{position:absolute;overflow:hidden;box-shadow:0 0 0 1px ${dark ? 'rgba(255,255,255,.06)' : 'rgba(21,24,29,.06)'},0 24px 50px -24px rgba(${c.shadow},${dark ? '.9' : '.22'})}
.crop img{display:block}
.crop.float{box-shadow:0 0 0 1px ${dark ? 'rgba(255,255,255,.10)' : 'rgba(21,24,29,.08)'},0 30px 60px -20px rgba(${c.shadow},${dark ? '.95' : '.35'}),0 10px 20px -10px rgba(${c.shadow},${dark ? '.6' : '.15'})}`;
  const body = TILES.map((t) => `<div class="tile" style="left:${t.x}px;top:${t.y}px;width:${t.w}px"><div class="sg"></div><div class="sgv"></div>
    <div class="txt"><div class="eb">${t.eyebrow}</div><div class="tt">${t.title}</div><div class="ln">${t.line}</div></div>${t.imgs.map((m) => crop(t, m)).join('')}</div>`).join('');
  return page(theme, 2400, 1500, css, body);
}

// ---------- 3. "How it works" parts ----------
// The agent's status screen (agent-android/app/.../MainActivity.kt buildUi): INK background, 28/40dp padding,
// "MDMesh" 30sp bold SIGNAL, "Device agent" 14sp MUTED, then label (11sp FAINT, 0.10em) + value pairs with a 16dp
// spacer, and "Managed by MDMesh" 12sp centred. 1 CSS px = 1 dp here. Values are the sample device WH-SCAN-07.
const AGENT = { INK: '#0E1117', TEXT: '#E8EEF4', MUTED: '#8693A4', FAINT: '#5C6675', SIGNAL: '#F4B942', OK: '#3FD08A' };
function agentScreen() {
  const row = (label, value, style) => `<div class="lbl">${label}</div><div style="${style}">${value}</div><div class="sp"></div>`;
  return `<div class="agent">
    <div class="sys"><span>9:41</span><span class="hole"></span><span class="icons">
      <svg viewBox="0 0 24 24" width="15" height="15"><path d="M2 8.5a15 15 0 0120 0M5.5 12a10 10 0 0113 0M9 15.5a5 5 0 016 0" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round"/><circle cx="12" cy="19" r="1.6" fill="#fff"/></svg>
      <svg viewBox="0 0 30 16" width="22" height="12"><rect x="1" y="1" width="25" height="14" rx="4" fill="none" stroke="rgba(255,255,255,.7)" stroke-width="1.6"/><rect x="3.5" y="3.5" width="17" height="9" rx="2" fill="#fff"/></svg></span></div>
    <div class="col">
      <div style="font:700 30px/1.17 Roboto;color:${AGENT.SIGNAL}">MDMesh</div>
      <div style="font:400 14px/1.17 Roboto;color:${AGENT.MUTED};padding:2px 0 20px">Device agent</div>
      ${row('MANAGEMENT', 'Device Owner — active', `font:400 16px/1.17 Roboto;color:${AGENT.OK}`)}
      ${row('DEVICE ID', '7c1e4b2a-93f0-4d6e-8a51-2f0b9c3d6e14', `font:400 14px/1.17 'Noto Sans Mono';color:${AGENT.TEXT}`)}
      ${row('KIOSK', 'Not locked', `font:400 16px/1.17 Roboto;color:${AGENT.TEXT}`)}
      ${row('AGENT VERSION', '0.1.15 (build 115)', `font:400 15px/1.17 'Noto Sans Mono';color:${AGENT.TEXT}`)}
      ${row('SERVER', 'https://mdm.acme.example/', `font:400 13px/1.17 'Noto Sans Mono';color:${AGENT.MUTED}`)}
      <div style="font:400 12px/1.17 Roboto;color:${AGENT.MUTED};text-align:center;padding-top:24px">Managed by MDMesh</div>
    </div><div class="gesture"></div></div>`;
}

function hiwHTML(theme) {
  const c = T[theme]; const dark = theme === 'dark';
  // Layout in SVG units of the 2400x1040 "How it works" viewBox (round 7 final): the panel's vertical centre sits
  // on the line (y 454.9); the phone stands at x 1898 with its body's upper 45% above the line.
  const LINE_Y = 454.9, DW = 1160.86, GAP = 26.4, MG = 52.8, ANGLE = 11, PERSP = 2200;
  const z = DW / CARDS.cfg.w;
  const chS = CARDS.cfg.h * z, chB = CARDS.cfgbar.h * z;
  const lx = 112, ly = LINE_Y - chS / 2;
  const PS = 0.8, pw = 360 * PS, ph = 764 * PS, px = 2400 - 24 - 190 - pw, py = LINE_Y - 0.45 * ph, PM = 60;
  const cropShadow = `0 0 0 1px ${dark ? 'rgba(255,255,255,.06)' : 'rgba(21,24,29,.06)'},0 24px 50px -24px rgba(${c.shadow},${dark ? '.9' : '.22'})`;
  const floatShadow = `0 0 0 1px ${dark ? 'rgba(255,255,255,.10)' : 'rgba(21,24,29,.08)'},0 30px 60px -20px rgba(${c.shadow},${dark ? '.95' : '.30'}),0 10px 20px -10px rgba(${c.shadow},${dark ? '.6' : '.14'})`;
  const css = `
.part{position:absolute;transform-style:preserve-3d}
.a{position:absolute;width:0;height:0}
#cons{left:${lx - MG}px;top:${ly - MG}px;width:${DW + 2 * MG}px;height:${chS + GAP + chB + 2 * MG}px;
  transform-origin:50% ${LINE_Y - (ly - MG)}px;transform:perspective(${PERSP}px) rotateY(${ANGLE}deg)}
#cons .crop{position:absolute;left:${MG}px;width:${DW}px;overflow:hidden;border-radius:${12 * z}px}
#cons .crop img{display:block;width:100%;height:100%}
#phone{left:${px - PM}px;top:${py - PM}px;width:${pw + 2 * PM}px;height:${ph + 2 * PM}px;
  transform-origin:50% ${LINE_Y - (py - PM)}px;transform:perspective(${PERSP}px) rotateY(${-ANGLE}deg)}
.pbox{position:absolute;left:${PM}px;top:${PM}px;width:${pw}px;height:${ph}px}
.phone{position:absolute;left:0;top:0;width:360px;height:764px;border-radius:52px;transform:scale(${PS});transform-origin:0 0;
  background:linear-gradient(145deg,#41464f 0%,#25292f 22%,#16191d 60%,#2c3037 100%);
  box-shadow:inset 0 0 0 1.2px rgba(255,255,255,.16),inset 0 0 0 2.5px rgba(0,0,0,.55),0 50px 80px -30px rgba(${c.shadow},${dark ? '.85' : '.32'}),0 18px 30px -16px rgba(${c.shadow},${dark ? '.6' : '.18'})}
.btn{position:absolute;right:-4px;width:5px;border-radius:0 3px 3px 0;background:linear-gradient(90deg,#1b1e23,#3a3f47 60%,#24272d)}
.glass{position:absolute;inset:7px;border-radius:46px;background:#050607;padding:5px}
.agent{position:relative;width:100%;height:100%;border-radius:41px;overflow:hidden;background:${AGENT.INK}}
.agent::after{content:'';position:absolute;inset:0;background:linear-gradient(125deg,rgba(255,255,255,.05),rgba(255,255,255,0) 38%)}
.sys{position:absolute;left:0;right:0;top:0;height:30px;display:flex;align-items:center;justify-content:space-between;padding:4px 24px 0;font:500 13px Roboto;color:#fff}
.hole{position:absolute;left:50%;top:9px;width:16px;height:16px;margin-left:-8px;border-radius:50%;background:#000;box-shadow:inset 0 0 0 2px #0d0f12}
.icons{display:flex;align-items:center;gap:6px}
.col{position:absolute;left:0;right:0;top:0;padding:40px 28px 40px}
.col div{overflow-wrap:anywhere}
.lbl{font:400 11px/1.17 Roboto;color:${AGENT.FAINT};letter-spacing:.10em;padding-bottom:4px}
.sp{height:16px}
.gesture{position:absolute;left:50%;bottom:9px;width:100px;height:4px;margin-left:-50px;border-radius:3px;background:rgba(255,255,255,.55)}`;
  const body = `
<div class="part" id="cons">
  <div class="crop" style="top:${MG}px;height:${chS}px;box-shadow:${cropShadow}"><img src="${shot('cfg', theme)}"></div>
  <div class="crop" style="top:${MG + chS + GAP}px;height:${chB}px;box-shadow:${floatShadow}"><img src="${shot('cfgbar', theme)}"></div>
  <div class="a" id="aC" style="left:${MG + DW}px;top:${MG + chS / 2}px"></div></div>
<div class="part" id="phone"><div class="pbox"><div class="phone">
  <div class="btn" style="top:190px;height:64px"></div><div class="btn" style="top:280px;height:116px"></div>
  <div class="glass">${agentScreen()}</div></div></div>
  <div class="a" id="aP" style="left:${PM}px;top:${LINE_Y - (py - PM)}px"></div></div>`;
  return page(theme, 2400, 1040, css, body);
}

async function hiwParts() {
  const geom = {};
  for (const theme of THEMES) {
    const p = await openPage(`hiw-${theme}`, hiwHTML(theme), { w: 2400, h: 1040, scale: 1 });
    const g = await p.evaluate(() => {
      const r = (id) => { const b = document.getElementById(id).getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height }; };
      return { cons: r('cons'), phone: r('phone'), aC: r('aC'), aP: r('aP') };
    });
    for (const part of ['cons', 'phone']) {
      const b = g[part];
      const clip = { x: Math.floor(b.x) - 4, y: Math.floor(b.y) - 4, width: Math.ceil(b.w) + 8, height: Math.ceil(b.h) + 8 };
      await p.evaluate((part) => { for (const id of ['cons', 'phone']) document.getElementById(id).style.visibility = id === part ? 'visible' : 'hidden'; }, part);
      const png = await p.screenshot({ clip, omitBackground: true });
      // WebP for embedding in the SVG (svg/howitworks.py base64-encodes it as is).
      await sharp(png).webp({ quality: 82, alphaQuality: 90, effort: 6 }).toFile(path.join(PARTS, `hiw-${part === 'cons' ? 'console' : 'phone'}-${theme}.webp`));
      g[part + 'Clip'] = clip;
    }
    geom[theme] = g;
    await p.close();
  }
  fs.writeFileSync(path.join(PARTS, 'hiw-geom.json'), JSON.stringify(geom, null, 1));
  console.log('how-it-works parts: line', geom.light.aC.x.toFixed(1), '->', geom.light.aP.x.toFixed(1), 'at y', geom.light.aC.y.toFixed(1));
}

try {
  for (const theme of THEMES) {
    for (const [name, html, w, h] of [['hero', heroHTML(theme), 2400, 1300], ['features', featuresHTML(theme), 2400, 1500]]) {
      const p = await openPage(`${name}-${theme}`, html, { w, h, scale: 1 });
      const png = await p.screenshot({ omitBackground: true });
      await p.close();
      await webp(png, path.join(OUT, `${name}-${theme}.webp`));
    }
  }
  await hiwParts();
} finally {
  await closeBrowser();
}
