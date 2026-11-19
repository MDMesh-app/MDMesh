#!/usr/bin/env bash
# The signed manifest's contract, end to end, without a registry: release/build-manifest.sh writes it, minisign signs it
# (a throwaway key), and then
#   - quickstart.sh's own verification functions (the "release verification" block, run as they are) accept it, and
#     refuse it tampered with or signed by another key;
#   - they read the version, the three image digests and each downloaded file's SHA-256 from it;
#   - supervisor/lib.js imagePins (what apply.sh is handed) reads the same three refs;
#   - release/image-digest.sh pins an index only when it holds one image per platform asked for (docker stubbed);
#   - the fields older supervisors and the installers read (components.serverImage/webImage/apk) are unchanged;
#   - quickstart.sh's copy of the release key and its file list match release/minisign.pub and build-manifest.sh;
#   - securityReleases is CHANGELOG.md's list (release/security-releases.py: a version with a Security heading), the
#     release workflow's check refuses a manifest whose list differs, and the rule itself holds on a fixture changelog;
#   - the manifest records quickstart.sh's own SHA-256, and release.yml publishes quickstart.sh as a release asset;
#   - DEPLOY.md carries the one install block and shortcut, with release/minisign.pub's key, and the manifest's
#     version line is what that block checks; README.md runs the latest release's quickstart.sh asset, never main's.
# Needs bash, python3, node, openssl and minisign. Run from anywhere: scripts/release-manifest-check.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
cleanup() {
  rm -f -- "$T/manifest.json" "$T/manifest.json.minisig" "$T/k.pub" "$T/k.key" "$T/x.pub" "$T/x.key" \
    "$T/agent.apk" "$T/tampered.json" "$T/qs.sh" "$T/changelog.md" "$T/sec.json" "$T/sec.out" "$T/inspect.json" \
    "$T/bin/docker" "$T/old.json" "$T/rc/apk-checksum.sh" "$T/rc/agent-signature-checksum"
  rmdir -- "$T/bin" "$T/rc" "$T" 2>/dev/null || true
}
trap cleanup EXIT
fail() { echo "release-manifest-check: $*" >&2; exit 1; }

O=example-owner
D1=sha256:$(printf '1%.0s' $(seq 64)); D2=sha256:$(printf '2%.0s' $(seq 64)); D3=sha256:$(printf '3%.0s' $(seq 64))
head -c 4096 /dev/urandom > "$T/agent.apk"
P2=linux/amd64,linux/arm64
( cd "$T" && "$ROOT/release/build-manifest.sh" 9.8.7 stable "ghcr.io/$O/mdmesh-server:9.8.7" "ghcr.io/$O/mdmesh-web:9.8.7" \
    agent.apk 90807 CKSUM 1.5 "ghcr.io/$O/mdmesh-server@$D1" "ghcr.io/$O/mdmesh-web@$D2" "ghcr.io/$O/mdmesh-supervisor@$D3" "$P2" ) >/dev/null
# A ref outside the image's repository, or not a digest, is refused at build time.
( cd "$T" && ! "$ROOT/release/build-manifest.sh" 9.8.7 stable "ghcr.io/$O/mdmesh-server:9.8.7" "ghcr.io/$O/mdmesh-web:9.8.7" \
    agent.apk 90807 CKSUM 1.5 "ghcr.io/other/mdmesh-server@$D1" "ghcr.io/$O/mdmesh-web@$D2" "ghcr.io/$O/mdmesh-supervisor@$D3" "$P2" ) 2>/dev/null \
  || fail "build-manifest.sh accepted a server digest from another repository"
( cd "$T" && ! "$ROOT/release/build-manifest.sh" 9.8.7 stable "ghcr.io/$O/mdmesh-server:9.8.7" "ghcr.io/$O/mdmesh-web:9.8.7" \
    agent.apk 90807 CKSUM 1.5 "ghcr.io/$O/mdmesh-server:9.8.7" "ghcr.io/$O/mdmesh-web@$D2" "ghcr.io/$O/mdmesh-supervisor@$D3" "$P2" ) 2>/dev/null \
  || fail "build-manifest.sh accepted a tag where a digest belongs"
# The image platforms (components.platforms) are linux/<arch> names, linux/amd64 among them.
for bad in linux/arm64 'linux/amd64, linux/arm64' amd64,arm64 ''; do
  ( cd "$T" && ! "$ROOT/release/build-manifest.sh" 9.8.7 stable "ghcr.io/$O/mdmesh-server:9.8.7" "ghcr.io/$O/mdmesh-web:9.8.7" \
      agent.apk 90807 CKSUM 1.5 "ghcr.io/$O/mdmesh-server@$D1" "ghcr.io/$O/mdmesh-web@$D2" "ghcr.io/$O/mdmesh-supervisor@$D3" "$bad" ) 2>/dev/null \
    || fail "build-manifest.sh accepted the platforms '$bad'"
done

# The digests release.yml pins come from release/image-digest.sh: a registry index (docker stubbed with its inspect
# output) is pinned only when it holds exactly one image for each platform asked for.
mkdir "$T/bin"
# shellcheck disable=SC2016  # the stub expands it when it runs
printf '#!/bin/sh\ncat "$MDM_FAKE_INSPECT"\n' > "$T/bin/docker"; chmod +x "$T/bin/docker"
plat() { printf '{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:%s","platform":{"os":"%s","architecture":"%s"%s}}' \
  "$(printf %064d 0 | tr 0 "$1")" "$2" "$3" "${4:+,\"variant\":\"$4\"}"; }
index() { local IFS=,; printf '{"mediaType":"application/vnd.oci.image.index.v1+json","digest":"%s","manifests":[%s]}' "$D1" "$*"; }
digest_of() { MDM_FAKE_INSPECT="$T/inspect.json" PATH="$T/bin:$PATH" "$ROOT/release/image-digest.sh" "ghcr.io/$O/mdmesh-server:9.8.7" "$@"; }
index "$(plat a linux amd64)" "$(plat b linux arm64 v8)" > "$T/inspect.json"
[ "$(digest_of linux/amd64 linux/arm64)" = "$D1" ] || fail "image-digest.sh refused an amd64 + arm64 index"
[ "$(digest_of)" = "$D1" ] || fail "image-digest.sh without platforms refused an index"
index "$(plat a linux amd64)" "$(plat c unknown unknown)" > "$T/inspect.json"
! digest_of linux/amd64 linux/arm64 2>/dev/null || fail "image-digest.sh pinned an index without arm64"
index "$(plat a linux amd64)" "$(plat b linux amd64)" "$(plat c linux arm64)" > "$T/inspect.json"
! digest_of linux/amd64 linux/arm64 2>/dev/null || fail "image-digest.sh pinned an index with two amd64 images"
printf '{"mediaType":"application/vnd.docker.distribution.manifest.v2+json","digest":"%s"}' "$D2" > "$T/inspect.json"
! digest_of linux/amd64 linux/arm64 2>/dev/null || fail "image-digest.sh pinned a single-platform image as multi-platform"
[ "$(digest_of)" = "$D2" ] || fail "image-digest.sh without platforms refused a single-platform image"
printf '{"digest":"sha256:abc"}' > "$T/inspect.json"
! digest_of 2>/dev/null || fail "image-digest.sh accepted a malformed digest"
: > "$T/inspect.json"
! digest_of 2>/dev/null || fail "image-digest.sh printed a digest for a failed inspect"

# The recorded APK signing checksum (release/agent-signature-checksum), which release.yml bakes into the web image and
# holds the signed APK to: apk-checksum.sh --recorded prints its one value, a 43-character base64url SHA-256.
ck=$("$ROOT/release/apk-checksum.sh" --recorded) || fail "apk-checksum.sh --recorded failed"
[[ "$ck" =~ ^[A-Za-z0-9_-]{43}$ ]] || fail "release/agent-signature-checksum does not hold one base64url SHA-256 (got '$ck')"
[ "$ck" = "$(grep -v '^#' "$ROOT/release/agent-signature-checksum" | grep .)" ] || fail "apk-checksum.sh --recorded is not the file's value"
# It takes exactly one value line: comments (indented ones too) and blank lines aside; two values, or none, fail.
mkdir "$T/rc"; cp "$ROOT/release/apk-checksum.sh" "$T/rc/"
printf '# c\n\n  # indented c\n%s\n\n' "$ck" > "$T/rc/agent-signature-checksum"
[ "$("$T/rc/apk-checksum.sh" --recorded)" = "$ck" ] || fail "apk-checksum.sh --recorded refused comments and blank lines"
printf '# c\n%s\n%s\n' "$ck" "$ck" > "$T/rc/agent-signature-checksum"
! "$T/rc/apk-checksum.sh" --recorded 2>/dev/null || fail "apk-checksum.sh --recorded accepted two value lines"
printf '# c\n   # %s\n' "$ck" > "$T/rc/agent-signature-checksum"
! "$T/rc/apk-checksum.sh" --recorded 2>/dev/null || fail "apk-checksum.sh --recorded took a value from an indented comment"
printf '%s \n' "$ck" > "$T/rc/agent-signature-checksum"
! "$T/rc/apk-checksum.sh" --recorded 2>/dev/null || fail "apk-checksum.sh --recorded accepted a malformed line"

# securityReleases: derived from CHANGELOG.md by build-manifest.sh, and checked by release.yml.
[ "$(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))["securityReleases"]))' "$T/manifest.json")" \
    = "$("$ROOT/release/security-releases.py" "$ROOT/CHANGELOG.md")" ] || fail "securityReleases is not CHANGELOG.md's list"
"$ROOT/release/security-releases.py" --check "$T/manifest.json" > "$T/sec.out" || fail "security-releases.py --check refused a good manifest"
python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); m["securityReleases"]=m["securityReleases"][:-1]; json.dump(m,open(sys.argv[2],"w"))' \
  "$T/manifest.json" "$T/sec.json"
! "$ROOT/release/security-releases.py" --check "$T/sec.json" 2> "$T/sec.out" || fail "security-releases.py --check accepted a short list"
python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); del m["securityReleases"]; json.dump(m,open(sys.argv[2],"w"))' \
  "$T/manifest.json" "$T/sec.json"
! "$ROOT/release/security-releases.py" --check "$T/sec.json" 2> "$T/sec.out" || fail "security-releases.py --check accepted a manifest without the list"
# The rule on a fixture changelog: a "Security" heading line (with "(…)" or ": …", or as a markdown heading) marks its
# release; an indented or inline "Security", one in fenced code, Unreleased, and the Older releases section don't;
# --upto stops at a version.
cat > "$T/changelog.md" <<'MD'
# Changelog

## Unreleased

Security
- Not released yet.

## v1.3.0 — 2030-01-03

New
- Security keys can be rotated.
- Something
  Security

## v1.2.1 — 2030-01-02

### Security (agent 1.2.1)
- A fix.

## v1.2.0 — 2030-01-01

Fixes
- Something.

```
Security
```

Security: one fix
- A fix.

## v1.10.0 — 2030-02-01

Security
- A fix.

## v1.0.0 — 2029-12-01

```
Security
```

## Older releases

Security
MD
[ "$("$ROOT/release/security-releases.py" "$T/changelog.md")" = '["1.2.0", "1.2.1", "1.10.0"]' ] \
  || fail "security-releases.py: wrong list for the fixture changelog: $("$ROOT/release/security-releases.py" "$T/changelog.md")"
[ "$("$ROOT/release/security-releases.py" --upto 1.3.0 "$T/changelog.md")" = '["1.2.0", "1.2.1"]' ] \
  || fail "security-releases.py --upto: wrong list for the fixture changelog"
[ "$("$ROOT/release/security-releases.py" --upto 1.2.0 "$T/changelog.md")" = '["1.2.0"]' ] \
  || fail "security-releases.py --upto: a version is its own security release"

minisign -G -W -p "$T/k.pub" -s "$T/k.key" >/dev/null
minisign -S -s "$T/k.key" -m "$T/manifest.json" >/dev/null
minisign -G -W -p "$T/x.pub" -s "$T/x.key" >/dev/null

# quickstart.sh's verification functions, exactly as the script defines them.
sed -n '/^# >>> release verification >>>$/,/^# <<< release verification <<<$/p' "$ROOT/quickstart.sh" > "$T/qs.sh"
[ -s "$T/qs.sh" ] || fail "quickstart.sh has no release verification block"
# shellcheck source=/dev/null
. "$T/qs.sh"

[ "$MDM_RELEASE_PUBKEY_DEFAULT" = "$(sed -n 2p "$ROOT/release/minisign.pub")" ] \
  || fail "quickstart.sh's MDM_RELEASE_PUBKEY_DEFAULT is not the key line of release/minisign.pub: copy it over"
want_files=$(python3 -c 'import re,sys; s=open(sys.argv[1]).read(); print(" ".join(re.search(r"QUICKSTART_FILES = \[(.*?)\]", s, re.S).group(1).replace("\"","").replace(",","").split()))' "$ROOT/release/build-manifest.sh")
[ "$want_files" = "$MDM_QUICKSTART_FILES" ] \
  || fail "quickstart.sh's MDM_QUICKSTART_FILES ($MDM_QUICKSTART_FILES) differ from build-manifest.sh's QUICKSTART_FILES ($want_files)"

PK=$(sed -n 2p "$T/k.pub")
mdm_minisign_verify "$PK" "$T/manifest.json" "$T/manifest.json.minisig" || fail "a good signature was refused"
sed 's/9\.8\.7/9.8.8/' "$T/manifest.json" > "$T/tampered.json"
! mdm_minisign_verify "$PK" "$T/tampered.json" "$T/manifest.json.minisig" 2>/dev/null || fail "a tampered manifest verified"
! mdm_minisign_verify "$(sed -n 2p "$T/x.pub")" "$T/manifest.json" "$T/manifest.json.minisig" 2>/dev/null \
  || fail "a manifest signed by another key verified"

# The same through mdm_verify_manifest: openssl when it can (3.0+), else minisign, else rc 2 (neither tool; nothing checked).
mdm_verify_manifest "$PK" "$T/manifest.json" "$T/manifest.json.minisig" || fail "mdm_verify_manifest refused a good signature"
(
  # shellcheck disable=SC2317  # called by mdm_verify_manifest
  mdm_openssl_can_verify() { return 1; }   # an OpenSSL 1.1.1 host
  mdm_verify_manifest "$PK" "$T/manifest.json" "$T/manifest.json.minisig" || fail "the minisign fallback refused a good signature"
  rc=0; mdm_verify_manifest "$PK" "$T/tampered.json" "$T/manifest.json.minisig" 2>/dev/null || rc=$?
  [ "$rc" = 1 ] || fail "the minisign fallback accepted a tampered manifest (rc $rc)"
  rc=0; PATH=/nonexistent mdm_verify_manifest "$PK" "$T/manifest.json" "$T/manifest.json.minisig" || rc=$?
  [ "$rc" = 2 ] || fail "with neither openssl 3 nor minisign, mdm_verify_manifest must return 2 (got $rc)"
)

[ "$(mdm_manifest_field "$T/manifest.json" version '[0-9A-Za-z.+-]+')" = 9.8.7 ] || fail "version not read"
[ "$(mdm_manifest_field "$T/manifest.json" server "$MDM_IMAGE_REF_ERE")" = "ghcr.io/$O/mdmesh-server@$D1" ] || fail "server ref not read"
[ "$(mdm_manifest_field "$T/manifest.json" web "$MDM_IMAGE_REF_ERE")" = "ghcr.io/$O/mdmesh-web@$D2" ] || fail "web ref not read"
[ "$(mdm_manifest_field "$T/manifest.json" supervisor "$MDM_IMAGE_REF_ERE")" = "ghcr.io/$O/mdmesh-supervisor@$D3" ] || fail "supervisor ref not read"
# The platforms the quick start checks the host against; a manifest without them (before Arm64 images) is linux/amd64 only.
mdm_manifest_has_platform "$T/manifest.json" linux/arm64 || fail "the quick start does not read linux/arm64 from the manifest"
mdm_manifest_has_platform "$T/manifest.json" linux/amd64 || fail "the quick start does not read linux/amd64 from the manifest"
! mdm_manifest_has_platform "$T/manifest.json" linux/arm || fail "the quick start matched linux/arm against linux/arm64"
python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); del m["components"]["platforms"]; open(sys.argv[2],"w").write(json.dumps(m, indent=2))' \
  "$T/manifest.json" "$T/old.json"
mdm_manifest_has_platform "$T/old.json" linux/amd64 || fail "a manifest without platforms is not linux/amd64"
! mdm_manifest_has_platform "$T/old.json" linux/arm64 || fail "a manifest without platforms counted as linux/arm64"
grep -q '^  "files": {$' "$T/manifest.json" || fail "quickstart.sh would not find the files block"
for f in $MDM_QUICKSTART_FILES quickstart.sh; do
  [ "$(mdm_manifest_field "$T/manifest.json" "$f" "$MDM_SHA256_ERE")" = "$(mdm_sha256 "$ROOT/$f")" ] || fail "sha256 of $f not read or wrong"
done
# The release asset quickstart.sh, which README.md and DEPLOY.md's install block download, is in the files: list of
# release.yml's GitHub Release step.
awk '/^ +files: \|$/ { on = 1; next } on && /^ +[a-z0-9._-]+$/ { print $1; next } { on = 0 }' \
    "$ROOT/.github/workflows/release.yml" | grep -qx 'quickstart\.sh' \
  || fail "release.yml no longer uploads quickstart.sh as a release asset"
# DEPLOY.md's install block greps this exact version line (grep -qxF '  "version": "X.Y.Z",').
grep -qxF '  "version": "9.8.7",' "$T/manifest.json" || fail "the manifest's version line is not what the install block greps for"
# DEPLOY.md has the install block and the unchecked shortcut once each, and the block's key is the release key.
# README.md's quick start is the one-line command that runs the latest release's quickstart.sh asset (never main's).
python3 - "$ROOT" "$(sed -n 2p "$ROOT/release/minisign.pub")" <<'PY' || fail "README.md / DEPLOY.md install commands (details above)"
import re, sys
root, key = sys.argv[1], sys.argv[2]
def bash_blocks(name):
    return re.findall(r"```bash\n(.*?)```", open(f"{root}/{name}").read(), re.S)
found = bash_blocks("DEPLOY.md")
checked = [b for b in found if "minisign -V -m" in b]
shortcut = [b for b in found if "/refs/tags/$t/quickstart.sh" in b]
if len(checked) != 1 or len(shortcut) != 1:
    sys.exit(f"DEPLOY.md: expected one install block and one shortcut, found {len(checked)} and {len(shortcut)}")
keys = re.findall(r"-P (\S+)", checked[0])
if keys != [key]: sys.exit(f"install block key {keys} is not release/minisign.pub's {key}")
one_liner = "bash <(curl -fsSL https://github.com/MDMesh-app/MDMesh/releases/latest/download/quickstart.sh)\n"
readme = [b for b in bash_blocks("README.md") if "quickstart.sh" in b]
if readme != [one_liner]:
    sys.exit(f"README.md: expected one quick-start block, exactly {one_liner.strip()!r}; found {readme}")
PY

# shellcheck disable=SC2016  # the JavaScript is single-quoted on purpose
node -e '
  const { imagePins, imageTags, apkAsset } = require(process.argv[1] + "/supervisor/lib.js");
  const m = JSON.parse(require("fs").readFileSync(process.argv[2], "utf8"));
  const o = process.argv[3], want = { server: `ghcr.io/${o}/mdmesh-server@${process.argv[4]}`,
    web: `ghcr.io/${o}/mdmesh-web@${process.argv[5]}`, supervisor: `ghcr.io/${o}/mdmesh-supervisor@${process.argv[6]}` };
  const got = imagePins(m, o);
  if (JSON.stringify(got) !== JSON.stringify(want)) { console.error("imagePins:", got); process.exit(1); }
  // What a supervisor before 0.5.0 reads (imageTags, apkAsset) is still there, in the same shape.
  const t = imageTags(m);
  if (t.version !== "9.8.7" || t.serverImage !== `ghcr.io/${o}/mdmesh-server:9.8.7` || t.webImage !== `ghcr.io/${o}/mdmesh-web:9.8.7`) process.exit(2);
  if (!apkAsset({ assets: [{ name: "agent.apk", browser_download_url: "https://x/agent.apk" }] }, m)) process.exit(3);
' "$ROOT" "$T/manifest.json" "$O" "$D1" "$D2" "$D3" || fail "supervisor/lib.js does not read the manifest as expected"
# What setup.sh --update reads: the commit, which is the checkout's.
[ "$(mdm_manifest_field "$T/manifest.json" commit '[0123456789abcdef]{40}' || true)" = "$(git -C "$ROOT" rev-parse HEAD)" ] \
  || fail "the manifest's commit is not the checkout's HEAD, or setup.sh --update can't read it"
# What install/lib/release.sh reads (setup.sh and the native installer).
python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); a=m["components"]["apk"]; assert m["version"]=="9.8.7" and a["sha256"] and a["signatureChecksum"]=="CKSUM"' "$T/manifest.json" \
  || fail "the installers' fields changed"
echo "release-manifest-check: ok"
