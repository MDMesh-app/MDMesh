#!/usr/bin/env bash
# The signed manifest's contract, end to end, without a registry: release/build-manifest.sh writes it, minisign signs it
# (a throwaway key), and then
#   - quickstart.sh's own verification functions (the "release verification" block, run as they are) accept it, and
#     refuse it tampered with or signed by another key;
#   - they read the version, the three image digests and each downloaded file's SHA-256 from it;
#   - supervisor/lib.js imagePins (what apply.sh is handed) reads the same three refs;
#   - the fields older supervisors and the installers read (components.serverImage/webImage/apk) are unchanged;
#   - quickstart.sh's copy of the release key and its file list match release/minisign.pub and build-manifest.sh;
#   - the manifest records quickstart.sh's own SHA-256, and release.yml publishes quickstart.sh as a release asset.
# Needs bash, python3, node, openssl and minisign. Run from anywhere: scripts/release-manifest-check.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
cleanup() {
  rm -f -- "$T/manifest.json" "$T/manifest.json.minisig" "$T/k.pub" "$T/k.key" "$T/x.pub" "$T/x.key" \
    "$T/agent.apk" "$T/tampered.json" "$T/qs.sh"
  rmdir -- "$T" 2>/dev/null || true
}
trap cleanup EXIT
fail() { echo "release-manifest-check: $*" >&2; exit 1; }

O=example-owner
D1=sha256:$(printf '1%.0s' $(seq 64)); D2=sha256:$(printf '2%.0s' $(seq 64)); D3=sha256:$(printf '3%.0s' $(seq 64))
head -c 4096 /dev/urandom > "$T/agent.apk"
( cd "$T" && "$ROOT/release/build-manifest.sh" 9.8.7 stable "ghcr.io/$O/mdmesh-server:9.8.7" "ghcr.io/$O/mdmesh-web:9.8.7" \
    agent.apk 90807 CKSUM 1.5 "ghcr.io/$O/mdmesh-server@$D1" "ghcr.io/$O/mdmesh-web@$D2" "ghcr.io/$O/mdmesh-supervisor@$D3" ) >/dev/null
# A ref outside the image's repository, or not a digest, is refused at build time.
( cd "$T" && ! "$ROOT/release/build-manifest.sh" 9.8.7 stable "ghcr.io/$O/mdmesh-server:9.8.7" "ghcr.io/$O/mdmesh-web:9.8.7" \
    agent.apk 90807 CKSUM 1.5 "ghcr.io/other/mdmesh-server@$D1" "ghcr.io/$O/mdmesh-web@$D2" "ghcr.io/$O/mdmesh-supervisor@$D3" ) 2>/dev/null \
  || fail "build-manifest.sh accepted a server digest from another repository"
( cd "$T" && ! "$ROOT/release/build-manifest.sh" 9.8.7 stable "ghcr.io/$O/mdmesh-server:9.8.7" "ghcr.io/$O/mdmesh-web:9.8.7" \
    agent.apk 90807 CKSUM 1.5 "ghcr.io/$O/mdmesh-server:9.8.7" "ghcr.io/$O/mdmesh-web@$D2" "ghcr.io/$O/mdmesh-supervisor@$D3" ) 2>/dev/null \
  || fail "build-manifest.sh accepted a tag where a digest belongs"

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
grep -q '^  "files": {$' "$T/manifest.json" || fail "quickstart.sh would not find the files block"
for f in $MDM_QUICKSTART_FILES quickstart.sh; do
  [ "$(mdm_manifest_field "$T/manifest.json" "$f" "$MDM_SHA256_ERE")" = "$(mdm_sha256 "$ROOT/$f")" ] || fail "sha256 of $f not read or wrong"
done
# The release asset quickstart.sh, which the README's checked install block downloads, is uploaded by release.yml.
grep -Eq '^ +quickstart\.sh$' "$ROOT/.github/workflows/release.yml" || fail "release.yml no longer uploads quickstart.sh as a release asset"

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
