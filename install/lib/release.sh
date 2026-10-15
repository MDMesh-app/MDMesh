#!/usr/bin/env bash
# The latest GitHub release's agent APK, trusted only through its signed manifest — the one check both from-source
# installers (setup.sh, install/install-native.sh) use, as the supervisor does: manifest.json must verify with minisign
# against the checkout's release/minisign.pub before any field of it is read, it must be the manifest of that release
# (not a replayed older one), and the APK must match its sha256. Source this file; do not execute it.

# mdm_gh_curl ARGS...: curl, sending GITHUB_TOKEN (when set) as an Authorization header read from stdin (-H @-, curl
# 7.55+), never on curl's command line, which every local user can read (ps, /proc/<pid>/cmdline).
mdm_gh_curl() {
  if [ -n "${GITHUB_TOKEN:-}" ]; then printf 'Authorization: Bearer %s\n' "$GITHUB_TOKEN" | curl -H @- "$@"
  else curl "$@"; fi
}

# _mdm_rel_get NAME: the download URL of release asset NAME (or the tag) in the release JSON on stdin; empty (and
# success) when there is none or the reply is not JSON (no release yet, an empty body, a rate-limit page): under
# set -euo pipefail a failing lookup would end the caller at the assignment instead of taking the "no release" branch.
_mdm_rel_get() { python3 -c 'import sys,json;
d=json.load(sys.stdin)
def asset(n): return next((a["browser_download_url"] for a in d.get("assets",[]) if a["name"]==n),"")
print({"apk":asset("mdmesh-agent.apk"),"manifest":asset("manifest.json"),
       "signature":asset("manifest.json.minisig"),"tag":str(d.get("tag_name") or "")}.get(sys.argv[1],""))' "$1" 2>/dev/null || true; }
# _mdm_manifest_apk FIELD FILE: components.apk.FIELD of the manifest FILE; empty when it has none.
_mdm_manifest_apk() { python3 -c 'import sys,json;print(json.load(open(sys.argv[2]))["components"]["apk"][sys.argv[1]])' "$1" "$2" 2>/dev/null || true; }
# _mdm_manifest_version FILE: the manifest's top-level "version" (release/build-manifest.sh: the tag without its "v").
_mdm_manifest_version() { python3 -c 'import sys,json;v=json.load(open(sys.argv[1])).get("version");print(v if isinstance(v,str) else "")' "$1" 2>/dev/null || true; }

# mdm_fetch_release_apk GITHUB_REPO PUBKEY DIR LOG: fetch the latest release of GITHUB_REPO (owner/name) into DIR (an
# existing private directory the caller owns and removes) and verify it as described above, sending tool output to LOG.
# Returns 0 with MDM_AGENT_CK (the APK's signing-certificate checksum from the signed manifest) and the APK at
# DIR/agent.apk; otherwise returns 1 with MDM_RELEASE_PROBLEM set to a one-line reason and MDM_AGENT_CK empty.
# Either way MDM_RELEASE_NOTE is a one-line note for the caller to show, or empty: when GitHub refuses GITHUB_TOKEN
# (HTTP 401: expired or revoked), the release is fetched again without it, and the note says so.
# The reasons are worded to be followed by the caller's own consequence (e.g. " — console uses debug defaults").
# MDM_MINISIGN names the minisign command (default: minisign on PATH); setup.sh points it at the supervisor image's.
# shellcheck disable=SC2034  # MDM_AGENT_CK / MDM_RELEASE_PROBLEM / MDM_RELEASE_NOTE are results for the caller
mdm_fetch_release_apk() {
  local repo=$1 pubkey=$2 d=$3 log=$4 rel="" code apk_url man_url sig_url tag mver want_sha details="" ms=${MDM_MINISIGN:-minisign}
  local api="https://api.github.com/repos/${repo}/releases/latest"
  MDM_AGENT_CK=""; MDM_RELEASE_PROBLEM=""; MDM_RELEASE_NOTE=""
  [ "$log" = /dev/null ] || details=" (details in $log)"
  # The HTTP status says why a lookup failed (curl prints 000 when GitHub could not be reached).
  code=$(mdm_gh_curl -sSL -o "$d/release.json" -w '%{http_code}' "$api" 2>>"$log" || true)
  if [ "$code" = 401 ] && [ -n "${GITHUB_TOKEN:-}" ]; then
    MDM_RELEASE_NOTE="GitHub refused GITHUB_TOKEN (HTTP 401: expired or revoked?), so the release was fetched without it"
    # Shadows the caller's GITHUB_TOKEN for the rest of this function (and mdm_gh_curl, which it calls) only.
    local GITHUB_TOKEN=""
    code=$(mdm_gh_curl -sSL -o "$d/release.json" -w '%{http_code}' "$api" 2>>"$log" || true)
  fi
  case "$code" in
    200) rel=$(cat -- "$d/release.json" 2>/dev/null || true) ;;
    403) if [ -n "${GITHUB_TOKEN:-}" ]; then MDM_RELEASE_PROBLEM="GitHub refused the release lookup for ${repo} (HTTP 403: a rate limit, or GITHUB_TOKEN lacks access)"
         else MDM_RELEASE_PROBLEM="GitHub refused the release lookup for ${repo} (HTTP 403: a rate limit; a GITHUB_TOKEN raises it)"; fi; return 1 ;;
    429) MDM_RELEASE_PROBLEM="GitHub's rate limit stopped the release lookup for ${repo} (HTTP 429$([ -n "${GITHUB_TOKEN:-}" ] || echo '; a GITHUB_TOKEN raises it'))"; return 1 ;;
    000|'') MDM_RELEASE_PROBLEM="Could not reach GitHub to look up the latest release of ${repo}${details}"; return 1 ;;
    404) if [ -n "${GITHUB_TOKEN:-}" ]; then MDM_RELEASE_PROBLEM="No published release found for ${repo}, or GITHUB_TOKEN has no access to it (HTTP 404)"
         else MDM_RELEASE_PROBLEM="No published release found for ${repo}"; fi; return 1 ;;
    *) MDM_RELEASE_PROBLEM="No published release found for ${repo} (HTTP ${code:-000})"; return 1 ;;
  esac
  rm -f -- "$d/release.json"
  apk_url=$(printf '%s' "$rel" | _mdm_rel_get apk); man_url=$(printf '%s' "$rel" | _mdm_rel_get manifest)
  sig_url=$(printf '%s' "$rel" | _mdm_rel_get signature)
  if [ -z "$apk_url" ] || [ -z "$man_url" ]; then MDM_RELEASE_PROBLEM="No published release found for ${repo}"; return 1; fi
  if [ -z "$sig_url" ]; then
    MDM_RELEASE_PROBLEM="The latest release of ${repo} has no manifest signature (manifest.json.minisig), so its APK is not trusted"; return 1
  fi
  if ! command -v "$ms" >/dev/null 2>&1; then
    MDM_RELEASE_PROBLEM="minisign is unavailable, so the release manifest cannot be verified and its APK is not trusted"; return 1
  fi
  if [ ! -r "$pubkey" ]; then
    MDM_RELEASE_PROBLEM="The release signing key ${pubkey} is missing, so the manifest cannot be verified"; return 1
  fi
  if ! mdm_gh_curl -fsSL "$man_url" -o "$d/manifest.json" 2>>"$log" \
     || ! mdm_gh_curl -fsSL "$sig_url" -o "$d/manifest.json.minisig" 2>>"$log"; then
    MDM_RELEASE_PROBLEM="Could not download the release manifest or its signature${details}"; return 1
  fi
  if ! "$ms" -V -p "$pubkey" -m "$d/manifest.json" >> "$log" 2>&1; then
    MDM_RELEASE_PROBLEM="The release manifest's signature is invalid (it does not verify with ${pubkey}), so its APK is not trusted"; return 1
  fi
  # Bind the signed manifest to this release: an older release's manifest is signed too, and served in its place (a
  # replay) it would verify. Its version must be the release tag without the "v".
  tag=$(printf '%s' "$rel" | _mdm_rel_get tag); mver=$(_mdm_manifest_version "$d/manifest.json")
  if [ -z "$mver" ] || [ "$mver" != "${tag#v}" ]; then
    MDM_RELEASE_PROBLEM="The signed manifest is for version ${mver:-(none)}, not for the latest release ${tag:-(untagged)}, so its APK is not trusted"; return 1
  fi
  MDM_AGENT_CK=$(_mdm_manifest_apk signatureChecksum "$d/manifest.json"); want_sha=$(_mdm_manifest_apk sha256 "$d/manifest.json")
  if [ -z "$MDM_AGENT_CK" ] || [ -z "$want_sha" ]; then
    MDM_AGENT_CK=""; MDM_RELEASE_PROBLEM="The signed release manifest names no agent APK checksum or sha256"; return 1
  fi
  if ! mdm_gh_curl -fsSL "$apk_url" -o "$d/agent.apk" 2>>"$log"; then
    MDM_AGENT_CK=""; MDM_RELEASE_PROBLEM="Could not download the release APK${details}"; return 1
  fi
  if [ "$(sha256sum "$d/agent.apk" | awk '{print $1}')" != "$want_sha" ]; then
    MDM_AGENT_CK=""; MDM_RELEASE_PROBLEM="The downloaded APK does not match the sha256 in the signed manifest, so it is not trusted"; return 1
  fi
  return 0
}
