// Values core hands an extension and keeps using itself (a configuration section's defaults, its stored value and
// the value its onChange gave) are frozen all the way down, so a body that changes one in place
// (`value.hosts.push(h)`) throws instead of changing what core compares against, or what the next configuration
// starts from.

const frozen = new WeakSet<object>();

/**
 * Freezes `value` and every object and array in it, in place; returns it. Plain data only (JSON). A typed array or
 * DataView in it (`Object.freeze` refuses one with elements) is left as it is, and the rest is still frozen.
 */
export function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || ArrayBuffer.isView(value) || frozen.has(value)) return value;
  frozen.add(value);
  for (const key of Object.keys(value)) deepFreeze((value as Record<string, unknown>)[key]);
  return Object.freeze(value);
}

/** A frozen copy of `value` as JSON keeps it (the caller's own object is left as it is); throws when it isn't JSON. */
export function frozenJsonCopy<T>(value: T): T {
  return deepFreeze(JSON.parse(JSON.stringify(value)) as T);
}
