// The API calls the current stubApi (test/console.tsx) has not answered yet. Kept apart from console.tsx so the setup
// file (test/setup.ts), which runs before every test file, can check it without loading the console's modules.

interface Calls {
  /** Made and not yet answered. */
  count: number;
  /** Made since the stub was set up. */
  made: number;
}

/** One record per stub: a call a previous test left unanswered keeps counting down its own stub's record. */
let current: Calls = { count: 0, made: 0 };

/** A fresh record for a new stub (or a new test). */
export function newCallCount(): Calls {
  current = { count: 0, made: 0 };
  return current;
}

export const pendingCalls = () => current.count;
export const callsMade = () => current.made;

/** The calls still unanswered as a test ends, read once: the count starts afresh for the next test. */
export function takePendingCalls(): number {
  const n = current.count;
  newCallCount();
  return n;
}
