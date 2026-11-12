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
#   - an example value in the contract is accepted or refused differently by the script, or by the contract's regex.
#
# Every installer run here is a dry run, in a temporary copy (setup.sh and the native installer) or a temporary working
# directory (quickstart.sh), with an empty environment and stdin from /dev/null: nothing is installed, and a question
# the scripts would ask fails the run. Needs: bash, jq, grep, sed, awk.
set -euo pipefail
unset CDPATH
HERE=$(cd -P -- "$(dirname -- "$0")" && pwd)
REPO=$(cd -P -- "$HERE/../.." && pwd)
CONTRACT="$REPO/install/options.json"
command -v jq > /dev/null || { echo "jq is required" >&2; exit 2; }
# Not as root: the native installer refuses a non-root user before it changes anything, a second guard should a dry run
# ever stop working. (The commands that would change the host are stubbed out below as well.)
[ "$(id -u)" != 0 ] || { echo "Run this as an unprivileged user, not root." >&2; exit 2; }
export LC_ALL=C
umask 077   # answers files must be private to be read

WORK=$(mktemp -d)
cleanup() { rm -r -- "$WORK" 2>/dev/null || true; }
trap cleanup EXIT
mkdir -p "$WORK/src/install" "$WORK/qs" "$WORK/out"
cp -- "$REPO/setup.sh" "$WORK/src/"
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

# Each installer's flags in options.json are exactly the ones its argument loop accepts (the "-x|--xx)" patterns of its
# for a in "$@" loop; none for an installer without one).
for inst in $(installer_ids); do
  f="$REPO/$(installer_path "$inst")"
  got=$(awk '/^for a in "\$@"; do/ { f = 1 } f && /^done/ { f = 0 } f' "$f" \
    | { grep -oE '^[[:space:]]*-[-a-zA-Z|]+\)' || true; } | tr -d ' )' | tr '|' '\n' | sort -u)
  want=$(jq -r --arg i "$inst" '.installers[] | select(.id == $i) | .flags[]' "$CONTRACT" | sort -u)
  [ "$got" = "$want" ] || fail "$inst accepts the flags [$(tr "\n" " " <<< "$got")], options.json lists [$(tr "\n" " " <<< "$want")]"
  ok
done

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
  (cd "$WORK/qs" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" MDMESH_DRY_RUN=1 "$@" bash ${XTRACE:+-x} "$script" < /dev/null > "$WORK/out/o" 2> "$WORK/out/e") || RC=$?
}
show() { sed 's/^/    | /' "$WORK/out/o" "$WORK/out/e" >&2; }
# plan_field ID: "<value> <source>" from the plan line of ID.
plan_field() { awk -v id="$1" '$1 == id { print $2, $NF; exit }' "$WORK/out/o"; }
modes_of() { if options_of "$1" | grep -qx mode; then echo tunnel domain; else echo -; fi; }
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
cp -- "$REPO/setup.sh" "$WORK/src2/"; cp -R -- "$REPO/install/lib" "$WORK/src2/install/"
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

# Answers typed at a question get the same rules as pre-set ones: a wrong one is refused (never echoing a token) and
# asked again. A real setup.sh run in a copy, answering on stdin; it stops at the docker stub after writing .env.
for c in "1|mdm.example.com|bad SECRET tok|eyJhIjoiYWJjIn0=|^TUNNEL_TOKEN=eyJhIjoiYWJjIn0=$|is not a Cloudflare Tunnel token" \
         "2|mdm.example.com|ops @SECRET|ops@example.com|^ACME_EMAIL=ops@example.com$|is not an email address"; do
  IFS='|' read -r m h bad good want why <<< "$c"
  mkdir -p "$WORK/src3/install"; cp -- "$REPO/setup.sh" "$WORK/src3/"; cp -R -- "$REPO/install/lib" "$WORK/src3/install/"
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

# A re-run whose .env this user can't read says what to do, instead of a bare "Permission denied": its own .env (mode
# 000) → chmod 600 .env; another user's (here a link to /proc/1/environ: root's, mode 400) → run as them.
mkdir -p "$WORK/src4/install"; cp -- "$REPO/setup.sh" "$WORK/src4/"; cp -R -- "$REPO/install/lib" "$WORK/src4/install/"
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

if [ -e "$WORK/stubs.called" ]; then fail "a dry run ran a command that changes the host: $(cat "$WORK/stubs.called")"; fi

if [ "$FAILS" != 0 ]; then echo "installer answers: $FAILS failure(s)" >&2; exit 1; fi
echo "installer answers: all $CHECKS checks passed"
