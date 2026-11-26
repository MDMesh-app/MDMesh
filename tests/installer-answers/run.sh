#!/usr/bin/env bash
# The installers' answers contract: install/options.json against quickstart.sh, setup.sh and install/install-native.sh.
#
# Usage: tests/installer-answers/run.sh
#
# Fails when:
#   - an installer asks a question (a `read` from the terminal) without an "# answer: <id>" marker naming an option in
#     options.json, or with a prompt text the option doesn't list;
#   - an option (or one of its prompt texts) is missing from an installer it names, or its `mdm_answer` call there uses
#     another variable, alias or rule than the contract says;
#   - install/lib/answers.sh accepts other answer-file keys than the contract's variables;
#   - a dry run (MDMESH_DRY_RUN=1) with a full set of answers asks anything, fails, shows another value, or prints a
#     secret; the same answers from an answers file give another plan;
#   - a default differs (a dry run with only the required answers and MDMESH_NONINTERACTIVE=1 shows each default);
#   - a required answer can be left out under MDMESH_NONINTERACTIVE=1, or a question without an answer isn't asked;
#   - an example value in the contract is accepted or refused differently by the script, or by the contract's regex;
#   - install/lib/jdk.sh misjudges a JDK 17 version, or the native installer stops setting -XX:-UseBASE64Intrinsics.
#
# Every installer run here is a dry run, in a temporary copy (setup.sh and the native installer) or a temporary working
# directory (quickstart.sh), with an empty environment and stdin from /dev/null: nothing is installed, and a question
# the scripts would ask fails the run. Needs: bash, jq, minisign, grep, sed, awk.
set -euo pipefail
unset CDPATH
HERE=$(cd -P -- "$(dirname -- "$0")" && pwd)
REPO=$(cd -P -- "$HERE/../.." && pwd)
CONTRACT="$REPO/install/options.json"
command -v jq > /dev/null || { echo "jq is required" >&2; exit 2; }
command -v minisign > /dev/null || { echo "minisign is required (the resumed quick start's release fixture)" >&2; exit 2; }
# Not as root: the native installer refuses a non-root user before it changes anything, a second guard should a dry run
# ever stop working. (The commands that would change the host are stubbed out below as well.)
[ "$(id -u)" != 0 ] || { echo "Run this as an unprivileged user, not root." >&2; exit 2; }
export LC_ALL=C
umask 077   # answers files must be private to be read

WORK=$(mktemp -d)
cleanup() { rm -r -- "$WORK" 2>/dev/null || true; }
trap cleanup EXIT
mkdir -p "$WORK/src/install" "$WORK/qs" "$WORK/out"
# cp_setup DIR: setup.sh and what it sources from outside install/ (supervisor/mode-guard.sh, the updater's version rule)
cp_setup() { cp -- "$REPO/setup.sh" "$1/"; mkdir -p "$1/supervisor"; cp -- "$REPO/supervisor/mode-guard.sh" "$1/supervisor/"; }
cp_setup "$WORK/src"
cp -R -- "$REPO/install/lib" "$WORK/src/install/"
cp -- "$REPO/install/install-native.sh" "$WORK/src/install/"
# Stubs, first on PATH, for every command that could install or start something: called at all, they record it (their
# arguments and environment, in $STUB_LOG or else stubs.called) and fail.
mkdir -p "$WORK/stubs"
for c in docker curl wget apt-get systemctl useradd mvn git; do
  # shellcheck disable=SC2016  # $0, $* and $STUB_LOG belong to the stub
  printf '#!/bin/sh\nl=${STUB_LOG:-%s/stubs.called}\n{ echo "$0 $*"; env; } >> "$l"\nexit 99\n' "$WORK" > "$WORK/stubs/$c"
  chmod +x "$WORK/stubs/$c"
done
# A chmod stub (only on the PATH of the .env runs below) that records .env's mode when chmod is called, then runs it.
mkdir -p "$WORK/stubs-chmod"
printf '#!/bin/sh\n[ ! -e .env ] || stat -c %%a .env >> "%s/chmod.log"\nexec %s "$@"\n' "$WORK" "$(command -v chmod)" \
  > "$WORK/stubs-chmod/chmod"
chmod +x "$WORK/stubs-chmod/chmod"
# With STUB_DOCKER_OK=1, `docker compose version` succeeds (so setup.sh gets past its checks); anything else still fails.
# shellcheck disable=SC2016  # the stub's own variables
sed -i '2i [ "${STUB_DOCKER_OK:-}" = 1 ] \&\& [ "$1 $2" = "compose version" ] \&\& exit 0' "$WORK/stubs/docker"
# With STUB_DOCKER_OK=1, `docker ps` and `docker volume ls` (what install/lib/stack.sh asks) print STUB_DOCKER_PS (the
# working directories of the project's containers, one per line; "-": a container that names none) and
# STUB_DOCKER_VOLUMES (its volumes): none unset. Logged.
# shellcheck disable=SC2016  # the stub's own variables
sed -i '2i [ "${STUB_DOCKER_OK:-}" = 1 ] \&\& [ "$1" = ps ] \&\& { [ -z "${STUB_LOG:-}" ] || echo "$0 $*" >> "$STUB_LOG"; [ -z "${STUB_DOCKER_PS:-}" ] || printf "%s\\n" "$STUB_DOCKER_PS" | sed -e "s/^-\$//" -e "s/^/dir:/"; exit 0; }' "$WORK/stubs/docker"
# shellcheck disable=SC2016  # the stub's own variables
sed -i '2i [ "${STUB_DOCKER_OK:-}" = 1 ] \&\& [ "$1 $2" = "volume ls" ] \&\& { [ -z "${STUB_LOG:-}" ] || echo "$0 $*" >> "$STUB_LOG"; [ -z "${STUB_DOCKER_VOLUMES:-}" ] || printf "%s\\n" "$STUB_DOCKER_VOLUMES"; exit 0; }' "$WORK/stubs/docker"
# With STUB_DOCKER_OK=1, the quick start's architecture query fails quietly, so it reads uname (as without a daemon).
# shellcheck disable=SC2016  # the stub's own variables
sed -i '2i [ "${STUB_DOCKER_OK:-}" = 1 ] \&\& [ "$*" = "info --format {{.Architecture}}" ] \&\& exit 1' "$WORK/stubs/docker"
# With STUB_ARGV, every docker call's arguments, one per line and then "--", are added to that file.
# shellcheck disable=SC2016  # the stub's own variables
sed -i '2i [ -z "${STUB_ARGV:-}" ] || { printf "%s\\n" "$@" --; } >> "$STUB_ARGV"' "$WORK/stubs/docker"
# With STUB_DOCKER_INFO, `docker info` prints it (what install/lib/modes.sh reads to tell rootless Docker apart).
sed -i '2i [ -n "${STUB_DOCKER_INFO:-}" ] \&\& [ "$1" = info ] \&\& { echo "$STUB_DOCKER_INFO"; exit 0; }' "$WORK/stubs/docker"

FAILS=0; CHECKS=0
fail() { FAILS=$((FAILS + 1)); printf 'FAIL: %s\n' "$*" >&2; }
ok() { CHECKS=$((CHECKS + 1)); }

jq -e '.options | length > 0' "$CONTRACT" > /dev/null || { echo "install/options.json: no options" >&2; exit 1; }
installer_path() { jq -r --arg i "$1" '.installers[] | select(.id == $i) | .path' "$CONTRACT"; }
installer_ids() { jq -r '.installers[].id' "$CONTRACT"; }
# options_of INSTALLER: the ids of the options that installer asks.
options_of() { jq -r --arg i "$1" '.options[] | select(.installers | index($i)) | .id' "$CONTRACT"; }
opt() { jq -r --arg o "$1" ".options[] | select(.id == \$o) | $2" "$CONTRACT"; }

# ---------------------------------------------------------------------------------------------------------------------
# Static: questions <-> contract.
# prompt_reads FILE: "LINENO:LINE" of each read from the terminal: a `read -r…` that is not a comment, not in a while
# loop, and not reading an array, a NUL-delimited stream or a here-string.
prompt_reads() {
  grep -nE '(^|[^[:alnum:]_])read[[:space:]]+-r' "$1" \
    | grep -vE '^[0-9]+:[[:space:]]*#' | grep -vE '<<<|while|read[[:space:]]+-r[[:space:]]+-a|-d[[:space:]]' || true
}

for inst in $(installer_ids); do
  f="$REPO/$(installer_path "$inst")"
  [ -f "$f" ] || { fail "$inst: $f is missing"; continue; }
  # An installer that sources install/lib/modes.sh asks its questions there too (quickstart.sh holds a copy of it):
  # checked as one text, the installer's then the library's.
  if grep -qE '^\. \./install/lib/modes\.sh$' "$f"; then
    cat "$f" "$REPO/install/lib/modes.sh" > "$WORK/with-modes.$inst"; f="$WORK/with-modes.$inst"
  fi
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    n=${hit%%:*}; line=${hit#*:}
    id=$(printf '%s\n' "$line" | sed -nE 's/.*# answer: ([a-z0-9-]+).*/\1/p')
    if [ -z "$id" ]; then fail "$inst:$n asks a question with no '# answer: <id>' marker (and no options.json entry): $line"; continue; fi
    if ! options_of "$inst" | grep -qx -- "$id"; then fail "$inst:$n: the answer \"$id\" has no options.json entry for $inst"; continue; fi
    text=$(printf '%s\n' "$line" | sed -nE 's/.*read -rp "([^"]*)".*/\1/p')
    if [ -n "$text" ]; then
      opt "$id" '.prompts[].script' | grep -qxF -- "$text" \
        || fail "$inst:$n: prompt text \"$text\" is not among options.json's prompts for \"$id\""
    else
      ctx=$(sed -n "$((n > 4 ? n - 4 : 1)),${n}p" "$f")
      found=0
      while IFS= read -r s; do case "$ctx" in *"$s"*) found=1 ;; esac; done < <(opt "$id" '.prompts[].script')
      [ "$found" = 1 ] || fail "$inst:$n: none of options.json's prompts for \"$id\" is printed just before this read"
    fi
    ok
  done < <(prompt_reads "$f")

  for id in $(options_of "$inst"); do
    grep -qE "# answer: ${id}([^a-z0-9-]|\$)" "$f" || fail "$inst: options.json has \"$id\", but no question in $inst is marked '# answer: $id'"
    while IFS= read -r s; do
      grep -qF -- "$s" "$f" || fail "$inst: options.json's prompt text for \"$id\" is not in $inst: \"$s\""
    done < <(opt "$id" '.prompts[].script')
    grep -qE "(mdm_answered|mdm_ask) ${id}([^a-z0-9-]|\$)" "$f" \
      || fail "$inst: no mdm_answered/mdm_ask $id before its question (so MDMESH_NONINTERACTIVE=1 would not stop it)"
    # The default the question shows ("[${NAME}]") is one constant, assigned once, to the contract's default (or, for a
    # default that depends on the install, defaultNote, written as ${…:-default}).
    def=$(opt "$id" '.default // ""')
    if [ -n "$def" ]; then
      for name in $(opt "$id" '.prompts[].script' | sed -nE 's/.*\[\$\{([A-Za-z_][A-Za-z0-9_]*)\}\].*/\1/p'); do
        if [ "$(opt "$id" '.defaultNote // ""')" != "" ]; then
          grep -qF -- ":-$def}" "$f" || fail "$inst: the default $def of \"$id\" (\${…:-$def}) is not in $inst"
        else
          assigns=$(grep -E "(^|[^A-Za-z0-9_])$name=" "$f" || true)
          [ "$assigns" = "$name=\"$def\"" ] || fail "$inst: \"$id\" shows [\${$name}], which must be assigned once, as $name=\"$def\" (found: $assigns)"
        fi
      done
    fi
    calls=$(grep -E "^[[:space:]]*mdm_answer ${id} " "$f" || true)
    if [ "$(printf '%s' "$calls" | grep -c .)" != 1 ]; then fail "$inst: expected one 'mdm_answer $id …' call"; continue; fi
    read -r _ _ _var rule env alias _rest <<< "$calls"
    [ "$rule" = "$(opt "$id" .rule)" ] || fail "$inst: mdm_answer $id uses rule $rule, options.json says $(opt "$id" .rule)"
    [ "$env" = "$(opt "$id" .env)" ] || fail "$inst: mdm_answer $id reads $env, options.json says $(opt "$id" .env)"
    [ "${alias:-}" = "$(opt "$id" '.aliases[0] // ""')" ] || fail "$inst: mdm_answer $id alias ${alias:-(none)} differs from options.json"
    ok
  done
  # Each mdm_answer call is in the contract.
  while read -r _ id _; do
    options_of "$inst" | grep -qx -- "$id" || fail "$inst: mdm_answer $id has no options.json entry for $inst"
  done < <(grep -E '^[[:space:]]*mdm_answer [a-z]' "$f" || true)
  for env in $(jq -r --arg i "$inst" '.settings[] | select(.installers | index($i)) | .env' "$CONTRACT"); do
    grep -qF -- "$env" "$f" || grep -qF -- "$env" "$REPO/install/lib/answers.sh" || fail "$inst: setting $env is not read"
  done
done

# Each installer's flags in options.json are exactly the ones its argument parser accepts. The one parser form this
# understands is a top-level `for VAR in "$@"; do … done` loop, read for its case patterns (-x|--xx), "--quoted",
# --opt=*) …) and its [ "$VAR" = --x ] tests; an installer without one takes no flags. Any other parser (getopts, a
# while/shift loop over $#, a case on $1 or "$@" at the top level, a second loop) fails the test, so that a new form
# can't hide its flags. setup.sh's "forwards" are the flags it hands to the native installer with --native.
# parsed_flags FILE: the flags FILE's parser accepts, one per line, or UNRECOGNISED.
parsed_flags() {
  local loops var
  loops=$(grep -cE '^for [A-Za-z_][A-Za-z0-9_]* in "\$@"; do$' "$1" || true)
  if [ "$loops" -gt 1 ] || grep -nE 'getopts|^[^#[:space:]][^#]*\$#|^(while|until|case)[^#]*"?\$[1@*]|^for [A-Za-z_][A-Za-z0-9_]*; do' "$1" \
       | grep -vE '^[0-9]+:[[:space:]]*#' | grep -q .; then
    echo UNRECOGNISED; return
  fi
  [ "$loops" = 1 ] || return 0
  var=$(sed -nE 's/^for ([A-Za-z_][A-Za-z0-9_]*) in "\$@"; do$/\1/p' "$1")
  awk '/^for [A-Za-z_][A-Za-z0-9_]* in "\$@"; do$/ { f = 1; next } f && /^done/ { f = 0 } f' "$1" > "$WORK/parser.txt"
  {
    # case patterns: up to the first ")", each alternative that starts with - (quotes removed)
    sed -nE 's/^[[:space:]]*\(?(["'"'"']?-[^)]*)\).*/\1/p' "$WORK/parser.txt" | tr '|' '\n' | tr -d "\"' "
    # [ "$VAR" = --x ] tests
    { grep -oE "\"\\\$$var\" = [\"']?-[^] \"']*" "$WORK/parser.txt" || true; } | sed -E "s/.* = [\"']?//"
  } | { grep -E '^-' || true; } | sort -u
}
for inst in $(installer_ids); do
  f="$REPO/$(installer_path "$inst")"
  got=$(parsed_flags "$f")
  [ "$got" != UNRECOGNISED ] || { fail "$inst: its argument parser is not one this test can read (see parsed_flags)"; continue; }
  want=$(jq -r --arg i "$inst" '.installers[] | select(.id == $i) | .flags[]' "$CONTRACT" | sort -u)
  [ "$got" = "$want" ] || fail "$inst accepts the flags [$(tr "\n" " " <<< "$got")], options.json lists [$(tr "\n" " " <<< "$want")]"
  to=$(jq -r --arg i "$inst" '.installers[] | select(.id == $i) | .forwards.to // ""' "$CONTRACT")
  # An installer that hands its other arguments to another one (exec ./install/install-native.sh "${ARGS[@]}") says so.
  if grep -qE 'exec \./install/install-native\.sh "\$\{[A-Za-z_]+\[@\]\}"' "$f"; then
    [ "$to" = install-native.sh ] || fail "$inst passes flags on to install-native.sh, but options.json has no forwards for it"
  fi
  if [ -n "$to" ]; then
    fw=$(jq -r --arg i "$inst" '.installers[] | select(.id == $i) | .forwards.flags[]' "$CONTRACT" | sort -u)
    tf=$(jq -r --arg i "$to" '.installers[] | select(.id == $i) | .flags[]' "$CONTRACT" | sort -u)
    [ "$fw" = "$tf" ] || fail "$inst forwards [$(tr "\n" " " <<< "$fw")] to $to, which takes [$(tr "\n" " " <<< "$tf")]"
  fi
  ok
done
# The extraction itself reads every form it claims to, and refuses the ones it doesn't know.
# shellcheck disable=SC2016  # the parsers below are text, not code to expand
printf '%s\n' '#!/bin/bash' 'for a in "$@"; do' '  [ "$a" = --first ] || x=1' '  case "$a" in' '    -a|--all) ;;' \
  '    "--quoted") ;;' "    '-s'|--single) ;;" '    --opt=*) ;;' '    --v2) ;;' '    *) exit 2 ;;' '  esac' 'done' > "$WORK/p1.sh"
[ "$(parsed_flags "$WORK/p1.sh" | tr '\n' ' ')" = "--all --first --opt=* --quoted --single --v2 -a -s " ] \
  || fail "parsed_flags misreads a parser: $(parsed_flags "$WORK/p1.sh" | tr '\n' ' ')"
# shellcheck disable=SC2016  # as above
for form in 'while getopts yv o; do :; done' 'while [ $# -gt 0 ]; do shift; done' 'case "$1" in -y) ;; esac' \
            'for a; do :; done'; do
  printf '#!/bin/bash\n%s\n' "$form" > "$WORK/p2.sh"
  [ "$(parsed_flags "$WORK/p2.sh")" = UNRECOGNISED ] || fail "parsed_flags does not refuse: $form"
done
ok

lib_keys=$(sed -nE 's/^_MDM_ANSWER_KEYS="(.*)"$/\1/p' "$REPO/install/lib/answers.sh" | tr ' ' '\n' | grep . | sort)
contract_keys=$(jq -r '(.options[].env), (.settings[].env)' "$CONTRACT" | sort)
[ "$lib_keys" = "$contract_keys" ] || fail "install/lib/answers.sh _MDM_ANSWER_KEYS differs from options.json's variables: $(diff <(echo "$lib_keys") <(echo "$contract_keys") | grep '^[<>]' | tr '\n' ' ')"
ok

# Regexes and allowed values: every valid example matches, every invalid one doesn't (empty ones: emptyMeans).
for id in $(jq -r '.options[].id' "$CONTRACT"); do
  re=$(opt "$id" '.regex // ""')
  [ -n "$re" ] || { fail "$id has no regex"; continue; }
  while IFS= read -r v; do
    [ -n "$v" ] || continue
    printf '%s\n' "$v" | grep -Eqx -- "$re" || fail "$id: valid example \"$v\" does not match the contract's regex"
  done < <(opt "$id" '.examples.valid[]')
  while IFS= read -r v; do
    [ -n "$v" ] || continue
    if printf '%s\n' "$v" | grep -Eqx -- "$re"; then fail "$id: invalid example \"$v\" matches the contract's regex"; fi
  done < <(opt "$id" '.examples.invalid[]')
  while IFS= read -r v; do
    opt "$id" '.examples.valid[]' | grep -qxF -- "$v" || fail "$id: allowed value \"$v\" is not a valid example"
  done < <(opt "$id" '(.allowed // [])[], ((.synonyms // {}) | keys[])')
  ok
done

# ---------------------------------------------------------------------------------------------------------------------
# Dynamic: dry runs.
# dry INSTALLER [NAME=VALUE...]: runs INSTALLER with MDMESH_DRY_RUN=1, only the given variables in its environment and
# stdin from /dev/null. Sets RC; stdout in $WORK/out/o, stderr in $WORK/out/e.
dry() {
  local inst=$1 script; shift
  case "$inst" in
    quickstart.sh) script="$REPO/quickstart.sh" ;;
    setup.sh) script="$WORK/src/setup.sh" ;;
    install-native.sh) script="$WORK/src/install/install-native.sh" ;;
  esac
  RC=0
  (cd "$WORK/qs" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" MDMESH_DRY_RUN=1 "$@" bash ${XTRACE:+-x} "$script" \
    ${DRY_ARGS[@]+"${DRY_ARGS[@]}"} < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || RC=$?
}
DRY_ARGS=()   # command-line arguments for dry's runs (the mode flags)
show() { sed 's/^/    | /' "$WORK/out/o" "$WORK/out/e" >&2; }
# plan_field ID: "<value> <source>" from the plan line of ID.
plan_field() { awk -v id="$1" '$1 == id { print $2, $NF; exit }' "$WORK/out/o"; }
modes_of() { if options_of "$1" | grep -qx mode; then opt mode '.allowed[]'; else echo -; fi; }
# opt_m ID MODE: y when option ID is asked in MODE ("-": an installer without modes), else n.
opt_m() { jq -r --arg o "$1" --arg m "$2" '.options[] | select(.id == $o) | if .when == null or $m == "-" then "y" elif .when.mode == $m then "y" else "n" end' "$CONTRACT"; }
# answers INSTALLER MODE [required]: NAME=VALUE for each answer (the first valid example; mode = MODE), all or only the
# required ones.
answers() {
  local id req
  for id in $(options_of "$1"); do
    [ "$(opt_m "$id" "$2")" = y ] || continue
    req=$(opt "$id" .required)
    [ "${3:-}" != required ] || [ "$req" = true ] || [ "$req" = fresh-install ] || continue
    if [ "$id" = mode ]; then echo "MDMESH_MODE=$2"; else echo "$(opt "$id" .env)=$(opt "$id" '.examples.valid[0]')"; fi
  done
}
secrets_of() { for id in $(options_of "$1"); do [ "$(opt "$id" .secret)" = true ] && opt "$id" '.examples.valid[]'; done; true; }

for inst in $(installer_ids); do
  for mode in $(modes_of "$inst"); do
    tag="$inst${mode#-}"; [ "$mode" = - ] || tag="$inst ($mode)"
    mapfile -t full < <(answers "$inst" "$mode")
    mapfile -t req < <(answers "$inst" "$mode" required)

    # A full set of answers, non-interactive: nothing asked, every answer shown as given, no secret printed.
    dry "$inst" MDMESH_NONINTERACTIVE=1 "${full[@]}"
    if [ "$RC" != 0 ] || grep -q '\[ask\]' "$WORK/out/o"; then fail "$tag: a dry run with every answer failed or asks something"; show; fi
    for a in "${full[@]}"; do
      env=${a%%=*}; val=${a#*=}; id=$(jq -r --arg e "$env" '.options[] | select(.env == $e) | .id' "$CONTRACT")
      want=$val; [ "$(opt "$id" .secret)" != true ] || want='(hidden)'
      [ "$(plan_field "$id")" = "$want [$env]" ] || { fail "$tag: plan shows \"$(plan_field "$id")\" for $id, expected \"$want [$env]\""; show; }
    done
    for s in $(secrets_of "$inst"); do
      if grep -qF -- "$s" "$WORK/out/o" "$WORK/out/e"; then fail "$tag: a secret was printed"; fi
    done
    [ -z "$(ls -A "$WORK/qs")" ] || fail "$tag: the dry run created files in its working directory"
    cp "$WORK/out/o" "$WORK/out/full.plan"
    ok
    # Interactive (no MDMESH_NONINTERACTIVE), every answer given: nothing asked either (stdin is /dev/null).
    dry "$inst" "${full[@]}"
    { [ "$RC" = 0 ] && ! grep -q '\[ask\]' "$WORK/out/o"; } || { fail "$tag: a dry run with every answer (interactive) asks something"; show; }
    ok
    # The same, traced (bash -x): no secret in the trace either.
    XTRACE=1 dry "$inst" MDMESH_NONINTERACTIVE=1 "${full[@]}"
    [ "$RC" = 0 ] || { fail "$tag: a traced dry run failed"; show; }
    for s in $(secrets_of "$inst"); do
      if grep -qF -- "$s" "$WORK/out/o" "$WORK/out/e"; then fail "$tag: a secret was printed by xtrace (bash -x)"; fi
    done
    ok
    # The same answers from an answers file (private: it holds the secret, if any): the same plan, traced or not.
    printf '# answers\n' > "$WORK/answers.env"; printf '%s\n' "${full[@]}" >> "$WORK/answers.env"; chmod 600 "$WORK/answers.env"
    dry "$inst" MDMESH_NONINTERACTIVE=1 MDMESH_ANSWERS="$WORK/answers.env"
    { [ "$RC" = 0 ] && cmp -s "$WORK/out/o" "$WORK/out/full.plan"; } || { fail "$tag: answers from MDMESH_ANSWERS give another plan"; show; }
    XTRACE=1 dry "$inst" MDMESH_NONINTERACTIVE=1 MDMESH_ANSWERS="$WORK/answers.env"
    for s in $(secrets_of "$inst"); do
      if grep -qF -- "$s" "$WORK/out/o" "$WORK/out/e"; then fail "$tag: a secret in MDMESH_ANSWERS was printed by xtrace"; fi
    done
    # Readable by others: refused before it is read, saying how to fix it (any answers file: it chooses what is
    # installed, and may hold the token).
    chmod 644 "$WORK/answers.env"
    dry "$inst" MDMESH_NONINTERACTIVE=1 MDMESH_ANSWERS="$WORK/answers.env"
    { [ "$RC" != 0 ] && grep -qF 'chmod 600' "$WORK/out/e"; } || { fail "$tag: an answers file readable by others is not refused"; show; }
    for s in $(secrets_of "$inst"); do
      if grep -qF -- "$s" "$WORK/out/o" "$WORK/out/e"; then fail "$tag: refusing the answers file printed the secret"; fi
    done
    ok

    # Only the required answers, non-interactive: every other question takes its default, as the contract says.
    dry "$inst" MDMESH_NONINTERACTIVE=1 "${req[@]}"
    [ "$RC" = 0 ] || { fail "$tag: a dry run with only the required answers failed"; show; }
    for id in $(options_of "$inst"); do
      [ "$(opt_m "$id" "$mode")" = y ] || continue
      [ "$(opt "$id" .required)" = false ] || continue
      def=$(opt "$id" '.default'); [ -n "$def" ] || def='""'
      [ "$(plan_field "$id")" = "$def [default]" ] || { fail "$tag: default of $id is \"$(plan_field "$id")\", options.json says \"$def\""; show; }
    done
    ok
    # Leaving out a required answer stops a non-interactive run, naming its variable.
    for a in "${req[@]}"; do
      env=${a%%=*}; id=$(jq -r --arg e "$env" '.options[] | select(.env == $e) | .id' "$CONTRACT")
      [ "$(opt "$id" .required)" = true ] || continue
      rest=(); for b in "${req[@]}"; do [ "$b" = "$a" ] || rest+=("$b"); done
      dry "$inst" MDMESH_NONINTERACTIVE=1 "${rest[@]}"
      { [ "$RC" != 0 ] && grep -qF -- "$env" "$WORK/out/e"; } || { fail "$tag: without $env a non-interactive run does not stop naming it"; show; }
      ok
    done

    # Examples (interactive, the other answers given): each valid one is accepted, each invalid one refused, naming the
    # variable and never echoing a secret.
    for id in $(options_of "$inst"); do
      [ "$(opt_m "$id" "$mode")" = y ] || continue
      env=$(opt "$id" .env)
      base=(); for b in "${full[@]}"; do [ "${b%%=*}" = "$env" ] || base+=("$b"); done
      while IFS= read -r v; do
        [ "$id" != mode ] || [ "$v" = "$mode" ] || [ "$(jq -r --arg v "$v" '.options[] | select(.id == "mode") | .synonyms[$v] // ""' "$CONTRACT")" = "$mode" ] || continue
        dry "$inst" "${base[@]}" "$env=$v"
        [ "$RC" = 0 ] || { fail "$tag: $env=\"$v\" (a valid example) is refused"; show; }
        ok
      done < <(opt "$id" '.examples.valid[]')
      while IFS= read -r v; do
        dry "$inst" "${base[@]}" "$env=$v"
        { [ "$RC" != 0 ] && grep -qF -- "$env" "$WORK/out/e"; } || { fail "$tag: $env=\"$v\" (an invalid example) is not refused naming $env"; show; }
        if [ "$(opt "$id" .secret)" = true ] && [ "${#v}" -gt 2 ] && grep -qF -- "$v" "$WORK/out/o" "$WORK/out/e"; then fail "$tag: the invalid secret was printed"; fi
        ok
      done < <(opt "$id" '.examples.invalid[]')
    done

    # No answers, interactive: every question would be asked.
    dry "$inst"
    [ "$RC" = 0 ] || { fail "$tag: a dry run without answers failed"; show; }
    for id in $(options_of "$inst"); do
      [ "$(plan_field "$id")" = "? [ask]" ] || { fail "$tag: without answers, $id is not asked: \"$(plan_field "$id")\""; show; }
    done
    ok
  done
done

# Secrets in a real (not dry) run: neither the arguments nor the environment of any child process holds the token. The
# runs stop at the first stub (docker), before anything else; the stubs log their arguments and environment.
token=$(opt tunnel-token '.examples.valid[0]')
for inst in quickstart.sh setup.sh; do
  : > "$WORK/children.log"
  dry "$inst" MDMESH_DRY_RUN=0 STUB_LOG="$WORK/children.log" MDMESH_NONINTERACTIVE=1 MDMESH_MODE=tunnel \
    MDMESH_HOST=mdm.example.com MDMESH_TUNNEL_TOKEN="$token"
  [ -s "$WORK/children.log" ] || { fail "$inst: the real run called no stub (the child-process check did not run)"; show; }
  if grep -qF -- "$token" "$WORK/children.log" "$WORK/out/o" "$WORK/out/e"; then
    fail "$inst: the tunnel token reached a child process's arguments or environment, or the output"
  fi
  ok
done
# A line holding a bare token in the answers file is refused without echoing it.
printf '%s\n' "$token" > "$WORK/answers.env"; chmod 600 "$WORK/answers.env"
dry quickstart.sh MDMESH_ANSWERS="$WORK/answers.env"
{ [ "$RC" != 0 ] && ! grep -qF -- "${token%=}" "$WORK/out/o" "$WORK/out/e"; } || { fail "a bare token line in MDMESH_ANSWERS is echoed (or accepted)"; show; }
ok

# A setup.sh re-run reads .env with a parser, never by sourcing it: traced (bash -x), the run shows no .env secret.
mkdir -p "$WORK/src2/install"
cp_setup "$WORK/src2"; cp -R -- "$REPO/install/lib" "$WORK/src2/install/"
dbpw=c0ffee0dbpassword0c0ffee; hs=c0ffee0hashsecret0c0ffee; tt=c0ffee0tunneltoken0c0ffee
printf '%s\n' "DB_PASSWORD=$dbpw" "HASH_SECRET=\"$hs\"" "TUNNEL_TOKEN=$tt # set by hand" BASE_URL=https://mdm.example.com \
  COMPOSE_PROFILES=cloudflare IMAGE_OWNER=local > "$WORK/src2/.env"
chmod 644 "$WORK/src2/.env"   # left readable by an older version or a hand edit: the re-run makes it owner-only
: > "$WORK/children.log"
(cd "$WORK/src2" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" STUB_LOG="$WORK/children.log" STUB_DOCKER_OK=1 \
  bash -x ./setup.sh < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
grep -q 'Existing .env found' "$WORK/out/o" || { fail "the traced setup.sh re-run did not reach the .env"; show; }
grep -q '^\S*docker compose [^v]' "$WORK/children.log" || { fail "the traced setup.sh re-run stopped before using the .env"; show; }
for v in "$dbpw" "$hs" "$tt"; do
  if grep -qF -- "$v" "$WORK/out/o" "$WORK/out/e"; then fail "a setup.sh re-run traced with bash -x printed a .env secret"; fi
done
[ "$(stat -c %a "$WORK/src2/.env")" = 600 ] || fail "a setup.sh re-run leaves .env readable by others ($(stat -c %a "$WORK/src2/.env"))"
ok

# install/lib/dotenv.sh (setup.sh's .env reader): every ACCEPT line of dotenv-cases.sh gives exactly the environment
# `set -a; . FILE` gives; every REFUSE line stops it, naming the line number and never the value.
# shellcheck source=tests/installer-answers/dotenv-cases.sh
. "$HERE/dotenv-cases.sh"
envof() { env -i HOME=/nonexistent bash -c "$1; env" _ "$2" 2>/dev/null | { grep -v '^_=' || true; } | sort; }
for line in "${ACCEPT[@]}"; do
  printf '%s\n' "$line" > "$WORK/case.env"
  # shellcheck disable=SC2016  # $1 is the inner bash's
  want=$(envof 'set -a; . "$1"' "$WORK/case.env")
  got=$(envof ". '$REPO/install/lib/dotenv.sh'; mdm_load_env \"\$1\"" "$WORK/case.env")
  [ "$got" = "$want" ] || fail "dotenv: $(printf '%q' "$line") reads as $(printf '%q' "$(diff <(echo "$want") <(echo "$got") | grep '^>' | tr '\n' ' ')"), bash reads $(printf '%q' "$(diff <(echo "$want") <(echo "$got") | grep '^<' | tr '\n' ' ')")"
  ok
done
for line in "${REFUSE[@]}"; do
  printf 'A=1\n%s\n' "$line" > "$WORK/case.env"
  RC=0; out=$(env -i bash -c ". '$REPO/install/lib/dotenv.sh'; mdm_load_env \"\$1\"" _ "$WORK/case.env" 2>&1) || RC=$?
  { [ "$RC" != 0 ] && printf '%s' "$out" | grep -q 'line 2' && ! printf '%s' "$out" | grep -q SECRETVALUE; } \
    || fail "dotenv: $(printf '%q' "$line") is not refused naming line 2 only (rc $RC): $out"
  ok
done

# install/lib/jdk.sh: which JDK 17 builds have the Base64 decoder bug of CVE-2026-114938 (OpenJDK), 17.0.6 to 17.0.21,
# as javac -version, java.version or a Temurin image's JAVA_VERSION spell them. And the native installer runs the
# server with -XX:-UseBASE64Intrinsics (in the CATALINA_OPTS its unit gets; T2 checks the running JVM).
for c in "17.0.6 affected" "17.0.13 affected" "17.0.21 affected" "jdk-17.0.21+9 affected" "17.0.21.1 unaffected" \
         "jdk-17.0.21.1+2 unaffected" "17.0.21.1_2 unaffected" "17.0.22 unaffected" "17.0.5 unaffected" "17.0.4.1 unaffected" "17 other" \
         "21.0.9 other" "11.0.29 other" "'' other" "17.0.x other"; do
  read -r v want <<< "$c"; [ "$v" != "''" ] || v=""
  got=$(bash -c '. "$1"; if mdm_jdk17_base64_affected "$2"; then echo affected; elif mdm_jdk17_base64_unaffected "$2"; then echo unaffected; else echo other; fi' \
        _ "$REPO/install/lib/jdk.sh" "$v")
  [ "$got" = "$want" ] || fail "jdk.sh: JDK '$v' is $got, want $want"
  ok
done
grep -qE '^export CATALINA_OPTS="(.* )?-XX:-UseBASE64Intrinsics( |")' "$REPO/install/install-native.sh" \
  || fail "install-native.sh no longer sets -XX:-UseBASE64Intrinsics in CATALINA_OPTS"
# shellcheck disable=SC2016  # the installer's own text
grep -qF 'Environment="CATALINA_OPTS=${CATALINA_OPTS}"' "$REPO/install/install-native.sh" \
  || fail "install-native.sh's server unit no longer gets CATALINA_OPTS"
# Tomcat's bin/setenv.sh (the Docker image's and a native install's) appends the flag to whatever CATALINA_OPTS holds.
for pre in "" "-Dx=1 -Xmx1g"; do
  # shellcheck disable=SC2016  # $1 and $CATALINA_OPTS are the inner sh's
  got=$(env -i CATALINA_OPTS="$pre" sh -c '. "$1"; printf %s "$CATALINA_OPTS"' _ "$REPO/install/tomcat-setenv.sh")
  [ "$got" = "${pre:+$pre }-XX:-UseBASE64Intrinsics" ] || fail "install/tomcat-setenv.sh turns CATALINA_OPTS '$pre' into '$got'"
done
# A native install writes it where there is none, rewrites its own (the marker line) and leaves an operator's alone: a
# file without the marker, a link (even to ours) or a directory.
mkdir -p "$WORK/setenv"
sstate() { bash -c '. "$1"; mdm_setenv_state "$2"' _ "$REPO/install/lib/jdk.sh" "$1"; }
grep -qxF -- "$(bash -c '. "$1"; printf %s "$MDM_SETENV_MARKER"' _ "$REPO/install/lib/jdk.sh")" "$REPO/install/tomcat-setenv.sh" \
  || fail "install/tomcat-setenv.sh lacks install/lib/jdk.sh's MDM_SETENV_MARKER line"
[ "$(sstate "$WORK/setenv/none.sh")" = absent ] || fail "setenv.sh: a missing file is not 'absent'"
cp -- "$REPO/install/tomcat-setenv.sh" "$WORK/setenv/ours.sh"
[ "$(sstate "$WORK/setenv/ours.sh")" = ours ] || fail "setenv.sh: ours is not 'ours'"
# shellcheck disable=SC2016  # the operator's file's own text
printf 'CATALINA_OPTS="$CATALINA_OPTS -Xmx2g"\n' >"$WORK/setenv/theirs.sh"
[ "$(sstate "$WORK/setenv/theirs.sh")" = theirs ] || fail "setenv.sh: an operator's own is not 'theirs'"
ln -s ours.sh "$WORK/setenv/link.sh"; mkdir "$WORK/setenv/dir.sh"
[ "$(sstate "$WORK/setenv/link.sh")$(sstate "$WORK/setenv/dir.sh")" = theirstheirs ] || fail "setenv.sh: a link or a directory is not 'theirs'"
# shellcheck disable=SC2016  # the installer's own text
{ grep -qF 'case "$(mdm_setenv_state "$CATALINA/bin/setenv.sh" svc_cat)" in' "$REPO/install/install-native.sh" \
  && grep -qxF '  absent|ours) tc_write bin/setenv.sh cat "$REPO/install/tomcat-setenv.sh" ;;' "$REPO/install/install-native.sh"; } \
  || fail "install-native.sh no longer writes Tomcat's bin/setenv.sh only when it is absent or ours"
grep -qxF 'COPY install/tomcat-setenv.sh /usr/local/tomcat/bin/setenv.sh' "$REPO/docker/server.Dockerfile" \
  || fail "docker/server.Dockerfile no longer installs Tomcat's bin/setenv.sh"
ok

# Answers typed at a question get the same rules as pre-set ones: a wrong one is refused (never echoing a token) and
# asked again. A real setup.sh run in a copy, answering on stdin; it stops at the docker stub after writing .env.
for c in "1|mdm.example.com|bad SECRET tok|eyJhIjoiYWJjIn0=|^TUNNEL_TOKEN=eyJhIjoiYWJjIn0=$|is not a Cloudflare Tunnel token" \
         "2|mdm.example.com|ops @SECRET|ops@example.com|^ACME_EMAIL=ops@example.com$|is not an email address"; do
  IFS='|' read -r m h bad good want why <<< "$c"
  mkdir -p "$WORK/src3/install"; cp_setup "$WORK/src3"; cp -R -- "$REPO/install/lib" "$WORK/src3/install/"
  [ ! -e "$WORK/src3/.env" ] || mv -- "$WORK/src3/.env" "$WORK/src3/.env.prev"
  : > "$WORK/chmod.log"
  # umask 022, a common default: the new .env must be owner-only from the start anyway.
  (cd "$WORK/src3" && umask 022 && printf '%s\n' "$m" "$h" "$bad" "$good" | env -i PATH="$WORK/stubs-chmod:$WORK/stubs:$PATH" \
    HOME="$WORK" STUB_LOG="$WORK/children.log" STUB_DOCKER_OK=1 bash ./setup.sh > "$WORK/out/o" 2> "$WORK/out/e") || true
  { [ "$(stat -c %a "$WORK/src3/.env")" = 600 ] && ! grep -qv '^600$' "$WORK/chmod.log" \
    && [ -z "$(find "$WORK/src3" -maxdepth 1 -name '.env.new.*')" ]; } \
    || fail "setup.sh's new .env is not owner-only from the start (mode $(stat -c %a "$WORK/src3/.env"); at chmod: $(tr '\n' ' ' < "$WORK/chmod.log"))"
  { grep -qF -- "$why" "$WORK/out/e" && grep -qE -- "$want" "$WORK/src3/.env" && ! grep -q SECRET "$WORK/out/o" "$WORK/out/e"; } \
    || { fail "a wrong typed answer ($why) is not refused and asked again"; show; }
  ok
done
# The native installer stops on an option it doesn't take (setup.sh's --update, say) instead of installing.
RC=0; env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" MDMESH_DRY_RUN=1 bash "$WORK/src/install/install-native.sh" --update \
  < /dev/null > "$WORK/out/o" 2> "$WORK/out/e" || RC=$?
{ [ "$RC" = 2 ] && grep -q 'Unknown option: --update' "$WORK/out/e"; } || { fail "install-native.sh accepts an unknown option"; show; }
ok
# A custom release key shows in the plan.
dry quickstart.sh MDMESH_RELEASE_PUBKEY=RWQexample
[ "$(plan_field release-pubkey)" = "(custom) [MDMESH_RELEASE_PUBKEY]" ] || { fail "the plan does not show MDMESH_RELEASE_PUBKEY"; show; }
ok

# A PATH line earlier in .env does not switch off the read-only check (it uses no external command).
printf 'PATH=/nonexistent\nUID=1\n' > "$WORK/case.env"
RC=0; out=$(env -i bash -c ". '$REPO/install/lib/dotenv.sh'; mdm_load_env \"\$1\"" _ "$WORK/case.env" 2>&1) || RC=$?
{ [ "$RC" != 0 ] && printf '%s' "$out" | grep -q 'line 2: UID is a read-only shell variable'; } || fail "dotenv: the read-only check depends on PATH: $out"
ok
# quickstart.sh's repo and image-owner questions refuse a wrong answer and ask again (a real run on stdin; it stops when
# the release lookup reaches the curl stub, naming the repo it accepted).
mkdir -p "$WORK/qs2"
(cd "$WORK/qs2" && printf '%s\n' 1 'bad repo!' me/fork Bad myowner | env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" \
  STUB_LOG="$WORK/children.log" STUB_DOCKER_OK=1 bash "$REPO/quickstart.sh" > "$WORK/out/o" 2> "$WORK/out/e") || true
{ grep -qF 'That answer must be owner/name' "$WORK/out/e" && grep -qF 'That answer must be a GHCR owner in lowercase' "$WORK/out/e" \
  && grep -qF 'Could not resolve the latest release of me/fork' "$WORK/out/e"; } \
  || { fail "quickstart.sh does not refuse a wrong repo or image owner and ask again"; show; }
ok
# A release whose signed manifest has no image digests or file hashes (before 0.5.0) is sent to its own tag's
# quickstart.sh before anything is downloaded.
mkdir -p "$WORK/qs3"; : > "$WORK/children3.log"
(cd "$WORK/qs3" && printf '%s\n' 1 '' '' | env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" MDMESH_VERSION=0.4.0 \
  STUB_LOG="$WORK/children3.log" STUB_DOCKER_OK=1 bash "$REPO/quickstart.sh" > "$WORK/out/o" 2> "$WORK/out/e") || true
{ grep -qF "Releases before 0.5.0 can't be installed with this quick start" "$WORK/out/e" \
  && grep -qF '/refs/tags/v0.4.0/quickstart.sh' "$WORK/out/e" && ! grep -q 'curl' "$WORK/children3.log"; } \
  || { fail "quickstart.sh does not send MDMESH_VERSION=0.4.0 to that release's own quickstart.sh"; show; }
ok
# The images are linux/amd64, and linux/arm64 from the first release with Arm64 images: another architecture stops
# before any question, and x86_64 and Arm64 go on to verify the release. (Whether that release has images for the host
# is read from its signed manifest: scripts/release-manifest-check.sh. The docker stub fails `docker info`, so the
# host's architecture comes from uname, stubbed here.)
mkdir -p "$WORK/stubs-arch"
# shellcheck disable=SC2016  # the stub's own variables
printf '#!/bin/sh\n[ "$1" = -m ] && { echo "$STUB_ARCH"; exit 0; }\nexec /bin/uname "$@"\n' > "$WORK/stubs-arch/uname"
chmod +x "$WORK/stubs-arch/uname"
qs_arch() {   # ARCH [VAR=value...]: a quick start run in a new directory, answering mode 1 and the defaults
  local arch="$1"; shift; rm -rf -- "$WORK/qs4"; mkdir -p "$WORK/qs4"; : > "$WORK/children4.log"
  (cd "$WORK/qs4" && printf '%s\n' 1 '' '' | env -i PATH="$WORK/stubs-arch:$WORK/stubs:$PATH" HOME="$WORK" STUB_ARCH="$arch" \
    STUB_LOG="$WORK/children4.log" STUB_DOCKER_OK=1 "$@" bash "$REPO/quickstart.sh" > "$WORK/out/o" 2> "$WORK/out/e") || true
}
qs_arch riscv64
{ grep -qF 'built for x86_64 and Arm64 (aarch64); this host is riscv64' "$WORK/out/e" && ! grep -q 'curl' "$WORK/children4.log" \
  && ! grep -qF 'Hosting mode' "$WORK/out/o"; } || { fail "quickstart.sh does not stop on an unsupported architecture"; show; }
ok
qs_arch aarch64 MDMESH_VERSION=0.5.0
{ grep -qF 'Verifying release v0.5.0' "$WORK/out/o" && ! grep -q 'no images for this host\|built for x86_64' "$WORK/out/e"; } \
  || { fail "quickstart.sh does not go on to verify a release on Arm64"; show; }
ok
# A release before 0.5.0 on Arm64: not sent to that release's own quickstart.sh (its images are x86_64 only too).
qs_arch aarch64 MDMESH_VERSION=0.4.0
{ grep -qF 'Release v0.4.0 of MDMesh-app/MDMesh has no images for this host (linux/arm64), only for linux/amd64.' "$WORK/out/e" \
  && grep -qF "MDMesh's releases have Arm64 images from 0.6.0" "$WORK/out/e" \
  && ! grep -qF 'refs/tags/v0.4.0/quickstart.sh' "$WORK/out/e" && ! grep -q 'curl' "$WORK/children4.log"; } \
  || { fail "quickstart.sh sends Arm64 to a release before 0.5.0"; show; }
ok
# A fork's releases: no advice about MDMesh's versions, which say nothing about the fork's images.
qs_arch aarch64 MDMESH_VERSION=0.4.0 MDMESH_REPO=acme/mdm
{ grep -qF 'Release v0.4.0 of acme/mdm has no images for this host (linux/arm64)' "$WORK/out/e" \
  && grep -qF 'Use a release whose images include linux/arm64' "$WORK/out/e" && ! grep -qF '0.6.0' "$WORK/out/e"; } \
  || { fail "quickstart.sh gives a fork's users MDMesh's version advice"; show; }
ok
qs_arch x86_64 MDMESH_VERSION=0.5.0
{ grep -qF 'Verifying release v0.5.0' "$WORK/out/o" && ! grep -q 'no images for this host\|built for x86_64' "$WORK/out/e"; } \
  || { fail "quickstart.sh does not go on to verify 0.5.0 on x86_64"; show; }
ok

# A re-run whose .env this user can't read says what to do, instead of a bare "Permission denied": its own .env (mode
# 000) → chmod 600 .env; another user's (here a link to /proc/1/environ: root's, mode 400) → run as them.
mkdir -p "$WORK/src4/install"; cp_setup "$WORK/src4"; cp -R -- "$REPO/install/lib" "$WORK/src4/install/"
printf 'BASE_URL=https://mdm.example.com\n' > "$WORK/src4/.env"; chmod 000 "$WORK/src4/.env"
(cd "$WORK/src4" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" STUB_LOG="$WORK/children.log" STUB_DOCKER_OK=1 \
  bash ./setup.sh < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
{ grep -qF "can't read .env (mode 000)" "$WORK/out/e" && grep -qF 'chmod 600 .env' "$WORK/out/e" \
  && ! grep -q 'Permission denied\|as that user' "$WORK/out/e"; } || { fail "an unreadable .env of this user's does not say chmod 600"; show; }
ok
if [ -e /proc/1/environ ] && [ ! -r /proc/1/environ ] && [ ! -O /proc/1/environ ]; then
  mv -- "$WORK/src4/.env" "$WORK/src4/.env.own"; ln -s /proc/1/environ "$WORK/src4/.env"
  (cd "$WORK/src4" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" STUB_LOG="$WORK/children.log" STUB_DOCKER_OK=1 \
    bash ./setup.sh < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
  { grep -qF "can't read .env" "$WORK/out/e" && grep -qF 'it belongs to root' "$WORK/out/e" && grep -qF 'as that user' "$WORK/out/e" \
    && ! grep -q 'Permission denied' "$WORK/out/e"; } || { fail "an unreadable .env of another user's does not name its owner"; show; }
  # Its target (mode 400) is not readable by others, though the link itself is 777: no warning about that.
  ! grep -qF 'readable by other users' "$WORK/out/o" "$WORK/out/e" || { fail "a .env linked to a private file is said to be readable by others"; show; }
  ok
fi
# A link to another user's file that everyone can read (/etc/passwd) is warned about.
if [ -r /etc/passwd ] && [ ! -O /etc/passwd ]; then
  rm -f -- "$WORK/src4/.env"; ln -s /etc/passwd "$WORK/src4/.env"
  (cd "$WORK/src4" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" STUB_LOG="$WORK/children.log" STUB_DOCKER_OK=1 \
    bash ./setup.sh < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
  grep -qF 'readable by other users' "$WORK/out/o" "$WORK/out/e" || { fail "a .env linked to a world-readable file is not warned about"; show; }
  ok
fi

# The answers file's own rules, and the native installer's older variable names.
dry quickstart.sh MDMESH_ANSWERS=answers.env
{ [ "$RC" != 0 ] && grep -q 'absolute path' "$WORK/out/e"; } || fail "a relative MDMESH_ANSWERS is not refused"
printf 'MDMESH_HOSTNAME=x\n' > "$WORK/answers.env"; dry quickstart.sh MDMESH_ANSWERS="$WORK/answers.env"
{ [ "$RC" != 0 ] && grep -q 'unknown key MDMESH_HOSTNAME' "$WORK/out/e"; } || fail "an unknown key in MDMESH_ANSWERS is not refused"
printf 'MDMESH_HOST=a.example.com\nMDMESH_HOST=b.example.com\n' > "$WORK/answers.env"; dry quickstart.sh MDMESH_ANSWERS="$WORK/answers.env"
{ [ "$RC" != 0 ] && grep -q 'set twice' "$WORK/out/e"; } || fail "a key set twice in MDMESH_ANSWERS is not refused"
printf 'MDMESH_HOST="a.example.com"\r\n' > "$WORK/answers.env"
dry quickstart.sh MDMESH_ANSWERS="$WORK/answers.env" MDMESH_HOST=b.example.com
[ "$(plan_field host)" = "b.example.com [MDMESH_HOST]" ] || { fail "the environment does not win over MDMESH_ANSWERS"; show; }
dry quickstart.sh MDMESH_ANSWERS="$WORK/answers.env"
[ "$(plan_field host)" = "a.example.com [MDMESH_HOST]" ] || { fail "quotes/CRLF in MDMESH_ANSWERS are not handled"; show; }
dry quickstart.sh MDMESH_NONINTERACTIVE=yes
{ [ "$RC" != 0 ] && grep -q 'MDMESH_NONINTERACTIVE must be 1 or 0' "$WORK/out/e"; } || fail "MDMESH_NONINTERACTIVE=yes is not refused"
dry install-native.sh BASE_URL=https://old.example.com ASSUME_YES=1
[ "$(plan_field base-url)" = "https://old.example.com [BASE_URL]" ] || { fail "the native installer's BASE_URL is not read"; show; }
dry install-native.sh BASE_URL=https://old.example.com MDMESH_BASE_URL=https://new.example.com
{ [ "$RC" != 0 ] && grep -q 'both set' "$WORK/out/e"; } || fail "different MDMESH_BASE_URL and BASE_URL are not refused"
dry install-native.sh MDMESH_NONINTERACTIVE=1 MDMESH_BASE_URL=https://mdm.example.com
{ [ "$RC" != 0 ] && grep -q 'MDMESH_ASSUME_YES' "$WORK/out/e"; } || fail "a non-interactive native run without MDMESH_ASSUME_YES is not refused"
CHECKS=$((CHECKS + 9))

# ---------------------------------------------------------------------------------------------------------------------
# Hosting modes (install/lib/modes.sh): the flags, the menu, answers from 0.5.x, and the .env each mode writes.
# Each flag the contract names picks its mode, in every installer that has the mode question, and is one it accepts.
for inst in quickstart.sh setup.sh; do
  for flag in $(jq -r '.options[] | select(.id == "mode") | .flags | keys[]' "$CONTRACT"); do
    m=$(jq -r --arg f "$flag" '.options[] | select(.id == "mode") | .flags[$f]' "$CONTRACT")
    jq -e --arg i "$inst" --arg f "$flag" '.installers[] | select(.id == $i) | .flags | index($f)' "$CONTRACT" > /dev/null \
      || fail "$inst: options.json's mode flag $flag is not among its flags"
    DRY_ARGS=("$flag"); dry "$inst" MDMESH_NONINTERACTIVE=1 MDMESH_HOST=mdm.example.com MDMESH_TUNNEL_TOKEN=eyJhIjoiYWJjIn0=
    { [ "$RC" = 0 ] && [ "$(plan_field mode)" = "$m [MDMESH_MODE]" ]; } || { fail "$inst $flag: the plan's mode is not $m"; show; }
    # The same mode in MDMESH_MODE (either form) agrees with it; another one is refused before anything happens.
    dry "$inst" MDMESH_MODE="$m" MDMESH_HOST=mdm.example.com
    [ "$RC" = 0 ] || { fail "$inst $flag with MDMESH_MODE=$m is refused"; show; }
    other=tunnel; [ "$m" != tunnel ] || other=domain
    dry "$inst" MDMESH_MODE="$other" MDMESH_HOST=mdm.example.com
    { [ "$RC" != 0 ] && grep -qF 'name different hosting modes' "$WORK/out/e"; } || { fail "$inst $flag with MDMESH_MODE=$other is not refused"; show; }
    DRY_ARGS=("$flag" "--$other"); dry "$inst" MDMESH_HOST=mdm.example.com
    { [ "$RC" = 2 ] && grep -qF 'name different hosting modes' "$WORK/out/e"; } || { fail "$inst $flag --$other is not refused"; show; }
    DRY_ARGS=(); ok
  done
done
DRY_ARGS=(--docker); dry quickstart.sh
{ [ "$RC" = 2 ] && grep -qF 'Unknown option: --docker' "$WORK/out/e"; } || { fail "quickstart.sh accepts an unknown option"; show; }
DRY_ARGS=(--native --proxy); dry setup.sh
{ [ "$RC" = 2 ] && grep -qF 'hosting mode of the Docker install' "$WORK/out/e"; } || { fail "setup.sh --native --proxy is not refused"; show; }
DRY_ARGS=(); ok
# 0.5.x answers keep their meaning: MDMESH_MODE=1 and 2 (an answers file from then, too); 3 is the proxy mode.
printf '%s\n' MDMESH_MODE=2 MDMESH_HOST=mdm.example.com MDMESH_ACME_EMAIL=ops@example.com > "$WORK/answers.env"
chmod 600 "$WORK/answers.env"
for inst in quickstart.sh setup.sh; do
  dry "$inst" MDMESH_NONINTERACTIVE=1 MDMESH_ANSWERS="$WORK/answers.env"
  { [ "$RC" = 0 ] && [ "$(plan_field mode)" = "domain [MDMESH_MODE]" ] && [ "$(plan_field acme-email)" = "ops@example.com [MDMESH_ACME_EMAIL]" ] \
    && [ "$(plan_field bind)" = "- [unused]" ] && [ "$(plan_field trusted-proxies)" = "- [unused]" ]; } \
    || { fail "$inst: a 0.5.x domain answers file reads differently"; show; }
  for c in 1:tunnel 2:domain 3:proxy; do
    dry "$inst" MDMESH_MODE="${c%%:*}" MDMESH_HOST=mdm.example.com
    [ "$(plan_field mode)" = "${c#*:} [MDMESH_MODE]" ] || { fail "$inst: MDMESH_MODE=${c%%:*} is not ${c#*:}"; show; }
  done
  # An answer of another mode is not used, saying so.
  dry "$inst" MDMESH_MODE=domain MDMESH_HOST=mdm.example.com MDMESH_BIND=127.0.0.1:9090 MDMESH_TUNNEL_TOKEN=eyJhIjoiYWJjIn0=
  { [ "$RC" = 0 ] && grep -qF 'MDMESH_BIND is not used in domain mode' "$WORK/out/e" \
    && grep -qF 'MDMESH_TUNNEL_TOKEN is not used in domain mode' "$WORK/out/e"; } || { fail "$inst: another mode's answers are not reported unused"; show; }
  dry "$inst" MDMESH_ORIGIN_TLS=yes
  { [ "$RC" != 0 ] && grep -qF 'MDMESH_ORIGIN_TLS must be 1 or 0' "$WORK/out/e"; } || { fail "$inst: MDMESH_ORIGIN_TLS=yes is not refused"; show; }
  ok
done

# The menu, as the design has it (plain text: what each choice is for), its footer, and the hint when ports 80/443 are
# taken (an ss stub that lists a listener on :443, as ss prints it: a header line first; for "free" one that lists
# nothing, so the host's own listeners don't count). A wrong choice is asked again; the run then goes on to the repo
# question and stops at the curl stub.
mkdir -p "$WORK/stubs-ss" "$WORK/stubs-ssfree" "$WORK/qs5"
SS_HEAD='State  Recv-Q Send-Q Local Address:Port Peer Address:Port'
printf '#!/bin/sh\necho "%s"\necho "LISTEN 0 511 0.0.0.0:443 0.0.0.0:*"\n' "$SS_HEAD" > "$WORK/stubs-ss/ss"
printf '#!/bin/sh\necho "%s"\n' "$SS_HEAD" > "$WORK/stubs-ssfree/ss"
chmod +x "$WORK/stubs-ss/ss" "$WORK/stubs-ssfree/ss"
for ports in free busy; do
  p="$WORK/stubs-ssfree:$WORK/stubs:$PATH"; [ "$ports" = free ] || p="$WORK/stubs-ss:$WORK/stubs:$PATH"
  (cd "$WORK/qs5" && printf '%s\n' 4 3 '' '' | env -i PATH="$p" HOME="$WORK" STUB_LOG="$WORK/children.log" STUB_DOCKER_OK=1 \
    bash "$REPO/quickstart.sh" > "$WORK/out/o" 2> "$WORK/out/e") || true
  menu=$(sed -n '/^How will your phones reach MDMesh?$/,/^Without Docker/p' "$WORK/out/o")
  want="How will your phones reach MDMesh?
  1) Cloudflare Tunnel       easiest: no ports to open (domain on Cloudflare)
  2) Your own domain         MDMesh gets its own HTTPS (ports 80/443 must reach this machine)
  3) Behind your own proxy   your nginx/Apache/Caddy/Traefik does HTTPS; MDMesh listens on 127.0.0.1:8080"
  [ "$ports" = free ] || want="$want
  Ports 80/443 are already in use on this machine. If that is your own web server, pick 3."
  want="$want
Without Docker? See Native in DEPLOY.md: https://github.com/MDMesh-app/MDMesh/blob/main/DEPLOY.md#option-c--native-no-docker"
  { [ "$menu" = "$want" ] && grep -qF 'Please enter 1, 2 or 3.' "$WORK/out/o" \
    && grep -qF 'Could not resolve the latest release of MDMesh-app/MDMesh' "$WORK/out/e"; } \
    || { fail "quickstart.sh's menu (ports $ports) is not as designed: $(diff <(echo "$want") <(echo "$menu") | tr '\n' ' ')"; show; }
  ok
done

# What setup.sh writes and runs per mode: a real run in a copy, stopping at the docker stub (`up`). Behind your proxy:
# the overlay, where the edge listens and whom it believes, in .env and on compose's command line; site.d made and its
# absolute path in .env.
setup_run() {  # setup_run DIR [NAME=VALUE...]: ./setup.sh in DIR (a copy), stdin empty; docker calls in $WORK/docker.log
  local d=$1; shift
  : > "$WORK/docker.log"
  (cd "$d" && env -i PATH="$WORK/stubs-ssfree:$WORK/stubs:$PATH" HOME="$WORK" STUB_LOG="$WORK/docker.log" STUB_DOCKER_OK=1 "$@" \
    bash ./setup.sh ${SETUP_ARGS[@]+"${SETUP_ARGS[@]}"} < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
}
up_args() { sed -n 's#^\S*/docker compose \(.*\) up -d --build$#\1#p' "$WORK/docker.log" | head -1; }
SETUP_ARGS=()
mkdir -p "$WORK/src6/install"; cp_setup "$WORK/src6"; cp -R -- "$REPO/install/lib" "$WORK/src6/install/"
SETUP_ARGS=(--proxy)
setup_run "$WORK/src6" MDMESH_NONINTERACTIVE=1 MDMESH_HOST=mdm.example.com MDMESH_TRUSTED_PROXIES='192.0.2.10, host'
for l in 'COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml' 'COMPOSE_PROFILES=' 'SITE_ADDRESS=:8080' \
         'MDMESH_BIND=127.0.0.1:8080' 'TRUSTED_PROXIES="192.0.2.10 host"' 'MDMESH_ORIGIN_TLS=0' "MDMESH_SITE_DIR=$WORK/src6/site.d" \
         'ACME_EMAIL=' 'TUNNEL_TOKEN=' 'BASE_URL=https://mdm.example.com'; do
  grep -qxF -- "$l" "$WORK/src6/.env" || { fail "setup.sh --proxy: .env has no line $l"; show; }
done
{ [ "$(up_args)" = '-f docker-compose.yml -f docker-compose.proxy.yml' ] && [ -f "$WORK/src6/site.d/README" ] \
  && [ "$(stat -c %a "$WORK/src6/site.d")" = 755 ]; } || { fail "setup.sh --proxy: compose args \"$(up_args)\", or no site.d"; show; }
ok
# A re-run keeps the mode .env has, whatever mode is asked for now (saying so), and keeps .env's own values.
cp "$WORK/src6/.env" "$WORK/env6.before"
SETUP_ARGS=(--domain); setup_run "$WORK/src6"; SETUP_ARGS=()
{ [ "$(up_args)" = '-f docker-compose.yml -f docker-compose.proxy.yml' ] && grep -qF 'runs in proxy mode, and a re-run keeps it' "$WORK/out/o" \
  && cmp -s <(grep -v '_VERSION=\|^VITE_\|^APPLY_SUPPORTED=' "$WORK/env6.before") <(grep -v '_VERSION=\|^VITE_\|^APPLY_SUPPORTED=' "$WORK/src6/.env"); } \
  || { fail "a setup.sh re-run does not keep the proxy mode and its .env"; show; diff "$WORK/env6.before" "$WORK/src6/.env" >&2; }
ok
# With an override of the operator's own in .env's COMPOSE_FILE, the re-run starts the stack with it too.
sed -i 's#^COMPOSE_FILE=.*#COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml:docker-compose.local.yml#' "$WORK/src6/.env"
setup_run "$WORK/src6"
[ "$(up_args)" = '-f docker-compose.yml -f docker-compose.proxy.yml -f docker-compose.local.yml' ] \
  || { fail "a setup.sh re-run ignores the COMPOSE_FILE list .env saved (compose args \"$(up_args)\")"; show; }
ok
# A path in that list with blanks in it stays one argument.
sed -i 's#^COMPOSE_FILE=.*#COMPOSE_FILE="docker-compose.yml:docker-compose.proxy.yml:/srv/MDMesh rules/override.yml"#' "$WORK/src6/.env"
: > "$WORK/argv.log"; setup_run "$WORK/src6" STUB_ARGV="$WORK/argv.log"
grep -qxF '/srv/MDMesh rules/override.yml' "$WORK/argv.log" \
  || { fail "a setup.sh re-run splits a COMPOSE_FILE path with a blank in it"; show; grep -A8 -- '^-f$' "$WORK/argv.log" | head -12 >&2; }
cp "$WORK/env6.before" "$WORK/src6/.env"
ok
# Installs from before 0.6.0 keep their compose command (tunnel: COMPOSE_FILE from .env; domain: the domain overlay),
# gain MDMESH_SITE_DIR, and keep their other .env values (SITE_ADDRESS, TRUSTED_PROXIES as the operator set them).
for c in 'cloudflare|:80|--profile cloudflare' '|mdm.example.com, www.example.com|-f docker-compose.yml -f docker-compose.domain.yml'; do
  IFS='|' read -r prof site want <<< "$c"
  rm -rf -- "$WORK/src7"; mkdir -p "$WORK/src7/install"; cp_setup "$WORK/src7"; cp -R -- "$REPO/install/lib" "$WORK/src7/install/"
  printf '%s\n' DB_PASSWORD=x HASH_SECRET=y BASE_URL=https://mdm.example.com "SITE_ADDRESS=\"$site\"" \
    'TRUSTED_PROXIES="203.0.113.0/24"' "COMPOSE_PROFILES=$prof" IMAGE_OWNER=local > "$WORK/src7/.env"
  setup_run "$WORK/src7"
  { [ "$(up_args)" = "$want" ] && grep -qxF "MDMESH_SITE_DIR=$WORK/src7/site.d" "$WORK/src7/.env" \
    && grep -qxF "SITE_ADDRESS=\"$site\"" "$WORK/src7/.env" && grep -qxF 'TRUSTED_PROXIES="203.0.113.0/24"' "$WORK/src7/.env" \
    && grep -qxF "SITE_ADDRESS=$site" "$WORK/docker.log"; } \
    || { fail "a 0.5.x .env (COMPOSE_PROFILES=$prof) re-run: compose args \"$(up_args)\", want \"$want\", or .env changed"; show; }
  ok
done

# Your own domain: a re-run stops, before any container is touched, when the edge binds host port 80 or 443 and the
# compose files it would start publish it no more (install/lib/ports.sh). docker is a stub: STUB_IDS are the edge's
# containers (inspect answers $STUB_BIND), STUB_CONFIG the file `config` prints (as compose 5.3.1 prints it);
# "fail" for any of the three makes it fail. Only `up` ends the run (the stub fails it).
mkdir -p "$WORK/stubs-ports"
# shellcheck disable=SC2016  # the stub's own variables
printf '%s\n' '#!/bin/sh' 'echo "docker $*" >> "$STUB_LOG"' \
  'no() { [ "$1" != fail ] || { echo "the $2 error" >&2; exit 1; }; }' 'case "$*" in' \
  '  "compose ps -a -q caddy") no "$STUB_IDS" ps; printf "%s\n" $STUB_IDS ;;' \
  '  "inspect --format {{json .HostConfig.PortBindings}}"*) no "$STUB_BIND" inspect; shift 3' \
  '    [ "$#" != 0 ] || no fail inspect; for i; do case "$i" in idle) echo "{}" ;; *) printf "%s\n" "$STUB_BIND" ;; esac; done ;;' \
  '  compose*" config") [ "$STUB_CONFIG" != fail ] || echo "      POSTGRES_PASSWORD: s3cr3t-pw"; no "$STUB_CONFIG" config; cat "$STUB_CONFIG" ;;' \
  '  compose*" up "*) echo "up env COMPOSE_FILE=${COMPOSE_FILE-unset}" >> "$STUB_LOG"; exit 99 ;;' \
  'esac' 'exit 0' > "$WORK/stubs-ports/docker"
chmod +x "$WORK/stubs-ports/docker"
# conf NAME PORT...: `config` output whose caddy service publishes each PORT.
conf() {
  local f="$WORK/conf.$1" c; shift
  { printf '%s\n' 'name: mdmesh' 'services:' '  caddy:' '    labels:' '      note: "published: 443"' '    environment:' '      SITE_ADDRESS: :80'
    [ "$#" = 0 ] || echo '    ports:'
    for c in "$@"; do printf '%s\n' '      - mode: ingress' "        target: ${c//\"/}" "        published: $c" '        protocol: tcp'; done
    printf '%s\n' '  postgres:' '    environment:' '      POSTGRES_PASSWORD: s3cr3t-pw'; } > "$f"
}
conf none; conf both '"80"' '"443"'; conf numbers 80 443; conf only443 '"443"'; conf other '"8080"' '"4430"'
EDGE_BOTH='{"443/tcp":[{"HostIp":"","HostPort":"443"}],"80/tcp":[{"HostIp":"","HostPort":"80"}]}'
EDGE_443='{"443/tcp":[{"HostIp":"0.0.0.0","HostPort":"443"}]}'
EDGE_8080='{"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"8080"}]}'
# ports_run CONFIG BINDINGS LIST [ENV LINE...]: a re-run of ./setup.sh whose .env's COMPOSE_FILE is LIST ("-": no such
# line). Sets RC, UP (how often `up` ran) and CONF (`config`); ENV_SAME=1 when .env is as it was, but for its mode bits.
ports_run() {
  local c=$1 bind=$2 list=$3; shift 3
  [ "$c" = fail ] || c="$WORK/conf.$c"
  rm -rf -- "$WORK/src12"; mkdir -p "$WORK/src12/install"; cp_setup "$WORK/src12"; cp -R -- "$REPO/install/lib" "$WORK/src12/install/"
  { printf '%s\n' DB_PASSWORD=s3cr3t-pw HASH_SECRET=y BASE_URL=https://mdm.example.com SITE_ADDRESS=mdm.example.com IMAGE_OWNER=local
    [ "$list" = - ] || echo "COMPOSE_FILE=\"$list\""; [ "$#" = 0 ] || printf '%s\n' "$@"; } > "$WORK/src12/.env"
  ports_again "$c" "$bind"
}
ports_again() {
  cp "$WORK/src12/.env" "$WORK/env12.before"; : > "$WORK/ports.log"; RC=0
  (cd "$WORK/src12" && env -i PATH="$WORK/stubs-ports:$WORK/stubs:$PATH" HOME="$WORK" STUB_LOG="$WORK/ports.log" STUB_IDS="${STUB_IDS-edge1}" \
    STUB_CONFIG="$1" STUB_BIND="$2" bash ./setup.sh < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || RC=$?
  UP=$(grep -c '^docker compose .*up -d --build$' "$WORK/ports.log" || true); CONF=$(grep -c ' config$' "$WORK/ports.log" || true)
  ENV_SAME=0; ! cmp -s "$WORK/env12.before" "$WORK/src12/.env" || ENV_SAME=1
}
# stopped WHAT TEXT...: the run stopped, exit 1, with each TEXT; no container touched, .env as it was, no secret shown.
stopped() {
  local what=$1 t good=1; shift
  for t in "$@"; do grep -qF -- "$t" "$WORK/out/e" || { good=0; echo "missing: $t" >&2; }; done
  { [ "$good" = 1 ] && [ "$RC" = 1 ] && [ "$UP" = 0 ] && [ "$ENV_SAME" = 1 ] && ! grep -qE ' (up|build|rm|stop|down|start|restart) ?' "$WORK/ports.log" \
    && grep -qF 'Nothing was started or stopped' "$WORK/out/e" && ! grep -q 's3cr3t-pw' "$WORK/out/o" "$WORK/out/e"; } \
    || { fail "$what: not stopped as it should be (rc $RC, up $UP, .env same $ENV_SAME)"; show; cat "$WORK/ports.log" >&2; }
  ok
}
# goes_on WHAT CONFIGS: the run went on to `up`, having asked `config` CONFIGS times.
goes_on() {
  { [ "$UP" = 1 ] && [ "$CONF" = "$2" ] && ! grep -qF 'Nothing was started or stopped' "$WORK/out/e"; } \
    || { fail "$1: did not go on to up, or asked config $CONF times, want $2"; show; cat "$WORK/ports.log" >&2; }
  ok
}
D=docker-compose.domain.yml
# Lists that publish neither: stopped, with the line that adds the domain file to the list as saved (no file of it lost).
for c in docker-compose.yml ./docker-compose.yml /srv/m/docker-compose.yml docker-compose.yml:docker-compose.override.yml \
         "docker-compose.yml:my-$D" "docker-compose.yml:$D.bak" 'docker-compose.yml:/srv/MDMesh rules/override.yml'; do
  ports_run none "$EDGE_BOTH" "$c"
  stopped "COMPOSE_FILE=$c with the edge on 80/443" "port 80 and 443 would close" "COMPOSE_FILE='$c:$D'" \
    'before any update or rollback in the console' 'docker compose rm -sf caddy'
done
# `config` is asked of the list `up` gets.
grep -qxF "docker compose -f docker-compose.yml -f /srv/MDMesh rules/override.yml config" "$WORK/ports.log" \
  || { fail "config is not asked of the run's own list"; cat "$WORK/ports.log" >&2; }
ok
# One port of two, or another host port, is no substitute.
ports_run only443 "$EDGE_BOTH" docker-compose.yml:mine.yml; stopped "a list that drops 80 alone" 'port 80 would close' 'publish it in your own compose file'
ports_run other "$EDGE_443" docker-compose.yml:mine.yml; stopped "a list that publishes 8080 and 4430 instead of 443" 'port 443 would close' "'docker-compose.yml:mine.yml:$D'"
# Any of the edge's containers counts, the first or not (a stopped one too: HostConfig, in ps -a).
for STUB_IDS in 'idle edge1' 'edge1 idle'; do ports_run none "$EDGE_BOTH" docker-compose.yml; stopped "one of the containers $STUB_IDS on 80/443"; done; unset STUB_IDS
# What can't be answered stops too, in its own words, and shows no line of config's output.
for c in ps inspect config; do
  bind=$EDGE_BOTH; conf=none; STUB_IDS=edge1
  case "$c" in ps) STUB_IDS=fail ;; inspect) bind=fail ;; config) conf=fail ;; esac
  ports_run "$conf" "$bind" 'docker-compose.yml '; stopped "docker's $c failing" "the $c error"
done
unset STUB_IDS
# Published by the list (an override of the operator's, another service of the stack; quoted or not): goes on.
for c in both numbers; do ports_run $c "$EDGE_BOTH" docker-compose.yml:mine.yml; goes_on "a list that publishes 80/443 itself ($c)" 1; done
ports_run both "$EDGE_443" docker-compose.yml:mine.yml; goes_on "a list that publishes one port more" 1
# The edge binds neither (none, others, no edge at all): nothing to lose, and config isn't asked.
for c in '{}' null "$EDGE_8080" '{"443/tcp":[{"HostIp":"","HostPort":"8443"}]}'; do ports_run fail "$c" docker-compose.yml; goes_on "the edge binds $c" 0; done
STUB_IDS=''; ports_run fail "$EDGE_BOTH" docker-compose.yml; goes_on "no edge container" 0; unset STUB_IDS
# The domain overlay in the list (as the installers write it, or any path to it; without COMPOSE_FILE the run adds it):
# nothing is asked, so nothing docker or compose says can stop it.
for c in "docker-compose.yml:$D" "/srv/m/docker-compose.yml:/srv/m/$D" - ''; do
  STUB_IDS=fail; ports_run fail fail "$c"; goes_on "COMPOSE_FILE=$c" 0
  ! grep -q 'compose ps\|inspect' "$WORK/ports.log" || fail "COMPOSE_FILE=$c: the edge was asked"
done
unset STUB_IDS
# Moves between modes through ./setup.sh: proxy -> domain, domain -> proxy, domain -> tunnel.
ports_run fail "$EDGE_8080" "docker-compose.yml:$D"; goes_on "proxy to domain" 0
ports_run none "$EDGE_BOTH" docker-compose.yml:docker-compose.proxy.yml MDMESH_BIND=127.0.0.1:8080 'TRUSTED_PROXIES="host"'; goes_on "domain to proxy" 0
for c in cloudflare cloudflare,backup; do ports_run none "$EDGE_BOTH" docker-compose.yml "COMPOSE_PROFILES=$c"; goes_on "domain to tunnel ($c)" 0; done
# Stopped, then the shown line set in .env: the same run goes on with the operator's list and the domain file, and hands
# the supervisor that list.
for c in docker-compose.yml:docker-compose.override.yml 'docker-compose.yml:/srv/MDMesh rules/override.yml'; do
  ports_run none "$EDGE_BOTH" "$c"
  line=$(grep -o "COMPOSE_FILE='.*'" "$WORK/out/e" || true)
  grep -v '^COMPOSE_FILE=' "$WORK/src12/.env" > "$WORK/env12"; printf '%s\n' "$line" >> "$WORK/env12"; cp "$WORK/env12" "$WORK/src12/.env"
  ports_again "$WORK/conf.both" "$EDGE_BOTH"; goes_on "$c, then the line it showed" 0
  want="$c:$D"
  { grep -qxF "docker compose -f ${want//:/ -f } up -d --build" "$WORK/ports.log" && grep -qxF "up env COMPOSE_FILE=$want" "$WORK/ports.log"; } \
    || { fail "$c, then the line it showed: up or the supervisor has another list"; cat "$WORK/ports.log" >&2; }
  ok
done

# A site.d that already exists and others may write is not connected to the edge (never made private and then trusted),
# and left out of .env so a later run tries again; once it is private, it is.
rm -rf -- "$WORK/src7"; mkdir -p "$WORK/src7/install" "$WORK/src7/site.d"; chmod 775 "$WORK/src7/site.d"
cp_setup "$WORK/src7"; cp -R -- "$REPO/install/lib" "$WORK/src7/install/"
printf '%s\n' DB_PASSWORD=x HASH_SECRET=y BASE_URL=https://mdm.example.com SITE_ADDRESS=mdm.example.com \
  COMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml COMPOSE_PROFILES= IMAGE_OWNER=local > "$WORK/src7/.env"
setup_run "$WORK/src7"
{ grep -qF 'site.d is not connected to the edge' "$WORK/out/o" "$WORK/out/e" && ! grep -q '^MDMESH_SITE_DIR=' "$WORK/src7/.env" \
  && [ "$(stat -c %a "$WORK/src7/site.d")" = 775 ]; } || { fail "setup.sh trusts a group-writable site.d, or changes it"; show; }
chmod 755 "$WORK/src7/site.d"; setup_run "$WORK/src7"
grep -qxF "MDMESH_SITE_DIR=$WORK/src7/site.d" "$WORK/src7/.env" || { fail "setup.sh does not connect site.d once it is private"; show; }
ok

# install/lib/modes.sh: the proxy overlay is found in COMPOSE_FILE as the installers write it and as a hand edit might
# (./ or a path). supervisor/mode-guard.sh (the updater's rule, which setup.sh --update and the quick start use too): a
# release before 0.6.0 can't run the proxy mode.
RC=0; out=$(env -i bash -c '. "$1"; . "$2"; r=""
  for l in docker-compose.yml:docker-compose.proxy.yml docker-compose.yml:./docker-compose.proxy.yml /srv/m/docker-compose.proxy.yml; do
    mdm_compose_has_proxy "$l" || r="$r has:$l"; done
  for l in docker-compose.yml:docker-compose.domain.yml docker-compose.proxy.yml.bak "" docker-compose.yml:my-docker-compose.proxy.yml.d; do
    ! mdm_compose_has_proxy "$l" || r="$r not:$l"; done
  export ENV_FILE=/nonexistent COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml
  for v in 0.5.9 0.5.1@sha256:aa 0.0.1; do [ -n "$(mode_problem "$v")" ] || r="$r old:$v"; done
  for v in 0.6.0 0.6.1 1.0.0 9.1.0 latest ""; do [ -z "$(mode_problem "$v")" ] || r="$r new:$v"; done
  echo "r=$r"' _ "$REPO/install/lib/modes.sh" "$REPO/supervisor/mode-guard.sh" 2>&1) || RC=$?
[ "$out" = "r=" ] || fail "mdm_compose_has_proxy or mode_problem got these wrong: $out"
ok

# A setup.sh re-run to the end, with a docker stub that answers like a running stack (server booted, database seeded):
# the always-run database repairs run whether or not the edge stays up; an edge that doesn't stay up then fails the
# run, after the summary, with its logs.
mkdir -p "$WORK/stubs-full"
# shellcheck disable=SC2016  # the stub's own variables
printf '%s\n' '#!/bin/sh' 'echo "docker $*" >> "$STUB_LOG"' 'case "$*" in' \
  '  *"exec -T -u mdmesh server cat"*) echo OK ;;' \
  '  *psql*"FROM settings)||"*) echo 1/0 ;;' \
  '  *psql*"createnewdevices AND"*) echo 1 ;;' \
  '  *psql*) cat > "$STUB_LOG.stdin" ;;' \
  '  "compose ps -a -q caddy") echo edge1 ;;' \
  '  "inspect --format {{json .HostConfig.PortBindings}} "*) grep -q " up -d" "$STUB_LOG" || { echo "{}"; exit 0; }   # before up: none' \
  '    [ "$STUB_BIND" != fail ] || exit 1; echo "$STUB_BIND" ;;' \
  '  "inspect "*) echo "$STUB_EDGE t0" ;;' \
  '  "compose logs"*) echo "the edge log line" ;;' \
  'esac' 'exit 0' > "$WORK/stubs-full/docker"
chmod +x "$WORK/stubs-full/docker"
for e in restarting running; do
  rm -rf -- "$WORK/src10"; mkdir -p "$WORK/src10/install"; cp_setup "$WORK/src10"; cp -R -- "$REPO/install/lib" "$REPO/install/sql" "$WORK/src10/install/"
  printf '%s\n' DB_PASSWORD=x HASH_SECRET=y BASE_URL=https://mdm.example.com SITE_ADDRESS=mdm.example.com \
    COMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml COMPOSE_PROFILES= IMAGE_OWNER=local > "$WORK/src10/.env"
  : > "$WORK/full.log"; RC=0
  (cd "$WORK/src10" && env -i PATH="$WORK/stubs-full:$PATH" HOME="$WORK" STUB_LOG="$WORK/full.log" STUB_EDGE=$e STUB_BIND="$EDGE_BOTH" MDM_EDGE_SETTLE=0 \
    bash ./setup.sh < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || RC=$?
  repairs=$(grep -n 'createnewdevices AND' "$WORK/full.log" | head -1 | cut -d: -f1 || true)
  edge=$(grep -n '^docker inspect ' "$WORK/full.log" | head -1 | cut -d: -f1 || true)
  if [ "$e" = running ]; then
    { [ "$RC" = 0 ] && grep -qF '== MDMesh is up ==' "$WORK/out/o"; } || { fail "setup.sh to the end with its edge up does not succeed"; show; }
  else
    { [ "$RC" != 0 ] && [ -n "$repairs" ] && [ -n "$edge" ] && [ "$repairs" -lt "$edge" ] && grep -qF 'the edge log line' "$WORK/out/e" \
      && grep -qF 'its edge is not up' "$WORK/out/e" && grep -qF 'Console:' "$WORK/out/o"; } \
      || { fail "setup.sh with its edge down: repairs not run first, or no summary, or not a failure (rc $RC)"; show; }
  fi
  ok
done

# The same run to the end in domain mode, with a list that leaves the domain file out: when neither port is found
# published (or Docker can't say), it says so, with the .env line, and claims no open port. Other summaries are as always.
for c in "docker-compose.yml:$D||fail|claim" "docker-compose.yml:mine.yml||$EDGE_443|claim" 'docker-compose.yml:mine.yml||{}|line' \
         'docker-compose.yml:mine.yml||fail|line' 'docker-compose.yml|cloudflare|{}|entry point (Cloudflare Tunnel)' \
         'docker-compose.yml:docker-compose.proxy.yml||{}|behind your proxy'; do
  IFS='|' read -r list prof bind what <<< "$c"
  rm -rf -- "$WORK/src10"; mkdir -p "$WORK/src10/install"; cp_setup "$WORK/src10"; cp -R -- "$REPO/install/lib" "$REPO/install/sql" "$WORK/src10/install/"
  printf '%s\n' DB_PASSWORD=x HASH_SECRET=y BASE_URL=https://mdm.example.com SITE_ADDRESS=mdm.example.com \
    "COMPOSE_FILE=$list" "COMPOSE_PROFILES=$prof" IMAGE_OWNER=local > "$WORK/src10/.env"
  : > "$WORK/full.log"; RC=0
  (cd "$WORK/src10" && env -i PATH="$WORK/stubs-full:$PATH" HOME="$WORK" STUB_LOG="$WORK/full.log" STUB_EDGE=running STUB_BIND="$bind" \
    MDM_EDGE_SETTLE=0 bash ./setup.sh < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || RC=$?
  up=0; ! grep -qF '== MDMesh is up ==' "$WORK/out/o" || up=1
  claims=0; ! grep -qE 'entry point \(ports? |ports? [0-9/]+ (is|are) open' "$WORK/out/o" || claims=1
  line=0; ! grep -qF "COMPOSE_FILE='docker-compose.yml:mine.yml:$D'" "$WORK/out/o" || line=1
  want="rc=0 up=1 claims=0 line=0"; t=$what
  case "$what" in
    claim) want="rc=0 up=1 claims=1 line=0"; t='entry point (ports 80/443)' ;;
    line) want="rc=0 up=1 claims=0 line=1"; t='(no port 80 or 443 found published)' ;;
  esac
  got="rc=$RC up=$up claims=$claims line=$line"
  { [ "$got" = "$want" ] && grep -qF -- "$t" "$WORK/out/o"; } \
    || { fail "setup.sh's summary, COMPOSE_FILE=$list, the edge on $bind: $got, want $want and \"$t\""; show; }
  ok
done

# A site.d that is a link is not connected either, saying what it is (no chmod hint).
rm -rf -- "$WORK/src7"; mkdir -p "$WORK/src7/install" "$WORK/elsewhere"; ln -s "$WORK/elsewhere" "$WORK/src7/site.d"
cp_setup "$WORK/src7"; cp -R -- "$REPO/install/lib" "$WORK/src7/install/"
printf '%s\n' DB_PASSWORD=x HASH_SECRET=y BASE_URL=https://mdm.example.com SITE_ADDRESS=mdm.example.com \
  COMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml COMPOSE_PROFILES= IMAGE_OWNER=local > "$WORK/src7/.env"
setup_run "$WORK/src7"
{ grep -qF 'it is a link or not a directory' "$WORK/out/o" "$WORK/out/e" && ! grep -qF 'chmod go-w' "$WORK/out/o" "$WORK/out/e" \
  && ! grep -q '^MDMESH_SITE_DIR=' "$WORK/src7/.env"; } || { fail "setup.sh with site.d a link: no clear message, or connected"; show; }
ok

# Moved out of the proxy mode with TRUSTED_PROXIES="host" left in .env: a re-run says to clear it, and goes on.
rm -rf -- "$WORK/src7"; mkdir -p "$WORK/src7/install"; cp_setup "$WORK/src7"; cp -R -- "$REPO/install/lib" "$WORK/src7/install/"
printf '%s\n' DB_PASSWORD=x HASH_SECRET=y BASE_URL=https://mdm.example.com SITE_ADDRESS=mdm.example.com 'TRUSTED_PROXIES="host"' \
  COMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml COMPOSE_PROFILES= IMAGE_OWNER=local > "$WORK/src7/.env"
setup_run "$WORK/src7"
{ grep -qF 'TRUSTED_PROXIES in .env says host, which only the proxy mode reads' "$WORK/out/o" "$WORK/out/e" \
  && [ "$(up_args)" = '-f docker-compose.yml -f docker-compose.domain.yml' ]; } || { fail "a re-run out of the proxy mode with host left does not say to clear it"; show; }
ok

# Behind your own proxy: `host` (a proxy on this machine) only with MDMesh on a loopback address, refused before
# anything happens (dry runs too); a proxy's own address with any address.
for inst in quickstart.sh setup.sh; do
  dry "$inst" MDMESH_MODE=proxy MDMESH_HOST=mdm.example.com MDMESH_BIND=0.0.0.0:8080
  { [ "$RC" != 0 ] && grep -qF 'host (a proxy on this machine) needs MDMesh on a loopback address' "$WORK/out/e"; } \
    || { fail "$inst: MDMESH_BIND=0.0.0.0:8080 with the default TRUSTED_PROXIES=host is not refused"; show; }
  dry "$inst" MDMESH_MODE=proxy MDMESH_HOST=mdm.example.com MDMESH_BIND=10.0.0.5:8080 MDMESH_TRUSTED_PROXIES='192.0.2.10, host'
  [ "$RC" != 0 ] || { fail "$inst: host with MDMESH_BIND=10.0.0.5:8080 is not refused"; show; }
  for ok in '[::1]:8080|host' '127.0.0.2:9000|host' '10.0.0.5:8080|192.0.2.10'; do
    dry "$inst" MDMESH_MODE=proxy MDMESH_HOST=mdm.example.com MDMESH_BIND="${ok%|*}" MDMESH_TRUSTED_PROXIES="${ok#*|}"
    [ "$RC" = 0 ] || { fail "$inst: MDMESH_BIND=${ok%|*} with ${ok#*|} is refused"; show; }
  done
  # MDMESH_ORIGIN_TLS outside the proxy mode is reported unused, like any other mode's answer.
  dry "$inst" MDMESH_MODE=domain MDMESH_HOST=mdm.example.com MDMESH_ORIGIN_TLS=1
  { [ "$RC" = 0 ] && grep -qF 'MDMESH_ORIGIN_TLS is not used in domain mode' "$WORK/out/e"; } \
    || { fail "$inst: MDMESH_ORIGIN_TLS=1 in domain mode is not reported unused"; show; }
  dry "$inst" MDMESH_MODE=domain MDMESH_HOST=mdm.example.com MDMESH_ORIGIN_TLS=0
  { [ "$RC" = 0 ] && ! grep -qF 'MDMESH_ORIGIN_TLS is not used' "$WORK/out/e"; } \
    || { fail "$inst: MDMESH_ORIGIN_TLS=0 (the default) is reported unused"; show; }
  ok
done
# A setup.sh re-run reuses .env and doesn't check answers it won't use: proxy answers that would be refused for a new
# .env (host off loopback) don't stop it.
SETUP_ARGS=(); setup_run "$WORK/src6" MDMESH_MODE=proxy MDMESH_BIND=0.0.0.0:8080
{ [ "$(up_args)" = '-f docker-compose.yml -f docker-compose.proxy.yml' ] && grep -qF 'answers are not used' "$WORK/out/o" "$WORK/out/e"; } \
  || { fail "a setup.sh re-run is stopped by answers it doesn't use"; show; }
ok
# A proxy list with a line break is refused (the line after it would be dropped from .env).
dry setup.sh MDMESH_MODE=proxy MDMESH_HOST=mdm.example.com "MDMESH_TRUSTED_PROXIES=192.0.2.10
10.0.0.1"
{ [ "$RC" != 0 ] && grep -qF 'has a line break' "$WORK/out/e"; } || { fail "a proxy list with a line break is not refused"; show; }
ok
# Asked at the terminal with a non-loopback MDMESH_BIND given: the proxies are asked for, not refused before.
rm -rf -- "$WORK/src12"; mkdir -p "$WORK/src12/install"; cp_setup "$WORK/src12"; cp -R -- "$REPO/install/lib" "$WORK/src12/install/"
: > "$WORK/docker.log"
(cd "$WORK/src12" && printf '%s\n' mdm.example.com 192.0.2.10 | env -i PATH="$WORK/stubs-ssfree:$WORK/stubs:$PATH" HOME="$WORK" \
  STUB_LOG="$WORK/docker.log" STUB_DOCKER_OK=1 MDMESH_MODE=proxy MDMESH_BIND=10.0.0.5:8080 bash ./setup.sh > "$WORK/out/o" 2> "$WORK/out/e") || true
{ grep -qxF 'TRUSTED_PROXIES="192.0.2.10"' "$WORK/src12/.env" && ! grep -qF 'needs MDMesh on a loopback' "$WORK/out/e"; } \
  || { fail "setup.sh at the terminal with a non-loopback MDMESH_BIND doesn't ask for the proxies"; show; [ ! -f "$WORK/src12/.env" ] || sed 's/^/    env| /' "$WORK/src12/.env" >&2; }
ok
# A release before 0.6.0 named in advance has no proxy mode: refused at once, dry run included.
dry quickstart.sh MDMESH_MODE=proxy MDMESH_HOST=mdm.example.com MDMESH_VERSION=0.5.1
{ [ "$RC" != 0 ] && grep -qF 'behind your own proxy came in 0.6.0' "$WORK/out/e"; } || { fail "proxy mode with MDMESH_VERSION=0.5.1 is not refused"; show; }
dry quickstart.sh MDMESH_MODE=proxy MDMESH_HOST=mdm.example.com MDMESH_VERSION=0.6.0
[ "$RC" = 0 ] || { fail "proxy mode with MDMESH_VERSION=0.6.0 is refused"; show; }
# And with no release named (the latest one), the check doesn't trip on the unset MDMESH_VERSION.
dry quickstart.sh MDMESH_MODE=proxy MDMESH_HOST=mdm.example.com
! grep -qF 'unbound variable' "$WORK/out/e" || { fail "proxy mode without MDMESH_VERSION stops on an unbound variable"; show; }
ok

# setup.sh asked at the terminal, behind a proxy on another machine: the address in use is refused and asked again
# (an ss stub lists 127.0.0.1:8080), Enter for `host` with a LAN address is refused and asked again, and a rootless
# Docker (the docker stub's `info`) is warned about. It stops at the docker stub (`up`).
mkdir -p "$WORK/stubs-busy8080" "$WORK/src8/install"
printf '#!/bin/sh\necho "%s"\necho "LISTEN 0 511 127.0.0.1:8080 0.0.0.0:*"\n' "$SS_HEAD" > "$WORK/stubs-busy8080/ss"
chmod +x "$WORK/stubs-busy8080/ss"
cp_setup "$WORK/src8"; cp -R -- "$REPO/install/lib" "$WORK/src8/install/"
(cd "$WORK/src8" && printf '%s\n' 3 mdm.example.com '' 10.0.0.5:8080 '' 192.0.2.10 | env -i PATH="$WORK/stubs-busy8080:$WORK/stubs:$PATH" \
  HOME="$WORK" STUB_LOG="$WORK/docker.log" STUB_DOCKER_OK=1 STUB_DOCKER_INFO='Ubuntu ["name=seccomp","name=rootless"]' \
  bash ./setup.sh > "$WORK/out/o" 2> "$WORK/out/e") || true
{ grep -qF 'already listens on 127.0.0.1:8080' "$WORK/out/o" && grep -qF 'needs MDMesh on a loopback address' "$WORK/out/o" \
  && grep -qF 'This Docker (rootless, or Docker Desktop)' "$WORK/out/o" && grep -qxF 'MDMESH_BIND=10.0.0.5:8080' "$WORK/src8/.env" \
  && grep -qxF 'TRUSTED_PROXIES="192.0.2.10"' "$WORK/src8/.env"; } || { fail "setup.sh's proxy questions: busy address, host off loopback or rootless Docker not handled"; show; }
ok
# Non-interactive, the default address in use stops the installer instead of asking forever.
rm -f -- "$WORK/src8/.env"
(cd "$WORK/src8" && env -i PATH="$WORK/stubs-busy8080:$WORK/stubs:$PATH" HOME="$WORK" STUB_LOG="$WORK/docker.log" STUB_DOCKER_OK=1 \
  MDMESH_NONINTERACTIVE=1 MDMESH_MODE=proxy MDMESH_HOST=mdm.example.com bash ./setup.sh < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
{ grep -qF 'MDMESH_BIND=127.0.0.1:8080: something on this machine already listens there' "$WORK/out/e" && [ ! -e "$WORK/src8/.env" ]; } \
  || { fail "a non-interactive setup.sh with its default address in use does not stop before writing .env"; show; }
ok

# quickstart.sh over an existing directory. It must be this user's (or root's) and nobody else's to write, with what is
# in it, or nothing in it is read: a directory someone else made (group- or world-writable, another user's) is refused.
# An install it didn't finish (.quickstart-unfinished) is resumed with its private .env, but nothing else on disk is
# trusted: each .env value is checked, the release is verified again against its signed manifest (a throwaway key,
# MDMESH_RELEASE_PUBKEY; a curl stub serving the release and this checkout's files), the files are fetched and checked
# again over the ones there (a tampered db.sh is replaced, never sourced), and .env's image pins must be the manifest's.
# Any other .env is left alone.
FIX="$WORK/fix"; mkdir -p "$FIX/rel" "$FIX/raw/install/lib" "$FIX/raw/install/sql" "$WORK/stubs-rel"
for f in docker-compose.release.yml docker-compose.domain.yml docker-compose.proxy.yml install/sql/hmdm_init.en.sql \
         install/sql/post_seed.sql install/lib/db.sh; do cp -- "$REPO/$f" "$FIX/raw/$f"; done
D1=$(printf 'a%.0s' {1..64}); D2=$(printf 'b%.0s' {1..64}); D3=$(printf 'c%.0s' {1..64})
{
  # Both platforms, so the resume tests run on an Arm64 host too (no components.platforms means linux/amd64 only).
  printf '{\n  "version": "0.6.0",\n  "components": {\n    "platforms": "linux/amd64,linux/arm64",\n    "images": {\n'
  printf '      "server": "ghcr.io/me/mdmesh-server@sha256:%s",\n      "web": "ghcr.io/me/mdmesh-web@sha256:%s",\n' "$D1" "$D2"
  printf '      "supervisor": "ghcr.io/me/mdmesh-supervisor@sha256:%s"\n    }\n  },\n  "files": {\n' "$D3"
  for f in docker-compose.release.yml docker-compose.domain.yml docker-compose.proxy.yml install/sql/hmdm_init.en.sql \
           install/sql/post_seed.sql; do printf '    "%s": "%s",\n' "$f" "$(sha256sum "$FIX/raw/$f" | cut -d' ' -f1)"; done
  printf '    "install/lib/db.sh": "%s"\n  }\n}\n' "$(sha256sum "$FIX/raw/install/lib/db.sh" | cut -d' ' -f1)"
} > "$FIX/rel/manifest.json"
minisign -G -W -p "$FIX/k.pub" -s "$FIX/k.key" > /dev/null
minisign -S -s "$FIX/k.key" -m "$FIX/rel/manifest.json" -x "$FIX/rel/manifest.json.minisig" > /dev/null
# The same release as one from before 0.6.0 publishes it: no docker-compose.proxy.yml (STUB_REL=rel-noproxy).
mkdir -p "$FIX/rel-noproxy"; grep -v '"docker-compose.proxy.yml"' "$FIX/rel/manifest.json" > "$FIX/rel-noproxy/manifest.json"
minisign -S -s "$FIX/k.key" -m "$FIX/rel-noproxy/manifest.json" -x "$FIX/rel-noproxy/manifest.json.minisig" > /dev/null
# shellcheck disable=SC2016  # the stub's own variables
printf '#!/bin/sh\nout=; url=\nwhile [ $# -gt 0 ]; do case "$1" in -o) out=$2; shift ;; https://*) url=$1 ;; esac; shift; done\ncase "$url" in\n  https://github.com/me/fork/releases/download/v0.6.0/*) f="%s/${STUB_REL:-rel}/${url##*/}" ;;\n  https://raw.githubusercontent.com/me/fork/v0.6.0/*) f="%s/raw/${url#https://raw.githubusercontent.com/me/fork/v0.6.0/}" ;;\n  *) exit 22 ;;\nesac\n[ -f "$f" ] && cp "$f" "$out"\n' "$FIX" "$FIX" > "$WORK/stubs-rel/curl"
chmod +x "$WORK/stubs-rel/curl"
qs_env() {  # the .env lines of an unfinished quick start of v0.6.0 of me/fork in domain mode, then any NAME=VALUE overrides
  local l k
  for l in DB_PASSWORD=keepme BASE_URL=https://mdm.example.com IMAGE_OWNER=me GITHUB_REPO=me/fork CURRENT_VERSION=0.6.0 \
           "SERVER_VERSION=0.6.0@sha256:$D1" "WEB_VERSION=0.6.0@sha256:$D2" "SUPERVISOR_VERSION=0.6.0@sha256:$D3" \
           COMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml COMPOSE_PROFILES=; do
    k=${l%%=*}
    case " $* " in *" $k="*) ;; *) printf '%s\n' "$l" ;; esac
  done
  for l in "$@"; do printf '%s\n' "$l"; done
}
qs_dir() {  # qs_dir: a fresh $WORK/qs7/mdmesh holding an unfinished install, its db.sh tampered with
  rm -rf -- "$WORK/qs7"; mkdir -p "$WORK/qs7/mdmesh/install/lib"
  printf 'touch "%s/PWNED"\n' "$WORK" > "$WORK/qs7/mdmesh/install/lib/db.sh"
  : > "$WORK/qs7/mdmesh/.quickstart-unfinished"
}
qs_run() {  # qs_run [NAME=VALUE...]: quickstart.sh in $WORK/qs7 (stdin empty), the release served by the stub
  : > "$WORK/docker.log"; rm -f -- "$WORK/PWNED"
  (cd "$WORK/qs7" && env -i PATH="$WORK/stubs-rel:$WORK/stubs:$PATH" HOME="$WORK" STUB_LOG="$WORK/docker.log" STUB_DOCKER_OK=1 \
    MDMESH_RELEASE_PUBKEY="$(sed -n 2p "$FIX/k.pub")" "$@" bash "$REPO/quickstart.sh" < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
}
# Resumed: verified again, the tampered db.sh replaced by the release's (never sourced), then on to `docker compose pull`.
qs_dir; qs_env > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"
qs_run MDMESH_MODE=proxy
{ grep -qF 'finishing the install in' "$WORK/out/o" && grep -qF 'answers are not used' "$WORK/out/o" \
  && grep -qF 'Verifying release v0.6.0 of me/fork' "$WORK/out/o" && [ ! -e "$WORK/PWNED" ] \
  && cmp -s "$REPO/install/lib/db.sh" "$WORK/qs7/mdmesh/install/lib/db.sh" && grep -q '/docker compose pull$' "$WORK/docker.log" \
  && grep -qxF DB_PASSWORD=keepme "$WORK/qs7/mdmesh/.env" && grep -qF 'MDMESH_DIR=' "$WORK/out/e"; } \
  || { fail "quickstart.sh does not resume an unfinished install by verifying the release again and replacing its files"; show; }
ok
# Refused before anything is sourced or run: a value .env must not hold, image pins that aren't the manifest's, an .env
# others may read, a directory or a file in it that others may write.
# Each case is "CASE#MESSAGE": the refusal must be this case's own, not an earlier check's.
for c in 'BASE_URL=https://x.example.com/'"'"';drop#.env: BASE_URL is not valid' 'BASE_URL=https://a\b.example.com#.env: BASE_URL is not valid' \
         'IMAGE_OWNER=Me#.env: IMAGE_OWNER' 'GITHUB_REPO=../x#.env: GITHUB_REPO' \
         'CURRENT_VERSION=0.6.0;id#.env: CURRENT_VERSION is not a release version' \
         'COMPOSE_FILE=docker-compose.yml:evil.yml#not a hosting mode the quick start writes' \
         "WEB_VERSION=0.6.0@sha256:$D1#.env: WEB_VERSION is not the image" \
         'perm:.env:644#must belong to you (or root) and be private to you' 'perm:.:775#chmod go-w' 'perm:install/lib/db.sh:666#chmod go-w' \
         'COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml|MDMESH_BIND=127.0.0.1:8080|TRUSTED_PROXIES="0.0.0.0/0"#.env: TRUSTED_PROXIES has "0.0.0.0/0"' \
         'COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml|MDMESH_BIND=0.0.0.0:8080|TRUSTED_PROXIES="host"#.env: TRUSTED_PROXIES and MDMESH_BIND: host' \
         'COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml|MDMESH_BIND=127.0.0.1:8080|TRUSTED_PROXIES=#.env: TRUSTED_PROXIES is empty' \
         'MDMESH_SITE_DIR=/etc#.env: MDMESH_SITE_DIR is not this install' 'MDMESH_SITE_DIR=site.d#.env: MDMESH_SITE_DIR is not this install'; do
  want=${c##*#}; c=${c%#*}
  qs_dir
  case "$c" in
    perm:*) qs_env > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"
            p=${c#perm:}; chmod "${p##*:}" "$WORK/qs7/mdmesh/${p%:*}" ;;
    *) IFS='|' read -r -a kv <<< "$c"; qs_env "${kv[@]}" > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env" ;;
  esac
  qs_run
  { [ ! -e "$WORK/PWNED" ] && ! grep -q '/docker compose ' "$WORK/docker.log" && grep -qF 'Nothing was changed.' "$WORK/out/e" \
    && grep -qF -- "$want" "$WORK/out/e"; } || { fail "quickstart.sh resumes over a bad install directory or .env ($c), or not for its own reason ($want)"; show; }
  ok
done
# A proxy-mode install resumes with its own sound values: its site.d, and a proxy list that fits its bind.
qs_dir; qs_env COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml MDMESH_BIND=127.0.0.1:8080 'TRUSTED_PROXIES="host 192.0.2.10"' \
  "MDMESH_SITE_DIR=$WORK/qs7/mdmesh/site.d" > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"
qs_run
grep -q '/docker compose pull$' "$WORK/docker.log" || { fail "quickstart.sh does not resume a sound proxy-mode install"; show; }
ok
# Its .env read as compose reads it: a " # note" after a value isn't part of it.
qs_dir; qs_env 'CURRENT_VERSION=0.6.0 # pinned' 'GITHUB_REPO=me/fork   # ours' > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"
qs_run
grep -q '/docker compose pull$' "$WORK/docker.log" || { fail "quickstart.sh does not resume an install whose .env has a comment after a value"; show; }
ok
# Resumed after its earlier run had seeded the database (it stopped between the seed and the report): the seed doesn't
# run again, the repairs do, and the admin gets a new temporary password unless they had set their own. A docker stub
# that answers like a running stack, the database seeded; STUB_ADMIN is what the admin-account query says (other knobs:
# STUB_STATE the database's settings/devices count, STUB_REPAIRS the repairs' postcondition, STUB_EDGE the edge).
mkdir -p "$WORK/stubs-qsfull"
# shellcheck disable=SC2016  # the stub's own variables
printf '%s\n' '#!/bin/sh' 'echo "docker $*" >> "$STUB_LOG"' 'case "$*" in' \
  '  ps\ *|"volume ls"*) ;;' \
  '  "info --format {{.Architecture}}") exit 1 ;;' \
  '  *"exec -T -u mdmesh server cat"*) echo OK ;;' \
  '  *psql*"FROM settings)||"*) echo "${STUB_STATE:-1/0}" ;;' \
  '  *psql*"SELECT passwordreset FROM users"*) echo "$STUB_ADMIN" ;;' \
  '  *psql*"createnewdevices AND"*) echo "${STUB_REPAIRS:-1}" ;;' \
  '  *psql*) cat >> "$STUB_LOG.stdin" ;;' \
  '  "compose up -d") echo "up env MDMESH_BIND=${MDMESH_BIND-unset} COMPOSE_ENV_FILES=${COMPOSE_ENV_FILES-unset}" >> "$STUB_LOG" ;;' \
  '  "compose ps -a -q caddy") echo edge1 ;;' \
  '  "inspect "*) echo "${STUB_EDGE:-running} t0" ;;' \
  'esac' 'exit 0' > "$WORK/stubs-qsfull/docker"
chmod +x "$WORK/stubs-qsfull/docker"
for a in t f; do
  qs_dir; qs_env > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"; : > "$WORK/docker.log.stdin"
  : > "$WORK/docker.log"; rm -f -- "$WORK/PWNED"
  RC=0; (cd "$WORK/qs7" && env -i PATH="$WORK/stubs-rel:$WORK/stubs-qsfull:$PATH" HOME="$WORK" STUB_LOG="$WORK/docker.log" \
    STUB_ADMIN=$a MDM_EDGE_SETTLE=0 MDMESH_RELEASE_PUBKEY="$(sed -n 2p "$FIX/k.pub")" bash "$REPO/quickstart.sh" \
    < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || RC=$?
  seed_ran=0; grep -q '_ADMIN_EMAIL_\|INSERT INTO settings' "$WORK/docker.log.stdin" && seed_ran=1
  { [ "$RC" = 0 ] && grep -qF '== MDMesh is up ==' "$WORK/out/o" && [ "$seed_ran" = 0 ] && grep -q 'createnewdevices AND' "$WORK/docker.log" \
    && [ ! -e "$WORK/qs7/mdmesh/.quickstart-unfinished" ]; } || { fail "a resume after a committed seed (admin $a) does not finish"; show; }
  if [ "$a" = t ]; then
    { grep -q "UPDATE users SET password=:'h', passwordreset=true" "$WORK/docker.log.stdin" && grep -qE 'Password: +[0-9a-f]{48} ' "$WORK/out/o"; } \
      || { fail "a resume after a committed seed does not give the admin a new temporary password"; show; }
  else
    { ! grep -q 'UPDATE users SET password' "$WORK/docker.log.stdin" && grep -qF '(the one you set; unchanged)' "$WORK/out/o"; } \
      || { fail "a resume after a committed seed replaces a password the admin had set"; show; }
  fi
  ok
done
qs_full() {  # qs_full [NAME=VALUE...]: quickstart.sh in $WORK/qs7 against the running-stack stub; its status in QS_RC
  QS_RC=0; : > "$WORK/docker.log.stdin"; : > "$WORK/docker.log"; rm -f -- "$WORK/PWNED"
  (cd "$WORK/qs7" && env -i PATH="$WORK/stubs-rel:$WORK/stubs-qsfull:$PATH" HOME="$WORK" STUB_LOG="$WORK/docker.log" \
    STUB_ADMIN=t MDM_EDGE_SETTLE=0 MDMESH_RELEASE_PUBKEY="$(sed -n 2p "$FIX/k.pub")" "$@" bash "$REPO/quickstart.sh" \
    < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || QS_RC=$?
}
qs_unfinished() { qs_dir; qs_env "$@" > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"; }
# Stopped in the repairs: not reported up, the marker kept (removed only at the very end), the way back said.
qs_unfinished; qs_full STUB_REPAIRS=0
{ [ "$QS_RC" != 0 ] && ! grep -qF '== MDMesh is up ==' "$WORK/out/o" && [ -e "$WORK/qs7/mdmesh/.quickstart-unfinished" ] && grep -qF 'MDMESH_DIR=' "$WORK/out/e"; } \
  || { fail "a resume whose repairs fail is reported up, or loses its marker"; show; }
ok
# An edge that doesn't stay up: not reported up, the marker kept, and nothing seeded or set before it.
qs_unfinished; qs_full STUB_EDGE=restarting STUB_STATE=0/0
{ [ "$QS_RC" != 0 ] && ! grep -qF '== MDMesh is up ==' "$WORK/out/o" && [ -e "$WORK/qs7/mdmesh/.quickstart-unfinished" ] \
  && grep -qF 'The edge (caddy) did not stay up' "$WORK/out/e" && ! grep -q 'INSERT INTO\|UPDATE users' "$WORK/docker.log.stdin"; } \
  || { fail "a quick start whose edge doesn't stay up is reported up, loses its marker, or seeds"; show; }
ok
# .env wins over an exported answer that compose also reads (a first run's MDMESH_BIND, fixed in .env since).
qs_unfinished COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml MDMESH_BIND=127.0.0.1:8082 'TRUSTED_PROXIES="host"' \
  "MDMESH_SITE_DIR=$WORK/qs7/mdmesh/site.d"
# Also not stopped by the rules across answers, which a resume doesn't use (0.0.0.0 with the default host).
qs_full MDMESH_MODE=proxy MDMESH_BIND=0.0.0.0:8081
{ [ "$QS_RC" = 0 ] && grep -qF 'up env MDMESH_BIND=unset ' "$WORK/docker.log" && grep -qF '== MDMesh is up ==' "$WORK/out/o"; } \
  || { fail "an exported MDMESH_BIND reaches compose over .env's, or stops a resume"; show; }
ok
# Also when .env writes it "export MDMESH_BIND=..." (a hand edit): the value the resume checked is the one compose gets.
qs_unfinished COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml 'export MDMESH_BIND=127.0.0.1:8082' 'TRUSTED_PROXIES="host"' \
  "MDMESH_SITE_DIR=$WORK/qs7/mdmesh/site.d"
qs_full MDMESH_MODE=proxy MDMESH_BIND=0.0.0.0:443
{ [ "$QS_RC" = 0 ] && grep -qF 'up env MDMESH_BIND=unset ' "$WORK/docker.log"; } \
  || { fail "an exported MDMESH_BIND reaches compose over .env's \"export MDMESH_BIND=\" line"; show; }
ok
# Headless with nothing but the directory (and no questions), as the resume hint says: it finishes, without the answers
# a new install needs. And an exported COMPOSE_ENV_FILES doesn't reach compose (it would read other files than .env).
qs_unfinished; printf 'COMPOSE_PROJECT_NAME=other\n' > "$WORK/other.env"
qs_full MDMESH_NONINTERACTIVE=1 COMPOSE_ENV_FILES="$WORK/other.env"
{ [ "$QS_RC" = 0 ] && grep -qF '== MDMesh is up ==' "$WORK/out/o" && grep -qF 'COMPOSE_ENV_FILES=unset' "$WORK/docker.log"; } \
  || { fail "a headless resume with only MDMESH_NONINTERACTIVE=1 doesn't finish, or COMPOSE_ENV_FILES reaches compose"; show; }
ok
# Two at once in one directory (a resume after a hang-up, run twice): the second is refused while the first runs.
qs_unfinished; flock "$WORK/qs7/mdmesh/.quickstart.lock" sleep 30 & held=$!
for _ in $(seq 1 50); do ! flock -n "$WORK/qs7/mdmesh/.quickstart.lock" true || { sleep 0.1; continue; }; break; done
qs_full
kill "$held" > "$WORK/kill.log" 2>&1 || true; wait "$held" > "$WORK/kill.log" 2>&1 || true
{ grep -qF 'Another quick start is running in' "$WORK/out/e" && ! grep -q 'compose pull\|compose up' "$WORK/docker.log"; } \
  || { fail "a second quick start in the same directory is not refused while the first runs"; show; }
ok
# Without flock (macOS): the lock is the running quick start's PID. A PID that is no quick start (reused after a reboot,
# say) doesn't hold it; one that is, does, and the refusal names the file. A PATH of this host's commands but flock.
mkdir -p "$WORK/noflock"
for c in /usr/bin/* /bin/*; do n=${c##*/}; [ "$n" = flock ] || [ -e "$WORK/noflock/$n" ] || [ -L "$WORK/noflock/$n" ] || ln -s "$c" "$WORK/noflock/$n"; done
qs_noflock() {
  : > "$WORK/docker.log"
  (cd "$WORK/qs7" && env -i PATH="$WORK/stubs-rel:$WORK/stubs:$WORK/noflock" HOME="$WORK" STUB_LOG="$WORK/docker.log" STUB_DOCKER_OK=1 \
    MDMESH_RELEASE_PUBKEY="$(sed -n 2p "$FIX/k.pub")" bash "$REPO/quickstart.sh" < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
}
qs_unfinished; sleep 60 & other_pid=$!; echo "$other_pid" > "$WORK/qs7/mdmesh/.quickstart.lock"
qs_noflock
kill "$other_pid" > "$WORK/kill.log" 2>&1 || true; wait "$other_pid" > "$WORK/kill.log" 2>&1 || true
{ ! grep -qF 'Another quick start is running' "$WORK/out/e" && grep -q '/docker compose pull$' "$WORK/docker.log"; } \
  || { fail "without flock, a stale PID (no quick start) in .quickstart.lock refuses a resume"; show; }
ok
if [ -n "$(command -v ps || true)" ]; then
qs_unfinished; bash -c 'sleep 60; true' quickstart.sh & qs_pid=$!; echo "$qs_pid" > "$WORK/qs7/mdmesh/.quickstart.lock"
qs_noflock
kill "$qs_pid" > "$WORK/kill.log" 2>&1 || true; wait "$qs_pid" > "$WORK/kill.log" 2>&1 || true
{ grep -qF "Another quick start is running in $WORK/qs7/mdmesh (PID $qs_pid)" "$WORK/out/e" && grep -qF '.quickstart.lock' "$WORK/out/e" \
  && ! grep -q 'compose pull' "$WORK/docker.log"; } || { fail "without flock, a running quick start's PID does not refuse, naming the lock file"; show; }
ok
fi
# A new install over a seeded database is refused, also with RESUMING=1 in the environment (only a resume finishes one).
mkdir -p "$WORK/qs10"; rm -rf -- "$WORK/qs10/mdmesh"; : > "$WORK/docker.log"; : > "$WORK/docker.log.stdin"
(cd "$WORK/qs10" && env -i PATH="$WORK/stubs-rel:$WORK/stubs-qsfull:$PATH" HOME="$WORK" STUB_LOG="$WORK/docker.log" STUB_ADMIN=t \
  MDM_EDGE_SETTLE=0 MDMESH_RELEASE_PUBKEY="$(sed -n 2p "$FIX/k.pub")" RESUMING=1 MDMESH_NONINTERACTIVE=1 MDMESH_MODE=domain \
  MDMESH_HOST=mdm.example.com MDMESH_REPO=me/fork MDMESH_IMAGE_OWNER=me MDMESH_VERSION=0.6.0 bash "$REPO/quickstart.sh" \
  < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
{ grep -qF 'This database is already seeded' "$WORK/out/e" && ! grep -q 'UPDATE users' "$WORK/docker.log.stdin"; } \
  || { fail "a new quick start over a seeded database is not refused"; show; }
ok
# Behind your own proxy with a release that has no proxy mode (its manifest lists no docker-compose.proxy.yml):
# refused before anything is written.
rm -rf -- "$WORK/qs10/mdmesh"; : > "$WORK/docker.log"
(cd "$WORK/qs10" && env -i PATH="$WORK/stubs-rel:$WORK/stubs-qsfull:$PATH" HOME="$WORK" STUB_LOG="$WORK/docker.log" STUB_REL=rel-noproxy \
  MDMESH_RELEASE_PUBKEY="$(sed -n 2p "$FIX/k.pub")" MDMESH_NONINTERACTIVE=1 MDMESH_MODE=proxy MDMESH_HOST=mdm.example.com \
  MDMESH_REPO=me/fork MDMESH_IMAGE_OWNER=me MDMESH_VERSION=0.6.0 bash "$REPO/quickstart.sh" < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
{ grep -qF 'has no proxy mode' "$WORK/out/e" && [ ! -e "$WORK/qs10/mdmesh/.env" ] && [ ! -e "$WORK/qs10/mdmesh/.quickstart-unfinished" ]; } \
  || { fail "proxy mode with a release that has none is not refused before .env"; show; }
ok
# A login umask of 0002 (Debian and Ubuntu's default for a regular user): a new quick start still makes its directory
# writable by this user only. A path that already exists and others may write (an install directory made by hand under
# that umask, a file in it, its .env) is refused with the chmod to run, never made private and then trusted.
mkdir -p "$WORK/qs9"; rm -rf -- "$WORK/qs9/mdmesh"; : > "$WORK/docker.log"
(umask 002; cd "$WORK/qs9" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" STUB_LOG="$WORK/docker.log" STUB_DOCKER_OK=1 \
  bash "$REPO/quickstart.sh" < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
{ grep -qF 'How will your phones reach MDMesh?' "$WORK/out/o" && [ "$(stat -c %a "$WORK/qs9/mdmesh")" = 755 ]; } \
  || { fail "a new quick start under umask 0002 refuses its own directory, or leaves it group-writable"; show; }
ok
for p in .:775 install:775 install/lib/db.sh:664 .env:660; do
  qs_dir; qs_env > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"; chmod "${p##*:}" "$WORK/qs7/mdmesh/${p%:*}"
  (umask 002; qs_run)
  { ! grep -q '/docker compose ' "$WORK/docker.log" && [ ! -e "$WORK/PWNED" ] && grep -qF 'chmod go-w' "$WORK/out/e" \
    && [ "$(stat -c %a "$WORK/qs7/mdmesh/${p%:*}")" = "${p##*:}" ]; } \
    || { fail "quickstart.sh trusts its own pre-existing group-writable ${p%:*}, or changes it"; show; }
  ok
done
# A finished install's directory (no marker) is left alone, saying so first, also when a 0002 umask left it open.
qs_dir; rm -f -- "$WORK/qs7/mdmesh/.quickstart-unfinished"; qs_env > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"
chmod 775 "$WORK/qs7/mdmesh"; qs_run
{ grep -qF "Don't delete that .env" "$WORK/out/e" && ! grep -qF 'chmod go-w' "$WORK/out/e" && ! grep -q '/docker compose ' "$WORK/docker.log"; } \
  || { fail "quickstart.sh over a finished install in an open directory does not say it leaves it alone, first"; show; }
ok
# A directory another user owns (one found on this system, not world-writable), and /tmp itself: refused.
other=$(find /var /usr /etc -maxdepth 3 -type d ! -user 0 ! -user "$(id -u)" ! -perm -002 -perm -001 -print -quit 2>/dev/null || true)
for d in /tmp ${other:+"$other"}; do
  RC=0; (cd "$WORK" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" STUB_DOCKER_OK=1 MDMESH_DIR="$d" \
    bash "$REPO/quickstart.sh" < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || RC=$?
  { [ "$RC" != 0 ] && grep -qF 'must belong to you (or root)' "$WORK/out/e"; } || { fail "quickstart.sh installs into $d"; show; }
  ok
done
# Another .env (no marker) is left alone, saying never to delete it while the volumes exist.
qs_dir; rm -f -- "$WORK/qs7/mdmesh/.quickstart-unfinished"; qs_env > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"
qs_run
{ grep -qF "Don't delete that .env" "$WORK/out/e" && grep -qxF DB_PASSWORD=keepme "$WORK/qs7/mdmesh/.env" && [ ! -e "$WORK/PWNED" ]; } \
  || { fail "quickstart.sh over a finished install does not leave it alone, saying how"; show; }
ok

# One stack per Docker project (install/lib/stack.sh): a quick start or setup.sh in a second directory never touches an
# install elsewhere. Refused before any question or compose command: another directory's containers (named), containers
# only the updater recreated (/project), and, for a new .env, volumes with no containers. A re-run in the install's own
# directory (its containers, the updater's /project ones beside them) goes on.
stack_refused() {  # stack_refused WHAT WANT: the run was refused with WANT in its errors, and ran no compose command
  { grep -qF -- "$2" "$WORK/out/e" && grep -qF 'Nothing was changed.' "$WORK/out/e" && ! grep -q '/docker compose ' "$WORK/docker.log"; } \
    || { fail "$1 is not refused before compose runs (want: $2)"; show; }
  ok
}
mkdir -p "$WORK/qs9"
qs_fresh() {  # qs_fresh [NAME=VALUE...]: a new quick start in $WORK/qs9/mdmesh (stdin empty)
  rm -rf -- "$WORK/qs9/mdmesh"; : > "$WORK/docker.log"
  (cd "$WORK/qs9" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" STUB_LOG="$WORK/docker.log" STUB_DOCKER_OK=1 "$@" \
    bash "$REPO/quickstart.sh" < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || true
}
qs_fresh STUB_DOCKER_PS="$(printf '/srv/mdmesh\n/project\n/srv/mdmesh')"
stack_refused "a new quick start beside an install in /srv/mdmesh" 'An MDMesh install already exists in /srv/mdmesh (Docker project mdmesh)'
grep -qF 'Settings → Updates' "$WORK/out/e" && grep -q 'label=com.docker.compose.project=mdmesh' "$WORK/docker.log" && [ ! -e "$WORK/qs9/mdmesh/.env" ] \
  || { fail "the refusal does not say how to update the other install, or asked the wrong project"; show; }
qs_fresh STUB_DOCKER_PS=/project
stack_refused "a new quick start beside an install only the updater's labels name" "'docker compose ls' shows where"
qs_fresh STUB_DOCKER_VOLUMES="$(printf 'mdmesh_pgdata\nmdmesh_mdmeshdata')"
stack_refused "a new quick start over another install's volumes" 'docker volume rm mdmesh_pgdata mdmesh_mdmeshdata'
# No other stack: on to the first question.
qs_fresh
grep -qF 'How will your phones reach MDMesh?' "$WORK/out/o" || { fail "a new quick start with no other stack does not ask"; show; }
ok
# Resuming its own unfinished install: its own containers and volumes are expected; another directory's are not.
qs_dir; qs_env > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"
qs_run STUB_DOCKER_PS="$(printf '%s\n/project' "$WORK/qs7/mdmesh")" STUB_DOCKER_VOLUMES=mdmesh_pgdata
grep -q '/docker compose pull$' "$WORK/docker.log" || { fail "quickstart.sh does not resume beside its own containers"; show; }
ok
qs_dir; qs_env > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"
mkdir -p "$WORK/other-install"   # another install's directory, still there
qs_run STUB_DOCKER_PS="$WORK/other-install"
stack_refused "a resumed quick start beside an install in another directory" "An MDMesh install already exists in $WORK/other-install"
# A container of the project that names no directory (a compose too old to record it): refused, even beside its own.
qs_dir; qs_env > "$WORK/qs7/mdmesh/.env"; chmod 600 "$WORK/qs7/mdmesh/.env"
qs_run STUB_DOCKER_PS="$(printf '%s\n-' "$WORK/qs7/mdmesh")"
stack_refused "a resumed quick start beside a container that names no directory" "'docker compose ls' shows where"
# mdm_edge_up (install/lib/stack.sh): the edge running and the same run a moment later; otherwise it fails, showing the
# edge's last log lines. A stub docker: STUB_EDGE says what inspect sees.
mkdir -p "$WORK/stubs-edge"
# shellcheck disable=SC2016  # the stub's own variables
printf '%s\n' '#!/bin/sh' 'case "$*" in' \
  '  "compose ps -a -q caddy") [ "$STUB_EDGE" = gone ] || echo edge1 ;;' \
  '  "inspect "*) n=$(cat "$STUB_N" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$STUB_N"' \
  '    case "$STUB_EDGE" in restarting) echo "restarting t0" ;; flapping) echo "running t$n" ;; *) echo "running t0" ;; esac ;;' \
  '  "compose logs --tail 20 caddy") echo "Error: adapting config: site.d/bad.caddy:1 - unrecognized directive" ;;' \
  'esac' > "$WORK/stubs-edge/docker"
chmod +x "$WORK/stubs-edge/docker"
for e in up restarting flapping gone; do
  rm -f -- "$WORK/edge.n"
  RC=0; env -i PATH="$WORK/stubs-edge:$PATH" STUB_EDGE=$e STUB_N="$WORK/edge.n" MDM_EDGE_SETTLE=0 \
    bash -c 'err() { printf "%s\n" "$*" >&2; }; . "$1"; mdm_edge_up' _ "$REPO/install/lib/stack.sh" > "$WORK/out/o" 2> "$WORK/out/e" || RC=$?
  if [ "$e" = up ]; then [ "$RC" = 0 ] || { fail "mdm_edge_up: an edge that stays up is not up"; show; }
  else { [ "$RC" != 0 ] && grep -qF 'The edge (caddy) did not stay up' "$WORK/out/e" && grep -qF 'site.d/bad.caddy' "$WORK/out/e"; } \
    || { fail "mdm_edge_up: an edge that is $e is up, or no logs shown"; show; }; fi
  ok
done
# setup.sh: a new .env (a fresh clone) beside another install or over its volumes; a re-run beside its own stack goes on,
# beside another directory's it is refused.
rm -rf -- "$WORK/src9"; mkdir -p "$WORK/src9/install"; cp_setup "$WORK/src9"; cp -R -- "$REPO/install/lib" "$WORK/src9/install/"
setup_run "$WORK/src9" MDMESH_NONINTERACTIVE=1 MDMESH_MODE=domain MDMESH_HOST=mdm.example.com STUB_DOCKER_PS=/opt/mdmesh
stack_refused "setup.sh in a fresh clone beside an install in /opt/mdmesh" 'An MDMesh install already exists in /opt/mdmesh'
[ ! -e "$WORK/src9/.env" ] || { fail "setup.sh wrote a .env before refusing"; show; }
setup_run "$WORK/src9" MDMESH_NONINTERACTIVE=1 MDMESH_MODE=domain MDMESH_HOST=mdm.example.com STUB_DOCKER_VOLUMES=mdmesh_pgdata
stack_refused "setup.sh in a fresh clone over another install's volumes" 'docker volume rm mdmesh_pgdata'
setup_run "$WORK/src9" MDMESH_NONINTERACTIVE=1 MDMESH_MODE=domain MDMESH_HOST=mdm.example.com
[ "$(up_args)" = '-f docker-compose.yml -f docker-compose.domain.yml' ] || { fail "setup.sh with no other stack does not start one"; show; }
ok
setup_run "$WORK/src9" STUB_DOCKER_PS="$(printf '%s\n/project' "$WORK/src9")" STUB_DOCKER_VOLUMES=mdmesh_pgdata
[ "$(up_args)" = '-f docker-compose.yml -f docker-compose.domain.yml' ] || { fail "a setup.sh re-run beside its own stack does not go on"; show; }
ok
setup_run "$WORK/src9" STUB_DOCKER_PS="$WORK/other-install"
stack_refused "a setup.sh re-run beside an install in another directory" "An MDMesh install already exists in $WORK/other-install"
# Its own containers, made where the clone was before it was moved (a path that is gone): the same install, moved here.
setup_run "$WORK/src9" STUB_DOCKER_PS="$WORK/moved-away-clone"
{ [ "$(up_args)" = '-f docker-compose.yml -f docker-compose.domain.yml' ] && grep -qF "were made in $WORK/moved-away-clone, which is gone" "$WORK/out/o" "$WORK/out/e" \
  && ! grep -qF 'take over' "$WORK/out/e"; } || { fail "a setup.sh re-run of a moved clone is refused as another install"; show; }
ok
# A new install beside such containers is still refused (no .env here: they are another install's).
rm -rf -- "$WORK/src11"; mkdir -p "$WORK/src11/install"; cp_setup "$WORK/src11"; cp -R -- "$REPO/install/lib" "$WORK/src11/install/"
setup_run "$WORK/src11" MDMESH_NONINTERACTIVE=1 MDMESH_MODE=domain MDMESH_HOST=mdm.example.com STUB_DOCKER_PS="$WORK/moved-away-clone"
stack_refused "setup.sh in a fresh clone beside containers whose directory is gone" "An MDMesh install already exists in $WORK/moved-away-clone"

if [ -e "$WORK/stubs.called" ]; then fail "a dry run ran a command that changes the host: $(cat "$WORK/stubs.called")"; fi

# Which tree ran, and a floor on how many checks did (a few depend on the host), so a section that silently ran
# nothing can't pass.
TREE=$(git -c safe.directory='*' -C "$REPO" describe --always --dirty 2>&1) || TREE='?'
TREE="$REPO at $TREE"
if [ "$FAILS" != 0 ]; then echo "installer answers: $FAILS failure(s) ($TREE)" >&2; exit 1; fi
if [ "$CHECKS" -lt 692 ]; then echo "installer answers: only $CHECKS checks ran, expected at least 692 ($TREE)" >&2; exit 1; fi
echo "installer answers: all $CHECKS checks passed ($TREE)"
