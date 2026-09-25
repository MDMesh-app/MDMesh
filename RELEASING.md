# Releasing MDMesh

<sub>[← README](README.md) · [Deploy](DEPLOY.md) · [Structure](STRUCTURE.md) · [Contributing](CONTRIBUTING.md) · **Releasing**</sub>

Cutting a release is one command — push a semver tag:

```bash
git tag v1.2.3 && git push --tags
```

`.github/workflows/release.yml` then: runs the agent unit tests + builds the **signed** release APK,
builds & pushes the **server**, **web**, and **supervisor** images to GHCR as `:VERSION`, builds a
**minisign-signed manifest**, and publishes a **GitHub Release** with `mdmesh-agent.apk`, `manifest.json`,
and `manifest.json.minisig`. The fleet auto-updater consumes that signed manifest to apply/roll-out updates.

`:latest` moves **last**: only after the anonymous-pull check, the manifest signing and the GitHub Release
have all succeeded does the final step retag each image's `:latest` to the release's `:VERSION` (same
digest, registry-side, no rebuild), then verify all three digests. A failure anywhere earlier leaves
`:latest` — and so every quick-start install — on the previous release.

**If the job goes red after the GitHub Release step** (i.e. in "Move :latest to this release"): the
Release and its `:VERSION` images are published and fine, but `:latest` may point at the previous release
for some or all of the three images. Don't re-run the whole job (it would rebuild and re-push `:VERSION`
and try to recreate the Release). Fix `:latest` by hand, logged in to GHCR with `write:packages`:

```bash
V=1.2.3                   # edit: the release version, no leading v
O=owner-lowercase-here    # edit: the GitHub owner, lowercased
for img in mdmesh-server mdmesh-web mdmesh-supervisor; do
  docker buildx imagetools create --prefer-index=false --tag "ghcr.io/$O/$img:latest" "ghcr.io/$O/$img:$V"
done
# verify: each pair must print the same digest
for img in mdmesh-server mdmesh-web mdmesh-supervisor; do
  for t in "$V" latest; do docker buildx imagetools inspect --format "$img:$t {{.Manifest.Digest}}{{println}}" "ghcr.io/$O/$img:$t"; done
done
```

`--prefer-index=false` matters: without it buildx wraps the image in a new index and `:latest` gets a
different digest from `:VERSION`.

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
`docker login ghcr.io` with a PAT.) The release workflow verifies this: after pushing, it fetches each image
manifest **anonymously** and fails the release with instructions if any package is still private. Release notes
come from the annotated tag message (`git tag -a vX.Y.Z -m "..."`) plus GitHub's generated list.

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
