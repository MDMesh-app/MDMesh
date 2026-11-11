# Releasing MDMesh

<sub>[← README](README.md) · [Deploy](DEPLOY.md) · [Upgrading](docs/UPGRADING.md) · [Operations](docs/OPERATIONS.md) · [Structure](STRUCTURE.md) · [Contributing](CONTRIBUTING.md) · **Releasing**</sub>

## Release cycle

- **Feature releases (0.x.0) every three weeks.** Everything merged to `main` since the last one ships together.
- **Patch releases (0.x.y) only for a security fix or something broken in the field.** The notes say why it
  couldn't wait. See [Patch releases](#patch-releases).
- **Freeze about three days before a feature release.** Only fixes merge to `main` until the release PR lands.
- **[CHANGELOG.md](CHANGELOG.md) is the release notes.** Each PR adds its line under Unreleased. A version's section
  is its tag annotation and its GitHub release notes, word for word; the workflow refuses a tag whose annotation
  differs.

1. **Freeze and release PR.** Branch `release/vX.Y.Z` from `main` and open a PR. Rebase it onto any fix that
   lands during the freeze.
2. **Move Unreleased to `## vX.Y.Z — YYYY-MM-DD`** in CHANGELOG.md, with a new empty `## Unreleased` above it.
   Remove every `<!-- pending … -->` marker, and the whole line if its PR didn't merge; the script refuses a released
   section with any HTML comment left. Sum up the Dependabot bumps in Under the hood. Preview it with
   `release/changelog-section.sh X.Y.Z`.
3. **Review and CI.** The PR's code review, green CI, and the native install matrix on the release branch, all nine
   matrix jobs, 3 distros × 3 scenarios (T2; see "T2 native rig" in [docs/DEV.md](docs/DEV.md)). Pull requests run
   T2 only when the installers change, so this is what catches a server change that breaks a native upgrade:
   `gh workflow run t2-native.yml --ref release/vX.Y.Z`
4. **The owner's phone check** of the release branch, and the owner's go.
5. **Squash-merge** the release PR as `vX.Y.Z (#N)`.
6. **Tag the merge commit from its changelog section and push the tag.** Tag that commit explicitly, not whatever
   `main` is by now:

   ```bash
   git fetch origin
   sha=$(gh pr view N -R MDMesh-app/MDMesh --json mergeCommit -q .mergeCommit.oid)
   git switch --detach "$sha" && git log -1 --format=%s     # must print: vX.Y.Z (#N)
   n=$(release/changelog-section.sh X.Y.Z) && git tag -a vX.Y.Z --cleanup=whitespace -m "$n" "$sha"
   release/changelog-section.sh --check vX.Y.Z "$sha" && git push origin vX.Y.Z
   ```

   `n=$(…) &&` stops if the script fails (`git tag -F <(…)` would make an empty tag instead), and
   `--cleanup=whitespace` keeps lines that start with `#`. Then approve the `release` environment's deployment once
   the `gate` job is green (setup step 4).
7. **Verify the published release** ([below](#verifying-a-published-release)): asset SHA-256 against the
   manifest, the minisign signature, the APK's signing certificate, its versionCode, `quickstart.sh`, and the GHCR
   images.

### Patch releases

Patches are tagged from `main` for now, like every release (the `gate` job accepts nothing else).

- A patch ships all of `main`, so its section is all of Unreleased and it goes through the full checklist, without
  the three-day freeze.
- If `main` already holds new features, the release is 0.(x+1).0 instead of a patch. An early 0.(x+1).0 restarts
  the three-week clock.
- If an urgent fix ever has to skip unreleased work on `main`, the path is a protected `maint/v0.X` branch cut from
  the last tag: the fix and its section are cherry-picked there, tagged, and the section merged back to `main`. The
  `gate` job would have to accept that branch first; it doesn't today.

### Security fixes

A fix for a vulnerability that isn't public yet is developed in a GitHub private security advisory (Security →
Advisories → New draft advisory → Start a temporary private fork), not in a public PR. Its changelog line says what
operators see, without the details.

The fix is public from the moment it merges, so keep that window short:

- Merge it from the private fork only when the release is otherwise ready (reviewed, Unreleased final).
- Then rebase the release PR onto it, run T2 and the phone check, and tag the same day.
- An advisory merge may skip `main`'s required checks, and whether CI runs in the temporary private fork depends on
  GitHub (check GitHub's docs). Treat CI on the release PR as the only CI the fix gets.
- Publish the advisory once the release is out. See [SECURITY.md](SECURITY.md) for reporting.

## What the release workflow does

The tag must point at a commit on `main` (its first-parent history), be newer than every earlier release, and carry
the tagged commit's CHANGELOG.md section as its annotation (`release/changelog-section.sh --check vX.Y.Z`, which
you can also run locally): the workflow's `gate` job checks that first, and the `release` job (the only one with the
signing secrets) waits for a reviewer's approval in the `release` environment (setup step 4). The release job runs the
changelog check again when it starts and just before it publishes, and fails if the tag no longer points at the
commit it checked out.

`.github/workflows/release.yml` then: runs the agent unit tests + builds the **signed** release APK,
builds & pushes the **server**, **web**, and **supervisor** images to GHCR as `:VERSION`, reads back each image's
digest (`release/image-digest.sh`), builds a **minisign-signed manifest**, and publishes a **GitHub Release** with
`mdmesh-agent.apk`, `manifest.json`, `manifest.json.minisig` and `quickstart.sh` (the tagged commit's copy, which the
README's install command downloads from the latest release). Before the upload it checks that `quickstart.sh` is the
file the signed manifest hashes, and after it reads the published asset back and checks it again. The fleet auto-updater consumes that signed manifest
to apply/roll-out updates.

The manifest (`release/build-manifest.sh`) keeps `components.serverImage`/`webImage` (the `:VERSION` tags, which
supervisors before 0.5.0 read) and adds `components.images`, the three images as `repo@sha256:…`, and `files`, the
SHA-256 of each file the quick start downloads from the tag and of `quickstart.sh` itself (the release asset). A 0.5.0+ supervisor and the quick start run exactly those
digests and refuse a manifest without them, so a fork that publishes its own releases must use this workflow (or write
the same fields). `scripts/release-manifest-check.sh` (T0) checks the manifest against what its readers expect.

`:latest` moves **last**: only after the anonymous-pull check, the manifest signing and the GitHub Release
have all succeeded does the final step retag each image's `:latest` to the release's `:VERSION` (same
digest, registry-side, no rebuild), then verify all three digests. It first checks that each `:VERSION` is still the
digest the signed manifest pins. A failure anywhere earlier leaves
`:latest` — and so every quick-start install — on the previous release.

**If a changelog check fails**, re-tag. In the `gate` job or at the start of the `release` job nothing was built or
published; just before publishing (the tag changed during the run) the `:VERSION` images are pushed but there is no
Release, and the new tag's run pushes them again. Delete the tag and tag again from the changelog (deleting a `v*` tag
needs the tag ruleset's bypass). Take the commit from the release PR's merge commit, not from the tag, which may be
the stale or the moved one. If the section itself is wrong, fix it in a PR to `main` first and tag that PR's merge
commit instead.

```bash
git fetch origin
sha=$(gh pr view N -R MDMesh-app/MDMesh --json mergeCommit -q .mergeCommit.oid)   # the release PR, as in step 6
git switch --detach "$sha" && git log -1 --format=%s     # must print: vX.Y.Z (#N)
n=$(release/changelog-section.sh X.Y.Z) && git tag -f -a vX.Y.Z --cleanup=whitespace -m "$n" "$sha"
release/changelog-section.sh --check vX.Y.Z "$sha" && git push origin :refs/tags/vX.Y.Z && git push origin vX.Y.Z
```

**If "Published quickstart.sh matches the signed manifest" fails**, the published `quickstart.sh` asset was changed
during the run: the checked install refuses it (the shortcut runs the tag's copy, not the asset). Upload the tag's
own copy over it
(`gh release upload vX.Y.Z quickstart.sh --clobber -R MDMesh-app/MDMesh` from the tagged commit, then check its
SHA-256 against the manifest) and find out who changed it; then move `:latest` by hand as below.

**If the job goes red after the GitHub Release step** (the quickstart.sh readback, or "Move :latest to this release"): the
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
password manager / HSM, and add them as **environment secrets of the `release` environment** (Settings →
Environments → `release` → Environment secrets; see step 4), not as repository secrets.

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
manifest **anonymously** and fails the release with instructions if any package is still private.

### 4. Release environment and tag protection
The release job runs in the `release` environment, so the repo settings decide who can publish:

- **Settings → Environments → New environment `release`:**
  - **Required reviewers:** at least one maintainer; tick **Prevent self-review**. Before approving, the reviewer
    opens the run and confirms the `gate` job ran and passed (tag on main's first-parent history, version newer than
    every earlier release, annotation matches CHANGELOG.md) and that the tag is the one they expect.
  - **Deployment branches and tags:** *Selected branches and tags* → add a **tag** rule `v*`.
  - **Environment secrets:** the six secrets above (`MDM_RELEASE_STORE_B64`, `MDM_RELEASE_STORE_PASSWORD`,
    `MDM_RELEASE_KEY_ALIAS`, `MDM_RELEASE_KEY_PASSWORD`, `MINISIGN_SECRET_KEY`, `MINISIGN_PASSWORD`). Then delete any
    repository-level copies (Settings → Secrets and variables → Actions → Repository secrets).
- **Settings → Rules → Rulesets → New ruleset → New tag ruleset.** It is what keeps a release tag, and so what the
  quick start's unchecked shortcut runs, from being moved. Exactly:
  - **Ruleset name:** any, e.g. `v* ruleset`; **Enforcement status:** *Active*.
  - **Bypass list:** add the release maintainers only (or a team of them), mode *Always allow*. Not *Repository admin*
    as a role unless every admin is a release maintainer, and no apps or deploy keys.
  - **Target tags → Add target → Include by pattern:** `v*`.
  - **Rules:** tick **Restrict creations**, **Restrict updates**, **Restrict deletions** and **Block force pushes**;
    leave the others off.
  - Save, then check it: `gh api repos/MDMesh-app/MDMesh/rulesets` lists it with `"enforcement": "active"` and
    `"target": "tag"`. Re-tagging after a failed changelog check (above) needs a bypass actor.
- **Settings → Rules → Rulesets** (or branch protection) for `main`: require pull requests and the CI checks, and
  block force pushes, so "on `main`" means reviewed.

## How a deployment trusts a release
The updater fetches the GitHub Release, verifies `manifest.json` against the baked
`release/minisign.pub`, then checks each artifact's SHA-256 against the manifest. A 0.5.0+ supervisor pulls the server
and web images by the digests it pins, and pins its own image the same way for its next start (an older supervisor
pulls the `:VERSION` tags). A release published without the private minisign key fails verification and is
refused, so a hijacked repo/account or a moved image tag alone can't push code to the fleet.

**What runs `quickstart.sh`, and what protects it.** Anyone with write access to the repository can replace a release
asset, without a PR or review. So the README's install block checks the asset before running it: the manifest's
minisign signature against the release key, that the manifest is the latest release's, and the script's SHA-256 in
its `files`; only the private minisign key can make a script pass that. The README's unchecked shortcut instead takes the
latest release's tag from GitHub's `/releases/latest` redirect, accepts it only if it is `/releases/tag/vX.Y.Z` (and
stops otherwise), and runs `quickstart.sh` from `refs/tags/vX.Y.Z` on raw.githubusercontent.com: the full ref, so a
branch with the same name can't stand in for the tag. What it runs is therefore the commit that tag points at, and the
`v*` tag ruleset ([setup step 4](#4-release-environment-and-tag-protection)) is what stops anyone outside its bypass
list from creating, moving or deleting a `v*` tag. It does not protect against a bypass actor. Neither path stops a writer who
marks an older release as latest: that release is genuine and signed, so both install it.
Without the ruleset, anyone with write access could repoint a tag.

The quick start verifies the same signature with `openssl` against a copy of the key line in `quickstart.sh`, then
the image digests and the downloaded files' SHA-256. README.md and DEPLOY.md carry the same key in their install
block (`-P`). T0 (`scripts/release-manifest-check.sh`) fails if any of the three differs from `release/minisign.pub`.
**If you ever rotate the minisign key, update all three copies (quickstart.sh, README.md, DEPLOY.md) in the same
PR.**

## Verifying a published release

Needs `gh`, `minisign`, the Android build-tools (`apksigner`, `aapt2`) and `docker buildx`:

```bash
V=1.2.3; d=$(mktemp -d); gh release download "v$V" -R MDMesh-app/MDMesh -D "$d"
release/verify-manifest.sh "$d/manifest.json"     # minisign: signed by the release key
python3 -c 'import json,sys; a=json.load(open(sys.argv[1]))["components"]["apk"]; print(a["sha256"], a["versionCode"], a["signatureChecksum"])' "$d/manifest.json"
sha256sum "$d/mdmesh-agent.apk"                   # = the manifest's sha256
release/apk-checksum.sh "$d/mdmesh-agent.apk"     # = its signatureChecksum, and the same as every earlier release
release/version.sh "$V"                           # = its versionCode, and the APK's:
aapt2 dump badging "$d/mdmesh-agent.apk" | grep -o "versionCode='[0-9]*'"
for img in mdmesh-server mdmesh-web mdmesh-supervisor; do   # :V and :latest print the same digest
  for t in "$V" latest; do docker buildx imagetools inspect --format "$img:$t {{.Manifest.Digest}}{{println}}" "ghcr.io/mdmesh-app/$img:$t"; done
done
python3 -c 'import json,sys; print(*json.load(open(sys.argv[1]))["components"]["images"].values(), sep="\n")' "$d/manifest.json"   # = those digests
python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["files"]["quickstart.sh"])' "$d/manifest.json"
sha256sum "$d/quickstart.sh"                      # = that, and the same as quickstart.sh at the tag
```

`components.images`, `files` and the `quickstart.sh` asset exist from 0.5.0 on; a manifest from an earlier release has
only `components.serverImage`/`webImage` (the `:VERSION` tags) and `components.apk`.
