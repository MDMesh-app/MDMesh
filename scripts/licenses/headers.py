#!/usr/bin/env python3
"""Change notices on the files MDMesh kept from Headwind MDM (Apache-2.0 section 4(b) and (c)).

docs/licenses/upstream-files.tsv lists every file kept from upstream: its path, its blob at the upstream commit MDMesh
was taken from, whether it carried the upstream copyright header there, and whether it can carry a notice. A kept file
that differs from upstream must say MDMesh changed it, and one that had the header must keep it.

  scripts/licenses/headers.py check
      fail when a changed upstream file lacks the notice, a file lost the upstream header, a listed file is gone, or
      a file that isn't listed carries the upstream header (list it as derived from its upstream file and keep the
      header; only a file MDMesh wrote itself takes the MDMesh header)
  scripts/licenses/headers.py mark PATH...
      add the notice to PATH: a line under the upstream copyright line, or a comment at the top of a file that never
      had the header
  scripts/licenses/headers.py manifest UPSTREAM_GIT_DIR
      rewrite docs/licenses/upstream-files.tsv from an hmdm-server clone (full history, treeless is enough) that has
      the commit below; only needed if the list itself is in question

Messages are GitHub Actions annotations (::error file=...::), which read as plain lines elsewhere.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
MANIFEST = "docs/licenses/upstream-files.tsv"
MARK = "scripts/licenses/headers.py mark"
UPSTREAM = "daed2099c9d50e68248ced38135180ffea6902ce"
ANCHOR = "Copyright (C) 2019 Headwind Solutions LLC"
# The notice in a file that has the upstream header, under its copyright line.
MODIFIED = "Modified by the MDMesh contributors."
# The comment at the top of a changed file that never had the header (upstream left it without one).
DERIVED = "Derived from Headwind MDM (Apache-2.0); modified by the MDMesh contributors."
# What counts as the notice: either of ours, or the ported agent files' "Modifications Copyright (C) 2026 the MDMesh
# contributors".
NOTICE = re.compile(r"[Mm]odified by the MDMesh contributors|Modifications Copyright \(C\) \d+ the MDMesh contributors")
# Kept files that carry no in-file notice, and why.
EXEMPT = {
    "NOTICE": "rewritten for MDMesh",
    "README.md": "rewritten for MDMesh",
    "plugins/audit/src/main/webapp/i18n/en_US.json": "JSON has no comments; this list is its notice",
    "plugins/push/src/main/webapp/i18n/vi_VN.json": "JSON has no comments; this list is its notice",
}
# The agent files ported from hmdm-android (NOTICE): not copies of an upstream file, so no blob to compare.
PORTED = "ported from hmdm-android"
# A file derived from an upstream one under another path (moved, copied, ported within the server): "derived from
# <upstream path>" in the blob column. Like a ported file, it must say MDMesh changed it.
DERIVED_FROM = "derived from "
# The Apache notice an upstream header carries with its copyright line (4(c) keeps both).
APACHE = "Licensed under the Apache License"
# The comment syntax of a file that never had the header, by extension.
COMMENTS = {".java": "// {}", ".js": "// {}", ".sql": "-- {}", ".example": "# {}", ".xml": "<!-- {} -->",
            ".sh": "# {}", ".bat": "REM {}"}
# A first line that must stay first: an XML declaration, a shebang, a batch file's @echo off.
FIRST = ("<?xml", "#!", "@echo off")


def git(*args, cwd=ROOT, stdin=None):
    return subprocess.run(["git", *args], cwd=cwd, input=stdin, capture_output=True, text=True, check=True).stdout


def read_manifest():
    rows = {}
    with open(os.path.join(ROOT, MANIFEST)) as f:
        for number, line in enumerate(f, 1):
            if line.startswith("#") or not line.strip():
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) != 4 or fields[2] not in ("yes", "no"):
                print(f"::error file={MANIFEST},line={number}::{MANIFEST}:{number}: not a row: four tab-separated "
                      "columns (path, upstream blob or 'derived from <path>', yes or no, note), the note may be empty")
                sys.exit(1)
            path, blob, header, note = fields
            rows[path] = (blob, header == "yes", note)
    return rows


def check():
    rows = read_manifest()
    errors = []

    def error(path, message):
        errors.append(path)
        print(f"::error file={path}::{path}: {message}")

    present = [p for p in rows if os.path.isfile(os.path.join(ROOT, p))]
    for path in sorted(set(rows) - set(present)):
        error(MANIFEST, f"lists {path}, which is gone. If it moved, change its path in the list (it is still a file "
                        "kept from upstream, and keeps its header); remove the row only if the file was deleted")
    blobs = dict(zip(present, git("hash-object", "--stdin-paths", stdin="\n".join(present) + "\n").split()))
    for path in present:
        blob, header, note = rows[path]
        with open(os.path.join(ROOT, path), encoding="utf-8", errors="replace") as f:
            text = f.read()
        if header and ANCHOR not in text:
            error(path, f"lost the upstream copyright line ({ANCHOR}); Apache-2.0 4(c) requires it: put it back")
        elif header and APACHE not in text:
            error(path, f"lost the upstream header's licence notice ({APACHE}...); Apache-2.0 4(c) requires it: put "
                        "it back")
        if blobs[path] != blob and note == "" and not NOTICE.search(text):
            error(path, f"differs from upstream but doesn't say MDMesh changed it: {MARK} {path}")
    # This script and its test name the line; nothing else outside the list may.
    carriers = git("grep", "--untracked", "-l", "-F", ANCHOR, "--", ".", ":!scripts/licenses", ":!tests/licenses")
    for path in carriers.splitlines():
        if path not in rows:
            error(path, f"carries the upstream copyright header but isn't in {MANIFEST}. If it comes from upstream "
                        "(moved, copied or ported from a Headwind MDM file), keep the header and add the row "
                        f"'{path}<TAB>{DERIVED_FROM}<upstream path><TAB>yes<TAB>', then {MARK} {path}. Only a file "
                        "MDMesh wrote itself takes the MDMesh header instead (docs/licenses/policy.md)")
    if errors:
        print(f"{len(errors)} problem(s); docs/licenses/policy.md explains the notices.")
        return 1
    print(f"Every file kept from upstream ({len(present)}) carries the notices it needs.")
    return 0


def mark(paths):
    for path in paths:
        full = os.path.join(ROOT, path)
        # newline="": keep the file's own line endings (a CRLF batch file stays CRLF).
        with open(full, encoding="utf-8", newline="") as f:
            text = f.read()
        eol = "\r\n" if "\r\n" in text else "\n"
        lines = text.split(eol)
        if any(NOTICE.search(line) for line in lines):
            print(f"{path}: already says MDMesh changed it")
            continue
        anchor = next((i for i, line in enumerate(lines) if ANCHOR in line), None)
        if anchor is not None:
            prefix = lines[anchor][: lines[anchor].index("Copyright")]
            lines.insert(anchor + 1, prefix + MODIFIED)
        else:
            ext = os.path.splitext(path)[1]
            if ext not in COMMENTS:
                sys.exit(f"{path}: no comment syntax known for {ext or 'this file'}: add it to EXEMPT, with the reason")
            at = 1 if lines[0].startswith(FIRST) else 0
            lines.insert(at, COMMENTS[ext].format(DERIVED))
        with open(full, "w", encoding="utf-8", newline="") as f:
            f.write(eol.join(lines))
        print(f"{path}: marked")
    return 0


def manifest(upstream):
    up = {}
    for line in git("ls-tree", "-r", UPSTREAM, cwd=upstream).splitlines():
        meta, path = line.split("\t", 1)
        up[path] = meta.split()[2]
    kept = [p for p in git("ls-files").splitlines() if p in up]
    batch = "".join(f"{up[p]}\n" for p in kept)
    raw = subprocess.run(["git", "cat-file", "--batch"], cwd=upstream, input=batch.encode(), capture_output=True,
                         check=True).stdout
    headered, i = set(), 0
    for path in kept:
        nl = raw.index(b"\n", i)
        size = int(raw[i:nl].split()[2])
        if ANCHOR.encode() in raw[nl + 1:nl + 1 + size]:
            headered.add(path)
        i = nl + 1 + size + 1
    ported = sorted(p for p in git("grep", "-l", "-F", ANCHOR, "--", "agent-android").splitlines())
    # Rows for files derived from upstream ones under another path are written by hand: keep them.
    with open(os.path.join(ROOT, MANIFEST)) as f:
        derived = [line for line in f if f"\t{DERIVED_FROM}" in line]
    with open(os.path.join(ROOT, MANIFEST), "w") as f:
        f.write(
            "# The files MDMesh kept from Headwind MDM (https://github.com/h-mdm/hmdm-server at upstream commit\n"
            f"# {UPSTREAM}), and the agent files ported from hmdm-android. Columns: path, the file's\n"
            "# blob at that commit (or how it came), whether it carried the upstream copyright header there, and why\n"
            "# it carries no in-file notice if it doesn't. A listed file that differs from that blob says MDMesh\n"
            "# changed it. A file derived from an upstream one under another path has 'derived from <path>' in the\n"
            "# blob column (a row added by hand) and says MDMesh changed it.\n"
            "# Written by scripts/licenses/headers.py manifest; checked by scripts/licenses/headers.py check.\n"
        )
        for path in kept:
            f.write(f"{path}\t{up[path]}\t{'yes' if path in headered else 'no'}\t{EXEMPT.get(path, '')}\n")
        for path in ported:
            f.write(f"{path}\t{PORTED}\tyes\t\n")
        f.writelines(derived)
    print(f"{MANIFEST}: {len(kept)} kept files, {len(headered)} with the upstream header, {len(ported)} ported")
    return 0


def main(argv):
    if argv[:1] == ["check"] and len(argv) == 1:
        return check()
    if argv[:1] == ["mark"] and len(argv) > 1:
        return mark(argv[1:])
    if argv[:1] == ["manifest"] and len(argv) == 2:
        return manifest(argv[1])
    sys.exit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
