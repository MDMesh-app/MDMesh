import { useState } from 'react';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Menu } from './Menu';
import { Modal } from './Modal';
import { useStickyValue } from './useStickyValue';
import { render } from '../test/render';

/** Opens a Radix menu from the keyboard and picks `item`, as a keyboard user would. */
function pick(trigger: HTMLElement, item: string) {
  trigger.focus();
  fireEvent.keyDown(trigger, { key: 'Enter' });
  fireEvent.click(screen.getByRole('menuitem', { name: item }));
}

/** A row menu whose item opens a dialog kept mounted for its exit, with a field typed into per opening. */
function RowWithDialog() {
  const [subject, setSubject] = useState<string | null>(null);
  const [opening, setOpening] = useState(0);
  const shown = useStickyValue(subject);
  return (
    <>
      <Menu
        label="More for Default"
        items={[
          {
            key: 'rename',
            label: 'Rename',
            onSelect: () => {
              setOpening((n) => n + 1);
              setSubject('Default');
            },
          },
        ]}
      />
      <Modal key={opening} open={subject !== null} onClose={() => setSubject(null)} title={`Rename ${shown ?? ''}`}>
        <Field />
      </Modal>
    </>
  );
}
function Field() {
  const [v, setV] = useState('');
  return <input aria-label="New name" value={v} onChange={(e) => setV(e.target.value)} />;
}

describe('dialogs opened from a menu, kept mounted for their exit', () => {
  it('Escape returns focus to the menu’s trigger (the menu item is gone by then)', async () => {
    render(<RowWithDialog />);
    const trigger = screen.getByRole('button', { name: 'More for Default' });
    pick(trigger, 'Rename');
    expect(await screen.findByRole('dialog', { name: 'Rename Default' })).toBeTruthy();
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it('starts blank on each opening (nothing typed in an earlier one carries over)', async () => {
    render(<RowWithDialog />);
    const trigger = screen.getByRole('button', { name: 'More for Default' });
    pick(trigger, 'Rename');
    fireEvent.change(await screen.findByRole('textbox', { name: 'New name' }), { target: { value: 'Lobby' } });
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    pick(trigger, 'Rename');
    expect(((await screen.findByRole('textbox', { name: 'New name' })) as HTMLInputElement).value).toBe('');
  });
});

describe('Modal: focus return', () => {
  function Opener() {
    const [open, setOpen] = useState(false);
    return (
      <>
        <button type="button" onClick={() => setOpen(true)}>
          Duplicate
        </button>
        <Modal open={open} onClose={() => setOpen(false)} title="Duplicate configuration">
          {/* A field that focuses itself as it mounts, before the dialog's own focus handling runs. */}
          <input aria-label="Name" autoFocus />
        </Modal>
      </>
    );
  }

  it('goes back to the opener even when a field in the dialog has autoFocus', async () => {
    render(<Opener />);
    const opener = screen.getByRole('button', { name: 'Duplicate' });
    opener.focus();
    fireEvent.click(opener);
    await screen.findByRole('dialog', { name: 'Duplicate configuration' });
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(opener));
  });
});

describe('Modal: a press outside', () => {
  async function pressOutside() {
    await act(async () => {
      // Radix starts listening for outside presses a tick after the dialog mounts.
      await new Promise((r) => setTimeout(r, 20));
      const scrim = document.querySelector('.v2-modal__scrim') as HTMLElement;
      // Radix dismisses on the click that follows the press.
      fireEvent.pointerDown(scrim);
      fireEvent.pointerUp(scrim);
      fireEvent.click(scrim);
      await new Promise((r) => setTimeout(r, 20));
    });
  }

  it('closes a dialog', async () => {
    let closed = 0;
    render(
      <Modal open onClose={() => (closed += 1)} title="Rename">
        <p>Body.</p>
      </Modal>,
    );
    await screen.findByRole('dialog');
    await pressOutside();
    expect(closed).toBe(1);
  });

  it('never closes an alertdialog (a destructive confirmation)', async () => {
    let closed = 0;
    render(
      <Modal open role="alertdialog" onClose={() => (closed += 1)} title="Delete configuration?">
        <p>Gone for good.</p>
      </Modal>,
    );
    await screen.findByRole('alertdialog');
    await pressOutside();
    expect(closed).toBe(0);
  });
});

describe('Menu: a click with no pointer behind it (a screen reader’s activation)', () => {
  it('toggles the menu: opens it, then closes it', async () => {
    render(<Menu label="More for Default" items={[{ key: 'rename', label: 'Rename', onSelect: () => {} }]} />);
    const trigger = screen.getByRole('button', { name: 'More for Default' });
    fireEvent.click(trigger, { detail: 0 });
    expect(await screen.findByRole('menu')).toBeTruthy();
    fireEvent.click(trigger, { detail: 0 });
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());
  });
});
