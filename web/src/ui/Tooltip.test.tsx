import { useState } from 'react';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Button } from './Button';
import { Modal } from './Modal';
import { Tooltip } from './Tooltip';
import { render } from '../test/render';

/** A tooltip'd button that goes busy while its confirmation dialog runs (Reset passcode, Factory reset). */
function BusyAction() {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  return (
    <>
      <Tooltip content="Sends a new passcode">
        <Button disabled={busy} onClick={() => setOpen(true)}>
          Reset passcode
        </Button>
      </Tooltip>
      <Button onClick={() => setBusy((b) => !b)}>Toggle busy</Button>
      <Modal open={open} onClose={() => setOpen(false)} title="Reset passcode">
        <button type="button" onClick={() => setBusy((b) => !b)}>
          Work
        </button>
      </Modal>
    </>
  );
}

describe('Tooltip around a control that can be disabled', () => {
  it('keeps the same control when disabled flips, with the tip as its description', () => {
    render(<BusyAction />);
    const button = screen.getByRole('button', { name: 'Reset passcode' });
    const described = button.getAttribute('aria-describedby')!;
    expect(document.getElementById(described)?.textContent).toBe('Sends a new passcode');
    fireEvent.click(screen.getByRole('button', { name: 'Toggle busy' }));
    expect(screen.getByRole('button', { name: 'Reset passcode' })).toBe(button);
    expect(button).toHaveProperty('disabled', true);
    fireEvent.click(screen.getByRole('button', { name: 'Toggle busy' }));
    expect(screen.getByRole('button', { name: 'Reset passcode' })).toBe(button);
    button.focus();
    expect(document.activeElement).toBe(button);
  });

  it('a dialog still returns focus to it after it was disabled and enabled meanwhile', async () => {
    render(<BusyAction />);
    const button = screen.getByRole('button', { name: 'Reset passcode' });
    button.focus();
    fireEvent.click(button);
    const dialog = await screen.findByRole('dialog', { name: 'Reset passcode' });
    const work = dialog.querySelector('button:not([aria-label])') as HTMLElement;
    act(() => work.click());
    act(() => work.click());
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(button));
  });
});
