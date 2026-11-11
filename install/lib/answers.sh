#!/usr/bin/env bash
# Answers given in advance, for the installers' questions (quickstart.sh, setup.sh, install/install-native.sh;
# quickstart.sh keeps a verbatim copy of the functions between the markers below, which CI checks). Source this file
# after install/lib/url.sh; do not execute it. bash 3.2 compatible (quickstart may run under macOS's bash).
#
# Every question an installer asks can be answered by an environment variable (MDMESH_HOST=…), or by a KEY=VALUE line
# in an answers file named by MDMESH_ANSWERS (an absolute path). The environment wins over the file. A question with an
# answer is not asked; one without is asked exactly as before. Each answer is checked with the same rule as the
# question's (url.sh for hostnames and base URLs), and a wrong one stops the installer before it changes anything.
# install/options.json is the contract (each answer's variable, rule, default and question), which the website's
# install wizard reads; tests/installer-answers/run.sh checks it against the scripts.
#
# MDMESH_NONINTERACTIVE=1: never ask. A missing answer with a default takes it; a missing answer without one stops the
# installer, naming the variable. MDMESH_DRY_RUN=1: check the answers, print the plan, and exit before the installer
# checks or changes anything on this host.
#
# The answers file is never sourced: each line is a comment (#…), blank, or KEY=VALUE with a known KEY (see
# _MDM_ANSWER_KEYS) and the VALUE taken literally (one pair of surrounding '…' or "…" is removed).
#
# Secrets (the tunnel token, _MDM_SECRET_KEYS) never reach anything another user or a log could see: they are never
# printed (not in the plan, a "Using" line, or an error about the value), xtrace (set -x) is off while these functions
# hold them, they are never put on a command line, and their environment variable is taken out of the environment as
# soon as the answers are loaded (export -n), then unset once read, so that no child process inherits it.
#
# An answers file chooses what is installed (MDMESH_REPO, MDMESH_RELEASE_PUBKEY, MDMESH_VERSION, …) and can hold the
# tunnel token, so it is read only when it belongs to the user running the installer (or to root) and nobody else may
# read, write or run it (chmod 600). Answers typed at a question are checked with the same rules (mdm_prompt_ok).

# >>> answers.sh functions (quickstart.sh keeps a verbatim copy) >>>
# The keys an answers file may set: every answer and setting in install/options.json (CI checks the two match).
_MDM_ANSWER_KEYS=" MDMESH_MODE MDMESH_REPO MDMESH_IMAGE_OWNER MDMESH_HOST MDMESH_TUNNEL_TOKEN MDMESH_ACME_EMAIL MDMESH_BASE_URL MDMESH_HTTP_PORT MDMESH_REPLACE_DATA MDMESH_ASSUME_YES MDMESH_DIR MDMESH_VERSION MDMESH_RELEASE_PUBKEY MDMESH_NONINTERACTIVE MDMESH_DRY_RUN "
_MDM_SECRET_KEYS=" MDMESH_TUNNEL_TOKEN "
_MDM_ANS_GIVEN=" "       # ids of the questions answered in advance
_MDM_ANS_FROM_FILE=" "   # keys set from the answers file
_mdm_answer_fail() { printf 'MDMesh installer: %s\n' "$*" >&2; exit 1; }

# mdm_answers_load: reads the answers file MDMESH_ANSWERS names, if any (keys already in the environment are left
# alone), then checks MDMESH_NONINTERACTIVE and MDMESH_DRY_RUN. Stops the installer on any error. xtrace stays off
# while it runs (the file may hold a secret).
mdm_answers_load() { local _mdm_x=$-; set +x; _mdm_answers_load; case "$_mdm_x" in *x*) set -x ;; esac; }
# _mdm_private_file FILE: FILE belongs to this user (or root) and nobody else may read, write or run it. Fails closed:
# without find, or when it fails, the file does not count as private.
_mdm_private_file() {
  local r
  command -v find >/dev/null 2>&1 || return 1
  r=$(find "$1" -prune \( -user "$(id -u)" -o -user 0 \) ! -perm -040 ! -perm -020 ! -perm -010 ! -perm -004 \
        ! -perm -002 ! -perm -001 -print 2>/dev/null) || return 1
  [ "$r" = "$1" ]
}
_mdm_answers_load() {
  local f=${MDMESH_ANSWERS-} line key val n=0 seen=" " v
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
  # Secrets leave the environment now: they stay shell variables, so mdm_answer still reads them, but no child sees them.
  # shellcheck disable=SC2163  # the variable named by $key
  for key in $_MDM_SECRET_KEYS; do [ -z "${!key+x}" ] || export -n "$key"; done
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
# saying why (never the value of a secret), so the caller asks again. xtrace is off while it holds the value.
mdm_prompt_ok() { local _mdm_x=$- _mdm_rc=0; set +x; _mdm_prompt_ok "$@" || _mdm_rc=1; case "$_mdm_x" in *x*) set -x ;; esac; return "$_mdm_rc"; }
_mdm_prompt_ok() {
  local r="_MDM_ANS_RULE_${1//-/_}"
  _MDM_V=${!2-}
  _mdm_answer_check "${!r}"
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
