import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider, useToast, type ToastApi } from './toast';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

function renderProvider() {
  const api: { push: ToastApi['push'] } = { push: () => {} };
  function Pusher() {
    api.push = useToast().push;
    return <button type="button">Save</button>;
  }
  const view = render(
    <ToastProvider>
      <Pusher />
    </ToastProvider>,
  );
  return { ...view, api };
}

const stack = () => screen.getByRole('region', { name: 'Notifications' });
const toastTitles = () =>
  Array.from(stack().querySelectorAll('.toast .t-title')).map((t) => t.firstChild?.textContent);
/** The toast titled `title` on screen (not its announcement), or null. */
const toastNode = (title: string) =>
  Array.from(document.querySelectorAll('.toast')).find((t) => t.querySelector('.t-title')?.firstChild?.textContent === title) ?? null;
/** The pointer over the stack, as the browser's :hover reports it (jsdom tracks no hover). */
function hover(on: boolean) {
  const el = stack();
  vi.spyOn(el, 'matches').mockImplementation((sel: string) => (sel === ':hover' ? on : Element.prototype.matches.call(el, sel)));
}

describe('ToastProvider', () => {
  it('dismisses a toast after 5 seconds', () => {
    vi.useFakeTimers();
    const { api } = renderProvider();
    act(() => api.push('ok', 'Saved', ''));
    expect(toastNode('Saved')).not.toBeNull();
    act(() => vi.advanceTimersByTime(5000));
    expect(toastNode('Saved')).toBeNull();
  });

  // As a test file's page unmounts and its jsdom goes away while a toast's dismissal is still due (CI, v0.4.0).
  it('does nothing when a dismissal comes due after it unmounts', () => {
    vi.useFakeTimers();
    const { api, unmount } = renderProvider();
    act(() => api.push('ok', 'Saved', ''));
    unmount();
    api.push('err', 'Late', ''); // say, a request that answers after the page is gone
    vi.stubGlobal('window', undefined);
    expect(() => vi.advanceTimersByTime(30_000)).not.toThrow();
  });

  it('announces a failure in the assertive region and the rest in the polite region, one region each', () => {
    const { api } = renderProvider();
    act(() => api.push('err', 'Could not save', 'The server refused the change.'));
    act(() => api.push('ok', 'Saved', ''));
    expect(within(stack().querySelector<HTMLElement>('[aria-live=assertive]')!).getByText('Could not save. The server refused the change.')).toBeTruthy();
    expect(within(stack().querySelector<HTMLElement>('[aria-live=polite]')!).getByText('Saved')).toBeTruthy();
    // No live region inside another (a failure would be read twice).
    expect(stack().querySelectorAll('[aria-live] [aria-live], [aria-live] [role=alert], [aria-live] [role=status]')).toHaveLength(0);
    // The dismiss button's name doesn't repeat the message into the live region.
    expect(screen.getAllByRole('button', { name: 'Dismiss' })).toHaveLength(2);
  });

  it('keeps a failure for 30 seconds, then lets it go', () => {
    vi.useFakeTimers();
    const { api } = renderProvider();
    act(() => api.push('err', 'Could not save', ''));
    act(() => vi.advanceTimersByTime(29_000));
    expect(toastNode('Could not save')).not.toBeNull();
    act(() => vi.advanceTimersByTime(1000));
    expect(toastNode('Could not save')).toBeNull();
  });

  it('holds every dismissal while the pointer is over the stack (2.2.1)', () => {
    vi.useFakeTimers();
    const { api } = renderProvider();
    act(() => api.push('ok', 'Saved', ''));
    hover(true);
    act(() => vi.advanceTimersByTime(20_000));
    expect(toastNode('Saved')).not.toBeNull();
    hover(false);
    act(() => vi.advanceTimersByTime(1000));
    expect(toastNode('Saved')).toBeNull();
  });

  it('a mouse dismiss leaves no hold behind: the next toast still expires', () => {
    vi.useFakeTimers();
    const { api } = renderProvider();
    act(() => api.push('err', 'Could not save', ''));
    hover(true);
    fireEvent.pointerEnter(stack());
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    hover(false); // the toast under the pointer is gone; no pointerleave ever comes
    act(() => api.push('ok', 'Saved', ''));
    act(() => vi.advanceTimersByTime(5000));
    expect(toastNode('Saved')).toBeNull();
  });

  it('a keyboard dismiss returns focus and leaves no hold behind', () => {
    vi.useFakeTimers();
    const { api } = renderProvider();
    const save = screen.getByRole('button', { name: 'Save' });
    save.focus();
    act(() => api.push('err', 'Could not save', ''));
    const close = screen.getByRole('button', { name: 'Dismiss' });
    act(() => close.focus());
    fireEvent.click(close);
    expect(document.activeElement).toBe(save);
    act(() => api.push('ok', 'Saved', ''));
    act(() => vi.advanceTimersByTime(5000));
    expect(toastNode('Saved')).toBeNull();
  });

  it('after a keyboard dismiss, focus moves to the next toast', () => {
    const { api } = renderProvider();
    act(() => api.push('err', 'First', ''));
    act(() => api.push('err', 'Second', ''));
    const [first, second] = screen.getAllByRole('button', { name: 'Dismiss' });
    act(() => first.focus());
    fireEvent.click(first);
    expect(document.activeElement).toBe(second);
  });

  it('a mouse dismiss with two toasts leaves focus where it was, and the stack keeps expiring', () => {
    vi.useFakeTimers();
    const { api } = renderProvider();
    const save = screen.getByRole('button', { name: 'Save' });
    save.focus();
    act(() => api.push('err', 'First', ''));
    act(() => api.push('err', 'Second', ''));
    const [first] = screen.getAllByRole('button', { name: 'Dismiss' });
    // A mouse press focuses the button (Chromium), then the click comes with detail 1.
    act(() => first.focus());
    fireEvent.click(first, { detail: 1 });
    expect(document.activeElement).toBe(save);
    act(() => api.push('ok', 'Saved', ''));
    act(() => vi.advanceTimersByTime(5000));
    expect(toastNode('Saved')).toBeNull();
  });

  it('a mouse dismiss with nothing focused before blurs the button', () => {
    const { api } = renderProvider();
    act(() => api.push('err', 'First', ''));
    act(() => api.push('err', 'Second', ''));
    const [first] = screen.getAllByRole('button', { name: 'Dismiss' });
    act(() => first.focus());
    fireEvent.click(first, { detail: 1 });
    expect(screen.getByRole('region', { name: 'Notifications' }).contains(document.activeElement)).toBe(false);
  });

  it('a newer toast moving into view from "N more" is not announced again', () => {
    const { api } = renderProvider();
    for (const t of ['One', 'Two', 'Three', 'Four']) act(() => api.push('ok', t, ''));
    const polite = stack().querySelector<HTMLElement>('[aria-live=polite]')!;
    const watch = new MutationObserver(() => {});
    watch.observe(polite, { childList: true, subtree: true });
    const dismissFour = screen.getAllByRole('button', { name: 'Dismiss' }).pop()!;
    fireEvent.click(dismissFour);
    expect(toastTitles()).toEqual(['One', 'Two', 'Three']);
    const added = watch.takeRecords().flatMap((r) => Array.from(r.addedNodes, (n) => n.textContent ?? ''));
    watch.disconnect();
    expect(added.filter((t) => t.includes('One'))).toEqual([]);
  });

  it('Escape dismisses the toast that has focus', () => {
    const { api } = renderProvider();
    act(() => api.push('err', 'Could not save', ''));
    const close = screen.getByRole('button', { name: 'Dismiss' });
    act(() => close.focus());
    fireEvent.keyDown(close, { key: 'Escape' });
    expect(toastNode('Could not save')).toBeNull();
  });

  it('collapses a repeated message into one toast with a count, announced again', () => {
    const { api } = renderProvider();
    act(() => api.push('err', 'Name required', 'Give the configuration a name.'));
    const firstNode = screen.getByText('Name required').closest('.toast');
    act(() => api.push('err', 'Name required', 'Give the configuration a name.'));
    expect(toastTitles()).toEqual(['Name required']);
    expect(stack().querySelector('.t-count')?.textContent).toContain('2');
    expect(screen.getByText('Name required').closest('.toast')).not.toBe(firstNode);
    // Announced again: two entries in the assertive region.
    expect(stack().querySelectorAll('[aria-live=assertive] p')).toHaveLength(2);
  });

  it('shows the 3 newest and says how many more there are', () => {
    const { api } = renderProvider();
    for (const t of ['One', 'Two', 'Three', 'Four', 'Five']) act(() => api.push('err', t, ''));
    expect(toastTitles()).toEqual(['Three', 'Four', 'Five']);
    expect(stack().textContent).toContain('2 more');
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss all' }));
    expect(toastTitles()).toEqual([]);
  });
});
