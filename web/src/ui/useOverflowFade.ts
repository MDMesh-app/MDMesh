import { useLayoutEffect, type RefObject } from 'react';

/**
 * Marks a horizontally scrolling strip (tabs, a segmented filter, a section nav on phones) with `data-overflow`:
 * `start`, `end`, `both` or nothing, by which of its ends has more beyond it. Its CSS fades only those edges, so a
 * strip that fits shows no fade and one scrolled to its end no longer fades there.
 */
export function useOverflowFade(ref: RefObject<HTMLElement | null>) {
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => {
      const max = el.scrollWidth - el.clientWidth;
      const start = el.scrollLeft > 1;
      const end = max - el.scrollLeft > 1;
      const v = start && end ? 'both' : start ? 'start' : end ? 'end' : '';
      if (v) el.setAttribute('data-overflow', v);
      else el.removeAttribute('data-overflow');
    };
    update();
    el.addEventListener('scroll', update, { passive: true });
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
    ro?.observe(el);
    return () => {
      el.removeEventListener('scroll', update);
      ro?.disconnect();
    };
  }, [ref]);
}
