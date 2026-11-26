# Policies server plugin

The server side of the console's Policies extension (`web/src/extensions/policies`), same id: `policies`.

## What it does

`GET /rest/plugins/policies/private/fleet` returns every phone the signed-in user may access, with what the Policies
page shows for it: brand (from telemetry), configuration status (the same shape as core's per-phone
`/private/agent/v1/devices/{number}/configStatus`), the latest app scan (package, name, system flag), and the open
scans and uninstalls.

Without it the page makes three requests per phone, and one of them downloads the phone's whole command history. With
it the page makes one request for the whole fleet. Measured on 41 phones: 123 per-phone requests and about 470 KB
became one request.

## How

- **Two SQL queries** for the whole fleet (`FleetMapper`). The latest config.apply and the latest finished scan of each
  phone are LATERAL lookups on core's `(deviceNumber, type, id)` index: one row read per phone, however long its
  history.
- **Access:** the phones are filtered as core filters them: the user's customer, and core's own
  `DeviceMapper.USER_DEVICE_ACCESS` clause unless the user is a super-admin. The route needs the `configurations`
  permission, like the Policies page.
- **Status:** worked out by core's own `ConfigReconciler`, once per configuration, not per phone. A plugin can't
  compile against the server module, so `CoreStatus` looks it up by name at runtime. If core ever renames it, the
  plugin logs a warning, answers `statusAvailable: false`, and the console asks core per phone: slower, never wrong.
- **Read-only:** the fleet request writes nothing.

## Bulk jobs

`POST /rest/plugins/policies/private/jobs` runs one action on many phones in one request (needs `edit_devices`):
`reapply` (wake only), `scan`, `command` (the device page's remote actions, allowlisted on the server with their
capability; wipe and passcode reset are never sent), `install` (core builds the install commands) and `remove`
(uninstalls, with an optional alert). `GET .../jobs` lists the last 24 hours with progress; `POST .../jobs/{id}/cancel`
stops one.

- **One transaction:** the job, its phones and all its commands are stored at once. The commands go into core's
  `agentCommand` in batched INSERTs (500 rows a statement) linked to the job in the same statement.
- **Paced wakes:** one worker (`JobWorker`) wakes the next queued phones each second, oldest first, at most
  `-Dmdmesh.policies.wakesPerSecond` (default 5). Offline phones aren't woken: their commands wait for their own next
  check-in, which spreads them out by itself. The wake service is core's, reached at runtime (`CoreWake`); without it,
  every phone simply gets its commands at its next check-in.
- **Retries:** core expires a command nobody picked up after 60 minutes. The worker queues a never-delivered expired
  command again, at most 3 times in all, within 24 hours. A command a phone received is never sent twice.
- **Cancel:** no more wakes, and the job's commands no phone has received yet expire ("Cancelled from the Policies page").
- **Restart-safe:** the state is in `plugin_policies_job`, `plugin_policies_job_device` and `plugin_policies_job_command`.
  Jobs older than 30 days are deleted (their commands stay in core's history).

## Automation

`GET`/`PUT /rest/plugins/policies/private/automation` reads and saves a customer's settings (`PUT` needs
`edit_devices`). The job worker runs `AutomationService` once a minute:

- **Nightly sweep (automatic scans on):** inside the window (hours in the customer's time zone; it may cross midnight),
  the phones are scanned in id order, a batch a minute sized to finish within the first half of the window. A cursor
  (the last id reached) moves one way only and starts again from the first phone the next night: C-LOOK. Phones
  scanned in the last 20 hours and wiped phones are skipped. One job per night, "Nightly app scan", grows a batch at a
  time; cancelling it stops the sweep until the next night.
- **New phones (automatic scans on):** phones never scanned get a scan at any time, five a minute, at most once every
  six hours each until one succeeds.
- **Auto-remove:** on a kiosk configuration whose policy is set to Auto-remove, each scan that finished since the last
  check (the `autoRemoveSince` time) is compared with the allowed apps (`AutoRemoveRules`, the console's rule), and the
  extra apps are uninstalled through one job, with the policy's alert. On its first run it starts from that moment:
  older scans are never acted on. A kiosk policy with no allowed app at all is left alone. The console's in-browser
  auto-remove stands down when the plugin answers, so nothing is uninstalled twice.

State is in `plugin_policies_settings` (one row per customer).

## Device groups and brands

`/rest/plugins/policies/private/groups`: `GET` (needs `configurations`); `POST` a group, `PUT`/`DELETE .../{id}`,
`PUT .../members` `{deviceIds, groupId}`, `PUT .../device-brands` `{deviceIds, brand}`, `POST .../brands` `{label}`,
`DELETE .../brands/{brand}`, `PUT .../policy` `{configurationId, groupIds}`. Changes need `configurations` and a user
who may manage all phones (a group covers whole brands), and answer with the whole new state.

- **A group** has a name, a policy (configuration) or none yet, brands and phones picked by hand. Every phone of its
  brands joins it, new ones too; a picked phone joins it whatever its brand. A brand and a phone are in one group at
  most (primary keys of `plugin_policies_group_brand` and `plugin_policies_group_device`); saving a group takes its
  brands and phones from any other group, in one transaction.
- **Brands:** a phone's brand is the one set on it by hand (`plugin_policies_device_brand`), else the one it reports.
  Brands can be made by hand (`plugin_policies_brand`) for phones that report none or a wrong one.
- **Nothing reaches a phone:** the plugin stores groups and brands only. Phones go to their group's policy when someone
  presses Apply on the Policies page, which uses core's bulk move (`PUT /rest/private/devices`), so core checks the
  user's access to each phone.
- Deleted or wiped phones drop out of every list; a group whose policy was deleted shows no policy.

## App schedules and Open app

`GET`/`POST /rest/plugins/policies/private/schedules/{configurationId}`, `PUT`/`DELETE .../{configurationId}/{id}`
(reading needs `configurations`; writes need `edit_devices` and a user who may manage all phones). A schedule opens an
app (`app.launch`, agent protocol 1.13) at one or more times on chosen days, in a time zone. Bulk jobs can send
`app.launch` too (Client actions, Open app).

- **Who:** every phone of the policy, the policy's phones in one group (placed in groups as the console does: picked by
  hand first, else by brand, so phones that join later are included; `BrandRules` mirrors the console's brand keys), or
  phones picked by hand.
- **Only phones that can open apps:** phones whose agent doesn't report the `launch` capability are skipped (core would
  never deliver the command to them anyway). Nothing locks the app: the user leaves it when they want.
- **Once, on time:** a slot is claimed in the database before it fires (once, even across restarts), up to
  `lateMinutes` after its time; its job has that deadline, so a phone offline at the time still gets it if it comes
  online before then, and an app never opens hours late (`JobWorker` expires what's left every 5 seconds).

## Fallback

The console uses the plugin when it answers and asks per phone otherwise (plugin missing, off for the customer, or
failing). A phone the plugin returns without a status gets only core's per-phone status call.

## Tests

```bash
docker run --rm -v "$PWD:/src" -v mdmesh-m2:/root/.m2 -w /src maven:3.9-eclipse-temurin-17 \
  mvn -B -q -pl plugins/fork/policies -am test -Dtest=FleetServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
