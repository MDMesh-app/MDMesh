// The API calls the current stubApi (test/console.tsx) has not answered yet. Kept apart from console.tsx so the DOM
// setup (test/dom.ts), which runs before every test file, can check it without loading the console's modules.

/** One record per stub: a call a previous test left unanswered keeps counting down its own stub's record. */
let current = { count: 0 };

/** A fresh record for a new stub (or a new test). */
export function newCallCount() {
  current = { count: 0 };
  return current;
}

export const pendingCalls = () => current.count;

/** The calls still unanswered as a test ends, read once: the count starts afresh for the next test. */
export function takePendingCalls(): number {
  const n = current.count;
  newCallCount();
  return n;
}
