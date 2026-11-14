# Changelog

What changed in each MDMesh release, for the people running it, newest first. A version's section is its tag
annotation and GitHub release notes, word for word ([RELEASING.md](RELEASING.md)). From v0.3.2 on, its sections come
in this order, empty ones left out: Upgrading, Heads-up, New, Fixes, Under the hood. Feature releases (0.x.0) ship
every three weeks; patch releases (0.x.y) only for a security fix or something broken in the field. Each PR adds its
line under Unreleased.

## Unreleased

## v0.5.0 — 2026-11-13

Upgrading
- The database adds columns on start: `devices.wipedAt`; `configurations.camera`, `configurations.locationServices`,
  `configurations.backgroundImageFileId`, `configurations.backgroundImageSha256`, `configurations.backgroundImageFit`,
  `configurations.backgroundImageFocalX` and `configurations.backgroundImageFocalY`; `device_state.kioskGeneration` and
  `device_state.kioskReturnGeneration`; `applicationVersions.requestedPermissions`; the `configurationAppPermissions`
  table; `devices.agentEnrollAttemptHash`, with an index on it and one on each customer's devices by enrollment
  time; and the `agentEnrollmentKey` table, whose one row the server fills with a random key on first use. Devices
  wiped before the update aren't marked.
- The database adds three columns to enrollment tokens on start (`maxUses`, `useCount`, `revokedAt`) and stores
  the tokens hashed. Existing codes stay single-use and keep working; on PostgreSQL older than 11 they stop
  instead, so make a new code on the Enroll page. A 0.4.0 server on the upgraded database accepts no code made
  before the downgrade; a rollback that restores the pre-update backup is fine
  ([UPGRADING, v0.5.0](https://github.com/MDMesh-app/MDMesh/blob/main/docs/UPGRADING.md#v050)).
- Quick start: before you press Update, run `docker compose pull supervisor && docker compose up -d supervisor`. The
  0.5.0 supervisor then applies this update, pinned, and can roll it back. One from before 0.5.0 can't restore over
  0.5.0's new tables: its rollback, automatic or by hand, stops with the database unchanged and the server stopped.
  Already updated by the old one? The same command, then Roll back
  ([UPGRADING, v0.5.0](https://github.com/MDMesh-app/MDMesh/blob/main/docs/UPGRADING.md#v050)).
- Quick start, if the old supervisor already applied this update: run `docker compose pull && docker compose up -d`
  once. Updates never touch the supervisor, and one from before 0.5.0 keeps applying updates by tag. From source, or
  with `SUPERVISOR_VERSION` set by hand: [UPGRADING, v0.5.0](https://github.com/MDMesh-app/MDMesh/blob/main/docs/UPGRADING.md#v050).
- From source and native: 0.4.x has no `--update`, so move the clone to 0.5.0 once with
  `git fetch --tags && git checkout v0.5.0 && ./setup.sh --update` (native:
  `git fetch --tags && git checkout v0.5.0 && sudo ./setup.sh --update --native`). From then on, update with `./setup.sh --update`
  (native: `sudo ./setup.sh --update --native`).
- There is a new agent, 0.5.0: roll it out from Settings → Agent rollout. Much of this release needs it, and devices
  on older agents keep working ([UPGRADING, v0.5.0](https://github.com/MDMesh-app/MDMesh/blob/main/docs/UPGRADING.md#v050)).
- Native: the installer now stops on an option it doesn't take, instead of ignoring it.
- Answers files (`MDMESH_ANSWERS`) must be owned by you or root and give no access to group or others (for example
  `chmod 600`), or they are refused.

Heads-up
- Native installs: `REPLACE_DATA` must be `yes` or `no`, and `ASSUME_YES` `1` or `0`; empty means not set (the
  default). Any other value now stops the installer before it changes anything, where it used to mean Keep or ask.
- The agent is now called **MDMesh** on the device (was MDM Agent): in the app drawer, Settings → Apps, the
  device admin list and the device's Apps tab in the console. Its icon is new too: a white square on dark (was a white
  shield on blue). It's the same app, so it updates in place. Needs agent 0.5.0.
- Kiosk: the launcher keeps its text readable. If a configuration's kiosk text color has less than 4.5:1 contrast
  with its background (the minimum for readable text), the device uses a readable color instead, so some brand
  pairs show dark text (white on a bright red, for example). The background is always drawn solid (transparency is
  ignored). Needs agent 0.5.0.
- Agent 0.5.0: when the server has been unreachable for a while, a device's instant connection can take up to 5
  minutes to come back (it was up to 1 minute), so Sync now may say the device isn't connected a little longer. It
  comes back right away when the device's network returns or its next check-in gets through, and devices retry at
  random times so they don't all reconnect at once after a server restart. Check-ins are not affected.

New
- From source and native: `./setup.sh --update` moves the clone to the newest release, checked against its signed
  manifest like the quick start, then runs it. A manifest that doesn't verify, or names no commit, and local changes
  stop it; a release that isn't published yet is skipped. Fresh installs clone and run `./setup.sh --update` too. It
  needs `git`, `curl` and `openssl` 3.0+ (or an older `openssl` and `minisign`), and says which are missing first; as
  root with `--native` it installs them, waiting for another apt run that holds apt's lock (up to 5 minutes).
- Installers check answers typed at a question with the same rules as answers given in advance, and ask again.
- The console's update steps for source and native installs show `./setup.sh --update`.
- Installers: every question can be answered in advance, with `MDMESH_*` variables or an answers file, and
  `MDMESH_NONINTERACTIVE=1` runs with no questions at all. `MDMESH_DRY_RUN=1` checks the answers and shows what a run
  would do. See [DEPLOY](https://github.com/MDMesh-app/MDMesh/blob/main/DEPLOY.md#answering-the-questions-in-advance).
- Enroll: devices that enroll while the page is open show under the code, with a link, first as getting their
  configuration and then Online (or that one hasn't checked in after 10 minutes), so you see the device you just set
  up arrive without going to Devices. The page stops looking after 30 minutes until you ask it to look again. Needs
  this server.
- Configurations can allow or block the camera. Block covers every app, kiosk apps too. Not managed after Block
  gives the camera back on agent 0.5.0; on older agents, choose Allow to lift a block (thanks @letternumber).
- Kiosk: staff can tap Return to kiosk in the MDMesh app after leaving with the exit PIN, even offline. It shows
  only when the device's configuration enables kiosk, asks first (and warns when that kiosk has no exit on the
  device), and tells the server at once when online. Needs agent 0.5.0; no server update needed (thanks @tisign).
- Enroll: one code can set up a batch. Choose Devices (one, or up to 10, 25 or 100) and Valid for (24 hours to 30
  days); a code for several devices works for anyone who has it until it expires or its devices are used up. The
  default is still one device for 24 hours, the code to send to one person. **Stop all codes** ends every code that
  still works, and making a new code says when the one it replaces still works. Codes are stored hashed, making and
  stopping them is in the audit log, and the server log names the code each device enrolled with. The API takes
  `maxUses` and `validHours` on `POST /rest/private/agent/v1/token`, and `POST /rest/private/agent/v1/token/revoke-all`
  (thanks @alessfi).
- Configuration → Network can manage Android Location services independently of MDMesh location reporting on Android
  11+ Device Owner devices. Needs agent 0.5.0 (thanks @letternumber).
- Kiosk: a configuration can show a background image behind the kiosk launcher. Choose it in the Kiosk section: a
  PNG, JPEG or WebP image, at most 10 MB and 16 megapixels. Devices download it only over HTTPS (with an `http://`
  server address they keep the background color), check it by its SHA-256, and keep the color if it can't be shown.
  The editor shows the current image (size, upload date, short SHA-256) with Replace and Remove, and previews the
  launcher over it on a phone and a tablet, turned either way, with the text contrast it reaches. The launcher dims
  the image only lightly (at most 30%, as much as the Exit button needs to stand out at 3:1) and puts a plate of the
  background color behind each text that needs one to read at 4.5:1, solid over busy texture. The preview grid has
  the configuration's apps in the device's order, named as in the configuration (a device leaves out apps that aren't
  installed and shows each app's own label). Labels break between words as on the device, End of list shows the grid
  scrolled to its end, clear of Exit, and a single-app kiosk shows its pinned app over the image. Fill (cropped around
  a focal point you set by clicking, dragging or with the arrow keys) or Fit (the whole image, letterboxed in the
  background color). Agents before 0.5.0 ignore the image and keep the background color.
  Choose from uploaded images reuses one without uploading it again, and deletes ones nothing uses. Large photos are
  scaled to at most 3840 px before upload, and their location, camera details and other metadata are removed before
  devices get them. The device page names the image its configuration sets. The download starts as soon as the kiosk
  is applied, so the image is usually ready when the launcher shows. Needs agent 0.5.0 (thanks @letternumber).
- Configurations can Grant or Deny an app's runtime permissions (camera, location, microphone, contacts, SMS and
  more), under each installed app in the editor; Default removes MDMesh's override. The choices come from the APK
  uploaded to the server. Needs agent 0.5.0 on a Device Owner device: a device on an older agent applies the rest of
  the configuration and shows as out of sync while the configuration has a rule (thanks @letternumber).
- Settings → Users & roles: create, edit and delete console accounts, choose the devices and configurations each
  can reach, and edit roles and their permissions (thanks @lared3294).
- A wiped device shows as **Wiped** instead of Online and then Offline, and is left out of the counts, Needs attention
  and rollouts; the Devices list's Wiped filter shows it (thanks @Przemek-Magic).
- Sync now and console commands say when the device isn't connected and will get it at its next check-in.
- Agent: its dialogs (Return to kiosk, Exit kiosk, Exit locked) follow the device's light or dark setting and use
  the console's look, the same on every phone. An open dialog comes back after the device is rotated, unless
  kiosk was turned off meanwhile. Needs agent 0.5.0.
- Agent: Exit kiosk is easier to use. A wrong PIN shows in the dialog, which stays open and says how many tries are
  left before the exit locks; the PIN can be shown while typing, but not copied or shared. The prompt closes, and
  what was typed with it, when the screen goes off or another app opens, or after a minute without typing. After too
  many wrong PINs, Exit locked counts down to when the exit can be tried again, and closes then (or after a minute).
  Needs agent 0.5.0.
- Agent: its notifications have sharp MDMesh status bar icons, and its app icon follows themed icons on Android 13
  and later. Needs agent 0.5.0.
- Agent: the MDMesh status screen follows the device's light or dark setting and uses the console's look: a device
  card, a Kiosk card with Return to kiosk, and a callout that stays on screen when a return fails (it was a
  short-lived message). Needs agent 0.5.0.
- Kiosk: the launcher grid has pressed highlights, two-line app names (three with a larger font) and as many
  columns as fit, also after rotating; its empty, loading, "App closed" and idle screens and the Exit kiosk button
  use the kiosk's own colours and stay readable on any background. Needs agent 0.5.0.
- Agent: the status screen, dialogs and kiosk launcher are easier to read and use: darker secondary and status text,
  no text under 13sp, a visible focus ring for keyboards and D-pads, full-size copy buttons, and TalkBack reads
  headings, each fact with its label, and a failed return or stuck enrolment as it appears. Needs agent 0.5.0.
- Updates and the quick start run exactly the images the signed release names, pinned by digest; the quick start
  also checks each file it downloads, stops instead of falling back to `main`, and needs OpenSSL 3.0+, or an older
  OpenSSL and `minisign`.
- Each release now publishes `quickstart.sh`, with its SHA-256 in the signed manifest. The README's install command
  runs the latest release's copy instead of `main`'s; DEPLOY.md has one that checks it against the signed release
  first and installs exactly the release it checked
  ([DEPLOY.md](https://github.com/MDMesh-app/MDMesh/blob/main/DEPLOY.md#option-a--quick-start-no-clone-published-images)).

Fixes
- Rollback and restore: the database is emptied before the backup is loaded, in the same transaction. The dump's own
  DROPs failed when the update had added a table that refers to an older one, so the rollback stopped (nothing
  changed). The by-hand restores in the docs, and the command the native installer prints next to each dump, do the
  same.
- The installers create .env and other files holding secrets readable only by their owner from the start, and a
  setup.sh re-run makes an existing .env owner-only again.
- setup.sh re-runs read `.env` without running it as a script, so `bash -x ./setup.sh` no longer prints its
  passwords and secrets. Values are read as before, quotes and escapes included; a line whose value the shell would
  compute (a `$` or backquote) stops setup.sh, naming the line.
- The console recognizes an on-device Return to kiosk and does not offer the same action again; a later kiosk exit,
  custom kiosk, or configuration apply makes it available again.
- Files: uploading a file again with the same name and the same content returns the file already there instead of an
  error.
- Enroll: the page warns before you hand out a QR code devices can't use: the console is open at localhost, over
  `http://`, at a local address, or at another address than the server's `BASE_URL` (devices keep the address in the
  code until a factory reset). Wi-Fi a device can't join from a QR code (a secured network entered without its
  password, a WPA password that isn't 8 to 63 characters, a WEP key of the wrong length, enterprise EAP) is left out of
  the code with the reason, instead of stopping setup at the Wi-Fi step.
- Agent: on Android 12 and later, a device enrolled by QR reports the same hardware id from its first contact, so
  re-enrolling it after a factory reset is flagged as the same device. Needs agent 0.5.0.
- Agent: while a device can't enroll, its screen says why and what to do (code used up or expired, server not
  found, certificate not trusted, device date wrong, Wi-Fi asking for a sign-in, `http://` address) instead of an
  error key or exception text. Needs agent 0.5.0.
- Agent: a server address in the QR with spaces, a trailing slash, no `https://` or a path now works, and one that
  isn't an address is reported on the device instead of the agent quietly trying a placeholder host. Needs agent
  0.5.0.
- Enroll: the server log says why it refused a device's enrollment (unknown, expired, used up or stopped code), so
  "my code doesn't work" can be answered from the log.
- Enroll: when the network drops just as a device enrolls, or the agent gives up waiting and tries again while the
  server is still enrolling it, it no longer stays on "Enrolling…" with a code that reads as already used, or takes a
  second device's place on a batch code. Its retry gets the device it already created, with the same credential, so a
  late duplicate of the request can't lock it out; a stopped code resumes nothing. If the server refuses a new
  device's credentials before its first check-in, the agent gets them back by resuming its enrollment (never by
  making another device); if that isn't possible, the agent's screen says the device has to be enrolled again, until
  a check-in succeeds. Needs agent 0.5.0 and this server;
  older agents enroll as before.
- Enroll: each enrollment is now one database transaction. A server error, restart or lost database connection part-way
  through no longer leaves a device in the list that can never check in, or uses up the enrollment code: nothing is
  kept and the agent tries again.
- Enroll: on an install with several customers, enrolling with a code now respects the customer's device limit, as
  other ways of adding devices already did, and devices added at the same moment through the console, enrollment or
  on-demand creation can no longer together pass it. A device over the limit isn't added and says the organisation has
  reached its device limit; it enrolls by itself once there is room (agent 0.5.0; older agents just keep trying).
- Audit log: on Docker installs, entries show the client's address instead of the edge container's (thanks
  @Przemek-Magic).
- Agent: on Android 15 the status screen and the kiosk no longer draw under the status and navigation bars, where
  the kiosk's Exit kiosk button could be hard to reach. Needs agent 0.5.0.
- Kiosk: after someone leaves kiosk on the device with the exit PIN, the agent checks in right away, so the console
  shows kiosk off without waiting for the next heartbeat. Needs agent 0.5.0 (thanks @letternumber).
- Agent rollout: when every device is in the canary, the rollout finishes once they've updated instead of staying
  open, and Settings stops offering the same agent again. If one can't update, it offers Finish instead of Promote
  (thanks @letternumber).
- Agent: commands like an app scan, app icons or an install no longer run on the thread the kiosk home screen uses, so
  the kiosk stays responsive while they run. Needs agent 0.5.0.
- Agent: after the screen turns off and on, or the network drops, the agent keeps one connection to the server
  instead of sometimes opening a second one or leaving one open. Needs agent 0.5.0.
- Agent: the 10-minute heartbeat keeps its pace when the device's clock is corrected, for example right after setup.
  Needs agent 0.5.0.
- Agent: device events (boot, app installed, and so on) are no longer lost when two arrive at once or when the agent
  is stopped during a check-in. Needs agent 0.5.0.
- Agent: a 10-minute heartbeat that arrives while an install or another check-in is still running no longer keeps
  Android waiting, which could get the agent stopped. The device stays awake for up to about 10 minutes while the
  install finishes, so its result usually reaches the console right away. Needs agent 0.5.0.
- Agent: a device deleted in the console no longer reconnects to the server about once a minute forever; it waits
  longer each time, up to 5 minutes. Needs agent 0.5.0.
- Agent: if Android stops the agent's service, it comes back within 10 minutes (with its notification). Needs agent
  0.5.0.
- Agent: APK downloads left behind by an interrupted install are removed when the agent next starts or installs an
  app, and otherwise by the agent's regular heartbeat once they are an hour old. Needs agent 0.5.0.
- Agent: the device timeline no longer shows a network change each time the agent's service starts. Needs agent 0.5.0.
- Console accessibility: inputs, icon buttons, toggles, status dots and bars reach 3:1 contrast in both themes, help
  and status text is darker, the selected tab, section and page are outlined, and disabled settings stay readable.
- Console: a toast waits while you point at it or tab into it, can be dismissed (Escape too), and a failure stays 30
  seconds. A repeated message shows once with a count. Three show at once (two on a phone 360px wide or less) and the
  rest wait behind "N more": a failure gets its full 30 seconds once it shows, a success or warning goes on time. They
  sit above the Save bar.
- Enroll, for screen readers: each device that enrolls or changes state is announced once (several at once are
  counted), and so is the page stopping looking, each time. The Wi-Fi notes are read as they change, including when
  Security goes from Open to WPA.
- Console: the device's Location tab lists its locations under the map, for keyboards and screen readers, and a
  table that scrolls sideways can be reached with Tab.
- Console on phones: device names and reasons wrap instead of being cut off, main buttons and menu items are 44px
  tall, and the top bar no longer covers the field you tab to.
- Console: the sign-in pages and a device that can't be found name the browser tab.
- Kiosk: a single-app kiosk reopens its app once when it is closed, instead of starting it twice, and rotating the
  device or changing the font or display size no longer restarts the kiosk screen. Needs agent 0.5.0.
- Kiosk: when the exit PIN is right but the device can't leave kiosk just then (for example when its
  storage is full), the launcher says so instead of doing nothing. Needs agent 0.5.0.

Under the hood
- The release manifest records the tagged commit, which `setup.sh --update` checks the tag against. CI tests
  `--update` against signed fixture releases.
- `install/options.json` lists the installers' questions and answers, for the website's install guide. CI checks it
  against the scripts with dry runs.
- The kiosk background image's address is built from the server's current address each time a configuration is sent
  (config.apply) and each time Enter kiosk is queued (`kiosk.enter`, one device or many), so moving the server to HTTPS
  or another domain needs no new pick of the image. The adaptive scrim is documented in
  `docs/kiosk-background-scrim.md`, with test vectors (`proto/vectors/kiosk-scrim.json`) the console and the agent
  both pass. `GET /rest/private/configurations/backgroundImage/{fileId}` describes an image as devices will get it.
- NOTICE now says which parts of the agent are ported from Headwind MDM, and those files carry its license header.
- Console: a new colour token for extensions, `--control-border`, the 3:1 edge for icon buttons and selected
  items. `npm test` checks every token pair's contrast, which component edges must be 3:1, and runs axe on each page;
  `scripts/shots/a11y.mjs` runs axe, a keyboard walk and reflow checks on the built console in both themes at 1280,
  640 and 320px, and CI runs its quick pass.
- Agent: the APK carries English resources only (about 90 KB smaller), and its screen text lives in string
  resources.
- Agent protocol 1.6 and 1.7 (additive): config.apply `policies.locationServices`, and the `appPermissions` section
  with the `app.runtimePermissions` capability.
- Agent protocol 1.9 (additive): the enroll request's optional `enrollAttempt` and `resumeOnly`, the enroll response's
  `resumeSupported`, `error.agent.enrollment.notResumable`, and the retryable enroll errors
  `error.agent.enrollment.failed`, `error.agent.enrollment.inProgress` and `error.agent.enrollment.deviceLimit`. New
  console API: `GET /rest/private/agent/v1/devices/enrolled`.
- Protocol 1.10: `theme.backgroundImage` may carry `fit` and `focalX`/`focalY`, sent only when not the default
  (additive). `GET /rest/private/configurations/backgroundImages` lists a customer's kiosk images and
  `POST /rest/private/configurations/backgroundImages/{fileId}/delete` deletes an unused one.
- Dependencies: Room 2.8.5, WorkManager 2.12.0, Retrofit 3.0.0, AppCompat 1.8.0, desugar_jdk_libs 2.1.5 (agent);
  Jackson 2.22.4, Guava 33.8.0, commons-codec 1.23.0, ActiveMQ 5.19.12, classgraph 4.8.200 (server); vite 8.3.6,
  @vitejs/plugin-react 6.2.1, vitest 5.1.0, jsdom 31.0.0 (console); Caddy 2.11.9; docker/login-action 4.8.0,
  setup-java 6.2.0, softprops/action-gh-release 3.0.4.

## v0.4.0 — 2026-11-03

The console is new and replaces the old one. Every page was rebuilt, and each page loads only when it's opened.
- **Overview** (now at `/`): counts that open the Devices list filtered, a Needs attention list with one action per
  device (Sync now, Update agent or Open device), and sync per configuration. It refreshes while its tab is open.
- **Devices**: an Attention filter with each device's reason, sorting, pages, and Sync now and a menu on each row.
- **Device page**: details in a column beside the tabs, a new Apps tab, and Retry apply when its configuration isn't
  applied.
- **Configuration editor**: its own address (`/configurations/12`), a count of changes per section, Discard and Save
  and apply in a bar, and a prompt before you leave with unsaved changes.
- **Apps**: the Library is a table, with a source filter and the configurations each app is used in.
- **Settings** has Change password and a Match system theme; Density is gone for now. **Enroll** has a full-screen QR.
- On phones the Devices list turns into cards, section lists scroll sideways and the save bar sits at the bottom.
- Server updates show in a card in the sidebar. The fonts ship with the console: nothing loads from Google Fonts.

Upgrading
- Update the server: Settings → Updates (quick start), `git pull && ./setup.sh` (from source), or
  `git pull && sudo ./setup.sh --native` (native). The database updates itself on start (three new columns and an
  index), and there is nothing new to set. No device re-applies its configuration, and the update queues no app
  installs.
- There is a new agent, 0.4.0: roll it out from Settings → Agent rollout. Alerts on QR-enrolled devices and the
  connectivity mode need it. Rolling back to 0.3.9 is safe:
  [UPGRADING, v0.4.0](https://github.com/MDMesh-app/MDMesh/blob/main/docs/UPGRADING.md#v040).

Heads-up
- Apps added to a configuration now install on its devices at their next check-in, without Install config apps.
  Apps it already had are not sent again (thanks @letternumber).
- The console now hides the pages and buttons a user's role can't use, which the server already refused. A link to
  a hidden page says "You don't have access to this page". What each permission shows:
  [UPGRADING, v0.4.0](https://github.com/MDMesh-app/MDMesh/blob/main/docs/UPGRADING.md#v040).
- A role change reaches an open console when its tab is shown again or the page is reloaded, with no new sign-in.
- Old bookmarks still work: `/dashboard` opens the Overview at `/`, and `/configs` opens `/configurations`.
- Forks: the console's page files moved, so a patch to a v0.3 page won't apply, and the console build stops on a
  v0.3 file left in the tree. Each file's new place, its slot and what to check after the merge:
  [UPGRADING, v0.4.0 for forks](https://github.com/MDMesh-app/MDMesh/blob/main/docs/UPGRADING.md#v040-for-forks).

New
- Connectivity per configuration (Configuration → Network): Always-on keeps the agent's live connection up, so
  commands reach a locked phone without waiting for its next check-in. "—" leaves each device's own mode. Needs agent
  0.4.0 (thanks @Przemek-Magic).
- Device page, Apps tab: the apps with a launcher icon from the device's latest scan, with search, a system-apps
  filter, Export CSV and Scan now (thanks @Przemek-Magic).
- The Location tab has a time range: the last hour, 24 hours or 7 days, or All (the default). A range shows its
  newest 500 fixes at most and says when it hits that cap; All shows the newest 500, as before (thanks @Oxym0r3).
- Audit log: device commands, such as wipe, lock, PIN reset, kiosk and alert, and Install config apps, are recorded
  with who sent them, from where and to which devices. Their settings are not, since they can hold a password
  (thanks @Przemek-Magic).
- **Console extensions for forks**: pages, device tabs, configuration and Settings sections, Overview cards, and list
  columns and actions, added in `web/src/extensions/<id>/` without editing core files. The guide is
  [EXTENDING](https://github.com/MDMesh-app/MDMesh/blob/main/web/EXTENDING.md).
  A breaking change to their API (`@mdmesh/console`) gets one release of deprecation first.
- **Server plugins for forks** go in `plugins/fork/<id>/`. For the customers it is on for, a plugin can check its data
  in a configuration's new `extensions` object on save, and add `x.<plugin>.<key>` policy keys to what devices apply.
  The device page names such a key in words (`x.netaccess.blockTethering` is "netaccess: block tethering").
- What's new: after an update, the console shows the new version's notes once in each browser, but not on a
  browser's first visit. Settings → Server opens them again, beside the version.
- A device's open tab is in its address (`/devices/WH-12?tab=telemetry`), so a link opens that tab and Back returns
  to the previous one. The arrow keys change tab in place, so Back then leaves the page.

Fixes
- Alerts show on devices enrolled by QR code, with the "MDMesh active" notification, and an alert is reported failed
  when notifications are off on the device. Needs agent 0.4.0 (thanks @Przemek-Magic).
- Device page: Enrolled shows when the device enrolled with the MDMesh agent. Devices enrolled before this update
  keep it empty (thanks @Przemek-Magic).
- Storage and Local IP show on the device page. The old console read keys the agent never sends, so both were "—".
- Saving a configuration keeps its apps' Headwind launcher layout (screen order, key code, bottom row, long tap).
- Enroll shows a configuration's EAP Wi-Fi security as EAP, not as WPA / WPA2.
- Sign-in starts a fresh session, and so does changing your own password.
- Audit log: requests from API clients are recorded with their user, and a request is recorded however its path is
  spelled.

Under the hood
- The console has unit tests (vitest), and CI runs them on every console change.
- The server no longer packs the removed AngularJS console's plugin files.
- The console no longer keeps the sign-in token (`authToken`) or a password-reset token in browser storage. When it
  loads, it removes the copies an older version left there.
- Setting a password with a reset token (`POST /rest/public/passwordReset/reset`) answers OK with no data. It returned
  the account, with the new password's stored hash in `newPassword`.
- A server plugin whose `plugins` row is `disabled` (set by hand; MDMesh never sets it) is now off for every customer:
  its private REST answers 404 and its checks and policy keys don't run. Restart the server after changing it.
- Dependencies: react-router 8.5.1, vite 8.3.5, docker/login-action 4.7.0.

## v0.3.9 — 2026-10-27

Upgrading
- Update every server: this is a security release. Devices need no new agent, and the updater (supervisor) is
  unchanged. Quick-start installs: apply the update from Settings → Updates. From source: `git pull && ./setup.sh`.
  Native installs: `git pull && sudo ./setup.sh --native`.

Heads-up
- Sign-in is limited. After 10 failed sign-ins from one address in 15 minutes (or 50 in 6 hours), that address is
  refused for a while; after 20 failed sign-ins for one account in 15 minutes, new browsers on new addresses are
  refused for it for a while. A browser or API client that signed in to the account before (the MDMESH_SIGNIN
  cookie), and an address that signed in to it in the last 30 days, keep signing in. The console says "Too many
  failed sign-ins"; the API answers 429 with Retry-After: 900. Checking the current password for a password or
  e-mail change counts too. A server restart clears every limit (docs/OPERATIONS.md).
- A token sign-in (/rest/public/jwt/login) also sets the MDMESH_SIGNIN cookie: API clients that keep it are spared
  someone else's failed sign-ins.
- Native installs behind a proxy on another host must list it in proxy.addresses, and Docker installs behind a CDN
  or another proxy in front of Caddy must add it to TRUSTED_PROXIES in .env: otherwise every client shares one
  sign-in limit (docs/UPGRADING.md).
- On an install whose BASE_URL is https, the console session cookie is Secure, and the console can no longer be
  signed in to over plain http (for example through Tomcat's own port on a native install). Use the https address.
- The edge, and on native installs the server, send browser security headers: the console can't be put in a frame,
  and https installs get Strict-Transport-Security for six months.
- A request path with a `;` or an encoded `/`, `\`, `;` or `.` is refused with 400. No MDMesh client sends one. A
  hosted file whose name has a `;` can't be downloaded: upload it again (uploads now replace `;`).

Security
- Requests to the console's and plugins' private routes always need a signed-in user, however the path is spelled.
- Failed sign-ins no longer hold a server thread, and password guessing is limited per address and per account.
- The session cookie is Secure on https installs. The edge and the server send no-framing, nosniff, Referrer-Policy and
  (on https) HSTS headers.
- The mail library is Jakarta Mail 1.6.8 (CVE-2025-7962).
- Thanks to Przemek-Magic for the report.

## v0.3.8 — 2026-10-26

Upgrading
- Update every server: this is a security release. The security fixes need no new agent; the location fix below
  needs agent 0.3.8.
- Update the updater too, since updates never touch it. Quick-start installs: apply the update from Settings →
  Updates, then `docker compose pull && docker compose up -d` (if `.env` pins `SUPERVISOR_VERSION`, set it to 0.3.8
  first). From source: `git pull && ./setup.sh`, which also moves the pinned `SUPERVISOR_VERSION`. Native installs:
  re-run the installer, which updates both. Either order works.
- Single-customer installs: check for customers you didn't create, and remove them (docs/UPGRADING.md, v0.3.8).
  The server lists them at startup.

Heads-up
- Updates are for the admin who runs the install: a super-admin, or the org admin (the built-in Administrator role) of
  a single-customer install (every install made with quick start, setup.sh or the native installer is one). Once an
  install has a second customer, only a super-admin can apply, roll back, check for or schedule updates. The recovery
  token still rolls back.
- Setting a password ends that account's other console sessions and its API tokens. The session that changed its own
  password stays signed in. API clients sign in again.
- Password reset links work once, for 24 hours. Links sent before the update no longer work: ask for a new one.
- Impersonating a user or a customer's admin is a POST; a GET answers 405. The console doesn't use these routes.

Security
- The self-signup API is removed. MDMesh never offered self-signup; its routes now answer 404. At startup, a
  single-customer install logs any other customer it has, with how to remove it.
- The updater asks the server whether the session may manage the deployment before it applies, rolls back or changes
  automatic updates. On an install with more than one customer, only a super-admin may. In front of an older server,
  only a super-admin or the org admin of customer 1 may.
- Every customer account route needs a super-admin.
- Setting a password, by reset link, at first sign-in, by the user or by an admin, ends the account's other sessions
  and API tokens. Reset links expire after 24 hours and work once. An account gets at most 3 recovery mails an hour.
- The console session cookie is SameSite=Lax: another site's forms and scripts can't POST, PUT or DELETE with it.
  Purging message history in the messaging and push plugins, and impersonating, are POSTs.
- A new customer admin's first password, the signature a refused download should have had, and a deleted customer's
  file paths are no longer logged.
- Thanks to a security researcher (#120) for the report.

Fixes
- Location: devices enrolled by QR code report their location. The agent grants itself the background location access
  it was missing, and "GPS (fresh fix)" configurations get a fresh fix instead of always timing out. Needs agent
  0.3.8; no server update needed (thanks @lared3294).

## v0.3.7 — 2026-10-22

Upgrading
- Update the server, then roll out agent 0.3.7 from Settings → Agent rollout, to one device first.
- Until devices have agent 0.3.7, set Safe Mode to Block on configurations whose devices you put into kiosk with Enter
  kiosk.

Fixes
- Kiosk: Safe Mode is blocked whenever a device is in kiosk, including kiosks started with Enter kiosk on the device
  page, unless the configuration's Safe Mode setting is Allow. The block is lifted once nothing asks for it: when such
  a kiosk ends, or when kiosk is turned off on the configuration, unless the setting is Block. Needs agent 0.3.7.
- Kiosk: an exit PIN that can't be checked right now says so ("Exit unavailable right now") instead of closing.
- The console's kiosk help says which behaviour needs agent 0.3.6 or later.

## v0.3.6 — 2026-10-21

Upgrading
- Update the server first, then roll out agent 0.3.6 from Settings → Agent rollout, to one device first. This is a
  security release for kiosk devices: update every server.
- Kiosk configurations without an exit PIN have no exit on the device with agent 0.3.6. Set an exit PIN first if you
  need one.
- Kiosk devices re-apply their configuration once after the server update, because Safe Mode is now blocked on them
  unless allowed.

Heads-up
- Kiosk: a kiosk without an exit PIN has no exit on the device; end it from the console. Set an exit PIN on the
  configuration to keep the on-device exit. Needs agent 0.3.6.
- Legacy device API (`LEGACY_DEVICE_API=1`): removing a Headwind content app from a configuration on the Apps page no
  longer turns kiosk mode off by itself. Turn kiosk off on the configuration page if you want it off.

Fixes
- Kiosk: a kiosk app that keeps stopping no longer ends kiosk. The device stays in kiosk on an "App closed" screen with
  an "Open again" button, until kiosk is ended with the exit PIN or from the console. Needs agent 0.3.6.
- Kiosk: a kiosk change that fails on a device already in kiosk leaves the device in the kiosk it had. Needs agent
  0.3.6.
- Kiosk: Recents and Notifications stay off while Home is off, as Android requires. Before, kiosk didn't start with
  that combination.
- Kiosk: too many wrong exit PINs lock the exit for a while, and administrators get an email (needs SMTP). Needs
  agent 0.3.6.
- Kiosk: Safe Mode is blocked on kiosk devices unless the configuration's Safe Mode setting says Allow.
- Saving app assignments on the Apps page no longer changes other configurations' settings, and devices pick up the
  change right away.
- A device whose agent stopped while applying its configuration (Android ended the app, the phone restarted, the
  agent updated) gets the configuration again on its next check-in. Before, newer changes could wait up to 6 hours.

## v0.3.5 — 2026-10-21

Upgrading
- Update the server: Settings → Updates (quick-start installs), or re-run the installer on native installs. Then
  roll out the new agent from Settings → Agent rollout, to one device first. Devices enrolled by QR code on 0.3.4 or
  earlier apply their restrictions once they have the new agent (or after a restart).

Fixes
- Agent: restrictions that need Device Owner (camera, screenshots, factory reset, Bluetooth, USB storage, Safe Mode,
  Wi-Fi) apply on devices enrolled by QR code. Before, they showed as "unsupported" until the phone restarted.
- Re-running the native installer to upgrade keeps `BASE_URL`, `GITHUB_TOKEN` and a `GITHUB_REPO` you set from the
  existing install, so `sudo ./setup.sh --native -y` is enough. A repo that came from the clone's origin keeps
  following the origin. A value you pass still wins, and `GITHUB_TOKEN=` clears the token.
- The native installer says when GitHub refuses the token, and downloads the release without it. Rate limits are
  reported as rate limits.
- Saving a configuration keeps its configuration files and app settings.
- Configuration copies keep their apps, and saving one never overwrites the original.
- Device pages always open the device you clicked, even when a device's number matches another device's internal id.
  Device action dialogs, Enter kiosk included, name the device.
- Enroll: "New code" keeps the chosen configuration.
- Location popups in the device map render as plain text.
- The "Password updated" notice shows on the sign-in page.
- The enrollment QR code is drawn at its real size, so it isn't blurred and scans reliably (thanks @vanek-Impacts).
- A device's recorded public address comes from the edge, not from headers the device sends. Native installs: set
  your proxy's headers and limit Tomcat's port to the proxy, as docs/OPERATIONS.md "Device public address" describes.

Under the hood: dependency updates (React Router 8, Caddy 2.11.7, pgjdbc 42.7.14, Vite, Guava), and CI runs the
agent's protocol tests on every change.

## v0.3.4 — 2026-10-14

A follow-up security release with console and agent fixes. Please update every server.

Upgrading
- Update the server first (Settings → Updates, or re-run the installer on native installs), then roll out
  agent 0.3.4 from Rollouts, to one device first. Server first matters: a new agent talking to an older
  server still has its location stored on "Do not track".
- After the update, devices on "Do not track" configurations re-apply their configuration once. Kiosks on
  agent 0.3.0–0.3.2 relaunch once, and a device someone took out of kiosk by hand goes back into kiosk if
  its configuration still has kiosk on.

Heads-up
- "Do not track" now really collects no location. It is the default for the configurations the installer
  creates and for new ones, so those devices stop reporting location. To keep it, set Location reporting to
  "Last known (passive)" (was "Network") or "GPS". Location already stored is kept.
- Kiosk exit PIN: new installs get a random PIN, shown (and editable) in the configuration editor. A
  configuration still on the old default 12345678 gets a warning in the console. Set your own PIN there.
- The Headwind launcher's QR routes now answer 404 unless LEGACY_DEVICE_API=1 (the console draws the
  agent's QR itself). The installer-created configurations get new QR keys.
- The read-only Observer role can no longer send push messages or edit device descriptions.
- SECURE_ENROLLMENT=1 also blocks the agent's APK downloads; the server now warns about it at startup.
  Leave it at 0 for MDMesh agents.

New (agent 0.3.4)
- Wi-Fi radio on/off per configuration.
  - Off is skipped while Wi-Fi is the device's only network, shown as deferred, and tried again at the
    next configuration change, reboot or agent update. Phones that drop mobile data while on Wi-Fi can't
    apply it.
  - If a device can't reach the server for about 30 minutes after Wi-Fi went off (SIM removed, airplane
    mode, server only reachable over Wi-Fi, a server outage), it turns Wi-Fi back on and keeps it on until
    the configuration changes. The console shows "Reset by device".
  - The old Wi-Fi setting, now "Wi-Fi settings", still only locks the settings screen.
- Lock volume buttons in kiosk. Android mutes the device while it's locked, so Ring device is silent.
  "Lock hardware buttons" is the power menu only.

Fixes
- The configuration editor shows only settings the agent applies, with clearer Not managed / Allow / Block
  choices and kiosk-only labels. Old Headwind launcher settings moved to their own section.
- The dashboard only says "in sync" after a complete check, and flags devices whose configuration isn't
  applied.
- "Deferred" results show as a warning instead of a failure.
- The hosted-APK check uses the server's address, and the device page shows just the agent version.
- App SHA-256 must be 64 hex characters.

Under the hood: agent protocol 1.4 (additive), unused Headwind dependencies removed.

## v0.3.3 — 2026-10-13

This is a security release. A full review of the server found a number of permission and input-handling
issues, several of them serious, mostly in code inherited from Headwind. All are fixed here. Please update
every server as soon as you can.

Upgrading
- Update the server first: Settings → Updates (quick-start installs), or re-run the installer on native
  installs. Then roll out the new agent from Rollouts, to one device first. Older agents keep working
  until they update.

Heads-up
- App installs are now always checked against a SHA-256. Uploaded APKs and F-Droid apps are covered
  automatically. For an app added by an external link, add its SHA-256 in the Library (new "SHA-256…"
  button) or upload the APK; until then its installs stop with a message saying so.
- The old Headwind launcher endpoints are now off by default (LEGACY_DEVICE_API=0). The MDMesh agent
  doesn't use them. Unused legacy routes (self-update, config-file and video uploads, stats, AppList
  upload) are removed.
- Organizations with two-factor sign-in turned on no longer get API (JWT) tokens.
- Users limited to some device groups only see and act on those devices. Creating, promoting and
  cancelling rollouts needs access to all devices.
- Changing your email address asks for your current password. Org admins can't create, edit or
  impersonate superadmin accounts.
- Icon uploads need the Settings permission (max 1 MB, 1024 px).

New
- Disable Safe Mode (Configuration → Restrictions), so Safe Mode can't be used to get out of kiosk.
  Needs the new agent.
- The native installer brings its own Node 24 and JDK 17 when the distro's are too old, so Debian 12/13
  and Ubuntu 24.04 work out of the box.
- Password-recovery email on native installs, and SMTP_SSL / SMTP_STARTTLS on Docker.

Fixes
- Agent updates that got stuck are sent again, and the agent no longer freezes waiting on an install.
- The agent connects as soon as it's enrolled, so commands don't wait for the first check-in.
- Moving a device to another configuration (e.g. kiosk) applies right away.
- Command results survive an agent restart, and large backlogs are sent in batches.
- Setting statuses refresh after an agent update, without a kiosk flicker.
- The old Headwind demo apps are no longer sent to devices.

Under the hood: agent protocol 1.3 (additive), Node 24 across the build, a stricter release pipeline.

Thanks @TalesRayn and @m87938510-ctrl for the detailed bug reports, and @kmehmonov for the Safe Mode request.

## v0.3.2 — 2026-10-08

Upgrading
- Update the server from Settings → Updates (quick-start installs) or with the command it shows.
  Then roll out the new agent from Rollouts. Agents on v0.3.1 keep working until they update.

Heads-up
- Kiosk: apps set to hide their icon are no longer allowed in the kiosk launcher. Tick
  "Show in kiosk" on any app you still want there.
- The old Headwind launcher entry is removed from the app library and from configurations
  that used it as the main app.

New
- Block factory reset (Configuration → Restrictions). Needs the new agent.
- "Show in kiosk" per app, separate from installing it.
- Remove apps from the Library. Devices keep the installed app.

Fixes
- Agents install APKs hosted on other servers, and pick up queued commands as soon as they reconnect.
- Manual and bulk "Enter kiosk" keep the configuration's launcher colours.
- One-click update and rollback work on Docker installs. Native install and upgrade fixes.
- An expired session takes you to sign-in, then back to the page you were on.
- Dialogs stay on screen on long pages.
- The device page shows the last known location.
- Configurations show app versions, and sync status refreshes after saving.
- Library: only installable apps are listed, no duplicate entries, uploaded versions are kept, and
  shared APK files are no longer deleted with another entry.

Under the hood: React 19, dependency updates, quieter logs.

Thanks @letternumber, @Keidevk and @benailjamim for the contributions.

## v0.3.1 — 2026-09-25

v0.3.1 — security fixes, Docker supervisor fix (#27), Cloudflare-mode fix

Security
- Agent admin and rollout APIs now enforce the `edit_devices` permission for every change: queueing commands (incl.
  wipe / passcode reset), bulk commands, sync, app sync, minting enrollment tokens, and creating, promoting or cancelling
  rollouts. Users without it (the seeded Observer role, or custom roles) now get "permission denied"; read-only views are
  unchanged. The built-in Administrator and User roles already have it.
- New auth tokens, password-reset tokens and generated passwords now come from SecureRandom (previously java.util.Random),
  and a build-time guard fails on any weak random generator. Reset links and server-generated org-admin passwords issued
  BEFORE this release came from the old generator: re-issue reset links and have those users change their password
  (changing a password also rotates that user's stored session token).
  The default JWT signing key (used only by API clients of /rest/public/jwt/login) is still generated at each server
  start, now from SecureRandom.
- Device command history no longer returns command payloads (configuration and kiosk commands embedded the configuration
  admin password hash); the API returns the target package as `subject` for app.install/app.uninstall. The queue endpoint
  (POST …/devices/{id}/commands) answers with the same slim view instead of echoing the payload. Integrations that read
  `payload` or `deviceNumber` from these endpoints must adapt.

Fixes
- Docker (#27): the updater/recovery supervisor crash-looped on every Docker install since v0.1.0
  ("Cannot find module '/project/server.js'"). Settings → Updates, the recovery page and the Docker /files/agent.apk
  download (the APK URL in enrollment QR codes) now work. On from-source Docker installs the recovery page shows status and
  the manual update steps (no one-click Roll back). Thanks @detobel36 for the diagnosis.
  Quick-start: `docker compose pull && docker compose up -d` (if .env pins SUPERVISOR_VERSION, set it to 0.3.1 first).
  From source: `git pull && ./setup.sh` (not just `docker compose up --build`).
  The supervisor now runs for the first time on existing Docker installs: take a DB backup before your first one-click update.
- Existing quick-start installs (CURRENT_VERSION=0.0.0) show "Update available: 0.3.1" after pulling; applying it once pins
  the versions (it takes a DB backup and restarts the server briefly). New quick-start installs pin the release they install
  (the supervisor stays on :latest so pulls deliver its fixes).
- From-source Docker installs no longer offer one-click Update (it could only fail and roll back); update with
  `git pull && ./setup.sh`, which now also records the running version from the checkout's tag on every run, so the console
  no longer shows a permanent false "Update available". Pull and native installs are unchanged.
- Cloudflare mode: the web container's Caddy config now starts with an empty ACME_EMAIL. Own-domain mode unchanged.

CI / release
- The supervisor image is smoke-tested (booted the way compose runs it) in the fast gate and before any release push, and the
  release publishes exactly the image that passed. `:latest` image tags move only after the release is published (same digest
  as `:VERSION`). The Caddyfile is validated in every hosting mode and all compose files are checked in CI.

## v0.3.0 — 2026-09-22

v0.3.0: desired-state configuration — saving a configuration now converges its devices

Configurations are now enforced on devices, not just stored.

- Server: on every check-in the server derives a desired-state document from the device's configuration, hashes it, and sends one `config.apply` command when the device has drifted (delivered in the same check-in). Saving a configuration wakes its devices. New admin endpoints: `GET /rest/private/agent/v1/devices/{id}/configStatus` and `GET /rest/private/agent/v1/configurations/syncSummary`.
- Agent: applies Wi-Fi, Bluetooth, USB storage, screenshots, kiosk (same path as `kiosk.enter`) and location capture mode; reports per-key outcomes (applied / unsupported / failed); persists the revision only when nothing failed; re-applies after boot or self-update. Advertises the new `configApply` capability.
- Console: an "Enforced" chip marks the 18 fields the agent applies; the rest are collapsed under "Legacy Headwind fields". Configuration list shows "N/M in sync"; device pages get a "Configuration status" card; kiosk-affecting edits ask for confirmation with the affected device count. The main-app picker now stores the correct application-version id.
- Protocol: additive (1.1). Old agents are never sent `config.apply` and show "Agent too old" until updated.

Behaviour change on upgrade: the first check-in of every updated device applies its configuration's managed policies, and devices whose configuration has kiosk ON enter kiosk. Kiosk is never lifted by the upgrade itself; turning kiosk OFF in a configuration only lifts it on devices that entered through that configuration. Location capture mode follows the configuration (GPS = active, otherwise passive). See DEPLOY.md "Upgrading to v0.3.0".

Database: two nullable columns on device_state (Liquibase, automatic). Docker installs: pull the new images as usual.

## Older releases

v0.1.0 to v0.2.9: see their notes on the [GitHub releases page](https://github.com/MDMesh-app/MDMesh/releases).
