# Kiosk background image: keeping the launcher legible (version 3.6)

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
- Shared test vectors: [`proto/vectors/kiosk-scrim.json`](../proto/vectors/kiosk-scrim.json), version 3.6, generated
  by an independent implementation. The console passes them; agent implementations must pass them too (the agent
  isn't released with images until it does). A change to these rules changes the vectors.

Version history: versions 1 and 2 (one full-screen scrim from area-averaged samples, with a 2 % allowance) are
replaced by version 3: the bounds are now the worst pixel (per-cell minimum and maximum), and texture gets a solid plate.
3.1 adds row uniformity (the labels of a grid row share one plate step) and states the Exit button's outlined model.
3.2 adds the fit mode and the focal point, pins the decode the cells come from, makes orientation JPEG-only, caps a row
plate at the cell width less 8 dp, and settles every rounding and tie (see "What 3.2 changes" at the end).
3.3 pins how an app label breaks into lines (word boundaries only, never inside a word; "Label lines") and the room
the grid leaves for the Exit button (see "What 3.3 changes" at the end). 3.4 makes the cap and every ellipsis cut
between grapheme clusters (never inside a flag, an emoji sequence or a letter and its accent), measures an ellipsis
with its text, gives a label one direction, and pins one text box for labels with or without an image (see "What 3.4
changes"). 3.5 keeps Indic conjuncts and prepended marks whole at a cut, pins the label's direction to UAX #9's first
strong character, and pins the cap when no cut is near it (see "What 3.5 changes"). 3.6 adds the placeholder letter
and says exactly what skipping unassigned code points does and doesn't guarantee (see "What 3.6 changes").

The console is the **reference**: it reads the image at full size, 8-bit sRGB, so its bounds contain every pixel. A
device that reads the image the same way works out the same cells, plates and dim. A device may read it less
exactly, and then its ranges can differ a little from the preview's, either way:

- It samples a large image (for memory) and sizes its cells in sampled pixels, so a cell covers a slightly different
  part of the image: a 6000 x 4000 photo sampled by 4 has 13 px cells, 52 full-size pixels, where the console's are
  50, so the device reads x in [936, 1456) where the console reads [950, 1450).
- A low-RAM device draws a picture without transparency at 2 bytes a pixel (RGB_565) and works its cells out from
  those drawn pixels.

Each side keeps its own text at 4.5:1, and the Exit button's border at 3:1, against the pixels it draws itself. So a
device can draw a plate or a stronger dim that the preview doesn't show, or the other way round, but never less than
its own pixels need.

## Parsing

The configuration stores `backgroundColor` and `textColor` as strings; the server sends them unchanged. Both sides
read a stored value the same way: exactly the forms Android's `Color.parseColor` reads and the console's colour
picker writes (the server doesn't check them, so an API client may store anything):

- `#RRGGBB`: `#` and exactly 6 ASCII hex digits (`0-9`, `A-F`, `a-f`; no other Unicode digits).
- `#AARRGGBB`: `#` and exactly 8 ASCII hex digits; the alpha is dropped (the launcher draws its colours opaque).
- One of Android's names, any case, lower-cased with `Locale.ROOT` (so `WHITE` is white in every locale; `WHİTE` is
  not a colour): black, darkgray, gray, lightgray, white, red, green, blue, yellow, cyan, magenta,
  aqua, fuchsia, darkgrey, grey, lightgrey, lime, maroon, navy, olive, purple, silver, teal. Android's values, which
  differ from CSS for some: black `#000000`, darkgray/darkgrey `#444444`, gray/grey `#888888`, lightgray/lightgrey
  `#CCCCCC`, white `#FFFFFF`, red `#FF0000`, green and lime `#00FF00`, blue `#0000FF`, yellow `#FFFF00`, cyan and
  aqua `#00FFFF`, magenta and fuchsia `#FF00FF`, maroon `#800000`, navy `#000080`, olive `#808000`, purple
  `#800080`, silver `#C0C0C0`, teal `#008080`.

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
  treating the pixels as sRGB. The cells come from the pixels that are drawn: the ARGB_8888 bitmap, or, on a low-RAM
  device that draws an opaque image as RGB_565, that bitmap read back (each channel widened to 8 bits as Android
  draws it).
- **Size**: full size (the console), or the device's power-of-two sample (`inSampleSize`), with `iw`, `ih` and the
  cells taken from the sampled bitmap. Sampling can change the ranges a little (see the reference note above).
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
  `(fx, fy)` (default `(0.5, 0.5)`, which is Android's `CENTER_CROP`), `clamp(v, a, b) = max(a, min(b, v))`.
  Written exactly like that, not as a range check: `iw - cw` can come out a hair below 0 from rounding (1440 x 3200 in
  393 x 851 dp), where Kotlin's `coerceIn(0.0, iw - cw)` would throw. The focal point is in the **upright** image
  (after the EXIF orientation), 0..1 on each axis; one that isn't a finite number (a broken document) is the centre. The image's
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
- **Letterbox** (fit): when the box, **clipped to the window first**, reaches outside the image rectangle, the
  background colour joins the bounds. On the tight axis the image can come out a hair short of the window, so a box
  touching 0 or `W` would count as letterbox; no launcher box comes within 8 dp of an edge. A box with no cell and no letterbox under it has the background colour as both bounds.
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

The dim is decided over all controls first (every one must reach 3:1, not any), then the plates. A control reports
`busy` and its ratio, both from its dimmed bounds, but never gets a plate; when 30 % isn't enough the console warns.
A text's `busy` is also judged on its dimmed bounds.

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
  (`cellWidth - 8 - 16` dp; labels break into lines in it as "Label lines" says). Each plate is centred under its icon, then kept inside
  the grid's content edges (`left` = the first cell's start, `right` = the last cell's end):
  `x = min(max(iconCentre - width / 2, left), right - width)`. Vectors: `rowPlates`.
- **Corner radius**: 10 dp (the console's small radius, `--radius-sm`).
- **Header**: one plate around the title text only, not the full row.
- **App labels**: all labels of a grid row share one width (see Label rows); each centred under its icon.
- **Other text** (hold screen, splash): one plate per text view. A single-app kiosk is previewed as its pinned app
  over the image; the splash and hold screens aren't measured.
- **No plate**: buttons (the outlined Exit button, Open again) and the empty-state callout, which have their own fill.
- A plate's box is its region: its bounds come from the cells under it (plus the margin).

## Label lines (3.3)

An app label breaks into lines at word boundaries only, never inside a word, at its own size (it is never shrunk:
large fonts must stay large). The same rule with or without an image. In order:

0. **Cap**: only the label's first 256 code points are laid out, cut at the last grapheme cut (5) at or before the
   256th code point, so the cap never splits a cluster (or a surrogate pair). When there is no grapheme cut at or
   before it (a single cluster longer than the cap), the cut is right after the 256th code point. Labels come from installed apps and can
   be any length; 256 is far more than 3 lines can show, and it bounds the work below (the cap segments the first 256
   code points and a short lookahead after them, whatever the label's length).
1. **Text**: the capped label with every run of whitespace (U+0009, U+000A, U+000D, U+0020) turned into one U+0020,
   trimmed. Other spaces, such as U+00A0, are part of a word. Empty: no lines.
2. **Pieces**: the words (split at U+0020), each split further at its break points: after a hyphen `-` (U+002D) or a
   slash `/` (U+002F) that has another character after it in the word, and before and after every CJK character
   (U+3040 to U+30FF, U+3400 to U+4DBF, U+4E00 to U+9FFF, U+F900 to U+FAFF: kana and Han, which are written without
   spaces). A run of characters in any other script with no space is one piece, however long ("Supercalifragilistic",
   "Timesheets"). Hangul, Thai and the like are not split (Hangul uses spaces; Thai needs a dictionary).
3. **Width**: `measure(s)`, the width of a string as the platform's text measure gives it with the label's paint (on
   Android `Paint.measureText`; in the console the canvas `measureText` with the label's font). The vectors give a
   width per character instead, and `measure(s)` is then the sum over the characters of `s`.
4. **Greedy fill**, at most `maxLines` lines (2; the agent allows 3 above font scale 1, see Drawing), in a text box
   `box` wide (`cellWidth - 24` dp, as above, with or without an image). Each piece in turn: a piece that starts a word follows a space, one
   that continues a word follows nothing. While the lines so far are fewer than `maxLines - 1`:
   - the current line is empty and the piece alone is wider than `box`: the piece becomes a line of its own,
     ellipsized (5);
   - the current line is empty: the piece starts it;
   - the current line plus the piece (with its space) is at most `box`: the piece joins it;
   - otherwise the current line is finished and the piece is tried again on a new line.

   When `maxLines - 1` lines are finished, the last line is everything left (the current line and every remaining
   piece, with their spaces), ellipsized (5).
5. **Ellipsis**: a line at most `box` wide stays as it is. Otherwise it becomes its longest prefix, trailing spaces
   removed, such that the prefix followed by `…` (U+2026), measured as one string with the same paint, is at most
   `box`; when no prefix fits (or only spaces do), `…` alone. A prefix ends at a **grapheme cut**: an extended grapheme
   cluster boundary (UAX #29) that is also
   - not right after U+200D;
   - not right after a prepended concatenation mark: U+0600 to U+0605, U+06DD, U+070F, U+0890, U+0891, U+08E2,
     U+110BD, U+110CD (3.5);
   - not right before a combining mark (general category M), U+200D, U+FE0F, a skin tone (U+1F3FB to U+1F3FF) or a
     tag (U+E0020 to U+E007F);
   - not inside an Indic conjunct (3.5, as Unicode 15.1's GB9c): not before a consonant that follows a consonant, then
     any run of linkers and extenders with at least one linker in it (consonant (extender|linker)* linker
     (extender|linker)* × consonant). Linkers are the viramas U+094D (Devanagari), U+09CD (Bengali), U+0ACD
     (Gujarati), U+0B4D (Oriya), U+0C4D (Telugu) and U+0D4D (Malayalam); extenders are general category Mn or Me and
     U+200D; consonants are U+0915 to U+0939, U+0958 to U+095F, U+0978 to U+097F; U+0995 to U+09A8, U+09AA to
     U+09B0, U+09B2, U+09B6 to U+09B9, U+09DC, U+09DD, U+09DF, U+09F0, U+09F1; U+0A95 to U+0AA8, U+0AAA to U+0AB0,
     U+0AB2, U+0AB3, U+0AB5 to U+0AB9, U+0AF9; U+0B15 to U+0B28, U+0B2A to U+0B30, U+0B32, U+0B33, U+0B35 to U+0B39,
     U+0B5C, U+0B5D, U+0B5F, U+0B71; U+0C15 to U+0C28, U+0C2A to U+0C39, U+0C58 to U+0C5A; U+0D15 to U+0D3A.
     (Unicode's own extenders for this rule also take a few spacing vowel signs; that differs only for a vowel sign
     right after a virama, which is not valid text);
   - between two regional indicators, only after an even number of them counted from the start of their run (and
     always there); after the last one of a run, as any other boundary.

   General categories and boundaries come from the platform's own Unicode data (on Android `android.icu`, for both).
   On current data the conditions add only "no cut after U+200D before a non-emoji"; on older data they keep emoji
   sequences, flags, skin tones, conjuncts and prepended marks as current Unicode does. So, given these conditions,
   the cuts are the same on every platform for the scripts and sequences the vectors cover; a script or sequence they
   don't cover can still differ between Unicode versions. The console uses `Intl.Segmenter` with
   `granularity: "grapheme"`, the agent `android.icu.text.BreakIterator.getCharacterInstance`, each with these
   conditions. A prefix's width grows with its length, so the longest one can be found by bisection over the cuts.
6. **Direction** (3.5: UAX #9 rule P2, pinned): lines are made in logical order, and the whole label has one
   direction, for every line, whatever a line holds. Going through the capped label's code points in order:
   - skip unassigned code points (general category Cn): a code point newer than the device's Unicode data (a new
     emoji, say) is never taken as strong, so it can't change the answer. A label whose first strong letter is in a
     script newer than the device's data (Arabic Extended-B or Hanifi Rohingya on older Android, for instance) is
     unassigned there too, so it is skipped, and the label can still read left to right on that device;
   - an isolate initiator (U+2066, U+2067, U+2068) skips everything up to its matching U+2069 (to the end when there
     is none); a U+2069 with no initiator open is skipped;
   - the first code point of bidi class L makes the label left to right, R or AL right to left. Embeddings and
     overrides (U+202A to U+202E) are not strong; digits, punctuation and symbols aren't either.

   With none, the label is left to right. A Hebrew label with a Latin word on its second line still reads right to
   left there; its ellipsis is at the logical end, on the left. Vectors: `direction`.
7. **Placeholder letter** (3.6): where an app is shown without its own icon (the console's preview draws a lettered
   tile; on the device PackageManager always gives an icon), the tile shows the capped label's first grapheme
   (between the cuts of 5) that holds a visible code point, in upper case where the script has case (the default
   full case mapping, so `ß` becomes `SS`). Clusters made only of format characters (general category Cf: LRM, RLM,
   ALM, isolates, embeddings, ZWJ, ZWNJ and the like), whitespace (Zs, Zl, Zp) or controls (Cc) are skipped. With no
   visible code point, there is no letter: the tile stays blank. Vectors: `iconLetter`.

The agent lays the label out with exactly these lines (each one already fits, so the platform never breaks one
again). Why `cellWidth - 24` stays: the row plate may be at most the cell less 8 dp (8 dp between plates), and 8 dp of
plate padding either side leaves 24 dp; a wider text box would need a narrower padding or plates that touch. Vectors:
`labelLines`.

## Drawing

Bottom to top: the background colour, the image (fill or fit, placed as above), the background colour at `d / 50`
over the whole window, each plate (background colour at `k / 50`), then the launcher.

Everything is recomputed when any input changes: the image, the colours, the fit mode or focal point (a new
configuration), the window size (rotation, fold, split screen), the font scale, or the text layout. The vectors and
the console's preview are at the default font size (1.0); at a larger font scale the agent allows 3 label lines and
lays out (and measures) its own text, with the same rules.

**The Exit button** floats at the bottom centre, 24 dp above the bottom of the content area (above the navigation bar
or gesture area). The grid's scrolling content ends with room for it: the button's laid-out height (taller at large
font scales; measured at the width it is given, the content width less its own side margins) plus 24 dp plus 8 dp. So
at the end of the scroll no label or plate is under it; mid-scroll it may float over the grid, as a floating control
does. A grid that doesn't scroll ends well above it. The console's preview draws
the button where the device does and leaves the same room.

## What the console shows

One large preview of the launcher at device scale, scaled to fit, with a Phone / Tablet choice and a Rotate button
(the crop, dim and plates are recomputed for the turned window as on the device; the turn is animated unless the
viewer prefers reduced motion), and four thumbnails beneath it that select the large view: phone portrait
(360 x 780 dp), phone landscape, tablet 4:3 landscape (1024 x 768 dp) and tablet 16:10 portrait (800 x 1280 dp). Each
shape reads, for example, "Text 4.6:1 Pass (on plate)" (the lowest worst-case text contrast; "on plate" when some
text needs one) and "Image dimmed 22%".

## Vectors

`proto/vectors/kiosk-scrim.json` version 3.6:

- `parsing`: stored value to colour, or null (ASCII-only hex, `Locale.ROOT` names).
- `palette`: stored colours to background, text, secondary (ties and the black/white fallback included).
- `blends`: `blend(over, px, step)` at exact half steps (half up, not half even).
- `geometry`: image size, window, mode and focal point to the image rectangle (`x`, `y`, `w`, `h`, `scale`), compared
  to 6 decimals (doubles): centred, extreme focal points, very wide and very tall images, square, fit.
- `rowPlates`: icon centres, label text widths, cell width and content edges to each plate's `x` and `w`: a row of 7
  cells (function level) with one long label, a capped row, a short row, and clamping at the edges.
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
  Added after review (no change to the rules): a right edge inside a cell (`ceil`), texture that is busy only
  undimmed, two controls with one weak, a box past the window edge in fit (letterbox on the clipped box), and a box
  edge exactly on a cell boundary under a non-terminating scale, and one a hair below a boundary (`floor` and `ceil`
  on the value as computed, never on a rounded one); a `#707070` background (white fallback), Android's
  green and darkgray; and a geometry case where `iw - cw` is a hair below 0.
- `labelLines` (3.3, 3.4): a label's `text` (already normalized), the `widths` of its UTF-16 code units, in order (a
  surrogate pair's width on its high half, 0 on its low half; the same unit always has the same width), the
  `ellipsisWidth`, the `box` and `maxLines`,
  to the `expected` lines (strings, `…` included). Cases: fits, two words that just fit and just don't, a single word
  wider than the box, an over-wide first word then a second, three words overflowing two lines, a hyphen, a slash, a
  hyphen at the end of a word, CJK text without spaces, Latin then CJK, a long word with no spaces, a right-to-left
  label, three lines, a label longer than the 256-code-point cap, combining marks at the ellipsis, and a box narrower
  than one character and the ellipsis. Combining marks have width 0 in the vectors. 3.4 adds three flags (a cut after
  a whole flag only), an emoji ZWJ family, a skin tone, conjoining Hangul jamo, a ZWJ between letters and the cap
  landing inside a cluster. 3.5 adds Hindi and Marathi conjuncts, a Bengali and a Malayalam conjunct, a prepended
  mark and a cluster longer than the cap. The expected results follow UAX #29 of Unicode 15.1 with the conditions
  above; the agent passes them with Android 7's, 8's, 11's and 15's own data.
- `direction` (3.5): a label's `text` to `ltr` or `rtl`: Latin first, Hebrew first, RLM, ALM, LRM then Hebrew, a flag
  then Hebrew, a Roman numeral then Hebrew, RLE then Latin, digits first, neutral only, an isolate around Hebrew, an
  isolate left open, a stray PDI, a stray PDI before an isolate, an emoji newer than Unicode 8 then Hebrew, and Arabic.
- `iconLetter` (3.6): a `label` to its placeholder letter, or null: a Devanagari conjunct, a conjunct with a vowel
  sign, a flag, a ZWJ family, a skin tone, RLM then Hebrew, an isolated Hebrew word, a leading space and LRM, ZWJ
  alone, format characters and spaces only, an empty label, a Latin letter with a combining accent, and `ß`.

## What 3.2 changes

- Fit mode (`fill` default, `fit` letterboxed) and the focal point, with exact geometry; old agents keep centre-crop
  fill.
- The cell source is pinned: 8-bit sRGB ARGB_8888 after the JPEG-only EXIF orientation, Display P3 converted to
  sRGB; full size or a power-of-two sample. The console's full-size read is the conservative reference.
- Label rows: plate width at most the cell less 8 dp, text box `cellWidth - 24` dp, centred under the icon and
  clamped to the grid's content edges.
- Settled: half-up blending, `busy` as a span over 48, doubles throughout, ASCII hex and `Locale.ROOT` names, the
  palette ties, the dim before the plates, controls never plated, recompute triggers, font scale 1.0 in the vectors.

## What 3.3 changes

- Label lines: capped at 256 code points; word boundaries only (and after a hyphen or slash inside a word, and between CJK characters), a word
  wider than its box ellipsized on its own line, never split; greedy fill; the last line takes the rest, ellipsized.
  Before, a label wrapped as the platform's text layout chose, which split words that didn't fit ("Timesheet / s").
- The grid leaves room for the Exit button at the end of its scroll, at any font scale.

## What 3.6 changes

- The placeholder letter (Label lines, 7), with vectors.
- Direction: skipping unassigned code points keeps a newer code point from becoming strong, but a label that starts
  with a letter of a script newer than the device's data can still read left to right there. Said exactly.
- A sentence on the conjunct rule's extenders and spacing vowel signs (no change to the rule).

## What 3.5 changes

- Grapheme cuts also keep Indic conjuncts (as Unicode 15.1's GB9c, with fixed tables) and prepended concatenation
  marks (GB9b) whole, so older platforms cut like current ones there; categories come from the platform's own data.
- Direction is UAX #9's first strong character, with unassigned code points and isolates skipped.
- The cap when no grapheme cut is at or before code point 256: right after it.
- The Exit button's room is measured at the width the button is given (less its side margins).
- The claim that cuts are the same on every Unicode version is now limited to what the vectors cover.

## What 3.4 changes

- The cap and the ellipsis cut at grapheme cuts (UAX #29 clusters plus the conditions in Label lines, 5): a flag, an
  emoji ZWJ sequence, a skin tone, a letter and its accents and a Hangul syllable are never split.
- An ellipsis is measured together with the text before it (shaping between the two can add a fraction of a pixel).
- A label has one direction, from its first strong character, on every line.
- One text box for every app label, with or without an image: `cellWidth - 24` dp, so adding an image never changes
  how a label breaks (the agent padded labels on an image only, which made the box the cell less 16 dp, and less 8 dp
  without one).

## Not (yet) done

- Swapping the text colour to the one that reads best on the image.
- Japanese line-breaking rules (kinsoku): a line may start with a long-vowel mark (U+30FC) or a small kana. Halfwidth
  katakana (U+FF66 to U+FF9F) and CJK Extension B and later are not CJK under Label lines (2), so a label in them is one
  unbreakable word, ellipsized.
