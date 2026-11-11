// The kiosk background preview: one large view at device scale with Phone/Tablet and Rotate, four thumbnails that
// select it, the dim and plates the agent draws, and the readouts; free of axe violations. jsdom has no canvas, so the
// image's cells come from a colour function of the position in image pixels.
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

const preview = (opts: { colours?: ReturnType<typeof kioskColours>; exitVisible?: boolean; apps?: string[] } = {}) =>
  render(
    <div className="v2">
      <KioskBackgroundPreview
        src="/files/acme/beach.jpg"
        width={3840}
        height={2160}
        colours={opts.colours ?? kioskColours(null, null)}
        appNames={opts.apps ?? ['Scanner', 'Notes', 'Inventory', 'Maps', 'Browser']}
        exitVisible={opts.exitVisible ?? true}
        loadImage={fakeImage}
        measure={measure}
      />
    </div>,
  );

const large = () => screen.getByRole('img', { name: /cropped at the centre/ }) as HTMLElement;
const liveText = () => document.querySelector('.v2-kbg__large-readout')!.textContent ?? '';

describe('KioskBackgroundPreview', () => {
  it('shows a large phone in portrait with plates carrying the text and a dim capped at 30%', async () => {
    sampling.paint = WHITE;
    preview();
    await waitFor(() => expect(liveText()).toMatch(/Text \d\.\d:1 Pass \(on plate\)/));
    expect(liveText()).toMatch(/Image dimmed 30%/);
    expect(large().getAttribute('aria-label')).toMatch(/^Phone, portrait: the image cropped at the centre, image dimmed 30%/);
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

  it('rotates: re-crops for the turned window and recomputes the dim and plates', async () => {
    const restore = reducedMotion(false);
    sampling.paint = BRIGHT_BAND;
    preview({ apps: ['Scanner', 'Notes', 'Maps', 'Browser'] });
    await waitFor(() => expect(liveText()).toMatch(/Phone, portrait.*Image not dimmed/));
    expect(liveText()).not.toMatch(/on plate/);
    // The image is read once; every window is cropped from it.
    expect(sampling.reads).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: 'Rotate to landscape' }));
    // The turn is animated: the screen turns first, then the view changes.
    expect(document.querySelector('.v2-kbg__large-turn.is-turning')).not.toBeNull();
    await waitFor(() => expect(liveText()).toMatch(/^Phone, landscape/), { timeout: ROTATE_MS * 10 });
    expect(document.querySelector('.v2-kbg__large-turn.is-turning')).toBeNull();
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
    expect(lines(large())).toBe(lines(thumb));
    expect(large().querySelectorAll('.v2-kbg__label')[0].querySelectorAll('.v2-kbg__label-line').length).toBeLessThanOrEqual(2);
    expect(lines(large())).toMatch(/…$/);
  });
});

describe('thumbnailGrid', () => {
  it('gives all four one height: two columns on a wide page, one on a phone', () => {
    const wide = thumbnailGrid(690);
    expect(wide.columns).toBe(2);
    // The widest pair (tablet 4:3 landscape + phone landscape) fills the row.
    expect(Math.max(150, (wide.height * 1024) / 768) + (wide.height * 780) / 360 + 40).toBeLessThanOrEqual(690);
    expect(thumbnailGrid(330)).toEqual({ columns: 1, height: Math.floor(((330 - 28) * 360) / 780) });
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
  function open(configuration: Record<string, unknown>) {
    const calls: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const p = new URL(String(input), 'http://console').pathname.replace('/rest', '');
        calls.push(p);
        let data: unknown = null;
        if (p === '/private/configurations/7') data = { id: 7, name: 'Lobby', kioskMode: true, extensions: {}, ...configuration };
        else if (p === '/private/configurations/applications/7') data = [];
        else if (p === '/private/configurations/backgroundImage/42') data = INFO;
        else if (p === '/private/agent/v1/configurations/syncSummary') data = [];
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
    expect(screen.getByRole('img', { name: /^Phone, portrait: the image cropped at the centre/ })).toBeTruthy();
  });

  it('asks for nothing without an image', async () => {
    const calls = open({});
    expect(await screen.findByRole('button', { name: 'Choose image' })).toBeTruthy();
    expect(calls.some((c) => c.startsWith('/private/configurations/backgroundImage'))).toBe(false);
    expect(screen.queryByText('Preview on devices')).toBeNull();
  });
});
