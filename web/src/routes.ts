// The console's route paths. Pages link through these, never through string literals.

export const paths = {
  overview: '/',
  devices: '/devices',
  device: (id: string | number) => `/devices/${encodeURIComponent(String(id))}`,
  configurations: '/configurations',
  configuration: (id: string | number) => `/configurations/${encodeURIComponent(String(id))}`,
  apps: '/apps',
  enroll: '/enroll',
  settings: '/settings',
  login: '/login',
  setPassword: '/set-password',
  gallery: '/_gallery',
} as const;

/**
 * The current path for an old bookmark, or null when `pathname` is not an old path. The redesign's preview lived
 * under `/v2/...` (`/v2/overview` is now `/`), and the v0.3 console's `/dashboard` and `/configs` are now `/` and
 * `/configurations`. Callers keep the search and hash.
 */
export function legacyRedirect(pathname: string): string | null {
  const v2 = pathname.match(/^\/v2(\/.*)?$/i);
  let p = v2 ? v2[1] || '/' : pathname;
  if (/^\/(?:overview|dashboard)\/?$/i.test(p)) p = '/';
  const configs = p.match(/^\/configs(\/.*)?$/i);
  if (configs) p = `/configurations${configs[1] ?? ''}`;
  // Never hand <Navigate> a protocol-relative path ("/v2//evil.example" → "//evil.example"): one leading slash.
  p = p.replace(/^[\\/]+/, '/');
  return p === pathname ? null : p;
}
