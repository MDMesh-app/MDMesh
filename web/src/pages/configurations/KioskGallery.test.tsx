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
  const all = pages.flat();
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), 'http://console');
      calls.push(`${init?.method ?? 'GET'} ${url.pathname.replace('/rest', '')}${url.search}`);
      let body: unknown = { status: 'OK', message: null, data: null };
      if (url.pathname.endsWith('/backgroundImages')) {
        const page = Number(url.searchParams.get('page'));
        const size = Number(url.searchParams.get('size'));
        body = { status: 'OK', message: null, data: { items: all.slice(page * size, page * size + size), total: all.length, page, size } };
      } else if (url.pathname.endsWith('/delete')) {
        const parts = url.pathname.split('/');
        const id = Number(parts[parts.length - 2]);
        if (refuseDelete && id !== 2) {
          body = { status: 'ERROR', message: 'This image is used by a configuration. Choose another image there first, then delete it.', data: null };
        } else {
          const i = all.findIndex((x) => x.fileIds.includes(id));
          if (i >= 0) {
            all[i] = { ...all[i], fileIds: all[i].fileIds.filter((f) => f !== id) };
            if (all[i].fileIds.length === 0) all.splice(i, 1);
          }
        }
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

  it('shows why the server refused a delete, and the row as the server has it now', async () => {
    // Two files with the same content: the first goes, the second is refused (it became used meanwhile).
    serve([[item(2, { fileIds: [2, 12] })]], true);
    open();
    fireEvent.click(await screen.findByRole('button', { name: 'Delete img2.jpg' }));
    fireEvent.click(screen.getByRole('button', { name: 'Delete img2.jpg for good' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/used by a configuration/);
    expect(screen.getByText('img2.jpg')).toBeTruthy();
    // Reloaded: the row no longer points at the deleted file.
    await waitFor(() => expect(screen.getByRole('list', { name: 'Uploaded images' })).toBe(document.activeElement));
  });

  it('keeps focus in the list: on the confirmation, back on Delete, then on the next row', async () => {
    serve([[item(2), item(3, { sha256: sha('3') })]]);
    open();
    fireEvent.click(await screen.findByRole('button', { name: 'Delete img2.jpg' }));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Delete img2.jpg for good' })));
    fireEvent.click(screen.getByRole('button', { name: 'Keep' }));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Delete img2.jpg' })));
    fireEvent.click(screen.getByRole('button', { name: 'Delete img2.jpg' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete img2.jpg for good' }));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Use img3.jpg' })));
  });

  it('shows more after a delete without skipping or repeating images', async () => {
    const many = Array.from({ length: 45 }, (_, i) => item(100 + i, { sha256: sha('e').slice(0, 58) + String(100000 + i) }));
    serve([many]);
    open();
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(20));
    fireEvent.click(screen.getByRole('button', { name: 'Delete img105.jpg' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete img105.jpg for good' }));
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(19));
    fireEvent.click(screen.getByRole('button', { name: 'Show more (25)' }));
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(39));
    const names = screen.getAllByRole('listitem').map((li) => li.querySelector('.v2-kgal__name')!.textContent);
    expect(new Set(names).size).toBe(39);
    expect(names).not.toContain('img105.jpg');
    expect(names).toContain('img120.jpg');
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
    // One request per 100 images; the list keeps the first n.
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(21));
    expect(screen.queryByRole('button', { name: /Show more/ })).toBeNull();
  });
});
