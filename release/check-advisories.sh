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
# REPO=owner/name to check another repository (default MDMesh-app/MDMesh); CHANGELOG=path to check another changelog.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
repo="${REPO:-MDMesh-app/MDMesh}"
changelog="${CHANGELOG:-$root/CHANGELOG.md}"
upto="${1:-$(grep -m1 -oE '^## v[0-9]+\.[0-9]+\.[0-9]+' "$changelog" | cut -c5-)}"
printf '%s' "$upto" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "check-advisories: version must be X.Y.Z (got '$upto')" >&2; exit 2; }
sec=$("$root/release/security-releases.py" --upto "$upto" "$changelog")
# One line per advisory: id, state, its patched versions (comma-separated, may be empty).
advisories=$(gh api --paginate "repos/$repo/security-advisories?per_page=100" \
  --jq '.[] | [.ghsa_id, .state, ([.vulnerabilities[]?.patched_versions // empty] | join(","))] | @tsv')
python3 - "$upto" "$sec" "$advisories" <<'PY'
import json, re, sys
upto, sec, rows = sys.argv[1], set(json.loads(sys.argv[2])), sys.argv[3]
key = lambda v: tuple(int(x) for x in v.split("."))
bad = warn = 0
for row in filter(None, rows.split("\n")):
    ghsa, state, patched = (row.split("\t") + ["", ""])[:3]
    versions = sorted(set(re.findall(r"\d+\.\d+\.\d+", patched)), key=key)
    print("%-21s %-10s patched: %s" % (ghsa, state, ", ".join(versions) or "(not set)"))
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
