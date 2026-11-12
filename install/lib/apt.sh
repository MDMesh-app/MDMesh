#!/usr/bin/env bash
# apt-get for the from-source installers (setup.sh and install/install-native.sh). Source this file; do not execute it.
#
# Another apt run can hold apt's locks for minutes: on a new VM or LXC container, apt-daily (an `apt-get update`, which
# holds /var/lib/apt/lists/lock) and unattended-upgrades (the dpkg locks and /var/cache/apt/archives/lock) start at
# boot. apt itself waits only for the dpkg frontend lock, and only with DPkg::Lock::Timeout: `update` and the archives
# lock fail at once (exit 100, "Could not get lock …"). So mdm_apt runs apt-get again while its output says it could
# not get a lock, for up to MDM_APT_LOCK_WAIT seconds in all, and says it is waiting.

# How long mdm_apt waits, in all, for another apt run to let go of apt's locks (seconds), and the status it returns
# when that run still holds them after it.
MDM_APT_LOCK_WAIT=300
MDM_APT_LOCKED=75
# How often (seconds) it says it is still waiting.
MDM_APT_SAY_EVERY=60

# mdm_apt_say MESSAGE: how mdm_apt tells the user it is waiting (stderr). A script may define its own after sourcing
# this file (the native installer does: its apt steps write their output to its log).
mdm_apt_say() { printf '%s\n' "$1" >&2; }

# _mdm_apt_wait_text: MDM_APT_LOCK_WAIT for people ("5 minutes", "90 seconds").
_mdm_apt_wait_text() {
  if [ $((MDM_APT_LOCK_WAIT % 60)) = 0 ] && [ "$MDM_APT_LOCK_WAIT" -ge 120 ]; then echo "$((MDM_APT_LOCK_WAIT / 60)) minutes"
  else echo "$MDM_APT_LOCK_WAIT seconds"; fi
}

# _mdm_apt_lock_line LINE: LINE is apt saying it could not get one of its locks (another process holds it). Matched on
# the English text and, since apt's messages are translated, on the lock files' paths, which are not; never on a lock
# file apt could not open (not root: that is no reason to wait).
_mdm_apt_lock_line() {
  case "$1" in
    *'Could not open lock file'*|*'(13: '*) return 1 ;;
    *'Could not get lock'*|*'Unable to lock'*|*'is held by process'*|*'Unable to acquire the dpkg frontend lock'*) return 0 ;;
    *E:*/var/lib/dpkg/lock*|*E:*/var/lib/apt/lists/lock*|*E:*/var/cache/apt/archives/lock*) return 0 ;;
  esac
  return 1
}

# mdm_apt ARGS...: apt-get ARGS, non-interactive (changed config files are kept, nothing prompts). Its output (stdout
# and stderr together) goes to stdout as it comes. While another apt run holds one of apt's locks, it runs again every
# 5 seconds, saying so once and then every MDM_APT_SAY_EVERY seconds, for up to MDM_APT_LOCK_WAIT seconds in all.
# apt's lines saying the lock is held are not shown, nor, from the second try on, the lines the first try already
# printed before it found the lock held ("Reading package lists...", the packages it would install). Returns apt-get's
# status, or MDM_APT_LOCKED when the lock was still held at the end: the last try shows apt's lock lines (they name
# the process), then a line saying what to do.
mdm_apt() {
  local start=$SECONDS waited=0 said=-1 last=0 tries=0 rc locked line i seen=() mark=$'\037mdm_apt status '
  while :; do
    [ "$waited" -lt "$MDM_APT_LOCK_WAIT" ] || last=1
    rc='' locked=0 i=0
    # The loop runs in this shell (fed by a process substitution), so it can keep what the first try printed. apt-get's
    # status comes last, after a mark.
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in *"$mark"*) rc=${line##*"$mark"}; line=${line%"$mark"*}; [ -n "$line" ] || continue ;; esac
      if _mdm_apt_lock_line "$line"; then locked=1; [ "$last" = 1 ] || continue; fi
      if [ "$tries" -gt 0 ] && [ "$i" -ge 0 ] && [ "$i" -lt "${#seen[@]}" ] && [ "${seen[$i]}" = "$line" ]; then
        i=$((i + 1)); continue
      fi
      i=-1
      [ "$tries" -gt 0 ] || seen+=("$line")
      printf '%s\n' "$line"
    done < <(st=0; DEBIAN_FRONTEND=noninteractive apt-get -o Dpkg::Options::=--force-confdef \
               -o Dpkg::Options::=--force-confold "$@" 2>&1 || st=$?; printf '%s%s\n' "$mark" "$st")
    case "$rc" in ''|*[!0-9]*) rc=1 ;; esac
    # Done: apt-get succeeded, or failed for a reason other than a lock.
    if [ "$rc" = 0 ] || [ "$locked" = 0 ]; then return "$rc"; fi
    if [ "$last" = 1 ]; then
      mdm_apt_say "Another apt run still holds apt's lock after $(_mdm_apt_wait_text). Let it finish (or stop it), then re-run."
      return "$MDM_APT_LOCKED"
    fi
    if [ "$said" -lt 0 ]; then
      mdm_apt_say "Another apt run holds apt's lock (a new machine's first-boot updates, say): waiting for it, up to $(_mdm_apt_wait_text)…"
      said=$waited
    elif [ $((waited - said)) -ge "$MDM_APT_SAY_EVERY" ]; then
      mdm_apt_say "Still waiting for apt's lock (${waited} s so far)…"
      said=$waited
    fi
    tries=$((tries + 1))
    sleep 5
    waited=$((SECONDS - start))
  done
}
