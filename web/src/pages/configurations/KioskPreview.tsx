// The kiosk background image's preview: the launcher over the image as the agent draws it (centre crop, a light dim
// for the Exit button, a plate behind each text that needs one), in one large view at device scale with Phone/Tablet
// and Rotate, and four thumbnails that select it. Same rules and functions as the agent: data/kioskScrim.ts,
// data/kioskLauncherLayout.ts, docs/kiosk-background-scrim.md (version 3).
import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode, type RefObject } from 'react';
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
const LARGE_MAX_HEIGHT = 520;

const ratioText = (r: number) => (Number.isFinite(r) ? `${(Math.floor(r * 10) / 10).toFixed(1)}:1` : 'n/a');

/** "a", "a and b", "a, b and c". */
function listNames(names: string[]): string {
  return names.length < 2 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/** Thumbnails: a shared screen height (px) and the number of columns, from the width the list has. */
export function thumbnailGrid(width: number): { columns: 1 | 2; height: number } {
  const budget = width - 2 * 14; // button padding and border on both sides
  if (width < 520) return { columns: 1, height: Math.max(40, Math.min(160, Math.floor((budget * 360) / 780))) };
  // Two columns: phone portrait over tablet 4:3 landscape, phone landscape over tablet 16:10 portrait; every caption
  // at least 150 px wide.
  for (let h = 180; h > 40; h--) {
    const first = Math.max(150, (h * 1024) / 768, (h * 360) / 780);
    const second = Math.max(150, (h * 780) / 360, (h * 800) / 1280);
    if (first + second + 2 * 14 + 12 <= width) return { columns: 2, height: h };
  }
  return { columns: 1, height: 40 };
}

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
  const [turning, setTurning] = useState<View | null>(null);
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
    if (turning) return; // ignored while turning; the button keeps focus (aria-disabled, not disabled)
    const next: View = { ...view, orientation: view.orientation === 'portrait' ? 'landscape' : 'portrait' };
    if (prefersReducedMotion()) {
      setView(next);
      return;
    }
    setTurning(next);
    turnTimer.current = window.setTimeout(() => {
      setView(next);
      setTurning(null);
    }, ROTATE_MS);
  };

  const select = (v: View) => {
    window.clearTimeout(turnTimer.current);
    setTurning(null);
    if (v.model !== 'phone') setLastTablet(v.model);
    setView(v);
  };

  const [thumbList, thumbsWidth] = useWidth<HTMLUListElement>();
  const thumbs = thumbnailGrid(thumbsWidth);
  const key = viewKey(view);
  const current = measured?.[key];
  const big = current?.kind === 'ok' ? readout(current.legibility) : null;
  const weakExit = (v: View) => {
    const m = measured?.[viewKey(v)];
    return m?.kind === 'ok' && m.legibility.dim === DIM_MAX_STEP && (m.legibility.plates.exit?.ratio ?? Infinity) < CONTROL_MIN;
  };
  const shown = THUMBNAILS.some((t) => viewKey(t) === key) ? THUMBNAILS : [...THUMBNAILS, view];
  const weakViews = shown.filter(weakExit);
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
      {weakViews.length ? (
        <Callout tone="warn" icon={<IconWarning size={16} />}>
          In {listNames(weakViews.map((v) => `${DEVICES[v.model].label} ${v.orientation}`.toLowerCase()))}, the image is too bright behind the Exit button
          for it to stand out at 3:1, even dimmed 30%. A calmer or darker image at the bottom of the screen helps.
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
          aria-disabled={turning != null}
          aria-label={`Rotate to ${view.orientation === 'portrait' ? 'landscape' : 'portrait'}`}
        >
          <IconSync size={14} /> Rotate
        </Button>
      </div>
      <LargeScreen
        view={view}
        next={turning}
        layout={layouts[key]}
        legibility={current?.kind === 'ok' ? current.legibility : null}
        src={src}
        colours={colours}
      >
        <p className="v2-kbg__large-readout" aria-live="polite">
          <span className="v2-kbg__shape-name">{viewLabel(view)}</span>
          {big ? (
            <>
              <Readout r={big} />
              <span className="v2-kbg__meta">{big.dim}</span>
              {weakExit(view) ? <ExitWarning /> : null}
            </>
          ) : (
            <span className="v2-kbg__readout">{status(current)}</span>
          )}
        </p>
      </LargeScreen>
      <ul
        ref={thumbList}
        className="v2-kbg__thumbs"
        aria-label="Screen shapes"
        style={{ gridTemplateColumns: thumbs.columns === 2 ? 'auto auto' : 'auto' }}
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
                  scale={thumbs.height / viewWindow(t).h}
                />
                <span className="v2-kbg__caption">
                  <span className="v2-kbg__shape-name">{viewLabel(t)}</span>
                  {r ? (
                    <>
                      <Readout r={r} />
                      <span className="v2-kbg__meta">{r.dim}</span>
                      {weakExit(t) ? <ExitWarning /> : null}
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

function ExitWarning() {
  return (
    <span className="v2-kbg__readout is-warn">
      <IconWarning size={14} />
      <span>Exit button under 3:1</span>
    </span>
  );
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
  next,
  layout,
  legibility,
  src,
  colours,
  children,
}: {
  view: View;
  /** The view a Rotate turns to, while it turns; null otherwise. */
  next: View | null;
  layout: LauncherLayout;
  legibility: Legibility | null;
  src: string;
  colours: KioskColours;
  /** The readout, under the frame and aligned with its left edge. */
  children: ReactNode;
}) {
  const [frame, available] = useWidth<HTMLDivElement>();
  const fitScale = (v: View) => {
    const win = viewWindow(v);
    return Math.min(available / win.w, LARGE_MAX_HEIGHT / win.h);
  };
  const { w, h } = viewWindow(view);
  const scale = fitScale(view);
  // One continuous turn: the screen rotates a quarter about its centre while it moves and scales onto the box the
  // turned view takes; at the end the turned view replaces it, in place, with no transition.
  const nextBox = next ? { w: viewWindow(next).w * fitScale(next), h: viewWindow(next).h * fitScale(next) } : null;
  const turn = nextBox
    ? {
        '--kbg-turn-dx': `${(nextBox.w - w * scale) / 2}px`,
        '--kbg-turn-dy': `${(nextBox.h - h * scale) / 2}px`,
        '--kbg-turn-fit': fitScale(next!) / scale,
        '--kbg-turn-ms': `${ROTATE_MS}ms`,
      }
    : {};
  const boxW = Math.max(w * scale, nextBox?.w ?? 0);
  const boxH = Math.max(h * scale, nextBox?.h ?? 0);
  const r = legibility ? readout(legibility) : null;
  const description = `${viewLabel(view)}: the image cropped at the centre${r ? `, ${r.dim.toLowerCase()}, ${r.text.toLowerCase()}` : ''}`;
  return (
    <div ref={frame} className="v2-kbg__large">
      <div className="v2-kbg__large-figure" style={{ width: Math.floor(boxW) }}>
        <div className="v2-kbg__large-stage" style={{ height: Math.ceil(boxH) }}>
          <div className={`v2-kbg__large-turn${next ? ' is-turning' : ''}`} style={turn as CSSProperties}>
            <ScaledScreen layout={layout} legibility={legibility} src={src} colours={colours} scale={scale} label={description} />
          </div>
        </div>
        {children}
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
  // Below 9 px glyphs don't scale evenly (thumbnails): text is drawn as a bar of its line's width instead.
  const tiny = (sp: number) => sp * s < 9;
  const bar = (width: number, lineH: number, key?: number) => (
    <span key={key} className="v2-kbg__text-bar" style={{ width: width * s, height: Math.max(1, lineH * s * 0.42), marginBlock: lineH * s * 0.29 }} />
  );
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
        {tiny(layout.header.size) ? bar(layout.header.w, layout.header.h) : layout.header.text}
      </span>
      {layout.cells.map((c, i) => (
        <span key={`cell-${i}`}>
          <span className="v2-kbg__icon" style={{ ...px(c.icon, s), borderRadius: 14 * s, fontSize: c.icon.w * 0.42 * s }}>
            {c.name.slice(0, 1).toUpperCase()}
          </span>
          <span
            className="v2-kbg__label"
            style={{ ...px(c.label, s), fontSize: layout.labelSize * s, lineHeight: `${layout.labelLineHeight * s}px` }}
          >
            {c.lines.map((line, k) =>
              tiny(layout.labelSize) ? (
                bar(Math.min(c.label.w, c.lineWidth), layout.labelLineHeight, k)
              ) : (
                <span key={k} className="v2-kbg__label-line">
                  {line}
                </span>
              ),
            )}
          </span>
        </span>
      ))}
      {layout.exit ? (
        <span
          className="v2-kbg__exit"
          style={{
            ...px(layout.exit.button, s),
            fontSize: layout.exit.size * s,
            borderRadius: 10 * s,
            borderWidth: Math.max(1, s),
            lineHeight: `${layout.exit.button.h * s - 2 * Math.max(1, s)}px`,
          }}
        >
          {tiny(layout.exit.size) ? bar(layout.exit.button.w * 0.5, layout.exit.button.h * 0.5) : 'Exit kiosk'}
        </span>
      ) : null}
    </div>
  );
}
