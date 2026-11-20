// Bulk delete on Devices when the server can't delete everything: the devices not deleted stay selected (and the list
// refreshes); when nothing was deleted it's a plain failure, and the dialog stays with its Delete button to retry; a
// refusal is worded, never the server's key. Rendered through the real routes, against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole } from '../test/console';

const LOAD = { timeout: 10_000 };
const NOW = Date.now();
const DEVICES = ['Alpha', 'Bravo', 'Charlie', 'Delta', 'Echo'].map((name, i) => ({
  id: i + 1,
  number: `D-${i + 1}`,
  description: name,
  configurationId: 3,
  lastUpdate: NOW,
}));

type Envelope = { status: string; message: string | null; data: unknown };

/** The API, with bulk delete answering `deleteAnswer`; records each bulk delete's ids. */
function stub(deleteAnswer: Envelope) {
  const deletes: number[][] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const p = new URL(String(input), 'http://console').pathname.replace('/rest/private', '');
      let body: Envelope = { status: 'OK', message: null, data: null };
      if (p === '/devices/search') {
        body.data = {
          devices: { items: DEVICES, totalItemsCount: DEVICES.length },
          configurations: { 3: { id: 3, name: 'Warehouse' } },
        };
      } else if (p === '/configurations/search') {
        body.data = [{ id: 3, name: 'Warehouse' }];
      } else if (p === '/devices/deleteBulk') {
        deletes.push((JSON.parse(String(init?.body)) as { ids: number[] }).ids);
        body = deleteAnswer;
      }
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
  return deletes;
}

// hidden: the open dialog hides the page from the accessibility tree, and the selection behind it still counts.
const main = () => within(screen.getByRole('main', { hidden: true }));
const box = (n: number) =>
  main().getByRole('checkbox', { name: `Select D-${n}`, hidden: true }) as HTMLInputElement;
const checked = () => [1, 2, 3, 4, 5].filter((n) => box(n).checked);

/** Selects devices 1 to 3 and confirms Delete. @return the dialog */
async function deleteFirstThree() {
  renderInConsole(<AppRoutes />, { path: '/devices' });
  await main().findByText('Charlie', {}, LOAD);
  for (const n of [1, 2, 3]) fireEvent.click(box(n));
  fireEvent.click(await screen.findByRole('button', { name: /^Delete/ }));
  const dialog = await screen.findByRole('alertdialog');
  fireEvent.click(within(dialog).getByRole('button', { name: 'Delete 3 devices' }));
  return dialog;
}

describe('bulk delete that the server could not finish', () => {
  it('keeps the devices not deleted selected and says how many are left', async () => {
    const deletes = stub({
      status: 'ERROR',
      message: 'error.device.delete.partial',
      data: { deleted: [1], notDeleted: [2, 3] },
    });
    await deleteFirstThree();

    expect(await screen.findByText('2 devices not deleted', {}, LOAD)).toBeTruthy();
    expect(screen.getByText('1 device deleted. The other 2 are still selected: delete again to retry.')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    await waitFor(() => expect(checked()).toEqual([2, 3]));
    expect(deletes).toEqual([[1, 2, 3]]);
  });

  it('treats nothing deleted as a plain failure and leaves Delete to retry', async () => {
    const deletes = stub({
      status: 'ERROR',
      message: 'error.device.delete.partial',
      data: { deleted: [], notDeleted: [1, 2, 3] },
    });
    const dialog = await deleteFirstThree();

    expect(await screen.findByText('Devices not deleted', {}, LOAD)).toBeTruthy();
    const retry = within(dialog).getByRole('button', { name: 'Delete 3 devices' }) as HTMLButtonElement;
    await waitFor(() => expect(retry.disabled).toBe(false));
    expect(screen.getByRole('alertdialog')).toBe(dialog);
    expect(checked()).toEqual([1, 2, 3]);
    fireEvent.click(retry);
    await waitFor(() => expect(deletes).toEqual([[1, 2, 3], [1, 2, 3]]));
  });

  it('words a refusal instead of showing the server key', async () => {
    stub({ status: 'ERROR', message: 'error.permission.denied', data: null });
    await deleteFirstThree();

    expect(
      await screen.findByText('You can’t change one or more of these devices, so nothing was changed.', {}, LOAD),
    ).toBeTruthy();
    expect(screen.queryByText('error.permission.denied')).toBeNull();
    expect(screen.getByRole('alertdialog')).toBeTruthy();
  });
});
