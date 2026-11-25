#!/usr/bin/env bash
#
# Lists what an MDMesh image holds, for the record each release attaches (release.yml's packages job): every OS
# package with its version, the source package it was built from and, where the distribution records it, its licence;
# then the runtimes that don't come as packages (the JDK and Tomcat, Node, Caddy). NOTICE's source pointers and
# written offer resolve against this list (docs/licenses/images.md).
#
# Usage: scripts/licenses/image-packages.sh IMAGE_REF > list.tsv
#
# Runs only the image's own tools (apk or dpkg-query, and the runtimes' --version), in a throwaway container with no
# network. Needs a Docker daemon and the image pulled.
set -euo pipefail
[ $# -eq 1 ] || { sed -n '/^# Usage:/p' "$0" | sed 's/^# //' >&2; exit 2; }
REF=$1

# Inside the image: tab-separated package, version, source package, source version, licence.
# Alpine's `apk list -I` prints "name-version arch {origin} (licence) [installed]"; the runner's sed turns that into
# the same columns (BusyBox's sed in the image may not).
# shellcheck disable=SC2016  # expanded inside the container
LIST='
if [ -n "$(command -v apk)" ]; then
  apk list -I
elif [ -n "$(command -v dpkg-query)" ]; then
  dpkg-query -W -f="\${Package}\t\${Version}\t\${source:Package}\t\${source:Version}\t-\n"
else
  echo "no package manager found" >&2; exit 1
fi
echo "#"
[ -n "$(command -v java)" ] && java -version 2>&1 | sed -n 2p | sed "s/^/# runtime: /"
[ -f /usr/local/tomcat/lib/catalina.jar ] && java -cp /usr/local/tomcat/lib/catalina.jar \
  org.apache.catalina.util.ServerInfo | sed -n "s/^Server version: */# runtime: /p"
[ -n "$(command -v node)" ] && echo "# runtime: Node.js $(node --version)"
[ -x /usr/bin/caddy ] && /usr/bin/caddy version | sed "s/^/# runtime: Caddy /"
true
'

printf '# %s\n' "What ${REF} holds (scripts/licenses/image-packages.sh)."
printf '# %s\n' "package	version	source package	source version	licence ('-': see /usr/share/doc/<package>/copyright)"
# The licence may hold parentheses of its own ("(MIT AND (GPL-2.0-or-later OR LGPL-3.0-only))"): greedy up to the last
# ") [installed]". Every line that isn't a comment must come out as exactly five columns, or the list fails.
docker run --rm --network none --entrypoint sh "$REF" -c "$LIST" \
  | sed -E 's/^(.+)-([^-]+-r[0-9]+) [^ ]+ \{([^}]*)\} \((.*)\) \[installed\]$/\1\t\2\t\3\t\2\t\4/' \
  | awk -F '\t' '!/^#/ && NF != 5 { print "image-packages.sh: not a package line: " $0 | "cat 1>&2"; bad = 1 }
                 { print } END { exit bad }'
