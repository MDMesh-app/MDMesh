// "Uploaded images": list, choose, delete only unused (with a confirmation), refusals shown, paging; axe-clean.
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { KioskGalleryItem } from '../../api/configurations';
import { ToastProvider } from '../../ui/toast';
import { KioskGallery } from './KioskGallery';

const sha = (c: string) => c.repeat(64);
const item = (n: number, over: Partial<KioskGalleryItem> = {}): KioskGalleryItem => ({
  fileId: n,
  fileIds: [n],
  url: `https://mdm.example/files/acme/kiosk-${'a'.repeat(12)}-img${n}.jpg`,
  name: `img${n}.jpg`,
  format: 'JPEG',
  width: 1920,
  height: 1080,
  orientation: 1,
  size: 500_000,
  sha256: sha(String(n % 10)),
  uploadTime: Date.UTC(2026, 10, 1),
  usedBy: [],
  usedByCount: 0,
  ...over,
});

function serve(pages: KioskGalleryItem[][], refuseDelete = false) {
  const calls: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), 'http://console');
      calls.push(`${init?.method ?? 'GET'} ${url.pathname.replace('/rest', '')}${url.search}`);
      let body: unknown = { status: 'OK', message: null, data: null };
      if (url.pathname.endsWith('/backgroundImages')) {
        const page = Number(url.searchParams.get('page'));
        const all = pages.flat();
        body = { status: 'OK', message: null, data: { items: pages[page] ?? [], total: all.length, page, size: 20 } };
      } else if (url.pathname.endsWith('/delete') && refuseDelete) {
        body = { status: 'ERROR', message: 'This image is used by a configuration. Choose another image there first, then delete it.', data: null };
      }
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
  return calls;
}

const open = (props: Partial<Parameters<typeof KioskGallery>[0]> = {}) => {
  const onChoose = vi.fn();
  render(
    <ToastProvider>
      <div className="v2">
        <KioskGallery open onClose={() => {}} currentFileId={null} onChoose={onChoose} canDelete {...props} />
      </div>
    </ToastProvider>,
  );
  return { onChoose };
};

afterEach(() => vi.unstubAllGlobals());

describe('KioskGallery', () => {
  it('lists the images with their facts and uses, and chooses one', async () => {
    serve([[item(1, { usedBy: [{ id: 7, name: 'Lobby' }], usedByCount: 2 }), item(2)]]);
    const { onChoose } = open({ currentFileId: null });
    const list = await screen.findByRole('list', { name: 'Uploaded images' });
    await waitFor(() => expect(within(list).getAllByRole('listitem')).toHaveLength(2));
    const first = within(list).getAllByRole('listitem')[0];
    expect(first.textContent).toMatch(/img1\.jpg/);
    expect(first.textContent).toMatch(/1920 × 1080 px · 500 KB/);
    expect(first.textContent).toMatch(/Used by Lobby and 1 other/);
    // Used: no Delete.
    expect(within(first).queryByRole('button', { name: /Delete/ })).toBeNull();
    fireEvent.click(within(first).getByRole('button', { name: 'Use img1.jpg' }));
    expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({ fileId: 1 }));
    const res = await axe.run(document.body, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
      rules: { 'color-contrast': { enabled: false } },
    });
    expect(res.violations.map((v) => v.id)).toEqual([]);
  });

  it('marks the image in use here', async () => {
    serve([[item(1)]]);
    open({ currentFileId: 1 });
    expect(await screen.findByText('In use here')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Use img1.jpg' })).toBeNull();
  });

  it('deletes an unused image after a confirmation, every file with its content', async () => {
    const calls = serve([[item(2, { fileIds: [2, 12] })]]);
    open();
    fireEvent.click(await screen.findByRole('button', { name: 'Delete img2.jpg' }));
    fireEvent.click(screen.getByRole('button', { name: 'Delete img2.jpg for good' }));
    await waitFor(() => expect(screen.queryByText('img2.jpg')).toBeNull());
    expect(calls).toContain('POST /private/configurations/backgroundImages/2/delete');
    expect(calls).toContain('POST /private/configurations/backgroundImages/12/delete');
    expect(screen.getByRole('status').textContent).toMatch(/img2\.jpg deleted/);
  });

  it('shows why the server refused a delete', async () => {
    serve([[item(2)]], true);
    open();
    fireEvent.click(await screen.findByRole('button', { name: 'Delete img2.jpg' }));
    fireEvent.click(screen.getByRole('button', { name: 'Delete img2.jpg for good' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/used by a configuration/);
    expect(screen.getByText('img2.jpg')).toBeTruthy();
  });

  it('offers no Delete without the files permission', async () => {
    serve([[item(2)]]);
    open({ canDelete: false });
    await screen.findByText('img2.jpg');
    expect(screen.queryByRole('button', { name: /Delete/ })).toBeNull();
  });

  it('pages', async () => {
    const first = Array.from({ length: 20 }, (_, i) => item(100 + i, { sha256: sha('b').slice(0, 60) + String(1000 + i) }));
    serve([first, [item(3, { sha256: sha('c') })]]);
    open();
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(20));
    fireEvent.click(screen.getByRole('button', { name: 'Show more (1)' }));
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(21));
    expect(screen.queryByRole('button', { name: /Show more/ })).toBeNull();
  });
});
