import { act, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider, useToast } from './toast';

afterEach(() => {
  vi.useRealTimers();
});

describe('ToastProvider', () => {
  it('drops its pending dismissals when it unmounts', () => {
    vi.useFakeTimers();
    let push: ReturnType<typeof useToast>['push'] = () => {};
    function Pusher() {
      push = useToast().push;
      return null;
    }
    const { unmount } = render(
      <ToastProvider>
        <Pusher />
      </ToastProvider>,
    );
    act(() => push('ok', 'Saved', ''));
    expect(vi.getTimerCount()).toBe(1);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
