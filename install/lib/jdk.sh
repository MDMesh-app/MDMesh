#!/usr/bin/env bash
# JDK 17 version rules, and the Tomcat setenv.sh that sets the JVM flag they call for, for the native installer and CI
# (scripts/server-jvm-check.sh). Source this file; do not execute it.

# mdm_jdk17_base64_affected VERSION: succeeds when VERSION is a JDK 17 with the AVX-512 Base64 decoder bug of
# CVE-2026-114938 (OpenJDK): 17.0.6 to 17.0.21, fixed in 17.0.21.1. VERSION is what `javac -version` or java.version
# prints (17.0.21, 17.0.21.1), optionally as jdk-17.0.21+9. Only x86_64 CPUs with AVX-512 run the affected code, and
# -XX:-UseBASE64Intrinsics turns it off on any build.
mdm_jdk17_base64_affected() {
  local v=${1#jdk-} patch rest
  v=${v%%[+_ -]*}
  [[ "$v" =~ ^17\.0\.([0-9]+)(\.[0-9.]+)?$ ]] || return 1
  patch=$((10#${BASH_REMATCH[1]})); rest=${BASH_REMATCH[2]}
  [ "$patch" -ge 6 ] && { [ "$patch" -lt 21 ] || { [ "$patch" -eq 21 ] && [ -z "$rest" ]; }; }
}

# mdm_jdk17_base64_unaffected VERSION: succeeds when VERSION is a JDK 17 without that bug (17.0.0 to 17.0.5, or 17.0.21.1
# and later). Anything else, including other Java lines, fails.
mdm_jdk17_base64_unaffected() {
  local v=${1#jdk-}
  v=${v%%[+_ -]*}
  [[ "$v" =~ ^17\.0\.[0-9]+(\.[0-9.]+)?$ ]] && ! mdm_jdk17_base64_affected "$v"
}

# Tomcat's bin/setenv.sh that adds that flag (install/tomcat-setenv.sh) carries this line. The native installer rewrites
# a setenv.sh that has it, and leaves any other one alone.
MDM_SETENV_MARKER='# MDMesh-managed: the native installer rewrites this file. Remove this line to keep your own.'

# mdm_setenv_state FILE [READER...]: what the native installer does with Tomcat's bin/setenv.sh at FILE. Prints "absent"
# (write it), "ours" (a regular file with MDM_SETENV_MARKER: rewrite it) or "theirs" (an operator's file, a link or
# anything else: leave it). READER reads the file (the installer's svc_cat: as the service user); default cat.
mdm_setenv_state() {
  local f=$1 text
  shift
  [ $# -gt 0 ] || set -- cat
  if [ ! -e "$f" ] && [ ! -L "$f" ]; then echo absent; return 0; fi
  if [ -f "$f" ] && [ ! -L "$f" ] && text=$("$@" "$f" 2>/dev/null) && grep -qxF -- "$MDM_SETENV_MARKER" <<< "$text"; then
    echo ours
  else
    echo theirs
  fi
}
