// Contribution functions (a `requires.when`, later a column's `cell`, a section's `validate`) run inside core
// rendering, where no error boundary can isolate them. safeCall runs one, and when it throws, logs it once per
// function and returns a neutral value instead, so one broken extension never breaks the page around it.

const reported = new WeakSet<object>();

/** `self` is the object `fn` was read from, for a method that uses `this` (a section's `validate() { this... }`). */
export function safeCall<A extends unknown[], R>(
  fn: (...args: A) => R,
  args: A,
  fallback: R,
  label: string,
  self?: unknown,
): R {
  try {
    return fn.apply(self, args);
  } catch (error) {
    if (!reported.has(fn)) {
      reported.add(fn);
      console.error(`[ext:${label}] threw; using a neutral value instead`, error);
    }
    return fallback;
  }
}
