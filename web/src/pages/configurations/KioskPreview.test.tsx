// The kiosk background preview: one large view at device scale with Phone/Tablet and Rotate, four thumbnails that
// select it, the dim and plates the agent draws, and the readouts; free of axe violations. jsdom has no canvas, so the
// image's cells come from a colour function of the position in image pixels.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { KioskBackgroundImageInfo } from '../../api/configurations';
import { AppRoutes } from '../../App';
import { renderInConsole } from '../../test/console';

type Paint = ((x: number, y: number) => number) | null;
const sampling = vi.hoisted(() => ({ paint: null as Paint, reads: 0 }));
vi.mock('../../data/kioskScrim', async (importOriginal) => {
  const real = await importOriginal<typeof import('../../data/kioskScrim')>();
  return {
    ...real,
    readImageCells: (_img: unknown, iw: number, ih: number) => {
      sampling.reads++;
      const paint = sampling.paint;
      if (!paint) return null;
      const { cellSize, cols, rows } = real.cellGrid(iw, ih);
      const colours = Array.from({ length: cols * rows }, (_, k) =>
        paint(((k % cols) + 0.5) * cellSize, (Math.floor(k / cols) + 0.5) * cellSize),
      );
      return { width: iw, height: ih, cellSize, cols, rows, min: colours, max: colours };
    },
  };
});

import { kioskColours } from '../../data/kioskScrim';
import { KioskBackgroundPreview, ROTATE_MS, THUMBNAILS, readout, thumbnailGrid, viewLabel } from './KioskPreview';

const INFO: KioskBackgroundImageInfo = {
  fileId: 42,
  url: 'https://mdm.example/files/acme/kiosk-0123456789ab-beach.jpg',
  name: 'beach.jpg',
  format: 'JPEG',
  width: 3840,
  height: 2160,
  orientation: 1,
  size: 2_400_000,
  sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
  uploadTime: Date.UTC(2026, 10, 11, 9, 30),
  hasMetadata: true,
};

const WHITE: Paint = () => 0xffffff;
const DARK: Paint = () => 0x141414;
/**
 * A bright band across the 3840 x 2160 image at y 550..1000 px: under the app labels of a landscape phone (which
 * crops the height to 194..1966 px), between the header and the labels of a portrait one.
 */
const BRIGHT_BAND: Paint = (_x, y) => (y >= 550 && y <= 1000 ? 0xffffff : 0x141414);
const measure = (text: string, size: number) => text.length * (size / 2);
const fakeImage = async () => ({ naturalWidth: 3840, naturalHeight: 2160 }) as HTMLImageElement;

async function violations(): Promise<string[]> {
  const res = await axe.run(document.body, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
    rules: { 'color-contrast': { enabled: false } },
  });
  return res.violations.map((v) => `${v.id}: ${v.help} — ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
}

function reducedMotion(on: boolean) {
  const original = window.matchMedia;
  const stub = ((q: string) => ({ ...original(q), matches: on && q.includes('reduce') })) as typeof window.matchMedia;
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: stub });
  return () => Object.defineProperty(window, 'matchMedia', { configurable: true, value: original });
}

afterEach(() => {
  sampling.paint = null;
  sampling.reads = 0;
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const preview = (opts: { colours?: ReturnType<typeof kioskColours>; exitVisible?: boolean; apps?: string[]; pinned?: string } = {}) =>
  render(
    <div className="v2">
      <KioskBackgroundPreview
        src="/files/acme/beach.jpg"
        width={3840}
        height={2160}
        colours={opts.colours ?? kioskColours(null, null)}
        appNames={opts.apps ?? ['Scanner', 'Notes', 'Inventory', 'Maps', 'Browser']}
        pinned={opts.pinned}
        exitVisible={opts.exitVisible ?? true}
        loadImage={fakeImage}
        measure={measure}
      />
    </div>,
  );

const large = () => document.querySelector<HTMLElement>('.v2-kbg__large [role="img"]')!;
const liveText = () => document.querySelector('.v2-kbg__large-readout')!.textContent ?? '';

describe('KioskBackgroundPreview', () => {
  it('shows a large phone in portrait with plates carrying the text and a dim capped at 30%', async () => {
    sampling.paint = WHITE;
    preview();
    await waitFor(() => expect(liveText()).toMatch(/Text \d\.\d:1 Pass \(on plate\)/));
    expect(liveText()).toMatch(/Image dimmed 30%/);
    expect(large().getAttribute('aria-label')).toMatch(/^Phone, portrait: the image filling the screen, cropped around its focal point, image dimmed 30%/);
    expect(Number(large().querySelector<HTMLElement>('.v2-kbg__dim')!.dataset.dim)).toBe(15);
    expect(large().querySelector('[data-plate="header"]')).not.toBeNull();
    expect(large().querySelector('[data-plate="exit"]')).toBeNull();
    expect(large().querySelector('[data-plate="label-0"]')).not.toBeNull();
    expect(within(large()).getByText('MDMesh Kiosk')).toBeTruthy();
    expect(within(large()).getByText('Exit kiosk')).toBeTruthy();
    expect(await violations()).toEqual([]);
  });

  it('leaves a calm image alone: no dim, no plates', async () => {
    sampling.paint = DARK;
    preview();
    await waitFor(() => expect(liveText()).toMatch(/Text \d+\.\d:1 Pass/));
    expect(liveText()).not.toMatch(/on plate/);
    expect(liveText()).toMatch(/Image not dimmed/);
    expect(large().querySelector('.v2-kbg__plate')).toBeNull();
  });

  it('dims nothing without an Exit button', async () => {
    sampling.paint = WHITE;
    preview({ exitVisible: false });
    await waitFor(() => expect(liveText()).toMatch(/Image not dimmed/));
    expect(liveText()).toMatch(/on plate/);
  });

  it('scrolls the large view to the end of a long grid, clear of the Exit button, and back', async () => {
    sampling.paint = WHITE;
    const apps = Array.from({ length: 24 }, (_, i) => (i % 3 === 0 ? 'Maintenance Requests' : i % 3 === 1 ? 'Point-of-Sale' : 'Timesheets'));
    preview({ apps });
    await waitFor(() => expect(liveText()).toMatch(/Text \d\.\d:1/));
    const scroll = screen.getByRole('radiogroup', { name: 'Grid scroll' });
    const firstTop = large().querySelector<HTMLElement>('.v2-kbg__header')!.style.top;
    fireEvent.click(within(scroll).getByRole('radio', { name: 'End of list' }));
    await waitFor(() => expect(liveText()).toMatch(/^Phone, portrait, end of list/));
    expect(large().getAttribute('aria-label')).toMatch(/^Phone, portrait, scrolled to the end of the list:/);
    expect(large().querySelector<HTMLElement>('.v2-kbg__header')!.style.top).not.toBe(firstTop);
    // The last label ends above the Exit button's touch target, with 8 dp of air (px here: the same scale).
    const exit = large().querySelector<HTMLElement>('.v2-kbg__exit')!;
    const labels = [...large().querySelectorAll<HTMLElement>('.v2-kbg__label')];
    const last = labels[labels.length - 1];
    expect(parseFloat(last.style.top) + parseFloat(last.style.height)).toBeLessThan(parseFloat(exit.style.top));
    // Lines are never broken inside a word.
    // (A 62 dp text box at 7 dp per character.)
    const lines = new Set([...large().querySelectorAll('.v2-kbg__label-line')].map((l) => l.textContent));
    expect([...lines].sort()).toEqual(['Mainten…', 'Point-', 'Requests', 'Timeshe…', 'of-Sale']);
    expect(await violations()).toEqual([]);
    fireEvent.click(within(scroll).getByRole('radio', { name: 'Top' }));
    await waitFor(() => expect(liveText()).toMatch(/^Phone, portrait(?!, end)/));
    expect(large().querySelector<HTMLElement>('.v2-kbg__header')!.style.top).toBe(firstTop);
  });

  it('sets each label’s one direction on its element', async () => {
    const he = String.fromCodePoint(0x5e0, 0x5d9, 0x5d4, 0x5d5, 0x5dc);
    preview({ apps: [`${he} POS`, 'Notes'] });
    await waitFor(() => expect(liveText()).toMatch(/Text|Contrast|Measuring/));
    const labels = [...large().querySelectorAll<HTMLElement>('.v2-kbg__label')];
    expect(labels.map((l) => l.getAttribute('dir'))).toEqual(['rtl', 'ltr']);
  });

  it('draws a placeholder icon from the first whole grapheme of the name', async () => {
    const flag = String.fromCodePoint(0x1f1fa, 0x1f1f8);
    preview({ apps: [`${flag} Travel`, 'notes'] });
    await waitFor(() => expect(liveText()).toMatch(/Text|Contrast|Measuring/));
    const icons = [...large().querySelectorAll<HTMLElement>('.v2-kbg__icon')].map((i) => i.textContent);
    expect(icons).toEqual([flag, 'N']);
  });

  it('skips invisible clusters for the placeholder letter, and leaves the tile blank when nothing is visible', async () => {
    const he = String.fromCodePoint(0x5de, 0x5dc, 0x5d0, 0x5d9);
    preview({ apps: [String.fromCodePoint(0x2067) + he + String.fromCodePoint(0x2069), String.fromCodePoint(0x200f, 0x20), String.fromCodePoint(0xdf) + 'auna'] });
    await waitFor(() => expect(liveText()).toMatch(/Text|Contrast|Measuring/));
    const icons = [...large().querySelectorAll<HTMLElement>('.v2-kbg__icon')].map((i) => i.textContent);
    expect(icons).toEqual([String.fromCodePoint(0x5de), '', 'SS']);
  });

  it('draws the placeholder icon from the first cluster by the label cut rule: a whole Indic conjunct', async () => {
    const kshatriya = String.fromCodePoint(0x915, 0x94d, 0x937, 0x924, 0x94d, 0x930, 0x93f, 0x92f);
    preview({ apps: [kshatriya] });
    await waitFor(() => expect(liveText()).toMatch(/Text|Contrast|Measuring/));
    expect(large().querySelector<HTMLElement>('.v2-kbg__icon')!.textContent).toBe(String.fromCodePoint(0x915, 0x94d, 0x937));
  });

  it('offers no scroll when the grid fits', async () => {
    preview();
    await waitFor(() => expect(liveText()).toMatch(/Text|Contrast|Measuring/));
    expect(screen.queryByRole('radiogroup', { name: 'Grid scroll' })).toBeNull();
  });

  it('goes back to Top when it switches to a view that doesn’t scroll, and keeps the toolbar in place', async () => {
    sampling.paint = WHITE;
    // Long enough to scroll on a phone, short enough to fit on a tablet.
    const apps = Array.from({ length: 24 }, (_, i) => `App ${i}`);
    preview({ apps });
    await waitFor(() => expect(liveText()).toMatch(/Text \d\.\d:1/));
    const slot = () => document.querySelector<HTMLElement>('.v2-kbg__scroll-slot')!;
    fireEvent.click(within(screen.getByRole('radiogroup', { name: 'Grid scroll' })).getByRole('radio', { name: 'End of list' }));
    await waitFor(() => expect(liveText()).toMatch(/end of list/));
    fireEvent.click(screen.getByRole('radio', { name: 'Tablet' }));
    await waitFor(() => expect(liveText()).toMatch(/^Tablet 4:3, portrait/));
    // Hidden but in place: not focusable, not announced.
    expect(slot()).not.toBeNull();
    expect(slot().hasAttribute('inert')).toBe(true);
    expect(screen.queryByRole('radiogroup', { name: 'Grid scroll' })).toBeNull();
    fireEvent.click(screen.getByRole('radio', { name: 'Phone' }));
    await waitFor(() => expect(liveText()).toMatch(/^Phone, portrait/));
    expect(liveText()).not.toMatch(/end of list/);
    expect(within(screen.getByRole('radiogroup', { name: 'Grid scroll' })).getByRole('radio', { name: 'Top' }).getAttribute('aria-checked')).toBe('true');
  });

  it('loads the device font only once the preview is open and on screen', async () => {
    const load = vi.fn(async () => []);
    const fonts = Object.assign(new EventTarget(), { load, ready: Promise.resolve() });
    Object.defineProperty(document, 'fonts', { configurable: true, value: fonts });
    let report: (on: boolean) => void = () => undefined;
    const IO = vi.fn(function (this: unknown, cb: IntersectionObserverCallback) {
      report = (on) => cb([{ isIntersecting: on } as IntersectionObserverEntry], this as IntersectionObserver);
      return { observe() {}, disconnect() {}, unobserve() {}, takeRecords: () => [] };
    });
    vi.stubGlobal('IntersectionObserver', IO);
    try {
      render(
        <div className="v2">
          <KioskBackgroundPreview src="/x.jpg" width={3840} height={2160} colours={kioskColours(null, null)} appNames={['Notes']} exitVisible loadImage={fakeImage} />
        </div>,
      );
      const details = document.querySelector('details')!;
      // Open but not on screen yet: nothing loads.
      await new Promise((r) => setTimeout(r, 0));
      expect(load).not.toHaveBeenCalled();
      expect(details.classList.contains('is-shown')).toBe(false);
      // Closed, then scrolled into view: still nothing.
      details.open = false;
      fireEvent(details, new Event('toggle'));
      act(() => report(true));
      await new Promise((r) => setTimeout(r, 0));
      expect(load).not.toHaveBeenCalled();
      // Opened while on screen: both weights load, and the screens switch to the device font.
      details.open = true;
      fireEvent(details, new Event('toggle'));
      await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
      expect(load.mock.calls.map((c) => (c as unknown[])[0])).toEqual(['400 14px "Kiosk Roboto", system-ui, sans-serif', '500 14px "Kiosk Roboto", system-ui, sans-serif']);
      expect(details.classList.contains('is-shown')).toBe(true);
    } finally {
      vi.unstubAllGlobals();
      delete (document as { fonts?: unknown }).fonts;
    }
  });

  it('rotates: re-crops for the turned window and recomputes the dim and plates', async () => {
    const restore = reducedMotion(false);
    sampling.paint = BRIGHT_BAND;
    preview({ apps: ['Scanner', 'Notes', 'Maps', 'Browser'] });
    await waitFor(() => expect(liveText()).toMatch(/Phone, portrait.*Image not dimmed/));
    expect(liveText()).not.toMatch(/on plate/);
    // The image is read once; every window is cropped from it.
    expect(sampling.reads).toBe(1);
    const rotate = screen.getByRole('button', { name: 'Rotate to landscape' });
    rotate.focus();
    fireEvent.click(rotate);
    // One continuous turn: the old view turns onto the box of the new one, then the new one replaces it in place.
    const turn = document.querySelector<HTMLElement>('.v2-kbg__large-turn.is-turning')!;
    expect(turn).not.toBeNull();
    expect(turn.style.getPropertyValue('--kbg-turn-fit')).not.toBe('');
    // Rotate keeps focus while it turns, and ignores activation until done.
    expect(rotate.getAttribute('aria-disabled')).toBe('true');
    expect(rotate.hasAttribute('disabled')).toBe(false);
    expect(document.activeElement).toBe(rotate);
    fireEvent.click(rotate);
    await waitFor(() => expect(liveText()).toMatch(/^Phone, landscape/), { timeout: ROTATE_MS * 10 });
    const after = document.querySelector<HTMLElement>('.v2-kbg__large-turn')!;
    expect(after.classList.contains('is-turning')).toBe(false);
    expect(after.style.getPropertyValue('--kbg-turn-fit')).toBe('');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Rotate to portrait' }));
    expect(screen.getByRole('button', { name: 'Rotate to portrait' }).getAttribute('aria-disabled')).toBe('false');
    // In landscape the band runs under the labels: they get plates.
    expect(liveText()).toMatch(/on plate/);
    expect(large().querySelector('[data-plate^="label-"]')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Rotate to portrait' })).toBeTruthy();
    restore();
  });

  it('turns at once when the viewer prefers reduced motion', async () => {
    const restore = reducedMotion(true);
    sampling.paint = DARK;
    preview();
    await waitFor(() => expect(liveText()).toMatch(/Pass/));
    fireEvent.click(screen.getByRole('button', { name: 'Rotate to landscape' }));
    expect(document.querySelector('.v2-kbg__large-turn.is-turning')).toBeNull();
    expect(liveText()).toMatch(/^Phone, landscape/);
    restore();
  });

  it('switches between phone and tablet, keeping the orientation', async () => {
    const restore = reducedMotion(true);
    sampling.paint = DARK;
    preview();
    await waitFor(() => expect(liveText()).toMatch(/Pass/));
    const device = screen.getByRole('radiogroup', { name: 'Device' });
    fireEvent.click(within(device).getByRole('radio', { name: 'Tablet' }));
    expect(liveText()).toMatch(/^Tablet 4:3, portrait/);
    fireEvent.click(screen.getByRole('button', { name: 'Rotate to landscape' }));
    expect(liveText()).toMatch(/^Tablet 4:3, landscape/);
    fireEvent.click(within(device).getByRole('radio', { name: 'Phone' }));
    expect(liveText()).toMatch(/^Phone, landscape/);
    restore();
  });

  it('has four thumbnails that select the large view, each with its own readout', async () => {
    sampling.paint = BRIGHT_BAND;
    preview({ apps: ['Scanner', 'Notes', 'Maps', 'Browser'] });
    const list = screen.getByRole('list', { name: 'Screen shapes' });
    const buttons = within(list).getAllByRole('button');
    // The visible caption is the name: shape, readout and dim.
    expect(buttons.map((b) => b.querySelector('.v2-kbg__shape-name')!.textContent)).toEqual(THUMBNAILS.map(viewLabel));
    await waitFor(() => expect(buttons[1].textContent).toMatch(/on plate/));
    expect(buttons[0].textContent).not.toMatch(/on plate/);
    expect(buttons[1].querySelector('.v2-kbg__caption')!.textContent).toMatch(/^Phone, landscape.*Text \d+\.\d:1 Pass \(on plate\)/);
    expect(buttons[0].getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(buttons[3]);
    expect(buttons[3].getAttribute('aria-pressed')).toBe('true');
    expect(buttons[0].getAttribute('aria-pressed')).toBe('false');
    expect(liveText()).toMatch(/^Tablet 16:10, portrait/);
    // Same height for all four.
    const heights = buttons.map((b) => parseFloat((b.querySelector('.v2-kbg__screen') as HTMLElement).style.height));
    expect(new Set(heights.map((x) => x.toFixed(3))).size).toBe(1);
    expect(await violations()).toEqual([]);
  });

  it('uses the kiosk’s own colours, made readable as the agent makes them', async () => {
    sampling.paint = DARK;
    preview({ colours: kioskColours('#777777', '#888888'), exitVisible: false });
    expect(large().style.getPropertyValue('--kbg-bg')).toBe('#777777');
    expect(large().style.getPropertyValue('--kbg-text')).toBe('#000000');
    expect(within(large()).queryByText('Exit kiosk')).toBeNull();
  });

  it('says so when the browser can’t read the pixels', async () => {
    sampling.paint = null;
    preview();
    await waitFor(() => expect(liveText()).toMatch(/Contrast unavailable in this browser/));
  });

  it('says so when the image doesn’t load', async () => {
    render(
      <KioskBackgroundPreview src="/x.png" width={3840} height={2160} colours={kioskColours(null, null)} appNames={[]} exitVisible={false} loadImage={async () => null} measure={measure} />,
    );
    await waitFor(() => expect(liveText()).toMatch(/The image could not be loaded to measure it/));
  });

  it('is a disclosure the keyboard can fold, and says the contrast is the worst case', () => {
    preview();
    const summary = screen.getByText('Preview on devices');
    expect(summary.tagName).toBe('SUMMARY');
    expect(screen.getByText(/against every pixel under each text/)).toBeTruthy();
  });

  it('warns about a small image', () => {
    render(
      <KioskBackgroundPreview src="/x.png" width={800} height={600} colours={kioskColours(null, null)} appNames={[]} exitVisible={false} loadImage={fakeImage} measure={measure} />,
    );
    expect(screen.getByText(/600 px on its short side/)).toBeTruthy();
  });
});

describe('the device’s grid', () => {
  it('draws every app of the kiosk, however many: End of list ends on the last one', async () => {
    sampling.paint = WHITE;
    const apps = Array.from({ length: 30 }, (_, i) => `App ${String(i + 1).padStart(2, '0')}`);
    preview({ apps });
    await waitFor(() => expect(liveText()).toMatch(/Text \d\.\d:1/));
    expect(large().querySelectorAll('.v2-kbg__label')).toHaveLength(30);
    fireEvent.click(within(screen.getByRole('radiogroup', { name: 'Grid scroll' })).getByRole('radio', { name: 'End of list' }));
    await waitFor(() => expect(liveText()).toMatch(/end of list/));
    const labels = [...large().querySelectorAll<HTMLElement>('.v2-kbg__label')];
    expect(labels[labels.length - 1].textContent).toBe('App 30');
  });

  it('draws no made-up apps for a kiosk that has none, and says what the device shows', async () => {
    preview({ apps: [] });
    await waitFor(() => expect(liveText()).toMatch(/Text|Contrast|Measuring/));
    expect(large().querySelectorAll('.v2-kbg__icon')).toHaveLength(0);
    expect(screen.getByText(/no apps shown in kiosk yet/)).toBeTruthy();
  });

  it('shows a single-app kiosk as its pinned app over the image, not a launcher grid', async () => {
    sampling.paint = WHITE;
    preview({ pinned: 'Signage Player' });
    await waitFor(() => expect(large().getAttribute('aria-label')).toMatch(/^Phone, portrait: Signage Player pinned/));
    expect(large().querySelector('.v2-kbg__header')).toBeNull();
    expect(large().querySelectorAll('.v2-kbg__label')).toHaveLength(0);
    expect(large().querySelector('.v2-kbg__exit')).toBeNull();
    expect(large().querySelector('.v2-kbg__pinned')?.textContent).toMatch(/Signage Player/);
    expect(screen.getByText(/only behind the splash while it opens and the hold screen/)).toBeTruthy();
    // No launcher text to measure: no contrast readout, and no Top / End of list.
    expect(liveText()).not.toMatch(/Text \d/);
    expect(liveText()).toMatch(/Signage Player pinned/);
    expect(document.querySelector('.v2-kbg__scroll-slot')?.classList.contains('is-hidden')).toBe(true);
    expect(await violations()).toEqual([]);
  });
});

describe('fit and focal point', () => {
  /** Bright left third of the 3840 x 2160 image. */
  const BRIGHT_LEFT: Paint = (x) => (x < 1280 ? 0xffffff : 0x141414);

  it('moves the crop with the focal point and recomputes the plates', async () => {
    sampling.paint = BRIGHT_LEFT;
    const props = { src: '/i.jpg', width: 3840, height: 2160, colours: kioskColours(null, null), appNames: ['Scanner', 'Notes'], exitVisible: false, loadImage: fakeImage, measure };
    const { rerender } = render(<KioskBackgroundPreview {...props} focal={{ x: 0, y: 0.5 }} />);
    await waitFor(() => expect(liveText()).toMatch(/on plate/));
    const img = large().querySelector<HTMLElement>('.v2-kbg__img')!;
    expect(parseFloat(img.style.left)).toBe(0);
    rerender(<KioskBackgroundPreview {...props} focal={{ x: 1, y: 0.5 }} />);
    await waitFor(() => expect(liveText()).not.toMatch(/on plate/));
    expect(parseFloat(large().querySelector<HTMLElement>('.v2-kbg__img')!.style.left)).toBeLessThan(0);
  });

  it('shows the whole image letterboxed in Fit, ignoring the focal point', async () => {
    sampling.paint = DARK;
    render(
      <KioskBackgroundPreview src="/i.jpg" width={3840} height={2160} colours={kioskColours(null, null)} appNames={[]} exitVisible={false} loadImage={fakeImage} measure={measure} fit="fit" focal={{ x: 0, y: 0 }} />,
    );
    await waitFor(() => expect(liveText()).toMatch(/Pass/));
    const img = large().querySelector<HTMLElement>('.v2-kbg__img')!;
    // A 16:9 image on a 360 x 780 phone: full width, centred vertically.
    expect(parseFloat(img.style.left)).toBe(0);
    expect(parseFloat(img.style.top)).toBeGreaterThan(0);
    expect(large().getAttribute('aria-label')).toMatch(/the whole image, letterboxed/);
  });
});

describe('Exit button warning', () => {
  /** Bright only at the bottom of the 3840 x 2160 image (y > 2000 px), where most Exit buttons sit. */
  const BRIGHT_BOTTOM: Paint = (_x, y) => (y > 2000 ? 0xffffff : 0x141414);

  it('names the shapes it applies to, and marks their thumbnails with an icon and text', async () => {
    sampling.paint = BRIGHT_BOTTOM;
    preview();
    const callout = await screen.findByText(/too bright behind the Exit button/);
    expect(callout.textContent).toMatch(/^In phone portrait, tablet 4:3 landscape and tablet 16:10 portrait, the image/);
    const buttons = within(screen.getByRole('list', { name: 'Screen shapes' })).getAllByRole('button');
    const warned = buttons.map((b) => /Exit button under 3:1/.test(b.textContent ?? ''));
    // A landscape phone crops the image's bottom away.
    expect(warned).toEqual([true, false, true, true]);
    expect(buttons[0].querySelector('.v2-kbg__readout.is-warn svg')).not.toBeNull();
    expect(liveText()).toMatch(/Exit button under 3:1/);
  });

  it('is not shown when no shape needs it', async () => {
    sampling.paint = DARK;
    preview();
    await waitFor(() => expect(liveText()).toMatch(/Pass/));
    expect(screen.queryByText(/too bright behind the Exit button/)).toBeNull();
    expect(screen.queryByText(/Exit button under 3:1/)).toBeNull();
  });
});

describe('row plates (3.1)', () => {
  it('gives every label of a row a plate when one needs it, at the same opacity', async () => {
    // A bright patch under the third label of a portrait phone only (its crop starts at x = 1421 px, 2.77 px/dp).
    sampling.paint = (x, y) => (x > 1950 && x < 2100 && y > 360 && y < 440 ? 0xffffff : 0x141414);
    preview({ apps: ['Scanner', 'Notes', 'Maps', 'Browser'], exitVisible: false });
    await waitFor(() => expect(liveText()).toMatch(/on plate/));
    const plates = [0, 1, 2, 3].map((i) => large().querySelector<HTMLElement>(`[data-plate="label-${i}"]`));
    expect(plates.every((p) => p != null)).toBe(true);
    expect(new Set(plates.map((p) => p!.style.opacity)).size).toBe(1);
    expect(large().querySelector('[data-plate="header"]')).toBeNull();
  });
});

describe('the Exit button', () => {
  it('is outlined as on the device: background fill, text-colour edge and label', async () => {
    sampling.paint = DARK;
    preview({ colours: kioskColours('#00676B', '#FFFFFF') });
    const exit = within(large()).getByText('Exit kiosk');
    expect(exit.classList.contains('v2-kbg__exit')).toBe(true);
    expect(exit.style.borderWidth).not.toBe('');
    expect(large().style.getPropertyValue('--kbg-bg')).toBe('#00676B');
  });
});

describe('labels', () => {
  it('are cut the same in the large view and the thumbnails, as the device cuts them', async () => {
    sampling.paint = DARK;
    preview({ apps: ['Signage Player Pro Edition', 'Notes'] });
    const lines = (root: Element) => [...root.querySelectorAll('.v2-kbg__label')][0].textContent;
    const thumb = within(screen.getByRole('list', { name: 'Screen shapes' })).getAllByRole('button')[0];
    // The thumbnail draws the same laid-out lines (as bars at its scale).
    const largeLines = large().querySelectorAll('.v2-kbg__label')[0].querySelectorAll('.v2-kbg__label-line').length;
    expect(thumb.querySelectorAll('.v2-kbg__label')[0].querySelectorAll('.v2-kbg__text-bar').length).toBe(largeLines);
    expect(largeLines).toBeLessThanOrEqual(2);
    expect(lines(large())).toMatch(/…$/);
  });
});

describe('the camera hole', () => {
  it('sits in the bezel, outside the screen, top in portrait and left in landscape', async () => {
    const restore = reducedMotion(true);
    sampling.paint = DARK;
    preview();
    const device = large().parentElement!;
    expect(device.classList.contains('v2-kbg__device')).toBe(true);
    const cam = device.querySelector<HTMLElement>(':scope > .v2-kbg__camera')!;
    expect(cam).not.toBeNull();
    expect(large().querySelector('.v2-kbg__camera')).toBeNull();
    expect(cam.style.top).not.toBe('50%');
    fireEvent.click(screen.getByRole('button', { name: 'Rotate to landscape' }));
    const cam2 = large().parentElement!.querySelector<HTMLElement>(':scope > .v2-kbg__camera')!;
    expect(cam2.style.top).toBe('50%');
    restore();
  });
});

describe('thumbnails', () => {
  it('draw text smaller than 9 px as bars (the large view keeps the text)', async () => {
    sampling.paint = DARK;
    preview();
    const thumb = within(screen.getByRole('list', { name: 'Screen shapes' })).getAllByRole('button')[3];
    expect(thumb.querySelector('.v2-kbg__screen .v2-kbg__text-bar')).not.toBeNull();
    expect(within(thumb.querySelector('.v2-kbg__screen') as HTMLElement).queryByText('MDMesh Kiosk')).toBeNull();
    expect(within(large()).getByText('MDMesh Kiosk')).toBeTruthy();
  });
});

describe('thumbnailGrid', () => {
  it('gives all four one height: two columns on a wide page, one on a phone', () => {
    const wide = thumbnailGrid(690);
    expect(wide.columns).toBe(2);
    // The widest pair (tablet 4:3 landscape + phone landscape) fills the row.
    expect(Math.max(150, (wide.height * 1024) / 768) + (wide.height * 780) / 360 + 36).toBeLessThanOrEqual(690);
    expect(thumbnailGrid(330)).toEqual({ columns: 1, height: Math.floor(((330 - 10) * 360) / 780) });
  });
});

describe('readout', () => {
  it('names the plate and the dim', () => {
    expect(readout({ dim: 11, plates: {}, textRatio: 4.63, onPlate: true })).toEqual({
      text: 'Text 4.6:1 Pass (on plate)', dim: 'Image dimmed 22%', pass: true,
    });
    expect(readout({ dim: 0, plates: {}, textRatio: 4.49, onPlate: false }).text).toBe('Text 4.4:1 Fail');
  });
});

describe('the configuration editor', () => {
  function open(configuration: Record<string, unknown>, assigned: Record<string, unknown>[] = []) {
    const calls: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const p = new URL(String(input), 'http://console').pathname.replace('/rest', '');
        calls.push(p);
        let data: unknown = null;
        if (p === '/private/configurations/7') data = { id: 7, name: 'Lobby', kioskMode: true, extensions: {}, ...configuration };
        else if (p === '/private/configurations/applications/7') data = assigned;
        else if (p === '/private/configurations/backgroundImage/42') data = INFO;
        else if (p === '/private/configurations/backgroundImage/43') data = { ...INFO, fileId: 43, name: 'night.jpg' };
        else if (p === '/private/configurations/backgroundImages') {
          data = { items: [{ ...INFO, fileId: 43, fileIds: [43], name: 'night.jpg', sha256: 'f'.repeat(64), usedBy: [], usedByCount: 0 }], total: 1, page: 0, size: 100 };
        } else if (p === '/private/agent/v1/configurations/syncSummary') data = [];
        return new Response(JSON.stringify({ status: 'OK', message: null, data }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }),
    );
    renderInConsole(<AppRoutes />, { path: '/configurations/7' });
    return calls;
  }

  it('shows the configuration’s image and its preview in the kiosk section', async () => {
    sampling.paint = WHITE;
    const calls = open({ backgroundImageFileId: 42, backgroundImageUrl: INFO.url, backgroundImageSha256: INFO.sha256 });
    expect(await screen.findByRole('img', { name: /Current kiosk background: beach\.jpg/ })).toBeTruthy();
    expect(calls).toContain('/private/configurations/backgroundImage/42');
    // jsdom loads no images, so the readouts wait for the pixels (the preview tests above cover them).
    const list = screen.getByRole('list', { name: 'Screen shapes' });
    expect(within(list).getAllByRole('button')).toHaveLength(4);
    expect(screen.getByRole('img', { name: /^Phone, portrait: the image filling the screen/ })).toBeTruthy();
  });

  it('starts another image chosen from the gallery at the centre, keeping Fill or Fit', async () => {
    sampling.paint = DARK;
    open({ backgroundImageFileId: 42, backgroundImageUrl: INFO.url, backgroundImageSha256: INFO.sha256, backgroundImageFit: 'fill', backgroundImageFocalX: 0.2, backgroundImageFocalY: 0.8 });
    const slider = await screen.findByRole('slider', { name: 'Focal point' });
    expect(slider.getAttribute('aria-valuetext')).toBe('20% across, 80% down');
    fireEvent.click(screen.getByRole('button', { name: 'Choose from uploaded' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Use night.jpg' }));
    await waitFor(() => expect(screen.getByRole('slider', { name: 'Focal point' }).getAttribute('aria-valuetext')).toBe('50% across, 50% down'));
    expect(screen.getByRole('radio', { name: 'Fill' }).getAttribute('aria-checked')).toBe('true');
  });

  const row = (id: number, name: string, pkg: string, extra: Record<string, unknown> = {}) => ({
    id, name, pkg, usedVersionId: 100 + id, action: 1, showIcon: true, selected: true, ...extra,
  });
  const withImage = { backgroundImageFileId: 42, backgroundImageUrl: INFO.url, backgroundImageSha256: INFO.sha256 };

  it('previews the device’s grid: the main app first, then by package, each package once', async () => {
    open({ ...withImage, mainAppId: 103 }, [
      row(1, 'Zebra Scan', 'com.example.zebra'),
      row(2, 'Atlas', 'com.example.atlas'),
      row(3, 'Main Menu', 'com.example.menu'),
      row(4, 'Atlas (2)', 'com.example.atlas'),           // a second row of the same package
      row(5, 'Hidden', 'com.example.hidden', { showIcon: false }),
      row(6, 'Removed', 'com.example.removed', { action: 2 }),
    ]);
    await screen.findByRole('img', { name: /^Phone, portrait: the image filling the screen/ });
    const labels = () => [...document.querySelectorAll('.v2-kbg__large [role="img"] .v2-kbg__label')]
      .map((l) => [...l.querySelectorAll('.v2-kbg__label-line')].map((line) => line.textContent).join(' '));
    await waitFor(() => expect(labels()).toEqual(['Main Menu', 'Atlas', 'Zebra Scan']));
  });

  it('previews a single-app kiosk as the pinned app', async () => {
    open({ ...withImage, mainAppId: 101 }, [row(1, 'Signage Player', 'com.example.signage')]);
    expect(await screen.findByRole('img', { name: /^Phone, portrait: Signage Player pinned/ })).toBeTruthy();
    expect(document.querySelector('.v2-kbg__large .v2-kbg__header')).toBeNull();
  });

  it('asks for nothing without an image', async () => {
    const calls = open({});
    expect(await screen.findByRole('button', { name: 'Choose image' })).toBeTruthy();
    expect(calls.some((c) => c.startsWith('/private/configurations/backgroundImage'))).toBe(false);
    expect(screen.queryByText('Preview on devices')).toBeNull();
  });
});
