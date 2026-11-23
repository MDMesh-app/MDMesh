#!/usr/bin/env python3
"""The third-party components MDMesh ships, read from the built artifacts, against the committed inventory.

Each artifact carries its own list (docs/licenses/policy.md): the server WAR's META-INF/THIRD-PARTY.txt
(license-maven-plugin), the console's licenses/THIRD-PARTY.json (Vite's build.license) and the agent APK's
assets/licenses/third-party.json (Licensee). docs/licenses/inventory/{server,web,agent}.tsv keeps those lists without
versions (component, then licence), so it changes only when a component comes or goes or changes licence.

  scripts/licenses/inventory.py --war WAR --web DIST --apk APK
      fail when an artifact lacks LICENSE, NOTICE or its list, or when the WAR holds a jar its list leaves out (or the
      list names one it doesn't hold); warn, without failing, where the lists and the inventory differ
  scripts/licenses/inventory.py --write --war WAR --web DIST --apk APK
      the same checks, then rewrite the inventory from the artifacts

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

INVENTORY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "licenses", "inventory")
# A THIRD-PARTY.txt entry: "(Licence) [(Licence) ...]Name (group:artifact:version - url)".
SERVER_LINE = re.compile(r"^\s+((?:\([^()]+\) )+)(.*) \(([^:\s]+):([^:\s]+):(\S+) - .*\)$")

errors = []


def error(message):
    errors.append(message)
    print(f"::error::{message}")


def server(war):
    """group:artifact -> licence, from the WAR's THIRD-PARTY.txt, checked against the jars in WEB-INF/lib."""
    with zipfile.ZipFile(war) as z:
        names = set(z.namelist())
        for f in ("META-INF/LICENSE", "META-INF/NOTICE", "META-INF/THIRD-PARTY.txt"):
            if f not in names:
                error(f"{war} has no {f}")
        if "META-INF/THIRD-PARTY.txt" not in names:
            return {}
        listed, jars = {}, set()
        for line in z.read("META-INF/THIRD-PARTY.txt").decode().splitlines():
            m = SERVER_LINE.match(line)
            if m:
                licences, _, group, artifact, version = m.groups()
                listed[f"{group}:{artifact}"] = " OR ".join(re.findall(r"\(([^()]+)\)", licences))
                jars.add(f"{artifact}-{version}.jar")
        for name in sorted(n for n in names if re.fullmatch(r"WEB-INF/lib/[^/]+\.jar", n)):
            jar = name.rsplit("/", 1)[1]
            if jar in jars:
                jars.discard(jar)
                continue
            # MDMesh's own modules (common, the plugins...): groupId com.hmdm, no third-party entry.
            with zipfile.ZipFile(io.BytesIO(z.read(name))) as inner:
                if not any(n.startswith("META-INF/maven/com.hmdm") for n in inner.namelist()):
                    error(f"{war}: {name} is not in META-INF/THIRD-PARTY.txt (server/src/license/THIRD-PARTY.ftl)")
        for jar in sorted(jars):
            error(f"{war}: META-INF/THIRD-PARTY.txt lists {jar}, which WEB-INF/lib doesn't hold")
        return listed


def web(dist):
    """package -> licence, from the console build's licenses/THIRD-PARTY.json."""
    for f in ("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY.json"):
        if not os.path.isfile(os.path.join(dist, "licenses", f)):
            error(f"{dist} has no licenses/{f}")
    try:
        with open(os.path.join(dist, "licenses", "THIRD-PARTY.json")) as f:
            return {p["name"]: p.get("identifier", "") for p in json.load(f)}
    except FileNotFoundError:
        return {}


def agent(apk):
    """group:artifact -> licence, from the APK's assets/licenses/third-party.json."""
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
            listed[f"{a['groupId']}:{a['artifactId']}"] = " OR ".join(ids)
        return listed


def tsv(listed):
    return "".join(f"{name}\t{licence}\n" for name, licence in sorted(listed.items()))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--war", required=True)
    parser.add_argument("--web", required=True, help="the console build's dist/ directory")
    parser.add_argument("--apk", required=True)
    parser.add_argument("--write", action="store_true", help="rewrite docs/licenses/inventory from the artifacts")
    args = parser.parse_args()

    for name, read, artifact in (("server", server, args.war), ("web", web, args.web), ("agent", agent, args.apk)):
        if not os.path.exists(artifact):
            error(f"{artifact} not found: build the {name} first (docs/licenses/policy.md)")
            continue
        listed = read(artifact)
        path = os.path.join(INVENTORY, f"{name}.tsv")
        new = tsv(listed)
        if args.write:
            os.makedirs(INVENTORY, exist_ok=True)
            with open(path, "w") as f:
                f.write(new)
            continue
        try:
            with open(path) as f:
                old = f.read()
        except FileNotFoundError:
            old = ""
        if old != new:
            rel = os.path.relpath(path, os.path.join(INVENTORY, "..", "..", ".."))
            print(
                f"::warning file={rel}::the {name} build's third-party components differ from {rel}: check the new "
                "ones against docs/licenses/policy.md, then update it (scripts/licenses/inventory.py --write)"
            )
            sys.stdout.writelines(difflib.unified_diff(old.splitlines(True), new.splitlines(True), rel, "build"))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
