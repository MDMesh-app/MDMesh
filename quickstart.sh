#!/usr/bin/env bash
# MDMesh quick start — deploy from PUBLISHED images, no clone and no build. Needs Docker, curl and
# OpenSSL 3.0+ (or older OpenSSL plus minisign) to check the release signature.
# Run from anywhere (the `bash <(...)` form keeps the prompts interactive). The README runs the latest release's copy
# (a release asset); DEPLOY.md, Option A, checks that copy against the signed manifest before running it, and its
# unchecked shortcut runs the copy at the latest release's tag:
#
#   bash <(curl -fsSL https://github.com/MDMesh-app/MDMesh/releases/latest/download/quickstart.sh)
#
# It first asks how phones will reach MDMesh (install/lib/modes.sh): through a Cloudflare Tunnel, on your own domain, or
# behind your own proxy. --tunnel, --domain or --proxy after the command answers that (as MDMESH_MODE does).
#
# Each question can be answered in advance with an environment variable (MDMESH_MODE, MDMESH_HOST, …) or an answers
# file (MDMESH_ANSWERS); MDMESH_NONINTERACTIVE=1 never asks, MDMESH_DRY_RUN=1 only checks the answers and prints the
# plan. The variables are listed in install/options.json and DEPLOY.md.
#
# It creates ./mdmesh, downloads the pull-only compose + seed, generates secrets, brings the stack
# up, and prints the console URL + a temporary admin password (you set your own on first login).
set -euo pipefail
# An exported CDPATH makes cd (here and in every child, e.g. `bash -c 'cd web && …'`) resolve a relative path against
# CDPATH's directories, not the current one — silently building or reading from a same-named directory elsewhere. Unset
# it for this script and its children.
unset CDPATH
# What this script makes is writable by its user only, whatever the login umask (Debian and Ubuntu give a regular user
# 0002): the directory check below refuses group- or world-writable paths, and the secrets are written mode 600 anyway.
umask 022

REPO="MDMesh-app/MDMesh"
IMAGE_OWNER_DEFAULT="mdmesh-app"

say()  { printf '\033[1;36m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
rand() { openssl rand -hex 24; }
# The latest published (non-prerelease, non-draft) release of owner/repo $1, without the "v" — the version this
# install pins. Release tags are always vX.Y.Z[-pre] (release.yml triggers on v*), and the caller downloads from
# the v<version> ref, so anything else counts as unresolved. Prints nothing when GitHub is unreachable,
# rate-limited or has no release.
latest_release() {
  local tag
  tag=$(curl -fsSL -m 20 "https://api.github.com/repos/$1/releases/latest" 2>/dev/null \
        | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/') || true
  if [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.+-]+)?$ ]]; then printf '%s\n' "${tag#v}"; fi
}

# >>> release verification >>>
# What the quick start trusts, and why. It installs only a published release whose manifest.json carries a valid minisign
# signature by the release key below, a copy of release/minisign.pub's key line (CI fails if the two differ). The signed
# manifest pins the server, web and supervisor images by digest and every file downloaded below by SHA-256, so a release
# asset, a tag or a raw file changed by anyone without the private signing key is refused. The trust root is this script
# itself, with the key in it, served over HTTPS as a release asset or from a release tag: whoever can change that
# copy could change the key too. Its SHA-256 is in its release's signed manifest (files), so an operator can check it
# before running it (DEPLOY.md). A fork with its own signing key sets MDMESH_RELEASE_PUBKEY to the key line (line 2) of
# its release/minisign.pub.
MDM_RELEASE_PUBKEY_DEFAULT="RWQbf0pIhddaWnDsFknIG4X325wHR05pUxEQJmuuQRdBlQm3zi8/Eqcd"

# mdm_openssl_can_verify: succeeds when this host's openssl can check a minisign signature: `pkeyutl -rawin` (Ed25519
# over raw data, OpenSSL 3.0+; 1.1.1 has no -rawin) and BLAKE2b-512.
mdm_openssl_can_verify() {
  case "$(openssl pkeyutl -help 2>&1)" in *-rawin*) ;; *) return 1 ;; esac
  openssl dgst -blake2b512 < /dev/null > /dev/null 2>&1
}

# mdm_verify_manifest PUBKEY FILE SIG: checks SIG against FILE and PUBKEY (see mdm_minisign_verify) with openssl when it
# can (mdm_openssl_can_verify), else with minisign when it is installed. Returns 0 when the signature is valid, 1 when it
# is not (why on stderr), 2 when this host has neither tool, so nothing was checked.
mdm_verify_manifest() {
  local pk=$1 file=$2 sig=$3 d rc
  if mdm_openssl_can_verify; then mdm_minisign_verify "$pk" "$file" "$sig"; return; fi
  command -v minisign > /dev/null 2>&1 || return 2
  d=$(mktemp -d) || return 1
  printf 'untrusted comment: MDMesh release key\n%s\n' "$pk" > "$d/k.pub"
  minisign -V -q -p "$d/k.pub" -m "$file" -x "$sig" > /dev/null 2>&1; rc=$?
  [ "$rc" = 0 ] || echo "minisign: the signature does not verify with the release key" >&2
  rm -f -- "$d/k.pub"; rmdir -- "$d" 2>/dev/null || true
  [ "$rc" = 0 ]
}

# mdm_minisign_verify PUBKEY FILE SIG: succeeds when SIG (a .minisig file) is a valid minisign signature of FILE by the
# public key PUBKEY (the base64 key line of a minisign .pub file), its trusted comment included. Needs only openssl
# 3.0+ (Ed25519 with -rawin, and BLAKE2b-512), so no minisign binary on the host; mdm_verify_manifest probes for that
# first. Only the prehashed format ("ED", the default since minisign 0.10 and what the release workflow makes) is
# accepted. Prints why to stderr when it fails.
mdm_minisign_verify() {
  local pk=$1 file=$2 sig=$3 d tc ok=1
  d=$(mktemp -d) || return 1
  # Ed25519 public key as DER (SubjectPublicKeyInfo): the fixed 12-byte prefix, then the 32 key bytes.
  if ! printf '%s' "$pk" | openssl base64 -d -A > "$d/pk" 2>/dev/null || [ "$(wc -c < "$d/pk")" -ne 42 ] \
     || [ "$(head -c 2 "$d/pk")" != Ed ]; then
    echo "the release public key is not a minisign Ed25519 key" >&2
  elif ! sed -n 2p "$sig" | openssl base64 -d -A > "$d/sig" 2>/dev/null || [ "$(wc -c < "$d/sig")" -ne 74 ]; then
    echo "the signature file is not a minisign signature" >&2
  elif [ "$(head -c 2 "$d/sig")" != ED ]; then
    echo "the signature is not in minisign's prehashed format (ED)" >&2
  elif ! { head -c 10 "$d/pk" | tail -c 8 > "$d/kid.pk"; head -c 10 "$d/sig" | tail -c 8 > "$d/kid.sig"; cmp -s "$d/kid.pk" "$d/kid.sig"; }; then
    echo "the manifest is signed by another key than the release key" >&2
  else
    printf '\x30\x2a\x30\x05\x06\x03\x2b\x65\x70\x03\x21\x00' > "$d/pk.der"; tail -c 32 "$d/pk" >> "$d/pk.der"
    tail -c 64 "$d/sig" > "$d/sig.bin"
    tc=$(sed -n 3p "$sig")
    # The file's BLAKE2b-512 is what is signed; the global signature covers the signature + the trusted comment.
    if ! openssl dgst -blake2b512 -binary "$file" > "$d/hash" 2>/dev/null \
       || ! openssl pkeyutl -verify -pubin -keyform DER -inkey "$d/pk.der" -rawin -in "$d/hash" -sigfile "$d/sig.bin" >/dev/null 2>&1; then
      echo "the signature does not verify" >&2
    elif case "$tc" in 'trusted comment: '*) false ;; *) true ;; esac; then
      echo "the signature has no trusted comment" >&2
    elif ! { cat "$d/sig.bin"; printf '%s' "${tc#trusted comment: }"; } > "$d/global" \
       || ! sed -n 4p "$sig" | openssl base64 -d -A > "$d/global.sig" 2>/dev/null \
       || ! openssl pkeyutl -verify -pubin -keyform DER -inkey "$d/pk.der" -rawin -in "$d/global" -sigfile "$d/global.sig" >/dev/null 2>&1; then
      echo "the signature's trusted comment does not verify" >&2
    else
      ok=0
    fi
  fi
  rm -f -- "$d/pk" "$d/pk.der" "$d/sig" "$d/sig.bin" "$d/kid.pk" "$d/kid.sig" "$d/hash" "$d/global" "$d/global.sig"
  rmdir -- "$d" 2>/dev/null || true
  return "$ok"
}

# mdm_manifest_field FILE KEY VALUE_ERE: the value of the one line `"KEY": "VALUE"` in the manifest FILE (written by
# release/build-manifest.sh with one key per line) whose value matches VALUE_ERE in full. Fails, printing nothing, when no
# line or more than one matches. Read only from a manifest whose signature verified.
mdm_manifest_field() {
  local hits
  hits=$(LC_ALL=C grep -E "^[[:space:]]*\"${2//./\\.}\": \"(${3})\",?\$" "$1" || true)
  [ -n "$hits" ] && [ "$(printf '%s\n' "$hits" | wc -l)" -eq 1 ] || return 1
  printf '%s\n' "$hits" | sed -E 's/^[[:space:]]*"[^"]*": "([^"]*)",?$/\1/'
}
MDM_SHA256_ERE='[0123456789abcdef]{64}'
MDM_IMAGE_REF_ERE='[abcdefghijklmnopqrstuvwxyz0123456789._/-]+@sha256:[0123456789abcdef]{64}'

# The files downloaded from the release's tag, as named in the manifest's "files" (release/build-manifest.sh). A release
# before 0.6.0 has no docker-compose.proxy.yml: it is fetched when the manifest lists it, and needed only behind your
# own proxy.
MDM_QUICKSTART_FILES="docker-compose.release.yml docker-compose.domain.yml docker-compose.proxy.yml install/sql/hmdm_init.en.sql install/sql/post_seed.sql install/lib/db.sh"

# mdm_sha256 FILE: its SHA-256 in lowercase hex.
mdm_sha256() { openssl dgst -sha256 -r "$1" | cut -d' ' -f1; }
# <<< release verification <<<

# The BASE_URL rule of install/lib/url.sh (see there for what it accepts and why it is an allowlist). A verbatim copy
# of the functions between the markers, not a download: this script may be newer than the release it installs
# (MDMESH_VERSION, or a copy from main) and fetches install/lib from that release, which may predate url.sh (see db.sh
# below). CI (t0-fast, edge entry) fails if the copies differ.
# >>> url.sh functions (quickstart.sh keeps a verbatim copy) >>>
# The character sets, spelled out: a range such as [A-Za-z0-9] follows the locale's collation, and under en_US.UTF-8
# bash matches thousands of non-ASCII letters with it (https://ｅxample.com would pass).
_MDM_ALNUM=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789
_MDM_HEX=0123456789ABCDEFabcdef
_MDM_DIGITS=0123456789
# _mdm_hostport_problem HOSTPORT: prints why HOSTPORT is not host[:port] as described above; prints nothing when it is.
_mdm_hostport_problem() {
  local hp=$1 host='' port='' v6=''
  case "$hp" in
    \[*)
      v6=${hp#\[}
      case "$v6" in *\]*) ;; *) echo "\"$hp\" has no closing ]"; return ;; esac
      port=${v6#*\]}; v6=${v6%%\]*}
      case "$v6" in
        ''|*[!${_MDM_HEX}:.]*|*:::*|*::*::*|*:*:*:*:*:*:*:*:*) echo "\"[$v6]\" is not an IPv6 address"; return ;;
        *:*) ;;
        *) echo "\"[$v6]\" is not an IPv6 address"; return ;;
      esac
      case "$port" in '') return ;; :*) port=${port#:}; [ -n "$port" ] || port=- ;; *) echo "\"$hp\": only :port may follow ]"; return ;; esac ;;
    *)
      host=${hp%%:*}
      case "$hp" in *:*) port=${hp#*:}; [ -n "$port" ] || port=- ;; esac
      case "$host" in
        '') echo 'it has no host (expected e.g. mdm.example.com)'; return ;;
        *[!${_MDM_ALNUM}.-]*|.*|*.|-*|*-|*..*) echo "\"$host\" is not a host name or IPv4 address"; return ;;
      esac ;;
  esac
  case "$port" in
    '') ;;                                                                     # no port
    *[!${_MDM_DIGITS}]*) echo "\"$hp\" does not end in a port number after the colon"; return ;;
    0*) echo "\"$hp\" has an invalid port (a port is 1-65535, with no leading zero)"; return ;;
  esac
  # Length first, so the numeric compare never sees a value too big for the shell's integer.
  [ -z "$port" ] || { [ "${#port}" -le 5 ] && [ "$port" -ge 1 ] && [ "$port" -le 65535 ]; } \
    || echo "\"$hp\" has a port outside 1-65535"
}

# mdm_check_base_url VAR: checks the URL held in the variable named VAR. When it passes, VAR's scheme is rewritten in
# lowercase (HTTPS://… becomes https://…; nothing else changes) and it succeeds. Otherwise it prints why to stderr,
# leaves VAR alone and fails. It takes a variable name, not the value, so the caller's variable is normalised in place.
mdm_check_base_url() {
  local url=${!1-} rest='' bad='' why=''
  case "$url" in
    '')                             why='it is empty' ;;
    *@*)                            why='it contains "@": a base URL takes no user name or password (user@host)' ;;
    *\?*|*#*)                       why='it contains "?" or "#": a base URL takes no query or fragment' ;;
    *%*)                            why='it contains "%": percent-escapes are not accepted in a base URL' ;;
    *[!${_MDM_ALNUM}._:/+=,\[\]-]*)
      bad=${url//[${_MDM_ALNUM}._:\/+=,\[\]-]/}; bad=${bad:0:1}
      case "$bad" in \') bad="\"'\"" ;; [[:print:]]) bad="'$bad'" ;; *) bad=$(printf '%q' "$bad") ;; esac
      why="it contains $bad, which is not allowed (only letters, digits and . _ : / + = , [ ] -)" ;;
    [Hh][Tt][Tt][Pp]://*)           rest=${url#*://}; url="http://$rest" ;;
    [Hh][Tt][Tt][Pp][Ss]://*)       rest=${url#*://}; url="https://$rest" ;;
    *)                              why='it must start with http:// or https:// (e.g. https://mdm.example.com)' ;;
  esac
  case "$rest" in
    [Hh][Tt][Tt][Pp]://*|[Hh][Tt][Tt][Pp][Ss]://*) why='it has the scheme twice (where a hostname is asked for, enter the name only)' ;;
  esac
  [ -n "$why" ] || why=$(_mdm_hostport_problem "${rest%%/*}")
  if [ -z "$why" ]; then printf -v "$1" '%s' "$url"; return 0; fi
  url=${!1-}
  case "$url" in *[![:print:]]*) url=$(printf '%q' "$url") ;; esac   # shown as typed, unless that would garble the terminal
  printf 'Invalid public base URL %s: %s.\n' "${url:-(empty)}" "$why" >&2
  return 1
}
# mdm_check_host VAR: checks that the variable named VAR holds a bare host[:port] as described above, with no scheme,
# path, user@, query or fragment: what the hostname prompts ask for (it becomes BASE_URL and Caddy's site address).
# Otherwise it prints why to stderr and fails. The value is never changed.
mdm_check_host() {
  local h=${!1-} bad='' why=''
  case "$h" in
    '')                 why='it is empty' ;;
    *://*)              why='enter the name only, without http:// or https://' ;;
    */*|*\?*|*#*|*@*)   why='enter the name only: no /path, ?query, #fragment or user@' ;;
    *[!${_MDM_ALNUM}.:\[\]-]*)
      bad=${h//[${_MDM_ALNUM}.:\[\]-]/}; bad=${bad:0:1}
      case "$bad" in \') bad="\"'\"" ;; [[:print:]]) bad="'$bad'" ;; *) bad=$(printf '%q' "$bad") ;; esac
      why="it contains $bad, which is not allowed (only letters, digits, . and -, or an [IPv6] address, then an optional :port)" ;;
  esac
  [ -n "$why" ] || why=$(_mdm_hostport_problem "$h")
  [ -z "$why" ] && return 0
  case "$h" in *[![:print:]]*) h=$(printf '%q' "$h") ;; esac
  printf 'Invalid hostname %s: %s.\n' "${h:-(empty)}" "$why" >&2
  return 1
}
# <<< url.sh functions <<<

# Answers given in advance (see install/lib/answers.sh and install/options.json): a verbatim copy of the functions
# between the markers, for the same reason as url.sh's above. CI (t0-fast, edge entry) fails if the copies differ.
# >>> answers.sh functions (quickstart.sh keeps a verbatim copy) >>>
# The keys an answers file may set: every answer and setting in install/options.json (CI checks the two match).
_MDM_ANSWER_KEYS=" MDMESH_MODE MDMESH_REPO MDMESH_IMAGE_OWNER MDMESH_HOST MDMESH_TUNNEL_TOKEN MDMESH_ACME_EMAIL MDMESH_BIND MDMESH_TRUSTED_PROXIES MDMESH_BASE_URL MDMESH_HTTP_PORT MDMESH_REPLACE_DATA MDMESH_ASSUME_YES MDMESH_DIR MDMESH_VERSION MDMESH_RELEASE_PUBKEY MDMESH_ORIGIN_TLS MDMESH_NONINTERACTIVE MDMESH_DRY_RUN "
_MDM_SECRET_KEYS=" MDMESH_TUNNEL_TOKEN "
_MDM_ANS_GIVEN=" "       # ids of the questions answered in advance
_MDM_ANS_FROM_FILE=" "   # keys set from the answers file
_mdm_answer_fail() { printf 'MDMesh installer: %s\n' "$*" >&2; exit 1; }

# mdm_answers_load: reads the answers file MDMESH_ANSWERS names, if any (keys already in the environment are left
# alone), then checks the 0/1 settings (MDMESH_NONINTERACTIVE, MDMESH_DRY_RUN, MDMESH_ORIGIN_TLS). Stops the installer on any error. xtrace stays off
# while it runs (the file may hold a secret).
mdm_answers_load() { local _mdm_x=$-; set +x; _mdm_answers_load; case "$_mdm_x" in *x*) set -x ;; esac; }
# _mdm_private_file FILE: FILE belongs to this user (or root) and nobody else may read, write or run it. Fails closed:
# without find, or when it fails, the file does not count as private.
_mdm_private_file() {
  local r
  command -v find >/dev/null 2>&1 || return 1
  r=$(find "$1" -prune \( -user "$EUID" -o -user 0 \) ! -perm -040 ! -perm -020 ! -perm -010 ! -perm -004 \
        ! -perm -002 ! -perm -001 -print 2>/dev/null) || return 1
  [ "$r" = "$1" ]
}
_mdm_answers_load() {
  local f=${MDMESH_ANSWERS-} line key val n=0 seen=" " v
  # Secrets leave the environment first, before this starts any program (find, wc): they stay shell variables, so
  # mdm_answer still reads them, but no child sees them.
  # shellcheck disable=SC2163  # the variable named by $key
  for key in $_MDM_SECRET_KEYS; do [ -z "${!key+x}" ] || export -n "$key"; done
  if [ -n "$f" ]; then
    case "$f" in /*) ;; *) _mdm_answer_fail "MDMESH_ANSWERS must be an absolute path (e.g. \$PWD/answers.env)." ;; esac
    { [ -f "$f" ] && [ -r "$f" ]; } || _mdm_answer_fail "MDMESH_ANSWERS: $f is not a readable file."
    [ "$(wc -c < "$f")" -le 65536 ] || _mdm_answer_fail "MDMESH_ANSWERS: $f is larger than 64 KiB."
    # Read only when private (before any line of it is read).
    _mdm_private_file "$f" || _mdm_answer_fail "MDMESH_ANSWERS: $f must belong to you (or root) and be private to you: chmod 600 $f (under sudo, also chown root $f). It was not read."
    while IFS= read -r line || [ -n "$line" ]; do
      n=$((n + 1)); line=${line%$'\r'}
      case "$line" in ''|'#'*) continue ;; *=*) ;; *) _mdm_answer_fail "$f, line $n: expected KEY=VALUE." ;; esac
      key=${line%%=*}; val=${line#*=}
      case "$_MDM_ANSWER_KEYS" in
        *" $key "*) ;;
        *) # Named only when it looks like a variable name: a line holding a bare token (abc…=) must not be echoed.
           case "$key" in
             ''|*[!ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_]*) key='' ;;
             *) [ "${#key}" -le 64 ] || key='' ;;
           esac
           _mdm_answer_fail "$f, line $n: unknown key${key:+ $key} (the keys are in install/options.json)." ;;
      esac
      case "$seen" in *" $key "*) _mdm_answer_fail "$f, line $n: $key is set twice." ;; esac
      seen="$seen$key "
      case "$val" in \"*\"|\'*\') val=${val#?}; val=${val%?} ;; esac
      if [ -z "${!key+x}" ]; then printf -v "$key" '%s' "$val"; _MDM_ANS_FROM_FILE="$_MDM_ANS_FROM_FILE$key "; fi
    done < "$f"
  fi
  for key in MDMESH_NONINTERACTIVE MDMESH_DRY_RUN MDMESH_ORIGIN_TLS; do
    v=${!key-}
    case "$v" in ''|0|1) ;; *) _mdm_answer_fail "$key must be 1 or 0." ;; esac
  done
}

# _mdm_answer_check RULE: checks the value in _MDM_V by RULE. On success it may normalise _MDM_V (the value used) and
# sets _MDM_SHOW (the value shown); otherwise it sets _MDM_WHY (empty when url.sh has already said why on stderr).
_mdm_answer_check() {
  local v=$_MDM_V u l d
  _MDM_WHY=''; _MDM_SHOW=$v
  case "$1" in
    mode)   # the hosting modes of install/lib/modes.sh
      case "$v" in tunnel|1) _MDM_V=1; _MDM_SHOW=tunnel ;; domain|2) _MDM_V=2; _MDM_SHOW=domain ;;
        proxy|3) _MDM_V=3; _MDM_SHOW=proxy ;; *) _MDM_WHY='must be tunnel, domain or proxy' ;; esac ;;
    hostname)
      u="https://$v"
      { mdm_check_host _MDM_V && mdm_check_base_url u; } || _MDM_WHY=' ' ;;
    base-url)
      mdm_check_base_url _MDM_V || _MDM_WHY=' '
      _MDM_SHOW=$_MDM_V ;;
    tunnel-token)
      _MDM_SHOW='(hidden)'
      case "$v" in
        '') _MDM_WHY='is empty' ;;
        *[!${_MDM_ALNUM}._=+/-]*) _MDM_WHY='is not a Cloudflare Tunnel token (only letters, digits and . _ = + / -)' ;;
      esac ;;
    email)
      l=${v%%@*}; d=${v#*@}
      case "$v" in
        '') ;;
        *@*@*|*[!${_MDM_ALNUM}._%+@-]*) _MDM_WHY='is not an email address (name@example.com)' ;;
        *@*) case "$l" in '') _MDM_WHY='is not an email address (name@example.com)' ;; esac
             case "$d" in *[!${_MDM_ALNUM}.-]*|.*|*.|*..*|'') _MDM_WHY='is not an email address (name@example.com)' ;;
               *.*) ;; *) _MDM_WHY='is not an email address (name@example.com)' ;; esac ;;
        *) _MDM_WHY='is not an email address (name@example.com)' ;;
      esac ;;
    github-repo)
      case "$v" in
        '') ;;
        */*/*|*[!${_MDM_ALNUM}._/-]*) _MDM_WHY='must be owner/name (e.g. MDMesh-app/MDMesh)' ;;
        */*) case "${v%%/*}" in ''|.|..) _MDM_WHY='must be owner/name (e.g. MDMesh-app/MDMesh)' ;; esac
             case "${v#*/}" in ''|.|..) _MDM_WHY='must be owner/name (e.g. MDMesh-app/MDMesh)' ;; esac ;;
        *) _MDM_WHY='must be owner/name (e.g. MDMesh-app/MDMesh)' ;;
      esac ;;
    image-owner)
      case "$v" in
        '') ;;
        [!abcdefghijklmnopqrstuvwxyz0123456789]*|*[!abcdefghijklmnopqrstuvwxyz0123456789._-]*)
          _MDM_WHY='must be a GHCR owner in lowercase (letters, digits and . _ -, starting with a letter or digit)' ;;
      esac ;;
    port)
      u=$v
      while case "$u" in 0?*) true ;; *) false ;; esac; do u=${u#0}; done   # leading zeros, as the port question takes them
      case "$u" in
        '') ;;
        *[!${_MDM_DIGITS}]*) _MDM_WHY='must be a number' ;;
        *) { [ "${#u}" -le 5 ] && [ "$u" -ge 1 ] && [ "$u" -le 65535 ]; } || _MDM_WHY='must be 1-65535' ;;
      esac ;;
    bind)
      [ -z "$v" ] || _mdm_bind_ok "$v" || _MDM_WHY='must be an IP address and a port, e.g. 127.0.0.1:8080 or [::1]:8080' ;;
    proxies)
      _mdm_proxies_check "$v"; _MDM_SHOW=$_MDM_V ;;
    yes-no)
      case "$v" in yes|no) ;; *) _MDM_WHY='must be yes or no' ;; esac ;;
    flag)
      case "$v" in 0|1) ;; *) _MDM_WHY='must be 1 or 0' ;; esac ;;
    *) _mdm_answer_fail "internal: unknown answer rule $1" ;;
  esac
}

# _mdm_ipv4 ADDR: ADDR is a dotted IPv4 address (four numbers 0-255, no leading zeros).
_mdm_ipv4() {
  local a=$1. o n=0
  case "$1" in ''|*[!${_MDM_DIGITS}.]*|.*|*.|*..*) return 1 ;; esac
  while [ -n "$a" ]; do
    o=${a%%.*}; a=${a#*.}; n=$((n + 1))
    case "$o" in 0?*) return 1 ;; esac
    { [ "${#o}" -le 3 ] && [ "$o" -le 255 ]; } || return 1
  done
  [ "$n" = 4 ]
}
# _mdm_ipv6 ADDR: ADDR (no brackets) looks like an IPv6 address: hex digits and ":", at least two ":", at most one "::".
# IPv4-mapped addresses (::ffff:…, or with a dotted IPv4 part) are refused: written as IPv6 they don't match the IPv4
# peers they mean, so use the IPv4 address. (Not checked further: whatever reads it next refuses a wrong one.)
_mdm_ipv6() {
  case "$1" in ''|*[!${_MDM_HEX}:]*|*:::*|*::*::*|::[Ff][Ff][Ff][Ff]:*|0:0:0:0:0:[Ff][Ff][Ff][Ff]:*) return 1 ;; *:*:*) return 0 ;; esac
  return 1
}
# _mdm_bind_ok IP:PORT: an address to listen on: an IPv4 address or an [IPv6] one, then :port (1-65535).
_mdm_bind_ok() {
  local a
  case "$1" in
    \[*\]:*) a=${1%\]:*}; _mdm_ipv6 "${a#\[}" || return 1 ;;
    *:*) _mdm_ipv4 "${1%:*}" || return 1 ;;
    *) return 1 ;;
  esac
  [ -z "$(_mdm_hostport_problem "[::1]:${1##*:}")" ]
}
# _mdm_proxies_check LIST: the proxy addresses in LIST (separated by spaces or commas). Each is "host" (a proxy on this
# machine: MDMesh's edge sees it at its Docker network's gateway), an IP address, or a range (CIDR) no wider than an IPv4
# /8 or an IPv6 /16, which would trust far more than a proxy. Names and Caddy's private_ranges are refused. On success
# _MDM_V is the list with one space between entries; else _MDM_WHY says why. Empty: the default, host.
_mdm_proxies_check() {
  local t a l min max out='' list
  IFS=', ' read -r -a list <<< "$1"
  for t in ${list[@]+"${list[@]}"}; do
    case "$t" in '') continue ;; host) out="$out $t"; continue ;; esac
    a=${t%/*}
    if _mdm_ipv4 "$a"; then min=8; max=32
    elif _mdm_ipv6 "$a"; then min=16; max=128
    else _MDM_WHY="has \"$t\", which is not an IP address, a range (CIDR) or host"; return; fi
    case "$t" in
      */*) l=${t#*/}
           case "$l" in ''|*[!${_MDM_DIGITS}]*|0?*) _MDM_WHY="has \"$t\", whose range is not a prefix length"; return ;; esac
           { [ "${#l}" -le 3 ] && [ "$l" -ge "$min" ] && [ "$l" -le "$max" ]; } \
             || { _MDM_WHY="has \"$t\": a range must be /$min to /$max (a wider one trusts far more than your proxy)"; return; } ;;
    esac
    out="$out $t"
  done
  _MDM_V=${out# }
}

# _mdm_answer_rule_has_default RULE: an empty or missing answer means the question's default (Enter at the prompt).
_mdm_answer_rule_has_default() { case "$1" in github-repo|image-owner|port|email|yes-no|bind|proxies) return 0 ;; esac; return 1; }

# mdm_answer ID VAR RULE ENV [ALIAS]: if ENV (or ALIAS, the installer's older name for it) is set, in the environment or
# the answers file, checks its value by RULE and stores it in VAR: question ID is then answered. Stops the installer when
# the value is invalid, or when ENV and ALIAS are both set to different values. An empty value answers the GitHub repo,
# image owner and Let's Encrypt email questions (the default, as Enter at the prompt); for a base URL, the HTTP port,
# Keep/Erase and the confirmation it counts as not set, as it always did for the native installer.
# For a secret, xtrace is off throughout, ENV (and ALIAS) is then unset, and VAR is not exported: no child process sees it.
mdm_answer() { local _mdm_x=$-; set +x; _mdm_answer "$@"; case "$_mdm_x" in *x*) set -x ;; esac; }
_mdm_answer() {
  local id=$1 var=$2 rule=$3 env=$4 alias=${5-} k src=''
  k=${1//-/_}
  printf -v "_MDM_ANS_ENV_$k" '%s' "$env"; printf -v "_MDM_ANS_RULE_$k" '%s' "$rule"; printf -v "_MDM_ANS_VAR_$k" '%s' "$var"
  if [ -n "$alias" ] && [ -n "${!env-}" ] && [ -n "${!alias-}" ] && [ "${!env}" != "${!alias}" ]; then
    _mdm_answer_fail "$env and $alias are both set, to different values: set only $env."
  fi
  if [ -n "${!env+x}" ] && { [ -n "${!env}" ] || [ -z "$alias" ] || [ -z "${!alias-}" ]; }; then
    _MDM_V=${!env}; src=$env
    case "$_MDM_ANS_FROM_FILE" in *" $env "*) src="$env (from $MDMESH_ANSWERS)" ;; esac
  elif [ -n "$alias" ] && [ -n "${!alias+x}" ]; then
    _MDM_V=${!alias}; src=$alias
  else
    return 0
  fi
  if [ -z "$_MDM_V" ]; then
    case "$rule" in base-url|port|yes-no|flag) return 0 ;; esac
  fi
  _mdm_answer_check "$rule"
  if [ -n "$_MDM_WHY" ]; then
    case "$_MDM_WHY" in ' ') _mdm_answer_fail "$src is invalid (why above)." ;; esac
    _mdm_answer_fail "$src $_MDM_WHY."
  fi
  printf -v "$var" '%s' "$_MDM_V"
  _MDM_V=''
  # shellcheck disable=SC2163  # $var names the variable to un-export
  case "$_MDM_SECRET_KEYS" in
    *" $env "*) unset "$env"; [ -z "$alias" ] || unset "$alias"; export -n "$var" ;;
  esac
  printf -v "_MDM_ANS_SHOW_$k" '%s' "$_MDM_SHOW"; printf -v "_MDM_ANS_SRC_$k" '%s' "$src"
  _MDM_ANS_GIVEN="$_MDM_ANS_GIVEN$id "
}

# mdm_prompt_ok ID VAR: checks the answer just typed at question ID (in VAR) with the rule a pre-set answer gets. Fails,
# saying why (never the value of a secret), so the caller asks again; otherwise VAR holds the answer as the rule writes it
# (a list of proxies with one space between entries, say). xtrace is off while it holds the value.
mdm_prompt_ok() { local _mdm_x=$- _mdm_rc=0; set +x; _mdm_prompt_ok "$@" || _mdm_rc=1; case "$_mdm_x" in *x*) set -x ;; esac; return "$_mdm_rc"; }
_mdm_prompt_ok() {
  local r="_MDM_ANS_RULE_${1//-/_}"
  _MDM_V=${!2-}
  _mdm_answer_check "${!r}"
  [ -n "$_MDM_WHY" ] || printf -v "$2" '%s' "$_MDM_V"
  _MDM_V=''
  [ -n "$_MDM_WHY" ] || return 0
  case "$_MDM_WHY" in ' ') printf 'Enter it again.\n' >&2 ;; *) printf 'That answer %s. Enter it again.\n' "$_MDM_WHY" >&2 ;; esac
  return 1
}

# mdm_any_answered: succeeds when at least one question was answered in advance. mdm_given ID: when question ID was.
mdm_any_answered() { [ "$_MDM_ANS_GIVEN" != " " ]; }
mdm_given() { case "$_MDM_ANS_GIVEN" in *" $1 "*) return 0 ;; esac; return 1; }

# mdm_unused ID WHY: question ID is not asked in this run (WHY, e.g. "in domain mode"): an answer given for it is
# ignored, with a warning on stderr.
mdm_unused() {
  local e="_MDM_ANS_ENV_${1//-/_}"
  mdm_given "$1" || return 0
  printf 'MDMesh installer: %s is not used %s: ignoring it.\n' "${!e}" "$2" >&2
  _MDM_ANS_GIVEN=${_MDM_ANS_GIVEN/ $1 / }
}

# mdm_ask ID: called just before question ID is asked. With MDMESH_NONINTERACTIVE=1 it stops the installer instead,
# naming the variable that answers it.
mdm_ask() {
  local e="_MDM_ANS_ENV_${1//-/_}"
  [ "${MDMESH_NONINTERACTIVE:-0}" = 1 ] || return 0
  _mdm_answer_fail "MDMESH_NONINTERACTIVE=1, and the question \"$1\" has no answer: set ${!e-its variable} (see install/options.json)."
}

# mdm_need ID: with MDMESH_NONINTERACTIVE=1, stops the installer now when question ID, which this run will ask, has no
# answer and no default: before the run downloads or changes anything, rather than when it gets to the question.
mdm_need() {
  local r="_MDM_ANS_RULE_${1//-/_}"
  mdm_given "$1" || _mdm_answer_rule_has_default "${!r-}" || mdm_ask "$1"
}

# mdm_answered ID: succeeds when question ID needs no asking: it was answered in advance (says so, the value hidden when
# secret), or MDMESH_NONINTERACTIVE=1 and it has a default (the caller then applies it). Otherwise fails, so the caller
# asks it; with MDMESH_NONINTERACTIVE=1 and no default it stops the installer (mdm_ask).
mdm_answered() {
  local k=${1//-/_} e s r
  e="_MDM_ANS_ENV_$k"; s="_MDM_ANS_SHOW_$k"; r="_MDM_ANS_RULE_$k"
  case "$_MDM_ANS_GIVEN" in
    *" $1 "*) e="_MDM_ANS_SRC_$k"
              case "${!r-}" in tunnel-token) printf 'Using %s: (hidden)\n' "${!e}" ;; *) printf 'Using %s: %s\n' "${!e}" "${!s}" ;; esac
              return 0 ;;
  esac
  if [ "${MDMESH_NONINTERACTIVE:-0}" = 1 ] && _mdm_answer_rule_has_default "${!r-}"; then
    s="_MDM_ANS_VAR_$k"; printf -v "${!s}" '%s' ''   # the caller applies the default to an empty answer, as to Enter
    printf 'Using the default for %s\n' "${!e}"; return 0
  fi
  mdm_ask "$1"
  return 1
}

# mdm_plan_line ID DEFAULT [TAKES_DEFAULT]: the dry-run plan's line for question ID: "  <id> <value> [<source>]", where
# source is the variable that answered it, "default" (MDMESH_NONINTERACTIVE=1 and the question has a default, or
# TAKES_DEFAULT=1, as with -y), or "ask" (the question would be asked; value "?"). An empty value shows as "".
# With MDMESH_NONINTERACTIVE=1 a question that would be asked stops the installer instead (mdm_ask).
mdm_plan_line() {
  local id=$1 def=$2 k=${1//-/_} v s r
  r="_MDM_ANS_RULE_$k"
  case "$_MDM_ANS_GIVEN" in
    *" $id "*) v="_MDM_ANS_SHOW_$k"; v=${!v}; s="_MDM_ANS_SRC_$k"; s=${!s%% *}
               [ -n "$v" ] || v=$def ;;
    *) if [ "${3:-0}" = 1 ] || { [ "${MDMESH_NONINTERACTIVE:-0}" = 1 ] && _mdm_answer_rule_has_default "${!r-}"; }; then
         v=$def; s=default
       else
         mdm_ask "$id"; v='?'; s=ask
       fi ;;
  esac
  [ -n "$v" ] || v='""'
  mdm_plan_row "$id" "$v" "$s"
}
# mdm_plan_row ID VALUE SOURCE: one line of the dry-run plan, in mdm_plan_line's format.
mdm_plan_row() { printf '  %-13s %-32s [%s]\n' "$1" "$2" "$3"; }
# <<< answers.sh functions <<<

# The hosting modes (install/lib/modes.sh): a verbatim copy of the functions between the markers, for the same reason as
# url.sh's above. CI (t0-fast, edge entry) fails if the copies differ.
# >>> modes.sh functions (quickstart.sh keeps a verbatim copy) >>>
# number|name|menu label|who should pick it
_MDM_MODES='1|tunnel|Cloudflare Tunnel|easiest: no ports to open (domain on Cloudflare)
2|domain|Your own domain|MDMesh gets its own HTTPS (ports 80/443 must reach this machine)
3|proxy|Behind your own proxy|your nginx/Apache/Caddy/Traefik does HTTPS; MDMesh listens on 127.0.0.1:8080'
# The answers only one mode asks: number|answer id|its default in the dry-run plan.
_MDM_MODE_ANSWERS='1|tunnel-token|
2|acme-email|
3|bind|127.0.0.1:8080
3|trusted-proxies|host'
MDM_PROXY_BIND_DEFAULT=127.0.0.1:8080
MDM_PROXY_TRUSTED_DEFAULT=host
# Where the docs are for someone with no clone (the quick start): the main branch's pages on GitHub.
MDM_DOCS=https://github.com/MDMesh-app/MDMesh/blob/main

# mdm_mode_name NUMBER: the mode's name (tunnel, domain, proxy).
mdm_mode_name() { printf '%s\n' "$_MDM_MODES" | awk -F'|' -v n="$1" '$1 == n { print $2 }'; }

# mdm_mode_choose FOOTER: asks the hosting-mode question (MODE: 1, 2 or 3), unless it was answered in advance. When
# something on this host already listens on port 80 or 443 (often a web server of your own), it says so and points at
# the proxy mode.
mdm_mode_choose() {
  mdm_answered mode && return 0
  echo "How will your phones reach MDMesh?"
  printf '%s\n' "$_MDM_MODES" | awk -F'|' '{ printf "  %s) %-23s %s\n", $1, $3, $4 }'
  if mdm_listening 80 || mdm_listening 443; then
    echo "  Ports 80/443 are already in use on this machine. If that is your own web server, pick 3."
  fi
  echo "$1"
  MODE=""
  while :; do
    read -rp "Choose [1/2/3]: " MODE || { err "No selection (non-interactive run?). Aborting."; exit 1; }   # answer: mode
    case "$MODE" in 1|2|3) break ;; *) warn "Please enter 1, 2 or 3." ;; esac
  done
}

# mdm_listening PORT [IP]: succeeds when a TCP listener on this host holds PORT on IP, or on every address (with no IP:
# on any). From ss (iproute2), else netstat, else lsof; with none of them, nothing is found.
mdm_listening() {
  local out
  if command -v ss > /dev/null 2>&1; then out=$(ss -ltn 2> /dev/null | awk 'NR > 1 { print $4 }')
  elif command -v netstat > /dev/null 2>&1; then out=$(netstat -ltn 2> /dev/null | awk '$6 == "LISTEN" { print $4 }')
  elif command -v lsof > /dev/null 2>&1; then out=$(lsof -nP -iTCP -sTCP:LISTEN 2> /dev/null | awk 'NR > 1 { print $9 }')
  else return 1; fi
  printf '%s\n' "$out" | awk -v port="$1" -v ip="${2:-}" '
    { n = split($0, a, ":"); if (a[n] != port) next
      addr = substr($0, 1, length($0) - length(port) - 1); gsub(/[][]/, "", addr); sub(/%.*/, "", addr)
      if (ip == "" || addr == ip || addr == "*" || addr == "0.0.0.0" || addr == "::" || addr == "" ||
          ip == "0.0.0.0" || ip == "::") f = 1 }
    END { exit !f }'
}

# mdm_mode_from_flag NAME: the mode flag given on the command line (--NAME: --tunnel, --domain or --proxy; the
# installers' argument loops take only these) answers MDMESH_MODE, as the environment or an answers file would. Called
# after mdm_answers_load. Stops the installer when MDMESH_MODE names another mode. No NAME: nothing to do.
mdm_mode_from_flag() {
  local given=${MDMESH_MODE-} n
  [ -n "$1" ] || return 0
  if [ -n "$given" ]; then
    n=$(printf '%s\n' "$_MDM_MODES" | awk -F'|' -v v="$given" '$1 == v || $2 == v { print $2 }')
    [ "$n" = "$1" ] || _mdm_answer_fail "--$1 and MDMESH_MODE=$given name different hosting modes: give one."
  fi
  MDMESH_MODE=$1
}

# mdm_mode_check_answers: once the answers are loaded, the rules that span them: an answer given for another mode's
# question is ignored, saying so (MDMESH_ORIGIN_TLS too); behind your own proxy, `host` is refused with an address other
# machines reach (mdm_proxy_trust_problem).
mdm_mode_check_answers() {
  local m id why
  mdm_given mode || return 0
  while IFS='|' read -r m id _; do
    [ "$m" = "$MODE" ] || mdm_unused "$id" "in $(mdm_mode_name "$MODE") mode"
  done <<EOF
$_MDM_MODE_ANSWERS
EOF
  if [ "$MODE" != 3 ] && [ -n "${MDMESH_ORIGIN_TLS-}" ]; then
    printf 'MDMesh installer: MDMESH_ORIGIN_TLS is not used in %s mode: ignoring it.\n' "$(mdm_mode_name "$MODE")" >&2
  fi
  if [ "$MODE" = 3 ] && mdm_given bind; then
    why=$(mdm_proxy_trust_problem "${PROXY_BIND:-$MDM_PROXY_BIND_DEFAULT}" "${PROXY_TRUSTED:-$MDM_PROXY_TRUSTED_DEFAULT}")
    [ -z "$why" ] || _mdm_answer_fail "MDMESH_BIND and MDMESH_TRUSTED_PROXIES: $why"
  fi
}

# mdm_bind_loopback IP:PORT: the address is this machine's own loopback (127.x.x.x or [::1]): only programs on this
# machine reach it.
mdm_bind_loopback() { case "$1" in 127.*|\[::1\]:*) return 0 ;; esac; return 1; }

# mdm_proxy_trust_problem BIND LIST: prints why LIST can't be trusted with the edge on BIND; nothing when it can. `host`
# (this machine, as Docker delivers its connections: the stack network's gateway) is safe only on a loopback BIND: other
# machines reaching a non-loopback BIND can arrive from that same gateway (Docker Desktop, rootless Docker, a docker-proxy
# for IPv6), and would then be believed. docker/edge-peers.sh applies the same rule when the edge starts.
mdm_proxy_trust_problem() {
  case " $2 " in *" host "*) ;; *) return 0 ;; esac
  mdm_bind_loopback "$1" && return 0
  echo "host (a proxy on this machine) needs MDMesh on a loopback address, 127.0.0.1 or [::1]; with $1, list your proxy's own address instead"
}

# mdm_docker_keeps_sources: succeeds unless this Docker hides where connections come from: rootless Docker and Docker
# Desktop deliver every connection to a published port from their gateway.
mdm_docker_keeps_sources() {
  case "$(docker info --format '{{.OperatingSystem}} {{json .SecurityOptions}}' 2> /dev/null)" in
    *rootless*|*'Docker Desktop'*) return 1 ;;
  esac
}

# mdm_mode_questions: asks what the mode needs and was not answered in advance (the public hostname, then the mode's own
# questions), with their defaults, and sets BASE_URL and the mode's settings (mdm_mode_settings). Stops the installer on
# a wrong hostname. Behind your own proxy it checks the address MDMesh listens on is free, and that `host` fits it.
mdm_mode_questions() {
  local why
  if ! mdm_answered host; then
    case "$MODE" in
      1) read -rp "Public hostname devices will use (e.g. mdm.example.com): " HOST ;;   # answer: host
      2) read -rp "Your domain (DNS already pointing here, e.g. mdm.example.com): " HOST ;;   # answer: host
      3) read -rp "Public hostname your proxy serves (e.g. mdm.example.com): " HOST ;;   # answer: host
    esac
  fi
  mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
  BASE_URL="https://${HOST}"
  mdm_check_base_url BASE_URL || { err "Check the hostname you entered (the name only, e.g. mdm.example.com), then re-run."; exit 1; }
  case "$MODE" in
    1) while :; do
         mdm_answered tunnel-token || read -rp "Cloudflare Tunnel token (Zero Trust → Tunnels → your tunnel): " TUNNEL_TOKEN   # answer: tunnel-token
         mdm_prompt_ok tunnel-token TUNNEL_TOKEN && break
       done ;;
    2) while :; do
         mdm_answered acme-email || read -rp "Email for Let's Encrypt: " ACME_EMAIL   # answer: acme-email
         mdm_prompt_ok acme-email ACME_EMAIL && break
       done ;;
    3) while :; do
         mdm_answered bind || read -rp "Address MDMesh listens on for your proxy [127.0.0.1:8080]: " PROXY_BIND   # answer: bind
         mdm_prompt_ok bind PROXY_BIND || continue
         PROXY_BIND=${PROXY_BIND:-$MDM_PROXY_BIND_DEFAULT}
         _mdm_bind_free "$PROXY_BIND" && break
         if mdm_given bind || [ "${MDMESH_NONINTERACTIVE:-0}" = 1 ]; then
           _mdm_answer_fail "MDMESH_BIND=$PROXY_BIND: something on this machine already listens there. Set MDMESH_BIND to a free address and port."
         fi
         warn "Something on this machine already listens on $PROXY_BIND: choose another address or port."
         PROXY_BIND=''
       done
       while :; do
         mdm_answered trusted-proxies || read -rp "Your proxy's IP address as MDMesh sees it (host = a proxy on this machine) [host]: " PROXY_TRUSTED   # answer: trusted-proxies
         mdm_prompt_ok trusted-proxies PROXY_TRUSTED || continue
         PROXY_TRUSTED=${PROXY_TRUSTED:-$MDM_PROXY_TRUSTED_DEFAULT}
         why=$(mdm_proxy_trust_problem "$PROXY_BIND" "$PROXY_TRUSTED")
         [ -n "$why" ] || break
         mdm_given trusted-proxies && _mdm_answer_fail "MDMESH_TRUSTED_PROXIES: $why."
         [ "${MDMESH_NONINTERACTIVE:-0}" != 1 ] || _mdm_answer_fail "MDMESH_TRUSTED_PROXIES (default host): $why."
         warn "That answer: $why."
         PROXY_TRUSTED=''
       done
       if ! mdm_bind_loopback "$PROXY_BIND" && ! mdm_docker_keeps_sources; then
         warn "This Docker (rootless, or Docker Desktop) delivers every connection to $PROXY_BIND from its own gateway, so"
         warn "MDMesh can't see your proxy's address: it then believes no one's X-Forwarded-For, and every client counts as one"
         warn "address for the sign-in limits. With a proxy on this machine, keep MDMesh on 127.0.0.1 ($MDM_DOCS/DEPLOY.md#3-behind-your-own-proxy)."
       fi ;;
  esac
  ORIGIN_TLS=${MDMESH_ORIGIN_TLS:-0}
  mdm_mode_settings "$MODE"
}
# _mdm_bind_free IP:PORT: nothing on this machine listens where the edge would.
_mdm_bind_free() {
  local ip=${1%:*}
  ip=${ip#\[}; ip=${ip%\]}
  ! mdm_listening "${1##*:}" "$ip"
}

# mdm_mode_plan: the dry-run plan's lines for the answers only one mode asks: asked (or answered) in the mode given,
# "unused" in the others, and all of them when the mode isn't known yet.
mdm_mode_plan() {
  local m id def
  while IFS='|' read -r m id def; do
    if ! mdm_given mode || [ "$m" = "$MODE" ]; then mdm_plan_line "$id" "$def"; else mdm_plan_row "$id" - unused; fi
  done <<EOF
$_MDM_MODE_ANSWERS
EOF
}

# mdm_mode_of_env: the mode of an existing .env (already loaded): tunnel when COMPOSE_PROFILES lists cloudflare, proxy
# when COMPOSE_FILE lists docker-compose.proxy.yml, else domain.
mdm_mode_of_env() {
  case ",$(printf '%s' "${COMPOSE_PROFILES:-}" | tr -d ' ')," in *,cloudflare,*) echo 1; return ;; esac
  case ":${COMPOSE_FILE:-}:" in *:docker-compose.proxy.yml:*) echo 3; return ;; esac
  echo 2
}

# mdm_mode_settings NUMBER [rerun]: the mode's settings, from HOST (and, behind your proxy, PROXY_BIND and ORIGIN_TLS):
# COMPOSE_ARGS (its compose files and profile, for a command line), EXTRA_NOTE (what to do next) and ENTRY (where
# requests come in); and, for a new .env, SITE_ADDRESS (the edge's site; behind your proxy the edge sets its own),
# COMPOSE_FILE and COMPOSE_PROFILES (compose and the updater read them from .env), with another mode's answers cleared
# so they never reach .env. With rerun (an existing .env, already loaded and exported) only the first three: its own
# values stay as they are.
mdm_mode_settings() {
  local f site file profiles='' at
  case "$1" in
    1) site=':80'; file='docker-compose.yml'; profiles='cloudflare'; ENTRY="Cloudflare Tunnel"
       EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80." ;;
    2) site=$HOST; file='docker-compose.yml:docker-compose.domain.yml'; ENTRY="ports 80/443"
       EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open." ;;
    3) site=':8080'; file='docker-compose.yml:docker-compose.proxy.yml'; ENTRY="${PROXY_BIND}, behind your proxy"
       # On every address: the one your proxy uses is one of this machine's.
       case "$PROXY_BIND" in 0.0.0.0:*|\[::\]:*) at="<this machine's address>:${PROXY_BIND##*:}" ;; *) at=$PROXY_BIND ;; esac
       if [ "${ORIGIN_TLS:-0}" = 1 ]; then
         EXTRA_NOTE="Point your proxy at https://${at}, trusting MDMesh's own CA, certificate name $(mdm_host_name "$HOST"): $MDM_DOCS/DEPLOY.md#3-behind-your-own-proxy"
       else
         EXTRA_NOTE="Point your proxy at http://${at}. Ready configurations: $MDM_DOCS/DEPLOY.md#3-behind-your-own-proxy"
       fi ;;
    *) _mdm_answer_fail "internal: unknown hosting mode $1" ;;
  esac
  COMPOSE_ARGS=''
  # compose's own default file is left to COMPOSE_FILE (.env), as setup.sh always did in tunnel mode.
  [ "$file" = docker-compose.yml ] || for f in $(printf '%s' "$file" | tr ':' ' '); do COMPOSE_ARGS="$COMPOSE_ARGS -f $f"; done
  [ -z "$profiles" ] || COMPOSE_ARGS="$COMPOSE_ARGS --profile $profiles"
  COMPOSE_ARGS=${COMPOSE_ARGS# }
  [ "${2:-}" != rerun ] || return 0
  SITE_ADDRESS=$site; COMPOSE_FILE=$file; COMPOSE_PROFILES=$profiles
  [ "$1" = 1 ] || TUNNEL_TOKEN=''
  [ "$1" = 2 ] || ACME_EMAIL=''
}

# mdm_up_hint: what to check when `docker compose up` fails, by mode.
mdm_up_hint() {
  case "$MODE" in
    2) echo "If the edge could not publish ports 80/443, stop what holds them, or pick mode 3 behind that web server." ;;
    3) echo "If the edge could not publish MDMESH_BIND (${PROXY_BIND:-in .env}), set a free address in .env (e.g. MDMESH_BIND=127.0.0.1:8081)." ;;
  esac
}

# mdm_host_name HOSTPORT: the host of a checked host[:port] (an [IPv6] keeps its brackets).
mdm_host_name() {
  case "$1" in
    \[*) printf '%s]\n' "${1%%]*}" ;;
    *) printf '%s\n' "${1%%:*}" ;;
  esac
}

# mdm_site_dir: makes ./site.d, for your own Caddy rules (the edge imports site.d/*.caddy into its site; updates never
# touch it), with a short README, and sets MDMESH_SITE_DIR to its absolute path for .env: compose mounts it from there,
# also when the updater runs compose from inside its own container. A directory whose path .env can't hold as it is
# (a space, a quote, a $) is left out, saying so: the edge then mounts an empty volume instead.
mdm_site_dir() {
  local d
  MDMESH_SITE_DIR=''
  if ! { mkdir -p site.d && chmod 755 site.d; }; then warn "Could not make site.d here: your own edge rules can't be added."; return 0; fi
  if [ ! -e site.d/README ]; then
    printf '%s\n' "Your own rules for MDMesh's edge (Caddy). Every *.caddy file here is imported into the edge's site," \
      "after MDMesh's own settings. Updates never change this directory. Make each file readable by everyone" \
      "(chmod 644), check it, then restart the edge (from the install directory):" \
      "  docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile && docker compose restart caddy" \
      "Wrap a rule that answers requests (respond, redir) in handle { } or route { }: MDMesh's own handle blocks run" \
      "before a bare one." \
      "How, with an example: $MDM_DOCS/DEPLOY.md#your-own-edge-rules" > site.d/README
    chmod 644 site.d/README
  fi
  d=$(pwd -P)
  case "$d" in
    *[!${_MDM_ALNUM}._/+@-]*) warn "site.d is not connected to the edge: its path ($d) has a character .env can't hold as it is ($MDM_DOCS/DEPLOY.md#your-own-edge-rules)." ;;
    *) MDMESH_SITE_DIR="$d/site.d" ;;
  esac
}

# mdm_mode_env: the .env lines only this mode writes (behind your proxy: where the edge listens, whom it believes, and
# whether it speaks HTTPS to your proxy).
mdm_mode_env() {
  [ "$MODE" = 3 ] || return 0
  printf '%s\n' "MDMESH_BIND=${PROXY_BIND}" "TRUSTED_PROXIES=\"${PROXY_TRUSTED}\"" "MDMESH_ORIGIN_TLS=${ORIGIN_TLS:-0}"
}
# <<< modes.sh functions <<<

# One stack per Docker project name (install/lib/stack.sh): a verbatim copy, like the ones above.
# >>> stack.sh functions (quickstart.sh keeps a verbatim copy) >>>
# mdm_stack_guard PROJECT NEW: stops the installer when Docker already has a stack of PROJECT that is not this
# directory's. Its containers' com.docker.compose.project.working_dir labels name their directory: any other than this
# one is refused, naming it. (Containers the updater recreated say /project, its own mount of the install directory:
# they don't count either way.) With NEW=1 (no .env here yet: the installer writes a new one) any container or volume of
# PROJECT is refused, since a new .env's secrets can't work with them; NEW=0 (this directory's .env is used) allows this
# directory's own. Fails closed when Docker can't be asked, and on a container that names no directory.
mdm_stack_guard() {
  local project=$1 new=$2 here dirs d other='' ours=0 n=0 unknown=0 vols
  here=$(pwd -P)
  dirs=$(docker ps -a --filter "label=com.docker.compose.project=$project" \
    --format 'dir:{{.Label "com.docker.compose.project.working_dir"}}') \
    || { err "Could not list Docker's containers (docker ps failed, above): not starting anything. Nothing was changed."; exit 1; }
  while IFS= read -r d; do
    [ -n "$d" ] || continue
    n=$((n + 1)); d=${d#dir:}
    [ -n "$d" ] || { unknown=1; continue; }
    [ "$d" != /project ] || continue
    if [ "$d" -ef "$here" ]; then ours=1; else other="$other$d
"; fi
  done <<EOF
$dirs
EOF
  other=$(printf '%s' "$other" | sort -u | tr '\n' ' ' | sed 's/ $//')
  if [ -n "$other" ] || [ "$unknown" = 1 ] || { [ "$n" -gt 0 ] && { [ "$new" = 1 ] || [ "$ours" = 0 ]; }; }; then
    if [ -n "$other" ]; then
      err "An MDMesh install already exists in $other (Docker project $project)."
    elif [ "$ours" = 1 ] && [ "$unknown" = 0 ]; then
      err "The containers of an MDMesh install in this directory exist, but its .env is gone (Docker project $project)."
    else
      err "An MDMesh install already exists on this machine (Docker project $project; 'docker compose ls' shows where)."
    fi
    err "Starting another here would stop it and take over its containers. To update it, use Settings → Updates in its"
    err "console, or run ./setup.sh --update in its directory if it is a clone. Nothing was changed."
    exit 1
  fi
  [ "$new" = 1 ] || return 0
  vols=$(docker volume ls -q --filter "label=com.docker.compose.project=$project") \
    || { err "Could not list Docker's volumes (docker volume ls failed, above): not starting anything. Nothing was changed."; exit 1; }
  vols=$(printf '%s' "$vols" | tr '\n' ' ' | sed 's/ *$//')
  if [ -n "$vols" ]; then
    err "Docker still has the data of an MDMesh install (volumes: $vols), with none of its containers. Its database"
    err "keeps the password in that install's .env: run 'docker compose up -d' in that install's directory to start it"
    err "again. To start over instead, remove them: docker volume rm $vols (this deletes ALL of that install's data)."
    err "Nothing was changed."
    exit 1
  fi
}
# mdm_edge_up: the edge (caddy, which every phone and browser reaches MDMesh through) is running, and still the same run
# MDM_EDGE_SETTLE seconds later (default 5): an edge that can't load its configuration (a rule in site.d, proxy
# settings docker/edge-peers.sh refuses) exits at start and is restarted over and over. When it isn't, it says so with
# the edge's last log lines, and fails. Run from the install directory, after `docker compose up`.
mdm_edge_up() {
  local id a='' b=''
  id=$(docker compose ps -a -q caddy 2> /dev/null | head -1)
  if [ -n "$id" ]; then
    a=$(docker inspect --format '{{.State.Status}} {{.State.StartedAt}}' "$id" 2> /dev/null) || a=''
    sleep "${MDM_EDGE_SETTLE:-5}"
    b=$(docker inspect --format '{{.State.Status}} {{.State.StartedAt}}' "$id" 2> /dev/null) || b=''
    if [ "${a%% *}" = running ] && [ "$a" = "$b" ]; then return 0; fi
  fi
  err "The edge (caddy) did not stay up (${b:-${a:-no container}}), so MDMesh can't be reached. Its last log lines:"
  docker compose logs --tail 20 caddy 2>&1 | sed 's/^/    /' >&2 || true
  return 1
}
# <<< stack.sh functions <<<

# The only arguments: one hosting mode, which answers the first question. Nothing else runs before the answers are
# loaded (the tunnel token would be in its environment), so this only takes note.
MODE_FLAG=''
for a in "$@"; do
  case "$a" in
    --tunnel|--domain|--proxy)
      [ -z "$MODE_FLAG" ] || [ "$MODE_FLAG" = "${a#--}" ] || { err "--${MODE_FLAG} and $a name different hosting modes: give one."; exit 2; }
      MODE_FLAG=${a#--} ;;
    *) err "Unknown option: $a (quickstart.sh takes one of --tunnel, --domain or --proxy)."; exit 2 ;;
  esac
done

# Every answer is checked here, before anything else happens.
mdm_answers_load
mdm_mode_from_flag "$MODE_FLAG"
mdm_answer mode MODE mode MDMESH_MODE
mdm_answer repo GH_REPO github-repo MDMESH_REPO
mdm_answer image-owner IMAGE_OWNER image-owner MDMESH_IMAGE_OWNER
mdm_answer host HOST hostname MDMESH_HOST
mdm_answer tunnel-token TUNNEL_TOKEN tunnel-token MDMESH_TUNNEL_TOKEN
mdm_answer acme-email ACME_EMAIL email MDMESH_ACME_EMAIL
mdm_answer bind PROXY_BIND bind MDMESH_BIND
mdm_answer trusted-proxies PROXY_TRUSTED proxies MDMESH_TRUSTED_PROXIES
mdm_mode_check_answers
# Behind your own proxy needs a release from 0.6.0 on: one named in advance is refused now, before anything happens.
if mdm_given mode && [ "$MODE" = 3 ] && [[ "${MDMESH_VERSION:-}" =~ ^v?0\.([0-9]+)\. ]] && [ "$((10#${BASH_REMATCH[1]}))" -lt 6 ]; then
  _mdm_answer_fail "MDMESH_VERSION=${MDMESH_VERSION}: behind your own proxy came in 0.6.0, so install 0.6.0 or later, or pick another mode."
fi
mdm_need mode; mdm_need host
if mdm_given mode && [ "$MODE" = 1 ]; then mdm_need tunnel-token; fi
if [ "${MDMESH_DRY_RUN:-0}" = 1 ]; then
  say "== MDMesh quick start: dry run (MDMESH_DRY_RUN=1) =="
  echo "The answers check out. Nothing on this host was checked or changed. What a run would do:"
  mdm_plan_line mode ''
  mdm_plan_line repo "$REPO"
  mdm_plan_line image-owner "$IMAGE_OWNER_DEFAULT"
  mdm_plan_line host ''
  mdm_mode_plan
  [ -z "${MDMESH_RELEASE_PUBKEY:-}" ] || mdm_plan_row release-pubkey '(custom)' MDMESH_RELEASE_PUBKEY
  _dir=${MDMESH_DIR:-mdmesh}; case "$_dir" in /*) ;; *) _dir="$PWD/$_dir" ;; esac
  _repo=$REPO; if mdm_given repo && [ -n "$GH_REPO" ]; then _repo=$GH_REPO; fi
  _rel="the latest release of ${_repo}"; [ -z "${MDMESH_VERSION:-}" ] || _rel="release v${MDMESH_VERSION#v} of ${_repo}"
  echo "Then: download ${_rel}, check it against its signed manifest, and run it with Docker in ${_dir}."
  if mdm_given host; then echo "Its base URL: https://${HOST}"; fi
  exit 0
fi

command -v docker >/dev/null || { err "Docker is required."; exit 1; }
docker compose version >/dev/null 2>&1 || { err "Docker Compose v2 is required ('docker compose')."; exit 1; }
command -v curl    >/dev/null || { err "curl is required."; exit 1; }
command -v openssl >/dev/null || { err "openssl is required."; exit 1; }

DIR="${MDMESH_DIR:-mdmesh}"
# Two statements, not `mkdir && cd`: under set -e a failure before an && is not fatal, so a failed mkdir would fall
# through and the rest would run in the wrong directory.
mkdir -p -- "$DIR"
cd -P -- "$DIR"
# Everything here is read, sourced or handed to docker as this user (often root): the directory and whatever the quick
# start uses in it must belong to this user or root, and nobody else may write them. A directory someone else made (in
# /tmp, say) is refused, so they can't plant a .env, a compose file or db.sh, or swap a verified file before it runs.
# This user's own paths that a looser umask left group- or world-writable (an earlier run's, under 0002) are made
# writable by this user only, the directory first: from then on nobody else can change what is in it.
for _p in . install install/lib install/sql .env .quickstart-unfinished $MDM_QUICKSTART_FILES docker-compose.yml site.d; do
  [ -e "$_p" ] || [ -L "$_p" ] || continue
  if [ ! -L "$_p" ] && [ -O "$_p" ] && [ -n "$(find "$_p" -prune \( -perm -020 -o -perm -002 \) -print 2>/dev/null)" ]; then
    chmod go-w -- "$_p" || true
  fi
  if [ -L "$_p" ] || [ -z "$(find "$_p" -prune \( -user "$EUID" -o -user 0 \) ! -perm -020 ! -perm -002 -print 2>/dev/null)" ]; then
    err "$(pwd)/${_p#./}: the install directory and what is in it must belong to you (or root), not be links, and be"
    err "writable by nobody else. Install into a directory of your own (MDMESH_DIR). Nothing was changed."
    exit 1
  fi
done
RESUME_HINT="Fix it, then run the quick start again with MDMESH_DIR=$(pwd): it finishes this install with the same .env. Keep .env: the database has its password."
# verify_release: RELEASE of GH_REPO, checked against its signed manifest (see "What the quick start trusts" above):
# the signature, the version, and the image digests under IMAGE_OWNER. Sets MANIFEST, SERVER_TAG, WEB_TAG,
# SUPERVISOR_TAG and RAW. Stops the quick start when anything does not verify.
ENV_TMP=''
verify_release() {
  say "Verifying release v${RELEASE} of ${GH_REPO}…"
  VERIFY_DIR=$(mktemp -d)
  cleanup_verify() {
    [ -z "$ENV_TMP" ] || rm -f -- "$ENV_TMP"   # a .env write that failed or was interrupted: it holds the new secrets
    rm -f -- "$VERIFY_DIR/manifest.json" "$VERIFY_DIR/manifest.json.minisig" "$VERIFY_DIR"/dl.*
    rmdir -- "$VERIFY_DIR" 2>/dev/null || true
  }
  trap cleanup_verify EXIT
  MANIFEST="$VERIFY_DIR/manifest.json"
  REL_URL="https://github.com/${GH_REPO}/releases/download/v${RELEASE}"
  if ! curl -fsSL -m 60 "${REL_URL}/manifest.json" -o "$MANIFEST" \
     || ! curl -fsSL -m 60 "${REL_URL}/manifest.json.minisig" -o "$VERIFY_DIR/manifest.json.minisig"; then
    err "Could not download manifest.json and manifest.json.minisig from ${REL_URL} (is v${RELEASE} a published release with a signed manifest?)."
    exit 1
  fi
  VERIFY_RC=0
  mdm_verify_manifest "${MDMESH_RELEASE_PUBKEY:-$MDM_RELEASE_PUBKEY_DEFAULT}" "$MANIFEST" "$VERIFY_DIR/manifest.json.minisig" || VERIFY_RC=$?
  if [ "$VERIFY_RC" = 2 ]; then
    err "Checking the release signature needs OpenSSL 3.0+ or minisign (this host: $(openssl version 2>/dev/null || echo 'no openssl'))."
    err "Install minisign (your distribution's package, or https://github.com/jedisct1/minisign/releases) and re-run. Nothing was installed."
    exit 1
  fi
  if [ "$VERIFY_RC" != 0 ]; then
    err "The manifest of release v${RELEASE} of ${GH_REPO} is not signed by the release key (reason above). Nothing was installed."
    if [ "$GH_REPO" != "$REPO" ] && [ -z "${MDMESH_RELEASE_PUBKEY:-}" ]; then
      err "A fork signs with its own key: run that repository's quickstart.sh, or set MDMESH_RELEASE_PUBKEY to the key line of its release/minisign.pub."
    fi
    exit 1
  fi
  # Bind the signed manifest to this release: an older release's manifest is signed too.
  MANIFEST_VERSION=$(mdm_manifest_field "$MANIFEST" version '[0-9A-Za-z.+-]+' || true)
  if [ "$MANIFEST_VERSION" != "$RELEASE" ]; then
    err "The signed manifest is for version ${MANIFEST_VERSION:-(none)}, not v${RELEASE}. Nothing was installed."; exit 1
  fi
  # The image digests: all three, under the owner chosen above.
  SERVER_REF=$(mdm_manifest_field "$MANIFEST" server "$MDM_IMAGE_REF_ERE" || true)
  WEB_REF=$(mdm_manifest_field "$MANIFEST" web "$MDM_IMAGE_REF_ERE" || true)
  SUPERVISOR_REF=$(mdm_manifest_field "$MANIFEST" supervisor "$MDM_IMAGE_REF_ERE" || true)
  if [ -n "$SERVER_REF" ] && [ -n "$WEB_REF" ] && [ -n "$SUPERVISOR_REF" ]; then
    # Each ref must be exactly its own image under this owner (as supervisor/lib.js imagePins and apply.sh require).
    for _pair in "server $SERVER_REF" "web $WEB_REF" "supervisor $SUPERVISOR_REF"; do
      _name=${_pair%% *}; _ref=${_pair#* }
      if [ "${_ref%@*}" != "ghcr.io/${IMAGE_OWNER}/mdmesh-${_name}" ]; then
        err "Release v${RELEASE} of ${GH_REPO} publishes its ${_name} image as ${_ref%@*}, not ghcr.io/${IMAGE_OWNER}/mdmesh-${_name}. Re-run and enter the image owner it names."
        exit 1
      fi
    done
    SERVER_TAG="${RELEASE}@${SERVER_REF#*@}"; WEB_TAG="${RELEASE}@${WEB_REF#*@}"; SUPERVISOR_TAG="${RELEASE}@${SUPERVISOR_REF#*@}"
  else
    err "The signed manifest of v${RELEASE} does not pin the server, web and supervisor images by digest, so the quick start can't install it."
    err "(A fork publishing with an older release workflow: update its release/ scripts and .github/workflows/release.yml.)"
    exit 1
  fi
  RAW="https://raw.githubusercontent.com/${GH_REPO}/v${RELEASE}"
  say "Installing release v${RELEASE} of ${GH_REPO}."
}

# fetch_release_files: every file of MDM_QUICKSTART_FILES from RAW, each checked against its SHA-256 in the signed
# manifest, then moved into place over whatever is there (a resumed install's copies too), so nothing unverified is
# ever sourced or run. docker-compose.proxy.yml only when the release has it (0.6.0 on).
fetch_release_files() {
  local n f want got dest FETCHED
  say "Downloading the pull-only compose + seed…"
  # Every file is downloaded to a temporary name and checked against its SHA-256 in the signed manifest; only when all of
  # them match are they moved into place. db.sh is sourced below, so nothing unverified ever runs.
  n=0; FETCHED=''
  for f in $MDM_QUICKSTART_FILES; do
    n=$((n + 1))
    want=$(mdm_manifest_field "$MANIFEST" "$f" "$MDM_SHA256_ERE" || true)
    # Before 0.6.0 (proxy mode was refused above for such a release).
    if [ -z "$want" ] && [ "$f" = docker-compose.proxy.yml ]; then continue; fi
    [ -n "$want" ] || { err "The signed manifest of v${RELEASE} has no SHA-256 for ${f}, so it can't be verified. Nothing was installed."; exit 1; }
    curl -fsSL -m 120 "${RAW}/${f}" -o "$VERIFY_DIR/dl.$n" || { err "Could not download ${RAW}/${f}. Nothing was installed."; exit 1; }
    got=$(mdm_sha256 "$VERIFY_DIR/dl.$n")
    if [ "$got" != "$want" ]; then
      err "${f} from ${RAW} does not match the signed release (SHA-256 ${got}, expected ${want}). Nothing was installed."; exit 1
    fi
    FETCHED="$FETCHED $n"
  done
  n=0
  for f in $MDM_QUICKSTART_FILES; do
    n=$((n + 1))
    case " $FETCHED " in *" $n "*) ;; *) continue ;; esac
    dest=$f; [ "$f" = docker-compose.release.yml ] && dest=docker-compose.yml
    mkdir -p -- "$(dirname -- "$dest")"
    mv -f -- "$VERIFY_DIR/dl.$n" "$dest"
  done
}

# resume_install: finishes an install this quick start began here (.quickstart-unfinished) with its .env, whose
# secrets the database already has. Nothing on disk is trusted but that private .env, and each of its values is checked
# as a fresh run checks it: the release (CURRENT_VERSION) is verified again against its signed manifest, the files are
# downloaded and checked again over the ones here before db.sh is sourced, and the image pins in .env must be the
# manifest's.
resume_install() {
  local v
  say "== MDMesh quick start: finishing the install in $(pwd) with its .env =="
  mdm_any_answered && warn "The MDMESH_* answers are not used: this install's .env is."
  _mdm_private_file "$(pwd)/.env" || { err "$(pwd)/.env must belong to you (or root) and be private to you (chmod 600). Nothing was changed."; exit 1; }
  envval() { sed -n "s/^$1=//p" .env | head -1 | sed 's/^"\(.*\)"$/\1/'; }
  RELEASE=$(envval CURRENT_VERSION); GH_REPO=$(envval GITHUB_REPO); IMAGE_OWNER=$(envval IMAGE_OWNER); BASE_URL=$(envval BASE_URL)
  [[ "$RELEASE" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.+-]+)?$ ]] || { err ".env: CURRENT_VERSION is not a release version. Nothing was changed."; exit 1; }
  for v in "GITHUB_REPO|$GH_REPO|github-repo" "IMAGE_OWNER|$IMAGE_OWNER|image-owner"; do
    _MDM_V=${v#*|}; _MDM_V=${_MDM_V%|*}
    _mdm_answer_check "${v##*|}"
    { [ -z "$_MDM_WHY" ] && [ -n "$_MDM_V" ]; } || { err ".env: ${v%%|*} ${_MDM_WHY:-is empty}. Nothing was changed."; exit 1; }
  done
  mdm_check_base_url BASE_URL || { err ".env: BASE_URL is not valid (why above). Nothing was changed."; exit 1; }
  HOST=${BASE_URL#*://}; HOST=${HOST%%/*}
  mdm_check_host HOST || { err ".env: BASE_URL's host is not valid (why above). Nothing was changed."; exit 1; }
  COMPOSE_FILE=$(envval COMPOSE_FILE); COMPOSE_PROFILES=$(envval COMPOSE_PROFILES)
  case "$COMPOSE_FILE|$COMPOSE_PROFILES" in
    'docker-compose.yml|cloudflare'|'docker-compose.yml:docker-compose.domain.yml|'|'docker-compose.yml:docker-compose.proxy.yml|') ;;
    *) err ".env: COMPOSE_FILE and COMPOSE_PROFILES are not a hosting mode the quick start writes. Nothing was changed."; exit 1 ;;
  esac
  # Its own containers and volumes are expected; another directory's install is not touched.
  _project=$(envval COMPOSE_PROJECT_NAME)
  case "${_project:-mdmesh}" in *[!a-z0-9_-]*) err ".env: COMPOSE_PROJECT_NAME is not a Docker project name. Nothing was changed."; exit 1 ;; esac
  mdm_stack_guard "${_project:-mdmesh}" 0
  MODE=$(mdm_mode_of_env)
  PROXY_BIND=$(envval MDMESH_BIND); ORIGIN_TLS=$(envval MDMESH_ORIGIN_TLS)
  if [ "$MODE" = 3 ]; then
    _mdm_bind_ok "$PROXY_BIND" || { err ".env: MDMESH_BIND is not an address and port. Nothing was changed."; exit 1; }
    # The proxies the edge believes: as a fresh run checks the answer, and `host` only with a loopback bind.
    _MDM_WHY=''; _mdm_proxies_check "$(envval TRUSTED_PROXIES)"
    { [ -z "$_MDM_WHY" ] && [ -n "$_MDM_V" ]; } || { err ".env: TRUSTED_PROXIES ${_MDM_WHY:-is empty}. Nothing was changed."; exit 1; }
    v=$(mdm_proxy_trust_problem "$PROXY_BIND" "$_MDM_V")
    [ -z "$v" ] || { err ".env: TRUSTED_PROXIES and MDMESH_BIND: $v. Nothing was changed."; exit 1; }
  fi
  # Your own edge rules: this install's site.d (as mdm_site_dir writes it), or none.
  v=$(envval MDMESH_SITE_DIR)
  [ -z "$v" ] || [ "$v" = "$(pwd -P)/site.d" ] || { err ".env: MDMESH_SITE_DIR is not this install's site.d ($(pwd -P)/site.d). Nothing was changed."; exit 1; }
  verify_release
  for v in "SERVER_VERSION|$SERVER_TAG" "WEB_VERSION|$WEB_TAG" "SUPERVISOR_VERSION|$SUPERVISOR_TAG"; do
    [ "$(envval "${v%%|*}")" = "${v#*|}" ] || { err ".env: ${v%%|*} is not the image release v${RELEASE}'s signed manifest pins. Nothing was changed."; exit 1; }
  done
  fetch_release_files
  # shellcheck source=install/lib/db.sh
  . ./install/lib/db.sh
  mdm_mode_settings "$MODE" rerun
  ADMIN_PASSWORD=$(rand); RESET_TOKEN=$(openssl rand -hex 16)
  finish_install
}

# finish_install: pull, start, seed and report, with the .env in this directory. A new install runs it once .env is
# written; a run that stopped after that (.quickstart-unfinished is still there) is resumed with it, keeping .env:
# the database volume already has its password.
finish_install() {
  say "Pulling images…"
  docker compose pull || { err "Could not pull the v${RELEASE} images from ghcr.io/${IMAGE_OWNER} (pinned by digest in .env). Has a release been published? (to cut one, see RELEASING.md)"; err "$RESUME_HINT"; exit 1; }
  # The init marker the wait below reads is on the server's data volume, and the server writes it only when it is absent.
  # The entrypoint removes the previous start's marker only in images after v0.3.1, and this script (the latest
  # release's, or main's) may be installing v0.3.1 or older, so a re-run over an existing volume would read the last run's result. Remove it here:
  # the old server stopped first (so it cannot write it again in between), then a one-off container of the server image
  # that runs rm as the server's own user (never as root on the volume; rm -f removes a planted link, not its target).
  # On a fresh install there is no container to stop and no marker to remove, and the one-off only creates the volume
  # that `up` would create anyway.
  say "Starting the stack…"
  docker compose stop server >/dev/null 2>&1 || true
  docker compose run --rm --no-deps -T -u mdmesh --entrypoint rm server -f /opt/mdmesh/initialized.txt \
    || { err "Could not remove the previous start's init marker (/opt/mdmesh/initialized.txt) from the server's volume."; exit 1; }
  docker compose up -d || { err "docker compose up failed (above). $(mdm_up_hint)"; err "$RESUME_HINT"; exit 1; }

  say "Waiting for the server to finish first-boot (Liquibase)…"
  BOOTED=0
  for _ in $(seq 1 60); do
    sleep 5   # first: `up -d` can return before the entrypoint has removed the previous start's marker
    if docker compose exec -T server test -s /opt/mdmesh/initialized.txt 2>/dev/null; then BOOTED=1; break; fi
  done
  if [ "$BOOTED" != 1 ]; then
    err "Server did not finish first-boot within ~5 minutes. Last server logs:"
    docker compose logs --tail 40 server 2>&1 || true
    err "$RESUME_HINT"; exit 1
  fi
  # The marker holds "OK" or the server's initialization error (docker/entrypoint.sh removes the previous start's marker,
  # so it is this boot's). It is read as the server's own user: the volume is that account's, and the container's root
  # would follow a link planted there.
  INIT_RESULT=$(docker compose exec -T -u mdmesh server cat /opt/mdmesh/initialized.txt 2>/dev/null || true)
  if ! grep -q '^OK' <<< "$INIT_RESULT"; then
    err "The server reported an initialization error:"
    printf '%s\n' "${INIT_RESULT:0:2000}" | tr -d '\000-\010\013-\037\177' | sed 's/^/    /'   # the server's text: no control chars
    err "$RESUME_HINT"; exit 1
  fi
  # The edge too, before the database is seeded (so a run resumed after fixing it still finds a fresh database).
  mdm_edge_up || { err "$RESUME_HINT"; exit 1; }

  # Same rules as setup.sh (shared install/lib/db.sh): seed only a fresh database, verify the seed, then
  # the always-run repairs that switch on QR/token enrollment (this step used to be missing here).
  # shellcheck disable=SC2034  # PSQL is consumed by install/lib/db.sh
  PSQL=(docker compose exec -T postgres psql -U mdmesh -d mdmesh)
  STATE=$(mdm_db_state)
  case "$STATE" in
    fresh)  ;;
    seeded) err "This database is already seeded — the quick start is for new installs only. To upgrade, use ./setup.sh in a clone."; exit 1 ;;
    *)      err "Could not confirm a fresh database (state: ${STATE}). Aborting before touching data."; exit 1 ;;
  esac
  say "Seeding settings + admin…"
  if ! mdm_seed "admin@${HOST}" install/sql/hmdm_init.en.sql "$ADMIN_PASSWORD" "$RESET_TOKEN"; then
    err "Seeding failed — the install is NOT usable yet. Fix the error above."; err "$RESUME_HINT"; exit 1
  fi
  if ! mdm_post_seed install/sql/post_seed.sql; then
    err "Post-seed repairs failed — device enrollment would not work. Fix the error above."; err "$RESUME_HINT"; exit 1
  fi

  echo
  say "== MDMesh is up =="
  echo "  Console:        ${BASE_URL}"
  echo "  REST API base:  ${BASE_URL}/rest"
  echo "  Login:          admin"
  echo "  Password:       ${ADMIN_PASSWORD}   (temporary — you'll set your own on first login)"
  echo "  Directory:      $(pwd)   (run 'docker compose' commands from here)"
  echo "  Reached via:    ${ENTRY}"
  echo
  warn "Save that password now — it is not stored anywhere in clear text."
  echo "Next: $EXTRA_NOTE"
  if [ "$MODE" = 3 ] && [ "$ORIGIN_TLS" = 1 ]; then
    echo "      Your proxy trusts MDMesh's CA: docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./mdmesh-origin-ca.crt"
  fi
  echo "Enroll devices from the console's Enroll page (it builds the QR with ${BASE_URL})."
  rm -f -- .quickstart-unfinished
}

# An .env here: an install. One the quick start didn't finish is resumed; any other is left alone (never delete .env
# while its volumes exist: the database keeps the password in it).
if [ -e .env ] || [ -L .env ]; then
  if [ -f .quickstart-unfinished ] && [ -f .env ] && [ ! -L .env ]; then
    resume_install
    exit 0
  fi
  err "An .env already exists in $(pwd): an install is here, so the quick start leaves it alone. Don't delete that .env"
  err "while the install's volumes exist (its database keeps the password in it): update from the console, or edit"
  err ".env and run 'docker compose up -d' there. To start over, first 'docker compose down -v' there (deletes ALL data)."
  exit 1
fi

# A new install: no other install's containers or volumes may exist (install/lib/stack.sh), or compose would take them
# over. Checked before any question.
mdm_stack_guard mdmesh 1

say "== MDMesh quick start (published images) =="
echo "Installing into: $(pwd)"
echo
mdm_mode_choose "Without Docker? See Native in DEPLOY.md: $MDM_DOCS/DEPLOY.md#option-c--native-no-docker"

while :; do
  mdm_answered repo || read -rp "Pull releases from GitHub repo [${REPO}]: " GH_REPO   # answer: repo
  mdm_prompt_ok repo GH_REPO && break
done
GH_REPO="${GH_REPO:-$REPO}"
while :; do
  mdm_answered image-owner || read -rp "Image owner (GHCR, lowercase) [${IMAGE_OWNER_DEFAULT}]: " IMAGE_OWNER   # answer: image-owner
  mdm_prompt_ok image-owner IMAGE_OWNER && break
done
IMAGE_OWNER="${IMAGE_OWNER:-$IMAGE_OWNER_DEFAULT}"

# Install exactly one published release, verified before anything is downloaded from it: its signed manifest (see "What
# the quick start trusts" above) names the image digests and the SHA-256 of each file fetched from its tag. The server,
# web and supervisor images are pinned by digest (SERVER_VERSION=<version>@sha256:…: compose runs repo:tag@digest, which
# docker pulls by digest), and CURRENT_VERSION names the same release, so the console doesn't report it as an update, a
# rollback has a real version to return to, and a tag moved later can't change what runs. Each update the supervisor
# applies moves the pins to the next signed release. Nothing falls back to main or :latest: if the release can't be
# resolved or doesn't verify, the quick start stops.
if [ -n "${MDMESH_VERSION:-}" ]; then
  RELEASE=${MDMESH_VERSION#v}
  [[ "$RELEASE" =~ ^([0-9]+)\.([0-9]+)\.[0-9]+([-+][0-9A-Za-z.+-]+)?$ ]] || { err "MDMESH_VERSION=${MDMESH_VERSION} is not a release version (X.Y.Z)."; exit 1; }
  if [ "$((10#${BASH_REMATCH[1]}))" -eq 0 ] && [ "$((10#${BASH_REMATCH[2]}))" -lt 5 ]; then
    err "Releases before 0.5.0 can't be installed with this quick start: it pins the images and verifies each download"
    err "against the signed release, which those releases don't record. Run the quickstart.sh from that release's tag instead:"
    err "  bash <(curl -fsSL https://raw.githubusercontent.com/${REPO}/refs/tags/v${RELEASE}/quickstart.sh)"
    exit 1
  fi
else
  RELEASE=$(latest_release "$GH_REPO")
fi
if [ -z "$RELEASE" ]; then
  err "Could not resolve the latest release of ${GH_REPO} (GitHub API unreachable or rate-limited, or no release yet)."
  err "The quick start installs only a published, signed release. Try again in a few minutes, or name the release:"
  err "  MDMESH_VERSION=X.Y.Z bash <(curl -fsSL https://raw.githubusercontent.com/${REPO}/refs/tags/vX.Y.Z/quickstart.sh)"
  exit 1
fi
[ -z "${MDMESH_RELEASE_PUBKEY:-}" ] || warn "Using MDMESH_RELEASE_PUBKEY: a custom release signing key, not MDMesh's."
verify_release

DB_PASSWORD=$(rand); HASH_SECRET=$(rand); ADMIN_PASSWORD=$(rand); RESET_TOKEN=$(openssl rand -hex 16)

if [ "$MODE" = 3 ] && ! mdm_manifest_field "$MANIFEST" docker-compose.proxy.yml "$MDM_SHA256_ERE" > /dev/null; then
  err "Release v${RELEASE} has no proxy mode (it came in 0.6.0): install 0.6.0 or later, or pick another mode. Nothing was installed."; exit 1
fi
mdm_mode_questions

fetch_release_files
# Shared seed rules with setup.sh / the native installer (seed gate, verified seed, post-seed repairs).
# Version coupling: this script may be newer than the release it installs (MDMESH_VERSION, or a copy from main), but
# db.sh (like the compose file and the seed) comes from the release tag being installed, so it matches the images. Call only db.sh functions, with only the arguments and output, that the
# latest published release already has; a db.sh change becomes usable here once a release ships it. (install/lib/db.sh
# states the same rule for its side.)
# shellcheck source=install/lib/db.sh
. ./install/lib/db.sh

# Owner-only from the start: written to a new mode-600 file (mktemp), then renamed to .env, so no other user can read
# the secrets at any moment.
trap 'exit 130' INT; trap 'exit 143' TERM   # so an interrupt runs cleanup_verify too
mdm_site_dir
ENV_TMP=$(mktemp .env.new.XXXXXX)
cat > "$ENV_TMP" <<EOF
DB_NAME=mdmesh
DB_USER=mdmesh
DB_PASSWORD=${DB_PASSWORD}
BASE_URL=${BASE_URL}
HASH_SECRET=${HASH_SECRET}
SECURE_ENROLLMENT=0
SITE_ADDRESS=${SITE_ADDRESS}
ACME_EMAIL=${ACME_EMAIL}
TUNNEL_TOKEN=${TUNNEL_TOKEN}
IMAGE_OWNER=${IMAGE_OWNER}
SERVER_VERSION=${SERVER_TAG}
WEB_VERSION=${WEB_TAG}
SUPERVISOR_VERSION=${SUPERVISOR_TAG}
GITHUB_REPO=${GH_REPO}
UPDATE_CHANNEL=stable
POLL_INTERVAL_HOURS=6
CURRENT_VERSION=${RELEASE}
GITHUB_TOKEN=
AUTO_UPDATE=0
COMPOSE_PROJECT_NAME=mdmesh
COMPOSE_FILE=${COMPOSE_FILE}
COMPOSE_PROFILES=${COMPOSE_PROFILES}
MDMESH_SITE_DIR=${MDMESH_SITE_DIR}
$(mdm_mode_env)
SMTP_HOST=
SMTP_PORT=25
SMTP_FROM=mdm@${HOST}
EOF
# No -T (GNU only): so check again, just before, that nothing named .env has appeared since the start (a directory or a
# link to one would get the file inside it).
if [ -e .env ] || [ -L .env ]; then err "Something named .env appeared in $(pwd) during the install: not overwriting it. Remove it and re-run."; exit 1; fi
: > .quickstart-unfinished   # until finish_install completes: a later run resumes with this .env
mv -f -- "$ENV_TMP" .env
ENV_TMP=''; trap - INT TERM
say "Wrote .env (secrets generated). docker compose reads COMPOSE_FILE/PROFILES from it."

finish_install
