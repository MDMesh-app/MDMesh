#!/usr/bin/env python3
"""The third-party components MDMesh ships, read from the built artifacts, against the licence policy and the
committed inventory.

Each artifact carries its own list (docs/licenses/policy.md): the server WAR's META-INF/THIRD-PARTY.txt
(license-maven-plugin), the console's licenses/THIRD-PARTY.json (Vite's build.license) and the agent APK's
assets/licenses/third-party.json (Licensee). docs/licenses/inventory/{server,web,agent}.tsv keeps those lists without
versions (component, then the licence MDMesh uses it under), so it changes only when a component comes or goes or
changes licence.

  scripts/licenses/inventory.py [--war WAR] [--web DIST] [--apk APK]
      fail when an artifact lacks LICENSE, NOTICE or its list; when the WAR holds a jar its list leaves out (or the
      list names one it doesn't hold); or when a listed component is under a licence docs/licenses/allowed.txt doesn't
      allow, or under several with no recorded choice. Warn, without failing, where the lists and the inventory differ.
      Each option checks one part, so each build can check its own.
  scripts/licenses/inventory.py --write [--war WAR] [--web DIST] [--apk APK]
      the same checks, then, if they all pass, rewrite those parts' inventory from the artifacts

Messages are GitHub Actions annotations (::error:: and ::warning::), which read as plain lines elsewhere.
"""
import argparse
import difflib
import io
import json
import os
import re
import sys
import zipfile

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
INVENTORY = "docs/licenses/inventory"
ALLOWED = "docs/licenses/allowed.txt"
ELECTIONS = "docs/licenses/elections.tsv"
# Where each part records which licence it uses a multi-licensed component under.
RECORD = {
    "server": "server/src/license/override-THIRD-PARTY.properties",
    "web": ELECTIONS,
    "agent": ELECTIONS,
}
# A THIRD-PARTY.txt entry: "(Licence) [(Licence) ...]Name (group:artifact:version - url)".
SERVER_LINE = re.compile(r"^\s+((?:\([^()]+\) )+)(.*) \(([^:\s]+):([^:\s]+):(\S+) - .*\)$")
# MDMesh's own modules in the WAR (common, the plugins, a fork's plugins): no third-party entry (server/pom.xml).
OWN_GROUPS = re.compile(r"META-INF/maven/com\.(hmdm|mdmesh)[./]")

errors = []


def error(message):
    errors.append(message)
    print(f"::error::{message}")


def read_lines(path):
    with open(os.path.join(ROOT, path)) as f:
        return [line.split("#", 1)[0].rstrip("\n") for line in f]


def server(war):
    """group:artifact -> [licences offered], from the WAR's THIRD-PARTY.txt, checked against WEB-INF/lib."""
    with zipfile.ZipFile(war) as z:
        names = set(z.namelist())
        for f in ("META-INF/LICENSE", "META-INF/NOTICE", "META-INF/THIRD-PARTY.txt"):
            if f not in names:
                error(f"{war} has no {f}")
        if "META-INF/THIRD-PARTY.txt" not in names:
            return {}
        listed, stems = {}, set()
        for line in z.read("META-INF/THIRD-PARTY.txt").decode().splitlines():
            m = SERVER_LINE.match(line)
            if m:
                licences, _, group, artifact, version = m.groups()
                listed[f"{group}:{artifact}"] = re.findall(r"\(([^()]+)\)", licences)
                stems.add(f"{artifact}-{version}")
        unmatched = set(stems)
        for name in sorted(n for n in names if re.fullmatch(r"WEB-INF/lib/[^/]+\.jar", n)):
            jar = name.rsplit("/", 1)[1][: -len(".jar")]
            # artifact-version.jar, or artifact-version-classifier.jar
            stem = next((s for s in stems if jar == s or jar.startswith(s + "-")), None)
            if stem:
                unmatched.discard(stem)
                continue
            with zipfile.ZipFile(io.BytesIO(z.read(name))) as inner:
                if not any(OWN_GROUPS.match(n) for n in inner.namelist()):
                    error(f"{war}: {name} is not in META-INF/THIRD-PARTY.txt. If it is a module of this repository "
                          "(a fork's plugin, say), give it a groupId under com.hmdm or com.mdmesh "
                          "(plugins/fork/README.md); if it is third-party, server/src/license/THIRD-PARTY.ftl lists "
                          "the jars the plugin can't read")
        for stem in sorted(unmatched):
            error(f"{war}: META-INF/THIRD-PARTY.txt lists {stem}.jar, which WEB-INF/lib doesn't hold")
        return listed


def offered(expression):
    """The licences an SPDX expression offers a choice of: "(WTFPL OR MIT)" -> [WTFPL, MIT]. One with an AND
    stays whole, which no allowed licence matches, so it fails: a person has to read it."""
    expression = expression.strip()
    if re.search(r"\bAND\b", expression):
        return [expression]
    while expression.startswith("(") and expression.endswith(")"):
        expression = expression[1:-1].strip()
    return [part.strip() for part in expression.split(" OR ")] if expression else []


def web(dist):
    """package -> [licences offered], from the console build's licenses/THIRD-PARTY.json."""
    for f in ("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY.json"):
        if not os.path.isfile(os.path.join(dist, "licenses", f)):
            error(f"{dist} has no licenses/{f}")
    try:
        with open(os.path.join(dist, "licenses", "THIRD-PARTY.json")) as f:
            return {p["name"]: offered(p.get("identifier", "")) for p in json.load(f)}
    except FileNotFoundError:
        return {}


def agent(apk):
    """group:artifact -> [licences offered], from the APK's assets/licenses/third-party.json."""
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        for f in ("LICENSE.txt", "NOTICE.txt", "third-party.json"):
            if f"assets/licenses/{f}" not in names:
                error(f"{apk} has no assets/licenses/{f}")
        if "assets/licenses/third-party.json" not in names:
            return {}
        listed = {}
        for a in json.loads(z.read("assets/licenses/third-party.json")):
            ids = [x["identifier"] for x in a.get("spdxLicenses", [])]
            ids += [x["name"] for x in a.get("unknownLicenses", [])]
            listed[f"{a['groupId']}:{a['artifactId']}"] = ids
        return listed


def used(part, listed, allowed, elections):
    """component -> the one licence MDMesh uses it under; an error for each that has none, or one not allowed."""
    out = {}
    for name, licences in sorted(listed.items()):
        if not licences:
            error(f"{part}: {name} names no licence (docs/licenses/policy.md)")
            continue
        if len(licences) > 1:
            choice = elections.get((part, name))
            if choice is None or choice[0] != licences:
                error(f"{part}: {name} is offered under {' or '.join(licences)}, and {RECORD[part]} records no choice "
                      "for exactly that offer: record the one MDMesh uses (docs/licenses/policy.md)")
                continue
            if choice[1] not in licences:
                error(f"{part}: {RECORD[part]} chooses {choice[1]} for {name}, which isn't one of the licences it is "
                      f"offered under ({' or '.join(licences)})")
                continue
            licences = [choice[1]]
        if licences[0] not in allowed:
            error(f"{part}: {name} is under {licences[0]}, which {ALLOWED} doesn't allow (docs/licenses/policy.md)")
            continue
        out[name] = licences[0]
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--war", help="the server's launcher.war")
    parser.add_argument("--web", help="the console build's dist/ directory")
    parser.add_argument("--apk", help="the agent APK")
    parser.add_argument("--write", action="store_true", help="rewrite those parts' inventory from the artifacts")
    args = parser.parse_args()
    parts = [(n, r, a) for n, r, a in (("server", server, args.war), ("web", web, args.web), ("agent", agent, args.apk))
             if a]
    if not parts:
        parser.error("name at least one artifact: --war, --web or --apk")

    allowed = {line.strip() for line in read_lines(ALLOWED) if line.strip()}
    elections = {}
    for line in read_lines(ELECTIONS):
        if line.strip():
            part, name, offer, choice = line.split("\t")
            elections[(part, name)] = (offered(offer), choice)

    staged = {}
    for name, read, artifact in parts:
        if not os.path.exists(artifact):
            error(f"{artifact} not found: build the {name} first (docs/licenses/policy.md)")
            continue
        new = "".join(f"{c}\t{lic}\n" for c, lic in used(name, read(artifact), allowed, elections).items())
        rel = f"{INVENTORY}/{name}.tsv"
        path = os.path.join(ROOT, rel)
        if args.write:
            staged[path] = new
            continue
        try:
            with open(path) as f:
                old = f.read()
        except FileNotFoundError:
            old = ""
        if old != new:
            print(
                f"::warning file={rel}::the {name} build's third-party components differ from {rel}: check the new "
                "ones against docs/licenses/policy.md, then update it (scripts/licenses/inventory.py --write)"
            )
            sys.stdout.writelines(difflib.unified_diff(old.splitlines(True), new.splitlines(True), rel, "build"))
    if errors:
        return 1
    for path, new in staged.items():
        with open(path, "w") as f:
            f.write(new)
    return 0


if __name__ == "__main__":
    sys.exit(main())
