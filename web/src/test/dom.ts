// The DOM test setup: runs before every jsdom test file (test/setup.ts).
import { cleanup, configure } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { readHistoryAgain } from '../whatsnew/seen';

// Each page is lazy, so whichever test reaches one first compiles and renders it cold, which a busy machine stretches
// past Testing Library's 1 s default. The test limit that goes with this wait is `test.testTimeout` in vite.config.ts.
// A `{ timeout }` passed to one call beats this value, so tests don't pass one.
configure({ asyncUtilTimeout: 10_000 });

// jsdom has no matchMedia; the shell and the theme read it. Every query answers "no match" (desktop, light).
Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }),
});

// Radix (Select, menus, popovers) uses pointer capture and scrollIntoView, which jsdom lacks.
if (!Element.prototype.hasPointerCapture) {
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.setPointerCapture = () => {};
  Element.prototype.releasePointerCapture = () => {};
}
if (!Element.prototype.scrollIntoView) Element.prototype.scrollIntoView = () => {};

// jsdom has no ResizeObserver (the save bar watches its own height) and no scrolling.
if (!('ResizeObserver' in window)) {
  Object.defineProperty(window, 'ResizeObserver', {
    configurable: true,
    value: class ResizeObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  });
}
window.scrollTo = () => {};

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  // What's new's memory of this page load (seen.ts) goes with the storage: the next test is a new page load.
  readHistoryAgain();
});
