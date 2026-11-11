# README images (`scripts/readme/`)

Generates every image in [`docs/readme/`](../../docs/readme) from the real console build, in light and dark. The
console crops come from the built SPA answered by the screenshot harness's sample data
([`scripts/shots/`](../shots)), so they show the product as it is.

## Regenerate

```bash
scripts/readme/generate.sh --build     # builds web/ first; without --build it uses the existing web/dist
```

One-time setup:

```bash
(cd web && npm ci)                                   # the build, and the IBM Plex fonts the images use
(cd scripts/readme && npm install && npx playwright install chromium)
python3 -m venv scripts/readme/.venv && scripts/readme/.venv/bin/pip install fonttools
```

Python with [fontTools](https://github.com/fonttools/fonttools) is a dev dependency of the SVG step only: it turns
the text into outlines, because GitHub shows README SVGs through `<img>`, where web fonts can't load. `generate.sh`
uses `scripts/readme/.venv` when it exists, otherwise `$PYTHON` or `python3`.

## What it makes

| File | What it is | Made by |
|---|---|---|
| `banner-{light,dark}.svg` | ■ MDMesh and three console status cards that arrive like notifications; plays once (5.95 s) | `svg/banner.py` |
| `hero-{light,dark}.webp` | The Overview screen as a flat cutout, with two floating status cards | `compose.mjs` |
| `features-{light,dark}.webp` | The bento grid: six tiles, each a crop of one real card | `compose.mjs` |
| `how-it-works-{light,dark}.svg` | A configuration change travelling from "Save and apply" to a device; loops (6.15 s) | `compose.mjs` (raster parts) + `svg/howitworks.py` |
| `architecture-{light,dark}.svg` | The system diagram | `svg/architecture.py` |

The steps, in `generate.sh`:

1. **`capture.mjs`** shoots the console at 2x: the Overview screen and seven cards, each clipped to its DOM box
   (`.cache/shots/`). The configuration-editor crops come from real clicks on Default's Security settings.
   The sample versions are README-only (`versions.mjs`): server 0.4.0 with a signed 0.5.0 available, agents on
   0.4.0 (build 400). The shared fixtures keep their own versions for the parity tests, and a shot page that still
   shows one of them fails the capture.
2. **`compose.mjs`** lays out the hero and the bento in HTML and encodes them as WebP. It also renders the two "How it
   works" parts, the console panel and the phone, tilted toward each other with CSS 3D, and measures where the line
   attaches (`.cache/parts/`).
3. **`svg/build.py`** writes the three SVGs. `svg/kit.py` has the shared pieces: outlined text, the console's colour
   tokens, its status components (StatusDot, StatusPill, Card) drawn as vectors, the engraved background grid and a
   keyframe helper.

The phone shows the agent's own status screen, redrawn from its layout code
(`agent-android/app/src/main/kotlin/com/mdmesh/agent/MainActivity.kt`, `buildUi`) with sample values for
`WH-SCAN-07`. If that screen changes, update `agentScreen()` in `compose.mjs`. Once there are real device
screenshots, they can replace it.

## The animations

Both SVGs animate with CSS keyframes inside the file. There's no script, and they're self-contained (the raster
parts are embedded as data URIs). Times are absolute milliseconds on one cycle (`kf()` in `svg/kit.py`). Each segment
has its own easing, and the base styles are the finished state, so `prefers-reduced-motion` (`animation: none`) shows
the settled banner and the applied transport. Each part is a group with a plain class name (`stack-p1`, `sig-pill`,
`hiw-packet`, `hiw-node-0`, …), so the motion can be ported into the console as is. The timelines and the reasoning
behind the curves are in the docstrings of `svg/banner.py` and `svg/howitworks.py`.

To check an animation, open the SVG in a browser. Chromium plays it through `<img>` the way GitHub shows it.
