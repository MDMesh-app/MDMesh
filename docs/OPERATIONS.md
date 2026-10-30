# Operations

<sub>[← README](../README.md) · [Deploy](../DEPLOY.md) · [Upgrading](UPGRADING.md) · **Operations** · [Structure](../STRUCTURE.md) · [Contributing](../CONTRIBUTING.md) · [Releasing](../RELEASING.md)</sub>

## Configuration reference

**Docker:** settings live in `.env`, in the install directory (template: [`.env.example`](../.env.example)). After
you edit it, run `docker compose up -d`.

**Native:** pass a setting to `sudo ./setup.sh --native` in its environment. The installer writes it to
`/opt/mdmesh-tc/conf/Catalina/localhost/ROOT.xml` or `/etc/mdmesh/supervisor.env`. Later runs keep `BASE_URL`,
`SMTP_*`, `LEGACY_DEVICE_API`, `GITHUB_REPO` and `GITHUB_TOKEN` until you pass a new value. `HTTP_PORT` follows the
port the install serves on, `REPLACE_DATA` applies to one run, and the rest of `supervisor.env` is rewritten on every
run: only `GITHUB_REPO` and `GITHUB_TOKEN` survive a hand edit there (a hand-edited `GITHUB_REPO` also needs
`GITHUB_REPO_FROM=setting`, or the next run follows `origin` again).

### Server

| Setting | Where | Default | Meaning |
|---------|-------|---------|---------|
| `BASE_URL` | `.env`; native: env or prompt → `ROOT.xml` | none | Public address of the server, also put in the enrollment QR ([rules](#base-url-and-hostname-rules)). |
| `HTTP_PORT` | native env | current port; fresh install `8080` | Tomcat's HTTP port. |
| `REPLACE_DATA` | native env | asks; `-y`: keep | `yes` erases existing data on a re-run. |
| `DB_PASSWORD`, `HASH_SECRET` | `.env`; native: `ROOT.xml` | generated | Don't change them: the database keeps its original password, and a new `HASH_SECRET` invalidates every enrolled device's token. |
| `SERVER_JWT_SECRET` | `.env`; native: `ROOT.xml` `jwt.secretkey` | generated | API token signing key ([details](#api-signing-key-jwt)). |
| `SERVER_JAVA_OPTS` | `.env` (native: `JAVA_OPTS` in a `systemctl edit mdmesh-server` drop-in) | empty | Extra JVM flags, e.g. `-Xmx1g`. |
| `LEGACY_DEVICE_API` | `.env`; native env → `ROOT.xml` | `0` | `1` re-enables the Headwind launcher's device routes. |
| `SECURE_ENROLLMENT` | `.env`; native: always `0` | `0` | Legacy Headwind setting. Leave it at `0`: `1` blocks agent downloads. |
| `SMTP_HOST`, `SMTP_PORT` | `.env`; native env → `ROOT.xml` | empty, `25` | Mail server for password recovery and kiosk alerts. Empty host: no email. |
| `SMTP_FROM` | same | `mdm@<hostname>` (Docker), `mdm@localhost` (native) | Sender address. |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | same | empty | Mail login. |
| `SMTP_SSL`, `SMTP_STARTTLS` | same | `false` | `SMTP_SSL=true` for port 465, `SMTP_STARTTLS=true` for 587. |

**Kiosk alerts.** When too many wrong exit PINs lock a device's kiosk exit (agent 0.3.6 or later), the server emails
the users of that device's customer who have an email address, may edit devices (or are super-admins) and can see the
device (all devices, or one of its device groups). At most one alert per device, and ten per customer, per hour.
This needs `SMTP_HOST`; without it the lockout only shows in the device's events.

The wizard writes `SITE_ADDRESS`, `ACME_EMAIL` and `TUNNEL_TOKEN` for the hosting mode you choose.

### Updater (supervisor)

| Setting | Where | Default | Meaning |
|---------|-------|---------|---------|
| `GITHUB_REPO` | `.env`; native env → `supervisor.env` | the clone's `origin` (quick start asks); native re-runs: the value you set, else the current `origin` | `owner/repo` to poll. Without it, there are no updates. On native, `GITHUB_REPO=` goes back to following `origin`. |
| `UPDATE_CHANNEL` | `.env` (native: always `stable`) | `stable` | `beta` also offers prereleases. |
| `POLL_INTERVAL_HOURS` | `.env` (native: always `6`) | `6` | How often to check. |
| `GITHUB_TOKEN` | `.env`; native env → `supervisor.env` | empty | Needed for a private repo; raises rate limits. See note. |
| `IMAGE_OWNER` | `.env` | quick start `mdmesh-app`; from source `local` | GHCR owner (lowercase). `local` means built from the clone. |
| `SERVER_VERSION`, `WEB_VERSION` | `.env` | the installed release | Image tags, without the `v` (`0.3.2`). Updates set them. |
| `CURRENT_VERSION` | `.env`; native: `supervisor.env` | `0.0.0` | Running release, compared with GitHub's latest. Updates and rollbacks set it. |
| `SUPERVISOR_VERSION` | `.env` | quick start `latest` | Supervisor image tag. Updates never change it. |
| `APPLY_SUPPORTED` | `.env` (native: always `0`) | `1` with a registry `IMAGE_OWNER`, else `0` | `1` shows one-click **Update**, `0` shows manual steps. |
| `AUTO_UPDATE` | `.env` | `0` | `1` applies releases unattended ([details](UPGRADING.md#quick-start-installs)). |

`./setup.sh` rewrites the version variables and `APPLY_SUPPORTED` from the clone on every run
([Upgrading](UPGRADING.md#from-source-docker-installs)).

**`GITHUB_TOKEN` note:** use a read-only token, either fine-grained with **Contents: read** on the repo or classic
with `repo` scope. On native the `mdmesh` user can read it. Pass it once, off the command line; later runs keep it
(and `GITHUB_REPO`) until you pass a new value, or `GITHUB_TOKEN=` to remove it:

```bash
read -rs GITHUB_TOKEN && export GITHUB_TOKEN
sudo --preserve-env=GITHUB_TOKEN ./setup.sh --native     # add GITHUB_REPO=owner/repo before ./setup.sh if you use one
unset GITHUB_TOKEN
```

### Email on native installs

Keep the password off the command line, where `ps` and shell history would show it:

```bash
read -rs SMTP_PASSWORD && export SMTP_PASSWORD     # type it; nothing is echoed
sudo --preserve-env=SMTP_PASSWORD SMTP_HOST=smtp.example.com SMTP_PORT=587 SMTP_STARTTLS=true SMTP_USERNAME=mdm \
  ./setup.sh --native
unset SMTP_PASSWORD
```

Later runs keep these settings unless you pass them again. An empty value clears a setting. `SMTP_PORT` must be a
number, and `SMTP_SSL` / `SMTP_STARTTLS` take `true`/`1` or `false`/`0`.

## Base URL and hostname rules

The installers check `BASE_URL` before they change anything (on a native re-run after an interrupted run, possibly
only once the old server is stopped). The rule is an allowlist
([`install/lib/url.sh`](../install/lib/url.sh)):

- `http://` or `https://`, in any case (stored in lowercase);
- a host name or IPv4 address (letters, digits, `.`, `-`; no leading or trailing `.` or `-`; no `..`), or a
  `[bracketed]` IPv6 address, then an optional `:port`;
- an optional path of letters, digits and `. _ : / + = , -`.

Nothing else is accepted: no spaces, quotes, `$`, `\`, `;` or `~`, no `user@`, no `?` or `#`, and no `%` escapes.

The hostname prompts take the host alone, with an optional port, such as `mdm.example.com` or
`mdm.example.com:8443`. In own-domain mode, Caddy also gets its certificate for that name. When you re-run
`./setup.sh`, it checks the `BASE_URL` already in `.env` against the same rule.

## Agent APK trust chain

`setup.sh` and the native installer trust the release's agent APK only after three checks pass:

- its `manifest.json` verifies with `minisign` against `release/minisign.pub`;
- the manifest names that same release;
- the APK matches the manifest's SHA-256.

Then they put the APK's checksum in the enrollment QR. The native installer also hosts the APK at `/files/agent.apk`.

If a check fails, or `minisign` is missing, the install still completes and prints the reason. The console keeps its
debug enrollment defaults. Either host an APK at `/files/agent.apk` yourself, or re-run once a verified release
exists.

For `setup.sh`, a missing `python3` or `curl` has the same effect. When it runs as root on Debian/Ubuntu, setup.sh
installs `minisign` itself; otherwise it uses the copy inside the supervisor image.

## Server logs

The server logs to stdout at INFO:

- **Docker:** `docker compose logs -f server`
- **Native:** `journalctl -u mdmesh-server -f`

Audit events (sign-ins, password changes, edits, commands sent to devices) are logged by `AuditLogger`, for example
`docker compose logs server | grep AuditLogger`. They are also stored in the `plugin_audit_log` table.

Tomcat's own files (`catalina.<date>.log` and the access log) are in `/usr/local/tomcat/logs` (container) or
`/opt/mdmesh-tc/logs` (native).

Docker keeps container logs without a size limit. To cap them, set
`"log-opts": {"max-size": "10m", "max-file": "5"}` in `/etc/docker/daemon.json`, then recreate the containers.

Installs upgraded from v0.2.1–v0.3.x may still have `/opt/mdmesh/log4j-mdmesh.xml` and `/opt/mdmesh/logs/`. Both are
unused and safe to delete.

### Temporary DEBUG log

Write a log4j 1.2 XML config, `log4j-debug.xml`, then point the JVM at it.

**Docker:**

1. Copy it into the container: `docker compose cp log4j-debug.xml server:/opt/mdmesh/`
2. Add `SERVER_JAVA_OPTS=-Dlog4j.configuration=file:///opt/mdmesh/log4j-debug.xml` to `.env`. If the value holds
   several flags, quote it: `SERVER_JAVA_OPTS="-Xmx1g -Dlog4j.configuration=file:///opt/mdmesh/log4j-debug.xml"`.
3. `docker compose up -d server`

A quick-start install made before v0.3.2 also needs `JAVA_OPTS: ${SERVER_JAVA_OPTS:-}` under `server:` →
`environment:` in its `docker-compose.yml`.

**Native:**

1. Put it at `/opt/mdmesh/log4j-debug.xml`, readable by the `mdmesh` user.
2. Run `systemctl edit mdmesh-server`, and under `[Service]` add
   `Environment=JAVA_OPTS=-Dlog4j.configuration=file:///opt/mdmesh/log4j-debug.xml`.
3. `systemctl restart mdmesh-server`

Undo it afterwards: DEBUG logs every SQL statement.

## Health checks

Docker installs have two unauthenticated probes for uptime monitors. Each answers `200 ok`, or `503` with a reason.
A trailing slash works too.

| Probe | `503` means |
|-------|-------------|
| `https://<host>/healthz` | `server unavailable`: the API server is stopped, starting, or erroring. This includes the minute or so an update takes. |
| `https://<host>/healthz/supervisor` | `supervisor unavailable`: devices and the console still work, but updates, `/recovery` and the `/files/agent.apk` mirror don't. |

No answer means the edge is down. For the database, `docker compose ps` shows whether `postgres` is healthy.

**Native installs** answer `404` on both paths. Point the monitor at `https://<host>/rest/public/name`, and on the
host check `curl -fsS 127.0.0.1:9000/healthz` or `systemctl is-active mdmesh-supervisor`. A native install from
before v0.3.2 serves the console on these paths until you
[re-run the installer](UPGRADING.md#native-installs).

## Device public address

A device page shows the public IP the device last checked in from. The server reads it from a header only when the
request comes from a loopback or private address, which it takes to be your proxy. From such a peer, the server takes,
in order:

1. `CF-Connecting-IP`, which Cloudflare sets.
2. Otherwise the **last** `X-Forwarded-For` entry, the one your proxy added. Earlier entries are whatever the device
   sent.
3. Otherwise the peer's own address.

A value that isn't an IP address is ignored. A request from any other address is recorded at that address.

**Docker:** Caddy sets these headers. It sends the server one `X-Forwarded-For` value, its own view of the client,
and drops any `CF-Connecting-IP` a device sends.

- **Cloudflare mode:** Caddy takes the client's address from cloudflared's `CF-Connecting-IP`. The mode publishes no
  ports, so devices reach Caddy only through the tunnel. Caddy knows the mode from `COMPOSE_PROFILES=cloudflare` in
  `.env`, which the installers write. A quick-start install from before v0.3.5 keeps its old `docker-compose.yml`
  through updates, which doesn't pass that variable, so Caddy looks for the `cloudflared` container instead. To pass it,
  add `COMPOSE_PROFILES: ${COMPOSE_PROFILES:-}` under `caddy:` → `environment:` in that file.
- **Own domain and the dev stack:** nothing a device sends is believed.

**Native installs:** keep Tomcat's port (`HTTP_PORT`, default `8080`) reachable only by your proxy. A device on a
private network that reaches it directly is trusted like a proxy, so it can set its own address. Either firewall the
port, or bind it to loopback: in `/opt/mdmesh-tc/conf/server.xml`, add `address="127.0.0.1"` to the HTTP connector,
after `protocol="HTTP/1.1"` (installer re-runs keep it there), then run `systemctl restart mdmesh-server`.

Your proxy must also set the headers itself and not pass on what the device sent:

- Overwrite `X-Forwarded-For` with the address the proxy sees; don't append to it. (The server reads the last entry,
  so a proxy that appends still gets the right address recorded, but overwriting leaves a device nothing to set.)
- Remove `CF-Connecting-IP`, unless the proxy is cloudflared, or sits behind Cloudflare and accepts connections only
  from Cloudflare. Otherwise a client that bypasses Cloudflare can set it.
- Overwrite `X-Real-IP` the same way. Code inherited from Headwind reads it from a proxy on the same host (or one
  listed in `proxy.addresses`): the IP allowlists `ui.allowed.address` and `device.allowed.address`, and the audit log.
  An allowlist blocks a request whose `X-Real-IP` isn't an IP address.
- Behind a load balancer, have nginx take the client's address from it first (`set_real_ip_from <balancer address>;`
  and `real_ip_header X-Forwarded-For;`), so `$remote_addr` below is the client, not the balancer.

nginx:

```nginx
proxy_set_header X-Forwarded-For  $remote_addr;
proxy_set_header X-Real-IP        $remote_addr;
proxy_set_header CF-Connecting-IP "";
```

Caddy:

```caddyfile
reverse_proxy 127.0.0.1:8080 {
	header_up X-Forwarded-For {client_ip}
	header_up X-Real-IP {client_ip}
	header_up -CF-Connecting-IP
}
```

## Sign-in limits

Failed sign-ins are limited, for the console (`/rest/public/auth/login`), API clients (`/rest/public/jwt/login`) and
the current-password check of a password or e-mail change (`/rest/private/users`), all together. A refused attempt
isn't checked, even with the right password. Every refusal looks the same, whichever limit refused: the console says
"Too many failed sign-ins"; the API answers `429` with `Retry-After: 900` (the 15-minute window; try again then).

Each attempt is counted under the first of these that applies:

- **Known browser.** Every completed sign-in, console or API, sets a cookie (`MDMESH_SIGNIN`, HttpOnly,
  SameSite=Strict, sent only to `/rest/public`, 30 days, Secure when `BASE_URL` is https) that names the account. A
  browser or API client that sends it back has its own limit: 10 failures in 15 minutes.
- **Known address.** An address that completed a sign-in to the account in the last 30 days (up to 16 per account)
  has its own limit for that account: 10 failures in 15 minutes. These failures don't count toward the account's
  limit below; they come on top of it. So someone at an address you signed in from (a shared office NAT, say) gets 10
  guesses per 15 minutes in addition to the account's 20: at most 20 + 10 per known address, 180 with all 16.
- **Anything else.** Per address: 10 failures in 15 minutes, and 50 in 6 hours, across all accounts. Per account: 20
  failures in 15 minutes, from all addresses together. While an account is over that limit, new browsers on new
  addresses are refused for it, even with the right password.

So someone failing sign-ins for your account locks out only browsers and addresses new to it. Your browser, your
office or home, and the host your API client runs on keep signing in. A sign-in that names no account is limited the
same way as one that does, so a refusal doesn't tell whether the account exists.

An IPv6 address counts by its /56, so a household or a small site is one address and shares its limit. The limits
are held in memory, at most 20,000 addresses, accounts, browsers and known addresses each. A flood of new addresses
past that shares one coarse limit instead of being let through, so during such a flood new addresses can be refused
more often, never less. Known browsers and known addresses aren't affected. Logins that name no account share 4,096
counters, so a flood of made-up names can't fill memory or lock out a real account.

Every attempt, refused or not, first looks the login up in the database. The limit doesn't shield the database from a
flood of requests; a rate limit at your proxy does.

### During an attack

- Sign in from a browser or an address you signed in from before. Each limit lifts by itself as failures age out of
  its window: a new browser on a new address waits until the attack has paused for 15 minutes.
- A restart clears every limit at once (`docker compose restart server`; native: `systemctl restart mdmesh-server`).
  It also forgets the known addresses, which become known again at their next sign-in. Known-browser cookies stay
  valid.
- API clients: keep the `MDMESH_SIGNIN` cookie from a sign-in and send it with the next one (for example
  `curl -c jar -b jar`), or sign in from the same host each time.

### The key that signs the cookies

The cookies are signed with a key derived from the API signing key (`jwt.secretkey`, see
[API signing key](#api-signing-key-jwt)) with HMAC and a fixed label. They stay valid across restarts and upgrades.
Changing the API signing key (a new `SERVER_JWT_SECRET`, `docker compose down -v`, or a new `jwt.secretkey`) makes every
cookie invalid, as it signs out every API client. A server with no `jwt.secretkey` of at least 32 characters (a dev
setup from source) uses a key of its own process, logs a warning, and forgets every browser when it restarts.

### The client address

The address is the one your proxy reports in `X-Forwarded-For` (its last entry), never `CF-Connecting-IP`:

- **Docker:** Caddy's view of the client, in every hosting mode.
- **Native:** believed only from a proxy on loopback (the same host, not in a container), or one whose address you
  list in `proxy.addresses`: run the installer with `PROXY_ADDRESSES=192.168.1.2` (comma-separated for several), or
  edit `<Parameter name="proxy.addresses" value="192.168.1.2"/>` in `ROOT.xml` and restart the server. Installer
  re-runs keep it. Write each address as Tomcat reports it: an IPv4 address as usual, an IPv6 one in full, uncompressed
  form (`2001:db8:0:0:0:0:0:2`); no ranges. Any other client is limited by its own address, whatever it sends: on a
  LAN-only install with no proxy, each browser on the LAN counts on its own.

Two setups make every client look alike. The server logs a warning the first time it sees each one:

- **A proxy that isn't listed** in `proxy.addresses`: on another host, or in a container on this one. This is the
  expected effect of a missing entry: the server takes the proxy for a single client, so every client behind it
  shares the proxy's address limit, and 10 failed sign-ins from anyone refuse every new browser for 15 minutes
  (longer once there have been 50 in 6 hours). Browsers and addresses that signed in before keep signing in. The log names each such
  peer: `A sign-in from <proxy> carried X-Forwarded-For, but <proxy> is not a trusted proxy`. Add that address to
  `proxy.addresses` and restart the server. The server doesn't guess that a private peer sending the header is a
  proxy: any LAN client can send one, and it would then pick its own address.
- **A proxy that sends no `X-Forwarded-For`.** The server can't tell the clients behind it apart and limits by account
  only. Set the header as in [Device public address](#device-public-address).

**A CDN or another proxy in front of Docker's Caddy** (Cloudflare's proxy in own-domain mode, a load balancer): Caddy
believes `X-Forwarded-For` only from the addresses in `TRUSTED_PROXIES`, by default none. Without them, Caddy reports
the CDN as the client, and every client shares one address. Add the CDN's ranges, space-separated, to `.env` in the
install directory, then run `docker compose up -d caddy`:

```bash
TRUSTED_PROXIES="173.245.48.0/20 103.21.244.0/22 2400:cb00::/32"
```

That line is an example: for Cloudflare, list every range on <https://www.cloudflare.com/ips/>, and update the list
when Cloudflare changes it. The compose file passes `TRUSTED_PROXIES` from `.env` to the `caddy` service from v0.3.9
on. Updates don't change your compose file, so on an install set up before v0.3.9, check with
`grep TRUSTED_PROXIES docker-compose.yml`: if nothing is found, add this line under `caddy:` → `environment:`,
indented like the lines there:

```yaml
      TRUSTED_PROXIES: ${TRUSTED_PROXIES:-}
```

The Cloudflare tunnel mode needs none of this: Caddy reads cloudflared's `CF-Connecting-IP` there.

**IPv6 clients of a Docker own-domain install.** Docker's networks are IPv4-only by default. A client that connects
to the host over IPv6 then reaches Caddy through Docker's port proxy, from the network's gateway address (such as
`172.18.0.1`), so Caddy and the server see every IPv6 client as that one address, and all of them share one address
limit. Nothing logs this. If your domain has an AAAA record, either turn on IPv6 for the stack's network
(Docker's [IPv6 guide](https://docs.docker.com/engine/daemon/ipv6/)), or remove the AAAA record so clients use IPv4.
The Cloudflare tunnel mode is not affected.

## Backups and restore

**Docker.** One-click updates dump the database first, and a rollback restores that dump
([Upgrading](UPGRADING.md#recovery-and-rollback)). `./setup.sh` takes no dump, so before a from-source upgrade make
one yourself:

```bash
docker compose exec -T postgres pg_dump -U mdmesh -Fc mdmesh > mdmesh-pre-upgrade.dump
```

To go back to it, stop the server, restore, then build the release the dump was taken on:

```bash
docker compose stop server
docker compose exec -T postgres pg_restore -U mdmesh -d mdmesh -c --if-exists < mdmesh-pre-upgrade.dump
git checkout vX.Y.Z && ./setup.sh      # the release you upgraded from
```

With a registry `IMAGE_OWNER`, add `--allow-downgrade` to that `./setup.sh`.

**Native.** The installer writes a dump to `/opt/mdmesh/backups/` before each upgrade. The uninstaller writes one to
`/root`. Restore **as the `mdmesh` role, never as `postgres`**: a superuser restore runs any function defined in the
dump, so a tampered database could gain superuser rights. With the install present, as root:

```bash
PGPASSWORD="$(setpriv --reuid=mdmesh --regid=mdmesh --init-groups \
  sed -n 's/.*name="JDBC.password" value="\([^"]*\)".*/\1/p' \
  /opt/mdmesh-tc/conf/Catalina/localhost/ROOT.xml | head -n1)" \
  pg_restore -h 127.0.0.1 -p 5432 -U mdmesh -d mdmesh -c --if-exists <dump-file>
```

The installer and uninstaller print this command next to each dump they write. To restore an uninstall dump, install
again first.

## Removing a customer

A single-customer install has no super-admin, so the console can't delete a customer. To remove one, for example one
the self-signup API made before 0.3.8 ([Upgrading](UPGRADING.md#customers-you-didnt-create)), follow these steps
once per customer. The SQL deletes the customer with its users, devices, configurations, applications, groups,
uploaded-file records, settings and plugin data, and the agent data of its devices. It runs in one transaction, so if
any statement fails, nothing changes. It refuses customer 1. It keeps the customer's rows in `plugin_audit_log`, a
record of what the account did that nothing else reads.

1. Dump the database ([Backups and restore](#backups-and-restore)). Then, in the install directory (Docker) or as
   root (native), set up the `psql` command, which runs as the `mdmesh` role:

   ```bash
   PSQL=(docker compose exec -T postgres psql -X -U mdmesh -d mdmesh)              # Docker
   export PGPASSWORD="$(setpriv --reuid=mdmesh --regid=mdmesh --init-groups \
     sed -n 's/.*name="JDBC.password" value="\([^"]*\)".*/\1/p' \
     /opt/mdmesh-tc/conf/Catalina/localhost/ROOT.xml | head -n1)"                  # native: these two lines
   PSQL=(psql -h 127.0.0.1 -p 5432 -U mdmesh -d mdmesh -X)
   ```

2. Find the customer's `id`:

   ```bash
   "${PSQL[@]}" -c "SELECT c.id, c.name, c.email, to_timestamp(c.registrationTime / 1000) AS created,
     to_timestamp(c.lastLoginTime / 1000) AS last_sign_in,
     (SELECT string_agg(u.login, ', ') FROM users u WHERE u.customerId = c.id) AS logins,
     (SELECT count(*) FROM devices d WHERE d.customerId = c.id) AS devices
     FROM customers c WHERE c.id > 1 ORDER BY c.id"
   ```

   To see what the account did (here customer 5):
   `"${PSQL[@]}" -c "SELECT to_timestamp(createTime / 1000), login, action, ipAddress FROM plugin_audit_log WHERE customerId = 5 ORDER BY createTime"`.
3. Save this as `remove-customer.sql`:

   ```sql
   SELECT count(*) = 1 AS found FROM customers WHERE id = :cid AND id > 1 AND NOT master \gset
   \if :found
   SELECT filesDir AS files_dir FROM customers WHERE id = :cid
     AND filesDir ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
   CREATE TEMP TABLE gone ON COMMIT DROP AS SELECT number FROM devices WHERE customerId = :cid;
   DELETE FROM agentCommand WHERE deviceNumber IN (SELECT number FROM gone);
   DELETE FROM device_state WHERE deviceNumber IN (SELECT number FROM gone);
   DELETE FROM device_event WHERE deviceNumber IN (SELECT number FROM gone);
   DELETE FROM device_location WHERE deviceNumber IN (SELECT number FROM gone);
   DELETE FROM agentRolloutCanary WHERE rolloutId IN (SELECT id FROM agentRollout WHERE customerId = :cid);
   DELETE FROM agentRollout WHERE customerId = :cid;
   DELETE FROM agentEnrollmentToken WHERE customerId = :cid;
   DELETE FROM configurations WHERE customerId = :cid;
   DELETE FROM customers WHERE id = :cid AND id > 1 AND NOT master;
   \else
   \echo 'No customer with that id above 1: nothing changed.'
   \endif
   ```

   The agent tables and enrollment tokens name devices and customers without a foreign key, so they go first.
   Configurations go before the customer because they point at its application versions with `ON DELETE RESTRICT`.
   Deleting the customer removes the rest through `ON DELETE CASCADE`.
4. Stop the server (`docker compose stop server`, or `systemctl stop mdmesh-server`), then run the file with the
   customer's id:

   ```bash
   "${PSQL[@]}" -v ON_ERROR_STOP=1 --single-transaction -v cid=5 < remove-customer.sql
   ```

5. Start the server again: `docker compose start server`, or `systemctl start mdmesh-server`.
6. If step 4 printed a `files_dir`, delete that directory. `${DIR:?}` refuses an empty name, which would delete every
   customer's files:

   ```bash
   DIR=<files_dir>
   docker compose exec -T server rm -r -- "/opt/mdmesh/files/${DIR:?}"     # native: rm -r -- "/opt/mdmesh/files/${DIR:?}"
   ```

The removed customer's users are signed out, since every request re-reads its user. Once no customer above id 1 is
left, the server logs no `SECURITY:` lines about customers at startup, and the install's org admin can update again.

## Moving to a new BASE_URL

Enrolled devices keep polling the old URL until you factory-reset or re-provision them. So keep the old address
reachable (or point its DNS at the new host). Library APKs keep the address they were uploaded under, and agents from
v0.3.2 on download from exactly that address. After a move, re-upload them.

## Uninstalling

### Docker (`setup.sh` or the quick start)

```bash
docker compose exec -T postgres pg_dump -U mdmesh -Fc mdmesh > mdmesh-final.dump
docker compose down -v --remove-orphans     # stops containers and DELETES the volumes (database, uploads, certs, backups)
rm -f .env                                  # secrets; the directory itself can go too for a quick-start install
docker image rm $(docker image ls 'ghcr.io/mdmesh-app/mdmesh-*' -q) 2>/dev/null   # optional: free the images
```

To keep the data volumes instead, run `docker compose down` without `-v`. A later `./setup.sh` picks up where you
left off.

### Native

```bash
sudo ./install/uninstall-native.sh
```

It lists what it will remove and writes a final dump to `/root`. It waits until you type `UNINSTALL`. It removes:

- `/opt/mdmesh-tc`, `/opt/mdmesh` and `/opt/mdmesh-runtime`;
- the `mdmesh-server` and `mdmesh-supervisor` units, and `/etc/mdmesh`;
- the `mdmesh` user, the install log, and the `mdmesh` database and role.

It never touches apt packages, your proxy or the git checkout.

- `--keep-data` keeps the database, `/opt/mdmesh/files`, `/opt/mdmesh/backups` and the `mdmesh` user.
- `-y` skips the prompt.
- `--no-backup` skips the dump. You need it if `ROOT.xml` is gone or unreadable (for example, a pre-0.2.9 install
  that ran Tomcat as root), because the dump is taken as the `mdmesh` role.

## Security notes

- Each install generates its own secrets. `.env` is mode 600.
- Traffic uses TLS: on Docker through Cloudflare or Caddy, on native through your own proxy. Docker never publishes
  the database and server ports. The agent speaks HTTPS only.
- The admin must set a new password at first sign-in. A database the server created without an installer run has an
  admin no password works for, until an installer (or `scripts/dev-seed.sh`) sets one.
- The Headwind launcher endpoints are off by default (404). MQTT push is off; agents are woken over a WebSocket.
- The supervisor mounts the Docker socket, so it is trusted. It acts only on minisign-verified manifests and on
  authorized callers (the recovery token, or the session of an admin who may update: a super-admin, or the org admin
  of a single-customer install). Apply and rollback only recreate `server` and `caddy`.
- Setting a password ends that account's other sessions and API tokens. Password reset links work once, for 24 hours.
- The console session cookie is `SameSite=Lax`, and `Secure` when `BASE_URL` is https.
- Failed sign-ins are limited per address and per account ([Sign-in limits](#sign-in-limits)).
- Responses carry no-framing, `nosniff`, `Referrer-Policy` and, on https, HSTS headers. A request path with a `;` or
  an encoded separator or dot is refused (400).

### Native installs

- Tomcat and the supervisor run as the unprivileged `mdmesh` user, which can read `GITHUB_TOKEN`
  ([use a read-only one](#updater-supervisor)).
- The installer and uninstaller use the `postgres` superuser only to create, alter or drop the role and database.
  All other database work runs as the `mdmesh` role. Your shell's `PGHOST` and similar variables are ignored.
- The installer stops if the `mdmesh` user has crontab or `at` jobs. Inspect them (`crontab -l -u mdmesh`, `atq`),
  remove them (`crontab -r -u mdmesh`, `atrm <id>`), then re-run.

### API signing key (JWT)

This key signs tokens for REST API clients (`/rest/public/jwt/login`); the console uses a session cookie. It is
generated once and kept, so tokens survive restarts and upgrades. The sign-in limit's known-browser cookies are signed
with a key derived from it, so changing it also makes every browser and API client new to that limit
([Sign-in limits](#sign-in-limits)).

A token sign-in also sets the `MDMESH_SIGNIN` cookie. An API client that keeps cookies and sends it with its next
sign-in keeps signing in while someone else's failed sign-ins limit the account.

- **Docker:** the key is kept in the server's data volume at `/opt/mdmesh/jwt.secret`, so `docker compose down -v`
  deletes it and API clients have to sign in again. To pin a key, set
  `SERVER_JWT_SECRET=<output of openssl rand -hex 64>` in `.env`; it takes priority over the file. A quick-start
  install made before v0.3.2 also needs `JWT_SECRET: ${SERVER_JWT_SECRET:-}` under `server:` → `environment:` in its
  `docker-compose.yml`.
- **Native:** the key is `jwt.secretkey` in `ROOT.xml`, kept across re-runs.
- The key must be hex, a multiple of 4 characters, and at least 128 long. The Docker server refuses to start with any
  other `SERVER_JWT_SECRET`, and the native installer replaces an invalid `jwt.secretkey`.
