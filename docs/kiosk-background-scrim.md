# Kiosk background image: keeping the launcher legible (version 3)

A kiosk background image sits under the launcher's text (header, app labels, empty-state and hold text) and its
controls (the Exit button). A photo can be any brightness, so the launcher does two things, both with the kiosk
**background colour**:

1. **A light dim** of the whole image, only as much as the controls need to stand out at **3:1** (WCAG 1.4.11), and
   never more than **30 %**. A kiosk without an Exit button has no dim at all.
2. **A plate** behind each text that needs one: a rounded rectangle hugging the text, at the lowest opacity at which
   that text reaches **4.5:1** (WCAG AA; the launcher has no large text) against **every** pixel under it, and solid
   over texture. Text over a calm part of the image gets no plate. Plates carry the text, so the image stays visible.

The console's preview computes the same, so what it shows is what the device draws.

- Console: `web/src/data/kioskScrim.ts` (`parseColor`, `kioskColours`, `imageCells`, `kioskLegibility`),
  `web/src/data/kioskLauncherLayout.ts` (the plate geometry) and the preview, `web/src/pages/configurations/
  KioskPreview.tsx`.
- Agent: a `BackdropRule` alongside `KioskPalette` (agent branch).
- Shared test vectors: [`proto/vectors/kiosk-scrim.json`](../proto/vectors/kiosk-scrim.json), version 3, generated
  by an independent implementation and passed by both test suites. A change to these rules changes the vectors.

Version history: versions 1 and 2 (one full-screen scrim from area-averaged samples, with a 2 % allowance) are
replaced by this one: the bounds are now the worst pixel (per-cell minimum and maximum), and texture gets a solid plate.

## Parsing

The configuration stores `backgroundColor` and `textColor` as strings; the server sends them unchanged. Both sides
read a stored value the same way: exactly the forms Android's `Color.parseColor` reads and the console's colour
picker writes (the server doesn't check them, so an API client may store anything):

- `#RRGGBB`: `#` and exactly 6 hex digits, any case.
- `#AARRGGBB`: `#` and exactly 8 hex digits; the alpha is dropped (the launcher draws its colours opaque).
- One of Android's names, any case: black, darkgray, gray, lightgray, white, red, green, blue, yellow, cyan, magenta,
  aqua, fuchsia, darkgrey, grey, lightgrey, lime, maroon, navy, olive, purple, silver, teal.

Anything else is not a colour, and the default is used: `#RGB`, a missing `#`, spaces around the value, `+`/`-` after
the `#` (which `parseColor` would take), CSS forms like `rgb(...)` or `transparent`, an empty string. The agent checks
the form before calling `parseColor`. Vectors: `parsing`, and `palette` cases with such values.

## Colours

`KioskPalette.of` on the agent, `kioskColours` in the console; opaque sRGB.

- `background` = the configuration's background colour, or v2 dark `#0F1115`. The dim and every plate use it.
- `text` = the configuration's text colour while it reaches 4.5:1 on `background`; otherwise the v2 text colour
  (`#E7EBF1` or `#1B2230`) that reads best on it, or black/white when neither reaches 4.5:1.
- `secondary` = `text` at 80 % over `background` (per channel `round(f * 0.8f + b * (1 - 0.8f))` in 32-bit float,
  as Kotlin computes it), or `text` when that is under 4.5:1.
- The Exit button is filled with `text` (label in `background`), so as a control its colour is `text`.

## The image as cells

Once, when the image is decoded (after its EXIF orientation), in image pixels:

- **Cells**: square, `cellSize = max(1, ceil(max(iw, ih) / 120))` px; `cols = ceil(iw / cellSize)`,
  `rows = ceil(ih / cellSize)`. Cell `(i, j)` (row-major) covers `x` in `[i * cellSize, min((i + 1) * cellSize, iw))`
  and the same for `y`.
- Each cell keeps the **per-channel minimum and maximum** of its pixels, each pixel first composited over
  `background`: per channel `floor((c * a + b * (255 - a) + 127) / 255)` (`a` its alpha, `b` the background's channel;
  an opaque pixel is itself). The agent may decode at a power-of-two sample size for memory; the cells then come from
  the decoded bitmap, still with exact min/max over it.
- Because luminance only grows with each channel, every pixel under a set of cells has a luminance between that of
  `low` (the per-channel minimum of their minimums) and `high` (the per-channel maximum of their maximums).

## The window

All launcher geometry is in dp, in the launcher's window (the full screen, under the system bars), `W` x `H`.

- **Crop**: Android's `CENTER_CROP`, centred: `scale = max(W / iw, H / ih)`, crop `cw = min(iw, W / scale)`,
  `ch = min(ih, H / scale)` at `cx = (iw - cw) / 2`, `cy = (ih - ch) / 2`. Image pixels per dp: `k = cw / W`. Rotating the
  device swaps `W` and `H` and recomputes everything below.
- **Cells under a box** `{x, y, w, h}` (dp): map it into the image, `x0 = cx + x * k`, `x1 = cx + (x + w) * k`,
  `y0 = cy + y * k`, `y1 = cy + (y + h) * k` (doubles), then take cells `i` from `max(0, floor(x0 / cellSize) - 1)` to
  `min(cols - 1, ceil(x1 / cellSize))` and `j` from `max(0, floor(y0 / cellSize) - 1)` to
  `min(rows - 1, ceil(y1 / cellSize))`: the cells the box touches plus a one-cell margin (for the sub-cell position of
  the text and the GPU's filtering). Their `low` and `high` are the box's bounds.

## Functions

```
blend(c, px, step) = per channel: floor((c * step + px * (50 - step) + 25) / 50)    // integers, half up
lum(c)             = WCAG 2.x relative luminance (double precision)
worst(colour, lo, hi):                                 // the lowest contrast against any pixel between lo and hi
    lo' = lo - 1 per channel (not below 0), hi' = hi + 1 per channel (not above 255)   // GPU rounding, one level
    a = lum(lo'), b = lum(hi'), t = lum(colour)
    a <= t <= b:  1
    t < a:        (a + 0.05) / (t + 0.05)
    t > b:        (t + 0.05) / (b + 0.05)
busy(lo, hi)       = some channel has hi - lo > 48
```

**Dim** `d` (0 to 15), over the controls (the Exit button's box; it has its own fill, colour `text`):

```
no controls:  d = 0
otherwise:    d = the lowest s in 0..15 with worst(text, blend(background, low, s), blend(background, high, s)) >= 3
                  for every control (low, high: the control's bounds); 15 when none reaches it
```

A control gets no plate; when 30 % isn't enough the console warns.

**Plate** `k` (0 to 50) of a text, colour `text` or `secondary`, over its plate box:

```
lo = blend(background, low, d), hi = blend(background, high, d)       // the dimmed bounds, rounded
worst(colour, lo, hi) >= 4.5:  k = 0                                   // no plate
busy(lo, hi):                  k = 50                                  // texture: solid
otherwise:                     k = the lowest s in 1..50 with worst(colour, blend(background, lo, s), blend(background, hi, s)) >= 4.5;
                               50 when none reaches it
```

- Both stages round (the dimmed bounds are integer colours before the plate is blended over them), as two 8-bit
  layers composite.
- **Solid over texture**: an accessibility checker (and a reader) can't judge text over noise even when its contrast
  bounds pass; a translucent plate over a busy photo failed the agent's checks at large font scales.
- Integer steps and integer blending make the results identical in Kotlin and JavaScript. `lum` is a double; the
  vectors keep a margin of at least 0.0002 from 3 and 4.5 at every step, so a last-bit `pow` difference can't flip a
  result. `ratio` values in the vectors are rounded to 2 decimals.

## Plate geometry

In dp, from the laid-out text views; padding is applied with negative margins, so the text doesn't move:

- **Width**: the widest line of the text plus 8 dp on each side, centred on the text.
- **Height**: the full text-view height plus 4 dp above and below the text.
- **Corner radius**: 10 dp (the console's small radius, `--radius-sm`).
- **Header**: one plate around the title text only, not the full row.
- **App labels**: all labels of a grid row share one width: the widest label in that row (as laid out, so at most the
  label's own width in the cell) plus padding, capped at the cell width; each centred under its icon.
- **Other text** (hold screen, splash): one plate per text view.
- **No plate**: buttons (the Exit button, Open again) and the empty-state callout, which have their own fill.
- A plate's box is its region: its bounds come from the cells under it (plus the margin).

## Drawing

Bottom to top: the background colour, the image (`CENTER_CROP`), the background colour at `d / 50` over the whole
window, each plate (background colour at `k / 50`), then the launcher. Everything is recomputed when the window size
changes (rotation, fold) or the text layout changes.

## What the console shows

One large preview of the launcher at device scale, scaled to fit, with a Phone / Tablet choice and a Rotate button
(the crop, dim and plates are recomputed for the turned window as on the device; the turn is animated unless the
viewer prefers reduced motion), and four thumbnails beneath it that select the large view: phone portrait
(360 x 780 dp), phone landscape, tablet 4:3 landscape (1024 x 768 dp) and tablet 16:10 portrait (800 x 1280 dp). Each
shape reads, for example, "Text 4.6:1 Pass (on plate)" (the lowest worst-case text contrast; "on plate" when some
text needs one) and "Image dimmed 22%".

## Vectors

`proto/vectors/kiosk-scrim.json` version 3: `parsing` (stored value to colour, or null), `palette` (stored colours to
background, text, secondary) and `layout` cases. A layout case gives the stored colours, an image (`width`, `height`,
`cellSize`, `cols`, `rows`, and `cells` as `[min, max]` row-major), a window (dp) and elements (`id`, `kind`: `text`,
`secondary` or `control`, `box` in dp). It expects the palette, the dim `d`, and per element its plate step (always 0
for a control), `busy`, its worst ratio with the plate and, for texts, without it. Cases cover no dim, the 30 % cap, no
controls, a light dim, texture to a solid plate, busy but dark enough (no plate), a translucent plate, a band under one
label, the one-cell margin (and a cell beyond it), secondary text, a light kiosk, saturated colours, a crop that hides
the bright sides, and brand colours.

## Not (yet) done

- Swapping the text colour to the one that reads best on the image.
- The status bar icon colour from the top strip of the image (agent only).
