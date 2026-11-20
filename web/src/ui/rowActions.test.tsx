// Row and card actions: a lone safe action shows as a button and a lone destructive one stays in the "…"
// (loneButtonItem); and after an action removes its item, focus moves to a neighbour, not to the page
// (useRefocusAfterRemove), or, when it is gone before the dialog closes, from the Modal itself (ui/focusPlace).
import { useState } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { loneButtonItem } from './loneAction';
import type { MenuEntry } from './Menu';
import { Modal } from './Modal';
import { useRefocusAfterRemove } from './useRefocusAfterRemove';

const noop = () => {};

describe('loneButtonItem', () => {
  it('shows one safe entry as a button', () => {
    const edit = { key: 'edit', label: 'Edit', onSelect: noop };
    expect(loneButtonItem([edit])).toBe(edit);
    expect(loneButtonItem(['separator', edit])).toBe(edit);
  });

  it('keeps a lone destructive entry, and two or more, in the menu', () => {
    const del = { key: 'delete', label: 'Delete', danger: true, onSelect: noop };
    const edit = { key: 'edit', label: 'Edit', onSelect: noop };
    expect(loneButtonItem([del])).toBeNull();
    expect(loneButtonItem(['separator', del])).toBeNull();
    expect(loneButtonItem([edit, 'separator', del] satisfies MenuEntry[])).toBeNull();
    expect(loneButtonItem([])).toBeNull();
  });
});

/** A list whose rows' Delete is confirmed (or cancelled) later, like a dialog's. */
function List({ initial }: { initial: string[] }) {
  const [rows, setRows] = useState(initial);
  const [asking, setAsking] = useState<string | null>(null);
  const remember = useRefocusAfterRemove();
  return (
    <main id="v2-main" tabIndex={-1}>
      <ul>
        {rows.map((r) => (
          <li key={r}>
            <a href={`#${r}`}>{r}</a>
            <button
              onClick={() => {
                remember();
                setAsking(r);
              }}
            >
              Delete {r}
            </button>
          </li>
        ))}
      </ul>
      {asking ? (
        <>
          <button onClick={() => setRows((cur) => cur.filter((x) => x !== asking))}>Confirm</button>
          <button onClick={() => setAsking(null)}>Cancel</button>
        </>
      ) : null}
    </main>
  );
}

/**
 * Deletes `row` as a keyboard user would: its button focused and pressed, then the answer given. Like the dialog,
 * answering leaves focus on the trigger it hands focus back to (`elsewhere` moves it on first).
 */
function remove(row: string, answer: 'Confirm' | 'Cancel' = 'Confirm', elsewhere?: HTMLElement) {
  const button = screen.getByRole('button', { name: `Delete ${row}` });
  button.focus();
  fireEvent.click(button);
  elsewhere?.focus();
  act(() => screen.getByRole('button', { name: answer }).click());
  return button;
}

describe('useRefocusAfterRemove', () => {
  it('moves focus to the next item once the item is gone', () => {
    render(<List initial={['A', 'B', 'C']} />);
    remove('A');
    expect(document.activeElement).toBe(screen.getByRole('link', { name: 'B' }));
  });

  it('to the previous item when the last one goes', () => {
    render(<List initial={['A', 'B']} />);
    remove('B');
    expect(document.activeElement).toBe(screen.getByRole('link', { name: 'A' }));
  });

  it('to the page when the list is empty', () => {
    render(<List initial={['A']} />);
    remove('A');
    expect(document.activeElement).toBe(screen.getByRole('main'));
  });

  it('leaves focus alone on a cancel', () => {
    render(<List initial={['A', 'B']} />);
    const a = remove('A', 'Cancel');
    expect(document.activeElement).toBe(a);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('leaves focus alone when it is somewhere else by the time the item goes', () => {
    render(<List initial={['A', 'B', 'C']} />);
    const c = screen.getByRole('link', { name: 'C' });
    remove('A', 'Confirm', c);
    expect(document.activeElement).toBe(c);
  });
});

/** A list whose rows' Delete removes the row while its dialog is still open (the reload beats the dialog's exit). */
function ListWithDialog({ initial, onRemoved }: { initial: string[]; onRemoved?: () => void }) {
  const [rows, setRows] = useState(initial);
  const [asking, setAsking] = useState<string | null>(null);
  return (
    <main id="v2-main" tabIndex={-1}>
      <ul>
        {rows.map((r) => (
          <li key={r}>
            <a href={`#${r}`}>{r}</a>
            <button onClick={() => setAsking(r)}>Delete {r}</button>
          </li>
        ))}
      </ul>
      <input aria-label="Elsewhere" />
      <Modal
        open={asking != null}
        onClose={() => setAsking(null)}
        title="Delete?"
        footer={
          <button
            onClick={() => {
              setRows((cur) => cur.filter((x) => x !== asking));
              setAsking(null);
              // After the commit, as a page the action navigates to would take focus.
              if (onRemoved) queueMicrotask(onRemoved);
            }}
          >
            Confirm
          </button>
        }
      />
    </main>
  );
}

describe('Modal: an opener removed while the dialog is open', () => {
  async function confirmDelete(row: string) {
    const button = screen.getByRole('button', { name: `Delete ${row}` });
    button.focus();
    fireEvent.click(button);
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  }

  it('returns focus to the next item', async () => {
    render(<ListWithDialog initial={['A', 'B']} />);
    await confirmDelete('A');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('link', { name: 'B' })));
  });

  it('to the page when it was the last item', async () => {
    render(<ListWithDialog initial={['A']} />);
    await confirmDelete('A');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('main')));
  });

  it('leaves focus that something else took meanwhile (a page the action opened)', async () => {
    const focusElsewhere = () => document.querySelector<HTMLInputElement>('input[aria-label="Elsewhere"]')?.focus();
    render(<ListWithDialog initial={['A', 'B']} onRemoved={focusElsewhere} />);
    await confirmDelete('A');
    const elsewhere = screen.getByRole('textbox', { name: 'Elsewhere' });
    await waitFor(() => expect(document.activeElement).toBe(elsewhere));
    // Radix hands focus back a tick after the dialog unmounts.
    await act(() => new Promise((r) => setTimeout(r, 20)));
    expect(document.activeElement).toBe(elsewhere);
  });
});
