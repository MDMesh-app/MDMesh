// The return path after sign-in (the sign-in and set-password pages' `from=`), checked against open redirects.

/** Pages a return path must never point back to (it would loop through sign-in; /v2/... redirects there too). */
const AUTH_PAGE = /^\/(?:v2\/)?(?:login|set-password)(?:\/|$)/i;

/**
 * The `from=` return path after sign-in, or `fallback`. Only a same-origin relative path is accepted (no open
 * redirect): exactly one leading "/" (not "//" or "/\", which browsers read as another host), no control
 * characters, whitespace or backslashes (browsers strip or rewrite those while parsing), and the origin re-checked
 * with the URL parser. The parsed, normalised path is returned, never the raw string.
 */
export function safeReturnPath(raw: unknown, fallback: string): string {
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > 2048) return fallback;
  if (!/^\/(?![/\\])/.test(raw)) return fallback;
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f\s\\]/.test(raw)) return fallback;
  let url: URL;
  try {
    url = new URL(raw, window.location.origin);
  } catch {
    return fallback;
  }
  if (url.origin !== window.location.origin || AUTH_PAGE.test(url.pathname)) return fallback;
  return url.pathname + url.search + url.hash;
}
