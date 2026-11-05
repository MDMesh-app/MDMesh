// The configuration editor's System updates (Network section): the Headwind-era systemUpdateType (0..3) and its daily
// window (systemUpdateFrom / systemUpdateTo, "HH:MM") as the editor shows, counts, checks and saves them. Rendered
// through the real routes against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Configuration } from '../api/configurations';
import { AppRoutes } from '../App';
import { renderInConsole, stubApi } from '../test/console';

const LOAD = { timeout: 3000 };

/** Configuration 7 ("Warehouse") with `config` on top, no apps, and 2 devices on it. Returns the saves (PUT bodies). */
function open(config: Partial<Configuration> = {}) {
  const stored: Configuration = { id: 7, name: 'Warehouse', kioskMode: false, systemUpdateType: 0, extensions: {}, ...config };
  const puts: Record<string, unknown>[] = [];
  stubApi((path, init) => {
    const p = new URL(path, 'http://console').pathname.replace('/rest', '');
    if (p === '/private/configurations' && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>;
      puts.push(body);
      return { ...stored, ...body };
    }
    if (p === `/private/configurations/${stored.id}`) return stored;
    if (p === `/private/configurations/applications/${stored.id}`) return [];
    if (p.startsWith('/private/applications/search')) return [];
    if (p === '/plugin/main/private/available') return [];
    if (p === '/private/agent/v1/configurations/syncSummary') {
      return [{ configurationId: stored.id, total: 2, inSync: 2, outOfSync: 0, unsupported: 0, neverSeen: 0 }];
    }
    return null;
  });
  renderInConsole(<AppRoutes />, { path: `/configurations/${stored.id}` });
  return puts;
}

const updates = () => screen.findByRole<HTMLSelectElement>('combobox', { name: 'System updates' }, LOAD);
const from = () => screen.getByLabelText<HTMLInputElement>('Window from');
const to = () => screen.getByLabelText<HTMLInputElement>('Window to');
/** Whether the row's control sits in a disabled fieldset (FieldRow `disabled`). */
const rowOff = (input: HTMLElement) => input.closest('fieldset')?.disabled === true;
const saveBar = () => screen.queryByRole('region', { name: 'Unsaved changes' });
const network = () => within(screen.getByRole('navigation', { name: 'Sections' })).getByRole('link', { name: /^Network/ });
const toastText = () => screen.getAllByRole('status').map((t) => t.textContent).join(' | ');
const alertIn = (id: string) => within(document.getElementById(id)!).queryByRole('alert')?.textContent;

async function save() {
  const button = () => within(saveBar()!).getByRole<HTMLButtonElement>('button', { name: 'Save and apply' });
  await waitFor(() => expect(button().disabled).toBe(false));
  fireEvent.click(button());
}

describe('configuration editor: System updates', () => {
  it('sits in Network with its four choices and no unset one, the window off until a daily window is chosen', async () => {
    open();
    const select = await updates();
    expect(select.closest('section')?.id).toBe('network');
    expect([...select.options].map((o) => o.textContent)).toEqual([
      'Device default', 'Install automatically', 'Install in a daily window', 'Postpone (up to 30 days)',
    ]);
    expect(select.value).toBe('0');
    expect(rowOff(from())).toBe(true);
    expect(rowOff(to())).toBe(true);

    fireEvent.change(select, { target: { value: '2' } });
    expect(rowOff(from())).toBe(false);
    expect(rowOff(to())).toBe(false);
  });

  it('counts the type and the window as changes, Discard puts them back, and saves 0..3 and HH:MM', async () => {
    const puts = open();
    fireEvent.change(await updates(), { target: { value: '2' } });
    fireEvent.change(from(), { target: { value: '22:00' } });
    fireEvent.change(to(), { target: { value: '04:00' } });
    expect(within(saveBar()!).getByText('3 unsaved changes')).toBeTruthy();
    expect(network().textContent).toContain('3 changed');

    fireEvent.click(within(saveBar()!).getByRole('button', { name: 'Discard' }));
    expect((await updates()).value).toBe('0');
    expect(from().value).toBe('');
    expect(saveBar()).toBeNull();

    // A window across midnight is fine.
    fireEvent.change(await updates(), { target: { value: '2' } });
    fireEvent.change(from(), { target: { value: '22:00' } });
    fireEvent.change(to(), { target: { value: '04:00' } });
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toMatchObject({ systemUpdateType: 2, systemUpdateFrom: '22:00', systemUpdateTo: '04:00' });
  });

  it.each([
    ['Device default', 0],
    ['Install automatically', 1],
    ['Postpone (up to 30 days)', 3],
  ])('saves %s as %i, keeping the stored window times as they are', async (_label, type) => {
    const puts = open({ systemUpdateType: 2, systemUpdateFrom: '01:00', systemUpdateTo: '05:00' });
    const select = await updates();
    expect(select.value).toBe('2');
    expect(from().value).toBe('01:00');
    fireEvent.change(select, { target: { value: String(type) } });
    expect(rowOff(from())).toBe(true);
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toMatchObject({ systemUpdateType: type, systemUpdateFrom: '01:00', systemUpdateTo: '05:00' });
  });

  it('refuses a daily window without both times, or with equal ones, until it is fixed', async () => {
    const puts = open();
    fireEvent.change(await updates(), { target: { value: '2' } });
    // Nothing is flagged before a save.
    expect(alertIn('network')).toBeUndefined();

    await save();
    await waitFor(() => expect(toastText()).toContain('Fix 1 problem before saving'));
    expect(toastText()).toContain('System updates: Set the time the daily window starts.');
    expect(alertIn('network')).toBe('Set the time the daily window starts.');
    expect(from().getAttribute('aria-invalid')).toBe('true');
    expect(network().textContent).toContain('1 problem');
    expect(document.activeElement).toBe(from());
    expect(puts).toHaveLength(0);

    // From then on the problem follows the edits.
    fireEvent.change(from(), { target: { value: '22:00' } });
    expect(alertIn('network')).toBe('Set the time the daily window ends.');
    expect(to().getAttribute('aria-invalid')).toBe('true');
    fireEvent.change(to(), { target: { value: '22:00' } });
    expect(alertIn('network')).toBe('Pick an end time different from the start.');
    await save();
    await waitFor(() => expect(toastText()).toContain('System updates: Pick an end time different from the start.'));
    expect(document.activeElement).toBe(to());
    expect(puts).toHaveLength(0);

    fireEvent.change(to(), { target: { value: '06:00' } });
    expect(alertIn('network')).toBeUndefined();
    expect(network().textContent).toContain('3 changed');
    await save();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toMatchObject({ systemUpdateType: 2, systemUpdateFrom: '22:00', systemUpdateTo: '06:00' });
  });

  it('refuses a stored daily window the server would refuse, whatever else the save changes', async () => {
    const puts = open({ systemUpdateType: 2, systemUpdateFrom: '25:00', systemUpdateTo: '04:00' });
    await updates();
    const description = screen.getByText('Description').closest('.v2-field')!.querySelector('textarea')!;
    fireEvent.change(description, { target: { value: 'Back room' } });
    await save();
    await waitFor(() => expect(toastText()).toContain('System updates: Set the time the daily window starts.'));
    expect(puts).toHaveLength(0);
  });
});
