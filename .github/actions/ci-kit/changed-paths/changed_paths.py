#!/usr/bin/env python3
"""Decide which named path filters a change touches.

Env in: FILTERS (lines "name: glob, glob"), FAIL_CLOSED (one glob per line),
        BASE (sha or empty), HEAD (sha), GITHUB_OUTPUT.
Outputs: matched (JSON name->bool), all (true/false), files (newline list).
"""
import json, os, re, subprocess

def glob_to_regex(glob: str) -> re.Pattern:
    out, i = "", 0
    while i < len(glob):
        c = glob[i]
        if glob.startswith("**/", i):
            out += "(?:.*/)?"; i += 3; continue
        if glob.startswith("**", i):
            out += ".*"; i += 2; continue
        if c == "*": out += "[^/]*"
        elif c == "?": out += "[^/]"
        else: out += re.escape(c)
        i += 1
    return re.compile(out)

def parse_filters(text: str) -> dict:
    filters = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"): continue
        name, _, globs = line.partition(":")
        filters[name.strip()] = [g.strip() for g in globs.split(",") if g.strip()]
    return filters

def evaluate(files, filters: dict, fail_closed: list) -> dict:
    if files is None:
        return {"matched": {k: True for k in filters}, "all": True, "files": []}
    closed = [glob_to_regex(g) for g in fail_closed]
    if any(r.fullmatch(f) for f in files for r in closed):
        return {"matched": {k: True for k in filters}, "all": True, "files": files}
    matched = {}
    for name, globs in filters.items():
        regs = [glob_to_regex(g) for g in globs]
        matched[name] = any(r.fullmatch(f) for f in files for r in regs)
    return {"matched": matched, "all": False, "files": files}

def _git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout

def changed_files(base: str, head: str):
    """Return changed paths between base and head, or None to fail closed.

    actions/checkout defaults to a depth-1 checkout of the PR merge commit, so
    neither the base sha nor the PR head sha is a local object. Fetch both by
    sha (GitHub allows reachable-sha fetches). If the head sha cannot be fetched,
    diff base against the checked-out HEAD instead: on a pull_request event that
    is the merge commit, whose diff from the base sha is exactly the PR's change.
    """
    if not base or set(base) == {"0"}:
        return None
    try:
        try:
            _git("fetch", "--no-tags", "--depth=1", "origin", base, head)
            target = head
        except subprocess.CalledProcessError:
            _git("fetch", "--no-tags", "--depth=1", "origin", base)
            target = "HEAD"
            print(f"::notice::head {head} not fetchable; diffing {base}..HEAD (merge commit)")
        out = _git("diff", "--name-only", base, target)
    except subprocess.CalledProcessError as e:
        print(f"::warning::could not diff {base}..{head}: {e.stderr.strip()}; failing closed")
        return None
    return [l for l in out.splitlines() if l]

def main():
    filters = parse_filters(os.environ.get("FILTERS", ""))
    fail_closed = [l.strip() for l in os.environ.get("FAIL_CLOSED", "").splitlines() if l.strip()]
    files = changed_files(os.environ.get("BASE", ""), os.environ.get("HEAD", "HEAD"))
    result = evaluate(files, filters, fail_closed)
    print(json.dumps(result, indent=2))
    with open(os.environ["GITHUB_OUTPUT"], "a") as fh:
        fh.write(f"matched={json.dumps(result['matched'])}\n")
        fh.write(f"all={'true' if result['all'] else 'false'}\n")
        fh.write(f"method={'fail-closed' if files is None else 'diff'}\n")
        fh.write("files<<EOF\n" + "\n".join(result["files"]) + "\nEOF\n")

if __name__ == "__main__":
    main()
