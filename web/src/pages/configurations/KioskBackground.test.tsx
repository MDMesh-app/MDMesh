// The kiosk background image in the console: the editor's viewer (what is set) and the device page's line, and that
// they are free of axe violations.
import { fireEvent, render, screen, within } from '@testing-library/react';
import axe from 'axe-core';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { KioskBackgroundImageInfo } from '../../api/configurations';
import { DeviceKioskBackground, KioskBackgroundField, hostedPath } from './KioskBackground';

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


async function violations(): Promise<string[]> {
  const res = await axe.run(document.body, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
    rules: { 'color-contrast': { enabled: false } },
  });
  return res.violations.map((v) => `${v.id}: ${v.help} — ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
}


afterEach(() => {
  vi.unstubAllGlobals();
});

describe('hostedPath', () => {
  it('loads a hosted file from the console’s own origin', () => {
    expect(hostedPath('https://mdm.example/files/acme/a%20b.jpg')).toBe('/files/acme/a%20b.jpg');
    expect(hostedPath('/files/x.png')).toBe('/files/x.png');
  });
});

describe('KioskBackgroundField (viewer)', () => {
  const field = (props: Partial<Parameters<typeof KioskBackgroundField>[0]> = {}) => {
    const onFile = vi.fn();
    const onRemove = vi.fn();
    render(
      <div className="v2">
        <span id="lbl">Background image</span>
        <KioskBackgroundField
          fileId={42}
          info={INFO}
          error={null}
          readOnly={false}
          uploading={false}
          onFile={onFile}
          onRemove={onRemove}
          labelId="lbl"
          {...props}
        />
      </div>,
    );
    return { onFile, onRemove };
  };

  it('shows the current image: thumbnail, name, size, upload date and short hash', async () => {
    field();
    const img = screen.getByRole<HTMLImageElement>('img', { name: /Current kiosk background: beach\.jpg, 3840 × 2160 pixels/ });
    expect(img.getAttribute('src')).toBe('/files/acme/kiosk-0123456789ab-beach.jpg');
    expect(screen.getByText('beach.jpg')).toBeTruthy();
    expect(screen.getByText('3840 × 2160 px · 2 MB · JPEG')).toBeTruthy();
    expect(screen.getByText('0123456789ab').getAttribute('title')).toBe(INFO.sha256);
    expect(screen.getByText(/2026/)).toBeTruthy();
    expect(screen.getByText(/location and camera details are removed when you save/)).toBeTruthy();
    expect(await violations()).toEqual([]);
  });

  it('replaces and removes', () => {
    const { onFile, onRemove } = field();
    const group = screen.getByRole('group', { name: 'Background image' });
    fireEvent.click(within(group).getByRole('button', { name: 'Remove' }));
    expect(onRemove).toHaveBeenCalledOnce();
    expect(within(group).getByRole('button', { name: 'Replace image' })).toBeTruthy();
    const input = document.querySelector<HTMLInputElement>('input[type=file]')!;
    expect(input.accept).toBe('image/png,image/jpeg,image/webp');
    const picked = new File(['x'], 'beach.jpg', { type: 'image/jpeg' });
    fireEvent.change(input, { target: { files: [picked] } });
    expect(onFile).toHaveBeenCalledWith(picked);
  });

  it('says why an image can’t be read', () => {
    field({ info: null, error: 'The background image is not an uploaded file of this account.' });
    expect(screen.getByRole('alert').textContent).toMatch(/not an uploaded file/);
  });

  it('without an image offers to choose one, and shows a legacy wallpaper URL as such', async () => {
    field({ fileId: null, info: null });
    expect(screen.getByText(/No image: the kiosk shows its background color/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Choose image' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
    expect(await violations()).toEqual([]);
  });

  it('shows a legacy wallpaper URL as such', () => {
    field({ fileId: null, info: null, legacyUrl: 'https://cdn.example/old.jpg' });
    expect(screen.getByText('https://cdn.example/old.jpg')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeTruthy();
  });

  it('is read-only on a locked configuration', () => {
    field({ readOnly: true });
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Replace image' }).disabled).toBe(true);
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Remove' }).disabled).toBe(true);
  });
});

describe('DeviceKioskBackground', () => {
  it('names the image the device’s configuration sets, and says what isn’t reported', async () => {
    render(
      <MemoryRouter>
        <main className="v2">
          <DeviceKioskBackground image={{ url: INFO.url, sha256: INFO.sha256 }} configurationId={7} configName="Lobby" />
        </main>
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: 'Kiosk background' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Lobby' }).getAttribute('href')).toBe('/configurations/7');
    expect(screen.getByText('0123456789ab')).toBeTruthy();
    expect(screen.getByText(/isn’t reported yet/)).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Kiosk background image' }).getAttribute('src')).toBe('/files/acme/kiosk-0123456789ab-beach.jpg');
    expect(await violations()).toEqual([]);
  });
});
