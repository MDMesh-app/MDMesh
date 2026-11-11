# Kiosk background image: the adaptive scrim

A kiosk background image sits under the launcher's text (header, app labels, empty and hold text). A photo can be
any brightness, so the agent lays the kiosk **background colour** over the image at the lowest opacity at which
every text colour still reaches **4.5:1** (WCAG AA for body text; the launcher has no large text). A calm image
stays vivid; a busy or mid-tone one is dimmed just enough. The console's preview runs the same function, so the
contrast it shows is the one the device draws.

Implementations:

- Console: `web/src/data/kioskScrim.ts` (`kioskColours`, `scrimFor`, `sampleCrop`).
- Agent: alongside `KioskPalette` (done on the agent branch).
- Shared test vectors: [`proto/vectors/kiosk-scrim.json`](../proto/vectors/kiosk-scrim.json). Both test suites
  load it; a change to the algorithm changes the vectors, and both sides must pass them.

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
the form before calling `parseColor`. Vectors: `parsing` in the vector file, and palette cases with such values.

## Inputs

1. **Colours** (`KioskPalette.of` on the agent, `kioskColours` in the console), opaque sRGB:
   - `background` = the configuration's background colour (see Parsing), or v2 dark `#0F1115`.
   - `text` = the configuration's text colour while it reaches 4.5:1 on `background`; otherwise the v2 text colour
     (`#E7EBF1` or `#1B2230`) that reads best on it, or black/white when neither reaches 4.5:1.
   - `secondary` = `text` at 80 % over `background` (per channel `round(f * 0.8f + b * (1 - 0.8f))` in 32-bit
     float, as Kotlin computes it), or `text` when that is under 4.5:1.
2. **Samples**: the part of the image the window shows, as opaque sRGB colours.
   - Crop: Android's `CENTER_CROP`, centred: `scale = max(W / iw, H / ih)`, crop `iw' = min(iw, W / scale)`,
     `ih' = min(ih, H / scale)`, centred. `iw`/`ih` are the image size after its EXIF orientation.
   - Grid: 48 samples along the crop's long edge, `round(48 * short / long)` (at least 1) along the short one.
   - Each sample is the area average of its cell (any good downscaler: Android `Bitmap.createScaledBitmap` with
     filtering, the browser's canvas `drawImage` with high-quality smoothing), composited over `background` first so
     transparent pixels count as the colour they show on the device. The vectors fix the function from samples on;
     the downscaler only has to be close.

## Function

```
blend(scrim, px, step) = per channel: floor((scrim * step + px * (50 - step) + 25) / 50)   // integers, half up
ratio(a, b)            = WCAG 2.x contrast of two opaque colours
allowed                = floor(n * 2 / 100)                                               // n = number of samples
covered(text, step)    = the (allowed + 1)-th lowest of ratio(text, blend(background, s, step)) over all samples s

step = the lowest of 0, 1, ..., 50 at which covered(text, step) >= 4.5 and covered(secondary, step) >= 4.5
alpha = step / 50                                                                         // the scrim's opacity
```

- No samples: `step = 0`.
- A solution always exists: at step 50 every blended sample is `background`, which both text colours reach 4.5:1
  on by construction of the palette.
- **The 2 % allowance** keeps a few bright specks (a reflection, a lamp) from dimming a whole photo. It is a count,
  `floor(n * 2 / 100)`, so both sides agree exactly (no floating-point percentile).
- **Integer steps and integer blending** make the result bit-for-bit reproducible across Kotlin and JavaScript. The
  contrast itself is computed in double precision; vectors keep a margin from 4.5 so a last-bit `pow` difference
  can't flip a result. `ratio` values in the vectors are rounded to 2 decimals.

## Drawing

The agent draws, bottom to top: the background colour, the image (`CENTER_CROP`), then the background colour at
`alpha`, then the launcher. It recomputes when the window size changes (rotation, fold), since the crop changes.
The console's preview draws the same with CSS: `background-size: cover; background-position: center`, then the
colour at `alpha`.

## What the console shows

For each previewed shape (phone portrait 9:19.5, phone landscape, tablet 4:3 landscape, tablet 16:10 portrait): the
scrim percentage and the text contrast with and without it (pass at 4.5:1). Without the scrim is what agents before
the scrim show. A scrim over 60 % is flagged as a busy image that will look noticeably dimmed.

## Not (yet) done

- Swapping the text colour to the one that reads best on the image (would keep more of a light image visible).
- Per-label backing pills instead of a full scrim for very busy images.
- The status bar icon colour from the top strip of the scrimmed image (agent only).
