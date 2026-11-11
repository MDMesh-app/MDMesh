# Kiosk background image: keeping the launcher legible (version 3.2)

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
- Shared test vectors: [`proto/vectors/kiosk-scrim.json`](../proto/vectors/kiosk-scrim.json), version 3.2, generated
  by an independent implementation and passed by both test suites. A change to these rules changes the vectors.

Version history: versions 1 and 2 (one full-screen scrim from area-averaged samples, with a 2 % allowance) are
replaced by version 3: the bounds are now the worst pixel (per-cell minimum and maximum), and texture gets a solid plate.
3.1 adds row uniformity (the labels of a grid row share one plate step) and states the Exit button's outlined model.
3.2 adds the fit mode and the focal point, pins the decode the cells come from, makes orientation JPEG-only, caps a row
plate at the cell width less 8 dp, and settles every rounding and tie (see "What 3.2 changes" at the end).

The console is the **reference**: it reads the image at full size, 8-bit sRGB, so its bounds contain every pixel. A
device that samples the image (for memory) sees ranges that are subsets of those, so its plates are never stronger
than the preview's, and the preview never promises a plate the device won't draw.

## Parsing

The configuration stores `backgroundColor` and `textColor` as strings; the server sends them unchanged. Both sides
read a stored value the same way: exactly the forms Android's `Color.parseColor` reads and the console's colour
picker writes (the server doesn't check them, so an API client may store anything):

- `#RRGGBB`: `#` and exactly 6 ASCII hex digits (`0-9`, `A-F`, `a-f`; no other Unicode digits).
- `#AARRGGBB`: `#` and exactly 8 ASCII hex digits; the alpha is dropped (the launcher draws its colours opaque).
- One of Android's names, any case, lower-cased with `Locale.ROOT` (so `WHITE` is white in every locale; `WHİTE` is
  not a colour): black, darkgray, gray, lightgray, white, red, green, blue, yellow, cyan, magenta,
  aqua, fuchsia, darkgrey, grey, lightgrey, lime, maroon, navy, olive, purple, silver, teal.

Anything else is not a colour, and the default is used: `#RGB`, a missing `#`, spaces around the value, `+`/`-` after
the `#` (which `parseColor` would take), CSS forms like `rgb(...)` or `transparent`, an empty string. The agent checks
the form before calling `parseColor`. Vectors: `parsing`, and `palette` cases with such values.

## Colours

`KioskPalette.of` on the agent, `kioskColours` in the console; opaque sRGB.

- `background` = the configuration's background colour, or v2 dark `#0F1115`. The dim and every plate use it.
- `text` = the configuration's text colour while it reaches 4.5:1 on `background`; otherwise the v2 text colour
  (`#E7EBF1` or `#1B2230`) that reads best on it (`#E7EBF1` on a tie), or, when neither reaches 4.5:1, white or black,
  whichever reads better (white on a tie).
- `secondary` = `text` at 80 % over `background` (per channel `round(f * 0.8f + b * (1 - 0.8f))` in 32-bit float,
  as Kotlin computes it), or `text` when that is under 4.5:1.
- The Exit button is **outlined**: filled with `background`, a 1 dp border and its label in `text`. Its label sits on
  its own fill (no plate); what has to stand out from the image is the border, so as a control its colour is `text`.

## The image as cells

Once per image, in image pixels:

- **Decode**: the image as 8-bit sRGB, ARGB_8888, after its EXIF orientation. Orientation is **JPEG-only** (the server
  removes it from PNG and WebP, so those are shown as stored). An embedded colour profile is converted to sRGB (Display
  P3 included): on Android 8+ decode with `inPreferredColorSpace = SRGB`; older Android ignores profiles, the same as
  treating the pixels as sRGB. The cell source is ARGB_8888 even when the bitmap drawn is RGB_565.
- **Size**: full size (the console), or the device's power-of-two sample (`inSampleSize`), with `iw`, `ih` and the
  cells taken from the sampled bitmap. Sampling only narrows the ranges (see the reference note above).
- **Cells**: square, `cellSize = max(1, ceil(max(iw, ih) / 120))` px; `cols = ceil(iw / cellSize)`,
  `rows = ceil(ih / cellSize)`. Cell `(i, j)` (row-major) covers `x` in `[i * cellSize, min((i + 1) * cellSize, iw))`
  and the same for `y`.
- Each cell keeps the **per-channel minimum and maximum** of its pixels, each pixel first composited over
  `background`: per channel `floor((c * a + b * (255 - a) + 127) / 255)` (`a` its alpha, `b` the background's channel;
  an opaque pixel is itself).
- Because luminance only grows with each channel, every pixel under a set of cells has a luminance between that of
  `low` (the per-channel minimum of their minimums) and `high` (the per-channel maximum of their maximums).

## The window

All launcher geometry is in dp, in the launcher's window (the full screen, under the system bars), `W` x `H`; all of it
in doubles (not floats).

- **Fill** (the default): `scale = max(W / iw, H / ih)` (dp per image px), crop `cw = W / scale`, `ch = H / scale`,
  `cx = clamp(fx * iw - cw / 2, 0, iw - cw)`, `cy = clamp(fy * ih - ch / 2, 0, ih - ch)` with the focal point
  `(fx, fy)` (default `(0.5, 0.5)`, which is Android's `CENTER_CROP`), `clamp(v, a, b) = max(a, min(b, v))`. The image's
  rectangle in the window: `x = -cx * scale`, `y = -cy * scale`, `w = iw * scale`, `h = ih * scale`.
- **Fit**: `scale = min(W / iw, H / ih)`, `w = iw * scale`, `h = ih * scale`, `x = (W - w) / 2`, `y = (H - h) / 2`; the
  rest of the window (the letterbox) is the background colour. The focal point is ignored. Never stretched.
- The device draws with an image matrix translated to whole pixels (as `CENTER_CROP` rounds `dx`); the one-cell margin
  below absorbs that half pixel. One `scale` serves both axes.
- Rotating the device swaps `W` and `H` and recomputes everything below.
- **Cells under a box** `{x, y, w, h}` (dp): map it into the image, `x0 = (x - rx) / scale`,
  `x1 = (x + w - rx) / scale`, `y0 = (y - ry) / scale`, `y1 = (y + h - ry) / scale` (`rx, ry` the image rectangle's
  origin), clipped to the image: `x0' = max(0, x0)`, `x1' = min(iw, x1)`, the same for `y`. When `x1' > x0'` and
  `y1' > y0'`, take cells `i` from `max(0, floor(x0' / cellSize) - 1)` to `min(cols - 1, ceil(x1' / cellSize))` and `j`
  from `max(0, floor(y0' / cellSize) - 1)` to `min(rows - 1, ceil(y1' / cellSize))`: the cells the box touches plus a
  one-cell margin (for the sub-cell position of the text and the GPU's filtering).
- **Letterbox** (fit): when the box, clipped to the window, reaches outside the image rectangle, the background colour
  joins the bounds. A box with no cell and no letterbox under it has the background colour as both bounds.
- Their `low` and `high` are the box's bounds.

## Functions

```
blend(c, px, step) = per channel: floor((c * step + px * (50 - step) + 25) / 50)    // integers, half up (not half even)
lum(c)             = WCAG 2.x relative luminance (double precision)
worst(colour, lo, hi):                                 // the lowest contrast against any pixel between lo and hi
    lo' = lo - 1 per channel (not below 0), hi' = hi + 1 per channel (not above 255)   // GPU rounding, one level
    a = lum(lo'), b = lum(hi'), t = lum(colour)
    a <= t <= b:  1
    t < a:        (a + 0.05) / (t + 0.05)
    t > b:        (t + 0.05) / (b + 0.05)
busy(lo, hi)       = some channel has hi - lo > 48                                // 48 is smooth, 49 is texture
```

**Dim** `d` (0 to 15), over the controls (the Exit button's box; its border, colour `text`, against the dimmed
image):

```
no controls:  d = 0
otherwise:    d = the lowest s in 0..15 with worst(text, blend(background, low, s), blend(background, high, s)) >= 3
                  for every control (low, high: the control's bounds); 15 when none reaches it
```

The dim is decided over all controls first, then the plates. A control reports `busy` and its ratio but never gets a
plate; when 30 % isn't enough the console warns.

**Plate** `k` (0 to 50) of a text, colour `text` or `secondary`, over its plate box:

```
lo = blend(background, low, d), hi = blend(background, high, d)       // the dimmed bounds, rounded
worst(colour, lo, hi) >= 4.5:  k = 0                                   // no plate
busy(lo, hi):                  k = 50                                  // texture: solid
otherwise:                     k = the lowest s in 1..50 with worst(colour, blend(background, lo, s), blend(background, hi, s)) >= 4.5;
                               50 when none reaches it
```

- **Row uniformity (3.1)**: the labels of one grid row are one group. When any label of a row has `k > 0`, every
  label of that row gets the row's highest `k` (and, as before, the row's shared width); a row where none needs a plate
  stays without. The header and other texts are not grouped. Each label's reported ratio is then its `worst` at that
  shared `k`.
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
- **Label rows** (3.2): the shared row width is the widest label line plus padding, but at most the **cell width less
  8 dp**, so neighbouring plates are always at least 8 dp apart. The label's text box is that maximum less the padding
  (`cellWidth - 8 - 16` dp; labels wrap and ellipsize in it). Each plate is centred under its icon, then kept inside
  the grid's content edges (`left` = the first cell's start, `right` = the last cell's end):
  `x = min(max(iconCentre - width / 2, left), right - width)`. Vectors: `rowPlates`.
- **Corner radius**: 10 dp (the console's small radius, `--radius-sm`).
- **Header**: one plate around the title text only, not the full row.
- **App labels**: all labels of a grid row share one width (see Label rows); each centred under its icon.
- **Other text** (hold screen, splash): one plate per text view.
- **No plate**: buttons (the outlined Exit button, Open again) and the empty-state callout, which have their own fill.
- A plate's box is its region: its bounds come from the cells under it (plus the margin).

## Drawing

Bottom to top: the background colour, the image (fill or fit, placed as above), the background colour at `d / 50`
over the whole window, each plate (background colour at `k / 50`), then the launcher.

Everything is recomputed when any input changes: the image, the colours, the fit mode or focal point (a new
configuration), the window size (rotation, fold, split screen), the font scale, or the text layout. The vectors and
the console's preview are at the default font size (1.0); at a larger font scale the agent allows 3 label lines and
lays out (and measures) its own text, with the same rules.

## What the console shows

One large preview of the launcher at device scale, scaled to fit, with a Phone / Tablet choice and a Rotate button
(the crop, dim and plates are recomputed for the turned window as on the device; the turn is animated unless the
viewer prefers reduced motion), and four thumbnails beneath it that select the large view: phone portrait
(360 x 780 dp), phone landscape, tablet 4:3 landscape (1024 x 768 dp) and tablet 16:10 portrait (800 x 1280 dp). Each
shape reads, for example, "Text 4.6:1 Pass (on plate)" (the lowest worst-case text contrast; "on plate" when some
text needs one) and "Image dimmed 22%".

## Vectors

`proto/vectors/kiosk-scrim.json` version 3.2:

- `parsing`: stored value to colour, or null (ASCII-only hex, `Locale.ROOT` names).
- `palette`: stored colours to background, text, secondary (ties and the black/white fallback included).
- `blends`: `blend(over, px, step)` at exact half steps (half up, not half even).
- `geometry`: image size, window, mode and focal point to the image rectangle (`x`, `y`, `w`, `h`, `scale`), compared
  to 6 decimals (doubles): centred, extreme focal points, very wide and very tall images, square, fit.
- `rowPlates`: icon centres, label text widths, cell width and content edges to each plate's `x` and `w`: a 7-column
  landscape row with one long label, a capped row, a short row, and clamping at the edges.
- `layout`: stored colours, an image (`width`, `height`, `cellSize`, `cols`, `rows`, and either `cells` as
  `[min, max]` row-major or `pixels` as `#RRGGBBAA` row-major, composited per the formula above), a window (dp), the
  optional `mode` and `focal`, and elements (`id`, `kind`: `text`, `secondary` or `control`, `box` in dp, and `row` for
  the labels of a grid row). It expects the palette, the dim `d`, and per element its plate step (always 0 for a
  control), `busy`, its worst ratio with the plate and, for texts, without it. Cases cover no dim, the 30 % cap, no
  controls, a light dim, texture to a solid plate, busy but dark enough, a translucent plate, a band under one label,
  the one-cell margin (and a cell beyond it), secondary text, a light kiosk, saturated colours, a crop that hides the
  bright sides, brand colours, rows (one label alone needing a plate, one over texture, none, two rows), a span of
  exactly 48 and 49, cells of 3 px under a non-terminating scale with box edges inside cells, pixels with alpha, fit
  with a letterbox, fit ignoring the focal point, and focal points that move a bright area under a label or away.

## What 3.2 changes

- Fit mode (`fill` default, `fit` letterboxed) and the focal point, with exact geometry; old agents keep centre-crop
  fill.
- The cell source is pinned: 8-bit sRGB ARGB_8888 after the JPEG-only EXIF orientation, Display P3 converted to
  sRGB; full size or a power-of-two sample. The console's full-size read is the conservative reference.
- Label rows: plate width at most the cell less 8 dp, text box `cellWidth - 24` dp, centred under the icon and
  clamped to the grid's content edges.
- Settled: half-up blending, `busy` as a span over 48, doubles throughout, ASCII hex and `Locale.ROOT` names, the
  palette ties, the dim before the plates, controls never plated, recompute triggers, font scale 1.0 in the vectors.

## Not (yet) done

- Swapping the text colour to the one that reads best on the image.
- The status bar icon colour from the top strip of the image (agent only).
