#!/usr/bin/env bash
# The native install's own records in its root-owned settings directory (/etc/mdmesh), for install/install-native.sh.
# Source this file; do not execute it. Root reads these files itself: the directory is root's, so no other account can
# plant a link there.

# mdm_envfile_param KEY: KEY's value in the systemd EnvironmentFile on stdin, as systemd reads it: lines starting with #
# or ; are comments, the last KEY= line wins, and the CR of a CRLF line, whitespace around the key, the "=" and the
# value, and one pair of matching quotes around the value are dropped. A value that runs on (a quote not closed on its
# line, or a trailing \) is not followed: the lines it runs over are skipped, never read as KEY= lines of their own, and
# its first line is returned as it stands, for the caller's check to refuse. (Escapes inside a value are not undone
# either.) Prints nothing when KEY is not set. The file is parsed, never sourced.
mdm_envfile_param() {
  local line k v="" raw q="" cont=0
  while IFS= read -r line || [ -n "$line" ]; do   # also a last line without a newline, as systemd reads it
    line=${line%$'\r'}
    if [ -n "$q" ]; then case "$line" in *"$q"*) q="" ;; esac; continue; fi       # inside an open quote
    if [ "$cont" = 1 ]; then case "$line" in *\\) ;; *) cont=0 ;; esac; continue; fi   # after a trailing \
    line=${line#"${line%%[![:space:]]*}"}
    case "$line" in '#'*|';'*) continue ;; *=*) ;; *) continue ;; esac
    k=${line%%=*}; k=${k%"${k##*[![:space:]]}"}
    raw=${line#*=}; raw=${raw#"${raw%%[![:space:]]*}"}; raw=${raw%"${raw##*[![:space:]]}"}
    case "$raw" in \"*|\'*) case "${raw:1}" in *"${raw:0:1}"*) ;; *) q=${raw:0:1} ;; esac ;; esac
    if [ -z "$q" ]; then case "$raw" in *\\) cont=1 ;; esac; fi
    [ "$k" = "$1" ] || continue
    v=$raw
    case "$v" in \"*\"|\'*\') v=${v:1:${#v}-2} ;; esac
  done
  printf '%s' "$v"
}

# mdm_installed_version DIR: the release the native install in DIR (/etc/mdmesh) runs, as the install itself recorded
# it; nothing when it cannot tell. Never the clone's tag: the clone can move ahead of the install (an update stopped
# before it changed anything, a git pull), and only a completed run changes what the install runs.
#   DIR/installed-version: written by install-native.sh as the very last step of a run that succeeded, so a run that
#     stops or fails leaves the release before it. When the file is there it is the answer (empty: not known, as when
#     the clone had no release tag). An older installer (0.5.1 or earlier) run over it, a native rollback, leaves it as
#     it was: it names the newer release until this installer's next run. Cosmetic; accepted.
#   DIR/supervisor.env CURRENT_VERSION: installs from before that record have only this. It is written late in a run,
#     but before the run ends, so it is the fallback only, and it is read once: the installer records it as
#     installed-version as soon as a run over such an install is confirmed, before it changes anything.
#     0.0.0 is the installer's "untagged" placeholder.
#   Installs from before v0.3.2 kept supervisor.env in the service account's tree, which root must not read as itself:
#     nothing (the caller shows "?").
# Only a release-shaped value (X.Y.Z, optional -prerelease) is printed. Files are read only when they are regular files,
# and only their first 4 KiB (a FIFO or a link to a device must not hang or flood the run).
mdm_installed_version() {
  local dir=$1 v=""
  if [ -e "$dir/installed-version" ] || [ -L "$dir/installed-version" ]; then
    if [ -f "$dir/installed-version" ] && [ ! -L "$dir/installed-version" ]; then
      IFS= read -r v < <(head -c 4096 -- "$dir/installed-version") || true
      v=${v%$'\r'}
    fi
  elif [ -f "$dir/supervisor.env" ] && [ ! -L "$dir/supervisor.env" ]; then
    v=$(head -c 65536 -- "$dir/supervisor.env" | mdm_envfile_param CURRENT_VERSION)
    [ "$v" != 0.0.0 ] || v=""
  fi
  [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || return 0
  printf '%s\n' "$v"
}

