// The kiosk background image's preview: the launcher over the image as the agent draws it (fill around the focal
// point or fit, a light dim for the Exit button, a plate behind each text that needs one), in one large view at device
// scale with Phone/Tablet and Rotate, and four thumbnails that select it. Same rules and functions as the agent:
// data/kioskScrim.ts, data/kioskLauncherLayout.ts, data/kioskLabelLines.ts, docs/kiosk-background-scrim.md (version 3.6).
import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode, type RefObject } from 'react';
import { browserMeasure, FALLBACK_FONT, KIOSK_FONT, launcherLayout, PLATE_RADIUS, type LauncherLayout, type Measure } from '../../data/kioskLauncherLayout';
import {
  CONTROL_MIN,
  DIM_MAX_STEP,
  SCRIM_STEPS,
  TEXT_MIN,
  DEFAULT_FOCAL,
  kioskLegibility,
  placement,
  readImageCells,
  toHex,
  type Box,
  type FitMode,
  type FocalPoint,
  type ImageCells,
  type KioskColours,
  type Legibility,
} from '../../data/kioskScrim';
import { Button, Callout, IconCheckSmall, IconCrossSmall, IconInfo, IconSync, IconWarning, Segmented } from '../../ui';
import { iconLetter } from '../../data/kioskLabelLines';
import './kioskFonts.css';
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
/** Device frames: px around the screen. */
const LARGE_BEZEL = 10;
const THUMB_BEZEL = 4;
const cameraOf = (v: View): 'top' | 'left' | null => (v.model !== 'phone' ? null : v.orientation === 'portrait' ? 'top' : 'left');

const ratioText = (r: number) => (Number.isFinite(r) ? `${(Math.floor(r * 10) / 10).toFixed(1)}:1` : 'n/a');

/** "a", "a and b", "a, b and c". */
function listNames(names: string[]): string {
  return names.length < 2 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/** Thumbnails: a shared screen height (px) and the number of columns, from the width the list has. */
export function thumbnailGrid(width: number): { columns: 1 | 2; height: number } {
  const budget = width - 2 * 5; // the selection ring (5 px) on both sides
  if (width < 520) return { columns: 1, height: Math.max(40, Math.min(160, Math.floor((budget * 360) / 780))) };
  // Two columns: phone portrait over tablet 4:3 landscape, phone landscape over tablet 16:10 portrait; every caption
  // at least 150 px wide.
  for (let h = 180; h > 40; h--) {
    const first = Math.max(150, (h * 1024) / 768, (h * 360) / 780);
    const second = Math.max(150, (h * 780) / 360, (h * 800) / 1280);
    if (first + second + 2 * 2 * 5 + 16 <= width) return { columns: 2, height: h };
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

type Measured = { kind: 'ok'; legibility: Legibility } | { kind: 'unreadable' } | { kind: 'unloaded' } | { kind: 'pinned' };

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
  pinned,
  exitVisible,
  iconSize,
  fit = 'fill',
  focal = DEFAULT_FOCAL,
  loadImage = defaultLoadImage,
  measure,
}: {
  src: string;
  width: number;
  height: number;
  colours: KioskColours;
  /**
   * The grid's apps as the device lists them (configuredKiosk: the main app first, then by package, each once), all
   * of them: the device has no limit, so End of list ends on the last one.
   */
  appNames: readonly string[];
  /**
   * A single-app kiosk: the name of the app it pins. The device opens that app over the image, so there is no grid;
   * the image shows only behind the splash and the hold screen.
   */
  pinned?: string | null;
  exitVisible: boolean;
  iconSize?: string | null;
  /** Fill (cropped around the focal point) or fit (whole, letterboxed). */
  fit?: FitMode;
  focal?: FocalPoint;
  /** Loads the image for sampling; tests pass their own (jsdom has no canvas). */
  loadImage?: (src: string) => Promise<HTMLImageElement | null>;
  /** Text widths in dp; the browser's font by default. */
  measure?: Measure;
}) {
  const headingId = useId();
  const [view, setView] = useState<View>(THUMBNAILS[0]);
  const [lastTablet, setLastTablet] = useState<Model>('tablet43');
  const [turning, setTurning] = useState<View | null>(null);
  /** The large view scrolled to the end of the grid (where the room for the Exit button is). */
  const [atEnd, setAtEnd] = useState(false);
  // The device font (Roboto, kioskFonts.css) is used, so loaded, only once the preview is shown: its section open
  // and on screen. Until then the screens use the browser's fonts and nothing downloads.
  const [open, setOpen] = useState(true);
  const section = useRef<HTMLDetailsElement>(null);
  const inView = useInView(section);
  const [seen, setSeen] = useState(false);
  if (!seen && open && inView) setSeen(true); // once shown, it stays in the device font
  // Labels are laid out with the font's own widths: measure again once its faces have loaded.
  const [fontEpoch, setFontEpoch] = useState(0);
  useEffect(() => {
    const fonts = typeof document !== 'undefined' ? (document as Document & { fonts?: FontFaceSet }).fonts : undefined;
    if (!fonts || measure || !seen) return;
    let live = true;
    const bump = () => live && setFontEpoch((e) => e + 1);
    // Load the device font's two weights now (they would load only once text in them is drawn), then measure again.
    Promise.all([fonts.load(`400 14px ${KIOSK_FONT}`), fonts.load(`500 14px ${KIOSK_FONT}`)]).then(bump, bump);
    fonts.ready.then(bump, () => undefined);
    fonts.addEventListener?.('loadingdone', bump);
    return () => {
      live = false;
      fonts.removeEventListener?.('loadingdone', bump);
    };
  }, [measure, seen]);
  // fontEpoch: a new measure (so a new label cache) per font load.
  const measureFn = useMemo(() => measure ?? browserMeasure(seen ? KIOSK_FONT : FALLBACK_FONT), [measure, seen, fontEpoch]);
  const isPinned = pinned != null;
  // The device's own list, every app of it; none for a pinned app (it covers the screen).
  const apps = useMemo(() => (isPinned ? [] : [...appNames]), [appNames, isPinned]);
  const gridExit = exitVisible && !isPinned;

  const layouts = useMemo(() => {
    const out: Record<string, LauncherLayout> = {};
    for (const v of ALL_VIEWS) out[viewKey(v)] = launcherLayout(viewWindow(v), apps, { iconSize, exitVisible: gridExit }, measureFn);
    return out;
  }, [apps, iconSize, gridExit, measureFn]);

  // The image's cells: read once per image (and background colour), then every view is placed from them.
  const [source, setSource] = useState<{ cells: ImageCells | null; loaded: boolean } | null>(null);
  useEffect(() => {
    let cancelled = false;
    setSource(null);
    loadImage(src).then((img) => {
      if (cancelled) return;
      setSource({
        loaded: img != null,
        cells: img ? readImageCells(img, img.naturalWidth || width, img.naturalHeight || height, colours.background) : null,
      });
    });
    return () => {
      cancelled = true;
    };
  }, [src, width, height, colours.background, loadImage]);

  const imageFit = useMemo(() => ({ mode: fit, focal }), [fit, focal]);
  const measured = useMemo(() => {
    if (!source) return null;
    const out: Record<string, Measured> = {};
    for (const v of ALL_VIEWS) {
      const key = viewKey(v);
      out[key] = !source.loaded
        ? { kind: 'unloaded' }
        : isPinned
          ? { kind: 'pinned' }
          : source.cells
          ? { kind: 'ok', legibility: kioskLegibility(source.cells, viewWindow(v), colours, layouts[key].elements, imageFit) }
          : { kind: 'unreadable' };
    }
    return out;
  }, [source, colours, layouts, imageFit, isPinned]);

  // The large view at the end of its scroll: the grid moves up, the image and the Exit button stay.
  const key = viewKey(view);
  const scrollable = layouts[key].maxScroll > 0;
  // Hidden with a view that doesn't scroll, the choice goes back to Top: coming back never lands on End unasked.
  if (atEnd && !scrollable) setAtEnd(false);
  const scrolled = atEnd && scrollable;
  const largeLayout = useMemo(
    () => (scrolled ? launcherLayout(viewWindow(view), apps, { iconSize, exitVisible: gridExit, scrollY: Infinity }, measureFn) : layouts[key]),
    [scrolled, view, apps, iconSize, gridExit, measureFn, layouts, key],
  );
  const largeMeasured = useMemo((): Measured | undefined => {
    if (!scrolled) return measured?.[key];
    if (!source) return undefined;
    if (!source.loaded) return { kind: 'unloaded' };
    if (!source.cells) return { kind: 'unreadable' };
    return { kind: 'ok', legibility: kioskLegibility(source.cells, viewWindow(view), colours, largeLayout.elements, imageFit) };
  }, [scrolled, measured, key, source, view, colours, largeLayout, imageFit]);

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
  const current = largeMeasured;
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
          : m.kind === 'pinned'
            ? `${pinned} pinned`
            : null;

  return (
    <details
      ref={section}
      className={`v2-kbg__preview${seen ? ' is-shown' : ''}`}
      open={open}
      onToggle={(e) => setOpen(e.currentTarget.open)}
    >
      <summary id={headingId}>Preview on devices</summary>
      {isPinned ? (
        <p className="v2-kbg__lede">
          This kiosk pins {pinned}, which opens over the image and covers the screen, so devices show the image only
          behind the splash while it opens and the hold screen if it keeps closing.{' '}
          {fit === 'fit' ? 'There the whole image is shown, letterboxed with the background color.' : 'There the image fills the screen, cropped around its focal point.'}
        </p>
      ) : (
        <p className="v2-kbg__lede">
          {fit === 'fit' ? 'The whole image is shown, letterboxed with the background color.' : 'The image fills the screen, cropped around its focal point.'} Text sits on a plate of the background color where the image
          behind it is too bright or busy (solid over texture), and the image is dimmed by at most 30% for the Exit button.
        </p>
      )}
      {!isPinned && apps.length === 0 ? (
        <Callout tone="info" icon={<IconInfo size={16} />}>
          This kiosk has no apps shown in kiosk yet, so devices show only the title and a note to add apps.
        </Callout>
      ) : null}
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
          size="md"
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
        {/* Its place is kept while hidden (a view that doesn't scroll), so nothing in the row moves. */}
        <div className={`v2-kbg__scroll-slot${scrollable ? '' : ' is-hidden'}`} inert={!scrollable} aria-hidden={!scrollable || undefined}>
          <Segmented
            aria-label="Grid scroll"
            size="md"
            options={[
              { value: 'top', label: 'Top' },
              { value: 'end', label: 'End of list' },
            ]}
            value={atEnd ? 'end' : 'top'}
            onChange={(v) => setAtEnd(v === 'end')}
          />
        </div>
      </div>
      <LargeScreen
        image={{ w: width, h: height }}
        fit={imageFit}
        view={view}
        next={turning}
        layout={largeLayout}
        legibility={current?.kind === 'ok' ? current.legibility : null}
        src={src}
        colours={colours}
        scrolled={scrolled}
        pinned={pinned ?? null}
      >
        <p className="v2-kbg__large-readout" aria-live="polite">
          <span className="v2-kbg__shape-name">
            {viewLabel(view)}
            {scrolled ? ', end of list' : ''}
          </span>
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
                  image={{ w: width, h: height }}
                  fit={imageFit}
                  bezel={THUMB_BEZEL}
                  camera={cameraOf(t)}
                  layout={layouts[viewKey(t)]}
                  legibility={m?.kind === 'ok' ? m.legibility : null}
                  src={src}
                  colours={colours}
                  scale={(thumbs.height - 2 * THUMB_BEZEL) / viewWindow(t).h}
                  pinned={pinned ?? null}
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
      {isPinned ? null : (
        <p className="v2-kbg__footnote">
          Text contrast is the worst case: against every pixel under each text. Layout shown at 360 dp wide for phones, in
          Roboto, Android’s default font; real devices vary in width and font, and other scripts (such as Chinese or Arabic)
          are shown in this browser’s fonts.
        </p>
      )}
    </details>
  );
}

/** Whether the element is on screen (true where the browser can't tell, e.g. jsdom). */
function useInView(ref: RefObject<HTMLElement | null>): boolean {
  const [inView, setInView] = useState(() => typeof IntersectionObserver !== 'function');
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver !== 'function') return;
    const io = new IntersectionObserver((entries) => setInView(entries.some((e) => e.isIntersecting)));
    io.observe(el);
    return () => io.disconnect();
  }, [ref]);
  return inView;
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
  image,
  fit,
  scrolled,
  pinned,
}: {
  /** Scrolled to the end of the grid. */
  scrolled: boolean;
  /** The app a single-app kiosk pins, drawn instead of the grid; null for the launcher. */
  pinned: string | null;
  image: { w: number; h: number };
  fit: { mode: FitMode; focal: FocalPoint };
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
    return Math.min((available - 2 * LARGE_BEZEL) / win.w, LARGE_MAX_HEIGHT / win.h);
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
  const how = fit.mode === 'fit' ? 'the whole image, letterboxed' : 'the image filling the screen, cropped around its focal point';
  const description = pinned != null
    ? `${viewLabel(view)}: ${pinned} pinned, open over ${how}`
    : `${viewLabel(view)}${scrolled ? ', scrolled to the end of the list' : ''}: ${how}${r ? `, ${r.dim.toLowerCase()}, ${r.text.toLowerCase()}` : ''}`;
  return (
    <div ref={frame} className="v2-kbg__large">
      <div className="v2-kbg__large-figure" style={{ width: Math.floor(boxW + 2 * LARGE_BEZEL) }}>
        <div className="v2-kbg__large-stage" style={{ height: Math.ceil(boxH + 2 * LARGE_BEZEL) }}>
          <div className={`v2-kbg__large-turn${next ? ' is-turning' : ''}`} style={turn as CSSProperties}>
            <ScaledScreen
              layout={layout}
              legibility={legibility}
              src={src}
              colours={colours}
              scale={scale}
              label={description}
              image={image}
              fit={fit}
              bezel={LARGE_BEZEL}
              camera={cameraOf(view)}
              pinned={pinned}
            />
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
  image,
  fit,
  bezel,
  camera,
  pinned = null,
}: {
  /** The app a single-app kiosk pins: drawn over the image instead of the launcher's header, grid and Exit. */
  pinned?: string | null;
  /** The device frame around the screen, px. */
  bezel: number;
  /** A phone's front camera hole, at the top in portrait and the left in landscape; none on tablets. */
  camera: 'top' | 'left' | null;
  /** The image's size and how it is placed: the same geometry as the device (data/kioskScrim placement). */
  image: { w: number; h: number };
  fit: { mode: FitMode; focal: FocalPoint };
  layout: LauncherLayout;
  legibility: Legibility | null;
  src: string;
  colours: KioskColours;
  scale: number;
  /** An accessible description makes it an image; without one it is decorative (inside a labelled button). */
  label?: string;
}) {
  const { w, h } = layout.window;
  const p = placement(image.w, image.h, w, h, fit.mode, fit.focal);
  const placed: Box = { x: p.x, y: p.y, w: p.w, h: p.h };
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
    '--kbg-bezel': `${bezel}px`,
    '--kbg-corner': `${Math.round(Math.min(w, h) * 0.07 * s)}px`,
  } as CSSProperties;
  // The device: its frame (bezel) around the screen, with a phone's camera hole in the bezel, never over the screen
  // (a device keeps its window out of the cutout).
  const hole = Math.max(2, Math.min(8 * s, bezel * 0.6));
  return (
    <div className="v2-kbg__device" style={{ '--kbg-bezel': `${bezel}px`, '--kbg-corner': style['--kbg-corner' as keyof CSSProperties] } as CSSProperties}>
      {camera ? (
        <span
          className="v2-kbg__camera"
          aria-hidden="true"
          style={{
            width: hole,
            height: hole,
            ...(camera === 'top'
              ? { top: (bezel - hole) / 2, left: '50%', marginLeft: -hole / 2 }
              : { left: (bezel - hole) / 2, top: '50%', marginTop: -hole / 2 }),
          }}
        />
      ) : null}
    <div className="v2-kbg__screen" style={style} {...(label ? { role: 'img', 'aria-label': label } : { 'aria-hidden': true })}>
      <img className="v2-kbg__img" src={src} alt="" style={px(placed, s)} />
      {pinned != null ? (
        // The pinned app, open over the image: its own surface, not text on the image (nothing to plate or dim).
        <span
          className="v2-kbg__pinned"
          style={{ left: 24 * s, right: 24 * s, top: h * 0.3 * s, bottom: h * 0.3 * s, borderRadius: 16 * s, padding: 12 * s }}
        >
          <span className="v2-kbg__icon v2-kbg__pinned-icon" style={{ width: 56 * s, height: 56 * s, borderRadius: 14 * s, fontSize: 56 * 0.42 * s }}>
            {iconLetter(pinned) ?? ''}
          </span>
          <span className="v2-kbg__pinned-name" style={{ fontSize: 16 * s, marginTop: 8 * s }}>
            {tiny(16) ? bar(Math.min(w - 72, measureText(pinned, 16)), 22) : pinned}
          </span>
        </span>
      ) : (
        <PlacedLauncher layout={layout} legibility={legibility} s={s} plate={plate} tiny={tiny} bar={bar} />
      )}
    </div>
    </div>
  );
}

/** The launcher's dim, plates, header, grid and Exit button, at `s` px per dp. */
function PlacedLauncher({
  layout,
  legibility,
  s,
  plate,
  tiny,
  bar,
}: {
  layout: LauncherLayout;
  legibility: Legibility | null;
  s: number;
  plate: (id: string, box: Box) => ReactNode;
  tiny: (sp: number) => boolean;
  bar: (width: number, lineH: number, key?: number) => ReactNode;
}) {
  return (
    <>
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
            {iconLetter(c.name) ?? ''}
          </span>
          <span
            className="v2-kbg__label"
            dir={c.dir}
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
    </>
  );
}

/** A rough text width in dp, for the bar a thumbnail draws instead of text too small to read. */
const measureText = (text: string, sp: number) => text.length * sp * 0.55;
