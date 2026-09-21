#!/usr/bin/env bash
# Shared database provisioning steps for the three installers (setup.sh, quickstart.sh,
# install/install-native.sh). Source this file; do not execute it.
#
# The caller defines PSQL as an ARRAY that runs psql against the MDMesh database, e.g.
#   PSQL=(docker compose exec -T postgres psql -U mdmesh -d mdmesh)
#   PSQL=(env PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U mdmesh -d mdmesh)
# Every function here is strict (ON_ERROR_STOP) and verifies its own postcondition, so a
# failure is a non-zero return with the psql output on stderr — never a warning.
#
# History: each installer used to carry its own copy of this logic and they drifted. setup.sh
# decided "fresh install" by counting users AFTER first boot, but Liquibase itself inserts the
# admin user, so fresh installs were never seeded (default admin password, no enrollment
# defaults). quickstart.sh never ran the post-seed repairs, so enrollment was off. Keep the
# rules in one place.

# Password format the server expects: users.password = SHA1( UPPER(MD5(raw)) + SALT ).
# SALT must match PasswordUtil.PASS_SALT in the server.
MDM_PASS_SALT='5YdSYHyg2U'

mdm_rand()   { openssl rand -hex 24; }
mdm_pwhash() {
  local md5; md5=$(printf '%s' "$1" | md5sum | awk '{print toupper($1)}')
  printf '%s' "${md5}${MDM_PASS_SALT}" | sha1sum | awk '{print $1}'
}

# Run psql strictly, quiet, tuples-only. Extra args are passed through (-c, -f, stdin).
mdm_psql() { "${PSQL[@]}" -v ON_ERROR_STOP=1 -qAt "$@"; }

# Classify the database. Prints one of:
#   fresh          schema exists, no settings row, no devices  -> seed it
#   seeded         a settings row exists                       -> keep data, repairs only
#   inconsistent   no settings row but devices exist           -> refuse; needs a human
#   unavailable    could not query (no schema yet / no connection)
# Why settings and not users: Liquibase inserts the admin user on first boot, so users is
# never empty after the server has started. Only the seed inserts the settings row.
mdm_db_state() {
  local s
  s=$(mdm_psql -c "SELECT (SELECT count(*) FROM settings)||'/'||(SELECT count(*) FROM devices)" 2>/dev/null | tr -d '[:space:]') || { echo unavailable; return 0; }
  case "$s" in
    0/0) echo fresh ;;
    0/*) echo inconsistent ;;
    */*) echo seeded ;;
    *)   echo unavailable ;;
  esac
}

# Seed a FRESH database: base settings/configurations/apps, then set the admin password and force
# a change on first login. Args: admin-email seed-sql-file admin-password reset-token.
# Postcondition: exactly one settings row, at least one configuration, and the admin row carries
# the password hash we just wrote. Returns 1 (with psql output on stderr) otherwise.
mdm_seed() {
  local email=$1 seed_file=$2 admin_pw=$3 reset_token=$4 out hash st
  hash=$(mdm_pwhash "$admin_pw")
  # --single-transaction: the seed is all-or-nothing, so a failure never leaves a half-seeded database.
  if ! out=$(sed "s/_ADMIN_EMAIL_/${email}/g" "$seed_file" | mdm_psql --single-transaction 2>&1); then
    printf 'seed SQL failed:\n%s\n' "$(printf '%s\n' "$out" | tail -n 20)" >&2; return 1
  fi
  if ! out=$(mdm_psql -c "UPDATE users SET password='${hash}', passwordreset=true, passwordresettoken='${reset_token}' WHERE login='admin'" 2>&1); then
    printf 'setting the admin password failed:\n%s\n' "$out" >&2; return 1
  fi
  st=$(mdm_psql -c "SELECT (SELECT count(*) FROM settings)||'/'||(SELECT count(*) FROM configurations)||'/'||(SELECT count(*) FROM users WHERE login='admin' AND password='${hash}')" 2>/dev/null | tr -d '[:space:]')
  case "$st" in
    1/0/*|1/*/0|0/*) printf 'seed postcondition failed (settings/configurations/admin-with-new-password = %s)\n' "$st" >&2; return 1 ;;
    1/*/1) return 0 ;;
    *)     printf 'seed postcondition unreadable (%s)\n' "$st" >&2; return 1 ;;
  esac
}

# Idempotent repairs that must run on EVERY install and upgrade (enrollment flags, aux-app scrub).
# Arg: post-seed SQL file. Postcondition: on-demand device creation is on and a default
# configuration is set — without both, every /agent/v1/enroll fails.
mdm_post_seed() {
  local file=$1 out st
  if ! out=$(mdm_psql < "$file" 2>&1); then
    printf 'post-seed repairs failed:\n%s\n' "$out" >&2; return 1
  fi
  st=$(mdm_psql -c "SELECT count(*) FROM settings WHERE createnewdevices AND newdeviceconfigurationid IS NOT NULL" 2>/dev/null | tr -d '[:space:]')
  [ "$st" = "1" ] || { printf 'post-seed postcondition failed: enrollment defaults not set (%s)\n' "${st:-?}" >&2; return 1; }
}
