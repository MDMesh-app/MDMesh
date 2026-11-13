#!/usr/bin/env sh
# Emits manifest.json — the signed fleet-update contract.
# Args: VERSION CHANNEL SERVER_IMG WEB_IMG APK_FILE APK_VERSIONCODE APK_CHECKSUM PROTO_VERSION SERVER_REF WEB_REF SUPERVISOR_REF [WEB_ZIP]
#   SERVER_IMG / WEB_IMG: the :VERSION tags (components.serverImage / webImage, which every supervisor reads).
#   *_REF: the same images by digest, repo@sha256:<hex> (components.images: what a 0.5.0+ supervisor and the quick start
#   pull; a supervisor before 0.5.0 ignores the field).
#   WEB_ZIP: optional mdmesh-web.zip asset (components.webZip), used by host-Nginx installs to serve the web UI.
# Also records the SHA-256 of each file the quick start downloads from this release's tag, and of quickstart.sh itself
# (published as a release asset, so an operator can check it before running it: RELEASING.md), in files, and the commit
# (commit: what `setup.sh --update` checks the tag against). They are read from the checkout this runs in, which must be
# the tagged commit. Paths are relative to the repo root, wherever this runs from.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
python3 - "$ROOT" "$@" <<'PY'
import sys, json, hashlib, os, re
root = sys.argv[1]
if len(sys.argv) not in (13, 14):
    sys.exit("usage: build-manifest.sh VERSION CHANNEL SERVER_IMG WEB_IMG APK_FILE APK_VERSIONCODE APK_CHECKSUM "
             "PROTO_VERSION SERVER_REF WEB_REF SUPERVISOR_REF [WEB_ZIP]")
ver, ch, server_img, web_img, apk, apk_vc, apk_ck, proto, server_ref, web_ref, sup_ref = sys.argv[2:13]
web_zip = sys.argv[13] if len(sys.argv) == 14 else None
sha = lambda p: hashlib.sha256(open(p, "rb").read()).hexdigest()

# Each ref is repo@sha256:<64 hex>; server and web are the repositories of the :VERSION tags above, and the
# supervisor's sits beside them. supervisor/lib.js (imagePins), apply.sh and quickstart.sh read exactly this shape.
ref_re = re.compile(r"^([a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)+)@sha256:[0-9a-f]{64}$")
def pinned(ref, want):
    m = ref_re.match(ref)
    if not m: sys.exit("not a repo@sha256:<digest> reference: %r" % ref)
    if m.group(1) != want: sys.exit("%s is not in %s" % (ref, want))
    return ref
server_repo, web_repo = server_img.rsplit(":", 1)[0], web_img.rsplit(":", 1)[0]
if not server_repo.endswith("/mdmesh-server") or web_repo != server_repo[:-len("server")] + "web":
    sys.exit("unexpected image names %s %s" % (server_img, web_img))
images = {
    "server": pinned(server_ref, server_repo),
    "web": pinned(web_ref, web_repo),
    "supervisor": pinned(sup_ref, server_repo[:-len("server")] + "supervisor"),
}

# The files quickstart.sh downloads from raw.githubusercontent.com/<repo>/v<VERSION>/ (keep the two lists in step).
QUICKSTART_FILES = ["docker-compose.release.yml", "docker-compose.domain.yml", "install/sql/hmdm_init.en.sql",
                    "install/sql/post_seed.sql", "install/lib/db.sh",
                    "docker-compose.nginx.yml", "docker/nginx.conf.template"]
files = {f: sha(os.path.join(root, f)) for f in QUICKSTART_FILES}
# The release asset quickstart.sh (release.yml uploads this same file); the quick start itself never reads this entry.
files["quickstart.sh"] = sha(os.path.join(root, "quickstart.sh"))
# The tagged commit this runs in (setup.sh --update refuses a tag that points elsewhere).
import subprocess
commit = subprocess.run(["git", "-C", root, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
if not re.fullmatch(r"[0-9a-f]{40}", commit):
    sys.exit("build-manifest.sh must run in a git checkout (of the tagged commit): no commit found")

components = {
    "serverImage": server_img, "webImage": web_img,
    "images": images,
    "apk": {"file": apk.split("/")[-1], "versionCode": int(apk_vc),
            "sha256": sha(apk), "signatureChecksum": apk_ck},
}
if web_zip and os.path.exists(web_zip):
    components["webZip"] = {
        "file": os.path.basename(web_zip),
        "sha256": sha(web_zip),
    }

m = {
  "version": ver, "channel": ch, "commit": commit,
  "components": components,
  "compat": {"minAgentProtocol": "1.0", "maxAgentProtocol": proto},
  "files": files,
}
# indent=2, one key per line: quickstart.sh reads the fields it needs line by line (it has no JSON parser).
open("manifest.json", "w").write(json.dumps(m, indent=2) + "\n")
print("wrote manifest.json")
PY
