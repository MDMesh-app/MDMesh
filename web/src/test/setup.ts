// Runs before every test file (vite.config.ts `test.setupFiles`).
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { readHistoryAgain } from '../whatsnew/seen';

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

// jsdom's <dialog> has no showModal() or close(); ui/Modal calls both. Opening sets `open`; closing clears it and
// fires `close`, as a browser does (without making the page behind inert).
if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    if (!this.open) return;
    this.open = false;
    this.dispatchEvent(new Event('close'));
  };
}

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
