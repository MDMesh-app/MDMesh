// Runs before every test file (vite.config.ts `test.setupFiles`). A file that runs in Node
// (`// @vitest-environment node`: it reads the source tree, and needs no page) has no DOM to set up.
if (typeof window !== 'undefined') await import('./dom');
