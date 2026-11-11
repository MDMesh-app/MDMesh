#!/usr/bin/env bash
# MDMesh quick start — deploy from PUBLISHED images, no clone and no build. Needs only Docker.
# Run from anywhere (the `bash <(...)` form keeps the prompts interactive):
#
#   bash <(curl -fsSL https://raw.githubusercontent.com/MDMesh-app/MDMesh/main/quickstart.sh)
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
# itself, served from main over HTTPS, with the key in it: whoever can change main could change the key too. A fork
# with its own signing key sets MDMESH_RELEASE_PUBKEY to the key line (line 2) of its release/minisign.pub.
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

# The files downloaded from the release's tag, as named in the manifest's "files" (release/build-manifest.sh).
MDM_QUICKSTART_FILES="docker-compose.release.yml docker-compose.domain.yml install/sql/hmdm_init.en.sql install/sql/post_seed.sql install/lib/db.sh"

# mdm_legacy_file_sha VERSION FILE: the SHA-256 of FILE at the tag of a release published before the manifest recorded
# them (v0.4.0), taken from that tag in git. Fails for any other release. Remove once the latest release has "files".
mdm_legacy_file_sha() {
  case "$1 $2" in
    "0.4.0 docker-compose.release.yml")   echo 855fcf6fcba18bacb135c8d8c5617ddeaf16f4127ff83c619d671083838d4122 ;;
    "0.4.0 docker-compose.domain.yml")    echo 25f31714054e1adfcb7491c2dcce33398f40e4235b9ed0268797ebc460015b07 ;;
    "0.4.0 install/sql/hmdm_init.en.sql") echo 25fd5c6b9ad6eedaf747548285070a24559adc2bf4d27da0e78f363d3834eba4 ;;
    "0.4.0 install/sql/post_seed.sql")    echo fc25c4a5355d1114cd3fbc1c84d3ded147e86e647fbd288048af3bcb0fbc73fe ;;
    "0.4.0 install/lib/db.sh")            echo 80c792957d6a8d5d31639344faee766bba1d0d965c7d1c29668884bbfa55a005 ;;
    *) return 1 ;;
  esac
}

# mdm_sha256 FILE: its SHA-256 in lowercase hex.
mdm_sha256() { openssl dgst -sha256 -r "$1" | cut -d' ' -f1; }
# <<< release verification <<<

# The BASE_URL rule of install/lib/url.sh (see there for what it accepts and why it is an allowlist). A verbatim copy
# of the functions between the markers, not a download: this script runs from main but fetches install/lib from the
# release it installs, which may predate url.sh (see db.sh below). CI (t0-fast, edge entry) fails if the copies differ.
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
_MDM_ANSWER_KEYS=" MDMESH_MODE MDMESH_REPO MDMESH_IMAGE_OWNER MDMESH_HOST MDMESH_TUNNEL_TOKEN MDMESH_ACME_EMAIL MDMESH_BASE_URL MDMESH_HTTP_PORT MDMESH_REPLACE_DATA MDMESH_ASSUME_YES MDMESH_DIR MDMESH_VERSION MDMESH_RELEASE_PUBKEY MDMESH_NONINTERACTIVE MDMESH_DRY_RUN "
_MDM_ANS_GIVEN=" "       # ids of the questions answered in advance
_MDM_ANS_FROM_FILE=" "   # keys set from the answers file
_mdm_answer_fail() { printf 'MDMesh installer: %s\n' "$*" >&2; exit 1; }

# mdm_answers_load: reads the answers file MDMESH_ANSWERS names, if any (keys already in the environment are left
# alone), then checks MDMESH_NONINTERACTIVE and MDMESH_DRY_RUN. Stops the installer on any error.
mdm_answers_load() {
  local f=${MDMESH_ANSWERS-} line key val n=0 seen=" " v
  if [ -n "$f" ]; then
    case "$f" in /*) ;; *) _mdm_answer_fail "MDMESH_ANSWERS must be an absolute path (e.g. \$PWD/answers.env)." ;; esac
    { [ -f "$f" ] && [ -r "$f" ]; } || _mdm_answer_fail "MDMESH_ANSWERS: $f is not a readable file."
    [ "$(wc -c < "$f")" -le 65536 ] || _mdm_answer_fail "MDMESH_ANSWERS: $f is larger than 64 KiB."
    while IFS= read -r line || [ -n "$line" ]; do
      n=$((n + 1)); line=${line%$'\r'}
      case "$line" in ''|'#'*) continue ;; *=*) ;; *) _mdm_answer_fail "$f, line $n: expected KEY=VALUE." ;; esac
      key=${line%%=*}; val=${line#*=}
      case "$_MDM_ANSWER_KEYS" in
        *" $key "*) ;;
        *) case "$key" in *[![:print:]]*) key=$(printf '%q' "$key") ;; esac
           _mdm_answer_fail "$f, line $n: unknown key $key (the keys are in install/options.json)." ;;
      esac
      case "$seen" in *" $key "*) _mdm_answer_fail "$f, line $n: $key is set twice." ;; esac
      seen="$seen$key "
      case "$val" in \"*\"|\'*\') val=${val#?}; val=${val%?} ;; esac
      if [ -z "${!key+x}" ]; then printf -v "$key" '%s' "$val"; _MDM_ANS_FROM_FILE="$_MDM_ANS_FROM_FILE$key "; fi
    done < "$f"
  fi
  for key in MDMESH_NONINTERACTIVE MDMESH_DRY_RUN; do
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
    mode)
      case "$v" in tunnel|1) _MDM_V=1; _MDM_SHOW=tunnel ;; domain|2) _MDM_V=2; _MDM_SHOW=domain ;;
        *) _MDM_WHY='must be tunnel or domain' ;; esac ;;
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
    yes-no)
      case "$v" in yes|no) ;; *) _MDM_WHY='must be yes or no' ;; esac ;;
    flag)
      case "$v" in 0|1) ;; *) _MDM_WHY='must be 1 or 0' ;; esac ;;
    *) _mdm_answer_fail "internal: unknown answer rule $1" ;;
  esac
}

# _mdm_answer_rule_has_default RULE: an empty or missing answer means the question's default (Enter at the prompt).
_mdm_answer_rule_has_default() { case "$1" in github-repo|image-owner|port|email|yes-no) return 0 ;; esac; return 1; }

# mdm_answer ID VAR RULE ENV [ALIAS]: if ENV (or ALIAS, the installer's older name for it) is set, in the environment or
# the answers file, checks its value by RULE and stores it in VAR: question ID is then answered. Stops the installer when
# the value is invalid, or when ENV and ALIAS are both set to different values. An empty value answers the GitHub repo,
# image owner and Let's Encrypt email questions (the default, as Enter at the prompt); for a base URL, the HTTP port,
# Keep/Erase and the confirmation it counts as not set, as it always did for the native installer.
mdm_answer() {
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
  printf -v "_MDM_ANS_SHOW_$k" '%s' "$_MDM_SHOW"; printf -v "_MDM_ANS_SRC_$k" '%s' "$src"
  _MDM_ANS_GIVEN="$_MDM_ANS_GIVEN$id "
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

# Every answer is checked here, before anything else happens.
mdm_answers_load
mdm_answer mode MODE mode MDMESH_MODE
mdm_answer repo GH_REPO github-repo MDMESH_REPO
mdm_answer image-owner IMAGE_OWNER image-owner MDMESH_IMAGE_OWNER
mdm_answer host HOST hostname MDMESH_HOST
mdm_answer tunnel-token TUNNEL_TOKEN tunnel-token MDMESH_TUNNEL_TOKEN
mdm_answer acme-email ACME_EMAIL email MDMESH_ACME_EMAIL
if mdm_given mode; then
  if [ "$MODE" = 1 ]; then mdm_unused acme-email "in tunnel mode"; else mdm_unused tunnel-token "in domain mode"; fi
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
  if ! mdm_given mode; then mdm_plan_line tunnel-token ''; mdm_plan_line acme-email ''
  elif [ "$MODE" = 1 ]; then mdm_plan_line tunnel-token ''; mdm_plan_row acme-email - unused
  else mdm_plan_row tunnel-token - unused; mdm_plan_line acme-email ''; fi
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
[ -f .env ] && { err "An .env already exists in $(pwd) — refusing to overwrite. Remove it to re-run."; exit 1; }

say "== MDMesh quick start (published images) =="
echo "Installing into: $(pwd)"
echo
if ! mdm_answered mode; then
  echo "Hosting mode:"
  echo "  1) Cloudflare Tunnel   (no open ports; Cloudflare manages TLS — needs a domain in Cloudflare)"
  echo "  2) Your own domain     (open 80/443; Caddy auto-provisions a Let's Encrypt cert)"
  MODE=""
  while [ "$MODE" != "1" ] && [ "$MODE" != "2" ]; do
    read -rp "Choose [1/2]: " MODE || { err "No selection (non-interactive run?). Aborting."; exit 1; }   # answer: mode
    case "$MODE" in 1|2) ;; *) warn "Please enter 1 or 2." ;; esac
  done
fi

mdm_answered repo || read -rp "Pull releases from GitHub repo [${REPO}]: " GH_REPO   # answer: repo
GH_REPO="${GH_REPO:-$REPO}"
mdm_answered image-owner || read -rp "Image owner (GHCR, lowercase) [${IMAGE_OWNER_DEFAULT}]: " IMAGE_OWNER   # answer: image-owner
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
  if [ "$((10#${BASH_REMATCH[1]}))" -eq 0 ] && [ "$((10#${BASH_REMATCH[2]}))" -lt 4 ]; then
    err "Releases before 0.4.0 can't be installed with this quick start: it verifies each download against the signed release,"
    err "which those releases don't record. Run the quickstart.sh from that release's tag instead:"
    err "  bash <(curl -fsSL https://raw.githubusercontent.com/${REPO}/v${RELEASE}/quickstart.sh)"
    exit 1
  fi
else
  RELEASE=$(latest_release "$GH_REPO")
fi
if [ -z "$RELEASE" ]; then
  err "Could not resolve the latest release of ${GH_REPO} (GitHub API unreachable or rate-limited, or no release yet)."
  err "The quick start installs only a published, signed release. Try again in a few minutes, or name the release:"
  err "  MDMESH_VERSION=X.Y.Z bash <(curl -fsSL https://raw.githubusercontent.com/${REPO}/main/quickstart.sh)"
  exit 1
fi
say "Verifying release v${RELEASE} of ${GH_REPO}…"
VERIFY_DIR=$(mktemp -d)
cleanup_verify() {
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
# The image digests: all three, under the owner chosen above. A release from before they were recorded (v0.4.0 only, see
# mdm_legacy_file_sha) runs its :X.Y.Z tags and the :latest supervisor, as the quick start did before.
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
elif [ -z "$SERVER_REF$WEB_REF$SUPERVISOR_REF" ] && mdm_legacy_file_sha "$RELEASE" install/lib/db.sh >/dev/null; then
  warn "Release v${RELEASE} predates image digests in its manifest: running its :${RELEASE} tags (the next update pins them)."
  SERVER_TAG="$RELEASE"; WEB_TAG="$RELEASE"; SUPERVISOR_TAG="latest"
else
  err "The signed manifest of v${RELEASE} does not pin the server, web and supervisor images by digest, so the quick start can't install it."
  err "(A fork publishing with an older release workflow: update its release/ scripts and .github/workflows/release.yml.)"
  exit 1
fi
RAW="https://raw.githubusercontent.com/${GH_REPO}/v${RELEASE}"
say "Installing release v${RELEASE} of ${GH_REPO}."

DB_PASSWORD=$(rand); HASH_SECRET=$(rand); ADMIN_PASSWORD=$(rand); RESET_TOKEN=$(openssl rand -hex 16)

if [ "$MODE" = "1" ]; then
  mdm_answered host || read -rp "Public hostname devices will use (e.g. mdm.example.com): " HOST   # answer: host
  mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
  mdm_answered tunnel-token || read -rp "Cloudflare Tunnel token (Zero Trust → Tunnels → your tunnel): " TUNNEL_TOKEN   # answer: tunnel-token
  BASE_URL="https://${HOST}"; SITE_ADDRESS=":80"; ACME_EMAIL=""
  COMPOSE_FILE="docker-compose.yml"; COMPOSE_PROFILES="cloudflare"
  EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80."
else
  mdm_answered host || read -rp "Your domain (DNS already pointing here, e.g. mdm.example.com): " HOST   # answer: host
  mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
  mdm_answered acme-email || read -rp "Email for Let's Encrypt: " ACME_EMAIL   # answer: acme-email
  BASE_URL="https://${HOST}"; SITE_ADDRESS="${HOST}"; TUNNEL_TOKEN=""
  COMPOSE_FILE="docker-compose.yml:docker-compose.domain.yml"; COMPOSE_PROFILES=""
  EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open."
fi
mdm_check_base_url BASE_URL || { err "Check the hostname you entered (the name only, e.g. mdm.example.com), then re-run."; exit 1; }

say "Downloading the pull-only compose + seed…"
# Every file is downloaded to a temporary name and checked against its SHA-256 in the signed manifest (or, for v0.4.0,
# the hash recorded above); only when all of them match are they moved into place. db.sh is sourced below, so nothing
# unverified ever runs.
HAS_FILES=0
grep -q '^  "files": {$' "$MANIFEST" && HAS_FILES=1
n=0
for f in $MDM_QUICKSTART_FILES; do
  n=$((n + 1))
  if [ "$HAS_FILES" = 1 ]; then want=$(mdm_manifest_field "$MANIFEST" "$f" "$MDM_SHA256_ERE" || true)
  else want=$(mdm_legacy_file_sha "$RELEASE" "$f" || true); fi
  [ -n "$want" ] || { err "The signed manifest of v${RELEASE} has no SHA-256 for ${f}, so it can't be verified. Nothing was installed."; exit 1; }
  curl -fsSL -m 120 "${RAW}/${f}" -o "$VERIFY_DIR/dl.$n" || { err "Could not download ${RAW}/${f}. Nothing was installed."; exit 1; }
  got=$(mdm_sha256 "$VERIFY_DIR/dl.$n")
  if [ "$got" != "$want" ]; then
    err "${f} from ${RAW} does not match the signed release (SHA-256 ${got}, expected ${want}). Nothing was installed."; exit 1
  fi
done
n=0
for f in $MDM_QUICKSTART_FILES; do
  n=$((n + 1))
  dest=$f; [ "$f" = docker-compose.release.yml ] && dest=docker-compose.yml
  mkdir -p -- "$(dirname -- "$dest")"
  mv -f -- "$VERIFY_DIR/dl.$n" "$dest"
done
# Shared seed rules with setup.sh / the native installer (seed gate, verified seed, post-seed repairs).
# Version coupling: this script runs from main, but db.sh (like the compose file and the seed) comes from the release
# tag being installed, so it matches the images. Call only db.sh functions, with only the arguments and output, that the
# latest published release already has; a db.sh change becomes usable here once a release ships it. (install/lib/db.sh
# states the same rule for its side.)
# shellcheck source=install/lib/db.sh
. ./install/lib/db.sh

cat > .env <<EOF
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
SMTP_HOST=
SMTP_PORT=25
SMTP_FROM=mdm@${HOST}
EOF
chmod 600 .env
say "Wrote .env (secrets generated). docker compose reads COMPOSE_FILE/PROFILES from it."

say "Pulling images…"
docker compose pull || { err "Could not pull the v${RELEASE} images from ghcr.io/${IMAGE_OWNER} (pinned by digest in .env). Has a release been published? (to cut one, see RELEASING.md)"; exit 1; }
# The init marker the wait below reads is on the server's data volume, and the server writes it only when it is absent.
# The entrypoint removes the previous start's marker only in images after v0.3.1, and this script (served from main) may
# be installing v0.3.1 or older, so a re-run over an existing volume would read the last run's result. Remove it here:
# the old server stopped first (so it cannot write it again in between), then a one-off container of the server image
# that runs rm as the server's own user (never as root on the volume; rm -f removes a planted link, not its target).
# On a fresh install there is no container to stop and no marker to remove, and the one-off only creates the volume
# that `up` would create anyway.
say "Starting the stack…"
docker compose stop server >/dev/null 2>&1 || true
docker compose run --rm --no-deps -T -u mdmesh --entrypoint rm server -f /opt/mdmesh/initialized.txt \
  || { err "Could not remove the previous start's init marker (/opt/mdmesh/initialized.txt) from the server's volume."; exit 1; }
docker compose up -d

say "Waiting for the server to finish first-boot (Liquibase)…"
BOOTED=0
for _ in $(seq 1 60); do
  sleep 5   # first: `up -d` can return before the entrypoint has removed the previous start's marker
  if docker compose exec -T server test -s /opt/mdmesh/initialized.txt 2>/dev/null; then BOOTED=1; break; fi
done
if [ "$BOOTED" != 1 ]; then
  err "Server did not finish first-boot within ~5 minutes. Last server logs:"
  docker compose logs --tail 40 server 2>&1 || true
  err "Fix the issue above and re-run the quick start from this directory ($(pwd))."; exit 1
fi
# The marker holds "OK" or the server's initialization error (docker/entrypoint.sh removes the previous start's marker,
# so it is this boot's). It is read as the server's own user: the volume is that account's, and the container's root
# would follow a link planted there.
INIT_RESULT=$(docker compose exec -T -u mdmesh server cat /opt/mdmesh/initialized.txt 2>/dev/null || true)
if ! grep -q '^OK' <<< "$INIT_RESULT"; then
  err "The server reported an initialization error:"
  printf '%s\n' "${INIT_RESULT:0:2000}" | tr -d '\000-\010\013-\037\177' | sed 's/^/    /'   # the server's text: no control chars
  err "Fix the issue above and re-run the quick start from this directory ($(pwd))."; exit 1
fi

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
  err "Seeding failed — the install is NOT usable yet. Fix the error above and re-run."; exit 1
fi
if ! mdm_post_seed install/sql/post_seed.sql; then
  err "Post-seed repairs failed — device enrollment would not work. Fix the error above and re-run."; exit 1
fi

echo
say "== MDMesh is up =="
echo "  Console:        ${BASE_URL}"
echo "  REST API base:  ${BASE_URL}/rest"
echo "  Login:          admin"
echo "  Password:       ${ADMIN_PASSWORD}   (temporary — you'll set your own on first login)"
echo "  Directory:      $(pwd)   (run 'docker compose' commands from here)"
echo
warn "Save that password now — it is not stored anywhere in clear text."
echo "Next: $EXTRA_NOTE"
echo "Enroll devices from the console's Enroll page (it builds the QR with ${BASE_URL})."
