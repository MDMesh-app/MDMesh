import { act, fireEvent, render, screen } from '@testing-library/react';
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
    return null;
  }
  const view = render(
    <ToastProvider>
      <Pusher />
    </ToastProvider>,
  );
  return { ...view, api };
}

describe('ToastProvider', () => {
  it('dismisses a toast after 5 seconds', () => {
    vi.useFakeTimers();
    const { api } = renderProvider();
    act(() => api.push('ok', 'Saved', ''));
    expect(screen.queryByText('Saved')).not.toBeNull();
    act(() => vi.advanceTimersByTime(5000));
    expect(screen.queryByText('Saved')).toBeNull();
  });

  // As a test file's page unmounts and its jsdom goes away while a toast's dismissal is still due (CI, v0.4.0).
  it('does nothing when a dismissal comes due after it unmounts', () => {
    vi.useFakeTimers();
    const { api, unmount } = renderProvider();
    act(() => api.push('ok', 'Saved', ''));
    unmount();
    api.push('err', 'Late', ''); // say, a request that answers after the page is gone
    vi.stubGlobal('window', undefined);
    expect(() => vi.advanceTimersByTime(5000)).not.toThrow();
  });

  it('keeps a failure until it is dismissed, and announces it as an alert', () => {
    vi.useFakeTimers();
    const { api } = renderProvider();
    act(() => api.push('err', 'Could not save', 'The server refused the change.'));
    act(() => vi.advanceTimersByTime(60_000));
    expect(screen.getByRole('alert').textContent).toContain('Could not save');
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss: Could not save' }));
    expect(screen.queryByText('Could not save')).toBeNull();
  });

  it('holds a dismissal while the pointer is over the stack (2.2.1)', () => {
    vi.useFakeTimers();
    const { api } = renderProvider();
    act(() => api.push('ok', 'Saved', ''));
    const wrap = screen.getByRole('status').parentElement!;
    fireEvent.pointerEnter(wrap);
    act(() => vi.advanceTimersByTime(10_000));
    expect(screen.queryByText('Saved')).not.toBeNull();
    fireEvent.pointerLeave(wrap);
    act(() => vi.advanceTimersByTime(2000));
    expect(screen.queryByText('Saved')).toBeNull();
  });
});
