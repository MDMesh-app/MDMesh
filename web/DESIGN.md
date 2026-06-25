# MDMesh — design system ("Instrument")

A device-fleet command console for IT admins. The direction is an **instrument / control
panel**: calm, precise, data-dense, watched for long stretches. The opposite of a generic
light-blue Bootstrap admin. Bold in exactly one place (the dashboard's fleet-vitals strip);
quiet and disciplined everywhere else.

## Color (dark, ground = near-black with a blue undertone)

```
--ink:        #0E1117   /* app background            */
--panel:      #151A22   /* cards / surfaces          */
--panel-2:    #1B222C   /* raised / hover surfaces   */
--line:       #273040   /* hairline borders          */
--line-soft:  #1F2733   /* faint inner dividers      */
--text:       #E8EEF4   /* primary text              */
--muted:      #8693A4   /* secondary text            */
--faint:      #5C6675   /* labels, disabled          */
--signal:     #F4B942   /* BRAND amber — accents, active nav, primary CTA */
--signal-dim: #5A4718   /* amber at low alpha for arcs/tracks */
/* status (kept distinct from brand amber) */
--ok:    #3FD08A   /* online / applied   */
--warn:  #F0A93B   /* pending / degraded */
--alert: #F2545B   /* violation / failed */
--idle:  #5C6675   /* offline / unknown  */
```

Amber is the brand signal (active nav, primary buttons, the vitals arcs) — **not** a status.
Device status uses ok/warn/alert/idle only.

## Type (engineered, IBM Plex family — load from Google Fonts)

- **Display / wordmark / labels:** `IBM Plex Sans Condensed` (600/700). Labels are 11px,
  uppercase, letter-spacing .08em.
- **Body / UI:** `IBM Plex Sans` (400/500/600).
- **Mono (device ids, tokens, telemetry, all numbers in the vitals strip):** `IBM Plex Mono`.
  Use `font-variant-numeric: tabular-nums` everywhere numbers align.

Scale: 11 (label) · 13 (small) · 14 (body) · 16 · 20 · 28 · 40 (vitals readout).

## Form

- Radius: 4px panels, 2px chips — low, instrument-like. No big rounded cards.
- Borders: 1px `--line` hairlines do the structural work (not shadows).
- Spacing: 8px grid.
- Motion: subtle only — a short fade/translate on route load; row hover = `--panel-2` + a 2px
  amber left-accent. Respect `prefers-reduced-motion`. No ambient/looping animation.

## Layout — app shell

- **Left sidebar (240px, fixed):** MDMesh wordmark at top (condensed; render as
  `MDM` in `--text` + `esh` in `--muted`, with a small amber square bullet before it).
  Nav: Dashboard · Devices · Enroll · Settings. Active item = amber 2px left bar + brighter text.
  Bottom: signed-in user + Sign out.
- **Top bar:** page title left; right = an environment chip (mono, e.g. the API host) + a small
  connection dot (ok/alert).
- **Content:** panels on `--ink`, max-width ~1200.

## Signature — the fleet-vitals strip (dashboard hero)

A single horizontal instrument cluster across the top of the dashboard: 4 readouts separated by
hairlines — **Managed**, **Online**, **Pending cmds**, **Violations**. Each: a big mono number
(40px, tabular), an 11px uppercase label, and a thin amber arc/sparkline beneath drawn with SVG
(track in `--signal-dim`, value in `--signal`). This is the one memorable element — make it feel
like an instrument cluster, keep everything else restrained.

## Pages

1. **Login** — centered card on `--ink`; wordmark; "Sign in to MDMesh"; amber CTA. Errors are
   direct ("Wrong login or password"), in the alert color, no apology.
2. **Dashboard** — vitals strip + a "Fleet" panel (compact recent-devices table) + an "Activity"
   panel (recent enrollments/commands; if no data source yet, a quiet empty state: "No activity yet").
3. **Devices** — full table: Status dot · Device (number in mono + model) · Configuration ·
   Android · Last seen. Search box. Row → device detail.
4. **Device detail** — header (device number in mono + status), an info grid, and a **Command
   console** card: pick a command template, send it, toast the result. Templates map to the agent
   v1 contract (see below).
5. **Enroll** — "Generate enrollment token" → shows the token (mono, copyable) + a one-line note
   that it goes into the device's QR provisioning bundle. (QR rendering can come later.)

## Live endpoints (verified)

Session-cookie auth; password is `md5(password).toUpperCase()`. Responses are
`{status,message,data}` (status `"OK"`/`"ERROR"`). Reuse the existing `web/src/api` client.

- `POST /rest/public/auth/login` `{login,password}` → user (+ session cookie).
- `POST /rest/private/devices/search` `{pageNum,pageSize,value?}` → `{devices:{items,totalItemsCount},configurations}`.
  (Read `server/.../rest/json/view/devicelist/DeviceView.java` for exact item fields.)
- `POST /rest/private/agent/v1/token` → `{token,...}` (mint enrollment token).
- `POST /rest/private/agent/v1/devices/{deviceId}/commands` `{type,requiresCapability,payload}` → queued command.
  `payload` is a JSON **string**. Command templates:
  - Disable Wi-Fi → `{type:"policy.apply",requiresCapability:"policy.wifi",payload:"{\"policy\":\"wifi\",\"value\":false}"}`
  - Enable Wi-Fi  → same with `value:true`.
  - Disable camera → `policy.camera`, payload `{"policy":"camera","value":false}`.
  - Reboot device → `{type:"device.reboot"}` (no requiresCapability).
  - Lock device → `{type:"device.lock"}`.

## Quality floor

Responsive to mobile (sidebar collapses to a top bar), visible keyboard focus (amber outline),
reduced-motion honored. Copy is end-user language, sentence case, active voice.
