import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Modal } from './Modal';

/** jsdom has no layout: the body's heights, and every element shown. ResizeObserver reports once when observing. */
function layout({ scrollHeight, clientHeight }: { scrollHeight: number; clientHeight: number }) {
  const body = (el: Element) => el.classList.contains('v2-modal__body');
  vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockImplementation(function (this: HTMLElement) {
    return body(this) ? scrollHeight : 0;
  });
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(function (this: HTMLElement) {
    return body(this) ? clientHeight : 0;
  });
  vi.spyOn(Element.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList);
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(private readonly cb: () => void) {}
      observe() {
        this.cb();
      }
      unobserve() {}
      disconnect() {}
    },
  );
}

const body = () => document.querySelector('.v2-modal__body') as HTMLElement;

describe('Modal: a body that scrolls', () => {
  it('is a Tab stop, named by the title, when nothing in it is', () => {
    layout({ scrollHeight: 2000, clientHeight: 400 });
    render(
      <Modal open onClose={() => {}} title="What’s new in 0.3.7">
        <p>Long notes without a link.</p>
      </Modal>,
    );
    expect(body().getAttribute('tabindex')).toBe('0');
    expect(body().getAttribute('role')).toBe('group');
    expect(screen.getByRole('group', { name: 'What’s new in 0.3.7' })).toBe(body());
  });

  it('stays one while it has focus when it stops scrolling (focus would drop out of the dialog), until focus leaves', async () => {
    layout({ scrollHeight: 2000, clientHeight: 400 });
    render(
      <Modal open onClose={() => {}} title="Notes">
        <p>Long notes.</p>
      </Modal>,
    );
    const grow = async () => {
      layout({ scrollHeight: 400, clientHeight: 400 }); // the window grew
      await act(async () => {
        body().appendChild(document.createElement('span')); // anything that makes it look again
        await Promise.resolve();
      });
    };
    body().focus();
    await grow();
    expect(body().getAttribute('tabindex')).toBe('0');
    act(() => body().blur()); // once focus leaves, the stop goes
    expect(body().hasAttribute('tabindex')).toBe(false);
  });

  it('is not one when something in it is (Tab reaches the body that way), or when it fits', () => {
    layout({ scrollHeight: 2000, clientHeight: 400 });
    const { unmount } = render(
      <Modal open onClose={() => {}} title="Custom kiosk">
        <button type="button">Pick</button>
      </Modal>,
    );
    expect(body().hasAttribute('tabindex')).toBe(false);
    expect(body().hasAttribute('role')).toBe(false);
    unmount();

    layout({ scrollHeight: 400, clientHeight: 400 });
    render(
      <Modal open onClose={() => {}} title="Short">
        <p>Fits.</p>
      </Modal>,
    );
    expect(body().hasAttribute('tabindex')).toBe(false);
  });
});
