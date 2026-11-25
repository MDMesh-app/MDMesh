# MDMesh console (`web/`)

The admin console: a React + TypeScript single-page app built with Vite. In production it is baked into the web
image and served by Caddy next to the API (`docker/web.Dockerfile`, `docker/Caddyfile`); the native installer serves
the same build from Tomcat.

## Run

Start the dev stack first ([docs/DEV.md](../docs/DEV.md)), then:

```bash
cd web
npm install
npm run dev                 # http://localhost:5173, hot reload
```

`npm run build` first stops on any v0.3 console file a fork's merge left in the tree (`check-v03-files.ts`), then
type-checks and writes the production build to `dist/`; `npm run preview` serves that build.
`npm test` runs the unit tests (vitest, jsdom): the extension registry and its guards, and the shell.

## Layout

```
src/
  App.tsx       the route table: /login, /set-password, and every signed-in page from the registry
  routes.ts     path helpers; old bookmarks (/v2/..., /dashboard, /configs) redirect to the current paths
  pages/        one lazy chunk per page
  ui/           shared components (barrel: ui/index.ts) and the app shell
  ext/          the extension registry: slot types, validation, permissions, error boundary, the hosts that
                render a slot (ext/hosts/); ext/core/ is core's own contributions (nav items, pages, device tabs
                and rail groups, configuration and Settings sections, Overview cards) and the permission each page
                and action needs
  extensions/   fork-owned extension folders (EXTENDING.md); core ships none
  sdk/          `@mdmesh/console`, the only module extensions import, and `@mdmesh/console/testing` for their
                tests; surface.txt and surface.d.ts.txt snapshot its names and its types (`npm test -- -u`
                updates them)
  data/         product rules and data hooks shared by pages (no markup)
  api/          REST calls
  auth/         session, sign-in guard, safe return paths
  enroll/       enrollment QR payload
  whatsnew/     the What's new dialog, built from CHANGELOG.md
  tokens.css    design tokens and base styles; fonts.css self-hosts IBM Plex
```

Core's sidebar items, pages, device tabs and rail groups, configuration and Settings sections, and Overview cards
are contributions to the same registry extensions use (`ext/core/`): add a new one there, not as JSX in a page. A page
whose permission the signed-in role lacks shows "You don't have access" and drops out of the sidebar
(`ext/core/access.ts` maps each page and action to the server permission it needs).

A fork adds to the console in `src/extensions/<id>/` and to the server in `plugins/fork/<id>/`, editing no core file:
[EXTENDING.md](EXTENDING.md) is the guide.

`npm run dev` also serves a component gallery at `/_gallery`, behind sign-in like every console page (production
builds include it only with `VITE_GALLERY=1`).

## Translatable text

Console text is written as [react-intl](https://formatjs.github.io/docs/react-intl) messages, with an explicit id and
the English beside it in the code:

```tsx
const messages = defineMessages({
  title: { id: 'devices.list.title', defaultMessage: 'Devices' },
});
<PageHeader title={intl.formatMessage(messages.title)} />            // a string prop (useIntl())
<FormattedMessage id="devices.list.empty" defaultMessage="No devices yet" />   // JSX text
```

Ids are lowerCamel segments joined by dots, at least two and usually three (`area.topic.purpose`), and never change
with the wording. Plurals and variables use ICU
(`{count, plural, one {# device} other {# devices}}`), not string concatenation. A destructive action's message gets a
`description` starting `DESTRUCTIVE:`, so translators and reviewers see it.

- `npm run i18n:extract` writes `src/i18n/locales/en.json`, the source for translators. Commit it with the change;
  `npm test` fails while it is out of date, on a message without an id, and on a translation that adds ids or changes
  a message's placeholders. A translation with messages missing passes and shows those in English.
- `npm test` also counts the English written straight into the UI, per file (`src/i18n/literals.test.ts`): JSX text
  and children, UI props (`title`, `label`, `header`, `aria-label`…) and capitalised sentences, not console or Error
  messages. A file's count may not go up; the failure lists the file's strings with their lines. It is a count, so
  converting one string makes room for another in the same file: review catches that. When a change turns text into
  messages, `npm run i18n:baseline` lowers the counts in `src/i18n/literals-baseline.json`; commit that too.
- Component tests render with `render` from `src/test/render.tsx` (Testing Library's, inside `IntlRoot`), and page
  tests with `renderInConsole`, so a component whose text is a message finds its provider.
- `npm run i18n:pseudo`, then `localStorage.setItem('mdmesh-locale', 'en-XA')` in the dev console's browser, shows
  every message accented and padded; text that stays plain English isn't a message yet. Remove the key to go back.
  Reload the page after setting or removing it: the console reads it once, as it loads.

Two things to know:
- The console's locale is a language (`en`), not a region: an en-GB browser gets `en`, so dates and numbers formatted
  through react-intl come out US-style. Passing the browser's full tag when its language matches is for later.
- `formatjs` (extract, compile, verify) is a native binary, published for Linux (x64 and arm64, glibc and musl),
  macOS and Windows, with no JavaScript fallback; `npm ci --omit=optional` leaves it out. The production build doesn't
  run it, only `npm test` and the `i18n:*` scripts do.

## How the dev server reaches the API

Sign-in is session-cookie based (`JSESSIONID`), so the console has to be same-origin with the API. The Vite dev
server proxies every path the console calls to one target, cookies included:

```
/rest  /files  /agent/ws  /update  /recovery   ->   VITE_DEV_PROXY_TARGET (default http://localhost:8088)
```

The default is the dev stack's Caddy, which routes them exactly as production does: `/rest`, `/files` and
`/agent/ws` to the server, `/update`, `/recovery` and `/files/agent.apk` to the supervisor.

## Environment variables

| Variable | Default | Used by | Purpose |
|---|---|---|---|
| `VITE_API_BASE` | `/rest` | app (runtime) | URL prefix for API calls. Keep `/rest`: an absolute URL makes the calls cross-origin, and the server's `Access-Control-Allow-Origin: *` does not allow the credentialed (cookie) requests the console makes. |
| `VITE_DEV_PROXY_TARGET` | `http://localhost:8088` | `vite.config.ts` | Where the dev server proxies (above). |
| `VITE_GALLERY` | unset | app (build) | `1` includes the component gallery (`/_gallery`) in a production build; `scripts/shots/compare-v2.mjs --build` sets it. |
| `VITE_EXTENSIONS_DISABLED` | unset | app (build) | Comma-separated extension ids to leave out of the registry, to bisect a broken fork build ([EXTENDING.md](EXTENDING.md#troubleshooting)). |

See `.env.example`. The enrollment-QR defaults (`VITE_AGENT_*`) are build arguments of the web image; see
`docker/web.Dockerfile`.
