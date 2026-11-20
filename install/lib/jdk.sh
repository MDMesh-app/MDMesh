#!/usr/bin/env bash
# JDK 17 version rules for the native installer and CI (scripts/server-jvm-check.sh). Source this file; do not execute
# it.

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
