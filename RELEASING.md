# Releasing MDMesh

<sub>[← README](README.md) · [Deploy](DEPLOY.md) · [Structure](STRUCTURE.md) · [Contributing](CONTRIBUTING.md) · **Releasing**</sub>

Cutting a release is one command — push a semver tag:

```bash
git tag v1.2.3 && git push --tags
```

`.github/workflows/release.yml` then: runs the agent unit tests + builds the **signed** release APK,
builds & pushes the **server**, **web**, and **supervisor** images to GHCR (each tagged both
`:VERSION` and `:latest`), builds a **minisign-signed manifest**, and publishes a **GitHub Release**
with `mdmesh-agent.apk`, `manifest.json`, and `manifest.json.minisig`. The fleet auto-updater consumes
that signed manifest to apply/roll-out updates.

Tags must be strict `vMAJOR.MINOR.PATCH`; the workflow rejects anything else. versionCode is derived
`major*10000 + minor*100 + patch` (monotonic).

## One-time setup (you own the keys — do this offline)

These two key sets are **custody-critical**. Generate them on a secure machine, store backups in a
password manager / HSM, and add them as repo secrets (Settings → Secrets and variables → Actions).

### 1. APK release keystore — NEVER ROTATE
Android ties the **Device-Owner** relationship to the APK's signing certificate. If the signing key
changes, OTA updates are rejected *and* every enrolled device must be factory-reset. So this key is
generated **once** and used **forever**.

```bash
keytool -genkeypair -v -keystore mdmesh-release.jks -alias mdmesh \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 mdmesh-release.jks   # value for the MDM_RELEASE_STORE_B64 secret
```

Secrets: `MDM_RELEASE_STORE_B64` (the base64), `MDM_RELEASE_STORE_PASSWORD`, `MDM_RELEASE_KEY_ALIAS`
(`mdmesh`), `MDM_RELEASE_KEY_PASSWORD`.

### 2. Manifest signing key (minisign)
Establishes release trust: deployments verify the manifest against the committed public key and
reject anything unsigned/tampered.

```bash
minisign -G -p release/minisign.pub -s mdmesh-release.key   # set a password
```

- **Commit** the generated `release/minisign.pub` (replace the placeholder in the repo).
- Secrets: `MINISIGN_SECRET_KEY` = the full contents of `mdmesh-release.key`; `MINISIGN_PASSWORD` =
  its password.
- No `minisign` binary? Generate it in a container:
  `docker run --rm -it -v "$PWD:/keys" -w /keys alpine sh -c 'apk add --no-cache minisign && minisign -G -p minisign.pub -s minisign.key'`

### 3. Make the GHCR packages public (one-time, after the first release)
Images pushed by Actions to an org are **private by default**. For the no-clone `docker compose pull`
to work anonymously, set each package to public: **org → Packages → `mdmesh-server` / `mdmesh-web` /
`mdmesh-supervisor` → Package settings → Change visibility → Public.** (Otherwise deployers must
`docker login ghcr.io` with a PAT.)

## How a deployment trusts a release
The updater fetches the GitHub Release, verifies `manifest.json` against the baked
`release/minisign.pub`, then checks each artifact's SHA-256 against the manifest. A release published
without the private minisign key fails verification and is refused — so a hijacked repo/account alone
can't push code to the fleet.

## Verifying locally
```bash
release/version.sh 1.2.3            # → 10203
release/apk-checksum.sh some.apk    # → provisioning signature checksum
release/verify-manifest.sh manifest.json   # needs minisign + the real public key
```
