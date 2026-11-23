#!/usr/bin/env python3
"""The releases that fixed security issues, from CHANGELOG.md: the signed manifest's securityReleases.

A version is a security release if and only if its `## vX.Y.Z` section has a Security heading: a line of its own,
"Security", optionally "Security (agent 0.5.0)" or "Security: …", the rule the console's release notes use
(web/src/whatsnew/changelog.ts LABEL, web/src/data/releaseNotes.ts isSecurityGroup). RELEASING.md documents it.
Lines inside fenced code (```) are never headings; CHANGELOG.md has none, and web/src/data/releaseNotes.test.ts keeps
it that way, since the console's notes parser doesn't read fences.

  release/security-releases.py [--upto X.Y.Z] [CHANGELOG]
      print the list as JSON, oldest first (with --upto, only versions up to and including X.Y.Z)
  release/security-releases.py --check manifest.json [CHANGELOG]
      fail unless the manifest's securityReleases is exactly the list for its version

build-manifest.sh runs this script (--upto VERSION) for the manifest's list, so it is always derived from the changelog.
"""
import json
import os
import re
import sys

SECTION = re.compile(r"^## v(\d+\.\d+\.\d+)(?:\s|$)")
# A heading line as the console reads it: markdown heading marks, with up to 3 spaces before them, are dropped
# (releaseNotes.ts MD_HEADING), and what is left must start at column 0 (an indented "Security" is list text).
HEADING_MARKS = re.compile(r"^\s{0,3}#{1,6}\s+")
SECURITY = re.compile(r"^Security(?: \([^)]*\))?(?::\s*.*)?$")
VERSION = re.compile(r"^\d+\.\d+\.\d+$")
FENCE = re.compile(r"^\s{0,3}(```|~~~)")

DEFAULT_CHANGELOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "CHANGELOG.md")


def key(v):
    return tuple(int(x) for x in v.split("."))


def releases(text, upto=None):
    """Every `## vX.Y.Z` section with a Security heading, oldest first; with `upto`, only versions <= upto."""
    found, current, fenced = set(), None, False
    for raw in text.splitlines():
        line = raw.rstrip("\r").rstrip()
        if FENCE.match(line):
            fenced = not fenced
            continue
        if fenced:
            continue
        m = SECTION.match(line)
        if m:
            current = m.group(1)
            continue
        if line.startswith("## "):
            current = None  # Unreleased, Older releases: not a release section
            continue
        heading = HEADING_MARKS.sub("", line)
        if current and not heading[:1].isspace() and SECURITY.match(heading):
            found.add(current)
    out = sorted(found, key=key)
    if upto is not None:
        out = [v for v in out if key(v) <= key(upto)]
    return out


def main(argv):
    def read(path):
        with open(path, encoding="utf-8") as f:
            return f.read()

    if argv[:1] == ["--check"]:
        if len(argv) not in (2, 3):
            sys.exit("usage: security-releases.py --check manifest.json [CHANGELOG]")
        manifest = json.loads(read(argv[1]))
        ver = manifest.get("version")
        if not isinstance(ver, str) or not VERSION.match(ver):
            sys.exit("security-releases: the manifest's version %r is not X.Y.Z" % (ver,))
        want = releases(read(argv[2] if len(argv) == 3 else DEFAULT_CHANGELOG), upto=ver)
        got = manifest.get("securityReleases")
        if got != want:
            sys.exit("security-releases: the manifest's securityReleases %s differ from CHANGELOG.md's %s (a release is a "
                     "security release if and only if its section has a Security heading: RELEASING.md)"
                     % (json.dumps(got), json.dumps(want)))
        print("securityReleases match CHANGELOG.md: %s" % json.dumps(want))
        return
    upto = None
    if argv[:1] == ["--upto"]:
        if len(argv) < 2 or not VERSION.match(argv[1]):
            sys.exit("usage: security-releases.py [--upto X.Y.Z] [CHANGELOG]")
        upto, argv = argv[1], argv[2:]
    if len(argv) > 1:
        sys.exit("usage: security-releases.py [--upto X.Y.Z] [CHANGELOG]")
    print(json.dumps(releases(read(argv[0] if argv else DEFAULT_CHANGELOG), upto=upto)))


if __name__ == "__main__":
    main(sys.argv[1:])
