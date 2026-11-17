#!/usr/bin/env bash
# Every security advisory patched in a release has that release marked a security release in CHANGELOG.md (a Security
# section: release/security-releases.py), so its notes say "Security fixes" and the signed manifest's securityReleases
# lists it. Run before tagging (RELEASING.md, step 2); it needs `gh` signed in as a maintainer who can read the
# repository's advisories, drafts included, which the release workflow's token can't, so CI doesn't run it.
#   release/check-advisories.sh [X.Y.Z]
#       X.Y.Z: the release being cut (default: the newest `## vX.Y.Z` section of CHANGELOG.md). Advisories patched in a
#       later version are left for that release.
# Lists each advisory's id, state and patched versions; fails if a patched version up to X.Y.Z has no Security section,
# and warns about a draft advisory with no patched version yet (set it, or make sure it isn't in this release).
# Withdrawn and closed advisories are listed but not checked. Exit codes: 1 a missing Security section, 2 usage or an
# unreadable CHANGELOG.md, 3 gh couldn't list the advisories.
# REPO=owner/name to check another repository (default MDMesh-app/MDMesh); CHANGELOG=path to check another changelog.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
repo="${REPO:-MDMesh-app/MDMesh}"
changelog="${CHANGELOG:-$root/CHANGELOG.md}"
upto="${1:-}"
[ -r "$changelog" ] || { echo "check-advisories: can't read $changelog" >&2; exit 2; }
if [ -z "$upto" ]; then
  upto=$(grep -m1 -oE '^## v[0-9]+\.[0-9]+\.[0-9]+' "$changelog" | cut -c5- || true)
  [ -n "$upto" ] || { echo "check-advisories: $changelog has no '## vX.Y.Z' heading; name the version: check-advisories.sh X.Y.Z" >&2; exit 2; }
fi
printf '%s' "$upto" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "check-advisories: version must be X.Y.Z (got '$upto')" >&2; exit 2; }
sec=$("$root/release/security-releases.py" --upto "$upto" "$changelog")
# One line per advisory: id, state, its patched versions (comma-separated, may be empty). A gh failure (not signed in,
# no access, network) exits 3, so it can't be taken for a missing Security section (1).
if ! advisories=$(gh api --paginate "repos/$repo/security-advisories?per_page=100" \
    --jq '.[] | [.ghsa_id, .state, ([.vulnerabilities[]?.patched_versions // empty] | join(","))] | @tsv'); then
  echo "check-advisories: couldn't list $repo's security advisories with gh (signed in, with access to the repository?)" >&2
  exit 3
fi
# Drafts are listed only to a token with admin or security-manager access; anyone else sees published ones only.
if [ "$(gh api "repos/$repo" --jq '.permissions.admin // false' 2>&1 || true)" != true ]; then
  echo "check-advisories: warning: this gh token isn't an admin of $repo, so draft advisories may be missing from the list" >&2
fi
python3 - "$upto" "$sec" "$advisories" <<'PY'
import json, re, sys
upto, sec, rows = sys.argv[1], set(json.loads(sys.argv[2])), sys.argv[3]
key = lambda v: tuple(int(x) for x in v.split("."))
bad = warn = 0
for row in filter(None, rows.split("\n")):
    ghsa, state, patched = (row.split("\t") + ["", ""])[:3]
    versions = sorted(set(re.findall(r"\d+\.\d+\.\d+", patched)), key=key)
    print("%-21s %-10s patched: %s" % (ghsa, state, ", ".join(versions) or "(not set)"))
    if state in ("withdrawn", "closed"):
        print("  - %s: not a vulnerability fixed here, not checked" % state)
        continue
    if not versions:
        if state in ("draft", "triage"):
            warn += 1
            print("  ! no patched version yet: set it if %s fixes it, or check it isn't in this release" % upto)
        continue
    for v in versions:
        if key(v) <= key(upto) and v not in sec:
            bad += 1
            print("  x %s has no Security section in CHANGELOG.md: add one, with this fix's line under it" % v)
print("security releases up to %s: %s" % (upto, ", ".join(sorted(sec, key=key)) or "(none)"))
if bad:
    sys.exit("check-advisories: %d patched version(s) without a Security section (RELEASING.md, Security fixes)" % bad)
print("check-advisories: ok" + (" (%d draft(s) to look at)" % warn if warn else ""))
PY
