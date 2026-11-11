// The kiosk background image's preview: the launcher over the image as the agent draws it (centre crop, a light dim
// for the Exit button, a plate behind each text that needs one), in one large view at device scale with Phone/Tablet
// and Rotate, and four thumbnails that select it. Same rules and functions as the agent: data/kioskScrim.ts,
// data/kioskLauncherLayout.ts, docs/kiosk-background-scrim.md (version 3).
import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type RefObject } from 'react';
import { browserMeasure, launcherLayout, PLATE_RADIUS, type LauncherLayout, type Measure } from '../../data/kioskLauncherLayout';
import {
  CONTROL_MIN,
  DIM_MAX_STEP,
  SCRIM_STEPS,
  TEXT_MIN,
  kioskLegibility,
  readImageCells,
  toHex,
  type Box,
  type KioskColours,
  type Legibility,
} from '../../data/kioskScrim';
import { Button, Callout, IconCheckSmall, IconCrossSmall, IconInfo, IconSync, IconWarning, Segmented } from '../../ui';
import './KioskPreview.css';

/** The devices the preview shows, by their short and long side in dp. */
export const DEVICES = {
  phone: { label: 'Phone', short: 360, long: 780 },
  tablet43: { label: 'Tablet 4:3', short: 768, long: 1024 },
  tablet1610: { label: 'Tablet 16:10', short: 800, long: 1280 },
} as const;
export type Model = keyof typeof DEVICES;
export type Orientation = 'portrait' | 'landscape';
export interface View {
  model: Model;
  orientation: Orientation;
}

/** The four thumbnails under the large view. */
export const THUMBNAILS: readonly View[] = [
  { model: 'phone', orientation: 'portrait' },
  { model: 'phone', orientation: 'landscape' },
  { model: 'tablet43', orientation: 'landscape' },
  { model: 'tablet1610', orientation: 'portrait' },
];

const ALL_VIEWS: readonly View[] = (Object.keys(DEVICES) as Model[]).flatMap((model) =>
  (['portrait', 'landscape'] as const).map((orientation) => ({ model, orientation })),
);

export const viewKey = (v: View) => `${v.model}-${v.orientation}`;
export const viewLabel = (v: View) => `${DEVICES[v.model].label}, ${v.orientation}`;
export function viewWindow(v: View): { w: number; h: number } {
  const d = DEVICES[v.model];
  return v.orientation === 'portrait' ? { w: d.short, h: d.long } : { w: d.long, h: d.short };
}

/** An image with a shorter short edge may look soft on a large tablet. */
const SOFT_EDGE = 1080;
/** The turn's length; none when the viewer prefers reduced motion. */
export const ROTATE_MS = 320;
/** Thumbnails are this tall (px); their width follows the shape. */
const THUMB_HEIGHT = 132;
const LARGE_MAX_HEIGHT = 520;

const ratioText = (r: number) => (Number.isFinite(r) ? `${(Math.floor(r * 10) / 10).toFixed(1)}:1` : 'n/a');

/** What a view reads: text contrast (with plates) and the dim. */
export function readout(l: Legibility): { text: string; dim: string; pass: boolean } {
  const pass = l.textRatio >= TEXT_MIN;
  const text = `Text ${ratioText(l.textRatio)} ${pass ? 'Pass' : 'Fail'}${l.onPlate ? ' (on plate)' : ''}`;
  const dim = l.dim === 0 ? 'Image not dimmed' : `Image dimmed ${Math.round((l.dim / SCRIM_STEPS) * 100)}%`;
  return { text, dim, pass };
}

type Measured = { kind: 'ok'; legibility: Legibility } | { kind: 'unreadable' } | { kind: 'unloaded' };

function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}

/** The kiosk launcher over the image, as the agent draws it. */
export function KioskBackgroundPreview({
  src,
  width,
  height,
  colours,
  appNames,
  exitVisible,
  iconSize,
  loadImage = defaultLoadImage,
  measure,
}: {
  src: string;
  width: number;
  height: number;
  colours: KioskColours;
  appNames: readonly string[];
  exitVisible: boolean;
  iconSize?: string | null;
  /** Loads the image for sampling; tests pass their own (jsdom has no canvas). */
  loadImage?: (src: string) => Promise<HTMLImageElement | null>;
  /** Text widths in dp; the browser's font by default. */
  measure?: Measure;
}) {
  const headingId = useId();
  const [view, setView] = useState<View>(THUMBNAILS[0]);
  const [lastTablet, setLastTablet] = useState<Model>('tablet43');
  const [turning, setTurning] = useState(false);
  const [measured, setMeasured] = useState<Record<string, Measured> | null>(null);
  const measureFn = useMemo(() => measure ?? browserMeasure(), [measure]);
  const apps = useMemo(() => (appNames.length ? appNames.slice(0, 24) : ['Camera', 'Notes', 'Browser', 'Scanner']), [appNames]);

  const layouts = useMemo(() => {
    const out: Record<string, LauncherLayout> = {};
    for (const v of ALL_VIEWS) out[viewKey(v)] = launcherLayout(viewWindow(v), apps, { iconSize, exitVisible }, measureFn);
    return out;
  }, [apps, iconSize, exitVisible, measureFn]);

  useEffect(() => {
    let cancelled = false;
    setMeasured(null);
    loadImage(src).then((img) => {
      if (cancelled) return;
      const out: Record<string, Measured> = {};
      const cells = img ? readImageCells(img, img.naturalWidth || width, img.naturalHeight || height, colours.background) : null;
      for (const v of ALL_VIEWS) {
        const key = viewKey(v);
        out[key] = !img
          ? { kind: 'unloaded' }
          : cells
            ? { kind: 'ok', legibility: kioskLegibility(cells, viewWindow(v), colours, layouts[key].elements) }
            : { kind: 'unreadable' };
      }
      setMeasured(out);
    });
    return () => {
      cancelled = true;
    };
  }, [src, width, height, colours, layouts, loadImage]);

  const turnTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(turnTimer.current), []);

  const rotate = () => {
    if (turning) return;
    const next: View = { ...view, orientation: view.orientation === 'portrait' ? 'landscape' : 'portrait' };
    if (prefersReducedMotion()) {
      setView(next);
      return;
    }
    setTurning(true);
    turnTimer.current = window.setTimeout(() => {
      setView(next);
      setTurning(false);
    }, ROTATE_MS);
  };

  const select = (v: View) => {
    window.clearTimeout(turnTimer.current);
    setTurning(false);
    if (v.model !== 'phone') setLastTablet(v.model);
    setView(v);
  };

  const [thumbList, thumbsWidth] = useWidth<HTMLUListElement>();
  // One row of four on a wide page, two rows of two on a narrow one; the same height for all four, low enough that
  // the widest (phone landscape) fits its column with the button's padding.
  const thumbColumns = thumbsWidth >= 900 ? 4 : 2;
  const columnWidth = (thumbsWidth - 12 * (thumbColumns - 1)) / thumbColumns - 14;
  const thumbHeight = Math.max(40, Math.min(THUMB_HEIGHT, Math.floor((columnWidth * DEVICES.phone.short) / DEVICES.phone.long)));
  const key = viewKey(view);
  const current = measured?.[key];
  const big = current?.kind === 'ok' ? readout(current.legibility) : null;
  const exitWeak = measured
    ? THUMBNAILS.some((t) => {
        const m = measured[viewKey(t)];
        return m?.kind === 'ok' && m.legibility.dim === DIM_MAX_STEP && (m.legibility.plates.exit?.ratio ?? Infinity) < CONTROL_MIN;
      })
    : false;
  const status = (m: Measured | undefined) =>
    !m
      ? 'Measuring…'
      : m.kind === 'unloaded'
        ? 'The image could not be loaded to measure it'
        : m.kind === 'unreadable'
          ? 'Contrast unavailable in this browser'
          : null;

  return (
    <details className="v2-kbg__preview" open>
      <summary id={headingId}>Preview on devices</summary>
      <p className="v2-kbg__lede">
        The image fills the screen, cropped at the centre. Text sits on a plate of the background color where the image
        behind it is too bright or busy (solid over texture), and the image is dimmed by at most 30% for the Exit button.
      </p>
      {Math.min(width, height) < SOFT_EDGE ? (
        <Callout tone="info" icon={<IconInfo size={16} />}>
          The image is {Math.min(width, height)} px on its short side, so it may look soft on large tablets.
        </Callout>
      ) : null}
      {exitWeak ? (
        <Callout tone="warn" icon={<IconWarning size={16} />}>
          The image is too bright behind the Exit button for it to stand out at 3:1, even dimmed 30%. A calmer or
          darker image at the bottom of the screen helps.
        </Callout>
      ) : null}
      <div className="v2-kbg__toolbar">
        <Segmented
          aria-label="Device"
          size="sm"
          options={[
            { value: 'phone', label: 'Phone' },
            { value: 'tablet', label: 'Tablet' },
          ]}
          value={view.model === 'phone' ? 'phone' : 'tablet'}
          onChange={(v) => select({ model: v === 'phone' ? 'phone' : lastTablet, orientation: view.orientation })}
        />
        <Button
          size="sm"
          onClick={rotate}
          disabled={turning}
          aria-label={`Rotate to ${view.orientation === 'portrait' ? 'landscape' : 'portrait'}`}
        >
          <IconSync size={14} /> Rotate
        </Button>
      </div>
      <LargeScreen
        view={view}
        turning={turning}
        layout={layouts[key]}
        legibility={current?.kind === 'ok' ? current.legibility : null}
        src={src}
        colours={colours}
      />
      <p className="v2-kbg__large-readout" aria-live="polite">
        <span className="v2-kbg__shape-name">{viewLabel(view)}</span>
        {big ? (
          <>
            <Readout r={big} />
            <span className="v2-kbg__meta">{big.dim}</span>
          </>
        ) : (
          <span className="v2-kbg__readout">{status(current)}</span>
        )}
      </p>
      <ul
        ref={thumbList}
        className="v2-kbg__thumbs"
        aria-label="Screen shapes"
        style={{ gridTemplateColumns: `repeat(${thumbColumns}, minmax(0, 1fr))` }}
      >
        {THUMBNAILS.map((t) => {
          const m = measured?.[viewKey(t)];
          const r = m?.kind === 'ok' ? readout(m.legibility) : null;
          return (
            <li key={viewKey(t)}>
              <button
                type="button"
                className="v2-kbg__thumb-button"
                aria-pressed={viewKey(t) === key}
                onClick={() => select(t)}
              >
                <ScaledScreen
                  layout={layouts[viewKey(t)]}
                  legibility={m?.kind === 'ok' ? m.legibility : null}
                  src={src}
                  colours={colours}
                  scale={thumbHeight / viewWindow(t).h}
                />
                <span className="v2-kbg__caption">
                  <span className="v2-kbg__shape-name">{viewLabel(t)}</span>
                  {r ? (
                    <>
                      <Readout r={r} />
                      <span className="v2-kbg__meta">{r.dim}</span>
                    </>
                  ) : (
                    <span className="v2-kbg__readout">{status(m)}</span>
                  )}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
      <p className="v2-kbg__footnote">Text contrast is the worst case: against every pixel under each text.</p>
    </details>
  );
}

/** The width of an element as laid out (640 until known, e.g. in jsdom). */
function useWidth<T extends HTMLElement>(): [RefObject<T | null>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(640);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => {
      const w = el.getBoundingClientRect().width;
      if (w > 0) setWidth(w);
    };
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, width];
}

function Readout({ r }: { r: { text: string; pass: boolean } }) {
  return (
    <span className={`v2-kbg__readout ${r.pass ? 'is-pass' : 'is-fail'}`}>
      {r.pass ? <IconCheckSmall size={14} /> : <IconCrossSmall size={14} />}
      <span>{r.text}</span>
    </span>
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

/** The large view: scaled to fit the available width (and a maximum height), turning on Rotate. */
function LargeScreen({
  view,
  turning,
  layout,
  legibility,
  src,
  colours,
}: {
  view: View;
  turning: boolean;
  layout: LauncherLayout;
  legibility: Legibility | null;
  src: string;
  colours: KioskColours;
}) {
  const [frame, available] = useWidth<HTMLDivElement>();
  const { w, h } = viewWindow(view);
  const scale = Math.min(available / w, LARGE_MAX_HEIGHT / h);
  // While turning, the screen rotates a quarter and shrinks to fit its turned footprint.
  const turnedFit = Math.min(1, available / (h * scale), LARGE_MAX_HEIGHT / (w * scale));
  const r = legibility ? readout(legibility) : null;
  const description = `${viewLabel(view)}: the image cropped at the centre${r ? `, ${r.dim.toLowerCase()}, ${r.text.toLowerCase()}` : ''}`;
  return (
    <div ref={frame} className="v2-kbg__large" style={{ height: Math.ceil(h * scale) }}>
      <div
        className={`v2-kbg__large-turn${turning ? ' is-turning' : ''}`}
        style={{ '--kbg-turn-fit': turnedFit, '--kbg-turn-ms': `${ROTATE_MS}ms` } as CSSProperties}
      >
        <ScaledScreen layout={layout} legibility={legibility} src={src} colours={colours} scale={scale} label={description} />
      </div>
    </div>
  );
}

const px = (b: Box, s: number): CSSProperties => ({ left: b.x * s, top: b.y * s, width: b.w * s, height: b.h * s });

/** The launcher drawn from its dp layout at `scale` px per dp. */
function ScaledScreen({
  layout,
  legibility,
  src,
  colours,
  scale: s,
  label,
}: {
  layout: LauncherLayout;
  legibility: Legibility | null;
  src: string;
  colours: KioskColours;
  scale: number;
  /** An accessible description makes it an image; without one it is decorative (inside a labelled button). */
  label?: string;
}) {
  const { w, h } = layout.window;
  const plate = (id: string, box: Box) => {
    const k = legibility?.plates[id]?.step ?? 0;
    return k > 0 ? (
      <div
        key={`plate-${id}`}
        className="v2-kbg__plate"
        data-plate={id}
        style={{ ...px(box, s), borderRadius: PLATE_RADIUS * s, opacity: k / SCRIM_STEPS }}
      />
    ) : null;
  };
  const style = {
    width: w * s,
    height: h * s,
    '--kbg-bg': toHex(colours.background),
    '--kbg-text': toHex(colours.text),
  } as CSSProperties;
  return (
    <div className="v2-kbg__screen" style={style} {...(label ? { role: 'img', 'aria-label': label } : { 'aria-hidden': true })}>
      <img className="v2-kbg__img" src={src} alt="" />
      <div className="v2-kbg__dim" data-dim={legibility?.dim ?? 0} style={{ opacity: (legibility?.dim ?? 0) / SCRIM_STEPS }} />
      {plate('header', layout.headerPlate)}
      {layout.labelPlates.map((p, i) => plate(`label-${i}`, p))}
      <span
        className="v2-kbg__header"
        style={{ ...px(layout.header, s), fontSize: layout.header.size * s, lineHeight: `${layout.header.h * s}px` }}
      >
        {layout.header.text}
      </span>
      {layout.cells.map((c, i) => (
        <span key={`cell-${i}`}>
          <span className="v2-kbg__icon" style={{ ...px(c.icon, s), borderRadius: 14 * s, fontSize: c.icon.w * 0.42 * s }}>
            {c.name.slice(0, 1).toUpperCase()}
          </span>
          <span className="v2-kbg__label" style={{ ...px(c.label, s), fontSize: layout.labelSize * s, lineHeight: `${c.label.h * s}px` }}>
            {c.name}
          </span>
        </span>
      ))}
      {layout.exit ? (
        <span
          className="v2-kbg__exit"
          style={{
            ...px(layout.exit.button, s),
            fontSize: layout.exit.size * s,
            borderRadius: 20 * s,
            lineHeight: `${layout.exit.button.h * s}px`,
          }}
        >
          Exit kiosk
        </span>
      ) : null}
    </div>
  );
}
