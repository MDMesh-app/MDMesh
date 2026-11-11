// The kiosk background image's preview: the launcher on common screen shapes over the image as the agent draws it
// (centre crop, then the adaptive scrim), with the text contrast it reaches. Same scrim function as the agent:
// data/kioskScrim.ts, docs/kiosk-background-scrim.md.
import { useEffect, useId, useMemo, useState, type CSSProperties } from 'react';
import { TEXT_MIN, sampleCrop, scrimFor, toHex, type KioskColours, type Scrim } from '../../data/kioskScrim';
import { Callout, IconCheckSmall, IconCrossSmall, IconInfo, IconWarning } from '../../ui';
import './KioskPreview.css';

/** Screen shapes the preview shows: width and height in the shape's proportions, and the short side in dp. */
export const PREVIEW_SHAPES = [
  { id: 'phone-portrait', label: 'Phone, portrait', w: 9, h: 19.5, dp: 360 },
  { id: 'phone-landscape', label: 'Phone, landscape', w: 19.5, h: 9, dp: 360 },
  { id: 'tablet-landscape', label: 'Tablet 4:3, landscape', w: 4, h: 3, dp: 768 },
  { id: 'tablet-portrait', label: 'Tablet 16:10, portrait', w: 10, h: 16, dp: 800 },
] as const;

/** Above this the image is flagged as one the launcher has to dim a lot. */
export const BUSY_ALPHA = 0.6;
/** An image with a shorter short edge may look soft on a large tablet. */
const SOFT_EDGE = 1080;

const ratioText = (r: number) => (Number.isFinite(r) ? `${(Math.floor(r * 10) / 10).toFixed(1)}:1` : 'n/a');
/* ---------------------------------------------------------------------------------------------------------------
   The previewer
   --------------------------------------------------------------------------------------------------------------- */

/** null scrim: the browser couldn't read the pixels; `loaded` false: the image didn't load. */
type ShapeResult = { scrim: Scrim | null; loaded: boolean };

/** The kiosk launcher on common screen shapes, over the image as the agent draws it. */
export function KioskBackgroundPreview({
  src,
  width,
  height,
  colours,
  appNames,
  exitVisible,
  loadImage = defaultLoadImage,
}: {
  src: string;
  width: number;
  height: number;
  colours: KioskColours;
  appNames: readonly string[];
  exitVisible: boolean;
  /** Loads the image for sampling; tests pass their own (jsdom has no canvas). */
  loadImage?: (src: string) => Promise<HTMLImageElement | null>;
}) {
  const headingId = useId();
  const [results, setResults] = useState<Record<string, ShapeResult> | null>(null);

  useEffect(() => {
    let cancelled = false;
    setResults(null);
    loadImage(src).then((img) => {
      if (cancelled) return;
      const out: Record<string, ShapeResult> = {};
      for (const s of PREVIEW_SHAPES) {
        const samples = img ? sampleCrop(img, img.naturalWidth || width, img.naturalHeight || height, s.w, s.h, colours.background) : null;
        out[s.id] = { scrim: samples ? scrimFor(samples, colours) : null, loaded: img != null };
      }
      setResults(out);
    });
    return () => {
      cancelled = true;
    };
  }, [src, width, height, colours.background, colours.text, colours.secondary, loadImage]);

  const scrims = results ? Object.values(results).map((r) => r.scrim) : [];
  const known = scrims.filter((s): s is Scrim => s != null);
  const maxAlpha = known.length ? Math.max(...known.map((s) => s.alpha)) : 0;
  const apps = appNames.length ? appNames.slice(0, 8) : ['Camera', 'Notes', 'Browser', 'Scanner'];

  return (
    <details className="v2-kbg__preview" open>
      <summary id={headingId}>Preview on devices</summary>
      <p className="v2-kbg__lede">
        The image fills the screen, cropped at the centre. The launcher lays its background color over it just enough
        for text to reach {TEXT_MIN}:1.
      </p>
      {known.length && maxAlpha > BUSY_ALPHA ? (
        <Callout tone="warn" icon={<IconWarning size={16} />}>
          This image is busy or bright behind the text, so the launcher dims it by up to {Math.round(maxAlpha * 100)}%. A
          calmer or darker image keeps more of it visible.
        </Callout>
      ) : null}
      {Math.min(width, height) < SOFT_EDGE ? (
        <Callout tone="info" icon={<IconInfo size={16} />}>
          The image is {Math.min(width, height)} px on its short side, so it may look soft on large tablets.
        </Callout>
      ) : null}
      <ul className="v2-kbg__shapes" aria-labelledby={headingId}>
        {PREVIEW_SHAPES.map((s) => (
          <li key={s.id} className="v2-kbg__slot" style={{ '--kbg-w': s.w, '--kbg-h': s.h, '--kbg-dp': s.dp } as CSSProperties}>
            <ShapePreview
              shape={s}
              src={src}
              colours={colours}
              scrim={results ? results[s.id].scrim : undefined}
              loaded={results ? results[s.id].loaded : true}
              apps={apps}
              exitVisible={exitVisible}
            />
          </li>
        ))}
      </ul>
    </details>
  );
}

function defaultLoadImage(src: string): Promise<HTMLImageElement | null> {
  return new Promise((resolve) => {
    const img = new Image();
    img.decoding = 'async';
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = src;
  });
}

function ShapePreview({
  shape,
  src,
  colours,
  scrim,
  loaded,
  apps,
  exitVisible,
}: {
  shape: (typeof PREVIEW_SHAPES)[number];
  src: string;
  colours: KioskColours;
  /** undefined: still measuring; null: the browser couldn't read the pixels. */
  scrim: Scrim | null | undefined;
  /** False: the image didn't load, so nothing could be measured. */
  loaded: boolean;
  apps: readonly string[];
  exitVisible: boolean;
}) {
  const alpha = scrim?.alpha ?? 0;
  const pass = scrim ? scrim.ratio >= TEXT_MIN : null;
  const description = scrim
    ? `${shape.label}: the image cropped at the centre, dimmed ${Math.round(alpha * 100)}%, text contrast ${ratioText(scrim.ratio)}`
    : `${shape.label}: the image cropped at the centre`;
  const style = useMemo(
    () => ({
      '--kbg-bg': toHex(colours.background),
      '--kbg-text': toHex(colours.text),
      '--kbg-scrim': String(alpha),
    }) as CSSProperties,
    [colours.background, colours.text, alpha],
  );
  return (
    <figure className="v2-kbg__shape">
      <div className="v2-kbg__stage">
        <div className="v2-kbg__screen" style={style} role="img" aria-label={description}>
          <img className="v2-kbg__img" src={src} alt="" />
          <div className="v2-kbg__scrim" />
          <div className="v2-kbg__ui" aria-hidden="true">
            <div className="v2-kbg__header">MDMesh Kiosk</div>
            <div className="v2-kbg__grid">
              {apps.map((name, i) => (
                <div key={`${name}-${i}`} className="v2-kbg__app">
                  <span className="v2-kbg__icon">{name.slice(0, 1).toUpperCase()}</span>
                  <span className="v2-kbg__label">{name}</span>
                </div>
              ))}
            </div>
            {exitVisible ? <span className="v2-kbg__exit">Exit kiosk</span> : null}
          </div>
        </div>
      </div>
      <figcaption className="v2-kbg__caption">
        <span className="v2-kbg__shape-name">{shape.label}</span>
        {scrim === undefined ? (
          <span className="v2-kbg__readout">Measuring…</span>
        ) : scrim === null ? (
          <span className="v2-kbg__readout">
            {loaded ? 'Contrast unavailable in this browser' : 'The image could not be loaded to measure it'}
          </span>
        ) : (
          <>
            <span className={`v2-kbg__readout ${pass ? 'is-pass' : 'is-fail'}`}>
              {pass ? <IconCheckSmall size={14} /> : <IconCrossSmall size={14} />}
              <span>
                Text {ratioText(scrim.ratio)} <strong>{pass ? 'Pass' : 'Fail'}</strong>
              </span>
            </span>
            <span className="v2-kbg__meta">worst 2% of the image aside</span>
            <span className="v2-kbg__meta">
              {scrim.step === 0 ? 'No dimming needed' : `Dimmed ${Math.round(scrim.alpha * 100)}%`} · image alone{' '}
              {ratioText(scrim.ratioWithoutScrim)}
            </span>
          </>
        )}
      </figcaption>
    </figure>
  );
}

