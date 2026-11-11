// The kiosk background preview: the launcher on four screen shapes with the agent's scrim and the text contrast it
// reaches, in the configuration editor too, free of axe violations. jsdom has no canvas, so the pixel sampling is
// replaced with fixed samples.
import { render, screen, waitFor, within } from '@testing-library/react';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { KioskBackgroundImageInfo } from '../../api/configurations';
import { AppRoutes } from '../../App';
import { renderInConsole } from '../../test/console';

const sampling = vi.hoisted(() => ({ samples: [] as number[] | null }));
vi.mock('../../data/kioskScrim', async (importOriginal) => {
  const real = await importOriginal<typeof import('../../data/kioskScrim')>();
  return { ...real, sampleCrop: () => sampling.samples };
});

import { kioskColours } from '../../data/kioskScrim';
import { KioskBackgroundPreview, PREVIEW_SHAPES } from './KioskPreview';

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

const WHITE = Array<number>(100).fill(0xffffff);
const DARK = Array<number>(100).fill(0x141414);

async function violations(): Promise<string[]> {
  const res = await axe.run(document.body, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
    rules: { 'color-contrast': { enabled: false } },
  });
  return res.violations.map((v) => `${v.id}: ${v.help} — ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
}

const fakeImage = async () => ({ naturalWidth: 3840, naturalHeight: 2160 }) as HTMLImageElement;

afterEach(() => {
  sampling.samples = [];
  vi.unstubAllGlobals();
});

describe('KioskBackgroundPreview', () => {
  const preview = (colours = kioskColours(null, null), exitVisible = true) =>
    render(
      <div className="v2">
        <KioskBackgroundPreview
          src="/files/acme/beach.jpg"
          width={3840}
          height={2160}
          colours={colours}
          appNames={['Scanner', 'Notes']}
          exitVisible={exitVisible}
          loadImage={fakeImage}
        />
      </div>,
    );

  it('shows the four shapes, each with the scrim the agent draws and a passing contrast readout', async () => {
    sampling.samples = WHITE;
    preview();
    const list = screen.getByRole('list', { name: 'Preview on devices' });
    const shapes = within(list).getAllByRole('listitem');
    expect(shapes).toHaveLength(PREVIEW_SHAPES.length);
    await waitFor(() => expect(screen.getAllByText(/Pass/)).toHaveLength(4));
    for (const s of PREVIEW_SHAPES) {
      const screenImg = screen.getByRole('img', { name: new RegExp(`^${s.label}: the image cropped at the centre, dimmed \\d+%, text contrast`) });
      const scrim = Number((screenImg as HTMLElement).style.getPropertyValue('--kbg-scrim'));
      expect(scrim).toBeGreaterThan(0.6);
      expect(screenImg.querySelector('img')!.getAttribute('src')).toBe('/files/acme/beach.jpg');
      // The launcher's header, the kiosk's apps and the Exit button sit over it.
      expect(within(screenImg as HTMLElement).getByText('MDMesh Kiosk')).toBeTruthy();
      expect(within(screenImg as HTMLElement).getByText('Scanner')).toBeTruthy();
      expect(within(screenImg as HTMLElement).getByText('Exit kiosk')).toBeTruthy();
    }
    // Readout: with the scrim, and the image alone (what fails without it).
    expect(screen.getAllByText(/Dimmed \d+% · image alone 1\.\d:1/)).toHaveLength(4);
    // The readout is the 98th percentile, and says so.
    expect(screen.getAllByText(/worst 2% of the image aside/)).toHaveLength(4);
    expect(screen.getByText(/busy or bright behind the text/)).toBeTruthy();
    expect(await violations()).toEqual([]);
  });

  it('leaves a calm image undimmed', async () => {
    sampling.samples = DARK;
    preview();
    await waitFor(() => expect(screen.getAllByText(/No dimming needed/)).toHaveLength(4));
    expect(screen.queryByText(/busy or bright/)).toBeNull();
    const first = screen.getByRole('img', { name: /^Phone, portrait/ }) as HTMLElement;
    expect(first.style.getPropertyValue('--kbg-scrim')).toBe('0');
  });

  it('uses the kiosk’s own colours, made readable as the agent makes them', async () => {
    sampling.samples = DARK;
    // Grey text on grey: the launcher (and so the preview) uses black instead.
    preview(kioskColours('#777777', '#888888'), false);
    const first = screen.getByRole('img', { name: /^Phone, portrait/ }) as HTMLElement;
    expect(first.style.getPropertyValue('--kbg-bg')).toBe('#777777');
    expect(first.style.getPropertyValue('--kbg-text')).toBe('#000000');
    expect(within(first).queryByText('Exit kiosk')).toBeNull();
    await waitFor(() => expect(screen.getAllByText(/Pass/)).toHaveLength(4));
  });

  it('says so when the browser can’t read the pixels', async () => {
    sampling.samples = null;
    preview();
    await waitFor(() => expect(screen.getAllByText('Contrast unavailable in this browser')).toHaveLength(4));
  });

  it('says so when the image doesn’t load', async () => {
    sampling.samples = DARK;
    render(
      <KioskBackgroundPreview src="/x.png" width={3840} height={2160} colours={kioskColours(null, null)} appNames={[]} exitVisible={false} loadImage={async () => null} />,
    );
    await waitFor(() => expect(screen.getAllByText('The image could not be loaded to measure it')).toHaveLength(4));
  });

  it('lays the four screens out in one even grid with their captions under a shared stage', () => {
    sampling.samples = DARK;
    preview();
    const list = screen.getByRole('list', { name: 'Preview on devices' });
    expect(list.classList.contains('v2-kbg__shapes')).toBe(true);
    for (const item of within(list).getAllByRole('listitem')) {
      expect(item.querySelector('.v2-kbg__stage > .v2-kbg__screen')).not.toBeNull();
      expect(item.querySelector('.v2-kbg__stage + .v2-kbg__caption')).not.toBeNull();
    }
  });

  it('is a disclosure the keyboard can fold', () => {
    sampling.samples = DARK;
    preview();
    const summary = screen.getByText('Preview on devices');
    expect(summary.tagName).toBe('SUMMARY');
    expect(summary.closest('details')!.open).toBe(true);
  });

  it('warns about a small image', () => {
    render(
      <KioskBackgroundPreview src="/x.png" width={800} height={600} colours={kioskColours(null, null)} appNames={[]} exitVisible={false} loadImage={fakeImage} />,
    );
    expect(screen.getByText(/600 px on its short side/)).toBeTruthy();
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
    sampling.samples = WHITE;
    const calls = open({ backgroundImageFileId: 42, backgroundImageUrl: INFO.url, backgroundImageSha256: INFO.sha256 });
    expect(await screen.findByRole('img', { name: /Current kiosk background: beach\.jpg/ })).toBeTruthy();
    expect(calls).toContain('/private/configurations/backgroundImage/42');
    const list = screen.getByRole('list', { name: 'Preview on devices' });
    // jsdom loads no images, so the readouts wait for the pixels (the preview tests above cover them).
    expect(within(list).getAllByRole('listitem')).toHaveLength(4);
    expect(within(list).getByRole('img', { name: /^Tablet 4:3, landscape/ })).toBeTruthy();
  });

  it('asks for nothing without an image', async () => {
    const calls = open({});
    expect(await screen.findByRole('button', { name: 'Choose image' })).toBeTruthy();
    expect(calls.some((c) => c.startsWith('/private/configurations/backgroundImage'))).toBe(false);
    expect(screen.queryByText('Preview on devices')).toBeNull();
  });
});
