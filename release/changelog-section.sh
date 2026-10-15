#!/usr/bin/env bash
# One release's section of CHANGELOG.md, as its tag annotation and GitHub release notes must read it.
#   release/changelog-section.sh 1.2.3            print "v1.2.3", a blank line, then the body of "## v1.2.3 …"
#   release/changelog-section.sh --check v1.2.3 [SHA]
#       fail unless tag v1.2.3's annotation matches the CHANGELOG.md of the commit it tags, and (with SHA) that commit
#       is SHA. release.yml runs this in the gate job, at the start of the release job and before publishing.
# Tag with (RELEASING.md): n=$(release/changelog-section.sh 1.2.3) && git tag -a v1.2.3 --cleanup=whitespace -m "$n" SHA
# A released section may not contain HTML comments, so a leftover <!-- pending … --> marker fails here.
# The comparison ignores CRs, trailing spaces and runs of blank lines.
set -euo pipefail

die() { echo "changelog-section: $*" >&2; exit 1; }

# CRs and trailing whitespace off, runs of blank lines squeezed to one, leading/trailing blank lines dropped.
norm() { awk '{ sub(/\r$/, ""); sub(/[ \t]+$/, "") }
  $0 == "" { blank = 1; next }
  { if (seen && blank) print ""; print; seen = 1; blank = 0 }'; }

# section VERSION < CHANGELOG.md
section() {
  local v="$1" re out n
  re="^## v${v//./[.]}([[:space:]]|\$)"
  out=$(sed 's/\r$//')
  n=$(printf '%s\n' "$out" | grep -cE "$re" || true)
  [ "$n" -gt 0 ] || die "no '## v$v' section in CHANGELOG.md"
  [ "$n" -eq 1 ] || die "'## v$v' appears $n times in CHANGELOG.md"
  out=$(printf '%s\n' "$out" | awk -v re="$re" '$0 ~ re { on = 1; next } on && /^## / { exit } on' | norm)
  [ -n "$out" ] || die "the '## v$v' section in CHANGELOG.md is empty"
  if printf '%s\n' "$out" | grep -q -- '<!--'; then
    die "the '## v$v' section has an HTML comment. Drop the lines of PRs that didn't make it and remove every marker."
  fi
  printf 'v%s\n\n%s\n' "$v" "$out"
}

valid() { printf '%s' "$1" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || die "version must be X.Y.Z (got '$1')"; }

if [ "${1:-}" = "--check" ]; then
  tag="${2:?usage: changelog-section.sh --check vX.Y.Z}"
  v="${tag#v}"; valid "$v"; tag="v$v"
  [ "$(git cat-file -t "refs/tags/$tag" 2>/dev/null)" = tag ] \
    || die "$tag is not an annotated tag here (git fetch --force origin refs/tags/$tag:refs/tags/$tag)"
  if [ -n "${3:-}" ]; then
    [ "$(git rev-parse "refs/tags/$tag^{commit}")" = "$(git rev-parse "$3^{commit}")" ] \
      || die "$tag no longer points at $3, the commit this run checked out (the tag was moved)"
  fi
  want=$(git show "refs/tags/$tag^{commit}:CHANGELOG.md" 2>/dev/null) || die "the commit $tag tags has no CHANGELOG.md"
  want=$(printf '%s\n' "$want" | section "$v" | norm)
  got=$(git tag -l --format='%(contents)' "$tag" \
    | sed -E '/^-----BEGIN (PGP SIGNATURE|SSH SIGNATURE|SIGNED MESSAGE)-----/,$d' | norm)
  if [ "$got" != "$want" ]; then
    echo "changelog-section: $tag's annotation differs from its CHANGELOG.md section (- tag, + changelog):" >&2
    diff -u <(printf '%s\n' "$got") <(printf '%s\n' "$want") | tail -n +3 >&2 || true
    echo "Re-tag from the changelog: RELEASING.md, \"If a changelog check fails\"." >&2
    exit 1
  fi
  echo "$tag annotation matches CHANGELOG.md"
  exit 0
fi

v="${1:?usage: changelog-section.sh X.Y.Z | --check vX.Y.Z}"
v="${v#v}"; valid "$v"
root=$(cd "$(dirname "$0")/.." && pwd)
section "$v" < "${CHANGELOG:-$root/CHANGELOG.md}"
