#!/usr/bin/env sh
# semver X.Y.Z → monotonic Android versionCode (major*10000 + minor*100 + patch).
set -eu
v="${1:?usage: version.sh X.Y.Z}"
IFS=. read -r MA MI PA <<INNER
$v
INNER
echo $(( MA*10000 + MI*100 + PA ))
